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

//! One-shot watcher implementation resolving exactly one event or terminal error.
//!
//! A one-shot watcher is constructed upon the successful completion of an operation
//! that registers a watch (such as `get_data_watched`, `exists_watched`, or
//! `get_children_watched`). Internally, the watch sink is registered before the initial request
//! is submitted to the ensemble. This ensures that an event triggered immediately following
//! server side reply processing cannot be missed or dropped due to race conditions.

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Weak};
use std::task::{Context, Poll};

use crate::decode;
use crate::driver::Gate;
use crate::error::{Error, WatchError};
use crate::future::{ResponseFuture, report_taken};
use crate::path::{ZPath, ZPathBuf};
use crate::shared::Shared;
use crate::slot::{Slot, Taken};
#[cfg(feature = "blocking")]
use crate::sync::blocking_refused;
use crate::tokens::WatchId;

use super::{WatchOutcome, WatchedEvent, WatcherKind};

/// Handle to an active one-shot ZooKeeper watch.
///
/// A one-shot watcher resolves asynchronously as a [`Future`] (or synchronously via
/// `wait` under the `blocking` feature) to either the triggered [`WatchedEvent`] or a
/// terminal [`WatchError`].
///
/// Dropping the handle prior to event delivery deregisters the watcher locally. If this
/// watcher was the sole local registrant for the underlying server watch on the znode,
/// the client automatically submits a `RemoveWatches` request to clean up server side state.
#[must_use = "dropping the watcher removes the watch"]
pub struct OneshotWatcher {
    id: WatchId,
    slot: Arc<Slot<WatchOutcome>>,
    shared: Weak<Shared>,
    /// Read gate permit ensuring accounting consistency across watcher delivery.
    gate: Option<Gate>,
    path: ZPathBuf,
    kind: WatcherKind,
    /// Indicates whether the watch outcome has already been consumed or removed.
    done: bool,
}

impl std::fmt::Debug for OneshotWatcher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OneshotWatcher")
            .field("path", &self.path)
            .field("kind", &self.kind)
            .finish_non_exhaustive()
    }
}

impl OneshotWatcher {
    /// Constructs a new active watcher handle wrapping the provided outcome slot.
    pub(crate) fn new(
        id: WatchId,
        slot: Arc<Slot<WatchOutcome>>,
        shared: Weak<Shared>,
        gate: Option<Gate>,
        path: ZPathBuf,
        kind: WatcherKind,
    ) -> Self {
        Self {
            id,
            slot,
            shared,
            gate,
            path,
            kind,
            done: false,
        }
    }

    /// Returns the ZooKeeper znode path associated with this watch registration.
    #[must_use]
    pub fn path(&self) -> ZPath<'_> {
        self.path.as_path()
    }

    /// Returns the classification of this watch ([`WatcherKind::Data`] or
    /// [`WatcherKind::Children`]).
    #[must_use]
    pub fn kind(&self) -> WatcherKind {
        self.kind
    }

    /// Explicitly deregisters this watcher, submitting a `RemoveWatches` request to the
    /// ensemble if no other local watchers remain on the target znode.
    ///
    /// If other active watchers still share the underlying server registration, this returns
    /// an immediately completed successful future without issuing a wire request.
    pub fn remove(mut self) -> ResponseFuture<()> {
        self.done = true;
        if self.slot.drop_receiver() {
            report_taken(self.gate.as_ref());
        }
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

    /// Consumes the watch outcome from the internal slot, updating read gate accounting.
    fn finish(&mut self, t: Taken<WatchOutcome>) -> WatchOutcome {
        self.done = true;
        match t {
            Taken::Value { v, counted } => {
                if counted {
                    report_taken(self.gate.as_ref());
                }
                v
            }
            Taken::Closed => {
                // If the slot closed without delivering an event, retrieve any terminal
                // session error from the shared client state and detach the watch sink.
                let shared = self.shared.upgrade();
                let err = shared
                    .as_ref()
                    .and_then(|s| s.terminal_error())
                    .map_or(WatchError::Closed, |e| watch_error_of(&e));
                if let Some(s) = shared {
                    s.drop_watch_sink(self.id);
                }
                Err(err)
            }
        }
    }

    /// Synchronously blocks the calling thread until a watch event is delivered or a fatal error
    /// occurs.
    ///
    /// # Errors
    ///
    /// Returns a [`WatchError`] if the session terminates, authentication fails, or the client
    /// closes. Returns [`WatchError::InvalidState`] immediately if invoked from a client I/O
    /// thread, or from any Tokio runtime context (including `spawn_blocking`) when the client uses
    /// the Tokio driver. In Tokio contexts, use `Handle::block_on` instead.
    #[cfg(feature = "blocking")]
    pub fn wait(mut self) -> Result<WatchedEvent, WatchError> {
        if blocking_refused(self.gate.as_ref().is_some_and(Gate::is_tokio)) {
            return Err(WatchError::InvalidState);
        }
        loop {
            // Block indefinitely until an outcome is posted to the slot.
            if let Some(t) = self.slot.wait_take(None) {
                return self.finish(t);
            }
        }
    }
}

impl Future for OneshotWatcher {
    type Output = Result<WatchedEvent, WatchError>;

