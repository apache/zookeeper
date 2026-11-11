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

//! Simulated tests for `DistributedLock` against `zk_fakeserver::FakeServer`:
//! contended FIFO acquisition, predecessor deletion races, protected create
//! recovery across connection loss, future/guard cancellation and background
//! guaranteed delete cleanup, `try_acquire`, session expiry, read-only connection
//! handling, and Curator-compatible node naming and ordering.
//!
//! Faults are injected via `FakeServer` rules: `Action::DropReply` followed by
//! `Action::Close` simulates a request applied by the server whose reply is lost
//! on disconnect, whereas `Action::Close` alone simulates a request lost before
//! reaching the server.
//!
//! Each test verifies both client outcomes and the server model state via
//! `server.cluster()`, including per-session request transcripts.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::collections::BTreeSet;
use std::sync::Arc;
use std::time::Duration;

use apache_zookeeper::{Client, Error, SessionState};
use apache_zookeeper_recipes::{DistributedLock, LeaderLatch, LockGuard};
use common::{is_v4, poll_once, session_id};
use tokio::sync::mpsc;
use zk_fakeserver::{Action, FakeServer, FrameRecord, Rule, Script, create_mode};

/// ZooKeeper protocol opcodes matched in fake-server rules and transcripts.
const OP_DELETE: i32 = 2;
const OP_EXISTS: i32 = 3;
const OP_GET_DATA: i32 = 4;
const OP_SYNC: i32 = 9;
/// `get_children` issues `getChildren2` (opcode 12).
const OP_GET_CHILDREN: i32 = 12;
const OP_CREATE2: i32 = 15;
const OP_CREATE_CONTAINER: i32 = 19;

/// Sentinel `ephemeralOwner` value marking container nodes in the fake-server model.
const CONTAINER_OWNER: i64 = i64::MIN;

/// Timeout window used to assert that a waiting contender does not proceed.
const SHORT: Duration = Duration::from_millis(300);

/// ZooKeeper `ZNOAUTH` error code.
const ERR_NO_AUTH: i32 = -102;
/// ZooKeeper `ZNOTREADONLY` error code.
const ERR_NOT_READ_ONLY: i32 = -119;

/// Returns the children of `path` sorted in Curator lock order (by the suffix
/// following the last `lock-` marker, or by the full node name if absent).
fn lock_order(server: &FakeServer, path: &str) -> Vec<String> {
    let mut c = server.cluster().children(path).unwrap_or_default();
    c.sort_by(|a, b| {
        let key = |s: &str| s.rsplit_once("lock-").map_or(s, |(_, t)| t).to_owned();
        key(a).encode_utf16().cmp(key(b).encode_utf16())
    });
    c
}

/// Returns the number of child nodes under `path` in the fake-server model.
fn child_count(server: &FakeServer, path: &str) -> usize {
    server.cluster().children(path).map_or(0, |c| c.len())
}

