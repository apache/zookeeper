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

//! Integration testing of client identity initialization, key formats, and parameter bounds.
//!
//! Asserts invariant security guarantees across identity loading workflows:
//! - Unencrypted and password-protected PKCS#8, PKCS#1, and SEC1 private key parsing.
//! - PBES2 derivation parameters and Scrypt memory bounds enforcement against resource exhaustion
//!   attacks.
//! - OpenSSL-compatible certificate chain parsing and multi-certificate payload handling.
//! - Keypair public key matching and cryptographic compatibility checking prior to handshake use.
//! - Strict configuration sequencing and deterministic error step attribution.
//! - Zeroization and heap scrub verification preventing sensitive key material leakage.

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

mod common;

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;

use common::fixture;
use common::tls::{TlsAcceptor, legacy_path};
use pkcs8::EncryptedPrivateKeyInfo;
use pkcs8::der::Encode as _;
use pkcs8::pkcs5::pbes2::{self, EncryptionScheme, Kdf, Pbkdf2Params, Pbkdf2Prf, ScryptParams};
use zk_driver::Unlocked;
use zk_driver::net::tls::identity::{
    Identity, KeyError, MAX_PASSPHRASE, PBKDF2_MAX_ITER, check_pbes2_params, decrypt_pkcs8,
};
use zk_driver::net::tls::{
    LegacyPath, LegacyTlsArgs, RustlsConnector, TlsConfigStep, TlsConn, TlsConnector, TlsFailure,
    TlsPurpose, TlsSetupError, default_provider,
};
use zk_fakeserver::{ClusterConfig, FakeServer, ServerOptions};
use zk_proto::compat::TlsVerify;
use zk_proto::{Endpoint, HostToken, SecretBytes};

/// Standard fixture decryption passphrase.
const PASS: &[u8] = b"zk-test-pass";

/// Resolves the filesystem path to a fixture inside the `keys/` directory.
fn key_path(name: &str) -> LegacyPath {
    at(fixture("keys").join(name))
}

/// Wraps a standard filesystem path as a [`LegacyPath`].
fn at(p: PathBuf) -> LegacyPath {
    LegacyPath::new(p.as_os_str().as_bytes().to_vec())
}

/// Resolves a fixture identifier to its corresponding [`LegacyPath`].
fn named(name: &str) -> LegacyPath {
    match name.strip_prefix("keys/") {
        Some(n) => key_path(n),
        None => legacy_path(name),
    }
}

/// Executes [`Identity::load`] and maps failures to their failing configuration step.
fn load(cert: &LegacyPath, key: &LegacyPath, pass: &[u8]) -> Result<Identity, TlsConfigStep> {
    Identity::load(
        cert,
        key,
        &SecretBytes::from_slice(pass),
        &default_provider(),
    )
    .map_err(|e| match e {
        TlsSetupError::Config { step } => step,
        other => panic!("not a config error: {other:?}"),
    })
}

/// Loads a certificate and private key fixture pair using default credentials.
fn pair(cert: &str, key: &str) -> Result<Identity, TlsConfigStep> {
    load(&named(cert), &named(key), PASS)
}

/// Writes transient fixture bytes into the test temporary scratch directory.
fn scratch(name: &str, body: &[u8]) -> LegacyPath {
    let p = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
        .join(format!("tls_identity-{}-{name}", std::process::id()));
    std::fs::write(&p, body).unwrap();
    at(p)
}

/// Extracts unencrypted PKCS#8 DER bytes from the standard client private key fixture.
fn client_pkcs8() -> Vec<u8> {
    common::tls::key("client.key").secret_der().to_vec()
}

/// Encrypts the client private key using PBKDF2-HMAC-SHA256 and AES-256-CBC, returning formatted
/// PEM text.
fn encrypted_client_key(pass: &[u8]) -> String {
    let salt = [7u8; 16];
    let iv = [9u8; 16];
    let params = pbes2::Parameters::pbkdf2_sha256_aes256cbc(1000, &salt, &iv).unwrap();
    let data = params.encrypt(pass, &client_pkcs8()).unwrap();
    let der = EncryptedPrivateKeyInfo {
        encryption_algorithm: params.into(),
        encrypted_data: &data,
    }
    .to_der()
    .unwrap();
    pem_rfc7468::encode_string("ENCRYPTED PRIVATE KEY", pem_rfc7468::LineEnding::LF, &der).unwrap()
}

/// Encodes an [`EncryptedPrivateKeyInfo`] structure into DER with synthetic ciphertext.
fn info_der(params: pbes2::Parameters<'_>) -> Vec<u8> {
    info_der_with(params, &[0u8; 32])
}

/// Encodes an [`EncryptedPrivateKeyInfo`] structure with provided parameters and ciphertext.
fn info_der_with(params: pbes2::Parameters<'_>, data: &[u8]) -> Vec<u8> {
    EncryptedPrivateKeyInfo {
        encryption_algorithm: params.into(),
        encrypted_data: data,
    }
    .to_der()
    .unwrap()
}

