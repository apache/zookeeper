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

//! Single threaded caller-polled driver implementation (`libzookeeper_st`).
//!
//! In the single threaded execution model, no background threads are spawned by the library.
//! Instead, the host application or foreign C runtime retains ownership of the outer socket event
//! loop (`poll` / `select` / `epoll` / `kqueue`) and drives the ZooKeeper session in lockstep by
//! alternating calls to `zookeeper_interest` and `zookeeper_process`. [`Polled`] implements this
//! contract on top of [`crate::iocore::IoCore`]:
//!
//! - **[`Polled::interest`]**: Advances `IoCore::interest_step` under [`DeferPolicy::NONE`] to
//!   execute DNS lookups, initiate nonblocking TCP/TLS connections, run `isro` probes, check
//!   heartbeat timers, and compute descriptor polling interests and timeout deadlines
//!   (`InterestOut`).
//! - **[`Polled::process`]**: Advances `IoCore::process_step` to consume socket readiness flags,
//!   flush outbound request queues, read incoming wire frames, and stage decoded completions.
//! - **[`Polled::resume`]**: Re-enters suspended driver steps whenever `interest`, `process`,
//!   `set_servers`, or `close` pauses at an outbox barrier yielding [`Step::More`]. Each active
//!   [`Outbox`] carries both a [`ResumeAt`] phase tag and a frame serial number (`Frame::id`) so
//!   resumption correlates cleanly with the originating call frame.
//!
//! # Single Threaded Completion Buffering (`Core::local`)
//!
//! Unlike multithreaded mode (where a dedicated dispatch thread consumes a shared FIFO queue
//! concurrently), single threaded mode buffers asynchronous completions (`Route::Queued`), watch
//! notifications, and non-barrier session events inside `Core::local` (`FifoBound::Local`). These
//! items remain queued in `Core::local` until either the protocol engine emits an explicit
//! `DrainPoint` / barrier `Session` output, or `zookeeper_process` finishes its `IoCore` step in
//! `Polled::st_after_step` (mirroring C `process_completions` running at the tail of
//! `zookeeper_process`).
//!
//! # Reentrancy, Nested Driving Calls, and Deferred Closure
//!
//! Because application callbacks run synchronously on the caller's thread while draining an
//! [`Outbox`], a user watcher or completion callback may reentrantly invoke `zookeeper_interest`,
//! `zookeeper_process`, `zoo_aset`, or `zookeeper_close` before the outer driving call has
//! finished! [`Polled`] supports arbitrary callback reentrancy through three mechanisms:
//!
//! 1. **Activation Frame Stack (`StIo::frames`)**: Each entry into `interest` or `process` pushes
//!    an isolated `Frame` onto `StIo::frames` and acquires a [`Lifecycle::prolog`] reference for
//!    the duration of that frame (from `st_enter` through `st_epilog`).
//! 2. **Completing Suspended Outer Steps Before Nested Steps (`st_begin`)**: Because [`IoCore`]
//!    maintains a single continuation slot (`IoCore::cont`), if a callback invoked mid-step (while
//!    `st.io.is_suspended()` is true) reentrantly calls `interest` or `process`, `st_begin` records
//!    the requested `NewStep` in the new frame's `top.next` field and first drives
//!    `st.io.resume` to finish the suspended outer `IoCore` step! Once that outer step finishes,
//!    `st_after_step` updates all waiting outer frames on the stack to `StPhase::Drain` and
//!    `st_epilog` seamlessly launches the deferred `top.next` step.
//! 3. **Deferred Teardown on Nested Close (`StClose::Deferred`)**: If `Polled::close` is called
//!    while one or more driving frames are active on the stack (`refs > 1`), destroying the
//!    connection immediately would invalidate the outer frames still unwinding on the call stack.
//!    Instead, `Polled::st_close_nested` transitions the protocol engine into closing
//!    (`begin_close`), pumps and delivers auth completions inline while collecting asynchronous
//!    `ZCLOSING` completions into `Core::local`, and marks `st.close = StClose::Deferred`. Actual
//!    socket flushing (`CLOSE_OP`), connection destruction, and `Step::Release` are deferred until
//!    the outermost driving frame reaches `Polled::st_epilog` and drops the final lifecycle
//!    reference.

use std::net::SocketAddr;
use std::sync::Arc;

use rustix::event::PollFd;
use zk_jute::JString;
use zk_proto::legacy::connect::{HostParseErrorKind, parse_servers};
use zk_proto::rc::{ZINVALIDSTATE, ZOK, ZSYSTEMERROR};
use zk_proto::{
    Action, AddAuthError, ClientId, Connection, HostSpec, LogArg, LogSite, Millis, MultiOpRef,
    Request, SecretBytes, Stage, State, SubmitError, Submitted,
};

use crate::host::{DriverTokens, discard_leftovers, log_record};
use crate::iocore::{DeferPolicy, InterestOut, IoCore, Progress, StepCtx};
use crate::lifecycle::Lifecycle;
use crate::net::poll::{PollEvents, map_revents, request_flags};
use crate::outbox::{
    FifoBound, Flavor, Item, Outbox, Publish, Requeued, ResumeAt, SaslItem, Step, discard_item,
    pump,
};
use crate::resolve::{SetServersEnd, SetServersRun, adopt_set_servers};
use crate::state::{
    Core, Env, SaslAnswer, SubmitResult, read_at_entry, read_conn, with_conn_at_entry,
};
use crate::sync::{Mutex, Unlocked};

/// Maximum number of outbox pumping passes permitted within a single close phase.
///
/// Ensures that unconsumed protocol outputs are bounded during session termination while
/// preventing infinite loops in the presence of recurring barriers or defects.
const PUMP_PASSES: usize = 64;

/// Upper limit on protocol actions processed during the final resource release phase.
const RELEASE_ACTIONS: usize = 16;

/// Tracks the phased execution state of connection shutdown in single threaded mode.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum StClose {
    /// Normal operating state; no shutdown sequence is active.
    Idle,
    /// Connection close was initiated from within a callback; final teardown is deferred to
    /// epilog.
    Deferred,
    /// Outbound close request and diagnostic logs flushed; awaiting transport drain.
    Flush,
    /// Protocol engine destroyed; proceeding to final resource release.
    Release,
}

/// Internal execution phase for an active driving call frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum StPhase {
    /// Inactive frame with no operation currently executing.
    Idle,
    /// Driving step paused at an I/O or state barrier, awaiting resume.
    Step(ResumeAt),
    /// Driving step finished and outputs staged; awaiting outbox callback draining.
    Drain(ResumeAt),
}

/// Pending driving operation scheduled to execute upon completion of a prior suspended stage.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum NewStep {
    /// Interest calculation step (`zookeeper_interest`).
    Interest,
    /// Network I/O processing step (`zookeeper_process`) with received event bitmask.
    Process(u32),
}

/// Activation frame representing an in-flight driver call within the reentrant call stack.
///
/// Retains a lifecycle reference spanning from initial entry prolog through completion epilog.
/// If an application callback raises an exception or aborts, the frame is marked abandoned
/// so subsequent cleanup passes can reclaim its resources.
#[derive(Clone, Copy, Debug)]
struct Frame {
    /// Unique identifier correlated with the driving call's [`Outbox`].
    id: u64,
    /// Active execution phase of this driving invocation.
    phase: StPhase,
    /// Deferred next step to be initiated after an antecedent suspended stage finishes.
    next: Option<NewStep>,
    /// Accumulated ZooKeeper return code for the invocation.
    rc: i32,
    /// Operating system errno captured during execution.
    errno: i32,
    /// Indicates whether an application callback unwound across the call boundary.
    abandoned: bool,
}

