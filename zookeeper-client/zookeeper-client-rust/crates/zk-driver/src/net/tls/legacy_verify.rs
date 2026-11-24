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

//! OpenSSL-parity server certificate verifier ([`LegacyChainOnly`]) for legacy ZooKeeper client
//! compatibility.
//!
//! Replicates OpenSSL verification behavior configured by the legacy C client: enforcing
//! `SSL_VERIFY_PEER`, `ssl_server` purpose and trust settings, trusted-first intermediate path
//! building, and explicitly disabling hostname validation and SNI verification. The verification
//! decision workflow proceeds:
//!
//! 1. Leaf cryptographic key compatibility check via `leaf_key_usable` across all execution
//!    paths.
//! 2. Direct trust path (Path B): OpenSSL-self-signed end-entity certificate matching a trusted,
//!    non-rejected store entry, enforcing OpenSSL mimic disambiguation rules.
//! 3. Legacy X.509 v1 leaf path (Path A-v1): delegated directly to `v1_leaf::verify` for
//!    autonomous path construction.
//! 4. Modern X.509 v3 leaf path (Path A-v3): preliminary validation via
//!    `verify_server_cert_signed_by_trust_anchor`, followed by intermediate post-checks
//!    (`keycertsign_post_check`, `rejected_issuer_post_check`), leaf temporal validity, server
//!    authentication purpose constraints, and path post-check (`v1_leaf::verify_v3_path`).
//!
//! The resulting verification verdict represents the strict intersection of modern WebPKI checks
//! and OpenSSL path validation rules, ensuring it never accepts a certificate that OpenSSL would
//! reject. Server name validation is omitted by design to match legacy C client semantics.
//! Handshake signature verification extracts public key material directly from the SPKI parsed by
//! [`LegacyCert`], bypassing WebPKI's refusal to inspect v1 certificates.
//!
//! Installed by the drop-in connector under `Compat::LEGACY_C` as well as by the native Rust
//! client's `legacy_c_compat` verification mode.

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::WebPkiSupportedAlgorithms;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime, alg_id};
use rustls::server::ParsedCertificate;
use rustls::{CertificateError, DigitallySignedStruct, Error, PeerMisbehaved, SignatureScheme};

use crate::net::tls::trust::{Role, TrustSet, TrustedCert};
use crate::net::tls::v1_leaf::{self, DerReader, KU_DIGITAL_SIGNATURE, KU_KEY_CERT_SIGN, V1Reject};
use crate::net::tls::x509::{AuxTrust, CertVersion, LegacyCert, Spki, check_issued};

/// Minimum RSA modulus bit length accepted by the cryptographic provider.
const RSA_MIN_BITS: usize = 2048;
/// Maximum RSA modulus bit length accepted by the cryptographic provider.
const RSA_MAX_BITS: usize = 8192;

/// OpenSSL-parity server certificate verifier installed for legacy C client compatibility.
#[derive(Debug)]
pub struct LegacyChainOnly {
    trust: TrustSet,
    algs: WebPkiSupportedAlgorithms,
}

impl LegacyChainOnly {
    /// Constructs a new verifier configured with the specified trust store and cryptographic
    /// signature verification algorithms.
    #[must_use]
    pub fn new(trust: TrustSet, algs: WebPkiSupportedAlgorithms) -> Self {
        Self { trust, algs }
    }

    /// Returns an immutable reference to the underlying trust set.
    #[must_use]
    pub fn trust(&self) -> &TrustSet {
        &self.trust
    }

