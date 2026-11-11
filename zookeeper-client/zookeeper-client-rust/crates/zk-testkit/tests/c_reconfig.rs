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

//! Parity tests for the legacy `TestReconfig.cc` (`Zookeeper_reconfig`) suite.
//!
//! The C suite exercises the legacy C client's server list management (ZOOKEEPER-1355) via
//! `zoo_set_servers` and `zoo_cycle_next_server` against synthetic `10.10.10.x` endpoints without
//! opening connections. These tests exercise the corresponding Rust implementation in
//! `zk_proto::legacy` (host parser, Fisher-Yates shuffle, `addrvec`, and `ServerSet`), driving
//! the `zk_testkit::rng` replicas of `random()` and `drand48()` with the exact seeds from the C
//! suite.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::collections::BTreeSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use zk_proto::legacy::addrvec::{AddrEq, AddrVec, ListEq, contains, shuffle};
use zk_proto::legacy::connect::parse_servers;
use zk_proto::legacy::servers::{ServerSet, UpdateOutcome};
use zk_testkit::rng::{Drand48, GlibcRandom, SplitMix};

/// Base port offset added to a host's final IPv4 octet in the C test suite.
const PORT_OFFSET: u16 = 2000;

/// Number of simulated clients in load-balancing tests.
const NUM_CLIENTS: usize = 10_000;

/// Permitted deviation from uniform load distribution, in percent.
const SLACK_PERCENT: f64 = 10.0;

/// Constructs the host list matching C `createHostList(start, stop, octet)`.
///
/// Generates comma-separated `10.10.10.<o>:<2000 + o>` entries descending from `octet` (or
/// `start` when `octet == 0`), producing one entry per step from `start` down to `stop`.
fn host_list(start: u16, stop: u16, octet: u16) -> String {
    let octet = if octet == 0 { start } else { octet };
    (stop..=start)
        .rev()
        .zip((0..=octet).rev())
        .map(|(_, o)| format!("10.10.10.{o}:{}", PORT_OFFSET + o))
        .collect::<Vec<_>>()
        .join(", ")
}

/// Constructs the host list from `start` down to 1 (`createHostList(start)`).
fn hosts(start: u16) -> String {
    host_list(start, 1, 0)
}

/// Simulated C client handle for ensemble reconfiguration tests.
///
/// Matches the C suite's PRNG initialization: `srandom` and `srand48` are seeded per client with
/// `seed * seed + 0xAFAFAFAF`. When non-deterministic connection ordering is enabled, each
/// resolution re-seeds both generators from `/dev/urandom` (`setup_random`), modeled here via a
/// deterministic `SplitMix` stream for reproducibility.
struct Client {
    set: ServerSet<SocketAddr>,
    seed: u32,
    random: GlibcRandom,
    drand: Drand48,
    deterministic: bool,
    urandom: SplitMix,
}

impl Client {
    /// Constructs client `index` following the C `Client` constructor: resolves `list`, re-seeds
    /// both PRNGs, and cycles to the initial server.
    fn new(index: u32, list: &str, deterministic: bool) -> Self {
        let seed = index.wrapping_mul(index).wrapping_add(0xAFAF_AFAF);
        let mut c = Self {
            set: ServerSet::new(),
            seed,
            random: GlibcRandom::new(seed),
            drand: Drand48::new(seed),
            deterministic,
            urandom: SplitMix::new(u64::from(seed)),
        };
        c.set_servers(list);
        c.re_seed();
        c.cycle_next_server();
        c
    }

    /// Constructs a client and cycles until connected to `ip` (C `createClient(hosts, host)`).
    fn on(index: u32, list: &str, ip: &str) -> Self {
        assert!(list.contains(ip), "{ip} is not in {list}");
        let mut c = Self::new(index, list, true);
        c.cycle_until_server(ip);
        c
    }

    /// Resets both PRNGs to this client's base seed (C `reSeed`).
    fn re_seed(&mut self) {
        self.random.seed(self.seed);
        self.drand.seed(self.seed);
    }

