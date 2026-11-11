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

//! Verification engine for legacy X.509 v1 leaf certificates and post-acceptance chain validation.
//!
//! Because WebPKI deliberately declines to parse or validate legacy X.509 v1 certificates,
//! this module provides an autonomous path-building and certificate verification engine replicating
//! OpenSSL 3.x verification semantics (`x509_vfy.c`, `v3_purp.c`).
//!
//! Two primary validation entry points are exposed:
//! - `verify`: Acts as the authoritative acceptance gate for peer-supplied legacy v1 end-entity
//!   certificates, searching candidate trust chains and applying full X.509 semantic checks.
//! - `verify_v3_path`: Runs the identical candidate selection, chain construction, and finish
//!   rules against standard v3 leaf certificates that WebPKI has already accepted. This function
//!   acts strictly as a rejection gate, guaranteeing that certificates accepted under modern WebPKI
//!   rules also satisfy OpenSSL trust-store and policy requirements.
//!
//! The chain construction pipeline enforces:
//! - Trusted-first candidate issuer selection governed by canonical subject matching
//!   ([`check_issued`]).
//! - Peer-supplied intermediate issuer exploration ordering across sent certificates via
//!   `find_issuer`.
//! - Strict termination preventing peer-supplied certificates above trusted bridges or self-signed
//!   peers.
//! - Multi-candidate ambiguity resolution requiring unanimous path convergence across candidate
//!   branches.
//! - Mandatory `keyCertSign` and non-rejected status verification on every selected intermediate
//!   issuer.
//! - Terminal path validation evaluating basic constraints (`check_ca`), the `ssl_server` CA
//!   purpose, path length constraints, name constraints across subjects and alternative names, and
//!   unhandled critical extensions.
//! - Work budget bounds (`WORK_BUDGET`) preventing combinatorial denial-of-service from adversarial
//!   or densely cross-signed directory topologies.
//!
//! Cryptographic signature verification is delegated exclusively to the provider's
//! [`SignatureVerificationAlgorithm`] instances (exercising FIPS-approved algorithms in FIPS mode).
//! A bounded, zero-allocation DER reader inspects certificate extensions, distinguished names,
//! subject alternative names, and name constraint envelopes safely without panic risks.

use rustls::pki_types::{SignatureVerificationAlgorithm, UnixTime};

use crate::net::tls::trust::{Origin, Role, TrustSet, TrustedCert};
use crate::net::tls::x509::{AuxTrust, CertVersion, LegacyCert, check_issued};

/// Default maximum intermediate certificate depth allowed during path validation, matching OpenSSL.
pub(crate) const MAX_DEPTH: usize = 100;

/// Maximum number of candidate certificate evaluations permitted during a single verification run.
pub(crate) const WORK_BUDGET: usize = 256;

/// `KeyUsage` bit mask representing `digitalSignature` (RFC 5280 named bit 0).
pub(crate) const KU_DIGITAL_SIGNATURE: u16 = 1 << 0;
/// `KeyUsage` bit mask representing `keyEncipherment` (RFC 5280 named bit 2).
pub(crate) const KU_KEY_ENCIPHERMENT: u16 = 1 << 2;
/// `KeyUsage` bit mask representing `keyAgreement` (RFC 5280 named bit 4).
pub(crate) const KU_KEY_AGREEMENT: u16 = 1 << 4;
/// `KeyUsage` bit mask representing `keyCertSign` (RFC 5280 named bit 5).
pub(crate) const KU_KEY_CERT_SIGN: u16 = 1 << 5;

/// Netscape certificate type bit flag for `sslServer` (named bit 1).
pub(crate) const NS_SSL_SERVER: u8 = 1 << 1;
/// Netscape certificate type bit flag for `sslCA` (named bit 5).
pub(crate) const NS_SSL_CA: u8 = 1 << 5;

/// Maximum product of candidate names and constraint rules accepted in one evaluation
/// (`NAME_CHECK_MAX`).
const NAME_CHECK_MAX: usize = 1 << 20;

/// Recognized critical extension OID contents explicitly handled during path validation.
const PROCESSED_CRITICAL: [&[u8]; 6] = [
    // basicConstraints
    &[0x55, 0x1d, 0x13],
    // keyUsage
    &[0x55, 0x1d, 0x0f],
    // extKeyUsage
    &[0x55, 0x1d, 0x25],
    // subjectAltName
    &[0x55, 0x1d, 0x11],
    // nameConstraints
    &[0x55, 0x1d, 0x1e],
    // Netscape cert type
    &[0x60, 0x86, 0x48, 0x01, 0x86, 0xf8, 0x42, 0x01, 0x01],
];

/// Attribute type OID for `commonName` (2.5.4.3).
const OID_COMMON_NAME: &[u8] = &[0x55, 0x04, 0x03];
/// Attribute type OID for PKCS #9 `emailAddress` (1.2.840.113549.1.9.1).
const OID_EMAIL_ADDRESS: &[u8] = &[0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x01];
/// GeneralName `otherName` type OID for `SmtpUTF8Mailbox` (RFC 8398, 1.3.6.1.5.5.7.8.9).
const OID_SMTP_UTF8_MAILBOX: &[u8] = &[0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x08, 0x09];

/// Standard ASN.1 DER tag constants utilized across X.509 parsing routines.
const TAG_INTEGER: u8 = 0x02;
const TAG_OID: u8 = 0x06;
const TAG_UTF8_STRING: u8 = 0x0c;
const TAG_IA5_STRING: u8 = 0x16;
const TAG_SEQUENCE: u8 = 0x30;
const TAG_SET: u8 = 0x31;

/// Context-specific tag numbers for `GeneralName` choice alternatives (RFC 5280).
const GN_OTHER_NAME: u8 = 0;
const GN_RFC822: u8 = 1;
const GN_DNS: u8 = 2;
const GN_DIRECTORY: u8 = 4;
const GN_URI: u8 = 6;
const GN_IP: u8 = 7;

/// Specific rejection reason encountered during path construction or certificate verification.
///
/// Any variant results in TLS handshake termination; detailed reasons are retained for logging and
/// tests.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum V1Reject {
    /// Certificate failed basic CA verification constraints (OpenSSL error 79:
    /// `X509_V_ERR_INVALID_CA`).
    InvalidCa,
    /// Path hierarchy depth exceeded an intermediate `pathLenConstraint` (OpenSSL error 25).
    PathLength,
    /// Certificate failed key usage or extended key usage purpose checks (OpenSSL error 26).
    Purpose,
    /// Certificate is not valid at the evaluation timestamp (`now`).
    Expired,
    /// Cryptographic signature verification failed or algorithm is unsupported.
    Signature,
    /// Unable to locate a valid issuer certificate in the trust store or peer chain (OpenSSL errors
    /// 2, 19, 20).
    NoIssuer,
    /// Certificate name violates permitted or excluded name constraints.
    NameConstraints,
    /// Certificate contains an unhandled critical extension (OpenSSL error 34).
    CriticalExtension,
    /// Path intermediate depth exceeded [`MAX_DEPTH`] (OpenSSL error 22).
    ChainTooLong,
    /// Multiple time-valid candidate issuer paths yielded conflicting verification outcomes.
    Ambiguous,
    /// Candidate search step count exceeded [`WORK_BUDGET`].
    Budget,
    /// Intermediate issuer certificate lacks required `keyCertSign` key usage bit (OpenSSL error
    /// 32).
    KeyUsage,
    /// Path traversed a certificate explicitly designated as `Role::Rejected` (OpenSSL error 28).
    Rejected,
    /// Store lookup encountered an unresolvable state, such as parse rejection, partial file load,
    /// or conflicting metadata.
    Undecided,
}

