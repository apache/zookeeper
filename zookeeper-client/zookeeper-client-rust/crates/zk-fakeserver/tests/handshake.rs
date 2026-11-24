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

//! Integration tests for connection handshakes, session reattachment, pings, and four-letter
//! administrative commands.

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

use std::io::{ErrorKind, Read, Write};
use std::net::TcpStream;

use common::{SHORT, WAIT, create_ok, open, open_session};
use zk_fakeserver::{
    Action, ClusterConfig, Detail, FakeServer, FrameKind, HandshakeStyle, RawClient, Rule,
    ServerOptions, create_mode,
};
use zk_jute::handshake::ConnectRequest;
use zk_jute::{opcode, xid};

fn four_letter(server: &FakeServer, word: &str) -> String {
    let mut s = TcpStream::connect(server.addr()).unwrap();
    s.write_all(word.as_bytes()).unwrap();
    s.set_read_timeout(Some(WAIT)).unwrap();
    let mut out = String::new();
    s.read_to_string(&mut out).unwrap();
    out
}

#[test]
fn new_session_and_timeout_clamp() {
    let server = FakeServer::start().unwrap();
    let (_c, low) = RawClient::open(server.addr(), 100).unwrap();
    assert_eq!(low.time_out, 4000);
    assert_ne!(low.session_id, 0);
    assert!(!low.read_only);
    assert_eq!(low.passwd_len, 16);
    let (_c2, high) = RawClient::open(server.addr(), 1_000_000).unwrap();
    assert_eq!(high.time_out, 40_000);
    assert_eq!(high.session_id, low.session_id + 1);
    let (_c3, mid) = RawClient::open(server.addr(), 12_345).unwrap();
    assert_eq!(mid.time_out, 12_345);
    let sessions = server.cluster().sessions();
    assert_eq!(sessions.len(), 3);
    assert_eq!(sessions[0].passwd, low.passwd);
}

#[test]
fn same_seed_gives_same_sessions() {
    let run = || {
        let server =
            FakeServer::start_with(ClusterConfig::default(), ServerOptions::default()).unwrap();
        let (_c, r) = open_session(&server);
        (r.session_id, r.passwd)
    };
    assert_eq!(run(), run());
    let other = FakeServer::start_with(
        ClusterConfig {
            seed: 99,
            ..ClusterConfig::default()
        },
        ServerOptions::default(),
    )
    .unwrap();
    let (_c, r) = open_session(&other);
    assert_ne!(r.session_id, run().0);
    assert_eq!(r.session_id >> 56, 1);
}

#[test]
fn reattach_and_bad_password() {
    let server = FakeServer::start().unwrap();
    let (mut first, r) = open_session(&server);
    let (mut again, r2) =
        RawClient::resume(server.addr(), r.session_id, r.passwd, 20_000, 0).unwrap();
    assert_eq!(r2.session_id, r.session_id);
    assert_eq!(r2.passwd, r.passwd);
    assert_eq!(r2.time_out, 20_000);
    // Reattaching a session on a new socket closes the previous socket bound to that session.
    assert!(first.wait_closed(WAIT));
    assert_eq!(again.ping().unwrap().xid, xid::PING);

    let mut bad = r.passwd;
    bad[0] ^= 0xFF;
    let (mut c, resp) = RawClient::resume(server.addr(), r.session_id, bad, 20_000, 0).unwrap();
    assert_eq!(resp.session_id, 0);
    assert_eq!(resp.time_out, 0);
    assert!(c.wait_closed(WAIT));

    let (mut c, resp) = RawClient::resume(server.addr(), 12345, r.passwd, 20_000, 0).unwrap();
    assert_eq!(resp.session_id, 0);
    assert!(c.wait_closed(WAIT));
}

#[test]
fn client_ahead_of_server_gets_no_response() {
    let server = FakeServer::start().unwrap();
    let (_c, r) = open_session(&server);
    let err = RawClient::resume(server.addr(), r.session_id, r.passwd, 20_000, 1_000).unwrap_err();
    assert_eq!(err.kind(), ErrorKind::UnexpectedEof);
}

