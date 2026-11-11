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

//! Native multithreaded driver runtime (`native` package, `DispatchMode::IoThread`).
//!
//! # Architectural Overview
//!
//! Unlike the legacy C `libzookeeper_mt` runtime in [`crate::threaded`], which must coordinate two
//! C-managed `pthread_t` threads (`do_io` and `do_completion`) across an FFI outbox boundary, the
//! native Rust driver owns its execution loop end-to-end inside a single dedicated OS thread
//! (`zk-io`, running `io_loop`).
//!
//! Each iteration of `io_loop` invokes `NativeShared::pass`, which drives [`IoCore`] under
//! [`Flavor::Native`] and [`DeferPolicy::DNS_THREAD`] and streams completed protocol outputs
//! (`Item::Result`, `Item::Watch`, `Item::Session`, `Item::AuthResult`, `Item::Log`) directly into
//! the caller's [`RouteSink`] (typically backed by per-request oneshot channels and bounded watcher
//! channels in `zk-client`) without an intermediate completion-thread FIFO or C callback barrier:
//!
//! - **Lock-Free Output Routing and Backpressure ([`RouteSink`] + [`ReadGate`])**: Under
//!   [`Flavor::Native`], [`crate::outbox::pump`] drains `zk-proto` outputs inline without pausing
//!   at C callback barriers. `NativeShared::route` forwards each item to [`RouteSink::route`]
//!   while holding `&mut Unlocked` (proving `core` is unlocked). Counted items increment
//!   [`ReadGate`]; when unconsumed items in application channels reach
//!   `DriverConfig::dispatch_queue_capacity`, the gate closes, suppressing `PollEvents::READ` in
//!   `IoCore::interest_step` so TCP window backpressure throttles the server until consumers call
//!   [`NativeGate::taken`].
//! - **Offloaded Foreground and Background DNS Resolution (`DeferPolicy::DNS_THREAD`)**: Blocking
//!   `getaddrinfo` lookups are offloaded to short-lived `zk-dns` worker threads (`dns_thread`)
//!   coordinated through `DnsSlot`. [`IoCore`] distinguishes two resolution modes:
//!   1. *Foreground resolution* (`IoCore::job`): When disconnected and waiting on initial or
//!      reconnect DNS resolution, `interest_step` yields [`Progress::Wait`];
//!      `NativeShared::drive` spawns `zk-dns` via `NativeShared::start_dns` and `pass` waits on
//!      the self-pipe waker until `dns_thread` stores the `JobDone::Resolved` outcome in
//!      `DnsSlot::done` and wakes `zk-io`.
//!   2. *Background resolution* (`IoCore::bg_job`): When [`NativeHandle::set_servers`] triggers an
//!      ensemble update while a live connection is already active, `IoCore::interest_step`
//!      stages the lookup in `IoCore::bg_job` and continues computing socket read/write/heartbeat
//!      interest for the active connection! `NativeShared::start_background` spawns `zk-dns` in
//!      parallel so the active connection continues servicing traffic without interruption while
//!      DNS resolves in the background.
//! - **Inline Native SASL Negotiation ([`SaslSession`], `SaslHosts`)**: Rather than yielding
//!   across FFI to C Cyrus SASL callbacks, `Item::Sasl` commands are handled directly by
//!   `NativeShared::sasl_call` on `zk-io` (outside `core` lock) using the trait-based
//!   [`SaslSession`]. Because Kerberos/GSSAPI mechanisms require the canonical hostname from the
//!   connect string (not just the numerical `SocketAddr`), `SaslHosts` records `(SocketAddr,
//!   hostname)` pairs from each completed DNS generation and supplies the matching hostname to
//!   [`SaslSession::connection`].
//! - **Graceful vs. Abrupt Shutdown (`CloseProgress` vs. `detach`)**: [`NativeHandle::close`] (and
//!   `CloseOnDrop` when the last [`NativeHandle`] clone drops) initiates graceful closure by
//!   calling `conn.begin_close(now)` and `conn.queue_close_op()`, allowing `zk-io` to continue
//!   driving [`IoCore`] until `conn.close_step(now)` reports [`CloseProgress::Complete`] (meaning
//!   `CLOSE_OP` has been transmitted or the close deadline expired), followed by TLS
//!   `close_notify` flushing in `NativeShared::close_rc`. Conversely, [`NativeHandle::detach`]
//!   calls `conn.abandon(now)` and exits the reactor loop immediately on the next wake.

use std::any::Any;
use std::net::SocketAddr;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

use rustix::event::{PollFd, PollFlags};
use zk_jute::JString;
use zk_proto::rc::{ZCLOSING, ZCONNECTIONLOSS, ZINVALIDSTATE, ZOK, ZSYSTEMERROR};
use zk_proto::{
    Action, AddAuthError, Cause, ClientId, CloseProgress, Connection, HostSpec, Millis, MultiOpRef,
    Request, SaslCall, SaslCallKind, SaslError, SaslSession, SecretBytes, SubmitError, Submitted,
};

use crate::dispatch::{ReadGate, RouteSink};
use crate::host::{DriverTokens, discard_leftovers};
use crate::iocore::{
    DeferPolicy, InterestOut, IoCore, Job, JobCtx, JobDone, Progress, StepCtx, run_job,
};
use crate::net::poll::{PollEvents, clamp_timeout_ms, map_revents, request_flags};
use crate::net::waker::{WakeRx, Waker};
use crate::outbox::{Flavor, Item, Outbox, SaslItem, discard_item};
use crate::state::{
    Core, Env, SaslAnswer, SubmitResult, read_at_entry, read_conn, with_conn_at_entry,
};
use crate::sync::atomic::{AtomicBool, Ordering};
use crate::sync::{Condvar, Mutex, Unlocked};

/// Operating system thread name assigned to the primary network I/O reactor thread.
pub const IO_THREAD_NAME: &str = "zk-io";

/// Operating system thread name assigned to background DNS resolution worker threads.
pub const DNS_THREAD_NAME: &str = "zk-dns";

/// Upper bound on discrete protocol actions executed during final teardown following connection
/// destruction.
const MAX_FINAL_ACTIONS: usize = 16;

/// Closure type representing a background worker thread entry routine.
pub type ThreadBody = Box<dyn FnOnce() + Send + 'static>;

/// Abstract thread spawning factory enabling custom thread creation and detachment.
pub trait Spawn: Send + Sync {
    /// Spawns `body` on a new detached OS thread configured with the specified thread name.
    ///
    /// # Errors
    ///
    /// Returns the operating system error if thread creation fails.
    fn spawn(&self, name: &'static str, body: ThreadBody) -> std::io::Result<()>;
}

/// Errors encountered during native driver startup.
#[derive(Debug)]
pub enum StartError {
    /// Self-pipe waker channel creation failed.
    Waker(rustix::io::Errno),
    /// Failed to spawn the primary network I/O thread.
    Spawn(std::io::Error),
}

/// Shared coordination slot bridging background DNS worker results with the primary I/O reactor
/// thread.
pub(crate) struct DnsSlot {
    /// Indicates whether a background DNS resolution thread is actively executing.
    pub(crate) running: bool,
    /// Completed DNS resolution job outcome awaiting processing by the I/O thread.
    pub(crate) done: Option<JobDone>,
}

/// Maximum count of remote socket address-to-hostname mappings retained for SASL authentication.
pub(crate) const SASL_HOSTS_MAX: usize = 256;

/// Caches mapping from resolved remote socket addresses back to their original connect string
/// hostnames.
///
/// SASL mechanisms (such as Kerberos GSSAPI) require the canonical hostname of the target server
/// to form valid service principals. Because protocol connection items report only numerical
/// socket addresses, this cache preserves the source hostnames associated with resolved endpoints.
#[derive(Debug, Default)]
pub(crate) struct SaslHosts {
    /// Resolver generation corresponding to the cached host mappings.
    generation: Option<u64>,
    /// Address-to-hostname pairings from the most recent resolution generation.
    entries: Vec<(SocketAddr, Vec<u8>)>,
}

impl SaslHosts {
    /// Extracts the original hostname byte strings for each target host in a resolution job.
    pub(crate) fn names(job: &Job) -> Option<Vec<Vec<u8>>> {
        match job {
            Job::Resolve { hosts, .. } => {
                Some(hosts.iter().map(|h| h.host_bytes().to_vec()).collect())
            }
            _ => None,
        }
    }

