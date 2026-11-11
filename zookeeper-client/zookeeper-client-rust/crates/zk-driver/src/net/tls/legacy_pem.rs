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

//! Decryption engine for traditional OpenSSL-encrypted PEM private keys (RFC 1421 / RFC 1423).
//!
//! Private key encapsulation blocks carrying `Proc-Type: 4,ENCRYPTED` and `DEK-Info` headers
//! are decrypted using the legacy OpenSSL key derivation function `EVP_BytesToKey` (MD5 hash,
//! single iteration, salt derived from the initial 8 bytes of the initialization vector). The
//! derived key material feeds into block cipher decryption (supporting AES-128/192/256-CBC,
//! DES-EDE3-CBC, or DES-CBC), followed by strict PKCS#7 padding validation and removal. Both
//! derived cryptographic keys and decrypted plaintext payloads reside in zeroizing buffers to
//! prevent leakage of secret material. An invalid passphrase manifests downstream as a PKCS#7
//! padding failure or corrupted ASN.1 DER structure.
//!
//! Header ordering and validation follow OpenSSL `PEM_get_EVP_CIPHER_INFO` conventions:
//! - The initial header must be `Proc-Type: 4,ENCRYPTED`.
//! - The subsequent header must be `DEK-Info`, specifying a supported cipher and hex-encoded IV.
//! - The IV hex string must contain exactly two hexadecimal digits per cipher block byte.
//!
//! The underlying CBC decryption routine (`cbc_decrypt`) is also leveraged for PBES2 key decryption
//! in `identity.rs`. This module is excluded from FIPS builds where non-approved algorithms (such
//! as MD5 and DES) are disabled.

#![cfg(feature = "_legacy-keys")]

use cipher::block_padding::Pkcs7;
use cipher::{BlockCipher, BlockDecryptMut, KeyInit, KeyIvInit};
use der::zeroize::Zeroizing;
use md5::digest::Output;
use md5::{Digest, Md5};
use zk_proto::SecretBytes;

use crate::net::tls::identity::KeyError;
use crate::net::tls::pem::PemBlock;

/// Supported symmetric block ciphers recognized in `DEK-Info` encapsulation headers.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DekCipher {
    /// AES-128 in CBC mode (16-byte key, 16-byte IV/block size).
    Aes128Cbc,
    /// AES-192 in CBC mode (24-byte key, 16-byte IV/block size).
    Aes192Cbc,
    /// AES-256 in CBC mode (32-byte key, 16-byte IV/block size).
    Aes256Cbc,
    /// Triple-DES (EDE3) in CBC mode (24-byte key, 8-byte IV/block size).
    DesEde3Cbc,
    /// Single-DES in CBC mode (8-byte key, 8-byte IV/block size).
    DesCbc,
}

impl DekCipher {
    /// Resolves a cipher configuration from its OpenSSL algorithm name, case-insensitively
    /// (`EVP_get_cipherbyname`).
    #[must_use]
    pub fn from_name(name: &str) -> Option<Self> {
        [
            ("AES-128-CBC", Self::Aes128Cbc),
            ("AES-192-CBC", Self::Aes192Cbc),
            ("AES-256-CBC", Self::Aes256Cbc),
            ("DES-EDE3-CBC", Self::DesEde3Cbc),
            ("DES-CBC", Self::DesCbc),
        ]
        .into_iter()
        .find_map(|(n, c)| n.eq_ignore_ascii_case(name).then_some(c))
    }

    /// Returns the required symmetric key length in bytes.
    #[must_use]
    pub fn key_len(self) -> usize {
        match self {
            Self::Aes128Cbc => 16,
            Self::Aes192Cbc | Self::DesEde3Cbc => 24,
            Self::Aes256Cbc => 32,
            Self::DesCbc => 8,
        }
    }

    /// Returns the initialization vector length in bytes (matching cipher block size).
    #[must_use]
    pub fn iv_len(self) -> usize {
        match self {
            Self::Aes128Cbc | Self::Aes192Cbc | Self::Aes256Cbc => 16,
            Self::DesEde3Cbc | Self::DesCbc => 8,
        }
    }
}

/// Checks whether a PEM block specifies traditional OpenSSL encryption via
/// `Proc-Type: 4,ENCRYPTED`.
#[must_use]
pub fn is_encrypted(block: &PemBlock) -> bool {
    block
        .headers
        .iter()
        .any(|(k, v)| k == "Proc-Type" && proc_type_encrypted(v))
}

