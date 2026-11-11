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

//! Definitive C ABI type declarations and structural layouts exported to `zkrs.h`.
//!
//! This module serves as the single source of truth for all data structures, enumerations, and
//! constants shared across the Foreign Function Interface (FFI) boundary with the C compatibility
//! shim (`zookeeper-client-rust/c/shim/zkrs.h`). During the build process, `cbindgen` parses these
//! definitions to synthesize matching C header declarations. To ensure absolute parity between Rust
//! and C symbols, naming conventions directly mirror their C counterparts.
//!
//! All types defined herein are explicitly annotated with `#[repr(C)]` and strictly constrained to
//! primitive scalar integers and raw pointers, guaranteeing cross-language ABI compatibility across
//! supported target architectures. The module maintains minimal dependencies (relying solely on
//! `core` and `std`), enabling standalone harnesses—such as fuzz targets and the `zk-ffi-layout`
//! compile-time verification tool—to include this file directly via `#[path]` attributes and assert
//! layout equivalence against the generated header.
//!
//! Explicit numeric assignments across enumerations and bitflags are strictly normative. Any
//! divergence in ordinal values or struct member offsets will fail CI verification via `cbindgen
//! --verify` until downstream C shim headers are synchronized.

#![allow(non_camel_case_types, non_snake_case)]

use std::ffi::{c_char, c_double, c_int, c_long, c_void};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6};
use std::ptr;

/// Opaque handle type representing an active ZooKeeper client session instance.
///
/// Across the C ABI boundary, client handles are passed as `const zkrs_handle *` pointers,
/// which internally wrap an `Arc::into_raw(Arc<Inner>)` reference count. The C runtime
/// treats this pointer as an untyped opaque token and must never dereference or inspect
/// its internal layout directly.
pub struct zkrs_handle {
    _opaque: [u8; 0],
}

/// Bounded slice view into an immutable contiguous byte sequence across the FFI boundary.
///
/// This structure provides a standardized representation for byte payloads (such as SASL
/// negotiation tokens and binary znode payloads). In accordance with ZooKeeper Jute serialization
/// semantics, an explicit `is_null == 1` sentinel indicates a serialized null token, in which case
/// `p` is null and `len` is 0. Non-null byte sequences require a non-null base pointer `p` with
/// `len` constrained to positive values within `0x7fffffff`, fitting safely within signed 32-bit
/// integer ranges (`c_int`).
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_bytes {
    /// Pointer to the initial byte of the contiguous payload, or null if representing a null token.
    pub p: *const u8,
    /// Length of the byte buffer in bytes (guaranteed not to exceed `0x7fffffff`).
    pub len: u32,
    /// Sentinel flag indicating whether this buffer represents a Jute null token (1 if null, 0
    /// otherwise).
    pub is_null: u8,
}

impl zkrs_bytes {
    /// Constructs a canonical Jute null byte token (`{ p: null, len: 0, is_null: 1 }`).
    #[must_use]
    pub const fn null() -> Self {
        Self {
            p: ptr::null(),
            len: 0,
            is_null: 1,
        }
    }
}

/// Operational discriminator for Cyrus SASL authentication bridge invocations.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum zkrs_sasl_kind {
    /// Initiates a new Cyrus SASL client authentication context with configured local and peer
    /// socket endpoints.
    ZKRS_SASL_START = 1,
    /// Submits an intermediate authentication challenge token received from the ZooKeeper server
    /// into Cyrus SASL.
    ZKRS_SASL_STEP = 2,
    /// Deallocates and disposes of the underlying Cyrus SASL client negotiation context without
    /// producing a result.
    ZKRS_SASL_DISPOSE = 3,
}

/// Outcome status returned by the C Cyrus SASL processing layer.
///
/// The `ZKRS_SASL_STALE` status is emitted by the C glue when encountering an outdated session
/// generation, signaling that negotiation was superseded. On the Rust side of the bridge, any
/// status code other than `ZKRS_SASL_CONTINUE` or `ZKRS_SASL_DONE` is treated as a terminal
/// authentication failure.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum zkrs_sasl_status {
    /// Negotiation requires additional challenge-response round-trips (`SASL_CONTINUE`).
    ZKRS_SASL_CONTINUE = 0,
    /// Authentication has concluded successfully (`SASL_OK`).
    ZKRS_SASL_DONE = 1,
    /// SASL negotiation or internal glue processing encountered an unrecoverable error.
    #[allow(dead_code, reason = "only the C glue writes it")]
    ZKRS_SASL_FAIL = -1,
    /// Request generation mismatch: the call was discarded without invoking Cyrus SASL because a
    /// newer session began.
    #[allow(dead_code, reason = "only the C glue writes it")]
    ZKRS_SASL_STALE = -2,
}

/// Outbox payload describing a SASL authentication action delegated to the C Cyrus layer.
///
/// Referenced byte buffers point directly into the caller's outbox storage ring. These references
/// remain valid until either the corresponding `zkrs_sasl_result` callback concludes or the outbox
/// moves forward to subsequent entries.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_sasl_call {
    /// Specific SASL action requested, corresponding to [`zkrs_sasl_kind`].
    pub kind: u32,
    /// Monotonically increasing session generation counter used to detect and discard stale
    /// responses.
    pub r#gen: u32,
    /// Binary representation of the local socket address (`sockaddr_in` or `sockaddr_in6`) for
    /// START actions.
    pub local: zkrs_bytes,
    /// Binary representation of the remote peer socket address for START actions.
    pub peer: zkrs_bytes,
    /// Inbound challenge token received from the ZooKeeper server for STEP actions.
    pub token: zkrs_bytes,
}