const SALT: [u8; 8] = [1, 2, 3, 4, 5, 6, 7, 8];
const IV: [u8; 16] = [3u8; 16];

/// Generates synthetic Scrypt PBES2 DER structures for parameter bounds validation.
fn scrypt_der(n: u64, r: u16, p: u16, key_length: Option<u16>) -> Vec<u8> {
    info_der(pbes2::Parameters {
        kdf: Kdf::Scrypt(ScryptParams {
            salt: &SALT,
            cost_parameter: n,
            block_size: r,
            parallelization: p,
            key_length,
        }),
        encryption: EncryptionScheme::Aes256Cbc { iv: &IV },
    })
}

/// Generates synthetic PBKDF2 PBES2 DER structures for parameter bounds validation.
fn pbkdf2_der(iterations: u32, prf: Pbkdf2Prf, key_length: Option<u16>) -> Vec<u8> {
    info_der(pbes2::Parameters {
        kdf: Kdf::Pbkdf2(Pbkdf2Params {
            salt: &SALT,
            iteration_count: iterations,
            key_length,
            prf,
        }),
        encryption: EncryptionScheme::Aes128Cbc { iv: &IV },
    })
}

// Private key format parsing.

/// Asserts that unencrypted PKCS#8, SEC1, and PKCS#1 keys load successfully regardless of supplied
/// passphrase.
#[test]
fn unencrypted_key_forms_load() {
    for (cert, key) in [
        ("client.pem", "client.key"),
        ("client.pem", "keys/client-sec1.pem"),
        ("keys/rsa.pem", "keys/rsa.key"),
        ("keys/rsa.pem", "keys/rsa-pkcs1.pem"),
        ("keys/ed25519.pem", "keys/ed25519.key"),
    ] {
        assert!(pair(cert, key).is_ok(), "{cert} {key}");
        // Unencrypted keys ignore supplied passphrases.
        assert!(load(&named(cert), &named(key), b"").is_ok(), "{cert} {key}");
        assert!(
            load(&named(cert), &named(key), b"wrong").is_ok(),
            "{cert} {key}"
        );
    }
}

/// Asserts that supported PBES2 and legacy encrypted private key formats decrypt with valid
/// passphrases.
#[test]
fn encrypted_key_forms_load() {
    for key in [
        "p8-sha256-aes256.pem",
        "p8-sha1-aes128.pem",
        "p8-sha224-aes192.pem",
        "p8-sha384-aes256.pem",
        "p8-sha512-aes128.pem",
        "p8-des3.pem",
        "p8-scrypt.pem",
        "trad-aes128.pem",
        "trad-aes192.pem",
        "trad-aes256.pem",
        "trad-des3.pem",
        "trad-des.pem",
    ] {
        assert!(
            load(&legacy_path("client.pem"), &key_path(key), PASS).is_ok(),
            "{key}"
        );
    }
    assert!(pair("keys/rsa.pem", "keys/rsa-trad-aes256.pem").is_ok());
}

/// Asserts that unsupported cryptographic schemes, obsolete algorithms, or missing key files fail
/// at the key step.
#[test]
fn rejected_schemes_and_key_types_fail_the_key_step() {
    for key in [
        // PRF, PBES1, and cipher algorithms outside the supported specification.
        "keys/p8-sha512-256.pem",
        "keys/p8-pkcs12.pem",
        "keys/p8-camellia.pem",
        "keys/trad-cfb.pem",
        "keys/trad-camellia.pem",
        // Asymmetric key algorithms unsupported by active signing providers.
        "keys/dsa.key",
        "keys/dsa-trad.pem",
        "keys/ed448.key",
        "keys/rsa-pss.key",
        "keys/rsa1024.key",
        // Inputs lacking a private key PEM block or referencing missing files.
        "client.pem",
        "absent.key",
    ] {
        assert_eq!(
            pair("client.pem", key).err(),
            Some(TlsConfigStep::ClientKey),
            "{key}"
        );
    }
}

/// Asserts that decryption attempts with invalid passphrases fail at the key configuration step.
#[test]
fn wrong_passphrase_fails_the_key_step() {
    for key in [
        "p8-sha256-aes256.pem",
        "p8-scrypt.pem",
        "trad-aes256.pem",
        "trad-des3.pem",
    ] {
        for pass in [&b""[..], b"zk-test-pas", b"zk-test-pass!"] {
            assert_eq!(
                load(&legacy_path("client.pem"), &key_path(key), pass).err(),
                Some(TlsConfigStep::ClientKey),
                "{key}"
            );
        }
    }
}

