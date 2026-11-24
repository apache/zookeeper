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

//! Verification of X.509 certificate decoding, trust anchor classification, and store resolution.
//!
//! Validates trust store discovery and parsing against OpenSSL reference behavior:
//! - Multi-label PEM container decoding (`CERTIFICATE`, `TRUSTED CERTIFICATE`, `X509 CRL`).
//! - OpenSSL-compatible subject name canonicalization and RFC 3280 subject hashing.
//! - Auxiliary trust attribute parsing, explicit serverAuth trust/rejection list evaluation.
//! - CA file loading semantics, duplicate certificate de-duplication, and bundle ingestion.
//! - OpenSSL hashed certificate directory (`c_rehash` / `by_dir`) resolution and sequential suffix
//!   probing.
//! - Process-isolated validation of `SSL_CERT_FILE` and `SSL_CERT_DIR` environment discovery
//!   rules.
//! - Resilient property fuzzing of ASN.1 DER certificate parsers and PEM decoders.

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

use std::collections::BTreeMap;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU32, Ordering};

use proptest::prelude::*;
use rustls::pki_types::{CertificateDer, UnixTime};
use zk_driver::net::tls::LegacyPath;
use zk_driver::net::tls::pem::{self, PemError, PemKind};
use zk_driver::net::tls::trust::{Role, TrustLoadError, TrustSet, TrustedCert, classify};
use zk_driver::net::tls::x509::{AuxTrust, CertVersion, LegacyCert};

fn fixtures() -> PathBuf {
    zk_testkit::fixtures::tls_dir().join("trust")
}

fn fixture(name: &str) -> PathBuf {
    fixtures().join(name)
}

fn read(name: &str) -> Vec<u8> {
    std::fs::read(fixture(name)).unwrap()
}

fn ca_path(path: &Path) -> LegacyPath {
    LegacyPath::new(path.as_os_str().as_bytes().to_vec())
}

fn load(name: &str) -> (TrustSet, Result<(), TrustLoadError>) {
    let mut set = TrustSet::default();
    let r = set.load_ca_file(Some(&ca_path(&fixture(name))));
    (set, r)
}

/// Extracts expected test oracle lines of the designated kind from `expect.txt`.
fn expect(kind: &str) -> Vec<Vec<String>> {
    let text = std::fs::read_to_string(fixture("expect.txt")).unwrap();
    let rows: Vec<Vec<String>> = text
        .lines()
        .map(|l| l.split(' ').map(str::to_owned).collect::<Vec<_>>())
        .filter(|w| w.first().is_some_and(|k| k == kind))
        .collect();
    assert!(!rows.is_empty(), "no {kind} lines");
    rows
}

/// Computes byte length of the primary ASN.1 DER TLV to isolate trailing auxiliary trust
/// attributes.
fn tlv_len(der: &[u8]) -> usize {
    let first = der[1];
    if first < 0x80 {
        return 2 + usize::from(first);
    }
    let n = usize::from(first & 0x7F);
    let len = der[2..2 + n]
        .iter()
        .fold(0usize, |a, b| (a << 8) | usize::from(*b));
    2 + n + len
}

/// Extracts stripped certificate DER bytes from the first PEM block of a fixture.
fn cert_der(name: &str) -> Vec<u8> {
    let blocks = pem::read_blocks(&read(name)).unwrap();
    let body = blocks[0].body.expose();
    body[..tlv_len(body)].to_vec()
}

fn der_of(name: &str) -> CertificateDer<'static> {
    CertificateDer::from(cert_der(name))
}

