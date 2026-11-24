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

//! Strongly typed, validated ZooKeeper hierarchy paths.
//!
//! This module provides zero-copy borrowed (`ZPath`) and owned (`ZPathBuf`) path abstractions
//! backed by ZooKeeper's strict path validation rules (`zk_proto::native::validate_path_strict`).
//! It also includes compile-time const validators, component iterators, and path manipulation
//! helpers.
//!
//! `ZPath<'a>` is defined as a sized, `Copy` wrapper over `&'a str`. An unsized dynamically sized
//! type such as `ZPath(str)` would necessitate raw pointer casting upon creation, which is
//! precluded by the crate-wide `#![forbid(unsafe_code)]` policy.

use zk_proto::native::{PathErrorKind, validate_path_strict};

use crate::error::Error;

/// An immutable, borrowed reference to a validated absolute ZooKeeper path.
///
/// Invariant: the underlying character slice conforms strictly to the validation
/// invariants enforced by the canonical Java ZooKeeper client (`PathUtils.validatePath`),
/// ensuring leading slash requirements, absence of relative components (`.` / `..`), and
/// exclusion of invalid or null characters.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ZPath<'a>(&'a str);

/// An owned, validated absolute ZooKeeper path stored in heap memory.
///
/// Enforces identical structural invariants to [`ZPath`], guaranteeing that the string
/// represents a well-formed hierarchical node path that can be safely transmitted to ZooKeeper.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ZPathBuf(String);

/// Detailed diagnostic information explaining why a path failed validation.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub struct PathError {
    /// The invalid path string that was evaluated.
    pub path: String,
    /// The byte offset within `path` where the violation was detected. Defaults to 0
    /// for empty strings or paths lacking a required leading slash.
    pub index: usize,
    /// The specific structural or character validation rule violated by the path.
    pub reason: PathErrorReason,
}

/// Structural or lexical validation rule violated by an invalid path string.
#[non_exhaustive]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PathErrorReason {
    /// The supplied path string contains zero characters.
    Empty,
    /// The path does not begin with an absolute root slash (`/`).
    NotAbsolute,
    /// The path contains consecutive slashes (`//`), denoting an empty node component name.
    EmptyComponent,
    /// The path ends with a trailing slash (`/`) on a non-root znode hierarchy.
    TrailingSlash,
    /// The path contains relative navigation components such as `.` or `..`.
    RelativeComponent,
    /// The path contains an illegal character rejected by canonical ZooKeeper specifications.
    InvalidChar(char),
}

impl std::fmt::Display for PathErrorReason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Empty => f.write_str("path length must be > 0"),
            Self::NotAbsolute => f.write_str("path must start with / character"),
            Self::EmptyComponent => f.write_str("empty node name specified"),
            Self::TrailingSlash => f.write_str("path must not end with / character"),
            Self::RelativeComponent => f.write_str("relative paths not allowed"),
            Self::InvalidChar(c) => write!(f, "invalid character {:?}", c),
        }
    }
}

impl std::fmt::Display for PathError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "invalid path string {:?} caused by {} @{}",
            self.path, self.reason, self.index
        )
    }
}

impl std::error::Error for PathError {}

/// Translates a UTF-16 code unit offset into the UTF-8 byte boundary offset within `s`.
fn byte_offset(s: &str, utf16: usize) -> usize {
    let mut units = 0usize;
    for (at, c) in s.char_indices() {
        if units >= utf16 {
            return at;
        }
        units = units.saturating_add(c.len_utf16());
    }
    s.len()
}

