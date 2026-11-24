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

//! Conformance and behavioral parity tests for the legacy C algorithm ports in
//! [`zk_proto::legacy`].

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::legacy::{
    chroot::{JoinError, join_legacy},
    connect::{parse_init, parse_servers},
    hashtable::{LegacyHashTable, djb2},
    path::is_valid_path,
};

#[test]
fn embedded_nul_policy() {
    // C-string value consumers truncate at the first embedded NUL byte.
    assert_eq!(
        parse_servers(b"a:1\0,b").unwrap(),
        parse_servers(b"a:1").unwrap()
    );
    assert_eq!(
        parse_init(b"a:1/x\0/..").unwrap().chroot,
        Some(b"/x".to_vec())
    );
    assert_eq!(djb2(b"/a\0b", true), djb2(b"/a", true));
    let mut t = LegacyHashTable::new(true);
    t.insert(b"/a\0b", 1_u32);
    assert_eq!(t.get(b"/a"), Some(&1));
    // Path validation routines explicitly reject slices containing embedded NUL bytes.
    assert!(!is_valid_path(b"/a\0", 0));
    assert!(!is_valid_path(b"/a\0/..", 0));
    assert_eq!(join_legacy(None, b"/a\0b", 0), Err(JoinError::InvalidPath));
    assert_eq!(
        join_legacy(Some(b"/app"), b"/a\0b", 0),
        Err(JoinError::InvalidPath)
    );
}

/// Address vector cursor traversal, list equivalence predicates, and Fisher-Yates permutation.
mod addrvec {
    use std::net::SocketAddr;

    use zk_proto::legacy::addrvec::{
        AddrEq, AddrVec, ListEq, bidirectional_eq, legacy_eq, lists_equal, shuffle,
    };

    fn sa(s: &str) -> SocketAddr {
        s.parse().unwrap()
    }

    #[test]
    fn cursor_wraps() {
        let mut v = AddrVec::from(vec![sa("10.0.0.1:1"), sa("10.0.0.2:1")]);
        assert!(v.has_next());
        assert!(!v.at_end());
        assert_eq!(v.next_item(), Some(&sa("10.0.0.1:1")));
        assert_eq!(v.peek(), Some(&sa("10.0.0.2:1")));
        assert_eq!(v.next_item(), Some(&sa("10.0.0.2:1")));
        assert!(v.at_end());
        assert_eq!(v.peek(), Some(&sa("10.0.0.1:1")));
        assert_eq!(v.next_item(), Some(&sa("10.0.0.1:1")));
        let mut e: AddrVec<SocketAddr> = AddrVec::new();
        assert_eq!(e.next_item(), None);
        assert_eq!(e.peek(), None);
        assert!(!e.at_end());
    }

    #[test]
    fn equality_rules() {
        let a = sa("10.0.0.1:2181");
        let a2 = sa("10.0.0.1:2182");
        let b = sa("10.0.0.2:2181");
        assert!(legacy_eq(&[a, a], &[a, b], AddrEq::IpOnly));
        assert!(!legacy_eq(&[a, b], &[a, a], AddrEq::IpOnly));
        assert!(!bidirectional_eq(&[a, a], &[a, b], AddrEq::IpAndPort));
        assert!(legacy_eq(&[a], &[a2], AddrEq::IpOnly));
        assert!(!legacy_eq(&[a], &[a2], AddrEq::IpAndPort));
        assert!(lists_equal(
            &[a, b],
            &[b, a],
            ListEq::Bidirectional,
            AddrEq::IpAndPort
        ));
    }

