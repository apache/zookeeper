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

//! ABI golden file generation and binary symbol verification: `cargo xtask abi golden`,
//! `cargo xtask abi check`, and `cargo xtask abi static`.
//!
//! Ground truth symbol expectations are extracted directly from a legacy C reference build.
//! Conformance checks independently parse compiled dynamic libraries and static archives,
//! sharing no code with build time export list generators to ensure unbiased verification.

use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

use crate::headers::{self, Decl};
use crate::objfile::{self, Binary, Format, SymKind, Symbol};
use crate::util::{self, Error};

/// Tab-separated column header for exported dynamic library symbols (`symbols.tsv`).
pub(crate) const SYMBOLS_COLUMNS: &str =
    "name\tkind\tflavors\tfeature\trw\tsize_lp64\tsize_ilp32\tdecl\tnote";
/// Tab-separated column header for static-archive-only global symbols (`static-symbols.tsv`).
pub(crate) const STATIC_COLUMNS: &str = "name\tkind\tdecl\tnote";
/// Tab-separated column header for forbidden or internal non-exported symbols (`absent.tsv`).
pub(crate) const ABSENT_COLUMNS: &str = "name\tflavors\tclass\tdecl";

/// Expected exported symbol counts across reference build configurations:
/// `(config_name, mt_count, st_count)`.
const TOTALS: [(&str, usize, usize); 4] = [
    ("plain", 201, 178),
    ("ssl", 202, 179),
    ("sasl", 210, 187),
    ("full", 211, 188),
];

/// Expected number of static-archive-only global symbols in `static-symbols.tsv`.
const STATIC_TOTAL: usize = 179;

/// Legacy symbol names matched by historical export regexes that lack any source definition.
const NEVER_DEFINED: &[(&str, &str)] = &[(
    "format_log_message",
    "matches the legacy export regex; no source defines it",
)];

/// Threading flavor of the ZooKeeper client library.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(crate) enum Flavor {
    /// multithreaded runtime library variant (`libzookeeper_mt`).
    Mt,
    /// single threaded synchronous runtime library variant (`libzookeeper_st`).
    St,
}

impl Flavor {
    /// All supported library threading flavors.
    const ALL: [Self; 2] = [Self::Mt, Self::St];

    /// Canonical short string representation used in filenames, directories, and tables.
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::Mt => "mt",
            Self::St => "st",
        }
    }

    /// Parses a flavor identifier string ("mt" or "st").
    pub(crate) fn parse(s: &str) -> Result<Self, Error> {
        match s {
            "mt" => Ok(Self::Mt),
            "st" => Ok(Self::St),
            _ => Err(Error::usage(format!("unknown flavor `{s}`: use mt or st"))),
        }
    }
}

/// Configurable optional protocol and security features enabled for a build target.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Features {
    /// Indicates whether TLS/SSL transport security is compiled in.
    pub(crate) ssl: bool,
    /// Indicates whether SASL authentication support is compiled in.
    pub(crate) sasl: bool,
}

impl Features {
    /// Standard reference configurations mapped to their feature activations.
    const CONFIGS: [(&'static str, Self); 4] = [
        (
            "plain",
            Self {
                ssl: false,
                sasl: false,
            },
        ),
        (
            "ssl",
            Self {
                ssl: true,
                sasl: false,
            },
        ),
        (
            "sasl",
            Self {
                ssl: false,
                sasl: true,
            },
        ),
        (
            "full",
            Self {
                ssl: true,
                sasl: true,
            },
        ),
    ];

    /// Resolves features from a predefined configuration profile name.
    pub(crate) fn from_config(s: &str) -> Result<Self, Error> {
        Self::CONFIGS
            .iter()
            .find(|(n, _)| *n == s)
            .map(|(_, f)| *f)
            .ok_or_else(|| {
                Error::usage(format!(
                    "unknown configuration `{s}`: use plain, ssl, sasl or full"
                ))
            })
    }

    /// Parses a comma-separated list of feature flags (e.g. `ssl,sasl`, `none`, or empty).
    pub(crate) fn from_list(s: &str) -> Result<Self, Error> {
        let mut f = Self {
            ssl: false,
            sasl: false,
        };
        for part in s
            .split(',')
            .map(str::trim)
            .filter(|p| !p.is_empty() && *p != "none")
        {
            match part {
                "ssl" | "tls" => f.ssl = true,
                "sasl" => f.sasl = true,
                _ => {
                    return Err(Error::usage(format!(
                        "unknown feature `{part}`: use ssl and sasl"
                    )));
                }
            }
        }
        Ok(f)
    }

    /// Returns the canonical profile name matching this feature combination.
    pub(crate) fn name(self) -> &'static str {
        Self::CONFIGS
            .iter()
            .find(|(_, f)| *f == self)
            .map_or("plain", |(n, _)| n)
    }

    /// Evaluates whether this configuration satisfies the feature constraint of a symbol row.
    fn has(self, feature: &str) -> bool {
        match feature {
            "ssl" => self.ssl,
            "sasl" => self.sasl,
            _ => true,
        }
    }
}

/// Single symbol entry in the exported dynamic symbols specification (`symbols.tsv`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Row {
    /// C linkage symbol name.
    pub(crate) name: String,
    /// Classification as function code or data storage.
    pub(crate) kind: SymKind,
    /// Library flavors (`mt`, `st`) that must export this symbol.
    pub(crate) flavors: BTreeSet<Flavor>,
    /// Feature dependency gating this export (`-`, `ssl`, or `sasl`).
    pub(crate) feature: String,
    /// Mutability attribute for data symbols (`true` for writable, `false` for read-only).
    pub(crate) rw: Option<bool>,
    /// Expected object size in bytes under 64-bit LP64 data models.
    pub(crate) size_lp64: Option<u64>,
    /// Expected object size in bytes under 32-bit ILP32 data models.
    pub(crate) size_ilp32: Option<u64>,
    /// Originating header and line declaration (`<file>:<line>`), or `-` if undeclared.
    pub(crate) decl: String,
    /// ABI compliance annotation, provenance note, or defect cross-reference.
    pub(crate) note: String,
}

impl Row {
    /// Returns `true` if this export applies to the given library flavor and feature set.
    fn applies(&self, flavor: Flavor, f: Features) -> bool {
        self.flavors.contains(&flavor) && f.has(&self.feature)
    }

    /// Returns the expected object byte size for the target pointer width.
    fn size(&self, is64: bool) -> Option<u64> {
        if is64 {
            self.size_lp64
        } else {
            self.size_ilp32
        }
    }
}

/// Symbol entry for global definitions present only in static archives (`static-symbols.tsv`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct StaticRow {
    /// C linkage symbol name.
    pub(crate) name: String,
    /// Classification as function code or data storage.
    pub(crate) kind: SymKind,
    /// Originating header declaration (`<file>:<line>`), or `-` if undeclared.
    pub(crate) decl: String,
    /// Contextual note explaining static linkage visibility and provenance.
    pub(crate) note: String,
}