#[test]
fn ping_and_close() {
    let server = FakeServer::start().unwrap();
    let (mut c, r) = open_session(&server);
    create_ok(&mut c, "/e", b"", create_mode::EPHEMERAL);
    let p = c.ping().unwrap();
    assert_eq!((p.xid, p.err), (xid::PING, 0));
    assert_eq!(p.zxid, server.cluster().zxid());
    let close = c.call_empty(opcode::CLOSE).unwrap();
    assert_eq!(close.err, 0);
    assert!(c.wait_closed(WAIT));
    assert!(server.cluster().node("/e").is_none());
    assert!(
        server
            .cluster()
            .sessions()
            .iter()
            .all(|s| s.id != r.session_id)
    );
}

#[test]
fn read_only_server() {
    let server = FakeServer::start_with(
        ClusterConfig::default(),
        ServerOptions {
            read_only: true,
            ..ServerOptions::default()
        },
    )
    .unwrap();
    let err = RawClient::open(server.addr(), 10_000).unwrap_err();
    assert_eq!(err.kind(), ErrorKind::UnexpectedEof);
    let (mut c, r) = RawClient::open_with(
        server.addr(),
        &ConnectRequest {
            time_out: 10_000,
            read_only: true,
            ..ConnectRequest::default()
        },
    )
    .unwrap();
    assert!(r.read_only);
    let w = common::create(&mut c, "/x", b"", 0);
    assert_eq!(w.err, zk_jute::rc::ZNOTREADONLY);
    assert!(w.body.is_empty());
    assert_eq!(common::get_data(&mut c, "/", false).err, 0);
    assert_eq!(four_letter(&server, "isro"), "ro");
    server.set_read_only(false);
    assert_eq!(common::create(&mut c, "/x", b"", 0).err, 0);
    assert_eq!(four_letter(&server, "isro"), "rw");
    server.set_isro_answer(Some("ro"));
    assert_eq!(four_letter(&server, "isro"), "ro");
}

#[test]
fn four_letter_words() {
    let server = FakeServer::start().unwrap();
    assert_eq!(four_letter(&server, "ruok"), "imok");
    assert!(four_letter(&server, "srvr").contains("Mode: standalone"));
    assert_eq!(
        four_letter(&server, "dump"),
        "dump is not executed because it is not in the whitelist.\n"
    );
    let rec = server
        .wait_for(WAIT, |r| {
            r.kind == FrameKind::FourLetter && r.body == b"dump"
        })
        .unwrap();
    assert_eq!(rec.frame, 0);
}

#[test]
fn legacy_and_split_handshake() {
    let server = FakeServer::start().unwrap();
    server.set_handshake(HandshakeStyle::Legacy36);
    let mut c = RawClient::connect(server.addr()).unwrap();
    c.send_raw(
        &ConnectRequest {
            time_out: 10_000,
            ..ConnectRequest::default()
        }
        .encode_frame(),
    )
    .unwrap();
    assert_eq!(c.read_frame(WAIT).unwrap().len(), 36);

    server.set_handshake(HandshakeStyle::Modern);
    server.add_rule(Rule::on_connect().then(Action::Handshake {
        style: HandshakeStyle::Modern,
        split_at: Some(3),
    }));
    let (_c, r) = RawClient::open(server.addr(), 10_000).unwrap();
    assert_ne!(r.session_id, 0);
    let rec = server
        .wait_for(WAIT, |r| r.kind == FrameKind::Connect && r.rule.is_some())
        .unwrap();
    match rec.detail {
        Detail::Connect(info) => {
            assert_eq!(info.time_out, 10_000);
            assert_eq!(info.read_only, Some(false));
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn expired_handshake_rule() {
    let server = FakeServer::start().unwrap();
    server.add_rule(Rule::on_connect().then(Action::ExpiredHandshake));
    let (mut c, r) = RawClient::open(server.addr(), 10_000).unwrap();
    assert_eq!(r.session_id, 0);
    assert!(c.wait_closed(WAIT));
}

#[test]
fn old_client_without_read_only_flag() {
    let server = FakeServer::start().unwrap();
    let mut c = RawClient::connect(server.addr()).unwrap();
    let frame = ConnectRequest {
        time_out: 10_000,
        ..ConnectRequest::default()
    }
    .encode_frame();
    // Pre-3.4 44-byte `ConnectRequest` payload omitting the trailing `read_only` flag.
    let mut old = frame[..48].to_vec();
    old[3] = 44;
    c.send_raw(&old).unwrap();
    assert_eq!(c.read_frame(WAIT).unwrap().len(), 37);
    let mut c2 = open(&server);
    assert!(!c2.wait_closed(SHORT));
}
