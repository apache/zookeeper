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

//! Network transport lifecycle management, protocol handshake, and session finalization.
//!
//! Manages the transitions of [`SessionState`] across connection phases: from idle through
//! nonblocking TCP connection, optional TLS negotiation, Jute protocol handshake, and
//! session establishment finalization, dispatching cluster session state events.

use zeroize::Zeroize;
use zk_jute::handshake::{ConnectRequest, decode_connect_response};

use crate::action::ConnectStart;
use crate::compat::{ExpiryRule, HandshakeTimeout};
use crate::config::{ClientId, SessionDelivery};
use crate::connection::Connection;
use crate::errno;
use crate::hooks::Tokens;
use crate::log::{LogArg, LogRecord, LogSite, str_arg};
use crate::output::{Output, Work};
use crate::recv::Flow;
use crate::requests::{FrameKind, OutFrame};
use crate::state::{Cause, Phase, State, event_name};
use crate::teardown::{T0Line, session_id_arg};
use crate::time::{Millis, two_thirds};

/// Synthetic event type identifier representing session state changes within watcher dispatch
/// callbacks.
const SESSION_EVENT: i32 = -1;

/// Transient session handshake state associated with the active network transport.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct SessionState {
    /// Indicates whether a `ConnectRequest` has been dispatched and awaits a `ConnectResponse`.
    pub(crate) handshake_pending: bool,
    /// Indicates whether the outbound `Connect` wire frame has completed transmission.
    pub(crate) connect_sent: bool,
    /// Indicates whether the negotiated session operates in restricted read-only mode.
    pub(crate) read_only: bool,
    /// Indicates whether TCP socket connection succeeded synchronously upon initiation.
    immediate: bool,
}

/// Execution stages within the six-step session finalization state machine.
///
/// Finalization executes: (1) state transition to Connected/ReadOnly, (2) diagnostic INFO logging,
/// (3) queuing `SetWatches` registration, (4) auth lockpoint synchronization and credential
/// resubmission, (5) dispatching session state transition events, and (6) releasing queued request
/// frames.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum FinalizeStep {
    /// Resumption point following driver authentication lockpoint synchronization.
    AfterLockpoint,
    /// Resumption point following inline session event delivery to client watchers.
    AfterSession,
}

/// Deferred continuation payload tracking pending steps of the session finalization sequence.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct FinalizeJob {
    /// Next finalization step to execute upon resumption.
    pub(crate) step: FinalizeStep,
    /// Transport epoch under which finalization originated, preventing execution if the connection
    /// was torn down.
    pub(crate) epoch: u64,
}

impl<T: Tokens> Connection<T> {
    /// Initiates a new transport connection attempt in response to a network driver connect
    /// notification.
    ///
    /// Establishes a new transport generation epoch. If `kind` is `ConnectStart::InProgress`,
    /// advances the protocol phase to `Phase::TcpConnecting`, sets state to `Connecting` (or
    /// `SslConnecting`), records connection start timestamps, and resets ping/receive deadlines.
    /// If `kind` is `Immediate`, defers timer reset until the driver completes immediate transport
    /// readiness processing.
    ///
    /// If connection teardown is already in progress or a transport exists, rejects the attempt
    /// and queues an immediate `CloseTransport` action for socket cleanup.
    pub fn handle_connect_started(&mut self, kind: ConnectStart, now: Millis) {
        if self.close.closing || self.core.has_transport {
            self.queue_close_transport();
            return;
        }
        let _epoch = self.open_transport();
        let immediate = matches!(kind, ConnectStart::Immediate);
        self.session = SessionState {
            immediate,
            ..SessionState::default()
        };
        self.core.phase = Phase::TcpConnecting;
        if !immediate {
            let state = if self.cfg.tls {
                State::SslConnecting
            } else {
                State::Connecting
            };
            self.set_state(state);
            self.timers.connect_started_at = Some(now);
            self.timers.reset_for_connect(now);
        }
    }

    /// Transitions protocol phase upon successful completion of the underlying TCP socket
    /// connection.
    ///
    /// When TLS is configured, advances to `Phase::TlsHandshaking` with a two-thirds receive
    /// timeout deadline. In plain TCP mode, awaits transport readiness notification via
    /// `handle_transport_ready`.
    pub fn handle_tcp_connected(&mut self, now: Millis) {
        if !self.core.has_transport || self.core.phase != Phase::TcpConnecting {
            return;
        }
        if self.cfg.tls {
            let deadline = now.offset_ms(two_thirds(self.core.recv_timeout));
            self.core.phase = Phase::TlsHandshaking { deadline };
            if self.session.immediate {
                self.timers.reset_for_connect(now);
            }
        }
    }

