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

//! Authentication scenarios covering `add_auth` before and after session
//! establishment, per-profile completion and resend ordering policies, and
//! terminal teardown on `ZAUTHFAILED`.

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

use common::{Profile, cause, run_to_state};
use zk_jute::{opcode, rc};
use zk_proto::{Cause, Output, State, TerminateReason};
use zk_testkit::server::{InboundKind, Rule, RuleAction};
use zk_testkit::{ActionRecord, Event, Harness, HarnessError, ServerOptions, WatcherId};

fn link_ops(h: &Harness) -> Vec<i32> {
    let link = h.link().unwrap();
    h.server()
        .inbound()
        .iter()
        .filter(|f| f.link == link)
        .filter_map(|f| match f.kind {
            InboundKind::Request { op, .. } => Some(op),
            _ => None,
        })
        .collect()
}

fn link_auth(h: &Harness) -> Vec<(String, String)> {
    let link = h.link().unwrap();
    h.server()
        .link_auth(link)
        .into_iter()
        .map(|(s, u)| (String::from_utf8(s).unwrap(), String::from_utf8(u).unwrap()))
        .collect()
}

fn pair(s: &str, u: &str) -> (String, String) {
    (s.to_string(), u.to_string())
}

fn lockpoints(h: &Harness) -> usize {
    h.recorder()
        .outputs()
        .filter(|o| matches!(o, Output::AuthLockpoint))
        .count()
}

// `add_auth` registered before session establishment is transmitted first upon
// handshake finalization and completes once with `ZOK`. `AuthLockpoint` is
// emitted only under legacy C profiles.
#[test]
fn auth_added_before_connect_goes_out_at_finalize() {
    for p in Profile::ALL {
        let mut h = p.harness(41);
        let t = h.add_auth("digest", b"u1:pw").unwrap();
        h.connect().unwrap();
        h.run_until(|h| !h.recorder().auth_results().is_empty(), 5_000)
            .unwrap();
        assert_eq!(
            h.recorder().auth_results(),
            vec![(vec![t], rc::ZOK)],
            "{p:?}"
        );
        assert_eq!(link_auth(&h), vec![pair("digest", "u1")]);
        assert_eq!(link_ops(&h).first(), Some(&opcode::SET_AUTH), "{p:?}");
        assert_eq!(lockpoints(&h) > 0, !p.is_native(), "{p:?}");
    }
}

// `add_auth` on an established session transmits `SET_AUTH` immediately and
// completes its token with `ZOK`.
#[test]
fn auth_added_after_connect_is_sent_at_once() {
    for p in Profile::ALL {
        let mut h = p.harness(42);
        h.connect().unwrap();
        let t = h.add_auth("digest", b"u2:pw").unwrap();
        h.run_until(|h| !h.recorder().auth_results().is_empty(), 5_000)
            .unwrap();
        assert_eq!(
            h.recorder().auth_results(),
            vec![(vec![t], rc::ZOK)],
            "{p:?}"
        );
        assert_eq!(h.server().request_count(opcode::SET_AUTH), 1);
        assert_eq!(link_auth(&h), vec![pair("digest", "u2")]);
    }
}

// C `zoo_add_auth` rejects calls in `ZOO_CLOSED_STATE` (0) with `ZINVALIDSTATE`,
// whereas the native profile queues the entry and transmits it upon reconnect.
#[test]
fn auth_in_closed_state_follows_the_profile() {
    for p in Profile::ALL {
        let mut h = p.harness(43);
        h.connect().unwrap();
        h.reset_current();
        run_to_state(&mut h, State::Closed, 10_000);
        let r = h.add_auth("digest", b"u3:pw");
        if p.is_native() {
            let t = r.unwrap();
            h.connect().unwrap();
            h.run_until(|h| !h.recorder().auth_results().is_empty(), 5_000)
                .unwrap();
            assert_eq!(h.recorder().auth_results(), vec![(vec![t], rc::ZOK)]);
        } else {
            assert!(
                matches!(
                    r,
                    Err(HarnessError::AuthRejected {
                        code: rc::ZINVALIDSTATE
                    })
                ),
                "{p:?}: {r:?}"
            );
        }
    }
}