/// Verifies a peer-presented X.509 v1 leaf certificate against trust stores and sent intermediates.
///
/// Assumes the caller has already resolved whether the leaf is self-signed against the trust set.
/// Adheres strictly to OpenSSL verification rules: never accepts when OpenSSL would reject, though
/// it may fail closed on unsupported or non-standard algorithm configurations (such as DSA or
/// Ed448).
pub(crate) fn verify(
    leaf: &LegacyCert<'_>,
    sent: &[LegacyCert<'_>],
    trust: &TrustSet,
    now: UnixTime,
    algs: &[&dyn SignatureVerificationAlgorithm],
) -> Result<(), V1Reject> {
    // Only non-self-signed v1 leaf certificates are evaluated by this path builder.
    if leaf.version() != CertVersion::V1 || leaf.openssl_self_signed() {
        return Err(V1Reject::NoIssuer);
    }
    // Standard X.509 v1 certificates cannot contain extensions; fail closed if any extension
    // exists.
    if has_any_extension(leaf) {
        return Err(V1Reject::CriticalExtension);
    }
    check_leaf(leaf, now)?;
    Search::new(leaf, sent, trust, now, algs)?.run()
}

/// Executes OpenSSL post-validation path checks against an X.509 v3 leaf certificate accepted by
/// WebPKI.
///
/// Ensures that v3 certificates accepted by modern WebPKI rules strictly adhere to OpenSSL policy
/// constraints, rejecting any chain that violates OpenSSL trust or intermediate requirements.
pub(crate) fn verify_v3_path(
    leaf: &LegacyCert<'_>,
    sent: &[LegacyCert<'_>],
    trust: &TrustSet,
    now: UnixTime,
    algs: &[&dyn SignatureVerificationAlgorithm],
) -> Result<(), V1Reject> {
    if leaf.version() != CertVersion::V3 || leaf.openssl_self_signed() {
        return Err(V1Reject::NoIssuer);
    }
    check_leaf(leaf, now)?;
    Search::new(leaf, sent, trust, now, algs)?.run()
}

/// Evaluates end-entity server authentication purpose constraints
/// (`check_purpose_ssl_server(x, ca = 0)`).
///
/// Validates EKU, key usage, and legacy Netscape certificate type flags. `ext_key_usage` is skipped
/// when validating v3 leaves already accepted by WebPKI.
pub(crate) fn ssl_server_purpose(leaf: &LegacyCert<'_>, ext_key_usage: bool) -> bool {
    if ext_key_usage && !leaf.eku_allows_ssl_server() {
        return false;
    }
    if leaf
        .ns_cert_type()
        .is_some_and(|ns| ns & NS_SSL_SERVER == 0)
    {
        return false;
    }
    let server_usage = KU_DIGITAL_SIGNATURE | KU_KEY_ENCIPHERMENT | KU_KEY_AGREEMENT;
    !leaf.key_usage().is_some_and(|ku| ku & server_usage == 0)
}

/// Checks whether a certificate includes critical extensions outside the handled set.
pub(crate) fn has_unprocessed_critical(cert: &LegacyCert<'_>) -> bool {
    cert.critical_extensions()
        .iter()
        .any(|oid| !PROCESSED_CRITICAL.contains(oid))
}

/// Validates leaf certificate temporal validity and server authentication purpose constraints.
fn check_leaf(leaf: &LegacyCert<'_>, now: UnixTime) -> Result<(), V1Reject> {
    if !leaf.valid_at(now) {
        return Err(V1Reject::Expired);
    }
    if !ssl_server_purpose(leaf, true) {
        return Err(V1Reject::Purpose);
    }
    Ok(())
}

/// Determines whether a certificate contains any recognizable extension fields.
fn has_any_extension(cert: &LegacyCert<'_>) -> bool {
    cert.basic_constraints().is_some()
        || cert.key_usage().is_some()
        || cert.ext_key_usage().is_some()
        || cert.ns_cert_type().is_some()
        || cert.subject_key_id().is_some()
        || cert.authority_key_id().is_some()
        || cert.subject_alt_name().is_some()
        || cert.name_constraints().is_some()
        || !cert.critical_extensions().is_empty()
}

/// Candidate trust-store certificate entry maintained during path exploration.
struct Trusted<'a> {
    cert: LegacyCert<'a>,
    role: Role,
    aux_trusts: bool,
    /// Store origin locations tracking where this certificate DER was loaded from.
    origins: Vec<Origin>,
    /// Flags whether multiple occurrences of this DER disagree on trust roles or auxiliary
    /// settings.
    conflict: bool,
}

impl Trusted<'_> {
    /// Determines whether this certificate is accessible given the currently loaded directory hash
    /// groups.
    fn visible(&self, loaded: &[usize]) -> bool {
        self.origins.iter().any(|o| TrustSet::visible(*o, loaded))
    }
}

/// Single certificate node within an actively constructed candidate validation path.
#[derive(Clone, Copy)]
struct Link<'s, 'a> {
    cert: &'s LegacyCert<'a>,
    /// Associated trust-store metadata if this certificate node originates from the configured
    /// trust store.
    trusted: Option<&'s Trusted<'a>>,
}

/// Path building and verification state machine executing candidate issuer resolution.
struct Search<'a> {
    leaf: &'a LegacyCert<'a>,
    sent: &'a [LegacyCert<'a>],
    /// Parsed candidate trust-store inputs indexed in discovery order, one entry per unique DER.
    trust: Vec<Trusted<'a>>,
    /// Backing trust-store reference used for dynamic hash directory lookups.
    store: &'a TrustSet,
    now: UnixTime,
    algs: &'a [&'a dyn SignatureVerificationAlgorithm],
}

/// Appends an issuer subject distinguished name to the transitive closure list if not already
/// present.
fn add_name(names: &mut Vec<Vec<u8>>, name: Vec<u8>) {
    if !names.contains(&name) {
        names.push(name);
    }
}

/// Extracts and parses all candidate trust-store certificates whose canonical subjects match the
/// transitive issuer closure.
///
/// Under OpenSSL `check_issued` semantics, candidate issuers are considered only when their
/// canonical subject matches the canonical issuer of the child certificate. The search pre-computes
/// the transitive closure of required subjects starting from the leaf and peer-sent intermediates
/// to avoid redundant parsing of unrelated trust certificates.
fn chain_inputs<'a>(
    leaf: &LegacyCert<'_>,
    sent: &[LegacyCert<'_>],
    inputs: &'a [TrustedCert],
) -> Result<Vec<(&'a TrustedCert, LegacyCert<'a>)>, V1Reject> {
    // Collect seed issuer names from the leaf and all peer-provided certificates.
    let mut names: Vec<Vec<u8>> = Vec::with_capacity(sent.len().saturating_add(1));
    add_name(&mut names, leaf.canonical_issuer());
    for cert in sent {
        add_name(&mut names, cert.canonical_issuer());
    }
    let mut parsed: Vec<Option<LegacyCert<'a>>> = inputs.iter().map(|_| None).collect();
    // Iteratively expand the closure as matching trust certificates contribute their own issuers.
    let mut next = 0usize;
    while next < names.len() {
        for (input, slot) in inputs.iter().zip(parsed.iter_mut()) {
            if slot.is_some()
                || names.get(next).map(Vec::as_slice) != Some(input.canonical_subject())
            {
                continue;
            }
            // Trust store entries are validated by TrustedCert; parse failure indicates
            // unexpected corruption.
            let cert = LegacyCert::parse(input.der().as_ref()).map_err(|_| V1Reject::NoIssuer)?;
            add_name(&mut names, cert.canonical_issuer());
            *slot = Some(cert);
        }
        next = next.saturating_add(1);
    }
    Ok(inputs
        .iter()
        .zip(parsed)
        .filter_map(|(input, cert)| cert.map(|cert| (input, cert)))
        .collect())
}

