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

//! Build configuration and toolchain verification tests for the drop-in C client libraries.
//!
//! The drop-in CMake project under `c/` builds the Rust core, whereas `zookeeper-client-c` builds
//! the legacy C client. CMake test cases execute the configuration phase through a synthetic
//! consumer project that includes the client via `add_subdirectory()` and inspects the resulting
//! `zookeeper` target's public compile definitions and link interface. Hosts lacking a `cmake`
//! binary skip the CMake configuration tests.
//!
//! Additional static checks validate workspace minimum toolchain versions, verify that C ABI Cargo
//! profiles enforce `panic = "abort"`, and confirm that the drop-in CMake and Autoconf scripts
//! remain synchronized with the platform probes in `zookeeper-client-c`.

#![cfg(unix)]
#![forbid(unsafe_code)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::print_stderr
)]
#![allow(clippy::disallowed_macros)]

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;

/// Resolves the canonical path to the Rust workspace root.
fn ws_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .unwrap()
}

/// Resolves the canonical path to the legacy C client directory. Its projects build only the
/// legacy C client. The drop-in project reads its headers and sources.
fn c_dir() -> PathBuf {
    ws_dir()
        .join("../zookeeper-client-c")
        .canonicalize()
        .unwrap()
}

/// Resolves the canonical path to the `c/` directory. It holds the drop-in CMake and Autoconf
/// projects of the Rust core.
fn rust_c_dir() -> PathBuf {
    ws_dir().join("c").canonicalize().unwrap()
}

fn read(p: &Path) -> String {
    fs::read_to_string(p).unwrap_or_else(|e| panic!("read {}: {e}", p.display()))
}

