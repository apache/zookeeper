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

//! Public API contract tests for `apache-zookeeper-recipes`: auto-trait bounds
//! (`Send`/`Sync`), `Send` bounds on returned futures, and constructor path validation.
//!
//! Futures in these tests are constructed and dropped without polling so no network
//! requests are transmitted.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::Error;
use apache_zookeeper_recipes::{
    CachedNode, DistributedLock, Latch, LeaderLatch, LockGuard, Participant, TreeCache,
    TreeCacheBuilder, TreeEvent,
};

fn assert_send<T: Send>(_: &T) {}

fn assert_send_sync<T: Send + Sync>() {}

fn assert_send_only<T: Send>() {}

#[test]
fn recipe_types_have_the_auto_traits() {
    // Futures borrowing `&self` are `Send` only when the receiver type is `Sync`.
    assert_send_sync::<DistributedLock>();
    assert_send_sync::<LeaderLatch>();
    assert_send_sync::<CachedNode>();
    assert_send_sync::<TreeEvent>();
    assert_send_sync::<Participant>();
    assert_send_only::<LockGuard>();
    assert_send_only::<Latch>();
    assert_send_only::<TreeCache>();
    assert_send_only::<TreeCacheBuilder>();
}

#[tokio::test]
async fn every_returned_future_is_send() {
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;

    let lock = DistributedLock::new(&client, "/contract/lock").unwrap();
    let f = lock.acquire();
    assert_send(&f);
    drop(f);
    let f = lock.try_acquire();
    assert_send(&f);
    drop(f);

    let latch = LeaderLatch::new(&client, "/contract/latch", "p1").unwrap();
    let f = latch.start();
    assert_send(&f);
    drop(f);

    let f = TreeCache::builder(&client, "/contract/tree").start();
    assert_send(&f);
    drop(f);

    // Uninvoked closures verify `Send` bounds on futures returned by active recipe handles.
    let _ = |g: LockGuard| {
        assert_send(&g.lost());
        assert_send(&g.release());
    };
    let _ = |mut l: Latch| {
        assert_send(&l.changed());
        assert_send(&l.await_leadership());
        assert_send(&l.participants());
        assert_send(&l.close());
    };
    let _ = |mut c: TreeCache| {
        assert_send(&c.next());
    };

    common::within(client.close()).await.unwrap();
}

#[tokio::test]
async fn constructors_reject_bad_paths() {
    let server = common::fake();
    let client = common::connect(&server.connect_string()).await;

    for bad in ["", "relative", "/trailing/", "/double//slash", "/dot/."] {
        assert!(
            matches!(
                DistributedLock::new(&client, bad),
                Err(Error::InvalidPath(_))
            ),
            "lock path {bad:?}"
        );
        assert!(
            matches!(
                LeaderLatch::new(&client, bad, "id"),
                Err(Error::InvalidPath(_))
            ),
            "latch path {bad:?}"
        );
        let started = common::within(TreeCache::builder(&client, bad).start()).await;
        assert!(
            matches!(started, Err(Error::InvalidPath(_))),
            "tree cache root {bad:?}"
        );
    }

    // Builder configuration is purely local and transmits no requests until started.
    let builder = TreeCache::builder(&client, "/contract/tree")
        .cache_data(false)
        .max_depth(Some(2));
    drop(builder);
    let lock = DistributedLock::new(&client, "/contract/lock")
        .unwrap()
        .node_data(b"owner".to_vec());
    drop(lock);

    common::within(client.close()).await.unwrap();
}
