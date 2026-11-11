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

//! Deterministic pseudorandom number generators and execution audit logs.
//!
//! Provides deterministic RNG implementations for both testkit internal decisions (such as frame
//! chunking boundaries and synthetic session IDs) and protocol driver hooks (`zk_proto::Rng`).
//! Implementations include [`SplitMix`] for fast uniform hashing, [`SeededRng`] and [`ScriptedRng`]
//! for deterministic or pre-canned random sequences, and [`LibcRandomClone`] for bug-for-bug
//! parity with the legacy C client's `srandom`/`drand48` shuffle routines.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, PoisonError};

use zk_proto::Rng;

/// A SplitMix64 pseudorandom generator providing fast, high quality 64-bit deterministic streams.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SplitMix {
    state: u64,
}

impl SplitMix {
    /// Initializes a SplitMix64 generator seeded with the provided 64-bit state.
    #[must_use]
    pub const fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Advances generator state and produces the next pseudorandom 64-bit unsigned integer.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Returns a pseudorandom integer uniformly sampled from `[0, n)` (or 0 if `n == 0`).
    pub fn below(&mut self, n: u64) -> u64 {
        self.next_u64().checked_rem(n).unwrap_or(0)
    }

    /// Returns a pseudorandom integer uniformly distributed in `[lo, hi]` (or `lo` if `hi < lo`).
    pub fn range_usize(&mut self, lo: usize, hi: usize) -> usize {
        let span = u64::try_from(hi.saturating_sub(lo)).unwrap_or(u64::MAX);
        let off = self.below(span.saturating_add(1));
        lo.saturating_add(usize::try_from(off).unwrap_or(0))
    }

    /// Generates a floating-point value uniformly distributed in `[0.0, 1.0)` with 53 bits of
    /// precision.
    pub fn next_f64(&mut self) -> f64 {
        let bits = self.next_u64() >> 11;
        let hi = u32::try_from(bits >> 32).unwrap_or(0);
        let lo = u32::try_from(bits & 0xFFFF_FFFF).unwrap_or(0);
        (f64::from(hi) * 4_294_967_296.0 + f64::from(lo)) / 9_007_199_254_740_992.0
    }
}

/// Recorded observation of an individual pseudo-random generator invocation.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum RngCall {
    /// Re-seeding notification triggered by address resolution (`on_resolution`).
    Reseed,
    /// Execution of `random()`, recording the produced signed 64-bit value.
    Random(i64),
    /// Execution of `drand48()`, recording the produced double-precision float.
    Drand48(f64),
}

/// Thread-safe audit log recording all draws and reseed operations across an [`Rng`] instance.
#[derive(Clone, Debug, Default)]
pub struct RngLog {
    calls: Arc<Mutex<Vec<RngCall>>>,
}

impl RngLog {
    /// Initializes an empty random invocation audit log.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn push(&self, c: RngCall) {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .push(c);
    }

    /// Retrieves a snapshot of all recorded random draws in chronological invocation order.
    #[must_use]
    pub fn calls(&self) -> Vec<RngCall> {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    /// Returns the aggregate count of random invocations recorded.
    #[must_use]
    pub fn len(&self) -> usize {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .len()
    }

    /// Returns `true` if no random invocations have been logged.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Clears all recorded invocations from the audit log.
    pub fn clear(&self) {
        self.calls
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .clear();
    }
}

fn log_push(log: Option<&RngLog>, c: RngCall) {
    if let Some(l) = log {
        l.push(c);
    }
}

/// Deterministic [`Rng`] implementation backed by [`SplitMix`] that maintains a continuous
/// pseudo-random stream across reseed hooks.
#[derive(Clone, Debug)]
pub struct SeededRng {
    gen_: SplitMix,
    log: Option<RngLog>,
}

impl SeededRng {
    /// Constructs a deterministic random number generator initialized with the specified seed.
    #[must_use]
    pub const fn new(seed: u64) -> Self {
        Self {
            gen_: SplitMix::new(seed),
            log: None,
        }
    }

