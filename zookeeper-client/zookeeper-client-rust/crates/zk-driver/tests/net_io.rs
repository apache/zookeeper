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

//! Verification of low level networking primitives, event polling, socket wrappers, and server
//! state probing.
//!
//! Asserts invariant guarantees of network layer components:
//! - Bi-directional conversion between legacy bitmasks and typed poll readiness flags
//!   ([`crate::net::poll`]).
//! - POSIX poll revents classification across read, write, hangup, and error conditions.
//! - nonblocking socket lifecycle management and non-destructive peek probes
//!   ([`crate::net::sock`]).
//! - Cross-thread self-pipe signaling and event coalescing ([`crate::net::waker`]).
//! - Read-only and read-write cluster role probing state machine execution
//!   ([`crate::net::probe`]).

#![allow(missing_docs)]
#![allow(clippy::disallowed_macros)]

mod unit_util;

mod poll {
    // tv_nsec is an i64 on 64-bit targets and an i32 on 32-bit targets.
    #![allow(clippy::useless_conversion)]

    use rustix::event::{PollFd, PollFlags};

    use zk_driver::net::poll::*;

    #[test]
    fn legacy_bits_round_trip() {
        for bits in 0..4 {
            assert_eq!(PollEvents::from_bits(bits).bits(), bits);
        }
        assert!(PollEvents::NONE.is_empty());
        assert!(PollEvents::from_bits(ZOOKEEPER_READ).read);
        assert!(PollEvents::from_bits(ZOOKEEPER_WRITE).write);
    }

    const BOTH: PollEvents = PollEvents {
        read: true,
        write: true,
    };
    const READ: PollEvents = PollEvents {
        read: true,
        write: false,
    };
    const WRITE: PollEvents = PollEvents {
        read: false,
        write: true,
    };

    #[test]
    fn revents_map_to_legacy_events() {
        assert_eq!(map_revents(PollFlags::empty()), PollEvents::NONE);
        assert_eq!(map_revents(PollFlags::IN), READ);
        assert_eq!(map_revents(PollFlags::OUT), WRITE);
        // HUP is WRITE, as do_io maps it (mt_adaptor.c:403-404).
        assert_eq!(map_revents(PollFlags::HUP), WRITE);
        assert_eq!(map_revents(PollFlags::IN | PollFlags::HUP), BOTH);
        // ERR and NVAL give both, so the next call reports the error.
        assert_eq!(map_revents(PollFlags::ERR), BOTH);
        assert_eq!(map_revents(PollFlags::NVAL), BOTH);
        assert_eq!(map_revents(PollFlags::ERR | PollFlags::HUP), BOTH);
        // Other bits give nothing.
        assert_eq!(map_revents(PollFlags::PRI), PollEvents::NONE);
    }

    #[test]
    fn request_flags_ask_for_the_interest() {
        assert_eq!(request_flags(PollEvents::NONE), PollFlags::empty());
        assert_eq!(request_flags(READ), PollFlags::IN);
        assert_eq!(request_flags(WRITE), PollFlags::OUT);
        assert_eq!(request_flags(BOTH), PollFlags::IN | PollFlags::OUT);
    }

    #[test]
    fn timeouts_clamp_to_the_poll_range() {
        assert_eq!(clamp_timeout_ms(-1), 0);
        assert_eq!(clamp_timeout_ms(i64::MIN), 0);
        assert_eq!(clamp_timeout_ms(0), 0);
        assert_eq!(clamp_timeout_ms(1500), 1500);
        assert_eq!(clamp_timeout_ms(i64::from(i32::MAX)), i32::MAX);
        assert_eq!(clamp_timeout_ms(i64::from(i32::MAX) + 1), i32::MAX);
        assert_eq!(clamp_timeout_ms(i64::MAX), i32::MAX);
    }

    #[test]
    fn rustix_poll_reports_readiness_and_times_out() {
        let (a, b) = crate::unit_util::socket_pair();
        let mut fds = [PollFd::new(&b, PollFlags::IN)];
        assert_eq!(RustixPoll.poll(&mut fds, 0), Ok(0));
        assert_eq!(a.send(b"x"), Ok(1));
        let mut fds = [PollFd::new(&b, PollFlags::IN)];
        assert_eq!(RustixPoll.poll(&mut fds, 5000), Ok(1));
        let [fd] = &fds;
        assert_eq!(map_revents(fd.revents()), READ);
    }
}