/// Translates a low-level wire protocol path validation error into a public [`PathError`].
fn convert(path: &str, e: &zk_proto::native::PathError) -> PathError {
    let index = e.index.map_or(0, |i| byte_offset(path, i));
    let reason = match e.kind {
        PathErrorKind::Empty => PathErrorReason::Empty,
        PathErrorKind::NotAbsolute => PathErrorReason::NotAbsolute,
        PathErrorKind::TrailingSlash => PathErrorReason::TrailingSlash,
        PathErrorKind::NulCharacter => PathErrorReason::InvalidChar('\0'),
        PathErrorKind::EmptyComponent => PathErrorReason::EmptyComponent,
        PathErrorKind::RelativeComponent => PathErrorReason::RelativeComponent,
        PathErrorKind::InvalidCharacter => PathErrorReason::InvalidChar(
            path.get(index..)
                .and_then(|rest| rest.chars().next())
                .unwrap_or(char::REPLACEMENT_CHARACTER),
        ),
    };
    let index = match e.kind {
        PathErrorKind::TrailingSlash => path.len().saturating_sub(1),
        _ => index,
    };
    PathError {
        path: path.to_owned(),
        index,
        reason,
    }
}

/// Validates `path` against canonical ZooKeeper path constraints.
///
/// When `sequential` is true, the validation permits a trailing slash because sequential
/// node creation appends an autogenerated numeric suffix to the specified prefix.
pub(crate) fn check(path: &str, sequential: bool) -> Result<(), PathError> {
    validate_path_strict(path, sequential).map_err(|e| convert(path, &e))
}

/// Compile time path validator implementation used by [`ZPath::from_static`].
///
/// Concurrency and correctness invariant: Maintains identical acceptance criteria to
/// `validate_path_strict(s, false)` by manually parsing UTF-8 byte sequences without dynamic
/// memory allocations, allowing path verification during compile time constant evaluation.
///
/// Operates across full path components, enforcing non-empty segments, barring relative
/// navigation elements (`.` and `..`), and rejecting Unicode codepoint ranges forbidden by
/// ZooKeeper's canonical Java implementation.
const fn check_static(s: &str) -> bool {
    let mut rest = match s.as_bytes() {
        [b'/', tail @ ..] => tail,
        _ => return false,
    };
    if rest.is_empty() {
        return true;
    }
    // Track component length in characters along with dot-only character composition.
    let mut len = 0usize;
    let mut dots = true;
    while let Some((c, tail)) = decode_utf8(rest) {
        rest = tail;
        if c == 0x2f {
            if !component_ok(len, dots) {
                return false;
            }
            len = 0;
            dots = true;
        } else {
            if java_invalid_char(c) {
                return false;
            }
            len = len.saturating_add(1);
            dots = dots && c == 0x2e;
        }
    }
    // Since Rust string slices are guaranteed well-formed UTF-8, decoding failures
    // can only occur if an unexpected sequence boundary is encountered.
    rest.is_empty() && component_ok(len, dots)
}

/// Verifies that a path segment is non-empty and does not consist solely of `.` or `..`.
const fn component_ok(len: usize, dots: bool) -> bool {
    len != 0 && !(dots && len <= 2)
}

/// Identifies characters rejected by the canonical Java ZooKeeper client.
///
/// Rejection criteria encompass ASCII control characters (U+0000..=U+001F, U+007F..=U+009F),
/// surrogate pairs and private-use blocks (U+D800..=U+F8FF), non-characters (U+FFF0..=U+FFFF),
/// and supplementary code points above U+FFFF due to Java UTF-16 surrogate handling restrictions.
// `RangeInclusive::contains` cannot be used in a const context on stable Rust.
#[allow(clippy::manual_range_contains)]
const fn java_invalid_char(c: u32) -> bool {
    c <= 0x1f || (c >= 0x7f && c <= 0x9f) || (c >= 0xd800 && c <= 0xf8ff) || c >= 0xfff0
}

