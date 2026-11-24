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

//! Shared unit-testing harnesses, mock fixtures, and test utilities.
//!
//! Provides modular mock implementations across driver dependencies, allowing unit tests
//! to exercise protocol state machines, event loops, and transport abstractions in isolation:
//! - [`TestHost`]: In-memory host platform adapter with configurable DNS address tables,
//!   deterministic PRNGs, and token trackers.
//! - [`test_connection`]: Pre-configured protocol `Connection` instance bound to localhost.
//! - [`socket_pair`]: Directly interconnected loopback TCP socket pair bypassing external network
//!   bindings.
//! - [`RealPoll`]: Direct descriptor polling engine backed by `rustix::event::poll`.
//! - [`NullTls`] and [`FakeConnector`]: Scriptable sans-I/O TLS session mock with transparent
//!   plaintext transport, enabling TLS I/O driver testing without cryptographic overhead.

#![allow(
    dead_code,
    unreachable_pub,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::{HashMap, VecDeque};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::os::fd::OwnedFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use rustix::event::{PollFd, Timespec};
use zk_jute::JString;
use zk_proto::legacy::connect::parse_init;
use zk_proto::{
    ClientId, Compat, Config, Connection, DriverMode, HostResolution, HostSpec, InputDefect,
    LogLevel, Millis, Op, PathIn, Request, ResolveError, Route,
};
use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
use zk_testkit::rng::SeededRng;
use zk_testkit::tokens::CompletionId;
use zk_testkit::tokens::{TestTokens, WatcherId};

use crate::clock::TestClock;
use crate::host::{Discarded, HostLeaves};
use crate::net::poll::Poll;
use crate::net::sock::TcpSock;
use crate::net::tls::{
    Endpoint, TlsConfigStep, TlsConn, TlsConnector, TlsFailure, TlsPurpose, TlsSetupError,
};
use crate::sync::{Mutex, Unlocked};

/// Synthetic `getaddrinfo` return code representing an unresolvable hostname (`EAI_NONAME`).
pub const TEST_EAI_NONAME: i32 = -2;

/// Configurable host platform environment for unit testing.
///
/// - Logging threshold defaults to `LogLevel::Warn`, with `None` disabling all emission.
/// - Deterministic pseudo-random values (`rng_random` returns 7, `rng_drand48` returns 0.5).
/// - DNS address resolution queries an in-memory routing table, returning [`TEST_EAI_NONAME`] for
///   unrecognized names.
pub struct TestHost {
    level: Mutex<Option<LogLevel>>,
    addrs: Mutex<HashMap<Vec<u8>, Vec<SocketAddr>>>,
    discarded: Mutex<Vec<&'static str>>,
    ids: Mutex<Vec<ClientId>>,
    permute_disabled: AtomicBool,
    resolves: AtomicUsize,
    reseeds: AtomicUsize,
    slow_dns: Mutex<Option<(Arc<TestClock>, u64)>>,
}

impl TestHost {
    /// Instantiates a test host with an empty DNS resolution table and default mock parameters.
    pub fn new() -> Self {
        Self {
            level: Mutex::new(Some(LogLevel::Warn)),
            addrs: Mutex::new(HashMap::new()),
            discarded: Mutex::new(Vec::new()),
            ids: Mutex::new(Vec::new()),
            permute_disabled: AtomicBool::new(false),
            resolves: AtomicUsize::new(0),
            reseeds: AtomicUsize::new(0),
            slow_dns: Mutex::new(None),
        }
    }

    /// Configures each `resolve` call to advance `clock` by `ms` to simulate DNS lookup latency.
    pub fn set_slow_dns(&self, clock: Arc<TestClock>, ms: u64) {
        *self.slow_dns.lock_owner() = Some((clock, ms));
    }

    /// Adjusts the maximum enabled log level threshold.
    pub fn set_log_level(&self, level: Option<LogLevel>) {
        *self.level.lock_owner() = level;
    }

    /// Registers socket addresses associated with a target hostname.
    pub fn set_addrs(&self, host: &[u8], addrs: Vec<SocketAddr>) {
        self.addrs.lock_owner().insert(host.to_vec(), addrs);
    }

    /// Configures whether server list permutation is disabled.
    pub fn set_permute_disabled(&self, v: bool) {
        self.permute_disabled.store(v, Ordering::Relaxed);
    }

    /// Retrieves the sequential list of discarded token categories.
    pub fn discarded(&self) -> Vec<&'static str> {
        self.discarded.lock_owner().clone()
    }

    /// Retrieves recorded client session identities in chronological order.
    pub fn stored_ids(&self) -> Vec<ClientId> {
        self.ids.lock_owner().clone()
    }

    /// Returns the cumulative count of `resolve` invocations.
    pub fn resolves(&self) -> usize {
        self.resolves.load(Ordering::Relaxed)
    }

    /// Returns the cumulative count of PRNG reseed requests.
    pub fn reseeds(&self) -> usize {
        self.reseeds.load(Ordering::Relaxed)
    }
}