/// Symbol entry for internal or forbidden names that shared libraries must never export
/// (`absent.tsv`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct AbsentRow {
    /// C linkage symbol name.
    pub(crate) name: String,
    /// Library flavors where this symbol must remain unexported.
    pub(crate) flavors: BTreeSet<Flavor>,
    /// Rejection classification (`archive-only`, `internal`, `undefined`, or `mt-only`).
    pub(crate) class: String,
    /// Originating header declaration (`<file>:<line>`), or `-` if undeclared.
    pub(crate) decl: String,
}

fn parse_kind(s: &str) -> Result<SymKind, Error> {
    match s {
        "func" => Ok(SymKind::Func),
        "data" => Ok(SymKind::Data),
        _ => Err(Error::msg(format!("unknown kind `{s}`"))),
    }
}

fn parse_flavors(s: &str) -> Result<BTreeSet<Flavor>, Error> {
    s.split(',')
        .map(|f| Flavor::parse(f).map_err(|e| Error::msg(e.to_string())))
        .collect()
}

fn flavors_str(f: &BTreeSet<Flavor>) -> String {
    f.iter().map(|f| f.as_str()).collect::<Vec<_>>().join(",")
}

fn opt_u64(s: &str) -> Result<Option<u64>, Error> {
    if s == "-" {
        return Ok(None);
    }
    s.parse()
        .map(Some)
        .map_err(|_| Error::msg(format!("bad number `{s}`")))
}

fn opt_str<T: std::fmt::Display>(v: Option<T>) -> String {
    v.map_or_else(|| "-".to_owned(), |v| v.to_string())
}

/// Validates that a parsed TSV line contains exactly `n` columns, returning the column slice.
fn fields<'a>(path: &str, line: usize, cols: &[&'a str], n: usize) -> Result<Vec<&'a str>, Error> {
    if cols.len() == n {
        Ok(cols.to_vec())
    } else {
        Err(Error::msg(format!(
            "{path}:{line}: expected {n} columns, found {}",
            cols.len()
        )))
    }
}

/// Parses the dynamic library symbol export baseline (`symbols.tsv`).
pub(crate) fn parse_symbols(text: &str) -> Result<Vec<Row>, Error> {
    let mut out = Vec::new();
    for (n, cols) in util::tsv_rows(text, SYMBOLS_COLUMNS)? {
        let at = |e: Error| Error::msg(format!("symbols.tsv:{n}: {e}"));
        let f = fields("symbols.tsv", n, &cols, 9)?;
        let get = |i: usize| f.get(i).copied().unwrap_or("-");
        let rw = match get(4) {
            "rw" => Some(true),
            "ro" => Some(false),
            "-" => None,
            other => return Err(Error::msg(format!("symbols.tsv:{n}: bad rw `{other}`"))),
        };
        if !matches!(get(3), "-" | "ssl" | "sasl") {
            return Err(Error::msg(format!(
                "symbols.tsv:{n}: bad feature `{}`",
                get(3)
            )));
        }
        out.push(Row {
            name: get(0).to_owned(),
            kind: parse_kind(get(1)).map_err(at)?,
            flavors: parse_flavors(get(2)).map_err(at)?,
            feature: get(3).to_owned(),
            rw,
            size_lp64: opt_u64(get(5)).map_err(at)?,
            size_ilp32: opt_u64(get(6)).map_err(at)?,
            decl: get(7).to_owned(),
            note: get(8).to_owned(),
        });
    }
    Ok(out)
}

/// Parses the static-archive-only global symbols catalog (`static-symbols.tsv`).
pub(crate) fn parse_static(text: &str) -> Result<Vec<StaticRow>, Error> {
    let mut out = Vec::new();
    for (n, cols) in util::tsv_rows(text, STATIC_COLUMNS)? {
        let f = fields("static-symbols.tsv", n, &cols, 4)?;
        let get = |i: usize| f.get(i).copied().unwrap_or("-");
        out.push(StaticRow {
            name: get(0).to_owned(),
            kind: parse_kind(get(1))
                .map_err(|e| Error::msg(format!("static-symbols.tsv:{n}: {e}")))?,
            decl: get(2).to_owned(),
            note: get(3).to_owned(),
        });
    }
    Ok(out)
}

/// Parses the forbidden symbol baseline (`absent.tsv`) of names shared libraries must not export.
pub(crate) fn parse_absent(text: &str) -> Result<Vec<AbsentRow>, Error> {
    let mut out = Vec::new();
    for (n, cols) in util::tsv_rows(text, ABSENT_COLUMNS)? {
        let f = fields("absent.tsv", n, &cols, 4)?;
        let get = |i: usize| f.get(i).copied().unwrap_or("-");
        out.push(AbsentRow {
            name: get(0).to_owned(),
            flavors: parse_flavors(get(1))
                .map_err(|e| Error::msg(format!("absent.tsv:{n}: {e}")))?,
            class: get(2).to_owned(),
            decl: get(3).to_owned(),
        });
    }
    Ok(out)
}

/// ABI golden specifications comprising shared library exports, static globals, and forbidden
/// symbols. In source tarballs packaged without the integration test suite, `absent` is `None`.
struct Goldens {
    symbols: Vec<Row>,
    statics: Vec<StaticRow>,
    absent: Option<Vec<AbsentRow>>,
}

/// Loads ABI golden specifications from `dir`, optionally including `absent.tsv` when present.
fn load_goldens(dir: &Path, absent: Option<&Path>) -> Result<Goldens, Error> {
    Ok(Goldens {
        symbols: parse_symbols(&util::read(&dir.join("symbols.tsv"))?)?,
        statics: parse_static(&util::read(&dir.join("static-symbols.tsv"))?)?,
        absent: absent.map(read_absent).transpose()?,
    })
}

/// Reads and parses the forbidden symbol specification file at `path`.
fn read_absent(path: &Path) -> Result<Vec<AbsentRow>, Error> {
    parse_absent(&util::read(path)?)
}

/// Resolves the default repository path for `tests/abi/absent.tsv`.
fn absent_path() -> PathBuf {
    util::rust_c_dir().join("tests/abi/absent.tsv")
}

/// Resolves the path to `absent.tsv`, checking `golden_dir` first (as produced by
/// `abi golden --out-dir`) and falling back to the repository `tests/abi` directory.
fn absent_golden(golden_dir: &Path) -> Option<PathBuf> {
    let own = golden_dir.join("absent.tsv");
    if own.is_file() {
        return Some(own);
    }
    util::rust_c_dir().join("tests").is_dir().then(absent_path)
}

// ---------------------------------------------------------------------
// Golden generation
// ---------------------------------------------------------------------

/// Shared library exports and static archive globals extracted from a reference legacy build.
struct RefLibs {
    /// Exported public dynamic symbols decoded from the shared library.
    exports: BTreeMap<String, Symbol>,
    /// Global symbols decoded from all member object files within the static archive.
    globals: BTreeMap<String, Symbol>,
}

