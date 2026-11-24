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

//! Static extraction of API declarations and public type definitions from installed C headers.
//!
//! Provides lexical analysis and AST-free pattern matching to extract function signatures,
//! exported object declarations, public aggregate definitions (structs, unions, enums), and
//! struct field layout designators directly from header sources without invoking a C
//! preprocessor.

use std::collections::BTreeMap;
use std::path::Path;

use crate::clex::{self, Kind, Lexed, Token};
use crate::util::{Error, read};

/// Public headers installed by the legacy C client (`pkginclude_HEADERS` in `Makefile.am`).
pub(crate) const INSTALLED: &[&str] = &[
    "include/zookeeper.h",
    "include/zookeeper_version.h",
    "include/zookeeper_log.h",
    "include/proto.h",
    "include/recordio.h",
    "generated/zookeeper.jute.h",
];

/// Function prototype or `extern` object declaration extracted from a C header.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Decl {
    /// Base filename of the declaring header.
    pub(crate) file: String,
    /// 1-based source line number where the identifier is declared.
    pub(crate) line: u32,
    /// `true` for a function declaration; `false` for a data object.
    pub(crate) func: bool,
    /// `true` if annotated with the `ZOOAPI` visibility macro.
    pub(crate) zooapi: bool,
    /// `true` if qualified with top-level `const`.
    pub(crate) is_const: bool,
    /// Normalized type tokens preceding the identifier, stripped of storage classes.
    pub(crate) ty: String,
    /// Preprocessor conditional expression guarding this declaration.
    pub(crate) cond: String,
}

/// Scans the installed C headers and records the first declaration of each visible symbol.
pub(crate) fn declarations(c_dir: &Path) -> Result<BTreeMap<String, Decl>, Error> {
    let mut out = BTreeMap::new();
    for h in INSTALLED {
        let src = read(&c_dir.join(h))?;
        let file = Path::new(h)
            .file_name()
            .map_or_else(String::new, |s| s.to_string_lossy().into_owned());
        scan_decls(&clex::lex(&src), &file, &mut out);
    }
    Ok(out)
}

/// Scans file-scope global object definitions across `src/*.c` to determine concrete types for
/// exported symbols that are not declared in installed headers.
pub(crate) fn source_definitions(c_dir: &Path) -> Result<BTreeMap<String, Decl>, Error> {
    let dir = c_dir.join("src");
    let rd = std::fs::read_dir(&dir).map_err(|e| Error::io(&dir, &e))?;
    let mut files: Vec<std::path::PathBuf> = rd
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "c"))
        .collect();
    files.sort();
    let mut out = BTreeMap::new();
    for f in files {
        let name = f
            .file_name()
            .map_or_else(String::new, |s| s.to_string_lossy().into_owned());
        for (k, v) in definitions(&read(&f)?, &name) {
            out.entry(k).or_insert(v);
        }
    }
    Ok(out)
}

/// Extracts file-scope non-static data definitions from a C translation unit.
fn definitions(src: &str, file: &str) -> BTreeMap<String, Decl> {
    let mut out = BTreeMap::new();
    let lx = clex::lex(src);
    let mut stmt: Vec<&Token> = Vec::new();
    let mut in_init = false;
    for t in &lx.tokens {
        if t.depth != 0 {
            continue;
        }
        if t.is_punct('{') || t.is_punct('}') {
            if !in_init {
                stmt.clear();
            }
            continue;
        }
        if t.is_punct(';') || t.is_punct('=') {
            if !in_init
                && !stmt
                    .iter()
                    .any(|s| s.is_ident("static") || s.is_ident("extern") || s.is_ident("typedef"))
            {
                if let Some((n, d)) = object_def(&lx, &stmt, file) {
                    out.entry(n).or_insert(d);
                }
            }
            stmt.clear();
            in_init = t.is_punct('=');
            continue;
        }
        if !in_init {
            stmt.push(t);
        }
    }
    out
}

