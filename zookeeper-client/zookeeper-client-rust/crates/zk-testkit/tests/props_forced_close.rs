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

//! Forced close snapshot capture and deferred teardown.
//!
//! Verifies that `handle_forced_close` invoked either from a barrier hook or
//! at an idle point immediately transitions the visible state to
//! `NotConnected` (999), captures pending requests into the deferred teardown
//! snapshot, and defers completion failure until the next
//! `run_pending_teardown` invocation.

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
use std::collections::BTreeSet;
use std::rc::Rc;

use props_common::{Profile, check_once, config, profile};
use proptest::prelude::*;
use zk_jute::{opcode, rc};
use zk_proto::{Cause, Origin, Output, Route, State};
use zk_testkit::server::{Rule, RuleAction};
use zk_testkit::{ConnectStyle, Event, Harness, HarnessError, Ops, Ticket, WatcherId};

/// Injection point for the forced close.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Point {
    /// Inside the hook for the `k`-th barrier output (1-based).
    Barrier(usize),
    /// Between harness steps while connected with no output in flight.
    Quiet,
    /// Between harness steps while a TCP connect is in progress.
    Connecting,
    /// While connected, followed by an explicit close before teardown runs.
    QuietThenClose,
}

fn point() -> impl Strategy<Value = Point> {
    prop_oneof![
        3 => (1usize..8).prop_map(Point::Barrier),
        2 => Just(Point::Quiet),
        1 => Just(Point::Connecting),
        1 => Just(Point::QuietThenClose),
    ]
}

#[derive(Clone, Debug)]
struct Case {
    p: Profile,
    seed: u64,
    point: Point,
    /// Requests ignored by the server so they remain pending in the FIFO.
    held: usize,
    /// Requests answered by the server (routed `Direct` under MT to act as barriers).
    answered: usize,
    auths: usize,
    hold_auth: bool,
    survivors: usize,
}

fn case() -> impl Strategy<Value = Case> {
    (
        profile(),
        any::<u64>(),
        point(),
        0usize..5,
        0usize..5,
        0usize..3,
        any::<bool>(),
        0usize..3,
    )
        .prop_map(
            |(p, seed, point, held, answered, auths, hold_auth, survivors)| Case {
                p,
                seed,
                point,
                held,
                answered,
                auths,
                hold_auth,
                survivors,
            },
        )
}

/// Connection state observed when popping an output.
#[derive(Clone, Copy, Debug)]
struct Seen {
    state: State,
}

/// Snapshot of state captured when `handle_forced_close` is triggered.
#[derive(Clone, Debug)]
struct Trig {
    /// Index of the first output emitted after the trigger.
    out_index: usize,
    /// Recorder event count at the time of the trigger.
    rec_mark: usize,
    /// Ticket of the in-flight completion output, if triggered from a completion barrier.
    current: Option<Ticket>,
    had_transport: bool,
    connected: bool,
    before: State,
    after: State,
    after_pending: usize,
    after_transport: bool,
}

#[derive(Default)]
struct Ctl {
    k: Option<usize>,
    barriers: usize,
    seen: Vec<Seen>,
    trig: Option<Trig>,
    drain: Option<usize>,
    survivors: usize,
    survivor_tickets: Vec<Ticket>,
    errors: Vec<String>,
}

fn fail(e: impl std::fmt::Debug) -> TestCaseError {
    TestCaseError::fail(format!("{e:?}"))
}

/// Invokes `handle_forced_close` and records pre/post state. Survivor requests
/// are submitted afterward so they are excluded from the captured snapshot.
fn trigger(
    ctl: &mut Ctl,
    conn: &mut zk_proto::Connection<zk_testkit::TestTokens>,
    now: zk_proto::Millis,
    out_index: usize,
    rec_mark: usize,
    current: Option<Ticket>,
) -> Trig {
    let had_transport = conn.has_transport();
    let before = conn.state();
    conn.handle_forced_close(now);
    let t = Trig {
        out_index,
        rec_mark,
        current,
        had_transport,
        connected: before.is_connected(),
        before,
        after: conn.state(),
        after_pending: conn.pending_len(),
        after_transport: conn.has_transport(),
    };
    let _ = ctl;
    t
}

fn hold(h: &mut Harness, hold_auth: bool) {
    for op in [opcode::GET_DATA, opcode::EXISTS, opcode::GET_CHILDREN] {
        h.server_mut()
            .add_rule(Rule::on_request().op(op).then(RuleAction::Ignore));
    }
    if hold_auth {
        h.server_mut().add_rule(
            Rule::on_request()
                .op(opcode::SET_AUTH)
                .then(RuleAction::Ignore),
        );
    }
}

