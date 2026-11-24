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

//! Smoke test suite validating end-to-end client functionality against a live ZooKeeper server.
//!
//! Verifies baseline operations: znode CRUD lifecycle, one-shot watched data reads,
//! persistent recursive watch event delivery, atomic multi-op transactions, and clean session
//! shutdown. Asynchronous tests execute across both OS thread and Tokio runtimes, alongside the
//! synchronous `blocking::Client` facade.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    AddWatchMode, Client, CreateMode, EventKind, MultiWriter, Runtime, SessionState, ThreadOptions,
    Version,
};
use common::{builder, fresh_server, within};

/// Designated port offsets within the base test allocation.
const THREAD_PORT: u16 = 7;
#[cfg(all(unix, feature = "tokio"))]
const TOKIO_PORT: u16 = 8;
#[cfg(feature = "blocking")]
const BLOCKING_PORT: u16 = 9;

/// Executes end-to-end smoke verification sequence against a connected asynchronous client,
/// concluding with graceful closure.
async fn smoke(c: Client) {
    assert_eq!(c.state().state, SessionState::Connected);
    create_get_set(&c).await;
    oneshot_watch(&c).await;
    persistent_watch(&c).await;
    multi_and_delete(&c).await;
    assert_eq!(within(c.close()).await, Ok(()));
    assert_eq!(c.state().state, SessionState::Closed);
}

/// Exercises znode creation, data retrieval, and version-checked update on `/smoke`.
async fn create_get_set(c: &Client) {
    let (p, _) = within(c.create("/smoke", b"a", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(p.as_str(), "/smoke");
    let (data, stat) = within(c.get_data("/smoke")).await.unwrap();
    assert_eq!(data, b"a");
    let stat = within(c.set_data("/smoke", b"b", stat.version))
        .await
        .unwrap();
    assert_eq!(stat.version, Version::new(1));
}

/// Validates that a one-shot data watch fires upon subsequent data mutation.
async fn oneshot_watch(c: &Client) {
    let (data, _, w) = within(c.get_data_watched("/smoke")).await.unwrap();
    assert_eq!(data, b"b");
    within(c.set_data("/smoke", b"c", None)).await.unwrap();
    let ev = within(w).await.unwrap();
    assert_eq!(
        (ev.kind, ev.path.as_str()),
        (EventKind::NodeDataChanged, "/smoke")
    );
}

/// Validates that a persistent recursive watch stream receives events within the monitored
/// subtree.
async fn persistent_watch(c: &Client) {
    let mut pw = within(c.add_watch("/smoke", AddWatchMode::PersistentRecursive))
        .await
        .unwrap();
    within(c.create("/smoke/c", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    within(c.set_data("/smoke/c", b"1", None)).await.unwrap();
    let a = within(pw.next()).await.unwrap().unwrap();
    let b = within(pw.next()).await.unwrap().unwrap();
    assert_eq!(
        (a.kind, a.path.as_str()),
        (EventKind::NodeCreated, "/smoke/c")
    );
    assert_eq!(
        (b.kind, b.path.as_str()),
        (EventKind::NodeDataChanged, "/smoke/c")
    );
    within(pw.remove()).await.unwrap();
}

/// Validates atomic multi-operation transaction commit and cleans up test znodes.
async fn multi_and_delete(c: &Client) {
    let mut txn = MultiWriter::new();
    let made = txn
        .create("/smoke/m", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let set = txn.set_data("/smoke/m", b"x", Version::new(0)).unwrap();
    txn.delete("/smoke/c", Version::ANY).unwrap();
    let results = within(c.multi(&txn)).await.unwrap();
    assert_eq!(results.get(made).unwrap().path.as_str(), "/smoke/m");
    assert_eq!(results.get(set).unwrap().version, Version::new(1));
    assert_eq!(within(c.exists("/smoke/c")).await, Ok(None));
    within(c.delete("/smoke/m", None)).await.unwrap();
    within(c.delete("/smoke", Version::new(2))).await.unwrap();
    assert_eq!(within(c.exists("/smoke")).await, Ok(None));
}

#[tokio::test]
#[ignore = "real-server"]
async fn smoke_with_the_io_thread() {
    let Some(srv) = fresh_server(THREAD_PORT) else {
        return;
    };
    let b = builder(&srv.connect_string()).runtime(Runtime::Thread(ThreadOptions::default()));
    smoke(within(b.connect()).await.expect("connect")).await;
}

#[cfg(all(unix, feature = "tokio"))]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn smoke_with_a_tokio_task() {
    let Some(srv) = fresh_server(TOKIO_PORT) else {
        return;
    };
    let rt = Runtime::Tokio(tokio::runtime::Handle::current());
    let b = builder(&srv.connect_string()).runtime(rt);
    smoke(within(b.connect()).await.expect("connect")).await;
}

#[cfg(feature = "blocking")]
#[test]
#[ignore = "real-server"]
fn smoke_with_the_blocking_client() {
    use apache_zookeeper::blocking;

    let Some(srv) = fresh_server(BLOCKING_PORT) else {
        return;
    };
    let b = builder(&srv.connect_string()).runtime(Runtime::Thread(ThreadOptions::default()));
    let c = blocking::Client::connect(b).expect("connect");
    assert_eq!(c.state().state, SessionState::Connected);

    c.create("/smoke", b"a", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let (data, stat) = c.get_data("/smoke").unwrap();
    assert_eq!(data, b"a");
    let stat = c.set_data("/smoke", b"b", stat.version).unwrap();
    assert_eq!(stat.version, Version::new(1));

    let (_, _, w) = c.get_data_watched("/smoke").unwrap();
    c.set_data("/smoke", b"c", None).unwrap();
    assert_eq!(w.wait().unwrap().kind, EventKind::NodeDataChanged);

    let mut pw = c
        .add_watch("/smoke", AddWatchMode::PersistentRecursive)
        .unwrap();
    c.create("/smoke/c", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    c.delete("/smoke/c", None).unwrap();
    let a = pw.recv().unwrap().unwrap();
    let b = pw.recv().unwrap().unwrap();
    assert_eq!(
        (a.kind, b.kind),
        (EventKind::NodeCreated, EventKind::NodeDeleted)
    );
    drop(pw);

    let mut txn = MultiWriter::new();
    txn.create("/smoke/m", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.delete("/smoke/m", Version::ANY).unwrap();
    txn.delete("/smoke", Version::new(2)).unwrap();
    assert_eq!(c.multi(&txn).unwrap().iter().count(), 3);
    assert_eq!(c.exists("/smoke"), Ok(None));
    assert_eq!(c.close(), Ok(()));
    assert_eq!(c.state().state, SessionState::Closed);
}