/// Decrypts a traditional OpenSSL-encrypted PEM key block into its underlying unencrypted DER
/// representation.
///
/// Supports PKCS#1, SEC1, or PKCS#8 private keys encrypted using traditional encapsulation headers.
///
/// # Errors
///
/// Returns [`KeyError::Rejected`] for malformed encapsulation headers or unsupported ciphers;
/// returns [`KeyError::BadPassphrase`] on PKCS#7 padding validation failure.
pub fn decrypt(block: &PemBlock, passwd: &[u8]) -> Result<SecretBytes, KeyError> {
    let (cipher, iv) = dek_info(&block.headers)?;
    let salt = iv.get(..8).ok_or(KeyError::Rejected)?;
    let key = bytes_to_key(passwd, salt, cipher.key_len());
    let mut buf = block.body.expose().to_vec();
    match cbc_decrypt(cipher, &key, &iv, &mut buf) {
        Ok(len) => {
            buf.truncate(len);
            Ok(SecretBytes::new(buf))
        }
        Err(e) => {
            drop(SecretBytes::new(buf));
            Err(e)
        }
    }
}

/// Decrypts a mutable buffer in place using the specified block cipher in CBC mode with strict
/// PKCS#7 unpadding.
///
/// Returns the length of valid plaintext after padding removal.
///
/// # Errors
///
/// Returns [`KeyError::Rejected`] on key or IV length mismatches; returns
/// [`KeyError::BadPassphrase`] on padding validation failure.
pub(crate) fn cbc_decrypt(
    cipher: DekCipher,
    key: &[u8],
    iv: &[u8],
    buf: &mut [u8],
) -> Result<usize, KeyError> {
    match cipher {
        DekCipher::Aes128Cbc => cbc_with::<aes::Aes128>(key, iv, buf),
        DekCipher::Aes192Cbc => cbc_with::<aes::Aes192>(key, iv, buf),
        DekCipher::Aes256Cbc => cbc_with::<aes::Aes256>(key, iv, buf),
        DekCipher::DesEde3Cbc => cbc_with::<des::TdesEde3>(key, iv, buf),
        DekCipher::DesCbc => cbc_with::<des::Des>(key, iv, buf),
    }
}

/// Executes CBC mode block decryption and unpadding for a concrete cipher type.
fn cbc_with<C>(key: &[u8], iv: &[u8], buf: &mut [u8]) -> Result<usize, KeyError>
where
    C: BlockCipher + BlockDecryptMut + KeyInit,
{
    let dec = cbc::Decryptor::<C>::new_from_slices(key, iv).map_err(|_| KeyError::Rejected)?;
    dec.decrypt_padded_mut::<Pkcs7>(buf)
        .map(<[u8]>::len)
        .map_err(|_| KeyError::BadPassphrase)
}

/// Verifies whether a `Proc-Type` header value matches `4,ENCRYPTED`, permitting whitespace after
/// commas.
fn proc_type_encrypted(value: &str) -> bool {
    value
        .trim()
        .strip_prefix("4,")
        .is_some_and(|v| v.trim_start() == "ENCRYPTED")
}

/// Extracts and validates the cipher algorithm and decoded initialization vector from encapsulation
/// headers.
fn dek_info(headers: &[(String, String)]) -> Result<(DekCipher, Vec<u8>), KeyError> {
    let mut it = headers.iter();
    match it.next() {
        Some((k, v)) if k == "Proc-Type" && proc_type_encrypted(v) => {}
        _ => return Err(KeyError::Rejected),
    }
    let info = match it.next() {
        Some((k, v)) if k == "DEK-Info" => v.trim(),
        _ => return Err(KeyError::Rejected),
    };
    let (name, iv) = info.split_once(',').ok_or(KeyError::Rejected)?;
    let cipher = DekCipher::from_name(name.trim()).ok_or(KeyError::Rejected)?;
    let iv = hex(iv.trim(), cipher.iv_len()).ok_or(KeyError::Rejected)?;
    Ok((cipher, iv))
}

/// Decodes an exact count of hexadecimal bytes, accepting both uppercase and lowercase hex digits.
fn hex(text: &str, len: usize) -> Option<Vec<u8>> {
    if text.len() != len.checked_mul(2)? {
        return None;
    }
    text.as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let mut byte = 0u8;
            for &c in pair {
                let nibble = u8::try_from(char::from(c).to_digit(16)?).ok()?;
                byte = byte.checked_mul(16)?.checked_add(nibble)?;
            }
            Some(byte)
        })
        .collect()
}