    /// Polls the underlying outcome slot. Once resolved, subsequent polls return
    /// `WatchError::Closed`.
    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let this = self.get_mut();
        if this.done {
            return Poll::Ready(Err(WatchError::Closed));
        }
        match this.slot.poll_take(cx) {
            Poll::Pending => Poll::Pending,
            Poll::Ready(t) => Poll::Ready(this.finish(t)),
        }
    }
}

impl Drop for OneshotWatcher {
    /// Cleans up receiver registration and detaches the sink if dropped prior to event delivery.
    fn drop(&mut self) {
        if self.done {
            return;
        }
        if self.slot.drop_receiver() {
            report_taken(self.gate.as_ref());
        }
        if let Some(shared) = self.shared.upgrade() {
            shared.drop_watch_sink(self.id);
        }
    }
}

/// Maps a fatal client error into its corresponding watch error representation.
fn watch_error_of(e: &Error) -> WatchError {
    match e {
        Error::SessionExpired => WatchError::SessionExpired,
        Error::AuthFailed => WatchError::AuthFailed,
        _ => WatchError::Closed,
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::task::{Wake, Waker};

    use super::*;
    use crate::watch::EventKind;

    struct CountWake(AtomicUsize);

    impl Wake for CountWake {
        fn wake(self: Arc<Self>) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    fn event() -> WatchedEvent {
        WatchedEvent {
            kind: EventKind::NodeDeleted,
            path: ZPathBuf::new("/a".to_owned()).unwrap(),
            zxid: None,
        }
    }

    fn watcher(slot: &Arc<Slot<WatchOutcome>>) -> OneshotWatcher {
        OneshotWatcher::new(
            WatchId(1),
            Arc::clone(slot),
            Weak::new(),
            None,
            ZPathBuf::new("/a".to_owned()).unwrap(),
            WatcherKind::Data,
        )
    }

    fn poll(w: &mut OneshotWatcher, waker: &Waker) -> Poll<WatchOutcome> {
        Pin::new(w).poll(&mut Context::from_waker(waker))
    }

    #[test]
    fn poll_gives_one_outcome_then_closed() {
        let slot = Slot::new();
        let mut w = watcher(&slot);
        assert_eq!(w.path().as_str(), "/a");
        assert_eq!(w.kind(), WatcherKind::Data);
        let c = Arc::new(CountWake(AtomicUsize::new(0)));
        let waker = Waker::from(Arc::clone(&c));
        assert!(poll(&mut w, &waker).is_pending());
        assert_eq!(slot.fill(Ok(event()), true), Ok(true));
        assert_eq!(c.0.load(Ordering::SeqCst), 1);
        assert_eq!(poll(&mut w, &waker), Poll::Ready(Ok(event())));
        assert_eq!(poll(&mut w, &waker), Poll::Ready(Err(WatchError::Closed)));
        drop(w);
        // The outcome has already been consumed, so dropping the watcher does not mutate slot
        // state.
        assert!(slot.receiver_alive());
    }

    #[test]
    fn a_terminal_error_is_the_outcome() {
        let slot = Slot::new();
        let mut w = watcher(&slot);
        assert_eq!(slot.fill(Err(WatchError::SessionExpired), false), Ok(false));
        assert_eq!(
            poll(&mut w, Waker::noop()),
            Poll::Ready(Err(WatchError::SessionExpired))
        );
    }

    #[test]
    fn a_closed_slot_with_no_client_gives_closed() {
        let slot = Slot::new();
        let mut w = watcher(&slot);
        slot.close();
        assert_eq!(
            poll(&mut w, Waker::noop()),
            Poll::Ready(Err(WatchError::Closed))
        );
    }

    #[test]
    fn a_drop_before_the_outcome_drops_the_receiver() {
        let slot = Slot::new();
        let w = watcher(&slot);
        drop(w);
        assert!(!slot.receiver_alive());
        assert!(slot.fill(Ok(event()), true).is_err());
    }

    #[test]
    fn a_drop_with_a_pending_outcome_drops_it() {
        let slot = Slot::new();
        let w = watcher(&slot);
        assert_eq!(slot.fill(Ok(event()), true), Ok(true));
        drop(w);
        assert!(!slot.receiver_alive());
        assert!(slot.try_take().is_none());
    }

    #[test]
    fn terminal_errors_map_to_watch_errors() {
        assert_eq!(
            watch_error_of(&Error::SessionExpired),
            WatchError::SessionExpired
        );
        assert_eq!(watch_error_of(&Error::AuthFailed), WatchError::AuthFailed);
        assert_eq!(watch_error_of(&Error::Closing), WatchError::Closed);
    }

    #[cfg(feature = "blocking")]
    #[test]
    fn wait_is_refused_on_the_io_side_and_blocks_elsewhere() {
        let slot = Slot::new();
        {
            let w = watcher(&slot);
            let _io = crate::sync::enter_io_side(7);
            assert_eq!(w.wait(), Err(WatchError::InvalidState));
        }
        let slot = Slot::new();
        let w = watcher(&slot);
        let filler = Arc::clone(&slot);
        let t = std::thread::spawn(move || filler.fill(Ok(event()), false));
        assert_eq!(w.wait(), Ok(event()));
        assert_eq!(t.join().unwrap(), Ok(false));
    }
}
