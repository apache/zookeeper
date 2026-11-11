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

//! Staged connection teardown and forced termination state machine.
//!
//! This module manages [`TeardownState`] and orchestrates the orderly dismantling of a ZooKeeper
//! connection across distinct lifecycle stages (T0 through T6). Each stage can pause at
//! cooperative scheduling barriers (such as yielding to allow user callbacks or driver
//! processing). In the event of a forced close, an atomic snapshot of in-flight and queued
//! operations is captured immediately, deferring subsequent failure dispatching to background
//! stages without blocking callers.

use std::collections::VecDeque;

use crate::action::{TlsConfigStep, TlsResourceAt, TransportFailure};
use crate::connection::Connection;
use crate::errno;
use crate::hooks::Tokens;
use crate::log::{LogArg, LogSite};
use crate::output::{Output, TerminateReason, Work};
use crate::rc;
use crate::requests::{OutFrame, Pending};
use crate::state::{Cause, Phase, State};
use crate::time::{Millis, until};
use zk_jute::JString;

/// Discrete execution stages of the connection teardown state machine.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum TeardownStage {
    /// Stage T2: Emits state change notifications (such as `Connecting` or terminal states) to
    /// registered session watchers.
    Session,
    /// Stage T3: Captures a point-in-time snapshot of outbound transmission frames and pending
    /// FIFO requests. Standard teardown captures the active queue state; forced close utilizes the
    /// pre-captured snapshot.
    Snapshot,
    /// Stage T3: Systematically fails captured pending requests with appropriate error codes,
    /// yielding across direct-completion boundaries.
    FailFifo,
    /// Stage T3: Emits authentication lock-point synchronization barriers for single threaded
    /// drivers.
    Lockpoint,
    /// Stage T3: Completes authentication callbacks with terminal error status.
    Auth,
    /// Stages T4 through T6: Finalizes host selection backoff, unregisters watchers, and completes
    /// cleanup.
    Finish,
}

/// Active execution context tracking the progress of an in-flight teardown operation across
/// execution barriers.
pub(crate) struct TeardownJob<T: Tokens> {
    /// Root cause triggering the connection teardown.
    pub(crate) cause: Cause,
    /// Next stage scheduled for execution when the job resumes.
    pub(crate) stage: TeardownStage,
    /// Indicates whether the connection was actively established when teardown was initiated.
    pub(crate) connected: bool,
    /// Pending operations remaining to be failed and notified following the T3 queue snapshot.
    pub(crate) entries: VecDeque<Pending<T>>,
    /// Authentication credentials awaiting failure notification, preserved from the teardown
    /// snapshot.
    pub(crate) auth: Vec<T::Auth>,
    /// Protocol return code assigned to failed operations, derived from [`Cause::rc_for`].
    pub(crate) rc: i32,
    /// Indicates whether this job represents the delayed completion phase of a forced close.
    pub(crate) forced: bool,
}

impl<T: Tokens> TeardownJob<T> {
    /// Extracts all pending request completion and authentication tokens from this job.
    pub(crate) fn into_tokens(self) -> (Vec<T::Completion>, Vec<T::Auth>) {
        (
            self.entries.into_iter().map(|p| p.token).collect(),
            self.auth,
        )
    }
}

/// Point-in-time snapshot of connection queues captured at the exact moment a forced close was
/// triggered.
pub(crate) struct ForcedSnapshot<T: Tokens> {
    /// Connection state when forced closure occurred.
    pub(crate) connected: bool,
    /// Outbound wire frames aborted prior to transmission.
    pub(crate) tx: VecDeque<OutFrame>,
    /// Operations in the FIFO queue awaiting response dispatch when the connection closed.
    pub(crate) fifo: VecDeque<Pending<T>>,
    /// Active authentication tokens captured for terminal failure callbacks.
    pub(crate) auth: Vec<T::Auth>,
}

impl<T: Tokens> ForcedSnapshot<T> {
    /// Extracts all completion and authentication tokens preserved within this snapshot.
    pub(crate) fn into_tokens(self) -> (Vec<T::Completion>, Vec<T::Auth>) {
        (self.fifo.into_iter().map(|p| p.token).collect(), self.auth)
    }
}

/// Connection teardown state tracking pending snapshots, failure causes, and stage barriers.
pub(crate) struct TeardownState<T: Tokens> {
    /// Captured snapshot of connection buffers pending deferred failure processing during a forced
    /// close.
    pub(crate) forced: Option<ForcedSnapshot<T>>,
    /// Root cause of the most recently initiated teardown sequence.
    pub(crate) last_cause: Option<Cause>,
    /// Operating system error number associated with the most recent transport failure, if
    /// available.
    pub(crate) last_errno: Option<i32>,
    /// Tracks whether a forced close teardown job has started but has not yet reached stage T3.
    /// The snapshot remains in `forced` until T3 so re-entrant close calls during T2 callbacks can
    /// access it.
    forced_started: bool,
    /// Indicates that stage T3 is actively executing; concurrent close requests in this window
    /// defer until T3 finishes.
    t3_started: bool,
    /// Unit test interception seam recording inter-module peer invocations.
    #[cfg(test)]
    pub(crate) stub: Option<Vec<PeerCall>>,
}

impl<T: Tokens> TeardownState<T> {
    /// Constructs an uninitialized teardown state with no active teardown or recorded failures.
    pub(crate) fn new() -> Self {
        Self {
            forced: None,
            last_cause: None,
            last_errno: None,
            forced_started: false,
            t3_started: false,
            #[cfg(test)]
            stub: None,
        }
    }
}

/// Inter-module operation recorded by the unit test interception seam.
#[cfg(test)]
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum PeerCall {
    /// Invocation of `session_watchers`.
    SessionWatchers,
    /// Invocation of `queue_set_watches`.
    QueueSetWatches,
    /// Invocation of `queue_auth_resend`.
    QueueAuthResend,
    /// Invocation of `begin_sasl`.
    BeginSasl,
    /// Invocation of `sasl_on_teardown`.
    SaslOnTeardown,
    /// Invocation of `sasl_on_destroy`.
    SaslOnDestroy,
    /// Invocation of `hosts_on_handle_error` or `hosts_on_session_lost`.
    HostsOnHandleError,
    /// Invocation of `clear_watches` with the specified termination reason.
    ClearWatches(TerminateReason),
    /// Invocation of `fail_pending_run` with the specified return code.
    FailPendingRun(i32),
    /// Invocation of `request_frame` with the specified transaction ID and opcode.
    RequestFrame(i32, i32),
}

/// Structured log entry emitted during Stage T0 following C client socket error formatting.
pub(crate) struct T0Line {
    /// Logging call site identifier.
    site: LogSite,
    /// Operating system error number reported in the log message.
    errno: i32,
    /// Supplementary log arguments following the standard socket error prefix parameters.
    extra: Vec<LogArg>,
}

impl T0Line {
    /// Constructs a basic T0 error log specification without additional contextual parameters.
    pub(crate) fn new(site: LogSite, errno: i32) -> Self {
        Self {
            site,
            errno,
            extra: Vec::new(),
        }
    }

    /// Constructs a T0 error log specification populated with supplementary contextual arguments.
    pub(crate) fn with(site: LogSite, errno: i32, extra: Vec<LogArg>) -> Self {
        Self { site, errno, extra }
    }
}

/// Converts a 64-bit session identifier into pointer representation matching `%#llx` formatting in
/// C client logs.
pub(crate) fn session_id_arg(id: i64) -> LogArg {
    LogArg::Ptr(u64::from_ne_bytes(id.to_ne_bytes()))
}

