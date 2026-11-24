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

//! Protocol request representations, sub-operation types, and submission results.
//!
//! A [`Request`] encapsulates an application operation, its asynchronous completion handle,
//! delivery routing rules, and any deferred input defects. The protocol engine validates
//! requests following legacy C client precedence, allocates monotonically increasing transaction
//! identifiers (xids), serializes Jute wire frames, and tracks pending completion tokens.

use core::fmt;

use zk_jute::data::ACL;
use zk_jute::{JBuffer, JString, JVec};

use crate::hooks::Tokens;

/// Execution path and dispatch strategy for delivering request completion results
/// back to the application layer.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Route {
    /// Dispatched asynchronously via the connection's dedicated output event queue.
    Queued,
    /// Delivered immediately on the thread that retrieves the completed output, functioning
    /// as an execution barrier for synchronous invocations in multithreaded runtimes.
    Direct,
}

/// Surface input anomalies and memory faults detected during raw C foreign function interface
/// marshalling.
///
/// To preserve bug-for-bug compatibility with the legacy C client, defects encountered while
/// inspecting raw pointers are captured here and deferred until the protocol engine reaches the
/// exact validation phase where the legacy C client would have failed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct InputDefect {
    /// Indicates whether a supplied byte buffer pointer was invalid or unreadable.
    pub bad_value_buffer: bool,
    /// Indicates whether the provided Access Control List was NULL, malformed, or exceeded
    /// bounds.
    pub bad_acl: bool,
    /// Indicates whether memory allocation failed during C-to-Rust input ingestion.
    pub marshal_oom: bool,
}

impl InputDefect {
    /// Determines whether any defect flags are currently asserted.
    #[must_use]
    pub const fn any(&self) -> bool {
        self.bad_value_buffer || self.bad_acl || self.marshal_oom
    }
}

/// Represents an unvalidated or validated znode path submitted by the client driver.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum PathIn {
    /// Raw unparsed bytes supplied by a C FFI caller prior to trailing NUL termination.
    /// `None` represents a NULL pointer. Subjected to legacy C path validation rules.
    Legacy(Option<Vec<u8>>),
    /// A structured, UTF-8 validated ZooKeeper path provided by a native Rust caller.
    Native(JString),
}

/// An individual sub-operation within an atomic transactional `multi` request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MultiOp {
    /// Create operation (`Create`, `Create2`, `CreateContainer`, or `CreateTTL`).
    Create {
        /// Protocol wire opcode corresponding to the specific create variant.
        op_type: i32,
        /// Target znode path for creation.
        path: PathIn,
        /// Initial node payload data.
        data: JBuffer,
        /// Access Control Lists governing permissions on the newly created node.
        acl: JVec<ACL>,
        /// Node creation mode flags (e.g., persistent, ephemeral, sequential, container, or TTL).
        mode: i32,
        /// Time-to-live duration in milliseconds when creating a TTL znode.
        ttl: i64,
        /// Marshalling defects encountered while reading sub-operation arguments.
        defect: InputDefect,
    },
    /// Delete operation (opcode 2).
    Delete {
        /// Target znode path to delete.
        path: PathIn,
        /// Expected data version of the target node, or -1 to match any version.
        version: i32,
        /// Marshalling defects encountered while reading sub-operation arguments.
        defect: InputDefect,
    },
    /// Set data operation (opcode 5).
    SetData {
        /// Target znode path whose data is to be updated.
        path: PathIn,
        /// New payload data bytes.
        data: JBuffer,
        /// Expected data version of the target node, or -1 to match any version.
        version: i32,
        /// Marshalling defects encountered while reading sub-operation arguments.
        defect: InputDefect,
    },
    /// Version check assertion (opcode 13).
    Check {
        /// Target znode path to verify.
        path: PathIn,
        /// Expected version that must match for transaction commit.
        version: i32,
        /// Marshalling defects encountered while reading sub-operation arguments.
        defect: InputDefect,
    },
    /// Read node data operation (opcode 4), valid only within native `MultiRead` transactions.
    GetData {
        /// Target znode path to read.
        path: PathIn,
    },
    /// Read child node list operation (opcode 8), valid only within native `MultiRead`
    /// transactions.
    GetChildren {
        /// Target znode path whose child list is to be retrieved.
        path: PathIn,
    },
    /// Unsupported or invalid sub-operation opcode, causing transactional validation failure.
    Other {
        /// Wire opcode received from the caller.
        op_type: i32,
    },
}