/// Extracts a global variable definition from a leading declaration token sequence.
fn object_def(lx: &Lexed, stmt: &[&Token], file: &str) -> Option<(String, Decl)> {
    if stmt.iter().any(|s| s.is_punct('(')) {
        return None;
    }
    let end = stmt
        .iter()
        .position(|s| s.is_punct('['))
        .unwrap_or(stmt.len());
    let (name, head) = stmt.get(..end)?.split_last()?;
    if name.kind != Kind::Ident || head.is_empty() {
        return None;
    }
    Some((
        name.text.clone(),
        Decl {
            file: file.to_owned(),
            line: name.line,
            func: false,
            zooapi: head.iter().any(|t| t.is_ident("ZOOAPI")),
            is_const: head.iter().any(|t| t.is_ident("const")),
            ty: type_words(head),
            cond: lx.cond_of(name).to_owned(),
        },
    ))
}

/// Sequentially parses top-level declarations within a tokenized C header unit.
fn scan_decls(lx: &Lexed, file: &str, out: &mut BTreeMap<String, Decl>) {
    let mut stmt: Vec<&Token> = Vec::new();
    for t in &lx.tokens {
        if t.is_punct('{') || t.is_punct('}') {
            stmt.clear();
            continue;
        }
        if !t.is_punct(';') {
            stmt.push(t);
            continue;
        }
        if stmt.first().is_some_and(|s| s.is_ident("typedef")) {
            stmt.clear();
            continue;
        }
        let paren = stmt.iter().position(|s| s.is_punct('('));
        let decl = match paren {
            Some(p) if p > 0 => function_decl(lx, &stmt, p, file),
            _ if stmt.iter().any(|s| s.is_ident("extern")) => object_decl(lx, &stmt, file),
            _ => None,
        };
        if let Some((name, d)) = decl {
            out.entry(name).or_insert(d);
        }
        stmt.clear();
    }
}

/// Storage-class specifiers and visibility qualifiers excluded when normalizing type strings.
const STORAGE: &[&str] = &["extern", "ZOOAPI", "static", "inline", "const", "volatile"];

/// Formats and normalizes type tokens preceding a declared identifier into a clean C type string.
fn type_words(head: &[&Token]) -> String {
    head.iter()
        .filter(|t| {
            !(t.kind == Kind::Ident && STORAGE.contains(&t.text.as_str())) && t.kind != Kind::Str
        })
        .map(|t| t.text.as_str())
        .collect::<Vec<_>>()
        .join(" ")
        .replace(" *", "*")
}

fn function_decl(lx: &Lexed, stmt: &[&Token], paren: usize, file: &str) -> Option<(String, Decl)> {
    let name_at = paren.checked_sub(1)?;
    let name = stmt.get(name_at)?;
    if name.kind != Kind::Ident {
        return None;
    }
    let head = stmt.get(..name_at)?;
    if head.is_empty() {
        return None;
    }
    Some((
        name.text.clone(),
        Decl {
            file: file.to_owned(),
            line: name.line,
            func: true,
            zooapi: head.iter().any(|t| t.is_ident("ZOOAPI")),
            is_const: false,
            ty: type_words(head),
            cond: lx.cond_of(name).to_owned(),
        },
    ))
}

fn object_decl(lx: &Lexed, stmt: &[&Token], file: &str) -> Option<(String, Decl)> {
    if stmt.iter().any(|s| s.is_punct('(')) || !stmt.iter().any(|s| s.is_ident("extern")) {
        return None;
    }
    let end = stmt
        .iter()
        .position(|s| s.is_punct('['))
        .unwrap_or(stmt.len());
    let body = stmt.get(..end)?;
    let (name, head) = body.split_last()?;
    if name.kind != Kind::Ident || head.is_empty() || head.iter().any(|t| t.is_ident("typedef")) {
        return None;
    }
    Some((
        name.text.clone(),
        Decl {
            file: file.to_owned(),
            line: name.line,
            func: false,
            zooapi: head.iter().any(|t| t.is_ident("ZOOAPI")),
            is_const: head.iter().any(|t| t.is_ident("const")),
            ty: type_words(head),
            cond: lx.cond_of(name).to_owned(),
        },
    ))
}

/// Public composite type (struct, union) or enum definition exposed across the C ABI.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PubType {
    /// Canonical C type name (e.g. `struct Stat` or typedef alias `zoo_op_t`).
    pub(crate) name: String,
    /// `true` if the type is an `enum`.
    pub(crate) is_enum: bool,
    /// Preprocessor conditional expression gating this type definition.
    pub(crate) cond: String,
    /// `(member_path, cond)` pairs suitable for `offsetof` layout probes.
    pub(crate) members: Vec<(String, String)>,
}

