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

//! Multithreaded drop in driver runtime (`mt` package) for the `libzookeeper_mt` C client.
//!
//! # Architectural Overview
//!
//! In the legacy C `libzookeeper_mt` runtime (`mt_adaptor.c`), each active `zhandle_t` spawns two
//! dedicated background `pthread_t` threads—`do_io` (network I/O) and `do_completion` (callback
//! dispatch), while arbitrary application threads submit requests and may call `zookeeper_close`
//! concurrently, including from inside a watcher or completion callback running on
//! `do_completion`.
//!
//! To preserve 100% C ABI and thread-identity compatibility (including `pthread_join` vs.
//! `pthread_detach` self-close avoidance, `pthread_exit` cleanup handlers, and C-side `auth_lock`
//! ordering), the thin C wrapper retains ownership of the OS `pthread_t` handles and thread-local
//! [`Role`] tag, while delegating all state management, socket I/O, and synchronization to
//! [`Shared`]:
//!
//! - **Network I/O thread ([`Role::Io`])**: Repeatedly invokes [`Shared::io_step`] in a loop,
//!   draining any outbox items (`Item::Log`, `Item::Sasl`, `Item::AuthResult`) across
//!   [`Step::More`] barriers via [`Shared::resume`], until [`Shared::io_step`] returns
//!   [`Step::Exit`], at which point the C loop invokes [`Shared::thread_exit`].
//! - **Completion dispatch thread ([`Role::Dispatch`])**: Repeatedly invokes
//!   [`Shared::wait_dispatch`] to pop batches of [`Item::Result`], [`Item::Watch`], and
//!   [`Item::Session`] from `DispatchQueue` and invoke user C callbacks outside all driver
//!   locks, until [`Shared::wait_dispatch`] returns [`Step::Exit`]. When `zookeeper_close` was
//!   initiated reentrantly from inside a callback on the dispatch thread itself
//!   (`CloseStage::AtDispatchExit`), [`Shared::thread_exit`] runs the final shutdown tail
//!   (`CLOSE_OP` socket flush and connection destruction) as the dispatch thread exits.
//! - **Caller / Application threads ([`Role::Foreign`] / [`Role::Init`])**: Submit requests via
//!   [`Shared::submit`] (which wakes the I/O reactor through the self-pipe [`Waker`]) and initiate
//!   session termination via [`Shared::close`], passing the calling thread's [`Role`].
//!
//! # Role-Aware Shutdown State Machine (`CloseStage` and `FinalStage`)
//!
//! Because `zookeeper_close` can be called from an external application thread (`Role::Foreign`),
//! from inside a completion callback on the dispatch thread (`Role::Dispatch`), or even from the
//! I/O thread (`Role::Io`), and because outbox barriers (`Step::More`) may pause execution between
//! any two steps, shutdown is modeled as two explicit state machines:
//!
//! 1. **`CloseStage` (Multi-Thread Coordination)**: Transitions the protocol engine into closing
//!    (`conn.begin_close`), pumps initial cancellation completions into `DispatchQueue`, and
//!    emits [`Item::Thread`] instructions (`ThreadOp::Join` or `ThreadOp::Detach`) into the
//!    caller's [`Outbox`] so the C shim executes `pthread_join` or `pthread_detach` outside Rust
//!    locks.
//!    - For a **`Role::Foreign`** closer, `run_close` joins the I/O thread first, waits in
//!      `DispatchQueue::wait_idle` (`CloseStage::Settle`) for the dispatch thread to finish
//!      delivering all pre-close callbacks, runs the `FinalStage` tail (`CLOSE_OP` wire flush
//!      and `conn.destroy`), publishes the terminal close marker (`close_published`), and then
//!      joins the dispatch thread.
//!    - For a **`Role::Dispatch`** closer (closing inside a callback), waiting for the dispatch
//!      thread to go idle or joining `do_completion` would self-deadlock! Instead, `run_close`
//!      publishes the close marker, joins the I/O thread, detaches the dispatch thread, and sets
//!      `CloseStage::AtDispatchExit` so the current callback can return normally. Once the
//!      dispatch loop finishes, [`Shared::thread_exit`] executes the `FinalStage` tail and final
//!      handle release.
//! 2. **`FinalStage` (Wire Flush, Destruction and Token Reclamation)**: Executed by
//!    `Shared::run_tail` and `Shared::release` once the I/O thread is no longer touching the
//!    socket. Queues and flushes `CLOSE_OP` via `IoCore::close_flush`, logs `Freeing`, invokes
//!    `conn.destroy`, pumps any remaining outputs, and returns unconsumed tokens via
//!    [`HostLeaves`].
//!
//! # Lock Hierarchy
//!
//! Lock acquisition order is strictly enforced across the FFI boundary: C-level `auth_lock` is
//! acquired first (only around synchronous C callbacks), followed by the driver `io` lock, then
//! the protocol `core` lock, and finally the terminal `fifo` leaf lock on `DispatchQueue`. No
//! Rust mutexes remain held when returning to C code or while waiting in `wait_idle`.
//!
//! The pure-Rust native multithreaded runtime resides in [`crate::native`] and drives [`IoCore`]
//! directly on Rust-owned threads without this C-thread coordination shim.

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::Arc;

use rustix::event::{PollFd, PollFlags};
use zk_jute::JString;
use zk_jute::rc::{ZINVALIDSTATE, ZMARSHALLINGERROR, ZOK};
use zk_proto::legacy::connect::{HostParseError, HostParseErrorKind, parse_servers};
use zk_proto::{
    Action, AddAuthError, ClientId, Connection, EARLY_FRAME_MIN, FrameEncoder, HostSpec, LogArg,
    LogRecord, LogSite, Millis, MultiOpRef, Request, SecretBytes, Stage, Stopped, SubmitError,
    Submitted,
};

use crate::host::{DriverTokens, HostLeaves, discard_leftovers, log_record};
use crate::iocore::{DeferPolicy, InterestOut, IoCore, Progress};
use crate::lifecycle::Lifecycle;
use crate::net::poll::{PollEvents, clamp_timeout_ms, map_revents, request_flags};
use crate::net::sock::errno_value;
use crate::net::waker::{WakeRx, Waker};
use crate::outbox::{
    FifoBound, Flavor, Item, Outbox, Publish, Pumped, Requeued, ResumeAt, SaslItem, Step, ThreadOp,
    ThreadRole, discard_item, pump,
};
use crate::resolve::{SetServersEnd, SetServersRun, adopt_set_servers};
use crate::state::{
    Core, Env, SaslAnswer, SubmitResult, read_at_entry, read_conn, with_conn_at_entry,
};
use crate::sync::{Condvar, Mutex, Unlocked};

/// Identifies the execution context and role of an invoking thread, preserved in
/// thread-local storage by the C runtime wrapper and passed into the driver.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Role {
    /// Thread executing the initial setup sequence within `zookeeper_init`.
    Init,
    /// Dedicated background network I/O reactor thread.
    Io,
    /// Dedicated background completion and watcher dispatch thread.
    Dispatch,
    /// Arbitrary application thread invoking client APIs.
    Foreign,
}

/// Unbounded FIFO queue buffering completed results and asynchronous events
/// destined for processing by the completion dispatch thread.
pub(crate) struct Fifo<T: DriverTokens> {
    /// Queue of published completion items awaiting dispatch, oldest first.
    pub(crate) items: VecDeque<Item<T>>,
    /// Flag indicating whether the I/O thread has terminated its reactor loop or detached during
    /// shutdown.
    pub(crate) io_exited: bool,
    /// Set once the shutdown drain reaches the close marker, confirming that all terminal events
    /// are queued or taken.
    pub(crate) close_published: bool,
    /// Indicates whether the dispatch thread is currently executing application callbacks for a
    /// batch.
    pub(crate) busy: bool,
    /// Set when an application thread initiating shutdown is waiting for the dispatch thread to
    /// settle into an idle state.
    pub(crate) settling: bool,
    /// Flag indicating that a completion callback is continuing a previously requeued shutdown,
    /// avoiding self-deadlock.
    pub(crate) closer_is_dispatch: bool,
    /// Set when an application callback terminates the dispatch thread abnormally.
    pub(crate) dispatch_ended: bool,
}

/// Thread safe event queue coordinating event publishing, thread notifications, and shutdown
/// quiescence between the I/O reactor, worker threads, and dispatch.
pub(crate) struct DispatchQueue<T: DriverTokens> {
    /// Internal FIFO storage guarded by a leaf mutex that must not be held when acquiring other
    /// locks.
    pub(crate) fifo: Mutex<Fifo<T>>,
    /// Condition variable signaling newly published items, I/O loop exit, or close marker
    /// publication.
    pub(crate) cv: Condvar,
    /// Condition variable notifying an external closer that the dispatch thread has finished
    /// processing its active batch.
    pub(crate) idle: Condvar,
}

impl<T: DriverTokens> Publish<T> for DispatchQueue<T> {
    fn publish(&self, item: Item<T>) {
        self.fifo.lock_owner().items.push_back(item);
        self.cv.notify_one();
    }

    fn close_published(&self) {
        self.fifo.lock_owner().close_published = true;
        self.cv.notify_all();
    }
}

impl<T: DriverTokens> DispatchQueue<T> {
    /// Wakes all waiting threads following a state change outside the FIFO mutex,
    /// acquiring and releasing the lock briefly to avoid lost wakeups.
    fn wake_all(&self) {
        drop(self.fifo.lock_owner());
        self.cv.notify_all();
    }

    /// Blocks until the FIFO queue is completely empty and the dispatch thread
    /// has finished executing its current batch of completion callbacks.
    ///
    /// Called during application-initiated shutdown after joining the I/O thread,
    /// guaranteeing that all accumulated completion events are fully processed
    /// before teardown proceeds, matching the join semantics of C `libzookeeper_mt`.
    /// If the dispatch thread terminates prematurely via `pthread_exit`, this wait
    /// aborts and any remaining items are subsequently discarded.
    fn wait_idle(&self) {
        let mut fifo = self.fifo.lock_owner();
        if fifo.closer_is_dispatch {
            return;
        }
        fifo.settling = true;
        while !fifo.dispatch_ended && (fifo.busy || !fifo.items.is_empty()) {
            fifo = self.idle.wait(fifo);
        }
        fifo.settling = false;
    }
}

/// Protected state and resources owned exclusively by the network I/O reactor thread.
pub(crate) struct IoSide {
    /// Core transport engine and connection state machine.
    pub(crate) io: IoCore,
    /// Receiving end of the self-pipe waker channel for interruptible reactor polling.
    pub(crate) wake_rx: WakeRx,
    /// Progress marker tracking execution stages of the final session teardown.
    pub(crate) final_stage: FinalStage,
    /// Current reactor phase indicating where a suspended I/O iteration must resume.
    phase: IoPhase,
    /// Cached descriptor interest flags and polling timeout from the current iteration.
    out: InterestOut,
    /// Flag indicating that an unrecoverable transport or protocol error requires the reactor
    /// loop to exit.
    exit_next: bool,
    /// Return code captured during the shutdown socket flush to report upon final release.
    final_rc: i32,
    /// Suspended execution stage of the connection destroy pump across asynchronous barriers.
    final_stopped: Option<Stopped>,
}