/// Decodes the leading UTF-8 code point from the byte slice in a const context,
/// returning the decoded scalar value along with the remaining unread slice.
const fn decode_utf8(b: &[u8]) -> Option<(u32, &[u8])> {
    match b {
        [b0, tail @ ..] if *b0 < 0x80 => Some((widen(*b0), tail)),
        [b0, b1, tail @ ..] if *b0 >= 0xc0 && *b0 < 0xe0 && is_cont(*b1) => {
            Some((bits(*b0, 0x1f, 6) | bits(*b1, 0x3f, 0), tail))
        }
        [b0, b1, b2, tail @ ..] if *b0 >= 0xe0 && *b0 < 0xf0 && is_cont(*b1) && is_cont(*b2) => {
            Some((
                bits(*b0, 0x0f, 12) | bits(*b1, 0x3f, 6) | bits(*b2, 0x3f, 0),
                tail,
            ))
        }
        [b0, b1, b2, b3, tail @ ..]
            if *b0 >= 0xf0 && *b0 < 0xf8 && is_cont(*b1) && is_cont(*b2) && is_cont(*b3) =>
        {
            Some((
                bits(*b0, 0x07, 18) | bits(*b1, 0x3f, 12) | bits(*b2, 0x3f, 6) | bits(*b3, 0x3f, 0),
                tail,
            ))
        }
        _ => None,
    }
}

/// Determines whether the given byte represents a UTF-8 continuation sequence byte (10xxxxxx).
const fn is_cont(b: u8) -> bool {
    b & 0xc0 == 0x80
}

/// Zero-extends a single byte to `u32` within a const context without relying on `as` casts.
const fn widen(b: u8) -> u32 {
    u32::from_le_bytes([b, 0, 0, 0])
}

/// Applies bitmask filtering to a byte and shifts the extracted bits into position for code point
/// assembly.
const fn bits(b: u8, mask: u8, shift: u32) -> u32 {
    widen(b & mask).wrapping_shl(shift)
}

impl<'a> ZPath<'a> {
    /// Static reference representing the ZooKeeper root path (`/`).
    pub const ROOT: ZPath<'static> = ZPath("/");

    /// Constructs a validated borrowed path reference from a string slice.
    ///
    /// # Errors
    ///
    /// Returns [`PathError`] detailing the first structural rule violated by the input.
    pub fn new(s: &'a str) -> Result<ZPath<'a>, PathError> {
        check(s, false)?;
        Ok(ZPath(s))
    }

    /// Validates a static string literal at compile time using a const function.
    ///
    /// Returns `None` if the literal fails path validation rules. In constant expressions,
    /// chaining `.unwrap()` produces a compile-time assertion failure for invalid literals,
    /// shifting validation overhead entirely from runtime execution to compilation.
    ///
    /// To retrieve detailed error diagnostics for runtime strings, prefer [`ZPath::new`].
    ///
    /// ```
    /// use apache_zookeeper::ZPath;
    /// const LOCKS: ZPath<'static> = ZPath::from_static("/app/locks").unwrap();
    /// assert_eq!(LOCKS.name(), Some("locks"));
    /// assert!(ZPath::from_static("/app/").is_none());
    /// ```
    ///
    /// A bad literal in a `const` item does not compile:
    ///
    /// ```compile_fail
    /// use apache_zookeeper::ZPath;
    /// const BAD: ZPath<'static> = ZPath::from_static("/app/").unwrap();
    /// assert_eq!(BAD.as_str(), "/app/");
    /// ```
    #[must_use]
    pub const fn from_static(s: &'static str) -> Option<ZPath<'static>> {
        if check_static(s) {
            Some(ZPath(s))
        } else {
            None
        }
    }

    /// Unchecked internal constructor used when path validity has already been guaranteed by
    /// caller logic.
    pub(crate) const fn new_unchecked(s: &'a str) -> ZPath<'a> {
        ZPath(s)
    }

    /// Returns the underlying path string slice.
    #[must_use]
    pub fn as_str(self) -> &'a str {
        self.0
    }

    /// Returns the immediate parent path reference, or `None` if this path is the root (`/`).
    #[must_use]
    pub fn parent(self) -> Option<ZPath<'a>> {
        if self.is_root() {
            return None;
        }
        match self.0.rfind('/') {
            Some(0) => Some(ZPath::ROOT),
            Some(at) => self.0.get(..at).map(ZPath),
            None => None,
        }
    }

    /// Extracts the final path segment name, or `None` if this path represents the root (`/`).
    #[must_use]
    pub fn name(self) -> Option<&'a str> {
        if self.is_root() {
            return None;
        }
        self.0.rsplit('/').next()
    }

