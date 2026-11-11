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

//! Persistent watcher event queue and asynchronous stream processing.
//!
//! The internal I/O thread pushes incoming watch events into an [`EventQueue`] without
//! blocking. When an unbounded buffer is configured, events accumulate until consumed.
//! When a bounded capacity is established via `persistent_watch_buffer(n)`, overflowing
//! events are dropped and counted. Upon the next retrieval, [`WatchError::Lagged`] is
//! returned prior to delivering subsequent events. A terminal session or watch error is
//! preserved outside the normal buffer capacity, ensuring that fatal state changes are
//! delivered before the watcher permanently yields `None`.

use std::collections::VecDeque;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Condvar, Mutex, Weak};
use std::task::{Context, Poll, Waker};
#[cfg(any(test, feature = "blocking"))]
use std::time::Instant;

use crate::decode;
use crate::error::{Error, WatchError};
use crate::future::ResponseFuture;
use crate::path::{ZPath, ZPathBuf};
use crate::shared::Shared;
use crate::sync::LockExt;
#[cfg(feature = "blocking")]
use crate::sync::blocking_refused;
#[cfg(any(test, feature = "blocking"))]
use crate::sync::{wait, wait_until};
use crate::tokens::WatchId;

use super::{AddWatchMode, WatchOutcome, WatchedEvent, wake_quietly};

/// Internal synchronized state of a persistent watcher event queue.
#[derive(Debug, Default)]
struct QueueState {
    events: VecDeque<WatchedEvent>,
    /// Count of events dropped due to buffer capacity exhaustion since the last lag notification.
    missed: u64,
    /// Terminal error recorded when the watch or session ceases operation.
    terminal: Option<WatchError>,
    /// Indicates whether the terminal error has already been yielded to the consumer.
    finished: bool,
    waker: Option<Waker>,
    receiver_gone: bool,
}

/// Thread-safe event queue backing a persistent watch handle.
///
/// The internal mutex acts as a leaf lock in the lock hierarchy and is never held
/// across external callbacks or client coordination locks.
#[derive(Debug)]
pub(crate) struct EventQueue {
    st: Mutex<QueueState>,
    cv: Condvar,
    /// Maximum number of queued events allowed, or `None` for unbounded buffering.
    bound: Option<usize>,
}

impl EventQueue {
    /// Constructs a new empty event queue with the specified capacity limit.
    pub(crate) fn new(bound: Option<usize>) -> Arc<Self> {
        Arc::new(Self {
            st: Mutex::new(QueueState::default()),
            cv: Condvar::new(),
            bound,
        })
    }

    /// Enqueues an event and schedules receiver notification after releasing the lock.
    ///
    /// If the queue has reached capacity, the event is dropped and the cumulative missed
    /// count is returned. Invocations after termination or receiver detachment are ignored.
    pub(crate) fn push(&self, ev: WatchedEvent) -> Option<u64> {
        let (waker, lagged) = {
            let mut s = self.st.lock_unpoisoned();
            if s.receiver_gone || s.terminal.is_some() || s.finished {
                return None;
            }
            let full = self.bound.is_some_and(|b| s.events.len() >= b);
            let lagged = if full {
                s.missed = s.missed.saturating_add(1);
                Some(s.missed)
            } else {
                s.events.push_back(ev);
                None
            };
            // Dropped events also trigger a wake notification so that zero-capacity queues
            // promptly deliver the lag notification without requiring a queued event.
            (s.waker.take(), lagged)
        };
        self.wake(waker);
        lagged
    }

    /// Signals any condition-variable waiters and invokes the registered async waker outside the
    /// lock.
    fn wake(&self, waker: Option<Waker>) {
        self.cv.notify_all();
        if let Some(w) = waker {
            wake_quietly(w);
        }
    }

