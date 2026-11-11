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

//! Outbound notification events, completions, and execution barrier coordination.
//!
//! To decouple protocol processing from application callback runtimes, the protocol engine
//! never executes user code directly. Instead, notifications, completions, watch triggers, and
//! session state transitions are sequenced into an ordered outbound queue retrieved via
//! `poll_output`. Barrier outputs temporarily suspend the originating protocol stage until the
//! driver processes the event and explicitly advances execution via `resume`.

use core::fmt;
use std::collections::VecDeque;

use zk_jute::data::{ACL, ClientInfo, Stat};
use zk_jute::multi::MultiItem;
use zk_jute::{JString, JVec};

use crate::auth::AuthReplyJob;
use crate::close::CloseJob;
use crate::config::ClientId;
use crate::hooks::Tokens;
use crate::log::LogRecord;
use crate::request::Route;
use crate::responses::ReplyData;
use crate::session::FinalizeJob;
use crate::state::State;
use crate::teardown::TeardownJob;

/// Asynchronous notification event delivered from the protocol engine to the driver or application.
pub enum Output<T: Tokens> {
    /// Request completion outcome.
    ///
    /// Requests routed via `Route::Queued` are dispatched asynchronously through the client's
    /// completion event thread. Requests routed via `Route::Direct` act as execution barriers,
    /// delivering results immediately on the thread polling outputs for synchronous invocations.
    Completion {
        /// Associated completion token provided at request submission.
        token: T::Completion,
        /// Dispatch routing policy governing delivery.
        route: Route,
        /// Decoded request outcome or error code.
        result: OpResult,
        /// Structured log records to be emitted by the delivering thread immediately prior to
        /// callback invocation.
        delivery_logs: Vec<LogRecord>,
    },
    /// Znode data, existence, or child change notification.
    ///
    /// The protocol engine evaluates matching watchers upon frame ingestion in strict delivery
    /// order. Under `LEGACY_C` compatibility profiles, this watcher collection may be empty if no
    /// local watchers matched.
    Watch {
        /// Watched event payload containing path, event type, and connection state.
        event: WatchedEvent,
        /// Matching watcher handles to invoke in registration order.
        watchers: Vec<T::Watcher>,
        /// Structured log records to emit prior to dispatching watch callbacks.
        delivery_logs: Vec<LogRecord>,
    },
    /// Cluster session state transition event.
    ///
    /// Under legacy compatibility rules, the global default watcher is evaluated last in sequence.
    Session {
        /// Newly established connection or session state.
        state: State,
        /// Watcher handles to notify of the session state transition.
        watchers: Vec<T::Watcher>,
        /// Indicates whether this output acts as an inline execution barrier
        /// (`SessionDelivery::Inline`).
        barrier: bool,
        /// Structured log records to emit prior to dispatching session callbacks.
        delivery_logs: Vec<LogRecord>,
    },
    /// Outcome of one or more asynchronous `add_auth` credential submissions.
    Auth {
        /// List of authentication completion tokens being settled.
        tokens: Vec<T::Auth>,
        /// Protocol return code reflecting authentication negotiation success or failure.
        rc: i32,
        /// Indicates whether subsequent protocol stages remain suspended pending driver handling.
        barrier: bool,
    },
    /// Execution synchronization barrier requiring the legacy C driver to acquire and release its
    /// authentication mutex.
    AuthLockpoint,
    /// Session credentials negotiated during connection handshake, delivered prior to the connected
    /// `Session` event.
    ClientId(ClientId),
    /// Watch registration termination event indicating watches dropped without triggering (native
    /// hook).
    WatchTerminated {
        /// Terminated watcher handles.
        watchers: Vec<T::Watcher>,
        /// Specific condition precipitating watch termination.
        reason: TerminateReason,
    },
    /// Structured diagnostic log record sequenced with surrounding protocol outputs.
    Log(LogRecord),
    /// Synchronization point instructing single threaded event loops to drain their internal
    /// dispatch queue.
    DrainPoint,
}

