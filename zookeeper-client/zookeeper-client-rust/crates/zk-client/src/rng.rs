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

//! High-performance pseudorandom number generator dedicated to client session operations.
//!
//! Implements SplitMix64 over an atomic state counter (`AtomicU64`), allowing concurrent, lock-
//! free random number generation through shared `&self` references (such as across host selection
//! routines). Seeds are initialized per client instance from cryptographically secure entropy via
//! `RandomState`, guaranteeing isolated, deterministic sequences without shared state.

use std::hash::BuildHasher;
use std::sync::atomic::{AtomicU64, Ordering};

/// Golden ratio fractional multiplier constant (`2^64 / phi`) utilized by SplitMix64.
const GOLDEN: u64 = 0x9E37_79B9_7F4A_7C15;

/// Lock-free, client-isolated SplitMix64 pseudorandom generator.
#[derive(Debug)]
pub(crate) struct NativeRng {
    state: AtomicU64,
}

impl NativeRng {
    /// Instantiates a new generator seeded from OS entropy via standard library hasher.
    pub(crate) fn new() -> Self {
        let seed = std::collections::hash_map::RandomState::new().hash_one(0x5A4B_u64);
        Self::with_seed(seed)
    }

    /// Constructs a generator with an explicit initial seed for repeatable testing scenarios.
    pub(crate) fn with_seed(seed: u64) -> Self {
        Self {
            state: AtomicU64::new(seed),
        }
    }

    /// Generates the next pseudorandom 64-bit unsigned integer via atomic state progression.
    pub(crate) fn next_u64(&self) -> u64 {
        let s = self
            .state
            .fetch_add(GOLDEN, Ordering::Relaxed)
            .wrapping_add(GOLDEN);
        let mut z = s;
        z = (z ^ z.wrapping_shr(30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ z.wrapping_shr(27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ z.wrapping_shr(31)
    }

    /// Produces a pseudorandom non-negative 31-bit integer within `0..=0x7FFF_FFFF`, matching
    /// standard C `random()`.
    pub(crate) fn random(&self) -> i64 {
        i64::try_from(self.next_u64().wrapping_shr(33)).unwrap_or(0)
    }

    /// Generates a double precision floating point number uniformly distributed in the half-
    /// open interval `[0.0, 1.0)`.
    pub(crate) fn drand48(&self) -> f64 {
        let bits = 0x3FF0_0000_0000_0000_u64 | self.next_u64().wrapping_shr(12);
        f64::from_bits(bits) - 1.0
    }
}

impl zk_proto::Rng for NativeRng {
    fn on_resolution(&mut self) {}

    fn random(&mut self) -> i64 {
        NativeRng::random(self)
    }

    fn drand48(&mut self) -> f64 {
        NativeRng::drand48(self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn values_stay_in_range() {
        let r = NativeRng::with_seed(1);
        for _ in 0..10_000 {
            let v = r.random();
            assert!((0..=0x7FFF_FFFF).contains(&v));
            let d = r.drand48();
            assert!((0.0..1.0).contains(&d));
        }
    }

    #[test]
    fn two_generators_differ() {
        let a = NativeRng::new();
        let b = NativeRng::new();
        let xa: Vec<u64> = (0..4).map(|_| a.next_u64()).collect();
        let xb: Vec<u64> = (0..4).map(|_| b.next_u64()).collect();
        assert_ne!(xa, xb);
    }

    #[test]
    fn a_fixed_seed_repeats() {
        let a = NativeRng::with_seed(42);
        let b = NativeRng::with_seed(42);
        assert_eq!(a.next_u64(), b.next_u64());
    }
}