fn queue(h: &mut Harness, c: &Case) -> Result<(), TestCaseError> {
    let ops = h.ops();
    // Submit answered requests before held requests so replies arrive in FIFO xid order.
    for _ in 0..c.answered {
        let route = if c.p == Profile::Mt {
            Route::Direct
        } else {
            Route::Queued
        };
        h.submit_with(ops.sync("/"), route, zk_proto::InputDefect::default())
            .map_err(fail)?;
    }
    for i in 0..c.held {
        let op = match i % 3 {
            0 => ops.get_data("/a", None),
            1 => ops.exists("/a", Some(WatcherId::new(3, 3))),
            _ => ops.get_children("/", None),
        };
        h.submit(op).map_err(fail)?;
    }
    for i in 0..c.auths {
        h.add_auth("digest", format!("u{i}:p").as_bytes())
            .map_err(fail)?;
    }
    Ok(())
}

fn ok_or_timeout(r: Result<(), HarnessError>) -> Result<(), TestCaseError> {
    match r {
        Err(HarnessError::Runaway { steps }) => Err(fail(format!("runaway {steps}"))),
        _ => Ok(()),
    }
}

fn run(c: &Case) -> Result<(), TestCaseError> {
    let mut h = c.p.builder(c.seed).build();
    let ops: Ops = h.ops();
    let ctl = Rc::new(RefCell::new(Ctl {
        k: match c.point {
            Point::Barrier(k) => Some(k),
            _ => None,
        },
        survivors: c.survivors,
        ..Ctl::default()
    }));
    let hc = Rc::clone(&ctl);
    h.set_hook(Box::new(move |ctx, o| {
        let mut ctl = hc.borrow_mut();
        let idx = ctl.seen.len();
        ctl.seen.push(Seen {
            state: ctx.conn.state(),
        });
        let fired = ctl.trig.as_ref().is_some_and(|t| t.had_transport);
        let in_window = fired && ctl.drain.is_none();
        if in_window && matches!(o, Output::DrainPoint) {
            ctl.drain = Some(idx);
        }
        if !o.is_barrier() {
            return;
        }
        ctl.barriers += 1;
        if ctl.trig.is_none() && ctl.k == Some(ctl.barriers) {
            let current = match o {
                Output::Completion { token, .. } => Some(token.ticket()),
                _ => None,
            };
            let mark = ctx.recorder.mark();
            let t = trigger(&mut ctl, ctx.conn, ctx.now, idx + 1, mark, current);
            if t.had_transport {
                for i in 0..ctl.survivors {
                    let p = format!("/s{i}");
                    match ctx.submit(ops.create(&p, b"s", 0)) {
                        Ok(t) => ctl.survivor_tickets.push(t),
                        Err(e) => ctl.errors.push(format!("survivor refused: {e:?}")),
                    }
                }
            }
            ctl.trig = Some(t);
        } else if in_window {
            // Re-triggering without an active transport during forced teardown is a no-op.
            ctx.conn.handle_forced_close(ctx.now);
            if ctx.conn.state() != State::NotConnected {
                ctl.errors
                    .push(format!("retrigger changed state to {:?}", ctx.conn.state()));
            }
        }
    }));
    hold(&mut h, c.hold_auth);
    h.connect().map_err(fail)?;
    if c.point == Point::Connecting {
        h.net_mut()
            .set_connect_style(ConnectStyle::InProgress { delay_ms: 200 });
        h.reset_current();
        ok_or_timeout(h.run_until(
            |h| h.conn().has_transport() && h.state() == State::Connecting,
            30_000,
        ))?;
        prop_assert!(h.conn().has_transport());
        prop_assert_eq!(h.state(), State::Connecting);
    }
    queue(&mut h, c)?;
    match c.point {
        Point::Barrier(_) => {}
        Point::Quiet | Point::QuietThenClose => h.run_for(100).map_err(fail)?,
        Point::Connecting => {}
    }
    if !matches!(c.point, Point::Barrier(_)) {
        // Trigger invoked between harness steps while the state machine is not suspended.
        let now = h.now();
        let out_index = ctl.borrow().seen.len();
        let mark = h.recorder().mark();
        prop_assert!(!h.conn().is_suspended());
        let t = {
            let mut g = ctl.borrow_mut();
            trigger(&mut g, h.conn_mut(), now, out_index, mark, None)
        };
        // `handle_forced_close` only emits log outputs synchronously.
        while let Some(o) = h.conn_mut().poll_output() {
            prop_assert!(matches!(o, Output::Log(_)), "trigger output {:?}", o);
            let state = h.conn().state();
            ctl.borrow_mut().seen.push(Seen { state });
            h.recorder_mut().push(now, Event::Output(o));
        }
        ctl.borrow_mut().trig = Some(t);
        if c.point != Point::QuietThenClose {
            for i in 0..c.survivors {
                let t = h
                    .submit(ops.create(&format!("/s{i}"), b"s", 0))
                    .map_err(fail)?;
                ctl.borrow_mut().survivor_tickets.push(t);
            }
        } else {
            h.close().map_err(fail)?;
        }
    }
    if c.point != Point::QuietThenClose {
        let hc = Rc::clone(&ctl);
        ok_or_timeout(h.run_until(
            move |h| {
                let g = hc.borrow();
                let fired = g.trig.as_ref().is_some_and(|t| t.had_transport);
                fired
                    && g.drain.is_some()
                    && g.survivor_tickets
                        .iter()
                        .all(|t| h.recorder().completion_count(*t) > 0)
            },
            60_000,
        ))?;
    }

    let g = ctl.borrow();
    prop_assert!(g.errors.is_empty(), "{:?}", g.errors);
    if let Some(t) = g.trig.clone() {
        check_trigger(&h, c, &g, &t)?;
    }
    drop(g);
    let f = h.finish();
    check_once(&f)
}

