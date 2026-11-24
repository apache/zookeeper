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

//! Differential tests for address vector operations and ensemble server list rebalancing.
//!
//! Cross-validates `zk_proto::legacy::addrvec` and `zk_proto::legacy::servers` against
//! `addrvec.c` and verbatim extracts of `resolve_hosts`, `update_addrs`,
//! `get_next_server_in_reconfig`, and `zoo_cycle_next_server` from `zookeeper.c`, driving
//! both implementations with identical `random()` and `drand48()` sequences.

#![cfg(zkx_c)]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    missing_docs
)]
#![allow(clippy::disallowed_macros)]

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::str::from_utf8;

use zk_proto::legacy::addrvec::{AddrEq, AddrVec, ListEq, legacy_eq, shuffle};
use zk_proto::legacy::connect::parse_servers;
use zk_proto::legacy::servers::{ServerSet, UpdateOutcome};
use zk_xcheck::rng::SplitMix64;
use zk_xcheck::{CApi, with_c};

/// Returns a generator closure that yields elements from `vals` in sequence, defaulting to
/// `T::default()` once exhausted, while incrementing `calls` on each invocation.
fn feeder<'a, T: Copy + Default>(vals: &'a [T], calls: &'a mut usize) -> impl FnMut() -> T + 'a {
    let mut i = 0;
    move || {
        *calls += 1;
        let v = vals.get(i).copied().unwrap_or_default();
        i += 1;
        v
    }
}

fn v4(ip: u32, port: u16) -> SocketAddr {
    SocketAddr::new(IpAddr::V4(Ipv4Addr::from(ip)), port)
}

#[test]
fn shuffle_matches_c() {
    let mut rng = SplitMix64::new(0x5f1e);
    with_c(|c| {
        for round in 0..3000 {
            let n = rng.index(14);
            let feed: Vec<i64> = (0..n + 2).map(|_| rng.c_random()).collect();
            c.av_clear();
            for i in 0..n {
                c.av_append(i as u32, 0);
            }
            c.set_random_feed(&feed);
            c.av_shuffle();
            let got: Vec<u32> = c.av_items().iter().map(|x| x.0).collect();
            let c_calls = c.random_calls();
            let mut want: Vec<u32> = (0..n as u32).collect();
            let mut calls = 0;
            shuffle(&mut want, feeder(&feed, &mut calls));
            assert_eq!(got, want, "round {round} n {n}");
            assert_eq!(c_calls, calls, "draw count, round {round}");
        }
    });
}

#[test]
fn cursor_matches_c() {
    let mut rng = SplitMix64::new(0xc0c0);
    with_c(|c| {
        for round in 0..300 {
            c.av_clear();
            let mut r: AddrVec<(u32, u16)> = AddrVec::new();
            for step in 0..60 {
                let ctx = format!("round {round} step {step}");
                match rng.below(8) {
                    0 | 1 => {
                        let item = (rng.below(5) as u32, rng.below(3) as u16);
                        c.av_append(item.0, item.1);
                        r.push(item);
                    }
                    2..=4 => assert_eq!(c.av_next(), r.next_item().copied(), "next: {ctx}"),
                    5 => assert_eq!(c.av_peek(), r.peek().copied(), "peek: {ctx}"),
                    6 => {
                        assert_eq!(c.av_has_next(), r.has_next(), "hasnext: {ctx}");
                        assert_eq!(c.av_at_end(), r.at_end(), "atend: {ctx}");
                    }
                    _ => {
                        if rng.chance(1, 4) {
                            c.av_clear();
                            r.clear();
                        }
                    }
                }
                assert_eq!(c.av_cursor() as usize, r.cursor(), "cursor: {ctx}");
                assert_eq!(c.av_items(), r.as_slice().to_vec(), "items: {ctx}");
            }
        }
    });
}

#[test]
fn list_equality_matches_c() {
    let mut rng = SplitMix64::new(0xe9);
    with_c(|c| {
        for round in 0..20_000 {
            let list = |rng: &mut SplitMix64| -> Vec<(u32, u16)> {
                let n = rng.index(5);
                (0..n)
                    .map(|_| (rng.below(4) as u32, rng.below(3) as u16))
                    .collect()
            };
            let a = list(&mut rng);
            let b = if rng.chance(1, 3) {
                let mut b = a.clone();
                b.reverse();
                b
            } else {
                list(&mut rng)
            };
            let ra: Vec<SocketAddr> = a.iter().map(|&(ip, p)| v4(ip, p)).collect();
            let rb: Vec<SocketAddr> = b.iter().map(|&(ip, p)| v4(ip, p)).collect();
            assert_eq!(
                c.av_eq(&a, &b),
                legacy_eq(&ra, &rb, AddrEq::IpOnly),
                "round {round}: {a:?} {b:?}"
            );
        }
    });
}

