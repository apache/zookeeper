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

//! Driver outbox architecture for staging and dispatching protocol side effects and callbacks.
//!
//! To preserve safety across the C FFI boundary, drop-in driver variants never execute application
//! callbacks or logging hooks directly from within internal protocol step contexts. Instead, each
//! driver driving step accumulates pending events, log messages, and completions into an
//! [`Outbox`]. The foreign caller drains these items sequentially once the Rust execution stack
//! frames have unwound. Native Rust driver runtimes utilize the same outbox abstractions to route
//! events directly into the client event channel.
//!
//! Architectural highlights:
//! - [`pump`] transfers protocol outputs from an active connection into the outbox in strict
//!   causal order, pausing execution whenever an event barrier (such as SASL negotiation or state
//!   transition) is encountered.
//! - FIFO-bound outputs (queued asynchronous completions, watchers, and session events) are
//!   steered according to the selected driver flavor: multithreaded (`Mt`) handles stage them as
//!   publish entries targeting the shared dispatch queue, single threaded (`St`) handles route
//!   them into a handle-local FIFO, and native runtimes process them inline.
//! - Publish-on-take semantics: when [`Outbox::next`] or [`Outbox::pop`] encounters a publish
//!   entry, it commits the item to the dispatch FIFO before yielding subsequent items. This
//!   guarantees that log records generated prior to a completion are emitted before worker threads
//!   observe the completion. If no unconsumed items precede a publish entry, it is forwarded
//!   immediately during the pump phase, preventing concurrent thread pumps from racing.
//! - Close markers: multithreaded connection shutdown appends a terminal close marker following
//!   its final publish entry. Once drained, the dispatch FIFO records that all outstanding events
//!   have been published, signaling the completion thread that it may safely terminate.
//! - Requeuing: [`Outbox::requeue`] preserves unconsumed entries if an application callback
//!   unwinds or raises an error, ensuring deterministic recovery during subsequent driver
//!   invocations.

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::Arc;

use zk_proto::{
    Connection, LogRecord, OpResult, Output, Route, SaslCall, State, Stopped, TerminateReason,
    Tokens, WatchedEvent,
};

use crate::host::{Discarded, HostLeaves};

/// Represents an uncommitted side effect or callback action destined for application or FFI
/// execution.
pub enum Item<T: Tokens> {
    /// Formatted diagnostic log entry to be emitted on the invoking thread.
    Log(LogRecord),
    /// Asynchronous operation completion or synchronous direct result.
    Result {
        /// Opaque token identifying the target request completion.
        token: T::Completion,
        /// Result payload or error code returned by the ZooKeeper server.
        result: OpResult,
        /// Diagnostic log lines to be flushed prior to invoking the callback.
        delivery_logs: Vec<LogRecord>,
    },
    /// Result of an explicit authentication request (`zoo_add_auth`).
    AuthResult {
        /// Authentication request token.
        token: T::Auth,
        /// Legacy ZooKeeper return code indicating authentication success or failure.
        rc: i32,
    },
    /// Data or child node watcher notification.
    Watch {
        /// Watched event payload containing state, path, and event type.
        event: WatchedEvent,
        /// Target watcher callbacks in registration order.
        watchers: Vec<T::Watcher>,
        /// Diagnostic log lines to be emitted prior to invoking watcher callbacks.
        delivery_logs: Vec<LogRecord>,
    },
    /// Global session state transition notification.
    Session {
        /// New connection lifecycle state.
        state: State,
        /// Registered session watchers to notify.
        watchers: Vec<T::Watcher>,
        /// Diagnostic log lines to be emitted prior to invoking session callbacks.
        delivery_logs: Vec<LogRecord>,
    },
    /// Termination notification for registered watchers when the session closes (native driver
    /// only).
    WatchTerminated {
        /// Watcher instances being invalidated.
        watchers: Vec<T::Watcher>,
        /// Specific reason for session termination.
        reason: TerminateReason,
    },
    /// Synchronization barrier requiring acquisition and release of `zh->auth_lock` in C FFI.
    AuthLockpoint,
    /// SASL authentication negotiation step requiring Cyrus SASL interaction.
    Sasl(SaslItem),
    /// OS thread lifecycle operation (join or detach) in multithreaded mode. Always acts as a
    /// barrier.
    Thread {
        /// Thread management operation to execute.
        op: ThreadOp,
        /// Target worker thread role.
        role: ThreadRole,
    },
}