fn check_trigger(h: &Harness, c: &Case, g: &Ctl, t: &Trig) -> Result<(), TestCaseError> {
    if !t.had_transport {
        // Without an active transport, `handle_forced_close` is a no-op.
        prop_assert_eq!(t.after, t.before);
        return Ok(());
    }
    // Trigger immediately sets `NotConnected` (999), runs T1, and moves pending FIFO to snapshot.
    prop_assert_eq!(t.after, State::NotConnected);
    prop_assert!(!t.after_transport);
    prop_assert_eq!(t.after_pending, 0);

    // Reconstruct the expected snapshot from submitted requests not yet completed.
    let rec = h.recorder().all();
    let mut done: BTreeSet<Ticket> = BTreeSet::new();
    let mut snapshot: Vec<Ticket> = Vec::new();
    for r in &rec[..t.rec_mark] {
        match &r.event {
            Event::Submitted { ticket, .. } => snapshot.push(*ticket),
            Event::Output(Output::Completion { token, .. }) => {
                done.insert(token.ticket());
            }
            _ => {}
        }
    }
    snapshot.retain(|x| !done.contains(x) && Some(*x) != t.current);

    if c.point == Point::QuietThenClose {
        // Closing before T3 executes fails the captured snapshot with `ZCLOSING`.
        for x in &snapshot {
            let r = h.recorder().completion(*x).unwrap();
            prop_assert_eq!(r.rc, rc::ZCLOSING, "ticket {}", x.0);
        }
        return Ok(());
    }

    let drain = g.drain;
    prop_assert!(drain.is_some(), "no forced drain point");
    let drain = drain.unwrap();
    // Visible state remains `NotConnected` (999) from trigger through T4 `DrainPoint`.
    for (i, s) in g.seen.iter().enumerate().take(drain + 1).skip(t.out_index) {
        prop_assert_eq!(s.state, State::NotConnected, "output {} state", i);
    }
    let window: Vec<&Output<zk_testkit::TestTokens>> = h
        .recorder()
        .outputs()
        .skip(t.out_index)
        .take(drain + 1 - t.out_index)
        .collect();
    // T2: `Session { Connecting }` is emitted only if previously connected.
    let sessions: Vec<State> = window
        .iter()
        .filter_map(|o| match o {
            Output::Session { state, .. } => Some(*state),
            _ => None,
        })
        .collect();
    if t.connected {
        prop_assert_eq!(&sessions, &vec![State::Connecting]);
    } else {
        prop_assert!(sessions.is_empty(), "{:?}", sessions);
    }
    // T0 is omitted for `ForcedClose` to match C `zoo_cycle_next_server` / `update_addrs`.
    for o in &window {
        if let Output::Log(l) = o {
            prop_assert!(
                !format!("{:?}", l.site).starts_with("Sock"),
                "T0 {:?}",
                l.site
            );
        }
    }
    // T3: captured snapshot fails in FIFO order with `ZCONNECTIONLOSS`.
    let failed: Vec<Ticket> = window
        .iter()
        .filter_map(|o| match o {
            Output::Completion { token, result, .. } => {
                assert_eq!(
                    result.rc,
                    rc::ZCONNECTIONLOSS,
                    "ticket {}",
                    token.ticket().0
                );
                assert_eq!(result.origin, Origin::Teardown);
                Some(token.ticket())
            }
            _ => None,
        })
        .collect();
    prop_assert_eq!(&failed, &snapshot);
    for o in &window {
        if let Output::Auth { rc: r, .. } = o {
            prop_assert_eq!(*r, rc::ZCONNECTIONLOSS);
        }
    }
    // Requests submitted after the trigger survive teardown and complete on reconnect.
    for x in &g.survivor_tickets {
        let r = h.recorder().completion(*x);
        prop_assert!(r.is_some(), "survivor {} lost", x.0);
        prop_assert_eq!(r.unwrap().origin, Origin::Reply, "survivor {}", x.0);
    }
    prop_assert_eq!(h.conn().last_teardown_cause(), Some(Cause::ForcedClose));
    Ok(())
}

