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

//! Client certificate chain resolution and private key loading.
//!
//! To match the legacy C driver's authentication semantics, [`Identity::load`] executes
//! the three identity processing stages of `init_ssl_for_socket` in legacy order:
//! `ClientCertChain`, `ClientKey`, and `CheckPrivateKey`. This workflow is triggered whenever
//! client certificate, key, and passphrase parameters are supplied.
//!
//! - **Certificate Chain Processing**: The first parsed `CERTIFICATE`, `X509 CERTIFICATE`, or
//!   `TRUSTED CERTIFICATE` PEM block establishes the leaf certificate (stripping auxiliary trust
//!   data when present). Subsequent `CERTIFICATE` and `X509 CERTIFICATE` blocks constitute
//!   intermediate chain elements in lexical file order. Because Rustls defers structural validation
//!   of the client's own credentials until peer negotiation, X.509 v1 leaf certificates remain
//!   supported.
//! - **Private Key Ingestion**: Selects the initial private key PEM block encountered. Encrypted
//!   PKCS#8 containers utilize PBES2 key derivation following validation via
//!   [`check_pbes2_params`]. Traditional legacy key blocks bearing RFC 1421 headers are routed to
//!   `legacy_pem`, which strictly accepts `Proc-Type: 4,ENCRYPTED` with corresponding `DEK-Info`
//!   attributes.
//! - **OpenSSL Compatibility Slot Rules**: Governs whether key mismatch conditions trigger failure
//!   at the `ClientKey` stage (matching cryptographic family but mismatched key material) or at
//!   `CheckPrivateKey` (incompatible key algorithm types). Leaf SubjectPublicKeyInfo (SPKI)
//!   structures are extracted via bounded DER traversal, ensuring consistent enforcement for v1
//!   leaves.
//!
//! To prevent sensitive key material from lingering in process memory, this module manages PBES2
//! key derivation directly rather than delegating to `EncryptedPrivateKeyInfo::decrypt` (which
//! lacks zeroization guarantees in underlying crates). Derived keys and decrypted private key bytes
//! reside exclusively in zeroizing buffers.
//!
//! In FIPS-compliant builds, key derivation is restricted to PBES2 with PBKDF2-HMAC (using SHA-256,
//! SHA-384, or SHA-512) and AES-CBC ciphers, executed inside the cryptographic boundary via
//! `aws_lc_rs::pbkdf2` and `aws_lc_rs::cipher`. Support for legacy PEM formats and non-FIPS
//! primitives is omitted entirely.
//!
//! All secret key representations remain wrapped in [`SecretBytes`] or zeroizing memory wrappers
//! until consumed by the active cryptographic provider.

use std::sync::Arc;

use der::zeroize::Zeroizing;
use pkcs8::EncryptedPrivateKeyInfo;
use pkcs8::pkcs5::pbes2::{self, Kdf, Pbkdf2Prf};
use rustls::SignatureAlgorithm;
use rustls::SignatureScheme;
use rustls::client::ResolvesClientCert;
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{
    CertificateDer, PrivateKeyDer, PrivatePkcs1KeyDer, PrivatePkcs8KeyDer, PrivateSec1KeyDer,
};
use rustls::sign::{CertifiedKey, SigningKey};
use zk_proto::SecretBytes;

use crate::net::tls::connector::{LegacyPath, read_path};
#[cfg(feature = "_legacy-keys")]
use crate::net::tls::legacy_pem::{self, DekCipher};
use crate::net::tls::pem::{self, PemBlock, PemKind};
use crate::net::tls::{TlsConfigStep, TlsSetupError};

/// Maximum allowable passphrase length matching OpenSSL's internal `PEM_BUFSIZE` (1024 bytes).
pub const MAX_PASSPHRASE: usize = 1024;

/// Upper bound on PBKDF2 iteration counts to prevent adversarial key files from stalling the
/// connection path.
pub const PBKDF2_MAX_ITER: u32 = 10_000_000;

/// Upper memory threshold for scrypt key derivation, aligning with OpenSSL's `SCRYPT_MAX_MEM`
/// (32 MiB).
pub const SCRYPT_MAX_MEM: u64 = 32 * 1024 * 1024;

/// Specific failure modes encountered during private key ingestion and decryption.
///
/// The connector maps all variants of this error to the `ClientKey` configuration step.
/// Discrepancies where the private key does not match the leaf certificate are classified
/// separately under `CheckPrivateKey`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum KeyError {
    /// Passphrase failed to decrypt key data due to invalid padding or corrupt DER formatting.
    BadPassphrase,
    /// Key encoding, encryption cipher, PRF, or public key algorithm is unsupported or rejected.
    Rejected,
    /// PBES2 or scrypt parameters exceed configured memory or iteration bounds.
    Bounds,
}

/// Validated client identity credentials comprising a public certificate chain and signing key.
#[derive(Debug)]
pub struct Identity {
    key: Arc<CertifiedKey>,
}

impl Identity {
    /// Loads client identity credentials across the sequential stages `ClientCertChain`,
    /// `ClientKey`, and `CheckPrivateKey`.
    ///
    /// Certificate and key files are re-read from disk on every invocation. The provided passphrase
    /// is capped at [`MAX_PASSPHRASE`] bytes and ignored if the private key is unencrypted. The
    /// signing key is parsed via the active cryptographic provider by reference to ensure
    /// zeroization on drop.
    ///
    /// # Errors
    ///
    /// Returns [`TlsSetupError::Config`] tagging the precise configuration step that encountered an
    /// error.
    pub fn load(
        cert: &LegacyPath,
        key: &LegacyPath,
        passwd: &SecretBytes,
        _provider: &CryptoProvider,
    ) -> Result<Self, TlsSetupError> {
        let chain = load_chain(cert).ok_or(config(TlsConfigStep::ClientCertChain))?;
        let signer = load_key(key, passwd).map_err(|_| config(TlsConfigStep::ClientKey))?;
        check_pair(&chain, signer.as_ref())?;
        Ok(Self {
            key: Arc::new(CertifiedKey::new(chain, signer)),
        })
    }