fn same_f64(a: f64, b: f64) -> bool {
    a.to_bits() == b.to_bits() || (a.is_nan() && b.is_nan())
}

struct Model {
    set: ServerSet<SocketAddr>,
    connected: bool,
}

fn compare_state(c: &CApi, m: &Model, ctx: &str) {
    let (addrs, addrs_next) = c.u_vec(0);
    let (old, old_next) = c.u_vec(1);
    let (new, new_next) = c.u_vec(2);
    assert_eq!(addrs, m.set.addrs.as_slice().to_vec(), "addrs: {ctx}");
    assert_eq!(
        addrs_next as usize,
        m.set.addrs.cursor(),
        "addrs cursor: {ctx}"
    );
    assert_eq!(old, m.set.old.as_slice().to_vec(), "addrs_old: {ctx}");
    assert_eq!(
        old_next as usize,
        m.set.old.cursor(),
        "addrs_old cursor: {ctx}"
    );
    assert_eq!(new, m.set.new.as_slice().to_vec(), "addrs_new: {ctx}");
    assert_eq!(
        new_next as usize,
        m.set.new.cursor(),
        "addrs_new cursor: {ctx}"
    );
    assert_eq!(c.u_reconfig(), m.set.reconfig, "reconfig: {ctx}");
    assert!(same_f64(c.u_p_old(), m.set.p_old), "pOld: {ctx}");
    assert!(same_f64(c.u_p_new(), m.set.p_new), "pNew: {ctx}");
    assert_eq!(c.u_cur(), m.set.current, "addr_cur: {ctx}");
    assert_eq!(c.u_connected(), m.connected, "socket: {ctx}");
}

/// Address family distribution for synthesized host lists.
#[derive(Clone, Copy, Debug)]
enum Family {
    V4,
    V6,
    /// Combines IPv4, IPv6, and IPv4-mapped IPv6 literals. In the C client, mapped addresses
    /// carry `AF_INET6` and therefore never compare equal to their `AF_INET` counterparts.
    Mixed,
}

fn random_ip(rng: &mut SplitMix64, family: Family) -> String {
    let k = 1 + rng.below(7);
    let v6 = |rng: &mut SplitMix64| {
        if rng.chance(1, 8) {
            "::1".to_owned()
        } else {
            format!("fd00::{k}")
        }
    };
    match family {
        Family::V4 => format!("10.0.0.{k}"),
        Family::V6 => v6(rng),
        Family::Mixed => match rng.below(3) {
            0 => format!("10.0.0.{k}"),
            1 => v6(rng),
            _ => format!("::ffff:10.0.0.{}", 1 + k % 3),
        },
    }
}

/// Converts parsed `HostSpec` entries into socket addresses. Legacy connection string syntax
/// splits the port at the last `:`, allowing unbracketed IPv6 literals.
fn to_addrs(specs: &[zk_proto::legacy::connect::HostSpec]) -> Vec<SocketAddr> {
    specs
        .iter()
        .map(|h| {
            let ip: IpAddr = from_utf8(&h.host).unwrap().parse().unwrap();
            SocketAddr::new(ip, h.port as u16)
        })
        .collect()
}

fn random_hosts_of(rng: &mut SplitMix64, family: Family) -> String {
    let n = 1 + rng.index(6);
    let mut parts: Vec<String> = (0..n)
        .map(|_| {
            let port = if rng.chance(1, 5) { 2182 } else { 2181 };
            format!("{}:{port}", random_ip(rng, family))
        })
        .collect();
    if rng.chance(1, 25) {
        // Inject an invalid port specification to verify that `update_addrs` aborts without
        // mutating handle state.
        parts.push("10.0.0.9:0".to_owned());
    }
    parts.join(",")
}

