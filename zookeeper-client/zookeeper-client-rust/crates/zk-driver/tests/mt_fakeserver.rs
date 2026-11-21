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

//! multithreaded drop-in driver integration tests against `zk-fakeserver`.
//!
//! Simulates foreign C runtime hosting architecture with isolated I/O and completion worker
//! threads, verifying loop progression across `io_step`, `wait_dispatch`, outbox FIFO ordering,
//! and thread barrier rendezvous.

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

use std::sync::Arc;
use std::sync::mpsc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use common::{CountingPoll, IpHost, WAIT, env, exists};
use zk_driver::outbox::{Flavor, Item};
use zk_driver::{Env, Init, InitArgs, InitStep, Outbox, Poll, Role, Shared, Step, Unlocked};
use zk_fakeserver::FakeServer;
use zk_proto::{Compat, Config, DriverMode, State};
use zk_testkit::tokens::TestTokens;

/// Initializes a multithreaded drop-in driver instance targeting the mock server.
fn handle(server: &FakeServer, host: Arc<IpHost>) -> Arc<Shared<TestTokens>> {
    handle_in(server, env(host, false))
}

/// Initializes a multithreaded drop-in driver instance using the provided runtime environment.
fn handle_in(server: &FakeServer, env: Env<TestTokens>) -> Arc<Shared<TestTokens>> {
    let args = InitArgs {
        cfg: Config::legacy(10_000),
        host_string: server.connect_string().into_bytes(),
        compat: Compat::LEGACY_C,
        mode: DriverMode::MT,
        flavor: Flavor::Mt,
    };
    let mut ob = Outbox::new();
    let mut init = Init::new(args, env, &mut ob).unwrap();
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
    Shared::from_init(init, &mut ob).unwrap()
}

/// Drives execution iterations across continuation barriers, routing dequeued items to `sink`.
fn drive(
    shared: &Shared<TestTokens>,
    first: impl FnOnce(&mut Outbox<TestTokens>, &mut i32) -> Step,
    sink: &mpsc::Sender<Item<TestTokens>>,
) -> Step {
    let mut ob = shared.outbox();
    let mut rc = 0;
    let mut step = first(&mut ob, &mut rc);
    loop {
        while let Some(item) = ob.pop() {
            let _ = sink.send(item);
        }
        if step != Step::More {
            return step;
        }
        step = shared.resume(&mut ob, &mut rc);
    }
}

/// RAII supervisor managing the lifecycle of background I/O and completion worker threads.
struct Threads {
    shared: Arc<Shared<TestTokens>>,
    handles: Vec<JoinHandle<()>>,
}

impl Threads {
    /// Initiates graceful session teardown from foreign thread context and joins background worker
    /// threads.
    fn close(mut self) -> Step {
        let (tx, _keep) = mpsc::channel();
        let s = Arc::clone(&self.shared);
        let end = drive(&s, |ob, rc| s.close(Role::Foreign, ob, rc), &tx);
        // Join all worker threads before propagating any thread panic so no background worker is
        // left detached.
        let joined: Vec<_> = std::mem::take(&mut self.handles)
            .into_iter()
            .map(JoinHandle::join)
            .collect();
        for r in joined {
            r.unwrap();
        }
        end
    }
}

impl Drop for Threads {
    /// Cleanup fallback ensuring worker threads are joined without panicking during abnormal
    /// stack unwinding.
    fn drop(&mut self) {
        if self.handles.is_empty() {
            return;
        }
        let (tx, _keep) = mpsc::channel();
        let s = Arc::clone(&self.shared);
        let _ = drive(&s, |ob, rc| s.close(Role::Foreign, ob, rc), &tx);
        let end = Instant::now() + WAIT;
        for t in std::mem::take(&mut self.handles) {
            while !t.is_finished() && Instant::now() < end {
                std::thread::sleep(Duration::from_millis(5));
            }
            if t.is_finished() {
                let _ = t.join();
            }
        }
    }
}

/// Spawns background I/O and completion worker threads, returning an item receiver channel and
/// lifecycle supervisor.
fn start_threads(shared: &Arc<Shared<TestTokens>>) -> (mpsc::Receiver<Item<TestTokens>>, Threads) {
    let (tx, rx) = mpsc::channel();
    shared.thread_enter(Role::Io);
    shared.thread_enter(Role::Dispatch);
    let io = {
        let s = Arc::clone(shared);
        let tx = tx.clone();
        std::thread::spawn(move || {
            while drive(&s, |ob, rc| s.io_step(ob, rc), &tx) != Step::Exit {}
            drive(&s, |ob, rc| s.thread_exit(Role::Io, ob, rc), &tx);
        })
    };
    let dispatch = {
        let s = Arc::clone(shared);
        std::thread::spawn(move || {
            loop {
                let mut ob = s.outbox();
                if s.wait_dispatch(&mut ob) == Step::Exit {
                    break;
                }
                while let Some(item) = ob.pop() {
                    let _ = tx.send(item);
                }
            }
            let mut rc = 0;
            let mut ob = s.outbox();
            let _ = s.thread_exit(Role::Dispatch, &mut ob, &mut rc);
        })
    };
    let threads = Threads {
        shared: Arc::clone(shared),
        handles: vec![io, dispatch],
    };
    (rx, threads)
}