impl<T: Tokens> Connection<T> {
    /// Processes a low-level transport failure reported by the network I/O driver.
    ///
    /// # Protocol Lifecycle & Error Invariants
    /// - TLS configuration failures emit descriptive diagnostics, set the error code hint to
    ///   `EINVAL`, transition the connection to `Phase::TlsConfigFailed`, and immediately return
    ///   `ZSSLCONNECTIONERROR` without executing teardown stages.
    /// - Polling failures return `ZSYSTEMERROR` directly without initiating teardown (used during
    ///   graceful close flush).
    /// - All other transport failures map to their corresponding [`Cause`] variant and initiate a
    ///   multi-stage teardown sequence, returning the protocol return code appropriate for the
    ///   disconnection.
    /// - When no transport is established or the connection has already been destroyed, this
    ///   function returns `ZOK` as a no-op, since destroyed instances have already discharged
    ///   pending requests.
    /// - Socket creation and initial TCP connection refusal failures are handled even before
    ///   transport initialization, mirroring legacy C client error reporting behavior
    ///   (`zookeeper.c:2681-2711`).
    pub fn handle_transport_failed(&mut self, f: TransportFailure, now: Millis) -> i32 {
        if self.close.destroyed {
            return rc::ZOK;
        }
        // Initiate teardown using default T0 error log templates for standard failures,
        // or specialized log sites for TLS resource initialization failures.
        match f {
            TransportFailure::SocketCreate { errno } => {
                self.teardown(Cause::SocketCreate, errno, now)
            }
            TransportFailure::TcpRefused { errno } => {
                self.teardown(Cause::ConnectFailed, errno, now)
            }
            _ if !self.core.has_transport => rc::ZOK,
            TransportFailure::Io { errno } => self.teardown(Cause::Io, errno, now),
            TransportFailure::Eof => self.teardown(Cause::Eof, errno::EHOSTDOWN, now),
            TransportFailure::TlsResource { at, errno } => {
                let site = match at {
                    TlsResourceAt::ContextNew => {
                        self.emit_log(LogSite::TlsContextNew, Vec::new);
                        LogSite::SockTlsContextNew
                    }
                    TlsResourceAt::ConnectionNew => LogSite::SockTlsConnectionNew,
                };
                self.teardown_with_line(Cause::Tls, Some(T0Line::new(site, errno)), now)
            }
            TransportFailure::TlsHandshake { errno } => self.teardown(Cause::Tls, errno, now),
            TransportFailure::TlsConfig { step, file } => {
                self.tls_config_failed(step, file);
                self.core.phase = Phase::TlsConfigFailed;
                rc::ZSSLCONNECTIONERROR
            }
            TransportFailure::Poll { .. } => rc::ZSYSTEMERROR,
        }
    }

    /// Emits structured diagnostic logs indicating a failure during a TLS configuration step
    /// and records `EINVAL` as an operating system error hint.
    ///
    /// Both active TLS connection attempts and background read-write probes share identical
    /// configuration validation rules, emitting uniform error sites and error codes.
    pub(crate) fn tls_config_failed(&mut self, step: TlsConfigStep, file: JString) {
        let (site, with_file) = match step {
            TlsConfigStep::CaFile => (LogSite::TlsCaFile, true),
            TlsConfigStep::DefaultVerifyPaths => (LogSite::TlsDefaultVerifyPaths, false),
            TlsConfigStep::ClientCertChain => (LogSite::TlsClientCertChain, true),
            TlsConfigStep::ClientKey => (LogSite::TlsClientKey, true),
            TlsConfigStep::CheckPrivateKey => (LogSite::TlsCheckPrivateKey, false),
        };
        self.emit_log(site, || {
            if with_file {
                vec![LogArg::Str(file)]
            } else {
                Vec::new()
            }
        });
        self.set_errno_hint(errno::EINVAL);
    }

    /// Triggers immediate connection shutdown from the single threaded submission flush path.
    ///
    /// Adheres to identical snapshot capture semantics as `forced_close_trigger`.
    pub fn handle_forced_close(&mut self, now: Millis) {
        self.forced_close_trigger(now);
    }

    /// Executes deferred stages of a pending forced connection teardown sequence if one is
    /// awaiting execution.
    ///
    /// # Execution Invariants
    /// - If the output stream is suspended, an explicit close is already underway, or a forced
    ///   teardown job has already started, this function returns `false` without performing work.
    /// - Otherwise, it extracts the captured snapshot, launches a background teardown job under
    ///   [`Cause::ForcedClose`] omitting initial T0 error logs, and returns `true`. Captured
    ///   outbound transmission frames are dropped.
    /// - If `begin_close` was invoked previously, the explicit close discharges snapshot entries
    ///   directly, making this invocation a no-op. The teardown job delays consuming the snapshot
    ///   until Stage T3 so that re-entrant close requests triggered from Stage T2 watcher callbacks
    ///   can discover and process it.
    pub fn run_pending_teardown(&mut self, now: Millis) -> bool {
        if self.out.is_suspended() || self.close.closing || self.teardown.forced_started {
            return false;
        }
        let Some(connected) = self.teardown.forced.as_ref().map(|s| s.connected) else {
            return false;
        };
        self.teardown.forced_started = true;
        let cause = Cause::ForcedClose;
        let rc = cause.rc_for(self.compat.disconnect_rc);
        self.teardown_start_job(cause, connected, None, rc, true, now);
        true
    }

    /// Returns the root cause of the most recently initiated connection teardown, if any.
    #[must_use]
    pub fn last_teardown_cause(&self) -> Option<Cause> {
        self.teardown.last_cause
    }

    /// Returns the operating system error code recorded during the most recent teardown, if
    /// reported by the I/O driver.
    ///
    /// Hardware socket creation (`SocketCreate`), TCP connect failure (`ConnectFailed`), and
    /// general network I/O (`Io`) always preserve the underlying OS error number.
    #[must_use]
    pub fn last_teardown_errno(&self) -> Option<i32> {
        self.teardown.last_errno
    }

    /// Initiates a multi-stage connection teardown sequence and returns the applicable protocol
    /// return code.
    ///
    /// # Staged Teardown Architecture
    /// - **Stage T0**: Emits a socket error log line matching legacy `handle_socket_error_msg`
    ///   formatting, carrying the underlying OS errno. Exempt failure causes suppress log emission.
    /// - **Stage T1**: Disarms active network transport, closes the socket, resets SASL and
    ///   receive state machines, clears request pinned buffers, reverts connection phase to `Idle`,
    ///   and advances the connection epoch.
    /// - **Subsequent Stages**: Advances through stages T2 to T6 via [`continue_teardown`].
    /// - **State Accounting**: Records the failure cause and returns the derived protocol return
    ///   code (such as `ZCONNECTIONLOSS` or specialized codes like `ZSSLCONNECTIONERROR`).
    pub(crate) fn teardown(&mut self, cause: Cause, errno: i32, now: Millis) -> i32 {
        let line = self.teardown_default_line(cause, errno, now);
        self.teardown_with_line(cause, line, now)
    }

