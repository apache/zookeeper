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

//! Integration tests for watch registration, event delivery order, and watch removal.

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

use common::{SHORT, WAIT, create_ok, delete, exists, get_children, get_data, open, set_data};
use zk_fakeserver::{FakeServer, RawClient, add_watch_mode, event, watcher_type};
use zk_jute::proto::{
    AddWatchRequest, CheckWatchesRequest, RemoveWatchesRequest, SetWatches, SetWatches2,
};
use zk_jute::rc::{ZMARSHALLINGERROR, ZNOWATCHER};
use zk_jute::{JString, JVec, opcode, xid};

fn ev(c: &mut RawClient) -> (i32, String) {
    let e = c.next_event(WAIT).unwrap();
    assert_eq!(e.state, event::SYNC_CONNECTED);
    (e.event_type, e.path)
}

fn no_event(c: &mut RawClient) {
    let r = c.next_event(SHORT);
    assert!(r.is_err(), "unexpected event {r:?}");
}

#[test]
fn one_shot_data_watch_fires_before_reply() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/w", b"", 0);
    assert_eq!(get_data(&mut c, "/w", true).err, 0);
    // When a client triggers its own watch, the watch event frame precedes the operation reply.
    let xid = c
        .send_record(
            opcode::SET_DATA,
            &zk_jute::proto::SetDataRequest {
                path: "/w".into(),
                data: zk_jute::JBuffer::from(&b"1"[..]),
                version: -1,
            },
        )
        .unwrap();
    let first = c.read_frame(WAIT).unwrap();
    let head = zk_fakeserver::RawReply::parse(&first).unwrap();
    assert_eq!(head.xid, xid::WATCHER_EVENT);
    assert_eq!(head.zxid, server.cluster().zxid());
    assert_eq!(c.read_reply(WAIT).unwrap().xid, xid);
    assert_eq!(set_data(&mut c, "/w", b"2", -1).err, 0);
    assert!(c.take_events().is_empty());
}

#[test]
fn watch_types_from_another_client() {
    let server = FakeServer::start().unwrap();
    let mut w = open(&server);
    let mut c = open(&server);
    create_ok(&mut c, "/p", b"", 0);
    assert_eq!(exists(&mut w, "/p/new", true).err, zk_jute::rc::ZNONODE);
    assert_eq!(get_children(&mut w, "/p", true).err, 0);
    create_ok(&mut c, "/p/new", b"", 0);
    assert_eq!(ev(&mut w), (event::NODE_CREATED, "/p/new".into()));
    assert_eq!(ev(&mut w), (event::NODE_CHILDREN_CHANGED, "/p".into()));
    assert_eq!(get_data(&mut w, "/p/new", true).err, 0);
    assert_eq!(get_children(&mut w, "/p/new", true).err, 0);
    assert_eq!(get_children(&mut w, "/p", true).err, 0);
    assert_eq!(delete(&mut c, "/p/new", -1).err, 0);
    // Data and child watches on a deleted znode coalesce into a single `NODE_DELETED` event.
    assert_eq!(ev(&mut w), (event::NODE_DELETED, "/p/new".into()));
    assert_eq!(ev(&mut w), (event::NODE_CHILDREN_CHANGED, "/p".into()));
    no_event(&mut w);
    // `getData` returning `ZNONODE` does not register a watch.
    assert_eq!(get_data(&mut w, "/p/new", true).err, zk_jute::rc::ZNONODE);
    create_ok(&mut c, "/p/new", b"", 0);
    no_event(&mut w);
    assert_eq!(server.cluster().watch_count(), 0);
}

#[test]
fn persistent_and_recursive_watches() {
    let server = FakeServer::start().unwrap();
    let mut w = open(&server);
    let mut c = open(&server);
    create_ok(&mut c, "/r", b"", 0);
    let add = |w: &mut RawClient, path: &str, mode| {
        w.call(
            opcode::ADD_WATCH,
            &AddWatchRequest {
                path: path.into(),
                mode,
            },
        )
        .unwrap()
    };
    let r = add(&mut w, "/r", add_watch_mode::PERSISTENT_RECURSIVE);
    assert_eq!(r.err, 0);
    assert_eq!(r.body, vec![0, 0, 0, 0]);
    assert_eq!(add(&mut w, "/r", 7).err, ZMARSHALLINGERROR);
    create_ok(&mut c, "/r/a", b"", 0);
    assert_eq!(ev(&mut w), (event::NODE_CREATED, "/r/a".into()));
    create_ok(&mut c, "/r/a/b", b"", 0);
    assert_eq!(ev(&mut w), (event::NODE_CREATED, "/r/a/b".into()));
    assert_eq!(set_data(&mut c, "/r/a/b", b"x", -1).err, 0);
    assert_eq!(ev(&mut w), (event::NODE_DATA_CHANGED, "/r/a/b".into()));
    no_event(&mut w);

    assert_eq!(add(&mut w, "/r/a", add_watch_mode::PERSISTENT).err, 0);
    assert_eq!(set_data(&mut c, "/r/a", b"x", -1).err, 0);
    assert_eq!(ev(&mut w), (event::NODE_DATA_CHANGED, "/r/a".into()));
    assert_eq!(set_data(&mut c, "/r/a", b"y", -1).err, 0);
    assert_eq!(ev(&mut w), (event::NODE_DATA_CHANGED, "/r/a".into()));

    let check = |w: &mut RawClient, path: &str, ty| {
        w.call(
            opcode::CHECK_WATCHES,
            &CheckWatchesRequest {
                path: path.into(),
                r#type: ty,
            },
        )
        .unwrap()
        .err
    };
    let remove = |w: &mut RawClient, path: &str, ty| {
        w.call(
            opcode::REMOVE_WATCHES,
            &RemoveWatchesRequest {
                path: path.into(),
                r#type: ty,
            },
        )
        .unwrap()
        .err
    };
    assert_eq!(check(&mut w, "/r", watcher_type::PERSISTENT_RECURSIVE), 0);
    assert_eq!(check(&mut w, "/r", watcher_type::DATA), ZNOWATCHER);
    assert_eq!(check(&mut w, "/r", 9), ZMARSHALLINGERROR);
    assert_eq!(remove(&mut w, "/r", watcher_type::PERSISTENT_RECURSIVE), 0);
    assert_eq!(
        remove(&mut w, "/r", watcher_type::PERSISTENT_RECURSIVE),
        ZNOWATCHER
    );
    assert_eq!(remove(&mut w, "/r/a", watcher_type::ANY), 0);
    assert_eq!(set_data(&mut c, "/r/a", b"z", -1).err, 0);
    no_event(&mut w);
}