impl<T: Tokens> Output<T> {
    /// Indicates whether processing this output suspends the originating protocol stage until
    /// resumed.
    #[must_use]
    pub fn is_barrier(&self) -> bool {
        match self {
            Self::Completion { route, .. } => *route == Route::Direct,
            Self::Session { barrier, .. } | Self::Auth { barrier, .. } => *barrier,
            Self::AuthLockpoint => true,
            Self::Watch { .. }
            | Self::ClientId(_)
            | Self::WatchTerminated { .. }
            | Self::Log(_)
            | Self::DrainPoint => false,
        }
    }
}

impl<T: Tokens> fmt::Debug for Output<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Completion {
                route,
                result,
                delivery_logs,
                ..
            } => f
                .debug_struct("Completion")
                .field("route", route)
                .field("result", result)
                .field("delivery_logs", delivery_logs)
                .finish_non_exhaustive(),
            Self::Watch {
                event,
                watchers,
                delivery_logs,
            } => f
                .debug_struct("Watch")
                .field("event", event)
                .field("watchers", &watchers.len())
                .field("delivery_logs", delivery_logs)
                .finish(),
            Self::Session {
                state,
                watchers,
                barrier,
                delivery_logs,
            } => f
                .debug_struct("Session")
                .field("state", state)
                .field("watchers", &watchers.len())
                .field("barrier", barrier)
                .field("delivery_logs", delivery_logs)
                .finish(),
            Self::Auth {
                tokens,
                rc,
                barrier,
            } => f
                .debug_struct("Auth")
                .field("tokens", &tokens.len())
                .field("rc", rc)
                .field("barrier", barrier)
                .finish(),
            Self::AuthLockpoint => f.write_str("AuthLockpoint"),
            Self::ClientId(id) => f.debug_tuple("ClientId").field(id).finish(),
            Self::WatchTerminated { watchers, reason } => f
                .debug_struct("WatchTerminated")
                .field("watchers", &watchers.len())
                .field("reason", reason)
                .finish(),
            Self::Log(r) => f.debug_tuple("Log").field(r).finish(),
            Self::DrainPoint => f.write_str("DrainPoint"),
        }
    }
}

/// Root cause precipitating the termination and unregistration of active watchers without event
/// firing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TerminateReason {
    /// Explicit watch removal requested by the application.
    Removed,
    /// Underlying connection dropped without session resurrection or watch restoration.
    ConnectionLost,
    /// Server ensemble expired the client session.
    SessionExpired,
    /// Session authentication failed or credentials were rejected by the server.
    AuthFailed,
    /// Client handle was explicitly closed by the application.
    Closed,
}

/// Source origin indicating how a request outcome was generated within the protocol pipeline.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Origin {
    /// Result derived from a server response frame.
    Reply,
    /// Result synthesized locally during connection teardown, session termination, or client close.
    Teardown,
}

/// Unified outcome of an application protocol operation.
///
/// `reply` contains `None` when `origin` is `Teardown`, or when `rc != 0` for non-multi requests.
/// A successful `rc == 0` always provides a corresponding decoded `reply`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OpResult {
    /// Protocol return code. For `multi` transactions, reflects the first failing sub-operation
    /// error.
    pub rc: i32,
    /// ZooKeeper transaction identifier (zxid) from the response header, or 0 if synthesized
    /// locally.
    pub zxid: i64,
    /// Origin indicating whether this result was received from the server or generated during
    /// teardown.
    pub origin: Origin,
    /// Decoded response payload when available.
    pub reply: Option<Reply>,
}

impl OpResult {
    /// Constructs a synthesized teardown result carrying the specified return code with no reply
    /// payload.
    #[must_use]
    pub const fn teardown(rc: i32) -> Self {
        Self {
            rc,
            zxid: 0,
            origin: Origin::Teardown,
            reply: None,
        }
    }
}

