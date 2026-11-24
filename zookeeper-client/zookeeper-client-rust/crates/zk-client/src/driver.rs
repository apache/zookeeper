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

//! Driver selection abstractions and [`DriverHandle`] lifecycle management bridging `zk-client` to
//! `zk-driver`.
//!
//! # Architectural Role in `zk-client`
//!
//! Higher level `zk-client` modules (`Client`, `Session`, `BlockingClient`, `Router`) interact
//! with the network transport exclusively through [`DriverHandle`], which unifies the two native
//! execution backends provided by [`zk_driver`] (both of which drive [`zk_driver::iocore::IoCore`]
//! under [`zk_driver::outbox::Flavor::Native`]):
//!
//! - **Dedicated OS Thread ([`DriverHandle::Thread`] -> [`zk_driver::NativeHandle`])**: Spawns a
//!   dedicated background `zk-io` thread (plus on-demand `zk-dns` resolver threads under
//!   `DeferPolicy::DNS_THREAD`) via a custom [`ClientSpawner`]. [`ClientSpawner`] wraps the primary
//!   `zk-io` thread body with [`enter_io_side`] (setting a thread-local marker so any reentrant
//!   blocking `zk-client` call from an `IoThread`-dispatched callback is rejected with
//!   `ZINVALIDSTATE` instead of deadlocking `zk-io`) and triggers [`Router::shutdown`] +
//!   [`ExitSignal`] when the `zk-io` thread exits.
//! - **Tokio Asynchronous Task ([`DriverHandle::Tokio`] -> [`zk_driver::tokio::TaskHandle`])**:
//!   Spawns a cooperative task on the caller's Tokio runtime (`DeferPolicy::ALL`, offloading all
//!   blocking DNS, TLS setup, `isro` probe, and SASL work to `spawn_blocking`). Because Tokio tasks
//!   have no OS thread exit hook, [`CloseWait::Tokio`] tears down the [`Router`] as soon as
//!   [`zk_driver::tokio::TaskHandle::closed`] resolves.

#[cfg(any(test, all(unix, feature = "tokio")))]
use std::future::Future;
#[cfg(any(test, all(unix, feature = "tokio")))]
use std::pin::Pin;
use std::sync::{Arc, Condvar, Mutex};
#[cfg(any(test, all(feature = "blocking", unix, feature = "tokio")))]
use std::task::Wake;
use std::task::{Context, Poll, Waker};
use std::time::Duration;
#[cfg(any(test, feature = "blocking"))]
use std::time::Instant;

use zk_driver::native::{IO_THREAD_NAME, ThreadBody};
use zk_driver::{Env, NativeHandle, RouteSink, Spawn, StartError, StdSpawner};
use zk_jute::JString;
use zk_proto::{
    AddAuthError, ClientId, Connection, HostSpec, MultiOpRef, Request, SaslSession, SecretBytes,
    SubmitError, Submitted,
};

use crate::builder::{Runtime, ThreadOptions};
use crate::error::{ConnectError, Error};
use crate::route::Router;
use crate::sync::{LockExt, enter_io_side, wake_quietly};
#[cfg(any(test, feature = "blocking"))]
use crate::sync::{wait, wait_until};
use crate::tokens::{AuthToken, NativeTokens};

/// Sentinel return code indicating that the background driver terminated unexpectedly without
/// providing an exit status.
const ZSYSTEMERROR: i32 = -1;

/// Unified handle managing the background I/O driver executing a client's network loop.
pub(crate) enum DriverHandle {
    /// Dedicated OS thread driver implementation.
    Thread {
        /// Native driver handle managing submission queues and thread signaling.
        handle: NativeHandle<NativeTokens>,
        /// Synchronization signal triggered when the background I/O thread terminates.
        exit: Arc<ExitSignal>,
    },
    /// Asynchronous Tokio runtime task driver implementation.
    #[cfg(all(unix, feature = "tokio"))]
    Tokio(zk_driver::tokio::TaskHandle<NativeTokens>),
}

impl std::fmt::Debug for DriverHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Thread { .. } => f.write_str("DriverHandle::Thread"),
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(_) => f.write_str("DriverHandle::Tokio"),
        }
    }
}

/// Dispatches a method invocation across either driver handle variant.
macro_rules! forward {
    ($self:ident, $h:ident => $e:expr) => {
        match $self {
            DriverHandle::Thread { handle: $h, .. } => $e,
            #[cfg(all(unix, feature = "tokio"))]
            DriverHandle::Tokio($h) => $e,
        }
    };
}

