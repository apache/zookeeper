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

//! Verification of receive liveness while the backpressure read gate is closed across the native
//! threaded and Tokio asynchronous drivers.
//!
//! Asserts receive-timer and flow-control invariants under read suspension:
//! - A gated session whose server continues answering heartbeat pings remains connected across
//!   multiple session timeouts without reconnecting or tearing down; the driver routes no items
//!   past the closed gate, and once the application consumes its backlog the driver reads and
//!   routes the remaining replies (the native test also checks that every routed result completes
//!   with `ZOK`).
//! - For a gated session whose server stops transmitting (ping replies dropped), the connection is
//!   still torn down by the receive timeout (`Cause::RecvTimeout`), even when prior replies wait
//!   unread in the socket behind the closed gate, because only bytes arriving while the gate is
//!   closed count as a receive.
//!
//! Both the live-server test and the unread-replies silent-server test submit their remaining
//! requests after the gate closes so those replies stay unread in the socket; the two scenarios
//! differ only in whether the server answers heartbeat pings.
//!
//! Asserts that the I/O loop does not spin while the gate is closed:
//! - A plain session whose server resets the connection while the gate is closed waits on the
//!   waker and its timers. Linux poll reports hangup and error on a reset socket even with no
//!   requested events, so the driver must not register a socket that has no interest bits.
//! - A TLS session whose server sends replies and then `close_notify` holds the latched end of
//!   stream behind the held replies. A request submitted while the gate is closed cannot flush
//!   past that latched failure, so the driver must not ask for write readiness. Once the gate
//!   reopens, the driver delivers the held reply and then tears down the connection.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use common::{Collect, IpHost, TestSpawner, WAIT, env, exists_native, legacy_config};
use zk_driver::{Clock, Env, MonotonicClock};
use zk_fakeserver::{Action, ClusterConfig, FakeServer, Rule, ServerOptions};
use zk_proto::{Cause, Compat, Connection, DriverMode, LogLevel, Millis, State};
use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
use zk_testkit::rng::SeededRng;
use zk_testkit::tokens::TestTokens;

/// Requested session timeout (600 ms); the receive timeout is two-thirds of this interval and
/// heartbeat pings are scheduled after one-third of it.
const SESSION_MS: i32 = 600;

/// Duration for which the application holds its unconsumed backlog, spanning multiple session
/// timeouts.
const HOLD: Duration = Duration::from_millis(2_000);

/// Configured `dispatch_queue_capacity`: the number of capacity-counted items the driver may
/// route before closing the read gate and suspending socket reads.
const CAPACITY: usize = 2;

/// Total number of requests submitted by the gated tests: `CAPACITY` requests before the read
/// gate closes and the remainder afterward.
const TOTAL: u64 = 4;

fn server() -> FakeServer {
    let cfg = ClusterConfig {
        min_session_timeout_ms: 100,
        ..ClusterConfig::default()
    };
    FakeServer::start_with(cfg, ServerOptions::default()).unwrap()
}

fn connection(server: &FakeServer) -> Box<Connection<TestTokens>> {
    let mut cfg = legacy_config(&server.connect_string());
    cfg.timeout_ms = SESSION_MS;
    cfg.close_timeout_ms = 3_000;
    Box::new(Connection::new(
        cfg,
        Compat::NATIVE,
        DriverMode::NATIVE,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        Millis(0),
    ))
}

fn gated_env() -> Env<TestTokens> {
    let mut e = env(IpHost::new(), true);
    e.cfg.dispatch_queue_capacity = CAPACITY;
    e
}

/// Configures the fake server to drop heartbeat ping replies so no new bytes reach a gated
/// client.
fn silence_pings(server: &FakeServer) {
    server.add_rule(
        Rule::on_request()
            .op(zk_jute::opcode::PING)
            .then(Action::DropReply),
    );
}

/// Session timeout (3 s) for the spin tests. The receive timeout and the first heartbeat ping
/// come after each spin measurement window ends.
const SPIN_SESSION_MS: i32 = 3_000;

/// Interval in which a spin test counts the driver's clock reads while the gate is closed.
const SPIN_WINDOW: Duration = Duration::from_millis(300);

/// Largest number of clock reads permitted in `SPIN_WINDOW`. An idle driver reads the clock a few
/// times for each timer wakeup. A spinning driver reads it many thousands of times.
const SPIN_READS: u64 = 500;