/// Extracts all public struct, union, and enum definitions from the installed C headers.
pub(crate) fn public_types(c_dir: &Path) -> Result<Vec<PubType>, Error> {
    let mut out = Vec::new();
    for h in INSTALLED {
        let lx = clex::lex(&read(&c_dir.join(h))?);
        types_of(&lx, &mut out);
    }
    Ok(out)
}

/// Finds the closing `}` matching the opening `{` at `open`.
fn matching(tokens: &[Token], open: usize) -> Option<usize> {
    let depth = tokens.get(open)?.depth;
    let mut i = open.checked_add(1)?;
    while let Some(t) = tokens.get(i) {
        if t.is_punct('}') && t.depth == depth {
            return Some(i);
        }
        i = i.checked_add(1)?;
    }
    None
}

fn types_of(lx: &Lexed, out: &mut Vec<PubType>) {
    let toks = &lx.tokens;
    let mut i = 0_usize;
    while let Some(t) = toks.get(i) {
        let next = i.saturating_add(1);
        let is_typedef = t.is_ident("typedef");
        let kw_at = if is_typedef { next } else { i };
        let Some(kw) = toks.get(kw_at) else { break };
        let is_enum = kw.is_ident("enum");
        if !(kw.is_ident("struct") || kw.is_ident("union") || is_enum) {
            i = next;
            continue;
        }
        // Skip inline struct/union member declarations nested inside an enclosing type.
        if !is_typedef
            && i > 0
            && toks
                .get(i.saturating_sub(1))
                .is_some_and(|p| !(p.is_punct(';') || p.is_punct('}') || p.is_punct('{')))
        {
            i = next;
            continue;
        }
        let mut j = kw_at.saturating_add(1);
        let tag = toks
            .get(j)
            .filter(|t| t.kind == Kind::Ident)
            .map(|t| t.text.clone());
        if tag.is_some() {
            j = j.saturating_add(1);
        }
        if !toks.get(j).is_some_and(|t| t.is_punct('{')) {
            i = next;
            continue;
        }
        let Some(close) = matching(toks, j) else {
            break;
        };
        let alias = if is_typedef {
            toks.get(close.saturating_add(1))
                .filter(|t| t.kind == Kind::Ident)
                .map(|t| t.text.clone())
        } else {
            None
        };
        let name = alias.or_else(|| tag.map(|g| format!("{} {g}", kw.text)));
        if let Some(name) = name {
            let members = if is_enum {
                Vec::new()
            } else {
                members(lx, j.saturating_add(1), close)
            };
            out.push(PubType {
                name,
                is_enum,
                cond: lx.cond_of(kw).to_owned(),
                members,
            });
        }
        i = close.saturating_add(1);
    }
}

/// Recursively extracts member field designators and nested aggregates within `start..end`.
fn members(lx: &Lexed, start: usize, end: usize) -> Vec<(String, String)> {
    let toks = &lx.tokens;
    let mut out = Vec::new();
    let mut i = start;
    let mut stmt_start = start;
    while i < end {
        let Some(t) = toks.get(i) else { break };
        if t.is_punct('{') {
            let Some(close) = matching(toks, i) else {
                break;
            };
            let inner = members(lx, i.saturating_add(1), close);
            let name = toks
                .get(close.saturating_add(1))
                .filter(|n| n.kind == Kind::Ident);
            match name {
                Some(n) => {
                    out.push((n.text.clone(), lx.cond_of(n).to_owned()));
                    for (m, c) in inner {
                        out.push((format!("{}.{m}", n.text), c));
                    }
                }
                None => out.extend(inner),
            }
            // Advance past the semicolon terminating the nested aggregate declaration.
            i = close.saturating_add(1);
            while toks.get(i).is_some_and(|t| !t.is_punct(';')) && i < end {
                i = i.saturating_add(1);
            }
            i = i.saturating_add(1);
            stmt_start = i;
            continue;
        }
        if t.is_punct(';') {
            if let Some(stmt) = toks.get(stmt_start..i) {
                for d in split_declarators(stmt) {
                    if let Some(n) = declarator_name(d) {
                        out.push((n.text.clone(), lx.cond_of(n).to_owned()));
                    }
                }
            }
            stmt_start = i.saturating_add(1);
        }
        i = i.saturating_add(1);
    }
    out
}

