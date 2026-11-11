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

//! Fault-injection tests evaluating client capacity bounds and lifecycle teardown contracts:
//! bounded pending queue enforcement, close operation timeouts, queued request draining,
//! and automated RAII session termination upon drop of the final client handle.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::{Duration, Instant};

use apache_zookeeper::{Client, Error, SessionState};
use zk_fakeserver::{Action, FakeServer, FrameKind, Rule};
use zk_jute::opcode;

use common::{thread, within};

/// Computes the tally of captured request frames matching opcode `op`.
fn handled(fs: &FakeServer, op: i32) -> usize {
    fs.transcript()
        .iter()
        .filter(|r| r.kind == FrameKind::Request && r.op == Some(op))
        .count()
}

/// Validates pending request bound enforcement. Requests submitted when `max_pending_requests`
/// is saturated fail immediately with `Error::TooManyPendingRequests` without transmitting wire
/// frames, with slots recycled as preceding requests resolve.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_pending_bound_refuses_and_sends_nothing() {
    const LIMIT: usize = 3;
    let fs = common::fake();
    let c = within(
        common::builder(&fs.connect_string())
            .max_pending_requests(LIMIT)
            .connect(),
    )
    .await
    .unwrap();
    fs.hold();
    let held: Vec<_> = (0..LIMIT).map(|_| c.get_data("/")).collect();
    assert_eq!(c.stats().pending, LIMIT);
    let refused = c.get_data("/");
    assert!(refused.is_ready(), "the refusal must be at once");
    assert_eq!(refused.await, Err(Error::TooManyPendingRequests));
    // A refusal does not use a place.
    assert_eq!(c.stats().pending, LIMIT);
    fs.release();
    for f in held {
        within(f).await.unwrap();
    }
    assert_eq!(handled(&fs, opcode::GET_DATA), LIMIT);
    // The places are free again.
    within(c.get_data("/")).await.unwrap();
    assert_eq!(c.stats().pending, 0);
    within(c.close()).await.unwrap();
}

/// Validates close timeout bounds. When the `CloseSession` acknowledgment exceeds `close_timeout`,
/// `close()` aborts with `Error::OperationTimeout`, transitions the session to
/// `SessionState::Closed`, and fails remaining pending futures with `Error::Closing`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn close_stops_at_the_close_timeout() {
    let fs = common::fake();
    let c = within(
        common::builder(&fs.connect_string())
            .close_timeout(Duration::from_millis(500))
            .connect(),
    )
    .await
    .unwrap();
    fs.hold();
    let pending = c.get_data("/");
    let t0 = Instant::now();
    let r = within(c.close()).await;
    let took = t0.elapsed();
    assert_eq!(r, Err(Error::OperationTimeout));
    assert!(
        took >= Duration::from_millis(450) && took < Duration::from_millis(2_000),
        "the close took {took:?}"
    );
    assert_eq!(within(pending).await, Err(Error::Closing));
    assert_eq!(c.state().state, SessionState::Closed);
    // Operations dispatched following client closure are rejected immediately with Error::Closing.
    assert_eq!(within(c.get_data("/")).await, Err(Error::Closing));
    fs.release();
}

/// Verifies graceful shutdown pipeline ordering. Pending queued operations are transmitted and
/// drained prior to the terminal `CloseSession` frame.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn close_drains_the_queued_requests() {
    let fs = common::fake();
    fs.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .once()
            .then(Action::Delay(Duration::from_millis(200))),
    );
    let c = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    let reads: Vec<_> = (0..3).map(|_| c.get_data("/")).collect();
    let r = within(c.close()).await;
    assert_eq!(r, Ok(()));
    for f in reads {
        within(f).await.unwrap();
    }
    // The server got the `CloseSession` behind the reads.
    let ops: Vec<i32> = fs
        .transcript()
        .iter()
        .filter(|r| r.kind == FrameKind::Request)
        .filter_map(|r| r.op)
        .filter(|op| *op == opcode::GET_DATA || *op == opcode::CLOSE)
        .collect();
    assert_eq!(
        ops,
        vec![
            opcode::GET_DATA,
            opcode::GET_DATA,
            opcode::GET_DATA,
            opcode::CLOSE
        ]
    );
    assert!(fs.cluster().sessions().is_empty());
}

/// Validates disconnected session termination. Calling `close()` during disconnected state
/// transitions to `SessionState::Closed` without attempting remote network transmission.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn close_while_disconnected_sends_nothing() {
    let mut fs = common::fake();
    let c = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    fs.stop();
    let mut w = c.state_watcher();
    within(async {
        while w.current().state == SessionState::Connected {
            w.changed().await;
        }
    })
    .await;
    let t0 = Instant::now();
    let _ = within(c.close()).await;
    assert!(t0.elapsed() < Duration::from_secs(2), "{:?}", t0.elapsed());
    assert_eq!(c.state().state, SessionState::Closed);
    assert_eq!(handled(&fs, opcode::CLOSE), 0);
    assert_eq!(fs.cluster().sessions().len(), 1);
}

/// Verifies RAII drop teardown. Dropping the final `Client` clone initiates background session
/// closure, draining in-flight requests and transmitting `CloseSession` across all runtime
/// drivers.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_last_drop_drains_and_closes_on_both_runtimes() {
    for rt in [None, Some(thread())] {
        let fs = common::fake();
        let mut b = common::builder(&fs.connect_string());
        if let Some(rt) = rt {
            b = b.runtime(rt);
        }
        let c = within(b.connect()).await.unwrap();
        let mut w = c.state_watcher();
        fs.hold();
        let pending = c.get_data("/");
        drop(c);
        fs.release();
        assert!(within(pending).await.is_ok());
        within(async {
            while w.current().state != SessionState::Closed {
                w.changed().await;
            }
        })
        .await;
        assert_eq!(handled(&fs, opcode::CLOSE), 1);
        assert!(
            fs.cluster()
                .wait_until(common::WAIT, |cl| cl.sessions().is_empty())
        );
    }
}

/// Asserts reference counting lifecycle semantics. Dropping individual `Client` handles maintains
/// the active session while sibling handles persist, whereas `StateWatcher` instances do not
/// prevent session cleanup.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_state_watcher_does_not_keep_the_session() {
    let fs = common::fake();
    let c = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    let mut w = c.state_watcher();
    let f = c.sync("/");
    drop(c);
    within(f).await.unwrap();
    within(async {
        while w.current().state != SessionState::Closed {
            w.changed().await;
        }
    })
    .await;
    assert!(
        fs.cluster()
            .wait_until(common::WAIT, |cl| cl.sessions().is_empty())
    );
}

/// Verifies mass queue invalidation. When connection loss occurs with a full pending queue, all
/// pending requests fail concurrently with `Error::ConnectionLoss` and release quota capacity.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_loss_frees_every_pending_place() {
    const LIMIT: usize = 64;
    let fs = common::fake();
    let c: Client = within(
        common::builder(&fs.connect_string())
            .max_pending_requests(LIMIT)
            .connect(),
    )
    .await
    .unwrap();
    fs.hold();
    let held: Vec<_> = (0..LIMIT).map(|_| c.get_data("/")).collect();
    assert_eq!(c.get_data("/").await, Err(Error::TooManyPendingRequests));
    fs.drop_connections();
    fs.release();
    for f in held {
        assert_eq!(within(f).await, Err(Error::ConnectionLoss));
    }
    within(c.ready()).await.unwrap();
    within(c.get_data("/")).await.unwrap();
    assert_eq!(c.stats().pending, 0);
    within(c.close()).await.unwrap();
}
