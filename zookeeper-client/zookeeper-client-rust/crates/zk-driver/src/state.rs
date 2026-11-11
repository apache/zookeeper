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

//! Shared protocol and driver state synchronized under the `core` mutex.
//!
//! `Core` encapsulates the heap allocated `zk_proto::Connection` state machine alongside
//! driver level coordination state that must be mutated atomically with protocol transitions. While
//! [`crate::iocore::IoCore`] owns the physical socket descriptors and staging buffers under the
//! outer `io` mutex, `Core` holds the state shared between the I/O runner and external API
//! callers (`submit`, `add_auth`, `set_servers`, `close`, `requeue`, `sasl_result`, and state
//! accessors).
//!
//! # Ownership and Mutation Responsibilities Across Subsystems
//!
//! Each field of `Core` has a strictly defined set of writers across the driver lifecycle:
//! - **`conn`**: Initialized in `Core::new` and borrowed mutably by all driver packages (`mt`,
//!   `st`, `native`, `tokio`, `iocore`, and `resolve`) to drive protocol transitions and submit
//!   operations. Taken (`conn.take()`) exactly once during terminal handle release so any lingering
//!   in-flight tokens can be surrendered via `crate::host::discard_leftovers`.
//! - **`stopped`**: Latched by `Core::pump` whenever draining protocol outputs hits a barrier
//!   that parks a [`zk_proto::Stopped`] continuation inside `Connection`. Consumed and resumed by
//!   `IoCore::prelude` (or by driver close loops) via `Core::resume_stopped` at the start of the
//!   next driving turn after the caller finishes draining the outbox.
//! - **`requeued`**: Populated by `Polled::requeue` and `Shared::requeue` when a foreign callback
//!   unwinds or aborts mid-outbox-drain. Restored to the front of the outbox at the very beginning
//!   of the next turn by `Core::restore_requeued` so no event is ever lost or reordered.
//! - **`local`**: Used exclusively in single threaded mode (`Flavor::St`), where `outbox::pump`
//!   routes asynchronous completions, watch events, and non-barrier session events into this
//!   handle-local FIFO until a protocol `DrainPoint` or barrier `Session` event flushes them into
//!   the caller's active [`Outbox`].
//! - **`peer`**: Populated by `IoCore::connect` when a TCP socket is installed and cleared by
//!   `IoCore::settle` / `CloseTransport` when the transport is torn down. Enables
//!   `Shared::peer_addr` and `Polled::peer_addr` to report the connected server address without
//!   contending on the `io` mutex while the I/O thread is blocked in `poll`.
//! - **`sasl`**: Staged by `Polled::sasl_result`, `Shared::sasl_result`, or native SASL mechanism
//!   runners after evaluating an [`Item::Sasl`] outbox directive, and consumed by `IoCore::prelude`
//!   on the ensuing resume call.
//! - **`set_servers`, `set_servers_runs`, and `next_run_id`**: Coordinate dynamic ensemble updates.
//!   In native drivers, `Core::set_servers_native` records the new host list in `set_servers` for
//!   `IoCore::interest_actions` to pick up as an `Action::Resolve`. In drop-in drivers,
//!   `crate::resolve::SetServersRun` tracks multi-step resolution runs in `set_servers_runs`,
//!   transferring interrupted runs into `set_servers` via `adopt_set_servers` if a callback
//!   unwinds.
//! - **`orphan_close`**: Records an interrupted `ResumeAt::Close` or `ResumeAt::FinalClose` stage
//!   when a callback unwinds during session teardown, allowing a subsequent `zookeeper_close` call
//!   to adopt and finish the orphaned shutdown sequence.

use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::Arc;

use zk_jute::JString;
use zk_proto::{
    AddAuthError, Connection, HostSpec, Millis, Request, SaslError, SaslStep, SecretBytes, Stage,
    Stopped, SubmitError, Submitted, Tokens,
};

/// Result type returned when submitting an operation to the protocol state machine.
pub(crate) type SubmitResult<T> = Result<Submitted<T>, SubmitError<T>>;

