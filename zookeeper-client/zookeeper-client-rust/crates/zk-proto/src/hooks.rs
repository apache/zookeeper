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

//! Driver extension hooks and generic token abstractions.
//!
//! Because the protocol engine remains strictly agnostic of specific driver threading,
//! memory management, and runtime environments, drivers inject concrete types via the
//! [`Tokens`] trait and customize runtime behaviors via hook traits. Hooks are invoked
//! synchronously during protocol state transitions; implementations must remain nonblocking
//! and must not re-enter application code or protocol methods.

use std::net::SocketAddr;

use crate::action::{SaslError, SaslStep};
use crate::log::LogLevel;

/// Generic associated token types binding driver-specific callback and watcher representations.
///
/// The state machine stores, correlates, and propagates these tokens without inspecting
/// or interpreting their internal structures.
pub trait Tokens {
    /// Completion handle associated with an in-flight request, such as an asynchronous
    /// callback closure or a synchronization channel sender. Ingested during operation
    /// submission and emitted exactly once upon response completion or failure teardown.
    type Completion;
    /// Watcher registration token identifying client side watch interests. Must implement
    /// equality comparisons to support duplicate detection and multi-registration pruning.
    type Watcher: Clone + Eq;
    /// Completion handle associated with asynchronous authentication operations (`add_auth`).
    type Auth;
}

/// Pluggable pseudorandom number generator driving ensemble server randomization.
///
/// In legacy drop-in compatibility mode, this interface delegates directly to POSIX
/// libc `random()` and `drand48()` to preserve historical server selection sequences.
pub trait Rng: Send {
    /// Signals host list resolution completion, triggering deterministic re-seeding
    /// emulating C ZooKeeper's `setup_random()` sequence prior to server shuffling.
    fn on_resolution(&mut self);
    /// Generates a non-negative pseudo-random integer in the range `[0, 2^31 - 1]`.
    fn random(&mut self) -> i64;
    /// Generates a floating-point pseudo-random value uniformly distributed over `[0.0, 1.0)`.
    fn drand48(&mut self) -> f64;
}

/// Diagnostic log level filter controlling emission of internal protocol events.
///
/// The protocol state machine queries this filter before constructing structured
/// [`crate::log::LogRecord`] instances to eliminate unnecessary formatting overhead.
pub trait LogFilter: Send {
    /// Determines whether log messages of the specified diagnostic severity should be generated.
    fn enabled(&self, level: LogLevel) -> bool;
}

/// Provider for the session-wide default watcher.
///
/// In legacy compatibility mode, queries the active C-style `(watcher_fn, context)` pair.
/// Native async clients typically register discrete watch handlers and return `None`.
pub trait DefaultWatcher<W>: Send {
    /// Captures a snapshot of the currently configured session default watcher.
    fn snapshot(&self) -> Option<W>;
}

/// State machine interface for pluggable SASL authentication mechanisms.
///
/// Provides the step-by-step negotiation protocol interface for SASL handshakes.
/// Invoked by native drivers outside the core state machine lock to allow asynchronous
/// authentication engines (e.g. Kerberos/GSSAPI) to complete cryptographic challenges.
pub trait SaslSession: Send {
    /// Initiates the SASL negotiation sequence, returning the selected mechanism name and initial
    /// token.
    ///
    /// # Errors
    ///
    /// Returns a [`SaslError`] if mechanism initialization fails, prompting the protocol
    /// state machine to transition into session failure and close the underlying transport.
    fn start(&mut self) -> Result<(String, SaslStep), SaslError>;

    /// Evaluates a server authentication challenge and produces the client response token.
    ///
    /// A challenge parameter of `None` signifies an empty server challenge token (wire length -1).
    ///
    /// # Errors
    ///
    /// Returns a [`SaslError`] if challenge processing fails, resulting in session termination.
    fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError>;

    /// Informs the SASL provider of connection socket metadata prior to invocation of
    /// [`start`](Self::start).
    ///
    /// Transmits the target server hostname, local socket address, and remote peer address
    /// required for establishing SASL channel bindings and Kerberos service principal names (SPNs).
    fn connection(&mut self, host: &[u8], local: SocketAddr, peer: SocketAddr) {
        let _ = (host, local, peer);
    }
}

/// Permissive [`LogFilter`] implementation enabling log emission across all severity levels.
#[derive(Clone, Copy, Debug, Default)]
pub struct AllLevels;

impl LogFilter for AllLevels {
    fn enabled(&self, _level: LogLevel) -> bool {
        true
    }
}

/// Restrictive [`LogFilter`] implementation suppressing all diagnostic log emissions.
#[derive(Clone, Copy, Debug, Default)]
pub struct NoLevels;

impl LogFilter for NoLevels {
    fn enabled(&self, _level: LogLevel) -> bool {
        false
    }
}

/// Fallback [`DefaultWatcher`] implementation indicating no global default watcher is configured.
#[derive(Clone, Copy, Debug, Default)]
pub struct NoDefaultWatcher;

impl<W> DefaultWatcher<W> for NoDefaultWatcher {
    fn snapshot(&self) -> Option<W> {
        None
    }
}
