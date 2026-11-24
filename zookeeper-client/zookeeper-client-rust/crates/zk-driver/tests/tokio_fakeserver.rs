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

//! Asynchronous Tokio runtime task driver ([`crate::tokio`]) integration tests against
//! `zk-fakeserver`.
//!
//! Validates:
//! - Asynchronous session establishment, request-response execution, and graceful teardown.
//! - nonblocking concurrency isolation between background blocking DNS resolution tasks and
//!   active network I/O.
//! - Read gate backpressure flow control.
//! - Session detachment semantics without graceful shutdown handshake.

#![cfg(all(unix, feature = "tokio"))]
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
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use common::{
    Collect, IpHost, PanicOnSession, SlowHost, WAIT, env, exists_native, native_connection,
};
use zk_driver::outbox::Item;
use zk_fakeserver::FakeServer;
use zk_proto::State;
use zk_testkit::tokens::TestTokens;

#[test]
fn session_connects_routes_a_result_and_closes() {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        env(IpHost::new(), true),
        sink.clone(),
        None,
        rt.handle(),
    );
    rt.block_on(async {
        let start = tokio::time::Instant::now();
        while h.state() != State::Connected.code() {
            assert!(start.elapsed() < WAIT, "no session");
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        h.submit(exists_native(1)).ok().unwrap();
        while !sink
            .0
            .lock()
            .unwrap()
            .iter()
            .any(|i| matches!(i, Item::Result { .. }))
        {
            assert!(start.elapsed() < WAIT, "no result");
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        h.close();
        let _rc = h.closed().await;
    });
}

/// Awaits active session connection status (`State::Connected`).
async fn connected(h: &zk_driver::tokio::TaskHandle<TestTokens>) {
    let start = tokio::time::Instant::now();
    while h.state() != State::Connected.code() {
        assert!(start.elapsed() < WAIT, "no session");
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
}

#[test]
fn graceful_close_gives_zok_and_a_second_close_does_nothing() {
    let rt = runtime();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        env(IpHost::new(), true),
        sink,
        None,
        rt.handle(),
    );
    rt.block_on(async {
        connected(&h).await;
        h.close();
        h.close();
        let rc = tokio::time::timeout(WAIT, h.closed()).await.unwrap();
        assert_eq!(rc, zk_proto::rc::ZOK);
        // Subsequent close observers receive the identical latched terminal status code.
        assert_eq!(h.closed().await, rc);
    });
    assert_eq!(h.state(), 0);
    assert!(h.submit(exists_native(9)).is_err());
}

#[test]
fn detach_ends_the_task_with_no_close_request() {
    let rt = runtime();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        env(IpHost::new(), true),
        sink,
        None,
        rt.handle(),
    );
    rt.block_on(async {
        connected(&h).await;
        let id = h.client_id();
        h.detach();
        let rc = tokio::time::timeout(WAIT, h.closed()).await.unwrap();
        assert_eq!(rc, zk_proto::rc::ZOK);
        assert_ne!(id, zk_proto::ClientId::default());
    });
    assert_eq!(h.state(), 0);
}

#[test]
fn close_with_no_server_completes_at_once() {
    let rt = runtime();
    // Allocate an ephemeral unbound port so every connection attempt fails immediately.
    let port = std::net::TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let h = zk_driver::tokio::spawn(
        native_connection(&format!("127.0.0.1:{port}")),
        env(IpHost::new(), true),
        Arc::new(Collect::default()),
        None,
        rt.handle(),
    );
    rt.block_on(async {
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        h.close();
        let rc = tokio::time::timeout(WAIT, h.closed()).await.unwrap();
        assert_eq!(rc, zk_proto::rc::ZOK);
    });
}

