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

//! Sequential domain name resolution, endpoint list permutation, and dynamic `zoo_set_servers`
//! continuation state machines.
//!
//! # Architectural Role in `zk-driver`
//!
//! When the protocol engine (`zk-proto`) emits `Action::Resolve { generation, hosts, initial }`,
//! or when a caller invokes `zoo_set_servers` to dynamically reconfigure the server ensemble,
//! the driver must resolve each configured [`HostSpec`] into concrete socket addresses, optionally
//! re-seed the host PRNG for Fisher-Yates permutation, commit the resolved address list to
//! [`Connection::handle_resolved`], and tear down the active transport if rebalancing requires
//! migrating away from the current server.
//!
//! This module provides two execution paths corresponding to the two
//! [`DeferPolicy`](crate::iocore::DeferPolicy) regimes in [`crate::iocore`]:
//!
//! 1. **Inline Resumable State Machine ([`ResolveRun`] and [`SetServersRun`])**: Used by the
//!    drop-in C compatibility drivers ([`crate::init`], [`crate::polled`], and [`crate::threaded`])
//!    where [`DeferPolicy::NONE`](crate::iocore::DeferPolicy::NONE) executes blocking `getaddrinfo`
//!    and `rng_reseed` hooks inline on the calling thread (`&Unlocked`). Because C `zookeeper.c`
//!    logs each failed `getaddrinfo` call immediately before starting the next host's lookup,
//!    [`ResolveRun::run_hosts`] checks `ob.has_pending_logs()` before every blocking
//!    `host.resolve(...)` or `host.rng_reseed(...)` call and yields [`ResolveEnd::More`] so the C
//!    shim flushes pending log records outside all Rust locks before the next syscall blocks.
//! 2. **Batch Offloaded Application ([`apply_results`])**: Used by the pure-Rust native and Tokio
//!    drivers ([`crate::native`] and [`crate::tokio`]) when DNS lookups execute asynchronously on a
//!    worker thread (`zk-dns` or `spawn_blocking`) via [`crate::iocore::run_job`]. Once the worker
//!    collects the full `Vec<HostResolution>` and reseeds the PRNG, [`apply_results`] ingests the
//!    entire batch into `conn` in a single critical section.

use std::sync::Arc;

use zk_proto::{Connection, HostResolution, HostSpec, Millis, Tokens, rc};

use crate::clock::Clock;
use crate::host::HostLeaves;
use crate::outbox::{Flavor, Outbox};
use crate::state::Core;
use crate::sync::{Mutex, Unlocked};

/// Execution context and shared services powering an iterative host resolution loop.
pub(crate) struct ResolveCtx<'a, T: Tokens> {
    /// Reference to the protected driver core state mutex.
    pub(crate) core: &'a Mutex<Core<T>>,
    /// Host environment hooks providing system resolution and random number services.
    pub(crate) host: &'a dyn HostLeaves<T>,
    /// Monotonic clock provider tracking operational deadlines.
    pub(crate) clock: &'a dyn Clock,
    /// Outbox buffering flavor defining completion routing behavior.
    pub(crate) flavor: Flavor,
}

/// Progression phases within an active host resolution loop.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ResolvePhase {
    /// Iteratively resolving individual host entries.
    Hosts,
    /// Host resolution complete; performing PRNG reseed and committing the resolved ensemble.
    Reseed,
    /// Resolution sequence fully completed.
    Done,
}

/// State tracker managing the step-by-step inline resolution of a server ensemble across log flush
/// and callback barriers.
#[derive(Debug)]
pub(crate) struct ResolveRun {
    /// Monotonic generation identifier validating this resolution pass against newer ensemble
    /// updates.
    pub(crate) generation: u64,
    /// Configured host specifications to resolve in order.
    pub(crate) hosts: Arc<[HostSpec]>,
    /// Index of the next host entry awaiting DNS resolution.
    pub(crate) next: usize,
    /// Indicates whether at least one host entry resolved to a valid IP address.
    pub(crate) any_resolved: bool,
    /// Current execution phase of the resolution loop.
    pub(crate) phase: ResolvePhase,
    /// Accumulated ZooKeeper return code resulting from the resolution pass.
    rc: i32,
    /// Whether this run should execute transport teardown upon completing a rebalance.
    teardown: bool,
    /// Monotonic timestamp sampled after the most recent host lookup in the active `run` invocation
    /// so ensemble commitment and the enclosing turn can reuse it. Reset on each `run` entry.
    at: Option<Millis>,
}

