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

//! Architectural boundary test confining `unsafe` code in `zk-ffi` to audited modules.
//!
//! At the crate root, `zk-ffi` enforces `#![deny(unsafe_code)]`, granting module wide
//! exemptions solely to `abi::raw` and the `abi::standin` test harness. Individual C ABI
//! exports carry the item-level `#[allow(unsafe_code)]` attribute strictly to accommodate
//! `#[unsafe(no_mangle)]`. Because `rustc`'s lint pass cannot distinguish between an
//! attribute use and an interior `unsafe` block under the same item level exemption, this
//! test scans the source tree directly and rejects any `unsafe` block, function, trait,
//! implementation, or extern block outside the audited boundary.
//!
//! Before scanning for `unsafe` tokens, the lexical stripper removes comments and string or
//! character literal payloads across the entire file. The scanner fails closed: any unclosed
//! comment or literal is reported as a violation at its opening line.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

use std::fs;
use std::path::{Path, PathBuf};

/// Returns true if `rel` (relative to `src/`) resides within the audited unsafe boundary.
fn is_audited(rel: &Path) -> bool {
    rel == Path::new("abi/raw.rs") || rel.starts_with("abi/raw") || rel.starts_with("abi/standin")
}

fn rust_files(dir: &Path, out: &mut Vec<PathBuf>) {
    for e in fs::read_dir(dir).unwrap() {
        let p = e.unwrap().path();
        if p.is_dir() {
            rust_files(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

/// Returns true if `c` is a valid Rust identifier continuation character.
fn ident(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// Appends the newline characters present in `skipped` to `out` and advances `line`.
fn keep_newlines(skipped: &str, out: &mut String, line: &mut usize) {
    for _ in skipped.matches('\n') {
        out.push('\n');
        *line = line.saturating_add(1);
    }
}

/// Recognizes the opening prefix of a raw string literal (`r"`, `r#"`, `br"`, or `cr"`).
///
/// Returns the delimiter hash count and the slice following the opening quote, distinguishing
/// raw string literals from raw identifiers such as `r#name`.
fn raw_open(rest: &str) -> Option<(usize, &str)> {
    let s = rest
        .strip_prefix(['b', 'c'])
        .filter(|s| s.starts_with('r'))
        .unwrap_or(rest);
    let s = s.strip_prefix('r')?;
    let hashes = s.chars().take_while(|&c| c == '#').count();
    let s = s.get(hashes..)?.strip_prefix('"')?;
    Some((hashes, s))
}

/// Advances past the body of a standard string or character literal up to `close`, honoring
/// backslash escape sequences.
///
/// Returns the literal payload and the remaining suffix following the closing delimiter, or `None`
/// if the literal is unterminated.
fn skip_cooked(body: &str, close: char) -> Option<(&str, &str)> {
    let mut it = body.char_indices();
    while let Some((at, c)) = it.next() {
        if c == '\\' {
            it.next();
        } else if c == close {
            let end = at.saturating_add(c.len_utf8());
            return Some((body.get(..at)?, body.get(end..)?));
        }
    }
    None
}

/// Strips comments and literal payloads from Rust source text while preserving line numbering.
///
/// Empties literal contents while retaining enclosing quote delimiters (turning `extern "C"` into
/// `extern ""`), or returns the 1-based starting line number of any unclosed comment or literal.
fn strip(text: &str) -> Result<String, usize> {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    let mut line = 1usize;
    let mut prev: Option<char> = None;
    while let Some(c) = rest.chars().next() {
        let start = line;
        if let Some(after) = rest.strip_prefix("//") {
            rest = after.find('\n').and_then(|n| after.get(n..)).unwrap_or("");
            prev = Some(' ');
            continue;
        }
        if let Some(after) = rest.strip_prefix("/*") {
            rest = after;
            let mut depth = 1usize;
            while depth > 0 {
                if let Some(r) = rest.strip_prefix("*/") {
                    depth = depth.saturating_sub(1);
                    rest = r;
                } else if let Some(r) = rest.strip_prefix("/*") {
                    depth = depth.saturating_add(1);
                    rest = r;
                } else {
                    let mut it = rest.chars();
                    match it.next() {
                        None => return Err(start),
                        Some('\n') => keep_newlines("\n", &mut out, &mut line),
                        Some(_) => {}
                    }
                    rest = it.as_str();
                }
            }
            prev = Some(' ');
            continue;
        }
        if !prev.is_some_and(ident)
            && let Some((hashes, body)) = raw_open(rest)
        {
            let close = format!("\"{}", "#".repeat(hashes));
            let Some(end) = body.find(&close) else {
                return Err(start);
            };
            out.push('"');
            keep_newlines(body.get(..end).unwrap_or(""), &mut out, &mut line);
            out.push('"');
            rest = body.get(end.saturating_add(close.len())..).unwrap_or("");
            prev = Some('"');
            continue;
        }
        if let Some(body) = rest.strip_prefix('"') {
            let Some((skipped, after)) = skip_cooked(body, '"') else {
                return Err(start);
            };
            out.push('"');
            keep_newlines(skipped, &mut out, &mut line);
            out.push('"');
            rest = after;
            prev = Some('"');
            continue;
        }
        if let Some(body) = rest.strip_prefix('\'') {
            let mut it = body.chars();
            let first = it.next();
            let literal = first == Some('\\') || (first != Some('\n') && it.next() == Some('\''));
            if literal {
                let Some((_, after)) = skip_cooked(body, '\'') else {
                    return Err(start);
                };
                out.push_str("''");
                rest = after;
                prev = Some('\'');
                continue;
            }
            // A lifetime or a loop label stays as code.
        }
        out.push(c);
        if c == '\n' {
            line = line.saturating_add(1);
        }
        prev = Some(c);
        rest = rest.get(c.len_utf8()..).unwrap_or("");
    }
    Ok(out)
}

/// Determines whether an `unsafe` keyword occurrence followed by `rest` is permitted outside
/// the audited modules (specifically `#[unsafe(no_mangle)]` or an `unsafe fn(...)` pointer
/// type).
fn allowed_use(rest: &str) -> bool {
    let rest = rest.trim_start();
    if rest.starts_with("(no_mangle)") {
        return true;
    }
    let rest = rest.strip_prefix("extern").map_or(rest, |r| {
        let r = r.trim_start();
        r.strip_prefix("\"\"").unwrap_or(r).trim_start()
    });
    rest.strip_prefix("fn")
        .is_some_and(|r| r.trim_start().starts_with('('))
}

/// Returns the 1-based line numbers of all disallowed `unsafe` usages in `text`.
///
/// Unterminated comments or literals report their opening line number so scanning fails closed.
fn violations(text: &str) -> Vec<usize> {
    let code = match strip(text) {
        Ok(code) => code,
        Err(line) => return vec![line],
    };
    let mut bad = Vec::new();
    for (n, code) in code.lines().enumerate() {
        let mut from = 0;
        while let Some(i) = code.get(from..).and_then(|s| s.find("unsafe")) {
            let at = from.saturating_add(i);
            let end = at.saturating_add("unsafe".len());
            let before = code.get(..at).and_then(|s| s.chars().next_back());
            let after = code.get(end..).unwrap_or("");
            let word = !before.is_some_and(ident) && !after.chars().next().is_some_and(ident);
            if word && !allowed_use(after) {
                bad.push(n.saturating_add(1));
                break;
            }
            from = end;
        }
    }
    bad
}

#[test]
fn scanner_finds_unsafe_code() {
    assert_eq!(violations("let x = unsafe { f() };"), vec![1]);
    assert_eq!(violations("pub unsafe fn f() {}"), vec![1]);
    assert_eq!(violations("unsafe impl Send for X {}"), vec![1]);
    assert_eq!(violations("unsafe trait T {}"), vec![1]);
    assert_eq!(violations("pub unsafe extern \"C\" fn f() {}"), vec![1]);
    assert_eq!(violations("unsafe extern \"C\" {\n}"), vec![1]);
    assert_eq!(violations("ok();\nunsafe {}"), vec![2]);
    assert_eq!(violations("/* \" */ unsafe { f() } /* \" */"), vec![1]);
    assert_eq!(violations("let c = '\"'; unsafe { f() }"), vec![1]);
    assert_eq!(violations("let s = r#\"a\"b\"#; unsafe { f() }"), vec![1]);
    assert_eq!(
        violations("let s = \"a\nb\\\" c\n\"; unsafe { f() }"),
        vec![3]
    );
    assert_eq!(violations("fn f<'a>(x: &'a u8) { unsafe {} }"), vec![1]);
    assert_eq!(violations("'outer: loop { unsafe {} }"), vec![1]);
    assert_eq!(violations("let c = '\\''; unsafe {}"), vec![1]);
    assert_eq!(violations("let b = b'\"'; unsafe {}"), vec![1]);
    assert_eq!(violations("let s = br\"x\"; unsafe {}"), vec![1]);
    // A comment or a literal that does not close fails closed at its start line.
    assert_eq!(violations("/* not closed\nunsafe {}"), vec![1]);
    assert_eq!(violations("ok();\n\"not closed\nunsafe {}"), vec![2]);
    assert_eq!(violations("let s = r#\"not closed\"\n"), vec![1]);
}

#[test]
fn scanner_skips_allowed_forms() {
    assert!(violations("#[unsafe(no_mangle)]").is_empty());
    assert!(violations("f: Option<unsafe extern \"C\" fn(usize)>,").is_empty());
    assert!(violations("f: unsafe fn(u8) -> u8,").is_empty());
    assert!(violations("#![deny(unsafe_code)]").is_empty());
    assert!(violations("// unsafe { f() }").is_empty());
    assert!(violations("/// SAFETY: unsafe { x }").is_empty());
    assert!(violations("let s = \"unsafe { }\";").is_empty());
    assert!(violations("is_unsafe(); unsafety();").is_empty());
    assert!(violations("/* unsafe { f() } */").is_empty());
    assert!(violations("/* outer /* inner */ unsafe {} */").is_empty());
    assert!(violations("/*\nunsafe {}\n*/").is_empty());
    assert!(violations("let s = r#\"unsafe { }\"#;").is_empty());
    assert!(violations("let s = \"a\nunsafe {}\n\";").is_empty());
    assert!(violations("let c = 'u'; let r#unsafe_ish = 1;").is_empty());
    assert!(violations("#[unsafe(no_mangle)] pub extern \"C\" fn f() {}").is_empty());
}

#[test]
fn unsafe_code_stays_in_the_audited_modules() {
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut files = Vec::new();
    rust_files(&src, &mut files);
    assert!(!files.is_empty());
    let mut bad = Vec::new();
    for f in files {
        let rel = f.strip_prefix(&src).unwrap().to_path_buf();
        if is_audited(&rel) {
            continue;
        }
        let text = fs::read_to_string(&f).unwrap();
        for n in violations(&text) {
            bad.push(format!("{}:{n}", rel.display()));
        }
    }
    assert!(bad.is_empty(), "unsafe code outside abi::raw: {bad:?}");
}