    /// Produces an iterator yielding the individual segment names descending from root to leaf.
    #[must_use]
    pub fn components(self) -> Components<'a> {
        Components { rest: self.0 }
    }

    /// Concatenates one or more relative child segments onto this path.
    ///
    /// The `child` slice must be relative (omitting any leading slash), such as `"subnode"` or
    /// `"a/b"`.
    ///
    /// # Errors
    ///
    /// Returns [`PathError`] if the resulting combined path violates ZooKeeper path formatting
    /// rules.
    pub fn join(self, child: &str) -> Result<ZPathBuf, PathError> {
        let mut joined =
            String::with_capacity(self.0.len().saturating_add(child.len()).saturating_add(1));
        joined.push_str(self.0);
        if !self.is_root() {
            joined.push('/');
        }
        joined.push_str(child);
        if child.is_empty() {
            // Joining an empty segment to root yields `/`, which is structurally valid but
            // represents an illegal no-op join. Reject it as an empty component error.
            let index = joined.len().saturating_sub(1);
            return Err(PathError {
                path: joined,
                index,
                reason: PathErrorReason::EmptyComponent,
            });
        }
        ZPathBuf::new(joined)
    }

    /// Determines whether this path is identical to, or a descendant of, the specified `base`
    /// path.
    ///
    /// Comparison is evaluated segment by segment; for example, `/app` is not considered a base
    /// of `/app2`.
    #[must_use]
    pub fn starts_with(self, base: ZPath<'_>) -> bool {
        if base.is_root() {
            return true;
        }
        match self.0.strip_prefix(base.0) {
            Some(rest) => rest.is_empty() || rest.starts_with('/'),
            None => false,
        }
    }

    /// Returns `true` if this path represents the filesystem root (`/`).
    #[must_use]
    pub fn is_root(self) -> bool {
        self.0 == "/"
    }

    /// Allocates an owned [`ZPathBuf`] containing an independent copy of this path.
    #[must_use]
    pub fn to_buf(self) -> ZPathBuf {
        ZPathBuf(self.0.to_owned())
    }
}

/// Iterator over the hierarchical segment names of a [`ZPath`], traversing from root toward
/// leaf.
#[derive(Clone, Debug)]
pub struct Components<'a> {
    /// Remaining unparsed path slice, prefixed with a leading separator slash.
    rest: &'a str,
}

impl<'a> Iterator for Components<'a> {
    type Item = &'a str;

    /// Yields the next path segment name, or `None` once all segments have been consumed.
    fn next(&mut self) -> Option<&'a str> {
        let body = self.rest.strip_prefix('/')?;
        if body.is_empty() {
            self.rest = body;
            return None;
        }
        match body.find('/').and_then(|at| body.split_at_checked(at)) {
            Some((name, rest)) => {
                self.rest = rest;
                Some(name)
            }
            None => {
                self.rest = "";
                Some(body)
            }
        }
    }
}

impl ZPathBuf {
    /// Constructs an owned, validated path from an existing string.
    ///
    /// # Errors
    ///
    /// Returns [`PathError`] if the string violates ZooKeeper hierarchical path constraints.
    pub fn new(s: String) -> Result<ZPathBuf, PathError> {
        check(&s, false)?;
        Ok(ZPathBuf(s))
    }

    /// Decodes a server-returned byte payload into a validated path buffer.
    ///
    /// Verifies that the payload is valid UTF-8 and passes the same non-sequential path validation
    /// rules as [`ZPathBuf::new`], ensuring a faulty server cannot produce a `ZPathBuf` that
    /// breaks the type invariant [`PathArg`] relies on when skipping validation for `ZPathBuf`.
    /// The payload is the path after the lower-level protocol layer has stripped the chroot.
    ///
    /// # Errors
    ///
    /// Returns [`Error::MarshallingError`] if the payload is not valid UTF-8 or not a valid path.
    pub(crate) fn from_server(bytes: Vec<u8>) -> Result<ZPathBuf, Error> {
        let s = String::from_utf8(bytes).map_err(|_| Error::MarshallingError)?;
        check(&s, false).map_err(|_| Error::MarshallingError)?;
        Ok(ZPathBuf(s))
    }

