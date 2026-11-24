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

//! Shared client state coordination and driver request dispatching.
//!
//! [`Shared`] acts as the internal anchor referenced via [`Arc`] across client clones.
//! [`ResponseFuture`] and active watcher instances hold [`Weak<Shared>`] references, ensuring that
//! uncompleted futures or dormant watcher streams do not artificially keep the connection or
//! session open.
//!
//! Owns wire request submission, watcher sink registration, and watcher detachment coordination.
//! Preserves strict leaf locking and concurrency invariants. User supplied closures are never
//! invoked within this module, and internal synchronization primitives are never held across calls
//! into the I/O driver.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Weak};

use zk_jute::JString;
use zk_proto::{
    Connection, InputDefect, Origin, Reply, Request, Route, SecretBytes, SubmitError, Submitted,
};

use crate::decode::{self, Decoder};
use crate::driver::{DriverHandle, Gate};
use crate::error::{Error, map_submit_error};
use crate::future::{ResponseFuture, report_taken};
use crate::observe::OpKind;
use crate::ops::{self, NativeOp};
use crate::path::ZPathBuf;
use crate::route::Router;
use crate::slot::Slot;
use crate::sync::LockExt;
use crate::tokens::{AuthToken, NativeCompletion, NativeTokens, RawResult, ReplyToken, WatchId};
use crate::watch::{
    AddWatchMode, EventQueue, OneshotWatcher, PersistentWatcher, Sink, WatchOutcome, WatcherKind,
};

/// Result of submitting an operation frame into the background I/O driver queue.
type Sent = Result<Submitted<NativeTokens>, SubmitError<NativeTokens>>;

/// Monotonically increasing sequence generator for unique process-wide client identifiers
/// (initial identifier 1 leaves 0 as an unassigned sentinel).
static NEXT_CLIENT_ID: AtomicU64 = AtomicU64::new(1);

/// Allocates a unique, non-zero client identifier within the current process.
pub(crate) fn next_client_id() -> u64 {
    NEXT_CLIENT_ID.fetch_add(1, Ordering::Relaxed)
}

/// Immutable configuration options established during client initialization.
#[derive(Clone, Debug)]
pub(crate) struct ClientConfig {
    /// Maximum number of queued and in-flight requests permitted before backpressure stalls
    /// submission.
    pub(crate) max_pending: Option<usize>,
    /// Capacity bound for unbounded persistent watcher event queues before buffering triggers lag
    /// drops.
    pub(crate) persistent_watch_buffer: Option<usize>,
}

/// Shared core state backing public client facades, handling I/O dispatch and watcher
/// registration.
#[derive(Debug)]
pub(crate) struct Shared {
    /// Handle to the background network driver and event loop runner.
    pub(crate) driver: DriverHandle,
    /// Response router demultiplexing protocol frames and distributing watch events.
    pub(crate) router: Arc<Router>,
    /// Static client configuration parameters.
    pub(crate) config: Arc<ClientConfig>,
    /// Monotonic sequence generator allocating unique watch identifiers.
    next_watch_id: AtomicU64,
}

impl Shared {
    /// Initializes the shared client state and binds the router instance.
    pub(crate) fn new(
        driver: DriverHandle,
        router: Arc<Router>,
        config: Arc<ClientConfig>,
    ) -> Arc<Self> {
        let shared = Arc::new(Self {
            driver,
            router,
            config,
            next_watch_id: AtomicU64::new(1),
        });
        shared.router.bind(&shared);
        shared
    }

    /// Allocates a unique watch identifier. Monotonically increasing values ensure distinct
    /// watchers are never conflated by protocol state tracking.
    pub(crate) fn new_watch_id(&self) -> WatchId {
        WatchId(self.next_watch_id.fetch_add(1, Ordering::Relaxed))
    }

    /// Submits a protocol request and returns the single value slot holding its eventual reply.
    ///
    /// Executes the connection state machine under lock to validate parameters, apply any chroot
    /// prefix, allocate the next `xid`, and enqueue the outbound frame in submission order. Local
    /// watcher removals resolve immediately with `Reply::Void`.
    ///
    /// # Errors
    ///
    /// Returns the terminal error if the client session has expired or failed, or translates
    /// protocol submission rejections.
    fn submit_by(
        &self,
        op: NativeOp,
        kind: OpKind,
        watch: Option<WatchId>,
        send: impl FnOnce(&DriverHandle, Request<NativeTokens>) -> Sent,
    ) -> Result<Arc<Slot<RawResult>>, Error> {
        if let Some(e) = self.terminal_error() {
            return Err(e);
        }
        let slot = Slot::new();
        let token = NativeCompletion::Reply(ReplyToken::new(Arc::clone(&slot), watch, kind));
        self.settle(slot, send(&self.driver, queued(op, token)))
    }