/// Distinct execution phases comprising an iteration of the network I/O reactor loop.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum IoPhase {
    /// Reactor is idle between polling iterations.
    Idle,
    /// Registering file descriptor interests and computing timeout deadlines.
    Interest,
    /// Interest computation completed; entering descriptor polling wait.
    Poll,
    /// Processing socket readability and writability events after polling.
    Process,
}

/// Execution stages governing the shutdown tail sequence and final handle release.
///
/// Progresses sequentially from `NotStarted` to `Release`, executing either on
/// the thread initiating closure or upon completion thread exit.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FinalStage {
    /// Teardown sequence has not yet begun.
    NotStarted,
    /// Emitted the closing log message; awaiting socket flush completion.
    Flush,
    /// Socket flush concluded or omitted; transitioning to connection destruction.
    Free,
    /// Destruction initiated; draining pending events across suspension barriers.
    Destroy,
    /// Connection destroyed and events flushed; ready for transport cleanup and final release.
    Release,
    /// Terminal release completed; all resources finalized.
    Released,
}

/// Progression stages of session closure prior to final handle deallocation.
///
/// Maintains an independent suspension state because reactor I/O steps may
/// simultaneously preserve a separate suspended phase within `Core::stopped`.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum CloseStage {
    /// Handle is active; no close sequence is underway.
    Idle,
    /// Initial close began; event draining continues across execution barriers.
    Pump {
        /// Execution context and role of the thread driving the close sequence.
        role: Role,
        /// Suspended state captured at the most recent barrier.
        stopped: Option<Stopped>,
    },
    /// Initial event draining completed; proceeding with background thread termination.
    Threads {
        /// Execution context and role of the thread driving the close sequence.
        role: Role,
        /// Role of the next thread targeted for join or detachment.
        next: ThreadRole,
    },
    /// I/O thread has terminated; waiting for the dispatch thread to drain remaining callbacks.
    Settle {
        /// Execution context and role of the thread driving the close sequence.
        role: Role,
    },
    /// I/O thread has terminated; executing the shutdown tail on the current thread.
    Tail {
        /// Execution context and role of the thread driving the close sequence.
        role: Role,
    },
    /// Thread coordination operations emitted; proceeding to handle epilog.
    Epilog {
        /// Indicates whether the dispatch thread should execute the shutdown tail on loop exit.
        tail_at_exit: bool,
    },
    /// Closure initiated from an asynchronous completion callback; dispatch thread will run the
    /// tail on exit.
    AtDispatchExit,
}

/// Maximum count of discrete teardown actions processed during final handle cleanup
/// following connection destruction (typically limited to transport teardown and SASL disposal).
const MAX_FINAL_ACTIONS: usize = 16;

/// Primary runtime handle coordinating concurrent state, reactor loops, and event
/// dispatch for a multithreaded ZooKeeper client session (`libzookeeper_mt`).
pub struct Shared<T: DriverTokens> {
    /// Core connection state machine, outbox buffers, and server configurations.
    pub(crate) core: Mutex<Core<T>>,
    /// Reactor execution state, polling descriptors, and socket buffers owned by the I/O thread.
    pub(crate) io: Mutex<IoSide>,
    /// Thread-safe event queue buffering completion items and watcher notifications for dispatch.
    pub(crate) queue: Arc<DispatchQueue<T>>,
    /// Self-pipe waker channel used to interrupt reactor polling across thread boundaries.
    pub(crate) waker: Waker,
    /// Reference counting and shutdown coordination lifecycle state machine.
    pub(crate) life: Lifecycle,
    /// Current progression stage tracking multi-step teardown barriers during session close.
    pub(crate) close_stage: Mutex<CloseStage>,
    /// Runtime environment providing monotonic clock readings, host logging, and DNS resolution.
    pub(crate) env: Env<T>,
    /// Frame encoder for large request bodies used by submitting threads before acquiring the
    /// core lock, or `None` when the core holds no connection; boxed to keep the field one
    /// pointer wide inside the handle.
    frames: Option<Box<FrameEncoder>>,
}

impl<T: DriverTokens> Shared<T> {
    /// Constructs a multithreaded driver runtime instance wrapping an initialized connection
    /// core.
    ///
    /// The self-pipe waker is deliberately created without `FD_CLOEXEC` to mirror upstream
    /// `adaptor_init` in `mt_adaptor.c`, where pipes are created via standard `pipe()` and the
    /// `sock_cloexec` configuration option restricts cloexec behavior exclusively to network
    /// sockets.
    ///
    /// # Errors
    ///
    /// Returns the system errno if pipe allocation fails, emitting a `LogSite::PipeFailed`
    /// record into `ob` so the C shim can log the diagnostic before returning NULL.
    pub(crate) fn new(core: Core<T>, env: Env<T>, ob: &mut Outbox<T>) -> Result<Arc<Self>, i32> {
        let (waker, wake_rx) = match Waker::new(false) {
            Ok(pair) => pair,
            Err(e) => {
                let errno = errno_value(e);
                let args = vec![LogArg::I64(i64::from(errno))];
                push_log(&*env.host, ob, LogSite::PipeFailed, args);
                return Err(errno);
            }
        };
        let side = IoSide {
            io: IoCore::new(),
            wake_rx,
            final_stage: FinalStage::NotStarted,
            phase: IoPhase::Idle,
            out: InterestOut::UNWRITTEN,
            exit_next: false,
            final_rc: ZOK,
            final_stopped: None,
        };
        let queue = DispatchQueue {
            fifo: Mutex::new(Fifo {
                items: VecDeque::new(),
                io_exited: false,
                close_published: false,
                busy: false,
                settling: false,
                closer_is_dispatch: false,
                dispatch_ended: false,
            }),
            cv: Condvar::new(),
            idle: Condvar::new(),
        };
        let frames = core
            .conn
            .as_deref()
            .map(|c| Box::new(Connection::frame_encoder(c)));
        Ok(Arc::new(Self {
            frames,
            core: Mutex::new(core),
            io: Mutex::new(side),
            queue: Arc::new(queue),
            waker,
            life: Lifecycle::new(),
            close_stage: Mutex::new(CloseStage::Idle),
            env,
        }))
    }

    /// Constructs a multithreaded driver instance from a completed initialization state machine.
    ///
    /// # Errors
    ///
    /// Returns the system errno if waker pipe creation fails.
    pub fn from_init(init: crate::init::Init<T>, ob: &mut Outbox<T>) -> Result<Arc<Self>, i32> {
        let (core, env) = init.into_parts();
        Self::new(core, env, ob)
    }

    /// Creates an event outbox configured to publish asynchronous results directly into the
    /// dispatch FIFO.
    #[must_use]
    pub fn outbox(&self) -> Outbox<T> {
        let queue: Arc<dyn Publish<T>> = self.queue.clone();
        Outbox::with_publish(queue)
    }

    /// Registers the entrance of an active library or application thread into the driver
    /// runtime.
    pub fn thread_enter(&self, _role: Role) {
        self.life.prolog();
    }

    /// Coordinates thread exit and decrements the runtime lifecycle reference count.
    ///
    /// For the network I/O thread, marks `io_exited` and wakes the dispatch thread.
    /// For the dispatch thread, if session termination was initiated from within a completion
    /// callback, executes the shutdown tail and dispatches its terminal results before exiting.
    /// The final exiting thread runs the handle teardown epilog.
    pub fn thread_exit(&self, role: Role, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        if role == Role::Io {
            self.queue.fifo.lock_owner().io_exited = true;
            self.queue.cv.notify_all();
        }
        if role == Role::Dispatch {
            let mut stage = self.close_stage.lock_owner();
            if *stage == CloseStage::AtDispatchExit {
                *stage = CloseStage::Tail {
                    role: Role::Dispatch,
                };
                drop(stage);
                return self.run_close(ob, rc);
            }
        }
        self.leave(ob, rc)
    }

    /// Invoked by thread cleanup handlers when an application callback terminates the dispatch
    /// thread.
    ///
    /// Any unconsumed items in `ob` are reclaimed without invoking callbacks, the dispatch thread
    /// is marked as terminated, and any external close thread waiting for callback quiescence is
    /// unblocked.
    pub fn dispatch_ended(&self, ob: &mut Outbox<T>) {
        ob.finish(&*self.env.host);
        let mut fifo = self.queue.fifo.lock_owner();
        fifo.dispatch_ended = true;
        fifo.busy = false;
        drop(fifo);
        self.queue.idle.notify_all();
    }

    /// Drives a single iteration of the nonblocking network I/O reactor loop.
    ///
    /// Evaluates descriptor interests, yields if accumulated log lines require draining,
    /// performs interruptible reactor polling across the socket and waker pipe, dispatches
    /// ready I/O events, and terminates the loop if an unrecoverable protocol error occurs.
    pub fn io_step(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let mut guard = self.io.lock_owner();
        if self.life.close_requested() || guard.exit_next {
            return Step::Exit;
        }
        guard.phase = IoPhase::Interest;
        guard.out = InterestOut::UNWRITTEN;
        self.run_io(&mut guard, false, ob, rc)
    }

    /// Resumes execution of a suspended multi-step driver operation after outbox draining.
    pub fn resume(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        match ob.take_resume() {
            Some(ResumeAt::IoStep) => {
                let mut guard = self.io.lock_owner();
                self.run_io(&mut guard, true, ob, rc)
            }
            Some(ResumeAt::Close) => self.run_close(ob, rc),
            Some(ResumeAt::FinalClose) => self.run_final(ob, rc),
            Some(ResumeAt::SetServers(id)) => {
                let cx = self.env.resolve_ctx(&self.core, Self::FLAVOR);
                let mut proof = Unlocked::new_at_entry();
                let end = SetServersRun::resume(&cx, &mut proof, ob, id);
                self.set_servers_end(end, ob, rc)
            }
            // Foreign resume tags correspond to different driver runtimes; complete immediately
            // with ZOK.
            _ => {
                *rc = ZOK;
                Step::Done(ZOK)
            }
        }
    }

    /// Awaits completion events or session shutdown on the dedicated dispatch thread.
    ///
    /// Blocks until events are published to the FIFO or both the I/O loop has exited and
    /// the terminal close marker has been reached. When items are present, moves the entire
    /// batch into `ob` for delivery and notifies any external close thread waiting for
    /// quiescence.
    pub fn wait_dispatch(&self, ob: &mut Outbox<T>) -> Step {
        let mut fifo = self.queue.fifo.lock_owner();
        // Acknowledge that the calling C harness has finished delivering the preceding batch.
        fifo.busy = false;
        if fifo.settling {
            self.queue.idle.notify_all();
        }
        while fifo.items.is_empty() && !(fifo.close_published && fifo.io_exited) {
            fifo = self.queue.cv.wait(fifo);
        }
        if fifo.items.is_empty() {
            return Step::Exit;
        }
        let batch = std::mem::take(&mut fifo.items);
        fifo.busy = true;
        drop(fifo);
        ob.extend_items(batch);
        Step::Done(ZOK)
    }