    /// Updates cached host mappings from a completed resolution result, ignoring obsolete
    /// generations.
    pub(crate) fn record(&mut self, names: &[Vec<u8>], done: &JobDone) {
        let JobDone::Resolved {
            generation,
            results,
        } = done
        else {
            return;
        };
        if self.generation.is_some_and(|g| g > *generation) {
            return;
        }
        self.generation = Some(*generation);
        self.entries.clear();
        for r in results {
            let (Some(name), Ok(addrs)) = (names.get(r.host), r.result.as_ref()) else {
                continue;
            };
            for addr in addrs {
                if self.entries.len() >= SASL_HOSTS_MAX {
                    return;
                }
                if !self.entries.iter().any(|(a, _)| a == addr) {
                    self.entries.push((*addr, name.clone()));
                }
            }
        }
    }

    /// Resolves the hostname associated with a remote peer address, falling back to IP string
    /// representation if unknown.
    pub(crate) fn host_of(&self, peer: SocketAddr) -> Vec<u8> {
        self.entries
            .iter()
            .find(|(a, _)| *a == peer)
            .map_or_else(|| peer.ip().to_string().into_bytes(), |(_, h)| h.clone())
    }
}

/// Protected state and resources owned exclusively by the primary I/O reactor thread.
pub(crate) struct NativeIo {
    /// Core transport engine and nonblocking framing state machine.
    pub(crate) io: IoCore,
    /// Receiving end of the self-pipe waker channel for interruptible reactor polling.
    pub(crate) wake_rx: WakeRx,
    /// Configured SASL authentication session, or `None` if authentication is disabled.
    pub(crate) sasl: Option<Box<dyn SaslSession>>,
}

/// Shared runtime state coordinating reactor loops, backpressure gating, and client shutdown.
pub(crate) struct NativeShared<T: DriverTokens> {
    /// Primary protocol connection state machine and request queues.
    pub(crate) core: Mutex<Core<T>>,
    /// Network reactor state, transport socket, and SASL session guarded by the I/O lock.
    pub(crate) io: Mutex<NativeIo>,
    /// Self-pipe waker channel used to interrupt reactor polling across thread boundaries.
    pub(crate) waker: Waker,
    /// Read gate bounding unconsumed completion events in application channels.
    pub(crate) gate: ReadGate,
    /// Sink routing completion events and notifications directly to client dispatch receivers.
    pub(crate) sink: Arc<dyn RouteSink<T>>,
    /// Driver environment providing monotonic time, host hooks, and network polling facilities.
    pub(crate) env: Env<T>,
    /// Atomic flag indicating that graceful or abrupt session shutdown has been initiated.
    pub(crate) close_requested: AtomicBool,
    /// Atomic flag indicating that the session has been detached without graceful close.
    pub(crate) detached: AtomicBool,
    /// Coordination slot bridging background DNS worker results with the reactor loop.
    pub(crate) dns: Mutex<DnsSlot>,
    /// Leaf-locked mapping from resolved socket addresses to hostnames for SASL authentication.
    pub(crate) hosts: Mutex<SaslHosts>,
    /// Indicates whether SASL authentication is active and requires host tracking.
    pub(crate) sasl_hosts: bool,
    /// Captured session termination return code, set once when the I/O thread exits.
    pub(crate) exit: Mutex<Option<i32>>,
    /// Condition variable notifying callers awaiting session termination in `wait_closed`.
    pub(crate) exit_cv: Condvar,
    /// Spawner implementation used to launch background DNS resolution threads.
    pub(crate) spawner: Arc<dyn Spawn>,
    /// Flag indicating whether the protocol `CLOSE_OP` frame was successfully enqueued.
    close_op_queued: AtomicBool,
    /// Weak reference back to this shared instance for background DNS worker callbacks.
    this: Weak<NativeShared<T>>,
}

/// Outcome of a single iteration of the primary network I/O reactor loop.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Pass {
    /// Proceed with the next reactor polling pass.
    Again,
    /// Terminate the reactor loop reporting the specified close return code.
    Exit(i32),
}

/// Distinguishes between graceful session closure and abrupt client detachment.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum End {
    /// Graceful session teardown completed.
    Close,
    /// Client detached, abandoning the active connection immediately.
    Detach,
}

/// Client session handle for the native threaded driver, clonable across application tasks.
///
/// The session remains active until [`NativeHandle::close`] or [`NativeHandle::detach`] is
/// invoked, or until the final clone of the handle is dropped (which triggers background
/// graceful closure).
pub struct NativeHandle<T: DriverTokens> {
    pub(crate) shared: Arc<NativeShared<T>>,
    /// Drop guard that triggers session termination when the final handle clone is deallocated.
    owner: Arc<CloseOnDrop<T>>,
}

impl<T: DriverTokens> Clone for NativeHandle<T> {
    fn clone(&self) -> Self {
        Self {
            shared: Arc::clone(&self.shared),
            owner: Arc::clone(&self.owner),
        }
    }
}

/// Flow-control reporter tracking unconsumed events delivered to client dispatch channels.
///
/// Allows consumer pipelines to safely outlive the primary client handle while pending results
/// are drained, ensuring accurate backpressure tracking until the I/O thread exits.
pub struct NativeGate<T: DriverTokens> {
    shared: Arc<NativeShared<T>>,
}

impl<T: DriverTokens> Clone for NativeGate<T> {
    fn clone(&self) -> Self {
        Self {
            shared: Arc::clone(&self.shared),
        }
    }
}

impl<T: DriverTokens> NativeGate<T> {
    /// Notifies the read gate that an item was processed or dropped by the consumer, waking the
    /// reactor thread if the gate reopens.
    pub fn taken(&self) {
        if self.shared.gate.taken() {
            self.shared.waker.wake();
        }
    }
}

/// Internal drop guard that initiates graceful background session closure when the final handle
/// clone is deallocated.
struct CloseOnDrop<T: DriverTokens> {
    shared: Arc<NativeShared<T>>,
}

impl<T: DriverTokens> Drop for CloseOnDrop<T> {
    fn drop(&mut self) {
        // Idempotent session closure on final handle deallocation.
        self.shared.close();
    }
}

impl<T: DriverTokens> NativeHandle<T> {
    /// Constructs the primary client handle wrapping shared driver runtime state.
    pub(crate) fn from_shared(shared: Arc<NativeShared<T>>) -> Self {
        let owner = Arc::new(CloseOnDrop {
            shared: Arc::clone(&shared),
        });
        Self { shared, owner }
    }
}

/// Spawns and initializes the native threaded driver for an established connection.
///
/// Enforces `O_CLOEXEC` on all descriptors, constructs shared runtime state, launches
/// the dedicated background I/O thread under [`IO_THREAD_NAME`], and returns the primary handle.
///
/// # Errors
///
/// Returns a [`StartError`] if waker pipe creation fails or if the OS fails to spawn the I/O
/// thread.
pub fn start<T: DriverTokens>(
    conn: Box<Connection<T>>,
    mut env: Env<T>,
    sink: Arc<dyn RouteSink<T>>,
    sasl: Option<Box<dyn SaslSession>>,
    spawner: Arc<dyn Spawn>,
) -> Result<NativeHandle<T>, StartError> {
    // Enforce CLOEXEC across all sockets and pipes to prevent leaking descriptors across process
    // forks.
    env.cfg.cloexec = true;
    let shared = NativeShared::new(conn, env, sink, sasl, spawner)?;
    let body = Arc::clone(&shared);
    let spawned = shared
        .spawner
        .spawn(IO_THREAD_NAME, Box::new(move || io_loop(body)));
    if let Err(e) = spawned {
        // When thread spawning fails, clean up connection state synchronously on the caller
        // thread.
        let mut proof = Unlocked::new_at_entry();
        let mut io = shared.io.lock_owner();
        shared.release(&mut proof, &mut io);
        return Err(StartError::Spawn(e));
    }
    Ok(NativeHandle::from_shared(shared))
}

/// Primary execution loop for the dedicated background network I/O reactor thread.
///
/// Repeatedly drives reactor passes via [`NativeShared::pass`] until signaled to exit,
/// subsequently publishing the final termination return code to all awaiting callers.
///
/// If an unrecoverable panic occurs during a reactor pass, the loop aborts the session, releases
/// the underlying transport, reclaims all in-flight tokens, and publishes `ZCONNECTIONLOSS`
/// (matching the Tokio driver's `ExitGuard`), even if the teardown procedure itself panics.
pub(crate) fn io_loop<T: DriverTokens>(shared: Arc<NativeShared<T>>) {
    let mut proof = Unlocked::new_at_entry();
    let mut ob = Outbox::new();
    let run = catch_unwind(AssertUnwindSafe(|| {
        run_passes(&shared, &mut proof, &mut ob)
    }));
    let rc = match run {
        Ok(rc) => rc,
        Err(payload) => {
            drop_payload(payload);
            let teardown = catch_unwind(AssertUnwindSafe(|| {
                shared.abort_after_panic(&mut proof, &mut ob);
            }));
            if let Err(payload) = teardown {
                drop_payload(payload);
            }
            ZCONNECTIONLOSS
        }
    };
    shared.set_exit(rc);
}

