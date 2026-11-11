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

//! Handle lifecycle management, driver dispatch architectures, and FFI driving execution loops.
//!
//! Session handles represent the core synchronization nexus bridging C clients to Rust driver
//! logic. When a session is initialized via `zkrs_handle_new`, an `Arc<Inner>` instance is
//! allocated and converted into an opaque `zkrs_handle` pointer representing a retained C ownership
//! reference. Background worker threads increment this reference count via `zkrs_handle_retain`.
//! Furthermore, every exported API invocation temporarily clones an additional reference through
//! `with_handle`, guaranteeing that memory remains stable even if session teardown or final
//! destruction is initiated mid-invocation.
//!
//! The [`Inner`] structure manages state transitions, holding intermediate initialization state
//! until connection negotiation completes, whereupon the active [`Driver`] is installed. The driver
//! runtime encapsulates flavor-specific dispatch: `Mt` wraps an `Arc<Shared<CTokens>>` managing
//! multithreaded background I/O and dispatch workers for `libzookeeper_mt`, whereas `St` wraps an
//! `Arc<Polled<CTokens>>` supporting cooperative event-driven loops for `libzookeeper_st`. A single
//! compiled binary artifact serves both library flavors, resolving dispatch dynamically via
//! `zkrs_init_args.flavor`.
//!
//! Common [`Driver`] methods provide a uniform, polymorphic interface across both concurrency
//! models, insulating operational ABI modules from having to inspect or branch upon the active
//! client flavor.

use std::ffi::c_int;
use std::net::SocketAddr;
use std::sync::{Arc, OnceLock};

use zeroize::{Zeroize, Zeroizing};

use zk_driver::net::tls::TlsConnector;
use zk_driver::outbox::Flavor;
use zk_driver::sync::Mutex;
use zk_driver::{
    Env, HostLeaves, Init, InitArgs, InitError, InitStep, InterestOut, Outbox, Polled, RawSock,
    Requeued, Role, Shared, Step, Unlocked,
};
use zk_jute::JString;
use zk_proto::{
    AddAuthError, ClientId, Compat, Config, Connection, DriverMode, Millis, MultiOpRef, Request,
    SecretBytes, SubmitError, Submitted,
};

use super::entry::{abort_with, ffi_guard, with_handle};
use super::host::FfiHost;
use super::outbox::{take_driver_outbox, with_outbox};
use super::raw;
use super::tokens::CTokens;
use super::types::{
    ZKRS_FLAVOR_MT, ZKRS_FLAVOR_ST, ZKRS_INIT_SASL_POINT, ZKRS_ROLE_DISPATCH, ZKRS_ROLE_INIT,
    ZKRS_ROLE_IO, zkrs_handle, zkrs_host_vtable, zkrs_init_args, zkrs_interest_out, zkrs_outbox,
    zkrs_step,
};

/// Concrete driver engine instance backing an active session handle.
pub(crate) enum Driver {
    /// Multithreaded driver runtime backed by dedicated background network I/O and callback
    /// dispatch threads.
    Mt(Arc<Shared<CTokens>>),
    /// Single threaded cooperative driver runtime driven synchronously by application event polling
    /// within `zookeeper_process`.
    St(Arc<Polled<CTokens>>),
}

impl Driver {
    /// Allocates an outbox container for the current driving step. The multithreaded driver routes
    /// events to the dispatch queue, while single threaded mode buffers events locally.
    pub(crate) fn outbox(&self) -> Outbox<CTokens> {
        match self {
            Self::Mt(s) => s.outbox(),
            Self::St(p) => p.outbox(),
        }
    }

    /// Requeues undelivered event items that were left unconsumed by the C outbox drain loop.
    pub(crate) fn requeue(&self, rest: Requeued<CTokens>) {
        match self {
            Self::Mt(s) => s.requeue(rest),
            Self::St(p) => p.requeue(rest),
        }
    }

    /// Retrieves the current session connection state (`zoo_state`), returning 0 if the session has
    /// completed final teardown.
    pub(crate) fn state(&self) -> i32 {
        match self {
            Self::Mt(s) => s.state(),
            Self::St(p) => p.state(),
        }
    }

    /// Returns the system errno recorded during the most recent single threaded execution step, or
    /// 0 for multithreaded drivers.
    pub(crate) fn last_errno(&self) -> i32 {
        match self {
            Self::Mt(_) => 0,
            Self::St(p) => p.last_errno(),
        }
    }

    /// Queries the negotiated session timeout in milliseconds (`zoo_recv_timeout`).
    pub(crate) fn recv_timeout(&self) -> i32 {
        match self {
            Self::Mt(s) => s.recv_timeout(),
            Self::St(p) => p.recv_timeout(),
        }
    }

    /// Submits a request packet with optional borrowed payload data (e.g. znode content for create
    /// or set operations).
    ///
    /// In multithreaded mode, the submission wakes the background I/O worker without populating the
    /// local outbox. In single threaded mode, an immediate nonblocking socket flush pass is
    /// performed, queuing any teardown events directly into `ob` should the flush fail.
    pub(crate) fn submit_with_data(
        &self,
        req: Request<CTokens>,
        data: Option<&[u8]>,
        ob: &mut Outbox<CTokens>,
    ) -> Result<Submitted<CTokens>, SubmitError<CTokens>> {
        match self {
            Self::Mt(s) => s.submit_with_data(req, data),
            Self::St(p) => p.submit_with_data(req, data, ob),
        }
    }

    /// Submits an atomic multi-transaction request referencing an array of borrowed sub-operations.
    ///
    /// Flushing and outbox handling behavior mirrors [`Self::submit_with_data`].
    pub(crate) fn submit_multi_ref(
        &self,
        req: Request<CTokens>,
        ops: &[MultiOpRef<'_>],
        ob: &mut Outbox<CTokens>,
    ) -> Result<Submitted<CTokens>, SubmitError<CTokens>> {
        match self {
            Self::Mt(s) => s.submit_multi_ref(req, ops),
            Self::St(p) => p.submit_multi_ref(req, ops, ob),
        }
    }

    /// Registers authentication credentials (`zoo_add_auth`) to be transmitted to the ensemble upon
    /// connection.
    pub(crate) fn add_auth(
        &self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<<CTokens as zk_proto::Tokens>::Auth>,
    ) -> Result<(), AddAuthError<CTokens>> {
        match self {
            Self::Mt(s) => s.add_auth(scheme, cred, done),
            Self::St(p) => p.add_auth(scheme, cred, done),
        }
    }

    /// Updates the configured ensemble server list (`zoo_set_servers`), yielding `Step::More` at
    /// barriers requiring subsequent `zkrs_resume` continuation.
    pub(crate) fn set_servers(&self, hosts: &[u8], ob: &mut Outbox<CTokens>, rc: &mut i32) -> Step {
        match self {
            Self::Mt(s) => s.set_servers(hosts, ob, rc),
            Self::St(p) => p.set_servers(hosts, ob, rc),
        }
    }

    /// Resolves the socket address of the active remote peer endpoint.
    pub(crate) fn peer_addr(&self) -> Option<SocketAddr> {
        match self {
            Self::Mt(s) => s.peer_addr(),
            Self::St(p) => p.peer_addr(),
        }
    }

    /// Executes a read-only closure against the underlying connection state under synchronization
    /// lock; returns `None` after teardown.
    pub(crate) fn read_conn<R>(&self, f: impl FnOnce(&Connection<CTokens>) -> R) -> Option<R> {
        match self {
            Self::Mt(s) => s.read_conn(f),
            Self::St(p) => p.read_conn(f),
        }
    }

    /// Executes a mutating closure against the connection state under lock, waking the I/O thread
    /// in MT mode; returns `None` after teardown.
    pub(crate) fn with_conn<R>(
        &self,
        f: impl FnOnce(&mut Connection<CTokens>, Millis) -> R,
    ) -> Option<R> {
        match self {
            Self::Mt(s) => s.with_conn(f),
            Self::St(p) => p.with_conn(f),
        }
    }
}

/// Internal synchronized state backing an opaque `zkrs_handle` reference across the C ABI boundary.
pub(crate) struct Inner {
    host: Arc<FfiHost>,
    flavor: u32,
    init: Mutex<Option<Init<CTokens>>>,
    driver: OnceLock<Driver>,
}

impl Inner {
    /// Constructs a fresh handle state container, initializing the driver state to uninstalled.
    pub(crate) fn new(host: Arc<FfiHost>, flavor: u32) -> Self {
        Self {
            host,
            flavor,
            init: Mutex::new(None),
            driver: OnceLock::new(),
        }
    }

