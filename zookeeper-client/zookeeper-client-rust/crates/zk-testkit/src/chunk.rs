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

//! Stream fragmentation strategies for in-memory simulated transport.
//!
//! Real network sockets deliver TCP byte streams across arbitrary packet
//! boundaries, fragmenting protocol headers and payload frames across multiple
//! `read` and `write` invocations. The chunking infrastructure models these
//! unaligned delivery characteristics deterministically, ensuring that frame
//! assembly edge cases and partial read buffers can be reproduced reliably.

use crate::rng::SplitMix;

/// Strategy governing how contiguous byte buffers are fragmented across transport operations.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub enum Chunking {
    /// Delivers the entire available buffer in a single operation without fragmentation.
    #[default]
    Whole,
    /// Restricts each operation to at most the specified byte limit (treating 0 as 1 byte).
    Fixed(usize),
    /// Generates pseudorandom fragment sizes in `1..=max` from a deterministic seed.
    Seeded {
        /// Initial seed for the deterministic pseudorandom chunk size generator.
        seed: u64,
        /// Upper bound on individual fragment sizes (values of 0 default to 1).
        max: usize,
    },
    /// Cycled sequence of explicit chunk sizes, repeating continuously (empty vectors fall back
    /// to [`Chunking::Whole`]).
    Script(Vec<usize>),
}

impl Chunking {
    /// Worst-case fragmentation plan transferring exactly one byte per read or write step.
    pub const ONE_BYTE: Self = Self::Fixed(1);
}

/// Stateful iterator that yields fragment sizes according to an active [`Chunking`] strategy.
#[derive(Clone, Debug)]
pub struct Chunker {
    plan: Chunking,
    rng: SplitMix,
    pos: usize,
}

impl Chunker {
    /// Constructs a new chunker initialized for the given fragmentation strategy.
    #[must_use]
    pub fn new(plan: Chunking) -> Self {
        let seed = match &plan {
            Chunking::Seeded { seed, .. } => *seed,
            Chunking::Whole | Chunking::Fixed(_) | Chunking::Script(_) => 0,
        };
        Self {
            plan,
            rng: SplitMix::new(seed),
            pos: 0,
        }
    }

    /// Returns a reference to the active fragmentation configuration.
    #[must_use]
    pub const fn plan(&self) -> &Chunking {
        &self.plan
    }

    /// Computes the allowable byte transfer length for the next operation given `available` bytes.
    ///
    /// The returned length is guaranteed to fall in the range `1..=available`, returning 0
    /// only when no bytes are currently available for transfer.
    pub fn next_len(&mut self, available: usize) -> usize {
        if available == 0 {
            return 0;
        }
        let want = match &self.plan {
            Chunking::Whole => available,
            Chunking::Fixed(n) => (*n).max(1),
            Chunking::Seeded { max, .. } => self.rng.range_usize(1, (*max).max(1)),
            Chunking::Script(list) => {
                let len = list.len();
                let at = self.pos.checked_rem(len).unwrap_or(0);
                self.pos = self.pos.wrapping_add(1);
                list.get(at).copied().map_or(available, |n| n.max(1))
            }
        };
        want.clamp(1, available)
    }

    /// Partitions a contiguous byte slice into sequential chunk references adhering to this plan.
    pub fn split<'a>(&mut self, bytes: &'a [u8]) -> Vec<&'a [u8]> {
        let mut out = Vec::new();
        let mut rest = bytes;
        while !rest.is_empty() {
            let n = self.next_len(rest.len());
            let (head, tail) = rest.split_at(n.min(rest.len()));
            out.push(head);
            rest = tail;
        }
        out
    }
}
