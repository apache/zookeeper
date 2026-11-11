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

//! Watch subsystem integration tests against real ZooKeeper server instances.
//!
//! Validates one-shot watch triggers, explicit deregistration via `remove_watches`,
//! persistent and recursive watch streams, client buffer overflow handling (`WatchError::Lagged`),
//! and automatic watch state restoration across session reconnections (`SetWatches`).
//!
//! Shared scenarios execute against a persistent server instance at port offset 11;
//! server restart scenarios manage a dedicated instance at port offset 12.
//! Run with: `ZK_TEST_PORT_BASE=<base> cargo test --test real_watch -- --ignored`.

// Test scenarios execute extended asynchronous flows on dedicated worker threads;
// stack size limits configured for the core library do not apply.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    AddWatchMode, Client, CreateMode, Error, EventKind, PersistentWatcher, SessionState,
    WatchError, WatchedEvent, WatcherKind, Zxid,
};
use common::proxy::Proxy;
use common::{Ctx, Rt, SharedServer, WAIT, setup, wait_state, within};

/// Shared server instance backing multi-client watch tests in this module.
static SERVER: SharedServer = SharedServer::new(11);

/// Executes a test scenario across both OS thread and Tokio drivers on a multithreaded runtime.
macro_rules! real_test {
    ($name:ident) => {
        mod $name {
            use super::*;

            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn thread() {
                if let Some(ctx) = Ctx::new(&SERVER, stringify!($name), Rt::Thread) {
                    super::$name(ctx).await;
                }
            }

            #[cfg(all(unix, feature = "tokio"))]
            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn tokio() {
                if let Some(ctx) = Ctx::new(&SERVER, stringify!($name), Rt::Tokio) {
                    super::$name(ctx).await;
                }
            }
        }
    };
}