impl DriverHandle {
    /// Submits a fully owned request under the core connection lock and signals the driver event
    /// loop.
    pub(crate) fn submit(
        &self,
        req: Request<NativeTokens>,
    ) -> Result<Submitted<NativeTokens>, SubmitError<NativeTokens>> {
        forward!(self, h => h.submit(req))
    }

    /// Submits a request carrying a borrowed data payload, delegating to
    /// [`Connection::submit_with_data`] before waking the driver loop.
    pub(crate) fn submit_with_data(
        &self,
        req: Request<NativeTokens>,
        data: Option<&[u8]>,
    ) -> Result<Submitted<NativeTokens>, SubmitError<NativeTokens>> {
        forward!(self, h => h.submit_with_data(req, data))
    }

    /// Submits a multi-operation transaction consisting of borrowed sub-operations, delegating to
    /// [`Connection::submit_multi_ref`] before waking the driver loop.
    pub(crate) fn submit_multi_ref(
        &self,
        req: Request<NativeTokens>,
        ops: &[MultiOpRef<'_>],
    ) -> Result<Submitted<NativeTokens>, SubmitError<NativeTokens>> {
        forward!(self, h => h.submit_multi_ref(req, ops))
    }

    /// Enqueues an authentication credential packet (`add_auth`) and signals the background driver
    /// loop.
    pub(crate) fn add_auth(
        &self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<AuthToken>,
    ) -> Result<(), AddAuthError<NativeTokens>> {
        forward!(self, h => h.add_auth(scheme, cred, done))
    }

    /// Updates the dynamic ensemble server list, returning the newly registered DNS resolver
    /// generation, or `0` if the driver has already terminated.
    pub(crate) fn set_servers(&self, hosts: Vec<HostSpec>) -> u64 {
        forward!(self, h => h.set_servers(hosts))
    }

    /// Initiates a graceful session shutdown handshake with the ensemble. Subsequent invocations
    /// are idempotent.
    pub(crate) fn close(&self) {
        forward!(self, h => h.close())
    }

    /// Terminates the local driver immediately without transmitting a session close packet across
    /// the wire.
    pub(crate) fn detach(&self) {
        forward!(self, h => h.detach())
    }

    /// Constructs a read-gate backpressure reporter that records consumed responses without
    /// extending session lifespan.
    pub(crate) fn gate(&self) -> Gate {
        match self {
            Self::Thread { handle, .. } => Gate::Thread(handle.gate_reporter()),
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(h) => Gate::Tokio(h.gate_reporter()),
        }
    }

    /// Indicates whether the active client instance is driven by a Tokio asynchronous task
    /// runtime.
    pub(crate) fn is_tokio(&self) -> bool {
        match self {
            Self::Thread { .. } => false,
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(_) => true,
        }
    }

    /// Returns the negotiated session identifier and credentials assigned to this client
    /// connection.
    pub(crate) fn client_id(&self) -> ClientId {
        forward!(self, h => h.client_id())
    }

    /// Executes a closure inspecting connection state under the core lock, returning `None` if
    /// the driver has terminated.
    pub(crate) fn read_conn<R>(&self, f: impl FnOnce(&Connection<NativeTokens>) -> R) -> Option<R> {
        forward!(self, h => h.read_conn(f))
    }

    /// Executes a mutating closure under the core connection lock and wakes the driver loop,
    /// returning `None` if terminated.
    pub(crate) fn with_conn<R>(
        &self,
        f: impl FnOnce(&mut Connection<NativeTokens>, zk_proto::Millis) -> R,
    ) -> Option<R> {
        forward!(self, h => h.with_conn(f))
    }

    /// Constructs an asynchronous completion barrier awaiting driver termination and extracting
    /// its final exit code.
    ///
    /// The Tokio driver does not support explicit thread exit hooks. Exit-following tasks may
    /// execute concurrently with close futures. To ensure that [`Router::on_exit`] is executed
    /// before the close future completes, the Tokio wait wrapper executes router cleanup directly
    /// upon driver task completion. The client is guaranteed to observe the `Closed` state once
    /// the close future resolves, matching the behavior of the dedicated thread driver.
    pub(crate) fn close_wait(&self, router: &Arc<Router>) -> CloseWait {
        match self {
            Self::Thread { handle, exit } => {
                // The dedicated thread driver executes `Router::on_exit` inside the thread wrapper
                // prior to signaling exit.
                let _ = router;
                CloseWait::Thread {
                    exit: Arc::clone(exit),
                    handle: handle.clone(),
                }
            }
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(h) => {
                let h = h.clone();
                let router = Arc::clone(router);
                CloseWait::Tokio(Box::pin(async move {
                    let rc = h.closed().await;
                    router.on_exit();
                    rc
                }))
            }
        }
    }
}

/// Backpressure tracking handle reporting consumed or dropped response items back to the driver
/// read gate.
///
/// In-flight futures and registered watchers retain clones of this gate alongside their weak
/// client references. When the final clone is dropped, the driver drains the pending submission
/// queues, maintaining backpressure balance until termination without extending the lifetime of
/// the underlying session.
#[derive(Clone)]
pub(crate) enum Gate {
    /// Dedicated OS thread driver gate reporter.
    Thread(zk_driver::NativeGate<NativeTokens>),
    /// Tokio asynchronous runtime driver gate reporter.
    #[cfg(all(unix, feature = "tokio"))]
    Tokio(zk_driver::tokio::TaskGate<NativeTokens>),
}

impl std::fmt::Debug for Gate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Gate(..)")
    }
}

