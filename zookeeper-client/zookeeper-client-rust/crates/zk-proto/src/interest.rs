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

//! Network I/O event interest calculation, action polling, and readiness management.
//!
//! Owns [`InterestState`] and coordinates the progression of the driver event loop iteration:
//! entering via `begin_interest`, draining discrete pending operations via `poll_action`, and
//! finalizing interest registration flags (read, write, poll timeouts) via `finish_interest`
//! in strict accordance with C `zookeeper_interest` semantics.

use std::collections::VecDeque;

use zk_jute::rc;

use crate::action::{Action, Interest, InterestOutcome};
use crate::compat::Backoff;
use crate::config::InterestBits;
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::hosts::ConnectDecision;
use crate::state::Phase;
use crate::time::{Millis, clamp_timeout, until};

/// Execution phase within an active network interest iteration. An iteration runs `Resolve`,
/// `Connect`, `Sasl`, `Timers`, and `Drain` in declaration order. A failed connect skips ahead to
/// `Drain`, and a blocked connect ends the iteration.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) enum IterStep {
    /// Inactive iteration state; `poll_action` yields only pre-enqueued driver actions.
    #[default]
    Idle,
    /// Queries DNS address resolution for ensemble server endpoints.
    Resolve,
    /// Evaluates connection initiation and server selection decisions.
    Connect,
    /// Dispatches SASL authentication negotiation interactions.
    Sasl,
    /// Evaluates heartbeat ping, timeout, and reconnect timer deadlines.
    Timers,
    /// Drains deferred actions, including pending transport closures.
    Drain,
    /// Terminal state of the active iteration following complete action polling.
    Done,
}

/// State tracker governing the execution and outcome of a network interest polling iteration.
pub(crate) struct InterestState {
    /// Current progression stage within the multi-step iteration.
    pub(crate) step: IterStep,
    /// Protocol return code to be returned by `finish_interest`.
    pub(crate) rc: i32,
    /// Indicates whether `finish_interest` should populate concrete `Interest` descriptors or omit
    /// them.
    pub(crate) write_outputs: bool,
    /// Queue of high-priority actions produced outside the structured iteration pipeline.
    pub(crate) queued: VecDeque<Action>,
    /// Indicates whether transport teardown occurred during the current interest pass.
    pub(crate) closed_this_call: bool,
    /// Indicates whether server selection recommended a reconnection backoff interval.
    pub(crate) backoff_wait: bool,
    /// Indicates whether the timer evaluation step enqueued an outbound ping frame.
    pub(crate) ping_queued: bool,
}

impl InterestState {
    /// Constructs a clean, idle interest tracking state.
    pub(crate) fn new() -> Self {
        Self {
            step: IterStep::Idle,
            rc: zk_jute::rc::ZOK,
            write_outputs: true,
            queued: VecDeque::new(),
            closed_this_call: false,
            backoff_wait: false,
            ping_queued: false,
        }
    }
}

impl<T: Tokens> Connection<T> {
    /// Initiates a new driver interest calculation cycle.
    ///
    /// Validates that connection state remains recoverable and that no execution barrier is
    /// currently suspended. Inspects whether previous deadlines were exceeded, resets internal
    /// tracking flags, advances step to `IterStep::Resolve`, and prepares for subsequent
    /// `poll_action` iterations.
    ///
    /// # Errors
    ///
    /// Returns `ZINVALIDSTATE` if the session is unrecoverable or if an execution barrier is
    /// suspended.
    pub fn begin_interest(&mut self, now: Millis) -> Result<(), i32> {
        if self.core.state.is_unrecoverable() || self.out.is_suspended() {
            return Err(rc::ZINVALIDSTATE);
        }
        self.check_deadline_warn(now);
        self.interest.rc = rc::ZOK;
        self.interest.write_outputs = true;
        self.interest.closed_this_call = false;
        self.interest.backoff_wait = false;
        self.interest.ping_queued = false;
        self.hosts.block_connect = false;
        self.interest.step = IterStep::Resolve;
        Ok(())
    }

