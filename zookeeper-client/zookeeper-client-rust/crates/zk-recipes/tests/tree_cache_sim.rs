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

//! Simulated tests for `TreeCache` against `zk_fakeserver::FakeServer`: initial
//! synchronization under concurrent writes, incremental tree events, reconnection
//! and lag resynchronization, terminal error states, and `ZUNIMPLEMENTED` fallback
//! on pre-3.6 servers.
//!
//! `FakeServer` supports `addWatch` (opcode 106) by default; injecting
//! `Action::Reply { err: -6 }` (`ZUNIMPLEMENTED`) on opcode 106 simulates a
//! ZooKeeper 3.5 server.
//!
//! Each test verifies the cached subtree against the model state in `server.cluster()`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::Duration;

use apache_zookeeper::{Error, WatcherKind};
use apache_zookeeper_recipes::{TreeCache, TreeEvent};
use common::{label, next, until_mark};
use zk_fakeserver::{Action, Cluster, FakeServer, Rule};

/// Protocol opcodes matched in fake-server rules and transcripts.
const EXISTS: i32 = 3;
const GET_DATA: i32 = 4;
/// `get_children` issues `getChildren2` (opcode 12).
const GET_CHILDREN: i32 = 12;
const ADD_WATCH: i32 = 106;

/// Timeout window used to assert that `cache.next()` emits no further events.
const QUIET: Duration = Duration::from_millis(300);

fn create(c: &Cluster, path: &str, data: &[u8]) {
    c.create(path, data, 0)
        .unwrap_or_else(|e| panic!("create {path}: {e}"));
}

fn set(c: &Cluster, path: &str, data: &[u8]) {
    c.set_data(path, data)
        .unwrap_or_else(|e| panic!("set {path}: {e}"));
}

fn delete(c: &Cluster, path: &str) {
    c.delete(path)
        .unwrap_or_else(|e| panic!("delete {path}: {e}"));
}

/// Asserts that `cache.next()` remains pending for [`QUIET`].
async fn assert_quiet(cache: &mut TreeCache) {
    assert!(
        common::still_pending(cache.next(), QUIET).await,
        "the cache gave an event"
    );
}

/// Asserts that `cache` matches the model's subtree at `root` up to `max_depth`,
/// comparing `czxid`, `mzxid`, `version`, child lists, and optional node data.
fn assert_same(cache: &TreeCache, c: &Cluster, root: &str, data: bool, max_depth: Option<usize>) {
    let mut todo = vec![(root.to_owned(), 0_usize)];
    let mut seen = 0;
    if c.node(root).is_none() {
        assert_eq!(cache.get(root), None, "{root}");
        return;
    }
    while let Some((path, depth)) = todo.pop() {
        let info = c
            .node(&path)
            .unwrap_or_else(|| panic!("model has no {path}"));
        let node = cache
            .get(path.as_str())
            .unwrap_or_else(|| panic!("cache has no {path}"));
        assert_eq!(node.stat.czxid.0, info.stat.czxid, "czxid of {path}");
        assert_eq!(node.stat.mzxid.0, info.stat.mzxid, "mzxid of {path}");
        assert_eq!(
            node.stat.version.get(),
            info.stat.version,
            "version of {path}"
        );
        if data {
            let want = info.data.clone().unwrap_or_default();
            assert_eq!(
                node.data.as_deref(),
                Some(want.as_slice()),
                "data of {path}"
            );
        } else {
            assert_eq!(node.data, None, "data of {path}");
        }
        let kids = cache.children(path.as_str()).unwrap();
        if max_depth.is_some_and(|m| depth >= m) {
            assert!(kids.is_empty(), "children of {path} below the depth limit");
            continue;
        }
        assert_eq!(kids, info.children, "children of {path}");
        for k in kids {
            let child = if path == "/" {
                format!("/{k}")
            } else {
                format!("{path}/{k}")
            };
            todo.push((child, depth + 1));
        }
        seen += 1;
    }
    assert!(seen > 0);
}

/// Populates `/r` and the specified descendant paths in the model.
fn build(server: &FakeServer, nodes: &[&str]) {
    let c = server.cluster();
    create(c, "/r", b"root");
    for n in nodes {
        create(c, n, n.as_bytes());
    }
}

/// Starts a default `TreeCache` rooted at `/r` and verifies the initial `NodeAdded`
/// sequence through `Initialized`.
async fn start(client: &apache_zookeeper::Client, want: &[&str]) -> TreeCache {
    let mut cache = common::within(TreeCache::builder(client, "/r").start())
        .await
        .expect("start");
    let mut expected: Vec<String> = want.iter().map(|p| format!("+{p}")).collect();
    expected.push("Initialized".to_owned());
    assert_eq!(until_mark(&mut cache).await, expected);
    cache
}