    /// Borrows this path buffer as an immutable [`ZPath`] reference.
    #[must_use]
    pub fn as_path(&self) -> ZPath<'_> {
        ZPath(&self.0)
    }

    /// Returns a string slice view of this path buffer.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Consumes the wrapper, returning the underlying heap-allocated [`String`].
    #[must_use]
    pub fn into_string(self) -> String {
        self.0
    }
}

impl AsRef<str> for ZPathBuf {
    fn as_ref(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ZPathBuf {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::fmt::Display for ZPath<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

impl PartialEq<str> for ZPathBuf {
    fn eq(&self, other: &str) -> bool {
        self.0 == other
    }
}

impl PartialEq<&str> for ZPathBuf {
    fn eq(&self, other: &&str) -> bool {
        self.0 == *other
    }
}

mod sealed {
    use super::PathError;

    /// Private trait sealing [`super::PathArg`] to prevent downstream implementation.
    pub trait Sealed {
        /// Validates and extracts the path string slice according to sequential node rules.
        ///
        /// When `sequential` is true, the validation check accommodates a trailing slash.
        /// validated path types ([`super::ZPath`], [`super::ZPathBuf`]) bypass re-validation.
        fn checked(&self, sequential: bool) -> Result<&str, PathError>;
    }
}

/// Polymorphic abstraction over input types convertible to a validated ZooKeeper path.
///
/// Sealed and implemented for standard string types (`&str`, `String`, `&String`) as well as
/// validated domain types ([`ZPath`], [`ZPathBuf`]). String arguments undergo strict client
/// side validation at request submission time. Any syntax or semantic violation immediately
/// yields an `Err(Error::InvalidPath(..))` without generating network traffic.
pub trait PathArg: sealed::Sealed {
    /// Converts the argument into a validated [`ZPath`] reference.
    ///
    /// # Errors
    ///
    /// Returns [`PathError`] if the underlying string does not satisfy ZooKeeper path constraints.
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError>;
}

/// Internal helper evaluating path validation for client operation submissions.
pub(crate) fn checked(p: &impl PathArg, sequential: bool) -> Result<&str, PathError> {
    sealed::Sealed::checked(p, sequential)
}

impl sealed::Sealed for &str {
    fn checked(&self, sequential: bool) -> Result<&str, PathError> {
        check(self, sequential).map(|()| *self)
    }
}
impl PathArg for &str {
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError> {
        ZPath::new(self)
    }
}

impl sealed::Sealed for String {
    fn checked(&self, sequential: bool) -> Result<&str, PathError> {
        check(self, sequential).map(|()| self.as_str())
    }
}
impl PathArg for String {
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError> {
        ZPath::new(self)
    }
}

impl sealed::Sealed for &String {
    fn checked(&self, sequential: bool) -> Result<&str, PathError> {
        check(self, sequential).map(|()| self.as_str())
    }
}
impl PathArg for &String {
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError> {
        ZPath::new(self)
    }
}

impl sealed::Sealed for ZPath<'_> {
    fn checked(&self, _sequential: bool) -> Result<&str, PathError> {
        Ok(self.0)
    }
}
impl PathArg for ZPath<'_> {
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError> {
        Ok(*self)
    }
}

impl sealed::Sealed for ZPathBuf {
    fn checked(&self, _sequential: bool) -> Result<&str, PathError> {
        Ok(&self.0)
    }
}
impl PathArg for ZPathBuf {
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError> {
        Ok(self.as_path())
    }
}

impl sealed::Sealed for &ZPathBuf {
    fn checked(&self, _sequential: bool) -> Result<&str, PathError> {
        Ok(&self.0)
    }
}
impl PathArg for &ZPathBuf {
    fn to_zpath(&self) -> Result<ZPath<'_>, PathError> {
        Ok(self.as_path())
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]

