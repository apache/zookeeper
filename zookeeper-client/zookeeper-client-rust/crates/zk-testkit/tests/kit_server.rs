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

//! Tests for the simulated server ([`ServerSim`]), its wire frame codec, and its in-memory data
//! tree.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::net::SocketAddr;

use zk_jute::proto::{
    ConnectRequest, CreateRequest, CreateResponse, ExistsRequest, GetDataRequest, GetDataResponse,
    ReplyHeader, SyncRequest, WatcherEvent,
};
use zk_jute::{JBuffer, JVec, Record, opcode, rc, xid};
use zk_proto::Millis;
use zk_testkit::server::codec::{
    connect_frame, decode_connect_response, decode_reply, request_frame, split_frames,
};
use zk_testkit::server::tree::EVENT_DATA_CHANGED;
use zk_testkit::server::*;

const ADDR: &str = "127.0.0.1:2181";

fn addr() -> SocketAddr {
    ADDR.parse().unwrap()
}

fn connect_req(session_id: i64, passwd: Vec<u8>) -> ConnectRequest {
    ConnectRequest {
        protocol_version: 0,
        last_zxid_seen: 0,
        time_out: 10_000,
        session_id,
        passwd: JBuffer::Bytes(passwd),
        read_only: false,
    }
}

/// Accepts a link and completes the session handshake, returning `(link_id, session_id)`.
fn open(s: &mut ServerSim, now: Millis) -> (LinkId, i64) {
    let link = s.accept(addr(), now).unwrap();
    s.client_write(link, &connect_frame(&connect_req(0, vec![0; 16])), now);
    let bytes = s.take_available(link, now);
    let frames = split_frames(&bytes).unwrap();
    assert_eq!(frames.len(), 1);
    let resp = decode_connect_response(&frames[0]).unwrap();
    assert_eq!(resp.time_out, 10_000);
    assert_ne!(resp.session_id, 0);
    (link, resp.session_id)
}

/// Writes a single request frame and returns all decoded `(ReplyHeader, body)` frames.
fn call<R: Record>(
    s: &mut ServerSim,
    link: LinkId,
    xid: i32,
    op: i32,
    body: &R,
    now: Millis,
) -> Vec<(ReplyHeader, Vec<u8>)> {
    s.client_write(link, &request_frame(xid, op, Some(body)), now);
    let bytes = s.take_available(link, now);
    split_frames(&bytes)
        .unwrap()
        .iter()
        .map(|f| {
            let (h, rest) = decode_reply(f).unwrap();
            (h, rest.to_vec())
        })
        .collect()
}

fn create_req(path: &str, data: &[u8]) -> CreateRequest {
    CreateRequest {
        path: path.into(),
        data: JBuffer::from(data),
        acl: JVec::Items(open_acl()),
        flags: 0,
    }
}

#[test]
fn handshake_create_get_round_trip() {
    let mut s = ServerSim::new(ServerOptions::default(), 1);
    s.add_listener(addr(), false);
    let now = Millis(10);
    let (link, sid) = open(&mut s, now);
    assert_eq!(s.sessions(), vec![sid]);
    assert_eq!(s.link_session(link), Some(sid));

    let r = call(
        &mut s,
        link,
        1,
        opcode::CREATE,
        &create_req("/a", b"v1"),
        now,
    );
    assert_eq!(r.len(), 1);
    assert_eq!((r[0].0.xid, r[0].0.err), (1, rc::ZOK));
    let body = CreateResponse::from_bytes(&r[0].1).unwrap();
    assert_eq!(body.path.as_str(), Some("/a"));

    let get = GetDataRequest {
        path: "/a".into(),
        watch: false,
    };
    let r = call(&mut s, link, 2, opcode::GET_DATA, &get, now);
    assert_eq!(r[0].0.err, rc::ZOK);
    let body = GetDataResponse::from_bytes(&r[0].1).unwrap();
    assert_eq!(body.data.as_bytes(), Some(&b"v1"[..]));

    let missing = GetDataRequest {
        path: "/b".into(),
        watch: false,
    };
    let r = call(&mut s, link, 3, opcode::GET_DATA, &missing, now);
    assert_eq!(r[0].0.err, rc::ZNONODE);
    assert_eq!(s.request_count(opcode::GET_DATA), 2);
}

