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

//! Chronological event log capturing driver inputs, client outputs, and state transitions.
//!
//! Maintains an ordered execution audit trail of all driver actions, socket events, and protocol
//! outputs emitted by the client state machine. Test scenarios leverage this trace to verify
//! output ordering, validate response correlation, and enforce strict exactly-once token
//! completion semantics.

use std::collections::{BTreeMap, BTreeSet};
use std::net::SocketAddr;

use zk_proto::{
    InterestOutcome, LogRecord, Millis, OpResult, Output, ProbeOutcome, ReadOutcome, Route, Stage,
    State, TransportFailure, WatchedEvent,
};

use crate::net::ConnectPlan;
use crate::tokens::{AuthTicket, TestTokens, Ticket, WatcherId};

/// SASL authentication handshake invocation variants.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SaslKind {
    /// Initial mechanism negotiation request (`start`).
    Start,
    /// Challenge-response evaluation step carrying an optional server token.
    Step(Option<Vec<u8>>),
    /// Session termination and credential teardown (`dispose`).
    Dispose,
}

/// Record of an asynchronous platform driver action dispatched on behalf of the client core.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ActionRecord {
    /// Host name resolution request for the configured ensemble connection string.
    Resolve {
        /// Epoch generation counter tracking ensemble reconfiguration cycles.
        generation: u64,
        /// Number of distinct host entries resolved.
        hosts: usize,
        /// Indicates whether this represents the initial bootstrap connection resolution.
        initial: bool,
        /// Return code reported by `handle_resolved`.
        rc: i32,
    },
    /// Socket connection attempt directed at a server endpoint.
    Connect {
        /// Destination socket address.
        addr: SocketAddr,
        /// Indicates whether transport-level security was requested by the core.
        tls: bool,
        /// Network disposition plan applied to this connection attempt.
        plan: ConnectPlan,
    },
    /// Explicit transport teardown command requested by the state machine.
    CloseTransport,
    /// Read-write capability probe initiated against an active server listener.
    ProbeRw {
        /// Target socket address evaluated by the probe.
        addr: SocketAddr,
        /// Evaluated probe outcome returned to the client state machine.
        outcome: ProbeOutcome,
    },
    /// SASL authentication mechanism interaction.
    Sasl {
        /// Authentication attempt generation identifier.
        generation: u32,
        /// Specific SASL lifecycle invocation executed.
        kind: SaslKind,
    },
}

/// Discrete simulation event recorded during harness execution.
#[derive(Debug)]
pub enum Event {
    /// Outbound protocol event or completion emitted by the client core.
    Output(Output<TestTokens>),
    /// Cooperative pause barrier yielding execution control via `take_stopped`.
    Stopped(u64),
    /// Resumption of a previously yielded execution context via `resume`.
    Resumed(u64),
    /// Platform driver action dispatched by the client core.
    Action(ActionRecord),
    /// I/O interest evaluation cycle result produced by `finish_interest`.
    Interest(InterestOutcome),
    /// Refusal return code encountered when initiating `begin_interest`.
    InterestRefused(i32),
    /// Inbound wire data transfer evaluated by `handle_read`.
    Read {
        /// Byte count presented to the protocol parser.
        len: usize,
        /// Parse outcome and consumption summary returned by the client.
        outcome: ReadOutcome,
    },
    /// Outbound wire data written to the simulated transport link.
    Transmit {
        /// Number of bytes successfully transferred to the server receiver.
        bytes: usize,
    },
    /// Fatal transport failure reported to the client core via `handle_transport_failed`.
    Failure {
        /// Transport error descriptor.
        failure: TransportFailure,
        /// Client core return code responding to the failure.
        rc: i32,
    },
    /// Request submitted to the client pipeline and assigned a transaction identifier.
    Submitted {
        /// Correlation ticket identifying the submitted operation.
        ticket: Ticket,
        /// ZooKeeper transaction identifier allocated for the request.
        xid: i32,
    },
    /// Immediate synchronous local completion of a submitted operation without wire transmission.
    SubmitLocal(Ticket),
    /// Immediate rejection of a submitted operation during client validation.
    SubmitRejected {
        /// Correlation ticket identifying the rejected operation.
        ticket: Ticket,
        /// Pipeline validation stage where rejection occurred.
        stage: Stage,
        /// Legacy return code associated with the rejection.
        code: i32,
    },
    /// Authentication credentials accepted and queued for negotiation.
    AuthAdded(Option<AuthTicket>),
    /// Authentication credentials rejected prior to transmission.
    AuthRejected {
        /// Correlation ticket returned upon rejection.
        ticket: Option<AuthTicket>,
        /// Legacy return code indicating the rejection cause.
        code: i32,
    },
    /// SASL negotiation outcome delivered to the client state machine.
    SaslResult {
        /// Authentication generation identifier.
        generation: u32,
        /// Client core return code responding to the SASL result.
        rc: i32,
    },
    /// Diagnostic annotation attached by test logic or harness coordination routines.
    Note(String),
}