/// Polls `pred(server)` asynchronously until satisfied within [`common::WAIT`].
async fn until(server: &FakeServer, mut pred: impl FnMut(&FakeServer) -> bool) {
    common::within(async {
        while !pred(server) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await;
}

/// Returns all transcript frames matching opcode `op` since the last transcript clear.
fn frames(server: &FakeServer, op: i32) -> Vec<FrameRecord> {
    server
        .cluster()
        .transcript()
        .into_iter()
        .filter(|r| r.op == Some(op))
        .collect()
}

/// Returns the ephemeral node paths owned by `session`.
fn ephemerals(server: &FakeServer, session: i64) -> Vec<String> {
    server
        .cluster()
        .sessions()
        .into_iter()
        .find(|s| s.id == session)
        .map(|s| s.ephemerals)
        .unwrap_or_default()
}

/// Splits a protected lock node name `_c_<uuid>-lock-<seq>` into `(uuid, seq)`.
fn split_lock_name(name: &str) -> (String, String) {
    let rest = name.strip_prefix("_c_").expect("protected prefix");
    let (uuid, seq) = rest.split_once("-lock-").expect("lock marker");
    (uuid.to_owned(), seq.to_owned())
}

/// Spawns a task that acquires `path` on `client` and sends `(n, guard)` to `tx`.
fn spawn_acquire(
    client: &Client,
    path: &str,
    n: usize,
    tx: mpsc::UnboundedSender<(usize, LockGuard)>,
) -> tokio::task::JoinHandle<()> {
    let lock = DistributedLock::new(client, path).unwrap();
    tokio::spawn(async move {
        let guard = lock.acquire().await.expect("acquire");
        let _ = tx.send((n, guard));
    })
}

/// Verifies five way contention on a single lock path: mutual exclusion holds at
/// every step, ownership is granted in sequential creation order, and each waiter
/// watches only its immediate predecessor so releasing wakes a single contender.
#[tokio::test]
async fn re01_five_contenders_in_creation_order() {
    const PATH: &str = "/re01/lock";
    let server = common::fake();
    let mut clients = Vec::new();
    for _ in 0..5 {
        clients.push(common::connect(&server.connect_string()).await);
    }
    let (tx, mut rx) = mpsc::unbounded_channel();
    for (n, c) in clients.iter().enumerate() {
        spawn_acquire(c, PATH, n, tx.clone());
        // Wait for contender `n`'s node creation before starting contender `n + 1`
        // so creation order matches contender index order.
        until(&server, |s| child_count(s, PATH) == n + 1).await;
    }
    // Four non-holding contenders, each with a single predecessor data watch.
    until(&server, |s| s.cluster().watch_count() == 4).await;

    // Verify that each waiter watched only its immediate predecessor.
    let order = lock_order(&server, PATH);
    let sessions: Vec<i64> = clients.iter().map(session_id).collect();
    for (n, s) in sessions.iter().enumerate() {
        let own = ephemerals(&server, *s);
        assert_eq!(own, vec![format!("{PATH}/{}", order[n])], "contender {n}");
        let watched: BTreeSet<String> = frames(&server, OP_GET_DATA)
            .into_iter()
            .filter(|r| r.session_id == *s)
            .filter_map(|r| r.path)
            .collect();
        let expected: BTreeSet<String> = match n {
            0 => BTreeSet::new(),
            _ => BTreeSet::from([format!("{PATH}/{}", order[n - 1])]),
        };
        assert_eq!(watched, expected, "contender {n}");
    }

    for n in 0..5 {
        let (got, guard) = common::within(rx.recv()).await.unwrap();
        assert_eq!(got, n, "the lock goes in creation order");
        assert_eq!(guard.node().name(), Some(order[n].as_str()));
        // Mutual exclusion: no other contender acquires while `guard` is held.
        assert!(common::still_pending(rx.recv(), SHORT).await);
        let waiters = 4 - n;
        assert_eq!(server.cluster().watch_count(), waiters);
        assert_eq!(lock_order(&server, PATH).first(), Some(&order[n]));

        server.cluster().clear_transcript();
        common::within(guard.release()).await.unwrap();
        if n < 4 {
            // Releasing wakes only the immediate successor (`n + 1`), which re-lists `PATH`.
            let (next, guard) = common::within(rx.recv()).await.unwrap();
            assert_eq!(next, n + 1);
            let listers: BTreeSet<i64> = frames(&server, OP_GET_CHILDREN)
                .into_iter()
                .map(|r| r.session_id)
                .collect();
            assert_eq!(listers, BTreeSet::from([sessions[n + 1]]));
            // Return `guard` to the channel for the next loop iteration.
            tx.send((next, guard)).unwrap();
        }
    }
    assert_eq!(child_count(&server, PATH), 0);
    for c in clients {
        common::within(c.close()).await.unwrap();
    }
}

/// Verifies recovery when a predecessor node is deleted while the successor is
/// registering its data watch (`get_data_watched` returns `NoNode` and the successor
/// immediately re-evaluates the child list).
///
/// Part 1 delays the successor's `getChildren` reply and deletes the predecessor
/// in that window so the successor observes a genuine `NoNode` on `get_data_watched`.
/// Part 2 injects a synthetic `NoNode` while the predecessor remains alive. The
/// successor relists, rewatches the same predecessor, and wakes when it is released.
#[tokio::test]
async fn re02_predecessor_deleted_during_watch() {
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let sb = session_id(&b);

    // Part 1: predecessor deleted before the watch request reaches the server.
    const P1: &str = "/re02a";
    let held = common::within(DistributedLock::new(&a, P1).unwrap().acquire())
        .await
        .unwrap();
    let pred = held.node().to_buf();
    server.cluster().clear_transcript();
    server.add_rule(
        Rule::on_request()
            .op(OP_GET_CHILDREN)
            .path(P1)
            .once()
            .then(Action::Delay(Duration::from_millis(500)))
            .then(Action::Model),
    );
    let (tx, mut rx) = mpsc::unbounded_channel();
    spawn_acquire(&b, P1, 1, tx.clone());
    // Delete the predecessor while the `getChildren` response containing it is delayed.
    until(&server, |s| !frames(s, OP_GET_CHILDREN).is_empty()).await;
    server.cluster().delete(pred.as_str()).unwrap();
    let (_, guard) = common::within(rx.recv()).await.unwrap();
    let watched: Vec<String> = frames(&server, OP_GET_DATA)
        .into_iter()
        .filter(|r| r.session_id == sb)
        .filter_map(|r| r.path)
        .collect();
    assert_eq!(watched, vec![pred.as_str().to_owned()]);
    // Successor immediately re-listed `P1` and found its node at the head of the queue.
    assert_eq!(frames(&server, OP_GET_CHILDREN).len(), 2);
    assert_eq!(server.cluster().watch_count(), 0);
    assert_eq!(lock_order(&server, P1), vec![guard.node().name().unwrap()]);
    drop(held);
    common::within(guard.release()).await.unwrap();
    server.clear_rules();

    // Part 2: synthetic `NoNode` (-101) while the predecessor remains present.
    const P2: &str = "/re02b";
    let held = common::within(DistributedLock::new(&a, P2).unwrap().acquire())
        .await
        .unwrap();
    let pred = held.node().to_buf();
    server.cluster().clear_transcript();
    server.add_rule(
        Rule::on_request()
            .op(OP_GET_DATA)
            .path(pred.as_str())
            .once()
            .then(Action::Reply { err: -101 }),
    );
    spawn_acquire(&b, P2, 2, tx);
    until(&server, |s| s.cluster().watch_count() == 1).await;
    let reads = frames(&server, OP_GET_DATA);
    assert_eq!(reads.len(), 2, "the successor read again at once");
    assert!(
        reads
            .iter()
            .all(|r| r.path.as_deref() == Some(pred.as_str()))
    );
    assert_eq!(frames(&server, OP_GET_CHILDREN).len(), 2);
    // Successor remains blocked until `held` is released.
    assert!(common::still_pending(rx.recv(), SHORT).await);
    common::within(held.release()).await.unwrap();
    let (n, guard) = common::within(rx.recv()).await.unwrap();
    assert_eq!(n, 2);
    assert_eq!(lock_order(&server, P2), vec![guard.node().name().unwrap()]);
    common::within(guard.release()).await.unwrap();

    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that when the server applies a protected participant `create` but the
/// connection drops before the reply arrives, the client adopts the existing
/// `_c_<uuid>-` node upon reconnection rather than creating a duplicate.
#[tokio::test]
async fn re03a_create_applied_before_loss_is_adopted() {
    const PATH: &str = "/re03a";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::DropReply)
            .then(Action::Close),
    );
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    // Only a single `create2` request was transmitted (on the first connection).
    let creates = frames(&server, OP_CREATE2);
    assert_eq!(creates.len(), 1);
    assert_eq!(creates[0].conn_ordinal, 1);
    let sent = creates[0].path.clone().unwrap();
    let name = guard.node().name().unwrap().to_owned();
    assert!(format!("{PATH}/{name}").starts_with(&sent));
    // Single participant node, adopted on the second connection.
    assert_eq!(lock_order(&server, PATH), vec![name.clone()]);
    assert!(state.current().epoch >= 2);
    assert!(
        frames(&server, OP_GET_CHILDREN)
            .iter()
            .any(|r| r.conn_ordinal == 2)
    );
    // Recovery precedes `getChildren` with `sync` so a lagging follower cannot
    // hide a node committed before the disconnect.
    let lookup: Vec<i32> = server
        .cluster()
        .transcript()
        .into_iter()
        .filter(|r| r.conn_ordinal == 2 && r.path.as_deref() == Some(PATH))
        .filter_map(|r| r.op)
        .collect();
    assert_eq!(lookup.get(..2), Some(&[OP_SYNC, OP_GET_CHILDREN][..]));
    common::wait_state(&mut state, |i| i.state == SessionState::Connected).await;
    common::within(guard.release()).await.unwrap();
    assert_eq!(child_count(&server, PATH), 0);
    common::within(client.close()).await.unwrap();
}

/// Verifies that when the connection drops before the server applies a participant
/// `create`, the client re-sends the `create` on the new connection with the same UUID.
#[tokio::test]
async fn re03b_create_not_applied_is_sent_again() {
    const PATH: &str = "/re03b";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Close),
    );
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    let creates = frames(&server, OP_CREATE2);
    assert_eq!(creates.len(), 2, "the create went out again");
    assert_eq!(creates[0].path, creates[1].path, "the same <uuid>");
    // First connection was closed by the rule prior to model execution; second
    // `create2` succeeded on the new connection.
    assert!(creates[0].rule.is_some());
    assert_eq!(creates[0].conn_ordinal, 1);
    assert_eq!(creates[1].conn_ordinal, 2);
    let name = guard.node().name().unwrap().to_owned();
    assert!(format!("{PATH}/{name}").starts_with(creates[1].path.as_deref().unwrap()));
    // Exactly one node exists on the server.
    assert_eq!(lock_order(&server, PATH), vec![name]);
    common::within(guard.release()).await.unwrap();
    assert_eq!(child_count(&server, PATH), 0);
    common::within(client.close()).await.unwrap();
}

