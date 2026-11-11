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

//! SASL authentication negotiation sequencing and handshake coordination.
//!
//! Owns [`SaslState`] and governs asynchronous mechanism interactions. Rather than directly
//! binding to concrete GSSAPI/Cyrus SASL implementations, the protocol engine requests external
//! mechanism operations via `Action::Sasl`, transmits resulting authentication tokens across
//! protocol wire frames, and routes inbound server challenge frames back to mechanism steps.

use std::collections::VecDeque;

use zk_jute::proto::ReplyHeader;
use zk_jute::{DecodeError, JString, Reader, Record, opcode, rc};

use crate::action::{Action, SaslAddr, SaslCall, SaslCallKind, SaslError, SaslStep};
use crate::auth::secret_frame;
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::log::{LogArg, LogSite};
use crate::recv::Flow;
use crate::requests::{FrameKind, OutFrame};
use crate::secret::SecretBytes;
use crate::state::{Cause, Phase, SaslNegotiation, State};
use crate::time::Millis;

/// Category of outstanding asynchronous mechanism invocation awaiting external completion.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum WaitKind {
    /// Initial mechanism setup via `Start`.
    Start,
    /// Intermediate token exchange via `Step`.
    Step,
}

/// Tracking descriptor for an in-flight mechanism call awaiting driver completion.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Waiting {
    /// Category of the outstanding invocation.
    kind: WaitKind,
    /// Generation identifier associated with the invocation.
    generation: u32,
}

/// Internal state machine tracking SASL negotiation generations, tokens, and mechanism lifecycles.
pub(crate) struct SaslState {
    /// Monotonically increasing negotiation generation counter, wrapping from `u32::MAX` to 1.
    pub(crate) generation: u32,
    /// Generation identifier of the active mechanism session, preserved until explicit `Dispose`.
    pub(crate) mech_gen: Option<u32>,
    /// Indicates whether an asynchronous mechanism `Start` or `Step` invocation is currently
    /// outstanding.
    pub(crate) outstanding: bool,
    /// Ordered queue of mechanism actions awaiting dispatch to the driver.
    pub(crate) calls: VecDeque<SaslCall>,
    /// Selected SASL mechanism name (e.g., "GSSAPI", "DIGEST-MD5", or "PLAIN") negotiated for
    /// this session.
    pub(crate) mechanism: Option<String>,
    /// Active in-flight mechanism call being waited upon.
    waiting: Option<Waiting>,
    /// Byte length of the server challenge frame that queued the pending mechanism step, held
    /// for logging.
    processed_len: Option<i64>,
}

impl SaslState {
    /// Instantiates an initial SASL state without active mechanism sessions.
    pub(crate) fn new() -> Self {
        Self {
            generation: 0,
            mech_gen: None,
            outstanding: false,
            calls: VecDeque::new(),
            mechanism: None,
            waiting: None,
            processed_len: None,
        }
    }

    /// Advances the generation counter, wrapping from `u32::MAX` to 1, and returns the updated
    /// value.
    pub(crate) fn advance_gen(&mut self) -> u32 {
        self.generation = self.generation.checked_add(1).unwrap_or(1);
        self.generation
    }

    /// Determines whether a supplied generation identifier matches the active negotiation cycle.
    pub(crate) fn is_live(&self, generation: u32) -> bool {
        generation != 0 && generation == self.generation
    }

    /// Clears the outstanding wait condition and extracts the pending call descriptor.
    fn end_wait(&mut self) -> Option<Waiting> {
        self.outstanding = false;
        self.waiting.take()
    }

    /// Clears the outstanding wait condition if the supplied generation matches the in-flight call.
    fn end_wait_for(&mut self, generation: u32) -> Option<Waiting> {
        if self.waiting.is_some_and(|w| w.generation == generation) {
            self.end_wait()
        } else {
            None
        }
    }

    /// Concludes the active negotiation cycle, invalidating outstanding generations and draining
    /// pending steps.
    fn end_negotiation(&mut self) {
        self.advance_gen();
        self.calls
            .retain(|c| matches!(c.kind, SaslCallKind::Dispose));
        self.processed_len = None;
    }
}

/// Decodes the authentication token payload from an inbound `SetSASLResponse` frame.
fn read_token<'a>(r: &mut Reader<'a>) -> Result<Option<&'a [u8]>, DecodeError> {
    let len = r.read_i32()?;
    if len == -1 {
        return Ok(None);
    }
    let n = usize::try_from(len).map_err(|_| DecodeError::NegativeBuffer(len))?;
    r.read_bytes(n).map(Some)
}

