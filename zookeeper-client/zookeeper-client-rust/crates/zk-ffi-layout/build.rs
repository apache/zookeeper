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

//! Build script synthesizing the C and Rust ABI layout and constant tables.
//!
//! The C fact table is measured directly by the host C compiler. The script parses type,
//! field, and constant identifiers from the C headers, emits a C probe program that prints
//! each property, compiles it via the `cc` crate across each header feature configuration,
//! and captures its standard output on the build host.
//!
//! The Rust fact table is evaluated at compile time by `rustc`. The script scans the
//! `zk-ffi` and `zk-jute` source files for `#[repr(C)]` types, fields, enum variants, and
//! constants and emits `const` layout expressions.
//!
//! Because this script extracts only symbolic identifiers while delegating all size, offset,
//! alignment, and constant evaluation to the respective compilers, any divergence or missing
//! declaration on either side is caught by the integration test suite.

use std::collections::BTreeSet;
use std::env;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

type BuildResult<T> = Result<T, String>;

/// Synthetic type group identifier for `ZKRS_*` preprocessor constants defined in `zkrs.h`.
const ZKRS_DEFINES: &str = "zkrs.h";
/// Synthetic type group identifier for protocol operation codes defined in `proto.h`.
const OPCODES: &str = "proto.h";
/// Type group identifier for public ZooKeeper status codes (`enum ZOO_ERRORS`).
const ZOO_ERRORS: &str = "enum ZOO_ERRORS";

/// Descriptor for a single property measured by the generated C probe program.
struct CFact {
    /// Enclosing `#if` preprocessor conditions gating this property.
    cond: Vec<String>,
    fact: &'static str,
    ty: String,
    member: String,
    /// C constant expression evaluated by the probe binary.
    expr: String,
}

impl CFact {
    fn new(fact: &'static str, ty: &str, member: &str, expr: String) -> Self {
        Self {
            cond: Vec::new(),
            fact,
            ty: ty.to_owned(),
            member: member.to_owned(),
            expr,
        }
    }
}

/// Optional external dependency gating conditional public header declarations.
struct Feature {
    /// Preprocessor macro enabling the feature in the public C headers.
    define: &'static str,
    /// Environment variable overriding compiler include flags for this dependency.
    env: &'static str,
    /// Package name queried via `pkg-config` when the environment override is unset.
    pkg: &'static str,
}

const SSL: Feature = Feature {
    define: "HAVE_OPENSSL_H",
    env: "ZK_LAYOUT_SSL_CFLAGS",
    pkg: "openssl",
};

const SASL: Feature = Feature {
    define: "HAVE_CYRUS_SASL_H",
    env: "ZK_LAYOUT_SASL_CFLAGS",
    pkg: "libsasl2",
};

/// Build feature permutations of the drop-in C library, each exposing a distinct subset of
/// public C types.
const CONFIGS: &[(&str, &[&Feature])] = &[
    ("plain", &[]),
    ("ssl", &[&SSL]),
    ("sasl", &[&SASL]),
    ("full", &[&SSL, &SASL]),
];

/// Public C client headers included by the generated layout probe.
const PUBLIC_HEADERS: &[&str] = &[
    "zookeeper.h",
    "zookeeper_log.h",
    "recordio.h",
    "proto.h",
    "zookeeper_version.h",
];

fn read(path: &Path) -> BuildResult<String> {
    println!("cargo::rerun-if-changed={}", path.display());
    fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))
}

