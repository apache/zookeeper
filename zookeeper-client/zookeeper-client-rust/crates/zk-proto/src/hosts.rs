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

//! Server host specification, asynchronous DNS resolution, address rotation, and read-write
//! probing.
//!
//! Owns [`Hosts`]. The state machine delegates DNS resolution to external driver loops via
//! `Action::Resolve`. Upon receiving resolved socket addresses, the core commits updated endpoints,
//! executes legacy or native rebalancing and randomization rules, and rotates through candidate
//! endpoints.

use std::net::SocketAddr;

use zk_jute::JString;
use zk_jute::rc;

use crate::action::{Action, HostResolution, ProbeOutcome, ProbeTlsFailure, TlsResourceAt};
use crate::compat::{Backoff, DnsFailure, ResolveCadence};
use crate::config::{Endpoint, HostSpec, HostToken};
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::interest::IterStep;
use crate::legacy::addrvec::{AddrVec, ListEq, shuffle as shuffle_list};
use crate::legacy::servers::{ServerSet, UpdateOutcome};
use crate::log::{LogArg, LogSite};
use crate::state::{Cause, State};
use crate::time::{Millis, elapsed};
use crate::timers::RW_PROBE_MIN_MS;

/// Growth chunk increment matching the legacy C address vector (`ADDRVEC_DEFAULT_GROW_AMOUNT`).
const ADDRVEC_GROW: usize = 16;

/// Maximum exponent bit-shift applied during exponential reconnection backoff (`2^16` factor).
const BACKOFF_MAX_SHIFT: u32 = 16;

/// Hard ceiling in milliseconds applied to exponential reconnection delays prior to jitter
/// calculation.
pub(crate) const BACKOFF_CAP_MS: i64 = 30_000;

/// Connect disposition evaluated during an interest loop iteration.
#[derive(Debug)]
pub(crate) enum ConnectDecision {
    /// Proceed with socket establishment using the enclosed `Action::Connect`.
    Connect(Action),
    /// Reconnection delay or backoff timer is active; interest iteration should wait.
    Wait,
    /// Unresolved host list or DNS error barrier temporarily blocks socket connection.
    Blocked,
    /// Empty host list or terminal socket creation failure encountered.
    Failed,
}

/// Initiating context for an asynchronous DNS resolution cycle.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
enum ResolveOrigin {
    /// Initial DNS resolution performed on connection startup.
    #[default]
    Init,
    /// Periodic or cadence-driven DNS re-resolution during connection maintenance.
    Iteration,
    /// Explicit server ensemble update initiated via `set_servers`.
    SetServers,
}

/// Internal state machine governing server endpoint lists, address rotation, and DNS resolution
/// lifecycles.
pub(crate) struct Hosts {
    /// Configured ensemble host specifications in original connection string order.
    pub(crate) specs: Vec<HostSpec>,
    /// Active, standby, and reconfiguring server endpoint sets with rotation cursors.
    pub(crate) servers: ServerSet<Endpoint>,
    /// Generation identifier of the latest resolution cycle; responses from superseded generations
    /// are ignored.
    pub(crate) generation: u64,
    /// Indicates whether a DNS resolution cycle is due for execution.
    pub(crate) resolve_due: bool,
    /// Indicates that the initial resolution cycle has not yet been dispatched.
    pub(crate) initial: bool,
    /// Generation identifier currently in-flight across the driver, if any.
    pub(crate) in_flight: Option<u64>,
    /// Accumulator collecting resolved socket addresses for the in-flight generation.
    pub(crate) collected: Vec<Endpoint>,
    /// Recycled endpoint buffer retaining allocated host name storage across resolution cycles.
    spare: Vec<Endpoint>,
    /// Indicates whether at least one host in the current generation successfully yielded
    /// addresses.
    pub(crate) any_resolved: bool,
    /// Configured resolution refresh interval: -1 disables background refresh, 0 refreshes
    /// constantly, >0 specifies millisecond interval.
    pub(crate) resolve_delay_ms: i32,
    /// Timestamp marking the most recent successful DNS resolution.
    pub(crate) last_resolved_at: Option<Millis>,
    /// Indicates whether a DNS resolution error inhibits connection attempts under legacy error
    /// preservation.
    pub(crate) block_connect: bool,
    /// Legacy one-shot delay flag indicating a full rotation pass through the host list has
    /// completed.
    pub(crate) delay: bool,
    /// Earliest timestamp at which reconnection may be attempted under deadline backoff.
    pub(crate) not_before: Option<Millis>,
    /// Cumulative count of consecutive full-pass connection failures, used to scale exponential
    /// backoff.
    pub(crate) failed_passes: u32,
    /// Standby read-write server discovered by background probe during read-only operation.
    pub(crate) rw_target: Option<Endpoint>,
    /// Indicates that a host rotation pass finished or session loss occurred without subsequent DNS
    /// refresh.
    pass_ended: bool,
    /// Remaining failure attempts allotted for a counted rotation pass.
    pass_left: Option<usize>,
    /// Contextual origin of the active in-flight resolution.
    origin: ResolveOrigin,
    /// System error code captured from the most recent DNS resolution failure.
    last_errno: Option<i32>,
    /// Canonical connect string representation after dynamic reconfiguration.
    host_text: Option<JString>,
}

impl Hosts {
    /// Instantiates initial host tracking state with configured host specifications.
    pub(crate) fn new(specs: Vec<HostSpec>, resolve_delay_ms: i32) -> Self {
        Self {
            specs,
            servers: ServerSet::new(),
            generation: 0,
            resolve_due: true,
            initial: true,
            in_flight: None,
            collected: Vec::new(),
            spare: Vec::new(),
            any_resolved: false,
            resolve_delay_ms,
            last_resolved_at: None,
            block_connect: false,
            delay: false,
            not_before: None,
            failed_passes: 0,
            rw_target: None,
            pass_ended: false,
            pass_left: None,
            origin: ResolveOrigin::Init,
            last_errno: None,
            host_text: None,
        }
    }
}

/// Computes the allocated capacity of an address vector based on 16-element legacy growth
/// increments.
fn addrvec_capacity(count: usize) -> usize {
    count
        .checked_add(ADDRVEC_GROW.saturating_sub(1))
        .and_then(|n| n.checked_div(ADDRVEC_GROW))
        .map_or(usize::MAX, |n| n.saturating_mul(ADDRVEC_GROW))
}

/// Formats diagnostic log arguments detailing reconfiguration address vector metrics.
fn reconfig_list_args(list: &AddrVec<Endpoint>) -> Vec<LogArg> {
    let as_i64 = |n: usize| i64::try_from(n).unwrap_or(i64::MAX);
    vec![
        LogArg::I64(as_i64(list.len())),
        LogArg::I64(as_i64(addrvec_capacity(list.len()))),
        LogArg::I64(as_i64(list.cursor())),
        LogArg::I64(i64::from(list.has_next())),
    ]
}

