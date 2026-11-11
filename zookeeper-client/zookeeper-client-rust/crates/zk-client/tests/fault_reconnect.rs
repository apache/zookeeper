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

//! Evaluates connection lifecycle and failover policies: exponential backoff intervals with
//! jitter, deterministic vs. randomized ensemble endpoint traversal, dynamic DNS refresh
//! strategies, and panic-resilient custom resolver dispatch.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::collections::HashSet;
use std::io;
use std::net::{SocketAddr, TcpListener};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use apache_zookeeper::{
    Client, DnsRefresh, Resolve, Runtime, ServerOrder, SessionState, StateWatcher, ThreadOptions,
};
use zk_fakeserver::{ClusterConfig, Ensemble, ServerOptions};

use common::{connect, within};

/// Awaits until the client establishes an active socket connection to `addr`.
async fn wait_server(c: &Client, addr: SocketAddr) {
    let mut w = c.state_watcher();
    within(async {
        while c.connected_server() != Some(addr) {
            let _ = tokio::time::timeout(Duration::from_millis(50), w.changed()).await;
        }
    })
    .await;
}

/// Test TCP listener running on a dedicated thread that records connection arrival timestamps
/// and terminates incoming sockets immediately.
struct Refuser {
    addr: SocketAddr,
    times: Arc<Mutex<Vec<Instant>>>,
    stop: Arc<AtomicBool>,
}