/// Strips C block and line comments from `s` while preserving newline positions.
fn strip_comments(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars().peekable();
    while let Some(c) = it.next() {
        match c {
            '/' if it.peek() == Some(&'*') => {
                it.next();
                let mut prev = ' ';
                for d in it.by_ref() {
                    if prev == '*' && d == '/' {
                        break;
                    }
                    if d == '\n' {
                        out.push('\n');
                    }
                    prev = d;
                }
                out.push(' ');
            }
            '/' if it.peek() == Some(&'/') => {
                for d in it.by_ref() {
                    if d == '\n' {
                        out.push('\n');
                        break;
                    }
                }
            }
            '"' | '\'' => {
                out.push(c);
                let mut esc = false;
                for d in it.by_ref() {
                    out.push(d);
                    if esc {
                        esc = false;
                    } else if d == '\\' {
                        esc = true;
                    } else if d == c {
                        break;
                    }
                }
            }
            _ => out.push(c),
        }
    }
    out
}

fn is_ident(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

/// Extracts the leading C/Rust identifier prefix from `s`.
fn leading_ident(s: &str) -> &str {
    let end = s.find(|c: char| !is_ident(c)).unwrap_or(s.len());
    s.get(..end).unwrap_or_default()
}

/// Extracts the trailing C/Rust identifier suffix from `s`.
fn trailing_ident(s: &str) -> &str {
    let start = s
        .char_indices()
        .rev()
        .find(|(_, c)| !is_ident(*c))
        .map_or(0, |(i, c)| i.saturating_add(c.len_utf8()));
    s.get(start..).unwrap_or_default()
}

/// Extracts the brace-delimited body starting at the opening brace of `s`, returning the
/// inner body slice and the remainder following the matching closing brace.
fn braced(s: &str) -> Option<(&str, &str)> {
    let body = s.strip_prefix('{')?;
    let mut depth = 0_u32;
    for (i, c) in body.char_indices() {
        match c {
            '{' => depth = depth.checked_add(1)?,
            '}' if depth == 0 => return Some((body.get(..i)?, body.get(i..)?.strip_prefix('}')?)),
            '}' => depth = depth.checked_sub(1)?,
            _ => {}
        }
    }
    None
}

/// Extracts the field identifier from a single C struct member declaration.
fn member_name(decl: &str) -> Option<String> {
    let d = decl.trim();
    if let Some((_, rest)) = d.split_once("(*") {
        let name = leading_ident(rest.trim_start());
        return (!name.is_empty()).then(|| name.to_owned());
    }
    let mut d = d;
    while d.ends_with(']') {
        let open = d.rfind('[')?;
        d = d.get(..open)?.trim_end();
    }
    let name = trailing_ident(d);
    (!name.is_empty()).then(|| name.to_owned())
}

/// Generates `sizeof`, `alignof`, `offsetof`, and `fieldsize` probe descriptors for a C
/// struct and its declared members.
fn struct_facts(ty: &str, members: &[String]) -> Vec<CFact> {
    let mut out = vec![
        CFact::new("sizeof", ty, "-", format!("sizeof({ty})")),
        CFact::new("alignof", ty, "-", format!("_Alignof({ty})")),
    ];
    for m in members {
        out.push(CFact::new(
            "offsetof",
            ty,
            m,
            format!("offsetof({ty}, {m})"),
        ));
        out.push(CFact::new(
            "fieldsize",
            ty,
            m,
            format!("sizeof((({ty} *)0)->{m})"),
        ));
    }
    out
}

/// Parses a single constant expression entry from the generated public layout probe.
fn probe_expr(e: &str) -> Option<(&'static str, String, String)> {
    if let Some(t) = e.strip_prefix("sizeof(").and_then(|t| t.strip_suffix(')')) {
        return Some(("sizeof", t.to_owned(), "-".to_owned()));
    }
    if let Some(t) = e
        .strip_prefix("_Alignof(")
        .and_then(|t| t.strip_suffix(')'))
    {
        return Some(("alignof", t.to_owned(), "-".to_owned()));
    }
    if let Some(t) = e
        .strip_prefix("((")
        .and_then(|t| t.strip_suffix(")-1 < 0)"))
    {
        return Some(("signed", t.to_owned(), "-".to_owned()));
    }
    let inner = e.strip_prefix("offsetof(")?.strip_suffix(')')?;
    let (t, m) = inner.rsplit_once(", ")?;
    Some(("offsetof", t.to_owned(), m.to_owned()))
}

/// Extracts layout probe descriptors for all public C structures from `probe.c` (synthesized
/// by `cargo xtask layout gen`).
fn public_facts(probe: &str) -> BuildResult<Vec<CFact>> {
    let mut cond: Vec<String> = Vec::new();
    let mut out = Vec::new();
    for line in probe.lines() {
        let t = line.trim();
        if let Some(c) = t.strip_prefix("#if ") {
            cond.push(c.to_owned());
            continue;
        }
        if t == "#endif" {
            cond.pop();
            continue;
        }
        if t.starts_with('#') && !t.starts_with("#include") {
            return Err(format!("probe.c: unknown line {t}"));
        }
        if !line.starts_with("    ") {
            continue;
        }
        let Some(e) = t.strip_suffix(',') else {
            continue;
        };
        let (fact, ty, member) =
            probe_expr(e).ok_or_else(|| format!("probe.c: unknown fact {e}"))?;
        let mut f = CFact::new(fact, &ty, &member, e.to_owned());
        f.cond.clone_from(&cond);
        out.push(f);
        if fact == "offsetof" {
            let mut s = CFact::new(
                "fieldsize",
                &ty,
                &member,
                format!("sizeof((({ty} *)0)->{member})"),
            );
            s.cond.clone_from(&cond);
            out.push(s);
        }
    }
    if out.is_empty() {
        return Err("probe.c: no facts".to_owned());
    }
    Ok(out)
}

/// Extracts `typedef <kw> NAME { ... } NAME;` declaration blocks from a C header.
fn typedef_blocks<'a>(text: &'a str, kw: &str) -> Vec<(String, &'a str)> {
    let mut out = Vec::new();
    let pat = format!("typedef {kw} ");
    let mut rest = text;
    while let Some((_, after)) = rest.split_once(pat.as_str()) {
        rest = after;
        let name = leading_ident(after);
        let tail = after.get(name.len()..).unwrap_or_default().trim_start();
        if let Some((body, _)) = braced(tail) {
            out.push((name.to_owned(), body));
        }
    }
    out
}

