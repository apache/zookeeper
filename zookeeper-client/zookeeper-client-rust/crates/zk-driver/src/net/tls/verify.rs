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

//! WebPKI server certificate verification implementations.
//!
//! Provides two distinct WebPKI verification strategies:
//! - **Standard WebPKI (`TlsVerify::WebPki`)**: Standard Rustls verifier enforcing full X.509
//!   trust anchor chaining, temporal validity constraints, and SAN matching against the
//!   destination host token.
//! - **Chain-Only Verification ([`ChainOnlyVerifier`])**: Validates that the end-entity cert
//!   forms a valid trust path to a configured root anchor at current evaluation time, while
//!   omitting server hostname validation. Employed when hostname verification is disabled.
//!
//! Handshake signature validation across both modes utilizes the active cryptographic provider's
//! supported signature schemes.

use std::sync::Arc;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::{WebPkiServerVerifier, verify_server_cert_signed_by_trust_anchor};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::server::ParsedCertificate;
use rustls::{DigitallySignedStruct, RootCertStore, SignatureScheme};

use crate::net::tls::{TlsResourceAt, TlsSetupError};

/// Server certificate verifier enforcing WebPKI trust anchor chaining without domain name
/// validation.
#[derive(Debug)]
pub struct ChainOnlyVerifier {
    roots: Arc<RootCertStore>,
    provider: Arc<CryptoProvider>,
}

impl ChainOnlyVerifier {
    /// Constructs a chain only verifier bound to the provided trust anchors and cryptographic
    /// provider.
    #[must_use]
    pub fn new(roots: Arc<RootCertStore>, provider: Arc<CryptoProvider>) -> Self {
        Self { roots, provider }
    }
}

impl ServerCertVerifier for ChainOnlyVerifier {
    /// Validates the server certificate chain against configured trust anchors at the specified
    /// timestamp.
    ///
    /// Evaluates path signatures, intermediate validity periods, and basic constraints. The server
    /// name parameter and OCSP responses are ignored, mirroring the behavior of legacy clients.
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        // Server name validation is bypassed for legacy chain-only verification modes. OCSP
        // stapling responses are ignored in accordance with legacy client behavior.
        let leaf = ParsedCertificate::try_from(end_entity)?;
        verify_server_cert_signed_by_trust_anchor(
            &leaf,
            &self.roots,
            intermediates,
            now,
            self.provider.signature_verification_algorithms.all,
        )?;
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// Constructs a standard Rustls `WebPki` server verifier populated with root trust anchors.
///
/// # Errors
///
/// Returns [`TlsSetupError::Resource`] with variant `ContextNew` if the verifier cannot be
/// assembled.
pub fn webpki_verifier(
    roots: Arc<RootCertStore>,
    provider: Arc<CryptoProvider>,
) -> Result<Arc<dyn ServerCertVerifier>, TlsSetupError> {
    match WebPkiServerVerifier::builder_with_provider(roots, provider).build() {
        Ok(v) => Ok(v),
        Err(_) => Err(TlsSetupError::Resource {
            at: TlsResourceAt::ContextNew,
        }),
    }
}
