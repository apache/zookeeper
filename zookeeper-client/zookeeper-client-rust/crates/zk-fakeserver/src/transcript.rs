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

//! Protocol transcript recorder capturing and dissecting client-server wire interactions.
//!
//! The ensemble records every frame transmitted by connected clients, decoding opcodes, request
//! correlation IDs (`xid`), target znode paths, and operation specific parameters. The raw frame
//! body bytes are also retained so tests can perform full Jute deserialization via `zk-jute`.

use zk_jute::data::ACL;
use zk_jute::proto::{
    AddWatchRequest, AuthPacket, CheckWatchesRequest, CreateRequest, CreateTTLRequest,
    DeleteRequest, GetDataRequest, GetSASLRequest, MultiHeader, SetACLRequest, SetDataRequest,
    SetWatches, SetWatches2,
};
use zk_jute::{DecodeError, JString, JVec, Reader, Record, opcode};

/// Categorization of inbound wire frames received from client sockets.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FrameKind {
    /// Initial session establishment or reconnection handshake frame (`ConnectRequest`).
    Connect,
    /// Post-handshake protocol request bearing a header, opcode, and body.
    Request,
    /// Unframed four-letter administrative command (such as `isro`, `stat`, or `ruok`).
    FourLetter,
    /// Malformed or unparsable frame, such as an invalid length prefix or truncated header.
    Unparsable,
}

/// Decoded client connection handshake (`ConnectRequest`).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ConnectInfo {
    /// ZooKeeper wire protocol version advertised by the client.
    pub protocol_version: i32,
    /// Highest transaction identifier (`zxid`) seen by the client prior to connecting.
    pub last_zxid_seen: i64,
    /// Client-requested session lease timeout in milliseconds.
    pub time_out: i32,
    /// Session identifier to resume, or 0 when requesting a new session.
    pub session_id: i64,
    /// Session password bytes; `None` represents a null Jute buffer.
    pub passwd: Option<Vec<u8>>,
    /// Read-only session mode flag, omitted by pre-3.4 ZooKeeper clients.
    pub read_only: Option<bool>,
}

impl ConnectInfo {
    /// Decodes a connection handshake request payload from `body`.
    ///
    /// Following the Java server implementation, the trailing `read_only` boolean is optional.
    ///
    /// # Errors
    ///
    /// Returns a [`DecodeError`] if the buffer ends prematurely or fails Jute decoding.
    pub fn decode(body: &[u8]) -> Result<Self, DecodeError> {
        let mut r = Reader::new(body);
        let protocol_version = r.read_i32()?;
        let last_zxid_seen = r.read_i64()?;
        let time_out = r.read_i32()?;
        let session_id = r.read_i64()?;
        let passwd = zk_jute::JBuffer::decode(&mut r)?.into_bytes();
        let read_only = if r.is_empty() {
            None
        } else {
            Some(r.read_bool()?)
        };
        Ok(Self {
            protocol_version,
            last_zxid_seen,
            time_out,
            session_id,
            passwd,
            read_only,
        })
    }
}

/// Watch registration sets sent during session recovery via `SetWatches` or `SetWatches2`.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WatchSet {
    /// Baseline `zxid` relative to which the client restores its watch state.
    pub relative_zxid: i64,
    /// Paths with active data watches (`getData`).
    pub data: Vec<String>,
    /// Paths with active existence watches (`exists`).
    pub exist: Vec<String>,
    /// Paths with active child watches (`getChildren`).
    pub child: Vec<String>,
    /// Paths with non-recursive persistent watches (`SetWatches2`).
    pub persistent: Vec<String>,
    /// Paths with recursive persistent watches (`SetWatches2`).
    pub persistent_recursive: Vec<String>,
}

