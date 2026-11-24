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

//! Asynchronous ZooKeeper client driver backed by a Tokio task runtime (`tokio` package,
//! `DispatchMode::Tokio`, Unix only).
//!
//! # Architectural Overview
//!
//! Rather than spawning a dedicated OS thread per session (`zk-io` in [`crate::native`]), this
//! driver executes a single cooperative asynchronous Tokio task (`run`) per client session. It
//! drives the same deterministic [`IoCore`] transport state machine under [`Flavor::Native`] and
//! [`DeferPolicy::ALL`], achieving full behavioral parity with [`crate::native`] without ever
//! blocking a Tokio worker thread:
//!
//! - **Per-Wait `AsyncFd` Registration (`tokio_wait_with`)**: Network socket readiness is
//!   monitored via [`tokio::io::unix::AsyncFd`] wrapping the raw socket descriptor returned in
//!   [`InterestOut::fd`]. Crucially, the descriptor is registered with `AsyncFd::with_interest` at
//!   the start of each wait cycle and deregistered (dropped) as soon as the wait completes, before
//!   `IoCore::process_step` runs. Because [`IoCore`] may close and replace its socket during any
//!   `interest_step` or `process_step` (and the OS kernel may immediately reuse the same numerical
//!   `RawFd` for a new connection), scoping `AsyncFd` strictly to the wait phase prevents stale
//!   epoll/kqueue registrations from aliasing across reconnects.
//! - **Wakeup Notification via `tokio::sync::Notify`**: Out-of-band wakeups from
//!   [`TaskHandle::submit`], [`TaskHandle::add_auth`], [`TaskHandle::set_servers`],
//!   [`TaskHandle::close`], and [`TaskGate::taken`] signal `TaskShared::notify` instead of
//!   writing to an OS self-pipe.
//! - **Universal Blocking Offload (`DeferPolicy::ALL`)**: Every operation that can perform
//!   blocking syscalls or heavy synchronous computation is deferred to
//!   [`tokio::task::spawn_blocking`]:
//!   1. *Foreground `Job`s* (`Job::Resolve`, `Job::NewConn` for OS CSPRNG/TLS setup, and
//!      `Job::Probe` for `isro` TCP/TLS probing): When [`IoCore`] returns [`Progress::Wait`],
//!      `tokio_settle` pops all queued jobs via `IoCore::take_jobs`, awaits each one
//!      sequentially on `spawn_blocking` via `tokio_job`, and feeds the `JobDone` result back
//!      into `IoCore::complete_job`.
//!   2. *Background DNS Resolution (`BgTask`)*: When `set_servers` is called while a connection is
//!      already active, `IoCore::interest_step` stages the lookup in `IoCore::bg_start` and
//!      continues servicing the live socket. `tokio_start_bg` launches a `BgTask` on
//!      `spawn_blocking`, and `tokio_wait_with` polls `task.handle` alongside `AsyncFd`,
//!      `Notify`, and the interest timer inside a single zero-allocation [`poll_fn`].
//!   3. *Native SASL/TLS Close-Notify*: `tokio_sasl` temporarily extracts `TaskIo::sasl` and
//!      runs [`SaslSession::start`] / [`SaslSession::step`] on `spawn_blocking`. Similarly,
//!      `tokio_final_close` offloads `IoCore::close_flush` when sending a TLS `close_notify`
//!      alert.
//!
//! # Strict `Send` and Lock Discipline Across `.await`
//!
//! Synchronous helper methods on `TaskShared` (`tokio_interest`, `tokio_process`, `tokio_resume`,
//! `tokio_complete`, `tokio_route_sync`) construct a stack-scoped [`Unlocked`] proof, acquire the
//! synchronous `io` and `core` mutexes for the brief nonblocking step, and drop all guards before
//! returning. Asynchronous functions (`run`, `tokio_settle`, `tokio_route`, `tokio_wait_with`)
//! never hold a [`Guard`] or [`Unlocked`] proof across an `.await` suspension point, ensuring the
//! task future is unconditionally [`Send`] and deadlock-free.
//!
//! If the hosting Tokio runtime shuts down or aborts the task future at any `.await` point (or even
//! before its first poll), `ExitGuard` drops the transport, reclaims in-flight tokens via
//! [`HostLeaves`](crate::host::HostLeaves), and broadcasts `ZCONNECTIONLOSS` on the exit watch
//! channel so [`TaskHandle::closed`] never hangs.

use std::collections::VecDeque;
use std::future::{Future, poll_fn};
use std::net::SocketAddr;
use std::os::fd::RawFd;
use std::pin::{Pin, pin};
use std::sync::Arc;
use std::task::Poll as TaskPoll;
use std::time::Duration;

use ::tokio::io::unix::{AsyncFd, AsyncFdReadyGuard};
use ::tokio::io::{Interest, Ready};
use zk_jute::JString;
use zk_proto::rc::{ZCLOSING, ZCONNECTIONLOSS, ZOK, ZSYSTEMERROR};
use zk_proto::{
    Action, AddAuthError, Cause, ClientId, CloseProgress, Connection, Endpoint, HostResolution,
    HostSpec, Millis, MultiOpRef, ProbeOutcome, Request, ResolveError, SaslCall, SaslCallKind,
    SaslError, SaslSession, SecretBytes, SubmitError, Submitted, TlsResourceAt,
};

use crate::dispatch::{ReadGate, RouteSink};
use crate::host::{DriverTokens, discard_leftovers};
use crate::iocore::{
    DeferPolicy, InterestOut, IoCore, Job, JobCtx, JobDone, Progress, StepCtx, Transport, run_job,
};
use crate::native::{SaslHosts, no_mechanism};
use crate::net::poll::PollEvents;
use crate::net::probe::{ProbeCtx, ProbeEnd};
use crate::net::tls::TlsSetupError;
use crate::outbox::{Flavor, Item, Outbox, SaslItem};
use crate::state::{
    Core, Env, SaslAnswer, SubmitResult, read_at_entry, read_conn, with_conn_at_entry,
};
use crate::sync::atomic::{AtomicBool, Ordering};
use crate::sync::{Guard, Mutex, Unlocked};

/// Synthetic `getaddrinfo` return code representing `EAI_FAIL` when a background resolver job
/// aborts or panics without returning address records on Linux/Android platforms.
#[cfg(any(target_os = "linux", target_os = "android"))]
const JOB_EAI_FAIL: i32 = -4;
/// Synthetic `getaddrinfo` return code representing `EAI_FAIL` when a background resolver job
/// aborts or panics without returning address records on BSD/macOS platforms.
#[cfg(not(any(target_os = "linux", target_os = "android")))]
const JOB_EAI_FAIL: i32 = 4;

/// Synthetic POSIX input/output error code (`EIO`) returned when a background resolver job
/// terminates unexpectedly.
const JOB_EIO: i32 = 5;

/// Bounded poll backoff interval utilized when descriptor re-registration with `AsyncFd` fails,
/// throttling socket polling checks to prevent CPU starvation.
const REGISTER_RETRY: Duration = Duration::from_millis(10);

/// Maximum number of drain barrier iterations allowed during outbox flushing before yielding.
const MAX_DRAIN_ROUNDS: u32 = 4096;

/// Upper bound on action polling loops executed following connection destruction, servicing
/// remaining cleanup actions such as transport teardown and SASL session disposal.
const MAX_FINAL_ACTIONS: u32 = 64;

/// Internal task-side state managed under the `io` mutex.
pub(crate) struct TaskIo {
    /// Core I/O loop engine and step continuation state machine.
    pub(crate) io: IoCore,
    /// Active native SASL session mechanism, detached during blocking authentication steps.
    pub(crate) sasl: Option<Box<dyn SaslSession>>,
}

/// Shared execution state and synchronization primitives coordinating the Tokio driver task and
/// user handles.
pub(crate) struct TaskShared<T: DriverTokens> {
    pub(crate) core: Mutex<Core<T>>,
    pub(crate) io: Mutex<TaskIo>,
    pub(crate) notify: ::tokio::sync::Notify,
    pub(crate) gate: ReadGate,
    pub(crate) sink: Arc<dyn RouteSink<T>>,
    pub(crate) env: Env<T>,
    /// Cancellation flag asserted during graceful close or handle detachment to abort running
    /// probe operations.
    pub(crate) close_requested: Arc<AtomicBool>,
    /// State flag asserted when client detachment is initiated via `detach`.
    pub(crate) detached: AtomicBool,
    /// Watch channel broadcasting the final exit status code upon task termination.
    pub(crate) exit: ::tokio::sync::watch::Sender<Option<i32>>,
    /// Mapping of resolved IP addresses to hostnames for SASL endpoint identification, guarded by
    /// a leaf mutex.
    pub(crate) hosts: Mutex<SaslHosts>,
    /// Flag indicating whether the client configuration includes an active SASL mechanism
    /// requiring host tracking.
    pub(crate) sasl_hosts: bool,
    /// Flag indicating whether a graceful `CLOSE_OP` request was successfully enqueued in the
    /// protocol engine.
    graceful: AtomicBool,
}

/// Client handle providing thread-safe access to a ZooKeeper session running on a Tokio driver
/// task.
///
/// The session remains active until [`close`](Self::close) or [`detach`](Self::detach) is called,
/// or until the last cloned handle instance is dropped. Handle destruction triggers an async
/// shutdown in the background. The driver task holds only weak references to the session
/// lifecycle, ensuring that handle drops automatically reclaim resources.
pub struct TaskHandle<T: DriverTokens> {
    pub(crate) shared: Arc<TaskShared<T>>,
    /// Shared lifecycle owner whose drop handler initiates graceful session teardown.
    owner: Arc<CloseOnDrop<T>>,
}

impl<T: DriverTokens> Clone for TaskHandle<T> {
    fn clone(&self) -> Self {
        Self {
            shared: Arc::clone(&self.shared),
            owner: Arc::clone(&self.owner),
        }
    }
}

/// Backpressure reporting handle used by consumers to notify the Tokio driver task when dispatched
/// items are drained.
///
/// Dispatched item consumers may outlive client handles while the driver drains outbound queues
/// during shutdown. The gate reporter does not own a session lifecycle reference and will not
/// prevent session teardown, but keeps backpressure accounting accurate until the driver task
/// exits.
pub struct TaskGate<T: DriverTokens> {
    shared: Arc<TaskShared<T>>,
}