impl<'a> Search<'a> {
    /// Constructs a path search engine, pre-filtering candidate trust certificates and handling
    /// duplicate DERs.
    ///
    /// Duplicate DER certificates retain their initial entry, mirroring OpenSSL store behavior. If
    /// any copy is marked rejected, the candidate is treated as rejected to fail closed.
    /// Discrepancies in roles or auxiliary settings mark the candidate as conflicting.
    fn new(
        leaf: &'a LegacyCert<'a>,
        sent: &'a [LegacyCert<'a>],
        trust: &'a TrustSet,
        now: UnixTime,
        algs: &'a [&'a dyn SignatureVerificationAlgorithm],
    ) -> Result<Self, V1Reject> {
        let inputs = chain_inputs(leaf, sent, trust.certs())?;
        let mut list: Vec<Trusted<'a>> = Vec::with_capacity(inputs.len());
        for (input, cert) in inputs {
            let aux_trusts = input.aux().is_some_and(AuxTrust::trusts_server_auth);
            if let Some(first) = list.iter_mut().find(|t| t.cert.der() == cert.der()) {
                if input.role() == Role::Rejected {
                    first.role = Role::Rejected;
                } else if input.role() != first.role || aux_trusts != first.aux_trusts {
                    first.conflict = true;
                }
                first.origins.push(input.origin());
                continue;
            }
            list.push(Trusted {
                cert,
                role: input.role(),
                aux_trusts,
                origins: vec![input.origin()],
                conflict: false,
            });
        }
        Ok(Self {
            leaf,
            sent,
            trust: list,
            store: trust,
            now,
            algs,
        })
    }

    fn run(&self) -> Result<(), V1Reject> {
        let mut chain = vec![Link {
            cert: self.leaf,
            trusted: None,
        }];
        let mut budget = WORK_BUDGET;
        let mut loaded = Vec::new();
        self.extend(&mut chain, &mut budget, &mut loaded)
    }

    /// Recursively extends candidate certificate paths according to OpenSSL `trusted-first`
    /// semantics.
    ///
    /// Evaluates trust-store certificates matching the subject before considering peer-supplied
    /// intermediates. The `loaded` slice tracks directory hash groups dynamically resolved along
    /// the current search branch.
    fn extend<'s>(
        &'s self,
        chain: &mut Vec<Link<'s, 'a>>,
        budget: &mut usize,
        loaded: &mut Vec<usize>,
    ) -> Result<(), V1Reject> {
        let Some(top) = chain.last().copied() else {
            return Err(V1Reject::NoIssuer);
        };
        if chain.len().saturating_sub(1) > MAX_DEPTH {
            return Err(V1Reject::ChainTooLong);
        }
        let x = top.cert;
        // A self-signed top certificate has no parent issuer. If self-signed in the trust set, the
        // chain terminates in `step`. A self-signed peer certificate cannot have further issuers
        // above it (OpenSSL error 19).
        if x.openssl_self_signed() {
            return Err(V1Reject::NoIssuer);
        }
        // Query the trust store for certificates matching the issuer DN, activating corresponding
        // hash groups.
        self.store
            .lookup(&x.canonical_issuer(), loaded)
            .map_err(|_| V1Reject::Undecided)?;
        let issuers: Vec<&'s Trusted<'a>> = self
            .trust
            .iter()
            .filter(|t| {
                t.visible(loaded) && !in_chain(chain, t.cert.der()) && check_issued(&t.cert, x)
            })
            .collect();
        if issuers.iter().any(|t| t.conflict) {
            return Err(V1Reject::Undecided);
        }
        if !issuers.is_empty() {
            let valid: Vec<&'s Trusted<'a>> = issuers
                .into_iter()
                .filter(|t| t.cert.valid_at(self.now))
                .collect();
            return self.step_trusted(chain, &valid, budget, loaded);
        }
        // Once a trust-store certificate has been incorporated into the path, peer-sent
        // intermediates are rejected (error 2).
        if chain.iter().any(|l| l.trusted.is_some()) {
            return Err(V1Reject::NoIssuer);
        }
        let issuer = self.find_issuer(chain, x).ok_or(V1Reject::NoIssuer)?;
        self.step(
            chain,
            Link {
                cert: issuer,
                trusted: None,
            },
            budget,
            loaded,
        )
    }

    /// Selects candidate intermediate issuers from peer-supplied certificates matching OpenSSL
    /// `find_issuer`.
    ///
    /// Prefers the first time-valid candidate issuer; falls back to the candidate with the latest
    /// expiration.
    fn find_issuer<'s>(
        &'s self,
        chain: &[Link<'s, 'a>],
        x: &LegacyCert<'_>,
    ) -> Option<&'s LegacyCert<'a>> {
        let mut fallback: Option<&'s LegacyCert<'a>> = None;
        for c in self.sent {
            if in_chain(chain, c.der()) || !check_issued(c, x) {
                continue;
            }
            if c.valid_at(self.now) {
                return Some(c);
            }
            let later = fallback.is_none_or(|f| {
                c.validity().not_after.as_secs() > f.validity().not_after.as_secs()
            });
            if later {
                fallback = Some(c);
            }
        }
        fallback
    }

    /// Applies ambiguity resolution across time-valid trust-store candidate issuers.
    ///
    /// The candidate path is accepted if and only if every valid branch achieves acceptance;
    /// conflicting outcomes fail closed with `V1Reject::Ambiguous`.
    fn step_trusted<'s>(
        &'s self,
        chain: &mut Vec<Link<'s, 'a>>,
        valid: &[&'s Trusted<'a>],
        budget: &mut usize,
        loaded: &mut Vec<usize>,
    ) -> Result<(), V1Reject> {
        // If all candidate issuers in the trust store are expired, fail closed immediately.
        if valid.is_empty() {
            return Err(V1Reject::Expired);
        }
        let mut accepted = false;
        let mut first_error = None;
        for t in valid {
            let link = Link {
                cert: &t.cert,
                trusted: Some(*t),
            };
            let mark = loaded.len();
            let result = self.step(chain, link, budget, loaded);
            loaded.truncate(mark);
            match result {
                Ok(()) => accepted = true,
                Err(V1Reject::Budget) => return Err(V1Reject::Budget),
                Err(e) => {
                    first_error.get_or_insert(e);
                }
            }
        }
        match (accepted, first_error) {
            (true, None) => Ok(()),
            (false, Some(e)) => Err(e),
            _ => Err(V1Reject::Ambiguous),
        }
    }

    /// Appends candidate issuer `y` to the active chain after verifying key usage and cryptographic
    /// signatures.
    fn step<'s>(
        &'s self,
        chain: &mut Vec<Link<'s, 'a>>,
        y: Link<'s, 'a>,
        budget: &mut usize,
        loaded: &mut Vec<usize>,
    ) -> Result<(), V1Reject> {
        *budget = budget.checked_sub(1).ok_or(V1Reject::Budget)?;
        let role = y.trusted.map(|t| t.role);
        if role == Some(Role::Rejected) {
            return Err(V1Reject::Rejected);
        }
        if y.cert
            .key_usage()
            .is_some_and(|ku| ku & KU_KEY_CERT_SIGN == 0)
        {
            return Err(V1Reject::KeyUsage);
        }
        let Some(x) = chain.last() else {
            return Err(V1Reject::NoIssuer);
        };
        if !verify_sig(x.cert, y.cert, self.algs) {
            return Err(V1Reject::Signature);
        }
        chain.push(y);
        // Explicitly trusted anchors terminate path expansion (`check_trust`). LeafOnly inputs are
        // terminated here and evaluated in `finish`, where their lack of CA capabilities causes
        // rejection.
        let result = match role {
            Some(Role::Anchor | Role::LeafOnly) => self.finish(chain),
            _ => self.extend(chain, budget, loaded),
        };
        chain.pop();
        result
    }

    /// Evaluates terminal OpenSSL chain rules across the complete candidate path
    /// `[leaf, intermediates..., anchor]`.
    fn finish(&self, chain: &[Link<'_, 'a>]) -> Result<(), V1Reject> {
        let Some((anchor, below)) = chain.split_last() else {
            return Err(V1Reject::NoIssuer);
        };
        let intermediates = below.get(1..).unwrap_or_default();
        if chain.iter().any(|l| !l.cert.valid_at(self.now)) {
            return Err(V1Reject::Expired);
        }
        // OpenSSL 3 requires `X509_check_ca == 1` for all intermediates and non-zero for the trust
        // anchor.
        if intermediates.iter().any(|l| l.cert.openssl_check_ca() != 1) {
            return Err(V1Reject::InvalidCa);
        }
        if anchor.cert.openssl_check_ca() == 0 {
            return Err(V1Reject::InvalidCa);
        }
        // Check server authentication purpose constraints; auxiliary trust explicitly approving
        // serverAuth satisfies this.
        let purpose_ok =
            |l: &Link<'_, 'a>| l.trusted.is_some_and(|t| t.aux_trusts) || ca_purpose(l.cert);
        if !intermediates.iter().all(purpose_ok) || !purpose_ok(anchor) {
            return Err(V1Reject::Purpose);
        }
        check_path_length(chain)?;
        check_name_constraints(chain)?;
        if chain.iter().any(|l| has_unprocessed_critical(l.cert)) {
            return Err(V1Reject::CriticalExtension);
        }
        Ok(())
    }
}

/// Checks whether a certificate with identical DER encoding is already present in the active path.
fn in_chain(chain: &[Link<'_, '_>], der: &[u8]) -> bool {
    chain.iter().any(|l| l.cert.der() == der)
}

/// Determines whether a certificate is self-issued (`EXFLAG_SI`), matching canonical subject and
/// issuer names.
fn self_issued(cert: &LegacyCert<'_>) -> bool {
    cert.canonical_subject() == cert.canonical_issuer()
}

/// Evaluates CA server authentication purpose constraints (`check_purpose_ssl_server(x, ca = 1)`).
fn ca_purpose(cert: &LegacyCert<'_>) -> bool {
    if !cert.eku_allows_ssl_server() {
        return false;
    }
    match cert.openssl_check_ca() {
        0 => false,
        5 => cert.ns_cert_type().is_some_and(|ns| ns & NS_SSL_CA != 0),
        _ => true,
    }
}

/// Verifies whether certificate `x` is signed by the public key of certificate `y`.
///
/// Matches algorithms against both the signature algorithm identifier and public key parameters,
/// evaluating the first compatible match according to WebPKI selection rules.
fn verify_sig(
    x: &LegacyCert<'_>,
    y: &LegacyCert<'_>,
    algs: &[&dyn SignatureVerificationAlgorithm],
) -> bool {
    if !tbs_signature_matches(x) {
        return false;
    }
    let spki = y.spki();
    let sig_alg = x.signature_alg();
    algs.iter()
        .find(|a| {
            a.signature_alg_id().as_ref() == sig_alg
                && a.public_key_alg_id().as_ref() == spki.alg_id_value
        })
        .is_some_and(|a| {
            a.verify_signature(spki.key_bits, x.tbs(), x.signature())
                .is_ok()
        })
}