/// Decoded payload details extracted from specific request opcodes.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum Detail {
    /// Request without opcode-specific parameter extraction.
    #[default]
    None,
    /// Decoded connection handshake parameters.
    Connect(ConnectInfo),
    /// Client authentication packet (`AuthPacket`).
    Auth {
        /// Authentication packet type field.
        auth_type: i32,
        /// Authentication scheme name (such as `"digest"` or `"ip"`).
        scheme: String,
        /// Raw credential bytes for the scheme.
        auth: Vec<u8>,
    },
    /// Watch re-registration payload from `SetWatches` or `SetWatches2`.
    SetWatches(WatchSet),
    /// Node creation request parameters across standard, container, and TTL variants.
    Create {
        /// Creation mode flags (persistent, ephemeral, sequential, container, or TTL).
        flags: i32,
        /// Access control list assigned to the new znode.
        acl: Vec<ACL>,
        /// Optional time-to-live in milliseconds for TTL nodes.
        ttl: Option<i64>,
        /// Byte length of the znode payload, or -1 for a null Jute buffer.
        data_len: i64,
    },
    /// Read operation carrying a `watch` registration flag (`getData`, `exists`, `getChildren`).
    Watch(bool),
    /// Expected data or ACL version (`setData`, `delete`, `check`, `setACL`).
    Version(i32),
    /// Watcher category specified in `checkWatches` or `removeWatches`.
    WatchType(i32),
    /// Mode selector (persistent vs persistent recursive) from an `addWatch` request.
    AddWatch(i32),
    /// Sub-operation `(opcode, path)` pairs contained in a `multi` or `multiRead` batch.
    Multi(Vec<(i32, Option<String>)>),
    /// Byte length of an incoming SASL token payload.
    Sasl(usize),
    /// Error message when request body decoding fails.
    Error(String),
}

/// Audit record capturing a single inbound frame received by the ensemble.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameRecord {
    /// Monotonically increasing sequence number ordering frames across the cluster.
    pub seq: u64,
    /// Zero-based index of the listener endpoint that received the frame.
    pub server: usize,
    /// Cluster-wide unique identifier assigned to the client connection.
    pub conn: u64,
    /// One-based connection ordinal local to the receiving listener endpoint.
    pub conn_ordinal: u64,
    /// Zero-based frame index on this connection (handshake is frame 0).
    pub frame: u64,
    /// Categorization of the frame.
    pub kind: FrameKind,
    /// Associated session identifier (established or proposed in the handshake).
    pub session_id: i64,
    /// Client request correlation identifier (`xid`).
    pub xid: Option<i32>,
    /// Wire protocol opcode for request frames.
    pub op: Option<i32>,
    /// Target znode path, when applicable to the operation.
    pub path: Option<String>,
    /// Opcode-specific decoded parameters.
    pub detail: Detail,
    /// Raw frame payload bytes following the 4-byte length prefix.
    pub body: Vec<u8>,
    /// Index of the fault-injection rule matched by this frame, if any.
    pub rule: Option<usize>,
    /// Whether the server processed the frame (`false` when received on a stalled connection).
    pub handled: bool,
}

fn text(s: &JString) -> Option<String> {
    s.as_bytes()
        .map(|b| String::from_utf8_lossy(b).into_owned())
}

fn texts(v: &JVec<JString>) -> Vec<String> {
    v.iter().filter_map(text).collect()
}

/// Decodes a request body (after the `RequestHeader`) into a target path and [`Detail`].
pub(crate) fn describe(op: i32, body: &[u8]) -> (Option<String>, Detail) {
    match describe_inner(op, body) {
        Ok(v) => v,
        Err(e) => (None, Detail::Error(e.to_string())),
    }
}

/// Decodes `T` in its own function scope so stack allocations are not merged across match arms.
fn one<T: Record>(
    r: &mut Reader<'_>,
    f: impl FnOnce(T) -> (Option<String>, Detail),
) -> Result<(Option<String>, Detail), DecodeError> {
    T::decode(r).map(f)
}