/// Verifies initial synchronization of a three-level tree with concurrent mutations:
/// `NodeAdded` events precede `Initialized`, `czxid`/`mzxid` comparison reconciles
/// racing updates without duplicate or dropped events, and `addWatch` is sent before
/// any tree read.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn re09_initial_sync_with_racing_writes() {
    let server = common::fake();
    let c = server.cluster();
    build(&server, &["/r/a", "/r/b", "/r/a/x", "/r/a/y", "/r/b/z"]);
    let client = common::connect(&server.connect_string()).await;

    // Delay the first-level reply after the model has already evaluated the first-level
    // reads so mutations below occur between the first-level and second-level reads.
    server.add_rule(
        Rule::on_request()
            .op(GET_DATA)
            .path("/r/a")
            .once()
            .then(Action::Delay(Duration::from_millis(500))),
    );
    let started = tokio::spawn(TreeCache::builder(&client, "/r").start());
    c.wait_for_frame(common::WAIT, |f| {
        f.op == Some(GET_CHILDREN) && f.path.as_deref() == Some("/r/b")
    })
    .expect("the sync reads the first level");
    // Apply mutations in deterministic order while the second-level scan is gated.
    set(c, "/r/a", b"a1");
    set(c, "/r/a/x", b"x1");
    create(c, "/r/b/new", b"new");
    delete(c, "/r/b/z");

    let mut cache = common::within(started).await.unwrap().expect("start");
    // Initial scan observed stale `/r/a`, updated `/r/a/x`, and neither `/r/b/new` nor `/r/b/z`.
    assert_eq!(
        until_mark(&mut cache).await,
        ["+/r", "+/r/a", "+/r/b", "+/r/a/x", "+/r/a/y", "Initialized"]
    );
    // Watch notifications reconcile `/r/a` and `/r/b/new`; `/r/a/x` is already up to date
    // and `/r/b/z` was never added.
    let ev = next(&mut cache).await;
    assert_eq!(label(&ev), "~/r/a");
    let TreeEvent::NodeUpdated { node, .. } = ev else {
        unreachable!()
    };
    assert_eq!(node.data.as_deref(), Some(&b"a1"[..]));
    assert_eq!(label(&next(&mut cache).await), "+/r/b/new");
    assert_quiet(&mut cache).await;
    assert_same(&cache, c, "/r", true, None);
    // Persistent watch must be registered before the initial tree read begins.
    let frames = c.transcript();
    let first = |op: i32| frames.iter().position(|f| f.op == Some(op)).unwrap();
    assert!(first(ADD_WATCH) < first(GET_DATA));
}

/// Verifies incremental events (create, set_data, bottom-up subtree removal,
/// delete-and-recreate with a new `czxid`), `max_depth` filtering, and `cache_data(false)`.
#[tokio::test]
async fn re10_events_and_max_depth() {
    let server = common::fake();
    let c = server.cluster();
    build(&server, &["/r/a", "/r/a/b", "/r/a/b/c", "/r/k"]);
    let client = common::connect(&server.connect_string()).await;
    let mut cache = start(&client, &["/r", "/r/a", "/r/k", "/r/a/b", "/r/a/b/c"]).await;

    // Node creation and data update.
    create(c, "/r/n", b"n1");
    let ev = next(&mut cache).await;
    assert_eq!(label(&ev), "+/r/n");
    set(c, "/r/n", b"n2");
    let ev = next(&mut cache).await;
    assert_eq!(label(&ev), "~/r/n");
    assert_eq!(cache.get("/r/n").unwrap().data.as_deref(), Some(&b"n2"[..]));
    assert_eq!(cache.children("/r").unwrap(), ["a", "k", "n"]);

    // When a read triggered by a data update on `/r/a` observes `NoNode` because the
    // entire subtree was deleted, the cache prunes the subtree bottom-up and ignores
    // the subsequent delete notifications for the already-removed nodes.
    set(c, "/r/a", b"a1");
    delete(c, "/r/a/b/c");
    delete(c, "/r/a/b");
    delete(c, "/r/a");
    assert_eq!(label(&next(&mut cache).await), "-/r/a/b/c");
    assert_eq!(label(&next(&mut cache).await), "-/r/a/b");
    assert_eq!(label(&next(&mut cache).await), "-/r/a");
    assert_eq!(cache.get("/r/a/b"), None);
    assert_eq!(cache.children("/r").unwrap(), ["k", "n"]);

    // Deleting and recreating a node emits `NodeRemoved` followed by `NodeAdded`
    // with a new `czxid`.
    let old = cache.get("/r/k").unwrap();
    delete(c, "/r/k");
    create(c, "/r/k", b"k2");
    assert_eq!(label(&next(&mut cache).await), "-/r/k");
    let ev = next(&mut cache).await;
    assert_eq!(label(&ev), "+/r/k");
    let TreeEvent::NodeAdded { node, .. } = ev else {
        unreachable!()
    };
    assert_ne!(node.stat.czxid, old.stat.czxid);
    assert_eq!(node.data.as_deref(), Some(&b"k2"[..]));
    assert_quiet(&mut cache).await;
    assert_same(&cache, c, "/r", true, None);

    // Depth-bounded cache with `cache_data(false)` queries stats via `exists`
    // rather than `getData` and ignores events beyond `max_depth`.
    let before = c.transcript().len();
    let mut shallow = common::within(
        TreeCache::builder(&client, "/r")
            .max_depth(Some(1))
            .cache_data(false)
            .start(),
    )
    .await
    .expect("start");
    assert_eq!(
        until_mark(&mut shallow).await,
        ["+/r", "+/r/k", "+/r/n", "Initialized"]
    );
    let frames = c.transcript();
    let ops: Vec<i32> = frames[before..].iter().filter_map(|f| f.op).collect();
    assert!(ops.contains(&EXISTS) && !ops.contains(&GET_DATA), "{ops:?}");
    // Nodes at `max_depth` (`/r/k`, `/r/n`) do not have their children listed.
    let listed: Vec<_> = frames[before..]
        .iter()
        .filter(|f| f.op == Some(GET_CHILDREN))
        .filter_map(|f| f.path.clone())
        .collect();
    assert_eq!(listed, ["/r"]);

    create(c, "/r/n/deep", b"d");
    set(c, "/r/k", b"k3");
    // Notification for `/r/n/deep` arrives first and is filtered out by `max_depth`.
    let ev = next(&mut shallow).await;
    assert_eq!(label(&ev), "~/r/k");
    let TreeEvent::NodeUpdated { node, .. } = ev else {
        unreachable!()
    };
    assert_eq!(node.data, None);
    assert_eq!(shallow.get("/r/n/deep"), None);
    assert_quiet(&mut shallow).await;
    assert_same(&shallow, c, "/r", false, Some(1));
}

