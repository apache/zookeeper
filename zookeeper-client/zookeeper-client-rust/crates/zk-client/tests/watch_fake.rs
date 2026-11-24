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

//! Evaluates watcher lifecycle contracts against synthetic server fixtures: one-shot data and
//! child watchers, persistent and recursive watch streams, programmatic watch deregistration,
//! backpressure buffering overflow, session state tracking, and post-reconnection watch
//! restoration.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use apache_zookeeper::{
    AddWatchMode, Client, EventKind, SessionState, StateWatcher, WatchError, WatchedEvent,
    WatcherKind,
};
use zk_fakeserver::{Action, FakeServer, Rule};
use zk_jute::opcode;

use common::{connect, within};

/// Dispatches a synchronous round-trip inquiry to synchronize wire pipeline order, ensuring
/// all preceding asynchronous server events have been processed.
async fn round_trip(c: &Client) {
    within(c.exists("/")).await.expect("round trip");
}

/// Awaits session state transition until matching `want`, returning the observed session epoch.
async fn wait_state(w: &mut StateWatcher, want: SessionState) -> u64 {
    within(async {
        loop {
            let info = w.current();
            if info.state == want {
                return info.epoch;
            }
            let _ = w.changed().await;
        }
    })
    .await
}

/// Asserts whether the server captured a `RemoveWatches` protocol frame for `path` within `wait`.
fn sent_remove(srv: &FakeServer, path: &str, wait: Duration) -> bool {
    srv.wait_for(wait, |r| {
        r.op == Some(opcode::REMOVE_WATCHES) && r.path.as_deref() == Some(path)
    })
    .is_some()
}

fn assert_event(ev: &WatchedEvent, kind: EventKind, path: &str) {
    assert_eq!(ev.kind, kind);
    assert_eq!(ev.path.as_str(), path);
}

#[tokio::test(flavor = "multi_thread")]
async fn an_exist_watch_arms_on_a_missing_node() {
    let srv = common::fake();
    let c = connect(common::builder(&srv.connect_string())).await;
    let (stat, w) = within(c.exists_watched("/a")).await.unwrap();
    assert!(stat.is_none());
    assert_eq!(w.path().as_str(), "/a");
    assert_eq!(w.kind(), WatcherKind::Data);
    srv.cluster().create("/a", b"x", 0).unwrap();
    let ev = within(w).await.unwrap();
    assert_event(&ev, EventKind::NodeCreated, "/a");
    assert!(ev.zxid.is_some_and(|z| z.0 > 0));
}

#[tokio::test(flavor = "multi_thread")]
async fn data_and_child_watches_fire_once() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let (data, _, dw) = within(c.get_data_watched("/a")).await.unwrap();
    assert_eq!(data, b"x");
    let (kids, _, cw) = within(c.get_children_watched("/a")).await.unwrap();
    assert!(kids.is_empty());
    assert_eq!(cw.kind(), WatcherKind::Children);
    srv.cluster().set_data("/a", b"y").unwrap();
    srv.cluster().create("/a/k", b"", 0).unwrap();
    let mut dw = dw;
    let ev = within(&mut dw).await.unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/a");
    // One-shot watchers resolve exactly once; subsequent polls yield `WatchError::Closed`.
    assert_eq!(within(&mut dw).await, Err(WatchError::Closed));
    let ev = within(cw).await.unwrap();
    assert_event(&ev, EventKind::NodeChildrenChanged, "/a");
}

