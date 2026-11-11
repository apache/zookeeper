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

//! Integration tests verifying Client operations against an active ZooKeeper server instance.
//!
//! These tests validate end-to-end protocol interactions against a real ZooKeeper deployment.
//! Each test is attributed with `#[ignore = "real-server"]` and resolves its target port via
//! `common::real_port`. Execute with:
//! `ZK_TEST_PORT_BASE=<base> cargo test --test client_real -- --ignored`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::acl::{CREATOR_ALL_ACL, OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{
    AddWatchMode, Client, CreateMode, Error, EventKind, MultiError, MultiReader, MultiWriter,
    ReadResult, SessionId, SessionPassword, SessionState, Version, WatchError, WatcherKind,
};
use common::{SESSION_TIMEOUT, builder, fresh_server, within};

/// Establishes an asynchronous client session against the specified connection string.
async fn connect(cs: &str) -> Client {
    within(builder(cs).connect()).await.expect("connect")
}

#[tokio::test]
#[ignore = "real-server"]
async fn real_requests_round_trip() {
    let Some(srv) = fresh_server(0) else { return };
    let c = connect(&srv.connect_string()).await;
    assert_eq!(c.negotiated_timeout(), Some(SESSION_TIMEOUT));
    assert!(c.session().0 != SessionId(0));
    assert_eq!(c.connected_server().map(|a| a.port()), Some(srv.port()));

    let (p, stat) = within(c.create("/rt", b"one", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(p.as_str(), "/rt");
    assert_eq!(c.last_zxid(), stat.czxid);
    let (data, stat) = within(c.get_data("/rt")).await.unwrap();
    assert_eq!(data, b"one");
    let stat = within(c.set_data("/rt", b"two", stat.version))
        .await
        .unwrap();
    assert_eq!(stat.version, Version::new(1));
    assert_eq!(
        within(c.delete("/rt", Version::new(0))).await,
        Err(Error::BadVersion)
    );

    for _ in 0..3 {
        within(c.create(
            "/rt/s-",
            b"",
            CreateMode::PersistentSequential,
            OPEN_ACL_UNSAFE,
        ))
        .await
        .unwrap();
    }
    within(c.create("/rt/e", b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let mut names = within(c.get_children("/rt")).await.unwrap();
    names.sort();
    assert_eq!(
        names,
        vec![
            "e".to_owned(),
            "s-0000000000".to_owned(),
            "s-0000000001".to_owned(),
            "s-0000000002".to_owned()
        ]
    );
    let (_, stat) = within(c.get_children_with_stat("/rt")).await.unwrap();
    assert_eq!(stat.num_children, 4);
    assert_eq!(within(c.get_all_children_number("/rt")).await, Ok(4));
    let eph = within(c.get_ephemerals("/rt")).await.unwrap();
    assert_eq!(
        eph.iter().map(|p| p.as_str()).collect::<Vec<_>>(),
        vec!["/rt/e"]
    );
    assert_eq!(within(c.get_ephemerals_all()).await.unwrap().len(), 1);
    within(c.sync("/rt")).await.unwrap();
    assert_eq!(within(c.exists("/none")).await, Ok(None));
    assert!(matches!(
        within(c.get_data("bad")).await,
        Err(Error::InvalidPath(_))
    ));

    let ids = within(c.who_am_i()).await.unwrap();
    assert!(ids.iter().any(|i| i.auth_scheme == "ip"), "{ids:?}");
    within(c.get_config()).await.unwrap();

    // Verifies that a client mounted at a chroot path accesses the subtree rooted at /rt.
    let d = connect(&format!("{}/rt", srv.connect_string())).await;
    assert!(within(d.exists("/e")).await.unwrap().is_some());

    within(c.close()).await.unwrap();
    assert_eq!(c.state().state, SessionState::Closed);
    assert_eq!(within(d.exists("/e")).await, Ok(None));
    within(d.close()).await.unwrap();
}

#[tokio::test]
#[ignore = "real-server"]
async fn real_transactions() {
    let Some(srv) = fresh_server(1) else { return };
    let c = connect(&srv.connect_string()).await;
    let mut txn = MultiWriter::new();
    let made = txn
        .create("/tx", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let child = txn
        .create("/tx/c", b"v", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let set = txn.set_data("/tx", b"top", Version::new(0)).unwrap();
    let results = within(c.multi(&txn)).await.unwrap();
    assert_eq!(results.get(made).unwrap().path.as_str(), "/tx");
    assert_eq!(results.get(child).unwrap().path.as_str(), "/tx/c");
    assert_eq!(results.get(set).unwrap().version, Version::new(1));

    let mut bad = MultiWriter::new();
    bad.delete("/tx/c", Version::ANY).unwrap();
    bad.check("/tx", Version::new(9)).unwrap();
    match within(c.multi(&bad)).await {
        Err(MultiError::Aborted { index, error, .. }) => {
            assert_eq!((index, error), (1, Error::BadVersion));
        }
        other => panic!("expected an abort: {other:?}"),
    }
    assert!(within(c.exists("/tx/c")).await.unwrap().is_some());

    let mut reads = MultiReader::new();
    reads.get_data("/tx/c").unwrap();
    reads.get_children("/tx").unwrap();
    reads.get_data("/tx/none").unwrap();
    let out = within(c.multi_read(&reads)).await.unwrap();
    assert!(matches!(out.first(), Some(Ok(ReadResult::Data { data, .. })) if data == b"v"));
    assert!(matches!(out.get(1), Some(Ok(ReadResult::Children(n))) if n == &vec!["c".to_owned()]));
    assert_eq!(out.get(2), Some(&Err(Error::NoNode)));
    within(c.close()).await.unwrap();
}

#[tokio::test]
#[ignore = "real-server"]
async fn real_watches() {
    let Some(srv) = fresh_server(2) else { return };
    let c = connect(&srv.connect_string()).await;
    let writer = connect(&srv.connect_string()).await;

    let (stat, w) = within(c.exists_watched("/w")).await.unwrap();
    assert!(stat.is_none());
    within(writer.create("/w", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(within(w).await.unwrap().kind, EventKind::NodeCreated);

    let (_, _, w) = within(c.get_data_watched("/w")).await.unwrap();
    within(writer.set_data("/w", b"x", None)).await.unwrap();
    let ev = within(w).await.unwrap();
    assert_eq!(
        (ev.kind, ev.path.as_str()),
        (EventKind::NodeDataChanged, "/w")
    );

    let (_, _, w) = within(c.get_children_watched("/w")).await.unwrap();
    within(writer.create("/w/c", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(
        within(w).await.unwrap().kind,
        EventKind::NodeChildrenChanged
    );

    let mut pw = within(c.add_watch("/w", AddWatchMode::PersistentRecursive))
        .await
        .unwrap();
    within(writer.set_data("/w/c", b"1", None)).await.unwrap();
    within(writer.delete("/w/c", None)).await.unwrap();
    let a = within(pw.next()).await.unwrap().unwrap();
    let b = within(pw.next()).await.unwrap().unwrap();
    assert_eq!(
        (a.kind, a.path.as_str()),
        (EventKind::NodeDataChanged, "/w/c")
    );
    assert_eq!((b.kind, b.path.as_str()), (EventKind::NodeDeleted, "/w/c"));
    within(c.remove_watches("/w", WatcherKind::PersistentRecursive, false))
        .await
        .unwrap();
    assert!(matches!(
        within(pw.next()).await,
        Some(Err(WatchError::Removed)) | None
    ));
    assert_eq!(
        within(c.remove_watches("/w", WatcherKind::Data, false)).await,
        Err(Error::NoWatcher)
    );
    within(c.close()).await.unwrap();
    within(writer.close()).await.unwrap();
}

#[tokio::test]
#[ignore = "real-server"]
async fn real_auth_and_acl() {
    let Some(srv) = fresh_server(3) else { return };
    let owner = within(
        builder(&srv.connect_string())
            .auth("digest", b"alice:secret".to_vec())
            .connect(),
    )
    .await
    .unwrap();
    within(owner.create("/secure", b"s", CreateMode::Persistent, CREATOR_ALL_ACL))
        .await
        .unwrap();
    let (acl, _) = within(owner.get_acl("/secure")).await.unwrap();
    assert_eq!(acl.len(), 1);
    let ids = within(owner.who_am_i()).await.unwrap();
    assert!(
        ids.iter()
            .any(|i| i.auth_scheme == "digest" && i.user == "alice"),
        "{ids:?}"
    );

    let other = connect(&srv.connect_string()).await;
    assert_eq!(
        within(other.get_data("/secure")).await.err(),
        Some(Error::NoAuth)
    );
    within(other.add_auth("digest", b"alice:secret".to_vec()))
        .await
        .unwrap();
    let (data, _) = within(other.get_data("/secure")).await.unwrap();
    assert_eq!(data, b"s");
    // Validate that modifying ACLs returns updated metadata.
    let _ = within(other.set_acl("/secure", OPEN_ACL_UNSAFE, Version::new(0))).await;
    let (acl, stat) = within(owner.get_acl("/secure")).await.unwrap();
    assert_eq!(acl, OPEN_ACL_UNSAFE.to_vec());
    assert_eq!(stat.aversion, Version::new(1));
    within(owner.close()).await.unwrap();
    within(other.close()).await.unwrap();
}

#[tokio::test]
#[ignore = "real-server"]
async fn real_detach_resume_and_expiry() {
    let Some(srv) = fresh_server(4) else { return };
    let c = connect(&srv.connect_string()).await;
    within(c.create("/held", b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (id, pw) = c.detach();
    assert!(id != SessionId(0));

    let d = within(builder(&srv.connect_string()).session(id, pw).connect())
        .await
        .unwrap();
    assert_eq!(d.session().0, id);
    assert!(within(d.exists("/held")).await.unwrap().is_some());
    let (_, old_pw) = d.session();
    within(d.close()).await.unwrap();

    // Explicitly closed sessions cannot be reconnected; attempts must fail with SessionExpired.
    let e = within(builder(&srv.connect_string()).session(id, old_pw).connect())
        .await
        .unwrap_err();
    assert_eq!(e.error(), &Error::SessionExpired);

    let e = within(
        builder(&srv.connect_string())
            .session(SessionId(0x0123_4567), SessionPassword::from_bytes([1; 16]))
            .connect(),
    )
    .await
    .unwrap_err();
    assert_eq!(e.error(), &Error::SessionExpired);

    let check = connect(&srv.connect_string()).await;
    assert_eq!(within(check.exists("/held")).await, Ok(None));
    within(check.close()).await.unwrap();
}

#[tokio::test]
#[ignore = "real-server"]
async fn real_set_acl_gives_the_new_stat() {
    let Some(srv) = fresh_server(6) else { return };
    let c = connect(&srv.connect_string()).await;
    within(c.create("/sa", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let stat = within(c.set_acl("/sa", READ_ACL_UNSAFE, None)).await;
    within(c.close()).await.unwrap();
    assert_eq!(stat.unwrap().aversion, Version::new(1));
}

#[cfg(feature = "blocking")]
#[test]
#[ignore = "real-server"]
fn real_blocking_facade() {
    let Some(srv) = fresh_server(5) else { return };
    let c = builder(&srv.connect_string()).connect().wait().unwrap();
    c.create("/b", b"v", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .wait()
        .unwrap();
    let (data, _) = c.get_data("/b").wait().unwrap();
    assert_eq!(data, b"v");
    c.close().wait().unwrap();
}