/// Asserts that passphrases exceeding 1024 bytes are truncated according to OpenSSL `PEM_BUFSIZE`
/// conventions.
#[test]
fn passphrase_is_cut_to_the_first_1024_bytes() {
    let long: Vec<u8> = (0..1500u32).map(|i| b'a' + (i % 26) as u8).collect();
    let key = scratch(
        "long-pass.pem",
        encrypted_client_key(&long[..MAX_PASSPHRASE]).as_bytes(),
    );
    let cert = legacy_path("client.pem");
    // Passphrase truncation occurs at MAX_PASSPHRASE (1024 bytes).
    assert!(load(&cert, &key, &long).is_ok());
    assert!(load(&cert, &key, &long[..MAX_PASSPHRASE]).is_ok());
    assert_eq!(
        load(&cert, &key, &long[..MAX_PASSPHRASE - 1]).err(),
        Some(TlsConfigStep::ClientKey)
    );
    // Standalone decryption preserves caller buffer integrity.
    let pem = encrypted_client_key(b"pw");
    let (_, der) = pem_rfc7468::decode_vec(pem.as_bytes()).unwrap();
    assert_eq!(decrypt_pkcs8(&der, b"pw").unwrap().expose(), client_pkcs8());
    assert!(decrypt_pkcs8(&der, b"pw2").is_err());
}

/// Asserts that combined PEM files containing certificates followed by private keys parse
/// correctly.
#[test]
fn key_file_may_start_with_a_certificate() {
    let both = key_path("cert-then-key.pem");
    assert!(load(&both, &both, b"").is_ok());
    assert!(load(&legacy_path("client.pem"), &both, b"").is_ok());
}

// Traditional OpenSSL DEK-Info encapsulation headers.

/// Modifies header directives in traditional OpenSSL encrypted PEM fixtures.
fn trad_with(name: &str, edit: impl Fn(&str) -> String) -> LegacyPath {
    let text = std::fs::read_to_string(fixture("keys/trad-aes128.pem")).unwrap();
    scratch(name, edit(&text).as_bytes())
}

/// Extracts the IV substring from a DEK-Info header directive.
fn dek_iv(text: &str) -> String {
    let line = text.lines().find(|l| l.starts_with("DEK-Info:")).unwrap();
    line.split_once(',').unwrap().1.to_owned()
}

/// Asserts strict parsing of traditional OpenSSL DEK-Info encapsulation headers and IV
/// representations.
#[test]
fn dek_info_headers_follow_the_openssl_rule() {
    let cert = legacy_path("client.pem");
    // Cipher identifiers are matched case insensitively.
    let lower = trad_with("dek-lower.pem", |t| t.replace("AES-128-CBC", "aes-128-cbc"));
    assert!(load(&cert, &lower, PASS).is_ok());
    // Proc-Type header must precede DEK-Info.
    let swapped = trad_with("dek-swapped.pem", |t| {
        let proc_type = t.lines().find(|l| l.starts_with("Proc-Type:")).unwrap();
        let dek = t.lines().find(|l| l.starts_with("DEK-Info:")).unwrap();
        t.replace(
            &format!("{proc_type}\n{dek}"),
            &format!("{dek}\n{proc_type}"),
        )
    });
    // Initialization vector must contain exact hex pair encoding matching the cipher block size.
    let short_iv = trad_with("dek-short-iv.pem", |t| {
        let iv = dek_iv(t);
        t.replace(&iv, &iv[..30])
    });
    let bad_hex = trad_with("dek-bad-hex.pem", |t| {
        let iv = dek_iv(t);
        t.replace(&iv, &format!("{}zz", &iv[..30]))
    });
    let unknown = trad_with("dek-unknown.pem", |t| {
        t.replace("AES-128-CBC", "AES-128-XTS")
    });
    let no_dek = trad_with("dek-missing.pem", |t| {
        let dek = t.lines().find(|l| l.starts_with("DEK-Info:")).unwrap();
        t.replace(&format!("{dek}\n"), "")
    });
    for key in [swapped, short_iv, bad_hex, unknown, no_dek] {
        assert_eq!(
            load(&cert, &key, PASS).err(),
            Some(TlsConfigStep::ClientKey),
            "{:?}",
            String::from_utf8_lossy(key.raw())
        );
    }
}

// PBES2 parameter validation and resource bounds.

