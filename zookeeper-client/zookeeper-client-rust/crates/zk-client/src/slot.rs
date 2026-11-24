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

//! Single-producer, single consumer rendezvous cell for asynchronous operation results and one-
//! shot events.
//!
//! A `Slot<T>` coordinates single assignment result delivery between the driver I/O loop and
//! application-level response futures or blocking callers. The internal synchronization primitive
//! adheres to strict leaf-lock discipline: task wakers and condition variables are signaled only
//! after releasing the inner mutex, preventing deadlock scenarios when wakers re-enter futures
//! inline.
//!
//! The cell also participates in socket backpressure regulation via the driver's read gate
//! protocol. Deliveries that consume slots under `dispatch_queue_capacity` account for memory
//! pressure, and the consuming receiver or drop handler notifies the driver when counted frames
//! are retired.

use std::sync::{Arc, Condvar, Mutex};
use std::task::{Context, Poll, Waker};
#[cfg(any(test, feature = "blocking"))]
use std::time::Instant;

use crate::sync::{LockExt, wake_quietly};
#[cfg(any(test, feature = "blocking"))]
use crate::sync::{wait, wait_until};

/// Result obtained when consuming the contents of a [`Slot`].
#[derive(Debug)]
pub(crate) enum Taken<T> {
    /// Successfully acquired payload.
    Value {
        /// The encapsulated value delivered by the producer.
        v: T,
        /// Flag indicating whether this payload holds a backpressure permit against the driver's
        /// read gate.
        counted: bool,
    },
    /// Indicates the slot was closed without delivering a payload, or the value has already been
    /// consumed.
    Closed,
}

/// Internal synchronized state maintained within a [`Slot`].
struct SlotState<T> {
    value: Option<T>,
    counted: bool,
    waker: Option<Waker>,
    receiver_alive: bool,
    /// Set to true once the channel has been closed or the encapsulated value has been extracted.
    closed: bool,
}

/// Thread-safe, single-assignment synchronization primitive for asynchronous result handoff.
pub(crate) struct Slot<T> {
    st: Mutex<SlotState<T>>,
    cv: Condvar,
}

impl<T> std::fmt::Debug for Slot<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Slot(..)")
    }
}