    /// Returns a reference to the shared certified key pair.
    #[must_use]
    pub fn certified_key(&self) -> &Arc<CertifiedKey> {
        &self.key
    }
}

/// Drop-in client certificate resolver emulating OpenSSL client authentication behavior.
///
/// Resolves the preconfigured signing key whenever it supports any of the peer's requested
/// signature schemes. Ignores server CA root hint subjects, mirroring OpenSSL's behavior of
/// transmitting configured certificates unconditionally.
#[derive(Debug)]
pub struct LegacyClientCert(pub Arc<CertifiedKey>);

impl ResolvesClientCert for LegacyClientCert {
    fn resolve(
        &self,
        _root_hint_subjects: &[&[u8]],
        sigschemes: &[SignatureScheme],
    ) -> Option<Arc<CertifiedKey>> {
        self.0
            .key
            .choose_scheme(sigschemes)
            .map(|_| Arc::clone(&self.0))
    }

    fn has_certs(&self) -> bool {
        true
    }
}

/// Decrypts an `ENCRYPTED PRIVATE KEY` PKCS#8 container into plaintext DER format.
///
/// Enforces parameter limits via [`check_pbes2_params`] prior to deriving keys. Decryption is
/// performed in zeroizing buffers to prevent leaking sensitive plaintext key bytes.
///
/// # Errors
///
/// Returns [`KeyError::Rejected`] for unsupported cryptographic algorithms, [`KeyError::Bounds`]
/// if resource bounds are exceeded, or [`KeyError::BadPassphrase`] if decryption padding checks
/// fail.
pub fn decrypt_pkcs8(der: &[u8], passwd: &[u8]) -> Result<SecretBytes, KeyError> {
    let info = EncryptedPrivateKeyInfo::try_from(der).map_err(|_| KeyError::Rejected)?;
    let params = info
        .encryption_algorithm
        .pbes2()
        .ok_or(KeyError::Rejected)?;
    check_params(params)?;
    let mut buf = info.encrypted_data.to_vec();
    match decrypt_pbes2(params, passwd, &mut buf) {
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

/// Validates key derivation parameters against denial-of-service bounds prior to executing crypto
/// operations.
///
/// For scrypt derivations, ensures total memory consumption `128·r·(N + 2) + 128·r·p` remains
/// within [`SCRYPT_MAX_MEM`], with `p·r < 2^30` and `N` a non-trivial power of two. For PBKDF2
/// derivations, verifies accepted PRF algorithms and iteration counts from 1 to
/// [`PBKDF2_MAX_ITER`]. Confirms explicit key length specifications match the selected cipher.
///
/// # Errors
///
/// Returns [`KeyError::Bounds`] or [`KeyError::Rejected`].
pub fn check_pbes2_params(der: &[u8]) -> Result<(), KeyError> {
    let info = EncryptedPrivateKeyInfo::try_from(der).map_err(|_| KeyError::Rejected)?;
    let params = info
        .encryption_algorithm
        .pbes2()
        .ok_or(KeyError::Rejected)?;
    check_params(params)
}

/// Convenience helper generating a configuration step failure.
fn config(step: TlsConfigStep) -> TlsSetupError {
    TlsSetupError::Config { step }
}

/// Ingests certificate chains from disk according to OpenSSL `SSL_CTX_use_certificate_chain_file`
/// semantics.
///
/// Requires a regular file within the shared size bound, retaining raw file contents in a
/// [`SecretBytes`] buffer because combined PEM bundles may also include private key material.
fn load_chain(cert: &LegacyPath) -> Option<Vec<CertificateDer<'static>>> {
    let text = SecretBytes::new(read_path(cert)?);
    let blocks = pem::read_blocks(text.expose()).ok()?;
    let mut chain: Vec<CertificateDer<'static>> = Vec::new();
    for block in &blocks {
        let der = match pem::classify_label(&block.label) {
            PemKind::Certificate => block.body.expose(),
            // Only the leaf certificate may originate from a TRUSTED CERTIFICATE block;
            // trailing auxiliary certificate trust data is discarded.
            PemKind::TrustedCertificate if chain.is_empty() => der_tlv(block.body.expose())?.full,
            _ => continue,
        };
        if !pem::der_is_certificate(der) {
            return None;
        }
        chain.push(CertificateDer::from(der.to_vec()));
    }
    if chain.is_empty() { None } else { Some(chain) }
}

/// Supported DER representations for private key structures.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum KeyForm {
    /// Standard PKCS#8 `PrivateKeyInfo`.
    Pkcs8,
    /// Traditional PKCS#1 `RSAPrivateKey`.
    Pkcs1,
    /// Traditional SEC1 `ECPrivateKey`.
    Sec1,
}

/// Ingests private key material in accordance with `SSL_CTX_use_PrivateKey_file` semantics,
/// parsing the first encountered key block and decrypting when encrypted.
fn load_key(key: &LegacyPath, passwd: &SecretBytes) -> Result<Arc<dyn SigningKey>, KeyError> {
    let text = SecretBytes::new(read_path(key).ok_or(KeyError::Rejected)?);
    let blocks = pem::read_blocks(text.expose()).map_err(|_| KeyError::Rejected)?;
    drop(text);
    let block = blocks
        .iter()
        .find(|b| is_key_kind(pem::classify_label(&b.label)))
        .ok_or(KeyError::Rejected)?;
    let pass = passwd.expose();
    let pass = pass.get(..MAX_PASSPHRASE).unwrap_or(pass);
    let (form, der) = decode_key(block, pass)?;
    signing_key(form, &der).ok_or(KeyError::Rejected)
}

/// Identifies whether a parsed PEM label represents private key material.
fn is_key_kind(kind: PemKind) -> bool {
    matches!(
        kind,
        PemKind::Pkcs8Key
            | PemKind::RsaKey
            | PemKind::EcKey
            | PemKind::EncryptedPkcs8Key
            | PemKind::DsaKey
    )
}