    /// Retrieves the next actionable driver command, progressing the interest state machine.
    ///
    /// Evaluates scheduled actions across the configured sequence: address resolution, connection
    /// initiation, SASL negotiation handshakes, heartbeat timer expiration, and queued socket
    /// actions. Returns `None` once all operations for the current iteration have been retrieved.
    pub fn poll_action(&mut self, now: Millis) -> Option<Action> {
        if self.out.is_suspended() {
            return None;
        }
        if let Some(action) = self.interest.queued.pop_front() {
            return Some(action);
        }
        if self.close.destroyed {
            // Following destruction, only remaining SASL disposal interactions are dispatched.
            return self.interest_sasl_action();
        }
        loop {
            match self.interest.step {
                IterStep::Idle | IterStep::Done => {
                    // Initial hostname resolution runs proactively before the first poll iteration.
                    if self.hosts.initial && !self.close.closing {
                        if let Some(action) = self.resolve_action(now) {
                            return Some(action);
                        }
                    }
                    return self.interest_sasl_action();
                }
                IterStep::Resolve => {
                    self.interest.step = IterStep::Connect;
                    if let Some(action) = self.resolve_action(now) {
                        return Some(action);
                    }
                }
                IterStep::Connect => {
                    self.interest.step = IterStep::Sasl;
                    // Closing connections never attempt to open new transport sockets.
                    if self.core.has_transport || self.close.closing {
                        continue;
                    }
                    // Connection attempts are blocked if address updates experienced whole-list
                    // failure.
                    if self.hosts.block_connect {
                        self.interest_end_unwritten();
                        return None;
                    }
                    match self.connect_decision(now) {
                        ConnectDecision::Connect(action) => {
                            self.interest.closed_this_call = false;
                            return Some(action);
                        }
                        ConnectDecision::Wait => self.interest.backoff_wait = true,
                        ConnectDecision::Blocked => {
                            self.interest_end_unwritten();
                            return None;
                        }
                        ConnectDecision::Failed => {
                            // Immediate socket allocation failure halts connect attempts for this
                            // pass.
                            self.interest.step = IterStep::Drain;
                        }
                    }
                }
                IterStep::Sasl => {
                    if let Some(action) = self.interest_sasl_action() {
                        return Some(action);
                    }
                    self.interest.step = IterStep::Timers;
                }
                IterStep::Timers => {
                    self.interest.step = IterStep::Drain;
                    if let Some(action) = self.run_timers(now) {
                        return Some(action);
                    }
                }
                IterStep::Drain => {
                    self.interest.step = IterStep::Done;
                    return None;
                }
            }
            if let Some(action) = self.interest.queued.pop_front() {
                return Some(action);
            }
        }
    }

    /// Concludes the active interest cycle, calculating final I/O readiness flags and poll
    /// timeouts.
    ///
    /// When a transport socket is active, evaluates whether the receive decoder is unblocked and
    /// whether outbound frames await transmission, recording the nearest impending timer deadline.
    /// In disconnected states, computes appropriate reconnect backoff timeouts.
    pub fn finish_interest(&mut self, now: Millis) -> InterestOutcome {
        let rc = self.interest.rc;
        let outputs = if !self.interest.write_outputs {
            None
        } else if self.core.has_transport {
            let timeout_ms = self.transport_timeout_ms(now);
            // In legacy C precedence, deadline timestamps are only calculated when a socket is
            // open.
            self.timers.next_deadline = Some(now.saturating_add_ms(u64::from(timeout_ms)));
            Some(Interest {
                has_socket: !self.interest.closed_this_call,
                // A stalled decoder suppresses read interest to avoid tight busy-polling loops.
                read: !self.recv.stalled,
                write: self.write_interest(),
                timeout_ms,
            })
        } else {
            let timeout_ms = match self.compat.reconnect_backoff {
                Backoff::OneShotSkip if self.interest.backoff_wait => clamp_timeout(
                    i64::from(self.core.recv_timeout)
                        .checked_div(60)
                        .unwrap_or(0),
                ),
                Backoff::Deadline => self
                    .hosts
                    .not_before
                    .map_or(0, |nb| clamp_timeout(until(now, nb))),
                Backoff::OneShotSkip => 0,
            };
            Some(Interest {
                has_socket: false,
                read: false,
                write: false,
                timeout_ms,
            })
        };
        self.interest.step = IterStep::Idle;
        InterestOutcome { rc, outputs }
    }

    /// Indicates whether the timer evaluation step of the current interest iteration queued a ping
    /// frame.
    ///
    /// Enables single threaded drivers to immediately flush outbound buffers, matching C
    /// `send_ping` behavior.
    #[must_use]
    pub fn interest_queued_ping(&self) -> bool {
        self.interest.ping_queued
    }

