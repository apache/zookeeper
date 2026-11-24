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

//! ZooKeeper znode path validation routines.
//!
//! Provides dual-mode path validation implementations:
//! - Legacy C client path validation matching historical `isValidPath` behavior in `zookeeper.c`.
//! - Strict Java path validation conforming to `org.apache.zookeeper.common.PathUtils.validatePath`
//!   as enforced by modern native drivers and ZooKeeper server ensembles.

use std::fmt;

/// ZooKeeper sequential persistent node creation mode (`ZOO_PERSISTENT_SEQUENTIAL`).
pub const PERSISTENT_SEQUENTIAL: i32 = 2;
/// ZooKeeper sequential ephemeral node creation mode (`ZOO_EPHEMERAL_SEQUENTIAL`).
pub const EPHEMERAL_SEQUENTIAL: i32 = 3;
/// ZooKeeper persistent node with container/TTL lifetime (`ZOO_PERSISTENT_WITH_TTL`).
pub const PERSISTENT_WITH_TTL: i32 = 5;
/// ZooKeeper sequential persistent node with container/TTL lifetime
/// (`ZOO_PERSISTENT_SEQUENTIAL_WITH_TTL`).
pub const PERSISTENT_SEQUENTIAL_WITH_TTL: i32 = 6;

/// Evaluates whether the given create mode flag represents a sequential node creation request
/// (`ZOOKEEPER_IS_SEQUENCE`).
///
/// In accordance with C client conventions, this evaluates explicit equality against known
/// sequential mode constants rather than bitmask inspection.
pub fn is_sequence(mode: i32) -> bool {
    matches!(
        mode,
        PERSISTENT_SEQUENTIAL | EPHEMERAL_SEQUENTIAL | PERSISTENT_SEQUENTIAL_WITH_TTL
    )
}

/// Evaluates whether the given create mode flag carries an associated Time-To-Live (TTL) duration
/// (`ZOOKEEPER_IS_TTL`).
pub fn is_ttl(mode: i32) -> bool {
    matches!(mode, PERSISTENT_WITH_TTL | PERSISTENT_SEQUENTIAL_WITH_TTL)
}

/// Validates a znode path using the legacy C client algorithm (`isValidPath` in `zookeeper.c`).
///
/// Operates on the raw byte slice of a C string, excluding trailing null termination.
/// To preserve memory safety and avoid accepting bytes diverging from user intent, any
/// embedded null byte immediately renders the path invalid.
///
/// Structural rules enforced include:
/// - Path must not be empty and must begin with an absolute root slash (`/`).
/// - Trailing slashes are permitted strictly when creating sequential nodes, where the ensemble
///   appends a sequence suffix.
/// - Redundant empty components (consecutive slashes `//`) are prohibited.
/// - Relative directory components (`.` or `..`) are rejected, except as final components in
///   sequential mode.
/// - Control characters in the range `0x01..=0x1E` are rejected, while `0x1F`, `0x7F`, and
///   high-bit bytes (`0x80..=0xFF`) are accepted across both signed and unsigned `char` targets.
pub fn is_valid_path(path: &[u8], mode: i32) -> bool {
    let seq = is_sequence(mode);
    let len = path.len();
    // Simulate C null-terminated string indexing beyond buffer boundaries.
    let at = |i: Option<usize>| i.and_then(|i| path.get(i)).copied().unwrap_or(0);
    if path.first() != Some(&b'/') {
        return false;
    }
    if len == 1 {
        return true;
    }
    if path.last() == Some(&b'/') && !seq {
        return false;
    }
    let mut last = b'/';
    for (i, &c) in path.iter().enumerate().skip(1) {
        let at_end = i.checked_add(1) == Some(len);
        let next_is_slash = at(i.checked_add(1)) == b'/';
        if c == 0 || (c == b'/' && last == b'/') {
            return false;
        } else if c == b'.' && last == b'.' {
            if at(i.checked_sub(2)) == b'/' && ((at_end && !seq) || next_is_slash) {
                return false;
            }
        } else if c == b'.' {
            if at(i.checked_sub(1)) == b'/' && ((at_end && !seq) || next_is_slash) {
                return false;
            }
        } else if (0x01..0x1f).contains(&c) {
            return false;
        }
        last = c;
    }
    true
}

/// Specific classification of a strict path validation error.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum PathErrorKind {
    /// Path string contains zero characters.
    Empty,
    /// Path does not begin with the required leading root slash (`/`).
    NotAbsolute,
    /// Path terminates with a trailing slash on a non-sequential node creation.
    TrailingSlash,
    /// Path contains a forbidden null (`\0`) character.
    NulCharacter,
    /// Path contains consecutive slashes resulting in an empty component name.
    EmptyComponent,
    /// Path contains relative navigation segments (`.` or `..`).
    RelativeComponent,
    /// Path contains prohibited control characters, Unicode surrogates, or private-use code points.
    InvalidCharacter,
}