use crate::clock::{Clock, MonotonicClock};
use crate::dispatch::ReadGate;
use crate::host::HostLeaves;
use crate::iocore::{DeferPolicy, DriverConfig, StepCtx};
use crate::net::poll::{Poll, RustixPoll};
use crate::net::tls::TlsConnector;
use crate::net::waker::{WakeRx, Waker};
use crate::outbox::{FifoBound, Flavor, Item, Outbox, Pumped, Requeued, ResumeAt, pump};
use crate::resolve::{ResolveCtx, SetServersRun};
use crate::sync::atomic::AtomicBool;
use crate::sync::{Mutex, Unlocked};

/// Immutable platform and configuration services configured at handle creation time.
///
/// In drop-in FFI environments, `zk-ffi` populates this structure from host vtables and SSL
/// parameters. In native Rust clients, values are supplied via client builder configurations.
pub struct Env<T: Tokens> {
    /// Host integration platform leaves providing DNS, logging, and lifecycle hooks.
    pub host: Arc<dyn HostLeaves<T>>,
    /// Configured TLS connector engine, or `None` for unencrypted plaintext transports.
    pub tls: Option<Arc<dyn TlsConnector>>,
    /// Monotonic time provider tracking deadlines and timeouts.
    pub clock: Arc<dyn Clock>,
    /// Socket readiness polling engine (e.g., standard `RustixPoll` or test harness wrapper).
    pub poll: Arc<dyn Poll>,
    /// Operational driver configuration parameters.
    pub cfg: DriverConfig,
}

impl<T: Tokens> Env<T> {
    /// Instantiates default environment services with plaintext transport, monotonic time,
    /// standard `rustix` polling, and default operational configuration.
    #[must_use]
    pub fn new(host: Arc<dyn HostLeaves<T>>) -> Self {
        Self {
            host,
            tls: None,
            clock: Arc::new(MonotonicClock::new()),
            poll: Arc::new(RustixPoll),
            cfg: DriverConfig::default(),
        }
    }

    /// Assembles an ephemeral [`StepCtx`] execution context for driving a single [`crate::IoCore`]
    /// progression turn.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn step_ctx<'a>(
        &'a self,
        core: &'a Mutex<Core<T>>,
        flavor: Flavor,
        defer: DeferPolicy,
        wake: Option<(&'a Waker, &'a WakeRx)>,
        abort: Option<&'a AtomicBool>,
        gate: Option<&'a ReadGate>,
    ) -> StepCtx<'a, T> {
        StepCtx {
            core,
            host: &*self.host,
            tls: self.tls.as_deref(),
            clock: &*self.clock,
            poll: &*self.poll,
            wake,
            abort,
            cfg: &self.cfg,
            flavor,
            defer,
            gate,
        }
    }

    /// Assembles an execution context for driving iterative host DNS resolution loops.
    pub(crate) fn resolve_ctx<'a>(
        &'a self,
        core: &'a Mutex<Core<T>>,
        flavor: Flavor,
    ) -> ResolveCtx<'a, T> {
        ResolveCtx {
            core,
            host: &*self.host,
            clock: &*self.clock,
            flavor,
        }
    }
}

/// Inspects the protocol connection under the `core` mutex, returning `None` if already torn down.
///
/// Used by read-only client queries such as `zoo_state` when the caller already holds an
/// [`Unlocked`] witness token.
pub(crate) fn read_conn<T: Tokens, R>(
    core: &Mutex<Core<T>>,
    proof: &mut Unlocked,
    f: impl FnOnce(&Connection<T>) -> R,
) -> Option<R> {
    core.lock(proof).conn.as_deref().map(f)
}

/// Inspects the protocol connection from an external API entry point without requiring caller
/// locks.
pub(crate) fn read_at_entry<T: Tokens, R>(
    core: &Mutex<Core<T>>,
    f: impl FnOnce(&Connection<T>) -> R,
) -> Option<R> {
    read_conn(core, &mut Unlocked::new_at_entry(), f)
}

