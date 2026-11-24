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
use std::time::Duration;

use common::{Collect, IpHost, TestSpawner, WAIT, env, exists_native, legacy_config};
use zk_driver::Env;
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
}
