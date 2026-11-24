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

//! Differential tests for znode path validation, chroot path transformations, and connection
//! string host parsing.
//!
//! Cross-validates `zk_proto::legacy::path`, `zk_proto::legacy::chroot`, and
//! `zk_proto::legacy::connect` against verbatim extracts of `isValidPath`, `prepend_string`,
//! `sub_string`, and `resolve_hosts` from `zookeeper.c` alongside libc `strtol`.

#![cfg(zkx_c)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::useless_conversion,
    missing_docs
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::legacy::chroot::{prepend_c, strip_c};
use zk_proto::legacy::connect::{long_to_int, parse_servers, strtol_base0};
use zk_proto::legacy::hashtable::c_str;
use zk_proto::legacy::path::{is_sequence, is_valid_path};
use zk_xcheck::rng::SplitMix64;
use zk_xcheck::{CApi, with_c};

const MODES: [i32; 10] = [-1, 0, 1, 2, 3, 4, 5, 6, 7, 8];

fn check_path(c: &CApi, s: &[u8], mode: i32) {
    let want = c.is_valid_path(s, mode);
    let got = is_valid_path(c_str(s), mode);
    assert_eq!(got, want, "isValidPath({s:?}, {mode})");
    if c_str(s).len() != s.len() {
        // C strings terminate at the first NUL byte; Rust slice validation rejects embedded NULs.
        assert!(!is_valid_path(s, mode), "embedded NUL accepted: {s:?}");
    }
}

#[test]
fn is_valid_path_all_strings_up_to_two_bytes() {
    with_c(|c| {
        for &mode in &MODES {
            check_path(c, b"", mode);
            for a in 0..=255_u8 {
                check_path(c, &[a], mode);
                for b in 0..=255_u8 {
                    check_path(c, &[a, b], mode);
                }
            }
        }
    });
}

#[test]
fn is_valid_path_three_bytes() {
    with_c(|c| {
        // Exhaustively test all 3-byte strings prefixed with '/' across all creation modes;
        // inputs without a leading '/' are rejected immediately by both implementations.
        for &mode in &MODES {
            for b in 0..=255_u8 {
                for d in 0..=255_u8 {
                    check_path(c, &[b'/', b, d], mode);
                }
            }
        }
        let mut rng = SplitMix64::new(0x3b17e);
        for _ in 0..200_000 {
            let s = [
                rng.below(256) as u8,
                rng.below(256) as u8,
                rng.below(256) as u8,
            ];
            check_path(c, &s, *rng.pick(&MODES).unwrap());
        }
    });
}

#[test]
fn is_valid_path_longer_samples() {
    const ALPHABET: &[u8] = b"//..a\x01\x1e\x1f\x7f\x80\xff";
    let mut rng = SplitMix64::new(0x10_96);
    with_c(|c| {
        for _ in 0..300_000 {
            let len = 1 + rng.index(12);
            let mut s: Vec<u8> = (0..len).map(|_| *rng.pick(ALPHABET).unwrap()).collect();
            if rng.chance(3, 4) {
                s[0] = b'/';
            }
            check_path(c, &s, *rng.pick(&MODES).unwrap());
        }
        for mode in -10..20 {
            assert_eq!(c.is_sequence(mode), is_sequence(mode), "mode {mode}");
        }
    });
}

/// Returns true if `s` starts with optional white space, an optional sign, and a `0b` or `0B`
/// prefix with a binary digit after it.
fn binary_prefixed(s: &[u8]) -> bool {
    let rest = s.trim_ascii_start();
    let rest = rest
        .strip_prefix(b"+")
        .or_else(|| rest.strip_prefix(b"-"))
        .unwrap_or(rest);
    matches!(rest, [b'0', b'b' | b'B', b'0' | b'1', ..])
}

/// Compares the drop-in `strtol` with the host libc.
///
/// The drop-in reads a `0b` prefix as binary on every glibc target, as glibc 2.38 and later do.
/// An older host libc reads it as 0. When the two rules differ, the test skips the inputs with a
/// binary prefix, because the specification documents that difference.
#[test]
fn strtol_matches_libc() {
    const ALPHABET: &[u8] = b"0123456789xXabcdefBgzZ +-\t\n";
    let mut rng = SplitMix64::new(0x57a7);
    with_c(|c| {
        let skip_binary = (strtol_base0(b"0b1").0 == 1) != (c.strtol0(b"0b1").0 == 1);
        let skip = |s: &[u8]| skip_binary && binary_prefixed(s);
        for _ in 0..200_000 {
            let len = rng.index(26);
            let s: Vec<u8> = (0..len).map(|_| *rng.pick(ALPHABET).unwrap()).collect();
            if skip(&s) {
                continue;
            }
            let (cv, cu) = c.strtol0(&s);
            let (rv, ru) = strtol_base0(&s);
            assert_eq!(
                (i64::from(rv), ru),
                (cv, cu),
                "strtol({:?})",
                String::from_utf8_lossy(&s)
            );
        }
        for s in [
            &b"9223372036854775807"[..],
            b"9223372036854775808",
            b"-9223372036854775808",
            b"-9223372036854775809",
            b"0x7fffffffffffffff",
            b"0x8000000000000000",
            b"-0x8000000000000000",
            b"0777777777777777777777",
            b"01777777777777777777777",
            b"0x",
            b"-0x",
            b"0X1G",
            b"0b",
            b"0B1",
            b"0b2",
            b"-0b1",
            b" 0b1",
            b"0b1x",
        ] {
            if skip(s) {
                continue;
            }
            let (cv, cu) = c.strtol0(s);
            let (rv, ru) = strtol_base0(s);
            assert_eq!(
                (i64::from(rv), ru),
                (cv, cu),
                "strtol({:?})",
                String::from_utf8_lossy(s)
            );
        }
        for _ in 0..100_000 {
            let v = rng.next_u64() as i64;
            assert_eq!(long_to_int(v), c.long_to_int(v), "int conversion of {v}");
        }
    });
}

