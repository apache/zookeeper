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

//! Integration testing of the single threaded polled driver against `zk-fakeserver`.
//!
//! Validates execution under external, applicatio managed event loop scheduling mirroring
//! `libzookeeper_st`: readiness interest computation, descriptor polling, readiness event
//! dispatching, reentrant outbox event draining, and failover across connection refusal
//! conditions.

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

use std::net::{SocketAddr, TcpListener};
use std::time::{Duration, Instant};

use common::{IpHost, WAIT, env, exists};
use zk_driver::net::poll::ZOOKEEPER_READ;
use zk_driver::outbox::{Flavor, Item};
use zk_driver::{Init, InitArgs, InitStep, InterestOut, Outbox, Polled, Step, Unlocked};
use zk_fakeserver::{
    Action, ClusterConfig, FakeServer, HandshakeStyle, Malformed, Rule, Script, ServerOptions,
    event,
};
use zk_proto::rc::{ZCLOSING, ZNOTHING, ZOK};
use zk_proto::{Compat, Config, DriverMode, LogSite, State};
use zk_testkit::tokens::TestTokens;

fn handle(server: &FakeServer) -> Polled<TestTokens> {
    handle_hosts(server.connect_string().into_bytes())
}

/// Initializes a [`Polled`] handle configured for the specified ensemble connection string.
fn handle_hosts(host_string: Vec<u8>) -> Polled<TestTokens> {
    handle_timeout(host_string, 10_000)
}

/// Initializes a [`Polled`] handle with the specified session timeout in milliseconds.
fn handle_timeout(host_string: Vec<u8>, timeout_ms: i32) -> Polled<TestTokens> {
    let args = InitArgs {
        cfg: Config::legacy(timeout_ms),
        host_string,
        compat: Compat::LEGACY_C,
        mode: DriverMode::ST,
        flavor: Flavor::St,
    };
    let mut ob = Outbox::new();
    let mut init = Init::new(args, env(IpHost::new(), false), &mut ob).unwrap();
    let mut proof = Unlocked::new_at_entry();
    loop {
        match init.step(&mut proof, &mut ob) {
            InitStep::More | InitStep::SaslPoint => {
                common::drain(&mut ob);
            }
            InitStep::Ready => break,
            InitStep::Failed(e) => panic!("init failed: {e:?}"),
        }
    }
    Polled::from_init(init)
}

/// Executes a discrete application event loop iteration, collecting emitted outbox items.
fn round(h: &Polled<TestTokens>) -> Vec<Item<TestTokens>> {
    round_with(h, &|_: &Polled<TestTokens>, events: u32| events)
}

/// Executes an application event loop iteration with event readiness transformation applied prior
/// to processing.
fn round_with(
    h: &Polled<TestTokens>,
    map: &dyn Fn(&Polled<TestTokens>, u32) -> u32,
) -> Vec<Item<TestTokens>> {
    let mut items = Vec::new();
    let mut ob = h.outbox();
    let mut out = InterestOut::UNWRITTEN;
    let mut rc = 0;
    let mut step = h.interest(&mut ob, &mut out, &mut rc);
    while step == Step::More {
        items.extend(common::drain(&mut ob));
        step = h.resume(&mut ob, &mut out, &mut rc);
    }
    items.extend(common::drain(&mut ob));
    if out.fd < 0 {
        return items;
    }
    let events = map(h, h.wait_events(&out, 50));
    let mut ob = h.outbox();
    let mut step = h.process(events, &mut ob, &mut rc);
    while step == Step::More {
        items.extend(common::drain(&mut ob));
        step = h.resume(&mut ob, &mut out, &mut rc);
    }
    items.extend(common::drain(&mut ob));
    items
}

