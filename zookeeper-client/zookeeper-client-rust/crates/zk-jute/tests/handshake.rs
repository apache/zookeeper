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

//! Conformance tests for the fixed layout session handshake codec.
//!
//! Expected binary layouts verify byte-for-byte compatibility with `serialize_prime_connect` and
//! `deserialize_prime_response` in the canonical C client.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_jute::Record;
use zk_jute::handshake::{
    CONNECT_REQUEST_FRAME_LEN, CONNECT_REQUEST_LEN, CONNECT_RESPONSE_LEN, CONNECT_RESPONSE_OLD_LEN,
    ConnectRequest, ConnectResponse, HandshakeError, PASSWD_LEN, decode_connect_response,
};
use zk_jute::proto;

fn request() -> ConnectRequest {
    ConnectRequest {
        protocol_version: 0,
        last_zxid_seen: 0x0102_0304_0506_0708,
        time_out: 30_000,
        session_id: 0x1122_3344_5566_7788,
        passwd: *b"0123456789abcdef",
        read_only: true,
    }
}

const GOLDEN_BODY: [u8; 45] = [
    0, 0, 0, 0, // protocolVersion
    1, 2, 3, 4, 5, 6, 7, 8, // lastZxidSeen
    0, 0, 0x75, 0x30, // timeOut
    0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, // sessionId
    0, 0, 0, 16, // passwd length
    b'0', b'1', b'2', b'3', b'4', b'5', b'6', b'7', b'8', b'9', b'a', b'b', b'c', b'd', b'e',
    b'f', // passwd
    1,    // readOnly
];

#[test]
fn connect_request_golden_bytes() {
    assert_eq!(CONNECT_REQUEST_LEN, 45);
    assert_eq!(CONNECT_REQUEST_FRAME_LEN, 49);
    assert_eq!(PASSWD_LEN, 16);
    let req = request();
    assert_eq!(req.encode_body(), GOLDEN_BODY);
    let frame = req.encode_frame();
    assert_eq!(frame[..4], [0, 0, 0, 45]);
    assert_eq!(frame[4..], GOLDEN_BODY);

    let mut body = [0xee; 45];
    req.write_body(&mut body);
    assert_eq!(body, GOLDEN_BODY);
    let mut out = [0xee; 49];
    req.write_frame(&mut out);
    assert_eq!(out, frame);
}

#[test]
fn connect_request_matches_the_jute_record() {
    let req = request();
    let rec = proto::ConnectRequest {
        protocol_version: req.protocol_version,
        last_zxid_seen: req.last_zxid_seen,
        time_out: req.time_out,
        session_id: req.session_id,
        passwd: req.passwd.to_vec().into(),
        read_only: req.read_only,
    };
    assert_eq!(rec.to_bytes(), req.encode_body());
    // Initial session negotiation transmits a 16-byte zeroed password buffer rather than a Jute
    // null (-1) marker.
    let fresh = ConnectRequest::default().encode_body();
    assert_eq!(fresh[24..28], [0, 0, 0, 16]);
    assert_eq!(fresh[28..44], [0; 16]);
    assert_eq!(fresh[44], 0);
}

fn response_bytes(read_only: Option<u8>) -> Vec<u8> {
    let mut b = Vec::new();
    b.extend_from_slice(&0i32.to_be_bytes());
    b.extend_from_slice(&40_000i32.to_be_bytes());
    b.extend_from_slice(&0x0abc_i64.to_be_bytes());
    b.extend_from_slice(&16i32.to_be_bytes());
    b.extend_from_slice(&[7; 16]);
    if let Some(flag) = read_only {
        b.push(flag);
    }
    b
}

#[test]
fn connect_response_37_bytes() {
    let body = response_bytes(Some(1));
    assert_eq!(body.len(), CONNECT_RESPONSE_LEN);
    let resp = decode_connect_response(&body).unwrap();
    assert_eq!(
        resp,
        ConnectResponse {
            protocol_version: 0,
            time_out: 40_000,
            session_id: 0x0abc,
            passwd_len: 16,
            passwd: [7; 16],
            read_only: true,
        }
    );
    // Any non-zero byte value is interpreted as boolean `true`, matching C deserialization rules.
    assert!(
        decode_connect_response(&response_bytes(Some(2)))
            .unwrap()
            .read_only
    );
    assert!(
        !decode_connect_response(&response_bytes(Some(0)))
            .unwrap()
            .read_only
    );
}

#[test]
fn connect_response_36_bytes_from_old_server() {
    let body = response_bytes(None);
    assert_eq!(body.len(), CONNECT_RESPONSE_OLD_LEN);
    let resp = decode_connect_response(&body).unwrap();
    assert!(!resp.read_only);
    assert_eq!(resp.session_id, 0x0abc);
}

#[test]
fn connect_response_ignores_the_password_length() {
    let mut body = response_bytes(Some(0));
    body[16..20].copy_from_slice(&(-1i32).to_be_bytes());
    let resp = decode_connect_response(&body).unwrap();
    assert_eq!(resp.passwd_len, -1);
    assert_eq!(resp.passwd, [7; 16]);
}

#[test]
fn connect_response_other_lengths_fail() {
    for len in [0usize, 1, 16, 35, 38, 45, 100] {
        let body = vec![0u8; len];
        assert_eq!(decode_connect_response(&body), Err(HandshakeError { len }));
    }
    assert!(!HandshakeError { len: 3 }.to_string().is_empty());
}