impl<T: Tokens> std::fmt::Debug for Item<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let kind = match self {
            Self::Log(_) => "Log",
            Self::Result { .. } => "Result",
            Self::AuthResult { .. } => "AuthResult",
            Self::Watch { .. } => "Watch",
            Self::Session { .. } => "Session",
            Self::WatchTerminated { .. } => "WatchTerminated",
            Self::AuthLockpoint => "AuthLockpoint",
            Self::Sasl(_) => "Sasl",
            Self::Thread { .. } => "Thread",
        };
        f.write_str(kind)
    }
}

/// Parameters for a protocol SASL handshake negotiation step.
#[derive(Debug)]
pub struct SaslItem {
    /// Target SASL negotiation action (start, step, or evaluate).
    pub call: SaslCall,
    /// Local and peer socket addresses required during initial SASL start negotiation.
    pub addrs: Option<(SocketAddr, SocketAddr)>,
}

/// Execution outcome returned by an individual driver polling step (`zkrs_step`).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Step {
    /// Step completed successfully with the specified legacy return code.
    Done(i32),
    /// Execution paused at a barrier; caller must drain the outbox and invoke resume.
    More,
    /// Connection shutdown complete; foreign host should deallocate handle resources.
    Release,
    /// Background driver thread should terminate its event loop.
    Exit,
}

/// Thread management action associated with a [`Item::Thread`] event.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ThreadOp {
    /// Synchronously wait for thread termination via `pthread_join`.
    Join,
    /// Release thread resources independently via `pthread_detach`.
    Detach,
}

/// Identifies the role of a background worker thread in multithreaded mode.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ThreadRole {
    /// Dedicated network I/O and socket polling thread.
    Io,
    /// Dedicated callback and watcher completion dispatch thread.
    Dispatch,
}

/// Execution model determining how asynchronous completion events are buffered and routed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Flavor {
    /// multithreaded C drop-in driver routing completions to a shared dispatch queue.
    Mt,
    /// single threaded C drop-in driver buffering completions in a handle-local FIFO queue.
    St,
    /// Native Rust driver streaming completions directly to asynchronous caller channels.
    Native,
}

/// Suspended driver execution phase to be restored upon subsequent resume calls.
///
/// When a driver step yields [`Step::More`], the corresponding resume state is preserved
/// within the outbox. Because the C API entry point `zkrs_resume` does not pass execution
/// context explicitly, the driver reads this state to resume the suspended task.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ResumeAt {
    /// Initial connection establishment and handle initialization.
    Init,
    /// single threaded socket interest computation (`zookeeper_interest`).
    Interest,
    /// single threaded network I/O processing (`zookeeper_process`).
    Process,
    /// multithreaded background network event loop iteration (`zkrs_io_step`).
    IoStep,
    /// Graceful connection termination phase preceding final handle release.
    Close,
    /// Final shutdown phase freeing underlying network and protocol resources.
    FinalClose,
    /// Dynamic server ensemble update initiated via `zoo_set_servers`.
    SetServers(u64),
}

/// Sink interface for enqueueing completion items into a multithreaded dispatch queue.
pub trait Publish<T: Tokens>: Send + Sync {
    /// Enqueues an item into the dispatch queue and wakes the worker thread.
    fn publish(&self, item: Item<T>);

    /// Records that all pending events from a closing connection have been published,
    /// allowing the dispatch worker thread to terminate once the queue drains.
    fn close_published(&self) {}
}

enum Entry<T: Tokens> {
    Item(Item<T>),
    Publish(Item<T>),
    ClosePublished,
}