    /// Drives teardown execution forward from the job's current stage until completion or a
    /// barrier is encountered.
    ///
    /// # Stage Execution Lifecycle
    /// - **Stage T2 (Session)**: Notifies session state watchers. If the connection state is
    ///   unrecoverable (e.g. expired or auth failed), it emits the terminal state; if connected, it
    ///   emits `Connecting`. In native mode, connection attempt failures also emit `Connecting` to
    ///   allow client side error observation during retries. If the notification constitutes a
    ///   barrier, the job parks.
    /// - **Stage T3 (Snapshot & FailFifo)**: Captures pending outbound and queued requests (or
    ///   consumes the forced close snapshot), discards unsent frames, and fails queued requests via
    ///   `fail_pending_run`, parking at direct execution boundaries. Subsequently emits
    ///   authentication lock-point barriers and completes authentication tokens with failure
    ///   status.
    /// - **Stage T4 (Finish & Drain)**: Emits diagnostic disconnect logs, transitions state to
    ///   `Closed` (while forced jobs preserve specialized terminal codes), and queues a
    ///   `DrainPoint` barrier.
    /// - **Stage T5 (Hosts)**: Updates ensemble backoff timers via `hosts_on_handle_error` or
    ///   `hosts_on_session_lost`.
    /// - **Stage T6 (Watches)**: Terminates or retains registered watchers in accordance with
    ///   configured compatibility policies.
    pub(crate) fn continue_teardown(&mut self, mut job: TeardownJob<T>, now: Millis) {
        loop {
            match job.stage {
                TeardownStage::Session => {
                    job.stage = TeardownStage::Snapshot;
                    if self.teardown_session_event(job.connected, job.forced) {
                        self.out.park(Work::Teardown(job));
                        return;
                    }
                }
                TeardownStage::Snapshot => {
                    job.stage = TeardownStage::FailFifo;
                    self.teardown.t3_started = true;
                    if job.forced {
                        self.teardown.forced_started = false;
                        if let Some(snap) = self.teardown.forced.take() {
                            let ForcedSnapshot { fifo, auth, .. } = snap;
                            job.entries = fifo;
                            job.auth = auth;
                        }
                    } else {
                        let (_tx, fifo) = self.requests.take_all();
                        job.entries = fifo;
                    }
                }
                TeardownStage::FailFifo => {
                    if !job.entries.is_empty() {
                        let mut entries = core::mem::take(&mut job.entries);
                        let stopped = self.teardown_peer_fail_pending_run(&mut entries, job.rc);
                        job.entries = entries;
                        if stopped {
                            self.out.park(Work::Teardown(job));
                            return;
                        }
                    }
                    job.stage = TeardownStage::Lockpoint;
                }
                TeardownStage::Lockpoint => {
                    job.stage = TeardownStage::Auth;
                    if self.compat.emits_lockpoints() {
                        self.out.push(Output::AuthLockpoint);
                        self.out.park(Work::Teardown(job));
                        return;
                    }
                }
                TeardownStage::Auth => {
                    job.stage = TeardownStage::Finish;
                    let mut tokens = core::mem::take(&mut job.auth);
                    if !job.forced {
                        tokens.extend(self.auth.take_tokens());
                    }
                    if !tokens.is_empty() {
                        let barrier = self.compat.emits_lockpoints();
                        self.out.push(Output::Auth {
                            tokens,
                            rc: job.rc,
                            barrier,
                        });
                        if barrier {
                            self.out.park(Work::Teardown(job));
                            return;
                        }
                    }
                }
                TeardownStage::Finish => {
                    self.teardown_finish(&job, now);
                    self.teardown.t3_started = false;
                    // Execute any deferred close requests that arrived while Stage T3 snapshot
                    // processing was in flight.
                    self.close_run_deferred(now);
                    return;
                }
            }
        }
    }

    /// Captures connection queue state and severs transport immediately upon an asynchronous
    /// forced close trigger.
    ///
    /// # Invariants
    /// If no network transport is active, this trigger is a no-op. Otherwise, it records
    /// connection status, sets the connection state to `NotConnected`, resets phase to `Idle`,
    /// executes Stage T1 cleanup, and moves outbound frames, FIFO requests, and pending
    /// authentication tokens into `teardown.forced` for deferred teardown.
    pub(crate) fn forced_close_trigger(&mut self, _now: Millis) {
        if !self.core.has_transport {
            return;
        }
        let connected = self.core.state.is_connected();
        self.set_state(State::NotConnected);
        self.teardown_t1();
        let (tx, fifo) = self.requests.take_all();
        let auth = self.auth.take_tokens();
        // The architecture permits only a single atomic snapshot; in the anomalous event that a
        // driver invokes forced closure multiple times, subsequent entries are merged to preserve
        // all tokens.
        match self.teardown.forced.as_mut() {
            Some(snap) => {
                snap.tx.extend(tx);
                snap.fifo.extend(fifo);
                snap.auth.extend(auth);
            }
            None => {
                self.teardown.forced = Some(ForcedSnapshot {
                    connected,
                    tx,
                    fifo,
                    auth,
                });
            }
        }
    }

    /// Initiates connection teardown with a pre-configured Stage T0 log entry and returns the
    /// derived protocol return code.
    ///
    /// Executes Stage T0 logging and Stage T1 transport reset before launching the multi-stage
    /// teardown job. If the connection has already been destroyed, it returns the return code
    /// immediately without spawning a job.
    pub(crate) fn teardown_with_line(
        &mut self,
        cause: Cause,
        line: Option<T0Line>,
        now: Millis,
    ) -> i32 {
        let rc = cause.rc_for(self.compat.disconnect_rc);
        // Destroyed connections have already discharged all pending operations; suppress spawning
        // a teardown job that could stall at an unresumed barrier.
        if self.close.destroyed {
            return rc;
        }
        let errno = line.as_ref().map(|l| l.errno).filter(|&e| e != 0);
        if let Some(line) = line {
            self.teardown_t0(line, rc);
        }
        let connected = self.core.state.is_connected();
        self.teardown_t1();
        self.teardown_start_job(cause, connected, errno, rc, false, now);
        rc
    }

    /// Records the root cause and operating system error number for a new teardown and dispatches
    /// execution starting at Stage T2.
    fn teardown_start_job(
        &mut self,
        cause: Cause,
        connected: bool,
        errno: Option<i32>,
        rc: i32,
        forced: bool,
        now: Millis,
    ) {
        self.teardown.last_cause = Some(cause);
        self.teardown.last_errno = errno;
        let job = TeardownJob {
            cause,
            stage: TeardownStage::Session,
            connected,
            entries: VecDeque::new(),
            auth: Vec::new(),
            rc,
            forced,
        };
        self.continue_teardown(job, now);
    }

    /// Initiates teardown due to an XID sequencing mismatch, formatting legacy log arguments with
    /// the reply XID followed by the expected FIFO XID.
    pub(crate) fn teardown_xid_mismatch(
        &mut self,
        reply_xid: i32,
        head_xid: i32,
        errno: i32,
        now: Millis,
    ) -> i32 {
        let line = T0Line::with(
            LogSite::SockUnexpectedXid,
            errno,
            vec![
                LogArg::I64(i64::from(reply_xid)),
                LogArg::I64(i64::from(head_xid)),
            ],
        );
        self.teardown_with_line(Cause::XidMismatch, Some(line), now)
    }

    /// Indicates whether a paused teardown sequence is actively executing within Stage T3 or later
    /// stages.
    pub(crate) fn teardown_t3_in_flight(&self) -> bool {
        self.teardown.t3_started && !self.close.destroyed
    }

    /// Extracts the deferred snapshot captured during a forced close operation.
    pub(crate) fn teardown_take_forced(&mut self) -> Option<ForcedSnapshot<T>> {
        self.teardown.forced.take()
    }

    /// Executes Stage T1: closes underlying network transport, resets SASL and packet decoder
    /// state, clears buffer pins, reverts connection phase to `Idle`, and re-initializes session
    /// parameters.
    pub(crate) fn teardown_t1(&mut self) {
        if self.core.has_transport {
            self.queue_close_transport();
        }
        self.teardown_peer_sasl_on_teardown();
        self.recv.reset();
        self.requests.clear_pins();
        self.core.phase = Phase::Idle;
        self.lose_transport();
        self.session = crate::session::SessionState::default();
    }

    /// Emits the Stage T0 socket error log entry adhering to legacy C client
    /// `handle_socket_error_msg` formatting.
    fn teardown_t0(&mut self, line: T0Line, rc: i32) {
        let server = self.current_server();
        let T0Line { site, errno, extra } = line;
        self.emit_log(site, move || {
            let mut args = vec![
                LogArg::Endpoint(server),
                LogArg::I64(i64::from(rc)),
                LogArg::I64(i64::from(errno)),
                LogArg::ErrnoStr(errno),
            ];
            args.extend(extra);
            args
        });
    }

