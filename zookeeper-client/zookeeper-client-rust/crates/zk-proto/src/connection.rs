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

//! Primary client protocol state machine and shared core coordinator.
//!
//! [`Connection`] integrates sub-state machines governing request serialization, frame
//! deserialization, watcher indexing, authentication lifecycles, timer deadlines, host shuffling,
//! and teardown flows. To preserve strict determinism and modular encapsulation, each sub-state
//! machine maintains ownership of its respective domain fields, exposing cohesive APIs invoked by
//! this central connection engine.

use crate::auth::AuthState;
use crate::close::CloseState;
use crate::compat::Compat;
use crate::config::{ClientId, Config, DriverMode};
use crate::hooks::{DefaultWatcher, LogFilter, Rng, Tokens};
use crate::hosts::Hosts;
use crate::interest::InterestState;
use crate::log::{LogArg, LogSite, Logger};
use crate::output::{Output, Outputs, Stopped, Work};
use crate::recv::Recv;
use crate::requests::Requests;
use crate::sasl::SaslState;
use crate::session::SessionState;
use crate::state::{Phase, State};
use crate::teardown::TeardownState;
use crate::time::Millis;
use crate::timers::Timers;
use crate::watches::Watches;
use core::fmt;
use zeroize::Zeroize;

/// Shared protocol state context accessible across multiple sub-state machines.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Core {
    /// Externally visible session state as reported through C APIs (`zoo_state`).
    pub(crate) state: State,
    /// Granular internal transport connection and handshake phase.
    pub(crate) phase: Phase,
    /// Active 64-bit session identifier and cryptographic session password.
    pub(crate) client_id: ClientId,
    /// Active session timeout interval: requested timeout initially, updated to negotiated value
    /// upon handshake completion.
    pub(crate) recv_timeout: i32,
    /// Highest ZooKeeper transaction identifier (`zxid`) observed across processed server reply
    /// headers.
    pub(crate) last_zxid: i64,
    /// Flag indicating whether the current session identifier was previously established on a
    /// read-write quorum member.
    pub(crate) seen_rw_before: bool,
    /// Platform-specific operating system error number hint emitted for legacy C shim `errno`
    /// tracking.
    pub(crate) errno_hint: Option<i32>,
    /// Indicates whether a physical transport socket is actively open.
    pub(crate) has_transport: bool,
    /// Monotonically increasing transport lifecycle epoch incremented on each connection opening
    /// and teardown.
    pub(crate) epoch: u64,
}

/// Central ZooKeeper client protocol state machine.
///
/// Implements deterministic, pure-state protocol processing without direct network I/O, OS threads,
/// or system clock dependencies. External drivers invoke mutating methods synchronously under
/// driver-managed synchronization and process the returned I/O [`crate::action::Action`] directives
/// and [`Output`] events.
pub struct Connection<T: Tokens> {
    pub(crate) cfg: Config,
    pub(crate) compat: Compat,
    pub(crate) mode: DriverMode,
    pub(crate) rng: Box<dyn Rng>,
    pub(crate) default_watcher: Box<dyn DefaultWatcher<T::Watcher>>,
    pub(crate) log: Logger,
    pub(crate) out: Outputs<T>,
    pub(crate) core: Core,
    pub(crate) requests: Requests<T>,
    pub(crate) recv: Recv,
    pub(crate) watches: Watches<T::Watcher>,
    pub(crate) auth: AuthState<T>,
    pub(crate) sasl: SaslState,
    pub(crate) hosts: Hosts,
    pub(crate) timers: Timers,
    pub(crate) interest: InterestState,
    pub(crate) session: SessionState,
    pub(crate) teardown: TeardownState<T>,
    pub(crate) close: CloseState,
}

/// Undelivered tokens, unconsumed outputs, and in-flight operations retained upon connection drop.
pub struct Leftovers<T: Tokens> {
    /// Request completion handles retained in-flight when the connection terminated, ordered oldest
    /// to newest.
    pub completions: Vec<T::Completion>,
    /// Asynchronous authentication tokens retained in-flight upon termination.
    pub auth: Vec<T::Auth>,
    /// Queued protocol output events that were not retrieved via [`Connection::poll_output`].
    pub outputs: Vec<Output<T>>,
}

impl<T: Tokens> fmt::Debug for Leftovers<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Leftovers")
            .field("completions", &self.completions.len())
            .field("auth", &self.auth.len())
            .field("outputs", &self.outputs)
            .finish()
    }
}