/// Repeatedly executes reactor passes until [`Pass::Exit`] returns the terminal session status.
fn run_passes<T: DriverTokens>(
    shared: &NativeShared<T>,
    proof: &mut Unlocked,
    ob: &mut Outbox<T>,
) -> i32 {
    loop {
        let pass = shared.pass(proof, ob);
        // Completed items are routed through the sink during the pass; any orphaned items are
        // reclaimed.
        ob.finish(&*shared.env.host);
        if let Pass::Exit(rc) = pass {
            return rc;
        }
    }
}

/// Disposes of a caught panic payload inside a secondary unwind boundary, leaking the inner
/// payload if its destructor panics so that the I/O thread always publishes its exit status.
fn drop_payload(payload: Box<dyn Any + Send>) {
    if let Err(again) = catch_unwind(AssertUnwindSafe(move || drop(payload))) {
        std::mem::forget(again);
    }
}

/// Background DNS resolution worker routine executing asynchronous lookups without blocking the
/// I/O thread.
pub(crate) fn dns_thread<T: DriverTokens>(shared: Arc<NativeShared<T>>, job: crate::iocore::Job) {
    let proof = Unlocked::new_at_entry();
    let names = shared.sasl_hosts.then_some(&job).and_then(SaslHosts::names);
    let done = {
        // DNS worker threads do not inspect waker pipes. Cancellation is governed via the atomic
        // close flag.
        let cx = shared.env.step_ctx(
            &shared.core,
            NativeShared::<T>::FLAVOR,
            NativeShared::<T>::DEFER,
            None,
            Some(&shared.close_requested),
            None,
        );
        let jcx = JobCtx {
            host: &*shared.env.host,
            probe: cx.probe_ctx(),
        };
        run_job(&jcx, &proof, job)
    };
    if let Some(names) = names {
        shared.hosts.lock_owner().record(&names, &done);
    }
    {
        let mut slot = shared.dns.lock_owner();
        slot.done = Some(done);
        slot.running = false;
    }
    shared.waker.wake();
}

impl<T: DriverTokens> NativeShared<T> {
    /// Constructs shared runtime state configured with an open read gate and a cloexec waker
    /// channel.
    fn new(
        conn: Box<Connection<T>>,
        env: Env<T>,
        sink: Arc<dyn RouteSink<T>>,
        sasl: Option<Box<dyn SaslSession>>,
        spawner: Arc<dyn Spawn>,
    ) -> Result<Arc<Self>, StartError> {
        let (waker, wake_rx) = Waker::new(true).map_err(StartError::Waker)?;
        let gate = ReadGate::new(env.cfg.dispatch_queue_capacity);
        let sasl_hosts = sasl.is_some();
        Ok(Arc::new_cyclic(|this| Self {
            core: Mutex::new(Core::new(conn)),
            io: Mutex::new(NativeIo {
                io: IoCore::new(),
                wake_rx,
                sasl,
            }),
            waker,
            gate,
            sink,
            env,
            close_requested: AtomicBool::new(false),
            detached: AtomicBool::new(false),
            dns: Mutex::new(DnsSlot {
                running: false,
                done: None,
            }),
            hosts: Mutex::new(SaslHosts::default()),
            sasl_hosts,
            exit: Mutex::new(None),
            exit_cv: Condvar::new(),
            spawner,
            close_op_queued: AtomicBool::new(false),
            this: this.clone(),
        }))
    }

    /// Initiates graceful session closure by enqueuing `CLOSE_OP` and waking the reactor loop.
    pub(crate) fn close(&self) {
        let now = self.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        {
            let mut core = self.core.lock(&mut proof);
            if self.close_requested.swap(true, Ordering::AcqRel) {
                return;
            }
            if let Some(c) = core.conn.as_deref_mut() {
                c.begin_close(now);
                let queued = c.queue_close_op();
                self.close_op_queued.store(queued, Ordering::Release);
            }
        }
        self.waker.wake();
    }

    /// Drives a single iteration of the primary `zk-io` network reactor loop.
    ///
    /// Execution sequence for each pass:
    /// 1. **Pre-Pass Shutdown Check**: Calls [`NativeShared::end_check`] to exit immediately if the
    ///    session was detached (`End::Detach`) or if graceful closure has finished draining
    ///    (`CloseProgress::Complete`).
    /// 2. **DNS Completion and Interest Evaluation**: Inspects [`DnsSlot`] for any completed
    ///    `zk-dns` worker outcome:
    ///    - If `done` arrived while `IoCore` was suspended in a foreground wait (`waiting`), feeds
    ///      it directly into [`IoCore::complete_resolve`].
    ///    - If `done` arrived from a background resolution (`!waiting`), applies it first via
    ///      [`NativeShared::apply_background`] (which may drop the current transport if the active
    ///      server was removed from the new ensemble) and then runs [`IoCore::interest_step`].
    ///    - If a foreground `zk-dns` worker is still running (`running && waiting`), skips
    ///      `interest_step` (`first = None`) and waits on the self-pipe waker in step 5.
    ///    - Otherwise runs [`IoCore::interest_step`] normally.
    /// 3. **Step Driving and Background DNS Launch**: Drives the initial [`Progress`] through
    ///    [`NativeShared::drive`] (which routes outbox items and spawns foreground `zk-dns` jobs
    ///    on [`Progress::Wait`]), then calls [`NativeShared::start_background`] to launch any
    ///    newly staged background DNS job (`IoCore::bg_job`).
    /// 4. **Socket Poll and Process Step**: If `drive` completed (`ready == true`), polls socket
    ///    and waker via [`NativeShared::poll_io`] and—if a socket is open or events fired—runs
    ///    [`IoCore::process_step`] through [`NativeShared::drive`]. If `drive` yielded on a
    ///    foreground DNS worker (`ready == false`), waits on the waker pipe alone via
    ///    [`NativeShared::wait_waker`].
    /// 5. **Post-Pass Shutdown Check**: Re-evaluates [`NativeShared::end_check`] so a `CLOSE_OP`
    ///    transmitted during `process_step` terminates the reactor loop without an extra poll
    ///    cycle.
    pub(crate) fn pass(&self, proof: &mut Unlocked, ob: &mut Outbox<T>) -> Pass {
        let mut guard = self.io.lock_owner();
        let io = &mut *guard;
        if let Some(rc) = self.end_check(proof, ob, io) {
            return Pass::Exit(rc);
        }
        let mut out = InterestOut::UNWRITTEN;
        let (running, done) = {
            let mut slot = self.dns.lock_owner();
            (slot.running, slot.done.take())
        };
        let waiting = io.io.job.is_some();
        let first = match done {
            Some(done) if waiting => {
                let cx = self.step_cx(&io.wake_rx);
                Some(io.io.complete_resolve(&cx, proof, done, ob, &mut out))
            }
            Some(done) => {
                // Background resolution completed. Process it ahead of this iteration's interest
                // calculation.
                self.apply_background(proof, ob, io, done);
                let cx = self.step_cx(&io.wake_rx);
                Some(io.io.interest_step(&cx, proof, ob, &mut out))
            }
            None if running && waiting => None,
            None => {
                let cx = self.step_cx(&io.wake_rx);
                Some(io.io.interest_step(&cx, proof, ob, &mut out))
            }
        };
        let ready = match first {
            Some(p) => self.drive(proof, ob, io, p, &mut out).is_some(),
            None => false,
        };
        self.start_background(proof, ob, io);
        if ready {
            let events = self.poll_io(proof, io, &out);
            if io.io.has_socket() || !events.is_empty() {
                let mut scratch = InterestOut::UNWRITTEN;
                let p = {
                    let cx = self.step_cx(&io.wake_rx);
                    io.io.process_step(&cx, proof, events, ob)
                };
                let _ = self.drive(proof, ob, io, p, &mut scratch);
            }
        } else {
            let ms = self.idle_ms(proof);
            self.wait_waker(io, ms);
        }
        match self.end_check(proof, ob, io) {
            Some(rc) => Pass::Exit(rc),
            None => Pass::Again,
        }
    }