    /// Constructs the standard Stage T0 log descriptor corresponding to a specific failure cause
    /// and OS error code.
    ///
    /// Causes without standard socket error logging representations (such as `AuthFailed` or
    /// `ForcedClose`) yield `None`.
    fn teardown_default_line(&self, cause: Cause, errno: i32, now: Millis) -> Option<T0Line> {
        let server = self.current_server();
        match cause {
            Cause::ConnectFailed => {
                let site = if !self.core.has_transport {
                    LogSite::SockConnectFailed
                } else if self.cfg.tls {
                    LogSite::SockRefusedTls
                } else {
                    LogSite::SockRefused
                };
                Some(T0Line::new(site, errno))
            }
            Cause::Io => Some(self.teardown_io_line(errno)),
            Cause::Eof | Cause::ProtocolError => Some(T0Line::new(LogSite::SockRecvFailed, errno)),
            Cause::SocketCreate => Some(T0Line::new(LogSite::SockSocketFailed, errno)),
            Cause::Tls => Some(T0Line::new(LogSite::SockTlsConnect, errno)),
            Cause::RecvTimeout => {
                // Calculate overdue duration matching C client semantics: -recv_to = (2/3 *
                // timeout) - idle_recv.
                let exceeded = self.timers_recv_overdue_ms(now);
                Some(T0Line::with(
                    LogSite::SockTimedOut,
                    errno,
                    vec![LogArg::Endpoint(server), LogArg::I64(exceeded)],
                ))
            }
            Cause::TlsHandshakeTimeout => {
                let exceeded = match self.core.phase {
                    Phase::TlsHandshaking { deadline } => until(now, deadline).saturating_neg(),
                    _ => 0,
                };
                Some(T0Line::with(
                    LogSite::SockTimedOut,
                    errno,
                    vec![LogArg::Endpoint(server), LogArg::I64(exceeded)],
                ))
            }
            Cause::XidMismatch => {
                let head = self.requests.fifo.front().map_or(0, |p| p.xid);
                Some(T0Line::with(
                    LogSite::SockUnexpectedXid,
                    errno,
                    vec![LogArg::I64(0), LogArg::I64(i64::from(head))],
                ))
            }
            Cause::SessionExpired => Some(T0Line::with(
                LogSite::SockSessionExpired,
                errno,
                vec![session_id_arg(self.core.client_id.session_id)],
            )),
            Cause::AuthFailed | Cause::RwServerFound | Cause::SaslTeardown | Cause::ForcedClose => {
                None
            }
        }
    }

    /// Determines the appropriate Stage T0 log descriptor for network I/O failures based on
    /// connection state.
    ///
    /// Distinguishes handshake transmission errors, pending transmit flush failures, and general
    /// receive errors.
    fn teardown_io_line(&self, errno: i32) -> T0Line {
        if self.session.handshake_pending && !self.session.connect_sent {
            T0Line::with(
                LogSite::SockHandshakeSend,
                errno,
                vec![LogArg::ErrnoStr(errno)],
            )
        } else if self.requests.unacked > 0 {
            T0Line::new(LogSite::SockFlushFailed, errno)
        } else {
            T0Line::new(LogSite::SockRecvFailed, errno)
        }
    }

    /// Executes Stage T2: dispatches session state transition events to registered watchers and
    /// indicates if execution paused at a barrier.
    fn teardown_session_event(&mut self, connected: bool, forced: bool) -> bool {
        let state = self.core.state;
        if state.is_unrecoverable() {
            let name = state.c_name();
            self.emit_log(LogSite::CleanupSessionState, || {
                vec![crate::log::str_arg(name.as_bytes())]
            });
            self.emit_session(state, Vec::new())
        } else if connected {
            self.emit_log(LogSite::CleanupSessionConnecting, Vec::new);
            self.emit_session(State::Connecting, Vec::new())
        } else if !forced && !self.compat.session_fanout_to_path_watchers {
            // Under native semantics, state transitions are routed exclusively to the dedicated
            // state watcher. Failed connection attempts emit a Connecting event to enable clients
            // to observe connection churn; forced close represents intentional client side
            // termination rather than a retryable disconnect.
            self.emit_session(State::Connecting, Vec::new())
        } else {
            false
        }
    }

    /// Executes Stages T4 through T6: logs previous connection metrics, transitions state,
    /// triggers backoff, and clears watchers.
    fn teardown_finish(&mut self, job: &TeardownJob<T>, now: Millis) {
        let server = self.current_server();
        let delay = self.hosts.delay;
        self.emit_log(LogSite::CleanupPrevious, || {
            vec![LogArg::Endpoint(server), LogArg::I64(i64::from(delay))]
        });
        if !job.forced && self.core.state.code() >= 0 {
            self.set_state(State::Closed);
        }
        self.out.push(Output::DrainPoint);
        if job.cause.runs_handle_error() {
            self.teardown_peer_hosts_on_handle_error(job.connected, now);
        }
        // Stage T6: Determine watch termination reason and apply watcher clearing policies based
        // on failure cause. Session expiry and authentication failures terminate watches when
        // clear_watches_on_expiry is active; transient network disconnects preserve watches unless
        // automatic re-registration is explicitly disabled.
        let reason = match job.cause {
            Cause::SessionExpired => TerminateReason::SessionExpired,
            Cause::AuthFailed | Cause::SaslTeardown => TerminateReason::AuthFailed,
            _ => TerminateReason::ConnectionLost,
        };
        self.teardown_peer_clear_watches(reason);
    }

    // The following peer wrapper methods route inter-module interactions through test interception
    // seams. In unit test configurations with stubbing enabled, these wrappers record invocations
    // and execute isolated behavior models, decoupling teardown testing from external module
    // dependencies.

    /// Dispatches watcher retrieval through `session_watchers`, intercepting with recorded test
    /// stub observations under test modes.
    pub(crate) fn teardown_peer_session_watchers(&mut self) -> Vec<T::Watcher> {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::SessionWatchers);
                return self.default_watcher.snapshot().into_iter().collect();
            }
        }
        self.session_watchers()
    }

    /// Enqueues outstanding watch registration frames via `queue_set_watches`, intercepting with
    /// test recording under unit test harnesses.
    pub(crate) fn teardown_peer_queue_set_watches(&mut self) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::QueueSetWatches);
                return;
            }
        }
        self.queue_set_watches();
    }

    /// Queues authentication resend packets via `queue_auth_resend`, recording the call site when
    /// executing within unit test harnesses.
    pub(crate) fn teardown_peer_queue_auth_resend(&mut self) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::QueueAuthResend);
                return;
            }
        }
        self.queue_auth_resend();
    }

    /// Initiates SASL authentication via `begin_sasl`, updating internal test phase state when
    /// executed under unit test harnesses.
    pub(crate) fn teardown_peer_begin_sasl(&mut self, now: Millis) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::BeginSasl);
                self.core.phase = Phase::Sasl(crate::state::SaslNegotiation::default());
                return;
            }
        }
        self.begin_sasl(now);
    }

    /// Discharges SASL teardown callbacks via `sasl_on_teardown`, intercepting with test harness
    /// logging when stubbed.
    pub(crate) fn teardown_peer_sasl_on_teardown(&mut self) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::SaslOnTeardown);
                return;
            }
        }
        self.sasl_on_teardown();
    }

    /// Cleans up SASL resources on connection destruction via `sasl_on_destroy`, recording test
    /// events when stubbed.
    pub(crate) fn teardown_peer_sasl_on_destroy(&mut self) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::SaslOnDestroy);
                return;
            }
        }
        self.sasl_on_destroy();
    }

    /// Dispatches host connection failure accounting via `hosts_on_session_lost` if a session was
    /// established, or `hosts_on_handle_error` otherwise.
    pub(crate) fn teardown_peer_hosts_on_handle_error(&mut self, connected: bool, now: Millis) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::HostsOnHandleError);
                return;
            }
        }
        if connected {
            self.hosts_on_session_lost(now);
        } else {
            self.hosts_on_handle_error(now);
        }
    }

    /// Clears registered client watchers via `clear_watches` according to the provided termination
    /// reason.
    pub(crate) fn teardown_peer_clear_watches(&mut self, reason: TerminateReason) {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::ClearWatches(reason));
                return;
            }
        }
        self.clear_watches(reason);
    }

    /// Systematically fails pending FIFO operations up through the most recent Direct request,
    /// returning true if execution yielded at a Direct boundary.
    pub(crate) fn teardown_peer_fail_pending_run(
        &mut self,
        entries: &mut VecDeque<Pending<T>>,
        rc: i32,
    ) -> bool {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::FailPendingRun(rc));
                let last_direct = entries
                    .iter()
                    .rposition(|p| p.route == crate::request::Route::Direct);
                let count = last_direct.map_or(entries.len(), |i| i.saturating_add(1));
                for _ in 0..count {
                    if let Some(p) = entries.pop_front() {
                        self.out.push(Output::Completion {
                            token: p.token,
                            route: p.route,
                            result: crate::output::OpResult::teardown(rc),
                            delivery_logs: Vec::new(),
                        });
                    }
                }
                return last_direct.is_some();
            }
        }
        self.fail_pending_run(entries, rc)
    }

    /// Encodes a length-prefixed request packet frame carrying the transaction ID, opcode, and
    /// payload body.
    pub(crate) fn teardown_peer_request_frame(
        &mut self,
        xid: i32,
        op_type: i32,
        body: &[u8],
    ) -> Vec<u8> {
        #[cfg(test)]
        {
            if let Some(calls) = self.teardown.stub.as_mut() {
                calls.push(PeerCall::RequestFrame(xid, op_type));
                let len = i32::try_from(body.len().saturating_add(8)).unwrap_or(i32::MAX);
                let mut out = Vec::new();
                out.extend_from_slice(&len.to_be_bytes());
                out.extend_from_slice(&xid.to_be_bytes());
                out.extend_from_slice(&op_type.to_be_bytes());
                out.extend_from_slice(body);
                return out;
            }
        }
        Self::request_frame(xid, op_type, body)
    }
}