#[test]
fn read_gate_closes_at_capacity_and_taken_reopens_it() {
    let rt = runtime();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let mut e = env(IpHost::new(), true);
    e.cfg.dispatch_queue_capacity = 1;
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        rt.handle(),
    );
    let results = |s: &Collect| {
        s.0.lock()
            .unwrap()
            .iter()
            .filter(|i| matches!(i, Item::Result { .. }))
            .count()
    };
    rt.block_on(async {
        connected(&h).await;
        h.submit(exists_native(1)).ok().unwrap();
        h.submit(exists_native(2)).ok().unwrap();
        let start = tokio::time::Instant::now();
        while results(&sink) == 0 {
            assert!(start.elapsed() < WAIT, "no result");
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        assert!(!h.gate().is_open());
        // Consuming the dispatched item reopens the read gate so the second response frame can be
        // processed.
        h.taken();
        while results(&sink) < 2 {
            assert!(start.elapsed() < WAIT, "no second result");
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        // Consuming the second item reopens the read gate prior to close so the CLOSE_OP response
        // arrives before the close deadline.
        h.taken();
        h.close();
        assert_eq!(h.closed().await, zk_proto::rc::ZOK);
    });
}

/// Asserts that blocking pool address resolution tasks do not stall socket I/O loops or session
/// teardown.
#[test]
fn a_slow_resolve_does_not_stop_requests_or_the_close() {
    let rt = runtime();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let host = Arc::new(SlowHost {
        inner: IpHost::new(),
        slow_ms: AtomicU64::new(0),
        resolves: AtomicUsize::new(0),
    });
    let mut e = env(IpHost::new(), true);
    e.host = host.clone();
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        rt.handle(),
    );
    let results = |sink: &Collect| {
        sink.0
            .lock()
            .unwrap()
            .iter()
            .filter(|i| matches!(i, Item::Result { .. }))
            .count()
    };
    rt.block_on(async {
        connected(&h).await;
        host.slow_ms.store(1_500, Ordering::SeqCst);
        let before = host.resolves.load(Ordering::SeqCst);
        h.submit(exists_native(1)).ok().unwrap();
        let start = Instant::now();
        while host.resolves.load(Ordering::SeqCst) == before {
            assert!(start.elapsed() < WAIT, "no resolve");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        let t0 = Instant::now();
        h.submit(exists_native(2)).ok().unwrap();
        while results(&sink) < 2 {
            assert!(t0.elapsed() < WAIT, "no result");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert!(
            t0.elapsed() < Duration::from_millis(1_000),
            "the request waited for the resolver: {:?}",
            t0.elapsed()
        );
        let t1 = Instant::now();
        h.close();
        let rc = tokio::time::timeout(WAIT, h.closed()).await.unwrap();
        assert_eq!(rc, zk_proto::rc::ZOK);
        assert!(t1.elapsed() < Duration::from_millis(1_000));
    });
}

/// Asserts that an idle connected session with the legacy resolution delay of 0 does not loop.
/// The completion of a background resolve wakes the driver task. That wake must not start a new
/// routine resolve, because the next completion then wakes the task again without end.
#[test]
fn an_idle_session_with_a_zero_resolve_delay_does_not_loop() {
    let rt = runtime();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let host = Arc::new(SlowHost {
        inner: IpHost::new(),
        slow_ms: AtomicU64::new(0),
        resolves: AtomicUsize::new(0),
    });
    let mut e = env(IpHost::new(), true);
    e.host = host.clone();
    assert_eq!(common::legacy_config("127.0.0.1:1").resolve_delay_ms, 0);
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        rt.handle(),
    );
    rt.block_on(async {
        connected(&h).await;
        // The session timeout is 10 s, so no ping is due in this window and the session is idle.
        tokio::time::sleep(Duration::from_millis(100)).await;
        let before = host.resolves.load(Ordering::SeqCst);
        tokio::time::sleep(Duration::from_millis(500)).await;
        let during = host.resolves.load(Ordering::SeqCst) - before;
        assert!(during <= 1, "{during} resolves while idle");
        h.close();
        let rc = tokio::time::timeout(WAIT, h.closed()).await.unwrap();
        assert_eq!(rc, zk_proto::rc::ZOK);
    });
}

/// Verifies that an unhandled panic in the asynchronous driver task terminates the session,
/// discards pending tokens, and publishes the terminal close status.
#[test]
fn a_panic_in_the_driver_task_releases_the_session() {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(PanicOnSession::default());
    let host = IpHost::new();
    let mut e = env(IpHost::new(), true);
    e.host = host.clone();
    let h = zk_driver::tokio::spawn(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        rt.handle(),
    );
    h.submit(exists_native(1)).ok().unwrap();
    let rc = rt.block_on(async { tokio::time::timeout(WAIT, h.closed()).await });
    assert_eq!(rc.ok(), Some(zk_proto::rc::ZCONNECTIONLOSS));
    assert!(sink.fired.load(Ordering::SeqCst));
    assert!(
        host.discarded().contains(&"completion"),
        "{:?}",
        host.discarded()
    );
}