    /// Determines whether the active transport requires socket write interest registration.
    ///
    /// Evaluates protocol state against configured `InterestBits` policy: under `LegacyExact`,
    /// asserts write readiness during connect handshakes or when buffered data is pending; under
    /// `Precise`, asserts write readiness only when outbound frame bytes are immediately
    /// transmittable.
    pub(crate) fn write_interest(&self) -> bool {
        if !self.core.has_transport {
            return false;
        }
        match self.mode.interest {
            InterestBits::LegacyExact => match self.core.phase {
                Phase::TlsHandshaking { .. } | Phase::TlsConfigFailed => false,
                phase => {
                    let ready = self.core.state.is_connected() || matches!(phase, Phase::Sasl(_));
                    (self.requests.has_unsent() && ready)
                        || phase == Phase::TcpConnecting
                        || self.requests.connect_frame_unwritten()
                }
            },
            InterestBits::Precise => self.interest_would_transmit(),
        }
    }

    /// Determines whether `poll_transmit` has unacknowledged, sendable payload bytes available for
    /// immediate copying.
    fn interest_would_transmit(&self) -> bool {
        if !self.requests.has_unsent() {
            return false;
        }
        let mut copied = self.requests.unacked;
        for frame in &self.requests.tx {
            if !self.may_send(frame.kind) {
                return false;
            }
            let left = frame.len().saturating_sub(frame.written);
            if left > copied {
                return true;
            }
            copied = copied.saturating_sub(left);
        }
        false
    }

    /// Terminates the current action polling sequence, suppressing output descriptor generation in
    /// `finish_interest`.
    fn interest_end_unwritten(&mut self) {
        self.interest.write_outputs = false;
        self.interest.step = IterStep::Done;
    }

    /// Retrieves the next scheduled SASL authentication interaction, if any are pending.
    fn interest_sasl_action(&mut self) -> Option<Action> {
        if self.sasl.calls.is_empty() {
            return None;
        }
        self.poll_sasl_action()
    }

    /// Enqueues a `CloseTransport` driver action and marks the current transport closed for socket
    /// interest accounting.
    pub(crate) fn queue_close_transport(&mut self) {
        let queued = self
            .interest
            .queued
            .iter()
            .any(|a| matches!(a, Action::CloseTransport));
        if !queued {
            self.interest.queued.push_back(Action::CloseTransport);
        }
        self.interest.closed_this_call = true;
    }
}

/// Shared test fixtures, mock RNG generators, and harness utilities for interest, timer, and server
/// testing.
#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::panic,
    clippy::arithmetic_side_effects
)]
pub(crate) mod test_support {
    use std::collections::VecDeque;
    use std::net::SocketAddr;
    use std::sync::{Arc, Mutex};

    use crate::compat::Compat;
    use crate::config::{Config, DriverMode, Endpoint, HostSpec, HostToken};
    use crate::connection::Connection;
    use crate::hooks::{AllLevels, NoDefaultWatcher, Rng, Tokens};
    use crate::legacy::connect::HostSpec as LegacyHostSpec;
    use crate::log::{LogRecord, LogSite};
    use crate::output::Output;
    use crate::time::Millis;

    /// Concrete [`Tokens`] marker implementation binding integer identifiers for unit test
    /// assertions.
    pub(crate) struct Tk;
    impl Tokens for Tk {
        type Completion = u32;
        type Watcher = u32;
        type Auth = u32;
    }

    /// Pre-scripted pseudorandom return sequences and invocation counters for [`TestRng`].
    #[derive(Default)]
    pub(crate) struct Draws {
        /// Sequenced values returned by `random`. An empty queue defaults to 0.
        pub(crate) random: VecDeque<i64>,
        /// Sequenced floating-point values returned by `drand48`. An empty queue defaults to 0.0.
        pub(crate) drand: VecDeque<f64>,
        /// Cumulative count of `random` invocations.
        pub(crate) random_calls: usize,
        /// Cumulative count of `drand48` invocations.
        pub(crate) drand_calls: usize,
        /// Cumulative count of `on_resolution` callbacks observed.
        pub(crate) resolutions: usize,
    }

    /// Deterministic pseudorandom number generator backed by pre-scripted queues for unit testing.
    pub(crate) struct TestRng(pub(crate) Arc<Mutex<Draws>>);
    impl Rng for TestRng {
        fn on_resolution(&mut self) {
            self.0.lock().unwrap().resolutions += 1;
        }
        fn random(&mut self) -> i64 {
            let mut d = self.0.lock().unwrap();
            d.random_calls += 1;
            d.random.pop_front().unwrap_or(0)
        }
        fn drand48(&mut self) -> f64 {
            let mut d = self.0.lock().unwrap();
            d.drand_calls += 1;
            d.drand.pop_front().unwrap_or(0.0)
        }
    }

