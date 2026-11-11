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

//! Fluent configuration builder ([`ClientBuilder`]), connection futures ([`ConnectFuture`]), and
//! runtime options.
//!
//! [`ClientBuilder`] records client configuration parameters without immediate side effects. Upon
//! calling [`ClientBuilder::build`] or [`ClientBuilder::connect`], the builder validates
//! configuration invariants, initializes protocol state machine connections, launches the chosen
//! background driver, and establishes shared client state. Initial request submissions are queued
//! in memory until connection handshake completion.

use std::future::Future;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

#[cfg(not(feature = "tls-rustls"))]
use zk_driver::net::tls::TlsConnector;
use zk_driver::{Env, HostLeaves};
use zk_jute::JString;
use zk_proto::compat::ResolveCadence;
use zk_proto::{ClientId, Compat, Config, Connection, DriverMode, SecretBytes};

use crate::client::Client;
use crate::connect_string;
use crate::driver;
use crate::error::{ConnectError, Error};
use crate::follow;
use crate::future::ResponseFuture;
use crate::host::{NativeHost, StdResolve};
use crate::observe::{Metrics, Observer};
use crate::path::{PathArg, PathError, ZPathBuf};
use crate::route::Router;
#[cfg(any(not(feature = "tls-rustls"), feature = "sasl"))]
use crate::route::TlsErrorSource;
#[cfg(feature = "sasl")]
use crate::sasl::{LastSaslError, NativeSasl, SaslConfig};
use crate::shared::{self, ClientConfig, Shared};
#[cfg(feature = "sasl")]
use crate::sync::LockExt;
#[cfg(feature = "blocking")]
use crate::sync::blocking_refused;
#[cfg(feature = "tls-rustls")]
use crate::tls::TlsOptions;
use crate::tokens::NativeTokens;
use crate::types::{SessionId, SessionPassword};
use crate::watch::{SessionInfo, SessionState, StateCell, StateWatcher};

/// Default session timeout applied when none is explicitly configured.
pub(crate) const DEFAULT_SESSION_TIMEOUT: Duration = Duration::from_secs(30);
/// Base reconnect backoff delay between sequential passes across the ensemble server list.
pub(crate) const DEFAULT_RECONNECT_DELAY: Duration = Duration::from_secs(1);
/// Random jitter factor percentage applied to reconnect backoff intervals (25%).
pub(crate) const RECONNECT_JITTER_PCT: u8 = 25;
/// Maximum duration permitted for graceful session close round-trips before tearing down sockets.
pub(crate) const DEFAULT_CLOSE_TIMEOUT: Duration = Duration::from_secs(3);
/// Default maximum protocol frame size permitted for inbound packets (4 MiB).
pub(crate) const DEFAULT_MAX_FRAME_SIZE: u32 = zk_proto::compat::NATIVE_MAX_FRAME_LEN;
/// Default item capacity configured for the internal driver read-gate backpressure buffer.
pub(crate) const DEFAULT_DISPATCH_QUEUE_CAPACITY: usize = 4096;

/// Ordering policy governing server selection from the configured ensemble host list.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub enum ServerOrder {
    /// Randomly shuffles the ensemble host sequence upon initial connection and each server list
    /// refresh.
    #[default]
    Shuffled,
    /// Preserves the exact literal ordering defined in the connection string.
    AsGiven,
}

/// Policy governing DNS host name resolution and re-resolution frequency.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub enum DnsRefresh {
    /// Re-resolves host names once per complete traversal of the server list, following reconnect
    /// backoff delay.
    #[default]
    EachPass,
    /// Re-resolves host names once the specified time interval has elapsed since the last
    /// successful lookup.
    Interval(Duration),
    /// Performs DNS resolution only upon client initialization and explicit calls.
    Never,
}

impl DnsRefresh {
    /// Translates refresh policy into protocol resolution cadence and legacy delay representation.
    fn proto(self) -> (ResolveCadence, i32) {
        match self {
            Self::EachPass => (ResolveCadence::EachPass, -1),
            Self::Interval(d) => (
                ResolveCadence::Throttled { ms: millis_u32(d) },
                i32::try_from(d.as_millis()).unwrap_or(i32::MAX),
            ),
            Self::Never => (ResolveCadence::Never, -1),
        }
    }
}