    #[test]
    fn shuffle_matches_fisher_yates() {
        let mut v = vec![0, 1, 2, 3];
        let mut draws = vec![0_i64, 0, 0].into_iter();
        shuffle(&mut v, || draws.next().unwrap_or(0));
        // Trace of swaps for zero draws: i=3 swaps(3,0) -> [3,1,2,0]; i=2 swaps(2,0) -> [2,1,3,0];
        // i=1 swaps(1,0) -> [1,2,3,0].
        assert_eq!(v, vec![1, 2, 3, 0]);
        let mut one = vec![7];
        shuffle(&mut one, || -5);
        assert_eq!(one, vec![7]);
        let mut neg = vec![0, 1];
        shuffle(&mut neg, || -1);
        assert_eq!(neg, vec![0, 1]);
    }
}

/// Client chroot prefix composition and response path stripping rules.
mod chroot {
    use zk_proto::legacy::chroot::{
        CONFIG_NODE, JoinError, join_component, join_legacy, prepend_c, strip, strip_c,
        strip_event, strip_str,
    };

    fn p(c: &[u8]) -> Vec<u8> {
        c.to_vec()
    }

    #[test]
    fn c_reference() {
        assert_eq!(p(&prepend_c(Some(b"/app"), b"foo")), b"/appfoo".to_vec());
        assert_eq!(p(&prepend_c(Some(b"/app"), b"x")), b"/app".to_vec());
        assert_eq!(p(&prepend_c(Some(b"/app"), b"")), b"/app".to_vec());
        assert_eq!(p(&prepend_c(None, b"x")), b"x".to_vec());
        let s = strip_c(Some(b"/app"), b"/app2/x");
        assert_eq!(p(&s.path), b"2/x".to_vec());
        assert!(!s.mismatch);
        assert_eq!(p(&strip_c(Some(b"/app"), b"/app").path), b"/".to_vec());
        assert!(strip_c(Some(b"/app"), b"/other").mismatch);
    }

    #[test]
    fn rust_join() {
        assert_eq!(
            join_legacy(Some(b"/app"), b"foo", 0),
            Err(JoinError::NotAbsolute)
        );
        assert_eq!(
            join_legacy(Some(b"/app"), b"", 0),
            Err(JoinError::NotAbsolute)
        );
        assert_eq!(
            join_legacy(Some(b"/app"), b"/", 2),
            Err(JoinError::SequentialOnRoot)
        );
        assert_eq!(
            join_legacy(Some(b"/app"), b"/", 0).map(|c| c.to_vec()),
            Ok(b"/app".to_vec())
        );
        assert_eq!(
            join_legacy(Some(b"/app"), b"/a/", 2).map(|c| c.to_vec()),
            Ok(b"/app/a/".to_vec())
        );
        assert_eq!(
            join_legacy(Some(b"/app"), b"/a//b", 0),
            Err(JoinError::InvalidPath)
        );
        assert_eq!(join_legacy(None, b"foo", 0), Err(JoinError::InvalidPath));
        assert_eq!(
            join_legacy(Some(b"/"), b"/a", 0).map(|c| c.to_vec()),
            Ok(b"/a".to_vec())
        );
        assert_eq!(join_component(Some("/app"), "/"), "/app");
        assert_eq!(join_component(Some("/app"), "/x"), "/app/x");
        assert_eq!(join_component(None, "/x"), "/x");
    }

    #[test]
    fn rust_strip() {
        let s = strip(Some(b"/app"), b"/app2/x");
        assert!(s.mismatch);
        assert_eq!(p(&s.path), b"/app2/x".to_vec());
        assert_eq!(p(&strip(Some(b"/app"), b"/app/x").path), b"/x".to_vec());
        assert_eq!(p(&strip(Some(b"/app"), b"/app").path), b"/".to_vec());
        assert!(!strip(None, b"/x").mismatch);
        assert!(strip_event(Some(b"/app"), CONFIG_NODE, false).mismatch);
        assert!(!strip_event(Some(b"/app"), CONFIG_NODE, true).mismatch);
        assert_eq!(strip_str(Some("/app"), "/app/x"), ("/x", false));
        assert_eq!(strip_str(Some("/app"), "/app"), ("/", false));
        assert_eq!(strip_str(Some("/app"), "/apple"), ("/apple", true));
    }
}

