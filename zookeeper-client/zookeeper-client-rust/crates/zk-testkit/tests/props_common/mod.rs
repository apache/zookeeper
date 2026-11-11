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

//! Shared infrastructure for `proptest` suites: deterministic runner configuration, driver
//! profiles, randomized operation scripts, and exactly-once completion invariants.

#![allow(
    dead_code,
    missing_docs,
    unreachable_pub,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

#[path = "../common/mod.rs"]
mod scenario;

use std::collections::{BTreeMap, BTreeSet};

use proptest::prelude::*;
use proptest::test_runner::{Config as PtConfig, RngSeed};
use zk_proto::{Op, OpResult, Origin, Output, Route, State};
use zk_testkit::server::{Rule, RuleAction, ServerOptions};
use zk_testkit::{
    AuthTicket, Event, Finished, Harness, HarnessBuilder, HarnessError, Ops, TestTokens, Ticket,
    WatcherId,
};

/// Driver execution profiles shared with the scenario tests.
pub(crate) use scenario::Profile;

/// Default number of test cases per property.
pub const CASES: u32 = 256;

/// Returns a `proptest` configuration with a fixed PRNG seed for deterministic runs.
pub fn config(seed: u64) -> PtConfig {
    config_cases(CASES, seed)
}

/// Returns a `proptest` configuration with `cases` iterations and a fixed PRNG `seed`.
pub fn config_cases(cases: u32, seed: u64) -> PtConfig {
    PtConfig {
        cases,
        rng_seed: RngSeed::Fixed(seed),
        failure_persistence: None,
        max_shrink_iters: 512,
        ..PtConfig::default()
    }
}

pub fn profile() -> impl Strategy<Value = Profile> {
    prop_oneof![Just(Profile::St), Just(Profile::Mt), Just(Profile::Native)]
}

/// Candidate znode paths used by generated operation scripts.
pub const PATHS: [&str; 4] = ["/a", "/b", "/a/c", "/zk"];

/// Authentication scheme configured to be rejected by the simulated server.
pub const BAD_SCHEME: &str = "bad";

/// Operation kinds generated in property scripts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OpKind {
    Create,
    Exists,
    GetData,
    SetData,
    Delete,
    GetChildren,
    GetChildren2,
    GetAcl,
    Sync,
    Multi,
}

pub fn op_kind() -> impl Strategy<Value = OpKind> {
    prop_oneof![
        Just(OpKind::Create),
        Just(OpKind::Exists),
        Just(OpKind::GetData),
        Just(OpKind::SetData),
        Just(OpKind::Delete),
        Just(OpKind::GetChildren),
        Just(OpKind::GetChildren2),
        Just(OpKind::GetAcl),
        Just(OpKind::Sync),
        Just(OpKind::Multi),
    ]
}

/// Constructs an [`Op`] for a script step.
pub fn make_op(ops: &Ops, kind: OpKind, path: usize, watch: bool) -> Op<WatcherId> {
    let p = PATHS[path % PATHS.len()];
    let w = watch.then(|| WatcherId::new(1, u32::try_from(path).unwrap_or(0)));
    match kind {
        OpKind::Create => ops.create(p, b"v", 0),
        OpKind::Exists => ops.exists(p, w),
        OpKind::GetData => ops.get_data(p, w),
        OpKind::SetData => ops.set_data(p, b"w", -1),
        OpKind::Delete => ops.delete(p, -1),
        OpKind::GetChildren => ops.get_children(p, w),
        OpKind::GetChildren2 => ops.get_children2(p, w),
        OpKind::GetAcl => ops.get_acl(p),
        OpKind::Sync => ops.sync(p),
        OpKind::Multi => ops.multi(vec![ops.m_create(p, b"m", 0), ops.m_check(p, -1)]),
    }
}

/// Transport failures injectable by a property script. Each variant tears down the active link.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FailKind {
    Io,
    Eof,
    Refused,
    SocketCreate,
    TlsHandshake,
}

pub fn fail_kind() -> impl Strategy<Value = FailKind> {
    prop_oneof![
        Just(FailKind::Io),
        Just(FailKind::Eof),
        Just(FailKind::Refused),
        Just(FailKind::SocketCreate),
        Just(FailKind::TlsHandshake),
    ]
}

impl FailKind {
    pub fn failure(self) -> zk_proto::TransportFailure {
        use zk_proto::TransportFailure as F;
        match self {
            Self::Io => F::Io { errno: 104 },
            Self::Eof => F::Eof,
            Self::Refused => F::TcpRefused { errno: 111 },
            Self::SocketCreate => F::SocketCreate { errno: 24 },
            Self::TlsHandshake => F::TlsHandshake { errno: 0 },
        }
    }
}