impl MultiOp {
    /// Returns the wire protocol opcode associated with this multi sub-operation.
    #[must_use]
    pub const fn op_type(&self) -> i32 {
        match self {
            Self::Create { op_type, .. } | Self::Other { op_type } => *op_type,
            Self::Delete { .. } => zk_jute::opcode::DELETE,
            Self::SetData { .. } => zk_jute::opcode::SET_DATA,
            Self::Check { .. } => zk_jute::opcode::CHECK,
            Self::GetData { .. } => zk_jute::opcode::GET_DATA,
            Self::GetChildren { .. } => zk_jute::opcode::GET_CHILDREN,
        }
    }
}

/// Borrowed variant of a transactional write sub-operation within a multi request.
///
/// Used by zero-copy submission entry points such as `Connection::submit_multi_ref`. Paths and
/// payload buffers are borrowed from the caller and encoded directly into the outbound
/// serialization buffer without intermediate heap allocations. The borrow terminates upon return.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MultiOpRef<'a> {
    /// Create operation (`Create`, `Create2`, `CreateContainer`, or `CreateTTL`).
    Create {
        /// Protocol wire opcode corresponding to the create variant.
        op_type: i32,
        /// Borrowed raw path byte slice, or `None` if the pointer was NULL.
        path: Option<&'a [u8]>,
        /// Borrowed node payload byte slice, or `None` for a null buffer.
        data: Option<&'a [u8]>,
        /// Access Control Lists governing permissions on the created node.
        acl: JVec<ACL>,
        /// Node creation mode flags.
        mode: i32,
        /// Time-to-live duration in milliseconds for TTL znodes.
        ttl: i64,
        /// Marshalling defects detected during argument extraction.
        defect: InputDefect,
    },
    /// Delete operation (opcode 2).
    Delete {
        /// Borrowed raw path byte slice, or `None` if the pointer was NULL.
        path: Option<&'a [u8]>,
        /// Expected data version of the target node, or -1 to match any version.
        version: i32,
        /// Marshalling defects detected during argument extraction.
        defect: InputDefect,
    },
    /// Set data operation (opcode 5).
    SetData {
        /// Borrowed raw path byte slice, or `None` if the pointer was NULL.
        path: Option<&'a [u8]>,
        /// Borrowed node payload byte slice, or `None` for a null buffer.
        data: Option<&'a [u8]>,
        /// Expected data version of the target node, or -1 to match any version.
        version: i32,
        /// Marshalling defects detected during argument extraction.
        defect: InputDefect,
    },
    /// Version check assertion (opcode 13).
    Check {
        /// Borrowed raw path byte slice, or `None` if the pointer was NULL.
        path: Option<&'a [u8]>,
        /// Expected version that must match for transaction commit.
        version: i32,
        /// Marshalling defects detected during argument extraction.
        defect: InputDefect,
    },
    /// Unsupported or invalid sub-operation opcode.
    Other {
        /// Wire opcode received from the caller.
        op_type: i32,
    },
}

impl MultiOpRef<'_> {
    /// Returns the wire protocol opcode associated with this borrowed sub-operation.
    #[must_use]
    pub const fn op_type(&self) -> i32 {
        match self {
            Self::Create { op_type, .. } | Self::Other { op_type } => *op_type,
            Self::Delete { .. } => zk_jute::opcode::DELETE,
            Self::SetData { .. } => zk_jute::opcode::SET_DATA,
            Self::Check { .. } => zk_jute::opcode::CHECK,
        }
    }
}