impl<T: DriverTokens> Clone for TaskGate<T> {
    fn clone(&self) -> Self {
        Self {
            shared: Arc::clone(&self.shared),
        }
    }
}

impl<T: DriverTokens> TaskGate<T> {
    /// Notifies the read gate that a queued dispatch item has been consumed or dropped.
    ///
    /// If consuming the item transitions the gate from saturated to open, the driver task
    /// is notified to resume network reads.
    pub fn taken(&self) {
        if self.shared.gate.taken() {
            self.shared.notify.notify_one();
        }
    }
}

/// Drop guard that triggers session shutdown when the last cloned handle is destroyed.
struct CloseOnDrop<T: DriverTokens> {
    shared: Arc<TaskShared<T>>,
}

impl<T: DriverTokens> Drop for CloseOnDrop<T> {
    fn drop(&mut self) {
        // If the session was already closed or detached, the close request is a no-op.
        self.shared.tokio_close();
    }
}

impl<T: DriverTokens> TaskHandle<T> {
    /// Constructs the initial primary handle wrapping the shared driver state.
    pub(crate) fn from_shared(shared: Arc<TaskShared<T>>) -> Self {
        let owner = Arc::new(CloseOnDrop {
            shared: Arc::clone(&shared),
        });
        Self { shared, owner }
    }
}

/// Safety guard ensuring an exit code is broadcast if the driver task terminates prematurely.
///
/// If the driver task future is aborted before polling or during execution (for example, during
/// runtime shutdown), this guard drops the active transport, discards remaining tokens, and
/// broadcasts `ZCONNECTIONLOSS`, preventing callers awaiting [`closed`](TaskHandle::closed) from
/// hanging indefinitely.
struct ExitGuard<T: DriverTokens> {
    shared: Arc<TaskShared<T>>,
}

impl<T: DriverTokens> Drop for ExitGuard<T> {
    fn drop(&mut self) {
        self.shared.tokio_abort_exit();
    }
}

/// Spawns a new ZooKeeper client driver task on the provided Tokio runtime handle.
///
/// `conn` must be configured with `DriverMode::NATIVE` and `Compat::NATIVE`. The driver
/// automatically sets `env.cfg.cloexec` to guarantee close-on-exec descriptor safety. The returned
/// handle does not own an OS thread. The background task terminates upon explicit closure, handle
/// detachment, or runtime teardown. The target Tokio runtime must have both I/O and timer
/// facilities enabled.
pub fn spawn<T: DriverTokens>(
    conn: Box<Connection<T>>,
    env: Env<T>,
    sink: Arc<dyn RouteSink<T>>,
    sasl: Option<Box<dyn SaslSession>>,
    rt: &::tokio::runtime::Handle,
) -> TaskHandle<T> {
    let mut env = env;
    env.cfg.cloexec = true;
    let shared = Arc::new(TaskShared::tokio_new(conn, env, sink, sasl));
    // The abort guard is anchored inside the spawned future so that even if the runtime
    // drops the task prior to its first poll, exit notification is reliably dispatched.
    let guard = ExitGuard {
        shared: Arc::clone(&shared),
    };
    let task = run(Arc::clone(&shared));
    drop(rt.spawn(async move {
        let guard = guard;
        task.await;
        drop(guard);
    }));
    TaskHandle::from_shared(shared)
}

/// Main execution loop of the Tokio driver task.
///
/// In each loop pass, the task:
/// 1. Inspects detachment and closure flags.
/// 2. Evaluates the interest step of the I/O engine.
/// 3. Offloads deferred jobs ([`Progress::Wait`]) to [`tokio::task::spawn_blocking`].
/// 4. Dispatches outbound events accumulated in the outbox.
/// 5. Asynchronously awaits readiness on [`AsyncFd`], external notifications via
///    [`tokio::sync::Notify`], or timer expiration.
/// 6. Executes the process step to handle network I/O and protocol state advancements.
///
/// Concurrently, background DNS resolution jobs dispatched across connection reconnects run on the
/// blocking thread pool and are integrated into the wait phase.
// Clippy counts the awaited step futures as stack locals. They live in the task state, which the
// runtime keeps on the heap. Boxing each step would add an allocation to each loop pass.
#[allow(clippy::large_stack_frames)]
pub(crate) async fn run<T: DriverTokens>(shared: Arc<TaskShared<T>>) {
    let mut ob: Outbox<T> = Outbox::new();
    let mut bg: Option<BgTask> = None;
    let rc = loop {
        if shared.detached.load(Ordering::Acquire) {
            break tokio_detach_exit(&shared, &mut ob).await;
        }
        if shared.close_requested.load(Ordering::Acquire) {
            if let Some(rc) = shared.tokio_close_rc() {
                break tokio_final_close(&shared, &mut ob, rc).await;
            }
        }
        let mut out = InterestOut::UNWRITTEN;
        let p = shared.tokio_interest(&mut ob, &mut out);
        // `Settle::Ended` leaves `IoCore` suspended on an abandoned job whose result will never
        // arrive; return directly to the loop top so only the detach or close exit path runs next.
        if tokio_settle(&shared, p, &mut ob, &mut out, &mut bg).await == Settle::Ended {
            continue;
        }
        let plan = shared.tokio_wait_plan(&out);
        let (events, wake, finished) = tokio_wait_with(&shared, plan, &mut bg).await;
        // The process step produces no new interest outputs.
        // Continuation resumes record into scratch storage.
        let mut scratch = InterestOut::UNWRITTEN;
        if let Some(done) = finished {
            let p = shared.tokio_complete_resolve(done, &mut ob, &mut scratch);
            if tokio_settle(&shared, p, &mut ob, &mut scratch, &mut bg).await == Settle::Ended {
                continue;
            }
        }
        let p = shared.tokio_process(events, wake, &mut ob);
        // The settle outcome is ignored because execution proceeds to the loop top whether
        // settlement completed or ended.
        let _settled = tokio_settle(&shared, p, &mut ob, &mut scratch, &mut bg).await;
    };
    shared.tokio_finish(rc);
}

/// Drives an [`IoCore`] [`Progress`] state transition loop to completion across outbox barriers and
/// offloaded blocking jobs.
///
/// In each iteration:
/// 1. Routes all buffered [`Outbox`] items via [`tokio_route`] (offloading any `Item::Sasl` steps
///    to `spawn_blocking`).
/// 2. If `p == Progress::More`, resumes the suspended [`IoCore`] continuation via
///    `shared.tokio_resume(ob, out)`.
/// 3. If `p` is [`Progress::Wait`] or [`Progress::Done`]:
///    - Drains any foreground [`Job`]s staged in `IoCore::deferred` (`shared.tokio_take_jobs()`),
///      executes the next job on `spawn_blocking` via [`tokio_job`], and feeds its [`JobDone`]
///      back into [`IoCore::complete_job`] (`shared.tokio_complete`).
///    - Once all foreground jobs are drained, launches any newly queued background DNS job
///      (`IoCore::bg_start`) into `bg` via [`tokio_start_bg`].
///    - If `p == Progress::Wait` remains active (which occurs when a connection dropped while a
///      background DNS resolution `bg` was already in flight, promoting that lookup to a foreground
///      dependency), awaits `bg.take()` directly and feeds its result into
///      `shared.tokio_complete_resolve`.
///
/// Each blocking job is awaited concurrently with task wakeup notifications (`shared.notify`). If
/// handle detachment or a completable close arrives first, the in-flight job is abandoned and
/// [`Settle::Ended`] is returned (see [`tokio_await_job`]); otherwise [`Settle::Done`] is returned.
async fn tokio_settle<T: DriverTokens>(
    shared: &Arc<TaskShared<T>>,
    first: Progress,
    ob: &mut Outbox<T>,
    out: &mut InterestOut,
    bg: &mut Option<BgTask>,
) -> Settle {
    let mut p = first;
    let mut jobs: VecDeque<Job> = VecDeque::new();
    loop {
        tokio_route(shared, ob).await;
        match p {
            Progress::More => p = shared.tokio_resume(ob, out),
            Progress::Done(_) | Progress::Wait => {
                jobs.extend(shared.tokio_take_jobs());
                // Process any remaining deferred jobs queued by the completed step to prevent
                // dropping deferred work.
                if let Some(job) = jobs.pop_front() {
                    let Some(done) = tokio_job(shared, job).await else {
                        return Settle::Ended;
                    };
                    p = shared.tokio_complete(done, ob, out);
                    continue;
                }
                tokio_start_bg(shared, bg);
                if p != Progress::Wait {
                    return Settle::Done;
                }
                let Some(task) = bg.take() else {
                    return Settle::Done;
                };
                let Some(done) = tokio_await_job(shared, task).await else {
                    return Settle::Ended;
                };
                p = shared.tokio_complete_resolve(done, ob, out);
            }
        }
    }
}

/// Settlement outcome returned by [`tokio_settle`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[must_use]
enum Settle {
    /// The driving step settled to completion and the current pass of the driver loop may
    /// continue.
    Done,
    /// Handle detachment or a completable close abandoned an in-flight job, requiring the task to
    /// proceed directly to its exit path at the loop top.
    Ended,
}

/// Handle tracking a background resolver or offloaded task executing on Tokio's blocking thread
/// pool.
struct BgTask {
    /// Background task join handle.
    handle: ::tokio::task::JoinHandle<JobDone>,
    /// Contextual metadata used to synthesize a result if the task panics or is cancelled.
    kind: JobKind,
}

impl BgTask {
    /// Spawns a deferred blocking job on Tokio's blocking thread pool.
    fn launch<T: DriverTokens>(shared: &Arc<TaskShared<T>>, job: Job) -> Self {
        let kind = JobKind::of(&job);
        let s = Arc::clone(shared);
        let handle = ::tokio::task::spawn_blocking(move || s.tokio_run_job(job));
        Self { handle, kind }
    }

    /// Extracts the task result, falling back to a synthetic error if the worker thread panicked.
    fn result(self, r: Result<JobDone, ::tokio::task::JoinError>) -> JobDone {
        r.unwrap_or_else(|_| self.kind.failed())
    }

    /// Awaits completion of the background task and extracts its resolved outcome.
    async fn join(mut self) -> JobDone {
        let r = (&mut self.handle).await;
        self.result(r)
    }
}

