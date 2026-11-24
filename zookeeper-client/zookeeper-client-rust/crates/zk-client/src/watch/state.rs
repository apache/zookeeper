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

//! Session lifecycle state management and observable state change notifications.
//!
//! Provides [`SessionState`], [`SessionInfo`], [`StateWatcher`], and the synchronized
//! [`StateCell`] primitive. Similar to a watch channel (e.g. `tokio::sync::watch`), the
//! cell stores only the latest state snapshot alongside a monotonic version counter,
//! eliminating the need for backpressure or unbounded queue growth. Waiters that lag
//! behind will observe the most recent state upon waking, while terminal states are
//! permanent. The cell's internal mutex serves as a leaf lock in the client synchronization
//! hierarchy, and all async wakers and condition variables are signaled after releasing the
//! lock.

use std::collections::BTreeMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Condvar, Mutex};
use std::task::{Context, Poll, Waker};
use std::time::Instant;

#[cfg(test)]
use crate::error::LastError;
use crate::error::{Error, LastErrorUpdate};
use crate::sync::LockExt;
#[cfg(feature = "blocking")]
use crate::sync::blocking_refused;
#[cfg(any(test, feature = "blocking"))]
use crate::sync::{wait, wait_until};

use super::wake_quietly;

/// High-level client session connection state.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum SessionState {
    /// Initial disconnected state prior to establishing a connection, or actively reconnecting
    /// following a connection loss.
    Connecting,
    /// Fully connected to an ensemble member and authenticated (including SASL handshake
    /// completion if configured).
    Connected,
    /// Connected to a read-only server replica (serving reads, but rejecting write operations).
    ConnectedReadOnly,
    /// The session expired on the server. Ephemeral nodes and server watches are cleared. Fatal.
    Expired,
    /// Authentication credentials were rejected by the ensemble. Fatal.
    AuthFailed,
    /// Client was explicitly closed, detached, or all client handles were dropped. Fatal.
    Closed,
}

impl SessionState {
    /// Returns `true` if currently connected to a ZooKeeper server in either read-write or read-
    /// only mode.
    #[must_use]
    pub fn is_connected(self) -> bool {
        matches!(self, Self::Connected | Self::ConnectedReadOnly)
    }

    /// Returns `true` if the session has entered a terminal state and cannot transition further.
    #[must_use]
    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Expired | Self::AuthFailed | Self::Closed)
    }
}

/// Immutable snapshot capturing the current state and connection history of a ZooKeeper session.
#[derive(Clone)]
#[non_exhaustive]
pub struct SessionInfo {
    /// Current connection lifecycle state.
    pub state: SessionState,
    /// Monotonically increasing counter incremented each time a connection is established from a
    /// disconnected state.
    pub epoch: u64,
    /// Timestamp marking when the most recent established connection was severed, if currently
    /// disconnected.
    pub disconnected_since: Option<Instant>,
    /// Root cause of the most recent connection failure or teardown, if any.
    pub last_error: Option<Arc<dyn std::error::Error + Send + Sync>>,
}

impl std::fmt::Debug for SessionInfo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SessionInfo")
            .field("state", &self.state)
            .field("epoch", &self.epoch)
            .field("disconnected_since", &self.disconnected_since)
            .field(
                "last_error",
                &self.last_error.as_ref().map(|e| e.to_string()),
            )
            .finish()
    }
}

/// Synchronized inner state guarded by the [`StateCell`] mutex.
struct CellState {
    info: SessionInfo,
    /// Monotonically increasing revision counter incremented on every state modification.
    version: u64,
    /// Map of active async task wakers waiting for state changes, keyed by registration token.
    wakers: BTreeMap<u64, Waker>,
    /// Next key to allocate for an async waker registration.
    next_key: u64,
}

