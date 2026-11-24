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

//! Lightweight C tokenizer and preprocessor tracker tailored for legacy C sources and headers.
//!
//! Operates directly on source bytes without requiring a full preprocessor pipeline.
//! Strips comments and directives while attaching rich lexical context to each token,
//! including 1-based source lines, curly brace nesting depth, and enclosing preprocessor
//! conditional compilation expressions. Inactive branches (e.g. `#if 0`) are pruned.
//!
//! To accommodate conditional header blocks where alternative `#ifdef` / `#else` branches
//! declare mismatched brace pairs (such as conditional function signatures), the tokenizer
//! preserves the brace depth established by the primary branch across subsequent alternatives.

use std::collections::HashMap;

/// Lexical category classifying a parsed C token.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    /// Identifier or language keyword.
    Ident,
    /// String literal retaining raw character content between enclosing quotes.
    Str,
    /// Character literal retaining raw character content between single quotes.
    Char,
    /// Numeric literal constant (integer or floating point).
    Num,
    /// Punctuation glyph or operator token.
    Punct,
}

/// Token representation retaining text, line coordinates, nesting depth, and preprocessor context.
#[derive(Clone, Debug)]
pub(crate) struct Token {
    /// Lexical classification of the token.
    pub(crate) kind: Kind,
    /// Raw token text as preserved in the source input.
    pub(crate) text: String,
    /// 1-based line number where the token begins.
    pub(crate) line: u32,
    /// Curly brace nesting depth (`{` and `}` share identical depth levels).
    pub(crate) depth: u32,
    /// Index pointing to the active conditional expression in [`Lexed::conds`].
    pub(crate) cond: usize,
}

impl Token {
    /// Checks whether this token represents the specified single punctuation character.
    pub(crate) fn is_punct(&self, c: char) -> bool {
        self.kind == Kind::Punct && self.text.len() == 1 && self.text.starts_with(c)
    }

    /// Checks whether this token is an identifier matching the specified string.
    pub(crate) fn is_ident(&self, s: &str) -> bool {
        self.kind == Kind::Ident && self.text == s
    }
}

/// Complete token stream resulting from tokenizing a C source or header file.
#[derive(Debug)]
pub(crate) struct Lexed {
    /// Sequence of active source tokens in program order.
    pub(crate) tokens: Vec<Token>,
    /// Deduplicated table of conditional compilation expressions (index 0 is unconditional).
    pub(crate) conds: Vec<String>,
}

impl Lexed {
    /// Returns the preprocessor conditional expression gating `t`, or `""` if unconditional.
    pub(crate) fn cond_of(&self, t: &Token) -> &str {
        self.conds.get(t.cond).map_or("", String::as_str)
    }
}

/// Tracking frame for an active preprocessor conditional block (`#if`, `#ifdef`, `#ifndef`).
struct Frame {
    /// Condition expressions encountered across prior `#if` and `#elif` branches in this block.
    exprs: Vec<String>,
    /// Indicates whether the active branch is an `#else` block.
    in_else: bool,
    /// True when this block is an idempotent `#ifndef` file include guard.
    guard: bool,
    /// Curly brace depth recorded upon entry into the `#if` directive.
    depth_at_if: u32,
    /// Curly brace depth observed at the conclusion of the initial conditional branch.
    first_end: Option<u32>,
}

impl Frame {
    /// Returns `true` if static evaluation proves the active branch is dead code (e.g. `#if 0`).
    fn dead(&self) -> bool {
        if self.in_else {
            self.exprs.iter().any(|e| e == "1")
        } else {
            self.exprs.last().is_some_and(|e| e == "0")
        }
    }

    /// Appends the active branch's Boolean condition terms to the target accumulator.
    fn parts(&self, out: &mut Vec<String>) {
        if self.guard {
            return;
        }
        let (prior, own) = if self.in_else {
            (self.exprs.as_slice(), None)
        } else {
            match self.exprs.split_last() {
                Some((last, prior)) => (prior, Some(last)),
                None => (self.exprs.as_slice(), None),
            }
        };
        for e in prior {
            if e != "0" {
                out.push(negate(e));
            }
        }
        if let Some(e) = own {
            out.push(e.clone());
        }
    }
}

/// Inverts a preprocessor condition, eliding redundant parentheses around atomic identifier terms.
fn negate(e: &str) -> String {
    if let Some(rest) = e.strip_prefix('!') {
        if is_name(rest) {
            return rest.to_owned();
        }
    }
    if is_name(e) {
        format!("!{e}")
    } else {
        format!("!({e})")
    }
}