/// Detailed error describing a strict path validation failure, formatting messages identical to
/// Java `PathUtils`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PathError {
    /// Categorical reason for path rejection.
    pub kind: PathErrorKind,
    /// Zero-based character index within the path where the violation was detected.
    pub index: Option<usize>,
}

impl fmt::Display for PathError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let i = self.index.unwrap_or(0);
        match self.kind {
            PathErrorKind::Empty => f.write_str("Path length must be > 0"),
            PathErrorKind::NotAbsolute => f.write_str("Path must start with / character"),
            PathErrorKind::TrailingSlash => f.write_str("Path must not end with / character"),
            PathErrorKind::NulCharacter => write!(f, "null character not allowed @{i}"),
            PathErrorKind::EmptyComponent => write!(f, "empty node name specified @{i}"),
            PathErrorKind::RelativeComponent => write!(f, "relative paths not allowed @{i}"),
            PathErrorKind::InvalidCharacter => write!(f, "invalid character @{i}"),
        }
    }
}

impl std::error::Error for PathError {}

/// Determines whether a UTF-16 code unit falls into the forbidden ranges defined by Java
/// `PathUtils`.
fn java_invalid_unit(c: u16) -> bool {
    (0x0001..=0x001f).contains(&c)
        || (0x007f..=0x009f).contains(&c)
        || (0xd800..=0xf8ff).contains(&c)
        || c >= 0xfff0
}

/// UTF-16 code unit representing ASCII forward slash (`/`).
const SLASH: u16 = 0x002f;
/// UTF-16 code unit representing ASCII period (`.`).
const DOT: u16 = 0x002e;

/// Validates an individual UTF-16 code unit situated beyond the initial root slash.
///
/// Evaluates segment boundary rules and character constraints against the preceding two units
/// (`before`, `last`) and upcoming peeked unit (`next`), returning an error condition if violated.
fn strict_unit_error(
    c: u16,
    before: Option<u16>,
    last: u16,
    next: Option<u16>,
) -> Option<PathErrorKind> {
    let ends_component = matches!(next, None | Some(SLASH));
    if c == 0 {
        Some(PathErrorKind::NulCharacter)
    } else if c == SLASH && last == SLASH {
        Some(PathErrorKind::EmptyComponent)
    } else if c == DOT && last == DOT {
        (before == Some(SLASH) && ends_component).then_some(PathErrorKind::RelativeComponent)
    } else if c == DOT {
        (last == SLASH && ends_component).then_some(PathErrorKind::RelativeComponent)
    } else if java_invalid_unit(c) {
        Some(PathErrorKind::InvalidCharacter)
    } else {
        None
    }
}

/// Validates a znode path conforming strictly to the canonical Java client rules
/// (`PathUtils.validatePath`).
///
/// In sequential node creation mode, validation applies conceptually to `path + "1"` to verify
/// that appending the sequential counter produces a well-formed absolute path, allowing trailing
/// slashes. Java's validator inspects 16-bit UTF-16 code units; hence Unicode surrogate pairs
/// (representing code points above U+FFFF) and private use areas (`U+E000..=U+F8FF`) are rejected.
pub fn validate_path_strict(path: &str, sequential: bool) -> Result<(), PathError> {
    // Process UTF-16 code units as a zero-allocation streaming iterator with one-unit lookahead.
    let tail = sequential.then_some(u16::from(b'1'));
    let mut units = path.encode_utf16().chain(tail).peekable();
    let err = |kind, index| Err(PathError { kind, index });
    match units.next() {
        None => return err(PathErrorKind::Empty, None),
        Some(SLASH) => {}
        Some(_) => return err(PathErrorKind::NotAbsolute, None),
    }
    if units.peek().is_none() {
        return Ok(());
    }
    // Non-sequential paths are prohibited from terminating with a trailing slash.
    if !sequential && path.ends_with('/') {
        return err(PathErrorKind::TrailingSlash, None);
    }
    let mut before = None;
    let mut last = SLASH;
    let mut i: usize = 1;
    while let Some(c) = units.next() {
        if let Some(kind) = strict_unit_error(c, before, last, units.peek().copied()) {
            return err(kind, Some(i));
        }
        before = Some(last);
        last = c;
        i = i.saturating_add(1);
    }
    Ok(())
}