/// Legacy C and native Rust connection string parsers.
mod connect {
    use zk_proto::legacy::connect::{
        CLong, EINVAL, HostParseErrorKind, NativeHost, NativeParseError, ZBADARGUMENTS,
        ZSYSTEMERROR, parse_connect_string_native, parse_init, parse_servers, strtol_base0,
    };

    #[test]
    fn strtol_cases() {
        assert_eq!(strtol_base0(b"2181"), (2181, 4));
        assert_eq!(strtol_base0(b"0x1f"), (31, 4));
        assert_eq!(strtol_base0(b"017"), (15, 3));
        assert_eq!(strtol_base0(b"08"), (0, 1));
        assert_eq!(strtol_base0(b"0x"), (0, 1));
        assert_eq!(strtol_base0(b"0xg"), (0, 1));
        assert_eq!(strtol_base0(b" -12z"), (-12, 4));
        assert_eq!(strtol_base0(b"+"), (0, 0));
        assert_eq!(strtol_base0(b""), (0, 0));
        assert_eq!(strtol_base0(b"  "), (0, 0));
        let big = strtol_base0(b"99999999999999999999999");
        assert_eq!(big, (CLong::MAX, 23));
        let small = strtol_base0(b"-99999999999999999999999");
        assert_eq!(small, (CLong::MIN, 24));
    }

    /// The C23 `0b` prefix selects base 2 on glibc targets only.
    #[test]
    fn strtol_binary_prefix() {
        let glibc = cfg!(all(target_os = "linux", target_env = "gnu"));
        let want = |c23: (CLong, usize), c17: (CLong, usize)| if glibc { c23 } else { c17 };
        assert_eq!(strtol_base0(b"0b101"), want((5, 5), (0, 1)));
        assert_eq!(strtol_base0(b"-0B11"), want((-3, 5), (0, 2)));
        assert_eq!(strtol_base0(b"0b2"), (0, 1));
        assert_eq!(strtol_base0(b"0b"), (0, 1));
    }

    #[test]
    fn legacy_chroot_at_first_slash() {
        let c = parse_init(b"h1:2181/app,h2:2181").unwrap();
        assert_eq!(c.host_text, b"h1:2181".to_vec());
        assert_eq!(c.chroot, Some(b"/app,h2:2181".to_vec()));
        assert_eq!(c.hosts.len(), 1);
        let c = parse_init(b"h1:2181/").unwrap();
        assert_eq!(c.chroot, None);
        let e = parse_init(b"h1:2181/app/").unwrap_err();
        assert_eq!(e.kind, HostParseErrorKind::InvalidChroot);
        assert_eq!(e.errno, Some(EINVAL));
        let c = parse_init(b"/app").unwrap();
        assert!(c.hosts.is_empty());
        assert_eq!(parse_init(b"").unwrap_err().kind, HostParseErrorKind::Empty);
    }

