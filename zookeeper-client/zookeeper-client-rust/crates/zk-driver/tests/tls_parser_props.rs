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

//! Property-based fuzzing and invariant validation of cryptographic parsers and verifiers.
//!
//! Asserts parser robustness, bounds enforcement, and verification integrity via proptest:
//! - Total parser function guarantees: arbitrary byte sequences produce deterministic error
//!   results without panic.
//! - Cryptographic integrity invariants: single-byte mutations of valid leaf certificates
//!   invariably yield rejection.
//! - Resource limit enforcement: malformed PBES2 parameters reject expensive KDF operations
//!   before execution.
//! - Traditional OpenSSL DEK-Info header resilience against corrupted or truncated directive
//!   inputs.

#![cfg(all(unix, feature = "tls", feature = "_legacy-keys"))]
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
use std::fs;
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use pkcs8::EncryptedPrivateKeyInfo;
use pkcs8::pkcs5::pbes2::Kdf;
use proptest::prelude::*;
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use zk_driver::net::tls::identity::{Identity, check_pbes2_params, decrypt_pkcs8};
use zk_driver::net::tls::legacy_pem;
use zk_driver::net::tls::legacy_verify::LegacyChainOnly;
use zk_driver::net::tls::pem::{self, PemBlock};
use zk_driver::net::tls::trust::TrustSet;
use zk_driver::net::tls::{LegacyPath, default_provider};
use zk_proto::SecretBytes;

/// Standard fixture decryption passphrase.
const PASS: &[u8] = b"zk-test-pass";

/// Encrypted PKCS#8 corpus test fixtures.
const PKCS8: &[&str] = &[
    "p8-sha256-aes256.pem",
    "p8-sha1-aes128.pem",
    "p8-sha224-aes192.pem",
    "p8-sha384-aes256.pem",
    "p8-sha512-aes128.pem",
    "p8-sha512-256.pem",
    "p8-des3.pem",
    "p8-scrypt.pem",
    "p8-pkcs12.pem",
    "p8-camellia.pem",
];

/// Traditional OpenSSL encrypted corpus test fixtures.
const TRADITIONAL: &[&str] = &[
    "trad-aes128.pem",
    "trad-aes192.pem",
    "trad-aes256.pem",
    "trad-des3.pem",
    "trad-des.pem",
    "trad-cfb.pem",
    "trad-camellia.pem",
    "rsa-trad-aes256.pem",
    "dsa-trad.pem",
];

/// Unencrypted or legacy single-iteration key files paired with matching certificates.
const KEY_FILES: &[(&str, &str)] = &[
    ("client.pem", "client.key"),
    ("client.pem", "keys/client-sec1.pem"),
    ("client.pem", "keys/client-explicit.pem"),
    ("client.pem", "keys/trad-aes256.pem"),
    ("client.pem", "keys/trad-des3.pem"),
    ("client.pem", "keys/trad-des.pem"),
    ("keys/rsa.pem", "keys/rsa.key"),
    ("keys/rsa.pem", "keys/rsa-pkcs1.pem"),
    ("keys/rsa.pem", "keys/rsa-trad-aes256.pem"),
    ("keys/ed25519.pem", "keys/ed25519.key"),
    ("client.pem", "keys/cert-then-key.pem"),
];

/// Execution bounds preventing resource exhaustion during property evaluation over mutated KDF
/// parameters.
const MAX_ITERATIONS: u32 = 100_000;
const MAX_SCRYPT_WORK: u64 = 1 << 22;

fn fixtures() -> &'static Path {
    zk_testkit::fixtures::tls_dir()
}

/// Reads raw fixture payload bytes from disk.
fn fixture(name: &str) -> Vec<u8> {
    fs::read(fixtures().join(name)).unwrap()
}

fn at(p: &Path) -> LegacyPath {
    LegacyPath::new(p.as_os_str().as_bytes().to_vec())
}

/// Extracts the initial [`PemBlock`] from a named fixture in the `keys/` directory.
fn key_block(name: &str) -> PemBlock {
    pem::read_blocks(&fixture(&format!("keys/{name}")))
        .unwrap()
        .into_iter()
        .next()
        .unwrap()
}

/// Mutates target byte offsets and applies bounded truncation to input data.
fn mutate(mut data: Vec<u8>, edits: &[(usize, u8)], cut: usize) -> Vec<u8> {
    if data.is_empty() {
        return data;
    }
    for &(at, b) in edits {
        let at = at % data.len();
        data[at] = b;
    }
    let keep = data.len() / 2 + cut % (data.len() / 2 + 1);
    data.truncate(keep);
    data
}

