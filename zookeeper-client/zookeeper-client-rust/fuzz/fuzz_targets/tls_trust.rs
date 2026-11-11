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

//! Fuzzes the PEM parser, X.509 certificate inspector, trust store loader, and legacy certificate
//! chain verifier in the drop-in TLS layer.
//!
//! Each fuzz input represents a CA bundle image. The harness parses raw DER and PEM blocks,
//! exercises all `LegacyCert` and `AuxTrust` accessors, populates a `TrustSet` both in-memory
//! and from a temporary file via `load_ca_file`, and invokes `LegacyChainOnly::verify_server_cert`
//! using the parsed certificates as the peer certificate chain.

#![no_main]
#![forbid(unsafe_code)]

use std::path::PathBuf;
use std::sync::OnceLock;

use libfuzzer_sys::fuzz_target;
use rustls::client::danger::ServerCertVerifier;
use rustls::crypto::CryptoProvider;
use rustls_pki_types::{CertificateDer, ServerName, UnixTime};
use std::sync::Arc;
use zk_driver::net::tls::legacy_verify::LegacyChainOnly;
use zk_driver::net::tls::pem::{self, PemKind};
use zk_driver::net::tls::trust::{TrustSet, TrustedCert, classify};
use zk_driver::net::tls::x509::{AuxTrust, LegacyCert, check_issued};
use zk_driver::net::tls::{LegacyPath, default_provider};

/// Fixed evaluation timestamp falling within the validity window of `zk-testkit` fixtures.
const NOW: u64 = 1_800_000_000;

fn provider() -> &'static Arc<CryptoProvider> {
    static P: OnceLock<Arc<CryptoProvider>> = OnceLock::new();
    P.get_or_init(default_provider)
}

/// Returns a process-scoped temporary file path for `TrustSet::load_ca_file` testing.
fn scratch() -> &'static PathBuf {
    static F: OnceLock<PathBuf> = OnceLock::new();
    F.get_or_init(|| {
        std::env::temp_dir().join(format!("zk-fuzz-tls-trust-{}.pem", std::process::id()))
    })
}

/// Computes the total byte length of the leading DER TLV element in `der`, if well-formed.
fn tlv_len(der: &[u8]) -> Option<usize> {
    let first = *der.get(1)?;
    if first < 0x80 {
        return 2usize.checked_add(usize::from(first));
    }
    let n = usize::from(first & 0x7f);
    if n == 0 || n > 4 {
        return None;
    }
    let mut len = 0usize;
    for b in der.get(2..2 + n)? {
        len = (len << 8) | usize::from(*b);
    }
    len.checked_add(2 + n)
}

/// Exercises all `LegacyCert` field and extension accessors and verifies internal consistency.
fn exercise(cert: &LegacyCert<'_>, der: &[u8]) {
    let now = UnixTime::since_unix_epoch(std::time::Duration::from_secs(NOW));
    assert_eq!(cert.der(), der, "LegacyCert::der() did not preserve input slice");
    let _ = cert.version();
    assert!(!cert.tbs().is_empty());
    let _ = (
        cert.signature_alg(),
        cert.signature(),
        cert.issuer(),
        cert.subject(),
        cert.serial(),
    );
    let v = cert.validity();
    assert_eq!(
        v.contains(now),
        cert.valid_at(now),
        "validity().contains() and valid_at() disagreed"
    );
    let _ = (
        cert.basic_constraints(),
        cert.key_usage(),
        cert.ext_key_usage(),
        cert.ns_cert_type(),
    );
    let _ = (
        cert.subject_key_id(),
        cert.authority_key_id(),
        cert.subject_alt_name(),
    );
    let _ = (
        cert.name_constraints(),
        cert.critical_extensions(),
        cert.spki(),
        cert.spki_der(),
    );
    let _ = (
        cert.openssl_self_signed(),
        cert.openssl_check_ca(),
        cert.eku_allows_ssl_server(),
    );
    let _ = (cert.canonical_issuer(), cert.subject_hash());
    let _ = check_issued(cert, cert);
    let _ = classify(cert, None);
}

fuzz_target!(|data: &[u8]| {
    // Evaluate raw input directly as a DER certificate and as an OpenSSL TRUSTED CERTIFICATE aux
    // structure.
    if let Ok(cert) = LegacyCert::parse(data) {
        exercise(&cert, data);
    }
    if let Ok(aux) = AuxTrust::parse(data) {
        let _ = (
            aux.rejects_server_auth(),
            aux.trusts_server_auth(),
            aux.has_trust_list(),
        );
    }

    // Parse PEM blocks and populate an in-memory TrustSet.
    let mut set = TrustSet::default();
    let mut chain: Vec<CertificateDer<'static>> = Vec::new();
    if let Ok(blocks) = pem::read_blocks(data) {
        for block in &blocks {
            let kind = pem::classify_label(&block.label);
            let _ = pem::cipher_info_ok(&block.headers);
            let body = block.body.expose();
            let (der, aux) = match kind {
                PemKind::Certificate => (body, None),
                PemKind::TrustedCertificate => {
                    let Some(n) = tlv_len(body).filter(|n| *n <= body.len()) else {
                        continue;
                    };
                    let (der, rest) = body.split_at(n);
                    (der, AuxTrust::parse(rest).ok())
                }
                _ => continue,
            };
            let is_cert = pem::der_is_certificate(der);
            if let Ok(cert) = LegacyCert::parse(der) {
                exercise(&cert, der);
                let _ = classify(&cert, aux.as_ref());
                for other in &chain {
                    if let Ok(o) = LegacyCert::parse(other.as_ref()) {
                        let _ = (check_issued(&o, &cert), check_issued(&cert, &o));
                    }
                }
            }
            let owned = CertificateDer::from(der.to_vec());
            if let Ok(t) = TrustedCert::new(owned.clone(), aux) {
                let _ = (t.der(), t.aux(), t.role(), t.foreign());
                set.push(t);
            }
            if is_cert && chain.len() < 8 {
                chain.push(owned);
            }
        }
    }
    let now = UnixTime::since_unix_epoch(std::time::Duration::from_secs(NOW));
    let total = set.certs().len();
    assert!(set.anchor_candidates().len() <= total);
    assert!(set.rejected().len() <= total);
    let _ = (set.bridges(), set.crl_count(), set.root_store_at(now));

    // Exercise the file-based CA bundle loader and legacy chain verifier.
    let path = scratch();
    if std::fs::write(path, data).is_ok() {
        let mut loaded = TrustSet::default();
        let res = loaded.load_ca_file(Some(&LegacyPath::new(
            path.as_os_str().as_encoded_bytes().to_vec(),
        )));
        if pem::read_blocks(data).is_err() {
            assert!(
                res.is_err(),
                "malformed PEM block must cause load_ca_file to fail"
            );
        }
        let _ = loaded.root_store_at(now);
        // Evaluate the legacy chain verifier against the loaded TrustSet using the file's
        // certificates as the candidate peer chain.
        if let Some((leaf, rest)) = chain.split_first() {
            let algs = provider().signature_verification_algorithms;
            let v = LegacyChainOnly::new(loaded, algs);
            let name = ServerName::try_from("zk.test").expect("valid static DNS name");
            let _ = v.verify_server_cert(leaf, rest, &name, &[], now);
        }
        // Unlink the temporary CA file before returning.
        let _ = std::fs::remove_file(path);
    }
});
