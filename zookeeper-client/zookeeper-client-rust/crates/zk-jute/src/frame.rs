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

//! Length-prefixed wire frame encoding and incremental decoding.
//!
//! In the ZooKeeper wire protocol, every transport packet is encapsulated in a length prefixed
//! frame consisting of a 4-byte signed big-endian integer followed by that exact number of
//! payload bytes.
//!
//! [`FrameDecoder`] provides stateful, incremental frame reassembly from arbitrary I/O stream
//! chunks. To mitigate denial-of-service vectors involving malicious or corrupted oversized length
//! headers, the decoder never eagerly preallocates memory based solely on the declared length
//! prefix. Instead, buffer capacity expands conservatively only as actual payload bytes are
//! delivered from the transport, bounding memory exposure strictly to throughput received.

use core::fmt;

/// Wire length of the 4-byte big-endian integer frame prefix.
const HEADER_LEN: usize = 4;

/// Granularity threshold (64 KiB) governing incremental buffer expansion before geometric doubling.
const GROW_STEP: usize = 64 * 1024;

/// Upper limit (4 MiB) on buffer capacity retained across frames by [`FrameDecoder::recycle`].
/// Larger buffers are surrendered to the memory allocator to avoid persistent footprint bloat.
const RECYCLE_MAX: usize = 4 * 1024 * 1024;

/// Framing errors encountered during wire length prefix validation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FrameError {
    /// Declared frame length was zero. The canonical C client interprets a 0-length frame
    /// as an orderly transport shutdown, triggering connection teardown with `ZCONNECTIONLOSS`.
    Empty,
    /// Declared frame length was negative. The C compatibility shim stalls ingestion until
    /// connection timeout expires, whereas native runtimes drop the connection immediately.
    Negative(i32),
    /// Declared frame length exceeded the administratively configured maximum threshold.
    TooLarge(u32),
}

impl fmt::Display for FrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => f.write_str("zero frame length"),
            Self::Negative(len) => write!(f, "negative frame length {len}"),
            Self::TooLarge(len) => write!(f, "frame length {len} is above the maximum"),
        }
    }
}

impl std::error::Error for FrameError {}

/// Outcome yielded by a [`FrameDecoder::push`] or [`FrameDecoder::commit`] operation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Pushed {
    /// Input stream was exhausted before accumulating a complete frame; additional data required.
    NeedMore,
    /// Successfully assembled a complete, validated frame payload (excluding the 4-byte length
    /// prefix).
    Frame(Vec<u8>),
    /// Encountered an invalid frame length header; the decoder transitions to a failed state.
    Error(FrameError),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    /// Accumulating the leading 4-byte big-endian length prefix. Holds the count of header bytes
    /// received.
    Header(usize),
    /// Ingesting payload bytes for a frame of expected length `len`.
    Body {
        /// Total expected payload length in bytes.
        len: usize,
        /// Cumulative number of payload bytes received and committed thus far.
        got: usize,
        /// Maximum byte capacity exposed in the current uncommitted zero-copy read window
        /// ([`FrameDecoder::body_room`]).
        room: usize,
    },
    /// Ingestion suspended due to protocol error or explicit client stall.
    Stalled,
    /// Ingestion halted due to an unrecoverable framing error.
    Failed(FrameError),
}

/// Incremental streaming decoder for ZooKeeper length-prefixed wire packets.
///
/// Designed with defensive memory invariants to protect against adversarial framing attacks:
/// - **Zero Speculative Allocation**: The decoder does not allocate payload capacity upon merely
///   reading an incoming length prefix. Storage expands incrementally as payload chunks arrive from
///   the socket, guaranteeing that an untrusted packet declaring a 1 GiB length consumes only
///   memory proportional to the actual bytes delivered.
/// - **Buffer Reuse and Pooling**: Callers may return processed payload buffers via
///   [`FrameDecoder::recycle`]. Retained buffers are reused across successive frames of comparable
///   size, eliminating heap churn during high-volume message exchanges.
/// - **Direct Socket Ingestion**: Network runtimes can query [`FrameDecoder::body_room`] to obtain
///   a mutable slice pointing directly into the frame payload buffer, allowing kernel reads to
///   write directly without intermediate bounce buffers, followed by [`FrameDecoder::commit`].
/// - **Zero-Trust Cleansing**: The decoder zeroizes storage that it relinquishes itself: old
///   storage replaced during growth, and its body buffer in [`FrameDecoder::stall`],
///   [`FrameDecoder::reset`], and `Drop`. Completed frame bodies and buffers passed to
///   [`FrameDecoder::recycle`] are not wiped by the decoder and must be cleansed by the caller.
#[derive(Debug, Clone)]
pub struct FrameDecoder {
    max_len: Option<u32>,
    state: State,
    header: [u8; HEADER_LEN],
    body: Vec<u8>,
}

