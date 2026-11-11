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

//! Xid monotonicity and FIFO reply correlation.
//!
//! Verifies that user request xids increase monotonically (wrapping from
//! `i32::MAX` to `1`) without taking negative or reserved control values,
//! replies are matched strictly against the pending FIFO head, and an
//! out-of-order reply triggers teardown (`ZRUNTIMEINCONSISTENCY` under legacy
//! profiles, `ZCONNECTIONLOSS` under native).

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

use props_common::{OpKind, Profile, check_once, config, make_op, op_kind, profile, submitted};
use proptest::prelude::*;
use zk_jute::{opcode, rc};
use zk_proto::{Origin, Output};
use zk_testkit::server::{InboundKind, OutboundKind, Rule, RuleAction};
use zk_testkit::{Event, Ticket};

/// Returns `true` for opcodes assigned sequential xids from the request counter
/// (excluding ping, auth, SASL, and watch-restore control frames).
fn counted(op: i32) -> bool {
    !matches!(
        op,
        opcode::PING | opcode::SET_AUTH | opcode::SASL | opcode::SET_WATCHES | opcode::SET_WATCHES2
    )
}

/// Returns `true` if `next` immediately follows `prev` in the positive `i32`
/// xid sequence, accounting for wraparound from `i32::MAX` to `1` (C `get_xid`).
fn follows(prev: i32, next: i32) -> bool {
    let d = i64::from(next) - i64::from(prev);
    let d = if d <= 0 { d + i64::from(i32::MAX) } else { d };
    d > 0 && d < (1 << 30)
}

fn xid_seed() -> impl Strategy<Value = i32> {
    prop_oneof![
        Just(1),
        (0i32..16).prop_map(|k| i32::MAX - k),
        1i32..1_000_000
    ]
}

fn plain_kind() -> impl Strategy<Value = OpKind> {
    op_kind().prop_filter("one reply header per op", |k| *k != OpKind::Multi)
}

fn run(
    p: Profile,
    seed: u64,
    xid_seed: i32,
    kinds: &[(OpKind, usize)],
    drop_at: Option<usize>,
) -> Result<(), TestCaseError> {
    let mut h = p.builder_with(seed, |c| c.xid_seed = xid_seed).build();
    h.connect()
        .map_err(|e| TestCaseError::fail(e.to_string()))?;
    let ops = h.ops();
    let mut tickets: Vec<Ticket> = Vec::new();
    for (k, path) in kinds {
        let t = h
            .submit(make_op(&ops, *k, *path, false))
            .map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
        tickets.push(t);
    }
    let subs = submitted(h.recorder());
    let xids: Vec<i32> = subs.iter().map(|(_, x)| *x).collect();
    prop_assert_eq!(subs.len(), tickets.len());
    // C reference: `get_xid` assigns the configured seed to the first request.
    prop_assert_eq!(xids[0], xid_seed);
    if let Some(d) = drop_at {
        h.server_mut().add_rule(
            Rule::on_request()
                .xid(xids[d])
                .once()
                .then(RuleAction::Drop),
        );
    }
    let want = tickets.clone();
    let r = h.run_until(
        move |h| want.iter().all(|t| h.recorder().completion_count(*t) > 0),
        60_000,
    );
    prop_assert!(r.is_ok(), "not all completed: {:?}", r);

    // Wire xids for counted requests must be strictly positive and monotonically increasing.
    let wire: Vec<i32> = h
        .server()
        .inbound()
        .iter()
        .filter_map(|i| match &i.kind {
            InboundKind::Request { xid, op, .. } if counted(*op) => Some(*xid),
            _ => None,
        })
        .collect();
    for w in wire.windows(2) {
        prop_assert!(follows(w[0], w[1]), "xid {} then {}", w[0], w[1]);
    }
    for x in &wire {
        prop_assert!(*x > 0, "counted xid {} is not positive", x);
    }
    // Requests are never retransmitted; wire xids form a prefix of submitted xids.
    prop_assert!(wire.len() <= xids.len());
    prop_assert_eq!(&wire[..], &xids[..wire.len()]);

    // Completions arrive in submission order and carry the server reply for the matching xid.
    let order: Vec<Ticket> = h
        .recorder()
        .completion_order()
        .into_iter()
        .filter(|t| tickets.contains(t))
        .collect();
    prop_assert_eq!(&order, &tickets);
    let replies: Vec<(i32, i64, i32)> = h
        .server()
        .outbound()
        .iter()
        .filter_map(|o| match o.kind {
            OutboundKind::Reply { xid, zxid, err } => Some((xid, zxid, err)),
            _ => None,
        })
        .collect();
    for (i, (t, xid)) in subs.iter().enumerate() {
        let res = h.recorder().completion(*t).unwrap().clone();
        match drop_at {
            // C reference: `zookeeper_process` ("unexpected server response").
            // A reply arriving ahead of a dropped request triggers teardown for
            // the dropped entry and all subsequent pending requests.
            Some(d) if i >= d => {
                prop_assert_eq!(res.origin, Origin::Teardown, "ticket {}", t.0);
                let want = if p.is_native() {
                    rc::ZCONNECTIONLOSS
                } else {
                    rc::ZRUNTIMEINCONSISTENCY
                };
                prop_assert_eq!(res.rc, want, "ticket {}", t.0);
            }
            _ => {
                prop_assert_eq!(res.origin, Origin::Reply, "ticket {}", t.0);
                let found = replies.iter().find(|(x, _, _)| x == xid);
                prop_assert!(found.is_some(), "no server reply for xid {}", xid);
                let (_, zxid, err) = *found.unwrap();
                prop_assert_eq!(res.zxid, zxid, "ticket {}", t.0);
                prop_assert_eq!(res.rc, err, "ticket {}", t.0);
            }
        }
    }
    if drop_at.is_some() {
        let mismatch = h.recorder().all().iter().any(|r| {
            matches!(&r.event, Event::Output(Output::Log(l))
                if format!("{:?}", l.site).contains("UnexpectedXid"))
        });
        prop_assert!(mismatch || p.is_native(), "no unexpected-xid log");
    }
    let f = h.finish();
    check_once(&f)
}

proptest! {
    #![proptest_config(config(0x5eed_0002))]

    // C reference: `get_xid` and `zookeeper_process`. Request xids increase
    // monotonically (wrapping to 1) and replies correlate with the FIFO head.
    #[test]
    fn xids_increase_and_replies_match_fifo(
        p in profile(),
        seed in any::<u64>(),
        xid_seed in xid_seed(),
        kinds in proptest::collection::vec((plain_kind(), 0usize..4), 2..12),
        drop_pick in proptest::option::of(any::<proptest::sample::Index>()),
    ) {
        // Never drop the final request so a subsequent reply arrives to trigger mismatch.
        let drop_at = drop_pick.map(|i| i.index(kinds.len() - 1));
        run(p, seed, xid_seed, &kinds, drop_at)?;
    }
}