    /// Returns a reference to the host foreign function interface abstraction.
    pub(crate) fn host(&self) -> &Arc<FfiHost> {
        &self.host
    }

    /// Returns the runtime flavor constant (`ZKRS_FLAVOR_*`) configured for this handle.
    #[cfg(test)]
    pub(crate) fn flavor(&self) -> u32 {
        self.flavor
    }

    /// Extracts the pending initialization state for step execution, releasing the lock during the
    /// step via `u`.
    pub(crate) fn take_init(&self, u: &mut Unlocked) -> Option<Init<CTokens>> {
        self.init.lock(u).take()
    }

    /// Reinstates the pending initialization state following an incomplete initialization step.
    pub(crate) fn put_init(&self, u: &mut Unlocked, init: Init<CTokens>) {
        *self.init.lock(u) = Some(init);
    }

    /// Transitions the handle from initialization to active runtime by installing the completed
    /// driver.
    pub(crate) fn install(&self, d: Driver) -> Result<(), Driver> {
        self.driver.set(d)
    }

    /// Borrows the active driver instance, or returns `None` while initialization is still
    /// underway.
    pub(crate) fn driver(&self) -> Option<&Driver> {
        self.driver.get()
    }
}

/// Converts an ABI thread role constant (`ZKRS_ROLE_*`) into the internal driver `Role`
/// representation.
pub(crate) fn role_of(role: u32) -> Role {
    match role {
        ZKRS_ROLE_INIT => Role::Init,
        ZKRS_ROLE_IO => Role::Io,
        ZKRS_ROLE_DISPATCH => Role::Dispatch,
        _ => Role::Foreign,
    }
}

/// Translates an internal driver execution step into the ABI `zkrs_step` status code, updating
/// `*rc` upon completion.
///
/// Output return codes are recorded strictly when reaching `ZKRS_DONE`; for `ZKRS_RELEASE`,
/// [`finish_step`] records the teardown status.
pub(crate) fn step_out(s: Step, rc: *mut c_int) -> zkrs_step {
    match s {
        Step::Done(r) => {
            raw::write_out(rc, r);
            zkrs_step::ZKRS_DONE
        }
        Step::More => zkrs_step::ZKRS_MORE,
        Step::Release => zkrs_step::ZKRS_RELEASE,
        Step::Exit => zkrs_step::ZKRS_EXIT,
    }
}

/// Verifies that handle initialization has completed and returns the active driver, aborting if
/// invoked prematurely.
pub(crate) fn require_driver(h: &Inner) -> &Driver {
    match h.driver() {
        Some(d) => d,
        None => abort_with("zk-ffi: driving call before the init finished"),
    }
}

/// Extracts the multithreaded driver runtime, aborting if invoked against a single threaded client
/// handle.
fn require_mt(h: &Inner) -> &Arc<Shared<CTokens>> {
    match require_driver(h) {
        Driver::Mt(s) => s,
        Driver::St(_) => abort_with("zk-ffi: an mt-only call on an st handle"),
    }
}

/// Normalizes an optional system error code into a non-zero failure errno, mapping missing or zero
/// values to `EINVAL`.
fn failure_errno(errno: Option<i32>) -> i32 {
    match errno {
        Some(e) if e != 0 => e,
        _ => libc::EINVAL,
    }
}

/// Finalizes a driver execution step, zeroing host vtable bindings and recording the close status
/// on terminal release.
fn finish_step(i: &Inner, s: Step, close_rc: i32, rc: *mut c_int) -> zkrs_step {
    if s == Step::Release {
        i.host().zero();
        raw::write_out(rc, close_rc);
        return zkrs_step::ZKRS_RELEASE;
    }
    step_out(s, rc)
}

/// Dispatches a driver execution step with the provided outbox context, finalizing the step via
/// [`finish_step`].
fn drive(
    i: &Arc<Inner>,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
    f: impl FnOnce(&Driver, &mut Outbox<CTokens>, &mut i32) -> Step,
) -> zkrs_step {
    let d = require_driver(i);
    let mut close_rc: i32 = 0;
    let step = with_outbox(ob, i, |o| f(d, o, &mut close_rc));
    finish_step(i, step, close_rc, rc)
}

/// Dispatches a multithreaded driver execution step; see [`require_mt`].
fn drive_mt(
    i: &Arc<Inner>,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
    f: impl FnOnce(&Shared<CTokens>, &mut Outbox<CTokens>, &mut i32) -> Step,
) -> zkrs_step {
    let s = require_mt(i);
    drive(i, ob, rc, |_, o, r| f(s, o, r))
}

/// Executes an incremental initialization step, installing the completed driver once ready.
///
/// Incomplete or suspended initialization contexts are reinstated into the handle to be continued
/// by `zkrs_resume` or reclaimed via `zkrs_handle_abandon`. Because handle identity dictates
/// execution rather than resume tags, any tag present in the outbox is consumed prior to dispatch.
fn init_step(
    i: &Arc<Inner>,
    mut init: Init<CTokens>,
    u: &mut Unlocked,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    let step = with_outbox(ob, i, |o| {
        let _tag = o.take_resume();
        match init.step(u, o) {
            InitStep::More => {
                i.put_init(u, init);
                None
            }
            InitStep::SaslPoint => {
                i.put_init(u, init);
                Some(ZKRS_INIT_SASL_POINT)
            }
            InitStep::Ready => match make_driver(i.flavor, init, o) {
                Ok(d) => {
                    if i.install(d).is_err() {
                        abort_with("zk-ffi: the handle has a driver already");
                    }
                    Some(0)
                }
                Err(e) => Some(failure_errno(Some(e))),
            },
            InitStep::Failed(InitError { errno }) => {
                i.put_init(u, init);
                Some(failure_errno(errno))
            }
        }
    });
    match step {
        None => zkrs_step::ZKRS_MORE,
        Some(ZKRS_INIT_SASL_POINT) => {
            raw::write_out(rc, ZKRS_INIT_SASL_POINT);
            zkrs_step::ZKRS_MORE
        }
        Some(r) => step_out(Step::Done(r), rc),
    }
}

/// Constructs the flavor-specific driver instance upon successful completion of session handshake
/// negotiation.
///
/// Multithreaded driver creation allocates socket wakers and may return an initialization errno;
/// single threaded drivers cannot fail.
fn make_driver(flavor: u32, init: Init<CTokens>, o: &mut Outbox<CTokens>) -> Result<Driver, i32> {
    if flavor == ZKRS_FLAVOR_ST {
        return Ok(Driver::St(Arc::new(Polled::from_init(init))));
    }
    Shared::from_init(init, o).map(Driver::Mt)
}

/// Instantiates a new client session handle and advances initialization up to the initial execution
/// barrier.
///
/// Copies input configurations and host dispatch vtables across the ABI boundary using safe memory
/// readers, constructing `InitArgs` configured for legacy compatibility mode. Path parsing errors
/// during chroot evaluation leave `*out` null and record the failure status in `*rc`. Otherwise,
/// `*out` is populated before encountering the first barrier. Suspended initialization returns
/// `ZKRS_MORE` for subsequent resumption via `zkrs_resume`. Upon reaching `InitStep::Ready`, the
/// appropriate driver variant (`Shared` for MT or `Polled` for ST) is installed. Failures occurring
/// after handle creation require deallocation via `zkrs_handle_abandon`. Builds lacking TLS or SASL
/// reject corresponding configuration flags with `EINVAL`.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_handle_new(
    a: *const zkrs_init_args,
    vt: *const zkrs_host_vtable,
    host: usize,
    out: *mut *mut zkrs_handle,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    ffi_guard(|| {
        raw::write_out(out, std::ptr::null_mut());
        let mut args = raw::read_struct(a);
        // Ensure sensitive session authentication passwords are zeroized from stack memory across
        // all execution paths.
        let passwd = Zeroizing::new(args.client_passwd);
        args.client_passwd.zeroize();
        let table = raw::read_struct(vt);
        // A single binary serves both client flavors; unrecognized variants indicate an ABI
        // specification defect.
        let (mode, flavor) = match args.flavor {
            ZKRS_FLAVOR_MT => (DriverMode::MT, Flavor::Mt),
            ZKRS_FLAVOR_ST => (DriverMode::ST, Flavor::St),
            _ => abort_with("zk-ffi: no driver for the flavor"),
        };
        // Bitmask flag corresponding to `ZOO_READONLY` in `zookeeper_init`.
        const ZOO_READONLY: i32 = 1;
        let mut cfg = Config::legacy(args.recv_timeout);
        if args.have_client_id != 0 {
            cfg.client_id = ClientId {
                session_id: args.client_id,
                passwd: *passwd,
            };
        }
        cfg.allow_read_only = args.flags & ZOO_READONLY != 0;
        cfg.xid_seed = args.xid_seed;
        cfg.c_char_signed = args.c_char_signed != 0;
        // Setup failures terminate initialization prior to handle allocation; C deallocates its
        // wrapper and returns NULL.
        let tls = match tls_setup(&args, &mut cfg) {
            Ok(t) => t,
            Err(e) => return step_out(Step::Done(e), rc),
        };
        if let Err(e) = super::sasl::setup(&args, &mut cfg) {
            return step_out(Step::Done(e), rc);
        }
        let init_args = InitArgs {
            cfg,
            host_string: raw::cstr_opt(args.hosts).unwrap_or_default(),
            compat: Compat::LEGACY_C,
            mode,
            flavor,
        };
        let ffi_host = Arc::new(FfiHost::new(table, host));
        let leaves: Arc<dyn HostLeaves<CTokens>> = ffi_host.clone();
        let mut env = Env::new(leaves);
        env.tls = tls;
        env.cfg.cloexec = args.sock_cloexec != 0;
        let inner = Arc::new(Inner::new(ffi_host, args.flavor));
        let mut u = Unlocked::new_at_entry();
        match with_outbox(ob, &inner, |o| Init::new(init_args, env, o)) {
            // Path errors in the chroot specification fail before handle pointer allocation.
            Err(InitError { errno }) => step_out(Step::Done(failure_errno(errno)), rc),
            Ok(init) => {
                let p = raw::handle_into_raw(Arc::clone(&inner));
                raw::write_out(out, p.cast_mut());
                init_step(&inner, init, &mut u, ob, rc)
            }
        }
    })
}