impl Default for TestHost {
    fn default() -> Self {
        Self::new()
    }
}

impl HostLeaves<TestTokens> for TestHost {
    fn discard_token(&self, token: Discarded<TestTokens>) {
        let kind = match token {
            Discarded::Completion(_) => "completion",
            Discarded::Watcher(_) => "watcher",
            Discarded::Auth(_) => "auth",
        };
        self.discarded.lock_owner().push(kind);
    }

    fn snapshot_default_watcher(&self) -> Option<WatcherId> {
        None
    }

    fn store_client_id(&self, id: &ClientId) {
        self.ids.lock_owner().push(id.clone());
    }

    fn log_enabled(&self, level: LogLevel) -> bool {
        self.level
            .lock_owner()
            .is_some_and(|m| level.code() <= m.code())
    }

    fn conn_permute_disabled(&self) -> bool {
        self.permute_disabled.load(Ordering::Relaxed)
    }

    fn rng_random(&self) -> i64 {
        7
    }

    fn rng_drand48(&self) -> f64 {
        0.5
    }

    fn rng_reseed(&self, _proof: &Unlocked) {
        self.reseeds.fetch_add(1, Ordering::Relaxed);
    }

    fn resolve(&self, _proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
        self.resolves.fetch_add(1, Ordering::Relaxed);
        if let Some((clock, ms)) = &*self.slow_dns.lock_owner() {
            clock.advance(*ms);
        }
        let result = match self.addrs.lock_owner().get(spec.host_bytes()) {
            Some(a) => Ok(a.clone()),
            None => Err(ResolveError {
                gai_rc: TEST_EAI_NONAME,
                errno: 0,
            }),
        };
        HostResolution {
            host,
            result,
            skipped_families: Vec::new(),
        }
    }
}

/// Constructs a legacy configuration initialized from a connection string.
pub fn test_config(hosts: &[u8]) -> Config {
    let cs = parse_init(hosts).expect("valid connect string");
    let mut cfg = Config::legacy(10_000);
    cfg.hosts = cs.hosts.into_iter().map(HostSpec::Legacy).collect();
    cfg.host_text = JString::Bytes(cs.host_text);
    cfg.chroot = cs.chroot.map(JString::Bytes);
    cfg
}

/// Creates a drop-in legacy `Connection` bound to `127.0.0.1:2181` at logical timestamp zero.
pub fn test_connection(mode: DriverMode) -> Connection<TestTokens> {
    Connection::new(
        test_config(b"127.0.0.1:2181"),
        Compat::LEGACY_C,
        mode,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        Millis(0),
    )
}

/// Creates a native driver `Connection` bound to `127.0.0.1:2181` at logical timestamp zero.
pub fn native_connection() -> Box<Connection<TestTokens>> {
    Box::new(Connection::new(
        test_config(b"127.0.0.1:2181"),
        Compat::NATIVE,
        DriverMode::NATIVE,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        Millis(0),
    ))
}

/// Constructs an `Exists` protocol request without an active watcher, targeting the specified path.
pub fn exists_request(path: PathIn, id: u64) -> Request<TestTokens> {
    Request {
        op: Op::Exists { path, watch: None },
        token: CompletionId::new(id),
        route: Route::Queued,
        defect: InputDefect::default(),
    }
}

/// Constructs a pair of connected loopback TCP sockets in blocking mode.
pub fn socket_pair() -> (TcpSock, TcpSock) {
    let l = TcpListener::bind("127.0.0.1:0").expect("bind");
    let a = TcpStream::connect(l.local_addr().expect("addr")).expect("connect");
    let (b, _) = l.accept().expect("accept");
    (
        TcpSock::from_owned(OwnedFd::from(a)),
        TcpSock::from_owned(OwnedFd::from(b)),
    )
}

/// Direct descriptor polling engine backed by `rustix::event::poll`.
pub struct RealPoll;

impl Poll for RealPoll {
    fn poll(&self, fds: &mut [PollFd<'_>], timeout_ms: i32) -> rustix::io::Result<usize> {
        let ts = (timeout_ms >= 0).then(|| Timespec {
            tv_sec: i64::from(timeout_ms / 1000),
            tv_nsec: i64::from(timeout_ms % 1000) * 1_000_000,
        });
        rustix::event::poll(fds, ts.as_ref())
    }
}

/// Scripted mock TLS session executing transparent plaintext passthrough with deterministic state
/// transitions.
///
/// - Initial `take_ciphertext` call emits synthetic `b"HELLO"` client greeting.
/// - During handshake: incoming bytes transition session to established. `b'F'` latches a
///   handshake failure; `b'A'` latches a handshake failure and queues an alert record.
/// - Post-handshake: ciphertext mirrors plaintext verbatim. An incoming `b'!'` triggers a data
///   stream failure.
/// - `send_close_notify` appends synthetic close notification `b"CN"`.
pub struct NullTls {
    handshaking: bool,
    out: Vec<u8>,
    plain: Vec<u8>,
    latched: Option<TlsFailure>,
}

impl NullTls {
    /// Instantiates a mock TLS session initialized in the handshake state with queued ClientHello
    /// bytes.
    pub fn new() -> Self {
        Self {
            handshaking: true,
            out: b"HELLO".to_vec(),
            plain: Vec::new(),
            latched: None,
        }
    }
}

impl Default for NullTls {
    fn default() -> Self {
        Self::new()
    }
}

impl TlsConn for NullTls {
    fn is_handshaking(&self) -> bool {
        self.handshaking
    }