    /// Maps a driver submission outcome to the response slot or a client error.
    ///
    /// Immediate local completions populate the slot synchronously, while submission rejections
    /// prioritize any latched terminal session error.
    fn settle(
        &self,
        slot: Arc<Slot<RawResult>>,
        sent: Sent,
    ) -> Result<Arc<Slot<RawResult>>, Error> {
        match sent {
            Ok(Submitted::Queued { .. }) => Ok(slot),
            Ok(Submitted::Local(token)) => {
                if let NativeCompletion::Reply(t) = token {
                    let _ = t.complete(local_void(), false);
                }
                Ok(slot)
            }
            Err(e) => {
                let err = self
                    .terminal_error()
                    .unwrap_or_else(|| map_submit_error(e.stage, e.code));
                drop(e.token);
                Err(err)
            }
        }
    }

    /// Submits a request and constructs a [`ResponseFuture`] yielding the decoded response
    /// payload.
    ///
    /// If request submission is refused, returns an immediately ready future bearing the
    /// submission error, dropping the decoder. Dropping the decoder guarantees that speculative
    /// watcher sinks are detached cleanly.
    pub(crate) fn request<T, E: From<Error>>(
        self: &Arc<Self>,
        op: NativeOp,
        kind: OpKind,
        watch: Option<WatchId>,
        decode: Decoder<T, E>,
    ) -> ResponseFuture<T, E> {
        self.request_with_data(op, None, kind, watch, decode)
    }

    /// Submits a request carrying borrowed payload bytes and returns a [`ResponseFuture`]
    /// awaiting the decoded response.
    pub(crate) fn request_with_data<T, E: From<Error>>(
        self: &Arc<Self>,
        op: NativeOp,
        data: Option<&[u8]>,
        kind: OpKind,
        watch: Option<WatchId>,
        decode: Decoder<T, E>,
    ) -> ResponseFuture<T, E> {
        self.request_by(op, kind, watch, decode, |d, req| {
            d.submit_with_data(req, data)
        })
    }

    /// Submits a request via a specialized driver transmission closure and binds the resultant
    /// slot to a [`ResponseFuture`].
    pub(crate) fn request_by<T, E: From<Error>>(
        self: &Arc<Self>,
        op: NativeOp,
        kind: OpKind,
        watch: Option<WatchId>,
        decode: Decoder<T, E>,
        send: impl FnOnce(&DriverHandle, Request<NativeTokens>) -> Sent,
    ) -> ResponseFuture<T, E> {
        match self.submit_by(op, kind, watch, send) {
            Ok(slot) => ResponseFuture::waiting(slot, decode, Arc::downgrade(self)),
            Err(e) => {
                drop(decode);
                ResponseFuture::ready(Err(E::from(e)))
            }
        }
    }

    /// Submits an untracked `/zookeeper/config` query on behalf of the dynamic ensemble follower.
    ///
    /// The request allocates no response receiver resources. A refusal returns the latched
    /// terminal error if one exists, and otherwise the mapped submission error. The follower
    /// uses the error to tell a full request queue from the end of the session.
    pub(crate) fn submit_follow(&self, op: NativeOp) -> Result<(), Error> {
        if let Some(e) = self.terminal_error() {
            return Err(e);
        }
        match self.driver.submit(queued(op, NativeCompletion::Follow)) {
            Ok(_) => Ok(()),
            Err(e) => Err(self
                .terminal_error()
                .unwrap_or_else(|| map_submit_error(e.stage, e.code))),
        }
    }

    /// Submits session authentication credentials via `add_auth` and returns a future resolving
    /// upon server confirmation.
    ///
    /// Resolves to `Ok(())` upon successful authentication or an appropriate [`Error`] variant on
    /// failure or terminal disconnection.
    pub(crate) fn add_auth(
        self: &Arc<Self>,
        scheme: JString,
        cred: SecretBytes,
    ) -> ResponseFuture<()> {
        if let Some(e) = self.terminal_error() {
            return ResponseFuture::ready(Err(e));
        }
        let slot = Slot::new();
        let token = AuthToken::new(Arc::clone(&slot));
        match self.driver.add_auth(scheme, cred, Some(token)) {
            Ok(()) => {
                ResponseFuture::waiting(slot, decode::boxed(decode::void), Arc::downgrade(self))
            }
            Err(e) => {
                drop(e.done);
                ResponseFuture::ready(Err(self
                    .terminal_error()
                    .or_else(|| Error::from_code(e.code))
                    .unwrap_or(Error::SystemError)))
            }
        }
    }