/// Spawns a background DNS resolver task on the blocking pool if one was queued by the preceding
/// step.
fn tokio_start_bg<T: DriverTokens>(shared: &Arc<TaskShared<T>>, bg: &mut Option<BgTask>) {
    if let Some(job) = shared.tokio_take_bg_job() {
        *bg = Some(BgTask::launch(shared, job));
    }
}

/// Metadata describing the category and parameters of a deferred job, used for generating
/// synthetic fallback outcomes.
enum JobKind {
    Resolve { generation: u64, hosts: usize },
    NewConn,
    Probe { endpoint: Endpoint },
}

impl JobKind {
    fn of(job: &Job) -> Self {
        match job {
            Job::Resolve {
                generation, hosts, ..
            } => Self::Resolve {
                generation: *generation,
                hosts: hosts.len(),
            },
            Job::NewConn { .. } => Self::NewConn,
            Job::Probe { endpoint, .. } => Self::Probe {
                endpoint: endpoint.clone(),
            },
        }
    }

    /// Constructs a synthetic failure outcome corresponding to the job type when a worker thread
    /// terminates abnormally.
    fn failed(self) -> JobDone {
        match self {
            Self::Resolve { generation, hosts } => JobDone::Resolved {
                generation,
                results: (0..hosts)
                    .map(|host| HostResolution {
                        host,
                        result: Err(ResolveError {
                            gai_rc: JOB_EAI_FAIL,
                            errno: JOB_EIO,
                        }),
                        skipped_families: Vec::new(),
                    })
                    .collect(),
            },
            Self::NewConn => JobDone::NewConn(Err(TlsSetupError::Resource {
                at: TlsResourceAt::ConnectionNew,
            })),
            Self::Probe { endpoint } => JobDone::Probe {
                endpoint,
                end: ProbeEnd::Done(ProbeOutcome::Failed(None)),
            },
        }
    }
}

/// Dispatches a deferred blocking job to Tokio's blocking thread pool and awaits its completion
/// without blocking runtime worker threads.
///
/// Returns `None` when the task must exit before the job completes (see [`tokio_await_job`]).
async fn tokio_job<T: DriverTokens>(shared: &Arc<TaskShared<T>>, job: Job) -> Option<JobDone> {
    tokio_await_job(shared, BgTask::launch(shared, job)).await
}

/// Awaits a blocking job on Tokio's blocking thread pool while concurrently monitoring task
/// notifications for handle detachment or session closure.
///
/// Because a DNS lookup can block for the full resolver timeout on each host, session closure must
/// not wait for an in-flight lookup to finish (the native threaded driver remains responsive in
/// the same scenario because its lookups execute on a separate `zk-dns` thread). When the task
/// must exit (`shared.tokio_must_exit()` observes detachment or a completable close), this
/// function drops the join handle and returns `None`; the abandoned job runs to completion on the
/// blocking pool (with the resolve loop and connectivity probe stopping at the close flag) and its
/// outcome is never delivered to [`IoCore`]. Any wakeup notification that does not terminate the
/// task is re-posted via `notify.notify_one()` once the job finishes so the subsequent wait cycle
/// in the driver loop still observes the submission or other event that triggered it.
async fn tokio_await_job<T: DriverTokens>(
    shared: &Arc<TaskShared<T>>,
    mut task: BgTask,
) -> Option<JobDone> {
    let mut woken = false;
    loop {
        let mut notified = pin!(shared.notify.notified());
        // Register the notification listener before checking `tokio_must_exit` so a close that
        // sets its flag and notifies after the check still wakes this wait without a lost wakeup.
        notified.as_mut().enable();
        if shared.tokio_must_exit() {
            return None;
        }
        let r = poll_fn(|cx| {
            if let TaskPoll::Ready(r) = Pin::new(&mut task.handle).poll(cx) {
                return TaskPoll::Ready(Some(r));
            }
            match notified.as_mut().poll(cx) {
                TaskPoll::Ready(()) => TaskPoll::Ready(None),
                TaskPoll::Pending => TaskPoll::Pending,
            }
        })
        .await;
        match r {
            Some(r) => {
                if woken {
                    shared.notify.notify_one();
                }
                return Some(task.result(r));
            }
            None => woken = true,
        }
    }
}

/// Drains and routes outbox events in strict sequential order.
///
/// Synchronous events are routed directly until an asynchronous SASL operation is encountered, at
/// which point the SASL mechanism invocation is offloaded to the blocking pool before routing
/// resumes.
async fn tokio_route<T: DriverTokens>(shared: &Arc<TaskShared<T>>, ob: &mut Outbox<T>) {
    while let Some(item) = shared.tokio_route_sync(ob) {
        tokio_sasl(shared, item).await;
    }
}

/// Dispatches an asynchronous native SASL authentication step to the blocking thread pool.
///
/// Successful client-first and challenge-response steps store the resulting credentials in
/// [`Core::sasl`] for consumption by subsequent driver step resumes. The SASL mechanism instance
/// is temporarily extracted from the `io` state during execution. In the event of a worker panic
/// or unrecoverable error, the session is marked as failed.
async fn tokio_sasl<T: DriverTokens>(shared: &Arc<TaskShared<T>>, item: SaslItem) {
    let SaslCall { generation, kind } = item.call;
    let answer = match kind {
        SaslCallKind::Dispose => return,
        SaslCallKind::Start => SaslAnswer::Started {
            generation,
            result: tokio_sasl_call(shared, item.addrs, |s| s.start()).await,
        },
        SaslCallKind::Step(token) => SaslAnswer::Stepped {
            generation,
            result: tokio_sasl_call(shared, None, move |s| {
                s.step(token.as_ref().map(SecretBytes::expose))
            })
            .await,
        },
    };
    shared.tokio_store_sasl(answer);
}

/// Invokes a closure on the native SASL session within a blocking thread pool worker, returning
/// the session upon completion.
///
/// If connection socket addresses are supplied, endpoint identification is configured prior to
/// invoking the mechanism. If no mechanism is registered or the worker panics, an appropriate
/// failure is returned.
async fn tokio_sasl_call<T: DriverTokens, R: Send + 'static>(
    shared: &Arc<TaskShared<T>>,
    addrs: Option<(SocketAddr, SocketAddr)>,
    call: impl FnOnce(&mut dyn SaslSession) -> Result<R, SaslError> + Send + 'static,
) -> Result<R, SaslError> {
    let Some(mut session) = shared.tokio_take_sasl() else {
        return Err(no_mechanism());
    };
    if let Some((local, peer)) = addrs {
        let host = shared.hosts.lock_owner().host_of(peer);
        session.connection(&host, local, peer);
    }
    let run = move || {
        let r = call(&mut *session);
        (session, r)
    };
    match ::tokio::task::spawn_blocking(run).await {
        Ok((session, r)) => {
            shared.tokio_put_sasl(session);
            r
        }
        Err(_) => Err(no_mechanism()),
    }
}

/// Specification of descriptor readiness events and timeout intervals monitored during a wait
/// cycle.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct WaitPlan {
    /// Socket file descriptor and associated read/write interest filter.
    fd: Option<(RawFd, PollEvents)>,
    /// Maximum duration to await readiness; `None` indicates an indefinite wait on wake
    /// notifications.
    timeout: Option<Duration>,
}

/// Waits asynchronously on notifications, socket I/O readiness, and timer expiration, returning
/// observed socket events.
///
/// Each wait cycle registers a fresh [`AsyncFd`] handle with the reactor, which is torn down
/// before executing the subsequent process step. Because the operating system kernel evaluates
/// readiness upon registration, no events are lost.
#[cfg(test)]
async fn tokio_wait<T: DriverTokens>(shared: &TaskShared<T>, plan: WaitPlan) -> PollEvents {
    tokio_wait_with(shared, plan, &mut None).await.0
}

/// Classifies whether an asynchronous reactor wait suspended before waking on a notification
/// while monitoring an active transport socket.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Wake {
    /// Either no notification occurred, the wait resolved immediately without suspending, or no
    /// socket descriptor was registered in the wait plan.
    None,
    /// The wait suspended while monitoring a socket descriptor and subsequently woke via
    /// `TaskShared::notify`, indicating newly submitted work for an idle transport.
    WithSocket,
}

/// Waits asynchronously on notifications, descriptor readiness, interest timeouts, and background
/// DNS resolution.
///
/// Returns the set of observed socket readiness events, the [`Wake`] classification, and any
/// background DNS resolution outcome that completed during the wait.
async fn tokio_wait_with<T: DriverTokens>(
    shared: &TaskShared<T>,
    plan: WaitPlan,
    bg: &mut Option<BgTask>,
) -> (PollEvents, Wake, Option<JobDone>) {
    if plan.timeout == Some(Duration::ZERO) {
        ::tokio::task::yield_now().await;
        let done = match bg.take_if(|t| t.handle.is_finished()) {
            Some(task) => Some(task.join().await),
            None => None,
        };
        return (PollEvents::NONE, Wake::None, done);
    }
    let mut timeout = plan.timeout;
    let afd = match plan.fd {
        None => None,
        Some((fd, want)) => match tokio_register(fd, want) {
            Ok(afd) => Some((afd, want)),
            Err(_) => {
                timeout = Some(timeout.map_or(REGISTER_RETRY, |t| t.min(REGISTER_RETRY)));
                None
            }
        },
    };
    let mut notified = pin!(shared.notify.notified());
    let mut sleep = pin!(timeout.map(::tokio::time::sleep));
    let mut finished = None;
    let mut wake = Wake::None;
    // A notification permit already stored prior to the initial poll indicates an active producer;
    // reporting `Wake::None` allows multiple submissions to coalesce into a single batch flush on
    // the subsequent pass.
    let mut blocked = false;
    let events = poll_fn(|cx| {
        let woken = notified.as_mut().poll(cx).is_ready();
        // A notification arriving after the wait has suspended signals a newly submitted request
        // on an idle driver, enabling immediate transmission during the current pass.
        if woken && blocked && afd.is_some() {
            wake = Wake::WithSocket;
        }
        if let Some(task) = bg.as_mut() {
            if let TaskPoll::Ready(r) = Pin::new(&mut task.handle).poll(cx) {
                finished = Some(r);
            }
        }
        let mut events = PollEvents::NONE;
        if let Some((afd, want)) = &afd {
            if want.read {
                events = tokio_merge(events, tokio_ready_events(afd.poll_read_ready(cx)));
            }
            if want.write {
                events = tokio_merge(events, tokio_ready_events(afd.poll_write_ready(cx)));
            }
        }
        let timed_out = sleep
            .as_mut()
            .as_pin_mut()
            .is_some_and(|s| s.poll(cx).is_ready());
        if woken || timed_out || !events.is_empty() || finished.is_some() {
            TaskPoll::Ready(events)
        } else {
            blocked = true;
            TaskPoll::Pending
        }
    })
    .await;
    let done = finished.and_then(|r| bg.take().map(|task| task.result(r)));
    (events, wake, done)
}

