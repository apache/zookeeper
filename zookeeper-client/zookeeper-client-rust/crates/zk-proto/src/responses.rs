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

//! Zero-copy payload buffer recycling and response data representation.
//!
//! To optimize memory throughput when reading large znode payloads (`GetData` responses),
//! large byte buffers are retained directly within the received frame memory rather than
//! allocated anew. The response assumes ownership of the incoming frame buffer, and the
//! frame decoder swaps in a recycled spare buffer of equivalent capacity.
//!
//! When application code drops the resulting [`ReplyData`], its buffer returns to an
//! epoch-tracked spare buffer slot shared with the connection. To eliminate locking
//! overhead for small payloads, data values below [`LARGE`] are cloned directly into
//! standalone vectors without acquiring slot locks.

use core::fmt;
use core::ops::Range;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use zk_jute::JBuffer;

/// Threshold size (64 KiB) above which response payloads utilize zero-copy frame buffer recycling.
///
/// Payloads smaller than this threshold are cloned inline to avoid mutex contention on the
/// spare slot.
pub(crate) const LARGE: usize = 64 * 1024;

/// Upper limit (4 MiB) on buffer capacities retained in the recycled spare slot.
const SPARE_MAX: usize = 4 * 1024 * 1024;

/// Payload byte container for `GetData` response values, supporting nullable znode contents.
///
/// Payloads below `LARGE` are stored as owned byte vectors. Larger payloads are stored as
/// zero-copy sub-slices referencing an underlying frame buffer linked to the connection's
/// spare recycling slot. When the [`ReplyData`] instance is dropped, the backing buffer
/// returns to the connection for reuse in upcoming frame deserialization rounds.
#[derive(Default)]
pub struct ReplyData(Repr);

/// Internal storage representations for [`ReplyData`].
#[derive(Default)]
enum Repr {
    /// Null znode data payload.
    #[default]
    Null,
    /// Independently allocated byte vector for small payloads.
    Owned(Vec<u8>),
    /// Recycled frame buffer holding a large payload slice linked to a spare pool.
    Linked(Box<Linked>),
}

/// Zero-copy payload slice coupled to a connection's buffer return slot.
struct Linked {
    /// Underlying memory buffer containing the received frame payload.
    buf: Vec<u8>,
    /// Sub-slice byte range corresponding to the znode data payload within `buf`.
    range: Range<usize>,
    /// Connection buffer recycling handle that recovers `buf` upon disposal.
    home: Home,
}

impl Drop for Linked {
    fn drop(&mut self) {
        self.home.give(core::mem::take(&mut self.buf));
    }
}

impl ReplyData {
    /// Indicates whether the response payload represents an explicit null znode value.
    #[must_use]
    pub const fn is_null(&self) -> bool {
        matches!(self.0, Repr::Null)
    }

    /// Provides immutable slice access to the payload bytes, returning `None` if null.
    #[must_use]
    pub fn as_bytes(&self) -> Option<&[u8]> {
        match &self.0 {
            Repr::Null => None,
            Repr::Owned(b) => Some(b),
            Repr::Linked(l) => Some(l.buf.get(l.range.clone()).unwrap_or_default()),
        }
    }

    /// Consumes the payload and extracts an owned byte vector, returning `None` if null.
    ///
    /// If the payload occupies the entire capacity of a linked buffer, the buffer is moved
    /// directly without re-allocation. Otherwise, the slice is cloned into a new vector,
    /// returning the underlying buffer to the connection spare pool.
    #[must_use]
    pub fn into_bytes(self) -> Option<Vec<u8>> {
        match self.0 {
            Repr::Null => None,
            Repr::Owned(b) => Some(b),
            Repr::Linked(mut l) => {
                if l.range.start == 0 && l.range.end == l.buf.len() {
                    // Entire buffer is consumed: transfer ownership directly.
                    return Some(core::mem::take(&mut l.buf));
                }
                Some(l.buf.get(l.range.clone()).unwrap_or_default().to_vec())
            }
        }
    }