    /// Initiates the ZooKeeper protocol handshake once the transport channel (TCP and optional
    /// TLS) is ready.
    ///
    /// Serializes and enqueues a high-priority `ConnectRequest` frame containing session
    /// credentials, negotiated or requested timeouts, last observed zxid, and read-only flags.
    /// Advances phase to `Phase::Associating` and logs connection initiation diagnostics.
    pub fn handle_transport_ready(&mut self, now: Millis) {
        let ready = matches!(
            self.core.phase,
            Phase::TcpConnecting | Phase::TlsHandshaking { .. }
        );
        if !self.core.has_transport || !ready {
            return;
        }
        let time_out = match self.compat.handshake_timeout {
            HandshakeTimeout::Negotiated => self.core.recv_timeout,
            HandshakeTimeout::Requested => self.cfg.timeout_ms,
        };
        let session_id = if self.core.seen_rw_before {
            self.core.client_id.session_id
        } else {
            0
        };
        let mut req = ConnectRequest {
            protocol_version: 0,
            last_zxid_seen: self.core.last_zxid,
            time_out,
            session_id,
            passwd: self.core.client_id.passwd,
            read_only: self.cfg.allow_read_only,
        };
        let mut frame = req.encode_frame();
        let bytes = frame.to_vec();
        frame.zeroize();
        req.passwd.zeroize();
        self.requests
            .push_priority(OutFrame::secret(FrameKind::Connect, bytes));
        self.core.phase = Phase::Associating;
        self.session.handshake_pending = true;
        self.session.connect_sent = false;
        self.set_state(State::Associating);
        if self.session.immediate && !self.cfg.tls {
            self.timers.reset_for_connect(now);
        }
        let site = if self.session.immediate {
            LogSite::InitiatedConnection
        } else {
            LogSite::InitiatedConnectionLate
        };
        let server = self.current_server();
        self.emit_log(site, || vec![LogArg::Endpoint(server)]);
    }

    /// Records completion of outbound network transmission for the `Connect` handshake frame.
    pub(crate) fn on_connect_sent(&mut self) {
        self.session.connect_sent = true;
    }

    /// Decodes and processes the incoming `ConnectResponse` handshake frame from the ensemble.
    ///
    /// Enforces session expiration rules based on configured compatibility policies:
    /// - Rejects malformed response bodies with a `Cause::ProtocolError` teardown.
    /// - Detects session expiration when session IDs change unexpectedly or negotiated timeouts
    ///   are non-positive.
    /// - Upon successful negotiation, updates session credentials, receive timeouts, and read-only
    ///   status, delivering a `ClientId` notification and advancing to SASL authentication or
    ///   session finalization.
    pub(crate) fn on_connect_response(&mut self, body: &[u8], now: Millis) -> Flow {
        let Ok(mut resp) = decode_connect_response(body) else {
            return Flow::Done(self.teardown(Cause::ProtocolError, 0, now));
        };
        let old_id = if self.core.seen_rw_before {
            self.core.client_id.session_id
        } else {
            0
        };
        self.core.seen_rw_before = self.core.seen_rw_before || !resp.read_only;
        let id_changed = old_id != 0 && old_id != resp.session_id;
        let no_timeout = self.compat.expiry == ExpiryRule::Java && resp.time_out <= 0;
        if id_changed || no_timeout {
            resp.passwd.zeroize();
            self.set_state(State::ExpiredSession);
            self.set_errno_hint(errno::ESTALE);
            let line = T0Line::with(
                LogSite::SockSessionExpired,
                errno::ESTALE,
                vec![session_id_arg(old_id)],
            );
            return Flow::Done(self.teardown_with_line(Cause::SessionExpired, Some(line), now));
        }
        self.core.recv_timeout = resp.time_out;
        self.core.client_id = ClientId {
            session_id: resp.session_id,
            passwd: resp.passwd,
        };
        resp.passwd.zeroize();
        self.session.read_only = resp.read_only;
        self.session.handshake_pending = false;
        self.out.push(Output::ClientId(self.core.client_id.clone()));
        if self.cfg.sasl {
            self.teardown_peer_begin_sasl(now);
            Flow::Stop
        } else {
            self.start_finalize(now)
        }
    }