/// Checks whether a given string adheres to C identifier naming rules.
fn is_name(s: &str) -> bool {
    let mut it = s.chars();
    it.next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && it.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Internal lexical scanner state tracking source cursor, nesting, and conditional frames.
struct Lexer<'a> {
    src: &'a [u8],
    pos: usize,
    line: u32,
    line_start: bool,
    depth: u32,
    frames: Vec<Frame>,
    conds: Vec<String>,
    cond_ids: HashMap<String, usize>,
    cond: usize,
    tokens: Vec<Token>,
}

/// Tokenizes a C source or header string into an annotated stream of tokens.
pub(crate) fn lex(src: &str) -> Lexed {
    let mut lx = Lexer {
        src: src.as_bytes(),
        pos: 0,
        line: 1,
        line_start: true,
        depth: 0,
        frames: Vec::new(),
        conds: vec![String::new()],
        cond_ids: HashMap::from([(String::new(), 0)]),
        cond: 0,
        tokens: Vec::new(),
    };
    lx.run();
    Lexed {
        tokens: lx.tokens,
        conds: lx.conds,
    }
}

impl Lexer<'_> {
    fn peek(&self, off: usize) -> Option<u8> {
        self.src.get(self.pos.saturating_add(off)).copied()
    }

    fn bump(&mut self) -> Option<u8> {
        let c = self.peek(0)?;
        self.pos = self.pos.saturating_add(1);
        if c == b'\n' {
            self.line = self.line.saturating_add(1);
        }
        Some(c)
    }

    fn dead(&self) -> bool {
        self.frames.iter().any(Frame::dead)
    }

    fn run(&mut self) {
        while let Some(c) = self.peek(0) {
            match c {
                b'\n' => {
                    self.bump();
                    self.line_start = true;
                }
                b' ' | b'\t' | b'\r' | 0x0c | 0x0b => {
                    self.bump();
                }
                b'\\' if self.peek(1) == Some(b'\n') => {
                    self.bump();
                    self.bump();
                }
                b'/' if self.peek(1) == Some(b'*') => self.block_comment(),
                b'/' if self.peek(1) == Some(b'/') => self.line_comment(),
                b'#' if self.line_start => self.directive(),
                _ => {
                    self.line_start = false;
                    self.token(c);
                }
            }
        }
    }

    fn block_comment(&mut self) {
        self.bump();
        self.bump();
        while let Some(c) = self.bump() {
            if c == b'*' && self.peek(0) == Some(b'/') {
                self.bump();
                return;
            }
        }
    }

    fn line_comment(&mut self) {
        while let Some(c) = self.peek(0) {
            if c == b'\n' {
                return;
            }
            if c == b'\\' && self.peek(1) == Some(b'\n') {
                self.bump();
            }
            self.bump();
        }
    }

    /// Reads the body of a preprocessor directive, stripping comments and folding `\` line
    /// continuations.
    fn directive_text(&mut self) -> String {
        let mut out = Vec::new();
        while let Some(c) = self.peek(0) {
            match c {
                b'\n' => break,
                b'\\' if self.peek(1) == Some(b'\n') => {
                    self.bump();
                    self.bump();
                    out.push(b' ');
                }
                b'/' if self.peek(1) == Some(b'*') => {
                    self.block_comment();
                    out.push(b' ');
                }
                b'/' if self.peek(1) == Some(b'/') => self.line_comment(),
                _ => {
                    out.push(c);
                    self.bump();
                }
            }
        }
        String::from_utf8_lossy(&out)
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")
    }

    /// Peeks ahead to check whether the next non-empty line defines `name`.
    fn next_is_define(&self, name: &str) -> bool {
        let rest = self.src.get(self.pos..).unwrap_or_default();
        let rest = String::from_utf8_lossy(rest);
        for l in rest.lines() {
            let l = l.trim();
            if l.is_empty() {
                continue;
            }
            let l = l.trim_start_matches('#').trim_start();
            return l
                .strip_prefix("define")
                .is_some_and(|r| r.split_whitespace().next() == Some(name));
        }
        false
    }

    fn directive(&mut self) {
        self.bump();
        let text = self.directive_text();
        let (word, rest) = match text.split_once(' ') {
            Some((w, r)) => (w.to_owned(), r.trim().to_owned()),
            None => (text.clone(), String::new()),
        };
        match word.as_str() {
            "if" | "ifdef" | "ifndef" => {
                let expr = match word.as_str() {
                    "ifdef" => rest.clone(),
                    "ifndef" => negate(&rest),
                    _ => normalize_expr(&rest),
                };
                let guard =
                    word == "ifndef" && self.frames.is_empty() && self.next_is_define(&rest);
                self.frames.push(Frame {
                    exprs: vec![expr],
                    in_else: false,
                    guard,
                    depth_at_if: self.depth,
                    first_end: None,
                });
            }
            "elif" | "else" => {
                let depth = self.depth;
                if let Some(f) = self.frames.last_mut() {
                    if f.first_end.is_none() {
                        f.first_end = Some(depth);
                    }
                    if word == "elif" {
                        f.exprs.push(normalize_expr(&rest));
                    } else {
                        f.in_else = true;
                    }
                    self.depth = f.depth_at_if;
                }
            }
            "endif" => {
                if let Some(f) = self.frames.pop() {
                    if let Some(d) = f.first_end {
                        self.depth = d;
                    }
                }
            }
            _ => {}
        }
        self.update_cond();
    }

    fn update_cond(&mut self) {
        let mut parts = Vec::new();
        for f in &self.frames {
            f.parts(&mut parts);
        }
        let text = parts.join(" && ");
        let next = self.conds.len();
        let id = *self.cond_ids.entry(text.clone()).or_insert(next);
        if id == next {
            self.conds.push(text);
        }
        self.cond = id;
    }

    fn token(&mut self, c: u8) {
        let line = self.line;
        let (kind, text) = if c.is_ascii_alphabetic() || c == b'_' {
            (
                Kind::Ident,
                self.take_while(|b| b.is_ascii_alphanumeric() || b == b'_'),
            )
        } else if c.is_ascii_digit()
            || (c == b'.' && self.peek(1).is_some_and(|d| d.is_ascii_digit()))
        {
            (Kind::Num, self.number())
        } else if c == b'"' {
            (Kind::Str, self.quoted(b'"'))
        } else if c == b'\'' {
            (Kind::Char, self.quoted(b'\''))
        } else {
            self.bump();
            (Kind::Punct, char::from(c).to_string())
        };
        if self.dead() {
            return;
        }
        let mut depth = self.depth;
        if kind == Kind::Punct {
            if text == "{" {
                self.depth = self.depth.saturating_add(1);
            } else if text == "}" {
                self.depth = self.depth.saturating_sub(1);
                depth = self.depth;
            }
        }
        self.tokens.push(Token {
            kind,
            text,
            line,
            depth,
            cond: self.cond,
        });
    }

    fn take_while(&mut self, f: impl Fn(u8) -> bool) -> String {
        let start = self.pos;
        while self.peek(0).is_some_and(&f) {
            self.bump();
        }
        String::from_utf8_lossy(self.src.get(start..self.pos).unwrap_or_default()).into_owned()
    }

    fn number(&mut self) -> String {
        let start = self.pos;
        while let Some(c) = self.peek(0) {
            let exp = matches!(c, b'+' | b'-')
                && self
                    .pos
                    .checked_sub(1)
                    .and_then(|p| self.src.get(p))
                    .is_some_and(|p| matches!(p, b'e' | b'E' | b'p' | b'P'));
            if c.is_ascii_alphanumeric() || c == b'.' || c == b'_' || exp {
                self.bump();
            } else {
                break;
            }
        }
        String::from_utf8_lossy(self.src.get(start..self.pos).unwrap_or_default()).into_owned()
    }

    fn quoted(&mut self, q: u8) -> String {
        self.bump();
        let mut out = Vec::new();
        while let Some(c) = self.peek(0) {
            if c == q || c == b'\n' {
                break;
            }
            if c == b'\\' {
                if self.peek(1) == Some(b'\n') {
                    self.bump();
                    self.bump();
                    continue;
                }
                out.push(c);
                self.bump();
                if let Some(n) = self.bump() {
                    out.push(n);
                }
                continue;
            }
            out.push(c);
            self.bump();
        }
        if self.peek(0) == Some(q) {
            self.bump();
        }
        String::from_utf8_lossy(&out).into_owned()
    }
}