/// Asserts parameter boundary and memory consumption enforcement for Scrypt key derivation
/// functions.
#[test]
fn scrypt_parameters_are_bounded() {
    // 128·r·(N + 2) + 128·r·p memory consumption cap (32 MiB).
    assert_eq!(check_pbes2_params(&scrypt_der(16384, 8, 1, None)), Ok(()));
    assert_eq!(
        check_pbes2_params(&scrypt_der(32768, 8, 1, None)),
        Err(KeyError::Bounds)
    );
    assert_eq!(
        check_pbes2_params(&scrypt_der(16384, 8, 20000, None)),
        Err(KeyError::Bounds)
    );
    // N must be a power of two > 1, satisfying log2(N) < 16·r.
    for n in [0, 1, 3, 1000, 1 << 63, u64::MAX] {
        assert_eq!(
            check_pbes2_params(&scrypt_der(n, 8, 1, None)),
            Err(KeyError::Bounds),
            "{n}"
        );
    }
    assert_eq!(
        check_pbes2_params(&scrypt_der(1 << 16, 1, 1, None)),
        Err(KeyError::Bounds)
    );
    assert_eq!(check_pbes2_params(&scrypt_der(1 << 15, 1, 1, None)), Ok(()));
    // r and p must be positive with r·p < 2^30.
    assert_eq!(
        check_pbes2_params(&scrypt_der(1024, 0, 1, None)),
        Err(KeyError::Bounds)
    );
    assert_eq!(
        check_pbes2_params(&scrypt_der(1024, 8, 0, None)),
        Err(KeyError::Bounds)
    );
    assert_eq!(
        check_pbes2_params(&scrypt_der(2, u16::MAX, u16::MAX, None)),
        Err(KeyError::Bounds)
    );
    // Explicit key length must match cipher specification.
    assert_eq!(
        check_pbes2_params(&scrypt_der(1024, 8, 1, Some(32))),
        Ok(())
    );
    assert_eq!(
        check_pbes2_params(&scrypt_der(1024, 8, 1, Some(16))),
        Err(KeyError::Bounds)
    );
}

/// Asserts iteration and key length bounds enforcement for PBKDF2 key derivation functions.
#[test]
fn pbkdf2_parameters_are_bounded() {
    for prf in [
        Pbkdf2Prf::HmacWithSha1,
        Pbkdf2Prf::HmacWithSha224,
        Pbkdf2Prf::HmacWithSha256,
        Pbkdf2Prf::HmacWithSha384,
        Pbkdf2Prf::HmacWithSha512,
    ] {
        assert_eq!(
            check_pbes2_params(&pbkdf2_der(1, prf, None)),
            Ok(()),
            "{prf:?}"
        );
    }
    assert_eq!(
        check_pbes2_params(&pbkdf2_der(0, Pbkdf2Prf::HmacWithSha256, None)),
        Err(KeyError::Bounds)
    );
    assert_eq!(
        check_pbes2_params(&pbkdf2_der(1, Pbkdf2Prf::HmacWithSha256, Some(16))),
        Ok(())
    );
    assert_eq!(
        check_pbes2_params(&pbkdf2_der(1, Pbkdf2Prf::HmacWithSha256, Some(32))),
        Err(KeyError::Bounds)
    );
    // Boundary violation aborts processing prior to expensive key derivation operations.
    let der = pbkdf2_der(0, Pbkdf2Prf::HmacWithSha256, None);
    assert_eq!(decrypt_pkcs8(&der, PASS).err(), Some(KeyError::Bounds));
    // Enforce the upper bound on PBKDF2 iteration counts during parameter validation.
    assert_eq!(
        check_pbes2_params(&pbkdf2_der(
            PBKDF2_MAX_ITER,
            Pbkdf2Prf::HmacWithSha256,
            None
        )),
        Ok(())
    );
    for rounds in [PBKDF2_MAX_ITER + 1, u32::MAX] {
        assert_eq!(
            check_pbes2_params(&pbkdf2_der(rounds, Pbkdf2Prf::HmacWithSha256, None)),
            Err(KeyError::Bounds),
            "{rounds}"
        );
    }
    // Excessive iteration counts are rejected prior to executing key derivation.
    let der = pbkdf2_der(u32::MAX, Pbkdf2Prf::HmacWithSha512, None);
    assert_eq!(decrypt_pkcs8(&der, PASS).err(), Some(KeyError::Bounds));
}

/// Creates a named pipe in the scratch directory, returning `None` if `mkfifo` is unavailable.
fn scratch_fifo(name: &str) -> Option<PathBuf> {
    let p = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
        .join(format!("tls_identity-{}-{name}", std::process::id()));
    let _ = std::fs::remove_file(&p);
    std::process::Command::new("mkfifo")
        .arg(&p)
        .status()
        .is_ok_and(|s| s.success())
        .then_some(p)
}