/// Outcome of executing a step within a [`ResolveRun`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ResolveEnd {
    /// Execution paused to flush pending log records or unblock a protocol barrier.
    More,
    /// Resolution pass finished with the resulting ZooKeeper return code.
    Done {
        /// Return code representing the outcome of ensemble resolution.
        rc: i32,
    },
}

impl ResolveRun {
    /// Initializes a new resolution run for the specified generation and host list.
    pub(crate) fn new(generation: u64, hosts: Arc<[HostSpec]>) -> Self {
        Self {
            generation,
            hosts,
            next: 0,
            any_resolved: false,
            phase: ResolvePhase::Hosts,
            rc: rc::ZOK,
            teardown: true,
            at: None,
        }
    }

    /// Returns the timestamp captured after the final host lookup or ensemble commit of the most
    /// recent `run` call, or `None` if that invocation neither resolved a host nor committed.
    pub(crate) fn last_at(&self) -> Option<Millis> {
        self.at
    }

    /// Initializes a resolution run that delegates forced close teardown to the outer I/O driver.
    pub(crate) fn without_teardown(generation: u64, hosts: Arc<[HostSpec]>) -> Self {
        Self {
            teardown: false,
            ..Self::new(generation, hosts)
        }
    }

    /// Advances the resolution state machine until it either completes or yields at a log/barrier
    /// boundary.
    pub(crate) fn run<T: Tokens>(
        &mut self,
        cx: &ResolveCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> ResolveEnd {
        // Clear any timestamp from a prior `run` invocation because the caller drained the outbox
        // across the yield boundary.
        self.at = None;
        if let ResolveEnd::More = self.run_hosts(cx, proof, ob) {
            return ResolveEnd::More;
        }
        if self.phase == ResolvePhase::Reseed {
            let shuffle = shuffle_on(cx.host);
            if shuffle && self.any_resolved {
                if ob.has_pending_logs() {
                    return ResolveEnd::More;
                }
                cx.host.rng_reseed(proof);
            }
            // Execute PRNG reseeding and ensemble commitment in an unbroken transition to prevent
            // duplicate reseeding, reusing the post-lookup timestamp when available.
            let now = match self.at {
                Some(at) => at,
                None => cx.clock.now(),
            };
            self.at = Some(now);
            let mut core = cx.core.lock(proof);
            self.rc = match core.conn.as_deref_mut() {
                Some(conn) => {
                    let rc = conn.handle_resolved(self.generation, shuffle, now);
                    if self.teardown {
                        conn.run_pending_teardown(now);
                    }
                    rc
                }
                None => rc::ZINVALIDSTATE,
            };
            self.phase = ResolvePhase::Done;
            if core.pump(cx.flavor, ob, cx.host) {
                return ResolveEnd::More;
            }
        }
        ResolveEnd::Done { rc: self.rc }
    }

    /// Executes only the host resolution phase of the loop without committing the resolved
    /// ensemble.
    ///
    /// Yields [`ResolveEnd::More`] if log records must be drained before calling blocking DNS
    /// routines. Used during initial connection setup (`init.rs`) to validate port syntax and
    /// resolve initial endpoints before constructing the final runtime handle.
    pub(crate) fn run_hosts<T: Tokens>(
        &mut self,
        cx: &ResolveCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> ResolveEnd {
        while self.phase == ResolvePhase::Hosts {
            let Some(spec) = self.hosts.get(self.next) else {
                self.phase = ResolvePhase::Reseed;
                break;
            };
            // Flush pending failure logs to the application before initiating the next blocking
            // DNS lookup.
            if ob.has_pending_logs() {
                return ResolveEnd::More;
            }
            let r = cx.host.resolve(proof, self.next, spec);
            if r.result.as_ref().is_ok_and(|a| !a.is_empty()) {
                self.any_resolved = true;
            }
            // Advance the host cursor prior to protocol dispatch to avoid re-resolving upon
            // barrier resume.
            self.next = self.next.saturating_add(1);
            let now = cx.clock.now();
            self.at = Some(now);
            let mut core = cx.core.lock(proof);
            let Some(conn) = core.conn.as_deref_mut() else {
                self.phase = ResolvePhase::Done;
                self.rc = rc::ZINVALIDSTATE;
                return ResolveEnd::Done { rc: self.rc };
            };
            conn.handle_host_resolved(self.generation, r, now);
            if core.pump(cx.flavor, ob, cx.host) {
                return ResolveEnd::More;
            }
        }
        match self.phase {
            ResolvePhase::Done => ResolveEnd::Done { rc: self.rc },
            ResolvePhase::Hosts | ResolvePhase::Reseed => ResolveEnd::Done { rc: rc::ZOK },
        }
    }
}

/// Execution state tracking a dynamic ensemble reconfiguration initiated via `zoo_set_servers`.
#[derive(Debug)]
pub(crate) struct SetServersRun {
    /// Unique run identifier correlated with `ResumeAt::SetServers`.
    pub(crate) id: u64,
    /// Underlying iterative host resolution state machine.
    pub(crate) run: ResolveRun,
    /// Indicates whether a valid transport connection was established prior to reconfiguration.
    pub(crate) had_transport: bool,
}

/// Execution outcome returned by an individual segment of a dynamic ensemble update.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SetServersEnd {
    /// Caller must drain the outbox and resume execution with the specified run identifier.
    More(u64),
    /// Ensemble reconfiguration has completed.
    Done {
        /// Return code representing the outcome of the update.
        rc: i32,
        /// True if connection migration severed the previously active transport.
        lost: bool,
    },
}

impl SetServersRun {
    /// Initiates a dynamic server ensemble reconfiguration, queueing the first resolution segment.
    ///
    /// Returns `ZINVALIDSTATE` immediately if the underlying connection has already been closed.
    pub(crate) fn start<T: Tokens>(
        cx: &ResolveCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        specs: Vec<HostSpec>,
    ) -> SetServersEnd {
        let now = cx.clock.now();
        let mut core = cx.core.lock(proof);
        let id = core.next_run_id;
        core.next_run_id = id.wrapping_add(1);
        let Some(conn) = core.conn.as_deref_mut() else {
            return SetServersEnd::Done {
                rc: rc::ZINVALIDSTATE,
                lost: false,
            };
        };
        let had_transport = conn.has_transport();
        let specs: Arc<[HostSpec]> = specs.into();
        let generation = conn.set_servers_shared(Arc::clone(&specs), now);
        let run = Self {
            id,
            run: ResolveRun::without_teardown(generation, specs),
            had_transport,
        };
        if core.pump(cx.flavor, ob, cx.host) {
            core.set_servers_runs.push(run);
            return SetServersEnd::More(id);
        }
        drop(core);
        run.step(cx, proof, ob)
    }