/// Reconstructs a comma-separated connection string (`host:port,...`) from structured host
/// specifications.
fn join_host_text(specs: &[HostSpec]) -> JString {
    let mut out = Vec::new();
    for (i, spec) in specs.iter().enumerate() {
        if i > 0 {
            out.push(b',');
        }
        match spec {
            HostSpec::Legacy(h) => {
                out.extend_from_slice(&h.host);
                out.push(b':');
                out.extend_from_slice(&h.service);
            }
            HostSpec::Native(h) => {
                out.extend_from_slice(h.host.as_bytes());
                out.push(b':');
                out.extend_from_slice(h.port.to_string().as_bytes());
            }
        }
    }
    JString::Bytes(out)
}

/// Populates an endpoint using preallocated spare storage when available, avoiding redundant string
/// allocations.
fn spare_endpoint(
    spare: &mut Vec<Endpoint>,
    addr: SocketAddr,
    index: usize,
    name: &[u8],
) -> Endpoint {
    match spare.pop() {
        Some(mut ep) => {
            ep.addr = addr;
            ep.host.index = index;
            ep.host.name.clear();
            ep.host.name.extend_from_slice(name);
            ep
        }
        None => Endpoint {
            addr,
            host: HostToken {
                index,
                name: name.to_vec(),
            },
        },
    }
}

/// Selects the platform-specific logging diagnostic site for `getaddrinfo` resolution failures.
const fn gai_site() -> LogSite {
    if cfg!(windows) {
        LogSite::ResolveWin32
    } else if cfg!(target_os = "linux") {
        LogSite::ResolveGai
    } else {
        LogSite::ResolveGaiErrno
    }
}

impl<T: Tokens> Connection<T> {
    /// Ingests asynchronous DNS resolution results for a single host specification.
    ///
    /// Results matching superseded generation identifiers are silently discarded. Resolution
    /// failures trigger immediate diagnostic logging at platform-specific error sites. Addresses
    /// are appended to the in-flight accumulation vector alongside associated host metadata tokens.
    pub fn handle_host_resolved(&mut self, generation: u64, r: HostResolution, _now: Millis) {
        if self.hosts.in_flight != Some(generation) {
            return;
        }
        let HostResolution {
            host,
            result,
            skipped_families,
        } = r;
        if !skipped_families.is_empty() {
            let text = self.hosts_text();
            for family in skipped_families {
                let text = text.clone();
                self.emit_log(LogSite::ResolveSkipFamily, || {
                    vec![LogArg::I64(i64::from(family)), LogArg::Str(text)]
                });
            }
        }
        match result {
            Err(e) => {
                self.hosts.last_errno = Some(e.errno);
                self.emit_log(gai_site(), || {
                    vec![LogArg::GaiStr {
                        gai_rc: e.gai_rc,
                        errno: e.errno,
                    }]
                });
            }
            Ok(addrs) => {
                if !addrs.is_empty() {
                    self.hosts.any_resolved = true;
                }
                let h = &mut self.hosts;
                let name = h.specs.get(host).map_or(&[][..], HostSpec::host_bytes);
                for addr in addrs {
                    let ep = spare_endpoint(&mut h.spare, addr, host, name);
                    h.collected.push(ep);
                }
            }
        }
    }

    /// Commits all accumulated resolution results following completion of host queries.
    ///
    /// # Resolution Processing & Commit Semantics:
    /// - Discards results matching obsolete generation counters.
    /// - If no socket addresses resolved across any host specification, returns `ZSYSTEMERROR`.
    ///   Under `DnsFailure::LegacyRcPreserved`, registers the error code to block subsequent
    ///   connection attempts. Under `DnsFailure::UseLastGood`, retains the existing server set and
    ///   proceeds.
    /// - Randomizes candidate ordering when `shuffle` is requested, reseeding the PRNG.
    /// - Merges updated addresses into the active `ServerSet`. If membership rebalancing shifts
    ///   endpoints on an active connection, triggers forced socket closure to initiate migration.
    pub fn handle_resolved(&mut self, generation: u64, shuffle: bool, now: Millis) -> i32 {
        if self.hosts.in_flight != Some(generation) {
            return rc::ZOK;
        }
        self.hosts.in_flight = None;
        let origin = self.hosts.origin;
        if origin == ResolveOrigin::SetServers {
            // Explicit ensemble reconfiguration satisfies any pending pass-based resolution
            // cadence.
            self.hosts.resolve_due = false;
            self.hosts.pass_ended = false;
        }
        let list = core::mem::take(&mut self.hosts.collected);
        self.hosts.any_resolved = false;
        let last_errno = self.hosts.last_errno.take();

        // An empty host specification list resolves cleanly to an empty address set.
        if list.is_empty() && !self.hosts.specs.is_empty() {
            if let Some(e) = last_errno {
                self.set_errno_hint(e);
            }
            // Propagate resolution failure return codes only when initiated by an active interest
            // iteration.
            let in_iteration =
                origin == ResolveOrigin::Iteration && self.interest.step != IterStep::Idle;
            if self.compat.dns_failure == DnsFailure::LegacyRcPreserved && in_iteration {
                self.interest.rc = rc::ZSYSTEMERROR;
                if !self.core.has_transport {
                    self.hosts.block_connect = true;
                }
            }
            return rc::ZSYSTEMERROR;
        }
        self.hosts_commit(list, shuffle, now);
        rc::ZOK
    }

    /// Commits a successful address list to the server set, shuffling and updating cursor state.
    fn hosts_commit(&mut self, mut list: Vec<Endpoint>, shuffle: bool, now: Millis) {
        self.hosts.last_resolved_at = Some(now);
        if shuffle && !list.is_empty() {
            self.rng.on_resolution();
            shuffle_list(&mut list, || self.rng.random());
        }
        let addr_eq = self.compat.addr_eq;
        let outcome =
            self.hosts
                .servers
                .update_from(&mut list, ListEq::LegacyOneWay, addr_eq, || {
                    self.rng.drand48()
                });
        // Swap allocated vector storage into the spare pool to recycle allocations on the next
        // cycle.
        core::mem::swap(&mut self.hosts.spare, &mut list);
        self.hosts.spare.reverse();
        list.clear();
        self.hosts.collected = list;
        if matches!(outcome, UpdateOutcome::Changed { .. }) {
            // A modified endpoint list resets rotation cursors and clears legacy counted pass
            // state.
            self.hosts.pass_left = None;
            // Purge spare allocations to prevent keeping oversized capacity following cluster
            // scale-down.
            self.hosts.collected = Vec::new();
        }
        if outcome == (UpdateOutcome::Changed { reconfig: true }) && self.core.has_transport {
            self.forced_close_trigger(now);
        }
    }