/// single threaded driver state encapsulating I/O reactor execution and lifecycle transitions.
pub(crate) struct StIo {
    /// Network transport state machine and step continuation context.
    pub(crate) io: IoCore,
    /// Current progression of connection shutdown.
    pub(crate) close: StClose,
    /// Return code resulting from the most recently completed driving step.
    pub(crate) last_rc: i32,
    /// Operating system errno recorded during the last driving step.
    errno: i32,
    /// Stack of active driving frames; the tail represents the innermost nested call.
    frames: Vec<Frame>,
    /// Monotonic serial counter allocating unique identifiers for call frames.
    serial: u64,
    /// Active suspended step waiting at a barrier, if any.
    stage: Option<ResumeAt>,
    /// Indicates that a nested close added items to the local FIFO requiring outbox delivery.
    refill: bool,
    /// Indicates whether a terminal `CLOSE_OP` protocol frame requires network flushing.
    flush: bool,
    /// Return code resulting from close flushing and transport teardown.
    close_rc: i32,
    /// Identifies the driving call responsible for executing final close in its epilog.
    owner: Option<ResumeAt>,
    /// Set to `true` once all underlying transport resources have been released.
    released: bool,
}

impl StIo {
    /// Initializes default single threaded I/O state for a newly constructed handle.
    fn new() -> Self {
        Self {
            io: IoCore::new(),
            close: StClose::Idle,
            last_rc: ZOK,
            errno: 0,
            frames: Vec::new(),
            serial: 0,
            stage: None,
            refill: false,
            flush: false,
            close_rc: ZOK,
            owner: None,
            released: false,
        }
    }

    /// Returns a mutable reference to the innermost active driving frame, if any.
    fn top(&mut self) -> Option<&mut Frame> {
        self.frames.last_mut()
    }
}

/// Intermediate sink collecting FIFO-bound completions during a nested close invocation.
///
/// Prevents asynchronous completions from mixing with inline direct results before the
/// outer driving step has completed its active dispatch pass.
struct StCollect<T: DriverTokens> {
    items: Mutex<Vec<Item<T>>>,
}

impl<T: DriverTokens> Publish<T> for StCollect<T> {
    fn publish(&self, item: Item<T>) {
        self.items.lock_owner().push(item);
    }
}

/// Single threaded caller polled driver handle implementing the `libzookeeper_st` runtime
/// contract.
pub struct Polled<T: DriverTokens> {
    pub(crate) io: Mutex<StIo>,
    pub(crate) core: Mutex<Core<T>>,
    pub(crate) life: Lifecycle,
    pub(crate) env: Env<T>,
}

impl<T: DriverTokens> Polled<T> {
    /// Constructs a new polled driver instance from an initialized core and environment.
    pub(crate) fn new(core: Core<T>, env: Env<T>) -> Self {
        Self {
            io: Mutex::new(StIo::new()),
            core: Mutex::new(core),
            life: Lifecycle::new(),
            env,
        }
    }

    /// Constructs a polled driver handle from a prepared [`crate::init::Init`] state.
    #[must_use]
    pub fn from_init(init: crate::init::Init<T>) -> Self {
        let (core, env) = init.into_parts();
        Self::new(core, env)
    }

    /// Creates a fresh [`Outbox`] instance for an individual driving call.
    #[must_use]
    pub fn outbox(&self) -> Outbox<T> {
        Outbox::new()
    }

    /// Computes active network descriptor polling interest and timeout deadlines.
    ///
    /// Executes the interest calculation step with single threaded deferral policies. If an event
    /// barrier is encountered, yields [`Step::More`] and records `ResumeAt::Interest`. Output
    /// parameters are populated only when the interest calculation reaches final completion.
    pub fn interest(&self, ob: &mut Outbox<T>, out: &mut InterestOut, rc: &mut i32) -> Step {
        let mut st = self.io.lock_owner();
        self.st_begin(&mut st, NewStep::Interest, ob, Some(out), rc)
    }

    /// Processes pending network socket events and drives protocol I/O.
    ///
    /// Decodes incoming read/write event bitmasks matching legacy C constants. Ingests available
    /// socket data, executes protocol state-machine transitions, and stages generated completions.
    pub fn process(&self, events: u32, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let mut st = self.io.lock_owner();
        self.st_begin(&mut st, NewStep::Process(events), ob, None, rc)
    }

    /// Resumes an interrupted driving step that previously paused at an event barrier.
    ///
    /// Correlates the supplied [`Outbox`] frame identity with the corresponding execution frame.
    /// Unwinds abandoned frames if preceding application callbacks terminated abnormally.
    pub fn resume(&self, ob: &mut Outbox<T>, out: &mut InterestOut, rc: &mut i32) -> Step {
        // Dedicated ensemble update routines maintain their own execution stage;
        // standard driving steps resolve suspended state via the active call frame.
        let tag = ob.take_resume();
        if let Some(ResumeAt::SetServers(id)) = tag {
            return self.set_servers_resume(id, ob, rc);
        }
        let mut st = self.io.lock_owner();
        if st.released {
            *rc = Self::st_release_rc(&st);
            return Step::Release;
        }
        match st.close {
            StClose::Flush => return self.st_final_flush(&mut st, ob, Some(out), rc),
            StClose::Release => return self.st_final_release(&mut st, ob, Some(out), rc),
            StClose::Idle | StClose::Deferred => {}
        }
        let found = match ob.frame() {
            Some(id) => st.frames.iter().position(|f| f.id == id),
            None => st.frames.len().checked_sub(1),
        };
        let Some(index) = found else {
            // No active call frame correlates with the supplied outbox.
            *rc = st.last_rc;
            return Step::Done(st.last_rc);
        };
        self.st_pop_from(&mut st, index.saturating_add(1));
        let fallback = match tag {
            Some(at @ (ResumeAt::Interest | ResumeAt::Process)) => at,
            _ => ResumeAt::Process,
        };
        let phase = st.top().map_or(StPhase::Idle, |f| f.phase);
        if st.refill {
            st.refill = false;
            let mut proof = Unlocked::new_at_entry();
            let mut core = self.core.lock(&mut proof);
            if !core.local.is_empty() {
                ob.extend_items(core.local.drain(..));
                let at = match phase {
                    StPhase::Step(at) | StPhase::Drain(at) => at,
                    StPhase::Idle => fallback,
                };
                ob.set_resume(at);
                return Step::More;
            }
        }
        match phase {
            StPhase::Step(at) if st.io.is_suspended() => {
                let mut proof = Unlocked::new_at_entry();
                let cx = self.st_ctx();
                let p = st.io.resume(&cx, &mut proof, ob, out);
                self.st_after_step(&mut st, at, p, ob, Some(out), rc)
            }
            StPhase::Step(at) | StPhase::Drain(at) => {
                self.st_epilog(&mut st, at, ob, Some(out), rc)
            }
            StPhase::Idle => self.st_epilog(&mut st, fallback, ob, Some(out), rc),
        }
    }

    /// Returns the operating system errno recorded by the most recent driving step.
    ///
    /// Used by the C FFI shim to update the thread-local `errno` following library calls.
    #[must_use]
    pub fn last_errno(&self) -> i32 {
        self.io.lock_owner().errno
    }

    /// Submits a request to the ZooKeeper cluster and performs a nonblocking transmission flush.
    ///
    /// If socket transmission fails fatally, initiates a forced transport close and stages
    /// failure notifications into the provided outbox.
    ///
    /// # Errors
    ///
    /// Returns [`SubmitError`] if request validation or queueing fails.
    pub fn submit(
        &self,
        req: Request<T>,
        ob: &mut Outbox<T>,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_with_data(req, None, ob)
    }

    /// Submits a request with externally borrowed payload data, flushing pending bytes.
    ///
    /// # Errors
    ///
    /// Returns [`SubmitError`] if request validation or queueing fails.
    pub fn submit_with_data(
        &self,
        req: Request<T>,
        data: Option<&[u8]>,
        ob: &mut Outbox<T>,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_by(req, ob, |c, r, now| c.submit_with_data(r, data, now))
    }

