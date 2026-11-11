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

//! Inbound packet processing pipeline and frame dispatch state machine.
//!
//! This module encapsulates the [`Recv`] subsystem responsible for ingesting
//! raw byte streams from the transport network layer, reconstructing complete
//! protocol wire frames via [`FrameDecoder`], and routing decoded responses
//! to their corresponding handlers based on connection phase and transaction
//! identifiers (`xid`). It manages reply matching against pending in-flight
//! request queues, watch trigger notifications, heartbeat responses, SASL
//! authentication handshakes, chroot path rewriting, and completion generation.

use core::ops::Range;

use zeroize::Zeroize;
use zk_jute::data::Stat;
use zk_jute::multi::{self, MultiBody, MultiKind};
use zk_jute::proto::{
    Create2Response, CreateResponse, GetACLResponse, GetAllChildrenNumberResponse,
    GetChildren2Response, GetChildrenResponse, GetDataResponse, GetEphemeralsResponse, ReplyHeader,
    SetDataResponse, WhoAmIResponse,
};
use zk_jute::rc::{ZINVALIDSTATE, ZMARSHALLINGERROR, ZNOTHING, ZOK, ZRUNTIMEINCONSISTENCY};
use zk_jute::{FrameDecoder, FrameError, JString, JVec, Pushed, Reader, Record, opcode, xid};

use crate::action::ReadOutcome;
use crate::compat::{ChrootPolicy, CloseMode, NegativeLen};
use crate::connection::Connection;
use crate::errno::EHOSTDOWN;
use crate::hooks::Tokens;
use crate::legacy::chroot::{self, Stripped};
use crate::log::{LogArg, LogRecord, LogSite, str_arg};
use crate::output::{OpResult, Origin, Output, Reply, Work};
use crate::request::Route;
use crate::requests::{Pending, Shape};
use crate::responses::{LARGE, Spares};
use crate::state::{Cause, Phase};
use crate::time::{self, Millis};

/// Directives returned by frame dispatch handlers to govern the receive loop.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Flow {
    /// Frame processing completed successfully; processing should continue
    /// with subsequent bytes or frames currently buffered.
    Continue,
    /// Frame processing yielded an operational pause (returning `ZOK`), such as
    /// when parking on an output barrier or awaiting SASL mechanism execution.
    Stop,
    /// Terminal receive condition encountered, halting the loop with a definitive
    /// ZooKeeper return code (e.g., protocol error, state violation, or auth failure).
    Done(i32),
}

/// Inbound frame decoding state and buffer pooling structures.
pub(crate) struct Recv {
    /// Incremental length-delimited wire frame decoder bound by configured maximum frame sizes.
    pub(crate) decoder: FrameDecoder,
    /// Flag indicating the decoder is stalled after encountering an invalid negative frame length
    /// under `NegativeLen::StallUntilTimeout` compatibility semantics until transport reconnect.
    pub(crate) stalled: bool,
    /// Spare buffer allocation cache to facilitate zero-copy handoffs for oversized `GetData`
    /// payloads.
    pub(crate) spares: Spares,
}

impl Recv {
    /// Initializes receive pipeline state configured with an optional maximum frame length
    /// threshold.
    pub(crate) fn new(max_frame_len: Option<u32>) -> Self {
        Self {
            decoder: FrameDecoder::new(max_frame_len),
            stalled: false,
            spares: Spares::default(),
        }
    }

    /// Clears any buffered frame fragments and resets decoder state during connection teardown.
    pub(crate) fn reset(&mut self) {
        self.decoder.reset();
        self.stalled = false;
        self.spares.reset();
    }
}

/// Intermediate status emitted during incremental frame consumption in `handle_read`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Took {
    /// Indicates whether a complete wire frame was successfully assembled and dispatched.
    frame: bool,
    /// Indicates whether byte consumption and decoding should proceed with remaining buffer slices.
    next: bool,
    /// Optional return code signaling an error or termination condition requiring loop termination.
    rc: Option<i32>,
}

impl Took {
    /// Read loop control state indicating that the frame decoder requires additional wire bytes.
    const STOP: Self = Self {
        frame: false,
        next: false,
        rc: None,
    };
}

/// High-level dispatch routing category determined by the active connection lifecycle phase.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum FrameRoute {
    /// Initial session establishment frame expecting a `ConnectResponse`.
    Connect,
    /// SASL negotiation packet routed directly to authentication mechanisms without standard
    /// header checks.
    Sasl,
    /// Standard protocol response frame prefixed with a 16-byte `ReplyHeader`.
    Header,
}

/// Resolves the appropriate frame dispatch route based on connection phase and handshake state.
fn frame_route(phase: Phase, handshake_pending: bool) -> FrameRoute {
    match phase {
        Phase::Associating if handshake_pending => FrameRoute::Connect,
        Phase::Sasl(_) => FrameRoute::Sasl,
        _ => FrameRoute::Header,
    }
}

/// Sub-routing classification for standard protocol frames based on transaction identifier (`xid`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum XidRoute {
    /// Heartbeat ping acknowledgment packet (`xid::PING`).
    Ping,
    /// Unsolicited server watch state change event notification (`xid::WATCHER_EVENT`).
    Watcher,
    /// Watch re-registration batch response following session reconnection (`xid::SET_WATCHES`).
    SetWatches,
    /// Authentication credential submission response (`xid::AUTH`).
    Auth,
    /// Standard response corresponding to an in-flight operation tracked in the pending FIFO queue.
    Reply,
}

/// Evaluates a transaction identifier to distinguish special protocol signals from request replies.
fn xid_route(x: i32) -> XidRoute {
    match x {
        xid::PING => XidRoute::Ping,
        xid::WATCHER_EVENT => XidRoute::Watcher,
        xid::SET_WATCHES => XidRoute::SetWatches,
        xid::AUTH => XidRoute::Auth,
        _ => XidRoute::Reply,
    }
}

/// Corrective action to execute upon encountering an invalid frame length header.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum LenAction {
    /// Cease reading on the current connection until transport reconnection occurs.
    Stall,
    /// Initiate immediate connection teardown with the specified cause classification and system
    /// error code.
    Teardown(Cause, i32),
}

/// Determines the recovery or teardown policy when an incoming frame violates length constraints.
///
/// Handles negative length headers in accordance with configured compatibility policies,
/// treats zero-length frame read attempts as socket EOF events matching C client behavior
/// (`EHOSTDOWN`), and treats oversized frames exceeding limits as protocol errors.
fn len_action(e: FrameError, policy: NegativeLen) -> LenAction {
    match (e, policy) {
        (FrameError::Negative(_), NegativeLen::StallUntilTimeout) => LenAction::Stall,
        (FrameError::Negative(_), NegativeLen::Disconnect) | (FrameError::TooLarge(_), _) => {
            LenAction::Teardown(Cause::ProtocolError, 0)
        }
        (FrameError::Empty, _) => LenAction::Teardown(Cause::Eof, EHOSTDOWN),
    }
}

/// Correlation classification matching an incoming response against the pending request FIFO
/// queue head.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HeadMatch {
    /// Incoming response corresponds to an outstanding `CLOSE_OP` request during draining shutdown.
    CloseReply,
    /// Pending queue was drained during shutdown; inbound response is discarded without error.
    ClosingDrop,
    /// Inbound response arrived while the pending FIFO queue was unexpectedly empty.
    NoHead,
    /// Leading request in the FIFO has not been flushed to the wire or carries a conflicting `xid`.
    Mismatch,
    /// Incoming response transaction identifier matches the in-flight request at the head of the
    /// FIFO.
    Hit,
}

/// Specific failure modes encountered during deserialization of response payload bodies.
#[derive(Debug)]
enum DecodeFail {
    /// Payload violates Jute encoding specifications or structural constraints, requiring teardown.
    Malformed,
    /// Decoded node path breaches chroot boundaries under strict `ChrootPolicy::Component`
    /// validation, failing the operation with `ZMARSHALLINGERROR` accompanied by detailed log
    /// records.
    Outside(Vec<LogRecord>),
}

/// Container holding a successfully deserialized response payload alongside chroot stripping
/// audit logs.
#[derive(Debug)]
struct Decoded {
    /// Unmarshaled response payload with server side chroot prefixes stripped from contained
    /// znode paths.
    reply: Reply,
    /// Aggregate return code for transaction batches (`multi`), or `ZOK` for discrete operations.
    rc: i32,
    /// Log records emitted during chroot path prefix stripping for top-level response paths.
    head_logs: Vec<LogRecord>,
    /// Per-sub-operation log records emitted during chroot path stripping in multi-operation
    /// transactions.
    item_logs: Vec<Vec<LogRecord>>,
}

/// Maps a transaction sub-operation opcode to its corresponding legacy C client completion log site
/// (`zookeeper.c:3298-3336`). Node creation operations map to string completions, `setData` maps
/// to stat completions, while `delete` and `check` map to void completions.
fn sub_op_site(op_type: i32) -> Option<LogSite> {
    match op_type {
        opcode::CREATE | opcode::CREATE2 | opcode::CREATE_CONTAINER | opcode::CREATE_TTL => {
            Some(LogSite::CompletionString)
        }
        opcode::SET_DATA => Some(LogSite::CompletionStat),
        opcode::DELETE | opcode::CHECK => Some(LogSite::CompletionVoid),
        _ => None,
    }
}

