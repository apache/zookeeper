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

//! Shared testing harness, server process lifecycles, and environment infrastructure.
//!
//! Provides orchestrators for live Java-based ZooKeeper daemon processes ([`RealServer`]),
//! process-wide shared server multiplexing ([`SharedServer`]), synthetic network disruption
//! proxies ([`proxy::Proxy`]), in-memory mock servers ([`FakeServer`]), and standardized test
//! timeouts and context wrappers.

#![allow(
    dead_code,
    unreachable_pub,
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::print_stderr
)]

pub mod proxy;

use std::io;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    Client, ClientBuilder, CreateMode, DisconnectCause, Runtime, SessionInfo, StateWatcher,
    ThreadOptions,
};
use zk_fakeserver::FakeServer;

/// Standard operation timeout budget allocated for individual test steps.
pub const WAIT: Duration = Duration::from_secs(5);

/// Session timeout configured across test client instances.
pub const SESSION_TIMEOUT: Duration = Duration::from_secs(4);

/// Environment variable specifying the base port allocation for integration test servers.
pub const PORT_BASE_ENV: &str = "ZK_TEST_PORT_BASE";

/// Environment variable enforcing strict test failure if a required test server is absent.
pub const REQUIRE_SERVER_ENV: &str = "ZK_TEST_REQUIRE_SERVER";

/// Resolves the absolute port address (`base + offset`), returning `None` if `ZK_TEST_PORT_BASE`
/// is unset.
pub fn real_port(offset: u16) -> Option<u16> {
    let base = std::env::var(PORT_BASE_ENV)
        .ok()
        .and_then(|v| v.trim().parse::<u16>().ok());
    let Some(base) = base else {
        return skip(
            "no real-server port",
            &format!("{PORT_BASE_ENV} is not valid"),
        );
    };
    base.checked_add(offset)
}

/// Indicates whether integration tests must fail strictly if live server instances are
/// unavailable.
pub fn server_required() -> bool {
    std::env::var(REQUIRE_SERVER_ENV).is_ok_and(|v| !v.trim().is_empty())
}

/// Aborts current test execution gracefully via `None`, panicking if strict server availability is
/// mandated.
pub fn skip<T>(what: &str, cause: &dyn std::fmt::Display) -> Option<T> {
    assert!(
        !server_required(),
        "{what}: {cause} ({REQUIRE_SERVER_ENV} is set)"
    );
    eprintln!("skip: {what}: {cause}");
    None
}

/// Resolves the path to the workspace test server launcher script `dev/test-server.sh`.
pub fn test_server_script() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../dev/test-server.sh")
}

/// Handle to an external Java ZooKeeper server instance, ensuring automated process teardown on
/// drop.
#[derive(Debug)]
pub struct RealServer {
    port: u16,
    /// The server directory, or `None` for the script default.
    dir: Option<PathBuf>,
    /// Extra `zoo.cfg` lines (`ZK_TEST_SERVER_PROPS`).
    props: String,
    /// Extra JVM options (`ZK_TEST_SERVER_JVM_OPTS`).
    jvm_opts: String,
}

impl RealServer {
    /// Launches a standalone ZooKeeper instance on `port`, awaiting cluster quorum readiness.
    pub fn start(port: u16) -> io::Result<Self> {
        Self::start_with(port, None, "", "")
    }

    /// Spawns a server instance with a custom working directory, configuration overrides, and JVM
    /// options.
    pub fn start_with(
        port: u16,
        dir: Option<PathBuf>,
        props: &str,
        jvm_opts: &str,
    ) -> io::Result<Self> {
        let s = Self {
            port,
            dir,
            props: props.to_owned(),
            jvm_opts: jvm_opts.to_owned(),
        };
        s.run_start()?;
        Ok(s)
    }

    /// Executes the server start script with configured environment options.
    fn run_start(&self) -> io::Result<()> {
        let mut cmd = Command::new("bash");
        cmd.arg(test_server_script())
            .arg("start")
            .arg(self.port.to_string());
        if let Some(d) = &self.dir {
            cmd.arg(d);
        }
        if !self.props.is_empty() {
            cmd.env("ZK_TEST_SERVER_PROPS", &self.props);
        }
        if !self.jvm_opts.is_empty() {
            cmd.env("ZK_TEST_SERVER_JVM_OPTS", &self.jvm_opts);
        }
        let status = cmd.stdout(Stdio::null()).status()?;
        if status.success() {
            Ok(())
        } else {
            // Ensure abandoned server processes are cleaned up following startup failure.
            stop(self.port, self.dir.as_deref());
            Err(io::Error::other(format!(
                "test-server.sh start {} failed: {status}",
                self.port
            )))
        }
    }

