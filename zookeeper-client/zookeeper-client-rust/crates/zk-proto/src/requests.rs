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

//! Outbound transmission queuing, in-flight request tracking, and transaction identifier
//! management.
//!
//! Owns [`Requests`]. All serialized frames destined for wire transmission pass through the
//! outbound transmission queue (`tx`). Once transmitted, requests awaiting server responses
//! reside in the pending FIFO queue (`fifo`) in sequential transaction identifier order.

use std::collections::VecDeque;

use zeroize::Zeroize;
use zk_jute::Record;
use zk_jute::multi::MultiKind;
use zk_jute::opcode;
use zk_jute::proto::RequestHeader;

use crate::connection::Connection;
use crate::hooks::Tokens;
use crate::log::LogSite;
use crate::request::Route;
use crate::secret::SecretBytes;
use crate::state::Phase;

/// Payload storage for an outbound wire frame, including its 4-byte length prefix.
pub(crate) enum FrameBytes {
    /// Standard non-sensitive frame payload held in heap-allocated buffer.
    Plain(Vec<u8>),
    /// Sensitive cryptographic payload (such as Connect, Auth, or SASL tokens) securely zeroed
    /// on drop.
    Secret(SecretBytes),
}

/// Minimum threshold in bytes above which large plaintext frames are lent directly without
/// buffer copies.
pub(crate) const LEND_MIN: usize = 512 * 1024;

/// Maximum payload size copied into transport buffers per `poll_transmit` cycle on plaintext
/// connections.
pub(crate) const PLAIN_COPY_MAX: usize = 64 * 1024;

impl FrameBytes {
    /// Exposes the inner frame payload as a contiguous byte slice.
    pub(crate) fn as_slice(&self) -> &[u8] {
        match self {
            Self::Plain(v) => v,
            Self::Secret(s) => s.expose(),
        }
    }

    /// Returns the length of the underlying frame buffer in bytes.
    pub(crate) fn len(&self) -> usize {
        self.as_slice().len()
    }

    /// Indicates whether the frame payload contains protected credentials.
    pub(crate) fn is_secret(&self) -> bool {
        matches!(self, Self::Secret(_))
    }
}

/// Budget constraints governing outbound frame polling per transport iteration.
#[derive(Clone, Copy)]
struct TxLimits {
    /// Upper bound on the total byte count copied into intermediate driver buffers.
    copy: usize,
    /// Maximum frame size eligible for zero-copy ownership transfer; zero disables lending.
    lend: usize,
}

/// Protocol classification of outbound frames, used for phase gating and queue prioritization.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum FrameKind {
    /// Initial session establishment frame (`ConnectRequest`).
    Connect,
    /// Standard application request or SASL-unrelated internal operation.
    Request {
        /// Transaction identifier associated with the request.
        xid: i32,
    },
    /// Connection keepalive heartbeat probe (xid -2).
    Ping,
    /// Authentication scheme credential frame (xid -4).
    Auth {
        /// Index within `AuthState::list` recording entry identity for FIFO reply resolution.
        entry: usize,
    },
    /// Bulk watcher restoration frame re-arming watches after reconnect (xid -8).
    SetWatches,
    /// SASL authentication token frame.
    Sasl,
    /// Graceful session termination request (`CLOSE_OP`).
    Close,
}

/// Outbound protocol frame queued for socket transmission.
pub(crate) struct OutFrame {
    /// Serialized payload bytes; emptied when buffer ownership is lent directly to the driver.
    pub(crate) bytes: FrameBytes,
    /// Original byte size of a lent frame, or zero if normal buffer retains storage.
    pub(crate) lent: u32,
    /// Count of bytes whose socket transmission has been acknowledged by `handle_transmitted`.
    pub(crate) written: usize,
    /// Indicates whether one or more bytes of this frame have been copied into active driver
    /// buffers.
    pub(crate) pinned: bool,
    /// Protocol frame classification.
    pub(crate) kind: FrameKind,
}

impl OutFrame {
    /// Constructs an unencrypted outbound frame.
    pub(crate) fn plain(kind: FrameKind, bytes: Vec<u8>) -> Self {
        Self {
            bytes: FrameBytes::Plain(bytes),
            lent: 0,
            written: 0,
            pinned: false,
            kind,
        }
    }

    /// Constructs a sensitive outbound frame whose payload memory is zeroed upon disposal.
    pub(crate) fn secret(kind: FrameKind, bytes: Vec<u8>) -> Self {
        Self {
            bytes: FrameBytes::Secret(SecretBytes::new(bytes)),
            lent: 0,
            written: 0,
            pinned: false,
            kind,
        }
    }

    /// Returns the effective length of the frame in bytes, accounting for lent buffers.
    pub(crate) fn len(&self) -> usize {
        let lent = usize::try_from(self.lent).unwrap_or(usize::MAX);
        self.bytes.len().max(lent)
    }

    /// Returns `true` if buffer ownership has been temporarily lent to the driver during
    /// transmission.
    #[cfg(test)]
    pub(crate) fn is_lent(&self) -> bool {
        self.lent > 0
    }

    /// Indicates whether the frame has not yet been copied, lent, or acknowledged.
    pub(crate) fn is_untouched(&self) -> bool {
        self.written == 0 && !self.pinned
    }

    /// Indicates whether all bytes comprising the frame have been acknowledged as transmitted.
    pub(crate) fn is_complete(&self) -> bool {
        self.written >= self.len()
    }

    /// Evaluates whether an unpinned, unencrypted frame meets criteria for zero-copy ownership
    /// lending.
    fn lendable(&self, start: usize, lend_max: usize) -> bool {
        let len = self.bytes.len();
        let fits = (LEND_MIN..=lend_max).contains(&len) && u32::try_from(len).is_ok();
        start == 0 && fits && matches!(self.bytes, FrameBytes::Plain(_))
    }

    /// Attempts zero-copy lending of a large plaintext frame into the output buffer.
    ///
    /// Returns `None` if the frame is ineligible, prompting normal byte copying. If prior bytes
    /// were copied in this cycle, returns `Some(0)` to defer lending until the next call. When
    /// `out` is empty, moves the internal vector into `out`, marks the frame pinned, and returns
    /// its length.
    fn try_lend(
        &mut self,
        out: &mut Vec<u8>,
        start: usize,
        copied: usize,
        lend_max: usize,
    ) -> Option<usize> {
        if !self.lendable(start, lend_max) || (copied == 0 && !out.is_empty()) {
            return None;
        }
        if copied > 0 {
            return Some(0);
        }
        if let FrameBytes::Plain(v) = &mut self.bytes {
            *out = core::mem::take(v);
            self.lent = u32::try_from(out.len()).unwrap_or(u32::MAX);
            self.pinned = true;
        }
        Some(out.len())
    }