impl FrameDecoder {
    /// Initializes a new frame decoder with an optional upper bound on acceptable payload length.
    ///
    /// Passing `None` imposes no artificial ceiling, matching the behavior of the canonical C
    /// client.
    #[must_use]
    pub const fn new(max_len: Option<u32>) -> Self {
        Self {
            max_len,
            state: State::Header(0),
            header: [0; HEADER_LEN],
            body: Vec::new(),
        }
    }

    /// Computes the exact number of bytes required to complete the next parsing phase:
    /// either finishing the 4-byte length prefix or completing the remaining payload bytes.
    ///
    /// single threaded synchronous drivers use this value to issue exact read requests,
    /// mirroring the framing loop of the canonical C client. Returns 0 when the decoder
    /// is stalled or in a failed state.
    #[must_use]
    pub fn want(&self) -> usize {
        match self.state {
            State::Header(got) => HEADER_LEN.saturating_sub(got),
            State::Body { len, got, .. } => len.saturating_sub(got),
            State::Stalled | State::Failed(_) => 0,
        }
    }

    /// Returns `true` if the decoder is currently aligned at a clean frame boundary with no
    /// partial packet in flight.
    #[must_use]
    pub const fn is_idle(&self) -> bool {
        matches!(self.state, State::Header(0))
    }

    /// Returns `true` if frame processing has been halted via [`FrameDecoder::stall`].
    #[must_use]
    pub const fn is_stalled(&self) -> bool {
        matches!(self.state, State::Stalled)
    }

    /// Ingests incoming bytes from the transport slice.
    ///
    /// Consumes bytes up to the completion of at most one packet frame per invocation, returning
    /// the count of bytes accepted along with the resulting [`Pushed`] status. Any unconsumed bytes
    /// remaining in `input` must be retained by the caller and re-submitted in subsequent calls.
    pub fn push(&mut self, input: &[u8]) -> (usize, Pushed) {
        let mut consumed = 0usize;
        loop {
            let rest = input.get(consumed..).unwrap_or_default();
            match self.state {
                State::Stalled => return (consumed, Pushed::NeedMore),
                State::Failed(e) => return (consumed, Pushed::Error(e)),
                State::Header(got) => {
                    if rest.is_empty() {
                        return (consumed, Pushed::NeedMore);
                    }
                    let mut got = got;
                    for (slot, b) in self.header.iter_mut().skip(got).zip(rest) {
                        *slot = *b;
                        got = got.saturating_add(1);
                        consumed = consumed.saturating_add(1);
                    }
                    if got < HEADER_LEN {
                        self.state = State::Header(got);
                        return (consumed, Pushed::NeedMore);
                    }
                    match self.check_len(i32::from_be_bytes(self.header)) {
                        Ok(len) => {
                            // Retain recycled buffer capacity and initialized contents for the new
                            // payload.
                            self.state = State::Body {
                                len,
                                got: 0,
                                room: 0,
                            };
                        }
                        Err(e) => {
                            self.state = State::Failed(e);
                            return (consumed, Pushed::Error(e));
                        }
                    }
                }
                State::Body { len, got, .. } => {
                    if rest.is_empty() {
                        return (consumed, Pushed::NeedMore);
                    }
                    let take = self.push_body(len, got, rest);
                    consumed = consumed.saturating_add(take);
                    return (consumed, self.advance(len, got, take));
                }
            }
        }
    }

    /// Copies available payload bytes from `input` into the internal frame buffer and returns the
    /// number of bytes absorbed.
    ///
    /// Existing initialized capacity from recycled buffers is overwritten first, minimizing
    /// reallocation overhead.
    fn push_body(&mut self, len: usize, got: usize, input: &[u8]) -> usize {
        let left = len.saturating_sub(got);
        let take = left.min(input.len());
        self.grow(left, take);
        let init = self.body.len().saturating_sub(got).min(take);
        let end = got.saturating_add(init);
        if let (Some(dst), Some(src)) = (self.body.get_mut(got..end), input.get(..init)) {
            dst.copy_from_slice(src);
        }
        self.body
            .extend_from_slice(input.get(init..take).unwrap_or_default());
        take
    }