/// Thread-safe storage cell maintaining current session info and distributing state notifications.
pub(crate) struct StateCell {
    st: Mutex<CellState>,
    cv: Condvar,
    /// Indicates whether the client is managed by a Tokio driver (used for blocking wait
    /// rejection).
    #[cfg_attr(not(feature = "blocking"), allow(dead_code))]
    tokio_driver: bool,
}

impl std::fmt::Debug for StateCell {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("StateCell(..)")
    }
}

impl StateCell {
    /// Constructs a new cell initialized to [`SessionState::Connecting`] at epoch 0 for dedicated
    /// thread drivers.
    pub(crate) fn new() -> Arc<Self> {
        Self::for_driver(false)
    }

    /// Constructs a new cell initialized to [`SessionState::Connecting`] at epoch 0 for the given
    /// runtime driver type.
    pub(crate) fn for_driver(tokio_driver: bool) -> Arc<Self> {
        Arc::new(Self {
            st: Mutex::new(CellState {
                info: SessionInfo {
                    state: SessionState::Connecting,
                    epoch: 0,
                    disconnected_since: None,
                    last_error: None,
                },
                version: 0,
                wakers: BTreeMap::new(),
                next_key: 0,
            }),
            cv: Condvar::new(),
            tokio_driver,
        })
    }

    /// Takes a consistent snapshot of the current session information.
    pub(crate) fn snapshot(&self) -> SessionInfo {
        self.st.lock_unpoisoned().info.clone()
    }

    /// Returns the current state revision counter.
    pub(crate) fn version(&self) -> u64 {
        self.st.lock_unpoisoned().version
    }

    /// Updates session state based on protocol driver transitions and optional error records.
    ///
    /// State transitions follow ZooKeeper protocol semantics:
    /// - `Connected` maps to [`SessionState::Connected`], and `ReadOnly` maps to
    ///   [`SessionState::ConnectedReadOnly`]. Both increment the epoch counter if transitioning
    ///   from a disconnected state and clear `disconnected_since`.
    /// - `ExpiredSession` and `AuthFailed` transition to terminal [`SessionState::Expired`] and
    ///   [`SessionState::AuthFailed`].
    /// - `Closed` maps to terminal [`SessionState::Closed`] if explicit shutdown is in progress.
    ///   Otherwise it represents a recoverable connection loss and transitions to
    ///   [`SessionState::Connecting`].
    /// - Intermediate protocol states map to [`SessionState::Connecting`].
    ///
    /// Once a terminal state is reached, further state modifications are rejected. Waiters are
    /// notified outside the lock if a state change occurred.
    pub(crate) fn on_session(
        &self,
        state: zk_proto::State,
        closing: bool,
        update: LastErrorUpdate,
    ) -> Option<SessionState> {
        let new = public_state(state, closing);
        let mut s = self.st.lock_unpoisoned();
        let old = s.info.state;
        if old.is_terminal() {
            return None;
        }
        let mut changed = new != old;
        if new.is_connected() {
            if !old.is_connected() {
                s.info.epoch = s.info.epoch.saturating_add(1);
                changed = true;
            }
            s.info.disconnected_since = None;
        } else {
            if old.is_connected() {
                s.info.disconnected_since = Some(Instant::now());
            }
            match update {
                LastErrorUpdate::Keep => {}
                LastErrorUpdate::Clear => {
                    changed |= s.info.last_error.take().is_some();
                }
                LastErrorUpdate::Set(e) => {
                    s.info.last_error = Some(e);
                    changed = true;
                }
            }
        }
        if !changed {
            return None;
        }
        s.info.state = new;
        let wakers = Self::bump(&mut s);
        drop(s);
        self.wake(wakers);
        (new != old).then_some(new)
    }

    /// Increments the revision version and extracts all registered wakers for notification outside
    /// the lock.
    fn bump(s: &mut CellState) -> BTreeMap<u64, Waker> {
        s.version = s.version.saturating_add(1);
        std::mem::take(&mut s.wakers)
    }