    /// Parses and installs `list` into the server set (`zoo_set_servers` / `update_addrs`),
    /// returning the update outcome.
    fn set_servers(&mut self, list: &str) -> UpdateOutcome {
        let mut resolved: Vec<SocketAddr> = parse_servers(list.as_bytes())
            .unwrap()
            .iter()
            .map(|h| {
                let ip: Ipv4Addr = std::str::from_utf8(&h.host).unwrap().parse().unwrap();
                SocketAddr::from((ip, u16::try_from(h.port).unwrap()))
            })
            .collect();
        if !self.deterministic {
            // Replicate C `setup_random` followed by `addrvec_shuffle`.
            let s = u32::try_from(self.urandom.next_u64() >> 32).unwrap();
            self.random.seed(s);
            self.drand.seed(s);
            let random = &mut self.random;
            shuffle(&mut resolved, || i64::from(random.next_u32()));
        }
        let drand = &mut self.drand;
        self.set
            .update(resolved, ListEq::LegacyOneWay, AddrEq::IpOnly, || {
                drand.next_f64()
            })
    }

    /// Updates the server list and asserts the resulting `reconfig` flag
    /// (C `setServersAndVerifyReconfig`).
    fn set_servers_and_verify_reconfig(&mut self, list: &str, want: bool) {
        self.set_servers(list);
        assert_eq!(self.set.reconfig, want, "reconfig after {list}");
    }

    /// Updates the server list and cycles to the next server if migration is required
    /// (C `setServersAndCycleIfNeeded`).
    fn set_servers_and_cycle_if_needed(&mut self, list: &str) {
        self.set_servers(list);
        if self.set.reconfig {
            self.cycle_next_server();
        }
    }

    /// Advances to and returns the next server (`zoo_cycle_next_server`).
    fn cycle_next_server(&mut self) -> SocketAddr {
        let drand = &mut self.drand;
        self.set.cycle_next(|| drand.next_f64());
        self.server()
    }

    /// Cycles through the server list until matching `ip`, panicking if a full cycle completes
    /// without a match (C `cycleUntilServer`).
    fn cycle_until_server(&mut self, ip: &str) {
        let want: IpAddr = ip.parse().unwrap();
        let mut first = None;
        loop {
            let next = self.cycle_next_server();
            match first {
                None => first = Some(next),
                Some(f) => assert_ne!(f, next, "{ip} not found in one loop"),
            }
            if next.ip() == want {
                return;
            }
        }
    }

    /// Returns the active server address (`zoo_get_current_server`).
    fn server(&self) -> SocketAddr {
        self.set.current_addr().expect("a current server")
    }

    /// Returns the zero-based index of the active server in the C client count table (`octet - 1`).
    fn host_index(&self) -> usize {
        usize::from(self.server().port() - PORT_OFFSET - 1)
    }
}

/// Parses a comma-separated host list into a set of socket addresses.
fn addr_set(list: &str) -> BTreeSet<SocketAddr> {
    list.split(", ").map(|s| s.parse().unwrap()).collect()
}

fn upper_bound(clients: usize, servers: usize) -> f64 {
    (1.0 + SLACK_PERCENT / 100.0) * clients as f64 / servers as f64
}

fn lower_bound(clients: usize, servers: usize) -> f64 {
    (1.0 - SLACK_PERCENT / 100.0) * clients as f64 / servers as f64
}

/// Verifies that each server in `offset..servers` holds a client count within the allowed slack
/// bounds, resetting each verified entry to zero (matching C `updateAllClientsAndServers`).
fn check_balance(counts: &mut [u32], offset: usize, servers: usize) {
    for (index, n) in counts.iter_mut().enumerate().take(servers).skip(offset) {
        let n_f = f64::from(*n);
        assert!(
            n_f <= upper_bound(NUM_CLIENTS, servers),
            "host {index}: {n} clients"
        );
        assert!(
            n_f >= lower_bound(NUM_CLIENTS, servers),
            "host {index}: {n} clients"
        );
        *n = 0;
    }
}

/// Reconfigures all clients to the host range `start..=stop` and verifies load balance across the
/// updated ensemble (C `updateAllClientsAndServers`).
fn update_all(clients: &mut [Client], counts: &mut [u32], start: u16, stop: u16) {
    let list = host_list(start, stop, 0);
    for c in clients.iter_mut() {
        c.re_seed();
        c.set_servers_and_cycle_if_needed(&list);
        counts[c.host_index()] += 1;
    }
    let servers = usize::from(start - stop + 1);
    check_balance(counts, usize::from(stop - 1), servers);
}