    /// Routes accumulated outbox items in order through the client's [`RouteSink`] without holding
    /// the `core` mutex.
    ///
    /// `Item::Sasl` negotiation commands are dispatched inline to [`NativeShared::sasl_call`],
    /// while user-visible items (`Result`, `Watch`, `Session`, `AuthResult`, `Log`) are forwarded
    /// to `self.sink.route(other)`. Whenever `RouteSink::route` returns `true` (indicating a
    /// counted item was buffered in a consumer channel), increments [`ReadGate::routed`] to enforce
    /// bounded backpressure.
    pub(crate) fn route(&self, proof: &mut Unlocked, ob: &mut Outbox<T>, io: &mut NativeIo) {
        while let Some(item) = ob.pop() {
            match item {
                Item::Sasl(s) => self.sasl_call(proof, io, s),
                // Lockpoint markers and thread management commands do not occur in native driver
                // mode.
                Item::AuthLockpoint | Item::Thread { .. } => {}
                other => {
                    if self.sink.route(other) {
                        self.gate.routed();
                    }
                }
            }
        }
    }

    /// Constructs the operational context for driving I/O steps on the reactor thread.
    fn step_cx<'a>(&'a self, wake_rx: &'a WakeRx) -> StepCtx<'a, T> {
        self.env.step_ctx(
            &self.core,
            Self::FLAVOR,
            Self::DEFER,
            Some((&self.waker, wake_rx)),
            Some(&self.close_requested),
            Some(&self.gate),
        )
    }

    /// Drives an [`IoCore`] [`Progress`] loop to completion, routing emitted outbox items before
    /// each state transition and dispatching deferred jobs on [`Progress::Wait`].
    ///
    /// Returns `Some(rc)` when the step finishes (`Progress::Done(rc)`), or `None` when a
    /// foreground `Job::Resolve` has been offloaded to a `zk-dns` worker thread and `IoCore` must
    /// wait for `DnsSlot::done` before computing socket interests.
    fn drive(
        &self,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        io: &mut NativeIo,
        first: Progress,
        out: &mut InterestOut,
    ) -> Option<i32> {
        let mut p = first;
        loop {
            self.route(proof, ob, io);
            p = match p {
                Progress::Done(rc) => return Some(rc),
                Progress::More => {
                    let cx = self.step_cx(&io.wake_rx);
                    io.io.resume(&cx, proof, ob, out)
                }
                Progress::Wait => self.run_jobs(proof, ob, io, out)?,
            };
        }
    }

    /// Dispatches deferred foreground jobs (`IoCore::job`) yielded via [`Progress::Wait`].
    ///
    /// Under [`DeferPolicy::DNS_THREAD`], `Job::Resolve` is offloaded to a detached `zk-dns`
    /// worker thread via [`NativeShared::start_dns`], returning `None` so `pass` yields to
    /// `wait_waker` while keeping `zk-io` responsive to shutdown signals. If spawning the worker
    /// thread fails (or if a `zk-dns` thread from an earlier generation is still exiting), falls
    /// back to executing the job synchronously on `zk-io` via [`NativeShared::run_inline`] and
    /// immediately resumes `IoCore::complete_job`.
    fn run_jobs(
        &self,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        io: &mut NativeIo,
        out: &mut InterestOut,
    ) -> Option<Progress> {
        let mut next = None;
        for job in io.io.take_jobs() {
            let job = match job {
                Job::Resolve { .. } => match self.start_dns(job) {
                    Ok(()) => {
                        next = None;
                        continue;
                    }
                    Err(job) => job,
                },
                other => other,
            };
            let done = self.run_inline(proof, io, job);
            let cx = self.step_cx(&io.wake_rx);
            next = Some(io.io.complete_job(&cx, proof, done, ob, out));
        }
        next
    }

    /// Launches a background `zk-dns` worker thread for any pending background resolution job
    /// (`IoCore::bg_job`, produced when `set_servers` is called while a connection is active),
    /// falling back to inline execution if thread creation fails.
    fn start_background(&self, proof: &mut Unlocked, ob: &mut Outbox<T>, io: &mut NativeIo) {
        while let Some(job) = io.io.take_bg_job() {
            let Err(job) = self.start_dns(job) else {
                return;
            };
            let done = self.run_inline(proof, io, job);
            self.apply_background(proof, ob, io, done);
        }
    }

    /// Applies the outcome of a completed background resolution job to connection state.
    fn apply_background(
        &self,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        io: &mut NativeIo,
        done: JobDone,
    ) {
        let mut scratch = InterestOut::UNWRITTEN;
        let p = {
            let cx = self.step_cx(&io.wake_rx);
            io.io.complete_resolve(&cx, proof, done, ob, &mut scratch)
        };
        let _ = self.drive(proof, ob, io, p, &mut scratch);
    }

    /// Attempts to spawn a background DNS worker thread, returning the job on failure or if a
    /// worker is already active.
    fn start_dns(&self, job: Job) -> Result<(), Job> {
        let Some(me) = self.this.upgrade() else {
            return Err(job);
        };
        {
            let mut slot = self.dns.lock_owner();
            if slot.running {
                return Err(job);
            }
            slot.running = true;
        }
        // Preserve the job instance across potential thread spawning failures.
        let cell = Arc::new(Mutex::new(Some(job)));
        let body_cell = Arc::clone(&cell);
        let body: ThreadBody = Box::new(move || {
            let job = body_cell.lock_owner().take();
            if let Some(job) = job {
                dns_thread(me, job);
            }
        });
        match self.spawner.spawn(DNS_THREAD_NAME, body) {
            Ok(()) => Ok(()),
            Err(_) => {
                self.dns.lock_owner().running = false;
                let job = cell.lock_owner().take();
                job.map_or(Ok(()), Err)
            }
        }
    }

    /// Executes a driver job synchronously on the I/O thread without holding core locks.
    fn run_inline(&self, proof: &mut Unlocked, io: &mut NativeIo, job: Job) -> JobDone {
        let names = self.sasl_hosts.then_some(&job).and_then(SaslHosts::names);
        let cx = self.step_cx(&io.wake_rx);
        let jcx = JobCtx {
            host: &*self.env.host,
            probe: cx.probe_ctx(),
        };
        let done = run_job(&jcx, proof, job);
        if let Some(names) = names {
            self.hosts.lock_owner().record(&names, &done);
        }
        done
    }

    /// Performs interruptible reactor polling across the self-pipe waker descriptor and the
    /// network socket.
    ///
    /// When a waker interrupt fires while an active data-phase transport is registered and no
    /// close is pending, `WRITE` readiness is synthesized immediately so newly submitted requests
    /// flush during the current reactor pass.
    fn poll_io(&self, proof: &mut Unlocked, io: &mut NativeIo, out: &InterestOut) -> PollEvents {
        let suspended = read_conn(&self.core, proof, Connection::is_suspended).unwrap_or(false);
        let buffered = io.io.buffered_ready(suspended, self.gate.is_open());
        let timeout = if buffered {
            0
        } else if out.written {
            clamp_timeout_ms(out.timeout_ms)
        } else {
            self.idle_ms(proof)
        };
        let flags =
            (out.written && out.fd >= 0).then(|| request_flags(PollEvents::from_bits(out.bits)));
        let (woke, mut events) = match (io.io.conn.sock(), flags) {
            (Some(sock), Some(flags)) => {
                let mut fds = [
                    PollFd::new(&io.wake_rx, PollFlags::IN),
                    PollFd::new(sock, flags),
                ];
                let ready = self.env.poll.poll(&mut fds, timeout).is_ok_and(|n| n > 0);
                let [w, s] = &fds;
                if ready {
                    (!w.revents().is_empty(), map_revents(s.revents()))
                } else {
                    (false, PollEvents::NONE)
                }
            }
            _ => (self.poll_waker(io, timeout), PollEvents::NONE),
        };
        if woke {
            io.wake_rx.reset(&self.waker);
            // Synthesize write readiness on wakeup so newly queued outbound frames flush within
            // the current pass without requiring an extra interest evaluation and poll cycle.
            if flags.is_some()
                && io.io.conn.data_phase()
                && !self.close_requested.load(Ordering::Acquire)
            {
                events.write = true;
            }
        }
        if buffered {
            events.read = true;
        }
        events
    }

    /// Awaits interrupt notification on the self-pipe waker channel, rearming it upon wake.
    fn wait_waker(&self, io: &mut NativeIo, timeout_ms: i32) {
        if self.poll_waker(io, timeout_ms) {
            io.wake_rx.reset(&self.waker);
        }
    }

