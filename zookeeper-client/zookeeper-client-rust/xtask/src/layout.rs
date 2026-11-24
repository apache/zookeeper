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

//! Validating ABI compatibility between the legacy C client and the Rust shim requires
//! verifying that memory layouts (type sizes, alignments, struct member offsets, and enum
//! sign semantics) match across compiler configurations and target architectures.
//!
//! Rather than running compiled binaries directly, which would prevent cross-compilation
//! verification, the probe generates a standalone C compilation unit containing statically
//! initialized constant arrays of layout evaluations. The extractor compiles this probe to
//! an unlinked object file (`.o`) and directly inspects section data to harvest layout values
//! into target expectation baselines (`<target>.expect`).

use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use crate::headers::{self, PubType};
use crate::objfile;
use crate::util::{self, Error};

/// TSV column headers for layout expectation (`.expect`) files.
const COLUMNS: &str = "config\tfact\ttype\tmember\tvalue";

/// Single structural ABI property to inspect: size, alignment, member offset, or enum signedness.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Fact {
    /// Type of layout query: `sizeof`, `alignof`, `offsetof`, or `signed`.
    fact: &'static str,
    /// Target C type identifier under inspection.
    ty: String,
    /// Struct member designator, or `-` when probing whole-type properties.
    member: String,
}

/// Group of layout queries sharing an identical preprocessor conditional compilation gate.
#[derive(Clone, Debug)]
struct Group {
    /// Emitted C global array symbol name in the compiled object file.
    symbol: String,
    /// Preprocessor `#if` conditional expression guarding this probe array.
    cond: String,
    /// Ordered sequence of layout queries packed into this array.
    facts: Vec<Fact>,
}

/// Returns `true` if `cond` is gated exclusively on Windows (`WIN32` / `_WIN32`).
fn windows_only(cond: &str) -> bool {
    cond.split("&&").any(|p| {
        let p = p.trim();
        p == "WIN32" || p == "_WIN32"
    })
}

/// Converts normalized macro predicate tokens into a C preprocessor `defined(...)` expression.
fn if_expr(cond: &str) -> String {
    let mut out = String::new();
    let mut word = String::new();
    let flush = |word: &mut String, out: &mut String| {
        if !word.is_empty() {
            if word.chars().next().is_some_and(|c| c.is_ascii_digit()) {
                out.push_str(word);
            } else {
                let _ = write!(out, "defined({word})");
            }
            word.clear();
        }
    };
    for c in cond.chars() {
        if c.is_ascii_alphanumeric() || c == '_' {
            word.push(c);
        } else {
            flush(&mut word, &mut out);
            out.push(c);
        }
    }
    flush(&mut word, &mut out);
    out
}

/// Joins two preprocessor expressions with `&&`, eliding empty or duplicate clauses.
fn and(a: &str, b: &str) -> String {
    match (a.is_empty(), b.is_empty()) {
        (true, _) => b.to_owned(),
        (_, true) => a.to_owned(),
        _ if a == b => a.to_owned(),
        _ => format!("{a} && {b}"),
    }
}

/// Groups public types and member queries into constant arrays keyed by preprocessor condition.
fn groups(types: &[PubType]) -> Vec<Group> {
    let mut out: Vec<Group> = Vec::new();
    let mut add = |cond: &str, f: Fact| {
        if let Some(g) = out.iter_mut().find(|g| g.cond == cond) {
            g.facts.push(f);
        } else {
            let symbol = format!("zk_layout_{}", out.len());
            out.push(Group {
                symbol,
                cond: cond.to_owned(),
                facts: vec![f],
            });
        }
    };
    for t in types {
        if windows_only(&t.cond) {
            continue;
        }
        let fact = |fact, member: &str| Fact {
            fact,
            ty: t.name.clone(),
            member: member.to_owned(),
        };
        add(&t.cond, fact("sizeof", "-"));
        add(&t.cond, fact("alignof", "-"));
        if t.is_enum {
            add(&t.cond, fact("signed", "-"));
        }
        let mut seen: Vec<&str> = Vec::new();
        for (m, c) in &t.members {
            if windows_only(c) || seen.contains(&m.as_str()) {
                continue;
            }
            seen.push(m);
            add(&and(&t.cond, c), fact("offsetof", m));
        }
    }
    out
}