#[tokio::test(flavor = "multi_thread")]
async fn an_event_right_after_the_reply_is_not_lost() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    srv.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .path("/a")
            .once()
            .then(Action::Model)
            .then(Action::Notify {
                event_type: 3,
                state: 3,
                path: "/a".to_owned(),
            }),
    );
    let c = connect(common::builder(&srv.connect_string())).await;
    // Delay polling until wire frame routing delivers event notification.
    let pending = c.get_data_watched("/a");
    round_trip(&c).await;
    let (_, _, w) = within(pending).await.unwrap();
    let ev = within(w).await.unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/a");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_failed_read_arms_no_watcher() {
    let srv = common::fake();
    let c = connect(common::builder(&srv.connect_string())).await;
    let e = within(c.get_data_watched("/missing")).await.unwrap_err();
    assert_eq!(e, apache_zookeeper::Error::NoNode);
    // Operation failure prevents registration; no superfluous server side watch deregistration is
    // dispatched.
    round_trip(&c).await;
    assert!(!sent_remove(&srv, "/missing", Duration::from_millis(200)));
}

#[tokio::test(flavor = "multi_thread")]
async fn remove_sends_remove_watches_for_the_last_user_only() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let (_, _, w1) = within(c.get_data_watched("/a")).await.unwrap();
    let (_, _, w2) = within(c.get_data_watched("/a")).await.unwrap();
    // Multiple local registrations share the wire watch; server deregistration is deferred until
    // all local references drop.
    within(w1.remove()).await.unwrap();
    round_trip(&c).await;
    assert!(!sent_remove(&srv, "/a", Duration::from_millis(200)));
    // Final local watcher removal triggers wire deregistration.
    within(w2.remove()).await.unwrap();
    assert!(sent_remove(&srv, "/a", common::WAIT));
    assert!(
        srv.cluster()
            .wait_until(common::WAIT, |c| c.watch_count() == 0)
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn a_drop_detaches_and_the_last_drop_removes_the_server_watch() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let (_, _, w1) = within(c.get_data_watched("/a")).await.unwrap();
    let (_, _, w2) = within(c.get_data_watched("/a")).await.unwrap();
    drop(w1);
    round_trip(&c).await;
    assert!(!sent_remove(&srv, "/a", Duration::from_millis(200)));
    // Sibling watcher reference retains active event dispatch.
    srv.cluster().set_data("/a", b"y").unwrap();
    let ev = within(w2).await.unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/a");

    let (_, _, w3) = within(c.get_data_watched("/a")).await.unwrap();
    drop(w3);
    assert!(sent_remove(&srv, "/a", common::WAIT));
}

/// Busy-waits on the calling thread until `go` is signaled, then spins for an additional `delay`
/// offset to achieve microsecond-level thread launch synchronization.
fn spin_start(go: &AtomicBool, delay: Duration) {
    while !go.load(Ordering::Acquire) {
        std::hint::spin_loop();
    }
    let at = Instant::now();
    while at.elapsed() < delay {
        std::hint::spin_loop();
    }
}

