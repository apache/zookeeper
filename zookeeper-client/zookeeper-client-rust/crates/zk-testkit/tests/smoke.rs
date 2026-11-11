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

//! End-to-end smoke tests exercising the deterministic harness against `zk-proto`.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_jute::rc;
use zk_proto::{Output, Reply, State, TransportFailure};
use zk_testkit::{Event, Harness, WatcherId};

/// ZooKeeper watch event type for node data modification (`ZOO_CHANGED_EVENT`).
const CHANGED: i32 = 3;

#[test]
fn connect_reaches_connected() {
    for mut h in [Harness::st(1), Harness::mt(2), Harness::native(3)] {
        h.connect().unwrap();
        assert!(h.state().is_connected());
        assert_eq!(h.server().sessions().len(), 1);
        assert_eq!(h.server().connect_requests().len(), 1);
        assert!(h.recorder().session_states().contains(&State::Connected));
    }
}

#[test]
fn create_then_get() {
    let mut h = Harness::st(4);
    h.connect().unwrap();
    let ops = h.ops();
    let r = h.call(ops.create("/a", b"hello", 0)).unwrap();
    assert_eq!(r.rc, rc::ZOK);
    let r = h.call(ops.get_data("/a", None)).unwrap();
    assert_eq!(r.rc, rc::ZOK);
    match r.reply {
        Some(Reply::Data { data, .. }) => assert_eq!(data.as_bytes(), Some(&b"hello"[..])),
        other => panic!("unexpected reply {other:?}"),
    }
    let r = h.call(ops.get_data("/missing", None)).unwrap();
    assert_eq!(r.rc, rc::ZNONODE);
    h.expect_exactly_once().unwrap();
}

#[test]
fn watch_fires_once() {
    let mut h = Harness::st(5);
    h.connect().unwrap();
    let ops = h.ops();
    h.call(ops.create("/w", b"1", 0)).unwrap();
    let w = WatcherId::new(9, 1);
    h.call(ops.get_data("/w", Some(w))).unwrap();
    h.server_mut().set_data("/w", b"2").unwrap();
    h.run_until(|h| !h.recorder().watch_events().is_empty(), 10_000)
        .unwrap();
    let events = h.recorder().watch_events();
    assert_eq!(events.len(), 1);
    let (ev, watchers) = events[0];
    assert_eq!(ev.event_type, CHANGED);
    assert_eq!(ev.path.as_str(), Some("/w"));
    assert_eq!(watchers, &[w][..]);
}

#[test]
fn idle_session_sends_pings() {
    let mut h = Harness::st(6);
    h.connect().unwrap();
    h.run_for(20_000).unwrap();
    assert!(h.server().request_count(zk_jute::opcode::PING) >= 2);
    assert!(h.state().is_connected());
    assert_eq!(h.server().sessions().len(), 1);
}

#[test]
fn reconnects_after_reset() {
    let mut h = Harness::st(7);
    h.connect().unwrap();
    let session = h.server().sessions()[0];
    h.reset_current();
    h.run_until(
        |h| {
            h.recorder()
                .failures()
                .any(|(f, _)| matches!(f, TransportFailure::Io { .. }))
        },
        10_000,
    )
    .unwrap();
    h.connect().unwrap();
    assert_eq!(h.server().connect_requests().len(), 2);
    assert_eq!(h.server().sessions(), vec![session]);
    let ops = h.ops();
    let r = h.call(ops.exists("/", None)).unwrap();
    assert_eq!(r.rc, rc::ZOK);
}

#[test]
fn close_ends_the_session() {
    for mut h in [Harness::st(8), Harness::native(9)] {
        h.connect().unwrap();
        let ops = h.ops();
        let t = h.submit(ops.exists("/", None)).unwrap();
        h.wait_for(t).unwrap();
        h.close().unwrap();
        assert_eq!(h.server().request_count(zk_jute::opcode::CLOSE), 1);
        let f = h.finish();
        f.recorder.check_exactly_once().unwrap();
        assert!(f.leftovers.completions.is_empty());
        assert!(
            f.recorder
                .all()
                .iter()
                .any(|r| matches!(r.event, Event::Output(Output::Session { .. })))
        );
    }
}