    /// Polls the self-pipe waker descriptor alone for incoming interrupt notifications.
    fn poll_waker(&self, io: &NativeIo, timeout_ms: i32) -> bool {
        let mut fds = [PollFd::new(&io.wake_rx, PollFlags::IN)];
        let ready = self
            .env
            .poll
            .poll(&mut fds, timeout_ms)
            .is_ok_and(|n| n > 0);
        let [w] = &fds;
        ready && !w.revents().is_empty()
    }

    /// Calculates fallback idle polling timeout when no active network socket interest exists.
    fn idle_ms(&self, proof: &mut Unlocked) -> i32 {
        read_conn(&self.core, proof, Connection::recv_timeout)
            .unwrap_or(0)
            .checked_div(60)
            .unwrap_or(0)
            .max(1)
    }

    /// Inspects shutdown conditions, coordinating graceful teardown or abrupt detachment.
    fn end_check(
        &self,
        proof: &mut Unlocked,
        ob: &mut Outbox<T>,
        io: &mut NativeIo,
    ) -> Option<i32> {
        if self.detached.load(Ordering::Acquire) {
            return Some(self.finish(proof, ob, io, End::Detach));
        }
        if !self.close_requested.load(Ordering::Acquire) {
            return None;
        }
        let now = self.env.clock.now();
        let complete = {
            let mut core = self.core.lock(proof);
            core.conn
                .as_deref_mut()
                .is_none_or(|c| c.close_step(now) == CloseProgress::Complete)
        };
        complete.then(|| self.finish(proof, ob, io, End::Close))
    }

    /// Finalizes session termination, draining remaining outbox events and releasing resources.
    fn finish(&self, proof: &mut Unlocked, ob: &mut Outbox<T>, io: &mut NativeIo, end: End) -> i32 {
        self.route(proof, ob, io);
        let rc = match end {
            End::Detach => ZOK,
            End::Close => {
                let rc = self.close_rc(proof, ob, io);
                let now = self.env.clock.now();
                if let Some(c) = self.core.lock(proof).conn.as_deref_mut() {
                    c.destroy(now);
                }
                rc
            }
        };
        self.final_drain(proof, ob, io);
        self.release(proof, io);
        rc
    }

    /// Determines the terminal return code for graceful session closure, executing TLS close-
    /// notify flush if active.
    fn close_rc(&self, proof: &mut Unlocked, ob: &mut Outbox<T>, io: &mut NativeIo) -> i32 {
        let outcome = read_conn(&self.core, proof, Connection::close_outcome).flatten();
        if !self.close_op_queued.load(Ordering::Acquire) {
            return ZOK;
        }
        let rc = outcome.unwrap_or(ZCONNECTIONLOSS);
        let live = read_conn(&self.core, proof, Connection::has_transport).unwrap_or(false);
        if live && io.io.has_tls_session() {
            let cx = self.step_cx(&io.wake_rx);
            let _flush_rc = io.io.close_flush(&cx, proof, ob);
        }
        rc
    }

    /// Drains terminal protocol actions following connection destruction.
    fn final_drain(&self, proof: &mut Unlocked, ob: &mut Outbox<T>, io: &mut NativeIo) {
        self.flush_outputs(proof, ob, io);
        for _ in 0..MAX_FINAL_ACTIONS {
            let now = self.env.clock.now();
            let action = {
                let mut core = self.core.lock(proof);
                core.conn.as_deref_mut().and_then(|c| c.poll_action(now))
            };
            let Some(action) = action else {
                break;
            };
            match action {
                Action::CloseTransport => {
                    io.io.drop_transport();
                    self.core.lock(proof).peer = None;
                }
                Action::Sasl(call) => ob.push(Item::Sasl(SaslItem { call, addrs: None })),
                // Suppress further resolution and connection actions during final teardown.
                Action::Resolve { .. } | Action::Connect { .. } | Action::ProbeRw { .. } => {}
            }
            self.flush_outputs(proof, ob, io);
        }
        io.io.drop_transport();
        self.core.lock(proof).peer = None;
    }

    /// Pumps and routes protocol outputs until outboxes are fully exhausted.
    fn flush_outputs(&self, proof: &mut Unlocked, ob: &mut Outbox<T>, io: &mut NativeIo) {
        loop {
            let barrier = {
                let mut core = self.core.lock(proof);
                core.pump(Self::FLAVOR, ob, &*self.env.host)
            };
            self.route(proof, ob, io);
            if !barrier {
                break;
            }
            let now = self.env.clock.now();
            self.core.lock(proof).resume_stopped(now);
        }
    }

    /// Deallocates connection state, drops transport handles, and reclaims all unconsumed tokens
    /// via host discard hooks.
    fn release(&self, proof: &mut Unlocked, io: &mut NativeIo) {
        let (conn, requeued, local) = {
            let mut core = self.core.lock(proof);
            core.stopped = None;
            core.sasl = None;
            core.peer = None;
            (
                core.conn.take(),
                core.requeued.take(),
                std::mem::take(&mut core.local),
            )
        };
        io.io.drop_transport();
        io.sasl = None;
        let host = &*self.env.host;
        if let Some(conn) = conn {
            discard_leftovers(host, conn);
        }
        if let Some(requeued) = requeued {
            let mut rest = Outbox::new();
            rest.restore(requeued);
            rest.finish(host);
        }
        for item in local {
            discard_item(host, item);
        }
    }

    /// Emergency teardown path invoked after catching an unhandled panic on the I/O thread.
    ///
    /// Bypasses protocol state transitions and event routing because internal state may be
    /// inconsistent, acquiring locks solely to drop the network transport and reclaim tokens.
    fn abort_after_panic(&self, proof: &mut Unlocked, ob: &mut Outbox<T>) {
        ob.finish(&*self.env.host);
        let mut io = self.io.lock_owner();
        self.release(proof, &mut io);
    }

    /// Records the terminal exit return code and broadcasts wakeups to all threads awaiting
    /// closure.
    fn set_exit(&self, rc: i32) {
        let mut exit = self.exit.lock_owner();
        if exit.is_none() {
            *exit = Some(rc);
        }
        drop(exit);
        self.exit_cv.notify_all();
    }

    /// Executes a single SASL mechanism negotiation step and records the resulting [`SaslAnswer`]
    /// so protocol execution can resume.
    ///
    /// Invoked outside the `core` mutex while retaining the outer `io` owner lock that guards
    /// `NativeIo::sasl`.
    fn sasl_call(&self, proof: &mut Unlocked, io: &mut NativeIo, item: SaslItem) {
        let SaslCall { generation, kind } = item.call;
        let mech = io.sasl.as_deref_mut();
        let answer = match kind {
            SaslCallKind::Start => SaslAnswer::Started {
                generation,
                result: match mech {
                    Some(m) => {
                        if let Some((local, peer)) = item.addrs {
                            let host = self.hosts.lock_owner().host_of(peer);
                            m.connection(&host, local, peer);
                        }
                        m.start()
                    }
                    None => Err(no_mechanism()),
                },
            },
            SaslCallKind::Step(token) => SaslAnswer::Stepped {
                generation,
                result: match mech {
                    Some(m) => m.step(token.as_ref().map(SecretBytes::expose)),
                    None => Err(no_mechanism()),
                },
            },
            SaslCallKind::Dispose => return,
        };
        self.core.lock(proof).sasl = Some(answer);
    }
}

/// Constructs a system error representing missing SASL authentication configuration.
pub(crate) fn no_mechanism() -> SaslError {
    SaslError {
        rc: ZSYSTEMERROR,
        errstring: b"no SASL mechanism".to_vec(),
        errdetail: Vec::new(),
    }
}

impl<T: DriverTokens> NativeHandle<T> {
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

    /// Submits a request with borrowed payload data, signaling the reactor waker upon acceptance.
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

