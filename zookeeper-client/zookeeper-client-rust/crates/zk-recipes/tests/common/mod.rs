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

//! Shared test harness and utilities for `apache-zookeeper-recipes` integration tests.
//!
//! - [`fake`] and [`connect`]: start an in-process `zk_fakeserver::FakeServer` on an
//!   ephemeral loopback port and connect a configured test client.
//! - [`RealServer`] and [`Ensemble`]: manage a standalone Java ZooKeeper server
//!   (`dev/test-server.sh`) or a three-node quorum (`dev/test-ensemble.sh`), stopping
//!   the server processes on drop.
//! - [`Servers`]: abstracts over [`RealServer`] and [`Ensemble`] by [`Topology`] so
//!   each real-server scenario can run against both deployments.
//! - [`Proxy`] and [`ProxyGroup`]: loopback TCP proxies supporting connection cuts,
//!   request holding, reply muting, and round-trip delay injection.
//!
//! Real-server ports are allocated from `ZK_TEST_PORT_BASE` (`base..=base + 9`, e.g.
//! `23400`). When `ZK_TEST_PORT_BASE` is unset or a server cannot start, tests skip
//! unless `ZK_TEST_REQUIRE_SERVER` is set (as in `dev/run-recipes-real.sh`), in which
//! case startup failure panics. Real-server tests are annotated with
//! `#[ignore = "real-server"]` so a default `cargo test` does not spawn Java processes.
//! `ZK_TEST_SERVER_VERSION` selects a pre-downloaded release under `/tmp/zk-servers`
//! (`dev/get-release-server.sh`); when unset, the in-tree build is used.
//!
//! Because standalone and ensemble servers share the 10-port range, at most one
//! [`RealServer`] or [`Ensemble`] may run at a time. Intra-process serialization uses
//! a static mutex, while cross-process serialization uses a PID-tracked lease directory
//! in the system temp directory. Acquiring the lease also reaps any leftover server
//! processes from a previously crashed test runner.

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

#[path = "../../../zk-client/tests/common/proxy.rs"]
mod proxy;

use std::io;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::process::{Command, Stdio};
use std::sync::{Mutex, MutexGuard};
use std::task::{Context, Poll, Waker};
use std::thread::ThreadId;
use std::time::{Duration, Instant};

use apache_zookeeper::{Client, ClientBuilder, Runtime, SessionInfo, StateWatcher, ThreadOptions};
use apache_zookeeper_recipes::{Latch, LeaderLatch, Participant, TreeCache, TreeEvent};
use zk_fakeserver::FakeServer;

pub use proxy::Proxy;

/// Default timeout budget for individual asynchronous test operations.
pub const WAIT: Duration = Duration::from_secs(10);

/// Default session timeout for test clients.
pub const SESSION_TIMEOUT: Duration = Duration::from_secs(4);

/// Environment variable specifying the base port of the 10-port test range.
pub const PORT_BASE_ENV: &str = "ZK_TEST_PORT_BASE";

/// Environment variable selecting a ZooKeeper release version under `/tmp/zk-servers`.
pub const SERVER_VERSION_ENV: &str = "ZK_TEST_SERVER_VERSION";

/// Environment variable that turns server startup skips into hard test failures.
pub const REQUIRE_SERVER_ENV: &str = "ZK_TEST_REQUIRE_SERVER";

/// Legacy ZooKeeper release lacking persistent recursive watch support.
pub const LEGACY_VERSION: &str = "3.5.10";

/// Port offset within `ZK_TEST_PORT_BASE` for the standalone server.
pub const STANDALONE_OFFSET: u16 = 0;

/// Port offset within `ZK_TEST_PORT_BASE` for the three-node ensemble (`base + 1..=base + 9`:
/// three client ports, three quorum ports, and three leader-election ports).
pub const ENSEMBLE_OFFSET: u16 = 1;

/// Resolves `base + offset` from `ZK_TEST_PORT_BASE`, or `None` if unset or invalid.
pub fn real_port(offset: u16) -> Option<u16> {
    let base: u16 = std::env::var(PORT_BASE_ENV).ok()?.trim().parse().ok()?;
    base.checked_add(offset)
}