/// Authentication response payload produced by Cyrus SASL following a START or STEP operation.
///
/// The C shim guarantees that memory backing `mech` and `token` remains valid for the duration of
/// the `zkrs_sasl_result` call; Rust copies any required data into managed storage before the
/// function returns.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_sasl_out {
    /// Status code indicating the outcome of the SASL operation ([`zkrs_sasl_status`]).
    pub status: i32,
    /// Selected SASL authentication mechanism identifier (populated during START operations).
    pub mech: zkrs_bytes,
    /// Outbound client authentication response token to be transmitted to the ZooKeeper cluster.
    pub token: zkrs_bytes,
}

/// Execution control directive returned by driver stepping and initialization routines.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum zkrs_step {
    /// Operation completed synchronously; the output return code pointer contains the final result.
    ZKRS_DONE = 0,
    /// Execution suspended at a synchronization barrier; the C caller must drain the outbox before
    /// invoking `zkrs_resume`.
    ZKRS_MORE = 1,
    /// Session shutdown sequence completed; after draining pending outbox items, C must deallocate
    /// `zh` and release resources.
    ZKRS_RELEASE = 2,
    /// Signals the background thread event loop in multithreaded mode to terminate cleanly.
    ZKRS_EXIT = 3,
}

/// Classification tag distinguishing the active payload type stored in an outbox event slot.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum zkrs_item_type {
    /// Diagnostic log record destined for formatted emission via legacy C logging routines.
    ZKRS_ITEM_LOG = 1,
    /// Asynchronous request completion callback, synchronous return container, or authentication
    /// confirmation.
    ZKRS_ITEM_RESULT = 2,
    /// ZooKeeper watcher notification for znode mutations or session state transitions.
    ZKRS_ITEM_WATCH = 3,
    /// Synchronization barrier preserving legacy semantics for the `zoo_lock_auth` authentication
    /// critical section.
    ZKRS_ITEM_AUTH_LOCKPOINT = 4,
    /// Delegated Cyrus SASL negotiation lifecycle request (initial handshake, challenge step, or
    /// teardown).
    ZKRS_ITEM_SASL = 5,
    /// Internal thread management directive (thread join or detach) specific to multithreaded
    /// client configurations.
    ZKRS_ITEM_THREAD = 6,
}

/// Thread lifecycle operation requested by multithreaded outbox management.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum zkrs_thread_op {
    /// Requests the caller to synchronize and join the background worker thread corresponding to
    /// the specified role.
    ZKRS_THREAD_JOIN = 1,
    /// Directs the currently executing worker thread to detach itself prior to termination.
    ZKRS_THREAD_DETACH = 2,
}

/// Result status classification returned by request submission entry points.
///
/// The C shim passes this code into `zks_funnel` to synthesize the appropriate legacy ZooKeeper
/// return code (`ZOK`, `ZBADARGUMENTS`, etc.).
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum zkrs_status {
    /// Request validated and submitted successfully; the associated tracking token is retained for
    /// completion dispatch.
    ZKRS_OK = 0,
    /// Local watch removal operation completed immediately without server communication; token is
    /// freed without callback.
    ZKRS_OK_LOCAL = 1,
    /// Request rejected during initial pre-flight validation due to invalid znode path syntax or
    /// unsupported TTL values.
    ZKRS_EARLY_BADARGS = 2,
    /// Request rejected due to incompatible session state (e.g. session expired, disconnected, or
    /// unauthenticated).
    ZKRS_EARLY_STATE = 3,
    /// Request submission aborted during payload encoding, packet serialization, or connection
    /// teardown.
    ZKRS_LATE = 4,
    /// Multi-operation transaction contained an unsupported or unrecognized sub-operation opcode.
    ZKRS_UNIMPLEMENTED = 5,
    /// Watch removal request did not match any active watcher registration.
    ZKRS_NOWATCHER = 6,
    /// System-level failure encountered, such as memory allocation exhaustion or negative
    /// certificate lengths.
    ZKRS_SYSTEM = 7,
}

/// Thread role assigned to external application threads interacting with the client library.
#[allow(dead_code, reason = "the C shim reads it through zkrs.h")]
pub const ZKRS_ROLE_FOREIGN: u32 = 0;
/// Thread role designating the thread executing the `zookeeper_init` initialization routine.
pub const ZKRS_ROLE_INIT: u32 = 1;
/// Thread role designating the multithreaded network I/O event processing thread.
pub const ZKRS_ROLE_IO: u32 = 2;
/// Thread role designating the multithreaded asynchronous callback dispatch worker thread.
pub const ZKRS_ROLE_DISPATCH: u32 = 3;

/// Runtime client flavor selector corresponding to the multithreaded `libzookeeper_mt` runtime
/// architecture.
pub const ZKRS_FLAVOR_MT: u32 = 1;
/// Runtime client flavor selector corresponding to the single threaded `libzookeeper_st`
/// event-driven architecture.
#[allow(dead_code, reason = "the C shim reads it through zkrs.h")]
pub const ZKRS_FLAVOR_ST: u32 = 2;

