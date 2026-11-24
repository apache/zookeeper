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

//! Background dynamic ensemble configuration follower driven by `update_servers_from_config`.
//!
//! Maintains an active watch on `/zookeeper/config` to track membership mutations in real time.
//! Operates purely reactively within the background I/O event loop and router without spawning
//! dedicated threads or asynchronous tasks.
//!
//! When `/zookeeper/config` changes, the follower decodes the updated cluster configuration and
//! extracts client facing endpoints. If the synthesized connection string differs from the active
//! ensemble, the follower dynamically updates the driver's server list via `set_servers`. Watch
//! triggers schedule subsequent configuration reads, keeping the watch armed. Transient connection
//! drops schedule fresh reads upon reconnect, while terminal errors deactivate the follower.
//!
//! The follower stays active when a read cannot go to the server for a transient reason:
//!
//! - A server `ThrottledOp` reply causes an immediate re-read. The server sends this reply only
//!   after it held the request for its throttle wait time, so the re-read does not spin.
//! - A full request queue (`max_pending_requests`) refuses the read with `TooManyPendingRequests`.
//!   The follower then waits for the next request completion and submits the read again. Only
//!   completions free queue places, and each completion wakes the waiters of `Client::ready`.
//!
//! The follower logs a warning when it defers a read and when it stops on an error that does not
//! end the session.
//!
//! Internal requests submitted by the follower bypass user-facing read gates because they do not
//! allocate response channels.

use std::sync::{Arc, Mutex, Weak};
use std::task::{Wake, Waker};

use zk_proto::TerminateReason;

use crate::connect_string;
use crate::decode;
use crate::error::Error;
use crate::ops;
use crate::shared::Shared;
use crate::sync::LockExt;
use crate::tokens::{RawResult, WatchId};

/// Internal state machine tracking active watches and dynamic cluster membership transitions.
#[derive(Debug, Default)]
pub(crate) struct ConfigFollow {
    inner: Mutex<Inner>,
}

/// Synchronized fields maintaining the configuration follower's state.
#[derive(Debug, Default)]
struct Inner {
    /// Active watch identifier associated with an in-flight read or armed watch on
    /// `/zookeeper/config`.
    watch: Option<WatchId>,
    /// Most recently applied ensemble connection string, preventing redundant server updates.
    applied: Option<String>,
    /// Terminal flag indicating whether the follower has been permanently deactivated.
    stopped: bool,
    /// Set when a full request queue refused a read and no later read has been submitted.
    deferred: bool,
    /// Set from the first deferral until a read is submitted, so that a long queue saturation
    /// logs only one warning.
    saturated: bool,
}

impl ConfigFollow {
    /// Registers an active watch identifier for the pending configuration read. Returns `false`
    /// if the follower has stopped.
    fn arm(&self, id: WatchId) -> bool {
        let mut s = self.inner.lock_unpoisoned();
        if s.stopped {
            return false;
        }
        s.watch = Some(id);
        true
    }

    /// Records that a read was submitted, which ends any queue saturation episode.
    fn sent(&self) {
        let mut s = self.inner.lock_unpoisoned();
        s.saturated = false;
    }

    /// Records a read that the full request queue refused. Returns `true` at the start of a
    /// saturation episode, when the caller must log a warning.
    fn defer(&self) -> bool {
        let mut s = self.inner.lock_unpoisoned();
        // The refused read registered no watch, so no event can carry its identifier.
        s.watch = None;
        s.deferred = true;
        !std::mem::replace(&mut s.saturated, true)
    }

    /// Claims a deferred read. Returns `true` to exactly one caller per deferral, and `false`
    /// if the follower stopped.
    fn take_deferred(&self) -> bool {
        let mut s = self.inner.lock_unpoisoned();
        let take = s.deferred && !s.stopped;
        s.deferred = false;
        take
    }

    /// Permanently deactivates the configuration follower and returns the current watch
    /// identifier, if armed.
    fn stop(&self) -> Option<WatchId> {
        let mut s = self.inner.lock_unpoisoned();
        s.stopped = true;
        s.watch.take()
    }

    /// Evaluates whether the active watch identifier is present in the specified slice of
    /// triggered watchers.
    fn owns_any(&self, watchers: &[WatchId]) -> bool {
        let s = self.inner.lock_unpoisoned();
        s.watch.is_some_and(|w| watchers.contains(&w))
    }