#[test]
fn session_connects_and_a_request_completes() {
    let server = FakeServer::start().unwrap();
    let h = handle(&server);
    assert!(common::wait_until(WAIT, || {
        round(&h);
        h.state() == State::Connected.code()
    }));
    let mut ob = h.outbox();
    h.submit(exists(1), &mut ob).ok().unwrap();
    let mut seen = Vec::new();
    assert!(common::wait_until(WAIT, || {
        seen.extend(round(&h));
        seen.iter().any(|i| matches!(i, Item::Result { .. }))
    }));
    let mut rc = 0;
    let mut ob = h.outbox();
    let mut step = h.close(&mut ob, &mut rc);
    let mut out = InterestOut::UNWRITTEN;
    while step == Step::More {
        common::drain(&mut ob);
        step = h.resume(&mut ob, &mut out, &mut rc);
    }
    assert_eq!(step, Step::Release);
}

/// Drives an execution step through continuation barriers, dispatching drained items to the
/// designated callback.
fn finish(
    h: &Polled<TestTokens>,
    mut step: Step,
    ob: &mut Outbox<TestTokens>,
    out: &mut InterestOut,
    rc: &mut i32,
    on_item: &mut dyn FnMut(Item<TestTokens>),
) -> Step {
    loop {
        for item in common::drain(ob) {
            on_item(item);
        }
        if step != Step::More {
            return step;
        }
        step = h.resume(ob, out, rc);
    }
}

fn connected(server: &FakeServer) -> Polled<TestTokens> {
    let h = handle(server);
    assert!(common::wait_until(WAIT, || {
        round(&h);
        h.state() == State::Connected.code()
    }));
    h
}

fn log_pos(items: &[Item<TestTokens>], site: LogSite) -> Option<usize> {
    items
        .iter()
        .position(|i| matches!(i, Item::Log(r) if r.site == site))
}

#[test]
fn close_of_a_session_logs_closing_then_freeing() {
    let server = FakeServer::start().unwrap();
    let h = connected(&server);
    assert!(h.peer_addr().is_some());
    let mut ob = h.outbox();
    let mut out = InterestOut::UNWRITTEN;
    let mut rc = -1;
    let mut items = Vec::new();
    let first = h.close(&mut ob, &mut rc);
    let step = finish(&h, first, &mut ob, &mut out, &mut rc, &mut |i| {
        items.push(i)
    });
    assert_eq!(step, Step::Release);
    assert_eq!(rc, 0);
    let closing = log_pos(&items, LogSite::Closing).expect("the Closing line");
    let freeing = log_pos(&items, LogSite::Freeing).expect("the Freeing line");
    assert!(closing < freeing);
    assert_eq!(h.state(), 0);
}

#[test]
fn close_from_a_result_callback_releases_in_the_driving_call() {
    // tests/TestOperations.cc testCloseWhileInProgressFromCompletion.
    let server = FakeServer::start().unwrap();
    let h = connected(&server);
    let mut ob = h.outbox();
    h.submit(exists(1), &mut ob).ok().unwrap();
    let mut seen: Vec<(u64, i32)> = Vec::new();
    let mut released = false;
    assert!(common::wait_until(WAIT, || {
        let mut ob = h.outbox();
        let mut out = InterestOut::UNWRITTEN;
        let mut rc = 0;
        let first = h.interest(&mut ob, &mut out, &mut rc);
        let mut step = finish(&h, first, &mut ob, &mut out, &mut rc, &mut |_| {});
        if step == Step::Release {
            released = true;
            return true;
        }
        if out.fd >= 0 {
            let events = h.wait_events(&out, 50);
            let mut ob = h.outbox();
            let first = h.process(events, &mut ob, &mut rc);
            let mut on_item = |item: Item<TestTokens>| {
                let Item::Result { token, result, .. } = item else {
                    return;
                };
                seen.push((token.id(), result.rc));
                if token.id() == 1 {
                    // The callback queues one more request, then closes.
                    let mut cob = h.outbox();
                    h.submit(exists(2), &mut cob).ok().unwrap();
                    let mut crc = -1;
                    assert_eq!(h.close(&mut cob, &mut crc), Step::Done(0));
                    common::drain(&mut cob);
                }
            };
            step = finish(&h, first, &mut ob, &mut out, &mut rc, &mut on_item);
        }
        released = step == Step::Release;
        released
    }));
    assert!(released);
    assert_eq!(seen, vec![(1, 0), (2, ZCLOSING)]);
    assert_eq!(h.state(), 0);
}