/// Synthesizes `WRITE` readiness when a suspended socket wait wakes on a notification during the
/// active data phase without a pending close request.
///
/// Consuming the notification during the wait prevents busy-looping while allowing newly submitted
/// requests to flush immediately without a second interest and wait cycle.
fn tokio_wake_events(
    events: PollEvents,
    wake: Wake,
    conn: &Transport,
    closing: bool,
) -> PollEvents {
    if wake == Wake::WithSocket && conn.data_phase() && !closing {
        PollEvents {
            write: true,
            ..events
        }
    } else {
        events
    }
}

/// Translates reactor poll readiness into protocol poll events, treating [`TaskPoll::Pending`] as
/// empty.
fn tokio_ready_events(poll: TaskPoll<std::io::Result<AsyncFdReadyGuard<'_, RawFd>>>) -> PollEvents {
    match poll {
        TaskPoll::Ready(Ok(guard)) => tokio_events(guard.ready()),
        TaskPoll::Ready(Err(_)) => tokio_error_events(),
        TaskPoll::Pending => PollEvents::NONE,
    }
}

/// Registers a raw socket file descriptor with the Tokio reactor for a single wait cycle.
///
/// Because [`IoCore`] tracks sockets as [`RawFd`], the safe registration API
/// [`AsyncFd::with_interest`] is used. Linear execution within the driver task ensures descriptor
/// safety. The socket remains valid throughout the wait and is never closed while registered.
#[allow(deprecated)]
fn tokio_register(fd: RawFd, want: PollEvents) -> std::io::Result<AsyncFd<RawFd>> {
    AsyncFd::with_interest(fd, tokio_interest_of(want))
}

/// Converts protocol interest bitmasks into Tokio reactor [`Interest`] filters.
fn tokio_interest_of(want: PollEvents) -> Interest {
    match (want.read, want.write) {
        (true, true) => Interest::READABLE.add(Interest::WRITABLE),
        (false, true) => Interest::WRITABLE,
        _ => Interest::READABLE,
    }
}

/// Translates Tokio readiness flags into protocol event flags.
///
/// Reactor errors and disconnects assert both read and write readiness so subsequent read and
/// write calls promptly observe and report the underlying transport error.
fn tokio_events(ready: Ready) -> PollEvents {
    let error = ready.is_error();
    PollEvents {
        read: error || ready.is_readable() || ready.is_read_closed(),
        write: error || ready.is_writable() || ready.is_write_closed(),
    }
}

/// Constructs a poll event set signaling both read and write readiness to trigger prompt error
/// propagation.
fn tokio_error_events() -> PollEvents {
    PollEvents {
        read: true,
        write: true,
    }
}

/// Computes the bitwise union of two poll event sets.
fn tokio_merge(a: PollEvents, b: PollEvents) -> PollEvents {
    PollEvents {
        read: a.read || b.read,
        write: a.write || b.write,
    }
}

/// Flushes accumulated outbox protocol events until all delivery barriers are cleared.
async fn tokio_drain<T: DriverTokens>(shared: &Arc<TaskShared<T>>, ob: &mut Outbox<T>) {
    for _ in 0..MAX_DRAIN_ROUNDS {
        let barrier = shared.tokio_pump(ob);
        tokio_route(shared, ob).await;
        if !barrier {
            return;
        }
        shared.tokio_resume_stopped();
    }
}

/// Performs graceful detachment shutdown when a handle requests abandonment.
///
/// Drains and routes remaining outbox outputs, drops the active transport without sending a TLS
/// `close_notify`, and exits with `ZOK`.
async fn tokio_detach_exit<T: DriverTokens>(
    shared: &Arc<TaskShared<T>>,
    ob: &mut Outbox<T>,
) -> i32 {
    tokio_drain(shared, ob).await;
    shared.tokio_release(ob);
    ZOK
}

/// Executes final session shutdown procedures following completion of graceful protocol closure.
///
/// 1. If configured with TLS and connected over an active socket, dispatches TLS `close_notify`
///    via `close_flush` on the blocking pool.
/// 2. Invokes `destroy` on the protocol connection and flushes generated outputs.
/// 3. Polls and executes final cleanup actions, including transport teardown and SASL session
///    disposal.
/// 4. Discards unconsumed request tokens and returns the final exit code.
async fn tokio_final_close<T: DriverTokens>(
    shared: &Arc<TaskShared<T>>,
    ob: &mut Outbox<T>,
    rc: i32,
) -> i32 {
    if shared.tokio_wants_close_notify() {
        let s = Arc::clone(shared);
        if let Ok(items) = ::tokio::task::spawn_blocking(move || s.tokio_close_flush()).await {
            ob.extend_items(items);
        }
    }
    shared.tokio_destroy();
    tokio_drain(shared, ob).await;
    for _ in 0..MAX_FINAL_ACTIONS {
        if !shared.tokio_final_action() {
            break;
        }
        tokio_drain(shared, ob).await;
    }
    shared.tokio_release(ob);
    rc
}

impl<T: DriverTokens> TaskHandle<T> {
    /// Submits an asynchronous ZooKeeper request to the protocol state machine and notifies the
    /// driver task.
    ///
    /// If the driver task has terminated or is shutting down, the request fails during validation
    /// with `ZCLOSING`.
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] containing the error code and associated completion token if
    /// rejected.
    pub fn submit(&self, req: Request<T>) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_with_data(req, None)
    }

    /// Submits a request referencing borrowed payload bytes without performing intermediate
    /// buffer allocations.
    ///
    /// See [`Self::submit`] for error handling and lifecycle semantics.
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] with the completion token if submission is rejected.
    pub fn submit_with_data(
        &self,
        req: Request<T>,
        data: Option<&[u8]>,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_by(req, |c, r, now| c.submit_with_data(r, data, now))
    }

    /// Submits a multi-operation transaction consisting of borrowed sub-operations without
    /// copying operation buffers.
    ///
    /// See [`Self::submit`] for error handling and lifecycle semantics.
    ///
    /// # Errors
    ///
    /// Returns a [`SubmitError`] with the completion token if submission is rejected.
    pub fn submit_multi_ref(
        &self,
        req: Request<T>,
        ops: &[MultiOpRef<'_>],
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_by(req, |c, r, now| c.submit_multi_ref(r, ops, now))
    }

    /// Submits a request under the core lock using the provided submission closure and notifies
    /// the driver task on success.
    fn submit_by(
        &self,
        req: Request<T>,
        send: impl FnOnce(&mut Connection<T>, Request<T>, Millis) -> SubmitResult<T>,
    ) -> SubmitResult<T> {
        let now = self.shared.env.clock.now();
        let r = self
            .shared
            .core
            .lock(&mut Unlocked::new_at_entry())
            .submit_or(req, now, ZCLOSING, send);
        if r.is_ok() {
            self.shared.notify.notify_one();
        }
        r
    }

    /// Enqueues authentication credentials for transmission to the ensemble and wakes the driver
    /// task.
    ///
    /// If the session has closed or terminated, the authentication request fails with `ZCLOSING`.
    ///
    /// # Errors
    ///
    /// Returns an [`AddAuthError`] containing the failure status and optional completion token if
    /// rejected.
    pub fn add_auth(
        &self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<T::Auth>,
    ) -> Result<(), AddAuthError<T>> {
        let now = self.shared.env.clock.now();
        let r = self
            .shared
            .core
            .lock(&mut Unlocked::new_at_entry())
            .add_auth_or(scheme, cred, done, now, ZCLOSING);
        if r.is_ok() {
            self.shared.notify.notify_one();
        }
        r
    }

    /// Reconfigures the server connection string dynamically, waking the driver task to initiate
    /// resolution.
    ///
    /// Returns the newly allocated resolver generation identifier, or `0` if the driver has
    /// already terminated.
    pub fn set_servers(&self, hosts: Vec<HostSpec>) -> u64 {
        let now = self.shared.env.clock.now();
        let generation = self
            .shared
            .core
            .lock(&mut Unlocked::new_at_entry())
            .set_servers_native(hosts, now);
        self.shared.notify.notify_one();
        generation
    }

    /// Initiates graceful session termination by enqueuing a close request and notifying the
    /// driver task.
    ///
    /// Subsequent calls on any cloned handle are safe no-ops.
    pub fn close(&self) {
        self.shared.tokio_close();
    }

    /// Asynchronously awaits session termination, returning the final protocol exit code.
    ///
    /// Possible return codes include `ZOK` (graceful termination), `ZOPERATIONTIMEOUT`, or
    /// `ZCONNECTIONLOSS`. If the runtime shuts down or drops the driver task unexpectedly,
    /// `ZCONNECTIONLOSS` is returned.
    pub async fn closed(&self) -> i32 {
        let mut rx = self.shared.exit.subscribe();
        loop {
            let seen = *rx.borrow_and_update();
            if let Some(rc) = seen {
                return rc;
            }
            if rx.changed().await.is_err() {
                let last = *rx.borrow();
                return last.unwrap_or(ZSYSTEMERROR);
            }
        }
    }

    /// Forcefully detaches the client from the ZooKeeper cluster without transmitting a close
    /// request.
    ///
    /// Drains pending outbox events, asserts cancellation to terminate any in-flight network
    /// probe, and signals the driver.
    pub fn detach(&self) {
        let s = &self.shared;
        let now = s.env.clock.now();
        {
            // Synchronize detachment under the core lock to ensure exactly once connection
            // abandonment.
            let mut proof = Unlocked::new_at_entry();
            let mut core = s.core.lock(&mut proof);
            if s.detached.load(Ordering::Acquire) {
                return;
            }
            if let Some(conn) = core.conn.as_deref_mut() {
                conn.abandon(now);
            }
            s.detached.store(true, Ordering::Release);
            s.close_requested.store(true, Ordering::Release);
        }
        s.notify.notify_one();
    }

    /// Notifies the read gate that a dispatched item has been processed, waking the driver task if
    /// backpressure clears.
    pub fn taken(&self) {
        if self.shared.gate.taken() {
            self.shared.notify.notify_one();
        }
    }

    /// Creates a lightweight backpressure gate reporter decoupled from session ownership.
    #[must_use]
    pub fn gate_reporter(&self) -> TaskGate<T> {
        TaskGate {
            shared: Arc::clone(&self.shared),
        }
    }

    /// Returns a reference to the read backpressure gate, primarily for telemetry, metrics, and
    /// testing.
    #[must_use]
    pub fn gate(&self) -> &ReadGate {
        &self.shared.gate
    }

    /// Returns the integer code representing the current connection lifecycle state, or `0` if
    /// terminated.
    #[must_use]
    pub fn state(&self) -> i32 {
        read_at_entry(&self.shared.core, |c| c.state().code()).unwrap_or(0)
    }

    /// Returns the current session identity credentials (session ID and password).
    #[must_use]
    pub fn client_id(&self) -> ClientId {
        read_at_entry(&self.shared.core, Connection::client_id).unwrap_or_default()
    }

    /// Retrieves the causal reason for the most recent connection disconnect, if available.
    #[must_use]
    pub fn last_teardown_cause(&self) -> Option<Cause> {
        read_at_entry(&self.shared.core, Connection::last_teardown_cause).flatten()
    }

    /// Executes an inspection closure against the active connection state under the core lock,
    /// returning `None` if terminated.
    ///
    /// Allows inspecting protocol internals such as `last_zxid` or `pending_len` without blocking
    /// or reentering handle methods.
    pub fn read_conn<R>(&self, f: impl FnOnce(&Connection<T>) -> R) -> Option<R> {
        read_at_entry(&self.shared.core, f)
    }

    /// Executes a mutating closure against the connection under the core lock and notifies the
    /// driver task, returning `None` if terminated.
    ///
    /// Employed during watcher detachment or batch operations where modifications generate
    /// outbound events requiring prompt dispatch.
    pub fn with_conn<R>(
        &self,
        f: impl FnOnce(&mut Connection<T>, zk_proto::Millis) -> R,
    ) -> Option<R> {
        let r = with_conn_at_entry(&self.shared.core, self.shared.env.clock.now(), f);
        self.shared.notify.notify_one();
        r
    }
}