impl Gate {
    /// Notifies the driver that a receiver has consumed or discarded an in-flight accounted
    /// response item.
    pub(crate) fn taken(&self) {
        match self {
            Self::Thread(g) => g.taken(),
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(g) => g.taken(),
        }
    }

    /// Indicates whether this gate reports backpressure to a Tokio asynchronous runtime driver.
    #[cfg(feature = "blocking")]
    pub(crate) fn is_tokio(&self) -> bool {
        match self {
            Self::Thread(_) => false,
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(_) => true,
        }
    }
}

/// Synchronized internal state tracked behind the [`ExitSignal`] mutex.
#[derive(Debug, Default)]
struct ExitState {
    done: bool,
    wakers: Vec<Waker>,
}

/// One-shot synchronization primitive signaled exactly once when the dedicated I/O thread exits.
#[derive(Debug, Default)]
pub(crate) struct ExitSignal {
    st: Mutex<ExitState>,
    cv: Condvar,
}

impl ExitSignal {
    /// Allocates an unset exit synchronization primitive.
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Marks the exit signal as complete, unparks all condition-variable waiters, and wakes
    /// asynchronous tasks outside the lock.
    pub(crate) fn signal(&self) {
        let wakers = {
            let mut s = self.st.lock_unpoisoned();
            s.done = true;
            std::mem::take(&mut s.wakers)
        };
        self.cv.notify_all();
        for w in wakers {
            wake_quietly(w);
        }
    }

    /// Asynchronously polls for driver exit, registering the task waker if termination is
    /// still pending.
    pub(crate) fn poll(&self, cx: &mut Context<'_>) -> Poll<()> {
        let mut s = self.st.lock_unpoisoned();
        if s.done {
            return Poll::Ready(());
        }
        if !s.wakers.iter().any(|w| w.will_wake(cx.waker())) {
            s.wakers.push(cx.waker().clone());
        }
        Poll::Pending
    }

    /// Synchronously blocks the calling thread until the exit signal is triggered or the
    /// specified deadline elapses.
    #[cfg(any(test, feature = "blocking"))]
    pub(crate) fn wait(&self, deadline: Option<Instant>) -> bool {
        let mut s = self.st.lock_unpoisoned();
        loop {
            if s.done {
                return true;
            }
            match deadline {
                None => s = wait(&self.cv, s),
                Some(d) => {
                    let (g, timed_out) = wait_until(&self.cv, s, d);
                    s = g;
                    if timed_out {
                        return s.done;
                    }
                }
            }
        }
    }
}

/// Asynchronous completion future awaiting driver termination and retrieving its final exit status
/// code.
pub(crate) enum CloseWait {
    /// Dedicated thread driver: awaits the [`ExitSignal`] before reading the final return code
    /// from the native handle.
    Thread {
        /// Exit signal tripped when the I/O thread terminates.
        exit: Arc<ExitSignal>,
        /// Driver handle clone used to query the final termination status after graceful shutdown.
        handle: NativeHandle<NativeTokens>,
    },
    /// Tokio asynchronous runtime driver: awaits the termination future returned by
    /// [`zk_driver::tokio::TaskHandle::closed`].
    #[cfg(all(unix, feature = "tokio"))]
    Tokio(Pin<Box<dyn Future<Output = i32> + Send>>),
}