/// Decodes an individual PEM block into its raw DER representation and key format.
///
/// Traditional encrypted keys (characterized by RFC 1421 encapsulation headers) are parsed
/// and decrypted unless running under FIPS mode. `ENCRYPTED PRIVATE KEY` structures are decrypted
/// using PBES2.
fn decode_key(block: &PemBlock, pass: &[u8]) -> Result<(KeyForm, SecretBytes), KeyError> {
    let form = match pem::classify_label(&block.label) {
        PemKind::Pkcs8Key | PemKind::EncryptedPkcs8Key => KeyForm::Pkcs8,
        PemKind::RsaKey => KeyForm::Pkcs1,
        PemKind::EcKey => KeyForm::Sec1,
        // Legacy DSA and unrecognized key algorithms are rejected.
        _ => return Err(KeyError::Rejected),
    };
    let body = if block.headers.is_empty() {
        block.body.duplicate()
    } else {
        traditional(block, pass)?
    };
    if pem::classify_label(&block.label) == PemKind::EncryptedPkcs8Key {
        Ok((form, decrypt_pkcs8(body.expose(), pass)?))
    } else {
        Ok((form, body))
    }
}

/// Decrypts private key material using legacy RFC 1421 PEM encryption.
#[cfg(feature = "_legacy-keys")]
fn traditional(block: &PemBlock, pass: &[u8]) -> Result<SecretBytes, KeyError> {
    legacy_pem::decrypt(block, pass)
}

/// Stub rejecting legacy RFC 1421 PEM encryption under FIPS configurations.
#[cfg(not(feature = "_legacy-keys"))]
fn traditional(_block: &PemBlock, _pass: &[u8]) -> Result<SecretBytes, KeyError> {
    Err(KeyError::Rejected)
}

/// Instantiates a signing key implementation from protected memory bytes.
///
/// Passes key DER by reference to prevent unmanaged heap duplication.
fn signing_key(form: KeyForm, der: &SecretBytes) -> Option<Arc<dyn SigningKey>> {
    let key = match form {
        KeyForm::Pkcs8 => PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(der.expose())),
        KeyForm::Pkcs1 => PrivateKeyDer::Pkcs1(PrivatePkcs1KeyDer::from(der.expose())),
        KeyForm::Sec1 => PrivateKeyDer::Sec1(PrivateSec1KeyDer::from(der.expose())),
    };
    #[cfg(feature = "tls-ring")]
    let signer = rustls::crypto::ring::sign::any_supported_type(&key);
    #[cfg(all(
        not(feature = "tls-ring"),
        any(feature = "tls-aws-lc-rs", feature = "tls-aws-lc-rs-fips")
    ))]
    let signer = rustls::crypto::aws_lc_rs::sign::any_supported_type(&key);
    signer.ok()
}

/// Cryptographic algorithm slot categorizations aligning with OpenSSL key dispatch.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Slot {
    /// RSA encryption (`rsaEncryption`).
    Rsa,
    /// RSA Probabilistic Signature Scheme (`id-RSASSA-PSS`).
    RsaPss,
    /// Elliptic curve public key (`id-ecPublicKey`).
    Ecc,
    /// Edwards-curve 25519 (`id-Ed25519`).
    Ed25519,
    /// Edwards-curve 448 (`id-Ed448`).
    Ed448,
}

/// Object identifier for `rsaEncryption` (1.2.840.113549.1.1.1).
const OID_RSA: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01];
/// Object identifier for `id-RSASSA-PSS` (1.2.840.113549.1.1.10).
const OID_RSA_PSS: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0a];
/// Object identifier for `id-ecPublicKey` (1.2.840.10045.2.1).
const OID_EC: &[u8] = &[0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
/// Object identifier for `id-Ed25519` (1.3.101.112).
const OID_ED25519: &[u8] = &[0x2b, 0x65, 0x70];
/// Object identifier for `id-Ed448` (1.3.101.113).
const OID_ED448: &[u8] = &[0x2b, 0x65, 0x71];
/// Canonical DER encoding of ASN.1 NULL.
const DER_NULL: &[u8] = &[0x05, 0x00];

/// Maps an SPKI algorithm object identifier to its corresponding cryptographic slot.
fn slot_of_oid(oid: &[u8]) -> Option<Slot> {
    match oid {
        OID_RSA => Some(Slot::Rsa),
        OID_RSA_PSS => Some(Slot::RsaPss),
        OID_EC => Some(Slot::Ecc),
        OID_ED25519 => Some(Slot::Ed25519),
        OID_ED448 => Some(Slot::Ed448),
        _ => None,
    }
}

/// Maps a Rustls signature algorithm to its corresponding cryptographic slot.
fn slot_of_key(alg: SignatureAlgorithm) -> Option<Slot> {
    match alg {
        SignatureAlgorithm::RSA => Some(Slot::Rsa),
        SignatureAlgorithm::ECDSA => Some(Slot::Ecc),
        SignatureAlgorithm::ED25519 => Some(Slot::Ed25519),
        SignatureAlgorithm::ED448 => Some(Slot::Ed448),
        _ => None,
    }
}

/// Validates that the client private key matches the leaf certificate using OpenSSL slot dispatch
/// rules.
///
/// In OpenSSL, attempting to bind a private key with matching algorithm slot but mismatched key
/// material fails during key attachment (`ClientKey` / `ssl_set_pkey`), whereas binding an entirely
/// mismatched algorithm slot fails during private key consistency checking (`CheckPrivateKey`).
/// Keys that do not expose public key bytes bypass verification.
fn check_pair(
    chain: &[CertificateDer<'static>],
    key: &dyn SigningKey,
) -> Result<(), TlsSetupError> {
    let Some(key_spki) = key.public_key() else {
        return Ok(());
    };
    let leaf = chain
        .first()
        .and_then(|c| leaf_spki(c.as_ref()))
        .and_then(spki_parts);
    let Some(leaf) = leaf else {
        // Failed to extract the leaf certificate's public key; report validation failure.
        return Err(config(TlsConfigStep::CheckPrivateKey));
    };
    let leaf_slot = slot_of_oid(leaf.oid);
    if leaf_slot.is_none() || leaf_slot != slot_of_key(key.algorithm()) {
        return Err(config(TlsConfigStep::CheckPrivateKey));
    }
    match spki_parts(key_spki.as_ref()) {
        Some(k) if same_key(&leaf, &k) => Ok(()),
        _ => Err(config(TlsConfigStep::ClientKey)),
    }
}

/// Deconstructed elements of a DER `SubjectPublicKeyInfo` structure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct SpkiParts<'a> {
    /// Algorithm object identifier content bytes.
    oid: &'a [u8],
    /// Optional algorithm parameters encoded as complete TLV byte slices.
    params: &'a [u8],
    /// Public key BIT STRING content bytes.
    bits: &'a [u8],
}

/// Compares two public keys under OpenSSL equivalence semantics: identical algorithm OIDs, matching
/// parameters (treating ASN.1 NULL and omitted parameters as equivalent for RSA), and matching
/// public key bit representations. Compressed elliptic curve points are treated as distinct from
/// uncompressed representations.
fn same_key(a: &SpkiParts<'_>, b: &SpkiParts<'_>) -> bool {
    let norm = |p: &SpkiParts<'_>| -> Vec<u8> {
        if p.oid == OID_RSA && p.params == DER_NULL {
            Vec::new()
        } else {
            p.params.to_vec()
        }
    };
    a.oid == b.oid && norm(a) == norm(b) && a.bits == b.bits
}

