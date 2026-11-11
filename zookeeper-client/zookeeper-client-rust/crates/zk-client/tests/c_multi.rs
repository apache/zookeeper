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

//! Parity test suite validating atomic multi-operation transaction batches (`TestMulti.cc`).
//!
//! Asserts transactional atomicity semantics corresponding to `Zookeeper_multi`, verifying
//! all-or-nothing execution, error attribution to specific sub-operations, intra-transaction
//! dependency visibility, optimistic locking version checks, watch notifications, and
//! oversized frame handling across both OS thread and Tokio runtimes.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{Client, CreateMode, Error, MultiError, MultiWriter, SessionState, Version};
use common::{Ctx, Rt, SharedServer, setup, within};

/// Process-wide shared server instance dedicated to multi-operation transactions.
static SERVER: SharedServer = SharedServer::new(0);

/// Generates integration test instances executing across thread-based and Tokio runtime drivers.
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

/// Dispatches a multi-operation transaction asserting that execution terminates in an aborted
/// transaction error.
async fn abort_of(c: &Client, txn: &MultiWriter<'_>) -> (usize, Error, Vec<Option<Error>>) {
    match within(c.multi(txn)).await {
        Err(MultiError::Aborted {
            index,
            error,
            per_op,
        }) => (index, error, per_op),
        other => panic!("expected an abort: {other:?}"),
    }
}