/// `Zookeeper_reconfig::testcycleNextServer`: shrinking the ensemble to a list that excludes the
/// current server sets `reconfig`. Subsequent cycles yield each retained server once before
/// resuming round-robin traversal over the new list.
#[test]
fn cycle_next_server() {
    let initial = hosts(10);
    let new = hosts(4);
    let mut c = Client::new(0, &initial, true);
    assert_eq!(c.server(), "10.10.10.10:2010".parse().unwrap());
    c.set_servers_and_verify_reconfig(&new, true);
    let allowed = addr_set(&new);
    let got: Vec<SocketAddr> = (0..10).map(|_| c.cycle_next_server()).collect();
    assert!(got.iter().all(|a| allowed.contains(a)), "{got:?}");
    // The four retained servers are visited once each before clearing `reconfig`.
    assert_eq!(got[..4].iter().collect::<BTreeSet<_>>().len(), 4, "{got:?}");
    assert!(!c.set.reconfig);
    // Subsequent cycles wrap around in standard round-robin order.
    assert_eq!(got[4], got[8], "{got:?}");
}

/// `Zookeeper_reconfig::testMigrateOrNot`: verifies the probabilistic migration decision across
/// ensemble size transitions:
/// - Shrinking while retaining the current server does not trigger migration.
/// - Shrinking while removing the current server triggers migration.
/// - Re-applying an identical list is a no-op (`UpdateOutcome::Unchanged`), preserving `reconfig`.
/// - Growing while removing the current server triggers migration.
#[test]
fn migrate_or_not() {
    let mut c = Client::on(0, &hosts(4), "10.10.10.3");
    // Ensemble shrinks, current server remains in the new list.
    c.set_servers_and_verify_reconfig(&hosts(3), false);
    // Ensemble shrinks, current server is excluded from the new list.
    c.set_servers_and_verify_reconfig(&hosts(2), true);
    // Identical server list is a no-op; `reconfig` remains set.
    assert_eq!(c.set_servers(&hosts(2)), UpdateOutcome::Unchanged);
    assert!(c.set.reconfig);
    // Ensemble grows, current server is excluded from the new list.
    c.set_servers(&hosts(4));
    c.cycle_until_server("10.10.10.1");
    c.set_servers_and_verify_reconfig(&host_list(7, 2, 0), true);
}

/// `Zookeeper_reconfig::testMigrationCycle`: during reconfiguration, cycling visits all newly
/// added servers first, then all retained old servers, and finally resumes round-robin order
/// across the full ensemble.
#[test]
fn migration_cycle() {
    let new_hosts = host_list(12, 3, 0);
    let old_staying = addr_set(&host_list(4, 3, 0));
    let new_coming = addr_set(&host_list(12, 5, 0));
    let mut c = Client::on(0, &hosts(4), "10.10.10.1");
    c.set_servers_and_verify_reconfig(&new_hosts, true);

    let mut seen = BTreeSet::new();
    for _ in 0..new_coming.len() {
        let next = c.cycle_next_server();
        assert!(new_coming.contains(&next), "{next} not in the new list");
        assert!(seen.insert(next), "{next} seen twice");
    }
    seen.clear();
    for _ in 0..old_staying.len() {
        let next = c.cycle_next_server();
        assert!(old_staying.contains(&next), "{next} not in the old list");
        assert!(seen.insert(next), "{next} seen twice");
    }
    // Resumes round-robin traversal over all ten servers.
    let first = c.cycle_next_server();
    for _ in 0..9 {
        c.cycle_next_server();
    }
    assert_eq!(c.cycle_next_server(), first);
}