/// Verifies that device nodes, directories, and named pipes fail the certificate or key step
/// immediately without blocking.
#[test]
fn special_files_fail_the_cert_and_key_steps() {
    let cert = named("client.pem");
    let key = named("client.key");
    assert!(load(&cert, &key, PASS).is_ok());
    let zero = at(PathBuf::from("/dev/zero"));
    assert_eq!(
        load(&cert, &zero, PASS).err(),
        Some(TlsConfigStep::ClientKey)
    );
    assert_eq!(
        load(&zero, &key, PASS).err(),
        Some(TlsConfigStep::ClientCertChain)
    );
    let dir = at(PathBuf::from(env!("CARGO_TARGET_TMPDIR")));
    assert_eq!(
        load(&cert, &dir, PASS).err(),
        Some(TlsConfigStep::ClientKey)
    );
    if let Some(fifo) = scratch_fifo("fifo.key") {
        let started = std::time::Instant::now();
        assert_eq!(
            load(&cert, &at(fifo.clone()), PASS).err(),
            Some(TlsConfigStep::ClientKey)
        );
        assert!(started.elapsed() < std::time::Duration::from_secs(5));
        // Non-regular path candidates are skipped in favor of subsequent valid fallback paths.
        let real = key.candidates()[0].clone();
        let both = LegacyPath::with_candidates(b"key".to_vec(), vec![fifo.clone(), real]);
        assert!(load(&cert, &both, PASS).is_ok());
        std::fs::remove_file(&fifo).unwrap();
    }
}

/// Verifies that key files exceeding the maximum file size bound fail the key step.
#[test]
fn oversize_key_file_is_rejected() {
    let big = scratch("oversize.key", b"");
    let path = big.candidates()[0].clone();
    std::fs::File::options()
        .write(true)
        .open(&path)
        .unwrap()
        .set_len(32 * 1024 * 1024 + 1)
        .unwrap();
    assert_eq!(
        load(&named("client.pem"), &big, PASS).err(),
        Some(TlsConfigStep::ClientKey)
    );
    std::fs::remove_file(&path).unwrap();
}

/// Asserts immediate rejection of malformed DER structures, PBES1 schemes, and partial block
/// ciphertexts.
#[test]
fn malformed_encrypted_keys_are_rejected() {
    assert_eq!(check_pbes2_params(b""), Err(KeyError::Rejected));
    assert_eq!(
        check_pbes2_params(&[0x30, 0x03, 1, 2, 3]),
        Err(KeyError::Rejected)
    );
    assert_eq!(
        decrypt_pkcs8(&[0x30, 0x80], PASS).err(),
        Some(KeyError::Rejected)
    );
    // Legacy PBES1 schemes are rejected in favor of PBES2.
    let text = std::fs::read(fixture("keys/p8-pkcs12.pem")).unwrap();
    let (_, der) = pem_rfc7468::decode_vec(&text).unwrap();
    assert_eq!(check_pbes2_params(&der), Err(KeyError::Rejected));
    // Ciphertext with unaligned partial blocks indicates invalid passphrase or corrupted framing.
    let params = pbes2::Parameters::pbkdf2_sha256_aes256cbc(1, &SALT, &IV).unwrap();
    for len in [0, 15, 31] {
        let der = info_der_with(params.clone(), &vec![0u8; len]);
        assert_eq!(
            decrypt_pkcs8(&der, PASS).err(),
            Some(KeyError::BadPassphrase),
            "{len}"
        );
    }
}

// Certificate chain parsing (`SSL_CTX_use_certificate_chain_file`).

fn chain_len(cert: &str) -> usize {
    pair(cert, "client.key").unwrap().certified_key().cert.len()
}

/// Asserts certificate chain resolution, auxiliary trusted cert stripping, and certificate format
/// validation.
#[test]
fn chain_file_rule() {
    let client = common::tls::certs("client.pem");
    let ca = common::tls::certs("ca.pem");
    assert_eq!(chain_len("client.pem"), 1);
    assert_eq!(chain_len("keys/chain.pem"), 2);
    // Post-leaf TRUSTED CERTIFICATE blocks are ignored per OpenSSL conventions.
    assert_eq!(chain_len("keys/chain-trusted-later.pem"), 1);
    // TRUSTED CERTIFICATE leaf headers strip auxiliary attributes.
    let id = pair("keys/trusted.pem", "client.key").unwrap();
    assert_eq!(id.certified_key().cert, client);
    let id = pair("keys/trusted-chain.pem", "client.key").unwrap();
    assert_eq!(id.certified_key().cert, [client[0].clone(), ca[0].clone()]);
    // X.509 version 1 leaf certificates parse successfully.
    assert_eq!(chain_len("keys/v1.pem"), 1);
    // Missing certificates, unreadable files, or corrupted bodies fail certificate chain loading.
    let bad = scratch(
        "bad-body.pem",
        b"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
    );
    let junk = scratch(
        "bad-base64.pem",
        b"-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n",
    );
    for cert in [
        legacy_path("client.key"),
        legacy_path("absent.pem"),
        bad,
        junk,
    ] {
        assert_eq!(
            load(&cert, &legacy_path("client.key"), b"").err(),
            Some(TlsConfigStep::ClientCertChain),
            "{:?}",
            String::from_utf8_lossy(cert.raw())
        );
    }
}

// Certificate and private key matching (`SSL_CTX_check_private_key`).

