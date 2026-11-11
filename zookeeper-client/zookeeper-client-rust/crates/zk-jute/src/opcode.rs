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

//! ZooKeeper request and sub-operation opcodes.
//!
//! Opcode values are transmitted in request headers (`RequestHeader.type_`) to identify
//! the target operation, as well as in individual sub-operation headers within composite
//! transactions (`MultiHeader.type_`).
//!
//! These integer identifiers correspond directly to `proto.h` in the standard C client
//! and `org.apache.zookeeper.ZooDefs.OpCode` in the Java client implementation.
//! Identifier names omit the legacy `ZOO_` prefix and `_OP` suffix for idiomatic Rust conventions.
//! Certain opcodes (such as container nodes, TTL nodes, persistent recursive watches, and
//! administrative inspection queries) reflect modern ZooKeeper extensions supported by
//! the protocol engine.

/// Unsolicited server-to-client watch notification event.
pub const NOTIFY: i32 = 0;
/// Create a new znode at the specified path (`CreateRequest`).
pub const CREATE: i32 = 1;
/// Delete an existing znode with conditional version verification (`DeleteRequest`).
pub const DELETE: i32 = 2;
/// Check for the existence of a znode and optionally register an existence watch (`ExistsRequest`).
pub const EXISTS: i32 = 3;
/// Retrieve the binary data and metadata statistics of a znode (`GetDataRequest`).
pub const GET_DATA: i32 = 4;
/// Update the binary data payload of a znode with conditional version checking (`SetDataRequest`).
pub const SET_DATA: i32 = 5;
/// Retrieve the Access Control List (ACL) and stat metadata for a znode (`GetACLRequest`).
pub const GET_ACL: i32 = 6;
/// Modify the Access Control List (ACL) applied to a znode (`SetACLRequest`).
pub const SET_ACL: i32 = 7;
/// List the relative child znode names under a parent path (`GetChildrenRequest`).
pub const GET_CHILDREN: i32 = 8;
/// Flush pending leader updates to ensure a read view is strictly up to date (`SyncRequest`).
pub const SYNC: i32 = 9;
/// Connection heartbeat ping packet to maintain active session validity across idle channels.
pub const PING: i32 = 11;
/// List the child znode names under a parent path, returning both children and parent `Stat`
/// (`GetChildren2Request`).
pub const GET_CHILDREN2: i32 = 12;
/// Check version precondition on a target node within an atomic multi-operation transaction
/// (`CheckVersionRequest`).
pub const CHECK: i32 = 13;
/// Execute an atomic batch of mutating operations via a single transaction envelope
/// (`MultiHeader`).
pub const MULTI: i32 = 14;
/// Create a new znode and return its generated `Stat` metadata upon creation (`Create2Request`).
pub const CREATE2: i32 = 15;
/// Dynamically reconfigure the active ensemble membership and voting topology (`ReconfigRequest`).
pub const RECONFIG: i32 = 16;
/// Verify the active registration of client side watches on the server (`CheckWatchesRequest`).
pub const CHECK_WATCHES: i32 = 17;
/// Explicitly deregister active client watches from a target path (`RemoveWatchesRequest`).
pub const REMOVE_WATCHES: i32 = 18;
/// Create a container znode whose lifecycle is automatically deleted by the server when empty.
pub const CREATE_CONTAINER: i32 = 19;
/// Server-internal opcode generated during automated container node cleanup.
pub const DELETE_CONTAINER: i32 = 20;
/// Create a znode governed by a time-to-live (TTL) expiration lease.
pub const CREATE_TTL: i32 = 21;
/// Execute a composite read-only multi transaction across multiple znodes in one round-trip.
pub const MULTI_READ: i32 = 22;
/// Gracefully terminate the client session and close the underlying transport connection.
pub const CLOSE: i32 = -11;
/// Associate client authentication credentials (scheme and auth token) with the active session.
pub const SET_AUTH: i32 = 100;
/// Re-register active data, child, and existence watches after recovering an established session
/// (`SetWatches`).
pub const SET_WATCHES: i32 = 101;
/// Perform SASL authentication token exchange during connection establishment.
pub const SASL: i32 = 102;
/// Query all ephemeral znodes owned by the current client session.
pub const GET_EPHEMERALS: i32 = 103;
/// Recursively count total descendant znodes rooted under a given path.
pub const GET_ALL_CHILDREN_NUMBER: i32 = 104;
/// Restore watches, including persistent and recursive watchers, upon session reconnection
/// (`SetWatches2`).
pub const SET_WATCHES2: i32 = 105;
/// Register a persistent or persistent-recursive watch on a znode hierarchy (`AddWatchRequest`).
pub const ADD_WATCH: i32 = 106;
/// Query the server for the authenticated user identities associated with the current session.
pub const WHO_AM_I: i32 = 107;
/// Header opcode signaling a failed multi-operation element or marking the trailing batch
/// terminator.
pub const ERROR: i32 = -1;
