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

//! Watch lifecycle scenarios covering one-shot, persistent, and persistent
//! recursive watches, server and local watch removal, `SetWatches` replay on
//! reconnect, session event fanout, and watch table retention on session expiry.

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

use common::{Profile, connected, run_to_state};
use zk_jute::{JString, opcode, rc};
use zk_proto::{Output, State, TerminateReason};
use zk_testkit::server::{
    ADD_WATCH_PERSISTENT, ADD_WATCH_PERSISTENT_RECURSIVE, InboundKind, WATCHER_CHILDREN,
    WATCHER_DATA,
};
use zk_testkit::{Harness, WatcherId};

const CREATED: i32 = 1;
const DELETED: i32 = 2;
const CHANGED: i32 = 3;
const CHILD: i32 = 4;

/// C `ZooWatcherType` constant for data watches (`ZOO_DATA_WATCH_TYPE`).
const WTYPE_DATA: i32 = 2;

fn events(h: &Harness) -> Vec<(i32, String, Vec<WatcherId>)> {
    h.recorder()
        .watch_events()
        .into_iter()
        .map(|(e, w)| {
            let p = String::from_utf8(e.path.as_bytes().unwrap_or_default().to_vec()).unwrap();
            (e.event_type, p, w.to_vec())
        })
        .collect()
}

fn terminated(h: &Harness) -> Vec<(Vec<WatcherId>, TerminateReason)> {
    h.recorder()
        .outputs()
        .filter_map(|o| match o {
            Output::WatchTerminated { watchers, reason } => Some((watchers.clone(), *reason)),
            _ => None,
        })
        .collect()
}

fn set_watches_frames(h: &Harness) -> usize {
    h.server()
        .inbound()
        .iter()
        .filter(|f| {
            matches!(
                f.kind,
                InboundKind::Request { op, .. } if op == opcode::SET_WATCHES || op == opcode::SET_WATCHES2
            )
        })
        .count()
}

// One-shot data, exists, and child watches fire once on the first matching
// event and are removed from the watcher table upon delivery.
#[test]
fn one_shot_watches_fire_once() {
    for p in Profile::ALL {
        let mut h = connected(p, 31);
        let ops = h.ops();
        let (wd, we, wc) = (
            WatcherId::new(1, 1),
            WatcherId::new(1, 2),
            WatcherId::new(1, 3),
        );
        h.call(ops.create("/d", b"0", 0)).unwrap();
        h.call(ops.get_data("/d", Some(wd))).unwrap();
        let r = h.call(ops.exists("/e", Some(we))).unwrap();
        assert_eq!(r.rc, rc::ZNONODE);
        h.call(ops.get_children("/d", Some(wc))).unwrap();
        assert!(h.conn().has_watcher(b"/d", 2, Some(&wd)));
        assert!(h.conn().has_watcher(b"/e", 2, Some(&we)));
        assert!(h.conn().has_watcher(b"/d", 1, Some(&wc)));

        h.server_mut().set_data("/d", b"1").unwrap();
        h.server_mut().set_data("/d", b"2").unwrap();
        h.server_mut().create("/e", b"", 0).unwrap();
        h.server_mut().create("/d/k", b"", 0).unwrap();
        h.run_for(500).unwrap();
        let ev = events(&h);
        let want = vec![
            (CHANGED, "/d".to_string(), vec![wd]),
            (CREATED, "/e".to_string(), vec![we]),
            (CHILD, "/d".to_string(), vec![wc]),
        ];
        assert_eq!(ev, want, "{p:?}");
        // Specify the watcher ID explicitly because C `pathHasWatcher` treats `None` as a wildcard.
        assert!(!h.conn().has_watcher(b"/d", 2, Some(&wd)), "{p:?}");
        assert!(!h.conn().has_watcher(b"/e", 2, Some(&we)), "{p:?}");
        assert!(!h.conn().has_watcher(b"/d", 1, Some(&wc)), "{p:?}");
    }
}

