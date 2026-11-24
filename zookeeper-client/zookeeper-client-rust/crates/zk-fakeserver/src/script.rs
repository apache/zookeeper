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

//! Programmable fault injection engine for simulating network and protocol faults.
//!
//! A [`Script`] holds an ordered list of conditional [`Rule`] definitions evaluated against
//! inbound client frames. For each frame received by a listener, the engine evaluates rules in
//! registration order and executes the actions of the first matching rule. If no rule matches, the
//! frame is dispatched to the data tree model. If a matching rule contains only non-terminal
//! actions (such as [`Action::Delay`]), the server executes normal state machine processing after
//! the scripted actions take effect.

use std::time::Duration;

use crate::config::{Chunking, HandshakeStyle};
use crate::rng::SplitMix64;

/// Classification of client protocol frames matched by rule triggers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Trigger {
    /// Connection handshake frame (`ConnectRequest`), the first frame on a connection.
    Connect,
    /// Post-handshake request frame.
    Request,
    /// Matches either handshake or post-handshake request frames.
    Any,
}

/// Framing corruption profiles for testing client handling of wire-level protocol violations.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum Malformed {
    /// Emits a frame with a 4-byte length prefix of zero.
    ZeroLength,
    /// Emits a frame with a negative 4-byte length prefix.
    NegativeLength(i32),
    /// Emits a frame whose declared length prefix exceeds the transmitted payload bytes, leaving
    /// the client waiting for the remaining bytes.
    Truncated {
        /// Frame length written in the 4-byte prefix.
        declared: i32,
        /// Partial payload bytes written after the length prefix.
        body: Vec<u8>,
    },
    /// Emits a length-prefixed frame shorter than a 16-byte `ReplyHeader`.
    ShortHeader(Vec<u8>),
}

impl Malformed {
    /// Serializes the malformed frame into wire bytes.
    #[must_use]
    pub fn to_bytes(&self) -> Vec<u8> {
        match self {
            Self::ZeroLength => 0i32.to_be_bytes().to_vec(),
            Self::NegativeLength(n) => n.to_be_bytes().to_vec(),
            Self::Truncated { declared, body } => {
                let mut out = declared.to_be_bytes().to_vec();
                out.extend_from_slice(body);
                out
            }
            Self::ShortHeader(body) => {
                let mut out = Vec::new();
                zk_jute::encode_frame(body, &mut out);
                out
            }
        }
    }
}

/// Network or protocol action executed when a fault-injection rule matches.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Dispatches the frame to the data tree model for normal execution and reply generation.
    Model,
    /// Delays subsequent outbound writes on this connection while preserving FIFO ordering.
    Delay(Duration),
    /// Executes the frame against the data tree model and fires watches, but suppresses the reply.
    DropReply,
    /// Drops the incoming frame without model execution or reply transmission.
    Ignore,
    /// Sends a reply header with the given error code and an empty body.
    Reply {
        /// ZooKeeper error code (such as `ZNONODE`, `ZBADVERSION`, or `ZSESSIONEXPIRED`).
        err: i32,
    },
    /// Sends a reply header with the given error code and custom body bytes.
    ReplyWith {
        /// ZooKeeper status or error code.
        err: i32,
        /// Raw payload bytes appended after the reply header.
        body: Vec<u8>,
    },
    /// Executes the frame in the model, but strips the response body and sends only the 16-byte
    /// `ReplyHeader`.
    HeaderOnly,
    /// Executes the frame in the model, but replaces the `xid` in the emitted reply header.
    RewriteXid(i32),
    /// Executes the frame in the model, but replaces the `err` code in the emitted reply header.
    RewriteErr(i32),
    /// Executes the frame in the model, but truncates the encoded response frame (including the
    /// 4-byte length prefix) to `len` bytes.
    TruncateReply(usize),
    /// Sends an unsolicited reply frame prior to processing the matched request.
    Unsolicited {
        /// Correlation identifier (`xid`) in the unsolicited reply header.
        xid: i32,
        /// Transaction identifier (`zxid`) in the unsolicited reply header.
        zxid: i64,
        /// Status code in the unsolicited reply header.
        err: i32,
        /// Raw payload bytes following the unsolicited reply header.
        body: Vec<u8>,
    },
    /// Sends an out-of-band watch notification (`WatcherEvent`) to this connection.
    Notify {
        /// Watch event type (such as `event::NODE_DATA_CHANGED`).
        event_type: i32,
        /// Session state (such as `event::SYNC_CONNECTED`).
        state: i32,
        /// Znode path carried in the watch event.
        path: String,
    },
    /// Sends a length-prefixed frame with the given payload bytes.
    SendFrame(Vec<u8>),
    /// Writes raw bytes directly to the socket without a length prefix.
    SendRaw(Vec<u8>),
    /// Sends a malformed frame matching the given [`Malformed`] profile.
    Malformed(Malformed),
    /// Closes the TCP connection after flushing previously queued outbound items.
    Close,
    /// Suspends inbound reads and outbound writes while keeping the TCP socket open.
    Stall,
    /// Expires the client session in the cluster state machine and closes the connection.
    ExpireSession,
    /// Replies with `ZAUTHFAILED` and closes the connection.
    RejectAuth,
    /// Updates the outbound chunking strategy for this connection.
    Chunking(Chunking),
    /// Sends a handshake response using `style`, optionally splitting the frame write at byte
    /// offset `split_at`.
    Handshake {
        /// Handshake wire format (modern 37-byte vs legacy 36-byte).
        style: HandshakeStyle,
        /// Optional byte offset at which to split the outbound frame write.
        split_at: Option<usize>,
    },
    /// Sends an expired-session handshake response (`session_id = 0`, zeroed password) and closes
    /// the socket.
    ExpiredHandshake,
}

