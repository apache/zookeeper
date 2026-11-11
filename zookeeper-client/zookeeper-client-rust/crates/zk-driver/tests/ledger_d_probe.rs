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

//! Verification of socket descriptor cleanup across all read-write probe execution branches.
//!
//! Asserts that file descriptors are deterministically closed across all probe terminations:
//! connection refusal, quiet timeouts, EOF disconnects, and positive or negative replies.
//! Verifies absence of descriptor leakage by monitoring active process file descriptor counts.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener};
use std::thread;
use std::time::Duration;

use zk_driver::clock::MonotonicClock;
use zk_driver::net::poll::RustixPoll;
use zk_driver::net::probe::{ProbeCtx, ProbeEnd, probe};
use zk_driver::sync::Unlocked;
use zk_proto::{Endpoint, HostToken, ProbeOutcome};

/// Iteration count executed per probe branch to surface descriptor leakage.
const ROUNDS: usize = 20;

/// Simulated server interaction script for probe execution evaluation.
#[derive(Clone, Copy)]
enum Server {
    /// Ingests `isro` four-letter-word request, responds with payload, and terminates.
    Reply(&'static [u8]),
    /// Ingests data without responding, forcing probe phase timeout expiration.
    Silent,
}

/// Enumerates open file descriptors for the current process via `/dev/fd`.
fn open_fds() -> usize {
    std::fs::read_dir("/dev/fd").unwrap().count()
}

fn ep(addr: SocketAddr) -> Endpoint {
    Endpoint {
        addr,
        host: HostToken::default(),
    }
}

fn run(addr: SocketAddr, phase_ms: u64) -> ProbeEnd {
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
        phase_ms,
    };
    probe(&cx, &Unlocked::new_at_entry(), &ep(addr), None)
}

/// Executes a probe transaction against an ephemeral mock server thread running the designated
/// script.
fn probe_server(server: Server, phase_ms: u64) -> ProbeEnd {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let t = thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        s.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        let mut buf = [0u8; 4];
        match server {
            Server::Reply(reply) => {
                s.read_exact(&mut buf).unwrap();
                s.write_all(reply).unwrap();
            }
            Server::Silent => {
                let mut rest = Vec::new();
                let _ = s.read_to_end(&mut rest);
            }
        }
    });
    let end = run(addr, phase_ms);
    t.join().unwrap();
    end
}

/// Allocates an ephemeral port guaranteed to reject connection attempts.
fn closed_port() -> SocketAddr {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    l.local_addr().unwrap()
}

/// Asserts that probe socket descriptors are reclaimed across connection failure, timeout, EOF,
/// and valid responses.
#[test]
fn probe_closes_its_socket_on_every_path() {
    let before = open_fds();
    for _ in 0..ROUNDS {
        let addr = closed_port();
        assert_eq!(run(addr, 1000), ProbeEnd::Done(ProbeOutcome::Failed(None)));
    }
    assert_eq!(open_fds(), before, "a failed connect leaks the socket");
    for _ in 0..ROUNDS {
        assert_eq!(
            probe_server(Server::Silent, 50),
            ProbeEnd::Done(ProbeOutcome::Failed(None))
        );
    }
    assert_eq!(open_fds(), before, "a timeout leaks the socket");
    for _ in 0..ROUNDS {
        assert_eq!(
            probe_server(Server::Reply(b""), 1000),
            ProbeEnd::Done(ProbeOutcome::NotReadWrite)
        );
        assert_eq!(
            probe_server(Server::Reply(b"rw"), 1000),
            ProbeEnd::Done(ProbeOutcome::ReadWrite)
        );
        assert_eq!(
            probe_server(Server::Reply(b"ro"), 1000),
            ProbeEnd::Done(ProbeOutcome::NotReadWrite)
        );
    }
    assert_eq!(open_fds(), before, "a reply or an EOF leaks the socket");
}
