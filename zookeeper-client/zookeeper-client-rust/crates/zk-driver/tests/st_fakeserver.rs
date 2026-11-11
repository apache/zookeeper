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
use zk_fakeserver::FakeServer;
use zk_proto::rc::{ZCLOSING, ZNOTHING};
use zk_proto::{Compat, Config, DriverMode, LogSite, State};
use zk_testkit::tokens::TestTokens;

fn handle(server: &FakeServer) -> Polled<TestTokens> {
    handle_hosts(server.connect_string().into_bytes())
}

/// Initializes a [`Polled`] handle configured for the specified ensemble connection string.
fn handle_hosts(host_string: Vec<u8>) -> Polled<TestTokens> {
    let args = InitArgs {
        cfg: Config::legacy(10_000),
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