    /// Resumes an interrupted ensemble update after the caller has drained the outbox.
    ///
    /// Returns `ZINVALIDSTATE` if `id` does not correspond to an active suspended reconfiguration
    /// run.
    pub(crate) fn resume<T: Tokens>(
        cx: &ResolveCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        id: u64,
    ) -> SetServersEnd {
        let now = cx.clock.now();
        let run = {
            let mut core = cx.core.lock(proof);
            core.resume_stopped(now);
            let at = core.set_servers_runs.iter().position(|r| r.id == id);
            match at {
                Some(at) => core.set_servers_runs.swap_remove(at),
                None => {
                    return SetServersEnd::Done {
                        rc: rc::ZINVALIDSTATE,
                        lost: false,
                    };
                }
            }
        };
        run.step(cx, proof, ob)
    }

    /// Drives the resolution state machine forward, storing suspended runs back in the core.
    fn step<T: Tokens>(
        mut self,
        cx: &ResolveCtx<'_, T>,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
    ) -> SetServersEnd {
        match self.run.run(cx, proof, ob) {
            ResolveEnd::More => {
                let id = self.id;
                cx.core.lock(proof).set_servers_runs.push(self);
                SetServersEnd::More(id)
            }
            ResolveEnd::Done { rc } => {
                let core = cx.core.lock(proof);
                let has = core.conn.as_deref().is_some_and(Connection::has_transport);
                SetServersEnd::Done {
                    rc,
                    lost: self.had_transport && !has,
                }
            }
        }
    }
}

/// Transfers ownership of an interrupted `zoo_set_servers` run to the background I/O loop.
///
/// If an application callback unwound or failed, subsequent interest calculations will
/// resume the suspended resolution using the same generation identifier.
pub(crate) fn adopt_set_servers<T: Tokens>(core: &mut Core<T>, id: u64) {
    let Some(at) = core.set_servers_runs.iter().position(|r| r.id == id) else {
        return;
    };
    let r = core.set_servers_runs.swap_remove(at);
    if r.run.phase == ResolvePhase::Done {
        return;
    }
    let newer = core
        .set_servers
        .as_ref()
        .is_some_and(|(g, _)| *g >= r.run.generation);
    if !newer {
        core.set_servers = Some((r.run.generation, r.run.hosts));
    }
}

/// Applies the results of an asynchronous DNS lookup batch directly to the protocol state machine.
///
/// Ingests individual host resolution records in order, updates the active ensemble, and executes
/// pending transport teardown if a reconnect or server rebalance was triggered.
pub(crate) fn apply_results<T: Tokens>(
    conn: &mut Connection<T>,
    generation: u64,
    results: Vec<HostResolution>,
    shuffle: bool,
    now: zk_proto::Millis,
) -> i32 {
    for r in results {
        conn.handle_host_resolved(generation, r, now);
    }
    let rc = conn.handle_resolved(generation, shuffle, now);
    conn.run_pending_teardown(now);
    rc
}

/// Returns `true` if server address shuffling is enabled by the host configuration.
pub(crate) fn shuffle_on<T: Tokens>(host: &dyn HostLeaves<T>) -> bool {
    !host.conn_permute_disabled()
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::net::SocketAddr;
    use std::sync::Arc;

    use zk_proto::{Action, ClientId, Compat, DriverMode, LogLevel, LogSite, ResolveError};
    use zk_testkit::tokens::{TestTokens, WatcherId};

    use super::*;
    use crate::clock::TestClock;
    use crate::host::{Discarded, connection_hooks};
    use crate::outbox::Item;
    use crate::testutil::{TEST_EAI_NONAME, TestHost, test_config};

    /// Test adapter recording invocations of blocking host leaves to verify call ordering.
    struct Recorder {
        inner: TestHost,
        events: Mutex<Vec<String>>,
    }

    impl Recorder {
        fn new() -> Self {
            Self {
                inner: TestHost::new(),
                events: Mutex::new(Vec::new()),
            }
        }

        fn events(&self) -> Vec<String> {
            self.events.lock_owner().clone()
        }
    }

    impl HostLeaves<TestTokens> for Recorder {
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
            self.events.lock_owner().push("reseed".to_owned());
            self.inner.rng_reseed(proof);
        }
        fn resolve(&self, proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
            let name = String::from_utf8_lossy(spec.host_bytes()).into_owned();
            self.events
                .lock_owner()
                .push(format!("resolve {host} {name}"));
            self.inner.resolve(proof, host, spec)
        }
    }

