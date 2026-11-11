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

//! Shared helpers for `zk-testkit` integration and scenario tests.

#![allow(
    dead_code,
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic
)]

use zk_jute::rc;
use zk_proto::{Cause, Compat, Config, DriverMode, Output, State};
use zk_testkit::server::SaslReply;
use zk_testkit::{
    ActionRecord, Event, Harness, HarnessBuilder, Recorder, SaslKind, SaslScript, ScriptStep,
    ServerOptions,
};

/// Driver execution profiles: legacy C single threaded, legacy C multithreaded, and native Rust.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Profile {
    St,
    Mt,
    Native,
}

impl Profile {
    pub(crate) const ALL: [Self; 3] = [Self::St, Self::Mt, Self::Native];
    pub(crate) const LEGACY: [Self; 2] = [Self::St, Self::Mt];

    pub(crate) fn compat(self) -> Compat {
        match self {
            Self::St | Self::Mt => Compat::LEGACY_C,
            Self::Native => Compat::NATIVE,
        }
    }

    pub(crate) fn mode(self) -> DriverMode {
        match self {
            Self::St => DriverMode::ST,
            Self::Mt => DriverMode::MT,
            Self::Native => DriverMode::NATIVE,
        }
    }

    pub(crate) fn is_native(self) -> bool {
        self == Self::Native
    }

    /// Returns a [`HarnessBuilder`] with a 10 s session timeout (and a 2 s close drain timeout
    /// under [`Self::Native`], matching [`Harness::native`]).
    pub(crate) fn builder(self, seed: u64) -> HarnessBuilder {
        self.builder_with(seed, |_| {})
    }

    /// Returns a [`HarnessBuilder`] with its [`Config`] customized by `f`.
    pub(crate) fn builder_with(self, seed: u64, f: impl FnOnce(&mut Config)) -> HarnessBuilder {
        let mut cfg = Config::legacy(10_000);
        if self.is_native() {
            cfg.close_timeout_ms = 2_000;
        }
        f(&mut cfg);
        HarnessBuilder::new(cfg, self.compat())
            .mode(self.mode())
            .seed(seed)
    }

    pub(crate) fn harness(self, seed: u64) -> Harness {
        self.builder(seed).build()
    }
}

/// Returns recorded session event states in emission order.
pub(crate) fn sessions(r: &Recorder) -> Vec<State> {
    r.session_states()
}

/// Returns the index of the first recorded event at or after `from` satisfying `pred`.
pub(crate) fn find(
    r: &Recorder,
    from: usize,
    mut pred: impl FnMut(&Event) -> bool,
) -> Option<usize> {
    r.all()
        .iter()
        .enumerate()
        .skip(from)
        .find(|(_, x)| pred(&x.event))
        .map(|(i, _)| i)
}

/// Returns `true` if any recorded output satisfies `pred`.
pub(crate) fn has_output(
    r: &Recorder,
    pred: impl FnMut(&Output<zk_testkit::TestTokens>) -> bool,
) -> bool {
    r.outputs().any(pred)
}

/// Steps the harness until session state `want` is reached or `limit_ms` elapses.
pub(crate) fn run_to_state(h: &mut Harness, want: State, limit_ms: u64) {
    h.run_until(|h| h.state() == want, limit_ms)
        .unwrap_or_else(|e| panic!("state {:?} not reached: {e}", want));
}

/// Returns the most recent transport teardown cause.
pub(crate) fn cause(h: &Harness) -> Option<Cause> {
    h.conn().last_teardown_cause()
}

/// Constructs a [`zk_proto::HostSpec`] matching the profile's representation.
pub(crate) fn spec(native: bool, host: &str, port: u16) -> zk_proto::HostSpec {
    use zk_proto::legacy::connect::{HostSpec as LegacyHostSpec, NativeHost};
    if native {
        zk_proto::HostSpec::Native(NativeHost {
            host: host.to_string(),
            port,
        })
    } else {
        zk_proto::HostSpec::Legacy(LegacyHostSpec {
            host: host.as_bytes().to_vec(),
            service: port.to_string().into_bytes(),
            port: i32::from(port),
        })
    }
}

/// Returns the timestamps and target addresses of all recorded `Connect` actions.
pub(crate) fn connect_log(r: &Recorder) -> Vec<(u64, std::net::SocketAddr)> {
    r.all()
        .iter()
        .filter_map(|x| match &x.event {
            Event::Action(zk_testkit::ActionRecord::Connect { addr, .. }) => Some((x.at.0, *addr)),
            _ => None,
        })
        .collect()
}

/// Constructs and connects a [`Harness`] for profile `p`.
pub(crate) fn connected(p: Profile, seed: u64) -> Harness {
    let mut h = p.harness(seed);
    h.connect().unwrap();
    h
}

/// Returns the total number of recorded `Connect` actions.
pub(crate) fn connects(h: &Harness) -> usize {
    h.recorder()
        .actions()
        .filter(|a| matches!(a, ActionRecord::Connect { .. }))
        .count()
}

/// Constructs a SASL-enabled [`Harness`] configured with client `script` and server `replies`.
pub(crate) fn sasl_harness(
    p: Profile,
    seed: u64,
    script: SaslScript,
    replies: Vec<SaslReply>,
) -> Harness {
    let opts = ServerOptions {
        sasl_replies: replies,
        ..ServerOptions::default()
    };
    p.builder_with(seed, |c| c.sasl = true)
        .server_options(opts)
        .sasl(script)
        .build()
}

/// Constructs a client SASL [`ScriptStep::Token`] with payload `t` and completion flag `done`.
pub(crate) fn token(t: &[u8], done: bool) -> ScriptStep {
    ScriptStep::Token {
        token: Some(t.to_vec()),
        done,
    }
}

/// Constructs a server [`SaslReply`] with token `t` and status `ZOK`.
pub(crate) fn reply(t: &[u8]) -> SaslReply {
    SaslReply {
        token: Some(t.to_vec()),
        err: rc::ZOK,
    }
}

/// Returns recorded SASL actions in emission order as `(generation, kind)` pairs.
pub(crate) fn sasl_calls(h: &Harness) -> Vec<(u32, SaslKind)> {
    h.recorder()
        .actions()
        .filter_map(|a| match a {
            ActionRecord::Sasl { generation, kind } => Some((*generation, kind.clone())),
            _ => None,
        })
        .collect()
}
