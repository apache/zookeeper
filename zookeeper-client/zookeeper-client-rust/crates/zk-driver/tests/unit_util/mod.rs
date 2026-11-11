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

//! In-memory mocks, deterministic test fixtures, and network doubles supporting unit tests.
//!
//! Provides test doubles operating purely through public driver APIs:
//! - [`TestHost`]: In-memory host resolution and environment hook tracking address mappings and
//!   lifecycle notifications.
//! - [`test_connection`]: Pre-configured protocol connection fixture targeting a single local test
//!   endpoint.
//! - [`socket_pair`]: Synchronous loopback socket pair establishing local TCP transports.
//! - [`RealPoll`]: Direct [`Poll`] implementation backed by POSIX `poll(2)`.
//! - [`NullTls`] and [`FakeConnector`]: Deterministic, scripted TLS session mock operating with
//!   transparent framing.

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
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use rustix::event::{PollFd, Timespec};
use zk_jute::JString;
use zk_proto::legacy::connect::parse_init;
use zk_proto::{
    ClientId, Compat, Config, Connection, DriverMode, HostResolution, HostSpec, LogLevel, Millis,
    ResolveError,
};
use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
use zk_testkit::rng::SeededRng;
use zk_testkit::tokens::{TestTokens, WatcherId};

use zk_driver::host::{Discarded, HostLeaves};
use zk_driver::net::poll::Poll;
use zk_driver::net::sock::TcpSock;
use zk_driver::net::tls::{
    Endpoint, TlsConfigStep, TlsConn, TlsConnector, TlsFailure, TlsPurpose, TlsSetupError,
};
use zk_driver::sync::{Mutex, Unlocked};

/// Simulated `getaddrinfo` error code for unmapped hostname lookups.
pub const TEST_EAI_NONAME: i32 = -2;

/// Controllable mock [`HostLeaves`] implementation tracking diagnostic and state transition hooks.
pub struct TestHost {
    level: Mutex<Option<LogLevel>>,
    addrs: Mutex<HashMap<Vec<u8>, Vec<SocketAddr>>>,
    discarded: Mutex<Vec<&'static str>>,
    ids: Mutex<Vec<ClientId>>,
    permute_disabled: AtomicBool,
    resolves: AtomicUsize,
    reseeds: AtomicUsize,
}

impl TestHost {
    /// Instantiates a mock host with default warn logging and empty hostname resolution mappings.
    pub fn new() -> Self {
        Self {
            level: Mutex::new(Some(LogLevel::Warn)),
            addrs: Mutex::new(HashMap::new()),
            discarded: Mutex::new(Vec::new()),
            ids: Mutex::new(Vec::new()),
            permute_disabled: AtomicBool::new(false),
            resolves: AtomicUsize::new(0),
            reseeds: AtomicUsize::new(0),
        }
    }

    /// Updates the configured maximum active logging verbosity filter.
    pub fn set_log_level(&self, level: Option<LogLevel>) {
        *self.level.lock_owner() = level;
    }

    /// Configures the resolved socket address list mapped to the specified host byte string.
    pub fn set_addrs(&self, host: &[u8], addrs: Vec<SocketAddr>) {
        self.addrs.lock_owner().insert(host.to_vec(), addrs);
    }

    /// Configures the connection permutation override flag.
    pub fn set_permute_disabled(&self, v: bool) {
        self.permute_disabled.store(v, Ordering::Relaxed);
    }

    /// Returns recorded token discard event categories in historical order.
    pub fn discarded(&self) -> Vec<&'static str> {
        self.discarded.lock_owner().clone()
    }

    /// Returns recorded client session credentials in historical order.
    pub fn stored_ids(&self) -> Vec<ClientId> {
        self.ids.lock_owner().clone()
    }

    /// Returns the cumulative invocation count of hostname resolution attempts.
    pub fn resolves(&self) -> usize {
        self.resolves.load(Ordering::Relaxed)
    }

    /// Returns the cumulative invocation count of entropy reseed operations.
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
        self.ids.lock_owner().push(*id);
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

/// Parses the connection string and produces a legacy client [`Config`].
pub fn test_config(hosts: &[u8]) -> Config {
    let cs = parse_init(hosts).expect("valid connect string");
    let mut cfg = Config::legacy(10_000);
    cfg.hosts = cs.hosts.into_iter().map(HostSpec::Legacy).collect();
    cfg.host_text = JString::Bytes(cs.host_text);
    cfg.chroot = cs.chroot.map(JString::Bytes);
    cfg
}

/// Constructs a drop-in protocol [`Connection`] targeting loopback port 2181 at timestamp zero.
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

/// Constructs an interconnected pair of blocking loopback TCP sockets.
pub fn socket_pair() -> (TcpSock, TcpSock) {
    let l = TcpListener::bind("127.0.0.1:0").expect("bind");
    let a = TcpStream::connect(l.local_addr().expect("addr")).expect("connect");
    let (b, _) = l.accept().expect("accept");
    (
        TcpSock::from_owned(OwnedFd::from(a)),
        TcpSock::from_owned(OwnedFd::from(b)),
    )
}

/// Direct readiness polling abstraction backed by `rustix::event::poll`.
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

/// Scripted, transparent TLS session double simulating handshake progression and record framing
/// without encryption.
pub struct NullTls {
    handshaking: bool,
    out: Vec<u8>,
    plain: Vec<u8>,
    latched: Option<TlsFailure>,
}

impl NullTls {
    /// Initializes a mock TLS session in the handshake state with simulated ClientHello bytes
    /// queued.
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
        out.append(&mut self.out);
        n
    }

    fn send_close_notify(&mut self) {
        self.out.extend_from_slice(b"CN");
    }
}

/// Scriptable [`TlsConnector`] yielding mock TLS sessions or sequenced configuration errors.
pub struct FakeConnector {
    script: Mutex<VecDeque<TlsSetupError>>,
    made: AtomicUsize,
}

impl FakeConnector {
    /// Constructs a mock connector preloaded with a sequence of initialization failures to
    /// simulate.
    pub fn new(failures: Vec<TlsSetupError>) -> Self {
        Self {
            script: Mutex::new(failures.into()),
            made: AtomicUsize::new(0),
        }
    }

    /// Returns the total number of TLS connection initialization attempts dispatched.
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
