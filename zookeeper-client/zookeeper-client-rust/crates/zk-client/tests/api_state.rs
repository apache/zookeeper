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

//! Verification of session lifecycle transitions, state observation streams, and connection
//! futures.
//!
//! Asserts monotonic epoch incrementation across reconnects, state notification sequences
//! through connection loss and server-driven session expiration, terminal state immutability,
//! confluent state coalescing in [`StateWatcher`], and connection future lifecycle management.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use apache_zookeeper::{
    Client, ConnectFuture, Error, Metrics, Runtime, SessionState, ThreadOptions,
};

use common::{wait_info, within};

/// Instrumented [`Metrics`] observer recording session lifecycle transitions.
#[derive(Clone, Default)]
struct States(Arc<Mutex<Vec<SessionState>>>);

impl Metrics for States {
    fn state_changed(&self, state: SessionState) {
        self.0.lock().unwrap().push(state);
    }
}

impl States {
    fn get(&self) -> Vec<SessionState> {
        self.0.lock().unwrap().clone()
    }
}

/// Allocates an ephemeral local TCP port guaranteed to reject connection attempts.
fn refused_port() -> u16 {
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    l.local_addr().unwrap().port()
}

/// Asserts chronological session state transitions across disconnects, epoch increments, and
/// terminal session expiration.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_state_sequence_through_a_loss_and_an_expiry() {
    let fs = common::fake();
    let states = States::default();
    let c = within(
        common::builder(&fs.connect_string())
            .metrics(states.clone())
            .connect(),
    )
    .await
    .unwrap();
    let mut w = c.state_watcher();
    let first = w.current();
    assert_eq!(first.state, SessionState::Connected);
    assert_eq!(first.epoch, 1);
    assert!(first.disconnected_since.is_none());

    fs.drop_connections();
    let lost = wait_info(&mut w, |i| i.state == SessionState::Connecting).await;
    assert!(lost.disconnected_since.is_some(), "{lost:?}");
    assert!(lost.last_error.is_some(), "{lost:?}");
    let back = wait_info(&mut w, |i| i.state == SessionState::Connected).await;
    assert_eq!(back.epoch, 2);
    assert!(back.disconnected_since.is_none());
    // Diagnostic disconnect causes are retained across reconnection boundaries.
    assert!(back.last_error.is_some(), "{back:?}");

    let (id, _) = c.session();
    fs.cluster().expire_session(id.0);
    let gone = wait_info(&mut w, |i| i.state == SessionState::Expired).await;
    assert!(gone.state.is_terminal());
    assert_eq!(within(c.get_data("/")).await, Err(Error::SessionExpired));
    let _ = within(c.close()).await;
    assert_eq!(c.state().state, SessionState::Expired);
    // Invocations of `changed` on terminal states resolve immediately without awaiting subsequent
    // mutations.
    assert_eq!(within(w.changed()).await.state, SessionState::Expired);

    // server side session expiration severs active connections; an intermediate `Connecting` state
    // may precede `Expired`.
    let mut seen: Vec<SessionState> = states
        .get()
        .into_iter()
        .skip_while(|s| *s == SessionState::Connecting)
        .collect();
    if seen.len() == 5 && seen[3] == SessionState::Connecting {
        seen.remove(3);
    }
    assert_eq!(
        seen,
        vec![
            SessionState::Connected,
            SessionState::Connecting,
            SessionState::Connected,
            SessionState::Expired,
        ]
    );
}

/// Asserts that graceful client closure transitions session state to the terminal `Closed` state
/// across all runtimes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn close_ends_in_closed_on_both_runtimes() {
    for thread in [false, true] {
        let fs = common::fake();
        let states = States::default();
        let mut b = common::builder(&fs.connect_string()).metrics(states.clone());
        if thread {
            b = b.runtime(Runtime::Thread(ThreadOptions::default()));
        }
        let c = within(b.connect()).await.unwrap();
        let mut w = c.state_watcher();
        within(c.close()).await.unwrap();
        let end = wait_info(&mut w, |i| i.state.is_terminal()).await;
        assert_eq!(end.state, SessionState::Closed);
        assert!(!end.state.is_connected());
        assert_eq!(states.get().last(), Some(&SessionState::Closed));
        assert_eq!(within(w.changed()).await.state, SessionState::Closed);
    }
}

