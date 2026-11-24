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

//! Teardown stage ordering and FIFO completion failure.
//!
//! Verifies that connection teardown across all disconnect causes emits outputs strictly ordered,
//! and fails pending requests in submission order with the profile appropriate return code.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod props_common;

use std::cell::RefCell;
use std::rc::Rc;

use props_common::{Profile, check_once, config, profile};
use proptest::prelude::*;
use zk_jute::{opcode, rc};
use zk_proto::{Output, Route, State, TransportFailure};
use zk_testkit::server::{HandshakeStyle, Rule, RuleAction};
use zk_testkit::{ConnectStyle, Event, Harness, TestTokens, Ticket, WatcherId};

/// Disconnect trigger that initiates connection teardown.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Trigger {
    /// Server TCP reset (`Io { ECONNRESET }`).
    Reset,
    /// Server clean TCP close (`Eof`).
    CloseLink,
    /// Driver-injected I/O error.
    Io,
    /// Driver-injected EOF.
    Eof,
    /// Receive timeout due to read inactivity.
    RecvTimeout,
    /// Server reply xid mismatch against the pending FIFO head.
    XidMismatch,
    /// Session expiration on the subsequent reconnect handshake while requests wait in the queue.
    Expired,
}

fn trigger() -> impl Strategy<Value = Trigger> {
    prop_oneof![
        Just(Trigger::Reset),
        Just(Trigger::CloseLink),
        Just(Trigger::Io),
        Just(Trigger::Eof),
        Just(Trigger::RecvTimeout),
        Just(Trigger::XidMismatch),
        Just(Trigger::Expired),
    ]
}

/// Returns the expected completion failure code for a teardown trigger.
/// Legacy profiles map each cause to its specific return code, whereas the
/// native `DisconnectRc::ConnectionLoss` policy maps all recoverable causes
/// to `ZCONNECTIONLOSS`.
fn expected_rc(p: Profile, t: Trigger) -> i32 {
    match (t, p.is_native()) {
        (Trigger::Expired, _) => rc::ZSESSIONEXPIRED,
        (_, true) => rc::ZCONNECTIONLOSS,
        (Trigger::RecvTimeout, false) => rc::ZOPERATIONTIMEOUT,
        (Trigger::XidMismatch, false) => rc::ZRUNTIMEINCONSISTENCY,
        (_, false) => rc::ZCONNECTIONLOSS,
    }
}

/// Maps a teardown output to its stage rank (`0..=8`), or `None` if unrelated to teardown.
fn rank(o: &Output<TestTokens>) -> Option<u8> {
    match o {
        Output::Log(l) => {
            let name = format!("{:?}", l.site);
            if name.starts_with("Sock") {
                Some(0)
            } else if name == "CleanupSessionState" || name == "CleanupSessionConnecting" {
                Some(2)
            } else if name == "CleanupPrevious" {
                Some(6)
            } else {
                None
            }
        }
        Output::Session { .. } => Some(2),
        Output::Completion { .. } => Some(3),
        Output::AuthLockpoint => Some(4),
        Output::Auth { .. } => Some(5),
        Output::DrainPoint => Some(7),
        Output::WatchTerminated { .. } => Some(8),
        Output::Watch { .. } | Output::ClientId(_) => None,
    }
}

/// Connection state and transport presence observed when popping an output.
#[derive(Clone, Copy, Debug)]
struct Seen {
    has_transport: bool,
    state: State,
}

#[derive(Clone, Debug)]
struct Case {
    p: Profile,
    seed: u64,
    trigger: Trigger,
    /// Pending requests: `(path_index, watch_flag, direct_route_flag)`.
    reqs: Vec<(usize, bool, bool)>,
    auths: usize,
    /// Whether to register an active data watch prior to teardown.
    watch: bool,
}

fn case() -> impl Strategy<Value = Case> {
    (
        profile(),
        any::<u64>(),
        trigger(),
        proptest::collection::vec((0usize..3, any::<bool>(), any::<bool>()), 0..8),
        0usize..3,
        any::<bool>(),
    )
        .prop_map(|(p, seed, trigger, reqs, auths, watch)| Case {
            p,
            seed,
            trigger,
            reqs,
            auths,
            watch,
        })
}

fn fail(e: impl std::fmt::Debug) -> TestCaseError {
    TestCaseError::fail(format!("{e:?}"))
}

/// Configures the server to ignore data/watch/auth requests so they remain pending.
fn hold_requests(h: &mut Harness) {
    for op in [
        opcode::GET_DATA,
        opcode::EXISTS,
        opcode::GET_CHILDREN,
        opcode::SET_AUTH,
    ] {
        h.server_mut()
            .add_rule(Rule::on_request().op(op).then(RuleAction::Ignore));
    }
}