    /// Spawns a server instance, returning `None` to skip execution if environmental prerequisites
    /// are unmet.
    pub fn start_or_skip(port: u16) -> Option<Self> {
        match Self::start(port) {
            Ok(s) => Some(s),
            Err(e) => skip("no real ZooKeeper server", &e),
        }
    }

    /// Terminates the server process synchronously, awaiting port closure while preserving on-disk
    /// state.
    pub fn stop_now(&self) {
        stop(self.port, self.dir.as_deref());
    }

    /// Restarts the server process preserving existing data directory state and session leases.
    pub fn start_again(&self) -> io::Result<()> {
        self.run_start()
    }

    /// Listening TCP port of the server process.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Returns the local client connection string (`127.0.0.1:PORT`).
    pub fn connect_string(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }
}

impl Drop for RealServer {
    fn drop(&mut self) {
        stop(self.port, self.dir.as_deref());
    }
}

/// Resolves the default temporary filesystem directory allocated for a server instance on `port`.
pub fn default_server_dir(port: u16) -> PathBuf {
    std::env::temp_dir().join(format!("zk-rust-test-{port}"))
}

/// Launches a clean server instance on `base + offset`, purging residual data from previous test
/// runs.
pub fn fresh_server(offset: u16) -> Option<RealServer> {
    let port = real_port(offset)?;
    // Clean prior execution data directory if present.
    let _ = std::fs::remove_dir_all(default_server_dir(port).join("data"));
    RealServer::start_or_skip(port)
}

/// Issues a termination signal to the server on `port`, polling loopback connectivity until socket
/// release.
fn stop(port: u16, dir: Option<&std::path::Path>) {
    let mut cmd = Command::new("bash");
    cmd.arg(test_server_script())
        .arg("stop")
        .arg(port.to_string());
    if let Some(d) = dir {
        cmd.arg(d);
    }
    let _ = cmd.stdout(Stdio::null()).stderr(Stdio::null()).status();
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    for _ in 0..100 {
        if std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(100)).is_err() {
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

/// Lazily initialized, reference-counted server instance shared across test scenarios in a single
/// binary.
pub struct SharedServer {
    state: Mutex<(usize, Option<RealServer>)>,
    offset: u16,
}

/// A use of a [`SharedServer`]. The drop of the last guard stops it.
pub struct SharedGuard {
    owner: &'static SharedServer,
    connect_string: String,
}

impl SharedServer {
    /// Initializes a shared server handle allocated at `base + offset`.
    pub const fn new(offset: u16) -> Self {
        Self {
            state: Mutex::new((0, None)),
            offset,
        }
    }

    /// Acquires an active lease on the shared server, launching the process if currently inactive.
    pub fn acquire(&'static self) -> Option<SharedGuard> {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if st.1.is_none() {
            st.1 = Some(fresh_server(self.offset)?);
        }
        st.0 += 1;
        let connect_string = st.1.as_ref().map(RealServer::connect_string)?;
        Some(SharedGuard {
            owner: self,
            connect_string,
        })
    }
}

impl SharedGuard {
    /// Formats the target server connection endpoint string (`127.0.0.1:PORT`).
    pub fn connect_string(&self) -> String {
        self.connect_string.clone()
    }
}

impl Drop for SharedGuard {
    fn drop(&mut self) {
        let mut st = self.owner.state.lock().unwrap_or_else(|e| e.into_inner());
        st.0 -= 1;
        if st.0 == 0 {
            // Teardown executing under lock prevents race conditions against subsequent
            // acquisition.
            st.1 = None;
        }
    }
}

/// Extracts the underlying [`DisconnectCause`] from session diagnostic error state if present.
pub fn disconnect_cause(info: &SessionInfo) -> Option<DisconnectCause> {
    info.last_error
        .as_ref()
        .and_then(|e| e.downcast_ref::<DisconnectCause>().copied())
}

/// Extracts the underlying [`io::ErrorKind`] from session diagnostic error state if present.
pub fn io_error_kind(info: &SessionInfo) -> Option<io::ErrorKind> {
    info.last_error
        .as_ref()
        .and_then(|e| e.downcast_ref::<io::Error>().map(io::Error::kind))
}

/// Polls session state mutations bounded by timeout `d` until matching the supplied predicate.
pub async fn wait_state(
    w: &mut StateWatcher,
    d: Duration,
    pred: impl Fn(&SessionInfo) -> bool,
) -> SessionInfo {
    let deadline = tokio::time::Instant::now() + d;
    loop {
        let info = w.current();
        if pred(&info) {
            return info;
        }
        match tokio::time::timeout_at(deadline, w.changed()).await {
            Ok(_) => {}
            Err(_) => panic!("state wait timed out at {info:?}"),
        }
    }
}

/// Awaits predicate satisfaction against session state updates within the default [`WAIT`] budget.
pub async fn wait_info(
    w: &mut StateWatcher,
    mut pred: impl FnMut(&SessionInfo) -> bool,
) -> SessionInfo {
    within(async {
        loop {
            let info = w.current();
            if pred(&info) {
                return info;
            }
            let _ = w.changed().await;
        }
    })
    .await
}

/// Binds an in-memory mock ZooKeeper server on an ephemeral loopback port.
pub fn fake() -> FakeServer {
    FakeServer::start().expect("fake server bind")
}

/// Initializes a [`ClientBuilder`] configured with standard test timeouts and expedited reconnect
/// intervals.
pub fn builder(connect_string: &str) -> ClientBuilder {
    Client::builder(connect_string)
        .session_timeout(SESSION_TIMEOUT)
        .reconnect_delay(Duration::from_millis(50))
}

/// Execution runtime driver configuration for test client instances.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Rt {
    /// OS thread driver managing dedicated background I/O execution.
    Thread,
    /// Asynchronous driver executing within the ambient Tokio task runtime.
    #[cfg(all(unix, feature = "tokio"))]
    Tokio,
}