impl<T: Tokens> fmt::Debug for Connection<T> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Connection")
            .field("state", &self.core.state)
            .field("phase", &self.core.phase)
            .field("client_id", &self.core.client_id)
            .field("pending", &self.requests.fifo.len())
            .field("outputs", &self.out)
            .finish_non_exhaustive()
    }
}

// Securely zeroize sensitive session password buffers upon connection deallocation.
impl<T: Tokens> Drop for Connection<T> {
    fn drop(&mut self) {
        self.core.client_id.passwd.zeroize();
        self.cfg.client_id.passwd.zeroize();
    }
}

impl<T: Tokens> Connection<T> {
    /// Initializes a fresh protocol connection engine resting in [`State::NotConnected`] (999)
    /// without active transport.
    ///
    /// Upon initial driving, [`Self::poll_action`] yields an initial
    /// [`crate::action::Action::Resolve`] directive.
    #[must_use]
    pub fn new(
        cfg: Config,
        compat: Compat,
        mode: DriverMode,
        rng: Box<dyn Rng>,
        log: Box<dyn LogFilter>,
        default_watcher: Box<dyn DefaultWatcher<T::Watcher>>,
        now: Millis,
    ) -> Self {
        let core = Core {
            state: State::NotConnected,
            phase: Phase::Idle,
            client_id: cfg.client_id,
            recv_timeout: cfg.timeout_ms,
            last_zxid: 0,
            seen_rw_before: cfg.client_id.session_id != 0,
            errno_hint: None,
            has_transport: false,
            epoch: 0,
        };
        Self {
            requests: Requests::new(cfg.xid_seed),
            recv: Recv::new(compat.max_frame_len),
            watches: Watches::new(compat.watch_table, cfg.c_char_signed),
            auth: AuthState::new(),
            sasl: SaslState::new(),
            hosts: Hosts::new(cfg.hosts.clone(), cfg.resolve_delay_ms),
            timers: Timers::new(now),
            interest: InterestState::new(),
            session: SessionState::default(),
            teardown: TeardownState::new(),
            close: CloseState::default(),
            out: Outputs::new(),
            log: Logger::new(log),
            core,
            cfg,
            compat,
            mode,
            rng,
            default_watcher,
        }
    }

    /// Retrieves the next pending protocol output event.
    ///
    /// Yields `None` when the output queue is drained, or immediately after emitting a
    /// synchronization barrier output. When a barrier is reached, the driver acquires the
    /// suspension handle via [`Self::take_stopped`], dispatches the barrier event to the
    /// application, and subsequently resumes the suspended stage via [`Self::resume`].
    pub fn poll_output(&mut self) -> Option<Output<T>> {
        self.out.poll()
    }

    /// Indicates whether output events are pending retrieval via [`Self::poll_output`].
    ///
    /// Evaluates to `false` if an active stage is currently suspended at an undischarged barrier.
    #[must_use]
    pub fn has_output(&self) -> bool {
        self.out.has_output()
    }

    /// Extracts the execution suspension token for a stage halted at the most recently popped
    /// barrier.
    ///
    /// Once extracted, subsequent calls to [`Self::poll_output`] continue yielding independent
    /// queued events.
    pub fn take_stopped(&mut self) -> Option<Stopped> {
        self.out.take_stopped()
    }

    /// Resumes execution of a previously suspended protocol stage following barrier completion.
    ///
    /// Passing an invalid, stale, or discarded suspension token is safely ignored as a no-op.
    pub fn resume(&mut self, work: Stopped, now: Millis) {
        let Some(job) = self.out.take_suspended(work) else {
            return;
        };
        match job {
            Work::Hold => {}
            Work::Teardown(j) => self.continue_teardown(j, now),
            Work::Close(j) => self.continue_close(j, now),
            Work::AuthReply(j) => self.continue_auth_reply(j, now),
            Work::Finalize(j) => self.continue_finalize(j, now),
        }
    }

    /// Returns the currently visible session state matching C `zoo_state`.
    #[must_use]
    pub fn state(&self) -> State {
        self.core.state
    }

    /// Returns the active session timeout interval in milliseconds (negotiated value
    /// post-handshake, requested value prior).
    #[must_use]
    pub fn recv_timeout(&self) -> i32 {
        self.core.recv_timeout
    }

    /// Returns the current session identity tuple (session ID and password).
    #[must_use]
    pub fn client_id(&self) -> ClientId {
        self.core.client_id
    }

