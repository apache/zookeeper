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

//! Authentication credential tracking, packet queuing, and reply matching.
//!
//! Owns [`AuthState`]. Clients append authentication schemes and credential payloads via
//! `add_auth`. Inbound server `AUTH` replies (xid -4) match and resolve corresponding client
//! completion tokens. During session re-establishment, post-reconnect finalization retransmits
//! all previously registered credentials to restore security context on the new ensemble member.
//!
//! ZooKeeper servers process authentication packets in the exact order received on the socket.
//! Because client side priority queuing can reorder outbound frames relative to normal requests,
//! the initial enqueue order may differ from actual wire transmission order. Each outbound frame
//! therefore tracks its internal entry index; `poll_transmit` records this index upon writing
//! the frame's initial byte, allowing inbound server replies to resolve entries in precise wire
//! order.

use std::collections::VecDeque;

use zk_jute::proto::RequestHeader;
use zk_jute::{JString, Record, begin_frame, finish_frame, opcode, rc, xid};

use crate::compat::{AuthInsert, AuthResend, CloseMode};
use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::log::{LogArg, LogSite};
use crate::output::{Output, Work};
use crate::recv::Flow;
use crate::request::AddAuthError;
use crate::requests::{FrameKind, OutFrame};
use crate::secret::SecretBytes;
use crate::state::{Cause, State};
use crate::time::Millis;

/// Persistent authentication record appended via `add_auth`. The collection is append-only and
/// retains historical credentials across reconnects.
pub(crate) struct AuthEntry<A> {
    /// Authentication scheme identifier (e.g., "digest", "ip", or "sasl").
    pub(crate) scheme: JString,
    /// Credential payload, protected in memory by zeroizing container types.
    pub(crate) cred: SecretBytes,
    /// Asynchronous completion handle resolved upon server response or cleared upon session
    /// teardown.
    pub(crate) done: Option<A>,
}

/// Connection-level authentication registry managing credential lists and wire-order in-flight
/// tracking.
pub(crate) struct AuthState<T: Tokens> {
    /// Ordered list of credentials submitted over the lifetime of the connection.
    pub(crate) list: Vec<AuthEntry<T::Auth>>,
    /// List indices of authentication packets transmitted across the active transport awaiting
    /// server reply, ordered by wire transmission.
    pub(crate) in_flight: VecDeque<usize>,
    /// Transport epoch corresponding to the contents of `in_flight`, invalidating queued entries
    /// on connection resets.
    in_flight_epoch: u64,
}

impl<T: Tokens> AuthState<T> {
    /// Instantiates an empty authentication registry.
    pub(crate) fn new() -> Self {
        Self {
            list: Vec::new(),
            in_flight: VecDeque::new(),
            in_flight_epoch: 0,
        }
    }

    /// Extracts all outstanding completion tokens in registration order while preserving entry
    /// definitions for session replay.
    pub(crate) fn take_tokens(&mut self) -> Vec<T::Auth> {
        self.list.iter_mut().filter_map(|e| e.done.take()).collect()
    }

    /// Retrieves the authentication scheme of the initial entry for logging and error reporting.
    pub(crate) fn first_scheme(&self) -> Option<&JString> {
        self.list.first().map(|e| &e.scheme)
    }

    /// Consumes the pending completion token and scheme for an entry referenced by an inbound
    /// reply.
    ///
    /// Yields `None` for the token if it was previously consumed by a prior reply or connection
    /// reset. If no entry matches, falls back to the initial scheme or defaults to an empty string.
    fn take_reply(&mut self, entry: Option<usize>) -> (Option<T::Auth>, JString) {
        match entry.and_then(|i| self.list.get_mut(i)) {
            Some(e) => (e.done.take(), e.scheme.clone()),
            None => (None, self.first_scheme().cloned().unwrap_or_default()),
        }
    }
}

/// Execution phase marker for staged processing of inbound authentication responses.
///
/// Full AUTH response handling spans lockpoint synchronization, state transitions,
/// token delivery, diagnostic logging, barrier emission, and error teardown.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum AuthReplyStep {
    /// Resumes after releasing thread synchronization locks at step 2.
    AfterLockpoint,
    /// Resumes after delivering the authentication barrier notification at step 6.
    AfterAuth,
}

/// Continuation descriptor for multi-step authentication reply handling across asynchronous
/// barriers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct AuthReplyJob {
    /// Target execution stage to resume.
    pub(crate) step: AuthReplyStep,
    /// Protocol return code received in the reply header.
    pub(crate) err: i32,
    /// Transport epoch active when the reply was received, used to discard stale responses across
    /// reconnects.
    pub(crate) epoch: u64,
    /// Target credential index in `AuthState::list` addressed by this response, if tracked.
    pub(crate) entry: Option<usize>,
}

