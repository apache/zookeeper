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

//! Protocol floor and cipher suite negotiation constraints for the drop in connector.
//!
//! Unlike the legacy C client, which inherits whatever protocol version and cipher suite
//! limits are permitted by the host OpenSSL installation, the Rust cryptographic core
//! enforces a strict baseline: TLS 1.2 and TLS 1.3 only, restricting TLS 1.2 cipher suites
//! strictly to ephemeral ECDHE key exchanges with AEAD ciphers. This test suite decodes
//! initial ClientHello flights and synthesizes downgrade ServerHello flights entirely in
//! memory to verify protocol version floor gating and cipher suite constraints.

#![cfg(all(unix, feature = "tls"))]
#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod tls_util;

use std::net::SocketAddr;
use std::os::unix::ffi::OsStrExt;

use zk_driver::net::tls::connector::{
    LegacyPath, LegacyTlsArgs, RustlsConnector, default_provider,
};
use zk_driver::net::tls::{TlsConn, TlsConnector, TlsFailure, TlsPurpose};
use zk_driver::sync::Unlocked;
use zk_proto::compat::TlsVerify;
use zk_proto::{Endpoint, HostToken};

use crate::tls_util::{TLS12, TLS13, exchange, fixture, server};

/// TLS record type byte identifying handshake protocol payloads.
const HANDSHAKE: u8 = 0x16;
/// TLS record type byte identifying alert protocol payloads.
const ALERT: u8 = 0x15;
/// RFC 8446 TLS extension type identifier for supported versions negotiation.
const SUPPORTED_VERSIONS: u16 = 0x002b;
/// TLS alert description code for protocol version negotiation failure.
const PROTOCOL_VERSION: u8 = 70;
/// Permitted TLS 1.2 cipher suites combining ephemeral ECDHE and AEAD primitives.
const AEAD_ECDHE: &[u16] = &[0xc02b, 0xc02c, 0xc02f, 0xc030, 0xcca8, 0xcca9];
/// TLS renegotiation Signaling Cipher Suite Value (RFC 5746).
const SCSV: u16 = 0x00ff;

/// Instantiates an in-memory legacy-compatible connector session backed by test fixtures.
fn drop_in() -> Box<dyn TlsConn> {
    let args = LegacyTlsArgs {
        ca: Some(LegacyPath::new(
            fixture("ca.pem").as_os_str().as_bytes().to_vec(),
        )),
        cert: None,
        key: None,
        passwd: None,
        env_allowed: false,
        verify: TlsVerify::LegacyChainOnly,
    };
    let ep = Endpoint {
        addr: SocketAddr::from(([127, 0, 0, 1], 2281)),
        host: HostToken {
            index: 0,
            name: b"127.0.0.1".to_vec(),
        },
    };
    RustlsConnector::legacy(args, default_provider())
        .new_conn(&Unlocked::new_at_entry(), TlsPurpose::Main, &ep)
        .unwrap()
}

fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_be_bytes([b[at], b[at + 1]])
}

/// ClientHello payload fields extracted for negotiation verification.
struct Hello {
    legacy_version: u16,
    suites: Vec<u16>,
    versions: Option<Vec<u16>>,
}

/// Decodes and extracts negotiation fields from an initial ClientHello flight.
fn client_hello(tls: &mut dyn TlsConn) -> Hello {
    let mut ct = Vec::new();
    tls.take_ciphertext(&mut ct);
    assert_eq!(ct[0], HANDSHAKE, "the first record is a handshake");
    let len = usize::from(u16_at(&ct, 3));
    let m = &ct[5..5 + len];
    assert_eq!(m[0], 1, "the first message is a ClientHello");
    let legacy_version = u16_at(m, 4);
    // Type (1), length (3), version (2) and random (32).
    let mut at = 38;
    at += 1 + usize::from(m[at]);
    let n = usize::from(u16_at(m, at));
    let suites = m[at + 2..at + 2 + n]
        .chunks(2)
        .map(|c| u16::from_be_bytes([c[0], c[1]]))
        .collect();
    at += 2 + n;
    at += 1 + usize::from(m[at]);
    let end = at + 2 + usize::from(u16_at(m, at));
    at += 2;
    let mut versions = None;
    while at < end {
        let ty = u16_at(m, at);
        let n = usize::from(u16_at(m, at + 2));
        let body = &m[at + 4..at + 4 + n];
        if ty == SUPPORTED_VERSIONS {
            let list = &body[1..1 + usize::from(body[0])];
            versions = Some(
                list.chunks(2)
                    .map(|c| u16::from_be_bytes([c[0], c[1]]))
                    .collect(),
            );
        }
        at += 4 + n;
    }
    Hello {
        legacy_version,
        suites,
        versions,
    }
}

