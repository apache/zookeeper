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

//! Parity tests for the legacy `TestZookeeperClose.cc` (`Zookeeper_close`) suite.
//!
//! Where the C suite uses `free` and `pthread` mocks to verify handle deallocation, conditional
//! `CLOSE` transmission, and thread joining, these tests verify the protocol level invariants on
//! the driver core: `CLOSE` request emission, transport teardown, completion draining, and absence
//! of residual outputs. Thread teardown is covered by the C ABI conformance suite (`close.c`,
//! TH-01).

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

use std::cell::Cell;
use std::rc::Rc;

use common::{Profile, connects};
use parity_g2::{connect_states, sessions_since};
use zk_jute::{opcode, rc};
use zk_proto::{CLOSE_FLUSH_MS, CLOSE_WAIT_MS, Output, State};
use zk_testkit::server::{Rule, RuleAction};
use zk_testkit::{ActionRecord, ConnectStyle, Harness, WatcherId};

/// Default session watcher token used across tests.
const W0: WatcherId = WatcherId::new(40, 0);

fn transport_closes(h: &Harness) -> usize {
    h.recorder()
        .actions()
        .filter(|a| matches!(a, ActionRecord::CloseTransport))
        .count()
}

/// `Zookeeper_close::testCloseUnconnected`: closing before the first step opens no transport,
/// transmits no frames, and leaves no residual completions or outputs.
#[test]
fn test_close_unconnected() {
    for p in Profile::LEGACY {
        let mut h = p.builder(401).default_watcher(Some(W0)).build();
        assert_eq!(h.state(), State::NotConnected, "{p:?}");
        h.close().unwrap();
        assert_eq!(connects(&h), 0, "{p:?}");
        assert_eq!(transport_closes(&h), 0, "{p:?}");
        assert!(sessions_since(&h, 0).is_empty(), "{p:?}");
        let f = h.finish();
        assert!(f.server.inbound().is_empty(), "{p:?}");
        assert!(f.leftovers.completions.is_empty(), "{p:?}");
        assert!(f.leftovers.outputs.is_empty(), "{p:?}");
    }
}

/// `Zookeeper_close::testCloseUnconnected1`: when closing a connected session whose server stops
/// reading after the handshake, the client enqueues a single `CLOSE` request and bounds its flush
/// and wait duration to `CLOSE_FLUSH_MS + CLOSE_WAIT_MS`.
#[test]
fn test_close_unconnected1() {
    for p in Profile::LEGACY {
        let mut h = p.harness(402);
        h.connect().unwrap();
        h.pause_reads(true);
        let start = h.now().0;
        h.close().unwrap();
        let spent = h.now().0 - start;
        assert!(
            spent <= u64::from(CLOSE_FLUSH_MS + CLOSE_WAIT_MS),
            "{p:?}: {spent}"
        );
        assert_eq!(h.server().request_count(opcode::CLOSE), 1, "{p:?}");
        assert_eq!(transport_closes(&h), 1, "{p:?}");
        let f = h.finish();
        assert!(f.leftovers.completions.is_empty(), "{p:?}");
    }
}

/// `Zookeeper_close::testCloseConnected1`: after transitioning through `NOTCONNECTED`,
/// `CONNECTING`, `ASSOCIATING`, and `CONNECTED`, closing transmits a single `CLOSE` request,
/// terminates the server session, and closes the transport once.
#[test]
fn test_close_connected1() {
    for p in Profile::LEGACY {
        let mut h = p.harness(403);
        // Introduce TCP connect and handshake delays so each intermediate state is observable.
        h.net_mut()
            .set_connect_style(ConnectStyle::InProgress { delay_ms: 5 });
        h.server_mut()
            .add_rule(Rule::on_connect().then(RuleAction::Delay(5)));
        let states = connect_states(&mut h);
        assert_eq!(
            states,
            vec![
                State::NotConnected,
                State::Connecting,
                State::Associating,
                State::Connected
            ],
            "{p:?}"
        );
        assert_eq!(h.server().sessions().len(), 1, "{p:?}");
        h.close().unwrap();
        assert_eq!(h.server().request_count(opcode::CLOSE), 1, "{p:?}");
        assert!(h.server().sessions().is_empty(), "{p:?}");
        assert_eq!(transport_closes(&h), 1, "{p:?}");
        h.expect_exactly_once().unwrap();
    }
}