/// Deserializes a typed Jute record from a binary reader, translating decode errors into
/// `DecodeFail::Malformed`.
fn read_record<R: Record>(r: &mut Reader<'_>) -> Result<R, DecodeFail> {
    R::decode(r).map_err(|_| DecodeFail::Malformed)
}

/// Inspects and extracts the slice boundary range of a binary payload from a `GetDataResponse`
/// without allocating an intermediate copy. Returns `Ok(None)` when encountering a null data
/// buffer.
fn read_data_range(r: &mut Reader<'_>) -> Result<Option<Range<usize>>, DecodeFail> {
    let len = r.read_i32().map_err(|_| DecodeFail::Malformed)?;
    if len == -1 {
        return Ok(None);
    }
    let n = usize::try_from(len).map_err(|_| DecodeFail::Malformed)?;
    let start = r.position();
    r.read_bytes(n).map_err(|_| DecodeFail::Malformed)?;
    Ok(Some(start..start.saturating_add(n)))
}

/// Assembles a standard three-element 64-bit integer argument vector for formatting completion
/// log lines.
fn int_args(a: i64, b: i64, c: i64) -> Vec<LogArg> {
    vec![LogArg::I64(a), LogArg::I64(b), LogArg::I64(c)]
}

impl<T: Tokens> Connection<T> {
    /// Computes the exact byte count required to finalize the next frame component.
    ///
    /// Driven single threaded network event loops consult this value to perform
    /// bounded reads matching the C client's single-frame-per-iteration behavior.
    /// Returns 0 if the frame decoder is currently stalled.
    #[must_use]
    pub fn read_hint(&self) -> usize {
        self.recv.decoder.want()
    }

    /// Ingests an incoming slice of transport bytes into the frame decoder.
    ///
    /// Behavioral guarantees:
    /// - Returns `rc = ZINVALIDSTATE` immediately without consuming bytes if no
    ///   active transport connection exists or the output pipeline is suspended.
    /// - Pushes incoming bytes into the incremental decoder. Each successfully
    ///   reconstructed frame updates heartbeat timers via `timers.on_recv(now)`
    ///   and dispatches through `dispatch_body`.
    /// - Halts frame consumption when encountering `Flow::Stop`, terminal `Flow::Done`,
    ///   a direct synchronous reply, or when awaiting an asynchronous SASL mechanism.
    ///   Unconsumed trailing bytes remain with the caller, accurately reported in `consumed`.
    /// - Discrepancies in frame length enforce configured compatibility rules: stalling
    ///   read interest until timeout under `NegativeLen::StallUntilTimeout`, or triggering
    ///   immediate connection teardown under `Cause::ProtocolError`. Socket EOF encounters
    ///   trigger teardown with `ZCONNECTIONLOSS`.
    /// - Returns `ZOK` if at least one complete frame was processed, `ZNOTHING` if
    ///   insufficient bytes were available to complete a frame, or the specific terminal `rc`.
    pub fn handle_read(&mut self, bytes: &[u8], now: Millis) -> ReadOutcome {
        if !self.core.has_transport || self.out.is_suspended() {
            return ReadOutcome {
                frames: 0,
                consumed: 0,
                rc: ZINVALIDSTATE,
            };
        }
        let mut frames = 0usize;
        let mut consumed = 0usize;
        let mut stop_rc = None;
        loop {
            if self.recv.stalled || self.recv_waits_for_mechanism() {
                break;
            }
            let rest = bytes.get(consumed..).unwrap_or_default();
            let (n, pushed) = self.recv.decoder.push(rest);
            consumed = consumed.saturating_add(n);
            let took = self.take_pushed(pushed, now);
            if took.frame {
                frames = frames.saturating_add(1);
            }
            stop_rc = took.rc;
            if !took.next {
                break;
            }
        }
        let rc = stop_rc.unwrap_or(if frames > 0 { ZOK } else { ZNOTHING });
        ReadOutcome {
            frames,
            consumed,
            rc,
        }
    }

    /// Exposes a direct writable buffer slice into the frame payload currently being decoded.
    ///
    /// Allows zero-copy socket reads directly into the internal receive buffer, capped
    /// at the smaller of `max`, 64 KiB, or the remaining byte deficit of the active frame body.
    ///
    /// Returns an empty slice if read operations are currently inhibited (e.g., absent transport,
    /// suspended output stage, stalled decoder, or pending SASL mechanism), or while receiving
    /// frame length headers. In those scenarios, drivers read into a staging buffer and route via
    /// `handle_read`.
    pub fn body_room(&mut self, max: usize) -> &mut [u8] {
        if !self.core.has_transport
            || self.out.is_suspended()
            || self.recv.stalled
            || self.recv_waits_for_mechanism()
        {
            return Default::default();
        }
        self.recv.decoder.body_room(max)
    }

    /// Commits `n` bytes written directly into the buffer slice retrieved from
    /// [`Connection::body_room`].
    ///
    /// Adheres to the operational contract of `handle_read`. Because the memory region exposed
    /// terminates precisely at the frame boundary, a single invocation completes at most one frame.
    pub fn handle_body_read(&mut self, n: usize, now: Millis) -> ReadOutcome {
        if !self.core.has_transport || self.out.is_suspended() {
            return ReadOutcome {
                frames: 0,
                consumed: 0,
                rc: ZINVALIDSTATE,
            };
        }
        let pushed = self.recv.decoder.commit(n);
        let took = self.take_pushed(pushed, now);
        let frames = usize::from(took.frame);
        let rc = took.rc.unwrap_or(if took.frame { ZOK } else { ZNOTHING });
        ReadOutcome {
            frames,
            consumed: n,
            rc,
        }
    }

    /// Evaluates the outcome of a frame decoder push or commit operation.
    ///
    /// Dispatches fully assembled frame buffers to `dispatch_body` before recycling the
    /// underlying buffer back into the decoder pool. Translates frame length errors into
    /// either decoder stalling or connection teardown based on compatibility configuration.
    fn take_pushed(&mut self, pushed: Pushed, now: Millis) -> Took {
        match pushed {
            Pushed::NeedMore => Took::STOP,
            Pushed::Frame(mut body) => {
                self.timers.on_recv(now);
                let small = body.len() < LARGE;
                let flow = self.dispatch_body(&mut body, now);
                // Authentication and connection handshakes zeroize payloads within dispatch_body.
                // Substantial GetData replies swap the buffer into a spare allocation pool,
                // freeing memory for immediate reuse by subsequent frame reads.
                // Teardown drops stale transport buffers without recycling.
                if self.core.has_transport {
                    self.recv.decoder.recycle(body);
                }
                if small {
                    self.recv.spares.release();
                }
                let (next, rc) = match flow {
                    Flow::Continue => (!self.out.is_suspended() && self.core.has_transport, None),
                    Flow::Stop => (false, None),
                    Flow::Done(rc) => (false, Some(rc)),
                };
                Took {
                    frame: true,
                    next,
                    rc,
                }
            }
            Pushed::Error(e) => {
                let rc = match len_action(e, self.compat.negative_frame_len) {
                    LenAction::Stall => {
                        self.recv.decoder.stall();
                        self.recv.stalled = true;
                        None
                    }
                    LenAction::Teardown(cause, errno) => Some(self.teardown(cause, errno, now)),
                };
                Took {
                    frame: false,
                    next: false,
                    rc,
                }
            }
        }
    }

    /// Routes an assembled wire frame to its specialized protocol handler.
    ///
    /// Behavioral flow:
    /// - During association with a pending handshake: delegates to `on_connect_response`.
    /// - During SASL negotiation: delegates to `on_sasl_reply` bypassing standard header decoding.
    /// - Standard protocol mode: peeks the 16-byte `ReplyHeader`. Truncated frames trigger teardown
    ///   under `Cause::ProtocolError`.
    /// - `xid == -2`: records ping latency telemetry logs and discards the packet.
    /// - `xid == -1`: routes to `on_watcher_event` to trigger client side watch notifications.
    /// - `xid == -8`: emits set-watches logging and discards the acknowledgment.
    /// - `xid == -4`: delegates to `on_auth_reply` to evaluate authentication response status.
    /// - All positive transaction identifiers: delegates to `dispatch_reply` for request matching.
    ///
    /// Sensitive cryptographic material (session passwords and SASL security tokens) is scrubbed
    /// and zeroized prior to buffer deallocation or recycling.
    fn dispatch_body(&mut self, body: &mut Vec<u8>, now: Millis) -> Flow {
        match frame_route(self.core.phase, self.session.handshake_pending) {
            FrameRoute::Connect => {
                let flow = self.on_connect_response(body, now);
                body.zeroize();
                return flow;
            }
            FrameRoute::Sasl => {
                let flow = self.on_sasl_reply(body, now);
                body.zeroize();
                return flow;
            }
            FrameRoute::Header => {}
        }
        let Ok((hdr, rest)) = ReplyHeader::peek(body) else {
            return Flow::Done(self.teardown(Cause::ProtocolError, 0, now));
        };
        let at = body.len().saturating_sub(rest.len());
        match xid_route(hdr.xid) {
            XidRoute::Ping => {
                let ms = time::elapsed(now, self.timers.last_ping);
                self.emit_log(LogSite::PingResponse, || vec![LogArg::I64(ms)]);
                Flow::Continue
            }
            XidRoute::Watcher => self.on_watcher_event(hdr.zxid, rest, now),
            XidRoute::SetWatches => {
                self.emit_log(LogSite::ProcessingSetWatches, Vec::new);
                Flow::Continue
            }
            XidRoute::Auth => {
                self.emit_log(LogSite::ProcessingAuth, Vec::new);
                self.on_auth_reply(hdr.err, now)
            }
            XidRoute::Reply => self.dispatch_reply(hdr, body, at, now),
        }
    }