    /// Submits a multi-operation transaction with borrowed sub-operations, flushing pending bytes.
    ///
    /// # Errors
    ///
    /// Returns [`SubmitError`] if request validation or queueing fails.
    pub fn submit_multi_ref(
        &self,
        req: Request<T>,
        ops: &[MultiOpRef<'_>],
        ob: &mut Outbox<T>,
    ) -> Result<Submitted<T>, SubmitError<T>> {
        self.submit_by(req, ob, |c, r, now| c.submit_multi_ref(r, ops, now))
    }

    /// Common submission helper routing requests under the core lock and driving subsequent
    /// flushes.
    fn submit_by(
        &self,
        req: Request<T>,
        ob: &mut Outbox<T>,
        send: impl FnOnce(&mut Connection<T>, Request<T>, Millis) -> SubmitResult<T>,
    ) -> SubmitResult<T> {
        let mut st = self.io.lock_owner();
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        let res = {
            let mut core = self.core.lock(&mut proof);
            let Some(conn) = core.conn.as_deref_mut() else {
                return Err(SubmitError {
                    stage: Stage::Validate,
                    code: ZINVALIDSTATE,
                    token: req.token,
                });
            };
            let res = send(conn, req, now);
            core.pump(Self::FLAVOR, ob, &*self.env.host);
            res
        };
        // Transmit buffers are flushed for all requests reaching the serialization phase.
        let flush = match &res {
            Ok(Submitted::Queued { .. }) => true,
            Ok(Submitted::Local(_)) => false,
            Err(e) => e.stage == Stage::Enqueue,
        };
        if flush && st.io.has_socket() {
            let cx = self.st_ctx();
            if !st.io.flush_once(&cx, &mut proof) {
                self.st_forced_close(&mut st, &mut proof, ob, now);
            }
        }
        res
    }

    /// Registers an authentication credential with the active session.
    ///
    /// # Errors
    ///
    /// Returns [`AddAuthError`] if the authentication payload cannot be queued.
    pub fn add_auth(
        &self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<T::Auth>,
    ) -> Result<(), AddAuthError<T>> {
        let now = self.env.clock.now();
        self.core.lock(&mut Unlocked::new_at_entry()).add_auth_or(
            scheme,
            cred,
            done,
            now,
            ZINVALIDSTATE,
        )
    }

    /// Updates the server ensemble list dynamically.
    ///
    /// Parses connection strings, resolves endpoint addresses, and reconfigures the server list.
    /// Triggers a forced reconnect if the currently connected server is removed from the ensemble.
    pub fn set_servers(&self, hosts: &[u8], ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let host = &*self.env.host;
        let parsed = match parse_servers(hosts) {
            Ok(parsed) => parsed,
            Err(e) => {
                // Parse failures leave ensemble configuration unchanged; log errors for invalid
                // ports.
                let site = match e.kind {
                    HostParseErrorKind::NoPort => Some(LogSite::ResolveNoPort),
                    HostParseErrorKind::InvalidPort => Some(LogSite::ResolveBadPort),
                    HostParseErrorKind::Empty
                    | HostParseErrorKind::InvalidChroot
                    | HostParseErrorKind::NoHosts => None,
                };
                if let Some(site) = site {
                    let args = vec![LogArg::Str(JString::Bytes(e.subject))];
                    if let Some(rec) = log_record(host, site, args) {
                        ob.push(Item::Log(rec));
                    }
                }
                *rc = e.rc;
                return Step::Done(e.rc);
            }
        };
        let specs: Vec<HostSpec> = parsed.into_iter().map(HostSpec::Legacy).collect();
        let mut st = self.io.lock_owner();
        let cx = self.env.resolve_ctx(&self.core, Self::FLAVOR);
        let mut proof = Unlocked::new_at_entry();
        let end = SetServersRun::start(&cx, &mut proof, ob, specs);
        self.set_servers_end(&mut st, end, ob, rc)
    }

    /// Resumes an in-flight server ensemble update sequence.
    fn set_servers_resume(&self, id: u64, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let mut st = self.io.lock_owner();
        let cx = self.env.resolve_ctx(&self.core, Self::FLAVOR);
        let mut proof = Unlocked::new_at_entry();
        let end = SetServersRun::resume(&cx, &mut proof, ob, id);
        self.set_servers_end(&mut st, end, ob, rc)
    }

    /// Finalizes an ensemble update phase, dropping active transports if connection migration is
    /// needed.
    fn set_servers_end(
        &self,
        st: &mut StIo,
        end: SetServersEnd,
        ob: &mut Outbox<T>,
        rc: &mut i32,
    ) -> Step {
        match end {
            SetServersEnd::More(id) => {
                ob.set_resume(ResumeAt::SetServers(id));
                Step::More
            }
            SetServersEnd::Done { rc: code, lost } => {
                if lost {
                    let mut proof = Unlocked::new_at_entry();
                    self.core.lock(&mut proof).peer = None;
                    st.io.drop_transport();
                }
                *rc = code;
                Step::Done(code)
            }
        }
    }

    /// Terminates the client session gracefully.
    ///
    /// Invocations originating from within an active callback defer final teardown until the outer
    /// driving call reaches its epilog. Direct top-level invocations initiate immediate shutdown,
    /// transmitting close notifications, tearing down transports, and releasing resources.
    pub fn close(&self, ob: &mut Outbox<T>, rc: &mut i32) -> Step {
        let mut st = self.io.lock_owner();
        // Snapshot active reference count prior to public state changes. Reclaim
        // references associated with any previously abandoned frames.
        let refs = self.life.prolog();
        let refs = refs.saturating_sub(self.st_pop_abandoned(&mut st));
        if !self.life.request_close() {
            let last = self.life.epilog();
            // When all active driving frames unwind without return, initiate final teardown
            // immediately.
            if last && st.close == StClose::Deferred && self.life.begin_final() {
                st.owner = None;
                return self.st_final_start(&mut st, ob, None, rc);
            }
            // Re-entrant close invocation resuming an orphaned final close sequence.
            let orphan = {
                let mut proof = Unlocked::new_at_entry();
                let mut core = self.core.lock(&mut proof);
                let at = core.orphan_close.take();
                if at.is_some() {
                    let _ = core.restore_requeued(ob);
                }
                at
            };
            if orphan.is_some() {
                match st.close {
                    StClose::Flush => return self.st_final_flush(&mut st, ob, None, rc),
                    StClose::Release => return self.st_final_release(&mut st, ob, None, rc),
                    StClose::Idle | StClose::Deferred => {}
                }
            }
            // Redundant close invocation on an already closed handle; return success.
            *rc = ZOK;
            return Step::Done(ZOK);
        }
        if refs > 1 {
            self.st_close_nested(&mut st, ob);
            st.close = StClose::Deferred;
            let _ = self.life.epilog();
            *rc = ZOK;
            return Step::Done(ZOK);
        }
        if !self.life.begin_final() {
            *rc = ZOK;
            return Step::Done(ZOK);
        }
        st.owner = None;
        self.st_final_start(&mut st, ob, None, rc)
    }

    /// Blocks the current thread awaiting socket readiness up to `timeout_ms` milliseconds.
    ///
    /// Polls socket readiness flags directly for testing harnesses and external event-loop
    /// simulators.
    #[must_use]
    pub fn wait_events(&self, out: &InterestOut, timeout_ms: i32) -> u32 {
        let st = self.io.lock_owner();
        let Some(sock) = st.io.conn.sock() else {
            return 0;
        };
        let flags = request_flags(PollEvents::from_bits(out.bits));
        let mut fds = [PollFd::new(sock, flags)];
        match self.env.poll.poll(&mut fds, timeout_ms) {
            Ok(_) => fds.first().map_or(0, |fd| map_revents(fd.revents()).bits()),
            Err(_) => 0,
        }
    }

    /// Ingests the result of an external Cyrus SASL negotiation step.
    pub fn sasl_result(&self, answer: SaslAnswer) {
        let mut proof = Unlocked::new_at_entry();
        self.core.lock(&mut proof).sasl = Some(answer);
    }

    /// Requeues unconsumed outbox items following an interrupted callback execution.
    pub fn requeue(&self, rest: Requeued<T>) {
        let mut st = self.io.lock_owner();
        // Mark caller frame abandoned following unwinding to trigger lifecycle reference recovery.
        if let Some(id) = rest.frame() {
            if let Some(f) = st.frames.iter_mut().find(|f| f.id == id) {
                f.abandoned = true;
            }
        }
        drop(st);
        let mut proof = Unlocked::new_at_entry();
        let mut core = self.core.lock(&mut proof);
        match rest.resume_at() {
            Some(ResumeAt::FinalClose) => core.orphan_close = Some(ResumeAt::FinalClose),
            Some(ResumeAt::SetServers(id)) => adopt_set_servers(&mut core, id),
            _ => {}
        }
        if rest.is_empty() {
            return;
        }
        let merged = match core.requeued.take() {
            // Preserve chronological delivery order by placing earlier items ahead of newly
            // requeued items.
            Some(older) => {
                let mut ob = Outbox::new();
                ob.restore(rest);
                ob.restore(older);
                ob.requeue()
            }
            None => rest,
        };
        core.requeued = Some(merged);
    }

    /// Returns the remote peer socket address of the active connection.
    #[must_use]
    pub fn peer_addr(&self) -> Option<SocketAddr> {
        let mut proof = Unlocked::new_at_entry();
        // Peer address queries succeed only while the session is in `State::Connected` (state 3).
        let connected =
            read_conn(&self.core, &mut proof, |c| c.state() == State::Connected).unwrap_or(false);
        if !connected {
            return None;
        }
        match self.io.try_lock() {
            Some(st) => st.io.conn.sock().and_then(|s| s.peer_addr().ok()),
            None => self.core.lock(&mut proof).peer,
        }
    }

    /// Returns the current numeric ZooKeeper session state.
    #[must_use]
    pub fn state(&self) -> i32 {
        read_at_entry(&self.core, |c| c.state().code()).unwrap_or(0)
    }

    /// Returns the negotiated session receive timeout in milliseconds.
    #[must_use]
    pub fn recv_timeout(&self) -> i32 {
        read_at_entry(&self.core, Connection::recv_timeout).unwrap_or(0)
    }

    /// Returns the active client identifier and session credentials.
    #[must_use]
    pub fn client_id(&self) -> ClientId {
        read_at_entry(&self.core, Connection::client_id).unwrap_or_default()
    }

    /// Executes a read-only inspection closure over the active connection under the core lock.
    pub fn read_conn<R>(&self, f: impl FnOnce(&Connection<T>) -> R) -> Option<R> {
        read_at_entry(&self.core, f)
    }

    /// Executes a mutable operation closure over the active connection under the core lock.
    pub fn with_conn<R>(&self, f: impl FnOnce(&mut Connection<T>, Millis) -> R) -> Option<R> {
        with_conn_at_entry(&self.core, self.env.clock.now(), f)
    }

    /// Deferral policy for single threaded driver execution: execute blocking steps inline.
    pub(crate) const DEFER: DeferPolicy = DeferPolicy::NONE;

    /// Active outbox routing flavor for single threaded mode.
    pub(crate) const FLAVOR: Flavor = Flavor::St;

    /// Constructs execution step context parameters for driver iterations.
    fn st_ctx(&self) -> StepCtx<'_, T> {
        self.env.step_ctx(
            &self.core,
            Self::FLAVOR,
            Self::DEFER,
            None,
            Some(self.life.close_flag()),
            None,
        )
    }