/// Deconstructs an SPKI DER byte slice into its constituent algorithm OID, parameters, and public
/// key bit string.
fn spki_parts(spki: &[u8]) -> Option<SpkiParts<'_>> {
    let outer = der_tlv(spki)?;
    if outer.tag != 0x30 {
        return None;
    }
    let alg = der_tlv(outer.content)?;
    if alg.tag != 0x30 {
        return None;
    }
    let oid = der_tlv(alg.content)?;
    if oid.tag != 0x06 {
        return None;
    }
    let bits = der_tlv(alg.rest)?;
    if bits.tag != 0x03 {
        return None;
    }
    Some(SpkiParts {
        oid: oid.content,
        params: oid.rest,
        bits: bits.content,
    })
}

/// Extracts the `SubjectPublicKeyInfo` byte slice from an X.509 certificate.
///
/// Traverses `tbsCertificate` across both X.509 v1 (no version tag) and v3 formats, parsing past
/// serial number, signature algorithm, issuer, validity period, and subject fields.
fn leaf_spki(cert: &[u8]) -> Option<&[u8]> {
    let outer = der_tlv(cert)?;
    if outer.tag != 0x30 {
        return None;
    }
    let tbs = der_tlv(outer.content)?;
    if tbs.tag != 0x30 {
        return None;
    }
    let mut next = der_tlv(tbs.content)?;
    if next.tag == 0xa0 {
        next = der_tlv(next.rest)?;
    }
    // Traversal expects serial number INTEGER (0x02) followed by four SEQUENCE elements (0x30).
    let expect: [u8; 5] = [0x02, 0x30, 0x30, 0x30, 0x30];
    for tag in expect {
        if next.tag != tag {
            return None;
        }
        next = der_tlv(next.rest)?;
    }
    (next.tag == 0x30).then_some(next.full)
}

/// Parsed ASN.1 DER Tag-Length-Value element.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Tlv<'a> {
    /// DER tag identifier.
    tag: u8,
    /// Complete TLV byte slice including header and content.
    full: &'a [u8],
    /// Payload value content slice.
    content: &'a [u8],
    /// Trailing bytes immediately following this TLV element.
    rest: &'a [u8],
}

/// Parses a single ASN.1 DER TLV element with a single-byte tag and up to four length bytes,
/// enforcing strict bounds.
fn der_tlv(input: &[u8]) -> Option<Tlv<'_>> {
    let (&tag, after_tag) = input.split_first()?;
    if tag & 0x1f == 0x1f {
        return None;
    }
    let (&first, mut after) = after_tag.split_first()?;
    let len = if first < 0x80 {
        usize::from(first)
    } else {
        let count = usize::from(first & 0x7f);
        if count == 0 || count > 4 {
            return None;
        }
        let (bytes, rest) = after.split_at_checked(count)?;
        after = rest;
        bytes.iter().try_fold(0usize, |acc, &b| {
            acc.checked_mul(256)?.checked_add(usize::from(b))
        })?
    };
    let header = input.len().checked_sub(after.len())?;
    let (content, rest) = after.split_at_checked(len)?;
    let (full, _) = input.split_at_checked(header.checked_add(len)?)?;
    Some(Tlv {
        tag,
        full,
        content,
        rest,
    })
}

/// Determines whether the requested PBKDF2 pseudo-random function is permitted under the active
/// build profile.
fn prf_accepted(prf: Pbkdf2Prf) -> bool {
    #[cfg(feature = "_legacy-keys")]
    let ok = matches!(
        prf,
        Pbkdf2Prf::HmacWithSha1
            | Pbkdf2Prf::HmacWithSha224
            | Pbkdf2Prf::HmacWithSha256
            | Pbkdf2Prf::HmacWithSha384
            | Pbkdf2Prf::HmacWithSha512
    );
    #[cfg(not(feature = "_legacy-keys"))]
    let ok = matches!(
        prf,
        Pbkdf2Prf::HmacWithSha256 | Pbkdf2Prf::HmacWithSha384 | Pbkdf2Prf::HmacWithSha512
    );
    ok
}