    /// Correlates an incoming response frame against the head of the in-flight request FIFO queue.
    ///
    /// Protocol invariants and dispatch sequence:
    /// - Empty queue or unwritten head: during graceful `CloseMode::Drain` shutdown, matching
    ///   `CLOSE_OP` replies route to `on_close_reply`. Other replies received during shutdown
    ///   are logged, discarded, and yield `Done(ZINVALIDSTATE)`. In active states, unexpected
    ///   replies cause immediate teardown under `Cause::XidMismatch`.
    /// - Transaction identifier mismatches initiate connection teardown under `Cause::XidMismatch`
    ///   while leaving the unmatched head in the queue for post-teardown failure reporting.
    /// - Successful matching dequeues the pending entry. Strictly positive server zxids advance
    ///   `core.last_zxid` (even for error replies). Active watch registrations are armed or
    ///   unwatch requests committed upon `ZOK` before completing the operation.
    /// - Response bodies deserialize according to the expected request [`Shape`]. Error replies
    ///   omit bodies, with the exception of synchronous direct multi-transactions containing
    ///   per-operation error records. Deserialization failures reinstate the head and trigger
    ///   teardown.
    /// - Generates and queues `Output::Completion` alongside diagnostic log records. Direct
    ///   synchronous completions park the connection on `Work::Hold` and signal `Flow::Stop`.
    pub(crate) fn dispatch_reply(
        &mut self,
        hdr: ReplyHeader,
        body: &mut Vec<u8>,
        at: usize,
        now: Millis,
    ) -> Flow {
        match self.recv_head_match(hdr.xid) {
            HeadMatch::CloseReply => return self.on_close_reply(now),
            HeadMatch::ClosingDrop => {
                self.emit_log(LogSite::ClearedByClose, Vec::new);
                return Flow::Done(ZINVALIDSTATE);
            }
            // Unlike C assertions, protocol violations trigger controlled connection teardown.
            HeadMatch::NoHead => {
                return Flow::Done(self.teardown(Cause::XidMismatch, 0, now));
            }
            HeadMatch::Mismatch => {
                self.emit_log(LogSite::UnexpectedResponse, Vec::new);
                let head_xid = self.requests.fifo.front().map_or(0, |p| p.xid);
                return Flow::Done(self.teardown_xid_mismatch(hdr.xid, head_xid, 0, now));
            }
            HeadMatch::Hit => {}
        }
        let Some(mut pending) = self.requests.pop_head() else {
            return Flow::Done(self.teardown(Cause::XidMismatch, 0, now));
        };
        if hdr.zxid > 0 {
            self.core.last_zxid = hdr.zxid;
        }
        // Deserialization precedes watch arming to avoid leaving lingering active watches
        // if decoding encounters a protocol error leading to teardown.
        let Some((result, delivery_logs)) = self.recv_reply_result(&pending, &hdr, body, at) else {
            self.requests.push_head(pending);
            return Flow::Done(self.teardown(Cause::ProtocolError, 0, now));
        };
        if let Some(reg) = pending.watch.take() {
            self.arm_watch(reg, hdr.err);
        }
        if let Some(u) = pending.unwatch.take() {
            if hdr.err == ZOK {
                self.apply_unwatch(u);
            }
        }
        let route = pending.route;
        // In legacy C clients, async dispatch queuing is logged immediately upon frame ingestion.
        if route == Route::Queued {
            self.emit_log(LogSite::QueueingAsync, Vec::new);
        }
        self.out.push(Output::Completion {
            token: pending.token,
            route,
            result,
            delivery_logs,
        });
        if route == Route::Direct {
            self.out.park(Work::Hold);
            return Flow::Stop;
        }
        Flow::Continue
    }

    /// Deserializes a response payload matching an expected shape and strips chroot prefixes.
    ///
    /// Structural contracts:
    /// - Individual request shapes map to typed response record decoders. Multi-operation batches
    ///   delegate to `zk_jute::multi::decode_multi` parameterizing expected sub-op counts.
    /// - Path fields pass through `strip_path`, appending boundary mismatch warnings to `logs`.
    /// - Strict `ChrootPolicy::Component` policies reject paths escaping the chroot namespace
    ///   with `ZMARSHALLINGERROR`. Ephemeral listing endpoints drop out-of-boundary paths silently.
    ///
    /// # Errors
    ///
    /// Returns negative ZooKeeper return codes on failure: wire framing or Jute decode errors
    /// trigger connection teardown, while chroot prefix violations fail the request with
    /// `ZMARSHALLINGERROR`.
    #[cfg(test)]
    pub(crate) fn decode_reply(
        &self,
        shape: &Shape,
        rest: &[u8],
        logs: &mut Vec<LogRecord>,
    ) -> Result<(Reply, i32), i32> {
        match self.recv_decode_record(shape, rest) {
            Ok(d) => {
                logs.extend(d.head_logs);
                logs.extend(d.item_logs.into_iter().flatten());
                Ok((d.reply, d.rc))
            }
            Err(DecodeFail::Malformed) => {
                Err(Cause::ProtocolError.rc_for(self.compat.disconnect_rc))
            }
            Err(DecodeFail::Outside(lines)) => {
                logs.extend(lines);
                Err(ZMARSHALLINGERROR)
            }
        }
    }

    /// Strips server side chroot prefix paths from response payload znodes according to configured
    /// policy.
    ///
    /// Compatibility semantics:
    /// - Under `ChrootPolicy::LegacyConcatNoEscape`: relies on `legacy::chroot::strip_c` byte
    ///   prefix matching. Mismatches retain the raw server path and record an ERROR log entry
    ///   reporting "server path %s does not include chroot path %s".
    /// - Under `ChrootPolicy::Component`: executes canonical path component verification via
    ///   `legacy::chroot::strip`, returning `None` if the target path falls outside the chroot
    ///   hierarchy.
    pub(crate) fn strip_path(&self, server: &[u8], logs: &mut Vec<LogRecord>) -> Option<JString> {
        let root = self.recv_chroot();
        let stripped = self.recv_strip_rule(root, server);
        if stripped.mismatch {
            let record = self.log.record(LogSite::ChrootMismatch, || {
                vec![str_arg(server), str_arg(root.unwrap_or_default())]
            });
            logs.extend(record);
            if self.compat.chroot == ChrootPolicy::Component {
                return None;
            }
        }
        Some(JString::Bytes(stripped.path.into_owned()))
    }

    /// Formats the legacy C diagnostic log records associated with operation completion delivery.
    ///
    /// Logging formats:
    /// - Asynchronous completion (`Route::Queued`): formats
    ///   "Calling COMPLETION_X for xid=%#x failed=%d rc=%d", where completion type derives from
    ///   `Shape::legacy_kind`. Multi-transactions append sub-operation lines.
    /// - Synchronous completion (`Route::Direct`): formats
    ///   "Processing sync_completion with type=%d xid=%#x rc=%d", similarly appending
    ///   per-operation diagnostic records for multi-transactions.
    /// - Filter levels configured on the active logger suppress records below thresholds.
    pub(crate) fn completion_logs(
        &self,
        pending: &Pending<T>,
        failed: bool,
        rc: i32,
        reply: Option<&Reply>,
    ) -> Vec<LogRecord> {
        self.recv_delivery_logs(pending, failed, rc, reply, Vec::new(), Vec::new())
    }

    /// Synthesizes a terminal failure completion for an orphaned in-flight request aborted by
    /// teardown or close.
    ///
    /// Returns an `OpResult::teardown(rc)` package. Asynchronous (`Queued`) operations include
    /// diagnostic legacy log records ("Calling COMPLETION_X ... failed=1 rc=%d"), whereas direct
    /// synchronous completions omit log records. Associated watch registrations are discarded
    /// without activation.
    pub(crate) fn teardown_completion(&self, pending: Pending<T>, rc: i32) -> Output<T> {
        let delivery_logs = match pending.route {
            Route::Queued => self.completion_logs(&pending, true, rc, None),
            Route::Direct => Vec::new(),
        };
        Output::Completion {
            token: pending.token,
            route: pending.route,
            result: OpResult::teardown(rc),
            delivery_logs,
        }
    }
}

// Internal helper routines supporting receive pipeline decoding, routing, and log generation.
impl<T: Tokens> Connection<T> {
    /// Determines whether the read loop must pause pending SASL authentication mechanism
    /// evaluation.
    ///
    /// Constrained strictly to active `Phase::Sasl` states, ensuring leftover state from previous
    /// connection attempts cannot stall subsequent connection handshakes.
    fn recv_waits_for_mechanism(&self) -> bool {
        matches!(self.core.phase, Phase::Sasl(_)) && self.sasl_awaiting_mechanism()
    }