    /// Executes the initial phases of session finalization following successful handshake or SASL
    /// authentication.
    ///
    /// Transitions state to `Connected` (or `ReadOnly`), clears dynamic reconfiguration flags,
    /// logs session establishment diagnostics, and queues active watch re-registration frames
    /// (`SetWatches`). If lockpoint synchronization is required, enqueues an `AuthLockpoint`
    /// barrier and yields `Flow::Stop`.
    pub(crate) fn start_finalize(&mut self, _now: Millis) -> Flow {
        let read_only = self.session.read_only;
        let state = if read_only {
            State::ReadOnly
        } else {
            State::Connected
        };
        self.set_state(state);
        self.hosts.servers.reconfig = false;
        self.hosts_on_established();
        self.core.phase = Phase::Established;
        let server = self.current_server();
        let sid = session_id_arg(self.core.client_id.session_id);
        let timeout = i64::from(self.core.recv_timeout);
        self.emit_log(LogSite::SessionEstablished, || {
            let mode: &[u8] = if read_only { b"(READ-ONLY mode)" } else { b"" };
            vec![
                LogArg::Endpoint(server),
                sid,
                LogArg::I64(timeout),
                str_arg(mode),
            ]
        });
        self.teardown_peer_queue_set_watches();
        let epoch = self.core.epoch;
        if self.compat.emits_lockpoints() {
            self.out.push(Output::AuthLockpoint);
            self.out.park(Work::Finalize(FinalizeJob {
                step: FinalizeStep::AfterLockpoint,
                epoch,
            }));
            return Flow::Stop;
        }
        if self.session_finalize_tail(epoch) {
            Flow::Stop
        } else {
            Flow::Continue
        }
    }

    /// Resumes suspended session finalization steps following driver barrier synchronization.
    ///
    /// Verifies that the transport epoch remains valid before resubmitting authentication
    /// credentials and delivering session state notifications.
    pub(crate) fn continue_finalize(&mut self, job: FinalizeJob, _now: Millis) {
        if job.epoch != self.core.epoch || !self.core.has_transport {
            return;
        }
        match job.step {
            FinalizeStep::AfterLockpoint => {
                let _stopped = self.session_finalize_tail(job.epoch);
            }
            FinalizeStep::AfterSession => {}
        }
    }

    /// Dispatches a session state transition event to registered global and session watchers.
    ///
    /// Prepends diagnostic watcher dispatch logs. If inline session delivery is configured,
    /// marks the output as an execution barrier and returns true to pause caller progression.
    pub(crate) fn emit_session(&mut self, state: State, extra_logs: Vec<LogRecord>) -> bool {
        let watchers = self.teardown_peer_session_watchers();
        let mut delivery_logs = Vec::new();
        if let Some(r) = self.log.record(LogSite::CallingWatcher, || {
            vec![
                str_arg(b""),
                LogArg::I64(i64::from(SESSION_EVENT)),
                str_arg(event_name(SESSION_EVENT).as_bytes()),
            ]
        }) {
            delivery_logs.push(r);
        }
        delivery_logs.extend(extra_logs);
        let barrier = self.mode.session_delivery == SessionDelivery::Inline;
        self.out.push(Output::Session {
            state,
            watchers,
            barrier,
            delivery_logs,
        });
        barrier
    }