/// Verifies that reconnecting after offline mutations triggers a full tree scan,
/// emits differential events followed by `Resynced`, and is cancel-safe if `next()`
/// is dropped mid-resync.
#[tokio::test]
async fn re11_resync_after_reconnect() {
    let mut server = common::fake();
    build(&server, &["/r/a", "/r/b", "/r/c"]);
    let client = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let mut cache = start(&client, &["/r", "/r/a", "/r/b", "/r/c"]).await;
    let epoch = client.state().epoch;

    // Delay the root read of the post-reconnect resync to test mid-resync cancellation.
    server.add_rule(
        Rule::on_request()
            .op(GET_DATA)
            .path("/r")
            .once()
            .then(Action::Delay(Duration::from_millis(500))),
    );
    server.stop();
    common::wait_state(&mut state, |i| !i.state.is_connected()).await;
    {
        let c = server.cluster();
        set(c, "/r/a", b"a1");
        delete(c, "/r/b");
        create(c, "/r/d", b"d");
        create(c, "/r/c/e", b"e");
    }
    server.resume().expect("resume");
    common::wait_state(&mut state, |i| i.state.is_connected() && i.epoch > epoch).await;

    // Dropping `cache.next()` while the delayed resync read is in flight preserves state.
    assert!(common::still_pending(cache.next(), Duration::from_millis(100)).await);
    assert_eq!(
        until_mark(&mut cache).await,
        ["-/r/b", "~/r/a", "+/r/d", "+/r/c/e", "Resynced"]
    );
    assert_quiet(&mut cache).await;
    let c = server.cluster();
    assert_same(&cache, c, "/r", true, None);

    // Persistent watch remains armed after resync.
    set(c, "/r/d", b"d2");
    assert_eq!(label(&next(&mut cache).await), "~/r/d");
}

/// Verifies that overflowing `persistent_watch_buffer` (`WatchError::Lagged`)
/// triggers a full resync.
#[tokio::test]
async fn re11_resync_after_lag() {
    let server = common::fake();
    let c = server.cluster();
    build(&server, &[]);
    let client = common::within(
        common::builder(&server.connect_string())
            .persistent_watch_buffer(1)
            .connect(),
    )
    .await
    .expect("connect");
    let mut cache = start(&client, &["/r"]).await;

    // Overflow the 1-slot watch buffer while `cache` is not being polled.
    let names = ["/r/n1", "/r/n2", "/r/n3", "/r/n4", "/r/n5"];
    for n in names {
        create(c, n, b"x");
    }
    tokio::time::sleep(QUIET).await;

    // Every created node is reported exactly once (either from the buffered event
    // or from the resync scan), followed by `Resynced`.
    let events = until_mark(&mut cache).await;
    assert_eq!(events.last().map(String::as_str), Some("Resynced"));
    let mut added: Vec<String> = events
        .iter()
        .filter_map(|e| e.strip_prefix('+').map(str::to_owned))
        .collect();
    added.sort();
    assert_eq!(added, names);
    assert_eq!(events.len(), names.len() + 1, "{events:?}");
    assert_quiet(&mut cache).await;
    assert_same(&cache, c, "/r", true, None);
}