/// Extracts enumerator identifiers from a C `enum` declaration body.
fn enum_variants(body: &str) -> Vec<String> {
    body.split(',')
        .map(|v| leading_ident(v.trim()).to_owned())
        .filter(|v| !v.is_empty())
        .collect()
}

/// Extracts `#define NAME <integer>` constant identifiers matching `prefix`.
fn int_defines(text: &str, prefix: &str) -> Vec<String> {
    text.lines()
        .filter_map(|l| {
            let mut w = l.split_whitespace();
            let (Some("#define"), Some(name), Some(v), None) =
                (w.next(), w.next(), w.next(), w.next())
            else {
                return None;
            };
            let digits = v.strip_prefix('-').unwrap_or(v);
            let int = !digits.is_empty() && digits.chars().all(|c| c.is_ascii_digit());
            (name.starts_with(prefix) && int).then(|| name.to_owned())
        })
        .collect()
}

/// Extracts layout and constant probe descriptors for private bridge declarations in `zkrs.h`.
fn private_facts(zkrs_h: &str) -> BuildResult<Vec<CFact>> {
    let text = strip_comments(zkrs_h);
    let mut out = Vec::new();
    for name in int_defines(&text, "ZKRS_") {
        out.push(CFact::new("value", ZKRS_DEFINES, &name, name.clone()));
    }
    for (name, body) in typedef_blocks(&text, "enum") {
        out.push(CFact::new("sizeof", &name, "-", format!("sizeof({name})")));
        out.push(CFact::new(
            "alignof",
            &name,
            "-",
            format!("_Alignof({name})"),
        ));
        for v in enum_variants(body) {
            out.push(CFact::new("value", &name, &v, v.clone()));
        }
    }
    for (name, body) in typedef_blocks(&text, "struct") {
        let mut members = Vec::new();
        for decl in body.split(';').filter(|d| !d.trim().is_empty()) {
            members.push(
                member_name(decl).ok_or_else(|| format!("zkrs.h: {name}: cannot parse {decl}"))?,
            );
        }
        out.extend(struct_facts(&name, &members));
    }
    if out.is_empty() {
        return Err("zkrs.h: no types".to_owned());
    }
    Ok(out)
}

