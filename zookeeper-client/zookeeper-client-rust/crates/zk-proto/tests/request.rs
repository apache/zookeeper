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

//! Verification tests for request descriptor types and TTL validation invariants.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::request::{MAX_TTL, MultiOp, check_ttl};

#[test]
fn ttl_rule() {
    assert_eq!(check_ttl(0, -1), Ok(()));
    assert!(check_ttl(0, 0).is_err());
    assert!(check_ttl(5, 0).is_err());
    assert_eq!(check_ttl(5, MAX_TTL), Ok(()));
    assert!(check_ttl(6, MAX_TTL + 1).is_err());
    assert_eq!(MultiOp::Other { op_type: 7 }.op_type(), 7);
}