/// Returns the release version from `ZK_TEST_SERVER_VERSION`, or `None` for the in-tree build.
pub fn server_version() -> Option<String> {
    std::env::var(SERVER_VERSION_ENV)
        .ok()
        .map(|v| v.trim().to_owned())
        .filter(|v| !v.is_empty())
}

/// Returns `true` if `ZK_TEST_REQUIRE_SERVER` is set to a non-empty value.
pub fn server_required() -> bool {
    std::env::var(REQUIRE_SERVER_ENV).is_ok_and(|v| !v.trim().is_empty())
}

/// Logs a skip notice and returns `None`, or panics if `ZK_TEST_REQUIRE_SERVER` is set.
fn skip<T>(what: &str, cause: &dyn std::fmt::Display) -> Option<T> {
    assert!(
        !server_required(),
        "{what}: {cause} ({REQUIRE_SERVER_ENV} is set)"
    );
    eprintln!("skip: {what}: {cause}");
    None
}

/// Returns the path to the workspace `dev/` script directory.
pub fn dev_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../dev")
}

/// Starts an in-process [`FakeServer`] bound to an ephemeral loopback port.
pub fn fake() -> FakeServer {
    FakeServer::start().expect("fake server bind")
}

/// Creates a [`ClientBuilder`] configured with [`SESSION_TIMEOUT`], a 50ms reconnect
/// delay, and a dedicated client I/O thread (`Runtime::Thread`).
pub fn builder(connect_string: &str) -> ClientBuilder {
    Client::builder(connect_string)
        .session_timeout(SESSION_TIMEOUT)
        .reconnect_delay(Duration::from_millis(50))
        .runtime(Runtime::Thread(ThreadOptions::default()))
}

/// Connects a test client to `connect_string` within [`WAIT`].
pub async fn connect(connect_string: &str) -> Client {
    within(builder(connect_string).connect())
        .await
        .expect("connect")
}

/// Awaits `f` with a timeout of [`WAIT`], panicking if the deadline expires.
pub async fn within<F: std::future::Future>(f: F) -> F::Output {
    tokio::time::timeout(WAIT, f).await.expect("timed out")
}

/// Returns `true` if `f` remains pending after duration `d`.
pub async fn still_pending<F: std::future::Future>(f: F, d: Duration) -> bool {
    tokio::time::timeout(d, f).await.is_err()
}

/// Waits up to [`WAIT`] for `w` to reach a [`SessionInfo`] state satisfying `pred`.
pub async fn wait_state(w: &mut StateWatcher, pred: impl Fn(&SessionInfo) -> bool) -> SessionInfo {
    let deadline = tokio::time::Instant::now() + WAIT;
    loop {
        let info = w.current();
        if pred(&info) {
            return info;
        }
        if tokio::time::timeout_at(deadline, w.changed())
            .await
            .is_err()
        {
            panic!("state wait timed out at {info:?}");
        }
    }
}

/// Waits up to [`WAIT`] for the [`FakeServer`] cluster state to satisfy `pred`.
pub fn wait_fake(server: &FakeServer, pred: impl FnMut(&zk_fakeserver::Cluster) -> bool) {
    assert!(
        server.cluster().wait_until(WAIT, pred),
        "fake server condition timed out"
    );
}

/// Polls `fut` once using a no-op waker.
pub fn poll_once<F: std::future::Future>(fut: Pin<&mut F>) -> Poll<F::Output> {
    fut.poll(&mut Context::from_waker(Waker::noop()))
}

/// Returns the raw numeric session ID of `client`.
pub fn session_id(client: &Client) -> i64 {
    client.session().0.0
}

