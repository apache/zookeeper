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

//! Authentication credential registration, wire transmission ordering, and completion dispatch
//! integration tests exercising the public [`Connection`] API.

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
    JBuffer, JString, Record,
    proto::{AuthPacket, RequestHeader},
    rc, xid,
};
use zk_proto::{
    Connection,
    action::ConnectStart,
    compat::Compat,
    config::Config,
    log::{LogArg, LogSite},
    output::Output,
    request::{AddAuthError, InputDefect, Op, PathIn, Request, Route},
    secret::SecretBytes,
    state::State,
    time::Millis,
};

fn conn(compat: Compat, sasl: bool) -> Connection<Tk> {
    let mut cfg = Config::legacy(30_000);
    cfg.sasl = sasl;
    conn_with(cfg, compat)
}

/// Unmarshals a framed `AuthPacket` into its transaction ID, opcode, and authentication scheme,
/// asserting that the wire length prefix and credential payload match `cred`.
fn decode_auth(bytes: &[u8], cred: &[u8]) -> (i32, i32, JString) {
    let mut r = zk_jute::Reader::new(bytes);
    let len = r.read_i32().unwrap();
    assert_eq!(usize::try_from(len).unwrap(), bytes.len() - 4);
    let hdr = RequestHeader::decode(&mut r).unwrap();
    let pkt = AuthPacket::decode(&mut r).unwrap();
    assert_eq!(pkt.r#type, 0);
    assert_eq!(pkt.auth.as_bytes(), Some(cred));
    assert_eq!(r.remaining(), 0);
    (hdr.xid, hdr.r#type, pkt.scheme)
}

fn add(c: &mut Connection<Tk>, scheme: &str, tok: u32) -> Result<(), AddAuthError<Tk>> {
    c.add_auth(
        JString::from(scheme),
        SecretBytes::from_slice(b"pw"),
        Some(tok),
        Millis(0),
    )
}

/// Observable protocol output event recorded during authentication reply verification.
#[derive(Debug, PartialEq, Eq)]
enum Ev {
    /// Emitted diagnostic log entry recording scheme authentication outcome.
    Scheme(LogSite, JString),
    /// Authentication completion delivery containing tokens, status code, and barrier flag.
    Auth(Vec<u32>, i32, bool),
    /// Thread synchronization lockpoint boundary (`Output::AuthLockpoint`).
    Lockpoint,
    /// Session state transition event carrying the integer state code.
    Session(i32),
}

/// Drains all pending connection outputs and advances across suspended synchronization barriers,
/// collecting authentication-related events.
fn run(c: &mut Connection<Tk>) -> Vec<Ev> {
    let mut evs = Vec::new();
    loop {
        if let Some(o) = c.poll_output() {
            match o {
                Output::Log(r)
                    if matches!(
                        r.site,
                        LogSite::AuthSchemeSucceeded | LogSite::AuthSchemeFailed
                    ) =>
                {
                    let scheme = match r.args.as_slice() {
                        [LogArg::Str(s)] => s.clone(),
                        a => panic!("unexpected args {a:?}"),
                    };
                    evs.push(Ev::Scheme(r.site, scheme));
                }
                Output::Auth {
                    tokens,
                    rc,
                    barrier,
                } => evs.push(Ev::Auth(tokens, rc, barrier)),
                Output::AuthLockpoint => evs.push(Ev::Lockpoint),
                Output::Session { state, .. } => evs.push(Ev::Session(state.code())),
                _ => {}
            }
            continue;
        }
        match c.take_stopped() {
            Some(s) => c.resume(s, Millis(0)),
            None => break,
        }
    }
    evs
}

fn ok(scheme: &str) -> Ev {
    Ev::Scheme(LogSite::AuthSchemeSucceeded, JString::from(scheme))
}

fn failed(scheme: &str) -> Ev {
    Ev::Scheme(LogSite::AuthSchemeFailed, JString::from(scheme))
}

/// Polls up to `max` outbound bytes from the transmit queue, acknowledges transmission, and
/// returns the serialized wire bytes.
fn send_some(c: &mut Connection<Tk>, max: usize) -> Vec<u8> {
    let mut out = Vec::new();
    let n = c.poll_transmit(&mut out, max);
    c.handle_transmitted(n, Millis(0));
    out
}

/// Partitions a serialized byte stream into individual length-prefixed frames, extracting the
/// authentication scheme for `AUTH` packets and yielding `None` for standard request frames.
fn wire(bytes: &[u8]) -> Vec<Option<Vec<u8>>> {
    let mut frames = Vec::new();
    let mut rest = bytes;
    while !rest.is_empty() {
        let len = i32::from_be_bytes(rest[..4].try_into().unwrap());
        let (frame, tail) = rest.split_at(4 + usize::try_from(len).unwrap());
        let auth = frame[4..8] == xid::AUTH.to_be_bytes();
        frames.push(auth.then(|| decode_auth(frame, b"pw").2.into_bytes().unwrap()));
        rest = tail;
    }
    frames
}

/// Drains all pending outbound bytes from `c` and decodes the resulting sequence of wire frames.
fn send(c: &mut Connection<Tk>) -> Vec<Option<Vec<u8>>> {
    wire(&send_some(c, usize::MAX))
}

fn schemes(names: &[&str]) -> Vec<Option<Vec<u8>>> {
    names.iter().map(|n| Some(n.as_bytes().to_vec())).collect()
}

/// Simulates transport establishment and flushes the initial `ConnectRequest` handshake frame.
fn connect(c: &mut Connection<Tk>) {
    c.handle_connect_started(ConnectStart::InProgress, Millis(0));
    c.handle_tcp_connected(Millis(0));
    c.handle_transport_ready(Millis(0));
    assert_eq!(send(c), vec![None]);
}

/// Injects a server `ConnectResponse` for session `0x55` and drives resulting state transitions
/// (entering the SASL negotiation phase when SASL is enabled, or running session finalization).
fn respond(c: &mut Connection<Tk>, read_only: bool) -> Vec<Ev> {
    let body = response(30_000, 0x55, read_only);
    let mut frame = i32::try_from(body.len()).unwrap().to_be_bytes().to_vec();
    frame.extend_from_slice(&body);
    assert_eq!(c.handle_read(&frame, Millis(0)).frames, 1);
    run(c)
}

/// Establishes the transport connection and completes the session handshake exchange.
fn establish(c: &mut Connection<Tk>, read_only: bool) -> Vec<Ev> {
    connect(c);
    respond(c, read_only)
}

/// Injects an inbound `AUTH` reply header carrying status code `err` and drains resulting events.
fn reply(c: &mut Connection<Tk>, err: i32) -> Vec<Ev> {
    let mut frame = 16_i32.to_be_bytes().to_vec();
    frame.extend_from_slice(&xid::AUTH.to_be_bytes());
    frame.extend_from_slice(&0_i64.to_be_bytes());
    frame.extend_from_slice(&err.to_be_bytes());
    assert_eq!(c.handle_read(&frame, Millis(0)).frames, 1);
    run(c)
}

/// Constructs a 37-byte serialized `ConnectResponse` payload.
fn response(timeout: i32, session_id: i64, read_only: bool) -> Vec<u8> {
    let mut b = Vec::new();
    b.extend_from_slice(&0i32.to_be_bytes());
    b.extend_from_slice(&timeout.to_be_bytes());
    b.extend_from_slice(&session_id.to_be_bytes());
    b.extend_from_slice(&16i32.to_be_bytes());
    b.extend_from_slice(&[0xAB; 16]);
    b.push(u8::from(read_only));
    b
}

#[test]
fn an_add_before_the_connect_send_keeps_the_connect_first() {
    let mut c = conn(Compat::LEGACY_C, false);
    c.handle_connect_started(ConnectStart::InProgress, Millis(0));
    c.handle_tcp_connected(Millis(0));
    c.handle_transport_ready(Millis(0));
    // Credentials registered prior to flushing the initial handshake packet are queued behind the
    // leading `ConnectRequest` frame so connection negotiation always precedes authentication.
    add(&mut c, "a", 1).unwrap();
    assert_eq!(send(&mut c), vec![None]);
    let _ = respond(&mut c, false);
    assert_eq!(send(&mut c), schemes(&["a", "a"]));
    assert_eq!(
        reply(&mut c, 0),
        vec![Ev::Lockpoint, ok("a"), Ev::Auth(vec![1], 0, false)]
    );
    assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("a")]);
}