    /// Parses a socket address literal from text.
    pub(crate) fn sa(s: &str) -> SocketAddr {
        s.parse().unwrap()
    }

    /// Constructs a legacy hostname and port specification.
    pub(crate) fn host(name: &str, port: u16) -> HostSpec {
        HostSpec::Legacy(LegacyHostSpec {
            host: name.as_bytes().to_vec(),
            service: port.to_string().into_bytes(),
            port: i32::from(port),
        })
    }

    /// Constructs a resolved endpoint tagged with a specific host token index.
    pub(crate) fn ep(addr: &str, index: usize, name: &str) -> Endpoint {
        Endpoint {
            addr: sa(addr),
            host: HostToken {
                index,
                name: name.as_bytes().to_vec(),
            },
        }
    }

    /// Instantiates a test connection configured with default hosts `a:2181` and `b:2181`.
    pub(crate) fn conn_with(
        compat: Compat,
        mode: DriverMode,
        edit: impl FnOnce(&mut Config),
    ) -> (Connection<Tk>, Arc<Mutex<Draws>>) {
        let mut cfg = Config::legacy(30_000);
        cfg.hosts = vec![host("a", 2181), host("b", 2181)];
        cfg.host_text = zk_jute::JString::Bytes(b"a:2181,b:2181".to_vec());
        edit(&mut cfg);
        let draws = Arc::new(Mutex::new(Draws::default()));
        let conn = Connection::new(
            cfg,
            compat,
            mode,
            Box::new(TestRng(Arc::clone(&draws))),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        );
        (conn, draws)
    }

    /// Instantiates a test connection under default legacy C compatibility settings.
    pub(crate) fn conn() -> Connection<Tk> {
        conn_with(Compat::LEGACY_C, DriverMode::ST, |_| {}).0
    }

    /// Drains and collects all structured log records currently queued in outbound outputs.
    pub(crate) fn logs(conn: &mut Connection<Tk>) -> Vec<LogRecord> {
        let mut out = Vec::new();
        while let Some(o) = conn.poll_output() {
            if let Output::Log(r) = o {
                out.push(r);
            }
        }
        out
    }

    /// Drains and returns the site identifiers of all queued log records.
    pub(crate) fn sites(conn: &mut Connection<Tk>) -> Vec<LogSite> {
        logs(conn).into_iter().map(|r| r.site).collect()
    }

    /// Simulates address resolution responses for queued hosts across a generation epoch.
    pub(crate) fn answer(
        conn: &mut Connection<Tk>,
        generation: u64,
        per_host: &[Option<&str>],
        now: Millis,
    ) -> i32 {
        for (i, a) in per_host.iter().enumerate() {
            let result = match a {
                Some(addr) => Ok(vec![sa(addr)]),
                None => Err(crate::action::ResolveError {
                    gai_rc: -2,
                    errno: 2,
                }),
            };
            conn.handle_host_resolved(
                generation,
                crate::action::HostResolution {
                    host: i,
                    result,
                    skipped_families: Vec::new(),
                },
                now,
            );
        }
        conn.handle_resolved(generation, false, now)
    }

    /// Executes the initial resolution step, populating server addresses `10.0.0.1:2181` and
    /// `10.0.0.2:2181`.
    pub(crate) fn init_two(conn: &mut Connection<Tk>) {
        let Some(crate::action::Action::Resolve {
            generation,
            initial: true,
            ..
        }) = conn.poll_action(Millis(0))
        else {
            panic!("no initial Resolve");
        };
        let rc = answer(
            conn,
            generation,
            &[Some("10.0.0.1:2181"), Some("10.0.0.2:2181")],
            Millis(0),
        );
        assert_eq!(rc, zk_jute::rc::ZOK);
        let _ = logs(conn);
    }