/// Locates the versioned shared library (`.so.2` or `.2.dylib`) for `flavor` in `lib`.
fn shared_lib(lib: &Path, flavor: Flavor) -> Result<PathBuf, Error> {
    let f = flavor.as_str();
    [
        format!("libzookeeper_{f}.2.dylib"),
        format!("libzookeeper_{f}.so.2"),
    ]
    .iter()
    .map(|n| lib.join(n))
    .find(|p| p.is_file())
    .ok_or_else(|| {
        Error::msg(format!(
            "{}: no libzookeeper_{f} shared library",
            util::rel(lib)
        ))
    })
}

/// Inspects all member object files within a static archive (`.a`), returning the member count and
/// the union of all defined global symbols.
fn archive_globals(
    path: &Path,
    arch: Option<&str>,
) -> Result<(usize, BTreeMap<String, Symbol>), Error> {
    let members = objfile::parse_archive(&util::read_bytes(path)?, arch)
        .map_err(|e| Error::msg(format!("{}: {e}", util::rel(path))))?;
    let mut out = BTreeMap::new();
    for (_, m) in &members {
        for (n, s) in &m.symbols {
            out.entry(n.clone()).or_insert_with(|| s.clone());
        }
    }
    Ok((members.len(), out))
}

fn parse_lib(path: &Path, arch: Option<&str>) -> Result<Binary, Error> {
    objfile::parse(&util::read_bytes(path)?, arch)
        .map_err(|e| Error::msg(format!("{}: {e}", util::rel(path))))
}

fn load_ref(ref_dir: &Path) -> Result<BTreeMap<(&'static str, Flavor), RefLibs>, Error> {
    let mut out = BTreeMap::new();
    for (cfg, _) in Features::CONFIGS {
        let lib = ref_dir.join(format!("install-{cfg}/lib"));
        for flavor in Flavor::ALL {
            let so = parse_lib(&shared_lib(&lib, flavor)?, None)?;
            let (_, globals) = archive_globals(
                &lib.join(format!("libzookeeper_{}.a", flavor.as_str())),
                None,
            )?;
            out.insert(
                (cfg, flavor),
                RefLibs {
                    exports: so.symbols,
                    globals,
                },
            );
        }
    }
    Ok(out)
}

/// Formats a C declaration location as `<file>:<line>`, or `-` if undeclared.
fn decl_ref(d: Option<&Decl>) -> String {
    d.map_or_else(|| "-".to_owned(), |d| format!("{}:{}", d.file, d.line))
}

/// Returns the expected `(lp64, ilp32)` data object byte sizes for a declared C type.
fn data_size(ty: &str) -> Option<(u64, u64)> {
    match ty {
        "int" | "ZooLogLevel" => Some((4, 4)),
        "struct Id" | "struct ACL_vector" => Some((16, 8)),
        _ => None,
    }
}

/// Generates ABI provenance notes describing `ZOOAPI` visibility macros, `THREADED` guards, or
/// missing header declarations.
fn export_note(decl: Option<&Decl>, flavors: &BTreeSet<Flavor>) -> String {
    let mut notes = Vec::new();
    match decl {
        None => notes.push("not declared in an installed header (ABI-01)".to_owned()),
        Some(d) => {
            if !d.zooapi {
                notes.push("declared without ZOOAPI (ABI-01)".to_owned());
            }
            if flavors.len() == 1 && flavors.contains(&Flavor::Mt) && !d.cond.contains("THREADED") {
                notes.push("defined only in mt; declared outside the THREADED block".to_owned());
            }
            if d.cond.contains("!THREADED") && flavors.contains(&Flavor::Mt) {
                notes.push("declared only without THREADED; mt exports it too (ABI-01)".to_owned());
            }
        }
    }
    if notes.is_empty() {
        "-".to_owned()
    } else {
        notes.join("; ")
    }
}

/// Infers the feature gate (`-`, `ssl`, or `sasl`) from the set of configurations exporting a
/// symbol.
fn export_feature(cfgs: &BTreeSet<&str>) -> Option<&'static str> {
    let (ssl, sasl) = (cfgs.contains("ssl"), cfgs.contains("sasl"));
    if cfgs.contains("plain") {
        Some("-")
    } else if ssl != sasl {
        Some(if ssl { "ssl" } else { "sasl" })
    } else {
        None
    }
}

/// Returns the single element of a set, or `None` if the set is empty or has multiple elements.
fn only<T: Copy>(set: &BTreeSet<T>) -> Option<T> {
    let mut it = set.iter().copied();
    let first = it.next();
    if it.next().is_none() { first } else { None }
}

