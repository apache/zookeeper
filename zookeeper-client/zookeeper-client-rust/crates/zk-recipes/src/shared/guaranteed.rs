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

//! Guaranteed unlinking engine for ephemeral participant znodes.
//!
//! This module provides asynchronous cleanup guarantees equivalent to Apache Curator's
//! `guaranteed()` delete mode and its background orphan reclamation mechanism
//! (`FindAndDeleteProtectedNodeInBackground`). Because synchronous RAII `Drop` implementations
//! cannot suspend execution or await network I/O, node cleanup tasks are handed off to this
//! module to guarantee eventual unlinking without requiring caller intervention.
//!
//! # Multi-Tier Execution Architecture
//!
//! Cleanup execution is staged across two tiers to minimize runtime overhead:
//!
//! 1. **Immediate nonblocking Unlink**: Dropping an acquired znode with a known path immediately
//!    submits an asynchronous deletion request. A lightweight inline waker polls the response
//!    future directly on the completion thread. If the deletion succeeds or fails with a
//!    non-retryable error (such as `NoNode`), cleanup concludes immediately without spawning a
//!    background worker thread.
//! 2. **Dedicated Background Retry Engine (`zk-recipes-delete`)**: If the initial deletion
//!    fails due to transient connection loss, network partitions, or client queue saturation, the
//!    task is promoted to a shared process-wide background worker thread. This background thread
//!    spawns on demand when retries are required and automatically terminates when all pending
//!    cleanup tasks are resolved.
//!
//! # In-Flight Creation Reclamation
//!
//! When a participant creation future is dropped while its sequential ephemeral `create` request
//! remains in flight, an inline waker observes the creation response. If creation succeeds, an
//! immediate asynchronous deletion is dispatched via tier 1, escalating to the background worker
//! only if that deletion encounters a retryable failure. If creation fails with connection loss,
//! leaving the server side outcome ambiguous, the task is handed to the background worker, which
//! issues a linearizing `sync`, enumerates the parent directory, and unlinks any orphaned znode
//! matching the embedded [`ProtectedId`] (`_c_<uuid>-`).
//!
//! # Retry Policies and Session Invariants
//!
//! - **Connection Loss and Read-Only Servers**: Retries pause until the client re-establishes an
//!   active read-write `Connected` state under a subsequent epoch. Read-only connections
//!   (`ConnectedReadOnly`) are ignored because read-only servers cannot commit deletion writes.
//! - **Queue Saturation**: If deletion is rejected with `TooManyPendingRequests`, the task
//!   suspends until the client's internal request pipeline drains before retrying.
//! - **Reference Hygiene**: Pending tasks maintain [`WeakClient`] references rather than strong
//!   references, ensuring that background retry loops never prevent idle ZooKeeper client sessions
//!   from closing or garbage collecting cleanly.

use std::future::Future;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::pin::Pin;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::task::{Context, Poll, Wake, Waker};
use std::thread::{self, Thread, ThreadId};

use apache_zookeeper::{
    Client, Error, ResponseFuture, Stat, StateWatcher, Version, WeakClient, ZPath, ZPathBuf,
};

use super::protected::ProtectedId;
use super::session::{is_retryable, synced_list, writable_after};

/// Name assigned to the process-wide background cleanup thread.
const THREAD_NAME: &str = "zk-recipes-delete";

/// Pinned boxed future representing an active background cleanup task.
type Task = Pin<Box<dyn Future<Output = ()> + Send>>;

/// Submits an immediate asynchronous deletion request for the znode at `path`, scheduling
/// background retry on failure.
///
/// If the immediate deletion encounters a recoverable error (such as connection loss), the task
/// is transferred to the background cleanup thread for persistent retry across reconnection
/// epochs.
pub(crate) fn delete(client: &Client, path: ZPathBuf) {
    let session = Session::new(client);
    let sent = session.state.current().epoch;
    let reply = client.delete(&path, None::<Version>);
    watch(unlink(session, path, sent, reply));
}