/// Verifies that dropping a waiting `acquire` future or dropping a held `LockGuard`
/// deletes the participant node and advances the next contender.
#[tokio::test]
async fn re04_drop_of_acquire_and_guard_deletes_the_node() {
    const PATH: &str = "/re04/lock";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let c = common::connect(&server.connect_string()).await;
    let held = common::within(DistributedLock::new(&a, PATH).unwrap().acquire())
        .await
        .unwrap();

    let (tx, mut rx) = mpsc::unbounded_channel();
    let waiter_b = spawn_acquire(&b, PATH, 1, tx.clone());
    until(&server, |s| {
        child_count(s, PATH) == 2 && s.cluster().watch_count() == 1
    })
    .await;
    spawn_acquire(&c, PATH, 2, tx);
    until(&server, |s| {
        child_count(s, PATH) == 3 && s.cluster().watch_count() == 2
    })
    .await;

    // Aborting `b`'s waiting `acquire` deletes its node, causing `c` to watch `a`.
    waiter_b.abort();
    assert!(waiter_b.await.unwrap_err().is_cancelled());
    until(&server, |s| {
        child_count(s, PATH) == 2 && ephemerals(s, session_id(&b)).is_empty()
    })
    .await;
    until(&server, |s| s.cluster().watch_count() == 1).await;
    assert!(common::still_pending(rx.recv(), SHORT).await);

    // Dropping `held` deletes `a`'s node and grants the lock to `c`.
    drop(held);
    let (n, guard) = common::within(rx.recv()).await.unwrap();
    assert_eq!(n, 2);
    assert_eq!(
        ephemerals(&server, session_id(&c)),
        vec![guard.node().as_str().to_owned()]
    );
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    drop(guard);
    until(&server, |s| child_count(s, PATH) == 0).await;

    for client in [a, b, c] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies `try_acquire`: returns `Ok(Some(guard))` when uncontended, and deletes
/// its own participant node before returning `Ok(None)` when the lock is held.
#[tokio::test]
async fn re05_try_acquire_free_and_held() {
    const PATH: &str = "/re05";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let lock_a = DistributedLock::new(&a, PATH).unwrap();
    let lock_b = DistributedLock::new(&b, PATH).unwrap();

    let held = common::within(lock_a.try_acquire()).await.unwrap().unwrap();
    assert_eq!(lock_order(&server, PATH), vec![held.node().name().unwrap()]);

    server.cluster().clear_transcript();
    assert!(
        common::within(lock_b.try_acquire())
            .await
            .unwrap()
            .is_none()
    );
    // Participant deletion is confirmed before `try_acquire` returns `Ok(None)`.
    assert_eq!(lock_order(&server, PATH), vec![held.node().name().unwrap()]);
    let made = frames(&server, OP_CREATE2);
    let deleted = frames(&server, OP_DELETE);
    assert_eq!(made.len(), 1);
    assert_eq!(deleted.len(), 1);
    assert_eq!(deleted[0].session_id, session_id(&b));
    assert!(
        deleted[0]
            .path
            .as_deref()
            .unwrap()
            .starts_with(made[0].path.as_deref().unwrap())
    );
    // `try_acquire` never waits on a predecessor, leaving zero active watches.
    assert_eq!(server.cluster().watch_count(), 0);

    common::within(held.release()).await.unwrap();
    let guard = common::within(lock_b.try_acquire()).await.unwrap().unwrap();
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    common::within(guard.release()).await.unwrap();
    assert_eq!(child_count(&server, PATH), 0);

    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies `LockGuard::lost`: resolves with `SessionExpired` on session expiry,
/// resolves with `NoNode` on external deletion of the lock node, and remains pending
/// across transient connection loss without session expiry.
#[tokio::test]
async fn re06_lost_on_expiry_and_on_foreign_delete() {
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;

    // External deletion resolves `lost()` with `NoNode`, including after a reconnect.
    let held_b = common::within(DistributedLock::new(&b, "/re06b").unwrap().acquire())
        .await
        .unwrap();
    {
        let lost = held_b.lost();
        tokio::pin!(lost);
        assert!(common::still_pending(&mut lost, SHORT).await);
        let mut state = b.state_watcher();
        server.drop_connections();
        common::wait_state(&mut state, |i| i.state.is_connected() && i.epoch >= 2).await;
        assert!(common::still_pending(&mut lost, SHORT).await);
        server.cluster().delete(held_b.node().as_str()).unwrap();
        assert_eq!(common::within(&mut lost).await, Error::NoNode);
    }
    // `release()` surfaces `Err(NoNode)` when the lock node was externally deleted.
    assert_eq!(common::within(held_b.release()).await, Err(Error::NoNode));

    // Session expiry resolves `lost()` with `SessionExpired` and unblocks waiting contenders.
    let held_a = common::within(DistributedLock::new(&a, "/re06a").unwrap().acquire())
        .await
        .unwrap();
    let (tx, mut rx) = mpsc::unbounded_channel::<Result<LockGuard, Error>>();
    let lock_b = DistributedLock::new(&b, "/re06a").unwrap();
    tokio::spawn(async move {
        let _ = tx.send(lock_b.acquire().await);
    });
    until(&server, |s| child_count(s, "/re06a") == 2).await;
    let lost = held_a.lost();
    tokio::pin!(lost);
    assert!(common::still_pending(&mut lost, SHORT).await);
    assert!(server.cluster().expire_session(session_id(&a)));
    assert_eq!(common::within(&mut lost).await, Error::SessionExpired);
    // Ephemeral removal on `a`'s session expiry grants the lock to `b`.
    let guard = common::within(rx.recv()).await.unwrap().unwrap();
    assert_eq!(
        lock_order(&server, "/re06a"),
        vec![guard.node().name().unwrap()]
    );
    // Subsequent `acquire` on an expired client fails immediately with `SessionExpired`.
    let again = DistributedLock::new(&a, "/re06a").unwrap();
    assert_eq!(
        common::within(again.acquire()).await.unwrap_err(),
        Error::SessionExpired
    );
    // Expiring a waiting contender's session terminates its `acquire` with `SessionExpired`.
    let (tx, mut rx) = mpsc::unbounded_channel::<Result<LockGuard, Error>>();
    let c = common::connect(&server.connect_string()).await;
    let lock_c = DistributedLock::new(&c, "/re06a").unwrap();
    tokio::spawn(async move {
        let _ = tx.send(lock_c.acquire().await);
    });
    until(&server, |s| child_count(s, "/re06a") == 2).await;
    assert!(server.cluster().expire_session(session_id(&c)));
    assert!(matches!(
        common::within(rx.recv()).await.unwrap(),
        Err(Error::SessionExpired)
    ));
    until(&server, |s| child_count(s, "/re06a") == 1).await;
    common::within(guard.release()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that if a waiting contender's own node is externally deleted, the
/// contender detects its absence on the next `getChildren` check, creates a replacement
/// node with a fresh UUID, and acquires the lock.
#[tokio::test]
async fn own_node_deleted_while_waiting_makes_a_new_node() {
    const PATH: &str = "/c1";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let held = common::within(DistributedLock::new(&a, PATH).unwrap().acquire())
        .await
        .unwrap();
    let (tx, mut rx) = mpsc::unbounded_channel();
    spawn_acquire(&b, PATH, 1, tx);
    until(&server, |s| s.cluster().watch_count() == 1).await;
    let first = ephemerals(&server, session_id(&b));
    assert_eq!(first.len(), 1);
    server.cluster().delete(&first[0]).unwrap();
    common::within(held.release()).await.unwrap();
    let (_, guard) = common::within(rx.recv()).await.unwrap();
    let (old_uuid, _) = split_lock_name(first[0].rsplit('/').next().unwrap());
    let (new_uuid, _) = split_lock_name(guard.node().name().unwrap());
    assert_ne!(old_uuid, new_uuid);
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    common::within(guard.release()).await.unwrap();
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies Curator-compatible lock node naming (`<lock_path>/_c_<uuid>-lock-<seq>`
/// with lowercase v4 UUIDs), container parent creation, and sort ordering alongside
/// foreign child nodes.
#[tokio::test]
async fn re08_lock_node_layout_and_order() {
    const PATH: &str = "/re08/a/b/lock";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let session = session_id(&client);

    let lock = DistributedLock::new(&client, PATH)
        .unwrap()
        .node_data(b"owner".to_vec());
    let guard = common::within(lock.acquire()).await.unwrap();
    let name = guard.node().name().unwrap().to_owned();
    assert_eq!(guard.node().as_str(), format!("{PATH}/{name}"));
    let (uuid, seq) = split_lock_name(&name);
    assert!(is_v4(&uuid), "{uuid}");
    assert_eq!(seq.len(), 10);
    assert!(seq.chars().all(|c| c.is_ascii_digit()));
    let node = server.cluster().node(guard.node().as_str()).unwrap();
    assert_eq!(node.data.as_deref(), Some(&b"owner"[..]));
    assert_eq!(node.stat.ephemeral_owner, session);
    // Missing ancestor levels are created top-down as container nodes.
    let levels = ["/re08", "/re08/a", "/re08/a/b", PATH];
    for level in levels {
        let n = server.cluster().node(level).unwrap();
        assert_eq!(n.stat.ephemeral_owner, CONTAINER_OWNER, "{level}");
    }
    let made: Vec<String> = frames(&server, OP_CREATE_CONTAINER)
        .into_iter()
        .filter_map(|r| r.path)
        .collect();
    assert_eq!(made, levels.map(str::to_owned).to_vec());
    // Each acquisition attempt generates a distinct UUID.
    let other = common::within(lock.try_acquire()).await.unwrap();
    assert!(other.is_none());
    let creates = frames(&server, OP_CREATE2);
    let uuids: BTreeSet<String> = creates
        .iter()
        .filter_map(|r| r.path.as_deref())
        .map(|p| p.trim_start_matches(&format!("{PATH}/_c_")).to_owned())
        .collect();
    assert_eq!(uuids.len(), 2);
    common::within(guard.release()).await.unwrap();

    // Foreign children without `lock-` sort by their full name: `"000"` precedes
    // 10-digit sequence numbers and `"zzz"` sorts after them.
    const FOREIGN: &str = "/re08f";
    server
        .cluster()
        .create(FOREIGN, b"", create_mode::PERSISTENT)
        .unwrap();
    server
        .cluster()
        .create("/re08f/000", b"", create_mode::PERSISTENT)
        .unwrap();
    server
        .cluster()
        .create("/re08f/zzz", b"", create_mode::PERSISTENT)
        .unwrap();
    let foreign = DistributedLock::new(&client, FOREIGN).unwrap();
    assert!(
        common::within(foreign.try_acquire())
            .await
            .unwrap()
            .is_none()
    );
    server.cluster().clear_transcript();
    let (tx, mut rx) = mpsc::unbounded_channel();
    spawn_acquire(&client, FOREIGN, 0, tx);
    until(&server, |s| s.cluster().watch_count() == 1).await;
    let watched: Vec<String> = frames(&server, OP_GET_DATA)
        .into_iter()
        .filter_map(|r| r.path)
        .collect();
    assert_eq!(watched, vec!["/re08f/000".to_owned()]);
    assert!(common::still_pending(rx.recv(), SHORT).await);
    server.cluster().delete("/re08f/000").unwrap();
    let (_, guard) = common::within(rx.recv()).await.unwrap();
    let order = lock_order(&server, FOREIGN);
    assert_eq!(order.len(), 2);
    assert_eq!(order[0], guard.node().name().unwrap());
    assert_eq!(order[1], "zzz");
    common::within(guard.release()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that a waiting contender retains its queue position across a reconnect
/// without session expiry, both when watches are automatically restored and when
/// `restore_watches(false)` forces the waiter to re-list and re-watch its predecessor.
#[tokio::test]
async fn waiter_keeps_its_place_across_a_reconnect() {
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    for restore in [true, false] {
        let path = format!("/reconnect-{restore}");
        let b = common::within(
            common::builder(&server.connect_string())
                .restore_watches(restore)
                .connect(),
        )
        .await
        .unwrap();
        let held = common::within(DistributedLock::new(&a, &path).unwrap().acquire())
            .await
            .unwrap();
        let (tx, mut rx) = mpsc::unbounded_channel();
        spawn_acquire(&b, &path, 1, tx);
        until(&server, |s| s.cluster().watch_count() == 1).await;
        let own = ephemerals(&server, session_id(&b));

        let mut state = b.state_watcher();
        server.cluster().clear_transcript();
        server.drop_connections();
        common::wait_state(&mut state, |i| i.state.is_connected() && i.epoch >= 2).await;
        // Holder also reconnects with its ephemeral node intact.
        let mut state_a = a.state_watcher();
        common::wait_state(&mut state_a, |i| i.state.is_connected()).await;
        until(&server, |s| s.cluster().watch_count() == 1).await;
        assert!(common::still_pending(rx.recv(), SHORT).await, "{restore}");
        assert_eq!(ephemerals(&server, session_id(&b)), own, "{restore}");
        let reads = frames(&server, OP_GET_DATA)
            .into_iter()
            .filter(|r| r.session_id == session_id(&b))
            .count();
        // Restored watches require no additional `getData` request on reconnect.
        assert_eq!(reads, usize::from(!restore), "{restore}");

        common::within(held.release()).await.unwrap();
        let (_, guard) = common::within(rx.recv()).await.unwrap();
        assert_eq!(
            vec![guard.node().as_str().to_owned()],
            own,
            "the waiter kept its node"
        );
        common::within(guard.release()).await.unwrap();
        common::within(b.close()).await.unwrap();
    }
    common::within(a.close()).await.unwrap();
}

/// Verifies that `LockGuard::release` retries `delete` after connection loss:
/// when the initial delete was applied before disconnect, the retry's `NoNode`
/// is treated as success; when unapplied, the retry deletes the node.
#[tokio::test]
async fn release_after_connection_loss_is_sent_again() {
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    for applied in [true, false] {
        let path = format!("/release-{applied}");
        let guard = common::within(DistributedLock::new(&client, &path).unwrap().acquire())
            .await
            .unwrap();
        let node = guard.node().as_str().to_owned();
        let rule = Rule::on_request().op(OP_DELETE).path(&node).once();
        let rule = if applied {
            rule.then(Action::DropReply).then(Action::Close)
        } else {
            rule.then(Action::Close)
        };
        server.add_rule(rule);
        server.cluster().clear_transcript();
        common::within(guard.release()).await.unwrap();
        let deletes = frames(&server, OP_DELETE);
        assert_eq!(deletes.len(), 2, "{applied}");
        assert!(
            deletes
                .iter()
                .all(|r| r.path.as_deref() == Some(node.as_str()))
        );
        assert_eq!(child_count(&server, &path), 0, "{applied}");
        server.clear_rules();
    }
    common::within(client.close()).await.unwrap();
}

/// Verifies that dropping `acquire` while its `create` reply is still in flight
/// hands the late reply to the background guaranteed-delete task, removing the
/// orphan node without further calls on the lock.
#[tokio::test]
async fn dropped_create_in_flight_is_deleted_in_the_background() {
    const PATH: &str = "/orphan/lock";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    server
        .cluster()
        .create("/orphan", b"", create_mode::PERSISTENT)
        .unwrap();
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    // Apply the create immediately in the model while delaying the response frame.
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Delay(Duration::from_millis(500))),
    );
    let lock = DistributedLock::new(&a, PATH).unwrap();
    {
        let mut acquire = std::pin::pin!(lock.acquire());
        assert!(poll_once(acquire.as_mut()).is_pending());
    }
    until(&server, |s| child_count(s, PATH) == 1).await;
    let orphan = server.cluster().children(PATH).unwrap()[0].clone();
    assert_eq!(ephemerals(&server, session_id(&a)).len(), 1);

    // While the delayed create reply is still in flight, the orphan blocks other contenders.
    let other = DistributedLock::new(&b, PATH).unwrap();
    assert!(common::within(other.try_acquire()).await.unwrap().is_none());

    until(&server, |s| child_count(s, PATH) == 0).await;
    let deleted: Vec<String> = frames(&server, OP_DELETE)
        .into_iter()
        .filter_map(|r| r.path)
        .filter(|p| p.ends_with(&orphan))
        .collect();
    assert_eq!(deleted, vec![format!("{PATH}/{orphan}")]);
    let guard = common::within(other.try_acquire()).await.unwrap().unwrap();
    common::within(guard.release()).await.unwrap();

    // Subsequent acquisitions with no pending orphans issue no extra `sync` requests.
    server.cluster().clear_transcript();
    let guard = common::within(lock.acquire()).await.unwrap();
    assert!(frames(&server, OP_SYNC).is_empty());
    common::within(guard.release()).await.unwrap();
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that dropping `acquire` after a connection loss on `create` (before
/// protected-node lookup completes) hands the UUID prefix to the background
/// guaranteed-delete task, which issues `sync` + `getChildren` and deletes the
/// orphan node so other clients can acquire the lock.
#[tokio::test]
async fn dropped_lookup_after_connection_loss_is_deleted_in_the_background() {
    const PATH: &str = "/orphan-loss";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let other = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let epoch = state.current().epoch;
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::DropReply)
            .then(Action::Close),
    );
    let lock = DistributedLock::new(&client, PATH).unwrap();
    {
        let mut acquire = std::pin::pin!(lock.acquire());
        assert!(poll_once(acquire.as_mut()).is_pending());
        common::wait_state(&mut state, |i| {
            i.state != SessionState::Connected || i.epoch > epoch
        })
        .await;
        // Poll once more so `acquire` observes the connection loss and transitions
        // into the recovery lookup before being dropped.
        assert!(poll_once(acquire.as_mut()).is_pending());
    }
    assert_eq!(child_count(&server, PATH), 1);
    let orphan = server.cluster().children(PATH).unwrap()[0].clone();
    until(&server, |s| child_count(s, PATH) == 0).await;
    let deleted: Vec<String> = frames(&server, OP_DELETE)
        .into_iter()
        .filter_map(|r| r.path)
        .collect();
    assert_eq!(deleted, vec![format!("{PATH}/{orphan}")]);

    let guard = common::within(DistributedLock::new(&other, PATH).unwrap().try_acquire())
        .await
        .unwrap()
        .unwrap();
    common::within(guard.release()).await.unwrap();
    common::within(client.close()).await.unwrap();
    common::within(other.close()).await.unwrap();
}

/// Verifies that when `LockGuard::release` or `try_acquire` receives a non-connection-loss
/// error (such as `ThrottledOp`) on `delete`, it surfaces that error to the caller while
/// handing the node to the background guaranteed-delete task for immediate cleanup.
#[tokio::test]
async fn release_with_a_failed_delete_hands_the_node_over() {
    const PATH: &str = "/release-throttled";
    const ERR_THROTTLED: i32 = -127;
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let guard = common::within(DistributedLock::new(&a, PATH).unwrap().acquire())
        .await
        .unwrap();
    let node = guard.node().as_str().to_owned();
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Reply { err: ERR_THROTTLED }),
    );
    server.cluster().clear_transcript();
    assert_eq!(
        common::within(guard.release()).await,
        Err(Error::ThrottledOp)
    );
    until(&server, |s| child_count(s, PATH) == 0).await;
    assert_eq!(frames(&server, OP_DELETE).len(), 2);

    // `try_acquire` behind an active holder similarly hands off its own node on delete failure.
    let held = common::within(DistributedLock::new(&b, PATH).unwrap().acquire())
        .await
        .unwrap();
    server.clear_rules();
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Reply { err: ERR_THROTTLED }),
    );
    let lock = DistributedLock::new(&a, PATH).unwrap();
    assert_eq!(
        common::within(lock.try_acquire()).await.err(),
        Some(Error::ThrottledOp)
    );
    until(&server, |s| child_count(s, PATH) == 1).await;
    assert_eq!(lock_order(&server, PATH), vec![held.node().name().unwrap()]);
    common::within(held.release()).await.unwrap();
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that when `max_pending_requests` is saturated and a `delete` from
/// `release()` or `Drop` is rejected locally with `TooManyPendingRequests`, the
/// node is handed to the background guaranteed-delete task, which waits for queue
/// capacity and deletes the node on the same connection epoch.
#[tokio::test]
async fn refused_delete_is_sent_when_the_queue_has_room() {
    const PATH: &str = "/refused/lock";
    const LIMIT: usize = 2;
    let server = common::fake();
    let a = common::within(
        common::builder(&server.connect_string())
            .max_pending_requests(LIMIT)
            .connect(),
    )
    .await
    .unwrap();
    let b = common::connect(&server.connect_string()).await;
    let epoch = a.state().epoch;
    let lock = DistributedLock::new(&a, PATH).unwrap();
    for drop_guard in [false, true] {
        let guard = common::within(lock.acquire()).await.unwrap();
        server.cluster().clear_transcript();
        server.hold();
        let reads: Vec<_> = (0..LIMIT).map(|_| a.get_data("/")).collect();
        assert_eq!(a.stats().pending, LIMIT);
        if drop_guard {
            drop(guard);
        } else {
            assert_eq!(
                common::within(guard.release()).await,
                Err(Error::TooManyPendingRequests)
            );
        }
        tokio::time::sleep(SHORT).await;
        assert_eq!(child_count(&server, PATH), 1);
        server.release();
        for read in reads {
            common::within(read).await.unwrap();
        }
        until(&server, |s| child_count(s, PATH) == 0).await;
        assert_eq!(frames(&server, OP_DELETE).len(), 1);
    }
    assert_eq!(a.state().epoch, epoch);
    let guard = common::within(DistributedLock::new(&b, PATH).unwrap().try_acquire())
        .await
        .unwrap()
        .unwrap();
    common::within(guard.release()).await.unwrap();
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that dropping a `LockGuard::release` future while its `delete` is in
/// flight hands the node to the background guaranteed-delete task, which re-sends
/// the `delete` across subsequent connection loss and reconnection.
#[tokio::test]
async fn dropped_release_with_lost_delete_is_sent_again() {
    const PATH: &str = "/release-dropped";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let guard = common::within(DistributedLock::new(&a, PATH).unwrap().acquire())
        .await
        .unwrap();
    let node = guard.node().as_str().to_owned();
    for _ in 0..2 {
        server.add_rule(
            Rule::on_request()
                .op(OP_DELETE)
                .path(&node)
                .once()
                .then(Action::Ignore),
        );
    }
    server.cluster().clear_transcript();
    {
        let mut release = std::pin::pin!(guard.release());
        assert!(poll_once(release.as_mut()).is_pending());
    }
    until(&server, |s| s.script().rules()[1].fired() > 0).await;
    assert_eq!(child_count(&server, PATH), 1);
    server.drop_connections();
    until(&server, |s| child_count(s, PATH) == 0).await;
    assert_eq!(frames(&server, OP_DELETE).len(), 3);
    let guard = common::within(DistributedLock::new(&b, PATH).unwrap().try_acquire())
        .await
        .unwrap()
        .unwrap();
    common::within(guard.release()).await.unwrap();
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that if a `delete` fails with `NotReadOnly` (proving the server did
/// not apply it) and the subsequent retry returns `NoNode`, `release()` reports
/// `Err(NoNode)` because the lock node was externally deleted prior to release.
#[tokio::test]
async fn release_after_not_read_only_reports_a_foreign_delete() {
    const PATH: &str = "/release-ro";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    let node = guard.node().as_str().to_owned();
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Reply {
                err: ERR_NOT_READ_ONLY,
            })
            .then(Action::Close),
    );
    // Delete the node externally before `release` is called.
    server.cluster().delete(&node).unwrap();
    server.cluster().clear_transcript();
    let released = common::within(guard.release()).await;
    assert_eq!(released, Err(Error::NoNode));
    assert_eq!(frames(&server, OP_DELETE).len(), 2);
    common::within(client.close()).await.unwrap();
}

/// Verifies parent container creation beneath an ancestor that returns `NoAuth`
/// on `exists` (which ZooKeeper returns only when the node exists but lacks `READ`
/// permission; unlike Curator's `ZKPaths.mkdirs`, the recipes treat `NoAuth` on
/// `exists` as an existing parent and create missing child containers beneath it).
#[tokio::test]
async fn parents_below_an_unreadable_ancestor() {
    const TOP: &str = "/noread";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    server
        .cluster()
        .create(TOP, b"", create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_EXISTS)
            .path(TOP)
            .then(Action::Reply { err: ERR_NO_AUTH }),
    );
    let lock = DistributedLock::new(&client, "/noread/locks/job").unwrap();
    let guard = common::within(lock.acquire()).await.unwrap();
    assert!(guard.node().as_str().starts_with("/noread/locks/job/_c_"));
    let made: Vec<String> = frames(&server, OP_CREATE_CONTAINER)
        .into_iter()
        .filter_map(|r| r.path)
        .collect();
    assert_eq!(made, ["/noread/locks", "/noread/locks/job"]);
    common::within(guard.release()).await.unwrap();

    let latch = LeaderLatch::new(&client, "/noread/latch", "p1").unwrap();
    let mut latch = common::within(latch.start()).await.unwrap();
    assert!(common::within(latch.changed()).await.unwrap());
    common::within(latch.close()).await.unwrap();
    assert!(server.script().rules()[0].fired() > 0);
    common::within(client.close()).await.unwrap();
}

/// Verifies that when a dropped `LockGuard`'s `delete` is lost due to connection
/// closure, the background guaranteed-delete task re-sends the delete upon
/// reconnection without extra calls on the lock, unblocking a waiting contender.
#[tokio::test]
async fn guard_drop_with_lost_delete_is_sent_again_after_the_reconnect() {
    const PATH: &str = "/ghost/lock";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let other = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let lock = DistributedLock::new(&client, PATH).unwrap();

    let guard = common::within(lock.acquire()).await.unwrap();
    server.cluster().clear_transcript();
    drop(guard);
    let guard = common::within(lock.acquire()).await.unwrap();
    assert_eq!(frames(&server, OP_DELETE).len(), 1);
    assert!(frames(&server, OP_SYNC).is_empty());

    let node = guard.node().as_str().to_owned();
    let epoch = state.current().epoch;
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Close),
    );
    server.cluster().clear_transcript();
    let waiter = {
        let other = DistributedLock::new(&other, PATH).unwrap();
        tokio::spawn(async move { other.acquire().await })
    };
    until(&server, |s| s.cluster().watch_count() == 1).await;
    drop(guard);
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;

    let guard = common::within(waiter).await.unwrap().unwrap();
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    let resent = frames(&server, OP_DELETE)
        .into_iter()
        .filter(|r| r.path.as_deref() == Some(node.as_str()))
        .count();
    assert_eq!(resent, 2);
    common::within(guard.release()).await.unwrap();
    common::within(client.close()).await.unwrap();
    common::within(other.close()).await.unwrap();
}

/// Verifies that when a waiting `acquire` future is dropped and its `delete`
/// fails with connection loss, the background guaranteed-delete task re-sends
/// the delete upon reconnection so the next contender acquires the lock when
/// the holder releases.
#[tokio::test]
async fn waiting_acquire_drop_with_lost_delete_is_sent_again() {
    const PATH: &str = "/ghost-wait/lock";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let c = common::connect(&server.connect_string()).await;
    let mut state = b.state_watcher();
    let held = common::within(DistributedLock::new(&a, PATH).unwrap().acquire())
        .await
        .unwrap();
    let held_name = held.node().name().unwrap().to_owned();
    let lock = DistributedLock::new(&b, PATH).unwrap();
    let epoch = state.current().epoch;
    let mut acquire = Box::pin(lock.acquire());
    common::within(async {
        while server.cluster().watch_count() == 0 {
            assert!(poll_once(acquire.as_mut()).is_pending());
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await;
    let waiter = lock_order(&server, PATH)
        .into_iter()
        .find(|c| *c != held_name)
        .unwrap();
    let third = {
        let lock = DistributedLock::new(&c, PATH).unwrap();
        tokio::spawn(async move { lock.acquire().await })
    };
    until(&server, |s| s.cluster().watch_count() == 2).await;
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&format!("{PATH}/{waiter}"))
            .once()
            .then(Action::Close),
    );
    drop(acquire);
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    until(&server, |s| child_count(s, PATH) == 2).await;
    assert!(!lock_order(&server, PATH).contains(&waiter));
    common::within(held.release()).await.unwrap();

    let guard = common::within(third).await.unwrap().unwrap();
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    common::within(guard.release()).await.unwrap();
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
    common::within(c.close()).await.unwrap();
}

/// Verifies that if a background guaranteed-delete retry itself encounters a
/// connection loss, the node remains queued and is retried on the next epoch
/// until deletion succeeds.
#[tokio::test]
async fn lost_retry_is_sent_again_at_the_next_epoch() {
    const PATH: &str = "/ghost-requeue/lock";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let lock = DistributedLock::new(&client, PATH).unwrap();
    let guard = common::within(lock.acquire()).await.unwrap();
    let node = guard.node().as_str().to_owned();
    for _ in 0..2 {
        server.add_rule(
            Rule::on_request()
                .op(OP_DELETE)
                .path(&node)
                .once()
                .then(Action::Close),
        );
    }
    server.cluster().clear_transcript();
    drop(guard);
    until(&server, |s| child_count(s, PATH) == 0).await;
    assert!(server.script().rules()[1].fired() > 0);
    assert_eq!(frames(&server, OP_DELETE).len(), 3);
    common::within(client.close()).await.unwrap();
}

/// Creates a protected participant node whose reply is dropped on disconnect and
/// drops the `acquire` future before recovery lookup completes, returning the
/// resulting orphan node name.
async fn lose_create(
    server: &FakeServer,
    client: &Client,
    lock: &DistributedLock,
    path: &str,
) -> String {
    let before = lock_order(server, path);
    let mut state = client.state_watcher();
    let epoch = state.current().epoch;
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{path}/_c_"))
            .once()
            .then(Action::DropReply)
            .then(Action::Close),
    );
    {
        let mut acquire = std::pin::pin!(lock.acquire());
        assert!(poll_once(acquire.as_mut()).is_pending());
        common::wait_state(&mut state, |i| {
            i.state != SessionState::Connected || i.epoch > epoch
        })
        .await;
        assert!(poll_once(acquire.as_mut()).is_pending());
    }
    let after = server.cluster().children(path).unwrap();
    after.into_iter().find(|c| !before.contains(c)).unwrap()
}

/// Verifies that background prefix lookup deletes only child nodes matching the
/// exact recorded `_c_<uuid>-` prefix, preserving nodes whose UUID differs by even
/// a single character.
#[tokio::test]
async fn background_lookup_keeps_the_nodes_of_other_ids() {
    const PATH: &str = "/orphan-keep/lock";
    const OTHER: &str = "_c_00000000-0000-4000-8000-000000000000-lock-9999999999";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    let mut state = a.state_watcher();
    server
        .cluster()
        .create("/orphan-keep", b"", create_mode::PERSISTENT)
        .unwrap();
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    server
        .cluster()
        .create(&format!("{PATH}/{OTHER}"), b"", create_mode::PERSISTENT)
        .unwrap();
    // Reject `sync` with `NotReadOnly` until the near-miss sibling node is created
    // so the background lookup does not scan the parent prematurely.
    server.add_rule(
        Rule::on_request()
            .op(OP_SYNC)
            .path(PATH)
            .then(Action::Reply {
                err: ERR_NOT_READ_ONLY,
            }),
    );
    let lock = DistributedLock::new(&a, PATH).unwrap();
    let orphan = lose_create(&server, &a, &lock, PATH).await;
    // Construct a sibling node name sharing the orphan's `_c_<uuid>` prefix except
    // for the final hex digit of the UUID.
    let id = &orphan[..39];
    let near_last = if id.ends_with('0') { '1' } else { '0' };
    let near = format!("{}{near_last}-lock-9999999998", &id[..38]);
    server
        .cluster()
        .create(&format!("{PATH}/{near}"), b"", create_mode::PERSISTENT)
        .unwrap();
    server.clear_rules();
    let epoch = state.current().epoch;
    server.drop_connections();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    until(&server, |s| !lock_order(s, PATH).contains(&orphan)).await;
    let kids = lock_order(&server, PATH);
    assert!(kids.contains(&OTHER.to_owned()), "{kids:?}");
    assert!(kids.contains(&near), "{kids:?}");
    assert_eq!(kids.len(), 2, "{kids:?}");
    common::within(a.close()).await.unwrap();
}

/// Verifies that background prefix lookup retries across connection losses on both
/// its `sync` and its subsequent `delete` until the orphan node is removed.
#[tokio::test]
async fn background_lookup_retries_after_a_loss() {
    const PATH: &str = "/orphan-reap-loss";
    let server = common::fake();
    let a = common::connect(&server.connect_string()).await;
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_SYNC)
            .path(PATH)
            .once()
            .then(Action::Close),
    );
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Close),
    );
    let lock = DistributedLock::new(&a, PATH).unwrap();
    let orphan = lose_create(&server, &a, &lock, PATH).await;
    until(&server, |s| child_count(s, PATH) == 0).await;
    assert!(server.script().rules()[0].fired() > 0);
    assert!(server.script().rules()[1].fired() > 0);
    let deletes = frames(&server, OP_DELETE)
        .into_iter()
        .filter(|r| r.path.as_deref() == Some(format!("{PATH}/{orphan}").as_str()))
        .count();
    assert_eq!(deletes, 2);
    common::within(a.close()).await.unwrap();
}