/// Confirms that the inner `tbsCertificate.signature` algorithm identifier matches the outer
/// `signatureAlgorithm` envelope.
fn tbs_signature_matches(x: &LegacyCert<'_>) -> bool {
    let inner = (|| {
        let tbs = single(x.tbs(), TAG_SEQUENCE)?;
        let mut r = DerReader::new(tbs);
        let mut field = r.read()?;
        if field.tag == 0xa0 {
            field = r.read()?;
        }
        if field.tag != TAG_INTEGER {
            return None;
        }
        r.read_tag(TAG_SEQUENCE)
    })();
    inner == Some(x.signature_alg())
}

/// Validates path-length constraints along the certificate chain matching OpenSSL `check_chain`.
///
/// Self-issued intermediate certificates are excluded from path length accumulation.
fn check_path_length(chain: &[Link<'_, '_>]) -> Result<(), V1Reject> {
    let mut plen: u64 = 0;
    for (i, link) in chain.iter().enumerate() {
        if i > 1 {
            let limit = link.cert.basic_constraints().and_then(|bc| bc.path_len);
            if limit.is_some_and(|max| plen > u64::from(max)) {
                return Err(V1Reject::PathLength);
            }
        }
        if i > 0 && !self_issued(link.cert) {
            plen = plen.saturating_add(1);
        }
    }
    Ok(())
}

/// Enforces hierarchical name constraints according to OpenSSL `check_name_constraints`.
///
/// Constraints defined by CA certificates apply to all non-self-issued subordinate certificates and
/// the leaf.
fn check_name_constraints(chain: &[Link<'_, '_>]) -> Result<(), V1Reject> {
    for (i, link) in chain.iter().enumerate() {
        if i != 0 && self_issued(link.cert) {
            continue;
        }
        let above = chain.get(i.saturating_add(1)..).unwrap_or_default();
        for ca in above {
            if let Some(nc) = ca.cert.name_constraints() {
                if !name_constraints_ok(link.cert, nc, i == 0) {
                    return Err(V1Reject::NameConstraints);
                }
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------
// Name constraints (OpenSSL v3_ncons.c).
// ---------------------------------------------------------------------

/// Represents an individual X.509 `GeneralName` choice alternative.
///
/// For `directoryName`, `value` retains the complete ASN.1 Name TLV; for other types, `value`
/// points to content bytes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct GeneralName<'a> {
    kind: u8,
    value: &'a [u8],
}

/// Represents an individual `GeneralSubtree` constraint boundary within a `NameConstraints`
/// extension.
#[derive(Clone, Copy, Debug)]
struct Subtree<'a> {
    base: GeneralName<'a>,
    /// Indicates whether `minimum` (must be zero) and `maximum` (must be absent) conform to
    /// supported OpenSSL semantics.
    minmax_valid: bool,
}

/// Parsed representation of an X.509 `nameConstraints` extension containing permitted and excluded
/// subtree collections.
#[derive(Debug, Default)]
struct NameConstraints<'a> {
    permitted: Vec<Subtree<'a>>,
    excluded: Vec<Subtree<'a>>,
}

/// Individual attribute-type-and-value component extracted from a distinguished name RDN.
#[derive(Clone, Copy, Debug)]
struct Attribute<'a> {
    oid: &'a [u8],
    tag: u8,
    content: &'a [u8],
}

/// Result of evaluating a `GeneralName` against a subtree constraint.
///
/// `Err(())` indicates an unsupported or malformed encoding causing chain rejection.
type MatchResult = Result<bool, ()>;

/// Validates certificate distinguished names and alternative names against a CA's `nameConstraints`
/// extension (`NAME_CONSTRAINTS_check`).
///
/// Enforces `NAME_CONSTRAINTS_check_CN` against common name hostnames when DNS SANs are absent.
fn name_constraints_ok(cert: &LegacyCert<'_>, nc: &[u8], is_leaf: bool) -> bool {
    let Some(nc) = parse_name_constraints(nc) else {
        return false;
    };
    let Some(subject) = parse_name(cert.subject()) else {
        return false;
    };
    let sans = match cert.subject_alt_name() {
        Some(san) => match parse_general_names(san) {
            Some(names) => names,
            None => return false,
        },
        None => Vec::new(),
    };
    let names = subject.len().saturating_add(sans.len());
    let constraints = nc.permitted.len().saturating_add(nc.excluded.len());
    if NAME_CHECK_MAX
        .checked_div(names)
        .is_some_and(|max| constraints > max)
    {
        return false;
    }
    if !subject.is_empty() {
        let dn = GeneralName {
            kind: GN_DIRECTORY,
            value: cert.subject(),
        };
        if !nc_match(&dn, &nc) {
            return false;
        }
        for attr in subject.iter().filter(|a| a.oid == OID_EMAIL_ADDRESS) {
            if attr.tag != TAG_IA5_STRING {
                return false;
            }
            let email = GeneralName {
                kind: GN_RFC822,
                value: attr.content,
            };
            if !nc_match(&email, &nc) {
                return false;
            }
        }
    }
    if !sans.iter().all(|g| nc_match(g, &nc)) {
        return false;
    }
    if is_leaf && !sans.iter().any(|g| g.kind == GN_DNS) {
        return check_cn(&subject, &nc);
    }
    true
}

/// Validates distinguished name `commonName` attributes with host-name syntax against name
/// constraints (`NAME_CONSTRAINTS_check_CN`).
fn check_cn(subject: &[Attribute<'_>], nc: &NameConstraints<'_>) -> bool {
    for attr in subject.iter().filter(|a| a.oid == OID_COMMON_NAME) {
        let Some(id) = cn_to_dns_id(attr.tag, attr.content) else {
            return false;
        };
        if id.is_empty() {
            continue;
        }
        let dns = GeneralName {
            kind: GN_DNS,
            value: &id,
        };
        if !nc_match(&dns, nc) {
            return false;
        }
    }
    true
}

/// Extracts a DNS host identifier from a `commonName` attribute (`cn2dnsid`).
///
/// Returns an empty identifier for CNs that do not resemble valid hostnames with two or more
/// labels; returns `None` for unparseable strings or embedded NUL bytes.
fn cn_to_dns_id(tag: u8, content: &[u8]) -> Option<Vec<u8>> {
    let mut utf8 = string_to_utf8(tag, content)?;
    while utf8.last() == Some(&0) {
        utf8.pop();
    }
    if utf8.contains(&0) {
        return None;
    }
    let Some(last) = utf8.len().checked_sub(1) else {
        return Some(Vec::new());
    };
    let mut is_dns = false;
    for (i, &c) in utf8.iter().enumerate() {
        if c.is_ascii_alphanumeric() || c == b'_' {
            continue;
        }
        if i > 0 && i < last {
            if c == b'-' {
                continue;
            }
            let prev = utf8.get(i.wrapping_sub(1)).copied();
            let next = utf8.get(i.wrapping_add(1)).copied();
            if c == b'.' && next != Some(b'.') && prev != Some(b'-') && next != Some(b'-') {
                is_dns = true;
                continue;
            }
        }
        is_dns = false;
        break;
    }
    Some(if is_dns { utf8 } else { Vec::new() })
}

/// Evaluates whether a candidate `GeneralName` satisfies the constraints defined in a
/// `NameConstraints` structure (`nc_match`).
///
/// A permitted constraint must match when defined; no excluded constraint may match.
fn nc_match(name: &GeneralName<'_>, nc: &NameConstraints<'_>) -> bool {
    let other_oid = if name.kind == GN_OTHER_NAME {
        match other_name_oid(name.value) {
            Some(oid) => Some(oid),
            None => return false,
        }
    } else {
        None
    };
    // RFC 8398: an SmtpUTF8Mailbox otherName is matched as an email address.
    let kind = if other_oid == Some(OID_SMTP_UTF8_MAILBOX) {
        GN_RFC822
    } else {
        name.kind
    };
    let applies = |sub: &Subtree<'_>| {
        sub.base.kind == kind
            && (kind != GN_OTHER_NAME || other_name_oid(sub.base.value) == other_oid)
    };
    let mut seen = false;
    let mut matched = false;
    for sub in nc.permitted.iter().filter(|s| applies(s)) {
        if !sub.minmax_valid {
            return false;
        }
        if matched {
            continue;
        }
        seen = true;
        match match_single(kind, name, &sub.base) {
            Ok(true) => matched = true,
            Ok(false) => {}
            Err(()) => return false,
        }
    }
    if seen && !matched {
        return false;
    }
    for sub in nc.excluded.iter().filter(|s| applies(s)) {
        if !sub.minmax_valid {
            return false;
        }
        match match_single(kind, name, &sub.base) {
            Ok(false) => {}
            Ok(true) | Err(()) => return false,
        }
    }
    true
}