    /// Drives a complete interest iteration for a disconnected connection, answering resolutions
    /// with valid addresses.
    pub(crate) fn iterate(
        conn: &mut Connection<Tk>,
        now: Millis,
    ) -> (Vec<crate::action::Action>, crate::action::InterestOutcome) {
        conn.begin_interest(now).unwrap();
        let mut actions = Vec::new();
        while let Some(a) = conn.poll_action(now) {
            if let crate::action::Action::Resolve { generation, .. } = a {
                answer(
                    conn,
                    generation,
                    &[Some("10.0.0.1:2181"), Some("10.0.0.2:2181")],
                    now,
                );
            } else {
                actions.push(a);
            }
        }
        (actions, conn.finish_interest(now))
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
    use super::test_support::{conn, conn_with, iterate, sa, sites};
    use super::*;
    use crate::compat::Compat;
    use crate::config::DriverMode;
    use crate::log::LogSite;
    use crate::requests::{FrameKind, OutFrame};
    use crate::state::State;

    /// Extracts the target socket address from a `Connect` action.
    fn connect_addr(a: &Action) -> std::net::SocketAddr {
        match a {
            Action::Connect { endpoint, .. } => endpoint.addr,
            other => panic!("not a Connect: {other:?}"),
        }
    }

    #[test]
    fn full_iteration_without_transport_resolves_then_connects() {
        let mut c = conn();
        c.begin_interest(Millis(0)).unwrap();
        let Some(Action::Resolve {
            generation,
            hosts,
            initial,
        }) = c.poll_action(Millis(0))
        else {
            panic!("expected Resolve");
        };
        assert!(initial);
        assert_eq!(hosts.len(), 2);
        let rc = super::test_support::answer(
            &mut c,
            generation,
            &[Some("10.0.0.1:2181"), Some("10.0.0.2:2181")],
            Millis(0),
        );
        assert_eq!(rc, rc::ZOK);
        let Some(Action::Connect { endpoint, tls }) = c.poll_action(Millis(0)) else {
            panic!("expected Connect");
        };
        assert_eq!(endpoint.addr, sa("10.0.0.1:2181"));
        assert_eq!(endpoint.host.name, b"a".to_vec());
        assert_eq!(tls, None);
        assert_eq!(c.current_server(), Some(sa("10.0.0.1:2181")));
        assert!(c.poll_action(Millis(0)).is_none());
        let out = c.finish_interest(Millis(0));
        assert_eq!(out.rc, rc::ZOK);
        assert_eq!(
            out.outputs,
            Some(Interest {
                has_socket: false,
                read: false,
                write: false,
                timeout_ms: 0,
            })
        );
        assert_eq!(c.interest.step, IterStep::Idle);

        // Under legacy cadence, address resolution is re-evaluated on subsequent iterations.
        c.begin_interest(Millis(1)).unwrap();
        let Some(Action::Resolve { initial, .. }) = c.poll_action(Millis(1)) else {
            panic!("expected Resolve");
        };
        assert!(!initial);
    }

    #[test]
    fn begin_interest_rejects_unrecoverable_state() {
        let mut c = conn();
        c.core.state = State::ExpiredSession;
        assert_eq!(c.begin_interest(Millis(0)), Err(rc::ZINVALIDSTATE));
        assert_eq!(c.interest.step, IterStep::Idle);
    }

    /// Verifies that whole-list DNS resolution failure without an active transport suppresses
    /// outputs and halts connection.
    #[test]
    fn whole_list_failure_legacy_rc_preserved_blocks_and_hides_outputs() {
        let mut c = conn();
        super::test_support::init_two(&mut c);
        c.hosts.delay = true;
        c.begin_interest(Millis(5)).unwrap();
        let Some(Action::Resolve { generation, .. }) = c.poll_action(Millis(5)) else {
            panic!("expected Resolve");
        };
        let rc = super::test_support::answer(&mut c, generation, &[None, None], Millis(5));
        assert_eq!(rc, rc::ZSYSTEMERROR);
        assert_eq!(c.take_errno_hint(), Some(2));
        assert!(c.poll_action(Millis(5)).is_none());
        let out = c.finish_interest(Millis(5));
        assert_eq!(out.rc, rc::ZSYSTEMERROR);
        assert_eq!(out.outputs, None);
        // Because the C client returns prior to evaluating the reconnection delay flag, `delay`
        // remains asserted.
        assert!(c.hosts.delay);
        let s = sites(&mut c);
        assert_eq!(s.len(), 2);
        assert!(!s.contains(&LogSite::DelayingConnection));
        // Previously resolved server endpoints remain intact in the active address set.
        assert_eq!(c.hosts.servers.addrs.len(), 2);
    }

    /// Verifies that DNS resolution failure with an active transport preserves the resolver error
    /// code while retaining outputs.
    #[test]
    fn whole_list_failure_with_transport_keeps_rc_and_writes_outputs() {
        let mut c = conn();
        super::test_support::init_two(&mut c);
        c.core.has_transport = true;
        c.core.state = State::Connected;
        c.core.phase = Phase::Established;
        c.begin_interest(Millis(1000)).unwrap();
        let Some(Action::Resolve { generation, .. }) = c.poll_action(Millis(1000)) else {
            panic!("expected Resolve");
        };
        let rc = super::test_support::answer(&mut c, generation, &[None, None], Millis(1000));
        assert_eq!(rc, rc::ZSYSTEMERROR);
        assert!(!c.hosts.block_connect);
        assert!(c.poll_action(Millis(1000)).is_none());
        let out = c.finish_interest(Millis(1000));
        assert_eq!(out.rc, rc::ZSYSTEMERROR);
        assert_eq!(
            out.outputs,
            Some(Interest {
                has_socket: true,
                read: true,
                write: false,
                timeout_ms: 9_000,
            })
        );
        assert_eq!(c.timers.next_deadline, Some(Millis(10_000)));
    }

    /// Verifies that `OneShotSkip` backoff enforces a single iteration pause after exhausting a
    /// full cycle.
    #[test]
    fn one_shot_skip_waits_one_iteration_after_a_full_pass() {
        let mut c = conn();
        super::test_support::init_two(&mut c);
        // The initial pass traverses the reconfigured `new` vector before rotating across `addrs`
        // (A, B).
        let mut seen = Vec::new();
        let mut now = 0;
        let mut waits = Vec::new();
        while seen.len() < 6 {
            now += 10;
            let (actions, out) = iterate(&mut c, Millis(now));
            match actions.first() {
                Some(a) => {
                    seen.push(connect_addr(a));
                    assert_eq!(out.outputs.unwrap().timeout_ms, 0);
                    // Simulate transport connection failure triggering teardown stage T5.
                    c.hosts_on_handle_error(Millis(now));
                }
                None => {
                    waits.push(seen.len());
                    assert_eq!(out.outputs.unwrap().timeout_ms, 500);
                    assert!(sites(&mut c).contains(&LogSite::DelayingConnection));
                }
            }
        }
        let a = sa("10.0.0.1:2181");
        let b = sa("10.0.0.2:2181");
        // Under `SingleAdvance`, each connection failure advances the server cursor exactly once.
        assert_eq!(seen, vec![a, b, a, b, a, b]);
        // Exhausting the initial `new` vector imposes no delay; subsequent full passes across
        // `addrs` trigger a one-shot pause.
        assert_eq!(waits, vec![4]);
    }

    /// Verifies that server selection advances to the next candidate address upon transport
    /// connection failure.
    #[test]
    fn single_advance_reports_the_peeked_server_after_a_failure() {
        let mut c = conn();
        super::test_support::init_two(&mut c);
        c.hosts.servers.reconfig = false;
        c.cycle_next_server();
        assert_eq!(c.current_server(), Some(sa("10.0.0.1:2181")));
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.current_server(), Some(sa("10.0.0.2:2181")));
        assert!(!c.hosts.delay);
        c.cycle_next_server();
        assert_eq!(c.current_server(), Some(sa("10.0.0.2:2181")));
        c.hosts_on_handle_error(Millis(0));
        assert_eq!(c.current_server(), Some(sa("10.0.0.1:2181")));
        assert!(c.hosts.delay);
    }

