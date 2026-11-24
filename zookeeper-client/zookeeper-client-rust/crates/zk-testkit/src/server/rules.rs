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

//! Declarative frame interception, protocol mutation, and fault injection rules for the mock
//! server.
//!
//! [`Rule`] specifications evaluate pattern matchers against inbound wire frames (connection
//! handshakes, operation opcodes, target paths, frame sequence numbers, or link ordinals) and
//! substitute standard server responses with scripted [`RuleAction`] fault behaviors. Rules are
//! evaluated sequentially with support for match skipping and execution invocation budgets.

/// Scope of inbound wire frames eligible for rule matching.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MatchKind {
    /// Matches only the initial connection handshake frame (`ConnectRequest`).
    Connect,
    /// Matches only standard client request frames dispatched after session establishment.
    Request,
    /// Matches all inbound frames unconditionally.
    Any,
}

/// Simulated connection handshake response profile for `ConnectRequest` frames.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum HandshakeStyle {
    /// Standard protocol response creating a new session, renewing an existing session, or
    /// reporting expiration.
    Normal,
    /// Explicit session expiration response emitting `sessionId = 0` and `timeout = 0`, followed
    /// by immediate link closure.
    Expired,
    /// Standard handshake response with an overridden `read_only` capability flag.
    ReadOnly(bool),
    /// Standard handshake response omitting the trailing `read_only` byte (36-byte pre-3.4 wire
    /// format).
    Short,
    /// Standard handshake response negotiating an explicit timeout duration in milliseconds.
    Timeout(i32),
    /// Predefined handshake response returning exact field values.
    Exact {
        /// Negotiated session timeout in milliseconds.
        timeout: i32,
        /// Allocated or reaffirmed session identifier.
        session_id: i64,
        /// Password secret bytes.
        passwd: Vec<u8>,
        /// Read-only connection mode indicator.
        read_only: bool,
    },
}

/// Action executed by the simulated server when an inbound frame matches a rule.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RuleAction {
    /// Process the request normally but artificially delay outbound response delivery by `ms`
    /// milliseconds, preserving strict FIFO response ordering across the link.
    Delay(u64),
    /// Process the request into tree state normally while suppressing transmission of the reply
    /// frame.
    Drop,
    /// Discard the frame without processing state mutations and emit no response.
    Ignore,
    /// Reject the request immediately, returning a standalone reply header containing the
    /// specified ZooKeeper error code.
    Error(i32),
    /// Discard the frame and gracefully close the link after flushing previously queued outbound
    /// frames.
    Close,
    /// Process the request normally and deliver the reply, then immediately initiate graceful link
    /// closure.
    CloseAfterReply,
    /// Abruptly reset the transport link (`ECONNRESET`), discarding all unread and queued
    /// in-flight data.
    Reset,
    /// Emit unformatted arbitrary bytes onto the wire without framing headers to test parser fault
    /// tolerance.
    Garbage(Vec<u8>),
    /// Trigger immediate session expiration on the server, deleting ephemeral znodes, and close
    /// the transport link.
    ExpireSession,
    /// Respond to a connection handshake request using the specified handshake style.
    Handshake(HandshakeStyle),
}

/// Declarative fault injection and frame interception specification combining matching criteria,
/// invocation budgets, and actions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rule {
    pub(crate) kind: MatchKind,
    pub(crate) op: Option<i32>,
    pub(crate) path: Option<Vec<u8>>,
    pub(crate) xid: Option<i32>,
    pub(crate) frame: Option<u64>,
    pub(crate) conn: Option<u64>,
    pub(crate) skip: u32,
    pub(crate) times: Option<u32>,
    pub(crate) action: RuleAction,
}

impl Rule {
    fn of(kind: MatchKind) -> Self {
        Self {
            kind,
            op: None,
            path: None,
            xid: None,
            frame: None,
            conn: None,
            skip: 0,
            times: None,
            action: RuleAction::Drop,
        }
    }

    /// Constructs a rule matching post-handshake request frames.
    #[must_use]
    pub fn on_request() -> Self {
        Self::of(MatchKind::Request)
    }

    /// Constructs a rule matching connection handshake frames (`ConnectRequest`).
    #[must_use]
    pub fn on_connect() -> Self {
        Self::of(MatchKind::Connect)
    }

    /// Constructs a rule matching all inbound frames regardless of protocol phase.
    #[must_use]
    pub fn on_any() -> Self {
        Self::of(MatchKind::Any)
    }

    /// Configures the server to close a link immediately after delivering the reply to its `n`-th
    /// frame (1-indexed).
    #[must_use]
    pub fn close_after(n: u64) -> Self {
        Self::on_any()
            .frame(n.saturating_sub(1))
            .then(RuleAction::CloseAfterReply)
    }

    /// Restricts the rule to requests matching a specific operation opcode.
    #[must_use]
    pub const fn op(mut self, op: i32) -> Self {
        self.op = Some(op);
        self
    }