/// Executes a closure mutating the protocol connection under the `core` mutex at an external API
/// boundary.
///
/// Returns `None` if the connection has already been claimed by final handle closure.
pub(crate) fn with_conn_at_entry<T: Tokens, R>(
    core: &Mutex<Core<T>>,
    now: Millis,
    f: impl FnOnce(&mut Connection<T>, Millis) -> R,
) -> Option<R> {
    let mut proof = Unlocked::new_at_entry();
    core.lock(&mut proof).conn.as_deref_mut().map(|c| f(c, now))
}

/// Asynchronous response from the host environment or SASL mechanism to a SASL `Start` or `Step`
/// outbox challenge.
///
/// In FFI bindings, `zk-ffi` packages Cyrus SASL callback outcomes into this enum via
/// `sasl_result`; in native drivers, `SaslSession` results are stored directly. The answer is
/// consumed by `IoCore::prelude` on the subsequent resume turn and forwarded to
/// `Connection::handle_sasl_started` or `Connection::handle_sasl_stepped`.
#[derive(Debug)]
pub enum SaslAnswer {
    /// Result payload responding to an initial SASL `Start` challenge.
    Started {
        /// Correlation generation identifier matching the originating `Start` item.
        generation: u32,
        /// Negotiated mechanism name and initial client token payload, or the authentication error.
        result: Result<(String, SaslStep), SaslError>,
    },
    /// Result payload responding to an intermediate SASL `Step` server challenge.
    Stepped {
        /// Correlation generation identifier matching the originating `Step` item.
        generation: u32,
        /// Subsequent client challenge-response token payload, or the authentication error.
        result: Result<SaslStep, SaslError>,
    },
}

/// Synchronized internal state protected by the `core` mutex.
pub(crate) struct Core<T: Tokens> {
    /// Underlying protocol state machine, taken during terminal handle finalization.
    pub(crate) conn: Option<Box<Connection<T>>>,
    /// Suspended barrier state awaiting resolution before resuming connection progression.
    pub(crate) stopped: Option<Stopped>,
    /// Outbox items requeued due to unhandled callback unwinding, prioritized in the next turn.
    pub(crate) requeued: Option<Requeued<T>>,
    /// single threaded driver local FIFO buffer; unpopulated in multithreaded or native modes.
    pub(crate) local: VecDeque<Item<T>>,
    /// Remote socket address of the active network transport, cached for lock-free-of-`io` queries.
    pub(crate) peer: Option<SocketAddr>,
    /// Pending SASL authentication response delivered to the protocol core on the next resume.
    pub(crate) sasl: Option<SaslAnswer>,
    /// Dynamic server reconfiguration list awaiting background DNS resolution, with its generation
    /// tag.
    pub(crate) set_servers: Option<(u64, Vec<HostSpec>)>,
    /// Active dynamic server reconfiguration operations awaiting completion resume steps.
    pub(crate) set_servers_runs: Vec<SetServersRun>,
    /// Sequence generator allocating unique identifiers for dynamic server update runs.
    pub(crate) next_run_id: u64,
    /// Deferred close progression state orphaned by callback unwinding, resumed on subsequent
    /// close calls.
    pub(crate) orphan_close: Option<ResumeAt>,
}

impl<T: Tokens> Core<T> {
    /// Wraps an active connection instance in a new core state container.
    pub(crate) fn new(conn: Box<Connection<T>>) -> Self {
        Self {
            conn: Some(conn),
            stopped: None,
            requeued: None,
            local: VecDeque::new(),
            peer: None,
            sasl: None,
            set_servers: None,
            set_servers_runs: Vec::new(),
            next_run_id: 0,
            orphan_close: None,
        }
    }
}