real_test!(create);
/// C parity test case (`Zookeeper_multi::testCreate`): asserts atomic batch creation of multiple
/// znodes and returns individual created paths.
async fn create(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (p1, p2, p3) = (
        ctx.path("/multi1"),
        ctx.path("/multi1/a"),
        ctx.path("/multi1/b"),
    );
    let mut txn = MultiWriter::new();
    let h1 = txn
        .create(&p1, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let h2 = txn
        .create(&p2, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let h3 = txn
        .create(&p3, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let r = within(c.multi(&txn)).await.unwrap();
    assert_eq!(r.iter().count(), 3);
    assert_eq!(r.get(h1).unwrap().path.as_str(), p1);
    assert_eq!(r.get(h2).unwrap().path.as_str(), p2);
    assert_eq!(r.get(h3).unwrap().path.as_str(), p3);
    within(c.close()).await.unwrap();
}

real_test!(create_delete);
/// C parity test case (`Zookeeper_multi::testCreateDelete`): validates intra-batch creation and
/// immediate deletion within a single committed transaction.
async fn create_delete(ctx: Ctx) {
    let c = setup(&ctx).await;
    let p = ctx.path("/multi2");
    let mut txn = MultiWriter::new();
    txn.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.delete(&p, Version::new(0)).unwrap();
    within(c.multi(&txn)).await.unwrap();
    assert_eq!(within(c.exists(&p)).await, Ok(None));
    within(c.close()).await.unwrap();
}

real_test!(invalid_version);
/// C parity test case (`Zookeeper_multi::testInvalidVersion`): verifies that version check mismatch
/// aborts transaction execution with [`Error::BadVersion`] and marks subsequent operations with
/// [`Error::RuntimeInconsistency`].
async fn invalid_version(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (p, child) = (ctx.path("/multi3"), ctx.path("/multi3/a"));
    let mut txn = MultiWriter::new();
    txn.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.delete(&p, Version::new(1)).unwrap();
    txn.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.create(&child, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let (index, error, per_op) = abort_of(&c, &txn).await;
    assert_eq!((index, error), (1, Error::BadVersion));
    assert_eq!(
        per_op,
        vec![
            None,
            Some(Error::BadVersion),
            Some(Error::RuntimeInconsistency),
            Some(Error::RuntimeInconsistency)
        ]
    );
    assert_eq!(within(c.exists(&p)).await, Ok(None));
    within(c.close()).await.unwrap();
}

real_test!(nested_create);
/// C parity test case (`Zookeeper_multi::testNestedCreate`): verifies that sub-operations observe
/// mutations performed by earlier operations within the same transaction batch.
async fn nested_create(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (a, b, d) = (
        ctx.path("/multi4"),
        ctx.path("/multi4/a"),
        ctx.path("/multi4/a/1"),
    );
    let mut txn = MultiWriter::new();
    for p in [&a, &b, &d] {
        txn.create(p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
    }
    for p in [&d, &b, &a] {
        txn.delete(p, Version::new(0)).unwrap();
    }
    let r = within(c.multi(&txn)).await.unwrap();
    assert_eq!(r.iter().count(), 6);
    for p in [&d, &b, &a] {
        assert_eq!(within(c.exists(p)).await, Ok(None), "{p}");
    }
    within(c.close()).await.unwrap();
}

real_test!(set_data);
/// C parity test case (`Zookeeper_multi::testSetData`): validates batch updates applying version-
///checked data modifications across multiple znodes.
async fn set_data(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (a, b) = (ctx.path("/multi5"), ctx.path("/multi5/a"));
    let mut txn = MultiWriter::new();
    txn.create(&a, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.create(&b, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    within(c.multi(&txn)).await.unwrap();

    let mut sets = MultiWriter::new();
    let ha = sets.set_data(&a, b"1", Version::new(0)).unwrap();
    let hb = sets.set_data(&b, b"2", Version::new(0)).unwrap();
    let r = within(c.multi(&sets)).await.unwrap();
    assert_eq!(r.get(ha).unwrap().version, Version::new(1));
    assert_eq!(r.get(hb).unwrap().version, Version::new(1));
    assert_eq!(within(c.get_data(&a)).await.unwrap().0, b"1");
    assert_eq!(within(c.get_data(&b)).await.unwrap().0, b"2");
    within(c.close()).await.unwrap();
}

real_test!(update_conflict);
/// C parity test case (`Zookeeper_multi::testUpdateConflict`). Asserts that conflicting version
/// updates within a single batch trigger transaction abortion, whereas sequential versioning
/// succeeds.
async fn update_conflict(ctx: Ctx) {
    let c = setup(&ctx).await;
    let p = ctx.path("/multi6");
    let build = |second: Version| {
        let mut txn = MultiWriter::new();
        txn.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
        txn.set_data(&p, b"X", Version::new(0)).unwrap();
        txn.set_data(&p, b"Y", second).unwrap();
        txn
    };
    let (index, error, _) = abort_of(&c, &build(Version::new(0))).await;
    assert_eq!((index, error), (2, Error::BadVersion));
    assert_eq!(within(c.exists(&p)).await, Ok(None));

    // Sub-operation order determines evaluation sequence during transactional execution.
    within(c.multi(&build(Version::new(1)))).await.unwrap();
    let (data, st) = within(c.get_data(&p)).await.unwrap();
    assert_eq!((data.as_slice(), st.version), (&b"Y"[..], Version::new(2)));
    within(c.close()).await.unwrap();
}

real_test!(delete_update_conflict);
/// C parity test case (`Zookeeper_multi::testDeleteUpdateConflict`). Asserts that updating a znode
/// following its deletion within the same batch triggers [`Error::NoNode`] and rolls back all
/// modifications.
async fn delete_update_conflict(ctx: Ctx) {
    let c = setup(&ctx).await;
    let p = ctx.path("/multi7");
    let mut txn = MultiWriter::new();
    txn.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    txn.delete(&p, Version::new(0)).unwrap();
    txn.set_data(&p, b"Y", Version::new(0)).unwrap();
    let (index, error, per_op) = abort_of(&c, &txn).await;
    assert_eq!((index, error), (2, Error::NoNode));
    assert_eq!(per_op, vec![None, None, Some(Error::NoNode)]);
    assert_eq!(within(c.exists(&p)).await, Ok(None));
    within(c.close()).await.unwrap();
}

real_test!(async_multi);
/// C parity test case (`Zookeeper_multi::testAsyncMulti`). Verifies pipelined nonblocking
/// transaction dispatch and deferred result consumption.
async fn async_multi(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (p1, p2, p3) = (
        ctx.path("/multi8"),
        ctx.path("/multi8/a"),
        ctx.path("/multi8/b"),
    );
    let mut txn = MultiWriter::new();
    let hs = [&p1, &p2, &p3].map(|p| {
        txn.create(p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap()
    });
    // Deterministic FIFO request dispatch guarantees that subsequent operations observe prior
    // transaction effects.
    let pending = c.multi(&txn);
    let after = within(c.exists(&p3)).await.unwrap();
    assert!(after.is_some());
    let r = within(pending).await.unwrap();
    for (h, p) in hs.into_iter().zip([&p1, &p2, &p3]) {
        assert_eq!(r.get(h).unwrap().path.as_str(), p.as_str());
    }
    within(c.close()).await.unwrap();
}

real_test!(multi_fail);
/// C parity test case (`Zookeeper_multi::testMultiFail`). Asserts that duplicate path creation
/// attempts within a batch trigger [`Error::NodeExists`].
async fn multi_fail(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (p, child) = (ctx.path("/multi9"), ctx.path("/multi9/b"));
    let mut txn = MultiWriter::new();
    for q in [&p, &p, &child] {
        txn.create(q, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
            .unwrap();
    }
    let (index, error, _) = abort_of(&c, &txn).await;
    assert_eq!((index, error), (1, Error::NodeExists));
    assert_eq!(within(c.exists(&p)).await, Ok(None));
    within(c.close()).await.unwrap();
}

real_test!(check);
/// C parity test case (`Zookeeper_multi::testCheck`). Validates optimistic concurrency control
/// using explicit version check operations guarding subsequent modifications.
async fn check(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (p, a, b) = (
        ctx.path("/multi0"),
        ctx.path("/multi0/a"),
        ctx.path("/multi0/b"),
    );
    within(c.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();

    let mut ok = MultiWriter::new();
    let hc = ok.check(&p, Version::new(0)).unwrap();
    ok.create(&a, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let r = within(c.multi(&ok)).await.unwrap();
    assert_eq!(r.get(hc), Some(&()));
    assert!(within(c.exists(&a)).await.unwrap().is_some());

    let mut bad = MultiWriter::new();
    bad.check(&p, Version::new(10)).unwrap();
    bad.create(&b, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let (index, error, per_op) = abort_of(&c, &bad).await;
    assert_eq!((index, error), (0, Error::BadVersion));
    assert_eq!(
        per_op,
        vec![Some(Error::BadVersion), Some(Error::RuntimeInconsistency)]
    );
    assert_eq!(within(c.exists(&b)).await, Ok(None));
    within(c.close()).await.unwrap();
}

real_test!(watch);
/// C parity test case (`Zookeeper_multi::testWatch`). Verifies that transactional modifications
/// trigger active watch notifications and enable subsequent operations within the event handler.
async fn watch(ctx: Ctx) {
    let c = setup(&ctx).await;
    let p = ctx.path("/multiwatch");
    within(c.create(&p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (_, _, w) = within(c.get_data_watched(&p)).await.unwrap();

    // Background task models asynchronous C event delivery callbacks.
    let (c2, p2) = (c.clone(), p.clone());
    let task = tokio::spawn(async move {
        let ev = w.await.unwrap();
        assert_eq!(ev.path.as_str(), p2);
        let mut txn = MultiWriter::new();
        txn.set_data(&p2, b"1", Version::ANY).unwrap();
        let r = c2.multi(&txn).await.unwrap();
        assert_eq!(r.iter().count(), 1);
        let (data, _) = c2.get_data(&p2).await.unwrap();
        assert_eq!(data, b"1");
    });

    within(c.set_data(&p, b"", Version::ANY)).await.unwrap();
    within(task).await.unwrap();
    within(c.close()).await.unwrap();
}

real_test!(big_async_multi);
/// C parity test case (`Zookeeper_multi::testBigAsyncMulti`, ZOOKEEPER-1636). Validates handling
/// of requests exceeding server buffer limits, ensuring connection reset yields
/// [`Error::ConnectionLoss`] and automated reconnection succeeds.
async fn big_async_multi(ctx: Ctx) {
    let c = setup(&ctx).await;
    let x = ctx.path("/x");
    let buff = vec![0_u8; 1024];
    let mut txn = MultiWriter::new();
    for _ in 0..500 {
        txn.set_data(&x, &buff, Version::ANY).unwrap();
        txn.create(&x, &buff, CreateMode::PersistentSequential, OPEN_ACL_UNSAFE)
            .unwrap();
        txn.delete(&x, Version::ANY).unwrap();
    }
    assert_eq!(txn.len(), 1500);
    let mut sw = c.state_watcher();
    assert_eq!(
        within(c.multi(&txn)).await.unwrap_err(),
        MultiError::Request(Error::ConnectionLoss)
    );

    // Driver reconnects transparently following server side socket disconnection.
    common::wait_state(&mut sw, common::WAIT, |i| {
        i.state == SessionState::Connected && i.epoch >= 2
    })
    .await;
    let target = ctx.path("/target");
    within(c.create(&target, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    within(c.close()).await.unwrap();
}

real_test!(sequential_node_create_in_async_multi);
/// C parity test case (`Zookeeper_multi::testSequentialNodeCreateInAsyncMulti`, ZOOKEEPER-1624).
/// Asserts that sequence numbers from aborted transactions are discarded, preserving contiguous
/// numbering upon subsequent commits.
async fn sequential_node_create_in_async_multi(ctx: Ctx) {
    let c = setup(&ctx).await;
    let (node, dup) = (ctx.path("/node-"), ctx.path("/dup"));
    let mut txn = MultiWriter::new();
    let hn = txn
        .create(
            &node,
            b"",
            CreateMode::PersistentSequential,
            OPEN_ACL_UNSAFE,
        )
        .unwrap();
    txn.create(&dup, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .unwrap();
    let pending: Vec<_> = (0..4).map(|_| c.multi(&txn)).collect();
    let mut results = Vec::new();
    for f in pending {
        results.push(within(f).await);
    }
    let mut it = results.into_iter();
    let first = it.next().unwrap().unwrap();
    assert_eq!(
        first.get(hn).unwrap().path.as_str(),
        format!("{node}0000000000")
    );
    for r in it {
        match r {
            Err(MultiError::Aborted {
                index,
                error,
                per_op,
            }) => {
                assert_eq!((index, error), (1, Error::NodeExists));
                assert_eq!(per_op, vec![None, Some(Error::NodeExists)]);
            }
            other => panic!("expected an abort: {other:?}"),
        }
    }
    // Sequence numbering reflects successfully committed child znodes.
    let (next, _) = within(c.create(
        &node,
        b"",
        CreateMode::PersistentSequential,
        OPEN_ACL_UNSAFE,
    ))
    .await
    .unwrap();
    assert_eq!(next.as_str(), format!("{node}0000000002"));
    within(c.close()).await.unwrap();
}