/// Ordered container buffering outbound events, log records, and completions for a driver step.
pub struct Outbox<T: Tokens> {
    entries: VecDeque<Entry<T>>,
    /// Number of items visible to the foreign C caller.
    items: usize,
    publish: Option<Arc<dyn Publish<T>>>,
    resume: Option<ResumeAt>,
    frame: Option<u64>,
}

/// Preserved unconsumed outbox entries retained after an interrupted driver step.
pub struct Requeued<T: Tokens> {
    entries: VecDeque<Entry<T>>,
    resume: Option<ResumeAt>,
    frame: Option<u64>,
}

impl<T: Tokens> Requeued<T> {
    /// Returns `true` if no unconsumed entries remain in the requeued set.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Returns the suspended execution step associated with the requeued outbox.
    #[must_use]
    pub fn resume_at(&self) -> Option<ResumeAt> {
        self.resume
    }

    /// Returns the single threaded driving frame identifier associated with the outbox.
    #[must_use]
    pub fn frame(&self) -> Option<u64> {
        self.frame
    }
}

impl<T: Tokens> Default for Outbox<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T: Tokens> Outbox<T> {
    /// Creates an empty outbox without an attached dispatch queue.
    #[must_use]
    pub fn new() -> Self {
        Self {
            entries: VecDeque::new(),
            items: 0,
            publish: None,
            resume: None,
            frame: None,
        }
    }

    /// Creates an empty outbox configured to route completions to the specified dispatch queue.
    #[must_use]
    pub fn with_publish(publish: Arc<dyn Publish<T>>) -> Self {
        Self {
            entries: VecDeque::new(),
            items: 0,
            publish: Some(publish),
            resume: None,
            frame: None,
        }
    }

    /// Disables background dispatch queue publishing for this outbox, converting all pending
    /// publish entries into standard direct items. Invoked during completion thread teardown.
    pub(crate) fn deliver_here(&mut self) {
        self.publish = None;
        let mut moved = 0usize;
        self.entries = std::mem::take(&mut self.entries)
            .into_iter()
            .map(|entry| match entry {
                Entry::Publish(item) => {
                    moved = moved.saturating_add(1);
                    Entry::Item(item)
                }
                other => other,
            })
            .collect();
        self.items = self.items.saturating_add(moved);
    }

    /// Appends a new item visible to the foreign C caller.
    pub fn push(&mut self, item: Item<T>) {
        self.entries.push_back(Entry::Item(item));
        self.items = self.items.saturating_add(1);
    }

    /// Appends a completion item subject to dispatch queue routing rules.
    ///
    /// If an active publish queue is attached and no preceding entries are pending in the outbox,
    /// the item is dispatched immediately to prevent out-of-order execution against concurrent
    /// threads.
    pub fn push_publish(&mut self, item: Item<T>) {
        match &self.publish {
            Some(p) if self.entries.is_empty() => p.publish(item),
            Some(_) => self.entries.push_back(Entry::Publish(item)),
            None => self.push(item),
        }
    }

    /// Enqueues a shutdown marker signaling that all events preceding handle closure have been
    /// published.
    pub(crate) fn push_close_published(&mut self) {
        if self.publish.is_some() {
            self.entries.push_back(Entry::ClosePublished);
        }
    }

    /// Appends a sequence of items in order to the outbox.
    pub fn extend_items<I: IntoIterator<Item = Item<T>>>(&mut self, items: I) {
        for item in items {
            self.push(item);
        }
    }

    /// Returns `true` if the outbox contains no pending entries.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Returns the total number of entries currently stored in the outbox.
    #[must_use]
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Returns `true` if one or more unconsumed items are waiting for the foreign caller.
    #[must_use]
    pub(crate) fn holds_items(&self) -> bool {
        self.items > 0
    }

    /// Returns `true` if any pending log records are queued, indicating a log flush barrier.
    #[must_use]
    pub fn has_pending_logs(&self) -> bool {
        self.entries
            .iter()
            .any(|e| matches!(e, Entry::Item(Item::Log(_))))
    }