    #[test]
    fn deadline_backoff_pauses_until_not_before() {
        let compat = Compat {
            reconnect_backoff: crate::compat::Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut c, draws) = conn_with(compat, DriverMode::ST, |cfg| {
            cfg.reconnect_delay_ms = 1000;
            cfg.reconnect_jitter_pct = 10;
        });
        super::test_support::init_two(&mut c);
        c.hosts.servers.reconfig = false;
        c.hosts.servers.addrs.reset();
        let _ = c.hosts.servers.addrs.next_item();
        let _ = c.hosts.servers.addrs.next_item();
        // With jitter bound j = 100, a random draw of 150 produces a +50ms offset.
        draws.lock().unwrap().random.push_back(150);
        c.hosts_on_handle_error(Millis(5000));
        assert_eq!(draws.lock().unwrap().random_calls, 1);
        assert!(!c.hosts.delay);
        assert_eq!(c.hosts.not_before, Some(Millis(6050)));

        let (actions, out) = iterate(&mut c, Millis(5000));
        assert!(actions.is_empty());
        assert_eq!(out.outputs.unwrap().timeout_ms, 1050);
        assert!(!sites(&mut c).contains(&LogSite::DelayingConnection));

        let (actions, out) = iterate(&mut c, Millis(6049));
        assert!(actions.is_empty());
        assert_eq!(out.outputs.unwrap().timeout_ms, 1);

        let (actions, _) = iterate(&mut c, Millis(6050));
        assert_eq!(connect_addr(&actions[0]), sa("10.0.0.1:2181"));
        assert_eq!(c.hosts.not_before, None);
    }