/// High-level application operation submitted to the ZooKeeper protocol engine.
///
/// Generic over `W`, the caller's watcher handle or token type.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Op<W> {
    /// Create znode operation supporting standard, container, and TTL semantics.
    Create {
        /// Target znode path for creation.
        path: PathIn,
        /// Initial node payload data.
        data: JBuffer,
        /// Access Control Lists assigned to the created node.
        acl: JVec<ACL>,
        /// Node creation mode flags.
        mode: i32,
        /// Time-to-live duration in milliseconds for TTL nodes, or negative for standard nodes.
        ttl: i64,
        /// When true, requests the extended `Create2` response containing both path and `Stat`.
        create2: bool,
    },
    /// Delete znode operation.
    Delete {
        /// Target znode path to delete.
        path: PathIn,
        /// Expected data version, or -1 to delete unconditionally.
        version: i32,
    },
    /// Node existence check operation, with optional watch registration.
    Exists {
        /// Target znode path to inspect.
        path: PathIn,
        /// Optional watcher token to register for node creation, deletion, or data changes.
        watch: Option<W>,
    },
    /// Read node data and metadata, with optional watch registration.
    GetData {
        /// Target znode path whose data is to be retrieved.
        path: PathIn,
        /// Optional watcher token to register for node deletion or data changes.
        watch: Option<W>,
    },
    /// Retrieve cluster dynamic configuration from `/zookeeper/config` without chroot prefixing.
    GetConfig {
        /// Optional watcher token to register for cluster reconfiguration events.
        watch: Option<W>,
    },
    /// Write payload data to an existing znode.
    SetData {
        /// Target znode path to update.
        path: PathIn,
        /// New payload data buffer.
        data: JBuffer,
        /// Expected data version, or -1 to overwrite unconditionally.
        version: i32,
    },
    /// Retrieve Access Control Lists and metadata for a znode.
    GetAcl {
        /// Target znode path whose ACL is being queried.
        path: PathIn,
    },
    /// Replace Access Control Lists on an existing znode.
    SetAcl {
        /// Target znode path whose ACL is being modified.
        path: PathIn,
        /// New list of ACL specifications.
        acl: JVec<ACL>,
        /// Expected ACL version, or -1 to update unconditionally.
        version: i32,
    },
    /// Retrieve child node names, with optional watch registration.
    GetChildren {
        /// Target parent znode path.
        path: PathIn,
        /// Optional watcher token to register for child additions or removals.
        watch: Option<W>,
        /// When true, requests the extended `GetChildren2` response containing child names and
        /// parent `Stat`.
        with_stat: bool,
    },
    /// Flush leader-follower state sync for a given path.
    Sync {
        /// Target znode path for synchronization.
        path: PathIn,
    },
    /// Dynamic ensemble reconfiguration request.
    Reconfig {
        /// Specification of server joining the ensemble.
        joining: JString,
        /// Specification of server leaving the ensemble.
        leaving: JString,
        /// Full replacement membership list specification.
        members: JString,
        /// Expected configuration zxid version, or -1 to apply unconditionally.
        version: i64,
    },
    /// Execute an atomic batch of write operations.
    Multi {
        /// Sequential list of transactional sub-operations.
        ops: Vec<MultiOp>,
    },
    /// Deregister active watchers locally or via server protocol interaction.
    RemoveWatches {
        /// Target znode path whose watches are being removed.
        path: PathIn,
        /// Watch type selector: 1 (Children), 2 (Data), 3 (Any), or extended native types 4 and 5.
        wtype: i32,
        /// Specific watcher instance to deregister, or `None` to clear all matching watchers.
        watcher: Option<W>,
        /// When true, clears local client watcher registration without transmitting an unwatch
        /// frame to the server.
        local: bool,
        /// When true, indicates an invocation of `zoo_remove_all_watches`.
        all: bool,
    },
    /// Register a persistent or persistent recursive watch (native ZooKeeper 3.6+ feature).
    AddWatch {
        /// Target znode path to monitor.
        path: PathIn,
        /// Watch mode flag: 0 for standard persistent, 1 for persistent recursive.
        mode: i32,
        /// Watcher token to register.
        watch: W,
    },
    /// Execute an atomic batch of read operations (native ZooKeeper 3.6+ feature).
    MultiRead {
        /// Sequential list of read sub-operations.
        ops: Vec<MultiOp>,
    },
    /// Query all ephemeral znodes owned by the current session matching a path prefix (native
    /// feature).
    GetEphemerals {
        /// Znode path prefix filter.
        prefix: PathIn,
    },
    /// Recursively calculate total count of descendant children under a path (native feature).
    GetAllChildrenNumber {
        /// Root znode path for descendant counting.
        path: PathIn,
    },
    /// Query authentication and authorization identities associated with the current connection
    /// (native feature).
    WhoAmI,
    /// Modify Access Control Lists returning updated node `Stat` metadata (native feature).
    SetAclStat {
        /// Target znode path whose ACL is being modified.
        path: PathIn,
        /// New list of ACL specifications.
        acl: JVec<ACL>,
        /// Expected ACL version, or -1 to update unconditionally.
        version: i32,
    },
}