/// Extracts public enumeration and constant descriptors mirrored by Rust tables: result codes
/// in `zookeeper.h` and protocol opcodes in `proto.h`.
fn value_facts(zookeeper_h: &str, proto_h: &str) -> BuildResult<Vec<CFact>> {
    let text = strip_comments(zookeeper_h);
    let body = text
        .split_once(ZOO_ERRORS)
        .and_then(|(_, rest)| braced(rest.trim_start()))
        .map(|(body, _)| body)
        .ok_or_else(|| format!("zookeeper.h: no {ZOO_ERRORS}"))?;
    let mut out = Vec::new();
    for v in enum_variants(body) {
        out.push(CFact::new("value", ZOO_ERRORS, &v, v.clone()));
    }
    for name in int_defines(&strip_comments(proto_h), "ZOO_") {
        // Strip the `ZOO_` prefix and `_OP` suffix to align with Rust opcode constant names.
        let short = name.strip_prefix("ZOO_").unwrap_or(&name);
        let short = short.strip_suffix("_OP").unwrap_or(short);
        out.push(CFact::new("value", OPCODES, short, name.clone()));
    }
    Ok(out)
}

/// Formats an ASCII identifier as a quoted string literal valid in both C and Rust.
fn lit(s: &str) -> String {
    format!("{s:?}")
}

/// Synthesizes the standalone C probe source file that prints every measured property.
fn c_program(facts: &[CFact]) -> String {
    let mut s = String::from(
        "/* Generated by the zk-ffi-layout build script. */\n\
         #include <stddef.h>\n#include <stdio.h>\n",
    );
    for h in PUBLIC_HEADERS {
        let _ = writeln!(s, "#include <{h}>");
    }
    s.push_str("#include \"zkrs.h\"\n\n");
    s.push_str(
        "struct zk_fact {\n    const char *fact, *type, *member;\n    long long value;\n};\n\n",
    );
    s.push_str("static const struct zk_fact zk_facts[] = {\n");
    for f in facts {
        for c in &f.cond {
            let _ = writeln!(s, "#if {c}");
        }
        let _ = writeln!(
            s,
            "    {{{}, {}, {}, (long long)({})}},",
            lit(f.fact),
            lit(&f.ty),
            lit(&f.member),
            f.expr
        );
        for _ in &f.cond {
            s.push_str("#endif\n");
        }
    }
    s.push_str("};\n\n");
    s.push_str(
        "int main(void)\n{\n    size_t i;\n    for (i = 0; i < sizeof(zk_facts) / sizeof(zk_facts[0]); i++) {\n        \
         printf(\"%s\\t%s\\t%s\\t%lld\\n\", zk_facts[i].fact, zk_facts[i].type, zk_facts[i].member,\n               \
         zk_facts[i].value);\n    }\n    return fflush(stdout) == 0 ? 0 : 1;\n}\n",
    );
    s
}