    /// Constructs a recycled payload slice bound to the connection's buffer return slot.
    fn linked(buf: Vec<u8>, range: Range<usize>, home: Home) -> Self {
        Self(Repr::Linked(Box::new(Linked { buf, range, home })))
    }
}

impl From<JBuffer> for ReplyData {
    fn from(b: JBuffer) -> Self {
        Self(b.into_bytes().map_or(Repr::Null, Repr::Owned))
    }
}

impl Clone for ReplyData {
    /// Clones the payload into a standalone owned buffer detached from the connection's spare
    /// buffer pool.
    fn clone(&self) -> Self {
        Self(
            self.as_bytes()
                .map_or(Repr::Null, |b| Repr::Owned(b.to_vec())),
        )
    }
}

impl PartialEq for ReplyData {
    fn eq(&self, other: &Self) -> bool {
        self.as_bytes() == other.as_bytes()
    }
}

impl Eq for ReplyData {}

impl PartialEq<JBuffer> for ReplyData {
    fn eq(&self, other: &JBuffer) -> bool {
        self.as_bytes() == other.as_bytes()
    }
}

impl fmt::Debug for ReplyData {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_tuple("ReplyData").field(&self.as_bytes()).finish()
    }
}

/// Handle linking a zero-copy response payload buffer back to its originating connection.
struct Home {
    /// Shared synchronization context containing the spare buffer slot.
    shared: Arc<Shared>,
    /// Transport lifecycle epoch active when this payload buffer was allocated.
    epoch: u64,
}

impl Home {
    /// Attempts to deposit the released buffer into the shared spare slot.
    ///
    /// The buffer is recycled only if the current transport epoch matches the allocation
    /// epoch, the spare slot is currently unoccupied, and the buffer capacity falls within
    /// acceptable recycling limits (`LARGE < capacity <= SPARE_MAX`).
    fn give(&self, buf: Vec<u8>) {
        let cap = buf.capacity();
        let used = cap <= LARGE || buf.len().saturating_mul(2) >= cap;
        if cap == 0 || cap > SPARE_MAX || !used {
            return;
        }
        if let Ok(mut slot) = self.shared.slot.lock() {
            if slot.epoch == self.epoch && slot.spare.is_none() {
                slot.spare = Some(buf);
                self.shared.full.store(true, Ordering::Relaxed);
            }
        }
    }
}

/// Protected state tracking the spare buffer and transport lifecycle epoch.
#[derive(Default)]
struct Slot {
    /// Epoch identifier incremented across transport reconnects.
    epoch: u64,
    /// Cached buffer awaiting reuse in upcoming frame deserialization.
    spare: Option<Vec<u8>>,
}

/// Thread-safe spare buffer recycling slot with lock-free occupancy tracking.
#[derive(Default)]
struct Shared {
    /// Mutex protecting the spare buffer slot.
    slot: Mutex<Slot>,
    /// Fast atomic flag indicating whether the spare slot is currently occupied.
    full: AtomicBool,
}

/// Connection-level buffer manager coordinating zero-copy payload recycling.
#[derive(Default)]
pub(crate) struct Spares(Arc<Shared>);

impl Spares {
    /// Constructs a [`ReplyData`] instance extracted from the received frame body.
    ///
    /// For payloads exceeding [`LARGE`], if an existing spare buffer is present, the frame
    /// body is transferred directly to the reply and replaced with the spare. Otherwise, the
    /// payload slice is copied into a buffer preallocated to the frame capacity, seeding
    /// future recycling. Smaller payloads are copied into standard owned vectors.
    pub(crate) fn data_of(
        &self,
        body: &mut Vec<u8>,
        at: Option<Range<usize>>,
    ) -> Option<ReplyData> {
        let Some(at) = at else {
            return Some(ReplyData::default());
        };
        let value = body.get(at.clone())?;
        if value.len() < LARGE {
            return Some(ReplyData(Repr::Owned(value.to_vec())));
        }
        let (spare, epoch) = self.take();
        let home = Home {
            shared: Arc::clone(&self.0),
            epoch,
        };
        let Some(spare) = spare else {
            // Allocate a copy sized to frame capacity so that subsequent frames reuse this
            // allocation upon drop.
            let mut copy = Vec::with_capacity(body.len());
            copy.extend_from_slice(value);
            let all = 0..copy.len();
            return Some(ReplyData::linked(copy, all, home));
        };
        Some(ReplyData::linked(core::mem::replace(body, spare), at, home))
    }