    /// Evaluates and records a candidate ensemble connection string, returning `true` if it
    /// represents a novel configuration.
    fn take_change(&self, next: &str) -> bool {
        let mut s = self.inner.lock_unpoisoned();
        if s.stopped || s.applied.as_deref() == Some(next) {
            return false;
        }
        s.applied = Some(next.to_owned());
        true
    }
}

/// Initiates an asynchronous configuration query and arms a watch on `/zookeeper/config`.
///
/// A full request queue defers the read until a later request completion. Any other refusal
/// deactivates the follower.
pub(crate) fn read(shared: &Arc<Shared>) {
    // The loop repeats only when queue room appeared while the refused read was deferred.
    loop {
        let id = shared.new_watch_id();
        if !shared.router.follow.arm(id) {
            return;
        }
        match shared.submit_follow(ops::get_config(Some(id))) {
            Ok(()) => {
                shared.router.follow.sent();
                return;
            }
            Err(Error::TooManyPendingRequests) => {
                if !defer(shared) {
                    return;
                }
            }
            Err(e) => {
                // Session-terminal errors end the entire session, which the application
                // observes, so only stops on other errors are logged.
                if !e.is_session_terminal() {
                    warn_stopped(&e);
                }
                stop(shared);
                return;
            }
        }
    }
}

/// Parks a read that the full request queue refused until the next request completion.
///
/// Returns `true` when queue room appeared before the wake registration and this caller claimed
/// the read, so the caller must submit it again.
fn defer(shared: &Arc<Shared>) -> bool {
    if shared.router.follow.defer() {
        warn_deferred();
    }
    // The router wakes the `Client::ready` waiters after each request completion, and only
    // completions free queue places. The engine has no internal request path past the
    // `max_pending_requests` gate, so the follower uses this wake to submit the read again.
    let resume = Arc::new(Resume(Arc::downgrade(shared)));
    shared.router.ready.register(&Waker::from(resume));
    // A completion can wake the waiters before the registration above. This second check
    // prevents a lost wake. `take_deferred` lets only one of the two paths submit the read.
    has_room(shared) && shared.router.follow.take_deferred()
}

/// Returns whether the request queue has a free place for the follower read.
fn has_room(shared: &Shared) -> bool {
    match shared.config.max_pending {
        None => true,
        Some(max) => shared.pending_len().is_some_and(|n| n < max),
    }
}

/// Wake handler that submits a deferred follower read.
///
/// It holds a weak reference, so a registered wake does not keep the client alive. The router
/// runs it on the thread that routes the completion, where the follower reads also run.
struct Resume(Weak<Shared>);

impl Wake for Resume {
    fn wake(self: Arc<Self>) {
        let Some(shared) = self.0.upgrade() else {
            return;
        };
        if shared.router.follow.take_deferred() {
            read(&shared);
        }
    }
}

/// Deactivates the configuration follower and detaches any active watcher from protocol
/// state tracking.
fn stop(shared: &Shared) {
    if let Some(id) = shared.router.follow.stop() {
        shared.detach_watch(id);
    }
}

/// Dispatches the completion of an asynchronous configuration read.
///
/// Decodes the returned configuration payload and propagates novel client endpoints to the
/// connection driver. Transient transport errors schedule immediate rereads, unparseable
/// configuration payloads leave the watch armed awaiting future updates, and protocol errors
/// terminate the follower. A server `ThrottledOp` reply also schedules an immediate reread.
/// Emits a diagnostic warning when a derived client connection string cannot be parsed into a
/// server list, leaving the server list unchanged, or when the follower stops on an error that
/// is not session-terminal.
pub(crate) fn on_result(shared: &Arc<Shared>, r: RawResult) {
    match decode::config(r) {
        Ok((cfg, _stat)) => {
            let Some(next) = cfg.client_connect_string() else {
                return;
            };
            if !shared.router.follow.take_change(&next) {
                return;
            }
            match connect_string::parse_update(&next) {
                Ok(hosts) => {
                    // A return generation of zero indicates that the driver event loop has
                    // terminated. The subsequent read will fail, cleanly shutting down the
                    // follower state machine.
                    let _generation = shared.driver.set_servers(hosts);
                }
                // The server list is left unchanged, while the watch armed by this read remains
                // active so subsequent configuration changes trigger a re-read. Because
                // `take_change` recorded the connection string, the same string does not emit
                // a redundant warning.
                Err(e) => warn_not_applied(&next, &e),
            }
        }
        // The server sends `ThrottledOp` only after it held the request for its throttle wait
        // time, so an immediate reread does not spin.
        Err(
            Error::ConnectionLoss
            | Error::OperationTimeout
            | Error::RequestTimeout
            | Error::ThrottledOp,
        ) => {
            read(shared);
        }
        // The read completed successfully and armed its watch, but payload decoding failed (e.g.
        // standalone servers presenting empty configuration data). The watch remains active to
        // catch future valid updates.
        Err(Error::MarshallingError) => {}
        Err(e) => {
            // Session-terminal errors end the entire session, which the application observes,
            // so only stops on other errors are logged.
            if !e.is_session_terminal() {
                warn_stopped(&e);
            }
            stop(shared);
        }
    }
}