    /// Extracts the next item for foreign C consumption, honoring publish-on-take semantics.
    ///
    /// Any preceding publish entries or close markers are transferred to the dispatch queue
    /// before the next caller-visible item is returned.
    pub fn pop(&mut self) -> Option<Item<T>> {
        while let Some(entry) = self.entries.pop_front() {
            match entry {
                Entry::Item(item) => {
                    self.items = self.items.saturating_sub(1);
                    return Some(item);
                }
                Entry::Publish(item) => match &self.publish {
                    Some(p) => p.publish(item),
                    None => {
                        self.items = self.items.saturating_sub(1);
                        return Some(item);
                    }
                },
                Entry::ClosePublished => self.mark_close_published(),
            }
        }
        None
    }

    /// Forwards the close publication marker to the dispatch queue if attached.
    fn mark_close_published(&self) {
        if let Some(p) = &self.publish {
            p.close_published();
        }
    }

    /// Records the suspended execution step to be resumed on the next driver invocation.
    pub fn set_resume(&mut self, at: ResumeAt) {
        self.resume = Some(at);
    }

    /// Returns the suspended execution step, if any.
    #[must_use]
    pub fn resume_at(&self) -> Option<ResumeAt> {
        self.resume
    }

    /// Consumes and returns the suspended execution step.
    pub fn take_resume(&mut self) -> Option<ResumeAt> {
        self.resume.take()
    }

    /// Associates this outbox with a specific single threaded driving frame identifier.
    pub fn set_frame(&mut self, id: u64) {
        self.frame = Some(id);
    }

    /// Returns the driving frame identifier associated with this outbox, if set.
    #[must_use]
    pub fn frame(&self) -> Option<u64> {
        self.frame
    }

    /// Extracts all unconsumed entries and the suspended step into a [`Requeued`] snapshot.
    pub fn requeue(&mut self) -> Requeued<T> {
        self.items = 0;
        Requeued {
            entries: std::mem::take(&mut self.entries),
            resume: self.resume.take(),
            frame: self.frame.take(),
        }
    }

    /// Prepends previously requeued entries ahead of current outbox items, preserving FIFO
    /// ordering.
    pub fn restore(&mut self, mut requeued: Requeued<T>) {
        // When no dispatch queue is attached, publish entries are treated as caller-visible
        // items.
        let local = self.publish.is_none();
        let restored = requeued
            .entries
            .iter()
            .filter(|e| match e {
                Entry::Item(_) => true,
                Entry::Publish(_) => local,
                Entry::ClosePublished => false,
            })
            .count();
        self.items = self.items.saturating_add(restored);
        requeued.entries.append(&mut self.entries);
        self.entries = requeued.entries;
    }

    /// Discards unconsumed outbox entries and notifies host token reclaimers.
    pub fn finish(&mut self, host: &dyn HostLeaves<T>) {
        self.items = 0;
        while let Some(entry) = self.entries.pop_front() {
            match entry {
                Entry::Publish(item) => match &self.publish {
                    Some(p) => p.publish(item),
                    None => discard_item(host, item),
                },
                Entry::Item(item) => discard_item(host, item),
                Entry::ClosePublished => self.mark_close_published(),
            }
        }
    }
}

impl<T: Tokens> Iterator for Outbox<T> {
    type Item = Item<T>;

    /// Yields the next available item, delegating directly to [`Outbox::pop`].
    fn next(&mut self) -> Option<Item<T>> {
        self.pop()
    }
}

/// Releases any resource tokens held within an unconsumed item back to the host environment.
pub fn discard_item<T: Tokens>(host: &dyn HostLeaves<T>, item: Item<T>) {
    match item {
        Item::Result { token, .. } => host.discard_token(Discarded::Completion(token)),
        Item::AuthResult { token, .. } => host.discard_token(Discarded::Auth(token)),
        Item::Watch { watchers, .. }
        | Item::Session { watchers, .. }
        | Item::WatchTerminated { watchers, .. } => {
            for w in watchers {
                host.discard_token(Discarded::Watcher(w));
            }
        }
        Item::Log(_) | Item::AuthLockpoint | Item::Sasl(_) | Item::Thread { .. } => {}
    }
}

