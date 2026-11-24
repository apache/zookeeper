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

//! Evaluates core `Client` API invariants and state machine transitions against `zk_fakeserver`:
//! CRUD operations, ACL modifications, ephemeral node tracking, transparent reconnection,
//! authentication, error handling, weak reference session semantics, and synchronous blocking
//! facade contracts.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::Duration;

use apache_zookeeper::acl::{OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{
    AddWatchMode, Client, CreateMode, Error, EventKind, MultiError, MultiReader, MultiWriter,
    OpResult, ReadResult, Reconfig, SessionId, SessionPassword, SessionState, Version, WatchError,
    WatcherKind,
};
use common::{WAIT, builder, within};
use zk_fakeserver::{
    Action, AuthPolicy, ClusterConfig, Detail, FakeServer, FrameKind, FrameRecord, Rule,
    ServerOptions,
};

// Opcodes of the requests that the tests look for.
const OP_GET_CHILDREN2: i32 = 12;
const OP_SYNC: i32 = 9;
const OP_MULTI: i32 = 14;
const OP_REMOVE_WATCHES: i32 = 18;
const OP_GET_DATA: i32 = 4;
const OP_CREATE2: i32 = 15;
const OP_CLOSE: i32 = -11;
const OP_AUTH: i32 = 100;

/// Establishes an asynchronous client session against the mock server fixture.
async fn connect(fs: &FakeServer) -> Client {
    within(builder(&fs.connect_string()).connect())
        .await
        .expect("connect")
}

/// Returns recorded request protocol frames in reception sequence.
fn requests(fs: &FakeServer) -> Vec<FrameRecord> {
    fs.transcript()
        .into_iter()
        .filter(|r| r.kind == FrameKind::Request)
        .collect()
}

/// Predicate verifying whether the server captured a frame matching `op`.
fn saw_op(fs: &FakeServer, op: i32) -> bool {
    requests(fs).iter().any(|r| r.op == Some(op))
}

/// Instantiates a mock server bound to custom cluster configuration.
fn fake_with(cfg: ClusterConfig) -> FakeServer {
    FakeServer::start_with(cfg, ServerOptions::default()).expect("fake server bind")
}

// ---- CRUD ----

#[tokio::test]
async fn crud_round_trip() {
    let fs = common::fake();
    let c = connect(&fs).await;

    let (path, stat) = within(c.create("/a", b"one", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(path.as_str(), "/a");
    assert_eq!(stat.version, Version::new(0));
    assert_eq!(fs.cluster().data("/a"), Some(Some(b"one".to_vec())));

    let (data, stat) = within(c.get_data("/a")).await.unwrap();
    assert_eq!(data, b"one");
    assert_eq!(stat.data_length, 3);
    let (data, _) = within(c.get_data_opt("/a")).await.unwrap();
    assert_eq!(data.as_deref(), Some(&b"one"[..]));
    assert!(within(c.exists("/a")).await.unwrap().is_some());

    let stat = within(c.set_data("/a", b"two", stat.version))
        .await
        .unwrap();
    assert_eq!(stat.version, Version::new(1));
    assert_eq!(
        within(c.set_data("/a", b"x", Version::new(0))).await,
        Err(Error::BadVersion)
    );
    assert_eq!(
        within(c.create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)).await,
        Err(Error::NodeExists)
    );

    assert_eq!(
        within(c.delete("/a", Version::new(0))).await,
        Err(Error::BadVersion)
    );
    within(c.delete("/a", None)).await.unwrap();
    assert_eq!(within(c.exists("/a")).await, Ok(None));
    assert_eq!(within(c.get_data("/a")).await, Err(Error::NoNode));
    assert_eq!(within(c.delete("/a", None)).await, Err(Error::NoNode));
    within(c.close()).await.unwrap();
}

#[tokio::test]
async fn empty_data_goes_out_present_and_never_null() {
    let fs = common::fake();
    let c = connect(&fs).await;
    // Client passes payload data to the driver as a borrowed slice.
    // Empty payloads must reach the wire as a present, zero-length buffer rather than null.
    within(c.create("/e", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(fs.cluster().data("/e"), Some(Some(Vec::new())));
    within(c.set_data("/e", b"abc", None)).await.unwrap();
    assert_eq!(fs.cluster().data("/e"), Some(Some(b"abc".to_vec())));
    within(c.set_data("/e", b"", None)).await.unwrap();
    assert_eq!(fs.cluster().data("/e"), Some(Some(Vec::new())));
    within(c.close()).await.unwrap();
}

#[tokio::test]
async fn null_data_reads_as_empty_or_none() {
    let fs = common::fake();
    let c = connect(&fs).await;
    // The decoders map empty or null data to an empty vector or to
    // `None`, so both forms pass.
    within(c.create("/n", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (data, _) = within(c.get_data("/n")).await.unwrap();
    assert!(data.is_empty());
    let (data, _) = within(c.get_data_opt("/n")).await.unwrap();
    assert!(data.is_none_or(|d| d.is_empty()));
}

#[tokio::test]
async fn sequential_nodes_and_children() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.create("/p", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (first, _) = within(c.create(
        "/p/c-",
        b"",
        CreateMode::PersistentSequential,
        OPEN_ACL_UNSAFE,
    ))
    .await
    .unwrap();
    assert_eq!(first.as_str(), "/p/c-0000000000");
    // A sequential mode accepts a path that ends with `/`.
    let (second, _) = within(c.create(
        "/p/",
        b"",
        CreateMode::PersistentSequential,
        OPEN_ACL_UNSAFE,
    ))
    .await
    .unwrap();
    assert_eq!(second.as_str(), "/p/0000000001");

    let mut names = within(c.get_children("/p")).await.unwrap();
    names.sort();
    assert_eq!(
        names,
        vec!["0000000001".to_owned(), "c-0000000000".to_owned()]
    );
    let (names, stat) = within(c.get_children_with_stat("/p")).await.unwrap();
    assert_eq!(names.len(), 2);
    assert_eq!(stat.num_children, 2);
    assert_eq!(within(c.get_all_children_number("/p")).await, Ok(2));
    assert_eq!(within(c.get_children("/none")).await, Err(Error::NoNode));

    // Both child lists go out as getChildren2 (op 12).
    let ops: Vec<i32> = requests(&fs).iter().filter_map(|r| r.op).collect();
    assert!(!ops.contains(&8), "op 8 must not go out: {ops:?}");
    assert!(ops.contains(&OP_GET_CHILDREN2));
}

#[tokio::test]
async fn ephemerals_of_the_session() {
    let fs = common::fake();
    let c = connect(&fs).await;
    for p in ["/e1", "/e2", "/x"] {
        within(c.create(p, b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
            .await
            .unwrap();
    }
    let mut some: Vec<String> = within(c.get_ephemerals("/e"))
        .await
        .unwrap()
        .into_iter()
        .map(|p| p.as_str().to_owned())
        .collect();
    some.sort();
    assert_eq!(some, vec!["/e1".to_owned(), "/e2".to_owned()]);
    assert_eq!(within(c.get_ephemerals_all()).await.unwrap().len(), 3);
    let stat = within(c.exists("/x")).await.unwrap().unwrap();
    assert_eq!(stat.ephemeral_owner, c.session().0.0);

    // The close removes the ephemeral nodes.
    within(c.close()).await.unwrap();
    assert!(fs.cluster().wait_until(WAIT, |cl| cl.node("/x").is_none()));
}

#[tokio::test]
async fn a_bad_path_sends_nothing() {
    let fs = common::fake();
    let c = connect(&fs).await;
    assert!(matches!(
        within(c.get_data("no-slash")).await,
        Err(Error::InvalidPath(_))
    ));
    assert!(matches!(
        within(c.create("/a/", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)).await,
        Err(Error::InvalidPath(_))
    ));
    assert!(matches!(
        within(c.exists_watched("")).await,
        Err(Error::InvalidPath(_))
    ));
    assert!(matches!(
        within(c.add_watch("/a//b", AddWatchMode::Persistent)).await,
        Err(Error::InvalidPath(_))
    ));
    within(c.sync("/")).await.unwrap();
    assert!(!saw_op(&fs, OP_GET_DATA));
    assert!(!saw_op(&fs, OP_CREATE2));
    assert_eq!(fs.cluster().watch_count(), 0);
}

#[tokio::test]
async fn call_order_is_wire_order() {
    let fs = common::fake();
    let c = connect(&fs).await;
    // Every call submits before it returns, so no await is needed for
    // the order.
    let futures: Vec<_> = (0..40)
        .map(|i| {
            c.create(
                format!("/n{i:02}"),
                b"",
                CreateMode::Persistent,
                OPEN_ACL_UNSAFE,
            )
        })
        .collect();
    for f in futures {
        within(f).await.unwrap();
    }
    let creates: Vec<FrameRecord> = requests(&fs)
        .into_iter()
        .filter(|r| r.op == Some(OP_CREATE2))
        .collect();
    let paths: Vec<String> = creates.iter().filter_map(|r| r.path.clone()).collect();
    let expected: Vec<String> = (0..40).map(|i| format!("/n{i:02}")).collect();
    assert_eq!(paths, expected);
    let xids: Vec<i32> = creates.iter().filter_map(|r| r.xid).collect();
    assert!(xids.windows(2).all(|w| w.first() < w.get(1)));
}

#[tokio::test]
async fn a_chroot_applies_to_requests_and_replies() {
    let fs = common::fake();
    fs.cluster().create("/app", b"", 0).unwrap();
    let c = within(builder(&format!("{}/app", fs.connect_string())).connect())
        .await
        .unwrap();
    let (p, _) = within(c.create("/x", b"v", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(p.as_str(), "/x");
    assert!(fs.cluster().node("/app/x").is_some());

    // The builder override replaces the chroot of the connect string.
    fs.cluster().create("/other", b"", 0).unwrap();
    let d = within(
        builder(&format!("{}/app", fs.connect_string()))
            .chroot("/other")
            .connect(),
    )
    .await
    .unwrap();
    within(d.create("/y", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert!(fs.cluster().node("/other/y").is_some());
}

// ---- watches ----

#[tokio::test]
async fn oneshot_watches_fire() {
    let fs = common::fake();
    let c = connect(&fs).await;

    // An exist watch arms on a missing node.
    let (stat, w) = within(c.exists_watched("/w")).await.unwrap();
    assert!(stat.is_none());
    assert_eq!(w.kind(), WatcherKind::Data);
    fs.cluster().create("/w", b"", 0).unwrap();
    let ev = within(w).await.unwrap();
    assert_eq!(ev.kind, EventKind::NodeCreated);
    assert_eq!(ev.path.as_str(), "/w");

    let (_, _, w) = within(c.get_data_watched("/w")).await.unwrap();
    fs.cluster().set_data("/w", b"new").unwrap();
    assert_eq!(within(w).await.unwrap().kind, EventKind::NodeDataChanged);

    let (children, _, w) = within(c.get_children_watched("/w")).await.unwrap();
    assert!(children.is_empty());
    assert_eq!(w.kind(), WatcherKind::Children);
    fs.cluster().create("/w/c", b"", 0).unwrap();
    assert_eq!(
        within(w).await.unwrap().kind,
        EventKind::NodeChildrenChanged
    );
}

#[tokio::test]
async fn a_failed_watched_read_gives_no_watcher() {
    let fs = common::fake();
    let c = connect(&fs).await;
    assert_eq!(
        within(c.get_data_watched("/missing")).await.err(),
        Some(Error::NoNode)
    );
    assert_eq!(
        within(c.get_children_watched("/missing")).await.err(),
        Some(Error::NoNode)
    );
    assert_eq!(fs.cluster().watch_count(), 0);
}

#[tokio::test]
async fn persistent_watch_and_removal() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let mut w = within(c.add_watch("/pw", AddWatchMode::PersistentRecursive))
        .await
        .unwrap();
    assert_eq!(w.mode(), AddWatchMode::PersistentRecursive);
    fs.cluster().create("/pw", b"", 0).unwrap();
    fs.cluster().create("/pw/c", b"", 0).unwrap();
    let a = within(w.next()).await.unwrap().unwrap();
    let b = within(w.next()).await.unwrap().unwrap();
    assert_eq!((a.kind, a.path.as_str()), (EventKind::NodeCreated, "/pw"));
    assert_eq!((b.kind, b.path.as_str()), (EventKind::NodeCreated, "/pw/c"));

    within(c.remove_watches("/pw", WatcherKind::PersistentRecursive, false))
        .await
        .unwrap();
    assert!(saw_op(&fs, OP_REMOVE_WATCHES));
    match within(w.next()).await {
        Some(Err(WatchError::Removed)) | None => {}
        other => panic!("unexpected item after removal: {other:?}"),
    }
}

#[tokio::test]
async fn a_local_removal_asks_no_server() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let (_, w) = within(c.exists_watched("/lw")).await.unwrap();
    within(c.remove_watches("/lw", WatcherKind::Data, true))
        .await
        .unwrap();
    assert_eq!(within(w).await, Err(WatchError::Removed));
    assert!(!saw_op(&fs, OP_REMOVE_WATCHES));
}

// ---- ACL ----

#[tokio::test]
async fn acl_round_trip() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.create("/acl", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (acl, _) = within(c.get_acl("/acl")).await.unwrap();
    assert_eq!(acl, OPEN_ACL_UNSAFE.to_vec());
    assert_eq!(
        within(c.set_acl("/acl", READ_ACL_UNSAFE, Version::new(5))).await,
        Err(Error::BadVersion)
    );
    // The server applies the change even when the reply has no stat.
    let _ = within(c.set_acl("/acl", READ_ACL_UNSAFE, Version::new(0))).await;
    let (acl, stat) = within(c.get_acl("/acl")).await.unwrap();
    assert_eq!(acl, READ_ACL_UNSAFE.to_vec());
    assert_eq!(stat.aversion, Version::new(1));
    assert_eq!(
        within(c.set_data("/acl", b"x", None)).await,
        Err(Error::NoAuth)
    );
}

/// Asserts that `set_acl` populates and returns the updated znode stat structure.
#[tokio::test]
async fn set_acl_gives_the_new_stat() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.create("/acl", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let stat = within(c.set_acl("/acl", READ_ACL_UNSAFE, None))
        .await
        .unwrap();
    assert_eq!(stat.aversion, Version::new(1));
}

#[tokio::test]
async fn sync_goes_out() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.sync("/")).await.unwrap();
    assert!(saw_op(&fs, OP_SYNC));
}

// ---- transactions ----

#[tokio::test]
async fn multi_commits_and_aborts() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.create("/m", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();

    let mut txn = MultiWriter::new();
    let made = txn
        .create("/m/a", b"1", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let set = txn.set_data("/m", b"top", Version::new(0)).unwrap();
    let check = txn.check("/m/a", Version::new(0)).unwrap();
    let results = within(c.multi(&txn)).await.unwrap();
    assert_eq!(results.get(made).unwrap().path.as_str(), "/m/a");
    assert_eq!(results.get(set).unwrap().version, Version::new(1));
    assert!(results.get(check).is_some());
    assert_eq!(results.iter().count(), 3);

    let mut bad = MultiWriter::new();
    bad.create("/m/b", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    bad.check("/m", Version::new(7)).unwrap();
    match within(c.multi(&bad)).await {
        Err(MultiError::Aborted { index, error, .. }) => {
            assert_eq!(index, 1);
            assert_eq!(error, Error::BadVersion);
        }
        other => panic!("expected an abort: {other:?}"),
    }
    assert!(fs.cluster().node("/m/b").is_none());

    // An empty transaction still goes to the server.
    let before = requests(&fs)
        .iter()
        .filter(|r| r.op == Some(OP_MULTI))
        .count();
    let empty = within(c.multi(&MultiWriter::new())).await.unwrap();
    assert_eq!(empty.into_vec(), Vec::<OpResult>::new());
    let after = requests(&fs)
        .iter()
        .filter(|r| r.op == Some(OP_MULTI))
        .count();
    assert_eq!(after, before.checked_add(1).unwrap());
}

#[tokio::test]
async fn multi_read_gives_one_result_per_op() {
    let fs = common::fake();
    fs.cluster().create("/r", b"data", 0).unwrap();
    fs.cluster().create("/r/k", b"", 0).unwrap();
    let c = connect(&fs).await;
    let mut reads = MultiReader::new();
    reads.get_data("/r").unwrap();
    reads.get_children("/r").unwrap();
    reads.get_data("/missing").unwrap();
    let out = within(c.multi_read(&reads)).await.unwrap();
    assert_eq!(out.len(), 3);
    assert!(matches!(
        out.first(),
        Some(Ok(ReadResult::Data { data, .. })) if data == b"data"
    ));
    assert!(matches!(
        out.get(1),
        Some(Ok(ReadResult::Children(names))) if names == &vec!["k".to_owned()]
    ));
    assert_eq!(out.get(2), Some(&Err(Error::NoNode)));
}

// ---- reconfiguration ----

const CONFIG: &str = "server.1=127.0.0.1:2888:3888:participant;0.0.0.0:2181\nversion=100000000";

#[tokio::test]
async fn get_config_ignores_the_chroot() {
    let fs = common::fake();
    fs.cluster().set_config_data(CONFIG);
    fs.cluster().create("/app", b"", 0).unwrap();
    let c = within(builder(&format!("{}/app", fs.connect_string())).connect())
        .await
        .unwrap();
    let (cfg, _) = within(c.get_config()).await.unwrap();
    assert_eq!(cfg.version, Some(0x1_0000_0000));
    assert_eq!(cfg.servers.len(), 1);

    let (_, _, w) = within(c.get_config_watched()).await.unwrap();
    // `set_config_data` fires no watch, so the change uses `set_data`.
    fs.cluster()
        .set_data(
            "/zookeeper/config",
            b"server.1=127.0.0.1:2888:3888;2181\nversion=100000001",
        )
        .unwrap();
    let ev = within(w).await.unwrap();
    assert_eq!(ev.kind, EventKind::NodeDataChanged);
    assert_eq!(ev.path.as_str(), "/zookeeper/config");
}

#[tokio::test]
async fn reconfig_sends_the_change() {
    let fs = common::fake();
    fs.cluster().set_config_data(CONFIG);
    let c = connect(&fs).await;
    let change = Reconfig::Incremental {
        joining: vec!["server.2=127.0.0.1:2889:3889;2182".to_owned()],
        leaving: vec![],
    };
    assert_eq!(
        within(c.reconfig(&change, None)).await.err(),
        Some(Error::ReconfigDisabled)
    );

    let on = fake_with(ClusterConfig {
        reconfig_enabled: true,
        ..ClusterConfig::default()
    });
    on.cluster().set_config_data(CONFIG);
    let c = connect(&on).await;
    assert_eq!(
        within(c.reconfig(&change, Some(5))).await.err(),
        Some(Error::BadVersion)
    );
    let (cfg, _) = within(c.reconfig(&change, Some(0x1_0000_0000)))
        .await
        .unwrap();
    assert_eq!(cfg.servers.len(), 2);
}

#[tokio::test]
async fn update_servers_moves_the_client() {
    let a = common::fake();
    let b = a
        .cluster()
        .start_server(ServerOptions::default())
        .expect("second listener");
    let c = connect(&a).await;
    assert_eq!(c.connected_server(), Some(a.addr()));

    assert!(matches!(
        c.update_servers(""),
        Err(Error::InvalidArgument(_))
    ));
    assert!(matches!(
        c.update_servers(&format!("{}/chroot", b.connect_string())),
        Err(Error::InvalidArgument(_))
    ));
    assert_eq!(c.connected_server(), Some(a.addr()));

    c.update_servers(&b.connect_string()).unwrap();
    a.drop_connections();
    let mut w = c.state_watcher();
    within(async {
        while c.connected_server() != Some(b.addr()) {
            w.changed().await;
        }
    })
    .await;
    within(c.close()).await.unwrap();
    assert!(matches!(
        c.update_servers(&b.connect_string()),
        Err(Error::Closing)
    ));
}

// The fake server waits block, so this test needs a runtime with more than one thread.
#[tokio::test(flavor = "multi_thread")]
async fn update_servers_from_config_follows_the_config_node() {
    let a = common::fake();
    let b = a
        .cluster()
        .start_server(ServerOptions::default())
        .expect("second listener");
    let c = within(
        builder(&a.connect_string())
            .update_servers_from_config(true)
            .connect(),
    )
    .await
    .expect("connect");
    assert_eq!(c.connected_server(), Some(a.addr()));
    assert!(
        a.wait_for(WAIT, |r| r.op == Some(OP_GET_DATA)
            && r.path.as_deref() == Some("/zookeeper/config"))
            .is_some()
    );
    assert_eq!(c.connected_server(), Some(a.addr()));

    let mut w = c.state_watcher();
    for (n, to) in [(2, &b), (3, &a)] {
        let text = format!(
            "server.{n}={0}:2888:3888:participant;{0}:{1}\nversion=10000000{n}",
            to.addr().ip(),
            to.addr().port()
        );
        a.cluster()
            .set_data("/zookeeper/config", text.as_bytes())
            .unwrap();
        within(async {
            while c.connected_server() != Some(to.addr()) {
                w.changed().await;
            }
        })
        .await;
    }
    within(c.close()).await.unwrap();
}

// ---- auth ----

#[tokio::test]
async fn add_auth_and_who_am_i() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.add_auth("digest", b"user:pw".to_vec()))
        .await
        .unwrap();
    let ids = within(c.who_am_i()).await.unwrap();
    assert!(
        ids.iter()
            .any(|i| i.auth_scheme == "digest" && i.user == "user"),
        "{ids:?}"
    );
    let auth = requests(&fs)
        .into_iter()
        .find(|r| r.op == Some(OP_AUTH))
        .expect("auth frame");
    assert!(matches!(
        auth.detail,
        Detail::Auth { ref scheme, ref auth, .. } if scheme == "digest" && auth == b"user:pw"
    ));
}

#[tokio::test]
async fn a_refused_add_auth_ends_the_session() {
    let fs = fake_with(ClusterConfig {
        auth: AuthPolicy::RejectAll,
        ..ClusterConfig::default()
    });
    let c = connect(&fs).await;
    assert_eq!(
        within(c.add_auth("digest", b"user:pw".to_vec())).await,
        Err(Error::AuthFailed)
    );
    let mut w = c.state_watcher();
    within(async {
        while c.state().state != SessionState::AuthFailed {
            w.changed().await;
        }
    })
    .await;
    assert_eq!(within(c.get_data("/")).await.err(), Some(Error::AuthFailed));
    assert!(matches!(
        within(c.add_auth("digest", b"u:p".to_vec())).await,
        Err(Error::AuthFailed)
    ));
}

// ---- connect and build ----

#[tokio::test]
async fn connect_waits_for_builder_auth() {
    let fs = common::fake();
    fs.add_rule(
        Rule::on_request()
            .op(OP_AUTH)
            .then(Action::Delay(Duration::from_millis(200)))
            .then(Action::Model),
    );
    let c = within(
        builder(&fs.connect_string())
            .auth("digest", b"a:b".to_vec())
            .auth("digest", b"c:d".to_vec())
            .connect(),
    )
    .await
    .unwrap();
    assert!(c.state().state.is_connected());
    let n = requests(&fs)
        .iter()
        .filter(|r| r.op == Some(OP_AUTH))
        .count();
    assert_eq!(n, 2);
    let ids = within(c.who_am_i()).await.unwrap();
    assert_eq!(ids.iter().filter(|i| i.auth_scheme == "digest").count(), 2);
}

/// Verifies that connection loss occurring prior to authentication confirmation does not terminate
/// the connection attempt: credentials are retransmitted upon link recovery and connection
/// awaiting continues.
#[tokio::test]
async fn connect_survives_a_loss_before_the_auth_reply() {
    let fs = common::fake();
    fs.add_rule(Rule::on_request().op(OP_AUTH).once().then(Action::Close));
    let c = within(
        builder(&fs.connect_string())
            .auth("digest", b"a:b".to_vec())
            .connect(),
    )
    .await
    .unwrap();
    assert!(c.state().state.is_connected());
    assert!(fs.accepted() >= 2, "the client must connect again");
    let ids = within(c.who_am_i()).await.unwrap();
    assert!(ids.iter().any(|i| i.auth_scheme == "digest"), "{ids:?}");
    within(c.close()).await.unwrap();
}

/// Verifies that `connect()` succeeds when the server drops the connection upon receiving the
/// authentication request before replying and the client reconnects to a read-only server (which
/// replies to a `sync` with `NOTREADONLY`). Asserts that `connect()` completes in the read-only
/// state, the client connected at least twice, and the server reports the `digest` identity.
#[tokio::test]
async fn connect_survives_an_auth_loss_on_a_read_only_server() {
    let fs = FakeServer::start_with(
        ClusterConfig::default(),
        ServerOptions {
            read_only: true,
            ..ServerOptions::default()
        },
    )
    .expect("fake server bind");
    fs.add_rule(Rule::on_request().op(OP_AUTH).once().then(Action::Close));
    let c = within(
        builder(&fs.connect_string())
            .allow_read_only(true)
            .auth("digest", b"a:b".to_vec())
            .connect(),
    )
    .await
    .unwrap();
    assert_eq!(c.state().state, SessionState::ConnectedReadOnly);
    assert!(fs.accepted() >= 2, "the client must connect again");
    let ids = within(c.who_am_i()).await.unwrap();
    assert!(ids.iter().any(|i| i.auth_scheme == "digest"), "{ids:?}");
    within(c.close()).await.unwrap();
}

/// Synchronous blocking (`wait()`) form of [`connect_survives_an_auth_loss_on_a_read_only_server`].
#[cfg(feature = "blocking")]
#[test]
fn blocking_connect_survives_an_auth_loss_on_a_read_only_server() {
    let fs = FakeServer::start_with(
        ClusterConfig::default(),
        ServerOptions {
            read_only: true,
            ..ServerOptions::default()
        },
    )
    .expect("fake server bind");
    fs.add_rule(Rule::on_request().op(OP_AUTH).once().then(Action::Close));
    let c = builder(&fs.connect_string())
        .allow_read_only(true)
        .auth("digest", b"a:b".to_vec())
        .connect()
        .wait()
        .unwrap();
    assert_eq!(c.state().state, SessionState::ConnectedReadOnly);
    assert!(fs.accepted() >= 2, "the client must connect again");
    c.close().wait().unwrap();
}

/// Confirms that authentication rejection on a subsequent reconnection attempt correctly
/// terminates the pending connection future with `Error::AuthFailed`.
#[tokio::test]
async fn connect_fails_on_a_refusal_after_a_loss() {
    let fs = fake_with(ClusterConfig {
        auth: AuthPolicy::RejectAll,
        ..ClusterConfig::default()
    });
    fs.add_rule(Rule::on_request().op(OP_AUTH).once().then(Action::Close));
    let e = within(
        builder(&fs.connect_string())
            .auth("digest", b"a:b".to_vec())
            .connect(),
    )
    .await
    .unwrap_err();
    assert_eq!(e.error(), &Error::AuthFailed);
}

#[tokio::test]
async fn connect_fails_on_refused_auth() {
    let fs = fake_with(ClusterConfig {
        auth: AuthPolicy::Only(vec!["good:pw".to_owned()]),
        ..ClusterConfig::default()
    });
    let e = within(
        builder(&fs.connect_string())
            .auth("digest", b"bad:pw".to_vec())
            .connect(),
    )
    .await
    .unwrap_err();
    assert_eq!(e.error(), &Error::AuthFailed);

    within(
        builder(&fs.connect_string())
            .auth("digest", b"good:pw".to_vec())
            .connect(),
    )
    .await
    .unwrap();
}

#[tokio::test]
async fn connect_fails_on_an_expired_resumed_session() {
    let fs = common::fake();
    fs.add_rule(Rule::on_connect().then(Action::ExpiredHandshake));
    let e = within(
        builder(&fs.connect_string())
            .session(
                SessionId(0x0100_0000_0000_0042),
                SessionPassword::from_bytes([3; 16]),
            )
            .connect(),
    )
    .await
    .unwrap_err();
    assert_eq!(e.error(), &Error::SessionExpired);
}

#[tokio::test]
async fn connect_stays_pending_against_no_server() {
    // Bind and drop a listener, so the port refuses connections.
    let port = {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    };
    let f = builder(&format!("127.0.0.1:{port}"))
        .connect_timeout(Duration::from_millis(200))
        .connect();
    let mut w = f.state_watcher();
    assert_eq!(w.current().state, SessionState::Connecting);
    let r = tokio::time::timeout(Duration::from_millis(600), f).await;
    assert!(r.is_err(), "connect must stay pending");
    // The drop of the future closed the client.
    within(async {
        while w.current().state != SessionState::Closed {
            w.changed().await;
        }
    })
    .await;
}

#[tokio::test]
async fn build_returns_before_the_session() {
    let fs = common::fake();
    fs.hold();
    let c = builder(&fs.connect_string()).build().unwrap();
    assert_eq!(c.state().state, SessionState::Connecting);
    assert_eq!(c.negotiated_timeout(), None);
    assert_eq!(c.session().0, SessionId(0));
    // A request before the session waits in the queue.
    let f = c.create("/early", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
    let mut w = c.state_watcher();
    fs.release();
    within(async {
        while !c.state().state.is_connected() {
            w.changed().await;
        }
    })
    .await;
    within(f).await.unwrap();
    assert!(c.session().0 != SessionId(0));
}

#[tokio::test]
async fn build_checks_its_values() {
    let e = Client::builder("").build().unwrap_err();
    assert!(matches!(e.error(), Error::InvalidArgument(_)), "{e}");
    let e = Client::builder("127.0.0.1:1")
        .chroot("no-slash")
        .build()
        .unwrap_err();
    assert!(matches!(e.error(), Error::InvalidPath(_)), "{e}");
    let e = Client::builder("127.0.0.1:1")
        .session_timeout(Duration::from_secs(u64::from(u32::MAX)))
        .build()
        .unwrap_err();
    assert!(matches!(e.error(), Error::InvalidArgument(_)), "{e}");

    // The asynchronous connect future returns the validation error upon initial poll.
    let e = Client::builder("").connect().await.unwrap_err();
    assert!(matches!(e.error(), Error::InvalidArgument(_)), "{e}");
}

// ---- session and lifecycle ----

#[tokio::test]
async fn session_accessors_and_stats() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let (id, pw) = c.session();
    let server = fs.cluster().sessions();
    let mine = server.iter().find(|s| s.id == id.0).expect("session");
    assert_eq!(pw.as_bytes(), &mine.passwd);
    assert_eq!(c.negotiated_timeout(), Some(common::SESSION_TIMEOUT));
    assert_eq!(c.connected_server(), Some(fs.addr()));

    let (_, stat) = within(c.create("/z", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(c.last_zxid(), stat.czxid);
    let s = c.stats();
    assert_eq!(s.pending, 0);
    assert_eq!(s.reconnects, 0);
    assert_eq!(s.last_zxid, stat.czxid);
    assert_eq!(s.server, Some(fs.addr()));
    assert_eq!(s.negotiated_timeout, Some(common::SESSION_TIMEOUT));

    // A reconnect keeps the session and counts in the stats.
    let mut w = c.state_watcher();
    fs.drop_connections();
    within(async {
        while c.state().epoch < 2 {
            w.changed().await;
        }
    })
    .await;
    assert_eq!(c.stats().reconnects, 1);
    assert_eq!(c.session().0, id);
}

#[tokio::test]
async fn ready_resolves_with_no_limit() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.ready()).await.unwrap();
}

#[tokio::test]
async fn close_ends_the_session() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let other = c.clone();
    within(c.create("/eph", b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    within(c.close()).await.unwrap();
    assert!(saw_op(&fs, OP_CLOSE));
    assert_eq!(other.state().state, SessionState::Closed);
    assert!(fs.cluster().sessions().is_empty());
    assert!(fs.cluster().node("/eph").is_none());
    // Subsequent close invocations are idempotent, while new requests fail with Error::Closing.
    within(other.close()).await.unwrap();
    assert_eq!(
        within(other.get_data("/")).await.err(),
        Some(Error::Closing)
    );
    assert_eq!(other.connected_server(), None);
}

/// Validates close synchronization across multi-worker thread pools. Ensures `close().await`
/// resolves only after the driver task terminates, guaranteeing observing `SessionState::Closed`
/// immediately.
#[cfg(all(unix, feature = "tokio"))]
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_tokio_close_gives_closed_at_once() {
    let fs = common::fake();
    for _ in 0..20 {
        let c = within(
            builder(&fs.connect_string())
                .runtime(apache_zookeeper::Runtime::Tokio(
                    tokio::runtime::Handle::current(),
                ))
                .connect(),
        )
        .await
        .unwrap();
        within(c.close()).await.unwrap();
        assert_eq!(c.state().state, SessionState::Closed);
    }
}

/// Confirms that executor runtime shutdown cleanly halts driver tasks, transitioning the client
/// to `SessionState::Closed` and terminating registered watch streams.
#[cfg(all(unix, feature = "tokio"))]
#[test]
fn a_runtime_shutdown_closes_the_client_and_its_watchers() {
    let fs = common::fake();
    fs.cluster().create("/rs", b"x", 0).unwrap();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .unwrap();
    let (c, mut p, w) = rt.block_on(async {
        let c = within(
            builder(&fs.connect_string())
                .runtime(apache_zookeeper::Runtime::Tokio(
                    tokio::runtime::Handle::current(),
                ))
                .connect(),
        )
        .await
        .unwrap();
        let p = within(c.add_watch("/rs", AddWatchMode::Persistent))
            .await
            .unwrap();
        let (_, _, w) = within(c.get_data_watched("/rs")).await.unwrap();
        (c, p, w)
    });
    let mut sw = c.state_watcher();
    assert!(c.state().state.is_connected());
    drop(rt);
    assert_eq!(c.state().state, SessionState::Closed);
    // Polling the watchers on a fresh runtime resolves each with `Closed`.
    let rt2 = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    rt2.block_on(async {
        assert_eq!(within(sw.changed()).await.state, SessionState::Closed);
        assert_eq!(within(p.next()).await, Some(Err(WatchError::Closed)));
        assert_eq!(within(p.next()).await, None);
        assert_eq!(within(w).await, Err(WatchError::Closed));
        assert_eq!(c.get_data("/rs").await, Err(Error::Closing));
    });
}

#[tokio::test]
async fn the_last_drop_closes() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let clone = c.clone();
    let mut w = c.state_watcher();
    drop(c);
    within(clone.sync("/")).await.unwrap();
    assert_eq!(fs.cluster().sessions().len(), 1);
    drop(clone);
    within(async {
        while w.current().state != SessionState::Closed {
            w.changed().await;
        }
    })
    .await;
    assert!(fs.cluster().wait_until(WAIT, |cl| cl.sessions().is_empty()));
}

/// Verifies weak reference upgrading: `WeakClient::upgrade` yields an active `Client` reference
/// sharing the underlying session while strong clones exist.
#[tokio::test]
async fn a_weak_client_upgrades_while_a_clone_lives() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let weak = c.downgrade();
    let up = weak.upgrade().expect("a clone lives");
    assert_eq!(up.session(), c.session());
    drop(c);
    within(up.sync("/")).await.unwrap();
    assert_eq!(fs.cluster().sessions().len(), 1);
    assert!(weak.clone().upgrade().is_some());
    drop(up);
    assert!(fs.cluster().wait_until(WAIT, |cl| cl.sessions().is_empty()));
}

/// Verifies that `WeakClient` does not prevent session termination. Dropping all strong `Client`
/// handles initiates session teardown, causing subsequent `upgrade()` calls to return `None`.
#[tokio::test]
async fn a_weak_client_does_not_keep_the_session_open() {
    let fs = common::fake();
    let c = connect(&fs).await;
    let weak = c.downgrade();
    let mut w = c.state_watcher();
    drop(c);
    assert!(weak.upgrade().is_none());
    within(async {
        while w.current().state != SessionState::Closed {
            w.changed().await;
        }
    })
    .await;
    assert!(saw_op(&fs, OP_CLOSE));
    assert!(fs.cluster().wait_until(WAIT, |cl| cl.sessions().is_empty()));
    assert!(weak.upgrade().is_none());
}

#[tokio::test]
async fn detach_keeps_the_session_for_a_resume() {
    let fs = common::fake();
    let c = connect(&fs).await;
    within(c.create("/keep", b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (_, w) = within(c.exists_watched("/keep")).await.unwrap();
    let (id, pw) = c.detach();
    assert!(id != SessionId(0));
    assert_eq!(within(w).await, Err(WatchError::Closed));
    assert!(!saw_op(&fs, OP_CLOSE));
    assert!(fs.cluster().node("/keep").is_some());

    let d = within(builder(&fs.connect_string()).session(id, pw).connect())
        .await
        .unwrap();
    assert_eq!(d.session().0, id);
    let info = fs
        .wait_for(WAIT, |r| {
            r.kind == FrameKind::Connect && r.session_id == id.0
        })
        .expect("resume handshake");
    assert!(matches!(info.detail, Detail::Connect(ref ci) if ci.session_id == id.0));
    within(d.close()).await.unwrap();
    assert!(fs.cluster().node("/keep").is_none());
}

// ---- blocking facade ----

#[cfg(feature = "blocking")]
mod blocking {
    use super::*;
    #[cfg(feature = "tokio")]
    use apache_zookeeper::{Runtime, ThreadOptions};

    /// Binds a listener on an ephemeral loopback port and drops it, returning a port on which
    /// connections are refused.
    fn refused_port() -> u16 {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        l.local_addr().unwrap().port()
    }

    /// Polls the state watcher until the session state is `SessionState::Closed`, failing the test
    /// if that takes longer than `WAIT`.
    fn wait_closed(w: &mut apache_zookeeper::StateWatcher) {
        let end = std::time::Instant::now().checked_add(WAIT).unwrap();
        while w.current().state != SessionState::Closed {
            assert!(std::time::Instant::now() < end, "the client must close");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    /// Asserts that against an unreachable ensemble, a 300 ms `wait_timeout` returns
    /// `Error::OperationTimeout` after at least 300 ms and in under 2 s, and that the client is
    /// closed.
    #[test]
    fn connect_wait_timeout_ends_against_no_server() {
        let f = builder(&format!("127.0.0.1:{}", refused_port()))
            .connect_timeout(Duration::from_millis(100))
            .connect();
        let mut w = f.state_watcher();
        let start = std::time::Instant::now();
        let e = f.wait_timeout(Duration::from_millis(300)).unwrap_err();
        let took = start.elapsed();
        assert_eq!(e.error(), &Error::OperationTimeout);
        assert!(took >= Duration::from_millis(300), "{took:?}");
        assert!(took < Duration::from_secs(2), "{took:?}");
        wait_closed(&mut w);
    }

    /// Verifies that the timeout bound also covers awaiting authentication replies: when the
    /// server delays the auth reply by 3 s, a 300 ms `wait_timeout` still returns
    /// `Error::OperationTimeout` in under 2 s and closes the client.
    #[test]
    fn connect_wait_timeout_covers_the_auth_reply() {
        let fs = common::fake();
        fs.add_rule(
            Rule::on_request()
                .op(OP_AUTH)
                .then(Action::Delay(Duration::from_secs(3)))
                .then(Action::Model),
        );
        let f = builder(&fs.connect_string())
            .auth("digest", b"a:b".to_vec())
            .connect();
        let mut w = f.state_watcher();
        let start = std::time::Instant::now();
        let e = f.wait_timeout(Duration::from_millis(300)).unwrap_err();
        assert_eq!(e.error(), &Error::OperationTimeout);
        assert!(
            start.elapsed() < Duration::from_secs(2),
            "{:?}",
            start.elapsed()
        );
        wait_closed(&mut w);
    }

    /// Asserts that a bounded wait returns a connected client when connection establishment
    /// completes within the bound.
    #[test]
    fn connect_wait_timeout_returns_the_client() {
        let fs = common::fake();
        let c = builder(&fs.connect_string())
            .auth("digest", b"a:b".to_vec())
            .connect()
            .wait_timeout(WAIT)
            .unwrap();
        assert!(c.state().state.is_connected());
        c.close().wait().unwrap();
    }

    /// Asserts that `blocking::Client::connect_timeout` against an unreachable ensemble returns
    /// `Error::OperationTimeout` in under 2 s.
    #[test]
    fn blocking_connect_timeout_ends_against_no_server() {
        let b = builder(&format!("127.0.0.1:{}", refused_port()))
            .connect_timeout(Duration::from_millis(100));
        let start = std::time::Instant::now();
        let e = apache_zookeeper::blocking::Client::connect_timeout(b, Duration::from_millis(300))
            .unwrap_err();
        assert_eq!(e.error(), &Error::OperationTimeout);
        assert!(
            start.elapsed() < Duration::from_secs(2),
            "{:?}",
            start.elapsed()
        );
    }

    /// Asserts that synchronous blocking connection tolerates link loss during credential exchange
    /// and resumes upon reconnection.
    #[test]
    fn blocking_connect_survives_a_loss_before_the_auth_reply() {
        let fs = common::fake();
        fs.add_rule(Rule::on_request().op(OP_AUTH).once().then(Action::Close));
        let c = builder(&fs.connect_string())
            .auth("digest", b"a:b".to_vec())
            .connect()
            .wait()
            .unwrap();
        assert!(c.state().state.is_connected());
        assert!(fs.accepted() >= 2, "the client must connect again");
        c.close().wait().unwrap();
    }

    #[test]
    fn the_blocking_facade_works() {
        let fs = common::fake();
        let c = builder(&fs.connect_string()).connect().wait().unwrap();
        c.create("/b", b"v", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .wait()
            .unwrap();
        let (data, _) = c.get_data("/b").wait().unwrap();
        assert_eq!(data, b"v");
        let (_, _, w) = c.get_data_watched("/b").wait().unwrap();
        fs.cluster().set_data("/b", b"w").unwrap();
        assert_eq!(w.wait().unwrap().kind, EventKind::NodeDataChanged);
        let mut sw = c.state_watcher();
        c.close().wait().unwrap();
        assert_eq!(sw.wait_changed().unwrap().state, SessionState::Closed);
    }

    #[test]
    fn blocking_connect_reports_auth_failure() {
        let fs = fake_with(ClusterConfig {
            auth: AuthPolicy::RejectAll,
            ..ClusterConfig::default()
        });
        let e = builder(&fs.connect_string())
            .auth("digest", b"x:y".to_vec())
            .connect()
            .wait()
            .unwrap_err();
        assert_eq!(e.error(), &Error::AuthFailed);
    }

    /// Validates execution invariant: blocking waits executed within an active Tokio runtime
    /// thread fail with `Error::InvalidState` to prevent deadlocking driver event loops.
    #[cfg(feature = "tokio")]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_blocking_wait_in_tokio_is_refused() {
        let fs = common::fake();
        let e = builder(&fs.connect_string()).connect().wait().unwrap_err();
        assert_eq!(e.error(), &Error::InvalidState);
    }

    /// Validates driver selection under single threaded Tokio executors: automatically defaults
    /// to dedicated I/O threads, permitting synchronous blocking waits without starving the
    /// executor.
    #[cfg(feature = "tokio")]
    #[tokio::test]
    async fn a_current_thread_runtime_gets_the_thread_driver() {
        let fs = common::fake();
        fs.cluster().create("/ct", b"v", 0).unwrap();
        let c = builder(&fs.connect_string()).connect().wait().unwrap();
        assert_eq!(c.get_data("/ct").wait().unwrap().0, b"v");
        // Asynchronous operations execute normally on the same current-thread runtime.
        assert_eq!(within(c.get_data("/ct")).await.unwrap().0, b"v");
        assert_eq!(c.close().wait(), Ok(()));
    }

    /// Confirms that clients configured with dedicated thread drivers execute safely inside Tokio
    /// `spawn_blocking` pools.
    #[cfg(feature = "tokio")]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_thread_driver_client_waits_in_spawn_blocking() {
        let fs = common::fake();
        fs.cluster().create("/sb", b"v", 0).unwrap();
        let cs = fs.connect_string();
        let r = tokio::task::spawn_blocking(move || {
            let c = builder(&cs)
                .runtime(Runtime::Thread(ThreadOptions::default()))
                .connect()
                .wait()
                .unwrap();
            let (data, _) = c.get_data("/sb").wait().unwrap();
            let (_, _, w) = c.get_data_watched("/sb").wait().unwrap();
            let mut sw = c.state_watcher();
            c.close().wait().unwrap();
            let closed = sw.wait_changed().unwrap().state;
            (data, w.wait().is_err(), closed)
        })
        .await
        .unwrap();
        assert_eq!(r, (b"v".to_vec(), true, SessionState::Closed));
    }

    /// Verifies that clients backed by the Tokio driver reject synchronous blocking waits inside
    /// `spawn_blocking` pools while supporting `Handle::block_on`.
    #[cfg(all(unix, feature = "tokio"))]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_tokio_driver_client_refuses_waits_in_spawn_blocking() {
        let fs = common::fake();
        fs.cluster().create("/tb", b"v", 0).unwrap();
        let c = common::connect(builder(&fs.connect_string())).await;
        let inner = c.clone();
        let (get, changed, data) = tokio::task::spawn_blocking(move || {
            let get = inner.get_data("/tb").wait().map(|(d, _)| d);
            let changed = inner.state_watcher().wait_changed().map(|i| i.state);
            let data = tokio::runtime::Handle::current()
                .block_on(inner.get_data("/tb"))
                .map(|(d, _)| d);
            (get, changed, data)
        })
        .await
        .unwrap();
        assert_eq!(get, Err(Error::InvalidState));
        assert_eq!(changed, Err(Error::InvalidState));
        assert_eq!(data, Ok(b"v".to_vec()));
        within(c.close()).await.unwrap();
    }

    /// Confirms that `apache_zookeeper::blocking::Client` executes safely inside Tokio
    /// `spawn_blocking` contexts.
    #[cfg(feature = "tokio")]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_blocking_facade_works_in_spawn_blocking() {
        let fs = common::fake();
        let cs = fs.connect_string();
        let r = tokio::task::spawn_blocking(move || {
            let c = apache_zookeeper::blocking::Client::connect(builder(&cs)).unwrap();
            c.create("/fb", b"x", CreateMode::Persistent, OPEN_ACL_UNSAFE)
                .unwrap();
            let data = c.get_data("/fb").unwrap().0;
            c.as_async().close().wait().unwrap();
            data
        })
        .await
        .unwrap();
        assert_eq!(r, b"x");
    }
}
