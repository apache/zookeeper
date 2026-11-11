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

//! Protocol deadline evaluation, heartbeat scheduling, and read-only probe timers.
//!
//! Manages connection-level time tracking via [`Timers`]. Because the state engine
//! operates purely on external discrete inputs without system clock queries, all timeout
//! evaluations compare caller supplied monotonic millisecond timestamps (`now`) against
//! recorded I/O timestamps to drive keepalive pings, receive timeouts, and quorum probes.

use zk_jute::{opcode, xid};

use crate::action::Action;
use crate::compat::CloseMode;
use crate::connection::Connection;
use crate::errno::ETIMEDOUT;
use crate::hooks::Tokens;
use crate::log::{LogArg, LogSite};
use crate::requests::{FrameKind, OutFrame};
use crate::state::{Cause, Phase, State};
use crate::time::{Millis, clamp_timeout, elapsed, third, two_thirds, until};

/// Initial probe interval (200 ms) for checking read-write quorum availability when connected in
/// read-only mode.
pub(crate) const RW_PROBE_MIN_MS: u32 = 200;
/// Maximum backoff ceiling (60 seconds) for read-write ensemble availability probes.
pub(crate) const RW_PROBE_MAX_MS: u32 = 60_000;
/// Lateness threshold (200 ms) beyond which event loop scheduling delays trigger diagnostic
/// warning logs.
pub(crate) const DEADLINE_WARN_MAX_MS: i64 = 200;

/// Monotonic timestamp ledger tracking protocol transmission, reception, and heartbeat deadlines.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Timers {
    /// Timestamp when the most recent complete wire frame was received from the ensemble.
    pub(crate) last_recv: Millis,
    /// Timestamp when the most recent complete wire frame was fully written to the transport.
    pub(crate) last_send: Millis,
    /// Timestamp when the most recent heartbeat ping frame was queued for transmission.
    pub(crate) last_ping: Millis,
    /// Timestamp when the most recent background `"isro"` read-write probe was initiated.
    pub(crate) last_ping_rw: Millis,
    /// Current dynamic probe backoff interval (ranging from 200 ms up to 60,000 ms).
    pub(crate) rw_timeout_ms: u32,
    /// Timestamp capturing the initiation of an asynchronous TCP connection attempt
    /// (`EINPROGRESS`).
    pub(crate) connect_started_at: Option<Millis>,
    /// Calculated upcoming deadline timestamp emitted during the previous interest calculation
    /// round.
    pub(crate) next_deadline: Option<Millis>,
}

impl Timers {
    /// Initializes timer timestamps synchronized to the specified starting epoch timestamp.
    pub(crate) fn new(now: Millis) -> Self {
        Self {
            last_recv: now,
            last_send: now,
            last_ping: now,
            last_ping_rw: now,
            rw_timeout_ms: RW_PROBE_MIN_MS,
            connect_started_at: None,
            next_deadline: None,
        }
    }

    /// Records reception of a complete inbound protocol frame, resetting the idle receive timer.
    pub(crate) fn on_recv(&mut self, now: Millis) {
        self.last_recv = now;
    }

    /// Records completion of an outbound frame transmission, resetting the idle send timer.
    pub(crate) fn on_send(&mut self, now: Millis) {
        self.last_send = now;
    }

    /// Resets all protocol timers upon initiating a new transport connection attempt.
    pub(crate) fn reset_for_connect(&mut self, now: Millis) {
        self.last_recv = now;
        self.last_send = now;
        self.last_ping = now;
        self.last_ping_rw = now;
        self.rw_timeout_ms = RW_PROBE_MIN_MS;
    }
}