/// Synthesizes dynamic symbol export rows from reference libraries, header declarations, and
/// C source definitions.
fn golden_symbols(
    libs: &BTreeMap<(&'static str, Flavor), RefLibs>,
    decls: &BTreeMap<String, Decl>,
    defs: &BTreeMap<String, Decl>,
) -> Result<Vec<Row>, Error> {
    let mut seen: BTreeMap<String, BTreeSet<(&str, Flavor)>> = BTreeMap::new();
    for ((cfg, flavor), l) in libs {
        for n in l.exports.keys() {
            seen.entry(n.clone()).or_default().insert((cfg, *flavor));
        }
    }
    let mut rows = Vec::new();
    for (name, at) in &seen {
        let flavors: BTreeSet<Flavor> = at.iter().map(|(_, f)| *f).collect();
        let cfgs: BTreeSet<&str> = at.iter().map(|(c, _)| *c).collect();
        let feature = export_feature(&cfgs).ok_or_else(|| {
            Error::msg(format!(
                "{name}: exported in an unexpected set of configurations: {cfgs:?}"
            ))
        })?;
        if !flavors.contains(&Flavor::Mt) {
            return Err(Error::msg(format!("{name}: exported only by st")));
        }
        let mut kinds = BTreeSet::new();
        let mut writable = BTreeSet::new();
        let mut spans = Vec::new();
        for ((cfg, flavor), l) in libs {
            let f = Features::from_config(cfg)?;
            let want = flavors.contains(flavor) && f.has(feature);
            let have = l.exports.get(name);
            if want != have.is_some() {
                return Err(Error::msg(format!(
                    "{name}: export in {cfg}/{} does not follow flavors {} and feature {feature}",
                    flavor.as_str(),
                    flavors_str(&flavors)
                )));
            }
            if let Some(s) = have {
                kinds.insert(s.kind);
                writable.insert(s.writable);
                spans.extend(s.span.or(s.size));
            }
        }
        let kind = only(&kinds)
            .ok_or_else(|| Error::msg(format!("{name}: the kind differs between libraries")))?;
        if kind == SymKind::Other {
            return Err(Error::msg(format!(
                "{name}: the export is neither code nor data"
            )));
        }
        let decl = decls.get(name.as_str());
        let (rw, size_lp64, size_ilp32) = if kind == SymKind::Data {
            let d = decl.or_else(|| defs.get(name.as_str())).ok_or_else(|| {
                Error::msg(format!(
                    "{name}: data export without a declaration or definition"
                ))
            })?;
            let (lp, ilp) = data_size(&d.ty)
                .ok_or_else(|| Error::msg(format!("{name}: no size rule for type `{}`", d.ty)))?;
            let w = only(&writable).ok_or_else(|| {
                Error::msg(format!("{name}: writability differs between libraries"))
            })?;
            if w == d.is_const {
                return Err(Error::msg(format!(
                    "{name}: writable={w} contradicts the declaration"
                )));
            }
            if spans.iter().any(|&s| s < lp) {
                return Err(Error::msg(format!(
                    "{name}: the reference object is smaller than {lp} bytes"
                )));
            }
            (Some(w), Some(lp), Some(ilp))
        } else {
            (None, None, None)
        };
        rows.push(Row {
            name: name.clone(),
            kind,
            flavors: flavors.clone(),
            feature: feature.to_owned(),
            rw,
            size_lp64,
            size_ilp32,
            decl: decl_ref(decl),
            note: export_note(decl, &flavors),
        });
    }
    Ok(rows)
}

/// Computes the number of symbols in `rows` applicable to the specified flavor and feature set.
fn count(rows: &[Row], flavor: Flavor, f: Features) -> usize {
    rows.iter().filter(|r| r.applies(flavor, f)).count()
}

/// Extracts static-archive-only global symbols, including header-declared globals and
/// `recordio.c` `oa_*` / `ia_*` archive routines.
fn golden_static(
    libs: &BTreeMap<(&'static str, Flavor), RefLibs>,
    decls: &BTreeMap<String, Decl>,
) -> Result<Vec<StaticRow>, Error> {
    let mut sets: Vec<(String, BTreeMap<String, SymKind>)> = Vec::new();
    for ((cfg, flavor), l) in libs {
        let only: BTreeMap<String, SymKind> = l
            .globals
            .iter()
            .filter(|(n, _)| !l.exports.contains_key(n.as_str()))
            .filter(|(n, _)| {
                decls.contains_key(n.as_str()) || n.starts_with("oa_") || n.starts_with("ia_")
            })
            .map(|(n, s)| (n.clone(), s.kind))
            .collect();
        sets.push((format!("{cfg}/{}", flavor.as_str()), only));
    }
    let Some((first_at, first)) = sets.first() else {
        return Err(Error::msg("no reference libraries".to_owned()));
    };
    for (at, s) in &sets {
        if s != first {
            return Err(Error::msg(format!(
                "the archive-only set of {at} differs from {first_at}"
            )));
        }
    }
    Ok(first
        .iter()
        .map(|(n, k)| {
            let d = decls.get(n.as_str());
            let note = if d.is_none() {
                "recordio.c defines it globally; no installed header declares it".to_owned()
            } else {
                "archive only; the shared libraries do not export it".to_owned()
            };
            StaticRow {
                name: n.clone(),
                kind: *k,
                decl: decl_ref(d),
                note,
            }
        })
        .collect())
}

/// Derives the set of symbols that shared libraries must never export (`absent.tsv`).
fn golden_absent(
    libs: &BTreeMap<(&'static str, Flavor), RefLibs>,
    decls: &BTreeMap<String, Decl>,
    statics: &[StaticRow],
) -> Vec<AbsentRow> {
    let both: BTreeSet<Flavor> = Flavor::ALL.into_iter().collect();
    let st: BTreeSet<Flavor> = [Flavor::St].into_iter().collect();
    let static_names: BTreeSet<&str> = statics.iter().map(|s| s.name.as_str()).collect();
    let mut out: BTreeMap<String, AbsentRow> = BTreeMap::new();
    let mut add = |name: &str, flavors: &BTreeSet<Flavor>, class: &str| {
        out.entry(name.to_owned()).or_insert_with(|| AbsentRow {
            name: name.to_owned(),
            flavors: flavors.clone(),
            class: class.to_owned(),
            decl: decl_ref(decls.get(name)),
        });
    };
    for l in libs.values() {
        for n in l
            .globals
            .keys()
            .filter(|n| !l.exports.contains_key(n.as_str()))
        {
            let class = if static_names.contains(n.as_str()) {
                "archive-only"
            } else {
                "internal"
            };
            add(n, &both, class);
        }
    }
    for (cfg, _) in Features::CONFIGS {
        if let (Some(mt), Some(s)) = (libs.get(&(cfg, Flavor::Mt)), libs.get(&(cfg, Flavor::St))) {
            for n in mt
                .exports
                .keys()
                .filter(|n| !s.exports.contains_key(n.as_str()))
            {
                add(n, &st, "mt-only");
            }
        }
    }
    let defined: BTreeSet<&str> = libs
        .values()
        .flat_map(|l| l.globals.keys().chain(l.exports.keys()))
        .map(String::as_str)
        .collect();
    for (n, d) in decls {
        if d.func && !defined.contains(n.as_str()) {
            add(n, &both, "undefined");
        }
    }
    for (n, _) in NEVER_DEFINED {
        add(n, &both, "undefined");
    }
    out.into_values().collect()
}

fn header(title: &str, pin: &str, body: &[&str]) -> String {
    let mut s = util::tsv_license();
    let _ = writeln!(s, "# {title}");
    let _ = writeln!(s, "#");
    for l in body {
        let _ = writeln!(s, "#{}{l}", if l.is_empty() { "" } else { " " });
    }
    let _ = writeln!(s, "#");
    let _ = writeln!(
        s,
        "# Generated by `cargo xtask abi golden` from the legacy reference build"
    );
    let _ = writeln!(s, "# at commit {pin}. See README.md in this directory.");
    s
}

fn render_symbols(rows: &[Row], pin: &str) -> String {
    let mut s = header(
        "The export set of libzookeeper_mt and libzookeeper_st: the single export source.",
        pin,
        &[
            "kind: func or data. flavors: the libraries that export the name.",
            "feature: - (always), ssl (when TLS is built) or sasl (when SASL is built).",
            "rw: ro or rw for data, - for functions.",
            "size_lp64, size_ilp32: the data object size in bytes, - for functions.",
            "decl: the declaring installed header and line, or - when none declares it.",
            "",
            "Totals (mt/st): plain 201/178, ssl 202/179, sasl 210/187, full 211/188.",
        ],
    );
    let _ = writeln!(s, "{SYMBOLS_COLUMNS}");
    for r in rows {
        let rw = r.rw.map_or("-", |w| if w { "rw" } else { "ro" });
        let _ = writeln!(
            s,
            "{}\t{}\t{}\t{}\t{rw}\t{}\t{}\t{}\t{}",
            r.name,
            r.kind.as_str(),
            flavors_str(&r.flavors),
            r.feature,
            opt_str(r.size_lp64),
            opt_str(r.size_ilp32),
            r.decl,
            r.note
        );
    }
    s
}

fn render_static(rows: &[StaticRow], pin: &str) -> String {
    let mut s = header(
        "The archive-only globals of libzookeeper_mt.a and libzookeeper_st.a.",
        pin,
        &[
            "The static archives define these names in addition to the symbols.tsv rows.",
            "The shared libraries do not export them. All rows apply to both flavors",
            "and to every configuration: 154 serialize_/deserialize_ functions, six",
            "recordio archive functions, close_zsock, and 18 oa_/ia_ functions.",
        ],
    );
    let _ = writeln!(s, "{STATIC_COLUMNS}");
    for r in rows {
        let _ = writeln!(s, "{}\t{}\t{}\t{}", r.name, r.kind.as_str(), r.decl, r.note);
    }
    s
}

fn render_absent(rows: &[AbsentRow], pin: &str) -> String {
    let mut s = header(
        "Names that the shared libraries must not export.",
        pin,
        &[
            "flavors: the libraries that must not export the name.",
            "class: archive-only (in static-symbols.tsv), internal (an undeclared legacy",
            "global that DV-ABI-1 removes), undefined (no library defines it), or mt-only",
            "(a sync function that only mt defines).",
            "The rows are the reference archive globals minus the reference exports, the",
            "mt exports minus the st exports, and the names that nothing defines.",
        ],
    );
    let _ = writeln!(s, "{ABSENT_COLUMNS}");
    for r in rows {
        let _ = writeln!(
            s,
            "{}\t{}\t{}\t{}",
            r.name,
            flavors_str(&r.flavors),
            r.class,
            r.decl
        );
    }
    s
}

/// Resolves output directories for ABI golden specifications (`symbols.tsv`,
/// `static-symbols.tsv`, and `absent.tsv`).
///
/// By default, export baselines live in `abi/` and test fixtures live in `tests/abi/`. Passing
/// `--out-dir` writes all generated files into that single directory.
fn golden_dirs(out_dir: Option<PathBuf>) -> (PathBuf, PathBuf) {
    match out_dir {
        Some(d) => (d.clone(), d),
        None => (
            util::rust_c_dir().join("abi"),
            util::rust_c_dir().join("tests/abi"),
        ),
    }
}

/// Executes `cargo xtask abi golden` to regenerate ground-truth ABI tables from a legacy build.
fn golden(args: &[String]) -> Result<(), Error> {
    let mut ref_dir = PathBuf::from("/tmp/zk-legacy-ref");
    let mut c_dir = util::c_client_dir();
    let mut out_dir: Option<PathBuf> = None;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--ref" => ref_dir = PathBuf::from(util::value(&mut it, a)?),
            "--c-dir" => c_dir = PathBuf::from(util::value(&mut it, a)?),
            "--out-dir" => out_dir = Some(PathBuf::from(util::value(&mut it, a)?)),
            _ => return Err(Error::usage(format!("abi golden: unknown argument {a}"))),
        }
    }
    let (out_dir, absent_dir) = golden_dirs(out_dir);
    let decls = headers::declarations(&c_dir)?;
    let libs = load_ref(&ref_dir)?;
    let defs = headers::source_definitions(&c_dir)?;
    let rows = golden_symbols(&libs, &decls, &defs)?;
    let statics = golden_static(&libs, &decls)?;
    let absent = golden_absent(&libs, &decls, &statics);
    let mut msg = String::new();
    let mut bad = Vec::new();
    for (cfg, mt, st) in TOTALS {
        let f = Features::from_config(cfg)?;
        let (m, s) = (count(&rows, Flavor::Mt, f), count(&rows, Flavor::St, f));
        let data = rows
            .iter()
            .filter(|r| r.applies(Flavor::Mt, f) && r.kind == SymKind::Data)
            .count();
        let _ = writeln!(
            msg,
            "abi golden: {cfg}: mt {m} ({} func, {data} data), st {s}",
            m.saturating_sub(data)
        );
        if (m, s) != (mt, st) {
            bad.push(format!("{cfg}: mt {m} st {s}, expected mt {mt} st {st}"));
        }
    }
    let _ = writeln!(
        msg,
        "abi golden: {} archive-only rows, {} absent rows",
        statics.len(),
        absent.len()
    );
    if statics.len() != STATIC_TOTAL {
        bad.push(format!(
            "{} archive-only rows, expected {STATIC_TOTAL}",
            statics.len()
        ));
    }
    bad.extend(
        absent
            .iter()
            .filter(|a| {
                rows.iter()
                    .any(|r| r.name == a.name && r.flavors.iter().any(|f| a.flavors.contains(f)))
            })
            .map(|a| format!("{} is both exported and absent", a.name)),
    );
    util::out(&msg);
    if !bad.is_empty() {
        return Err(Error::msg(format!(
            "abi golden: the reference build does not match the specification: {}",
            bad.join("; ")
        )));
    }
    let pin = util::git_head(&c_dir);
    for (dir, file, text) in [
        (&out_dir, "symbols.tsv", render_symbols(&rows, &pin)),
        (
            &out_dir,
            "static-symbols.tsv",
            render_static(&statics, &pin),
        ),
        (&absent_dir, "absent.tsv", render_absent(&absent, &pin)),
    ] {
        util::write(&dir.join(file), &text)?;
    }
    util::out(&format!(
        "abi golden: wrote symbols.tsv and static-symbols.tsv to {}, absent.tsv to {}\n",
        util::rel(&out_dir),
        util::rel(&absent_dir)
    ));
    Ok(())
}

// ---------------------------------------------------------------------
// Checks
// ---------------------------------------------------------------------

/// Accumulates per-rule pass/fail/skip results for an ABI verification run.
#[derive(Default)]
struct Report {
    text: String,
    failed: usize,
    passed: usize,
    skipped: usize,
}

impl Report {
    fn pass(&mut self, id: &str, msg: &str) {
        self.passed = self.passed.saturating_add(1);
        let _ = writeln!(self.text, "PASS {id} {msg}");
    }

    fn fail(&mut self, id: &str, msg: &str) {
        self.failed = self.failed.saturating_add(1);
        let _ = writeln!(self.text, "FAIL {id} {msg}");
    }

    fn skip(&mut self, id: &str, msg: &str) {
        self.skipped = self.skipped.saturating_add(1);
        let _ = writeln!(self.text, "SKIP {id} {msg}");
    }

    fn info(&mut self, id: &str, msg: &str) {
        let _ = writeln!(self.text, "INFO {id} {msg}");
    }

    /// Records a `PASS` when `bad` is empty, or a `FAIL` listing all offending symbol names.
    fn names(&mut self, id: &str, ok: &str, what: &str, bad: &[String]) {
        if bad.is_empty() {
            self.pass(id, ok);
        } else {
            self.fail(id, &format!("{} {what}: {}", bad.len(), bad.join(" ")));
        }
    }

    fn finish(self, title: &str) -> Result<(), Error> {
        let mut t = self.text;
        let _ = writeln!(
            t,
            "{title}: {} passed, {} failed, {} skipped",
            self.passed, self.failed, self.skipped
        );
        util::out(&t);
        if self.failed == 0 {
            Ok(())
        } else {
            Err(Error::msg(format!(
                "{title}: {} checks failed",
                self.failed
            )))
        }
    }
}

/// Returns `true` if `name` carries an internal compiler, allocator, shim, or crypto provider
/// prefix forbidden in the exported symbol table.
fn leaks(name: &str) -> bool {
    const PREFIXES: &[&str] = &[
        "_ZN",
        "rust_",
        "__rust",
        "__rdl_",
        "__rg_",
        "zkrs_",
        "zks_",
        "ring_core_",
        "aws_lc_",
    ];
    PREFIXES.iter().any(|p| name.starts_with(p))
        || (name.starts_with("_R")
            && name
                .as_bytes()
                .get(2)
                .is_some_and(|c| c.is_ascii_uppercase() || c.is_ascii_digit()))
}

/// Returns `true` if `name` is an OpenSSL symbol forbidden as an undefined reference in the
/// Rust drop-in library.
fn openssl_ref(name: &str) -> bool {
    const PREFIXES: &[&str] = &[
        "SSL_", "ERR_", "EVP_", "X509_", "PEM_", "BIO_", "OPENSSL_", "CRYPTO_",
    ];
    PREFIXES.iter().any(|p| name.starts_with(p))
}

/// Command-line options for `abi check` and `abi static`.
struct CheckOpts {
    path: PathBuf,
    flavor: Flavor,
    features: Features,
    golden_dir: PathBuf,
    /// Path to `absent.tsv`, or `None` when running in a source tree without `tests/abi`.
    absent: Option<PathBuf>,
    reference: bool,
    verbose: bool,
    libdir: Option<String>,
    max_minos: Option<String>,
    arch: Option<String>,
}

fn check_opts(cmd: &str, args: &[String]) -> Result<CheckOpts, Error> {
    let mut path = None;
    let mut flavor = None;
    let mut features = None;
    let mut o = CheckOpts {
        path: PathBuf::new(),
        flavor: Flavor::Mt,
        features: Features {
            ssl: false,
            sasl: false,
        },
        golden_dir: util::rust_c_dir().join("abi"),
        absent: None,
        reference: false,
        verbose: false,
        libdir: None,
        max_minos: None,
        arch: None,
    };
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--flavor" => flavor = Some(Flavor::parse(util::value(&mut it, a)?)?),
            "--config" => features = Some(Features::from_config(util::value(&mut it, a)?)?),
            "--features" => features = Some(Features::from_list(util::value(&mut it, a)?)?),
            "--golden-dir" => o.golden_dir = PathBuf::from(util::value(&mut it, a)?),
            "--libdir" => o.libdir = Some(util::value(&mut it, a)?.to_owned()),
            "--max-minos" => o.max_minos = Some(util::value(&mut it, a)?.to_owned()),
            "--arch" => o.arch = Some(util::value(&mut it, a)?.to_owned()),
            "--reference" => o.reference = true,
            "--verbose" | "-v" => o.verbose = true,
            s if s.starts_with('-') => {
                return Err(Error::usage(format!("abi {cmd}: unknown option {s}")));
            }
            s if path.is_none() => path = Some(PathBuf::from(s)),
            s => return Err(Error::usage(format!("abi {cmd}: unexpected argument {s}"))),
        }
    }
    o.path = path.ok_or_else(|| Error::usage(format!("abi {cmd}: name the library to check")))?;
    o.flavor =
        flavor.ok_or_else(|| Error::usage(format!("abi {cmd}: --flavor mt|st is required")))?;
    o.features = features
        .ok_or_else(|| Error::usage(format!("abi {cmd}: --config or --features is required")))?;
    o.absent = absent_golden(&o.golden_dir);
    Ok(o)
}