    /// Releases any retained spare buffer when processing small frames to conserve memory.
    pub(crate) fn release(&self) {
        if !self.0.full.load(Ordering::Relaxed) {
            return;
        }
        if let Ok(mut slot) = self.0.slot.lock() {
            slot.spare = None;
            self.0.full.store(false, Ordering::Relaxed);
        }
    }

    /// Advances the transport lifecycle epoch and purges cached spare buffers across
    /// reconnects.
    pub(crate) fn reset(&self) {
        if let Ok(mut slot) = self.0.slot.lock() {
            slot.epoch = slot.epoch.wrapping_add(1);
            slot.spare = None;
            self.0.full.store(false, Ordering::Relaxed);
        }
    }

    /// Extracts the cached spare buffer and captures the current transport epoch.
    fn take(&self) -> (Option<Vec<u8>>, u64) {
        match self.0.slot.lock() {
            Ok(mut slot) => {
                let spare = slot.spare.take();
                self.0.full.store(false, Ordering::Relaxed);
                (spare, slot.epoch)
            }
            // Poisoned mutex returns no spare and an unmatchable epoch, preventing
            // erroneous recycling.
            Err(_) => (None, u64::MAX),
        }
    }

    /// Indicates whether a recycled spare buffer is currently cached in the slot.
    #[cfg(test)]
    pub(crate) fn has_spare(&self) -> bool {
        self.0.slot.lock().is_ok_and(|slot| slot.spare.is_some())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::indexing_slicing)]
mod tests {
    use super::*;