mod sock {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::net::TcpListener;

    use rustix::event::{PollFd, PollFlags};
    use rustix::fs::OFlags;
    use rustix::io::{Errno, FdFlags};
    use zk_proto::ConnectStart;

    use zk_driver::net::poll::Poll;
    use zk_driver::net::sock::*;

    use crate::unit_util::{RealPoll, socket_pair};

    /// Awaits socket writability readiness up to a bounded 5-second deadline.
    fn wait_writable(sock: &TcpSock) {
        let mut fds = [PollFd::new(sock, PollFlags::OUT)];
        assert_eq!(RealPoll.poll(&mut fds, 5000).unwrap(), 1);
    }

    /// Asserts that socket initialization configures nonblocking flags and suppresses SIGPIPEs.
    #[test]
    fn open_sets_the_flags_and_connect_reaches_the_listener() {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        // Miri has no `fcntl(F_SETFD)` and reports each descriptor as
        // CLOEXEC. So under Miri the test opens only the plain socket.
        let modes: &[bool] = if cfg!(miri) { &[false] } else { &[false, true] };
        for &cloexec in modes {
            let opened = TcpSock::open(&addr, cloexec).unwrap();
            assert!(opened.nodelay_ok);
            let sock = opened.sock;
            let fl = rustix::fs::fcntl_getfl(&sock).unwrap();
            assert!(fl.contains(OFlags::NONBLOCK));
            let fd = rustix::io::fcntl_getfd(&sock).unwrap();
            if !cfg!(miri) {
                assert_eq!(fd.contains(FdFlags::CLOEXEC), cloexec);
            }
            assert!(rustix::net::sockopt::tcp_nodelay(&sock).unwrap());
            // Miri can set SO_NOSIGPIPE, but it cannot read it back.
            #[cfg(all(target_vendor = "apple", not(miri)))]
            assert!(rustix::net::sockopt::socket_nosigpipe(&sock).unwrap());

            let start = sock.start_connect(&addr).unwrap();
            if start == ConnectStart::InProgress {
                wait_writable(&sock);
            }
            assert!(sock.socket_error().is_ok());
            assert_eq!(sock.peer_addr().unwrap(), addr);
            let (_peer, from) = l.accept().unwrap();
            assert_eq!(from, sock.local_addr().unwrap());
        }
    }

    #[test]
    fn connect_to_a_closed_port_fails() {
        let addr = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap();
        // Miri has no `fcntl(F_SETFD)`, so it opens the socket with no
        // CLOEXEC.
        let sock = TcpSock::open(&addr, !cfg!(miri)).unwrap().sock;
        match sock.start_connect(&addr) {
            Ok(ConnectStart::InProgress) => {
                wait_writable(&sock);
                assert_eq!(sock.socket_error(), Err(Errno::CONNREFUSED));
            }
            Ok(ConnectStart::Immediate) => panic!("connect to a closed port succeeded"),
            Err(e) => assert_eq!(e, Errno::CONNREFUSED),
        }
        assert_eq!(
            errno_value(Errno::CONNREFUSED),
            Errno::CONNREFUSED.raw_os_error()
        );
    }

    #[test]
    fn wrappers_send_and_receive() {
        let (a, b) = socket_pair();
        assert_eq!(a.send(b"isro").unwrap(), 4);
        let mut buf = [0u8; 8];
        let n = b.recv(&mut buf).unwrap();
        assert_eq!(buf.get(..n), Some(&b"isro"[..]));
        assert!(a.socket_error().is_ok());
        assert_eq!(a.local_addr().unwrap(), b.peer_addr().unwrap());
        assert!(a.raw() >= 0);
    }
}

mod waker {
    #![allow(clippy::unwrap_used)]

    use rustix::event::{PollFd, PollFlags};

    use zk_driver::net::waker::*;

    fn readable(rx: &WakeRx) -> bool {
        let mut fds = [PollFd::new(rx, PollFlags::IN)];
        let ts = rustix::event::Timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        rustix::event::poll(&mut fds, Some(&ts)).unwrap() == 1
    }

    #[test]
    fn wakes_coalesce_and_reset_rearms() {
        // Miri has no `fcntl(F_SETFD)`, so it makes the pipe with no
        // CLOEXEC.
        let (waker, rx) = Waker::new(!cfg!(miri)).unwrap();
        assert!(!readable(&rx));
        waker.wake();
        waker.wake();
        assert!(readable(&rx));
        rx.reset(&waker);
        assert!(!readable(&rx));
        waker.wake();
        assert!(readable(&rx));
    }