/// Clock that counts its reads. Each driver pass reads the clock, so the count bounds the number
/// of passes in an interval.
#[derive(Default)]
struct CountingClock {
    inner: MonotonicClock,
    reads: AtomicU64,
}

impl CountingClock {
    fn reads(&self) -> u64 {
        self.reads.load(Ordering::SeqCst)
    }
}

impl Clock for CountingClock {
    fn now(&self) -> Millis {
        self.reads.fetch_add(1, Ordering::SeqCst);
        self.inner.now()
    }
}

/// Returns the number of clock reads during `SPIN_WINDOW`.
fn reads_in_window(clock: &CountingClock) -> u64 {
    let before = clock.reads();
    std::thread::sleep(SPIN_WINDOW);
    clock.reads() - before
}

fn spin_connection(server: &FakeServer, tls: bool) -> Box<Connection<TestTokens>> {
    let mut cfg = legacy_config(&server.connect_string());
    cfg.timeout_ms = SPIN_SESSION_MS;
    cfg.close_timeout_ms = 3_000;
    cfg.tls = tls;
    // The legacy default resolves the hosts on each interest pass. Each background resolution
    // wakes the reactor, which would hide the spin under test.
    cfg.resolve_delay_ms = -1;
    Box::new(Connection::new(
        cfg,
        Compat::NATIVE,
        DriverMode::NATIVE,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        Millis(0),
    ))
}

fn counted_env(clock: &Arc<CountingClock>) -> Env<TestTokens> {
    let mut e = gated_env();
    e.clock = Arc::clone(clock) as Arc<dyn Clock>;
    e
}

/// TLS fixtures for the latched end-of-stream spin test.
#[cfg(feature = "tls")]
mod tls_close {
    use super::*;
    use common::fixture;
    use common::tls::{native_connector, provider};
    use std::sync::atomic::AtomicBool;
    use zk_driver::RouteSink;
    use zk_driver::outbox::Item;
    use zk_fakeserver::TlsAcceptor;

    /// Time the sink holds the I/O thread on the first result. The server sends the remaining
    /// replies and `close_notify` during this time, so the driver reads them in one receive.
    const STALL: Duration = Duration::from_millis(400);

    /// Routing sink that holds the I/O thread while it routes the first result.
    #[derive(Default)]
    pub(super) struct StallFirst {
        pub(super) inner: Collect,
        stalled: AtomicBool,
    }

    impl RouteSink<TestTokens> for StallFirst {
        fn route(&self, item: Item<TestTokens>) -> bool {
            if matches!(item, Item::Result { .. }) && !self.stalled.swap(true, Ordering::SeqCst) {
                std::thread::sleep(STALL);
            }
            self.inner.route(item)
        }
    }

    /// Starts a TLS fake server that sends `close_notify` after the third `exists` reply.
    ///
    /// The server holds the first reply until the client has sent all three requests. It then
    /// holds the second reply until the client stalls in the sink on the first result. The
    /// second reply, the third reply, and `close_notify` thus reach the client in one burst.
    pub(super) fn server() -> FakeServer {
        let acc = TlsAcceptor::builder(provider())
            .cert_chain_pem_file(fixture("server.pem"))
            .unwrap()
            .key_pem_file(fixture("server.key"))
            .unwrap()
            .build()
            .unwrap();
        let cfg = ClusterConfig {
            min_session_timeout_ms: 100,
            ..ClusterConfig::default()
        };
        let opts = ServerOptions::default().with_acceptor(acc as _);
        let server = FakeServer::start_with(cfg, opts).unwrap();
        let exists = || {
            Rule::on_request()
                .op(zk_jute::opcode::EXISTS)
                .conn(1)
                .times(1)
        };
        server.add_rule(
            exists()
                .then(Action::Delay(Duration::from_millis(100)))
                .then(Action::Model),
        );
        server.add_rule(
            exists()
                .then(Action::Delay(Duration::from_millis(200)))
                .then(Action::Model),
        );
        server.add_rule(exists().then(Action::Model).then(Action::Close));
        server
    }

    /// Returns an `exists` request. The second request has a direct route, so its reply is a
    /// barrier. The driver routes that reply and closes the gate before it parses the third reply,
    /// which then stays held with the latched `close_notify` behind it.
    pub(super) fn request(id: u64) -> zk_proto::Request<TestTokens> {
        let mut r = exists_native(id);
        if id == 2 {
            r.route = zk_proto::Route::Direct;
        }
        r
    }