/// Custom DNS host name resolution provider.
///
/// Implementations must be thread safe. Resolution calls are synchronous and permitted to block.
/// The thread driver runs lookups on a dedicated DNS thread with no driver lock held. If that
/// thread is not available, the lookup runs on the I/O thread with only the I/O owner lock held.
/// The Tokio driver runs lookups through `spawn_blocking`.
/// Panics are caught and treated as resolution failures.
pub trait Resolve: Send + Sync + 'static {
    /// Resolves the specified host name and port into a list of target socket addresses, arranged
    /// in preferred connection order.
    ///
    /// # Errors
    ///
    /// Returns an I/O error if DNS lookup fails, prompting the client to bypass the host for this
    /// connection attempt.
    fn resolve(&self, host: &str, port: u16) -> std::io::Result<Vec<SocketAddr>>;
}

/// Execution runtime environment hosting the client's background I/O network loop.
#[derive(Clone, Debug)]
#[non_exhaustive]
pub enum Runtime {
    /// Spawns a dedicated background OS thread per client instance. Compatible with any
    /// application runtime.
    Thread(ThreadOptions),
    /// Drives the client via an asynchronous task on the specified Tokio runtime (Unix
    /// platforms only).
    #[cfg(all(unix, feature = "tokio"))]
    Tokio(tokio::runtime::Handle),
}

/// Configuration tuning parameters for dedicated background I/O threads.
#[derive(Clone, Debug, Default)]
pub struct ThreadOptions {
    /// Optional thread name assigned to the I/O worker thread. Must not contain NULL bytes.
    pub name: Option<String>,
    /// Optional stack size override in bytes. Defaults to the platform's standard library thread
    /// stack size.
    pub stack_size: Option<usize>,
}

/// Fluent configuration builder constructing a [`Client`] instance.
#[must_use = "a builder does nothing until connect() or build() is called"]
pub struct ClientBuilder {
    pub(crate) connect_string: String,
    pub(crate) session_timeout: Duration,
    /// Chroot path prefix override, validated during builder construction or final build step.
    pub(crate) chroot: Option<Result<ZPathBuf, PathError>>,
    pub(crate) allow_read_only: bool,
    pub(crate) session: Option<(SessionId, SessionPassword)>,
    /// Authentication credentials registered during builder configuration, preserved in insertion
    /// order.
    pub(crate) auth: Vec<(String, SecretBytes)>,
    pub(crate) restore_watches: bool,
    /// Per-attempt TCP connection timeout; defaults to one-third of the negotiated session
    /// timeout.
    pub(crate) connect_timeout: Option<Duration>,
    pub(crate) reconnect_delay: Duration,
    pub(crate) server_order: ServerOrder,
    pub(crate) dns_refresh: DnsRefresh,
    /// Optional custom host name resolver implementation; defaults to standard library
    /// resolution.
    pub(crate) resolver: Option<Arc<dyn Resolve>>,
    /// Configured execution runtime strategy; defaults to ambient multithreaded Tokio or
    /// dedicated thread.
    pub(crate) runtime: Option<Runtime>,
    pub(crate) update_servers_from_config: bool,
    pub(crate) max_pending: Option<usize>,
    pub(crate) max_frame_size: u32,
    pub(crate) persistent_watch_buffer: Option<usize>,
    pub(crate) dispatch_queue_capacity: usize,
    pub(crate) close_timeout: Duration,
    #[cfg(feature = "tls-rustls")]
    pub(crate) tls: Option<TlsOptions>,
    /// Native SASL authentication configuration.
    #[cfg(feature = "sasl")]
    pub(crate) sasl: Option<SaslConfig>,
    pub(crate) metrics: Option<Arc<dyn Metrics>>,
    pub(crate) name: Option<String>,
}