/// Asserts that public key mismatches between certificate and key of matching types fail the key
/// configuration step.
#[test]
fn same_type_mismatch_fails_the_key_step() {
    for (cert, key) in [
        ("client.pem", "server.key"),
        ("client.pem", "keys/p384.key"),
        // Same key where elliptic curve point is represented in compressed form.
        ("keys/compressed.pem", "client.key"),
        ("keys/rsa.pem", "keys/rsa-e3.key"),
        ("keys/rsa-e3.pem", "keys/rsa.key"),
    ] {
        assert_eq!(
            pair(cert, key).err(),
            Some(TlsConfigStep::ClientKey),
            "{cert} {key}"
        );
    }
}

/// Asserts that algorithm type mismatches between certificate and private key fail the keypair
/// verification step.
#[test]
fn other_type_mismatch_fails_the_check_step() {
    for (cert, key) in [
        ("client.pem", "keys/ed25519.key"),
        ("client.pem", "keys/rsa.key"),
        ("keys/rsa.pem", "client.key"),
        ("keys/ed25519.pem", "client.key"),
        // An RSA-PSS leaf certificate OID does not match a standard rsaEncryption private key.
        ("keys/rsa-pss.pem", "keys/rsa.key"),
    ] {
        assert_eq!(
            pair(cert, key).err(),
            Some(TlsConfigStep::CheckPrivateKey),
            "{cert} {key}"
        );
    }
}

// Cryptographic provider algorithm and parameter support matrix.

/// Asserts crypto provider compatibility matrices for algorithm and key size support.
#[test]
fn provider_key_support() {
    // ring supports signing via P-256, P-384, Ed25519, and RSA (2048-4096 bits with e >= 65537).
    #[cfg(feature = "tls-ring")]
    let want: [(&str, &str, Option<TlsConfigStep>); 5] = [
        (
            "keys/p521.pem",
            "keys/p521.key",
            Some(TlsConfigStep::ClientKey),
        ),
        (
            "keys/rsa6144.pem",
            "keys/rsa6144.key",
            Some(TlsConfigStep::ClientKey),
        ),
        (
            "keys/rsa-e3.pem",
            "keys/rsa-e3.key",
            Some(TlsConfigStep::ClientKey),
        ),
        (
            "client.pem",
            "keys/client-sec1-nopub.pem",
            Some(TlsConfigStep::ClientKey),
        ),
        (
            "client.pem",
            "keys/client-explicit.pem",
            Some(TlsConfigStep::ClientKey),
        ),
    ];
    // aws-lc-rs supports extended algorithm specifications matching standard OpenSSL capabilities.
    #[cfg(not(feature = "tls-ring"))]
    let want: [(&str, &str, Option<TlsConfigStep>); 5] = [
        ("keys/p521.pem", "keys/p521.key", None),
        ("keys/rsa6144.pem", "keys/rsa6144.key", None),
        ("keys/rsa-e3.pem", "keys/rsa-e3.key", None),
        ("client.pem", "keys/client-sec1-nopub.pem", None),
        ("client.pem", "keys/client-explicit.pem", None),
    ];
    for (cert, key, step) in want {
        assert_eq!(pair(cert, key).err(), step, "{cert} {key}");
    }
}

// Connector initialization step sequence verification.

struct Args {
    ca: Option<&'static str>,
    cert: Option<&'static str>,
    key: Option<&'static str>,
    passwd: Option<&'static [u8]>,
    verify: TlsVerify,
}

impl Args {
    fn identity(cert: &'static str, key: &'static str) -> Self {
        Self {
            ca: Some("ca.pem"),
            cert: Some(cert),
            key: Some(key),
            passwd: Some(PASS),
            verify: TlsVerify::LegacyChainOnly,
        }
    }

    fn connector(&self) -> RustlsConnector {
        let args = LegacyTlsArgs {
            ca: self.ca.map(named),
            cert: self.cert.map(named),
            key: self.key.map(named),
            passwd: self.passwd.map(SecretBytes::from_slice),
            env_allowed: false,
            verify: self.verify,
        };
        RustlsConnector::legacy(args, default_provider())
    }
}

fn endpoint(addr: SocketAddr) -> Endpoint {
    Endpoint {
        addr,
        host: HostToken {
            index: 0,
            name: b"127.0.0.1".to_vec(),
        },
    }
}

fn new_conn(c: &RustlsConnector, addr: SocketAddr) -> Result<Box<dyn TlsConn>, TlsSetupError> {
    c.new_conn(&Unlocked::new_at_entry(), TlsPurpose::Main, &endpoint(addr))
}

