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

//! Fixed-layout connection handshake codec for ZooKeeper session negotiation.
//!
//! Unlike standard operational requests and responses that adhere to dynamic Jute
//! schema serialization, the initial connection establishment exchange uses a
//! specialized, fixed-size binary framing layout. Historically implemented in the C
//! client via `serialize_prime_connect` and `deserialize_prime_response` (`zookeeper.c`),
//! this handshake exchanges session credentials, negotiated timeout bounds, the last
//! observed transaction ID (`zxid`), and read-only mode support prior to initializing the
//! general request-response stream.

use core::fmt;

/// Fixed length of the session password buffer in bytes.
pub const PASSWD_LEN: usize = 16;

/// Unframed payload length of a [`ConnectRequest`] in bytes (excluding the 4-byte frame length
/// prefix).
pub const CONNECT_REQUEST_LEN: usize = 45;

/// Total wire frame length of a [`ConnectRequest`] in bytes, encompassing the 4-byte length prefix
/// and 45-byte payload.
pub const CONNECT_REQUEST_FRAME_LEN: usize = 49;

/// Standard wire payload length of a modern [`ConnectResponse`] in bytes, including the trailing
/// `read_only` boolean flag.
pub const CONNECT_RESPONSE_LEN: usize = 37;

/// Legacy wire payload length of a [`ConnectResponse`] emitted by older ZooKeeper servers lacking
/// read-only mode support.
pub const CONNECT_RESPONSE_OLD_LEN: usize = 36;

/// Big-endian integer value written into the 4-byte wire frame prefix of a [`ConnectRequest`].
const CONNECT_REQUEST_WIRE_LEN: i32 = 45;

/// Integer length prefix prepended to the 16-byte session password field on the wire.
const PASSWD_WIRE_LEN: i32 = 16;

/// Initial connection handshake request sent by the client to initialize or resume a ZooKeeper
/// session.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ConnectRequest {
    /// Wire protocol version negotiated with the ensemble; conventionally 0 in standard clients.
    pub protocol_version: i32,
    /// Highest transaction identifier (`zxid`) observed by the client, ensuring monotonically
    /// advancing state upon reconnection.
    pub last_zxid_seen: i64,
    /// Client's requested session expiration timeout in milliseconds.
    pub time_out: i32,
    /// Unique 64-bit session identifier to reconnect to, or 0 when requesting the establishment of
    /// a brand-new session.
    pub session_id: i64,
    /// Shared session credential token used to authenticate reconnection requests against an
    /// existing session identifier.
    pub passwd: [u8; PASSWD_LEN],
    /// Flag indicating whether the client is willing to establish a connection to an ensemble
    /// member running in read-only mode.
    pub read_only: bool,
}

/// Copies the slice `src` into `dst` starting at offset `*at`, advancing the offset index by the
/// number of bytes copied.
fn put(dst: &mut [u8], at: &mut usize, src: &[u8]) {
    let Some(end) = at.checked_add(src.len()) else {
        return;
    };
    if let Some(slot) = dst.get_mut(*at..end) {
        for (d, s) in slot.iter_mut().zip(src) {
            *d = *s;
        }
        *at = end;
    }
}

impl ConnectRequest {
    /// Serializes the handshake payload fields into the provided 45-byte destination buffer in
    /// big-endian wire format.
    pub fn write_body(&self, out: &mut [u8; CONNECT_REQUEST_LEN]) {
        self.write_fields(out, 0);
    }

    /// Serializes the complete 49-byte framed connect packet (4-byte length prefix followed by the
    /// payload) into `out`.
    ///
    /// By writing into a caller supplied buffer, sensitive session credentials can be zeroized
    /// immediately after transmission.
    pub fn write_frame(&self, out: &mut [u8; CONNECT_REQUEST_FRAME_LEN]) {
        let mut at = 0usize;
        put(out, &mut at, &CONNECT_REQUEST_WIRE_LEN.to_be_bytes());
        self.write_fields(out, at);
    }