    /// Computes the next data segment to transfer during transmission polling.
    ///
    /// Returns the lent byte count and an optional slice of bytes to copy.
    fn next_chunk(
        &mut self,
        out: &mut Vec<u8>,
        start: usize,
        left: usize,
        copied: usize,
        lim: TxLimits,
    ) -> (usize, Option<&[u8]>) {
        // Zero-copy lending requires an empty destination buffer, ensuring no secret residual
        // bytes.
        if let Some(lent) = self.try_lend(out, start, copied, lim.lend) {
            return (lent, None);
        }
        // Strict teardown invariants guarantee that transmission polling never encounters a
        // previously lent frame.
        #[cfg(test)]
        assert!(!self.is_lent(), "the bytes of a lent frame are read");
        let take = left.min(lim.copy.saturating_sub(copied));
        (
            0,
            self.bytes.as_slice().get(start..start.saturating_add(take)),
        )
    }
}

/// Expected response structure determining deserialization logic and legacy completion
/// dispatching.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub(crate) enum Shape {
    /// Empty payload response (e.g., `delete`, `setAcl`, `check`).
    Void,
    /// Metadata stat structure (e.g., `exists`).
    Stat,
    /// Byte payload accompanied by node stat metadata (e.g., `getData`).
    Data,
    /// Single znode path string (e.g., `create`).
    Path,
    /// Path string accompanied by node stat metadata (e.g., `create2`).
    PathStat,
    /// Child node name listing (e.g., `getChildren`).
    Children,
    /// Child node name listing accompanied by node stat metadata (e.g., `getChildren2`).
    Children2,
    /// Access control list entries and stat metadata (e.g., `getAcl`).
    Acl,
    /// Multi-operation batch response descriptor.
    Multi {
        /// Wire operation opcodes in sequential request order.
        op_types: Vec<i32>,
        /// Batch response variant distinguishing read transactions from write transactions.
        kind: MultiKind,
    },
    /// Ephemeral node listing (native mode extension).
    Ephemerals,
    /// Subtree child count query (native mode extension).
    ChildrenCount,
    /// Client authentication descriptor query (native mode extension).
    WhoAmI,
}

/// Legacy C client completion type constants matching the `COMPLETION_*` definitions in
/// `zookeeper.c`.
pub(crate) mod completion {
    /// Watcher event callback completion type (`COMPLETION_WATCH`).
    pub(crate) const WATCH: i32 = -1;
    /// Void callback completion type (`COMPLETION_VOID`).
    pub(crate) const VOID: i32 = 0;
    /// Stat callback completion type (`COMPLETION_STAT`).
    pub(crate) const STAT: i32 = 1;
    /// Data callback completion type (`COMPLETION_DATA`).
    pub(crate) const DATA: i32 = 2;
    /// Child string list callback completion type (`COMPLETION_STRINGLIST`).
    pub(crate) const STRINGLIST: i32 = 3;
    /// Child string list with stat callback completion type (`COMPLETION_STRINGLIST_STAT`).
    pub(crate) const STRINGLIST_STAT: i32 = 4;
    /// ACL list callback completion type (`COMPLETION_ACLLIST`).
    pub(crate) const ACLLIST: i32 = 5;
    /// String path callback completion type (`COMPLETION_STRING`).
    pub(crate) const STRING: i32 = 6;
    /// Multi-operation transaction callback completion type (`COMPLETION_MULTI`).
    pub(crate) const MULTI: i32 = 7;
    /// String path with stat callback completion type (`COMPLETION_STRING_STAT`).
    pub(crate) const STRING_STAT: i32 = 8;
}

impl Shape {
    /// Maps the response shape to its legacy C completion constant and diagnostic logging site.
    pub(crate) const fn legacy_kind(&self) -> Option<(i32, LogSite)> {
        match self {
            Self::Void => Some((completion::VOID, LogSite::CompletionVoid)),
            Self::Stat => Some((completion::STAT, LogSite::CompletionStat)),
            Self::Data => Some((completion::DATA, LogSite::CompletionData)),
            Self::Children => Some((completion::STRINGLIST, LogSite::CompletionStringList)),
            Self::Children2 => Some((
                completion::STRINGLIST_STAT,
                LogSite::CompletionStringListStat,
            )),
            Self::Acl => Some((completion::ACLLIST, LogSite::CompletionAclList)),
            Self::Path => Some((completion::STRING, LogSite::CompletionString)),
            Self::PathStat => Some((completion::STRING_STAT, LogSite::CompletionStringStat)),
            Self::Multi {
                kind: MultiKind::Multi,
                ..
            } => Some((completion::MULTI, LogSite::CompletionMulti)),
            Self::Multi {
                kind: MultiKind::MultiRead,
                ..
            }
            | Self::Ephemerals
            | Self::ChildrenCount
            | Self::WhoAmI => None,
        }
    }
}

/// Target watch registration table identifying the triggered event condition.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum WatchKind {
    /// Node data watch armed by `getData`.
    Data,
    /// Node existence watch armed by `exists`. Arms `Data` on `ZOK`, or `Exist` on `ZNONODE`.
    Exist,
    /// Child enumeration watch armed by `getChildren`.
    Child,
    /// Non-clearing persistent watch (native mode extension).
    Persistent,
    /// Recursive non-clearing persistent watch (native mode extension).
    PersistentRecursive,
}

/// Watch registration payload associated with an in-flight request to be armed upon reply receipt.
pub(crate) struct WatchReg<W> {
    /// Target watch table variant.
    pub(crate) kind: WatchKind,
    /// Absolute server znode path, including client chroot prefix.
    pub(crate) path: Vec<u8>,
    /// caller supplied watcher callback or identifier.
    pub(crate) watcher: W,
}

/// Local watcher deregistration directive applied upon successful response to `removeWatches`.
pub(crate) struct Unwatch<W> {
    /// Absolute server znode path, including client chroot prefix.
    pub(crate) path: Vec<u8>,
    /// Watcher category bitmask.
    pub(crate) wtype: i32,
    /// Specific watcher callback to remove, or `None` to clear all watchers on the path.
    pub(crate) watcher: Option<W>,
}