fn queue_pending(
    h: &mut Harness,
    c: &Case,
) -> Result<(Vec<Ticket>, Vec<zk_testkit::AuthTicket>), TestCaseError> {
    let ops = h.ops();
    let mut auths = Vec::new();
    for i in 0..c.auths {
        auths.push(
            h.add_auth("digest", format!("u{i}:pw").as_bytes())
                .map_err(fail)?,
        );
    }
    let mut tickets = Vec::new();
    for (i, (path, watch, direct)) in c.reqs.iter().enumerate() {
        let p = ["/a", "/b", "/c"][*path];
        let w = watch.then(|| WatcherId::new(7, u32::try_from(i).unwrap()));
        let op = match i % 3 {
            0 => ops.get_data(p, w),
            1 => ops.exists(p, w),
            _ => ops.get_children(p, w),
        };
        let route = if *direct && c.p == Profile::Mt {
            Route::Direct
        } else {
            Route::Queued
        };
        tickets.push(
            h.submit_with(op, route, zk_proto::InputDefect::default())
                .map_err(fail)?,
        );
    }
    Ok((tickets, auths))
}

fn drained_since(h: &Harness, mark: usize) -> bool {
    h.recorder()
        .outputs_since(mark)
        .any(|o| matches!(o, Output::DrainPoint))
}