/// Splits a member declaration into individual declarators at top-level commas, ignoring
/// commas inside function-pointer parameter lists.
fn split_declarators(stmt: &[Token]) -> Vec<&[Token]> {
    let mut out = Vec::new();
    let mut depth = 0_usize;
    let mut from = 0_usize;
    for (i, t) in stmt.iter().enumerate() {
        if t.is_punct('(') {
            depth = depth.saturating_add(1);
        } else if t.is_punct(')') {
            depth = depth.saturating_sub(1);
        } else if t.is_punct(',') && depth == 0 {
            out.extend(stmt.get(from..i));
            from = i.saturating_add(1);
        }
    }
    out.extend(stmt.get(from..));
    out
}

/// Extracts the field identifier token from a member declarator, or `None` for bit-fields.
fn declarator_name(d: &[Token]) -> Option<&Token> {
    if d.iter().any(|t| t.is_punct(':')) {
        return None;
    }
    if let Some(p) = d.windows(2).position(|w| {
        w.first().is_some_and(|a| a.is_punct('(')) && w.get(1).is_some_and(|b| b.is_punct('*'))
    }) {
        return d.get(p.saturating_add(2)).filter(|t| t.kind == Kind::Ident);
    }
    let end = d.iter().position(|t| t.is_punct('[')).unwrap_or(d.len());
    d.get(..end)?.iter().rev().find(|t| t.kind == Kind::Ident)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decls_and_types() {
        let src = "#ifdef __cplusplus\nextern \"C\" {\n#endif\nextern ZOOAPI const int ZOO_X;\nZOOAPI int zoo_f(int a,\n  char *b);\nint64_t zoo_htonll(int64_t v);\ntypedef void (*cb_t)(int);\ntypedef struct zoo_op {\n  int type;\n  union {\n    struct { const char *path; int (*fp)(void); } create_op;\n    struct { int version; } delete_op;\n  };\n} zoo_op_t;\nstruct buffer { int32_t len; char *buff; };\nstruct ia { int (*f)(struct ia *a, const char *t); int a, b; void *priv; };\ntypedef enum { A = 1 } E;\n#ifdef __cplusplus\n}\n#endif\n";
        let lx = clex::lex(src);
        let mut d = BTreeMap::new();
        scan_decls(&lx, "z.h", &mut d);
        let x = d.get("ZOO_X");
        assert_eq!(
            x.map(|x| (x.func, x.zooapi, x.is_const, x.ty.as_str(), x.line)),
            Some((false, true, true, "int", 4))
        );
        assert_eq!(d.get("zoo_f").map(|x| (x.func, x.line)), Some((true, 5)));
        assert_eq!(d.get("zoo_htonll").map(|x| x.zooapi), Some(false));
        assert!(!d.contains_key("cb_t"));
        let mut t = Vec::new();
        types_of(&lx, &mut t);
        let names: Vec<&str> = t.iter().map(|t| t.name.as_str()).collect();
        assert_eq!(names, vec!["zoo_op_t", "struct buffer", "struct ia", "E"]);
        let ia: Vec<&str> = t
            .get(2)
            .map(|t| t.members.iter().map(|m| m.0.as_str()).collect())
            .unwrap_or_default();
        assert_eq!(ia, vec!["f", "a", "b", "priv"]);
        let m: Vec<&str> = t
            .first()
            .map(|t| t.members.iter().map(|m| m.0.as_str()).collect())
            .unwrap_or_default();
        assert_eq!(
            m,
            vec![
                "type",
                "create_op",
                "create_op.path",
                "create_op.fp",
                "delete_op",
                "delete_op.version"
            ]
        );
    }

    #[test]
    fn source_definitions() {
        let d = definitions(
            "const int ZOO_A = 1;\nstatic int x;\nstruct Id ZOO_B = { \"a\", \"b\" };\nint f(void) { int y = 2; return y; }\nint g;\n",
            "z.c",
        );
        assert_eq!(
            d.get("ZOO_A").map(|d| (d.is_const, d.ty.as_str())),
            Some((true, "int"))
        );
        assert_eq!(d.get("ZOO_B").map(|d| d.ty.as_str()), Some("struct Id"));
        assert!(d.contains_key("g"));
        assert!(!d.contains_key("y") && !d.contains_key("x"));
    }
}
