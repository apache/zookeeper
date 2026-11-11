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

//! Protocol constants for watch events, session states, ACL permissions, and creation modes.

/// Watch notification event types and client connection state transitions (`KeeperState`).
pub mod event {
    /// Sentinel event type indicating a connection or session state notification rather than a
    /// znode mutation.
    pub const NONE: i32 = -1;
    /// Notification dispatched when a monitored znode is created.
    pub const NODE_CREATED: i32 = 1;
    /// Notification dispatched when a monitored znode is deleted.
    pub const NODE_DELETED: i32 = 2;
    /// Notification dispatched when the data payload of a monitored znode is modified.
    pub const NODE_DATA_CHANGED: i32 = 3;
    /// Notification dispatched when the child set of a monitored znode changes.
    pub const NODE_CHILDREN_CHANGED: i32 = 4;
    /// Notification dispatched when an active data watch is explicitly removed.
    pub const DATA_WATCH_REMOVED: i32 = 5;
    /// Notification dispatched when an active child watch is explicitly removed.
    pub const CHILD_WATCH_REMOVED: i32 = 6;
    /// Notification dispatched when an active persistent or recursive watch is explicitly removed.
    pub const PERSISTENT_WATCH_REMOVED: i32 = 7;
    /// Session state indicating an active connection synchronized with a quorum member.
    pub const SYNC_CONNECTED: i32 = 3;
    /// Session state indicating a connection to a read-only ensemble member.
    pub const CONNECTED_READ_ONLY: i32 = 5;
    /// Terminal session state indicating that the ensemble has expired the session.
    pub const EXPIRED: i32 = -112;
}

/// Bitmask definitions for ZooKeeper Access Control List (ACL) permissions.
pub mod perms {
    /// Grants permission to retrieve znode data and list child nodes.
    pub const READ: i32 = 1;
    /// Grants permission to update znode data payloads.
    pub const WRITE: i32 = 2;
    /// Grants permission to create child znodes.
    pub const CREATE: i32 = 4;
    /// Grants permission to delete child znodes.
    pub const DELETE: i32 = 8;
    /// Grants administrative authority to read and modify znode ACL policies.
    pub const ADMIN: i32 = 16;
    /// Composite bitmask granting read, write, create, delete, and administrative permissions.
    pub const ALL: i32 = 31;
}

/// Creation mode flags controlling znode persistence, TTL behavior, and sequential naming.
pub mod create_mode {
    /// Persistent znode that remains in the data tree until explicitly deleted.
    pub const PERSISTENT: i32 = 0;
    /// Ephemeral znode bound to the lifetime of the creating client session.
    pub const EPHEMERAL: i32 = 1;
    /// Persistent znode suffixed with a monotonically increasing parent sequence counter.
    pub const PERSISTENT_SEQUENTIAL: i32 = 2;
    /// Ephemeral znode suffixed with a monotonically increasing parent sequence counter.
    pub const EPHEMERAL_SEQUENTIAL: i32 = 3;
    /// Container znode for leader or lock hierarchies, purged when its last child is deleted.
    pub const CONTAINER: i32 = 4;
    /// Persistent znode with a time-to-live (TTL) that expires if unmodified within the TTL window.
    pub const PERSISTENT_WITH_TTL: i32 = 5;
    /// Sequential persistent znode with a time-to-live (TTL) expiration window.
    pub const PERSISTENT_SEQUENTIAL_WITH_TTL: i32 = 6;
}

/// Watcher categories used by `checkWatches` and `removeWatches` operations.
pub mod watcher_type {
    /// Child watches registered via `getChildren`.
    pub const CHILDREN: i32 = 1;
    /// Data watches registered via `getData` or `exists`.
    pub const DATA: i32 = 2;
    /// Wildcard matching all watch categories registered on the target path.
    pub const ANY: i32 = 3;
    /// Non-recursive persistent watches registered via `addWatch`.
    pub const PERSISTENT: i32 = 4;
    /// Recursive persistent watches registered via `addWatch`.
    pub const PERSISTENT_RECURSIVE: i32 = 5;
}

/// Modes supported by the `addWatch` operation.
pub mod add_watch_mode {
    /// Persistent watch that fires on mutations to the target znode without auto-removal.
    pub const PERSISTENT: i32 = 0;
    /// Persistent watch that fires on mutations to the target znode and its entire subtree.
    pub const PERSISTENT_RECURSIVE: i32 = 1;
}
