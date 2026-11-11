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

//! Verification of the observability metrics hook interface and runtime invariants.
//!
//! Asserts delivery of watch event lag notifications during queue saturation, monotonically
//! decreasing pending request counts during pipeline draining, accurate reflection of operation
//! outcomes across success and failure paths, and safe isolation of client execution when
//! observer callbacks panic.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use apache_zookeeper::{
    AddWatchMode, Error, EventKind, Metrics, OpKind, Runtime, SessionState, ThreadOptions,
    WatchError,
};

use common::{connect, within};

/// Captured invocation record for metric hook callbacks.
#[derive(Clone, Debug, PartialEq)]
enum Call {
    Request(OpKind, Result<(), Error>),
    State(SessionState),
    Event(EventKind),
    Lagged(u64),
    Pending(usize),
}

/// Instrumented [`Metrics`] observer recording callback invocations and supporting simulated
/// callback panics.
#[derive(Default)]
struct Rec {
    calls: Mutex<Vec<Call>>,
    entered: AtomicUsize,
    panic_on_state: bool,
}

#[derive(Clone)]
struct Hook(Arc<Rec>);

impl Hook {
    fn push(&self, c: Call) {
        self.0.entered.fetch_add(1, Ordering::SeqCst);
        self.0.calls.lock().unwrap().push(c);
    }

    fn calls(&self) -> Vec<Call> {
        self.0.calls.lock().unwrap().clone()
    }
}

impl Metrics for Hook {
    fn request_completed(&self, op: OpKind, result: Result<(), &Error>, _latency: Duration) {
        self.push(Call::Request(op, result.map_err(Clone::clone)));
    }
    fn state_changed(&self, state: SessionState) {
        if self.0.panic_on_state {
            self.0.entered.fetch_add(1, Ordering::SeqCst);
            panic!("a test hook panics in state_changed");
        }
        self.push(Call::State(state));
    }
    fn watch_event(&self, kind: EventKind) {
        self.push(Call::Event(kind));
    }
    fn watch_lagged(&self, missed: u64) {
        self.push(Call::Lagged(missed));
    }
    fn pending_requests(&self, n: usize) {
        self.push(Call::Pending(n));
    }
}

fn hook() -> Hook {
    Hook(Arc::new(Rec::default()))
}

/// Asserts that watch event drop counts reported to the metrics hook match values delivered via
/// [`WatchError::Lagged`].
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_dropped_persistent_event_reports_the_lag() {
    let fs = common::fake();
    fs.cluster().create("/p", b"", 0).unwrap();
    let h = hook();
    let c = connect(
        common::builder(&fs.connect_string())
            .persistent_watch_buffer(2)
            .metrics(h.clone()),
    )
    .await;
    let mut w = within(c.add_watch("/p", AddWatchMode::Persistent))
        .await
        .unwrap();
    for i in 0..5u8 {
        fs.cluster().set_data("/p", &[i]).unwrap();
    }
    within(c.sync("/")).await.unwrap();
    assert_eq!(
        within(w.next()).await.unwrap(),
        Err(WatchError::Lagged { missed: 3 })
    );
    let lags: Vec<u64> = h
        .calls()
        .into_iter()
        .filter_map(|c| match c {
            Call::Lagged(n) => Some(n),
            _ => None,
        })
        .collect();
    assert_eq!(lags, vec![1, 2, 3]);
    within(c.close()).await.unwrap();
}

/// Asserts that pending request counter notifications reflect pipeline queue depth and drain to
/// zero upon request completion.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_pending_count_falls_as_replies_arrive() {
    let fs = common::fake();
    let h = hook();
    let c = connect(common::builder(&fs.connect_string()).metrics(h.clone())).await;
    fs.hold();
    let reads: Vec<_> = (0..4).map(|_| c.get_data("/")).collect();
    fs.release();
    for f in reads {
        within(f).await.unwrap();
    }
    // Pending request counts are dispatched asynchronously upon reply delivery; await completion
    // of all notifications.
    let pending = || -> Vec<usize> {
        h.calls()
            .into_iter()
            .filter_map(|c| match c {
                Call::Pending(n) => Some(n),
                _ => None,
            })
            .collect()
    };
    let deadline = std::time::Instant::now() + common::WAIT;
    while pending().len() < 4 && std::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let counts = pending();
    assert_eq!(counts.len(), 4, "{counts:?}");
    assert!(counts.windows(2).all(|w| w[0] >= w[1]), "{counts:?}");
    assert_eq!(counts.last(), Some(&0));
    within(c.close()).await.unwrap();
}

