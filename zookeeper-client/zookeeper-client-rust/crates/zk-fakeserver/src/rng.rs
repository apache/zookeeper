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

//! Deterministic pseudorandom number generator for reproducible test execution.
//!
//! The server uses this seeded generator to synthesize session identifiers, session passwords,
//! fault-injection rule probabilities, and fragmented network frame chunk sizes. An identical
//! initial seed produces a deterministic, platform independent sequence across test runs.

/// Self-contained SplitMix64 pseudorandom number generator.
///
/// Maintains 64 bits of internal state, providing uniform distribution without external crate
/// dependencies.
#[derive(Debug, Clone)]
pub(crate) struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    /// Constructs a generator initialized with the given 64-bit seed.
    pub(crate) const fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Advances the generator state and returns the next 64-bit unsigned integer.
    pub(crate) fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ z.wrapping_shr(30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ z.wrapping_shr(27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ z.wrapping_shr(31)
    }

    /// Generates a pseudorandom integer bounded within `[0, n)`, or 0 if `n` is zero.
    pub(crate) fn below(&mut self, n: u64) -> u64 {
        self.next_u64().checked_rem(n).unwrap_or(0)
    }

    /// Fills `out` with pseudorandom bytes derived from successive 64-bit words.
    pub(crate) fn fill(&mut self, out: &mut [u8]) {
        for chunk in out.chunks_mut(8) {
            let v = self.next_u64().to_be_bytes();
            for (d, s) in chunk.iter_mut().zip(v) {
                *d = s;
            }
        }
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
mod tests {
    use super::SplitMix64;

    #[test]
    fn same_seed_same_sequence() {
        let mut a = SplitMix64::new(7);
        let mut b = SplitMix64::new(7);
        for _ in 0..16 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        assert_eq!(a.below(0), 0);
        assert!(a.below(5) < 5);
    }
}
