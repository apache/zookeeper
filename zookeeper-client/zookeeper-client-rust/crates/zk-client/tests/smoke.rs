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

//! Smoke test suite validating public domain value types, constant definitions, path syntax, and
//! secret redaction.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use apache_zookeeper::acl::{CREATOR_ALL_ACL, OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{Id, Perms, SecretString, SessionPassword, Version, ZPathBuf};

#[test]
fn acl_constants_match_java_ids() {
    assert_eq!(OPEN_ACL_UNSAFE.len(), 1);
    assert_eq!(OPEN_ACL_UNSAFE.first().unwrap().perms, Perms::ALL);
    assert_eq!(OPEN_ACL_UNSAFE.first().unwrap().id, Id::ANYONE);
    assert_eq!(READ_ACL_UNSAFE.first().unwrap().perms, Perms::READ);
    assert_eq!(CREATOR_ALL_ACL.first().unwrap().id, Id::AUTH_IDS);
    assert_eq!(Perms::ALL.bits(), 0x1f);
}

#[test]
fn version_any_is_minus_one() {
    assert_eq!(Version::ANY.get(), -1);
    assert_eq!(Version::new(7).get(), 7);
}

#[test]
fn paths_are_checked() {
    assert_eq!(ZPathBuf::new("/a/b".into()).unwrap().as_str(), "/a/b");
    assert!(ZPathBuf::new("a".into()).is_err());
    assert!(ZPathBuf::new("/a/".into()).is_err());
}

#[test]
fn secrets_never_print() {
    let s = SecretString::new("hunter2".into());
    assert!(!format!("{s:?}").contains("hunter2"));
    let pw = SessionPassword::from_bytes([7; 16]);
    assert!(!format!("{pw:?}").contains('7'));
}

#[test]
fn builder_debug_hides_credentials() {
    let b = common::builder("127.0.0.1:2181").auth("digest", b"user:secret".to_vec());
    let text = format!("{b:?}");
    assert!(text.contains("127.0.0.1:2181"));
    assert!(!text.contains("secret"));
}