fn describe_inner(op: i32, body: &[u8]) -> Result<(Option<String>, Detail), DecodeError> {
    let mut r = Reader::new(body);
    // Opcodes sharing an identical wire layout decode through the same Jute record type.
    match op {
        opcode::CREATE | opcode::CREATE2 | opcode::CREATE_CONTAINER => {
            one(&mut r, |q: CreateRequest| {
                create(&q.path, q.flags, q.acl, None, &q.data)
            })
        }
        opcode::CREATE_TTL => one(&mut r, |q: CreateTTLRequest| {
            create(&q.path, q.flags, q.acl, Some(q.ttl), &q.data)
        }),
        opcode::DELETE | opcode::DELETE_CONTAINER | opcode::CHECK => {
            one(&mut r, |q: DeleteRequest| {
                (text(&q.path), Detail::Version(q.version))
            })
        }
        opcode::EXISTS | opcode::GET_DATA | opcode::GET_CHILDREN | opcode::GET_CHILDREN2 => {
            one(&mut r, |q: GetDataRequest| {
                (text(&q.path), Detail::Watch(q.watch))
            })
        }
        opcode::SET_DATA => one(&mut r, |q: SetDataRequest| {
            (text(&q.path), Detail::Version(q.version))
        }),
        opcode::SET_ACL => one(&mut r, |q: SetACLRequest| {
            (text(&q.path), Detail::Version(q.version))
        }),
        opcode::GET_ACL
        | opcode::SYNC
        | opcode::GET_ALL_CHILDREN_NUMBER
        | opcode::GET_EPHEMERALS => one(&mut r, |q: JString| (text(&q), Detail::None)),
        opcode::CHECK_WATCHES | opcode::REMOVE_WATCHES => one(&mut r, |q: CheckWatchesRequest| {
            (text(&q.path), Detail::WatchType(q.r#type))
        }),
        opcode::ADD_WATCH => one(&mut r, |q: AddWatchRequest| {
            (text(&q.path), Detail::AddWatch(q.mode))
        }),
        opcode::SET_AUTH => one(&mut r, |q: AuthPacket| {
            (
                None,
                Detail::Auth {
                    auth_type: q.r#type,
                    scheme: text(&q.scheme).unwrap_or_default(),
                    auth: q.auth.into_bytes().unwrap_or_default(),
                },
            )
        }),
        opcode::SET_WATCHES => one(&mut r, |q: SetWatches| watch_set(widen(q))),
        opcode::SET_WATCHES2 => one(&mut r, watch_set),
        opcode::MULTI | opcode::MULTI_READ => Ok((None, Detail::Multi(multi_ops(&mut r)?))),
        opcode::SASL => one(&mut r, |q: GetSASLRequest| {
            (None, Detail::Sasl(q.token.len()))
        }),
        _ => Ok((None, Detail::None)),
    }
}

/// Extracts path and [`Detail::Create`] fields from a decoded create request.
fn create(
    path: &JString,
    flags: i32,
    acl: JVec<ACL>,
    ttl: Option<i64>,
    data: &zk_jute::JBuffer,
) -> (Option<String>, Detail) {
    (
        text(path),
        Detail::Create {
            flags,
            acl: acl.into_vec().unwrap_or_default(),
            ttl,
            data_len: data_len(data),
        },
    )
}

/// Converts a decoded `SetWatches2` record into [`Detail::SetWatches`].
fn watch_set(q: SetWatches2) -> (Option<String>, Detail) {
    (
        None,
        Detail::SetWatches(WatchSet {
            relative_zxid: q.relative_zxid,
            data: texts(&q.data_watches),
            exist: texts(&q.exist_watches),
            child: texts(&q.child_watches),
            persistent: texts(&q.persistent_watches),
            persistent_recursive: texts(&q.persistent_recursive_watches),
        }),
    )
}

/// Converts a `SetWatches` request into `SetWatches2` with empty persistent watch lists.
pub(crate) fn widen(w: SetWatches) -> SetWatches2 {
    SetWatches2 {
        relative_zxid: w.relative_zxid,
        data_watches: w.data_watches,
        exist_watches: w.exist_watches,
        child_watches: w.child_watches,
        ..SetWatches2::default()
    }
}

fn data_len(b: &zk_jute::JBuffer) -> i64 {
    if b.is_null() {
        -1
    } else {
        i64::try_from(b.len()).unwrap_or(i64::MAX)
    }
}

/// Extracts the `(opcode, path)` list from a `multi` or `multiRead` request body.
fn multi_ops(r: &mut Reader<'_>) -> Result<Vec<(i32, Option<String>)>, DecodeError> {
    let mut out = Vec::new();
    loop {
        let h = MultiHeader::decode(r)?;
        if h.done {
            break;
        }
        let path = match h.r#type {
            opcode::CREATE | opcode::CREATE2 | opcode::CREATE_CONTAINER => {
                text(&CreateRequest::decode(r)?.path)
            }
            opcode::CREATE_TTL => text(&CreateTTLRequest::decode(r)?.path),
            opcode::DELETE | opcode::DELETE_CONTAINER | opcode::CHECK => {
                text(&DeleteRequest::decode(r)?.path)
            }
            opcode::SET_DATA => text(&SetDataRequest::decode(r)?.path),
            opcode::GET_DATA | opcode::GET_CHILDREN => text(&GetDataRequest::decode(r)?.path),
            other => {
                out.push((other, None));
                break;
            }
        };
        out.push((h.r#type, path));
    }
    Ok(out)
}