impl<T: Tokens> Connection<T> {
    /// Registers an authentication entry and queues an outbound packet if connection state permits.
    ///
    /// # Protocol Semantics & State Constraints:
    /// - Following handle destruction (`destroy`), immediately fails with `ZINVALIDSTATE`.
    /// - Under `CloseMode::Drain`, an ongoing graceful shutdown rejects submission with `ZCLOSING`.
    /// - If the connection is unrecoverable (`state < 0`) or closed when `auth_reject_closed` is
    ///   configured, submission returns `ZINVALIDSTATE`.
    /// - Otherwise, the credential and completion token are appended to internal connection state.
    /// - When connected (or when associating without SASL configured), constructs an `AuthPacket`
    ///   frame (xid -4, op 100) embedding the entry index. Insertion order (priority boundary or
    ///   queue tail) is governed by `auth_insert`. Wire transmission records the entry index for
    ///   response matching.
    ///
    /// # Errors
    ///
    /// Returns [`AddAuthError`] containing the ZooKeeper error code and relinquishes the
    /// unconsumed completion token.
    pub fn add_auth(
        &mut self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<T::Auth>,
        _now: Millis,
    ) -> Result<(), AddAuthError<T>> {
        let state = self.core.state;
        let closed = self.compat.auth_reject_closed && state == State::Closed;
        if self.close.destroyed || state.code() < 0 || closed {
            return Err(AddAuthError {
                code: rc::ZINVALIDSTATE,
                done,
            });
        }
        if self.compat.close_mode == CloseMode::Drain && self.close.closing {
            return Err(AddAuthError {
                code: rc::ZCLOSING,
                done,
            });
        }
        // In the associating phase without SASL negotiation, the legacy C client transmits
        // credentials immediately and subsequently re-sends them upon session finalization.
        let send = state.is_connected()
            || (state == State::Associating
                && !self.cfg.sasl
                && self.compat.auth_dup_while_associating);
        let entry = self.auth.list.len();
        let frame = send.then(|| auth_out_frame(entry, &scheme, &cred));
        self.auth.list.push(AuthEntry { scheme, cred, done });
        if let Some(frame) = frame {
            match self.compat.auth_insert {
                AuthInsert::Priority => self.requests.push_priority(frame),
                AuthInsert::Tail => self.requests.push_tail(frame),
            }
            let server = self.current_server();
            self.emit_log(LogSite::SendLastAuth, || vec![LogArg::Endpoint(server)]);
        }
        Ok(())
    }

    /// Processes an inbound AUTH response (xid -4).
    ///
    /// Pops the oldest entry index from `in_flight` to match the server reply against the original
    /// request. When lockpoint synchronization is enabled, emits `AuthLockpoint`, suspends
    /// execution by parking an `AuthReplyJob`, and returns `Flow::Stop`. Otherwise, resolves the
    /// reply inline.
    pub(crate) fn on_auth_reply(&mut self, err: i32, now: Millis) -> Flow {
        let entry = self.auth_in_flight().pop_front();
        if self.compat.emits_lockpoints() {
            self.out.push(Output::AuthLockpoint);
            self.out.park(Work::AuthReply(AuthReplyJob {
                step: AuthReplyStep::AfterLockpoint,
                err,
                epoch: self.core.epoch,
                entry,
            }));
            return Flow::Stop;
        }
        self.auth_reply_inline(err, entry, now)
    }

    /// Resumes processing of an AUTH reply following an asynchronous synchronization barrier.
    ///
    /// # State Transitions & Lifecycle:
    /// - `AfterLockpoint`: Discards the continuation if the transport epoch shifted during lock
    ///   acquisition. Otherwise, on protocol failure transitions connection state to `AuthFailed`
    ///   (-113), extracts the associated completion token, logs the scheme result, and emits an
    ///   `Auth` output event. If a failure barrier is raised, parks `AfterAuth`; otherwise proceeds
    ///   directly to teardown evaluation.
    /// - `AfterAuth`: If connection state remains `AuthFailed`, initiates connection teardown with
    ///   `Cause::AuthFailed`.
    pub(crate) fn continue_auth_reply(&mut self, job: AuthReplyJob, now: Millis) {
        // Discard reply continuation if transport teardown or forced socket closure invalidated
        // the active epoch.
        if job.epoch != self.core.epoch {
            return;
        }
        if job.step == AuthReplyStep::AfterAuth {
            self.auth_finish_reply(now);
            return;
        }
        if self.auth_apply_reply(job.err, job.entry, true) {
            self.out.park(Work::AuthReply(AuthReplyJob {
                step: AuthReplyStep::AfterAuth,
                ..job
            }));
            return;
        }
        self.auth_finish_reply(now);
    }

    /// Retransmits all registered authentication credentials upon establishing a new connection
    /// session.
    ///
    /// Executed during post-connect finalization. Under `LegacyReverseFront`, entries are pushed to
    /// the priority boundary in registration order, yielding reverse wire transmission (`auth_N`
    /// down to `auth_1`). Under `Forward`, entries are reversed prior to priority insertion to
    /// preserve sequential wire order.
    pub(crate) fn queue_auth_resend(&mut self) {
        let mut frames: Vec<OutFrame> = self
            .auth
            .list
            .iter()
            .enumerate()
            .map(|(i, e)| auth_out_frame(i, &e.scheme, &e.cred))
            .collect();
        // Priority insertions push earlier frames backward. Reversing the slice prior to insertion
        // guarantees that earlier credentials precede later ones on the outbound wire stream.
        if matches!(self.compat.auth_resend, AuthResend::Forward) {
            frames.reverse();
        }
        let count = frames.len();
        for f in frames {
            self.requests.push_priority(f);
        }
        if count > 0 {
            let server = self.current_server();
            self.emit_log(LogSite::SendAllAuth, || vec![LogArg::Endpoint(server)]);
        }
    }

    /// Records transmission of an authentication packet for `entry`, establishing expected FIFO
    /// reply order.
    pub(crate) fn auth_record_sent(&mut self, entry: usize) {
        self.auth_in_flight().push_back(entry);
    }