/// Decoded response payload received from the server, with client chroot prefixes stripped from all
/// paths.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Reply {
    /// Empty response body (e.g., delete, setACL without stat, version check, removeWatches,
    /// addWatch).
    Void,
    /// Node metadata stat record (e.g., exists, setData, setACL).
    Stat(Stat),
    /// Node payload data accompanied by stat metadata (getData).
    Data {
        /// Node payload buffer, referencing zero-copy frame body slices when large.
        data: ReplyData,
        /// Current node stat metadata.
        stat: Stat,
    },
    /// Resulting znode path string (e.g., create, sync).
    Path(JString),
    /// Resulting znode path string and stat metadata (create2).
    PathStat(JString, Stat),
    /// Child node name list (getChildren).
    Children(JVec<JString>),
    /// Child node name list accompanied by parent stat metadata (getChildren2).
    Children2(JVec<JString>, Stat),
    /// Access Control List definitions and current ACL version stat (getACL).
    Acl(JVec<ACL>, Stat),
    /// Sequential list of individual sub-operation execution results (multi).
    Multi(Vec<MultiItem>),
    /// List of ephemeral znodes owned by the current session matching a prefix filter (native
    /// feature).
    Ephemerals(JVec<JString>),
    /// Recursive count of child znodes under a given subtree (native feature).
    ChildrenCount(i32),
    /// Client authentication identities recognized by the ensemble for this session (native
    /// feature).
    WhoAmI(JVec<ClientInfo>),
    /// Sequential list of read sub-operation results within a multi-read transaction (native
    /// feature).
    MultiRead(Vec<MultiItem>),
}

/// Znode state change or session notification delivered to watcher callbacks.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WatchedEvent {
    /// Event type identifier: 1 (Created), 2 (Deleted), 3 (Changed), 4 (Child), or extended native
    /// types.
    pub event_type: i32,
    /// Connection state code associated with the event generation.
    pub state: i32,
    /// Target znode path with chroot prefix stripped.
    pub path: JString,
    /// ZooKeeper transaction identifier (zxid) from the triggering notification frame header
    /// (native feature).
    pub zxid: i64,
}

/// Opaque continuation token representing a suspended protocol execution stage.
///
/// Designed without `Clone` to enforce linear single-use semantics, ensuring a suspended stage
/// is resumed at most once via `Connection::resume`.
#[derive(Debug, PartialEq, Eq, Hash)]
pub struct Stopped {
    id: u64,
}

impl Stopped {
    /// Returns the unique suspension identifier assigned to this continuation.
    #[must_use]
    pub const fn id(&self) -> u64 {
        self.id
    }
}

/// Deferred computation state of a suspended protocol execution stage.
pub(crate) enum Work<T: Tokens> {
    /// Protocol frame read suspended following direct reply delivery; execution resumes on
    /// subsequent input.
    Hold,
    /// Staged connection teardown pipeline in progress.
    Teardown(TeardownJob<T>),
    /// Graceful connection close sequence in progress.
    Close(CloseJob<T>),
    /// Continuation steps of an authentication reply following driver lockpoint synchronization.
    AuthReply(AuthReplyJob),
    /// Continuation steps of connection finalization following driver lockpoint synchronization.
    Finalize(FinalizeJob),
}

impl<T: Tokens> Work<T> {
    /// Extracts all completion tokens and authentication tokens held within this suspended work
    /// item.
    pub(crate) fn into_tokens(self) -> (Vec<T::Completion>, Vec<T::Auth>) {
        match self {
            Self::Hold | Self::AuthReply(_) | Self::Finalize(_) => (Vec::new(), Vec::new()),
            Self::Teardown(job) => job.into_tokens(),
            Self::Close(job) => job.into_tokens(),
        }
    }
}

