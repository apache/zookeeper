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

//! End-to-end integration tests validating ZooKeeper client data and session operations.
//!
//! Exercises standard CRUD, create modes, optimistic concurrency versioning, ACL permissions,
//! ZooKeeper 3.6+ inspection primitives, atomic transactions, chroot namespace scoping,
//! pipeline submission ordering, and synchronous blocking wait semantics.
//!
//! All tests share a persistent real server instance running at port offset 10, using isolated
//! root paths per scenario. Each scenario executes across both OS thread and Tokio task drivers.
//! Run with: `ZK_TEST_PORT_BASE=<base> cargo test --test real_ops -- --ignored`.

// Test scenarios execute extended asynchronous flows on dedicated worker threads;
// stack size limits configured for the core library do not apply.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::acl::{CREATOR_ALL_ACL, OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{
    Acl, CreateMode, EphemeralKind, Error, Id, MultiError, MultiReader, MultiWriter, OpResult,
    Perms, ReadResult, Reconfig, SessionState, Ttl, Version,
};
use common::{Ctx, Rt, SharedServer, setup, within};

/// Shared server instance backing all operation tests in this module.
static SERVER: SharedServer = SharedServer::new(10);

/// Instantiates and executes a test scenario across both OS thread and Tokio drivers on a
/// multithreaded runtime.
macro_rules! real_test {
    ($name:ident) => {
        mod $name {
            use super::*;

            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn thread() {
                if let Some(ctx) = Ctx::new(&SERVER, stringify!($name), Rt::Thread) {
                    super::$name(ctx).await;
                }
            }

            #[cfg(all(unix, feature = "tokio"))]
            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn tokio() {
                if let Some(ctx) = Ctx::new(&SERVER, stringify!($name), Rt::Tokio) {
                    super::$name(ctx).await;
                }
            }
        }
    };
}

// ---- CRUD and versions ----