#[test]
fn a_call_after_an_abandoned_barrier_completes_the_stopped_stage() {
    // The session watcher unwinds at its barrier, so C requeues
    // the outbox and never resumes. The next driving calls complete the
    // stopped stage, then the handle works as before.
    let server = FakeServer::start().unwrap();
    let h = handle(&server);
    let mut abandoned = false;
    assert!(common::wait_until(WAIT, || {
        let mut ob = h.outbox();
        let mut out = InterestOut::UNWRITTEN;
        let mut rc = 0;
        let first = h.interest(&mut ob, &mut out, &mut rc);
        finish(&h, first, &mut ob, &mut out, &mut rc, &mut |_| {});
        if out.fd < 0 {
            return false;
        }
        let events = h.wait_events(&out, 50);
        let mut ob = h.outbox();
        let mut step = h.process(events, &mut ob, &mut rc);
        while step == Step::More {
            while let Some(item) = ob.pop() {
                if matches!(item, Item::Session { .. }) {
                    // The callback of this item throws.
                    h.requeue(ob.requeue());
                    abandoned = true;
                    return true;
                }
            }
            step = h.resume(&mut ob, &mut out, &mut rc);
        }
        common::drain(&mut ob);
        false
    }));
    assert!(abandoned);
    assert!(common::wait_until(WAIT, || {
        round(&h);
        h.state() == State::Connected.code()
    }));
    let mut ob = h.outbox();
    h.submit(exists(1), &mut ob).ok().unwrap();
    let mut seen = Vec::new();
    assert!(common::wait_until(WAIT, || {
        seen.extend(round(&h));
        seen.iter().any(|i| matches!(i, Item::Result { .. }))
    }));
}

#[test]
fn a_read_takes_the_frame_length_and_the_body_in_one_call() {
    // C recv_buffer reads the length, then the body, in one
    // zookeeper_process call. So a READ call with a whole frame on the
    // socket never gives ZNOTHING.
    const READ: u32 = 2;
    let server = FakeServer::start().unwrap();
    let h = handle(&server);
    let mut nothing = 0;
    let mut results = 0;
    let mut sent = false;
    assert!(common::wait_until(WAIT, || {
        let mut ob = h.outbox();
        let mut out = InterestOut::UNWRITTEN;
        let mut rc = 0;
        let first = h.interest(&mut ob, &mut out, &mut rc);
        finish(&h, first, &mut ob, &mut out, &mut rc, &mut |_| {});
        if out.fd < 0 {
            return false;
        }
        let events = h.wait_events(&out, 50);
        let mut ob = h.outbox();
        let first = h.process(events, &mut ob, &mut rc);
        finish(&h, first, &mut ob, &mut out, &mut rc, &mut |i| {
            if matches!(i, Item::Result { .. }) {
                results += 1;
            }
        });
        if events & READ != 0 && rc == ZNOTHING {
            nothing += 1;
        }
        if !sent && h.state() == State::Connected.code() {
            let mut ob = h.outbox();
            h.submit(exists(1), &mut ob).ok().unwrap();
            sent = true;
        }
        results > 0
    }));
    assert_eq!(nothing, 0);
}

/// Asserts that connection refusal delivering POLLHUP/POLLIN readiness without POLLOUT
/// advances failover cursors without infinite spin loops.
#[test]
fn a_refused_connect_with_read_only_events_moves_to_the_next_host() {
    // Bind the port and drop the listener, so nothing listens on it.
    let closed: SocketAddr = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap();
    let server = FakeServer::start().unwrap();
    let h = handle_hosts(format!("{closed},{}", server.connect_string()).into_bytes());
    // A server sends nothing while the TCP connect runs. So READ in that
    // phase comes from a failed connect, and the program gives READ alone.
    let hup_as_read = |p: &Polled<TestTokens>, events: u32| {
        if events & ZOOKEEPER_READ != 0 && p.state() == State::Connecting.code() {
            ZOOKEEPER_READ
        } else {
            events
        }
    };
    let start = Instant::now();
    let mut rounds = 0_u32;
    let mut seen = Vec::new();
    while h.state() != State::Connected.code() && start.elapsed() < WAIT {
        seen.extend(round_with(&h, &hup_as_read));
        rounds += 1;
    }
    assert_eq!(
        h.state(),
        State::Connected.code(),
        "no failover in {rounds} rounds"
    );
    assert!(
        start.elapsed() < Duration::from_secs(2),
        "the attempt waited for the timeout"
    );
    assert!(rounds <= 50, "{rounds} rounds: the loop spins");
    let failed = log_pos(&seen, LogSite::SockRecvFailed)
        .or_else(|| log_pos(&seen, LogSite::SockConnectFailed));
    assert!(failed.is_some(), "no log line for the refused connect");
}