/// Delivery mode routing completions and watcher events through the session's asynchronous FIFO
/// queue.
#[allow(dead_code, reason = "the C shim reads it through zkrs.h")]
pub const ZKRS_DELIVER_QUEUED: u32 = 0;
/// Delivery mode executing completions directly on the calling thread for synchronous and
/// authentication calls.
pub const ZKRS_DELIVER_DIRECT: u32 = 1;

/// Indicates that an operation outcome originated from a canonical ZooKeeper cluster response
/// packet.
pub const ZKRS_ORIGIN_REPLY: u32 = 0;
/// Indicates that an operation was terminated locally as part of session teardown or disconnection.
pub const ZKRS_ORIGIN_TEARDOWN: u32 = 1;

/// Request completion type indicator for void operations (`COMPLETION_VOID`, e.g. delete or sync).
pub const ZKRS_K_VOID: u32 = 0;
/// Request completion type indicator for stat operations (`COMPLETION_STAT`, e.g. exists).
pub const ZKRS_K_STAT: u32 = 1;
/// Request completion type indicator for data operations (`COMPLETION_DATA`, e.g. get_data).
pub const ZKRS_K_DATA: u32 = 2;
/// Request completion type indicator for string list operations (`COMPLETION_STRINGLIST`, e.g.
/// get_children).
pub const ZKRS_K_STRINGLIST: u32 = 3;
/// Request completion type indicator for string list and stat operations
/// (`COMPLETION_STRINGLIST_STAT`, e.g. get_children2).
pub const ZKRS_K_STRINGLIST_STAT: u32 = 4;
/// Request completion type indicator for ACL retrieval operations (`COMPLETION_ACLLIST`, e.g.
/// get_acl).
pub const ZKRS_K_ACLLIST: u32 = 5;
/// Request completion type indicator for path creation operations (`COMPLETION_STRING`, e.g.
/// create).
pub const ZKRS_K_STRING: u32 = 6;
/// Request completion type indicator for atomic multi-transactions (`COMPLETION_MULTI`, e.g.
/// zoo_multi).
pub const ZKRS_K_MULTI: u32 = 7;
/// Request completion type indicator for path creation returning stat metadata
/// (`COMPLETION_STRING_STAT`, e.g. create2).
pub const ZKRS_K_STRING_STAT: u32 = 8;

/// Operation identifier for node creation (`zoo_acreate` and `zoo_acreate2`).
pub const ZKRS_OP_CREATE: u32 = 1;
/// Operation identifier for node deletion (`zoo_adelete`).
pub const ZKRS_OP_DELETE: u32 = 2;
/// Operation identifier for node existence verification (`zoo_awexists`).
pub const ZKRS_OP_EXISTS: u32 = 3;
/// Operation identifier for node data and stat retrieval (`zoo_awget`).
pub const ZKRS_OP_GETDATA: u32 = 4;
/// Operation identifier for node data mutation (`zoo_aset`).
pub const ZKRS_OP_SETDATA: u32 = 5;
/// Operation identifier for access control list retrieval (`zoo_aget_acl`).
pub const ZKRS_OP_GETACL: u32 = 6;
/// Operation identifier for access control list modification (`zoo_aset_acl`).
pub const ZKRS_OP_SETACL: u32 = 7;
/// Operation identifier for child node name enumeration (`zoo_awget_children`).
pub const ZKRS_OP_GETCHILDREN: u32 = 8;
/// Operation identifier for child node name enumeration with parent node stat metadata
/// (`zoo_awget_children2`).
pub const ZKRS_OP_GETCHILDREN2: u32 = 9;
/// Operation identifier for cluster state synchronization (`zoo_async`).
pub const ZKRS_OP_SYNC: u32 = 10;
/// Operation identifier for cluster configuration retrieval (`zoo_awgetconfig`).
pub const ZKRS_OP_GETCONFIG: u32 = 11;
/// Operation identifier for dynamic cluster configuration (`zoo_areconfig`).
pub const ZKRS_OP_RECONFIG: u32 = 12;
/// Operation identifier for atomic multi-operation transaction batches (`zoo_amulti`).
pub const ZKRS_OP_MULTI: u32 = 13;
/// Operation identifier for watcher deregistration (`zoo_aremove_watches` and
/// `zoo_aremove_all_watches`).
pub const ZKRS_OP_REMOVE_WATCHES: u32 = 14;

/// Request modifier flag indicating that a create operation should return node stat metadata
/// (`create2`).
pub const ZKRS_F_CREATE2: u32 = 0x01;
/// Request modifier flag suppressing client side chroot path prefixing (used for cluster
/// configuration znodes).
#[allow(dead_code, reason = "the C shim reads it through zkrs.h")]
pub const ZKRS_F_RAW_PATH: u32 = 0x02;
/// Request modifier flag specifying that all watchers matching the criteria should be removed.
pub const ZKRS_F_REMOVE_ALL: u32 = 0x04;
/// Request modifier flag specifying that watch removal should occur purely in local client state
/// without cluster communication.
pub const ZKRS_F_LOCAL: u32 = 0x08;
/// Flag indicating that internal memory allocation failed within the C shim during authentication
/// credential registration.
pub const ZKRS_AUTH_F_ALLOC_FAILED: u32 = 0x01;
/// Special return code sentinel signaling that initialization has paused at the SASL authentication
/// phase.
pub const ZKRS_INIT_SASL_POINT: i32 = -1;