#[test]
fn native_duplicate_goes_after_the_resend() {
    let mut compat = Compat::NATIVE;
    compat.auth_dup_while_associating = true;
    let mut c = conn(compat, false);
    connect(&mut c);
    add(&mut c, "a", 1).unwrap();
    let _ = respond(&mut c, false);
    assert_eq!(send(&mut c), schemes(&["a", "a"]));
    assert_eq!(reply(&mut c, 0), vec![ok("a"), Ev::Auth(vec![1], 0, false)]);
    assert_eq!(reply(&mut c, 0), vec![ok("a")]);
}

#[test]
fn native_failure_completes_only_the_rejected_token() {
    let mut c = conn(Compat::NATIVE, false);
    let _ = establish(&mut c, false);
    add(&mut c, "a", 1).unwrap();
    add(&mut c, "bogus", 2).unwrap();
    add(&mut c, "c", 3).unwrap();
    assert_eq!(send(&mut c), schemes(&["a", "bogus", "c"]));
    assert_eq!(reply(&mut c, 0), vec![ok("a"), Ev::Auth(vec![1], 0, false)]);
    let evs = reply(&mut c, rc::ZAUTHFAILED);
    assert_eq!(
        evs,
        vec![
            failed("bogus"),
            Ev::Auth(vec![2], rc::ZAUTHFAILED, false),
            Ev::Session(State::AuthFailed.code()),
            Ev::Auth(vec![3], rc::ZAUTHFAILED, false),
        ]
    );
    assert_eq!(c.state(), State::AuthFailed);
}