/// Evaluates a single `GeneralName` against a subtree constraint base (`nc_match_single`).
fn match_single(kind: u8, name: &GeneralName<'_>, base: &GeneralName<'_>) -> MatchResult {
    match kind {
        // SmtpUTF8Mailbox encountering an rfc822Name subtree: fail closed as nc_email_eai is
        // unsupported.
        GN_RFC822 if name.kind == GN_OTHER_NAME => Err(()),
        GN_RFC822 => nc_email(name.value, base.value),
        GN_DNS => Ok(nc_dns(name.value, base.value)),
        GN_DIRECTORY => nc_dn(name.value, base.value),
        GN_URI => nc_uri(name.value, base.value),
        GN_IP => nc_ip(name.value, base.value),
        _ => Err(()),
    }
}

/// Compares directory names against a subtree constraint (`nc_dn`), requiring the canonical base to
/// form a prefix.
fn nc_dn(name: &[u8], base: &[u8]) -> MatchResult {
    let name = canonical_name(name).ok_or(())?;
    let base = canonical_name(base).ok_or(())?;
    Ok(name.starts_with(&base))
}

/// Evaluates DNS host names against a subtree constraint (`nc_dns`), supporting case-insensitive
/// prefix and suffix label matching.
fn nc_dns(dns: &[u8], base: &[u8]) -> bool {
    if base.is_empty() {
        return true;
    }
    let Some(start) = dns.len().checked_sub(base.len()) else {
        return false;
    };
    let Some(tail) = dns.get(start..) else {
        return false;
    };
    if start > 0 {
        let before = dns.get(start.wrapping_sub(1)).copied();
        if base.first() != Some(&b'.') && before != Some(b'.') {
            return false;
        }
    }
    ia5_case_eq(tail, base)
}

/// Evaluates RFC 822 email addresses against an email subtree constraint (`nc_email`).
fn nc_email(email: &[u8], base: &[u8]) -> MatchResult {
    let Some(email_at) = email.iter().rposition(|&b| b == b'@') else {
        return Err(());
    };
    let base_at = base.iter().rposition(|&b| b == b'@');
    // A constraint base beginning with '.' without '@' matches domain suffixes.
    if base_at.is_none() && base.first() == Some(&b'.') {
        if email.len() > base.len() {
            let start = email.len().saturating_sub(base.len());
            let tail = email.get(start..).ok_or(())?;
            return Ok(ia5_case_eq(tail, base));
        }
        return Ok(false);
    }
    let mut base_host = base;
    if let Some(at) = base_at {
        if at != 0 {
            let base_local = base.get(..at).ok_or(())?;
            let email_local = email.get(..email_at).ok_or(())?;
            if base_local.len() != email_local.len() {
                return Ok(false);
            }
            if base_local.contains(&0) || email_local.contains(&0) {
                return Err(());
            }
            // Local mailbox names are compared case-sensitively.
            if base_local != email_local {
                return Ok(false);
            }
        }
        base_host = base.get(at.saturating_add(1)..).ok_or(())?;
    }
    let email_host = email.get(email_at.saturating_add(1)..).ok_or(())?;
    Ok(ia5_case_eq(base_host, email_host))
}

/// Performs case-insensitive equality comparison across two ASCII/IA5 byte sequences
/// (`ia5ncasecmp`), terminating at common NUL delimiters.
fn ia5_case_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    for (x, y) in a.iter().zip(b) {
        if !x.eq_ignore_ascii_case(y) {
            return false;
        }
        if *x == 0 {
            return true;
        }
    }
    true
}

/// Evaluates uniform resource identifiers against a URI subtree constraint (`nc_uri`), extracting
/// and comparing host portions.
fn nc_uri(uri: &[u8], base: &[u8]) -> MatchResult {
    let colon = uri.iter().position(|&b| b == b':').ok_or(())?;
    let after = uri.get(colon..).ok_or(())?;
    if after.len() < 3 || after.get(1) != Some(&b'/') || after.get(2) != Some(&b'/') {
        return Err(());
    }
    let host_part = after.get(3..).ok_or(())?;
    let end = host_part
        .iter()
        .position(|&b| b == b':')
        .or_else(|| host_part.iter().position(|&b| b == b'/'))
        .unwrap_or(host_part.len());
    let host = host_part.get(..end).ok_or(())?;
    if host.is_empty() {
        return Err(());
    }
    if base.first() == Some(&b'.') {
        if host.len() > base.len() {
            let start = host.len().saturating_sub(base.len());
            let tail = host.get(start..).ok_or(())?;
            return Ok(ia5_case_eq(tail, base));
        }
        return Ok(false);
    }
    Ok(ia5_case_eq(host, base))
}

/// Evaluates IP address candidates against an IP address and subnet mask constraint pair (`nc_ip`).
fn nc_ip(ip: &[u8], base: &[u8]) -> MatchResult {
    if ip.len() != 4 && ip.len() != 16 {
        return Err(());
    }
    if base.len() != 8 && base.len() != 32 {
        return Err(());
    }
    let Some((addr, mask)) = base.split_at_checked(ip.len()) else {
        return Ok(false);
    };
    if mask.len() != ip.len() {
        return Ok(false);
    }
    Ok(ip
        .iter()
        .zip(addr)
        .zip(mask)
        .all(|((h, b), m)| h & m == b & m))
}

/// Extracts the type OID from an ASN.1 `otherName` value sequence.
fn other_name_oid(value: &[u8]) -> Option<&[u8]> {
    DerReader::new(value).read_tag(TAG_OID)
}

/// Parses an ASN.1 DER `nameConstraints` extension sequence into permitted and excluded subtree
/// collections.
fn parse_name_constraints(bytes: &[u8]) -> Option<NameConstraints<'_>> {
    let content = if bytes.first() == Some(&TAG_SEQUENCE) {
        single(bytes, TAG_SEQUENCE)?
    } else {
        bytes
    };
    let mut nc = NameConstraints::default();
    let mut r = DerReader::new(content);
    while !r.is_empty() {
        let field = r.read()?;
        let list = match field.tag {
            0xa0 => &mut nc.permitted,
            0xa1 => &mut nc.excluded,
            _ => return None,
        };
        let mut subtrees = DerReader::new(field.content);
        while !subtrees.is_empty() {
            list.push(parse_subtree(subtrees.read_tag(TAG_SEQUENCE)?)?);
        }
    }
    Some(nc)
}

/// Decodes an individual `GeneralSubtree` record, evaluating minimum and maximum bounds.
fn parse_subtree(content: &[u8]) -> Option<Subtree<'_>> {
    let mut r = DerReader::new(content);
    let base = general_name(r.read()?)?;
    let mut minmax_valid = true;
    while !r.is_empty() {
        let field = r.read()?;
        match field.tag {
            // Context tag [0] minimum: OpenSSL supports only an explicit value of zero.
            0x80 => minmax_valid &= field.content == [0],
            // Context tag [1] maximum: OpenSSL rejects extensions specifying a maximum distance.
            0x81 => minmax_valid = false,
            _ => return None,
        }
    }
    Some(Subtree { base, minmax_valid })
}

/// Decodes an ASN.1 `GeneralNames` sequence into a vector of [`GeneralName`] instances.
fn parse_general_names(bytes: &[u8]) -> Option<Vec<GeneralName<'_>>> {
    let content = if bytes.first() == Some(&TAG_SEQUENCE) {
        single(bytes, TAG_SEQUENCE)?
    } else {
        bytes
    };
    let mut names = Vec::new();
    let mut r = DerReader::new(content);
    while !r.is_empty() {
        names.push(general_name(r.read()?)?);
    }
    Some(names)
}

/// Decodes an individual `GeneralName` context-tagged TLV according to RFC 5280.
fn general_name(tlv: Tlv<'_>) -> Option<GeneralName<'_>> {
    let kind = tlv.tag & 0x1f;
    let expected = match kind {
        0 | 3 | 5 => 0xa0 | kind,
        4 => 0xa4,
        1 | 2 | 6 | 7 | 8 => 0x80 | kind,
        _ => return None,
    };
    if tlv.tag != expected {
        return None;
    }
    if kind == GN_OTHER_NAME && !valid_other_name(tlv.content) {
        return None;
    }
    let value = if kind == GN_DIRECTORY {
        let mut r = DerReader::new(tlv.content);
        let name = r.read()?;
        if name.tag != TAG_SEQUENCE || !r.is_empty() {
            return None;
        }
        name.whole
    } else {
        tlv.content
    };
    Some(GeneralName { kind, value })
}

/// Validates that an `otherName` payload matches `type-id OID, [0] EXPLICIT value`.
fn valid_other_name(content: &[u8]) -> bool {
    let mut r = DerReader::new(content);
    let oid = r.read_tag(TAG_OID).is_some();
    let value = r.read().is_some_and(|v| v.tag == 0xa0);
    oid && value && r.is_empty()
}

/// Parses an ASN.1 `Name` sequence into an ordered collection of distinguished name attributes.
fn parse_name(name: &[u8]) -> Option<Vec<Attribute<'_>>> {
    let mut attrs = Vec::new();
    let mut rdns = DerReader::new(single(name, TAG_SEQUENCE)?);
    while !rdns.is_empty() {
        let mut set = DerReader::new(rdns.read_tag(TAG_SET)?);
        if set.is_empty() {
            return None;
        }
        while !set.is_empty() {
            let mut atv = DerReader::new(set.read_tag(TAG_SEQUENCE)?);
            let oid = atv.read_tag(TAG_OID)?;
            let value = atv.read()?;
            if !atv.is_empty() {
                return None;
            }
            attrs.push(Attribute {
                oid,
                tag: value.tag,
                content: value.content,
            });
        }
    }
    Some(attrs)
}