/// Configures TLS transport connectors when SSL encryption is requested via `a->tls`.
#[cfg(feature = "tls")]
fn tls_setup(a: &zkrs_init_args, cfg: &mut Config) -> Result<Option<Arc<dyn TlsConnector>>, c_int> {
    if a.tls == 0 {
        return Ok(None);
    }
    super::tls::setup(a, cfg, Compat::LEGACY_C.tls_verify).map(Some)
}

/// Rejects TLS initialization requests with `EINVAL` when compiling in plaintext-only builds
/// without TLS support.
#[cfg(not(feature = "tls"))]
fn tls_setup(a: &zkrs_init_args, cfg: &mut Config) -> Result<Option<Arc<dyn TlsConnector>>, c_int> {
    let _ = cfg;
    if a.tls == 0 {
        Ok(None)
    } else {
        Err(libc::EINVAL)
    }
}

/// Increments the handle's reference count, allowing background worker threads to retain handle
/// ownership.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_handle_retain(h: *const zkrs_handle) {
    ffi_guard(|| std::mem::forget(raw::handle_arc(h)));
}

/// Decrements the handle's reference count, reclaiming underlying resources when the final
/// reference is released.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_handle_release(h: *const zkrs_handle) {
    ffi_guard(|| raw::handle_release(h));
}

/// Aborts an uncompleted initialization sequence, terminating socket wakers and releasing pending
/// request tokens.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_handle_abandon(h: *const zkrs_handle) {
    ffi_guard(|| {
        let i = raw::handle_arc(h);
        let mut u = Unlocked::new_at_entry();
        if let Some(init) = i.take_init(&mut u) {
            init.abandon();
        }
        // A newly initialized driver holds no C tokens and is dropped when the final reference
        // expires.
        i.host().zero();
        drop(i);
        raw::handle_release(h);
    })
}

/// Executes thread initialization prologue for background worker threads or the main initialization
/// thread (MT only).
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_thread_enter(h: *const zkrs_handle, role: u32) {
    with_handle(h, |i, _| require_mt(i).thread_enter(role_of(role)));
}

/// Executes thread termination epilogue, potentially triggering final teardown on the exiting
/// thread (MT only).
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_thread_exit(
    h: *const zkrs_handle,
    role: u32,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| {
        drive_mt(i, ob, rc, |s, o, r| s.thread_exit(role_of(role), o, r))
    })
}

/// Cleanup handler invoked if the asynchronous dispatch worker thread terminates abnormally (e.g.
/// via `pthread_exit`).
///
/// Deallocates the current outbox batch without invoking callbacks, marks the dispatch loop as
/// finished, and notifies any close operations awaiting dispatch queue drain (MT only).
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_dispatch_ended(h: *const zkrs_handle, ob: *mut zkrs_outbox) {
    with_handle(h, |i, _| {
        let s = require_mt(i);
        let mut left = take_driver_outbox(ob).unwrap_or_default();
        s.dispatch_ended(&mut left);
    });
}

/// Advances multithreaded network I/O processing through interest evaluation, socket polling, and
/// packet marshaling.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_io_step(
    h: *const zkrs_handle,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| drive_mt(i, ob, rc, |s, o, r| s.io_step(o, r)))
}

/// Blocks until the asynchronous callback dispatch queue contains pending items or the dispatch
/// loop terminates (MT only).
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_wait_dispatch(
    h: *const zkrs_handle,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| drive_mt(i, ob, rc, |s, o, _| s.wait_dispatch(o)))
}

/// Evaluates network socket readiness interest (`zookeeper_interest`).
///
/// In multithreaded mode, writes a synthetic socket view indicating no direct socket ownership and
/// returns `ZKRS_DONE`. In single threaded mode, advances connection state via `Polled::interest`,
/// yielding `ZKRS_MORE` across barriers to be resumed by `zkrs_interest_resume`. The final
/// completed step writes socket descriptors and deadline timeouts to `*out`.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_interest(
    h: *const zkrs_handle,
    out: *mut zkrs_interest_out,
    written: *mut c_int,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| match i.driver() {
        // Multithreaded handles delegate socket polling entirely to the background I/O thread.
        Some(Driver::Mt(s)) => {
            let io = s.interest_exported();
            let step = step_out(Step::Done(io.rc), rc);
            write_interest(step, &io, out, written);
            step
        }
        Some(Driver::St(p)) => st_interest(i, p, out, written, ob, rc),
        None => abort_with("zk-ffi: driving call before the init finished"),
    })
}

/// Processes pending network socket I/O events (`zookeeper_process`).
///
/// In multithreaded mode, returns `ZINVALIDSTATE` as I/O processing occurs concurrently on
/// dedicated worker threads. In single threaded mode, performs socket reads and writes based on the
/// provided event mask, yielding `ZKRS_MORE` so that C callers can drain and dispatch outbox
/// callbacks inline before continuing via `zkrs_resume`.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_process(
    h: *const zkrs_handle,
    events: i32,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| match i.driver() {
        Some(Driver::Mt(s)) => step_out(Step::Done(s.process_exported()), rc),
        Some(Driver::St(p)) => {
            let events = u32::try_from(events).unwrap_or(0);
            drive(i, ob, rc, |_, o, r| p.process(events, o, r))
        }
        None => abort_with("zk-ffi: driving call before the init finished"),
    })
}

/// Initiates session teardown and connection closure (`zookeeper_close`).
///
/// In multithreaded mode, closing from an external application thread initiates full shutdown,
/// whereas worker threads defer shutdown steps. Thread lifecycle transitions emit barrier items,
/// concluding with `ZKRS_RELEASE`. In single threaded mode, shutdown executes synchronously,
/// deferring final teardown to epilogue handling if invoked within a callback.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_close(
    h: *const zkrs_handle,
    caller_role: u32,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| {
        drive(i, ob, rc, |d, o, r| match d {
            Driver::Mt(s) => s.close(role_of(caller_role), o, r),
            Driver::St(p) => p.close(o, r),
        })
    })
}

/// Resumes execution of a driving operation suspended at a synchronization barrier.
///
/// Steps pending initialization sequences while handshake negotiation is active; thereafter
/// dispatches to `Shared::resume` or `Polled::resume` based on the active client flavor.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_resume(
    h: *const zkrs_handle,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, u| match i.take_init(u) {
        Some(init) => init_step(i, init, u, ob, rc),
        None => drive(i, ob, rc, |d, o, r| match d {
            Driver::Mt(s) => s.resume(o, r),
            Driver::St(p) => st_resume(p, o, r),
        }),
    })
}