    /// Coordinates session termination across calling roles.
    ///
    /// Manages the shutdown sequence across forked processes, concurrent close requests,
    /// and calling thread identities:
    /// - **Forked Child**: Releases local resources immediately without network traffic.
    /// - **Foreign Thread**: Directs the I/O thread to terminate, waits for dispatch
    ///   thread quiescence, executes the closing tail, and coordinates thread joining.
    /// - **Dispatch Thread**: Initiates closure, joins the I/O thread, and arranges for
    ///   the shutdown tail to execute when exiting its dispatch loop.
    /// - **I/O Thread**: Detaches itself and coordinates joining the dispatch thread.
    /// - **Single Reference**: Bypasses thread handshakes and executes final release immediately.
    pub fn close(&self, role: Role, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        if self.life.is_forked_child() {
            self.forked_close();
            *rc = ZOK;
            return Step::Done(ZOK);
        }
        // Acquire reference count during the close prolog before making the close request public.
        // This ensures that concurrent API calls exiting later cannot cause an inner close to
        // appear final.
        let refs = self.life.prolog();
        if !self.life.request_close() {
            if let Some(step) = self.adopt_close(role, ob, rc) {
                return step;
            }
            return self.leave(ob, rc);
        }
        if refs <= 1 {
            return self.final_close(ob, rc);
        }
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        if let Some(conn) = self.core.lock(&mut proof).conn.as_deref_mut() {
            conn.begin_close(now);
        }
        *self.close_stage.lock_owner() = CloseStage::Pump {
            role,
            stopped: None,
        };
        self.run_close(ob, rc)
    }

    /// Submits an asynchronous ZooKeeper request and interrupts the reactor waker to prompt
    /// transmission.
    ///
    /// # Errors
    ///
    /// Returns a submission error with the original token if enqueuing fails or if the session is
    /// closing.
    pub fn submit(&self, req: Request<T>) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_with_data(req, None)
    }

    /// Submits a request with borrowed payload data, signaling the reactor waker upon
    /// acceptance.
    ///
    /// The payload of a large `create` or `setData` request (at least [`EARLY_FRAME_MIN`] bytes) is
    /// encoded into its frame before acquiring the core lock when the frame encoder accepts the
    /// request, so the lock holder does not copy the payload and the I/O thread waits less for the
    /// lock. Request validation, `xid` assignment, and queue ordering remain under the lock.
    ///
    /// # Errors
    ///
    /// Returns a submission error with the original token if enqueuing fails or if the session is
    /// closing.
    pub fn submit_with_data(
        &self,
        req: Request<T>,
        data: Option<&[u8]>,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        // Payloads below EARLY_FRAME_MIN proceed directly to the locked path, so small requests
        // perform no extra work.
        if let (Some(frames), Some(d)) = (self.frames.as_deref(), data) {
            if d.len() >= EARLY_FRAME_MIN {
                return match frames.prepare(req, data) {
                    Ok(early) => self.submit_by(early, |c, e, now| c.submit_early(e, now)),
                    Err(req) => self.submit_by(req, |c, r, now| c.submit_with_data(r, data, now)),
                };
            }
        }
        self.submit_by(req, |c, r, now| c.submit_with_data(r, data, now))
    }

    /// Submits a transactional multi-operation request with borrowed sub-operations, signaling the
    /// reactor.
    ///
    /// # Errors
    ///
    /// Returns a submission error with the original token if enqueuing fails or if the session is
    /// closing.
    pub fn submit_multi_ref(
        &self,
        req: Request<T>,
        ops: &[MultiOpRef<'_>],
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_by(req, |c, r, now| c.submit_multi_ref(r, ops, now))
    }

    /// Internal helper enqueuing a request under the core lock and waking the reactor loop upon
    /// success.
    fn submit_by<R: Into<Request<T>>>(
        &self,
        req: R,
        send: impl FnOnce(&mut Connection<T>, R, Millis) -> SubmitResult<T>,
    ) -> SubmitResult<T> {
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        let result = {
            let mut core = self.core.lock(&mut proof);
            match core.conn.as_deref_mut() {
                // Accept new operations until session closure is formally initiated.
                Some(conn) if !self.life.close_requested() || conn.is_closing() => {
                    send(conn, req, now)
                }
                _ => Err(SubmitError {
                    stage: Stage::Enqueue,
                    code: ZMARSHALLINGERROR,
                    token: req.into().token,
                }),
            }
        };
        if result.is_ok() {
            self.waker.wake();
        }
        result
    }

    /// Enqueues client authentication credentials and wakes the reactor thread to initiate
    /// exchange.
    ///
    /// # Errors
    ///
    /// Returns an error containing the auth completion token if the client state is invalid.
    pub fn add_auth(
        &self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<T::Auth>,
    ) -> Result<(), AddAuthError<T>> {
        let now = self.env.clock.now();
        let result = self.core.lock(&mut Unlocked::new_at_entry()).add_auth_or(
            scheme,
            cred,
            done,
            now,
            ZINVALIDSTATE,
        );
        if result.is_ok() {
            self.waker.wake();
        }
        result
    }

    /// Parses and applies a new ensemble connection string, resolving endpoints and rebalancing.
    ///
    /// Executes sequentially on the calling thread, yielding `Step::More` across DNS resolution
    /// barriers so log entries can be drained before blocking I/O calls.
    pub fn set_servers(&self, hosts: &[u8], ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let host = &*self.env.host;
        let specs = match parse_servers(hosts) {
            Ok(specs) => specs.into_iter().map(HostSpec::Legacy).collect::<Vec<_>>(),
            Err(e) => {
                // Parsing failure leaves the active ensemble configuration unmodified.
                if let Some(rec) = parse_error_record(host, &e) {
                    ob.push(Item::Log(rec));
                }
                *rc = e.rc;
                return Step::Done(e.rc);
            }
        };
        let cx = self.env.resolve_ctx(&self.core, Self::FLAVOR);
        let mut proof = Unlocked::new_at_entry();
        let end = SetServersRun::start(&cx, &mut proof, ob, specs);
        self.set_servers_end(end, ob, rc)
    }

    /// Finalizes or yields an ensemble resolution segment, waking the reactor thread when
    /// complete.
    fn set_servers_end(&self, end: SetServersEnd, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        match end {
            SetServersEnd::More(id) => {
                ob.set_resume(ResumeAt::SetServers(id));
                Step::More
            }
            SetServersEnd::Done { rc: code, .. } => {
                if code != ZINVALIDSTATE {
                    self.waker.wake();
                }
                *rc = code;
                Step::Done(code)
            }
        }
    }

    /// Records an asynchronous SASL negotiation result provided by host C callbacks.
    pub fn sasl_result(&self, answer: SaslAnswer) {
        let mut proof = Unlocked::new_at_entry();
        self.core.lock(&mut proof).sasl = Some(answer);
    }

    /// Restores unconsumed completion items following an interrupted callback delivery.
    ///
    /// Preserves earlier items at the head of the queue to maintain strict FIFO delivery
    /// ordering, and awakens the I/O thread if an orphaned server list re-resolution was
    /// requeued.
    pub fn requeue(&self, rest: Requeued<T>) {
        let mut proof = Unlocked::new_at_entry();
        let mut core = self.core.lock(&mut proof);
        let mut wake = false;
        match rest.resume_at() {
            Some(at @ (ResumeAt::Close | ResumeAt::FinalClose)) => core.orphan_close = Some(at),
            Some(ResumeAt::SetServers(id)) => {
                adopt_set_servers(&mut core, id);
                wake = true;
            }
            _ => {}
        }
        if wake {
            self.waker.wake();
        }
        let merged = match core.requeued.take() {
            None => rest,
            Some(old) => {
                // Ensure previously queued items retain precedence ahead of newly requeued items.
                let mut ob = Outbox::new();
                ob.restore(rest);
                ob.restore(old);
                ob.requeue()
            }
        };
        core.requeued = Some(merged);
    }

    /// Retrieves the remote socket address of the active server connection without blocking.
    #[must_use]
    pub fn peer_addr(&self) -> Option<SocketAddr> {
        // Leverages the cached peer address on the core struct to avoid contending with the I/O
        // thread during polling.
        let mut proof = Unlocked::new_at_entry();
        self.core.lock(&mut proof).peer
    }

    /// Legacy C compatibility export for `zookeeper_interest`, returning simulated idle state.
    #[must_use]
    pub fn interest_exported(&self) -> InterestOut {
        let mut proof = Unlocked::new_at_entry();
        let facts = read_conn(&self.core, &mut proof, |c| {
            (c.state().code(), c.recv_timeout())
        });
        match facts {
            Some((state, recv_timeout)) if state >= 0 => InterestOut {
                rc: ZOK,
                written: true,
                fd: -1,
                bits: 0,
                timeout_ms: i64::from(recv_timeout.checked_div(3).unwrap_or(0)),
            },
            _ => InterestOut {
                rc: ZINVALIDSTATE,
                ..InterestOut::UNWRITTEN
            },
        }
    }

    /// Legacy C compatibility export for `zookeeper_process`, returning `ZINVALIDSTATE` under
    /// multithreaded mode.
    #[must_use]
    pub fn process_exported(&self) -> i32 {
        ZINVALIDSTATE
    }

    /// Queries the current numerical connection state code.
    #[must_use]
    pub fn state(&self) -> i32 {
        read_at_entry(&self.core, |c| c.state().code()).unwrap_or(0)
    }

    /// Queries the negotiated session receive timeout in milliseconds.
    #[must_use]
    pub fn recv_timeout(&self) -> i32 {
        read_at_entry(&self.core, Connection::recv_timeout).unwrap_or(0)
    }

    /// Retrieves the established client session identifier and credentials.
    #[must_use]
    pub fn client_id(&self) -> ClientId {
        read_at_entry(&self.core, Connection::client_id).unwrap_or_default()
    }

    /// Executes a read-only closure against the underlying connection under the core lock.
    ///
    /// Intended for FFI accessors inspecting connection state without modifying internal buffers.
    pub fn read_conn<R>(&self, f: impl FnOnce(&Connection<T>) -> R) -> Option<R> {
        read_at_entry(&self.core, f)
    }

    /// Executes a mutating closure against the connection under the core lock and wakes the
    /// reactor.
    ///
    /// Used by configuration APIs such as server resolution delays and server cycling.
    pub fn with_conn<R>(
        &self,
        f: impl FnOnce(&mut Connection<T>, zk_proto::Millis) -> R,
    ) -> Option<R> {
        let r = with_conn_at_entry(&self.core, self.env.clock.now(), f);
        self.waker.wake();
        r
    }

    /// Deferral policy for the drop-in driver: executes blocking operations synchronously
    /// inline.
    pub(crate) const DEFER: DeferPolicy = DeferPolicy::NONE;

    /// Driver pump flavor identifying multithreaded execution semantics.
    pub(crate) const FLAVOR: Flavor = Flavor::Mt;

    /// Resume tag identifying the network I/O iteration phase.
    pub(crate) const IO_RESUME: ResumeAt = ResumeAt::IoStep;
}

