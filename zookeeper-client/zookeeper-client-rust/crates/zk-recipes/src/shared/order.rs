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

//! Curator-compatible child node ordering and predecessor resolution.
//!
//! This module implements the sorting semantics defined by Apache Curator's
//! `StandardLockInternalsDriver.fixForSorting`. For each candidate child znode name,
//! the sorting key is extracted as the substring following the final occurrence of a
//! recipe specific delimiter marker (such as `lock-` or `latch-`). If the marker is not
//! present in the name, the full znode name is retained as the sort key.
//!
//! # Sequence Evaluation and Lexicographical Parity
//!
//! In ZooKeeper sequential znodes, the server appends a 10-digit zero-padded integer
//! (`0000000000` through `2147483647`). Because the numeric suffix is fixed width,
//! standard lexicographical sorting over extracted keys precisely reproduces creation order.
//! Furthermore, child lists are intentionally not filtered, ensuring that Java and Rust
//! clients reach identical ordering decisions even if unexpected extraneous znodes are
//! introduced into the directory.
//!
//! Java's `String.compareTo` sorts strings according to raw UTF-16 code unit values.
//! To achieve bit-for-bit parity, particularly across supplementary Unicode code points
//! that encode as surrogate pairs, this implementation explicitly encodes keys to UTF-16
//! code unit sequences before performing comparisons.

use std::cmp::Ordering;

/// Delimiter identifying participant sequence suffixes in `InterProcessMutex`
/// (`InterProcessMutex.LOCK_NAME`).
pub(crate) const LOCK_MARKER: &str = "lock-";

/// Delimiter identifying participant sequence suffixes in `LeaderLatch` (`LeaderLatch.LOCK_NAME`).
pub(crate) const LATCH_MARKER: &str = "latch-";

/// Extracts the sort key from a child znode name by taking the suffix following the final
/// occurrence of `marker`.
///
/// If `marker` is not found, returns the entire name unaltered.
pub(crate) fn sort_key<'a>(name: &'a str, marker: &str) -> &'a str {
    name.rsplit_once(marker).map_or(name, |(_, tail)| tail)
}

/// Compares two child znode names according to Apache Curator's UTF-16 lexicographical sorting
/// rules.
pub(crate) fn compare(a: &str, b: &str, marker: &str) -> Ordering {
    sort_key(a, marker)
        .encode_utf16()
        .cmp(sort_key(b, marker).encode_utf16())
}

/// Sorts a slice of child znode names in place using stable Curator-compatible ordering.
pub(crate) fn sort_children(children: &mut [String], marker: &str) {
    children.sort_by(|a, b| compare(a, b, marker));
}

/// Locates the index of `own` within a sorted list of child znode names, returning `None` if
/// absent.
pub(crate) fn position(sorted: &[String], own: &str) -> Option<usize> {
    sorted.iter().position(|c| c == own)
}

/// Returns the child znode immediately preceding `own` in the sorted sequence, or `None` if `own`
/// is first or absent.
pub(crate) fn predecessor<'a>(sorted: &'a [String], own: &str) -> Option<&'a str> {
    let i = position(sorted, own)?;
    let before = i.checked_sub(1)?;
    sorted.get(before).map(String::as_str)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn key_is_text_after_last_marker() {
        assert_eq!(sort_key("_c_x-lock-0000000007", LOCK_MARKER), "0000000007");
        // While standard UUID tokens contain no delimiter, arbitrary external nodes might contain
        // multiple markers.
        assert_eq!(
            sort_key("lock-a-lock-0000000001", LOCK_MARKER),
            "0000000001"
        );
        assert_eq!(sort_key("foreign", LOCK_MARKER), "foreign");
        assert_eq!(sort_key("x-lock-", LOCK_MARKER), "");
    }

    #[test]
    fn order_is_creation_order_across_uuids() {
        let mut c = names(&[
            "_c_ffffffff-0000-4000-8000-000000000000-lock-0000000003",
            "_c_00000000-0000-4000-8000-000000000000-lock-0000000010",
            "_c_88888888-0000-4000-8000-000000000000-lock-0000000001",
        ]);
        sort_children(&mut c, LOCK_MARKER);
        assert!(c[0].ends_with("0000000001"));
        assert!(c[1].ends_with("0000000003"));
        assert!(c[2].ends_with("0000000010"));
    }

    #[test]
    fn foreign_children_take_part() {
        // Extraneous nodes retain their full names, sorting according to standard ASCII/Unicode
        // rules.
        let mut c = names(&["zzz", "a-latch-0000000002", "000", "b-latch-0000000001"]);
        sort_children(&mut c, LATCH_MARKER);
        assert_eq!(
            c,
            names(&["000", "b-latch-0000000001", "a-latch-0000000002", "zzz"])
        );
    }

    #[test]
    fn utf16_order_as_java() {
        // Demonstrates UTF-16 code unit sorting parity with Java for supplementary Unicode code
        // points.
        let mut c = names(&["\u{FF61}", "\u{10000}"]);
        sort_children(&mut c, LOCK_MARKER);
        assert_eq!(c, names(&["\u{10000}", "\u{FF61}"]));
    }

    #[test]
    fn predecessor_and_position() {
        let c = names(&["a-lock-1", "b-lock-2", "c-lock-3"]);
        assert_eq!(position(&c, "b-lock-2"), Some(1));
        assert_eq!(predecessor(&c, "a-lock-1"), None);
        assert_eq!(predecessor(&c, "c-lock-3"), Some("b-lock-2"));
        assert_eq!(predecessor(&c, "missing"), None);
    }
}