// Persistent watches fire repeatedly across data and child updates on the
// target znode; persistent recursive watches fire on data, creation, and
// deletion events across the subtree without emitting `CHILD` events.
#[test]
fn persistent_and_recursive_watches_stay_armed() {
    let mut h = connected(Profile::Native, 32);
    let ops = h.ops();
    let (wp, wr) = (WatcherId::new(2, 1), WatcherId::new(2, 2));
    h.call(ops.create("/p", b"", 0)).unwrap();
    h.call(ops.create("/t", b"", 0)).unwrap();
    let r = h
        .call(ops.add_watch("/p", ADD_WATCH_PERSISTENT, wp))
        .unwrap();
    assert_eq!(r.rc, rc::ZOK);
    let r = h
        .call(ops.add_watch("/t", ADD_WATCH_PERSISTENT_RECURSIVE, wr))
        .unwrap();
    assert_eq!(r.rc, rc::ZOK);
    assert!(h.conn().has_watcher(b"/p", 4, Some(&wp)));
    assert!(h.conn().has_watcher(b"/t", 5, Some(&wr)));

    h.server_mut().set_data("/p", b"1").unwrap();
    h.server_mut().set_data("/p", b"2").unwrap();
    h.server_mut().create("/p/c", b"", 0).unwrap();
    h.server_mut().create("/t/a", b"", 0).unwrap();
    h.server_mut().set_data("/t/a", b"x").unwrap();
    h.server_mut().delete("/t/a").unwrap();
    h.run_for(500).unwrap();
    let ev = events(&h);
    let want = vec![
        (CHANGED, "/p".to_string(), vec![wp]),
        (CHANGED, "/p".to_string(), vec![wp]),
        (CHILD, "/p".to_string(), vec![wp]),
        (CREATED, "/t/a".to_string(), vec![wr]),
        (CHANGED, "/t/a".to_string(), vec![wr]),
        (DELETED, "/t/a".to_string(), vec![wr]),
    ];
    assert_eq!(ev, want);
    assert!(h.conn().has_watcher(b"/p", 4, Some(&wp)));
    assert!(h.conn().has_watcher(b"/t", 5, Some(&wr)));
}

// C reference: `zoo_aremove_watches`. Removing all watchers on a path transmits
// `REMOVE_WATCHES` and clears the watch on both server and client; only the
// native profile emits `WatchTerminated { Removed }`.
#[test]
fn server_remove_watches_ends_the_watch() {
    for p in Profile::ALL {
        let mut h = connected(p, 33);
        let ops = h.ops();
        let w = WatcherId::new(3, 1);
        h.call(ops.create("/r", b"", 0)).unwrap();
        h.call(ops.get_data("/r", Some(w))).unwrap();
        let link = h.link().unwrap();
        assert!(h.server().has_watch(link, "/r", WATCHER_DATA));
        let r = h
            .call(ops.remove_watches("/r", WTYPE_DATA, None, false))
            .unwrap();
        assert_eq!(r.rc, rc::ZOK, "{p:?}");
        assert_eq!(h.server().request_count(opcode::REMOVE_WATCHES), 1);
        assert!(!h.server().has_watch(link, "/r", WATCHER_DATA));
        assert!(!h.conn().has_watcher(b"/r", 2, Some(&w)));
        let t = terminated(&h);
        if p.is_native() {
            assert_eq!(t, vec![(vec![w], TerminateReason::Removed)]);
        } else {
            assert!(t.is_empty(), "{p:?}: {t:?}");
        }
        h.server_mut().set_data("/r", b"1").unwrap();
        h.run_for(500).unwrap();
        assert!(events(&h).is_empty(), "{p:?}");
        // Removing an already-removed watcher fails with `ZNOWATCHER`.
        let e = h
            .submit(ops.remove_watches("/r", WTYPE_DATA, Some(w), false))
            .unwrap_err();
        assert_eq!(e.code, rc::ZNOWATCHER);
        h.expect_exactly_once().unwrap();
    }
}