/// `Zookeeper_reconfig::testAddrVecContainsIPv4`: `AddrVec::contains` distinguishes single-bit
/// differences across all 32 bits of an IPv4 address as well as IPv6 addresses with equivalent
/// zero bytes.
#[test]
fn addr_vec_contains_ipv4() {
    let addr = SocketAddr::from((Ipv4Addr::UNSPECIFIED, 1234));
    let mut vec: AddrVec<SocketAddr> = AddrVec::new();
    for i in 0..32 {
        let ip = Ipv4Addr::from(u32::from(Ipv4Addr::UNSPECIFIED) ^ (1 << i));
        vec.push(SocketAddr::from((ip, 1234)));
    }
    // Equivalent zero bytes represented as native IPv6 and IPv4-mapped IPv6.
    vec.push(SocketAddr::from((Ipv6Addr::UNSPECIFIED, 1234)));
    vec.push(SocketAddr::from((
        Ipv4Addr::UNSPECIFIED.to_ipv6_mapped(),
        1234,
    )));
    assert!(!vec.contains(&addr, AddrEq::IpOnly));
    assert!(!contains(vec.as_slice(), &addr, AddrEq::IpAndPort));
    vec.push(addr);
    assert!(vec.contains(&addr, AddrEq::IpOnly));
    assert!(contains(vec.as_slice(), &addr, AddrEq::IpAndPort));
}

/// `Zookeeper_reconfig::testAddrVecContainsIPv6`: `AddrVec::contains` distinguishes single-bit
/// differences across all 128 bits of an IPv6 address as well as IPv4 addresses.
#[test]
fn addr_vec_contains_ipv6() {
    let addr = SocketAddr::from((Ipv6Addr::UNSPECIFIED, 1234));
    let mut vec: AddrVec<SocketAddr> = AddrVec::new();
    for i in 0..128 {
        let ip = Ipv6Addr::from(u128::from(Ipv6Addr::UNSPECIFIED) ^ (1 << i));
        vec.push(SocketAddr::from((ip, 1234)));
    }
    vec.push(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 1234)));
    assert!(!vec.contains(&addr, AddrEq::IpOnly));
    assert!(!contains(vec.as_slice(), &addr, AddrEq::IpAndPort));
    vec.push(addr);
    assert!(vec.contains(&addr, AddrEq::IpOnly));
    assert!(contains(vec.as_slice(), &addr, AddrEq::IpAndPort));
}

/// `Zookeeper_reconfig::testMigrateProbability`: expanding the ensemble from 9 to 10 servers while
/// retaining the current server migrates clients with probability `1 - 9/10`. Across 10,000
/// deterministically seeded clients, the migration count falls within the 10% slack bounds.
#[test]
fn migrate_probability() {
    let initial = hosts(9);
    let new = hosts(10);
    let mut moves = 0_usize;
    for i in 0..NUM_CLIENTS {
        let mut c = Client::on(u32::try_from(i).unwrap(), &initial, "10.10.10.3");
        c.set_servers(&new);
        if c.set.reconfig {
            moves += 1;
        }
    }
    let m = moves as f64;
    assert!(m < upper_bound(NUM_CLIENTS, 10), "{moves} moves");
    assert!(m > lower_bound(NUM_CLIENTS, 10), "{moves} moves");
}

/// `Zookeeper_reconfig::testLoadBalancing`: 10,000 clients with randomized connection order
/// distribute uniformly across 9 servers and maintain per-server load within 10% slack across
/// successive ensemble resizing (8, 6, 8, and 9 servers), leaving zero clients on removed hosts.
///
/// Mirrors the C test's second `updateAllClientsAndServers(8, 1)` step and subsequent `counts[0]`
/// assertion after counter reset.
#[test]
fn load_balancing() {
    let initial = hosts(9);
    let mut counts = vec![0_u32; NUM_CLIENTS];
    let mut clients: Vec<Client> = (0..NUM_CLIENTS)
        .map(|i| Client::new(u32::try_from(i).unwrap(), &initial, false))
        .collect();
    for c in &clients {
        counts[c.host_index()] += 1;
    }
    check_balance(&mut counts, 0, 9);

    // Shrink to 8 servers.
    update_all(&mut clients, &mut counts, 8, 1);
    assert_eq!(counts[8], 0);

    // Shrink to 6 servers.
    update_all(&mut clients, &mut counts, 6, 1);
    assert_eq!(counts[6..9], [0, 0, 0]);

    // Expand back to 8 servers (mirrors the C step asserting `counts[0] == 0` post-reset).
    update_all(&mut clients, &mut counts, 8, 1);
    assert_eq!(counts[0], 0);

    // Restore full 9-server ensemble.
    update_all(&mut clients, &mut counts, 9, 1);
}
