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

//! Session teardown, orderly drain, client handle abandonment, and destruction.
//!
//! Owns [`CloseState`] and orchestrates shutdown mechanics. Depending on `Compat::close_mode`,
//! implements either legacy C semantics (immediately failing outstanding requests and
//! authentication tokens with `ZCLOSING`) or modern native drain semantics (transmitting a `Close`
//! request frame and awaiting protocol acknowledgment prior to resource disposal).

use std::collections::VecDeque;

use crate::action::CloseProgress;
use crate::compat::CloseMode;
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::output::{Output, TerminateReason, Work};
use crate::rc;
use crate::recv::Flow;
use crate::requests::{FrameKind, OutFrame, Pending};
use crate::state::Phase;
use crate::teardown::ForcedSnapshot;
use crate::time::Millis;

/// Maximum duration in milliseconds permitted for flushing queued outbound frames during graceful
/// close.
pub const CLOSE_FLUSH_MS: u32 = 3000;
/// Ingress wait timeout in milliseconds for observing inbound server responses following close
/// flush.
pub const CLOSE_WAIT_MS: u32 = 1500;

/// Underlying trigger initiating the connection termination sequence.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum CloseKind {
    /// Orderly client close initiated via `begin_close`.
    Begin,
    /// Unconditional teardown and resource release initiated via `destroy`.
    Destroy,
    /// Fast connection termination without wire handshake initiated via `abandon`.
    Abandon,
}

/// Discrete execution phase within the connection shutdown pipeline.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum CloseStage {
    /// Failing pending FIFO requests and forced-close snapshots with `ZCLOSING`.
    FailFifo,
    /// Emitting an authentication mutex synchronization lockpoint barrier for legacy drivers.
    Lockpoint,
    /// Completing outstanding session authentication tokens with `ZCLOSING`.
    Auth,
    /// Emitting a dispatch queue drain synchronization point and finalizing resource release.
    Finish,
}

/// Encapsulates state and pending tokens for a staged shutdown pipeline across barrier boundaries.
pub(crate) struct CloseJob<T: Tokens> {
    /// Shutdown variant driving the sequence.
    pub(crate) kind: CloseKind,
    /// Next stage to execute upon pipeline progression.
    pub(crate) stage: CloseStage,
    /// Remaining request tokens scheduled for failure completion.
    pub(crate) entries: VecDeque<Pending<T>>,
    /// Outstanding authentication tokens scheduled for failure completion.
    pub(crate) auth: Vec<T::Auth>,
}

impl<T: Tokens> CloseJob<T> {
    /// Extracts all completion and authentication tokens from this shutdown job.
    pub(crate) fn into_tokens(self) -> (Vec<T::Completion>, Vec<T::Auth>) {
        (
            self.entries.into_iter().map(|p| p.token).collect(),
            self.auth,
        )
    }
}

/// Internal tracking state governing shutdown progress, timeouts, and completion outcomes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct CloseState {
    /// Indicates whether a close or destroy sequence has been initiated.
    pub(crate) closing: bool,
    /// Monotonically allocated transaction identifier (xid) assigned to the outbound `CLOSE_OP`
    /// frame.
    pub(crate) close_xid: Option<i32>,
    /// Indicates whether `CLOSE_OP` was enqueued while connected, allowing outbound frames to
    /// bypass phase gates.
    pub(crate) flush_established: bool,
    /// Wall-clock monotonic deadline for concluding a native drain close.
    pub(crate) deadline: Option<Millis>,
    /// Indicates whether the graceful close sequence has achieved terminal completion.
    pub(crate) complete: bool,
    /// Final return code resulting from the close sequence (e.g., `ZOK`, `ZOPERATIONTIMEOUT`, or
    /// `ZCONNECTIONLOSS`).
    pub(crate) outcome: Option<i32>,
    /// Indicates whether unconditional client destruction has finalized.
    pub(crate) destroyed: bool,
    /// Indicates whether `queue_close_op` was rejected due to an unestablished or absent transport.
    op_refused: bool,
    /// Transport generation epoch at the time `CLOSE_OP` was enqueued, used to invalidate flushes
    /// if the socket dropped.
    pub(crate) op_epoch: u64,
    /// Deferred close operation waiting for an active connection teardown sequence to conclude.
    pub(crate) deferred: Option<CloseKind>,
}