/// In-flight request descriptor tracking transaction state, route, and completion tokens.
pub(crate) struct Pending<T: Tokens> {
    /// Assigned transaction identifier.
    pub(crate) xid: i32,
    /// Expected response payload structure.
    pub(crate) shape: Shape,
    /// Completion delivery route (direct synchronous or asynchronous queue).
    pub(crate) route: Route,
    /// Asynchronous completion handle returned to the driver.
    pub(crate) token: T::Completion,
    /// Optional watcher registration to activate upon successful server reply.
    pub(crate) watch: Option<WatchReg<T::Watcher>>,
    /// Optional local watcher removal to perform upon successful server reply.
    pub(crate) unwatch: Option<Unwatch<T::Watcher>>,
    /// Indicates whether the terminal byte of the outbound frame has been transmitted.
    pub(crate) written: bool,
}

/// Central request coordinator managing outbound transmission queues, pending FIFO records,
/// and xid assignment.
pub(crate) struct Requests<T: Tokens> {
    /// FIFO queue of serialized outbound frames awaiting socket transmission.
    pub(crate) tx: VecDeque<OutFrame>,
    /// Sequential queue of in-flight requests awaiting server responses, ordered strictly by
    /// transaction identifier.
    pub(crate) fifo: VecDeque<Pending<T>>,
    /// The expected `fifo` index of the next request that the transport sends completely.
    /// Request frames go out in submit order, so this index usually points to the match. A stale
    /// value causes only a scan, because each xid in `fifo` is unique.
    sent_hint: usize,
    /// Monotonically incrementing transaction identifier generator.
    next_xid: i32,
    /// Cumulative count of bytes copied into active transport buffers awaiting driver
    /// transmission acknowledgement.
    pub(crate) unacked: usize,
    /// High-water mark offset of sensitive cryptographic bytes in caller copy buffers, tracked
    /// for zeroization.
    secret_end: usize,
    /// Recycled byte buffer from a previously transmitted plaintext `multi` frame, reused for
    /// in-place encoding of the next `multi` request.
    pub(crate) tx_spare: Vec<u8>,
}

/// Minimum capacity threshold (4 KiB) for retaining a transmitted `multi` frame buffer in
/// `tx_spare`.
pub(crate) const TX_SPARE_MIN: usize = 4 * 1024;

/// Maximum capacity ceiling (1 MiB) for retaining a transmitted `multi` frame buffer in
/// `tx_spare`.
pub(crate) const TX_SPARE_MAX: usize = 1024 * 1024;

impl<T: Tokens> Requests<T> {
    /// Instantiates empty transmission and pending FIFO queues, seeding transaction identifiers.
    pub(crate) fn new(xid_seed: i32) -> Self {
        Self {
            tx: VecDeque::new(),
            fifo: VecDeque::new(),
            sent_hint: 0,
            next_xid: if xid_seed <= 0 { 1 } else { xid_seed },
            unacked: 0,
            secret_end: 0,
            tx_spare: Vec::new(),
        }
    }

    /// Retains the backing allocation of a completed outbound frame in `tx_spare` when eligible.
    ///
    /// Only non-secret `MULTI` or `MULTI_READ` frames whose capacity falls within
    /// `TX_SPARE_MIN..=TX_SPARE_MAX` and exceeds any currently retained buffer are kept. Secret
    /// frames are always dropped so their contents are zeroized immediately.
    fn keep_tx_spare(&mut self, frame: OutFrame) {
        if let FrameBytes::Plain(v) = frame.bytes {
            let cap = v.capacity();
            if (TX_SPARE_MIN..=TX_SPARE_MAX).contains(&cap)
                && cap > self.tx_spare.capacity()
                && is_multi_frame(&v)
            {
                self.tx_spare = v;
            }
        }
    }

    /// Extracts and clears the recycled transmit buffer, or returns a new empty vector if none is
    /// cached.
    pub(crate) fn take_tx_spare(&mut self) -> Vec<u8> {
        let mut v = core::mem::take(&mut self.tx_spare);
        v.clear();
        v
    }

    /// Allocates the next positive transaction identifier, wrapping from `i32::MAX` to 1.
    pub(crate) fn next_xid(&mut self) -> i32 {
        let xid = self.next_xid;
        self.next_xid = match xid.checked_add(1) {
            Some(n) if n > 0 => n,
            _ => 1,
        };
        xid
    }

    /// Appends an outbound frame to the rear of the transmission queue.
    pub(crate) fn push_tail(&mut self, frame: OutFrame) {
        self.tx.push_back(frame);
    }

    /// Inserts an urgent frame ahead of untouched queued packets, preserving partially sent
    /// frames.
    ///
    /// Preserves untouched `Connect` frames at the queue head to ensure handshake serialization.
    pub(crate) fn push_priority(&mut self, frame: OutFrame) {
        let at = self
            .tx
            .iter()
            .position(|f| f.is_untouched() && f.kind != FrameKind::Connect)
            .unwrap_or(self.tx.len());
        self.tx.insert(at, frame);
    }

    /// Appends a pending request descriptor to the in-flight FIFO queue.
    pub(crate) fn push_pending(&mut self, pending: Pending<T>) {
        self.fifo.push_back(pending);
    }

    /// Indicates whether unacknowledged frame bytes remain in the transmission queue.
    pub(crate) fn has_unsent(&self) -> bool {
        self.tx.iter().any(|f| !f.is_complete())
    }

    /// Indicates whether a session connection handshake frame remains pending in the
    /// transmission queue.
    pub(crate) fn connect_frame_unwritten(&self) -> bool {
        self.tx
            .iter()
            .any(|f| f.kind == FrameKind::Connect && !f.is_complete())
    }

    /// Clears transmission pinning flags and resets unacknowledged byte counts upon socket
    /// disconnection.
    pub(crate) fn clear_pins(&mut self) {
        for f in &mut self.tx {
            f.pinned = false;
        }
        self.unacked = 0;
    }

    /// Drains and returns both outbound transmission and pending FIFO queues during connection
    /// reset.
    pub(crate) fn take_all(&mut self) -> (VecDeque<OutFrame>, VecDeque<Pending<T>>) {
        self.unacked = 0;
        self.sent_hint = 0;
        (
            core::mem::take(&mut self.tx),
            core::mem::take(&mut self.fifo),
        )
    }

    /// Consumes and returns in-flight FIFO entries while preserving outbound transmission frames
    /// during graceful shutdown.
    pub(crate) fn take_fifo(&mut self) -> VecDeque<Pending<T>> {
        self.sent_hint = 0;
        core::mem::take(&mut self.fifo)
    }

    /// Removes and returns the oldest in-flight request to pair with an incoming server reply.
    pub(crate) fn pop_head(&mut self) -> Option<Pending<T>> {
        let head = self.fifo.pop_front();
        if head.is_some() {
            self.sent_hint = self.sent_hint.saturating_sub(1);
        }
        head
    }