    /// Restricts the rule to requests targeting a specific znode path.
    #[must_use]
    pub fn path(mut self, path: &str) -> Self {
        self.path = Some(path.as_bytes().to_vec());
        self
    }

    /// Restricts the rule to requests matching a specific client transaction identifier.
    #[must_use]
    pub const fn xid(mut self, xid: i32) -> Self {
        self.xid = Some(xid);
        self
    }

    /// Restricts the rule to the frame matching a specific 0-indexed link sequence number.
    #[must_use]
    pub const fn frame(mut self, index: u64) -> Self {
        self.frame = Some(index);
        self
    }

    /// Restricts the rule to the transport link matching a specific 0-indexed connection ordinal.
    #[must_use]
    pub const fn conn(mut self, ordinal: u64) -> Self {
        self.conn = Some(ordinal);
        self
    }

    /// Skips the first `n` matching frames before triggering the rule action.
    #[must_use]
    pub const fn skip(mut self, n: u32) -> Self {
        self.skip = n;
        self
    }

    /// Restricts the maximum number of times this rule action will execute.
    #[must_use]
    pub const fn times(mut self, n: u32) -> Self {
        self.times = Some(n);
        self
    }

    /// Limits the rule action to execute exactly once.
    #[must_use]
    pub const fn once(self) -> Self {
        self.times(1)
    }

    /// Configures the fault action to execute upon matching.
    #[must_use]
    pub fn then(mut self, action: RuleAction) -> Self {
        self.action = action;
        self
    }

    /// Returns a reference to the configured rule action.
    #[must_use]
    pub const fn action(&self) -> &RuleAction {
        &self.action
    }
}

/// Extracted metadata attributes of an inbound wire frame evaluated against rule matchers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct FrameFacts<'a> {
    pub(crate) connect: bool,
    pub(crate) op: i32,
    pub(crate) xid: i32,
    pub(crate) path: Option<&'a [u8]>,
    pub(crate) frame: u64,
    pub(crate) conn: u64,
}

/// Active rule instance paired with its runtime match and execution counters.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RuleState {
    pub(crate) rule: Rule,
    pub(crate) matched: u32,
    pub(crate) fired: u32,
}

impl RuleState {
    pub(crate) const fn new(rule: Rule) -> Self {
        Self {
            rule,
            matched: 0,
            fired: 0,
        }
    }

    fn matches(&self, f: &FrameFacts<'_>) -> bool {
        let r = &self.rule;
        let kind_ok = match r.kind {
            MatchKind::Connect => f.connect,
            MatchKind::Request => !f.connect,
            MatchKind::Any => true,
        };
        kind_ok
            && r.op.is_none_or(|o| !f.connect && o == f.op)
            && r.xid.is_none_or(|x| !f.connect && x == f.xid)
            && r.path
                .as_deref()
                .is_none_or(|p| f.path.is_some_and(|fp| fp == p))
            && r.frame.is_none_or(|n| n == f.frame)
            && r.conn.is_none_or(|c| c == f.conn)
    }

    /// Increments match counters and returns the rule action if within execution budget.
    pub(crate) fn consider(&mut self, f: &FrameFacts<'_>) -> Option<RuleAction> {
        if !self.matches(f) {
            return None;
        }
        if self.rule.times.is_some_and(|t| self.fired >= t) {
            return None;
        }
        self.matched = self.matched.saturating_add(1);
        if self.matched <= self.rule.skip {
            return None;
        }
        self.fired = self.fired.saturating_add(1);
        Some(self.rule.action.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts(connect: bool, op: i32, frame: u64) -> FrameFacts<'static> {
        FrameFacts {
            connect,
            op,
            xid: 1,
            path: Some(b"/a"),
            frame,
            conn: 0,
        }
    }

    #[test]
    fn skip_and_times() {
        let mut s = RuleState::new(
            Rule::on_request()
                .op(4)
                .skip(1)
                .once()
                .then(RuleAction::Drop),
        );
        assert_eq!(s.consider(&facts(false, 4, 1)), None);
        assert_eq!(s.consider(&facts(false, 4, 2)), Some(RuleAction::Drop));
        assert_eq!(s.consider(&facts(false, 4, 3)), None);
        assert_eq!(s.consider(&facts(false, 5, 3)), None);
    }

    #[test]
    fn kinds_and_close_after() {
        let mut c = RuleState::new(Rule::on_connect().then(RuleAction::Reset));
        assert_eq!(c.consider(&facts(false, 4, 0)), None);
        assert_eq!(c.consider(&facts(true, 0, 0)), Some(RuleAction::Reset));
        let mut a = RuleState::new(Rule::close_after(2));
        assert_eq!(a.consider(&facts(true, 0, 0)), None);
        assert_eq!(
            a.consider(&facts(false, 4, 1)),
            Some(RuleAction::CloseAfterReply)
        );
        let mut p = RuleState::new(Rule::on_request().path("/b"));
        assert_eq!(p.consider(&facts(false, 4, 1)), None);
    }
}