    /// Initializes state context for an incoming driving call, managing activation frames and
    /// refs.
    fn st_enter(
        &self,
        st: &mut StIo,
        ob: &mut Outbox<T>,
        rc: &mut i32,
    ) -> Result<Option<ResumeAt>, Step> {
        let mut proof = Unlocked::new_at_entry();
        let finished = matches!(st.close, StClose::Flush | StClose::Release) || st.released;
        if finished || read_conn(&self.core, &mut proof, |_| ()).is_none() {
            *rc = ZINVALIDSTATE;
            return Err(Step::Done(ZINVALIDSTATE));
        }
        self.life.prolog();
        let _ = self.st_pop_abandoned(st);
        let stopped = if st.io.is_suspended() { st.stage } else { None };
        if st.frames.is_empty() {
            st.refill = false;
        }
        let id = st.serial;
        st.serial = st.serial.wrapping_add(1);
        st.frames.push(Frame {
            id,
            phase: StPhase::Idle,
            next: None,
            rc: ZOK,
            errno: 0,
            abandoned: false,
        });
        ob.set_frame(id);
        Ok(stopped)
    }

    /// Unwinds abandoned call frames from the reentrant stack, returning the count of released
    /// refs.
    fn st_pop_abandoned(&self, st: &mut StIo) -> u32 {
        match st.frames.iter().position(|f| f.abandoned) {
            Some(index) => self.st_pop_from(st, index),
            None => 0,
        }
    }

    /// Pops call frames from the specified index to the top of the stack, invoking epilog hooks.
    fn st_pop_from(&self, st: &mut StIo, index: usize) -> u32 {
        let mut count = 0_u32;
        while st.frames.len() > index {
            st.frames.pop();
            let _ = self.life.epilog();
            count = count.saturating_add(1);
        }
        count
    }

    /// Begins a driving step (`interest` or `process`), pushing a new activation [`Frame`] and
    /// completing any prior suspended [`IoCore`] stage before starting the newly requested step.
    ///
    /// When a user callback invoked during an outbox barrier (while `st.io.is_suspended()` is
    /// true) reentrantly calls `zookeeper_interest` or `zookeeper_process`, `IoCore` cannot start a
    /// brand-new step while its previous continuation (`IoCore::cont`) is still mid-flight.
    /// Instead, `st_begin` stashes the requested `next` operation in the new frame's `top.next`
    /// slot, marks the frame as `StPhase::Step(at)` for the *already-suspended* stage `at`, and
    /// invokes `st.io.resume` to finish that antecedent stage first. Once the antecedent stage
    /// completes and its outbox items drain, `st_epilog` extracts `top.next` and runs `st_start`
    /// for the nested call.
    fn st_begin(
        &self,
        st: &mut StIo,
        next: NewStep,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let stopped = match self.st_enter(st, ob, rc) {
            Ok(stopped) => stopped,
            Err(step) => return step,
        };
        let Some(at) = stopped else {
            return self.st_start(st, next, ob, out, rc);
        };
        if let Some(top) = st.top() {
            top.next = Some(next);
            top.phase = StPhase::Step(at);
        }
        let mut scratch = InterestOut::UNWRITTEN;
        let o = match out {
            Some(o) => o,
            None => &mut scratch,
        };
        let mut proof = Unlocked::new_at_entry();
        let cx = self.st_ctx();
        let p = st.io.resume(&cx, &mut proof, ob, o);
        self.st_after_step(st, at, p, ob, Some(o), rc)
    }

    /// Dispatches the primary [`IoCore`] execution logic for an `Interest` or `Process` driving
    /// step after clearing any stale `errno` hints from previous turns.
    fn st_start(
        &self,
        st: &mut StIo,
        next: NewStep,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let mut proof = Unlocked::new_at_entry();
        {
            let mut core = self.core.lock(&mut proof);
            if let Some(conn) = core.conn.as_deref_mut() {
                let _ = conn.take_errno_hint();
            }
        }
        let _ = st.io.take_errno();
        if let Some(top) = st.top() {
            top.errno = 0;
        }
        let cx = self.st_ctx();
        match next {
            NewStep::Interest => {
                let mut scratch = InterestOut::UNWRITTEN;
                let o = match out {
                    Some(o) => o,
                    None => &mut scratch,
                };
                let p = st.io.interest_step(&cx, &mut proof, ob, o);
                self.st_after_step(st, ResumeAt::Interest, p, ob, Some(o), rc)
            }
            NewStep::Process(events) => {
                let p = st
                    .io
                    .process_step(&cx, &mut proof, PollEvents::from_bits(events), ob);
                self.st_after_step(st, ResumeAt::Process, p, ob, None, rc)
            }
        }
    }

