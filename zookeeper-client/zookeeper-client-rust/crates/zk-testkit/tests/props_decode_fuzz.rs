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

//! Fuzz style decode property: arbitrary server frames never cause the client core to panic.
//!
//! Injects randomized server frames, replies with arbitrary payloads across all request kinds,
//! watch events, ping/auth/`SetWatches` replies, unframed raw bytes, and malformed
//! `ConnectResponse` frames, verifying that `zk-jute` decoders within the core never panic and
//! that every issued token still exits the core exactly once.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod props_common;

use props_common::{OpKind, Profile, check_once, check_result, config, make_op, op_kind, profile};
use proptest::prelude::*;
use zk_jute::rc;
use zk_proto::Origin;
use zk_testkit::server::{Rule, RuleAction};
use zk_testkit::{Event, Harness, HarnessError};

/// Randomized server frame variant.
#[derive(Clone, Debug)]
enum Frame {
    /// Reply frame paired with a submitted request of `kind`, carrying `err`, `zxid`, and `body`.
    Reply {
        kind: OpKind,
        err: i32,
        zxid: i64,
        body: Vec<u8>,
    },
    /// Frame with a reserved control `xid` (`-1` watch event, `-2` ping, `-4` auth, `-8`
    /// `SetWatches`).
    Special { xid: i32, body: Vec<u8> },
    /// Unframed raw bytes.
    Raw(Vec<u8>),
    /// Arbitrary `ConnectResponse` payload for the next handshake.
    Handshake(Vec<u8>),
}

/// Generates either uniform random bytes or concatenated small big-endian integers and short byte
/// slices so that Jute length prefixes frequently pass bounds checks.
fn body() -> impl Strategy<Value = Vec<u8>> {
    let token = prop_oneof![
        (-2i32..10).prop_map(|v| v.to_be_bytes().to_vec()),
        proptest::collection::vec(any::<u8>(), 0..6),
        any::<i64>().prop_map(|v| v.to_be_bytes().to_vec()),
    ];
    prop_oneof![
        proptest::collection::vec(any::<u8>(), 0..64),
        proptest::collection::vec(token, 0..12).prop_map(|v| v.concat()),
    ]
}

fn err_code() -> impl Strategy<Value = i32> {
    prop_oneof![
        4 => Just(0),
        1 => Just(rc::ZNONODE),
        1 => Just(rc::ZRUNTIMEINCONSISTENCY),
        1 => Just(rc::ZAUTHFAILED),
        1 => any::<i32>(),
    ]
}

fn frame() -> impl Strategy<Value = Frame> {
    prop_oneof![
        6 => (op_kind(), err_code(), any::<i64>(), body())
            .prop_map(|(kind, err, zxid, body)| Frame::Reply { kind, err, zxid, body }),
        3 => (prop_oneof![Just(-1i32), Just(-2), Just(-4), Just(-8)], body())
            .prop_map(|(xid, body)| Frame::Special { xid, body }),
        1 => proptest::collection::vec(any::<u8>(), 0..40).prop_map(Frame::Raw),
        1 => body().prop_map(Frame::Handshake),
    ]
}

fn framed(body: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&i32::try_from(body.len()).unwrap().to_be_bytes());
    out.extend_from_slice(body);
    out
}

fn header(xid: i32, zxid: i64, err: i32) -> Vec<u8> {
    let mut h = Vec::new();
    h.extend_from_slice(&xid.to_be_bytes());
    h.extend_from_slice(&zxid.to_be_bytes());
    h.extend_from_slice(&err.to_be_bytes());
    h
}

fn no_runaway(r: Result<(), HarnessError>) -> Result<(), TestCaseError> {
    match r {
        Err(HarnessError::Runaway { steps }) => {
            Err(TestCaseError::fail(format!("runaway {steps}")))
        }
        _ => Ok(()),
    }
}

/// Injects `f` into the harness and steps until its effects settle.
fn send(h: &mut Harness, p: Profile, f: &Frame) -> Result<(), TestCaseError> {
    if let Frame::Handshake(b) = f {
        h.server_mut().add_rule(
            Rule::on_connect()
                .once()
                .then(RuleAction::Garbage(framed(b))),
        );
        h.reset_current();
        no_runaway(h.run_for(2_000))?;
        return Ok(());
    }
    no_runaway(h.run_until(|h| h.state().is_connected() && h.link().is_some(), 30_000))?;
    let Some(link) = h.link() else {
        return Ok(());
    };
    match f {
        Frame::Reply {
            kind,
            err,
            zxid,
            body,
        } => {
            let ops = h.ops();
            let Ok(t) = h.submit(make_op(&ops, *kind, 0, true)) else {
                return Ok(());
            };
            let xid = h
                .recorder()
                .all()
                .iter()
                .rev()
                .find_map(|r| match r.event {
                    Event::Submitted { ticket, xid } if ticket == t => Some(xid),
                    _ => None,
                })
                .unwrap();
            // Suppress the normal server reply so the synthetic response frame can be injected.
            h.server_mut()
                .add_rule(Rule::on_request().xid(xid).once().then(RuleAction::Ignore));
            no_runaway(h.run_for(10))?;
            let mut b = header(xid, *zxid, *err);
            b.extend_from_slice(body);
            h.server_mut().push_raw(link, framed(&b));
            no_runaway(h.run_until(|h| h.recorder().completion_count(t) > 0, 30_000))?;
            if let Some(r) = h.recorder().completion(t) {
                check_result(t, r)?;
                // Non-zero reply header errors bypass body decoding and propagate directly as
                // `rc` (whereas teardown under the native profile maps to `ZCONNECTIONLOSS`).
                if r.origin == Origin::Reply && *err != 0 && *kind != OpKind::Multi {
                    prop_assert_eq!(r.rc, *err);
                }
                // Under the native profile, teardown completions yield `ZCONNECTIONLOSS` or a
                // terminal session error code (`< ZAPIERROR`).
                if r.origin == Origin::Teardown && p.is_native() {
                    prop_assert!(
                        r.rc == rc::ZCONNECTIONLOSS || r.rc < rc::ZAPIERROR,
                        "native teardown rc {}",
                        r.rc
                    );
                }
            }
        }
        Frame::Special { xid, body } => {
            let mut b = header(*xid, 0, 0);
            b.extend_from_slice(body);
            h.server_mut().push_raw(link, framed(&b));
            no_runaway(h.run_for(200))?;
        }
        Frame::Raw(b) => {
            h.server_mut().push_raw(link, b.clone());
            no_runaway(h.run_for(200))?;
        }
        Frame::Handshake(_) => {}
    }
    Ok(())
}

fn run(p: Profile, seed: u64, frames: &[Frame]) -> Result<(), TestCaseError> {
    let mut h = p.builder(seed).build();
    no_runaway(h.run_until(|h| h.state().is_connected(), 30_000))?;
    for f in frames {
        send(&mut h, p, f)?;
        if h.state().is_unrecoverable() {
            break;
        }
    }
    no_runaway(h.close())?;
    let f = h.finish();
    check_once(&f)
}

proptest! {
    #![proptest_config(config(0x5eed_0007))]

    // Production code must never panic when decoding arbitrary server wire frames.
    #[test]
    fn random_server_frames_never_panic(
        p in profile(),
        seed in any::<u64>(),
        frames in proptest::collection::vec(frame(), 1..8),
    ) {
        run(p, seed, &frames)?;
    }
}
