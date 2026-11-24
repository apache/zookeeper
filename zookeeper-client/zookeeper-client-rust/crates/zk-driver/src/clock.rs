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

//! Monotonic time providers for session timing and protocol deadlines.
//!
//! To keep the protocol state machine purely deterministic and side effect free,
//! the core state machine in `zk_proto` does not query the system clock directly.
//! Instead, the driver layer reads an instance of [`Clock`] and supplies the
//! current timestamp as an explicit `now` parameter to all state transitions.
//! (TLS certificate validity checks rely on Rustls' internal time provider instead.)

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use zk_proto::Millis;

/// Monotonic millisecond clock provider used to drive heartbeat intervals and timeouts.
pub trait Clock: Send + Sync {
    /// Returns the current monotonic timestamp in milliseconds, guaranteed not to move backward.
    fn now(&self) -> Millis;
}

/// Standard production clock measuring monotonic elapsed time relative to an initialization
/// baseline.
#[derive(Clone, Copy, Debug)]
pub struct MonotonicClock {
    origin: Instant,
}

impl MonotonicClock {
    /// Creates a new monotonic clock with its origin anchored to the current instant.
    #[must_use]
    pub fn new() -> Self {
        Self {
            origin: Instant::now(),
        }
    }
}

impl Default for MonotonicClock {
    fn default() -> Self {
        Self::new()
    }
}

impl Clock for MonotonicClock {
    fn now(&self) -> Millis {
        Millis(u64::try_from(self.origin.elapsed().as_millis()).unwrap_or(u64::MAX))
    }
}

/// Manually controllable clock implementation used in unit and simulation tests.
#[derive(Debug, Default)]
pub struct TestClock {
    ms: AtomicU64,
}

impl TestClock {
    /// Creates a controllable test clock initialized to the specified millisecond offset.
    #[must_use]
    pub const fn new(start: Millis) -> Self {
        Self {
            ms: AtomicU64::new(start.0),
        }
    }

    /// Explicitly overwrites the current timestamp.
    pub fn set(&self, now: Millis) {
        self.ms.store(now.0, Ordering::SeqCst);
    }

    /// Advances the clock forward by the given duration in milliseconds, saturating at `u64::MAX`.
    pub fn advance(&self, ms: u64) {
        let mut cur = self.ms.load(Ordering::SeqCst);
        // Emulate atomic fetch_update via a compare-exchange loop to ensure compatibility across
        // MSRV toolchains.
        while let Err(seen) = self.ms.compare_exchange_weak(
            cur,
            cur.saturating_add(ms),
            Ordering::SeqCst,
            Ordering::SeqCst,
        ) {
            cur = seen;
        }
    }
}

impl Clock for TestClock {
    fn now(&self) -> Millis {
        Millis(self.ms.load(Ordering::SeqCst))
    }
}