// C reference: `zoo_aremove_watches`. Removing one of multiple watchers on a
// path transmits `CHECK_WATCHES` so the server retains its watch while the
// client removes the targeted watcher upon receiving `ZOK`.
#[test]
fn remove_of_one_watcher_sends_check_watches() {
    for p in Profile::ALL {
        let mut h = connected(p, 39);
        let ops = h.ops();
        let (w1, w2) = (WatcherId::new(3, 3), WatcherId::new(3, 4));
        h.call(ops.create("/c", b"", 0)).unwrap();
        h.call(ops.get_data("/c", Some(w1))).unwrap();
        h.call(ops.get_data("/c", Some(w2))).unwrap();
        let r = h
            .call(ops.remove_watches("/c", WTYPE_DATA, Some(w1), false))
            .unwrap();
        assert_eq!(r.rc, rc::ZOK, "{p:?}");
        assert_eq!(h.server().request_count(opcode::CHECK_WATCHES), 1);
        assert_eq!(h.server().request_count(opcode::REMOVE_WATCHES), 0);
        assert!(!h.conn().has_watcher(b"/c", 2, Some(&w1)));
        assert!(h.conn().has_watcher(b"/c", 2, Some(&w2)));
        h.server_mut().set_data("/c", b"1").unwrap();
        h.run_for(500).unwrap();
        assert_eq!(
            events(&h),
            vec![(CHANGED, "/c".to_string(), vec![w2])],
            "{p:?}"
        );
    }
}

// Spec row "Watcher Table Implementation". Local watch removal (`local = true`)
// sends no wire request, leaving the server watch armed. When the server event
// arrives with no matching client watcher, legacy C profiles emit an empty
// watcher list while the native profile drops the event.
#[test]
fn local_remove_watches_keeps_the_server_watch() {
    for p in Profile::ALL {
        let mut h = connected(p, 34);
        let ops = h.ops();
        let w = WatcherId::new(3, 2);
        h.call(ops.create("/l", b"", 0)).unwrap();
        h.call(ops.get_data("/l", Some(w))).unwrap();
        h.submit(ops.remove_watches("/l", WTYPE_DATA, Some(w), true))
            .unwrap();
        assert_eq!(h.server().request_count(opcode::REMOVE_WATCHES), 0);
        assert!(!h.conn().has_watcher(b"/l", 2, Some(&w)));
        h.server_mut().set_data("/l", b"1").unwrap();
        h.run_for(500).unwrap();
        let ev = events(&h);
        if p.is_native() {
            assert!(ev.is_empty(), "{ev:?}");
        } else {
            assert_eq!(ev, vec![(CHANGED, "/l".to_string(), vec![])], "{p:?}");
        }
    }
}

// C reference: `send_set_watches`. On reconnect, `SetWatches` replays active
// watches using chroot-prefixed server paths, and subsequent watch events
// delivered to the client have the chroot prefix stripped.
#[test]
fn set_watches_replay_after_reconnect_with_chroot() {
    for p in Profile::ALL {
        let mut h = p
            .builder_with(35, |c| c.chroot = Some(JString::from("/app")))
            .build();
        h.server_mut().create("/app", b"", 0).unwrap();
        h.connect().unwrap();
        let ops = h.ops();
        let (wd, we, wc) = (
            WatcherId::new(4, 1),
            WatcherId::new(4, 2),
            WatcherId::new(4, 3),
        );
        h.call(ops.create("/x", b"", 0)).unwrap();
        h.call(ops.get_data("/x", Some(wd))).unwrap();
        h.call(ops.exists("/y", Some(we))).unwrap();
        h.call(ops.get_children("/", Some(wc))).unwrap();
        assert!(h.conn().has_watcher(b"/app/x", 2, Some(&wd)));

        h.reset_current();
        h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
        // Requests submitted while disconnected are transmitted after `SetWatches`.
        let t = h.submit(ops.exists("/x", None)).unwrap();
        h.connect().unwrap();
        h.wait_for(t).unwrap();
        assert_eq!(set_watches_frames(&h), 1, "{p:?}");
        let link = h.link().unwrap();
        assert!(h.server().has_watch(link, "/app/x", WATCHER_DATA), "{p:?}");
        assert!(h.server().has_watch(link, "/app/y", WATCHER_DATA), "{p:?}");
        assert!(
            h.server().has_watch(link, "/app", WATCHER_CHILDREN),
            "{p:?}"
        );
        // Verify wire order on the new transport: `SET_WATCHES` precedes `EXISTS`.
        let ops_on_link: Vec<i32> = h
            .server()
            .inbound()
            .iter()
            .filter(|f| f.link == link)
            .filter_map(|f| match f.kind {
                InboundKind::Request { op, .. } => Some(op),
                _ => None,
            })
            .collect();
        assert_eq!(
            ops_on_link[0],
            opcode::SET_WATCHES,
            "{p:?}: {ops_on_link:?}"
        );
        assert_eq!(ops_on_link[1], opcode::EXISTS);

        h.server_mut().set_data("/app/x", b"1").unwrap();
        h.server_mut().create("/app/y", b"", 0).unwrap();
        h.run_for(500).unwrap();
        let ev = events(&h);
        assert!(
            ev.contains(&(CHANGED, "/x".to_string(), vec![wd])),
            "{p:?}: {ev:?}"
        );
        assert!(
            ev.contains(&(CREATED, "/y".to_string(), vec![we])),
            "{p:?}: {ev:?}"
        );
        assert!(
            ev.contains(&(CHILD, "/".to_string(), vec![wc])),
            "{p:?}: {ev:?}"
        );
    }
}

