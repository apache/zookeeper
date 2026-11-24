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

//! Rust protocol core SASL scenarios covering outbound phase gating during the
//! `Start` window, resetting `awaiting_final` across reconnects, final server
//! replies carrying trailing tokens, null (`-1` length) server tokens, and
//! malformed SASL reply frames.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use common::{Profile, reply, sasl_calls, sasl_harness, token};
use zk_jute::{opcode, rc};
use zk_proto::{LogArg, LogSite, State};
use zk_testkit::server::{InboundKind, Rule, RuleAction, SaslReply};
use zk_testkit::{Harness, SaslKind, SaslScript};

fn kinds(h: &Harness) -> Vec<SaslKind> {
    sasl_calls(h).into_iter().map(|(_, k)| k).collect()
}

fn log_args(h: &Harness, site: LogSite) -> Vec<Vec<LogArg>> {
    h.recorder()
        .logs()
        .iter()
        .filter(|l| l.site == site)
        .map(|l| l.args.clone())
        .collect()
}

fn digest_script() -> SaslScript {
    SaslScript {
        mechanism: "DIGEST-MD5".to_owned(),
        start: token(b"t1", false),
        steps: vec![token(b"t2", true)],
        ..SaslScript::default()
    }
}

fn all_ops(h: &Harness) -> Vec<i32> {
    h.server()
        .inbound()
        .iter()
        .filter_map(|f| match f.kind {
            InboundKind::Request { op, .. } => Some(op),
            _ => None,
        })
        .collect()
}

// Outbound send-queue phase gating holds user requests submitted while the
// initial SASL `Start` callback is still in flight, ensuring SASL frames are
// transmitted ahead of user requests on the wire (`tests/conformance/cases/sasl_fail.c`).
#[test]
fn request_in_start_window_waits_behind_sasl() {
    for p in Profile::ALL {
        let script = SaslScript {
            mechanism: "DIGEST-MD5".to_owned(),
            start: token(b"t1", false),
            steps: vec![token(b"t2", true)],
            defer_ms: Some(50),
            ..SaslScript::default()
        };
        let mut h = sasl_harness(p, 201, script, vec![reply(b"c1"), reply(b"")]);
        h.run_until(|h| !sasl_calls(h).is_empty(), 5_000).unwrap();
        let ops = h.ops();
        let t = h.submit(ops.exists("/", None)).unwrap();
        h.wait_for(t).unwrap();
        h.expect_completion(t, rc::ZOK).unwrap();
        assert_eq!(
            all_ops(&h)[..3],
            [opcode::SASL, opcode::SASL, opcode::EXISTS],
            "{p:?}"
        );
        assert_eq!(h.state(), State::Connected, "{p:?}");
        h.close().unwrap();
    }
}

/// Each `prime_response` initializes fresh SASL negotiation state. A transport reset while
/// awaiting the final server SASL reply clears `awaiting_final`, so the first SASL reply on the
/// subsequent connection is routed to `Step` rather than prematurely finalizing the session.
#[test]
fn awaiting_final_does_not_survive_a_reconnect() {
    for p in Profile::ALL {
        let mut h = sasl_harness(p, 202, digest_script(), vec![reply(b"c1"), reply(b"")]);
        // Drop the server's final SASL reply on the initial connection.
        h.server_mut().add_rule(
            Rule::on_request()
                .op(opcode::SASL)
                .conn(0)
                .skip(1)
                .once()
                .then(RuleAction::Ignore),
        );
        h.run_until(|h| h.server().request_count(opcode::SASL) == 2, 5_000)
            .unwrap();
        assert_eq!(h.state(), State::Associating, "{p:?}");
        h.reset_current();
        h.run_until(|h| h.state() == State::Connected, 10_000)
            .unwrap();
        let c1 = SaslKind::Step(Some(b"c1".to_vec()));
        assert_eq!(
            kinds(&h),
            [
                SaslKind::Start,
                c1.clone(),
                SaslKind::Dispose,
                SaslKind::Start,
                c1
            ],
            "{p:?}"
        );
        h.close().unwrap();
    }
}