impl std::fmt::Debug for ClientBuilder {
    /// Formats configuration parameters for diagnostics while redacting sensitive authentication
    /// credentials and passwords.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut d = f.debug_struct("ClientBuilder");
        d.field("connect_string", &self.connect_string)
            .field("session_timeout", &self.session_timeout)
            .field("auth_entries", &self.auth.len())
            .field("runtime", &self.runtime)
            .field("name", &self.name);
        // The `SaslConfig` text redacts the password.
        #[cfg(feature = "sasl")]
        d.field("sasl", &self.sasl);
        d.finish_non_exhaustive()
    }
}

impl ClientBuilder {
    /// Instantiates a new builder with default parameters, deferring connection string parsing
    /// until build time.
    pub(crate) fn new(connect_string: String) -> Self {
        Self {
            connect_string,
            session_timeout: DEFAULT_SESSION_TIMEOUT,
            chroot: None,
            allow_read_only: false,
            session: None,
            auth: Vec::new(),
            restore_watches: true,
            connect_timeout: None,
            reconnect_delay: DEFAULT_RECONNECT_DELAY,
            server_order: ServerOrder::Shuffled,
            dns_refresh: DnsRefresh::EachPass,
            resolver: None,
            runtime: None,
            update_servers_from_config: false,
            max_pending: None,
            max_frame_size: DEFAULT_MAX_FRAME_SIZE,
            persistent_watch_buffer: None,
            dispatch_queue_capacity: DEFAULT_DISPATCH_QUEUE_CAPACITY,
            close_timeout: DEFAULT_CLOSE_TIMEOUT,
            #[cfg(feature = "tls-rustls")]
            tls: None,
            #[cfg(feature = "sasl")]
            sasl: None,
            metrics: None,
            name: None,
        }
    }

    /// Configures the requested session heartbeat timeout (default is 30 seconds).
    ///
    /// The final negotiated timeout is constrained by server side tick limits.
    /// Must be at least 1 millisecond.
    pub fn session_timeout(mut self, t: Duration) -> Self {
        self.session_timeout = t;
        self
    }

    /// Configures an explicit chroot path prefix, overriding any chroot path included in the
    /// connection string.
    pub fn chroot(mut self, chroot: impl PathArg) -> Self {
        self.chroot = Some(chroot.to_zpath().map(|p| p.to_buf()));
        self
    }

    /// Configures whether the client permits connections to read-only ensemble replicas
    /// Defaults to false.
    pub fn allow_read_only(mut self, yes: bool) -> Self {
        self.allow_read_only = yes;
        self
    }

    /// Configures the client to resume an existing session using previously saved session
    /// credentials.
    pub fn session(mut self, id: SessionId, password: SessionPassword) -> Self {
        self.session = Some((id, password));
        self
    }

    /// Appends an authentication credential packet dispatched immediately upon establishing each
    /// network connection.
    pub fn auth(mut self, scheme: impl Into<String>, data: impl Into<Vec<u8>>) -> Self {
        self.auth
            .push((scheme.into(), SecretBytes::new(data.into())));
        self
    }

    /// Configures whether active client side watchers are automatically re-registered upon
    /// reconnect. Defaults to true.
    pub fn restore_watches(mut self, yes: bool) -> Self {
        self.restore_watches = yes;
        self
    }

    /// Configures an upper bound on individual TCP connection attempts. Defaults to one-third of
    /// session timeout.
    pub fn connect_timeout(mut self, t: Duration) -> Self {
        self.connect_timeout = Some(t);
        self
    }

    /// Sets the base backoff delay applied after completing a full traversal of the ensemble
    /// server list. Defaults to 1s.
    pub fn reconnect_delay(mut self, t: Duration) -> Self {
        self.reconnect_delay = t;
        self
    }

    /// Configures server ordering policy when traversing the ensemble host list. Ddefaults to
    /// [`ServerOrder::Shuffled`].
    pub fn server_order(mut self, o: ServerOrder) -> Self {
        self.server_order = o;
        self
    }

    /// Configures the DNS reresolution cadence policy. Defaults to [`DnsRefresh::EachPass`].
    pub fn dns_refresh(mut self, p: DnsRefresh) -> Self {
        self.dns_refresh = p;
        self
    }