    /// Executes Path (a) verification against an X.509 v3 leaf certificate: WebPKI path validation
    /// followed by OpenSSL post-checks.
    fn verify_v3(
        &self,
        end_entity: &CertificateDer<'_>,
        leaf: &LegacyCert<'_>,
        sent: &[LegacyCert<'_>],
        now: UnixTime,
    ) -> Result<(), Error> {
        let parsed = ParsedCertificate::try_from(end_entity)?;
        let roots = self.trust.root_store_at(now);
        // Collect active peer-sent certificates and non-expired bridge certificates into
        // intermediate candidate set I'.
        let mut bridges = Vec::with_capacity(self.trust.bridges().len());
        for der in self.trust.bridges() {
            bridges.push(LegacyCert::parse(der.as_ref()).map_err(|_| bad_encoding())?);
        }
        let reach: Vec<&LegacyCert<'_>> = sent
            .iter()
            .chain(bridges.iter())
            .filter(|c| now.as_secs() < c.validity().not_after.as_secs())
            .collect();
        let reach_der: Vec<CertificateDer<'_>> = reach
            .iter()
            .map(|c| CertificateDer::from(c.der()))
            .collect();
        rustls::client::verify_server_cert_signed_by_trust_anchor(
            &parsed,
            &roots,
            &reach_der,
            now,
            self.algs.all,
        )?;
        let reach: Vec<LegacyCert<'_>> = reach.into_iter().cloned().collect();
        if !keycertsign_post_check(leaf, &reach) {
            return Err(app_failure());
        }
        if !rejected_issuer_post_check(leaf, &reach, &self.trust) {
            return Err(app_failure());
        }
        if !leaf.valid_at(now) {
            return Err(Error::InvalidCertificate(CertificateError::Expired));
        }
        if !v1_leaf::ssl_server_purpose(leaf, false) {
            return Err(Error::InvalidCertificate(CertificateError::InvalidPurpose));
        }
        v1_leaf::verify_v3_path(leaf, sent, &self.trust, now, self.algs.all).map_err(reject_error)
    }
}

impl ServerCertVerifier for LegacyChainOnly {
    /// Evaluates peer-presented end-entity certificate and intermediate chain against OpenSSL
    /// policy rules.
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        let leaf = LegacyCert::parse(end_entity.as_ref()).map_err(|_| bad_encoding())?;
        if !leaf_key_usable(&leaf, &self.algs) {
            return Err(app_failure());
        }
        if leaf.openssl_self_signed() {
            // OpenSSL error 18 (X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT) if no matching trust anchor
            // exists.
            return if path_b(&leaf, &self.trust, now) {
                Ok(ServerCertVerified::assertion())
            } else {
                Err(Error::InvalidCertificate(CertificateError::UnknownIssuer))
            };
        }
        let mut sent = Vec::with_capacity(intermediates.len());
        for der in intermediates {
            sent.push(LegacyCert::parse(der.as_ref()).map_err(|_| bad_encoding())?);
        }
        match leaf.version() {
            CertVersion::V1 => {
                v1_leaf::verify(&leaf, &sent, &self.trust, now, self.algs.all)
                    .map_err(reject_error)?;
            }
            CertVersion::V3 => self.verify_v3(end_entity, &leaf, &sent, now)?,
        }
        Ok(ServerCertVerified::assertion())
    }

    /// Validates a TLS 1.2 handshake signature against the SPKI of the accepted end-entity
    /// certificate.
    ///
    /// On TLS 1.2, ECDSA cipher suites require `digitalSignature` key usage.
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        let leaf = LegacyCert::parse(cert.as_ref()).map_err(|_| bad_encoding())?;
        let spki = leaf.spki();
        let ecdsa_suite =
            is_ec_key(spki.alg_id_value) || spki.alg_id_value == alg_id::ED25519.as_ref();
        if ecdsa_suite
            && leaf
                .key_usage()
                .is_some_and(|ku| ku & KU_DIGITAL_SIGNATURE == 0)
        {
            return Err(Error::InvalidCertificate(CertificateError::InvalidPurpose));
        }
        verify_tls12_with_spki(message, &spki, dss, &self.algs)
    }

    /// Validates a TLS 1.3 handshake signature against the SPKI raw public key using TLS 1.3
    /// signature scheme rules.
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        let leaf = LegacyCert::parse(cert.as_ref()).map_err(|_| bad_encoding())?;
        rustls::crypto::verify_tls13_signature_with_raw_key(
            message,
            &leaf.spki_der(),
            dss,
            &self.algs,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algs.supported_schemes()
    }
}

/// Constructs an invalid certificate error denoting malformed ASN.1 DER encoding.
fn bad_encoding() -> Error {
    Error::InvalidCertificate(CertificateError::BadEncoding)
}

/// Constructs an application-level verification failure for OpenSSL policy violations lacking a
/// dedicated rustls error variant.
fn app_failure() -> Error {
    Error::InvalidCertificate(CertificateError::ApplicationVerificationFailure)
}

