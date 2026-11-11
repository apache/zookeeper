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

//! Session handshake scenarios covering initial session establishment,
//! session resumption across reconnects, and session expiration under legacy C
//! and native profiles.

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

use common::{Profile, cause, run_to_state};
use zk_jute::rc;
use zk_proto::{Cause, Output, State};
use zk_testkit::server::{HandshakeStyle, Rule, RuleAction};

// C reference: `prime_connection`. Initial session establishment sends
// `protocol_version = 0`, `last_zxid_seen = 0`, `session_id = 0`, a 16-byte
// zero password, and the configured session timeout.
#[test]
fn fresh_handshake_sends_a_new_session_request() {
    for p in Profile::ALL {
        let mut h = p.harness(11);
        h.connect().unwrap();
        let reqs = h.server().connect_requests();
        assert_eq!(reqs.len(), 1, "{p:?}");
        let c = reqs[0];
        assert_eq!(c.protocol_version, 0);
        assert_eq!(c.last_zxid_seen, 0);
        assert_eq!(c.session_id, 0);
        assert_eq!(c.passwd.as_bytes(), Some(&[0u8; 16][..]));
        assert_eq!(c.time_out, 10_000);
        assert!(!c.read_only);
        // Emits `ClientId` once followed by `Session { Connected }`.
        let ids = h
            .recorder()
            .outputs()
            .filter(|o| matches!(o, Output::ClientId(_)))
            .count();
        assert_eq!(ids, 1, "{p:?}");
        assert_eq!(h.recorder().session_states(), vec![State::Connected]);
        let sid = h.server().sessions()[0];
        assert_eq!(h.conn().client_id().session_id, sid);
        assert_eq!(h.conn().recv_timeout(), 10_000);
    }
}

// C reference: `prime_connection`. Reconnection transmits the established
// `session_id`, session password, and `last_zxid_seen`, resuming the session.
#[test]
fn reconnect_resumes_the_session() {
    for p in Profile::ALL {
        let mut h = p.harness(12);
        h.connect().unwrap();
        let ops = h.ops();
        h.call(ops.create("/r", b"x", 0)).unwrap();
        let sid = h.server().sessions()[0];
        let zxid = h.conn().last_zxid();
        assert!(zxid > 0);
        h.reset_current();
        h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
        h.connect().unwrap();
        let reqs = h.server().connect_requests();
        assert_eq!(reqs.len(), 2, "{p:?}");
        assert_eq!(reqs[1].session_id, sid);
        assert_eq!(reqs[1].last_zxid_seen, zxid);
        assert_eq!(
            reqs[1].passwd.as_bytes(),
            h.server().session_passwd(sid).as_ref().map(|p| &p[..])
        );
        assert_eq!(h.server().sessions(), vec![sid]);
        // Emits `Connecting` during T2 teardown, then `Connected` on resume.
        assert_eq!(
            h.recorder().session_states(),
            vec![State::Connected, State::Connecting, State::Connected],
            "{p:?}"
        );
        assert_eq!(cause(&h), Some(Cause::Io));
    }
}

// C reference: `check_events`. A different `session_id` in the reconnect
// response indicates session expiration, transitioning to terminal
// `ExpiredSession` (-112) and rejecting subsequent submissions with `ZINVALIDSTATE`.
#[test]
fn expired_session_on_resume_is_terminal() {
    for p in Profile::ALL {
        let mut h = p.harness(13);
        h.connect().unwrap();
        let sid = h.server().sessions()[0];
        h.server_mut().expire_session(sid);
        run_to_state(&mut h, State::ExpiredSession, 20_000);
        assert_eq!(cause(&h), Some(Cause::SessionExpired), "{p:?}");
        assert_eq!(
            h.recorder().session_states().last(),
            Some(&State::ExpiredSession)
        );
        // Terminal `ExpiredSession` stops further reconnection attempts.
        let n = h.server().connect_requests().len();
        h.run_for(10_000).unwrap();
        assert_eq!(h.server().connect_requests().len(), n, "{p:?}");
        let ops = h.ops();
        let e = h.submit(ops.exists("/", None)).unwrap_err();
        assert_eq!(e.code, rc::ZINVALIDSTATE, "{p:?}");
        assert!(!h.conn().has_transport());
    }
}

// Spec row "Session Expiry Conditions". The native profile treats a
// non-positive negotiated timeout on a fresh session as `SessionExpired`
// (matching the Java client), whereas legacy C profiles accept it.
#[test]
fn zero_timeout_handshake_expires_only_under_native() {
    for p in Profile::ALL {
        let mut h = p.harness(14);
        h.server_mut().add_rule(
            Rule::on_connect()
                .once()
                .then(RuleAction::Handshake(HandshakeStyle::Timeout(0))),
        );
        if p.is_native() {
            run_to_state(&mut h, State::ExpiredSession, 20_000);
            assert!(!h.recorder().session_states().contains(&State::Connected));
            assert_eq!(cause(&h), Some(Cause::SessionExpired));
        } else {
            // Legacy C profiles retain `recv_timeout = 0` and transition to `Connected`.
            h.connect().unwrap();
            assert_eq!(h.conn().recv_timeout(), 0);
            h.run_for(50).unwrap();
            assert!(
                !h.recorder()
                    .session_states()
                    .contains(&State::ExpiredSession),
                "{p:?}"
            );
            assert!(h.recorder().session_states().contains(&State::Connected));
        }
    }
}

// Spec row "Handshake Timeout". Legacy C profiles send the previously
// negotiated timeout on reconnect, whereas the native profile sends the
// originally requested timeout.
#[test]
fn reconnect_timeout_field_follows_the_profile() {
    for p in Profile::ALL {
        // Server clamps the requested 2000 ms timeout up to its 4000 ms minimum.
        let mut h = p.builder(15).timeout(2_000).build();
        h.connect().unwrap();
        assert_eq!(h.conn().recv_timeout(), 4_000, "{p:?}");
        h.reset_current();
        h.run_until(|h| !h.state().is_connected(), 10_000).unwrap();
        h.connect().unwrap();
        let reqs = h.server().connect_requests();
        assert_eq!(reqs[0].time_out, 2_000);
        let want = if p.is_native() { 2_000 } else { 4_000 };
        assert_eq!(reqs[1].time_out, want, "{p:?}");
    }
}

// C reference: `check_events`. When the server replies to a session resume with
// `session_id = 0` and `time_out = 0`, both profiles transition to
// `ExpiredSession` and complete all pending requests exactly once.
#[test]
fn expired_answer_fails_pending_work_with_session_expired() {
    for p in Profile::ALL {
        let mut h = p.harness(16);
        h.connect().unwrap();
        let ops = h.ops();
        // Hold `/slow` so the request remains pending in the FIFO.
        h.server_mut()
            .add_rule(Rule::on_request().path("/slow").then(RuleAction::Ignore));
        h.server_mut().add_rule(
            Rule::on_connect()
                .conn(1)
                .then(RuleAction::Handshake(HandshakeStyle::Expired)),
        );
        let t = h.submit(ops.get_data("/slow", None)).unwrap();
        h.run_for(100).unwrap();
        // Resetting the transport fails the in-flight request and triggers a
        // reconnect that receives the expired handshake response.
        h.reset_current();
        run_to_state(&mut h, State::ExpiredSession, 20_000);
        // The initial transport reset fails the transmitted request with `ZCONNECTIONLOSS`.
        h.expect_completion(t, rc::ZCONNECTIONLOSS).unwrap();
        h.expect_exactly_once().unwrap();
    }
}
