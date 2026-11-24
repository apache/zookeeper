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

//! Deterministic pseudorandom number generation for property based and differential tests.
//!
//! Implements the SplitMix64 pseudorandom generator, providing fast, no dependency,
//! reproducible pseudorandom sequences identical across all compilation targets for a
//! given seed.

/// Deterministic 64-bit pseudorandom number generator based on the SplitMix64 algorithm.
#[derive(Clone, Debug)]
pub struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    /// Initializes a new generator state from the provided 64-bit seed.
    pub fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Generates the next pseudorandom unsigned 64-bit integer.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9e37_79b9_7f4a_7c15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        z ^ (z >> 31)
    }

    /// Returns a pseudorandom value uniformly distributed in `0..n`, or 0 if `n == 0`.
    pub fn below(&mut self, n: u64) -> u64 {
        self.next_u64().checked_rem(n).unwrap_or(0)
    }

    /// Returns a pseudorandom slice index in `0..n`, or 0 if `n == 0`.
    pub fn index(&mut self, n: usize) -> usize {
        let n64 = u64::try_from(n).unwrap_or(u64::MAX);
        usize::try_from(self.below(n64)).unwrap_or(0)
    }

    /// Evaluates a boolean trial that succeeds with fractional probability `num / den`.
    pub fn chance(&mut self, num: u64, den: u64) -> bool {
        self.below(den) < num
    }

    /// Simulates POSIX `random()`, returning a value in the range `[0, 2^31 - 1]`.
    pub fn c_random(&mut self) -> i64 {
        i64::try_from(self.next_u64() >> 33).unwrap_or(0)
    }

    /// Simulates POSIX `drand48()`, producing a 53-bit precision float in `[0.0, 1.0)`.
    pub fn unit_f64(&mut self) -> f64 {
        let bits = self.next_u64() >> 11;
        // The 53-bit significand is represented exactly in double precision IEEE 754.
        let top = u32::try_from(bits >> 21).unwrap_or(0);
        let low = u32::try_from(bits & 0x1f_ffff).unwrap_or(0);
        (f64::from(top) * 2_097_152.0 + f64::from(low)) / 9_007_199_254_740_992.0
    }

    /// Selects an element uniformly at random from a non-empty slice.
    pub fn pick<'a, T>(&mut self, items: &'a [T]) -> Option<&'a T> {
        items.get(self.index(items.len()))
    }
}