// ---------------------------------------------------------------------
// OpenSSL canonical name form (x509_name_canon, asn1_string_canon).
// ---------------------------------------------------------------------

/// Produces the OpenSSL canonical byte encoding for an ASN.1 distinguished name
/// (`x509_name_canon`).
///
/// Normalizes strings according to `asn1_string_canon`, sorts multi-valued attribute sets in DER
/// order, and concatenates the resulting SETs omitting the outer SEQUENCE envelope.
pub(crate) fn canonical_name(name: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let mut rdns = DerReader::new(single(name, TAG_SEQUENCE)?);
    while !rdns.is_empty() {
        let mut set = DerReader::new(rdns.read_tag(TAG_SET)?);
        if set.is_empty() {
            return None;
        }
        let mut atvs: Vec<Vec<u8>> = Vec::new();
        while !set.is_empty() {
            let mut atv = DerReader::new(set.read_tag(TAG_SEQUENCE)?);
            let oid = atv.read()?;
            let value = atv.read()?;
            if oid.tag != TAG_OID || !atv.is_empty() {
                return None;
            }
            let mut body = oid.whole.to_vec();
            if is_canon_string(value.tag) {
                let text = canonical_string(&string_to_utf8(value.tag, value.content)?);
                push_tlv(&mut body, TAG_UTF8_STRING, &text);
            } else {
                body.extend_from_slice(value.whole);
            }
            let mut encoded = Vec::new();
            push_tlv(&mut encoded, TAG_SEQUENCE, &body);
            atvs.push(encoded);
        }
        // DER requires sorting elements of a SET OF by their raw DER encodings.
        atvs.sort();
        push_tlv(&mut out, TAG_SET, &atvs.concat());
    }
    Some(out)
}

/// Identifies ASN.1 string types subject to canonicalization under `asn1_string_canon`
/// (`ASN1_MASK_CANON`).
fn is_canon_string(tag: u8) -> bool {
    matches!(tag, 0x0c | 0x13 | 0x14 | 0x16 | 0x1a | 0x1c | 0x1e)
}

/// Checks whether a byte represents ASCII whitespace matching OpenSSL `ossl_isspace`.
fn is_space(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r')
}

/// Canonicalizes UTF-8 strings according to `asn1_string_canon` rules.
///
/// Trims surrounding whitespace, collapses internal whitespace runs into a single space, and
/// lowercases ASCII letters.
fn canonical_string(utf8: &[u8]) -> Vec<u8> {
    let start = utf8
        .iter()
        .position(|&b| !is_space(b))
        .unwrap_or(utf8.len());
    let end = utf8
        .iter()
        .rposition(|&b| !is_space(b))
        .map_or(start, |p| p.saturating_add(1));
    let trimmed = utf8.get(start..end).unwrap_or_default();
    let mut out = Vec::with_capacity(trimmed.len());
    let mut in_space = false;
    for &b in trimmed {
        if is_space(b) {
            if !in_space {
                out.push(b' ');
            }
            in_space = true;
        } else {
            in_space = false;
            out.push(b.to_ascii_lowercase());
        }
    }
    out
}

/// Decodes ASN.1 string types into standard UTF-8 byte sequences (`ASN1_STRING_to_UTF8`).
fn string_to_utf8(tag: u8, content: &[u8]) -> Option<Vec<u8>> {
    match tag {
        0x0c => core::str::from_utf8(content)
            .ok()
            .map(|s| s.as_bytes().to_vec()),
        0x12 | 0x13 | 0x14 | 0x16 | 0x17 | 0x18 | 0x1a => Some(
            content
                .iter()
                .map(|&b| char::from(b))
                .collect::<String>()
                .into_bytes(),
        ),
        0x1e => wide_to_utf8(content, 2),
        0x1c => wide_to_utf8(content, 4),
        _ => None,
    }
}

/// Decodes big-endian UCS-2 or UCS-4 wide character byte sequences to UTF-8.
///
/// Rejects unpaired surrogates and code points exceeding U+10FFFF.
fn wide_to_utf8(content: &[u8], width: usize) -> Option<Vec<u8>> {
    let chunks = content.chunks_exact(width);
    if !chunks.remainder().is_empty() {
        return None;
    }
    let mut s = String::with_capacity(content.len());
    for chunk in chunks {
        let value = chunk
            .iter()
            .fold(0_u32, |acc, &b| (acc << 8) | u32::from(b));
        s.push(char::from_u32(value)?);
    }
    Some(s.into_bytes())
}

// ---------------------------------------------------------------------
// Bounded zero-allocation DER reader.
// ---------------------------------------------------------------------

/// Represents an individual ASN.1 DER tag-length-value element.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Tlv<'a> {
    pub(crate) tag: u8,
    pub(crate) content: &'a [u8],
    pub(crate) whole: &'a [u8],
}

/// Bounded zero-allocation DER parser accepting single-byte tags and minimal definite lengths up to
/// 4 bytes.
#[derive(Clone, Copy, Debug)]
pub(crate) struct DerReader<'a> {
    rest: &'a [u8],
}

impl<'a> DerReader<'a> {
    pub(crate) fn new(bytes: &'a [u8]) -> Self {
        Self { rest: bytes }
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.rest.is_empty()
    }

    /// Reads the next DER TLV element from the buffer, returning `None` on truncation or invalid
    /// encoding.
    pub(crate) fn read(&mut self) -> Option<Tlv<'a>> {
        let (&tag, after_tag) = self.rest.split_first()?;
        if tag & 0x1f == 0x1f {
            return None;
        }
        let (&first, mut body) = after_tag.split_first()?;
        let len = if first < 0x80 {
            usize::from(first)
        } else {
            let count = usize::from(first & 0x7f);
            if count == 0 || count > 4 {
                return None;
            }
            let (bytes, rest) = body.split_at_checked(count)?;
            body = rest;
            if bytes.first() == Some(&0) {
                return None;
            }
            let len = bytes.iter().try_fold(0_usize, |acc, &b| {
                acc.checked_mul(256)?.checked_add(usize::from(b))
            })?;
            if len < 0x80 {
                return None;
            }
            len
        };
        let (content, rest) = body.split_at_checked(len)?;
        let whole_len = self.rest.len().checked_sub(rest.len())?;
        let whole = self.rest.get(..whole_len)?;
        self.rest = rest;
        Some(Tlv {
            tag,
            content,
            whole,
        })
    }

    /// Reads the next DER TLV element and returns its payload if the tag matches `tag`.
    pub(crate) fn read_tag(&mut self, tag: u8) -> Option<&'a [u8]> {
        let tlv = self.read()?;
        (tlv.tag == tag).then_some(tlv.content)
    }
}

/// Extracts the payload of an isolated DER TLV matching the specified tag, ensuring no trailing
/// bytes exist.
pub(crate) fn single(bytes: &[u8], tag: u8) -> Option<&[u8]> {
    let mut r = DerReader::new(bytes);
    let content = r.read_tag(tag)?;
    r.is_empty().then_some(content)
}

/// Appends an ASN.1 DER tag and minimal definite-length encoding to the target buffer.
fn push_tlv(out: &mut Vec<u8>, tag: u8, content: &[u8]) {
    out.push(tag);
    let len = content.len();
    if len < 0x80 {
        // Single-byte short length encoding.
        out.push(u8::try_from(len).unwrap_or_default());
    } else {
        let bytes = len.to_be_bytes();
        let skip = bytes.iter().take_while(|&&b| b == 0).count();
        let digits = bytes.get(skip..).unwrap_or_default();
        out.push(0x80 | u8::try_from(digits.len()).unwrap_or_default());
        out.extend_from_slice(digits);
    }
    out.extend_from_slice(content);
}