    /// Extracts the next pending outcome while holding the lock.
    ///
    /// Returns `None` if the queue is empty and the stream has not terminated.
    fn take_locked(s: &mut QueueState) -> Option<Option<WatchOutcome>> {
        if s.missed > 0 {
            let missed = std::mem::take(&mut s.missed);
            return Some(Some(Err(WatchError::Lagged { missed })));
        }
        if let Some(ev) = s.events.pop_front() {
            return Some(Some(Ok(ev)));
        }
        if let Some(err) = s.terminal.take() {
            s.finished = true;
            return Some(Some(Err(err)));
        }
        if s.finished {
            return Some(None);
        }
        None
    }

    /// Records a terminal error for the watcher and wakes any waiting consumers. Subsequent errors
    /// are ignored.
    pub(crate) fn terminate(&self, err: WatchError) {
        let waker = {
            let mut s = self.st.lock_unpoisoned();
            if s.receiver_gone || s.terminal.is_some() || s.finished {
                return;
            }
            s.terminal = Some(err);
            s.waker.take()
        };
        self.wake(waker);
    }

    /// Asynchronously polls for the next outcome: `Lagged` notifications take precedence,
    /// followed by queued events in arrival order, then any terminal error, and finally `None`.
    pub(crate) fn poll_next(&self, cx: &mut Context<'_>) -> Poll<Option<WatchOutcome>> {
        let mut s = self.st.lock_unpoisoned();
        if let Some(o) = Self::take_locked(&mut s) {
            return Poll::Ready(o);
        }
        match &s.waker {
            Some(w) if w.will_wake(cx.waker()) => {}
            _ => s.waker = Some(cx.waker().clone()),
        }
        Poll::Pending
    }

    /// Synchronously blocks until an outcome is ready or until the deadline expires.
    #[cfg(any(test, feature = "blocking"))]
    pub(crate) fn wait_next(&self, deadline: Option<Instant>) -> Result<Option<WatchOutcome>, ()> {
        let mut s = self.st.lock_unpoisoned();
        loop {
            if let Some(o) = Self::take_locked(&mut s) {
                return Ok(o);
            }
            match deadline {
                None => s = wait(&self.cv, s),
                Some(d) => {
                    let (g, timed_out) = wait_until(&self.cv, s, d);
                    s = g;
                    if timed_out {
                        return Self::take_locked(&mut s).ok_or(());
                    }
                }
            }
        }
    }

    /// Flags the receiver as detached and clears any pending buffered events and wakers.
    pub(crate) fn drop_receiver(&self) {
        let (events, waker) = {
            let mut s = self.st.lock_unpoisoned();
            s.receiver_gone = true;
            s.missed = 0;
            s.terminal = None;
            (std::mem::take(&mut s.events), s.waker.take())
        };
        // Deallocate queued events and waker outside the lock to prevent re-entrancy latency.
        drop(events);
        drop(waker);
    }
}

/// Handle to an active persistent or recursive ZooKeeper watch stream.
///
/// Persistent watchers produce a continuous stream of [`WatchedEvent`] notifications until
/// explicitly removed or terminated by a fatal session error. Buffer overflow reports
/// ([`WatchError::Lagged`]) are non-terminal, allowing the stream to continue processing
/// subsequent events.
///
/// Dropping the handle detaches the receiver locally. If no other active watchers remain
/// registered for the znode, the client automatically dispatches a `RemoveWatches` request.
#[must_use = "dropping the watcher removes the watch"]
pub struct PersistentWatcher {
    id: WatchId,
    queue: Arc<EventQueue>,
    shared: Weak<Shared>,
    /// Indicates whether the client is managed by a Tokio driver (used for blocking-mode
    /// rejection).
    #[cfg_attr(not(feature = "blocking"), allow(dead_code))]
    tokio: bool,
    path: ZPathBuf,
    mode: AddWatchMode,
    /// Tracks whether explicit removal has already occurred.
    removed: bool,
}

impl std::fmt::Debug for PersistentWatcher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PersistentWatcher")
            .field("path", &self.path)
            .field("mode", &self.mode)
            .finish_non_exhaustive()
    }
}