    use super::*;

    #[test]
    fn sequential_prefix_may_end_with_slash() {
        assert!(checked(&"/jobs/", true).is_ok());
        assert!(checked(&"/jobs/", false).is_err());
    }

    #[test]
    fn server_paths_are_validated() {
        assert!(ZPathBuf::from_server(b"/a".to_vec()).is_ok());
        assert!(ZPathBuf::from_server(b"/".to_vec()).is_ok());
        for bad in [
            vec![0x2f, 0xff],
            b"a".to_vec(),
            b"/a/".to_vec(),
            b"/a//b".to_vec(),
            b"/a/../b".to_vec(),
            b"/a\0b".to_vec(),
        ] {
            assert_eq!(
                ZPathBuf::from_server(bad.clone()).unwrap_err(),
                Error::MarshallingError,
                "{bad:?}"
            );
        }
    }

    /// Asserts equivalence of acceptance verdicts between the compile-time const validator and the
    /// protocol state machine.
    fn same_verdict(s: &str) {
        assert_eq!(
            check_static(s),
            validate_path_strict(s, false).is_ok(),
            "verdicts differ for {s:?}"
        );
    }

    #[test]
    fn const_check_matches_proto_on_edge_cases() {
        let cases = [
            "",
            "/",
            "//",
            "a",
            "a/b",
            "/a",
            "/a/",
            "/a//b",
            "/.",
            "/..",
            "/...",
            "/.a",
            "/a.",
            "/..a",
            "/a/.",
            "/a/..",
            "/a/./b",
            "/a/../b",
            "/a/.../b",
            "/a\0",
            "/a\u{0}b",
            "/\u{1}",
            "/\u{1f}",
            "/ ",
            "/~",
            "/\u{7f}",
            "/\u{80}",
            "/\u{9f}",
            "/\u{a0}",
            "/\u{e9}t\u{e9}",
            "/\u{d7ff}",
            "/\u{e000}",
            "/\u{f8ff}",
            "/\u{f900}",
            "/\u{ffef}",
            "/\u{fff0}",
            "/\u{ffff}",
            "/\u{10000}",
            "/\u{10ffff}",
            "/zookeeper/config",
            "/a/b/c/d",
        ];
        for c in cases {
            same_verdict(c);
        }
    }

    #[test]
    fn reserved_range_and_nul() {
        for c in [
            '\0',
            '\u{1}',
            '\u{7f}',
            '\u{9f}',
            '\u{e000}',
            '\u{fff0}',
            '\u{10000}',
        ] {
            let s = format!("/a{c}");
            let e = ZPath::new(&s).unwrap_err();
            assert_eq!(e.index, 2, "{s:?}");
            assert!(!check_static(&s));
        }
        assert!(ZPath::new("/a\u{a0}").is_ok());
        assert!(ZPath::new("/a\u{f900}").is_ok());
    }

    proptest::proptest! {
        #![proptest_config(crate::test_config::proptest())]
        #[test]
        fn const_check_matches_proto(s in "\\PC{0,12}") {
            same_verdict(&s);
        }

        #[test]
        fn const_check_matches_proto_on_path_like_text(
            s in "(/[a.\u{0}\u{1f}\u{7f}\u{9f}\u{e000}\u{fff0}\u{1f600}é/]{0,4}){0,4}"
        ) {
            same_verdict(&s);
            let _ = ZPath::new(&s);
        }

        #[test]
        fn new_never_panics(s in proptest::prelude::any::<String>()) {
            same_verdict(&s);
            if let Ok(p) = ZPath::new(&s) {
                let _ = p.parent();
                let _ = p.name();
                assert_eq!(p.components().count(), s.matches('/').count() - usize::from(p.is_root()));
            }
        }
    }
}