/// Derives symmetric encryption key material using legacy OpenSSL `EVP_BytesToKey` (MD5 digest,
/// single iteration).
///
/// Computes intermediate digests `D1 = MD5(pass || salt)` and `D2 = MD5(D1 || pass || salt)`,
/// concatenating them to yield the required key bytes.
fn bytes_to_key(pass: &[u8], salt: &[u8], key_len: usize) -> Zeroizing<Vec<u8>> {
    let mut out = Zeroizing::new(vec![0u8; 32]);
    let (d1, d2) = out.split_at_mut(16);
    let mut h = Md5::new();
    h.update(pass);
    h.update(salt);
    h.finalize_into(Output::<Md5>::from_mut_slice(d1));
    if key_len > 16 {
        let mut h = Md5::new();
        h.update(&*d1);
        h.update(pass);
        h.update(salt);
        h.finalize_into(Output::<Md5>::from_mut_slice(d2));
    }
    out.truncate(key_len);
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::indexing_slicing)]
mod tests {
    use super::*;

    fn headers(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect()
    }

    #[test]
    fn bytes_to_key_matches_openssl() {
        // Output verified against:
        // openssl enc -aes-256-cbc -P -md md5 -pass pass:pw -S 0102030405060708
        let want = hex(
            "222776D258E716EFDAA0CF5A7E504B9D21982575418A44BE8D93693294778EEA",
            32,
        )
        .unwrap();
        let salt = [1, 2, 3, 4, 5, 6, 7, 8];
        assert_eq!(&*bytes_to_key(b"pw", &salt, 32), &want[..]);
        assert_eq!(&*bytes_to_key(b"pw", &salt, 24), &want[..24]);
        assert_eq!(&*bytes_to_key(b"pw", &salt, 16), &want[..16]);
        assert_eq!(&*bytes_to_key(b"pw", &salt, 8), &want[..8]);
    }

    #[test]
    fn dek_info_follows_the_openssl_header_rule() {
        let iv16 = "000102030405060708090A0B0C0D0E0F";
        let (c, iv) = dek_info(&headers(&[
            ("Proc-Type", "4,ENCRYPTED"),
            ("DEK-Info", &format!("aes-128-cbc,{}", iv16.to_lowercase())),
        ]))
        .unwrap();
        assert_eq!(c, DekCipher::Aes128Cbc);
        assert_eq!(iv, (0u8..16).collect::<Vec<_>>());
        let ok = dek_info(&headers(&[
            ("Proc-Type", "4, ENCRYPTED "),
            ("DEK-Info", "DES-EDE3-CBC,0001020304050607"),
        ]));
        assert_eq!(ok.unwrap().0, DekCipher::DesEde3Cbc);
        for bad in [
            vec![("DEK-Info", "AES-128-CBC,00")],
            vec![("Proc-Type", "4,ENCRYPTED")],
            vec![
                ("Proc-Type", "4 ,ENCRYPTED"),
                ("DEK-Info", "DES-CBC,0001020304050607"),
            ],
            vec![
                ("Proc-Type", "4,MIC-ONLY"),
                ("DEK-Info", "DES-CBC,0001020304050607"),
            ],
            vec![
                ("Proc-Type", "4,ENCRYPTED"),
                ("X", "y"),
                ("DEK-Info", "DES-CBC,0001020304050607"),
            ],
            vec![("Proc-Type", "4,ENCRYPTED"), ("DEK-Info", "DES-CBC")],
            vec![
                ("Proc-Type", "4,ENCRYPTED"),
                ("DEK-Info", "DES-CBC,00010203040506"),
            ],
            vec![
                ("Proc-Type", "4,ENCRYPTED"),
                ("DEK-Info", "DES-CBC,000102030405060708"),
            ],
            vec![
                ("Proc-Type", "4,ENCRYPTED"),
                ("DEK-Info", "DES-CBC,00010203040506zz"),
            ],
            vec![
                ("Proc-Type", "4,ENCRYPTED"),
                ("DEK-Info", "AES-128-CFB,000102030405060708090A0B0C0D0E0F"),
            ],
            vec![
                ("Proc-Type", "4,ENCRYPTED"),
                ("DEK-Info", "DES-CBC,0001020304050\u{e9}"),
            ],
        ] {
            assert_eq!(dek_info(&headers(&bad)), Err(KeyError::Rejected), "{bad:?}");
        }
    }

    #[test]
    fn cbc_needs_strict_padding_and_whole_blocks() {
        let key = [7u8; 16];
        let iv = [9u8; 16];
        for len in [0usize, 15, 17] {
            let mut buf = vec![0u8; len];
            assert_eq!(
                cbc_decrypt(DekCipher::Aes128Cbc, &key, &iv, &mut buf),
                Err(KeyError::BadPassphrase)
            );
        }
        let mut buf = vec![0u8; 16];
        assert_eq!(
            cbc_decrypt(DekCipher::Aes128Cbc, &key, &iv[..8], &mut buf),
            Err(KeyError::Rejected)
        );
        assert_eq!(
            cbc_decrypt(DekCipher::Aes256Cbc, &key, &iv, &mut buf),
            Err(KeyError::Rejected)
        );
    }
}