    /// Configures a custom DNS host name resolver. Defaults to standard library resolution.
    pub fn resolver(mut self, r: impl Resolve) -> Self {
        self.resolver = Some(Arc::new(r));
        self
    }

    /// Configures the background execution runtime hosting the driver network loop.
    pub fn runtime(mut self, rt: Runtime) -> Self {
        self.runtime = Some(rt);
        self
    }

    /// Enables automatic background tracking of dynamic cluster configuration changes.
    pub fn update_servers_from_config(mut self, yes: bool) -> Self {
        self.update_servers_from_config = yes;
        self
    }

    /// Configures a backpressure limit on total concurrent queued and in-flight requests.
    /// Defaults to unbounded.
    pub fn max_pending_requests(mut self, n: usize) -> Self {
        self.max_pending = Some(n);
        self
    }

    /// Configures the maximum allowed size in bytes for inbound protocol frames. Defaults to
    /// 4 MiB.
    pub fn max_frame_size(mut self, bytes: u32) -> Self {
        self.max_frame_size = bytes;
        self
    }

    /// Configures the maximum event buffer capacity for persistent watcher event queues.
    /// Defaults to unbounded.
    pub fn persistent_watch_buffer(mut self, events: usize) -> Self {
        self.persistent_watch_buffer = Some(events);
        self
    }

    /// Configures the capacity of the internal driver read gate buffer. Defaults to 4096.
    pub fn dispatch_queue_capacity(mut self, items: usize) -> Self {
        self.dispatch_queue_capacity = items;
        self
    }

    /// Configures the maximum timeout duration to await server acknowledgement of session close
    /// requests. Defaults to 3s.
    pub fn close_timeout(mut self, t: Duration) -> Self {
        self.close_timeout = t;
        self
    }

    /// Configures TLS transport encryption options.
    #[cfg(feature = "tls-rustls")]
    pub fn tls(mut self, opts: TlsOptions) -> Self {
        self.tls = Some(opts);
        self
    }

    /// Configures native SASL authentication mechanism and credentials.
    ///
    /// SASL authentication executes immediately following initial session establishment and prior
    /// to transitioning the client into the connected state. SASL negotiation errors are fatal.
    #[cfg(feature = "sasl")]
    pub fn sasl(mut self, cfg: SaslConfig) -> Self {
        self.sasl = Some(cfg);
        self
    }

    /// Configures a custom metrics collection collector for client telemetry.
    pub fn metrics(mut self, m: impl Metrics) -> Self {
        self.metrics = Some(Arc::new(m));
        self
    }

    /// Sets a descriptive diagnostic label applied to logging and metrics emitted by this client.
    pub fn name(mut self, label: impl Into<String>) -> Self {
        self.name = Some(label.into());
        self
    }

    /// Builds the client instance and returns a future that resolves once the initial connection
    /// and credentials succeed.
    ///
    /// The returned future resolves once the connection transitions to `Connected` (or
    /// `ConnectedReadOnly` if permitted) and all pre-configured authentication credentials are
    /// confirmed. If the server rejects credentials, yields [`Error::AuthFailed`].
    pub fn connect(self) -> ConnectFuture {
        match self.start() {
            Ok((client, auth)) => {
                let watcher = client.state_watcher();
                ConnectFuture {
                    client: Some(client),
                    error: None,
                    watcher,
                    auth,
                    state_key: None,
                }
            }
            Err(e) => ConnectFuture {
                client: None,
                error: Some(e),
                watcher: StateWatcher::new(StateCell::new()),
                auth: Vec::new(),
                state_key: None,
            },
        }
    }

    /// Builds and starts the client immediately without waiting for initial network connection
    /// establishment.
    ///
    /// Validates connection parameters, initializes the background driver loop, and enqueues
    /// initial authentication packets. Requests submitted against the returned client buffer in
    /// memory until a connection is established.
    ///
    /// # Errors
    ///
    /// Returns [`ConnectError`] wrapping [`Error::InvalidArgument`] or [`Error::InvalidPath`] if
    /// configuration options fail validation, or [`Error::SystemError`] if operating system
    /// driver spawning fails.
    pub fn build(self) -> Result<Client, ConnectError> {
        // Authentication confirmation futures are discarded here.
        // Credentials remain enqueued in the protocol state machine.
        self.start().map(|(client, _auth)| client)
    }