fn run(c: &Case) -> Result<(), TestCaseError> {
    let mut h = c.p.builder(c.seed).build();
    let seen: Rc<RefCell<Vec<Seen>>> = Rc::new(RefCell::new(Vec::new()));
    let s2 = Rc::clone(&seen);
    h.set_hook(Box::new(move |ctx, _o| {
        s2.borrow_mut().push(Seen {
            has_transport: ctx.conn.has_transport(),
            state: ctx.conn.state(),
        });
    }));
    h.connect().map_err(fail)?;
    if c.watch {
        let ops = h.ops();
        h.server_mut().create("/w", b"x", 0).map_err(fail)?;
        h.call(ops.get_data("/w", Some(WatcherId::new(9, 9))))
            .map_err(fail)?;
    }
    hold_requests(&mut h);
    let (tickets, auths, mark) = if c.trigger == Trigger::Expired {
        h.server_mut().add_rule(
            Rule::on_connect()
                .conn(1)
                .then(RuleAction::Handshake(HandshakeStyle::Expired)),
        );
        // Delay TCP connect completion so requests can be queued prior to the handshake reply.
        h.net_mut()
            .set_connect_style(ConnectStyle::InProgress { delay_ms: 50 });
        let m0 = h.recorder().mark();
        h.reset_current();
        // C `zoo_add_auth` rejects calls in `ZOO_CLOSED_STATE` (0) (ZOOKEEPER-800),
        // so wait until the reconnect attempt leaves `Closed`.
        h.run_until(
            |h| drained_since(h, m0) && h.state() != State::Closed,
            30_000,
        )
        .map_err(fail)?;
        let mark = h.recorder().mark();
        let (t, a) = queue_pending(&mut h, c)?;
        (t, a, mark)
    } else {
        let (t, a) = queue_pending(&mut h, c)?;
        // Advance time so queued frames are transmitted to the server.
        h.run_for(100).map_err(fail)?;
        let mark = h.recorder().mark();
        match c.trigger {
            Trigger::Reset => h.reset_current(),
            Trigger::CloseLink => h.close_current(),
            Trigger::Io => {
                h.inject_failure(TransportFailure::Io { errno: 104 });
            }
            Trigger::Eof => {
                h.inject_failure(TransportFailure::Eof);
            }
            Trigger::RecvTimeout => h.pause_reads(true),
            Trigger::XidMismatch => {
                let link = h.link().unwrap();
                let mut f = Vec::new();
                f.extend_from_slice(&16i32.to_be_bytes());
                f.extend_from_slice(&(i32::MAX - 3).to_be_bytes());
                f.extend_from_slice(&1i64.to_be_bytes());
                f.extend_from_slice(&0i32.to_be_bytes());
                h.server_mut().push_raw(link, f);
            }
            Trigger::Expired => unreachable!(),
        }
        (t, a, mark)
    };
    h.run_until(|h| drained_since(h, mark), 30_000)
        .map_err(fail)?;
    h.pause_reads(false);

    // Collect outputs from `mark` through `DrainPoint` and any trailing T6 `WatchTerminated`.
    let out_mark = h.recorder().all()[..mark]
        .iter()
        .filter(|r| matches!(r.event, Event::Output(_)))
        .count();
    let mut window: Vec<(&Output<TestTokens>, Seen)> = Vec::new();
    let seen = seen.borrow();
    let mut drained = false;
    for (i, o) in h.recorder().outputs().enumerate().skip(out_mark) {
        if drained && !matches!(o, Output::WatchTerminated { .. }) {
            break;
        }
        drained |= matches!(o, Output::DrainPoint);
        window.push((o, seen[i]));
    }
    let want_rc = expected_rc(c.p, c.trigger);

    // Verify that stage ranks are monotonically non-decreasing.
    let ranked: Vec<(u8, &Output<TestTokens>, Seen)> = window
        .iter()
        .filter_map(|(o, s)| rank(o).map(|r| (r, *o, *s)))
        .collect();
    for w in ranked.windows(2) {
        prop_assert!(
            w[0].0 <= w[1].0,
            "{:?} came before {:?}",
            props_common::kind(w[0].1),
            props_common::kind(w[1].1)
        );
    }
    // T0: exactly one socket error log precedes all other teardown outputs.
    let t0 = ranked.iter().filter(|(r, _, _)| *r == 0).count();
    prop_assert_eq!(t0, 1, "T0 lines");
    prop_assert_eq!(ranked[0].0, 0);

    // T2: session event follows its cleanup log after T1 has closed the transport.
    // Under ST, `Session` is a barrier so the pre-transition state remains visible.
    let sessions: Vec<(usize, State, Seen)> = window
        .iter()
        .enumerate()
        .filter_map(|(i, (o, s))| match o {
            Output::Session { state, .. } => Some((i, *state, *s)),
            _ => None,
        })
        .collect();
    prop_assert_eq!(sessions.len(), 1, "T2 session events");
    let (si, sstate, sseen) = sessions[0];
    let want_state = if c.trigger == Trigger::Expired {
        State::ExpiredSession
    } else {
        State::Connecting
    };
    prop_assert_eq!(sstate, want_state);
    prop_assert!(!sseen.has_transport, "T2 before T1");
    prop_assert!(si > 0 && matches!(window[si - 1].0, Output::Log(_)));
    if c.p == Profile::St && c.trigger != Trigger::Expired {
        prop_assert!(sseen.state.is_connected(), "ST T2 state {:?}", sseen.state);
    }

    // T3: pending requests fail once in FIFO submission order with `want_rc`,
    // followed by `AuthLockpoint` (legacy C profiles only) and `Auth` completion.
    let failed: Vec<Ticket> = window
        .iter()
        .filter_map(|(o, _)| match o {
            Output::Completion { token, result, .. } => {
                Some((token.ticket(), result.rc, result.origin))
            }
            _ => None,
        })
        .map(|(t, r, origin)| {
            assert_eq!(r, want_rc, "ticket {} rc", t.0);
            assert_eq!(origin, zk_proto::Origin::Teardown);
            t
        })
        .collect();
    prop_assert_eq!(&failed, &tickets);
    let locks = window
        .iter()
        .filter(|(o, _)| matches!(o, Output::AuthLockpoint))
        .count();
    prop_assert_eq!(locks, usize::from(!c.p.is_native()), "lock points");
    let auth_out: Vec<(Vec<zk_testkit::AuthTicket>, i32)> = window
        .iter()
        .filter_map(|(o, _)| match o {
            Output::Auth { tokens, rc, .. } => {
                Some((tokens.iter().map(|a| a.ticket()).collect(), *rc))
            }
            _ => None,
        })
        .collect();
    if auths.is_empty() {
        prop_assert!(auth_out.is_empty());
    } else {
        prop_assert_eq!(auth_out.len(), 1);
        prop_assert_eq!(&auth_out[0].0, &auths);
        prop_assert_eq!(auth_out[0].1, want_rc);
    }

    // T4: `CleanupPrevious` log followed by `DrainPoint` (state `Closed` on
    // recoverable disconnect, or `ExpiredSession` on session expiry).
    let prev = ranked.iter().filter(|(r, _, _)| *r == 6).count();
    prop_assert_eq!(prev, 1);
    let drain = window
        .iter()
        .find(|(o, _)| matches!(o, Output::DrainPoint))
        .map(|(_, s)| s.state);
    let want_drain = if c.trigger == Trigger::Expired {
        State::ExpiredSession
    } else {
        State::Closed
    };
    prop_assert_eq!(drain, Some(want_drain));

    // T6: native profile emits `WatchTerminated` on terminal session expiry,
    // retaining watches across recoverable disconnects for `SetWatches` restore.
    let ended = window
        .iter()
        .any(|(o, _)| matches!(o, Output::WatchTerminated { .. }));
    if !(c.p.is_native() && c.trigger == Trigger::Expired && c.watch) {
        prop_assert!(!ended, "watches ended");
    } else {
        prop_assert!(ended, "watches not ended");
    }
    drop(seen);
    let f = h.finish();
    check_once(&f)
}

proptest! {
    #![proptest_config(config(0x5eed_0003))]

    /// Verifies teardown stage ordering (`T0..T6`) and FIFO request failure across all disconnect
    /// triggers (`handle_error` and `cleanup_bufs`).
    #[test]
    fn teardown_outputs_follow_the_stages(c in case()) {
        run(&c)?;
    }
}