impl CloseWait {
    /// Polls the termination future for completion, returning the driver exit status code once
    /// stopped.
    pub(crate) fn poll(&mut self, cx: &mut Context<'_>) -> Poll<i32> {
        match self {
            Self::Thread { exit, handle } => match exit.poll(cx) {
                Poll::Ready(()) => Poll::Ready(exit_rc(handle)),
                Poll::Pending => Poll::Pending,
            },
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(f) => f.as_mut().poll(cx),
        }
    }

    /// Returns whether this wait handle monitors a Tokio asynchronous task driver.
    #[cfg(feature = "blocking")]
    pub(crate) fn is_tokio(&self) -> bool {
        match self {
            Self::Thread { .. } => false,
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(_) => true,
        }
    }

    /// Synchronously blocks the calling thread until the driver terminates or the deadline
    /// expires. Callers must ensure that blocking calls are permitted on the current thread
    /// context before invoking.
    #[cfg(feature = "blocking")]
    pub(crate) fn wait(&mut self, deadline: Option<Instant>) -> Option<i32> {
        match self {
            Self::Thread { exit, handle } => {
                if exit.wait(deadline) {
                    Some(exit_rc(handle))
                } else {
                    None
                }
            }
            #[cfg(all(unix, feature = "tokio"))]
            Self::Tokio(f) => block_on(f.as_mut(), deadline),
        }
    }
}

/// Extracts the final exit return code from a terminated dedicated I/O thread handle.
fn exit_rc(handle: &NativeHandle<NativeTokens>) -> i32 {
    handle.wait_closed(Duration::ZERO).unwrap_or(ZSYSTEMERROR)
}

/// Minimal thread-unparking waker implementation for synchronous blocking execution.
#[cfg(any(test, all(feature = "blocking", unix, feature = "tokio")))]
struct ThreadWaker(std::thread::Thread);

#[cfg(any(test, all(feature = "blocking", unix, feature = "tokio")))]
impl Wake for ThreadWaker {
    fn wake(self: Arc<Self>) {
        self.0.unpark();
    }

    fn wake_by_ref(self: &Arc<Self>) {
        self.0.unpark();
    }
}

/// Synchronously drives a future to completion on the current thread by parking between wake
/// notifications.
#[cfg(any(test, all(feature = "blocking", unix, feature = "tokio")))]
fn block_on<T>(
    mut f: Pin<&mut (dyn Future<Output = T> + Send)>,
    deadline: Option<Instant>,
) -> Option<T> {
    let waker = Waker::from(Arc::new(ThreadWaker(std::thread::current())));
    let mut cx = Context::from_waker(&waker);
    loop {
        if let Poll::Ready(v) = f.as_mut().poll(&mut cx) {
            return Some(v);
        }
        match deadline {
            None => std::thread::park(),
            Some(d) => {
                let left = d.saturating_duration_since(Instant::now());
                if left.is_zero() {
                    return None;
                }
                std::thread::park_timeout(left);
            }
        }
    }
}

/// RAII guard invoking router cleanup and tripping the exit signal when the background I/O thread
/// unwinds or terminates.
struct OnIoExit {
    router: Arc<Router>,
    exit: Arc<ExitSignal>,
}

impl Drop for OnIoExit {
    fn drop(&mut self) {
        self.router.on_exit();
        self.exit.signal();
    }
}

/// Thread spawning orchestrator configuring I/O boundaries and thread names for client
/// background threads.
struct ClientSpawner {
    std: StdSpawner,
    /// Configured display name for the dedicated I/O thread, or `None` to default to standard
    /// naming conventions.
    io_name: Option<String>,
    router: Arc<Router>,
    exit: Arc<ExitSignal>,
    client: u64,
}

impl Spawn for ClientSpawner {
    /// Spawns either the dedicated I/O thread or an auxiliary DNS resolver thread.
    ///
    /// Wraps each thread body within an I/O boundary guard setting thread-local identity
    /// markers. The dedicated I/O thread additionally installs an RAII guard ensuring that
    /// [`Router::on_exit`] and exit signaling are triggered upon thread termination.
    fn spawn(&self, name: &'static str, body: ThreadBody) -> std::io::Result<()> {
        let client = self.client;
        if name == IO_THREAD_NAME {
            let on_exit = OnIoExit {
                router: Arc::clone(&self.router),
                exit: Arc::clone(&self.exit),
            };
            let wrapped: ThreadBody = Box::new(move || {
                let _io = enter_io_side(client);
                let _on_exit = on_exit;
                body();
            });
            let thread_name = self
                .io_name
                .clone()
                .unwrap_or_else(|| IO_THREAD_NAME.to_owned());
            self.std.spawn_owned(thread_name, wrapped)
        } else {
            let wrapped: ThreadBody = Box::new(move || {
                let _io = enter_io_side(client);
                body();
            });
            self.std.spawn(name, wrapped)
        }
    }
}

