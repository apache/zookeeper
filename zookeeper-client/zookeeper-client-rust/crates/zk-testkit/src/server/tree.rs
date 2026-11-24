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

//! In-memory hierarchical znode namespace tree emulating ZooKeeper `DataTree` semantics.
//!
//! Provides an in-memory replica of the ZooKeeper hierarchical node tree. Faithfully implements
//! Java `DataTree` invariants for path syntax validation, node version counters, sequential node
//! naming, ephemeral ownership lifecycles, and container/TTL modes. Mutations produce structured
//! watch trigger notifications for post-commit dispatch.

use std::collections::{BTreeMap, BTreeSet};

use zk_jute::data::{ACL, Stat};
use zk_jute::rc;

/// Watcher event notification type for node creation (`EventType.NodeCreated`).
pub const EVENT_CREATED: i32 = 1;
/// Watcher event notification type for node deletion (`EventType.NodeDeleted`).
pub const EVENT_DELETED: i32 = 2;
/// Watcher event notification type for node data updates (`EventType.NodeDataChanged`).
pub const EVENT_DATA_CHANGED: i32 = 3;
/// Watcher event notification type for child node additions or removals
/// (`EventType.NodeChildrenChanged`).
pub const EVENT_CHILDREN_CHANGED: i32 = 4;

/// Creation mode flag designating a standard persistent znode (`CreateMode.PERSISTENT`).
pub const MODE_PERSISTENT: i32 = 0;
/// Creation mode flag designating a session-scoped ephemeral znode (`CreateMode.EPHEMERAL`).
pub const MODE_EPHEMERAL: i32 = 1;
/// Creation mode flag designating a persistent znode with a monotonically increasing sequential
/// name suffix.
pub const MODE_PERSISTENT_SEQUENTIAL: i32 = 2;
/// Creation mode flag designating an ephemeral znode with a monotonically increasing sequential
/// name suffix.
pub const MODE_EPHEMERAL_SEQUENTIAL: i32 = 3;
/// Creation mode flag designating a container znode automatically purged when its children are
/// deleted.
pub const MODE_CONTAINER: i32 = 4;
/// Creation mode flag designating a persistent znode with a configured time-to-live expiration.
pub const MODE_PERSISTENT_WITH_TTL: i32 = 5;
/// Creation mode flag designating a persistent sequential znode with a configured time-to-live
/// expiration.
pub const MODE_PERSISTENT_SEQUENTIAL_WITH_TTL: i32 = 6;

/// In-memory representation of an individual znode within the hierarchical data tree.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Node {
    /// Opaque byte payload stored within the znode.
    pub data: Vec<u8>,
    /// Access control list associated with this node. Stored for protocol inspection without
    /// authorization enforcement.
    pub acl: Vec<ACL>,
    /// Metadata stat structure tracking node versions, timestamps, transaction zxids, and child
    /// counts.
    pub stat: Stat,
    /// Set of immediate child node name components, stored in lexicographical order.
    pub children: BTreeSet<Vec<u8>>,
    /// Creation mode flags specifying persistence, ephemeral ownership, and TTL semantics.
    pub mode: i32,
    /// Time-to-live duration in milliseconds if configured with TTL mode, or -1.
    pub ttl: i64,
}

/// Watcher notification trigger generated as a side effect of a state-mutating transaction.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Trigger {
    /// Affected znode path.
    pub path: Vec<u8>,
    /// Protocol event notification type code (`EventType.*`).
    pub event_type: i32,
}

fn trigger(path: &[u8], event_type: i32) -> Trigger {
    Trigger {
        path: path.to_vec(),
        event_type,
    }
}

/// Extracts the parent path slice of a normalized znode path, returning `None` for `"/"`.
#[must_use]
pub fn parent_of(path: &[u8]) -> Option<&[u8]> {
    if path == b"/" {
        return None;
    }
    let cut = path.iter().rposition(|b| *b == b'/')?;
    if cut == 0 {
        Some(b"/")
    } else {
        path.get(..cut)
    }
}

/// Extracts the trailing node name component of a normalized znode path.
#[must_use]
pub fn name_of(path: &[u8]) -> &[u8] {
    match path.iter().rposition(|b| *b == b'/') {
        Some(cut) => path.get(cut.saturating_add(1)..).unwrap_or_default(),
        None => path,
    }
}

/// Concatenates a parent path and child name component into a canonical absolute path.
#[must_use]
pub fn join(parent: &[u8], name: &[u8]) -> Vec<u8> {
    let mut p = parent.to_vec();
    if p != b"/" {
        p.push(b'/');
    }
    p.extend_from_slice(name);
    p
}