    /// Post-processing handler invoked after an [`IoCore`] step (`interest_step`, `process_step`,
    /// or `resume`) either completes (`Progress::Done`) or yields at a barrier (`Progress::More`).
    ///
    /// Coordination invariants:
    /// - **`Progress::More`**: Records the active stage in `st.stage` and the top frame's phase,
    ///   tags `ob` with `ResumeAt`, and returns [`Step::More`] while retaining the frame's
    ///   lifecycle reference so any nested `close` inside a callback sees `refs > 1`.
    /// - **`Progress::Done(done)`**: Clears `st.stage`, captures any diagnostic `errno` on failure,
    ///   and updates *all* outer frames on `st.frames` that were waiting on `StPhase::Step(a)` to
    ///   `StPhase::Drain(a)` with the completed `rc` and `errno`.
    /// - **Process Completion Drain**: When finishing `ResumeAt::Process`, drains all buffered
    ///   asynchronous completions and watch events from `Core::local` into `ob`, mirroring
    ///   `process_completions` at the end of C `zookeeper_process`.
    /// - **Drain vs. Epilog**: If `ob` is non-empty, transitions the top frame to
    ///   `StPhase::Drain(at)` and returns [`Step::More`] so the caller invokes the callbacks before
    ///   re-entering `resume` to run `st_epilog`.
    fn st_after_step(
        &self,
        st: &mut StIo,
        at: ResumeAt,
        progress: Progress,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let done = match progress {
            Progress::More => {
                st.stage = Some(at);
                if let Some(top) = st.top() {
                    top.phase = StPhase::Step(at);
                }
                ob.set_resume(at);
                return Step::More;
            }
            Progress::Done(r) => r,
            // single threaded drop-in driver never defers background jobs.
            Progress::Wait => ZSYSTEMERROR,
        };
        st.stage = None;
        let errno = if done == ZOK {
            0
        } else {
            self.st_failure_errno(st)
        };
        let waiting = st.frames.len().saturating_sub(1);
        for f in st.frames.iter_mut().take(waiting) {
            if let StPhase::Step(a) = f.phase {
                f.phase = StPhase::Drain(a);
                f.rc = done;
                f.errno = errno;
            }
        }
        if let Some(top) = st.top() {
            top.rc = done;
            if done != ZOK {
                top.errno = errno;
            }
        }
        *rc = done;
        if at == ResumeAt::Process {
            // Finalization of zookeeper_process serves as a protocol completion drain point.
            let mut proof = Unlocked::new_at_entry();
            let mut core = self.core.lock(&mut proof);
            ob.extend_items(core.local.drain(..));
        }
        if !ob.is_empty() {
            if let Some(top) = st.top() {
                top.phase = StPhase::Drain(at);
            }
            ob.set_resume(at);
            return Step::More;
        }
        self.st_epilog(st, at, ob, out, rc)
    }

    /// Resolves the diagnostic error number for a failed step from protocol hints or socket state.
    fn st_failure_errno(&self, st: &mut StIo) -> i32 {
        let mut proof = Unlocked::new_at_entry();
        let hint = {
            let mut core = self.core.lock(&mut proof);
            core.conn
                .as_deref_mut()
                .and_then(zk_proto::Connection::take_errno_hint)
        };
        let io = st.io.take_errno();
        hint.or(io).unwrap_or(0)
    }

    /// Executes invocation epilog logic for the top call frame on `st.frames`, either advancing to
    /// a deferred `top.next` step or popping the frame and triggering deferred final teardown when
    /// the last active frame exits.
    ///
    /// If `st_begin` previously had to finish a suspended antecedent stage before starting the
    /// caller's requested `NewStep`, `top.next` holds that pending request; `st_epilog` takes it
    /// and invokes `st_start` within the same frame. Otherwise, the frame is popped, its `rc` and
    /// `errno` are recorded in `st.last_rc` / `st.errno`, and `self.life.epilog()` decrements the
    /// active reference count. If this was the outermost active call (`last == true`) and a nested
    /// callback previously requested `zookeeper_close`, `self.life.begin_final()` claims the
    /// one-time final teardown transition, records `st.owner = Some(at)` so `st_final_release`
    /// knows to return the outer call's `st.last_rc` (and zero out `InterestOut` if the outer call
    /// was `interest`), and enters `st_final_start`.
    fn st_epilog(
        &self,
        st: &mut StIo,
        at: ResumeAt,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let next = st.top().and_then(|top| {
            top.phase = StPhase::Idle;
            top.next.take()
        });
        if let Some(next) = next {
            // Prior suspended stage has resolved; advance to the deferred next step.
            return self.st_start(st, next, ob, out, rc);
        }
        let mut last = false;
        if let Some(frame) = st.frames.pop() {
            st.last_rc = frame.rc;
            st.errno = frame.errno;
            last = self.life.epilog();
        }
        *rc = st.last_rc;
        if last && self.life.begin_final() {
            st.owner = Some(at);
            return self.st_final_start(st, ob, out, rc);
        }
        Step::Done(st.last_rc)
    }

    /// Handles session closure requested reentrantly from within an active application callback
    /// (`refs > 1`).
    ///
    /// In legacy C `zookeeper_close`, a nested close calls `adaptor_finish` (which in `st_adaptor`
    /// is a no-op that leaves the outer call's `IoCore` continuation intact) and synchronously
    /// drains auth completions while queuing request/watch cancellations for the outer call. To
    /// preserve the outer call's suspended `Core::stopped` stage without clobbering it,
    /// `st_close_nested` temporarily saves `core.stopped`, runs `conn.begin_close(now)`, and pumps
    /// the resulting close-cancellation items using `Flavor::Mt` into a temporary collector
    /// `StCollect`. Using `Flavor::Mt` here routes `Item::Log` and `Item::Auth` directly into the
    /// nested `close` caller's `ob` (for immediate inline delivery before `zookeeper_close`
    /// returns `ZOK`), while diverting `Item::Completion` and `Item::Watch` into `StCollect`
    /// (`core.local`) and setting `st.refill = true` so the outer driving frame drains them as
    /// soon as the current callback returns into `resume`.
    fn st_close_nested(&self, st: &mut StIo, ob: &mut Outbox<T>) {
        let host = &*self.env.host;
        let now = self.env.clock.now();
        let collect = Arc::new(StCollect {
            items: Mutex::new(Vec::new()),
        });
        let publish: Arc<dyn Publish<T>> = collect.clone();
        let mut tmp = Outbox::with_publish(publish);
        let mut proof = Unlocked::new_at_entry();
        let mut core = self.core.lock(&mut proof);
        let outer = core.stopped.take();
        if let Some(conn) = core.conn.as_deref_mut() {
            conn.begin_close(now);
        }
        let mut passes = 0;
        while passes < PUMP_PASSES {
            let barrier = core.pump(Flavor::Mt, &mut tmp, host);
            while let Some(item) = tmp.pop() {
                ob.push(item);
            }
            if core.stopped.is_some() {
                passes = passes.saturating_add(1);
                core.resume_stopped(now);
            } else if !barrier {
                break;
            }
        }
        core.stopped = outer;
        let items = std::mem::take(&mut *collect.items.lock_owner());
        if !items.is_empty() {
            core.local.extend(items);
            st.refill = true;
        }
    }

    /// Executes emergency connection teardown following an unrecoverable socket write failure
    /// during `submit_by`.
    fn st_forced_close(
        &self,
        st: &mut StIo,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        now: Millis,
    ) {
        let mut core = self.core.lock(proof);
        if let Some(conn) = core.conn.as_deref_mut() {
            conn.handle_forced_close(now);
        }
        core.pump(Self::FLAVOR, ob, &*self.env.host);
        core.peer = None;
        drop(core);
        st.io.drop_transport();
    }

    /// Initiates phase 1 (`StClose::Flush`) of final connection teardown: restores any requeued
    /// items, queues `CLOSE_OP` on the protocol engine if a live socket exists, and stages the
    /// `LogSite::Closing` diagnostic record.
    ///
    /// If `ob` contains items (e.g., the `Closing` log record or restored items), yields
    /// [`Step::More`] with `ResumeAt::FinalClose` so the C shim delivers those items before
    /// `st_final_flush` performs the synchronous close flush on the socket.
    fn st_final_start(
        &self,
        st: &mut StIo,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let host = &*self.env.host;
        let mut proof = Unlocked::new_at_entry();
        {
            let mut core = self.core.lock(&mut proof);
            // Drain items preserved from previous interrupted invocations first.
            let _ = core.restore_requeued(ob);
            let mut flush = false;
            if let Some(conn) = core.conn.as_deref_mut() {
                flush = conn.queue_close_op() && st.io.has_socket();
                if flush {
                    let id = session_ptr(conn.client_id().session_id);
                    let args = vec![LogArg::Ptr(id), LogArg::Endpoint(conn.current_server())];
                    if let Some(rec) = log_record(host, LogSite::Closing, args) {
                        ob.push(Item::Log(rec));
                    }
                }
            }
            st.flush = flush;
        }
        st.close = StClose::Flush;
        if !ob.is_empty() {
            ob.set_resume(ResumeAt::FinalClose);
            return Step::More;
        }
        self.st_final_flush(st, ob, out, rc)
    }

