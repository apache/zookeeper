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

//! Verification tests for [`SecretBytes`] debug redaction and zeroizing memory duplication.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::SecretBytes;

#[test]
fn debug_is_redacted() {
    let s = SecretBytes::from_slice(b"hunter2");
    assert_eq!(format!("{s:?}"), "SecretBytes(7 bytes)");
}

#[test]
fn duplicate_is_equal() {
    let s = SecretBytes::from_slice(b"k");
    assert_eq!(s.duplicate().expose(), s.expose());
    assert_eq!(s.len(), 1);
    assert!(SecretBytes::empty().is_empty());
}