/// Formats a byte buffer length safely as a 64-bit integer for structured logging.
fn log_len(n: usize) -> i64 {
    i64::try_from(n).unwrap_or(i64::MAX)
}

impl<T: Tokens> Connection<T> {
    /// Processes completion of an asynchronous mechanism initialization (`Start`) call.
    ///
    /// Validates generation freshness. On error, logs mechanism diagnostics and aborts the session
    /// via `sasl_fail`. On success, records the negotiated mechanism, handles DIGEST-MD5
    /// re-authentication suppression rules, detects GSSAPI mode, and enqueues the initial client
    /// token frame.
    pub fn handle_sasl_started(
        &mut self,
        generation: u32,
        r: Result<(String, SaslStep), SaslError>,
        now: Millis,
    ) -> i32 {
        let waiting = self.sasl.end_wait_for(generation);
        if !self.sasl_result_live(waiting, WaitKind::Start, generation) {
            return rc::ZOK;
        }
        match r {
            Err(e) => {
                self.sasl_log_mech_error(LogSite::SaslStartError, e);
                self.sasl_fail(rc::ZSYSTEMERROR, now)
            }
            Ok((mechanism, step)) => {
                let mut token = step.token;
                // Suppress initial DIGEST-MD5 response token to force fresh server
                // authentication (JDK-6682540).
                if mechanism == "DIGEST-MD5" && token.as_ref().is_some_and(|t| !t.is_empty()) {
                    let name = JString::from(mechanism.as_str());
                    self.emit_log(LogSite::SaslRefuseReauth, || vec![LogArg::Str(name)]);
                    token = None;
                }
                let is_gssapi = mechanism == "GSSAPI";
                if let Some(n) = self.sasl_negotiation() {
                    n.is_gssapi = is_gssapi;
                }
                self.sasl.mechanism = Some(mechanism);
                if !step.done || token.as_ref().is_some_and(|t| !t.is_empty()) {
                    self.sasl_send(token.as_ref());
                }
                rc::ZOK
            }
        }
    }

    /// Processes completion of an intermediate mechanism challenge evaluation (`Step`) call.
    ///
    /// Evaluates step tokens and completion flags. For non-terminal steps or steps yielding tokens,
    /// transmits an outbound SASL protocol frame. When the mechanism reports completion, manages
    /// protocol-specific termination semantics (e.g., awaiting final server confirmation for
    /// non-GSSAPI or handling trailing packets for GSSAPI) before transitioning to session
    /// finalization.
    pub fn handle_sasl_stepped(
        &mut self,
        generation: u32,
        r: Result<SaslStep, SaslError>,
        now: Millis,
    ) -> i32 {
        let waiting = self.sasl.end_wait_for(generation);
        if !self.sasl_result_live(waiting, WaitKind::Step, generation) {
            return rc::ZOK;
        }
        let step = match r {
            Err(e) => {
                self.sasl_log_mech_error(LogSite::SaslStepError, e);
                return self.sasl_fail(rc::ZSYSTEMERROR, now);
            }
            Ok(step) => step,
        };
        let has_token = step.token.as_ref().is_some_and(|t| !t.is_empty());
        if !step.done || has_token {
            self.sasl_send(step.token.as_ref());
        }
        let mut complete = false;
        if step.done {
            if let Some(n) = self.sasl_negotiation() {
                if n.is_gssapi {
                    // Under GSSAPI negotiation, the ensemble transmits one trailing packet prior
                    // to completion.
                    n.gssapi_last_packet = true;
                } else if has_token {
                    // Non-GSSAPI mechanisms with trailing client tokens require server
                    // acknowledgment.
                    n.awaiting_final = true;
                } else {
                    complete = true;
                }
            }
        }
        self.sasl_log_processed(rc::ZOK);
        if !complete {
            return rc::ZOK;
        }
        match self.start_finalize(now) {
            Flow::Done(rc) => rc,
            Flow::Continue | Flow::Stop => rc::ZOK,
        }
    }

