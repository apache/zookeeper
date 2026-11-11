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

//! Simulated tests for `LeaderLatch` against `zk_fakeserver::FakeServer`: session
//! expiry, leadership transitions across reconnection, latch drop and cancellation
//! semantics, background guaranteed delete recovery, read-only connection handling,
//! and Curator compatible node naming and ordering.

//! Each test verifies both client facing latch state and the underlying server node
//! tree via `server.cluster()`. `FakeServer::drop_connections` severs TCP connections
//! without expiring sessions so clients reconnect at a new epoch.

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

use apache_zookeeper::{Error, SessionState};
use apache_zookeeper_recipes::{Latch, LeaderLatch};
use common::{expect, ids, is_v4, poll_once, session_id, start_latch};
use zk_fakeserver::{Action, FakeServer, Rule};

/// Timeout window used to assert that a waiting future remains pending.
const QUIET: Duration = Duration::from_millis(200);

/// `delete` opcode in fake-server transcripts.
const OP_DELETE: i32 = 2;

/// `exists` opcode in fake-server transcripts.
const OP_EXISTS: i32 = 3;

/// `getData` opcode used to watch a predecessor node.
const OP_GET_DATA: i32 = 4;

/// Sentinel `ephemeralOwner` value marking container nodes in the fake-server model.
const CONTAINER_OWNER: i64 = i64::MIN;

/// `create2` opcode used for `EphemeralSequential` participant creation.
const OP_CREATE2: i32 = 15;

/// `getChildren2` opcode used by `get_children`.
const OP_GET_CHILDREN: i32 = 12;

/// Returns the children of `path` sorted in Curator latch sequence order.
fn latch_children(server: &FakeServer, path: &str) -> Vec<String> {
    let mut kids = server.cluster().children(path).unwrap_or_default();
    kids.sort_by(|a, b| sort_key(a).cmp(sort_key(b)));
    kids
}

/// Extracts the sort key following the last `latch-` marker (matching Curator ordering).
fn sort_key(name: &str) -> &str {
    name.rsplit_once("latch-").map_or(name, |(_, tail)| tail)
}

/// Returns the full path of the child under `path` whose payload equals `id`.
fn node_of(server: &FakeServer, path: &str, id: &str) -> String {
    latch_children(server, path)
        .into_iter()
        .map(|c| format!("{path}/{c}"))
        .find(|p| server.cluster().data(p) == Some(Some(id.as_bytes().to_vec())))
        .unwrap_or_else(|| panic!("no node of {id} under {path}"))
}

/// Parses `_c_<uuid>-latch-<seq>` into `(uuid, seq)` and validates both components.
fn layout(name: &str) -> (String, String) {
    let rest = name
        .strip_prefix("_c_")
        .unwrap_or_else(|| panic!("no protected prefix: {name}"));
    let (uuid, seq) = rest
        .split_once("-latch-")
        .unwrap_or_else(|| panic!("no latch marker: {name}"));
    assert!(is_v4(uuid), "not a lowercase v4 UUID: {uuid}");
    assert_eq!(seq.len(), 10, "{name}");
    assert!(seq.bytes().all(|b| b.is_ascii_digit()), "{name}");
    (uuid.to_owned(), seq.to_owned())
}

/// Drives `latch.changed()` until `done()` returns `true` (up to [`common::WAIT`]),
/// asserting that no leadership transition occurs while polling.
async fn drive_until(latch: &mut Latch, mut done: impl FnMut() -> bool) {
    let deadline = tokio::time::Instant::now() + common::WAIT;
    while !done() {
        assert!(
            tokio::time::Instant::now() < deadline,
            "condition timed out"
        );
        let r = tokio::time::timeout(Duration::from_millis(20), latch.changed()).await;
        assert!(r.is_err(), "the latch changed: {r:?}");
    }
}

/// Counts `delete` request frames targeting `path` since the last transcript clear.
fn deletes_of(server: &FakeServer, path: &str) -> usize {
    server
        .cluster()
        .transcript()
        .iter()
        .filter(|r| r.op == Some(OP_DELETE) && r.path.as_deref() == Some(path))
        .count()
}