/// Resolves candidate compiler include flag sets for an optional library dependency.
fn feature_flags(f: &Feature) -> Vec<Vec<String>> {
    println!("cargo::rerun-if-env-changed={}", f.env);
    if let Ok(v) = env::var(f.env) {
        return vec![v.split_whitespace().map(str::to_owned).collect()];
    }
    let mut out = vec![Vec::new()];
    let pkg_config = env::var("PKG_CONFIG").unwrap_or_else(|_| "pkg-config".to_owned());
    if let Ok(o) = Command::new(pkg_config).args(["--cflags", f.pkg]).output() {
        if o.status.success() {
            let flags: Vec<String> = String::from_utf8_lossy(&o.stdout)
                .split_whitespace()
                .map(str::to_owned)
                .collect();
            if !flags.is_empty() {
                out.push(flags);
            }
        }
    }
    out
}

/// Computes the Cartesian product of preprocessor defines and candidate header flag sets for
/// a build configuration.
fn config_flags(features: &[&Feature]) -> Vec<Vec<String>> {
    let mut sets = vec![Vec::new()];
    for f in features {
        let mut next = Vec::new();
        for set in &sets {
            for extra in feature_flags(f) {
                let mut s: Vec<String> = set.clone();
                s.push(format!("-D{}", f.define));
                s.extend(extra);
                next.push(s);
            }
        }
        sets = next;
    }
    sets
}

/// Captured probe execution output for a single header configuration.
struct Measured {
    config: &'static str,
    flags: String,
    error: String,
    /// Parsed `(fact, type, member, value)` tuples emitted by the C probe.
    facts: Vec<(String, String, String, i64)>,
}

/// Compiles and executes the C probe program under a specific compiler flag set.
fn run_probe(
    tool: &cc::Tool,
    base: &[String],
    flags: &[String],
    src: &Path,
    exe: &Path,
) -> BuildResult<Vec<(String, String, String, i64)>> {
    let out = tool
        .to_command()
        .arg("-std=gnu11")
        .args(base)
        .args(flags)
        .arg(src)
        .arg("-o")
        .arg(exe)
        .output()
        .map_err(|e| format!("cannot run the C compiler: {e}"))?;
    if !out.status.success() {
        return Err(String::from_utf8_lossy(&out.stderr).into_owned());
    }
    let run = Command::new(exe)
        .output()
        .map_err(|e| format!("{}: {e}", exe.display()))?;
    if !run.status.success() {
        return Err(format!("{} failed", exe.display()));
    }
    let mut facts = Vec::new();
    for line in String::from_utf8_lossy(&run.stdout).lines() {
        let cols: Vec<&str> = line.split('\t').collect();
        let [fact, ty, member, value] = cols.as_slice() else {
            return Err(format!("bad probe line: {line}"));
        };
        let value = value
            .parse::<i64>()
            .map_err(|e| format!("bad probe value in {line}: {e}"))?;
        facts.push((
            (*fact).to_owned(),
            (*ty).to_owned(),
            (*member).to_owned(),
            value,
        ));
    }
    Ok(facts)
}

/// Compiles and executes the C layout probe across all selected build configurations.
fn measure(c_dir: &Path, shim: &Path, program: &str, out_dir: &Path) -> BuildResult<Vec<Measured>> {
    let src = out_dir.join("zk_layout_probe.c");
    fs::write(&src, program).map_err(|e| format!("{}: {e}", src.display()))?;
    let tool = cc::Build::new()
        .cargo_metadata(false)
        .warnings(false)
        .get_compiler();
    let base = vec![
        format!("-I{}", c_dir.join("include").display()),
        format!("-I{}", c_dir.join("generated").display()),
        format!("-I{}", shim.display()),
    ];
    println!("cargo::rerun-if-env-changed=ZK_LAYOUT_CONFIGS");
    println!("cargo::rerun-if-env-changed=PKG_CONFIG");
    println!("cargo::rerun-if-env-changed=PKG_CONFIG_PATH");
    let selected: Option<BTreeSet<String>> = env::var("ZK_LAYOUT_CONFIGS")
        .ok()
        .map(|v| v.split(',').map(|s| s.trim().to_owned()).collect());
    if let Some(sel) = &selected {
        for s in sel {
            if !CONFIGS.iter().any(|(n, _)| n == s) {
                return Err(format!("ZK_LAYOUT_CONFIGS: unknown configuration {s}"));
            }
        }
    }
    let mut out = Vec::new();
    for (config, features) in CONFIGS {
        if selected.as_ref().is_some_and(|s| !s.contains(*config)) {
            continue;
        }
        let exe = out_dir.join(format!("zk_layout_probe_{config}"));
        let mut m = Measured {
            config,
            flags: String::new(),
            error: String::new(),
            facts: Vec::new(),
        };
        for flags in config_flags(features) {
            m.flags = flags.join(" ");
            match run_probe(&tool, &base, &flags, &src, &exe) {
                Ok(facts) => {
                    m.facts = facts;
                    m.error.clear();
                    break;
                }
                Err(e) => m.error = e,
            }
        }
        out.push(m);
    }
    Ok(out)
}