/// Chronologically indexed entry within the harness audit log.
#[derive(Debug)]
pub struct Recorded {
    /// Monotonically increasing event sequence index.
    pub seq: u64,
    /// Simulated timestamp at which the event occurred.
    pub at: Millis,
    /// Event payload.
    pub event: Event,
}

/// Terminal disposition mode of a client operation ticket.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Delivery<'a> {
    /// Normal asynchronous completion emitted through the output queue.
    Completion(&'a OpResult),
    /// Immediate synchronous completion at the submission boundary.
    Local,
    /// Immediate submission rejection with a return code.
    Rejected(i32),
}

/// Ordered audit log tracking all client events, driver actions, and operation token lifecycles.
#[derive(Debug, Default)]
pub struct Recorder {
    items: Vec<Recorded>,
    issued: BTreeSet<Ticket>,
    leftover: Vec<Ticket>,
}

impl Recorder {
    /// Constructs an unpopulated event recorder.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Appends a new timestamped event to the audit trail.
    pub fn push(&mut self, at: Millis, event: Event) {
        let seq = u64::try_from(self.items.len()).unwrap_or(u64::MAX);
        self.items.push(Recorded { seq, at, event });
    }

    /// Records issuance of an operation ticket into the client pipeline for completion accounting.
    pub fn issue(&mut self, ticket: Ticket) {
        self.issued.insert(ticket);
    }

    /// Records an uncompleted operation ticket recovered from connection leftovers upon teardown.
    pub fn leftover(&mut self, ticket: Ticket) {
        self.leftover.push(ticket);
    }

    /// Returns a slice containing all logged events in chronological order.
    #[must_use]
    pub fn all(&self) -> &[Recorded] {
        &self.items
    }

    /// Returns the total number of events recorded.
    #[must_use]
    pub fn len(&self) -> usize {
        self.items.len()
    }

    /// Returns `true` if no events have been logged.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    /// Captures a sequence watermark index for subsequent differential queries via
    /// [`Recorder::since`].
    #[must_use]
    pub fn mark(&self) -> usize {
        self.items.len()
    }

    /// Returns all logged events occurring after the specified watermark index.
    #[must_use]
    pub fn since(&self, mark: usize) -> &[Recorded] {
        self.items.get(mark..).unwrap_or_default()
    }

    /// Returns an iterator yielding all client output events in emission order.
    pub fn outputs(&self) -> impl Iterator<Item = &Output<TestTokens>> {
        self.items.iter().filter_map(|r| match &r.event {
            Event::Output(o) => Some(o),
            _ => None,
        })
    }

    /// Returns an iterator yielding all client output events emitted after `mark`.
    pub fn outputs_since(&self, mark: usize) -> impl Iterator<Item = &Output<TestTokens>> {
        self.since(mark).iter().filter_map(|r| match &r.event {
            Event::Output(o) => Some(o),
            _ => None,
        })
    }

    /// Returns an iterator yielding all platform driver actions dispatched during execution.
    pub fn actions(&self) -> impl Iterator<Item = &ActionRecord> {
        self.items.iter().filter_map(|r| match &r.event {
            Event::Action(a) => Some(a),
            _ => None,
        })
    }

    /// Returns an iterator yielding all transport failures reported to the client, paired with
    /// their handler return codes.
    pub fn failures(&self) -> impl Iterator<Item = (&TransportFailure, i32)> {
        self.items.iter().filter_map(|r| match &r.event {
            Event::Failure { failure, rc } => Some((failure, *rc)),
            _ => None,
        })
    }

    /// Retrieves the terminal operation result associated with `ticket`, if emitted as a
    /// `Completion` output.
    #[must_use]
    pub fn completion(&self, ticket: Ticket) -> Option<&OpResult> {
        self.outputs().find_map(|o| match o {
            Output::Completion { token, result, .. } if token.ticket() == ticket => Some(result),
            _ => None,
        })
    }

    /// Retrieves the dispatch route taken by the completion event for a given ticket.
    #[must_use]
    pub fn completion_route(&self, ticket: Ticket) -> Option<Route> {
        self.outputs().find_map(|o| match o {
            Output::Completion { token, route, .. } if token.ticket() == ticket => Some(*route),
            _ => None,
        })
    }