/// Asserts that [`StateWatcher`] operates as a conflating watch cell, delivering current session
/// epochs to lagging observers.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_late_watcher_sees_the_reconnect_by_the_epoch() {
    let fs = common::fake();
    let c = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    let mut late = c.state_watcher();
    let mut eager = c.state_watcher();
    fs.drop_connections();
    wait_info(&mut eager, |i| i.epoch == 2 && i.state.is_connected()).await;
    // Watcher notification resolves with the latest state snapshot, coalescing intermediate
    // reconnect epochs.
    let info = within(late.changed()).await;
    assert_eq!(info.epoch, 2);
    assert_eq!(info.state, SessionState::Connected);
    within(c.close()).await.unwrap();
}

/// Asserts that [`ConnectFuture`] remains pending while ensemble endpoints are unreachable and
/// resolves upon server availability.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn connect_waits_for_an_unreachable_server() {
    let mut fs = common::fake();
    let cs = fs.connect_string();
    fs.stop();
    let f: ConnectFuture = common::builder(&cs)
        .connect_timeout(Duration::from_millis(200))
        .connect();
    let w = f.state_watcher();
    let mut f = Box::pin(f);
    // Future remains pending while connection attempts encounter connection refusal.
    assert!(
        tokio::time::timeout(Duration::from_millis(400), &mut f)
            .await
            .is_err()
    );
    let info = w.current();
    assert_eq!(info.state, SessionState::Connecting);
    assert_eq!(info.epoch, 0);
    let err = info.last_error.expect("a failed connect sets last_error");
    assert!(err.downcast_ref::<std::io::Error>().is_some(), "{err:?}");
    fs.resume().unwrap();
    let c: Client = within(f).await.unwrap();
    let info = c.state();
    assert_eq!(info.state, SessionState::Connected);
    assert_eq!(info.epoch, 1);
    within(c.close()).await.unwrap();
}

/// Asserts that synchronous blocking connection attempts block the calling thread until session
/// establishment.
#[cfg(feature = "blocking")]
#[test]
fn a_blocking_connect_waits_for_the_server() {
    let mut fs = common::fake();
    let cs = fs.connect_string();
    fs.stop();
    let f = common::builder(&cs)
        .connect_timeout(Duration::from_millis(200))
        .runtime(Runtime::Thread(ThreadOptions::default()))
        .connect();
    let mut w = f.state_watcher();
    let t = std::thread::spawn(move || f.wait());
    let t0 = std::time::Instant::now();
    while w.current().last_error.is_none() {
        assert!(t0.elapsed() < common::WAIT, "no failed connect");
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(!t.is_finished());
    fs.resume().unwrap();
    let c = t.join().unwrap().unwrap();
    // The watcher awaits the initial state transition, unblocking upon session connection.
    let info = w.wait_changed().unwrap();
    assert_eq!(info.state, SessionState::Connected);
    assert!(c.state().state.is_connected());
    c.close().wait().unwrap();
}

/// Asserts that dropping an unresolved [`ConnectFuture`] cancels connection establishment and
/// transitions state to `Closed`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dropped_connect_future_closes() {
    let f = common::builder(&format!("127.0.0.1:{}", refused_port()))
        .connect_timeout(Duration::from_millis(200))
        .connect();
    let mut w = f.state_watcher();
    drop(f);
    let end = wait_info(&mut w, |i| i.state.is_terminal()).await;
    assert_eq!(end.state, SessionState::Closed);
}
