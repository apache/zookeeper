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

//! Host boundary contracts and platform adaptation hooks.
//!
//! The [`HostLeaves`] trait defines the abstract platform services required by the driver layer,
//! including host name resolution, random number generation, logging filters, and token cleanup.
//! In legacy C drop-in drivers, `zk-ffi` implements [`HostLeaves`] on top of the host virtual
//! function table. In native Rust environments, drivers employ the client resolver adapter or
//! [`crate::host_std::StdHostLeaves`].
//!
//! Service classification and concurrency guarantees:
//! - Pure operations never block and never invoke foreign application callbacks.
//! - Blocking operations (such as host resolution and PRNG reseeding) accept an [`Unlocked`]
//!   witness token, guaranteeing that neither the `core` state lock nor any leaf lock is held
//!   along the active call chain, although the outer `io` runner lock may remain held.
//! - Neither category of leaf service enters application code or invokes log dispatch callbacks
//!   directly.

use std::sync::Arc;

use zk_proto::{
    ClientId, Connection, DefaultWatcher, HostResolution, HostSpec, LogArg, LogFilter, LogLevel,
    LogRecord, LogSite, Output, Rng, Tokens,
};

use crate::sync::Unlocked;

/// Thread-safety and lifecycle bounds required for all token types managed across driver threads.
///
/// Because driver runtimes transfer completion, watcher, and authentication tokens across thread
/// boundaries, each token type must satisfy `Send`. This blanket trait implementation applies to
/// any [`Tokens`] bundle meeting these concurrency requirements.
pub trait DriverTokens:
    Tokens<Completion: Send, Watcher: Send, Auth: Send> + Send + Sync + 'static
{
}

impl<T> DriverTokens for T
where
    T: Tokens + Send + Sync + 'static,
    T::Completion: Send,
    T::Watcher: Send,
    T::Auth: Send,
{
}

/// Token payload surrendered by the driver without dispatching a user-facing completion callback.
///
/// During connection teardown or session aborts, `zk-ffi` transitions synchronized waiter tokens
/// into the `ZCLOSING` state upon discard so that pending client threads do not deadlock.
#[derive(Debug)]
pub enum Discarded<T: Tokens> {
    /// Discarded asynchronous operation completion token.
    Completion(T::Completion),
    /// Discarded watcher registration token.
    Watcher(T::Watcher),
    /// Discarded authentication operation completion token.
    Auth(T::Auth),
}

/// Platform level host integration services required by a driver instance.
pub trait HostLeaves<T: Tokens>: Send + Sync {
    /// Pure hook releasing a discarded token without triggering user callbacks.
    fn discard_token(&self, token: Discarded<T>);

    /// Pure hook obtaining an atomic snapshot of the default session watcher token, if configured.
    fn snapshot_default_watcher(&self) -> Option<T::Watcher>;

    /// Pure hook recording client session identity credentials negotiated during protocol
    /// handshake.
    fn store_client_id(&self, id: &ClientId);

    /// Pure predicate querying whether logging diagnostics at the specified severity level are
    /// enabled.
    fn log_enabled(&self, level: LogLevel) -> bool;

    /// Pure predicate indicating whether server address permutation is disabled.
    fn conn_permute_disabled(&self) -> bool;

    /// Pure pseudo-random generator returning values conforming to libc `random()`.
    fn rng_random(&self) -> i64;

    /// Pure floating-point pseudo-random generator returning uniform values conforming to libc
    /// `drand48()`.
    fn rng_drand48(&self) -> f64;

    /// Blocking hook reseeding the host pseudo-random number generator, replicating C
    /// `setup_random` behavior.
    ///
    /// Invoked prior to `handle_resolved` when server shuffling is enabled and at least one
    /// address resolved.
    fn rng_reseed(&self, proof: &Unlocked);

    /// Blocking DNS resolution hook resolving a single server host specification.
    ///
    /// Emits no logs directly. Resolution diagnostics are recorded downstream by the protocol
    /// core. The `host` argument represents the entry index within the configured server list.
    fn resolve(&self, proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution;
}

/// Adapter projecting host leaf PRNG operations into the protocol core's [`Rng`] trait.
///
/// The `on_resolution` callback is a no-op here because the driver explicitly invokes
/// [`HostLeaves::rng_reseed`] outside the `core` lock prior to applying DNS resolution results.
pub struct HostRng<T: Tokens> {
    host: Arc<dyn HostLeaves<T>>,
}

impl<T: Tokens> HostRng<T> {
    /// Wraps the provided host leaves in an RNG adapter.
    #[must_use]
    pub fn new(host: Arc<dyn HostLeaves<T>>) -> Self {
        Self { host }
    }
}

impl<T: Tokens> Rng for HostRng<T> {
    fn on_resolution(&mut self) {}