impl Action {
    /// Returns whether this action answers or terminates the request on its own.
    pub(crate) const fn answers(&self) -> bool {
        !matches!(
            self,
            Self::Delay(_)
                | Self::Unsolicited { .. }
                | Self::Notify { .. }
                | Self::SendFrame(_)
                | Self::SendRaw(_)
                | Self::Malformed(_)
                | Self::Chunking(_)
        )
    }
}

/// Fault-injection rule combining match predicates, firing limits, and scripted actions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    /// Frame category matched by this rule.
    pub trigger: Trigger,
    /// Optional filter matching a 1-based connection ordinal on the listener.
    pub conn: Option<u64>,
    /// Optional filter matching a 1-based request frame index on the connection.
    pub frame: Option<u64>,
    /// Optional filter matching a wire protocol opcode.
    pub op: Option<i32>,
    /// Optional filter requiring an exact match on the target znode path.
    pub path: Option<String>,
    /// Optional filter requiring the target znode path to begin with this prefix.
    pub path_prefix: Option<String>,
    /// Optional filter matching a client request `xid`.
    pub xid: Option<i32>,
    /// Number of matching frames to skip before this rule begins firing.
    pub skip: u32,
    /// Maximum number of times this rule may fire (`None` for unlimited).
    pub times: Option<u32>,
    /// Probability `(numerator, denominator)` of firing on a matching frame.
    pub chance: Option<(u32, u32)>,
    /// Actions executed in sequence when this rule fires.
    pub actions: Vec<Action>,
    seen: u32,
    fired: u32,
}

impl Rule {
    fn new(trigger: Trigger) -> Self {
        Self {
            trigger,
            conn: None,
            frame: None,
            op: None,
            path: None,
            path_prefix: None,
            xid: None,
            skip: 0,
            times: None,
            chance: None,
            actions: Vec::new(),
            seen: 0,
            fired: 0,
        }
    }

    /// Creates a rule matching connection handshake frames.
    #[must_use]
    pub fn on_connect() -> Self {
        Self::new(Trigger::Connect)
    }

    /// Creates a rule matching post-handshake request frames.
    #[must_use]
    pub fn on_request() -> Self {
        Self::new(Trigger::Request)
    }

    /// Creates a rule matching any inbound frame.
    #[must_use]
    pub fn on_any() -> Self {
        Self::new(Trigger::Any)
    }

    /// Restricts matching to the 1-based connection ordinal `n` on the listener.
    #[must_use]
    pub const fn conn(mut self, n: u64) -> Self {
        self.conn = Some(n);
        self
    }

    /// Restricts matching to the 1-based request frame index `n` on the connection.
    #[must_use]
    pub const fn frame(mut self, n: u64) -> Self {
        self.frame = Some(n);
        self
    }

    /// Restricts matching to the given protocol opcode.
    #[must_use]
    pub const fn op(mut self, op: i32) -> Self {
        self.op = Some(op);
        self
    }

    /// Restricts matching to the exact znode `path`.
    #[must_use]
    pub fn path(mut self, path: &str) -> Self {
        self.path = Some(path.to_owned());
        self
    }

    /// Restricts matching to znode paths starting with `prefix`.
    #[must_use]
    pub fn path_prefix(mut self, prefix: &str) -> Self {
        self.path_prefix = Some(prefix.to_owned());
        self
    }

    /// Restricts matching to the client request correlation `xid`.
    #[must_use]
    pub const fn xid(mut self, xid: i32) -> Self {
        self.xid = Some(xid);
        self
    }

    /// Skips the first `n` matching frames before firing.
    #[must_use]
    pub const fn skip(mut self, n: u32) -> Self {
        self.skip = n;
        self
    }

    /// Limits the rule to firing at most `n` times.
    #[must_use]
    pub const fn times(mut self, n: u32) -> Self {
        self.times = Some(n);
        self
    }

    /// Limits the rule to firing once.
    #[must_use]
    pub const fn once(self) -> Self {
        self.times(1)
    }