    fn has_ciphertext(&self) -> bool {
        !self.out.is_empty()
    }

    fn feed(&mut self, ciphertext: &[u8]) -> Result<(), TlsFailure> {
        if let Some(f) = self.latched {
            return Err(f);
        }
        for &b in ciphertext {
            if self.handshaking {
                match b {
                    b'F' => self.latched = Some(TlsFailure::Handshake),
                    b'A' => {
                        self.out.extend_from_slice(b"ALERT");
                        self.latched = Some(TlsFailure::Handshake);
                    }
                    _ => self.handshaking = false,
                }
            } else if b == b'!' {
                self.latched = Some(TlsFailure::Data);
            } else {
                self.plain.push(b);
            }
            if let Some(f) = self.latched {
                return Err(f);
            }
        }
        Ok(())
    }

    fn feed_eof(&mut self) -> Result<(), TlsFailure> {
        let f = if self.handshaking {
            TlsFailure::Handshake
        } else {
            TlsFailure::Closed
        };
        let f = *self.latched.get_or_insert(f);
        Err(f)
    }

    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure> {
        if !self.plain.is_empty() {
            let n = self.plain.len();
            out.append(&mut self.plain);
            return Ok(n);
        }
        self.latched.map_or(Ok(0), Err)
    }

    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure> {
        if let Some(f) = self.latched {
            return Err(f);
        }
        self.out.extend_from_slice(plaintext);
        Ok(())
    }

    fn take_ciphertext(&mut self, out: &mut Vec<u8>) -> usize {
        let n = self.out.len();
        // Bytes are transferred matching rustls session buffer movement.
        crate::net::tls::move_ciphertext(out, &mut self.out);
        n
    }

    fn send_close_notify(&mut self) {
        self.out.extend_from_slice(b"CN");
    }
}

/// Mock [`TlsConnector`] producing [`NullTls`] sessions or scripted setup failures in designated
/// sequence.
pub struct FakeConnector {
    script: Mutex<VecDeque<TlsSetupError>>,
    made: AtomicUsize,
}

impl FakeConnector {
    /// Constructs a connector configured to yield the specified sequence of initialization
    /// failures.
    pub fn new(failures: Vec<TlsSetupError>) -> Self {
        Self {
            script: Mutex::new(failures.into()),
            made: AtomicUsize::new(0),
        }
    }

    /// Returns the cumulative number of connection establishment requests processed.
    pub fn calls(&self) -> usize {
        self.made.load(Ordering::Relaxed)
    }
}

impl TlsConnector for FakeConnector {
    fn new_conn(
        &self,
        _proof: &Unlocked,
        _purpose: TlsPurpose,
        _peer: &Endpoint,
    ) -> Result<Box<dyn TlsConn>, TlsSetupError> {
        self.made.fetch_add(1, Ordering::Relaxed);
        match self.script.lock_owner().pop_front() {
            Some(e) => Err(e),
            None => Ok(Box::new(NullTls::new())),
        }
    }

    fn path_for(&self, step: TlsConfigStep) -> Option<&[u8]> {
        match step {
            TlsConfigStep::CaFile => Some(b"ca.pem"),
            TlsConfigStep::ClientCertChain => Some(b"cert.pem"),
            TlsConfigStep::ClientKey => Some(b"key.pem"),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_tls_latches_after_plaintext() {
        let mut t = NullTls::new();
        let mut ct = Vec::new();
        assert_eq!(t.take_ciphertext(&mut ct), 5);
        t.feed(b"s").unwrap();
        assert!(!t.is_handshaking());
        assert_eq!(t.feed(b"ab!c"), Err(TlsFailure::Data));
        let mut pt = Vec::new();
        assert_eq!(t.read_plaintext(&mut pt), Ok(2));
        assert_eq!(t.read_plaintext(&mut pt), Err(TlsFailure::Data));
    }

    #[test]
    fn host_resolves_from_the_table() {
        let host = TestHost::new();
        let cfg = test_config(b"a:1,b:2");
        host.set_addrs(b"a", vec!["127.0.0.1:1".parse().unwrap()]);
        let proof = Unlocked::new_at_entry();
        assert!(host.resolve(&proof, 0, &cfg.hosts[0]).result.is_ok());
        assert!(host.resolve(&proof, 1, &cfg.hosts[1]).result.is_err());
        assert_eq!(host.resolves(), 2);
    }
}