/// Verifies that when `max_pending_requests` rejects the `sync` of a background
/// prefix lookup, the lookup waits for queue capacity and completes on the same
/// connection epoch.
#[tokio::test]
async fn background_lookup_waits_for_room_in_the_queue() {
    const PATH: &str = "/orphan-full";
    const LIMIT: usize = 2;
    let server = common::fake();
    let a = common::within(
        common::builder(&server.connect_string())
            .max_pending_requests(LIMIT)
            .connect(),
    )
    .await
    .unwrap();
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    // Reject reconnect attempts so the background lookup blocks awaiting a new epoch.
    server.add_rule(Rule::on_connect().then(Action::Close));
    let lock = DistributedLock::new(&a, PATH).unwrap();
    let orphan = lose_create(&server, &a, &lock, PATH).await;
    // Saturate the request queue while disconnected; delaying the queued reads on
    // reconnect causes the background lookup's `sync` at the new epoch to hit
    // `TooManyPendingRequests` initially.
    let reads: Vec<_> = (0..LIMIT).map(|_| a.get_data("/")).collect();
    assert_eq!(a.stats().pending, LIMIT);
    server.cluster().clear_transcript();
    server.set_script(
        Script::new().rule(
            Rule::on_request()
                .op(OP_GET_DATA)
                .path("/")
                .then(Action::Delay(SHORT)),
        ),
    );
    let mut state = a.state_watcher();
    let connected = common::wait_state(&mut state, |i| i.state == SessionState::Connected).await;
    for read in reads {
        common::within(read).await.unwrap();
    }
    until(&server, |s| child_count(s, PATH) == 0).await;
    assert_eq!(a.state().epoch, connected.epoch);
    let deleted: Vec<String> = frames(&server, OP_DELETE)
        .into_iter()
        .filter_map(|r| r.path)
        .collect();
    assert_eq!(deleted, vec![format!("{PATH}/{orphan}")]);
    common::within(a.close()).await.unwrap();
}

