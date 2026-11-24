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

//! Parity tests for the legacy `TestWatchers.cc` (`Zookeeper_watchers`) suite.
//!
//! Exercises default watcher and per-request watcher object dispatch across the seven test cases
//! shared by the single threaded and multithreaded C client suites.

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
mod parity_g2;

use common::Profile;
use parity_g2::{CHANGED, CHILD, DELETED, deliveries, sessions_since, watches_since};
use zk_jute::rc;
use zk_proto::State;
use zk_testkit::{Harness, WatcherId};

/// Default session watcher token used across tests.
const W0: WatcherId = WatcherId::new(30, 0);

/// Constructs an unstepped legacy harness configured with default watcher [`W0`].
fn harness(p: Profile, seed: u64) -> Harness {
    p.builder(seed).default_watcher(Some(W0)).build()
}

/// Constructs a connected harness and pre-populates `paths` (in parent-first order) on the server.
fn connected_with(p: Profile, seed: u64, paths: &[&str]) -> Harness {
    let mut h = harness(p, seed);
    h.connect().unwrap();
    for path in paths {
        h.server_mut().create(path, b"", 0).unwrap();
    }
    h
}

/// Closes the active transport and steps the harness until the session leaves `CONNECTED`.
fn disconnect(h: &mut Harness) {
    h.close_current();
    h.run_until(|h| !h.state().is_connected(), 5_000).unwrap();
}

/// `Zookeeper_watchers::testDefaultSessionWatcher1`: establishing a connection delivers a single
/// `CONNECTED` session event exclusively to the default watcher.
#[test]
fn test_default_session_watcher1() {
    for p in Profile::LEGACY {
        let mut h = harness(p, 301);
        let mark = h.recorder().mark();
        h.connect().unwrap();
        assert_eq!(
            sessions_since(&h, mark),
            vec![(State::Connected, vec![W0])],
            "{p:?}"
        );
        assert_eq!(deliveries(&h, mark, W0), 1, "{p:?}");
    }
}

/// `Zookeeper_watchers::testDefaultSessionWatcher2`: a disconnect while a request is in flight
/// fails the request with `ZCONNECTIONLOSS` and delivers a single `CONNECTING` session event to
/// the default watcher.
#[test]
fn test_default_session_watcher2() {
    for p in Profile::LEGACY {
        let mut h = connected_with(p, 302, &["/x", "/x/y", "/x/y/1"]);
        let ops = h.ops();
        h.call(ops.get_data("/x/y/1", None)).unwrap();
        h.server_mut().add_rule(
            zk_testkit::server::Rule::on_request()
                .path("/x/y/1")
                .then(zk_testkit::server::RuleAction::Ignore),
        );
        let t = h.submit(ops.get_data("/x/y/1", None)).unwrap();
        h.run_for(10).unwrap();
        let mark = h.recorder().mark();
        disconnect(&mut h);
        h.expect_completion(t, rc::ZCONNECTIONLOSS).unwrap();
        assert_eq!(
            sessions_since(&h, mark),
            vec![(State::Connecting, vec![W0])],
            "{p:?}"
        );
        assert_eq!(deliveries(&h, mark, W0), 1, "{p:?}");
    }
}

/// `Zookeeper_watchers::testObjectSessionWatcher1`: on disconnect, both an armed node watcher
/// object and the default watcher receive a `CONNECTING` session event, with the node watcher
/// notified first.
#[test]
fn test_object_session_watcher1() {
    for p in Profile::LEGACY {
        let mut h = connected_with(p, 303, &["/x", "/x/y", "/x/y/1"]);
        let w1 = WatcherId::new(31, 1);
        let ops = h.ops();
        let r = h.call(ops.exists("/x/y/1", Some(w1))).unwrap();
        assert_eq!(r.rc, rc::ZOK, "{p:?}");
        let mark = h.recorder().mark();
        disconnect(&mut h);
        assert_eq!(
            sessions_since(&h, mark),
            vec![(State::Connecting, vec![w1, W0])],
            "{p:?}"
        );
        assert_eq!(deliveries(&h, mark, W0), 1, "{p:?}");
        assert_eq!(deliveries(&h, mark, w1), 1, "{p:?}");
    }
}