impl Refuser {
    fn start() -> Self {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        l.set_nonblocking(true).unwrap();
        let addr = l.local_addr().unwrap();
        let times = Arc::new(Mutex::new(Vec::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let (t, s) = (Arc::clone(&times), Arc::clone(&stop));
        std::thread::spawn(move || {
            while !s.load(Ordering::SeqCst) {
                match l.accept() {
                    Ok((sock, _)) => {
                        t.lock().unwrap().push(Instant::now());
                        drop(sock);
                    }
                    Err(_) => std::thread::sleep(Duration::from_millis(1)),
                }
            }
        });
        Self { addr, times, stop }
    }

    fn times(&self) -> Vec<Instant> {
        self.times.lock().unwrap().clone()
    }
}

impl Drop for Refuser {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

/// Validates exponential backoff and jitter algorithms. Intra-pass endpoint attempts proceed
/// without delay, whereas completed traversal passes induce an exponential delay starting at
/// `reconnect_delay` and scaling with ±25% randomized jitter.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_reconnect_pause_follows_a_pass_grows_and_has_jitter() {
    const DELAY_MS: u64 = 50;
    const PAUSES: usize = 5;
    let a = Refuser::start();
    let b = Refuser::start();
    let c = Client::builder(format!("{},{}", a.addr, b.addr))
        .session_timeout(common::SESSION_TIMEOUT)
        .server_order(ServerOrder::AsGiven)
        .reconnect_delay(Duration::from_millis(DELAY_MS))
        .build()
        .unwrap();
    // Two passes with no pause, then one pass after each pause.
    within(async {
        while b.times().len() < PAUSES + 2 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
    within(c.close()).await.unwrap();

    let (ta, tb) = (a.times(), b.times());
    // Within a single resolution cycle, server B is attempted immediately following server A.
    for (i, (x, y)) in ta.iter().zip(tb.iter()).enumerate() {
        let gap = y.saturating_duration_since(*x);
        assert!(
            gap < Duration::from_millis(40),
            "pass {i}: A to B took {gap:?}"
        );
    }
    // Measured inter-pass interval between iteration k and iteration k + 1. Initial attempt incurs
    // no delay.
    let gaps: Vec<Duration> = tb
        .iter()
        .zip(ta.iter().skip(1))
        .map(|(x, y)| y.saturating_duration_since(*x))
        .collect();
    assert!(
        gaps[0] < Duration::from_millis(30),
        "the second pass paused: {gaps:?}"
    );
    let mut ratios = Vec::new();
    for (k, g) in gaps.iter().skip(1).take(PAUSES).enumerate() {
        let nominal = DELAY_MS << k;
        let ms = u64::try_from(g.as_millis()).unwrap();
        assert!(
            ms + 2 >= nominal * 3 / 4 && ms <= nominal * 5 / 4 + 40,
            "pause {k} of {ms} ms is not {nominal} ms ±25%: {gaps:?}"
        );
        ratios.push(g.as_secs_f64() / Duration::from_millis(nominal).as_secs_f64());
    }
    let lo = ratios.iter().copied().fold(f64::MAX, f64::min);
    let hi = ratios.iter().copied().fold(f64::MIN, f64::max);
    assert!(hi - lo > 0.02, "the pauses have no jitter: {gaps:?}");
}

/// Validates deterministic failover: under `ServerOrder::AsGiven`, endpoint selection strictly
/// follows connection string sequence, preserving active session ID across successive node
/// transitions.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn failover_follows_the_given_order_and_keeps_the_session() {
    let mut ens = Ensemble::start(3, ClusterConfig::default()).unwrap();
    let addrs: Vec<SocketAddr> = ens.servers().iter().map(|s| s.addr()).collect();
    let c =
        connect(common::builder(&ens.connect_string()).server_order(ServerOrder::AsGiven)).await;
    let (id, _) = c.session();
    assert_eq!(c.connected_server(), Some(addrs[0]));

    ens.server_mut(0).unwrap().stop();
    wait_server(&c, addrs[1]).await;
    ens.server_mut(1).unwrap().stop();
    wait_server(&c, addrs[2]).await;
    // The order wraps around to the first host.
    ens.server_mut(0).unwrap().resume().unwrap();
    ens.server_mut(2).unwrap().stop();
    wait_server(&c, addrs[0]).await;

    assert_eq!(c.session().0, id);
    assert!(within(c.exists("/")).await.unwrap().is_some());
    // Unreachable endpoints are bypassed during subsequent traversal cycles.
    assert_eq!(ens.server(1).unwrap().accepted(), 1);
    within(c.close()).await.unwrap();
}

/// Confirms that default `ServerOrder::Shuffled` uniformly distributes independent client sessions
/// across available ensemble nodes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_default_order_spreads_clients_over_the_hosts() {
    let ens = Ensemble::start(3, ClusterConfig::default()).unwrap();
    let mut seen = HashSet::new();
    let mut clients = Vec::new();
    for _ in 0..12 {
        let c = connect(common::builder(&ens.connect_string())).await;
        seen.insert(c.connected_server().unwrap());
        clients.push(c);
    }
    assert!(seen.len() >= 2, "every client chose one host: {seen:?}");
    for c in clients {
        within(c.close()).await.unwrap();
    }
}

/// Mock DNS resolver with mutable address mappings and programmatic panic injection.
#[derive(Clone)]
struct TableResolve {
    table: Arc<Mutex<Vec<(String, SocketAddr)>>>,
    calls: Arc<AtomicUsize>,
    panics: usize,
    panic_on: Option<&'static str>,
}

impl TableResolve {
    fn new(entries: &[(&str, SocketAddr)]) -> Self {
        Self {
            table: Arc::new(Mutex::new(
                entries.iter().map(|(n, a)| ((*n).to_owned(), *a)).collect(),
            )),
            calls: Arc::default(),
            panics: 0,
            panic_on: None,
        }
    }

    fn set(&self, name: &str, addr: SocketAddr) {
        let mut t = self.table.lock().unwrap();
        t.retain(|(n, _)| n != name);
        t.push((name.to_owned(), addr));
    }

    fn calls(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
    }
}

impl Resolve for TableResolve {
    fn resolve(&self, host: &str, _port: u16) -> io::Result<Vec<SocketAddr>> {
        let n = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
        if n <= self.panics || self.panic_on == Some(host) {
            panic!("resolver bug at call {n}");
        }
        let t = self.table.lock().unwrap();
        let found: Vec<SocketAddr> = t
            .iter()
            .filter(|(name, _)| name == host)
            .map(|(_, a)| *a)
            .collect();
        if found.is_empty() {
            Err(io::Error::new(io::ErrorKind::NotFound, "unknown host"))
        } else {
            Ok(found)
        }
    }
}

/// Provisions a dual-listener mock cluster to evaluate session migration across physical
/// addresses.
fn two_listeners() -> (zk_fakeserver::FakeServer, zk_fakeserver::FakeServer) {
    let cluster = zk_fakeserver::Cluster::new(ClusterConfig::default());
    let a = cluster.start_server(ServerOptions::default()).unwrap();
    let b = cluster.start_server(ServerOptions::default()).unwrap();
    (a, b)
}

/// Validates `DnsRefresh::EachPass` policy. Hostname reresolution triggers upon commencing
/// each endpoint pass, applying modified IP targets following inter-pass delays.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn each_pass_follows_a_changed_record() {
    let (mut a, b) = two_listeners();
    let r = TableResolve::new(&[("zk.invalid", a.addr())]);
    let c = connect(
        common::builder("zk.invalid:2181")
            .resolver(r.clone())
            .dns_refresh(DnsRefresh::EachPass),
    )
    .await;
    assert_eq!(c.connected_server(), Some(a.addr()));
    let (id, _) = c.session();
    r.set("zk.invalid", b.addr());
    a.stop();
    wait_server(&c, b.addr()).await;
    assert_eq!(c.session().0, id);
    within(c.close()).await.unwrap();
}

/// Validates `DnsRefresh::Never` policy. Initial DNS resolutions remain fixed unless refreshed
/// via `update_servers()`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn never_keeps_the_first_resolution() {
    let (mut a, b) = two_listeners();
    let r = TableResolve::new(&[("zk.invalid", a.addr())]);
    let c = connect(
        common::builder("zk.invalid:2181")
            .resolver(r.clone())
            .dns_refresh(DnsRefresh::Never),
    )
    .await;
    let calls = r.calls();
    r.set("zk.invalid", b.addr());
    a.stop();
    tokio::time::sleep(Duration::from_millis(700)).await;
    assert_eq!(c.state().state, SessionState::Connecting);
    assert_eq!(r.calls(), calls, "Never resolved again");
    assert_eq!(b.accepted(), 0);
    // `update_servers` resolves again, and the client moves.
    c.update_servers("zk.invalid:2181").unwrap();
    wait_server(&c, b.addr()).await;
    assert!(r.calls() > calls);
    within(c.close()).await.unwrap();
}

