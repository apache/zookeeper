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

//! Evaluates protocol serialization, deserialization, and transaction encoding contracts
//! across all ZooKeeper operations against synthetic mock server fixtures (`zk_fakeserver`).
//!
//! Validates create mode bitflags, ACL serialization, stat decoding, empty/null byte
//! representations, transactional multi-operation encoding (`MultiWriter`), atomic multi-read
//! operations (`MultiReader`), and namespace chroot prefix stripping.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::acl::{OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{
    Acl, Client, CreateMode, EphemeralKind, Error, Id, MultiError, MultiReader, MultiWriter,
    OpResult, Perms, ReadResult, Ttl, Version,
};
use common::WAIT;
use zk_fakeserver::{Action, Detail, FakeServer, FrameRecord, Rule};
use zk_jute::{JBuffer, Record, opcode, rc};

/// Establishes an asynchronous client session against the mock server fixture.
async fn connect(fake: &FakeServer) -> Client {
    common::builder(&fake.connect_string())
        .connect()
        .await
        .expect("connect")
}

/// Captures the initial request protocol frame matching opcode `op` on target `path`.
fn frame(fake: &FakeServer, op: i32, path: &str) -> FrameRecord {
    fake.wait_for(WAIT, |r| {
        r.op == Some(op) && r.path.as_deref() == Some(path)
    })
    .unwrap_or_else(|| panic!("no frame op {op} on {path}"))
}

