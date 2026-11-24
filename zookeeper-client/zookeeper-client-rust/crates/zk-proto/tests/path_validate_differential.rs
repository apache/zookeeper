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

//! Differential verification of strict UTF-16 znode path validation.
//!
//! Cross-checks the streaming UTF-16 state machine against a reference implementation that
//! collects UTF-16 code units into a vector and inspects them by index. Both implementations must
//! agree on acceptance, error classification, offending code unit index, and diagnostic messages
//! across all inputs.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::legacy::path::{PathError, PathErrorKind, validate_path_strict};

/// Returns `true` if the UTF-16 code unit `c` is prohibited by the Java `PathUtils.validatePath`
/// character range rules.
fn reference_invalid_unit(c: u16) -> bool {
    (0x0001..=0x001f).contains(&c)
        || (0x007f..=0x009f).contains(&c)
        || (0xd800..=0xf8ff).contains(&c)
        || c >= 0xfff0
}

/// Materializing reference implementation of Java `PathUtils.validatePath` retained as a
/// differential oracle.
fn reference(path: &str, sequential: bool) -> Result<(), PathError> {
    let mut units: Vec<u16> = path.encode_utf16().collect();
    if sequential {
        units.push(u16::from(b'1'));
    }
    let err = |kind, index| Err(PathError { kind, index });
    let slash = u16::from(b'/');
    let dot = u16::from(b'.');
    let Some(&first) = units.first() else {
        return err(PathErrorKind::Empty, None);
    };
    if first != slash {
        return err(PathErrorKind::NotAbsolute, None);
    }
    let len = units.len();
    if len == 1 {
        return Ok(());
    }
    if units.last() == Some(&slash) {
        return err(PathErrorKind::TrailingSlash, None);
    }
    let at = |i: Option<usize>| i.and_then(|i| units.get(i)).copied();
    let mut last = slash;
    for (i, &c) in units.iter().enumerate().skip(1) {
        let at_end = i.checked_add(1) == Some(len);
        let next_is_slash = at(i.checked_add(1)) == Some(slash);
        if c == 0 {
            return err(PathErrorKind::NulCharacter, Some(i));
        } else if c == slash && last == slash {
            return err(PathErrorKind::EmptyComponent, Some(i));
        } else if c == dot && last == dot {
            if at(i.checked_sub(2)) == Some(slash) && (at_end || next_is_slash) {
                return err(PathErrorKind::RelativeComponent, Some(i));
            }
        } else if c == dot {
            if at(i.checked_sub(1)) == Some(slash) && (at_end || next_is_slash) {
                return err(PathErrorKind::RelativeComponent, Some(i));
            }
        } else if reference_invalid_unit(c) {
            return err(PathErrorKind::InvalidCharacter, Some(i));
        }
        last = c;
    }
    Ok(())
}

/// Asserts behavioral equivalence between `validate_path_strict` and [`reference`] across both
/// standard and sequential creation modes, returning the number of evaluated test cases.
fn compare(path: &str) -> usize {
    for sequential in [false, true] {
        let got = validate_path_strict(path, sequential);
        let want = reference(path, sequential);
        assert_eq!(got, want, "path {path:?} sequential {sequential}");
        assert_eq!(
            got.map_err(|e| e.to_string()),
            want.map_err(|e| e.to_string()),
            "message of {path:?} sequential {sequential}"
        );
    }
    2
}

/// Exhaustively evaluates all strings over `alphabet` up to length `max_len`, testing each
/// candidate both bare and prefixed with a leading `/` so paths traverse the per-unit validation
/// loop.
fn exhaustive(alphabet: &[char], max_len: u32) -> usize {
    let mut cases = 0;
    let mut s = String::new();
    for len in 0..=max_len {
        let total = alphabet.len().pow(len);
        for mut n in 0..total {
            s.clear();
            for _ in 0..len {
                s.push(alphabet[n % alphabet.len()]);
                n /= alphabet.len();
            }
            cases += compare(&s);
            s.insert(0, '/');
            cases += compare(&s);
        }
    }
    cases
}

/// Representative character set spanning Unicode range boundaries and structural path delimiters.
const WIDE: &[char] = &[
    '/',
    '.',
    'a',
    '1',
    '\0',
    '\u{1}',
    '\u{1f}',
    '\u{20}',
    '\u{7e}',
    '\u{7f}',
    '\u{9f}',
    '\u{a0}',
    '\u{e9}',
    '\u{d7ff}',
    '\u{e000}',
    '\u{f8ff}',
    '\u{f900}',
    '\u{ffef}',
    '\u{fff0}',
    '\u{ffff}',
    '\u{10000}',
    '\u{10ffff}',
];

/// Focused character alphabet targeting relative component, NUL, and supplementary-plane rules.
const NARROW: &[char] = &['/', '.', 'a', '\0', '\u{10000}'];

#[test]
fn short_paths_match_the_reference() {
    // Assert exact case counts to guard against unintended alphabet or length-bound regressions.
    // Under Miri, string lengths are reduced to accommodate interpreter execution overhead.
    if cfg!(miri) {
        let cases = exhaustive(WIDE, 2) + exhaustive(NARROW, 4);
        assert_eq!(cases, 5_152);
    } else {
        let cases = exhaustive(WIDE, 4) + exhaustive(NARROW, 7);
        assert_eq!(cases, 1_372_268);
    }
}

/// Deterministic xorshift64 pseudorandom number generator for reproducible fuzzing.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

#[test]
fn long_random_paths_match_the_reference() {
    let mut rng = Rng(0x9e37_79b9_7f4a_7c15);
    let mut cases = 0;
    let mut s = String::new();
    // Scale iteration count under Miri to account for interpreter overhead.
    let rounds = if cfg!(miri) { 200 } else { 20_000 };
    for round in 0..rounds {
        s.clear();
        if round % 8 != 0 {
            s.push('/');
        }
        let len = 1 + rng.below(300);
        for _ in 0..len {
            // Bias sampling toward valid ASCII characters so deeper path suffixes are exercised
            // before encountering a disqualifying code unit.
            let c = match rng.below(16) {
                0..=7 => 'a',
                8..=10 => '/',
                11 | 12 => '.',
                _ => WIDE[rng.below(WIDE.len())],
            };
            s.push(c);
        }
        cases += compare(&s);
        // Collapse adjacent slashes to increase coverage of valid multi-segment paths.
        let cleaned = s.replace("//", "/");
        cases += compare(&cleaned);
    }
    assert_eq!(cases, 4 * rounds);
}