impl PersistentWatcher {
    /// Constructs a persistent watcher handle bound to the given event queue and session state.
    pub(crate) fn new(
        id: WatchId,
        queue: Arc<EventQueue>,
        shared: Weak<Shared>,
        tokio: bool,
        path: ZPathBuf,
        mode: AddWatchMode,
    ) -> Self {
        Self {
            id,
            queue,
            shared,
            tokio,
            path,
            mode,
            removed: false,
        }
    }

    /// Asynchronously yields the next watch outcome, or `None` once terminated.
    ///
    /// The returned future is cancellation-safe: items are dequeued only when the future
    /// successfully resolves to `Poll::Ready`.
    #[allow(clippy::should_implement_trait)]
    pub fn next(&mut self) -> Next<'_> {
        Next { w: self }
    }

    /// Returns the ZooKeeper znode path associated with this watch registration.
    #[must_use]
    pub fn path(&self) -> ZPath<'_> {
        self.path.as_path()
    }

    /// Returns the watch registration mode ([`AddWatchMode::Persistent`] or
    /// [`AddWatchMode::PersistentRecursive`]).
    #[must_use]
    pub fn mode(&self) -> AddWatchMode {
        self.mode
    }

    /// Explicitly deregisters the persistent watch, submitting a `RemoveWatches` request to
    /// the ensemble if no other local watchers remain on the target path.
    pub fn remove(mut self) -> ResponseFuture<()> {
        self.removed = true;
        self.queue.drop_receiver();
        let Some(shared) = self.shared.upgrade() else {
            return ResponseFuture::ready(Err(Error::Closing));
        };
        match shared.remove_watch(self.id) {
            Ok(None) => ResponseFuture::ready(Ok(())),
            Ok(Some(slot)) => {
                ResponseFuture::waiting(slot, decode::boxed(decode::void), Arc::downgrade(&shared))
            }
            Err(e) => ResponseFuture::ready(Err(e)),
        }
    }

    /// Synchronously blocks the calling thread until the next outcome is available.
    ///
    /// # Errors
    ///
    /// Returns `Some(Err(WatchError::InvalidState))` immediately if invoked from a client I/O
    /// thread, or from any Tokio runtime context (including `spawn_blocking`) when the client uses
    /// the Tokio driver. In Tokio contexts, use `Handle::block_on` instead.
    #[cfg(feature = "blocking")]
    pub fn recv(&mut self) -> Option<Result<WatchedEvent, WatchError>> {
        if blocking_refused(self.tokio) {
            return Some(Err(WatchError::InvalidState));
        }
        loop {
            // Block indefinitely until an outcome is produced or the stream closes.
            if let Ok(o) = self.queue.wait_next(None) {
                return o;
            }
        }
    }
}

impl Drop for PersistentWatcher {
    /// Detaches the receiver and cleans up client watch state if dropped prior to explicit removal.
    fn drop(&mut self) {
        if self.removed {
            return;
        }
        self.queue.drop_receiver();
        if let Some(shared) = self.shared.upgrade() {
            shared.drop_watch_sink(self.id);
        }
    }
}

/// Future producing the next outcome from a [`PersistentWatcher`].
#[derive(Debug)]
#[must_use = "futures do nothing unless polled"]
pub struct Next<'a> {
    w: &'a mut PersistentWatcher,
}

impl Future for Next<'_> {
    type Output = Option<Result<WatchedEvent, WatchError>>;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        self.w.queue.poll_next(cx)
    }
}

#[cfg(feature = "stream")]
impl futures_core::Stream for PersistentWatcher {
    type Item = Result<WatchedEvent, WatchError>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.queue.poll_next(cx)
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::task::Wake;
    use std::time::Duration;

    use super::*;
    use crate::watch::EventKind;

    struct CountWake(AtomicUsize);

    impl Wake for CountWake {
        fn wake(self: Arc<Self>) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    fn counting_waker() -> (Arc<CountWake>, Waker) {
        let c = Arc::new(CountWake(AtomicUsize::new(0)));
        (Arc::clone(&c), Waker::from(Arc::clone(&c)))
    }