    /// Re-inserts a request at the head of the in-flight queue when reply deserialization fails.
    pub(crate) fn push_head(&mut self, pending: Pending<T>) {
        self.fifo.push_front(pending);
        self.sent_hint = self.sent_hint.saturating_add(1);
    }

    /// Marks the in-flight record corresponding to a completed outbound request frame as written.
    ///
    /// Probes the tracked `sent_hint` index first and falls back to a linear scan only when frames
    /// complete out of order, maintaining amortized constant time updates across deep in-flight
    /// pipelines.
    fn mark_written(&mut self, xid: i32) {
        let hit = match self.fifo.get(self.sent_hint) {
            Some(p) if p.xid == xid => Some(self.sent_hint),
            _ => self.fifo.iter().position(|p| p.xid == xid),
        };
        if let Some(i) = hit {
            if let Some(p) = self.fifo.get_mut(i) {
                p.written = true;
            }
            self.sent_hint = i.saturating_add(1);
        }
    }
}

/// Securely zeroes unmanaged memory in `out` beyond its active length up to `end`.
///
/// Front-draining or buffer truncation reduces the logical length while leaving historical
/// sensitive data in uninitialized capacity. Erasing only the freed region ensures bound
/// overhead.
fn erase_stale(out: &mut Vec<u8>, end: usize) -> usize {
    let stale = end.saturating_sub(out.len());
    if stale > 0 {
        let spare = out.spare_capacity_mut();
        let k = stale.min(spare.len());
        if let Some(s) = spare.get_mut(..k) {
            s.zeroize();
        }
    }
    end.min(out.len())
}

/// Expands the capacity of `out` by `extra` bytes, securely zeroizing the old buffer prior to
/// deallocation.
fn reserve_erased(out: &mut Vec<u8>, extra: usize) {
    let need = out.len().saturating_add(extra);
    if need <= out.capacity() {
        return;
    }
    let mut grown = Vec::with_capacity(need.max(out.capacity().saturating_mul(2)));
    grown.extend_from_slice(out);
    out.zeroize();
    *out = grown;
}

impl<T: Tokens> Connection<T> {
    /// Copies or lends outbound frame bytes into `out` up to `max` bytes, returning total bytes
    /// transferred.
    ///
    /// # Protocol Transmission Lifecycle & Security Invariants:
    /// - Returns 0 immediately if no underlying transport is currently connected.
    /// - Iterates through `tx` from the queue head. Transmission halts at the first frame
    ///   forbidden by current phase gating rules (`may_send`).
    /// - Reads from offset `written + in_flight_unacked` for each frame, marking copied frames
    ///   as pinned.
    /// - Increments `unacked` by the total copied byte count.
    /// - When copying the initial byte of an authentication packet, records the entry index in
    ///   the transport's reply order to guarantee sequential reply matching.
    /// - Cryptographic payloads (Connect, Auth, SASL) are tracked to ensure any trailing
    ///   remnants in caller supplied copy buffers are zeroized on subsequent invocations.
    /// - On plaintext transports, large unpinned frames exceeding `LEND_MIN` are transferred via
    ///   zero-copy buffer lending.
    pub fn poll_transmit(&mut self, out: &mut Vec<u8>, max: usize) -> usize {
        if self.requests.secret_end > 0 {
            self.requests.secret_end = erase_stale(out, self.requests.secret_end);
        }
        if !self.core.has_transport {
            return 0;
        }
        // Bytes copied into driver buffers awaiting socket transmission acknowledgement form an
        // in-flight prefix across queued frames. The transmission walk skips this prefix to
        // resume from uncopied bytes.
        let mut skip = self.requests.unacked;
        let mut copied = 0usize;
        // Protect secret credentials residing in `out` from being exposed during buffer
        // reallocations.
        let mut guard = !out.is_empty();
        let mut secret_end = self.requests.secret_end;
        let lim = self.tx_limits(max);
        for i in 0..self.requests.tx.len() {
            if copied >= lim.copy {
                break;
            }
            let Some(frame) = self.requests.tx.get(i) else {
                break;
            };
            let remaining = frame.len().saturating_sub(frame.written);
            let in_flight = skip.min(remaining);
            skip = skip.saturating_sub(in_flight);
            if in_flight >= remaining {
                continue;
            }
            if !self.may_send(frame.kind) {
                break;
            }
            let Some(frame) = self.requests.tx.get_mut(i) else {
                break;
            };
            let start = frame.written.saturating_add(in_flight);
            let left = remaining.saturating_sub(in_flight);
            let (lent, chunk) = frame.next_chunk(out, start, left, copied, lim);
            copied = copied.saturating_add(lent);
            let Some(chunk) = chunk else {
                break;
            };
            let take = chunk.len();
            if guard {
                reserve_erased(out, chunk.len());
            }
            out.extend_from_slice(chunk);
            if frame.bytes.is_secret() {
                guard = true;
                secret_end = out.len();
            }
            frame.pinned = true;
            copied = copied.saturating_add(take);
            // Writing the first byte of an authentication packet establishes its sequence on
            // the wire.
            if let (0, FrameKind::Auth { entry }) = (start, frame.kind) {
                self.auth_record_sent(entry);
            }
        }
        self.requests.unacked = self.requests.unacked.saturating_add(copied);
        self.requests.secret_end = secret_end;
        copied
    }

    /// Evaluates transmission budget constraints for a single polling iteration.
    fn tx_limits(&self, max: usize) -> TxLimits {
        if self.cfg.tls {
            TxLimits { copy: max, lend: 0 }
        } else {
            TxLimits {
                copy: max.min(PLAIN_COPY_MAX),
                lend: max,
            }
        }
    }

    /// Acknowledges socket transmission of `n` bytes previously copied by `poll_transmit`.
    ///
    /// # Protocol Acknowledgement & Lifecycle Contracts:
    /// - Caps `n` against `unacked`; stale acknowledgements following transport reset are
    ///   ignored.
    /// - Advances `written` byte offsets on transmission frames in FIFO order.
    /// - Upon fully acknowledging a frame, pops it from `tx` (zeroizing secret bytes) and
    ///   notifies ping timers.
    /// - Fully transmitted application requests mark their matching FIFO pending record as
    ///   `written`.
    /// - Fully transmitted connection requests notify the session state machine
    ///   (`on_connect_sent`).
    pub fn handle_transmitted(&mut self, n: usize, now: crate::time::Millis) {
        // Disconnection resets unacknowledged byte counts to zero, causing stale
        // acknowledgements to be safely ignored.
        let mut left = n.min(self.requests.unacked);
        self.requests.unacked = self.requests.unacked.saturating_sub(left);
        while left > 0 {
            let Some(frame) = self.requests.tx.front_mut() else {
                break;
            };
            let take = frame.len().saturating_sub(frame.written).min(left);
            frame.written = frame.written.saturating_add(take);
            left = left.saturating_sub(take);
            if !frame.is_complete() {
                break;
            }
            // Popping the frame drops and zeroizes sensitive credential buffers.
            let Some(done) = self.requests.tx.pop_front() else {
                break;
            };
            self.timers.on_send(now);
            match done.kind {
                FrameKind::Request { xid } => {
                    self.requests.mark_written(xid);
                    self.requests.keep_tx_spare(done);
                }
                FrameKind::Connect => self.on_connect_sent(),
                FrameKind::Ping
                | FrameKind::Auth { .. }
                | FrameKind::SetWatches
                | FrameKind::Sasl
                | FrameKind::Close => {}
            }
        }
    }