/// Serializes the measured C probe results as a static Rust array declaration.
fn c_table(measured: &[Measured]) -> String {
    let mut s = String::from(
        "/// Captured C layout probe results across build configurations.\n\
         pub(crate) static C_PROBES: &[Probe] = &[\n",
    );
    for m in measured {
        let _ = writeln!(
            s,
            "    Probe {{\n        config: {},\n        cflags: {},\n        error: {},\n        facts: &[",
            lit(m.config),
            lit(&m.flags),
            lit(&m.error)
        );
        for (fact, ty, member, value) in &m.facts {
            let _ = writeln!(
                s,
                "            Fact::new({}, {}, {}, {value}),",
                lit(fact),
                lit(ty),
                lit(member)
            );
        }
        s.push_str("        ],\n    },\n");
    }
    s.push_str("];\n");
    s
}

/// Parsed `#[repr(C)]` type or constant declaration extracted from a Rust source file.
enum Item {
    Struct {
        rust: String,
        c: String,
        fields: Vec<String>,
    },
    Enum {
        rust: String,
        variants: Vec<String>,
    },
    Const {
        name: String,
    },
}

/// Extracts the backtick-enclosed C type identifier from the first doc comment line of a
/// legacy mirror (for example, `struct Stat` or `zoo_op_t.create_op`).
fn doc_c_name(doc: &[String]) -> Option<String> {
    let (_, rest) = doc.first()?.split_once('`')?;
    let (name, _) = rest.split_once('`')?;
    Some(name.to_owned())
}

/// Extracts the item identifier following `kw` (and optional visibility modifiers) on a line
/// that opens a brace block.
fn item_name<'a>(line: &'a str, kw: &str) -> Option<&'a str> {
    let rest = line
        .strip_prefix("pub(crate) ")
        .or_else(|| line.strip_prefix("pub "))
        .unwrap_or(line);
    let rest = rest.strip_prefix(kw)?.strip_suffix(" {")?;
    (leading_ident(rest) == rest).then_some(rest)
}

/// Extracts the field identifier (preserving any `r#` raw identifier prefix) from a
/// four-space-indented struct field declaration line.
fn field_name(line: &str) -> Option<&str> {
    let rest = line.strip_prefix("    ")?;
    if rest.starts_with(' ') {
        return None;
    }
    let rest = rest
        .strip_prefix("pub(crate) ")
        .or_else(|| rest.strip_prefix("pub "))
        .unwrap_or(rest);
    let (name, _) = rest.split_once(": ")?;
    let bare = name.strip_prefix("r#").unwrap_or(name);
    (!bare.is_empty() && leading_ident(bare) == bare).then_some(name)
}

