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

//! Curator-compatible protected-mode node naming and UUID generation.
//!
//! When creating znodes in protected mode, Apache Curator prepends the unique token
//! `_c_<uuid>-` to the child name template. In the event of a transient `ConnectionLoss`
//! during creation, the client cannot know whether the request reached the ensemble and
//! committed before the connection severed. By embedding a collision resistant UUID into the
//! znode name, the client can safely inspect parent children upon reconnect and unambiguously
//! identify whether its creation request succeeded.
//!
//! # Format and Random Entropy
//!
//! The UUID conforms to RFC 4122 Version 4 in standard canonical lowercase 8-4-4-4-12 string
//! representation, matching `java.util.UUID.randomUUID().toString()`.
//!
//! Because these identifiers require collision resistance rather than cryptographic secrecy,
//! pseudorandom bits are derived from system-seeded [`RandomState`] hash keys combined with
//! monotonic process-local sequence counters and high resolution timestamps. This avoids
//! pulling in external dependencies while preserving complete protocol compatibility.

use std::collections::hash_map::RandomState;
use std::fmt::Write as _;
use std::hash::BuildHasher;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Protected mode znode prefix defined by Apache Curator (`CreateBuilderImpl.PROTECTED_PREFIX`).
pub(crate) const PROTECTED_PREFIX: &str = "_c_";

/// Separator delimiter following the protected UUID (`CreateBuilderImpl.PROTECTED_SEPARATOR`).
pub(crate) const PROTECTED_SEPARATOR: &str = "-";

/// Bitmask isolating the 4-bit UUID version field within the 128-bit representation.
const VERSION_MASK: u128 = 0x0000_0000_0000_F000_0000_0000_0000_0000;
/// RFC 4122 Version 4 (random) bit pattern.
const VERSION_4: u128 = 0x0000_0000_0000_4000_0000_0000_0000_0000;
/// Bitmask isolating the 2-bit UUID variant field within the 128-bit representation.
const VARIANT_MASK: u128 = 0x0000_0000_0000_0000_C000_0000_0000_0000;
/// RFC 4122 variant bit pattern (binary `10`).
const VARIANT_RFC4122: u128 = 0x0000_0000_0000_0000_8000_0000_0000_0000;

/// Monotonically increasing atomic sequence counter ensuring entropy uniqueness across iterations.
static MADE: AtomicU64 = AtomicU64::new(0);

/// Unique protected mode identifier encapsulating a canonical RFC 4122 Version 4 UUID.
///
/// Retained across creation retry attempts to correlate ambiguous server responses with the
/// originating request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ProtectedId {
    uuid: String,
}

impl ProtectedId {
    /// Generates a new random protected identifier.
    pub(crate) fn new() -> Self {
        Self::from_bits(random_u128())
    }

    /// Constructs a protected identifier from raw 128-bit integer entropy, configuring Version 4
    /// and RFC 4122 variant bits.
    pub(crate) fn from_bits(bits: u128) -> Self {
        let v = (bits & !VERSION_MASK & !VARIANT_MASK) | VERSION_4 | VARIANT_RFC4122;
        let mut uuid = String::with_capacity(36);
        for (i, b) in v.to_be_bytes().iter().enumerate() {
            if matches!(i, 4 | 6 | 8 | 10) {
                uuid.push('-');
            }
            // A write to a String cannot fail.
            let _ = write!(uuid, "{b:02x}");
        }
        Self { uuid }
    }

    /// Returns the canonical string representation of the UUID.
    #[cfg(test)]
    pub(crate) fn uuid(&self) -> &str {
        &self.uuid
    }

    /// Formats the complete protected-mode prefix token (`_c_<uuid>-`).
    pub(crate) fn prefix(&self) -> String {
        format!("{PROTECTED_PREFIX}{}{PROTECTED_SEPARATOR}", self.uuid)
    }

    /// Constructs the target node prefix sent in creation requests, appending the recipe marker
    /// to the protected token.
    pub(crate) fn node_name(&self, marker: &str) -> String {
        format!("{}{marker}", self.prefix())
    }

