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

//! Shared test harness components, mock host adapters, and fixture helpers for zk-driver
//! integration tests.
//!
//! Provides reusable test infrastructure including:
//! - [`IpHost`]: Deterministic, synchronous in-memory host resolver mapping IP literals without OS
//!   DNS resolution.
//! - [`legacy_connection`] and [`native_connection`]: Pre-configured protocol connection builders.
//! - [`env`]: Driver execution runtime environment initialized with monotonic time providers and
//!   bounded timeouts.
//! - [`drain`]: Sequential consumer collecting queued items from driver outboxes.
//! - [`fixture`]: Resolves on-disk TLS certificate and private key fixtures.
//! - [`Collect`], [`PanicOnSession`], [`TestSpawner`], and [`SlowHost`]: Mock response routing
//!   sinks, thread spawning adapters, and latency-injecting DNS resolvers.
//! - [`CountingPoll`]: Poll wrapper counting ready descriptor wakeups to verify I/O loop poll
//!   pass counts.
//! - [`tls`]: Test TLS transport acceptors and client connectors configured against test
//!   certificate authorities.
//!
//! Test-specific helpers with localized usage are maintained within their respective test modules.

#![allow(
    dead_code,
    unreachable_pub,
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use rustix::event::PollFd;
use zk_driver::native::{Spawn, ThreadBody};
use zk_driver::outbox::Item;
use zk_driver::{
    Discarded, DriverConfig, Env, HostLeaves, Outbox, Poll, RouteSink, RustixPoll, Unlocked,
};
use zk_jute::JString;
use zk_proto::legacy::connect::parse_init;
use zk_proto::{
    ClientId, Compat, Config, Connection, DriverMode, HostResolution, HostSpec, InputDefect,
    LogLevel, LogSite, Millis, Op, PathIn, Request, ResolveError, Route,
};
use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
use zk_testkit::rng::SeededRng;
use zk_testkit::tokens::{CompletionId, TestTokens, WatcherId};

/// Default timeout budget allocated for test assertions and synchronization barriers.
pub const WAIT: Duration = Duration::from_secs(5);

/// Simulated `getaddrinfo` return code representing host lookup failure for non-IP literals.
pub const EAI_NONAME: i32 = -2;

/// In-memory host resolution adapter operating without network DNS dependencies.
#[derive(Debug, Default)]
pub struct IpHost {
    pub discarded: Mutex<Vec<&'static str>>,
    pub ids: Mutex<Vec<ClientId>>,
    pub level: Option<LogLevel>,
    pub permute_disabled: bool,
}

impl IpHost {
    /// Constructs an `IpHost` instance configured with debug logging and disabled server list
    /// shuffling.
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            level: Some(LogLevel::Debug),
            permute_disabled: true,
            ..Self::default()
        })
    }

    pub fn discarded(&self) -> Vec<&'static str> {
        self.discarded.lock().unwrap().clone()
    }

    pub fn stored_ids(&self) -> Vec<ClientId> {
        self.ids.lock().unwrap().clone()
    }
}

impl HostLeaves<TestTokens> for IpHost {
    fn discard_token(&self, token: Discarded<TestTokens>) {
        let kind = match token {
            Discarded::Completion(_) => "completion",
            Discarded::Watcher(_) => "watcher",
            Discarded::Auth(_) => "auth",
        };
        self.discarded.lock().unwrap().push(kind);
    }

    fn snapshot_default_watcher(&self) -> Option<WatcherId> {
        None
    }

    fn store_client_id(&self, id: &ClientId) {
        self.ids.lock().unwrap().push(*id);
    }

    fn log_enabled(&self, level: LogLevel) -> bool {
        self.level.is_some_and(|m| level.code() <= m.code())
    }

    fn conn_permute_disabled(&self) -> bool {
        self.permute_disabled
    }

    fn rng_random(&self) -> i64 {
        7
    }

    fn rng_drand48(&self) -> f64 {
        0.5
    }

    fn rng_reseed(&self, _proof: &Unlocked) {}