impl<W> Op<W> {
    /// Indicates whether this operation requires native protocol extensions enabled via
    /// `Compat::native_ops`.
    #[must_use]
    pub const fn is_native_only(&self) -> bool {
        matches!(
            self,
            Self::AddWatch { .. }
                | Self::MultiRead { .. }
                | Self::GetEphemerals { .. }
                | Self::GetAllChildrenNumber { .. }
                | Self::WhoAmI
                | Self::SetAclStat { .. }
        )
    }
}

/// Fully specified protocol request combining an operation, completion handle, delivery route,
/// and defect status.
pub struct Request<T: Tokens> {
    /// Protocol operation payload and parameters.
    pub op: Op<T::Watcher>,
    /// Opaque completion token returned to the caller exactly once upon completion or failure.
    pub token: T::Completion,
    /// Dispatch routing policy governing how the completion notification is delivered.
    pub route: Route,
    /// Surface marshalling defects detected during foreign function interface parameter parsing.
    pub defect: InputDefect,
}

/// Represents successful submission and admission of an operation into the protocol pipeline.
pub enum Submitted<T: Tokens> {
    /// Operation successfully serialized and enqueued on the outbound pipeline under the assigned
    /// transaction identifier.
    Queued {
        /// Assigned protocol transaction identifier (xid).
        xid: i32,
    },
    /// Watcher deregistration completed immediately within the local client table without network
    /// transmission.
    Local(T::Completion),
}

/// Execution stage at which request submission or admission processing encountered an error.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Stage {
    /// Validation failure during parameter checks, state validation, or path formatting prior to
    /// frame allocation.
    Validate,
    /// Failure during serialization buffer allocation, frame formatting, or outbound queueing.
    Enqueue,
    /// Request rejected because native-only operations are disabled in current compatibility
    /// settings.
    NotEnabled,
    /// Request throttled and rejected because the outbound queue exceeded `max_pending` capacity.
    TooManyPending,
}

/// Detailed diagnostic and token recovery information returned upon request submission failure.
pub struct SubmitError<T: Tokens> {
    /// Processing stage where the rejection occurred.
    pub stage: Stage,
    /// Standard ZooKeeper legacy return code corresponding to the failure.
    pub code: i32,
    /// Intact completion token returned to caller ownership for error dispatch.
    pub token: T::Completion,
}

/// Error metadata returned when session authentication credential registration fails during
/// admission.
pub struct AddAuthError<T: Tokens> {
    /// Standard ZooKeeper legacy return code indicating the reason for rejection.
    pub code: i32,
    /// Authentication completion token returned to caller ownership, if one was provided.
    pub done: Option<T::Auth>,
}

impl<T: Tokens> fmt::Debug for Request<T>
where
    T::Watcher: fmt::Debug,
{
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Request")
            .field("op", &self.op)
            .field("route", &self.route)
            .field("defect", &self.defect)
            .finish_non_exhaustive()
    }
}

impl<T: Tokens> fmt::Debug for Submitted<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Queued { xid } => f.debug_struct("Queued").field("xid", xid).finish(),
            Self::Local(_) => f.write_str("Local(..)"),
        }
    }
}

impl<T: Tokens> fmt::Debug for SubmitError<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SubmitError")
            .field("stage", &self.stage)
            .field("code", &self.code)
            .finish_non_exhaustive()
    }
}

impl<T: Tokens> fmt::Debug for AddAuthError<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AddAuthError")
            .field("code", &self.code)
            .field("done", &self.done.is_some())
            .finish()
    }
}

/// Maximum allowable time-to-live value for TTL znodes in milliseconds (`0xFF_FFFF_FFFF`,
/// ~34 years).
pub const MAX_TTL: i64 = 0xFF_FFFF_FFFF;

/// Validates time-to-live parameters according to ZooKeeper specification rules.
///
/// TTL modes (mode 5 container/TTL and mode 6 ephemeral TTL) require positive duration
/// values within `1..=MAX_TTL`. Non-TTL node creation modes require negative TTL values.
/// In legacy C client ordering, this validation precedes handle validation checks.
///
/// # Errors
///
/// Returns `ZBADARGUMENTS` if the TTL does not conform to the expected range for the given
/// create mode.
pub fn check_ttl(mode: i32, ttl: i64) -> Result<(), i32> {
    let ok = if crate::legacy::path::is_ttl(mode) {
        (1..=MAX_TTL).contains(&ttl)
    } else {
        ttl < 0
    };
    if ok {
        Ok(())
    } else {
        Err(zk_jute::rc::ZBADARGUMENTS)
    }
}