    fn body(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    /// Extracts a reference to the backing frame buffer when the payload uses zero-copy
    /// linking.
    fn linked_buf(d: &ReplyData) -> Option<&Vec<u8>> {
        match &d.0 {
            Repr::Linked(l) => Some(&l.buf),
            Repr::Null | Repr::Owned(_) => None,
        }
    }

    #[test]
    fn the_data_has_the_size_of_a_vec() {
        assert_eq!(size_of::<ReplyData>(), size_of::<Vec<u8>>());
    }

    #[test]
    fn a_small_value_is_an_exact_copy() {
        let s = Spares::default();
        let mut b = body(100);
        let d = s.data_of(&mut b, Some(10..20)).unwrap();
        assert_eq!(d.as_bytes(), b.get(10..20));
        assert_eq!(b.len(), 100, "the body stays");
        drop(d);
        assert!(!s.has_spare(), "a small copy is no spare");
    }

    #[test]
    fn null_and_empty_values_keep_their_meaning() {
        let s = Spares::default();
        let mut b = body(100);
        let null = s.data_of(&mut b, None).unwrap();
        assert!(null.is_null());
        assert_eq!(null.as_bytes(), None);
        assert_eq!(null.into_bytes(), None);
        let empty = s.data_of(&mut b, Some(20..20)).unwrap();
        assert_eq!(empty.as_bytes(), Some(&[][..]));
        assert_eq!(empty.into_bytes(), Some(Vec::new()));
        assert!(s.data_of(&mut b, Some(90..101)).is_none());
    }

    #[test]
    fn a_large_value_takes_the_body_when_a_spare_exists() {
        let s = Spares::default();
        let n = LARGE + 100;
        let at = 20..20 + LARGE;
        // When the spare pool is initially empty, the payload is copied into a buffer
        // preallocated to the full frame capacity to seed future recycling.
        let mut b = body(n);
        let first = s.data_of(&mut b, Some(at.clone())).unwrap();
        assert_eq!(first.as_bytes(), b.get(at.clone()));
        assert_eq!(b.len(), n, "the body stays with the decoder");
        assert!(linked_buf(&first).unwrap().capacity() >= n);
        drop(first);
        assert!(s.has_spare());
        // Once a spare buffer is available, the response takes ownership of the incoming
        // frame buffer and swaps the spare into the decoder.
        let frame = b.clone();
        let mut b = frame.clone();
        let at_ptr = b.as_ptr();
        let second = s.data_of(&mut b, Some(at.clone())).unwrap();
        assert!(!s.has_spare());
        assert_eq!(second.as_bytes(), frame.get(at.clone()));
        assert_eq!(
            linked_buf(&second).unwrap().as_ptr(),
            at_ptr,
            "no copy of the value"
        );
        assert!(b.capacity() >= n, "the decoder gets the spare");
        // Extracting an owned sub-slice copies the payload bytes and returns the backing
        // frame buffer to the spare pool.
        assert_eq!(second.into_bytes().as_deref(), frame.get(at));
        assert!(s.has_spare());
    }

    #[test]
    fn a_small_frame_releases_the_spare() {
        let s = Spares::default();
        let mut b = body(LARGE * 2);
        drop(s.data_of(&mut b, Some(0..LARGE)));
        assert!(s.has_spare());
        s.release();
        assert!(!s.has_spare());
        s.release();
    }

    #[test]
    fn a_reply_of_an_old_transport_gives_no_spare() {
        let s = Spares::default();
        let mut b = body(LARGE * 2);
        let d = s.data_of(&mut b, Some(0..LARGE)).unwrap();
        s.reset();
        drop(d);
        assert!(!s.has_spare());
        let d = s.data_of(&mut b, Some(0..LARGE)).unwrap();
        drop(d);
        assert!(s.has_spare(), "the new epoch takes a spare");
    }

    #[test]
    fn the_slot_keeps_no_buffer_that_the_decoder_drops() {
        let s = Spares::default();
        // Buffers exceeding the 4 MiB retention ceiling are deallocated rather than pooled.
        let mut b = vec![1u8; SPARE_MAX + 1];
        drop(s.data_of(&mut b, Some(0..LARGE)));
        assert!(!s.has_spare());
        // Cloned payloads allocate standalone storage and do not participate in pool recycling.
        let mut b = body(LARGE * 2);
        let d = s.data_of(&mut b, Some(0..LARGE)).unwrap();
        let c = d.clone();
        assert_eq!(c, d);
        assert!(linked_buf(&c).is_none());
        drop(c);
        assert!(!s.has_spare());
        drop(d);
        assert!(s.has_spare());
    }

    #[test]
    fn a_full_copy_moves_out() {
        let d = ReplyData::from(JBuffer::Bytes(b"abc".to_vec()));
        let p = d.as_bytes().unwrap().as_ptr();
        assert_eq!(d, JBuffer::Bytes(b"abc".to_vec()));
        let v = d.into_bytes().unwrap();
        assert_eq!((v.as_ptr(), v.as_slice()), (p, &b"abc"[..]));
        assert!(ReplyData::from(JBuffer::Null).is_null());
        // When a linked payload spans the entire backing buffer, `into_bytes` transfers
        // buffer ownership directly to the caller instead of returning it to the spare pool.
        let s = Spares::default();
        let mut b = body(LARGE + 30);
        let d = s.data_of(&mut b, Some(0..LARGE + 30)).unwrap();
        let p = d.as_bytes().unwrap().as_ptr();
        let v = d.into_bytes().unwrap();
        assert_eq!((v.as_ptr(), v.as_slice()), (p, b.as_slice()));
        assert!(!s.has_spare());
    }
}