/// Internal element within the outbound event and continuation queue.
enum Entry<T: Tokens> {
    /// Outbound event ready for driver polling.
    Out(Output<T>),
    /// Protocol stage parked behind preceding outputs awaiting driver barrier synchronization.
    Park(Work<T>),
}

/// Ordered outbound notification queue and stage continuation coordinator.
///
/// When an execution stage produces outputs culminating in a barrier, it parks its remaining
/// continuation work behind those outputs. As the driver polls outputs, encountering a parked work
/// item transitions it into a suspended state, returning `None` from subsequent polls until the
/// driver retrieves the stop token.
pub(crate) struct Outputs<T: Tokens> {
    queue: VecDeque<Entry<T>>,
    suspended: Vec<(u64, Work<T>)>,
    next_id: u64,
    pending_stop: Option<Stopped>,
    destroyed: bool,
}

impl<T: Tokens> Outputs<T> {
    /// Constructs an empty outbound queue.
    pub(crate) fn new() -> Self {
        Self {
            queue: VecDeque::new(),
            suspended: Vec::new(),
            next_id: 1,
            pending_stop: None,
            destroyed: false,
        }
    }

    /// Enqueues an outbound notification event.
    pub(crate) fn push(&mut self, out: Output<T>) {
        self.queue.push_back(Entry::Out(out));
    }

    /// Enqueues an optional structured log record as an output event.
    pub(crate) fn push_log(&mut self, record: Option<LogRecord>) {
        if let Some(r) = record {
            self.push(Output::Log(r));
        }
    }

    /// Parks remaining stage computation behind previously queued outputs, enforcing an execution
    /// barrier.
    pub(crate) fn park(&mut self, work: Work<T>) {
        self.queue.push_back(Entry::Park(work));
    }

    /// Retrieves the next available outbound event from the queue.
    ///
    /// Returns `None` when the queue is depleted or when an execution barrier has paused processing
    /// pending driver acquisition of the stop continuation handle.
    pub(crate) fn poll(&mut self) -> Option<Output<T>> {
        if self.pending_stop.is_some() {
            return None;
        }
        match self.queue.pop_front() {
            None => None,
            Some(Entry::Park(work)) => {
                self.stop(work);
                None
            }
            Some(Entry::Out(out)) => {
                if matches!(self.queue.front(), Some(Entry::Park(_))) {
                    if let Some(Entry::Park(work)) = self.queue.pop_front() {
                        self.stop(work);
                    }
                }
                Some(out)
            }
        }
    }

    /// Determines whether polling will immediately yield an output or trigger a stage suspension.
    ///
    /// Returns false if an unacknowledged stopped stage is currently blocking queue progression.
    pub(crate) fn has_output(&self) -> bool {
        self.pending_stop.is_none() && !self.queue.is_empty()
    }