    fn resolve(&self, _proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
        let port = match spec {
            HostSpec::Legacy(h) => u16::try_from(h.port).ok(),
            HostSpec::Native(h) => Some(h.port),
        };
        let ip = std::str::from_utf8(spec.host_bytes())
            .ok()
            .and_then(|s| s.parse::<IpAddr>().ok());
        let result = match (ip, port) {
            (Some(ip), Some(port)) => Ok(vec![SocketAddr::new(ip, port)]),
            _ => Err(ResolveError {
                gai_rc: EAI_NONAME,
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

/// Constructs a legacy client [`Config`] with the specified ensemble connection string and a
/// 10-second timeout.
pub fn legacy_config(hosts: &str) -> Config {
    let cs = parse_init(hosts.as_bytes()).expect("valid connect string");
    let mut cfg = Config::legacy(10_000);
    cfg.hosts = cs.hosts.into_iter().map(HostSpec::Legacy).collect();
    cfg.host_text = JString::Bytes(cs.host_text);
    cfg.chroot = cs.chroot.map(JString::Bytes);
    cfg
}

/// Constructs a protocol [`Connection`] configured for legacy C compatibility.
pub fn legacy_connection(hosts: &str, mode: DriverMode) -> Box<Connection<TestTokens>> {
    connection(legacy_config(hosts), Compat::LEGACY_C, mode)
}

/// Constructs a protocol [`Connection`] configured for native Rust execution.
pub fn native_connection(hosts: &str) -> Box<Connection<TestTokens>> {
    let mut cfg = legacy_config(hosts);
    // Establish a non-zero graceful close timeout to prevent immediate termination with
    // ZOPERATIONTIMEOUT during outbox draining.
    cfg.close_timeout_ms = 3_000;
    connection(cfg, Compat::NATIVE, DriverMode::NATIVE)
}

fn connection(cfg: Config, compat: Compat, mode: DriverMode) -> Box<Connection<TestTokens>> {
    Box::new(Connection::new(
        cfg,
        compat,
        mode,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        Millis(0),
    ))
}

/// Initializes driver execution environment with accelerated close budgets and specified descriptor
/// flags.
pub fn env(host: Arc<IpHost>, native: bool) -> Env<TestTokens> {
    let leaves: Arc<dyn HostLeaves<TestTokens>> = host;
    let mut env = Env::new(leaves);
    env.cfg = DriverConfig {
        close_flush_ms: 200,
        close_wait_ms: 200,
        cloexec: native,
        ..DriverConfig::default()
    };
    env
}

/// Drains and collects all queued events from an [`Outbox`] in FIFO order.
pub fn drain(ob: &mut Outbox<TestTokens>) -> Vec<Item<TestTokens>> {
    std::iter::from_fn(|| ob.pop()).collect()
}

/// Extracts categorical descriptor tags for each outbox item to facilitate structural sequence
/// assertions.
pub fn kinds(items: &[Item<TestTokens>]) -> Vec<&'static str> {
    items
        .iter()
        .map(|i| match i {
            Item::Log(_) => "log",
            Item::Result { .. } => "result",
            Item::AuthResult { .. } => "auth",
            Item::Watch { .. } => "watch",
            Item::Session { .. } => "session",
            Item::WatchTerminated { .. } => "terminated",
            Item::AuthLockpoint => "lockpoint",
            Item::Sasl(_) => "sasl",
            Item::Thread { .. } => "thread",
        })
        .collect()
}

/// Periodically evaluates `pred` at 5ms polling intervals until satisfied or until `timeout`
/// expires.
pub fn wait_until(timeout: Duration, mut pred: impl FnMut() -> bool) -> bool {
    let end = Instant::now() + timeout;
    while Instant::now() < end {
        if pred() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    pred()
}

/// Resolves the absolute path of a cached TLS test fixture file by name.
pub fn fixture(name: &str) -> PathBuf {
    zk_testkit::fixtures::tls_dir().join(name)
}

/// Test routing sink recording dispatched outbox items and counting completed operations.
#[derive(Default)]
pub struct Collect(pub Mutex<Vec<Item<TestTokens>>>);

impl RouteSink<TestTokens> for Collect {
    fn route(&self, item: Item<TestTokens>) -> bool {
        let counts = matches!(item, Item::Result { .. } | Item::AuthResult { .. });
        self.0.lock().unwrap().push(item);
        counts
    }
}

/// Fault-injecting routing sink that triggers a panic upon routing the initial session event to
/// simulate application panic unwinding on the I/O runner.
#[derive(Default)]
pub struct PanicOnSession {
    pub fired: AtomicBool,
}

impl RouteSink<TestTokens> for PanicOnSession {
    fn route(&self, item: Item<TestTokens>) -> bool {
        if matches!(item, Item::Session { .. }) && !self.fired.swap(true, Ordering::SeqCst) {
            panic!("test sink panics on the session event");
        }
        matches!(item, Item::Result { .. } | Item::AuthResult { .. })
    }
}

impl Collect {
    /// Returns the count of routed operation completion results.
    pub fn results(&self) -> usize {
        self.0
            .lock()
            .unwrap()
            .iter()
            .filter(|i| matches!(i, Item::Result { .. }))
            .count()
    }

    /// Returns the return code of the initial routed result, if present.
    pub fn result_rc(&self) -> Option<i32> {
        self.0.lock().unwrap().iter().find_map(|i| match i {
            Item::Result { result, .. } => Some(result.rc),
            _ => None,
        })
    }

    /// Asserts whether a log record matching the designated [`LogSite`] was received.
    pub fn has_log(&self, site: LogSite) -> bool {
        self.0
            .lock()
            .unwrap()
            .iter()
            .any(|i| matches!(i, Item::Log(r) if r.site == site))
    }
}

/// Thread spawning adapter delegating directly to `std::thread::Builder`.
pub struct TestSpawner;

impl Spawn for TestSpawner {
    fn spawn(&self, name: &'static str, body: ThreadBody) -> std::io::Result<()> {
        std::thread::Builder::new()
            .name(name.to_owned())
            .spawn(body)
            .map(drop)
    }
}

/// Constructs an unwatched `exists` protocol request for the specified path.
pub fn exists_at(id: u64, path: PathIn) -> Request<TestTokens> {
    Request {
        op: Op::Exists { path, watch: None },
        token: CompletionId::new(id),
        route: Route::Queued,
        defect: InputDefect::default(),
    }
}

/// Constructs an unwatched `getData` request for the legacy path `path`.
pub fn get_data_at(id: u64, path: &str) -> Request<TestTokens> {
    Request {
        op: Op::GetData {
            path: PathIn::Legacy(Some(path.as_bytes().to_vec())),
            watch: None,
        },
        token: CompletionId::new(id),
        route: Route::Queued,
        defect: InputDefect::default(),
    }
}

/// Reply frame sizes straddling the 64 KiB read buffer boundary (both with and without the 4-byte
/// frame length prefix), plus a 1 MiB oversized frame.
pub const EDGE_FRAME_LENS: [usize; 7] = [65_531, 65_532, 65_533, 65_535, 65_536, 65_537, 1 << 20];

/// Wire frame size (excluding the 4-byte length prefix) of an empty `getData` reply: 16-byte
/// `ReplyHeader` + 4-byte data length + 68-byte `Stat`.
pub const GET_DATA_FRAME_BASE: usize = 88;

/// Generates a deterministic `len`-byte payload parameterized by `seed` so distinct replies have
/// non-overlapping byte patterns.
pub fn pattern(len: usize, seed: u8) -> Vec<u8> {
    (0..len)
        .map(|i| {
            (i as u8)
                .wrapping_mul(31)
                .wrapping_add(seed.wrapping_mul(97))
        })
        .collect()
}

/// Asserts that `item` is a successful completion for request `id` carrying either the expected
/// `getData` payload `want` or an `exists` `Stat` reply when `want` is `None`.
pub fn check_result(item: &Item<TestTokens>, id: u64, want: Option<&[u8]>) {
    let Item::Result { token, result, .. } = item else {
        panic!("not a result: {item:?}");
    };
    assert_eq!(token.id(), id, "results come in request order");
    assert_eq!(result.rc, 0, "request {id}");
    match (want, &result.reply) {
        (Some(w), Some(zk_proto::Reply::Data { data, .. })) => {
            let got = data.as_bytes().unwrap();
            assert_eq!(got.len(), w.len(), "request {id}");
            assert!(got == w, "request {id}: wrong data bytes");
        }
        (None, Some(zk_proto::Reply::Stat(_))) => {}
        (_, other) => panic!("request {id}: reply {other:?}"),
    }
}

/// Constructs an unwatched `exists` request targeting the legacy root path (`/`).
pub fn exists(id: u64) -> Request<TestTokens> {
    exists_at(id, PathIn::Legacy(Some(b"/".to_vec())))
}

/// Constructs an unwatched `exists` request targeting the native root path (`/`).
pub fn exists_native(id: u64) -> Request<TestTokens> {
    exists_at(id, PathIn::Native("/".into()))
}

/// Fault injecting host adapter capable of introducing artificial resolution latency and tracking
/// lookup invocations.
pub struct SlowHost {
    pub inner: Arc<IpHost>,
    pub slow_ms: AtomicU64,
    pub resolves: AtomicUsize,
}

impl HostLeaves<TestTokens> for SlowHost {
    fn discard_token(&self, token: Discarded<TestTokens>) {
        self.inner.discard_token(token);
    }

    fn snapshot_default_watcher(&self) -> Option<WatcherId> {
        self.inner.snapshot_default_watcher()
    }

    fn store_client_id(&self, id: &ClientId) {
        self.inner.store_client_id(id);
    }

    fn log_enabled(&self, level: LogLevel) -> bool {
        self.inner.log_enabled(level)
    }

    fn conn_permute_disabled(&self) -> bool {
        self.inner.conn_permute_disabled()
    }

    fn rng_random(&self) -> i64 {
        self.inner.rng_random()
    }

    fn rng_drand48(&self) -> f64 {
        self.inner.rng_drand48()
    }

    fn rng_reseed(&self, proof: &Unlocked) {
        self.inner.rng_reseed(proof);
    }

    fn resolve(&self, proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
        self.resolves.fetch_add(1, Ordering::SeqCst);
        let ms = self.slow_ms.load(Ordering::SeqCst);
        if ms > 0 {
            std::thread::sleep(Duration::from_millis(ms));
        }
        self.inner.resolve(proof, host, spec)
    }
}

/// In-process TLS server infrastructure supporting mock ensemble integration tests.
#[cfg(feature = "tls")]
pub mod tls {
    use std::io::{self, Read, Write};
    use std::net::TcpStream;
    use std::sync::Arc;

    use rustls::pki_types::pem::PemObject;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};
    use rustls::server::WebPkiClientVerifier;
    use rustls::{RootCertStore, ServerConfig, ServerConnection, StreamOwned};
    use zk_driver::Unlocked;
    use zk_driver::net::tls::verify::webpki_verifier;
    use zk_driver::net::tls::{
        LegacyPath, LegacyTlsArgs, NativeConfig, RustlsConnector, TlsConnector, TlsPurpose,
        TlsSetupError,
    };
    use zk_fakeserver::{Acceptor, Transport};
    use zk_proto::compat::TlsVerify;

    use super::fixture;

    /// Resolves the active cryptographic provider configured for the current build profile.
    pub fn provider() -> Arc<rustls::crypto::CryptoProvider> {
        #[cfg(feature = "tls-ring")]
        let p = rustls::crypto::ring::default_provider();
        #[cfg(all(not(feature = "tls-ring"), feature = "tls-aws-lc-rs-fips"))]
        let p = rustls::crypto::default_fips_provider();
        #[cfg(all(
            not(feature = "tls-ring"),
            not(feature = "tls-aws-lc-rs-fips"),
            feature = "tls-aws-lc-rs"
        ))]
        let p = rustls::crypto::aws_lc_rs::default_provider();
        Arc::new(p)
    }

    /// Parses all DER-encoded certificates contained in the specified PEM fixture.
    pub fn certs(name: &str) -> Vec<CertificateDer<'static>> {
        CertificateDer::pem_file_iter(fixture(name))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap()
    }

