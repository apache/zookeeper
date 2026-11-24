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

//! Transport teardown with large outbound frames.
//!
//! Verifies that zero-copy lending of large plaintext frames (> 512 KiB)
//! handles mid-stream resets across arbitrary write chunking strategies
//! without leaking pinned buffers or corrupting subsequent connections.
//! Every queued request completes once, and a large write after reconnect
//! arrives intact.

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

use props_common::{Profile, check_once, config_cases, profile};
use proptest::prelude::*;
use zk_proto::{Reply, State};
use zk_testkit::{Chunking, Harness, Ticket};

/// Payload size for the initial large `SetData` request (exceeds the 512 KiB lend threshold).
const BIG: usize = 520 * 1024;
/// Payload size for the post-reconnect `SetData` request.
const BIG2: usize = 600 * 1024;
/// Payload size for surrounding small `SetData` requests.
const SMALL: usize = 100;

/// Wire frame size for `SetData` on `/a`: length prefix, header, path, data, and version.
fn set_frame_len(data: usize) -> usize {
    4 + 8 + (4 + 2) + (4 + data) + 4
}

/// Outbound write chunking strategies with large chunk sizes to keep step counts bounded.
fn chunking() -> impl Strategy<Value = Chunking> {
    prop_oneof![
        Just(Chunking::Whole),
        (1000usize..70_000).prop_map(Chunking::Fixed),
        (any::<u64>(), 1000usize..300_000).prop_map(|(seed, max)| Chunking::Seeded { seed, max }),
        proptest::collection::vec(0usize..200_000, 1..6).prop_map(Chunking::Script),
    ]
}

fn fail(e: impl std::fmt::Debug) -> TestCaseError {
    TestCaseError::fail(format!("{e:?}"))
}

fn data_of(h: &mut Harness) -> Result<Vec<u8>, TestCaseError> {
    let ops = h.ops();
    let r = h.call(ops.get_data("/a", None)).map_err(fail)?;
    prop_assert_eq!(r.rc, 0);
    match r.reply {
        Some(Reply::Data { data, .. }) => Ok(data.as_bytes().unwrap_or_default().to_vec()),
        other => Err(fail(other)),
    }
}

fn run(
    p: Profile,
    seed: u64,
    tx: Chunking,
    before: usize,
    after: usize,
    cut: usize,
) -> Result<(), TestCaseError> {
    let mut h = p.builder(seed).tx_chunking(tx).build();
    h.connect().map_err(fail)?;
    h.server_mut().create("/a", b"x", 0).map_err(fail)?;
    let ops = h.ops();
    let mut tickets: Vec<Ticket> = Vec::new();
    for _ in 0..before {
        tickets.push(
            h.submit(ops.set_data("/a", &[1; SMALL], -1))
                .map_err(fail)?,
        );
    }
    let big = vec![0x11; BIG];
    tickets.push(h.submit(ops.set_data("/a", &big, -1)).map_err(fail)?);
    for _ in 0..after {
        tickets.push(
            h.submit(ops.set_data("/a", &[2; SMALL], -1))
                .map_err(fail)?,
        );
    }
    // Schedule a transport reset within the byte range of the queued outbound frames.
    let total = (before + after) * set_frame_len(SMALL) + set_frame_len(BIG);
    h.reset_after_bytes(cut % total);
    let want = tickets.clone();
    h.run_until(
        move |h| want.iter().all(|t| h.recorder().completion_count(*t) > 0),
        60_000,
    )
    .map_err(fail)?;
    h.run_until(|h| h.state() == State::Connected, 60_000)
        .map_err(fail)?;

    // Verify that the next transport transmits a fresh large frame without residual corruption.
    let new = vec![0x22; BIG2];
    let r = h.call(ops.set_data("/a", &new, -1)).map_err(fail)?;
    prop_assert_eq!(r.rc, 0);
    prop_assert!(
        data_of(&mut h)? == new,
        "the large set reached the server whole"
    );
    let f = h.finish();
    check_once(&f)?;
    Ok(())
}

proptest! {
    #![proptest_config(config_cases(48, 0x5eed_0b05))]

    // Pinned lent frames are unpinned during T1 and drained during T3 before
    // the next transport connects, preventing stale bytes from leaking across
    // connections.
    #[test]
    fn a_reset_while_a_frame_is_lent_is_safe(
        p in profile(),
        seed in any::<u64>(),
        tx in chunking(),
        before in 0usize..3,
        after in 0usize..3,
        cut in any::<usize>(),
    ) {
        run(p, seed, tx, before, after, cut)?;
    }
}
