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

//! Session timer scenarios covering idle keepalive ping cadence, receive
//! timeout teardown, and per-profile keepalive scheduling with in-flight
//! requests.

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

use common::{Profile, cause};
use zk_jute::{opcode, rc};
use zk_proto::Cause;
use zk_testkit::Harness;
use zk_testkit::server::{InboundKind, Rule, RuleAction};

fn ping_times(h: &Harness) -> Vec<u64> {
    h.server()
        .inbound()
        .iter()
        .filter(|f| matches!(f.kind, InboundKind::Request { op, .. } if op == opcode::PING))
        .map(|f| f.at.0)
        .collect()
}

// An idle connected session transmits a keepalive `PING` every `T / 3`
// (`3333 ms` for `T = 10_000 ms`) since the last outbound write.
#[test]
fn idle_session_pings_every_third_of_the_timeout() {
    for p in Profile::ALL {
        let mut h = p.harness(51);
        h.connect().unwrap();
        let start = h.now().0;
        h.run_for(10_100).unwrap();
        let times = ping_times(&h);
        assert_eq!(times.len(), 3, "{p:?}: {times:?}");
        assert!((times[0] - start).abs_diff(3_333) <= 2, "{p:?}: {times:?}");
        for w in times.windows(2) {
            assert!((w[1] - w[0]).abs_diff(3_333) <= 2, "{p:?}: {times:?}");
        }
        assert!(h.state().is_connected());
    }
}

// Spec row "Disconnect Return Codes". When no inbound frame arrives within
// `2T / 3`, the connection tears down with `Cause::RecvTimeout`, failing
// pending requests with `ZOPERATIONTIMEOUT` under legacy C profiles and
// `ZCONNECTIONLOSS` under the native profile.
#[test]
fn receive_timeout_tears_down_after_two_thirds() {
    for p in Profile::ALL {
        let mut h = p.harness(52);
        h.connect().unwrap();
        h.server_mut()
            .add_rule(Rule::on_request().op(opcode::PING).then(RuleAction::Ignore));
        h.server_mut()
            .add_rule(Rule::on_request().path("/slow").then(RuleAction::Ignore));
        let t0 = h.now().0;
        let t = h.submit(h.ops().exists("/slow", None)).unwrap();
        h.run_until(|h| h.recorder().completion(t).is_some(), 20_000)
            .unwrap();
        let at = h.now().0;
        assert_eq!(cause(&h), Some(Cause::RecvTimeout), "{p:?}");
        // Last inbound frame was the handshake reply at approximately `t0`.
        assert!((at - t0).abs_diff(6_666) <= 10, "{p:?}: {}", at - t0);
        let want = if p.is_native() {
            rc::ZCONNECTIONLOSS
        } else {
            rc::ZOPERATIONTIMEOUT
        };
        h.expect_completion(t, want).unwrap();
        // Reconnects and resumes the existing session.
        h.connect().unwrap();
        assert_eq!(h.server().sessions().len(), 1);
    }
}

// Spec row "Keepalive Ping Scheduling". Legacy C profiles suppress keepalive
// pings while user requests are pending (causing a stalled request to expire
// the receive timer), whereas the native profile continues sending pings whose
// replies keep the session alive.
#[test]
fn ping_with_outstanding_requests_follows_the_profile() {
    for p in Profile::ALL {
        let mut h = p.harness(53);
        h.connect().unwrap();
        h.server_mut()
            .add_rule(Rule::on_request().path("/slow").then(RuleAction::Ignore));
        let t = h.submit(h.ops().exists("/slow", None)).unwrap();
        h.run_for(6_000).unwrap();
        let pings = ping_times(&h).len();
        if p.is_native() {
            assert!(pings >= 1, "{p:?}");
            h.run_for(10_000).unwrap();
            assert!(h.recorder().completion(t).is_none());
            assert!(h.state().is_connected());
        } else {
            assert_eq!(pings, 0, "{p:?}");
            h.run_for(1_000).unwrap();
            h.expect_completion(t, rc::ZOPERATIONTIMEOUT).unwrap();
            assert_eq!(cause(&h), Some(Cause::RecvTimeout));
        }
    }
}

// On an idle connected session, `Interest::timeout_ms` reflects the remaining
// time until the next keepalive ping (at most `T / 3`).
#[test]
fn interest_timeout_points_at_the_next_ping() {
    for p in Profile::ALL {
        let mut h = p.harness(54);
        h.connect().unwrap();
        h.run_for(1_000).unwrap();
        let o = h.last_interest().and_then(|o| o.outputs).unwrap();
        assert!(o.timeout_ms <= 3_333, "{p:?}: {}", o.timeout_ms);
        assert!(o.timeout_ms >= 2_300, "{p:?}: {}", o.timeout_ms);
    }
}