    /// Attaches an audit log handle to record all subsequent random draws and reseed events.
    #[must_use]
    pub fn with_log(mut self, log: RngLog) -> Self {
        self.log = Some(log);
        self
    }
}

impl Rng for SeededRng {
    fn on_resolution(&mut self) {
        log_push(self.log.as_ref(), RngCall::Reseed);
    }

    fn random(&mut self) -> i64 {
        let v = i64::try_from(self.gen_.next_u64() >> 33).unwrap_or(0);
        log_push(self.log.as_ref(), RngCall::Random(v));
        v
    }

    fn drand48(&mut self) -> f64 {
        let v = self.gen_.next_f64();
        log_push(self.log.as_ref(), RngCall::Drand48(v));
        v
    }
}

/// Scripted [`Rng`] provider that emits predefined return values sequentially before falling back
/// to a deterministic PRNG.
#[derive(Clone, Debug)]
pub struct ScriptedRng {
    randoms: VecDeque<i64>,
    drands: VecDeque<f64>,
    fallback: SeededRng,
    log: Option<RngLog>,
}

impl ScriptedRng {
    /// Constructs a scripted RNG with pre-canned values for integer and floating-point draws,
    /// falling back to `seed` when depleted.
    #[must_use]
    pub fn new(randoms: Vec<i64>, drands: Vec<f64>, seed: u64) -> Self {
        Self {
            randoms: randoms.into(),
            drands: drands.into(),
            fallback: SeededRng::new(seed),
            log: None,
        }
    }

    /// Attaches an audit log handle to record all subsequent random operations.
    #[must_use]
    pub fn with_log(mut self, log: RngLog) -> Self {
        self.log = Some(log);
        self
    }
}

impl Rng for ScriptedRng {
    fn on_resolution(&mut self) {
        log_push(self.log.as_ref(), RngCall::Reseed);
    }

    fn random(&mut self) -> i64 {
        let v = self
            .randoms
            .pop_front()
            .unwrap_or_else(|| self.fallback.random());
        log_push(self.log.as_ref(), RngCall::Random(v));
        v
    }

    fn drand48(&mut self) -> f64 {
        let v = self
            .drands
            .pop_front()
            .unwrap_or_else(|| self.fallback.drand48());
        log_push(self.log.as_ref(), RngCall::Drand48(v));
        v
    }
}

const DEG: usize = 31;
const SEP: usize = 3;

/// Pure Rust emulation of the glibc non-linear additive feedback random number generator
/// (`TYPE_3`, 31-word state table).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GlibcRandom {
    state: [u32; DEG],
    f: usize,
    r: usize,
}

impl GlibcRandom {
    /// Initializes a glibc `random()` state table emulating the behavior of `srandom(seed)`.
    #[must_use]
    pub fn new(seed: u32) -> Self {
        let mut g = Self {
            state: [0; DEG],
            f: SEP,
            r: 0,
        };
        g.seed(seed);
        g
    }

    /// Re-seeds the internal 31-word state buffer matching glibc's linear congruential bootstrap
    /// algorithm.
    pub fn seed(&mut self, seed: u32) {
        let seed = if seed == 0 { 1 } else { seed };
        // Emulate glibc signed integer arithmetic: C casts the 32-bit seed to signed int32_t.
        let mut word = i64::from(i32::from_ne_bytes(seed.to_ne_bytes()));
        let mut prev = seed;
        for (i, slot) in self.state.iter_mut().enumerate() {
            if i == 0 {
                *slot = seed;
                continue;
            }
            let hi = word.wrapping_div(127_773);
            let lo = word.wrapping_rem(127_773);
            word = lo.wrapping_mul(16_807).wrapping_sub(hi.wrapping_mul(2_836));
            if word < 0 {
                word = word.wrapping_add(2_147_483_647);
            }
            prev = u32::try_from(word).unwrap_or(prev);
            *slot = prev;
        }
        self.f = SEP;
        self.r = 0;
        for _ in 0..DEG.saturating_mul(10) {
            let _ = self.next_u32();
        }
    }