/// Target destination for FIFO-ordered asynchronous completion events during outbox pumping.
pub enum FifoBound<'a, T: Tokens> {
    /// multithreaded driver mode: buffer as publish entries for the dispatch worker queue.
    Publish,
    /// single threaded driver mode: buffer into the handle-local FIFO queue. A session or drain
    /// point barrier moves the accumulated FIFO contents into the outbox.
    Local(&'a mut VecDeque<Item<T>>),
    /// Native driver mode: route items directly into the current outbox.
    Inline,
}

/// Outcome of draining protocol outputs into an outbox.
#[derive(Debug, PartialEq, Eq)]
pub enum Pumped {
    /// All available protocol outputs have been drained into the outbox.
    Drained,
    /// Output processing paused at an execution barrier or suspended protocol state.
    Barrier(Option<Stopped>),
}

/// Drains output events from the protocol state machine into the target outbox in causal order.
///
/// Processing rules:
/// - Synchronous `Completion { route: Direct, .. }` outputs are converted to [`Item::Result`] and
///   act as execution barriers so synchronous callers receive their results inline.
/// - Asynchronous `Completion { route: Queued, .. }`, `Watch`, and `Session` events are routed to
///   the `bound` destination ([`FifoBound::Publish`] for multithreaded dispatch,
///   [`FifoBound::Local`] for the single threaded handle FIFO, or [`FifoBound::Inline`] for native
///   drivers).
/// - Authentication responses (`Output::Auth`) produce one [`Item::AuthResult`] per token. In
///   multithreaded mode, `pump` yields a barrier immediately after staging non-empty auth results
///   so the I/O thread invokes the auth callback before decoding subsequent server packets (which
///   allows an auth callback to call `zookeeper_close` and fail remaining requests with
///   `ZCLOSING`).
/// - Client session identifier updates (`Output::ClientId`) are forwarded directly to
///   [`HostLeaves::store_client_id`] without creating an outbox item.
/// - Log records, `AuthLockpoint` barriers, and `WatchTerminated` notices become direct outbox
///   items.
/// - `Output::DrainPoint` and barrier `Output::Session` events flush all accumulated items from the
///   single threaded local FIFO (`FifoBound::Local`) into the outbox.
///
/// Output processing halts when `Connection::poll_output` yields `None`, or immediately after
/// staging multithreaded authentication results. In multithreaded mode with
/// [`FifoBound::Publish`], processing also suspends whenever caller-visible items
/// (`ob.holds_items()`) are held in the outbox and additional protocol outputs remain queued in
/// `Connection`. Keeping later outputs inside `Connection` until the current thread drains its
/// caller-visible items ensures that if an I/O-thread callback initiates `zookeeper_close`, the
/// close pump drains those earlier decoded replies ahead of its `ZCLOSING` cancellations and close
/// marker.
///
/// Returns [`Pumped::Barrier`] if an execution barrier or suspended protocol stage was
/// encountered.
pub fn pump<T: Tokens>(
    conn: &mut Connection<T>,
    ob: &mut Outbox<T>,
    mut bound: FifoBound<'_, T>,
    host: &dyn HostLeaves<T>,
) -> Pumped {
    let mut barrier = false;
    let mut drain_local = false;
    let publish = matches!(bound, FifoBound::Publish);
    loop {
        if publish && ob.holds_items() && conn.has_output() {
            barrier = true;
            break;
        }
        let Some(out) = conn.poll_output() else {
            break;
        };
        barrier = out.is_barrier();
        drain_local = false;
        match out {
            Output::Completion {
                token,
                route,
                result,
                delivery_logs,
            } => {
                let item = Item::Result {
                    token,
                    result,
                    delivery_logs,
                };
                match route {
                    Route::Direct => ob.push(item),
                    Route::Queued => to_bound(ob, &mut bound, item),
                }
            }
            Output::Watch {
                event,
                watchers,
                delivery_logs,
            } => to_bound(
                ob,
                &mut bound,
                Item::Watch {
                    event,
                    watchers,
                    delivery_logs,
                },
            ),
            Output::Session {
                state,
                watchers,
                barrier: is_barrier,
                delivery_logs,
            } => {
                to_bound(
                    ob,
                    &mut bound,
                    Item::Session {
                        state,
                        watchers,
                        delivery_logs,
                    },
                );
                drain_local = is_barrier;
            }
            Output::Auth { tokens, rc, .. } => {
                let stop = !tokens.is_empty() && publish;
                for token in tokens {
                    ob.push(Item::AuthResult { token, rc });
                }
                // In multithreaded mode, authentication callbacks run on the calling thread and
                // may initiate handle closure. Remaining outputs remain buffered in the protocol
                // engine.
                if stop {
                    barrier = true;
                    break;
                }
            }
            Output::AuthLockpoint => ob.push(Item::AuthLockpoint),
            Output::ClientId(id) => host.store_client_id(&id),
            Output::WatchTerminated { watchers, reason } => {
                ob.push(Item::WatchTerminated { watchers, reason });
            }
            Output::Log(rec) => ob.push(Item::Log(rec)),
            Output::DrainPoint => {
                barrier = true;
                drain_local = true;
            }
        }
    }
    if drain_local {
        if let FifoBound::Local(fifo) = &mut bound {
            ob.extend_items(fifo.drain(..));
        }
    }
    match conn.take_stopped() {
        Some(stopped) => Pumped::Barrier(Some(stopped)),
        None if barrier => Pumped::Barrier(None),
        None => Pumped::Drained,
    }
}