    /// Test harness fixture encapsulating core state and an active resolution run.
    struct Fixture {
        host: Arc<Recorder>,
        core: Mutex<Core<TestTokens>>,
        clock: TestClock,
        run: ResolveRun,
    }

    fn addr(s: &str) -> Vec<SocketAddr> {
        vec![s.parse().unwrap()]
    }

    fn fixture(setup: impl FnOnce(&TestHost)) -> Fixture {
        let host = Arc::new(Recorder::new());
        setup(&host.inner);
        let dyn_host: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        let (rng, log, dw) = connection_hooks(&dyn_host);
        let mut conn = Connection::new(
            test_config(b"a:1,b:2"),
            Compat::LEGACY_C,
            DriverMode::ST,
            rng,
            log,
            dw,
            zk_proto::Millis(0),
        );
        let Some(Action::Resolve {
            generation, hosts, ..
        }) = conn.poll_action(zk_proto::Millis(0))
        else {
            panic!("a new connection resolves first");
        };
        Fixture {
            host,
            core: Mutex::new(Core::new(Box::new(conn))),
            clock: TestClock::new(zk_proto::Millis(0)),
            run: ResolveRun::new(generation, hosts),
        }
    }

    impl Fixture {
        fn step(&mut self, ob: &mut Outbox<TestTokens>) -> ResolveEnd {
            let cx = ResolveCtx {
                core: &self.core,
                host: &*self.host,
                clock: &self.clock,
                flavor: Flavor::St,
            };
            self.run.run(&cx, &mut Unlocked::new_at_entry(), ob)
        }

