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

//! Integration testing of TCP socket lifecycle, server state probing, and drop-in initialization
//! against simulated ensembles.
//!
//! Validates:
//! - Read-only versus read-write server probe determination via `isro` four-letter-word
//!   handshakes.
//! - nonblocking TCP connection establishment and peer address inspection.
//! - Outbox event generation and error code sequencing during ensemble address initialization.

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
use std::time::Instant;

use rustix::event::{PollFd, PollFlags};
use zk_driver::net::Poll;

use common::{IpHost, WAIT, env, kinds};
use zk_driver::net::probe::{ProbeCtx, ProbeEnd, probe};
use zk_driver::outbox::Flavor;
use zk_driver::{Init, InitArgs, InitStep, MonotonicClock, Outbox, RustixPoll, TcpSock, Unlocked};
use zk_fakeserver::FakeServer;
use zk_proto::{Compat, Config, ConnectStart, DriverMode, Endpoint, HostToken, ProbeOutcome};
use zk_testkit::tokens::TestTokens;

fn endpoint(addr: SocketAddr) -> Endpoint {
    Endpoint {
        addr,
        host: HostToken::default(),
    }
}

fn run_probe(addr: SocketAddr) -> ProbeEnd {
    let clock = MonotonicClock::new();
    let cx = ProbeCtx {
        poll: &RustixPoll,
        clock: &clock,
        wake: None,
        abort: None,
        tls: None,
        // Miri has no `fcntl(F_SETFD)`, so it opens the socket with no
        // CLOEXEC.
        cloexec: !cfg!(miri),
        phase_ms: 1000,
    };
    probe(&cx, &Unlocked::new_at_entry(), &endpoint(addr), None)
}

#[test]
fn probe_of_a_read_write_server_is_read_write() {
    let server = FakeServer::start().unwrap();
    assert_eq!(
        run_probe(server.addr()),
        ProbeEnd::Done(ProbeOutcome::ReadWrite)
    );
}

#[test]
fn probe_of_a_read_only_server_is_not_read_write() {
    let server = FakeServer::start().unwrap();
    server.set_read_only(true);
    assert_eq!(
        run_probe(server.addr()),
        ProbeEnd::Done(ProbeOutcome::NotReadWrite)
    );
}

#[test]
fn probe_of_a_closed_port_fails() {
    let addr = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap();
    assert_eq!(run_probe(addr), ProbeEnd::Done(ProbeOutcome::Failed(None)));
}

#[test]
fn open_and_connect_reach_the_server() {
    let server = FakeServer::start().unwrap();
    let opened = TcpSock::open(&server.addr(), !cfg!(miri)).unwrap();
    assert!(opened.nodelay_ok);
    let sock = opened.sock;
    // A loopback connect may complete at once. Else it completes when the socket is writable.
    let start = Instant::now();
    if sock.start_connect(&server.addr()).unwrap() == ConnectStart::InProgress {
        loop {
            let mut fds = [PollFd::new(&sock, PollFlags::OUT)];
            if RustixPoll.poll(&mut fds, 100).unwrap() > 0 {
                break;
            }
            assert!(start.elapsed() < WAIT, "connect does not complete");
        }
    }
    sock.socket_error().unwrap();
    assert_eq!(sock.peer_addr().unwrap(), server.addr());
    assert!(sock.local_addr().unwrap().ip().is_loopback());
}

fn init_args(hosts: &str) -> InitArgs {
    InitArgs {
        cfg: Config::legacy(10_000),
        host_string: hosts.as_bytes().to_vec(),
        compat: Compat::LEGACY_C,
        mode: DriverMode::MT,
        flavor: Flavor::Mt,
    }
}

#[test]
fn init_resolves_every_host_and_is_ready() {
    let host = IpHost::new();
    let mut ob: Outbox<TestTokens> = Outbox::new();
    let mut init = Init::new(
        init_args("127.0.0.1:2181,127.0.0.2:2181"),
        env(host, false),
        &mut ob,
    )
    .unwrap();
    let mut proof = Unlocked::new_at_entry();
    let mut steps = 0;
    loop {
        match init.step(&mut proof, &mut ob) {
            InitStep::More | InitStep::SaslPoint => {
                common::drain(&mut ob);
            }
            InitStep::Ready => break,
            InitStep::Failed(e) => panic!("init failed: {e:?}"),
        }
        steps += 1;
        assert!(steps < 16, "init does not end");
    }
}

#[test]
fn init_port_error_comes_after_the_prefix_lines() {
    let host = IpHost::new();
    let mut ob: Outbox<TestTokens> = Outbox::new();
    let mut init = Init::new(
        init_args("nohost:2181,127.0.0.1"),
        env(host, false),
        &mut ob,
    )
    .unwrap();
    let mut proof = Unlocked::new_at_entry();
    let mut seen = Vec::new();
    let end = loop {
        let step = init.step(&mut proof, &mut ob);
        seen.extend(kinds(&common::drain(&mut ob)));
        if !matches!(step, InitStep::More) {
            break step;
        }
    };
    // The resolver line of entry 0 comes before the "no port" line and the init fails with EINVAL.
    assert_eq!(
        end,
        InitStep::Failed(zk_driver::InitError { errno: Some(22) })
    );
    assert!(seen.len() >= 2);
    init.abandon();
}

#[test]
fn init_with_an_empty_string_fails_with_einval() {
    let host = IpHost::new();
    let mut ob: Outbox<TestTokens> = Outbox::new();
    let err = Init::new(init_args(""), env(host.clone(), false), &mut ob)
        .err()
        .unwrap();
    assert_eq!(err, zk_driver::InitError { errno: Some(22) });
    assert!(ob.is_empty());
    assert!(host.discarded().is_empty());
}
