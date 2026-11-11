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

//! Shared primitives, protocol utilities, and session management infrastructure.
//!
//! This module encapsulates common lower level coordination machinery shared across
//! [`DistributedLock`](crate::DistributedLock), [`LeaderLatch`](crate::LeaderLatch),
//! and [`TreeCache`](crate::TreeCache):
//!
//! - [`protected`]: Curator compatible protected mode token generation (`_c_<uuid>-`)
//!   for unambiguous znode identification during transient network partitions.
//! - [`order`]: Curator compatible sequence ordering, predecessor selection, and child sorting.
//! - [`parents`]: Recursive creation of ancestor paths utilizing ZooKeeper container znodes.
//! - [`participant`]: Resilient sequential ephemeral participant creation with connection loss
//!   recovery, and RAII node guards enforcing guaranteed unlinking upon drop.
//! - [`guaranteed`]: Dedicated asynchronous retry engine providing guaranteed deletion semantics
//!   across session reconnects for abandoned or unlinked znodes.
//! - [`session`]: Session state lifecycle tracking, terminal error classification, and
//!   reconnection synchronization.
//! - [`select`]: Lightweight executor agnostic biased select combinator for coordinating futures.
//!
//! By centralizing these building blocks, the recipes maintain consistent protocol semantics,
//! identical on-wire znode structures, and unified failure recovery strategies.

pub(crate) mod guaranteed;
pub(crate) mod order;
pub(crate) mod parents;
pub(crate) mod participant;
pub(crate) mod protected;
pub(crate) mod select;
pub(crate) mod session;