    /// Wakes all condition-variable waiters and async task wakers outside the lock.
    fn wake(&self, wakers: BTreeMap<u64, Waker>) {
        self.cv.notify_all();
        for w in wakers.into_values() {
            wake_quietly(w);
        }
    }

    /// Inspects the locked state and returns the snapshot if the version exceeds `seen` or if the
    /// state is terminal.
    fn changed_locked(s: &CellState, seen: &mut u64) -> Option<SessionInfo> {
        if s.version > *seen || s.info.state.is_terminal() {
            *seen = s.version;
            return Some(s.info.clone());
        }
        None
    }

    /// Transitions the session state to [`SessionState::Closed`] upon driver shutdown.
    pub(crate) fn set_closed(&self) -> bool {
        let mut s = self.st.lock_unpoisoned();
        if s.info.state.is_terminal() {
            return false;
        }
        if s.info.state.is_connected() {
            s.info.disconnected_since = Some(Instant::now());
        }
        s.info.state = SessionState::Closed;
        let wakers = Self::bump(&mut s);
        drop(s);
        self.wake(wakers);
        true
    }

    /// Records an error in the session snapshot without altering the lifecycle state.
    #[cfg(test)]
    pub(crate) fn set_last_error(&self, e: LastError) {
        let mut s = self.st.lock_unpoisoned();
        s.info.last_error = Some(e);
        let wakers = Self::bump(&mut s);
        drop(s);
        self.wake(wakers);
    }

    /// Returns the corresponding fatal request error if the session is currently in a terminal
    /// state.
    pub(crate) fn terminal_error(&self) -> Option<Error> {
        match self.st.lock_unpoisoned().info.state {
            SessionState::Expired => Some(Error::SessionExpired),
            SessionState::AuthFailed => Some(Error::AuthFailed),
            SessionState::Closed => Some(Error::Closing),
            SessionState::Connecting
            | SessionState::Connected
            | SessionState::ConnectedReadOnly => None,
        }
    }

    /// Asynchronously polls for a state update advancing beyond the provided `seen` version.
    ///
    /// If the state is terminal or a newer revision exists, returns `Poll::Ready`. Otherwise,
    /// registers or updates the task waker under the specified key and returns `Poll::Pending`.
    pub(crate) fn poll_changed(
        &self,
        seen: &mut u64,
        key: &mut Option<u64>,
        cx: &mut Context<'_>,
    ) -> Poll<SessionInfo> {
        let mut s = self.st.lock_unpoisoned();
        if let Some(info) = Self::changed_locked(&s, seen) {
            if let Some(k) = key.take() {
                s.wakers.remove(&k);
            }
            return Poll::Ready(info);
        }
        let k = match *key {
            Some(k) => k,
            None => {
                let k = s.next_key;
                s.next_key = s.next_key.wrapping_add(1);
                *key = Some(k);
                k
            }
        };
        match s.wakers.get_mut(&k) {
            Some(w) if w.will_wake(cx.waker()) => {}
            Some(w) => w.clone_from(cx.waker()),
            None => {
                s.wakers.insert(k, cx.waker().clone());
            }
        }
        Poll::Pending
    }

    /// Deregisters the waker associated with the given registration key when a future is dropped.
    fn forget(&self, key: u64) {
        let removed = self.st.lock_unpoisoned().wakers.remove(&key);
        // Drop the removed waker outside the lock to minimize contention.
        drop(removed);
    }

    /// Returns the count of registered wakers (used for test assertions).
    #[cfg(test)]
    fn waker_count(&self) -> usize {
        self.st.lock_unpoisoned().wakers.len()
    }