    /// Initializes driver infrastructure, returning the client handle alongside authentication
    /// dispatch futures.
    fn start(self) -> Result<(Client, Vec<ResponseFuture<()>>), ConnectError> {
        let parsed = connect_string::parse(&self.connect_string)?;
        let chroot = match self.chroot {
            None => parsed.chroot,
            Some(Err(e)) => return Err(Error::InvalidPath(e).into()),
            Some(Ok(p)) if p.as_path().is_root() => None,
            Some(Ok(p)) => Some(p.into_string()),
        };
        let (timeout_ms, connect_timeout) =
            checked_timeouts(self.session_timeout, self.connect_timeout)?;

        let mut compat = Compat::NATIVE;
        compat.max_frame_len = Some(self.max_frame_size);
        let (cadence, resolve_delay_ms) = self.dns_refresh.proto();
        compat.resolve_cadence = cadence;

        let id = shared::next_client_id();
        let observe = Arc::new(Observer::new(self.name, self.metrics));
        // The runtime execution flavor must be determined prior to allocating the state cell to
        // configure blocking guards.
        let runtime = self.runtime.unwrap_or_else(driver::default_runtime);
        #[cfg(all(unix, feature = "tokio"))]
        let follow_on = match &runtime {
            Runtime::Tokio(h) => Some(h.clone()),
            Runtime::Thread(_) => None,
        };
        #[cfg(all(unix, feature = "tokio"))]
        let state = StateCell::for_driver(follow_on.is_some());
        #[cfg(not(all(unix, feature = "tokio")))]
        let state = StateCell::new();

        #[cfg(feature = "tls-rustls")]
        let tls = match &self.tls {
            Some(opts) => {
                opts.check().map_err(|e| {
                    ConnectError::new(
                        Error::InvalidArgument("the TLS options are not valid".into()),
                        Some(Arc::new(e)),
                    )
                })?;
                compat.tls_verify = opts.tls_verify();
                Some((opts.connector(), opts.error_probe()))
            }
            None => None,
        };
        #[cfg(not(feature = "tls-rustls"))]
        let tls: Option<(Arc<dyn TlsConnector>, TlsErrorSource)> = None;
        let (connector, probe) = tls.unzip();

        #[cfg(feature = "sasl")]
        let (sasl, probe) = match self.sasl {
            Some(config) => {
                let last = LastSaslError::default();
                let session: Box<dyn zk_proto::SaslSession> =
                    Box::new(NativeSasl::new(config, Arc::clone(&last)));
                (Some(session), Some(with_sasl_error(probe, last)))
            }
            None => (None, probe),
        };
        #[cfg(not(feature = "sasl"))]
        let sasl: Option<Box<dyn zk_proto::SaslSession>> = None;

        let router = Router::new(Arc::clone(&state), Arc::clone(&observe), probe, id);
        let resolver = self.resolver.unwrap_or_else(|| Arc::new(StdResolve));
        let host: Arc<dyn HostLeaves<NativeTokens>> = Arc::new(NativeHost::new(
            resolver,
            self.server_order,
            Arc::clone(&observe),
            id,
        ));

        let mut cfg = Config::legacy(timeout_ms);
        cfg.client_id = self
            .session
            .as_ref()
            .map_or_else(ClientId::default, |(sid, pw)| ClientId {
                session_id: sid.0,
                passwd: *pw.as_bytes(),
            });
        cfg.allow_read_only = self.allow_read_only;
        cfg.tls = connector.is_some();
        cfg.sasl = sasl.is_some();
        cfg.hosts = parsed.hosts;
        cfg.host_text = JString::from(parsed.host_text);
        cfg.chroot = chroot.map(JString::from);
        cfg.resolve_delay_ms = resolve_delay_ms;
        cfg.restore_watches = self.restore_watches;
        cfg.max_pending = self.max_pending;
        cfg.connect_timeout_ms = Some(millis_u32(connect_timeout));
        cfg.reconnect_delay_ms = millis_u32(self.reconnect_delay);
        cfg.reconnect_jitter_pct = RECONNECT_JITTER_PCT;
        cfg.close_timeout_ms = millis_u32(self.close_timeout);

        let mut env = Env::new(Arc::clone(&host));
        env.tls = connector;
        env.cfg.dispatch_queue_capacity = self.dispatch_queue_capacity;
        env.cfg.cloexec = true;
        let now = env.clock.now();
        let (rng, log, default_watcher) = zk_driver::host::connection_hooks(&host);
        let conn = Box::new(Connection::new(
            cfg,
            compat,
            DriverMode::NATIVE,
            rng,
            log,
            default_watcher,
            now,
        ));

        let driver = driver::start(conn, env, Arc::clone(&router), Some(runtime), sasl, id)?;
        // The Tokio driver does not support explicit thread exit hooks.
        // A companion task notifies the router upon task exit.
        #[cfg(all(unix, feature = "tokio"))]
        if let (Some(rt), driver::DriverHandle::Tokio(task)) = (follow_on, &driver) {
            follow_exit(&rt, task.clone(), Arc::clone(&router));
        }
        let config = Arc::new(ClientConfig {
            max_pending: self.max_pending,
            persistent_watch_buffer: self.persistent_watch_buffer,
        });
        let shared = Shared::new(driver, router, config);
        let auth = self
            .auth
            .into_iter()
            .map(|(scheme, cred)| shared.add_auth(JString::from(scheme), cred))
            .collect();
        // Config change follower requests are enqueued after authentication packets to ensure
        // proper ACL evaluation.
        if self.update_servers_from_config {
            follow::read(&shared);
        }
        Ok((Client::from_shared(shared), auth))
    }
}

