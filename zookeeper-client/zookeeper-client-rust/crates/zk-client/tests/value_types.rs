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

//! Invariant tests for public value types: standard ACL presets, `Stat` metadata representations,
//! `SessionId` hexadecimal formatting, `SessionPassword` redaction, TTL range constraints,
//! `CreateMode` properties, `SecretString` encapsulation, and `SessionState` predicates.

#![allow(clippy::unwrap_used)]
#![allow(clippy::disallowed_macros)]

use std::time::Duration;

use apache_zookeeper::acl::{CREATOR_ALL_ACL, OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{
    CreateMode, EphemeralKind, Id, Perms, SecretString, SessionId, SessionPassword, SessionState,
    Stat, Ttl,
};

// ---- acl ----

#[test]
fn standard_lists() {
    assert_eq!(OPEN_ACL_UNSAFE.len(), 1);
    assert_eq!(OPEN_ACL_UNSAFE.first().map(|a| a.perms), Some(Perms::ALL));
    assert_eq!(READ_ACL_UNSAFE.first().map(|a| a.perms), Some(Perms::READ));
    assert_eq!(
        CREATOR_ALL_ACL.first().map(|a| a.id.scheme.as_ref()),
        Some("auth")
    );
    assert_eq!(Perms::from_bits_retain(0x40).bits(), 0x40);
    assert_eq!(Id::ip("10.0.0.0/8").scheme, "ip");
}

// ---- types ----

fn stat_with_owner(owner: i64) -> Stat {
    Stat {
        ephemeral_owner: owner,
        ..Stat::default()
    }
}

#[test]
fn ephemeral_kinds_follow_java() {
    assert_eq!(stat_with_owner(0).ephemeral_kind(), EphemeralKind::None);
    assert_eq!(
        stat_with_owner(i64::MIN).ephemeral_kind(),
        EphemeralKind::Container
    );
    let ttl = i64::from_be_bytes(0xFF00_0000_0000_1388_u64.to_be_bytes());
    assert_eq!(
        stat_with_owner(ttl).ephemeral_kind(),
        EphemeralKind::Ttl(Duration::from_millis(5000))
    );
    assert_eq!(
        stat_with_owner(0x1234).ephemeral_kind(),
        EphemeralKind::Session(SessionId(0x1234))
    );
}

#[test]
fn session_id_prints_hex() {
    assert_eq!(SessionId(255).to_string(), "0x00000000000000ff");
    assert_eq!(format!("{:?}", SessionId(1)), "0x0000000000000001");
}

#[test]
fn password_is_redacted() {
    let p = SessionPassword::from_bytes([7; 16]);
    assert_eq!(format!("{p:?}"), "<redacted>");
    assert_eq!(p.as_bytes(), &[7; 16]);
}

#[test]
fn ttl_bounds() {
    assert!(Ttl::from_millis(0).is_err());
    assert!(Ttl::from_millis(1).is_ok());
    assert_eq!(Ttl::from_millis(0xFF_FFFF_FFFF).unwrap(), Ttl::MAX);
    assert!(Ttl::from_millis(0x100_0000_0000).is_err());
    assert_eq!(Ttl::new(Duration::from_secs(2)).unwrap().as_millis(), 2000);
}

#[test]
fn create_mode_predicates() {
    let t = Ttl::from_millis(10).unwrap();
    assert!(CreateMode::PersistentSequentialWithTtl(t).is_sequential());
    assert!(CreateMode::EphemeralSequential.is_ephemeral());
    assert_eq!(CreateMode::PersistentWithTtl(t).ttl(), Some(t));
    assert_eq!(CreateMode::Container.ttl(), None);
}

// ---- secret ----

#[test]
fn debug_is_redacted() {
    let s = SecretString::from("hunter2");
    assert_eq!(format!("{s:?}"), "<redacted>");
    assert_eq!(s.expose(), "hunter2");
    assert_eq!(s.duplicate().expose(), "hunter2");
}

// ---- session state ----

#[test]
fn predicates() {
    assert!(SessionState::ConnectedReadOnly.is_connected());
    assert!(!SessionState::Connecting.is_connected());
    assert!(SessionState::Closed.is_terminal());
    assert!(!SessionState::Connected.is_terminal());
}