    /// Extracts the DER-encoded private key from the specified PEM fixture.
    pub fn key(name: &str) -> PrivateKeyDer<'static> {
        PrivateKeyDer::from_pem_file(fixture(name)).unwrap()
    }

    /// Converts a fixture filename into a [`LegacyPath`] representation.
    #[cfg(unix)]
    pub fn legacy_path(name: &str) -> LegacyPath {
        use std::os::unix::ffi::OsStrExt;
        LegacyPath::new(fixture(name).as_os_str().as_bytes().to_vec())
    }

    /// Static [`NativeConfig`] implementation wrapping a pre-built Rustls client configuration.
    #[derive(Debug)]
    pub struct Native(pub Arc<rustls::ClientConfig>);

    impl NativeConfig for Native {
        fn client_config(
            &self,
            _proof: &Unlocked,
            _purpose: TlsPurpose,
        ) -> Result<Arc<rustls::ClientConfig>, TlsSetupError> {
            Ok(Arc::clone(&self.0))
        }
    }

    /// Constructs a native [`TlsConnector`] configured with standard WebPKI certificate validation
    /// against `ca.pem`.
    pub fn native_connector() -> Arc<dyn TlsConnector> {
        let mut roots = RootCertStore::empty();
        for c in certs("ca.pem") {
            roots.add(c).unwrap();
        }
        let verifier = webpki_verifier(Arc::new(roots), provider()).unwrap();
        let cfg = rustls::ClientConfig::builder_with_provider(provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        Arc::new(RustlsConnector::native(
            Arc::new(Native(Arc::new(cfg))),
            provider(),
        ))
    }

    /// Constructs a drop-in [`TlsConnector`] configured with legacy chain validation against
    /// `ca.pem`.
    #[cfg(unix)]
    pub fn legacy_connector() -> Arc<dyn TlsConnector> {
        let args = LegacyTlsArgs {
            ca: Some(legacy_path("ca.pem")),
            cert: None,
            key: None,
            passwd: None,
            env_allowed: false,
            verify: TlsVerify::LegacyChainOnly,
        };
        Arc::new(RustlsConnector::legacy(args, provider()))
    }

    /// server side transport wrapping an active Rustls server connection over a TCP stream.
    pub struct TlsStream(StreamOwned<ServerConnection, TcpStream>);

    impl Read for TlsStream {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            self.0.read(buf)
        }
    }

    impl Write for TlsStream {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            self.0.write(buf)
        }

        fn flush(&mut self) -> io::Result<()> {
            self.0.flush()
        }
    }

    impl Transport for TlsStream {
        fn tcp(&self) -> &TcpStream {
            &self.0.sock
        }
    }

    /// TLS connection acceptor configuring server certificates and optional mutual TLS client
    /// verification.
    #[derive(Debug)]
    pub struct TlsAcceptor {
        config: Arc<ServerConfig>,
    }

    impl TlsAcceptor {
        pub fn new(client_auth: bool) -> Arc<Self> {
            let builder = ServerConfig::builder_with_provider(provider())
                .with_safe_default_protocol_versions()
                .unwrap();
            let builder = if client_auth {
                let mut roots = RootCertStore::empty();
                for c in certs("ca.pem") {
                    roots.add(c).unwrap();
                }
                let verifier =
                    WebPkiClientVerifier::builder_with_provider(Arc::new(roots), provider())
                        .build()
                        .unwrap();
                builder.with_client_cert_verifier(verifier)
            } else {
                builder.with_no_client_auth()
            };
            let config = builder
                .with_single_cert(certs("server.pem"), key("server.key"))
                .unwrap();
            Arc::new(Self {
                config: Arc::new(config),
            })
        }
    }

    impl Acceptor for TlsAcceptor {
        // Handshake state representations require substantial stack allocations in unoptimized
        // builds.
        #[allow(clippy::large_stack_frames)]
        fn accept(&self, tcp: TcpStream) -> io::Result<Box<dyn Transport>> {
            let conn = ServerConnection::new(Arc::clone(&self.config)).map_err(io::Error::other)?;
            // Allocate stream wrapper on heap to accommodate Rustls state sizes.
            let mut s = Box::new(TlsStream(StreamOwned::new(conn, tcp)));
            // Complete the TLS handshake synchronously during accept so subsequent I/O sees
            // decrypted application data.
            let end = std::time::Instant::now() + std::time::Duration::from_secs(10);
            while s.0.conn.is_handshaking() {
                let TlsStream(inner) = &mut *s;
                match inner.conn.complete_io(&mut inner.sock) {
                    Ok(_) => {}
                    Err(e)
                        if matches!(
                            e.kind(),
                            io::ErrorKind::WouldBlock
                                | io::ErrorKind::TimedOut
                                | io::ErrorKind::Interrupted
                        ) && std::time::Instant::now() < end => {}
                    Err(e) => return Err(e),
                }
            }
            Ok(s)
        }
    }
}