/// Awaits a single deletion response, escalating to the background retry worker only if the
/// deletion fails with a retryable error.
async fn unlink(session: Session, path: ZPathBuf, sent: u64, reply: ResponseFuture<()>) {
    if let Some(next) = reply.await.err().and_then(|e| retry(&e, sent)) {
        submit(delete_task(session, path, next));
    }
}

/// Manages cleanup for a sequential participant node creation abandoned while the request was in
/// flight.
///
/// If creation ultimately succeeds, an immediate asynchronous deletion is dispatched on the
/// completion thread, escalating to the background worker only upon retryable failure. If creation
/// terminates with connection loss, ambiguous state recovery is scheduled on the background worker
/// to identify and unlink the znode by its protected UUID prefix.
pub(crate) fn abandon_create(
    client: &Client,
    parent: ZPathBuf,
    id: ProtectedId,
    sent: u64,
    reply: ResponseFuture<(ZPathBuf, Stat)>,
) {
    let session = Session::new(client);
    watch(async move {
        match reply.await {
            Ok((path, _)) => {
                let sent = session.state.current().epoch;
                // Enqueue the deletion request directly. No client locks are held during reply
                // waker notification.
                if let Some(reply) = session.send(|c| c.delete(&path, None::<Version>)) {
                    unlink(session, path, sent, reply).await;
                }
            }
            Err(e) if e.is_connection_loss() => {
                // `submit` acquires the worker mutex solely to enqueue the task and spawn or
                // unpark the background thread without polling the task under lock, preventing
                // lock-order cycles when invoked from completion waker callbacks.
                submit(lookup_task(session, parent, id, sent));
            }
            Err(_) => {}
        }
    });
}

/// Enqueues background reconciliation to find and unlink an orphaned znode identified by its
/// protected UUID.
pub(crate) fn find_and_delete(client: &Client, parent: ZPathBuf, id: ProtectedId, sent: u64) {
    submit(lookup_task(Session::new(client), parent, id, sent));
}

/// Weak reference handle to a ZooKeeper client session with associated state tracking.
struct Session {
    client: WeakClient,
    state: StateWatcher,
}

impl Session {
    fn new(client: &Client) -> Self {
        Self {
            client: client.downgrade(),
            state: client.state_watcher(),
        }
    }

    /// Executes a closure using a temporarily upgraded strong `Client` reference.
    ///
    /// Returns `None` if all strong references to the client have dropped, indicating
    /// that the session is terminating and ephemeral nodes will be unlinked by the server.
    fn send<T>(&self, f: impl FnOnce(&Client) -> T) -> Option<T> {
        self.client.upgrade().map(|client| f(&client))
    }
}

/// Scheduling prerequisite determining when a cleanup task may re-attempt submission.
#[derive(Clone, Copy)]
enum Next {
    /// Await an active read-write `Connected` state associated with an epoch strictly greater
    /// than the specified epoch.
    Epoch(u64),
    /// Await pipeline capacity in the client request queue, followed by a valid read-write
    /// session epoch.
    Room(u64),
}

/// Evaluates an error condition to determine whether the operation is recoverable and computes
/// the next retry prerequisite.
///
/// Returns `None` if the error is non-retryable (e.g. authoritative server errors or node
/// absence), terminating the task.
fn retry(e: &Error, epoch: u64) -> Option<Next> {
    if is_retryable(e) {
        Some(Next::Epoch(epoch))
    } else if matches!(e, Error::TooManyPendingRequests) {
        Some(Next::Room(epoch))
    } else {
        None
    }
}

/// Suspends execution until the scheduling condition defined by `next` is satisfied.
///
/// Returns `Some(epoch)` reflecting the updated connection epoch, or `None` if the session
/// permanently terminates.
async fn wait(session: &mut Session, next: Next) -> Option<u64> {
    match next {
        Next::Epoch(after) => writable_after(&mut session.state, after).await,
        Next::Room(epoch) => {
            session.send(Client::ready)?.await.ok()?;
            writable_after(&mut session.state, epoch.saturating_sub(1)).await
        }
    }
}