    /// Internal helper submitting requests under the core lock and signaling the reactor waker
    /// upon acceptance.
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
            self.shared.waker.wake();
        }
        r
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
        let now = self.shared.env.clock.now();
        let r = self
            .shared
            .core
            .lock(&mut Unlocked::new_at_entry())
            .add_auth_or(scheme, cred, done, now, ZINVALIDSTATE);
        if r.is_ok() {
            self.shared.waker.wake();
        }
        r
    }

    /// Updates the ensemble server list and triggers background re-resolution.
    ///
    /// Returns the new resolver generation, or 0 if the I/O thread has already terminated.
    pub fn set_servers(&self, hosts: Vec<HostSpec>) -> u64 {
        let now = self.shared.env.clock.now();
        let generation = self
            .shared
            .core
            .lock(&mut Unlocked::new_at_entry())
            .set_servers_native(hosts, now);
        self.shared.waker.wake();
        generation
    }

    /// Initiates graceful session closure, queuing `CLOSE_OP` and waking the reactor thread.
    ///
    /// Subsequent close calls are idempotent no-ops.
    pub fn close(&self) {
        self.shared.close();
    }

    /// Blocks the calling thread until the network I/O reactor terminates or the specified timeout
    /// expires.
    ///
    /// Returns the final close return code upon exit, or `None` if the timeout elapsed.
    #[must_use]
    pub fn wait_closed(&self, timeout: std::time::Duration) -> Option<i32> {
        let deadline = Instant::now().checked_add(timeout);
        let mut exit = self.shared.exit.lock_owner();
        loop {
            if let Some(rc) = *exit {
                return Some(rc);
            }
            let left = deadline.map_or(timeout, |d| d.saturating_duration_since(Instant::now()));
            if left == Duration::ZERO {
                return None;
            }
            exit = self.shared.exit_cv.wait_timeout(exit, left).0;
        }
    }

    /// Immediately terminates client operation without a graceful closing handshake.
    ///
    /// Inflight requests are failed immediately with `ZCLOSING`, and the reactor loop terminates.
    pub fn detach(&self) {
        let now = self.shared.env.clock.now();
        let mut proof = Unlocked::new_at_entry();
        {
            let mut core = self.shared.core.lock(&mut proof);
            if self.shared.detached.load(Ordering::Acquire) {
                return;
            }
            if let Some(c) = core.conn.as_deref_mut() {
                c.abandon(now);
            }
            self.shared.close_requested.store(true, Ordering::Release);
            self.shared.detached.store(true, Ordering::Release);
        }
        self.shared.waker.wake();
    }

    /// Notifies the read gate that an item was processed or dropped by the consumer, waking the
    /// reactor if the gate reopens.
    pub fn taken(&self) {
        if self.shared.gate.taken() {
            self.shared.waker.wake();
        }
    }

    /// Allocates a lightweight read gate reporter that tracks event processing without keeping the
    /// session open.
    #[must_use]
    pub fn gate_reporter(&self) -> NativeGate<T> {
        NativeGate {
            shared: Arc::clone(&self.shared),
        }
    }

    /// Wakes the network I/O reactor thread via the self-pipe waker channel.
    pub fn wake(&self) {
        self.shared.waker.wake();
    }

    /// Returns a reference to the read gate governing flow control.
    #[must_use]
    pub fn gate(&self) -> &ReadGate {
        &self.shared.gate
    }

    /// Queries the current numerical connection state code (0 after driver shutdown).
    #[must_use]
    pub fn state(&self) -> i32 {
        read_at_entry(&self.shared.core, |c| c.state().code()).unwrap_or(0)
    }

    /// Retrieves the established client session identifier and credentials.
    #[must_use]
    pub fn client_id(&self) -> ClientId {
        read_at_entry(&self.shared.core, Connection::client_id).unwrap_or_default()
    }

    /// Retrieves the underlying diagnostic cause of the most recent connection disconnect.
    #[must_use]
    pub fn last_teardown_cause(&self) -> Option<Cause> {
        read_at_entry(&self.shared.core, Connection::last_teardown_cause).flatten()
    }

    /// Executes a read-only closure against the underlying connection under the core lock without
    /// blocking.
    pub fn read_conn<R>(&self, f: impl FnOnce(&Connection<T>) -> R) -> Option<R> {
        read_at_entry(&self.shared.core, f)
    }

    /// Executes a mutating closure against the connection under the core lock and signals the
    /// reactor.
    pub fn with_conn<R>(
        &self,
        f: impl FnOnce(&mut Connection<T>, zk_proto::Millis) -> R,
    ) -> Option<R> {
        let r = with_conn_at_entry(&self.shared.core, self.shared.env.clock.now(), f);
        self.shared.waker.wake();
        r
    }
}

impl<T: DriverTokens> NativeShared<T> {
    /// Deferral policy for the native threaded driver: delegates DNS resolution to worker threads.
    pub(crate) const DEFER: DeferPolicy = DeferPolicy::DNS_THREAD;

    /// Driver pump flavor identifying native execution semantics.
    pub(crate) const FLAVOR: Flavor = Flavor::Native;
}

#[cfg(test)]
mod tests {
    #![allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use super::*;
    use crate::host::HostLeaves;
    use crate::testutil::{TestHost, exists_request, native_connection, test_config};
    use zk_proto::{LogLevel, LogRecord, LogSite, PathIn, SaslStep};

    use zk_testkit::tokens::{AuthId, TestTokens};

    /// Test route sink capturing routed items and returning true for counted completion events.
    #[derive(Default)]
    struct Collect(std::sync::Mutex<Vec<String>>);

    impl RouteSink<TestTokens> for Collect {
        fn route(&self, item: Item<TestTokens>) -> bool {
            let counts = matches!(item, Item::Result { .. } | Item::AuthResult { .. });
            self.0.lock().unwrap().push(format!("{item:?}"));
            counts
        }
    }

    impl Collect {
        fn kinds(&self) -> Vec<String> {
            self.0.lock().unwrap().clone()
        }
    }

    /// Test thread spawner that records requested thread names and rejects thread creation for
    /// deterministic unit tests.
    #[derive(Default)]
    struct NoSpawn(std::sync::Mutex<Vec<&'static str>>);

    impl Spawn for NoSpawn {
        fn spawn(&self, name: &'static str, body: ThreadBody) -> std::io::Result<()> {
            self.0.lock().unwrap().push(name);
            drop(body);
            Err(std::io::Error::other("no threads in unit tests"))
        }
    }

    /// Test SASL mechanism recording received server challenges.
    #[derive(Default)]
    struct Mech(Arc<std::sync::Mutex<Vec<Option<Vec<u8>>>>>);

    impl SaslSession for Mech {
        fn start(&mut self) -> Result<(String, SaslStep), SaslError> {
            Ok((
                "PLAIN".into(),
                SaslStep {
                    token: Some(SecretBytes::from_slice(b"hi")),
                    done: false,
                },
            ))
        }

        fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
            self.0.lock().unwrap().push(challenge.map(<[u8]>::to_vec));
            Ok(SaslStep {
                token: None,
                done: true,
            })
        }
    }

    struct Fixture {
        h: NativeHandle<TestTokens>,
        host: Arc<TestHost>,
        sink: Arc<Collect>,
        spawner: Arc<NoSpawn>,
    }

    fn native_env(host: &Arc<TestHost>, capacity: usize) -> Env<TestTokens> {
        let leaves: Arc<dyn HostLeaves<TestTokens>> = host.clone();
        let mut env = Env::new(leaves);
        env.cfg.cloexec = true;
        env.cfg.dispatch_queue_capacity = capacity;
        env
    }

    fn fixture(capacity: usize, sasl: Option<Box<dyn SaslSession>>) -> Fixture {
        let host = Arc::new(TestHost::new());
        let sink = Arc::new(Collect::default());
        let spawner = Arc::new(NoSpawn::default());
        let shared = NativeShared::new(
            native_connection(),
            native_env(&host, capacity),
            sink.clone(),
            sasl,
            spawner.clone(),
        )
        .unwrap();
        Fixture {
            h: NativeHandle::from_shared(shared),
            host,
            sink,
            spawner,
        }
    }

    fn exists(id: u64) -> Request<TestTokens> {
        exists_request(PathIn::Native("/".into()), id)
    }

    /// Verifies whether the self-pipe waker channel has an unconsumed notification pending.
    fn woke(h: &NativeHandle<TestTokens>) -> bool {
        let io = h.shared.io.lock_owner();
        let mut fds = [PollFd::new(&io.wake_rx, PollFlags::IN)];
        let ts = rustix::event::Timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        rustix::event::poll(&mut fds, Some(&ts)).unwrap() == 1
    }

    fn rearm(h: &NativeHandle<TestTokens>) {
        let io = h.shared.io.lock_owner();
        io.wake_rx.reset(&h.shared.waker);
    }

    fn pending(h: &NativeHandle<TestTokens>) -> usize {
        let mut proof = Unlocked::new_at_entry();
        read_conn(&h.shared.core, &mut proof, Connection::pending_len).unwrap()
    }