    pub(super) fn tls_env(clock: &Arc<CountingClock>) -> Env<TestTokens> {
        let mut e = counted_env(clock);
        e.tls = Some(native_connector());
        e
    }
}

mod native {
    use super::*;
    use zk_driver::native::{NativeHandle, start};

    fn wait(pred: impl FnMut() -> bool) -> bool {
        common::wait_until(WAIT, pred)
    }

    fn connected(h: &NativeHandle<TestTokens>) -> bool {
        h.state() == State::Connected.code()
    }

    /// Submits `CAPACITY` requests to fill the read gate, verifies that the gate has closed, and
    /// then submits the remaining requests so their replies wait unread in the socket.
    fn gate_with_unread(h: &NativeHandle<TestTokens>, sink: &Collect) {
        for id in 1..=CAPACITY as u64 {
            h.submit(exists_native(id)).ok().unwrap();
        }
        assert!(wait(|| sink.results() == CAPACITY));
        assert!(!h.gate().is_open());
        for id in CAPACITY as u64 + 1..=TOTAL {
            h.submit(exists_native(id)).ok().unwrap();
        }
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_gated_session_with_a_live_server_stays_connected() {
        let server = server();
        let sink = Arc::new(Collect::default());
        let h = start(
            connection(&server),
            gated_env(),
            sink.clone(),
            None,
            Arc::new(TestSpawner),
        )
        .unwrap();
        assert!(wait(|| connected(&h)));
        gate_with_unread(&h, &sink);
        std::thread::sleep(HOLD);
        assert_eq!(
            sink.results(),
            CAPACITY,
            "the driver read past a closed gate"
        );
        assert_eq!(server.accepted(), 1, "the client reconnected");
        assert_eq!(h.last_teardown_cause(), None);
        assert!(connected(&h));
        // Consuming (taking) the routed backlog items reopens the read gate and allows the driver
        // to read and dispatch the remaining replies.
        let routed = sink.results();
        for _ in 0..routed {
            h.taken();
        }
        assert!(wait(|| sink.results() == TOTAL as usize));
        assert!(sink.0.lock().unwrap().iter().all(|i| match i {
            zk_driver::outbox::Item::Result { result, .. } => result.rc == zk_proto::rc::ZOK,
            _ => true,
        }));
        for _ in routed..TOTAL as usize {
            h.taken();
        }
        assert_eq!(server.accepted(), 1);
        h.close();
        assert_eq!(h.wait_closed(WAIT), Some(zk_proto::rc::ZOK));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_gated_session_with_unread_replies_and_a_silent_server_times_out() {
        let server = server();
        let sink = Arc::new(Collect::default());
        let h = start(
            connection(&server),
            gated_env(),
            sink.clone(),
            None,
            Arc::new(TestSpawner),
        )
        .unwrap();
        assert!(wait(|| connected(&h)));
        gate_with_unread(&h, &sink);
        silence_pings(&server);
        assert!(wait(|| h.last_teardown_cause() == Some(Cause::RecvTimeout)));
        h.close();
        assert!(h.wait_closed(WAIT).is_some());
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_gated_session_with_a_silent_server_times_out() {
        let server = server();
        let sink = Arc::new(Collect::default());
        let h = start(
            connection(&server),
            gated_env(),
            sink.clone(),
            None,
            Arc::new(TestSpawner),
        )
        .unwrap();
        assert!(wait(|| connected(&h)));
        for id in 1..=CAPACITY as u64 {
            h.submit(exists_native(id)).ok().unwrap();
        }
        assert!(wait(|| sink.results() == CAPACITY));
        assert!(!h.gate().is_open());
        silence_pings(&server);
        assert!(wait(|| h.last_teardown_cause() == Some(Cause::RecvTimeout)));
        h.close();
        assert!(h.wait_closed(WAIT).is_some());
    }

    /// A gated plain session whose server resets the connection does not spin, and the next
    /// heartbeat ping detects the reset.
    ///
    /// The server closes on the third request. The fourth request reaches a closed socket, so the
    /// server host answers it with a reset. Only Linux poll then reports hangup and error on a
    /// socket that requests no events, so only Linux reproduces the spin. The test runs on every
    /// platform because the session must still detect the reset there.
    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_gated_session_reset_by_the_server_does_not_spin() {
        let server = server();
        let sink = Arc::new(Collect::default());
        let clock = Arc::new(CountingClock::default());
        let h = start(
            spin_connection(&server, false),
            counted_env(&clock),
            sink.clone(),
            None,
            Arc::new(TestSpawner),
        )
        .unwrap();
        assert!(wait(|| connected(&h)));
        for id in 1..=CAPACITY as u64 {
            h.submit(exists_native(id)).ok().unwrap();
        }
        assert!(wait(|| sink.results() == CAPACITY));
        assert!(!h.gate().is_open());
        server.add_rule(
            Rule::on_request()
                .op(zk_jute::opcode::EXISTS)
                .then(Action::Close),
        );
        h.submit(exists_native(3)).ok().unwrap();
        std::thread::sleep(Duration::from_millis(100));
        h.submit(exists_native(4)).ok().unwrap();
        std::thread::sleep(Duration::from_millis(100));
        let reads = reads_in_window(&clock);
        assert!(
            reads < SPIN_READS,
            "the gated driver spun: {reads} clock reads"
        );
        assert_eq!(h.last_teardown_cause(), None);
        assert!(wait(|| h.last_teardown_cause().is_some()));
        h.close();
        assert!(h.wait_closed(WAIT).is_some());
    }

    /// A gated TLS session with a latched `close_notify` does not spin on write readiness, and it
    /// tears down the connection after it delivers the held reply.
    #[cfg(feature = "tls")]
    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_gated_tls_session_with_a_latched_close_does_not_spin() {
        let server = tls_close::server();
        let sink = Arc::new(tls_close::StallFirst::default());
        let clock = Arc::new(CountingClock::default());
        let h = start(
            spin_connection(&server, true),
            tls_close::tls_env(&clock),
            sink.clone(),
            None,
            Arc::new(TestSpawner),
        )
        .unwrap();
        assert!(wait(|| connected(&h)));
        for id in 1..=3 {
            h.submit(tls_close::request(id)).ok().unwrap();
        }
        assert!(wait(|| sink.inner.results() == CAPACITY));
        assert!(!h.gate().is_open());
        // The queued request asks for write readiness, but it cannot flush past the latched
        // failure.
        h.submit(exists_native(4)).ok().unwrap();
        let reads = reads_in_window(&clock);
        assert!(
            reads < SPIN_READS,
            "the gated driver spun: {reads} clock reads"
        );
        assert_eq!(sink.inner.results(), CAPACITY);
        assert_eq!(h.last_teardown_cause(), None);
        for _ in 0..CAPACITY {
            h.taken();
        }
        assert!(wait(|| h.last_teardown_cause() == Some(Cause::Eof)));
        assert!(sink.inner.results() > CAPACITY);
        assert_eq!(
            sink.inner.0.lock().unwrap().iter().find_map(|i| match i {
                zk_driver::outbox::Item::Result { token, result, .. } if token.id() == 3 => {
                    Some(result.rc)
                }
                _ => None,
            }),
            Some(zk_proto::rc::ZOK),
            "the held reply was not delivered before the teardown"
        );
        h.close();
        assert!(h.wait_closed(WAIT).is_some());
    }
}

#[cfg(all(unix, feature = "tokio"))]
mod tokio_driver {
    use super::*;
    use zk_driver::tokio::{TaskHandle, spawn};

    fn runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
    }

    async fn wait(mut pred: impl FnMut() -> bool) -> bool {
        let start = tokio::time::Instant::now();
        while start.elapsed() < WAIT {
            if pred() {
                return true;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        pred()
    }

    fn connected(h: &TaskHandle<TestTokens>) -> bool {
        h.state() == State::Connected.code()
    }

    /// Submits `CAPACITY` requests to fill the read gate, verifies that the gate has closed, and
    /// then submits the remaining requests so their replies wait unread in the socket.
    async fn gate_with_unread(h: &TaskHandle<TestTokens>, sink: &Collect) {
        for id in 1..=CAPACITY as u64 {
            h.submit(exists_native(id)).ok().unwrap();
        }
        assert!(wait(|| sink.results() == CAPACITY).await);
        assert!(!h.gate().is_open());
        for id in CAPACITY as u64 + 1..=TOTAL {
            h.submit(exists_native(id)).ok().unwrap();
        }
    }

    #[test]
    fn a_gated_session_with_a_live_server_stays_connected() {
        let rt = runtime();
        let server = server();
        let sink = Arc::new(Collect::default());
        let h = spawn(
            connection(&server),
            gated_env(),
            sink.clone(),
            None,
            rt.handle(),
        );
        rt.block_on(async {
            assert!(wait(|| connected(&h)).await);
            gate_with_unread(&h, &sink).await;
            tokio::time::sleep(HOLD).await;
            assert_eq!(
                sink.results(),
                CAPACITY,
                "the driver read past a closed gate"
            );
            assert_eq!(server.accepted(), 1, "the client reconnected");
            assert_eq!(h.last_teardown_cause(), None);
            assert!(connected(&h));
            let routed = sink.results();
            for _ in 0..routed {
                h.taken();
            }
            assert!(wait(|| sink.results() == TOTAL as usize).await);
            for _ in routed..TOTAL as usize {
                h.taken();
            }
            assert_eq!(server.accepted(), 1);
            h.close();
            let rc = tokio::time::timeout(WAIT, h.closed()).await.unwrap();
            assert_eq!(rc, zk_proto::rc::ZOK);
        });
    }

    #[test]
    fn a_gated_session_with_unread_replies_and_a_silent_server_times_out() {
        let rt = runtime();
        let server = server();
        let sink = Arc::new(Collect::default());
        let h = spawn(
            connection(&server),
            gated_env(),
            sink.clone(),
            None,
            rt.handle(),
        );
        rt.block_on(async {
            assert!(wait(|| connected(&h)).await);
            gate_with_unread(&h, &sink).await;
            silence_pings(&server);
            assert!(wait(|| h.last_teardown_cause() == Some(Cause::RecvTimeout)).await);
            h.close();
            assert!(tokio::time::timeout(WAIT, h.closed()).await.is_ok());
        });
    }

    #[test]
    fn a_gated_session_with_a_silent_server_times_out() {
        let rt = runtime();
        let server = server();
        let sink = Arc::new(Collect::default());
        let h = spawn(
            connection(&server),
            gated_env(),
            sink.clone(),
            None,
            rt.handle(),
        );
        rt.block_on(async {
            assert!(wait(|| connected(&h)).await);
            for id in 1..=CAPACITY as u64 {
                h.submit(exists_native(id)).ok().unwrap();
            }
            assert!(wait(|| sink.results() == CAPACITY).await);
            assert!(!h.gate().is_open());
            silence_pings(&server);
            assert!(wait(|| h.last_teardown_cause() == Some(Cause::RecvTimeout)).await);
            h.close();
            assert!(tokio::time::timeout(WAIT, h.closed()).await.is_ok());
        });
    }

    /// A gated TLS session with a latched `close_notify` does not spin on write readiness, and it
    /// tears down the connection after it delivers the held reply.
    #[cfg(feature = "tls")]
    #[test]
    fn a_gated_tls_session_with_a_latched_close_does_not_spin() {
        // A separate thread drives the runtime, so the test thread measures the window while the
        // I/O task runs.
        let rt = runtime();
        let handle = rt.handle().clone();
        let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
        let worker = std::thread::spawn(move || rt.block_on(async { drop(stop_rx.await) }));
        let server = tls_close::server();
        let sink = Arc::new(tls_close::StallFirst::default());
        let clock = Arc::new(CountingClock::default());
        let h = spawn(
            spin_connection(&server, true),
            tls_close::tls_env(&clock),
            sink.clone(),
            None,
            &handle,
        );
        let poll = |pred: &mut dyn FnMut() -> bool| common::wait_until(WAIT, pred);
        assert!(poll(&mut || connected(&h)));
        for id in 1..=3 {
            h.submit(tls_close::request(id)).ok().unwrap();
        }
        assert!(poll(&mut || sink.inner.results() == CAPACITY));
        assert!(!h.gate().is_open());
        h.submit(exists_native(4)).ok().unwrap();
        let reads = reads_in_window(&clock);
        assert!(
            reads < SPIN_READS,
            "the gated driver spun: {reads} clock reads"
        );
        assert_eq!(sink.inner.results(), CAPACITY);
        assert_eq!(h.last_teardown_cause(), None);
        for _ in 0..CAPACITY {
            h.taken();
        }
        assert!(poll(&mut || h.last_teardown_cause() == Some(Cause::Eof)));
        assert!(sink.inner.results() > CAPACITY);
        h.close();
        assert!(
            handle
                .block_on(async { tokio::time::timeout(WAIT, h.closed()).await })
                .is_ok()
        );
        let _sent = stop_tx.send(());
        worker.join().unwrap();
    }
}