fn check_hosts(c: &CApi, s: &[u8]) {
    c.gai_clear();
    c.log_clear();
    let (rc, addrs) = c.resolve(s, false);
    let calls = c.gai_calls();
    let logs = c.logs();
    let ctx = String::from_utf8_lossy(s).into_owned();
    match parse_servers(s) {
        Ok(hosts) => {
            assert_eq!(rc, 0, "rc for {ctx:?}");
            let want: Vec<(Vec<u8>, Vec<u8>)> = hosts
                .iter()
                .map(|h| (h.host.clone(), h.service.clone()))
                .collect();
            assert_eq!(calls, want, "resolver calls for {ctx:?}");
            assert_eq!(addrs.len(), hosts.len(), "address count for {ctx:?}");
            assert!(logs.is_empty(), "logs for {ctx:?}: {logs:?}");
        }
        Err(e) => {
            assert_eq!(rc, e.rc, "rc for {ctx:?}");
            let want: Vec<(Vec<u8>, Vec<u8>)> = e
                .prefix
                .iter()
                .map(|h| (h.host.clone(), h.service.clone()))
                .collect();
            assert_eq!(calls, want, "resolver calls for {ctx:?}");
            assert!(addrs.is_empty(), "addresses after error for {ctx:?}");
            match e.log_format() {
                Some(fmt) => {
                    assert_eq!(logs.len(), 1, "logs for {ctx:?}: {logs:?}");
                    assert_eq!(logs[0].level, "ERROR");
                    assert_eq!(logs[0].fmt, fmt, "log format for {ctx:?}");
                    let mut msg = fmt.replace("%s", "").into_bytes();
                    let at = fmt.find("%s").unwrap();
                    msg.splice(at..at, e.subject.iter().copied());
                    assert_eq!(logs[0].msg, msg, "log line for {ctx:?}");
                }
                None => assert!(logs.is_empty(), "logs for {ctx:?}: {logs:?}"),
            }
        }
    }
}

#[test]
fn host_list_matches_resolve_hosts() {
    const ALPHABET: &[u8] = b"a1209x:,, :[].-+\t\xa0\x85";
    let fixed: &[&[u8]] = &[
        b"",
        b",",
        b",,,",
        b"h:2181",
        b" h:2181,  k:1",
        b"h",
        b"h:",
        b"h:0",
        b"h:0x10",
        b"h:010",
        b"h:08",
        b"h:2181 ",
        b"h :2181",
        b"h:-1",
        b"h:4294967296",
        b"h:4294967297",
        b"h:99999999999999999999",
        b"::1:2181",
        b"[::1]:2181",
        b"[::1]",
        b"a:1,b,c:2",
        b"a:1,b:x,c:2",
        b"\t:1",
        b"h:+5",
        b"h: 5",
    ];
    with_c(|c| {
        for s in fixed {
            check_hosts(c, s);
        }
        let mut rng = SplitMix64::new(0x4057);
        for _ in 0..100_000 {
            let len = rng.index(20);
            let s: Vec<u8> = (0..len).map(|_| *rng.pick(ALPHABET).unwrap()).collect();
            check_hosts(c, &s);
        }
    });
}

#[test]
fn chroot_join_and_strip_match_c() {
    const ALPHABET: &[u8] = b"//ap2x";
    let mut rng = SplitMix64::new(0xc4_7007);
    let word = |rng: &mut SplitMix64, max: usize| -> Vec<u8> {
        let len = rng.index(max + 1);
        (0..len).map(|_| *rng.pick(ALPHABET).unwrap()).collect()
    };
    with_c(|c| {
        for _ in 0..50_000 {
            let chroot = if rng.chance(1, 5) {
                None
            } else {
                Some(word(&mut rng, 5))
            };
            let path = word(&mut rng, 7);
            let ctx = format!("chroot {chroot:?} path {path:?}");
            assert_eq!(
                c.prepend(chroot.as_deref(), &path),
                prepend_c(chroot.as_deref(), &path).into_owned(),
                "prepend_string: {ctx}"
            );
            c.log_clear();
            let got = c.sub_string(chroot.as_deref(), &path);
            let want = strip_c(chroot.as_deref(), &path);
            assert_eq!(got, want.path.clone().into_owned(), "sub_string: {ctx}");
            let logs = c.logs();
            assert_eq!(
                logs.len(),
                usize::from(want.mismatch),
                "mismatch log: {ctx}"
            );
            if let Some(l) = logs.first() {
                assert_eq!(l.fmt, "server path %s does not include chroot path %s");
            }
        }
    });
}

#[test]
fn extract_manifest_is_complete() {
    let names: Vec<&str> = zk_xcheck::manifest::ZOOKEEPER_C_EXTRACTS
        .iter()
        .map(|e| e.0)
        .collect();
    assert_eq!(
        names,
        [
            "create mode constants",
            "ZOOKEEPER_IS_SEQUENCE",
            "getaddrinfo_errno",
            "count_hosts",
            "resolve_hosts",
            "update_addrs",
            "get_next_server_in_reconfig",
            "zoo_cycle_next_server",
            "free_duplicate_path",
            "prepend_string",
            "sub_string",
            "isValidPath",
        ]
    );
    let mut last = 0;
    for &(name, first, end) in zk_xcheck::manifest::ZOOKEEPER_C_EXTRACTS {
        assert!(
            first > last && end >= first,
            "extract {name} is out of order"
        );
        last = end;
    }
    let (first, end) = zk_xcheck::manifest::DJB2_EXTRACT;
    assert!(end > first);
}