/// Extracts the cipher key length for supported PBES2 encryption schemes.
fn cipher_key_len(enc: &pbes2::EncryptionScheme<'_>) -> Result<usize, KeyError> {
    match enc {
        pbes2::EncryptionScheme::Aes128Cbc { .. }
        | pbes2::EncryptionScheme::Aes192Cbc { .. }
        | pbes2::EncryptionScheme::Aes256Cbc { .. } => Ok(enc.key_size()),
        #[cfg(feature = "_legacy-keys")]
        pbes2::EncryptionScheme::DesEde3Cbc { .. } => Ok(enc.key_size()),
        _ => Err(KeyError::Rejected),
    }
}

/// Validates that any explicit `keyLength` parameter in PBES2 parameters matches the cipher key
/// length.
fn key_length_ok(explicit: Option<u16>, key_len: usize) -> Result<(), KeyError> {
    match explicit {
        Some(l) if usize::from(l) != key_len => Err(KeyError::Bounds),
        _ => Ok(()),
    }
}

/// Applies structural and parameter limit validation to parsed PBES2 parameters.
fn check_params(params: &pbes2::Parameters<'_>) -> Result<(), KeyError> {
    let key_len = cipher_key_len(&params.encryption)?;
    match &params.kdf {
        Kdf::Pbkdf2(k) => {
            if !prf_accepted(k.prf) {
                return Err(KeyError::Rejected);
            }
            if k.iteration_count == 0 || k.iteration_count > PBKDF2_MAX_ITER {
                return Err(KeyError::Bounds);
            }
            key_length_ok(k.key_length, key_len)
        }
        #[cfg(feature = "_legacy-keys")]
        Kdf::Scrypt(s) => {
            scrypt_bounds(s.cost_parameter, s.block_size, s.parallelization)?;
            key_length_ok(s.key_length, key_len)
        }
        // scrypt is rejected in FIPS builds, along with any unrecognized KDF.
        _ => Err(KeyError::Rejected),
    }
}

/// Validates scrypt parameter bounds against OpenSSL's `EVP_PBE_scrypt_ex` memory limit in checked
/// `u64` arithmetic.
///
/// Ensures compliance with RFC 7914 constraints (`N < 2^(16·r)`).
#[cfg(feature = "_legacy-keys")]
fn scrypt_bounds(n: u64, r: u16, p: u16) -> Result<(), KeyError> {
    if n < 2 || !n.is_power_of_two() || r == 0 || p == 0 {
        return Err(KeyError::Bounds);
    }
    let (r, p) = (u64::from(r), u64::from(p));
    let rp = r.checked_mul(p).ok_or(KeyError::Bounds)?;
    if rp >= 1 << 30 {
        return Err(KeyError::Bounds);
    }
    let log_n = u64::from(n.trailing_zeros());
    if log_n >= r.checked_mul(16).ok_or(KeyError::Bounds)? {
        return Err(KeyError::Bounds);
    }
    let r128 = r.checked_mul(128).ok_or(KeyError::Bounds)?;
    let v_len = n
        .checked_add(2)
        .and_then(|n2| r128.checked_mul(n2))
        .ok_or(KeyError::Bounds)?;
    let b_len = r128.checked_mul(p).ok_or(KeyError::Bounds)?;
    match v_len.checked_add(b_len) {
        Some(total) if total <= SCRYPT_MAX_MEM => Ok(()),
        _ => Err(KeyError::Bounds),
    }
}

/// Maps supported PBES2 encryption schemes to internal cipher identifiers and initialization
/// vectors.
#[cfg(feature = "_legacy-keys")]
fn pbes2_cipher<'a>(
    enc: &'a pbes2::EncryptionScheme<'_>,
) -> Result<(DekCipher, &'a [u8]), KeyError> {
    match enc {
        pbes2::EncryptionScheme::Aes128Cbc { iv } => Ok((DekCipher::Aes128Cbc, iv.as_slice())),
        pbes2::EncryptionScheme::Aes192Cbc { iv } => Ok((DekCipher::Aes192Cbc, iv.as_slice())),
        pbes2::EncryptionScheme::Aes256Cbc { iv } => Ok((DekCipher::Aes256Cbc, iv.as_slice())),
        pbes2::EncryptionScheme::DesEde3Cbc { iv } => Ok((DekCipher::DesEde3Cbc, iv.as_slice())),
        _ => Err(KeyError::Rejected),
    }
}

/// Derives the cryptographic key into a zeroizing buffer and decrypts ciphertext in place,
/// returning the plaintext length.
#[cfg(feature = "_legacy-keys")]
fn decrypt_pbes2(
    params: &pbes2::Parameters<'_>,
    pass: &[u8],
    buf: &mut [u8],
) -> Result<usize, KeyError> {
    let (cipher, iv) = pbes2_cipher(&params.encryption)?;
    let mut key = Zeroizing::new(vec![0u8; params.encryption.key_size()]);
    match &params.kdf {
        Kdf::Pbkdf2(k) => {
            let (salt, rounds) = (k.salt, k.iteration_count);
            match k.prf {
                Pbkdf2Prf::HmacWithSha1 => {
                    pbkdf2::pbkdf2_hmac::<sha1::Sha1>(pass, salt, rounds, &mut key);
                }
                Pbkdf2Prf::HmacWithSha224 => {
                    pbkdf2::pbkdf2_hmac::<sha2::Sha224>(pass, salt, rounds, &mut key);
                }
                Pbkdf2Prf::HmacWithSha256 => {
                    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(pass, salt, rounds, &mut key);
                }
                Pbkdf2Prf::HmacWithSha384 => {
                    pbkdf2::pbkdf2_hmac::<sha2::Sha384>(pass, salt, rounds, &mut key);
                }
                Pbkdf2Prf::HmacWithSha512 => {
                    pbkdf2::pbkdf2_hmac::<sha2::Sha512>(pass, salt, rounds, &mut key);
                }
                _ => return Err(KeyError::Rejected),
            }
        }
        Kdf::Scrypt(s) => {
            let log_n =
                u8::try_from(s.cost_parameter.trailing_zeros()).map_err(|_| KeyError::Bounds)?;
            let sp = scrypt::Params::new(
                log_n,
                u32::from(s.block_size),
                u32::from(s.parallelization),
                key.len(),
            )
            .map_err(|_| KeyError::Bounds)?;
            scrypt_zeroizing(pass, s.salt, &sp, &mut key)?;
        }
        _ => return Err(KeyError::Rejected),
    }
    legacy_pem::cbc_decrypt(cipher, &key, iv, buf)
}