// Network I/O reactor execution.
impl<T: DriverTokens> Shared<T> {
    /// Executes the nonblocking network I/O reactor loop through its successive [`IoPhase`]
    /// stages (`Interest -> Poll -> Process`) until it either yields at an outbox barrier
    /// ([`Step::More`]) or finishes a complete reactor turn ([`Step::Done`]).
    ///
    /// - **`IoPhase::Interest`**: Runs [`IoCore::interest_step`] (or [`IoCore::resume`] when
    ///   re-entering after a barrier) under [`DeferPolicy::NONE`]. Once interest computation
    ///   completes, transitions to `IoPhase::Poll`. Importantly, if `ob` contains any pending
    ///   items (such as diagnostic `Item::Log` records produced during DNS lookup or socket
    ///   connection), `run_io` yields [`Step::More`] *before* entering `poll_once` so the C
    ///   wrapper flushes those log messages immediately rather than holding them across a blocking
    ///   OS `poll()` wait.
    /// - **`IoPhase::Poll`**: Calls [`Shared::poll_once`] to wait on both the self-pipe `wake_rx`
    ///   and the active socket descriptor (if any), then transitions to `IoPhase::Process` and
    ///   invokes [`IoCore::process_step`] with the observed socket readiness flags.
    /// - **`IoPhase::Process`**: Re-entered via [`IoCore::resume`] whenever `process_step` yields
    ///   [`Progress::More`] at an outbox barrier (such as a synchronous `Item::AuthResult` or
    ///   `Item::Sasl` step).
    fn run_io(&self, side: &mut IoSide, resumed: bool, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let IoSide {
            io,
            wake_rx,
            phase,
            out,
            exit_next,
            ..
        } = side;
        let cx = self.env.step_ctx(
            &self.core,
            Self::FLAVOR,
            Self::DEFER,
            Some((&self.waker, &*wake_rx)),
            Some(self.life.close_flag()),
            None,
        );
        let mut proof = Unlocked::new_at_entry();
        let mut resumed = resumed;
        loop {
            match *phase {
                IoPhase::Idle => {
                    *rc = ZOK;
                    return Step::Done(ZOK);
                }
                IoPhase::Interest => {
                    let progress = if resumed {
                        io.resume(&cx, &mut proof, ob, out)
                    } else {
                        io.interest_step(&cx, &mut proof, ob, out)
                    };
                    resumed = false;
                    if progress == Progress::More {
                        ob.set_resume(Self::IO_RESUME);
                        return Step::More;
                    }
                    *phase = IoPhase::Poll;
                    // Flush accumulated log messages to host callbacks before blocking on
                    // reactor polling.
                    if !ob.is_empty() {
                        ob.set_resume(Self::IO_RESUME);
                        return Step::More;
                    }
                }
                IoPhase::Poll => {
                    let events = self.poll_once(io, wake_rx, out, &mut proof);
                    *phase = IoPhase::Process;
                    let progress = io.process_step(&cx, &mut proof, events, ob);
                    if progress == Progress::More {
                        ob.set_resume(Self::IO_RESUME);
                        return Step::More;
                    }
                    return self.end_io(phase, exit_next, progress, &mut proof, rc);
                }
                IoPhase::Process => {
                    let mut unused = InterestOut::UNWRITTEN;
                    let progress = io.resume(&cx, &mut proof, ob, &mut unused);
                    if progress == Progress::More {
                        ob.set_resume(Self::IO_RESUME);
                        return Step::More;
                    }
                    return self.end_io(phase, exit_next, progress, &mut proof, rc);
                }
            }
        }
    }

    /// Performs an interruptible OS `poll()` wait across the self-pipe `wake_rx` descriptor and
    /// the active network socket (if `out.written && out.fd >= 0`).
    ///
    /// Timeout selection:
    /// - If `io.buffered_ready(suspended, true)` reports that the rustls buffer already holds
    ///   decrypted plaintext waiting to be framed, clamps `timeout_ms` to `0` so the reactor
    ///   processes those bytes immediately without waiting for additional TCP segments.
    /// - Otherwise uses `out.timeout_ms` from [`IoCore::interest_step`], falling back to
    ///   `max(recv_timeout / 60, 1)` when `interest_step` exited early without writing `out`.
    ///
    /// When `wake_rx` reports `PollFlags::IN`, drains the self-pipe via
    /// `wake_rx.reset(&self.waker)` before returning the socket's [`PollEvents`]. Transient
    /// `poll()` errors (such as `EINTR`) return [`PollEvents::NONE`] so the next reactor iteration
    /// re-evaluates cleanly.
    ///
    /// A wake also adds `WRITE` to the returned events when the poll included an open transport
    /// and no close is requested. A wake usually signals a new request, so the process step sends
    /// it in the same reactor iteration instead of after a second interest call and poll.
    fn poll_once(
        &self,
        io: &IoCore,
        wake_rx: &WakeRx,
        out: &InterestOut,
        proof: &mut Unlocked,
    ) -> PollEvents {
        let (recv_timeout, suspended) =
            read_conn(&self.core, proof, |c| (c.recv_timeout(), c.is_suspended()))
                .unwrap_or((0, false));
        let timeout_ms = if io.buffered_ready(suspended, true) {
            0
        } else if out.written {
            out.timeout_ms
        } else {
            // Default to a bounded waker wait when connection interest computation has not
            // executed.
            i64::from(recv_timeout.checked_div(60).unwrap_or(0).max(1))
        };
        let sock = if out.written && out.fd >= 0 {
            io.conn.sock()
        } else {
            None
        };
        // Construct the poll descriptor array on the stack to prevent heap allocations during
        // polling.
        let timeout = clamp_timeout_ms(timeout_ms);
        let polled = match sock {
            Some(sock) => {
                let want = request_flags(PollEvents::from_bits(out.bits));
                let mut fds = [PollFd::new(wake_rx, PollFlags::IN), PollFd::new(sock, want)];
                let ok = self.env.poll.poll(&mut fds, timeout).is_ok();
                let [w, s] = &fds;
                ok.then(|| {
                    (
                        w.revents().contains(PollFlags::IN),
                        map_revents(s.revents()),
                    )
                })
            }
            None => {
                let mut fds = [PollFd::new(wake_rx, PollFlags::IN)];
                let ok = self.env.poll.poll(&mut fds, timeout).is_ok();
                let [w] = &fds;
                ok.then(|| (w.revents().contains(PollFlags::IN), PollEvents::NONE))
            }
        };
        // Transient errors like EINTR produce no events. The reactor loop will poll again on the
        // next pass.
        let Some((woken, mut events)) = polled else {
            return PollEvents::NONE;
        };
        if woken {
            wake_rx.reset(&self.waker);
            // Synthesize write readiness on wakeup so newly queued outbound frames flush within
            // the current reactor iteration; any partial write remainder re-registers WRITE
            // interest on the subsequent pass without busy-looping.
            if sock.is_some() && io.conn.data_phase() && !self.life.close_requested() {
                events.write = true;
            }
        }
        events
    }

    /// Concludes an I/O iteration, transitioning the phase to idle and flagging unrecoverable
    /// connection errors.
    ///
    /// If the connection has entered a terminal error state, flags `exit_next` so that the reactor
    /// loop terminates on its next iteration.
    fn end_io(
        &self,
        phase: &mut IoPhase,
        exit_next: &mut bool,
        progress: Progress,
        proof: &mut Unlocked,
        rc: &mut i32,
    ) -> Step {
        *phase = IoPhase::Idle;
        let r = match progress {
            Progress::Done(r) => r,
            Progress::More | Progress::Wait => ZOK,
        };
        if read_conn(&self.core, proof, |c| c.state().code() < 0).unwrap_or(true) {
            *exit_next = true;
        }
        *rc = r;
        Step::Done(r)
    }
}

/// Session closure workflows by caller role and final handle deallocation.
impl<T: DriverTokens> Shared<T> {
    /// Decrements the caller's lifecycle reference count, running final handle teardown if this
    /// was the last reference.
    fn leave(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        if self.life.epilog() {
            return self.final_close(ob, rc);
        }
        *rc = ZOK;
        Step::Done(ZOK)
    }

    /// Continues an orphaned close stage whose resume token was lost when an application callback
    /// threw or unwound.
    ///
    /// Releases the current caller's reference first, drains any requeued outbox items, and
    /// continues the closure stage.
    fn adopt_close(&self, role: Role, ob: &mut Outbox<T>, rc: &mut i32) -> Option<Step> {
        let at = {
            let mut proof = Unlocked::new_at_entry();
            let mut core = self.core.lock(&mut proof);
            let at = core.orphan_close.take()?;
            let _ = core.restore_requeued(ob);
            at
        };
        // Prevent self-deadlock by bypassing the idle queue wait when running in dispatch
        // thread context.
        self.queue.fifo.lock_owner().closer_is_dispatch = role == Role::Dispatch;
        let _ = self.life.epilog();
        Some(match at {
            ResumeAt::FinalClose => self.run_final(ob, rc),
            _ => self.run_close(ob, rc),
        })
    }

    /// Performs immediate nonblocking cleanup in a forked child process, tearing down socket
    /// descriptors without network traffic.
    fn forked_close(&self) {
        if let (Some(_core), Some(mut side)) = (self.core.try_lock(), self.io.try_lock()) {
            side.io.forked_close();
        }
        let _ = self.waker.close_in_child();
    }

