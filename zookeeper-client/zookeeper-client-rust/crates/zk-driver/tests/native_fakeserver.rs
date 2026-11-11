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

//! Verification of the native threaded driver ([`crate::native`]) against `zk-fakeserver`.
//!
//! Asserts execution invariants across background threaded architectures:
//! - Connection establishment, request dispatch, and graceful session termination.
//! - Nonblocking I/O loop operation decoupled from background DNS resolution latency.
//! - Dynamic flow control and backpressure read gate saturation.

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
    Collect, IpHost, PanicOnSession, SlowHost, TestSpawner, WAIT, env, exists_native,
    native_connection,
};
use zk_driver::native::start;
use zk_driver::{HostLeaves, StdHostLeaves, Unlocked};
use zk_fakeserver::FakeServer;
use zk_proto::{HostSpec, State};
use zk_testkit::tokens::TestTokens;

#[test]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
fn session_connects_routes_a_result_and_closes() {
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let h = start(
        native_connection(&server.connect_string()),
        env(IpHost::new(), true),
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    assert!(common::wait_until(WAIT, || h.state() == State::Connected.code()));
    h.submit(exists_native(1)).ok().unwrap();
    assert!(common::wait_until(WAIT, || sink.results() == 1));
    h.close();
    assert_eq!(h.wait_closed(WAIT), Some(zk_proto::rc::ZOK));
}

/// Asserts that background hostname resolution latency does not impede active socket I/O or
/// graceful teardown.
#[test]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
fn a_slow_resolve_does_not_stop_requests_or_the_close() {
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let host = Arc::new(SlowHost {
        inner: IpHost::new(),
        slow_ms: AtomicU64::new(0),
        resolves: AtomicUsize::new(0),
    });
    let mut e = env(IpHost::new(), true);
    e.host = host.clone();
    let h = start(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    assert!(common::wait_until(WAIT, || h.state() == State::Connected.code()));
    host.slow_ms.store(1_500, Ordering::SeqCst);
    let before = host.resolves.load(Ordering::SeqCst);
    h.submit(exists_native(1)).ok().unwrap();
    // Request submission wakes the I/O loop and initiates asynchronous host resolution.
    assert!(common::wait_until(WAIT, || host
        .resolves
        .load(Ordering::SeqCst)
        > before));
    let t0 = Instant::now();
    h.submit(exists_native(2)).ok().unwrap();
    assert!(common::wait_until(WAIT, || sink.results() == 2));
    assert!(
        t0.elapsed() < Duration::from_millis(1_000),
        "the request waited for the resolver: {:?}",
        t0.elapsed()
    );
    let t1 = Instant::now();
    h.close();
    assert_eq!(h.wait_closed(WAIT), Some(zk_proto::rc::ZOK));
    assert!(t1.elapsed() < Duration::from_millis(1_000));
}

#[test]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
fn read_gate_closes_at_capacity() {
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(Collect::default());
    let mut e = env(IpHost::new(), true);
    e.cfg.dispatch_queue_capacity = 2;
    let h = start(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    assert!(common::wait_until(WAIT, || h.state() == State::Connected.code()));
    for id in 1..=4 {
        h.submit(exists_native(id)).ok().unwrap();
    }
    assert!(common::wait_until(WAIT, || sink.results() >= 2));
    assert!(!h.gate().is_open());
    h.taken();
    h.taken();
    assert!(common::wait_until(WAIT, || sink.results() == 4));
    // Consuming the remaining items reopens the read gate so the CLOSE_OP response is processed
    // before the close deadline.
    h.taken();
    h.taken();
    h.close();
    assert_eq!(h.wait_closed(WAIT), Some(zk_proto::rc::ZOK));
}

/// Verifies that an unhandled panic on the I/O worker terminates the session, discards pending
/// tokens, and publishes the terminal close status.
#[test]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
fn a_panic_on_the_io_thread_releases_the_session() {
    let server = FakeServer::start().unwrap();
    let sink = Arc::new(PanicOnSession::default());
    let host = IpHost::new();
    let mut e = env(IpHost::new(), true);
    e.host = host.clone();
    let h = start(
        native_connection(&server.connect_string()),
        e,
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    h.submit(exists_native(1)).ok().unwrap();
    assert_eq!(h.wait_closed(WAIT), Some(zk_proto::rc::ZCONNECTIONLOSS));
    assert!(sink.fired.load(Ordering::SeqCst));
    assert_eq!(h.state(), 0);
    assert!(
        host.discarded().contains(&"completion"),
        "{:?}",
        host.discarded()
    );
    assert!(h.submit(exists_native(2)).is_err());
}

#[test]
#[ignore = "environment: needs a resolver that knows localhost"]
fn std_host_leaves_resolve_localhost() {
    let leaves = StdHostLeaves::new();
    let spec = HostSpec::Native(zk_proto::legacy::connect::NativeHost {
        host: "localhost".into(),
        port: 2181,
    });
    let r = HostLeaves::<TestTokens>::resolve(&leaves, &Unlocked::new_at_entry(), 0, &spec);
    assert!(r.result.unwrap().iter().all(|a| a.port() == 2181));
}