/// Address family discriminator designating standard IPv4 socket addresses (`AF_INET`).
pub const ZKRS_AF_INET: u32 = 4;
/// Address family discriminator designating standard IPv6 socket addresses (`AF_INET6`).
pub const ZKRS_AF_INET6: u32 = 6;

/// Log argument discriminator: 64-bit signed integer value (`int64_t`).
pub const ZKRS_ARG_I64: u32 = 0;
/// Log argument discriminator: 64-bit unsigned integer value (`uint64_t`).
pub const ZKRS_ARG_U64: u32 = 1;
/// Log argument discriminator: pointer address represented as an unsigned integer (`uintptr_t`).
pub const ZKRS_ARG_PTR: u32 = 2;
/// Log argument discriminator: NUL-terminated C string reference ([`zkrs_str`]).
pub const ZKRS_ARG_STR: u32 = 3;
/// Log argument discriminator: formatted network socket endpoint ([`zkrs_sockaddr_view`]).
pub const ZKRS_ARG_ENDPOINT: u32 = 4;
/// Log argument discriminator: system error message string obtained via `strerror(err)`.
pub const ZKRS_ARG_ERRNO_STR: u32 = 5;
/// Log argument discriminator: address resolution error message derived from `gai_rc` and system
/// errno.
pub const ZKRS_ARG_GAI_STR: u32 = 6;

/// Thread-local outbox storage container allocated on the C stack during driver stepping.
///
/// Encapsulates pending outbox events queued for emission during a single driver execution step.
/// The `impl` pointer remains null until the Rust runtime enqueues the first item and is accessed
/// exclusively by Rust.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_outbox {
    /// Opaque pointer to the internal Rust outbox buffer instance, or null if unallocated.
    pub r#impl: *mut c_void,
}

/// Borrowed reference to a NUL-terminated C string across the ABI boundary.
///
/// In compliance with Jute protocol conventions, null string values are represented with `ptr` set
/// to null.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_str {
    /// Pointer to character data guaranteed to be terminated by a NUL byte, or null for null
    /// strings.
    pub ptr: *const c_char,
    /// Byte length of the string excluding the terminating NUL byte.
    pub len: i32,
}

/// Bounded raw byte buffer view representing znode payloads.
///
/// In accordance with ZooKeeper semantics, null node payloads are designated by setting `len` to
/// -1.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_buf {
    /// Pointer to the byte payload data in memory, or null if the payload is empty or null.
    pub ptr: *const c_char,
    /// Byte length of the payload, or -1 representing a null znode data payload.
    pub len: i32,
}

/// Fixed ABI layout representation of ZooKeeper znode metadata (`struct Stat`).
///
/// Memory writers perform field-by-field marshaling from this canonical representation into the
/// legacy C client's `Stat` structure.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct zkrs_stat {
    /// Transaction ID of the change that caused this znode to be created.
    pub czxid: i64,
    /// Transaction ID of the change that last modified this znode.
    pub mzxid: i64,
    /// Milliseconds since epoch when this znode was created.
    pub ctime: i64,
    /// Milliseconds since epoch when this znode was last modified.
    pub mtime: i64,
    /// Modification version number of the node's data.
    pub version: i32,
    /// Modification version number of the node's children list.
    pub cversion: i32,
    /// Modification version number of the node's access control list (ACL).
    pub aversion: i32,
    /// Session ID of the ephemeral node's owner, or 0 for persistent znodes.
    pub ephemeralOwner: i64,
    /// Size of the data field in bytes.
    pub dataLength: i32,
    /// Number of direct child znodes beneath this node.
    pub numChildren: i32,
    /// Transaction ID of the change that last modified children of this znode.
    pub pzxid: i64,
}

/// Read-only view of a single Access Control List entry across the FFI boundary.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_acl_view {
    /// Bitmask of granted ZooKeeper permissions (read, write, create, delete, admin).
    pub perms: i32,
    /// Authentication scheme identifier (e.g. "digest", "ip", "world").
    pub scheme: zkrs_str,
    /// Identity descriptor valid within the designated authentication scheme.
    pub id: zkrs_str,
}

/// Individual sub-operation result view contained within an atomic multi-transaction response.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_multi_op {
    /// Legacy ZooKeeper error code resulting from the execution of this sub-operation.
    pub err: i32,
    /// Non-zero flag indicating that this specific sub-operation encountered a failure.
    pub failed: i32,
    /// Resulting path for create sub-operations with chroot prefix stripped, or null for non-create
    /// ops.
    pub path: zkrs_str,
    /// Pointer to stat metadata returned by set sub-operations, or null if not applicable.
    pub stat: *const zkrs_stat,
}

/// Discriminant union representing an individual structured argument passed to the logging
/// subsystem.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_log_arg {
    /// Argument type tag indicating which union variant field is populated ([`ZKRS_ARG_I64`]
    /// through [`ZKRS_ARG_GAI_STR`]).
    pub kind: u32,
    /// Signed 64-bit integer argument value.
    pub i: i64,
    /// Unsigned 64-bit integer argument value.
    pub u: u64,
    /// Pointer address argument value.
    pub p: usize,
    /// Borrowed string argument value.
    pub s: zkrs_str,
    /// Formatted socket address argument value.
    pub ep: zkrs_sockaddr_view,
    /// Return code from `getaddrinfo` resolution routines.
    pub gai_rc: i32,
    /// Standard system errno value associated with resolution or I/O failures.
    pub err: i32,
}