/// Validates `DnsRefresh::Interval`. Background hostname reresolution executes periodically
/// during active sessions without severing established TCP connections.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn interval_resolves_while_connected() {
    let (a, _b) = two_listeners();
    let r = TableResolve::new(&[("zk.invalid", a.addr())]);
    let c = connect(
        common::builder("zk.invalid:2181")
            .resolver(r.clone())
            .dns_refresh(DnsRefresh::Interval(Duration::from_millis(100))),
    )
    .await;
    let epoch = c.state().epoch;
    let calls = r.calls();
    within(async {
        while r.calls() <= calls {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await;
    assert_eq!(
        c.state().epoch,
        epoch,
        "the resolution broke the connection"
    );
    assert!(c.state().state.is_connected());
    within(c.close()).await.unwrap();
}

/// Validates resolver fault isolation. Panics encountered during `Resolve::resolve` are caught
/// at driver boundaries via `catch_unwind`, treated as resolution failures, and allow failover
/// to proceed.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_panicking_host_is_skipped_on_both_runtimes() {
    let rts = [
        Some(Runtime::Thread(ThreadOptions::default())),
        None, // the default: the tokio driver with the tokio feature
    ];
    for rt in rts {
        let (a, _b) = two_listeners();
        let mut r = TableResolve::new(&[("good.invalid", a.addr())]);
        r.panic_on = Some("bad.invalid");
        let mut b = common::builder("bad.invalid:1,good.invalid:2")
            .resolver(r.clone())
            .server_order(ServerOrder::AsGiven);
        if let Some(rt) = rt {
            b = b.runtime(rt);
        }
        let c = connect(b).await;
        assert_eq!(c.connected_server(), Some(a.addr()));
        assert!(r.calls() >= 2);
        within(c.close()).await.unwrap();
    }
}

/// Verifies system stability under continuous resolver panics. Background connection loops
/// absorb panics without terminating the client, allowing clean lifecycle cancellation via
/// `close()`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_resolver_that_always_panics_keeps_the_client_alive() {
    let mut r = TableResolve::new(&[]);
    r.panics = usize::MAX;
    let f = common::builder("zk.invalid:2181")
        .resolver(r.clone())
        .reconnect_delay(Duration::from_millis(20))
        .connect();
    let w: StateWatcher = f.state_watcher();
    let pending = tokio::time::timeout(Duration::from_millis(600), f).await;
    assert!(pending.is_err(), "connect resolved with no address");
    assert!(
        r.calls() >= 2,
        "the client gave up after {} calls",
        r.calls()
    );
    // The drop of the connect future closed the client.
    let mut w = w;
    within(async {
        while w.current().state != SessionState::Closed {
            let _ = w.changed().await;
        }
    })
    .await;
}