/// Creates and returns a clean temporary working directory for a single test case.
fn work_dir(name: &str) -> PathBuf {
    let d = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
        .join("ledger_e")
        .join(name);
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

/// Resolves the `cmake` executable path, or prints a skip notice and returns `None` if absent.
fn cmake() -> Option<String> {
    let exe = std::env::var("ZK_CMAKE").unwrap_or_else(|_| "cmake".to_owned());
    match Command::new(&exe).arg("--version").output() {
        Ok(o) if o.status.success() => Some(exe),
        _ => {
            eprintln!("skip: no cmake program ({exe})");
            None
        }
    }
}

/// Resolves an installation prefix from environment variable `var`, falling back to `default`
/// when that directory exists on disk.
fn prefix(var: &str, default: &str) -> Option<String> {
    match std::env::var(var) {
        Ok(v) if !v.is_empty() => Some(v),
        _ => Path::new(default).is_dir().then(|| default.to_owned()),
    }
}

/// Captured output from a single CMake configure invocation.
struct Configured {
    ok: bool,
    log: String,
    defs: Vec<String>,
    libs: Vec<String>,
}

/// Extracts a semicolon-delimited CMake property list emitted on a `ZKPROBE` log line,
/// filtering out `-NOTFOUND` sentinel values.
fn probe_list(log: &str, key: &str) -> Vec<String> {
    let tag = format!("ZKPROBE {key}=");
    log.lines()
        .find_map(|l| l.split_once(&tag).map(|(_, v)| v.trim().to_owned()))
        .filter(|v| !v.ends_with("-NOTFOUND"))
        .map(|v| {
            v.split(';')
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

/// Runs `cmake` to configure `source` inside working directory `name` with `args`. When
/// `tools` is given, that directory comes first in `PATH`.
fn configure(
    exe: &str,
    name: &str,
    source: &Path,
    args: &[String],
    tools: Option<&Path>,
) -> Configured {
    let b = work_dir(name);
    let mut cmd = Command::new(exe);
    cmd.arg("-S")
        .arg(source)
        .arg("-B")
        .arg(&b)
        .arg("-DWANT_CPPUNIT=OFF")
        .args(args);
    if let Some(t) = tools {
        let path = std::env::var("PATH").unwrap_or_default();
        cmd.env("PATH", format!("{}:{path}", t.display()));
    }
    let out = cmd.output().unwrap();
    let mut log = String::from_utf8_lossy(&out.stdout).into_owned();
    log.push_str(&String::from_utf8_lossy(&out.stderr));
    fs::write(b.join("configure.log"), &log).unwrap();
    Configured {
        ok: out.status.success(),
        defs: probe_list(&log, "defs"),
        libs: probe_list(&log, "libs"),
        log,
    }
}

/// Configures the consumer probe project around the C client project at `client`.
///
/// Returns `None` with a diagnostic notice only when offline Cargo crate sources are unavailable
/// (`cargo fetch --locked` fails); any other configuration failure fails the test.
fn probe(exe: &str, name: &str, client: &Path, args: &[String]) -> Option<Configured> {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/ledger_e/cmake-probe");
    let mut all = vec![format!("-DZK_CLIENT_SOURCE_DIR={}", client.display())];
    all.extend_from_slice(args);
    let c = configure(exe, name, &src, &all, None);
    if !c.ok
        && c.log
            .contains("no crate source: cargo fetch --locked failed")
    {
        eprintln!("skip: {name}: no crate sources for the Rust core (cargo fetch --locked failed)");
        return None;
    }
    assert!(c.ok, "{name}: the configure step fails:\n{}", c.log);
    Some(c)
}

/// Returns true when the repository's CMake `FindCyrusSASL.cmake` module is available.
fn have_find_modules() -> bool {
    c_dir()
        .join("../../tools/cmake/Modules/FindCyrusSASL.cmake")
        .is_file()
}

/// Returns true if any entry in the target link interface references OpenSSL.
fn names_openssl(libs: &[String]) -> bool {
    libs.iter().any(|l| {
        let l = l.to_ascii_lowercase();
        l.contains("openssl")
            || l.contains("libssl")
            || l.contains("libcrypto")
            || l == "-lssl"
            || l == "-lcrypto"
    })
}

fn has(list: &[String], item: &str) -> bool {
    list.iter().any(|s| s == item)
}

/// Verifies that a full TLS + SASL build of the Rust core exports `HAVE_OPENSSL_H` and
/// `HAVE_CYRUS_SASL_H` as `PUBLIC` compile definitions while linking native system libraries
/// without exposing OpenSSL in the link interface.
#[test]
fn abi16_rust_core_tls_sasl_public_definitions() {
    let Some(exe) = cmake() else { return };
    let ossl =
        prefix("OPENSSL_PREFIX", "/opt/homebrew/opt/openssl@3").unwrap_or_else(|| "ON".to_owned());
    let sasl =
        prefix("SASL_PREFIX", "/opt/homebrew/opt/cyrus-sasl").unwrap_or_else(|| "ON".to_owned());
    let Some(c) = probe(
        &exe,
        "rust-full",
        &rust_c_dir(),
        &[
            format!("-DWITH_OPENSSL={ossl}"),
            format!("-DWITH_CYRUS_SASL={sasl}"),
        ],
    ) else {
        return;
    };
    assert!(
        c.log
            .contains("The TLS and SASL build of the Rust client: tls=ON"),
        "the probe finds no OpenSSL:\n{}",
        c.log
    );
    assert!(has(&c.defs, "HAVE_OPENSSL_H"), "defs {:?}", c.defs);
    assert!(
        !names_openssl(&c.libs),
        "the link interface names OpenSSL: {:?}",
        c.libs
    );
    assert!(has(&c.libs, "Threads::Threads"), "libs {:?}", c.libs);
    if have_find_modules() {
        assert!(has(&c.defs, "HAVE_CYRUS_SASL_H"), "defs {:?}", c.defs);
        assert!(has(&c.libs, "CyrusSASL"), "libs {:?}", c.libs);
    } else {
        eprintln!("skip: the SASL part needs tools/cmake/Modules/FindCyrusSASL.cmake");
    }
}

/// Verifies that `WITH_RUST_TLS=ON` enables TLS support without requiring an OpenSSL
/// installation and still exports `HAVE_OPENSSL_H` to downstream consumers.
#[test]
fn abi16_rust_tls_without_openssl_defines_have_openssl_h() {
    let Some(exe) = cmake() else { return };
    let Some(c) = probe(
        &exe,
        "rust-rusttls",
        &rust_c_dir(),
        &[
            "-DWITH_RUST_TLS=ON".to_owned(),
            "-DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=TRUE".to_owned(),
            "-DWITH_CYRUS_SASL=OFF".to_owned(),
        ],
    ) else {
        return;
    };
    assert!(
        c.log.contains("WITH_RUST_TLS=ON builds SSL support"),
        "the probe finds OpenSSL, or TLS is off:\n{}",
        c.log
    );
    assert!(has(&c.defs, "HAVE_OPENSSL_H"), "defs {:?}", c.defs);
    assert!(!has(&c.defs, "HAVE_CYRUS_SASL_H"), "defs {:?}", c.defs);
    assert!(
        !names_openssl(&c.libs),
        "the link interface names OpenSSL: {:?}",
        c.libs
    );
}

/// Verifies that a plaintext build of the Rust core exports neither feature macro.
#[test]
fn abi16_rust_core_plain_has_no_feature_definitions() {
    let Some(exe) = cmake() else { return };
    let Some(c) = probe(
        &exe,
        "rust-plain",
        &rust_c_dir(),
        &[
            "-DWITH_OPENSSL=OFF".to_owned(),
            "-DWITH_CYRUS_SASL=OFF".to_owned(),
        ],
    ) else {
        return;
    };
    assert!(!has(&c.defs, "HAVE_OPENSSL_H"), "defs {:?}", c.defs);
    assert!(!has(&c.defs, "HAVE_CYRUS_SASL_H"), "defs {:?}", c.defs);
    assert!(
        !names_openssl(&c.libs),
        "the link interface names OpenSSL: {:?}",
        c.libs
    );
}

/// Verifies the baseline legacy C build of `zookeeper-client-c`, with no Rust option. It
/// exports the same `PUBLIC` feature definitions and has `OpenSSL::SSL` in its public link
/// interface.
#[test]
fn abi16_legacy_core_reference_definitions() {
    let Some(exe) = cmake() else { return };
    let Some(ossl) = prefix("OPENSSL_PREFIX", "/opt/homebrew/opt/openssl@3") else {
        eprintln!("skip: no OpenSSL prefix (set OPENSSL_PREFIX)");
        return;
    };
    let sasl =
        prefix("SASL_PREFIX", "/opt/homebrew/opt/cyrus-sasl").unwrap_or_else(|| "ON".to_owned());
    // Because the legacy CMake configuration uses `OPENSSL_ROOT_DIR` and resolves custom
    // Find modules relative to `CMAKE_SOURCE_DIR`, pass both paths explicitly for the
    // subproject probe.
    let modules = c_dir().join("../../tools/cmake/Modules");
    let Some(c) = probe(
        &exe,
        "legacy-full",
        &c_dir(),
        &[
            format!("-DOPENSSL_ROOT_DIR={ossl}"),
            format!("-DWITH_CYRUS_SASL={sasl}"),
            format!("-DCMAKE_MODULE_PATH={}", modules.display()),
        ],
    ) else {
        return;
    };
    assert!(has(&c.defs, "HAVE_OPENSSL_H"), "defs {:?}", c.defs);
    assert!(has(&c.libs, "OpenSSL::SSL"), "libs {:?}", c.libs);
    if have_find_modules() {
        assert!(has(&c.defs, "HAVE_CYRUS_SASL_H"), "defs {:?}", c.defs);
    }
}

/// Creates a synthetic executable script in `dir` that prints `line` on invocation.
fn fake_tool(dir: &Path, name: &str, line: &str) -> PathBuf {
    let p = dir.join(name);
    fs::write(&p, format!("#!/bin/sh\necho '{line}'\n")).unwrap();
    fs::set_permissions(&p, fs::Permissions::from_mode(0o755)).unwrap();
    p
}

/// Configures the C project in `source` with synthetic `rustc` and `cargo` programs that
/// report the given versions. The programs come first in `PATH`, and the Rust options name
/// them.
fn old_toolchain(exe: &str, name: &str, source: &Path, rustc: &str, cargo: &str) -> Configured {
    let tools = work_dir(&format!("{name}-tools"));
    let r = fake_tool(
        &tools,
        "rustc",
        &format!("rustc {rustc} (0000000 2025-01-01)"),
    );
    let k = fake_tool(
        &tools,
        "cargo",
        &format!("cargo {cargo} (0000000 2025-01-01)"),
    );
    configure(
        exe,
        name,
        source,
        &[
            format!("-DRUSTC_EXECUTABLE={}", r.display()),
            format!("-DCARGO_EXECUTABLE={}", k.display()),
            "-DWITH_OPENSSL=OFF".to_owned(),
            "-DWITH_CYRUS_SASL=OFF".to_owned(),
        ],
        Some(&tools),
    )
}

/// Verifies that an old Rust toolchain has no effect on the legacy C build. The legacy
/// project configures when `rustc` or `cargo` is older than 1.85, and it does not mention
/// the Rust client.
#[test]
fn dv_bld4_old_toolchain_legacy_unaffected() {
    let Some(exe) = cmake() else { return };
    for (name, rustc, cargo) in [
        ("legacy-184", "1.84.0", "1.84.0"),
        ("legacy-cargo-184", "1.85.0", "1.84.1"),
    ] {
        let c = old_toolchain(&exe, name, &c_dir(), rustc, cargo);
        assert!(
            c.ok,
            "{name}: the legacy project must configure:\n{}",
            c.log
        );
        assert!(
            !c.log.contains("Rust client"),
            "{name}: the legacy project mentions the Rust client:\n{}",
            c.log
        );
    }
}

/// Verifies that the drop-in project stops with the reason when the Rust toolchain is older
/// than 1.85. The error names the legacy project as the alternative.
#[test]
fn dv_bld4_old_toolchain_dropin_stops() {
    let Some(exe) = cmake() else { return };
    for (name, rustc, cargo) in [
        ("dropin-184", "1.84.0", "1.84.0"),
        ("dropin-cargo-184", "1.85.0", "1.84.1"),
    ] {
        let c = old_toolchain(&exe, name, &rust_c_dir(), rustc, cargo);
        assert!(
            !c.ok,
            "{name}: the drop-in must stop with an old toolchain:\n{}",
            c.log
        );
        // CMake wraps a long message, so the check joins the words of the log.
        let words = c.log.split_whitespace().collect::<Vec<_>>().join(" ");
        assert!(
            words.contains(
                "cannot build the Rust client: cargo and rustc 1.85 or later not found; \
                 zookeeper-client-c builds the legacy C client"
            ),
            "{name}: no error message:\n{}",
            c.log
        );
    }
}

/// Verifies the declared minimum tool versions: Rust 1.85 in `Cargo.toml`, CMake and
/// Autoconf. The legacy and the drop-in `CMakeLists.txt` keep the CMake 3.5 minimum, and only
/// the Rust core needs CMake 3.15.
#[test]
fn dv_bld4_declared_minimums() {
    let ws = read(&ws_dir().join("Cargo.toml"));
    assert!(
        ws.lines().any(|l| l.trim() == "rust-version = \"1.85\""),
        "the workspace does not declare rust-version 1.85"
    );
    for dir in [c_dir(), rust_c_dir()] {
        let cm = read(&dir.join("CMakeLists.txt"));
        assert!(
            cm.lines()
                .any(|l| l.trim() == "cmake_minimum_required(VERSION 3.5)"),
            "{}/CMakeLists.txt does not keep the legacy CMake 3.5 minimum",
            dir.display()
        );
    }
    let detect = read(&rust_c_dir().join("cmake/ZkRustDetect.cmake"));
    assert!(
        detect.contains("if(CMAKE_VERSION VERSION_LESS 3.15)")
            && detect.contains("the Rust client needs CMake 3.15 or later"),
        "ZkRustDetect.cmake does not require CMake 3.15 for the Rust core"
    );
    assert!(
        detect.contains("v1 LESS 1085 OR v2 LESS 1085"),
        "ZkRustDetect.cmake"
    );
    let m4 = read(&rust_c_dir().join("m4/zk_rust.m4"));
    assert!(
        m4.contains("test \"$zk_rust_v1\" -ge 1085 && test \"$zk_rust_v2\" -ge 1085"),
        "m4/zk_rust.m4"
    );
}

/// Extracts the trimmed lines belonging to a single TOML table section.
fn toml_table<'a>(text: &'a str, header: &str) -> Vec<&'a str> {
    text.lines()
        .skip_while(|l| l.trim() != header)
        .skip(1)
        .take_while(|l| !l.trim_start().starts_with('['))
        .map(str::trim)
        .collect()
}

fn rust_sources(dir: &Path, out: &mut Vec<PathBuf>) {
    for e in fs::read_dir(dir).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            rust_sources(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

/// Verifies that both C library Cargo profiles (`capi` and `capi-dev`) configure
/// `panic = "abort"`, that both CMake and Autoconf select exclusively those profiles, and
/// that production library crates install no custom panic or allocation-error hooks and
/// require no nightly compiler features.
#[test]
fn dv_bld5_panic_aborts_without_hook() {
    let ws = read(&ws_dir().join("Cargo.toml"));
    for t in ["[profile.capi]", "[profile.capi-dev]"] {
        let lines = toml_table(&ws, t);
        assert!(!lines.is_empty(), "no table {t}");
        assert!(
            lines.contains(&"panic = \"abort\""),
            "{t} does not abort: {lines:?}"
        );
    }
    let cmake_rust = read(&rust_c_dir().join("cmake/ZkRust.cmake"));
    assert!(
        cmake_rust.contains("set(ZKRS_PROFILE capi-dev)"),
        "ZkRust.cmake debug profile"
    );
    assert!(
        cmake_rust.contains("set(ZKRS_PROFILE capi)"),
        "ZkRust.cmake release profile"
    );
    let ac = read(&rust_c_dir().join("m4/zk_rust.m4"));
    assert!(
        ac.contains("ZKRS_PROFILE=capi\n"),
        "m4/zk_rust.m4 release profile"
    );
    assert!(
        ac.contains("ZKRS_PROFILE=capi-dev;"),
        "m4/zk_rust.m4 debug profile"
    );

    for f in ["rust-toolchain", "rust-toolchain.toml"] {
        let p = ws_dir().join(f);
        if p.exists() {
            assert!(
                !read(&p).contains("nightly"),
                "{f} selects a nightly toolchain"
            );
        }
    }
    let mut files = Vec::new();
    for c in ["zk-ffi", "zk-driver", "zk-proto", "zk-jute"] {
        rust_sources(&ws_dir().join("crates").join(c).join("src"), &mut files);
    }
    assert!(!files.is_empty());
    for f in &files {
        let text = read(f);
        for bad in [
            "panic::set_hook",
            "panic::take_hook",
            "panic::update_hook",
            "set_alloc_error_hook",
            "#![feature",
        ] {
            assert!(!text.contains(bad), "{} has {bad}", f.display());
        }
    }
}

/// Returns the lines of `old` and the lines of `new` that a longest common subsequence of the
/// two does not contain. These are the lines that the change from `old` to `new` removes and
/// adds.
fn line_diff<'a>(old: &[&'a str], new: &[&'a str]) -> (Vec<&'a str>, Vec<&'a str>) {
    let (n, m) = (old.len(), new.len());
    let mut lcs = vec![vec![0usize; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            lcs[i][j] = if old[i] == new[j] {
                lcs[i + 1][j + 1] + 1
            } else {
                lcs[i + 1][j].max(lcs[i][j + 1])
            };
        }
    }
    let (mut i, mut j) = (0, 0);
    let (mut removed, mut added) = (Vec::new(), Vec::new());
    while i < n && j < m {
        if old[i] == new[j] {
            i += 1;
            j += 1;
        } else if lcs[i + 1][j] >= lcs[i][j + 1] {
            removed.push(old[i]);
            i += 1;
        } else {
            added.push(new[j]);
            j += 1;
        }
    }
    removed.extend_from_slice(&old[i..]);
    added.extend_from_slice(&new[j..]);
    (removed, added)
}

/// Removes the one copy of `block` from `text`, and fails when `text` does not have exactly
/// one copy.
fn cut_block(text: &str, block: &str, what: &str) -> String {
    assert_eq!(
        text.matches(block).count(),
        1,
        "the legacy configure.ac has no single copy of the {what}:\n{block}"
    );
    text.replacen(block, "", 1)
}

/// The Doxygen part of the legacy `configure.ac`. The drop-in builds no documentation.
const LEGACY_DOXYGEN: &str = "# initialize Doxygen support
DX_HTML_FEATURE(ON)
DX_CHM_FEATURE(OFF)
DX_CHI_FEATURE(OFF)
DX_MAN_FEATURE(OFF)
DX_RTF_FEATURE(OFF)
DX_XML_FEATURE(OFF)
DX_PDF_FEATURE(OFF)
DX_PS_FEATURE(OFF)
DX_INIT_DOXYGEN([zookeeper],[c-doc.Doxyfile],[docs])
";

/// The `CALLER=ANT` part of the legacy `configure.ac`. The drop-in checks the jute files of
/// the legacy tree in its own way, and its public suites set their own server command.
const LEGACY_CALLER: &str = r#"if test "$CALLER" = "ANT" ; then
CPPUNIT_CFLAGS="$CPPUNIT_CFLAGS -DZKSERVER_CMD=\"\\\"${base_dir}/zookeeper-client/zookeeper-client-c/tests/zkServer.sh\\\"\""
else
CPPUNIT_CFLAGS="$CPPUNIT_CFLAGS -DZKSERVER_CMD=\"\\\"./tests/zkServer.sh\\\"\""
AC_CHECK_FILES([$srcdir/generated/zookeeper.jute.c $srcdir/generated/zookeeper.jute.h],[],
    [AC_MSG_ERROR([jute files are missing! Please run "ant compile_jute" while in the zookeeper top level directory.])
])
fi
"#;

/// The legacy lines of `configure.ac` that the drop-in replaces, other than the two parts
/// above. The version line is compared without the version.
const LEGACY_REPLACED: [&str; 3] = [
    "AC_PREREQ(2.59)",
    "AC_CONFIG_SRCDIR([src/zookeeper.c])",
    "AM_CONDITIONAL([WANT_OPENSSL],[test \"x$with_openssl\" != xno])",
];

/// The lines of `c/configure.ac` that are not in the legacy `configure.ac`, other than
/// comments and blank lines. Each line occurs once.
const DROPIN_OWN: [&str; 17] = [
    "AC_PREREQ([2.64])",
    r#"m4_define([zk_c_version], m4_esyscmd_s([sed -n 's/^.define ZOO_VERSION "\(.*\)"/\1/p' ../../zookeeper-client-c/include/zookeeper_version.h]))"#,
    "m4_if(zk_c_version, [],",
    "  [m4_fatal([cannot read ZOO_VERSION from ../../zookeeper-client-c/include/zookeeper_version.h])])",
    "AC_INIT([zookeeper C client],zk_c_version,[user@zookeeper.apache.org],[zookeeper])",
    "AC_CONFIG_SRCDIR([shim/zkrs.h])",
    "AC_CONFIG_AUX_DIR([build-aux])",
    "m4_include([../../zookeeper-client-c/acinclude.m4])",
    "m4_include([m4/zk_rust.m4])",
    r#"zk_c_dir="$srcdir/../../zookeeper-client-c""#,
    r#"AS_IF([test ! -f "$zk_c_dir/generated/zookeeper.jute.c" || test ! -f "$zk_c_dir/generated/zookeeper.jute.h"],"#,
    r#"  [AC_MSG_ERROR([jute files are missing! Please run "ant compile_jute" while in the zookeeper top level directory.])])"#,
    r#"AS_IF([test ! -f "$zk_c_dir/include/zookeeper.h"],"#,
    "  [AC_MSG_ERROR([the legacy C client tree is missing: no $zk_c_dir/include/zookeeper.h])])",
    "ZK_RUST_CLIENT",
    r#"AS_IF([test "x$with_sock_cloexec" != xno],"#,
    r#"  [ZKS_CONFIG_CPPFLAGS="$ZKS_CONFIG_CPPFLAGS -DSOCK_CLOEXEC_ENABLED"])"#,
];

/// Returns true for a comment line of `c/configure.ac` that the drop-in adds. The C
/// preprocessor lines of the test programs do not match.
fn is_dropin_comment(l: &str) -> bool {
    l == "#" || l.starts_with("# ") || l == "dnl" || l.starts_with("dnl ")
}

/// Returns the logical lines of a Makefile.am, without comments and blank lines. A logical
/// line joins the continuation lines, and one space separates its words.
fn make_lines(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    for l in text.lines() {
        let (body, more) = match l.strip_suffix('\\') {
            Some(b) => (b, true),
            None => (l, false),
        };
        cur.push(' ');
        cur.push_str(body);
        if !more {
            let line = cur.split_whitespace().collect::<Vec<_>>().join(" ");
            if !line.is_empty() && !line.starts_with('#') {
                out.push(line);
            }
            cur.clear();
        }
    }
    out
}

/// Verifies that the drop-in build files preserve exact copies of the legacy platform probes.
///
/// For Autoconf, the legacy `configure.ac` (excluding sections intentionally replaced by the
/// drop-in) must appear as an ordered subsequence of `c/configure.ac`, and all remaining lines in
/// `c/configure.ac` must belong to comments, whitespace, or [`DROPIN_OWN`]. For CMake, the probe
/// section of the legacy `CMakeLists.txt` must match `c/cmake/ZkCProbes.cmake` (modulo two
/// legacy-tree relative paths) and be preceded only by the expected preamble lines.
#[test]
fn dv_bld_probe_parity() {
    // Autoconf.
    let legacy = read(&c_dir().join("configure.ac"));
    let dropin = read(&rust_c_dir().join("configure.ac"));
    let legacy = cut_block(&legacy, LEGACY_DOXYGEN, "Doxygen part");
    let legacy = cut_block(&legacy, LEGACY_CALLER, "CALLER=ANT part");
    let mut replaced = Vec::new();
    let legacy_lines: Vec<&str> = legacy
        .lines()
        .filter(|l| !l.trim().is_empty())
        .filter(|l| {
            let version = l.starts_with("AC_INIT([zookeeper C client],")
                && l.ends_with(",[user@zookeeper.apache.org],[zookeeper])");
            if version || LEGACY_REPLACED.contains(l) {
                replaced.push(*l);
                return false;
            }
            true
        })
        .collect();
    assert_eq!(
        replaced.len(),
        LEGACY_REPLACED.len() + 1,
        "the legacy configure.ac has no single copy of each replaced line: {replaced:?}"
    );
    let dropin_lines: Vec<&str> = dropin.lines().filter(|l| !l.trim().is_empty()).collect();
    let (removed, added) = line_diff(&legacy_lines, &dropin_lines);
    assert!(
        removed.is_empty(),
        "c/configure.ac has no copy of these legacy lines, in the legacy order:\n{}",
        removed.join("\n")
    );
    let mut own: Vec<&str> = DROPIN_OWN.to_vec();
    let unknown: Vec<&str> = added
        .into_iter()
        .filter(|l| !is_dropin_comment(l))
        .filter(|l| match own.iter().position(|o| o == l) {
            Some(i) => {
                own.remove(i);
                false
            }
            None => true,
        })
        .collect();
    assert!(
        unknown.is_empty(),
        "c/configure.ac has lines that are not in the legacy configure.ac (a stale copy?):\n{}",
        unknown.join("\n")
    );
    assert!(
        own.is_empty(),
        "c/configure.ac does not have these lines of the drop-in:\n{}",
        own.join("\n")
    );

    // CMake.
    let legacy = read(&c_dir().join("CMakeLists.txt"));
    let lines: Vec<&str> = legacy.lines().collect();
    let start = lines
        .iter()
        .position(|l| *l == "# general options")
        .expect("the legacy CMakeLists.txt has no '# general options' line");
    let end = lines
        .iter()
        .position(|l| l.starts_with("configure_file(cmake_config.h.in "))
        .expect("the legacy CMakeLists.txt has no configure_file of config.h");
    assert!(start < end, "the legacy probe part is empty");
    let head: Vec<&str> = lines[..start]
        .iter()
        .copied()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .collect();
    let head_ok = head.len() == 5
        && head[0] == "cmake_minimum_required(VERSION 3.5)"
        && head[1].starts_with("project(zookeeper VERSION ")
        && head[2] == "set(email user@zookeeper.apache.org)"
        && head[3] == "set(description \"zookeeper C client\")"
        && head[4]
            == "list(APPEND CMAKE_MODULE_PATH \"${CMAKE_SOURCE_DIR}/../../tools/cmake/Modules\")";
    assert!(
        head_ok,
        "the legacy CMakeLists.txt has new or changed lines before '# general options': {head:?}"
    );
    let after = lines[end + 1..].iter().find(|l| !l.trim().is_empty());
    assert!(
        after.is_some_and(|l| *l == "# hashtable library"),
        "the legacy CMakeLists.txt has new lines after the configure_file of config.h: {after:?}"
    );
    let part = lines[start..=end].join("\n");
    assert_eq!(
        part.matches("CMAKE_CURRENT_SOURCE_DIR").count(),
        1,
        "the legacy probe part has a new CMAKE_CURRENT_SOURCE_DIR path"
    );
    let want = part
        .replace("${CMAKE_CURRENT_SOURCE_DIR}/${f}", "${ZKC_DIR}/${f}")
        .replace(
            "configure_file(cmake_config.h.in ",
            "configure_file(${ZKC_DIR}/cmake_config.h.in ",
        );
    let probes = read(&rust_c_dir().join("cmake/ZkCProbes.cmake"));
    let copy = probes
        .split_once("cmake_policy(VERSION 3.5)\n")
        .and_then(|(_, rest)| rest.rsplit_once("cmake_policy(POP)"))
        .map(|(copy, _)| copy.trim())
        .expect("ZkCProbes.cmake has no cmake_policy(VERSION 3.5) ... cmake_policy(POP) block");
    assert!(
        copy == want,
        "ZkCProbes.cmake differs from the legacy probe part:\n--- want\n{want}\n--- have\n{copy}"
    );
    let top = read(&rust_c_dir().join("CMakeLists.txt"));
    for l in [head[0], head[2], head[3]] {
        assert!(
            top.lines().any(|t| t == l),
            "c/CMakeLists.txt has no copy of the legacy line {l}"
        );
    }
}

/// Verifies that `c/Makefile.am` preserves the global variable definitions of the legacy
/// `Makefile.am`.
///
/// The legacy global definitions preceding `EXTRA_DIST` must appear in `c/Makefile.am` in identical
/// order, rooted under the legacy source tree variables (`ZKC_SRCDIR` and `ZKC_ABS_SRCDIR`),
/// with `zk_rust.m4` directory flags appended to `OPENSSL_CPPFLAGS` and `SASL_CPPFLAGS`, public
/// headers installed via `nodist_pkginclude_HEADERS`, `LIB_LDFLAGS` relocated to `rust.am`, and
/// legacy Doxygen, OpenSSL link flags, and `src/zk_sasl.c` omitted.
#[test]
fn dv_bld_makefile_parity() {
    let legacy = make_lines(&read(&c_dir().join("Makefile.am")));
    let end = legacy
        .iter()
        .position(|l| l.starts_with("EXTRA_DIST"))
        .expect("the legacy Makefile.am has no EXTRA_DIST line");
    let mut want = Vec::new();
    let mut lib_ldflags = None;
    for l in &legacy[..end] {
        match l.as_str() {
            "include $(top_srcdir)/aminclude.am"
            | "OPENSSL_LIB_LDFLAGS = -lssl -lcrypto"
            | "SASL_SRC = src/zk_sasl.c" => {}
            "SASL_LIB_LDFLAGS = -lsasl2" => {
                let m4 = read(&rust_c_dir().join("m4/zk_rust.m4"));
                assert!(
                    m4.contains("\n  ZKRS_SASL_LIBS=-lsasl2\n"),
                    "m4/zk_rust.m4 does not link -lsasl2 as the legacy SASL_LIB_LDFLAGS"
                );
            }
            "OPENSSL_CPPFLAGS = -DHAVE_OPENSSL_H" => {
                want.push(format!("{l} $(ZK_OPENSSL_DIR_CPPFLAGS)"));
            }
            "SASL_CPPFLAGS = -DHAVE_CYRUS_SASL_H" => {
                want.push(format!("{l} $(ZK_SASL_DIR_CPPFLAGS)"));
            }
            _ if l.starts_with("LIB_LDFLAGS = ") => {
                lib_ldflags = Some(
                    l.replacen("LIB_LDFLAGS = ", "ZKRS_LIB_LDFLAGS = ", 1)
                        .replace(" $(OPENSSL_LIB_LDFLAGS) $(SASL_LIB_LDFLAGS)", ""),
                );
            }
            _ if l.starts_with("pkginclude_HEADERS = ") => {
                let headers: Vec<String> = l["pkginclude_HEADERS = ".len()..]
                    .split(' ')
                    .map(|h| format!("$(ZKC_SRCDIR)/{h}"))
                    .collect();
                want.push(format!("nodist_pkginclude_HEADERS = {}", headers.join(" ")));
            }
            _ => want.push(l.replace("${srcdir}/", "$(ZKC_SRCDIR)/")),
        }
    }
    let lib_ldflags = lib_ldflags.expect("the legacy Makefile.am has no LIB_LDFLAGS line");
    let rust_am = make_lines(&read(&rust_c_dir().join("rust.am")));
    assert!(
        rust_am.contains(&lib_ldflags),
        "rust.am has no line {lib_ldflags}, which follows the legacy LIB_LDFLAGS"
    );
    let dropin = make_lines(&read(&rust_c_dir().join("Makefile.am")));
    let end = dropin
        .iter()
        .position(|l| l == "noinst_LTLIBRARIES =")
        .expect("c/Makefile.am has no 'noinst_LTLIBRARIES =' line");
    let have: Vec<&String> = dropin[..end]
        .iter()
        .filter(|l| {
            l.as_str() != "ZKC_SRCDIR = $(srcdir)/../../zookeeper-client-c"
                && l.as_str() != "ZKC_ABS_SRCDIR = $(abs_srcdir)/../../zookeeper-client-c"
        })
        .collect();
    let want: Vec<&String> = want.iter().collect();
    assert!(
        have == want,
        "the globals of c/Makefile.am differ from the legacy globals:\n--- want\n{}\n--- have\n{}",
        want.iter()
            .map(|l| l.as_str())
            .collect::<Vec<_>>()
            .join("\n"),
        have.iter()
            .map(|l| l.as_str())
            .collect::<Vec<_>>()
            .join("\n")
    );
}