    /// Executes phase 2 (`StClose::Release`) of final connection teardown: flushes the queued
    /// `CLOSE_OP` to the wire via [`IoCore::close_flush`], logs `LogSite::Freeing`, invokes
    /// `conn.destroy(now)`, and drains all resulting completion/watch cancellations into `ob`.
    ///
    /// Outputs generated during session destruction follow the legacy C ordering: authentication
    /// completions are yielded first via `core.pump(Flavor::St, ...)`, followed by closing
    /// cancellation results drained from `core.local`. If `ob` is non-empty, yields [`Step::More`]
    /// with `ResumeAt::FinalClose` so user completion callbacks run before `st_final_release`
    /// returns [`Step::Release`] to free the handle.
    fn st_final_flush(
        &self,
        st: &mut StIo,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let host = &*self.env.host;
        let mut proof = Unlocked::new_at_entry();
        st.close_rc = if st.flush {
            let cx = self.st_ctx();
            st.io.close_flush(&cx, &mut proof, ob)
        } else {
            ZOK
        };
        let now = self.env.clock.now();
        {
            let mut core = self.core.lock(&mut proof);
            // Flush earlier diagnostic output before logging destruction message.
            let _ = core.pump(Self::FLAVOR, ob, host);
            if let Some(conn) = core.conn.as_deref_mut() {
                let id = session_ptr(conn.client_id().session_id);
                if let Some(rec) = log_record(host, LogSite::Freeing, vec![LogArg::Ptr(id)]) {
                    ob.push(Item::Log(rec));
                }
                conn.destroy(now);
            }
            // Connection destruction terminates any remaining suspended stages.
            core.stopped = None;
            for _ in 0..PUMP_PASSES {
                if !core.pump(Self::FLAVOR, ob, host) {
                    break;
                }
            }
            ob.extend_items(core.local.drain(..));
        }
        st.close = StClose::Release;
        if !ob.is_empty() {
            ob.set_resume(ResumeAt::FinalClose);
            return Step::More;
        }
        self.st_final_release(st, ob, out, rc)
    }

    /// Executes phase 3 (terminal phase) of final connection teardown: extracts `core.conn`, drains
    /// any terminal `CloseTransport` or `Sasl::Dispose` actions, discards any unconsumed tokens via
    /// [`HostLeaves`], drops the transport socket, and returns [`Step::Release`].
    ///
    /// When final teardown was triggered at the epilog of an outer `interest` call that contained a
    /// nested `close` (`st.owner == Some(ResumeAt::Interest)`), any `InterestOut` descriptor that
    /// was populated earlier in the turn is invalidated (`fd = -1, bits = 0, timeout_ms = 0`)
    /// because the socket has now been closed and the handle is about to be freed.
    fn st_final_release(
        &self,
        st: &mut StIo,
        ob: &mut Outbox<T>,
        out: Option<&mut InterestOut>,
        rc: &mut i32,
    ) -> Step {
        let host = &*self.env.host;
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        let (conn, local, requeued) = {
            let mut core = self.core.lock(&mut proof);
            let _ = core.pump(Self::FLAVOR, ob, host);
            core.peer = None;
            core.stopped = None;
            core.sasl = None;
            let local: Vec<Item<T>> = core.local.drain(..).collect();
            (core.conn.take(), local, core.requeued.take())
        };
        if let Some(mut conn) = conn {
            for _ in 0..RELEASE_ACTIONS {
                match conn.poll_action(now) {
                    Some(Action::CloseTransport) => st.io.drop_transport(),
                    Some(Action::Sasl(call)) => ob.push(Item::Sasl(SaslItem { call, addrs: None })),
                    Some(
                        Action::Resolve { .. } | Action::Connect { .. } | Action::ProbeRw { .. },
                    ) => {}
                    None => break,
                }
            }
            let _ = pump(&mut conn, ob, FifoBound::Inline, host);
            discard_leftovers(host, conn);
        }
        for item in local {
            discard_item(host, item);
        }
        if let Some(r) = requeued {
            let mut tmp = Outbox::new();
            tmp.restore(r);
            tmp.finish(host);
        }
        st.io.drop_transport();
        st.released = true;
        *rc = Self::st_release_rc(st);
        if st.owner == Some(ResumeAt::Interest) {
            // Socket descriptor is invalidated; zero out interest output parameters.
            if let Some(o) = out {
                if o.written {
                    o.fd = -1;
                    o.bits = 0;
                    o.timeout_ms = 0;
                }
            }
        }
        Step::Release
    }

    /// Computes the terminal return code for the release step.
    fn st_release_rc(st: &StIo) -> i32 {
        match st.owner {
            Some(_) => st.last_rc,
            None => st.close_rc,
        }
    }
}

/// Formats a session identifier as a hexadecimal pointer representation.
fn session_ptr(id: i64) -> u64 {
    u64::from_ne_bytes(id.to_ne_bytes())
}