/// Verifies that dropping the final watcher on a path while concurrently registering a new watch
/// on the same path and watch class never cancels the newly registered watcher.
///
/// Because dropping the final local watcher enqueues a wildcard `RemoveWatches` request, atomic
/// detachment and `xid` assignment under the core lock ensures concurrent registrations receive
/// a strictly ordered `xid`. Sweeps relative thread start offsets across the contention window and
/// asserts that the newly registered watcher remains armed in every iteration.
#[tokio::test(flavor = "multi_thread")]
async fn a_drop_racing_a_new_watch_never_removes_it() {
    const ROUNDS: u32 = 600;
    const STEPS: u32 = 60;
    const STEP: Duration = Duration::from_nanos(250);
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    for round in 0..ROUNDS {
        let (_, _, old) = within(c.get_data_watched("/a")).await.unwrap();
        // Sweep relative launch timing so watch registration leads in the lower half and drop
        // leads in the upper half.
        let step = round % STEPS;
        let (drop_delay, watch_delay) = if step < STEPS / 2 {
            (STEP * (STEPS / 2 - step), Duration::ZERO)
        } else {
            (Duration::ZERO, STEP * (step - STEPS / 2))
        };
        let go = Arc::new(AtomicBool::new(false));
        let dropper = {
            let go = Arc::clone(&go);
            std::thread::spawn(move || {
                spin_start(&go, drop_delay);
                drop(old);
            })
        };
        let watcher = {
            let (go, c) = (Arc::clone(&go), c.clone());
            std::thread::spawn(move || {
                spin_start(&go, watch_delay);
                c.get_data_watched("/a")
            })
        };
        go.store(true, Ordering::Release);
        dropper.join().unwrap();
        let reply = watcher.join().unwrap();
        let (_, _, fresh) = within(reply).await.unwrap();
        round_trip(&c).await;
        srv.cluster()
            .set_data("/a", format!("v{round}").as_bytes())
            .unwrap();
        match within(fresh).await {
            Ok(ev) => assert_event(&ev, EventKind::NodeDataChanged, "/a"),
            Err(e) => panic!("round {round}: the new watcher ended with {e:?}"),
        }
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn a_persistent_watcher_gives_every_event_in_order() {
    let srv = common::fake();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut w = within(c.add_watch("/p", AddWatchMode::Persistent))
        .await
        .unwrap();
    assert_eq!(w.mode(), AddWatchMode::Persistent);
    srv.cluster().create("/p", b"", 0).unwrap();
    srv.cluster().set_data("/p", b"1").unwrap();
    srv.cluster().create("/p/k", b"", 0).unwrap();
    srv.cluster().delete("/p/k").unwrap();
    srv.cluster().delete("/p").unwrap();
    let want = [
        EventKind::NodeCreated,
        EventKind::NodeDataChanged,
        EventKind::NodeChildrenChanged,
        EventKind::NodeChildrenChanged,
        EventKind::NodeDeleted,
    ];
    for kind in want {
        let ev = within(w.next()).await.unwrap().unwrap();
        assert_event(&ev, kind, "/p");
    }
    // Persistent watchers remain active following znode deletion.
    srv.cluster().create("/p", b"", 0).unwrap();
    let ev = within(w.next()).await.unwrap().unwrap();
    assert_event(&ev, EventKind::NodeCreated, "/p");
    within(w.remove()).await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_recursive_watcher_sees_descendants() {
    let srv = common::fake();
    srv.cluster().create("/r", b"", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut w = within(c.add_watch("/r", AddWatchMode::PersistentRecursive))
        .await
        .unwrap();
    srv.cluster().create("/r/x", b"", 0).unwrap();
    srv.cluster().set_data("/r/x", b"1").unwrap();
    srv.cluster().delete("/r/x").unwrap();
    let want = [
        (EventKind::NodeCreated, "/r/x"),
        (EventKind::NodeDataChanged, "/r/x"),
        (EventKind::NodeDeleted, "/r/x"),
    ];
    for (kind, path) in want {
        let ev = within(w.next()).await.unwrap().unwrap();
        assert_event(&ev, kind, path);
    }
    drop(w);
    assert!(sent_remove(&srv, "/r", common::WAIT));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_full_persistent_buffer_gives_lagged() {
    let srv = common::fake();
    srv.cluster().create("/p", b"", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string()).persistent_watch_buffer(2)).await;
    let mut w = within(c.add_watch("/p", AddWatchMode::Persistent))
        .await
        .unwrap();
    for i in 0..5u8 {
        srv.cluster().set_data("/p", &[i]).unwrap();
    }
    round_trip(&c).await;
    let first = within(w.next()).await.unwrap();
    assert_eq!(first, Err(WatchError::Lagged { missed: 3 }));
    for _ in 0..2 {
        let ev = within(w.next()).await.unwrap().unwrap();
        assert_event(&ev, EventKind::NodeDataChanged, "/p");
    }
    // Persistent watcher recovers and continues event delivery after reporting backpressure lag.
    srv.cluster().set_data("/p", b"z").unwrap();
    let ev = within(w.next()).await.unwrap().unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/p");
}

#[tokio::test(flavor = "multi_thread")]
async fn remove_watches_ends_the_local_watchers() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let (_, _, w) = within(c.get_data_watched("/a")).await.unwrap();
    let mut p = within(c.add_watch("/a", AddWatchMode::Persistent))
        .await
        .unwrap();
    within(c.remove_watches("/a", WatcherKind::Data, false))
        .await
        .unwrap();
    assert_eq!(within(w).await, Err(WatchError::Removed));
    within(c.remove_watches("/a", WatcherKind::Persistent, true))
        .await
        .unwrap();
    assert_eq!(within(p.next()).await, Some(Err(WatchError::Removed)));
    assert_eq!(within(p.next()).await, None);
}

#[tokio::test(flavor = "multi_thread")]
async fn watches_are_restored_after_a_reconnect() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut sw = c.state_watcher();
    let epoch = wait_state(&mut sw, SessionState::Connected).await;
    let (_, _, w) = within(c.get_data_watched("/a")).await.unwrap();
    let mut p = within(c.add_watch("/a", AddWatchMode::Persistent))
        .await
        .unwrap();
    srv.drop_connections();
    // Epoch progression disambiguates reconnection transitions during rapid state recovery.
    within(async {
        loop {
            let info = sw.current();
            if info.state == SessionState::Connected && info.epoch > epoch {
                return;
            }
            let _ = sw.changed().await;
        }
    })
    .await;
    let rec = srv
        .wait_for(common::WAIT, |r| r.op == Some(opcode::SET_WATCHES2))
        .expect("SetWatches2 after the reconnect");
    match rec.detail {
        zk_fakeserver::Detail::SetWatches(set) => {
            assert_eq!(set.data, vec!["/a".to_owned()]);
            assert_eq!(set.persistent, vec!["/a".to_owned()]);
        }
        other => panic!("unexpected detail {other:?}"),
    }
    srv.cluster().set_data("/a", b"y").unwrap();
    let ev = within(w).await.unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/a");
    let ev = within(p.next()).await.unwrap().unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/a");
}

/// Returns `n` paths directly under `/` starting with `prefix`, each about 1000 bytes long.
fn long_paths(prefix: &str, n: usize) -> Vec<String> {
    let pad = "x".repeat(1000);
    (0..n).map(|i| format!("/{prefix}{i:03}{pad}")).collect()
}

/// Verifies that reconnecting with 50 one-shot data watches, 50 persistent watches, and 50
/// recursive watches on paths of about 1000 bytes (about 150 KB total, exceeding the 128 KiB
/// `SetWatches` batch) restores them across several `SetWatches2` frames. The client consumes
/// every out-of-band reply without an `xid` mismatch and dispatches a request queued while
/// disconnected after the final frame, the frames together hold every watch path, and the client
/// delivers events for every watch after the reconnect.
#[tokio::test(flavor = "multi_thread")]
async fn watches_are_restored_in_several_set_watches2_frames() {
    let srv = common::fake();
    let (data, persistent, recursive) = (
        long_paths("d", 50),
        long_paths("p", 50),
        long_paths("r", 50),
    );
    for path in &data {
        srv.cluster().create(path, b"", 0).unwrap();
    }
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut sw = c.state_watcher();
    let epoch = wait_state(&mut sw, SessionState::Connected).await;
    let mut oneshots = Vec::new();
    for path in &data {
        let (_, _, w) = within(c.get_data_watched(path.as_str())).await.unwrap();
        oneshots.push(w);
    }
    let mut streams = Vec::new();
    for path in &persistent {
        let w = within(c.add_watch(path.as_str(), AddWatchMode::Persistent));
        streams.push(w.await.unwrap());
    }
    for path in &recursive {
        let w = within(c.add_watch(path.as_str(), AddWatchMode::PersistentRecursive));
        streams.push(w.await.unwrap());
    }

    // Delay server writes on the second connection to hold back its handshake reply, keeping a
    // request submitted after the drop queued until the session is re-established.
    srv.add_rule(
        Rule::on_connect()
            .conn(2)
            .once()
            .then(Action::Delay(Duration::from_millis(300))),
    );
    srv.drop_connections();
    within(async {
        while sw.current().state == SessionState::Connected {
            let _ = sw.changed().await;
        }
    })
    .await;
    let queued = c.exists("/");
    within(queued).await.expect("queued request");
    let info = sw.current();
    assert_eq!(info.state, SessionState::Connected);
    assert!(info.epoch > epoch, "{info:?}");
    round_trip(&c).await;
    // An `xid` mismatch on a `SetWatches2` reply would terminate the connection; an unchanged
    // session epoch confirms none occurred.
    assert_eq!(sw.current().epoch, info.epoch);

    let frames: Vec<_> = srv
        .transcript()
        .into_iter()
        .filter(|r| r.conn_ordinal == 2 && r.op.is_some())
        .collect();
    let ops: Vec<_> = frames.iter().filter_map(|r| r.op).collect();
    let n = ops
        .iter()
        .take_while(|op| **op == opcode::SET_WATCHES2)
        .count();
    assert!(n >= 2, "{ops:?}");
    assert_eq!(ops.get(n), Some(&opcode::EXISTS), "{ops:?}");
    assert!(
        !ops.iter().skip(n).any(|op| *op == opcode::SET_WATCHES2),
        "{ops:?}"
    );
    let mut set = zk_fakeserver::WatchSet::default();
    for r in frames.iter().take(n) {
        match &r.detail {
            zk_fakeserver::Detail::SetWatches(s) => {
                set.data.extend(s.data.iter().cloned());
                set.persistent.extend(s.persistent.iter().cloned());
                set.persistent_recursive
                    .extend(s.persistent_recursive.iter().cloned());
            }
            other => panic!("unexpected detail {other:?}"),
        }
    }
    let sorted = |mut v: Vec<String>| {
        v.sort();
        v
    };
    assert_eq!(sorted(set.data), data);
    assert_eq!(sorted(set.persistent), persistent);
    assert_eq!(sorted(set.persistent_recursive), recursive);

    for path in &data {
        srv.cluster().set_data(path, b"1").unwrap();
    }
    for path in persistent.iter().chain(&recursive) {
        srv.cluster().create(path, b"", 0).unwrap();
    }
    for (w, path) in oneshots.into_iter().zip(&data) {
        let ev = within(w).await.unwrap();
        assert_event(&ev, EventKind::NodeDataChanged, path);
    }
    for (w, path) in streams.iter_mut().zip(persistent.iter().chain(&recursive)) {
        let ev = within(w.next()).await.unwrap().unwrap();
        assert_event(&ev, EventKind::NodeCreated, path);
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn without_restore_a_reconnect_ends_the_watchers() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string()).restore_watches(false)).await;
    let (_, _, w) = within(c.get_data_watched("/a")).await.unwrap();
    let mut p = within(c.add_watch("/a", AddWatchMode::Persistent))
        .await
        .unwrap();
    srv.drop_connections();
    assert_eq!(within(w).await, Err(WatchError::ConnectionLost));
    assert_eq!(
        within(p.next()).await,
        Some(Err(WatchError::ConnectionLost))
    );
    assert_eq!(within(p.next()).await, None);
}

#[tokio::test(flavor = "multi_thread")]
async fn expiry_ends_every_watcher_and_the_state_stays_terminal() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut sw = c.state_watcher();
    let (_, _, w) = within(c.get_data_watched("/a")).await.unwrap();
    let mut p = within(c.add_watch("/a", AddWatchMode::Persistent))
        .await
        .unwrap();
    let (id, _) = c.session();
    assert!(srv.cluster().expire_session(id.0));
    wait_state(&mut sw, SessionState::Expired).await;
    assert_eq!(within(w).await, Err(WatchError::SessionExpired));
    assert_eq!(
        within(p.next()).await,
        Some(Err(WatchError::SessionExpired))
    );
    assert_eq!(within(p.next()).await, None);
    // State watchers resolve immediately upon reaching terminal state.
    for _ in 0..3 {
        let info = within(sw.changed()).await;
        assert_eq!(info.state, SessionState::Expired);
    }
    // Operations attempted against expired sessions fail immediately without registering watches.
    let e = within(c.get_data_watched("/a")).await.unwrap_err();
    assert_eq!(e, apache_zookeeper::Error::SessionExpired);
}

