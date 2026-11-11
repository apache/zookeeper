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

//! Integration tests for `LeaderLatch` against standalone and three-node ensemble
//! ZooKeeper servers.
//!
//! Covers sequential leadership handoff across three participants, proxy induced
//! connection loss and same session reconnection, follower and leader server stop
//! failovers on an ensemble, and background guaranteed delete recovery when a
//! latch drop's `delete` request is lost across a connection cut.
//!
//! Requires `ZK_TEST_PORT_BASE` and a runnable server (optionally selected via
//! `ZK_TEST_SERVER_VERSION`); skips otherwise. Run with `--ignored`.

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

use std::time::Duration;

use apache_zookeeper::{ServerOrder, SessionState};
use common::{ProxyGroup, Servers, Topology, expect, ids, start_latch};

/// Timeout window used to assert that a waiting future remains pending.
const QUIET: Duration = Duration::from_millis(300);

/// Verifies leadership transitions and proxy induced reconnection on a standalone server.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_latch_three_participants() {
    three_participants(Topology::Standalone).await;
}

/// Verifies leadership transitions and proxy-induced reconnection on a three node ensemble.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_latch_three_participants_ensemble() {
    three_participants(Topology::Ensemble).await;
}

/// Exercises three latch participants across leader close, connection loss, and
/// same session reconnection.
async fn three_participants(topology: Topology) {
    const PATH: &str = "/re12/latch/three";
    let Some(servers) = Servers::start_or_skip(topology) else {
        return;
    };
    let proxy = ProxyGroup::start(&servers.client_ports());
    let c1 = common::connect(&servers.connect_string()).await;
    let c2 = common::connect(&proxy.connect_string()).await;
    let c3 = common::connect(&proxy.connect_string()).await;

    let mut l1 = start_latch(&c1, PATH, "p1").await;
    let mut l2 = start_latch(&c2, PATH, "p2").await;
    let mut l3 = start_latch(&c3, PATH, "p3").await;
    // Sync with the quorum leader so follower reads reflect all participant creations.
    common::within(c1.sync("/")).await.unwrap();
    assert_eq!(
        ids(&common::within(l1.participants()).await.unwrap()),
        expect(&[("p1", true), ("p2", false), ("p3", false)])
    );

    // Closing the leader transfers leadership in sequential node order.
    assert!(common::within(l1.changed()).await.unwrap());
    assert!(common::still_pending(l2.changed(), QUIET).await);
    assert!(common::still_pending(l3.changed(), QUIET).await);
    common::within(l1.close()).await.unwrap();
    assert!(common::within(l2.changed()).await.unwrap());
    assert!(common::still_pending(l3.changed(), QUIET).await);

    // Severing the proxy drops both client connections without expiring their sessions.
    let (s2, s3) = (c2.session().0, c3.session().0);
    let (e2, e3) = (c2.state().epoch, c3.state().epoch);
    proxy.cut();
    assert_eq!(common::within(l2.changed()).await, Ok(false));
    assert!(!l2.is_leader());
    assert!(common::still_pending(l3.changed(), QUIET).await);
    assert!(!l3.is_leader());
    proxy.restore();

    // Upon reconnecting at the new epoch, each latch verifies its node and re-evaluates order.
    assert!(common::within(l2.changed()).await.unwrap());
    let mut w3 = c3.state_watcher();
    common::wait_state(&mut w3, |i| i.state.is_connected() && i.epoch > e3).await;
    assert!(common::still_pending(l3.changed(), QUIET).await);
    assert!(!l3.is_leader());
    assert!(c2.state().epoch > e2);
    assert_eq!(c2.session().0, s2);
    assert_eq!(c3.session().0, s3);
    common::within(c3.sync("/")).await.unwrap();
    assert_eq!(
        ids(&common::within(l3.participants()).await.unwrap()),
        expect(&[("p2", true), ("p3", false)])
    );

    // Re-established predecessor watch wakes `l3` when `l2` closes.
    common::within(l2.close()).await.unwrap();
    assert!(common::within(l3.changed()).await.unwrap());
    common::within(l3.close()).await.unwrap();
    assert_eq!(
        common::synced_children(&c1, PATH).await,
        Vec::<String>::new()
    );
    for client in [c1, c2, c3] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies latch recovery when the connected follower node is stopped (quorum
/// remains intact without a leader election).
#[tokio::test]
#[ignore = "real-server"]
async fn re12_latch_survives_server_failover() {
    failover(false).await;
}

/// Verifies latch recovery when the ensemble leader is stopped (all clients
/// disconnect during quorum reelection and reconnect within their session timeout).
#[tokio::test]
#[ignore = "real-server"]
async fn re12_latch_survives_leader_change() {
    failover(true).await;
}

/// Stops the ensemble member serving all three clients (either the quorum `leader`
/// or a follower) and verifies session migration and leadership recovery.
async fn failover(leader: bool) {
    const PATH: &str = "/re12/latch/failover";
    let Some(ensemble) = common::Ensemble::start_or_skip() else {
        return;
    };
    let current = ensemble.leader().expect("the ensemble has a leader");
    let stopped = if leader {
        current
    } else {
        (1..=3).find(|&n| n != current).unwrap()
    };
    // Place the target server first in the host list so all clients connect to it initially.
    let mut hosts = vec![format!("127.0.0.1:{}", ensemble.client_port(stopped))];
    hosts.extend(
        (1..=3)
            .filter(|&n| n != stopped)
            .map(|n| format!("127.0.0.1:{}", ensemble.client_port(n))),
    );
    let connect_string = hosts.join(",");
    // Use a 10s session timeout to accommodate ensemble leader election latency.
    let connect = || async {
        common::within(
            common::builder(&connect_string)
                .server_order(ServerOrder::AsGiven)
                .session_timeout(Duration::from_secs(10))
                .connect(),
        )
        .await
        .unwrap()
    };
    let c1 = connect().await;
    let c2 = connect().await;
    let c3 = connect().await;

    let mut l1 = start_latch(&c1, PATH, "p1").await;
    let mut l2 = start_latch(&c2, PATH, "p2").await;
    let mut l3 = start_latch(&c3, PATH, "p3").await;
    assert!(common::within(l1.changed()).await.unwrap());
    assert!(common::still_pending(l2.changed(), QUIET).await);

    let sessions: Vec<_> = [&c1, &c2, &c3].iter().map(|c| c.session().0).collect();
    ensemble.stop_node(stopped).unwrap();
    if leader {
        assert!(
            ensemble.wait_leader(Some(stopped)).is_some(),
            "a new leader"
        );
    }

    // Leader revokes leadership on disconnect, reconnects to a surviving peer,
    // verifies its node, and regains leadership.
    assert_eq!(common::within(l1.changed()).await, Ok(false));
    assert!(common::within(l1.changed()).await.unwrap());
    assert!(common::still_pending(l2.changed(), QUIET).await);
    assert!(common::still_pending(l3.changed(), QUIET).await);
    let after: Vec<_> = [&c1, &c2, &c3].iter().map(|c| c.session().0).collect();
    assert_eq!(after, sessions);
    common::within(c2.sync("/")).await.unwrap();
    assert_eq!(
        ids(&common::within(l2.participants()).await.unwrap()),
        expect(&[("p1", true), ("p2", false), ("p3", false)])
    );

    // Sequential leadership handoff proceeds normally after failover.
    common::within(l1.close()).await.unwrap();
    assert!(common::within(l2.changed()).await.unwrap());
    assert!(common::still_pending(l3.changed(), QUIET).await);
    common::within(l2.close()).await.unwrap();
    assert!(common::within(l3.changed()).await.unwrap());
    common::within(l3.close()).await.unwrap();
    for client in [c1, c2, c3] {
        common::within(client.close()).await.unwrap();
    }
}

/// Verifies background guaranteed delete recovery after a dropped latch's `delete`
/// is lost across a proxy cut on a standalone server.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_latch_lost_delete_through_proxy() {
    lost_delete(Topology::Standalone).await;
}

/// Verifies background guaranteed delete recovery after a dropped latch's `delete`
/// is lost across a proxy cut in front of an ensemble follower.
#[tokio::test]
#[ignore = "real-server"]
async fn re12_latch_lost_delete_through_proxy_ensemble() {
    lost_delete(Topology::Ensemble).await;
}

/// Holds and severs the proxy connection when `l1` is dropped so its `delete`
/// never reaches the server, then verifies that background guaranteed delete
/// removes the ghost node upon reconnection and unblocks `l2`.
async fn lost_delete(topology: Topology) {
    const PATH: &str = "/re12/latch/ghost";
    let Some(servers) = Servers::start_or_skip(topology) else {
        return;
    };
    let proxy = common::Proxy::start(servers.relay_port());
    let c1 = common::connect(&proxy.connect_string()).await;
    let c2 = common::connect(&servers.connect_string()).await;
    let mut state = c1.state_watcher();
    let session = c1.session().0;

    let mut l1 = start_latch(&c1, PATH, "p1").await;
    assert!(common::within(l1.changed()).await.unwrap());
    let mut l2 = start_latch(&c2, PATH, "p2").await;
    assert!(common::still_pending(l2.changed(), QUIET).await);

    // Holding the proxy prevents the delete from reaching the server, leaving `p1` as a ghost.
    let epoch = state.current().epoch;
    proxy.hold();
    drop(l1);
    assert!(common::still_pending(l2.changed(), QUIET).await, "ghost");
    assert_eq!(common::synced_children(&c2, PATH).await.len(), 2);
    proxy.cut();
    common::wait_state(&mut state, |i| !i.state.is_connected()).await;
    proxy.restore();
    common::wait_state(&mut state, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    assert_eq!(c1.session().0, session);

    assert!(common::within(l2.changed()).await.unwrap());
    common::within(c2.sync("/")).await.unwrap();
    assert_eq!(
        ids(&common::within(l2.participants()).await.unwrap()),
        expect(&[("p2", true)])
    );
    common::within(l2.close()).await.unwrap();
    assert!(common::synced_children(&c2, PATH).await.is_empty());
    common::within(c1.close()).await.unwrap();
    common::within(c2.close()).await.unwrap();
}