    /// Registers the delivery sink for a speculative one-shot watcher prior to wire submission.
    ///
    /// Establishing the sink before dispatch guarantees that server events arriving immediately
    /// after response frames are never missed or dropped.
    pub(crate) fn register_oneshot(
        self: &Arc<Self>,
        path: ZPathBuf,
        kind: WatcherKind,
    ) -> OneshotReg {
        let id = self.new_watch_id();
        let slot = Slot::new();
        self.router
            .sinks
            .lock_unpoisoned()
            .insert(id, Sink::Oneshot(Arc::clone(&slot)));
        OneshotReg {
            id,
            slot,
            shared: Arc::downgrade(self),
            gate: self.driver.gate(),
            path,
            kind,
            armed: false,
        }
    }

    /// Registers the event queue sink for a persistent watcher prior to wire submission.
    pub(crate) fn register_persistent(
        self: &Arc<Self>,
        path: ZPathBuf,
        mode: AddWatchMode,
    ) -> PersistentReg {
        let id = self.new_watch_id();
        let queue = EventQueue::new(self.config.persistent_watch_buffer);
        self.router
            .sinks
            .lock_unpoisoned()
            .insert(id, Sink::Persistent(Arc::clone(&queue)));
        PersistentReg {
            id,
            queue,
            shared: Arc::downgrade(self),
            tokio: self.driver.is_tokio(),
            path,
            mode,
            armed: false,
        }
    }

    /// Deregisters a watcher delivery sink whose external handle has been dropped and notifies
    /// protocol tracking.
    ///
    /// Deallocates the sink outside router locks. Redundant invocations safely no-op without
    /// error.
    pub(crate) fn drop_watch_sink(&self, id: WatchId) {
        let sink = self.router.sinks.lock_unpoisoned().remove(id);
        drop(sink);
        self.detach_watch(id);
    }

    /// Detaches a dropped watcher handle from protocol state tracking.
    ///
    /// When the final watcher for a given path and watch class is detached, an untracked
    /// `RemoveWatches` frame is enqueued on a best-effort basis.
    pub(crate) fn detach_watch(&self, id: WatchId) {
        let _queued = self.release_watch(id, |c, op, now| {
            c.submit(queued(op, NativeCompletion::Discard), now).is_ok()
        });
    }

    /// Explicitly deregisters a watcher upon user request via [`OneshotWatcher::remove`] or
    /// [`PersistentWatcher::remove`].
    ///
    /// Returns a slot awaiting server acknowledgement if a `RemoveWatches` request was queued to
    /// the wire, or `Ok(None)` if remaining local watchers continue observing the target znode.
    ///
    /// # Errors
    ///
    /// Returns [`Error`] if submitting the `RemoveWatches` request fails.
    pub(crate) fn remove_watch(&self, id: WatchId) -> Result<Option<Arc<Slot<RawResult>>>, Error> {
        let sink = self.router.sinks.lock_unpoisoned().remove(id);
        drop(sink);
        // Any rejected completion token is returned from the critical section so its response slot
        // closes outside the core lock.
        let sent = self.release_watch(id, |c, op, now| {
            let slot = Slot::new();
            let token = NativeCompletion::Reply(ReplyToken::new(
                Arc::clone(&slot),
                None,
                OpKind::RemoveWatches,
            ));
            (slot, c.submit(queued(op, token), now))
        });
        match sent {
            None => Ok(None),
            Some((slot, r)) => self.settle(slot, r).map(Some),
        }
    }

    /// Detaches a watcher and enqueues any resulting `RemoveWatches` request atomically under a
    /// single core lock acquisition.
    ///
    /// Coupling detachment with `xid` allocation ensures strict serialization against concurrent
    /// watch registrations on the same path.
    fn release_watch<R>(
        &self,
        id: WatchId,
        queue: impl FnOnce(&mut Connection<NativeTokens>, NativeOp, zk_proto::Millis) -> R,
    ) -> Option<R> {
        self.driver
            .with_conn(|c, now| {
                let hint = c.detach_watcher(&id)?;
                Some(queue(c, ops::remove_hint(hint), now))
            })
            .flatten()
    }