/// Parses a dotted `MAJOR.MINOR.PATCH` version string into a 3-tuple for numeric comparison.
fn version_key(v: &str) -> [u64; 3] {
    let mut k = [0_u64; 3];
    for (slot, p) in k.iter_mut().zip(v.split('.')) {
        *slot = p.parse().unwrap_or(0);
    }
    k
}

/// Executes `cargo xtask abi check` to validate a shared library against ABI rules E1..E16.
fn check(args: &[String]) -> Result<(), Error> {
    let o = check_opts("check", args)?;
    let g = load_goldens(&o.golden_dir, o.absent.as_deref())?;
    let bin = parse_lib(&o.path, o.arch.as_deref())?;
    if !bin.shared {
        return Err(Error::msg(format!(
            "{} is not a shared library; use `abi static` for archives",
            util::rel(&o.path)
        )));
    }
    let mut r = Report::default();
    let want: Want<'_> = g
        .symbols
        .iter()
        .filter(|x| x.applies(o.flavor, o.features))
        .map(|x| (x.name.as_str(), x))
        .collect();
    r.info(
        "--",
        &format!(
            "{}: flavor {}, config {}, {} golden rows, {} exports",
            util::rel(&o.path),
            o.flavor.as_str(),
            o.features.name(),
            want.len(),
            bin.symbols.len()
        ),
    );

    check_names(&mut r, &o, &g, &want, &bin);
    check_data(&mut r, &want, &bin);
    match bin.format {
        Format::Elf => check_elf_identity(&mut r, &o, &bin),
        Format::MachO => check_macho_identity(&mut r, &o, &want, &bin),
    }
    check_refs(&mut r, &o, &bin);
    r.finish(&format!("abi check {}", util::rel(&o.path)))
}