impl<T: Tokens> Connection<T> {
    /// Evaluates protocol timeouts and schedules heartbeat pings during interest calculation.
    ///
    /// State machine invariant rules enforced:
    /// - Receive timeout: If idle receive time exceeds two-thirds of session timeout (`T * 2 / 3`),
    ///   triggers transport teardown with [`Cause::RecvTimeout`] and emits `ETIMEDOUT`.
    /// - TCP connect timeout: When configured, bounds TCP connection establishment to
    ///   `connect_timeout_ms`.
    /// - TLS handshake timeout: Enforces strict deadline expiration on cryptographic negotiation.
    /// - Heartbeat keepalive ping: When established and idle send time exceeds one-third session
    ///   timeout (`T / 3`), appends a `PING` frame to the send queue if the pending queue is
    ///   drained (or `ping_when_outstanding` is asserted). Deduplication ensures at most one ping
    ///   is queued at any time.
    /// - Read-only probe: When connected to a read-only server, initiates periodic `"isro"` probes
    ///   with exponential backoff to discover read-write quorum recovery.
    pub(crate) fn run_timers(&mut self, now: Millis) -> Option<Action> {
        if !self.core.has_transport {
            return None;
        }
        let t = self.core.recv_timeout;
        let tcp_connecting = self.core.phase == Phase::TcpConnecting;

        // Terminal timeout conditions trigger immediate transport teardown.
        if let (true, Some(bound)) = (tcp_connecting, self.cfg.connect_timeout_ms) {
            let started = self.timers.connect_started_at.unwrap_or(now);
            if elapsed(now, started) >= i64::from(bound) {
                self.timers_expire(Cause::RecvTimeout, now);
                return None;
            }
        } else if self.timers_recv_left_ms(now) <= 0 {
            self.timers_expire(Cause::RecvTimeout, now);
            return None;
        }
        if let Phase::TlsHandshaking { deadline } = self.core.phase {
            if now >= deadline {
                self.timers_expire(Cause::TlsHandshakeTimeout, now);
                return None;
            }
        }

        // Prevent outbound ping proliferation: retain at most one ping in the transmission
        // pipeline.
        if self.core.state.is_connected()
            && third(t).saturating_sub(elapsed(now, self.timers.last_send)) <= 0
            && (self.requests.fifo.is_empty() || self.compat.ping_when_outstanding)
            && !self.requests.tx.iter().any(|f| f.kind == FrameKind::Ping)
        {
            self.queue_ping(now);
            self.interest.ping_queued = true;
        }

        if self.core.state == State::ReadOnly
            && elapsed(now, self.timers.last_ping_rw) >= i64::from(self.timers.rw_timeout_ms)
        {
            self.timers.last_ping_rw = now;
            self.timers.rw_timeout_ms = self
                .timers
                .rw_timeout_ms
                .saturating_mul(2)
                .clamp(RW_PROBE_MIN_MS, RW_PROBE_MAX_MS);
            let endpoint = self.probe_endpoint()?;
            let tls = self.cfg.tls.then_some(self.compat.tls_verify);
            return Some(Action::ProbeRw { endpoint, tls });
        }
        None
    }

    /// Sets the `ETIMEDOUT` error hint and initiates transport teardown due to timer expiration.
    fn timers_expire(&mut self, cause: Cause, now: Millis) {
        self.set_errno_hint(ETIMEDOUT);
        self.interest.rc = self.teardown(cause, ETIMEDOUT, now);
    }

    /// Computes the millisecond duration remaining before the receive timeout deadline expires
    /// (`T * 2 / 3 - elapsed`).
    pub(crate) fn timers_recv_left_ms(&self, now: Millis) -> i64 {
        two_thirds(self.core.recv_timeout).saturating_sub(elapsed(now, self.timers.last_recv))
    }

    /// Computes the extent in milliseconds by which the receive deadline has lapsed.
    pub(crate) fn timers_recv_overdue_ms(&self, now: Millis) -> i64 {
        self.timers_recv_left_ms(now).saturating_neg()
    }