    fn write_fields(&self, out: &mut [u8], start: usize) {
        let mut at = start;
        put(out, &mut at, &self.protocol_version.to_be_bytes());
        put(out, &mut at, &self.last_zxid_seen.to_be_bytes());
        put(out, &mut at, &self.time_out.to_be_bytes());
        put(out, &mut at, &self.session_id.to_be_bytes());
        put(out, &mut at, &PASSWD_WIRE_LEN.to_be_bytes());
        put(out, &mut at, &self.passwd);
        put(out, &mut at, &[u8::from(self.read_only)]);
    }

    /// Serializes and returns the 45-byte handshake payload as an owned array.
    #[must_use]
    pub fn encode_body(&self) -> [u8; CONNECT_REQUEST_LEN] {
        let mut out = [0u8; CONNECT_REQUEST_LEN];
        self.write_body(&mut out);
        out
    }

    /// Serializes and returns the full 49-byte framed connect packet as an owned array.
    #[must_use]
    pub fn encode_frame(&self) -> [u8; CONNECT_REQUEST_FRAME_LEN] {
        let mut out = [0u8; CONNECT_REQUEST_FRAME_LEN];
        self.write_frame(&mut out);
        out
    }
}

/// Initial connection handshake response returned by the ZooKeeper server upon session negotiation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ConnectResponse {
    /// Protocol version acknowledged by the server; ignored during client session establishment.
    pub protocol_version: i32,
    /// Ensemble-negotiated session timeout in milliseconds, bounded by the server's min/max tick
    /// thresholds.
    pub time_out: i32,
    /// Unique 64-bit session identifier allocated or confirmed by the ensemble. A value of 0
    /// signals session expiration or rejection.
    pub session_id: i64,
    /// Length of the password payload on the wire; legacy C compatibility dictates reading exactly
    /// 16 bytes.
    pub passwd_len: i32,
    /// Session password credential assigned by the server, to be supplied by the client during
    /// subsequent reconnections.
    pub passwd: [u8; PASSWD_LEN],
    /// Indicates whether the connected server is currently operating in read-only mode. Omitted by
    /// legacy servers.
    pub read_only: bool,
}

/// Error condition encountered when the byte length of a server's connect response does not match
/// expected wire framing layouts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct HandshakeError {
    /// Actual payload byte length received from the transport stream.
    pub len: usize,
}

impl fmt::Display for HandshakeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "ConnectResponse body has {} bytes, expected {CONNECT_RESPONSE_LEN} or {CONNECT_RESPONSE_OLD_LEN}",
            self.len
        )
    }
}

impl std::error::Error for HandshakeError {}

/// Decodes the payload of a server's connection handshake response packet.
///
/// Accepts both modern 37-byte responses (which include the trailing read-only boolean flag)
/// and legacy 36-byte responses from older ZooKeeper servers. Any other payload length represents
/// framing corruption or an incompatible protocol implementation and triggers connection teardown.
///
/// # Errors
///
/// Returns [`HandshakeError`] if the payload slice length is neither 36 nor 37 bytes, or if
/// binary field unmarshalling fails.
pub fn decode_connect_response(body: &[u8]) -> Result<ConnectResponse, HandshakeError> {
    let err = HandshakeError { len: body.len() };
    if body.len() != CONNECT_RESPONSE_LEN && body.len() != CONNECT_RESPONSE_OLD_LEN {
        return Err(err);
    }
    let mut r = crate::Reader::new(body);
    let protocol_version = r.read_i32().map_err(|_| err)?;
    let time_out = r.read_i32().map_err(|_| err)?;
    let session_id = r.read_i64().map_err(|_| err)?;
    let passwd_len = r.read_i32().map_err(|_| err)?;
    let mut passwd = [0u8; PASSWD_LEN];
    let bytes = r.read_bytes(PASSWD_LEN).map_err(|_| err)?;
    for (d, s) in passwd.iter_mut().zip(bytes) {
        *d = *s;
    }
    let read_only = r.read_u8().is_ok_and(|b| b != 0);
    Ok(ConnectResponse {
        protocol_version,
        time_out,
        session_id,
        passwd_len,
        passwd,
        read_only,
    })
}