/// Verifies that dropping a `Latch` deletes its participant node and transfers
/// leadership to the next participant, while dropping a `changed()` future
/// preserves the in-progress wait state without registering duplicate watches.
#[tokio::test]
async fn re04_drop_of_latch_passes_leadership() {
    const PATH: &str = "/re04/latch";
    let server = common::fake();
    let c1 = common::connect(&server.connect_string()).await;
    let c2 = common::connect(&server.connect_string()).await;
    let c3 = common::connect(&server.connect_string()).await;

    let mut a = start_latch(&c1, PATH, "a").await;
    assert!(common::within(a.changed()).await.unwrap());
    assert!(a.is_leader());
    let mut b = start_latch(&c2, PATH, "b").await;
    let mut c = start_latch(&c3, PATH, "c").await;

    // Dropping `changed()` retains the existing step so each follower sets only one watch.
    for _ in 0..3 {
        assert!(common::still_pending(b.changed(), QUIET).await);
        assert!(common::still_pending(c.changed(), QUIET).await);
    }
    assert!(!b.is_leader());
    assert!(!c.is_leader());
    assert_eq!(server.cluster().watch_count(), 2);

    // Dropping a middle follower deletes its node; its successor rewatches the leader
    // while leadership remains unchanged.
    let b_node = node_of(&server, PATH, "b");
    drop(b);
    common::wait_fake(&server, |cl| cl.node(&b_node).is_none());
    assert!(common::still_pending(c.changed(), QUIET).await);
    assert!(common::still_pending(a.changed(), QUIET).await);
    assert!(a.is_leader());

    // Dropping the leader transfers leadership to the next participant in sequence order.
    drop(a);
    assert!(common::within(c.changed()).await.unwrap());
    let kids = latch_children(&server, PATH);
    assert_eq!(kids.len(), 1, "{kids:?}");
    assert_eq!(node_of(&server, PATH, "c"), format!("{PATH}/{}", kids[0]));

    drop(c);
    common::wait_fake(&server, |cl| {
        cl.children(PATH).is_some_and(|k| k.is_empty())
    });
    for client in [c1, c2, c3] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies session expiration handling: a leading latch yields `Ok(false)` followed
/// by sticky `Err(SessionExpired)` on subsequent calls, whereas a non-leading latch
/// yields `Err(SessionExpired)` immediately.
#[tokio::test]
async fn re06_expiry_while_leading() {
    const PATH: &str = "/re06/latch";
    let server = common::fake();
    let c1 = common::connect(&server.connect_string()).await;
    let c2 = common::connect(&server.connect_string()).await;
    let c3 = common::connect(&server.connect_string()).await;

    let mut a = start_latch(&c1, PATH, "a").await;
    assert!(common::within(a.changed()).await.unwrap());
    let mut b = start_latch(&c2, PATH, "b").await;
    let mut c = start_latch(&c3, PATH, "c").await;
    assert!(common::still_pending(b.changed(), QUIET).await);

    assert!(server.cluster().expire_session(session_id(&c1)));
    assert_eq!(common::within(a.changed()).await, Ok(false));
    assert!(!a.is_leader());
    for _ in 0..2 {
        assert_eq!(
            common::within(a.changed()).await,
            Err(Error::SessionExpired)
        );
    }
    assert_eq!(
        common::within(a.await_leadership()).await,
        Err(Error::SessionExpired)
    );
    assert_eq!(
        common::within(a.participants()).await,
        Err(Error::SessionExpired)
    );
    assert_eq!(common::within(a.close()).await, Err(Error::SessionExpired));

    // Ephemeral removal on leader session expiry promotes the next participant.
    common::within(b.await_leadership()).await.unwrap();
    assert!(b.is_leader());

    // Session expiry on a non-leading participant yields `SessionExpired` without `Ok(false)`.
    assert!(server.cluster().expire_session(session_id(&c3)));
    assert_eq!(
        common::within(c.changed()).await,
        Err(Error::SessionExpired)
    );
    assert_eq!(
        common::within(c.changed()).await,
        Err(Error::SessionExpired)
    );
    assert!(!c.is_leader());
    drop(c);

    let kids = latch_children(&server, PATH);
    assert_eq!(kids.len(), 1, "{kids:?}");
    assert_eq!(node_of(&server, PATH, "b"), format!("{PATH}/{}", kids[0]));
    assert!(common::still_pending(b.changed(), QUIET).await);
    assert!(b.is_leader());

    common::within(b.close()).await.unwrap();
    common::within(c2.close()).await.unwrap();
}

/// Verifies three participant leadership handoff on `close()` and recovery across
/// connection loss without session expiry. The leader emits `Ok(false)` on entering
/// `Connecting`, verifies its node via `exists` at the new epoch, and reevaluates
/// participant order.
#[tokio::test]
async fn re07_three_participants_close_and_reconnect() {
    const PATH: &str = "/re07/latch";
    let server = common::fake();
    let c1 = common::connect(&server.connect_string()).await;
    let c2 = common::connect(&server.connect_string()).await;
    let c3 = common::connect(&server.connect_string()).await;

    let mut l1 = start_latch(&c1, PATH, "p1").await;
    let mut l2 = start_latch(&c2, PATH, "p2").await;
    let mut l3 = start_latch(&c3, PATH, "p3").await;
    assert!(!l1.is_leader(), "no evaluation before the first call");
    assert_eq!(
        ids(&common::within(l3.participants()).await.unwrap()),
        expect(&[("p1", true), ("p2", false), ("p3", false)])
    );

    assert!(common::within(l1.changed()).await.unwrap());
    // `await_leadership` resolves immediately when already leading on a connected session.
    common::within(l1.await_leadership()).await.unwrap();
    assert!(common::still_pending(l2.changed(), QUIET).await);
    assert!(common::still_pending(l3.changed(), QUIET).await);

    // Closing the leader transfers leadership to `l2`.
    common::within(l1.close()).await.unwrap();
    assert!(common::within(l2.changed()).await.unwrap());
    assert!(common::still_pending(l3.changed(), QUIET).await);
    assert_eq!(
        ids(&common::within(l2.participants()).await.unwrap()),
        expect(&[("p2", true), ("p3", false)])
    );

    // Drop connections while preserving sessions.
    let n2 = node_of(&server, PATH, "p2");
    let n3 = node_of(&server, PATH, "p3");
    let (s2, s3) = (session_id(&c2), session_id(&c3));
    let (e2, e3) = (c2.state().epoch, c3.state().epoch);
    server.cluster().clear_transcript();
    server.drop_connections();

    // Leader revokes leadership immediately on disconnect.
    assert_eq!(common::within(l2.changed()).await, Ok(false));
    assert!(!l2.is_leader());
    // At the new epoch, `l2` verifies its node and regains leadership.
    assert!(common::within(l2.changed()).await.unwrap());
    assert!(c2.state().epoch > e2);
    assert_eq!(session_id(&c2), s2);

    // Follower `l3` also verifies its node at the new epoch and resumes watching `l2`.
    let mut w3 = c3.state_watcher();
    common::wait_state(&mut w3, |i| i.state.is_connected() && i.epoch > e3).await;
    let checked = |node: &str| {
        server
            .cluster()
            .transcript()
            .iter()
            .any(|r| r.op == Some(OP_EXISTS) && r.path.as_deref() == Some(node))
    };
    drive_until(&mut l3, || checked(&n3)).await;
    assert!(checked(&n2));
    assert!(!l3.is_leader());
    assert_eq!(session_id(&c3), s3);
    assert_eq!(latch_children(&server, PATH).len(), 2);
    assert_eq!(
        ids(&common::within(l3.participants()).await.unwrap()),
        expect(&[("p2", true), ("p3", false)])
    );

    // Closing `l2` wakes `l3`'s re-established predecessor watch.
    common::within(l2.close()).await.unwrap();
    assert!(common::within(l3.changed()).await.unwrap());
    assert_eq!(
        ids(&common::within(l3.participants()).await.unwrap()),
        expect(&[("p3", true)])
    );

    common::within(l3.close()).await.unwrap();
    assert_eq!(latch_children(&server, PATH), Vec::<String>::new());
    for client in [c1, c2, c3] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies that if a participant's node is externally deleted before reconnection,
/// the latch recreates its node with a fresh UUID at the new epoch.
#[tokio::test]
async fn re07_node_gone_after_reconnect_is_made_again() {
    const PATH: &str = "/re07gone/latch";
    let server = common::fake();
    let c1 = common::connect(&server.connect_string()).await;
    let c2 = common::connect(&server.connect_string()).await;

    let mut l1 = start_latch(&c1, PATH, "p1").await;
    let mut l2 = start_latch(&c2, PATH, "p2").await;
    assert!(common::within(l1.changed()).await.unwrap());
    assert!(common::still_pending(l2.changed(), QUIET).await);

    // Delete `l1`'s node and drop connections before `l1` processes the deletion event.
    let old = node_of(&server, PATH, "p1");
    let (old_uuid, old_seq) = layout(old.rsplit('/').next().unwrap());
    server.cluster().delete(&old).unwrap();
    server.drop_connections();

    assert_eq!(common::within(l1.changed()).await, Ok(false));
    drive_until(&mut l1, || latch_children(&server, PATH).len() == 2).await;
    assert!(common::within(l2.changed()).await.unwrap());

    // Recreated node has a new UUID and higher sequence number, placing `p1` behind `p2`.
    let new = node_of(&server, PATH, "p1");
    let (new_uuid, new_seq) = layout(new.rsplit('/').next().unwrap());
    assert_ne!(new_uuid, old_uuid);
    assert!(new_seq > old_seq);
    assert_eq!(
        server.cluster().node(&new).unwrap().stat.ephemeral_owner,
        session_id(&c1)
    );
    assert_eq!(
        latch_children(&server, PATH),
        [
            node_of(&server, PATH, "p2").rsplit('/').next().unwrap(),
            new.rsplit('/').next().unwrap()
        ]
    );
    assert_eq!(
        ids(&common::within(l1.participants()).await.unwrap()),
        expect(&[("p2", true), ("p1", false)])
    );
    assert!(common::still_pending(l1.changed(), QUIET).await);

    // Recreated node participates normally in subsequent leadership handoff.
    common::within(l2.close()).await.unwrap();
    assert!(common::within(l1.changed()).await.unwrap());
    common::within(l1.close()).await.unwrap();
    assert_eq!(latch_children(&server, PATH), Vec::<String>::new());
    for client in [c1, c2] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies Curator-compatible latch node naming (`<latch_path>/_c_<uuid>-latch-<seq>`),
/// participant ID payload, container parent creation, and sort ordering alongside
/// foreign child nodes.
#[tokio::test]
async fn re08_latch_node_layout() {
    const PATH: &str = "/re08/a/b/latch";
    let server = common::fake();
    let c1 = common::connect(&server.connect_string()).await;
    let c2 = common::connect(&server.connect_string()).await;

    let mut l1 = start_latch(&c1, PATH, "p1").await;
    let mut l2 = start_latch(&c2, PATH, "p2").await;

    // Missing ancestor levels are created as container nodes.
    for parent in ["/re08", "/re08/a", "/re08/a/b", PATH] {
        let node = server.cluster().node(parent).unwrap();
        assert_eq!(node.stat.ephemeral_owner, CONTAINER_OWNER, "{parent}");
    }

    // Participant nodes use protected `_c_<uuid>-latch-<seq>` names, store participant
    // IDs as data, and are owned by their creating sessions.
    let kids = latch_children(&server, PATH);
    assert_eq!(kids.len(), 2, "{kids:?}");
    let (u1, q1) = layout(&kids[0]);
    let (u2, q2) = layout(&kids[1]);
    assert_ne!(u1, u2);
    assert!(q1 < q2);
    for (kid, id, client) in [(&kids[0], "p1", &c1), (&kids[1], "p2", &c2)] {
        let node = server.cluster().node(&format!("{PATH}/{kid}")).unwrap();
        assert_eq!(node.data.as_deref(), Some(id.as_bytes()));
        assert_eq!(node.stat.ephemeral_owner, session_id(client));
    }

    // Foreign children participate in Curator sort order: `-1` sorts before 10-digit
    // sequence numbers, and a name lacking `latch-` sorts after them.
    server
        .cluster()
        .create(&format!("{PATH}/f-latch--1"), b"F", 0)
        .unwrap();
    server
        .cluster()
        .create(&format!("{PATH}/nomarker"), b"N", 0)
        .unwrap();
    assert_eq!(
        ids(&common::within(l1.participants()).await.unwrap()),
        expect(&[("F", true), ("p1", false), ("p2", false), ("N", false)])
    );
    assert!(common::still_pending(l1.changed(), QUIET).await);
    assert!(!l1.is_leader());

    // Deleting the foreign predecessor promotes the first latch participant.
    server
        .cluster()
        .delete(&format!("{PATH}/f-latch--1"))
        .unwrap();
    assert!(common::within(l1.changed()).await.unwrap());
    assert!(common::still_pending(l2.changed(), QUIET).await);
    assert_eq!(
        ids(&common::within(l2.participants()).await.unwrap()),
        expect(&[("p1", true), ("p2", false), ("N", false)])
    );

    // Container reaping removes the latch path once all children are deleted.
    common::within(l1.close()).await.unwrap();
    common::within(l2.close()).await.unwrap();
    server
        .cluster()
        .delete(&format!("{PATH}/nomarker"))
        .unwrap();
    assert!(server.cluster().reap().contains(&PATH.to_owned()));
    for client in [c1, c2] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies that `Latch::close` retries `delete` after connection loss (both when
/// the initial delete was applied with a dropped reply and when it was not applied),
/// completing cleanly so the next participant acquires leadership.
#[tokio::test]
async fn close_after_connection_loss_is_sent_again() {
    const OP_DELETE: i32 = 2;
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let other = common::connect(&server.connect_string()).await;
    for applied in [true, false] {
        let path = format!("/close-{applied}");
        let mut leader = start_latch(&client, &path, "a").await;
        assert!(common::within(leader.changed()).await.unwrap());
        let mut next = start_latch(&other, &path, "b").await;
        let node = node_of(&server, &path, "a");
        let rule = Rule::on_request().op(OP_DELETE).path(&node).once();
        let rule = if applied {
            rule.then(Action::DropReply).then(Action::Close)
        } else {
            rule.then(Action::Close)
        };
        server.add_rule(rule);
        server.cluster().clear_transcript();
        common::within(leader.close()).await.unwrap();
        let deletes: Vec<_> = server
            .cluster()
            .transcript()
            .into_iter()
            .filter(|r| r.op == Some(OP_DELETE))
            .collect();
        assert_eq!(deletes.len(), 2, "{applied}");
        assert!(
            deletes
                .iter()
                .all(|r| r.path.as_deref() == Some(node.as_str()))
        );
        assert_eq!(latch_children(&server, &path).len(), 1, "{applied}");
        common::within(next.await_leadership()).await.unwrap();
        common::within(next.close()).await.unwrap();
        server.clear_rules();
    }
    common::within(other.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that dropping `LeaderLatch::start` while its `create` request is still
/// in flight hands the late create reply to the background guaranteed-delete task,
/// removing the orphan node so subsequent participants can lead.
#[tokio::test]
async fn dropped_start_with_create_in_flight_is_deleted_in_the_background() {
    const PATH: &str = "/orphan/latch";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let other = common::connect(&server.connect_string()).await;
    server
        .cluster()
        .create("/orphan", b"", zk_fakeserver::create_mode::PERSISTENT)
        .unwrap();
    server
        .cluster()
        .create(PATH, b"", zk_fakeserver::create_mode::PERSISTENT)
        .unwrap();
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Delay(Duration::from_millis(500))),
    );
    let latch = LeaderLatch::new(&client, PATH, "a").unwrap();
    {
        let mut start = std::pin::pin!(latch.start());
        assert!(poll_once(start.as_mut()).is_pending());
    }
    common::wait_fake(&server, |c| c.children(PATH).is_some_and(|k| k.len() == 1));
    let orphan = latch_children(&server, PATH).remove(0);
    common::wait_fake(&server, |c| c.children(PATH).is_some_and(|k| k.is_empty()));
    assert_eq!(deletes_of(&server, &format!("{PATH}/{orphan}")), 1);

    let mut started = start_latch(&other, PATH, "b").await;
    assert!(common::within(started.changed()).await.unwrap());
    common::within(started.close()).await.unwrap();
    common::within(other.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that if `Latch::close` receives a non-connection-loss error (such as
/// `ThrottledOp`) on `delete`, it returns that error to the caller while handing
/// the node to the background guaranteed-delete task for cleanup.
#[tokio::test]
async fn close_with_a_failed_delete_hands_the_node_over() {
    const PATH: &str = "/close-throttled/latch";
    const ERR_THROTTLED: i32 = -127;
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let other = common::connect(&server.connect_string()).await;
    let mut leader = start_latch(&client, PATH, "a").await;
    assert!(common::within(leader.changed()).await.unwrap());
    let mut next = start_latch(&other, PATH, "b").await;
    let node = node_of(&server, PATH, "a");
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&node)
            .once()
            .then(Action::Reply { err: ERR_THROTTLED }),
    );
    server.cluster().clear_transcript();
    assert_eq!(
        common::within(leader.close()).await,
        Err(Error::ThrottledOp)
    );
    common::within(next.await_leadership()).await.unwrap();
    assert_eq!(deletes_of(&server, &node), 2);
    common::within(next.close()).await.unwrap();
    common::within(other.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that a latch never claims leadership on a `ConnectedReadOnly` session
/// (which may be isolated in a minority partition): it revokes leadership on
/// disconnect, stays non-leading while connected read-only, and regains leadership
/// only after reconnecting in read-write `Connected` state.
#[tokio::test]
async fn read_only_connection_does_not_lead() {
    const PATH: &str = "/ro/latch";
    let server = common::fake();
    let client = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    let mut state = client.state_watcher();
    let mut latch = start_latch(&client, PATH, "a").await;
    assert!(common::within(latch.changed()).await.unwrap());

    server.set_read_only(true);
    server.drop_connections();
    assert!(!common::within(latch.changed()).await.unwrap());
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    let quiet = tokio::time::timeout(QUIET * 2, latch.changed()).await;
    assert!(
        quiet.is_err(),
        "the latch led on a read-only server: {quiet:?}"
    );
    assert!(!latch.is_leader());
    assert!(
        tokio::time::timeout(QUIET, latch.await_leadership())
            .await
            .is_err()
    );

    server.set_read_only(false);
    server.drop_connections();
    assert!(common::within(latch.changed()).await.unwrap());
    assert_eq!(client.state().state, SessionState::Connected);
    common::within(latch.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that if a `getChildren` reply placing the latch first arrives in the
/// same poll as a connection loss, leadership is suppressed until the session
/// reconnects in `Connected` state.
#[tokio::test]
async fn leadership_found_with_a_lost_connection_is_not_reported() {
    const PATH: &str = "/race/latch";
    let server = common::fake();
    let a_client = common::connect(&server.connect_string()).await;
    let b_client = common::connect(&server.connect_string()).await;
    let mut a = start_latch(&a_client, PATH, "a").await;
    assert!(common::within(a.changed()).await.unwrap());
    let mut b = start_latch(&b_client, PATH, "b").await;
    drive_until(&mut b, || server.cluster().watch_count() == 1).await;

    // Deliver the `getChildren` reply to `b` after `a` closes, then immediately
    // close the connection.
    server.add_rule(
        Rule::on_request()
            .op(OP_GET_CHILDREN)
            .path(PATH)
            .once()
            .then(Action::Model)
            .then(Action::Close),
    );
    let mut state = b_client.state_watcher();
    let epoch = state.current().epoch;
    common::within(a.close()).await.unwrap();
    {
        let mut changed = std::pin::pin!(b.changed());
        // Poll with a no-op waker until the `getChildren` request fires the rule.
        common::within(async {
            while server.script().rules()[0].fired() == 0 {
                assert!(poll_once(changed.as_mut()).is_pending());
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await;
        common::wait_state(&mut state, |i| {
            i.state != SessionState::Connected || i.epoch > epoch
        })
        .await;
        let r = poll_once(changed.as_mut());
        assert!(r.is_pending(), "{r:?}");
    }
    assert!(!b.is_leader());
    assert!(common::within(b.changed()).await.unwrap());
    let info = b_client.state();
    assert_eq!(info.state, SessionState::Connected);
    assert!(info.epoch > epoch);
    common::within(b.close()).await.unwrap();
    common::within(a_client.close()).await.unwrap();
    common::within(b_client.close()).await.unwrap();
}

/// Verifies that when a dropped `Latch`'s `delete` request is lost due to connection
/// closure, the background guaranteed-delete task re-sends the delete upon reconnection
/// so a waiting participant on another client acquires leadership.
#[tokio::test]
async fn latch_drop_with_lost_delete_is_sent_again_after_the_reconnect() {
    const PATH: &str = "/ghost/latch";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let other = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let leader_latch = LeaderLatch::new(&client, PATH, "a").unwrap();
    let mut latch = common::within(leader_latch.start()).await.unwrap();
    assert!(common::within(latch.changed()).await.unwrap());
    let mut waiter = start_latch(&other, PATH, "b").await;
    let old = format!("{PATH}/{}", latch_children(&server, PATH)[0]);
    let epoch = state.current().epoch;
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&old)
            .once()
            .then(Action::Close),
    );
    server.cluster().clear_transcript();
    drop(latch);
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;

    assert!(common::within(waiter.changed()).await.unwrap());
    assert_eq!(latch_children(&server, PATH).len(), 1);
    assert_eq!(deletes_of(&server, &old), 2);
    common::within(waiter.close()).await.unwrap();
    common::within(other.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that when two `Latch` instances share a `LeaderLatch` (`a` ahead of `s`
/// behind an external leader) and `a` is dropped with a lost `delete`, the background
/// guaranteed-delete removes `a`'s ghost node on reconnect so `s` acquires leadership
/// once the external leader closes.
#[tokio::test]
async fn waiting_latch_passes_a_sibling_lost_delete() {
    const PATH: &str = "/ghost-sibling/latch";
    let server = common::fake();
    let h_client = common::connect(&server.connect_string()).await;
    let b_client = common::connect(&server.connect_string()).await;
    let mut state = b_client.state_watcher();
    let mut leader = start_latch(&h_client, PATH, "h").await;
    assert!(common::within(leader.changed()).await.unwrap());

    let leader_latch = LeaderLatch::new(&b_client, PATH, "b").unwrap();
    let mut a = common::within(leader_latch.start()).await.unwrap();
    let mut s = common::within(leader_latch.start()).await.unwrap();
    drive_until(&mut a, || server.cluster().watch_count() == 1).await;
    drive_until(&mut s, || server.cluster().watch_count() == 2).await;
    let kids = latch_children(&server, PATH);
    assert_eq!(kids.len(), 3);
    let a_node = format!("{PATH}/{}", kids[1]);
    let epoch = state.current().epoch;
    server.add_rule(
        Rule::on_request()
            .op(OP_DELETE)
            .path(&a_node)
            .once()
            .then(Action::Close),
    );
    server.cluster().clear_transcript();
    drop(a);
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    common::within(leader.close()).await.unwrap();

    assert!(common::within(s.changed()).await.unwrap());
    assert_eq!(latch_children(&server, PATH), vec![kids[2].clone()]);
    let resent = server
        .cluster()
        .transcript()
        .into_iter()
        .filter(|r| r.op == Some(OP_DELETE) && r.path.as_deref() == Some(a_node.as_str()))
        .count();
    assert_eq!(resent, 2);
    common::within(s.close()).await.unwrap();
    common::within(h_client.close()).await.unwrap();
    common::within(b_client.close()).await.unwrap();
}

/// Verifies that background guaranteed-delete removes only the specific node abandoned
/// by a cancelled `start()` future, leaving active sibling `Latch` nodes on the same
/// `LeaderLatch` intact across reconnection.
#[tokio::test]
async fn guaranteed_delete_keeps_the_own_nodes() {
    const PATH: &str = "/ghost-own/latch";
    let server = common::fake();
    let h_client = common::connect(&server.connect_string()).await;
    let b_client = common::connect(&server.connect_string()).await;
    let mut state = b_client.state_watcher();
    let mut leader = start_latch(&h_client, PATH, "h").await;
    assert!(common::within(leader.changed()).await.unwrap());

    let leader_latch = LeaderLatch::new(&b_client, PATH, "b").unwrap();
    let mut first = common::within(leader_latch.start()).await.unwrap();
    let mut second = common::within(leader_latch.start()).await.unwrap();
    drive_until(&mut first, || server.cluster().watch_count() == 1).await;
    drive_until(&mut second, || server.cluster().watch_count() == 2).await;
    let waiters = latch_children(&server, PATH)[1..].to_vec();
    assert_eq!(waiters.len(), 2);
    server.add_rule(
        Rule::on_request()
            .op(OP_CREATE2)
            .path_prefix(&format!("{PATH}/_c_"))
            .once()
            .then(Action::Delay(Duration::from_millis(300))),
    );
    server.cluster().clear_transcript();
    {
        let mut start = std::pin::pin!(leader_latch.start());
        assert!(poll_once(start.as_mut()).is_pending());
    }
    let deletes = || {
        server
            .cluster()
            .transcript()
            .into_iter()
            .filter(|r| r.op == Some(OP_DELETE))
            .collect::<Vec<_>>()
    };
    common::wait_fake(&server, |c| {
        c.children(PATH).is_some_and(|k| k.len() == 3)
            && c.transcript().iter().any(|r| r.op == Some(OP_DELETE))
    });
    server.clear_rules();
    let create = server
        .cluster()
        .transcript()
        .into_iter()
        .find(|r| r.op == Some(OP_CREATE2))
        .and_then(|r| r.path)
        .unwrap();
    let deleted = deletes();
    assert_eq!(deleted.len(), 1, "{deleted:?}");
    assert!(deleted[0].path.as_deref().unwrap().starts_with(&create));
    assert_eq!(latch_children(&server, PATH)[1..], waiters[..]);

    let epoch = state.current().epoch;
    server.drop_connections();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    // Drive both active latches until each re-establishes its predecessor watch.
    let read = |node: &str| {
        server
            .cluster()
            .transcript()
            .iter()
            .any(|r| r.op == Some(OP_GET_DATA) && r.path.as_deref() == Some(node))
    };
    let leader_node = format!("{PATH}/{}", latch_children(&server, PATH)[0]);
    let first_node = format!("{PATH}/{}", waiters[0]);
    drive_until(&mut first, || read(&leader_node)).await;
    drive_until(&mut second, || read(&first_node)).await;
    assert_eq!(deletes().len(), 1);
    assert_eq!(latch_children(&server, PATH)[1..], waiters[..]);
    assert!(!first.is_leader() && !second.is_leader());

    common::within(leader.close()).await.unwrap();
    assert!(common::within(first.changed()).await.unwrap());
    common::within(first.close()).await.unwrap();
    assert!(common::within(second.changed()).await.unwrap());
    assert_eq!(latch_children(&server, PATH), vec![waiters[1].clone()]);
    common::within(second.close()).await.unwrap();
    common::within(h_client.close()).await.unwrap();
    common::within(b_client.close()).await.unwrap();
}

/// Verifies that when a latch is dropped on a read-only connection (receiving
/// `NotReadOnly`), the background guaranteed-delete task refrains from retrying
/// on subsequent `ConnectedReadOnly` epochs and deletes the node once the session
/// reconnects in read-write `Connected` state.
#[tokio::test]
async fn read_only_connection_does_not_delete() {
    const PATH: &str = "/ro-ghost/latch";
    let server = common::fake();
    let client = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    let other = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let mut latch = start_latch(&client, PATH, "a").await;
    assert!(common::within(latch.changed()).await.unwrap());
    let node = node_of(&server, PATH, "a");

    server.set_read_only(true);
    server.drop_connections();
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    server.cluster().clear_transcript();
    drop(latch);
    common::wait_fake(&server, |c| {
        c.transcript().iter().any(|r| r.op == Some(OP_DELETE))
    });
    // Subsequent read-only epochs must not trigger a retry.
    let epoch = state.current().epoch;
    server.drop_connections();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::ConnectedReadOnly && i.epoch > epoch
    })
    .await;
    tokio::time::sleep(QUIET).await;
    assert_eq!(deletes_of(&server, &node), 1);
    assert!(server.cluster().node(&node).is_some());

    server.set_read_only(false);
    server.drop_connections();
    common::wait_fake(&server, |c| c.node(&node).is_none());
    assert_eq!(deletes_of(&server, &node), 2);
    let mut next = start_latch(&other, PATH, "b").await;
    assert!(common::within(next.changed()).await.unwrap());
    common::within(next.close()).await.unwrap();
    common::within(other.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that `is_leader` returns `false` immediately upon connection loss prior to polling
/// `changed()`, remains `false` across reconnection until the participant node is verified, and
/// still delivers the revocation event through `changed()`.
#[tokio::test]
async fn is_leader_drops_on_disconnect_without_a_poll() {
    const PATH: &str = "/live-loss/latch";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let mut latch = start_latch(&client, PATH, "a").await;
    assert!(common::within(latch.changed()).await.unwrap());
    assert!(latch.is_leader());

    let epoch = client.state().epoch;
    server.drop_connections();
    common::wait_state(&mut state, |i| {
        i.state != SessionState::Connected || i.epoch > epoch
    })
    .await;
    assert!(!latch.is_leader());
    // Reconnecting in a new epoch does not reassert leadership until the state machine completes
    // a fresh evaluation pass.
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    assert!(!latch.is_leader());

    assert_eq!(common::within(latch.changed()).await, Ok(false));
    assert!(common::within(latch.changed()).await.unwrap());
    assert!(latch.is_leader());
    common::within(latch.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that `is_leader` returns `false` immediately when the session transitions to
/// `ConnectedReadOnly`, prior to polling `changed()`.
#[tokio::test]
async fn is_leader_drops_on_read_only_without_a_poll() {
    const PATH: &str = "/live-ro/latch";
    let server = common::fake();
    let client = common::within(
        common::builder(&server.connect_string())
            .allow_read_only(true)
            .connect(),
    )
    .await
    .unwrap();
    let mut state = client.state_watcher();
    let mut latch = start_latch(&client, PATH, "a").await;
    assert!(common::within(latch.changed()).await.unwrap());
    assert!(latch.is_leader());

    server.set_read_only(true);
    server.drop_connections();
    common::wait_state(&mut state, |i| i.state == SessionState::ConnectedReadOnly).await;
    assert!(!latch.is_leader());
    assert_eq!(common::within(latch.changed()).await, Ok(false));
    assert!(!latch.is_leader());

    server.set_read_only(false);
    server.drop_connections();
    assert!(common::within(latch.changed()).await.unwrap());
    assert!(latch.is_leader());
    common::within(latch.close()).await.unwrap();
    common::within(client.close()).await.unwrap();
}

/// Verifies that `is_leader` returns `false` immediately upon session expiration prior to polling
/// `changed()`, while subsequent `changed()` calls yield `Ok(false)` followed by the terminal
/// `SessionExpired` error.
#[tokio::test]
async fn is_leader_drops_on_expiry_without_a_poll() {
    const PATH: &str = "/live-expiry/latch";
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;
    let mut state = client.state_watcher();
    let mut latch = start_latch(&client, PATH, "a").await;
    assert!(common::within(latch.changed()).await.unwrap());
    assert!(latch.is_leader());

    assert!(server.cluster().expire_session(session_id(&client)));
    common::wait_state(&mut state, |i| i.state == SessionState::Expired).await;
    assert!(!latch.is_leader());
    assert_eq!(common::within(latch.changed()).await, Ok(false));
    assert_eq!(
        common::within(latch.changed()).await,
        Err(Error::SessionExpired)
    );
    assert!(!latch.is_leader());
}