    /// Fires with probability `num / den` on each match using the seeded RNG.
    #[must_use]
    pub const fn chance(mut self, num: u32, den: u32) -> Self {
        self.chance = Some((num, den));
        self
    }

    /// Appends `action` to the rule's action list.
    #[must_use]
    pub fn then(mut self, action: Action) -> Self {
        self.actions.push(action);
        self
    }

    /// Returns the number of times this rule has fired.
    #[must_use]
    pub const fn fired(&self) -> u32 {
        self.fired
    }

    fn matches(&self, f: &FrameView<'_>) -> bool {
        let kind_ok = match self.trigger {
            Trigger::Any => true,
            Trigger::Connect => f.connect,
            Trigger::Request => !f.connect,
        };
        kind_ok
            && self.conn.is_none_or(|c| c == f.conn)
            && self.frame.is_none_or(|n| n == f.frame)
            && self.op.is_none_or(|op| Some(op) == f.op)
            && self.xid.is_none_or(|x| Some(x) == f.xid)
            && self.path.as_deref().is_none_or(|p| Some(p) == f.path)
            && self
                .path_prefix
                .as_deref()
                .is_none_or(|p| f.path.is_some_and(|fp| fp.starts_with(p)))
    }
}

/// Frame metadata passed to rule predicate evaluation.
#[derive(Debug, Clone, Copy)]
pub(crate) struct FrameView<'a> {
    pub(crate) connect: bool,
    pub(crate) conn: u64,
    pub(crate) frame: u64,
    pub(crate) op: Option<i32>,
    pub(crate) xid: Option<i32>,
    pub(crate) path: Option<&'a str>,
}

/// Ordered sequence of fault-injection rules evaluated on inbound frames.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Script {
    rules: Vec<Rule>,
}

impl Script {
    /// Creates an empty fault-injection script.
    #[must_use]
    pub const fn new() -> Self {
        Self { rules: Vec::new() }
    }

    /// Appends `rule` to the script and returns the updated script.
    #[must_use]
    pub fn rule(mut self, rule: Rule) -> Self {
        self.rules.push(rule);
        self
    }

    /// Appends `rule` to the script in place.
    pub fn push(&mut self, rule: Rule) {
        self.rules.push(rule);
    }

    /// Removes all rules from the script.
    pub fn clear(&mut self) {
        self.rules.clear();
    }

    /// Returns the registered rules.
    #[must_use]
    pub fn rules(&self) -> &[Rule] {
        &self.rules
    }

    /// Evaluates the script against `f`, returning the matched rule index and actions if triggered.
    pub(crate) fn decide(
        &mut self,
        f: &FrameView<'_>,
        rng: &mut SplitMix64,
    ) -> Option<(usize, Vec<Action>)> {
        for (i, rule) in self.rules.iter_mut().enumerate() {
            if rule.times.is_some_and(|t| rule.fired >= t) || !rule.matches(f) {
                continue;
            }
            rule.seen = rule.seen.saturating_add(1);
            if rule.seen <= rule.skip {
                continue;
            }
            if let Some((num, den)) = rule.chance {
                if rng.below(u64::from(den)) >= u64::from(num) {
                    continue;
                }
            }
            rule.fired = rule.fired.saturating_add(1);
            return Some((i, rule.actions.clone()));
        }
        None
    }
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
    use super::{Action, FrameView, Rule, Script};
    use crate::rng::SplitMix64;

    fn view(frame: u64, op: i32) -> FrameView<'static> {
        FrameView {
            connect: false,
            conn: 1,
            frame,
            op: Some(op),
            xid: Some(1),
            path: Some("/a/b"),
        }
    }

    #[test]
    fn skip_times_and_order() {
        let mut s = Script::new()
            .rule(Rule::on_request().op(4).skip(1).once().then(Action::Close))
            .rule(Rule::on_request().path_prefix("/a").then(Action::Ignore));
        let mut rng = SplitMix64::new(1);
        assert_eq!(s.decide(&view(1, 4), &mut rng).map(|d| d.0), Some(1));
        assert_eq!(s.decide(&view(2, 4), &mut rng).map(|d| d.0), Some(0));
        assert_eq!(s.decide(&view(3, 4), &mut rng).map(|d| d.0), Some(1));
        assert_eq!(s.rules()[0].fired(), 1);
    }

    #[test]
    fn connect_rules_do_not_match_requests() {
        let mut s = Script::new().rule(Rule::on_connect().then(Action::Close));
        let mut rng = SplitMix64::new(1);
        assert!(s.decide(&view(1, 4), &mut rng).is_none());
    }

    #[test]
    fn chance_is_seeded() {
        let run = |seed| {
            let mut s = Script::new().rule(Rule::on_any().chance(1, 2).then(Action::Ignore));
            let mut rng = SplitMix64::new(seed);
            (0..32)
                .map(|i| s.decide(&view(i, 4), &mut rng).is_some())
                .collect::<Vec<_>>()
        };
        assert_eq!(run(9), run(9));
    }
}