/// Executes RFC 7914 scrypt key derivation utilizing zeroizing working buffers.
///
/// Standard upstream scrypt implementations release internal `B`, `V`, and `XY` scratch buffers
/// without zeroization. Because intermediate state contains passphrase derivations, this
/// implementation executes ROMix within zeroizing allocations.
#[cfg(feature = "_legacy-keys")]
fn scrypt_zeroizing(
    pass: &[u8],
    salt: &[u8],
    params: &scrypt::Params,
    out: &mut [u8],
) -> Result<(), KeyError> {
    let block = usize::try_from(params.r())
        .ok()
        .and_then(|r| r.checked_mul(128))
        .ok_or(KeyError::Bounds)?;
    let lanes = usize::try_from(params.p()).map_err(|_| KeyError::Bounds)?;
    let n = 1usize
        .checked_shl(u32::from(params.log_n()))
        .ok_or(KeyError::Bounds)?;
    let b_len = block.checked_mul(lanes).ok_or(KeyError::Bounds)?;
    let v_len = block.checked_mul(n).ok_or(KeyError::Bounds)?;
    let xy_len = block.checked_mul(2).ok_or(KeyError::Bounds)?;
    if block == 0 || lanes == 0 || n < 2 {
        return Err(KeyError::Bounds);
    }
    let mut b = Zeroizing::new(vec![0u8; b_len]);
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(pass, salt, 1, &mut b);
    let mut v = Zeroizing::new(vec![0u8; v_len]);
    let mut xy = Zeroizing::new(vec![0u8; xy_len]);
    for lane in b.chunks_exact_mut(block) {
        romix(lane, &mut v, &mut xy, n)?;
    }
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(pass, &b, 1, out);
    Ok(())
}

/// Executes `scryptROMix` (RFC 7914, section 5) over zeroizing scratch buffers.
#[cfg(feature = "_legacy-keys")]
fn romix(lane: &mut [u8], v: &mut [u8], xy: &mut [u8], n: usize) -> Result<(), KeyError> {
    let len = lane.len();
    let (x, y) = xy.split_at_mut(len);
    x.copy_from_slice(lane);
    for slot in v.chunks_exact_mut(len) {
        slot.copy_from_slice(x);
        block_mix(x, y);
        x.copy_from_slice(y);
    }
    let mask =
        u64::try_from(n.checked_sub(1).ok_or(KeyError::Bounds)?).map_err(|_| KeyError::Bounds)?;
    for _ in 0..n {
        let tail = len.checked_sub(64).ok_or(KeyError::Bounds)?;
        let word = x
            .get(tail..tail.saturating_add(8))
            .and_then(|w| <[u8; 8]>::try_from(w).ok())
            .ok_or(KeyError::Bounds)?;
        let j = usize::try_from(u64::from_le_bytes(word) & mask).map_err(|_| KeyError::Bounds)?;
        let start = j.checked_mul(len).ok_or(KeyError::Bounds)?;
        let vj = v
            .get(start..start.saturating_add(len))
            .ok_or(KeyError::Bounds)?;
        for (a, b) in x.iter_mut().zip(vj) {
            *a ^= *b;
        }
        block_mix(x, y);
        x.copy_from_slice(y);
    }
    lane.copy_from_slice(x);
    Ok(())
}

/// Executes `scryptBlockMix` (RFC 7914, section 4), distributing even and odd mixing outputs.
#[cfg(feature = "_legacy-keys")]
fn block_mix(input: &[u8], out: &mut [u8]) {
    let mut x = [0u32; 16];
    if let Some(last) = input.rchunks_exact(64).next() {
        load_words(&mut x, last);
    }
    let (even, odd) = out.split_at_mut(input.len() / 2);
    let dest = even.chunks_exact_mut(64).zip(odd.chunks_exact_mut(64));
    for (pair, (e, o)) in input.chunks_exact(128).zip(dest) {
        let (first, second) = pair.split_at(64);
        xor_words(&mut x, first);
        salsa20_8(&mut x);
        store_words(&x, e);
        xor_words(&mut x, second);
        salsa20_8(&mut x);
        store_words(&x, o);
    }
    der::zeroize::Zeroize::zeroize(&mut x);
}

#[cfg(feature = "_legacy-keys")]
fn load_words(x: &mut [u32; 16], bytes: &[u8]) {
    for (w, c) in x.iter_mut().zip(bytes.chunks_exact(4)) {
        *w = c.try_into().map_or(0, u32::from_le_bytes);
    }
}

#[cfg(feature = "_legacy-keys")]
fn xor_words(x: &mut [u32; 16], bytes: &[u8]) {
    for (w, c) in x.iter_mut().zip(bytes.chunks_exact(4)) {
        *w ^= c.try_into().map_or(0, u32::from_le_bytes);
    }
}

#[cfg(feature = "_legacy-keys")]
fn store_words(x: &[u32; 16], out: &mut [u8]) {
    for (w, c) in x.iter().zip(out.chunks_exact_mut(4)) {
        c.copy_from_slice(&w.to_le_bytes());
    }
}