fn setup_step(args: &Args) -> Option<TlsConfigStep> {
    let c = args.connector();
    match new_conn(&c, "127.0.0.1:1".parse().unwrap()) {
        Ok(_) => None,
        Err(TlsSetupError::Config { step }) => {
            let want = match step {
                TlsConfigStep::CaFile => args.ca.map(named),
                TlsConfigStep::ClientCertChain => args.cert.map(named),
                TlsConfigStep::ClientKey => args.key.map(named),
                TlsConfigStep::DefaultVerifyPaths | TlsConfigStep::CheckPrivateKey => None,
            };
            assert_eq!(c.path_for(step), want.as_ref().map(LegacyPath::raw));
            Some(step)
        }
        Err(other) => panic!("not a config error: {other:?}"),
    }
}

/// Asserts sequential execution order of connector configuration validation steps and fail-fast
/// termination.
#[test]
fn setup_steps_run_in_order() {
    for verify in [TlsVerify::LegacyChainOnly, TlsVerify::WebPki] {
        let all_absent = Args {
            ca: Some("absent-ca.pem"),
            cert: Some("absent.pem"),
            key: Some("absent.key"),
            passwd: Some(PASS),
            verify,
        };
        assert_eq!(setup_step(&all_absent), Some(TlsConfigStep::CaFile));
        let chain_and_key = Args {
            ca: Some("ca.pem"),
            ..all_absent
        };
        assert_eq!(
            setup_step(&chain_and_key),
            Some(TlsConfigStep::ClientCertChain)
        );
        let key = Args {
            cert: Some("client.pem"),
            ..chain_and_key
        };
        assert_eq!(setup_step(&key), Some(TlsConfigStep::ClientKey));
        let check = Args {
            key: Some("keys/rsa.key"),
            ..key
        };
        assert_eq!(setup_step(&check), Some(TlsConfigStep::CheckPrivateKey));
        let ok = Args {
            key: Some("keys/p8-scrypt.pem"),
            ..check
        };
        assert_eq!(setup_step(&ok), None);
    }
}

/// Asserts that client identity resolution requires both certificate and private key
/// specifications.
#[test]
fn identity_needs_cert_and_key() {
    // Missing either certificate or private key inhibits client identity loading without raising
    // fatal errors.
    for (cert, key, passwd) in [
        (Some("absent.pem"), None, None),
        (Some("absent.pem"), None, Some(PASS)),
        (None, Some("absent.key"), Some(PASS)),
        (None, None, Some(PASS)),
    ] {
        let args = Args {
            ca: Some("ca.pem"),
            cert,
            key,
            passwd,
            verify: TlsVerify::LegacyChainOnly,
        };
        assert_eq!(setup_step(&args), None, "{cert:?} {key:?} {passwd:?}");
    }
    // Omitting passphrase defaults to empty secret; invalid certificate chain paths trigger setup
    // errors.
    let args = Args {
        ca: Some("ca.pem"),
        cert: Some("absent.pem"),
        key: Some("absent.key"),
        passwd: None,
        verify: TlsVerify::LegacyChainOnly,
    };
    assert_eq!(setup_step(&args), Some(TlsConfigStep::ClientCertChain));
}

// Mutual TLS client authentication handshakes.

/// Spawns an in-process TLS fake server configured with optional client authentication.
fn tls_server(client_auth: bool) -> FakeServer {
    let opts = ServerOptions {
        acceptor: Some(TlsAcceptor::new(client_auth)),
        ..ServerOptions::default()
    };
    FakeServer::start_with(ClusterConfig::default(), opts).unwrap()
}

/// Drives TLS handshake progression across a blocking TCP stream until establishment or failure
/// latching.
fn handshake(tcp: &mut TcpStream, tls: &mut dyn TlsConn) -> Result<(), TlsFailure> {
    let mut out = Vec::new();
    let mut buf = [0u8; 4096];
    while tls.is_handshaking() {
        out.clear();
        tls.take_ciphertext(&mut out);
        if !out.is_empty() {
            tcp.write_all(&out).unwrap();
            continue;
        }
        let n = tcp.read(&mut buf).unwrap_or(0);
        if n == 0 {
            return tls.feed_eof();
        }
        tls.feed(&buf[..n])?;
    }
    out.clear();
    tls.take_ciphertext(&mut out);
    tcp.write_all(&out).unwrap();
    Ok(())
}

/// Dispatches an `isro` probe request over TLS and drains received application plaintext preceding
/// connection closure.
fn isro(tcp: &mut TcpStream, tls: &mut dyn TlsConn) -> (Vec<u8>, TlsFailure) {
    tls.write_frame(b"isro").unwrap();
    let mut out = Vec::new();
    tls.take_ciphertext(&mut out);
    tcp.write_all(&out).unwrap();
    let mut buf = [0u8; 4096];
    let mut plain = Vec::new();
    loop {
        let n = tcp.read(&mut buf).unwrap_or(0);
        let fed = if n == 0 {
            tls.feed_eof()
        } else {
            tls.feed(&buf[..n])
        };
        loop {
            match tls.read_plaintext(&mut plain) {
                Ok(0) => break,
                Ok(_) => {}
                Err(f) => return (plain, f),
            }
        }
        if let Err(f) = fed {
            return (plain, f);
        }
    }
}