    #[test]
    fn child_close_drops_the_write_end() {
        let (waker, rx) = Waker::new(false).unwrap();
        assert!(waker.close_in_child());
        waker.wake();
        // The write end is closed, so the read end reports EOF as IN.
        assert!(readable(&rx));
    }
}

mod probe {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::sync::Arc;
    use std::thread;
    use std::thread::JoinHandle;
    use std::time::{Duration, Instant};
    use zk_driver::sync::atomic::{AtomicBool, Ordering};

    use zk_proto::compat::TlsVerify;
    use zk_proto::{Endpoint, HostToken, ProbeOutcome, ProbeTlsFailure, TlsConfigStep};

    use zk_driver::clock::MonotonicClock;
    use zk_driver::net::poll::RustixPoll;
    use zk_driver::net::probe::*;
    use zk_driver::net::tls::{TlsResourceAt, TlsSetupError, jstring};
    use zk_driver::net::waker::Waker;
    use zk_driver::sync::Unlocked;

    use crate::unit_util::FakeConnector;

    /// Scripted mock server behavior driving simulated connection interactions.
    #[derive(Clone, Copy)]
    enum Script {
        /// Consumes `isro` probe request, transmits designated reply payload, and severs
        /// transport.
        Reply(&'static [u8]),
        /// Consumes `isro` probe request, introduces artificial latency, transmits reply, and
        /// closes.
        Late(Duration, &'static [u8]),
        /// Consumes `isro` probe request and immediately terminates transport without responding.
        Close,
        /// Ingests incoming bytes continuously until peer severs connection.
        Silent,
        /// Ingests TLS ClientHello, transmits simulated handshake flight, and delegates to nested
        /// script.
        Tls(&'static [u8], &'static [u8]),
    }

    /// Accepts a single inbound connection and executes the designated script scenario on a
    /// background thread.
    fn serve(script: Script) -> (SocketAddr, JoinHandle<Vec<u8>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let handle = thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            s.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
            let mut seen = Vec::new();
            match script {
                Script::Reply(reply) => {
                    read_n(&mut s, &mut seen, 4);
                    s.write_all(reply).unwrap();
                }
                Script::Late(delay, reply) => {
                    read_n(&mut s, &mut seen, 4);
                    thread::sleep(delay);
                    let _ = s.write_all(reply);
                }
                Script::Close => read_n(&mut s, &mut seen, 4),
                Script::Silent => {
                    let _ = s.read_to_end(&mut seen);
                }
                Script::Tls(hs, reply) => {
                    read_n(&mut s, &mut seen, 5);
                    s.write_all(hs).unwrap();
                    if reply.is_empty() {
                        let _ = s.read_to_end(&mut seen);
                    } else {
                        read_n(&mut s, &mut seen, 4);
                        s.write_all(reply).unwrap();
                    }
                }
            }
            seen
        });
        (addr, handle)
    }

    fn read_n(s: &mut TcpStream, seen: &mut Vec<u8>, n: usize) {
        let mut buf = vec![0u8; n];
        if s.read_exact(&mut buf).is_ok() {
            seen.extend_from_slice(&buf);
        }
    }

    fn ep(addr: SocketAddr) -> Endpoint {
        Endpoint {
            addr,
            host: HostToken::default(),
        }
    }