/// Resumes execution of a single threaded `zkrs_interest` evaluation suspended at an execution
/// barrier.
///
/// Restricted to single threaded client handles; continues polling evaluation and marshals socket
/// readiness outputs.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_interest_resume(
    h: *const zkrs_handle,
    out: *mut zkrs_interest_out,
    written: *mut c_int,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    with_handle(h, |i, _| match i.driver() {
        Some(Driver::Mt(_)) => abort_with("zk-ffi: zkrs_interest_resume on an mt handle"),
        Some(Driver::St(p)) => st_interest_resume(i, p, out, written, ob, rc),
        None => abort_with("zk-ffi: driving call before the init finished"),
    })
}

/// Retrieves the system errno recorded during the preceding `zookeeper_interest` or
/// `zookeeper_process` execution.
///
/// Reflects the underlying failure reason (such as `ECONNREFUSED` or `ETIMEDOUT`) in
/// single threaded event loops; returns 0 if no failure occurred or when invoked on multithreaded
/// handles.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_last_errno(h: *const zkrs_handle) -> c_int {
    with_handle(h, |i, _| i.driver().map_or(0, Driver::last_errno))
}

// single threaded driver execution pathways.
//
// Each function advances a single `Polled` execution step using the outbox provided for the call.
// Execution yields at each barrier, ensuring no Rust stack frames remain active when C invokes user
// callback routines, thus preventing foreign cross-language unwinding into Rust. The C caller
// drains the outbox and invokes `zkrs_resume` or `zkrs_interest_resume` to continue. Because
// `Polled` encapsulates the active execution stage internally, these entry points maintain no
// external state across calls.

/// Executes a single threaded socket interest evaluation step.
///
/// Dispatches `Polled::interest` through `drive`. Steps populating `InterestOut::written` write
/// socket descriptors, event masks, and timeout deadlines into `*out` with `*written = 1`.
/// Completed or released steps without new outputs set `*written = 0` to preserve caller values.
fn st_interest(
    i: &Arc<Inner>,
    p: &Polled<CTokens>,
    out: *mut zkrs_interest_out,
    written: *mut c_int,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    let mut io = InterestOut::UNWRITTEN;
    let step = drive(i, ob, rc, |_, o, r| p.interest(o, &mut io, r));
    write_interest(step, &io, out, written);
    step
}

/// Resumes a suspended single threaded socket interest evaluation step following outbox draining.
fn st_interest_resume(
    i: &Arc<Inner>,
    p: &Polled<CTokens>,
    out: *mut zkrs_interest_out,
    written: *mut c_int,
    ob: *mut zkrs_outbox,
    rc: *mut c_int,
) -> zkrs_step {
    let mut io = read_interest(out.cast_const(), written.cast_const());
    let step = drive(i, ob, rc, |_, o, r| p.resume(o, &mut io, r));
    write_interest(step, &io, out, written);
    step
}

/// Resumes a suspended single threaded driver execution step following outbox draining.
///
/// Because interest evaluation uses `st_interest_resume`, standard resumption provides a scratch
/// output container.
fn st_resume(p: &Polled<CTokens>, o: &mut Outbox<CTokens>, r: &mut i32) -> Step {
    let mut scratch = InterestOut::UNWRITTEN;
    p.resume(o, &mut scratch, r)
}

/// Marshals internal driver socket interest outputs into ABI-compatible `zkrs_interest_out`
/// structures.
fn interest_view(io: &InterestOut) -> zkrs_interest_out {
    zkrs_interest_out {
        fd: isize::try_from(io.fd).unwrap_or(-1),
        interest: i32::try_from(io.bits).unwrap_or(0),
        timeout_ms: io.timeout_ms,
    }
}

/// Writes resolved socket interest outputs and readiness flags across the ABI boundary into C
/// memory.
fn write_interest(
    step: zkrs_step,
    io: &InterestOut,
    out: *mut zkrs_interest_out,
    written: *mut c_int,
) {
    if io.written {
        raw::write_out(out, interest_view(io));
        raw::write_out(written, 1);
    } else if step != zkrs_step::ZKRS_MORE {
        raw::write_out(written, 0);
    }
}

/// Recovers previously written socket interest parameters from C local variables across resumed
/// execution steps.
fn read_interest(out: *const zkrs_interest_out, written: *const c_int) -> InterestOut {
    if raw::read_struct(written) != 1 {
        return InterestOut::UNWRITTEN;
    }
    let v = raw::read_struct(out);
    InterestOut {
        rc: 0,
        written: true,
        fd: RawSock::try_from(v.fd).unwrap_or(-1),
        bits: u32::try_from(v.interest).unwrap_or(0),
        timeout_ms: v.timeout_ms,
    }
}

#[cfg(test)]
pub(crate) mod test_support {
    //! Unit test fixtures and mock environments providing synthetic host vtables, default
    //! initialization parameters, and empty outboxes.

    use std::ffi::CStr;

    use super::*;
    use crate::abi::standin;
    use crate::abi::types::ZKRS_FLAVOR_MT;

    /// Allocates a mock handle reference configured with the synthetic test host vtable.
    pub(crate) fn new_handle() -> *const zkrs_handle {
        let host = Arc::new(FfiHost::new(standin::vtable(), 0x55));
        raw::handle_into_raw(Arc::new(Inner::new(host, ZKRS_FLAVOR_MT)))
    }

    /// Generates baseline initialization arguments for a plain multithreaded client targeting
    /// `hosts`.
    pub(crate) fn init_args(hosts: &CStr) -> zkrs_init_args {
        zkrs_init_args {
            flavor: ZKRS_FLAVOR_MT,
            hosts: hosts.as_ptr(),
            recv_timeout: 30_000,
            have_client_id: 0,
            client_id: 0,
            client_passwd: [0; 16],
            flags: 0,
            tls: 0,
            tls_ca: std::ptr::null(),
            tls_cert: std::ptr::null(),
            tls_key: std::ptr::null(),
            tls_passwd: std::ptr::null(),
            tls_env_allowed: 0,
            sasl: 0,
            xid_seed: 1,
            sock_cloexec: 1,
            c_char_signed: 1,
        }
    }

    /// Constructs an unallocated outbox structure with a null implementation pointer.
    pub(crate) fn empty_outbox() -> zkrs_outbox {
        zkrs_outbox {
            r#impl: std::ptr::null_mut(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::test_support::{empty_outbox, init_args, new_handle};
    use super::*;

    #[test]
    fn retain_and_release_balance() {
        let _rec = crate::abi::standin::recorder();
        let h = new_handle();
        zkrs_handle_retain(h);
        let count = with_handle(h, |i, _| Arc::strong_count(i));
        assert_eq!(count, 3);
        zkrs_handle_release(h);
        let (count, flavor, ready) = with_handle(h, |i, _| {
            (Arc::strong_count(i), i.flavor(), i.driver().is_some())
        });
        assert_eq!(
            (count, flavor, ready),
            (2, crate::abi::types::ZKRS_FLAVOR_MT, false)
        );
        zkrs_handle_release(h);
    }

    #[test]
    fn steps_map_to_the_c_enum() {
        let mut rc: c_int = 0;
        assert_eq!(step_out(Step::Done(-4), &raw mut rc), zkrs_step::ZKRS_DONE);
        assert_eq!(rc, -4);
        assert_eq!(step_out(Step::More, &raw mut rc), zkrs_step::ZKRS_MORE);
        assert_eq!(role_of(ZKRS_ROLE_IO), Role::Io);
        assert_eq!(role_of(99), Role::Foreign);
    }

    use std::ffi::CString;

    use crate::abi::standin;
    use crate::abi::types::{
        ZKRS_FLAVOR_MT, ZKRS_FLAVOR_ST, ZKRS_ROLE_FOREIGN, zkrs_item, zkrs_sockaddr_view,
    };

    /// Legacy `ZINVALIDSTATE` error constant.
    const ZINVALIDSTATE: c_int = -9;

    /// Invokes `zkrs_handle_new`, returning a tuple containing the resulting execution step, handle
    /// pointer, and status code.
    fn handle_new(
        a: &zkrs_init_args,
        ob: &mut zkrs_outbox,
    ) -> (zkrs_step, *mut zkrs_handle, c_int) {
        let vt = standin::vtable();
        let mut out: *mut zkrs_handle = std::ptr::dangling_mut();
        let mut rc: c_int = 12_345;
        let s = zkrs_handle_new(a, &raw const vt, 0x55, &raw mut out, ob, &raw mut rc);
        (s, out, rc)
    }

    /// Deallocates the backing storage of `ob`, releasing buffered items and any attached resume
    /// tags.
    fn drop_box(ob: &mut zkrs_outbox) {
        drop(raw::outbox_take(ob));
    }

    /// Drives initialization to completion, draining and dropping outbox items across intermediate
    /// barriers.
    fn ready(hosts: &str) -> *mut zkrs_handle {
        let hosts = CString::new(hosts).unwrap_or_default();
        let a = init_args(&hosts);
        let mut ob = empty_outbox();
        let (mut s, h, mut rc) = handle_new(&a, &mut ob);
        assert!(!h.is_null());
        while s == zkrs_step::ZKRS_MORE {
            drop_box(&mut ob);
            s = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        drop_box(&mut ob);
        assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, 0));
        h
    }

    fn loopback() -> zkrs_sockaddr_view {
        zkrs_sockaddr_view::from_addr("127.0.0.1:2181".parse().ok())
    }

    #[test]
    fn handle_new_installs_the_mt_driver() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let h = ready("127.0.0.1:2181,localhost:2182");
        let (ready, flavor) = with_handle(h, |i, _| (i.driver().is_some(), i.flavor()));
        assert_eq!((ready, flavor), (true, ZKRS_FLAVOR_MT));
        assert_eq!(
            standin::resolved(),
            vec![
                (b"127.0.0.1".to_vec(), b"2181".to_vec()),
                (b"localhost".to_vec(), b"2182".to_vec()),
            ]
        );
        zkrs_handle_release(h);
    }