#[test]
fn data_watch_fires_once() {
    let mut s = ServerSim::new(ServerOptions::default(), 2);
    s.add_listener(addr(), false);
    let now = Millis(10);
    let (link, _) = open(&mut s, now);
    call(&mut s, link, 1, opcode::CREATE, &create_req("/w", b""), now);
    let get = GetDataRequest {
        path: "/w".into(),
        watch: true,
    };
    call(&mut s, link, 2, opcode::GET_DATA, &get, now);
    assert!(s.has_watch(link, "/w", WATCHER_DATA));
    s.set_data("/w", b"x").unwrap();
    let bytes = s.take_available(link, now);
    let frames = split_frames(&bytes).unwrap();
    assert_eq!(frames.len(), 1);
    let (h, rest) = decode_reply(&frames[0]).unwrap();
    assert_eq!(h.xid, xid::WATCHER_EVENT);
    let ev = WatcherEvent::from_bytes(rest).unwrap();
    assert_eq!(ev.r#type, EVENT_DATA_CHANGED);
    assert_eq!(ev.path.as_str(), Some("/w"));
    assert!(!s.has_watch(link, "/w", WATCHER_DATA));
    s.set_data("/w", b"y").unwrap();
    assert!(!s.has_available(link, now));
}

#[test]
fn rules_override_replies() {
    let mut s = ServerSim::new(ServerOptions::default(), 3);
    s.add_listener(addr(), false);
    let now = Millis(10);
    let (link, _) = open(&mut s, now);
    s.add_rule(
        Rule::on_request()
            .op(opcode::EXISTS)
            .once()
            .then(RuleAction::Error(rc::ZMARSHALLINGERROR)),
    );
    s.add_rule(
        Rule::on_request()
            .op(opcode::SYNC)
            .then(RuleAction::Delay(50)),
    );
    let ex = ExistsRequest {
        path: "/".into(),
        watch: false,
    };
    let r = call(&mut s, link, 1, opcode::EXISTS, &ex, now);
    assert_eq!(r[0].0.err, rc::ZMARSHALLINGERROR);
    let r = call(&mut s, link, 2, opcode::EXISTS, &ex, now);
    assert_eq!(r[0].0.err, rc::ZOK);
    let sync = SyncRequest { path: "/".into() };
    let r = call(&mut s, link, 3, opcode::SYNC, &sync, now);
    assert!(r.is_empty());
    assert_eq!(s.next_wake(now), Some(Millis(60)));
    assert!(s.has_available(link, Millis(60)));
    assert_eq!(s.rule_hits(), vec![1, 1]);
}

#[test]
fn reset_and_session_resume() {
    let mut s = ServerSim::new(ServerOptions::default(), 4);
    s.add_listener(addr(), false);
    let now = Millis(10);
    let (link, sid) = open(&mut s, now);
    let passwd = s.session_passwd(sid).unwrap();
    s.reset_link(link);
    assert_eq!(s.status(link), LinkStatus::Reset);
    s.client_close(link);
    let l2 = s.accept(addr(), now).unwrap();
    s.client_write(l2, &connect_frame(&connect_req(sid, passwd.to_vec())), now);
    let bytes = s.take_available(l2, now);
    let frames = split_frames(&bytes).unwrap();
    let resp = decode_connect_response(&frames[0]).unwrap();
    assert_eq!(resp.session_id, sid);
    assert_eq!(s.sessions(), vec![sid]);
}

#[test]
fn session_expires_without_traffic() {
    let mut s = ServerSim::new(ServerOptions::default(), 5);
    s.add_listener(addr(), false);
    let (link, sid) = open(&mut s, Millis(10));
    s.client_close(link);
    s.tick(Millis(5_000));
    assert_eq!(s.sessions(), vec![sid]);
    s.tick(Millis(20_000));
    assert!(s.sessions().is_empty());
}

proptest::proptest! {
    /// Server request decoding and responses are invariant under arbitrary segmentation of client
    /// wire bytes.
    #[test]
    fn split_invariance(cuts in proptest::collection::vec(1usize..9, 1..40)) {
        let mut bytes = connect_frame(&connect_req(0, vec![0; 16]));
        bytes.extend(request_frame(1, opcode::CREATE, Some(&create_req("/p", b"1"))));
        let get = GetDataRequest { path: "/p".into(), watch: true };
        bytes.extend(request_frame(2, opcode::GET_DATA, Some(&get)));

        let run = |parts: &[&[u8]]| {
            let mut s = ServerSim::new(ServerOptions::default(), 9);
            s.add_listener(addr(), false);
            let link = s.accept(addr(), Millis(1)).unwrap();
            for p in parts {
                s.client_write(link, p, Millis(1));
            }
            (s.take_available(link, Millis(1)), s.inbound().len())
        };
        let whole = run(&[&bytes]);
        let mut parts = Vec::new();
        let mut rest: &[u8] = &bytes;
        for c in cuts.iter().cycle() {
            if rest.is_empty() {
                break;
            }
            let n = (*c).min(rest.len());
            let (a, b) = rest.split_at(n);
            parts.push(a);
            rest = b;
        }
        proptest::prop_assert_eq!(run(&parts), whole);
    }
}

mod codec {
    use zk_jute::proto::{ConnectRequest, ConnectResponse, GetDataRequest};
    use zk_jute::{JBuffer, Record};
    use zk_testkit::server::codec::*;

    #[test]
    fn connect_round_trip() {
        let req = ConnectRequest {
            protocol_version: 0,
            last_zxid_seen: 9,
            time_out: 10_000,
            session_id: 0,
            passwd: JBuffer::Bytes(vec![0; 16]),
            read_only: true,
        };
        let f = connect_frame(&req);
        assert_eq!(f.len(), 49);
        let bodies = split_frames(&f).unwrap();
        assert_eq!(decode_connect_request(&bodies[0]).unwrap(), req);
        let mut short = req.to_bytes();
        short.pop();
        let d = decode_connect_request(&short).unwrap();
        assert!(!d.read_only);
        let resp = ConnectResponse {
            protocol_version: 0,
            time_out: 4000,
            session_id: 5,
            passwd: JBuffer::Bytes(vec![1; 16]),
            read_only: false,
        };
        let long = connect_response_frame(&resp, false);
        assert_eq!(long.len(), 41);
        let short = connect_response_frame(&resp, true);
        assert_eq!(short.len(), 40);
        let b = split_frames(&short).unwrap();
        assert_eq!(decode_connect_response(&b[0]).unwrap(), resp);
    }

    #[test]
    fn request_and_reply_round_trip() {
        let body = GetDataRequest {
            path: "/a".into(),
            watch: true,
        };
        let f = request_frame(3, zk_jute::opcode::GET_DATA, Some(&body));
        let bodies = split_frames(&f).unwrap();
        let (h, rest) = decode_client_frame(&bodies[0]).unwrap();
        assert_eq!((h.xid, h.r#type), (3, zk_jute::opcode::GET_DATA));
        assert_eq!(GetDataRequest::from_bytes(rest).unwrap(), body);
        let r = encode_record_reply::<GetDataRequest>(3, 7, -101, None);
        let rb = split_frames(&r).unwrap();
        let (rh, rest) = decode_reply(&rb[0]).unwrap();
        assert_eq!((rh.xid, rh.zxid, rh.err), (3, 7, -101));
        assert!(rest.is_empty());
    }
}

mod tree {
    use zk_jute::data::{ACL, Id};
    use zk_jute::rc;
    use zk_testkit::server::tree::*;

    fn acl() -> Vec<ACL> {
        vec![ACL {
            perms: 31,
            id: Id {
                scheme: "world".into(),
                id: "anyone".into(),
            },
        }]
    }

    fn args<'a>(path: &'a [u8], acl: &'a [ACL], mode: i32) -> CreateArgs<'a> {
        CreateArgs {
            path,
            data: b"d",
            acl,
            mode,
            ttl: -1,
            session: 7,
        }
    }

    #[test]
    fn paths() {
        assert_eq!(parent_of(b"/a/b"), Some(&b"/a"[..]));
        assert_eq!(parent_of(b"/a"), Some(&b"/"[..]));
        assert_eq!(parent_of(b"/"), None);
        assert_eq!(name_of(b"/a/b"), b"b");
        assert_eq!(join(b"/", b"a"), b"/a");
        assert_eq!(join(b"/a", b"b"), b"/a/b");
        assert!(validate_path(b"/a/b", false).is_ok());
        assert!(validate_path(b"a", false).is_err());
        assert!(validate_path(b"/a/", false).is_err());
        assert!(validate_path(b"/a/", true).is_ok());
        assert!(validate_path(b"/a//b", false).is_err());
        assert!(validate_path(b"/a/./b", false).is_err());
        assert!(is_below(b"/a/b", b"/a"));
        assert!(!is_below(b"/ab", b"/a"));
    }

    #[test]
    fn create_delete_and_versions() {
        let mut t = DataTree::new();
        let a = acl();
        let mut trig = Vec::new();
        let (p, s) = t.create(&args(b"/a", &a, 0), 5, 1, &mut trig).unwrap();
        assert_eq!(p, b"/a");
        assert_eq!(s.czxid, 5);
        assert_eq!(
            t.create(&args(b"/a", &a, 0), 6, 1, &mut trig),
            Err(rc::ZNODEEXISTS)
        );
        assert_eq!(
            t.create(&args(b"/x/y", &a, 0), 6, 1, &mut trig),
            Err(rc::ZNONODE)
        );
        assert_eq!(
            t.create(&args(b"/b", &[], 0), 6, 1, &mut trig),
            Err(rc::ZINVALIDACL)
        );
        let (s1, _) = t.create(&args(b"/a/s-", &a, 2), 7, 1, &mut trig).unwrap();
        assert_eq!(s1, b"/a/s-0000000000");
        let (s2, _) = t.create(&args(b"/a/s-", &a, 2), 8, 1, &mut trig).unwrap();
        assert_eq!(s2, b"/a/s-0000000001");
        assert_eq!(t.delete(b"/a", -1, 9, &mut trig), Err(rc::ZNOTEMPTY));
        let st = t.set_data(b"/a", b"zz", 0, 10, 2, &mut trig).unwrap();
        assert_eq!(st.version, 1);
        assert_eq!(
            t.set_data(b"/a", b"zz", 0, 11, 2, &mut trig),
            Err(rc::ZBADVERSION)
        );
        assert_eq!(t.all_children_number(b"/a"), Ok(2));
        t.create(&args(b"/e", &a, 1), 12, 1, &mut trig).unwrap();
        assert_eq!(
            t.create(&args(b"/e/c", &a, 0), 13, 1, &mut trig),
            Err(rc::ZNOCHILDRENFOREPHEMERALS)
        );
        assert_eq!(t.ephemerals(7, b"/"), vec![b"/e".to_vec()]);
        t.kill_session(7, 14, &mut trig);
        assert!(!t.contains(b"/e"));
        assert_eq!(t.get(b"/").unwrap().stat.num_children, 2);
    }
}