#[cfg(test)]
mod tests {
    #![allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing
    )]

    use super::*;
    use crate::host::HostLeaves;
    use crate::testutil::{TestHost, exists_request, test_connection};
    use zk_proto::rc::{ZBADARGUMENTS, ZCLOSING, ZNOTHING};
    use zk_proto::{DriverMode, LogLevel, PathIn, SaslError};
    use zk_testkit::tokens::{AuthId, TestTokens};

    fn polled(host: &Arc<TestHost>) -> Polled<TestTokens> {
        let core = Core::new(Box::new(test_connection(DriverMode::ST)));
        let leaves: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        Polled::new(core, Env::new(leaves))
    }

    fn exists(id: u64) -> Request<TestTokens> {
        exists_request(PathIn::Legacy(Some(b"/".to_vec())), id)
    }

    fn drain(ob: &mut Outbox<TestTokens>) -> Vec<Item<TestTokens>> {
        std::iter::from_fn(|| ob.pop()).collect()
    }

    /// Executes a driving step and all subsequent resumes until terminal completion.
    fn run(
        p: &Polled<TestTokens>,
        ob: &mut Outbox<TestTokens>,
        rc: &mut i32,
        mut step: Step,
    ) -> (Step, Vec<Item<TestTokens>>) {
        let mut items = Vec::new();
        let mut out = InterestOut::UNWRITTEN;
        while step == Step::More {
            items.extend(drain(ob));
            step = p.resume(ob, &mut out, rc);
        }
        items.extend(drain(ob));
        (step, items)
    }

    fn log_at(items: &[Item<TestTokens>], site: LogSite) -> Option<usize> {
        items
            .iter()
            .position(|i| matches!(i, Item::Log(r) if r.site == site))
    }

    fn result_of(items: &[Item<TestTokens>], id: u64) -> Option<(usize, i32)> {
        items.iter().enumerate().find_map(|(k, i)| match i {
            Item::Result { token, result, .. } if token.id() == id => Some((k, result.rc)),
            _ => None,
        })
    }

    #[test]
    fn submit_and_close_with_no_socket() {
        let host = Arc::new(TestHost::new());
        host.set_log_level(Some(LogLevel::Info));
        let p = polled(&host);
        let mut ob = p.outbox();
        assert!(matches!(
            p.submit(exists(1), &mut ob),
            Ok(Submitted::Queued { .. })
        ));
        assert!(
            p.add_auth(
                JString::from("digest"),
                SecretBytes::from(b"u:p".to_vec()),
                None
            )
            .is_ok()
        );
        assert_eq!(p.peer_addr(), None);
        assert_eq!(p.wait_events(&InterestOut::UNWRITTEN, 0), 0);

        let mut rc = 1;
        let mut ob = p.outbox();
        let first = p.close(&mut ob, &mut rc);
        let (step, items) = run(&p, &mut ob, &mut rc, first);
        assert_eq!(step, Step::Release);
        assert_eq!(rc, ZOK);
        // No active socket: suppress CLOSE_OP transmission and diagnostic logs.
        assert_eq!(log_at(&items, LogSite::Closing), None);
        let freeing = log_at(&items, LogSite::Freeing).expect("the Freeing line");
        let (at, code) = result_of(&items, 1).expect("the request result");
        assert_eq!(code, ZCLOSING);
        assert!(freeing < at);
        assert!(host.discarded().is_empty());

        // Connection handle is invalidated following final release.
        assert_eq!(p.state(), 0);
        let mut rc = 1;
        assert_eq!(p.process(0, &mut ob, &mut rc), Step::Done(ZINVALIDSTATE));
        let mut out = InterestOut::UNWRITTEN;
        assert_eq!(p.resume(&mut ob, &mut out, &mut rc), Step::Release);
        let Err(err) = p.submit(exists(2), &mut ob) else {
            panic!("no connection");
        };
        assert_eq!(
            (err.stage, err.code, err.token.id()),
            (Stage::Validate, ZINVALIDSTATE, 2)
        );
        assert!(
            p.add_auth(
                JString::from("digest"),
                SecretBytes::from(Vec::new()),
                Some(AuthId::new(3))
            )
            .is_err()
        );
    }

    /// Verifies that closing a session from within a callback defers teardown to the epilog.
    #[test]
    fn close_from_a_callback_defers_to_the_epilog() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        let mut ob = p.outbox();
        assert!(p.submit(exists(1), &mut ob).is_ok());

        // Simulate an interest step completing with pending callbacks.
        p.life.prolog();
        {
            let mut st = p.io.lock_owner();
            st.frames.push(Frame {
                id: 7,
                phase: StPhase::Drain(ResumeAt::Interest),
                next: None,
                rc: ZNOTHING,
                errno: 0,
                abandoned: false,
            });
        }

        // Callback initiates session closure.
        let mut cob = p.outbox();
        let mut crc = 1;
        assert_eq!(p.close(&mut cob, &mut crc), Step::Done(ZOK));
        assert_eq!(crc, ZOK);
        assert!(result_of(&drain(&mut cob), 1).is_none());
        assert_eq!(p.io.lock_owner().close, StClose::Deferred);
        assert_ne!(p.state(), 0);
        assert_eq!(p.life.refs(), 1);
        // Subsequent close attempts are ignored.
        assert_eq!(p.close(&mut cob, &mut crc), Step::Done(ZOK));
        assert_eq!(p.life.refs(), 1);

        // Outermost driving call delivers pending closing results and runs teardown in epilog.
        let mut ob = p.outbox();
        ob.set_frame(7);
        let mut rc = 1;
        let mut out = InterestOut::UNWRITTEN;
        let first = p.resume(&mut ob, &mut out, &mut rc);
        assert_eq!(first, Step::More);
        let (step, items) = run(&p, &mut ob, &mut rc, first);
        assert_eq!(result_of(&items, 1).map(|r| r.1), Some(ZCLOSING));
        assert_eq!(step, Step::Release);
        // Driving call retains its original return code.
        assert_eq!(rc, ZNOTHING);
        assert_eq!(p.state(), 0);
        assert_eq!(p.life.refs(), 0);
        assert!(host.discarded().is_empty());
    }

    /// Initializes an outer driving call paused in its drain phase.
    fn outer_call_in_drain(p: &Polled<TestTokens>) -> u64 {
        p.life.prolog();
        let mut st = p.io.lock_owner();
        let id = st.serial;
        st.serial = st.serial.wrapping_add(1);
        st.frames.push(Frame {
            id,
            phase: StPhase::Drain(ResumeAt::Process),
            next: None,
            rc: ZNOTHING,
            errno: 0,
            abandoned: false,
        });
        id
    }

    /// Executes a nested `zookeeper_process` invocation to completion.
    fn nested_process(p: &Polled<TestTokens>) -> Step {
        let mut ob = p.outbox();
        let mut rc = 1;
        let first = p.process(0, &mut ob, &mut rc);
        run(p, &mut ob, &mut rc, first).0
    }

    /// Drives an outer call through its resume sequence to final completion.
    fn finish_outer(p: &Polled<TestTokens>, id: u64) -> (Step, i32, Vec<Item<TestTokens>>) {
        let mut ob = p.outbox();
        ob.set_frame(id);
        let mut rc = 1;
        let mut out = InterestOut::UNWRITTEN;
        let first = p.resume(&mut ob, &mut out, &mut rc);
        let (step, items) = run(p, &mut ob, &mut rc, first);
        (step, rc, items)
    }

    #[test]
    fn a_nested_call_then_a_close_releases_in_the_outer_call() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        let mut sob = p.outbox();
        assert!(p.submit(exists(1), &mut sob).is_ok());
        let id = outer_call_in_drain(&p);

        // Callback performs nested driving before requesting session close.
        let nested = nested_process(&p);
        assert!(matches!(nested, Step::Done(_)));
        assert_eq!(p.life.refs(), 1);
        let mut cob = p.outbox();
        let mut crc = 1;
        assert_eq!(p.close(&mut cob, &mut crc), Step::Done(ZOK));
        assert_eq!(p.io.lock_owner().close, StClose::Deferred);
        assert_ne!(p.state(), 0);

        // Teardown occurs only when the outermost call reaches its epilog.
        let (step, rc, items) = finish_outer(&p, id);
        assert_eq!(step, Step::Release);
        assert_eq!(rc, ZNOTHING);
        assert_eq!(result_of(&items, 1).map(|r| r.1), Some(ZCLOSING));
        assert_eq!(p.state(), 0);
        assert_eq!(p.life.refs(), 0);
        assert!(host.discarded().is_empty());
    }

    #[test]
    fn a_close_then_a_nested_call_releases_in_the_outer_call() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        let mut sob = p.outbox();
        assert!(p.submit(exists(1), &mut sob).is_ok());
        let id = outer_call_in_drain(&p);

        // Callback closes session first, then executes nested driving step.
        let mut cob = p.outbox();
        let mut crc = 1;
        assert_eq!(p.close(&mut cob, &mut crc), Step::Done(ZOK));
        assert_eq!(p.life.refs(), 1);
        let nested = nested_process(&p);
        assert!(matches!(nested, Step::Done(_)));
        assert_eq!(p.life.refs(), 1);
        assert_ne!(p.state(), 0);

        let (step, rc, _) = finish_outer(&p, id);
        assert_eq!(step, Step::Release);
        assert_eq!(rc, ZNOTHING);
        assert_eq!(p.state(), 0);
        assert_eq!(p.life.refs(), 0);
        assert!(host.discarded().is_empty());
    }

    #[test]
    fn an_abandoned_nested_call_gives_its_ref_back() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        let id = outer_call_in_drain(&p);

        // Nested driving step pauses in drain phase before callback raises an exception.
        let mut ob = p.outbox();
        ob.push(Item::AuthLockpoint);
        let mut rc = 1;
        p.life.prolog();
        let nested_id = {
            let mut st = p.io.lock_owner();
            let nid = st.serial;
            st.serial = st.serial.wrapping_add(1);
            st.frames.push(Frame {
                id: nid,
                phase: StPhase::Drain(ResumeAt::Process),
                next: None,
                rc: ZOK,
                errno: 0,
                abandoned: false,
            });
            nid
        };
        ob.set_frame(nested_id);
        ob.set_resume(ResumeAt::Process);
        p.requeue(ob.requeue());
        assert_eq!(p.life.refs(), 2);

        // Outer call recovers from unwinding and resumes. Nested references are reclaimed.
        let (step, end_rc, _) = finish_outer(&p, id);
        assert_eq!(step, Step::Done(ZNOTHING));
        assert_eq!(end_rc, ZNOTHING);
        assert_eq!(p.life.refs(), 0);
        assert!(p.io.lock_owner().frames.is_empty());
        // Subsequent close execution executes final teardown directly.
        let mut cob = p.outbox();
        let first = p.close(&mut cob, &mut rc);
        assert_eq!(run(&p, &mut cob, &mut rc, first).0, Step::Release);
    }

    #[test]
    fn a_resume_of_an_ended_call_changes_nothing() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        let mut ob = p.outbox();
        ob.set_frame(42);
        let mut rc = 1;
        let mut out = InterestOut::UNWRITTEN;
        assert_eq!(p.resume(&mut ob, &mut out, &mut rc), Step::Done(ZOK));
        assert_eq!(p.life.refs(), 0);
        assert_eq!(p.last_errno(), 0);
    }

    /// Drives `zoo_set_servers` execution through all intermediate resume steps.
    fn set_servers(p: &Polled<TestTokens>, hosts: &[u8]) -> (i32, Vec<Item<TestTokens>>) {
        let mut ob = p.outbox();
        let mut rc = -1;
        let first = p.set_servers(hosts, &mut ob, &mut rc);
        let (step, items) = run(p, &mut ob, &mut rc, first);
        assert_eq!(step, Step::Done(rc));
        (rc, items)
    }

    #[test]
    fn set_servers_with_a_bad_string_changes_nothing() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        let (rc, items) = set_servers(&p, b"10.0.0.1");
        assert_eq!(rc, ZBADARGUMENTS);
        assert_eq!(log_at(&items, LogSite::ResolveNoPort), Some(0));
        let (rc, items) = set_servers(&p, b"10.0.0.1:x");
        assert_eq!(rc, ZBADARGUMENTS);
        assert_eq!(log_at(&items, LogSite::ResolveBadPort), Some(0));
        let (rc, items) = set_servers(&p, b",,");
        assert_eq!(rc, ZSYSTEMERROR);
        assert!(items.is_empty());
        assert_eq!(host.resolves(), 0);

        // Valid server configurations trigger address resolution and PRNG re-seeding.
        host.set_addrs(b"10.0.0.1", vec![SocketAddr::from(([10, 0, 0, 1], 2182))]);
        assert_eq!(set_servers(&p, b"10.0.0.1:2182").0, ZOK);
        assert_eq!((host.resolves(), host.reseeds()), (1, 1));
        // Unresolvable host specifications trigger resolver errors.
        assert_eq!(set_servers(&p, b"10.9.9.9:2181").0, ZSYSTEMERROR);
        assert_eq!((host.resolves(), host.reseeds()), (2, 1));
    }

    #[test]
    fn set_servers_flushes_each_failure_line_before_the_next_resolve() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        host.set_addrs(b"10.0.0.3", vec![SocketAddr::from(([10, 0, 0, 3], 2181))]);
        let mut ob = p.outbox();
        let mut rc = -1;
        // Failure on initial host emits diagnostic log before resolving secondary hosts.
        assert_eq!(
            p.set_servers(b"10.9.9.1:2181,10.0.0.3:2181", &mut ob, &mut rc),
            Step::More
        );
        assert_eq!(host.resolves(), 1);
        assert_eq!(
            ob.resume_at().map(|a| matches!(a, ResumeAt::SetServers(_))),
            Some(true)
        );
        let first = drain(&mut ob);
        assert!(!first.is_empty());
        assert!(first.iter().all(|i| matches!(i, Item::Log(_))));
        let mut out = InterestOut::UNWRITTEN;
        let step = p.resume(&mut ob, &mut out, &mut rc);
        let (step, _) = run(&p, &mut ob, &mut rc, step);
        assert_eq!(step, Step::Done(ZOK));
        assert_eq!((host.resolves(), host.reseeds()), (2, 1));
        // Unrecognized resume token terminates with invalid state.
        ob.set_resume(ResumeAt::SetServers(99));
        assert_eq!(
            p.resume(&mut ob, &mut out, &mut rc),
            Step::Done(ZINVALIDSTATE)
        );
    }

    #[test]
    fn a_second_close_continues_a_requeued_final_close() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        // Requeued entries cause initial close phase to pause.
        let mut old = p.outbox();
        old.push(Item::AuthLockpoint);
        p.requeue(old.requeue());
        let mut ob = p.outbox();
        let mut rc = -1;
        assert_eq!(p.close(&mut ob, &mut rc), Step::More);
        assert_eq!(ob.resume_at(), Some(ResumeAt::FinalClose));
        // Callback exception triggers requeue; subsequent close resumes sequence.
        p.requeue(ob.requeue());
        let mut ob = p.outbox();
        let first = p.close(&mut ob, &mut rc);
        let (end, items) = run(&p, &mut ob, &mut rc, first);
        assert_eq!(end, Step::Release);
        assert!(matches!(items.first(), Some(Item::AuthLockpoint)));
        assert_eq!(p.state(), 0);
        // Subsequent close attempts on terminated sessions return success.
        let mut ob = p.outbox();
        assert_eq!(p.close(&mut ob, &mut rc), Step::Done(ZOK));
    }

    #[test]
    fn a_requeued_set_servers_run_goes_to_the_interest_step() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        host.set_addrs(b"10.0.0.3", vec![SocketAddr::from(([10, 0, 0, 3], 2181))]);
        let mut ob = p.outbox();
        let mut rc = -1;
        assert_eq!(
            p.set_servers(b"10.9.9.1:2181,10.0.0.3:2181", &mut ob, &mut rc),
            Step::More
        );
        // Interrupted callback causes ensemble update to be requeued.
        p.requeue(ob.requeue());
        let mut proof = Unlocked::new_at_entry();
        {
            let core = p.core.lock(&mut proof);
            assert!(core.set_servers_runs.is_empty());
            let (_, hosts) = core.set_servers.as_ref().unwrap();
            assert_eq!(hosts.len(), 2);
        }
    }

    #[test]
    fn requeue_and_sasl_result_are_stored() {
        let host = Arc::new(TestHost::new());
        let p = polled(&host);
        for id in [1, 2] {
            let mut ob = p.outbox();
            ob.push(Item::AuthResult {
                token: AuthId::new(id),
                rc: ZOK,
            });
            p.requeue(ob.requeue());
        }
        p.requeue(Outbox::new().requeue());
        p.sasl_result(SaslAnswer::Stepped {
            generation: 3,
            result: Err(SaslError {
                rc: ZSYSTEMERROR,
                errstring: Vec::new(),
                errdetail: Vec::new(),
            }),
        });
        {
            let mut proof = Unlocked::new_at_entry();
            let core = p.core.lock(&mut proof);
            assert!(matches!(
                core.sasl,
                Some(SaslAnswer::Stepped { generation: 3, .. })
            ));
            assert!(core.requeued.as_ref().is_some_and(|r| !r.is_empty()));
        }

        // Final close flushes requeued items in FIFO order.
        let mut ob = p.outbox();
        let mut rc = 1;
        let first = p.close(&mut ob, &mut rc);
        let (step, items) = run(&p, &mut ob, &mut rc, first);
        assert_eq!(step, Step::Release);
        let ids: Vec<u64> = items
            .iter()
            .filter_map(|i| match i {
                Item::AuthResult { token, .. } => Some(token.id()),
                _ => None,
            })
            .collect();
        assert_eq!(ids.get(..2), Some(&[1, 2][..]));
        assert!(host.discarded().is_empty());
    }

    #[test]
    fn requeued_items_left_at_the_release_are_discarded() {
        let host = Arc::new(TestHost::new());
        host.set_log_level(Some(LogLevel::Info));
        let p = polled(&host);
        let mut ob = p.outbox();
        let mut rc = 1;
        // Diagnostic log halts close sequence before final release.
        assert_eq!(p.close(&mut ob, &mut rc), Step::More);
        assert_eq!(p.io.lock_owner().close, StClose::Release);
        // Exception in callback abandons unconsumed items.
        let mut lost = p.outbox();
        lost.push(Item::AuthResult {
            token: AuthId::new(9),
            rc: ZCLOSING,
        });
        p.requeue(lost.requeue());
        let (step, _) = run(&p, &mut ob, &mut rc, Step::More);
        assert_eq!(step, Step::Release);
        assert_eq!(rc, ZOK);
        assert_eq!(host.discarded(), vec!["auth"]);
    }
}
