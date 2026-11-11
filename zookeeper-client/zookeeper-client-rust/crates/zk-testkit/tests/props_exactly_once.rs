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

//! Exactly once token completion.
//!
//! Executes randomized request scripts interleaved with transport resets, fault injection, session
//! expiries, forced closes, and client closes, verifying that every issued completion and auth
//! token exits the core exactly once.

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

use std::cell::Cell;
use std::rc::Rc;

use props_common::{
    PATHS, Profile, Step, apply, check_once, config, profile, script, script_builder,
};
use proptest::prelude::*;
use zk_proto::Output;
use zk_testkit::{Chunking, Harness};

fn run(p: Profile, seed: u64, steps: &[Step], close: bool) -> Result<(), TestCaseError> {
    let h = script_builder(p, seed).build();
    run_on(h, p, steps, close)
}

fn run_on(mut h: Harness, p: Profile, steps: &[Step], close: bool) -> Result<(), TestCaseError> {
    let mut closed = false;
    for s in steps {
        if apply(&mut h, p, s)? {
            closed = true;
            break;
        }
    }
    if close && !closed {
        apply(&mut h, p, &Step::Close)?;
    }
    let f = h.finish();
    check_once(&f)
}

proptest! {
    #![proptest_config(config(0x5eed_0001))]

    // Completion guarantee: every submitted completion or auth token is delivered via an output or
    // returned in `into_leftovers` exactly once.
    #[test]
    fn every_token_leaves_exactly_once(
        p in profile(),
        seed in any::<u64>(),
        steps in script(24),
        close in any::<bool>(),
    ) {
        run(p, seed, &steps, close)?;
    }
}

fn chunking() -> impl Strategy<Value = Chunking> {
    prop_oneof![
        Just(Chunking::Whole),
        (1usize..9).prop_map(Chunking::Fixed),
        (any::<u64>(), 1usize..64).prop_map(|(seed, max)| Chunking::Seeded { seed, max }),
    ]
}

proptest! {
    #![proptest_config(config(0x5eed_0011))]

    // Reentrant submissions from completion/watcher callbacks (including during teardown and close)
    // preserve the exactly-once token delivery invariant across arbitrary wire chunkings.
    #[test]
    fn reentrant_submits_leave_exactly_once(
        p in profile(),
        seed in any::<u64>(),
        steps in script(24),
        close in any::<bool>(),
        every in 1usize..5,
        tx in chunking(),
        rx in chunking(),
    ) {
        let mut h = script_builder(p, seed).tx_chunking(tx).rx_chunking(rx).build();
        let ops = h.ops();
        let count = Rc::new(Cell::new(0usize));
        let c2 = Rc::clone(&count);
        h.set_hook(Box::new(move |ctx, o| {
            if matches!(o, Output::Log(_)) {
                return;
            }
            let n = c2.get() + 1;
            c2.set(n);
            // Bound total reentrant submissions to ensure termination.
            if n % every == 0 && n < 64 {
                let path = PATHS[n % PATHS.len()];
                // Synchronous rejections are recorded by the hook context.
                let _ = ctx.submit(ops.exists(path, None));
            }
        }));
        run_on(h, p, &steps, close)?;
    }
}