    /// Correlates an incoming response transaction identifier against the head of the in-flight
    /// request queue.
    fn recv_head_match(&self, reply_xid: i32) -> HeadMatch {
        let head = self.requests.fifo.front();
        match head {
            Some(p) if p.written => {
                if p.xid == reply_xid {
                    HeadMatch::Hit
                } else {
                    HeadMatch::Mismatch
                }
            }
            _ if self.close.closing => {
                if self.compat.close_mode == CloseMode::Drain
                    && self.close.close_xid == Some(reply_xid)
                {
                    HeadMatch::CloseReply
                } else {
                    HeadMatch::ClosingDrop
                }
            }
            Some(_) => HeadMatch::Mismatch,
            None => HeadMatch::NoHead,
        }
    }

    /// Constructs operation result payloads and completion log records for a correlated response
    /// frame.
    ///
    /// Standard error responses omit bodies except for direct synchronous multi-transactions
    /// containing trailing per-operation result bytes (mirroring C client `process_sync_completion`
    /// behavior). Returns `None` if payload deserialization fails, prompting connection teardown.
    fn recv_reply_result(
        &self,
        pending: &Pending<T>,
        hdr: &ReplyHeader,
        body: &mut Vec<u8>,
        at: usize,
    ) -> Option<(OpResult, Vec<LogRecord>)> {
        let failed = hdr.err != ZOK;
        let is_multi = matches!(pending.shape, Shape::Multi { .. });
        let has_rest = body.len() > at;
        let decode = !failed || (is_multi && pending.route == Route::Direct && has_rest);
        let fail = |rc: i32| OpResult {
            rc,
            zxid: hdr.zxid,
            origin: Origin::Reply,
            reply: None,
        };
        if !decode {
            let logs = self.completion_logs(pending, true, hdr.err, None);
            return Some((fail(hdr.err), logs));
        }
        match self.recv_decode_body(&pending.shape, body, at) {
            Ok(d) => {
                let rc = if is_multi { d.rc } else { hdr.err };
                let logs = self.recv_delivery_logs(
                    pending,
                    failed,
                    hdr.err,
                    Some(&d.reply),
                    d.head_logs,
                    d.item_logs,
                );
                let result = OpResult {
                    rc,
                    zxid: hdr.zxid,
                    origin: Origin::Reply,
                    reply: Some(d.reply),
                };
                Some((result, logs))
            }
            Err(DecodeFail::Malformed) => None,
            Err(DecodeFail::Outside(lines)) => {
                let mut logs = self.completion_logs(pending, true, ZMARSHALLINGERROR, None);
                logs.extend(lines);
                Some((fail(ZMARSHALLINGERROR), logs))
            }
        }
    }

    /// Deserializes response body bytes starting at byte offset `at`.
    ///
    /// Employs zero-copy buffer handoffs for substantial `GetData` responses by transferring
    /// ownership of the underlying frame vector to spare buffer pools. Other shapes decode from
    /// borrowed byte slices.
    fn recv_decode_body(
        &self,
        shape: &Shape,
        body: &mut Vec<u8>,
        at: usize,
    ) -> Result<Decoded, DecodeFail> {
        if !matches!(shape, Shape::Data) {
            return self.recv_decode_record(shape, body.get(at..).unwrap_or_default());
        }
        let mut r = Reader::new(body.get(at..).unwrap_or_default());
        let range = read_data_range(&mut r)?;
        let stat: Stat = read_record(&mut r)?;
        let range = range.map(|v| v.start.saturating_add(at)..v.end.saturating_add(at));
        let data = self
            .recv
            .spares
            .data_of(body, range)
            .ok_or(DecodeFail::Malformed)?;
        Ok(Decoded {
            reply: Reply::Data { data, stat },
            rc: ZOK,
            head_logs: Vec::new(),
            item_logs: Vec::new(),
        })
    }

    /// Deserializes typed response records based on expected shape and applies chroot stripping to
    /// paths. For `GetData` operations routed through this path, payload buffers are cloned into
    /// owned storage.
    fn recv_decode_record(&self, shape: &Shape, rest: &[u8]) -> Result<Decoded, DecodeFail> {
        let mut r = Reader::new(rest);
        let mut head_logs = Vec::new();
        let reply = match shape {
            Shape::Void => Reply::Void,
            // Matches C client conventions where all STAT responses share the SetDataResponse
            // wire layout.
            Shape::Stat => Reply::Stat(read_record::<SetDataResponse>(&mut r)?.stat),
            Shape::Data => {
                let v: GetDataResponse = read_record(&mut r)?;
                Reply::Data {
                    data: v.data.into(),
                    stat: v.stat,
                }
            }
            Shape::Path => {
                let v: CreateResponse = read_record(&mut r)?;
                Reply::Path(self.recv_strip_field(v.path, &mut head_logs)?)
            }
            Shape::PathStat => {
                let v: Create2Response = read_record(&mut r)?;
                Reply::PathStat(self.recv_strip_field(v.path, &mut head_logs)?, v.stat)
            }
            Shape::Children => {
                Reply::Children(read_record::<GetChildrenResponse>(&mut r)?.children)
            }
            Shape::Children2 => {
                let v: GetChildren2Response = read_record(&mut r)?;
                Reply::Children2(v.children, v.stat)
            }
            Shape::Acl => {
                let v: GetACLResponse = read_record(&mut r)?;
                Reply::Acl(v.acl, v.stat)
            }
            Shape::Multi { op_types, kind } => {
                return self.recv_decode_multi(&mut r, op_types.len(), *kind);
            }
            Shape::Ephemerals => {
                let v: GetEphemeralsResponse = read_record(&mut r)?;
                Reply::Ephemerals(self.recv_strip_list(v.ephemerals))
            }
            Shape::ChildrenCount => Reply::ChildrenCount(
                read_record::<GetAllChildrenNumberResponse>(&mut r)?.total_number,
            ),
            Shape::WhoAmI => Reply::WhoAmI(read_record::<WhoAmIResponse>(&mut r)?.client_info),
        };
        Ok(Decoded {
            reply,
            rc: ZOK,
            head_logs,
            item_logs: Vec::new(),
        })
    }

    /// Deserializes multi-transaction responses, stripping chroot prefixes and computing the
    /// aggregate return code.
    ///
    /// Under ZooKeeper transaction semantics (`zookeeper.c:3319-3328`), the aggregate transaction
    /// return code is defined by the first encountered sub-operation error other than `ZOK` or
    /// `ZRUNTIMEINCONSISTENCY`. Read-only multi-transactions (`MultiKind::MultiRead`) do not fail
    /// atomically and return `ZOK`.
    fn recv_decode_multi(
        &self,
        r: &mut Reader<'_>,
        expected: usize,
        kind: MultiKind,
    ) -> Result<Decoded, DecodeFail> {
        let mut items =
            multi::decode_multi(r, expected, kind).map_err(|_| DecodeFail::Malformed)?;
        let mut rc = ZOK;
        let mut item_logs = Vec::with_capacity(items.len());
        for item in &mut items {
            let mut logs = Vec::new();
            match &mut item.body {
                MultiBody::Create(c) => {
                    let path = core::mem::replace(&mut c.path, JString::Null);
                    c.path = self.recv_strip_field(path, &mut logs)?;
                }
                MultiBody::Create2(c) => {
                    let path = core::mem::replace(&mut c.path, JString::Null);
                    c.path = self.recv_strip_field(path, &mut logs)?;
                }
                MultiBody::Error(e) => {
                    if rc == ZOK && e.err != ZOK && e.err != ZRUNTIMEINCONSISTENCY {
                        rc = e.err;
                    }
                }
                MultiBody::SetData(_)
                | MultiBody::Empty
                | MultiBody::GetData(_)
                | MultiBody::GetChildren(_) => {}
            }
            item_logs.push(logs);
        }
        let reply = match kind {
            MultiKind::Multi => Reply::Multi(items),
            MultiKind::MultiRead => {
                rc = ZOK;
                Reply::MultiRead(items)
            }
        };
        Ok(Decoded {
            reply,
            rc,
            head_logs: Vec::new(),
            item_logs,
        })
    }

    /// Strips server side chroot prefixes from a single string path field, preserving null paths
    /// unmodified.
    fn recv_strip_field(
        &self,
        path: JString,
        logs: &mut Vec<LogRecord>,
    ) -> Result<JString, DecodeFail> {
        let stripped = path.as_bytes().map(|b| self.strip_path(b, logs));
        match stripped {
            None => Ok(path),
            Some(Some(p)) => Ok(p),
            Some(None) => Err(DecodeFail::Outside(core::mem::take(logs))),
        }
    }

    /// Strips chroot prefixes from a list of ephemeral node paths, silently dropping paths that
    /// escape boundaries.
    fn recv_strip_list(&self, list: JVec<JString>) -> JVec<JString> {
        let root = self.recv_chroot();
        let Some(items) = list.into_vec() else {
            return JVec::Null;
        };
        let kept = items
            .into_iter()
            .filter_map(|p| match p.as_bytes() {
                None => Some(JString::Null),
                Some(b) => {
                    let s = self.recv_strip_rule(root, b);
                    if s.mismatch {
                        None
                    } else {
                        Some(JString::Bytes(s.path.into_owned()))
                    }
                }
            })
            .collect();
        JVec::Items(kept)
    }