/// Returns `true` if `s` is a canonical lowercase RFC 4122 version-4 UUID string
/// (matching Java's `UUID.randomUUID().toString()`).
pub fn is_v4(s: &str) -> bool {
    let groups: Vec<&str> = s.split('-').collect();
    let lens: Vec<usize> = groups.iter().map(|g| g.len()).collect();
    lens == [8, 4, 4, 4, 12]
        && groups
            .iter()
            .all(|g| g.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')))
        && groups[2].starts_with('4')
        && groups[3].starts_with(['8', '9', 'a', 'b'])
}

/// Starts a [`LeaderLatch`] at `path` with participant ID `id`.
pub async fn start_latch(client: &Client, path: &str, id: &str) -> Latch {
    let latch = LeaderLatch::new(client, path, id).unwrap();
    within(latch.start()).await.unwrap()
}

/// Converts a slice of [`Participant`]s into `(utf8_id, is_leader)` pairs.
pub fn ids(list: &[Participant]) -> Vec<(String, bool)> {
    list.iter()
        .map(|p| (String::from_utf8(p.id.clone()).unwrap(), p.is_leader))
        .collect()
}

/// Converts borrowed `(id, is_leader)` tuples into owned `(String, bool)` pairs for assertions.
pub fn expect(list: &[(&str, bool)]) -> Vec<(String, bool)> {
    list.iter().map(|(id, l)| ((*id).to_owned(), *l)).collect()
}

/// Formats a [`TreeEvent`] into a compact test label (`+path`, `~path`, `-path`,
/// `Initialized`, or `Resynced`).
pub fn label(ev: &TreeEvent) -> String {
    match ev {
        TreeEvent::NodeAdded { path, .. } => format!("+{path}"),
        TreeEvent::NodeUpdated { path, .. } => format!("~{path}"),
        TreeEvent::NodeRemoved { path, .. } => format!("-{path}"),
        other => format!("{other:?}"),
    }
}

/// Awaits the next [`TreeEvent`] from `cache` within [`WAIT`].
pub async fn next(cache: &mut TreeCache) -> TreeEvent {
    match within(cache.next()).await {
        Some(Ok(ev)) => ev,
        other => panic!("expected an event, got {other:?}"),
    }
}

/// Collects compact event labels from `cache` through the next `Initialized` or `Resynced`.
pub async fn until_mark(cache: &mut TreeCache) -> Vec<String> {
    let mut out = Vec::new();
    loop {
        let ev = next(cache).await;
        let mark = matches!(ev, TreeEvent::Initialized | TreeEvent::Resynced);
        out.push(label(&ev));
        if mark {
            return out;
        }
    }
}

/// Intra-process mutex serializing access to the real-server port range.
static RANGE: Mutex<()> = Mutex::new(());

/// Thread ID of the current [`RANGE`] holder, used to fail fast on reentrant acquisition
/// rather than deadlocking.
static HOLDER: Mutex<Option<ThreadId>> = Mutex::new(None);

/// Maximum wait time when contending for the cross-process port-range lease.
const LEASE_WAIT: Duration = Duration::from_secs(900);

/// RAII guard holding both the intra-process mutex and cross-process directory lease
/// for a real-server port range.
#[derive(Debug)]
pub struct RangeLease {
    dir: PathBuf,
    _guard: MutexGuard<'static, ()>,
}

impl Drop for RangeLease {
    fn drop(&mut self) {
        *HOLDER.lock().unwrap_or_else(|e| e.into_inner()) = None;
        release_lease_at(&self.dir);
    }
}

/// Returns the temporary lease directory path for port range `key`.
pub fn lease_dir(key: u16) -> PathBuf {
    std::env::temp_dir().join(format!("zk-recipes-lease-{key}"))
}

/// Resolves the lease key from `ZK_TEST_PORT_BASE`, falling back to `fallback`.
fn range_key(fallback: u16) -> u16 {
    real_port(STANDALONE_OFFSET).unwrap_or(fallback)
}

/// Acquires the intra-process lock and cross-process lease for range `key`, then
/// terminates any orphaned server processes left in that range by prior runs.
///
/// # Panics
///
/// Panics if the calling thread already holds the range lease.
fn take_range(key: u16) -> io::Result<RangeLease> {
    let me = std::thread::current().id();
    assert!(
        *HOLDER.lock().unwrap_or_else(|e| e.into_inner()) != Some(me),
        "this thread holds the port range already: drop the RealServer or Ensemble first"
    );
    let guard = RANGE.lock().unwrap_or_else(|e| e.into_inner());
    let dir = lease_dir(key);
    if !acquire_lease_at(&dir, LEASE_WAIT, WAIT) {
        return Err(io::Error::other(format!(
            "no lease of the port range {}",
            dir.display()
        )));
    }
    *HOLDER.lock().unwrap_or_else(|e| e.into_inner()) = Some(me);
    let lease = RangeLease { dir, _guard: guard };
    reap_leftovers(key);
    Ok(lease)
}

/// Acquires the directory lease at `dir` and writes the current PID into `dir/pid`.
///
/// Reclaims stale leases whose owner PID is no longer alive, or uninitialized lease
/// directories that remain without a `pid` file for longer than `grace`. Returns
/// `false` if the lease remains held by a live process after `wait` or if `dir`
/// cannot be created.
pub fn acquire_lease_at(dir: &Path, wait: Duration, grace: Duration) -> bool {
    let start = Instant::now();
    let mut no_owner_since: Option<Instant> = None;
    loop {
        match std::fs::create_dir(dir) {
            Ok(()) => {
                if std::fs::write(dir.join("pid"), std::process::id().to_string()).is_ok() {
                    return true;
                }
                let _ = std::fs::remove_dir_all(dir);
                eprintln!("no lease {}: the pid file cannot be written", dir.display());
                return false;
            }
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
            Err(e) => {
                eprintln!("no lease {}: {e}", dir.display());
                return false;
            }
        }
        let stale = match lease_owner(dir) {
            Some(pid) => {
                no_owner_since = None;
                !process_alive(pid)
            }
            None => no_owner_since.get_or_insert_with(Instant::now).elapsed() >= grace,
        };
        if stale {
            let _ = std::fs::remove_dir_all(dir);
            no_owner_since = None;
            continue;
        }
        if start.elapsed() >= wait {
            eprintln!("no lease {}: it stays taken", dir.display());
            return false;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

/// Removes the lease directory `dir` if owned by the current process.
pub fn release_lease_at(dir: &Path) {
    if lease_owner(dir) == Some(std::process::id()) {
        let _ = std::fs::remove_dir_all(dir);
    }
}

/// Reads the owner PID from `dir/pid`, if present and valid.
pub fn lease_owner(dir: &Path) -> Option<u32> {
    std::fs::read_to_string(dir.join("pid"))
        .ok()
        .and_then(|s| s.trim().parse::<u32>().ok())
}

/// Returns `true` if a process with `pid` is currently running (`kill -0`).
pub fn process_alive(pid: u32) -> bool {
    Command::new("kill")
        .args(["-0", &pid.to_string()])
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

/// Stops any orphaned standalone or ensemble ZooKeeper servers left in port range `key`.
fn reap_leftovers(key: u16) {
    let Ok(entries) = std::fs::read_dir(std::env::temp_dir()) else {
        return;
    };
    let standalone = key + STANDALONE_OFFSET;
    let ensemble = key + ENSEMBLE_OFFSET;
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.starts_with(STANDALONE_DIR_PREFIX)
            && name.ends_with(&format!("-{standalone}"))
            && path.join("server.pid").exists()
        {
            eprintln!("stop a leftover server in {}", path.display());
            let args = [
                "stop".to_owned(),
                standalone.to_string(),
                path.display().to_string(),
            ];
            let _ = run_script("test-server.sh", &args, None);
        }
        if name.starts_with(ENSEMBLE_DIR_PREFIX)
            && name.ends_with(&format!("-{ensemble}"))
            && (1..=3).any(|n| path.join(format!("node{n}/server.pid")).exists())
        {
            eprintln!("stop a leftover ensemble in {}", path.display());
            let args = [
                "stop".to_owned(),
                ensemble.to_string(),
                path.display().to_string(),
            ];
            let _ = run_script("test-ensemble.sh", &args, None);
        }
    }
}

/// Directory name prefix for standalone server working directories.
const STANDALONE_DIR_PREFIX: &str = "zk-recipes-test-";

/// Directory name prefix for ensemble working directories.
const ENSEMBLE_DIR_PREFIX: &str = "zk-recipes-ensemble-";

/// Executes a script in `dev/` with `args` and optional `ZK_TEST_SERVER_VERSION`.
fn run_script(script: &str, args: &[String], version: Option<&str>) -> io::Result<()> {
    let mut cmd = Command::new("bash");
    cmd.arg(dev_dir().join(script)).args(args);
    if let Some(v) = version {
        // Clear `ZK_TEST_SERVER_HOME` so it does not override `ZK_TEST_SERVER_VERSION`.
        cmd.env(SERVER_VERSION_ENV, v)
            .env_remove("ZK_TEST_SERVER_HOME");
    }
    let status = cmd.stdout(Stdio::null()).status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::other(format!(
            "{script} {args:?} failed: {status}"
        )))
    }
}

/// Returns `true` if a TCP listener accepts connections on `127.0.0.1:port`.
pub fn port_open(port: u16) -> bool {
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(200)).is_ok()
}

/// Returns an error if any port in `ports` already has an active listener.
fn check_free(ports: impl IntoIterator<Item = u16>) -> io::Result<()> {
    match ports.into_iter().find(|&p| port_open(p)) {
        Some(p) => Err(io::Error::other(format!("the port {p} is in use"))),
        None => Ok(()),
    }
}

/// Polls `srvr` on `port` up to [`WAIT`] until the server reports an active `Mode:`.
fn wait_serving(port: u16) -> io::Result<()> {
    let deadline = Instant::now() + WAIT;
    while srvr_mode(port).is_none() {
        if Instant::now() >= deadline {
            return Err(io::Error::other(format!(
                "the server on {port} does not serve requests"
            )));
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    Ok(())
}

/// Waits up to 10s for `port` to stop accepting TCP connections.
fn wait_closed(port: u16) {
    for _ in 0..100 {
        if !port_open(port) {
            return;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

/// Handle to a running standalone Java ZooKeeper server that stops the server on drop.
#[derive(Debug)]
pub struct RealServer {
    port: u16,
    dir: PathBuf,
    version: Option<String>,
    _range: RangeLease,
}

impl RealServer {
    /// Starts a standalone server on `port` with an empty data tree, using release
    /// `version` (or the in-tree build when `None`).
    ///
    /// # Errors
    ///
    /// Returns an error if the server distribution is missing, the port is in use,
    /// or the startup script fails.
    pub fn start(port: u16, version: Option<&str>) -> io::Result<Self> {
        let tag = version.unwrap_or("head");
        let dir = std::env::temp_dir().join(format!("{STANDALONE_DIR_PREFIX}{tag}-{port}"));
        let range = take_range(range_key(port))?;
        let s = Self {
            port,
            dir,
            version: version.map(str::to_owned),
            _range: range,
        };
        // Stop any leftover server instance and wipe prior data before starting.
        s.stop_now();
        let _ = std::fs::remove_dir_all(s.dir.join("data"));
        check_free([port])?;
        // Wait for `srvr` to report a `Mode:` line (unlike `ruok`, which can respond
        // before the request processor pipeline is initialized).
        if let Err(e) = s.run("start").and_then(|()| wait_serving(port)) {
            s.stop_now();
            return Err(e);
        }
        Ok(s)
    }

    /// Starts a standalone server on the configured test port range using
    /// `ZK_TEST_SERVER_VERSION`, or returns `None` to skip the test.
    pub fn start_or_skip() -> Option<Self> {
        Self::start_version_or_skip(server_version().as_deref())
    }

    /// Starts a standalone server on the configured test port range with `version`,
    /// or returns `None` to skip the test.
    pub fn start_version_or_skip(version: Option<&str>) -> Option<Self> {
        let what = format!("no real ZooKeeper server {}", version.unwrap_or("head"));
        let Some(port) = real_port(STANDALONE_OFFSET) else {
            return skip(&what, &format!("{PORT_BASE_ENV} is not set"));
        };
        match Self::start(port, version) {
            Ok(s) => Some(s),
            Err(e) => skip(&what, &e),
        }
    }

    fn run(&self, cmd: &str) -> io::Result<()> {
        let args = [
            cmd.to_owned(),
            self.port.to_string(),
            self.dir.display().to_string(),
        ];
        run_script("test-server.sh", &args, self.version.as_deref())
    }

    /// Stops the server process and waits until its client port closes.
    pub fn stop_now(&self) {
        let _ = self.run("stop");
        wait_closed(self.port);
    }

    /// Returns the `127.0.0.1:<port>` connect string for this server.
    pub fn connect_string(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }

    /// Returns the client TCP port of this server.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Returns the selected release version, or `None` for the in-tree server build.
    pub fn version(&self) -> Option<&str> {
        self.version.as_deref()
    }
}

impl Drop for RealServer {
    fn drop(&mut self) {
        self.stop_now();
    }
}

/// Handle to a three-node ZooKeeper ensemble that stops all member processes on drop.
///
/// `dev/test-ensemble.sh` assigns node `n` (`1..=3`) client port `base + n - 1`,
/// quorum port `base + n + 2`, and leader-election port `base + n + 5`.
#[derive(Debug)]
pub struct Ensemble {
    base: u16,
    dir: PathBuf,
    version: Option<String>,
    _range: RangeLease,
}

/// Timeout budget for ensemble leader election.
pub const ELECTION_WAIT: Duration = Duration::from_secs(30);

impl Ensemble {
    /// Starts a three-node ensemble at port `base` with empty data directories and
    /// waits until every node serves as leader or follower.
    ///
    /// # Errors
    ///
    /// Returns an error if any port in `base..base + 9` is in use or the script fails.
    pub fn start(base: u16, version: Option<&str>) -> io::Result<Self> {
        let tag = version.unwrap_or("head");
        let dir = std::env::temp_dir().join(format!("{ENSEMBLE_DIR_PREFIX}{tag}-{base}"));
        let range = take_range(range_key(base.saturating_sub(ENSEMBLE_OFFSET)))?;
        let s = Self {
            base,
            dir,
            version: version.map(str::to_owned),
            _range: range,
        };
        // Stop any existing processes before removing the working directory and PID files.
        s.stop_all();
        let _ = std::fs::remove_dir_all(&s.dir);
        check_free(base..base + 9)?;
        if let Err(e) = s.run(&["start".to_owned()]) {
            s.stop_all();
            return Err(e);
        }
        Ok(s)
    }

    /// Starts the three-node ensemble on the configured test port range using
    /// `ZK_TEST_SERVER_VERSION`, or returns `None` to skip the test.
    pub fn start_or_skip() -> Option<Self> {
        Self::start_version_or_skip(server_version().as_deref())
    }

    /// Starts the three-node ensemble on the configured test port range with `version`,
    /// or returns `None` to skip the test.
    pub fn start_version_or_skip(version: Option<&str>) -> Option<Self> {
        let what = format!("no ZooKeeper ensemble {}", version.unwrap_or("head"));
        let Some(base) = real_port(ENSEMBLE_OFFSET) else {
            return skip(&what, &format!("{PORT_BASE_ENV} is not set"));
        };
        match Self::start(base, version) {
            Ok(s) => Some(s),
            Err(e) => skip(&what, &e),
        }
    }

    fn run(&self, cmd: &[String]) -> io::Result<()> {
        let mut args = cmd.to_vec();
        args.insert(1, self.base.to_string());
        args.push(self.dir.display().to_string());
        run_script("test-ensemble.sh", &args, self.version.as_deref())
    }

    /// Returns the client port of ensemble node `n` (`1..=3`).
    pub fn client_port(&self, n: u16) -> u16 {
        assert!((1..=3).contains(&n), "no node {n}");
        self.base + n - 1
    }

    /// Returns the comma-separated connect string for all three ensemble nodes.
    pub fn connect_string(&self) -> String {
        (1..=3)
            .map(|n| self.node_connect_string(n))
            .collect::<Vec<_>>()
            .join(",")
    }

    /// Returns the `127.0.0.1:<port>` connect string for node `n` alone.
    pub fn node_connect_string(&self, n: u16) -> String {
        format!("127.0.0.1:{}", self.client_port(n))
    }

    /// Returns the selected release version, or `None` for the in-tree server build.
    pub fn version(&self) -> Option<&str> {
        self.version.as_deref()
    }

    /// Stops ensemble node `n` and waits until its client port closes.
    pub fn stop_node(&self, n: u16) -> io::Result<()> {
        self.run(&["stop-node".to_owned(), n.to_string()])?;
        wait_closed(self.client_port(n));
        Ok(())
    }

    /// Restarts ensemble node `n` against its existing data directory and waits
    /// until it rejoins as leader or follower.
    pub fn start_node(&self, n: u16) -> io::Result<()> {
        self.run(&["start-node".to_owned(), n.to_string()])
    }

    /// Queries `srvr` across the ensemble and returns the node number (`1..=3`)
    /// currently serving as leader, if any.
    pub fn leader(&self) -> Option<u16> {
        (1..=3).find(|&n| srvr_mode(self.client_port(n)).as_deref() == Some("leader"))
    }

    /// Waits up to [`ELECTION_WAIT`] for a node other than `not` to become leader.
    pub fn wait_leader(&self, not: Option<u16>) -> Option<u16> {
        let deadline = Instant::now() + ELECTION_WAIT;
        loop {
            if let Some(n) = self.leader().filter(|&n| Some(n) != not) {
                return Some(n);
            }
            if Instant::now() >= deadline {
                return None;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    fn stop_all(&self) {
        let _ = self.run(&["stop".to_owned()]);
        for n in 1..=3 {
            wait_closed(self.client_port(n));
        }
    }
}

impl Drop for Ensemble {
    fn drop(&mut self) {
        self.stop_all();
    }
}

/// Server deployment topology for parameterized real-server integration tests.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Topology {
    /// Single standalone server ([`RealServer`]).
    Standalone,
    /// Three-node quorum ([`Ensemble`]).
    Ensemble,
}

/// Active server deployment for a real-server test; stops all servers on drop.
#[derive(Debug)]
pub enum Servers {
    /// Single standalone server.
    Standalone(RealServer),
    /// Three-node quorum.
    Ensemble(Ensemble),
}

impl Servers {
    /// Starts the requested `topology` using `ZK_TEST_SERVER_VERSION`, or returns
    /// `None` to skip the test.
    pub fn start_or_skip(topology: Topology) -> Option<Self> {
        match topology {
            Topology::Standalone => RealServer::start_or_skip().map(Self::Standalone),
            Topology::Ensemble => Ensemble::start_or_skip().map(Self::Ensemble),
        }
    }

    /// Returns the connect string covering all servers in this deployment.
    pub fn connect_string(&self) -> String {
        match self {
            Self::Standalone(s) => s.connect_string(),
            Self::Ensemble(e) => e.connect_string(),
        }
    }

    /// Returns the client ports of all servers in this deployment.
    pub fn client_ports(&self) -> Vec<u16> {
        match self {
            Self::Standalone(s) => vec![s.port()],
            Self::Ensemble(e) => (1..=3).map(|n| e.client_port(n)).collect(),
        }
    }

    /// Returns a client port suitable for placing a single-node [`Proxy`] in front
    /// of: the standalone server port, or a follower port on an ensemble (exercising
    /// follower-to-leader request forwarding).
    pub fn relay_port(&self) -> u16 {
        match self {
            Self::Standalone(s) => s.port(),
            Self::Ensemble(e) => {
                let leader = e.leader().expect("the ensemble has a leader");
                let follower = (1..=3).find(|&n| n != leader).expect("a follower");
                e.client_port(follower)
            }
        }
    }

    /// Returns a reference to the underlying [`Ensemble`], or `None` if standalone.
    pub fn ensemble(&self) -> Option<&Ensemble> {
        match self {
            Self::Standalone(_) => None,
            Self::Ensemble(e) => Some(e),
        }
    }
}

/// Issues a `sync` on `/` followed by `get_children(path)` so follower reads reflect
/// all prior leader commits, returning an empty vector on `NoNode`.
pub async fn synced_children(client: &Client, path: &str) -> Vec<String> {
    within(client.sync("/")).await.expect("sync");
    match within(client.get_children(path)).await {
        Ok(c) => c,
        Err(apache_zookeeper::Error::NoNode) => Vec::new(),
        Err(e) => panic!("list {path}: {e}"),
    }
}

/// Sends four-letter command `cmd` to `127.0.0.1:port` and returns the response body.
pub fn four_letter(port: u16, cmd: &str) -> Option<String> {
    use std::io::{Read, Write};
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    let mut s = std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(1)).ok()?;
    s.set_read_timeout(Some(Duration::from_secs(2))).ok()?;
    s.write_all(cmd.as_bytes()).ok()?;
    let mut text = String::new();
    s.read_to_string(&mut text).ok()?;
    Some(text)
}

/// Returns the `Mode:` field from `srvr` on `port` (`standalone`, `leader`, or `follower`).
pub fn srvr_mode(port: u16) -> Option<String> {
    srvr_field(port, "Mode: ")
}

/// Extracts the base version number (e.g. `3.9.6`) from the `Zookeeper version:`
/// line of `srvr` on `port`.
pub fn srvr_version(port: u16) -> Option<String> {
    let line = srvr_field(port, "Zookeeper version: ")?;
    line.split(['-', ',', ' '])
        .next()
        .map(str::to_owned)
        .filter(|v| !v.is_empty())
}

/// Returns the trimmed value following `prefix` on the first matching line of `srvr`.
fn srvr_field(port: u16, prefix: &str) -> Option<String> {
    four_letter(port, "srvr")?
        .lines()
        .find_map(|l| l.strip_prefix(prefix))
        .map(|m| m.trim().to_owned())
}

/// Waits up to [`WAIT`] until all serving nodes on `ports` report the same `Zxid:`
/// via `srvr`, ensuring all followers have caught up with the leader.
pub fn wait_caught_up(ports: &[u16]) {
    let deadline = Instant::now() + WAIT;
    loop {
        let zxids: Vec<String> = ports
            .iter()
            .filter_map(|&p| srvr_field(p, "Zxid: "))
            .collect();
        if zxids.first().is_some_and(|z| zxids.iter().all(|x| x == z)) {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "the servers do not catch up: {zxids:?}"
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// Returns the path to `name` within the workspace `dev/` directory.
pub fn dev_file(name: &str) -> PathBuf {
    dev_dir().join(Path::new(name))
}

/// Group of [`Proxy`] instances fronting each server port in a connect string,
/// allowing ensemble tests to cut, hold, restore, or delay all server paths at once.
pub struct ProxyGroup {
    proxies: Vec<Proxy>,
}

impl ProxyGroup {
    /// Starts one [`Proxy`] for each port in `target_ports`.
    pub fn start(target_ports: &[u16]) -> Self {
        Self {
            proxies: target_ports.iter().map(|&p| Proxy::start(p)).collect(),
        }
    }

    /// Returns the comma-separated connect string of all proxies in the group.
    pub fn connect_string(&self) -> String {
        self.proxies
            .iter()
            .map(Proxy::connect_string)
            .collect::<Vec<_>>()
            .join(",")
    }

    /// Cuts connections across all proxies in the group.
    pub fn cut(&self) {
        self.proxies.iter().for_each(Proxy::cut);
    }

    /// Holds client-to-server traffic across all proxies in the group.
    pub fn hold(&self) {
        self.proxies.iter().for_each(Proxy::hold);
    }

    /// Restores traffic across all proxies in the group.
    pub fn restore(&self) {
        self.proxies.iter().for_each(Proxy::restore);
    }

    /// Configures per-direction forwarding delay `d` across all proxies in the group.
    pub fn delay(&self, d: Duration) {
        for p in &self.proxies {
            p.delay(d);
        }
    }
}