/// Expires the active server session and steps the harness until `EXPIRED_SESSION` is reached.
/// When `close_in_watcher` is true, invokes `begin_close` from the `EXPIRED_SESSION` output hook
/// (mirroring a C watcher calling `zookeeper_close`). Returns the pre-expiry recorder mark.
fn expire(h: &mut Harness, close_in_watcher: bool) -> (usize, Rc<Cell<bool>>) {
    let fired = Rc::new(Cell::new(false));
    if close_in_watcher {
        let seen = Rc::clone(&fired);
        h.set_hook(Box::new(move |ctx, o| {
            if matches!(
                o,
                Output::Session {
                    state: State::ExpiredSession,
                    ..
                }
            ) && !seen.get()
            {
                seen.set(true);
                ctx.conn.begin_close(ctx.now);
            }
        }));
    }
    let mark = h.recorder().mark();
    let sid = h.server().sessions()[0];
    h.server_mut().expire_session(sid);
    h.run_until(|h| h.state() == State::ExpiredSession, 20_000)
        .unwrap();
    (mark, fired)
}

/// `Zookeeper_close::testCloseFromWatcher1`: closing the handle from within the default watcher's
/// `EXPIRED_SESSION` callback suppresses the `CLOSE` request, rejects subsequent submissions with
/// `ZINVALIDSTATE`, and leaves no residual completions.
#[test]
fn test_close_from_watcher1() {
    for p in Profile::LEGACY {
        let mut h = p.builder(404).default_watcher(Some(W0)).build();
        h.connect().unwrap();
        let (mark, fired) = expire(&mut h, true);
        assert!(fired.get(), "{p:?}");
        let ops = h.ops();
        let late = h.submit(ops.exists("/", None));
        h.clear_hook();
        h.close().unwrap();
        assert_eq!(
            sessions_since(&h, mark).last(),
            Some(&(State::ExpiredSession, vec![W0])),
            "{p:?}"
        );
        assert_eq!(h.server().request_count(opcode::CLOSE), 0, "{p:?}");
        // Submissions on an expired session are rejected synchronously during validation.
        assert_eq!(
            late.map(|_| ()).map_err(|r| r.code),
            Err(rc::ZINVALIDSTATE),
            "{p:?}"
        );
        h.expect_exactly_once().unwrap();
        let f = h.finish();
        assert!(f.leftovers.completions.is_empty(), "{p:?}");
    }
}

/// `Zookeeper_close::testIOThreadStoppedOnExpire` (multithreaded profile): following session
/// expiry, the default watcher receives a single `EXPIRED_SESSION` event, no reconnection attempts
/// are made, and a subsequent close completes without sending a `CLOSE` request.
#[test]
fn test_io_thread_stopped_on_expire() {
    let mut h = Profile::Mt.builder(405).default_watcher(Some(W0)).build();
    h.connect().unwrap();
    let (mark, _) = expire(&mut h, false);
    let expired = sessions_since(&h, mark)
        .iter()
        .filter(|(s, w)| *s == State::ExpiredSession && w == &vec![W0])
        .count();
    assert_eq!(expired, 1);
    let before = connects(&h);
    h.run_for(10_000).unwrap();
    assert_eq!(connects(&h), before);
    assert_eq!(h.state(), State::ExpiredSession);
    assert!(!h.conn().has_transport());
    h.close().unwrap();
    assert_eq!(h.server().request_count(opcode::CLOSE), 0);
    h.expect_exactly_once().unwrap();
    assert!(h.finish().leftovers.completions.is_empty());
}