    /// Computes the bounded millisecond polling timeout for the current interest calculation round.
    ///
    /// Computes `max(0, min(recv_timeout, send_timeout, rw_probe, tls_deadline,
    /// connect_deadline))`.
    pub(crate) fn transport_timeout_ms(&self, now: Millis) -> u32 {
        let t = self.core.recv_timeout;
        let recv_to = self.timers_recv_left_ms(now);
        let mut send_to = third(t);
        if self.core.state.is_connected() {
            let left = third(t).saturating_sub(elapsed(now, self.timers.last_send));
            if left > 0 {
                send_to = left;
            }
        }
        let tcp_connecting = self.core.phase == Phase::TcpConnecting;
        let mut timeout = match (tcp_connecting, self.cfg.connect_timeout_ms) {
            (true, Some(bound)) => {
                // When an explicit connect timeout is set, it overrides the general receive
                // timeout.
                let started = self.timers.connect_started_at.unwrap_or(now);
                let connect_left = i64::from(bound).saturating_sub(elapsed(now, started));
                send_to.min(connect_left)
            }
            _ => recv_to.min(send_to),
        };
        if self.core.state == State::ReadOnly {
            let rw_left = i64::from(self.timers.rw_timeout_ms)
                .saturating_sub(elapsed(now, self.timers.last_ping_rw));
            timeout = timeout.min(rw_left);
        }
        if let Phase::TlsHandshaking { deadline } = self.core.phase {
            timeout = timeout.min(until(now, deadline));
        }
        if self.compat.close_mode == CloseMode::Drain && self.close.closing {
            if let Some(deadline) = self.close.deadline {
                timeout = timeout.min(until(now, deadline));
            }
        }
        clamp_timeout(timeout)
    }

    /// Checks whether event loop scheduling lateness exceeded the prior deadline threshold,
    /// logging a warning if delayed.
    pub(crate) fn check_deadline_warn(&mut self, now: Millis) {
        let Some(deadline) = self.timers.next_deadline else {
            return;
        };
        let late = until(deadline, now);
        let max_exceed = i64::from(self.core.recv_timeout)
            .checked_div(10)
            .unwrap_or(0)
            .min(DEADLINE_WARN_MAX_MS);
        if late > max_exceed {
            self.emit_log(LogSite::DeadlineExceeded, || vec![LogArg::I64(late)]);
        }
    }