/// Synthesizes a raw ServerHello record targeting a specific protocol version and cipher suite.
fn server_hello(version: u16, suite: u16) -> Vec<u8> {
    let mut body = Vec::new();
    body.extend_from_slice(&version.to_be_bytes());
    body.extend_from_slice(&[0x5a; 32]);
    body.push(0);
    body.extend_from_slice(&suite.to_be_bytes());
    body.push(0);
    let mut msg = vec![2, 0, 0, u8::try_from(body.len()).unwrap()];
    msg.extend_from_slice(&body);
    let mut rec = vec![HANDSHAKE];
    rec.extend_from_slice(&version.to_be_bytes());
    rec.extend_from_slice(&u16::try_from(msg.len()).unwrap().to_be_bytes());
    rec.extend_from_slice(&msg);
    rec
}

/// Verifies that ClientHello advertisements restrict supported versions to TLS 1.2 and TLS 1.3.
#[test]
fn the_client_hello_offers_only_tls_1_2_and_tls_1_3() {
    // The legacy ClientHello record version is pinned to TLS 1.2 (0x0303) for middlebox
    // compatibility, while supported_versions strictly advertises TLS 1.3 and TLS 1.2.
    let hello = client_hello(drop_in().as_mut());
    assert_eq!(hello.legacy_version, 0x0303);
    assert_eq!(hello.versions, Some(vec![0x0304, 0x0303]));
}

/// Verifies that peer ServerHello responses selecting versions below TLS 1.2 trigger fatal alerts.
#[test]
fn a_server_hello_below_tls_1_2_fails_the_handshake() {
    // Servers selecting SSLv3, TLS 1.0, or TLS 1.1 are rejected with a protocol_version fatal
    // alert.
    for version in [0x0300u16, 0x0301, 0x0302] {
        let mut tls = drop_in();
        let _ = client_hello(tls.as_mut());
        assert_eq!(
            tls.feed(&server_hello(version, 0xc02b)),
            Err(TlsFailure::Handshake),
            "version {version:#06x}"
        );
        let mut alert = Vec::new();
        tls.take_ciphertext(&mut alert);
        assert_eq!(alert[0], ALERT, "version {version:#06x}");
        assert_eq!(alert[5..7], [2, PROTOCOL_VERSION], "version {version:#06x}");
    }
}

/// Verifies successful handshake completion against TLS 1.2 and TLS 1.3 peers.
#[test]
fn tls_1_2_and_tls_1_3_servers_connect() {
    // Standard ZooKeeper deployments operate on TLS 1.2 or TLS 1.3, both of which
    // successfully negotiate and complete handshake processing.
    for versions in [TLS12, TLS13] {
        let mut tls = drop_in();
        let mut s = server(versions);
        exchange(tls.as_mut(), &mut s, false).unwrap();
        assert!(!tls.is_handshaking());
        assert!(!s.is_handshaking());
    }
}

/// Verifies that TLS 1.2 suite advertisements are strictly limited to AEAD ECDHE algorithms.
#[test]
fn tls_1_2_suites_are_only_aead_ecdhe() {
    // Legacy CBC modes, static RSA key exchange, and non-elliptic DHE suites are excluded
    // from TLS 1.2 advertisements.
    let hello = client_hello(drop_in().as_mut());
    let tls12: Vec<u16> = hello
        .suites
        .iter()
        .copied()
        .filter(|s| s >> 8 != 0x13 && *s != SCSV)
        .collect();
    assert!(!tls12.is_empty());
    for s in &tls12 {
        assert!(AEAD_ECDHE.contains(s), "suite {s:#06x}");
    }
}
