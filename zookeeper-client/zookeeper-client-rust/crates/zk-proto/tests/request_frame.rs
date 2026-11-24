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

//! Outbound request frame layout, zero-copy buffer lending, and priority insertion tests
//! exercising the public [`Connection`] API.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use common::{Tk, conn_with};
use zk_jute::{
    JBuffer, JString, Reader, Record, opcode,
    proto::{GetDataRequest, RequestHeader, SetDataRequest},
};
use zk_proto::{
    Connection, SecretBytes,
    action::ConnectStart,
    compat::Compat,
    config::Config,
    request::{InputDefect, MultiOp, Op, PathIn, Request, Route, Submitted},
    time::Millis,
};

/// Minimum serialized frame size (512 KiB) eligible for zero-copy buffer lending during transmit.
const LEND_MIN: usize = 512 * 1024;

/// Maximum byte count (64 KiB) copied into the caller's transmit buffer in a single plaintext poll.
const COPY_MAX: usize = 64 * 1024;

fn lp(p: &str) -> PathIn {
    PathIn::Legacy(Some(p.as_bytes().to_vec()))
}

/// Submits `op` for transmission and returns its assigned transaction identifier (`xid`).
fn queue(c: &mut Connection<Tk>, op: Op<u32>) -> i32 {
    let req = Request {
        op,
        token: 7,
        route: Route::Queued,
        defect: InputDefect::default(),
    };
    match c.submit(req, Millis(0)) {
        Ok(Submitted::Queued { xid }) => xid,
        other => panic!("submit: {other:?}"),
    }
}

fn set(len: usize) -> Op<u32> {
    Op::SetData {
        path: lp("/a"),
        data: JBuffer::Bytes(vec![0x5a; len]),
        version: 3,
    }
}

/// Instantiates a connection and completes the initial handshake so outbound requests can be
/// transmitted.
fn established() -> Connection<Tk> {
    let mut c = conn_with(Config::legacy(30_000), Compat::LEGACY_C);
    c.handle_connect_started(ConnectStart::InProgress, Millis(0));
    c.handle_tcp_connected(Millis(0));
    c.handle_transport_ready(Millis(0));
    let mut out = Vec::new();
    let n = c.poll_transmit(&mut out, usize::MAX);
    c.handle_transmitted(n, Millis(0));
    let mut body = Vec::new();
    body.extend_from_slice(&0i32.to_be_bytes());
    body.extend_from_slice(&30_000i32.to_be_bytes());
    body.extend_from_slice(&0x55i64.to_be_bytes());
    body.extend_from_slice(&16i32.to_be_bytes());
    body.extend_from_slice(&[0xAB; 16]);
    body.push(0);
    let mut frame = i32::try_from(body.len()).unwrap().to_be_bytes().to_vec();
    frame.extend_from_slice(&body);
    assert_eq!(c.handle_read(&frame, Millis(0)).frames, 1);
    loop {
        if c.poll_output().is_some() {
            continue;
        }
        match c.take_stopped() {
            Some(s) => c.resume(s, Millis(0)),
            None => break,
        }
    }
    c
}

/// Validates the 4-byte length prefix of `frame` and decodes its leading [`RequestHeader`],
/// returning the header alongside a [`Reader`] positioned at the request payload.
fn split(frame: &[u8]) -> (RequestHeader, Reader<'_>) {
    let mut r = Reader::new(frame);
    let len = r.read_i32().unwrap();
    assert_eq!(usize::try_from(len).unwrap(), frame.len() - 4);
    let hdr = RequestHeader::decode(&mut r).unwrap();
    (hdr, r)
}

/// Polls and acknowledges all pending outbound bytes using a fresh empty buffer per call, returning
/// the concatenated wire stream.
fn send_all(c: &mut Connection<Tk>) -> Vec<u8> {
    let mut wire = Vec::new();
    loop {
        let mut out = Vec::new();
        let n = c.poll_transmit(&mut out, usize::MAX);
        if n == 0 {
            return wire;
        }
        assert_eq!(n, out.len());
        c.handle_transmitted(n, Millis(0));
        wire.extend_from_slice(&out);
    }
}