/// Extracts mode bitflags and TTL metadata from a captured create frame record.
fn create_detail(r: &FrameRecord) -> (i32, Option<i64>) {
    match &r.detail {
        Detail::Create { flags, ttl, .. } => (*flags, *ttl),
        other => panic!("not a create: {other:?}"),
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn create_sends_the_java_mode_flags() {
    let fake = common::fake();
    let c = connect(&fake).await;
    let ttl = Ttl::from_millis(60_000).unwrap();
    let cases = [
        ("/p", CreateMode::Persistent, opcode::CREATE2, 0, None),
        ("/e", CreateMode::Ephemeral, opcode::CREATE2, 1, None),
        (
            "/ps",
            CreateMode::PersistentSequential,
            opcode::CREATE2,
            2,
            None,
        ),
        (
            "/es",
            CreateMode::EphemeralSequential,
            opcode::CREATE2,
            3,
            None,
        ),
        (
            "/c",
            CreateMode::Container,
            opcode::CREATE_CONTAINER,
            4,
            None,
        ),
        (
            "/t",
            CreateMode::PersistentWithTtl(ttl),
            opcode::CREATE_TTL,
            5,
            Some(60_000),
        ),
        (
            "/ts",
            CreateMode::PersistentSequentialWithTtl(ttl),
            opcode::CREATE_TTL,
            6,
            Some(60_000),
        ),
    ];
    for (path, mode, op, flags, wire_ttl) in cases {
        let (created, stat) = c
            .create(path, b"x", mode, OPEN_ACL_UNSAFE)
            .await
            .unwrap_or_else(|e| panic!("create {path}: {e:?}"));
        if mode.is_sequential() {
            assert!(created.as_str().starts_with(path), "{created}");
            assert_eq!(created.as_str().len(), path.len() + 10, "{created}");
        } else {
            assert_eq!(created, path);
        }
        // All create operations return populated stat structures from the server response.
        assert_ne!(stat.czxid.0, 0, "{path}");
        assert_eq!(stat.data_length, 1, "{path}");
        let kind = stat.ephemeral_kind();
        match mode {
            CreateMode::Ephemeral | CreateMode::EphemeralSequential => {
                assert!(
                    matches!(kind, EphemeralKind::Session(_)),
                    "{path}: {kind:?}"
                );
            }
            CreateMode::Container => assert_eq!(kind, EphemeralKind::Container),
            CreateMode::PersistentWithTtl(_) | CreateMode::PersistentSequentialWithTtl(_) => {
                assert_eq!(kind, EphemeralKind::Ttl(ttl.as_duration()));
            }
            _ => assert_eq!(kind, EphemeralKind::None, "{path}"),
        }
        let r = frame(&fake, op, path);
        assert_eq!(create_detail(&r), (flags, wire_ttl), "{path}");
    }
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn create_and_write_errors_map_to_codes() {
    let fake = common::fake();
    let c = connect(&fake).await;
    c.create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    assert_eq!(
        c.create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .await,
        Err(Error::NodeExists)
    );
    assert_eq!(
        c.create("/missing/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .await,
        Err(Error::NoNode)
    );
    assert_eq!(c.delete("/missing", None).await, Err(Error::NoNode));
    assert_eq!(
        c.set_data("/a", b"v", Version::new(7)).await,
        Err(Error::BadVersion)
    );
    assert_eq!(c.get_data("/missing").await, Err(Error::NoNode));
    assert_eq!(c.get_acl("/missing").await, Err(Error::NoNode));
    assert_eq!(c.get_children("/missing").await, Err(Error::NoNode));
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn reads_decode_their_replies() {
    let fake = common::fake();
    let c = connect(&fake).await;
    assert_eq!(c.exists("/n").await, Ok(None));
    c.create("/n", b"one", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    let st = c.exists("/n").await.unwrap().expect("node exists");
    assert_eq!(st.version, Version::new(0));
    let (data, st) = c.get_data("/n").await.unwrap();
    assert_eq!(data, b"one");
    assert_eq!(st.data_length, 3);
    let st = c.set_data("/n", b"two", st.version).await.unwrap();
    assert_eq!(st.version, Version::new(1));
    let (data, _) = c.get_data_opt("/n").await.unwrap();
    assert_eq!(data.as_deref(), Some(&b"two"[..]));
    for child in ["/n/b", "/n/a", "/n/a/x"] {
        c.create(child, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .await
            .unwrap();
    }
    let mut names = c.get_children("/n").await.unwrap();
    names.sort();
    assert_eq!(names, ["a", "b"]);
    let (mut names, st) = c.get_children_with_stat("/n").await.unwrap();
    names.sort();
    assert_eq!(names, ["a", "b"]);
    assert_eq!(st.num_children, 2);
    assert_eq!(c.get_all_children_number("/n").await, Ok(3));
    c.delete("/n/a/x", None).await.unwrap();
    c.delete("/n/a", Version::new(0)).await.unwrap();
    c.sync("/n").await.unwrap();
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn null_data_reads_as_empty_or_none() {
    let fake = common::fake();
    let c = connect(&fake).await;
    c.create("/null", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    // Simulates wire payload containing negative buffer length (-1) representing null znode data.
    let stat = fake.cluster().node("/null").unwrap().stat;
    let body = zk_jute::proto::GetDataResponse {
        data: JBuffer::Null,
        stat,
    }
    .to_bytes();
    fake.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .path("/null")
            .then(Action::ReplyWith { err: 0, body }),
    );
    let (data, _) = c.get_data("/null").await.unwrap();
    assert!(data.is_empty());
    let (data, _) = c.get_data_opt("/null").await.unwrap();
    assert_eq!(data, None);
    fake.clear_rules();
    // Preserves empty non-null data payload as `Some(vec![])`.
    let (data, _) = c.get_data_opt("/null").await.unwrap();
    assert_eq!(data, Some(Vec::new()));
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn acl_round_trip() {
    let fake = common::fake();
    let c = connect(&fake).await;
    c.create("/acl", b"", CreateMode::Persistent, READ_ACL_UNSAFE)
        .await
        .unwrap();
    let (acl, st) = c.get_acl("/acl").await.unwrap();
    assert_eq!(acl, READ_ACL_UNSAFE);
    assert_eq!(st.aversion, Version::new(0));
    c.delete("/acl", None).await.unwrap();

    c.create("/acl2", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    let list = vec![
        Acl {
            perms: Perms::READ | Perms::ADMIN,
            id: Id::ANYONE,
        },
        Acl {
            perms: Perms::ALL,
            id: Id::new("digest", zk_fakeserver::digest_id("u:p")),
        },
    ];
    let st = c.set_acl("/acl2", &list, Version::new(0)).await.unwrap();
    assert_eq!(st.aversion, Version::new(1));
    let (got, st) = c.get_acl("/acl2").await.unwrap();
    assert_eq!(got, list);
    assert_eq!(st.aversion, Version::new(1));
    let r = frame(&fake, opcode::SET_ACL, "/acl2");
    assert_eq!(r.detail, Detail::Version(0));
    assert_eq!(
        c.set_acl("/acl2", OPEN_ACL_UNSAFE, Version::new(0)).await,
        Err(Error::BadVersion)
    );
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn ephemerals_and_who_am_i() {
    let fake = common::fake();
    let c = connect(&fake).await;
    c.create("/app", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    for p in ["/app/e1", "/e2"] {
        c.create(p, b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE)
            .await
            .unwrap();
    }
    let mut all = c.get_ephemerals_all().await.unwrap();
    all.sort();
    assert_eq!(all, ["/app/e1", "/e2"]);
    let under = c.get_ephemerals("/app").await.unwrap();
    assert_eq!(under, ["/app/e1"]);

    c.add_auth("digest", b"u:p".to_vec()).await.unwrap();
    let ids = c.who_am_i().await.unwrap();
    assert!(
        ids.iter()
            .any(|i| i.auth_scheme == "digest" && i.user == "u"),
        "{ids:?}"
    );
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn chroot_is_stripped_from_reply_paths() {
    let fake = common::fake();
    fake.cluster().create("/root", b"", 0).unwrap();
    let c = common::builder(&format!("{}/root", fake.connect_string()))
        .connect()
        .await
        .expect("connect");
    let (p, _) = c
        .create("/s-", b"", CreateMode::EphemeralSequential, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    assert!(p.as_str().starts_with("/s-"), "{p}");
    assert!(fake.cluster().node(&format!("/root{p}")).is_some());
    assert_eq!(c.get_ephemerals_all().await.unwrap(), [p]);
    let mut txn = MultiWriter::new();
    let h = txn
        .create("/m", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let res = c.multi(&txn).await.unwrap();
    assert_eq!(res.get(h).unwrap().path, "/m");
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn get_config_parses_the_config_node() {
    let fake = common::fake();
    let text = "server.1=127.0.0.1:2888:3888:participant;0.0.0.0:2181\nversion=100000000";
    fake.cluster().set_config_data(text);
    let c = connect(&fake).await;
    let (cfg, _) = c.get_config().await.unwrap();
    assert_eq!(cfg.raw, text);
    assert_eq!(cfg.version, Some(0x1_0000_0000));
    assert_eq!(cfg.servers.len(), 1);
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn multi_commits_with_typed_results() {
    let fake = common::fake();
    let c = connect(&fake).await;
    c.create("/old", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    let ttl = Ttl::from_millis(60_000).unwrap();
    let mut txn = MultiWriter::new();
    let a = txn
        .create("/a", b"1", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let q = txn
        .create(
            "/q-",
            b"",
            CreateMode::PersistentSequential,
            OPEN_ACL_UNSAFE,
        )
        .unwrap();
    let t = txn
        .create(
            "/t",
            b"",
            CreateMode::PersistentWithTtl(ttl),
            OPEN_ACL_UNSAFE,
        )
        .unwrap();
    let k = txn
        .create("/k", b"", CreateMode::Container, OPEN_ACL_UNSAFE)
        .unwrap();
    let s = txn.set_data("/a", b"2", Version::new(0)).unwrap();
    let chk = txn.check("/a", Version::new(1)).unwrap();
    let d = txn.delete("/old", Version::ANY).unwrap();
    let res = c.multi(&txn).await.unwrap();
    assert_eq!(res.get(a).unwrap().path, "/a");
    assert!(res.get(q).unwrap().path.as_str().starts_with("/q-"));
    assert_eq!(
        res.get(t).unwrap().stat.ephemeral_kind(),
        EphemeralKind::Ttl(ttl.as_duration())
    );
    assert_eq!(
        res.get(k).unwrap().stat.ephemeral_kind(),
        EphemeralKind::Container
    );
    assert_eq!(res.get(s).unwrap().version, Version::new(1));
    assert!(res.get(chk).is_some());
    assert!(res.get(d).is_some());
    assert_eq!(res.iter().nth(6), Some(&OpResult::Delete));
    // Asserts corresponding sub-operation wire opcodes inside the batched request frame.
    let r = fake
        .wait_for(WAIT, |r| r.op == Some(opcode::MULTI))
        .unwrap();
    match r.detail {
        Detail::Multi(ops) => {
            let types: Vec<i32> = ops.iter().map(|(t, _)| *t).collect();
            assert_eq!(
                types,
                [
                    opcode::CREATE2,
                    opcode::CREATE2,
                    opcode::CREATE_TTL,
                    opcode::CREATE_CONTAINER,
                    opcode::SET_DATA,
                    opcode::CHECK,
                    opcode::DELETE
                ]
            );
        }
        other => panic!("not a multi: {other:?}"),
    }
    assert!(fake.cluster().node("/old").is_none());
    // Operations handles scoped to unrelated transactions yield `None`.
    let mut other = MultiWriter::new();
    let oa = other
        .create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    assert!(res.get(oa).is_none());
    // Empty transaction batches transmit valid wire frames and complete successfully.
    let empty = c.multi(&MultiWriter::new()).await.unwrap();
    assert_eq!(empty.iter().count(), 0);
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn multi_rolls_back_on_a_failing_op_in_the_middle() {
    let fake = common::fake();
    let c = connect(&fake).await;
    let mut txn = MultiWriter::new();
    txn.create("/m1", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.check("/missing", Version::ANY).unwrap();
    txn.create("/m2", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let err = c.multi(&txn).await.unwrap_err();
    assert_eq!(
        err,
        MultiError::Aborted {
            index: 1,
            error: Error::NoNode,
            per_op: vec![None, Some(Error::NoNode), Some(Error::RuntimeInconsistency)],
        }
    );
    assert_eq!(Error::from(err), Error::NoNode);
    assert!(fake.cluster().node("/m1").is_none());
    assert!(fake.cluster().node("/m2").is_none());

    // Asserts atomic rollback when version check failure occurs on the terminal sub-operation.
    c.create("/v", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    let mut txn = MultiWriter::new();
    txn.set_data("/v", b"x", Version::ANY).unwrap();
    txn.set_data("/v", b"y", Version::new(0)).unwrap();
    match c.multi(&txn).await {
        Err(MultiError::Aborted {
            index,
            error,
            per_op,
        }) => {
            assert_eq!(index, 1);
            assert_eq!(error, Error::BadVersion);
            assert_eq!(per_op, vec![None, Some(Error::BadVersion)]);
        }
        other => panic!("not aborted: {other:?}"),
    }
    assert_eq!(c.get_data("/v").await.unwrap().0, b"");
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn multi_header_only_error_is_a_request_error() {
    let fake = common::fake();
    let c = connect(&fake).await;
    fake.add_rule(
        Rule::on_request()
            .op(opcode::MULTI)
            .once()
            .then(Action::Reply {
                err: rc::ZNOTREADONLY,
            }),
    );
    let mut txn = MultiWriter::new();
    txn.create("/ro", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    assert_eq!(
        c.multi(&txn).await,
        Err(MultiError::Request(Error::NotReadOnly))
    );
    assert!(fake.cluster().node("/ro").is_none());
    c.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn multi_read_gives_mixed_results() {
    let fake = common::fake();
    let c = connect(&fake).await;
    c.create("/r", b"data", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    c.create("/r/c", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    let mut reads = MultiReader::new();
    assert_eq!(reads.get_data("/r").unwrap(), 0);
    assert_eq!(reads.get_data("/missing").unwrap(), 1);
    assert_eq!(reads.get_children("/r").unwrap(), 2);
    assert_eq!(reads.get_children("/missing").unwrap(), 3);
    let got = c.multi_read(&reads).await.unwrap();
    assert_eq!(got.len(), 4);
    match got.first().unwrap() {
        Ok(ReadResult::Data { data, stat }) => {
            assert_eq!(data, b"data");
            assert_eq!(stat.num_children, 1);
        }
        other => panic!("not data: {other:?}"),
    }
    assert_eq!(got.get(1).unwrap(), &Err(Error::NoNode));
    assert_eq!(
        got.get(2).unwrap(),
        &Ok(ReadResult::Children(vec!["c".to_owned()]))
    );
    assert_eq!(got.get(3).unwrap(), &Err(Error::NoNode));
    let r = fake
        .wait_for(WAIT, |r| r.op == Some(opcode::MULTI_READ))
        .unwrap();
    match r.detail {
        Detail::Multi(ops) => assert_eq!(
            ops,
            vec![
                (opcode::GET_DATA, Some("/r".to_owned())),
                (opcode::GET_DATA, Some("/missing".to_owned())),
                (opcode::GET_CHILDREN, Some("/r".to_owned())),
                (opcode::GET_CHILDREN, Some("/missing".to_owned())),
            ]
        ),
        other => panic!("not a multi read: {other:?}"),
    }
    c.close().await.unwrap();
}