/// After a `done = true` step that emits a client token, the subsequent server reply finalizes the
/// session immediately even if it carries a trailing token (e.g. `rspauth`), without invoking
/// another `Step` or logging `SaslServerData`.
#[test]
fn final_reply_with_a_token_completes_without_a_step() {
    for p in Profile::ALL {
        let mut h = sasl_harness(
            p,
            203,
            digest_script(),
            vec![reply(b"c1"), reply(b"rspauth=1")],
        );
        h.connect().unwrap();
        h.run_until(|h| h.state() == State::Connected, 10_000)
            .unwrap();
        assert_eq!(
            kinds(&h),
            [SaslKind::Start, SaslKind::Step(Some(b"c1".to_vec()))],
            "{p:?}"
        );
        assert_eq!(h.server().request_count(opcode::SASL), 2, "{p:?}");
        assert_eq!(
            log_args(&h, LogSite::SaslServerData),
            [vec![LogArg::I64(2)]],
            "{p:?}"
        );
        h.close().unwrap();
    }
}

/// A server SASL token with Jute length `-1` (null buffer) dispatches `SaslKind::Step(None)` and
/// logs `-1` at `SaslServerData`.
#[test]
fn null_server_token_steps_with_none() {
    for p in Profile::ALL {
        let null = SaslReply {
            token: None,
            err: rc::ZOK,
        };
        let mut h = sasl_harness(p, 204, digest_script(), vec![null, reply(b"")]);
        h.connect().unwrap();
        h.run_until(|h| h.state() == State::Connected, 10_000)
            .unwrap();
        assert_eq!(kinds(&h), [SaslKind::Start, SaslKind::Step(None)], "{p:?}");
        assert_eq!(
            log_args(&h, LogSite::SaslServerData),
            [vec![LogArg::I64(-1)]],
            "{p:?}"
        );
        h.close().unwrap();
    }
}

/// A truncated SASL reply frame whose declared token length exceeds the frame boundary fails
/// negotiation with `DecodeError::c_rc()` (`-E2BIG = -7`), logs the error at `SaslProcessed`, and
/// transitions to `AuthFailed` (-113) without invoking `Step`.
#[test]
fn malformed_reply_fails_with_the_decode_rc() {
    // ReplyHeader (xid = 1, zxid = 0, err = 0) followed by a 100-byte token
    // length prefix with no payload bytes.
    let mut frame = 20_i32.to_be_bytes().to_vec();
    frame.extend_from_slice(&1_i32.to_be_bytes());
    frame.extend_from_slice(&0_i64.to_be_bytes());
    frame.extend_from_slice(&0_i32.to_be_bytes());
    frame.extend_from_slice(&100_i32.to_be_bytes());
    for p in Profile::ALL {
        let mut h = sasl_harness(p, 205, digest_script(), Vec::new());
        h.server_mut().add_rule(
            Rule::on_request()
                .op(opcode::SASL)
                .once()
                .then(RuleAction::Garbage(frame.clone())),
        );
        h.run_until(|h| h.state() == State::AuthFailed, 10_000)
            .unwrap();
        assert_eq!(kinds(&h), [SaslKind::Start], "{p:?}");
        // `SaslProcessed` records the raw frame length and `-E2BIG` (`-7`);
        // `SaslFailed` is not logged because `zoo_sasl_mark_failed` emits no log.
        assert_eq!(
            log_args(&h, LogSite::SaslProcessed).last(),
            Some(&vec![LogArg::I64(24), LogArg::I64(-7)]),
            "{p:?}"
        );
        assert!(log_args(&h, LogSite::SaslFailed).is_empty(), "{p:?}");
        assert!(!h.conn().has_transport(), "{p:?}");
    }
}