/// Verifies that every outbound request frame construction path emits a valid 4-byte length prefix
/// followed by a [`RequestHeader`] and serialized Jute payload.
#[test]
fn request_frame_layout() {
    let mut c = established();

    let xid = queue(
        &mut c,
        Op::GetData {
            path: lp("/w"),
            watch: Some(9),
        },
    );
    let wire = send_all(&mut c);
    let (hdr, mut r) = split(&wire);
    assert_eq!((hdr.xid, hdr.r#type), (xid, opcode::GET_DATA));
    let rec = GetDataRequest::decode(&mut r).unwrap();
    assert_eq!((rec.path, rec.watch), (JString::from("/w"), true));
    assert_eq!(r.remaining(), 0);

    for len in [0, 100, LEND_MIN - 64, LEND_MIN, 1024 * 1024] {
        let xid = queue(&mut c, set(len));
        let wire = send_all(&mut c);
        let (hdr, mut r) = split(&wire);
        assert_eq!((hdr.xid, hdr.r#type), (xid, opcode::SET_DATA), "{len}");
        let rec = SetDataRequest::decode(&mut r).unwrap();
        assert_eq!(rec.path, JString::from("/a"));
        assert_eq!(rec.data.as_bytes(), Some(&vec![0x5a; len][..]));
        assert_eq!(rec.version, 3);
        assert_eq!(r.remaining(), 0);
    }

    // Multi-operation requests assemble their batched body prior to framing.
    let xid = queue(
        &mut c,
        Op::Multi {
            ops: vec![MultiOp::Delete {
                path: lp("/d"),
                version: -1,
                defect: InputDefect::default(),
            }],
        },
    );
    let wire = send_all(&mut c);
    let (hdr, _) = split(&wire);
    assert_eq!((hdr.xid, hdr.r#type), (xid, opcode::MULTI));
}

/// Verifies that unstarted plaintext frames at or above [`LEND_MIN`] are lent directly by swapping
/// their backing buffer into an empty caller vector without copying, whereas smaller or partially
/// transmitted frames are copied in chunks bounded by [`COPY_MAX`].
#[test]
fn large_frames_lend_their_buffer() {
    let mut c = established();
    let large = 1024 * 1024;
    let frame_len = |data: usize| 4 + 4 + 4 + (4 + 2) + 4 + data + 4;

    // Copying into `out` preserves the caller vector's existing capacity.
    queue(&mut c, set(LEND_MIN - 64));
    let mut out = Vec::with_capacity(4 * large);
    let mut sent = 0;
    while sent < frame_len(LEND_MIN - 64) {
        out.clear();
        let n = c.poll_transmit(&mut out, usize::MAX);
        assert!(n > 0 && n <= COPY_MAX, "{n}");
        assert_eq!(out.capacity(), 4 * large);
        c.handle_transmitted(n, Millis(0));
        sent += n;
    }
    assert_eq!(sent, frame_len(LEND_MIN - 64));

    // Zero-copy lending replaces `out` with the exact backing allocation of the frame.
    queue(&mut c, set(large));
    let mut out = Vec::with_capacity(4 * large);
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!((n, out.len(), out.capacity()), (frame_len(large), n, n));
    // A lent frame remains pinned at the head of the transmit queue until its final byte is
    // acknowledged.
    let mut again = Vec::new();
    assert_eq!(c.poll_transmit(&mut again, usize::MAX), 0);
    c.handle_transmitted(1000, Millis(0));
    assert_eq!(c.poll_transmit(&mut again, usize::MAX), 0);
    c.handle_transmitted(n - 1000, Millis(0));

    // When preceding small frames have already been copied into `out`, `poll_transmit` stops before
    // a large frame so the subsequent call can lend it into an empty buffer.
    let small = queue(&mut c, set(10));
    let big = queue(&mut c, set(large));
    let mut out = Vec::new();
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!(n, frame_len(10));
    assert_eq!(split(&out).0.xid, small);
    c.handle_transmitted(n, Millis(0));
    out.clear();
    out.reserve(4 * large);
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!((n, out.capacity()), (frame_len(large), frame_len(large)));
    assert_eq!(split(&out).0.xid, big);
    c.handle_transmitted(n, Millis(0));

    // When `max` is smaller than the frame length, the initial slice is copied and the partially
    // transmitted frame continues via chunked copying rather than lending.
    queue(&mut c, set(large));
    let mut out = Vec::with_capacity(4 * large);
    let n = c.poll_transmit(&mut out, 256 * 1024);
    assert_eq!((n, out.capacity()), (COPY_MAX, 4 * large));
    c.handle_transmitted(n, Millis(0));
    out.extend_from_slice(&send_all(&mut c));
    assert_eq!(out.len(), frame_len(large));
    let (_, mut r) = split(&out);
    let rec = SetDataRequest::decode(&mut r).unwrap();
    assert_eq!(rec.data.as_bytes(), Some(&vec![0x5a; large][..]));
}

/// Verifies that when the caller supplies a non-empty buffer to `poll_transmit`, a large frame at
/// the head of the queue is appended via chunked copying rather than overwriting the caller's
/// existing bytes.
#[test]
fn a_large_frame_after_held_bytes_is_copied() {
    let mut c = established();
    let large = 1024 * 1024;
    let xid = queue(&mut c, set(large));
    let mut out = Vec::with_capacity(4 * large);
    out.extend_from_slice(b"held");
    let at = out.as_ptr();
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!(n, COPY_MAX);
    assert_eq!((out.as_ptr(), out.len()), (at, 4 + COPY_MAX), "a copy");
    assert_eq!(&out[..4], b"held");
    c.handle_transmitted(n, Millis(0));
    let mut wire = out.split_off(4);
    wire.extend_from_slice(&send_all(&mut c));
    let (hdr, mut r) = split(&wire);
    assert_eq!(hdr.xid, xid);
    let rec = SetDataRequest::decode(&mut r).unwrap();
    assert_eq!(rec.data.as_bytes(), Some(&vec![0x5a; large][..]));
}

/// Extracts the sequence of transaction identifiers (`xid`) from a concatenated wire frame stream.
fn xids(wire: &[u8]) -> Vec<i32> {
    let mut all = Vec::new();
    let mut rest = wire;
    while !rest.is_empty() {
        let len = usize::try_from(i32::from_be_bytes(rest[..4].try_into().unwrap())).unwrap();
        all.push(i32::from_be_bytes(rest[4..8].try_into().unwrap()));
        rest = &rest[4 + len..];
    }
    all
}

/// Registers a `digest` authentication credential on a connected session, queuing an `AUTH` packet
/// at the priority insertion boundary.
fn auth(c: &mut Connection<Tk>) {
    let cred = SecretBytes::new(b"u:pw".to_vec());
    assert!(
        c.add_auth(JString::from("digest"), cred, None, Millis(0))
            .is_ok()
    );
}

/// Verifies that priority authentication frames are inserted immediately behind frames already
/// pinned by in-flight transmit copies or buffer lending, and ahead of all unstarted queued frames.
#[test]
fn an_auth_goes_behind_the_pinned_frames_only() {
    const AUTH_XID: i32 = -4;
    let mut c = established();
    let sets: Vec<i32> = (0..4).map(|_| queue(&mut c, set(COPY_MAX))).collect();
    let mut out = Vec::new();
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!(n, COPY_MAX);
    auth(&mut c);
    c.handle_transmitted(n, Millis(0));
    out.extend_from_slice(&send_all(&mut c));
    assert_eq!(xids(&out), [sets[0], AUTH_XID, sets[1], sets[2], sets[3]]);

    let big = queue(&mut c, set(LEND_MIN));
    let tail = queue(&mut c, set(10));
    let mut out = Vec::new();
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!(n, out.len());
    assert_eq!(xids(&out), [big], "a lent frame");
    auth(&mut c);
    c.handle_transmitted(n, Millis(0));
    out.extend_from_slice(&send_all(&mut c));
    assert_eq!(xids(&out), [big, AUTH_XID, tail]);
}