/// Shared test fixtures and stub helpers for validating session lifecycle, connection closure,
/// teardown stages, and authentication.
#[cfg(test)]
pub(crate) mod test_support {
    #![allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use zk_jute::JString;

    use super::PeerCall;
    use crate::action::ConnectStart;
    use crate::auth::AuthEntry;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::connection::Connection;
    use crate::hooks::{AllLevels, DefaultWatcher, Rng, Tokens};
    use crate::log::LogSite;
    use crate::output::{Output, TerminateReason};
    use crate::request::Route;
    use crate::requests::{Pending, Shape};
    use crate::secret::SecretBytes;
    use crate::time::Millis;

    /// Mock token type definitions utilized throughout protocol unit test suites.
    pub(crate) struct Tk;
    impl Tokens for Tk {
        type Completion = u32;
        type Watcher = u32;
        type Auth = u32;
    }

    struct FixedRng;
    impl Rng for FixedRng {
        fn on_resolution(&mut self) {}
        fn random(&mut self) -> i64 {
            0
        }
        fn drand48(&mut self) -> f64 {
            0.0
        }
    }

    /// Sentinel identifier representing the default state watcher in unit test assertions.
    pub(crate) const DEFAULT_WATCHER: u32 = 7;

    struct Dw;
    impl DefaultWatcher<u32> for Dw {
        fn snapshot(&self) -> Option<u32> {
            Some(DEFAULT_WATCHER)
        }
    }

    /// Instantiates a test connection configured with custom parameters and an active test
    /// interception seam.
    pub(crate) fn conn_with(cfg: Config, compat: Compat, mode: DriverMode) -> Connection<Tk> {
        let mut c = Connection::new(
            cfg,
            compat,
            mode,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(Dw),
            Millis(0),
        );
        c.teardown.stub = Some(Vec::new());
        c
    }

    /// Instantiates a test connection using default test configuration settings and an active test
    /// seam.
    pub(crate) fn conn(compat: Compat, mode: DriverMode) -> Connection<Tk> {
        conn_with(Config::legacy(30_000), compat, mode)
    }

    /// Constructs a mock pending FIFO entry with the specified transaction ID, routing model, and
    /// completion token.
    pub(crate) fn pend(xid: i32, route: Route, token: u32) -> Pending<Tk> {
        Pending {
            xid,
            shape: Shape::Void,
            route,
            token,
            watch: None,
            unwatch: None,
            written: true,
        }
    }

    /// Appends an authentication credential entry holding an active completion token to the
    /// connection.
    pub(crate) fn add_auth(c: &mut Connection<Tk>, token: u32) {
        c.auth.list.push(AuthEntry {
            scheme: JString::Bytes(b"digest".to_vec()),
            cred: SecretBytes::new(b"u:p".to_vec()),
            done: Some(token),
        });
    }