    fn random(&mut self) -> i64 {
        self.host.rng_random()
    }

    fn drand48(&mut self) -> f64 {
        self.host.rng_drand48()
    }
}

/// Adapter projecting host log verbosity checks into the core [`LogFilter`] trait.
pub struct HostLogFilter<T: Tokens> {
    host: Arc<dyn HostLeaves<T>>,
}

impl<T: Tokens> HostLogFilter<T> {
    /// Wraps the provided host leaves in a log filter adapter.
    #[must_use]
    pub fn new(host: Arc<dyn HostLeaves<T>>) -> Self {
        Self { host }
    }
}

impl<T: Tokens> LogFilter for HostLogFilter<T> {
    fn enabled(&self, level: LogLevel) -> bool {
        self.host.log_enabled(level)
    }
}

/// Adapter projecting host default watcher retrieval into the core [`DefaultWatcher`] trait.
pub struct HostDefaultWatcher<T: Tokens> {
    host: Arc<dyn HostLeaves<T>>,
}

impl<T: Tokens> HostDefaultWatcher<T> {
    /// Wraps the provided host leaves in a default watcher snapshot adapter.
    #[must_use]
    pub fn new(host: Arc<dyn HostLeaves<T>>) -> Self {
        Self { host }
    }
}

impl<T: Tokens> DefaultWatcher<T::Watcher> for HostDefaultWatcher<T> {
    fn snapshot(&self) -> Option<T::Watcher> {
        self.host.snapshot_default_watcher()
    }
}

/// Hook bundle supplying RNG, log filtering, and default watcher services for `Connection::new`.
pub type ConnectionHooks<T> = (
    Box<dyn Rng>,
    Box<dyn LogFilter>,
    Box<dyn DefaultWatcher<<T as Tokens>::Watcher>>,
);

/// Instantiates core protocol connection hooks backed by host leaf implementations.
#[must_use]
pub fn connection_hooks<T: DriverTokens>(host: &Arc<dyn HostLeaves<T>>) -> ConnectionHooks<T> {
    (
        Box::new(HostRng::new(Arc::clone(host))),
        Box::new(HostLogFilter::new(Arc::clone(host))),
        Box::new(HostDefaultWatcher::new(Arc::clone(host))),
    )
}

/// Constructs a diagnostic log record if the host configuration permits the record's log level.
///
/// Drivers emit diagnostics through structured core log records tagged with site locations.
pub fn log_record<T: Tokens>(
    host: &dyn HostLeaves<T>,
    site: LogSite,
    args: Vec<LogArg>,
) -> Option<LogRecord> {
    let level = site.level();
    host.log_enabled(level)
        .then_some(LogRecord { site, level, args })
}

/// Traverses all remaining pending tokens in a terminating connection and delivers them to
/// `discard_token`.
///
/// Replicates legacy C cleanup semantics by processing pending completions, followed by
/// authentication tokens, and lastly undrained output notifications.
pub(crate) fn discard_leftovers<T: Tokens>(host: &dyn HostLeaves<T>, conn: Box<Connection<T>>) {
    let left = conn.into_leftovers();
    for token in left.completions {
        host.discard_token(Discarded::Completion(token));
    }
    for token in left.auth {
        host.discard_token(Discarded::Auth(token));
    }
    for output in left.outputs {
        match output {
            Output::Completion { token, .. } => host.discard_token(Discarded::Completion(token)),
            Output::Auth { tokens, .. } => {
                for token in tokens {
                    host.discard_token(Discarded::Auth(token));
                }
            }
            Output::Watch { watchers, .. }
            | Output::Session { watchers, .. }
            | Output::WatchTerminated { watchers, .. } => {
                for w in watchers {
                    host.discard_token(Discarded::Watcher(w));
                }
            }
            Output::AuthLockpoint | Output::ClientId(_) | Output::Log(_) | Output::DrainPoint => {}
        }
    }
}
