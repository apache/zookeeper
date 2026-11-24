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

//! Wire format frame encoders and decoders for synthetic ZooKeeper network exchanges.
//!
//! Provides bidirectional wire framing utilities used by the mock server model and integration
//! tests. Supports encoding and decoding length prefixed Jute payloads, including connect
//! requests/responses, request headers, reply headers, and raw or typed record payloads.

use zk_jute::proto::{ConnectRequest, ConnectResponse, ReplyHeader, RequestHeader};
use zk_jute::{
    DecodeError, FrameDecoder, FrameError, JBuffer, Pushed, Reader, Record, encode_frame,
};

/// Encodes a length prefixed server response frame containing a reply header and payload bytes.
#[must_use]
pub fn encode_reply(xid: i32, zxid: i64, err: i32, body: &[u8]) -> Vec<u8> {
    let mut b = Vec::new();
    ReplyHeader { xid, zxid, err }.encode(&mut b);
    b.extend_from_slice(body);
    let mut out = Vec::new();
    encode_frame(&b, &mut out);
    out
}

/// Encodes a length prefixed server response frame containing a reply header and an optional Jute
/// record body.
#[must_use]
pub fn encode_record_reply<R: Record>(xid: i32, zxid: i64, err: i32, body: Option<&R>) -> Vec<u8> {
    let bytes = body.map(Record::to_bytes).unwrap_or_default();
    encode_reply(xid, zxid, err, &bytes)
}

/// Encodes a length prefixed client request frame with a standard header and optional Jute record
/// payload.
#[must_use]
pub fn request_frame<R: Record>(xid: i32, op: i32, body: Option<&R>) -> Vec<u8> {
    let mut b = Vec::new();
    RequestHeader { xid, r#type: op }.encode(&mut b);
    if let Some(r) = body {
        r.encode(&mut b);
    }
    let mut out = Vec::new();
    encode_frame(&b, &mut out);
    out
}

/// Encodes a length prefixed client request frame with a standard header and unparsed raw payload
/// bytes.
#[must_use]
pub fn raw_request_frame(xid: i32, op: i32, body: &[u8]) -> Vec<u8> {
    let mut b = Vec::new();
    RequestHeader { xid, r#type: op }.encode(&mut b);
    b.extend_from_slice(body);
    let mut out = Vec::new();
    encode_frame(&b, &mut out);
    out
}

/// Encodes a length prefixed client session establishment handshake frame (`ConnectRequest`).
#[must_use]
pub fn connect_frame(req: &ConnectRequest) -> Vec<u8> {
    let mut out = Vec::new();
    encode_frame(&req.to_bytes(), &mut out);
    out
}

/// Encodes a length prefixed server connection handshake response (`ConnectResponse`).
///
/// When `short` is true, omits the trailing `read_only` boolean flag to emulate pre-3.4 ZooKeeper
/// servers.
#[must_use]
pub fn connect_response_frame(resp: &ConnectResponse, short: bool) -> Vec<u8> {
    let mut b = resp.to_bytes();
    if short {
        b.pop();
    }
    let mut out = Vec::new();
    encode_frame(&b, &mut out);
    out
}

/// Parses a client `ConnectRequest` frame payload, accommodating optional read-only negotiation
/// flags.
///
/// # Errors
///
/// Returns [`DecodeError`] if the buffer contains insufficient bytes or invalid Jute fields.
pub fn decode_connect_request(body: &[u8]) -> Result<ConnectRequest, DecodeError> {
    let mut r = Reader::new(body);
    let protocol_version = r.read_i32()?;
    let last_zxid_seen = r.read_i64()?;
    let time_out = r.read_i32()?;
    let session_id = r.read_i64()?;
    let passwd = JBuffer::decode(&mut r)?;
    let read_only = if r.is_empty() { false } else { r.read_bool()? };
    Ok(ConnectRequest {
        protocol_version,
        last_zxid_seen,
        time_out,
        session_id,
        passwd,
        read_only,
    })
}

/// Parses a server `ConnectResponse` frame payload, accommodating optional read-only flags.
///
/// # Errors
///
/// Returns [`DecodeError`] if the response buffer is malformed or prematurely truncated.
pub fn decode_connect_response(body: &[u8]) -> Result<ConnectResponse, DecodeError> {
    let mut r = Reader::new(body);
    let protocol_version = r.read_i32()?;
    let time_out = r.read_i32()?;
    let session_id = r.read_i64()?;
    let passwd = JBuffer::decode(&mut r)?;
    let read_only = if r.is_empty() { false } else { r.read_bool()? };
    Ok(ConnectResponse {
        protocol_version,
        time_out,
        session_id,
        passwd,
        read_only,
    })
}

/// Decodes a client request frame header, returning the header alongside the remaining unparsed
/// payload slice.
///
/// # Errors
///
/// Returns [`DecodeError`] if the payload is too short to contain a valid [`RequestHeader`].
pub fn decode_client_frame(body: &[u8]) -> Result<(RequestHeader, &[u8]), DecodeError> {
    let mut r = Reader::new(body);
    let h = RequestHeader::decode(&mut r)?;
    Ok((h, r.rest()))
}

/// Decodes a server reply frame header, returning the header alongside the remaining unparsed
/// payload slice.
///
/// # Errors
///
/// Returns [`DecodeError`] if the buffer is too short to deserialize a complete [`ReplyHeader`].
pub fn decode_reply(body: &[u8]) -> Result<(ReplyHeader, &[u8]), DecodeError> {
    ReplyHeader::peek(body)
}

/// Demultiplexes a contiguous byte stream into discrete length prefixed frame bodies.
///
/// Incomplete trailing frames are retained within parser state and omitted from the returned batch.
///
/// # Errors
///
/// Returns [`FrameError`] if a frame length prefix violates protocol limits or is negative.
pub fn split_frames(bytes: &[u8]) -> Result<Vec<Vec<u8>>, FrameError> {
    let mut d = FrameDecoder::new(None);
    let mut out = Vec::new();
    let mut rest = bytes;
    while !rest.is_empty() {
        let (n, p) = d.push(rest);
        rest = rest.get(n..).unwrap_or_default();
        match p {
            Pushed::Frame(f) => out.push(f),
            Pushed::Error(e) => return Err(e),
            Pushed::NeedMore => {
                if n == 0 {
                    break;
                }
            }
        }
    }
    Ok(out)
}
