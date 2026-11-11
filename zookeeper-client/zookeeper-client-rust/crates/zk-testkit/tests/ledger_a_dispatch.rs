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

//! Unsolicited reply dispatch when the in-flight queue is empty.
//!
//! Whereas the legacy C client aborts via `assert` when a standard reply arrives with an empty
//! sent queue, the Rust core tears down the transport with [`Cause::XidMismatch`] and reconnects
//! the session.

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
use zk_proto::{Cause, State};

/// Encodes a reply frame with a standard request xid (`xid = 9999`, `zxid = 1`, `err = 0`).
fn stray_reply() -> Vec<u8> {
    let mut frame = 16i32.to_be_bytes().to_vec();
    frame.extend_from_slice(&9_999i32.to_be_bytes());
    frame.extend_from_slice(&1i64.to_be_bytes());
    frame.extend_from_slice(&0i32.to_be_bytes());
    frame
}

/// An unsolicited reply arriving when the in-flight FIFO is empty tears down the active link with
/// [`Cause::XidMismatch`] (`ZRUNTIMEINCONSISTENCY`) across all profiles, emits `CONNECTING`, and
/// reconnects the session without panicking.
#[test]
fn reply_with_an_empty_fifo_tears_down_and_reconnects() {
    for p in Profile::ALL {
        let mut h = p.harness(0xd508);
        h.connect().unwrap();
        let before = h.recorder().session_states().len();
        let link = h.link().unwrap();
        h.server_mut().push_raw(link, stray_reply());
        h.run_until(|h| cause(h).is_some(), 1_000).unwrap();
        assert_eq!(cause(&h), Some(Cause::XidMismatch), "{p:?}");
        run_to_state(&mut h, State::Connected, 10_000);
        let states = h.recorder().session_states();
        assert_eq!(
            states[before..],
            [State::Connecting, State::Connected],
            "{p:?}"
        );
        // Verify that the session remains usable on the re-established link.
        let ops = h.ops();
        let r = h.call(ops.exists("/", None)).unwrap();
        assert_eq!(r.rc, 0, "{p:?}");
    }
}
