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

//! Deterministic logical clock for simulated protocol execution.
//!
//! The `zk-proto` core remains strictly free of ambient operating system clocks.
//! Every state transition, deadline calculation, and interest poll accepts an
//! explicit [`Millis`] timestamp. The test kit uses [`ManualClock`] to provide a
//! controllable, monotonically increasing reference frame, advancing time only
//! under explicit test direction or when the harness drivers drain quiescent periods.

use zk_proto::Millis;

/// Controllable monotonic clock source driven explicitly during deterministic simulations.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct ManualClock {
    now: Millis,
}

impl ManualClock {
    /// Initializes a monotonic clock anchored at the designated initial timestamp.
    #[must_use]
    pub const fn new(start: Millis) -> Self {
        Self { now: start }
    }

    /// Obtains the current simulated timestamp.
    #[must_use]
    pub const fn now(&self) -> Millis {
        self.now
    }

    /// Advances the simulated timeline forward by the specified millisecond offset,
    /// saturating on arithmetic overflow and returning the updated timestamp.
    pub fn advance(&mut self, ms: u64) -> Millis {
        self.now = self.now.saturating_add_ms(ms);
        self.now
    }

    /// Advances the simulated time to target timestamp `t` if it represents a future
    /// epoch, guaranteeing monotonic progression without backward steps.
    pub fn advance_to(&mut self, t: Millis) -> Millis {
        if t > self.now {
            self.now = t;
        }
        self.now
    }
}