fn run_servers(c: &CApi, seed: u64, family: Family) -> (usize, usize) {
    let mut rng = SplitMix64::new(seed);
    c.u_reset();
    let mut m = Model {
        set: ServerSet::new(),
        connected: false,
    };
    let mut changes = 0;
    let mut nan_seen = 0;
    for step in 0..120 {
        let ctx = format!("seed {seed:#x} step {step}");
        match rng.below(6) {
            0 | 1 => {
                let hosts = random_hosts_of(&mut rng, family);
                let permute = rng.chance(1, 2);
                let rfeed: Vec<i64> = (0..8).map(|_| rng.c_random()).collect();
                let mut dfeed: Vec<f64> = (0..2).map(|_| rng.unit_f64()).collect();
                if rng.chance(1, 8) {
                    // Sample exact probability boundary values to exercise C `<=` comparisons.
                    dfeed[0] = *rng
                        .pick(&[0.0, 0.5, 1.0 / 3.0, 2.0 / 3.0, 0.25, 0.75])
                        .unwrap();
                }
                c.set_random_feed(&rfeed);
                c.set_drand_feed(&dfeed);
                let close_before = c.close_count();
                let rc = c.u_update(hosts.as_bytes(), permute);
                let c_closed = c.close_count() - close_before;

                let mut rcalls = 0;
                let mut dcalls = 0;
                let rust_rc = match parse_servers(hosts.as_bytes()) {
                    Err(e) => e.rc,
                    Ok(specs) => {
                        let mut list = to_addrs(&specs);
                        if permute {
                            shuffle(&mut list, feeder(&rfeed, &mut rcalls));
                        }
                        let out = m.set.update(
                            list,
                            ListEq::LegacyOneWay,
                            AddrEq::IpOnly,
                            feeder(&dfeed, &mut dcalls),
                        );
                        if let UpdateOutcome::Changed { reconfig } = out {
                            changes += 1;
                            if m.set.p_old.is_nan() {
                                nan_seen += 1;
                            }
                            if reconfig && m.connected {
                                m.connected = false;
                                assert_eq!(c_closed, 1, "close: {ctx}");
                                assert_eq!(c.u_state(), 999, "state: {ctx}");
                            }
                        }
                        0
                    }
                };
                assert_eq!(rc, rust_rc, "rc: {ctx} hosts {hosts}");
                if rc == 0 {
                    assert_eq!(c.random_calls(), rcalls, "random() draws: {ctx}");
                }
                assert_eq!(c.drand_calls(), dcalls, "drand48() draws: {ctx}");
            }
            2..=4 => {
                let dfeed: Vec<f64> = (0..1).map(|_| rng.unit_f64()).collect();
                c.set_drand_feed(&dfeed);
                c.u_cycle();
                let mut dcalls = 0;
                m.set.cycle_next(feeder(&dfeed, &mut dcalls));
                assert_eq!(c.drand_calls(), dcalls, "drand48() draws in cycle: {ctx}");
            }
            _ => {
                let connected = rng.chance(1, 2);
                c.u_set_connected(connected);
                m.connected = connected;
            }
        }
        compare_state(c, &m, &ctx);
    }
    (changes, nan_seen)
}

#[test]
fn rebalance_matches_c() {
    with_c(|c| {
        let mut changes = 0;
        let mut nans = 0;
        for seed in 0..300_u64 {
            let (ch, nan) = run_servers(c, 0x1355_0000 + seed, Family::V4);
            changes += ch;
            nans += nan;
        }
        assert!(changes > 1000, "too few list changes: {changes}");
        // Verify that the degenerate `0.0 / 0.0` `pOld` division path (`NaN`) is exercised.
        assert!(nans > 0, "the NaN case of pOld did not occur");
    });
}

#[test]
fn rebalance_matches_c_for_ipv6_and_mixed_families() {
    with_c(|c| {
        for (base, family) in [(0x1366_0000_u64, Family::V6), (0x1377_0000, Family::Mixed)] {
            let mut changes = 0;
            let mut nans = 0;
            for seed in 0..200_u64 {
                let (ch, nan) = run_servers(c, base + seed, family);
                changes += ch;
                nans += nan;
            }
            assert!(changes > 500, "{family:?}: too few list changes: {changes}");
            // Because the larger candidate pool in `Family::Mixed` rarely triggers disjoint
            // `0.0 / 0.0` overlap, require `NaN` coverage specifically on `Family::V6`.
            if matches!(family, Family::V6) {
                assert!(nans > 0, "{family:?}: the NaN case of pOld did not occur");
            }
        }
    });
}

#[test]
fn resolve_shuffle_matches_c() {
    let mut rng = SplitMix64::new(0x7e5);
    with_c(|c| {
        for round in 0..3000 {
            let family = match round % 3 {
                0 => Family::V4,
                1 => Family::V6,
                _ => Family::Mixed,
            };
            let hosts = random_hosts_of(&mut rng, family);
            let feed: Vec<i64> = (0..8).map(|_| rng.c_random()).collect();
            c.set_random_feed(&feed);
            let before = c.setup_random_count();
            let (rc, got) = c.resolve(hosts.as_bytes(), true);
            let Ok(specs) = parse_servers(hosts.as_bytes()) else {
                assert_ne!(rc, 0, "round {round}");
                continue;
            };
            assert_eq!(rc, 0, "round {round}");
            assert_eq!(
                c.setup_random_count(),
                before + 1,
                "setup_random(): round {round}"
            );
            let mut want = to_addrs(&specs);
            let mut calls = 0;
            shuffle(&mut want, feeder(&feed, &mut calls));
            assert_eq!(got, want, "round {round}: {hosts}");
            assert_eq!(c.random_calls(), calls, "round {round}");
        }
    });
}