#[tokio::test(flavor = "multi_thread")]
async fn close_ends_every_watcher_with_closed() {
    let srv = common::fake();
    srv.cluster().create("/a", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut sw = c.state_watcher();
    let (_, _, w) = within(c.get_data_watched("/a")).await.unwrap();
    let mut p = within(c.add_watch("/a", AddWatchMode::Persistent))
        .await
        .unwrap();
    within(c.close()).await.unwrap();
    assert_eq!(within(w).await, Err(WatchError::Closed));
    assert_eq!(within(p.next()).await, Some(Err(WatchError::Closed)));
    assert_eq!(within(p.next()).await, None);
    wait_state(&mut sw, SessionState::Closed).await;
}

#[cfg(feature = "stream")]
#[tokio::test(flavor = "multi_thread")]
async fn a_persistent_watcher_is_a_stream() {
    use std::pin::Pin;

    use futures_core::Stream;

    let srv = common::fake();
    srv.cluster().create("/s", b"", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut w = within(c.add_watch("/s", AddWatchMode::Persistent))
        .await
        .unwrap();
    srv.cluster().set_data("/s", b"1").unwrap();
    let item = within(std::future::poll_fn(|cx| Pin::new(&mut w).poll_next(cx))).await;
    assert_event(&item.unwrap().unwrap(), EventKind::NodeDataChanged, "/s");
}

#[cfg(feature = "blocking")]
#[test]
fn the_blocking_waits_take_events() {
    let srv = common::fake();
    srv.cluster().create("/b", b"x", 0).unwrap();
    let c = common::builder(&srv.connect_string())
        .connect()
        .wait()
        .unwrap();
    let mut sw = c.state_watcher();
    assert!(sw.current().state.is_connected());
    let (_, _, w) = c.get_data_watched("/b").wait().unwrap();
    let mut p = c.add_watch("/b", AddWatchMode::Persistent).wait().unwrap();
    srv.cluster().set_data("/b", b"y").unwrap();
    assert_event(&w.wait().unwrap(), EventKind::NodeDataChanged, "/b");
    assert_event(
        &p.recv().unwrap().unwrap(),
        EventKind::NodeDataChanged,
        "/b",
    );
    c.close().wait().unwrap();
    assert_eq!(p.recv(), Some(Err(WatchError::Closed)));
    assert_eq!(p.recv(), None);
    assert_eq!(sw.wait_changed().unwrap().state, SessionState::Closed);
}

/// Validates runtime contract: synchronous blocking operations invoked within an active Tokio
/// executor fail immediately with `InvalidState` to prevent worker thread starvation.
#[cfg(feature = "blocking")]
#[tokio::test(flavor = "multi_thread")]
async fn a_blocking_wait_in_a_runtime_is_refused() {
    let srv = common::fake();
    srv.cluster().create("/b", b"x", 0).unwrap();
    let c = connect(common::builder(&srv.connect_string())).await;
    let mut p = within(c.add_watch("/b", AddWatchMode::Persistent))
        .await
        .unwrap();
    let mut sw = c.state_watcher();
    if cfg!(feature = "tokio") {
        assert_eq!(p.recv(), Some(Err(WatchError::InvalidState)));
        assert_eq!(
            sw.wait_changed().unwrap_err(),
            apache_zookeeper::Error::InvalidState
        );
        let (_, _, w) = within(c.get_data_watched("/b")).await.unwrap();
        assert_eq!(w.wait(), Err(WatchError::InvalidState));
    }
    // Refused blocking call preserves watcher registration for subsequent asynchronous polling.
    srv.cluster().set_data("/b", b"y").unwrap();
    let ev = within(p.next()).await.unwrap().unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, "/b");
}