    fn event(path: &str) -> WatchedEvent {
        WatchedEvent {
            kind: EventKind::NodeCreated,
            path: ZPathBuf::new(path.to_owned()).unwrap(),
            zxid: None,
        }
    }

    fn next(q: &EventQueue) -> Poll<Option<WatchOutcome>> {
        q.poll_next(&mut Context::from_waker(Waker::noop()))
    }

    fn soon() -> Instant {
        Instant::now()
            .checked_add(Duration::from_millis(10))
            .unwrap()
    }

    #[test]
    fn an_unbounded_queue_never_drops() {
        let q = EventQueue::new(None);
        // Scale down event count under Miri execution to avoid excessive test durations.
        let n = if cfg!(miri) { 500 } else { 10_000 };
        for i in 0..n {
            assert_eq!(q.push(event(&format!("/n{i}"))), None);
        }
        for i in 0..n {
            assert_eq!(next(&q), Poll::Ready(Some(Ok(event(&format!("/n{i}"))))));
        }
        assert_eq!(next(&q), Poll::Pending);
    }

    #[test]
    fn a_full_queue_gives_lagged_first_and_stays_active() {
        let q = EventQueue::new(Some(2));
        assert_eq!(q.push(event("/a")), None);
        assert_eq!(q.push(event("/b")), None);
        assert_eq!(q.push(event("/c")), Some(1));
        assert_eq!(q.push(event("/d")), Some(2));
        assert_eq!(
            next(&q),
            Poll::Ready(Some(Err(WatchError::Lagged { missed: 2 })))
        );
        assert_eq!(next(&q), Poll::Ready(Some(Ok(event("/a")))));
        // A slot has been reclaimed following the previous pop.
        assert_eq!(q.push(event("/e")), None);
        assert_eq!(q.push(event("/f")), Some(1));
        assert_eq!(
            next(&q),
            Poll::Ready(Some(Err(WatchError::Lagged { missed: 1 })))
        );
        assert_eq!(next(&q), Poll::Ready(Some(Ok(event("/b")))));
        assert_eq!(next(&q), Poll::Ready(Some(Ok(event("/e")))));
        assert_eq!(next(&q), Poll::Pending);
    }

    #[test]
    fn a_zero_bound_drops_every_event_and_wakes() {
        let q = EventQueue::new(Some(0));
        let (count, w) = counting_waker();
        assert!(q.poll_next(&mut Context::from_waker(&w)).is_pending());
        assert_eq!(q.push(event("/a")), Some(1));
        assert_eq!(count.0.load(Ordering::SeqCst), 1);
        assert_eq!(
            next(&q),
            Poll::Ready(Some(Err(WatchError::Lagged { missed: 1 })))
        );
    }

    #[test]
    fn the_terminal_error_never_takes_a_slot() {
        let q = EventQueue::new(Some(1));
        assert_eq!(q.push(event("/a")), None);
        assert_eq!(q.push(event("/b")), Some(1));
        q.terminate(WatchError::SessionExpired);
        q.terminate(WatchError::Closed);
        assert_eq!(q.push(event("/c")), None);
        assert_eq!(
            next(&q),
            Poll::Ready(Some(Err(WatchError::Lagged { missed: 1 })))
        );
        assert_eq!(next(&q), Poll::Ready(Some(Ok(event("/a")))));
        assert_eq!(next(&q), Poll::Ready(Some(Err(WatchError::SessionExpired))));
        for _ in 0..3 {
            assert_eq!(next(&q), Poll::Ready(None));
        }
        assert_eq!(q.push(event("/d")), None);
        q.terminate(WatchError::Closed);
        assert_eq!(next(&q), Poll::Ready(None));
    }