    /// Enqueues a protocol heartbeat ping frame (xid -2, opcode 11) and updates the last ping
    /// timestamp.
    pub(crate) fn queue_ping(&mut self, now: Millis) {
        self.requests.push_tail(OutFrame::plain(
            FrameKind::Ping,
            Self::request_frame(xid::PING, opcode::PING, &[]),
        ));
        self.timers.last_ping = now;
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
    use crate::compat::Compat;
    use crate::config::DriverMode;
    use crate::interest::test_support::{Tk, conn, conn_with, init_two, sa};
    use crate::request::Route;
    use crate::requests::{Pending, Shape};

    /// Constructs an established legacy connection with protocol timers initialized at epoch 0.
    fn connected() -> Connection<Tk> {
        let mut c = conn();
        init_two(&mut c);
        c.core.has_transport = true;
        c.core.state = State::Connected;
        c.core.phase = Phase::Established;
        c
    }

    /// Constructs a synthetic in-flight pending request entry without an attached watch.
    fn pending() -> Pending<Tk> {
        Pending {
            xid: 1,
            shape: Shape::Data,
            route: Route::Queued,
            token: 7,
            watch: None,
            unwatch: None,
            written: true,
        }
    }

    #[test]
    fn receive_deadline_boundary() {
        let mut c = connected();
        // With a 30 s session timeout, the receive deadline expires at 2/3 T (20 s) after the
        // most recent inbound frame.
        c.timers.last_send = Millis(19_999);
        assert!(c.run_timers(Millis(19_999)).is_none());
        assert_eq!(c.timers_recv_left_ms(Millis(19_999)), 1);
        assert_eq!(c.core.errno_hint, None);
        // Reaching the 20 s threshold reduces the remaining receive window to zero.
        assert_eq!(c.timers_recv_left_ms(Millis(20_000)), 0);
        assert_eq!(c.timers_recv_overdue_ms(Millis(20_250)), 250);
    }

    /// Verifies that `SslConnecting` (state 7) receives no receive deadline exemption, whereas
    /// an explicit TCP connect timeout bound supersedes the receive deadline during
    /// `Phase::TcpConnecting`.
    #[test]
    fn receive_deadline_exemptions() {
        // `SslConnecting` during TCP connection establishment remains subject to the standard
        // min(recv_to, T/3) poll interval and tears down at 2/3 T.
        let mut c = conn();
        init_two(&mut c);
        c.core.has_transport = true;
        c.core.state = State::SslConnecting;
        c.core.phase = Phase::TcpConnecting;
        assert_eq!(c.transport_timeout_ms(Millis(1_000)), 10_000);
        assert_eq!(c.transport_timeout_ms(Millis(15_000)), 5_000);
        assert_eq!(c.transport_timeout_ms(Millis(19_999)), 1);
        assert!(c.run_timers(Millis(19_999)).is_none());
        assert!(c.core.has_transport);
        assert!(c.run_timers(Millis(20_000)).is_none());
        assert!(!c.core.has_transport);
        assert_eq!(c.core.errno_hint, Some(ETIMEDOUT));
        assert_eq!(c.interest.rc, zk_jute::rc::ZOPERATIONTIMEOUT);

        // When `connect_timeout_ms` is configured, `Phase::TcpConnecting` defers expiration until
        // the explicit connect bound elapses.
        let (mut n, _) = conn_with(Compat::LEGACY_C, DriverMode::ST, |cfg| {
            cfg.connect_timeout_ms = Some(40_000);
        });
        n.core.has_transport = true;
        n.core.state = State::Connecting;
        n.core.phase = Phase::TcpConnecting;
        n.timers.connect_started_at = Some(Millis(0));
        assert!(n.run_timers(Millis(39_999)).is_none());
        assert_eq!(n.core.errno_hint, None);
        // The explicit connect bound substitutes for `recv_to` in polling timeout calculations.
        assert_eq!(n.transport_timeout_ms(Millis(35_000)), 5_000);
        assert_eq!(n.transport_timeout_ms(Millis(1_000)), 10_000);
    }

    #[test]
    fn tls_handshake_deadline_boundary() {
        let mut c = conn();
        c.core.has_transport = true;
        c.core.state = State::SslConnecting;
        c.core.phase = Phase::TlsHandshaking {
            deadline: Millis(5_000),
        };
        assert!(c.run_timers(Millis(4_999)).is_none());
        assert_eq!(c.transport_timeout_ms(Millis(4_000)), 1_000);
        assert_eq!(c.transport_timeout_ms(Millis(5_000)), 0);
    }

    #[test]
    fn ping_boundary_and_frame() {
        let mut c = connected();
        assert!(c.run_timers(Millis(9_999)).is_none());
        assert!(c.requests.tx.is_empty());
        assert_eq!(c.transport_timeout_ms(Millis(9_999)), 1);
        assert!(c.run_timers(Millis(10_000)).is_none());
        assert_eq!(c.requests.tx.len(), 1);
        let f = &c.requests.tx[0];
        assert_eq!(f.kind, FrameKind::Ping);
        assert_eq!(
            f.bytes.as_slice(),
            &[0, 0, 0, 8, 0xff, 0xff, 0xff, 0xfe, 0, 0, 0, 11]
        );
        assert_eq!(c.timers.last_ping, Millis(10_000));
        // Once the send deadline has passed, `send_to` resets to the full T/3 interval.
        assert_eq!(c.transport_timeout_ms(Millis(10_000)), 10_000);
        assert_eq!(c.transport_timeout_ms(Millis(15_000)), 5_000);
    }

    #[test]
    fn interest_records_the_queued_ping() {
        let mut c = connected();
        assert!(c.begin_interest(Millis(9_999)).is_ok());
        assert!(c.run_timers(Millis(9_999)).is_none());
        assert!(!c.interest_queued_ping());
        assert!(c.run_timers(Millis(10_000)).is_none());
        assert!(c.interest_queued_ping());
        // Beginning a subsequent interest cycle clears the transient `ping_queued` indicator.
        assert!(c.begin_interest(Millis(10_001)).is_ok());
        assert!(!c.interest_queued_ping());
    }

    #[test]
    fn ping_queue_holds_one_unsent_ping() {
        for compat in [Compat::LEGACY_C, Compat::NATIVE] {
            let (mut c, _) = conn_with(compat, DriverMode::ST, |_| {});
            init_two(&mut c);
            c.core.has_transport = true;
            c.core.state = State::Connected;
            c.core.phase = Phase::Established;
            // Simulate a backpressured outbound socket while inbound frames continue arriving.
            for now in (10_000..10_100).step_by(10) {
                c.timers.last_recv = Millis(now);
                assert!(c.run_timers(Millis(now)).is_none());
            }
            let pings = c
                .requests
                .tx
                .iter()
                .filter(|f| f.kind == FrameKind::Ping)
                .count();
            assert_eq!(pings, 1, "{compat:?}");
            assert_eq!(c.timers.last_ping, Millis(10_000));
            // Once the queued ping is drained from the transmit queue, the next send deadline
            // enqueues a fresh ping frame.
            c.requests.tx.clear();
            c.timers.last_send = Millis(10_100);
            c.timers.last_recv = Millis(20_100);
            assert!(c.run_timers(Millis(20_100)).is_none());
            assert_eq!(c.requests.tx.len(), 1);
        }
    }

    #[test]
    fn ping_waits_for_an_empty_fifo_in_legacy() {
        let mut c = connected();
        c.requests.fifo.push_back(pending());
        assert!(c.run_timers(Millis(10_000)).is_none());
        assert!(c.requests.tx.is_empty());

        let compat = Compat {
            ping_when_outstanding: true,
            ..Compat::LEGACY_C
        };
        let (mut n, _) = conn_with(compat, DriverMode::ST, |_| {});
        n.core.has_transport = true;
        n.core.state = State::Connected;
        n.core.phase = Phase::Established;
        n.requests.fifo.push_back(pending());
        assert!(n.run_timers(Millis(10_000)).is_none());
        assert_eq!(n.requests.tx.len(), 1);
    }

    #[test]
    fn read_only_probe_boundary_and_backoff() {
        let mut c = connected();
        c.core.state = State::ReadOnly;
        let mut now = 0u64;
        let mut expected = RW_PROBE_MIN_MS;
        for _ in 0..12 {
            // Advance send and receive timestamps to suppress keepalive pings and receive timeouts.
            c.timers.last_send = Millis(now);
            c.timers.last_recv = Millis(now);
            let edge = now + u64::from(expected);
            c.timers.last_send = Millis(edge - 1);
            c.timers.last_recv = Millis(edge - 1);
            assert!(c.run_timers(Millis(edge - 1)).is_none());
            assert_eq!(c.transport_timeout_ms(Millis(edge - 1)), 1);
            c.timers.last_send = Millis(edge);
            c.timers.last_recv = Millis(edge);
            let Some(Action::ProbeRw { endpoint, tls }) = c.run_timers(Millis(edge)) else {
                panic!("expected ProbeRw");
            };
            assert_eq!(endpoint.addr, sa("10.0.0.1:2181"));
            assert_eq!(tls, None);
            assert_eq!(c.timers.last_ping_rw, Millis(edge));
            expected = (expected * 2).min(RW_PROBE_MAX_MS);
            assert_eq!(c.timers.rw_timeout_ms, expected);
            now = edge;
        }
        assert_eq!(c.timers.rw_timeout_ms, RW_PROBE_MAX_MS);
    }

    #[test]
    fn drain_close_caps_the_timeout() {
        let compat = Compat {
            close_mode: CloseMode::Drain,
            ..Compat::LEGACY_C
        };
        let (mut c, _) = conn_with(compat, DriverMode::ST, |_| {});
        c.core.has_transport = true;
        c.core.state = State::Connected;
        c.core.phase = Phase::Established;
        c.close.closing = true;
        c.close.deadline = Some(Millis(1_500));
        assert_eq!(c.transport_timeout_ms(Millis(1_000)), 500);
        assert_eq!(c.transport_timeout_ms(Millis(2_000)), 0);
    }

    #[test]
    fn no_timer_runs_without_transport() {
        let mut c = conn();
        c.core.state = State::Connected;
        assert!(c.run_timers(Millis(100_000)).is_none());
        assert!(c.requests.tx.is_empty());
    }
}
