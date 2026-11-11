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

//! SASL handshake scenarios covering multi-step challenge-response exchanges,
//! client mechanism failures, server error replies, and stale generation
//! filtering.

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

use common::{Profile, cause, reply, run_to_state, sasl_calls, sasl_harness, token};
use zk_jute::{opcode, rc};
use zk_proto::{Cause, Output, State};
use zk_testkit::server::{InboundKind, SaslReply};
use zk_testkit::{Event, Harness, SaslKind, SaslScript, ScriptStep};

/// Returns inbound request opcodes across all connections in arrival order.
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

// `SaslKind::Start` produces the initial client token, server challenges
// advance through `SaslKind::Step`, and a final step with a response token
// waits for the server's final acknowledgment before transitioning to
// `Connected` and transmitting queued requests.
#[test]
fn sasl_start_and_step_complete_the_session() {
    for p in Profile::ALL {
        let script = SaslScript {
            mechanism: "DIGEST-MD5".to_owned(),
            start: token(b"t1", false),
            steps: vec![token(b"t2", true)],
            ..SaslScript::default()
        };
        let mut h = sasl_harness(p, 90, script, vec![reply(b"c1"), reply(b"")]);
        let ops = h.ops();
        let t = h.submit(ops.exists("/", None)).unwrap();
        h.connect().unwrap();
        h.wait_for(t).unwrap();
        h.expect_completion(t, rc::ZOK).unwrap();
        assert_eq!(
            sasl_calls(&h),
            vec![
                (1, SaslKind::Start),
                (1, SaslKind::Step(Some(b"c1".to_vec())))
            ],
            "{p:?}"
        );
        assert_eq!(
            all_ops(&h)[..3],
            [opcode::SASL, opcode::SASL, opcode::EXISTS],
            "{p:?}"
        );
        assert_eq!(h.state(), State::Connected);
        // `SaslKind::Dispose` is emitted only upon `destroy`.
        h.close().unwrap();
        assert_eq!(
            sasl_calls(&h).last(),
            Some(&(1, SaslKind::Dispose)),
            "{p:?}"
        );
    }
}

// A client SASL mechanism error fails negotiation with `ZSYSTEMERROR`,
// transitions to terminal `AuthFailed` (-113), closes the transport, emits
// `Session { AuthFailed }`, and fails pending requests with `ZAUTHFAILED`.
// `SaslKind::Dispose` is deferred until `destroy`.
#[test]
fn mechanism_failure_is_a_terminal_teardown() {
    for p in Profile::ALL {
        let script = SaslScript {
            start: token(b"t1", false),
            steps: vec![ScriptStep::Fail { rc: -1 }],
            ..SaslScript::default()
        };
        let mut h = sasl_harness(p, 91, script, vec![reply(b"c1")]);
        let ops = h.ops();
        let t = h.submit(ops.exists("/", None)).unwrap();
        run_to_state(&mut h, State::AuthFailed, 10_000);
        h.run_for(100).unwrap();
        assert_eq!(cause(&h), Some(Cause::SaslTeardown), "{p:?}");
        h.expect_completion(t, rc::ZAUTHFAILED).unwrap();
        let rcs: Vec<i32> = h
            .recorder()
            .all()
            .iter()
            .filter_map(|r| match r.event {
                Event::SaslResult { rc, .. } => Some(rc),
                _ => None,
            })
            .collect();
        assert_eq!(rcs.last(), Some(&rc::ZSYSTEMERROR), "{p:?}");
        assert!(
            h.recorder().outputs().any(|o| matches!(
                o,
                Output::Session {
                    state: State::AuthFailed,
                    ..
                }
            )),
            "{p:?}"
        );
        assert!(!h.conn().has_transport());
        let n = h.server().connect_requests().len();
        h.run_for(5_000).unwrap();
        assert_eq!(h.server().connect_requests().len(), n, "{p:?}");
        assert!(!sasl_calls(&h).iter().any(|(_, k)| *k == SaslKind::Dispose));
        let now = h.now();
        h.conn_mut().destroy(now);
        h.pump(now);
        assert_eq!(
            sasl_calls(&h).last(),
            Some(&(1, SaslKind::Dispose)),
            "{p:?}"
        );
        h.expect_exactly_once().unwrap();
    }
}

// A server SASL reply with `err < 0` aborts negotiation with that error code
// and transitions to terminal `AuthFailed` (-113).
#[test]
fn server_failure_reply_fails_the_negotiation() {
    for p in Profile::ALL {
        let script = SaslScript {
            start: token(b"t1", false),
            ..SaslScript::default()
        };
        let bad = SaslReply {
            token: None,
            err: rc::ZAUTHFAILED,
        };
        let mut h = sasl_harness(p, 92, script, vec![bad]);
        run_to_state(&mut h, State::AuthFailed, 10_000);
        h.run_for(100).unwrap();
        assert_eq!(sasl_calls(&h), vec![(1, SaslKind::Start)], "{p:?}");
        assert!(!h.recorder().session_states().contains(&State::Connected));
        assert!(!h.conn().has_transport());
    }
}

// Transport teardown in T1 increments the SASL generation counter; a late
// callback from a superseded generation returns `ZOK` without transmitting a
// token, and the subsequent reconnect disposes the old SASL session before
// starting a new generation.
#[test]
fn stale_generation_result_is_ignored() {
    for p in Profile::ALL {
        let script = SaslScript {
            defer_ms: Some(500),
            ..SaslScript::default()
        };
        let mut h = sasl_harness(p, 93, script, Vec::new());
        // Wait for the initial `Start` action, then reset the transport before it completes.
        h.run_until(|h| !sasl_calls(h).is_empty(), 5_000).unwrap();
        let sasl_frames = h.server().request_count(opcode::SASL);
        assert_eq!(sasl_frames, 0);
        h.reset_current();
        h.run_until(|h| h.state() == State::Connected, 10_000)
            .unwrap();
        let results: Vec<(u32, i32)> = h
            .recorder()
            .all()
            .iter()
            .filter_map(|r| match r.event {
                Event::SaslResult { generation, rc } => Some((generation, rc)),
                _ => None,
            })
            .collect();
        assert_eq!(results.first(), Some(&(1, rc::ZOK)), "{p:?}");
        // Both T1 teardown and the new `Start` increment the generation counter,
        // placing the second `Start` at generation 3.
        let calls = sasl_calls(&h);
        assert_eq!(
            calls[..3],
            [
                (1, SaslKind::Start),
                (1, SaslKind::Dispose),
                (3, SaslKind::Start)
            ],
            "{p:?}"
        );
        // Only the active generation transmits a SASL request frame.
        assert_eq!(h.server().request_count(opcode::SASL), 1, "{p:?}");
    }
}