    /// Advances the multithreaded session closure state machine ([`CloseStage`]) across outbox
    /// barriers when background threads are active (`refs > 1`).
    ///
    /// Stage progression:
    /// - **`CloseStage::Pump`**: Drains the initial closing outputs generated by
    ///   `conn.begin_close(now)`. In-flight auth completions stay in `ob` for synchronous inline
    ///   delivery on the closing thread, while asynchronous request cancellations (`ZCLOSING`) are
    ///   published into [`DispatchQueue`]. For non-`Foreign` roles (e.g., `Role::Dispatch`), also
    ///   publishes the close marker (`ob.push_close_published()`) before joining the I/O thread.
    /// - **`CloseStage::Threads { next: ThreadRole::Io }`**: Emits [`Item::Thread`] for the I/O
    ///   thread (`Join` after waking `self.waker` when called from outside `Role::Io`, or `Detach`
    ///   when called from `Role::Io`) and yields [`Step::More`] so the C shim executes
    ///   `pthread_join` / `pthread_detach` outside Rust locks.
    /// - **`CloseStage::Settle`** (`Role::Foreign` only): After the I/O thread has been joined,
    ///   waits in [`DispatchQueue::wait_idle`] (with no driver locks held) for the dispatch thread
    ///   to finish delivering all pre-close callbacks before proceeding to `CloseStage::Tail`.
    /// - **`CloseStage::Tail`**: Runs [`Shared::run_tail`] (`CLOSE_OP` wire flush and
    ///   `conn.destroy`). When executed on `Role::Dispatch` (during `thread_exit`), first moves any
    ///   remaining items from `DispatchQueue` into `ob` with `ob.deliver_here()` so the exiting
    ///   dispatch thread delivers them inline. When executed on `Role::Foreign`, publishes the
    ///   close marker after `run_tail` finishes so the dispatch thread drains any `destroy()`
    ///   outputs before exiting.
    /// - **`CloseStage::Threads { next: ThreadRole::Dispatch }`**: Emits [`Item::Thread`] for the
    ///   dispatch thread (`Detach` if `role == Role::Dispatch`, otherwise `Join` after waking the
    ///   dispatch queue condvar).
    /// - **`CloseStage::Epilog`**: If `tail_at_exit` is set (`role == Role::Dispatch`), installs
    ///   [`CloseStage::AtDispatchExit`] and releases the `close` call's own reference so the
    ///   enclosing callback can return cleanly; [`Shared::thread_exit`] will transition
    ///   `AtDispatchExit -> CloseStage::Tail` when the dispatch loop terminates. Otherwise reclaims
    ///   any leaked reference from an abnormal `pthread_exit` (`dispatch_ended`) and decrements the
    ///   closer's lifecycle reference via `leave`.
    fn run_close(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let host = &*self.env.host;
        let mut stage = std::mem::replace(&mut *self.close_stage.lock_owner(), CloseStage::Idle);
        loop {
            match stage {
                CloseStage::Idle => {
                    *rc = ZOK;
                    return Step::Done(ZOK);
                }
                CloseStage::Pump { role, stopped } => {
                    let now = self.env.clock.now();
                    let mut proof = Unlocked::new_at_entry();
                    let mut core = self.core.lock(&mut proof);
                    let pumped = match core.conn.as_deref_mut() {
                        Some(conn) => {
                            if let Some(s) = stopped {
                                conn.resume(s, now);
                            }
                            pump(conn, ob, FifoBound::Publish, host)
                        }
                        None => Pumped::Drained,
                    };
                    drop(core);
                    match pumped {
                        Pumped::Barrier(stopped) => {
                            return self.pause_close(CloseStage::Pump { role, stopped }, ob);
                        }
                        Pumped::Drained => {
                            // Publish the close marker following all session-closing results and
                            // preceding thread joins. Foreign closes defer the close marker until
                            // after running the shutdown tail.
                            if role != Role::Foreign {
                                ob.push_close_published();
                            }
                            stage = CloseStage::Threads {
                                role,
                                next: ThreadRole::Io,
                            };
                        }
                    }
                }
                CloseStage::Threads {
                    role,
                    next: ThreadRole::Io,
                } => {
                    let op = if role == Role::Io {
                        self.queue.fifo.lock_owner().io_exited = true;
                        ThreadOp::Detach
                    } else {
                        self.waker.wake();
                        ThreadOp::Join
                    };
                    ob.push(Item::Thread {
                        op,
                        role: ThreadRole::Io,
                    });
                    let next = if role == Role::Foreign {
                        CloseStage::Settle { role }
                    } else {
                        CloseStage::Threads {
                            role,
                            next: ThreadRole::Dispatch,
                        }
                    };
                    return self.pause_close(next, ob);
                }
                CloseStage::Settle { role } => {
                    // Flush any pending requeued items (such as the I/O thread join) before
                    // waiting for dispatch quiescence.
                    if !ob.is_empty() {
                        return self.pause_close(CloseStage::Settle { role }, ob);
                    }
                    // Wait for the dispatch thread without holding locks so callbacks can
                    // invoke client APIs.
                    self.queue.wait_idle();
                    stage = CloseStage::Tail { role };
                }
                CloseStage::Tail { role } => {
                    if role == Role::Dispatch {
                        // Drain remaining callback queue items directly into the caller's
                        // outbox during dispatch shutdown.
                        let left = std::mem::take(&mut self.queue.fifo.lock_owner().items);
                        ob.extend_items(left);
                        ob.deliver_here();
                    }
                    let paused = {
                        let mut guard = self.io.lock_owner();
                        self.run_tail(&mut guard, ob)
                    };
                    if paused {
                        return self.pause_close(CloseStage::Tail { role }, ob);
                    }
                    if role == Role::Dispatch {
                        stage = CloseStage::Epilog {
                            tail_at_exit: false,
                        };
                    } else {
                        // Emit the close marker after the shutdown tail so the dispatch
                        // thread delivers all results before join.
                        ob.push_close_published();
                        stage = CloseStage::Threads {
                            role,
                            next: ThreadRole::Dispatch,
                        };
                    }
                }
                CloseStage::Threads {
                    role,
                    next: ThreadRole::Dispatch,
                } => {
                    let op = if role == Role::Dispatch {
                        ThreadOp::Detach
                    } else {
                        // Ensure all queued completion items are delivered before the dispatch
                        // thread terminates.
                        self.queue.wake_all();
                        ThreadOp::Join
                    };
                    ob.push(Item::Thread {
                        op,
                        role: ThreadRole::Dispatch,
                    });
                    let next = CloseStage::Epilog {
                        tail_at_exit: role == Role::Dispatch,
                    };
                    return self.pause_close(next, ob);
                }
                CloseStage::Epilog { tail_at_exit } => {
                    if tail_at_exit {
                        *self.close_stage.lock_owner() = CloseStage::AtDispatchExit;
                    } else {
                        // If an application callback terminated the dispatch thread via
                        // pthread_exit, reclaim its reference now.
                        let ended = self.queue.fifo.lock_owner().dispatch_ended;
                        if ended && self.life.epilog() {
                            return self.final_close(ob, rc);
                        }
                    }
                    return self.leave(ob, rc);
                }
                // Reserved for execution upon completion thread exit; preserved until that thread
                // terminates.
                CloseStage::AtDispatchExit => {
                    *self.close_stage.lock_owner() = CloseStage::AtDispatchExit;
                    *rc = ZOK;
                    return Step::Done(ZOK);
                }
            }
        }
    }

    /// Persists the active closure progress stage and yields `Step::More` to allow host outbox
    /// draining.
    fn pause_close(&self, stage: CloseStage, ob: &mut Outbox<T>) -> Step {
        *self.close_stage.lock_owner() = stage;
        ob.set_resume(ResumeAt::Close);
        Step::More
    }

    /// Initiates final handle teardown, ensuring exactly once execution via atomic lifecycle
    /// transition.
    fn final_close(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        if !self.life.begin_final() {
            *rc = ZOK;
            return Step::Done(ZOK);
        }
        self.run_final(ob, rc)
    }

    /// Continues final handle teardown across suspension barriers until reaching the terminal
    /// release stage.
    fn run_final(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let mut guard = self.io.lock_owner();
        let side = &mut *guard;
        if self.run_tail(side, ob) {
            return pause_final(ob);
        }
        let mut proof = Unlocked::new_at_entry();
        if side.final_stage == FinalStage::Release {
            side.final_stage = FinalStage::Released;
            self.release(side, ob, &mut proof);
        }
        *rc = side.final_rc;
        Step::Release
    }

    /// Executes the [`FinalStage`] closing tail sequence once the I/O thread has exited or when
    /// closing a single-reference handle:
    ///
    /// - **`FinalStage::NotStarted`**: Restores any requeued items from interrupted callbacks,
    ///   calls `conn.queue_close_op()`, and emits `LogSite::Closing` if a `CLOSE_OP` frame was
    ///   staged. If `ob` is non-empty, pauses (`return true`) so the C shim logs `Closing` before
    ///   blocking in `close_flush`.
    /// - **`FinalStage::Flush`**: Calls [`IoCore::close_flush`] to transmit the queued `CLOSE_OP`
    ///   over the TCP/TLS socket with a bounded timeout (`CLOSE_FLUSH_TIMEOUT_MS`), recording the
    ///   resulting return code in `side.final_rc`.
    /// - **`FinalStage::Free`**: Emits `LogSite::Freeing` and invokes `conn.destroy(now)`.
    /// - **`FinalStage::Destroy`**: Pumps any remaining outputs from `conn.destroy(now)` across
    ///   barrier boundaries until `Pumped::Drained` is reached, transitioning to
    ///   `FinalStage::Release`.
    ///
    /// Returns `true` if the caller must pause (`Step::More`) to drain `ob` before resuming, or
    /// `false` once `FinalStage::Release` is reached.
    fn run_tail(&self, side: &mut IoSide, ob: &mut Outbox<T>) -> bool {
        let host = &*self.env.host;
        let mut proof = Unlocked::new_at_entry();
        loop {
            match side.final_stage {
                FinalStage::NotStarted => {
                    let mut core = self.core.lock(&mut proof);
                    // Deliver any requeued items left by callbacks before proceeding with closing
                    // actions.
                    core.restore_requeued(ob);
                    let closing = core.conn.as_deref_mut().and_then(|conn| {
                        conn.queue_close_op()
                            .then(|| (conn.client_id(), conn.current_server()))
                    });
                    drop(core);
                    side.final_rc = ZOK;
                    side.final_stage = FinalStage::Free;
                    if let Some((id, server)) = closing {
                        let args = vec![session_arg(&id), LogArg::Endpoint(server)];
                        push_log(host, ob, LogSite::Closing, args);
                        if side.io.has_socket() {
                            side.final_stage = FinalStage::Flush;
                        }
                    }
                    // Flush accumulated log messages to host callbacks before blocking on socket
                    // flush.
                    if !ob.is_empty() {
                        return true;
                    }
                }
                FinalStage::Flush => {
                    // Flush pending outbound frames over the socket before terminating transport.
                    let cx =
                        self.env
                            .step_ctx(&self.core, Self::FLAVOR, Self::DEFER, None, None, None);
                    side.final_rc = side.io.close_flush(&cx, &mut proof, ob);
                    side.final_stage = FinalStage::Free;
                }
                FinalStage::Free => {
                    let now = self.env.clock.now();
                    let mut core = self.core.lock(&mut proof);
                    if let Some(conn) = core.conn.as_deref_mut() {
                        let args = vec![session_arg(&conn.client_id())];
                        push_log(host, ob, LogSite::Freeing, args);
                        conn.destroy(now);
                    }
                    side.final_stopped = None;
                    side.final_stage = FinalStage::Destroy;
                }
                FinalStage::Destroy => {
                    let now = self.env.clock.now();
                    let mut core = self.core.lock(&mut proof);
                    let pumped = match core.conn.as_deref_mut() {
                        Some(conn) => {
                            if let Some(s) = side.final_stopped.take() {
                                conn.resume(s, now);
                            }
                            pump(conn, ob, FifoBound::Publish, host)
                        }
                        None => Pumped::Drained,
                    };
                    drop(core);
                    match pumped {
                        Pumped::Barrier(stopped) => {
                            side.final_stopped = stopped;
                            return true;
                        }
                        Pumped::Drained => {
                            side.final_stage = FinalStage::Release;
                            // Ensure FIFO-bound results are published to the queue before
                            // proceeding.
                            if !ob.is_empty() {
                                return true;
                            }
                        }
                    }
                }
                FinalStage::Release | FinalStage::Released => return false,
            }
        }
    }