        fn errno_hint(&self) -> Option<i32> {
            let mut core = self.core.lock_owner();
            core.conn
                .as_deref_mut()
                .and_then(Connection::take_errno_hint)
        }
    }

    /// Matches DNS resolution failure log records across platforms.
    fn is_gai_line(sites: &[LogSite]) -> bool {
        matches!(sites, [LogSite::ResolveGai | LogSite::ResolveGaiErrno])
    }

    /// Drains all items from the outbox and extracts diagnostic log sites in order.
    fn drain(ob: &mut Outbox<TestTokens>) -> Vec<LogSite> {
        let mut sites = Vec::new();
        while let Some(item) = ob.pop() {
            if let Item::Log(r) = item {
                sites.push(r.site);
            }
        }
        sites
    }

    /// Verifies that hosts resolve in order before the PRNG is reseeded.
    #[test]
    fn hosts_resolve_in_order_then_the_reseed_runs() {
        let mut f = fixture(|h| {
            h.set_addrs(b"a", addr("10.0.0.1:1"));
            h.set_addrs(b"b", addr("10.0.0.2:2"));
        });
        let mut ob = Outbox::new();
        assert_eq!(f.step(&mut ob), ResolveEnd::Done { rc: rc::ZOK });
        assert_eq!(f.host.events(), ["resolve 0 a", "resolve 1 b", "reseed"]);
        assert_eq!(f.run.phase, ResolvePhase::Done);
        // Repeated executions of a completed run return the same result without re-invoking host
        // hooks.
        assert_eq!(f.step(&mut ob), ResolveEnd::Done { rc: rc::ZOK });
        assert_eq!(f.host.inner.resolves(), 2);
        assert_eq!(f.host.inner.reseeds(), 1);
    }

    #[test]
    fn pending_logs_stop_the_loop_before_each_leaf() {
        let mut f = fixture(|_| {});
        let mut ob = Outbox::new();
        // Initial host failure queues a diagnostic log record; execution yields before the next
        // lookup.
        assert_eq!(f.step(&mut ob), ResolveEnd::More);
        assert_eq!(f.host.events(), ["resolve 0 a"]);
        // Execution remains paused until the caller consumes the pending log record.
        assert_eq!(f.step(&mut ob), ResolveEnd::More);
        assert_eq!(f.host.inner.resolves(), 1);
        assert!(is_gai_line(&drain(&mut ob)));
        // If all host lookups fail, PRNG reseed is skipped and system error is reported.
        assert_eq!(
            f.step(&mut ob),
            ResolveEnd::Done {
                rc: rc::ZSYSTEMERROR
            }
        );
        assert_eq!(f.host.events(), ["resolve 0 a", "resolve 1 b"]);
        assert!(is_gai_line(&drain(&mut ob)));
        assert!(f.errno_hint().is_some());
    }