    fn stop(&mut self, work: Work<T>) {
        let id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);
        self.suspended.push((id, work));
        self.pending_stop = Some(Stopped { id });
    }

    /// Retrieves the continuation token for the stage suspended by the most recently polled barrier
    /// output.
    pub(crate) fn take_stopped(&mut self) -> Option<Stopped> {
        self.pending_stop.take()
    }

    /// Reclaims the deferred work associated with a suspended continuation handle.
    ///
    /// Returns `None` if the handle is invalid or if the connection has been marked destroyed.
    pub(crate) fn take_suspended(&mut self, stopped: Stopped) -> Option<Work<T>> {
        if self.destroyed {
            return None;
        }
        let pos = self
            .suspended
            .iter()
            .position(|(id, _)| *id == stopped.id)?;
        Some(self.suspended.remove(pos).1)
    }

    /// Indicates whether any execution stage is currently suspended, parked, or awaiting
    /// continuation handle retrieval.
    pub(crate) fn is_suspended(&self) -> bool {
        !self.suspended.is_empty()
            || self.pending_stop.is_some()
            || self.queue.iter().any(|e| matches!(e, Entry::Park(_)))
    }

    /// Drains all parked and suspended stage continuations across the queue, clearing pending
    /// stops.
    pub(crate) fn take_all_work(&mut self) -> Vec<Work<T>> {
        self.pending_stop = None;
        let mut works: Vec<Work<T>> = self.suspended.drain(..).map(|(_, w)| w).collect();
        let mut kept = VecDeque::with_capacity(self.queue.len());
        for entry in self.queue.drain(..) {
            match entry {
                Entry::Park(w) => works.push(w),
                Entry::Out(o) => kept.push_back(Entry::Out(o)),
            }
        }
        self.queue = kept;
        works
    }

    /// Marks the output queue destroyed, causing subsequent `take_suspended` invocations to yield
    /// `None`.
    pub(crate) fn mark_destroyed(&mut self) {
        self.destroyed = true;
    }

    /// Indicates whether `mark_destroyed` has been asserted.
    #[cfg(test)]
    pub(crate) fn is_destroyed(&self) -> bool {
        self.destroyed
    }

    /// Splits the queue into discrete lists of pending outputs and deferred work continuations for
    /// leftover extraction.
    pub(crate) fn into_parts(mut self) -> (Vec<Output<T>>, Vec<Work<T>>) {
        let works = self.take_all_work();
        let outs = self
            .queue
            .into_iter()
            .filter_map(|e| match e {
                Entry::Out(o) => Some(o),
                Entry::Park(_) => None,
            })
            .collect();
        (outs, works)
    }
}

impl<T: Tokens> fmt::Debug for Outputs<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Outputs")
            .field("queued", &self.queue.len())
            .field("suspended", &self.suspended.len())
            .field("pending_stop", &self.pending_stop)
            .field("destroyed", &self.destroyed)
            .finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Tk;
    impl Tokens for Tk {
        type Completion = u32;
        type Watcher = u32;
        type Auth = u32;
    }

    fn lockpoint() -> Output<Tk> {
        Output::AuthLockpoint
    }

    #[test]
    fn barrier_stops_until_handle_taken() {
        let mut q: Outputs<Tk> = Outputs::new();
        q.push(Output::DrainPoint);
        q.push(lockpoint());
        q.park(Work::Hold);
        assert!(q.is_suspended());
        assert!(matches!(q.poll(), Some(Output::DrainPoint)));
        assert!(matches!(q.poll(), Some(Output::AuthLockpoint)));
        q.push(Output::DrainPoint);
        assert!(q.poll().is_none());
        let stop = q.take_stopped();
        assert!(stop.is_some());
        assert!(matches!(q.poll(), Some(Output::DrainPoint)));
        assert!(q.is_suspended());
        let work = stop.and_then(|s| q.take_suspended(s));
        assert!(matches!(work, Some(Work::Hold)));
        assert!(!q.is_suspended());
    }

    #[test]
    fn has_output_follows_the_queue_and_the_pending_stop() {
        let mut q: Outputs<Tk> = Outputs::new();
        assert!(!q.has_output());
        q.push(lockpoint());
        q.park(Work::Hold);
        q.push(Output::DrainPoint);
        assert!(q.has_output());
        assert!(matches!(q.poll(), Some(Output::AuthLockpoint)));
        // An active execution stop suspends queue progression until the driver claims its
        // continuation handle.
        assert!(!q.has_output());
        assert!(q.take_stopped().is_some());
        assert!(q.has_output());
        assert!(matches!(q.poll(), Some(Output::DrainPoint)));
        assert!(!q.has_output());
    }

    #[test]
    fn leading_park_and_destroy() {
        let mut q: Outputs<Tk> = Outputs::new();
        q.park(Work::Hold);
        assert!(q.poll().is_none());
        let stop = q.take_stopped();
        q.mark_destroyed();
        assert!(stop.and_then(|s| q.take_suspended(s)).is_none());
        assert_eq!(q.take_all_work().len(), 1);
        assert!(!q.is_suspended());
    }
}
