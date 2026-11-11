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

//! Integration tests for `DistributedLock` against standalone and three node ensemble
//! ZooKeeper servers: lock contention, predecessor watch chains, ensemble leader
//! election while holding a lock, container creation under unreadable ancestors,
//! and proxy injected lost create/delete recovery.
//!
//! Server instances are provisioned via `common::Servers` (`ZK_TEST_PORT_BASE` and
//! `ZK_TEST_SERVER_VERSION`). Tests are marked `#[ignore = "real-server"]` and skip
//! when no server can be started.
//!
//! Lost create scenarios interpose a `common::Proxy` between client and server (or
//! follower on an ensemble), testing both unapplied creates (held before connection cut)
//! and applied creates with dropped replies (muted before connection cut). Lost delete
//! scenarios drop the `delete` frame from a dropped `LockGuard` and verify background
//! guaranteed delete cleanup upon reconnection.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    // Shared scenario futures hold multiple clients and recipe instances;
    // the 8 KiB stack frame lint targets library code rather than integration tests.
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use apache_zookeeper::acl::{Acl, Id, OPEN_ACL_UNSAFE, Perms};
use apache_zookeeper::{Client, CreateMode, Error, SessionState};
use apache_zookeeper_recipes::{DistributedLock, LeaderLatch, LockGuard};
use common::{Servers, Topology, session_id};
use tokio::sync::mpsc;

/// Timeout window used to assert that a waiting contender does not acquire the lock.
const SHORT: Duration = Duration::from_millis(300);

/// Returns the children of `path` sorted in Curator lock order after a `sync` barrier.
async fn lock_order(observer: &Client, path: &str) -> Vec<String> {
    let mut c = common::synced_children(observer, path).await;
    c.sort_by(|a, b| {
        let key = |s: &str| s.rsplit_once("lock-").map_or(s, |(_, t)| t).to_owned();
        key(a).encode_utf16().cmp(key(b).encode_utf16())
    });
    c
}