    /// Handles failure to resolve local or peer socket address metadata required for SASL
    /// mechanism binding.
    ///
    /// Logs socket address resolution diagnostics, clears mechanism session state, cancels queued
    /// start requests, and triggers an immediate authentication failure teardown.
    pub fn handle_sasl_addr_failed(
        &mut self,
        generation: u32,
        which: SaslAddr,
        now: Millis,
    ) -> i32 {
        let start_waits = self
            .sasl
            .end_wait_for(generation)
            .is_some_and(|w| w.kind == WaitKind::Start);
        let start_queued = self
            .sasl
            .calls
            .iter()
            .any(|c| c.generation == generation && matches!(c.kind, SaslCallKind::Start));
        let live = self.sasl.is_live(generation)
            && matches!(self.core.phase, Phase::Sasl(_))
            && (start_waits || start_queued);
        if !live {
            return rc::ZOK;
        }
        let site = match which {
            SaslAddr::Local => LogSite::SaslGetsockname,
            SaslAddr::Peer => LogSite::SaslGetpeername,
        };
        self.emit_log(site, Vec::new);
        self.sasl.mech_gen = None;
        self.sasl
            .calls
            .retain(|c| !(c.generation == generation && matches!(c.kind, SaslCallKind::Start)));
        self.sasl_fail(rc::ZSYSTEMERROR, now)
    }

    /// Transitions session state to `AuthFailed` and terminates active SASL negotiation without
    /// initiating teardown.
    pub fn mark_sasl_failed(&mut self) {
        self.set_state(State::AuthFailed);
        self.sasl.end_negotiation();
    }

    /// Initiates a new SASL authentication negotiation cycle following handshake connection
    /// response.
    ///
    /// Disposes previous mechanism sessions if lingering, increments negotiation generation,
    /// sets the phase to `Phase::Sasl`, and enqueues an initial `Start` mechanism call.
    pub(crate) fn begin_sasl(&mut self, _now: Millis) {
        self.core.phase = Phase::Sasl(SaslNegotiation::default());
        self.sasl.mechanism = None;
        self.sasl.processed_len = None;
        // Teardown drops stale calls; this retain preserves order if begin_sasl is invoked
        // directly.
        self.sasl
            .calls
            .retain(|c| matches!(c.kind, SaslCallKind::Dispose));
        // Dispose existing mechanism session prior to initiating fresh authentication.
        self.sasl_on_destroy();
        let generation = self.sasl.advance_gen();
        self.sasl.calls.push_back(SaslCall {
            generation,
            kind: SaslCallKind::Start,
        });
    }

    /// Ingests and processes an incoming SASL protocol challenge frame from the ensemble server.
    ///
    /// Decodes header return codes and token buffers. If the server signals an error, aborts
    /// authentication. When awaiting final confirmation, validates successful completion and
    /// triggers session finalization. Otherwise, extracts server challenge data and enqueues a
    /// corresponding `Step` mechanism invocation.
    pub(crate) fn on_sasl_reply(&mut self, body: &[u8], now: Millis) -> Flow {
        // C logging includes the 4-byte frame length prefix in the reported buffer size.
        let len = log_len(body.len()).saturating_add(4);
        let mut r = Reader::new(body);
        let hdr = match ReplyHeader::decode(&mut r) {
            Ok(h) => h,
            Err(e) => return self.sasl_reply_failed(len, e.c_rc(), now),
        };
        if hdr.err < 0 {
            return self.sasl_reply_failed(len, hdr.err, now);
        }
        let token = match read_token(&mut r) {
            Ok(t) => t,
            Err(e) => return self.sasl_reply_failed(len, e.c_rc(), now),
        };
        let neg = match self.core.phase {
            Phase::Sasl(n) => n,
            _ => SaslNegotiation::default(),
        };
        if neg.awaiting_final {
            // Server acknowledged final token exchange; negotiation complete.
            if let Some(n) = self.sasl_negotiation() {
                n.awaiting_final = false;
            }
            self.sasl_log_processed_now(len, rc::ZOK);
            return self.start_finalize(now);
        }
        let server_len = token.map_or(-1, |t| log_len(t.len()));
        self.emit_log(LogSite::SaslServerData, || vec![LogArg::I64(server_len)]);
        if neg.is_gssapi && neg.gssapi_last_packet {
            // Trailing GSSAPI packet concludes negotiation without invoking additional mechanism
            // steps.
            if let Some(n) = self.sasl_negotiation() {
                n.gssapi_last_packet = false;
            }
            self.sasl_log_processed_now(len, rc::ZOK);
            return self.start_finalize(now);
        }
        let generation = self.sasl.generation;
        self.sasl.calls.push_back(SaslCall {
            generation,
            kind: SaslCallKind::Step(token.map(SecretBytes::from_slice)),
        });
        self.sasl.processed_len = Some(len);
        Flow::Stop
    }

    /// Triggers terminal SASL authentication failure, logging diagnostics and initiating
    /// connection teardown.
    pub(crate) fn sasl_fail(&mut self, rc: i32, now: Millis) -> i32 {
        self.emit_log(LogSite::SaslFailed, || vec![LogArg::I64(i64::from(rc))]);
        self.sasl_log_processed(rc);
        self.sasl_abort(now);
        rc
    }