    /// Records `n` newly committed payload bytes, finalizing and returning the assembled frame
    /// once `len` is reached.
    fn advance(&mut self, len: usize, got: usize, n: usize) -> Pushed {
        let got = got.saturating_add(n);
        if got < len {
            self.state = State::Body { len, got, room: 0 };
            return Pushed::NeedMore;
        }
        self.state = State::Header(0);
        self.body.truncate(len);
        Pushed::Frame(core::mem::take(&mut self.body))
    }

    /// Exposes an uncommitted mutable slice of internal buffer capacity for direct, zero-copy
    /// network socket ingestion.
    ///
    /// The returned slice is capped at `max`, 64 KiB (`GROW_STEP`), or the remaining payload
    /// bytes required, whichever is smallest. Outside of an active body ingestion phase, returns
    /// an empty slice. Callers populate this buffer and then register the number of bytes written
    /// via [`FrameDecoder::commit`].
    pub fn body_room(&mut self, max: usize) -> &mut [u8] {
        let State::Body { len, got, .. } = self.state else {
            return Default::default();
        };
        let left = len.saturating_sub(got);
        let n = left.min(max).min(GROW_STEP);
        self.state = State::Body { len, got, room: n };
        if n == 0 {
            return Default::default();
        }
        self.grow(left, n);
        let end = got.saturating_add(n);
        if self.body.len() < end {
            self.body.resize(end, 0);
        }
        self.body.get_mut(got..end).unwrap_or_default()
    }

    /// Commits `n` bytes written into the direct ingestion slice previously acquired via
    /// [`FrameDecoder::body_room`].
    ///
    /// Advances the frame state and yields [`Pushed::Frame`] if the payload is now complete. Any
    /// bytes exceeding the most recent `body_room` reservation window are ignored to protect
    /// buffer boundaries.
    pub fn commit(&mut self, n: usize) -> Pushed {
        match self.state {
            State::Body { len, got, room } => {
                let n = n.min(room).min(self.body.len().saturating_sub(got));
                self.advance(len, got, n)
            }
            State::Failed(e) => Pushed::Error(e),
            State::Header(_) | State::Stalled => Pushed::NeedMore,
        }
    }

    /// Halts decoder ingestion and purges internal buffer contents with zeroization.
    ///
    /// A stalled decoder accepts no additional bytes until explicitly re-initialized via
    /// [`FrameDecoder::reset`]. Used by compatibility adapters to handle protocol violations (such
    /// as negative frame lengths) without entering CPU-intensive busy loops.
    pub fn stall(&mut self) {
        self.state = State::Stalled;
        wipe(&mut self.body);
        self.body = Vec::new();
    }

    /// Resets all internal parsing state, discarding partial frames and zeroizing buffer storage
    /// for reuse on a new connection.
    pub fn reset(&mut self) {
        self.state = State::Header(0);
        self.header = [0; HEADER_LEN];
        wipe(&mut self.body);
        self.body = Vec::new();
    }

    /// Offers a previously processed frame buffer back to the decoder for reuse in subsequent
    /// packet assemblies.
    ///
    /// Callers must ensure sensitive authentication credentials or cryptographic secrets have been
    /// cleansed prior to recycling. The decoder retains the buffer only between frame boundaries
    /// when idle, subject to heuristic bounds: capacity must not exceed 4 MiB (`RECYCLE_MAX`),
    /// and large buffers (> 64 KiB) must be at least half-utilized by the completed payload to
    /// prevent persistent memory ballooning.
    pub fn recycle(&mut self, buf: Vec<u8>) {
        let cap = buf.capacity();
        let used = cap <= GROW_STEP || buf.len().saturating_mul(2) >= cap;
        if matches!(self.state, State::Header(_))
            && self.body.capacity() == 0
            && cap <= RECYCLE_MAX
            && used
        {
            self.body = buf;
        }
    }

    fn check_len(&self, len: i32) -> Result<usize, FrameError> {
        let ulen = u32::try_from(len).map_err(|_| FrameError::Negative(len))?;
        if ulen == 0 {
            return Err(FrameError::Empty);
        }
        if self.max_len.is_some_and(|max| ulen > max) {
            return Err(FrameError::TooLarge(ulen));
        }
        usize::try_from(ulen).map_err(|_| FrameError::TooLarge(ulen))
    }