/// Allocates an isolated transient directory under the system temporary root.
fn temp_dir(tag: &str) -> PathBuf {
    static NEXT: AtomicU32 = AtomicU32::new(0);
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!("zk-tls-trust-{}-{tag}-{n}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn holds(set: &TrustSet, name: &str) -> bool {
    let der = der_of(name);
    set.certs().iter().any(|c| *c.der() == der)
}

fn is_anchor(set: &TrustSet, name: &str) -> bool {
    let der = der_of(name);
    set.anchor_candidates().iter().any(|c| *c.der() == der)
}

/// Asserts that PEM encapsulation labels classify accurately into certificate, trusted
/// certificate, and CRL kinds.
#[test]
fn pem_labels_classify() {
    assert_eq!(pem::classify_label("CERTIFICATE"), PemKind::Certificate);
    assert_eq!(
        pem::classify_label("X509 CERTIFICATE"),
        PemKind::Certificate
    );
    assert_eq!(
        pem::classify_label("TRUSTED CERTIFICATE"),
        PemKind::TrustedCertificate
    );
    assert_eq!(pem::classify_label("X509 CRL"), PemKind::Crl);
    assert_eq!(pem::classify_label("certificate"), PemKind::Other);
    assert_eq!(pem::classify_label("CERTIFICATE "), PemKind::Other);
    for (name, label) in [
        ("ca-root.pem", "CERTIFICATE"),
        ("ca-x509-label.pem", "X509 CERTIFICATE"),
        ("root-t-server.pem", "TRUSTED CERTIFICATE"),
        ("crl.pem", "X509 CRL"),
    ] {
        let blocks = pem::read_blocks(&read(name)).unwrap();
        assert_eq!(blocks.len(), 1, "{name}");
        assert_eq!(blocks[0].label, label, "{name}");
        assert!(blocks[0].headers.is_empty(), "{name}");
    }
    let blocks = pem::read_blocks(&read("ca-cert-header.pem")).unwrap();
    assert_eq!(blocks[0].headers.len(), 2);
    assert_eq!(blocks[0].headers[0].0, "Proc-Type");
}

/// Asserts that varied line-ending conventions, BOMs, and surrounding text decode to identical
/// DER payloads.
#[test]
fn pem_same_bytes_from_every_form() {
    let der = cert_der("ca-root.pem");
    for name in [
        "ca-x509-label.pem",
        "ca-bom.pem",
        "ca-crlf.pem",
        "ca-text-around.pem",
    ] {
        assert_eq!(cert_der(name), der, "{name}");
    }
    assert!(pem::der_is_certificate(&der));
    assert!(!pem::der_is_certificate(b"not a certificate"));
    assert_eq!(std::fs::read(fixture("ca-root.der")).unwrap(), der);
}

/// Asserts that truncated, malformed, or mismatched PEM encapsulation blocks return appropriate
/// parsing errors.
#[test]
fn pem_malformed_blocks() {
    assert_eq!(
        pem::read_blocks(&read("ca-bad-base64.pem")).unwrap_err(),
        PemError::Base64
    );
    assert_eq!(
        pem::read_blocks(&read("ca-unterminated.pem")).unwrap_err(),
        PemError::Unterminated
    );
    assert_eq!(
        pem::read_blocks(&read("ca-end-mismatch.pem")).unwrap_err(),
        PemError::Unterminated
    );
    assert_eq!(
        pem::read_blocks(&read("ca-second-bad.pem")).unwrap_err(),
        PemError::Base64
    );
    assert!(pem::read_blocks(&read("ca-root.der")).unwrap().is_empty());
    assert!(pem::read_blocks(b"").unwrap().is_empty());
}

/// Asserts that CA file ingestion conforms to OpenSSL reference load success and failure verdicts.
#[test]
fn ca_file_loads_as_openssl_does() {
    for row in expect("load") {
        let (set, r) = load(&row[1]);
        assert_eq!(r.is_ok(), row[2] == "ok", "{}: {r:?}", row[1]);
        if r.is_err() {
            assert!(set.certs().is_empty(), "{}: all or nothing", row[1]);
            assert_eq!(set.crl_count(), 0, "{}", row[1]);
        }
    }
}

/// Asserts that malformed CA files return deterministic, typed load errors.
#[test]
fn ca_file_errors() {
    let cases = [
        ("ca-empty.pem", TrustLoadError::Empty),
        ("ca-root.der", TrustLoadError::Empty),
        ("ca-other-only.pem", TrustLoadError::Empty),
        ("ca-bad-base64.pem", TrustLoadError::Pem(PemError::Base64)),
        (
            "ca-unterminated.pem",
            TrustLoadError::Pem(PemError::Unterminated),
        ),
        ("ca-cert-header.pem", TrustLoadError::Pem(PemError::Header)),
        (
            "ca-key-dek-short-iv.pem",
            TrustLoadError::Pem(PemError::Header),
        ),
        ("ca-bad-der.pem", TrustLoadError::Der),
        ("ca-bad-crl.pem", TrustLoadError::Der),
        ("ca-key-bad.pem", TrustLoadError::Der),
        ("ca-key-typed-mismatch.pem", TrustLoadError::Der),
        ("ca-trusted-bad-aux.pem", TrustLoadError::Der),
    ];
    for (name, want) in cases {
        assert_eq!(load(name).1, Err(want), "{name}");
    }
    let mut set = TrustSet::default();
    assert_eq!(set.load_ca_file(None), Err(TrustLoadError::NoPath));
    let missing = ca_path(&fixture("no-such-file.pem"));
    assert_eq!(set.load_ca_file(Some(&missing)), Err(TrustLoadError::Io));
    assert_eq!(
        set.load_ca_file(Some(&ca_path(&fixtures()))),
        Err(TrustLoadError::Io)
    );
}

/// Asserts that ordered candidate paths in [`LegacyPath`] are probed sequentially until the first
/// valid file.
#[test]
fn ca_file_candidates_in_order() {
    let mut set = TrustSet::default();
    let path = LegacyPath::with_candidates(
        b"x".to_vec(),
        vec![
            fixture("no-such-file.pem"),
            fixture("ca-root.pem"),
            fixture("ca-empty.pem"),
        ],
    );
    assert_eq!(set.load_ca_file(Some(&path)), Ok(()));
    assert!(is_anchor(&set, "root.pem"));
    let path = LegacyPath::with_candidates(
        b"x".to_vec(),
        vec![fixture("ca-empty.pem"), fixture("ca-root.pem")],
    );
    assert_eq!(set.load_ca_file(Some(&path)), Err(TrustLoadError::Empty));
}

/// Asserts accurate counting of CRL entries and certificate bundle contents during CA file
/// ingestion.
#[test]
fn ca_file_counts_crls_and_bundles() {
    let (set, r) = load("ca-crl-only.pem");
    assert_eq!(r, Ok(()));
    assert_eq!(set.crl_count(), 1);
    assert!(set.certs().is_empty());
    let (set, r) = load("ca-bundle.pem");
    assert_eq!(r, Ok(()));
    assert_eq!(set.certs().len(), 3);
    for name in ["root.pem", "v1root.pem", "nsssl.pem"] {
        assert!(is_anchor(&set, name), "{name}");
    }
}

/// Asserts that duplicate certificates retain their initial role and trust attributes upon
/// subsequent loads.
#[test]
fn ca_file_keeps_first_copy() {
    let mut set = TrustSet::default();
    for name in ["ca-root.pem", "ca-root.pem", "root-r-server.pem"] {
        set.load_ca_file(Some(&ca_path(&fixture(name)))).unwrap();
    }
    assert_eq!(set.certs().len(), 1);
    assert_eq!(set.certs()[0].role(), Role::Anchor);
    assert!(set.certs()[0].aux().is_none());
    let mut set = TrustSet::default();
    for name in ["root-r-server.pem", "ca-root.pem"] {
        set.load_ca_file(Some(&ca_path(&fixture(name)))).unwrap();
    }
    assert_eq!(set.certs().len(), 1);
    assert_eq!(set.certs()[0].role(), Role::Rejected);
    assert_eq!(set.rejected().len(), 1);
    assert!(set.anchor_candidates().is_empty());
}

/// Reference test fixture role classifications: Anchor, Bridge, LeafOnly, or Rejected.
const ROLES: &[(&str, Role)] = &[
    ("root.pem", Role::Anchor),
    ("root-t-server.pem", Role::Anchor),
    ("root-t-any.pem", Role::Anchor),
    ("root-t-client.pem", Role::Rejected),
    ("root-r-server.pem", Role::Rejected),
    ("root-r-client.pem", Role::Anchor),
    ("root-t-empty.pem", Role::Rejected),
    ("root-alias.pem", Role::Anchor),
    ("plain-aux-reject.pem", Role::Anchor),
    ("inter.pem", Role::Bridge),
    ("inter-t-server.pem", Role::Anchor),
    ("inter-t-client.pem", Role::Rejected),
    ("leaf.pem", Role::Bridge),
    ("v1root.pem", Role::Anchor),
    ("clientca.pem", Role::LeafOnly),
    ("nokcs.pem", Role::LeafOnly),
    ("nsssl.pem", Role::Anchor),
    ("nsobj.pem", Role::LeafOnly),
    ("noca.pem", Role::LeafOnly),
    ("noca-t-server.pem", Role::Anchor),
    ("zero-serial.pem", Role::Anchor),
];

fn role_of(name: &str) -> Role {
    let (set, r) = load(name);
    r.unwrap();
    assert_eq!(set.certs().len(), 1, "{name}");
    set.certs()[0].role()
}

/// Asserts that fixture certificates classify into expected trust roles.
#[test]
fn trust_roles() {
    for (name, role) in ROLES {
        assert_eq!(role_of(name), *role, "{name}");
    }
}

/// Asserts that computed trust roles agree with OpenSSL verification verdicts across test cases.
#[test]
fn trust_roles_match_openssl_verdicts() {
    let roles: BTreeMap<&str, Role> = ROLES.iter().copied().collect();
    for row in expect("verify") {
        let (ca, chain, ok) = (&row[1], &row[2], row[3] == "ok");
        if ca.starts_with("hd-") || ca.starts_with("ca-") {
            continue;
        }
        if ca == "root2.pem" {
            assert!(!ok, "root2 did not sign the chain");
            continue;
        }
        let role = roles[ca.as_str()];
        if chain == "noca" {
            // Self-signed leaves present in CA trust bundles match via direct anchor lookup.
            assert_eq!(ok, matches!(role, Role::Anchor | Role::LeafOnly), "{ca}");
        } else {
            assert_eq!(ok, role == Role::Anchor, "{ca} {chain}");
        }
    }
}

/// Asserts appropriate bridging or rejection of intermediate certificates within trust sets.
#[test]
fn bridge_and_rejected_intermediate() {
    let (set, r) = load("ca-root-inter.pem");
    r.unwrap();
    assert_eq!(set.bridges(), &[der_of("inter.pem")]);
    let (set, r) = load("ca-root-inter-t-client.pem");
    r.unwrap();
    assert!(set.bridges().is_empty());
    assert_eq!(set.rejected().len(), 1);
}

/// Asserts accurate parsing and evaluation of OpenSSL auxiliary trust lists and serverAuth
/// constraints.
#[test]
fn aux_trust_lists() {
    let aux = |name: &str| {
        let blocks = pem::read_blocks(&read(name)).unwrap();
        let body = blocks[0].body.expose().to_vec();
        AuxTrust::parse(&body[tlv_len(&body)..]).unwrap()
    };
    let a = aux("root-t-server.pem");
    assert!(a.trusts_server_auth() && !a.rejects_server_auth() && a.has_trust_list());
    let a = aux("root-t-any.pem");
    assert!(a.trusts_server_auth());
    let a = aux("root-t-client.pem");
    assert!(!a.trusts_server_auth() && a.has_trust_list());
    let a = aux("root-r-server.pem");
    assert!(a.rejects_server_auth() && !a.has_trust_list());
    let a = aux("root-r-client.pem");
    assert!(!a.rejects_server_auth() && !a.has_trust_list());
    let a = aux("root-alias.pem");
    assert!(a.trust.is_none() && a.reject.is_none());
    let a = aux("root-t-empty.pem");
    assert_eq!(a.trust.as_deref().map(<[_]>::len), Some(0));
    assert!(AuxTrust::parse(b"not an aux").is_err());
    assert!(AuxTrust::parse(&[]).is_err());
}

/// Asserts certificate classification transitions when augmented with auxiliary trust attributes.
#[test]
fn classify_without_and_with_aux() {
    let root = cert_der("root.pem");
    let cert = LegacyCert::parse(&root).unwrap();
    assert_eq!(classify(&cert, None), Role::Anchor);
    let body = pem::read_blocks(&read("root-r-server.pem")).unwrap()[0]
        .body
        .expose()
        .to_vec();
    let aux = AuxTrust::parse(&body[tlv_len(&body)..]).unwrap();
    assert_eq!(classify(&cert, Some(&aux)), Role::Rejected);
    let t = TrustedCert::new(CertificateDer::from(root.clone()), Some(aux)).unwrap();
    assert_eq!(t.role(), Role::Rejected);
    assert!(TrustedCert::new(CertificateDer::from(b"junk".to_vec()), None).is_err());
}

/// Asserts X.509 certificate parsing, CA constraint flags, EKU extensions, and validity interval
/// checks.
#[test]
fn x509_checks() {
    let root_der = cert_der("root.pem");
    let root = LegacyCert::parse(&root_der).unwrap();
    assert_eq!(root.version(), CertVersion::V3);
    assert!(root.openssl_self_signed());
    assert_eq!(root.openssl_check_ca(), 1);
    assert!(root.eku_allows_ssl_server());
    let v1_der = cert_der("v1root.pem");
    let v1 = LegacyCert::parse(&v1_der).unwrap();
    assert_eq!(v1.version(), CertVersion::V1);
    assert_eq!(v1.openssl_check_ca(), 3);
    let ns_der = cert_der("nsssl.pem");
    assert_eq!(LegacyCert::parse(&ns_der).unwrap().openssl_check_ca(), 5);
    let nokcs_der = cert_der("nokcs.pem");
    assert_eq!(LegacyCert::parse(&nokcs_der).unwrap().openssl_check_ca(), 0);
    let client_der = cert_der("clientca.pem");
    assert!(
        !LegacyCert::parse(&client_der)
            .unwrap()
            .eku_allows_ssl_server()
    );
    let inter_der = cert_der("inter.pem");
    let inter = LegacyCert::parse(&inter_der).unwrap();
    assert!(!inter.openssl_self_signed());
    assert!(zk_driver::net::tls::x509::check_issued(&root, &inter));
    assert!(!zk_driver::net::tls::x509::check_issued(&inter, &root));
    let root2_der = cert_der("root2.pem");
    let root2 = LegacyCert::parse(&root2_der).unwrap();
    assert!(!zk_driver::net::tls::x509::check_issued(&root2, &inter));
    let t2023 = UnixTime::since_unix_epoch(std::time::Duration::from_secs(1_672_531_200));
    let t2030 = UnixTime::since_unix_epoch(std::time::Duration::from_secs(1_893_456_000));
    assert!(!root.valid_at(t2023));
    assert!(root.valid_at(t2030));
}

/// Asserts that RFC 3280 subject hashes match OpenSSL reference hashing implementations.
#[test]
fn subject_hash_matches_openssl() {
    for row in expect("hash") {
        let der = cert_der(&format!("{}.pem", row[1]));
        let cert = LegacyCert::parse(&der).unwrap();
        assert_eq!(format!("{:08x}", cert.subject_hash()), row[2], "{}", row[1]);
    }
}

/// Asserts canonical subject and issuer string normalization and hierarchical linkage.
#[test]
fn canonical_names() {
    let odd_der = cert_der("odd.pem");
    let fold_der = cert_der("odd-fold.pem");
    let odd = LegacyCert::parse(&odd_der).unwrap();
    let fold = LegacyCert::parse(&fold_der).unwrap();
    assert_ne!(odd.subject(), fold.subject());
    assert_eq!(odd.canonical_subject(), fold.canonical_subject());
    assert_eq!(odd.subject_hash(), fold.subject_hash());
    let canon = odd.canonical_subject();
    let text = String::from_utf8_lossy(&canon);
    assert!(text.contains("apache zookeeper"), "{text}");
    assert!(text.contains("zk odd name"), "{text}");
    let pairs = [
        ("leaf.pem", "inter.pem"),
        ("inter.pem", "root.pem"),
        ("root.pem", "root.pem"),
    ];
    for (child, parent) in pairs {
        let c = cert_der(child);
        let p = cert_der(parent);
        let c = LegacyCert::parse(&c).unwrap();
        let p = LegacyCert::parse(&p).unwrap();
        assert_eq!(c.canonical_issuer(), p.canonical_subject(), "{child}");
    }
}

/// Asserts hashed certificate directory discovery and resolution matching OpenSSL `by_dir`
/// semantics.
#[test]
fn hash_dir_matches_openssl() {
    for row in expect("verify") {
        if !row[1].starts_with("hd-") {
            continue;
        }
        let mut set = TrustSet::default();
        set.load_hash_dir(&fixture(&row[1]));
        assert_eq!(is_anchor(&set, "root.pem"), row[3] == "ok", "{}", row[1]);
    }
}

/// Asserts directory suffix sequencing, missing hash handling, and malformed symlink rejection.
#[test]
fn hash_dir_suffixes_and_wrong_hashes() {
    let loaded = |dir: &str| {
        let mut set = TrustSet::default();
        set.load_hash_dir(&fixture(dir));
        set
    };
    let set = loaded("hd-next");
    assert!(holds(&set, "root2.pem") && holds(&set, "root.pem"));
    let set = loaded("hd-gap");
    assert!(holds(&set, "root2.pem") && !holds(&set, "root.pem"));
    // Valid certificate preceding a corrupted block causes load abort and halts traversal.
    let set = loaded("hd-partial");
    assert!(!holds(&set, "root2.pem") && !holds(&set, "root.pem"));
    // Certificates stored under non-matching hash filenames are treated as foreign entries.
    let set = loaded("hd-wrong");
    assert!(set.anchor_candidates().is_empty());
    assert!(set.bridges().is_empty() && set.rejected().is_empty());
    assert_eq!(set.certs().len(), 2);
    assert!(set.certs().iter().all(TrustedCert::foreign));
    // by_dir traversal ignores files with CRL hash conventions when searching certificates.
    let set = loaded("hd-rname");
    assert!(set.certs().is_empty());
    let set = loaded("hd-auxreject");
    assert!(set.anchor_candidates().is_empty());
    assert_eq!(set.rejected().len(), 1);
    let set = loaded("no-such-dir");
    assert!(set.certs().is_empty());

    // Filename formats ignored by by_dir: uppercase hex, truncated hash, non-decimal suffix.
    let dir = temp_dir("names");
    let root = read("root.pem");
    for name in [
        "B3BF326D.0",
        "b3bf326.0",
        "b3bf326d.",
        "b3bf326d.0x",
        "0b3bf326d.0",
    ] {
        std::fs::write(dir.join(name), &root).unwrap();
    }
    let mut set = TrustSet::default();
    set.load_hash_dir(&dir);
    assert!(set.certs().is_empty());
    std::fs::remove_dir_all(&dir).unwrap();
    // Traversal targets exact numerical suffix formatting ("b3bf326d.0", not "b3bf326d.00").
    let dir = temp_dir("suffix");
    std::fs::write(dir.join("b3bf326d.00"), &root).unwrap();
    set.load_hash_dir(&dir);
    assert!(set.certs().is_empty());
    std::fs::write(dir.join("b3bf326d.0"), read("root2.pem")).unwrap();
    std::fs::write(dir.join("b3bf326d.1"), &root).unwrap();
    set.load_hash_dir(&dir);
    assert!(holds(&set, "root.pem") && holds(&set, "root2.pem"));
    std::fs::remove_dir_all(&dir).unwrap();
}

/// Asserts that auxiliary trust attributes in hashed directories take precedence across all label
/// formats.
#[test]
fn hash_dir_takes_aux_from_every_label() {
    let mut set = TrustSet::default();
    set.load_hash_dir(&fixture("hd-auxreject"));
    assert_eq!(set.rejected()[0].role(), Role::Rejected);
    let (set, r) = load("ca-plain-aux-reject.pem");
    r.unwrap();
    assert!(
        is_anchor(&set, "root.pem"),
        "the CA file ignores aux on CERTIFICATE"
    );
}

/// Asserts temporal filtering of trust anchors against specified evaluation timestamps.
#[test]
fn root_store_filters_by_time() {
    let (set, r) = load("ca-bundle.pem");
    r.unwrap();
    let t2030 = UnixTime::since_unix_epoch(std::time::Duration::from_secs(1_893_456_000));
    let t2023 = UnixTime::since_unix_epoch(std::time::Duration::from_secs(1_672_531_200));
    assert_eq!(set.root_store_at(t2030).len(), 3);
    assert!(set.root_store_at(t2023).is_empty());
}

/// Asserts that unreadable or invalid default trust store paths are silently skipped without
/// raising errors.
#[test]
fn default_file_ignores_failures() {
    let mut set = TrustSet::default();
    set.load_default_file(&fixture("ca-bad-der.pem"));
    set.load_default_file(&fixture("no-such-file.pem"));
    set.load_default_file(&fixtures());
    assert!(set.certs().is_empty());
    set.load_default_file(&fixture("ca-root.pem"));
    assert!(is_anchor(&set, "root.pem"));
}

/// Isolated child process entry point for validating environment variable trust discovery.
#[test]
fn env_child() {
    let Some(mode) = std::env::var_os("ZK_TRUST_CHILD") else {
        return;
    };
    let mut set = TrustSet::default();
    let allowed = mode == "allowed";
    assert_eq!(set.load_default_paths(allowed), Ok(()));
    let want_root = allowed && std::env::var_os("ZK_TRUST_WANT_ROOT").is_some();
    let want_root2 = allowed && std::env::var_os("ZK_TRUST_WANT_ROOT2").is_some();
    assert_eq!(holds(&set, "root.pem"), want_root, "root");
    assert_eq!(holds(&set, "root2.pem"), want_root2, "root2");
}

fn run_child(mode: &str, envs: &[(&str, PathBuf)], want: &[&str]) {
    let mut cmd = Command::new(std::env::current_exe().unwrap());
    cmd.args(["env_child", "--exact", "--test-threads=1"])
        .env_remove("SSL_CERT_FILE")
        .env_remove("SSL_CERT_DIR")
        .env("ZK_TRUST_CHILD", mode);
    for (k, v) in envs {
        cmd.env(k, v);
    }
    for w in want {
        cmd.env(w, "1");
    }
    let out = cmd.output().unwrap();
    assert!(
        out.status.success(),
        "{mode} {envs:?}: {}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    assert!(
        String::from_utf8_lossy(&out.stdout).contains("1 passed"),
        "child ran"
    );
}

/// Asserts that `SSL_CERT_FILE` and `SSL_CERT_DIR` environment variables configure default trust
/// paths when permitted.
#[test]
fn default_paths_follow_env_when_allowed() {
    let file = ("SSL_CERT_FILE", fixture("ca-root.pem"));
    let dir = ("SSL_CERT_DIR", fixture("hd-gap"));
    let both = [file.clone(), dir.clone()];
    run_child(
        "allowed",
        &both,
        &["ZK_TRUST_WANT_ROOT", "ZK_TRUST_WANT_ROOT2"],
    );
    run_child(
        "allowed",
        std::slice::from_ref(&file),
        &["ZK_TRUST_WANT_ROOT"],
    );
    run_child(
        "allowed",
        std::slice::from_ref(&dir),
        &["ZK_TRUST_WANT_ROOT2"],
    );
    // Colon-delimited directory list splitting with empty component filtering.
    let mut list = fixture("no-such-dir").into_os_string();
    list.push("::");
    list.push(fixture("hd-basic"));
    list.push(":");
    list.push(fixture("hd-gap"));
    let list = ("SSL_CERT_DIR", PathBuf::from(list));
    run_child(
        "allowed",
        &[list],
        &["ZK_TRUST_WANT_ROOT", "ZK_TRUST_WANT_ROOT2"],
    );
    let missing = ("SSL_CERT_FILE", fixture("no-such-file.pem"));
    run_child("allowed", &[missing], &[]);
}

/// Asserts that trust environment discovery variables are ignored when disallowed by driver
/// configuration.
#[test]
fn default_paths_ignore_env_when_not_allowed() {
    let both = [
        ("SSL_CERT_FILE", fixture("ca-root.pem")),
        ("SSL_CERT_DIR", fixture("hd-gap")),
    ];
    run_child("denied", &both, &[]);
}

/// Asserts that FIFO special files encountered in hashed certificate directories are safely
/// ignored.
#[test]
fn hash_dir_skips_fifo() {
    let dir = temp_dir("fifo");
    let fifo = dir.join("b3bf326d.0");
    let made = Command::new("mkfifo")
        .arg(&fifo)
        .status()
        .is_ok_and(|s| s.success());
    if !made {
        return;
    }
    std::fs::write(dir.join("b3bf326d.1"), read("root.pem")).unwrap();
    let mut set = TrustSet::default();
    set.load_hash_dir(&dir);
    assert!(set.certs().is_empty());
    let mut set = TrustSet::default();
    let path = ca_path(&fifo);
    assert_eq!(set.load_ca_file(Some(&path)), Err(TrustLoadError::Io));
    std::fs::remove_dir_all(&dir).unwrap();
}

/// Encapsulates raw DER bytes within standard 64-character line-wrapped PEM formatting.
fn wrap(label: &str, body: &[u8]) -> Vec<u8> {
    const B64: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut text = Vec::new();
    for chunk in body.chunks(3) {
        let n = chunk
            .iter()
            .enumerate()
            .fold(0u32, |a, (i, b)| a | u32::from(*b) << (16 - 8 * i));
        for i in 0..4 {
            if i <= chunk.len() {
                text.push(B64[((n >> (18 - 6 * i)) & 63) as usize]);
            } else {
                text.push(b'=');
            }
        }
    }
    let mut out = format!("-----BEGIN {label}-----\n").into_bytes();
    for line in text.chunks(64) {
        out.extend_from_slice(line);
        out.push(b'\n');
    }
    out.extend_from_slice(format!("-----END {label}-----\n").as_bytes());
    out
}

#[test]
fn wrap_matches_fixture() {
    let der = cert_der("ca-root.pem");
    assert_eq!(wrap("CERTIFICATE", &der), read("ca-root.pem"));
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 256, failure_persistence: None, ..ProptestConfig::default() })]

    /// Asserts that arbitrary byte sequences provided to PEM and certificate decoders never panic.
    #[test]
    fn no_input_panics(data in proptest::collection::vec(any::<u8>(), 0..2048)) {
        let _ = pem::read_blocks(&data);
        let _ = pem::der_is_certificate(&data);
        let _ = LegacyCert::parse(&data);
        let _ = AuxTrust::parse(&data);
        let _ = pem::cipher_info_ok(&[
            ("Proc-Type".to_owned(), String::from_utf8_lossy(&data).into_owned()),
            ("DEK-Info".to_owned(), String::from_utf8_lossy(&data).into_owned()),
        ]);
    }

    /// Asserts that mutated certificate bytes never cause panic during parsing or hash
    /// calculation.
    #[test]
    fn no_mutated_certificate_panics(
        name in proptest::sample::select(vec!["root.pem", "v1root.pem", "nsssl.pem", "odd.pem"]),
        edits in proptest::collection::vec((any::<usize>(), any::<u8>()), 1..8),
        cut in any::<usize>(),
    ) {
        let mut der = cert_der(name);
        for (at, b) in edits {
            let at = at % der.len();
            der[at] = b;
        }
        der.truncate(cut % (der.len() + 1) + der.len() / 2);
        if let Ok(cert) = LegacyCert::parse(&der) {
            let _ = cert.subject_hash();
            let _ = cert.openssl_self_signed();
            let _ = cert.openssl_check_ca();
            let _ = cert.canonical_issuer();
            let _ = classify(&cert, None);
        }
        let _ = pem::der_is_certificate(&der);
        for label in ["CERTIFICATE", "TRUSTED CERTIFICATE", "X509 CRL", "PRIVATE KEY"] {
            let dir = temp_dir("prop");
            let path = dir.join("ca.pem");
            std::fs::write(&path, wrap(label, &der)).unwrap();
            let mut set = TrustSet::default();
            let _ = set.load_ca_file(Some(&ca_path(&path)));
            std::fs::rename(&path, dir.join("b3bf326d.0")).unwrap();
            set.load_hash_dir(&dir);
            std::fs::remove_dir_all(&dir).unwrap();
        }
    }
}