/// Each `SET_AUTH` reply completes only the token of its corresponding packet. Native profiles
/// enqueue auth packets at the tail (FIFO), while legacy C profiles prepend each packet at the
/// head of the transmit queue (LIFO).
#[test]
fn auth_completion_is_per_request() {
    for p in Profile::ALL {
        let mut h = p.harness(44);
        h.connect().unwrap();
        let t1 = h.add_auth("digest", b"a:pw").unwrap();
        let t2 = h.add_auth("digest", b"b:pw").unwrap();
        h.run_for(500).unwrap();
        let got = h.recorder().auth_results();
        let (want, wire) = if p.is_native() {
            (
                vec![(vec![t1], rc::ZOK), (vec![t2], rc::ZOK)],
                vec![pair("digest", "a"), pair("digest", "b")],
            )
        } else {
            (
                vec![(vec![t2], rc::ZOK), (vec![t1], rc::ZOK)],
                vec![pair("digest", "b"), pair("digest", "a")],
            )
        };
        assert_eq!(got, want, "{p:?}");
        assert_eq!(link_auth(&h), wire, "{p:?}");
        assert_eq!(h.server().request_count(opcode::SET_AUTH), 2);
    }
}

// Post-reconnect wire order is `SET_AUTH`, `SET_WATCHES`, then queued requests.
// Legacy C profiles resend auth entries in reverse registration order; the
// native profile resends in registration order.
#[test]
fn auth_resend_order_after_reconnect() {
    for p in Profile::ALL {
        let mut h = p.harness(45);
        h.connect().unwrap();
        h.add_auth("digest", b"a:pw").unwrap();
        h.add_auth("digest", b"b:pw").unwrap();
        let ops = h.ops();
        h.call(ops.create("/w", b"", 0)).unwrap();
        h.call(ops.get_data("/w", Some(WatcherId::new(1, 1))))
            .unwrap();
        h.reset_current();
        h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
        let t = h.submit(ops.exists("/w", None)).unwrap();
        h.connect().unwrap();
        h.wait_for(t).unwrap();
        let want = if p.is_native() {
            vec![pair("digest", "a"), pair("digest", "b")]
        } else {
            vec![pair("digest", "b"), pair("digest", "a")]
        };
        assert_eq!(link_auth(&h), want, "{p:?}");
        assert_eq!(
            link_ops(&h)[..4],
            [
                opcode::SET_AUTH,
                opcode::SET_AUTH,
                opcode::SET_WATCHES,
                opcode::EXISTS
            ],
            "{p:?}"
        );
    }
}

// During `Associating` without SASL, C `zoo_add_auth` transmits `SET_AUTH`
// immediately and again upon handshake finalization, whereas the native
// profile transmits it only once. Both profiles complete the auth token once.
#[test]
fn auth_while_associating_is_duplicated_only_by_c() {
    for p in Profile::ALL {
        let mut h = p.harness(46);
        // Delay the handshake response to hold the client in `Associating`.
        h.server_mut()
            .add_rule(Rule::on_connect().once().then(RuleAction::Delay(500)));
        run_to_state(&mut h, State::Associating, 5_000);
        let t = h.add_auth("digest", b"u:pw").unwrap();
        h.connect().unwrap();
        h.run_for(500).unwrap();
        let want = if p.is_native() { 1 } else { 2 };
        assert_eq!(h.server().request_count(opcode::SET_AUTH), want, "{p:?}");
        // Auth token completes exactly once under both profiles.
        let got = h.recorder().auth_results();
        assert_eq!(got.len(), 1, "{p:?}: {got:?}");
        assert_eq!(got[0], (vec![t], rc::ZOK));
    }
}

