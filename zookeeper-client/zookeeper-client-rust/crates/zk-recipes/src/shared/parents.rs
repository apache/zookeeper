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

//! Container node hierarchy creation and ancestor validation.
//!
//! Coordination primitives such as [`DistributedLock`](crate::DistributedLock) and
//! [`LeaderLatch`](crate::LeaderLatch) provision their target parent paths and intermediate
//! ancestors using ZooKeeper container nodes (`CreateMode::Container`), providing Apache
//! Curator's `creatingParentContainersIfNeeded` behavior. The ZooKeeper server automatically
//! reclaims container znodes once their last child is unlinked, preventing empty hierarchy
//! accumulation when distributed coordination objects are disposed.
//!
//! # Permission Invariants and ZOOKEEPER-2590
//!
//! Before issuing creation requests, intermediate path components are proactively queried via
//! `exists` to ensure that clients lacking write permissions on already existing parent levels
//! do not trigger authorization failures.
//!
//! Per ZOOKEEPER-2590, ZooKeeper servers enforce read authorization checks on `exists` requests
//! only if the target znode actually exists. Consequently, receiving `Error::NoAuth` on an
//! `exists` query conclusively proves that the znode is already present on the ensemble. This
//! refinement improves upon Curator's `ZKPaths.mkdirs`, which traverses upward from the leaf and
//! aborts with an authentication exception if the deepest existing ancestor is unreadable.

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{Client, CreateMode, Error, ZPath};

/// Recursively provisions missing ancestor directories as container znodes from root to leaf.
///
/// Dispatches parallel `exists` queries across the hierarchy, followed by ordered creation
/// of missing levels. Preexisting levels and concurrent creation races (`NodeExists`) are
/// treated as success.
///
/// # Errors
///
/// Returns an error if an unrecoverable server failure occurs. If a container is concurrently
/// purged by the server prior to child creation (`NoNode`), the caller should retry the operation.
pub(crate) async fn ensure_parents(client: &Client, path: ZPath<'_>) -> Result<(), Error> {
    let mut levels = Vec::new();
    let mut at = Some(path);
    while let Some(p) = at {
        if p.is_root() {
            break;
        }
        levels.push(p);
        at = p.parent();
    }
    levels.reverse();

    let checks: Vec<_> = levels.iter().map(|p| client.exists(*p)).collect();
    let mut missing = Vec::new();
    for (p, check) in levels.iter().zip(checks) {
        match check.await {
            Ok(Some(_)) | Err(Error::NoAuth) => {}
            Ok(None) => missing.push(*p),
            Err(e) => return Err(e),
        }
    }

    let creates: Vec<_> = missing
        .iter()
        .map(|p| client.create(*p, &[], CreateMode::Container, OPEN_ACL_UNSAFE))
        .collect();
    for create in creates {
        match create.await {
            Ok(_) | Err(Error::NodeExists) => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}