/// Selects the default execution runtime according to ambient environment discovery rules.
///
/// If an ambient Tokio runtime is detected and configured with a multithreaded flavor, the
/// client automatically selects [`Runtime::Tokio`]. If the ambient runtime is current thread only,
/// an asynchronous driver task could easily be starved of ping heartbeats whenever user code
/// executes busy computations on the thread, so the client falls back to spawning a dedicated OS
/// thread. Applications may still explicitly configure single threaded Tokio runtimes if desired.
pub(crate) fn default_runtime() -> Runtime {
    #[cfg(all(unix, feature = "tokio"))]
    if let Ok(h) = tokio::runtime::Handle::try_current() {
        if h.runtime_flavor() == tokio::runtime::RuntimeFlavor::MultiThread {
            return Runtime::Tokio(h);
        }
    }
    Runtime::Thread(ThreadOptions::default())
}

/// Initializes and starts the background execution driver for a newly established client instance.
///
/// Inspects the configured runtime strategy, sets up thread-local boundary guards and
/// synchronization primitives, and delegates to the underlying `zk-driver` implementation.
///
/// # Errors
///
/// Returns [`ConnectError`] wrapping [`Error::InvalidArgument`] if a custom thread name contains
/// interior NULL bytes, or [`Error::SystemError`] if operating system thread spawning or socket
/// pair allocation fails.
pub(crate) fn start(
    conn: Box<Connection<NativeTokens>>,
    env: Env<NativeTokens>,
    router: Arc<Router>,
    runtime: Option<Runtime>,
    sasl: Option<Box<dyn SaslSession>>,
    client: u64,
) -> Result<DriverHandle, ConnectError> {
    match runtime.unwrap_or_else(default_runtime) {
        Runtime::Thread(opts) => {
            if opts.name.as_deref().is_some_and(|n| n.contains('\0')) {
                return Err(ConnectError::new(
                    Error::InvalidArgument("the thread name has a NUL byte".into()),
                    None,
                ));
            }
            let exit = ExitSignal::new();
            let spawner = Arc::new(ClientSpawner {
                std: StdSpawner {
                    stack_size: opts.stack_size,
                },
                io_name: opts.name,
                router: Arc::clone(&router),
                exit: Arc::clone(&exit),
                client,
            });
            let sink: Arc<dyn RouteSink<NativeTokens>> = router;
            match zk_driver::native::start(conn, env, sink, sasl, spawner) {
                Ok(handle) => Ok(DriverHandle::Thread { handle, exit }),
                Err(e) => Err(start_error(e)),
            }
        }
        #[cfg(all(unix, feature = "tokio"))]
        Runtime::Tokio(h) => {
            let sink: Arc<dyn RouteSink<NativeTokens>> = router;
            Ok(DriverHandle::Tokio(zk_driver::tokio::spawn(
                conn, env, sink, sasl, &h,
            )))
        }
    }
}

/// Translates driver startup failures into structured connection errors.
fn start_error(e: StartError) -> ConnectError {
    let io = match e {
        StartError::Waker(errno) => std::io::Error::from_raw_os_error(errno.raw_os_error()),
        StartError::Spawn(io) => io,
    };
    ConnectError::new(Error::SystemError, Some(Arc::new(io)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_exit_signal_wakes_blocked_waiters() {
        let s = ExitSignal::new();
        assert!(!s.wait(Some(Instant::now())));
        let s2 = Arc::clone(&s);
        let t = std::thread::spawn(move || s2.wait(None));
        s.signal();
        assert!(t.join().unwrap());
    }

    #[test]
    fn block_on_returns_a_ready_value() {
        let mut f = std::future::ready(7);
        let p: Pin<&mut std::future::Ready<i32>> = Pin::new(&mut f);
        assert_eq!(block_on(p, None), Some(7));
    }

    #[test]
    fn the_default_runtime_follows_the_flavor() {
        assert!(matches!(default_runtime(), Runtime::Thread(_)));
        #[cfg(all(unix, feature = "tokio"))]
        {
            let ct = tokio::runtime::Builder::new_current_thread()
                .build()
                .unwrap();
            assert!(ct.block_on(async { matches!(default_runtime(), Runtime::Thread(_)) }));
            let mt = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(1)
                .build()
                .unwrap();
            assert!(mt.block_on(async { matches!(default_runtime(), Runtime::Tokio(_)) }));
        }
    }
}
