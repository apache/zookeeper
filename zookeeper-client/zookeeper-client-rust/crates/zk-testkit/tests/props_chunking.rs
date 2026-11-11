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

//! Wire chunking invariance.
//!
//! Frame transmission is atomic across arbitrary write chunkings, and decoded client outputs are
//! invariant across arbitrary read chunkings. Replaying an identical operation script under whole
//! frames versus randomized transmit/receive chunking plans produces an identical output sequence
//! and server inbound transcript.

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

use props_common::{OpKind, PATHS, Profile, check_once, config, make_op, op_kind, profile};
use proptest::prelude::*;
use zk_proto::Output;
use zk_testkit::server::InboundKind;
use zk_testkit::{Chunking, Harness, ReadMode, Ticket};

#[derive(Clone, Debug)]
enum Act {
    Submit {
        kind: OpKind,
        path: usize,
        watch: bool,
    },
    /// server side tree mutation that triggers armed watches on the target path.
    ServerCreate(usize),
    ServerSet(usize),
    ServerDelete(usize),
    Run(u64),
    /// Steps the harness until all in-flight requests have completed.
    Settle,
}

fn act() -> impl Strategy<Value = Act> {
    prop_oneof![
        6 => (op_kind(), 0..PATHS.len(), any::<bool>())
            .prop_map(|(kind, path, watch)| Act::Submit { kind, path, watch }),
        1 => (0..PATHS.len()).prop_map(Act::ServerCreate),
        1 => (0..PATHS.len()).prop_map(Act::ServerSet),
        1 => (0..PATHS.len()).prop_map(Act::ServerDelete),
        2 => (0u64..5_000).prop_map(Act::Run),
        2 => Just(Act::Settle),
    ]
}

fn chunking() -> impl Strategy<Value = Chunking> {
    prop_oneof![
        Just(Chunking::Whole),
        (1usize..9).prop_map(Chunking::Fixed),
        (any::<u64>(), 1usize..64).prop_map(|(seed, max)| Chunking::Seeded { seed, max }),
        proptest::collection::vec(0usize..20, 1..8).prop_map(Chunking::Script),
    ]
}

/// Chunking-invariant execution transcript: client outputs paired with their tickets and the
/// sequence of server inbound frames.
#[derive(Debug, PartialEq, Eq)]
struct Transcript {
    outputs: Vec<(Option<Ticket>, String)>,
    inbound: Vec<String>,
}

fn settle(h: &mut Harness, tickets: &[Ticket]) -> Result<(), TestCaseError> {
    let want = tickets.to_vec();
    let r = h.run_until(
        move |h| want.iter().all(|t| h.recorder().completion_count(*t) > 0),
        30_000,
    );
    prop_assert!(r.is_ok(), "settle: {:?}", r);
    Ok(())
}

fn run(
    p: Profile,
    seed: u64,
    acts: &[Act],
    tx: Chunking,
    rx: Chunking,
    hint: bool,
) -> Result<Transcript, TestCaseError> {
    let mut b = p.builder(seed).tx_chunking(tx).rx_chunking(rx);
    if hint {
        b = b.read_mode(ReadMode::Hint);
    }
    let mut h = b.build();
    h.connect()
        .map_err(|e| TestCaseError::fail(e.to_string()))?;
    let ops = h.ops();
    let mut tickets = Vec::new();
    for a in acts {
        match a {
            Act::Submit { kind, path, watch } => {
                let t = h
                    .submit(make_op(&ops, *kind, *path, *watch))
                    .map_err(|e| TestCaseError::fail(format!("{e:?}")))?;
                tickets.push(t);
            }
            Act::ServerCreate(i) => {
                let _ = h.server_mut().create(PATHS[*i], b"s", 0);
            }
            Act::ServerSet(i) => {
                let _ = h.server_mut().set_data(PATHS[*i], b"t");
            }
            Act::ServerDelete(i) => {
                let _ = h.server_mut().delete(PATHS[*i]);
            }
            Act::Run(ms) => h
                .run_for(*ms)
                .map_err(|e| TestCaseError::fail(e.to_string()))?,
            Act::Settle => settle(&mut h, &tickets)?,
        }
    }
    settle(&mut h, &tickets)?;
    // All requests completed normally without transport teardown.
    prop_assert!(h.conn().last_teardown_cause().is_none());
    let outputs = h
        .recorder()
        .outputs()
        .map(|o| {
            let t = match o {
                Output::Completion { token, .. } => Some(token.ticket()),
                _ => None,
            };
            (t, format!("{o:?}"))
        })
        .collect();
    let inbound = h
        .server()
        .inbound()
        .iter()
        .map(|i| match &i.kind {
            InboundKind::Request { xid, op, path } => format!("{xid} {op} {path:?}"),
            k => format!("{k:?}"),
        })
        .collect();
    let f = h.finish();
    check_once(&f)?;
    Ok(Transcript { outputs, inbound })
}

proptest! {
    #![proptest_config(config(0x5eed_0004))]

    // Because the frame reader buffers partial frames, client outputs and server inbound frames
    // depend strictly on the underlying byte streams rather than individual read/write sizes.
    #[test]
    fn outputs_do_not_depend_on_chunking(
        p in profile(),
        seed in any::<u64>(),
        acts in proptest::collection::vec(act(), 1..16),
        tx in chunking(),
        rx in chunking(),
        hint in any::<bool>(),
    ) {
        let base = run(p, seed, &acts, Chunking::Whole, Chunking::Whole, false)?;
        let other = run(p, seed, &acts, tx, rx, hint)?;
        prop_assert_eq!(base, other);
    }
}