    /// Expands the internal payload buffer to accommodate `take` incoming bytes, enforcing
    /// defensive growth curves.
    ///
    /// Expands by at least 64 KiB or the current capacity to maintain amortized linear copy
    /// overhead. When a frame nears completion, the remaining tail is allocated in full. To
    /// safeguard sensitive protocol secrets (such as session passwords or SASL tokens), memory
    /// migrations allocate a fresh vector and explicitly sanitize the old storage via [`wipe`]
    /// rather than relying on standard reallocations in place.
    fn grow(&mut self, left: usize, take: usize) {
        let got = self.got();
        let cap = self.body.capacity();
        let spare = cap.saturating_sub(got);
        if spare >= take {
            return;
        }
        let mut step = GROW_STEP.max(cap).min(left).max(take);
        if cap >= GROW_STEP && left.saturating_sub(step) < GROW_STEP {
            step = left;
        }
        let mut next = Vec::with_capacity(got.saturating_add(step));
        next.extend_from_slice(self.body.get(..got).unwrap_or_default());
        wipe(&mut self.body);
        self.body = next;
    }

    /// Returns the number of payload bytes received and committed for the active frame.
    const fn got(&self) -> usize {
        match self.state {
            State::Body { got, .. } => got,
            State::Header(_) | State::Stalled | State::Failed(_) => 0,
        }
    }
}

/// Securely overwrites the entire capacity of `buf` with zero bytes before clearing its length.
///
/// Operates without external cryptographic dependencies by zeroizing through [`zero_all`] and using
/// [`core::hint::black_box`] to inhibit compiler dead-code elimination.
fn wipe(buf: &mut Vec<u8>) {
    zero_all(buf);
    core::hint::black_box(buf.as_slice());
    buf.clear();
}

/// Overwrites every byte across the total capacity of `buf` with zeros, setting its length to its
/// capacity.
fn zero_all(buf: &mut Vec<u8>) {
    buf.clear();
    buf.resize(buf.capacity(), 0);
}

impl Drop for FrameDecoder {
    fn drop(&mut self) {
        wipe(&mut self.body);
    }
}

impl Default for FrameDecoder {
    fn default() -> Self {
        Self::new(None)
    }
}

/// Calculates the signed 32-bit wire length prefix and the number of payload bytes to transmit.
///
/// Payloads exceeding `i32::MAX` are clamped to prevent integer overflow in wire headers.
fn body_len(len: usize) -> (i32, usize) {
    match i32::try_from(len) {
        Ok(l) => (l, len),
        Err(_) => (i32::MAX, 0x7fff_ffff),
    }
}

/// Serializes a complete length-prefixed frame containing `body` into the destination vector `out`.
pub fn encode_frame(body: &[u8], out: &mut Vec<u8>) {
    let (len, n) = body_len(body.len());
    out.reserve(n.saturating_add(HEADER_LEN));
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(body.get(..n).unwrap_or(body));
}

/// Token tracking the buffer offset of an unfinalized frame header reserved by [`begin_frame`].
#[derive(Debug, PartialEq, Eq)]
#[must_use = "a frame must be closed with finish_frame"]
pub struct FrameStart(usize);

/// Reserves a 4-byte length prefix placeholder at the end of `out` and returns a [`FrameStart`]
/// token.
///
/// Callers append the payload bytes directly into `out` (e.g. via [`crate::Record::encode`]) and
/// subsequently invoke [`finish_frame`] to write the final computed length into the reserved prefix
/// without intermediate copies.
pub fn begin_frame(out: &mut Vec<u8>) -> FrameStart {
    let at = out.len();
    out.extend_from_slice(&[0; HEADER_LEN]);
    FrameStart(at)
}