    #[test]
    fn push_and_terminate_wake_once_per_stored_waker() {
        let q = EventQueue::new(None);
        let (count, w) = counting_waker();
        let mut cx = Context::from_waker(&w);
        assert!(q.poll_next(&mut cx).is_pending());
        assert!(q.poll_next(&mut cx).is_pending());
        q.push(event("/a"));
        q.push(event("/b"));
        assert_eq!(count.0.load(Ordering::SeqCst), 1);
        assert!(q.poll_next(&mut cx).is_ready());
        assert!(q.poll_next(&mut cx).is_ready());
        assert!(q.poll_next(&mut cx).is_pending());
        q.terminate(WatchError::Removed);
        assert_eq!(count.0.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn a_gone_receiver_drops_events_and_refuses_new_ones() {
        let q = EventQueue::new(Some(1));
        q.push(event("/a"));
        q.push(event("/b"));
        q.drop_receiver();
        assert_eq!(q.push(event("/c")), None);
        q.terminate(WatchError::Closed);
        let s = q.st.lock_unpoisoned();
        assert!(s.events.is_empty());
        assert_eq!(s.missed, 0);
        assert!(s.terminal.is_none());
        assert!(s.waker.is_none());
    }

    #[test]
    fn wait_next_times_out_then_gets_the_outcome() {
        let q = EventQueue::new(None);
        assert_eq!(q.wait_next(Some(soon())), Err(()));
        let pusher = Arc::clone(&q);
        let t = std::thread::spawn(move || {
            pusher.push(event("/a"));
            pusher.terminate(WatchError::Closed);
        });
        assert_eq!(q.wait_next(None), Ok(Some(Ok(event("/a")))));
        t.join().unwrap();
        assert_eq!(q.wait_next(None), Ok(Some(Err(WatchError::Closed))));
        assert_eq!(q.wait_next(Some(soon())), Ok(None));
    }

    #[test]
    fn a_watcher_drop_with_no_client_drops_the_receiver() {
        let q = EventQueue::new(None);
        let w = PersistentWatcher::new(
            WatchId(1),
            Arc::clone(&q),
            Weak::new(),
            false,
            ZPathBuf::new("/a".to_owned()).unwrap(),
            AddWatchMode::PersistentRecursive,
        );
        assert_eq!(w.path().as_str(), "/a");
        assert_eq!(w.mode(), AddWatchMode::PersistentRecursive);
        q.push(event("/a"));
        drop(w);
        assert!(q.st.lock_unpoisoned().receiver_gone);
        assert_eq!(q.push(event("/a")), None);
    }

    #[test]
    fn next_gives_the_queue_order() {
        let q = EventQueue::new(None);
        let mut w = PersistentWatcher::new(
            WatchId(1),
            Arc::clone(&q),
            Weak::new(),
            false,
            ZPathBuf::new("/a".to_owned()).unwrap(),
            AddWatchMode::Persistent,
        );
        q.push(event("/a"));
        q.terminate(WatchError::Removed);
        let mut cx = Context::from_waker(Waker::noop());
        let mut f = w.next();
        assert_eq!(
            Pin::new(&mut f).poll(&mut cx),
            Poll::Ready(Some(Ok(event("/a"))))
        );
        let mut f = w.next();
        assert_eq!(
            Pin::new(&mut f).poll(&mut cx),
            Poll::Ready(Some(Err(WatchError::Removed)))
        );
        let mut f = w.next();
        assert_eq!(Pin::new(&mut f).poll(&mut cx), Poll::Ready(None));
    }

    #[cfg(feature = "blocking")]
    #[test]
    fn recv_is_refused_on_the_io_side() {
        let q = EventQueue::new(None);
        let mut w = PersistentWatcher::new(
            WatchId(1),
            Arc::clone(&q),
            Weak::new(),
            false,
            ZPathBuf::new("/a".to_owned()).unwrap(),
            AddWatchMode::Persistent,
        );
        q.push(event("/a"));
        {
            let _io = crate::sync::enter_io_side(42);
            assert_eq!(w.recv(), Some(Err(WatchError::InvalidState)));
        }
        assert_eq!(w.recv(), Some(Ok(event("/a"))));
        q.terminate(WatchError::Closed);
        assert_eq!(w.recv(), Some(Err(WatchError::Closed)));
        assert_eq!(w.recv(), None);
    }
}