    #[test]
    fn pending_logs_stop_the_loop_before_the_reseed() {
        let mut f = fixture(|h| h.set_addrs(b"a", addr("10.0.0.1:1")));
        let mut ob = Outbox::new();
        assert_eq!(f.step(&mut ob), ResolveEnd::More);
        assert_eq!(f.host.events(), ["resolve 0 a", "resolve 1 b"]);
        assert_eq!(f.run.phase, ResolvePhase::Reseed);
        assert!(is_gai_line(&drain(&mut ob)));
        assert_eq!(f.step(&mut ob), ResolveEnd::Done { rc: rc::ZOK });
        assert_eq!(f.host.events(), ["resolve 0 a", "resolve 1 b", "reseed"]);
    }

    /// Verifies that PRNG reseed is bypassed when ensemble shuffling is explicitly disabled.
    #[test]
    fn no_reseed_without_the_shuffle() {
        let mut f = fixture(|h| {
            h.set_permute_disabled(true);
            h.set_addrs(b"a", addr("10.0.0.1:1"));
            h.set_addrs(b"b", addr("10.0.0.2:2"));
        });
        let mut ob = Outbox::new();
        assert_eq!(f.step(&mut ob), ResolveEnd::Done { rc: rc::ZOK });
        assert_eq!(f.host.events(), ["resolve 0 a", "resolve 1 b"]);
    }

    #[test]
    fn host_stage_alone_stops_before_the_reseed() {
        let mut f = fixture(|h| {
            h.set_addrs(b"a", addr("10.0.0.1:1"));
            h.set_addrs(b"b", addr("10.0.0.2:2"));
        });
        let mut ob = Outbox::new();
        let cx = ResolveCtx {
            core: &f.core,
            host: &*f.host,
            clock: &f.clock,
            flavor: Flavor::St,
        };
        let end = f.run.run_hosts(&cx, &mut Unlocked::new_at_entry(), &mut ob);
        assert_eq!(end, ResolveEnd::Done { rc: rc::ZOK });
        assert_eq!(f.run.phase, ResolvePhase::Reseed);
        assert_eq!(f.host.inner.reseeds(), 0);
    }

    #[test]
    fn a_closed_core_ends_the_run() {
        let mut f = fixture(|_| {});
        drop(f.core.lock_owner().conn.take());
        let mut ob = Outbox::new();
        assert_eq!(
            f.step(&mut ob),
            ResolveEnd::Done {
                rc: rc::ZINVALIDSTATE
            }
        );
        assert_eq!(f.run.phase, ResolvePhase::Done);
    }

    #[test]
    fn deferred_results_apply_in_order() {
        let f = fixture(|_| {});
        let generation = f.run.generation;
        let mut core = f.core.lock_owner();
        let conn = core.conn.as_deref_mut().unwrap();
        let results = vec![
            HostResolution {
                host: 0,
                result: Err(ResolveError {
                    gai_rc: TEST_EAI_NONAME,
                    errno: 0,
                }),
                skipped_families: Vec::new(),
            },
            HostResolution {
                host: 1,
                result: Ok(addr("10.0.0.2:2")),
                skipped_families: Vec::new(),
            },
        ];
        let now = zk_proto::Millis(0);
        assert_eq!(apply_results(conn, generation, results, true, now), rc::ZOK);
        // Applying results from a superseded resolver generation is a safe no-op.
        let stale = apply_results(conn, generation, Vec::new(), true, now);
        assert_eq!(stale, rc::ZOK);
    }

    #[test]
    fn deferred_failures_give_the_system_error() {
        let f = fixture(|_| {});
        let generation = f.run.generation;
        let mut core = f.core.lock_owner();
        let conn = core.conn.as_deref_mut().unwrap();
        let failed = |host| HostResolution {
            host,
            result: Err(ResolveError {
                gai_rc: TEST_EAI_NONAME,
                errno: 0,
            }),
            skipped_families: Vec::new(),
        };
        let now = zk_proto::Millis(0);
        let rc = apply_results(conn, generation, vec![failed(0), failed(1)], true, now);
        assert_eq!(rc, rc::ZSYSTEMERROR);
    }
}
