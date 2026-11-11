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

//! Fuzzes the private key decoders and client identity loader in the drop-in TLS layer.
//!
//! Each fuzz input consists of a passphrase and a key file image. The harness attempts decryption
//! of both traditional OpenSSL `DEK-Info` encrypted PEM blocks and PKCS#8 encrypted private keys,
//! and then exercises full client `Identity::load` parsing using the image as both certificate and
//! key file.
//!
//! While production code bounds scrypt memory usage, it permits arbitrary PBKDF2 iteration counts
//! because key files are local configuration artifacts. To preserve fuzzing throughput, the
//! harness inspects PBES2 parameters upfront and skips key derivation when PBKDF2 or scrypt work
//! factors exceed fuzzing thresholds.

#![no_main]
#![forbid(unsafe_code)]

use std::path::PathBuf;
use std::sync::OnceLock;

use libfuzzer_sys::fuzz_target;
use pkcs5::pbes2::Kdf;
use pkcs8::EncryptedPrivateKeyInfo;
use zk_driver::net::tls::identity::{self, Identity, MAX_PASSPHRASE};
use zk_driver::net::tls::pem::{self, PemKind};
use zk_driver::net::tls::{LegacyPath, default_provider, legacy_pem};
use zk_fuzz::Cursor;
use zk_proto::SecretBytes;

/// Upper bound on PBKDF2 iteration count executed during fuzzing.
const MAX_ITERATIONS: u32 = 2048;
/// Upper bound on scrypt work factor (`N * r * p`) executed during fuzzing.
const MAX_SCRYPT_WORK: u64 = 1 << 13;

/// Returns `true` if `der` either fails ASN.1 parsing or specifies PBES2 KDF parameters within the
/// fuzzing cost budget.
fn cheap(der: &[u8]) -> bool {
    let Ok(info) = EncryptedPrivateKeyInfo::try_from(der) else {
        return true;
    };
    let Some(params) = info.encryption_algorithm.pbes2() else {
        return true;
    };
    match &params.kdf {
        Kdf::Pbkdf2(p) => p.iteration_count <= MAX_ITERATIONS,
        Kdf::Scrypt(p) => {
            let work = p
                .cost_parameter
                .checked_mul(u64::from(p.block_size))
                .and_then(|w| w.checked_mul(u64::from(p.parallelization)));
            work.is_some_and(|w| w <= MAX_SCRYPT_WORK)
        }
        _ => true,
    }
}

/// Validates PKCS#8 PBES2 parameters and, when within the cost budget, runs `decrypt_pkcs8`,
/// verifying consistency between parameter pre-checks and full decryption.
fn pkcs8(der: &[u8], pass: &[u8]) {
    let pass = pass.get(..MAX_PASSPHRASE).unwrap_or(pass);
    match identity::check_pbes2_params(der) {
        Err(e) => {
            let got = identity::decrypt_pkcs8(der, pass).err();
            assert_eq!(got, Some(e), "check_pbes2_params and decrypt_pkcs8 disagreed");
        }
        Ok(()) if cheap(der) => {
            if let Ok(plain) = identity::decrypt_pkcs8(der, pass) {
                assert!(
                    plain.expose().len() <= der.len(),
                    "decrypted PKCS#8 plaintext exceeded DER input length"
                );
            }
        }
        Ok(()) => {}
    }
}

/// Returns a process-scoped temporary file path for `Identity::load` testing.
fn scratch() -> &'static PathBuf {
    static F: OnceLock<PathBuf> = OnceLock::new();
    F.get_or_init(|| {
        std::env::temp_dir().join(format!("zk-fuzz-tls-keys-{}.pem", std::process::id()))
    })
}

fuzz_target!(|data: &[u8]| {
    let mut c = Cursor::new(data);
    let pass = c.lp();
    let image = c.rest();

    // Evaluate the raw input slice as a standalone PKCS#8 DER structure.
    pkcs8(image, pass);

    let mut all_cheap = cheap(image);
    if let Ok(blocks) = pem::read_blocks(image) {
        for block in &blocks {
            let body = block.body.expose();
            if legacy_pem::is_encrypted(block)
                && let Ok(plain) = legacy_pem::decrypt(block, pass)
            {
                assert!(
                    plain.expose().len() <= body.len(),
                    "decrypted legacy PEM plaintext exceeded ciphertext body length"
                );
            }
            if pem::classify_label(&block.label) == PemKind::EncryptedPkcs8Key {
                all_cheap &= cheap(body);
                pkcs8(body, pass);
            }
        }
    }

    // Exercise the full file-based Identity loader if all contained keys have bounded KDF cost.
    if !all_cheap {
        return;
    }
    let path = scratch();
    if std::fs::write(path, image).is_ok() {
        let p = LegacyPath::new(path.as_os_str().as_encoded_bytes().to_vec());
        let secret = SecretBytes::new(pass.to_vec());
        let provider = default_provider();
        if let Ok(id) = Identity::load(&p, &p, &secret, &provider) {
            assert!(
                !id.certified_key().cert.is_empty(),
                "loaded Identity must contain at least one certificate"
            );
        }
        // Unlink the temporary key file before returning.
        let _ = std::fs::remove_file(path);
    }
});