impl<T: Tokens> Core<T> {
    /// Drains pending protocol outputs from `self.conn` into `ob`, applying the FIFO routing
    /// semantics of `flavor` (`Publish` for `Mt`, `Local` for `St`, `Inline` for `Native`).
    ///
    /// Returns `true` when encountering a protocol barrier requiring the driver turn to yield
    /// `Progress::More` (`ZKRS_MORE`). Any [`Stopped`] continuation token parked behind the barrier
    /// is stored in `self.stopped` to be resumed via [`Self::resume_stopped`] on the next turn.
    pub(crate) fn pump(
        &mut self,
        flavor: Flavor,
        ob: &mut Outbox<T>,
        host: &dyn HostLeaves<T>,
    ) -> bool {
        let Some(conn) = self.conn.as_deref_mut() else {
            return false;
        };
        let bound = match flavor {
            Flavor::Mt => FifoBound::Publish,
            Flavor::St => FifoBound::Local(&mut self.local),
            Flavor::Native => FifoBound::Inline,
        };
        match pump(conn, ob, bound, host) {
            Pumped::Drained => false,
            Pumped::Barrier(stopped) => {
                if stopped.is_some() {
                    self.stopped = stopped;
                }
                true
            }
        }
    }

    /// Resumes the protocol state machine from a previously recorded barrier suspension point.
    pub(crate) fn resume_stopped(&mut self, now: Millis) {
        if let (Some(conn), Some(stopped)) = (self.conn.as_deref_mut(), self.stopped.take()) {
            conn.resume(stopped, now);
        }
    }

    /// Submits a request to the protocol state machine using the provided submission closure.
    ///
    /// If the connection has already been destroyed, returns a validation-stage [`SubmitError`]
    /// with the given error code and surrenders the completion token back to the caller.
    pub(crate) fn submit_or(
        &mut self,
        req: Request<T>,
        now: Millis,
        code: i32,
        send: impl FnOnce(&mut Connection<T>, Request<T>, Millis) -> SubmitResult<T>,
    ) -> SubmitResult<T> {
        match self.conn.as_deref_mut() {
            Some(conn) => send(conn, req, now),
            None => Err(SubmitError {
                stage: Stage::Validate,
                code,
                token: req.token,
            }),
        }
    }

    /// Enrolls an authentication credential with the protocol state machine.
    ///
    /// Returns an [`AddAuthError`] surrendering the completion token back to the caller if the
    /// connection has already been destroyed.
    pub(crate) fn add_auth_or(
        &mut self,
        scheme: JString,
        cred: SecretBytes,
        done: Option<T::Auth>,
        now: Millis,
        code: i32,
    ) -> Result<(), AddAuthError<T>> {
        match self.conn.as_deref_mut() {
            Some(conn) => conn.add_auth(scheme, cred, done, now),
            None => Err(AddAuthError { code, done }),
        }
    }

    /// Supplies an updated dynamic server list to the protocol state machine in native driver mode.
    ///
    /// Registers the host specs in `self.set_servers` so the next `IoCore::interest_step` initiates
    /// resolution for the new generation. Returns the assigned resolution generation tag, or `0` if
    /// no active connection exists.
    pub(crate) fn set_servers_native(&mut self, hosts: Vec<HostSpec>, now: Millis) -> u64 {
        let generation = self
            .conn
            .as_deref_mut()
            .map_or(0, |conn| conn.set_servers(hosts.clone(), now));
        if generation != 0 {
            self.set_servers = Some((generation, hosts));
        }
        generation
    }

    /// Restores previously requeued outbox items to the front of `ob`.
    ///
    /// Returns `true` if items were restored, requiring the driver to yield `Progress::More`
    /// (`ZKRS_MORE`) so the restored items are drained before any new protocol work runs.
    pub(crate) fn restore_requeued(&mut self, ob: &mut Outbox<T>) -> bool {
        match self.requeued.take() {
            Some(r) if !r.is_empty() => {
                ob.restore(r);
                true
            }
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{TestHost, test_connection};
    use zk_proto::DriverMode;
    use zk_testkit::tokens::TestTokens;

    #[test]
    fn pump_of_a_new_core_has_no_barrier() {
        let host = TestHost::new();
        let mut core: Core<TestTokens> = Core::new(Box::new(test_connection(DriverMode::ST)));
        let mut ob = Outbox::new();
        assert!(!core.pump(Flavor::St, &mut ob, &host));
        assert!(!core.restore_requeued(&mut ob));
        core.resume_stopped(Millis(0));
        assert!(ob.is_empty());
    }
}