/// Emits a diagnostic warning when a fetched ensemble configuration cannot be applied to the
/// server list.
fn warn_not_applied(hosts: &str, e: &Error) {
    #[cfg(feature = "tracing")]
    tracing::warn!(
        target: "apache_zookeeper::session",
        "ignoring the ensemble configuration {hosts:?}: {e}"
    );
    #[cfg(all(feature = "log", not(feature = "tracing")))]
    log::warn!(
        target: "apache_zookeeper::session",
        "ignoring the ensemble configuration {hosts:?}: {e}"
    );
    #[cfg(not(any(feature = "tracing", feature = "log")))]
    let _ = (hosts, e);
}

/// Emits a diagnostic warning when the full request queue defers a configuration read.
fn warn_deferred() {
    #[cfg(feature = "tracing")]
    tracing::warn!(
        target: "apache_zookeeper::session",
        "the ensemble configuration follower deferred a read: the request queue is full \
         (max_pending_requests)"
    );
    #[cfg(all(feature = "log", not(feature = "tracing")))]
    log::warn!(
        target: "apache_zookeeper::session",
        "the ensemble configuration follower deferred a read: the request queue is full \
         (max_pending_requests)"
    );
}

/// Emits a diagnostic warning when the configuration follower stops on an error.
fn warn_stopped(e: &Error) {
    #[cfg(feature = "tracing")]
    tracing::warn!(
        target: "apache_zookeeper::session",
        "the ensemble configuration follower stopped: {e}"
    );
    #[cfg(all(feature = "log", not(feature = "tracing")))]
    log::warn!(
        target: "apache_zookeeper::session",
        "the ensemble configuration follower stopped: {e}"
    );
    #[cfg(not(any(feature = "tracing", feature = "log")))]
    let _ = e;
}

/// Processes node mutation watch triggers, scheduling a configuration re-read if the event targets
/// the follower's watch.
pub(crate) fn on_watch(shared: &Arc<Shared>, watchers: &[WatchId]) -> bool {
    if !shared.router.follow.owns_any(watchers) {
        return false;
    }
    read(shared);
    true
}

/// Handles watcher termination events, scheduling re-reads on transient connection drops and
/// deactivating on terminal sessions.
pub(crate) fn on_terminated(shared: &Arc<Shared>, watchers: &[WatchId], reason: TerminateReason) {
    if !shared.router.follow.owns_any(watchers) {
        return;
    }
    match reason {
        TerminateReason::ConnectionLost => read(shared),
        TerminateReason::Removed
        | TerminateReason::SessionExpired
        | TerminateReason::AuthFailed
        | TerminateReason::Closed => stop(shared),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_stopped_follower_arms_nothing() {
        let f = ConfigFollow::default();
        assert!(f.arm(WatchId(1)));
        assert!(f.owns_any(&[WatchId(2), WatchId(1)]));
        assert!(!f.owns_any(&[WatchId(2)]));
        assert_eq!(f.stop(), Some(WatchId(1)));
        assert!(!f.arm(WatchId(3)));
        assert!(!f.owns_any(&[WatchId(3)]));
        assert!(!f.take_change("a:1"));
    }

    #[test]
    fn one_caller_claims_a_deferred_read() {
        let f = ConfigFollow::default();
        assert!(f.arm(WatchId(1)));
        assert!(!f.take_deferred());
        // The first deferral of an episode asks for a warning, and a repeated one does not.
        assert!(f.defer());
        assert!(!f.owns_any(&[WatchId(1)]));
        assert!(f.take_deferred());
        assert!(!f.take_deferred());
        assert!(!f.defer());
        f.sent();
        assert!(f.defer());
        let _ = f.stop();
        assert!(!f.take_deferred());
    }

    #[test]
    fn only_a_new_string_applies() {
        let f = ConfigFollow::default();
        assert!(f.take_change("a:1"));
        assert!(!f.take_change("a:1"));
        assert!(f.take_change("a:1,b:2"));
    }
}