/// Formats the C compile-time constant expression for a layout query.
fn expr(f: &Fact) -> String {
    match f.fact {
        "sizeof" => format!("sizeof({})", f.ty),
        "alignof" => format!("_Alignof({})", f.ty),
        "signed" => format!("(({})-1 < 0)", f.ty),
        _ => format!("offsetof({}, {})", f.ty, f.member),
    }
}

/// Renders the standalone `probe.c` C translation unit.
fn render_probe(gs: &[Group]) -> String {
    let mut s = String::new();
    s.push_str(
        util::comment_license(" *")
            .replacen(" *", "/*\n *", 1)
            .trim_end(),
    );
    s.push_str("\n */\n\n");
    s.push_str(
        "/*\n\
         \x20* Compile-time ABI layout probe for public C types. Generated by\n\
         \x20* `cargo xtask layout gen`; do not edit manually. Layout measurements are\n\
         \x20* emitted as static constant arrays so verification requires compilation only.\n\
         \x20* Compile with `-I<prefix>/include` and `-I<prefix>/include/zookeeper`, and\n\
         \x20* optionally `-DHAVE_OPENSSL_H` and `-DHAVE_CYRUS_SASL_H`.\n\
         \x20*/\n\n",
    );
    s.push_str("#include <stddef.h>\n#include <zookeeper/zookeeper.h>\n#include <zookeeper/zookeeper_log.h>\n#include <zookeeper/recordio.h>\n#include <zookeeper/proto.h>\n\n");
    for g in gs {
        if !g.cond.is_empty() {
            let _ = writeln!(s, "#if {}", if_expr(&g.cond));
        }
        let _ = writeln!(s, "const unsigned long long {}[] = {{", g.symbol);
        for f in &g.facts {
            let _ = writeln!(s, "    {},", expr(f));
        }
        s.push_str("};\n");
        if !g.cond.is_empty() {
            s.push_str("#endif\n");
        }
    }
    s
}

/// Generates the C layout probe source text and its grouped layout query metadata.
fn probe(c_dir: &Path) -> Result<(String, Vec<Group>), Error> {
    let gs = groups(&headers::public_types(c_dir)?);
    Ok((render_probe(&gs), gs))
}

/// Compiles `probe_c` to an object file and extracts the evaluated layout constants.
fn measure(
    cc: &str,
    flags: &[String],
    probe_c: &Path,
    gs: &[Group],
) -> Result<Vec<(Fact, u64)>, Error> {
    let obj = probe_c.with_extension(format!("{}.o", flags.len()));
    let out = std::process::Command::new(cc)
        .args(flags)
        .arg("-c")
        .arg(probe_c)
        .arg("-o")
        .arg(&obj)
        .output()
        .map_err(|e| Error::msg(format!("{cc}: {e}")))?;
    if !out.status.success() {
        return Err(Error::msg(format!(
            "{cc} {} failed:\n{}",
            flags.join(" "),
            String::from_utf8_lossy(&out.stderr)
        )));
    }
    let bytes = util::read_bytes(&obj)?;
    let bin = objfile::parse(&bytes, None)?;
    // ELF Data 1 denotes little-endian; supported Mach-O targets are little-endian.
    let le = !(bytes.starts_with(b"\x7fELF") && bytes.get(5) == Some(&2));
    let mut vals = Vec::new();
    for g in gs {
        let Some(sym) = bin.symbols.get(&g.symbol) else {
            continue;
        };
        let at = sym
            .file_off
            .and_then(|o| usize::try_from(o).ok())
            .ok_or_else(|| Error::msg(format!("{}: no file contents", g.symbol)))?;
        for (i, f) in g.facts.iter().enumerate() {
            let start = i
                .checked_mul(8)
                .and_then(|o| o.checked_add(at))
                .ok_or_else(|| Error::msg("probe offset overflow".to_owned()))?;
            let raw = bytes
                .get(start..start.saturating_add(8))
                .ok_or_else(|| Error::msg(format!("{}: truncated", g.symbol)))?;
            let mut a = [0_u8; 8];
            a.copy_from_slice(raw);
            vals.push((
                f.clone(),
                if le {
                    u64::from_le_bytes(a)
                } else {
                    u64::from_be_bytes(a)
                },
            ));
        }
    }
    Ok(vals)
}