real_test!(crud_and_versions);
/// Invariant: operations return strongly typed responses matching the operation variant,
/// while optimistic concurrency conflicts and existence violations return corresponding typed
/// errors.
async fn crud_and_versions(ctx: Ctx) {
    let c = setup(&ctx).await;
    let n = ctx.path("/n");
    let (p, st) = within(c.create(&n, b"a", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(p.as_str(), n);
    assert_eq!((st.version, st.data_length), (Version::new(0), 1));
    assert_eq!(st.ephemeral_kind(), EphemeralKind::None);
    assert!(c.last_zxid() >= st.czxid);

    // Existence conflicts.
    let again = c.create(&n, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
    assert_eq!(within(again).await.unwrap_err(), Error::NodeExists);
    let orphan = ctx.path("/none/child");
    let orphan = c.create(&orphan, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
    assert_eq!(within(orphan).await.unwrap_err(), Error::NoNode);

    // Reads.
    let (data, st2) = within(c.get_data(&n)).await.unwrap();
    assert_eq!((data.as_slice(), st2), (&b"a"[..], st));
    assert_eq!(
        within(c.get_data_opt(&n)).await.unwrap().0,
        Some(b"a".to_vec())
    );
    assert_eq!(within(c.exists(&n)).await.unwrap(), Some(st));
    assert_eq!(within(c.exists(ctx.path("/none"))).await, Ok(None));
    assert_eq!(
        within(c.get_data(ctx.path("/none"))).await.unwrap_err(),
        Error::NoNode
    );

    // Versions: a wrong version, an exact version, `None` and `ANY`.
    let bad = c.set_data(&n, b"x", Version::new(5));
    assert_eq!(within(bad).await.unwrap_err(), Error::BadVersion);
    let st = within(c.set_data(&n, b"b", Some(Version::new(0))))
        .await
        .unwrap();
    assert_eq!(st.version, Version::new(1));
    let st = within(c.set_data(&n, b"c", None)).await.unwrap();
    assert_eq!(st.version, Version::new(2));
    let st = within(c.set_data(&n, b"", Version::ANY)).await.unwrap();
    assert_eq!((st.version, st.data_length), (Version::new(3), 0));
    // The native client writes empty byte sequences rather than null payloads.
    assert_eq!(
        within(c.get_data_opt(&n)).await.unwrap().0,
        Some(Vec::new())
    );
    let missing = c.set_data(ctx.path("/none"), b"", None);
    assert_eq!(within(missing).await.unwrap_err(), Error::NoNode);

    // Deletes: NotEmpty, BadVersion, NoNode, then success.
    let child = ctx.path("/n/c");
    within(c.create(&child, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(within(c.delete(&n, None)).await, Err(Error::NotEmpty));
    assert_eq!(
        within(c.delete(&child, Version::new(3))).await,
        Err(Error::BadVersion)
    );
    within(c.delete(&child, Version::new(0))).await.unwrap();
    assert_eq!(within(c.delete(&child, None)).await, Err(Error::NoNode));
    within(c.delete(&n, Version::new(3))).await.unwrap();
    assert_eq!(within(c.exists(&n)).await, Ok(None));
    within(c.close()).await.unwrap();
}

real_test!(bad_paths_send_nothing);
/// Invariant: path syntax validation is evaluated synchronously prior to request submission;
/// invalid path representations yield an immediate `Error::InvalidPath` without generating network
/// traffic.
async fn bad_paths_send_nothing(ctx: Ctx) {
    let c = ctx.connect().await;
    for bad in ["", "rel", "/a/", "/a//b", "/a/./b", "/a/..", "/a\u{0}b"] {
        let f = c.get_data(bad);
        assert!(f.is_ready(), "{bad:?}");
        assert!(matches!(f.await, Err(Error::InvalidPath(_))), "{bad:?}");
    }
    // Sequential node creation evaluates path validation against the prospective name prefix.
    let f = c.create(
        ctx.path("/"),
        b"",
        CreateMode::PersistentSequential,
        OPEN_ACL_UNSAFE,
    );
    assert_eq!(within(f).await.unwrap_err(), Error::NoNode);
    assert_eq!(c.stats().pending, 0);
    within(c.close()).await.unwrap();
}

// ---- create modes ----

real_test!(create_modes);
/// Invariant: each `CreateMode` variant produces its corresponding server side znode
/// representation, correctly reflected in the node's returned `Stat` metadata.
async fn create_modes(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (id, _) = c.session();

    let (_, st) = within(c.create(ctx.path("/e"), b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(st.ephemeral_kind(), EphemeralKind::Session(id));
    assert_eq!(st.ephemeral_owner, id.0);
    let under = c.create(
        ctx.path("/e/c"),
        b"",
        CreateMode::Persistent,
        OPEN_ACL_UNSAFE,
    );
    assert_eq!(
        within(under).await.unwrap_err(),
        Error::NoChildrenForEphemerals
    );

    // Sequential node names append a monotonically increasing 10-digit counter derived from parent
    // cversion.
    let mut seq = Vec::new();
    for mode in [
        CreateMode::PersistentSequential,
        CreateMode::EphemeralSequential,
        CreateMode::PersistentSequentialWithTtl(Ttl::from_millis(60_000).unwrap()),
    ] {
        let (p, _) = within(c.create(ctx.path("/s-"), b"", mode, OPEN_ACL_UNSAFE))
            .await
            .unwrap();
        seq.push(p.into_string());
    }
    for (i, p) in seq.iter().enumerate() {
        let suffix = p.strip_prefix(&ctx.path("/s-")).unwrap();
        assert_eq!(suffix.len(), 10, "{p}");
        assert!(suffix.parse::<u32>().is_ok(), "{p}");
        if let Some(prev) = i.checked_sub(1).and_then(|j| seq.get(j)) {
            assert!(p > prev, "{p} after {prev}");
        }
    }

    let (_, st) = within(c.create(ctx.path("/k"), b"", CreateMode::Container, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    // The server reports owner 0 for non-session ephemeral abstractions such as containers and TTL
    // nodes.
    assert_eq!(st.ephemeral_kind(), EphemeralKind::None);
    let ttl = CreateMode::PersistentWithTtl(Ttl::new(std::time::Duration::from_secs(90)).unwrap());
    let (_, st) = within(c.create(ctx.path("/t"), b"", ttl, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(st.ephemeral_kind(), EphemeralKind::None);
    assert!(Ttl::from_millis(0).is_err());

    // Query active ephemeral nodes associated with this session under the specified root path
    // prefix.
    let mut eph: Vec<String> = within(c.get_ephemerals(&ctx.root))
        .await
        .unwrap()
        .into_iter()
        .map(|p| p.into_string())
        .collect();
    eph.sort();
    let want = vec![ctx.path("/e"), seq.get(1).unwrap().clone()];
    assert_eq!(eph, want);
    let all = within(c.get_ephemerals_all()).await.unwrap();
    assert!(all.iter().any(|p| p.as_str() == ctx.path("/e")), "{all:?}");

    // Ephemeral nodes are cleaned up automatically upon session termination.
    let other = ctx.connect().await;
    within(c.close()).await.unwrap();
    assert_eq!(within(other.exists(ctx.path("/e"))).await, Ok(None));
    assert!(
        within(other.exists(seq.first().unwrap()))
            .await
            .unwrap()
            .is_some()
    );
    within(other.close()).await.unwrap();
}

real_test!(children_and_counts);
/// Invariant: `get_children` returns child node names, `get_children_with_stat` includes parent
/// `Stat` metadata, and `get_all_children_number` recursively counts all descendant znodes.
async fn children_and_counts(ctx: Ctx) {
    let c = setup(&ctx).await;
    for rel in ["/a", "/b", "/a/x", "/a/y", "/a/x/z"] {
        within(c.create(ctx.path(rel), b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
            .await
            .unwrap();
    }
    let mut names = within(c.get_children(&ctx.root)).await.unwrap();
    names.sort();
    assert_eq!(names, vec!["a".to_owned(), "b".to_owned()]);
    let (mut names, st) = within(c.get_children_with_stat(ctx.path("/a")))
        .await
        .unwrap();
    names.sort();
    assert_eq!(names, vec!["x".to_owned(), "y".to_owned()]);
    assert_eq!((st.num_children, st.cversion), (2, Version::new(2)));
    assert_eq!(within(c.get_all_children_number(&ctx.root)).await, Ok(5));
    assert_eq!(
        within(c.get_all_children_number(ctx.path("/b"))).await,
        Ok(0)
    );
    assert_eq!(
        within(c.get_children(ctx.path("/none"))).await,
        Err(Error::NoNode)
    );
    within(c.close()).await.unwrap();
}

// ---- ACLs and auth ----

real_test!(acls_and_digest_auth);
/// Invariant: access control lists restrict znode access based on authenticated identities,
/// credentials added via `add_auth` take effect strictly in pipeline submission order,
/// and `set_acl` enforces optimistic concurrency checks against the current ACL version.
async fn acls_and_digest_auth(ctx: Ctx) {
    let owner = setup(&ctx).await;
    let secure = ctx.path("/secure");
    // Establishing an ACL requiring authenticated identity fails if no identity is attached.
    let f = owner.create(&secure, b"s", CreateMode::Persistent, CREATOR_ALL_ACL);
    assert_eq!(within(f).await.unwrap_err(), Error::InvalidAcl);
    within(owner.add_auth("digest", b"alice:pw".to_vec()))
        .await
        .unwrap();
    within(owner.create(&secure, b"s", CreateMode::Persistent, CREATOR_ALL_ACL))
        .await
        .unwrap();
    let (acl, st) = within(owner.get_acl(&secure)).await.unwrap();
    let alice = Acl {
        perms: Perms::ALL,
        id: Id::digest("alice", "pw"),
    };
    assert_eq!((acl, st.aversion), (vec![alice.clone()], Version::new(0)));
    let ids = within(owner.who_am_i()).await.unwrap();
    assert!(
        ids.iter()
            .any(|i| i.auth_scheme == "digest" && i.user == "alice"),
        "{ids:?}"
    );

    // Unauthenticated sessions lack permissions; the server enforces READ checks on existence
    // probes.
    let other = ctx.connect().await;
    assert_eq!(within(other.exists(&secure)).await, Err(Error::NoAuth));
    assert_eq!(within(other.get_data(&secure)).await, Err(Error::NoAuth));
    assert_eq!(
        within(other.set_data(&secure, b"x", None)).await,
        Err(Error::NoAuth)
    );
    // Incorrect credentials establish a distinct authenticated identity that lacks matching
    // permissions.
    within(other.add_auth("digest", b"alice:wrong".to_vec()))
        .await
        .unwrap();
    assert_eq!(within(other.get_data(&secure)).await, Err(Error::NoAuth));

    // `add_auth` is sequenced strictly in FIFO pipeline order relative to adjacent read requests.
    let before = other.get_data(&secure);
    let auth = other.add_auth("digest", b"alice:pw".to_vec());
    let after = other.get_data(&secure);
    assert_eq!(within(after).await.unwrap().0, b"s");
    assert_eq!(within(auth).await, Ok(()));
    assert_eq!(within(before).await, Err(Error::NoAuth));

    // `set_acl` validates the optimistic concurrency ACL version and returns updated Stat
    // metadata.
    let bad = other.set_acl(&secure, READ_ACL_UNSAFE, Version::new(3));
    assert_eq!(within(bad).await.unwrap_err(), Error::BadVersion);
    let st = within(other.set_acl(&secure, READ_ACL_UNSAFE, Version::new(0)))
        .await
        .unwrap();
    assert_eq!(st.aversion, Version::new(1));
    // Following ACL update, world read access is granted while write privileges remain
    // restricted.
    assert_eq!(
        within(owner.set_data(&secure, b"x", None)).await,
        Err(Error::NoAuth)
    );
    assert_eq!(
        within(owner.get_acl(&secure)).await.unwrap().0,
        READ_ACL_UNSAFE.to_vec()
    );
    within(owner.close()).await.unwrap();
    within(other.close()).await.unwrap();
}

real_test!(refused_auth_is_terminal);
/// Invariant: rejected authentication results in an immediate transition to the terminal
/// `AuthFailed` session state. Subsequent operations fail with `Error::AuthFailed`, and
/// active watchers receive notification.
async fn refused_auth_is_terminal(ctx: Ctx) {
    let e = within(ctx.builder().auth("nosuch", b"x".to_vec()).connect())
        .await
        .unwrap_err();
    assert_eq!(e.error(), &Error::AuthFailed);

    let c = ctx.connect().await;
    let (_, _, w) = within(c.get_children_watched("/")).await.unwrap();
    assert_eq!(
        within(c.add_auth("nosuch", b"x".to_vec())).await,
        Err(Error::AuthFailed)
    );
    let mut sw = c.state_watcher();
    common::wait_state(&mut sw, common::WAIT, |i| i.state.is_terminal()).await;
    assert_eq!(c.state().state, SessionState::AuthFailed);
    assert_eq!(
        within(w).await,
        Err(apache_zookeeper::WatchError::AuthFailed)
    );
    assert_eq!(within(c.exists("/")).await, Err(Error::AuthFailed));
    // Closing an already failed session completes synchronously without generating further network
    // traffic.
    let _ = within(c.close()).await;
    assert_eq!(c.state().state, SessionState::AuthFailed);
}

// ---- transactions ----

real_test!(multi_commits_and_aborts);
/// Invariant: atomic multi transactions execute with all-or-nothing semantics; when a transaction
/// fails, the aborted response identifies the first failing operation and details individual
/// status per item.
async fn multi_commits_and_aborts(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (a, b, s, k, t) = (
        ctx.path("/a"),
        ctx.path("/a/b"),
        ctx.path("/s-"),
        ctx.path("/k"),
        ctx.path("/t"),
    );
    let ttl = CreateMode::PersistentWithTtl(Ttl::from_millis(60_000).unwrap());
    let mut txn = MultiWriter::new();
    let ha = txn
        .create(&a, b"1", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let hb = txn
        .create(&b, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let hs = txn
        .create(&s, b"", CreateMode::PersistentSequential, OPEN_ACL_UNSAFE)
        .unwrap();
    let hk = txn
        .create(&k, b"", CreateMode::Container, OPEN_ACL_UNSAFE)
        .unwrap();
    let ht = txn.create(&t, b"", ttl, OPEN_ACL_UNSAFE).unwrap();
    let hset = txn.set_data(&a, b"2", Version::new(0)).unwrap();
    let hcheck = txn.check(&a, Version::new(1)).unwrap();
    let hdel = txn.delete(&b, Version::ANY).unwrap();
    let r = within(c.multi(&txn)).await.unwrap();
    assert_eq!(r.get(ha).unwrap().path.as_str(), a);
    assert_eq!(r.get(hb).unwrap().path.as_str(), b);
    assert!(r.get(hs).unwrap().path.as_str().starts_with(&s));
    // The server emits owner 0 for container and TTL znodes.
    assert_eq!(
        r.get(hk).unwrap().stat.ephemeral_kind(),
        EphemeralKind::None
    );
    assert_eq!(
        r.get(ht).unwrap().stat.ephemeral_kind(),
        EphemeralKind::None
    );
    assert_eq!(r.get(hset).unwrap().version, Version::new(1));
    assert_eq!(r.get(hcheck), Some(&()));
    assert_eq!(r.get(hdel), Some(&()));
    assert_eq!(r.iter().count(), 8);
    assert!(matches!(r.iter().last(), Some(OpResult::Delete)));
    // Operation handles generated by a different MultiWriter instance return None.
    let mut other = MultiWriter::new();
    let foreign = other.check(&a, Version::ANY).unwrap();
    assert_eq!(r.get(foreign), None);

    // Transaction abortion mid-sequence: preceding operations report None, the failing operation
    // reports its concrete error, succeeding operations report RuntimeInconsistency, and no
    // state changes persist.
    let new = ctx.path("/new");
    let mut bad = MultiWriter::new();
    bad.create(&new, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    bad.check(&a, Version::new(7)).unwrap();
    bad.delete(&a, Version::ANY).unwrap();
    match within(c.multi(&bad)).await {
        Err(MultiError::Aborted {
            index,
            error,
            per_op,
        }) => {
            assert_eq!((index, error), (1, Error::BadVersion));
            assert_eq!(
                per_op,
                vec![
                    None,
                    Some(Error::BadVersion),
                    Some(Error::RuntimeInconsistency)
                ]
            );
        }
        other => panic!("expected an abort: {other:?}"),
    }
    assert_eq!(within(c.exists(&new)).await, Ok(None));

    // Verify error classification when the initial operation at index 0 fails.
    let none = ctx.path("/none");
    for (op, want) in [
        ("exists", Error::NodeExists),
        ("nonode", Error::NoNode),
        ("notempty", Error::NotEmpty),
    ] {
        let mut m = MultiWriter::new();
        match op {
            "exists" => drop(m.create(&a, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)),
            "nonode" => drop(m.set_data(&none, b"", Version::ANY)),
            _ => drop(m.delete(&ctx.root, Version::ANY)),
        }
        let e = within(c.multi(&m)).await.unwrap_err();
        assert!(
            matches!(&e, MultiError::Aborted { index: 0, error, .. } if *error == want),
            "{op}: {e:?}"
        );
        // Error conversion from MultiError yields the primary triggering Error.
        assert_eq!(Error::from(e), want);
    }

    // An empty transaction executes as a no-op.
    let empty = within(c.multi(&MultiWriter::new())).await.unwrap();
    assert_eq!(empty.iter().count(), 0);
    within(c.close()).await.unwrap();
}

real_test!(multi_read_is_per_op);
/// Invariant: `multi_read` evaluates composite read batches independently, yielding individual
/// result statuses without short-circuiting unaffected operations when a read fails.
async fn multi_read_is_per_op(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (d, secret) = (ctx.path("/d"), ctx.path("/secret"));
    within(c.create(&d, b"v", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    within(c.create(
        ctx.path("/d/x"),
        b"",
        CreateMode::Persistent,
        OPEN_ACL_UNSAFE,
    ))
    .await
    .unwrap();
    let only_bob = [Acl {
        perms: Perms::ALL,
        id: Id::digest("bob", "pw"),
    }];
    within(c.create(&secret, b"", CreateMode::Persistent, &only_bob))
        .await
        .unwrap();

    let none = ctx.path("/none");
    let mut r = MultiReader::new();
    r.get_data(&d).unwrap();
    r.get_children(&d).unwrap();
    r.get_data(&none).unwrap();
    r.get_children(&none).unwrap();
    r.get_data(&secret).unwrap();
    r.get_children(&ctx.root).unwrap();
    let out = within(c.multi_read(&r)).await.unwrap();
    assert_eq!(out.len(), 6);
    assert!(
        matches!(out.first(), Some(Ok(ReadResult::Data { data, stat })) if data == b"v" && stat.num_children == 1)
    );
    assert_eq!(
        out.get(1),
        Some(&Ok(ReadResult::Children(vec!["x".to_owned()])))
    );
    assert_eq!(out.get(2), Some(&Err(Error::NoNode)));
    assert_eq!(out.get(3), Some(&Err(Error::NoNode)));
    assert_eq!(out.get(4), Some(&Err(Error::NoAuth)));
    assert!(matches!(out.get(5), Some(Ok(ReadResult::Children(n))) if n.len() == 2));
    within(c.close()).await.unwrap();
}

// ---- consistency, identity and configuration ----

real_test!(sync_who_am_i_and_config);
/// Invariant: `sync`, `who_am_i`, and `get_config` operate against standalone server instances;
/// `reconfig` requests are rejected by standalone instances with `Error::ReconfigDisabled`.
async fn sync_who_am_i_and_config(ctx: Ctx) {
    let c = setup(&ctx).await;
    within(c.sync(&ctx.root)).await.unwrap();
    within(c.sync("/")).await.unwrap();
    let ids = within(c.who_am_i()).await.unwrap();
    assert!(
        ids.iter()
            .any(|i| i.auth_scheme == "ip" && i.user == "127.0.0.1"),
        "{ids:?}"
    );
    let (cfg, _) = within(c.get_config()).await.unwrap();
    assert!(cfg.servers.is_empty(), "{cfg:?}");
    let change = Reconfig::Incremental {
        joining: Vec::new(),
        leaving: vec![9],
    };
    // Standalone server configurations have dynamic reconfig disabled by default.
    assert_eq!(
        within(c.reconfig(&change, None)).await.unwrap_err(),
        Error::ReconfigDisabled
    );
    let stats = c.stats();
    assert_eq!(stats.pending, 0);
    within(c.close()).await.unwrap();
}

// ---- chroot ----

real_test!(chroot_applies_both_ways);
/// Invariant: chroot prefixes are transparently prepended to outbound paths and stripped from
/// inbound responses and watcher event paths at component boundaries. `/zookeeper/config` remains
/// exempt.
async fn chroot_applies_both_ways(ctx: Ctx) {
    let root = setup(&ctx).await;
    // Construct chroot connection via connection string suffix and builder configuration.
    let chrooted = format!("{}{}", ctx.cs, ctx.root);
    let c = within(ctx.rt.apply(common::builder(&chrooted)).connect())
        .await
        .unwrap();
    let d = within(ctx.builder().chroot(ctx.root.as_str()).connect())
        .await
        .unwrap();

    let (p, _) = within(c.create("/a", b"1", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(p.as_str(), "/a");
    let (q, _) = within(c.create("/q-", b"", CreateMode::EphemeralSequential, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert!(q.as_str().starts_with("/q-"), "{q}");
    assert_eq!(within(root.get_data(ctx.path("/a"))).await.unwrap().0, b"1");
    assert_eq!(within(d.get_children("/")).await.unwrap().len(), 2);
    let st = within(d.exists("/")).await.unwrap().unwrap();
    assert_eq!(st.num_children, 2);
    assert_eq!(within(c.get_ephemerals_all()).await.unwrap(), vec![q]);

    // A watch event path is stripped.
    let (_, _, w) = within(d.get_data_watched("/a")).await.unwrap();
    within(root.set_data(ctx.path("/a"), b"2", None))
        .await
        .unwrap();
    assert_eq!(within(w).await.unwrap().path.as_str(), "/a");

    // Multi-operation response paths are stripped of the chroot prefix.
    let mut txn = MultiWriter::new();
    let h = txn
        .create("/m", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let r = within(c.multi(&txn)).await.unwrap();
    assert_eq!(r.get(h).unwrap().path.as_str(), "/m");
    // The cluster configuration path `/zookeeper/config` retains its absolute path representation.
    let (cfg_c, _) = within(c.get_config()).await.unwrap();
    let (cfg_root, _) = within(root.get_config()).await.unwrap();
    assert_eq!(cfg_c, cfg_root);

    // Ephemeral node path queries use prefix matching; verify that chroot filtering isolates
    // nodes created within the chroot namespace from adjacent sibling paths outside it.
    let sib = format!("{}x", ctx.root);
    within(root.create(&sib, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let owner = ctx.connect().await;
    for e in [format!("{sib}/e"), ctx.path("/o")] {
        within(owner.create(e, b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
            .await
            .unwrap();
    }
    let all = within(owner.get_ephemerals(&ctx.root)).await.unwrap();
    assert_eq!(all.len(), 2, "{all:?}");
    let (id, pw) = owner.detach();
    let e = within(
        ctx.rt
            .apply(common::builder(&chrooted))
            .session(id, pw)
            .connect(),
    )
    .await
    .unwrap();
    let mine = within(e.get_ephemerals_all()).await.unwrap();
    assert_eq!(
        mine.iter().map(|p| p.as_str()).collect::<Vec<_>>(),
        vec!["/o"]
    );

    // Attempting operations under a nonexistent chroot root returns NoNode.
    let gone = within(ctx.builder().chroot(ctx.path("/none")).connect())
        .await
        .unwrap();
    let f = gone.create("/a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
    assert_eq!(within(f).await.unwrap_err(), Error::NoNode);
    for cl in [c, d, e, gone, root] {
        within(cl.close()).await.unwrap();
    }
}

// ---- call order ----

real_test!(call_order_is_wire_order);
/// Invariant: requests are dispatched eagerly at invocation time, guaranteeing that invocation
/// order determines XID assignment and wire transmission sequence regardless of future await
/// order.
async fn call_order_is_wire_order(ctx: Ctx) {
    let c = setup(&ctx).await;
    let n = ctx.path("/n");
    // Dispatch multiple requests concurrently without awaiting completion.
    let create = c.create(&n, b"0", CreateMode::Persistent, OPEN_ACL_UNSAFE);
    let set1 = c.set_data(&n, b"1", Version::new(0));
    let seq: Vec<_> = (0..5)
        .map(|_| {
            c.create(
                ctx.path("/s-"),
                b"",
                CreateMode::PersistentSequential,
                OPEN_ACL_UNSAFE,
            )
        })
        .collect();
    let set2 = c.set_data(&n, b"2", Version::new(1));
    let read = c.get_data(&n);

    // Awaiting the final request guarantees completion of all earlier pipelined requests.
    let (data, st_read) = within(read).await.unwrap();
    assert_eq!(
        (data.as_slice(), st_read.version),
        (&b"2"[..], Version::new(2))
    );
    assert!(create.is_ready() && set1.is_ready() && set2.is_ready());
    assert!(seq.iter().all(apache_zookeeper::ResponseFuture::is_ready));

    let st2 = within(set2).await.unwrap();
    let mut names = Vec::new();
    for f in seq.into_iter().rev() {
        names.push(within(f).await.unwrap().0.into_string());
    }
    names.reverse();
    let st1 = within(set1).await.unwrap();
    let (_, st0) = within(create).await.unwrap();
    // Server-assigned ZXIDs monotonically increase reflecting pipeline invocation order.
    assert!(
        st0.czxid < st1.mzxid && st1.mzxid < st2.mzxid,
        "{st0:?} {st1:?} {st2:?}"
    );
    let mut sorted = names.clone();
    sorted.sort();
    assert_eq!(names, sorted, "sequence numbers follow the call order");

    // Cloned client handles share the underlying pipeline: invocations across clones
    // are serialized according to mutual submission order.
    let c2 = c.clone();
    let a = c.set_data(&n, b"a", Version::new(2));
    let b = c2.set_data(&n, b"b", Version::new(3));
    assert_eq!(within(b).await.unwrap().version, Version::new(4));
    assert_eq!(within(a).await.unwrap().version, Version::new(3));
    within(c.close()).await.unwrap();
}

// ---- blocking waits ----

// Tokio runtime integration tests require the optional `tokio` feature.
#[cfg(all(feature = "blocking", unix, feature = "tokio"))]
real_test!(waits_in_a_tokio_worker_follow_the_driver);
/// Invariant: invoking blocking wait methods within a Tokio runtime worker thread yields
/// `Error::InvalidState` when running under the Tokio driver to prevent event loop deadlocks.
/// In contrast, clients managed by a dedicated background OS thread permit blocking calls safely.
#[cfg(all(feature = "blocking", unix, feature = "tokio"))]
async fn waits_in_a_tokio_worker_follow_the_driver(ctx: Ctx) {
    let c = setup(&ctx).await;
    let n = ctx.path("/n");
    // The dedicated blocking client facade always utilizes the background OS thread driver.
    let bc = apache_zookeeper::blocking::Client::connect(ctx.builder()).unwrap();
    assert!(bc.exists(&ctx.root).unwrap().is_some());
    assert_eq!(bc.as_async().close().wait(), Ok(()));
    if ctx.rt == Rt::Thread {
        thread_driver_waits(&ctx, c, &n);
    } else {
        tokio_driver_waits_are_refused(&ctx, c, &n).await;
    }
}

/// Executes blocking waits on a client driven by a dedicated OS background thread.
#[cfg(all(feature = "blocking", unix, feature = "tokio"))]
fn thread_driver_waits(ctx: &Ctx, c: apache_zookeeper::Client, n: &str) {
    use apache_zookeeper::AddWatchMode;
    c.create(n, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .wait()
        .unwrap();
    assert!(matches!(
        c.exists(n).wait_timeout(std::time::Duration::from_secs(5)),
        Ok(Ok(Some(_)))
    ));
    let (_, _, w) = c.get_data_watched(n).wait().unwrap();
    let mut pw = c.add_watch(n, AddWatchMode::Persistent).wait().unwrap();
    c.set_data(n, b"x", None).wait().unwrap();
    assert!(w.wait().is_ok());
    assert!(pw.recv().unwrap().is_ok());
    let other = ctx.builder().connect().wait().unwrap();
    assert_eq!(other.close().wait(), Ok(()));
    let mut sw = c.state_watcher();
    assert_eq!(c.close().wait(), Ok(()));
    assert_eq!(sw.wait_changed().unwrap().state, SessionState::Closed);
}

/// Verifies that blocking waits executed within a Tokio worker on a Tokio-driven client are
/// rejected.
#[cfg(all(feature = "blocking", unix, feature = "tokio"))]
async fn tokio_driver_waits_are_refused(ctx: &Ctx, c: apache_zookeeper::Client, n: &str) {
    use apache_zookeeper::{AddWatchMode, WatchError};
    let f = c.create(n, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
    assert_eq!(f.wait(), Err(Error::InvalidState));
    // The operation was submitted to the wire prior to rejection of the blocking wait.
    within(c.sync(n)).await.unwrap();
    assert!(within(c.exists(n)).await.unwrap().is_some());
    assert!(matches!(
        c.exists(n).wait_timeout(std::time::Duration::from_secs(1)),
        Ok(Err(Error::InvalidState))
    ));

    let (_, _, w) = within(c.get_data_watched(n)).await.unwrap();
    assert_eq!(w.wait(), Err(WatchError::InvalidState));
    let mut pw = within(c.add_watch(n, AddWatchMode::Persistent))
        .await
        .unwrap();
    assert_eq!(pw.recv(), Some(Err(WatchError::InvalidState)));
    // Rejection with InvalidState does not corrupt watcher registration or event delivery.
    within(c.set_data(n, b"x", None)).await.unwrap();
    assert!(within(pw.next()).await.unwrap().is_ok());
    assert_eq!(
        c.state_watcher().wait_changed().unwrap_err(),
        Error::InvalidState
    );
    assert_eq!(
        ctx.builder().connect().wait().unwrap_err().error(),
        &Error::InvalidState
    );
    assert_eq!(c.close().wait(), Err(Error::InvalidState));
    // Rejection of a blocking close call still initiates client shutdown.
    assert_eq!(within(c.close()).await, Ok(()));
    assert_eq!(c.state().state, SessionState::Closed);
}

/// Invariant: when invoked outside asynchronous runtime contexts, `wait` blocks until completion,
/// while `wait_timeout` yields `Err(self)` preserving the unfinished future upon timeout
/// expiration.
#[cfg(feature = "blocking")]
#[test]
#[ignore = "real-server"]
fn waits_on_a_plain_thread() {
    use std::time::Duration;
    let Some(guard) = SERVER.acquire() else {
        return;
    };
    let c = common::builder(&guard.connect_string())
        .connect()
        .wait()
        .unwrap();
    c.create("/wait-plain", b"v", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .wait()
        .unwrap();
    let r = c
        .get_data("/wait-plain")
        .wait_timeout(WAIT_LONG)
        .ok()
        .unwrap();
    assert_eq!(r.unwrap().0, b"v");

    // A server that accepts a TCP connection but ceases responding leaves requests in a pending
    // state.
    let hole = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let held = common::builder(&hole.local_addr().unwrap().to_string())
        .session_timeout(Duration::from_secs(2))
        .build()
        .unwrap();
    let f = held.exists("/");
    let f = f.wait_timeout(Duration::from_millis(200)).unwrap_err();
    assert!(!f.is_ready());
    // Expiration of the receive timeout threshold triggers connection teardown,
    // failing pending requests with ConnectionLoss.
    assert_eq!(f.wait(), Err(Error::ConnectionLoss));
    // In the absence of an active connection, shutdown does not transmit packets.
    // Close returns either Ok or ConnectionLoss; verify final session state.
    let _ = held.close().wait();
    assert_eq!(held.state().state, SessionState::Closed);
    c.delete("/wait-plain", None).wait().unwrap();
    assert_eq!(c.close().wait(), Ok(()));
}

/// Default timeout budget allocated for non-runtime synchronous wait tests.
#[cfg(feature = "blocking")]
const WAIT_LONG: std::time::Duration = common::WAIT;

/// Invariant: for clients utilizing the Tokio driver, blocking wait rejections apply to any thread
/// possessing a Tokio runtime context, including `spawn_blocking` pool threads. Off-runtime native
/// threads and OS-thread driven clients remain permitted to execute blocking waits.
#[cfg(all(feature = "blocking", unix, feature = "tokio"))]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn waits_off_the_workers() {
    let Some(ctx) = Ctx::new(&SERVER, "waits_off_the_workers", Rt::Tokio) else {
        return;
    };
    let c = setup(&ctx).await;
    let c1 = c.clone();
    let root = ctx.root.clone();
    let r = tokio::task::spawn_blocking(move || c1.exists(&root).wait())
        .await
        .unwrap();
    assert_eq!(r, Err(Error::InvalidState));
    let c2 = c.clone();
    let root = ctx.root.clone();
    let r = std::thread::spawn(move || c2.exists(&root).wait())
        .join()
        .unwrap();
    assert!(r.unwrap().is_some());
    within(c.close()).await.unwrap();

    let t = within(
        ctx.builder()
            .runtime(apache_zookeeper::Runtime::Thread(
                apache_zookeeper::ThreadOptions::default(),
            ))
            .connect(),
    )
    .await
    .unwrap();
    let t1 = t.clone();
    let root = ctx.root.clone();
    let r = tokio::task::spawn_blocking(move || t1.exists(&root).wait())
        .await
        .unwrap();
    assert!(r.unwrap().is_some());
    within(t.close()).await.unwrap();
}