/// Structured diagnostic log event emitted from the core driver for rendering by the C shim.
///
/// Log rendering in the C layer formats the pinned format string corresponding to `site` using the
/// provided argument vector.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_log_view {
    /// Destination log severity level corresponding to `ZOO_LOG_LEVEL_*`.
    pub level: u32,
    /// Call-site identifier representing the index into the canonical `zk_proto::LogSite::ALL`
    /// catalog.
    pub site: u32,
    /// Timestamp wall-clock seconds generated when the log record was created.
    pub tv_sec: i64,
    /// Timestamp fractional microseconds component.
    pub tv_usec: i32,
    /// Contiguous array of structured log arguments matching the format string placeholders.
    pub args: *const zkrs_log_arg,
    /// Count of arguments present in the `args` array.
    pub nargs: u32,
}

/// Immutable view of an asynchronous or synchronous operation completion payload.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_result_view {
    /// Completion signature category indicating which callback flavor to invoke ([`ZKRS_K_VOID`]
    /// through [`ZKRS_K_STRING_STAT`]).
    pub kind: u32,
    /// Provenance of the result indicating whether it came from a server response or local teardown
    /// ([`ZKRS_ORIGIN_*`]).
    pub origin: u32,
    /// Legacy ZooKeeper return code (`ZOK`, `ZNONODE`, etc.) passed to client callback handlers.
    pub rc: i32,
    /// Non-zero flag indicating that response decoding failed or was aborted before packet parsing.
    pub failed: i32,
    /// Node data payload buffer view for get-data operations.
    pub data: zkrs_buf,
    /// Pointer to znode stat metadata, or null if not applicable to this completion kind.
    pub stat: *const zkrs_stat,
    /// Normalized znode path with chroot prefix removed for create and sync operations.
    pub path: zkrs_str,
    /// Array of child node name strings for get-children operations.
    pub children: *const zkrs_str,
    /// Number of child node names present, or -1 representing a Jute null vector.
    pub nchildren: i32,
    /// Array of access control list entries for get-ACL operations.
    pub acl: *const zkrs_acl_view,
    /// Number of ACL entries present, or -1 representing a Jute null vector.
    pub nacl: i32,
    /// Array of sub-operation transaction results for multi-op completions.
    pub ops: *const zkrs_multi_op,
    /// Number of sub-operation results contained in `ops`.
    pub nops: i32,
    /// Structured log records queued during execution to be emitted immediately prior to invoking
    /// the callback.
    pub delivery_logs: *const zkrs_log_view,
    /// Number of structured delivery log records present.
    pub n_delivery_logs: u32,
}

/// Watcher registration descriptor encapsulating a legacy C `(watcher_fn, void *context)` closure
/// pair.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct zkrs_watcher {
    /// Function pointer address of the application watcher callback routine.
    pub r#fn: usize,
    /// Opaque user context pointer passed into the watcher callback upon invocation.
    pub ctx: usize,
}

/// Outbox view representing a triggered ZooKeeper watch notification or session event.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_watch_event_view {
    /// Watcher event type code (`ZOO_CREATED_EVENT`, `ZOO_DELETED_EVENT`, etc.), or -1 for session
    /// state events.
    pub r#type: i32,
    /// Session connection state (`ZOO_CONNECTED_STATE`, `ZOO_EXPIRED_SESSION_STATE`, etc.).
    pub state: i32,
    /// Affected znode path with chroot stripped, or an empty string for session-level state
    /// changes.
    pub path: zkrs_str,
    /// Contiguous array of registered watcher targets to be notified in order of registration.
    pub list: *const zkrs_watcher,
    /// Count of watcher callback targets in `list`.
    pub n: u32,
    /// Structured delivery log records to be emitted prior to executing watcher callbacks.
    pub delivery_logs: *const zkrs_log_view,
    /// Number of delivery log records.
    pub n_delivery_logs: u32,
}

/// Tagged union slot representing a single action item queued in the driver outbox.
///
/// Only members corresponding to the active `type` discriminator contain valid data.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_item {
    /// Discriminator identifying the active payload variant ([`zkrs_item_type`]).
    pub r#type: u32,
    /// Active when `type == ZKRS_ITEM_LOG`: structured log event for C emission.
    pub log: *const zkrs_log_view,
    /// Active when `type == ZKRS_ITEM_RESULT`: opaque request token whose ownership transfers to C.
    pub token: usize,
    /// Active when `type == ZKRS_ITEM_RESULT`: operation completion details and result data.
    pub result: *const zkrs_result_view,
    /// Active when `type == ZKRS_ITEM_WATCH`: watch event notification details and targets.
    pub watch: *const zkrs_watch_event_view,
    /// Active when `type == ZKRS_ITEM_SASL`: Cyrus SASL negotiation request details.
    pub sasl: *const zkrs_sasl_call,
    /// Active when `type == ZKRS_ITEM_THREAD`: thread lifecycle operation ([`zkrs_thread_op`]).
    pub thread_op: u32,
    /// Active when `type == ZKRS_ITEM_THREAD`: target worker thread role ([`ZKRS_ROLE_IO`] or
    /// [`ZKRS_ROLE_DISPATCH`]).
    pub thread_role: u32,
}