/// Drives single-threaded polling rounds until `n` operation completion results have been collected
/// in arrival order.
fn results(h: &Polled<TestTokens>, n: usize) -> Vec<Item<TestTokens>> {
    let mut got = Vec::new();
    let done = common::wait_until(Duration::from_secs(30), || {
        got.extend(
            round(h)
                .into_iter()
                .filter(|i| matches!(i, Item::Result { .. })),
        );
        got.len() >= n
    });
    assert!(done, "{} of {n} results", got.len());
    got
}

/// Verifies that reply frames straddling the 64 KiB read buffer boundary and a 1 MiB reply frame
/// are reassembled intact and delivered in order.
#[test]
fn frames_at_the_read_buffer_edges_arrive_whole() {
    let server = FakeServer::start().unwrap();
    let h = connected(&server);
    for (i, len) in common::EDGE_FRAME_LENS.into_iter().enumerate() {
        let data = common::pattern(len - common::GET_DATA_FRAME_BASE, i as u8);
        let path = format!("/edge{i}");
        server.cluster().create(&path, &data, 0).unwrap();
        let id = i as u64 + 1;
        let mut ob = h.outbox();
        h.submit(common::get_data_at(id, &path), &mut ob)
            .ok()
            .unwrap();
        let got = results(&h, 1);
        common::check_result(&got[0], id, Some(&data));
    }
}

/// Verifies that interleaved large and small replies flushed in a single server burst preserve
/// payload integrity and completion ordering.
#[test]
fn large_and_small_frames_interleaved_arrive_in_order() {
    let server = FakeServer::start().unwrap();
    let h = connected(&server);
    let mut want: Vec<Option<Vec<u8>>> = Vec::new();
    for (i, len) in common::EDGE_FRAME_LENS.into_iter().enumerate() {
        let data = common::pattern(len - common::GET_DATA_FRAME_BASE, i as u8);
        server
            .cluster()
            .create(&format!("/mix{i}"), &data, 0)
            .unwrap();
        want.push(Some(data));
        want.push(None);
        let small = common::pattern(i + 1, 0x40 + i as u8);
        server
            .cluster()
            .create(&format!("/small{i}"), &small, 0)
            .unwrap();
        want.push(Some(small));
    }
    // Hold server processing so all replies flush in a single coalesced burst upon release.
    server.hold();
    let mut ob = h.outbox();
    for k in 0..want.len() {
        let id = k as u64 + 1;
        let i = k / 3;
        let req = match k % 3 {
            0 => common::get_data_at(id, &format!("/mix{i}")),
            2 => common::get_data_at(id, &format!("/small{i}")),
            _ => common::exists(id),
        };
        h.submit(req, &mut ob).ok().unwrap();
    }
    drop(ob);
    let start = Instant::now();
    while start.elapsed() < Duration::from_millis(200) {
        assert!(round(&h).iter().all(|i| !matches!(i, Item::Result { .. })));
    }
    server.release();
    let got = results(&h, want.len());
    for (k, (item, w)) in got.iter().zip(&want).enumerate() {
        common::check_result(item, k as u64 + 1, w.as_deref());
    }
}

/// Starts a [`FakeServer`] configured with the specified listener options.
fn server_with(opts: ServerOptions) -> FakeServer {
    FakeServer::start_with(ClusterConfig::default(), opts).unwrap()
}