/// Single step in a randomized property script.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Step {
    /// Submits an operation (`direct` applies only under [`Profile::Mt`]).
    Submit {
        kind: OpKind,
        path: usize,
        watch: bool,
        direct: bool,
    },
    /// Registers an authentication credential (`bad` selects [`BAD_SCHEME`], which the server
    /// rejects).
    AddAuth { bad: bool },
    /// Advances the harness for the specified duration in milliseconds.
    Run(u32),
    /// Steps the harness until connected or 30 s elapses.
    Connect,
    /// Resets the active server link.
    Reset,
    /// Closes the active server link.
    CloseLink,
    /// Injects a driver-reported transport failure.
    Fail(FailKind),
    /// Expires the current session on the server.
    Expire,
    /// Drops the server reply for the next request.
    DropNext,
    /// Replies to the next request with arbitrary garbage bytes.
    GarbageNext(Vec<u8>),
    /// Forces a transport close (as triggered by ensemble reconfiguration).
    ForcedClose,
    /// Closes the client handle and terminates the script.
    Close,
}

pub fn step() -> impl Strategy<Value = Step> {
    prop_oneof![
        8 => (op_kind(), 0..PATHS.len(), any::<bool>(), any::<bool>()).prop_map(
            |(kind, path, watch, direct)| Step::Submit {
                kind,
                path,
                watch,
                direct
            }
        ),
        1 => any::<bool>().prop_map(|bad| Step::AddAuth { bad }),
        4 => (0u32..3_000).prop_map(Step::Run),
        3 => Just(Step::Connect),
        1 => Just(Step::Reset),
        1 => Just(Step::CloseLink),
        1 => fail_kind().prop_map(Step::Fail),
        1 => Just(Step::Expire),
        1 => Just(Step::DropNext),
        1 => proptest::collection::vec(any::<u8>(), 0..12).prop_map(Step::GarbageNext),
        1 => Just(Step::ForcedClose),
        1 => Just(Step::Close),
    ]
}

pub fn script(max: usize) -> impl Strategy<Value = Vec<Step>> {
    proptest::collection::vec(step(), 1..max)
}

/// Constructs a [`Harness`] for script execution with [`BAD_SCHEME`] rejected by the server.
pub fn script_harness(p: Profile, seed: u64) -> Harness {
    script_builder(p, seed).build()
}

/// Returns the [`HarnessBuilder`] used by [`script_harness`] for further customization.
pub fn script_builder(p: Profile, seed: u64) -> HarnessBuilder {
    let mut opts = ServerOptions::default();
    opts.reject_auth_schemes
        .insert(BAD_SCHEME.as_bytes().to_vec());
    p.builder(seed).server_options(opts)
}

/// Executes a single script [`Step`], returning `Ok(true)` if the script has terminated.
///
/// Harness timeouts are expected under randomized fault injection and are ignored, whereas
/// [`HarnessError::Runaway`] indicates a livelock and fails the test case.
pub fn apply(h: &mut Harness, p: Profile, s: &Step) -> Result<bool, TestCaseError> {
    let ok = |r: Result<(), HarnessError>| match r {
        Err(HarnessError::Runaway { steps }) => {
            Err(TestCaseError::fail(format!("runaway after {steps} steps")))
        }
        _ => Ok(()),
    };
    match s {
        Step::Submit {
            kind,
            path,
            watch,
            direct,
        } => {
            let ops = h.ops();
            let op = make_op(&ops, *kind, *path, *watch);
            let route = if *direct && p == Profile::Mt {
                Route::Direct
            } else {
                Route::Queued
            };
            // Synchronous rejections are recorded and count as the ticket's single delivery.
            let _ = h.submit_with(op, route, zk_proto::InputDefect::default());
        }
        Step::AddAuth { bad } => {
            let scheme = if *bad { BAD_SCHEME } else { "digest" };
            let _ = h.add_auth(scheme, b"user:pw");
        }
        Step::Run(ms) => ok(h.run_for(u64::from(*ms)))?,
        Step::Connect => ok(h.run_until(|h| h.state().is_connected(), 30_000))?,
        Step::Reset => h.reset_current(),
        Step::CloseLink => h.close_current(),
        Step::Fail(f) => {
            if h.conn().has_transport() {
                let _ = h.inject_failure(f.failure());
            }
        }
        Step::Expire => {
            let sid = h.conn().client_id().session_id;
            if sid != 0 {
                h.server_mut().expire_session(sid);
            }
        }
        Step::DropNext => {
            h.server_mut()
                .add_rule(Rule::on_request().once().then(RuleAction::Drop));
        }
        Step::GarbageNext(b) => {
            h.server_mut().add_rule(
                Rule::on_request()
                    .once()
                    .then(RuleAction::Garbage(b.clone())),
            );
        }
        Step::ForcedClose => {
            let now = h.now();
            h.conn_mut().handle_forced_close(now);
            h.pump(now);
        }
        Step::Close => {
            ok(h.close())?;
            return Ok(true);
        }
    }
    Ok(false)
}