/// Common parameter envelope marshaling client request arguments from legacy C `zoo_a*` entry
/// points.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_request {
    /// Requested ZooKeeper operation code ([`ZKRS_OP_*`]).
    pub op: u32,
    /// Request modifier bitflags ([`ZKRS_F_*`]).
    pub flags: u32,
    /// Null-terminated application target path, or null for pathless operations.
    pub path: *const c_char,
    /// Pointer to payload data buffer for create and set operations.
    pub data: *const c_char,
    /// Length of payload data buffer in bytes.
    pub datalen: i32,
    /// Access control list vector (`const struct ACL_vector *`), or null if omitted.
    pub acl: *const c_void,
    /// Node creation mode bitmask (ephemeral, sequential, container, TTL).
    pub mode: i32,
    /// Time-To-Live duration in milliseconds for TTL create modes.
    pub ttl: i64,
    /// Expected znode version for optimistic concurrency checks, or -1 to match any version.
    pub version: i32,
    /// Expected configuration version for cluster reconfiguration requests.
    pub reconfig_version: i64,
    /// Comma-separated list of joining cluster member specifications, or null.
    pub joining: *const c_char,
    /// Comma-separated list of leaving cluster member specifications, or null.
    pub leaving: *const c_char,
    /// Comma-separated list of new cluster membership specifications, or null.
    pub members: *const c_char,
    /// Watcher callback function pointer, or 0 if no watcher is requested.
    pub watch_fn: usize,
    /// User context pointer to be passed to the watcher callback.
    pub watch_ctx: usize,
    /// Watcher event type filter for watch removal operations.
    pub wtype: i32,
    /// caller supplied array of sub-operations for multi transactions (`const zoo_op_t *`).
    pub ops: *const c_void,
    /// Length of the multi-op prefix to be executed.
    pub ops_len: i32,
    /// Total count of operations specified in the caller's multi request.
    pub count: i32,
}

/// Network I/O readiness interest status returned to single threaded event loops by
/// `zkrs_interest`.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct zkrs_interest_out {
    /// Underlying socket file descriptor, or -1 if no active connection exists.
    pub fd: isize,
    /// Bitmask of monitored events (`ZOOKEEPER_READ`, `ZOOKEEPER_WRITE`).
    pub interest: i32,
    /// Next deadline timeout duration expressed in milliseconds.
    pub timeout_ms: i64,
}

/// Normalized fixed-layout representation of IPv4 and IPv6 socket endpoint addresses.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct zkrs_sockaddr_view {
    /// Protocol address family ([`ZKRS_AF_INET`], [`ZKRS_AF_INET6`], or 0 for uninitialized).
    pub family: u32,
    /// Port number expressed in host byte order.
    pub port: u16,
    /// Raw IP address bytes in network byte order; IPv4 addresses occupy the leading 4 bytes.
    pub addr: [u8; 16],
    /// IPv6 flow information header value (unused for IPv4).
    pub flowinfo: u32,
    /// IPv6 scope identifier value (unused for IPv4).
    pub scope_id: u32,
}

/// Parameter block provided by the C shim to initialize a new client session via `zkrs_handle_new`.
///
/// String pointers reference C-allocated memory borrowed strictly for the duration of the
/// initialization call.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_init_args {
    /// Client execution flavor selecting the driver loop model ([`ZKRS_FLAVOR_MT`] or
    /// [`ZKRS_FLAVOR_ST`]).
    pub flavor: u32,
    /// Comma-separated ZooKeeper server connection string optionally containing a chroot path
    /// suffix.
    pub hosts: *const c_char,
    /// Negotiated session timeout in milliseconds.
    pub recv_timeout: i32,
    /// Non-zero flag indicating whether a previous session identity is supplied for reconnection.
    pub have_client_id: u32,
    /// Previous session identifier for session resumption.
    pub client_id: i64,
    /// Previous session authentication password buffer for session resumption.
    pub client_passwd: [u8; 16],
    /// Client initialization bitflags passed from legacy `zookeeper_init`.
    pub flags: i32,
    /// Non-zero flag indicating that TLS encryption was requested via `zookeeper_init_ssl`.
    pub tls: u32,
    /// Filesystem path to Certificate Authority (CA) bundle for TLS verification, or null.
    pub tls_ca: *const c_char,
    /// Filesystem path to client X.509 certificate for mutual TLS authentication, or null.
    pub tls_cert: *const c_char,
    /// Filesystem path to client private key for mutual TLS authentication, or null.
    pub tls_key: *const c_char,
    /// Passphrase unlocking the client private key, or null if unencrypted.
    pub tls_passwd: *const c_char,
    /// Non-zero flag permitting TLS configuration to read standard environment variables
    /// (`SSL_CERT_FILE`, `SSL_CERT_DIR`).
    pub tls_env_allowed: u32,
    /// Non-zero flag indicating that SASL parameters were configured via `zookeeper_init_sasl`.
    pub sasl: u32,
    /// Initial seed value used to initialize the transaction ID generator, typically derived from
    /// `time(0)`.
    pub xid_seed: i32,
    /// Non-zero flag indicating whether `SOCK_CLOEXEC` socket creation behavior was enabled at
    /// compile time.
    pub sock_cloexec: u32,
    /// Compile-time signedness of `char` in the host C compiler (1 for signed, 0 for unsigned).
    pub c_char_signed: u32,
}