proptest! {
    #![proptest_config(config(0x5eed_0005))]

    /// Verifies that forced close (`zoo_cycle_next_server` and `update_addrs`) captures the
    /// pending request snapshot and sets state 999 until `run_pending_teardown` drains it.
    #[test]
    fn forced_close_captures_then_finishes(c in case()) {
        run(&c)?;
    }
}

/// Exercises host-list reconfiguration that removes the connected server
/// (C `update_addrs`), triggering a forced close inside `handle_resolved`.
fn run_reconfig(p: Profile, seed: u64, held: usize) -> Result<(), TestCaseError> {
    let mut h = p.builder(seed).servers(2).build();
    let first = h.host_spec(0);
    let (_, r0) = h.set_servers(vec![first]);
    prop_assert_eq!(r0, rc::ZOK);
    let seen: Rc<RefCell<Vec<State>>> = Rc::new(RefCell::new(Vec::new()));
    let s2 = Rc::clone(&seen);
    h.set_hook(Box::new(move |ctx, _o| {
        s2.borrow_mut().push(ctx.conn.state())
    }));
    hold(&mut h, false);
    h.connect().map_err(fail)?;
    prop_assert_eq!(h.conn().current_server(), Some(zk_testkit::server_addr(0)));
    let ops = h.ops();
    let mut tickets = Vec::new();
    for i in 0..held {
        let op = if i % 2 == 0 {
            ops.get_data("/a", None)
        } else {
            ops.exists("/a", None)
        };
        tickets.push(h.submit(op).map_err(fail)?);
    }
    h.run_for(50).map_err(fail)?;
    let out_mark = seen.borrow().len();
    let second = h.host_spec(1);
    let (_, r1) = h.set_servers(vec![second]);
    prop_assert_eq!(r1, rc::ZOK);
    // `handle_resolved` triggers forced close: state transitions to 999 and drops transport.
    prop_assert_eq!(h.state(), State::NotConnected);
    prop_assert!(!h.conn().has_transport());
    ok_or_timeout(h.run_until(
        |h| {
            h.recorder()
                .outputs()
                .skip(out_mark)
                .any(|o| matches!(o, Output::DrainPoint))
        },
        30_000,
    ))?;
    let outs: Vec<String> = h
        .recorder()
        .outputs()
        .skip(out_mark)
        .map(|o| props_common::kind(o).to_owned())
        .collect();
    let drain = outs.iter().position(|k| k == "DrainPoint").unwrap();
    for (i, s) in seen
        .borrow()
        .iter()
        .enumerate()
        .skip(out_mark)
        .take(drain + 1)
    {
        prop_assert_eq!(*s, State::NotConnected, "output {} state", i);
    }
    for t in &tickets {
        let r = h.recorder().completion(*t).unwrap();
        prop_assert_eq!(r.rc, rc::ZCONNECTIONLOSS);
        prop_assert_eq!(r.origin, Origin::Teardown);
    }
    prop_assert_eq!(h.conn().last_teardown_cause(), Some(Cause::ForcedClose));
    // Reconnects to the new ensemble member and resumes the session.
    h.connect().map_err(fail)?;
    prop_assert_eq!(h.conn().current_server(), Some(zk_testkit::server_addr(1)));
    let f = h.finish();
    check_once(&f)
}

proptest! {
    #![proptest_config(props_common::config_cases(64, 0x5eed_0015))]

    // C reference: `update_addrs` during reconfiguration forces transport
    // teardown when the current server is removed from the ensemble list.
    #[test]
    fn reconfig_forces_a_close(p in profile(), seed in any::<u64>(), held in 0usize..5) {
        run_reconfig(p, seed, held)?;
    }
}