#[test]
fn session_connects_and_a_request_completes() {
    let server = FakeServer::start().unwrap();
    let shared = handle(&server, IpHost::new());
    let (rx, threads) = start_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
    shared.submit(exists(1)).ok().unwrap();
    let got = std::iter::from_fn(|| rx.recv_timeout(WAIT).ok())
        .find(|i| matches!(i, Item::Result { .. }))
        .unwrap();
    assert!(matches!(got, Item::Result { result, .. } if result.rc == 0));
    let end = threads.close();
    assert!(matches!(end, Step::Done(_) | Step::Release));
}

#[test]
fn exported_interest_reports_no_socket() {
    let server = FakeServer::start().unwrap();
    let shared = handle(&server, IpHost::new());
    let out = shared.interest_exported();
    assert_eq!(out.fd, -1);
    assert_eq!(shared.process_exported(), zk_proto::rc::ZINVALIDSTATE);
}

/// Verifies that submitting a request to an idle handle transmits the frame during the immediate
/// waker wakeup pass, completing the round-trip in exactly two ready poll events (wake and reply).
#[test]
fn a_request_goes_out_in_the_pass_of_its_wake() {
    let server = FakeServer::start().unwrap();
    let poll = Arc::new(CountingPoll::default());
    let mut services = env(IpHost::new(), false);
    services.poll = Arc::clone(&poll) as Arc<dyn Poll>;
    let shared = handle_in(&server, services);
    let (rx, threads) = start_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
    assert!(poll.wait_idle(WAIT));
    let before = poll.ready();
    shared.submit(exists(1)).ok().unwrap();
    let got = std::iter::from_fn(|| rx.recv_timeout(WAIT).ok())
        .find(|i| matches!(i, Item::Result { .. }))
        .unwrap();
    assert!(matches!(got, Item::Result { result, .. } if result.rc == 0));
    assert_eq!(poll.ready() - before, 2);
    let end = threads.close();
    assert!(matches!(end, Step::Done(_) | Step::Release));
}

/// Receives `n` operation completion results from `rx` in arrival order.
fn results(rx: &mpsc::Receiver<Item<TestTokens>>, n: usize) -> Vec<Item<TestTokens>> {
    let end = Instant::now() + Duration::from_secs(30);
    let mut got = Vec::new();
    while got.len() < n {
        let left = end.saturating_duration_since(Instant::now());
        match rx.recv_timeout(left) {
            Ok(i @ Item::Result { .. }) => got.push(i),
            Ok(_) => {}
            Err(e) => panic!("{} of {n} results: {e}", got.len()),
        }
    }
    got
}

/// Verifies that reply frames straddling the 64 KiB read buffer boundary and a 1 MiB reply frame
/// are reassembled intact and delivered in order.
#[test]
fn frames_at_the_read_buffer_edges_arrive_whole() {
    let server = FakeServer::start().unwrap();
    let shared = handle(&server, IpHost::new());
    let (rx, threads) = start_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
    for (i, len) in common::EDGE_FRAME_LENS.into_iter().enumerate() {
        let data = common::pattern(len - common::GET_DATA_FRAME_BASE, i as u8);
        let path = format!("/edge{i}");
        server.cluster().create(&path, &data, 0).unwrap();
        let id = i as u64 + 1;
        shared.submit(common::get_data_at(id, &path)).ok().unwrap();
        let got = results(&rx, 1);
        common::check_result(&got[0], id, Some(&data));
    }
    let end = threads.close();
    assert!(matches!(end, Step::Done(_) | Step::Release));
}

/// Verifies that a burst of small replies coalesced into batched socket reads are decoded and
/// delivered in strict request order.
#[test]
fn many_small_replies_together_arrive_in_order() {
    let server = FakeServer::start().unwrap();
    let shared = handle(&server, IpHost::new());
    let (rx, threads) = start_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
    let n = 500;
    // Hold server processing so all replies flush in a single coalesced burst upon release.
    server.hold();
    for id in 1..=n {
        shared.submit(exists(id)).ok().unwrap();
    }
    std::thread::sleep(Duration::from_millis(200));
    server.release();
    let got = results(&rx, n as usize);
    for (k, item) in got.iter().enumerate() {
        common::check_result(item, k as u64 + 1, None);
    }
    let end = threads.close();
    assert!(matches!(end, Step::Done(_) | Step::Release));
}

/// Verifies that interleaved large and small replies flushed in a single server burst preserve
/// payload integrity and completion ordering.
#[test]
fn large_and_small_frames_interleaved_arrive_in_order() {
    let server = FakeServer::start().unwrap();
    let shared = handle(&server, IpHost::new());
    let (rx, threads) = start_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
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
    server.hold();
    for k in 0..want.len() {
        let id = k as u64 + 1;
        let i = k / 3;
        let req = match k % 3 {
            0 => common::get_data_at(id, &format!("/mix{i}")),
            2 => common::get_data_at(id, &format!("/small{i}")),
            _ => exists(id),
        };
        shared.submit(req).ok().unwrap();
    }
    std::thread::sleep(Duration::from_millis(200));
    server.release();
    let got = results(&rx, want.len());
    for (k, (item, w)) in got.iter().zip(&want).enumerate() {
        common::check_result(item, k as u64 + 1, w.as_deref());
    }
    let end = threads.close();
    assert!(matches!(end, Step::Done(_) | Step::Release));
}