/// Resolves the canonical target triple representing the local host environment.
fn host_target() -> String {
    let arch = std::env::consts::ARCH;
    match std::env::consts::OS {
        "macos" => format!("{arch}-apple-darwin"),
        "linux" => format!("{arch}-unknown-linux-gnu"),
        os => format!("{arch}-unknown-{os}"),
    }
}

/// Serializes layout measurements into a canonical `.expect` baseline TSV document.
fn render_expect(
    target: &str,
    plain: &[(Fact, u64)],
    full: &[(Fact, u64)],
    pin: &str,
    full_flags: &str,
) -> String {
    let mut s = util::tsv_license();
    let _ = writeln!(s, "# L1 layout facts of the public types for {target}.");
    s.push_str("#\n");
    s.push_str("# Generated by `cargo xtask layout expect` from tests/abi/layout/probe.c. The\n");
    s.push_str("# values come from the compiled object; no probe code runs.\n");
    s.push_str("# config plain: the headers without HAVE_OPENSSL_H and HAVE_CYRUS_SASL_H.\n");
    let _ = writeln!(
        s,
        "# config tls-sasl: {full_flags}. It lists only the facts that are new or differ."
    );
    s.push_str("# fact: sizeof, alignof, offsetof (bytes) or signed (1 for a signed enum).\n");
    let _ = writeln!(s, "# Headers at commit {pin}.");
    let _ = writeln!(s, "{COLUMNS}");
    for (f, v) in plain {
        let _ = writeln!(s, "plain\t{}\t{}\t{}\t{v}", f.fact, f.ty, f.member);
    }
    for (f, v) in full {
        if plain.iter().any(|(pf, pv)| pf == f && pv == v) {
            continue;
        }
        let _ = writeln!(s, "tls-sasl\t{}\t{}\t{}\t{v}", f.fact, f.ty, f.member);
    }
    s
}

/// CLI entry point for `cargo xtask layout`, dispatching to `gen` or `expect`.
pub(crate) fn run(args: &[String]) -> Result<(), Error> {
    let (cmd, rest) = args
        .split_first()
        .ok_or_else(|| Error::usage("layout: give gen or expect".to_owned()))?;
    let mut c_dir = util::c_client_dir();
    let mut include: Option<String> = None;
    let mut cc = std::env::var("CC").unwrap_or_else(|_| "cc".to_owned());
    let mut full_flags: Vec<String> = Vec::new();
    let mut target = host_target();
    let mut check_mode = false;
    let mut it = rest.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--c-dir" => c_dir = PathBuf::from(util::value(&mut it, a)?),
            "--include" => include = Some(util::value(&mut it, a)?.to_owned()),
            "--cc" => util::value(&mut it, a)?.clone_into(&mut cc),
            "--full-cflags" => {
                full_flags = util::value(&mut it, a)?
                    .split_whitespace()
                    .map(str::to_owned)
                    .collect()
            }
            "--target" => util::value(&mut it, a)?.clone_into(&mut target),
            "--check" => check_mode = true,
            _ => return Err(Error::usage(format!("layout {cmd}: unknown argument {a}"))),
        }
    }
    let o = LayoutOpts {
        c_dir,
        layout_dir: util::rust_c_dir().join("tests/abi/layout"),
        include,
        cc,
        full_flags,
        target,
        check_mode,
    };
    match cmd.as_str() {
        "gen" => generate(&o),
        "expect" => expect(&o),
        other => Err(Error::usage(format!(
            "layout: unknown command {other}: use gen or expect"
        ))),
    }
}

/// Configuration options for layout probe generation and expectation extraction.
struct LayoutOpts {
    /// Path to the legacy C tree containing reference headers.
    c_dir: PathBuf,
    /// Directory storing `probe.c` and `<target>.expect` baseline files.
    layout_dir: PathBuf,
    include: Option<String>,
    cc: String,
    full_flags: Vec<String>,
    target: String,
    check_mode: bool,
}