/// Computes the payload length written after `start` and writes the 4-byte big-endian length
/// prefix into the placeholder.
pub fn finish_frame(out: &mut [u8], start: FrameStart) {
    let FrameStart(at) = start;
    let Some(body_at) = at.checked_add(HEADER_LEN) else {
        return;
    };
    let (len, _) = body_len(out.len().saturating_sub(body_at));
    if let Some(slot) = out.get_mut(at..body_at) {
        for (dst, src) in slot.iter_mut().zip(len.to_be_bytes()) {
            *dst = src;
        }
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::{FrameDecoder, GROW_STEP, Pushed, wipe, zero_all};

    /// Helper routine that populates `max` bytes of value `b` into the zero-copy buffer window and
    /// commits them.
    fn room_write(dec: &mut FrameDecoder, max: usize, b: u8) -> (usize, Pushed) {
        let room = dec.body_room(max);
        room.fill(b);
        let n = room.len();
        (n, dec.commit(n))
    }

    #[test]
    fn growth_keeps_the_body_bytes() {
        let len = 3 * GROW_STEP + 17;
        let body: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
        let mut wire = i32::try_from(len).unwrap().to_be_bytes().to_vec();
        wire.extend_from_slice(&body);
        let mut dec = FrameDecoder::new(None);
        let mut frame = None;
        for chunk in wire.chunks(4093) {
            if let (_, Pushed::Frame(f)) = dec.push(chunk) {
                frame = Some(f);
            }
        }
        assert_eq!(frame.unwrap(), body);
    }

    #[test]
    fn wipe_keeps_the_capacity_and_empties_the_buffer() {
        let mut buf = vec![0xAAu8; 64];
        buf.truncate(10);
        let cap = buf.capacity();
        wipe(&mut buf);
        assert!(buf.is_empty());
        assert_eq!(buf.capacity(), cap);
    }

    #[test]
    fn capacity_follows_delivered_bytes() {
        let mut dec = FrameDecoder::new(None);
        assert_eq!(dec.push(&i32::MAX.to_be_bytes()), (4, Pushed::NeedMore));
        assert_eq!(dec.body.capacity(), 0);
        let chunk = vec![0u8; 1000];
        let mut sent = 0usize;
        for _ in 0..600 {
            assert_eq!(dec.push(&chunk), (chunk.len(), Pushed::NeedMore));
            sent += chunk.len();
            assert!(dec.body.capacity() <= 2 * sent + GROW_STEP);
        }
    }

    #[test]
    fn small_frame_reserves_only_its_length() {
        let mut dec = FrameDecoder::new(None);
        assert_eq!(dec.push(&[0, 0, 0, 3, 1]), (5, Pushed::NeedMore));
        assert!(dec.body.capacity() <= 3);
    }

    /// Verifies that a 1 MiB payload with an uneven trailing tail expands in a bounded number of
    /// geometric steps.
    #[test]
    fn a_large_body_grows_in_few_steps() {
        let len = 16 * GROW_STEP + 88;
        let mut dec = FrameDecoder::new(None);
        let header = i32::try_from(len).unwrap().to_be_bytes();
        assert_eq!(dec.push(&header), (4, Pushed::NeedMore));
        let chunk = vec![5u8; GROW_STEP];
        let mut caps: Vec<usize> = vec![];
        let mut sent = 0;
        let mut frame = None;
        while frame.is_none() {
            let n = GROW_STEP.min(len - sent);
            let (m, pushed) = dec.push(&chunk[..n]);
            assert_eq!(m, n);
            sent += n;
            match pushed {
                Pushed::Frame(f) => frame = Some(f),
                p => assert_eq!(p, Pushed::NeedMore),
            }
            let cap = frame.as_ref().map_or(dec.body.capacity(), Vec::capacity);
            assert!(cap <= 2 * sent + GROW_STEP);
            if caps.last() != Some(&cap) {
                caps.push(cap);
            }
        }
        assert_eq!(caps.len(), 5, "{caps:?}");
        assert_eq!(caps.last(), Some(&len));
        assert_eq!(frame.map(|f| f.len()), Some(len));
    }

    /// Verifies that direct zero-copy buffer allocations respect defensive growth thresholds and
    /// memory bounds.
    #[test]
    fn body_room_grows_with_the_delivered_bytes() {
        let mut dec = FrameDecoder::new(None);
        assert_eq!(dec.push(&i32::MAX.to_be_bytes()), (4, Pushed::NeedMore));
        assert_eq!(dec.body.capacity(), 0);
        let mut sent = 0usize;
        for _ in 0..600 {
            let room = dec.body_room(usize::MAX);
            assert_eq!(room.len(), GROW_STEP);
            room.get_mut(..1000).unwrap().fill(7);
            assert_eq!(dec.commit(1000), Pushed::NeedMore);
            sent += 1000;
            assert!(dec.body.capacity() <= 2 * sent + 2 * GROW_STEP);
        }
        // Idle reservation windows that receive no input (e.g. following nonblocking read
        // timeouts) do not trigger additional buffer reallocation.
        let cap = dec.body.capacity();
        let _ = dec.body_room(GROW_STEP);
        let _ = dec.body_room(GROW_STEP);
        assert_eq!(dec.body.capacity(), cap);
        assert_eq!(dec.want(), i32::MAX as usize - sent);
    }

    /// Verifies that recycled buffers of matching size avoid zero-fill initialization passes on
    /// subsequent frames.
    #[test]
    fn a_kept_body_needs_no_zero_fill() {
        let len = 16 * GROW_STEP;
        let header = i32::try_from(len).unwrap().to_be_bytes();
        let mut dec = FrameDecoder::new(None);
        assert_eq!(dec.push(&header), (4, Pushed::NeedMore));
        let mut first = None;
        while first.is_none() {
            let (n, p) = room_write(&mut dec, GROW_STEP, 1);
            assert_eq!(n, GROW_STEP);
            assert!(dec.body.len() <= len);
            if let Pushed::Frame(f) = p {
                first = Some(f);
            }
        }
        let first = first.unwrap();
        let (at, cap) = (first.as_ptr(), first.capacity());
        dec.recycle(first);
        assert_eq!(dec.body.len(), len);

        assert_eq!(dec.push(&header), (4, Pushed::NeedMore));
        let mut second = None;
        while second.is_none() {
            let (_, p) = room_write(&mut dec, GROW_STEP, 2);
            if let Pushed::Frame(f) = p {
                second = Some(f);
            } else {
                assert_eq!(dec.body.len(), len, "no new fill");
            }
        }
        let second = second.unwrap();
        assert_eq!((second.as_ptr(), second.capacity()), (at, cap));
        assert_eq!(second.len(), len);
        assert!(second.iter().all(|b| *b == 2));
    }

    /// Verifies seamless interoperability between direct slice ingestion and chunked push
    /// deserialization.
    #[test]
    fn push_and_body_room_share_a_kept_body() {
        let mut dec = FrameDecoder::new(None);
        dec.recycle(vec![0xEE; 300]);
        let mut wire = 200i32.to_be_bytes().to_vec();
        wire.extend_from_slice(&[1u8; 50]);
        assert_eq!(dec.push(&wire), (54, Pushed::NeedMore));
        assert_eq!(dec.body.len(), 300);
        let (n, p) = room_write(&mut dec, 1000, 2);
        assert_eq!(n, 150);
        assert!(matches!(p, Pushed::Frame(_)), "no frame: {p:?}");
        let Pushed::Frame(f) = p else {
            return;
        };
        assert_eq!(dec.body.capacity(), 0);
        assert_eq!(&f[..50], &[1u8; 50]);
        assert_eq!(&f[50..], &[2u8; 150]);
    }

    /// Verifies that stall and reset operations sanitize in-flight buffers and revoke zero-copy
    /// room.
    #[test]
    fn stall_and_reset_drop_a_body_read_in_place() {
        for stall in [true, false] {
            let mut dec = FrameDecoder::new(None);
            dec.recycle(vec![0xEE; 4096]);
            assert_eq!(dec.push(&1000i32.to_be_bytes()), (4, Pushed::NeedMore));
            assert_eq!(room_write(&mut dec, 100, 9), (100, Pushed::NeedMore));
            assert!(dec.body.capacity() > 0);
            if stall {
                dec.stall();
                assert!(dec.is_stalled());
            } else {
                dec.reset();
                assert!(dec.is_idle());
            }
            assert_eq!(dec.body.capacity(), 0);
            assert!(dec.body_room(100).is_empty());
            assert_eq!(dec.commit(100), Pushed::NeedMore);
            assert_eq!(dec.body.capacity(), 0);
        }
    }

    /// Verifies that the zeroization routine cleanses all capacity including uninitialized tail
    /// memory.
    #[test]
    fn zero_all_covers_the_whole_capacity() {
        let mut buf = vec![0xAAu8; 64];
        buf.truncate(10);
        buf.reserve_exact(64);
        let cap = buf.capacity();
        zero_all(&mut buf);
        assert_eq!(buf.len(), cap);
        assert!(buf.iter().all(|b| *b == 0));
    }
}