    /// Executes a closure under the I/O lock, simulating driver reactor operations.
    fn as_io<R>(
        h: &NativeHandle<TestTokens>,
        f: impl FnOnce(
            &NativeShared<TestTokens>,
            &mut Unlocked,
            &mut Outbox<TestTokens>,
            &mut NativeIo,
        ) -> R,
    ) -> R {
        let mut proof = Unlocked::new_at_entry();
        let mut ob = Outbox::new();
        let mut io = h.shared.io.lock_owner();
        f(&h.shared, &mut proof, &mut ob, &mut io)
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn submit_queues_the_request_and_wakes() {
        let f = fixture(8, None);
        assert!(!woke(&f.h));
        let r = f.h.submit(exists(1));
        assert!(matches!(r, Ok(Submitted::Queued { .. })));
        assert!(woke(&f.h));
        assert_eq!(pending(&f.h), 1);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn wakes_coalesce_and_the_reset_rearms() {
        let f = fixture(8, None);
        f.h.wake();
        f.h.wake();
        assert!(woke(&f.h));
        rearm(&f.h);
        assert!(!woke(&f.h));
        f.h.wake();
        assert!(woke(&f.h));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn set_servers_and_add_auth_wake() {
        let f = fixture(8, None);
        let g = f.h.set_servers(test_config(b"127.0.0.2:2181").hosts);
        assert!(g > 0);
        assert!(woke(&f.h));
        rearm(&f.h);
        let r = f.h.add_auth(
            JString::Bytes(b"digest".to_vec()),
            SecretBytes::from_slice(b"u:p"),
            Some(AuthId::new(1)),
        );
        assert!(r.is_ok());
        assert!(woke(&f.h));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn close_sets_the_flags_once() {
        let f = fixture(8, None);
        f.h.close();
        assert!(f.h.shared.close_requested.load(Ordering::Acquire));
        assert!(!f.h.shared.detached.load(Ordering::Acquire));
        // An unconnected session enqueues no CLOSE_OP packet.
        // The final close code resolves to ZOK.
        assert!(!f.h.shared.close_op_queued.load(Ordering::Acquire));
        let mut proof = Unlocked::new_at_entry();
        assert_eq!(
            read_conn(&f.h.shared.core, &mut proof, Connection::is_closing),
            Some(true)
        );
        assert!(woke(&f.h));
        rearm(&f.h);
        f.h.close();
        assert!(!woke(&f.h));
        let e = f.h.submit(exists(2)).err().unwrap();
        assert_eq!(e.code, ZCLOSING);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn the_drop_of_the_last_clone_starts_the_close() {
        let Fixture { h, .. } = fixture(8, None);
        let shared = Arc::clone(&h.shared);
        let other = h.clone();
        drop(h);
        // Active handle clones prevent session closure until all clones are deallocated.
        assert!(!shared.close_requested.load(Ordering::Acquire));
        drop(other);
        assert!(shared.close_requested.load(Ordering::Acquire));
        let mut proof = Unlocked::new_at_entry();
        assert_eq!(
            read_conn(&shared.core, &mut proof, Connection::is_closing),
            Some(true)
        );
        let io = shared.io.lock_owner();
        let mut fds = [PollFd::new(&io.wake_rx, PollFlags::IN)];
        let ts = rustix::event::Timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        assert_eq!(rustix::event::poll(&mut fds, Some(&ts)).unwrap(), 1);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn the_drop_after_a_detach_does_nothing_more() {
        let Fixture { h, .. } = fixture(8, None);
        let shared = Arc::clone(&h.shared);
        h.detach();
        drop(h);
        assert!(shared.detached.load(Ordering::Acquire));
        // Explicit detachment marks the connection abandoned. Subsequent drops perform no
        // additional actions.
        assert!(!shared.close_op_queued.load(Ordering::Acquire));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_close_with_no_session_completes_at_once() {
        let f = fixture(8, None);
        assert_eq!(as_io(&f.h, |s, p, ob, io| s.end_check(p, ob, io)), None);
        f.h.close();
        let rc = as_io(&f.h, |s, p, ob, io| s.end_check(p, ob, io));
        assert_eq!(rc, Some(ZOK));
        assert_eq!(f.h.state(), 0);
        let e = f.h.submit(exists(3)).err().unwrap();
        assert_eq!(e.code, ZCLOSING);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn detach_fails_the_pending_requests_and_exits() {
        let f = fixture(8, None);
        f.h.submit(exists(1)).ok().unwrap();
        f.h.detach();
        assert!(f.h.shared.detached.load(Ordering::Acquire));
        assert!(f.h.shared.close_requested.load(Ordering::Acquire));
        assert!(woke(&f.h));
        let rc = as_io(&f.h, |s, p, ob, io| s.end_check(p, ob, io));
        assert_eq!(rc, Some(ZOK));
        assert!(f.sink.kinds().iter().any(|k| k == "Result"));
        assert_eq!(f.h.gate().count(), 1);
        assert_eq!(f.h.state(), 0);
        assert!(f.host.discarded().is_empty());
        // Subsequent detach and close calls remain idempotent no-ops.
        rearm(&f.h);
        f.h.detach();
        f.h.close();
        assert!(!woke(&f.h));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn route_counts_into_the_gate_until_capacity() {
        let f = fixture(2, None);
        as_io(&f.h, |s, p, ob, io| {
            ob.push(Item::Log(LogRecord {
                site: LogSite::TcpNoDelay,
                level: LogLevel::Warn,
                args: Vec::new(),
            }));
            ob.push(Item::AuthResult {
                token: AuthId::new(1),
                rc: ZOK,
            });
            ob.push(Item::AuthLockpoint);
            ob.push(Item::AuthResult {
                token: AuthId::new(2),
                rc: ZOK,
            });
            s.route(p, ob, io);
            assert!(ob.is_empty());
        });
        assert_eq!(f.sink.kinds(), vec!["Log", "AuthResult", "AuthResult"]);
        assert_eq!(f.h.gate().count(), 2);
        assert!(!f.h.gate().is_open());
        assert!(!woke(&f.h));
        f.h.taken();
        assert!(f.h.gate().is_open());
        assert!(woke(&f.h));
        rearm(&f.h);
        f.h.taken();
        assert!(!woke(&f.h));
        assert_eq!(f.h.gate().count(), 0);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn wait_closed_times_out_then_gives_the_exit_rc() {
        let f = fixture(8, None);
        assert_eq!(f.h.wait_closed(Duration::from_millis(10)), None);
        let shared = Arc::clone(&f.h.shared);
        let t = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(20));
            shared.set_exit(ZCONNECTIONLOSS);
        });
        assert_eq!(
            f.h.wait_closed(Duration::from_secs(5)),
            Some(ZCONNECTIONLOSS)
        );
        t.join().unwrap();
        // Session exit code is write-once. Subsequent terminal transitions are ignored.
        f.h.shared.set_exit(ZOK);
        assert_eq!(f.h.wait_closed(Duration::ZERO), Some(ZCONNECTIONLOSS));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn sasl_items_go_to_the_mechanism() {
        let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
        let f = fixture(8, Some(Box::new(Mech(Arc::clone(&seen)))));
        let take = |h: &NativeHandle<TestTokens>| {
            let mut proof = Unlocked::new_at_entry();
            h.shared.core.lock(&mut proof).sasl.take()
        };
        as_io(&f.h, |s, p, ob, io| {
            ob.push(Item::Sasl(SaslItem {
                call: SaslCall {
                    generation: 4,
                    kind: SaslCallKind::Start,
                },
                addrs: None,
            }));
            s.route(p, ob, io);
        });
        match take(&f.h) {
            Some(SaslAnswer::Started {
                generation: 4,
                result: Ok((name, step)),
            }) => {
                assert_eq!(name, "PLAIN");
                assert_eq!(step.token.unwrap().expose(), b"hi");
            }
            other => panic!("unexpected answer {other:?}"),
        }
        as_io(&f.h, |s, p, ob, io| {
            for kind in [
                SaslCallKind::Step(Some(SecretBytes::from_slice(b"c1"))),
                SaslCallKind::Dispose,
            ] {
                ob.push(Item::Sasl(SaslItem {
                    call: SaslCall {
                        generation: 4,
                        kind,
                    },
                    addrs: None,
                }));
            }
            s.route(p, ob, io);
        });
        assert!(matches!(
            take(&f.h),
            Some(SaslAnswer::Stepped {
                generation: 4,
                result: Ok(SaslStep { done: true, .. })
            })
        ));
        assert_eq!(*seen.lock().unwrap(), vec![Some(b"c1".to_vec())]);
        assert!(f.sink.kinds().is_empty());
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn sasl_with_no_mechanism_is_a_mechanism_error() {
        let f = fixture(8, None);
        as_io(&f.h, |s, p, ob, io| {
            ob.push(Item::Sasl(SaslItem {
                call: SaslCall {
                    generation: 1,
                    kind: SaslCallKind::Start,
                },
                addrs: None,
            }));
            s.route(p, ob, io);
        });
        let mut proof = Unlocked::new_at_entry();
        let answer = f.h.shared.core.lock(&mut proof).sasl.take();
        assert!(matches!(
            answer,
            Some(SaslAnswer::Started {
                generation: 1,
                result: Err(SaslError {
                    rc: ZSYSTEMERROR,
                    ..
                })
            })
        ));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn release_discards_the_tokens_and_ends_the_api() {
        let f = fixture(8, None);
        f.h.submit(exists(1)).ok().unwrap();
        f.h.submit(exists(2)).ok().unwrap();
        as_io(&f.h, |s, p, _, io| s.release(p, io));
        assert_eq!(f.host.discarded(), vec!["completion", "completion"]);
        assert_eq!(f.h.state(), 0);
        assert_eq!(f.h.client_id(), ClientId::default());
        assert_eq!(f.h.submit(exists(3)).err().unwrap().code, ZCLOSING);
        let e =
            f.h.add_auth(
                JString::Bytes(b"x".to_vec()),
                SecretBytes::new(Vec::new()),
                None,
            )
            .err()
            .unwrap();
        assert_eq!(e.code, ZINVALIDSTATE);
        assert_eq!(f.h.set_servers(Vec::new()), 0);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_failed_dns_spawn_gives_the_job_back() {
        let f = fixture(8, None);
        let job = Job::Resolve {
            generation: 1,
            hosts: Vec::new(),
            shuffle: false,
        };
        let back = f.h.shared.start_dns(job);
        assert!(matches!(back, Err(Job::Resolve { generation: 1, .. })));
        assert!(!f.h.shared.dns.lock_owner().running);
        assert_eq!(*f.spawner.0.lock().unwrap(), vec![DNS_THREAD_NAME]);
    }

    /// Mock SASL mechanism tracking connection endpoint notifications.
    #[derive(Default)]
    struct ConnMech(Arc<std::sync::Mutex<Vec<Seen>>>);

    /// Tuple capturing the service hostname, local socket address, and remote peer address.
    type Seen = (Vec<u8>, SocketAddr, SocketAddr);

    impl SaslSession for ConnMech {
        fn start(&mut self) -> Result<(String, SaslStep), SaslError> {
            Ok((
                "X".into(),
                SaslStep {
                    token: None,
                    done: false,
                },
            ))
        }

        fn step(&mut self, _challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
            Err(no_mechanism())
        }

        fn connection(&mut self, host: &[u8], local: SocketAddr, peer: SocketAddr) {
            self.0.lock().unwrap().push((host.to_vec(), local, peer));
        }
    }

    fn resolve_job(generation: u64, servers: &[u8]) -> Job {
        Job::Resolve {
            generation,
            hosts: zk_proto::legacy::connect::parse_servers(servers)
                .unwrap()
                .into_iter()
                .map(HostSpec::Legacy)
                .collect(),
            shuffle: false,
        }
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_resolve_job_records_the_host_of_each_address() {
        let f = fixture(8, Some(Box::new(Mech::default())));
        let a = SocketAddr::from(([10, 0, 0, 1], 2181));
        let b = SocketAddr::from(([10, 0, 0, 2], 2181));
        f.host.set_addrs(b"zk1", vec![a, b]);
        f.host.set_addrs(b"zk2", vec![b]);
        as_io(&f.h, |s, p, _ob, io| {
            let done = s.run_inline(p, io, resolve_job(3, b"zk1:2181,zk2:2181,zk3:2181"));
            assert!(matches!(done, JobDone::Resolved { generation: 3, .. }));
        });
        {
            let hosts = f.h.shared.hosts.lock_owner();
            assert_eq!(hosts.host_of(a), b"zk1");
            // Earlier host mappings take precedence when multiple hostnames resolve to identical
            // addresses.
            assert_eq!(hosts.host_of(b), b"zk1");
            let other = SocketAddr::from(([10, 0, 0, 9], 2181));
            assert_eq!(hosts.host_of(other), b"10.0.0.9");
        }
        // Newer resolution generations overwrite existing records; older generations are ignored.
        f.host.set_addrs(b"zk2", vec![a]);
        as_io(&f.h, |s, p, _ob, io| {
            let _ = s.run_inline(p, io, resolve_job(4, b"zk2:2181"));
            let _ = s.run_inline(p, io, resolve_job(2, b"zk1:2181"));
        });
        let hosts = f.h.shared.hosts.lock_owner();
        assert_eq!(hosts.host_of(a), b"zk2");
        assert_eq!(hosts.host_of(b), b"10.0.0.2");
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_client_with_no_sasl_records_no_hosts() {
        let f = fixture(8, None);
        let a = SocketAddr::from(([10, 0, 0, 1], 2181));
        f.host.set_addrs(b"zk1", vec![a]);
        as_io(&f.h, |s, p, _ob, io| {
            let done = s.run_inline(p, io, resolve_job(3, b"zk1:2181"));
            assert!(matches!(done, JobDone::Resolved { generation: 3, .. }));
        });
        dns_thread(Arc::clone(&f.h.shared), resolve_job(4, b"zk1:2181"));
        assert!(f.h.shared.dns.lock_owner().done.is_some());
        let hosts = f.h.shared.hosts.lock_owner();
        assert!(hosts.entries.is_empty());
        assert_eq!(hosts.generation, None);
        assert_eq!(hosts.host_of(a), b"10.0.0.1");
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn a_dns_thread_records_hosts_with_a_mechanism() {
        let a = SocketAddr::from(([10, 0, 0, 1], 2181));
        let f = fixture(8, Some(Box::new(Mech::default())));
        f.host.set_addrs(b"zk1", vec![a]);
        dns_thread(Arc::clone(&f.h.shared), resolve_job(1, b"zk1:2181"));
        let done = f.h.shared.dns.lock_owner().done.take();
        assert!(matches!(
            done,
            Some(JobDone::Resolved { generation: 1, .. })
        ));
        assert_eq!(f.h.shared.hosts.lock_owner().host_of(a), b"zk1");
    }

    #[test]
    fn the_host_list_is_bounded() {
        let names = vec![b"many".to_vec()];
        let addrs: Vec<SocketAddr> = (0..=SASL_HOSTS_MAX)
            .map(|i| SocketAddr::from(([10, 0, 0, 1], u16::try_from(i + 1).unwrap())))
            .collect();
        let last = *addrs.last().unwrap();
        let done = JobDone::Resolved {
            generation: 1,
            results: vec![zk_proto::HostResolution {
                host: 0,
                result: Ok(addrs),
                skipped_families: Vec::new(),
            }],
        };
        let mut hosts = SaslHosts::default();
        hosts.record(&names, &done);
        assert_eq!(hosts.entries.len(), SASL_HOSTS_MAX);
        assert_eq!(hosts.host_of(last), b"10.0.0.1");
        // Non-resolution jobs provide no hostname mappings.
        let job = Job::Probe {
            endpoint: zk_proto::Endpoint {
                addr: last,
                host: zk_proto::HostToken {
                    index: 0,
                    name: b"many".to_vec(),
                },
            },
            tls: None,
        };
        assert!(SaslHosts::names(&job).is_none());
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
    )]
    fn sasl_start_gives_the_connection_to_the_mechanism() {
        let seen = Arc::new(std::sync::Mutex::new(Vec::new()));
        let f = fixture(8, Some(Box::new(ConnMech(Arc::clone(&seen)))));
        let local = SocketAddr::from(([127, 0, 0, 1], 40000));
        let peer = SocketAddr::from(([10, 0, 0, 1], 2181));
        f.host.set_addrs(b"zk1.example", vec![peer]);
        as_io(&f.h, |s, p, ob, io| {
            let _ = s.run_inline(p, io, resolve_job(1, b"zk1.example:2181"));
            for addrs in [Some((local, peer)), None] {
                ob.push(Item::Sasl(SaslItem {
                    call: SaslCall {
                        generation: 1,
                        kind: SaslCallKind::Start,
                    },
                    addrs,
                }));
                s.route(p, ob, io);
            }
        });
        // START requests lacking socket address metadata bypass connection notification.
        assert_eq!(
            *seen.lock().unwrap(),
            vec![(b"zk1.example".to_vec(), local, peer)]
        );
    }
}