    /// Finalizes handle release, dropping the underlying transport, disposing SASL, and discarding
    /// leftover tokens.
    fn release(&self, side: &mut IoSide, ob: &mut Outbox<T>, proof: &mut Unlocked) {
        let host = &*self.env.host;
        let now = self.env.clock.now();
        let mut core = self.core.lock(proof);
        if let Some(conn) = core.conn.as_deref_mut() {
            for _ in 0..MAX_FINAL_ACTIONS {
                match conn.poll_action(now) {
                    Some(Action::CloseTransport) => side.io.drop_transport(),
                    Some(Action::Sasl(call)) => {
                        ob.push(Item::Sasl(SaslItem { call, addrs: None }));
                    }
                    // A destroyed connection has no other active work.
                    Some(_) => {}
                    None => break,
                }
            }
        }
        side.io.drop_transport();
        core.peer = None;
        core.sasl = None;
        core.stopped = None;
        let conn = core.conn.take();
        let requeued = core.requeued.take();
        let local = std::mem::take(&mut core.local);
        drop(core);
        if let Some(conn) = conn {
            discard_leftovers(host, conn);
        }
        if let Some(requeued) = requeued {
            let mut rest = Outbox::new();
            rest.restore(requeued);
            rest.finish(host);
        }
        let fifo = std::mem::take(&mut self.queue.fifo.lock_owner().items);
        for item in local.into_iter().chain(fifo) {
            discard_item(host, item);
        }
    }
}

/// Sets the resume tag for final handle release and yields `Step::More`.
fn pause_final<T: DriverTokens>(ob: &mut Outbox<T>) -> Step {
    ob.set_resume(ResumeAt::FinalClose);
    Step::More
}

/// Emits a structured diagnostic log record into the outbox if the host logging level permits.
fn push_log<T: DriverTokens>(
    host: &dyn HostLeaves<T>,
    ob: &mut Outbox<T>,
    site: LogSite,
    args: Vec<LogArg>,
) {
    if let Some(rec) = log_record(host, site, args) {
        ob.push(Item::Log(rec));
    }
}

/// Formats a 64-bit client session identifier as a hexadecimal pointer argument matching protocol
/// formatting.
fn session_arg(id: &ClientId) -> LogArg {
    LogArg::Ptr(u64::from_ne_bytes(id.session_id.to_ne_bytes()))
}