/// Starts a [`FakeServer`] that responds to each `ConnectRequest` according to `rule` with the
/// given inter-chunk delay `gap`.
fn server_on_connect(rule: Rule, gap: Duration) -> FakeServer {
    server_with(ServerOptions {
        script: Script::default().rule(rule),
        chunk_gap: gap,
        ..ServerOptions::default()
    })
}

/// Captured telemetry from a single threaded event loop iteration (`interest`, `poll`, and
/// `process`).
struct Pass {
    /// Readiness interest bitmask returned by `interest`.
    bits: u32,
    /// Poll timeout in milliseconds returned by `interest`.
    timeout_ms: i64,
    /// Readiness events observed by `poll` and passed to `process`.
    events: u32,
    /// Return code produced by `process`.
    rc: i32,
    /// Client session state code immediately prior to `process`.
    before: i32,
    /// Client session state code immediately following `process`.
    after: i32,
    /// Outbox items emitted during the `interest` call.
    interest_items: Vec<Item<TestTokens>>,
    /// Outbox items emitted during the `process` call.
    items: Vec<Item<TestTokens>>,
}

impl Pass {
    fn sessions(&self) -> usize {
        self.items
            .iter()
            .filter(|i| matches!(i, Item::Session { .. }))
            .count()
    }

    fn watches(&self) -> usize {
        self.items
            .iter()
            .filter(|i| matches!(i, Item::Watch { .. }))
            .count()
    }

    fn connected_events(&self) -> usize {
        self.items
            .iter()
            .filter(|i| {
                matches!(
                    i,
                    Item::Session {
                        state: State::Connected,
                        ..
                    }
                )
            })
            .count()
    }

    fn has_log(&self, site: LogSite) -> bool {
        log_pos(&self.interest_items, site).is_some() || log_pos(&self.items, site).is_some()
    }
}

/// Executes one single-threaded event loop pass, pausing for `settle` after a readable poll wakeup
/// so multi-write server flights coalesce in the socket buffer prior to `process`.
fn pass(h: &Polled<TestTokens>, settle: Duration) -> Option<Pass> {
    let mut ob = h.outbox();
    let mut out = InterestOut::UNWRITTEN;
    let mut rc = 0;
    let first = h.interest(&mut ob, &mut out, &mut rc);
    let mut interest_items = Vec::new();
    finish(h, first, &mut ob, &mut out, &mut rc, &mut |i| {
        interest_items.push(i)
    });
    if out.fd < 0 {
        return None;
    }
    let (bits, timeout_ms) = (out.bits, out.timeout_ms);
    let events = h.wait_events(&out, 50);
    if events & ZOOKEEPER_READ != 0 && !settle.is_zero() {
        std::thread::sleep(settle);
    }
    let before = h.state();
    let mut ob = h.outbox();
    let mut items = Vec::new();
    let first = h.process(events, &mut ob, &mut rc);
    finish(h, first, &mut ob, &mut out, &mut rc, &mut |i| items.push(i));
    Some(Pass {
        bits,
        timeout_ms,
        events,
        rc,
        before,
        after: h.state(),
        interest_items,
        items,
    })
}

/// Repeatedly executes event loop passes until `done` is satisfied, returning the recorded passes.
fn passes_until(
    h: &Polled<TestTokens>,
    settle: Duration,
    done: &dyn Fn(&[Pass]) -> bool,
) -> Vec<Pass> {
    let start = Instant::now();
    let mut passes = Vec::new();
    while !done(&passes) && start.elapsed() < WAIT {
        if let Some(p) = pass(h, settle) {
            passes.push(p);
        }
    }
    assert!(done(&passes), "no end in {} passes", passes.len());
    passes
}

fn connected_pass(passes: &[Pass]) -> Option<usize> {
    passes
        .iter()
        .position(|p| p.after == State::Connected.code())
}