/// Continuously retries unlinking `path` across reconnects until deletion succeeds or the session
/// terminates.
async fn delete_task(mut session: Session, path: ZPathBuf, mut next: Next) {
    loop {
        let Some(sent) = wait(&mut session, next).await else {
            return;
        };
        let Some(reply) = session.send(|c| c.delete(&path, None::<Version>)) else {
            return;
        };
        let Some(later) = reply.await.err().and_then(|e| retry(&e, sent)) else {
            return;
        };
        next = later;
    }
}

/// Performs linearizable directory inspection to locate and delete orphaned znodes matching `id`.
///
/// Issues a `sync` request before listing parent children to guarantee visibility of recent
/// mutations committed across the ensemble quorum.
async fn lookup_task(mut session: Session, parent: ZPathBuf, id: ProtectedId, sent: u64) {
    let mut next = Next::Epoch(sent);
    loop {
        let Some(epoch) = wait(&mut session, next).await else {
            return;
        };
        let Some(later) = lookup_pass(&session, parent.as_path(), &id, epoch).await else {
            return;
        };
        next = later;
    }
}

/// Executes a single synchronized parent listing pass, deleting any children matching the
/// protected UUID.
async fn lookup_pass(
    session: &Session,
    parent: ZPath<'_>,
    id: &ProtectedId,
    epoch: u64,
) -> Option<Next> {
    let (sync, mut list) = session.send(|c| (c.sync(parent), c.get_children(parent)))?;
    let children = match synced_list(sync, &mut list).await {
        Ok(children) => children,
        // Absence of the parent directory implies no children exist; terminal errors abort the
        // task.
        Err(e) => return retry(&e, epoch),
    };
    let paths: Vec<ZPathBuf> = children
        .iter()
        .filter(|child| id.owns(child))
        .filter_map(|child| parent.join(child).ok())
        .collect();
    if paths.is_empty() {
        return None;
    }
    delete_owned(session, &paths, epoch).await
}

/// Dispatches batch deletion requests for identified child znodes, aggregating retry requirements.
async fn delete_owned(session: &Session, paths: &[ZPathBuf], epoch: u64) -> Option<Next> {
    let deletes = session.send(|c| {
        paths
            .iter()
            .map(|p| c.delete(p, None::<Version>))
            .collect::<Vec<_>>()
    })?;
    // Connection losses require epoch advancement; epoch retries supersede queue saturation
    // retries as the subsequent pass re-enumerates.
    let (mut lost, mut refused) = (false, false);
    for reply in deletes {
        match reply.await.err().and_then(|e| retry(&e, epoch)) {
            Some(Next::Epoch(_)) => lost = true,
            Some(_) => refused = true,
            None => {}
        }
    }
    if lost {
        Some(Next::Epoch(epoch))
    } else if refused {
        Some(Next::Room(epoch))
    } else {
        None
    }
}

/// Executes a task without spinning up a dedicated OS thread by driving polling from caller and
/// waker notification contexts.
fn watch(fut: impl Future<Output = ()> + Send + 'static) {
    let inline = Arc::new(Inline(Mutex::new(Some(Box::pin(fut)))));
    inline.poll();
}

/// Lightweight synchronization harness driving single-future execution within completion callback
/// contexts.
struct Inline(Mutex<Option<Task>>);

impl Inline {
    fn poll(self: &Arc<Self>) {
        let waker = Waker::from(Arc::clone(self));
        let mut cx = Context::from_waker(&waker);
        let mut slot = self.0.lock().unwrap_or_else(PoisonError::into_inner);
        let ready = slot
            .as_mut()
            .is_some_and(|task| task.as_mut().poll(&mut cx).is_ready());
        if ready {
            let done = slot.take();
            drop(slot);
            drop(done);
        }
    }
}

impl Wake for Inline {
    fn wake(self: Arc<Self>) {
        self.poll();
    }

    fn wake_by_ref(self: &Arc<Self>) {
        self.poll();
    }
}