    /// Returns the current count of in-flight requests awaiting server responses.
    #[must_use]
    pub fn pending_len(&self) -> usize {
        self.requests.fifo.len()
    }

    /// Determines whether the current protocol phase permits transmission of a given frame
    /// category.
    ///
    /// # Phase Gating Rules:
    /// - `Established`: Permits transmission of all protocol frame kinds.
    /// - `Sasl`: Restricts transmission exclusively to `Sasl` authentication frames.
    /// - `Associating`: Permits transmission exclusively of `Connect` handshake frames.
    /// - Graceful closing flush: Permits all queued frames if socket transport remains open.
    /// - All other lifecycle phases forbid transmission.
    pub(crate) fn may_send(&self, kind: FrameKind) -> bool {
        if !self.core.has_transport {
            return false;
        }
        if self.close_flush_open() {
            return true;
        }
        match self.core.phase {
            Phase::Established => true,
            Phase::Sasl(_) => kind == FrameKind::Sasl,
            // In the associating phase prior to ConnectResponse, only the initial handshake
            // frame may be transmitted.
            Phase::Associating => kind == FrameKind::Connect,
            Phase::Idle
            | Phase::TcpConnecting
            | Phase::TlsHandshaking { .. }
            | Phase::TlsConfigFailed => false,
        }
    }

    /// Fails a sequence of pending FIFO requests with error code `rc` during connection
    /// teardown.
    ///
    /// Drains requests up through the terminal synchronous `Route::Direct` entry, returning
    /// `true`. Remaining asynchronous requests remain parked for background resolution. If no
    /// synchronous requests are present, fails all entries and returns `false`.
    pub(crate) fn fail_pending_run(&mut self, entries: &mut VecDeque<Pending<T>>, rc: i32) -> bool {
        requests_fail_run(entries, |p| {
            let done = self.teardown_completion(p, rc);
            self.out.push(done);
        })
    }

    /// Serializes a request payload into a complete protocol wire frame.
    pub(crate) fn body_frame(xid: i32, op_type: i32, body: Vec<u8>, framed: bool) -> Vec<u8> {
        if framed {
            Self::frame_in_place(xid, op_type, body)
        } else {
            Self::request_frame(xid, op_type, &body)
        }
    }

    /// Injects frame length prefix and [`RequestHeader`] into preallocated header placeholder
    /// bytes without buffer copying.
    pub(crate) fn frame_in_place(xid: i32, op_type: i32, mut frame: Vec<u8>) -> Vec<u8> {
        // Enforces identical frame length calculation as Jute wire framing.
        let body_len = frame.len().saturating_sub(FRAME_PREFIX_LEN);
        let len = i32::try_from(body_len).unwrap_or(i32::MAX);
        let fields = [len, xid, op_type];
        if let Some(head) = frame.get_mut(..REQUEST_HEAD_LEN) {
            for (slot, field) in head.chunks_exact_mut(4).zip(fields) {
                slot.copy_from_slice(&field.to_be_bytes());
            }
        }
        frame
    }

    /// Constructs a standard protocol frame by prefixing length and [`RequestHeader`] onto the
    /// payload slice.
    pub(crate) fn request_frame(xid: i32, op_type: i32, body: &[u8]) -> Vec<u8> {
        let header = RequestHeader {
            xid,
            r#type: op_type,
        };
        let cap = FRAME_PREFIX_LEN
            .saturating_add(header.encoded_len())
            .saturating_add(body.len());
        let mut out = Vec::with_capacity(cap);
        let start = zk_jute::begin_frame(&mut out);
        header.encode(&mut out);
        out.extend_from_slice(body);
        zk_jute::finish_frame(&mut out, start);
        out
    }
}

/// Standard 4-byte length prefix prepended to all ZooKeeper protocol frames.
const FRAME_PREFIX_LEN: usize = 4;

/// Combined byte size of frame length prefix and encoded [`RequestHeader`].
pub(crate) const REQUEST_HEAD_LEN: usize = FRAME_PREFIX_LEN + 8;

/// Determines whether an encoded request frame carries a `MULTI` or `MULTI_READ` opcode in the
/// trailing 4 bytes of its [`REQUEST_HEAD_LEN`] header prefix.
fn is_multi_frame(frame: &[u8]) -> bool {
    frame
        .get(REQUEST_HEAD_LEN - 4..REQUEST_HEAD_LEN)
        .and_then(|b| <[u8; 4]>::try_from(b).ok())
        .map(i32::from_be_bytes)
        .is_some_and(|t| t == opcode::MULTI || t == opcode::MULTI_READ)
}

/// Pops and fails pending FIFO entries through the last synchronous `Route::Direct` request.
fn requests_fail_run<T: Tokens, F: FnMut(Pending<T>)>(
    entries: &mut VecDeque<Pending<T>>,
    mut fail: F,
) -> bool {
    let last_direct = entries.iter().rposition(|p| p.route == Route::Direct);
    let count = match last_direct {
        Some(i) => i.saturating_add(1),
        None => entries.len(),
    };
    for _ in 0..count {
        let Some(p) = entries.pop_front() else {
            break;
        };
        fail(p);
    }
    last_direct.is_some()
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::indexing_slicing)]
mod tests {
    use super::*;
    use crate::compat::Compat;
    use crate::config::{Config, DriverMode};
    use crate::hooks::{AllLevels, NoDefaultWatcher, Rng};
    use crate::state::SaslNegotiation;
    use crate::time::Millis;

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

    /// Instantiates a connection fixture with an active transport in the specified lifecycle
    /// phase.
    fn conn(phase: Phase) -> Connection<Tk> {
        let mut c = Connection::new(
            Config::legacy(30_000),
            Compat::LEGACY_C,
            DriverMode::ST,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        );
        c.open_transport();
        c.core.phase = phase;
        c
    }