/// Generates a diagnostic log record for invalid ensemble connection strings.
fn parse_error_record<T: DriverTokens>(
    host: &dyn HostLeaves<T>,
    e: &HostParseError,
) -> Option<LogRecord> {
    let site = match e.kind {
        HostParseErrorKind::NoPort => LogSite::ResolveNoPort,
        HostParseErrorKind::InvalidPort => LogSite::ResolveBadPort,
        _ => return None,
    };
    let args = vec![LogArg::Str(JString::Bytes(e.subject.clone()))];
    log_record(host, site, args)
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
    use std::time::Duration;

    use crate::net::poll::Poll;
    use crate::testutil::{RealPoll, TestHost, exists_request, test_connection};
    use zk_jute::rc::ZBADARGUMENTS;
    use zk_proto::{DriverMode, PathIn, Route, SaslError};
    use zk_testkit::tokens::{AuthId, TestTokens};

    type S = Shared<TestTokens>;

    /// Constructs an isolated test harness wrapping a multithreaded driver instance with no
    /// underlying socket.
    fn handle() -> (Arc<S>, Arc<TestHost>) {
        let host = Arc::new(TestHost::new());
        let leaves: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        let core = Core::new(Box::new(test_connection(DriverMode::MT)));
        let mut ob = Outbox::new();
        let shared = Shared::new(core, Env::new(leaves), &mut ob).unwrap();
        assert!(ob.is_empty());
        (shared, host)
    }

    /// Formats an outbox item into a human-readable diagnostic string for test assertions.
    fn label(item: &Item<TestTokens>) -> String {
        match item {
            Item::Thread { op, role } => format!("{op:?} {role:?}"),
            Item::AuthResult { token, .. } => format!("AuthResult {}", token.id()),
            Item::Result { token, result, .. } => format!("Result {} {}", token.id(), result.rc),
            other => format!("{other:?}"),
        }
    }

    /// Drives a driver execution step to completion, draining outbox items to the callback across
    /// intermediate yields.
    fn drive(
        s: &S,
        first: Step,
        ob: &mut Outbox<TestTokens>,
        rc: &mut i32,
        mut on: impl FnMut(&Item<TestTokens>),
    ) -> (Step, Vec<String>) {
        let mut seen = Vec::new();
        let mut step = first;
        loop {
            while let Some(item) = ob.pop() {
                on(&item);
                seen.push(label(&item));
            }
            if step != Step::More {
                return (step, seen);
            }
            step = s.resume(ob, rc);
        }
    }

    /// Executes a full session termination sequence for a specified caller role, accumulating
    /// delivered items and logging output.
    fn close(s: &S, role: Role, on: impl FnMut(&Item<TestTokens>)) -> (Step, i32, Vec<String>) {
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.close(role, &mut ob, &mut rc);
        let (end, seen) = drive(s, first, &mut ob, &mut rc, on);
        (end, rc, seen)
    }

    /// Coordinates thread exit and lifecycle decrements for a given thread role, draining any
    /// resulting outbox events.
    fn exit(s: &S, role: Role) -> (Step, i32) {
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.thread_exit(role, &mut ob, &mut rc);
        let (end, _) = drive(s, first, &mut ob, &mut rc, |_| {});
        (end, rc)
    }

    /// Executes a single nonblocking reactor step on the test handle.
    fn io_step(s: &S) -> Step {
        let mut ob = s.outbox();
        let mut rc = -1;
        s.io_step(&mut ob, &mut rc)
    }

    /// Drives the completion dispatch loop until receiving `Step::Exit`, delivering and finalizing
    /// all events.
    fn dispatch_until_exit(s: &S) {
        loop {
            let mut ob = s.outbox();
            if s.wait_dispatch(&mut ob) == Step::Exit {
                return;
            }
            ob.finish(&*s.env.host);
        }
    }

    /// Spawns a dedicated OS thread executing the dispatch loop until exit, returning the
    /// chronological list of delivered items.
    fn spawn_dispatch(s: &Arc<S>) -> std::thread::JoinHandle<Vec<String>> {
        let s = Arc::clone(s);
        std::thread::spawn(move || {
            let mut seen = Vec::new();
            loop {
                let mut ob = s.outbox();
                if s.wait_dispatch(&mut ob) == Step::Exit {
                    break;
                }
                while let Some(item) = ob.pop() {
                    seen.push(label(&item));
                }
            }
            assert_eq!(exit(&s, Role::Dispatch), (Step::Done(ZOK), ZOK));
            seen
        })
    }

    /// Yields execution briefly to allow asynchronous background threads to block in conditional
    /// waits.
    fn settle() {
        std::thread::sleep(Duration::from_millis(50));
    }

    /// Inspects whether the terminal close marker has been published to the dispatch FIFO.
    fn published(s: &S) -> bool {
        s.queue.fifo.lock_owner().close_published
    }

    /// Constructs a synchronous `exists` request routed directly to the server.
    fn sync_exists(id: u64) -> Request<TestTokens> {
        Request {
            route: Route::Direct,
            ..exists(id)
        }
    }

    /// Filters a sequence of item labels to isolate thread lifecycle coordination commands.
    fn thread_items(seen: &[String]) -> Vec<&str> {
        seen.iter()
            .map(String::as_str)
            .filter(|l| l.starts_with("Join") || l.starts_with("Detach"))
            .collect()
    }

    /// Queries whether the driver core retains an active connection instance.
    fn has_conn(s: &S) -> bool {
        let mut proof = Unlocked::new_at_entry();
        s.core.lock(&mut proof).conn.is_some()
    }

    /// Polls the waker channel descriptor to determine whether an interrupt notification is
    /// pending.
    fn readable(s: &S) -> bool {
        let side = s.io.lock_owner();
        let mut fds = [PollFd::new(&side.wake_rx, PollFlags::IN)];
        RealPoll.poll(&mut fds, 0).unwrap() == 1
    }

    /// Constructs an asynchronous `exists` request for testing request submission and completion.
    fn exists(id: u64) -> Request<TestTokens> {
        exists_request(PathIn::Legacy(Some(b"/".to_vec())), id)
    }

    #[test]
    fn thread_refs_and_the_epilog() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert_eq!(s.life.refs(), 2);
        assert_eq!(exit(&s, Role::Dispatch), (Step::Done(ZOK), ZOK));
        assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
        assert_eq!(s.life.refs(), 0);
        assert!(s.queue.fifo.lock_owner().io_exited);
        assert!(has_conn(&s));
        // Verify that the final reference released after a close request triggers handle
        // destruction.
        s.thread_enter(Role::Init);
        assert!(s.life.request_close());
        assert_eq!(exit(&s, Role::Init), (Step::Release, ZOK));
        assert!(!has_conn(&s));
        assert_eq!(s.state(), 0);
    }

    #[test]
    fn wait_dispatch_keeps_the_order() {
        let (s, _host) = handle();
        for n in 1..=3 {
            s.queue.publish(Item::AuthResult {
                token: AuthId::new(n),
                rc: ZOK,
            });
        }
        let mut ob = s.outbox();
        assert_eq!(s.wait_dispatch(&mut ob), Step::Done(ZOK));
        let got: Vec<String> = std::iter::from_fn(|| ob.pop()).map(|i| label(&i)).collect();
        assert_eq!(got, ["AuthResult 1", "AuthResult 2", "AuthResult 3"]);
        assert!(s.queue.fifo.lock_owner().items.is_empty());
    }

    #[test]
    fn publish_on_take_feeds_the_fifo_in_order() {
        let (s, _host) = handle();
        let mut ob = s.outbox();
        ob.push_publish(Item::AuthResult {
            token: AuthId::new(1),
            rc: ZOK,
        });
        ob.push(Item::AuthLockpoint);
        ob.push_publish(Item::AuthResult {
            token: AuthId::new(2),
            rc: ZOK,
        });
        assert!(matches!(ob.pop(), Some(Item::AuthLockpoint)));
        assert_eq!(s.queue.fifo.lock_owner().items.len(), 1);
        assert!(ob.pop().is_none());
        let mut batch = s.outbox();
        assert_eq!(s.wait_dispatch(&mut batch), Step::Done(ZOK));
        let got: Vec<String> = std::iter::from_fn(|| batch.pop())
            .map(|i| label(&i))
            .collect();
        assert_eq!(got, ["AuthResult 1", "AuthResult 2"]);
    }

    #[test]
    fn the_io_exit_wakes_the_dispatch_thread() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let waiter = {
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                let mut ob = s.outbox();
                s.wait_dispatch(&mut ob)
            })
        };
        settle();
        assert!(s.life.request_close());
        s.queue.wake_all();
        // A close request alone must not terminate the dispatch wait.
        settle();
        assert!(!waiter.is_finished());
        // The close marker alone must not terminate the dispatch wait until the I/O thread exits.
        s.queue.close_published();
        settle();
        assert!(!waiter.is_finished());
        assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
        assert_eq!(waiter.join().unwrap(), Step::Exit);
        assert_eq!(s.life.refs(), 1);
    }

    /// Verifies that when the I/O thread exits between a close request and the draining of
    /// terminal `ZCLOSING` results, the dispatch thread awaits the close marker and delivers
    /// all pending events without data loss.
    #[test]
    fn the_dispatch_thread_waits_for_the_close_mark() {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert!(s.submit(exists(1)).is_ok());
        assert!(s.submit(exists(2)).is_ok());
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.close(Role::Foreign, &mut ob, &mut rc);
        // Terminal results remain buffered in the outbox while the I/O thread detects closure and
        // exits.
        assert_eq!(io_step(&s), Step::Exit);
        assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
        {
            let fifo = s.queue.fifo.lock_owner();
            assert!(fifo.io_exited && fifo.items.is_empty() && !fifo.close_published);
        }
        assert!(s.life.close_requested());
        let mut waiter = Some(spawn_dispatch(&s));
        settle();
        assert!(!waiter.as_ref().unwrap().is_finished());
        let mut got = Vec::new();
        let (end, seen) = drive(&s, first, &mut ob, &mut rc, |item| match item {
            // In foreign closes, the close marker is published after the shutdown tail rather than
            // at the I/O join.
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } => assert!(!published(&s)),
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } => {
                assert!(published(&s));
                got = waiter.take().unwrap().join().unwrap();
            }
            _ => {}
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        let closing = format!("{}", zk_jute::rc::ZCLOSING);
        assert_eq!(
            got,
            [format!("Result 1 {closing}"), format!("Result 2 {closing}")]
        );
        assert!(host.discarded().is_empty());
        assert!(!has_conn(&s));
    }

    /// Verifies that an I/O thread terminating early due to an unrecoverable error does not
    /// prematurely stop the dispatch thread before terminal close events are published.
    #[test]
    fn an_early_io_exit_keeps_the_dispatch_thread_until_the_close_mark() {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
        let mut waiter = Some(spawn_dispatch(&s));
        settle();
        assert!(!waiter.as_ref().unwrap().is_finished());
        assert!(s.submit(exists(3)).is_ok());
        let mut got = Vec::new();
        let (end, rc, seen) = close(&s, Role::Foreign, |item| {
            if let Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } = item
            {
                got = waiter.take().unwrap().join().unwrap();
            }
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        assert_eq!(got, [format!("Result 3 {}", zk_jute::rc::ZCLOSING)]);
        assert!(host.discarded().is_empty());
    }

    /// Verifies that the close marker is only emitted after all multi-step outbox draining
    /// passes and barrier yields have completed.
    #[test]
    fn the_close_mark_follows_every_barrier_of_the_close_pump() {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        assert!(s.submit(sync_exists(1)).is_ok());
        assert!(s.submit(exists(2)).is_ok());
        assert!(s.submit(sync_exists(3)).is_ok());
        let mut ob = s.outbox();
        let mut rc = -1;
        let first = s.close(Role::Foreign, &mut ob, &mut rc);
        assert_eq!(first, Step::More);
        let mut before = Vec::new();
        let mut joined = false;
        let mut waiter = None;
        let mut got = Vec::new();
        let (end, seen) = drive(&s, first, &mut ob, &mut rc, |item| match item {
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } => {
                joined = true;
                assert!(!published(&s));
                assert_eq!(io_step(&s), Step::Exit);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
                waiter = Some(spawn_dispatch(&s));
            }
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } => got = waiter.take().unwrap().join().unwrap(),
            other if !joined => before.push((label(other), published(&s))),
            _ => {}
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        let closing = zk_jute::rc::ZCLOSING;
        // Synchronous request results act as yield barriers during close draining; the close
        // marker must not precede them.
        let syncs: Vec<_> = before
            .iter()
            .filter(|(l, _)| l.starts_with("Result"))
            .cloned()
            .collect();
        assert_eq!(
            syncs,
            [
                (format!("Result 1 {closing}"), false),
                (format!("Result 3 {closing}"), false)
            ]
        );
        assert!(before.iter().all(|(_, p)| !p), "{before:?}");
        assert!(before.iter().any(|(l, _)| l == "AuthLockpoint"));
        assert_eq!(got, [format!("Result 2 {closing}")]);
        assert!(host.discarded().is_empty());
    }

    /// Verifies that session termination on an uninitialized or broken connection still publishes
    /// the close marker to release the dispatch thread.
    #[test]
    fn a_close_with_no_connection_still_gives_the_mark() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        {
            let mut proof = Unlocked::new_at_entry();
            s.core.lock(&mut proof).conn = None;
        }
        let mut waiter = Some(spawn_dispatch(&s));
        let (end, rc, seen) = close(&s, Role::Foreign, |item| match item {
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } => {
                assert!(!published(&s));
                assert_eq!(io_step(&s), Step::Exit);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
            }
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } => {
                assert!(published(&s));
                assert!(waiter.take().unwrap().join().unwrap().is_empty());
            }
            _ => {}
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
    }

    #[test]
    fn submit_wakes_the_waker() {
        let (s, _host) = handle();
        assert!(!readable(&s));
        assert!(matches!(s.submit(exists(1)), Ok(Submitted::Queued { .. })));
        assert!(readable(&s));
        {
            let side = s.io.lock_owner();
            side.wake_rx.reset(&s.waker);
        }
        assert!(!readable(&s));
        assert!(s.submit(exists(2)).is_ok());
        assert!(readable(&s));
    }

    /// Confirms that request submission after initiating closure fails with `ZMARSHALLINGERROR`
    /// without attempting flush on the caller thread.
    #[test]
    fn submit_after_a_close_request_fails() {
        let (s, _host) = handle();
        assert!(s.life.request_close());
        let err = s.submit(exists(7)).err().unwrap();
        assert_eq!(err.code, ZMARSHALLINGERROR);
        assert_eq!(err.token.id(), 7);
        assert!(!readable(&s));
    }

    #[test]
    fn close_from_a_foreign_thread_joins_both_threads() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let (end, rc, seen) = close(&s, Role::Foreign, |item| match item {
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } => {
                assert_eq!(io_step(&s), Step::Exit);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
            }
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } => {
                dispatch_until_exit(&s);
                assert_eq!(exit(&s, Role::Dispatch), (Step::Done(ZOK), ZOK));
            }
            _ => {}
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        assert!(!has_conn(&s));
    }

    /// Constructs a simulated authentication completion item for outbox testing.
    fn auth_result(n: u64) -> Item<TestTokens> {
        Item::AuthResult {
            token: AuthId::new(n),
            rc: ZOK,
        }
    }

    /// Simulates host runtime handling of an I/O thread join command during foreign close.
    fn join_io(s: &S, item: &Item<TestTokens>) {
        if let Item::Thread {
            op: ThreadOp::Join,
            role: ThreadRole::Io,
        } = item
        {
            assert_eq!(io_step(s), Step::Exit);
            assert_eq!(exit(s, Role::Io), (Step::Done(ZOK), ZOK));
        }
    }

    /// Verifies that if an application callback aborts the dispatch thread while a foreign close
    /// is waiting for quiescence, the cleanup handler unblocks the wait and reclaims the thread
    /// reference.
    #[test]
    fn dispatch_end_during_a_foreign_close_ends_the_wait() {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        for n in 1..=3 {
            s.queue.publish(auth_result(n));
        }
        let dispatch = {
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                let mut ob = s.outbox();
                assert_eq!(s.wait_dispatch(&mut ob), Step::Done(ZOK));
                // Application callback terminates the dispatch thread after the closer enters its
                // settling wait.
                assert_eq!(label(&ob.pop().unwrap()), "AuthResult 1");
                while !s.queue.fifo.lock_owner().settling {
                    std::thread::yield_now();
                }
                s.queue.publish(auth_result(4));
                s.dispatch_ended(&mut ob);
            })
        };
        let mut dispatch = Some(dispatch);
        let (end, rc, seen) = close(&s, Role::Foreign, |item| {
            join_io(&s, item);
            if let Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } = item
            {
                dispatch.take().unwrap().join().unwrap();
            }
        });
        assert!(dispatch.is_none());
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        // Unconsumed items remaining in the active batch and queued in the FIFO are reclaimed via
        // discard hooks.
        assert_eq!(host.discarded(), ["auth", "auth", "auth"]);
        assert_eq!(s.life.refs(), 0);
        assert!(!has_conn(&s));
    }

    /// Verifies that if the dispatch thread terminates before shutdown begins, the close sequence
    /// proceeds without waiting for dispatch quiescence.
    #[test]
    fn dispatch_end_before_a_foreign_close() {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        s.queue.publish(auth_result(1));
        let mut ob = s.outbox();
        assert_eq!(s.wait_dispatch(&mut ob), Step::Done(ZOK));
        s.dispatch_ended(&mut ob);
        assert_eq!(host.discarded(), ["auth"]);
        s.queue.publish(auth_result(2));
        let (end, rc, seen) = close(&s, Role::Foreign, |item| join_io(&s, item));
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        assert_eq!(host.discarded(), ["auth", "auth"]);
        assert_eq!(s.life.refs(), 0);
        assert!(!has_conn(&s));
    }

    /// Ensures that when a callback initiates closure and subsequently aborts the dispatch thread,
    /// the close tail is preserved at thread exit and the reference is retained.
    #[test]
    fn dispatch_end_after_a_dispatch_close_keeps_the_ref() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let (end, rc, _) = close(&s, Role::Dispatch, |item| {
            if let Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } = item
            {
                assert_eq!(io_step(&s), Step::Exit);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
            }
        });
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        let mut ob = s.outbox();
        s.dispatch_ended(&mut ob);
        assert_eq!(s.life.refs(), 1);
        assert!(has_conn(&s));
        assert_eq!(*s.close_stage.lock_owner(), CloseStage::AtDispatchExit);
    }

    /// Verifies that if the dispatch thread terminates before closure is triggered from an I/O
    /// callback, the close reclaims the ended thread reference and finalizes upon I/O thread
    /// exit.
    #[test]
    fn dispatch_end_before_a_close_from_the_io_thread() {
        let (s, host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        s.queue.publish(auth_result(1));
        let mut ob = s.outbox();
        assert_eq!(s.wait_dispatch(&mut ob), Step::Done(ZOK));
        s.dispatch_ended(&mut ob);
        assert_eq!(host.discarded(), ["auth"]);
        s.queue.publish(auth_result(2));
        let (end, rc, seen) = close(&s, Role::Io, |_| {});
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        assert_eq!(thread_items(&seen), ["Detach Io", "Join Dispatch"]);
        assert!(has_conn(&s));
        assert_eq!(io_step(&s), Step::Exit);
        assert_eq!(exit(&s, Role::Io), (Step::Release, ZOK));
        assert_eq!(host.discarded(), ["auth", "auth"]);
        assert_eq!(s.life.refs(), 0);
        assert!(!has_conn(&s));
    }

    #[test]
    fn close_from_the_io_thread_detaches_it() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let (end, rc, seen) = close(&s, Role::Io, |item| {
            if let Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } = item
            {
                // Detaching the I/O thread sets io_exited, allowing the dispatch thread to exit
                // once the close marker is reached.
                assert!(published(&s));
                dispatch_until_exit(&s);
                assert_eq!(exit(&s, Role::Dispatch), (Step::Done(ZOK), ZOK));
            }
        });
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        assert_eq!(thread_items(&seen), ["Detach Io", "Join Dispatch"]);
        assert!(s.queue.fifo.lock_owner().io_exited);
        assert!(has_conn(&s));
        // The I/O thread leaves its reactor loop and drives final handle teardown.
        assert_eq!(io_step(&s), Step::Exit);
        assert_eq!(exit(&s, Role::Io), (Step::Release, ZOK));
        assert!(!has_conn(&s));
    }

    #[test]
    fn close_from_the_dispatch_thread_detaches_it() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let (end, rc, seen) = close(&s, Role::Dispatch, |item| {
            if let Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } = item
            {
                assert_eq!(io_step(&s), Step::Exit);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
            }
        });
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Detach Dispatch"]);
        assert!(has_conn(&s));
        // The dispatch thread publishes the close marker during its own close, avoiding self-
        // waiting.
        assert!(published(&s));
        // The dispatch thread finishes its local queue, exits, and executes final teardown.
        dispatch_until_exit(&s);
        assert_eq!(exit(&s, Role::Dispatch), (Step::Release, ZOK));
        assert!(!has_conn(&s));
    }

    #[test]
    fn close_with_the_last_ref_runs_the_final_close() {
        let (s, host) = handle();
        assert!(s.submit(exists(1)).is_ok());
        let (end, rc, seen) = close(&s, Role::Foreign, |_| {});
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert!(thread_items(&seen).is_empty());
        // Request tokens that never left the core are discarded through host notification hooks.
        assert_eq!(host.discarded(), ["completion"]);
        assert!(s.queue.fifo.lock_owner().items.is_empty());
        let err = s.submit(exists(2)).err().unwrap();
        assert_eq!(err.code, ZMARSHALLINGERROR);
        // Subsequent close calls after final release are idempotent no-ops.
        let (end, rc, seen) = close(&s, Role::Foreign, |_| {});
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        assert!(seen.is_empty());
    }

    #[test]
    fn a_second_close_during_a_close_is_a_no_op() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        let (end, rc, seen) = close(&s, Role::Foreign, |item| {
            if let Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } = item
            {
                let mut ob = s.outbox();
                let mut rc = -1;
                assert_eq!(s.close(Role::Foreign, &mut ob, &mut rc), Step::Done(ZOK));
                assert_eq!(rc, ZOK);
                assert!(ob.is_empty());
                assert_eq!(s.life.refs(), 2);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
            }
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
    }

    #[test]
    fn sasl_result_and_requeue_are_stored() {
        let (s, _host) = handle();
        s.sasl_result(SaslAnswer::Stepped {
            generation: 4,
            result: Err(SaslError {
                rc: -1,
                errstring: Vec::new(),
                errdetail: Vec::new(),
            }),
        });
        let mut proof = Unlocked::new_at_entry();
        assert!(matches!(
            s.core.lock(&mut proof).sasl,
            Some(SaslAnswer::Stepped { generation: 4, .. })
        ));

        let mut ob = s.outbox();
        ob.push(Item::AuthLockpoint);
        ob.push_publish(Item::AuthResult {
            token: AuthId::new(1),
            rc: ZOK,
        });
        ob.set_resume(ResumeAt::IoStep);
        s.requeue(ob.requeue());
        let mut ob = s.outbox();
        ob.push(Item::AuthResult {
            token: AuthId::new(2),
            rc: ZOK,
        });
        s.requeue(ob.requeue());

        let mut back = Outbox::new();
        assert!(s.core.lock(&mut proof).restore_requeued(&mut back));
        assert_eq!(back.resume_at(), None);
        let got: Vec<String> = std::iter::from_fn(|| back.pop())
            .map(|i| label(&i))
            .collect();
        assert_eq!(got, ["AuthLockpoint", "AuthResult 1", "AuthResult 2"]);
        assert!(s.core.lock(&mut proof).requeued.is_none());
    }

    #[test]
    #[cfg_attr(miri, ignore = "Miri reports every descriptor as CLOEXEC")]
    fn the_waker_pipe_has_no_cloexec_with_the_sock_flag() {
        let host = Arc::new(TestHost::new());
        let leaves: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        let core = Core::new(Box::new(test_connection(DriverMode::MT)));
        let mut env = Env::new(leaves);
        env.cfg.cloexec = true;
        let mut ob = Outbox::new();
        let s = Shared::new(core, env, &mut ob).unwrap();
        let io = s.io.lock_owner();
        let flags = rustix::io::fcntl_getfd(&io.wake_rx).unwrap();
        assert!(!flags.contains(rustix::io::FdFlags::CLOEXEC));
    }

    #[test]
    fn set_servers_resolves_and_wakes() {
        let (s, host) = handle();
        host.set_addrs(b"a", vec!["127.0.0.1:1".parse().unwrap()]);
        let mut ob = s.outbox();
        let mut rc = -1;
        assert_eq!(s.set_servers(b"a:1", &mut ob, &mut rc), Step::Done(ZOK));
        assert_eq!(rc, ZOK);
        assert_eq!(host.resolves(), 1);
        assert_eq!(host.reseeds(), 1);
        assert!(readable(&s));
        // Malformed host specifications are rejected without committing server list changes.
        let step = s.set_servers(b"a", &mut ob, &mut rc);
        assert_eq!((step, rc), (Step::Done(ZBADARGUMENTS), ZBADARGUMENTS));
        assert_eq!(host.resolves(), 1);
        ob.finish(&*s.env.host);
    }

    #[test]
    fn set_servers_flushes_each_failure_line_before_the_next_resolve() {
        let (s, host) = handle();
        host.set_addrs(b"b", vec!["127.0.0.1:2".parse().unwrap()]);
        let mut ob = s.outbox();
        let mut rc = -1;
        // Diagnostic log messages from failed resolution attempts are flushed before attempting
        // subsequent endpoints.
        let first = s.set_servers(b"a:1,b:2", &mut ob, &mut rc);
        assert_eq!(first, Step::More);
        assert_eq!(host.resolves(), 1);
        assert!(!readable(&s));
        let (end, seen) = drive(&s, first, &mut ob, &mut rc, |_| {});
        assert!(!seen.is_empty());
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        assert_eq!((host.resolves(), host.reseeds()), (2, 1));
        assert!(readable(&s));
    }

    #[test]
    fn a_requeued_set_servers_run_goes_to_the_io_thread() {
        let (s, host) = handle();
        host.set_addrs(b"b", vec!["127.0.0.1:2".parse().unwrap()]);
        let mut ob = s.outbox();
        let mut rc = -1;
        assert_eq!(s.set_servers(b"a:1,b:2", &mut ob, &mut rc), Step::More);
        // If a callback unwinds and requeues an incomplete resolution run, the I/O thread resumes
        // and adopts it.
        s.requeue(ob.requeue());
        let mut proof = Unlocked::new_at_entry();
        {
            let core = s.core.lock(&mut proof);
            assert!(core.set_servers_runs.is_empty());
            assert_eq!(core.set_servers.as_ref().map(|(_, h)| h.len()), Some(2));
        }
        assert!(readable(&s));
    }

    #[test]
    fn a_second_close_continues_a_requeued_close_stage() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let mut ob = s.outbox();
        let mut rc = -1;
        assert_eq!(s.close(Role::Foreign, &mut ob, &mut rc), Step::More);
        assert_eq!(ob.resume_at(), Some(ResumeAt::Close));
        // If a callback unwinds and requeues the close stage, a subsequent close invocation adopts
        // and continues it.
        s.requeue(ob.requeue());
        let mut proof = Unlocked::new_at_entry();
        assert_eq!(s.core.lock(&mut proof).orphan_close, Some(ResumeAt::Close));
        // Verify that the second close continues the stage and emits the close marker after the
        // tail.
        assert!(!published(&s));
        let (end, rc, seen) = close(&s, Role::Foreign, |item| match item {
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Io,
            } => {
                assert!(!published(&s));
                assert_eq!(io_step(&s), Step::Exit);
                assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
            }
            Item::Thread {
                op: ThreadOp::Join,
                role: ThreadRole::Dispatch,
            } => {
                assert!(published(&s));
                dispatch_until_exit(&s);
                assert_eq!(exit(&s, Role::Dispatch), (Step::Done(ZOK), ZOK));
            }
            _ => {}
        });
        assert_eq!((end, rc), (Step::Release, ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        assert!(!has_conn(&s));
        assert!(s.core.lock(&mut proof).orphan_close.is_none());
    }

    /// Verifies that when a completion callback continues a previously requeued foreign close,
    /// it bypasses the dispatch idle wait to prevent self-deadlock.
    #[test]
    fn a_completion_callback_continues_a_requeued_foreign_close() {
        let (s, _host) = handle();
        s.thread_enter(Role::Io);
        s.thread_enter(Role::Dispatch);
        let mut ob = s.outbox();
        let mut rc = -1;
        assert_eq!(s.close(Role::Foreign, &mut ob, &mut rc), Step::More);
        // Callback unwinds and requeues the close stage; no immediate resume follows.
        s.requeue(ob.requeue());
        // Dispatch thread takes a batch whose callback initiates closure.
        s.queue.publish(Item::AuthResult {
            token: AuthId::new(1),
            rc: ZOK,
        });
        let mut batch = s.outbox();
        assert_eq!(s.wait_dispatch(&mut batch), Step::Done(ZOK));
        let closer = {
            let s = Arc::clone(&s);
            std::thread::spawn(move || {
                close(&s, Role::Dispatch, |item| {
                    if let Item::Thread {
                        op: ThreadOp::Join,
                        role: ThreadRole::Io,
                    } = item
                    {
                        assert_eq!(io_step(&s), Step::Exit);
                        assert_eq!(exit(&s, Role::Io), (Step::Done(ZOK), ZOK));
                    }
                })
            })
        };
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        while !closer.is_finished() {
            assert!(std::time::Instant::now() < deadline, "the close waits");
            settle();
        }
        let (end, rc, seen) = closer.join().unwrap();
        assert_eq!((end, rc), (Step::Done(ZOK), ZOK));
        assert_eq!(thread_items(&seen), ["Join Io", "Join Dispatch"]);
        assert!(published(&s));
        // The callback returns and the dispatch thread delivers the remainder of its batch before
        // exiting.
        assert_eq!(
            batch.pop().map(|i| label(&i)),
            Some("AuthResult 1".to_owned())
        );
        dispatch_until_exit(&s);
        assert_eq!(exit(&s, Role::Dispatch), (Step::Release, ZOK));
        assert!(!has_conn(&s));
    }

    #[test]
    fn the_exported_steps_report_no_socket() {
        let (s, _host) = handle();
        let out = s.interest_exported();
        assert_eq!((out.rc, out.written, out.fd, out.bits), (ZOK, true, -1, 0));
        assert_eq!(out.timeout_ms, i64::from(s.recv_timeout() / 3));
        assert_eq!(s.process_exported(), ZINVALIDSTATE);
        let (end, _, _) = close(&s, Role::Foreign, |_| {});
        assert_eq!(end, Step::Release);
        let out = s.interest_exported();
        assert_eq!((out.rc, out.written, out.fd), (ZINVALIDSTATE, false, -1));
    }

    #[test]
    fn peer_addr_reads_the_cache() {
        let (s, _host) = handle();
        assert_eq!(s.peer_addr(), None);
        let addr: SocketAddr = "127.0.0.1:2181".parse().unwrap();
        let mut proof = Unlocked::new_at_entry();
        s.core.lock(&mut proof).peer = Some(addr);
        assert_eq!(s.peer_addr(), Some(addr));
    }
}
