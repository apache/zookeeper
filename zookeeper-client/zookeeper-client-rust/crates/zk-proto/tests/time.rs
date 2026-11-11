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

//! Unit tests for monotonic timestamp arithmetic, timeout clamping, and fractional session
//! interval calculations.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::time::{Millis, clamp_timeout, elapsed, third, two_thirds, until};

#[test]
fn elapsed_saturates() {
    assert_eq!(elapsed(Millis(10), Millis(4)), 6);
    assert_eq!(elapsed(Millis(4), Millis(10)), 0);
}

#[test]
fn until_is_signed() {
    assert_eq!(until(Millis(4), Millis(10)), 6);
    assert_eq!(until(Millis(10), Millis(4)), -6);
}

#[test]
fn thirds_truncate() {
    assert_eq!(two_thirds(10_000), 6_666);
    assert_eq!(third(10_000), 3_333);
    assert_eq!(two_thirds(i32::MAX), 1_431_655_764);
    assert_eq!(third(-7), -2);
}

#[test]
fn clamp() {
    assert_eq!(clamp_timeout(-5), 0);
    assert_eq!(clamp_timeout(i64::MAX), u32::MAX);
}
