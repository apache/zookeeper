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

//! Cross-language binary serialization conformance tests against Java Jute fixtures.
//!
//! Reference fixture corpora are emitted by the Java Jute serializer (`RustFixtures` in
//! `zookeeper-jute`). Every serialized record entry must deserialize without trailing bytes and
//! encode to an identical byte sequence.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

#[macro_use]
mod common;

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use zk_jute::{Reader, Record};

fn fixture_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
}

/// Partitions a binary fixture file into its constituent length-prefixed record payloads.
fn entries(bytes: &[u8]) -> Vec<&[u8]> {
    let mut out = Vec::new();
    let mut r = Reader::new(bytes);
    while !r.is_empty() {
        let len = usize::try_from(r.read_i32().unwrap()).unwrap();
        out.push(r.read_bytes(len).unwrap());
    }
    out
}

/// Verifies round trip deserialization, encoded length calculation, and byte-exact reencoding
/// across all entries in a fixture file, returning the total entry count verified.
fn check<T: Record + core::fmt::Debug + PartialEq>(file: &str) -> usize {
    let path = fixture_dir().join(file);
    let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    let list = entries(&bytes);
    assert!(!list.is_empty(), "{file} has no entries");
    for (i, entry) in list.iter().enumerate() {
        let mut r = Reader::new(entry);
        let rec = T::decode(&mut r).unwrap_or_else(|e| panic!("{file}[{i}]: {e}"));
        assert!(r.is_empty(), "{file}[{i}]: {} bytes left", r.remaining());
        assert_eq!(rec.encoded_len(), entry.len(), "{file}[{i}]");
        assert_eq!(rec.to_bytes(), *entry, "{file}[{i}]");
        assert_eq!(T::from_bytes(&rec.to_bytes()).unwrap(), rec, "{file}[{i}]");
    }
    list.len()
}

#[test]
#[cfg_attr(miri, ignore = "Miri isolation blocks the fixture file reads")]
fn java_fixtures_round_trip() {
    let mut names = BTreeSet::new();
    macro_rules! one {
        ($module:ident, $name:ident) => {{
            let file = concat!(stringify!($module), ".", stringify!($name), ".bin");
            assert_eq!(check::<zk_jute::$module::$name>(file), 24, "{file}");
            names.insert(file.to_owned());
        }};
    }
    for_each_record!(one);

    // Ensure strict bijection between checked in binary fixture files and tested Jute record types.
    let present: BTreeSet<String> = std::fs::read_dir(fixture_dir())
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .filter(|n| n.ends_with(".bin"))
        .collect();
    assert_eq!(present, names);
}

#[test]
#[cfg_attr(miri, ignore = "Miri isolation blocks the fixture file reads")]
fn fixtures_hold_nulls_and_multibyte_text() {
    let bytes = std::fs::read(fixture_dir().join("data.Id.bin")).unwrap();
    let list = entries(&bytes);
    let first = zk_jute::data::Id::from_bytes(list[0]).unwrap();
    assert!(first.scheme.is_null() && first.id.is_null());
    let second = zk_jute::data::Id::from_bytes(list[1]).unwrap();
    assert_eq!(second.scheme.as_bytes(), Some(&b""[..]));
    let multibyte = list
        .iter()
        .map(|e| zk_jute::data::Id::from_bytes(e).unwrap())
        .filter_map(|id| id.id.as_str().map(str::to_owned))
        .any(|s| s.chars().any(|c| c.len_utf8() > 1));
    assert!(multibyte);
}