    /// Executes trailing steps of session finalization: credential resubmission, diagnostic
    /// logging, and session event dispatch, parking continuation state if the event acts as a
    /// barrier.
    fn session_finalize_tail(&mut self, epoch: u64) -> bool {
        self.teardown_peer_queue_auth_resend();
        self.emit_log(LogSite::FinalizeSessionEvent, Vec::new);
        let state = self.core.state;
        if self.emit_session(state, Vec::new()) {
            self.out.park(Work::Finalize(FinalizeJob {
                step: FinalizeStep::AfterSession,
                epoch,
            }));
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use super::*;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::output::TerminateReason;
    use crate::rc;
    use crate::teardown::PeerCall;
    use crate::teardown::test_support::*;

    /// Extracts the serialized `Connect` frame payload from the outbound transmission queue.
    fn connect_frame(c: &Connection<Tk>) -> Vec<u8> {
        let f = c
            .requests
            .tx
            .iter()
            .find(|f| f.kind == FrameKind::Connect)
            .expect("a Connect frame");
        f.bytes.as_slice().to_vec()
    }

    /// Decodes the timeout and session ID fields from a raw `Connect` frame.
    fn frame_fields(frame: &[u8]) -> (i32, i64) {
        let t = i32::from_be_bytes(frame[16..20].try_into().unwrap());
        let s = i64::from_be_bytes(frame[20..28].try_into().unwrap());
        (t, s)
    }

    fn resumed_cfg(sid: i64) -> Config {
        let mut cfg = Config::legacy(30_000);
        cfg.client_id = ClientId {
            session_id: sid,
            passwd: [0x11; 16],
        };
        cfg
    }

    #[test]
    fn connect_start_sets_state_and_phase() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        let epoch = c.core.epoch;
        c.handle_connect_started(ConnectStart::InProgress, Millis(50));
        assert!(c.has_transport());
        assert_ne!(c.core.epoch, epoch);
        assert_eq!(c.state(), State::Connecting);
        assert_eq!(c.core.phase, Phase::TcpConnecting);
        assert_eq!(c.timers.connect_started_at, Some(Millis(50)));
        assert_eq!(c.timers.last_recv, Millis(50));

        let mut cfg = Config::legacy(30_000);
        cfg.tls = true;
        let mut t = conn_with(cfg, Compat::LEGACY_C, DriverMode::ST);
        t.handle_connect_started(ConnectStart::InProgress, Millis(1));
        assert_eq!(t.state(), State::SslConnecting);
        t.handle_tcp_connected(Millis(10));
        assert_eq!(
            t.core.phase,
            Phase::TlsHandshaking {
                deadline: Millis(20_010)
            }
        );
        t.handle_transport_ready(Millis(30));
        assert_eq!(t.state(), State::Associating);
        assert_eq!(t.core.phase, Phase::Associating);
    }

    #[test]
    fn immediate_connect_keeps_state_and_resets_timers_late() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        c.handle_connect_started(ConnectStart::Immediate, Millis(5));
        assert_eq!(c.state(), State::NotConnected);
        assert_eq!(c.timers.last_recv, Millis(0));
        c.handle_tcp_connected(Millis(5));
        assert_eq!(c.core.phase, Phase::TcpConnecting);
        c.handle_transport_ready(Millis(6));
        assert_eq!(c.state(), State::Associating);
        assert_eq!(c.timers.last_recv, Millis(6));
        assert!(c.session.handshake_pending);
        assert!(!c.session.connect_sent);
        assert_eq!(
            drain(&mut c, Millis(6)),
            vec![Ev::Log(LogSite::InitiatedConnection)]
        );
        // The frame is dispatched only upon initial transport readiness.
        c.handle_transport_ready(Millis(7));
        assert_eq!(c.requests.tx.len(), 1);
    }

    #[test]
    fn fresh_session_handshake_and_finalize_with_lockpoints() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        connect(&mut c, Millis(1));
        let frame = connect_frame(&c);
        assert_eq!(frame.len(), 49);
        assert_eq!(frame_fields(&frame), (30_000, 0));
        assert!(c.requests.tx.front().unwrap().kind == FrameKind::Connect);
        let _ = drain(&mut c, Millis(1));

        let flow = c.on_connect_response(&response(10_000, 0x55, false), Millis(2));
        assert_eq!(flow, Flow::Stop);
        assert_eq!(c.state(), State::Connected);
        assert_eq!(c.core.phase, Phase::Established);
        assert_eq!(c.recv_timeout(), 10_000);
        assert_eq!(c.client_id().session_id, 0x55);
        assert_eq!(c.client_id().passwd, [0xAB; 16]);
        assert!(c.core.seen_rw_before);
        assert!(!c.session.handshake_pending);

        // Barrier 1: authentication lockpoint synchronization.
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(
            evs,
            vec![
                Ev::ClientId(0x55),
                Ev::Log(LogSite::SessionEstablished),
                Ev::Lockpoint
            ]
        );
        assert_eq!(calls(&mut c), vec![PeerCall::QueueSetWatches]);
        c.resume(stop.unwrap(), Millis(2));