#[cfg(test)]
#[path = "search_diff_tests.rs"]
mod search_diff_tests;

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

    const TAG_PRINTABLE: u8 = 0x13;
    const TAG_BMP: u8 = 0x1e;
    const TAG_UNIVERSAL: u8 = 0x1c;

    fn der(tag: u8, content: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        push_tlv(&mut out, tag, content);
        out
    }

    fn atv(oid: &[u8], tag: u8, value: &[u8]) -> Vec<u8> {
        der(TAG_SEQUENCE, &[der(TAG_OID, oid), der(tag, value)].concat())
    }

    /// Constructs a synthetic Name structure containing one RDN per attribute list.
    fn name(rdns: &[&[Vec<u8>]]) -> Vec<u8> {
        let sets: Vec<u8> = rdns
            .iter()
            .flat_map(|r| der(TAG_SET, &r.concat()))
            .collect();
        der(TAG_SEQUENCE, &sets)
    }

    fn cn(tag: u8, value: &[u8]) -> Vec<u8> {
        name(&[&[atv(OID_COMMON_NAME, tag, value)]])
    }

    fn subtree(base: Vec<u8>, extra: &[u8]) -> Vec<u8> {
        der(TAG_SEQUENCE, &[base, extra.to_vec()].concat())
    }

    fn constraints(permitted: &[Vec<u8>], excluded: &[Vec<u8>]) -> Vec<u8> {
        let mut body = Vec::new();
        if !permitted.is_empty() {
            push_tlv(&mut body, 0xa0, &permitted.concat());
        }
        if !excluded.is_empty() {
            push_tlv(&mut body, 0xa1, &excluded.concat());
        }
        der(TAG_SEQUENCE, &body)
    }

    fn dns(host: &[u8]) -> GeneralName<'_> {
        GeneralName {
            kind: GN_DNS,
            value: host,
        }
    }

    #[test]
    fn der_reader_reads_short_and_long_lengths() {
        for len in [0, 1, 127, 128, 255, 256, 70_000] {
            let content = vec![0x5a; len];
            let mut bytes = der(0x04, &content);
            bytes.push(0x05);
            bytes.push(0x00);
            let mut r = DerReader::new(&bytes);
            let tlv = r.read().unwrap();
            assert_eq!(tlv.tag, 0x04);
            assert_eq!(tlv.content, &content[..]);
            assert_eq!(tlv.whole.len(), bytes.len() - 2);
            assert_eq!(r.read_tag(0x05), Some(&[][..]));
            assert!(r.is_empty());
        }
    }

    #[test]
    fn der_reader_rejects_non_der_lengths_and_truncation() {
        let bad: &[&[u8]] = &[
            &[],
            &[0x04],
            &[0x04, 0x02, 0x00],
            &[0x04, 0x80, 0x00, 0x00],
            &[0x04, 0x81, 0x05, 0, 0, 0, 0, 0],
            &[0x04, 0x82, 0x00, 0x80],
            &[0x04, 0x85, 0x01, 0x00, 0x00, 0x00, 0x00],
            &[0x1f, 0x01, 0x00],
        ];
        for b in bad {
            assert_eq!(DerReader::new(b).read(), None, "{b:02x?}");
        }
    }

    #[test]
    fn single_takes_exactly_one_tlv_with_the_tag() {
        assert_eq!(single(&[0x30, 0x01, 0x05], TAG_SEQUENCE), Some(&[0x05][..]));
        assert_eq!(single(&[0x30, 0x00, 0x05, 0x00], TAG_SEQUENCE), None);
        assert_eq!(single(&[0x31, 0x00], TAG_SEQUENCE), None);
    }

    #[test]
    fn canonical_name_ignores_case_spacing_and_string_type() {
        let a = canonical_name(&cn(TAG_UTF8_STRING, b"Corpus Canon")).unwrap();
        for variant in [
            cn(TAG_UTF8_STRING, b"CORPUS CANON"),
            cn(TAG_UTF8_STRING, b"  Corpus \t\n Canon "),
            cn(TAG_PRINTABLE, b"Corpus Canon"),
            cn(
                TAG_BMP,
                &[
                    0, b'c', 0, b'O', 0, b'R', 0, b'P', 0, b'U', 0, b'S', 0, b' ', 0, b'c', 0,
                    b'a', 0, b'n', 0, b'o', 0, b'n',
                ],
            ),
        ] {
            assert_eq!(canonical_name(&variant).unwrap(), a);
        }
        assert_ne!(
            canonical_name(&cn(TAG_UTF8_STRING, b"Corpus Canan")).unwrap(),
            a
        );
        assert_ne!(
            canonical_name(&cn(TAG_UTF8_STRING, b"CorpusCanon")).unwrap(),
            a
        );
    }

    #[test]
    fn canonical_name_sorts_multi_valued_rdns_and_keeps_rdn_order() {
        let o = atv(&[0x55, 0x04, 0x0a], TAG_UTF8_STRING, b"Org");
        let c = atv(OID_COMMON_NAME, TAG_UTF8_STRING, b"Name");
        let ab = canonical_name(&name(&[&[o.clone(), c.clone()]])).unwrap();
        let ba = canonical_name(&name(&[&[c.clone(), o.clone()]])).unwrap();
        assert_eq!(ab, ba);
        let split =
            canonical_name(&name(&[std::slice::from_ref(&o), std::slice::from_ref(&c)])).unwrap();
        let swapped = canonical_name(&name(&[&[c], &[o]])).unwrap();
        assert_ne!(split, swapped);
        assert_ne!(split, ab);
    }

    #[test]
    fn canonical_name_keeps_other_value_types_and_rejects_malformed_names() {
        let octets = name(&[&[atv(OID_COMMON_NAME, 0x04, b"ABC")]]);
        let canon = canonical_name(&octets).unwrap();
        assert!(
            canon
                .windows(5)
                .any(|w| w == [0x04, 0x03, b'A', b'B', b'C'])
        );
        assert_eq!(canonical_name(&der(TAG_SEQUENCE, &der(TAG_SET, &[]))), None);
        let no_oid = der(
            TAG_SEQUENCE,
            &der(TAG_SET, &der(TAG_SEQUENCE, &der(TAG_UTF8_STRING, b"x"))),
        );
        assert_eq!(canonical_name(&no_oid), None);
        assert_eq!(canonical_name(&cn(TAG_UTF8_STRING, &[0xff, 0xfe])), None);
        assert_eq!(canonical_name(&[0x30, 0x05]), None);
    }

    #[test]
    fn string_to_utf8_converts_one_byte_and_wide_strings() {
        assert_eq!(string_to_utf8(0x14, &[0xe9]).unwrap(), "\u{e9}".as_bytes());
        assert_eq!(
            string_to_utf8(TAG_BMP, &[0x00, 0x41, 0x00, 0xe9]).unwrap(),
            "A\u{e9}".as_bytes()
        );
        assert_eq!(string_to_utf8(TAG_BMP, &[0xd8, 0x00]), None);
        assert_eq!(string_to_utf8(TAG_BMP, &[0x00]), None);
        assert_eq!(
            string_to_utf8(TAG_UNIVERSAL, &[0x00, 0x01, 0xf6, 0x00]).unwrap(),
            "\u{1f600}".as_bytes()
        );
        assert_eq!(
            string_to_utf8(TAG_UNIVERSAL, &[0x00, 0x11, 0x00, 0x00]),
            None
        );
        assert_eq!(string_to_utf8(0x04, b"x"), None);
    }

    #[test]
    fn canonical_string_trims_collapses_and_lowers() {
        assert_eq!(canonical_string(b"\t A  B\n\x0bC "), b"a b c");
        assert_eq!(canonical_string(b" \t "), b"");
        assert_eq!(canonical_string("\u{c9}X".as_bytes()), "\u{c9}x".as_bytes());
    }

    #[test]
    fn cn_to_dns_id_takes_only_host_names_with_two_labels() {
        let id = |s: &[u8]| cn_to_dns_id(TAG_UTF8_STRING, s);
        assert_eq!(id(b"host.example").unwrap(), b"host.example");
        assert_eq!(id(b"a-b.c_d.e").unwrap(), b"a-b.c_d.e");
        assert_eq!(id(b"host.example\0\0").unwrap(), b"host.example");
        for not_dns in [
            &b"localhost"[..],
            b"a..b",
            b".a.b",
            b"a.b.",
            b"a-.b",
            b"a.-b",
            b"-a.b",
            b"a b.c",
            b"",
        ] {
            assert_eq!(id(not_dns).unwrap(), b"", "{not_dns:?}");
        }
        assert_eq!(id(b"a\0b.c"), None);
        assert_eq!(cn_to_dns_id(TAG_UTF8_STRING, &[0xff]), None);
    }

    #[test]
    fn nc_dns_matches_suffix_labels_without_case() {
        assert!(nc_dns(b"any.test", b""));
        assert!(nc_dns(b"www.example.com", b"example.com"));
        assert!(nc_dns(b"WWW.Example.COM", b"example.com"));
        assert!(nc_dns(b"example.com", b"example.com"));
        assert!(!nc_dns(b"badexample.com", b"example.com"));
        assert!(nc_dns(b"www.example.com", b".example.com"));
        assert!(!nc_dns(b"example.com", b".example.com"));
        assert!(!nc_dns(b"example.org", b"example.com"));
    }

    #[test]
    fn nc_email_matches_host_domain_and_mailbox() {
        assert_eq!(nc_email(b"a@x.other.test", b".other.test"), Ok(true));
        assert_eq!(nc_email(b"a@other.test", b".other.test"), Ok(false));
        assert_eq!(nc_email(b"a@Other.Test", b"other.test"), Ok(true));
        assert_eq!(nc_email(b"a@x.other.test", b"other.test"), Ok(false));
        assert_eq!(nc_email(b"a@other.test", b"a@OTHER.test"), Ok(true));
        assert_eq!(nc_email(b"A@other.test", b"a@other.test"), Ok(false));
        assert_eq!(nc_email(b"ab@other.test", b"a@other.test"), Ok(false));
        assert_eq!(nc_email(b"b@other.test", b"@other.test"), Ok(true));
        assert_eq!(nc_email(b"a\0@other.test", b"a\0@other.test"), Err(()));
        assert_eq!(nc_email(b"no-at-sign", b"other.test"), Err(()));
    }

    #[test]
    fn nc_uri_matches_the_host_part() {
        assert_eq!(
            nc_uri(b"https://host.example:443/p", b"host.example"),
            Ok(true)
        );
        assert_eq!(nc_uri(b"https://HOST.example/p", b"host.example"), Ok(true));
        assert_eq!(
            nc_uri(b"https://www.host.example", b".host.example"),
            Ok(true)
        );
        assert_eq!(nc_uri(b"https://host.example", b".host.example"), Ok(false));
        assert_eq!(nc_uri(b"https://other.example", b"host.example"), Ok(false));
        for bad in [
            &b"https:/x"[..],
            b"https://",
            b"https://:1",
            b"mailto:x",
            b"none",
        ] {
            assert_eq!(nc_uri(bad, b"x"), Err(()), "{bad:?}");
        }
    }

    #[test]
    fn nc_ip_matches_address_and_mask_of_one_family() {
        let base = [192, 0, 2, 0, 255, 255, 255, 0];
        assert_eq!(nc_ip(&[192, 0, 2, 1], &base), Ok(true));
        assert_eq!(nc_ip(&[198, 51, 100, 1], &base), Ok(false));
        assert_eq!(nc_ip(&[0; 16], &base), Ok(false));
        assert_eq!(nc_ip(&[192, 0, 2, 1], &[0; 32]), Ok(false));
        assert_eq!(nc_ip(&[0; 16], &[0; 32]), Ok(true));
        assert_eq!(nc_ip(&[0; 5], &base), Err(()));
        assert_eq!(nc_ip(&[192, 0, 2, 1], &[0; 7]), Err(()));
    }

    #[test]
    fn ia5_case_eq_stops_at_a_shared_nul() {
        assert!(ia5_case_eq(b"AbC", b"aBc"));
        assert!(ia5_case_eq(b"a\0x", b"A\0y"));
        assert!(!ia5_case_eq(b"a\0x", b"ab\0"));
        assert!(!ia5_case_eq(b"ab", b"abc"));
    }

    #[test]
    fn parse_name_constraints_reads_subtrees_and_min_max() {
        let ok = subtree(der(0x82, b"ok.test"), &[]);
        let zero_min = subtree(der(0x82, b"bad.ok.test"), &der(0x80, &[0]));
        let nc_der = constraints(std::slice::from_ref(&ok), &[zero_min]);
        let nc = parse_name_constraints(&nc_der).unwrap();
        assert_eq!(nc.permitted.len(), 1);
        assert_eq!(nc.excluded.len(), 1);
        assert!(nc.permitted[0].minmax_valid && nc.excluded[0].minmax_valid);
        assert_eq!(nc.permitted[0].base.value, b"ok.test");
        // The inner extension payload parses identically when stripped of its outer SEQUENCE
        // envelope.
        assert!(parse_name_constraints(single(&nc_der, TAG_SEQUENCE).unwrap()).is_some());

        let one_min = subtree(der(0x82, b"ok.test"), &der(0x80, &[1]));
        let max = subtree(der(0x82, b"ok.test"), &der(0x81, &[3]));
        for sub in [one_min, max] {
            let nc_der = constraints(&[sub], &[]);
            assert!(!parse_name_constraints(&nc_der).unwrap().permitted[0].minmax_valid);
        }
        let mut wrong_field = Vec::new();
        push_tlv(&mut wrong_field, 0xa2, &ok);
        assert!(parse_name_constraints(&der(TAG_SEQUENCE, &wrong_field)).is_none());
        let constructed_dns = subtree(der(0xa2, b"ok.test"), &[]);
        assert!(parse_name_constraints(&constraints(&[constructed_dns], &[])).is_none());
    }

    #[test]
    fn general_name_takes_rfc_5280_encodings_only() {
        let dn = cn(TAG_UTF8_STRING, b"x");
        fn gn(b: &[u8]) -> Option<GeneralName<'_>> {
            general_name(DerReader::new(b).read().unwrap())
        }
        assert_eq!(gn(&der(0xa4, &dn)).unwrap().value, &dn[..]);
        assert!(gn(&der(0xa4, &[dn.clone(), dn.clone()].concat())).is_none());
        assert!(gn(&der(0x84, &dn)).is_none());
        assert!(gn(&der(0x81, b"a@b")).is_some());
        assert!(gn(&der(0x89, b"x")).is_none());
        let other = [
            der(TAG_OID, OID_SMTP_UTF8_MAILBOX),
            der(0xa0, &der(TAG_UTF8_STRING, b"a@b")),
        ]
        .concat();
        assert_eq!(gn(&der(0xa0, &other)).unwrap().kind, GN_OTHER_NAME);
        assert!(gn(&der(0xa0, &der(TAG_OID, OID_SMTP_UTF8_MAILBOX))).is_none());
        let seq = der(
            TAG_SEQUENCE,
            &[der(0x82, b"a.test"), der(0x87, &[1, 2, 3, 4])].concat(),
        );
        let names = parse_general_names(&seq).unwrap();
        assert_eq!(names.len(), 2);
        assert_eq!(names[1].kind, GN_IP);
    }

    #[test]
    fn nc_match_applies_permitted_then_excluded_subtrees_of_the_same_type() {
        let nc_der = constraints(
            &[subtree(der(0x82, b".ok.test"), &[])],
            &[subtree(der(0x82, b"bad.ok.test"), &[])],
        );
        let nc = parse_name_constraints(&nc_der).unwrap();
        assert!(nc_match(&dns(b"www.ok.test"), &nc));
        assert!(!nc_match(&dns(b"www.other.test"), &nc));
        assert!(!nc_match(&dns(b"bad.ok.test"), &nc));
        assert!(!nc_match(&dns(b"x.bad.ok.test"), &nc));
        // In the absence of explicit IP constraints, candidate IP GeneralNames evaluate as
        // permitted.
        let ip = GeneralName {
            kind: GN_IP,
            value: &[192, 0, 2, 1],
        };
        assert!(nc_match(&ip, &nc));

        let bad_minmax = constraints(&[subtree(der(0x82, b"ok.test"), &der(0x80, &[1]))], &[]);
        assert!(!nc_match(
            &dns(b"ok.test"),
            &parse_name_constraints(&bad_minmax).unwrap()
        ));
    }

    #[test]
    fn nc_match_fails_closed_for_smtp_utf8_mailboxes_under_email_subtrees() {
        let value = [
            der(TAG_OID, OID_SMTP_UTF8_MAILBOX),
            der(0xa0, &der(TAG_UTF8_STRING, b"a@ok.test")),
        ]
        .concat();
        let mailbox = GeneralName {
            kind: GN_OTHER_NAME,
            value: &value,
        };
        let email_der = constraints(&[subtree(der(0x81, b"ok.test"), &[])], &[]);
        let email = parse_name_constraints(&email_der).unwrap();
        assert!(!nc_match(&mailbox, &email));
        let dns_der = constraints(&[subtree(der(0x82, b"ok.test"), &[])], &[]);
        let dns_only = parse_name_constraints(&dns_der).unwrap();
        assert!(nc_match(&mailbox, &dns_only));
        let broken = GeneralName {
            kind: GN_OTHER_NAME,
            value: &[0x05, 0x00],
        };
        assert!(!nc_match(&broken, &dns_only));
    }

    #[test]
    fn nc_dn_compares_canonical_prefixes() {
        let o = atv(&[0x55, 0x04, 0x0a], TAG_UTF8_STRING, b"Corpus");
        let c = atv(OID_COMMON_NAME, TAG_UTF8_STRING, b"leaf");
        let base = name(&[&[o]]);
        let full = name(&[
            &[atv(&[0x55, 0x04, 0x0a], TAG_PRINTABLE, b"CORPUS")],
            std::slice::from_ref(&c),
        ]);
        assert_eq!(nc_dn(&full, &base), Ok(true));
        assert_eq!(nc_dn(&name(&[&[c]]), &base), Ok(false));
        assert_eq!(nc_dn(&[0x30, 0x01], &base), Err(()));
    }
}