    #[test]
    fn legacy_host_list() {
        let h = parse_servers(b" a:1,,::1:2181,[::1]:2182").unwrap();
        assert_eq!(h[0].host, b"a".to_vec());
        assert_eq!(h[1].host, b"::1".to_vec());
        assert_eq!(h[1].port, 2181);
        assert_eq!(h[2].host, b"[::1]".to_vec());
        let e = parse_servers(b"a:1,b,c:2").unwrap_err();
        assert_eq!(e.kind, HostParseErrorKind::NoPort);
        assert_eq!(e.entry, Some(1));
        assert_eq!(e.prefix.len(), 1);
        assert_eq!(e.subject, b"b".to_vec());
        assert_eq!(e.rc, ZBADARGUMENTS);
        let e = parse_servers(b"a:0").unwrap_err();
        assert_eq!(e.kind, HostParseErrorKind::InvalidPort);
        assert_eq!(e.subject, b"a".to_vec());
        assert!(parse_servers(b"a:4294967296").is_err());
        assert_eq!(parse_servers(b"a:-1").unwrap()[0].port, -1);
        assert_eq!(
            parse_servers(b",").unwrap_err().kind,
            HostParseErrorKind::NoHosts
        );
        assert_eq!(parse_servers(b",,").unwrap_err().rc, ZSYSTEMERROR);
        // The legacy C parser sets `errno = EINVAL` on port syntax failures, but leaves `errno`
        // untouched when an input contains no host tokens.
        assert_eq!(parse_servers(b",,").unwrap_err().errno, None);
        assert_eq!(parse_servers(b"a:0").unwrap_err().errno, Some(EINVAL));
        assert_eq!(parse_servers(b"a").unwrap_err().errno, Some(EINVAL));
        assert_eq!(parse_init(b"").unwrap_err().errno, Some(EINVAL));
        assert_eq!(parse_init(b"h:1,,").unwrap().hosts.len(), 1);
        assert_eq!(parse_init(b",/app").unwrap_err().errno, None);
        assert!(parse_servers(b"").unwrap().is_empty());
        assert_eq!(
            parse_servers(b"a:1 ").unwrap_err().kind,
            HostParseErrorKind::InvalidPort
        );
    }

    #[test]
    fn native_syntax() {
        let c = parse_connect_string_native("host1:2181,host2:2181/chroot").unwrap();
        assert_eq!(c.chroot.as_deref(), Some("/chroot"));
        assert_eq!(c.hosts[1].host, "host2");
        let c = parse_connect_string_native(" h1 , [::1]:2182 ,[fe80::1], h3: ").unwrap();
        assert_eq!(
            c.hosts[0],
            NativeHost {
                host: "h1".into(),
                port: 2181
            }
        );
        assert_eq!(
            c.hosts[1],
            NativeHost {
                host: "::1".into(),
                port: 2182
            }
        );
        assert_eq!(c.hosts[2].port, 2181);
        assert_eq!(c.hosts[3].port, 2181);
        assert!(parse_connect_string_native("[::1]:").is_err());
        assert!(parse_connect_string_native("[::1]x").is_err());
        assert!(parse_connect_string_native("[]:1").is_err());
        assert!(parse_connect_string_native("h:70000").is_err());
        assert!(parse_connect_string_native("h:-1").is_err());
        assert!(parse_connect_string_native("h/a/").is_err());
        assert_eq!(parse_connect_string_native("h/").unwrap().chroot, None);
        assert_eq!(
            parse_connect_string_native("  "),
            Err(NativeParseError::Empty)
        );
        assert_eq!(
            parse_connect_string_native("/app"),
            Err(NativeParseError::NoHosts)
        );
    }
}

/// Port of the legacy C prime-sized chained hash table (`zk_hashtable.c`).
mod hashtable {
    use zk_proto::legacy::hashtable::{LegacyHashTable, MAX_MIN_SIZE, TARGET_C_CHAR_SIGNED, djb2};

    #[test]
    fn initial_size_is_53() {
        let t: LegacyHashTable<u32> = LegacyHashTable::new(true);
        assert_eq!(t.bucket_count(), 53);
        assert_eq!(t.load_limit(), 35);
        assert!(LegacyHashTable::<u32>::with_min_size(MAX_MIN_SIZE + 1, true).is_none());
        let t = LegacyHashTable::<u32>::with_min_size(53, false).unwrap();
        assert_eq!(t.bucket_count(), 97);
    }

    #[test]
    fn native_signedness_follows_the_target_abi() {
        // Verify `c_char` signedness against standard platform ABI specifications.
        let expected: Option<bool> =
            if cfg!(target_arch = "x86_64") || cfg!(target_vendor = "apple") {
                Some(true)
            } else if cfg!(all(target_arch = "aarch64", target_os = "linux")) {
                Some(false)
            } else {
                None
            };
        if let Some(signed) = expected {
            assert_eq!(TARGET_C_CHAR_SIGNED, signed);
        }
        let t: LegacyHashTable<u32> = LegacyHashTable::new_native();
        assert_eq!(t.c_char_signed(), TARGET_C_CHAR_SIGNED);
    }

