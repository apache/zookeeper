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

//! Integration testing of client private key formats under FIPS 140-3 boundary constraints.
//!
//! Validates algorithm compliance in FIPS-enforced builds (`tls-aws-lc-rs-fips`):
//! - Decryption is delegated strictly to the cryptographic boundary of AWS-LC-FIPS.
//! - Non-approved algorithms, legacy OpenSSL DEK-Info encapsulation, PBES1 schemes,
//!   and auxiliary derivation primitives (e.g. Scrypt, 3DES, HMAC-SHA1) are rejected.
//! - Approved PBES2 schemes (PBKDF2-HMAC-SHA256/384/512 with AES-CBC) and unencrypted
//!   PKCS#8, SEC1, and PKCS#1 keys remain accepted.

#![cfg(all(unix, feature = "tls-aws-lc-rs-fips"))]
#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;

use common::fixture;
use zk_driver::net::tls::identity::Identity;
use zk_driver::net::tls::{LegacyPath, TlsConfigStep, TlsSetupError, default_provider};
use zk_proto::SecretBytes;

/// Standard fixture decryption passphrase.
const PASS: &[u8] = b"zk-test-pass";

/// Resolves fixture paths within the test suite corpus.
fn named(name: &str) -> LegacyPath {
    let p: PathBuf = match name.strip_prefix("keys/") {
        Some(n) => fixture("keys").join(n),
        None => fixture(name),
    };
    LegacyPath::new(p.as_os_str().as_bytes().to_vec())
}

/// Loads certificate and private key fixtures, mapping configuration errors to the failing step.
fn pair(cert: &str, key: &str) -> Result<Identity, TlsConfigStep> {
    Identity::load(
        &named(cert),
        &named(key),
        &SecretBytes::from_slice(PASS),
        &default_provider(),
    )
    .map_err(|e| match e {
        TlsSetupError::Config { step } => step,
        other => panic!("not a config error: {other:?}"),
    })
}

/// Asserts that FIPS runtime configurations accept only FIPS-approved key formats and cipher
/// suites.
#[test]
fn fips_build_loads_only_approved_key_forms() {
    for (cert, key) in [
        // Unencrypted PKCS#8, SEC1 and PKCS#1 keys.
        ("client.pem", "client.key"),
        ("client.pem", "keys/client-sec1.pem"),
        ("keys/rsa.pem", "keys/rsa.key"),
        ("keys/rsa.pem", "keys/rsa-pkcs1.pem"),
        // PBES2 with PBKDF2-HMAC-SHA256, -SHA384 or -SHA512 and AES-CBC.
        ("client.pem", "keys/p8-sha256-aes256.pem"),
        ("client.pem", "keys/p8-sha384-aes256.pem"),
        ("client.pem", "keys/p8-sha512-aes128.pem"),
    ] {
        assert!(pair(cert, key).is_ok(), "{cert} {key}");
    }
}

/// Asserts that non-approved legacy encryption schemes and non-standard KDFs are rejected under
/// FIPS mode.
#[test]
fn fips_build_rejects_other_key_encryptions() {
    for key in [
        // Traditional PEM encryption, with any cipher.
        "keys/trad-aes128.pem",
        "keys/trad-aes192.pem",
        "keys/trad-aes256.pem",
        "keys/trad-des3.pem",
        "keys/trad-des.pem",
        // DES-EDE3-CBC, PBKDF2 with HMAC-SHA1 or HMAC-SHA224, and scrypt.
        "keys/p8-des3.pem",
        "keys/p8-sha1-aes128.pem",
        "keys/p8-sha224-aes192.pem",
        "keys/p8-scrypt.pem",
    ] {
        assert_eq!(
            pair("client.pem", key).err(),
            Some(TlsConfigStep::ClientKey),
            "{key}"
        );
    }
    assert_eq!(
        pair("keys/rsa.pem", "keys/rsa-trad-aes256.pem").err(),
        Some(TlsConfigStep::ClientKey)
    );
}