/// Evaluates whether KDF iteration count and memory parameters fall within test execution budget
/// bounds.
fn cheap_kdf(der: &[u8]) -> bool {
    let Ok(info) = EncryptedPrivateKeyInfo::try_from(der) else {
        return true;
    };
    match info.encryption_algorithm.pbes2().map(|p| &p.kdf) {
        Some(Kdf::Pbkdf2(p)) => p.iteration_count <= MAX_ITERATIONS,
        Some(Kdf::Scrypt(s)) => {
            u64::from(s.block_size)
                .saturating_mul(u64::from(s.parallelization))
                .saturating_mul(s.cost_parameter)
                <= MAX_SCRYPT_WORK
        }
        _ => true,
    }
}

/// Writes transient payload bytes into an isolated scratch file, returning the path.
fn scratch(body: &[u8]) -> PathBuf {
    static NEXT: AtomicU32 = AtomicU32::new(0);
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    let p = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
        .join(format!("tls_parser_props-{}-{n}.pem", std::process::id()));
    fs::write(&p, body).unwrap();
    p
}

/// Representation of a single corpus test case with initialized trust anchors.
struct Case {
    name: String,
    expect: String,
    now: UnixTime,
    chain: Vec<CertificateDer<'static>>,
    trust: TrustSet,
}

#[cfg(feature = "tls-ring")]
const EXPECT_KEY: &str = "expect";
#[cfg(not(feature = "tls-ring"))]
const EXPECT_KEY: &str = "expect-aws-lc-rs";

/// Initializes and memoizes the differential conformance test corpus.
fn corpus() -> &'static [Case] {
    static CASES: OnceLock<Vec<Case>> = OnceLock::new();
    CASES.get_or_init(|| {
        let root = zk_testkit::fixtures::tls_corpus_dir();
        let mut out = Vec::new();
        for entry in fs::read_dir(root).unwrap() {
            let dir = entry.unwrap().path();
            let Ok(text) = fs::read_to_string(dir.join("verdict.txt")) else {
                continue;
            };
            let v: BTreeMap<_, _> = text.lines().filter_map(|l| l.split_once('=')).collect();
            let secs = v["attime"].parse().unwrap();
            let mut trust = TrustSet::default();
            trust.load_ca_file(Some(&at(&dir.join("ca.pem")))).unwrap();
            for d in ["defaults", "defaults2"] {
                if dir.join(d).is_dir() {
                    trust.load_hash_dir(&dir.join(d));
                }
            }
            let chain = CertificateDer::pem_slice_iter(&fs::read(dir.join("chain.pem")).unwrap())
                .collect::<Result<Vec<_>, _>>()
                .unwrap();
            out.push(Case {
                name: dir.file_name().unwrap().to_string_lossy().into_owned(),
                expect: v[EXPECT_KEY].to_owned(),
                now: UnixTime::since_unix_epoch(Duration::from_secs(secs)),
                chain,
                trust,
            });
        }
        out.sort_by(|a, b| a.name.cmp(&b.name));
        assert!(out.len() > 100, "the corpus is incomplete");
        out
    })
}