    fn frame(kind: FrameKind, bytes: &[u8]) -> OutFrame {
        OutFrame::plain(kind, bytes.to_vec())
    }

    fn pending(xid: i32, route: Route) -> Pending<Tk> {
        Pending {
            xid,
            shape: Shape::Data,
            route,
            token: u32::try_from(xid).unwrap(),
            watch: None,
            unwatch: None,
            written: false,
        }
    }

    /// Enqueues dummy test frames of requested kinds and returns those copied during
    /// transmission polling.
    fn sendable(c: &mut Connection<Tk>, kinds: &[FrameKind]) -> Vec<FrameKind> {
        for &k in kinds {
            c.requests.push_tail(frame(k, &[1, 2]));
        }
        let mut out = Vec::new();
        let n = c.poll_transmit(&mut out, usize::MAX);
        assert_eq!(n, out.len());
        assert_eq!(c.requests.unacked, n);
        c.requests
            .tx
            .iter()
            .filter(|f| f.pinned)
            .map(|f| f.kind)
            .collect()
    }

    const ALL: [FrameKind; 7] = [
        FrameKind::Connect,
        FrameKind::Sasl,
        FrameKind::Auth { entry: 0 },
        FrameKind::SetWatches,
        FrameKind::Request { xid: 1 },
        FrameKind::Ping,
        FrameKind::Close,
    ];

    #[test]
    fn poll_gates_by_phase() {
        let mut c = conn(Phase::Established);
        c.lose_transport();
        assert!(sendable(&mut c, &ALL).is_empty());

        for phase in [
            Phase::Idle,
            Phase::TcpConnecting,
            Phase::TlsHandshaking {
                deadline: Millis(9),
            },
            Phase::TlsConfigFailed,
        ] {
            assert!(sendable(&mut conn(phase), &ALL).is_empty());
        }

        assert_eq!(
            sendable(&mut conn(Phase::Associating), &ALL),
            vec![FrameKind::Connect]
        );
        let sasl = Phase::Sasl(SaslNegotiation::default());
        let mut c = conn(sasl);
        assert_eq!(
            sendable(&mut c, &[FrameKind::Sasl, FrameKind::Sasl, FrameKind::Ping]),
            vec![FrameKind::Sasl, FrameKind::Sasl]
        );
        // Outbound transmission ceases immediately upon encountering a gated frame.
        assert!(sendable(&mut conn(sasl), &[FrameKind::Ping, FrameKind::Sasl]).is_empty());

        assert_eq!(sendable(&mut conn(Phase::Established), &ALL), ALL.to_vec());
        let mut c = conn(Phase::Idle);
        c.close.flush_established = true;
        c.close.op_epoch = c.core.epoch;
        assert_eq!(sendable(&mut c, &ALL), ALL.to_vec());
        // Reconnected transports restore baseline phase gating, restricting output in
        // Associating to Connect frames.
        let mut c = conn(Phase::Associating);
        c.close.flush_established = true;
        c.close.op_epoch = c.core.epoch.wrapping_sub(1);
        assert_eq!(sendable(&mut c, &ALL), vec![FrameKind::Connect]);
    }