/// Canonicalizes a preprocessor expression by stripping `defined` wrappers and redundant outer
/// parentheses.
fn normalize_expr(e: &str) -> String {
    let mut s = e.replace("defined(", "(").replace("defined ", "");
    while s.starts_with('(') && s.ends_with(')') && balanced_inner(&s) {
        s = s
            .get(1..s.len().saturating_sub(1))
            .unwrap_or_default()
            .trim()
            .to_owned();
    }
    let mut out = String::new();
    let mut rest = s.as_str();
    while let Some(i) = rest.find('(') {
        let (head, tail) = rest.split_at(i);
        let tail = tail.get(1..).unwrap_or_default();
        if let Some(j) = tail.find(')') {
            let inner = tail.get(..j).unwrap_or_default().trim();
            if is_name(inner)
                && !head
                    .trim_end()
                    .ends_with(|c: char| c.is_ascii_alphanumeric() || c == '_')
            {
                out.push_str(head);
                out.push_str(inner);
                rest = tail.get(j.saturating_add(1)..).unwrap_or_default();
                continue;
            }
        }
        out.push_str(head);
        out.push('(');
        rest = tail;
    }
    out.push_str(rest);
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Returns `true` if the leading `(` matches the trailing `)` across the entire expression.
fn balanced_inner(s: &str) -> bool {
    let mut depth = 0_i32;
    let n = s.len();
    for (i, c) in s.char_indices() {
        match c {
            '(' => depth = depth.saturating_add(1),
            ')' => {
                depth = depth.saturating_sub(1);
                if depth == 0 && i.saturating_add(1) != n {
                    return false;
                }
            }
            _ => {}
        }
    }
    true
}

/// Splits comma-delimited call arguments starting at opening parenthesis `open`, returning token
/// index ranges for each argument and the closing parenthesis index.
pub(crate) fn call_args(
    tokens: &[Token],
    open: usize,
) -> Option<(Vec<std::ops::Range<usize>>, usize)> {
    let mut nest = 0_u32;
    let mut args = Vec::new();
    let mut start = open.checked_add(1)?;
    let mut i = start;
    while let Some(t) = tokens.get(i) {
        if t.kind == Kind::Punct {
            match t.text.as_str() {
                "(" | "[" | "{" => nest = nest.saturating_add(1),
                ")" | "]" | "}" if nest > 0 => nest = nest.saturating_sub(1),
                ")" => {
                    args.push(start..i);
                    return Some((args, i));
                }
                "," if nest == 0 => {
                    args.push(start..i);
                    start = i.checked_add(1)?;
                }
                _ => {}
            }
        }
        i = i.checked_add(1)?;
    }
    None
}

/// State machine tracking the enclosing C function name for tokens encountered at file scope.
#[derive(Default)]
pub(crate) struct FuncTracker {
    candidate: Option<String>,
    paren: u32,
    last_ident: Option<String>,
    current: Option<String>,
}

impl FuncTracker {
    /// Advances state with `t` and returns the enclosing function name when inside a function body.
    pub(crate) fn feed(&mut self, t: &Token) -> Option<&str> {
        if t.depth == 0 {
            match (t.kind, t.text.as_str()) {
                (Kind::Punct, "(") => {
                    if self.paren == 0 {
                        if let Some(id) = self.last_ident.take() {
                            if !id.starts_with("__attribute") {
                                self.candidate = Some(id);
                            }
                        }
                    }
                    self.paren = self.paren.saturating_add(1);
                }
                (Kind::Punct, ")") => self.paren = self.paren.saturating_sub(1),
                (Kind::Punct, ";" | "=" | "}") => {
                    self.candidate = None;
                    self.last_ident = None;
                    self.current = None;
                }
                (Kind::Punct, "{") => {
                    self.current = self.candidate.take();
                    self.last_ident = None;
                }
                (Kind::Ident, _) => self.last_ident = Some(t.text.clone()),
                _ => self.last_ident = None,
            }
        }
        self.current.as_deref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn conditions_and_depth() {
        let src = "#ifndef G_H\n#define G_H\n#ifdef A\nint f(int a) {\n#else\nint f(void) {\n#endif\n  x(\"s\\\"t\", 'c');\n}\n#if 0\nint dead;\n#endif\n#endif\n";
        let lx = lex(src);
        let x = lx.tokens.iter().find(|t| t.is_ident("x"));
        assert_eq!(x.map(|t| (t.line, t.depth)), Some((8, 1)));
        assert_eq!(x.map(|t| lx.cond_of(t)), Some(""));
        let a = lx.tokens.iter().find(|t| t.is_ident("a"));
        assert_eq!(a.map(|t| lx.cond_of(t)), Some("A"));
        let v = lx.tokens.iter().find(|t| t.is_ident("void"));
        assert_eq!(v.map(|t| lx.cond_of(t)), Some("!A"));
        assert!(!lx.tokens.iter().any(|t| t.is_ident("dead")));
        let s = lx.tokens.iter().find(|t| t.kind == Kind::Str);
        assert_eq!(s.map(|t| t.text.as_str()), Some("s\\\"t"));
    }

    #[test]
    fn function_tracking() {
        let lx = lex(
            "static int g = 1;\nvoid __attribute__((x)) h(int a)\n{\n  call(1);\n}\nstruct s { int (*fp)(void); };\n",
        );
        let mut ft = FuncTracker::default();
        let mut seen = Vec::new();
        for t in &lx.tokens {
            let f = ft.feed(t).map(str::to_owned);
            if t.is_ident("call") || t.is_ident("fp") {
                seen.push(f);
            }
        }
        assert_eq!(seen, vec![Some("h".to_owned()), None]);
    }

    #[test]
    fn expressions() {
        assert_eq!(
            normalize_expr("defined(_WIN32) && !defined(X)"),
            "_WIN32 && !X"
        );
        assert_eq!(normalize_expr("(A)"), "A");
        assert_eq!(negate("!A"), "A");
        assert_eq!(negate("A || B"), "!(A || B)");
    }
}