    /// Returns the highest ZooKeeper transaction ID (`zxid`) acknowledged by the cluster.
    #[must_use]
    pub fn last_zxid(&self) -> i64 {
        self.core.last_zxid
    }

    /// Indicates whether a physical transport connection is open and active.
    #[must_use]
    pub fn has_transport(&self) -> bool {
        self.core.has_transport
    }

    /// Indicates whether any internal protocol stage is currently suspended awaiting external
    /// barrier resolution.
    #[must_use]
    pub fn is_suspended(&self) -> bool {
        self.out.is_suspended()
    }

    /// Retrieves and clears the platform-specific operating system error number hint emitted by
    /// state machine transitions.
    pub fn take_errno_hint(&mut self) -> Option<i32> {
        self.core.errno_hint.take()
    }

    /// Disassembles the connection and extracts all retained tokens, in-flight operations, and
    /// unconsumed outputs.
    ///
    /// Returned completions are ordered: suspended stages first, followed by forced-close snapshot
    /// work, and lastly the pending FIFO request queue.
    #[must_use]
    pub fn into_leftovers(mut self) -> Leftovers<T> {
        let mut completions = Vec::new();
        let mut auth = Vec::new();
        let (outputs, works) = core::mem::replace(&mut self.out, Outputs::new()).into_parts();
        for w in works {
            let (c, a) = w.into_tokens();
            completions.extend(c);
            auth.extend(a);
        }
        if let Some(snap) = self.teardown.forced.take() {
            let (c, a) = snap.into_tokens();
            completions.extend(c);
            auth.extend(a);
        }
        completions.extend(self.requests.fifo.drain(..).map(|p| p.token));
        auth.extend(self.auth.take_tokens());
        Leftovers {
            completions,
            auth,
            outputs,
        }
    }

    /// Updates the externally observable session state.
    pub(crate) fn set_state(&mut self, state: State) {
        self.core.state = state;
    }

    /// Records an operating system error hint for legacy C client shim extraction.
    pub(crate) fn set_errno_hint(&mut self, errno: i32) {
        self.core.errno_hint = Some(errno);
    }

    /// Marks the physical transport connection as open and advances the transport lifecycle epoch.
    pub(crate) fn open_transport(&mut self) -> u64 {
        // Assert that pinned/lent frames do not survive transport boundaries under test fixtures.
        #[cfg(test)]
        assert!(
            !self.requests.tx.iter().any(|f| f.is_lent()),
            "a lent frame outlives its transport"
        );
        self.core.has_transport = true;
        self.core.epoch = self.core.epoch.wrapping_add(1);
        self.core.epoch
    }

    /// Marks the active transport as disconnected and increments the transport lifecycle epoch.
    pub(crate) fn lose_transport(&mut self) {
        self.core.has_transport = false;
        self.core.epoch = self.core.epoch.wrapping_add(1);
    }

    /// Emits a structured diagnostic log record if the active log filter permits the target site's
    /// severity.
    pub(crate) fn emit_log<F>(&mut self, site: LogSite, args: F)
    where
        F: FnOnce() -> Vec<LogArg>,
    {
        let record = self.log.record(site, args);
        self.out.push_log(record);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hooks::{AllLevels, NoDefaultWatcher};

    struct Tk;
    impl Tokens for Tk {
        type Completion = u32;
        type Watcher = u32;
        type Auth = u32;
    }

    struct FixedRng;
    impl Rng for FixedRng {
        fn on_resolution(&mut self) {}
        fn random(&mut self) -> i64 {
            0
        }
        fn drand48(&mut self) -> f64 {
            0.0
        }
    }

    #[test]
    fn new_connection_is_idle() {
        let conn: Connection<Tk> = Connection::new(
            Config::legacy(30_000),
            Compat::LEGACY_C,
            DriverMode::ST,
            Box::new(FixedRng),
            Box::new(AllLevels),
            Box::new(NoDefaultWatcher),
            Millis(0),
        );
        assert_eq!(conn.state(), State::NotConnected);
        assert_eq!(conn.recv_timeout(), 30_000);
        assert!(!conn.has_transport());
        assert!(!conn.is_suspended());
        assert_eq!(conn.pending_len(), 0);
        assert!(conn.hosts.initial && conn.hosts.resolve_due);
        let left = conn.into_leftovers();
        assert!(left.completions.is_empty() && left.auth.is_empty() && left.outputs.is_empty());
    }
}