        // Barrier 2: session state notification delivery.
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(
            evs,
            vec![
                Ev::Log(LogSite::FinalizeSessionEvent),
                Ev::Session(3, true, vec![DEFAULT_WATCHER])
            ]
        );
        assert_eq!(
            calls(&mut c),
            vec![PeerCall::QueueAuthResend, PeerCall::SessionWatchers]
        );
        c.resume(stop.unwrap(), Millis(2));
        assert!(drain(&mut c, Millis(2)).is_empty());
        assert!(!c.is_suspended());
    }

    #[test]
    fn session_event_carries_the_delivery_log() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        assert!(!c.emit_session(State::Connecting, Vec::new()));
        match c.poll_output() {
            Some(Output::Session {
                state,
                barrier,
                delivery_logs,
                ..
            }) => {
                assert_eq!(state, State::Connecting);
                assert!(!barrier);
                assert_eq!(delivery_logs.len(), 1);
                assert_eq!(delivery_logs[0].site, LogSite::CallingWatcher);
                assert_eq!(delivery_logs[0].args[1], LogArg::I64(-1));
            }
            _ => panic!("no session output"),
        }
    }

    #[test]
    fn finalize_without_lockpoints() {
        // Asynchronous dispatch delivery executes without stage barriers.
        let mut c = conn(Compat::NATIVE, DriverMode::NATIVE);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(10_000, 0x55, false), Millis(2));
        assert_eq!(flow, Flow::Continue);
        assert!(!c.is_suspended());
        assert_eq!(
            no_logs(&drain(&mut c, Millis(2))),
            vec![
                Ev::ClientId(0x55),
                Ev::Session(3, false, vec![DEFAULT_WATCHER])
            ]
        );

        // Inline delivery halts once at the session notification barrier.
        let mut c = conn(Compat::NATIVE, DriverMode::ST);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(10_000, 0x55, false), Millis(2));
        assert_eq!(flow, Flow::Stop);
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(
            no_logs(&evs),
            vec![
                Ev::ClientId(0x55),
                Ev::Session(3, true, vec![DEFAULT_WATCHER])
            ]
        );
        c.resume(stop.unwrap(), Millis(2));
        assert!(drain(&mut c, Millis(2)).is_empty());
    }

    #[test]
    fn finalize_with_lockpoints_and_dispatch_stops_once() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        connect(&mut c, Millis(1));
        let _ = c.on_connect_response(&response(10_000, 0x55, true), Millis(2));
        assert_eq!(c.state(), State::ReadOnly);
        assert!(!c.core.seen_rw_before);
        let evs = drain(&mut c, Millis(2));
        assert_eq!(
            no_logs(&evs),
            vec![
                Ev::ClientId(0x55),
                Ev::Lockpoint,
                Ev::Stop,
                Ev::Session(5, false, vec![DEFAULT_WATCHER])
            ]
        );
    }

    #[test]
    fn resumed_session_handshake() {
        let mut c = conn_with(resumed_cfg(0x77), Compat::LEGACY_C, DriverMode::MT);
        connect(&mut c, Millis(1));
        assert_eq!(frame_fields(&connect_frame(&c)), (30_000, 0x77));
        let flow = c.on_connect_response(&response(20_000, 0x77, false), Millis(2));
        assert_eq!(flow, Flow::Stop);
        assert_eq!(c.state(), State::Connected);
        assert_eq!(c.client_id().session_id, 0x77);

        // Negotiated timeout mode: subsequent reconnect handshakes request the previously
        // negotiated timeout.
        let _ = drain(&mut c, Millis(2));
        let _ = c.teardown(Cause::Eof, 0, Millis(3));
        let _ = drain(&mut c, Millis(3));
        connect(&mut c, Millis(4));
        assert_eq!(frame_fields(&connect_frame(&c)), (20_000, 0x77));

        // Requested timeout mode: subsequent reconnect handshakes retain the configured static
        // timeout.
        let mut n = conn_with(resumed_cfg(0x77), Compat::NATIVE, DriverMode::NATIVE);
        connect(&mut n, Millis(1));
        let _ = n.on_connect_response(&response(20_000, 0x77, false), Millis(2));
        let _ = drain(&mut n, Millis(2));
        let _ = n.teardown(Cause::Eof, 0, Millis(3));
        let _ = drain(&mut n, Millis(3));
        connect(&mut n, Millis(4));
        assert_eq!(frame_fields(&connect_frame(&n)), (30_000, 0x77));
    }

    #[test]
    fn expiry_under_legacy_rule() {
        // Reassignment of session ID triggers session expiration under legacy rules.
        let mut c = conn_with(resumed_cfg(0x77), Compat::LEGACY_C, DriverMode::MT);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(20_000, 0x88, false), Millis(2));
        assert_eq!(flow, Flow::Done(rc::ZSESSIONEXPIRED));
        assert_eq!(c.state(), State::ExpiredSession);
        assert_eq!(c.take_errno_hint(), Some(crate::errno::ESTALE));
        assert_eq!(c.last_teardown_cause(), Some(Cause::SessionExpired));
        assert!(!c.has_transport());
        assert_eq!(c.client_id().session_id, 0x77);
        let evs = drain(&mut c, Millis(2));
        assert!(evs.contains(&Ev::Session(-112, false, vec![DEFAULT_WATCHER])));
        assert!(!evs.contains(&Ev::ClientId(0x88)));

        // A server returning a timeout of 0 is tolerated under legacy C rules without triggering
        // expiration.
        let mut c = conn_with(resumed_cfg(0x77), Compat::LEGACY_C, DriverMode::MT);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(0, 0x77, false), Millis(2));
        assert_eq!(flow, Flow::Stop);
        assert_eq!(c.state(), State::Connected);
        assert_eq!(c.recv_timeout(), 0);

        // Fresh session ID assignment on a connection that has never established read-write state
        // is permitted.
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        connect(&mut c, Millis(1));
        let _ = c.on_connect_response(&response(1_000, 0x10, true), Millis(2));
        let _ = drain(&mut c, Millis(2));
        let _ = c.teardown(Cause::Eof, 0, Millis(3));
        let _ = drain(&mut c, Millis(3));
        connect(&mut c, Millis(4));
        assert_eq!(frame_fields(&connect_frame(&c)).1, 0);
        let flow = c.on_connect_response(&response(1_000, 0x20, false), Millis(5));
        assert_eq!(flow, Flow::Stop);
        assert_eq!(c.client_id().session_id, 0x20);
    }

    #[test]
    fn expiry_under_java_rule() {
        let mut c = conn_with(resumed_cfg(0x77), Compat::NATIVE, DriverMode::NATIVE);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(0, 0x77, false), Millis(2));
        assert_eq!(flow, Flow::Done(rc::ZSESSIONEXPIRED));
        assert_eq!(c.state(), State::ExpiredSession);
        let evs = drain(&mut c, Millis(2));
        assert_eq!(
            evs.first(),
            Some(&Ev::Log(LogSite::InitiatedConnectionLate))
        );
        assert_eq!(evs.get(1), Some(&Ev::Log(LogSite::SockSessionExpired)));
        assert!(calls(&mut c).contains(&PeerCall::ClearWatches(TerminateReason::SessionExpired)));

        // A fresh session handshake receiving a non-positive timeout triggers expiration under
        // Java rules.
        let mut c = conn(Compat::NATIVE, DriverMode::NATIVE);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(-1, 0x55, false), Millis(2));
        assert_eq!(flow, Flow::Done(rc::ZSESSIONEXPIRED));

        // Reassigned session ID triggers expiration under Java rules as well.
        let mut c = conn_with(resumed_cfg(0x77), Compat::NATIVE, DriverMode::NATIVE);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(5_000, 0x99, false), Millis(2));
        assert_eq!(flow, Flow::Done(rc::ZSESSIONEXPIRED));
    }

    #[test]
    fn bad_response_is_a_protocol_error() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&[0; 10], Millis(2));
        assert_eq!(flow, Flow::Done(rc::ZCONNECTIONLOSS));
        assert_eq!(c.last_teardown_cause(), Some(Cause::ProtocolError));
        assert!(!c.has_transport());
    }

    #[test]
    fn sasl_starts_after_the_response() {
        let mut cfg = Config::legacy(30_000);
        cfg.sasl = true;
        let mut c = conn_with(cfg, Compat::LEGACY_C, DriverMode::ST);
        connect(&mut c, Millis(1));
        let flow = c.on_connect_response(&response(10_000, 0x55, false), Millis(2));
        assert_eq!(flow, Flow::Stop);
        assert_eq!(c.state(), State::Associating);
        assert!(matches!(c.core.phase, Phase::Sasl(_)));
        assert_eq!(calls(&mut c), vec![PeerCall::BeginSasl]);
        assert_eq!(no_logs(&drain(&mut c, Millis(2))), vec![Ev::ClientId(0x55)]);

        // Successful SASL authentication triggers subsequent session finalization.
        assert_eq!(c.start_finalize(Millis(3)), Flow::Stop);
        assert_eq!(c.state(), State::Connected);
    }

    #[test]
    fn finalize_job_of_an_old_epoch_does_nothing() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        connect(&mut c, Millis(1));
        let _ = c.on_connect_response(&response(10_000, 0x55, false), Millis(2));
        let (_, stop) = until_stop(&mut c);
        c.forced_close_trigger(Millis(3));
        let _ = calls(&mut c);
        c.resume(stop.unwrap(), Millis(3));
        assert!(drain(&mut c, Millis(3)).is_empty());
        assert!(calls(&mut c).is_empty());
    }
}