/// [`RustixPoll`] wrapper tracking descriptor-ready poll completions and active blocking waits.
#[derive(Default)]
pub struct CountingPoll {
    ready: AtomicUsize,
    waiting: AtomicBool,
}

impl CountingPoll {
    /// Returns the cumulative count of poll invocations that returned ready descriptors.
    pub fn ready(&self) -> usize {
        self.ready.load(Ordering::SeqCst)
    }

    /// Blocks up to `timeout` until the I/O thread enters an idle poll wait with no descriptor
    /// wakeups observed across a 50ms window.
    pub fn wait_idle(&self, timeout: Duration) -> bool {
        wait_until(timeout, || {
            let before = self.ready();
            std::thread::sleep(Duration::from_millis(50));
            self.waiting.load(Ordering::SeqCst) && self.ready() == before
        })
    }
}

impl Poll for CountingPoll {
    fn poll(&self, fds: &mut [PollFd<'_>], timeout_ms: i32) -> rustix::io::Result<usize> {
        self.waiting.store(true, Ordering::SeqCst);
        let r = RustixPoll.poll(fds, timeout_ms);
        self.waiting.store(false, Ordering::SeqCst);
        if matches!(r, Ok(n) if n > 0) {
            self.ready.fetch_add(1, Ordering::SeqCst);
        }
        r
    }
}