fn to_bound<T: Tokens>(ob: &mut Outbox<T>, bound: &mut FifoBound<'_, T>, item: Item<T>) {
    match bound {
        FifoBound::Publish => ob.push_publish(item),
        FifoBound::Local(fifo) => fifo.push_back(item),
        FifoBound::Inline => ob.push(item),
    }
}

// Internal unit tests verifying close marker behavior and dispatch ordering invariants.
#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::indexing_slicing)]

    use std::sync::Arc;

    use zk_proto::{LogRecord, LogSite};
    use zk_testkit::tokens::TestTokens;

    use super::*;
    use crate::sync::Mutex;
    use crate::testutil::TestHost;

    #[derive(Default)]
    struct Fifo(Mutex<Vec<Item<TestTokens>>>);

    impl Publish<TestTokens> for Fifo {
        fn publish(&self, item: Item<TestTokens>) {
            self.0.lock_owner().push(item);
        }
    }

    /// Test helper recording queue lengths at each observed close marker.
    #[derive(Default)]
    struct MarkFifo {
        items: Mutex<Vec<Item<TestTokens>>>,
        marks: Mutex<Vec<usize>>,
    }

    impl Publish<TestTokens> for MarkFifo {
        fn publish(&self, item: Item<TestTokens>) {
            self.items.lock_owner().push(item);
        }

        fn close_published(&self) {
            let n = self.items.lock_owner().len();
            self.marks.lock_owner().push(n);
        }
    }

    fn join_io() -> Item<TestTokens> {
        Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Io,
        }
    }

    fn log() -> Item<TestTokens> {
        Item::Log(LogRecord {
            site: LogSite::TcpNoDelay,
            level: LogSite::TcpNoDelay.level(),
            args: Vec::new(),
        })
    }

    #[test]
    fn the_close_mark_follows_the_earlier_publish_entries() {
        let fifo = Arc::new(MarkFifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push_publish(Item::AuthLockpoint);
        ob.push_publish(Item::AuthLockpoint);
        ob.push_close_published();
        ob.push(join_io());
        // Ensure close markers are routed to the dispatch FIFO rather than directly to the caller,
        // ordered after prior published items but ahead of subsequent thread lifecycle events.
        assert!(matches!(ob.pop(), Some(Item::Thread { .. })));
        assert_eq!(*fifo.marks.lock_owner(), [2]);
        assert!(ob.pop().is_none());
    }

    #[test]
    fn finish_gives_the_close_mark_to_the_fifo() {
        let host = TestHost::new();
        let fifo = Arc::new(MarkFifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push_publish(Item::AuthLockpoint);
        ob.push_close_published();
        ob.finish(&host);
        assert_eq!(*fifo.marks.lock_owner(), [1]);
        assert!(ob.is_empty());
    }

    #[test]
    fn a_requeued_close_mark_keeps_its_place() {
        let fifo = Arc::new(MarkFifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push_publish(Item::AuthLockpoint);
        ob.push_close_published();
        ob.push(join_io());
        let rest = ob.requeue();
        assert!(fifo.marks.lock_owner().is_empty());
        let mut next = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        next.push(log());
        next.restore(rest);
        assert!(matches!(next.pop(), Some(Item::Thread { .. })));
        assert_eq!(*fifo.marks.lock_owner(), [1]);
        assert!(matches!(next.pop(), Some(Item::Log(_))));
    }

    #[test]
    fn a_publish_entry_with_no_entry_ahead_goes_to_the_fifo_at_once() {
        let fifo = Arc::new(Fifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push_publish(Item::AuthLockpoint);
        assert!(ob.is_empty());
        assert_eq!(fifo.0.lock_owner().len(), 1);
        // An unconsumed item ahead in the outbox delays subsequent publish entries until
        // the preceding item is taken by the caller.
        ob.push(log());
        ob.push_publish(Item::AuthLockpoint);
        assert_eq!(ob.len(), 2);
        assert_eq!(fifo.0.lock_owner().len(), 1);
        assert!(matches!(ob.pop(), Some(Item::Log(_))));
        assert_eq!(fifo.0.lock_owner().len(), 1);
        assert!(ob.pop().is_none());
        assert_eq!(fifo.0.lock_owner().len(), 2);
    }

    #[test]
    fn holds_items_counts_only_the_entries_that_c_sees() {
        let host = TestHost::new();
        let fifo = Arc::new(Fifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push_publish(Item::AuthLockpoint);
        assert!(!ob.holds_items());
        ob.push(log());
        assert!(ob.holds_items());
        ob.push_publish(Item::AuthLockpoint);
        ob.push_close_published();
        assert!(matches!(ob.pop(), Some(Item::Log(_))));
        // Queued publish entries and close markers are internal and not counted as caller visible
        // items.
        assert_eq!(ob.len(), 2);
        assert!(!ob.holds_items());
        ob.extend_items([join_io()]);
        assert!(ob.holds_items());
        let rest = ob.requeue();
        assert!(!ob.holds_items());
        ob.restore(rest);
        assert!(ob.holds_items());
        assert!(matches!(ob.pop(), Some(Item::Thread { .. })));
        assert!(!ob.holds_items());
        assert_eq!(fifo.0.lock_owner().len(), 2);
        ob.push(log());
        ob.finish(&host);
        assert!(!ob.holds_items());
        // When no dispatch queue is attached, publish entries are treated as caller visible items.
        let mut plain: Outbox<TestTokens> = Outbox::new();
        plain.push_publish(Item::AuthLockpoint);
        assert!(plain.holds_items());
        plain.restore(requeue_with_a_publish_entry(&fifo));
        for _ in 0..2 {
            assert!(plain.pop().is_some());
            assert!(plain.holds_items());
        }
        assert!(plain.pop().is_some());
        assert!(!plain.holds_items());
    }

    /// Constructs a requeued snapshot containing an item followed by a pending publish entry.
    fn requeue_with_a_publish_entry(fifo: &Arc<Fifo>) -> Requeued<TestTokens> {
        let mut ob = Outbox::with_publish(Arc::clone(fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push(log());
        ob.push_publish(Item::AuthLockpoint);
        ob.requeue()
    }

    #[test]
    fn an_outbox_with_no_fifo_takes_no_close_mark() {
        let mut ob: Outbox<TestTokens> = Outbox::new();
        ob.push_close_published();
        assert!(ob.is_empty());
        // Default implementation of `close_published` is a no-op when no queue is active.
        let fifo = Arc::new(Fifo::default());
        let mut ob = Outbox::with_publish(Arc::clone(&fifo) as Arc<dyn Publish<TestTokens>>);
        ob.push_close_published();
        assert_eq!(ob.len(), 1);
        assert!(ob.pop().is_none());
        assert!(fifo.0.lock_owner().is_empty());
    }
}
