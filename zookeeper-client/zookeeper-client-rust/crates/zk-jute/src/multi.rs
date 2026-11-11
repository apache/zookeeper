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

//! Serialization and parsing codec for batched ZooKeeper multi-operation transactions.
//!
//! ZooKeeper supports atomic transaction batches (`MultiHeader` followed by individual
//! operation payloads) as well as composite multi-read requests. On the wire, each sub-operation
//! in the request stream is preceded by a `MultiHeader`, and the stream is finalized by a sentinel
//! terminator header (`type: -1`, `done: true`, `err: -1`). The server streams back sub-operation
//! responses using a matching framing structure.
//!
//! Unlike the legacy C client, which attempts to infer the response payload layout solely from the
//! originating request opcodes and can suffer from stream desynchronization, this
//! implementation dynamically unmarshals each sub-result according to the explicit opcode stamped
//! into its individual response `MultiHeader`. This guarantees forward compatibility and stream
//! integrity.

use core::fmt;

use crate::proto::{
    Create2Response, CreateResponse, ErrorResponse, GetChildrenResponse, GetDataResponse,
    MultiHeader, SetDataResponse,
};
use crate::{DecodeError, Reader, Record, opcode};

/// Minimum encoded byte length of a [`MultiHeader`] (4-byte type, 1-byte done flag, 4-byte error
/// code).
const MULTI_HEADER_LEN: usize = 9;

/// Discriminates the execution mode and allowed response opcodes for a multi-operation batch.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MultiKind {
    /// Atomic read-write transaction batch executed under opcode 14 (`MULTI`).
    Multi,
    /// Batch read-only transaction executed under opcode 22 (`MULTI_READ`).
    MultiRead,
}

/// Typed representation of an individual sub-operation response payload within a multi batch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MultiBody {
    /// server side execution failure for this sub-operation, containing the specific error code.
    Error(ErrorResponse),
    /// Successful node creation response returning the canonical path (`CREATE`).
    Create(CreateResponse),
    /// Extended node creation response returning both path and initial metadata `Stat` (`CREATE2`,
    /// `CREATE_TTL`, or `CREATE_CONTAINER`).
    Create2(Create2Response),
    /// Successful data mutation response containing the updated node `Stat` (`SET_DATA`).
    SetData(SetDataResponse),
    /// Sub-operation that returns no payload body upon success (`DELETE` or `CHECK`).
    Empty,
    /// Query result containing node byte contents and `Stat` (`GET_DATA`, valid only in
    /// `MultiRead`).
    GetData(GetDataResponse),
    /// Query result listing immediate child node names (`GET_CHILDREN`, valid only in `MultiRead`).
    GetChildren(GetChildrenResponse),
}

/// Decoded sub-operation result yielded from a multi transaction response stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MultiItem {
    /// Operation opcode declared in the wire header of this sub-result.
    pub wire_type: i32,
    /// Result status code stamped in the wire header (`ZOK` on success, or a specific negative
    /// error code).
    pub err: i32,
    /// Typed payload unmarshalled from the sub-operation byte stream.
    pub body: MultiBody,
}

/// Errors encountered while unmarshalling a multi-operation response stream.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MultiError {
    /// Syntax or framing error encountered while decoding a header or payload record, or premature
    /// stream termination.
    Decode(DecodeError),
    /// Received a sub-operation response opcode that is invalid or unexpected for the active
    /// [`MultiKind`].
    UnexpectedType(i32),
    /// Number of sub-operation results decoded before reaching the terminator header did not match
    /// the expected count.
    CountMismatch {
        /// Number of sub-operations submitted in the originating request.
        expected: usize,
        /// Actual number of sub-results parsed from the wire payload.
        got: usize,
    },
}

impl From<DecodeError> for MultiError {
    fn from(e: DecodeError) -> Self {
        Self::Decode(e)
    }
}

impl fmt::Display for MultiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Decode(e) => write!(f, "multi response: {e}"),
            Self::UnexpectedType(t) => write!(f, "multi response: unexpected result type {t}"),
            Self::CountMismatch { expected, got } => {
                write!(f, "multi response: {got} results for {expected} operations")
            }
        }
    }
}

impl std::error::Error for MultiError {}

/// Serializes and appends an operation header preceding a sub-operation in a multi request payload.
///
/// Encodes the designated operation opcode with `done` set to `false` and `err` set to `-1` per
/// wire specification.
pub fn encode_op_header(op_type: i32, out: &mut Vec<u8>) {
    MultiHeader {
        r#type: op_type,
        done: false,
        err: -1,
    }
    .encode(out);
}

/// Serializes and appends the sentinel multi-operation stream terminator header (`type: -1`,
/// `done: true`, `err: -1`).
pub fn encode_end(out: &mut Vec<u8>) {
    MultiHeader {
        r#type: -1,
        done: true,
        err: -1,
    }
    .encode(out);
}

/// Decodes the payload of an individual sub-operation based on its wire opcode and batch mode
/// context.
fn decode_body(
    r: &mut Reader<'_>,
    wire_type: i32,
    kind: MultiKind,
) -> Result<MultiBody, MultiError> {
    let read_only = kind == MultiKind::MultiRead;
    Ok(match wire_type {
        opcode::ERROR => MultiBody::Error(ErrorResponse::decode(r)?),
        opcode::CREATE if !read_only => MultiBody::Create(CreateResponse::decode(r)?),
        opcode::CREATE2 if !read_only => MultiBody::Create2(Create2Response::decode(r)?),
        opcode::SET_DATA if !read_only => MultiBody::SetData(SetDataResponse::decode(r)?),
        opcode::DELETE | opcode::CHECK if !read_only => MultiBody::Empty,
        opcode::GET_DATA if read_only => MultiBody::GetData(GetDataResponse::decode(r)?),
        opcode::GET_CHILDREN if read_only => {
            MultiBody::GetChildren(GetChildrenResponse::decode(r)?)
        }
        other => return Err(MultiError::UnexpectedType(other)),
    })
}

/// Decodes the sequence of multi-operation results following the leading
/// [`crate::proto::ReplyHeader`].
///
/// Expects exactly `expected` sub-operation items terminated by a sentinel header with
/// `done == true`. For write transactions ([`MultiKind::Multi`]), accepted response types include
/// error (-1), create (1), delete (2), setData (5), check (13), and create2 (15). For read
/// transactions ([`MultiKind::MultiRead`]), accepted types include error (-1), getData (4), and
/// getChildren (8). Parsing stops upon encountering the terminator header; trailing bytes are
/// discarded.
///
/// # Errors
///
/// Returns [`MultiError`] if Jute decoding fails, if an unexpected opcode is encountered, if the
/// stream ends prematurely without a terminator, or if the decoded item count does not match
/// `expected`.
pub fn decode_multi(
    r: &mut Reader<'_>,
    expected: usize,
    kind: MultiKind,
) -> Result<Vec<MultiItem>, MultiError> {
    let fit = r.remaining().checked_div(MULTI_HEADER_LEN).unwrap_or(0);
    let mut items = Vec::with_capacity(expected.min(fit));
    loop {
        let header = MultiHeader::decode(r)?;
        if header.done {
            break;
        }
        if items.len() >= expected {
            return Err(MultiError::CountMismatch {
                expected,
                got: items.len().saturating_add(1),
            });
        }
        let body = decode_body(r, header.r#type, kind)?;
        items.push(MultiItem {
            wire_type: header.r#type,
            err: header.err,
            body,
        });
    }
    if items.len() != expected {
        return Err(MultiError::CountMismatch {
            expected,
            got: items.len(),
        });
    }
    Ok(items)
}