impl<T: Tokens> Connection<T> {
    /// Initiates graceful termination of the client session.
    ///
    /// Under `CloseMode::LegacyFailPending`, asserts closing status, immediately fails all pending
    /// requests and authentication tokens with `ZCLOSING`, and emits an auth lockpoint.
    /// Under `CloseMode::Drain`, sets the closing flag and calculates the drain deadline
    /// (`now + close_timeout_ms`), allowing pending in-flight requests to complete normally.
    ///
    /// If an active teardown pipeline is currently suspended at stage T3, the close operation is
    /// deferred until the teardown sequence concludes, preserving chronological completion order.
    pub fn begin_close(&mut self, now: Millis) {
        match self.compat.close_mode {
            CloseMode::Drain => {
                self.close.closing = true;
                if self.close.deadline.is_none() {
                    let ms = u64::from(self.cfg.close_timeout_ms);
                    self.close.deadline = Some(now.saturating_add_ms(ms));
                }
            }
            CloseMode::LegacyFailPending => {
                if self.close.closing {
                    return;
                }
                self.close.closing = true;
                if self.teardown_t3_in_flight() {
                    self.close.deferred = Some(CloseKind::Begin);
                    return;
                }
                self.close_begin_now(now);
            }
        }
    }

    /// Executes the immediate failure sequence for pending work under legacy close rules.
    fn close_begin_now(&mut self, now: Millis) {
        let (mut entries, auth) = self.close_take_snapshot();
        entries.extend(self.requests.take_fifo());
        self.close_run(CloseKind::Begin, entries, auth, now);
    }

    /// Serializes and enqueues an explicit `CLOSE_OP` request frame on the active transport.
    ///
    /// Returns true if successfully queued on an established connection; returns false if the
    /// transport is absent or not yet connected.
    pub fn queue_close_op(&mut self) -> bool {
        if !self.core.state.is_connected() || !self.core.has_transport {
            self.close.op_refused = true;
            return false;
        }
        let xid = self.requests.next_xid();
        let bytes = self.teardown_peer_request_frame(xid, zk_jute::opcode::CLOSE, &[]);
        self.requests
            .push_tail(OutFrame::plain(FrameKind::Close, bytes));
        self.close.close_xid = Some(xid);
        self.close.flush_established = self.core.phase == Phase::Established;
        self.close.op_epoch = self.core.epoch;
        self.close.op_refused = false;
        true
    }

    /// Evaluates and advances the progress of a graceful drain shutdown.
    ///
    /// Returns `CloseProgress::Complete` once the server responds with a close confirmation,
    /// when the configured timeout expires, or if the underlying transport drops.
    pub fn close_step(&mut self, now: Millis) -> CloseProgress {
        if !self.close.closing {
            return CloseProgress::Pending;
        }
        if self.compat.close_mode == CloseMode::LegacyFailPending {
            return CloseProgress::Complete;
        }
        let transport_ended = !self.core.has_transport
            || (self.close.close_xid.is_some() && self.core.epoch != self.close.op_epoch);
        let past_deadline = self.close.deadline.is_some_and(|d| now >= d);
        if self.close.complete || self.close.op_refused || transport_ended || past_deadline {
            self.close.complete = true;
            if self.close.outcome.is_none() {
                self.close.outcome = Some(if self.close.op_refused {
                    rc::ZOK
                } else if transport_ended {
                    rc::ZCONNECTIONLOSS
                } else {
                    rc::ZOPERATIONTIMEOUT
                });
            }
            CloseProgress::Complete
        } else {
            CloseProgress::Pending
        }
    }

    /// Immediately drops transport connections and fails pending requests without transmitting a
    /// wire `CLOSE_OP`.
    ///
    /// Clears active watcher registrations with `TerminateReason::Closed` and closes the transport
    /// socket.
    pub fn abandon(&mut self, now: Millis) {
        if self.close.destroyed {
            return;
        }
        self.close.closing = true;
        if self.teardown_t3_in_flight() {
            // Abandon supersedes and subsumes deferred close actions.
            self.close.deferred = Some(CloseKind::Abandon);
            return;
        }
        self.close_abandon_now(now);
    }

