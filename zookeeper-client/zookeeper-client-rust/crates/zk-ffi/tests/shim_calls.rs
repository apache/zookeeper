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

//! Source test for the C shim call rule in `IMPLEMENTATION_GUIDE.md` section 1.2.
//!
//! Every call from a shim unit (`c/shim/*.c`) into a `zkrs_*` export must be enclosed in one of
//! the `ZKS_CALL` macros (`ZKS_CALL`, `ZKS_CALL_VOID`, `ZKS_CALL_LEAF`, or `ZKS_CALL_LEAF_VOID`).
//! Each macro calls `zks_check_call`, which aborts the process when invoked from a vtable entry (a
//! thread running under `zks_in_vtable`).
//!
//! The test blanks out comments along with string and character literals in each shim unit and
//! reports every `zkrs_*` call not enclosed by a `ZKS_CALL` macro with its file and line number.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

use std::fs;
use std::path::{Path, PathBuf};

const WRAPPERS: [&str; 4] = [
    "ZKS_CALL",
    "ZKS_CALL_VOID",
    "ZKS_CALL_LEAF",
    "ZKS_CALL_LEAF_VOID",
];

fn shim_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../c/shim")
}

/// Replaces comments and the contents of string and character literals with spaces, preserving
/// newlines so line numbers remain unchanged.
fn strip(src: &str) -> String {
    let mut out = String::with_capacity(src.len());
    let mut it = src.chars().peekable();
    while let Some(c) = it.next() {
        match c {
            '/' if it.peek() == Some(&'*') => {
                it.next();
                out.push_str("  ");
                let mut prev = ' ';
                for d in it.by_ref() {
                    out.push(if d == '\n' { '\n' } else { ' ' });
                    if prev == '*' && d == '/' {
                        break;
                    }
                    prev = d;
                }
            }
            '/' if it.peek() == Some(&'/') => {
                for d in it.by_ref() {
                    if d == '\n' {
                        out.push('\n');
                        break;
                    }
                    out.push(' ');
                }
            }
            '"' | '\'' => {
                out.push(c);
                let mut esc = false;
                for d in it.by_ref() {
                    if d == '\n' {
                        out.push('\n');
                    } else if !esc && d == c {
                        out.push(c);
                        break;
                    } else {
                        out.push(' ');
                    }
                    esc = !esc && d == '\\';
                }
            }
            _ => out.push(c),
        }
    }
    out
}

/// Returns `(line, name)` for each `zkrs_*` call in `src` not enclosed by a `ZKS_CALL` macro.
fn unwrapped_calls(src: &str) -> Vec<(usize, String)> {
    let text = strip(src);
    let mut bad = Vec::new();
    // Holds the parenthesis depth at which each open `ZKS_CALL` argument list started.
    let mut open: Vec<usize> = Vec::new();
    let mut depth = 0usize;
    let mut line = 1usize;
    let mut it = text.char_indices().peekable();
    while let Some((i, c)) = it.next() {
        if c == '\n' {
            line = line.saturating_add(1);
        } else if c == '(' {
            depth = depth.saturating_add(1);
        } else if c == ')' {
            if open.last() == Some(&depth) {
                open.pop();
            }
            depth = depth.saturating_sub(1);
        } else if c.is_ascii_alphabetic() || c == '_' {
            let mut end = i.saturating_add(c.len_utf8());
            while let Some(&(j, d)) = it.peek() {
                if !(d.is_ascii_alphanumeric() || d == '_') {
                    break;
                }
                end = j.saturating_add(d.len_utf8());
                it.next();
            }
            let word = text.get(i..end).unwrap();
            let rest = text.get(end..).unwrap().trim_start_matches([' ', '\t']);
            if !rest.starts_with('(') {
                continue;
            }
            if WRAPPERS.contains(&word) {
                open.push(depth.saturating_add(1));
            } else if word.starts_with("zkrs_") && open.is_empty() {
                bad.push((line, word.to_owned()));
            }
        }
    }
    bad
}

#[test]
fn the_scanner_finds_an_unwrapped_call() {
    let src = "int f(void) {\n  /* zkrs_a() */ ZKS_CALL(zkrs_b(g(1)));\n  return zkrs_c();\n}\n";
    assert_eq!(unwrapped_calls(src), vec![(3, "zkrs_c".to_owned())]);
}

#[test]
fn every_shim_call_into_rust_uses_a_call_macro() {
    let mut files: Vec<PathBuf> = fs::read_dir(shim_dir())
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "c"))
        .collect();
    files.sort();
    assert!(
        !files.is_empty(),
        "no shim units in {}",
        shim_dir().display()
    );
    let mut bad = Vec::new();
    for f in &files {
        let src = fs::read_to_string(f).unwrap();
        for (line, name) in unwrapped_calls(&src) {
            bad.push(format!(
                "{}:{line}: {name}",
                f.file_name().unwrap().to_string_lossy()
            ));
        }
    }
    assert!(bad.is_empty(), "zkrs_* calls outside ZKS_CALL: {bad:?}");
}