fn names(v: &[&str]) -> JVec<JString> {
    JVec::from(v.iter().map(|s| JString::from(*s)).collect::<Vec<_>>())
}

#[test]
fn set_watches_after_reconnect() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/s", b"", 0);
    create_ok(&mut c, "/s/old", b"", 0);
    let rel = server.cluster().zxid();
    create_ok(&mut c, "/s/kid", b"", 0);
    assert_eq!(set_data(&mut c, "/s/old", b"x", -1).err, 0);

    let mut w = open(&server);
    let xid = w.take_xid();
    assert_eq!(xid, 1);
    w.send(
        xid::SET_WATCHES,
        opcode::SET_WATCHES,
        &common::bytes(&SetWatches {
            relative_zxid: rel,
            data_watches: names(&["/s/old", "/s/gone", "/s"]),
            exist_watches: names(&["/s/kid", "/s/later"]),
            child_watches: names(&["/s"]),
        }),
    )
    .unwrap();
    let reply = w.read_reply(WAIT).unwrap();
    assert_eq!((reply.xid, reply.err), (xid::SET_WATCHES, 0));
    let evs: Vec<_> = w
        .take_events()
        .into_iter()
        .map(|e| (e.event_type, e.path, e.zxid))
        .collect();
    assert_eq!(
        evs,
        vec![
            (event::NODE_DATA_CHANGED, "/s/old".to_owned(), -1),
            (event::NODE_DELETED, "/s/gone".to_owned(), -1),
            (event::NODE_CREATED, "/s/kid".to_owned(), -1),
            (event::NODE_CHILDREN_CHANGED, "/s".to_owned(), -1),
        ]
    );
    // Unfired watches from `SetWatches` remain armed for subsequent mutations.
    create_ok(&mut c, "/s/later", b"", 0);
    assert_eq!(ev(&mut w), (event::NODE_CREATED, "/s/later".into()));

    w.send(
        xid::SET_WATCHES,
        opcode::SET_WATCHES2,
        &common::bytes(&SetWatches2 {
            relative_zxid: server.cluster().zxid(),
            data_watches: names(&[]),
            exist_watches: names(&[]),
            child_watches: names(&[]),
            persistent_watches: names(&[]),
            persistent_recursive_watches: names(&["/s"]),
        }),
    )
    .unwrap();
    assert_eq!(w.read_reply(WAIT).unwrap().err, 0);
    assert_eq!(set_data(&mut c, "/s/kid", b"1", -1).err, 0);
    assert_eq!(ev(&mut w), (event::NODE_DATA_CHANGED, "/s/kid".into()));
}

#[test]
fn ephemeral_expiry_fires_watches() {
    let server = FakeServer::start().unwrap();
    let (mut owner, r) = common::open_session(&server);
    let mut w = open(&server);
    create_ok(
        &mut owner,
        "/eph",
        b"",
        zk_fakeserver::create_mode::EPHEMERAL,
    );
    assert_eq!(exists(&mut w, "/eph", true).err, 0);
    assert!(server.cluster().expire_session(r.session_id));
    assert_eq!(ev(&mut w), (event::NODE_DELETED, "/eph".into()));
    assert!(owner.wait_closed(WAIT));
}

#[test]
fn injected_event() {
    let server = FakeServer::start().unwrap();
    let (mut c, r) = common::open_session(&server);
    assert!(
        server
            .cluster()
            .notify_session(r.session_id, event::NODE_DATA_CHANGED, 3, "/fake")
    );
    assert_eq!(ev(&mut c), (event::NODE_DATA_CHANGED, "/fake".into()));
}