    /// Executes immediate transport disconnection and pending work failure for an abandoned
    /// connection.
    fn close_abandon_now(&mut self, now: Millis) {
        if self.core.has_transport {
            self.teardown_t1();
        }
        let (mut entries, auth) = self.close_take_snapshot();
        let (_tx, fifo) = self.requests.take_all();
        entries.extend(fifo);
        self.close_run(CloseKind::Abandon, entries, auth, now);
    }

    /// Irrevocably destroys the connection handle, failing all pending work and releasing all
    /// resources.
    ///
    /// Reclaims all suspended jobs and queued tokens, completing them with `ZCLOSING`. Marks
    /// outbound queues destroyed, terminates active watches with `TerminateReason::Closed`, queues
    /// transport closure, and triggers SASL disposal. Subsequent operations on this instance are
    /// ignored.
    pub fn destroy(&mut self, now: Millis) {
        if self.close.destroyed {
            return;
        }
        self.close.closing = true;
        self.close.destroyed = true;
        // Unconditional destruction clears any deferred close actions.
        self.close.deferred = None;
        let mut entries = VecDeque::new();
        let mut auth = Vec::new();
        for work in self.out.take_all_work() {
            match work {
                Work::Teardown(job) => {
                    entries.extend(job.entries);
                    auth.extend(job.auth);
                }
                Work::Close(job) => {
                    entries.extend(job.entries);
                    auth.extend(job.auth);
                }
                Work::Hold | Work::AuthReply(_) | Work::Finalize(_) => {}
            }
        }
        let (snap_entries, snap_auth) = self.close_take_snapshot();
        entries.extend(snap_entries);
        auth.extend(snap_auth);
        let (_tx, fifo) = self.requests.take_all();
        entries.extend(fifo);
        self.out.mark_destroyed();
        self.close_run(CloseKind::Destroy, entries, auth, now);
    }

    /// Initializes and drives a close job starting from the pending FIFO failure stage.
    fn close_run(
        &mut self,
        kind: CloseKind,
        entries: VecDeque<Pending<T>>,
        auth: Vec<T::Auth>,
        now: Millis,
    ) {
        let job = CloseJob {
            kind,
            stage: CloseStage::FailFifo,
            entries,
            auth,
        };
        self.continue_close(job, now);
    }

    /// Indicates whether outbound request frames may continue transmitting during the close flush
    /// phase.
    pub(crate) fn close_flush_open(&self) -> bool {
        self.close.flush_established && self.core.epoch == self.close.op_epoch
    }

    /// Indicates whether a close or destruction procedure has been initiated on this connection.
    #[must_use]
    pub fn is_closing(&self) -> bool {
        self.close.closing
    }

    /// Retrieves the terminal return code for a completed drain close sequence.
    ///
    /// Yields `None` while drain is in progress or when running under legacy compatibility mode.
    #[must_use]
    pub fn close_outcome(&self) -> Option<i32> {
        if self.compat.close_mode == CloseMode::LegacyFailPending {
            return None;
        }
        self.close.outcome
    }

    /// Advances execution of a suspended close pipeline across sequential barrier stages.
    pub(crate) fn continue_close(&mut self, mut job: CloseJob<T>, _now: Millis) {
        let parks = job.kind != CloseKind::Destroy;
        loop {
            match job.stage {
                CloseStage::FailFifo => {
                    if job.entries.is_empty() {
                        job.stage = CloseStage::Lockpoint;
                        continue;
                    }
                    let mut entries = core::mem::take(&mut job.entries);
                    let stopped = self.teardown_peer_fail_pending_run(&mut entries, rc::ZCLOSING);
                    job.entries = entries;
                    if stopped && parks {
                        self.out.park(Work::Close(job));
                        return;
                    }
                }
                CloseStage::Lockpoint => {
                    job.stage = CloseStage::Auth;
                    if self.compat.emits_lockpoints() {
                        self.out.push(Output::AuthLockpoint);
                        if parks {
                            self.out.park(Work::Close(job));
                            return;
                        }
                    }
                }
                CloseStage::Auth => {
                    job.stage = CloseStage::Finish;
                    let mut tokens = core::mem::take(&mut job.auth);
                    tokens.extend(self.auth.take_tokens());
                    if !tokens.is_empty() {
                        self.out.push(Output::Auth {
                            tokens,
                            rc: rc::ZCLOSING,
                            barrier: false,
                        });
                    }
                }
                CloseStage::Finish => {
                    self.close_finish(job.kind);
                    return;
                }
            }
        }
    }