    #[test]
    fn handle_new_installs_the_st_driver() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let hosts = CString::new("127.0.0.1:2181").unwrap_or_default();
        let mut a = init_args(&hosts);
        a.flavor = ZKRS_FLAVOR_ST;
        let mut ob = empty_outbox();
        let (mut s, h, mut rc) = handle_new(&a, &mut ob);
        assert!(!h.is_null());
        while s == zkrs_step::ZKRS_MORE {
            drop_box(&mut ob);
            s = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        drop_box(&mut ob);
        assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, 0));
        let (st, flavor, state) = with_handle(h, |i, _| {
            let st = matches!(i.driver(), Some(Driver::St(_)));
            (st, i.flavor(), i.driver().map_or(-1, Driver::state))
        });
        // The handle state reflects uninitialized not-connected status until the initial interest
        // evaluation.
        assert_eq!((st, flavor, state), (true, ZKRS_FLAVOR_ST, 999));
        // Prior to network activity, no C tokens or sockets are held; abandonment safely dismantles
        // the handle without final teardown.
        zkrs_handle_retain(h);
        zkrs_handle_abandon(h);
        zkrs_handle_release(h);
    }

    /// Verifies that thread initialization failure following driver installation allows safe handle
    /// abandonment without aborting.
    #[test]
    fn abandon_after_a_ready_init_ends_the_handle() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let h = ready("127.0.0.1:2181");
        zkrs_thread_enter(h, ZKRS_ROLE_INIT);
        zkrs_handle_retain(h);
        zkrs_handle_release(h);
        zkrs_handle_abandon(h);
    }

    #[test]
    fn handle_new_rejects_an_empty_host_list() {
        let _rec = standin::recorder();
        for text in [",", ",,"] {
            let hosts = CString::new(text).unwrap_or_default();
            let mut ob = empty_outbox();
            let (s, h, rc) = handle_new(&init_args(&hosts), &mut ob);
            drop_box(&mut ob);
            assert_eq!(
                (s, h.is_null(), rc),
                (zkrs_step::ZKRS_DONE, true, libc::EINVAL)
            );
        }
        assert!(standin::resolved().is_empty());
    }

    #[test]
    fn handle_new_leaves_out_null_on_a_bad_chroot() {
        let _rec = standin::recorder();
        let hosts = CString::new("127.0.0.1:2181/bad/").unwrap_or_default();
        let mut ob = empty_outbox();
        let (s, h, rc) = handle_new(&init_args(&hosts), &mut ob);
        drop_box(&mut ob);
        assert_eq!(
            (s, h.is_null(), rc),
            (zkrs_step::ZKRS_DONE, true, libc::EINVAL)
        );
        assert!(standin::resolved().is_empty());
    }

    #[test]
    fn handle_new_takes_tls_and_sasl_only_in_their_builds() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let hosts = CString::new("127.0.0.1:2181").unwrap_or_default();
        let mut tls = init_args(&hosts);
        tls.tls = 1;
        let mut sasl = init_args(&hosts);
        sasl.sasl = 1;
        for (a, built) in [(tls, cfg!(feature = "tls")), (sasl, cfg!(feature = "sasl"))] {
            let mut ob = empty_outbox();
            let (mut s, h, mut rc) = handle_new(&a, &mut ob);
            if !built {
                // Assert that builds lacking the optional feature reject activation flags with an
                // invalid argument error.
                assert_eq!(
                    (s, h.is_null(), rc),
                    (zkrs_step::ZKRS_DONE, true, libc::EINVAL)
                );
                assert!(ob.r#impl.is_null());
                continue;
            }
            // In feature-enabled builds, initialization succeeds; SASL handshakes pause at the
            // negotiation barrier before completing.
            assert!(!h.is_null());
            let mut sasl_points = 0u32;
            while s == zkrs_step::ZKRS_MORE {
                if rc == ZKRS_INIT_SASL_POINT {
                    sasl_points = sasl_points.saturating_add(1);
                }
                drop_box(&mut ob);
                s = zkrs_resume(h, &raw mut ob, &raw mut rc);
            }
            drop_box(&mut ob);
            assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, 0));
            assert_eq!(sasl_points, u32::from(a.sasl == 1));
            zkrs_handle_release(h);
        }
    }

    #[test]
    fn a_failed_resolution_gives_the_errno_and_abandon_ends_it() {
        let _rec = standin::recorder();
        standin::set_resolve(Vec::new(), Vec::new(), libc::ENOENT, -2);
        let hosts = CString::new("nohost1:2181,nohost2:2181").unwrap_or_default();
        let mut ob = empty_outbox();
        let (mut s, h, mut rc) = handle_new(&init_args(&hosts), &mut ob);
        assert!(!h.is_null());
        // Emitted diagnostic errors for the initial unreachable host introduce an execution barrier
        // before the secondary host is evaluated.
        let mut barriers = 0u32;
        while s == zkrs_step::ZKRS_MORE {
            barriers = barriers.saturating_add(1);
            drop_box(&mut ob);
            s = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        drop_box(&mut ob);
        assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, libc::ENOENT));
        assert!(barriers >= 1);
        assert_eq!(standin::resolved().len(), 2);
        zkrs_handle_retain(h);
        zkrs_handle_abandon(h);
        let (count, zero, init, ready) = with_handle(h, |i, u| {
            (
                Arc::strong_count(i),
                i.host().is_zero(),
                i.take_init(u).is_some(),
                i.driver().is_some(),
            )
        });
        assert_eq!((count, zero, init, ready), (2, true, false, false));
        // Because initialization was aborted before completion, no active request tokens were
        // registered for reclamation.
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
    }

    #[test]
    fn abandon_at_a_barrier_discards_the_init() {
        let _rec = standin::recorder();
        standin::set_resolve(Vec::new(), Vec::new(), libc::ENOENT, -2);
        let hosts = CString::new("nohost1:2181,nohost2:2181").unwrap_or_default();
        let mut ob = empty_outbox();
        let (s, h, _) = handle_new(&init_args(&hosts), &mut ob);
        assert_eq!(s, zkrs_step::ZKRS_MORE);
        assert!(!ob.r#impl.is_null());
        drop_box(&mut ob);
        zkrs_handle_retain(h);
        zkrs_handle_abandon(h);
        let (count, zero, init) = with_handle(h, |i, u| {
            (
                Arc::strong_count(i),
                i.host().is_zero(),
                i.take_init(u).is_some(),
            )
        });
        assert_eq!((count, zero, init), (2, true, false));
        assert!(standin::discarded().is_empty());
        zkrs_handle_release(h);
    }

    #[test]
    fn mt_interest_and_process_give_the_exported_values() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let h = ready("127.0.0.1:2181");
        let mut ob = empty_outbox();
        let mut out = zkrs_interest_out {
            fd: 7,
            interest: 7,
            timeout_ms: 7,
        };
        let mut written: c_int = 0;
        let mut rc: c_int = 1;
        let s = zkrs_interest(h, &raw mut out, &raw mut written, &raw mut ob, &raw mut rc);
        assert_eq!((s, rc, written), (zkrs_step::ZKRS_DONE, 0, 1));
        assert_eq!(
            out,
            zkrs_interest_out {
                fd: -1,
                interest: 0,
                timeout_ms: 10_000,
            }
        );
        let s = zkrs_process(h, 3, &raw mut ob, &raw mut rc);
        assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, ZINVALIDSTATE));
        assert!(ob.r#impl.is_null());
        zkrs_handle_release(h);
    }

    #[test]
    fn the_init_thread_enters_and_leaves() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let h = ready("127.0.0.1:2181");
        let mut ob = empty_outbox();
        let mut rc: c_int = 1;
        zkrs_thread_enter(h, ZKRS_ROLE_INIT);
        let s = zkrs_thread_exit(h, ZKRS_ROLE_INIT, &raw mut ob, &raw mut rc);
        drop_box(&mut ob);
        assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, 0));
        zkrs_handle_release(h);
    }

    /// Drains all queued items from `ob` and finalizes the drain sequence, mirroring `zks_drain`.
    fn drain(ob: &mut zkrs_outbox) {
        let mut it = zkrs_item::empty();
        while crate::abi::outbox::zkrs_outbox_next(ob, &raw mut it) == 1 {}
        crate::abi::outbox::zkrs_outbox_finish(ob);
    }

    #[test]
    fn an_app_close_with_no_threads_releases_the_handle() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let h = ready("127.0.0.1:2181");
        zkrs_handle_retain(h);
        let mut ob = empty_outbox();
        let mut rc: c_int = 1;
        let mut s = zkrs_close(h, ZKRS_ROLE_FOREIGN, &raw mut ob, &raw mut rc);
        while s == zkrs_step::ZKRS_MORE {
            drain(&mut ob);
            s = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        drain(&mut ob);
        assert_eq!((s, rc), (zkrs_step::ZKRS_RELEASE, 0));
        let zero = with_handle(h, |i, _| i.host().is_zero());
        assert!(zero);
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }

    /// Simulates abnormal dispatch worker termination prior to delivering a completion batch. The
    /// batch contains the closing notification generated by the shutdown pump; the cleanup handler
    /// discards the unconsumed batch so the joining close operation does not deadlock waiting for
    /// delivery.
    #[test]
    fn a_dispatch_end_gives_its_ref_to_the_joining_close() {
        let _rec = standin::recorder();
        standin::set_resolve(vec![loopback()], Vec::new(), 0, 0);
        let h = ready("127.0.0.1:2181");
        zkrs_handle_retain(h);
        zkrs_thread_enter(h, ZKRS_ROLE_DISPATCH);
        submit_exists(h, 41);
        let mut ob = empty_outbox();
        let mut rc: c_int = 1;
        let mut joined = false;
        let mut s = zkrs_close(h, ZKRS_ROLE_FOREIGN, &raw mut ob, &raw mut rc);
        loop {
            let mut it = zkrs_item::empty();
            while crate::abi::outbox::zkrs_outbox_next(&raw mut ob, &raw mut it) == 1 {
                let join = it.r#type == crate::abi::types::zkrs_item_type::ZKRS_ITEM_THREAD as u32
                    && it.thread_op == crate::abi::types::zkrs_thread_op::ZKRS_THREAD_JOIN as u32;
                if join && it.thread_role == ZKRS_ROLE_IO {
                    // Simulate abnormal termination of the dispatch worker following batch receipt.
                    let mut dob = empty_outbox();
                    let mut drc: c_int = -1;
                    let first = zkrs_wait_dispatch(h, &raw mut dob, &raw mut drc);
                    assert_eq!(first, zkrs_step::ZKRS_DONE);
                    assert!(!dob.r#impl.is_null());
                    zkrs_dispatch_ended(h, &raw mut dob);
                    zkrs_handle_release(h);
                    joined = true;
                }
            }
            crate::abi::outbox::zkrs_outbox_finish(&raw mut ob);
            if s != zkrs_step::ZKRS_MORE {
                break;
            }
            s = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        assert!(joined);
        assert_eq!((s, rc), (zkrs_step::ZKRS_RELEASE, 0));
        assert_eq!(standin::discarded(), [41]);
        let zero = with_handle(h, |i, _| i.host().is_zero());
        assert!(zero);
        zkrs_handle_release(h);
    }

    // single threaded driving test scenarios mirroring C shim execution flows.

    /// Legacy `ZOK` success status code.
    const ZOK: c_int = 0;
    /// ABI item type discriminator values mirroring `zkrs_item_type`.
    const ITEM_RESULT: u32 = 2;
    const ITEM_WATCH: u32 = 3;
    /// Session connection state `ZOO_CONNECTED_STATE`.
    const CONNECTED: i32 = 3;
    /// Base port allocated for simulated server tests requiring no actual listening daemon.
    const ST_PORT: u16 = 23_000;
    /// Sentinel value verifying that unwritten interest fields remain unmodified by driver
    /// execution.
    const SENTINEL: zkrs_interest_out = zkrs_interest_out {
        fd: 77,
        interest: 77,
        timeout_ms: 77,
    };

    /// Simplified representation of a drained outbox event for assertions.
    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    enum Seen {
        Result { token: usize, rc: i32 },
        Watch { kind: i32, state: i32 },
        Other(u32),
    }

    fn seen(it: &zkrs_item) -> Seen {
        match it.r#type {
            ITEM_RESULT => Seen::Result {
                token: it.token,
                rc: raw::read_struct(it.result).rc,
            },
            ITEM_WATCH => {
                let w = raw::read_struct(it.watch);
                Seen::Watch {
                    kind: w.r#type,
                    state: w.state,
                }
            }
            t => Seen::Other(t),
        }
    }

    /// Drains all items from `ob` in the manner of `zks_drain`, invoking callback `f` for each
    /// drained item.
    fn drain_with(ob: &mut zkrs_outbox, f: &mut dyn FnMut(Seen)) -> Vec<Seen> {
        let mut it = zkrs_item::empty();
        let mut batch = Vec::new();
        while crate::abi::outbox::zkrs_outbox_next(ob, &raw mut it) == 1 {
            let s = seen(&it);
            f(s);
            batch.push(s);
        }
        crate::abi::outbox::zkrs_outbox_finish(ob);
        batch
    }

    /// Creates a single threaded client handle targeting `127.0.0.1:port` and advances
    /// initialization to completion.
    fn st_ready(port: u16) -> *mut zkrs_handle {
        let text = format!("127.0.0.1:{port}");
        let addr = zkrs_sockaddr_view::from_addr(text.parse().ok());
        standin::set_resolve(vec![addr], Vec::new(), 0, 0);
        let hosts = CString::new(text).unwrap_or_default();
        let mut a = init_args(&hosts);
        a.flavor = ZKRS_FLAVOR_ST;
        a.recv_timeout = 10_000;
        let mut ob = empty_outbox();
        let (mut s, h, mut rc) = handle_new(&a, &mut ob);
        assert!(!h.is_null());
        while s == zkrs_step::ZKRS_MORE {
            drop_box(&mut ob);
            s = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        drop_box(&mut ob);
        assert_eq!((s, rc), (zkrs_step::ZKRS_DONE, ZOK));
        h
    }

    /// Container holding the cumulative outcome, status codes, and item batches produced by a
    /// C-style driving step.
    struct Call {
        step: zkrs_step,
        rc: c_int,
        batches: Vec<Vec<Seen>>,
    }

    /// Executes `zookeeper_interest` mirroring the C shim dispatch loop, maintaining C locals
    /// across intermediate barriers.
    fn c_interest(
        h: *const zkrs_handle,
        out: &mut zkrs_interest_out,
        written: &mut c_int,
        f: &mut dyn FnMut(Seen),
    ) -> Call {
        let before = *out;
        let mut ob = empty_outbox();
        let mut rc: c_int = ZOK;
        let mut step = zkrs_interest(h, out, written, &raw mut ob, &raw mut rc);
        let mut batches = Vec::new();
        loop {
            batches.push(drain_with(&mut ob, f));
            if step != zkrs_step::ZKRS_MORE {
                break;
            }
            if *written == 0 {
                assert_eq!(*out, before, "a More step wrote the outputs");
            }
            step = zkrs_interest_resume(h, out, written, &raw mut ob, &raw mut rc);
        }
        Call { step, rc, batches }
    }

    /// Executes `zookeeper_process` or `zookeeper_close` mirroring C shim dispatch loops.
    fn c_call(
        h: *const zkrs_handle,
        first: impl FnOnce(&mut zkrs_outbox, &mut c_int) -> zkrs_step,
        f: &mut dyn FnMut(Seen),
    ) -> Call {
        let mut ob = empty_outbox();
        let mut rc: c_int = ZOK;
        let mut step = first(&mut ob, &mut rc);
        let mut batches = Vec::new();
        loop {
            batches.push(drain_with(&mut ob, f));
            if step != zkrs_step::ZKRS_MORE {
                break;
            }
            step = zkrs_resume(h, &raw mut ob, &raw mut rc);
        }
        Call { step, rc, batches }
    }

    fn c_process(h: *const zkrs_handle, events: i32, f: &mut dyn FnMut(Seen)) -> Call {
        c_call(h, |ob, rc| zkrs_process(h, events, ob, rc), f)
    }

    fn c_close(h: *const zkrs_handle, f: &mut dyn FnMut(Seen)) -> Call {
        c_call(h, |ob, rc| zkrs_close(h, ZKRS_ROLE_FOREIGN, ob, rc), f)
    }

    fn ignore(_: Seen) {}

    #[test]
    fn st_process_before_the_first_interest_is_invalid() {
        let _rec = standin::recorder();
        let h = st_ready(ST_PORT);
        let c = c_process(h, 3, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_DONE, ZINVALIDSTATE));
        assert!(c.batches.iter().all(Vec::is_empty));
        // The handle state remains viable following early rejection: session close cleanly releases
        // resources.
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_RELEASE, ZOK));
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }

    #[test]
    fn st_close_before_the_first_interest_releases_the_handle() {
        let _rec = standin::recorder();
        let h = st_ready(ST_PORT);
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_RELEASE, ZOK));
        let zero = with_handle(h, |i, _| i.host().is_zero());
        assert!(zero);
        // Because no requests were submitted, no tokens are discarded during close.
        assert!(standin::discarded().is_empty());
        // Release the primary C handle reference matching `zks_release`.
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the socket"
    )]
    fn st_interest_with_no_server_follows_the_output_rules() {
        let _rec = standin::recorder();
        let h = st_ready(ST_PORT);
        let mut out = SENTINEL;
        let mut written: c_int = 0;
        let c = c_interest(h, &mut out, &mut written, &mut ignore);
        assert_eq!(c.step, zkrs_step::ZKRS_DONE);
        // In-progress connection handshakes or immediate connection failures both return ZOK to C
        // callers, allowing the application event loop to poll again upon timeout expiry.
        assert_eq!((c.rc, written), (ZOK, 1));
        assert_ne!(out, SENTINEL);
        assert!(out.timeout_ms >= 0 && out.timeout_ms <= 10_000, "{out:?}");
        if out.fd >= 0 {
            // Write interest bit `ZOOKEEPER_WRITE` (1) indicates readiness monitoring for
            // nonblocking connect completion.
            assert_eq!(out.interest & 1, 1, "{out:?}");
        }
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_RELEASE, ZOK));
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }

    #[test]
    fn st_interest_after_the_close_keeps_the_caller_values() {
        let _rec = standin::recorder();
        let h = st_ready(ST_PORT);
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!(c.step, zkrs_step::ZKRS_RELEASE);
        zkrs_handle_release(h);
        // Following handle release, subsequent interest evaluations return ZINVALIDSTATE without
        // modifying caller supplied output buffers, preserving legacy C client behavior.
        let mut out = SENTINEL;
        let mut written: c_int = 1;
        let c = c_interest(h, &mut out, &mut written, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_DONE, ZINVALIDSTATE));
        assert_eq!((written, out), (0, SENTINEL));
        zkrs_handle_release(h);
    }

    #[test]
    fn interest_views_fall_back_to_no_socket() {
        let io = InterestOut {
            rc: 0,
            written: true,
            fd: 5,
            bits: u32::MAX,
            timeout_ms: 9,
        };
        assert_eq!(
            interest_view(&io),
            zkrs_interest_out {
                fd: 5,
                interest: 0,
                timeout_ms: 9,
            }
        );
        let bad = zkrs_interest_out {
            fd: isize::MAX,
            interest: -1,
            timeout_ms: 4,
        };
        let one: c_int = 1;
        let got = read_interest(&raw const bad, &raw const one);
        assert_eq!(
            (got.written, got.fd, got.bits, got.timeout_ms),
            (true, -1, 0, 4)
        );
        let zero: c_int = 0;
        let got = read_interest(&raw const bad, &raw const zero);
        assert!(!got.written);
    }

    #[test]
    fn write_interest_writes_only_real_outputs() {
        let io = InterestOut {
            rc: 0,
            written: true,
            fd: 6,
            bits: 3,
            timeout_ms: 1_000,
        };
        let want = zkrs_interest_out {
            fd: 6,
            interest: 3,
            timeout_ms: 1_000,
        };
        for step in [zkrs_step::ZKRS_MORE, zkrs_step::ZKRS_DONE] {
            let (mut out, mut written) = (SENTINEL, 0);
            write_interest(step, &io, &raw mut out, &raw mut written);
            assert_eq!((out, written), (want, 1), "{step:?}");
        }
        let none = InterestOut::UNWRITTEN;
        let (mut out, mut written) = (SENTINEL, 7);
        write_interest(zkrs_step::ZKRS_MORE, &none, &raw mut out, &raw mut written);
        assert_eq!((out, written), (SENTINEL, 7));
        for step in [zkrs_step::ZKRS_DONE, zkrs_step::ZKRS_RELEASE] {
            let (mut out, mut written) = (SENTINEL, 7);
            write_interest(step, &none, &raw mut out, &raw mut written);
            assert_eq!((out, written), (SENTINEL, 0), "{step:?}");
        }
    }

    // Real server integration tests executed against local ZooKeeper test cluster instances.
    // Controlled by `ZK_TEST_PORT_BASE` and managed via `dev/test-server.sh`.

    /// Environment variable specifying the base port allocation for live ZooKeeper server tests.
    const PORT_BASE_ENV: &str = "ZK_TEST_PORT_BASE";
    /// Port offset within the allocated range dedicated to shared single threaded server testing.
    const SERVER_OFFSET: u16 = 0;

    /// Resolves the target port for the live server instance, returning `None` if the base
    /// environment variable is unset.
    fn server_port() -> Option<u16> {
        let base: u16 = std::env::var(PORT_BASE_ENV).ok()?.trim().parse().ok()?;
        base.checked_add(SERVER_OFFSET)
    }

    /// Controls test server lifecycle by invoking `dev/test-server.sh CMD PORT`.
    fn test_server(cmd: &str, port: u16) -> bool {
        let script =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../dev/test-server.sh");
        std::process::Command::new("bash")
            .arg(script)
            .arg(cmd)
            .arg(port.to_string())
            .stdout(std::process::Stdio::null())
            .status()
            .is_ok_and(|s| s.success())
    }

    /// Shared server state tracking the active listener port and active reference count.
    static SERVER: std::sync::Mutex<(usize, Option<u16>)> = std::sync::Mutex::new((0, None));

    /// RAII guard retaining a reference to the active test server; drops shut down the daemon when
    /// the final guard expires.
    struct ServerGuard {
        port: u16,
    }

    impl Drop for ServerGuard {
        fn drop(&mut self) {
            let mut st = SERVER
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            st.0 = st.0.saturating_sub(1);
            if st.0 == 0 {
                if let Some(port) = st.1.take() {
                    test_server("stop", port);
                }
            }
        }
    }

    /// Emits a skip message to stderr when optional integration requirements are absent.
    #[allow(clippy::print_stderr, reason = "the test harness shows the skip note")]
    fn skip(why: &str) {
        eprintln!("skip: {why}");
    }

    /// Ensures the shared integration server is running, returning a scoped guard or `None` if
    /// skipped.
    fn server_or_skip() -> Option<ServerGuard> {
        let Some(port) = server_port() else {
            skip(&format!("{PORT_BASE_ENV} is not set"));
            return None;
        };
        let mut st = SERVER
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if st.1.is_none() {
            if !test_server("start", port) {
                // Clean up any partially started server process on startup failure.
                test_server("stop", port);
                skip(&format!("no ZooKeeper server can start on port {port}"));
                return None;
            }
            st.1 = Some(port);
        }
        st.0 = st.0.saturating_add(1);
        Some(ServerGuard { port })
    }

    /// Simulates application event loop polling by waiting for socket readiness events matching
    /// interest masks.
    fn wait(h: *const zkrs_handle, out: &zkrs_interest_out, written: c_int) -> i32 {
        let io = read_interest(out, &raw const written);
        let wait_ms = i32::try_from(out.timeout_ms.clamp(0, 200)).unwrap_or(200);
        let events = with_handle(h, |i, _| match i.driver() {
            Some(Driver::St(p)) => p.wait_events(&io, wait_ms),
            _ => 0,
        });
        i32::try_from(events).unwrap_or(0)
    }

    /// Advances the application event loop through one complete iteration of interest calculation,
    /// polling, and packet processing.
    fn turn(h: *const zkrs_handle, f: &mut dyn FnMut(Seen)) -> Vec<Seen> {
        let mut out = SENTINEL;
        let mut written: c_int = 0;
        let c = c_interest(h, &mut out, &mut written, f);
        assert_eq!(c.step, zkrs_step::ZKRS_DONE, "rc {}", c.rc);
        let mut all: Vec<Seen> = c.batches.concat();
        let events = wait(h, &out, written);
        if events != 0 {
            let c = c_process(h, events, f);
            assert_eq!(c.step, zkrs_step::ZKRS_DONE, "rc {}", c.rc);
            all.extend(c.batches.concat());
        }
        all
    }

    fn state(h: *const zkrs_handle) -> i32 {
        with_handle(h, |i, _| i.driver().map_or(-1, Driver::state))
    }

    /// Iterates the single threaded event loop until the client successfully establishes a
    /// connection with the server.
    fn connect(h: *const zkrs_handle) -> Vec<Seen> {
        let mut all = Vec::new();
        for _ in 0..100 {
            all.extend(turn(h, &mut ignore));
            if state(h) == CONNECTED {
                break;
            }
        }
        assert_eq!(state(h), CONNECTED, "the st handle did not connect");
        all
    }

    /// Submits a node existence query (`exists /`) using asynchronous queued delivery semantics.
    fn submit_exists(h: *const zkrs_handle, token: usize) {
        let rq = crate::abi::types::zkrs_request {
            op: crate::abi::types::ZKRS_OP_EXISTS,
            flags: 0,
            path: c"/".as_ptr(),
            data: std::ptr::null(),
            datalen: -1,
            acl: std::ptr::null(),
            mode: 0,
            ttl: -1,
            version: -1,
            reconfig_version: -1,
            joining: std::ptr::null(),
            leaving: std::ptr::null(),
            members: std::ptr::null(),
            watch_fn: 0,
            watch_ctx: 0,
            wtype: 0,
            ops: std::ptr::null(),
            ops_len: 0,
            count: 0,
        };
        let mut ob = empty_outbox();
        let st = crate::abi::submit::zkrs_submit(
            h,
            &raw const rq,
            token,
            crate::abi::types::ZKRS_DELIVER_QUEUED,
            &raw mut ob,
        );
        drain(&mut ob);
        assert_eq!(st, crate::abi::types::zkrs_status::ZKRS_OK);
    }

    #[test]
    #[ignore = "real-server"]
    fn st_connects_through_interest_and_process() {
        let Some(server) = server_or_skip() else {
            return;
        };
        let _rec = standin::recorder();
        let h = st_ready(server.port);
        let seen = connect(h);
        // The established connection triggers an asynchronous watcher event delivering a session
        // state notification.
        let want = Seen::Watch {
            kind: -1,
            state: CONNECTED,
        };
        assert!(seen.contains(&want), "{seen:?}");
        let mut out = SENTINEL;
        let mut written: c_int = 0;
        let c = c_interest(h, &mut out, &mut written, &mut ignore);
        assert_eq!((c.step, c.rc, written), (zkrs_step::ZKRS_DONE, ZOK, 1));
        assert!(out.fd >= 0, "{out:?}");
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_RELEASE, ZOK));
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }

    #[test]
    #[ignore = "real-server"]
    fn st_session_event_ends_a_batch() {
        let Some(server) = server_or_skip() else {
            return;
        };
        let _rec = standin::recorder();
        let h = st_ready(server.port);
        let mut batches = Vec::new();
        for _ in 0..100 {
            let mut out = SENTINEL;
            let mut written: c_int = 0;
            let c = c_interest(h, &mut out, &mut written, &mut ignore);
            batches.extend(c.batches);
            let events = wait(h, &out, written);
            if events != 0 {
                batches.extend(c_process(h, events, &mut ignore).batches);
            }
            if state(h) == CONNECTED {
                break;
            }
        }
        // Watch notifications act as delivery barriers: C executes associated callbacks outside
        // Rust stack frames, ensuring the watcher item occupies the terminal position of the
        // dispatched batch.
        let session = batches
            .iter()
            .find(|b| b.iter().any(|s| matches!(s, Seen::Watch { kind: -1, .. })));
        let last = session.and_then(|b| b.last().copied());
        assert_eq!(
            last,
            Some(Seen::Watch {
                kind: -1,
                state: CONNECTED,
            }),
            "{batches:?}"
        );
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!(c.step, zkrs_step::ZKRS_RELEASE);
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }

    #[test]
    #[ignore = "real-server"]
    fn st_submit_gives_its_result() {
        let Some(server) = server_or_skip() else {
            return;
        };
        let _rec = standin::recorder();
        let h = st_ready(server.port);
        connect(h);
        submit_exists(h, 0x7000);
        let want = Seen::Result {
            token: 0x7000,
            rc: ZOK,
        };
        let mut got = false;
        for _ in 0..100 {
            if turn(h, &mut ignore).contains(&want) {
                got = true;
                break;
            }
        }
        assert!(got);
        zkrs_handle_retain(h);
        let c = c_close(h, &mut ignore);
        assert_eq!((c.step, c.rc), (zkrs_step::ZKRS_RELEASE, ZOK));
        zkrs_handle_release(h);
        zkrs_handle_release(h);
        assert!(standin::discarded().is_empty());
    }

    #[test]
    #[ignore = "real-server"]
    fn st_close_from_a_callback_releases_at_the_end_of_the_call() {
        let Some(server) = server_or_skip() else {
            return;
        };
        let _rec = standin::recorder();
        let h = st_ready(server.port);
        connect(h);
        submit_exists(h, 0x7001);
        zkrs_handle_retain(h);
        let mut nested: Option<(zkrs_step, c_int)> = None;
        let mut end: Option<(zkrs_step, c_int)> = None;
        for _ in 0..100 {
            let mut on_item = |s: Seen| {
                if let Seen::Result { token: 0x7001, .. } = s {
                    // Invoking session close from within a completion callback defers final
                    // teardown to the outer execution epilogue of the enclosing call.
                    let c = c_close(h, &mut ignore);
                    nested = Some((c.step, c.rc));
                }
            };
            let mut out = SENTINEL;
            let mut written: c_int = 0;
            let c = c_interest(h, &mut out, &mut written, &mut on_item);
            if c.step == zkrs_step::ZKRS_RELEASE {
                end = Some((c.step, c.rc));
                break;
            }
            let events = wait(h, &out, written);
            if events != 0 {
                let c = c_process(h, events, &mut on_item);
                if c.step == zkrs_step::ZKRS_RELEASE {
                    end = Some((c.step, c.rc));
                    break;
                }
            }
        }
        assert_eq!(nested, Some((zkrs_step::ZKRS_DONE, ZOK)));
        // Verify that the enclosing driver call concludes with its own status code and releases
        // resources.
        assert_eq!(end, Some((zkrs_step::ZKRS_RELEASE, ZOK)));
        let zero = with_handle(h, |i, _| i.host().is_zero());
        assert!(zero);
        zkrs_handle_release(h);
        zkrs_handle_release(h);
    }
}
