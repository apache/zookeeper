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

//! Wire framing invariants under the legacy C profiles.
//!
//! - Whereas the legacy C client overreads 4 bytes past a reply body and ignores deserializer
//!   return codes, the Rust core decodes each frame within its exact length bounds and
//!   tears down the connection on malformed payloads.
//! - Whereas the legacy C client writes `ConnectRequest` via two separate `send` calls and
//!   ignores short writes, the Rust core stages the 49-byte frame in its transmit buffer so
//!   partial writes resume at the exact byte offset.

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
use zk_jute::rc;
use zk_proto::{Cause, State};
use zk_testkit::server::codec::connect_frame;
use zk_testkit::server::{InboundKind, Rule, RuleAction};
use zk_testkit::{Chunking, Event, Harness, Ticket};

/// Prepends a 4-byte big-endian length prefix to `body`.
fn framed(body: &[u8]) -> Vec<u8> {
    let mut out = i32::try_from(body.len()).unwrap().to_be_bytes().to_vec();
    out.extend_from_slice(body);
    out
}

/// Encodes a `ReplyHeader` (`xid`, `zxid`, `err`).
fn header(xid: i32, zxid: i64, err: i32) -> Vec<u8> {
    let mut h = xid.to_be_bytes().to_vec();
    h.extend_from_slice(&zxid.to_be_bytes());
    h.extend_from_slice(&err.to_be_bytes());
    h
}

/// Returns the wire `xid` assigned to `t`.
fn xid_of(h: &Harness, t: Ticket) -> i32 {
    h.recorder()
        .all()
        .iter()
        .find_map(|r| match r.event {
            Event::Submitted { ticket, xid } if ticket == t => Some(xid),
            _ => None,
        })
        .unwrap()
}

/// Connects and submits a `get_data` request whose server reply is suppressed.
fn pending_get(h: &mut Harness) -> (Ticket, i32) {
    h.connect().unwrap();
    h.server_mut()
        .add_rule(Rule::on_request().path("/slow").then(RuleAction::Ignore));
    let ops = h.ops();
    let t = h.submit(ops.get_data("/slow", None)).unwrap();
    h.run_for(100).unwrap();
    (t, xid_of(h, t))
}

/// A truncated reply payload never invokes the completion callback with partial data; instead,
/// legacy profiles fail the request with `ZCONNECTIONLOSS` and tear down the transport with
/// [`Cause::ProtocolError`] without consuming bytes from the subsequent frame.
#[test]
fn a_short_reply_body_tears_down_with_connection_loss() {
    for p in Profile::LEGACY {
        let mut h = p.harness(0x5704);
        let (t, xid) = pending_get(&mut h);
        // Truncated `GetDataResponse` declaring a 100-byte buffer followed by only 3 bytes.
        let mut body = header(xid, 7, 0);
        body.extend_from_slice(&100i32.to_be_bytes());
        body.extend_from_slice(b"abc");
        let mut raw = framed(&body);
        // Append a valid ping reply; the legacy C client would over-read its first 4 bytes as part
        // of the truncated `GetDataResponse`.
        raw.extend_from_slice(&framed(&header(-2, 0, 0)));
        let link = h.link().unwrap();
        h.server_mut().push_raw(link, raw);
        h.run_until(|h| h.recorder().completion(t).is_some(), 1_000)
            .unwrap();
        h.expect_completion(t, rc::ZCONNECTIONLOSS).unwrap();
        assert_eq!(cause(&h), Some(Cause::ProtocolError), "{p:?}");
        assert_ne!(h.state(), State::Connected, "{p:?}");
    }
}

/// `ConnectRequest` is transmitted intact across single-byte and scripted partial socket writes,
/// delivering the full 49-byte legacy handshake frame to the server.
#[test]
fn the_connect_request_survives_one_byte_writes() {
    for p in Profile::LEGACY {
        for plan in [Chunking::ONE_BYTE, Chunking::Script(vec![3, 1, 40, 2])] {
            let mut h = p.builder(0x5705).tx_chunking(plan.clone()).build();
            h.connect().unwrap();
            assert_eq!(h.state(), State::Connected, "{p:?} {plan:?}");
            let reqs = h.server().connect_requests();
            assert_eq!(reqs.len(), 1, "{p:?} {plan:?}");
            let req = reqs[0];
            assert_eq!(req.protocol_version, 0);
            assert_eq!(req.last_zxid_seen, 0);
            assert_eq!(req.time_out, 10_000);
            assert_eq!(req.session_id, 0);
            assert!(!req.read_only);
            // 4-byte length prefix plus 45-byte legacy `ConnectRequest` body.
            assert_eq!(connect_frame(req).len(), 49);
            let first = &h.server().inbound()[0];
            assert_eq!(first.index, 0);
            assert!(matches!(first.kind, InboundKind::Connect(_)));
            // Verify that the handshake was segmented across multiple partial writes.
            let writes: Vec<usize> = h
                .recorder()
                .all()
                .iter()
                .filter_map(|r| match r.event {
                    Event::Transmit { bytes } => Some(bytes),
                    _ => None,
                })
                .collect();
            assert!(writes.len() >= 4, "{p:?} {plan:?} {writes:?}");
            assert!(writes.iter().all(|b| *b <= 40), "{p:?} {plan:?}");
        }
    }
}