/// `Zookeeper_watchers::testObjectSessionWatcher2`: when the default watcher is also registered as
/// a path watcher alongside a distinct watcher object, a disconnect delivers the `CONNECTING`
/// session event once to each watcher (deduplicating the default watcher).
#[test]
fn test_object_session_watcher2() {
    for p in Profile::LEGACY {
        let mut h = connected_with(p, 304, &["/a", "/a/b", "/a/b/c", "/x", "/x/y", "/x/y/z"]);
        let w1 = WatcherId::new(31, 2);
        let ops = h.ops();
        h.call(ops.exists("/a/b/c", Some(W0))).unwrap();
        h.call(ops.exists("/x/y/z", Some(w1))).unwrap();
        assert!(h.conn().has_watcher(b"/a/b/c", 2, Some(&W0)), "{p:?}");
        assert!(h.conn().has_watcher(b"/x/y/z", 2, Some(&w1)), "{p:?}");
        let mark = h.recorder().mark();
        disconnect(&mut h);
        let s = sessions_since(&h, mark);
        assert_eq!(s.len(), 1, "{p:?}: {s:?}");
        assert_eq!(s[0].0, State::Connecting, "{p:?}");
        assert_eq!(s[0].1.last(), Some(&W0), "{p:?}: {s:?}");
        assert_eq!(deliveries(&h, mark, W0), 1, "{p:?}");
        assert_eq!(deliveries(&h, mark, w1), 1, "{p:?}");
    }
}

/// `Zookeeper_watchers::testNodeWatcher1`: `CHANGED` events on two distinct paths trigger their
/// respective watcher objects once each without invoking the default watcher.
#[test]
fn test_node_watcher1() {
    for p in Profile::LEGACY {
        let mut h = connected_with(p, 305, &["/a", "/a/b", "/a/b/c", "/x", "/x/y", "/x/y/z"]);
        let (w1, w2) = (WatcherId::new(32, 1), WatcherId::new(32, 2));
        let ops = h.ops();
        h.call(ops.exists("/a/b/c", Some(w1))).unwrap();
        h.call(ops.exists("/x/y/z", Some(w2))).unwrap();
        let mark = h.recorder().mark();
        let link = h.link().unwrap();
        h.server_mut().push_event(link, CHANGED, "/a/b/c", 10);
        h.server_mut().push_event(link, CHANGED, "/x/y/z", 11);
        h.run_for(100).unwrap();
        assert_eq!(
            watches_since(&h, mark),
            vec![
                (CHANGED, "/a/b/c".to_string(), vec![w1]),
                (CHANGED, "/x/y/z".to_string(), vec![w2]),
            ],
            "{p:?}"
        );
        assert_eq!(deliveries(&h, mark, w1), 1, "{p:?}");
        assert_eq!(deliveries(&h, mark, w2), 1, "{p:?}");
        assert_eq!(deliveries(&h, mark, W0), 0, "{p:?}");
    }
}

/// Registers an `exists` watcher (`w1`) and a `getChildren` watcher (`w2`) on `/a`.
fn child_watches(p: Profile, seed: u64) -> (Harness, WatcherId, WatcherId) {
    let mut h = connected_with(p, seed, &["/a"]);
    let (w1, w2) = (WatcherId::new(33, 1), WatcherId::new(33, 2));
    let ops = h.ops();
    h.call(ops.exists("/a", Some(w1))).unwrap();
    h.call(ops.get_children("/a", Some(w2))).unwrap();
    assert!(h.conn().has_watcher(b"/a", 2, Some(&w1)), "{p:?}");
    assert!(h.conn().has_watcher(b"/a", 1, Some(&w2)), "{p:?}");
    (h, w1, w2)
}

/// `Zookeeper_watchers::testChildWatcher1`: a `DELETED` event on `/a` triggers both the `exists`
/// watcher and the child watcher once without invoking the default watcher.
#[test]
fn test_child_watcher1() {
    for p in Profile::LEGACY {
        let (mut h, w1, w2) = child_watches(p, 306);
        let mark = h.recorder().mark();
        let link = h.link().unwrap();
        h.server_mut().push_event(link, DELETED, "/a", 10);
        h.run_for(100).unwrap();
        let ev = watches_since(&h, mark);
        assert_eq!(ev.len(), 1, "{p:?}: {ev:?}");
        assert_eq!((ev[0].0, ev[0].1.as_str()), (DELETED, "/a"), "{p:?}");
        assert_eq!(deliveries(&h, mark, w1), 1, "{p:?}");
        assert_eq!(deliveries(&h, mark, w2), 1, "{p:?}");
        assert_eq!(deliveries(&h, mark, W0), 0, "{p:?}");
    }
}

/// `Zookeeper_watchers::testChildWatcher2`: a `CHILD` event on `/a` triggers only the child
/// watcher, leaving the `exists` watcher armed and the default watcher uninvoked.
#[test]
fn test_child_watcher2() {
    for p in Profile::LEGACY {
        let (mut h, w1, w2) = child_watches(p, 307);
        let mark = h.recorder().mark();
        let link = h.link().unwrap();
        h.server_mut().push_event(link, CHILD, "/a", 10);
        h.run_for(100).unwrap();
        assert_eq!(
            watches_since(&h, mark),
            vec![(CHILD, "/a".to_string(), vec![w2])],
            "{p:?}"
        );
        assert_eq!(deliveries(&h, mark, w1), 0, "{p:?}");
        assert_eq!(deliveries(&h, mark, W0), 0, "{p:?}");
        assert!(h.conn().has_watcher(b"/a", 2, Some(&w1)), "{p:?}");
    }
}