/// Evaluates the Salsa20/8 8-round core transform (RFC 7914, section 3) with state zeroization on
/// completion.
#[cfg(feature = "_legacy-keys")]
fn salsa20_8(block: &mut [u32; 16]) {
    macro_rules! quarter {
        ($x:ident, $a:literal, $b:literal, $c:literal, $d:literal) => {
            $x[$b] ^= $x[$a].wrapping_add($x[$d]).rotate_left(7);
            $x[$c] ^= $x[$b].wrapping_add($x[$a]).rotate_left(9);
            $x[$d] ^= $x[$c].wrapping_add($x[$b]).rotate_left(13);
            $x[$a] ^= $x[$d].wrapping_add($x[$c]).rotate_left(18);
        };
    }
    let mut x = *block;
    for _ in 0..4 {
        quarter!(x, 0, 4, 8, 12);
        quarter!(x, 5, 9, 13, 1);
        quarter!(x, 10, 14, 2, 6);
        quarter!(x, 15, 3, 7, 11);
        quarter!(x, 0, 1, 2, 3);
        quarter!(x, 5, 6, 7, 4);
        quarter!(x, 10, 11, 8, 9);
        quarter!(x, 15, 12, 13, 14);
    }
    for (b, v) in block.iter_mut().zip(x) {
        *b = b.wrapping_add(v);
    }
    der::zeroize::Zeroize::zeroize(&mut x);
}

/// Executes PBKDF2 key derivation and AES-CBC decryption inside the FIPS module boundary via
/// `aws-lc-rs`.
#[cfg(not(feature = "_legacy-keys"))]
fn decrypt_pbes2(
    params: &pbes2::Parameters<'_>,
    pass: &[u8],
    buf: &mut [u8],
) -> Result<usize, KeyError> {
    use aws_lc_rs::{cipher, iv::FixedLength, pbkdf2};
    let Kdf::Pbkdf2(k) = &params.kdf else {
        return Err(KeyError::Rejected);
    };
    let prf = match k.prf {
        Pbkdf2Prf::HmacWithSha256 => pbkdf2::PBKDF2_HMAC_SHA256,
        Pbkdf2Prf::HmacWithSha384 => pbkdf2::PBKDF2_HMAC_SHA384,
        Pbkdf2Prf::HmacWithSha512 => pbkdf2::PBKDF2_HMAC_SHA512,
        _ => return Err(KeyError::Rejected),
    };
    let rounds = std::num::NonZeroU32::new(k.iteration_count).ok_or(KeyError::Bounds)?;
    let (alg, iv) = match &params.encryption {
        pbes2::EncryptionScheme::Aes128Cbc { iv } => (&cipher::AES_128, *iv),
        pbes2::EncryptionScheme::Aes192Cbc { iv } => (&cipher::AES_192, *iv),
        pbes2::EncryptionScheme::Aes256Cbc { iv } => (&cipher::AES_256, *iv),
        _ => return Err(KeyError::Rejected),
    };
    let mut key = Zeroizing::new(vec![0u8; params.encryption.key_size()]);
    pbkdf2::derive(prf, rounds, k.salt, pass, &mut key);
    let unbound = cipher::UnboundCipherKey::new(alg, &key).map_err(|_| KeyError::Rejected)?;
    let dec =
        cipher::PaddedBlockDecryptingKey::cbc_pkcs7(unbound).map_err(|_| KeyError::Rejected)?;
    let ctx = cipher::DecryptionContext::Iv128(FixedLength::from(iv));
    dec.decrypt(buf, ctx)
        .map(|plain| plain.len())
        .map_err(|_| KeyError::BadPassphrase)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]
mod tests {
    use rustls::pki_types::pem::PemObject as _;

    use super::*;

    /// Verifies that the custom zeroizing scrypt implementation reproduces the test vectors from
    /// RFC 7914, section 12.
    #[cfg(feature = "_legacy-keys")]
    #[test]
    fn scrypt_matches_the_rfc_7914_vectors() {
        let run = |pass: &[u8], salt: &[u8], log_n: u8, r: u32, p: u32| {
            let params = scrypt::Params::new(log_n, r, p, 64).expect("params");
            let mut out = [0u8; 64];
            scrypt_zeroizing(pass, salt, &params, &mut out).expect("scrypt");
            out.iter().map(|b| format!("{b:02x}")).collect::<String>()
        };
        assert_eq!(
            run(b"", b"", 4, 1, 1),
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442\
             fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906"
        );
        // The second test vector requires 16 MiB of memory mixing iterations, which is
        // prohibitively slow under Miri.
        if !cfg!(miri) {
            assert_eq!(
                run(b"password", b"NaCl", 10, 8, 16),
                "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162\
                 2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640"
            );
        }
        let mut crate_out = [0u8; 32];
        let params = scrypt::Params::new(5, 3, 2, 32).expect("params");
        scrypt::scrypt(b"pw", b"salt", &params, &mut crate_out).expect("crate scrypt");
        let mut own = [0u8; 32];
        scrypt_zeroizing(b"pw", b"salt", &params, &mut own).expect("scrypt");
        assert_eq!(own, crate_out);
    }