/// Parses `#[repr(C)]` structs, enums, and top-level constants matching `const_prefix` from a
/// Rust source file. When `legacy` is true, corresponding C type names are extracted from doc
/// comments; otherwise Rust item names match their C counterparts directly.
fn rust_items(
    text: &str,
    file: &str,
    legacy: bool,
    const_prefix: Option<&str>,
) -> BuildResult<Vec<Item>> {
    let mut out = Vec::new();
    let mut doc: Vec<String> = Vec::new();
    let mut repr_c = false;
    let mut lines = text.lines();
    while let Some(line) = lines.next() {
        if line.starts_with("///") {
            doc.push(line.to_owned());
            continue;
        }
        if line.starts_with("#[") {
            repr_c |= line == "#[repr(C)]";
            continue;
        }
        if let Some(prefix) = const_prefix {
            if let Some(rest) = line.strip_prefix("pub const ") {
                let name = leading_ident(rest);
                if name.starts_with(prefix) {
                    out.push(Item::Const {
                        name: name.to_owned(),
                    });
                }
            }
        }
        if repr_c {
            if let Some(name) = item_name(line, "struct ") {
                let c = if legacy {
                    doc_c_name(&doc)
                        .ok_or_else(|| format!("{file}: {name} has no C name in its doc"))?
                } else {
                    name.to_owned()
                };
                let mut fields = Vec::new();
                for l in lines.by_ref() {
                    if l == "}" {
                        break;
                    }
                    if let Some(f) = field_name(l) {
                        fields.push(f.to_owned());
                    }
                }
                // Dotted C identifiers denote union variant mirrors inside `zoo_op_t`, whose
                // offsets are defined relative to the parent union in `ZOO_OP_FACTS`.
                if !c.contains('.') {
                    out.push(Item::Struct {
                        rust: name.to_owned(),
                        c,
                        fields,
                    });
                }
            } else if let Some(name) = item_name(line, "enum ") {
                let mut variants = Vec::new();
                for l in lines.by_ref() {
                    if l == "}" {
                        break;
                    }
                    let Some(v) = l.strip_prefix("    ") else {
                        continue;
                    };
                    let id = leading_ident(v);
                    let tail = v.get(id.len()..).unwrap_or_default();
                    if !id.is_empty() && (tail == "," || tail.starts_with(" = ")) {
                        variants.push(id.to_owned());
                    }
                }
                out.push(Item::Enum {
                    rust: name.to_owned(),
                    variants,
                });
            }
        }
        doc.clear();
        repr_c = false;
    }
    Ok(out)
}

/// Synthesizes the Rust fact table as compile-time constant expressions evaluated by `rustc`.
fn rust_table(groups: &[(&str, &str, &[Item])]) -> String {
    let mut s = String::from(
        "/// Compile-time Rust layout and constant facts for `zk-ffi` and `zk-jute`.\n\
         pub(crate) static RUST_FACTS: &[Fact] = &[\n",
    );
    for (module, value_ty, items) in groups {
        for item in *items {
            match item {
                Item::Struct { rust, c, fields } => {
                    let t = format!("{module}::{rust}");
                    let c = lit(c);
                    let _ = writeln!(
                        s,
                        "    Fact::new(\"sizeof\", {c}, \"-\", n(size_of::<{t}>())),"
                    );
                    let _ = writeln!(
                        s,
                        "    Fact::new(\"alignof\", {c}, \"-\", n(align_of::<{t}>())),"
                    );
                    for f in fields {
                        let bare = lit(f.strip_prefix("r#").unwrap_or(f));
                        let _ = writeln!(
                            s,
                            "    Fact::new(\"offsetof\", {c}, {bare}, n(offset_of!({t}, {f}))),"
                        );
                        let _ = writeln!(
                            s,
                            "    Fact::new(\"fieldsize\", {c}, {bare}, field_size(|v: &{t}| &v.{f})),"
                        );
                    }
                }
                Item::Enum { rust, variants } => {
                    let t = format!("{module}::{rust}");
                    let c = lit(rust);
                    let _ = writeln!(
                        s,
                        "    Fact::new(\"sizeof\", {c}, \"-\", n(size_of::<{t}>())),"
                    );
                    let _ = writeln!(
                        s,
                        "    Fact::new(\"alignof\", {c}, \"-\", n(align_of::<{t}>())),"
                    );
                    for v in variants {
                        let _ = writeln!(
                            s,
                            "    Fact::new(\"value\", {c}, {}, {t}::{v} as i64),",
                            lit(v)
                        );
                    }
                }
                Item::Const { name } => {
                    let _ = writeln!(
                        s,
                        "    Fact::new(\"value\", {}, {}, {module}::{name} as i64),",
                        lit(value_ty),
                        lit(name)
                    );
                }
            }
        }
    }
    s.push_str("];\n");
    s
}