    /// Replaces the configured server ensemble with a parsed list of host specifications.
    ///
    /// Advances the resolution generation counter, flags a resolution cycle as immediately due,
    /// and resets failure backoff counters to initiate rapid reconnection against the updated
    /// ensemble.
    pub fn set_servers(&mut self, hosts: Vec<HostSpec>, _now: Millis) -> u64 {
        // Retain reconstructed connect string for diagnostic logging across reconnection events.
        self.hosts.host_text = Some(join_host_text(&hosts));
        self.hosts.specs = hosts;
        // Updating the ensemble specification resets consecutive failed pass counts and backoff
        // delays.
        self.hosts.failed_passes = 0;
        self.hosts.resolve_due = true;
        self.hosts_start_generation(ResolveOrigin::SetServers)
    }

    /// Configures the background DNS resolution refresh interval in milliseconds.
    ///
    /// Values < -1 are rejected with `ZBADARGUMENTS`. Value -1 disables periodic refresh,
    /// 0 performs continuous re-resolution, and values > 0 enforce the specified interval.
    pub fn set_resolve_delay(&mut self, ms: i32) -> i32 {
        if ms < -1 {
            self.emit_log(LogSite::ResolveDelayInvalid, || {
                vec![LogArg::I64(i64::from(ms))]
            });
            return rc::ZBADARGUMENTS;
        }
        self.hosts.resolve_delay_ms = ms;
        rc::ZOK
    }

    /// Advances the server selection cursor to the next candidate endpoint
    /// (`zoo_cycle_next_server`).
    ///
    /// During dynamic cluster reconfigurations, evaluates probabilistic selection between old and
    /// new ensemble address vectors and emits corresponding diagnostic logs.
    pub fn cycle_next_server(&mut self) {
        let reconfig = self.hosts.servers.reconfig;
        let new_cursor = self.hosts.servers.new.cursor();
        let old_cursor = self.hosts.servers.old.cursor();
        if reconfig {
            let old_args = reconfig_list_args(&self.hosts.servers.old);
            let new_args = reconfig_list_args(&self.hosts.servers.new);
            self.emit_log(LogSite::ReconfigOld, || old_args);
            self.emit_log(LogSite::ReconfigNew, || new_args);
        }
        let _ = self.hosts.servers.cycle_next(|| self.rng.drand48());
        if !reconfig {
            return;
        }
        // During reconfiguration, log whether the selected endpoint originated from the old or new
        // ensemble.
        let current = self.hosts.servers.current_addr();
        let still = self.hosts.servers.reconfig;
        if still && self.hosts.servers.new.cursor() != new_cursor {
            self.emit_log(LogSite::ReconfigUseNew, || vec![LogArg::Endpoint(current)]);
        } else if still && self.hosts.servers.old.cursor() != old_cursor {
            self.emit_log(LogSite::ReconfigUseOld, || vec![LogArg::Endpoint(current)]);
        } else {
            self.emit_log(LogSite::ReconfigNone, Vec::new);
        }
    }

    /// Retrieves the socket address of the actively selected server endpoint
    /// (`zoo_get_current_server`).
    #[must_use]
    pub fn current_server(&self) -> Option<SocketAddr> {
        self.hosts.servers.current_addr()
    }

    /// Ingests the outcome of an asynchronous read-write ensemble probe while operating in
    /// read-only mode.
    ///
    /// Discovery of a full read-write quorum member records the target endpoint, shortens probe
    /// intervals, and triggers connection teardown with `Cause::RwServerFound` to migrate the
    /// session.
    pub fn handle_rw_probe(&mut self, ep: Endpoint, outcome: ProbeOutcome, now: Millis) {
        if self.core.state != State::ReadOnly {
            return;
        }
        match outcome {
            ProbeOutcome::ReadWrite => {
                let addr = ep.addr;
                self.hosts.rw_target = Some(ep);
                self.timers.rw_timeout_ms = RW_PROBE_MIN_MS;
                self.emit_log(LogSite::RwServerFound, || {
                    vec![LogArg::Endpoint(Some(addr))]
                });
                let _ = self.teardown(Cause::RwServerFound, 0, now);
            }
            ProbeOutcome::NotReadWrite => {
                let _ = self.hosts.servers.addrs.next_item();
            }
            ProbeOutcome::Failed(failure) => {
                if let Some(f) = failure {
                    self.hosts_probe_tls_log(f);
                }
                let _ = self.hosts.servers.addrs.next_item();
            }
        }
    }

    /// Emits structured diagnostic log messages detailing probe-side TLS handshake or configuration
    /// failures.
    fn hosts_probe_tls_log(&mut self, failure: ProbeTlsFailure) {
        match failure {
            ProbeTlsFailure::Resource {
                at: TlsResourceAt::ContextNew,
            } => self.emit_log(LogSite::TlsContextNew, Vec::new),
            ProbeTlsFailure::Resource {
                at: TlsResourceAt::ConnectionNew,
            } => self.emit_log(LogSite::TlsConnectionNew, Vec::new),
            ProbeTlsFailure::Handshake => self.emit_log(LogSite::TlsConnect, Vec::new),
            ProbeTlsFailure::Config { step, file } => self.tls_config_failed(step, file),
        }
    }

    /// Evaluates whether a DNS resolution action should be dispatched during an interest loop
    /// iteration.
    pub(crate) fn resolve_action(&mut self, now: Millis) -> Option<Action> {
        // Suppress iteration resolution if an explicit ensemble reconfiguration query is currently
        // pending.
        if self.hosts.in_flight.is_some() && self.hosts.origin == ResolveOrigin::SetServers {
            return None;
        }
        if !self.hosts.resolve_due && !self.hosts_resolve_by_cadence(now) {
            return None;
        }
        let initial = self.hosts.initial;
        self.hosts.initial = false;
        let origin = if initial {
            ResolveOrigin::Init
        } else {
            ResolveOrigin::Iteration
        };
        let generation = self.hosts_start_generation(origin);
        self.hosts.resolve_due = false;
        self.hosts.pass_ended = false;
        Some(Action::Resolve {
            generation,
            hosts: self.hosts.specs.clone(),
            initial,
        })
    }

    /// Commences a new DNS resolution generation from `origin`, invalidating previous in-flight
    /// results.
    fn hosts_start_generation(&mut self, origin: ResolveOrigin) -> u64 {
        let generation = self.hosts.generation.wrapping_add(1);
        self.hosts.generation = generation;
        self.hosts.in_flight = Some(generation);
        self.hosts.origin = origin;
        self.hosts.collected.clear();
        self.hosts.any_resolved = false;
        self.hosts.last_errno = None;
        generation
    }

    /// Evaluates configured resolution cadence rules against connection state and elapsed time.
    fn hosts_resolve_by_cadence(&self, now: Millis) -> bool {
        let since_last_over = |ms: i64| {
            self.hosts
                .last_resolved_at
                .is_none_or(|t| elapsed(now, t) > ms)
        };
        if self.core.has_transport {
            return match self.hosts.resolve_delay_ms {
                -1 => false,
                d if d <= 0 => true,
                d => since_last_over(i64::from(d)),
            };
        }
        match self.compat.resolve_cadence {
            ResolveCadence::EveryIterationWhileDisconnected => true,
            ResolveCadence::EachPass => {
                self.hosts.pass_ended
                    && !self.hosts.delay
                    && self.hosts.not_before.is_none_or(|nb| now >= nb)
            }
            ResolveCadence::Throttled { ms } => since_last_over(i64::from(ms)),
            ResolveCadence::Never => false,
        }
    }