/// Mapping from symbol name to its expected golden specification row.
type Want<'a> = BTreeMap<&'a str, &'a Row>;

/// Validates against `absent.tsv`, ensuring no forbidden symbol is exported.
fn check_absent(r: &mut Report, o: &CheckOpts, g: &Goldens, have: &BTreeSet<&str>) {
    let Some(rows) = &g.absent else {
        r.skip(
            "E1",
            &format!("no absent.tsv at {}", util::rel(&absent_path())),
        );
        return;
    };
    let absent: Vec<String> = rows
        .iter()
        .filter(|a| a.flavors.contains(&o.flavor) && have.contains(a.name.as_str()))
        .map(|a| a.name.clone())
        .collect();
    r.names(
        "E1",
        "no absent.tsv name is exported",
        "absent.tsv names are exported",
        &absent,
    );
}

/// Checks exported symbols against rules E1 (exact set), E2 (symbol kind), E3 (binding and
/// visibility), and E12 (internal prefix leakage).
fn check_names(r: &mut Report, o: &CheckOpts, g: &Goldens, want: &Want<'_>, bin: &Binary) {
    let have: BTreeSet<&str> = bin.symbols.keys().map(String::as_str).collect();
    // Rule E1: Exported symbol names must match the golden set for this flavor and config.
    let missing: Vec<String> = want
        .keys()
        .filter(|n| !have.contains(*n))
        .map(|n| (*n).to_owned())
        .collect();
    let extra: Vec<String> = have
        .iter()
        .filter(|n| !want.contains_key(*n))
        .map(|n| (*n).to_owned())
        .collect();
    r.names(
        "E1",
        &format!("the {} exports equal the golden set", have.len()),
        "golden names are not exported",
        &missing,
    );
    r.names(
        "E1",
        "no export is outside the golden set",
        "exports are not in the golden set",
        &extra,
    );
    check_absent(r, o, g, &have);

    // Rule E2: Every exported symbol must match its expected function or data classification.
    let kinds: Vec<String> = want
        .iter()
        .filter_map(|(n, row)| {
            bin.symbols
                .get(*n)
                .filter(|s| s.kind != row.kind)
                .map(|s| format!("{n}({}!={})", s.kind.as_str(), row.kind.as_str()))
        })
        .collect();
    r.names(
        "E2",
        "every export has the golden kind",
        "exports have another kind",
        &kinds,
    );

    // Rule E3: Exported symbols must have non-weak global binding and default visibility.
    let e3: Vec<String> = bin
        .symbols
        .values()
        .filter(|s| s.weak || s.visibility == 3)
        .map(|s| s.name.clone())
        .collect();
    r.names(
        "E3",
        "no export is weak or protected",
        "exports are weak or protected",
        &e3,
    );

    // Rule E12: No exported symbol may carry a Rust mangled, shim, or crypto provider prefix.
    let leaked: Vec<String> = have
        .iter()
        .filter(|n| leaks(n))
        .map(|n| (*n).to_owned())
        .collect();
    r.names(
        "E12",
        "no export has a Rust, shim or crypto provider prefix",
        "exports leak",
        &leaked,
    );
}

