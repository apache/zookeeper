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

//! Verification of shared network primitives, cross-thread waker coalescing, and poll event
//! mapping.
//!
//! Asserts invariant guarantees of transport layer abstractions:
//! - multithreaded self-pipe signal coalescing and race-free reset semantics
//!   ([`crate::net::waker`]).
//! - POSIX revents bitmask normalization to driver poll events ([`crate::net::poll`]).
//! - Peer hangup and TCP reset handling across socket readiness evaluations.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::os::fd::AsFd;
use std::sync::Arc;
use std::time::Duration;

use rustix::event::{PollFd, PollFlags, Timespec};
use rustix::io::Errno;
use rustix::net::{AddressFamily, SocketFlags, SocketType};
use zk_driver::net::{PollEvents, Waker, map_revents};

/// Drains all bytes buffered within a nonblocking pipe until returning `WouldBlock`.
fn drain<F: AsFd>(fd: F) -> usize {
    let mut total = 0;
    let mut buf = [0u8; 64];
    loop {
        match rustix::io::read(&fd, &mut buf) {
            Ok(0) => return total,
            Ok(n) => total += n,
            Err(Errno::WOULDBLOCK) => return total,
            Err(Errno::INTR) => {}
            Err(e) => panic!("read: {e}"),
        }
    }
}

/// Polls a single file descriptor for target flags within the specified millisecond timeout.
fn revents<F: AsFd>(fd: F, flags: PollFlags, ms: i64) -> PollFlags {
    let mut fds = [PollFd::new(&fd, flags)];
    let ts = Timespec {
        tv_sec: ms / 1000,
        tv_nsec: (ms % 1000) * 1_000_000,
    };
    rustix::event::poll(&mut fds, Some(&ts)).unwrap();
    fds[0].revents()
}

/// Asserts that atomic waker latching ensures exactly one byte is written to the self-pipe across
/// concurrent wake calls.
#[test]
fn many_wakes_write_one_byte() {
    let (waker, rx) = Waker::new(false).unwrap();
    let waker = Arc::new(waker);
    let threads: Vec<_> = (0..8)
        .map(|_| {
            let w = Arc::clone(&waker);
            std::thread::spawn(move || {
                for _ in 0..10_000 {
                    w.wake();
                }
            })
        })
        .collect();
    for t in threads {
        t.join().unwrap();
    }
    assert_eq!(drain(&rx), 1, "one byte for 80000 wakes");
    // The flag is still set, so a new wake writes nothing.
    waker.wake();
    assert_eq!(drain(&rx), 0);
    // A reset allows the next wake again.
    rx.reset(&waker);
    waker.wake();
    waker.wake();
    assert_eq!(drain(&rx), 1);
}

/// Asserts that resetting the waker clears buffered notifications without losing concurrent wake
/// signals.
#[test]
fn a_wake_after_reset_is_never_lost() {
    // Miri has no `fcntl(F_SETFD)`, so it makes the pipe with no CLOEXEC.
    // Each thread spawn is slow under Miri, so it runs fewer rounds.
    let (waker, rx) = Waker::new(!cfg!(miri)).unwrap();
    let waker = Arc::new(waker);
    let rounds = if cfg!(miri) { 50 } else { 2_000 };
    for _ in 0..rounds {
        let w = Arc::clone(&waker);
        let t = std::thread::spawn(move || w.wake());
        rx.reset(&waker);
        t.join().unwrap();
        // The wake came before or after the reset. In both cases the
        // pipe has a byte or the flag is clear. A clear flag means the
        // next wake writes a byte.
        waker.wake();
        assert!(
            revents(&rx, PollFlags::IN, 1_000).contains(PollFlags::IN),
            "a lost wake"
        );
        rx.reset(&waker);
        assert_eq!(drain(&rx), 0, "the reset empties the pipe");
    }
}

/// Validates mapping of POSIX poll revents flags into driver readiness representations.
#[test]
fn revents_map_to_legacy_events() {
    let both = PollEvents {
        read: true,
        write: true,
    };
    let read = PollEvents {
        read: true,
        write: false,
    };
    let write = PollEvents {
        read: false,
        write: true,
    };
    assert_eq!(map_revents(PollFlags::empty()), PollEvents::NONE);
    assert_eq!(map_revents(PollFlags::IN), read);
    assert_eq!(map_revents(PollFlags::OUT), write);
    assert_eq!(map_revents(PollFlags::HUP), write);
    assert_eq!(map_revents(PollFlags::IN | PollFlags::HUP), both);
    assert_eq!(map_revents(PollFlags::ERR), both);
    assert_eq!(map_revents(PollFlags::NVAL), both);
}

/// Asserts that socket peer closure triggers immediate readability to allow graceful EOF
/// detection.
#[test]
fn socketpair_hangup_is_readable() {
    let (a, b) = rustix::net::socketpair(
        AddressFamily::UNIX,
        SocketType::STREAM,
        SocketFlags::empty(),
        None,
    )
    .unwrap();
    drop(b);
    let got = revents(&a, PollFlags::IN, 1_000);
    assert!(
        got.intersects(PollFlags::IN | PollFlags::HUP),
        "revents {got:?}"
    );
    let ev = map_revents(got);
    assert!(!ev.is_empty(), "{got:?} maps to no event");
    let mut buf = [0u8; 8];
    assert_eq!(rustix::io::read(&a, &mut buf), Ok(0), "end of stream");
    // With only OUT requested, the hang-up still gives an event.
    let got = revents(&a, PollFlags::OUT, 1_000);
    assert!(!map_revents(got).is_empty(), "revents {got:?}");
}

/// Asserts that abrupt peer termination via TCP RST manifests as actionable readiness events.
#[test]
#[cfg_attr(miri, ignore = "Miri has no SO_LINGER, which makes the reset peer")]
fn tcp_reset_is_an_event() {
    let l = TcpListener::bind("127.0.0.1:0").unwrap();
    let mut client = TcpStream::connect(l.local_addr().unwrap()).unwrap();
    let (server, _) = l.accept().unwrap();
    rustix::net::sockopt::set_socket_linger(&server, Some(Duration::ZERO)).unwrap();
    drop(server);
    let got = revents(&client, PollFlags::IN, 1_000);
    let ev = map_revents(got);
    assert!(ev.read, "revents {got:?} give no READ");
    let mut buf = [0u8; 8];
    match rustix::io::read(&client, &mut buf) {
        Ok(0) | Err(Errno::CONNRESET) => {}
        other => panic!("recv after a reset: {other:?}"),
    }
    // A later send also fails, so a write-only interest ends too.
    let _ = client.write(b"x");
    std::thread::sleep(Duration::from_millis(20));
    let got = revents(&client, PollFlags::OUT, 1_000);
    assert!(!map_revents(got).is_empty(), "revents {got:?}");
}