    /// Retrieves the next scheduled SASL driver action, enforcing serial mechanism invocation
    /// ordering.
    pub(crate) fn poll_sasl_action(&mut self) -> Option<Action> {
        // Enforce strict serialization: subsequent actions wait until in-flight results return.
        if self.sasl.outstanding {
            return None;
        }
        let call = self.sasl.calls.pop_front()?;
        let kind = match call.kind {
            SaslCallKind::Start => Some(WaitKind::Start),
            SaslCallKind::Step(_) => Some(WaitKind::Step),
            SaslCallKind::Dispose => None,
        };
        if let Some(kind) = kind {
            self.sasl.outstanding = true;
            self.sasl.waiting = Some(Waiting {
                kind,
                generation: call.generation,
            });
        }
        if kind == Some(WaitKind::Start) {
            self.sasl.mech_gen = Some(call.generation);
        }
        Some(Action::Sasl(call))
    }

    /// Convenience accessor for drivers to poll pending SASL mechanism invocations following
    /// read events.
    pub fn poll_sasl_call(&mut self) -> Option<SaslCall> {
        if self.out.is_suspended() || self.sasl.calls.is_empty() {
            return None;
        }
        match self.poll_sasl_action() {
            Some(Action::Sasl(call)) => Some(call),
            _ => None,
        }
    }

    /// Indicates whether the network event loop must suspend ingress frame processing while
    /// awaiting mechanism completion.
    pub(crate) fn sasl_awaiting_mechanism(&self) -> bool {
        self.sasl.outstanding || !self.sasl.calls.is_empty()
    }

    /// Invalidates in-flight negotiation generations and cleans up pending mechanism steps
    /// during connection teardown.
    pub(crate) fn sasl_on_teardown(&mut self) {
        // Active in-flight waits are abandoned; late-arriving results are treated as stale.
        self.sasl.end_negotiation();
    }

    /// Enqueues final `Dispose` actions to release underlying mechanism resources upon
    /// connection destruction.
    pub(crate) fn sasl_on_destroy(&mut self) {
        if let Some(generation) = self.sasl.mech_gen.take() {
            self.sasl.calls.push_back(SaslCall {
                generation,
                kind: SaslCallKind::Dispose,
            });
        }
    }

    /// Verifies that an incoming mechanism result matches both the active negotiation epoch and
    /// the awaited call.
    fn sasl_result_live(&self, waiting: Option<Waiting>, kind: WaitKind, generation: u32) -> bool {
        self.sasl.is_live(generation)
            && matches!(self.core.phase, Phase::Sasl(_))
            && waiting.is_some_and(|w| w.kind == kind && w.generation == generation)
    }

    /// Returns mutable reference to the active SASL negotiation descriptor when in `Phase::Sasl`.
    fn sasl_negotiation(&mut self) -> Option<&mut SaslNegotiation> {
        match &mut self.core.phase {
            Phase::Sasl(n) => Some(n),
            _ => None,
        }
    }

    /// Serializes and dispatches an outbound SASL protocol request frame (opcode 102) with
    /// priority ordering.
    fn sasl_send(&mut self, token: Option<&SecretBytes>) {
        let bytes = token.map_or(&[][..], SecretBytes::expose);
        let xid = self.requests.next_xid();
        let frame = secret_frame(xid, opcode::SASL, &[], bytes);
        self.requests
            .push_priority(OutFrame::secret(FrameKind::Sasl, frame));
        let len = log_len(bytes.len());
        self.emit_log(LogSite::SaslQueued, || {
            vec![LogArg::I64(len), LogArg::I64(i64::from(rc::ZOK))]
        });
    }

    /// Emits structured diagnostic log records detailing mechanism error strings and details.
    fn sasl_log_mech_error(&mut self, site: LogSite, e: SaslError) {
        if e.errstring.is_empty() && e.errdetail.is_empty() {
            return;
        }
        self.emit_log(site, || {
            vec![
                LogArg::Str(JString::Bytes(e.errstring)),
                LogArg::Str(JString::Bytes(e.errdetail)),
            ]
        });
    }

    /// Emits deferred server response processing logs once associated mechanism steps conclude.
    fn sasl_log_processed(&mut self, rc: i32) {
        if let Some(len) = self.sasl.processed_len.take() {
            self.sasl_log_processed_now(len, rc);
        }
    }

    /// Emits structured log records documenting completed SASL response frame ingestion.
    fn sasl_log_processed_now(&mut self, len: i64, rc: i32) {
        self.emit_log(LogSite::SaslProcessed, || {
            vec![LogArg::I64(len), LogArg::I64(i64::from(rc))]
        });
    }