    /// Evaluates reconnection backoff delays and rotates endpoints to produce a connect decision.
    ///
    /// # Decision Logic:
    /// - Checks configured backoff constraints (`OneShotSkip` or `Deadline`), deferring socket
    ///   connection if waiting.
    /// - Respects DNS failure connection blocks (`block_connect`).
    /// - Prioritizes designated read-write probe targets over standard rotation.
    /// - Cycles to the next candidate endpoint in the address set.
    /// - If no candidate endpoints exist, triggers connection teardown with `Cause::SocketCreate`.
    pub(crate) fn connect_decision(&mut self, now: Millis) -> ConnectDecision {
        match self.compat.reconnect_backoff {
            Backoff::OneShotSkip => {
                if self.hosts.delay {
                    self.hosts.delay = false;
                    let text = self.hosts_text();
                    self.emit_log(LogSite::DelayingConnection, || vec![LogArg::Str(text)]);
                    self.timers.reset_for_connect(now);
                    return ConnectDecision::Wait;
                }
            }
            Backoff::Deadline => {
                if let Some(nb) = self.hosts.not_before {
                    if now < nb {
                        return ConnectDecision::Wait;
                    }
                    self.hosts.not_before = None;
                }
            }
        }
        if self.hosts.block_connect {
            return ConnectDecision::Blocked;
        }
        let endpoint = if let Some(ep) = self.hosts.rw_target.take() {
            self.hosts.servers.current = Some(ep.clone());
            Some(ep)
        } else {
            self.cycle_next_server();
            self.hosts.servers.current.clone()
        };
        let Some(endpoint) = endpoint else {
            // Teardown under empty host lists sets delay flags and establishes backoff barriers.
            self.hosts.delay = true;
            self.hosts.pass_ended = true;
            self.interest.rc = self.teardown(Cause::SocketCreate, 0, now);
            return ConnectDecision::Failed;
        };
        let tls = self.cfg.tls.then_some(self.compat.tls_verify);
        ConnectDecision::Connect(Action::Connect { endpoint, tls })
    }

    /// Executes teardown stage T5 following connection failures to update cursor state and backoff
    /// timers.
    pub(crate) fn hosts_on_handle_error(&mut self, now: Millis) {
        let addrs = &self.hosts.servers.addrs;
        let delay = addrs.at_end() || addrs.is_empty();
        let len = addrs.len();
        let current = addrs.peek().cloned();
        self.hosts.servers.current = current;
        match self.compat.reconnect_backoff {
            Backoff::OneShotSkip => {
                if delay {
                    self.hosts.pass_ended = true;
                }
                self.hosts.delay = delay;
            }
            Backoff::Deadline => {
                self.hosts.delay = false;
                let pass_end = match self.hosts.pass_left {
                    Some(left) => {
                        let left = left.saturating_sub(1);
                        self.hosts.pass_left = Some(left);
                        left == 0
                    }
                    None => delay,
                };
                if pass_end {
                    self.hosts.pass_ended = true;
                    self.hosts.not_before = self.hosts_pause_end(now);
                    self.hosts.failed_passes = self.hosts.failed_passes.saturating_add(1);
                    if self.hosts.pass_left.is_some() {
                        // Reset counted passes to allow one connection attempt per candidate
                        // address.
                        self.hosts.pass_left = Some(len);
                    }
                }
            }
        }
    }

    /// Executes teardown stage T5 upon loss of an established session.
    ///
    /// Session loss initiates a counted host pass beginning at the current cursor without immediate
    /// delay, scheduling an `EachPass` DNS resolution cycle ahead of subsequent reconnection.
    pub(crate) fn hosts_on_session_lost(&mut self, now: Millis) {
        match self.compat.reconnect_backoff {
            Backoff::OneShotSkip => self.hosts_on_handle_error(now),
            Backoff::Deadline => {
                let addrs = &self.hosts.servers.addrs;
                let len = addrs.len();
                let current = addrs.peek().cloned();
                self.hosts.servers.current = current;
                self.hosts.delay = false;
                self.hosts.pass_left = Some(len);
                self.hosts.pass_ended = true;
            }
        }
    }

    /// Computes the expiration deadline for an exponential reconnection backoff pause with random
    /// jitter.
    fn hosts_pause_end(&mut self, now: Millis) -> Option<Millis> {
        let base = i64::from(self.cfg.reconnect_delay_ms);
        if base == 0 {
            return None;
        }
        let shift = self.hosts.failed_passes.min(BACKOFF_MAX_SHIFT);
        let grown = 1i64
            .checked_shl(shift)
            .map_or(i64::MAX, |factor| base.saturating_mul(factor));
        let d = grown.min(base.max(BACKOFF_CAP_MS));
        let j = d
            .saturating_mul(i64::from(self.cfg.reconnect_jitter_pct))
            .checked_div(100)
            .unwrap_or(0);
        let span = j.saturating_mul(2).saturating_add(1);
        let draw = self.rng.random().checked_rem_euclid(span).unwrap_or(0);
        let offset = draw.saturating_sub(j);
        Some(now.offset_ms(d.saturating_add(offset)))
    }

    /// Resets consecutive failed pass counters upon establishing a valid session.
    pub(crate) fn hosts_on_established(&mut self) {
        self.hosts.failed_passes = 0;
        self.hosts.pass_left = None;
    }

    /// Returns the endpoint candidate targeted by the next read-only background probe.
    pub(crate) fn probe_endpoint(&self) -> Option<Endpoint> {
        self.hosts.servers.addrs.peek().cloned()
    }