/// Asserts that operation completion metrics record exact high-level outcomes including success,
/// missing node errors, and connection losses.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_hook_sees_the_api_result() {
    let fs = common::fake();
    let h = hook();
    let c = connect(common::builder(&fs.connect_string()).metrics(h.clone())).await;
    assert_eq!(within(c.exists("/none")).await, Ok(None));
    assert_eq!(within(c.get_data("/none")).await.err(), Some(Error::NoNode));
    fs.hold();
    let lost = c.sync("/");
    fs.drop_connections();
    fs.release();
    assert_eq!(within(lost).await, Err(Error::ConnectionLoss));
    // Caller futures resolve prior to invoking metrics callbacks, so await delivery of all three
    // metric notifications.
    let requests = || -> Vec<Call> {
        h.calls()
            .into_iter()
            .filter(|c| matches!(c, Call::Request(..)))
            .collect()
    };
    let deadline = std::time::Instant::now() + common::WAIT;
    while requests().len() < 3 && std::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(
        requests(),
        vec![
            Call::Request(OpKind::Exists, Ok(())),
            Call::Request(OpKind::GetData, Err(Error::NoNode)),
            Call::Request(OpKind::Sync, Err(Error::ConnectionLoss)),
        ]
    );
    within(c.close()).await.unwrap();
}

/// Asserts that panic conditions inside observer callbacks permanently disable subsequent
/// invocations without impacting client operations.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_panic_in_state_changed_disables_the_hook_on_both_runtimes() {
    for thread in [false, true] {
        let fs = common::fake();
        let h = Hook(Arc::new(Rec {
            panic_on_state: true,
            ..Rec::default()
        }));
        let mut b = common::builder(&fs.connect_string()).metrics(h.clone());
        if thread {
            b = b.runtime(Runtime::Thread(ThreadOptions::default()));
        }
        let c = connect(b).await;
        for _ in 0..3 {
            within(c.get_data("/")).await.unwrap();
        }
        fs.drop_connections();
        let mut w = c.state_watcher();
        within(async {
            while w.current().epoch < 2 {
                let _ = w.changed().await;
            }
        })
        .await;
        within(c.close()).await.unwrap();
        assert_eq!(h.0.entered.load(Ordering::SeqCst), 1, "thread {thread}");
        assert!(h.calls().is_empty(), "thread {thread}: {:?}", h.calls());
    }
}

/// Instrumented metrics hook issuing a reentrant client operation upon the initial request
/// completion callback.
#[derive(Default)]
struct ReenterState {
    client: Mutex<Option<apache_zookeeper::Client>>,
    reentered: std::sync::atomic::AtomicBool,
}

#[derive(Clone, Default)]
struct Reenter(Arc<ReenterState>);

impl Metrics for Reenter {
    fn request_completed(&self, _op: OpKind, _result: Result<(), &Error>, _latency: Duration) {
        let taken = self.0.client.lock().unwrap().take();
        if let Some(c) = taken {
            // Request submission occurs eagerly at call time; dropping the future retains the
            // enqueued wire frame.
            drop(c.exists("/reentry"));
            drop(c);
            self.0.reentered.store(true, Ordering::SeqCst);
        }
    }
}

/// Verifies that metrics callbacks executing on the I/O runner can invoke client operations
/// reentrantly without deadlocking across both thread and Tokio runtimes.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_metrics_hook_can_call_the_client_on_the_io_thread() {
    for thread in [false, true] {
        let fs = common::fake();
        let hook = Reenter::default();
        let mut b = common::builder(&fs.connect_string()).metrics(hook.clone());
        if thread {
            b = b.runtime(Runtime::Thread(ThreadOptions::default()));
        }
        let c = connect(b).await;
        *hook.0.client.lock().unwrap() = Some(c.clone());
        for _ in 0..3 {
            within(c.get_data("/")).await.unwrap();
        }
        assert!(
            hook.0.reentered.load(Ordering::SeqCst),
            "thread {thread}: the hook did not call the client"
        );
        within(c.close()).await.unwrap();
        drop(hook.0.client.lock().unwrap().take());
    }
}