/// Writes `text` to `path`, or verifies that `path` already matches `text` when `check_mode` is
/// enabled.
fn write_or_check(path: &Path, text: &str, check_mode: bool) -> Result<bool, Error> {
    if check_mode {
        if util::read(path)? == text {
            Ok(false)
        } else {
            Err(Error::msg(format!(
                "{} is out of date; run the task without --check",
                util::rel(path)
            )))
        }
    } else {
        util::write(path, text)?;
        Ok(true)
    }
}

/// Executes `layout gen`, writing `probe.c` from the installed C headers.
fn generate(o: &LayoutOpts) -> Result<(), Error> {
    let (text, gs) = probe(&o.c_dir)?;
    let path = o.layout_dir.join("probe.c");
    if write_or_check(&path, &text, o.check_mode)? {
        let n: usize = gs.iter().map(|g| g.facts.len()).sum();
        util::out(&format!(
            "layout gen: wrote {n} facts in {} arrays to {}\n",
            gs.len(),
            util::rel(&path)
        ));
    }
    Ok(())
}

/// Executes `layout expect`, compiling the probe across `plain` and `tls-sasl` configurations
/// and writing `<target>.expect`.
fn expect(o: &LayoutOpts) -> Result<(), Error> {
    let include = o.include.as_ref().ok_or_else(|| {
        Error::usage("layout expect: --include <prefix>/include is required".to_owned())
    })?;
    let (text, gs) = probe(&o.c_dir)?;
    let tmp = std::env::temp_dir().join(format!("zk-layout-{}", std::process::id()));
    let tmp_probe = tmp.join("probe.c");
    util::write(&tmp_probe, &text)?;
    // Legacy headers use both `<zookeeper/zookeeper.h>` and `<zookeeper.h>` include styles,
    // requiring both `<prefix>/include` and `<prefix>/include/zookeeper` on the search path.
    let base = vec![format!("-I{include}"), format!("-I{include}/zookeeper")];
    let plain = measure(&o.cc, &base, &tmp_probe, &gs);
    let mut ff = base.clone();
    ff.extend([
        "-DHAVE_OPENSSL_H".to_owned(),
        "-DHAVE_CYRUS_SASL_H".to_owned(),
    ]);
    ff.extend(o.full_flags.iter().cloned());
    let full = measure(&o.cc, &ff, &tmp_probe, &gs);
    let _ = std::fs::remove_dir_all(&tmp);
    let (plain, full) = (plain?, full?);
    let shown: Vec<&str> = ff.iter().skip(base.len()).map(String::as_str).collect();
    let out = render_expect(
        &o.target,
        &plain,
        &full,
        &util::git_head(&o.c_dir),
        &shown.join(" "),
    );
    let path = o.layout_dir.join(format!("{}.expect", o.target));
    if write_or_check(&path, &out, o.check_mode)? {
        util::out(&format!(
            "layout expect: wrote {} plain facts to {}\n",
            plain.len(),
            util::rel(&path)
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conditions() {
        assert_eq!(
            if_expr("HAVE_OPENSSL_H && !WIN32"),
            "defined(HAVE_OPENSSL_H) && !defined(WIN32)"
        );
        assert!(windows_only("WIN32") && windows_only("A && _WIN32") && !windows_only("!WIN32"));
        let t = PubType {
            name: "zsock_t".to_owned(),
            is_enum: false,
            cond: String::new(),
            members: vec![
                ("sock".to_owned(), "WIN32".to_owned()),
                ("sock".to_owned(), "!WIN32".to_owned()),
                ("ssl_sock".to_owned(), "HAVE_OPENSSL_H".to_owned()),
            ],
        };
        let g = groups(&[t]);
        assert_eq!(g.len(), 3);
        assert_eq!(g.get(1).map(|g| g.cond.as_str()), Some("!WIN32"));
        assert!(render_probe(&g).contains("#if defined(HAVE_OPENSSL_H)\nconst unsigned long long zk_layout_2[] = {\n    offsetof(zsock_t, ssl_sock),\n};"));
    }
}