/// Maps internal [`V1Reject`] rejection codes to corresponding [`rustls::Error`] representations
/// for diagnostics and logging.
fn reject_error(reason: V1Reject) -> Error {
    let e = match reason {
        V1Reject::Expired => CertificateError::Expired,
        V1Reject::Signature => CertificateError::BadSignature,
        V1Reject::NoIssuer | V1Reject::ChainTooLong => CertificateError::UnknownIssuer,
        V1Reject::Purpose => CertificateError::InvalidPurpose,
        V1Reject::CriticalExtension => CertificateError::UnhandledCriticalExtension,
        V1Reject::InvalidCa
        | V1Reject::PathLength
        | V1Reject::NameConstraints
        | V1Reject::Ambiguous
        | V1Reject::Budget
        | V1Reject::KeyUsage
        | V1Reject::Rejected
        | V1Reject::Undecided => CertificateError::ApplicationVerificationFailure,
    };
    Error::InvalidCertificate(e)
}

/// Determines whether the cryptographic provider supports signature verification with the leaf
/// certificate's public key.
///
/// Validates key algorithm, curve compatibility, RSA modulus boundaries (2048 to 8192 bits), and
/// EC point compression constraints.
pub(crate) fn leaf_key_usable(leaf: &LegacyCert<'_>, algs: &WebPkiSupportedAlgorithms) -> bool {
    let spki = leaf.spki();
    let known = algs
        .all
        .iter()
        .any(|a| a.public_key_alg_id().as_ref() == spki.alg_id_value);
    if !known {
        return false;
    }
    if spki.alg_id_value == alg_id::RSA_ENCRYPTION.as_ref() {
        return rsa_modulus_bits(spki.key_bits)
            .is_some_and(|bits| (RSA_MIN_BITS..=RSA_MAX_BITS).contains(&bits));
    }
    if is_ec_key(spki.alg_id_value) {
        return ec_point_usable(spki.key_bits);
    }
    true
}

/// Checks whether an algorithm identifier corresponds to a recognized NIST elliptic curve.
fn is_ec_key(alg: &[u8]) -> bool {
    alg == alg_id::ECDSA_P256.as_ref()
        || alg == alg_id::ECDSA_P384.as_ref()
        || alg == alg_id::ECDSA_P521.as_ref()
}

/// Verifies elliptic curve point formatting requirements under `ring` (uncompressed 0x04 format).
#[cfg(feature = "tls-ring")]
fn ec_point_usable(point: &[u8]) -> bool {
    point.first() == Some(&0x04)
}

/// Verifies elliptic curve point formatting requirements under `aws-lc-rs`.
#[cfg(not(feature = "tls-ring"))]
fn ec_point_usable(point: &[u8]) -> bool {
    !point.is_empty()
}

/// Calculates the effective bit length of an RSA public key modulus from raw SPKI DER bytes.
fn rsa_modulus_bits(key: &[u8]) -> Option<usize> {
    let content = v1_leaf::single(key, 0x30)?;
    let mut r = DerReader::new(content);
    let modulus = r.read_tag(0x02)?;
    r.read_tag(0x02)?;
    if !r.is_empty() {
        return None;
    }
    let skip = modulus.iter().take_while(|&&b| b == 0).count();
    let digits = modulus.get(skip..)?;
    let first = digits.first()?;
    let lead = usize::try_from(first.leading_zeros()).ok()?;
    digits.len().checked_mul(8)?.checked_sub(lead)
}

/// Executes Path (b) validation for OpenSSL-self-signed end-entity certificates against the trust
/// store.
///
/// Enforces OpenSSL directory lookup rules, mimic resolution across same-name candidates, auxiliary
/// trust settings, and critical extensions.
pub(crate) fn path_b(leaf: &LegacyCert<'_>, trust: &TrustSet, now: UnixTime) -> bool {
    // Query directory hash groups for the leaf subject; failure to resolve store entries fails
    // closed.
    let subject = leaf.canonical_subject();
    let mut loaded = Vec::new();
    if trust.lookup(&subject, &mut loaded).is_err() {
        return false;
    }
    let visible: Vec<&TrustedCert> = trust
        .certs()
        .iter()
        .filter(|t| TrustSet::visible(t.origin(), &loaded))
        .collect();
    let Some(matched) = visible
        .iter()
        .find(|t| t.role() != Role::Rejected && t.der().as_ref() == leaf.der())
    else {
        return false;
    };
    let aux_trusts = matched.aux().is_some_and(AuxTrust::trusts_server_auth);
    // Reject when visible identical DER entries specify conflicting auxiliary serverAuth trust
    // configurations.
    let conflict = visible.iter().any(|t| {
        t.der().as_ref() == leaf.der()
            && t.aux().is_some_and(AuxTrust::trusts_server_auth) != aux_trusts
    });
    if conflict {
        return false;
    }
    // Candidate mimic set C: all trust inputs with matching subject and valid signature.
    // Disambiguation must not depend on store lookup order; any conflicting candidate fails
    // closed.
    for t in visible {
        let Ok(cert) = LegacyCert::parse(t.der().as_ref()) else {
            return false;
        };
        let in_c = cert.canonical_subject() == subject && check_issued(&cert, leaf);
        if !in_c || !cert.valid_at(now) {
            continue;
        }
        if t.role() == Role::Rejected || cert.der() != leaf.der() {
            return false;
        }
    }
    leaf.valid_at(now)
        && (aux_trusts || v1_leaf::ssl_server_purpose(leaf, true))
        && !v1_leaf::has_unprocessed_critical(leaf)
}