/// Executes end-to-end connection and probe exchange using configured client credentials.
fn probe(server: &FakeServer, args: &Args) -> Result<(Vec<u8>, TlsFailure), TlsFailure> {
    let c = args.connector();
    let mut tls = new_conn(&c, server.addr()).unwrap();
    let mut tcp = TcpStream::connect(server.addr()).unwrap();
    tcp.set_read_timeout(Some(common::WAIT)).unwrap();
    handshake(&mut tcp, tls.as_mut())?;
    Ok(isro(&mut tcp, tls.as_mut()))
}

/// Asserts that supported client identity combinations successfully complete mutual TLS
/// authentication.
#[test]
fn loaded_identities_satisfy_client_auth() {
    let server = tls_server(true);
    for (cert, key) in [
        ("client.pem", "keys/p8-sha256-aes256.pem"),
        ("client.pem", "keys/p8-scrypt.pem"),
        ("client.pem", "keys/p8-des3.pem"),
        ("client.pem", "keys/trad-des3.pem"),
        ("client.pem", "keys/client-sec1.pem"),
        ("keys/trusted.pem", "client.key"),
        ("keys/chain.pem", "client.key"),
        ("keys/cert-then-key.pem", "keys/cert-then-key.pem"),
        ("keys/rsa.pem", "keys/rsa-trad-aes256.pem"),
        ("keys/rsa.pem", "keys/rsa-pkcs1.pem"),
        ("keys/ed25519.pem", "keys/ed25519.key"),
    ] {
        assert_eq!(
            probe(&server, &Args::identity(cert, key)),
            Ok((b"rw".to_vec(), TlsFailure::Closed)),
            "{cert} {key}"
        );
    }
}

/// Asserts that connections omitting required client credentials fail mutual TLS authentication.
#[test]
fn missing_identity_fails_client_auth() {
    let server = tls_server(true);
    let args = Args {
        cert: None,
        ..Args::identity("client.pem", "client.key")
    };
    let got = probe(&server, &args);
    assert_ne!(got, Ok((b"rw".to_vec(), TlsFailure::Closed)), "{got:?}");
}

/// Asserts that unencrypted private keys satisfy mutual authentication without explicit
/// passphrases.
#[test]
fn identity_without_passwd_passes_client_auth() {
    let server = tls_server(true);
    let args = Args {
        passwd: None,
        ..Args::identity("client.pem", "client.key")
    };
    assert_eq!(
        probe(&server, &args),
        Ok((b"rw".to_vec(), TlsFailure::Closed))
    );
}

/// Reads unencrypted RSA private key fixtures into a fixed stack buffer without heap allocations.
fn read_rsa_pkcs1(buf: &mut [u8; 4096]) -> &[u8] {
    let path = zk_testkit::fixtures::tls_dir().join("keys/rsa-pkcs1.pem");
    let mut file = std::fs::File::open(&path).expect("keys/rsa-pkcs1.pem");
    let mut len = 0;
    loop {
        let n = file.read(&mut buf[len..]).expect("read keys/rsa-pkcs1.pem");
        if n == 0 {
            break;
        }
        len += n;
        assert!(len < buf.len(), "keys/rsa-pkcs1.pem fills the stack buffer");
    }
    &buf[..len]
}

/// Asserts that transient heap buffers utilized during PEM decoding are securely zeroized prior
/// to deallocation.
#[test]
#[ignore = "free-scan"]
fn pem_buffers_are_zero_before_free() {
    use zk_driver::net::tls::pem;
    let Some(out) = std::env::var_os("ZK_FREE_SCAN_OUT") else {
        return;
    };
    let hits = || {
        std::fs::read_to_string(&out)
            .map(|t| t.lines().filter(|l| l.starts_with("hit")).count())
            .unwrap_or(0)
    };
    let mut buf = [0u8; 4096];
    let key = read_rsa_pkcs1(&mut buf);
    let marker = key
        .split(|&b| b == b'\n')
        .nth(2)
        .and_then(|line| line.get(..24))
        .expect("keys/rsa-pkcs1.pem has a base64 line 3");
    drop(std::hint::black_box(marker.to_vec()));
    let control = hits();
    assert!(
        control >= 1,
        "the free scanner does not run, or its marker is not the start of \
         line 3 of keys/rsa-pkcs1.pem"
    );

    assert_eq!(pem::read_blocks(key).expect("blocks").len(), 1);
    let end = key
        .windows(9)
        .rposition(|w| w == b"-----END ")
        .expect("END line");
    assert!(pem::read_blocks(&key[..end]).is_err());
    assert!(pair("keys/rsa.pem", "keys/rsa-pkcs1.pem").is_ok());
    assert_eq!(hits(), control, "a freed block holds key text");
}