/// Verifies handshake event ordering: no `Connected` session event is emitted prior to the
/// `process` pass that completes `ConnectResponse` ingestion, and that pass emits exactly one
/// `Connected` session event.
fn assert_handshake_order(passes: &[Pass]) -> usize {
    let at = connected_pass(passes).expect("a connected pass");
    assert!(passes[..at].iter().all(|p| p.connected_events() == 0));
    let p = &passes[at];
    assert_eq!(p.before, State::Associating.code());
    assert_ne!(p.events & ZOOKEEPER_READ, 0);
    assert_eq!(p.rc, ZOK);
    let states: Vec<State> = p
        .items
        .iter()
        .filter_map(|i| match i {
            Item::Session { state, .. } => Some(*state),
            _ => None,
        })
        .collect();
    assert_eq!(states, [State::Connected]);
    at
}

/// Initiates handle closure on `h` and drives teardown to `Step::Release`.
fn close(h: &Polled<TestTokens>) {
    let mut rc = 0;
    let mut ob = h.outbox();
    let mut out = InterestOut::UNWRITTEN;
    let first = h.close(&mut ob, &mut rc);
    let step = finish(h, first, &mut ob, &mut out, &mut rc, &mut |_| {});
    assert_eq!(step, Step::Release);
}

/// Submits an `exists` request and awaits its completion to verify post-handshake frame alignment.
fn assert_request_completes(h: &Polled<TestTokens>) {
    let mut ob = h.outbox();
    h.submit(exists(1), &mut ob).ok().unwrap();
    let mut seen = Vec::new();
    assert!(common::wait_until(WAIT, || {
        seen.extend(round(h));
        seen.iter().any(|i| matches!(i, Item::Result { .. }))
    }));
}

#[test]
fn a_whole_connect_response_connects_in_the_first_read() {
    let server = FakeServer::start().unwrap();
    let h = handle(&server);
    let passes = passes_until(&h, Duration::ZERO, &|p| connected_pass(p).is_some());
    let at = assert_handshake_order(&passes);
    let first_read = passes
        .iter()
        .position(|p| p.events & ZOOKEEPER_READ != 0)
        .unwrap();
    assert_eq!(at, first_read, "the first READ call reads the whole reply");
    assert_request_completes(&h);
    close(&h);
}

#[test]
fn an_old_server_reply_of_40_bytes_connects_read_write() {
    let server = server_with(ServerOptions {
        handshake: HandshakeStyle::Legacy36,
        ..ServerOptions::default()
    });
    let h = handle(&server);
    let passes = passes_until(&h, Duration::ZERO, &|p| connected_pass(p).is_some());
    let at = assert_handshake_order(&passes);
    assert_eq!(passes[at].after, State::Connected.code(), "not read-only");
    assert_request_completes(&h);
    close(&h);
}

#[test]
fn a_split_connect_response_connects_in_the_same_order() {
    for split in [1usize, 4, 20] {
        let rule = Rule::on_connect().then(Action::Handshake {
            style: HandshakeStyle::Modern,
            split_at: Some(split),
        });
        let server = server_on_connect(rule, Duration::from_millis(30));
        let h = handle(&server);
        let passes = passes_until(&h, Duration::ZERO, &|p| connected_pass(p).is_some());
        let at = assert_handshake_order(&passes);
        let reads = passes[..at]
            .iter()
            .filter(|p| p.events & ZOOKEEPER_READ != 0)
            .count();
        assert!(
            reads >= 1,
            "split {split}: the first part is a separate read"
        );
        assert!(
            passes[..at]
                .iter()
                .filter(|p| p.events & ZOOKEEPER_READ != 0)
                .all(|p| p.rc == ZNOTHING),
            "split {split}"
        );
        assert_request_completes(&h);
        close(&h);
    }
}

#[test]
fn an_old_reply_and_a_notification_in_one_write_give_one_frame_per_call() {
    let rule = Rule::on_connect()
        .then(Action::Handshake {
            style: HandshakeStyle::Legacy36,
            split_at: None,
        })
        .then(Action::Notify {
            event_type: event::NODE_DATA_CHANGED,
            state: event::SYNC_CONNECTED,
            path: "/a".to_string(),
        });
    let server = server_on_connect(rule, Duration::ZERO);
    let h = handle(&server);
    let passes = passes_until(&h, Duration::from_millis(50), &|p| {
        p.iter().any(|x| x.watches() > 0)
    });
    let at = assert_handshake_order(&passes);
    assert_eq!(
        passes[at].watches(),
        0,
        "the connect call gives only the session event"
    );
    let watch = passes.iter().position(|p| p.watches() > 0).unwrap();
    assert!(watch > at);
    assert!(
        watch - at <= 2,
        "the notification waits {} calls",
        watch - at
    );
    for p in &passes {
        assert!(p.sessions() + p.watches() <= 1, "two frames in one call");
    }
    close(&h);
}