impl Rt {
    /// Namespace identifier suffix distinguishing test paths.
    pub fn name(self) -> &'static str {
        match self {
            Self::Thread => "thread",
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio => "tokio",
        }
    }

    /// Applies this runtime driver execution policy to the builder.
    pub fn apply(self, b: ClientBuilder) -> ClientBuilder {
        match self {
            Self::Thread => b.runtime(Runtime::Thread(ThreadOptions::default())),
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio => b.runtime(Runtime::Tokio(tokio::runtime::Handle::current())),
        }
    }
}

/// Enumerates all runtime driver configurations enabled for the current build.
pub fn all_rts() -> Vec<Rt> {
    vec![
        Rt::Thread,
        #[cfg(all(unix, feature = "tokio"))]
        Rt::Tokio,
    ]
}

/// Isolated execution context for real-server tests, providing unique root paths and connection
/// handles.
pub struct Ctx {
    /// Server connection endpoint address (`127.0.0.1:PORT`).
    pub cs: String,
    /// Root namespace path reserved for the scenario (e.g. `/<name>-<driver>`).
    pub root: String,
    /// Runtime driver executing client I/O.
    pub rt: Rt,
    _guard: SharedGuard,
}

impl Ctx {
    /// Allocates an isolated context under `/<name>-<rt>`, returning `None` if server startup
    /// fails.
    pub fn new(server: &'static SharedServer, name: &str, rt: Rt) -> Option<Self> {
        let guard = server.acquire()?;
        Some(Self {
            cs: guard.connect_string(),
            root: format!("/{name}-{}", rt.name()),
            rt,
            _guard: guard,
        })
    }

    /// Constructs a client builder configured for the active server and runtime driver.
    pub fn builder(&self) -> ClientBuilder {
        self.rt.apply(builder(&self.cs))
    }

    /// Connects a client instance bound to the scenario runtime driver.
    pub async fn connect(&self) -> Client {
        within(self.builder().connect()).await.expect("connect")
    }

    /// Resolves the remote listening port of the active server instance.
    pub fn port(&self) -> u16 {
        self.cs
            .rsplit(':')
            .next()
            .and_then(|p| p.parse().ok())
            .expect("server port")
    }

    /// Resolves a full path relative to the test scenario's root znode.
    pub fn path(&self, rel: &str) -> String {
        format!("{}{rel}", self.root)
    }
}

/// Awaits future resolution within the standard [`WAIT`] budget, panicking on timeout expiration.
pub async fn within<F: std::future::Future>(f: F) -> F::Output {
    tokio::time::timeout(WAIT, f).await.expect("timed out")
}

/// Establishes a client connection using the provided builder, awaiting initial session handshake.
pub async fn connect(b: ClientBuilder) -> Client {
    within(b.connect()).await.expect("connect")
}

/// Prepares test namespace hierarchy by creating the isolated root znode and returning the
/// connected client.
pub async fn setup(ctx: &Ctx) -> Client {
    let c = ctx.connect().await;
    within(c.create(&ctx.root, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    c
}

/// Resolves the absolute path to a TLS test certificate fixture.
pub fn fixture(name: &str) -> PathBuf {
    zk_testkit::fixtures::tls_dir().join(name)
}

/// Constructs a thread-backed execution runtime with default options.
pub fn thread() -> Runtime {
    Runtime::Thread(ThreadOptions::default())
}

/// Configures minimal TLS options containing test CA trust anchors without client identities.
#[cfg(feature = "tls-rustls")]
pub fn ca_only() -> apache_zookeeper::TlsOptions {
    apache_zookeeper::TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .build()
        .unwrap()
}