    /// Determines the terminal delivery channel and outcome for a given operation ticket.
    #[must_use]
    pub fn delivery(&self, ticket: Ticket) -> Option<Delivery<'_>> {
        self.items.iter().find_map(|r| match &r.event {
            Event::Output(Output::Completion { token, result, .. }) if token.ticket() == ticket => {
                Some(Delivery::Completion(result))
            }
            Event::SubmitLocal(t) if *t == ticket => Some(Delivery::Local),
            Event::SubmitRejected {
                ticket: t, code, ..
            } if *t == ticket => Some(Delivery::Rejected(*code)),
            _ => None,
        })
    }

    /// Counts the total number of asynchronous completion events emitted for a specific ticket.
    #[must_use]
    pub fn completion_count(&self, ticket: Ticket) -> usize {
        self.outputs()
            .filter(|o| matches!(o, Output::Completion { token, .. } if token.ticket() == ticket))
            .count()
    }

    /// Returns an ordered vector of tickets corresponding to all emitted completion outputs.
    #[must_use]
    pub fn completion_order(&self) -> Vec<Ticket> {
        self.outputs()
            .filter_map(|o| match o {
                Output::Completion { token, .. } => Some(token.ticket()),
                _ => None,
            })
            .collect()
    }

    /// Returns all watched event notifications paired with their targeted watcher identifiers, in
    /// emission order.
    #[must_use]
    pub fn watch_events(&self) -> Vec<(&WatchedEvent, &[WatcherId])> {
        self.outputs()
            .filter_map(|o| match o {
                Output::Watch {
                    event, watchers, ..
                } => Some((event, watchers.as_slice())),
                _ => None,
            })
            .collect()
    }

    /// Returns all session state transitions emitted by the client, in chronological order.
    #[must_use]
    pub fn session_states(&self) -> Vec<State> {
        self.outputs()
            .filter_map(|o| match o {
                Output::Session { state, .. } => Some(*state),
                _ => None,
            })
            .collect()
    }

    /// Returns all authentication completion results and associated tickets, in emission order.
    #[must_use]
    pub fn auth_results(&self) -> Vec<(Vec<AuthTicket>, i32)> {
        self.outputs()
            .filter_map(|o| match o {
                Output::Auth { tokens, rc, .. } => {
                    Some((tokens.iter().map(|t| t.ticket()).collect(), *rc))
                }
                _ => None,
            })
            .collect()
    }

    /// Collects all diagnostic log records emitted during execution, including standalone log
    /// outputs and inline delivery logs.
    #[must_use]
    pub fn logs(&self) -> Vec<&LogRecord> {
        let mut out = Vec::new();
        for o in self.outputs() {
            match o {
                Output::Log(r) => out.push(r),
                Output::Completion { delivery_logs, .. }
                | Output::Watch { delivery_logs, .. }
                | Output::Session { delivery_logs, .. } => out.extend(delivery_logs.iter()),
                _ => {}
            }
        }
        out
    }

    /// Validates that every operation ticket issued during the run completed exactly once.
    ///
    /// Tokens preserved within connection leftovers upon teardown count as valid completions.
    ///
    /// # Errors
    ///
    /// Returns a detailed error string listing any tickets that were dropped, duplicated, or
    /// never issued.
    pub fn check_exactly_once(&self) -> Result<(), String> {
        let mut seen: BTreeMap<Ticket, usize> = BTreeMap::new();
        for r in &self.items {
            let t = match &r.event {
                Event::Output(Output::Completion { token, .. }) => Some(token.ticket()),
                Event::SubmitLocal(t) | Event::SubmitRejected { ticket: t, .. } => Some(*t),
                _ => None,
            };
            if let Some(t) = t {
                let n = seen.entry(t).or_insert(0);
                *n = n.saturating_add(1);
            }
        }
        for t in &self.leftover {
            let n = seen.entry(*t).or_insert(0);
            *n = n.saturating_add(1);
        }
        let mut bad = Vec::new();
        for t in &self.issued {
            let n = seen.get(t).copied().unwrap_or(0);
            if n != 1 {
                bad.push(format!("ticket {} completed {n} times", t.0));
            }
        }
        for t in seen.keys() {
            if !self.issued.contains(t) {
                bad.push(format!("ticket {} was never issued", t.0));
            }
        }
        if bad.is_empty() {
            Ok(())
        } else {
            Err(bad.join("; "))
        }
    }
}