/// Process-wide background cleanup thread state and pending task queue.
struct Worker {
    /// Backlog of pending asynchronous cleanup tasks awaiting consumption by the worker thread.
    queue: Vec<Task>,
    /// Handle to the active background thread, or `None` if dormant.
    thread: Option<Thread>,
    /// Monotonically increasing counter tracking worker thread creations, maintained for testing
    /// verification.
    #[cfg(test)]
    starts: usize,
}

/// Global singleton registry managing background cleanup tasks.
static WORKER: Mutex<Worker> = Mutex::new(Worker {
    queue: Vec::new(),
    thread: None,
    #[cfg(test)]
    starts: 0,
});

/// Acquires the global worker mutex, recovering transparently from poisoning.
fn worker() -> MutexGuard<'static, Worker> {
    WORKER.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Enqueues a cleanup task and ensures the background worker thread is spawned and unparked.
fn submit(job: impl Future<Output = ()> + Send + 'static) {
    let mut w = worker();
    w.queue.push(Box::pin(job));
    if let Some(t) = &w.thread {
        t.unpark();
        return;
    }
    // The new thread waits for the lock, so it sees `thread` set.
    match thread::Builder::new()
        .name(THREAD_NAME.to_owned())
        .spawn(run)
    {
        Ok(handle) => {
            w.thread = Some(handle.thread().clone());
            #[cfg(test)]
            {
                w.starts = w.starts.saturating_add(1);
            }
        }
        Err(_) => {
            // If thread creation fails, queued tasks are dropped; orphaned nodes will expire
            // alongside the session.
            let lost = std::mem::take(&mut w.queue);
            drop(w);
            drop(lost);
        }
    }
}

/// Execution loop for the background cleanup worker thread.
///
/// Drives all active cleanup tasks cooperatively, parking the thread when tasks are pending I/O.
/// Automatically terminates and unregisters itself once all pending tasks complete.
fn run() {
    let me = thread::current();
    let _exit = Exit(me.id());
    let waker = Waker::from(Arc::new(Unpark(me)));
    let mut cx = Context::from_waker(&waker);
    let mut jobs: Vec<Task> = Vec::new();
    loop {
        {
            let mut w = worker();
            jobs.append(&mut w.queue);
            if jobs.is_empty() {
                w.thread = None;
                return;
            }
        }
        // Isolate panics within individual cleanup tasks to prevent crashing the shared worker.
        jobs.retain_mut(|job| {
            matches!(
                catch_unwind(AssertUnwindSafe(|| job.as_mut().poll(&mut cx))),
                Ok(Poll::Pending)
            )
        });
        if !jobs.is_empty() {
            thread::park();
        }
    }
}

/// RAII guard ensuring the global worker handle is cleared if the background thread unwinds.
struct Exit(ThreadId);

impl Drop for Exit {
    fn drop(&mut self) {
        let mut w = worker();
        if w.thread.as_ref().is_some_and(|t| t.id() == self.0) {
            w.thread = None;
        }
    }
}

/// Waker implementation that unparks the background worker thread upon task notification.
struct Unpark(Thread);

impl Wake for Unpark {
    fn wake(self: Arc<Self>) {
        self.0.unpark();
    }

    fn wake_by_ref(self: &Arc<Self>) {
        self.0.unpark();
    }
}

/// Indicates whether the background cleanup worker thread is currently running.
#[cfg(test)]
fn running() -> bool {
    worker().thread.is_some()
}

/// Returns the total count of background worker thread spawn operations.
#[cfg(test)]
fn starts() -> usize {
    worker().starts
}

#[cfg(test)]
mod tests {
    //! Unit tests verifying background worker lifecycle, task retry loops, and session detachment.

    use std::time::Duration;

    use apache_zookeeper::{Client, Runtime, SessionState, ThreadOptions};
    use zk_fakeserver::{Action, FakeServer, Rule};

    use super::{running, starts};
    use crate::{DistributedLock, LeaderLatch};

