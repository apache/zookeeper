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

//! Tests the ensemble configuration follower (`update_servers_from_config`) against
//! `zk_fakeserver` when a read cannot go to the server for a transient reason: a full request
//! queue (`max_pending_requests`) and a server `ThrottledOp` reply.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::Duration;

use apache_zookeeper::{Client, EventKind};
use common::{WAIT, builder, within};
use zk_fakeserver::{Action, FakeServer, FrameKind, Rule, ServerOptions};

/// Opcode of `getData`, which also carries `getConfig`.
const OP_GET_DATA: i32 = 4;
/// Server error code of `ThrottledOp`.
const ERR_THROTTLED: i32 = -127;
/// Path of the ensemble configuration znode.
const CONFIG: &str = "/zookeeper/config";

/// Counts the configuration reads that `fs` received.
fn config_reads(fs: &FakeServer) -> usize {
    fs.transcript()
        .iter()
        .filter(|r| {
            r.kind == FrameKind::Request
                && r.op == Some(OP_GET_DATA)
                && r.path.as_deref() == Some(CONFIG)
        })
        .count()
}

/// Writes a one-member ensemble configuration naming `to`, and waits until `c` connects to it.
async fn reconfigure(fs: &FakeServer, c: &Client, n: u32, to: &FakeServer) {
    let text = format!(
        "server.{n}={0}:2888:3888:participant;{0}:{1}\nversion=10000000{n}",
        to.addr().ip(),
        to.addr().port()
    );
    fs.cluster().set_data(CONFIG, text.as_bytes()).unwrap();
    let mut w = c.state_watcher();
    within(async {
        while c.connected_server() != Some(to.addr()) {
            w.changed().await;
        }
    })
    .await;
}

/// Verifies that a configuration read refused by a full request queue is deferred, not
/// abandoned: the follower submits it when a request completes, applies the new server list,
/// and keeps following later changes.
// The fake server waits block, so this test needs a runtime with more than one thread.
#[tokio::test(flavor = "multi_thread")]
async fn a_full_queue_defers_the_config_read() {
    const LIMIT: usize = 2;
    let a = common::fake();
    let b = a
        .cluster()
        .start_server(ServerOptions::default())
        .expect("second listener");
    let c = within(
        builder(&a.connect_string())
            .update_servers_from_config(true)
            .max_pending_requests(LIMIT)
            .connect(),
    )
    .await
    .expect("connect");
    assert!(
        a.wait_for(WAIT, |r| r.op == Some(OP_GET_DATA)
            && r.path.as_deref() == Some(CONFIG))
            .is_some()
    );
    // Replies arrive in request order, so the follower read has completed and armed its watch
    // when this read completes.
    let (_, _, seen) = within(c.get_data_watched(CONFIG)).await.unwrap();
    assert_eq!(c.stats().pending, 0);

    a.hold();
    let held: Vec<_> = (0..LIMIT).map(|_| c.get_data("/")).collect();
    assert_eq!(c.stats().pending, LIMIT);
    let text = format!(
        "server.2={0}:2888:3888:participant;{0}:{1}\nversion=100000002",
        b.addr().ip(),
        b.addr().port()
    );
    a.cluster().set_data(CONFIG, text.as_bytes()).unwrap();
    // The router runs the follower before it delivers the event to this watcher, so the
    // follower has met the full queue when the event arrives.
    let ev = within(seen).await.unwrap();
    assert_eq!(ev.kind, EventKind::NodeDataChanged);
    assert_eq!(c.connected_server(), Some(a.addr()));
    assert_eq!(config_reads(&a), 2);

    a.release();
    for f in held {
        within(f).await.unwrap();
    }
    let mut w = c.state_watcher();
    within(async {
        while c.connected_server() != Some(b.addr()) {
            w.changed().await;
        }
    })
    .await;
    reconfigure(&a, &c, 3, &a).await;
    within(c.close()).await.unwrap();
}

/// Verifies that a server `ThrottledOp` reply to a configuration read makes the follower read
/// again, so that later configuration changes still update the server list.
#[tokio::test(flavor = "multi_thread")]
async fn a_throttled_config_read_is_retried() {
    let a = common::fake();
    let b = a
        .cluster()
        .start_server(ServerOptions::default())
        .expect("second listener");
    a.add_rule(
        Rule::on_request()
            .op(OP_GET_DATA)
            .path(CONFIG)
            .once()
            .then(Action::Reply { err: ERR_THROTTLED }),
    );
    let c = within(
        builder(&a.connect_string())
            .update_servers_from_config(true)
            .connect(),
    )
    .await
    .expect("connect");
    // The second read either arms the watch before the change below or reads the changed
    // configuration itself. Either way the follower applies the change.
    within(async {
        while config_reads(&a) < 2 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
    reconfigure(&a, &c, 2, &b).await;
    within(c.close()).await.unwrap();
}
