// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Compiles legacy C client sources and extracts reference functions for differential testing.
//!
//! Compiles the legacy C hash table, address vector, and watcher table implementations
//! directly alongside verbatim function extracts from `zookeeper.c`. Stub headers isolate
//! the extracted routines from unrelated networking and session machinery.
//!
//! Function extraction locates each target routine by its exact signature line and captures
//! the body through the closing `}` at column 0, emitting `#line` directives to preserve
//! source locations in compiler diagnostics. Any upstream signature change fails the build
//! immediately rather than silently drifting.
//!
//! The watcher table driver is compiled twice—once with `-fsigned-char` and once with
//! `-funsigned-char`, using preprocessor symbol prefixing to expose both variants in a single
//! test binary regardless of host architecture defaults.
//!
//! On non-Unix targets or when the legacy C source tree is absent, compilation is skipped
//! unless `ZKX_REQUIRE_C=1` is set in the environment (as enforced in CI).

use std::env;
use std::error::Error;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

type BuildResult<T> = Result<T, Box<dyn Error>>;

/// Delimiter rule terminating a source extraction block.
#[derive(Clone, Copy)]
enum End {
    /// Terminates at the first line consisting solely of `}`.
    Function,
    /// Terminates at the first line matching the specified string literal.
    Line(&'static str),
    /// Terminates at the first line not ending with a backslash (`\`) continuation.
    Macro,
}

/// Specification for extracting a contiguous code block from a C source file.
struct Extract {
    name: &'static str,
    start: &'static str,
    end: End,
}

/// Target declarations and functions extracted from `zookeeper.c`, in source appearance order.
const ZOOKEEPER_C_EXTRACTS: &[Extract] = &[
    Extract {
        name: "create mode constants",
        start: "const int ZOO_PERSISTENT = 0;",
        end: End::Line("const int ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL = 6;"),
    },
    Extract {
        name: "ZOOKEEPER_IS_SEQUENCE",
        start: "#define ZOOKEEPER_IS_SEQUENCE(mode) \\",
        end: End::Macro,
    },
    Extract {
        name: "getaddrinfo_errno",
        start: "static int getaddrinfo_errno(int rc) {",
        end: End::Function,
    },
    Extract {
        name: "count_hosts",
        start: "static int count_hosts(char *hosts)",
        end: End::Function,
    },
    Extract {
        name: "resolve_hosts",
        start: "static int resolve_hosts(const zhandle_t *zh, const char *hosts_in, addrvec_t *avec)",
        end: End::Function,
    },
    Extract {
        name: "update_addrs",
        start: "int update_addrs(zhandle_t *zh, const struct timeval *ref_time)",
        end: End::Function,
    },
    Extract {
        name: "get_next_server_in_reconfig",
        start: "static int get_next_server_in_reconfig(zhandle_t *zh)",
        end: End::Function,
    },
    Extract {
        name: "zoo_cycle_next_server",
        start: "void zoo_cycle_next_server(zhandle_t *zh)",
        end: End::Function,
    },
    Extract {
        name: "free_duplicate_path",
        start: "void free_duplicate_path(const char *free_path, const char* path) {",
        end: End::Function,
    },
    Extract {
        name: "prepend_string",
        start: "static char* prepend_string(zhandle_t *zh, const char* client_path) {",
        end: End::Function,
    },
    Extract {
        name: "sub_string",
        start: "char* sub_string(zhandle_t *zh, const char* server_path) {",
        end: End::Function,
    },
    Extract {
        name: "isValidPath",
        start: "static int isValidPath(const char* path, const int mode) {",
        end: End::Function,
    },
];

/// Extraction rule for the DJB2 string hash implementation in `zk_hashtable.c`.
const DJB2_EXTRACT: Extract = Extract {
    name: "string_hash_djb2",
    start: "static unsigned int string_hash_djb2(void *str) ",
    end: End::Function,
};

/// External symbols defined by `zkx_zt.c` and its included `zk_hashtable.c` translation unit.
///
/// Each driver variant renames these symbols with a distinct prefix so both signed and
/// unsigned `char` builds can link into the same binary without duplicate symbol collisions.
const ZT_EXTERNAL_NAMES: &[&str] = &[
    "zkx_zt_reset",
    "zkx_zt_set_default",
    "zkx_zt_set_chroot",
    "zkx_zt_activate",
    "zkx_zt_event",
    "zkx_dlv_get",
    "zkx_zt_has",
    "zkx_zt_remove",
    "zkx_zt_dump",
    "getImpl",
    "getFirstWatcher",
    "clone_watcher_object",
    "create_zk_hashtable",
    "destroy_zk_hashtable",
    "collect_keys",
    "collectWatchers",
    "deliverWatchers",
    "free_watcher_object_list",
    "activateWatcher",
    "deactivateWatcher",
    "removeWatchers",
    "pathHasWatcher",
];

/// Formats an external symbol name for a watcher driver variant using `prefix`.
///
/// Strips any leading `zkx_zt_` or `zkx_` prefix before prepending `prefix` (e.g.,
/// `zkx_zt_reset` with prefix `zkx_zts_` becomes `zkx_zts_reset`).
fn prefixed(prefix: &str, name: &str) -> String {
    let base = name
        .strip_prefix("zkx_zt_")
        .or_else(|| name.strip_prefix("zkx_"))
        .unwrap_or(name);
    format!("{prefix}{base}")
}

/// Compiles the extracted `string_hash_djb2` routine under both `-fsigned-char` and
/// `-funsigned-char`, returning the resulting object file paths.
fn compile_djb2(
    gen_dir: &Path,
    zk_hashtable_c: &Path,
    djb2: &Located,
) -> BuildResult<Vec<PathBuf>> {
    let djb2_lit = c_string_literal(zk_hashtable_c);
    let mut objects = Vec::new();
    for (suffix, flag) in [("signed", "-fsigned-char"), ("unsigned", "-funsigned-char")] {
        let file = gen_dir.join(format!("zkx_djb2_{suffix}.c"));
        let text = format!(
            "#line {} \"{djb2_lit}\"\n{}\nunsigned int zkx_djb2_{suffix}(void *key)\n{{\n    return string_hash_djb2(key);\n}}\n",
            djb2.first, djb2.text
        );
        fs::write(&file, text)?;
        objects.extend(compile_intermediates(&[], &file, flag)?);
    }

    Ok(objects)
}

/// Compiles signed and unsigned `char` variants of the watcher table driver and returns the
/// resulting object file paths.
fn compile_zt_drivers(
    stub: &Path,
    csrc: &Path,
    gen_dir: &Path,
    c_src: &Path,
) -> BuildResult<Vec<PathBuf>> {
    let zt_lit = c_string_literal(&csrc.join("zkx_zt.c"));
    let mut objects = Vec::new();
    for (prefix, flag) in [
        ("zkx_zts_", "-fsigned-char"),
        ("zkx_ztu_", "-funsigned-char"),
    ] {
        let mut text = String::new();
        for name in ZT_EXTERNAL_NAMES {
            writeln!(text, "#define {name} {}", prefixed(prefix, name))?;
        }
        writeln!(text, "#include \"{zt_lit}\"")?;
        let file = gen_dir.join(format!("{prefix}driver.c"));
        fs::write(&file, text)?;
        let includes = [stub, csrc, gen_dir, c_src];
        objects.extend(compile_intermediates(&includes, &file, flag)?);
    }
    Ok(objects)
}

/// Compiles a single generated C translation unit with the specified `char` signedness flag
/// and include search path order.
fn compile_intermediates(includes: &[&Path], file: &Path, flag: &str) -> BuildResult<Vec<PathBuf>> {
    cc::Build::new()
        .includes(includes)
        .file(file)
        .flag(flag)
        .warnings(false)
        .cargo_metadata(false)
        .try_compile_intermediates()
        .map_err(Into::into)
}

/// Extracted source snippet paired with its 1-based inclusive line range in the origin file.
struct Located {
    text: String,
    first: usize,
    last: usize,
}

fn locate(source: &str, file: &str, ex: &Extract) -> BuildResult<Located> {
    let lines: Vec<&str> = source.lines().collect();
    let starts: Vec<usize> = lines
        .iter()
        .enumerate()
        .filter(|(_, l)| **l == ex.start)
        .map(|(i, _)| i)
        .collect();
    let start = match starts.as_slice() {
        [one] => *one,
        [] => return Err(format!("{file}: no line for extract {}", ex.name).into()),
        _ => return Err(format!("{file}: more than one line for extract {}", ex.name).into()),
    };
    let mut end = None;
    for (i, line) in lines.iter().enumerate().skip(start) {
        let done = match ex.end {
            End::Function => *line == "}",
            End::Line(text) => *line == text,
            End::Macro => !line.ends_with('\\'),
        };
        if done {
            end = Some(i);
            break;
        }
    }
    let end = end.ok_or_else(|| format!("{file}: no end for extract {}", ex.name))?;
    // `end >= start` is guaranteed because the search begins at `start`.
    let mut text = lines.get(start..=end).unwrap_or_default().join("\n");
    text.push('\n');
    Ok(Located {
        text,
        first: start.saturating_add(1),
        last: end.saturating_add(1),
    })
}

fn c_string_literal(path: &Path) -> String {
    let s = path.display().to_string();
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn main() -> BuildResult<()> {
    println!("cargo::rustc-check-cfg=cfg(zkx_c)");
    println!("cargo::rerun-if-changed=build.rs");
    println!("cargo::rerun-if-changed=csrc");
    println!("cargo::rerun-if-env-changed=ZKX_C_SRC");
    println!("cargo::rerun-if-env-changed=ZKX_REQUIRE_C");

    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR")?);
    let out = PathBuf::from(env::var("OUT_DIR")?);
    let manifest_rs = out.join("extract_manifest.rs");

    let c_src = match env::var_os("ZKX_C_SRC") {
        Some(p) => PathBuf::from(p),
        None => manifest.join("../../../zookeeper-client-c/src"),
    };
    let is_unix = env::var_os("CARGO_CFG_UNIX").is_some();
    if !is_unix || !c_src.join("zookeeper.c").is_file() {
        if env::var("ZKX_REQUIRE_C").as_deref() == Ok("1") {
            return Err(format!(
                "zk-xcheck: ZKX_REQUIRE_C=1, but the C sources at {} cannot be built on this target",
                c_src.display()
            )
            .into());
        }
        println!("cargo::warning=zk-xcheck: C sources not built; the C cross-checks are off");
        fs::write(
            &manifest_rs,
            "/// Extracts from `zookeeper.c` (empty when C sources are not compiled).\n\
             pub const ZOOKEEPER_C_EXTRACTS: &[(&str, usize, usize)] = &[];\n",
        )?;
        return Ok(());
    }

    let zookeeper_c = c_src.join("zookeeper.c");
    let zk_hashtable_c = c_src.join("zk_hashtable.c");
    for f in [
        &zookeeper_c,
        &zk_hashtable_c,
        &c_src.join("addrvec.c"),
        &c_src.join("addrvec.h"),
        &c_src.join("zk_hashtable.h"),
        &c_src.join("hashtable"),
    ] {
        println!("cargo::rerun-if-changed={}", f.display());
    }

    let gen_dir = out.join("zkx");
    fs::create_dir_all(&gen_dir)?;

    // Stage `zk_hashtable.c` into `gen_dir` so quoted `#include "zk_adaptor.h"` resolves to
    // the harness stub header rather than the production header in `c_src`.
    let zk_hashtable = fs::read_to_string(&zk_hashtable_c)?;
    fs::write(gen_dir.join("zk_hashtable.c"), &zk_hashtable)?;

    let source = fs::read_to_string(&zookeeper_c)?;
    let mut unit = String::from("#include \"zkx_extract_prelude.h\"\n\n");
    let mut rs = String::from(
        "/// Extracts from `zookeeper.c`: `(name, first_line, last_line)`.\n\
         pub const ZOOKEEPER_C_EXTRACTS: &[(&str, usize, usize)] = &[\n",
    );
    let lit = c_string_literal(&zookeeper_c);
    for ex in ZOOKEEPER_C_EXTRACTS {
        let found = locate(&source, "zookeeper.c", ex)?;
        writeln!(
            unit,
            "/* zookeeper.c lines {}-{}: {} */",
            found.first, found.last, ex.name
        )?;
        writeln!(unit, "#line {} \"{lit}\"", found.first)?;
        unit.push_str(&found.text);
        unit.push('\n');
        writeln!(rs, "    ({:?}, {}, {}),", ex.name, found.first, found.last)?;
    }
    rs.push_str("];\n");
    unit.push_str("#include \"zkx_extract_tail.inc\"\n");
    fs::write(gen_dir.join("zookeeper_extract.c"), unit)?;

    let djb2 = locate(&zk_hashtable, "zk_hashtable.c", &DJB2_EXTRACT)?;
    writeln!(
        rs,
        "/// Line range `(first_line, last_line)` of `string_hash_djb2` in `zk_hashtable.c`.\n\
         pub const DJB2_EXTRACT: (usize, usize) = ({}, {});",
        djb2.first, djb2.last
    )?;
    fs::write(&manifest_rs, rs)?;

    let mut objects = compile_djb2(&gen_dir, &zk_hashtable_c, &djb2)?;

    let stub = manifest.join("csrc/stub");
    let csrc = manifest.join("csrc");
    objects.extend(compile_zt_drivers(&stub, &csrc, &gen_dir, &c_src)?);

    let mut build = cc::Build::new();
    build
        .include(&stub)
        .include(&csrc)
        .include(&gen_dir)
        .include(&c_src)
        // Redirect `random()` in `addrvec_shuffle()` to the deterministic harness RNG feed.
        .define("random", "zkx_random")
        .warnings(false)
        .file(c_src.join("hashtable/hashtable.c"))
        .file(c_src.join("hashtable/hashtable_itr.c"))
        .file(c_src.join("addrvec.c"))
        .file(gen_dir.join("zookeeper_extract.c"))
        .file(csrc.join("zkx_harness.c"))
        .objects(objects);
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("linux") {
        build.define("_GNU_SOURCE", None);
    }
    build.try_compile("zkx_c")?;
    println!("cargo::rustc-cfg=zkx_c");
    Ok(())
}
