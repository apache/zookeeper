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

//! Linear completion tokens, correlation tickets, and watcher identities for deterministic wire
//! testing.
//!
//! Enforces linear type invariants for asynchronous operation completions and authentication
//! requests. Completion tokens ([`CompletionId`], [`AuthId`]) explicitly omit `Clone` to ensure
//! that every issued request is accounted for exactly once across the client state machine
//! lifecycle. Correlated lightweight copyable tickets ([`Ticket`], [`AuthTicket`]) allow test
//! harnesses and event recorders to verify delivery invariants, while [`WatcherId`] models the C
//! binding's `(fn, context)` watcher deduplication key.

use zk_proto::Tokens;

/// Test double implementation of [`Tokens`] binding linear token types to the testkit framework.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TestTokens {}

impl Tokens for TestTokens {
    type Completion = CompletionId;
    type Watcher = WatcherId;
    type Auth = AuthId;
}

/// Linear operation completion token tracking in-flight client request lifecycle.
///
/// Intentionally omits `Clone` to enforce at compile time that each token enters the client
/// pipeline once and emerges through the completion queue exactly once.
#[derive(Debug, PartialEq, Eq, Hash)]
pub struct CompletionId(u64);

impl CompletionId {
    /// Constructs a new linear completion token with the specified unique identifier.
    #[must_use]
    pub const fn new(id: u64) -> Self {
        Self(id)
    }

    /// Returns the underlying numeric identifier of this completion token.
    #[must_use]
    pub const fn id(&self) -> u64 {
        self.0
    }

    /// Extracts a copyable [`Ticket`] handle referencing the token's correlation identifier.
    #[must_use]
    pub const fn ticket(&self) -> Ticket {
        Ticket(self.0)
    }
}

/// Copyable correlation ticket corresponding to a linear [`CompletionId`], used by recorders for
/// delivery auditing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Ticket(pub u64);

/// Linear authentication completion token tracking asynchronous credential registration
/// (`add_auth`).
///
/// Omits `Clone` to enforce strict single-completion semantics across authentication handshakes.
#[derive(Debug, PartialEq, Eq, Hash)]
pub struct AuthId(u64);

impl AuthId {
    /// Constructs a new linear authentication token with the specified unique identifier.
    #[must_use]
    pub const fn new(id: u64) -> Self {
        Self(id)
    }

    /// Returns the underlying numeric identifier of this authentication token.
    #[must_use]
    pub const fn id(&self) -> u64 {
        self.0
    }

    /// Extracts a copyable [`AuthTicket`] handle referencing the token's correlation identifier.
    #[must_use]
    pub const fn ticket(&self) -> AuthTicket {
        AuthTicket(self.0)
    }
}

/// Copyable correlation ticket corresponding to a linear [`AuthId`], used by recorders for
/// delivery auditing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct AuthTicket(pub u64);

/// Watcher identity descriptor modeling the legacy C client's `(watcher_fn, context)` pointer pair.
///
/// Equality comparisons reflect the watcher deduplication semantics enforced by the client core.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct WatcherId {
    /// Unique identifier representing the watcher callback function pointer.
    pub fn_id: u32,
    /// Unique identifier representing the arbitrary caller context pointer.
    pub ctx_id: u32,
}

impl WatcherId {
    /// Constructs a watcher identity combining function and context identifiers.
    #[must_use]
    pub const fn new(fn_id: u32, ctx_id: u32) -> Self {
        Self { fn_id, ctx_id }
    }
}

/// Monotonic token generator dispensing unique linear completion tokens paired with copyable audit
/// tickets.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct TokenMint {
    next: u64,
}

impl Default for TokenMint {
    fn default() -> Self {
        Self::new()
    }
}

impl TokenMint {
    /// Initializes a token mint configured to allocate identifiers starting from 1.
    #[must_use]
    pub const fn new() -> Self {
        Self { next: 1 }
    }

    fn take(&mut self) -> u64 {
        let id = self.next;
        self.next = self.next.wrapping_add(1);
        id
    }

    /// Mints a fresh linear [`CompletionId`] paired with its correlating audit [`Ticket`].
    pub fn completion(&mut self) -> (CompletionId, Ticket) {
        let id = self.take();
        (CompletionId(id), Ticket(id))
    }

    /// Mints a fresh linear [`AuthId`] paired with its correlating audit [`AuthTicket`].
    pub fn auth(&mut self) -> (AuthId, AuthTicket) {
        let id = self.take();
        (AuthId(id), AuthTicket(id))
    }
}
