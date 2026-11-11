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

//! Scripted SASL authentication mechanism and verification audit logs.
//!
//! Provides a configurable test double implementation of [`SaslSession`] to simulate multi step
//! challenge-response authentication exchanges (such as `PLAIN`, `DIGEST-MD5`, or `GSSAPI`).
//! Step outcomes, generated authentication tokens, simulated negotiation delays, and handshake
//! errors can be scripted deterministically, with all invocations captured in an audit log.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, PoisonError};

use zk_proto::{SaslAddr, SaslError, SaslSession, SaslStep, SecretBytes};

/// Predefined outcome of a single SASL authentication handshake step.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ScriptStep {
    /// Successful handshake progression yielding an optional outbound token and completion flag.
    Token {
        /// Outbound authentication token destined for the server, or `None` if no token is emitted.
        token: Option<Vec<u8>>,
        /// Indicates whether mechanism negotiation completed successfully on the client.
        done: bool,
    },
    /// Authentication failure terminating negotiation with a specific error return code.
    Fail {
        /// SASL framework error return code (`SASL_*`).
        rc: i32,
    },
}

impl ScriptStep {
    fn to_step(&self) -> Result<SaslStep, SaslError> {
        match self {
            Self::Token { token, done } => Ok(SaslStep {
                token: token.as_deref().map(SecretBytes::from_slice),
                done: *done,
            }),
            Self::Fail { rc } => Err(SaslError {
                rc: *rc,
                errstring: b"scripted failure".to_vec(),
                errdetail: Vec::new(),
            }),
        }
    }
}

/// Deterministic execution scenario defining the progression of a SASL authentication session.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SaslScript {
    /// Authentication mechanism identifier reported during negotiation start (e.g., `"PLAIN"`).
    pub mechanism: String,
    /// Predetermined outcome of the initial handshake invocation (`SaslSession::start`).
    pub start: ScriptStep,
    /// Sequence of predetermined outcomes consumed by subsequent challenge-response cycles
    /// (`SaslSession::step`). When exhausted, subsequent steps complete with empty tokens and
    /// `done = true`.
    pub steps: Vec<ScriptStep>,
    /// Simulates an address inspection failure before initiating negotiation, triggering fallback.
    pub addr_failure: Option<SaslAddr>,
    /// Simulated asynchronous execution delay in milliseconds before returning step results.
    pub defer_ms: Option<u64>,
}

impl Default for SaslScript {
    /// Initializes a default single-round `PLAIN` authentication exchange with synthetic
    /// credentials.
    fn default() -> Self {
        Self {
            mechanism: "PLAIN".to_owned(),
            start: ScriptStep::Token {
                token: Some(b"\0user\0secret".to_vec()),
                done: true,
            },
            steps: Vec::new(),
            addr_failure: None,
            defer_ms: None,
        }
    }
}

/// Record of an individual handshake interaction dispatched to a [`ScriptedSasl`] session.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SaslCallRecord {
    /// Initial negotiation start call.
    Start,
    /// Challenge evaluation step carrying the optional server-provided challenge token.
    Step(Option<Vec<u8>>),
}

/// Thread-safe audit log recording all handshake invocations and incoming challenge tokens.
#[derive(Clone, Debug, Default)]
pub struct SaslLog {
    calls: Arc<Mutex<Vec<SaslCallRecord>>>,
}

impl SaslLog {
    /// Initializes an empty SASL interaction audit log.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn push(&self, c: SaslCallRecord) {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(c);
    }

    /// Retrieves a snapshot of all logged SASL interactions in chronological invocation order.
    #[must_use]
    pub fn calls(&self) -> Vec<SaslCallRecord> {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }
}

/// Test double implementation of [`SaslSession`] driven by a declarative [`SaslScript`].
#[derive(Debug)]
pub struct ScriptedSasl {
    mechanism: String,
    start: ScriptStep,
    steps: VecDeque<ScriptStep>,
    log: SaslLog,
}

impl ScriptedSasl {
    /// Constructs a scripted SASL session executing `script` and recording interactions into `log`.
    #[must_use]
    pub fn new(script: &SaslScript, log: SaslLog) -> Self {
        Self {
            mechanism: script.mechanism.clone(),
            start: script.start.clone(),
            steps: script.steps.iter().cloned().collect(),
            log,
        }
    }
}

impl SaslSession for ScriptedSasl {
    fn start(&mut self) -> Result<(String, SaslStep), SaslError> {
        self.log.push(SaslCallRecord::Start);
        self.start.to_step().map(|s| (self.mechanism.clone(), s))
    }

    fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
        self.log
            .push(SaslCallRecord::Step(challenge.map(<[u8]>::to_vec)));
        match self.steps.pop_front() {
            Some(s) => s.to_step(),
            None => Ok(SaslStep {
                token: None,
                done: true,
            }),
        }
    }
}