/// Verifies that when a `LockGuard` is dropped on a read-only connection (receiving
/// `NotReadOnly`), the background guaranteed-delete task refrains from retrying on
/// subsequent `ConnectedReadOnly` epochs and deletes the node once the session
/// reconnects in read-write `Connected` state.
#[tokio::test]
async fn read_only_connection_does_not_delete() {
    const PATH: &str = "/ro-ghost/lock";
    let server = common::fake();
    let client = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    let other = common::connect(&server.connect_string()).await;
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    let node = guard.node().as_str().to_owned();
    let mut state = client.state_watcher();
    server.set_read_only(true);
    server.drop_connections();
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    server.cluster().clear_transcript();
    drop(guard);
    until(&server, |s| !frames(s, OP_DELETE).is_empty()).await;
    // Subsequent read-only epochs must not trigger a retry.
    let epoch = state.current().epoch;
    server.drop_connections();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::ConnectedReadOnly && i.epoch > epoch
    })
    .await;
    tokio::time::sleep(SHORT).await;
    assert_eq!(frames(&server, OP_DELETE).len(), 1);
    assert!(server.cluster().node(&node).is_some());

    server.set_read_only(false);
    server.drop_connections();
    until(&server, |s| s.cluster().node(&node).is_none()).await;
    assert_eq!(frames(&server, OP_DELETE).len(), 2);
    let guard = common::within(DistributedLock::new(&other, PATH).unwrap().try_acquire())
        .await
        .unwrap()
        .unwrap();
    common::within(guard.release()).await.unwrap();
    common::within(other.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies `release()` across `ConnectionLost` -> `NotReadOnly` -> `NoNode`:
/// because the initial `delete` on a read-write connection may have been applied
/// prior to `ConnectionLost`, the final `NoNode` is treated as a successful release
/// even with an intervening `NotReadOnly`.
#[tokio::test]
async fn release_loss_then_not_read_only_then_no_node() {
    const PATH: &str = "/release-loss-ro";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    let node = guard.node().as_str().to_owned();
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::DropReply)
            .then(Action::Close),
    );
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Reply {
                err: ERR_NOT_READ_ONLY,
            })
            .then(Action::Close),
    );
    server.cluster().clear_transcript();
    let released = common::within(guard.release()).await;
    assert_eq!(frames(&server, OP_DELETE).len(), 3);
    assert_eq!(released, Ok(()));
    common::within(client.close()).await.unwrap();
}