    /// Accesses the active transport's in-flight authentication queue, resetting stale state across
    /// transport epochs.
    fn auth_in_flight(&mut self) -> &mut VecDeque<usize> {
        if self.auth.in_flight_epoch != self.core.epoch {
            self.auth.in_flight.clear();
            self.auth.in_flight_epoch = self.core.epoch;
        }
        &mut self.auth.in_flight
    }

    /// Executes inline AUTH reply handling without synchronization lockpoints or asynchronous
    /// barriers.
    fn auth_reply_inline(&mut self, err: i32, entry: Option<usize>, now: Millis) -> Flow {
        self.auth_apply_reply(err, entry, false);
        if self.core.state.code() < 0 {
            return Flow::Done(self.teardown(Cause::AuthFailed, 0, now));
        }
        Flow::Continue
    }

    /// Updates internal authentication state and delivers completion events for a received reply.
    ///
    /// On server error, transitions state to `AuthFailed` (-113) and records diagnostic logging.
    /// Returns `true` if an error barrier output was pushed to suspend the event loop.
    fn auth_apply_reply(&mut self, err: i32, entry: Option<usize>, barriers: bool) -> bool {
        if err != 0 {
            self.set_state(State::AuthFailed);
        }
        let (token, scheme) = self.auth.take_reply(entry);
        self.auth_log_result(err, scheme);
        let Some(token) = token else {
            return false;
        };
        let barrier = barriers && self.core.state.code() < 0;
        self.out.push(Output::Auth {
            tokens: vec![token],
            rc: err,
            barrier,
        });
        barrier
    }

    /// Finalizes AUTH reply processing, initiating connection teardown if authentication failed.
    fn auth_finish_reply(&mut self, now: Millis) {
        if self.core.state.code() < 0 {
            // Asynchronous reply completion occurs outside the initial read loop; discard teardown
            // return codes.
            let _ = self.teardown(Cause::AuthFailed, 0, now);
        }
    }

    /// Emits structured diagnostic log events recording authentication success or failure for a
    /// scheme.
    fn auth_log_result(&mut self, err: i32, scheme: JString) {
        let site = if err != 0 {
            LogSite::AuthSchemeFailed
        } else {
            LogSite::AuthSchemeSucceeded
        };
        self.emit_log(site, move || vec![LogArg::Str(scheme)]);
    }
}

/// Constructs a wire-format request frame terminating with a secret byte buffer.
///
/// Encodes the frame length prefix, [`RequestHeader`], unencrypted payload bytes, and the secret
/// buffer as a Jute-delimited sequence. Allocates exact capacity to prevent heap reallocation and
/// avoid leaving stale secret copies in unmanaged memory.
pub(crate) fn secret_frame(xid: i32, op_type: i32, plain: &[u8], secret: &[u8]) -> Vec<u8> {
    let secret_len = i32::try_from(secret.len()).unwrap_or(i32::MAX);
    let secret = usize::try_from(secret_len)
        .ok()
        .and_then(|n| secret.get(..n))
        .unwrap_or(secret);
    let header = RequestHeader {
        xid,
        r#type: op_type,
    };
    let capacity = 4_usize
        .saturating_add(header.encoded_len())
        .saturating_add(plain.len())
        .saturating_add(4)
        .saturating_add(secret.len());
    let mut out = Vec::with_capacity(capacity);
    let start = begin_frame(&mut out);
    header.encode(&mut out);
    out.extend_from_slice(plain);
    out.extend_from_slice(&secret_len.to_be_bytes());
    out.extend_from_slice(secret);
    finish_frame(&mut out, start);
    out
}

/// Encodes an `AuthPacket{0, scheme, cred}` frame (xid -4, op 100).
///
/// Emits empty credentials as zero-length buffers rather than null markers, adhering to C client
/// wire behavior.
fn auth_frame(scheme: &JString, cred: &SecretBytes) -> Vec<u8> {
    let mut plain = Vec::with_capacity(4_usize.saturating_add(scheme.encoded_len()));
    plain.extend_from_slice(&0_i32.to_be_bytes());
    scheme.encode(&mut plain);
    secret_frame(xid::AUTH, opcode::SET_AUTH, &plain, cred.expose())
}