/// Checks exported data objects against rules E4 (byte size), E5 (read-only vs. writable
/// section placement), and E6 (natural alignment).
fn check_data(r: &mut Report, want: &Want<'_>, bin: &Binary) {
    // Rules E4, E5, and E6 validate data symbol sizes, section mutability, and alignment.
    let data: Vec<(&Row, &Symbol)> = want
        .iter()
        .filter(|(_, row)| row.kind == SymKind::Data)
        .filter_map(|(n, row)| bin.symbols.get(*n).map(|s| (*row, s)))
        .collect();
    let mut e4 = Vec::new();
    let mut e5 = Vec::new();
    let mut e6 = Vec::new();
    let ptr: u64 = if bin.is64 { 8 } else { 4 };
    for (row, s) in &data {
        let want_size = row.size(bin.is64).unwrap_or(0);
        match (bin.format, s.size, s.span) {
            (Format::Elf, Some(sz), _) if sz != want_size => {
                e4.push(format!("{}(size {sz}!={want_size})", row.name))
            }
            (Format::MachO, _, Some(sp)) if sp < want_size => {
                e4.push(format!("{}(span {sp}<{want_size})", row.name))
            }
            (Format::MachO, _, None) => e4.push(format!("{}(no section)", row.name)),
            _ => {}
        }
        if row.rw.is_some_and(|w| w != s.writable) {
            e5.push(format!(
                "{}({})",
                row.name,
                if s.writable { "writable" } else { "read-only" }
            ));
        }
        let align = want_size.min(ptr).max(1);
        if s.addr.checked_rem(align).is_some_and(|m| m != 0) || s.section_align < align {
            e6.push(format!(
                "{}(addr {:#x}, section align {})",
                row.name, s.addr, s.section_align
            ));
        }
    }
    let how = if bin.format == Format::MachO {
        "Mach-O records no size; the span to the next symbol covers it"
    } else {
        "st_size equals the golden size"
    };
    r.names(
        "E4",
        &format!("{} data objects have the golden size ({how})", data.len()),
        "data objects have another size",
        &e4,
    );
    let ro = data.iter().filter(|(row, _)| row.rw == Some(false)).count();
    r.names(
        "E5",
        &format!(
            "{ro} read-only and {} writable data objects",
            data.len().saturating_sub(ro)
        ),
        "data objects have the wrong writability",
        &e5,
    );
    r.names(
        "E6",
        "every data object has at least its natural alignment",
        "data objects are misaligned",
        &e6,
    );
}

/// Checks ELF shared object identity metadata, including `DT_SONAME`.
fn check_elf_identity(r: &mut Report, o: &CheckOpts, bin: &Binary) {
    r.skip("E7", "the parser does not read .gnu.version_d");
    let want_so = format!("libzookeeper_{}.so.2", o.flavor.as_str());
    match &bin.soname {
        Some(s) if *s == want_so => r.pass("E8", &format!("SONAME {s}")),
        other => r.fail("E8", &format!("SONAME {other:?}, expected {want_so}")),
    }
    r.skip("E14", "not applicable to ELF");
}

/// Checks Mach-O dylib identity metadata: `LC_ID_DYLIB`, compatibility and current versions,
/// two-level namespace flag, `minos` deployment floor, and export trie parity.
fn check_macho_identity(r: &mut Report, o: &CheckOpts, want: &Want<'_>, bin: &Binary) {
    let f = o.flavor.as_str();
    let have: BTreeSet<&str> = bin.symbols.keys().map(String::as_str).collect();
    r.skip("E7", "not applicable to Mach-O");
    r.skip("E8", "not applicable to Mach-O; see E14");
    let base = format!("libzookeeper_{f}.2.dylib");
    let want_id = o
        .libdir
        .as_ref()
        .map(|d| format!("{}/{base}", d.trim_end_matches('/')));
    match (&bin.soname, &want_id) {
        (Some(s), Some(w)) if s == w => r.pass("E14", &format!("LC_ID_DYLIB {s}")),
        (Some(s), None)
            if Path::new(s)
                .file_name()
                .is_some_and(|n| n.to_string_lossy() == base) =>
        {
            r.pass(
                "E14",
                &format!("LC_ID_DYLIB {s} (give --libdir to check the directory)"),
            );
        }
        (s, _) => r.fail(
            "E14",
            &format!(
                "LC_ID_DYLIB {s:?}, expected {}",
                want_id.unwrap_or(format!("<libdir>/{base}"))
            ),
        ),
    }
    match &bin.dylib_versions {
        Some((cur, compat)) if cur == "3.0.0" && compat == "3.0.0" => {
            r.pass("E14", "current and compatibility versions 3.0.0")
        }
        v => r.fail(
            "E14",
            &format!("current and compatibility versions {v:?}, expected 3.0.0"),
        ),
    }
    if bin.twolevel == Some(true) {
        r.pass("E14", "two-level namespace");
    } else {
        r.fail("E14", "the library has no two-level namespace flag");
    }
    match (&bin.minos, &o.max_minos) {
        (Some(m), Some(max)) if version_key(m) <= version_key(max) => {
            r.pass("E14", &format!("minos {m} <= {max}"))
        }
        (m, Some(max)) => r.fail("E14", &format!("minos {m:?} is higher than {max}")),
        (m, None) => r.info(
            "E14",
            &format!(
                "minos {} (give --max-minos to check it)",
                m.as_deref().unwrap_or("unknown")
            ),
        ),
    }
    match &bin.trie {
        Some(t) => {
            let tw: BTreeSet<&str> = t.iter().map(String::as_str).collect();
            let wk: BTreeSet<&str> = want.keys().copied().collect();
            let diff: Vec<String> = tw
                .symmetric_difference(&wk)
                .map(|n| (*n).to_owned())
                .collect();
            r.names(
                "E14",
                &format!("the export trie has the {} golden names", tw.len()),
                "names differ between the export trie and the golden",
                &diff,
            );
            let sym_diff: Vec<String> = tw
                .symmetric_difference(&have)
                .map(|n| (*n).to_owned())
                .collect();
            r.names(
                "E14",
                "the export trie equals the symbol table exports",
                "names differ between the export trie and the symbol table",
                &sym_diff,
            );
        }
        None => r.fail("E14", "the library has no export trie"),
    }
}