/// Virtual function dispatch table providing callbacks into host C environment facilities.
///
/// Pure methods are nonblocking and execute quickly under lock. Blocking callbacks (such as DNS
/// resolution) are guaranteed to run without holding internal Rust synchronization locks. None of
/// these callbacks invoke user defined application code.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_host_vtable {
    /// nonblocking callback: deallocates a tracked request token without invoking an application
    /// callback.
    pub discard_token: Option<unsafe extern "C" fn(token: usize)>,
    /// nonblocking callback: retrieves the active default watcher registration pair.
    pub snapshot_default_watcher: Option<unsafe extern "C" fn(host: usize, out: *mut zkrs_watcher)>,
    /// nonblocking callback: records the established session identifier and password into legacy
    /// `zh->client_id`.
    pub store_client_id: Option<unsafe extern "C" fn(host: usize, id: i64, passwd: *const u8)>,
    /// nonblocking callback: queries whether logging is currently active for the given severity
    /// level.
    pub log_enabled: Option<unsafe extern "C" fn(level: u32) -> c_int>,
    /// nonblocking callback: returns non-zero if deterministic connection ordering is enabled via
    /// `zoo_deterministic_conn_order`.
    pub conn_permute_disabled: Option<unsafe extern "C" fn() -> c_int>,
    /// nonblocking callback: retrieves a pseudo-random integer using standard libc `random()`.
    pub rng_random: Option<unsafe extern "C" fn() -> c_long>,
    /// nonblocking callback: retrieves a pseudo-random floating-point value using standard libc
    /// `drand48()`.
    pub rng_drand48: Option<unsafe extern "C" fn() -> c_double>,
    /// Blocking callback: reseeds host pseudo-random number generator state.
    pub rng_reseed: Option<unsafe extern "C" fn()>,
    /// Blocking callback: performs asynchronous host address resolution via `getaddrinfo`,
    /// returning the resulting system errno.
    #[allow(clippy::type_complexity, reason = "the C prototype names no typedef")]
    pub resolve: Option<
        unsafe extern "C" fn(
            name: *const c_char,
            port: *const c_char,
            gai_rc: *mut i32,
            push: Option<unsafe extern "C" fn(sink: *mut c_void, addr: *const zkrs_sockaddr_view)>,
            skip: Option<unsafe extern "C" fn(sink: *mut c_void, family: i32)>,
            sink: *mut c_void,
        ) -> c_int,
    >,
}

/// caller supplied destination pointers for marshaling operation results into C memory.
///
/// Any null pointer indicates that the caller does not require that particular result component.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_result_dst {
    /// Pointer to caller-allocated `struct Stat` to receive node metadata, or null.
    pub stat: *mut c_void,
    /// Destination pointer to receive a heap-allocated copy of node data (`malloc`), or null.
    pub value: *mut *mut c_char,
    /// Pointer to receive the length of the copied node data in bytes, or -1 for null data.
    pub value_len: *mut i32,
    /// Destination pointer to caller's `struct String_vector` to receive child node names, or null.
    pub strings: *mut c_void,
    /// Destination pointer to caller's `struct ACL_vector` to receive access control list entries,
    /// or null.
    pub acl: *mut c_void,
    /// Destination pointer to receive a heap-allocated copy of the stripped result path (`malloc`),
    /// or null.
    pub str: *mut *mut c_char,
    /// Pointer to receive the byte length of the copied result path.
    pub str_len: *mut i32,
    /// Destination pointer to receive an array of copied sub-operation results for synchronous
    /// multi requests.
    pub ops: *mut *mut zkrs_multi_copy,
    /// Pointer to receive the count of sub-operation results copied into `ops`.
    pub nops: *mut i32,
}

/// Heap-allocated copy of an individual sub-operation result within a synchronous multi
/// transaction.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_multi_copy {
    /// Individual sub-operation error return code.
    pub err: i32,
    /// Non-zero flag indicating whether this sub-operation failed.
    pub failed: i32,
    /// Heap-allocated copy of the resulting path (`malloc`), or null.
    pub path: *mut c_char,
    /// Byte length of the copied path string.
    pub path_len: i32,
    /// Non-zero flag indicating whether `stat` contains valid node metadata.
    pub has_stat: u32,
    /// Node stat metadata resulting from a set sub-operation.
    pub stat: zkrs_stat,
}

/// Intermediate response payload stored in a synchronous operation completion record.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_sync_src {
    /// Operation completion category ([`ZKRS_K_*`]) identifying the active result structure.
    pub kind: u32,
    /// Legacy ZooKeeper return code (`ZOK`, `ZNONODE`, etc.).
    pub rc: i32,
    /// Non-zero flag indicating that response decoding failed or was aborted before packet parsing.
    pub failed: i32,
    /// Provenance of the result ([`ZKRS_ORIGIN_*`]) indicating server reply or local session
    /// teardown.
    pub origin: i32,
    /// Pointer to node data payload within the completion record.
    pub data: *const c_char,
    /// Byte length of node data payload, or -1 for null data.
    pub data_len: i32,
    /// Pointer to stripped path string within the completion record.
    pub path: *const c_char,
    /// Byte length of the stripped path string.
    pub path_len: i32,
    /// Pointer to node stat metadata (`const struct Stat *`) within the completion record.
    pub stat: *const c_void,
    /// Array of copied sub-operation results for multi transactions.
    pub ops: *const zkrs_multi_copy,
    /// Total count of sub-operation results present in `ops`.
    pub nops: i32,
    /// Array of writer type identifiers indicating how each sub-operation result should be
    /// unpacked.
    pub op_kind: *const u8,
}

