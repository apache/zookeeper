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

//! Conformance tests for session state integer encodings, legacy C symbol names, and disconnect
//! cause return code mappings.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_proto::{
    compat::DisconnectRc,
    rc,
    state::{Cause, State, event_name},
};

#[test]
fn codes_round_trip() {
    for s in [
        State::Closed,
        State::Connecting,
        State::Associating,
        State::Connected,
        State::ReadOnly,
        State::SslConnecting,
        State::NotConnected,
        State::ExpiredSession,
        State::AuthFailed,
    ] {
        assert_eq!(State::from_code(s.code()), Some(s));
    }
    assert_eq!(State::from_code(4), None);
}

#[test]
fn names_follow_c() {
    assert_eq!(State::NotConnected.c_name(), "INVALID_STATE");
    assert_eq!(State::Connected.c_name(), "ZOO_CONNECTED_STATE");
    assert_eq!(event_name(-1), "ZOO_SESSION_EVENT");
    assert_eq!(event_name(9), "INVALID_EVENT");
}

#[test]
fn rc_table() {
    assert_eq!(
        Cause::Tls.rc_for(DisconnectRc::PerCause),
        rc::ZSSLCONNECTIONERROR
    );
    assert_eq!(
        Cause::Tls.rc_for(DisconnectRc::ConnectionLoss),
        rc::ZCONNECTIONLOSS
    );
    assert_eq!(
        Cause::SessionExpired.rc_for(DisconnectRc::ConnectionLoss),
        rc::ZSESSIONEXPIRED
    );
    assert!(!Cause::ForcedClose.runs_handle_error());
    assert!(Cause::SaslTeardown.is_terminal());
}
