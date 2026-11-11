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
//! Internal requests submitted by the follower bypass user-facing read gates because they do not
//! allocate response channels.

use std::sync::Mutex;

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
/// Deactivates the follower if submission is rejected.
pub(crate) fn read(shared: &Shared) {
    let id = shared.new_watch_id();
    if !shared.router.follow.arm(id) {
        return;
    }
    if !shared.submit_follow(ops::get_config(Some(id))) {
        stop(shared);
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
/// terminate the follower.
pub(crate) fn on_result(shared: &Shared, r: RawResult) {
    match decode::config(r) {
        Ok((cfg, _stat)) => {
            let Some(next) = cfg.client_connect_string() else {
                return;
            };
            if !shared.router.follow.take_change(&next) {
                return;
            }
            if let Ok(hosts) = connect_string::parse_update(&next) {
                // A return generation of zero indicates that the driver event loop has terminated.
                // The subsequent read will fail, cleanly shutting down the follower state machine.
                let _generation = shared.driver.set_servers(hosts);
            }
        }
        Err(Error::ConnectionLoss | Error::OperationTimeout | Error::RequestTimeout) => {
            read(shared);
        }
        // The read completed successfully and armed its watch, but payload decoding failed (e.g.
        // standalone servers presenting empty configuration data). The watch remains active to
        // catch future valid updates.
        Err(Error::MarshallingError) => {}
        Err(_) => stop(shared),
    }
}

/// Processes node mutation watch triggers, scheduling a configuration re-read if the event targets
/// the follower's watch.
pub(crate) fn on_watch(shared: &Shared, watchers: &[WatchId]) -> bool {
    if !shared.router.follow.owns_any(watchers) {
        return false;
    }
    read(shared);
    true
}

/// Handles watcher termination events, scheduling re-reads on transient connection drops and
/// deactivating on terminal sessions.
pub(crate) fn on_terminated(shared: &Shared, watchers: &[WatchId], reason: TerminateReason) {
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
    fn only_a_new_string_applies() {
        let f = ConfigFollow::default();
        assert!(f.take_change("a:1"));
        assert!(!f.take_change("a:1"));
        assert!(f.take_change("a:1,b:2"));
    }
}