/// Wraps an encoded `AuthPacket` frame into an [`OutFrame`] tracking its internal registry index.
fn auth_out_frame(entry: usize, scheme: &JString, cred: &SecretBytes) -> OutFrame {
    OutFrame::secret(FrameKind::Auth { entry }, auth_frame(scheme, cred))
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
    use crate::action::ConnectStart;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::hooks::{AllLevels, NoDefaultWatcher, Rng};
    use crate::state::Phase;
    use crate::teardown::test_support::response;
    use zk_jute::proto::AuthPacket;

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

    const ALL_STATES: [State; 9] = [
        State::Closed,
        State::Connecting,
        State::Associating,
        State::Connected,
        State::ReadOnly,
        State::SslConnecting,
        State::NotConnected,
        State::ExpiredSession,
        State::AuthFailed,
    ];

    fn conn(compat: Compat, sasl: bool) -> Connection<Tk> {
        let mut cfg = Config::legacy(30_000);
        cfg.sasl = sasl;
        Connection::new(
            cfg,
            compat,
            DriverMode::ST,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        )
    }

    fn drain(c: &mut Connection<Tk>) -> Vec<Output<Tk>> {
        let mut v = Vec::new();
        while let Some(o) = c.poll_output() {
            v.push(o);
        }
        v
    }

    fn sites(outs: &[Output<Tk>]) -> Vec<LogSite> {
        outs.iter()
            .filter_map(|o| match o {
                Output::Log(r) => Some(r.site),
                _ => None,
            })
            .collect()
    }

    /// Deserializes an authentication frame, verifying framing length and credential payload bytes
    /// before returning the decoded transaction ID, opcode, and scheme.
    fn decode_auth(bytes: &[u8], cred: &[u8]) -> (i32, i32, JString) {
        let mut r = zk_jute::Reader::new(bytes);
        let len = r.read_i32().unwrap();
        assert_eq!(usize::try_from(len).unwrap(), bytes.len() - 4);
        let hdr = RequestHeader::decode(&mut r).unwrap();
        let pkt = AuthPacket::decode(&mut r).unwrap();
        assert_eq!(pkt.r#type, 0);
        assert_eq!(pkt.auth.as_bytes(), Some(cred));
        assert_eq!(r.remaining(), 0);
        (hdr.xid, hdr.r#type, pkt.scheme)
    }

    /// Decodes authentication frames in outbound transmission order, returning scheme payloads.
    fn tx_schemes(c: &Connection<Tk>) -> Vec<Option<Vec<u8>>> {
        c.requests
            .tx
            .iter()
            .map(|f| match f.kind {
                FrameKind::Auth { .. } => {
                    assert!(matches!(f.bytes, crate::requests::FrameBytes::Secret(_)));
                    let (x, op, scheme) = decode_auth(f.bytes.as_slice(), b"pw");
                    assert_eq!((x, op), (xid::AUTH, opcode::SET_AUTH));
                    scheme.into_bytes()
                }
                _ => None,
            })
            .collect()
    }

    fn add(c: &mut Connection<Tk>, scheme: &str, tok: u32) -> Result<(), AddAuthError<Tk>> {
        c.add_auth(
            JString::from(scheme),
            SecretBytes::from_slice(b"pw"),
            Some(tok),
            Millis(0),
        )
    }

    fn plain_request(c: &mut Connection<Tk>) {
        c.requests
            .push_tail(OutFrame::plain(FrameKind::Request { xid: 7 }, vec![0; 8]));
    }

    // Emulation drivers below simulate client side transport event loops.

    /// Observable protocol output events captured during authentication reply testing.
    #[derive(Debug, PartialEq, Eq)]
    enum Ev {
        /// Emitted log message indicating scheme resolution.
        Scheme(LogSite, JString),
        /// Completion event containing tokens, return code, and barrier state.
        Auth(Vec<u32>, i32, bool),
        /// Thread synchronization lockpoint boundary.
        Lockpoint,
        /// Connection state transition event.
        Session(i32),
    }

    /// Drains emitted connection outputs and resumes through synchronization barriers until
    /// completion.
    fn run(c: &mut Connection<Tk>) -> Vec<Ev> {
        let mut evs = Vec::new();
        loop {
            if let Some(o) = c.poll_output() {
                match o {
                    Output::Log(r)
                        if matches!(
                            r.site,
                            LogSite::AuthSchemeSucceeded | LogSite::AuthSchemeFailed
                        ) =>
                    {
                        let scheme = match r.args.as_slice() {
                            [LogArg::Str(s)] => s.clone(),
                            a => panic!("unexpected args {a:?}"),
                        };
                        evs.push(Ev::Scheme(r.site, scheme));
                    }
                    Output::Auth {
                        tokens,
                        rc,
                        barrier,
                    } => evs.push(Ev::Auth(tokens, rc, barrier)),
                    Output::AuthLockpoint => evs.push(Ev::Lockpoint),
                    Output::Session { state, .. } => evs.push(Ev::Session(state.code())),
                    _ => {}
                }
                continue;
            }
            match c.take_stopped() {
                Some(s) => c.resume(s, Millis(0)),
                None => break,
            }
        }
        evs
    }

    fn ok(scheme: &str) -> Ev {
        Ev::Scheme(LogSite::AuthSchemeSucceeded, JString::from(scheme))
    }

    /// Flushes up to `max` outbound bytes through transmission polling, returning transmitted
    /// bytes.
    fn send_some(c: &mut Connection<Tk>, max: usize) -> Vec<u8> {
        let mut out = Vec::new();
        let n = c.poll_transmit(&mut out, max);
        c.handle_transmitted(n, Millis(0));
        out
    }

    /// Partitions a raw byte stream into discrete ZooKeeper protocol frames, decoding auth scheme
    /// payloads.
    fn wire(bytes: &[u8]) -> Vec<Option<Vec<u8>>> {
        let mut frames = Vec::new();
        let mut rest = bytes;
        while !rest.is_empty() {
            let len = i32::from_be_bytes(rest[..4].try_into().unwrap());
            let (frame, tail) = rest.split_at(4 + usize::try_from(len).unwrap());
            let auth = frame[4..8] == xid::AUTH.to_be_bytes();
            frames.push(auth.then(|| decode_auth(frame, b"pw").2.into_bytes().unwrap()));
            rest = tail;
        }
        frames
    }

    /// Drains all pending outbound data, reconstructing framed wire packets.
    fn send(c: &mut Connection<Tk>) -> Vec<Option<Vec<u8>>> {
        wire(&send_some(c, usize::MAX))
    }

    fn schemes(names: &[&str]) -> Vec<Option<Vec<u8>>> {
        names.iter().map(|n| Some(n.as_bytes().to_vec())).collect()
    }

    /// Simulates transport establishment and session connection handshake.
    fn connect(c: &mut Connection<Tk>) {
        c.handle_connect_started(ConnectStart::InProgress, Millis(0));
        c.handle_tcp_connected(Millis(0));
        c.handle_transport_ready(Millis(0));
        assert_eq!(send(c), vec![None]);
    }

    /// Simulates receipt of server `ConnectResponse` for session establishment.
    fn respond(c: &mut Connection<Tk>, read_only: bool) -> Vec<Ev> {
        let body = response(30_000, 0x55, read_only);
        let mut frame = i32::try_from(body.len()).unwrap().to_be_bytes().to_vec();
        frame.extend_from_slice(&body);
        assert_eq!(c.handle_read(&frame, Millis(0)).frames, 1);
        run(c)
    }

    /// Establishes transport connection and completes initial session finalization.
    fn establish(c: &mut Connection<Tk>, read_only: bool) -> Vec<Ev> {
        connect(c);
        respond(c, read_only)
    }

    /// Injects a synthesized AUTH response frame with the specified return code.
    fn reply(c: &mut Connection<Tk>, err: i32) -> Vec<Ev> {
        let mut frame = 16_i32.to_be_bytes().to_vec();
        frame.extend_from_slice(&xid::AUTH.to_be_bytes());
        frame.extend_from_slice(&0_i64.to_be_bytes());
        frame.extend_from_slice(&err.to_be_bytes());
        assert_eq!(c.handle_read(&frame, Millis(0)).frames, 1);
        run(c)
    }

    #[test]
    fn take_tokens_keeps_entries() {
        let mut a: AuthState<Tk> = AuthState::new();
        for (i, tok) in [Some(1_u32), None, Some(3)].into_iter().enumerate() {
            a.list.push(AuthEntry {
                scheme: JString::Bytes(vec![b'a'; i]),
                cred: SecretBytes::empty(),
                done: tok,
            });
        }
        assert_eq!(a.take_tokens(), vec![1, 3]);
        assert_eq!(a.list.len(), 3);
        assert!(a.take_tokens().is_empty());
    }

    #[test]
    fn add_auth_each_state_legacy() {
        for sasl in [false, true] {
            for state in ALL_STATES {
                let mut c = conn(Compat::LEGACY_C, sasl);
                c.set_state(state);
                plain_request(&mut c);
                let r = add(&mut c, "digest", 5);
                let rejected = state.code() <= 0;
                if rejected {
                    let e = r.unwrap_err();
                    assert_eq!(e.code, rc::ZINVALIDSTATE, "{state:?}");
                    assert_eq!(e.done, Some(5));
                    assert!(c.auth.list.is_empty());
                    assert_eq!(c.requests.tx.len(), 1);
                    assert!(sites(&drain(&mut c)).is_empty());
                    continue;
                }
                assert!(r.is_ok(), "{state:?}");
                assert_eq!(c.auth.list.len(), 1);
                assert_eq!(c.auth.list[0].done, Some(5));
                // Outbound packets are tracked in flight upon wire transmission rather than initial
                // queue insertion.
                assert!(c.auth.in_flight.is_empty());
                let sends = state.is_connected() || (state == State::Associating && !sasl);
                let logs = sites(&drain(&mut c));
                if sends {
                    // Priority insertion queues authentication packets ahead of standard pending
                    // requests.
                    assert_eq!(tx_schemes(&c), vec![Some(b"digest".to_vec()), None]);
                    assert_eq!(logs, vec![LogSite::SendLastAuth], "{state:?}");
                } else {
                    assert_eq!(c.requests.tx.len(), 1, "{state:?} sasl={sasl}");
                    assert!(logs.is_empty());
                }
            }
        }
    }

    #[test]
    fn add_auth_each_state_native() {
        for sasl in [false, true] {
            for state in ALL_STATES {
                let mut c = conn(Compat::NATIVE, sasl);
                c.set_state(state);
                plain_request(&mut c);
                let r = add(&mut c, "digest", 5);
                if state.code() < 0 {
                    let e = r.unwrap_err();
                    assert_eq!((e.code, e.done), (rc::ZINVALIDSTATE, Some(5)));
                    assert!(c.auth.list.is_empty());
                    continue;
                }
                // Native compatibility permits credential registration in unassociated states.
                assert!(r.is_ok(), "{state:?}");
                assert_eq!(c.auth.list.len(), 1);
                assert!(c.auth.in_flight.is_empty());
                // In native mode, packet transmission is deferred until post-connect session
                // finalization.
                if state.is_connected() {
                    assert_eq!(tx_schemes(&c), vec![None, Some(b"digest".to_vec())]);
                } else {
                    assert_eq!(c.requests.tx.len(), 1, "{state:?}");
                }
            }
        }
    }

    #[test]
    fn add_auth_associating_with_dup_under_native() {
        let mut compat = Compat::NATIVE;
        compat.auth_dup_while_associating = true;
        let mut c = conn(compat, false);
        c.set_state(State::Associating);
        add(&mut c, "a", 1).unwrap();
        assert_eq!(tx_schemes(&c), vec![Some(b"a".to_vec())]);
        assert!(c.auth.in_flight.is_empty());
    }

    #[test]
    fn auth_frame_writes_empty_credential_as_length_zero() {
        let bytes = auth_frame(&JString::from("x"), &SecretBytes::empty());
        assert_eq!(bytes.capacity(), bytes.len());
        let (x, op, scheme) = decode_auth(&bytes, b"");
        assert_eq!((x, op, scheme), (-4, 100, JString::from("x")));
        let null = auth_frame(&JString::Null, &SecretBytes::from_slice(b"pw"));
        assert_eq!(decode_auth(&null, b"pw").2, JString::Null);
    }

    fn three_entries(c: &mut Connection<Tk>) {
        for (s, t) in [("a", 1), ("b", 2), ("c", 3)] {
            c.auth.list.push(AuthEntry {
                scheme: JString::from(s),
                cred: SecretBytes::from_slice(b"pw"),
                done: Some(t),
            });
        }
    }

    #[test]
    fn resend_order_legacy_reverse_front() {
        let mut c = conn(Compat::LEGACY_C, false);
        three_entries(&mut c);
        plain_request(&mut c);
        c.queue_auth_resend();
        let want: Vec<Option<Vec<u8>>> = vec![
            Some(b"c".to_vec()),
            Some(b"b".to_vec()),
            Some(b"a".to_vec()),
            None,
        ];
        assert_eq!(tx_schemes(&c), want);
        assert!(c.auth.in_flight.is_empty());
        assert_eq!(sites(&drain(&mut c)), vec![LogSite::SendAllAuth]);
    }

    #[test]
    fn resend_order_forward() {
        let mut c = conn(Compat::NATIVE, false);
        three_entries(&mut c);
        plain_request(&mut c);
        c.queue_auth_resend();
        let want: Vec<Option<Vec<u8>>> = vec![
            Some(b"a".to_vec()),
            Some(b"b".to_vec()),
            Some(b"c".to_vec()),
            None,
        ];
        assert_eq!(tx_schemes(&c), want);
        assert!(c.auth.in_flight.is_empty());
        assert_eq!(sites(&drain(&mut c)), vec![LogSite::SendAllAuth]);
    }

    #[test]
    fn resend_frames_hold_their_entry_index() {
        let mut c = conn(Compat::LEGACY_C, false);
        three_entries(&mut c);
        c.queue_auth_resend();
        let entries: Vec<FrameKind> = c.requests.tx.iter().map(|f| f.kind).collect();
        assert_eq!(
            entries,
            vec![
                FrameKind::Auth { entry: 2 },
                FrameKind::Auth { entry: 1 },
                FrameKind::Auth { entry: 0 },
            ]
        );
    }

    #[test]
    fn record_drops_the_indexes_of_an_old_transport() {
        let mut c = conn(Compat::LEGACY_C, false);
        let _ = c.open_transport();
        c.auth_record_sent(1);
        c.auth_record_sent(0);
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![1, 0]));
        c.lose_transport();
        let _ = c.open_transport();
        c.auth_record_sent(2);
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![2]));
    }

    #[test]
    fn resend_empty_list_logs_nothing() {
        let mut c = conn(Compat::LEGACY_C, false);
        c.queue_auth_resend();
        assert!(c.requests.tx.is_empty());
        assert!(drain(&mut c).is_empty());
    }

    /// Validates that successful AUTH responses resolve only the completion token matching the sent
    /// frame.
    #[test]
    fn legacy_reply_success_completes_its_own_token() {
        let mut c = conn(Compat::LEGACY_C, false);
        c.set_state(State::Connected);
        three_entries(&mut c);
        c.auth_record_sent(1);
        assert_eq!(c.on_auth_reply(0, Millis(5)), Flow::Stop);
        let outs = drain(&mut c);
        assert!(matches!(outs.as_slice(), [Output::AuthLockpoint]));
        let stopped = c.take_stopped().unwrap();
        c.resume(stopped, Millis(5));
        let outs = drain(&mut c);
        assert_eq!(outs.len(), 2);
        match &outs[0] {
            Output::Log(r) => {
                assert_eq!(r.site, LogSite::AuthSchemeSucceeded);
                assert_eq!(r.args, vec![LogArg::Str(JString::from("b"))]);
            }
            o => panic!("unexpected {o:?}"),
        }
        match &outs[1] {
            Output::Auth {
                tokens,
                rc,
                barrier,
            } => {
                assert_eq!(tokens, &vec![2]);
                assert_eq!((*rc, *barrier), (0, false));
            }
            o => panic!("unexpected {o:?}"),
        }
        assert_eq!(c.state(), State::Connected);
        assert!(c.take_stopped().is_none());
        assert!(!c.is_suspended());
        assert_eq!(c.auth.list[0].done, Some(1));
        assert_eq!(c.auth.list[2].done, Some(3));
    }

    #[test]
    fn legacy_reply_failure_sets_auth_failed_before_the_teardown() {
        let mut c = conn(Compat::LEGACY_C, false);
        c.set_state(State::Connected);
        three_entries(&mut c);
        c.auth_record_sent(0);
        assert_eq!(c.on_auth_reply(rc::ZAUTHFAILED, Millis(5)), Flow::Stop);
        assert!(matches!(drain(&mut c).as_slice(), [Output::AuthLockpoint]));
        // State transitions to AuthFailed following lockpoint resolution rather than synchronously
        // upon receipt.
        assert_eq!(c.state(), State::Connected);
        let stopped = c.take_stopped().unwrap();
        c.resume(stopped, Millis(5));
        assert_eq!(c.state(), State::AuthFailed);
        let outs = drain(&mut c);
        assert_eq!(sites(&outs), vec![LogSite::AuthSchemeFailed]);
        assert!(matches!(
            outs.last(),
            Some(Output::Auth { tokens, rc: -115, barrier: true }) if tokens == &vec![1]
        ));
        // The failure barrier suspends the event loop prior to initiating connection teardown.
        let stopped = c.take_stopped().unwrap();
        assert!(c.is_suspended());
        // Forced connection closure across the barrier supersedes teardown and advances the
        // transport epoch.
        c.set_state(State::NotConnected);
        c.lose_transport();
        c.resume(stopped, Millis(6));
        assert!(drain(&mut c).is_empty());
        assert!(!c.is_suspended());
        assert_eq!(c.state(), State::NotConnected);
    }

    #[test]
    fn legacy_reply_after_forced_close_is_dropped() {
        let mut c = conn(Compat::LEGACY_C, false);
        c.set_state(State::Connected);
        three_entries(&mut c);
        c.auth_record_sent(0);
        assert_eq!(c.on_auth_reply(rc::ZAUTHFAILED, Millis(5)), Flow::Stop);
        drain(&mut c);
        let stopped = c.take_stopped().unwrap();
        c.set_state(State::NotConnected);
        c.lose_transport();
        c.resume(stopped, Millis(5));
        assert_eq!(c.state(), State::NotConnected);
        assert!(drain(&mut c).is_empty());
        assert_eq!(c.auth.list.iter().filter(|e| e.done.is_some()).count(), 3);
    }

    /// Validates that AUTH responses received with an empty credential list log a null scheme
    /// identifier.
    #[test]
    fn legacy_reply_with_empty_list_logs_null_scheme() {
        let mut c = conn(Compat::LEGACY_C, false);
        c.set_state(State::Connected);
        assert_eq!(c.on_auth_reply(0, Millis(0)), Flow::Stop);
        drain(&mut c);
        let stopped = c.take_stopped().unwrap();
        c.resume(stopped, Millis(0));
        let outs = drain(&mut c);
        assert_eq!(outs.len(), 1);
        match &outs[0] {
            Output::Log(r) => {
                assert_eq!(r.site, LogSite::AuthSchemeSucceeded);
                assert_eq!(r.args, vec![LogArg::Str(JString::Null)]);
            }
            o => panic!("unexpected {o:?}"),
        }
    }

    #[test]
    fn native_reply_completes_oldest_entry() {
        let mut c = conn(Compat::NATIVE, false);
        c.set_state(State::Connected);
        add(&mut c, "a", 1).unwrap();
        add(&mut c, "b", 2).unwrap();
        drain(&mut c);
        c.auth_record_sent(0);
        c.auth_record_sent(1);
        assert_eq!(c.on_auth_reply(0, Millis(0)), Flow::Continue);
        let outs = drain(&mut c);
        assert_eq!(outs.len(), 2);
        assert!(matches!(&outs[0], Output::Log(r)
            if r.site == LogSite::AuthSchemeSucceeded
                && r.args == vec![LogArg::Str(JString::from("a"))]));
        assert!(matches!(&outs[1],
            Output::Auth { tokens, rc: 0, barrier: false } if tokens == &vec![1]));
        assert_eq!(c.auth.list[1].done, Some(2));
        assert_eq!(c.on_auth_reply(0, Millis(0)), Flow::Continue);
        let outs = drain(&mut c);
        assert!(matches!(outs.last(),
            Some(Output::Auth { tokens, .. }) if tokens == &vec![2]));
        // Responses matching no in-flight requests log scheme status without resolving tokens.
        assert_eq!(c.on_auth_reply(0, Millis(0)), Flow::Continue);
        assert_eq!(sites(&drain(&mut c)), vec![LogSite::AuthSchemeSucceeded]);
        assert!(c.auth.in_flight.is_empty());
        assert!(!c.is_suspended());
    }

    #[test]
    fn priority_inserts_reply_in_wire_order() {
        let mut c = conn(Compat::LEGACY_C, false);
        let _ = establish(&mut c, false);
        add(&mut c, "a", 1).unwrap();
        add(&mut c, "b", 2).unwrap();
        // Priority insertions push earlier frames backward in the transmission queue.
        assert_eq!(send(&mut c), schemes(&["b", "a"]));
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![1, 0]));
        let first = reply(&mut c, 0);
        assert_eq!(
            first,
            vec![Ev::Lockpoint, ok("b"), Ev::Auth(vec![2], 0, false)]
        );
        let second = reply(&mut c, 0);
        assert_eq!(
            second,
            vec![Ev::Lockpoint, ok("a"), Ev::Auth(vec![1], 0, false)]
        );
        // A reply matching no in-flight packet logs the default scheme without delivering tokens.
        assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("a")]);
    }

    /// Validates that priority insertion does not interrupt partially transmitted frames on the
    /// wire.
    #[test]
    fn a_partly_sent_packet_keeps_its_place() {
        let mut c = conn(Compat::LEGACY_C, false);
        let _ = establish(&mut c, false);
        add(&mut c, "a", 1).unwrap();
        // Writing the frame's initial byte fixes its transmission position on the wire.
        let head = send_some(&mut c, 3);
        assert_eq!(head.len(), 3);
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![0]));
        add(&mut c, "b", 2).unwrap();
        let mut bytes = head;
        bytes.extend(send_some(&mut c, usize::MAX));
        assert_eq!(wire(&bytes), schemes(&["a", "b"]));
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![0, 1]));
        assert_eq!(reply(&mut c, 0)[2], Ev::Auth(vec![1], 0, false));
        assert_eq!(reply(&mut c, 0)[2], Ev::Auth(vec![2], 0, false));
    }

    /// Validates that duplicate authentication transmissions resolve the associated token exactly
    /// once.
    #[test]
    fn a_duplicate_packet_completes_its_token_once() {
        let mut c = conn(Compat::LEGACY_C, false);
        connect(&mut c);
        // Under legacy C semantics, associating state sends initial credentials, followed by
        // retransmission during session finalization.
        add(&mut c, "a", 1).unwrap();
        let _ = respond(&mut c, false);
        assert_eq!(send(&mut c), schemes(&["a", "a"]));
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![0, 0]));
        assert_eq!(
            reply(&mut c, 0),
            vec![Ev::Lockpoint, ok("a"), Ev::Auth(vec![1], 0, false)]
        );
        // Secondary responses for duplicate transmissions log resolution without emitting redundant
        // completions.
        assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("a")]);
    }

    #[test]
    fn a_new_transport_drops_the_old_indexes_and_never_reports_twice() {
        let mut c = conn(Compat::LEGACY_C, false);
        let _ = establish(&mut c, false);
        add(&mut c, "a", 1).unwrap();
        add(&mut c, "b", 2).unwrap();
        assert_eq!(send(&mut c), schemes(&["b", "a"]));
        assert_eq!(reply(&mut c, 0)[2], Ev::Auth(vec![2], 0, false));
        // Failure to receive reply before socket loss triggers completion via teardown connection
        // loss.
        let _ = c.teardown(Cause::Eof, 0, Millis(0));
        let evs = run(&mut c);
        assert!(evs.contains(&Ev::Auth(vec![1], rc::ZCONNECTIONLOSS, true)));
        // Reconnected transports retransmit all registered credentials, where duplicate replies
        // yield no tokens.
        let _ = establish(&mut c, false);
        assert_eq!(send(&mut c), schemes(&["b", "a"]));
        assert_eq!(c.auth.in_flight, VecDeque::from(vec![1, 0]));
        assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("b")]);
        assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("a")]);
    }

    #[test]
    fn forced_close_then_an_add_completes_on_its_resend() {
        let mut c = conn(Compat::LEGACY_C, false);
        let _ = establish(&mut c, false);
        add(&mut c, "a", 1).unwrap();
        assert_eq!(send(&mut c), schemes(&["a"]));
        c.handle_forced_close(Millis(0));
        assert_eq!(c.state(), State::NotConnected);
        // Adding credentials while disconnected buffers the entry for transmission upon
        // reconnection.
        add(&mut c, "b", 2).unwrap();
        assert!(c.requests.tx.is_empty());
        assert!(c.run_pending_teardown(Millis(0)));
        let evs = run(&mut c);
        assert!(evs.contains(&Ev::Auth(vec![1], rc::ZCONNECTIONLOSS, true)));
        assert_eq!(c.auth.list[1].done, Some(2));
        let _ = establish(&mut c, false);
        assert_eq!(send(&mut c), schemes(&["b", "a"]));
        assert_eq!(
            reply(&mut c, 0),
            vec![Ev::Lockpoint, ok("b"), Ev::Auth(vec![2], 0, false)]
        );
        assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("a")]);
    }

    #[test]
    fn rw_switch_completes_the_pending_token_once() {
        let mut c = conn(Compat::LEGACY_C, false);
        let _ = establish(&mut c, true);
        assert_eq!(c.state(), State::ReadOnly);
        add(&mut c, "a", 1).unwrap();
        assert_eq!(send(&mut c), schemes(&["a"]));
        let _ = c.teardown(Cause::RwServerFound, 0, Millis(0));
        let evs = run(&mut c);
        let auths: Vec<&Ev> = evs.iter().filter(|e| matches!(e, Ev::Auth(..))).collect();
        assert_eq!(auths, vec![&Ev::Auth(vec![1], rc::ZCONNECTIONLOSS, true)]);
        let _ = establish(&mut c, false);
        assert_eq!(c.state(), State::Connected);
        assert_eq!(send(&mut c), schemes(&["a"]));
        assert_eq!(reply(&mut c, 0), vec![Ev::Lockpoint, ok("a")]);
    }

    #[test]
    fn sasl_holds_auth_packets_until_finalize() {
        let mut c = conn(Compat::LEGACY_C, true);
        connect(&mut c);
        // Under SASL, credential transmission is deferred until post-handshake finalization.
        add(&mut c, "a", 1).unwrap();
        let _ = respond(&mut c, false);
        assert!(matches!(c.core.phase, Phase::Sasl(_)));
        add(&mut c, "b", 2).unwrap();
        assert!(c.requests.tx.is_empty());
        // The SASL negotiation phase suppresses premature authentication packet transmission.
        c.queue_auth_resend();
        assert!(send(&mut c).is_empty());
        assert!(c.auth.in_flight.is_empty());
        c.requests
            .tx
            .retain(|f| !matches!(f.kind, FrameKind::Auth { .. }));
        // Negotiation completion triggers finalization, flushing queued credentials to the wire.
        let _ = c.start_finalize(Millis(0));
        let _ = run(&mut c);
        assert_eq!(send(&mut c), schemes(&["b", "a"]));
        assert_eq!(reply(&mut c, 0)[2], Ev::Auth(vec![2], 0, false));
        assert_eq!(reply(&mut c, 0)[2], Ev::Auth(vec![1], 0, false));
    }
}