    /// Retrieves configured chroot path bytes, treating root `"/"` as the absence of a chroot per
    /// C conventions.
    fn recv_chroot(&self) -> Option<&[u8]> {
        self.cfg
            .chroot
            .as_ref()
            .and_then(JString::as_bytes)
            .filter(|c| c.len() > 1)
    }

    /// Executes path prefix stripping according to the active compatibility profile.
    fn recv_strip_rule<'a>(&self, root: Option<&[u8]>, server: &'a [u8]) -> Stripped<'a> {
        match self.compat.chroot {
            ChrootPolicy::LegacyConcatNoEscape => chroot::strip_c(root, server),
            ChrootPolicy::Component => chroot::strip(root, server),
        }
    }

    /// Generates structured delivery log records for operation completions.
    ///
    /// Assembles the primary completion diagnostic record followed by any path stripping logs.
    /// For multi-operation transactions, appends individual sub-operation status lines interleaved
    /// with their respective chroot transformation audit records.
    fn recv_delivery_logs(
        &self,
        pending: &Pending<T>,
        failed: bool,
        rc: i32,
        reply: Option<&Reply>,
        head_logs: Vec<LogRecord>,
        item_logs: Vec<Vec<LogRecord>>,
    ) -> Vec<LogRecord> {
        let mut logs = Vec::new();
        let x = i64::from(pending.xid);
        if let Some((ctype, site)) = pending.shape.legacy_kind() {
            let record = match pending.route {
                Route::Queued => self
                    .log
                    .record(site, || int_args(x, i64::from(failed), i64::from(rc))),
                Route::Direct => self.log.record(LogSite::SyncCompletion, || {
                    int_args(i64::from(ctype), x, i64::from(rc))
                }),
            };
            logs.extend(record);
        }
        logs.extend(head_logs);
        match (&pending.shape, reply) {
            (
                Shape::Multi {
                    op_types,
                    kind: MultiKind::Multi,
                },
                Some(Reply::Multi(items)),
            ) => {
                let mut extra = item_logs.into_iter();
                for (op, item) in op_types.iter().zip(items) {
                    let item_failed = item.wire_type == opcode::ERROR;
                    let item_rc = match &item.body {
                        MultiBody::Error(e) => e.err,
                        _ => item.err,
                    };
                    if let Some(site) = sub_op_site(*op) {
                        let record = self.log.record(site, || {
                            int_args(x, i64::from(item_failed), i64::from(item_rc))
                        });
                        logs.extend(record);
                    }
                    logs.extend(extra.next().into_iter().flatten());
                }
                logs.extend(extra.flatten());
            }
            (
                Shape::Multi {
                    op_types,
                    kind: MultiKind::Multi,
                },
                _,
            ) => {
                // Mirrors legacy C cleanup_failed_multi behavior (zookeeper.c:3298-3306),
                // emitting synthetic failure logs for each sub-operation when the transaction
                // fails.
                for op in op_types {
                    if let Some(site) = sub_op_site(*op) {
                        let record = self.log.record(site, || int_args(x, 1, i64::from(rc)));
                        logs.extend(record);
                    }
                }
                logs.extend(item_logs.into_iter().flatten());
            }
            _ => logs.extend(item_logs.into_iter().flatten()),
        }
        logs
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::panic)]
mod tests {
    use super::*;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::hooks::{AllLevels, NoDefaultWatcher, Rng};
    use crate::requests::completion;
    use crate::state::SaslNegotiation;
    use zk_jute::data::{ACL, ClientInfo, Stat};
    use zk_jute::proto::{ErrorResponse, MultiHeader};
    use zk_jute::rc::{ZCONNECTIONLOSS, ZNONODE};
    use zk_jute::{JBuffer, encode_frame};

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

    /// Test helper initializing an established connection fixture configured with custom
    /// compatibility settings.
    fn conn_with(compat: Compat, chroot: Option<&str>) -> Connection<Tk> {
        let mut cfg = Config::legacy(30_000);
        cfg.chroot = chroot.map(JString::from);
        let mut c = Connection::new(
            cfg,
            compat,
            DriverMode::ST,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        );
        c.core.has_transport = true;
        c.core.phase = Phase::Established;
        c
    }

    fn conn() -> Connection<Tk> {
        conn_with(Compat::LEGACY_C, None)
    }

    fn component() -> Compat {
        let mut c = Compat::LEGACY_C;
        c.chroot = ChrootPolicy::Component;
        c
    }

    fn pending(xid: i32, _op_type: i32, shape: Shape, route: Route) -> Pending<Tk> {
        Pending {
            xid,
            shape,
            route,
            token: u32::try_from(xid).unwrap_or(0),
            watch: None,
            unwatch: None,
            written: true,
        }
    }

    fn enc<R: Record>(r: &R) -> Vec<u8> {
        let mut v = Vec::new();
        r.encode(&mut v);
        v
    }

    /// Test helper serializing a 4-byte length-delimited protocol reply frame with a standard
    /// header.
    fn reply_frame(x: i32, zxid: i64, err: i32, body: &[u8]) -> Vec<u8> {
        let mut b = enc(&ReplyHeader { xid: x, zxid, err });
        b.extend_from_slice(body);
        let mut f = Vec::new();
        encode_frame(&b, &mut f);
        f
    }

    /// Test helper serializing multi-operation response records terminated with an end-of-multi
    /// header.
    fn multi_body(items: &[(i32, i32, Vec<u8>)]) -> Vec<u8> {
        let mut v = Vec::new();
        for (t, err, body) in items {
            MultiHeader {
                r#type: *t,
                done: false,
                err: *err,
            }
            .encode(&mut v);
            v.extend_from_slice(body);
        }
        MultiHeader {
            r#type: -1,
            done: true,
            err: -1,
        }
        .encode(&mut v);
        v
    }

    fn create(path: &str) -> Vec<u8> {
        enc(&CreateResponse {
            path: JString::from(path),
        })
    }

    fn error(err: i32) -> Vec<u8> {
        enc(&ErrorResponse { err })
    }

    fn stat(czxid: i64) -> Stat {
        Stat {
            czxid,
            ..Stat::default()
        }
    }

    fn drain(c: &mut Connection<Tk>) -> Vec<Output<Tk>> {
        let mut v = Vec::new();
        while let Some(o) = c.poll_output() {
            v.push(o);
        }
        v
    }

    fn sites(logs: &[LogRecord]) -> Vec<LogSite> {
        logs.iter().map(|r| r.site).collect()
    }

    fn ints(r: &LogRecord) -> Vec<i64> {
        r.args
            .iter()
            .filter_map(|a| match a {
                LogArg::I64(v) => Some(*v),
                _ => None,
            })
            .collect()
    }

    /// Test utility extracting the singular completion output and preceding log sites from an
    /// output stream.
    fn completion_of(
        outs: Vec<Output<Tk>>,
    ) -> (u32, Route, OpResult, Vec<LogRecord>, Vec<LogSite>) {
        let mut before = Vec::new();
        for o in outs {
            match o {
                Output::Log(r) => before.push(r.site),
                Output::Completion {
                    token,
                    route,
                    result,
                    delivery_logs,
                } => return (token, route, result, delivery_logs, before),
                other => panic!("unexpected output {other:?}"),
            }
        }
        panic!("no completion")
    }

    #[test]
    fn secret_frame_bodies_are_cleared() {
        // Even when an undersized connect response triggers a protocol error, secret session
        // credentials must be zeroized.
        let mut c = conn();
        c.core.phase = Phase::Associating;
        c.session.handshake_pending = true;
        let mut body = vec![0xAB; 36];
        let _ = c.dispatch_body(&mut body, Millis(1));
        assert!(body.is_empty(), "connect body kept");

        let mut c = conn();
        c.core.phase = Phase::Sasl(SaslNegotiation::default());
        let mut body = vec![0xCD; 24];
        let _ = c.dispatch_body(&mut body, Millis(1));
        assert!(body.is_empty(), "sasl body kept");

        // Non-handshake operational frames do not contain credentials and do not require payload
        // zeroization.
        let mut c = conn();
        let mut body = reply_frame(-2, 0, 0, &[]).split_off(4);
        let _ = c.dispatch_body(&mut body, Millis(1));
        assert_eq!(body.len(), 16);
    }

    #[test]
    fn frame_route_follows_phase() {
        assert_eq!(frame_route(Phase::Associating, true), FrameRoute::Connect);
        assert_eq!(frame_route(Phase::Associating, false), FrameRoute::Header);
        let sasl = Phase::Sasl(SaslNegotiation::default());
        assert_eq!(frame_route(sasl, true), FrameRoute::Sasl);
        assert_eq!(frame_route(sasl, false), FrameRoute::Sasl);
        assert_eq!(frame_route(Phase::Established, true), FrameRoute::Header);
    }

    #[test]
    fn xid_route_covers_every_branch() {
        assert_eq!(xid_route(-2), XidRoute::Ping);
        assert_eq!(xid_route(-1), XidRoute::Watcher);
        assert_eq!(xid_route(-8), XidRoute::SetWatches);
        assert_eq!(xid_route(-4), XidRoute::Auth);
        assert_eq!(xid_route(7), XidRoute::Reply);
        assert_eq!(xid_route(-3), XidRoute::Reply);
    }