impl<T> Slot<T> {
    /// Instantiates an empty slot initialized with an active receiver.
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self {
            st: Mutex::new(SlotState {
                value: None,
                counted: false,
                waker: None,
                receiver_alive: true,
                closed: false,
            }),
            cv: Condvar::new(),
        })
    }

    /// Populates the slot with `v` and wakes any registered task or thread awaiting completion.
    ///
    /// Returns `Ok(counted)` if the value was accepted. Returns `Err(v)` to surrender ownership
    /// back to the caller if the receiver has been dropped, the slot is already closed, or a value
    /// was previously assigned.
    pub(crate) fn fill(&self, v: T, count: bool) -> Result<bool, T> {
        let waker = {
            let mut s = self.st.lock_unpoisoned();
            if !s.receiver_alive || s.closed || s.value.is_some() {
                return Err(v);
            }
            s.value = Some(v);
            s.counted = count;
            s.waker.take()
        };
        self.cv.notify_all();
        if let Some(w) = waker {
            wake_quietly(w);
        }
        Ok(count)
    }

    /// Closes an empty slot and notifies the receiver. If a value is already present, it is
    /// preserved.
    pub(crate) fn close(&self) {
        let waker = {
            let mut s = self.st.lock_unpoisoned();
            if s.value.is_some() || s.closed {
                return;
            }
            s.closed = true;
            s.waker.take()
        };
        self.cv.notify_all();
        if let Some(w) = waker {
            wake_quietly(w);
        }
    }

    /// Extracts the contained value under the internal lock or detects a closed state.
    fn take_locked(s: &mut SlotState<T>) -> Option<Taken<T>> {
        if let Some(v) = s.value.take() {
            s.closed = true;
            let counted = std::mem::replace(&mut s.counted, false);
            return Some(Taken::Value { v, counted });
        }
        if s.closed {
            return Some(Taken::Closed);
        }
        None
    }

    /// Asynchronously polls the slot for completion, registering the calling task waker if empty.
    ///
    /// Deduplicates waker updates using `will_wake` to avoid unnecessary task waker re-cloning.
    pub(crate) fn poll_take(&self, cx: &mut Context<'_>) -> Poll<Taken<T>> {
        let mut s = self.st.lock_unpoisoned();
        if let Some(t) = Self::take_locked(&mut s) {
            return Poll::Ready(t);
        }
        match &s.waker {
            Some(w) if w.will_wake(cx.waker()) => {}
            _ => s.waker = Some(cx.waker().clone()),
        }
        Poll::Pending
    }

    /// nonblocking check attempting immediate extraction of any completed payload.
    #[cfg(test)]
    pub(crate) fn try_take(&self) -> Option<Taken<T>> {
        let mut s = self.st.lock_unpoisoned();
        Self::take_locked(&mut s)
    }

    /// Synchronously parks the current thread until a payload arrives, the slot closes, or the
    /// optional deadline expires.
    ///
    /// The caller must ensure compliance with thread isolation guarantees before blocking.
    #[cfg(any(test, feature = "blocking"))]
    pub(crate) fn wait_take(&self, deadline: Option<Instant>) -> Option<Taken<T>> {
        let mut s = self.st.lock_unpoisoned();
        loop {
            if let Some(t) = Self::take_locked(&mut s) {
                return Some(t);
            }
            match deadline {
                None => s = wait(&self.cv, s),
                Some(d) => {
                    let (g, timed_out) = wait_until(&self.cv, s, d);
                    s = g;
                    if timed_out {
                        return Self::take_locked(&mut s);
                    }
                }
            }
        }
    }

    /// Disconnects the receiving end and discards any unconsumed payload outside of the mutex
    /// lock.
    ///
    /// Returns `true` if the dropped payload held a backpressure credit that must be returned
    /// to the driver.
    pub(crate) fn drop_receiver(&self) -> bool {
        let (value, counted) = {
            let mut s = self.st.lock_unpoisoned();
            s.receiver_alive = false;
            s.waker = None;
            let counted = std::mem::replace(&mut s.counted, false);
            (s.value.take(), counted)
        };
        let was_counted = value.is_some() && counted;
        drop(value);
        was_counted
    }

    /// Indicates whether the slot holds a pending value or has transitioned to closed.
    pub(crate) fn is_ready(&self) -> bool {
        let s = self.st.lock_unpoisoned();
        s.value.is_some() || s.closed
    }

    /// Indicates whether the receiving half of this slot remains alive.
    #[cfg(test)]
    pub(crate) fn receiver_alive(&self) -> bool {
        self.st.lock_unpoisoned().receiver_alive
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::task::Wake;
    use std::time::Duration;

    use super::*;

    struct CountWake(AtomicUsize);

    impl Wake for CountWake {
        fn wake(self: Arc<Self>) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    fn waker() -> (Arc<CountWake>, Waker) {
        let c = Arc::new(CountWake(AtomicUsize::new(0)));
        (Arc::clone(&c), Waker::from(Arc::clone(&c)))
    }

    #[test]
    fn fill_wakes_the_stored_waker_once() {
        let slot = Slot::new();
        let (count, w) = waker();
        let mut cx = Context::from_waker(&w);
        assert!(slot.poll_take(&mut cx).is_pending());
        assert!(slot.poll_take(&mut cx).is_pending());
        assert_eq!(slot.fill(5, true), Ok(true));
        assert_eq!(count.0.load(Ordering::SeqCst), 1);
        match slot.poll_take(&mut cx) {
            Poll::Ready(Taken::Value { v, counted }) => {
                assert_eq!(v, 5);
                assert!(counted);
            }
            _ => panic!("no value"),
        }
        assert!(matches!(
            slot.poll_take(&mut cx),
            Poll::Ready(Taken::Closed)
        ));
    }

    #[test]
    fn a_second_fill_gives_the_value_back() {
        let slot = Slot::new();
        assert_eq!(slot.fill(1, false), Ok(false));
        assert_eq!(slot.fill(2, false), Err(2));
    }

    #[test]
    fn close_ends_an_empty_slot_only() {
        let slot: Arc<Slot<u8>> = Slot::new();
        slot.close();
        assert!(matches!(slot.try_take(), Some(Taken::Closed)));
        let slot = Slot::new();
        assert_eq!(slot.fill(3, false), Ok(false));
        slot.close();
        assert!(matches!(slot.try_take(), Some(Taken::Value { v: 3, .. })));
    }

    #[test]
    fn a_dropped_receiver_refuses_values_and_reports_counted_values() {
        let slot = Slot::new();
        assert_eq!(slot.fill(1, true), Ok(true));
        assert!(slot.drop_receiver());
        assert!(!slot.receiver_alive());
        assert_eq!(slot.fill(2, true), Err(2));
        let slot: Arc<Slot<u8>> = Slot::new();
        assert!(!slot.drop_receiver());
    }

    #[test]
    fn wait_take_returns_at_the_deadline_and_after_a_fill() {
        let slot: Arc<Slot<u8>> = Slot::new();
        let soon = Instant::now()
            .checked_add(Duration::from_millis(10))
            .unwrap();
        assert!(slot.wait_take(Some(soon)).is_none());
        let filler = Arc::clone(&slot);
        let t = std::thread::spawn(move || filler.fill(9, false));
        assert!(matches!(
            slot.wait_take(None),
            Some(Taken::Value { v: 9, .. })
        ));
        assert_eq!(t.join().unwrap(), Ok(false));
    }
}