/// Evaluates [`LegacyChainOnly`] certificate verification against a candidate chain.
fn verify(c: &Case, chain: &[CertificateDer<'static>]) -> Result<(), rustls::Error> {
    let verifier = LegacyChainOnly::new(
        c.trust.clone(),
        default_provider().signature_verification_algorithms,
    );
    let name = ServerName::try_from("192.0.2.1").unwrap();
    verifier
        .verify_server_cert(&chain[0], &chain[1..], &name, &[], c.now)
        .map(|_| ())
}

/// Indices of corpus test cases expected to achieve an ACCEPT verdict.
fn accepted() -> Vec<usize> {
    let all = corpus();
    (0..all.len())
        .filter(|&i| all[i].expect == "ACCEPT")
        .collect()
}

/// Asserts baseline validity of seed fixtures prior to property testing.
#[test]
fn seeds_give_the_recorded_results() {
    let der = key_block("p8-sha256-aes256.pem");
    assert!(decrypt_pkcs8(der.body.expose(), PASS).is_ok());
    assert!(legacy_pem::decrypt(&key_block("trad-des3.pem"), PASS).is_ok());
    let c = &corpus()[accepted()[0]];
    assert!(verify(c, &c.chain).is_ok(), "{}", c.name);
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 256, failure_persistence: None, ..ProptestConfig::default() })]

    /// Asserts that arbitrary byte sequences supplied to key parameter checkers never panic.
    #[test]
    fn no_key_der_panics(data in proptest::collection::vec(any::<u8>(), 0..1024)) {
        let _ = check_pbes2_params(&data);
        if cheap_kdf(&data) {
            let _ = decrypt_pkcs8(&data, PASS);
        }
    }

    /// Asserts that mutated PKCS#8 encrypted payloads fail gracefully without panicking.
    #[test]
    fn no_mutated_pkcs8_panics(
        name in proptest::sample::select(PKCS8),
        edits in proptest::collection::vec((any::<usize>(), any::<u8>()), 1..6),
        cut in any::<usize>(),
    ) {
        let der = mutate(key_block(name).body.expose().to_vec(), &edits, cut);
        let checked = check_pbes2_params(&der);
        if cheap_kdf(&der) && decrypt_pkcs8(&der, PASS).is_ok() {
            prop_assert!(checked.is_ok());
        }
    }

    /// Asserts that malformed traditional PEM blocks and headers fail gracefully without panicking.
    #[test]
    fn no_traditional_block_panics(
        label in proptest::sample::select(vec![
            "RSA PRIVATE KEY", "EC PRIVATE KEY", "PRIVATE KEY", "DSA PRIVATE KEY", "X",
        ]),
        proc_type in proptest::sample::select(vec!["4,ENCRYPTED", "4,ENCRYPTED,X", "", "ENCRYPTED"]),
        cipher in proptest::sample::select(vec![
            "AES-128-CBC", "aes-256-cbc", "DES-EDE3-CBC", "DES-CBC", "AES-128-CFB", "",
        ]),
        iv in "[0-9A-Fa-f,]{0,40}",
        body in proptest::collection::vec(any::<u8>(), 0..256),
        pass in proptest::collection::vec(any::<u8>(), 0..64),
    ) {
        let block = PemBlock {
            label: label.to_owned(),
            headers: vec![
                ("Proc-Type".to_owned(), proc_type.to_owned()),
                ("DEK-Info".to_owned(), format!("{cipher},{iv}")),
            ],
            body: SecretBytes::new(body),
        };
        let _ = legacy_pem::is_encrypted(&block);
        let _ = legacy_pem::decrypt(&block, &pass);
    }

    /// Asserts that corrupted traditional encrypted key bodies fail or decrypt without panicking.
    #[test]
    fn no_mutated_traditional_panics(
        name in proptest::sample::select(TRADITIONAL),
        edits in proptest::collection::vec((any::<usize>(), any::<u8>()), 1..6),
        cut in any::<usize>(),
        header in proptest::option::of("[ -~]{0,48}"),
    ) {
        let mut block = key_block(name);
        let body = mutate(block.body.expose().to_vec(), &edits, cut);
        block.body = SecretBytes::new(body);
        if let Some(h) = header {
            block.headers.retain(|(k, _)| k != "DEK-Info");
            block.headers.push(("DEK-Info".to_owned(), h));
        }
        let _ = legacy_pem::decrypt(&block, PASS);
    }
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 64, failure_persistence: None, ..ProptestConfig::default() })]

    /// Asserts that corrupted key files fail configuration steps without panicking.
    #[test]
    fn no_mutated_key_file_panics(
        pair in proptest::sample::select(KEY_FILES),
        edits in proptest::collection::vec((any::<usize>(), any::<u8>()), 1..6),
        cut in any::<usize>(),
        pass in proptest::sample::select(vec![PASS.to_vec(), Vec::new(), vec![b'a'; 1500]]),
    ) {
        let (cert, key) = pair;
        let body = mutate(fixture(key), &edits, cut);
        let path = scratch(&body);
        let _ = Identity::load(
            &at(&fixtures().join(cert)),
            &at(&path),
            &SecretBytes::new(pass),
            &default_provider(),
        );
        fs::remove_file(&path).unwrap();
    }

    /// Asserts that mutated certificate chains in the corpus terminate evaluation without
    /// panicking.
    #[test]
    fn no_mutated_chain_panics(
        pick in any::<usize>(),
        which in any::<usize>(),
        edits in proptest::collection::vec((any::<usize>(), any::<u8>()), 1..6),
        cut in any::<usize>(),
    ) {
        let c = &corpus()[pick % corpus().len()];
        let mut chain = c.chain.clone();
        let i = which % chain.len();
        chain[i] = CertificateDer::from(mutate(chain[i].to_vec(), &edits, cut));
        let _ = verify(c, &chain);
    }
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 256, failure_persistence: None, ..ProptestConfig::default() })]

    /// Asserts that single byte alterations to valid leaf certificates result in strict
    /// verification rejection.
    #[test]
    fn changed_leaf_is_rejected(
        pick in any::<usize>(),
        at in any::<usize>(),
        b in any::<u8>(),
    ) {
        let ok = accepted();
        let c = &corpus()[ok[pick % ok.len()]];
        let mut leaf = c.chain[0].to_vec();
        let at = at % leaf.len();
        prop_assume!(leaf[at] != b);
        leaf[at] = b;
        let mut chain = c.chain.clone();
        chain[0] = CertificateDer::from(leaf);
        prop_assert!(verify(c, &chain).is_err(), "{}: byte {} set to {:#04x}", c.name, at, b);
    }
}