    /// Synthesizes a serialized 37-byte `ConnectResponse` packet body with specified session
    /// parameters.
    pub(crate) fn response(timeout: i32, session_id: i64, read_only: bool) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend_from_slice(&0i32.to_be_bytes());
        b.extend_from_slice(&timeout.to_be_bytes());
        b.extend_from_slice(&session_id.to_be_bytes());
        b.extend_from_slice(&16i32.to_be_bytes());
        b.extend_from_slice(&[0xAB; 16]);
        b.push(u8::from(read_only));
        b
    }

    /// Simulates driver events transitioning an in-progress TCP connection through socket
    /// readiness and handshake transmission.
    pub(crate) fn connect(c: &mut Connection<Tk>, now: Millis) {
        c.handle_connect_started(ConnectStart::InProgress, now);
        c.handle_tcp_connected(now);
        c.handle_transport_ready(now);
        c.on_connect_sent();
    }

    /// Completes connection handshake and processes a mock `ConnectResponse` for session ID `0x55`,
    /// draining initial outputs.
    pub(crate) fn establish(c: &mut Connection<Tk>, now: Millis) {
        connect(c, now);
        let body = response(30_000, 0x55, false);
        let _ = c.on_connect_response(&body, now);
        let _ = drain(c, now);
        let _ = calls(c);
    }

    /// Normalized representation of connection output events for straightforward test pattern
    /// matching and assertions.
    #[derive(Clone, Debug, PartialEq, Eq)]
    pub(crate) enum Ev {
        /// Log diagnostic event carrying the emitting call site.
        Log(LogSite),
        /// Session state change notification carrying the integer state code, barrier flag, and
        /// affected watcher IDs.
        Session(i32, bool, Vec<u32>),
        /// Asynchronous request completion carrying the token, return code, and direct routing
        /// flag.
        Done(u32, i32, bool),
        /// Authentication completion event carrying affected tokens, return code, and barrier flag.
        Auth(Vec<u32>, i32, bool),
        /// Authentication synchronization lock-point barrier.
        Lockpoint,
        /// Session credential establishment event carrying the assigned 64-bit session ID.
        ClientId(i64),
        /// Watch termination notification carrying the underlying termination reason.
        Terminated(TerminateReason),
        /// Watch event notification.
        Watch,
        /// Output queue drain marker indicating completion of a processing phase.
        Drain,
        /// Cooperative execution barrier requiring driver resumption.
        Stop,
    }

    /// Converts a raw protocol [`Output`] event into its normalized test representation.
    pub(crate) fn ev(o: Output<Tk>) -> Ev {
        match o {
            Output::Log(r) => Ev::Log(r.site),
            Output::Session {
                state,
                barrier,
                watchers,
                ..
            } => Ev::Session(state.code(), barrier, watchers),
            Output::Completion {
                token,
                route,
                result,
                ..
            } => Ev::Done(token, result.rc, route == Route::Direct),
            Output::Auth {
                tokens,
                rc,
                barrier,
            } => Ev::Auth(tokens, rc, barrier),
            Output::AuthLockpoint => Ev::Lockpoint,
            Output::ClientId(id) => Ev::ClientId(id.session_id),
            Output::WatchTerminated { reason, .. } => Ev::Terminated(reason),
            Output::Watch { .. } => Ev::Watch,
            Output::DrainPoint => Ev::Drain,
        }
    }

    /// Exhausts all queued outputs from the connection, automatically resuming paused execution at
    /// each cooperative stop barrier.
    pub(crate) fn drain(c: &mut Connection<Tk>, now: Millis) -> Vec<Ev> {
        let mut evs = Vec::new();
        loop {
            if let Some(o) = c.poll_output() {
                evs.push(ev(o));
                continue;
            }
            match c.take_stopped() {
                Some(s) => {
                    evs.push(Ev::Stop);
                    c.resume(s, now);
                }
                None => break,
            }
        }
        evs
    }

    /// Collects queued output events up to the first encountered stop barrier, returning the
    /// collected events and optional resumption handle.
    pub(crate) fn until_stop(c: &mut Connection<Tk>) -> (Vec<Ev>, Option<crate::Stopped>) {
        let mut evs = Vec::new();
        while let Some(o) = c.poll_output() {
            evs.push(ev(o));
        }
        (evs, c.take_stopped())
    }

    /// Filters out diagnostic log entries from an event slice to simplify assertion on state
    /// events and completions.
    pub(crate) fn no_logs(evs: &[Ev]) -> Vec<Ev> {
        evs.iter()
            .filter(|e| !matches!(e, Ev::Log(_)))
            .cloned()
            .collect()
    }

    /// Extracts call site identifiers for all diagnostic log entries in an event slice.
    pub(crate) fn logs(evs: &[Ev]) -> Vec<LogSite> {
        evs.iter()
            .filter_map(|e| match e {
                Ev::Log(s) => Some(*s),
                _ => None,
            })
            .collect()
    }

    /// Retrieves and clears all recorded inter-module peer invocations captured by the test
    /// interception seam.
    pub(crate) fn calls(c: &mut Connection<Tk>) -> Vec<PeerCall> {
        c.teardown
            .stub
            .as_mut()
            .map(core::mem::take)
            .unwrap_or_default()
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

    use super::test_support::*;
    use super::*;
    use crate::action::{ConnectStart, TlsConfigStep, TlsResourceAt};
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::request::Route;
    use zk_jute::JString;

    const CL: i32 = rc::ZCONNECTIONLOSS;

    /// Constructs an established legacy connection primed with two Queued entries flanking an
    /// intermediate Direct entry and an authentication token.
    fn loaded(mode: DriverMode) -> Connection<Tk> {
        let mut c = conn(Compat::LEGACY_C, mode);
        establish(&mut c, Millis(10));
        c.requests.push_pending(pend(1, Route::Queued, 11));
        c.requests.push_pending(pend(2, Route::Direct, 12));
        c.requests.push_pending(pend(3, Route::Queued, 13));
        add_auth(&mut c, 21);
        c
    }

    #[test]
    fn the_teardown_keeps_the_os_errno() {
        let mut c = conn(Compat::NATIVE, DriverMode::NATIVE);
        establish(&mut c, Millis(10));
        let _ = c.handle_transport_failed(TransportFailure::Io { errno: 54 }, Millis(20));
        assert_eq!(c.last_teardown_cause(), Some(Cause::Io));
        assert_eq!(c.last_teardown_errno(), Some(54));
        // An errno value of zero denotes that no underlying operating system error code was
        // provided.
        let mut c = conn(Compat::NATIVE, DriverMode::NATIVE);
        establish(&mut c, Millis(10));
        let _ = c.handle_transport_failed(TransportFailure::Io { errno: 0 }, Millis(20));
        assert_eq!(c.last_teardown_errno(), None);
    }

    #[test]
    fn teardown_stops_at_each_barrier_then_resumes() {
        let mut c = loaded(DriverMode::ST);
        let rc = c.handle_transport_failed(TransportFailure::Eof, Millis(20));
        assert_eq!(rc, CL);
        assert_eq!(c.state(), State::Connected, "T2 sees the old state");
        assert!(!c.has_transport());
        assert!(c.is_suspended());
        assert_eq!(c.last_teardown_cause(), Some(Cause::Eof));

        // Stages T0 (error logging) and T2 (session watcher notification).
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(
            evs,
            vec![
                Ev::Log(LogSite::SockRecvFailed),
                Ev::Log(LogSite::CleanupSessionConnecting),
                Ev::Session(1, true, vec![DEFAULT_WATCHER]),
            ]
        );
        // Requests submitted during the Stage T2 callback are captured in the subsequent Stage T3
        // snapshot.
        c.requests.push_pending(pend(4, Route::Queued, 14));
        c.resume(stop.unwrap(), Millis(20));

        // Stage T3: execute failure dispatch up to the first Direct routing boundary.
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Done(11, CL, false), Ev::Done(12, CL, true)]);
        c.resume(stop.unwrap(), Millis(20));

        // Stage T3: fail remaining queued entries, followed by the authentication lock-point
        // barrier.
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(
            evs,
            vec![
                Ev::Done(13, CL, false),
                Ev::Done(14, CL, false),
                Ev::Lockpoint
            ]
        );
        c.resume(stop.unwrap(), Millis(20));

        // Stage T3: deliver authentication failure completions.
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Auth(vec![21], CL, true)]);
        assert_eq!(c.state(), State::Connected);
        c.resume(stop.unwrap(), Millis(20));

        // Stages T4 through T6: emit cleanup logging, drain markers, host failure accounting, and
        // watcher teardown.
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Log(LogSite::CleanupPrevious), Ev::Drain]);
        assert!(stop.is_none());
        assert!(!c.is_suspended());
        assert_eq!(c.state(), State::Closed);
        assert_eq!(c.core.phase, Phase::Idle);
        assert_eq!(
            calls(&mut c),
            vec![
                PeerCall::SaslOnTeardown,
                PeerCall::SessionWatchers,
                PeerCall::FailPendingRun(CL),
                PeerCall::FailPendingRun(CL),
                PeerCall::HostsOnHandleError,
                PeerCall::ClearWatches(TerminateReason::ConnectionLost),
            ]
        );
        let left = c.into_leftovers();
        assert!(left.completions.is_empty() && left.auth.is_empty());
    }

    #[test]
    fn teardown_without_barriers_runs_in_one_call() {
        let mut c = conn(Compat::NATIVE, DriverMode::NATIVE);
        establish(&mut c, Millis(10));
        c.requests.push_pending(pend(1, Route::Queued, 11));
        add_auth(&mut c, 21);
        let rc = c.handle_transport_failed(TransportFailure::Io { errno: 104 }, Millis(20));
        assert_eq!(rc, CL);
        assert!(!c.is_suspended());
        assert_eq!(
            no_logs(&drain(&mut c, Millis(20))),
            vec![
                Ev::Session(1, false, vec![DEFAULT_WATCHER]),
                Ev::Done(11, CL, false),
                Ev::Auth(vec![21], CL, false),
                Ev::Drain,
            ]
        );
        assert_eq!(c.state(), State::Closed);
    }

    #[test]
    fn stopped_teardown_job_is_dropped_with_destroy() {
        let mut c = loaded(DriverMode::ST);
        let _ = c.teardown(Cause::RecvTimeout, errno::ETIMEDOUT, Millis(40_000));
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs.first(), Some(&Ev::Log(LogSite::SockTimedOut)));
        c.destroy(Millis(40_000));
        c.resume(stop.unwrap(), Millis(40_000));
        let evs = no_logs(&drain(&mut c, Millis(40_000)));
        let cl = rc::ZCLOSING;
        assert_eq!(
            evs,
            vec![
                Ev::Done(11, cl, false),
                Ev::Done(12, cl, true),
                Ev::Done(13, cl, false),
                Ev::Lockpoint,
                Ev::Auth(vec![21], cl, false),
                Ev::Drain,
            ]
        );
        assert!(c.into_leftovers().completions.is_empty());
    }

    #[test]
    fn rc_per_cause_and_profile() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        establish(&mut c, Millis(1));
        let rc = c.handle_transport_failed(TransportFailure::TlsHandshake { errno: 0 }, Millis(2));
        assert_eq!(rc, rc::ZSSLCONNECTIONERROR);
        assert_eq!(c.last_teardown_cause(), Some(Cause::Tls));

        let mut n = conn(Compat::NATIVE, DriverMode::NATIVE);
        establish(&mut n, Millis(1));
        let rc = n.handle_transport_failed(TransportFailure::TlsHandshake { errno: 0 }, Millis(2));
        assert_eq!(rc, CL);

        let mut n = conn(Compat::NATIVE, DriverMode::NATIVE);
        establish(&mut n, Millis(1));
        n.set_state(State::AuthFailed);
        let rc = n.teardown(Cause::AuthFailed, 0, Millis(2));
        assert_eq!(rc, rc::ZAUTHFAILED);
        let evs = drain(&mut n, Millis(2));
        assert_eq!(evs.first(), Some(&Ev::Log(LogSite::CleanupSessionState)));
        assert_eq!(n.state(), State::AuthFailed);
        assert!(calls(&mut n).contains(&PeerCall::ClearWatches(TerminateReason::AuthFailed)));
    }

    #[test]
    fn transport_failure_sites() {
        // Prior to transport initialization, only socket creation and initial TCP refusal failures
        // trigger teardown.
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        assert_eq!(
            c.handle_transport_failed(TransportFailure::Eof, Millis(1)),
            rc::ZOK
        );
        assert!(drain(&mut c, Millis(1)).is_empty());
        let rc = c.handle_transport_failed(TransportFailure::SocketCreate { errno: 24 }, Millis(1));
        assert_eq!(rc, rc::ZSYSTEMERROR);
        let evs = drain(&mut c, Millis(1));
        assert_eq!(c.state(), State::Closed);
        assert_eq!(
            evs,
            vec![
                Ev::Log(LogSite::SockSocketFailed),
                Ev::Lockpoint,
                Ev::Stop,
                Ev::Log(LogSite::CleanupPrevious),
                Ev::Drain
            ]
        );
        let rc = c.handle_transport_failed(TransportFailure::TcpRefused { errno: 61 }, Millis(1));
        assert_eq!(rc, CL);
        assert_eq!(
            logs(&drain(&mut c, Millis(1))).first(),
            Some(&LogSite::SockConnectFailed)
        );

        // Connection refused during nonblocking in-progress TCP connection attempt.
        c.handle_connect_started(ConnectStart::InProgress, Millis(2));
        let _ = c.handle_transport_failed(TransportFailure::TcpRefused { errno: 61 }, Millis(2));
        assert_eq!(
            logs(&drain(&mut c, Millis(2))).first(),
            Some(&LogSite::SockRefused)
        );

        // Socket I/O send failure occurring prior to the completion of the initial ConnectRequest
        // handshake frame transmission.
        c.handle_connect_started(ConnectStart::InProgress, Millis(3));
        c.handle_tcp_connected(Millis(3));
        c.handle_transport_ready(Millis(3));
        let _ = drain(&mut c, Millis(3));
        let _ = c.handle_transport_failed(TransportFailure::Io { errno: 32 }, Millis(3));
        assert_eq!(
            logs(&drain(&mut c, Millis(3))).first(),
            Some(&LogSite::SockHandshakeSend)
        );

        // Verify call sites emitted during TLS context and connection resource allocation
        // failures.
        c.handle_connect_started(ConnectStart::InProgress, Millis(4));
        let _ = c.handle_transport_failed(
            TransportFailure::TlsResource {
                at: TlsResourceAt::ContextNew,
                errno: 0,
            },
            Millis(4),
        );
        let l = logs(&drain(&mut c, Millis(4)));
        assert_eq!(
            &l[..2],
            &[LogSite::TlsContextNew, LogSite::SockTlsContextNew]
        );
        c.handle_connect_started(ConnectStart::InProgress, Millis(5));
        let _ = c.handle_transport_failed(
            TransportFailure::TlsResource {
                at: TlsResourceAt::ConnectionNew,
                errno: 0,
            },
            Millis(5),
        );
        assert_eq!(
            logs(&drain(&mut c, Millis(5))).first(),
            Some(&LogSite::SockTlsConnectionNew)
        );
    }

    #[test]
    fn tls_config_failure_is_not_a_teardown() {
        let mut cfg = Config::legacy(30_000);
        cfg.tls = true;
        let mut c = conn_with(cfg, Compat::LEGACY_C, DriverMode::MT);
        c.handle_connect_started(ConnectStart::InProgress, Millis(1));
        assert_eq!(c.state(), State::SslConnecting);
        let rc = c.handle_transport_failed(
            TransportFailure::TlsConfig {
                step: TlsConfigStep::CaFile,
                file: JString::Bytes(b"/ca.pem".to_vec()),
            },
            Millis(1),
        );
        assert_eq!(rc, rc::ZSSLCONNECTIONERROR);
        assert_eq!(c.take_errno_hint(), Some(errno::EINVAL));
        assert_eq!(c.core.phase, Phase::TlsConfigFailed);
        assert_eq!(c.state(), State::SslConnecting);
        assert!(c.has_transport());
        assert_eq!(drain(&mut c, Millis(1)), vec![Ev::Log(LogSite::TlsCaFile)]);
        assert_eq!(c.last_teardown_cause(), None);
        let rc = c.handle_transport_failed(TransportFailure::Poll { errno: 4 }, Millis(1));
        assert_eq!(rc, rc::ZSYSTEMERROR);
        assert!(c.has_transport());
    }

    #[test]
    fn expiry_logs_state_and_rc() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::MT);
        establish(&mut c, Millis(1));
        let _ = c.teardown(Cause::Eof, 0, Millis(2));
        let _ = drain(&mut c, Millis(2));
        connect(&mut c, Millis(3));
        let body = response(30_000, 0x66, false);
        assert_eq!(
            c.on_connect_response(&body, Millis(3)),
            crate::recv::Flow::Done(rc::ZSESSIONEXPIRED)
        );
        assert_eq!(c.state(), State::ExpiredSession);
        assert_eq!(c.take_errno_hint(), Some(errno::ESTALE));
        let evs = drain(&mut c, Millis(3));
        assert_eq!(
            evs,
            vec![
                Ev::Log(LogSite::InitiatedConnectionLate),
                Ev::Log(LogSite::SockSessionExpired),
                Ev::Log(LogSite::CleanupSessionState),
                Ev::Session(-112, false, vec![DEFAULT_WATCHER]),
                Ev::Lockpoint,
                Ev::Stop,
                Ev::Log(LogSite::CleanupPrevious),
                Ev::Drain,
            ]
        );
    }

    #[test]
    fn forced_close_trigger_then_pending_stages() {
        let mut c = loaded(DriverMode::ST);
        c.requests.push_tail(OutFrame::plain(
            crate::requests::FrameKind::Request { xid: 3 },
            vec![1, 2, 3],
        ));
        c.forced_close_trigger(Millis(5));
        assert_eq!(c.state(), State::NotConnected);
        assert_eq!(c.core.phase, Phase::Idle);
        assert!(!c.has_transport());
        assert!(c.requests.fifo.is_empty() && c.requests.tx.is_empty());
        assert!(c.auth.list.iter().all(|e| e.done.is_none()));
        assert_eq!(c.auth.list.len(), 1, "the entry stays for the resend");
        assert!(drain(&mut c, Millis(5)).is_empty());
        assert!(
            c.interest
                .queued
                .iter()
                .any(|a| matches!(a, crate::action::Action::CloseTransport))
        );

        // Redundant forced close triggers are idempotent and ignored.
        c.handle_forced_close(Millis(5));
        // Requests submitted after a forced close trigger remain queued for the subsequent
        // connection lifecycle.
        c.requests.push_pending(pend(9, Route::Queued, 19));

        assert!(c.run_pending_teardown(Millis(6)));
        assert_eq!(c.last_teardown_cause(), Some(Cause::ForcedClose));
        assert!(!c.run_pending_teardown(Millis(6)), "suspended");
        let evs = drain(&mut c, Millis(6));
        assert_eq!(
            no_logs(&evs),
            vec![
                Ev::Session(1, true, vec![DEFAULT_WATCHER]),
                Ev::Stop,
                Ev::Done(11, CL, false),
                Ev::Done(12, CL, true),
                Ev::Stop,
                Ev::Done(13, CL, false),
                Ev::Lockpoint,
                Ev::Stop,
                Ev::Auth(vec![21], CL, true),
                Ev::Stop,
                Ev::Drain,
            ]
        );
        assert_eq!(
            logs(&evs),
            vec![LogSite::CleanupSessionConnecting, LogSite::CleanupPrevious]
        );
        assert_eq!(c.state(), State::NotConnected);
        assert_eq!(c.requests.fifo.len(), 1);
        assert!(!calls(&mut c).contains(&PeerCall::HostsOnHandleError));
        assert!(!c.run_pending_teardown(Millis(7)));
    }

    #[test]
    fn forced_stages_wait_for_a_suspended_stage() {
        let mut c = loaded(DriverMode::ST);
        // Simulate a finalize stop barrier by parking work behind an authentication lock-point.
        c.out.push(Output::AuthLockpoint);
        c.out.park(Work::Hold);
        c.forced_close_trigger(Millis(5));
        assert!(!c.run_pending_teardown(Millis(5)));
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Lockpoint]);
        assert!(!c.run_pending_teardown(Millis(5)));
        c.resume(stop.unwrap(), Millis(5));
        assert!(c.run_pending_teardown(Millis(6)));
        let evs = no_logs(&drain(&mut c, Millis(6)));
        assert_eq!(
            evs.first(),
            Some(&Ev::Session(1, true, vec![DEFAULT_WATCHER]))
        );
    }

    #[test]
    fn close_from_the_t2_callback_takes_the_snapshot() {
        let mut c = loaded(DriverMode::ST);
        c.forced_close_trigger(Millis(5));
        assert!(c.run_pending_teardown(Millis(6)));
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(
            no_logs(&evs),
            vec![Ev::Session(1, true, vec![DEFAULT_WATCHER])]
        );
        // An explicit close executed within the watcher callback preemptively discharges the
        // snapshot.
        c.requests.push_pending(pend(9, Route::Queued, 19));
        c.begin_close(Millis(6));
        let (evs, stop2) = until_stop(&mut c);
        let cl = rc::ZCLOSING;
        assert_eq!(evs, vec![Ev::Done(11, cl, false), Ev::Done(12, cl, true)]);
        c.resume(stop2.unwrap(), Millis(6));
        let evs = no_logs(&drain(&mut c, Millis(6)));
        assert_eq!(
            evs,
            vec![
                Ev::Done(13, cl, false),
                Ev::Done(19, cl, false),
                Ev::Lockpoint,
                Ev::Stop,
                Ev::Auth(vec![21], cl, false),
                Ev::Drain,
            ]
        );
        // Teardown resumption subsequently observes an already exhausted snapshot.
        c.resume(stop.unwrap(), Millis(6));
        let evs = no_logs(&drain(&mut c, Millis(6)));
        assert_eq!(evs, vec![Ev::Lockpoint, Ev::Stop, Ev::Drain]);
        assert!(c.into_leftovers().completions.is_empty());
    }

    /// Simulates network driver operations by initializing transport, transmitting the
    /// ConnectRequest frame, and ingesting the handshake response.
    fn open_and_send_connect(c: &mut Connection<Tk>, now: Millis) {
        c.handle_connect_started(ConnectStart::InProgress, now);
        c.handle_tcp_connected(now);
        c.handle_transport_ready(now);
        let mut out = Vec::new();
        let n = c.poll_transmit(&mut out, usize::MAX);
        assert_eq!(c.requests.tx.len(), 1, "the Connect frame goes alone");
        assert_eq!(c.requests.tx[0].kind, crate::requests::FrameKind::Connect);
        assert_eq!(n, out.len());
        c.handle_transmitted(n, now);
        let _ = c.on_connect_response(&response(30_000, 0x55, false), now);
        let _ = drain(c, now);
    }

    /// Submits a queued SetData protocol request containing `len` payload bytes and returns its
    /// assigned transaction ID.
    fn queue_set(c: &mut Connection<Tk>, len: usize) -> i32 {
        let req = crate::request::Request {
            op: crate::request::Op::SetData {
                path: crate::request::PathIn::Legacy(Some(b"/a".to_vec())),
                data: zk_jute::JBuffer::Bytes(vec![0x5a; len]),
                version: -1,
            },
            token: 5,
            route: Route::Queued,
            defect: crate::request::InputDefect::default(),
        };
        match c.submit(req, Millis(0)) {
            Ok(crate::request::Submitted::Queued { xid }) => xid,
            _ => panic!("submit failed"),
        }
    }

    /// Verifies that lent transmission frames are safely unpinned and purged during Stage T1
    /// teardown and Stage T3 snapshotting, preventing partial or corrupted frame leakage onto
    /// subsequent transports.
    #[test]
    fn a_lent_frame_goes_away_before_the_next_transport() {
        let now = Millis(10);
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        open_and_send_connect(&mut c, now);
        let big = 1024 * 1024;
        let _ = queue_set(&mut c, big);
        let _ = queue_set(&mut c, 10);
        let mut out = Vec::new();
        let n = c.poll_transmit(&mut out, usize::MAX);
        assert!(c.requests.tx[0].is_lent());
        assert_eq!(n, out.len());
        c.handle_transmitted(1000, now);

        let _ = c.handle_transport_failed(TransportFailure::Eof, now);
        assert!(c.is_suspended(), "the job waits at the T2 barrier");
        assert!(c.poll_action(now).is_none(), "no connect while it waits");
        let head = &c.requests.tx[0];
        assert!(head.is_lent() && !head.pinned && c.requests.unacked == 0);
        let _ = drain(&mut c, now);
        assert!(!c.is_suspended());
        assert!(c.requests.tx.is_empty(), "T3 drops the lent frame");

        // Opening a subsequent transport verifies that no residual lent frames remain in the
        // queue, ensuring newly submitted requests transmit intact and in order.
        open_and_send_connect(&mut c, now);
        let small = queue_set(&mut c, 10);
        let large = queue_set(&mut c, big);
        let mut wire = Vec::new();
        for _ in 0..2 {
            let mut out = Vec::new();
            let n = c.poll_transmit(&mut out, usize::MAX);
            c.handle_transmitted(n, now);
            wire.extend_from_slice(&out);
        }
        assert!(c.requests.tx.is_empty());
        use zk_jute::Record as _;
        let mut r = zk_jute::Reader::new(&wire);
        for (xid, len) in [(small, 10), (large, big)] {
            let _len = r.read_i32().unwrap();
            let hdr = zk_jute::proto::RequestHeader::decode(&mut r).unwrap();
            let rec = zk_jute::proto::SetDataRequest::decode(&mut r).unwrap();
            assert_eq!(hdr.xid, xid);
            assert_eq!(rec.data.as_bytes(), Some(&vec![0x5a; len][..]));
        }
        assert_eq!(r.remaining(), 0);
    }

    #[test]
    fn forced_close_with_no_transport_does_nothing() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        c.forced_close_trigger(Millis(1));
        assert!(c.teardown.forced.is_none());
        assert!(!c.run_pending_teardown(Millis(1)));
        assert_eq!(c.state(), State::NotConnected);
    }

    #[test]
    fn epoch_advances_and_finalize_stops_after_teardown() {
        let mut c = conn(Compat::LEGACY_C, DriverMode::ST);
        connect(&mut c, Millis(1));
        let body = response(30_000, 0x55, false);
        assert_eq!(
            c.on_connect_response(&body, Millis(1)),
            crate::recv::Flow::Stop
        );
        let (_, stop) = until_stop(&mut c);
        // Trigger a teardown while paused at a finalize authentication lock-point barrier.
        let epoch = c.core.epoch;
        let _ = c.teardown(Cause::Eof, 0, Millis(2));
        assert_ne!(c.core.epoch, epoch);
        let _ = drain(&mut c, Millis(2));
        let _ = calls(&mut c);
        c.resume(stop.unwrap(), Millis(2));
        assert!(drain(&mut c, Millis(2)).is_empty());
        assert!(!calls(&mut c).contains(&PeerCall::QueueAuthResend));
    }
}