    /// Synchronously blocks until the state revision advances beyond `seen` or the deadline
    /// expires.
    #[cfg(any(test, feature = "blocking"))]
    pub(crate) fn wait_changed(
        &self,
        seen: &mut u64,
        deadline: Option<Instant>,
    ) -> Option<SessionInfo> {
        let mut s = self.st.lock_unpoisoned();
        loop {
            if let Some(info) = Self::changed_locked(&s, seen) {
                return Some(info);
            }
            match deadline {
                None => s = wait(&self.cv, s),
                Some(d) => {
                    let (g, timed_out) = wait_until(&self.cv, s, d);
                    s = g;
                    if timed_out {
                        return Self::changed_locked(&s, seen);
                    }
                }
            }
        }
    }
}

/// Maps raw protocol state variants into client-facing [`SessionState`] values.
fn public_state(state: zk_proto::State, closing: bool) -> SessionState {
    match state {
        zk_proto::State::Connected => SessionState::Connected,
        zk_proto::State::ReadOnly => SessionState::ConnectedReadOnly,
        zk_proto::State::ExpiredSession => SessionState::Expired,
        zk_proto::State::AuthFailed => SessionState::AuthFailed,
        zk_proto::State::Closed if closing => SessionState::Closed,
        zk_proto::State::Closed
        | zk_proto::State::Connecting
        | zk_proto::State::Associating
        | zk_proto::State::SslConnecting
        | zk_proto::State::NotConnected => SessionState::Connecting,
    }
}

/// Observer handle monitoring ZooKeeper session state transitions.
///
/// Maintains a local revision cursor tracking observed updates from the shared `StateCell`.
/// Intermediate state changes may coalesce if consumer processing lags; however, `epoch`
/// guarantees visibility into reconnection cycles, and terminal states are permanently retained.
/// Holding a watcher does not prevent session termination or resource cleanup.
#[derive(Clone, Debug)]
pub struct StateWatcher {
    cell: Arc<StateCell>,
    seen: u64,
}

impl StateWatcher {
    /// Constructs a new state watcher initialized to the current cell revision.
    pub(crate) fn new(cell: Arc<StateCell>) -> Self {
        let seen = cell.version();
        Self { cell, seen }
    }

    /// Returns a snapshot of the current session information.
    #[must_use]
    pub fn current(&self) -> SessionInfo {
        self.cell.snapshot()
    }

    /// Indicates whether the backing client runs on a Tokio driver.
    #[cfg(feature = "blocking")]
    pub(crate) fn tokio_driver(&self) -> bool {
        self.cell.tokio_driver
    }

    /// Returns the root cause of the most recent connection loss, if one has occurred.
    #[must_use]
    pub fn last_error(&self) -> Option<Arc<dyn std::error::Error + Send + Sync>> {
        self.cell.snapshot().last_error
    }

    /// Returns an asynchronous future that resolves when the session state changes next.
    ///
    /// If the session has already entered a terminal state, the future resolves immediately.
    pub fn changed(&mut self) -> Changed<'_> {
        Changed { w: self, key: None }
    }

    /// Polls for state changes while retaining a persistent waker key across invocations.
    pub(crate) fn poll_keyed(
        &mut self,
        key: &mut Option<u64>,
        cx: &mut Context<'_>,
    ) -> Poll<SessionInfo> {
        self.cell.poll_changed(&mut self.seen, key, cx)
    }

    /// Cleans up any persistent waker registration associated with the given key.
    pub(crate) fn forget(&self, key: &mut Option<u64>) {
        if let Some(k) = key.take() {
            self.cell.forget(k);
        }
    }

    /// Synchronously blocks the calling thread until the session state changes.
    ///
    /// # Errors
    ///
    /// Returns [`Error::InvalidState`] immediately if invoked from a client I/O thread, or from
    /// any Tokio runtime context (including `spawn_blocking`) when the client uses the Tokio
    /// driver. In Tokio contexts, use `Handle::block_on` on [`StateWatcher::changed`] instead.
    #[cfg(feature = "blocking")]
    pub fn wait_changed(&mut self) -> Result<SessionInfo, Error> {
        loop {
            // Block indefinitely until a state revision update is produced.
            if let Some(info) = self.wait_changed_until(None)? {
                return Ok(info);
            }
        }
    }

    /// Synchronously blocks like [`StateWatcher::wait_changed`] until the session state changes or
    /// `deadline` passes (`None` indicates no deadline). Returns `Ok(None)` when the deadline
    /// elapses first, and returns `Err(Error::InvalidState)` immediately in the same contexts as
    /// `wait_changed` (see that method's `# Errors` section).
    #[cfg(feature = "blocking")]
    pub(crate) fn wait_changed_until(
        &mut self,
        deadline: Option<Instant>,
    ) -> Result<Option<SessionInfo>, Error> {
        if blocking_refused(self.cell.tokio_driver) {
            return Err(Error::InvalidState);
        }
        Ok(self.cell.wait_changed(&mut self.seen, deadline))
    }
}