    #[test]
    fn deadline_backoff_with_zero_delay_draws_nothing() {
        let compat = Compat {
            reconnect_backoff: crate::compat::Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut c, draws) = conn_with(compat, DriverMode::ST, |_| {});
        super::test_support::init_two(&mut c);
        c.hosts.servers.addrs.reset();
        let _ = c.hosts.servers.addrs.next_item();
        let _ = c.hosts.servers.addrs.next_item();
        c.hosts_on_handle_error(Millis(5000));
        assert_eq!(draws.lock().unwrap().random_calls, 0);
        assert_eq!(c.hosts.not_before, None);
        let (actions, _) = iterate(&mut c, Millis(5000));
        assert_eq!(actions.len(), 1);
    }

    #[test]
    fn finish_interest_timeouts_per_case() {
        // Disconnected without backoff yields zero poll timeout.
        let mut c = conn();
        super::test_support::init_two(&mut c);
        c.interest.step = IterStep::Done;
        let out = c.finish_interest(Millis(0));
        assert_eq!(out.outputs.unwrap().timeout_ms, 0);
        assert_eq!(c.timers.next_deadline, None);

        // OneShotSkip backoff wait yields a timeout of T/60.
        c.interest.backoff_wait = true;
        assert_eq!(
            c.finish_interest(Millis(0)).outputs.unwrap().timeout_ms,
            500
        );
        c.interest.backoff_wait = false;

        // Deadline pause yields duration until `not_before`.
        let compat = Compat {
            reconnect_backoff: crate::compat::Backoff::Deadline,
            ..Compat::LEGACY_C
        };
        let (mut d, _) = conn_with(compat, DriverMode::ST, |_| {});
        d.hosts.not_before = Some(Millis(700));
        d.interest.backoff_wait = true;
        assert_eq!(
            d.finish_interest(Millis(200)).outputs.unwrap().timeout_ms,
            500
        );
        assert_eq!(
            d.finish_interest(Millis(900)).outputs.unwrap().timeout_ms,
            0
        );

        // Active transport yields calculated transport timeout and records deadline timestamp.
        c.core.has_transport = true;
        c.core.state = State::Connecting;
        c.core.phase = Phase::TcpConnecting;
        let out = c.finish_interest(Millis(100));
        assert_eq!(
            out.outputs,
            Some(Interest {
                has_socket: true,
                read: true,
                write: true,
                timeout_ms: 10_000,
            })
        );
        assert_eq!(c.timers.next_deadline, Some(Millis(10_100)));

        // Output descriptor is omitted when `write_outputs` flag is unasserted.
        c.interest.write_outputs = false;
        assert_eq!(c.finish_interest(Millis(100)).outputs, None);
    }

    #[test]
    fn stalled_decoder_clears_read_interest() {
        let mut c = conn();
        super::test_support::init_two(&mut c);
        c.core.has_transport = true;
        c.core.state = State::Connected;
        c.core.phase = Phase::Established;
        c.interest.step = IterStep::Done;
        assert!(c.finish_interest(Millis(0)).outputs.unwrap().read);
        // Framing defect stalling the decoder suppresses read interest under legacy compatibility.
        let r = c.handle_read(&[0xFF, 0xFF, 0xFF, 0xFF, 1, 2], Millis(0));
        assert_eq!(r.consumed, 4);
        assert!(c.recv.stalled);
        let out = c.finish_interest(Millis(0)).outputs.unwrap();
        assert!(!out.read);
        assert!(out.has_socket);
        // Receive timer remains active ensuring finite timeout duration.
        assert!(out.timeout_ms > 0);
    }