/// Verifies that if the protected-create recovery lookup receives `NotReadOnly`
/// on its `sync`, it waits for the next connection epoch, re-lists the parent,
/// and adopts the existing node without failing or creating a duplicate.
#[tokio::test]
async fn lookup_sync_not_read_only_waits() {
    const PATH: &str = "/lookup-ro";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    server
        .cluster()
        .create(PATH, b"", create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::DropReply)
            .then(Action::Close),
    );
    server.add_rule(
        Rule::on_request()
            .op(OP_SYNC)
            .path(PATH)
            .once()
            .then(Action::Reply {
                err: ERR_NOT_READ_ONLY,
            })
            .then(Action::Close),
    );
    server.cluster().clear_transcript();
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    assert!(server.script().rules()[1].fired() > 0);
    assert_eq!(frames(&server, OP_CREATE2).len(), 1);
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    common::within(guard.release()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that a connection loss on a `delete` sent over a `ConnectedReadOnly`
/// session does not cause a subsequent `NoNode` to be treated as a successful
/// self-delete (since a read-only server never applies writes, only an external
/// client in the majority partition could have deleted the node).
#[tokio::test]
async fn release_after_loss_on_read_only_reports_a_foreign_delete() {
    const PATH: &str = "/release-ro-loss";
    let server = common::fake();
    let client = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    let guard = common::within(DistributedLock::new(&client, PATH).unwrap().acquire())
        .await
        .unwrap();
    let node = guard.node().as_str().to_owned();
    let mut state = client.state_watcher();
    server.set_read_only(true);
    server.drop_connections();
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    // First delete on read-only connection: model replies `NotReadOnly`.
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Model),
    );
    // Second delete on the next read-only connection: connection closes before
    // the model processes the frame.
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Close),
    );
    let release = tokio::spawn(guard.release());
    until(&server, |s| s.script().rules()[0].fired() > 0).await;
    server.drop_connections();
    until(&server, |s| s.script().rules()[1].fired() > 0).await;
    // External client in the majority partition deletes the lock node.
    server.cluster().delete(&node).unwrap();
    server.set_read_only(false);
    server.drop_connections();
    let released = common::within(release).await.unwrap();
    assert_eq!(released, Err(Error::NoNode));
    common::within(client.close()).await.unwrap();
}