    #[test]
    fn poll_copies_from_offset_and_pins() {
        let mut c = conn(Phase::Established);
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &[0, 1, 2, 3, 4, 5]));
        c.requests
            .push_tail(frame(FrameKind::Ping, &[10, 11, 12, 13]));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, 0), 0);
        assert!(!c.requests.tx[0].pinned);

        assert_eq!(c.poll_transmit(&mut out, 4), 4);
        assert_eq!(out, [0, 1, 2, 3]);
        assert!(c.requests.tx[0].pinned && !c.requests.tx[1].pinned);

        out.clear();
        assert_eq!(c.poll_transmit(&mut out, 4), 4);
        assert_eq!(out, [4, 5, 10, 11]);
        assert!(c.requests.tx[1].pinned);
        assert_eq!(c.requests.unacked, 8);

        c.handle_transmitted(3, Millis(5));
        assert_eq!(c.requests.tx[0].written, 3);
        assert_eq!(c.requests.unacked, 5);
        assert_eq!(c.timers.last_send, Millis(0));

        // Subsequent polling continues from uncopied bytes rather than the acknowledged
        // written offset.
        out.clear();
        assert_eq!(c.poll_transmit(&mut out, 10), 2);
        assert_eq!(out, [12, 13]);
        assert_eq!(c.requests.unacked, 7);
        assert_eq!(c.poll_transmit(&mut out, 10), 0);

        c.handle_transmitted(7, Millis(9));
        assert!(c.requests.tx.is_empty());
        assert_eq!(c.requests.unacked, 0);
        assert_eq!(c.timers.last_send, Millis(9));
    }

    #[test]
    fn transmitted_caps_at_unacked_and_marks_written() {
        let mut c = conn(Phase::Established);
        c.requests.push_tail(frame(FrameKind::Connect, &[0; 4]));
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 7 }, &[7; 5]));
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 8 }, &[8; 5]));
        c.requests.push_pending(pending(7, Route::Queued));
        c.requests.push_pending(pending(8, Route::Queued));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, 6), 6);

        c.handle_transmitted(100, Millis(3));
        assert!(c.session.connect_sent);
        assert_eq!(c.requests.tx.len(), 2);
        assert_eq!(c.requests.tx[0].written, 2);
        assert!(!c.requests.fifo[0].written);
        assert_eq!(c.requests.unacked, 0);

        // Transmission acknowledgements without prior copies cannot be credited.
        c.handle_transmitted(5, Millis(4));
        assert_eq!(c.requests.tx[0].written, 2);
        assert_eq!(c.timers.last_send, Millis(3));

        assert_eq!(c.poll_transmit(&mut out, 100), 8);
        c.handle_transmitted(8, Millis(5));
        assert!(c.requests.tx.is_empty());
        assert!(c.requests.fifo.iter().all(|p| p.written));
    }

    #[test]
    fn mark_written_follows_replies_and_survives_a_stale_hint() {
        let mut r: Requests<Tk> = Requests::new(1);
        for xid in 1..=4 {
            r.push_pending(pending(xid, Route::Queued));
        }
        r.mark_written(1);
        r.mark_written(2);
        assert!(r.pop_head().is_some_and(|p| p.xid == 1 && p.written));
        r.mark_written(3);
        assert_eq!(r.sent_hint, 2);
        assert!(r.fifo.iter().take(2).all(|p| p.written));
        // A frame out of submit order causes a scan.
        r.sent_hint = 0;
        r.mark_written(4);
        assert!(r.fifo.iter().all(|p| p.written));
        assert_eq!(r.sent_hint, 3);
        // A reply that fails to decode goes back to the head.
        let head = r.pop_head();
        assert!(head.is_some());
        if let Some(p) = head {
            r.push_head(p);
        }
        assert_eq!(r.sent_hint, 3);
        let _ = r.take_fifo();
        assert_eq!(r.sent_hint, 0);
    }

    #[test]
    fn transmitted_after_transport_loss_is_ignored() {
        let mut c = conn(Phase::Established);
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &[1; 6]));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, 4), 4);
        c.requests.clear_pins();
        c.lose_transport();
        c.handle_transmitted(4, Millis(2));
        assert_eq!(c.requests.tx.len(), 1);
        assert_eq!(c.requests.tx[0].written, 0);
        assert!(c.requests.tx[0].is_untouched());
        assert_eq!(c.timers.last_send, Millis(0));
        assert_eq!(c.poll_transmit(&mut out, 4), 0);
    }

    /// Validates that sensitive frame copies record the high-water mark of secret bytes for
    /// subsequent zeroization.
    #[test]
    fn secret_copy_records_the_end_of_its_bytes() {
        let mut c = conn(Phase::Established);
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &[1; 6]));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, 100), 6);
        assert_eq!(c.requests.secret_end, 0);
        c.handle_transmitted(6, Millis(1));
        out.clear();

        c.requests
            .push_tail(OutFrame::secret(FrameKind::Connect, vec![9; 8]));
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 2 }, &[2; 4]));
        assert_eq!(c.poll_transmit(&mut out, 100), 12);
        assert_eq!(c.requests.secret_end, 8, "the request is not secret");

        // Incomplete socket transmission leaves unacknowledged secret bytes in the buffer.
        out.drain(..5);
        c.handle_transmitted(5, Millis(2));
        assert_eq!(c.poll_transmit(&mut out, 100), 0);
        assert_eq!(c.requests.secret_end, 7);
        assert_eq!(out, [9, 9, 9, 2, 2, 2, 2]);

        out.clear();
        c.handle_transmitted(7, Millis(3));
        assert_eq!(c.poll_transmit(&mut out, 100), 0);
        assert_eq!(c.requests.secret_end, 0);
    }

    /// Validates that zeroization of residual secret buffer capacity occurs even when the
    /// transport is disconnected.
    #[test]
    fn a_call_with_no_transport_erases_old_copies() {
        let mut c = conn(Phase::Established);
        c.requests
            .push_tail(OutFrame::secret(FrameKind::Connect, vec![9; 8]));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, 100), 8);
        assert_eq!(c.requests.secret_end, 8);
        c.requests.clear_pins();
        c.lose_transport();
        out.clear();
        assert_eq!(c.poll_transmit(&mut out, 100), 0);
        assert_eq!(c.requests.secret_end, 0);
    }

    /// Validates that appending additional data to a pre-populated buffer preserves existing
    /// prefix order.
    #[test]
    fn a_copy_after_held_bytes_keeps_the_order() {
        let mut c = conn(Phase::Established);
        c.requests
            .push_tail(OutFrame::secret(FrameKind::Connect, vec![9; 8]));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, 100), 8);
        out.shrink_to_fit();
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &[1; 64]));
        assert_eq!(c.poll_transmit(&mut out, 100), 64);
        assert!(out.capacity() >= 72);
        assert_eq!(out[..8], [9; 8]);
        assert_eq!(out[8..], [1; 64]);
        assert_eq!(c.requests.secret_end, 8);
    }

    #[test]
    fn fail_run_stops_after_last_direct() {
        let routes = [
            Route::Queued,
            Route::Direct,
            Route::Queued,
            Route::Direct,
            Route::Queued,
        ];
        let mut entries: VecDeque<Pending<Tk>> =
            (1..=5).zip(routes).map(|(x, r)| pending(x, r)).collect();
        let mut failed = Vec::new();
        assert!(requests_fail_run(&mut entries, |p| failed.push(p.xid)));
        assert_eq!(failed, [1, 2, 3, 4]);
        assert_eq!(entries.len(), 1);

        failed.clear();
        assert!(!requests_fail_run(&mut entries, |p| failed.push(p.xid)));
        assert_eq!(failed, [5]);
        assert!(entries.is_empty());
        assert!(!requests_fail_run(&mut entries, |p| failed.push(p.xid)));
        assert_eq!(failed, [5]);
    }

    #[test]
    fn request_frame_layout() {
        let f = Connection::<Tk>::request_frame(0x10, 4, &[9, 9]);
        assert_eq!(f, [0, 0, 0, 10, 0, 0, 0, 0x10, 0, 0, 0, 4, 9, 9]);
        // In-place frame header encoding produces identical wire bytes to standalone
        // construction.
        let mut body = vec![0xee; REQUEST_HEAD_LEN];
        body.extend_from_slice(&[9, 9]);
        let at = body.as_ptr();
        let g = Connection::<Tk>::frame_in_place(0x10, 4, body);
        assert_eq!((g.as_slice(), g.as_ptr()), (f.as_slice(), at));
        let neg = Connection::<Tk>::frame_in_place(-2, 11, vec![0; REQUEST_HEAD_LEN]);
        assert_eq!(neg, Connection::<Tk>::request_frame(-2, 11, &[]));
    }

    /// Verifies that only non-secret `MULTI` and `MULTI_READ` frames within the configured
    /// capacity bounds are retained in `tx_spare` after transmission.
    #[test]
    fn a_sent_request_frame_becomes_the_transmit_spare() {
        let mut c = conn(Phase::Established);
        let big = Connection::<Tk>::frame_in_place(1, opcode::MULTI, vec![1; TX_SPARE_MIN]);
        let at = big.as_ptr();
        c.requests
            .push_tail(OutFrame::plain(FrameKind::Request { xid: 1 }, big));
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 2 }, &[2; 16]));
        c.requests.push_tail(OutFrame::secret(
            FrameKind::Request { xid: 3 },
            vec![3; 2 * TX_SPARE_MIN],
        ));
        // Non-multi request opcodes do not overwrite the retained multi frame spare.
        c.requests.push_tail(OutFrame::plain(
            FrameKind::Request { xid: 4 },
            Connection::<Tk>::frame_in_place(4, opcode::SET_DATA, vec![4; 2 * TX_SPARE_MIN]),
        ));
        for xid in 1..=4 {
            c.requests.push_pending(pending(xid, Route::Queued));
        }
        let mut out = Vec::new();
        loop {
            out.clear();
            let n = c.poll_transmit(&mut out, usize::MAX);
            if n == 0 {
                break;
            }
            c.handle_transmitted(n, Millis(1));
        }
        assert!(c.requests.tx.is_empty());
        assert!(c.requests.fifo.iter().all(|p| p.written));
        let spare = c.requests.take_tx_spare();
        assert_eq!((spare.as_ptr(), spare.len()), (at, 0));
        assert_eq!(c.requests.take_tx_spare().capacity(), 0);
        // Plain `MULTI_READ` frames are also eligible for spare retention.
        c.requests.keep_tx_spare(OutFrame::plain(
            FrameKind::Request { xid: 5 },
            Connection::<Tk>::frame_in_place(5, opcode::MULTI_READ, vec![5; TX_SPARE_MIN]),
        ));
        assert_eq!(c.requests.take_tx_spare().capacity(), TX_SPARE_MIN);
        // Multi frames exceeding `TX_SPARE_MAX` are discarded rather than retained.
        let mut huge = Vec::with_capacity(TX_SPARE_MAX + 1);
        huge.resize(REQUEST_HEAD_LEN, 0);
        c.requests.keep_tx_spare(OutFrame::plain(
            FrameKind::Request { xid: 6 },
            Connection::<Tk>::frame_in_place(6, opcode::MULTI, huge),
        ));
        assert_eq!(c.requests.tx_spare.capacity(), 0);
    }

    /// Validates that uncollected lent frames trigger safety panics upon establishing
    /// subsequent transports.
    #[test]
    #[should_panic(expected = "a lent frame outlives its transport")]
    fn a_lent_frame_left_at_the_next_open_is_caught() {
        let mut c = conn(Phase::Established);
        let bytes = vec![0; LEND_MIN];
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &bytes));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, usize::MAX), LEND_MIN);
        assert!(c.requests.tx[0].is_lent());
        c.requests.clear_pins();
        c.lose_transport();
        let _ = c.open_transport();
    }

    /// Validates that transmission polling panics if attempting to read a frame whose buffer
    /// was previously lent.
    #[test]
    #[should_panic(expected = "the bytes of a lent frame are read")]
    fn a_walk_that_reaches_a_lent_frame_is_caught() {
        let mut c = conn(Phase::Established);
        let bytes = vec![0; LEND_MIN];
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &bytes));
        let mut out = Vec::new();
        assert_eq!(c.poll_transmit(&mut out, usize::MAX), LEND_MIN);
        c.requests.clear_pins();
        out.clear();
        let _ = c.poll_transmit(&mut out, usize::MAX);
    }

    /// Validates that sensitive frames are never lent via zero-copy paths and always remain
    /// subject to copy zeroization.
    #[test]
    fn a_large_secret_frame_is_copied() {
        let mut c = conn(Phase::Established);
        let len = 600 * 1024;
        c.requests.push_tail(OutFrame::secret(
            FrameKind::Auth { entry: 0 },
            vec![0x6b; len],
        ));
        let mut wire = Vec::new();
        let mut out = Vec::with_capacity(len);
        let at = out.as_ptr();
        loop {
            let n = c.poll_transmit(&mut out, usize::MAX);
            if n == 0 {
                break;
            }
            assert!(n <= PLAIN_COPY_MAX, "a copy, at most the copy limit");
            assert_eq!((out.as_ptr(), out.capacity()), (at, len), "no lend");
            let head = &c.requests.tx[0];
            assert!(head.bytes.is_secret() && !head.is_lent());
            wire.extend_from_slice(&out);
            out.clear();
            c.handle_transmitted(n, Millis(0));
        }
        assert_eq!(wire, vec![0x6b; len]);
        assert!(c.requests.tx.is_empty());
    }

    /// Validates that TLS connections bypass buffer lending to accommodate internal TLS
    /// encryption copies.
    #[test]
    fn a_tls_connection_copies_large_frames() {
        let mut c = conn(Phase::Established);
        c.cfg.tls = true;
        let len = 1024 * 1024;
        c.requests
            .push_tail(frame(FrameKind::Request { xid: 1 }, &vec![3; len]));
        let mut out = Vec::with_capacity(len);
        let at = out.as_ptr();
        let chunk = 256 * 1024;
        assert_eq!(c.poll_transmit(&mut out, chunk), chunk);
        assert_eq!((out.as_ptr(), out.capacity()), (at, len));
        assert!(!c.requests.tx[0].is_lent());
        out.clear();
        assert_eq!(c.poll_transmit(&mut out, usize::MAX), len - chunk);
        assert_eq!((out.as_ptr(), out.capacity()), (at, len));
        assert!(!c.requests.tx[0].is_lent());
    }

    /// Validates xid initialization and sequential positive integer rollover.
    #[test]
    fn xid_seed_and_wrap() {
        let mut r: Requests<Tk> = Requests::new(0);
        assert_eq!(r.next_xid(), 1);
        let mut r: Requests<Tk> = Requests::new(i32::MAX);
        assert_eq!(r.next_xid(), i32::MAX);
        assert_eq!(r.next_xid(), 1);
    }

    /// Validates that multiple priority insertions at the untouched boundary emerge in reverse
    /// submission order.
    #[test]
    fn priority_inserts_reverse_at_boundary() {
        let mut r: Requests<Tk> = Requests::new(1);
        let mut head = OutFrame::plain(FrameKind::Ping, vec![0; 8]);
        head.pinned = true;
        r.push_tail(head);
        r.push_tail(OutFrame::plain(FrameKind::Request { xid: 1 }, vec![0; 8]));
        r.push_priority(OutFrame::plain(FrameKind::SetWatches, vec![1]));
        r.push_priority(OutFrame::plain(FrameKind::Auth { entry: 0 }, vec![2]));
        let kinds: Vec<FrameKind> = r.tx.iter().map(|f| f.kind).collect();
        assert_eq!(
            kinds,
            vec![
                FrameKind::Ping,
                FrameKind::Auth { entry: 0 },
                FrameKind::SetWatches,
                FrameKind::Request { xid: 1 }
            ]
        );
    }

    #[test]
    fn priority_inserts_stay_after_an_unsent_connect() {
        let mut r: Requests<Tk> = Requests::new(1);
        r.push_tail(OutFrame::plain(FrameKind::Request { xid: 1 }, vec![0; 8]));
        r.push_priority(OutFrame::plain(FrameKind::Connect, vec![0; 8]));
        r.push_priority(OutFrame::plain(FrameKind::Auth { entry: 0 }, vec![1]));
        let kinds: Vec<FrameKind> = r.tx.iter().map(|f| f.kind).collect();
        assert_eq!(
            kinds,
            vec![
                FrameKind::Connect,
                FrameKind::Auth { entry: 0 },
                FrameKind::Request { xid: 1 }
            ]
        );
    }
}