    #[test]
    fn djb2_signedness() {
        assert_eq!(djb2(b"", true), 5381);
        assert_eq!(djb2(b"a", true), 5381 * 33 + 97);
        assert_ne!(djb2(&[0x80], true), djb2(&[0x80], false));
        assert_eq!(djb2(&[0x7f], true), djb2(&[0x7f], false));
        assert_eq!(djb2(b"ab\0cd", true), djb2(b"ab", true));
    }

    #[test]
    fn grows_and_keeps_entries() {
        let mut t = LegacyHashTable::new(true);
        for i in 0..1000_u32 {
            t.insert(format!("/k{i}").as_bytes(), i);
        }
        assert_eq!(t.len(), 1000);
        assert!(t.bucket_count() > 1000);
        for i in 0..1000_u32 {
            assert_eq!(t.get(format!("/k{i}").as_bytes()), Some(&i));
        }
        assert_eq!(t.iter().count(), 1000);
        for i in 0..1000_u32 {
            assert_eq!(t.remove(format!("/k{i}").as_bytes()), Some(i));
        }
        assert!(t.is_empty());
        assert_eq!(t.remove(b"/k1"), None);
    }

    #[test]
    fn collision_prepends() {
        let mut t = LegacyHashTable::new(true);
        // Locate two distinct keys that hash to the same bucket index.
        let a = b"/a".to_vec();
        let target = t.bucket_of(&a);
        let mut other = None;
        for i in 0..10_000_u32 {
            let k = format!("/x{i}").into_bytes();
            if t.bucket_of(&k) == target {
                other = Some(k);
                break;
            }
        }
        let b = other.unwrap();
        t.insert(&a, 1);
        t.insert(&b, 2);
        let keys: Vec<&[u8]> = t.keys().collect();
        assert_eq!(keys, vec![b.as_slice(), a.as_slice()]);
    }
}

/// Legacy C and strict Java-compatible znode path validation rules.
mod path {
    use zk_proto::legacy::path::{PathErrorKind, is_valid_path, validate_path_strict};

    #[test]
    fn legacy_rules() {
        assert!(is_valid_path(b"/", 0));
        assert!(!is_valid_path(b"", 0));
        assert!(!is_valid_path(b"a", 0));
        assert!(!is_valid_path(b"/a/", 0));
        assert!(is_valid_path(b"/a/", 2));
        assert!(!is_valid_path(b"/a//b", 0));
        assert!(!is_valid_path(b"/.", 0));
        assert!(is_valid_path(b"/.", 3));
        assert!(!is_valid_path(b"/./a", 3));
        assert!(!is_valid_path(b"/..", 0));
        assert!(is_valid_path(b"/..", 6));
        assert!(is_valid_path(b"/...", 0));
        assert!(is_valid_path(b"/a.", 0));
        assert!(!is_valid_path(b"/a\x01", 0));
        assert!(!is_valid_path(b"/a\x1e", 0));
        assert!(is_valid_path(b"/a\x1f", 0));
        assert!(is_valid_path(b"/a\x7f", 0));
        assert!(is_valid_path(b"/a\xff", 0));
        assert!(!is_valid_path(b"/a\0", 0));
        // Creation mode 7 is rejected for trailing slashes because legacy C compares creation
        // modes via exact integer equality against sequential variants rather than bitmask tests.
        assert!(!is_valid_path(b"/a/", 7));
    }

    #[test]
    fn strict_rules() {
        assert!(validate_path_strict("/", false).is_ok());
        assert!(validate_path_strict("/a/", true).is_ok());
        assert_eq!(
            validate_path_strict("/a/", false).map_err(|e| e.kind),
            Err(PathErrorKind::TrailingSlash)
        );
        assert_eq!(
            validate_path_strict("/a\u{7f}", false).map_err(|e| e.to_string()),
            Err("invalid character @2".to_owned())
        );
        assert!(validate_path_strict("/a\u{10000}", false).is_err());
        assert!(validate_path_strict("/a\u{e000}", false).is_err());
        assert!(validate_path_strict("/\u{e9}t\u{e9}", false).is_ok());
        assert!(validate_path_strict("/.", true).is_ok());
        assert!(validate_path_strict("/..", false).is_err());
        assert!(validate_path_strict("", false).is_err());
    }
}