/// Rejects candidate paths if any reachable intermediate CA certificate lacks required
/// `keyCertSign` key usage (OpenSSL error 32).
///
/// Conducts a breadth-first search traversing reachable certificates via `check_issued` to enforce
/// OpenSSL parity.
pub(crate) fn keycertsign_post_check(leaf: &LegacyCert<'_>, reach: &[LegacyCert<'_>]) -> bool {
    // Breadth-first traversal from leaf through candidate intermediate set.
    let mut reached = vec![false; reach.len()];
    let mut frontier: Vec<&LegacyCert<'_>> = vec![leaf];
    while let Some(y) = frontier.pop() {
        for (x, seen) in reach.iter().zip(reached.iter_mut()) {
            if *seen || !check_issued(x, y) {
                continue;
            }
            *seen = true;
            if x.key_usage().is_some_and(|ku| ku & KU_KEY_CERT_SIGN == 0) {
                return false;
            }
            frontier.push(x);
        }
    }
    true
}

/// Rejects candidate paths if any reachable intermediate certificate or leaf is issued by a
/// rejected anchor (OpenSSL error 28).
pub(crate) fn rejected_issuer_post_check(
    leaf: &LegacyCert<'_>,
    intermediates: &[LegacyCert<'_>],
    trust: &TrustSet,
) -> bool {
    for r in trust.rejected() {
        // Trust inputs are pre-parsed by TrustedCert; parse failure indicates unexpected
        // corruption.
        let Ok(r) = LegacyCert::parse(r.der().as_ref()) else {
            return false;
        };
        if check_issued(&r, leaf) || intermediates.iter().any(|x| check_issued(&r, x)) {
            return false;
        }
    }
    true
}

/// Verifies a TLS 1.2 handshake signature using the raw SPKI public key and supported
/// cryptographic provider algorithms.
///
/// # Errors
///
/// Returns [`Error::PeerMisbehaved`] for unadvertised signature schemes; returns
/// [`CertificateError::BadSignature`] on failure.
pub(crate) fn verify_tls12_with_spki(
    message: &[u8],
    spki: &Spki<'_>,
    dss: &DigitallySignedStruct,
    algs: &WebPkiSupportedAlgorithms,
) -> Result<HandshakeSignatureValid, Error> {
    let candidates = algs
        .mapping
        .iter()
        .find(|(scheme, _)| *scheme == dss.scheme)
        .map(|(_, a)| *a)
        .ok_or(Error::PeerMisbehaved(
            PeerMisbehaved::SignedHandshakeWithUnadvertisedSigScheme,
        ))?;
    // Select the first verification algorithm matching the certificate's public key identifier.
    let alg = candidates
        .iter()
        .find(|a| a.public_key_alg_id().as_ref() == spki.alg_id_value)
        .ok_or(Error::InvalidCertificate(CertificateError::BadSignature))?;
    alg.verify_signature(spki.key_bits, message, dss.signature())
        .map(|()| HandshakeSignatureValid::assertion())
        .map_err(|_| Error::InvalidCertificate(CertificateError::BadSignature))
}

#[cfg(test)]
mod tests {
    #![allow(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::arithmetic_side_effects
    )]

    use super::*;

    fn der(tag: u8, content: &[u8]) -> Vec<u8> {
        let mut out = vec![tag];
        let len = content.len();
        let digits: Vec<u8> = len
            .to_be_bytes()
            .into_iter()
            .skip_while(|&b| b == 0)
            .collect();
        if len < 0x80 {
            out.push(u8::try_from(len).unwrap());
        } else {
            out.push(0x80 | u8::try_from(digits.len()).unwrap());
            out.extend(digits);
        }
        out.extend_from_slice(content);
        out
    }

    /// Constructs an RSAPublicKey DER sequence from modulus bytes and standard public exponent
    /// 65537.
    fn rsa_key(modulus: &[u8]) -> Vec<u8> {
        der(0x30, &[der(0x02, modulus), der(0x02, &[1, 0, 1])].concat())
    }

    fn modulus(bits: usize) -> Vec<u8> {
        let bytes = bits.div_ceil(8);
        let mut m = vec![0xff; bytes];
        m[0] = 0xff >> (bytes * 8 - bits);
        if m[0] & 0x80 != 0 {
            m.insert(0, 0);
        }
        m
    }

    #[test]
    fn rsa_modulus_bits_counts_significant_bits() {
        for bits in [1024, 2047, 2048, 2049, 4096, 8192, 8193] {
            assert_eq!(rsa_modulus_bits(&rsa_key(&modulus(bits))), Some(bits));
        }
        let mut trailing = rsa_key(&modulus(2048));
        trailing.extend([0x05, 0x00]);
        assert_eq!(rsa_modulus_bits(&trailing), None);
        let extra = der(
            0x30,
            &[der(0x02, &modulus(2048)), der(0x02, &[3]), der(0x02, &[3])].concat(),
        );
        assert_eq!(rsa_modulus_bits(&extra), None);
        assert_eq!(rsa_modulus_bits(&rsa_key(&[0, 0])), None);
        assert_eq!(rsa_modulus_bits(&[0x30, 0x80]), None);
    }

    #[test]
    fn rsa_size_bounds_match_the_provider_algorithms() {
        let ok = |bits| (RSA_MIN_BITS..=RSA_MAX_BITS).contains(&bits);
        assert!(!ok(2047));
        assert!(ok(2048));
        assert!(ok(8192));
        assert!(!ok(8193));
    }

    #[test]
    fn ec_key_types_are_the_three_nist_curves() {
        assert!(is_ec_key(alg_id::ECDSA_P256.as_ref()));
        assert!(is_ec_key(alg_id::ECDSA_P384.as_ref()));
        assert!(is_ec_key(alg_id::ECDSA_P521.as_ref()));
        assert!(!is_ec_key(alg_id::RSA_ENCRYPTION.as_ref()));
        assert!(!is_ec_key(alg_id::ED25519.as_ref()));
    }

    #[test]
    fn ec_point_form_follows_the_provider() {
        let uncompressed = [0x04_u8; 65];
        let mut compressed = [0x11_u8; 33];
        compressed[0] = 0x02;
        assert!(ec_point_usable(&uncompressed));
        assert_eq!(
            ec_point_usable(&compressed),
            cfg!(not(feature = "tls-ring"))
        );
        assert!(!ec_point_usable(&[]));
    }

    #[test]
    fn reject_reasons_map_to_rustls_errors() {
        let cert = |r| match reject_error(r) {
            Error::InvalidCertificate(e) => e,
            other => panic!("{other:?}"),
        };
        assert_eq!(cert(V1Reject::Expired), CertificateError::Expired);
        assert_eq!(cert(V1Reject::Signature), CertificateError::BadSignature);
        assert_eq!(cert(V1Reject::NoIssuer), CertificateError::UnknownIssuer);
        assert_eq!(
            cert(V1Reject::ChainTooLong),
            CertificateError::UnknownIssuer
        );
        assert_eq!(cert(V1Reject::Purpose), CertificateError::InvalidPurpose);
        assert_eq!(
            cert(V1Reject::CriticalExtension),
            CertificateError::UnhandledCriticalExtension
        );
        for r in [
            V1Reject::InvalidCa,
            V1Reject::PathLength,
            V1Reject::NameConstraints,
            V1Reject::Ambiguous,
            V1Reject::Budget,
            V1Reject::KeyUsage,
            V1Reject::Rejected,
            V1Reject::Undecided,
        ] {
            assert_eq!(cert(r), CertificateError::ApplicationVerificationFailure);
        }
    }
}
