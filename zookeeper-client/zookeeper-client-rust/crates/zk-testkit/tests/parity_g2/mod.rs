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

//! Shared helpers for the legacy C parity test suites (`TestOperations`, `TestWatchers`,
//! `TestZookeeperInit`, and `TestZookeeperClose`) targeting the sans-IO harness.

#![allow(
    dead_code,
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use zk_jute::opcode;
use zk_proto::{Output, State};
use zk_testkit::server::InboundKind;
use zk_testkit::{Harness, Ticket, WatcherId};

/// `WatchedEvent` event type constants.
pub(crate) const CREATED: i32 = 1;
pub(crate) const DELETED: i32 = 2;
pub(crate) const CHANGED: i32 = 3;
pub(crate) const CHILD: i32 = 4;

/// Wire `xid` for ping replies.
const PING_XID: i32 = -2;

/// Returns the server arrival timestamps of all `PING` requests.
pub(crate) fn ping_times(h: &Harness) -> Vec<u64> {
    h.server()
        .inbound()
        .iter()
        .filter(|f| matches!(f.kind, InboundKind::Request { op, .. } if op == opcode::PING))
        .map(|f| f.at.0)
        .collect()
}

/// Returns the server arrival timestamp of the most recent inbound frame (which coincides with the
/// client's most recent send timestamp because the harness transfers bytes synchronously within the
/// writing step).
pub(crate) fn last_send_at(h: &Harness) -> u64 {
    h.server().inbound().last().map(|f| f.at.0).unwrap()
}

/// Encodes a length-prefixed `ConnectResponse` frame matching the C mock `HandshakeResponse`.
///
/// When `read_only` is `None`, the trailing read-only flag byte is omitted, producing a 36-byte
/// legacy payload instead of 37 bytes.
pub(crate) fn connect_response(timeout: i32, session: i64, read_only: Option<bool>) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&0_i32.to_be_bytes());
    body.extend_from_slice(&timeout.to_be_bytes());
    body.extend_from_slice(&session.to_be_bytes());
    body.extend_from_slice(&16_i32.to_be_bytes());
    body.extend_from_slice(b"1234567890123456");
    if let Some(ro) = read_only {
        body.push(u8::from(ro));
    }
    framed(&body)
}

/// Encodes a length-prefixed ping reply frame (`xid = -2`, `zxid = 0`, `rc = 0`).
pub(crate) fn ping_reply() -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&PING_XID.to_be_bytes());
    body.extend_from_slice(&0_i64.to_be_bytes());
    body.extend_from_slice(&0_i32.to_be_bytes());
    framed(&body)
}

/// Prepends a 4-byte big-endian length prefix to `body`.
pub(crate) fn framed(body: &[u8]) -> Vec<u8> {
    let mut out = i32::try_from(body.len()).unwrap().to_be_bytes().to_vec();
    out.extend_from_slice(body);
    out
}

/// Returns the total number of watch and session event deliveries to `w` recorded since `mark`.
pub(crate) fn deliveries(h: &Harness, mark: usize, w: WatcherId) -> usize {
    h.recorder()
        .outputs_since(mark)
        .map(|o| match o {
            Output::Watch { watchers, .. } | Output::Session { watchers, .. } => {
                watchers.iter().filter(|x| **x == w).count()
            }
            _ => 0,
        })
        .sum()
}

/// Returns all session events recorded since `mark` as `(state, watchers)` pairs.
pub(crate) fn sessions_since(h: &Harness, mark: usize) -> Vec<(State, Vec<WatcherId>)> {
    h.recorder()
        .outputs_since(mark)
        .filter_map(|o| match o {
            Output::Session {
                state, watchers, ..
            } => Some((*state, watchers.clone())),
            _ => None,
        })
        .collect()
}

/// Returns all watch events recorded since `mark` as `(event_type, path, watchers)` tuples.
pub(crate) fn watches_since(h: &Harness, mark: usize) -> Vec<(i32, String, Vec<WatcherId>)> {
    h.recorder()
        .outputs_since(mark)
        .filter_map(|o| match o {
            Output::Watch {
                event, watchers, ..
            } => {
                let p = event.path.as_bytes().unwrap_or_default();
                Some((
                    event.event_type,
                    String::from_utf8_lossy(p).into_owned(),
                    watchers.clone(),
                ))
            }
            _ => None,
        })
        .collect()
}

/// Returns `true` if `o` is the completion output for ticket `t`.
pub(crate) fn is_completion_of(o: &Output<zk_testkit::TestTokens>, t: Ticket) -> bool {
    matches!(o, Output::Completion { token, .. } if token.ticket() == t)
}

/// Steps the harness until connected, recording each distinct session state transition starting
/// from the initial state prior to the first step.
pub(crate) fn connect_states(h: &mut Harness) -> Vec<State> {
    let mut seen = vec![h.state()];
    h.run_until(
        |h| {
            let s = h.state();
            if seen.last() != Some(&s) {
                seen.push(s);
            }
            s.is_connected()
        },
        10_000,
    )
    .unwrap();
    seen
}