/// Dynamic ensemble membership updates and probabilistic load-balancing migration (`ServerSet`).
mod servers {
    use std::net::SocketAddr;

    use zk_proto::legacy::{
        addrvec::{AddrEq, ListEq},
        servers::{ServerSet, UpdateOutcome},
    };

    fn sa(s: &str) -> SocketAddr {
        s.parse().unwrap()
    }

    #[test]
    fn init_puts_all_in_new() {
        let mut s: ServerSet<SocketAddr> = ServerSet::new();
        let out = s.update(
            vec![sa("10.0.0.1:1"), sa("10.0.0.2:1")],
            ListEq::LegacyOneWay,
            AddrEq::IpOnly,
            unreachable_draw,
        );
        assert_eq!(out, UpdateOutcome::Changed { reconfig: true });
        assert_eq!(s.new.len(), 2);
        assert!(s.old.is_empty());
        assert!((s.p_new - 1.0).abs() < f64::EPSILON);
        assert_eq!(s.cycle_next(|| 0.5), Some(&sa("10.0.0.1:1")));
        assert_eq!(s.cycle_next(|| 0.5), Some(&sa("10.0.0.2:1")));
        // Once both `new` and `old` partitions are exhausted, `reconfig` mode clears and rotation
        // falls back to the primary `addrs` vector.
        assert_eq!(s.cycle_next(|| 0.5), Some(&sa("10.0.0.1:1")));
        assert!(!s.reconfig);
    }

    fn unreachable_draw() -> f64 {
        2.0
    }

    #[test]
    fn same_size_not_found_gives_nan_for_zero_over_zero() {
        let mut s: ServerSet<SocketAddr> = ServerSet::new();
        s.update(
            vec![sa("10.0.0.1:1"), sa("10.0.0.2:1")],
            ListEq::LegacyOneWay,
            AddrEq::IpOnly,
            unreachable_draw,
        );
        // Updating from `{A, B}` to `{A, A}` fails one-way equivalence because `B` is absent, yet
        // both entries in the new list match `A` in the old set (`num_old == 2`, `num_new == 0`),
        // producing `0.0 / 0.0` (`NaN`) probability thresholds as in C.
        let out = s.update(
            vec![sa("10.0.0.1:1"), sa("10.0.0.1:1")],
            ListEq::LegacyOneWay,
            AddrEq::IpOnly,
            unreachable_draw,
        );
        assert_eq!(out, UpdateOutcome::Changed { reconfig: true });
        assert!(s.p_old.is_nan());
        assert!(s.p_new.is_nan());
    }

    #[test]
    fn port_only_change() {
        let mut s: ServerSet<SocketAddr> = ServerSet::new();
        s.update(
            vec![sa("10.0.0.1:1")],
            ListEq::Bidirectional,
            AddrEq::IpAndPort,
            unreachable_draw,
        );
        let legacy = s.clone().update(
            vec![sa("10.0.0.1:2")],
            ListEq::LegacyOneWay,
            AddrEq::IpOnly,
            unreachable_draw,
        );
        assert_eq!(legacy, UpdateOutcome::Unchanged);
        let fixed = s.update(
            vec![sa("10.0.0.1:2")],
            ListEq::Bidirectional,
            AddrEq::IpAndPort,
            unreachable_draw,
        );
        assert_eq!(fixed, UpdateOutcome::Changed { reconfig: true });
    }