/// Verifies that when two `acquire` futures share a `DistributedLock` instance
/// (`acquire_a` ahead of `waiter` behind an external holder) and `acquire_a` is
/// dropped with a lost `delete`, the background guaranteed-delete removes
/// `acquire_a`'s ghost node on reconnect so `waiter` acquires the lock once
/// the external holder releases.
#[tokio::test]
async fn waiting_acquire_passes_a_sibling_lost_delete() {
    const PATH: &str = "/ghost-sibling/lock";
    let server = common::fake();
    let h = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let mut state = b.state_watcher();
    let held = common::within(DistributedLock::new(&h, PATH).unwrap().acquire())
        .await
        .unwrap();
    let lock = Arc::new(DistributedLock::new(&b, PATH).unwrap());
    let epoch = state.current().epoch;
    let mut acquire_a = Box::pin(lock.acquire());
    common::within(async {
        while server.cluster().watch_count() == 0 {
            assert!(poll_once(acquire_a.as_mut()).is_pending());
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await;
    let a_node = format!("{PATH}/{}", lock_order(&server, PATH)[1]);
    let waiter = {
        let lock = Arc::clone(&lock);
        tokio::spawn(async move { lock.acquire().await })
    };
    until(&server, |s| {
        s.cluster().watch_count() == 2 && child_count(s, PATH) == 3
    })
    .await;
    let b_name = lock_order(&server, PATH)[2].clone();
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&a_node)
            .once()
            .then(Action::Close),
    );
    server.cluster().clear_transcript();
    drop(acquire_a);
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    common::within(held.release()).await.unwrap();

    let guard = common::within(waiter).await.unwrap().unwrap();
    assert_eq!(guard.node().name().unwrap(), b_name);
    assert_eq!(lock_order(&server, PATH), vec![b_name]);
    let resent = frames(&server, OP_DELETE)
        .into_iter()
        .filter(|r| r.path.as_deref() == Some(a_node.as_str()))
        .count();
    assert_eq!(resent, 2);
    common::within(guard.release()).await.unwrap();
    common::within(h.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that background guaranteed-delete removes only the specific node abandoned
/// by a cancelled `acquire` future, leaving active sibling `acquire` nodes on the same
/// `DistributedLock` intact across reconnection.
#[tokio::test]
async fn guaranteed_delete_keeps_the_own_nodes() {
    const PATH: &str = "/ghost-own/lock";
    let server = common::fake();
    let h = common::connect(&server.connect_string()).await;
    let b = common::connect(&server.connect_string()).await;
    let mut state = b.state_watcher();
    let held = common::within(DistributedLock::new(&h, PATH).unwrap().acquire())
        .await
        .unwrap();
    let lock = Arc::new(DistributedLock::new(&b, PATH).unwrap());
    let spawn = |lock: &Arc<DistributedLock>| {
        let lock = Arc::clone(lock);
        tokio::spawn(async move { lock.acquire().await })
    };
    let first = spawn(&lock);
    until(&server, |s| s.cluster().watch_count() == 1).await;
    let second = spawn(&lock);
    until(&server, |s| s.cluster().watch_count() == 2).await;
    let waiters = lock_order(&server, PATH)[1..].to_vec();
    assert_eq!(waiters.len(), 2);

    server.cluster().clear_transcript();
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Delay(Duration::from_millis(300))),
    );
    {
        let mut acquire = std::pin::pin!(lock.acquire());
        assert!(poll_once(acquire.as_mut()).is_pending());
    }
    until(&server, |s| {
        child_count(s, PATH) == 3 && !frames(s, OP_DELETE).is_empty()
    })
    .await;
    server.clear_rules();
    let create = frames(&server, OP_CREATE2)[0].path.clone().unwrap();
    let deletes = frames(&server, OP_DELETE);
    assert_eq!(deletes.len(), 1, "{deletes:?}");
    assert!(deletes[0].path.as_deref().unwrap().starts_with(&create));
    assert_eq!(lock_order(&server, PATH)[1..], waiters[..]);

    let epoch = state.current().epoch;
    server.drop_connections();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    tokio::time::sleep(SHORT).await;
    assert_eq!(frames(&server, OP_DELETE).len(), 1);
    assert_eq!(lock_order(&server, PATH)[1..], waiters[..]);
    assert!(!first.is_finished() && !second.is_finished());

    common::within(held.release()).await.unwrap();
    let guard = common::within(first).await.unwrap().unwrap();
    assert_eq!(guard.node().name().unwrap(), waiters[0]);
    common::within(guard.release()).await.unwrap();
    let guard = common::within(second).await.unwrap().unwrap();
    assert_eq!(guard.node().name().unwrap(), waiters[1]);
    assert_eq!(lock_order(&server, PATH), vec![waiters[1].clone()]);
    common::within(guard.release()).await.unwrap();
    common::within(h.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}

/// Verifies that `acquire` never grants lock ownership from a child listing served over a
/// `ConnectedReadOnly` connection; if the predecessor node is deleted while connected read-only,
/// acquisition suspends until a read-write connection is re-established.
#[tokio::test]
async fn acquire_waits_for_read_write_after_predecessor_deleted_on_read_only() {
    const PATH: &str = "/acquire-ro-grant";
    let server = common::fake();
    let holder = common::connect(&server.connect_string()).await;
    let waiter = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    let held = common::within(DistributedLock::new(&holder, PATH).unwrap().acquire())
        .await
        .unwrap();
    let held_node = held.node().as_str().to_owned();
    let lock = DistributedLock::new(&waiter, PATH).unwrap();
    let mut acquire = tokio::spawn(async move { lock.acquire().await });
    until(&server, |s| s.cluster().watch_count() == 1).await;

    let mut state = waiter.state_watcher();
    server.set_read_only(true);
    server.drop_connections();
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    // Removing the holder's znode while the waiter is connected read-only exposes the candidate as
    // first in the read-only replica's tree, which must not grant ownership prior to read-write
    // reconnection.
    server.cluster().delete(&held_node).unwrap();
    assert!(common::still_pending(&mut acquire, SHORT).await);

    server.set_read_only(false);
    server.drop_connections();
    let guard = common::within(acquire).await.unwrap().unwrap();
    assert_eq!(waiter.state().state, SessionState::Connected);
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    common::within(guard.release()).await.unwrap();
    drop(held);
    common::within(holder.close()).await.unwrap();
    common::within(waiter.close()).await.unwrap();
}

/// Verifies that `try_acquire` never evaluates lock ownership from a child listing served over a
/// `ConnectedReadOnly` connection, suspending after an interrupted initial listing until a
/// read-write connection is restored.
#[tokio::test]
async fn try_acquire_waits_for_read_write_on_read_only_listing() {
    const PATH: &str = "/try-acquire-ro";
    let server = common::fake();
    let client = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_GET_CHILDREN)
            .path(PATH)
            .once()
            .then(Action::Ignore),
    );
    let lock = DistributedLock::new(&client, PATH).unwrap();
    let mut attempt = tokio::spawn(async move { lock.try_acquire().await });
    until(&server, |s| s.script().rules()[0].fired() > 0).await;
    assert_eq!(child_count(&server, PATH), 1);

    let mut state = client.state_watcher();
    server.set_read_only(true);
    server.drop_connections();
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    assert!(common::still_pending(&mut attempt, SHORT).await);

    server.set_read_only(false);
    server.drop_connections();
    let guard = common::within(attempt).await.unwrap().unwrap().unwrap();
    assert_eq!(client.state().state, SessionState::Connected);
    assert_eq!(
        lock_order(&server, PATH),
        vec![guard.node().name().unwrap()]
    );
    common::within(guard.release()).await.unwrap();
    common::within(client.close()).await.unwrap();
}
