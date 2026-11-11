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

//! Static configuration and validation tests for `TlsOptions` without active network connections:
//! `TlsConfigError` display rendering, filesystem path validation, and legacy C-client
//! compatibility parsing.
//!
//! Fixture files are provided via `zk_testkit::fixtures::tls_dir`.

#![cfg(feature = "tls-rustls")]
#![allow(clippy::unwrap_used)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};

use apache_zookeeper::{TlsConfigError, TlsOptions};

use common::fixture;

/// RAII temporary file helper unlinked upon drop.
struct Temp(PathBuf);

impl Temp {
    fn path() -> PathBuf {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let n = NEXT.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!("zk-client-tls-options-{}-{n}", std::process::id()))
    }

    fn file(body: &[u8]) -> Self {
        let p = Self::path();
        std::fs::write(&p, body).unwrap();
        Self(p)
    }
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn err_of(r: Result<TlsOptions, TlsConfigError>) -> TlsConfigError {
    r.err().unwrap()
}

#[test]
fn display_names_the_file_and_the_cause() {
    let p = PathBuf::from("/etc/zk/ca.pem");
    let cases = [
        (
            TlsConfigError::CaFile(p.clone()),
            "failed to load CA file /etc/zk/ca.pem",
        ),
        (
            TlsConfigError::DefaultRoots,
            "no usable root certificate in the default locations",
        ),
        (
            TlsConfigError::CertChain(p.clone()),
            "failed to load client certificate chain from /etc/zk/ca.pem",
        ),
        (
            TlsConfigError::PrivateKey(p),
            "failed to load client private key from /etc/zk/ca.pem",
        ),
        (
            TlsConfigError::KeyMismatch,
            "the client private key does not match the certificate",
        ),
        (TlsConfigError::Context, "cannot make the TLS configuration"),
        (
            TlsConfigError::PlatformVerifier("x".into()),
            "cannot use the platform verifier: x",
        ),
        (TlsConfigError::NotFips, "the TLS configuration is not FIPS"),
    ];
    for (e, text) in cases {
        assert_eq!(e.to_string(), text);
    }
}

#[test]
fn build_reports_a_bad_ca_file() {
    let missing = fixture("missing.pem");
    let e = err_of(TlsOptions::builder().ca_file(&missing).build());
    assert!(matches!(&e, TlsConfigError::CaFile(p) if *p == missing));

    let garbage = Temp::file(b"-----BEGIN CERTIFICATE-----\n!!!\n-----END CERTIFICATE-----\n");
    let e = err_of(TlsOptions::builder().ca_file(&garbage.0).build());
    assert!(matches!(&e, TlsConfigError::CaFile(p) if *p == garbage.0));

    // Files lacking certificates and CRL entries fail validation.
    let key_only = fixture("ca.key");
    let e = err_of(TlsOptions::builder().ca_file(&key_only).build());
    assert!(matches!(&e, TlsConfigError::CaFile(p) if *p == key_only));
}

#[test]
fn build_needs_a_trust_source() {
    let e = err_of(TlsOptions::builder().build());
    assert!(matches!(e, TlsConfigError::Context));
}

#[test]
fn build_checks_the_client_identity() {
    let ok = TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .client_identity(fixture("client.pem"), fixture("client.key"), None)
        .build();
    assert!(ok.is_ok());

    let chain = fixture("missing.pem");
    let e = err_of(
        TlsOptions::builder()
            .ca_file(fixture("ca.pem"))
            .client_identity(&chain, fixture("client.key"), None)
            .build(),
    );
    assert!(matches!(&e, TlsConfigError::CertChain(p) if *p == chain));

    // Key file lacking valid private key PEM blocks fails validation.
    let key = fixture("ca.pem");
    let e = err_of(
        TlsOptions::builder()
            .ca_file(fixture("ca.pem"))
            .client_identity(fixture("client.pem"), &key, None)
            .build(),
    );
    assert!(matches!(&e, TlsConfigError::PrivateKey(p) if *p == key));

    // Specifying a mismatched P-256 private key yields PrivateKey rejection.
    let other = fixture("server.key");
    let e = err_of(
        TlsOptions::builder()
            .ca_file(fixture("ca.pem"))
            .client_identity(fixture("client.pem"), &other, None)
            .build(),
    );
    assert!(matches!(&e, TlsConfigError::PrivateKey(p) if *p == other));
}

#[test]
fn a_key_of_another_type_is_a_key_mismatch() {
    // Mismatched cryptographic algorithms between certificate and key trigger KeyMismatch.
    let ed = Temp::file(
        b"-----BEGIN PRIVATE KEY-----\n\
          MC4CAQAwBQYDK2VwBCIEIFZsce3s/dPi6fmIVU4czLVxub7EWvhZWLqePPyi6WQ/\n\
          -----END PRIVATE KEY-----\n",
    );
    let e = err_of(
        TlsOptions::builder()
            .ca_file(fixture("ca.pem"))
            .client_identity(fixture("client.pem"), &ed.0, None)
            .build(),
    );
    assert!(matches!(e, TlsConfigError::KeyMismatch), "{e:?}");
}

#[test]
fn legacy_c_compat_needs_the_ca_field() {
    let e = err_of(TlsOptions::legacy_c_compat(""));
    assert!(matches!(&e, TlsConfigError::CaFile(p) if p.as_os_str().is_empty()));
}

#[test]
fn legacy_c_compat_names_a_missing_ca_file() {
    let e = err_of(TlsOptions::legacy_c_compat("/no/such/ca.pem"));
    assert!(matches!(&e, TlsConfigError::CaFile(p) if *p == Path::new("/no/such/ca.pem")));
}