// When `restore_watches` is disabled under the native profile, disconnect
// omits `SetWatches` and terminates active watches with `ConnectionLost`.
#[test]
fn no_restore_ends_the_watches_on_disconnect() {
    let mut h = Profile::Native
        .builder_with(36, |c| c.restore_watches = false)
        .build();
    h.connect().unwrap();
    let ops = h.ops();
    let w = WatcherId::new(5, 1);
    h.call(ops.create("/z", b"", 0)).unwrap();
    h.call(ops.get_data("/z", Some(w))).unwrap();
    h.reset_current();
    h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
    h.connect().unwrap();
    h.run_for(100).unwrap();
    assert_eq!(set_watches_frames(&h), 0);
    assert_eq!(
        terminated(&h),
        vec![(vec![w], TerminateReason::ConnectionLost)]
    );
    assert!(!h.conn().has_watcher(b"/z", 2, None));
}

// Spec row "Session Event Fanout". Legacy C profiles deliver session state
// events to all registered path watchers followed by the default watcher,
// whereas the native profile delivers session events only to the default watcher.
#[test]
fn session_event_fanout_follows_the_profile() {
    for p in Profile::ALL {
        let w0 = WatcherId::new(6, 0);
        let mut h = p.builder(37).default_watcher(Some(w0)).build();
        h.connect().unwrap();
        let ops = h.ops();
        let w1 = WatcherId::new(6, 1);
        h.call(ops.create("/f", b"", 0)).unwrap();
        h.call(ops.get_data("/f", Some(w1))).unwrap();
        let mark = h.recorder().mark();
        h.reset_current();
        h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
        let got: Vec<Vec<WatcherId>> = h
            .recorder()
            .outputs_since(mark)
            .filter_map(|o| match o {
                Output::Session {
                    state: State::Connecting,
                    watchers,
                    ..
                } => Some(watchers.clone()),
                _ => None,
            })
            .collect();
        let want = if p.is_native() {
            vec![w0]
        } else {
            vec![w1, w0]
        };
        assert_eq!(got, vec![want], "{p:?}");
    }
}

// Spec row "Watch Retention on Expiry". Legacy C profiles retain watcher table
// entries after session expiration, whereas the native profile clears the
// tables and emits `WatchTerminated { SessionExpired }`.
#[test]
fn expiry_clears_watches_only_under_native() {
    for p in Profile::ALL {
        let mut h = connected(p, 38);
        let ops = h.ops();
        let w = WatcherId::new(7, 1);
        h.call(ops.create("/v", b"", 0)).unwrap();
        h.call(ops.get_data("/v", Some(w))).unwrap();
        let sid = h.server().sessions()[0];
        h.server_mut().expire_session(sid);
        run_to_state(&mut h, State::ExpiredSession, 20_000);
        let t = terminated(&h);
        if p.is_native() {
            assert_eq!(t, vec![(vec![w], TerminateReason::SessionExpired)]);
            assert!(!h.conn().has_watcher(b"/v", 2, None));
        } else {
            assert!(t.is_empty(), "{p:?}: {t:?}");
            assert!(h.conn().has_watcher(b"/v", 2, Some(&w)), "{p:?}");
        }
    }
}