    /// Handles protocol error frames received during SASL negotiation, logging diagnostics and
    /// aborting the session.
    fn sasl_reply_failed(&mut self, len: i64, rc: i32, now: Millis) -> Flow {
        self.sasl_log_processed_now(len, rc);
        self.sasl_abort(now);
        Flow::Done(rc)
    }

    /// Sets state to `AuthFailed` and triggers connection teardown with `Cause::SaslTeardown`.
    fn sasl_abort(&mut self, now: Millis) {
        self.set_state(State::AuthFailed);
        let _ = self.teardown(Cause::SaslTeardown, 0, now);
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::*;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::hooks::{AllLevels, NoDefaultWatcher, Rng};
    use crate::output::Output;
    use zk_jute::proto::{GetSASLRequest, RequestHeader};

    struct Tk;
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

    fn conn() -> Connection<Tk> {
        let mut cfg = Config::legacy(30_000);
        cfg.sasl = true;
        let mut c = Connection::new(
            cfg,
            Compat::LEGACY_C,
            DriverMode::ST,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        );
        c.set_state(State::Associating);
        c
    }

    fn logs(c: &mut Connection<Tk>) -> Vec<(LogSite, Vec<LogArg>)> {
        let mut v = Vec::new();
        while let Some(o) = c.poll_output() {
            if let Output::Log(r) = o {
                v.push((r.site, r.args));
            }
        }
        v
    }

    /// Inspects the next pending SASL action dispatched by the connection, returning its
    /// generation identifier, operational variant ("start", "step", or "dispose"), and payload
    /// token.
    fn next_call(c: &mut Connection<Tk>) -> Option<(u32, &'static str, Option<Vec<u8>>)> {
        match c.poll_sasl_action()? {
            Action::Sasl(SaslCall { generation, kind }) => Some(match kind {
                SaslCallKind::Start => (generation, "start", None),
                SaslCallKind::Step(t) => (generation, "step", t.map(|t| t.expose().to_vec())),
                SaslCallKind::Dispose => (generation, "dispose", None),
            }),
            _ => panic!("not a SASL action"),
        }
    }

    /// Decodes outbound SASL request frames from the transmission queue in FIFO order,
    /// extracting their raw inner authentication token byte vectors.
    fn tx_tokens(c: &Connection<Tk>) -> Vec<Vec<u8>> {
        c.requests
            .tx
            .iter()
            .map(|f| {
                assert_eq!(f.kind, FrameKind::Sasl);
                assert!(matches!(f.bytes, crate::requests::FrameBytes::Secret(_)));
                let bytes = f.bytes.as_slice();
                let mut r = Reader::new(bytes);
                let len = r.read_i32().unwrap();
                assert_eq!(usize::try_from(len).unwrap(), bytes.len() - 4);
                let hdr = RequestHeader::decode(&mut r).unwrap();
                assert!(hdr.xid > 0);
                assert_eq!(hdr.r#type, opcode::SASL);
                let req = GetSASLRequest::decode(&mut r).unwrap();
                assert_eq!(r.remaining(), 0);
                // The ZooKeeper wire format requires non-null token payloads for SASL requests.
                req.token.into_bytes().unwrap()
            })
            .collect()
    }

    fn step(token: Option<&[u8]>, done: bool) -> SaslStep {
        SaslStep {
            token: token.map(SecretBytes::from_slice),
            done,
        }
    }

    fn mech_err() -> SaslError {
        SaslError {
            rc: rc::ZSYSTEMERROR,
            errstring: Vec::new(),
            errdetail: Vec::new(),
        }
    }

    fn reply(err: i32, token: Option<&[u8]>) -> Vec<u8> {
        let mut b = Vec::new();
        ReplyHeader {
            xid: 1,
            zxid: 9,
            err,
        }
        .encode(&mut b);
        match token {
            None => b.extend_from_slice(&(-1_i32).to_be_bytes()),
            Some(t) => {
                b.extend_from_slice(&i32::try_from(t.len()).unwrap().to_be_bytes());
                b.extend_from_slice(t);
            }
        }
        b
    }

    fn started(c: &mut Connection<Tk>, mech: &str, token: Option<&[u8]>, done: bool) -> i32 {
        let generation = c.sasl.generation;
        c.handle_sasl_started(
            generation,
            Ok((mech.to_owned(), step(token, done))),
            Millis(0),
        )
    }

    #[test]
    fn gen_wraps_to_one() {
        let mut s = SaslState::new();
        assert_eq!(s.advance_gen(), 1);
        s.generation = u32::MAX;
        assert_eq!(s.advance_gen(), 1);
        assert!(s.is_live(1));
        assert!(!s.is_live(0));
    }

    /// Verifies that protocol negotiation issues SASL mechanism calls strictly sequentially.
    #[test]
    fn negotiation_runs_one_call_at_a_time() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        assert!(matches!(c.core.phase, Phase::Sasl(n) if n == SaslNegotiation::default()));
        assert!(c.sasl_awaiting_mechanism());
        assert_eq!(next_call(&mut c), Some((1, "start", None)));
        assert_eq!(c.sasl.mech_gen, Some(1));
        assert!(c.sasl.outstanding);
        // Suppress additional SASL invocations while awaiting resolution of an outstanding step.
        assert_eq!(next_call(&mut c), None);
        assert_eq!(started(&mut c, "PLAIN", Some(b"init"), false), rc::ZOK);
        assert!(!c.sasl_awaiting_mechanism());
        assert_eq!(c.sasl.mechanism.as_deref(), Some("PLAIN"));
        assert_eq!(tx_tokens(&c), vec![b"init".to_vec()]);
        assert_eq!(
            logs(&mut c),
            vec![(LogSite::SaslQueued, vec![LogArg::I64(4), LogArg::I64(0)])]
        );

        // A server challenge packet enqueues a step action and halts socket read iteration.
        let body = reply(0, Some(b"chal"));
        assert_eq!(c.on_sasl_reply(&body, Millis(1)), Flow::Stop);
        assert!(c.sasl_awaiting_mechanism());
        assert_eq!(
            logs(&mut c),
            vec![(LogSite::SaslServerData, vec![LogArg::I64(4)])]
        );
        assert_eq!(next_call(&mut c), Some((1, "step", Some(b"chal".to_vec()))));
        assert_eq!(next_call(&mut c), None);
        let r = c.handle_sasl_stepped(1, Ok(step(Some(b"resp"), false)), Millis(2));
        assert_eq!(r, rc::ZOK);
        assert_eq!(tx_tokens(&c), vec![b"resp".to_vec(), b"init".to_vec()]);
        // For compatibility with the legacy C driver, log packet processing alongside the
        // decoded length.
        let len = i64::try_from(body.len() + 4).unwrap();
        assert_eq!(
            logs(&mut c),
            vec![
                (LogSite::SaslQueued, vec![LogArg::I64(4), LogArg::I64(0)]),
                (
                    LogSite::SaslProcessed,
                    vec![LogArg::I64(len), LogArg::I64(0)]
                ),
            ]
        );
        assert!(!c.sasl_awaiting_mechanism());
    }

    #[test]
    fn digest_md5_initial_response_is_refused() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        assert_eq!(
            started(&mut c, "DIGEST-MD5", Some(b"reauth"), false),
            rc::ZOK
        );
        assert_eq!(tx_tokens(&c), vec![Vec::<u8>::new()]);
        assert_eq!(
            logs(&mut c),
            vec![
                (
                    LogSite::SaslRefuseReauth,
                    vec![LogArg::Str(JString::from("DIGEST-MD5"))]
                ),
                (LogSite::SaslQueued, vec![LogArg::I64(0), LogArg::I64(0)]),
            ]
        );
    }