    #[test]
    fn no_transport_or_suspended_is_invalid_state() {
        let mut c = conn();
        c.core.has_transport = false;
        let f = reply_frame(-2, 0, 0, &[]);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 0, ZINVALIDSTATE));

        let mut c = conn();
        c.out.park(Work::Hold);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 0, ZINVALIDSTATE));
    }

    #[test]
    fn partial_frame_is_nothing() {
        let mut c = conn();
        let f = reply_frame(-2, 0, 0, &[]);
        let r = c.handle_read(&f[..6], Millis(1));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 6, ZNOTHING));
        assert_eq!(c.read_hint(), f.len() - 6);
        let r = c.handle_read(&f[6..], Millis(1));
        assert_eq!((r.frames, r.rc), (1, ZOK));
    }

    #[test]
    fn ping_logs_latency_and_continues() {
        let mut c = conn();
        c.timers.last_ping = Millis(100);
        let mut bytes = reply_frame(-2, 0, 0, &[]);
        bytes.extend(reply_frame(-2, 0, 0, &[]));
        let r = c.handle_read(&bytes, Millis(130));
        assert_eq!((r.frames, r.consumed, r.rc), (2, bytes.len(), ZOK));
        assert_eq!(c.timers.last_recv, Millis(130));
        let outs = drain(&mut c);
        assert_eq!(outs.len(), 2);
        let Output::Log(rec) = &outs[0] else {
            panic!("not a log")
        };
        assert_eq!(rec.site, LogSite::PingResponse);
        assert_eq!(ints(rec), vec![30]);
    }

    #[test]
    fn set_watches_reply_logs_and_drops() {
        let mut c = conn();
        let f = reply_frame(-8, 0, ZNONODE, &[]);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!((r.frames, r.rc), (1, ZOK));
        let outs = drain(&mut c);
        assert!(matches!(&outs[..], [Output::Log(r)] if r.site == LogSite::ProcessingSetWatches));
    }

    #[test]
    fn queued_reply_completes_and_reading_continues() {
        let mut c = conn();
        c.requests
            .fifo
            .push_back(pending(1, opcode::EXISTS, Shape::Stat, Route::Queued));
        let mut bytes = reply_frame(1, 7, 0, &enc(&SetDataResponse { stat: stat(3) }));
        bytes.extend(reply_frame(-2, 0, 0, &[]));
        let r = c.handle_read(&bytes, Millis(5));
        assert_eq!((r.frames, r.consumed, r.rc), (2, bytes.len(), ZOK));
        assert_eq!(c.last_zxid(), 7);
        assert!(c.requests.fifo.is_empty());
        let outs = drain(&mut c);
        let (token, route, result, logs, before) = completion_of(outs);
        assert_eq!((token, route), (1, Route::Queued));
        assert_eq!(before, vec![LogSite::QueueingAsync]);
        assert_eq!(result.rc, ZOK);
        assert_eq!(result.zxid, 7);
        assert_eq!(result.origin, Origin::Reply);
        assert_eq!(result.reply, Some(Reply::Stat(stat(3))));
        assert_eq!(sites(&logs), vec![LogSite::CompletionStat]);
        assert_eq!(ints(&logs[0]), vec![1, 0, 0]);
    }

    #[test]
    fn error_reply_sets_zxid_and_has_no_reply() {
        let mut c = conn();
        c.core.last_zxid = 4;
        c.requests
            .fifo
            .push_back(pending(1, opcode::GET_DATA, Shape::Data, Route::Queued));
        c.requests
            .fifo
            .push_back(pending(2, opcode::GET_DATA, Shape::Data, Route::Queued));
        let mut bytes = reply_frame(1, 9, ZNONODE, &[]);
        bytes.extend(reply_frame(2, 0, ZNONODE, &[]));
        let r = c.handle_read(&bytes, Millis(5));
        assert_eq!((r.frames, r.rc), (2, ZOK));
        // Server replies with zxid 0 do not advance the highest confirmed transaction
        // identifier.
        assert_eq!(c.last_zxid(), 9);
        let outs: Vec<Output<Tk>> = drain(&mut c)
            .into_iter()
            .filter(|o| !matches!(o, Output::Log(_)))
            .collect();
        assert_eq!(outs.len(), 2);
        for o in outs {
            let Output::Completion {
                result,
                delivery_logs,
                ..
            } = o
            else {
                panic!("not a completion")
            };
            assert_eq!(result.rc, ZNONODE);
            assert_eq!(result.reply, None);
            assert_eq!(sites(&delivery_logs), vec![LogSite::CompletionData]);
            assert_eq!(ints(&delivery_logs[0])[1..], [1, i64::from(ZNONODE)]);
        }
    }

    #[test]
    fn direct_reply_parks_hold_and_stops_the_loop() {
        let mut c = conn();
        c.requests
            .fifo
            .push_back(pending(3, opcode::SET_DATA, Shape::Stat, Route::Direct));
        let first = reply_frame(3, 11, 0, &enc(&SetDataResponse { stat: stat(1) }));
        let mut bytes = first.clone();
        bytes.extend(reply_frame(-2, 0, 0, &[]));
        let r = c.handle_read(&bytes, Millis(5));
        assert_eq!((r.frames, r.consumed, r.rc), (1, first.len(), ZOK));
        assert!(c.is_suspended());
        // When the connection output pipeline is suspended awaiting dispatch acknowledgment,
        // incoming byte reads yield ZINVALIDSTATE.
        let r = c.handle_read(&bytes[first.len()..], Millis(5));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 0, ZINVALIDSTATE));
        let Some(Output::Completion {
            route,
            result,
            delivery_logs,
            ..
        }) = c.poll_output()
        else {
            panic!("no completion")
        };
        assert_eq!(route, Route::Direct);
        assert_eq!(result.reply, Some(Reply::Stat(stat(1))));
        assert_eq!(sites(&delivery_logs), vec![LogSite::SyncCompletion]);
        assert_eq!(
            ints(&delivery_logs[0]),
            vec![i64::from(completion::STAT), 3, 0]
        );
        assert!(c.poll_output().is_none());
        let stopped = c.take_stopped().unwrap();
        c.resume(stopped, Millis(6));
        assert!(!c.is_suspended());
        let r = c.handle_read(&bytes[first.len()..], Millis(6));
        assert_eq!((r.frames, r.rc), (1, ZOK));
    }

    #[test]
    fn head_match_classifies_replies() {
        let mut c = conn();
        assert_eq!(c.recv_head_match(1), HeadMatch::NoHead);
        let mut p = pending(1, opcode::DELETE, Shape::Void, Route::Queued);
        p.written = false;
        c.requests.fifo.push_back(p);
        assert_eq!(c.recv_head_match(1), HeadMatch::Mismatch);
        c.requests.fifo.front_mut().unwrap().written = true;
        assert_eq!(c.recv_head_match(1), HeadMatch::Hit);
        assert_eq!(c.recv_head_match(2), HeadMatch::Mismatch);

        // Terminal close state with either an exhausted request queue or a pending head that
        // was never flushed to the transport.
        let mut c = conn();
        c.close.closing = true;
        c.close.close_xid = Some(9);
        assert_eq!(c.recv_head_match(9), HeadMatch::ClosingDrop);
        let mut d = Compat::LEGACY_C;
        d.close_mode = CloseMode::Drain;
        let mut c = conn_with(d, None);
        c.close.closing = true;
        c.close.close_xid = Some(9);
        assert_eq!(c.recv_head_match(9), HeadMatch::CloseReply);
        assert_eq!(c.recv_head_match(8), HeadMatch::ClosingDrop);
        let mut p = pending(5, opcode::DELETE, Shape::Void, Route::Queued);
        p.written = false;
        c.requests.fifo.push_back(p);
        assert_eq!(c.recv_head_match(9), HeadMatch::CloseReply);
        assert_eq!(c.recv_head_match(5), HeadMatch::ClosingDrop);
    }

    /// Verifies that replies arriving during shutdown when the pending FIFO queue has already
    /// drained are discarded with `ZINVALIDSTATE`.
    #[test]
    fn reply_after_close_cleared_the_fifo_is_dropped() {
        let mut c = conn();
        c.close.closing = true;
        let f = reply_frame(4, 3, 0, &[]);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!((r.frames, r.consumed, r.rc), (1, f.len(), ZINVALIDSTATE));
        assert_eq!(c.last_zxid(), 0);
        let outs = drain(&mut c);
        assert!(matches!(&outs[..], [Output::Log(r)] if r.site == LogSite::ClearedByClose));

        // If the head request in the pending queue has not been written to the socket during
        // close, unsolicited replies are dropped.
        let mut c = conn();
        c.close.closing = true;
        let mut p = pending(4, opcode::DELETE, Shape::Void, Route::Queued);
        p.written = false;
        c.requests.fifo.push_back(p);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!(r.rc, ZINVALIDSTATE);
        assert_eq!(c.requests.fifo.len(), 1);
    }

    #[test]
    fn length_errors_follow_the_profile() {
        let stall = NegativeLen::StallUntilTimeout;
        let cut = NegativeLen::Disconnect;
        assert_eq!(
            len_action(FrameError::Negative(-1), stall),
            LenAction::Stall
        );
        assert_eq!(
            len_action(FrameError::Negative(-1), cut),
            LenAction::Teardown(Cause::ProtocolError, 0)
        );
        for p in [stall, cut] {
            assert_eq!(
                len_action(FrameError::Empty, p),
                LenAction::Teardown(Cause::Eof, EHOSTDOWN)
            );
            assert_eq!(
                len_action(FrameError::TooLarge(9), p),
                LenAction::Teardown(Cause::ProtocolError, 0)
            );
        }
        assert_eq!(
            Cause::Eof.rc_for(Compat::LEGACY_C.disconnect_rc),
            ZCONNECTIONLOSS
        );
    }

    /// Validates that encountering a negative frame length stalls the decoder under
    /// `StallUntilTimeout` policy semantics.
    #[test]
    fn negative_length_stalls_the_decoder() {
        let mut c = conn();
        let mut bytes = (-5i32).to_be_bytes().to_vec();
        bytes.extend(reply_frame(-2, 0, 0, &[]));
        let r = c.handle_read(&bytes, Millis(1));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 4, ZNOTHING));
        assert!(c.recv.stalled);
        assert!(c.recv.decoder.is_stalled());
        assert_eq!(c.read_hint(), 0);
        let r = c.handle_read(&bytes[4..], Millis(2));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 0, ZNOTHING));
        c.recv.reset();
        assert!(!c.recv.stalled);
        assert_eq!(c.read_hint(), 4);
    }

    #[test]
    fn sasl_mechanism_work_stops_reading() {
        let mut c = conn();
        c.core.phase = Phase::Sasl(SaslNegotiation::default());
        c.sasl.outstanding = true;
        let f = reply_frame(-2, 0, 0, &[]);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!((r.frames, r.consumed, r.rc), (0, 0, ZNOTHING));
        // Stale mechanism work flags cannot inhibit frame reception once the connection
        // transitions to established phase.
        c.core.phase = Phase::Established;
        let r = c.handle_read(&f, Millis(1));
        assert_eq!((r.frames, r.rc), (1, ZOK));
    }

    /// Verifies legacy chroot path prefix stripping matching C client `sub_string`
    /// byte-comparison semantics.
    #[test]
    fn strip_path_legacy_rules() {
        let c = conn_with(Compat::LEGACY_C, Some("/app"));
        let mut logs = Vec::new();
        assert_eq!(
            c.strip_path(b"/app/x", &mut logs),
            Some(JString::from("/x"))
        );
        assert_eq!(c.strip_path(b"/app", &mut logs), Some(JString::from("/")));
        // C client performs raw byte substring slicing without verifying slash component
        // boundaries.
        assert_eq!(
            c.strip_path(b"/apple", &mut logs),
            Some(JString::from("le"))
        );
        assert!(logs.is_empty());
        assert_eq!(
            c.strip_path(b"/other", &mut logs),
            Some(JString::from("/other"))
        );
        assert_eq!(sites(&logs), vec![LogSite::ChrootMismatch]);
        assert_eq!(
            logs[0].args,
            vec![
                LogArg::Str(JString::from("/other")),
                LogArg::Str(JString::from("/app"))
            ]
        );
        // Configured chroot root path "/" is treated as an empty chroot per C client
        // conventions.
        let c = conn_with(Compat::LEGACY_C, Some("/"));
        let mut logs = Vec::new();
        assert_eq!(c.strip_path(b"/a", &mut logs), Some(JString::from("/a")));
        let c = conn();
        assert_eq!(c.strip_path(b"/a", &mut logs), Some(JString::from("/a")));
        assert!(logs.is_empty());
    }

    #[test]
    fn strip_path_component_rules() {
        let c = conn_with(component(), Some("/app"));
        let mut logs = Vec::new();
        assert_eq!(
            c.strip_path(b"/app/x", &mut logs),
            Some(JString::from("/x"))
        );
        assert_eq!(c.strip_path(b"/app", &mut logs), Some(JString::from("/")));
        assert!(logs.is_empty());
        assert_eq!(c.strip_path(b"/apple", &mut logs), None);
        assert_eq!(c.strip_path(b"/other", &mut logs), None);
        assert_eq!(
            sites(&logs),
            vec![LogSite::ChrootMismatch, LogSite::ChrootMismatch]
        );
    }

    /// Verifies that all protocol response shapes correctly unmarshal into typed `Reply`
    /// variants with chroot prefixes stripped.
    #[test]
    fn decode_reply_each_shape() {
        let c = conn_with(Compat::LEGACY_C, Some("/app"));
        let mut logs = Vec::new();
        let ok = |shape: Shape, body: Vec<u8>, logs: &mut Vec<LogRecord>| {
            c.decode_reply(&shape, &body, logs).unwrap()
        };
        assert_eq!(ok(Shape::Void, vec![], &mut logs), (Reply::Void, ZOK));
        assert_eq!(
            ok(
                Shape::Stat,
                enc(&SetDataResponse { stat: stat(2) }),
                &mut logs
            ),
            (Reply::Stat(stat(2)), ZOK)
        );
        let data = GetDataResponse {
            data: JBuffer::Bytes(b"v".to_vec()),
            stat: stat(3),
        };
        assert_eq!(
            ok(Shape::Data, enc(&data), &mut logs).0,
            Reply::Data {
                data: JBuffer::Bytes(b"v".to_vec()).into(),
                stat: stat(3)
            }
        );
        assert_eq!(
            ok(Shape::Path, create("/app/n"), &mut logs).0,
            Reply::Path(JString::from("/n"))
        );
        let c2 = Create2Response {
            path: JString::from("/app/m"),
            stat: stat(4),
        };
        assert_eq!(
            ok(Shape::PathStat, enc(&c2), &mut logs).0,
            Reply::PathStat(JString::from("/m"), stat(4))
        );
        let kids: JVec<JString> = JVec::from(vec![JString::from("a"), JString::from("b")]);
        assert_eq!(
            ok(
                Shape::Children,
                enc(&GetChildrenResponse {
                    children: kids.clone()
                }),
                &mut logs
            )
            .0,
            Reply::Children(kids.clone())
        );
        let k2 = GetChildren2Response {
            children: kids.clone(),
            stat: stat(5),
        };
        assert_eq!(
            ok(Shape::Children2, enc(&k2), &mut logs).0,
            Reply::Children2(kids, stat(5))
        );
        let acl = GetACLResponse {
            acl: JVec::from(vec![ACL::default()]),
            stat: stat(6),
        };
        assert_eq!(
            ok(Shape::Acl, enc(&acl), &mut logs).0,
            Reply::Acl(JVec::from(vec![ACL::default()]), stat(6))
        );
        let eph = GetEphemeralsResponse {
            ephemerals: JVec::from(vec![JString::from("/app/e"), JString::from("/zz")]),
        };
        assert_eq!(
            ok(Shape::Ephemerals, enc(&eph), &mut logs).0,
            Reply::Ephemerals(JVec::from(vec![JString::from("/e")]))
        );
        let n = GetAllChildrenNumberResponse { total_number: 12 };
        assert_eq!(
            ok(Shape::ChildrenCount, enc(&n), &mut logs).0,
            Reply::ChildrenCount(12)
        );
        let who = WhoAmIResponse {
            client_info: JVec::from(vec![ClientInfo::default()]),
        };
        assert_eq!(
            ok(Shape::WhoAmI, enc(&who), &mut logs).0,
            Reply::WhoAmI(JVec::from(vec![ClientInfo::default()]))
        );
        // Ephemeral node listings silently omit paths residing outside the chroot without
        // generating diagnostic warnings.
        assert!(logs.is_empty());

        // Paths violating chroot boundaries generate audit log records under legacy
        // compatibility rules.
        let _ = ok(Shape::Path, create("/x"), &mut logs);
        assert_eq!(sites(&logs), vec![LogSite::ChrootMismatch]);

        // Syntactically truncated or corrupted response payloads trigger connection teardown.
        let mut logs = Vec::new();
        assert_eq!(
            c.decode_reply(&Shape::Stat, &[0; 3], &mut logs),
            Err(ZCONNECTIONLOSS)
        );
    }

    #[test]
    fn decode_reply_component_outside_fails_the_request() {
        let c = conn_with(component(), Some("/app"));
        let mut logs = Vec::new();
        assert_eq!(
            c.decode_reply(&Shape::Path, &create("/apple"), &mut logs),
            Err(ZMARSHALLINGERROR)
        );
        assert_eq!(sites(&logs), vec![LogSite::ChrootMismatch]);
        let eph = GetEphemeralsResponse {
            ephemerals: JVec::from(vec![JString::from("/apple"), JString::from("/app/b")]),
        };
        let mut logs = Vec::new();
        assert_eq!(
            c.decode_reply(&Shape::Ephemerals, &enc(&eph), &mut logs),
            Ok((
                Reply::Ephemerals(JVec::from(vec![JString::from("/b")])),
                ZOK
            ))
        );
        assert!(logs.is_empty());
    }

    fn multi_shape(ops: &[i32]) -> Shape {
        Shape::Multi {
            op_types: ops.to_vec(),
            kind: MultiKind::Multi,
        }
    }

    #[test]
    fn decode_reply_multi_rc() {
        let c = conn_with(Compat::LEGACY_C, Some("/app"));
        let mut logs = Vec::new();
        // The aggregate transaction return code reflects the earliest non-zero,
        // non-runtime-inconsistency failure.
        let body = multi_body(&[
            (-1, -1, error(ZRUNTIMEINCONSISTENCY)),
            (-1, -1, error(ZNONODE)),
            (-1, -1, error(-110)),
        ]);
        let (reply, rc) = c
            .decode_reply(&multi_shape(&[1, 2, 5]), &body, &mut logs)
            .unwrap();
        assert_eq!(rc, ZNONODE);
        assert!(matches!(reply, Reply::Multi(ref v) if v.len() == 3));

        let body = multi_body(&[(-1, -1, error(0)), (-1, -1, error(ZRUNTIMEINCONSISTENCY))]);
        let (_, rc) = c
            .decode_reply(&multi_shape(&[2, 13]), &body, &mut logs)
            .unwrap();
        assert_eq!(rc, ZOK);

        // Node creation responses within a transaction have server side chroot prefixes
        // stripped.
        let body = multi_body(&[(1, 0, create("/app/a")), (2, 0, vec![])]);
        let (reply, rc) = c
            .decode_reply(&multi_shape(&[1, 2]), &body, &mut logs)
            .unwrap();
        assert_eq!(rc, ZOK);
        let Reply::Multi(items) = reply else {
            panic!("not a multi")
        };
        assert_eq!(
            items[0].body,
            MultiBody::Create(CreateResponse {
                path: JString::from("/a")
            })
        );
        assert!(logs.is_empty());

        // Read-only multi-operation transactions return ZOK overall while preserving per-sub-op
        // responses.
        let read = Shape::Multi {
            op_types: vec![4],
            kind: MultiKind::MultiRead,
        };
        let body = multi_body(&[(-1, -1, error(ZNONODE))]);
        let (reply, rc) = c.decode_reply(&read, &body, &mut logs).unwrap();
        assert_eq!(rc, ZOK);
        assert!(matches!(reply, Reply::MultiRead(_)));

        // Multi-operation payloads with sub-operation counts conflicting with headers trigger
        // connection teardown.
        let body = multi_body(&[(2, 0, vec![])]);
        assert_eq!(
            c.decode_reply(&multi_shape(&[2, 2]), &body, &mut logs),
            Err(ZCONNECTIONLOSS)
        );
    }

    #[test]
    fn queued_multi_lines_interleave_strip_errors() {
        let mut c = conn_with(Compat::LEGACY_C, Some("/app"));
        c.requests.fifo.push_back(pending(
            6,
            opcode::MULTI,
            multi_shape(&[1, 2, 5]),
            Route::Queued,
        ));
        let body = multi_body(&[
            (1, 0, create("/x")),
            (2, 0, vec![]),
            (5, 0, enc(&SetDataResponse { stat: stat(1) })),
        ]);
        let f = reply_frame(6, 20, 0, &body);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!(r.rc, ZOK);
        let (_, _, result, logs, _) = completion_of(drain(&mut c));
        assert_eq!(result.rc, ZOK);
        assert!(matches!(result.reply, Some(Reply::Multi(_))));
        assert_eq!(
            sites(&logs),
            vec![
                LogSite::CompletionMulti,
                LogSite::CompletionString,
                LogSite::ChrootMismatch,
                LogSite::CompletionVoid,
                LogSite::CompletionStat,
            ]
        );
        assert_eq!(ints(&logs[0]), vec![6, 0, 0]);
        assert_eq!(ints(&logs[1]), vec![6, 0, 0]);
    }

    #[test]
    fn failed_queued_multi_fakes_every_sub_op() {
        let mut c = conn();
        c.requests.fifo.push_back(pending(
            7,
            opcode::MULTI,
            multi_shape(&[1, 13]),
            Route::Queued,
        ));
        let body = multi_body(&[(-1, -1, error(ZNONODE)), (-1, -1, error(-2))]);
        let f = reply_frame(7, 0, ZNONODE, &body);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!(r.rc, ZOK);
        let (_, _, result, logs, _) = completion_of(drain(&mut c));
        assert_eq!((result.rc, result.reply), (ZNONODE, None));
        let n = i64::from(ZNONODE);
        assert_eq!(
            sites(&logs),
            vec![
                LogSite::CompletionMulti,
                LogSite::CompletionString,
                LogSite::CompletionVoid
            ]
        );
        for l in &logs {
            assert_eq!(ints(l), vec![7, 1, n]);
        }
    }

    #[test]
    fn direct_multi_decodes_an_error_body() {
        let mut c = conn();
        c.requests.fifo.push_back(pending(
            8,
            opcode::MULTI,
            multi_shape(&[2, 13]),
            Route::Direct,
        ));
        let body = multi_body(&[(-1, -1, error(ZNONODE)), (-1, -1, error(-2))]);
        let f = reply_frame(8, 0, ZNONODE, &body);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!(r.rc, ZOK);
        let (_, route, result, logs, _) = completion_of(drain(&mut c));
        assert_eq!(route, Route::Direct);
        assert_eq!(result.rc, ZNONODE);
        assert!(matches!(result.reply, Some(Reply::Multi(_))));
        assert_eq!(
            sites(&logs),
            vec![
                LogSite::SyncCompletion,
                LogSite::CompletionVoid,
                LogSite::CompletionVoid
            ]
        );
        let n = i64::from(ZNONODE);
        assert_eq!(ints(&logs[0]), vec![i64::from(completion::MULTI), 8, n]);
        assert_eq!(ints(&logs[1]), vec![8, 1, n]);
        assert_eq!(ints(&logs[2]), vec![8, 1, -2]);
    }

    #[test]
    fn header_only_direct_multi_has_no_reply() {
        let mut c = conn();
        c.requests
            .fifo
            .push_back(pending(9, opcode::MULTI, multi_shape(&[2]), Route::Direct));
        let f = reply_frame(9, 0, ZNONODE, &[]);
        let r = c.handle_read(&f, Millis(1));
        assert_eq!(r.rc, ZOK);
        let (_, _, result, logs, _) = completion_of(drain(&mut c));
        assert_eq!((result.rc, result.reply), (ZNONODE, None));
        assert_eq!(
            sites(&logs),
            vec![LogSite::SyncCompletion, LogSite::CompletionVoid]
        );
        assert_eq!(ints(&logs[1]), vec![9, 1, i64::from(ZNONODE)]);
    }

    #[test]
    fn component_outside_path_fails_with_marshalling_error() {
        let mut c = conn_with(component(), Some("/app"));
        c.requests
            .fifo
            .push_back(pending(2, opcode::CREATE, Shape::Path, Route::Queued));
        let f = reply_frame(2, 5, 0, &create("/apple"));
        let r = c.handle_read(&f, Millis(1));
        assert_eq!(r.rc, ZOK);
        let (_, _, result, logs, _) = completion_of(drain(&mut c));
        assert_eq!(result.rc, ZMARSHALLINGERROR);
        assert_eq!(result.origin, Origin::Reply);
        assert_eq!(result.reply, None);
        assert_eq!(
            sites(&logs),
            vec![LogSite::CompletionString, LogSite::ChrootMismatch]
        );
    }

    #[test]
    fn completion_logs_skip_native_shapes() {
        let c = conn();
        let p = pending(3, opcode::GET_EPHEMERALS, Shape::Ephemerals, Route::Queued);
        assert!(c.completion_logs(&p, false, 0, None).is_empty());
        let p = pending(3, opcode::SYNC, Shape::Path, Route::Queued);
        let logs = c.completion_logs(&p, false, 0, None);
        assert_eq!(sites(&logs), vec![LogSite::CompletionString]);
    }

    #[test]
    fn teardown_completion_lines_by_route() {
        let c = conn();
        let p = pending(4, opcode::MULTI, multi_shape(&[1, 5]), Route::Queued);
        let Output::Completion {
            token,
            route,
            result,
            delivery_logs,
        } = c.teardown_completion(p, ZCONNECTIONLOSS)
        else {
            panic!("not a completion")
        };
        assert_eq!((token, route), (4, Route::Queued));
        assert_eq!(result, OpResult::teardown(ZCONNECTIONLOSS));
        assert_eq!(
            sites(&delivery_logs),
            vec![
                LogSite::CompletionMulti,
                LogSite::CompletionString,
                LogSite::CompletionStat
            ]
        );
        for l in &delivery_logs {
            assert_eq!(ints(l), vec![4, 1, i64::from(ZCONNECTIONLOSS)]);
        }
        let p = pending(5, opcode::GET_DATA, Shape::Data, Route::Direct);
        let Output::Completion {
            result,
            delivery_logs,
            ..
        } = c.teardown_completion(p, ZCONNECTIONLOSS)
        else {
            panic!("not a completion")
        };
        assert_eq!(result.origin, Origin::Teardown);
        assert!(delivery_logs.is_empty());
    }

    #[test]
    fn disabled_levels_make_no_lines() {
        let mut c = conn();
        c.log = crate::log::Logger::new(Box::new(crate::hooks::NoLevels));
        let p = pending(4, opcode::GET_DATA, Shape::Data, Route::Queued);
        assert!(c.completion_logs(&p, true, ZNONODE, None).is_empty());
        let c = conn_with(Compat::LEGACY_C, Some("/app"));
        let mut c = c;
        c.log = crate::log::Logger::new(Box::new(crate::hooks::NoLevels));
        let mut logs = Vec::new();
        assert_eq!(c.strip_path(b"/x", &mut logs), Some(JString::from("/x")));
        assert!(logs.is_empty());
    }
}