/// Spawns a companion task monitoring Tokio driver termination to transition routing sinks to
/// final closed state.
///
/// Installs an RAII drop guard that guarantees [`Router::on_exit`] is executed even if the Tokio
/// runtime shuts down abruptly, preventing dependent tasks on other threads from deadlocking.
/// Subsequent invocations of `on_exit` are idempotent.
#[cfg(all(unix, feature = "tokio"))]
fn follow_exit(
    rt: &tokio::runtime::Handle,
    task: zk_driver::tokio::TaskHandle<NativeTokens>,
    router: Arc<Router>,
) {
    let guard = ExitOnDrop(router);
    drop(rt.spawn(async move {
        let _guard = guard;
        let _rc = task.closed().await;
    }));
}

/// RAII drop guard executing [`Router::on_exit`] when dropped.
#[cfg(all(unix, feature = "tokio"))]
struct ExitOnDrop(Arc<Router>);

#[cfg(all(unix, feature = "tokio"))]
impl Drop for ExitOnDrop {
    fn drop(&mut self) {
        self.0.on_exit();
    }
}

/// Validates and converts configured session and connect timeout parameters into protocol
/// representations.
fn checked_timeouts(
    session_timeout: Duration,
    connect_timeout: Option<Duration>,
) -> Result<(i32, Duration), Error> {
    let timeout_ms = i32::try_from(session_timeout.as_millis()).map_err(|_| {
        Error::InvalidArgument("the session timeout does not fit an i32 of milliseconds".into())
    })?;
    // Zero-duration timeout bounds would expire immediately, permanently preventing connection
    // establishment.
    if timeout_ms == 0 {
        return Err(Error::InvalidArgument(
            "the session timeout is below 1 ms".into(),
        ));
    }
    let connect_timeout =
        connect_timeout.unwrap_or_else(|| session_timeout.checked_div(3).unwrap_or_default());
    if connect_timeout.as_millis() == 0 {
        return Err(Error::InvalidArgument(
            "the connect timeout is below 1 ms (the default is a third of the session timeout)"
                .into(),
        ));
    }
    Ok((timeout_ms, connect_timeout))
}