/// Validates znode path syntax in conformance with Java `PathUtils.validatePath`.
///
/// # Errors
///
/// Returns `ZBADARGUMENTS` if the path contains illegal characters, relative segments (`.` or
/// `..`), trailing slashes (unless permitted for sequential creation), or empty segments.
pub fn validate_path(path: &[u8], sequential: bool) -> Result<(), i32> {
    let bad = Err(rc::ZBADARGUMENTS);
    if path.first() != Some(&b'/') {
        return bad;
    }
    if path.len() == 1 {
        return Ok(());
    }
    let body = if sequential && path.last() == Some(&b'/') {
        path.get(..path.len().saturating_sub(1)).unwrap_or_default()
    } else {
        path
    };
    if body.last() == Some(&b'/') || body.contains(&0) {
        return bad;
    }
    for comp in body.split(|b| *b == b'/').skip(1) {
        if comp.is_empty() || comp == b"." || comp == b".." {
            return bad;
        }
    }
    Ok(())
}

/// Returns `true` if the creation mode requests automatic sequential name allocation.
#[must_use]
pub const fn is_sequential(mode: i32) -> bool {
    matches!(
        mode,
        MODE_PERSISTENT_SEQUENTIAL
            | MODE_EPHEMERAL_SEQUENTIAL
            | MODE_PERSISTENT_SEQUENTIAL_WITH_TTL
    )
}

/// Returns `true` if the creation mode ties node lifetime to client session lifetime.
#[must_use]
pub const fn is_ephemeral(mode: i32) -> bool {
    matches!(mode, MODE_EPHEMERAL | MODE_EPHEMERAL_SEQUENTIAL)
}

/// Returns `true` if the creation mode enforces time-to-live node expiration semantics.
#[must_use]
pub const fn is_ttl(mode: i32) -> bool {
    matches!(
        mode,
        MODE_PERSISTENT_WITH_TTL | MODE_PERSISTENT_SEQUENTIAL_WITH_TTL
    )
}

/// Parameter bundle for znode creation operations.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CreateArgs<'a> {
    /// Requested target path prefix or absolute path.
    pub path: &'a [u8],
    /// Initial payload bytes to store in the created node.
    pub data: &'a [u8],
    /// Access control list to assign to the new node.
    pub acl: &'a [ACL],
    /// Creation mode flags controlling persistence, sequence numbering, and TTL.
    pub mode: i32,
    /// Configured time-to-live duration in milliseconds, or -1.
    pub ttl: i64,
    /// Session identifier of the creating client, recorded as owner if ephemeral.
    pub session: i64,
}

/// Stateful hierarchical znode database emulating the ZooKeeper server data tree.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DataTree {
    nodes: BTreeMap<Vec<u8>, Node>,
}

impl Default for DataTree {
    fn default() -> Self {
        Self::new()
    }
}

impl DataTree {
    /// Initializes a default hierarchical namespace populated with standard root and system nodes
    /// (`/`, `/zookeeper`, `/zookeeper/config`, `/zookeeper/quota`).
    #[must_use]
    pub fn new() -> Self {
        let mut t = Self {
            nodes: BTreeMap::new(),
        };
        t.nodes.insert(b"/".to_vec(), Node::default());
        for p in [
            &b"/zookeeper"[..],
            b"/zookeeper/config",
            b"/zookeeper/quota",
        ] {
            t.insert_raw(p);
        }
        t
    }

    fn insert_raw(&mut self, path: &[u8]) {
        if let Some(parent) = parent_of(path) {
            if let Some(pn) = self.nodes.get_mut(parent) {
                pn.children.insert(name_of(path).to_vec());
                pn.stat.num_children = len_i32(pn.children.len());
            }
        }
        self.nodes.insert(
            path.to_vec(),
            Node {
                ttl: -1,
                ..Node::default()
            },
        );
    }

    /// Retrieves a reference to the znode stored at `path`, if it exists.
    #[must_use]
    pub fn get(&self, path: &[u8]) -> Option<&Node> {
        self.nodes.get(path)
    }

    /// Returns `true` if a znode exists at `path`.
    #[must_use]
    pub fn contains(&self, path: &[u8]) -> bool {
        self.nodes.contains_key(path)
    }

    /// Returns the total count of znodes present in the hierarchy, including the root node.
    #[must_use]
    pub fn len(&self) -> usize {
        self.nodes.len()
    }