    /// Retrieves canonical connection string text for diagnostic logging.
    fn hosts_text(&self) -> JString {
        self.hosts
            .host_text
            .clone()
            .unwrap_or_else(|| self.cfg.host_text.clone())
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::*;
    use crate::action::{ResolveError, TlsConfigStep};
    use crate::compat::Compat;
    use crate::config::DriverMode;
    use crate::errno;
    use crate::interest::test_support::{
        Tk, answer, conn, conn_with, ep, host, init_two, logs, sa, sites,
    };

    #[test]
    fn stale_results_change_nothing() {
        let mut c = conn();
        let Some(Action::Resolve { generation, .. }) = c.poll_action(Millis(0)) else {
            panic!("expected Resolve");
        };
        c.handle_host_resolved(
            generation + 1,
            HostResolution {
                host: 0,
                result: Ok(vec![sa("10.0.0.1:2181")]),
                skipped_families: vec![99],
            },
            Millis(0),
        );
        assert!(c.hosts.collected.is_empty());
        assert!(sites(&mut c).is_empty());
        assert_eq!(c.handle_resolved(generation + 1, true, Millis(0)), rc::ZOK);
        assert_eq!(c.hosts.in_flight, Some(generation));
        assert!(c.hosts.servers.addrs.is_empty());
    }

    #[test]
    fn unchanged_resolution_keeps_the_list_storage() {
        let mut c = conn();
        init_two(&mut c);
        let two = [Some("10.0.0.1:2181"), Some("10.0.0.2:2181")];
        let want = [ep("10.0.0.1:2181", 0, "a"), ep("10.0.0.2:2181", 1, "b")];
        assert_eq!(c.hosts.servers.addrs.as_slice(), &want[..]);
        let specs = vec![host("a", 2181), host("b", 2181)];
        let generation = c.set_servers(specs.clone(), Millis(1));
        assert_eq!(answer(&mut c, generation, &two, Millis(1)), rc::ZOK);
        assert_eq!(c.hosts.servers.addrs.as_slice(), &want[..]);
        assert!(c.hosts.collected.is_empty());
        assert_eq!(c.hosts.spare.len(), 2);
        // Successive resolutions populate the spare endpoint buffer in configured order,
        // alternating backing storage between `collected` and `spare` vectors to avoid allocations.
        let generation = c.set_servers(specs.clone(), Millis(2));
        assert_eq!(answer(&mut c, generation, &two, Millis(2)), rc::ZOK);
        let names: Vec<*const u8> = c.hosts.spare.iter().rev().map(name_ptr).collect();
        let spare_list = c.hosts.spare.as_ptr();
        let collected_list = c.hosts.collected.as_ptr();
        assert!(c.hosts.collected.capacity() >= 2);
        let generation = c.set_servers(specs.clone(), Millis(3));
        feed_host(&mut c, generation, 0, &["10.0.0.1:2181"]);
        feed_host(&mut c, generation, 1, &["10.0.0.2:2181"]);
        assert_eq!(c.hosts.collected.as_ptr(), collected_list);
        let reused: Vec<*const u8> = c.hosts.collected.iter().map(name_ptr).collect();
        assert_eq!(reused, names);
        assert_eq!(c.handle_resolved(generation, false, Millis(3)), rc::ZOK);
        assert_eq!(c.hosts.servers.addrs.as_slice(), &want[..]);
        assert_eq!(c.hosts.spare.as_ptr(), collected_list);
        assert_eq!(c.hosts.collected.as_ptr(), spare_list);
        // When the resolved host list differs from active servers, the new configuration is
        // committed and spare allocation buffers are reclaimed.
        let generation = c.set_servers(specs, Millis(4));
        let one = [Some("10.0.0.3:2181"), None];
        assert_eq!(answer(&mut c, generation, &one, Millis(4)), rc::ZOK);
        let want = [ep("10.0.0.3:2181", 0, "a")];
        assert_eq!(c.hosts.servers.addrs.as_slice(), &want[..]);
        assert!(c.hosts.collected.is_empty());
        assert_eq!(c.hosts.collected.capacity(), 0);
        assert!(c.hosts.spare.is_empty());
        assert_eq!(c.hosts.spare.capacity(), 0);
    }

    /// Obtains a raw pointer to the underlying hostname byte buffer of an endpoint for identity
    /// verification.
    fn name_ptr(e: &Endpoint) -> *const u8 {
        e.host.name.as_ptr()
    }

    /// Feeds resolved IP addresses for each host index and returns the accumulated endpoint
    /// collection.
    fn feed(c: &mut Connection<Tk>, generation: u64, per_host: &[&[&str]]) -> Vec<Endpoint> {
        for (i, addrs) in per_host.iter().enumerate() {
            feed_host(c, generation, i, addrs);
        }
        c.hosts.collected.clone()
    }

    /// Injects resolved IP addresses for a single host index into the connection's resolution
    /// state.
    fn feed_host(c: &mut Connection<Tk>, generation: u64, host: usize, addrs: &[&str]) {
        c.handle_host_resolved(
            generation,
            HostResolution {
                host,
                result: Ok(addrs.iter().map(|a| sa(a)).collect()),
                skipped_families: Vec::new(),
            },
            Millis(0),
        );
    }

    /// Verifies that resolving specifications against an existing connection yields identical
    /// endpoint collections to a clean resolution against a newly initialized connection.
    fn resolve_like_fresh(c: &mut Connection<Tk>, specs: &[HostSpec], per_host: &[&[&str]]) {
        let mut fresh = conn();
        let fg = fresh.set_servers(specs.to_vec(), Millis(0));
        let want = feed(&mut fresh, fg, per_host);
        assert_eq!(fresh.handle_resolved(fg, false, Millis(0)), rc::ZOK);
        let generation = c.set_servers(specs.to_vec(), Millis(0));
        assert_eq!(feed(c, generation, per_host), want);
        assert_eq!(c.handle_resolved(generation, false, Millis(0)), rc::ZOK);
        assert_eq!(
            c.hosts.servers.addrs.as_slice(),
            fresh.hosts.servers.addrs.as_slice()
        );
    }

    #[test]
    fn spare_entries_give_the_list_of_a_fresh_build() {
        let mut c = conn();
        let two = [host("alpha", 2181), host("b", 2181)];
        let addrs: [&[&str]; 2] = [&["10.0.0.1:2181"], &["10.0.0.2:2181"]];
        resolve_like_fresh(&mut c, &two, &addrs);
        assert!(c.hosts.spare.is_empty());
        // An unchanged server list recycles its allocated entries into the spare buffer for
        // subsequent passes.
        resolve_like_fresh(&mut c, &two, &addrs);
        assert_eq!(c.hosts.spare.len(), 2);
        let generation = c.set_servers(two.to_vec(), Millis(0));
        feed_host(&mut c, generation, 0, addrs[0]);
        assert_eq!(c.hosts.spare.len(), 1);
        feed_host(&mut c, generation, 1, addrs[1]);
        assert!(c.hosts.spare.is_empty());
        assert_eq!(c.handle_resolved(generation, false, Millis(0)), rc::ZOK);
        assert_eq!(c.hosts.spare.len(), 2);
        // A truncated host list with shorter hostnames safely overwrites existing spare capacity
        // in-place.
        let one = [host("c", 2182)];
        resolve_like_fresh(&mut c, &one, &[&["10.0.0.3:2182"]]);
        assert!(c.hosts.spare.is_empty());
        resolve_like_fresh(&mut c, &one, &[&["10.0.0.3:2182"]]);
        assert_eq!(c.hosts.spare.len(), 1);
        // An expanded host list exhausts existing spare capacity before allocating additional
        // buffer space.
        let three = [host("x", 2181), host("yy", 2181), host("zzz", 2181)];
        let more: [&[&str]; 3] = [
            &["10.0.1.1:2181", "10.0.1.2:2181"],
            &["10.0.1.3:2181"],
            &["10.0.1.4:2181", "10.0.1.5:2181"],
        ];
        resolve_like_fresh(&mut c, &three, &more);
        assert!(c.hosts.spare.is_empty());
        resolve_like_fresh(&mut c, &three, &more);
        assert_eq!(c.hosts.spare.len(), 5);
        resolve_like_fresh(&mut c, &three, &more);
        assert_eq!(c.hosts.spare.len(), 5);
    }

    #[test]
    fn host_results_log_at_once_and_carry_the_token() {
        let mut c = conn();
        let Some(Action::Resolve { generation, .. }) = c.poll_action(Millis(0)) else {
            panic!("expected Resolve");
        };
        c.handle_host_resolved(
            generation,
            HostResolution {
                host: 0,
                result: Err(ResolveError {
                    gai_rc: -2,
                    errno: 5,
                }),
                skipped_families: Vec::new(),
            },
            Millis(0),
        );
        let l = logs(&mut c);
        assert_eq!(l.len(), 1);
        assert_eq!(l[0].site, gai_site());
        assert_eq!(
            l[0].args,
            vec![LogArg::GaiStr {
                gai_rc: -2,
                errno: 5
            }]
        );
        c.handle_host_resolved(
            generation,
            HostResolution {
                host: 1,
                result: Ok(vec![sa("10.0.0.2:2181")]),
                skipped_families: vec![0x1e],
            },
            Millis(0),
        );
        let l = logs(&mut c);
        assert_eq!(l[0].site, LogSite::ResolveSkipFamily);
        assert_eq!(
            l[0].args,
            vec![
                LogArg::I64(0x1e),
                LogArg::Str(JString::Bytes(b"a:2181,b:2181".to_vec()))
            ]
        );
        assert_eq!(c.hosts.collected, vec![ep("10.0.0.2:2181", 1, "b")]);
        assert_eq!(c.handle_resolved(generation, false, Millis(3)), rc::ZOK);
        assert_eq!(c.hosts.last_resolved_at, Some(Millis(3)));
        assert_eq!(c.hosts.servers.addrs.len(), 1);
    }

    /// Verifies that each host resolution cycle re-seeds the random number generator exactly once
    /// and performs Fisher-Yates shuffling over the resolved addresses.
    #[test]
    fn shuffle_reseeds_and_draws() {
        let (mut c, draws) = conn_with(Compat::LEGACY_C, DriverMode::ST, |_| {});
        let Some(Action::Resolve { generation, .. }) = c.poll_action(Millis(0)) else {
            panic!("expected Resolve");
        };
        for (i, a) in ["10.0.0.1:2181", "10.0.0.2:2181"].iter().enumerate() {
            c.handle_host_resolved(
                generation,
                HostResolution {
                    host: i,
                    result: Ok(vec![sa(a)]),
                    skipped_families: Vec::new(),
                },
                Millis(0),
            );
        }
        // A random value of zero causes the shuffle to swap the two endpoints.
        draws.lock().unwrap().random.push_back(0);
        assert_eq!(c.handle_resolved(generation, true, Millis(0)), rc::ZOK);
        let d = draws.lock().unwrap();
        assert_eq!(d.resolutions, 1);
        assert_eq!(d.random_calls, 1);
        drop(d);
        assert_eq!(
            c.hosts.servers.addrs.as_slice()[0].addr,
            sa("10.0.0.2:2181")
        );
    }

    #[test]
    fn set_servers_commits_and_resolves_with_its_generation() {
        let mut c = conn();
        init_two(&mut c);
        let generation = c.set_servers(vec![host("c", 2182)], Millis(10));
        assert_eq!(c.hosts.generation, generation);
        assert!(c.hosts.resolve_due);
        // Ensure no redundant background resolution is initiated while an active driver resolution
        // is pending.
        c.begin_interest(Millis(10)).unwrap();
        c.interest.step = IterStep::Resolve;
        assert!(c.resolve_action(Millis(10)).is_none());
        c.handle_host_resolved(
            generation,
            HostResolution {
                host: 0,
                result: Ok(vec![sa("10.0.0.3:2182")]),
                skipped_families: Vec::new(),
            },
            Millis(10),
        );
        assert_eq!(c.handle_resolved(generation, false, Millis(10)), rc::ZOK);
        assert!(!c.hosts.resolve_due);
        assert_eq!(
            c.hosts.servers.addrs.as_slice()[0],
            ep("10.0.0.3:2182", 0, "c")
        );
        assert_eq!(c.hosts_text(), JString::Bytes(b"c:2182".to_vec()));
    }

    #[test]
    fn set_servers_failure_keeps_the_old_addresses() {
        let mut c = conn();
        init_two(&mut c);
        let generation = c.set_servers(vec![host("c", 2182)], Millis(10));
        let rc = answer(&mut c, generation, &[None], Millis(10));
        assert_eq!(rc, rc::ZSYSTEMERROR);
        // Configuration resolution failures preserve existing interest flags and active addresses.
        assert_eq!(c.interest.rc, rc::ZOK);
        assert!(!c.hosts.block_connect);
        assert_eq!(c.hosts.servers.addrs.len(), 2);
        assert_eq!(c.hosts.specs, vec![host("c", 2182)]);
    }

    #[test]
    fn empty_host_list_resolves_to_an_empty_list() {
        let mut c = conn();
        init_two(&mut c);
        let generation = c.set_servers(Vec::new(), Millis(10));
        assert!(sites(&mut c).is_empty());
        assert_eq!(c.handle_resolved(generation, true, Millis(10)), rc::ZOK);
        assert!(c.hosts.servers.addrs.is_empty());
    }

    #[test]
    fn resolve_delay_validation() {
        let mut c = conn();
        assert_eq!(c.set_resolve_delay(-2), rc::ZBADARGUMENTS);
        let l = logs(&mut c);
        assert_eq!(l[0].site, LogSite::ResolveDelayInvalid);
        assert_eq!(l[0].args, vec![LogArg::I64(-2)]);
        assert_eq!(c.hosts.resolve_delay_ms, 0);
        assert_eq!(c.set_resolve_delay(-1), rc::ZOK);
        assert_eq!(c.hosts.resolve_delay_ms, -1);
    }

    #[test]
    fn resolve_with_transport_follows_the_delay() {
        let mut c = conn();
        init_two(&mut c);
        c.core.has_transport = true;
        c.hosts.resolve_delay_ms = -1;
        assert!(c.resolve_action(Millis(1_000_000)).is_none());
        c.hosts.resolve_delay_ms = 0;
        assert!(c.resolve_action(Millis(1)).is_some());
        c.hosts.in_flight = None;
        c.hosts.last_resolved_at = Some(Millis(1000));
        c.hosts.resolve_delay_ms = 500;
        assert!(c.resolve_action(Millis(1500)).is_none());
        assert!(c.resolve_action(Millis(1501)).is_some());
    }

    #[test]
    fn resolve_cadence_without_transport() {
        let with = |cadence| {
            let compat = Compat {
                resolve_cadence: cadence,
                ..Compat::LEGACY_C
            };
            let (mut c, _) = conn_with(compat, DriverMode::ST, |_| {});
            init_two(&mut c);
            c
        };
        let mut c = with(ResolveCadence::Never);
        assert!(c.resolve_action(Millis(1_000_000)).is_none());

        let mut c = with(ResolveCadence::Throttled { ms: 100 });
        assert!(c.resolve_action(Millis(100)).is_none());
        assert!(c.resolve_action(Millis(101)).is_some());

        let mut c = with(ResolveCadence::EachPass);
        assert!(c.resolve_action(Millis(5)).is_none());
        c.hosts.servers.reconfig = false;
        let _ = c.hosts.servers.addrs.next_item();
        let _ = c.hosts.servers.addrs.next_item();
        c.hosts_on_handle_error(Millis(5));
        // Under OneShotSkip cadence, resolution occurs only after the reconnection delay interval
        // expires.
        assert!(c.resolve_action(Millis(5)).is_none());
        c.hosts.delay = false;
        let Some(Action::Resolve { initial, .. }) = c.resolve_action(Millis(6)) else {
            panic!("expected Resolve");
        };
        assert!(!initial);
        assert!(c.resolve_action(Millis(7)).is_none());
    }

    #[test]
    fn reconfig_selection_logs() {
        let mut c = conn();
        init_two(&mut c);
        assert!(c.hosts.servers.reconfig);
        c.cycle_next_server();
        let l = logs(&mut c);
        let s: Vec<LogSite> = l.iter().map(|r| r.site).collect();
        assert_eq!(
            s,
            vec![
                LogSite::ReconfigOld,
                LogSite::ReconfigNew,
                LogSite::ReconfigUseNew
            ]
        );
        assert_eq!(
            l[1].args,
            vec![
                LogArg::I64(2),
                LogArg::I64(16),
                LogArg::I64(0),
                LogArg::I64(1)
            ]
        );
        assert_eq!(l[0].args, vec![LogArg::I64(0); 4]);
        assert_eq!(l[2].args, vec![LogArg::Endpoint(Some(sa("10.0.0.1:2181")))]);
        c.cycle_next_server();
        let _ = logs(&mut c);
        c.cycle_next_server();
        assert_eq!(
            sites(&mut c),
            vec![
                LogSite::ReconfigOld,
                LogSite::ReconfigNew,
                LogSite::ReconfigNone
            ]
        );
        assert!(!c.hosts.servers.reconfig);
        assert_eq!(c.current_server(), Some(sa("10.0.0.1:2181")));
        c.cycle_next_server();
        assert!(sites(&mut c).is_empty());
    }

    #[test]
    fn reconfig_old_list_logs_use_old() {
        let mut c = conn();
        init_two(&mut c);
        // When server list lengths match and the current server is absent, the old list probability
        // governs selection.
        c.hosts.servers.new = AddrVec::new();
        c.hosts.servers.old = AddrVec::from(vec![ep("10.0.0.2:2181", 1, "b")]);
        c.cycle_next_server();
        let s = sites(&mut c);
        assert_eq!(s[2], LogSite::ReconfigUseOld);
        assert_eq!(c.current_server(), Some(sa("10.0.0.2:2181")));
    }

    #[test]
    fn capacity_rounds_up_to_the_growth_step() {
        assert_eq!(addrvec_capacity(0), 0);
        assert_eq!(addrvec_capacity(1), 16);
        assert_eq!(addrvec_capacity(16), 16);
        assert_eq!(addrvec_capacity(17), 32);
    }

    #[test]
    fn probe_failures_advance_the_cursor_without_cur() {
        let mut c = conn();
        init_two(&mut c);
        c.hosts.servers.reconfig = false;
        c.cycle_next_server();
        let cur = c.current_server();
        c.core.state = State::ReadOnly;
        assert_eq!(c.probe_endpoint(), Some(ep("10.0.0.2:2181", 1, "b")));
        c.handle_rw_probe(
            ep("10.0.0.2:2181", 1, "b"),
            ProbeOutcome::NotReadWrite,
            Millis(0),
        );
        assert_eq!(c.current_server(), cur);
        assert_eq!(c.probe_endpoint(), Some(ep("10.0.0.1:2181", 0, "a")));
        assert!(sites(&mut c).is_empty());

        c.handle_rw_probe(
            ep("10.0.0.1:2181", 0, "a"),
            ProbeOutcome::Failed(Some(ProbeTlsFailure::Config {
                step: TlsConfigStep::CaFile,
                file: JString::Null,
            })),
            Millis(0),
        );
        let l = logs(&mut c);
        assert_eq!(l[0].site, LogSite::TlsCaFile);
        assert_eq!(l[0].args, vec![LogArg::Str(JString::Null)]);
        assert_eq!(c.take_errno_hint(), Some(errno::EINVAL));
        assert_eq!(c.probe_endpoint(), Some(ep("10.0.0.2:2181", 1, "b")));

        c.handle_rw_probe(
            ep("10.0.0.2:2181", 1, "b"),
            ProbeOutcome::Failed(Some(ProbeTlsFailure::Handshake)),
            Millis(0),
        );
        assert_eq!(sites(&mut c), vec![LogSite::TlsConnect]);
        assert_eq!(c.take_errno_hint(), None);
        c.handle_rw_probe(
            ep("10.0.0.1:2181", 0, "a"),
            ProbeOutcome::Failed(None),
            Millis(0),
        );
        assert!(sites(&mut c).is_empty());
    }

    #[test]
    fn probe_results_outside_read_only_are_ignored() {
        let mut c = conn();
        init_two(&mut c);
        c.core.state = State::Connected;
        let before = c.hosts.servers.addrs.cursor();
        c.handle_rw_probe(
            ep("10.0.0.1:2181", 0, "a"),
            ProbeOutcome::ReadWrite,
            Millis(0),
        );
        assert_eq!(c.hosts.rw_target, None);
        assert_eq!(c.hosts.servers.addrs.cursor(), before);
        assert!(sites(&mut c).is_empty());
    }

    /// Verifies that a successful read-write probe target is selected as the primary endpoint for
    /// the next connection attempt.
    #[test]
    fn rw_target_is_the_next_connect() {
        let mut c = conn();
        init_two(&mut c);
        c.hosts.rw_target = Some(ep("10.0.0.2:2181", 1, "b"));
        let ConnectDecision::Connect(Action::Connect { endpoint, .. }) =
            c.connect_decision(Millis(0))
        else {
            panic!("expected Connect");
        };
        assert_eq!(endpoint.addr, sa("10.0.0.2:2181"));
        assert_eq!(c.current_server(), Some(sa("10.0.0.2:2181")));
        assert_eq!(c.hosts.rw_target, None);
        // Confirm that the reconfiguration cursor remains stationary when connecting to a probed
        // endpoint.
        assert_eq!(c.hosts.servers.new.cursor(), 0);
    }

    #[test]
    fn block_connect_gives_blocked() {
        let mut c = conn();
        init_two(&mut c);
        c.hosts.block_connect = true;
        assert!(matches!(
            c.connect_decision(Millis(0)),
            ConnectDecision::Blocked
        ));
    }

    #[test]
    fn one_shot_wait_resets_the_timers() {
        let mut c = conn();
        init_two(&mut c);
        c.hosts.delay = true;
        c.timers.rw_timeout_ms = 800;
        assert!(matches!(
            c.connect_decision(Millis(42)),
            ConnectDecision::Wait
        ));
        assert!(!c.hosts.delay);
        assert_eq!(c.timers.last_recv, Millis(42));
        assert_eq!(c.timers.rw_timeout_ms, RW_PROBE_MIN_MS);
        let l = logs(&mut c);
        assert_eq!(l[0].site, LogSite::DelayingConnection);
        assert_eq!(
            l[0].args,
            vec![LogArg::Str(JString::Bytes(b"a:2181,b:2181".to_vec()))]
        );
    }

    #[test]
    fn handle_error_on_an_empty_list() {
        let mut c = conn();
        c.hosts_on_handle_error(Millis(0));
        assert!(c.hosts.delay);
        assert_eq!(c.current_server(), None);
    }

    #[test]
    fn deadline_pause_grows_exponentially_and_resets() {
        let compat = Compat {
            reconnect_backoff: Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut c, _) = conn_with(compat, DriverMode::ST, |cfg| {
            cfg.reconnect_delay_ms = 1_000;
            cfg.reconnect_jitter_pct = 0;
        });
        // With an empty address list, each failure constitutes the completion of an entire
        // traversal pass.
        let mut pauses = Vec::new();
        for _ in 0..7 {
            c.hosts_on_handle_error(Millis(0));
            pauses.push(c.hosts.not_before.unwrap().0);
        }
        assert_eq!(
            pauses,
            vec![1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]
        );
        // Successfully establishing a session resets backoff failure counters back to their initial
        // levels.
        c.hosts_on_established();
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.hosts.not_before, Some(Millis(1_000)));
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.hosts.not_before, Some(Millis(2_000)));
        // Similarly, updating the ensemble host specification resets the backoff state.
        let _ = c.set_servers(vec![host("a", 2181)], Millis(0));
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.hosts.not_before, Some(Millis(1_000)));
        // Repeated failure counts saturate arithmetic without overflowing and remain clamped at the
        // maximum backoff ceiling.
        c.hosts.failed_passes = u32::MAX;
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.hosts.not_before, Some(Millis(30_000)));
        // Explicitly configured delay durations exceeding default maximum caps are preserved
        // without reduction.
        let (mut big, _) = conn_with(compat, DriverMode::ST, |cfg| {
            cfg.reconnect_delay_ms = 45_000;
        });
        big.hosts_on_handle_error(Millis(0));
        big.hosts_on_handle_error(Millis(0));
        assert_eq!(big.hosts.not_before, Some(Millis(45_000)));
    }

    #[test]
    fn deadline_jitter_scales_with_the_grown_pause() {
        let compat = Compat {
            reconnect_backoff: Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut c, draws) = conn_with(compat, DriverMode::ST, |cfg| {
            cfg.reconnect_delay_ms = 1_000;
            cfg.reconnect_jitter_pct = 25;
        });
        c.hosts.failed_passes = 2;
        // Base backoff 4000ms with 25% jitter (1000ms); a minimum RNG draw yields the maximum
        // negative offset (-1000ms).
        draws.lock().unwrap().random.push_back(0);
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.hosts.not_before, Some(Millis(3_000)));
    }

    #[test]
    fn deadline_lost_session_starts_a_counted_pass() {
        let compat = Compat {
            reconnect_backoff: Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut c, _) = conn_with(compat, DriverMode::ST, |cfg| {
            cfg.reconnect_delay_ms = 1_000;
            cfg.reconnect_jitter_pct = 0;
        });
        init_two(&mut c);
        c.hosts.servers.reconfig = false;
        // When a session is lost while connected to the final address in the list, reconnection
        // occurs immediately while scheduling a fresh DNS resolution pass.
        let _ = c.hosts.servers.addrs.next_item();
        let _ = c.hosts.servers.addrs.next_item();
        c.hosts_on_session_lost(Millis(0));
        assert_eq!(c.hosts.not_before, None);
        assert!(c.hosts.pass_ended);
        assert_eq!(c.hosts.pass_left, Some(2));
        assert_eq!(c.current_server(), Some(sa("10.0.0.1:2181")));
        // The traversal pass concludes on its second consecutive failure, after which the
        // subsequent pass retains the same traversal count.
        c.cycle_next_server();
        c.hosts_on_handle_error(Millis(10));
        assert_eq!(c.hosts.not_before, None);
        c.cycle_next_server();
        c.hosts_on_handle_error(Millis(20));
        assert_eq!(c.hosts.not_before, Some(Millis(1_020)));
        assert_eq!(c.hosts.failed_passes, 1);
        assert_eq!(c.hosts.pass_left, Some(2));
        // Updating the server configuration terminates active counted pass constraints.
        let generation = c.set_servers(vec![host("c", 23700)], Millis(30));
        let got = answer(&mut c, generation, &[Some("10.0.0.3:23700")], Millis(30));
        assert_eq!(got, rc::ZOK);
        assert_eq!(c.hosts.pass_left, None);
        // The resolution triggered by updating servers satisfies the resolution requirement for
        // this traversal pass.
        assert!(!c.hosts.pass_ended);
        // Re-establishing an active session similarly clears active pass tracking.
        c.hosts_on_session_lost(Millis(40));
        assert_eq!(c.hosts.pass_left, Some(1));
        c.hosts_on_established();
        assert_eq!(c.hosts.pass_left, None);
    }

    #[test]
    fn each_pass_counts_a_set_servers_resolution_after_a_loss() {
        let compat = Compat {
            resolve_cadence: ResolveCadence::EachPass,
            reconnect_backoff: Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut c, _) = conn_with(compat, DriverMode::ST, |_| {});
        init_two(&mut c);
        c.hosts.servers.reconfig = false;
        c.hosts_on_session_lost(Millis(0));
        assert!(c.hosts.pass_ended);
        // A failed server reconfiguration resolution also fulfills the pass requirement, preventing
        // immediate duplicate resolution.
        let generation = c.set_servers(vec![host("c", 2182)], Millis(10));
        let got = answer(&mut c, generation, &[None], Millis(10));
        assert_eq!(got, rc::ZSYSTEMERROR);
        assert!(!c.hosts.pass_ended);
        assert!(c.resolve_action(Millis(20)).is_none());
    }

    #[test]
    fn one_shot_skip_lost_session_keeps_the_legacy_delay() {
        let mut c = conn();
        init_two(&mut c);
        c.hosts.servers.reconfig = false;
        let _ = c.hosts.servers.addrs.next_item();
        let _ = c.hosts.servers.addrs.next_item();
        c.hosts_on_session_lost(Millis(0));
        assert!(c.hosts.delay);
        assert!(c.hosts.pass_ended);
        assert_eq!(c.hosts.pass_left, None);
        assert_eq!(c.hosts.not_before, None);
        assert_eq!(c.current_server(), Some(sa("10.0.0.1:2181")));
    }
}