    /// Dispatches a previously deferred close or abandon sequence once active teardown processing
    /// completes.
    pub(crate) fn close_run_deferred(&mut self, now: Millis) {
        match self.close.deferred.take() {
            Some(CloseKind::Begin) => self.close_begin_now(now),
            Some(CloseKind::Abandon) => self.close_abandon_now(now),
            Some(CloseKind::Destroy) | None => {}
        }
    }

    /// Processes receipt of the server's `CLOSE_OP` response frame, completing the drain sequence
    /// with `ZOK`.
    pub(crate) fn on_close_reply(&mut self, _now: Millis) -> Flow {
        self.close.complete = true;
        if self.close.outcome.is_none() {
            self.close.outcome = Some(rc::ZOK);
        }
        Flow::Done(rc::ZOK)
    }

    /// Extracts pending FIFO entries and authentication tokens from an unstarted forced-close
    /// snapshot.
    fn close_take_snapshot(&mut self) -> (VecDeque<Pending<T>>, Vec<T::Auth>) {
        match self.teardown_take_forced() {
            Some(ForcedSnapshot { fifo, auth, .. }) => (fifo, auth),
            None => (VecDeque::new(), Vec::new()),
        }
    }

    /// Finalizes the terminal stage of a close job, emitting a drain point and completing
    /// subsystem cleanup.
    fn close_finish(&mut self, kind: CloseKind) {
        self.out.push(Output::DrainPoint);
        match kind {
            CloseKind::Begin => {}
            CloseKind::Abandon => {
                self.teardown_peer_clear_watches(TerminateReason::Closed);
            }
            CloseKind::Destroy => {
                self.teardown_peer_clear_watches(TerminateReason::Closed);
                if self.core.has_transport {
                    self.queue_close_transport();
                    self.core.phase = Phase::Idle;
                    self.lose_transport();
                }
                self.teardown_peer_sasl_on_destroy();
            }
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
    use crate::action::{Action, TransportFailure};
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::request::Route;
    use crate::state::{Cause, State};
    use crate::teardown::PeerCall;
    use crate::teardown::test_support::*;

    const CL: i32 = rc::ZCLOSING;

    fn loaded(compat: Compat, mode: DriverMode) -> Connection<Tk> {
        let mut cfg = Config::legacy(30_000);
        cfg.close_timeout_ms = 1_000;
        let mut c = conn_with(cfg, compat, mode);
        establish(&mut c, Millis(10));
        c.requests.push_pending(pend(1, Route::Queued, 11));
        c.requests.push_pending(pend(2, Route::Direct, 12));
        c.requests.push_pending(pend(3, Route::Queued, 13));
        add_auth(&mut c, 21);
        c
    }

    fn queued_close_transport(c: &Connection<Tk>) -> bool {
        c.interest
            .queued
            .iter()
            .any(|a| matches!(a, Action::CloseTransport))
    }

    #[test]
    fn begin_close_legacy_fails_pending_work() {
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        let tx_len = c.requests.tx.len();
        c.begin_close(Millis(20));
        assert!(c.is_closing());
        assert!(c.requests.fifo.is_empty());
        assert_eq!(c.requests.tx.len(), tx_len, "tx stays");
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Done(11, CL, false), Ev::Done(12, CL, true)]);
        c.resume(stop.unwrap(), Millis(20));
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Done(13, CL, false), Ev::Lockpoint]);
        c.resume(stop.unwrap(), Millis(20));
        let evs = drain(&mut c, Millis(20));
        assert_eq!(evs, vec![Ev::Auth(vec![21], CL, false), Ev::Drain]);
        assert_eq!(c.state(), State::Connected, "no session event");
        assert!(c.has_transport());

        // A second invocation is idempotent, and legacy close requires no drain progression.
        c.begin_close(Millis(21));
        assert!(drain(&mut c, Millis(21)).is_empty());
        assert_eq!(c.close_step(Millis(21)), CloseProgress::Complete);
        assert_eq!(c.close_outcome(), None, "the drop-in driver has the rc");

        // An explicit CLOSE_OP frame is still queued following legacy close.
        assert!(c.queue_close_op());
        assert_eq!(c.requests.tx.back().unwrap().kind, FrameKind::Close);
    }

    #[test]
    fn begin_close_drain_keeps_pending_work() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        assert_eq!(c.close_step(Millis(20)), CloseProgress::Pending);
        c.begin_close(Millis(20));
        assert!(c.is_closing());
        assert_eq!(c.close.deadline, Some(Millis(1_020)));
        assert_eq!(c.requests.fifo.len(), 3);
        assert!(drain(&mut c, Millis(20)).is_empty());
        // A subsequent begin_close invocation retains the initial deadline.
        c.begin_close(Millis(500));
        assert_eq!(c.close.deadline, Some(Millis(1_020)));
        assert_eq!(c.close_step(Millis(500)), CloseProgress::Pending);
    }

    #[test]
    fn close_step_runs_to_completion_on_the_reply() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        c.begin_close(Millis(20));
        let _ = calls(&mut c);
        assert!(c.queue_close_op());
        let xid = c.close.close_xid.unwrap();
        assert_eq!(
            calls(&mut c),
            vec![PeerCall::RequestFrame(xid, zk_jute::opcode::CLOSE)]
        );
        assert!(c.close.flush_established);
        let frame = c.requests.tx.back().unwrap();
        assert_eq!(frame.kind, FrameKind::Close);
        assert_eq!(frame.bytes.len(), 12);
        assert_eq!(c.requests.fifo.len(), 3, "no completion for CLOSE_OP");
        assert_eq!(c.close_step(Millis(30)), CloseProgress::Pending);
        assert_eq!(c.close_outcome(), None);
        assert_eq!(c.on_close_reply(Millis(40)), Flow::Done(rc::ZOK));
        assert_eq!(c.close_step(Millis(40)), CloseProgress::Complete);
        assert_eq!(c.close_step(Millis(41)), CloseProgress::Complete);
        assert_eq!(c.close_outcome(), Some(rc::ZOK));

        // The driver subsequently tears down and destroys the connection handle.
        c.destroy(Millis(41));
        let evs = no_logs(&drain(&mut c, Millis(41)));
        assert_eq!(
            evs,
            vec![
                Ev::Done(11, CL, false),
                Ev::Done(12, CL, true),
                Ev::Done(13, CL, false),
                Ev::Auth(vec![21], CL, false),
                Ev::Drain,
            ]
        );
        assert!(queued_close_transport(&c));
        assert!(!c.has_transport());
    }

    #[test]
    fn close_step_completes_at_the_deadline() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        c.begin_close(Millis(20));
        assert!(c.queue_close_op());
        assert_eq!(c.close_step(Millis(1_019)), CloseProgress::Pending);
        assert_eq!(c.close_step(Millis(1_020)), CloseProgress::Complete);
        assert_eq!(c.close_outcome(), Some(rc::ZOPERATIONTIMEOUT));
        // A late-arriving server reply cannot override an already expired close outcome.
        let _ = c.on_close_reply(Millis(1_030));
        assert_eq!(c.close_outcome(), Some(rc::ZOPERATIONTIMEOUT));
    }

    #[test]
    fn close_step_completes_when_close_op_is_refused() {
        let mut c = conn(Compat::NATIVE, DriverMode::NATIVE);
        c.begin_close(Millis(1));
        assert!(!c.queue_close_op());
        assert!(c.requests.tx.is_empty());
        assert_eq!(c.close_step(Millis(1)), CloseProgress::Complete);
        assert_eq!(c.close_outcome(), Some(rc::ZOK));
    }

    #[test]
    fn close_step_completes_after_a_teardown() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        c.begin_close(Millis(20));
        assert!(c.queue_close_op());
        let _ = c.handle_transport_failed(TransportFailure::Eof, Millis(30));
        assert_eq!(c.last_teardown_cause(), Some(Cause::Eof));
        assert_eq!(c.close_step(Millis(30)), CloseProgress::Complete);
        assert_eq!(c.close_outcome(), Some(rc::ZCONNECTIONLOSS));
        // A connection teardown during active drain fails pending FIFO requests with its own error
        // code.
        let evs = no_logs(&drain(&mut c, Millis(30)));
        assert!(evs.contains(&Ev::Done(11, rc::ZCONNECTIONLOSS, false)));
        assert!(c.is_closing());
    }

    #[test]
    fn destroy_with_a_suspended_close_job() {
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        c.begin_close(Millis(20));
        let (evs, stop) = until_stop(&mut c);
        assert_eq!(evs, vec![Ev::Done(11, CL, false), Ev::Done(12, CL, true)]);
        c.requests.push_pending(pend(4, Route::Queued, 14));
        let _ = calls(&mut c);
        c.destroy(Millis(21));
        assert!(c.is_closing());
        assert!(c.out.is_destroyed());
        assert!(c.requests.tx.is_empty());
        // Shutdown job entries are failed prior to draining the FIFO queue.
        let evs = drain(&mut c, Millis(21));
        assert_eq!(
            no_logs(&evs),
            vec![
                Ev::Done(13, CL, false),
                Ev::Done(14, CL, false),
                Ev::Lockpoint,
                Ev::Auth(vec![21], CL, false),
                Ev::Drain,
            ]
        );
        assert!(!c.is_suspended());
        assert_eq!(
            calls(&mut c),
            vec![
                PeerCall::FailPendingRun(CL),
                PeerCall::ClearWatches(TerminateReason::Closed),
                PeerCall::SaslOnDestroy,
            ]
        );
        assert!(queued_close_transport(&c));
        assert!(!c.has_transport());
        // The stale continuation token fails to resume any further processing.
        c.resume(stop.unwrap(), Millis(22));
        assert!(drain(&mut c, Millis(22)).is_empty());
        // Redundant destroy or abandon invocations perform no additional work.
        c.destroy(Millis(22));
        c.abandon(Millis(22));
        assert!(drain(&mut c, Millis(22)).is_empty());
        let left = c.into_leftovers();
        assert!(left.completions.is_empty() && left.auth.is_empty());
    }

    #[test]
    fn destroy_with_a_suspended_teardown_and_snapshot() {
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        c.forced_close_trigger(Millis(5));
        assert!(c.run_pending_teardown(Millis(6)));
        let (_, stop) = until_stop(&mut c);
        assert!(stop.is_some());
        c.requests.push_pending(pend(4, Route::Queued, 14));
        c.destroy(Millis(7));
        let evs = no_logs(&drain(&mut c, Millis(7)));
        assert_eq!(
            evs,
            vec![
                Ev::Done(11, CL, false),
                Ev::Done(12, CL, true),
                Ev::Done(13, CL, false),
                Ev::Done(14, CL, false),
                Ev::Lockpoint,
                Ev::Auth(vec![21], CL, false),
                Ev::Drain,
            ]
        );
        assert!(!c.has_transport());
        assert!(c.teardown.forced.is_none());
        assert!(c.into_leftovers().completions.is_empty());
    }

    #[test]
    fn abandon_closes_the_transport_and_fails_work() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        let _ = calls(&mut c);
        c.abandon(Millis(20));
        assert!(c.is_closing());
        assert!(!c.has_transport());
        assert!(queued_close_transport(&c));
        assert!(c.requests.tx.is_empty());
        let evs = no_logs(&drain(&mut c, Millis(20)));
        assert_eq!(
            evs,
            vec![
                Ev::Done(11, CL, false),
                Ev::Done(12, CL, true),
                Ev::Stop,
                Ev::Done(13, CL, false),
                Ev::Auth(vec![21], CL, false),
                Ev::Drain,
            ]
        );
        let calls = calls(&mut c);
        assert_eq!(
            calls.last(),
            Some(&PeerCall::ClearWatches(TerminateReason::Closed))
        );
        assert!(!calls.contains(&PeerCall::HostsOnHandleError));
        assert_eq!(c.close_step(Millis(21)), CloseProgress::Complete);
    }

    #[test]
    fn begin_close_stops_the_pending_forced_stages() {
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        c.forced_close_trigger(Millis(5));
        c.begin_close(Millis(6));
        assert!(!c.run_pending_teardown(Millis(6)));
        let evs = no_logs(&drain(&mut c, Millis(6)));
        assert_eq!(
            evs,
            vec![
                Ev::Done(11, CL, false),
                Ev::Done(12, CL, true),
                Ev::Stop,
                Ev::Done(13, CL, false),
                Ev::Lockpoint,
                Ev::Stop,
                Ev::Auth(vec![21], CL, false),
                Ev::Drain,
            ]
        );
        assert!(c.teardown.forced.is_none());
        assert!(!c.run_pending_teardown(Millis(7)));
    }

    #[test]
    fn drain_close_flush_ends_with_its_transport() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        c.begin_close(Millis(20));
        assert!(c.queue_close_op());
        assert!(c.close_flush_open());
        let _ = c.handle_transport_failed(TransportFailure::Eof, Millis(21));
        let _ = drain(&mut c, Millis(21));
        assert!(!c.close_flush_open(), "the flush belongs to the old epoch");
        // A closing connection will not attempt to establish new transport connections.
        while let Some(a) = c.poll_action(Millis(5_000)) {
            assert!(!matches!(a, Action::Connect { .. }), "{a:?}");
        }
        // Extraneous transport connect attempts initiated by the driver are rejected.
        c.interest.queued.clear();
        c.handle_connect_started(crate::action::ConnectStart::InProgress, Millis(5_000));
        assert!(!c.has_transport());
        assert!(queued_close_transport(&c));
    }

    #[test]
    fn connect_started_with_a_live_transport_is_refused() {
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        let epoch = c.core.epoch;
        let state = c.state();
        c.interest.queued.clear();
        c.handle_connect_started(crate::action::ConnectStart::InProgress, Millis(20));
        assert!(queued_close_transport(&c));
        assert_eq!(c.core.epoch, epoch);
        assert_eq!(c.state(), state);
        assert!(c.has_transport());
    }

    #[test]
    fn connect_started_after_destroy_is_refused() {
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        c.destroy(Millis(20));
        let _ = drain(&mut c, Millis(20));
        c.interest.queued.clear();
        c.handle_connect_started(crate::action::ConnectStart::InProgress, Millis(21));
        assert!(!c.has_transport());
        assert!(queued_close_transport(&c));
    }

    #[test]
    fn transport_failures_after_destroy_do_nothing() {
        let failures = [
            TransportFailure::SocketCreate { errno: 24 },
            TransportFailure::TcpRefused { errno: 111 },
            TransportFailure::Io { errno: 104 },
            TransportFailure::Eof,
            TransportFailure::TlsHandshake { errno: 0 },
        ];
        for f in failures {
            let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
            c.destroy(Millis(20));
            let _ = drain(&mut c, Millis(20));
            let _ = calls(&mut c);
            let cause = c.last_teardown_cause();
            let _ = c.handle_transport_failed(f.clone(), Millis(21));
            let evs = no_logs(&drain(&mut c, Millis(21)));
            assert!(evs.is_empty(), "{f:?}: {evs:?}");
            assert!(!c.is_suspended(), "{f:?}");
            assert!(calls(&mut c).is_empty(), "{f:?}");
            assert_eq!(c.last_teardown_cause(), cause, "{f:?}");
            assert!(!c.run_pending_teardown(Millis(22)), "{f:?}");
        }
    }

    fn add_auth_now(c: &mut Connection<Tk>, token: u32) -> Result<(), crate::AddAuthError<Tk>> {
        c.add_auth(
            zk_jute::JString::from("digest"),
            crate::SecretBytes::from_slice(b"pw"),
            Some(token),
            Millis(30),
        )
    }

    #[test]
    fn add_auth_after_destroy_gives_the_token_back() {
        for (compat, mode) in [
            (Compat::LEGACY_C, DriverMode::ST),
            (Compat::NATIVE, DriverMode::NATIVE),
        ] {
            let mut c = loaded(compat, mode);
            c.destroy(Millis(20));
            let _ = drain(&mut c, Millis(20));
            let e = add_auth_now(&mut c, 31).unwrap_err();
            assert_eq!(e.code, rc::ZINVALIDSTATE);
            assert_eq!(e.done, Some(31));
            assert!(c.auth.take_tokens().is_empty());
            assert!(c.into_leftovers().auth.is_empty());
        }
    }

    #[test]
    fn add_auth_while_a_drain_close_runs_is_refused() {
        let mut c = loaded(Compat::NATIVE, DriverMode::NATIVE);
        c.begin_close(Millis(20));
        let e = add_auth_now(&mut c, 31).unwrap_err();
        assert_eq!(e.code, CL);
        assert_eq!(e.done, Some(31));
        // Under legacy C compatibility, authentication submissions during close are still
        // accepted.
        let mut c = loaded(Compat::LEGACY_C, DriverMode::ST);
        c.begin_close(Millis(20));
        let _ = drain(&mut c, Millis(20));
        assert!(add_auth_now(&mut c, 31).is_ok());
    }
}