    /// Advances the sliding feedback tap pointers and returns the next 31-bit random integer.
    pub fn next_u32(&mut self) -> u32 {
        let add = self.state.get(self.r).copied().unwrap_or(0);
        let val = match self.state.get_mut(self.f) {
            Some(slot) => {
                *slot = slot.wrapping_add(add);
                *slot
            }
            None => 0,
        };
        self.f = self.f.saturating_add(1);
        if self.f >= DEG {
            self.f = 0;
            self.r = self.r.saturating_add(1);
        } else {
            self.r = self.r.saturating_add(1);
            if self.r >= DEG {
                self.r = 0;
            }
        }
        val >> 1
    }
}

impl Default for GlibcRandom {
    /// Initializes the generator matching the unseeded glibc default state (`srandom(1)`).
    fn default() -> Self {
        Self::new(1)
    }
}

const DRAND_MASK: u64 = (1 << 48) - 1;

/// Pure Rust emulation of POSIX `drand48()` 48-bit linear congruential generator.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Drand48 {
    x: u64,
}

impl Drand48 {
    /// Initializes the 48-bit state register emulating POSIX `srand48(seed)`.
    #[must_use]
    pub fn new(seed: u32) -> Self {
        let mut d = Self::default();
        d.seed(seed);
        d
    }

    /// Re-seeds the 48-bit internal register matching standard `srand48` bit formatting.
    pub fn seed(&mut self, seed: u32) {
        self.x = (u64::from(seed) << 16) | 0x330E;
    }

    /// Advances the 48-bit linear congruential recurrence and returns the next double-precision
    /// float in `[0.0, 1.0)`.
    pub fn next_f64(&mut self) -> f64 {
        self.x = self.x.wrapping_mul(0x5_DEEC_E66D).wrapping_add(0xB) & DRAND_MASK;
        let hi = u32::try_from(self.x >> 16).unwrap_or(0);
        let lo = u32::try_from(self.x & 0xFFFF).unwrap_or(0);
        (f64::from(hi) * 65_536.0 + f64::from(lo)) / 281_474_976_710_656.0
    }
}

impl Default for Drand48 {
    /// Returns the initial 48-bit state register value of an unseeded POSIX process.
    fn default() -> Self {
        Self {
            x: 0x1234_ABCD_330E,
        }
    }
}

/// Exact behavioral test double for the C client's pseudo-random number generator routines.
///
/// Emulates the mixed use of glibc `random()` and POSIX `drand48()` across host shuffle and
/// jitter calculations, re-seeding both generators at each address resolution boundary
/// (`setup_random`) from a supplied sequence of seeds.
#[derive(Clone, Debug)]
pub struct LibcRandomClone {
    random: GlibcRandom,
    drand: Drand48,
    seeds: Vec<u32>,
    next_seed: usize,
    log: Option<RngLog>,
}

impl LibcRandomClone {
    /// Constructs a libc PRNG clone configured with a sequence of seed values consumed at
    /// successive resolution events.
    #[must_use]
    pub fn new(seeds: Vec<u32>) -> Self {
        Self {
            random: GlibcRandom::default(),
            drand: Drand48::default(),
            seeds,
            next_seed: 0,
            log: None,
        }
    }

    /// Attaches an audit log handle to record all subsequent random operations.
    #[must_use]
    pub fn with_log(mut self, log: RngLog) -> Self {
        self.log = Some(log);
        self
    }
}

impl Rng for LibcRandomClone {
    fn on_resolution(&mut self) {
        let seed = self
            .seeds
            .get(self.next_seed)
            .or_else(|| self.seeds.last())
            .copied()
            .unwrap_or(1);
        self.next_seed = self.next_seed.saturating_add(1);
        self.random.seed(seed);
        self.drand.seed(seed);
        log_push(self.log.as_ref(), RngCall::Reseed);
    }

    fn random(&mut self) -> i64 {
        let v = i64::from(self.random.next_u32());
        log_push(self.log.as_ref(), RngCall::Random(v));
        v
    }

    fn drand48(&mut self) -> f64 {
        let v = self.drand.next_f64();
        log_push(self.log.as_ref(), RngCall::Drand48(v));
        v
    }
}