    fn cx<'a>(clock: &'a MonotonicClock, phase_ms: u64) -> ProbeCtx<'a> {
        ProbeCtx {
            poll: &RustixPoll,
            clock,
            wake: None,
            abort: None,
            tls: None,
            // Miri has no `fcntl(F_SETFD)`, so it opens the socket with
            // no CLOEXEC.
            cloexec: !cfg!(miri),
            phase_ms,
        }
    }

    fn run_plain(script: Script, phase_ms: u64) -> (ProbeEnd, Vec<u8>) {
        let (addr, server) = serve(script);
        let clock = MonotonicClock::new();
        let end = probe(
            &cx(&clock, phase_ms),
            &Unlocked::new_at_entry(),
            &ep(addr),
            None,
        );
        (end, server.join().unwrap())
    }

    fn run_tls(connector: &FakeConnector, script: Script, phase_ms: u64) -> (ProbeEnd, Vec<u8>) {
        let (addr, server) = serve(script);
        let clock = MonotonicClock::new();
        let mut c = cx(&clock, phase_ms);
        c.tls = Some(connector);
        let verify = Some(TlsVerify::LegacyChainOnly);
        let end = probe(&c, &Unlocked::new_at_entry(), &ep(addr), verify);
        (end, server.join().unwrap())
    }

    fn done(o: ProbeOutcome) -> ProbeEnd {
        ProbeEnd::Done(o)
    }

    /// Validates probe response interpretation matching legacy C client string comparison
    /// semantics (`rw`).
    #[test]
    fn rw_reply_follows_the_strcmp_rule() {
        assert!(is_rw_reply(b"rw"));
        assert!(is_rw_reply(b"rw\0junk"));
        assert!(is_rw_reply(&[b'r', b'w', 0, 0, 0, 0, 0, 0, 0, 0]));
        assert!(!is_rw_reply(b"rwx"));
        assert!(!is_rw_reply(b"ro"));
        assert!(!is_rw_reply(b"RW"));
        assert!(!is_rw_reply(b"r"));
        assert!(!is_rw_reply(b""));
        assert!(!is_rw_reply(&[0u8; 10]));
    }

    /// Validates cleartext `isro` probe dispatch and response decoding sequence.
    #[test]
    fn plaintext_probe_reads_one_reply() {
        let (end, seen) = run_plain(Script::Reply(b"rw"), 2000);
        assert_eq!(end, done(ProbeOutcome::ReadWrite));
        assert_eq!(seen, b"isro");
        let (end, _) = run_plain(Script::Reply(b"ro"), 2000);
        assert_eq!(end, done(ProbeOutcome::NotReadWrite));
        // An EOF is a successful recv of 0 bytes, as in C.
        let (end, _) = run_plain(Script::Close, 2000);
        assert_eq!(end, done(ProbeOutcome::NotReadWrite));
    }

    /// Asserts that nonresponsive peers trigger probe phase timeout transitions rather than
    /// stalling indefinitely.
    #[test]
    fn silent_server_ends_at_the_phase_budget() {
        let start = Instant::now();
        let (end, seen) = run_plain(Script::Silent, 100);
        assert_eq!(end, done(ProbeOutcome::Failed(None)));
        assert_eq!(seen, b"isro");
        assert!(start.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn closed_port_fails() {
        let addr = {
            let l = TcpListener::bind("127.0.0.1:0").unwrap();
            l.local_addr().unwrap()
        };
        let clock = MonotonicClock::new();
        let end = probe(
            &cx(&clock, 1000),
            &Unlocked::new_at_entry(),
            &ep(addr),
            None,
        );
        assert_eq!(end, done(ProbeOutcome::Failed(None)));
    }

    #[test]
    fn a_set_close_flag_aborts_at_entry() {
        let clock = MonotonicClock::new();
        let flag = AtomicBool::new(true);
        let mut c = cx(&clock, 1000);
        c.abort = Some(&flag);
        let addr: SocketAddr = "127.0.0.1:9".parse().unwrap();
        assert_eq!(
            probe(&c, &Unlocked::new_at_entry(), &ep(addr), None),
            ProbeEnd::Aborted
        );
    }

    #[test]
    fn the_waker_ends_the_wait_when_the_close_flag_is_set() {
        let (addr, server) = serve(Script::Silent);
        let (waker, rx) = Waker::new(!cfg!(miri)).unwrap();
        let waker = Arc::new(waker);
        let flag = Arc::new(AtomicBool::new(false));
        let closer = {
            let (waker, flag) = (Arc::clone(&waker), Arc::clone(&flag));
            thread::spawn(move || {
                thread::sleep(Duration::from_millis(100));
                flag.store(true, Ordering::Release);
                waker.wake();
            })
        };
        let clock = MonotonicClock::new();
        let mut c = cx(&clock, 30_000);
        c.wake = Some((&waker, &rx));
        c.abort = Some(&flag);
        let start = Instant::now();
        let end = probe(&c, &Unlocked::new_at_entry(), &ep(addr), None);
        assert_eq!(end, ProbeEnd::Aborted);
        assert!(start.elapsed() < Duration::from_secs(10));
        closer.join().unwrap();
        assert_eq!(server.join().unwrap(), b"isro");
    }

    #[test]
    fn a_wake_without_a_close_does_not_end_the_probe() {
        let (addr, server) = serve(Script::Late(Duration::from_millis(300), b"rw"));
        let (waker, rx) = Waker::new(!cfg!(miri)).unwrap();
        let waker = Arc::new(waker);
        let flag = Arc::new(AtomicBool::new(false));
        let other = {
            let waker = Arc::clone(&waker);
            thread::spawn(move || {
                thread::sleep(Duration::from_millis(50));
                waker.wake();
            })
        };
        let clock = MonotonicClock::new();
        let mut c = cx(&clock, 5000);
        c.wake = Some((&waker, &rx));
        c.abort = Some(&flag);
        let end = probe(&c, &Unlocked::new_at_entry(), &ep(addr), None);
        assert_eq!(end, done(ProbeOutcome::ReadWrite));
        other.join().unwrap();
        server.join().unwrap();
    }

    #[test]
    fn tls_probe_sends_isro_as_a_frame() {
        let connector = FakeConnector::new(Vec::new());
        let (end, seen) = run_tls(&connector, Script::Tls(b"S", b"rw"), 2000);
        assert_eq!(end, done(ProbeOutcome::ReadWrite));
        assert_eq!(seen, b"HELLOisro");
        assert_eq!(connector.calls(), 1);
        let (end, _) = run_tls(&connector, Script::Tls(b"S", b"ro"), 2000);
        assert_eq!(end, done(ProbeOutcome::NotReadWrite));
    }

    #[test]
    fn tls_setup_failures_name_the_step() {
        let step = TlsConfigStep::CaFile;
        let connector = FakeConnector::new(vec![TlsSetupError::Config { step }]);
        let (end, _) = run_tls(&connector, Script::Silent, 1000);
        assert_eq!(
            end,
            done(ProbeOutcome::Failed(Some(ProbeTlsFailure::Config {
                step,
                file: jstring(Some(b"ca.pem")),
            })))
        );
        let at = TlsResourceAt::ConnectionNew;
        let connector = FakeConnector::new(vec![TlsSetupError::Resource { at }]);
        let (end, _) = run_tls(&connector, Script::Silent, 1000);
        assert_eq!(
            end,
            done(ProbeOutcome::Failed(Some(ProbeTlsFailure::Resource { at })))
        );
    }

    #[test]
    fn tls_without_a_connector_fails_at_the_context() {
        let (addr, server) = serve(Script::Silent);
        let clock = MonotonicClock::new();
        let verify = Some(TlsVerify::WebPki);
        let end = probe(
            &cx(&clock, 1000),
            &Unlocked::new_at_entry(),
            &ep(addr),
            verify,
        );
        assert_eq!(
            end,
            done(ProbeOutcome::Failed(Some(ProbeTlsFailure::Resource {
                at: TlsResourceAt::ContextNew,
            })))
        );
        assert!(server.join().unwrap().is_empty());
    }

    #[test]
    fn tls_handshake_failures_are_handshake_failures() {
        let connector = FakeConnector::new(Vec::new());
        let handshake = done(ProbeOutcome::Failed(Some(ProbeTlsFailure::Handshake)));
        let (end, seen) = run_tls(&connector, Script::Tls(b"F", b""), 2000);
        assert_eq!(end, handshake);
        assert_eq!(seen, b"HELLO");
        // A failure that queues an alert sends it once.
        let (end, seen) = run_tls(&connector, Script::Tls(b"A", b""), 2000);
        assert_eq!(end, handshake);
        assert_eq!(seen, b"HELLOALERT");
        // A silent server ends at the phase budget.
        let (end, _) = run_tls(&connector, Script::Silent, 100);
        assert_eq!(end, handshake);
    }
}

mod tls {
    use zk_jute::JString;

    use zk_driver::net::tls::*;

    #[test]
    fn jstring_maps_null() {
        assert_eq!(jstring(None), JString::Null);
        assert_eq!(jstring(Some(b"ca.pem")).as_bytes(), Some(&b"ca.pem"[..]));
    }

    #[test]
    fn failures_map_to_read_failures() {
        assert_eq!(ReadFailure::from(TlsFailure::Closed), ReadFailure::Closed);
        assert_eq!(ReadFailure::from(TlsFailure::Data), ReadFailure::Data);
        assert_eq!(ReadFailure::from(TlsFailure::Handshake), ReadFailure::Data);
    }
}
