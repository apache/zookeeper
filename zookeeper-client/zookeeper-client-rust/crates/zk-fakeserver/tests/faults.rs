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

//! Integration tests for scripted fault injection and frame transcript recording.

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

use std::io::ErrorKind;
use std::time::{Duration, Instant};

use common::{SHORT, WAIT, create_ok, get_data, open};
use zk_fakeserver::{
    Action, Chunking, ClusterConfig, Detail, FakeServer, FrameKind, Malformed, RawClient, Rule,
    Script, ServerOptions, event,
};
use zk_jute::proto::GetDataRequest;
use zk_jute::rc::{ZCONNECTIONLOSS, ZNONODE};
use zk_jute::{opcode, xid};

fn get_req(path: &str) -> GetDataRequest {
    GetDataRequest {
        path: path.into(),
        watch: false,
    }
}

#[test]
fn delay_keeps_order() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    server.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .once()
            .then(Action::Delay(Duration::from_millis(200))),
    );
    let start = Instant::now();
    let a = c.send_record(opcode::GET_DATA, &get_req("/")).unwrap();
    let b = c.send_record(opcode::EXISTS, &get_req("/")).unwrap();
    assert_eq!(c.read_reply(WAIT).unwrap().xid, a);
    assert!(start.elapsed() >= Duration::from_millis(190));
    assert_eq!(c.read_reply(WAIT).unwrap().xid, b);
    assert_eq!(server.script().rules()[0].fired(), 1);
}

#[test]
fn drop_and_rewrite_replies() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    server.set_script(
        Script::new()
            .rule(Rule::on_request().frame(1).then(Action::DropReply))
            .rule(Rule::on_request().frame(2).then(Action::RewriteXid(777)))
            .rule(
                Rule::on_request()
                    .frame(3)
                    .then(Action::RewriteErr(ZNONODE)),
            )
            .rule(Rule::on_request().frame(4).then(Action::Reply {
                err: ZCONNECTIONLOSS,
            }))
            .rule(Rule::on_request().frame(5).then(Action::HeaderOnly)),
    );
    c.send_record(opcode::GET_DATA, &get_req("/")).unwrap();
    assert!(c.read_frame(SHORT).is_err());
    c.send_record(opcode::GET_DATA, &get_req("/")).unwrap();
    assert_eq!(c.read_reply(WAIT).unwrap().xid, 777);
    let r = c.call(opcode::GET_DATA, &get_req("/")).unwrap();
    assert_eq!(r.err, ZNONODE);
    assert!(!r.body.is_empty());
    let r = c.call(opcode::GET_DATA, &get_req("/")).unwrap();
    assert_eq!((r.err, r.body.len()), (ZCONNECTIONLOSS, 0));
    let r = c.call(opcode::GET_DATA, &get_req("/")).unwrap();
    assert_eq!((r.err, r.body.len()), (0, 0));
    let rec = server.wait_for(WAIT, |r| r.frame == 4).unwrap();
    assert_eq!(rec.rule, Some(3));
    assert_eq!(rec.op, Some(opcode::GET_DATA));
}

#[test]
fn close_on_op_and_after_frames() {
    let server = FakeServer::start().unwrap();
    server.add_rule(Rule::on_request().op(opcode::SET_DATA).then(Action::Close));
    let mut c = open(&server);
    create_ok(&mut c, "/x", b"", 0);
    let r = c.call(
        opcode::SET_DATA,
        &zk_jute::proto::SetDataRequest {
            path: "/x".into(),
            data: zk_jute::JBuffer::Null,
            version: -1,
        },
    );
    assert_eq!(r.unwrap_err().kind(), ErrorKind::UnexpectedEof);
    // `Action::Close` terminates the connection prior to executing the state machine mutation.
    assert_eq!(server.cluster().node("/x").unwrap().stat.version, 0);

    server.set_script(Script::new().rule(Rule::on_request().frame(3).then(Action::Close)));
    let mut c = open(&server);
    assert_eq!(c.ping().unwrap().err, 0);
    assert_eq!(c.ping().unwrap().err, 0);
    assert!(c.ping().is_err());
}

#[test]
fn malformed_frames() {
    let cases = [
        Malformed::ZeroLength,
        Malformed::NegativeLength(-5),
        Malformed::Truncated {
            declared: 100,
            body: vec![0; 10],
        },
        Malformed::ShortHeader(vec![1, 2, 3]),
    ];
    for m in cases {
        let server = FakeServer::start().unwrap();
        // `Action::Malformed` is non-terminal, so `Action::Ignore` suppresses the model reply.
        server.add_rule(
            Rule::on_request()
                .op(opcode::GET_DATA)
                .then(Action::Malformed(m.clone()))
                .then(Action::Ignore),
        );
        let mut c = open(&server);
        c.send_record(opcode::GET_DATA, &get_req("/")).unwrap();
        match &m {
            Malformed::ZeroLength | Malformed::NegativeLength(_) => {
                assert_eq!(
                    c.read_frame(WAIT).unwrap_err().kind(),
                    ErrorKind::InvalidData
                );
            }
            Malformed::Truncated { .. } => {
                assert_eq!(c.read_frame(SHORT).unwrap_err().kind(), ErrorKind::TimedOut);
            }
            Malformed::ShortHeader(_) => {
                assert_eq!(c.read_frame(WAIT).unwrap(), vec![1, 2, 3]);
            }
        }
    }
}