/// Verifies that when `restore_watches(false)` causes the persistent watcher to
/// terminate with `ConnectionLost` on disconnect, the cache re-registers `addWatch`
/// on the new connection and resyncs once.
#[tokio::test]
async fn re11_rearm_without_restored_watches() {
    let mut server = common::fake();
    build(&server, &["/r/a"]);
    let client = common::within(
        common::builder(&server.connect_string())
            .restore_watches(false)
            .connect(),
    )
    .await
    .expect("connect");
    let mut state = client.state_watcher();
    let mut cache = start(&client, &["/r", "/r/a"]).await;
    let epoch = client.state().epoch;

    server.stop();
    common::wait_state(&mut state, |i| !i.state.is_connected()).await;
    {
        let c = server.cluster();
        set(c, "/r/a", b"a1");
        create(c, "/r/b", b"b");
    }
    server.resume().expect("resume");
    common::wait_state(&mut state, |i| i.state.is_connected() && i.epoch > epoch).await;

    assert_eq!(until_mark(&mut cache).await, ["~/r/a", "+/r/b", "Resynced"]);
    assert_quiet(&mut cache).await;
    let c = server.cluster();
    assert_same(&cache, c, "/r", true, None);
    // One `addWatch` frame per connection and no `setWatches`/`setWatches2` frames.
    let frames = c.transcript();
    let adds: Vec<u64> = frames
        .iter()
        .filter(|f| f.op == Some(ADD_WATCH))
        .map(|f| f.conn)
        .collect();
    assert_eq!(adds.len(), 2, "{adds:?}");
    assert_ne!(adds[0], adds[1]);
    assert!(!frames.iter().any(|f| matches!(f.op, Some(101 | 105))));

    // Re-armed watch delivers subsequent events.
    create(c, "/r/a/k", b"k");
    assert_eq!(label(&next(&mut cache).await), "+/r/a/k");
}

/// Verifies terminal stream behavior: session expiry yields `Some(Err(SessionExpired))`
/// once and `remove_watches` yields `Some(Err(NoWatcher))` once, after which `next()`
/// returns `None`.
#[tokio::test]
async fn cache_ends_on_terminal_state_and_removed_watch() {
    let server = common::fake();
    let c = server.cluster();
    build(&server, &["/r/a"]);

    // Explicit watch removal.
    let client = common::connect(&server.connect_string()).await;
    let mut cache = start(&client, &["/r", "/r/a"]).await;
    common::within(client.remove_watches("/r", WatcherKind::PersistentRecursive, false))
        .await
        .expect("remove_watches");
    assert_eq!(
        common::within(cache.next()).await,
        Some(Err(Error::NoWatcher))
    );
    assert_eq!(common::within(cache.next()).await, None);
    assert_eq!(common::within(cache.next()).await, None);
    // In-memory snapshot remains queryable after stream termination.
    assert!(cache.get("/r/a").is_some());

    // Session expiration.
    let mut cache = start(&client, &["/r", "/r/a"]).await;
    assert!(c.expire_session(client.session().0.0));
    assert_eq!(
        common::within(cache.next()).await,
        Some(Err(Error::SessionExpired))
    );
    assert_eq!(common::within(cache.next()).await, None);
    // Starting a new cache on an expired session fails immediately.
    let again = common::within(TreeCache::builder(&client, "/r").start()).await;
    assert_eq!(again.err(), Some(Error::SessionExpired));
}

/// Verifies that `TreeCache::start` fails immediately with `Error::Unimplemented`
/// (before issuing any tree reads) when `addWatch` is rejected with `ZUNIMPLEMENTED`.
#[tokio::test]
async fn start_fails_without_persistent_watches() {
    let server = common::fake();
    let c = server.cluster();
    build(&server, &["/r/a"]);
    server.add_rule(
        Rule::on_request()
            .op(ADD_WATCH)
            .then(Action::Reply { err: -6 }),
    );
    let client = common::connect(&server.connect_string()).await;
    let started = common::within(TreeCache::builder(&client, "/r").start()).await;
    assert_eq!(started.err(), Some(Error::Unimplemented));
    let frames = c.transcript();
    assert!(frames.iter().any(|f| f.op == Some(ADD_WATCH)));
    assert!(
        !frames
            .iter()
            .any(|f| matches!(f.op, Some(EXISTS | GET_DATA | GET_CHILDREN)))
    );
}