/// Checks external library dependencies and undefined symbol references, enforcing the prohibition
/// on undefined OpenSSL symbols in drop-in binaries.
fn check_refs(r: &mut Report, o: &CheckOpts, bin: &Binary) {
    // Dynamic library dependencies recorded in DT_NEEDED / LC_LOAD_DYLIB.
    r.info("E9", &format!("needed: {}", bin.needed.join(" ")));
    r.skip("E9", "abi/needed/<os>.txt is not part of this baseline");
    r.skip(
        "E10",
        "the glibc floor applies to ELF builds in the floor image",
    );
    r.skip("E11", "hardening parity needs program headers");

    // Verify absence of undefined OpenSSL symbol references in drop-in builds.
    let ssl: Vec<String> = bin
        .undefined
        .iter()
        .filter(|n| openssl_ref(n))
        .cloned()
        .collect();
    if o.reference && !ssl.is_empty() {
        r.info(
            "E13",
            &format!(
                "DV-TLS-4: the reference build has {} OpenSSL references; the drop-in has none",
                ssl.len()
            ),
        );
    } else {
        r.names(
            "E13",
            "no undefined OpenSSL reference",
            "undefined OpenSSL references",
            &ssl,
        );
    }
    r.skip(
        "E13",
        "the libc/libsasl2 allowlist needs the per-OS goldens",
    );
    if o.verbose {
        r.info(
            "E13",
            &format!(
                "undefined: {}",
                bin.undefined.iter().cloned().collect::<Vec<_>>().join(" ")
            ),
        );
    }
    r.skip("E15", "COFF is not parsed");
    r.skip("E16", "the property note applies to ELF only");
}

/// Executes `cargo xtask abi static` to validate a static archive against rules S1..S3 and E12.
fn check_static(args: &[String]) -> Result<(), Error> {
    let o = check_opts("static", args)?;
    let g = load_goldens(&o.golden_dir, o.absent.as_deref())?;
    let (members, globals) = archive_globals(&o.path, o.arch.as_deref())?;
    let mut r = Report::default();
    r.info(
        "--",
        &format!(
            "{}: flavor {}, config {}, {members} members, {} globals",
            util::rel(&o.path),
            o.flavor.as_str(),
            o.features.name(),
            globals.len()
        ),
    );
    let exports: Vec<&Row> = g
        .symbols
        .iter()
        .filter(|x| x.applies(o.flavor, o.features))
        .collect();
    let s1: Vec<String> = exports
        .iter()
        .filter(|x| !globals.contains_key(&x.name))
        .map(|x| x.name.clone())
        .collect();
    r.names(
        "S1",
        &format!(
            "the {} golden exports are global definitions",
            exports.len()
        ),
        "golden exports are not defined",
        &s1,
    );
    let s2: Vec<String> = g
        .statics
        .iter()
        .filter(|x| !globals.contains_key(&x.name))
        .map(|x| x.name.clone())
        .collect();
    r.names(
        "S2",
        &format!(
            "the {} static-symbols.tsv names are defined",
            g.statics.len()
        ),
        "static-symbols.tsv names are not defined",
        &s2,
    );
    let known: BTreeSet<&str> = exports
        .iter()
        .map(|x| x.name.as_str())
        .chain(g.statics.iter().map(|x| x.name.as_str()))
        .collect();
    let other: Vec<String> = globals
        .keys()
        .filter(|n| !known.contains(n.as_str()))
        .cloned()
        .collect();
    if o.reference {
        r.info("S3", &format!("DV-ABI-1: the reference archive has {} globals in neither golden; the drop-in removes them", other.len()));
        if o.verbose {
            for n in &other {
                r.info("S3", &format!("removed {n}"));
            }
        }
    } else {
        r.names(
            "S3",
            "no other global is defined",
            "other globals are defined",
            &other,
        );
        if members == 1 {
            r.pass("S3", "the archive has one member");
        } else {
            r.fail(
                "S3",
                &format!("the archive has {members} members, expected one prelinked member"),
            );
        }
    }
    let leaked: Vec<String> = globals.keys().filter(|n| leaks(n)).cloned().collect();
    r.names(
        "E12",
        "no global has a Rust, shim or crypto provider prefix",
        "globals leak",
        &leaked,
    );
    r.finish(&format!("abi static {}", util::rel(&o.path)))
}

/// CLI entry point for `cargo xtask abi`, dispatching to `golden`, `check`, or `static`.
pub(crate) fn run(args: &[String]) -> Result<(), Error> {
    let (cmd, rest) = args
        .split_first()
        .ok_or_else(|| Error::usage("abi: give golden, check or static".to_owned()))?;
    match cmd.as_str() {
        "golden" => golden(rest),
        "check" => check(rest),
        "static" => check_static(rest),
        other => Err(Error::usage(format!(
            "abi: unknown command {other}: use golden, check or static"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn symbols_round_trip() {
        let rows = vec![
            Row {
                name: "logLevel".to_owned(),
                kind: SymKind::Data,
                flavors: Flavor::ALL.into_iter().collect(),
                feature: "-".to_owned(),
                rw: Some(true),
                size_lp64: Some(4),
                size_ilp32: Some(4),
                decl: "zookeeper_log.h:30".to_owned(),
                note: "-".to_owned(),
            },
            Row {
                name: "zoo_get".to_owned(),
                kind: SymKind::Func,
                flavors: [Flavor::Mt].into_iter().collect(),
                feature: "sasl".to_owned(),
                rw: None,
                size_lp64: None,
                size_ilp32: None,
                decl: "-".to_owned(),
                note: "a note".to_owned(),
            },
        ];
        let parsed = parse_symbols(&render_symbols(&rows, "abc"));
        assert_eq!(parsed.ok(), Some(rows.clone()));
        let full = Features {
            ssl: true,
            sasl: true,
        };
        assert_eq!(count(&rows, Flavor::Mt, full), 2);
        assert_eq!(count(&rows, Flavor::St, full), 1);
        assert_eq!(
            count(
                &rows,
                Flavor::Mt,
                Features {
                    ssl: true,
                    sasl: false
                }
            ),
            1
        );
    }

    #[test]
    fn options_and_prefixes() {
        assert_eq!(
            Features::from_list("ssl,sasl").ok(),
            Features::from_config("full").ok()
        );
        assert_eq!(
            Features::from_list("").ok().map(Features::name),
            Some("plain")
        );
        assert!(Features::from_list("tls13").is_err());
        assert!(leaks("_ZN4core3fmt") && leaks("_RNvCs1") && leaks("zkrs_handle_new"));
        assert!(!leaks("_Rx") && !leaks("zoo_get") && !leaks("ZOO_OPEN_ACL_UNSAFE"));
        assert!(openssl_ref("SSL_CTX_new") && !openssl_ref("sasl_client_new"));
        assert!(version_key("11.0.0") < version_key("14.2"));
        assert_eq!(version_key("26.5"), version_key("26.5.0"));
    }
}