    #[test]
    fn closed_transport_reports_no_socket() {
        let mut c = conn();
        c.hosts.initial = false;
        c.hosts.resolve_due = false;
        c.begin_interest(Millis(0)).unwrap();
        c.queue_close_transport();
        assert!(matches!(
            c.poll_action(Millis(0)),
            Some(Action::CloseTransport)
        ));
        // Empty action queue concludes iteration without socket descriptor.
        c.interest.step = IterStep::Drain;
        assert!(c.poll_action(Millis(0)).is_none());
        let out = c.finish_interest(Millis(0));
        assert!(!out.outputs.unwrap().has_socket);
        // Closed transport status is preserved even if transport handle remains recorded.
        c.core.has_transport = true;
        c.interest.closed_this_call = true;
        assert!(!c.finish_interest(Millis(0)).outputs.unwrap().has_socket);
    }

    #[test]
    fn write_interest_legacy_exact() {
        let mut c = conn();
        c.core.has_transport = true;
        c.core.state = State::Connecting;
        c.core.phase = Phase::TcpConnecting;
        assert!(c.write_interest());

        c.core.state = State::SslConnecting;
        c.core.phase = Phase::TlsHandshaking {
            deadline: Millis(10),
        };
        c.requests
            .push_tail(OutFrame::plain(FrameKind::Ping, vec![0; 12]));
        assert!(!c.write_interest());
        c.core.phase = Phase::TlsConfigFailed;
        assert!(!c.write_interest());

        // Associating phase with pending unwritten Connect frame asserts write interest.
        c.requests.tx.clear();
        c.core.state = State::Associating;
        c.core.phase = Phase::Associating;
        assert!(!c.write_interest());
        c.requests
            .push_tail(OutFrame::plain(FrameKind::Connect, vec![0; 4]));
        assert!(c.write_interest());

        // Connected state with pending unsent frames asserts write interest.
        c.requests.tx.clear();
        c.core.state = State::Connected;
        c.core.phase = Phase::Established;
        assert!(!c.write_interest());
        c.requests
            .push_tail(OutFrame::plain(FrameKind::Ping, vec![0; 12]));
        assert!(c.write_interest());

        // Disconnected state without active SASL negotiation suppresses write interest.
        c.core.state = State::NotConnected;
        c.core.phase = Phase::Idle;
        assert!(!c.write_interest());
    }

    #[test]
    fn write_interest_precise() {
        let (mut c, _) = conn_with(Compat::LEGACY_C, DriverMode::MT, |_| {});
        // In connecting state, Precise mode does not assert write interest until bytes are
        // copyable.
        c.core.has_transport = true;
        c.core.state = State::Connecting;
        c.core.phase = Phase::TcpConnecting;
        assert!(!c.write_interest());
        // Frames whose payload bytes are fully acknowledged require no write interest.
        let mut f = OutFrame::plain(FrameKind::Ping, vec![0; 12]);
        f.written = 12;
        c.requests.tx.push_back(f);
        c.core.state = State::Connected;
        c.core.phase = Phase::Established;
        assert!(!c.write_interest());
        // Absence of active transport suppresses write interest regardless of pending queues.
        c.requests.tx.clear();
        c.requests
            .push_tail(OutFrame::plain(FrameKind::Ping, vec![0; 12]));
        c.core.has_transport = false;
        assert!(!c.write_interest());
    }

    #[test]
    fn queued_actions_go_first_and_idle_yields_only_them() {
        let mut c = conn();
        c.hosts.initial = false;
        c.interest.queued.push_back(Action::CloseTransport);
        assert!(matches!(
            c.poll_action(Millis(0)),
            Some(Action::CloseTransport)
        ));
        assert!(c.poll_action(Millis(0)).is_none());
    }

    #[test]
    fn deadline_warn_threshold() {
        let mut c = conn();
        c.timers.next_deadline = Some(Millis(1000));
        c.begin_interest(Millis(1200)).unwrap();
        assert!(sites(&mut c).is_empty());
        c.begin_interest(Millis(1201)).unwrap();
        let logs = super::test_support::logs(&mut c);
        assert_eq!(logs.len(), 1);
        assert_eq!(logs[0].site, LogSite::DeadlineExceeded);
        assert_eq!(logs[0].args, vec![crate::log::LogArg::I64(201)]);
        // Minimum latency threshold scales to T/10 when configured below 200ms.
        c.core.recv_timeout = 1000;
        c.begin_interest(Millis(1101)).unwrap();
        assert_eq!(sites(&mut c), vec![LogSite::DeadlineExceeded]);
        c.begin_interest(Millis(1100)).unwrap();
        assert!(sites(&mut c).is_empty());
    }
}