    const WAIT: Duration = Duration::from_secs(10);
    const OP_DELETE: i32 = 2;
    const OP_CREATE2: i32 = 15;
    const ERR_NOT_READ_ONLY: i32 = -119;

    /// Serializes execution across test cases to isolate the shared singleton worker.
    static SERIAL: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

    async fn within<F: std::future::Future>(f: F) -> F::Output {
        tokio::time::timeout(WAIT, f).await.expect("timed out")
    }

    async fn connect(server: &FakeServer) -> Client {
        within(
            Client::builder(server.connect_string())
                .session_timeout(Duration::from_secs(4))
                .reconnect_delay(Duration::from_millis(50))
                .runtime(Runtime::Thread(ThreadOptions::default()))
                .connect(),
        )
        .await
        .unwrap()
    }

    async fn until(mut pred: impl FnMut() -> bool) {
        within(async {
            while !pred() {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await;
    }

    fn children(server: &FakeServer, path: &str) -> usize {
        server.cluster().children(path).map_or(0, |c| c.len())
    }

    /// Configures a mock server rule causing znode deletion to fail with NotReadOnly, forcing
    /// task retry.
    async fn stuck_delete(server: &FakeServer, client: &Client, path: &str) {
        let guard = within(DistributedLock::new(client, path).unwrap().acquire())
            .await
            .unwrap();
        server.add_rule(
            Rule::on_request()
                .op(OP_DELETE)
                .path(guard.node().as_str())
                .then(Action::Reply {
                    err: ERR_NOT_READ_ONLY,
                }),
        );
        drop(guard);
        until(running).await;
        assert_eq!(server.script().rules()[0].fired(), 1);
    }

    /// Verifies that standard error-free lock and latch lifecycles never spawn the background
    /// worker thread.
    #[tokio::test]
    async fn no_worker_without_a_lost_delete() {
        let _serial = SERIAL.lock().await;
        until(|| !running()).await;
        let before = starts();
        let server = FakeServer::start().unwrap();
        let a = connect(&server).await;
        let b = connect(&server).await;
        let lock = DistributedLock::new(&a, "/quiet/lock").unwrap();
        let guard = within(lock.acquire()).await.unwrap();
        let other = DistributedLock::new(&b, "/quiet/lock").unwrap();
        assert!(within(other.try_acquire()).await.unwrap().is_none());
        within(guard.release()).await.unwrap();
        let guard = within(lock.acquire()).await.unwrap();
        drop(guard);
        until(|| children(&server, "/quiet/lock") == 0).await;

        let latch = LeaderLatch::new(&a, "/quiet/latch", "a").unwrap();
        let mut started = within(latch.start()).await.unwrap();
        assert!(within(started.changed()).await.unwrap());
        within(started.close()).await.unwrap();
        let started = within(latch.start()).await.unwrap();
        drop(started);
        until(|| children(&server, "/quiet/latch") == 0).await;

        assert_eq!(starts(), before);
        assert!(!running());
        within(a.close()).await.unwrap();
        within(b.close()).await.unwrap();
    }

    /// Verifies that abandoning an in-flight creation unlinks the created znode without the
    /// worker thread.
    #[tokio::test]
    async fn dropped_create_in_flight_needs_no_worker() {
        const PATH: &str = "/inflight/lock";
        let _serial = SERIAL.lock().await;
        until(|| !running()).await;
        let before = starts();
        let server = FakeServer::start().unwrap();
        let client = connect(&server).await;
        let lock = DistributedLock::new(&client, PATH).unwrap();
        // Pre-create parent directory structure so only the subsequent creation is intercepted.
        drop(within(lock.acquire()).await.unwrap());
        until(|| children(&server, PATH) == 0).await;
        assert_eq!(starts(), before);
        server.add_rule(
            Rule::on_request()
                .op(OP_CREATE2)
                .path_prefix("/inflight/lock/_c_")
                .once()
                .then(Action::Delay(Duration::from_millis(300))),
        );
        {
            let mut acquire = std::pin::pin!(lock.acquire());
            let mut cx = std::task::Context::from_waker(std::task::Waker::noop());
            assert!(std::future::Future::poll(acquire.as_mut(), &mut cx).is_pending());
        }
        until(|| children(&server, PATH) == 1).await;
        until(|| children(&server, PATH) == 0).await;
        assert_eq!(starts(), before);
        assert!(!running());
        within(client.close()).await.unwrap();
    }

    /// Verifies that the background worker thread takes over when the immediate deletion of an
    /// abandoned created znode fails with a retryable error.
    #[tokio::test]
    async fn dropped_create_in_flight_with_a_failed_delete_starts_the_worker() {
        const PATH: &str = "/inflight-retry/lock";
        let _serial = SERIAL.lock().await;
        until(|| !running()).await;
        let before = starts();
        let server = FakeServer::start().unwrap();
        let client = connect(&server).await;
        let mut state = client.state_watcher();
        let lock = DistributedLock::new(&client, PATH).unwrap();
        // Pre-create the parent hierarchy so fault-injection rules match only the subsequent
        // candidate creation.
        drop(within(lock.acquire()).await.unwrap());
        until(|| children(&server, PATH) == 0).await;
        assert_eq!(starts(), before);
        server.add_rule(
            Rule::on_request()
                .op(OP_CREATE2)
                .path_prefix("/inflight-retry/lock/_c_")
                .once()
                .then(Action::Delay(Duration::from_millis(300))),
        );
        server.add_rule(
            Rule::on_request()
                .op(OP_DELETE)
                .path_prefix("/inflight-retry/lock/_c_")
                .then(Action::Reply {
                    err: ERR_NOT_READ_ONLY,
                }),
        );
        {
            let mut acquire = std::pin::pin!(lock.acquire());
            let mut cx = std::task::Context::from_waker(std::task::Waker::noop());
            assert!(std::future::Future::poll(acquire.as_mut(), &mut cx).is_pending());
        }
        until(running).await;
        assert_eq!(starts(), before.saturating_add(1));
        assert_eq!(server.script().rules()[1].fired(), 1);
        assert_eq!(children(&server, PATH), 1);

        assert!(server.cluster().expire_session(client.session().0.0));
        within(async {
            while state.current().state != SessionState::Expired {
                state.changed().await;
            }
        })
        .await;
        until(|| !running()).await;
        assert_eq!(server.script().rules()[1].fired(), 1);
        assert_eq!(children(&server, PATH), 0);
        let _ = within(client.close()).await;
    }

    /// Verifies that session expiration aborts pending cleanup retries and terminates the
    /// background worker.
    #[tokio::test]
    async fn expiry_stops_the_retries_and_the_worker_exits() {
        let _serial = SERIAL.lock().await;
        until(|| !running()).await;
        let server = FakeServer::start().unwrap();
        let client = connect(&server).await;
        let mut state = client.state_watcher();
        stuck_delete(&server, &client, "/expire/lock").await;

        assert!(server.cluster().expire_session(client.session().0.0));
        within(async {
            while state.current().state != SessionState::Expired {
                state.changed().await;
            }
        })
        .await;
        until(|| !running()).await;
        assert_eq!(server.script().rules()[0].fired(), 1);
        assert_eq!(children(&server, "/expire/lock"), 0);
    }

    /// Verifies that dropping all active client handles terminates the session and permits worker
    /// shutdown.
    #[tokio::test]
    async fn last_client_drop_closes_the_session_and_the_worker_exits() {
        let _serial = SERIAL.lock().await;
        until(|| !running()).await;
        let server = FakeServer::start().unwrap();
        let client = connect(&server).await;
        let mut state = client.state_watcher();
        stuck_delete(&server, &client, "/last/lock").await;

        drop(client);
        within(async {
            while state.current().state != SessionState::Closed {
                state.changed().await;
            }
        })
        .await;
        until(|| server.cluster().sessions().is_empty()).await;
        until(|| !running()).await;
        assert_eq!(server.script().rules()[0].fired(), 1);
        assert_eq!(children(&server, "/last/lock"), 0);
    }
}