    #[test]
    fn growth_with_current_draws_once() {
        let mut s: ServerSet<SocketAddr> = ServerSet::new();
        s.update(
            vec![sa("10.0.0.1:1")],
            ListEq::LegacyOneWay,
            AddrEq::IpOnly,
            unreachable_draw,
        );
        s.cycle_next(|| 0.0);
        let mut draws = 0;
        let out = s.update(
            vec![sa("10.0.0.1:1"), sa("10.0.0.2:1")],
            ListEq::LegacyOneWay,
            AddrEq::IpOnly,
            || {
                draws += 1;
                0.9
            },
        );
        assert_eq!(draws, 1);
        // Because `0.9 > 1.0 - 0.5`, the client retains its current ensemble connection.
        assert_eq!(out, UpdateOutcome::Changed { reconfig: false });
    }
}

/// Legacy watcher hash tables and notification fan-out ordering (`zk_hashtable.c`).
mod watchers {
    use zk_proto::legacy::watchers::{
        CHANGED_EVENT, DELETED_EVENT, LegacyWatchers, WATCH_TYPE_ANY, WATCH_TYPE_DATA, fan_out,
    };

    #[test]
    fn fan_out_dedups_and_reverses() {
        assert_eq!(fan_out([1, 2, 1, 3, 2]), vec![3, 2, 1]);
        assert_eq!(fan_out(Vec::<u8>::new()), Vec::<u8>::new());
    }

    /// Verifies that znode events collect and deduplicate watchers in canonical C table order.
    #[test]
    fn node_event_order() {
        let mut w = LegacyWatchers::new(true);
        // Register data watchers `1` and `2`, followed by existence watcher `3`.
        assert!(w.data.insert(b"/a", 1));
        assert!(w.data.insert(b"/a", 2));
        assert!(!w.data.insert(b"/a", 2));
        assert!(w.exist.insert(b"/a", 3));
        // Existence watchers are prepended ahead of data watchers in the intermediate list,
        // yielding `[3, 1, 2]` after reverse deduplication.
        assert_eq!(w.collect_node_event(CHANGED_EVENT, b"/a"), vec![3, 1, 2]);
        assert!(w.data.is_empty());
        assert!(w.exist.is_empty());
    }

    #[test]
    fn deleted_order_and_unknown_type() {
        let mut w = LegacyWatchers::new(false);
        w.data.insert(b"/a", 1);
        w.exist.insert(b"/a", 2);
        w.child.insert(b"/a", 3);
        assert!(w.collect_node_event(99, b"/a").is_empty());
        assert_eq!(w.collect_node_event(DELETED_EVENT, b"/a"), vec![3, 2, 1]);
    }

    #[test]
    fn session_default_last_and_dedup() {
        let mut w = LegacyWatchers::new(true);
        w.data.insert(b"/a", 1);
        w.child.insert(b"/b", 0);
        let got = w.collect_session_event(&0);
        assert_eq!(got.last(), Some(&0));
        assert_eq!(got, vec![1, 0]);
        // Session event fan-out preserves all registered entries in the underlying watch tables.
        assert_eq!(w.data.len(), 1);
    }

    #[test]
    fn remove_and_has_watcher() {
        let mut w = LegacyWatchers::new(true);
        w.data.insert(b"/a", 1);
        w.data.insert(b"/a", 2);
        assert!(w.has_watcher(b"/a", WATCH_TYPE_DATA, Some(&1)));
        assert!(w.has_watcher(b"/none", WATCH_TYPE_DATA, None));
        assert!(!w.has_watcher(b"/a", 7, None));
        w.remove_watchers(b"/a", WATCH_TYPE_DATA, Some(&1));
        assert!(!w.has_watcher(b"/a", WATCH_TYPE_ANY, Some(&1)));
        w.remove_watchers(b"/a", WATCH_TYPE_DATA, Some(&2));
        assert!(w.data.is_empty());
        w.child.insert(b"/c", 5);
        w.remove_watchers(b"/c", WATCH_TYPE_ANY, None);
        assert!(w.child.is_empty());
    }
}