/// Chains native SASL negotiation errors into the router's contextual error inspection probe.
#[cfg(feature = "sasl")]
fn with_sasl_error(tls: Option<TlsErrorSource>, sasl: LastSaslError) -> TlsErrorSource {
    Arc::new(move || {
        if let Some(e) = tls.as_ref().and_then(|f| f()) {
            return Some(e);
        }
        let e = sasl.lock_unpoisoned().clone()?;
        let e: crate::error::LastError = Arc::new(e);
        Some(e)
    })
}

/// Converts a duration into whole milliseconds, saturating at `u32::MAX`.
fn millis_u32(d: Duration) -> u32 {
    u32::try_from(d.as_millis()).unwrap_or(u32::MAX)
}

/// Asynchronous completion future awaiting initial session connection establishment and credential
/// verification.
///
/// Owns the active client instance during connection establishment; dropping this future prior to
/// completion closes the client.
#[must_use = "futures do nothing unless polled"]
pub struct ConnectFuture {
    /// Active client instance, retained until successful completion or terminal failure.
    pub(crate) client: Option<Client>,
    /// Deferred initialization error returned upon first poll.
    pub(crate) error: Option<ConnectError>,
    /// State watcher tracking connection status changes.
    pub(crate) watcher: StateWatcher,
    /// Pending futures tracking verification of configured authentication credentials.
    pub(crate) auth: Vec<ResponseFuture<()>>,
    /// Registered registration key inside the state cell condition variable.
    pub(crate) state_key: Option<u64>,
}

impl Drop for ConnectFuture {
    /// Deregisters task waker from the underlying state cell.
    fn drop(&mut self) {
        self.watcher.forget(&mut self.state_key);
    }
}

impl std::fmt::Debug for ConnectFuture {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConnectFuture")
            .field("state", &self.watcher.current().state)
            .finish_non_exhaustive()
    }
}

impl ConnectFuture {
    /// Returns a state watcher monitoring connection progress and surfacing diagnostic error
    /// details while pending.
    #[must_use]
    pub fn state_watcher(&self) -> StateWatcher {
        self.watcher.clone()
    }

    /// Synchronously blocks the calling thread until initial connection establishment completes.
    ///
    /// # Errors
    ///
    /// Returns connection errors encountered during handshake or credential negotiation. Returns
    /// a [`ConnectError`] that holds [`Error::InvalidState`] at once on the I/O thread of a client.
    /// Also returns it in any Tokio runtime context, `spawn_blocking` included, when the client
    /// uses the Tokio driver. In that context, use `Handle::block_on` instead.
    #[cfg(feature = "blocking")]
    pub fn wait(mut self) -> Result<Client, ConnectError> {
        if blocking_refused(self.watcher.tokio_driver()) {
            return Err(Error::InvalidState.into());
        }
        if let Some(e) = self.error.take() {
            return Err(e);
        }
        'connect: loop {
            loop {
                let info = self.watcher.current();
                if info.state.is_terminal() {
                    return Err(self.terminal(info));
                }
                if info.state.is_connected() {
                    break;
                }
                self.watcher.wait_changed()?;
            }
            while !self.auth.is_empty() {
                let f = self.auth.remove(0);
                match f.wait() {
                    Ok(()) => {}
                    Err(e) if e.is_connection_loss() => {
                        self.after_auth_loss();
                        continue 'connect;
                    }
                    Err(e) => return Err(ConnectError::new(e, self.watcher.last_error())),
                }
            }
            return self.client.take().ok_or_else(|| Error::Closing.into());
        }
    }

    /// Reevaluates authentication state following connection loss during initial handshake.
    ///
    /// Incomplete authentication requests fail upon disconnection, but credentials remain
    /// registered in the protocol core. Dispatches a `sync` barrier to confirm that the server
    /// successfully reauthenticated the session upon reconnection.
    fn after_auth_loss(&mut self) {
        self.auth.clear();
        if let Some(c) = &self.client {
            self.auth.push(c.sync("/"));
        }
    }
}

impl Future for ConnectFuture {
    type Output = Result<Client, ConnectError>;