/// A non-zero `SET_AUTH` reply transitions to terminal `AuthFailed` (-113), closes the transport,
/// emits `Session { AuthFailed }`, and fails pending requests and auth tokens with `ZAUTHFAILED`.
/// Native profiles also emit `WatchTerminated { AuthFailed }`.
#[test]
fn auth_failure_is_a_terminal_teardown() {
    for p in Profile::ALL {
        let mut opts = ServerOptions::default();
        opts.reject_auth_schemes.insert(b"bad".to_vec());
        let mut h = p.builder(47).server_options(opts).build();
        h.connect().unwrap();
        let ops = h.ops();
        let w = WatcherId::new(2, 1);
        h.call(ops.create("/a", b"", 0)).unwrap();
        h.call(ops.get_data("/a", Some(w))).unwrap();
        h.server_mut()
            .add_rule(Rule::on_request().path("/slow").then(RuleAction::Ignore));
        let slow = h.submit(ops.exists("/slow", None)).unwrap();
        h.run_for(50).unwrap();
        let good = h.add_auth("digest", b"g:pw").unwrap();
        let bad = h.add_auth("bad", b"x").unwrap();
        run_to_state(&mut h, State::AuthFailed, 5_000);
        h.run_for(100).unwrap();
        assert_eq!(cause(&h), Some(Cause::AuthFailed), "{p:?}");
        assert_eq!(
            h.recorder().session_states().last(),
            Some(&State::AuthFailed),
            "{p:?}"
        );
        h.expect_completion(slow, rc::ZAUTHFAILED).unwrap();
        // Each reply completes only its corresponding token. Native queues at the tail so `good`
        // succeeds before `bad` fails. C prepends at the head so `bad` fails first and teardown
        // fails `good` with `ZAUTHFAILED`.
        let res = h.recorder().auth_results();
        if p.is_native() {
            assert_eq!(
                res,
                vec![(vec![good], rc::ZOK), (vec![bad], rc::ZAUTHFAILED)]
            );
            let t: Vec<_> = h
                .recorder()
                .outputs()
                .filter_map(|o| match o {
                    Output::WatchTerminated { watchers, reason } => {
                        Some((watchers.clone(), *reason))
                    }
                    _ => None,
                })
                .collect();
            assert_eq!(t, vec![(vec![w], TerminateReason::AuthFailed)]);
        } else {
            assert_eq!(
                res,
                vec![(vec![bad], rc::ZAUTHFAILED), (vec![good], rc::ZAUTHFAILED)],
                "{p:?}"
            );
        }
        assert!(
            h.recorder()
                .actions()
                .any(|a| matches!(a, ActionRecord::CloseTransport))
        );
        assert!(!h.conn().has_transport());
        let n = h.server().connect_requests().len();
        h.run_for(10_000).unwrap();
        assert_eq!(h.server().connect_requests().len(), n, "{p:?}");
        let r = h.add_auth("digest", b"late:pw");
        assert!(matches!(
            r,
            Err(HarnessError::AuthRejected {
                code: rc::ZINVALIDSTATE
            })
        ));
        h.expect_exactly_once().unwrap();
    }
}

// Under ST, auth failure suspends at `AuthLockpoint`, `Auth { barrier: true }`,
// and `Session { state: AuthFailed, barrier: true }`, resuming once per barrier.
#[test]
fn auth_failure_barriers_under_st() {
    let mut opts = ServerOptions::default();
    opts.reject_auth_schemes.insert(b"bad".to_vec());
    let mut h = Profile::St.builder(48).server_options(opts).build();
    h.connect().unwrap();
    let mark = h.recorder().mark();
    h.add_auth("bad", b"x").unwrap();
    run_to_state(&mut h, State::AuthFailed, 5_000);
    h.run_for(100).unwrap();
    let ev: Vec<&Event> = h.recorder().since(mark).iter().map(|r| &r.event).collect();
    let auth_barrier = ev
        .iter()
        .position(|e| {
            matches!(
                e,
                Event::Output(Output::Auth {
                    barrier: true,
                    rc: rc::ZAUTHFAILED,
                    ..
                })
            )
        })
        .expect("Auth barrier");
    assert!(matches!(ev[auth_barrier + 1], Event::Stopped(_)));
    assert!(matches!(ev[auth_barrier + 2], Event::Resumed(_)));
    let lock = ev
        .iter()
        .position(|e| matches!(e, Event::Output(Output::AuthLockpoint)))
        .unwrap();
    assert!(lock < auth_barrier);
    assert!(matches!(ev[lock + 1], Event::Stopped(_)));
    // Teardown runs after the `Auth` barrier, emitting `CloseTransport` and
    // the `Session { AuthFailed }` barrier.
    let close = ev
        .iter()
        .position(|e| matches!(e, Event::Action(ActionRecord::CloseTransport)))
        .unwrap();
    let sess = ev
        .iter()
        .position(|e| {
            matches!(
                e,
                Event::Output(Output::Session {
                    state: State::AuthFailed,
                    barrier: true,
                    ..
                })
            )
        })
        .unwrap();
    assert!(
        auth_barrier < close && auth_barrier < sess,
        "{auth_barrier} {close} {sess}"
    );
    assert!(matches!(ev[sess + 1], Event::Stopped(_)));
    // SASL is disabled, so no SASL `Dispose` action is emitted.
    assert!(
        !ev.iter()
            .any(|e| matches!(e, Event::Action(ActionRecord::Sasl { .. })))
    );
}