impl<T: DriverTokens> TaskShared<T> {
    /// Deferral policy enforced by the Tokio driver, offloading all blocking operations to worker
    /// threads.
    pub(crate) const DEFER: DeferPolicy = DeferPolicy::ALL;

    /// Event outbox pumping flavor corresponding to native Rust asynchronous dispatching.
    pub(crate) const FLAVOR: Flavor = Flavor::Native;
}

// Synchronous driver task operations executing under transient lock scopes.
impl<T: DriverTokens> TaskShared<T> {
    /// Initiates graceful session teardown by transitioning connection state, enqueuing a close
    /// frame, and waking the driver.
    fn tokio_close(&self) {
        if self.close_requested.swap(true, Ordering::AcqRel) {
            return;
        }
        let now = self.env.clock.now();
        {
            let mut proof = Unlocked::new_at_entry();
            let mut core = self.core.lock(&mut proof);
            if let Some(conn) = core.conn.as_deref_mut() {
                conn.begin_close(now);
                let queued = conn.queue_close_op();
                self.graceful.store(queued, Ordering::Release);
            }
        }
        self.notify.notify_one();
    }

    /// Constructs a newly initialized shared driver state bundle.
    fn tokio_new(
        conn: Box<Connection<T>>,
        env: Env<T>,
        sink: Arc<dyn RouteSink<T>>,
        sasl: Option<Box<dyn SaslSession>>,
    ) -> Self {
        let gate = ReadGate::new(env.cfg.dispatch_queue_capacity);
        let (exit, _) = ::tokio::sync::watch::channel(None);
        let sasl_hosts = sasl.is_some();
        Self {
            core: Mutex::new(Core::new(conn)),
            io: Mutex::new(TaskIo {
                io: IoCore::new(),
                sasl,
            }),
            notify: ::tokio::sync::Notify::new(),
            gate,
            sink,
            env,
            close_requested: Arc::new(AtomicBool::new(false)),
            detached: AtomicBool::new(false),
            exit,
            hosts: Mutex::new(SaslHosts::default()),
            sasl_hosts,
            graceful: AtomicBool::new(false),
        }
    }