    /// Returns `false`, as a valid ZooKeeper data tree always contains at least the root node.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.nodes.is_empty()
    }

    /// Returns an iterator yielding all znode paths in the tree in lexicographical order.
    pub fn paths(&self) -> impl Iterator<Item = &Vec<u8>> {
        self.nodes.keys()
    }

    /// Returns an ordered list of immediate child node name components located directly under
    /// `path`.
    ///
    /// # Errors
    ///
    /// Returns `ZNONODE` if the target parent node does not exist.
    pub fn children(&self, path: &[u8]) -> Result<Vec<Vec<u8>>, i32> {
        self.nodes
            .get(path)
            .map(|n| n.children.iter().cloned().collect())
            .ok_or(rc::ZNONODE)
    }

    /// Recursively calculates the aggregate count of all descendant znodes located beneath `path`.
    ///
    /// # Errors
    ///
    /// Returns `ZNONODE` if the target root node does not exist.
    pub fn all_children_number(&self, path: &[u8]) -> Result<i32, i32> {
        if !self.nodes.contains_key(path) {
            return Err(rc::ZNONODE);
        }
        let n = self
            .nodes
            .keys()
            .filter(|p| p.as_slice() != path && is_below(p, path))
            .count();
        Ok(len_i32(n))
    }

    /// Retrieves all ephemeral znode paths owned by `session` matching `prefix`, in
    /// lexicographical order.
    #[must_use]
    pub fn ephemerals(&self, session: i64, prefix: &[u8]) -> Vec<Vec<u8>> {
        self.nodes
            .iter()
            .filter(|(p, n)| {
                n.stat.ephemeral_owner == session && is_ephemeral(n.mode) && p.starts_with(prefix)
            })
            .map(|(p, _)| p.clone())
            .collect()
    }

    /// Evaluates and applies a znode creation request, returning the allocated path and stat
    /// metadata.
    ///
    /// Validates operations in Java `DataTree` order: path syntax, ACL validity, TTL parameters,
    /// parent existence, collision detection, and parent ephemeral restrictions. Records
    /// corresponding watcher triggers into `out`.
    ///
    /// # Errors
    ///
    /// Returns the ZooKeeper error code corresponding to the first failed precondition.
    pub fn create(
        &mut self,
        a: &CreateArgs<'_>,
        zxid: i64,
        time: i64,
        out: &mut Vec<Trigger>,
    ) -> Result<(Vec<u8>, Stat), i32> {
        let seq = is_sequential(a.mode);
        validate_path(a.path, seq)?;
        if a.path == b"/" {
            return Err(rc::ZNODEEXISTS);
        }
        if a.acl.is_empty() {
            return Err(rc::ZINVALIDACL);
        }
        if !(0..=MODE_PERSISTENT_SEQUENTIAL_WITH_TTL).contains(&a.mode) {
            return Err(rc::ZBADARGUMENTS);
        }
        if is_ttl(a.mode) && a.ttl <= 0 {
            return Err(rc::ZBADARGUMENTS);
        }
        let parent_path = parent_of(a.path).ok_or(rc::ZBADARGUMENTS)?.to_vec();
        let parent = self.nodes.get(&parent_path).ok_or(rc::ZNONODE)?;
        let mut path = a.path.to_vec();
        if seq {
            path.extend_from_slice(format!("{:010}", parent.stat.cversion).as_bytes());
        }
        validate_path(&path, false)?;
        if self.nodes.contains_key(&path) {
            return Err(rc::ZNODEEXISTS);
        }
        if is_ephemeral(parent.mode) {
            return Err(rc::ZNOCHILDRENFOREPHEMERALS);
        }
        let stat = Stat {
            czxid: zxid,
            mzxid: zxid,
            ctime: time,
            mtime: time,
            version: 0,
            cversion: 0,
            aversion: 0,
            ephemeral_owner: if is_ephemeral(a.mode) { a.session } else { 0 },
            data_length: len_i32(a.data.len()),
            num_children: 0,
            pzxid: zxid,
        };
        self.nodes.insert(
            path.clone(),
            Node {
                data: a.data.to_vec(),
                acl: a.acl.to_vec(),
                stat: stat.clone(),
                children: BTreeSet::new(),
                mode: a.mode,
                ttl: a.ttl,
            },
        );
        if let Some(pn) = self.nodes.get_mut(&parent_path) {
            pn.children.insert(name_of(&path).to_vec());
            pn.stat.cversion = pn.stat.cversion.wrapping_add(1);
            pn.stat.pzxid = zxid;
            pn.stat.num_children = len_i32(pn.children.len());
        }
        out.push(trigger(&path, EVENT_CREATED));
        out.push(trigger(&parent_path, EVENT_CHILDREN_CHANGED));
        Ok((path, stat))
    }

    /// Deletes the znode located at `path`, verifying version preconditions and ensuring the node
    /// has no children.
    ///
    /// Records watcher triggers for node deletion and parent child list modification into `out`.
    ///
    /// # Errors
    ///
    /// Returns `ZBADARGUMENTS`, `ZNONODE`, `ZBADVERSION`, or `ZNOTEMPTY` if preconditions fail.
    pub fn delete(
        &mut self,
        path: &[u8],
        version: i32,
        zxid: i64,
        out: &mut Vec<Trigger>,
    ) -> Result<(), i32> {
        validate_path(path, false)?;
        let parent_path = parent_of(path).ok_or(rc::ZBADARGUMENTS)?.to_vec();
        if !self.nodes.contains_key(&parent_path) {
            return Err(rc::ZNONODE);
        }
        let node = self.nodes.get(path).ok_or(rc::ZNONODE)?;
        if version != -1 && version != node.stat.version {
            return Err(rc::ZBADVERSION);
        }
        if !node.children.is_empty() {
            return Err(rc::ZNOTEMPTY);
        }
        self.nodes.remove(path);
        if let Some(pn) = self.nodes.get_mut(&parent_path) {
            pn.children.remove(name_of(path));
            pn.stat.cversion = pn.stat.cversion.wrapping_add(1);
            pn.stat.pzxid = zxid;
            pn.stat.num_children = len_i32(pn.children.len());
        }
        out.push(trigger(path, EVENT_DELETED));
        out.push(trigger(&parent_path, EVENT_CHILDREN_CHANGED));
        Ok(())
    }

    /// Asserts that the znode at `path` exists and satisfies expected version constraints without
    /// performing mutations.
    ///
    /// # Errors
    ///
    /// Returns `ZBADARGUMENTS`, `ZNONODE`, or `ZBADVERSION` if validation fails.
    pub fn check(&self, path: &[u8], version: i32) -> Result<(), i32> {
        validate_path(path, false)?;
        let node = self.nodes.get(path).ok_or(rc::ZNONODE)?;
        if version != -1 && version != node.stat.version {
            return Err(rc::ZBADVERSION);
        }
        Ok(())
    }

    /// Updates the payload of an existing znode, advancing modification zxids, timestamps, and
    /// data versions.
    ///
    /// Records watcher triggers for data modification into `out`.
    ///
    /// # Errors
    ///
    /// Returns `ZBADARGUMENTS`, `ZNONODE`, or `ZBADVERSION` if preconditions fail.
    pub fn set_data(
        &mut self,
        path: &[u8],
        data: &[u8],
        version: i32,
        zxid: i64,
        time: i64,
        out: &mut Vec<Trigger>,
    ) -> Result<Stat, i32> {
        validate_path(path, false)?;
        let node = self.nodes.get_mut(path).ok_or(rc::ZNONODE)?;
        if version != -1 && version != node.stat.version {
            return Err(rc::ZBADVERSION);
        }
        node.data = data.to_vec();
        node.stat.version = node.stat.version.wrapping_add(1);
        node.stat.mzxid = zxid;
        node.stat.mtime = time;
        node.stat.data_length = len_i32(data.len());
        out.push(trigger(path, EVENT_DATA_CHANGED));
        Ok(node.stat.clone())
    }

    /// Updates the access control list assigned to an existing znode and increments its ACL
    /// version counter.
    ///
    /// # Errors
    ///
    /// Returns `ZBADARGUMENTS`, `ZINVALIDACL`, `ZNONODE`, or `ZBADVERSION` if preconditions fail.
    pub fn set_acl(&mut self, path: &[u8], acl: &[ACL], version: i32) -> Result<Stat, i32> {
        validate_path(path, false)?;
        if acl.is_empty() {
            return Err(rc::ZINVALIDACL);
        }
        let node = self.nodes.get_mut(path).ok_or(rc::ZNONODE)?;
        if version != -1 && version != node.stat.aversion {
            return Err(rc::ZBADVERSION);
        }
        node.acl = acl.to_vec();
        node.stat.aversion = node.stat.aversion.wrapping_add(1);
        Ok(node.stat.clone())
    }

    /// Purges all ephemeral znodes owned by `session` in bottom-up hierarchical order, firing
    /// watcher notifications.
    pub fn kill_session(&mut self, session: i64, zxid: i64, out: &mut Vec<Trigger>) {
        let mut paths = self.ephemerals(session, b"/");
        paths.sort_by(|a, b| b.cmp(a));
        for p in paths {
            let _ = self.delete(&p, -1, zxid, out);
        }
    }
}

/// Determines whether `path` is identical to or situated strictly beneath `root` in the namespace
/// hierarchy.
#[must_use]
pub fn is_below(path: &[u8], root: &[u8]) -> bool {
    if root == b"/" {
        return path.first() == Some(&b'/');
    }
    match path.strip_prefix(root) {
        Some(rest) => rest.is_empty() || rest.first() == Some(&b'/'),
        None => false,
    }
}

fn len_i32(n: usize) -> i32 {
    i32::try_from(n).unwrap_or(i32::MAX)
}