    fn cert(name: &str) -> CertificateDer<'static> {
        let path = zk_testkit::fixtures::tls_dir().join(name);
        CertificateDer::from_pem_file(path).unwrap()
    }

    #[test]
    fn der_tlv_reads_both_length_forms() {
        let short = der_tlv(&[0x04, 0x02, 1, 2, 9]).unwrap();
        assert_eq!(short.tag, 0x04);
        assert_eq!(short.content, &[1, 2]);
        assert_eq!(short.full, &[0x04, 0x02, 1, 2]);
        assert_eq!(short.rest, &[9]);

        let mut long = vec![0x30, 0x82, 0x01, 0x00];
        long.extend_from_slice(&[7u8; 256]);
        long.push(0xff);
        let t = der_tlv(&long).unwrap();
        assert_eq!(t.content.len(), 256);
        assert_eq!(t.full.len(), 260);
        assert_eq!(t.rest, &[0xff]);

        let mut one = vec![0x30, 0x81, 0x80];
        one.extend_from_slice(&[0u8; 128]);
        assert_eq!(der_tlv(&one).unwrap().content.len(), 128);
    }

    #[test]
    fn der_tlv_rejects_bad_headers() {
        for bad in [
            &[][..],
            &[0x30],
            // Truncated payload: content buffer is shorter than the declared length.
            &[0x30, 0x03, 0, 0],
            // Indefinite length encoding (disallowed in strict DER).
            &[0x30, 0x80, 0, 0],
            // Excessive length byte count (greater than 4 bytes).
            &[0x30, 0x85, 0, 0, 0, 0, 1, 0],
            // Declared length exceeds total input slice capacity.
            &[0x30, 0x84, 0xff, 0xff, 0xff, 0xff, 0],
            // High-tag-number form (multi-byte tag representation).
            &[0x1f, 0x01, 0x00],
            // Incomplete length byte sequence.
            &[0x30, 0x82, 0x01],
        ] {
            assert_eq!(der_tlv(bad), None, "{bad:?}");
        }
    }

    #[test]
    fn leaf_spki_takes_v1_and_v3_certificates() {
        let v3 = cert("client.pem");
        let v1 = cert("keys/v1.pem");
        let a = spki_parts(leaf_spki(v3.as_ref()).unwrap()).unwrap();
        let b = spki_parts(leaf_spki(v1.as_ref()).unwrap()).unwrap();
        assert_eq!(a.oid, OID_EC);
        // Confirm that both certificate versions yield identical SPKI public keys.
        assert!(same_key(&a, &b));
        let rsa = cert("keys/rsa.pem");
        let r = spki_parts(leaf_spki(rsa.as_ref()).unwrap()).unwrap();
        assert_eq!(slot_of_oid(r.oid), Some(Slot::Rsa));
        assert_eq!(r.params, DER_NULL);
        assert!(!same_key(&a, &r));
    }

    #[test]
    fn leaf_spki_rejects_other_shapes() {
        let v3 = cert("client.pem");
        let der = v3.as_ref();
        // Verify all prefix truncations fail safely without panics.
        for len in 0..der.len() {
            assert_eq!(leaf_spki(&der[..len]), None, "{len}");
        }
        assert_eq!(leaf_spki(&[0x30, 0x03, 0x02, 0x01, 0x00]), None);
        assert_eq!(spki_parts(&[0x30, 0x00]), None);
        assert_eq!(spki_parts(&[0x31, 0x00]), None);
    }

    #[test]
    fn same_key_treats_an_rsa_null_as_absent_parameters() {
        let with_null = SpkiParts {
            oid: OID_RSA,
            params: DER_NULL,
            bits: b"key",
        };
        let absent = SpkiParts {
            params: &[],
            ..with_null
        };
        assert!(same_key(&with_null, &absent));
        // RSA is the only algorithm where NULL parameters equate to omitted parameters.
        let ec_null = SpkiParts {
            oid: OID_EC,
            ..with_null
        };
        let ec_absent = SpkiParts {
            oid: OID_EC,
            ..absent
        };
        assert!(!same_key(&ec_null, &ec_absent));
        let other_bits = SpkiParts {
            bits: b"kez",
            ..with_null
        };
        assert!(!same_key(&with_null, &other_bits));
        let pss = SpkiParts {
            oid: OID_RSA_PSS,
            ..with_null
        };
        assert!(!same_key(&with_null, &pss));
    }

    #[test]
    fn slots_come_from_the_oid_and_the_key_type() {
        assert_eq!(slot_of_oid(OID_RSA), Some(Slot::Rsa));
        assert_eq!(slot_of_oid(OID_RSA_PSS), Some(Slot::RsaPss));
        assert_eq!(slot_of_oid(OID_EC), Some(Slot::Ecc));
        assert_eq!(slot_of_oid(OID_ED25519), Some(Slot::Ed25519));
        assert_eq!(slot_of_oid(OID_ED448), Some(Slot::Ed448));
        // DSA object identifier (1.2.840.10040.4.1) maps to no supported TLS slot.
        assert_eq!(
            slot_of_oid(&[0x2a, 0x86, 0x48, 0xce, 0x38, 0x04, 0x01]),
            None
        );
        assert_eq!(slot_of_key(SignatureAlgorithm::RSA), Some(Slot::Rsa));
        assert_eq!(slot_of_key(SignatureAlgorithm::ECDSA), Some(Slot::Ecc));
        assert_eq!(
            slot_of_key(SignatureAlgorithm::ED25519),
            Some(Slot::Ed25519)
        );
        assert_eq!(slot_of_key(SignatureAlgorithm::Anonymous), None);
    }

    #[cfg(feature = "_legacy-keys")]
    #[test]
    fn scrypt_bounds_meet_the_memory_limit_exactly() {
        // Compute memory footprint: 256·(65536 + 2) + 256·65534 = 2^25 bytes (32 MiB).
        assert_eq!(scrypt_bounds(65536, 2, 65534), Ok(()));
        assert_eq!(scrypt_bounds(65536, 2, 65535), Err(KeyError::Bounds));
        // Validate cost parameter constraints: log2 N < 16·r.
        assert_eq!(scrypt_bounds(1 << 15, 1, 1), Ok(()));
        assert_eq!(scrypt_bounds(1 << 16, 1, 1), Err(KeyError::Bounds));
        for n in [0, 1, 6, u64::MAX, 1 << 63] {
            assert_eq!(scrypt_bounds(n, 8, 1), Err(KeyError::Bounds), "{n}");
        }
        assert_eq!(scrypt_bounds(2, u16::MAX, u16::MAX), Err(KeyError::Bounds));
    }

    #[test]
    fn prfs_follow_the_build() {
        assert!(prf_accepted(Pbkdf2Prf::HmacWithSha256));
        assert!(prf_accepted(Pbkdf2Prf::HmacWithSha512));
        assert_eq!(
            prf_accepted(Pbkdf2Prf::HmacWithSha1),
            cfg!(feature = "_legacy-keys")
        );
    }

    #[test]
    fn key_length_must_match_the_cipher() {
        assert_eq!(key_length_ok(None, 32), Ok(()));
        assert_eq!(key_length_ok(Some(32), 32), Ok(()));
        assert_eq!(key_length_ok(Some(16), 32), Err(KeyError::Bounds));
    }
}