fn main() -> BuildResult<()> {
    println!("cargo::rerun-if-changed=build.rs");
    println!("cargo::rerun-if-env-changed=ZK_LAYOUT_C_DIR");
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").map_err(|e| e.to_string())?);
    let out_dir = PathBuf::from(env::var("OUT_DIR").map_err(|e| e.to_string())?);
    let crates = manifest.join("..");
    let rs = crates.join("..");

    let family = env::var("CARGO_CFG_TARGET_FAMILY").unwrap_or_default();
    if !family.split(',').any(|f| f == "unix") {
        return Err("zk-ffi-layout supports Unix targets only".to_owned());
    }
    if env::var("TARGET").ok() != env::var("HOST").ok() {
        return Err(
            "zk-ffi-layout runs its C probe on the build host, so the target must be \
                    the host. Use `cargo xtask layout expect` for another target."
                .to_owned(),
        );
    }

    let c_dir = env::var_os("ZK_LAYOUT_C_DIR")
        .map_or_else(|| rs.join("../zookeeper-client-c"), PathBuf::from);
    let jute_h = c_dir.join("generated/zookeeper.jute.h");
    if !jute_h.is_file() {
        return Err(format!(
            "{} is missing. Run `mvn -pl zookeeper-jute generate-sources` from the top \
             level directory.",
            jute_h.display()
        ));
    }
    read(&jute_h)?;
    for h in PUBLIC_HEADERS {
        read(&c_dir.join("include").join(h))?;
    }
    let shim = rs.join("c/shim");

    // Measure layout and constant facts across all configured C header permutations.
    let mut facts = public_facts(&read(&rs.join("c/tests/abi/layout/probe.c"))?)?;
    facts.extend(private_facts(&read(&shim.join("zkrs.h"))?)?);
    facts.extend(value_facts(
        &read(&c_dir.join("include/zookeeper.h"))?,
        &read(&c_dir.join("include/proto.h"))?,
    )?);
    let measured = measure(&c_dir, &shim, &c_program(&facts), &out_dir)?;

    // Synthesize corresponding compile-time layout and constant expressions from Rust sources.
    let legacy = rust_items(
        &read(&crates.join("zk-ffi/src/abi/legacy.rs"))?,
        "legacy.rs",
        true,
        None,
    )?;
    let types = rust_items(
        &read(&crates.join("zk-ffi/src/abi/types.rs"))?,
        "types.rs",
        false,
        Some("ZKRS_"),
    )?;
    let rc = rust_items(
        &read(&crates.join("zk-jute/src/rc.rs"))?,
        "rc.rs",
        false,
        Some("Z"),
    )?;
    let opcode = rust_items(
        &read(&crates.join("zk-jute/src/opcode.rs"))?,
        "opcode.rs",
        false,
        Some(""),
    )?;
    let rust = rust_table(&[
        ("legacy", "", &legacy),
        ("types", ZKRS_DEFINES, &types),
        ("rc", ZOO_ERRORS, &rc),
        ("opcode", OPCODES, &opcode),
    ]);

    let mut generated = c_table(&measured);
    generated.push('\n');
    generated.push_str(&rust);
    let path = out_dir.join("facts.rs");
    fs::write(&path, generated).map_err(|e| format!("{}: {e}", path.display()))
}