/// Verifies that an authentication rejection reply resolves only its corresponding in-flight token
/// before connection teardown drains remaining uncompleted authentication tokens.
#[test]
fn legacy_failure_completes_only_the_rejected_token() {
    let mut c = conn(Compat::LEGACY_C, false);
    let _ = establish(&mut c, false);
    add(&mut c, "a", 1).unwrap();
    add(&mut c, "bogus", 2).unwrap();
    assert_eq!(send(&mut c), schemes(&["bogus", "a"]));
    let evs = reply(&mut c, rc::ZAUTHFAILED);
    assert_eq!(
        evs,
        vec![
            Ev::Lockpoint,
            failed("bogus"),
            Ev::Auth(vec![2], rc::ZAUTHFAILED, true),
            Ev::Session(State::AuthFailed.code()),
            Ev::Lockpoint,
            Ev::Auth(vec![1], rc::ZAUTHFAILED, true),
        ]
    );
    assert_eq!(c.state(), State::AuthFailed);
}

/// Submits a `SetData` request carrying a payload of `len` bytes.
fn queue_set(c: &mut Connection<Tk>, len: usize) {
    let req = Request {
        op: Op::SetData {
            path: PathIn::Legacy(Some(b"/a".to_vec())),
            data: JBuffer::Bytes(vec![0x5a; len]),
            version: -1,
        },
        token: 7,
        route: Route::Queued,
        defect: InputDefect::default(),
    };
    assert!(c.submit(req, Millis(0)).is_ok());
}

/// Verifies that outbound transmit buffer reallocation after copying a secret authentication frame
/// preserves contiguous wire byte ordering across subsequent request copies.
#[test]
fn secret_copies_keep_the_wire_order() {
    let mut c = conn(Compat::LEGACY_C, false);
    establish(&mut c, false);

    add(&mut c, "digest", 1).unwrap();
    queue_set(&mut c, 4096);
    let mut out = Vec::new();
    let n = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!(n, out.len());
    assert_eq!(wire(&out), vec![Some(b"digest".to_vec()), None]);
    // The serialized data payload immediately precedes the trailing 4-byte version field.
    assert_eq!(out[out.len() - 4 - 4096..out.len() - 4], [0x5a; 4096]);
    c.handle_transmitted(n, Millis(0));
    out.clear();

    // Appending a subsequent frame into a buffer already holding a secret frame preserves both.
    add(&mut c, "digest", 2).unwrap();
    let first = c.poll_transmit(&mut out, usize::MAX);
    out.shrink_to_fit();
    queue_set(&mut c, 8192);
    let second = c.poll_transmit(&mut out, usize::MAX);
    assert_eq!(first + second, out.len());
    assert_eq!(wire(&out), vec![Some(b"digest".to_vec()), None]);
    assert_eq!(out[out.len() - 4 - 8192..out.len() - 4], [0x5a; 8192]);
}