    #[test]
    fn start_done_with_no_token_sends_nothing() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        assert_eq!(started(&mut c, "EXTERNAL", None, true), rc::ZOK);
        assert!(c.requests.tx.is_empty());
        assert!(logs(&mut c).is_empty());
        // When negotiation completes immediately with an empty token, no wire packet should be
        // transmitted.
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        assert_eq!(started(&mut c, "EXTERNAL", Some(b""), true), rc::ZOK);
        assert!(c.requests.tx.is_empty());
    }

    #[test]
    fn gssapi_and_final_token_flags() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        started(&mut c, "GSSAPI", Some(b"t0"), false);
        assert!(matches!(c.core.phase, Phase::Sasl(n) if n.is_gssapi));
        c.on_sasl_reply(&reply(0, Some(b"c1")), Millis(0));
        next_call(&mut c);
        assert_eq!(
            c.handle_sasl_stepped(1, Ok(step(None, true)), Millis(0)),
            rc::ZOK
        );
        assert!(
            matches!(c.core.phase, Phase::Sasl(n) if n.gssapi_last_packet && !n.awaiting_final)
        );
        assert_eq!(c.requests.tx.len(), 1);

        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        started(&mut c, "SCRAM", Some(b"t0"), false);
        c.on_sasl_reply(&reply(0, Some(b"c1")), Millis(0));
        next_call(&mut c);
        assert_eq!(
            c.handle_sasl_stepped(1, Ok(step(Some(b"t1"), true)), Millis(0)),
            rc::ZOK
        );
        assert!(matches!(c.core.phase, Phase::Sasl(n) if n.awaiting_final && !n.is_gssapi));
        assert_eq!(tx_tokens(&c), vec![b"t1".to_vec(), b"t0".to_vec()]);
    }

    #[test]
    fn null_server_token_gives_step_none() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        started(&mut c, "PLAIN", None, false);
        logs(&mut c);
        assert_eq!(c.on_sasl_reply(&reply(0, None), Millis(0)), Flow::Stop);
        assert_eq!(
            logs(&mut c),
            vec![(LogSite::SaslServerData, vec![LogArg::I64(-1)])]
        );
        assert_eq!(next_call(&mut c), Some((1, "step", None)));
    }

    /// Validates that SASL frame token deserialization correctly rejects malformed buffer lengths.
    #[test]
    fn token_reader_rejects_bad_lengths() {
        let mut short = Vec::new();
        short.extend_from_slice(&5_i32.to_be_bytes());
        short.extend_from_slice(b"ab");
        let e = read_token(&mut Reader::new(&short)).unwrap_err();
        assert!(matches!(e, DecodeError::Truncated { .. }));
        let neg = (-2_i32).to_be_bytes();
        let e = read_token(&mut Reader::new(&neg)).unwrap_err();
        assert_eq!(e, DecodeError::NegativeBuffer(-2));
        assert_eq!(e.c_rc(), -12);
        let e = read_token(&mut Reader::new(&[0, 0])).unwrap_err();
        assert!(matches!(e, DecodeError::Truncated { .. }));
        let mut r = Reader::new(&[0, 0, 0, 0, 7]);
        assert_eq!(read_token(&mut r).unwrap(), Some(&[][..]));
        assert_eq!(r.remaining(), 1);
    }

    /// Verifies that callbacks originating from superseded SASL generations are safely ignored
    /// after teardown.
    #[test]
    fn stale_result_after_teardown_changes_nothing() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        assert_eq!(next_call(&mut c), Some((1, "start", None)));
        c.sasl_on_teardown();
        assert_eq!(c.sasl.generation, 2);
        assert!(c.sasl.calls.is_empty());
        // Outstanding driver waits persist until the in-flight asynchronous invocation yields a
        // result.
        assert!(c.sasl_awaiting_mechanism());
        let r = c.handle_sasl_started(1, Err(mech_err()), Millis(0));
        assert_eq!(r, rc::ZOK);
        assert!(!c.sasl.outstanding);
        assert_eq!(c.state(), State::Associating);
        assert!(c.requests.tx.is_empty());
        assert!(logs(&mut c).is_empty());
        // Stale successful completions and mechanism step callbacks from defunct generations must
        // not mutate state.
        let r = c.handle_sasl_started(
            1,
            Ok(("PLAIN".to_owned(), step(Some(b"x"), false))),
            Millis(0),
        );
        assert_eq!(r, rc::ZOK);
        let r = c.handle_sasl_stepped(1, Ok(step(Some(b"x"), false)), Millis(0));
        assert_eq!(r, rc::ZOK);
        assert_eq!(
            c.handle_sasl_addr_failed(1, SaslAddr::Local, Millis(0)),
            rc::ZOK
        );
        assert!(c.requests.tx.is_empty());
        assert!(c.sasl.mechanism.is_none());
        assert_eq!(c.sasl.mech_gen, Some(1));
        assert!(logs(&mut c).is_empty());
    }

    #[test]
    fn stale_step_after_teardown_drops_the_processed_line() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        started(&mut c, "PLAIN", Some(b"a"), false);
        c.on_sasl_reply(&reply(0, Some(b"b")), Millis(0));
        // Resetting the connection purges queued step actions alongside pending outbound frames.
        c.sasl_on_teardown();
        c.requests.tx.clear();
        assert!(c.sasl.calls.is_empty());
        assert!(c.sasl.processed_len.is_none());
        assert_eq!(next_call(&mut c), None);
        logs(&mut c);
        // Subsequent authentication sequences commence by releasing mechanism state from the prior
        // generation.
        c.begin_sasl(Millis(0));
        assert_eq!(next_call(&mut c), Some((1, "dispose", None)));
        assert_eq!(next_call(&mut c), Some((3, "start", None)));
        let r = c.handle_sasl_stepped(1, Ok(step(Some(b"x"), false)), Millis(0));
        assert_eq!(r, rc::ZOK);
        // An asynchronous response from an earlier generation does not release the barrier for the
        // current active sequence.
        assert!(c.sasl.outstanding);
        assert!(c.requests.tx.is_empty());
        assert_eq!(
            c.handle_sasl_started(
                3,
                Ok(("PLAIN".to_owned(), step(Some(b"n"), false))),
                Millis(0)
            ),
            rc::ZOK
        );
        assert_eq!(tx_tokens(&c), vec![b"n".to_vec()]);
    }

    #[test]
    fn second_negotiation_disposes_then_starts() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        assert_eq!(next_call(&mut c), Some((1, "start", None)));
        started(&mut c, "PLAIN", Some(b"a"), false);
        c.sasl_on_teardown();
        // Socket teardown alone does not immediately dispatch disposal until a new attempt is
        // initiated or closed.
        assert_eq!(next_call(&mut c), None);
        assert_eq!(c.sasl.mech_gen, Some(1));
        c.begin_sasl(Millis(0));
        assert_eq!(c.sasl.mech_gen, None);
        assert_eq!(next_call(&mut c), Some((1, "dispose", None)));
        // Disposal does not block asynchronous handshakes, allowing the new initiation call to be
        // polled immediately.
        assert_eq!(next_call(&mut c), Some((3, "start", None)));
        assert_eq!(c.sasl.mech_gen, Some(3));
        assert_eq!(next_call(&mut c), None);
    }

    /// Verifies that mechanism disposal is deferred while an earlier asynchronous call remains
    /// pending.
    #[test]
    fn dispose_waits_behind_an_outstanding_result() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        assert_eq!(next_call(&mut c), Some((1, "start", None)));
        c.sasl_on_teardown();
        c.begin_sasl(Millis(0));
        assert_eq!(next_call(&mut c), None);
        assert_eq!(
            c.handle_sasl_started(1, Err(mech_err()), Millis(0)),
            rc::ZOK
        );
        assert_eq!(c.state(), State::Associating);
        assert_eq!(next_call(&mut c), Some((1, "dispose", None)));
        assert_eq!(next_call(&mut c), Some((3, "start", None)));
    }

    #[test]
    fn destroy_queues_the_final_dispose_once() {
        let mut c = conn();
        c.sasl_on_destroy();
        assert_eq!(next_call(&mut c), None);
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        started(&mut c, "PLAIN", Some(b"a"), false);
        c.sasl_on_teardown();
        c.sasl_on_destroy();
        c.sasl_on_destroy();
        assert_eq!(next_call(&mut c), Some((1, "dispose", None)));
        assert_eq!(next_call(&mut c), None);
        assert_eq!(c.sasl.mech_gen, None);
    }

    #[test]
    fn mark_failed_sets_state_and_ends_the_negotiation() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        c.mark_sasl_failed();
        assert_eq!(c.state(), State::AuthFailed);
        assert!(c.sasl.calls.is_empty());
        assert_eq!(c.sasl.generation, 2);
        assert!(logs(&mut c).is_empty());
        assert!(!c.is_suspended());
        // Deferred disposal actions remain pending in the queue until retrieved by the driver.
        let mut c = conn();
        c.begin_sasl(Millis(0));
        next_call(&mut c);
        started(&mut c, "PLAIN", Some(b"a"), false);
        c.sasl_on_teardown();
        c.begin_sasl(Millis(0));
        c.mark_sasl_failed();
        assert_eq!(next_call(&mut c), Some((1, "dispose", None)));
        assert_eq!(next_call(&mut c), None);
    }

    #[test]
    fn addr_failure_for_a_stale_or_unknown_start_is_ignored() {
        let mut c = conn();
        c.begin_sasl(Millis(0));
        assert_eq!(
            c.handle_sasl_addr_failed(7, SaslAddr::Peer, Millis(0)),
            rc::ZOK
        );
        assert_eq!(c.sasl.calls.len(), 1);
        next_call(&mut c);
        // Address resolution failures matching mismatched generations do not clear the active
        // driver barrier.
        assert_eq!(
            c.handle_sasl_addr_failed(7, SaslAddr::Peer, Millis(0)),
            rc::ZOK
        );
        assert!(c.sasl.outstanding);
        // Following connection teardown, notification of failure from the prior generation clears
        // the outstanding barrier.
        c.sasl_on_teardown();
        assert_eq!(
            c.handle_sasl_addr_failed(1, SaslAddr::Local, Millis(0)),
            rc::ZOK
        );
        assert!(!c.sasl.outstanding);
        assert_eq!(c.state(), State::Associating);
        assert!(logs(&mut c).is_empty());
    }
}