/// Output buffer destinations supplied by callers of synchronous API wrapper functions.
///
/// Pointers are null for result components that the specific wrapper function does not collect.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct zkrs_sync_dst {
    /// User-supplied destination buffer to receive node data.
    pub buffer: *mut c_char,
    /// Input capacity of `buffer` in bytes; receives the actual length copied on completion.
    pub buffer_len: *mut i32,
    /// User-supplied destination buffer to receive resulting path strings.
    pub path_buffer: *mut c_char,
    /// Capacity of `path_buffer` in bytes.
    pub path_buffer_len: i32,
    /// Pointer to caller-allocated `struct Stat` to receive node metadata.
    pub stat: *mut c_void,
    /// caller supplied destination array of `zoo_op_result_t` records for multi transactions.
    pub results: *mut c_void,
    /// Count of entries in the `results` array.
    pub count: i32,
}

impl zkrs_str {
    /// Constructs a representation of a Jute null string (`{ ptr: null, len: 0 }`).
    #[must_use]
    pub const fn null() -> Self {
        Self {
            ptr: ptr::null(),
            len: 0,
        }
    }
}

impl zkrs_buf {
    /// Constructs a representation of a Jute null byte buffer (`{ ptr: null, len: -1 }`).
    #[must_use]
    pub const fn null() -> Self {
        Self {
            ptr: ptr::null(),
            len: -1,
        }
    }
}

impl zkrs_item {
    /// Constructs an uninitialized outbox item slot with all pointer and numeric fields set to
    /// zero.
    ///
    /// The outbox populates `type` and the corresponding active payload variant fields when
    /// enqueuing events.
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            r#type: 0,
            log: ptr::null(),
            token: 0,
            result: ptr::null(),
            watch: ptr::null(),
            sasl: ptr::null(),
            thread_op: 0,
            thread_role: 0,
        }
    }
}

impl zkrs_sockaddr_view {
    /// Constructs an empty endpoint view indicating the absence of an address (`family == 0`).
    #[must_use]
    pub const fn none() -> Self {
        Self {
            family: 0,
            port: 0,
            addr: [0; 16],
            flowinfo: 0,
            scope_id: 0,
        }
    }

    /// Converts an optional standard library `SocketAddr` into an ABI-compatible endpoint view.
    ///
    /// Passing `None` produces an empty view via [`Self::none`].
    #[must_use]
    pub fn from_addr(a: Option<SocketAddr>) -> Self {
        match a {
            None => Self::none(),
            Some(SocketAddr::V4(v4)) => {
                let mut addr = [0u8; 16];
                let (head, _) = addr.split_at_mut(4);
                head.copy_from_slice(&v4.ip().octets());
                Self {
                    family: ZKRS_AF_INET,
                    port: v4.port(),
                    addr,
                    flowinfo: 0,
                    scope_id: 0,
                }
            }
            Some(SocketAddr::V6(v6)) => Self {
                family: ZKRS_AF_INET6,
                port: v6.port(),
                addr: v6.ip().octets(),
                flowinfo: v6.flowinfo(),
                scope_id: v6.scope_id(),
            },
        }
    }

    /// Reconstructs a standard library `SocketAddr` from this ABI endpoint view.
    ///
    /// Returns `None` if `family` is 0 or unrecognised.
    #[must_use]
    pub fn to_addr(self) -> Option<SocketAddr> {
        match self.family {
            ZKRS_AF_INET => {
                let [a, b, c, d, ..] = self.addr;
                Some(SocketAddr::V4(SocketAddrV4::new(
                    Ipv4Addr::new(a, b, c, d),
                    self.port,
                )))
            }
            ZKRS_AF_INET6 => Some(SocketAddr::V6(SocketAddrV6::new(
                Ipv6Addr::from(self.addr),
                self.port,
                self.flowinfo,
                self.scope_id,
            ))),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sockaddr_view_round_trips_both_families() {
        let v4: SocketAddr = "10.1.2.3:2181".parse().unwrap_or_else(|_| unreachable!());
        let v = zkrs_sockaddr_view::from_addr(Some(v4));
        assert_eq!(v.family, ZKRS_AF_INET);
        assert_eq!(v.addr[..4], [10, 1, 2, 3]);
        assert_eq!(v.to_addr(), Some(v4));
        let v6 = SocketAddr::V6(SocketAddrV6::new(Ipv6Addr::LOCALHOST, 2182, 7, 9));
        let w = zkrs_sockaddr_view::from_addr(Some(v6));
        assert_eq!(w.family, ZKRS_AF_INET6);
        assert_eq!(w.to_addr(), Some(v6));
        assert_eq!(zkrs_sockaddr_view::from_addr(None).to_addr(), None);
    }

    #[test]
    fn enum_values_match_the_header_table() {
        assert_eq!(zkrs_step::ZKRS_EXIT as u32, 3);
        assert_eq!(zkrs_item_type::ZKRS_ITEM_THREAD as u32, 6);
        assert_eq!(zkrs_thread_op::ZKRS_THREAD_DETACH as u32, 2);
        assert_eq!(zkrs_status::ZKRS_SYSTEM as u32, 7);
    }
}