    /// Polls for connection establishment and subsequent authentication credential confirmation.
    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let this = self.get_mut();
        if let Some(e) = this.error.take() {
            return Poll::Ready(Err(e));
        }
        if this.client.is_none() {
            // Future has already yielded its terminal outcome.
            return Poll::Ready(Err(Error::Closing.into()));
        }
        'connect: loop {
            // Await establishment of a connected session state.
            loop {
                let info = this.watcher.current();
                if info.state.is_terminal() {
                    return Poll::Ready(Err(this.terminal(info)));
                }
                if info.state.is_connected() {
                    break;
                }
                match this.watcher.poll_keyed(&mut this.state_key, cx) {
                    Poll::Ready(_) => continue,
                    Poll::Pending => return Poll::Pending,
                }
            }
            this.watcher.forget(&mut this.state_key);
            // Verify and drain configured authentication credentials in FIFO sequence.
            while let Some(f) = this.auth.first_mut() {
                match Pin::new(f).poll(cx) {
                    Poll::Ready(Ok(())) => {
                        drop(this.auth.remove(0));
                    }
                    Poll::Ready(Err(e)) if e.is_connection_loss() => {
                        this.after_auth_loss();
                        continue 'connect;
                    }
                    Poll::Ready(Err(e)) => {
                        let cause = this.watcher.last_error();
                        this.client = None;
                        return Poll::Ready(Err(ConnectError::new(e, cause)));
                    }
                    Poll::Pending => return Poll::Pending,
                }
            }
            return Poll::Ready(this.client.take().ok_or_else(|| Error::Closing.into()));
        }
    }
}

impl ConnectFuture {
    /// Constructs a terminal connection error from session status and releases the client handle
    /// to terminate background drivers.
    fn terminal(&mut self, info: SessionInfo) -> ConnectError {
        let error = self
            .client
            .as_ref()
            .and_then(|c| c.shared().terminal_error())
            .unwrap_or(match info.state {
                SessionState::Expired => Error::SessionExpired,
                SessionState::AuthFailed => Error::AuthFailed,
                _ => Error::Closing,
            });
        self.client = None;
        ConnectError::new(error, info.last_error)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_defaults_follow_04() {
        let b = ClientBuilder::new("h:2181".into());
        assert_eq!(b.session_timeout, Duration::from_secs(30));
        assert!(b.restore_watches);
        assert_eq!(b.max_frame_size, 4 * 1024 * 1024);
        assert_eq!(b.dispatch_queue_capacity, 4096);
        assert_eq!(b.server_order, ServerOrder::Shuffled);
        assert!(b.chroot.is_none());
    }

    #[test]
    fn millis_saturate_at_u32_max() {
        assert_eq!(millis_u32(Duration::from_millis(1500)), 1500);
        assert_eq!(millis_u32(Duration::from_secs(u64::MAX)), u32::MAX);
    }

    #[test]
    fn a_build_error_comes_at_the_first_poll() {
        let mut f = ConnectFuture {
            client: None,
            error: Some(Error::BadArguments.into()),
            watcher: StateWatcher::new(StateCell::new()),
            auth: Vec::new(),
            state_key: None,
        };
        let mut cx = Context::from_waker(std::task::Waker::noop());
        match Pin::new(&mut f).poll(&mut cx) {
            Poll::Ready(Err(e)) => assert_eq!(e.error(), &Error::BadArguments),
            other => panic!("unexpected poll result: {other:?}"),
        }
        // Subsequent poll invocations on resolved futures return Error::Closing without altering
        // client state.
        match Pin::new(&mut f).poll(&mut cx) {
            Poll::Ready(Err(e)) => assert_eq!(e.error(), &Error::Closing),
            other => panic!("unexpected poll result: {other:?}"),
        }
    }

    #[test]
    fn a_bad_chroot_is_kept_for_build() {
        let b = ClientBuilder::new("h".into()).chroot("no-slash");
        assert!(matches!(b.chroot, Some(Err(_))));
    }

    #[test]
    fn debug_hides_credentials() {
        let b = ClientBuilder::new("h".into()).auth("digest", "u:secret");
        assert!(!format!("{b:?}").contains("secret"));
    }
}
