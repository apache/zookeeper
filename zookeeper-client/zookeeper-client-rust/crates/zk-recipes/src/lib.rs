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

//! Coordination recipes for Apache ZooKeeper.
//!
//! This crate provides high level distributed coordination recipes built directly
//! upon the public API of [`apache_zookeeper`]:
//!
//! - [`DistributedLock`]: A distributed mutual exclusion lock implementing the
//!   sequential ephemeral znode pattern, wire compatible with Apache Curator's
//!   `InterProcessMutex`.
//! - [`LeaderLatch`]: Distributed leader election with deterministic participant
//!   ordering and leadership handoff, wire compatible with Apache Curator's
//!   `LeaderLatch`.
//! - [`TreeCache`]: An in-memory cache mirroring a ZooKeeper znode subtree,
//!   maintained through persistent recursive watches.
//!
//! # Design
//!
//! - **Public API Foundation**: All recipes are constructed entirely using the public
//!   [`apache_zookeeper`] client API without relying on internal hooks or private client state.
//! - **Executor-Agnostic Async Execution**: Recipes do not spawn detached background runtime
//!   tasks for their primary state machines. Progress is driven cooperatively when
//!   application tasks await the returned futures. All returned futures implement `Send`
//!   and integrate cleanly with any async runtime.
//! - **Timeout Management**: Recipes do not enforce internal timer mechanisms. Callers
//!   manage waiting durations using runtime level timeout combinators, such as
//!   `tokio::time::timeout`.
//! - **Cancellation Safety**: All asynchronous wait operations are cancellation safe. If
//!   a waiting future is dropped before acquisition completes, any transient ephemeral
//!   nodes created during negotiation are cleanly unlinked to prevent resource leaks or
//!   phantom participants.
//! - **Guaranteed Ephemeral Deletion**: Ephemeral node cleanup follows Apache Curator's
//!   guaranteed deletion semantics. When a lock guard or latch is dropped, a deletion
//!   request is issued immediately. If transient network disconnections, session reconnection
//!   transitions, or client queue saturation prevent immediate deletion, a dedicated
//!   background cleanup thread (`zk-recipes-delete`) retries unlinking across subsequent
//!   `Connected` session epochs. If a node creation was in-flight when dropped, the cleanup
//!   mechanism identifies the orphaned znode via its unique protected UUID prefix. This
//!   background thread operates on-demand and terminates once all pending unlinks resolve or
//!   the underlying session enters a terminal state.
//! - **Session Lifecycle and Error Propagation**: Operations return [`apache_zookeeper::Error`].
//!   Terminal session conditions (`SessionExpired`, `AuthFailed`, `Closing`) invalidate
//!   active recipe state, requiring the application to reconstruct client sessions and
//!   coordination primitives.
//!
//! # Server Version Requirements
//!
//! - [`DistributedLock`] and [`LeaderLatch`] require ZooKeeper 3.5.1 or later due to their
//!   reliance on `CreateMode::Container` for self-cleaning parent znodes.
//! - [`TreeCache`] requires ZooKeeper 3.6.0 or later to leverage server side persistent
//!   recursive watches (`AddWatchMode::PersistentRecursive`).

#![forbid(unsafe_code)]
#![deny(missing_docs)]
#![cfg_attr(
    test,
    allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects,
        clippy::disallowed_macros
    )
)]

mod latch;
mod lock;
mod shared;
mod tree_cache;

pub use latch::{Latch, LeaderLatch, Participant};
pub use lock::{DistributedLock, LockGuard};
pub use tree_cache::{CachedNode, TreeCache, TreeCacheBuilder, TreeEvent};