    /// Scans a slice of child znode names, returning the first entry matching this protected
    /// identifier.
    pub(crate) fn find_in<'a>(&self, children: &'a [String]) -> Option<&'a str> {
        children.iter().map(String::as_str).find(|c| self.owns(c))
    }

    /// Evaluates whether the given znode name carries this instance's protected-mode prefix.
    pub(crate) fn owns(&self, name: &str) -> bool {
        name.strip_prefix(PROTECTED_PREFIX)
            .and_then(|rest| rest.strip_prefix(self.uuid.as_str()))
            .is_some_and(|rest| rest.starts_with(PROTECTED_SEPARATOR))
    }
}

/// Validates whether a string matches canonical lowercase RFC 4122 Version 4 UUID formatting.
#[cfg(test)]
pub(crate) fn is_canonical_v4(s: &str) -> bool {
    let groups: Vec<&str> = s.split('-').collect();
    let lens_ok = groups.iter().map(|g| g.len()).eq([8, 4, 4, 4, 12]);
    let hex_ok = s
        .chars()
        .all(|c| c == '-' || c.is_ascii_digit() || ('a'..='f').contains(&c));
    let version_ok = groups.get(2).is_some_and(|g| g.starts_with('4'));
    let variant_ok = groups
        .get(3)
        .and_then(|g| g.chars().next())
        .is_some_and(|c| matches!(c, '8' | '9' | 'a' | 'b'));
    lens_ok && hex_ok && version_ok && variant_ok
}

/// Generates 128 pseudo-random bits by hashing process counter state and timestamps using
/// OS-seeded hashers.
fn random_u128() -> u128 {
    let n = MADE.fetch_add(1, Ordering::Relaxed);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_nanos());
    let hi = RandomState::new().hash_one((n, nanos, 0x5A4B_u16));
    let lo = RandomState::new().hash_one((n, nanos, 0xB4A5_u16));
    u128::from(hi).wrapping_shl(64) | u128::from(lo)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_is_canonical_v4() {
        for _ in 0..1000 {
            let id = ProtectedId::new();
            assert!(is_canonical_v4(id.uuid()), "{}", id.uuid());
        }
    }

    #[test]
    fn uuid_sets_version_and_variant_bits() {
        assert_eq!(
            ProtectedId::from_bits(0).uuid(),
            "00000000-0000-4000-8000-000000000000"
        );
        assert_eq!(
            ProtectedId::from_bits(u128::MAX).uuid(),
            "ffffffff-ffff-4fff-bfff-ffffffffffff"
        );
    }

    #[test]
    fn uuids_differ() {
        let a: std::collections::BTreeSet<String> = (0..1000)
            .map(|_| ProtectedId::new().uuid().to_owned())
            .collect();
        assert_eq!(a.len(), 1000);
    }

    #[test]
    fn names_follow_curator() {
        let id = ProtectedId::from_bits(0);
        assert_eq!(id.prefix(), "_c_00000000-0000-4000-8000-000000000000-");
        assert_eq!(
            id.node_name("lock-"),
            "_c_00000000-0000-4000-8000-000000000000-lock-"
        );
        let children = vec![
            "_c_11111111-0000-4000-8000-000000000000-lock-0000000001".to_owned(),
            "_c_00000000-0000-4000-8000-000000000000-lock-0000000002".to_owned(),
        ];
        assert_eq!(id.find_in(&children), children.get(1).map(String::as_str));
        assert_eq!(id.find_in(&children[..1]), None);
    }

    #[test]
    fn canonical_check_rejects_other_forms() {
        assert!(!is_canonical_v4("00000000-0000-1000-8000-000000000000"));
        assert!(!is_canonical_v4("00000000-0000-4000-7000-000000000000"));
        assert!(!is_canonical_v4("00000000-0000-4000-8000-00000000000A"));
        assert!(!is_canonical_v4("000000000000-4000-8000-000000000000"));
    }
}