/// Future resolving to the updated [`SessionInfo`] when a session state change occurs.
#[derive(Debug)]
#[must_use = "futures do nothing unless polled"]
pub struct Changed<'a> {
    w: &'a mut StateWatcher,
    /// Key identifying the registered waker within the state cell.
    key: Option<u64>,
}

impl Future for Changed<'_> {
    type Output = SessionInfo;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<SessionInfo> {
        let this = self.get_mut();
        let w = &mut *this.w;
        w.cell.poll_changed(&mut w.seen, &mut this.key, cx)
    }
}

impl Drop for Changed<'_> {
    /// Deregisters the waker from the state cell upon drop to prevent stale registrations.
    fn drop(&mut self) {
        if let Some(k) = self.key.take() {
            self.w.cell.forget(k);
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::task::Wake;
    use std::time::Duration;

    use zk_proto::State;

    use super::*;

    struct CountWake(AtomicUsize);

    impl Wake for CountWake {
        fn wake(self: Arc<Self>) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    /// Helper constructing an error update for disconnection test scenarios.
    fn cause(text: &str) -> LastErrorUpdate {
        LastErrorUpdate::Set(Arc::new(std::io::Error::other(text.to_owned())))
    }

    fn poll(w: &mut StateWatcher, waker: &Waker) -> Poll<SessionInfo> {
        let mut f = w.changed();
        Pin::new(&mut f).poll(&mut Context::from_waker(waker))
    }

    fn soon() -> Instant {
        Instant::now()
            .checked_add(Duration::from_millis(10))
            .unwrap()
    }

    #[test]
    fn a_dropped_changed_future_leaves_no_waker() {
        let cell = StateCell::new();
        let mut w = StateWatcher::new(Arc::clone(&cell));
        let c = Arc::new(CountWake(AtomicUsize::new(0)));
        for _ in 0..1000 {
            let waker = Waker::from(Arc::new(CountWake(AtomicUsize::new(0))));
            assert!(poll(&mut w, &waker).is_pending());
        }
        assert_eq!(cell.waker_count(), 0, "each drop removes its entry");
        // Successive poll calls on a single future retain a single slot, updating the registered
        // waker.
        {
            let mut f = w.changed();
            let first = Waker::from(Arc::new(CountWake(AtomicUsize::new(0))));
            let last = Waker::from(Arc::clone(&c));
            let mut pf = Pin::new(&mut f);
            assert!(
                pf.as_mut()
                    .poll(&mut Context::from_waker(&first))
                    .is_pending()
            );
            assert!(
                pf.as_mut()
                    .poll(&mut Context::from_waker(&last))
                    .is_pending()
            );
            assert_eq!(cell.waker_count(), 1);
            cell.on_session(State::Connected, false, LastErrorUpdate::Keep);
            assert_eq!(c.0.load(Ordering::SeqCst), 1, "the last waker runs");
            assert!(pf.poll(&mut Context::from_waker(Waker::noop())).is_ready());
        }
        assert_eq!(cell.waker_count(), 0);
    }

    #[test]
    fn a_keyed_poll_keeps_its_waker_until_forget() {
        // Persistent key registrations preserve waker entries across poll intervals to capture
        // subsequent state updates.
        let cell = StateCell::new();
        let mut w = StateWatcher::new(Arc::clone(&cell));
        let c = Arc::new(CountWake(AtomicUsize::new(0)));
        let waker = Waker::from(Arc::clone(&c));
        let mut key = None;
        for _ in 0..3 {
            assert!(
                w.poll_keyed(&mut key, &mut Context::from_waker(&waker))
                    .is_pending()
            );
        }
        assert_eq!(cell.waker_count(), 1);
        cell.on_session(State::Connected, false, LastErrorUpdate::Keep);
        assert_eq!(c.0.load(Ordering::SeqCst), 1);
        assert!(
            w.poll_keyed(&mut key, &mut Context::from_waker(&waker))
                .is_ready()
        );
        assert!(
            w.poll_keyed(&mut key, &mut Context::from_waker(&waker))
                .is_pending()
        );
        assert_eq!(cell.waker_count(), 1);
        w.forget(&mut key);
        assert_eq!((cell.waker_count(), key), (0, None));
    }

    #[test]
    fn the_mapping_table() {
        use SessionState as S;
        let rows = [
            (State::Connected, false, S::Connected),
            (State::ReadOnly, false, S::ConnectedReadOnly),
            (State::ExpiredSession, false, S::Expired),
            (State::AuthFailed, false, S::AuthFailed),
            (State::Closed, true, S::Closed),
            (State::Closed, false, S::Connecting),
            (State::Connecting, false, S::Connecting),
            (State::Associating, true, S::Connecting),
            (State::SslConnecting, false, S::Connecting),
            (State::NotConnected, false, S::Connecting),
        ];
        for (proto, closing, public) in rows {
            assert_eq!(public_state(proto, closing), public, "{proto:?} {closing}");
        }
    }

    #[test]
    fn epoch_grows_at_each_connect_only() {
        let cell = StateCell::new();
        assert_eq!(
            cell.on_session(State::Connected, false, LastErrorUpdate::Keep),
            Some(SessionState::Connected)
        );
        assert_eq!(cell.snapshot().epoch, 1);
        // Transitions between Connected and read-only preserve the existing connection epoch.
        assert_eq!(
            cell.on_session(State::ReadOnly, false, LastErrorUpdate::Keep),
            Some(SessionState::ConnectedReadOnly)
        );
        assert_eq!(cell.snapshot().epoch, 1);
        assert_eq!(
            cell.on_session(State::Connected, false, LastErrorUpdate::Keep),
            Some(SessionState::Connected)
        );
        assert_eq!(
            cell.on_session(State::Connected, false, LastErrorUpdate::Keep),
            None
        );
        assert_eq!(cell.snapshot().epoch, 1);
        let v = cell.version();
        assert_eq!(
            cell.on_session(State::Closed, false, cause("server closed")),
            Some(SessionState::Connecting)
        );
        let info = cell.snapshot();
        assert!(info.disconnected_since.is_some());
        assert_eq!(info.last_error.unwrap().to_string(), "server closed");
        assert!(cell.version() > v);
        assert_eq!(
            cell.on_session(State::Connecting, false, LastErrorUpdate::Keep),
            None
        );
        assert_eq!(
            cell.on_session(State::ReadOnly, false, LastErrorUpdate::Keep),
            Some(SessionState::ConnectedReadOnly)
        );
        let info = cell.snapshot();
        assert_eq!(info.epoch, 2);
        assert!(info.disconnected_since.is_none());
        assert!(info.last_error.is_some(), "a connect keeps the last error");
    }

    #[test]
    fn a_new_last_error_changes_the_value_but_not_the_state() {
        let cell = StateCell::new();
        let v = cell.version();
        assert_eq!(
            cell.on_session(State::Connecting, false, cause("receive timeout")),
            None
        );
        assert_eq!(cell.version(), v.saturating_add(1));
        assert!(cell.snapshot().disconnected_since.is_none());
        assert_eq!(
            cell.on_session(State::Connecting, false, LastErrorUpdate::Clear),
            None
        );
        assert!(cell.snapshot().last_error.is_none());
        assert_eq!(cell.version(), v.saturating_add(2));
        // Subsequent clear invocations are idempotent and do not increment the version.
        cell.on_session(State::Connecting, false, LastErrorUpdate::Clear);
        assert_eq!(cell.version(), v.saturating_add(2));
        cell.set_last_error(Arc::new(std::io::Error::other("tls")));
        assert_eq!(cell.version(), v.saturating_add(3));
        assert!(cell.snapshot().last_error.is_some());
        assert_eq!(cell.snapshot().state, SessionState::Connecting);
    }

    #[test]
    fn a_terminal_state_never_changes() {
        for (proto, closing, public, err) in [
            (
                State::ExpiredSession,
                false,
                SessionState::Expired,
                Error::SessionExpired,
            ),
            (
                State::AuthFailed,
                false,
                SessionState::AuthFailed,
                Error::AuthFailed,
            ),
            (State::Closed, true, SessionState::Closed, Error::Closing),
        ] {
            let cell = StateCell::new();
            cell.on_session(State::Connected, false, LastErrorUpdate::Keep);
            assert!(cell.terminal_error().is_none());
            assert_eq!(
                cell.on_session(proto, closing, LastErrorUpdate::Keep),
                Some(public)
            );
            assert!(cell.snapshot().disconnected_since.is_some());
            assert_eq!(cell.terminal_error(), Some(err.clone()));
            let v = cell.version();
            assert_eq!(
                cell.on_session(State::Connected, false, LastErrorUpdate::Keep),
                None
            );
            assert_eq!(
                cell.on_session(State::Closed, true, LastErrorUpdate::Clear),
                None
            );
            assert!(!cell.set_closed());
            assert_eq!(cell.version(), v);
            assert_eq!(cell.snapshot().state, public);
            assert_eq!(cell.terminal_error(), Some(err));
        }
    }

    #[test]
    fn set_closed_moves_to_closed_once() {
        let cell = StateCell::new();
        assert!(cell.terminal_error().is_none());
        assert!(cell.set_closed());
        assert!(!cell.set_closed());
        assert_eq!(cell.snapshot().state, SessionState::Closed);
        assert_eq!(cell.terminal_error(), Some(Error::Closing));
        // Disconnection timestamps are not set if a connection was never established.
        assert!(cell.snapshot().disconnected_since.is_none());
    }

    #[test]
    fn changed_resolves_at_the_next_change_and_wakes_every_watcher() {
        let cell = StateCell::new();
        let mut a = StateWatcher::new(Arc::clone(&cell));
        let mut b = a.clone();
        let ca = Arc::new(CountWake(AtomicUsize::new(0)));
        let cb = Arc::new(CountWake(AtomicUsize::new(0)));
        let wa = Waker::from(Arc::clone(&ca));
        let wb = Waker::from(Arc::clone(&cb));
        // An active future maintains a single waker registration regardless of poll frequency.
        let mut fa = a.changed();
        let mut fb = b.changed();
        let pa = |f: &mut Changed<'_>| Pin::new(f).poll(&mut Context::from_waker(&wa));
        assert!(pa(&mut fa).is_pending());
        assert!(pa(&mut fa).is_pending());
        assert!(
            Pin::new(&mut fb)
                .poll(&mut Context::from_waker(&wb))
                .is_pending()
        );
        assert_eq!(cell.st.lock_unpoisoned().wakers.len(), 2);
        cell.on_session(State::Connected, false, LastErrorUpdate::Keep);
        assert_eq!(ca.0.load(Ordering::SeqCst), 1);
        assert_eq!(cb.0.load(Ordering::SeqCst), 1);
        match pa(&mut fa) {
            Poll::Ready(info) => assert_eq!(info.state, SessionState::Connected),
            Poll::Pending => panic!("no change"),
        }
        drop(fa);
        assert!(poll(&mut a, &wa).is_pending());
        // Rapid sequential updates coalesce, with the epoch reflecting the reconnect event.
        cell.on_session(State::Closed, false, LastErrorUpdate::Keep);
        cell.on_session(State::Connected, false, LastErrorUpdate::Keep);
        match Pin::new(&mut fb).poll(&mut Context::from_waker(&wb)) {
            Poll::Ready(info) => {
                assert_eq!(info.state, SessionState::Connected);
                assert_eq!(info.epoch, 2);
            }
            Poll::Pending => panic!("no change"),
        }
        drop(fb);
        assert!(poll(&mut b, &wb).is_pending());
    }

    #[test]
    fn changed_resolves_at_once_after_a_terminal_state() {
        let cell = StateCell::new();
        let mut w = StateWatcher::new(Arc::clone(&cell));
        cell.on_session(State::ExpiredSession, false, LastErrorUpdate::Keep);
        for _ in 0..3 {
            match poll(&mut w, Waker::noop()) {
                Poll::Ready(info) => assert_eq!(info.state, SessionState::Expired),
                Poll::Pending => panic!("a terminal state must resolve at once"),
            }
        }
        // Watchers instantiated after terminal state entry resolve immediately on the first poll.
        let mut late = StateWatcher::new(Arc::clone(&cell));
        assert!(poll(&mut late, Waker::noop()).is_ready());
        let mut seen = cell.version();
        assert!(cell.wait_changed(&mut seen, Some(soon())).is_some());
    }

    #[test]
    fn a_panicking_waker_does_not_stop_the_change() {
        struct Boom;
        impl Wake for Boom {
            fn wake(self: Arc<Self>) {
                std::panic::panic_any("waker panic");
            }
        }
        let cell = StateCell::new();
        let mut w = StateWatcher::new(Arc::clone(&cell));
        assert!(poll(&mut w, &Waker::from(Arc::new(Boom))).is_pending());
        assert_eq!(
            cell.on_session(State::Connected, false, LastErrorUpdate::Keep),
            Some(SessionState::Connected)
        );
        assert!(poll(&mut w, Waker::noop()).is_ready());
    }

    #[test]
    fn wait_changed_times_out_and_wakes() {
        let cell = StateCell::new();
        let mut seen = cell.version();
        assert!(cell.wait_changed(&mut seen, Some(soon())).is_none());
        let other = Arc::clone(&cell);
        let t = std::thread::spawn(move || {
            other.on_session(State::Connected, false, LastErrorUpdate::Keep)
        });
        let info = cell.wait_changed(&mut seen, None).unwrap();
        assert!(info.state == SessionState::Connected);
        assert_eq!(seen, cell.version());
        assert_eq!(t.join().unwrap(), Some(SessionState::Connected));
    }

    #[cfg(feature = "blocking")]
    #[test]
    fn the_watcher_wait_is_refused_on_the_io_side() {
        let cell = StateCell::new();
        let mut w = StateWatcher::new(Arc::clone(&cell));
        {
            let _io = crate::sync::enter_io_side(3);
            assert_eq!(w.wait_changed().unwrap_err(), Error::InvalidState);
        }
        cell.set_closed();
        assert_eq!(w.wait_changed().unwrap().state, SessionState::Closed);
    }

    #[test]
    fn a_new_cell_is_connecting() {
        let cell = StateCell::new();
        let w = StateWatcher::new(Arc::clone(&cell));
        assert_eq!(w.current().state, SessionState::Connecting);
        assert_eq!(w.current().epoch, 0);
        assert!(w.last_error().is_none());
    }
}