/// Creates a persistent empty znode at the specified path.
async fn mk(c: &Client, path: &str) {
    within(c.create(path, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
}

/// Asserts that a watch event matches the expected event kind and target path, verifying presence
/// of a valid zxid.
fn assert_event(ev: &WatchedEvent, kind: EventKind, path: &str) {
    assert_eq!((ev.kind, ev.path.as_str()), (kind, path), "{ev:?}");
    assert!(ev.zxid.is_some(), "{ev:?}");
}

/// Asserts that a watch event fired server side during SetWatches reconstruction matches
/// expectations. The ZooKeeper server omits ZXID values for watches retriggered immediately
/// during SetWatches processing.
fn assert_restored(ev: &WatchedEvent, kind: EventKind, path: &str) {
    assert_eq!(
        (ev.kind, ev.path.as_str(), ev.zxid),
        (kind, path, None),
        "{ev:?}"
    );
}

/// Awaits and yields the next `WatchedEvent` from a persistent watcher stream.
async fn next_event(w: &mut PersistentWatcher) -> WatchedEvent {
    within(w.next())
        .await
        .expect("watcher ended")
        .expect("event")
}

// ---- one-shot watches ----

real_test!(oneshot_watches_fire_once);
/// Invariant: standard data, existence, and child watches fire exactly once upon corresponding
/// state mutations. Existence watches armed on nonexistent paths fire upon subsequent node
/// creation.
async fn oneshot_watches_fire_once(ctx: Ctx) {
    let c = setup(&ctx).await;
    let other = ctx.connect().await;
    let d = ctx.path("/d");
    let e = ctx.path("/e");
    mk(&c, &d).await;

    // Data watch triggers upon data content modification.
    let (_, st, w) = within(c.get_data_watched(&d)).await.unwrap();
    assert_eq!(w.kind(), WatcherKind::Data);
    assert_eq!(w.path().as_str(), d);
    let st2 = within(other.set_data(&d, b"1", None)).await.unwrap();
    let ev = within(w).await.unwrap();
    assert_event(&ev, EventKind::NodeDataChanged, &d);
    assert_eq!(ev.zxid, Some(st2.mzxid));
    assert!(st2.mzxid > st.mzxid);

    // Existence watch on a nonexistent path triggers when the node is created.
    let (none, w) = within(c.exists_watched(&e)).await.unwrap();
    assert!(none.is_none());
    within(other.create(&e, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_event(&within(w).await.unwrap(), EventKind::NodeCreated, &e);

    // Existence watch on an existing node triggers when the node is deleted.
    let (some, w) = within(c.exists_watched(&e)).await.unwrap();
    assert!(some.is_some());
    within(other.delete(&e, None)).await.unwrap();
    assert_event(&within(w).await.unwrap(), EventKind::NodeDeleted, &e);

    // Child watch on a parent path triggers when immediate child nodes are added or removed.
    let (kids, _, w) = within(c.get_children_watched(&ctx.root)).await.unwrap();
    assert_eq!(kids, vec!["d".to_owned()]);
    assert_eq!(w.kind(), WatcherKind::Children);
    mk(&other, &e).await;
    let ev = within(w).await.unwrap();
    assert_event(&ev, EventKind::NodeChildrenChanged, &ctx.root);

    // Data watch triggers upon node deletion. Parent child watch fires concurrently in the same
    // transaction.
    let (_, _, wd) = within(c.get_data_watched(&e)).await.unwrap();
    let (_, _, wc) = within(c.get_children_watched(&ctx.root)).await.unwrap();
    within(other.delete(&e, None)).await.unwrap();
    let ev_d = within(wd).await.unwrap();
    let ev_c = within(wc).await.unwrap();
    assert_event(&ev_d, EventKind::NodeDeleted, &e);
    assert_event(&ev_c, EventKind::NodeChildrenChanged, &ctx.root);
    assert_eq!(ev_d.zxid, ev_c.zxid);

    // A single server side watch registration multiplexes notifications to multiple local watcher
    // handles.
    let (_, _, w1) = within(c.get_data_watched(&d)).await.unwrap();
    let (_, _, w2) = within(c.get_data_watched(&d)).await.unwrap();
    within(other.set_data(&d, b"2", None)).await.unwrap();
    assert_event(&within(w1).await.unwrap(), EventKind::NodeDataChanged, &d);
    assert_event(&within(w2).await.unwrap(), EventKind::NodeDataChanged, &d);
}

real_test!(remove_watches_end_the_watchers);
/// Invariant: explicit watch removal via `remove_watches` concludes targeted local watchers with
/// `WatchError::Removed`, returning `Error::NoWatcher` if no matching watch exists on the server.
/// `OneshotWatcher::remove` deregisters from the server only when the last local consumer
/// unregisters.
async fn remove_watches_end_the_watchers(ctx: Ctx) {
    let c = setup(&ctx).await;
    let other = ctx.connect().await;
    let r = ctx.path("/r");
    mk(&c, &r).await;

    let (_, _, w) = within(c.get_data_watched(&r)).await.unwrap();
    within(c.remove_watches(&r, WatcherKind::Data, false))
        .await
        .unwrap();
    assert_eq!(within(w).await, Err(WatchError::Removed));
    assert_eq!(
        within(c.remove_watches(&r, WatcherKind::Data, false)).await,
        Err(Error::NoWatcher)
    );

    // WatcherKind::Any removes both data and child watch registrations simultaneously.
    let (_, _, wd) = within(c.get_data_watched(&r)).await.unwrap();
    let (_, _, wc) = within(c.get_children_watched(&r)).await.unwrap();
    within(c.remove_watches(&r, WatcherKind::Any, false))
        .await
        .unwrap();
    assert_eq!(within(wd).await, Err(WatchError::Removed));
    assert_eq!(within(wc).await, Err(WatchError::Removed));

    // Deregistering one of multiple local watchers on a path does not cancel the underlying
    // server watch.
    let (_, _, w1) = within(c.get_data_watched(&r)).await.unwrap();
    let (_, _, w2) = within(c.get_data_watched(&r)).await.unwrap();
    within(w1.remove()).await.unwrap();
    within(other.set_data(&r, b"1", None)).await.unwrap();
    assert_event(&within(w2).await.unwrap(), EventKind::NodeDataChanged, &r);

    // Deregistering the final local watcher on a path transmits a server watch cancellation
    // request.
    let (_, _, w) = within(c.get_data_watched(&r)).await.unwrap();
    within(w.remove()).await.unwrap();
    assert_eq!(
        within(c.remove_watches(&r, WatcherKind::Data, false)).await,
        Err(Error::NoWatcher)
    );

    // Specifying local_only concludes local watcher handles without transmitting a server request.
    let (_, _, w) = within(c.get_children_watched(&r)).await.unwrap();
    within(c.remove_watches(&r, WatcherKind::Children, true))
        .await
        .unwrap();
    assert_eq!(within(w).await, Err(WatchError::Removed));

    // Attempting to remove persistent watches using an incompatible WatcherKind variant is
    // rejected.
    let mut p = within(c.add_watch(&r, AddWatchMode::Persistent))
        .await
        .unwrap();
    assert_eq!(
        within(c.remove_watches(&r, WatcherKind::PersistentRecursive, false)).await,
        Err(Error::NoWatcher)
    );
    within(c.remove_watches(&r, WatcherKind::Persistent, false))
        .await
        .unwrap();
    assert_eq!(within(p.next()).await, Some(Err(WatchError::Removed)));
    assert_eq!(within(p.next()).await, None);
}

// ---- persistent watches ----

real_test!(persistent_watch_event_sets);
/// Invariant: persistent watches receive continuous notifications for all mutations at the
/// specified path, including child changes. Persistent recursive watches monitor the entire
/// descendant subtree for data and lifecycle mutations without generating parent child change
/// events. Events arrive in strict ZXID sequence.
async fn persistent_watch_event_sets(ctx: Ctx) {
    let c = setup(&ctx).await;
    let other = ctx.connect().await;
    let p = ctx.path("/p");
    let k = ctx.path("/p/c");
    let g = ctx.path("/p/c/g");

    // Persistent and recursive watches successfully arm against nonexistent target paths.
    let mut wp = within(c.add_watch(&p, AddWatchMode::Persistent))
        .await
        .unwrap();
    let mut wr = within(c.add_watch(&p, AddWatchMode::PersistentRecursive))
        .await
        .unwrap();
    assert_eq!(wp.mode(), AddWatchMode::Persistent);
    assert_eq!(wr.path().as_str(), p);

    mk(&other, &p).await;
    within(other.set_data(&p, b"1", None)).await.unwrap();
    mk(&other, &k).await;
    within(other.set_data(&k, b"1", None)).await.unwrap();
    mk(&other, &g).await;
    within(other.delete(&g, None)).await.unwrap();
    within(other.delete(&k, None)).await.unwrap();
    within(other.delete(&p, None)).await.unwrap();
    // Persistent registrations remain armed following node deletion.
    mk(&other, &p).await;

    use EventKind::{NodeChildrenChanged, NodeCreated, NodeDataChanged, NodeDeleted};
    let want_p = [
        (NodeCreated, &p),
        (NodeDataChanged, &p),
        (NodeChildrenChanged, &p),
        (NodeChildrenChanged, &p),
        (NodeDeleted, &p),
        (NodeCreated, &p),
    ];
    let want_r = [
        (NodeCreated, &p),
        (NodeDataChanged, &p),
        (NodeCreated, &k),
        (NodeDataChanged, &k),
        (NodeCreated, &g),
        (NodeDeleted, &g),
        (NodeDeleted, &k),
        (NodeDeleted, &p),
        (NodeCreated, &p),
    ];
    for (w, want) in [(&mut wp, &want_p[..]), (&mut wr, &want_r[..])] {
        let mut last = Zxid(0);
        for (kind, path) in want {
            let ev = next_event(w).await;
            assert_event(&ev, *kind, path);
            let z = ev.zxid.unwrap();
            assert!(z > last, "{ev:?} after {last:?}");
            last = z;
        }
    }

    // Deregister the standard persistent watcher handle, then deregister the recursive watch via
    // Client.
    within(wp.remove()).await.unwrap();
    within(c.remove_watches(&p, WatcherKind::PersistentRecursive, false))
        .await
        .unwrap();
    assert_eq!(within(wr.next()).await, Some(Err(WatchError::Removed)));
    assert_eq!(within(wr.next()).await, None);
}

real_test!(full_buffer_gives_lagged);
/// Invariant: when a persistent watcher's internal buffer capacity is exceeded, subsequent event
/// deliveries are dropped; the stream emits `WatchError::Lagged { missed }` signaling the overrun,
/// delivers buffered events, and remains active for future events.
async fn full_buffer_gives_lagged(ctx: Ctx) {
    let c = within(ctx.builder().persistent_watch_buffer(2).connect())
        .await
        .unwrap();
    mk(&c, &ctx.root).await;
    let other = ctx.connect().await;
    let mut w = within(c.add_watch(&ctx.root, AddWatchMode::Persistent))
        .await
        .unwrap();
    let mut zxids = Vec::new();
    for i in 0..5u8 {
        let st = within(other.set_data(&ctx.root, &[i][..], None))
            .await
            .unwrap();
        zxids.push(st.mzxid);
    }
    // Executing sync ensures all preceding server side watch events have reached the client queue.
    within(c.sync(&ctx.root)).await.unwrap();
    assert_eq!(
        within(w.next()).await,
        Some(Err(WatchError::Lagged { missed: 3 }))
    );
    for z in zxids.iter().take(2) {
        let ev = next_event(&mut w).await;
        assert_event(&ev, EventKind::NodeDataChanged, &ctx.root);
        assert_eq!(ev.zxid, Some(*z));
    }
    let st = within(other.set_data(&ctx.root, b"z", None)).await.unwrap();
    let ev = next_event(&mut w).await;
    assert_eq!(ev.zxid, Some(st.mzxid));
}

// ---- restore after a reconnect ----

real_test!(reconnect_restores_watches);
/// Invariant: upon session reconnection, `SetWatches` reregisters all active watches using the
/// last observed ZXID watermark. The server immediately fires one-shot watches whose triggering
/// mutations occurred during the disconnection window. Persistent watches are rearmed without
/// retroactively replaying missed events. If `restore_watches(false)` is configured, disconnected
/// watchers fail with `ConnectionLost`.
async fn reconnect_restores_watches(ctx: Ctx) {
    let other = setup(&ctx).await;
    let m = ctx.path("/m");
    let n = ctx.path("/n");
    let q = ctx.path("/q");
    let u = ctx.path("/u");
    for x in [&m, &q, &u] {
        mk(&other, x).await;
    }
    let proxy = Proxy::start(ctx.port());
    let via = |restore: bool| {
        ctx.rt
            .apply(common::builder(&proxy.connect_string()).restore_watches(restore))
    };
    let c = within(via(true).connect()).await.unwrap();
    let plain = within(via(false).connect()).await.unwrap();

    let (_, _, w_data) = within(c.get_data_watched(&m)).await.unwrap();
    let (_, w_exists) = within(c.exists_watched(&n)).await.unwrap();
    let (_, _, w_kids) = within(c.get_children_watched(&m)).await.unwrap();
    let (_, _, w_still) = within(c.get_data_watched(&u)).await.unwrap();
    let mut w_pers = within(c.add_watch(&q, AddWatchMode::Persistent))
        .await
        .unwrap();
    let (_, _, w_lost) = within(plain.get_data_watched(&u)).await.unwrap();

    let mut sc = c.state_watcher();
    let mut sp = plain.state_watcher();
    proxy.cut();
    wait_state(&mut sc, WAIT, |i| i.state == SessionState::Connecting).await;
    wait_state(&mut sp, WAIT, |i| i.state == SessionState::Connecting).await;
    within(other.set_data(&m, b"1", None)).await.unwrap();
    mk(&other, &n).await;
    mk(&other, &ctx.path("/m/c")).await;
    within(other.set_data(&q, b"1", None)).await.unwrap();
    proxy.restore();
    wait_state(&mut sc, WAIT, |i| i.state == SessionState::Connected).await;
    wait_state(&mut sp, WAIT, |i| i.state == SessionState::Connected).await;

    assert_restored(
        &within(w_data).await.unwrap(),
        EventKind::NodeDataChanged,
        &m,
    );
    assert_restored(&within(w_exists).await.unwrap(), EventKind::NodeCreated, &n);
    assert_restored(
        &within(w_kids).await.unwrap(),
        EventKind::NodeChildrenChanged,
        &m,
    );
    assert_eq!(within(w_lost).await, Err(WatchError::ConnectionLost));

    // Persistent streams deliver subsequent mutations occurring after reconnection.
    let st = within(other.set_data(&q, b"2", None)).await.unwrap();
    let ev = next_event(&mut w_pers).await;
    assert_event(&ev, EventKind::NodeDataChanged, &q);
    assert_eq!(ev.zxid, Some(st.mzxid));
    // Watches on unmutated znodes remain actively armed following session recovery.
    within(other.set_data(&u, b"1", None)).await.unwrap();
    assert_event(
        &within(w_still).await.unwrap(),
        EventKind::NodeDataChanged,
        &u,
    );
    assert_eq!(c.state().epoch, 2);
}

/// Invariant: active client watches survive ZooKeeper server process restarts. Sessions are
/// restored from durable transaction logs upon restart, triggering SetWatches watch table
/// reconstruction. Operations dispatched while the server is unavailable either fail with
/// `ConnectionLoss` or complete upon reconnection.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn server_restart_restores_watches() {
    let Some(server) = common::fresh_server(12) else {
        return;
    };
    let cs = server.connect_string();
    let rts = common::all_rts();

    let admin = within(common::builder(&cs).connect()).await.unwrap();
    mk(&admin, "/w").await;
    let mut conns = Vec::new();
    for rt in &rts {
        let c = within(rt.apply(common::builder(&cs)).connect())
            .await
            .unwrap();
        let eph = format!("/eph-{}", rt.name());
        within(c.create(&eph, b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
            .await
            .unwrap();
        conns.push((c, eph, format!("/x-{}", rt.name())));
    }
    // Arm watches after initial znode creation to establish baseline state.
    let mut clients = Vec::new();
    for (c, eph, x) in conns {
        let (_, _, wd) = within(c.get_data_watched("/w")).await.unwrap();
        let (_, wx) = within(c.exists_watched(&x)).await.unwrap();
        let wr = within(c.add_watch("/", AddWatchMode::PersistentRecursive))
            .await
            .unwrap();
        let id = c.session().0;
        clients.push((c, eph, x, wd, wx, wr, id));
    }
    drop(admin);

    server.stop_now();
    for (c, ..) in &clients {
        let mut s = c.state_watcher();
        let info = wait_state(&mut s, WAIT, |i| i.state == SessionState::Connecting).await;
        assert!(
            common::disconnect_cause(&info).is_some() || common::io_error_kind(&info).is_some(),
            "{info:?}"
        );
    }
    let queued: Vec<_> = clients.iter().map(|(c, ..)| c.exists("/w")).collect();
    server.start_again().unwrap();

    for (c, ..) in &clients {
        let mut s = c.state_watcher();
        let info = wait_state(&mut s, Duration::from_secs(10), |i| i.state.is_connected()).await;
        assert_eq!(info.epoch, 2);
    }
    for f in queued {
        match within(f).await {
            Ok(st) => assert!(st.is_some()),
            Err(e) => assert_eq!(e, Error::ConnectionLoss),
        }
    }
    let admin = within(common::builder(&cs).connect()).await.unwrap();
    within(admin.set_data("/w", b"1", None)).await.unwrap();
    for (c, eph, x, wd, wx, mut wr, id) in clients {
        assert_eq!(c.session().0, id);
        assert!(within(admin.exists(&eph)).await.unwrap().is_some());
        assert_event(&within(wd).await.unwrap(), EventKind::NodeDataChanged, "/w");
        mk(&admin, &x).await;
        assert_event(&within(wx).await.unwrap(), EventKind::NodeCreated, &x);
        // Persistent recursive watches resume delivering change events following server restart.
        assert_event(&next_event(&mut wr).await, EventKind::NodeDataChanged, "/w");
        within(c.close()).await.unwrap();
    }
}
