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

//! Monotonic time representation and protocol deadline arithmetic.
//!
//! Because the protocol engine maintains strict determinism without reading system clocks,
//! callers supply monotonic millisecond timestamps with each event ingestion. Time
//! arithmetic operations are executed using 64-bit signed integers to avoid 32-bit signed
//! integer overflow while preserving integer truncation semantics matching the reference C
//! implementation.

/// Monotonic millisecond timestamp tracking elapsed time from an arbitrary driver-defined epoch.
///
/// External event loops map their high-resolution system or monotonic clocks to this value,
/// while deterministic test harnesses supply discrete artificial time sequences.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Debug, Default)]
pub struct Millis(pub u64);

impl Millis {
    /// Computes a future timestamp advanced by the specified millisecond duration, saturating at
    /// [`u64::MAX`].
    #[must_use]
    pub const fn saturating_add_ms(self, ms: u64) -> Self {
        Self(self.0.saturating_add(ms))
    }

    /// Computes a relative timestamp offset by a signed millisecond duration, saturating at bounds.
    #[must_use]
    pub const fn offset_ms(self, ms: i64) -> Self {
        Self(self.0.saturating_add_signed(ms))
    }
}

/// Computes the elapsed millisecond duration between two timestamps, returning `0` if `then` is in
/// the future.
#[must_use]
pub fn elapsed(now: Millis, then: Millis) -> i64 {
    i64::try_from(now.0.saturating_sub(then.0)).unwrap_or(i64::MAX)
}

/// Computes the signed millisecond duration remaining until a deadline timestamp.
///
/// Returns a positive duration when the deadline remains in the future, zero when reached,
/// or a negative duration indicating the extent to which the deadline has lapsed.
#[must_use]
pub fn until(now: Millis, deadline: Millis) -> i64 {
    if deadline >= now {
        i64::try_from(deadline.0.saturating_sub(now.0)).unwrap_or(i64::MAX)
    } else {
        i64::try_from(now.0.saturating_sub(deadline.0))
            .unwrap_or(i64::MAX)
            .saturating_neg()
    }
}

/// Computes two-thirds of the given timeout (`t * 2 / 3`), matching ZooKeeper's heartbeat ping
/// interval.
#[must_use]
pub fn two_thirds(t: i32) -> i64 {
    i64::from(t).saturating_mul(2).checked_div(3).unwrap_or(0)
}

/// Computes one-third of the given timeout (`t / 3`), matching ZooKeeper's connect and read
/// interval fraction.
#[must_use]
pub fn third(t: i32) -> i64 {
    i64::from(t).checked_div(3).unwrap_or(0)
}

/// Bounds a millisecond delay into a 32-bit unsigned timeout value suitable for event loop poll
/// intervals.
///
/// Negative values (representing expired deadlines) clamp to `0`, emulating `get_timeval` behavior
/// in the C client.
#[must_use]
pub fn clamp_timeout(ms: i64) -> u32 {
    u32::try_from(ms.max(0)).unwrap_or(u32::MAX)
}