#[test]
fn a_negative_connect_response_length_stalls_without_a_busy_poll() {
    // Transmit a negative frame length prefix followed by 37 trailing bytes without a valid
    // handshake response.
    let rule = Rule::on_connect()
        .then(Action::Malformed(Malformed::NegativeLength(-2)))
        .then(Action::SendRaw(vec![0; 37]))
        .then(Action::Ignore);
    let server = server_on_connect(rule, Duration::ZERO);
    let h = handle_timeout(server.connect_string().into_bytes(), 3_000);
    let start = Instant::now();
    let window = Duration::from_millis(1_200);
    let passes = passes_until(&h, Duration::from_millis(20), &|_| {
        start.elapsed() >= window
    });
    let stall = passes
        .iter()
        .position(|p| p.events & ZOOKEEPER_READ != 0)
        .expect("a read of the bad length");
    assert_eq!(passes[stall].rc, ZNOTHING);
    assert_eq!(passes[stall].after, State::Associating.code());
    assert!(
        passes.len() > stall + 1,
        "the window holds passes after the stall"
    );
    // Once stalled on a negative frame length, the reader remains in Associating without polling
    // for further socket reads.
    for p in &passes[stall + 1..] {
        assert_eq!(p.before, State::Associating.code());
        assert_eq!(p.after, State::Associating.code());
        assert_eq!(
            p.bits & ZOOKEEPER_READ,
            0,
            "a stalled reader asks for no READ"
        );
        assert!(p.timeout_ms > 0, "a stalled reader does not busy poll");
        assert_eq!(p.events, 0);
        assert_eq!(p.items.len(), 0);
    }
    assert!(passes.iter().all(|p| p.sessions() == 0));
    close(&h);
    drop(server);
}

#[test]
fn a_bad_connect_response_length_is_a_protocol_error() {
    // Reply to the initial ConnectRequest with a malformed 38-byte body before succeeding on the
    // reconnect attempt.
    let rule = Rule::on_connect()
        .once()
        .then(Action::SendFrame(vec![0; 38]))
        .then(Action::Ignore);
    let server = server_on_connect(rule, Duration::ZERO);
    let h = handle(&server);
    let passes = passes_until(&h, Duration::ZERO, &|p| connected_pass(p).is_some());
    let failed = passes
        .iter()
        .position(|p| p.has_log(LogSite::SockRecvFailed))
        .expect("the protocol error line");
    assert_eq!(passes[failed].before, State::Associating.code());
    assert_ne!(passes[failed].after, State::Associating.code());
    assert_ne!(passes[failed].after, State::Connected.code());
    assert_eq!(passes[failed].sessions(), 0);
    let at = assert_handshake_order(&passes);
    assert!(at > failed);
    assert_eq!(server.accepted(), 2);
    assert_request_completes(&h);
    close(&h);
}

#[test]
fn a_reconnect_reads_the_connect_response_again() {
    let server = FakeServer::start().unwrap();
    let h = handle(&server);
    let passes = passes_until(&h, Duration::ZERO, &|p| connected_pass(p).is_some());
    let _ = assert_handshake_order(&passes);
    let id = h.client_id();
    server.drop_connections();
    let passes = passes_until(&h, Duration::ZERO, &|p| {
        p.iter().any(|x| x.after != State::Connected.code())
            && p.last().is_some_and(|x| x.after == State::Connected.code())
    });
    let lost = passes
        .iter()
        .position(|p| p.after != State::Connected.code())
        .unwrap();
    let _ = assert_handshake_order(&passes[lost..]);
    assert_eq!(h.client_id().session_id, id.session_id);
    assert_eq!(server.accepted(), 2);
    assert_request_completes(&h);
    close(&h);
}