#[test]
fn unsolicited_and_notify() {
    let server = FakeServer::start().unwrap();
    server.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .then(Action::Unsolicited {
                xid: 4242,
                zxid: 1,
                err: 0,
                body: Vec::new(),
            })
            .then(Action::Notify {
                event_type: event::NODE_DELETED,
                state: event::SYNC_CONNECTED,
                path: "/ghost".into(),
            }),
    );
    let mut c = open(&server);
    let x = c.send_record(opcode::GET_DATA, &get_req("/")).unwrap();
    assert_eq!(c.read_reply(WAIT).unwrap().xid, 4242);
    assert_eq!(c.read_reply(WAIT).unwrap().xid, x);
    assert_eq!(c.take_events()[0].path, "/ghost");
}

#[test]
fn stall_keeps_socket_open() {
    let server = FakeServer::start().unwrap();
    server.add_rule(Rule::on_request().frame(2).then(Action::Stall));
    let mut c = open(&server);
    assert_eq!(c.ping().unwrap().err, 0);
    c.send(xid::PING, opcode::PING, &[]).unwrap();
    c.send(xid::PING, opcode::PING, &[]).unwrap();
    assert!(!c.wait_closed(SHORT));
    let t = server.transcript();
    let last = t
        .iter()
        .rev()
        .find(|r| r.kind == FrameKind::Request)
        .unwrap();
    assert_eq!(last.frame, 3);
    assert!(!last.handled);
}

#[test]
fn expire_and_reject_auth_rules() {
    let server = FakeServer::start().unwrap();
    server.add_rule(
        Rule::on_request()
            .op(opcode::EXISTS)
            .then(Action::ExpireSession),
    );
    server.add_rule(
        Rule::on_request()
            .op(opcode::SET_AUTH)
            .then(Action::RejectAuth),
    );
    let (mut c, r) = common::open_session(&server);
    create_ok(&mut c, "/e", b"", zk_fakeserver::create_mode::EPHEMERAL);
    let _ = c.call(opcode::EXISTS, &get_req("/e"));
    assert!(c.wait_closed(WAIT));
    assert!(
        server
            .cluster()
            .sessions()
            .iter()
            .all(|s| s.id != r.session_id)
    );
    assert!(server.cluster().node("/e").is_none());

    let mut c = open(&server);
    c.send(
        xid::AUTH,
        opcode::SET_AUTH,
        &common::bytes(&zk_jute::proto::AuthPacket {
            r#type: 0,
            scheme: "digest".into(),
            auth: zk_jute::JBuffer::from(&b"a:b"[..]),
        }),
    )
    .unwrap();
    assert_eq!(c.read_reply(WAIT).unwrap().err, zk_jute::rc::ZAUTHFAILED);
    assert!(c.wait_closed(WAIT));
}

#[test]
fn chunked_writes_still_parse() {
    for chunking in [Chunking::Fixed(1), Chunking::Random { max: 3 }] {
        let server = FakeServer::start_with(
            ClusterConfig::default(),
            ServerOptions {
                chunking,
                chunk_gap: Duration::from_millis(1),
                ..ServerOptions::default()
            },
        )
        .unwrap();
        let mut c = open(&server);
        create_ok(&mut c, "/chunk", b"some data here", 0);
        assert_eq!(common::data_of(&mut c, "/chunk"), b"some data here");
    }
}

#[test]
fn hold_and_release() {
    let server = FakeServer::start().unwrap();
    let mut a = open(&server);
    let mut b = open(&server);
    server.hold();
    let xa = a.send_record(opcode::GET_DATA, &get_req("/")).unwrap();
    assert!(a.read_frame(SHORT).is_err());
    server.release();
    assert_eq!(a.read_reply(WAIT).unwrap().xid, xa);
    assert_eq!(get_data(&mut b, "/", false).err, 0);
}

#[test]
fn transcript_records_frames() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/t", b"abc", 0);
    let _ = get_data(&mut c, "/t", true);
    let t = server.transcript();
    assert_eq!(t[0].kind, FrameKind::Connect);
    assert_eq!(t[1].op, Some(opcode::CREATE));
    assert_eq!(t[1].path.as_deref(), Some("/t"));
    assert!(matches!(
        t[1].detail,
        Detail::Create {
            flags: 0,
            data_len: 3,
            ..
        }
    ));
    assert_eq!(t[2].detail, Detail::Watch(true));
    assert_eq!(t[2].frame, 2);
    assert!(t.windows(2).all(|w| w[0].seq < w[1].seq));
    assert_ne!(t[2].session_id, 0);

    // An invalid negative frame length prefix is recorded as `Unparsable` and closes the socket.
    let mut raw = RawClient::connect(server.addr()).unwrap();
    raw.send_raw(&(-3i32).to_be_bytes()).unwrap();
    assert!(raw.wait_closed(WAIT));
    let bad = server
        .wait_for(WAIT, |r| r.kind == FrameKind::Unparsable)
        .unwrap();
    assert!(matches!(bad.detail, Detail::Error(_)));
}

#[test]
fn chance_rules_repeat_with_seed() {
    let run = || {
        let server = FakeServer::start().unwrap();
        server.add_rule(
            Rule::on_request()
                .op(opcode::PING)
                .chance(1, 2)
                .then(Action::DropReply),
        );
        let mut c = open(&server);
        let mut out = Vec::new();
        for _ in 0..12 {
            c.send(xid::PING, opcode::PING, &[]).unwrap();
            out.push(c.read_frame(Duration::from_millis(60)).is_ok());
        }
        out
    };
    let a = run();
    assert_eq!(a, run());
    assert!(a.contains(&true) && a.contains(&false));
}

#[test]
fn refused_after_stop() {
    let mut server = FakeServer::start().unwrap();
    let addr = server.addr();
    server.stop();
    assert!(RawClient::connect(addr).is_err());
    server.resume().unwrap();
    let _c = open(&server);
}