    /// Retrieves the terminal error if the client session has encountered fatal expiration or
    /// disconnection.
    pub(crate) fn terminal_error(&self) -> Option<Error> {
        self.router.state.terminal_error()
    }

    /// Returns the aggregate count of queued and in-flight requests, or `None` if the driver event
    /// loop has exited.
    pub(crate) fn pending_len(&self) -> Option<usize> {
        self.driver.read_conn(Connection::pending_len)
    }

    /// Executes a read-only inspection closure against the underlying protocol connection state
    /// under driver lock.
    pub(crate) fn read_conn<R>(&self, f: impl FnOnce(&Connection<NativeTokens>) -> R) -> Option<R> {
        self.driver.read_conn(f)
    }
}

/// Constructs a protocol request routed through the standard submission queue.
fn queued(op: NativeOp, token: NativeCompletion) -> Request<NativeTokens> {
    Request {
        op,
        token,
        route: Route::Queued,
        defect: InputDefect::default(),
    }
}

/// Synthesizes a void response payload for local watcher removals that bypass wire transmission.
fn local_void() -> RawResult {
    RawResult {
        rc: 0,
        zxid: 0,
        origin: Origin::Reply,
        reply: Some(Reply::Void),
    }
}

/// Speculative one-shot watcher registration spanning the window between request dispatch and
/// response decoding.
///
/// Owned by the response decoder. Upon successful reply processing, calling [`OneshotReg::arm`]
/// transitions this into an active [`OneshotWatcher`]. If dropped before arming (e.g. on request
/// failure), it automatically cleans up its sink and detaches the watcher from protocol state
/// tracking.
#[derive(Debug)]
pub(crate) struct OneshotReg {
    id: WatchId,
    slot: Arc<Slot<WatchOutcome>>,
    shared: Weak<Shared>,
    gate: Gate,
    path: ZPathBuf,
    kind: WatcherKind,
    armed: bool,
}

impl OneshotReg {
    /// Watch identifier assigned for protocol frame serialization and reply demultiplexing.
    pub(crate) fn id(&self) -> WatchId {
        self.id
    }

    /// Consumes the registration upon successful response decoding, yielding an armed
    /// [`OneshotWatcher`].
    pub(crate) fn arm(mut self) -> OneshotWatcher {
        self.armed = true;
        OneshotWatcher::new(
            self.id,
            Arc::clone(&self.slot),
            self.shared.clone(),
            Some(self.gate.clone()),
            self.path.clone(),
            self.kind,
        )
    }
}

impl Drop for OneshotReg {
    fn drop(&mut self) {
        if self.armed {
            return;
        }
        if self.slot.drop_receiver() {
            report_taken(Some(&self.gate));
        }
        if let Some(shared) = self.shared.upgrade() {
            shared.drop_watch_sink(self.id);
        }
    }
}

/// Speculative persistent watcher registration spanning the window between request dispatch and
/// response decoding.
///
/// Follows cleanup and arming invariants identical to [`OneshotReg`].
#[derive(Debug)]
pub(crate) struct PersistentReg {
    id: WatchId,
    queue: Arc<EventQueue>,
    shared: Weak<Shared>,
    /// Whether the client operates within a Tokio runtime environment.
    tokio: bool,
    path: ZPathBuf,
    mode: AddWatchMode,
    armed: bool,
}

impl PersistentReg {
    /// Watch identifier assigned for protocol frame serialization and reply demultiplexing.
    pub(crate) fn id(&self) -> WatchId {
        self.id
    }

    /// Consumes the registration upon successful response decoding, yielding an armed
    /// [`PersistentWatcher`].
    pub(crate) fn arm(mut self) -> PersistentWatcher {
        self.armed = true;
        PersistentWatcher::new(
            self.id,
            Arc::clone(&self.queue),
            self.shared.clone(),
            self.tokio,
            self.path.clone(),
            self.mode,
        )
    }
}

impl Drop for PersistentReg {
    fn drop(&mut self) {
        if self.armed {
            return;
        }
        self.queue.drop_receiver();
        if let Some(shared) = self.shared.upgrade() {
            shared.drop_watch_sink(self.id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_ids_are_unique_and_not_zero() {
        let a = next_client_id();
        let b = next_client_id();
        assert_ne!(a, 0);
        assert_ne!(a, b);
    }

    #[test]
    fn a_local_removal_result_is_void() {
        let r = local_void();
        assert_eq!(r.rc, 0);
        assert_eq!(r.reply, Some(Reply::Void));
    }
}