/// Verifies the `OpResult` completion invariants across all recorded completions: teardown
/// completions carry no reply payload and a non-zero `rc`, and `rc == 0` always carries a reply.
pub fn check_results(f: &Finished) -> Result<(), TestCaseError> {
    for r in f.recorder.all() {
        if let Event::Output(Output::Completion { token, result, .. }) = &r.event {
            check_result(token.ticket(), result)?;
        }
    }
    Ok(())
}

pub fn check_result(t: Ticket, r: &OpResult) -> Result<(), TestCaseError> {
    if r.origin == Origin::Teardown {
        prop_assert!(r.reply.is_none(), "ticket {}: teardown reply", t.0);
        prop_assert!(r.rc != 0, "ticket {}: teardown rc 0", t.0);
    }
    prop_assert!(
        !(r.rc == 0 && r.reply.is_none()),
        "ticket {}: rc 0 with no reply",
        t.0
    );
    Ok(())
}

/// Verifies that every issued auth token exits the core exactly once—via an `Output::Auth` event,
/// a synchronous `add_auth` rejection, or the terminal leftovers.
pub fn check_auth_once(f: &Finished) -> Result<(), TestCaseError> {
    let mut issued: BTreeSet<AuthTicket> = BTreeSet::new();
    let mut seen: BTreeMap<AuthTicket, usize> = BTreeMap::new();
    let mut bump = |t: AuthTicket| *seen.entry(t).or_insert(0) += 1;
    for r in f.recorder.all() {
        match &r.event {
            Event::AuthAdded(Some(t)) => {
                issued.insert(*t);
            }
            Event::AuthRejected { ticket, code } => {
                let Some(t) = ticket else {
                    return Err(TestCaseError::fail(format!(
                        "add_auth rejected with {code} and lost the token"
                    )));
                };
                issued.insert(*t);
                bump(*t);
            }
            Event::Output(Output::Auth { tokens, .. }) => {
                for a in tokens {
                    bump(a.ticket());
                }
            }
            _ => {}
        }
    }
    for a in &f.leftovers.auth {
        bump(a.ticket());
    }
    for o in &f.leftovers.outputs {
        if let Output::Auth { tokens, .. } = o {
            for a in tokens {
                bump(a.ticket());
            }
        }
    }
    for t in &issued {
        let n = seen.get(t).copied().unwrap_or(0);
        prop_assert_eq!(n, 1, "auth ticket {} delivered {} times", t.0, n);
    }
    for t in seen.keys() {
        prop_assert!(issued.contains(t), "auth ticket {} never issued", t.0);
    }
    Ok(())
}

/// Verifies all completion, auth-token, and result invariants on a finished run.
pub fn check_once(f: &Finished) -> Result<(), TestCaseError> {
    f.recorder
        .check_exactly_once()
        .map_err(TestCaseError::fail)?;
    check_auth_once(f)?;
    check_results(f)
}

/// Returns `(ticket, xid)` pairs for all queued submissions in submission order.
pub fn submitted(f: &zk_testkit::Recorder) -> Vec<(Ticket, i32)> {
    f.all()
        .iter()
        .filter_map(|r| match r.event {
            Event::Submitted { ticket, xid } => Some((ticket, xid)),
            _ => None,
        })
        .collect()
}

/// Extracts `(ticket, OpResult)` pairs from a slice of recorded events.
pub fn completions(rs: &[zk_testkit::Recorded]) -> Vec<(Ticket, OpResult)> {
    rs.iter()
        .filter_map(|r| match &r.event {
            Event::Output(Output::Completion { token, result, .. }) => {
                Some((token.ticket(), result.clone()))
            }
            _ => None,
        })
        .collect()
}

/// Returns `true` if `s` is `Connected` or `ConnectedReadOnly`.
pub fn connected(s: State) -> bool {
    s.is_connected()
}

/// Returns the static variant name of `o` for diagnostic messages.
pub fn kind(o: &Output<TestTokens>) -> &'static str {
    match o {
        Output::Completion { .. } => "Completion",
        Output::Watch { .. } => "Watch",
        Output::Session { .. } => "Session",
        Output::Auth { .. } => "Auth",
        Output::AuthLockpoint => "AuthLockpoint",
        Output::ClientId(_) => "ClientId",
        Output::WatchTerminated { .. } => "WatchTerminated",
        Output::Log(_) => "Log",
        Output::DrainPoint => "DrainPoint",
    }
}