    /// Constructs an execution context for stepping the I/O engine, binding cancellation signals
    /// and flow-control gates.
    fn tokio_step_ctx(&self) -> StepCtx<'_, T> {
        self.env.step_ctx(
            &self.core,
            Self::FLAVOR,
            Self::DEFER,
            None,
            Some(&self.close_requested),
            Some(&self.gate),
        )
    }

    /// Prepares execution resources for stepping `IoCore`, constructing lock proofs and
    /// acquiring the `io` mutex.
    fn tokio_step_io(&self) -> (Unlocked, StepCtx<'_, T>, Guard<'_, TaskIo>) {
        let proof = Unlocked::new_at_entry();
        let cx = self.tokio_step_ctx();
        (proof, cx, self.io.lock_owner())
    }

    fn tokio_interest(&self, ob: &mut Outbox<T>, out: &mut InterestOut) -> Progress {
        let (mut proof, cx, mut io) = self.tokio_step_io();
        io.io.interest_step(&cx, &mut proof, ob, out)
    }

    /// Executes the I/O process step for the readiness events observed during the preceding wait.
    ///
    /// When `wake` is [`Wake::WithSocket`] and the transport is in the data phase with no pending
    /// close request, `WRITE` readiness is synthesized so newly submitted requests flush within
    /// the current reactor pass. Notifications stored prior to entering the wait omit this flag so
    /// concurrent bursts from an active producer coalesce into a single batched transmission on
    /// the next pass.
    fn tokio_process(&self, events: PollEvents, wake: Wake, ob: &mut Outbox<T>) -> Progress {
        let (mut proof, cx, mut io) = self.tokio_step_io();
        let closing = self.close_requested.load(Ordering::Acquire);
        let events = tokio_wake_events(events, wake, &io.io.conn, closing);
        io.io.process_step(&cx, &mut proof, events, ob)
    }

    fn tokio_resume(&self, ob: &mut Outbox<T>, out: &mut InterestOut) -> Progress {
        let (mut proof, cx, mut io) = self.tokio_step_io();
        io.io.resume(&cx, &mut proof, ob, out)
    }

    fn tokio_complete(&self, done: JobDone, ob: &mut Outbox<T>, out: &mut InterestOut) -> Progress {
        let (mut proof, cx, mut io) = self.tokio_step_io();
        io.io.complete_job(&cx, &mut proof, done, ob, out)
    }

    fn tokio_complete_resolve(
        &self,
        done: JobDone,
        ob: &mut Outbox<T>,
        out: &mut InterestOut,
    ) -> Progress {
        let (mut proof, cx, mut io) = self.tokio_step_io();
        io.io.complete_resolve(&cx, &mut proof, done, ob, out)
    }

    fn tokio_take_jobs(&self) -> Vec<Job> {
        self.io.lock_owner().io.take_jobs()
    }

    fn tokio_take_bg_job(&self) -> Option<Job> {
        self.io.lock_owner().io.take_bg_job()
    }

    /// Executes a blocking driver job without holding driver locks, intended for execution within
    /// the blocking thread pool.
    fn tokio_run_job(&self, job: Job) -> JobDone {
        let proof = Unlocked::new_at_entry();
        let cx = JobCtx {
            host: &*self.env.host,
            probe: ProbeCtx {
                poll: &*self.env.poll,
                clock: &*self.env.clock,
                wake: None,
                abort: Some(&self.close_requested),
                tls: self.env.tls.as_deref(),
                cloexec: self.env.cfg.cloexec,
                phase_ms: self.env.cfg.probe_phase_ms,
            },
        };
        let names = self.sasl_hosts.then_some(&job).and_then(SaslHosts::names);
        let done = run_job(&cx, &proof, job);
        if let Some(names) = names {
            self.hosts.lock_owner().record(&names, &done);
        }
        done
    }

    /// Synchronously routes outbound events to the destination sink until a SASL interaction is
    /// encountered.
    ///
    /// Events subject to backpressure increment the read gate's outstanding counter. Thread
    /// management and authentication lockpoint markers are inert under native dispatching.
    fn tokio_route_sync(&self, ob: &mut Outbox<T>) -> Option<SaslItem> {
        while let Some(item) = ob.pop() {
            match item {
                Item::Sasl(sasl) => return Some(sasl),
                Item::Thread { .. } | Item::AuthLockpoint => {}
                other => {
                    if self.sink.route(other) {
                        self.gate.routed();
                    }
                }
            }
        }
        None
    }

    fn tokio_take_sasl(&self) -> Option<Box<dyn SaslSession>> {
        self.io.lock_owner().sasl.take()
    }

    fn tokio_put_sasl(&self, session: Box<dyn SaslSession>) {
        self.io.lock_owner().sasl = Some(session);
    }

    fn tokio_store_sasl(&self, answer: SaslAnswer) {
        let mut proof = Unlocked::new_at_entry();
        self.core.lock(&mut proof).sasl = Some(answer);
    }

    /// Computes the descriptor readiness and timeout parameters governing the upcoming poll cycle.
    ///
    /// If the interest step produced explicit polling directives, those descriptor interests and
    /// deadlines are applied. If unwritten, an unrecoverable or closed connection awaits external
    /// notification exclusively, while an active session polls with a default timeout bounded by a
    /// fraction of its negotiated receive timeout.
    fn tokio_wait_plan(&self, out: &InterestOut) -> WaitPlan {
        if out.written {
            let want = PollEvents::from_bits(out.bits);
            let fd = (out.fd >= 0 && !want.is_empty()).then_some((out.fd, want));
            let ms = u64::try_from(out.timeout_ms).unwrap_or(0);
            return WaitPlan {
                fd,
                timeout: Some(Duration::from_millis(ms)),
            };
        }
        let mut proof = Unlocked::new_at_entry();
        let facts = read_conn(&self.core, &mut proof, |c| {
            (c.state().is_unrecoverable(), c.recv_timeout())
        });
        let timeout = match facts {
            None | Some((true, _)) => None,
            Some((false, recv)) => {
                let ms = u64::try_from(recv)
                    .unwrap_or(0)
                    .checked_div(60)
                    .unwrap_or(0);
                Some(Duration::from_millis(ms.max(1)))
            }
        };
        WaitPlan { fd: None, timeout }
    }

    /// Returns `true` when the loop top of `run` would exit: upon handle detachment, or upon a
    /// requested close whose close step can complete (`tokio_close_rc` returns `Some`).
    fn tokio_must_exit(&self) -> bool {
        self.detached.load(Ordering::Acquire)
            || (self.close_requested.load(Ordering::Acquire) && self.tokio_close_rc().is_some())
    }

    /// Evaluates whether session closure has completed, returning the final status code or `None`
    /// if teardown remains pending.
    fn tokio_close_rc(&self) -> Option<i32> {
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        let mut core = self.core.lock(&mut proof);
        let Some(conn) = core.conn.as_deref_mut() else {
            return Some(ZOK);
        };
        match conn.close_step(now) {
            CloseProgress::Complete => Some(conn.close_outcome().unwrap_or(ZOK)),
            CloseProgress::Pending => None,
        }
    }

    /// Determines whether the session closure sequence requires dispatching a TLS `close_notify`
    /// alert.
    fn tokio_wants_close_notify(&self) -> bool {
        self.graceful.load(Ordering::Acquire)
            && self.env.tls.is_some()
            && self.io.lock_owner().io.has_socket()
    }

    /// Executes synchronous TLS transport flush and `close_notify` alert transmission on the
    /// blocking thread pool.
    fn tokio_close_flush(&self) -> Vec<Item<T>> {
        let (mut proof, cx, mut io) = self.tokio_step_io();
        let mut ob = Outbox::new();
        let _flush_rc = io.io.close_flush(&cx, &mut proof, &mut ob);
        drop(io);
        std::iter::from_fn(|| ob.pop()).collect()
    }

    fn tokio_destroy(&self) {
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        if let Some(conn) = self.core.lock(&mut proof).conn.as_deref_mut() {
            conn.destroy(now);
        }
    }

    /// Pumps protocol engine outputs into the outbox buffer, returning `true` when a
    /// synchronization barrier is encountered.
    fn tokio_pump(&self, ob: &mut Outbox<T>) -> bool {
        let mut proof = Unlocked::new_at_entry();
        self.core
            .lock(&mut proof)
            .pump(Self::FLAVOR, ob, &*self.env.host)
    }

    fn tokio_resume_stopped(&self) {
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        self.core.lock(&mut proof).resume_stopped(now);
    }

    /// Dispatches terminal cleanup actions following connection destruction, such as dropping
    /// active transports and releasing SASL state.
    fn tokio_final_action(&self) -> bool {
        let now = self.env.clock.now();
        let mut io = self.io.lock_owner();
        let action = {
            let mut proof = Unlocked::new_at_entry();
            let mut core = self.core.lock(&mut proof);
            match core.conn.as_deref_mut() {
                Some(conn) => conn.poll_action(now),
                None => None,
            }
        };
        match action {
            None => false,
            Some(Action::CloseTransport) => {
                io.io.drop_transport();
                true
            }
            Some(Action::Sasl(SaslCall {
                kind: SaslCallKind::Dispose,
                ..
            })) => {
                io.sasl = None;
                true
            }
            Some(_) => true,
        }
    }

    /// Reclaims driver resources upon terminal exit, tearing down transport and authentication
    /// handles and discarding lingering tokens.
    fn tokio_release(&self, ob: &mut Outbox<T>) {
        {
            let mut io = self.io.lock_owner();
            io.io.drop_transport();
            io.sasl = None;
        }
        self.tokio_discard_conn();
        ob.finish(&*self.env.host);
    }

    /// Broadcasts the final driver status code across the exit watch channel.
    fn tokio_finish(&self, rc: i32) {
        self.exit.send_if_modified(|v| {
            if v.is_some() {
                return false;
            }
            *v = Some(rc);
            true
        });
    }

    /// Aborts driver execution when the driving future is dropped prematurely, notifying waiters
    /// with `ZCONNECTIONLOSS`.
    fn tokio_abort_exit(&self) {
        if self.exit.borrow().is_some() {
            return;
        }
        if let Some(mut io) = self.io.try_lock() {
            io.io.drop_transport();
            io.sasl = None;
        }
        self.tokio_discard_conn();
        self.tokio_finish(ZCONNECTIONLOSS);
    }

    /// Extracts the connection instance and invokes token discard hooks on remaining in-flight
    /// requests.
    fn tokio_discard_conn(&self) {
        let conn = self.core.lock(&mut Unlocked::new_at_entry()).conn.take();
        if let Some(conn) = conn {
            discard_leftovers(&*self.env.host, conn);
        }
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::*;
    use crate::host::HostLeaves;

    use std::io::Write;
    use std::net::{SocketAddr, TcpListener, TcpStream};
    use std::os::fd::{AsRawFd, OwnedFd};
    use std::time::Instant;

    use zk_proto::rc::ZOPERATIONTIMEOUT;
    use zk_proto::{OpResult, Origin, PathIn, SaslStep, State};

    use zk_testkit::tokens::{CompletionId, TestTokens};

    use crate::net::sock::TcpSock;
    use crate::testutil::{TestHost, exists_request, native_connection};

    /// Test event sink that buffers routed items in memory and tracks counted completion results.
    #[derive(Default)]
    struct Keep(std::sync::Mutex<Vec<Item<TestTokens>>>);

    impl RouteSink<TestTokens> for Keep {
        fn route(&self, item: Item<TestTokens>) -> bool {
            let counts = matches!(item, Item::Result { .. } | Item::AuthResult { .. });
            self.0.lock().unwrap().push(item);
            counts
        }
    }

    /// Test SASL mechanism returning a static mechanism name without authentication tokens.
    struct Fixed;

    impl SaslSession for Fixed {
        fn start(&mut self) -> Result<(String, SaslStep), SaslError> {
            Ok((
                "FIXED".to_owned(),
                SaslStep {
                    token: None,
                    done: true,
                },
            ))
        }

        fn step(&mut self, _challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
            Ok(SaslStep {
                token: None,
                done: true,
            })
        }
    }

    type Parts = (TaskHandle<TestTokens>, Arc<Keep>);

    fn handle_with(sasl: Option<Box<dyn SaslSession>>) -> Parts {
        let leaves: Arc<dyn HostLeaves<TestTokens>> = Arc::new(TestHost::new());
        let mut env = Env::new(leaves);
        env.cfg.dispatch_queue_capacity = 2;
        let sink = Arc::new(Keep::default());
        let shared = TaskShared::tokio_new(native_connection(), env, sink.clone(), sasl);
        (TaskHandle::from_shared(Arc::new(shared)), sink)
    }

    fn handle() -> TaskHandle<TestTokens> {
        handle_with(None).0
    }

    fn rt() -> ::tokio::runtime::Runtime {
        ::tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
    }

    fn exists(id: u64) -> Request<TestTokens> {
        exists_request(PathIn::Native("/".into()), id)
    }

    /// Verifies whether the driver notification handle has been signaled, consuming the
    /// notification.
    fn notified(rt: &::tokio::runtime::Runtime, h: &TaskHandle<TestTokens>) -> bool {
        rt.block_on(async {
            ::tokio::time::timeout(Duration::from_millis(20), h.shared.notify.notified())
                .await
                .is_ok()
        })
    }

    fn connected_pair() -> (TcpStream, TcpStream) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let a = TcpStream::connect(l.local_addr().unwrap()).unwrap();
        let (b, _) = l.accept().unwrap();
        (a, b)
    }

    #[test]
    fn submit_queues_and_notifies() {
        let rt = rt();
        let h = handle();
        assert!(!notified(&rt, &h));
        assert!(h.submit(exists(1)).is_ok());
        assert!(notified(&rt, &h));
        assert!(!notified(&rt, &h));
        assert_eq!(h.state(), State::NotConnected.code());
    }

    #[test]
    fn close_sets_the_flags_once() {
        let rt = rt();
        let h = handle();
        h.close();
        assert!(h.shared.close_requested.load(Ordering::Acquire));
        assert!(!h.shared.detached.load(Ordering::Acquire));
        // In the unconnected state, no CLOSE_OP is generated; termination completes immediately
        // without graceful handshake.
        assert!(!h.shared.graceful.load(Ordering::Acquire));
        assert!(notified(&rt, &h));
        h.close();
        assert!(!notified(&rt, &h));
        // New requests submitted during closure are rejected with ZCLOSING.
        let err = h.submit(exists(2)).err().unwrap();
        assert_eq!(err.code, ZCLOSING);
        assert!(!notified(&rt, &h));
        // Closure without queued CLOSE_OP operations completes immediately with ZOK.
        assert_eq!(h.shared.tokio_close_rc(), Some(ZOK));
    }

    #[test]
    fn the_drop_of_the_last_clone_starts_the_close() {
        let rt = rt();
        let h = handle();
        let shared = Arc::clone(&h.shared);
        let other = h.clone();
        drop(h);
        // Dropping one handle clone does not terminate the session while other handle references
        // exist.
        assert!(!shared.close_requested.load(Ordering::Acquire));
        drop(other);
        assert!(shared.close_requested.load(Ordering::Acquire));
        let woke = rt.block_on(async {
            ::tokio::time::timeout(Duration::from_millis(20), shared.notify.notified())
                .await
                .is_ok()
        });
        assert!(woke);
        assert_eq!(shared.tokio_close_rc(), Some(ZOK));
    }

    #[test]
    fn detach_abandons_and_sets_the_flags() {
        let rt = rt();
        let h = handle();
        assert!(h.submit(exists(3)).is_ok());
        assert!(notified(&rt, &h));
        h.detach();
        assert!(h.shared.detached.load(Ordering::Acquire));
        assert!(h.shared.close_requested.load(Ordering::Acquire));
        assert!(notified(&rt, &h));
        let mut proof = Unlocked::new_at_entry();
        assert_eq!(
            read_conn(&h.shared.core, &mut proof, Connection::is_closing),
            Some(true)
        );
    }

    #[test]
    fn handles_after_the_end_fail_with_closing() {
        let rt = rt();
        let h = handle();
        let mut ob = Outbox::new();
        h.shared.tokio_release(&mut ob);
        let err = h.submit(exists(4)).err().unwrap();
        assert_eq!(err.code, ZCLOSING);
        assert_eq!(err.token, CompletionId::new(4));
        let err = h
            .add_auth(
                JString::Bytes(b"digest".to_vec()),
                SecretBytes::from_slice(b"u:p"),
                None,
            )
            .err()
            .unwrap();
        assert_eq!(err.code, ZCLOSING);
        assert!(!notified(&rt, &h));
        assert_eq!(h.set_servers(Vec::new()), 0);
        assert_eq!(h.state(), 0);
        assert_eq!(h.shared.tokio_close_rc(), Some(ZOK));
    }

    #[test]
    fn closed_gives_the_exit_value_to_every_waiter() {
        let rt = rt();
        let h = handle();
        let h2 = h.clone();
        let waiter = rt.spawn(async move { h2.closed().await });
        h.shared.tokio_finish(ZOPERATIONTIMEOUT);
        h.shared.tokio_finish(ZOK);
        assert_eq!(rt.block_on(waiter).unwrap(), ZOPERATIONTIMEOUT);
        assert_eq!(rt.block_on(h.closed()), ZOPERATIONTIMEOUT);
    }

    #[test]
    fn taken_reopens_the_gate_and_notifies() {
        let rt = rt();
        let h = handle();
        h.gate().routed();
        h.gate().routed();
        assert!(!h.gate().is_open());
        h.taken();
        assert!(h.gate().is_open());
        assert!(notified(&rt, &h));
        h.taken();
        assert!(!notified(&rt, &h));
    }

    #[test]
    fn exit_guard_releases_and_sends_connection_loss() {
        let rt = rt();
        let h = handle();
        drop(ExitGuard {
            shared: Arc::clone(&h.shared),
        });
        assert_eq!(h.state(), 0);
        assert_eq!(rt.block_on(h.closed()), ZCONNECTIONLOSS);
    }

    #[test]
    fn a_dropped_runtime_ends_an_unpolled_task() {
        let leaves: Arc<dyn HostLeaves<TestTokens>> = Arc::new(TestHost::new());
        let rt = rt();
        let h = spawn(
            native_connection(),
            Env::new(leaves),
            Arc::new(Keep::default()),
            None,
            rt.handle(),
        );
        assert!(h.shared.env.cfg.cloexec);
        // Dropping the single threaded runtime drops unpolled tasks, asserting the exit guard.
        drop(rt);
        assert_eq!(*h.shared.exit.borrow(), Some(ZCONNECTIONLOSS));
        assert_eq!(h.state(), 0);
    }

    #[test]
    fn route_counts_items_and_answers_sasl() {
        let rt = rt();
        let (h, sink) = handle_with(Some(Box::new(Fixed)));
        let mut ob = Outbox::new();
        ob.push(Item::Result {
            token: CompletionId::new(5),
            result: OpResult {
                rc: ZCONNECTIONLOSS,
                zxid: 0,
                origin: Origin::Teardown,
                reply: None,
            },
            delivery_logs: Vec::new(),
        });
        ob.push(Item::Sasl(SaslItem {
            call: SaslCall {
                generation: 7,
                kind: SaslCallKind::Start,
            },
            addrs: None,
        }));
        ob.push(Item::AuthLockpoint);
        rt.block_on(tokio_route(&h.shared, &mut ob));
        assert!(ob.is_empty());
        assert_eq!(sink.0.lock().unwrap().len(), 1);
        assert_eq!(h.gate().count(), 1);
        let answer = {
            let mut proof = Unlocked::new_at_entry();
            h.shared.core.lock(&mut proof).sasl.take()
        };
        match answer {
            Some(SaslAnswer::Started {
                generation: 7,
                result: Ok((name, _)),
            }) => assert_eq!(name, "FIXED"),
            other => panic!("unexpected answer {other:?}"),
        }
        // Verify that the SASL mechanism is restored to the driver state following offloaded
        // execution.
        assert!(h.shared.io.lock_owner().sasl.is_some());
    }

    /// Test SASL mechanism recording connection endpoint parameters passed during authentication.
    struct Conn(Arc<std::sync::Mutex<Vec<Seen>>>);

    /// Recorded endpoint tuple containing host bytes, local socket address, and peer socket
    /// address.
    type Seen = (Vec<u8>, SocketAddr, SocketAddr);

    impl SaslSession for Conn {
        fn start(&mut self) -> Result<(String, SaslStep), SaslError> {
            Fixed.start()
        }

        fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
            Fixed.step(challenge)
        }

        fn connection(&mut self, host: &[u8], local: SocketAddr, peer: SocketAddr) {
            self.0.lock().unwrap().push((host.to_vec(), local, peer));
        }
    }

    #[test]
    fn sasl_start_gives_the_resolved_host_to_the_mechanism() {
        let rt = rt();
        let host = Arc::new(TestHost::new());
        let peer = SocketAddr::from(([10, 0, 0, 1], 2181));
        let local = SocketAddr::from(([127, 0, 0, 1], 40000));
        host.set_addrs(b"zk1.example", vec![peer]);
        let leaves: Arc<dyn HostLeaves<TestTokens>> = host;
        let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
        let mech: Box<dyn SaslSession> = Box::new(Conn(Arc::clone(&seen)));
        let shared = Arc::new(TaskShared::tokio_new(
            native_connection(),
            Env::new(leaves),
            Arc::new(Keep::default()),
            Some(mech),
        ));
        let hosts = zk_proto::legacy::connect::parse_servers(b"zk1.example:2181")
            .unwrap()
            .into_iter()
            .map(zk_proto::HostSpec::Legacy)
            .collect();
        let done = shared.tokio_run_job(Job::Resolve {
            generation: 1,
            hosts,
            shuffle: false,
        });
        assert!(matches!(done, JobDone::Resolved { generation: 1, .. }));
        let mut ob = Outbox::new();
        ob.push(Item::Sasl(SaslItem {
            call: SaslCall {
                generation: 1,
                kind: SaslCallKind::Start,
            },
            addrs: Some((local, peer)),
        }));
        rt.block_on(tokio_route(&shared, &mut ob));
        assert_eq!(
            *seen.lock().unwrap(),
            vec![(b"zk1.example".to_vec(), local, peer)]
        );
    }

    #[test]
    fn a_resolve_job_records_hosts_only_with_a_mechanism() {
        let peer = SocketAddr::from(([10, 0, 0, 1], 2181));
        let with: Box<dyn SaslSession> = Box::new(Conn(Arc::default()));
        for (sasl, want) in [(None, &b"10.0.0.1"[..]), (Some(with), b"zk1.example")] {
            let host = Arc::new(TestHost::new());
            host.set_addrs(b"zk1.example", vec![peer]);
            let leaves: Arc<dyn HostLeaves<TestTokens>> = host;
            let shared = TaskShared::tokio_new(
                native_connection(),
                Env::new(leaves),
                Arc::new(Keep::default()),
                sasl,
            );
            let hosts = zk_proto::legacy::connect::parse_servers(b"zk1.example:2181")
                .unwrap()
                .into_iter()
                .map(zk_proto::HostSpec::Legacy)
                .collect();
            let done = shared.tokio_run_job(Job::Resolve {
                generation: 1,
                hosts,
                shuffle: false,
            });
            assert!(matches!(done, JobDone::Resolved { generation: 1, .. }));
            assert_eq!(shared.hosts.lock_owner().host_of(peer), want);
        }
    }

    #[test]
    fn sasl_with_no_mechanism_is_a_mechanism_error() {
        let rt = rt();
        let h = handle();
        let mut ob = Outbox::new();
        ob.push(Item::Sasl(SaslItem {
            call: SaslCall {
                generation: 2,
                kind: SaslCallKind::Step(None),
            },
            addrs: None,
        }));
        rt.block_on(tokio_route(&h.shared, &mut ob));
        let mut proof = Unlocked::new_at_entry();
        let answer = h.shared.core.lock(&mut proof).sasl.take();
        assert!(matches!(
            answer,
            Some(SaslAnswer::Stepped {
                generation: 2,
                result: Err(SaslError {
                    rc: ZSYSTEMERROR,
                    ..
                })
            })
        ));
    }

    #[test]
    fn wait_plan_follows_the_interest_outputs() {
        let h = handle();
        let out = InterestOut {
            rc: ZOK,
            written: true,
            fd: 9,
            bits: crate::net::poll::ZOOKEEPER_READ,
            timeout_ms: 250,
        };
        let plan = h.shared.tokio_wait_plan(&out);
        let read = PollEvents {
            read: true,
            write: false,
        };
        assert_eq!(plan.fd, Some((9, read)));
        assert_eq!(plan.timeout, Some(Duration::from_millis(250)));
        let none = InterestOut { bits: 0, ..out };
        assert_eq!(h.shared.tokio_wait_plan(&none).fd, None);
        // When unwritten, wait duration falls back to recv_timeout / 60 with a 1 ms floor.
        let plan = h.shared.tokio_wait_plan(&InterestOut::UNWRITTEN);
        assert!(plan.fd.is_none());
        assert!(plan.timeout.is_some_and(|t| t >= Duration::from_millis(1)));
        // Without an active connection, the wait plan listens exclusively for notification
        // signals.
        let mut ob = Outbox::new();
        h.shared.tokio_release(&mut ob);
        let plan = h.shared.tokio_wait_plan(&InterestOut::UNWRITTEN);
        assert_eq!(plan.timeout, None);
    }

    #[test]
    fn wait_reports_socket_readiness() {
        let rt = rt();
        let h = handle();
        let (a, mut b) = connected_pair();
        a.set_nonblocking(true).unwrap();
        let read = PollEvents {
            read: true,
            write: false,
        };
        let plan = WaitPlan {
            fd: Some((a.as_raw_fd(), read)),
            timeout: Some(Duration::from_millis(20)),
        };
        // In the absence of incoming data, the wait plan terminates upon timer expiration.
        assert_eq!(rt.block_on(tokio_wait(&h.shared, plan)), PollEvents::NONE);
        b.write_all(b"x").unwrap();
        let plan = WaitPlan {
            timeout: Some(Duration::from_secs(5)),
            ..plan
        };
        assert!(rt.block_on(tokio_wait(&h.shared, plan)).read);
        // Established connected sockets report writable readiness.
        let write = PollEvents {
            read: false,
            write: true,
        };
        let plan = WaitPlan {
            fd: Some((a.as_raw_fd(), write)),
            timeout: Some(Duration::from_secs(5)),
        };
        assert!(rt.block_on(tokio_wait(&h.shared, plan)).write);
    }

    #[test]
    fn wait_ends_on_notify_and_on_a_zero_timeout() {
        let rt = rt();
        let h = handle();
        h.shared.notify.notify_one();
        let plan = WaitPlan {
            fd: None,
            timeout: None,
        };
        assert_eq!(rt.block_on(tokio_wait(&h.shared, plan)), PollEvents::NONE);
        let plan = WaitPlan {
            fd: None,
            timeout: Some(Duration::ZERO),
        };
        assert_eq!(rt.block_on(tokio_wait(&h.shared, plan)), PollEvents::NONE);
    }

    /// Suspends in [`tokio_wait_with`] and triggers an asynchronous wakeup notification from a
    /// concurrent task after a brief delay.
    fn wait_then_wake(
        rt: &::tokio::runtime::Runtime,
        h: &TaskHandle<TestTokens>,
        plan: WaitPlan,
    ) -> (PollEvents, Wake, Option<JobDone>) {
        rt.block_on(async {
            let shared = Arc::clone(&h.shared);
            let waker = ::tokio::spawn(async move {
                ::tokio::time::sleep(Duration::from_millis(20)).await;
                shared.notify.notify_one();
            });
            let r = tokio_wait_with(&h.shared, plan, &mut None).await;
            waker.await.unwrap();
            r
        })
    }

    #[test]
    fn a_wake_is_reported_only_when_the_wait_blocked_and_watched_the_socket() {
        let rt = rt();
        let h = handle();
        let (a, _b) = connected_pair();
        a.set_nonblocking(true).unwrap();
        let read = PollEvents {
            read: true,
            write: false,
        };
        let with_fd = WaitPlan {
            fd: Some((a.as_raw_fd(), read)),
            timeout: Some(Duration::from_secs(5)),
        };
        let (events, wake, done) = wait_then_wake(&rt, &h, with_fd);
        assert_eq!(events, PollEvents::NONE);
        assert_eq!(wake, Wake::WithSocket);
        assert!(done.is_none());
        // A pre-stored notification permit resolves the wait immediately while reporting
        // `Wake::None`.
        h.shared.notify.notify_one();
        let start = Instant::now();
        let (events, wake, _) = rt.block_on(tokio_wait_with(&h.shared, with_fd, &mut None));
        assert!(start.elapsed() < Duration::from_secs(4));
        assert_eq!(events, PollEvents::NONE);
        assert_eq!(wake, Wake::None);
        let no_fd = WaitPlan {
            fd: None,
            timeout: Some(Duration::from_secs(5)),
        };
        let (_, wake, _) = wait_then_wake(&rt, &h, no_fd);
        assert_eq!(wake, Wake::None);
        h.shared.notify.notify_one();
        let (_, wake, _) = rt.block_on(tokio_wait_with(&h.shared, no_fd, &mut None));
        assert_eq!(wake, Wake::None);
        // Expiration of the poll timer without a stored notification permit reports `Wake::None`.
        let short = WaitPlan {
            timeout: Some(Duration::from_millis(20)),
            ..with_fd
        };
        let start = Instant::now();
        let (_, wake, _) = rt.block_on(tokio_wait_with(&h.shared, short, &mut None));
        assert!(start.elapsed() >= Duration::from_millis(20));
        assert_eq!(wake, Wake::None);
        // Zero-duration timeouts yield immediately without polling the notification channel.
        let zero = WaitPlan {
            timeout: Some(Duration::ZERO),
            ..with_fd
        };
        h.shared.notify.notify_one();
        let (_, wake, _) = rt.block_on(tokio_wait_with(&h.shared, zero, &mut None));
        assert_eq!(wake, Wake::None);
    }

    #[test]
    fn a_wake_adds_write_only_in_the_data_phase() {
        let (a, _b) = connected_pair();
        let plain = Transport::Plain(TcpSock::from_owned(OwnedFd::from(a)));
        let read = PollEvents {
            read: true,
            write: false,
        };
        let both = PollEvents {
            read: true,
            write: true,
        };
        assert_eq!(
            tokio_wake_events(read, Wake::WithSocket, &plain, false),
            both
        );
        assert_eq!(tokio_wake_events(read, Wake::None, &plain, false), read);
        assert_eq!(
            tokio_wake_events(read, Wake::WithSocket, &plain, true),
            read
        );
        let none = Transport::None;
        assert_eq!(
            tokio_wake_events(read, Wake::WithSocket, &none, false),
            read
        );
        let (c, _d) = connected_pair();
        let parked = Transport::Parked(TcpSock::from_owned(OwnedFd::from(c)));
        let idle = PollEvents::NONE;
        assert_eq!(
            tokio_wake_events(idle, Wake::WithSocket, &parked, false),
            idle
        );
    }

    #[test]
    fn job_failures_match_the_job() {
        let done = JobKind::Resolve {
            generation: 3,
            hosts: 2,
        }
        .failed();
        match done {
            JobDone::Resolved {
                generation: 3,
                results,
            } => {
                assert_eq!(results.len(), 2);
                assert!(results.iter().all(|r| r.result.is_err()));
                assert_eq!(results[1].host, 1);
            }
            _ => panic!("not a resolve result"),
        }
        assert!(matches!(
            JobKind::NewConn.failed(),
            JobDone::NewConn(Err(TlsSetupError::Resource {
                at: TlsResourceAt::ConnectionNew
            }))
        ));
    }

    #[test]
    fn readiness_maps_to_legacy_events() {
        let read = PollEvents {
            read: true,
            write: false,
        };
        let write = PollEvents {
            read: false,
            write: true,
        };
        assert_eq!(tokio_events(Ready::READABLE), read);
        assert_eq!(tokio_events(Ready::READ_CLOSED), read);
        assert_eq!(tokio_events(Ready::WRITE_CLOSED), write);
        assert_eq!(tokio_events(Ready::ERROR), tokio_error_events());
        assert_eq!(tokio_merge(read, write), tokio_error_events());
        assert_eq!(tokio_interest_of(PollEvents::NONE), Interest::READABLE);
    }

    /// Test host whose `resolve` blocks until `release` is called (or 30 seconds elapse), standing
    /// in for a resolver waiting for its timeout and counting started lookups in `entered`.
    struct StallDns {
        inner: TestHost,
        entered: std::sync::atomic::AtomicUsize,
        open: std::sync::Mutex<bool>,
        cv: std::sync::Condvar,
    }

    impl StallDns {
        fn new() -> Self {
            Self {
                inner: TestHost::new(),
                entered: std::sync::atomic::AtomicUsize::new(0),
                open: std::sync::Mutex::new(false),
                cv: std::sync::Condvar::new(),
            }
        }

        fn release(&self) {
            *self.open.lock().unwrap() = true;
            self.cv.notify_all();
        }
    }

    impl HostLeaves<TestTokens> for StallDns {
        fn discard_token(&self, token: crate::host::Discarded<TestTokens>) {
            self.inner.discard_token(token);
        }
        fn snapshot_default_watcher(&self) -> Option<zk_testkit::tokens::WatcherId> {
            self.inner.snapshot_default_watcher()
        }
        fn store_client_id(&self, id: &ClientId) {
            self.inner.store_client_id(id);
        }
        fn log_enabled(&self, level: zk_proto::LogLevel) -> bool {
            self.inner.log_enabled(level)
        }
        fn conn_permute_disabled(&self) -> bool {
            self.inner.conn_permute_disabled()
        }
        fn rng_random(&self) -> i64 {
            self.inner.rng_random()
        }
        fn rng_drand48(&self) -> f64 {
            self.inner.rng_drand48()
        }
        fn rng_reseed(&self, proof: &Unlocked) {
            self.inner.rng_reseed(proof);
        }
        fn resolve(&self, proof: &Unlocked, host: usize, spec: &HostSpec) -> HostResolution {
            self.entered.fetch_add(1, Ordering::AcqRel);
            let open = self.open.lock().unwrap();
            let (open, _) = self
                .cv
                .wait_timeout_while(open, Duration::from_secs(30), |o| !*o)
                .unwrap();
            drop(open);
            self.inner.resolve(proof, host, spec)
        }
    }

    fn three_host_connection() -> Box<Connection<TestTokens>> {
        Box::new(Connection::new(
            crate::testutil::test_config(b"zk1.example:2181,zk2.example:2181,zk3.example:2181"),
            zk_proto::Compat::NATIVE,
            zk_proto::DriverMode::NATIVE,
            Box::new(zk_testkit::rng::SeededRng::new(1)),
            Box::new(zk_testkit::hooks::LevelFilter::up_to(
                zk_proto::LogLevel::Debug,
            )),
            Box::new(zk_testkit::hooks::SharedDefaultWatcher::new(None)),
            zk_proto::Millis(0),
        ))
    }

    /// Verifies that with three configured hosts, a close or detach arriving while the foreground
    /// resolve of the first host is blocked terminates the task promptly (within 2 seconds) with
    /// `ZOK`, and that once released the abandoned job starts no lookups for the remaining hosts.
    #[test]
    fn close_and_detach_end_the_task_while_a_resolve_blocks() {
        for detach in [false, true] {
            let rt = rt();
            let dns = Arc::new(StallDns::new());
            let leaves: Arc<dyn HostLeaves<TestTokens>> = dns.clone();
            let h = spawn(
                three_host_connection(),
                Env::new(leaves),
                Arc::new(Keep::default()),
                None,
                rt.handle(),
            );
            let rc = rt.block_on(async {
                let start = Instant::now();
                while dns.entered.load(Ordering::Acquire) == 0 {
                    assert!(
                        start.elapsed() < Duration::from_secs(5),
                        "no resolve started"
                    );
                    ::tokio::time::sleep(Duration::from_millis(5)).await;
                }
                if detach {
                    h.detach();
                } else {
                    h.close();
                }
                ::tokio::time::timeout(Duration::from_secs(2), h.closed()).await
            });
            dns.release();
            assert_eq!(rc, Ok(ZOK), "detach={detach}");
            // Once released, the abandoned job observes the close flag before the next host,
            // leaving the started lookup count at one.
            let start = Instant::now();
            while start.elapsed() < Duration::from_millis(200) {
                std::thread::sleep(Duration::from_millis(10));
            }
            assert_eq!(dns.entered.load(Ordering::Acquire), 1, "detach={detach}");
        }
    }

    #[test]
    fn task_future_is_send() {
        fn is_send<F: Send>(_: &F) {}
        let h = handle();
        let fut = run(Arc::clone(&h.shared));
        is_send(&fut);
        drop(fut);
    }
}