/// Polls `observer` until `path` has exactly `n` children.
async fn until_children(observer: &Client, path: &str, n: usize) {
    common::within(async {
        while lock_order(observer, path).await.len() != n {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
}

/// Queries active data watches by path across all server `ports` via the `wchp`
/// four-letter command, returning the union of watched paths and session IDs.
fn watches_by_path(ports: &[u16]) -> BTreeMap<String, BTreeSet<i64>> {
    let mut out: BTreeMap<String, BTreeSet<i64>> = BTreeMap::new();
    for &port in ports {
        let addr = SocketAddr::from(([127, 0, 0, 1], port));
        let mut s = TcpStream::connect_timeout(&addr, Duration::from_secs(2)).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        s.write_all(b"wchp").unwrap();
        let mut text = String::new();
        s.read_to_string(&mut text).unwrap();
        let mut at: Option<String> = None;
        for line in text.lines() {
            if let Some(hex) = line.trim().strip_prefix("0x") {
                let id = u64::from_str_radix(hex, 16).unwrap() as i64;
                out.entry(at.clone().unwrap()).or_default().insert(id);
            } else if line.starts_with('/') {
                at = Some(line.trim().to_owned());
            }
        }
    }
    out
}

/// Polls `wchp` across `ports` until `pred` is satisfied.
async fn until_watches(ports: &[u16], pred: impl Fn(&BTreeMap<String, BTreeSet<i64>>) -> bool) {
    common::within(async {
        while !pred(&watches_by_path(ports)) {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await;
}

/// Filters `all` watches to child nodes directly under `path`.
fn lock_watches(
    all: &BTreeMap<String, BTreeSet<i64>>,
    path: &str,
) -> BTreeMap<String, BTreeSet<i64>> {
    let prefix = format!("{path}/");
    all.iter()
        .filter(|(p, _)| p.starts_with(&prefix))
        .map(|(p, s)| (p.clone(), s.clone()))
        .collect()
}

/// Five contenders acquiring a lock in creation order on a standalone server.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_five_contenders() {
    five_contenders(Topology::Standalone).await;
}

/// Five contenders acquiring a lock in creation order across a three node ensemble.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_five_contenders_ensemble() {
    five_contenders(Topology::Ensemble).await;
}

/// Verifies mutual exclusion, FIFO acquisition order, and predecessor only data
/// watches (`wchp`) across five concurrent contenders.
async fn five_contenders(topology: Topology) {
    const PATH: &str = "/re12/re01/lock";
    let Some(servers) = Servers::start_or_skip(topology) else {
        return;
    };
    let ports = servers.client_ports();
    let observer = common::connect(&servers.connect_string()).await;
    let mut clients = Vec::new();
    for _ in 0..5 {
        clients.push(common::connect(&servers.connect_string()).await);
    }
    let (tx, mut rx) = mpsc::unbounded_channel::<(usize, LockGuard)>();
    for (n, c) in clients.iter().enumerate() {
        let lock = DistributedLock::new(c, PATH).unwrap();
        let tx = tx.clone();
        tokio::spawn(async move {
            let guard = lock.acquire().await.expect("acquire");
            let _ = tx.send((n, guard));
        });
        until_children(&observer, PATH, n + 1).await;
    }
    let order = lock_order(&observer, PATH).await;
    let sessions: Vec<i64> = clients.iter().map(session_id).collect();

    // Contender k + 1 watches only contender k's node under the lock path.
    let watched: Vec<(String, BTreeSet<i64>)> = (0..4)
        .map(|n| {
            (
                format!("{PATH}/{}", order[n]),
                BTreeSet::from([sessions[n + 1]]),
            )
        })
        .collect();
    let expected: BTreeMap<String, BTreeSet<i64>> = watched.iter().cloned().collect();
    until_watches(&ports, |w| lock_watches(w, PATH) == expected).await;

    for (n, own) in order.iter().enumerate() {
        let (got, guard) = common::within(rx.recv()).await.unwrap();
        assert_eq!(got, n, "the lock goes in creation order");
        assert_eq!(guard.node().name(), Some(own.as_str()));
        assert!(common::still_pending(rx.recv(), SHORT).await, "one holder");
        assert_eq!(lock_order(&observer, PATH).await.first(), Some(own));
        // Remaining waiters retain their predecessor watches undisturbed.
        let left: BTreeMap<String, BTreeSet<i64>> = watched.iter().skip(n).cloned().collect();
        assert_eq!(lock_watches(&watches_by_path(&ports), PATH), left);
        common::within(guard.release()).await.unwrap();
    }
    assert!(lock_order(&observer, PATH).await.is_empty());
    for c in clients {
        common::within(c.close()).await.unwrap();
    }
    common::within(observer.close()).await.unwrap();
}

/// Verifies that a held lock and a queued waiter survive an ensemble leader election:
/// `lost()` stays pending across reconnection, the holder retains first position,
/// and releasing the lock on the new leader wakes the waiter.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_held_through_leader_change() {
    const PATH: &str = "/re12/leader-change/lock";
    let Some(servers) = Servers::start_or_skip(Topology::Ensemble) else {
        return;
    };
    let ensemble = servers.ensemble().unwrap();
    let cs = servers.connect_string();
    let connect = || async {
        common::within(
            common::builder(&cs)
                .session_timeout(Duration::from_secs(10))
                .connect(),
        )
        .await
        .expect("connect")
    };
    let holder = connect().await;
    let waiter = connect().await;
    let observer = connect().await;

    let guard = common::within(DistributedLock::new(&holder, PATH).unwrap().acquire())
        .await
        .unwrap();
    let lock = DistributedLock::new(&waiter, PATH).unwrap();
    let (tx, mut rx) = mpsc::unbounded_channel::<LockGuard>();
    tokio::spawn(async move {
        let g = lock.acquire().await.expect("acquire");
        let _ = tx.send(g);
    });
    until_children(&observer, PATH, 2).await;
    let before = lock_order(&observer, PATH).await;
    assert_eq!(before.first().map(String::as_str), guard.node().name());

    let sessions = [holder.session().0, waiter.session().0];
    let epochs = [holder.state().epoch, waiter.state().epoch];
    let mut states = [holder.state_watcher(), waiter.state_watcher()];
    let old = ensemble.leader().expect("a leader");
    ensemble.stop_node(old).unwrap();
    let new = ensemble.wait_leader(Some(old)).expect("a new leader");
    assert_ne!(new, old);
    for (w, e) in states.iter_mut().zip(epochs) {
        common::wait_state(w, |i| i.state == SessionState::Connected && i.epoch > e).await;
    }
    assert_eq!([holder.session().0, waiter.session().0], sessions);

    // Lock remains held across the leader election.
    assert!(
        common::still_pending(guard.lost(), SHORT).await,
        "the lock is not lost"
    );
    assert!(common::still_pending(rx.recv(), SHORT).await, "one holder");
    assert_eq!(lock_order(&observer, PATH).await, before);

    // Releasing the lock transfers ownership to the waiting contender.
    common::within(guard.release()).await.unwrap();
    let next = common::within(rx.recv()).await.unwrap();
    assert_eq!(next.node().name(), Some(before[1].as_str()));
    assert_eq!(lock_order(&observer, PATH).await, before[1..]);
    common::within(next.release()).await.unwrap();
    assert!(lock_order(&observer, PATH).await.is_empty());
    for c in [holder, waiter, observer] {
        common::within(c.close()).await.unwrap();
    }
}

/// Verifies parent container creation beneath an ancestor lacking `READ` permission.
///
/// ZooKeeper returns `NoAuth` for `exists` on an existing unreadable node (ZOOKEEPER-2590);
/// `DistributedLock` and `LeaderLatch` treat `NoAuth` on `exists` as an existing parent
/// and create missing child containers beneath it (unlike Curator's `ZKPaths.mkdirs`,
/// which fails with `NoAuth` when the deepest existing ancestor is unreadable).
#[tokio::test]
#[ignore = "real-server"]
async fn re12_parents_below_an_unreadable_ancestor() {
    const TOP: &str = "/re12-noread";
    let Some(servers) = Servers::start_or_skip(Topology::Standalone) else {
        return;
    };
    let client = common::connect(&servers.connect_string()).await;
    let no_read = [Acl {
        perms: Perms::CREATE | Perms::DELETE | Perms::WRITE | Perms::ADMIN,
        id: Id::ANYONE,
    }];
    common::within(client.create(TOP, b"", CreateMode::Persistent, &no_read))
        .await
        .unwrap();
    assert_eq!(common::within(client.exists(TOP)).await, Err(Error::NoAuth));

    let lock = DistributedLock::new(&client, "/re12-noread/locks/job").unwrap();
    let guard = common::within(lock.acquire()).await.unwrap();
    assert!(
        guard
            .node()
            .as_str()
            .starts_with("/re12-noread/locks/job/_c_")
    );
    common::within(guard.release()).await.unwrap();

    let latch = LeaderLatch::new(&client, "/re12-noread/latch", "p1").unwrap();
    let mut latch = common::within(latch.start()).await.unwrap();
    assert!(common::within(latch.changed()).await.unwrap());
    common::within(latch.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies protected-node recovery after a connection cut during participant creation
/// on a standalone server.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_create_recovery_through_proxy() {
    create_recovery(Topology::Standalone).await;
}

/// Verifies protected-node recovery after a connection cut during participant creation
/// through an ensemble follower.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_create_recovery_through_proxy_ensemble() {
    create_recovery(Topology::Ensemble).await;
}

/// Exercises connection loss during protected participant creation:
/// (a) when the create is applied on the server but its reply is dropped, the client
///     adopts the existing `_c_<uuid>-` node upon reconnection;
/// (b) when the create is held by the proxy and never reaches the server, the client
///     creates the node anew upon reconnection.
async fn create_recovery(topology: Topology) {
    let Some(servers) = Servers::start_or_skip(topology) else {
        return;
    };
    let target = servers.relay_port();
    let observer = common::connect(&servers.connect_string()).await;

    // (a) Create applied on server, reply dropped by proxy.
    const PA: &str = "/re12-re03a";
    common::within(observer.create(PA, &[], CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let relay = common::Proxy::start(target);
    let client = common::connect(&relay.connect_string()).await;
    let mut state = client.state_watcher();
    relay.mute_replies();
    let lock = DistributedLock::new(&client, PA).unwrap();
    let acquire = tokio::spawn(async move { lock.acquire().await });
    until_children(&observer, PA, 1).await;
    let applied = lock_order(&observer, PA).await;
    assert!(applied[0].starts_with("_c_"));
    relay.cut();
    common::wait_state(&mut state, |i| !i.state.is_connected()).await;
    relay.restore();
    let guard = common::within(acquire).await.unwrap().unwrap();
    assert!(state.current().epoch >= 2);
    assert_eq!(guard.node().name(), Some(applied[0].as_str()), "adopted");
    assert_eq!(lock_order(&observer, PA).await, applied, "one node");
    common::within(guard.release()).await.unwrap();
    assert!(lock_order(&observer, PA).await.is_empty());
    common::within(client.close()).await.unwrap();
    drop(relay);

    // (b) Create held by proxy and never delivered to server.
    const PB: &str = "/re12-re03b";
    common::within(observer.create(PB, &[], CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let proxy = common::Proxy::start(target);
    let client = common::connect(&proxy.connect_string()).await;
    let mut state = client.state_watcher();
    proxy.hold();
    let lock = DistributedLock::new(&client, PB).unwrap();
    let acquire = tokio::spawn(async move { lock.acquire().await });
    // Request is held in the proxy.
    // Server has no child nodes yet.
    tokio::time::sleep(SHORT).await;
    assert!(lock_order(&observer, PB).await.is_empty());
    proxy.cut();
    common::wait_state(&mut state, |i| !i.state.is_connected()).await;
    proxy.restore();
    let guard = common::within(acquire).await.unwrap().unwrap();
    assert!(state.current().epoch >= 2);
    let made = lock_order(&observer, PB).await;
    assert_eq!(made.len(), 1, "one node");
    assert_eq!(guard.node().name(), Some(made[0].as_str()));
    common::within(guard.release()).await.unwrap();
    assert!(lock_order(&observer, PB).await.is_empty());
    common::within(client.close()).await.unwrap();
    common::within(observer.close()).await.unwrap();
}

/// Verifies background guaranteed delete recovery after a dropped guard's `delete`
/// is lost across a proxy cut on a standalone server.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_lost_delete_through_proxy() {
    lost_delete(Topology::Standalone).await;
}

/// Verifies background guaranteed delete recovery after a dropped guard's `delete`
/// is lost across a proxy cut in front of an ensemble follower.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_lock_lost_delete_through_proxy_ensemble() {
    lost_delete(Topology::Ensemble).await;
}

/// Holds and severs the proxy connection when `guard` is dropped so its `delete`
/// never reaches the server, then verifies that background guaranteed delete
/// removes the ghost node upon reconnection and unblocks the waiting contender.
async fn lost_delete(topology: Topology) {
    const PATH: &str = "/re12/ghost/lock";
    let Some(servers) = Servers::start_or_skip(topology) else {
        return;
    };
    let proxy = common::Proxy::start(servers.relay_port());
    let a = common::connect(&proxy.connect_string()).await;
    let b = common::connect(&servers.connect_string()).await;
    let mut state = a.state_watcher();
    let session = a.session().0;

    let guard = common::within(DistributedLock::new(&a, PATH).unwrap().acquire())
        .await
        .unwrap();
    let ghost = guard.node().name().unwrap().to_owned();
    let lock = DistributedLock::new(&b, PATH).unwrap();
    let mut waiter = tokio::spawn(async move { lock.acquire().await });
    until_children(&b, PATH, 2).await;

    // Holding the proxy prevents the delete from reaching the server, blocking `waiter`.
    let epoch = state.current().epoch;
    proxy.hold();
    drop(guard);
    assert!(common::still_pending(&mut waiter, SHORT).await, "ghost");
    assert!(lock_order(&b, PATH).await.contains(&ghost));
    proxy.cut();
    common::wait_state(&mut state, |i| !i.state.is_connected()).await;
    proxy.restore();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    assert_eq!(a.session().0, session);

    let next = common::within(waiter).await.unwrap().unwrap();
    assert_eq!(
        lock_order(&b, PATH).await,
        vec![next.node().name().unwrap().to_owned()]
    );
    common::within(next.release()).await.unwrap();
    assert!(lock_order(&b, PATH).await.is_empty());
    common::within(a.close()).await.unwrap();
    common::within(b.close()).await.unwrap();
}
