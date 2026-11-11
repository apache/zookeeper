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

//! Ingestion and management of certificate authority trust anchors within a `TrustSet`.
//!
//! Replicating OpenSSL's certificate verification architecture, this module ingests CA bundles
//! following `X509_load_cert_crl_file` semantics, inspects standard platform trust locations
//! (`SSL_CERT_FILE`, `SSL_CERT_DIR`, and compiled fallback paths), and processes hashed directory
//! structures (`HHHHHHHH.N` naming conventions) emulating OpenSSL `by_dir` resolution. Each
//! ingested certificate is evaluated and classified into a domain [`Role`]. To adhere to security
//! best practices, this represents the sole component in the drop-in driver runtime that inspects
//! process environment variables.
//!
//! In accordance with the legacy C driver behavior, trust stores are reloaded on each connection
//! attempt and read-write probe. Filesystem access is strictly bounded: only regular files within
//! size limits are read, and individual read failures do not corrupt previously ingested state.
//!
//! Deduplication ensures that each unique DER certificate is stored at most once; subsequent
//! identical entries are ignored per `X509_STORE_add_cert` semantics, preserving initial auxiliary
//! trust settings and role classifications.
//!
//! The store models OpenSSL lookup ordering explicitly: explicitly configured CA bundles and
//! default certificate files populate the store cache upfront, whereas hashed directory paths are
//! queried on-demand only when subject lookups miss the primary cache. Ingested entries record
//! their origin accordingly. During chain evaluation, verifiers query `TrustSet::lookup` to emulate
//! the exact sequence of certificates visible to OpenSSL. Inputs that are visible to OpenSSL but
//! cannot be evaluated fail closed to prevent security bypasses.

use std::collections::{BTreeSet, HashSet};
use std::ffi::OsStr;
use std::path::{Path, PathBuf};

use rustls::RootCertStore;
use rustls::pki_types::{CertificateDer, UnixTime};

use crate::net::tls::connector::{LegacyPath, ReadError, read_regular};
use crate::net::tls::pem::{self, PemBlock, PemError, PemKind};
use crate::net::tls::x509::{self, AuxTrust, LegacyCert, NS_SSL_CA, Validity};

/// Path to the compiled fallback certificate file. An unset or empty variable indicates no default
/// file.
pub const DEFAULT_CERT_FILE: Option<&str> = option_env!("ZK_TLS_DEFAULT_CERT_FILE");

/// Path to the compiled fallback certificate directory.
pub const DEFAULT_CERT_DIR: Option<&str> = option_env!("ZK_TLS_DEFAULT_CERT_DIR");

/// Maximum numerical `.N` suffix evaluated during hashed directory traversal.
const MAX_SUFFIX: u32 = 65_536;

/// Categorization of trust candidates during certificate chain evaluation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Role {
    /// Auxiliary settings explicitly reject `serverAuth`. The certificate remains an issuer
    /// candidate, but selecting it causes chain validation to fail with error 28.
    Rejected,
    /// Authorized root trust anchor capable of terminating a valid certificate chain.
    Anchor,
    /// Self-signed certificate not designated as a trusted root; eligible only for direct leaf
    /// matching.
    LeafOnly,
    /// Intermediate CA certificate that is not self-signed; serves as a path bridge rather than an
    /// anchor.
    Bridge,
}

/// Provenance classification of trust material within the OpenSSL store architecture.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum Origin {
    /// Initial store cache populated from CA bundles and default certificate files prior to
    /// lookups.
    Cache,
    /// Dynamically discovered group from a hashed directory matching a subject name hash, indexed
    /// in discovery order.
    Group(usize),
}

/// Fully qualified trust input encapsulating certificate DER, auxiliary settings, and resolved
/// role.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TrustedCert {
    der: CertificateDer<'static>,
    aux: Option<AuxTrust>,
    role: Role,
    /// Normalized canonical subject Distinguished Name bytes.
    subject: Vec<u8>,
    /// Pre-parsed validity period cached for efficient temporal filtering across connection
    /// attempts.
    validity: Validity,
    origin: Origin,
    /// Indicates whether the certificate was loaded from a hashed file whose filename hash differs
    /// from this certificate's subject hash.
    foreign: bool,
}

impl TrustedCert {
    /// Constructs a new trust input, parsing certificate DER and applying optional auxiliary trust
    /// rules (`X509_CERT_AUX`).
    ///
    /// The input's role is established via [`classify`], and its origin is assigned to the store
    /// cache.
    ///
    /// # Errors
    ///
    /// Returns [`crate::net::tls::x509::X509Error`] if the underlying DER encoding is corrupt.
    /// In such cases, the loader records an undecided stub ensuring lookups fail closed.
    pub fn new(
        der: CertificateDer<'static>,
        aux: Option<AuxTrust>,
    ) -> Result<Self, crate::net::tls::x509::X509Error> {
        let cert = LegacyCert::parse(der.as_ref())?;
        let role = classify(&cert, aux.as_ref());
        let subject = cert.canonical_subject();
        let validity = cert.validity();
        Ok(Self {
            der,
            aux,
            role,
            subject,
            validity,
            origin: Origin::Cache,
            foreign: false,
        })
    }

    /// Returns a reference to the certificate DER bytes.
    #[must_use]
    pub fn der(&self) -> &CertificateDer<'static> {
        &self.der
    }

    /// Returns a reference to any attached auxiliary trust configuration.
    #[must_use]
    pub fn aux(&self) -> Option<&AuxTrust> {
        self.aux.as_ref()
    }

    /// Returns the resolved verification role.
    #[must_use]
    pub fn role(&self) -> Role {
        self.role
    }

    /// Returns the canonical subject Distinguished Name.
    #[must_use]
    pub(crate) fn canonical_subject(&self) -> &[u8] {
        &self.subject
    }

    /// Returns the entry's origin within the trust store hierarchy.
    #[must_use]
    pub(crate) fn origin(&self) -> Origin {
        self.origin
    }

    /// Indicates whether this certificate was loaded from a hash file belonging to a different
    /// subject hash. Foreign entries cannot act as independent trust roots, serving solely as
    /// auxiliary issuer candidates.
    #[must_use]
    pub fn foreign(&self) -> bool {
        self.foreign
    }
}

/// Represents an unresolvable store input that OpenSSL parses but strict ASN.1 checks reject.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Undecided {
    origin: Origin,
    /// Canonical subject DN bytes, or `None` if the input subject is entirely unparseable.
    subject: Option<Vec<u8>>,
    /// Indicates whether OpenSSL considers this input a selectable issuer candidate.
    selectable: bool,
}

/// Represents a collection of files sharing a common subject hash in a hashed certificate
/// directory.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Group {
    hash: u32,
    /// Flagged true if an error was encountered after partially ingesting certificates from a file.
    partial: bool,
}

/// Error sentinel indicating that chain verification encountered an ambiguous or unresolvable trust
/// input.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) struct UndecidedInput;

/// Comprehensive error classifications for certificate trust bundle loading.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TrustLoadError {
    /// No CA certificate file path was configured.
    NoPath,
    /// Filesystem I/O error occurred during file traversal or reading.
    Io,
    /// PEM encapsulation block syntax is malformed.
    Pem(PemError),
    /// ASN.1 DER payload inside a certificate, CRL, or key block is invalid.
    Der,
    /// File contains no valid certificate or CRL structures.
    Empty,
    /// System memory allocation failure during path initialization.
    Resource,
}

/// Aggregated repository of trust anchors, intermediate bridges, and CRL metadata for an individual
/// connection attempt.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TrustSet {
    /// Comprehensive list of all ingested trust inputs in lexical discovery order.
    certs: Vec<TrustedCert>,
    /// Candidates eligible to serve as terminal trust anchors, subject to temporal validation.
    anchor_candidates: Vec<TrustedCert>,
    /// Trusted non-self-signed intermediate certificates offered to complete certificate chains.
    bridges: Vec<CertificateDer<'static>>,
    /// Inputs explicitly rejected by auxiliary trust policies.
    rejected: Vec<TrustedCert>,
    /// Total count of valid Certificate Revocation List (CRL) blocks parsed.
    crls: usize,
    /// Set of distinct DER payloads used to enforce deduplication across the store.
    seen: HashSet<CertificateDer<'static>>,
    /// Set of foreign entries recorded per hashed group.
    seen_foreign: HashSet<(usize, CertificateDer<'static>)>,
    /// Unresolvable certificate records tracked to ensure lookups fail closed.
    undecided: Vec<Undecided>,
    /// Ordered catalog of hashed directory groups discovered during resolution.
    groups: Vec<Group>,
}

impl TrustSet {
    /// Ingests a CA certificate file atomically under `CaFile` configuration semantics.
    ///
    /// Requires at least one certificate or CRL block to succeed. Any malformed block fails
    /// the entire file loading attempt. Candidate filesystem paths are evaluated sequentially.
    ///
    /// # Errors
    ///
    /// Returns [`TrustLoadError`] on parsing or I/O failure, mapped by the connector to
    /// `Config{CaFile}`.
    pub fn load_ca_file(&mut self, ca: Option<&LegacyPath>) -> Result<(), TrustLoadError> {
        let ca = ca.ok_or(TrustLoadError::NoPath)?;
        let text = read_first(ca.candidates())?;
        self.load_ca_text(&text)
    }

    /// Ingests platform default trust paths, implementing `DefaultVerifyPaths` semantics.
    ///
    /// When `env_allowed` is true, explicit overrides in `SSL_CERT_FILE` and `SSL_CERT_DIR` take
    /// precedence over compile-time defaults. Unreadable paths or missing directories are skipped
    /// without failing the overall initialization.
    ///
    /// # Errors
    ///
    /// Returns [`TrustLoadError::Resource`] only on internal memory allocation failure.
    pub fn load_default_paths(&mut self, env_allowed: bool) -> Result<(), TrustLoadError> {
        let file = env_path(env_allowed, "SSL_CERT_FILE").or_else(|| {
            DEFAULT_CERT_FILE
                .filter(|f| !f.is_empty())
                .map(PathBuf::from)
        });
        if let Some(file) = file {
            self.load_default_file(&file);
        }
        let dirs = env_path(env_allowed, "SSL_CERT_DIR").or_else(|| {
            DEFAULT_CERT_DIR
                .filter(|d| !d.is_empty())
                .map(PathBuf::from)
        });
        if let Some(dirs) = dirs {
            for dir in split_dir_list(dirs.as_os_str()) {
                self.load_hash_dir(&dir);
            }
        }
        Ok(())
    }

    /// Ingests a default CA certificate file if accessible, applying standard CA bundle parsing
    /// rules.
    pub fn load_default_file(&mut self, path: &Path) {
        if let Ok(text) = read_regular(path) {
            let _ = self.load_ca_text(&text);
        }
    }

    /// Traverses a directory of hash-named certificates adhering to OpenSSL `by_dir` resolution
    /// conventions (`HHHHHHHH.N`).
    ///
    /// Groups files sharing common subject hashes. Entries within a hash file whose subject differs
    /// from the filename hash are tracked as foreign auxiliary entries. Partial file reads mark
    /// groups as tainted, forcing subsequent lookups to fail closed.
    pub fn load_hash_dir(&mut self, dir: &Path) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        let hashes: BTreeSet<u32> = entries
            .flatten()
            .filter_map(|e| hash_of_name(&e.file_name()))
            .collect();
        for hash in hashes {
            let group = self.groups.len();
            self.groups.push(Group {
                hash,
                partial: false,
            });
            let origin = Origin::Group(group);
            for k in 0..MAX_SUFFIX {
                let path = dir.join(format!("{hash:08x}.{k}"));
                let (inputs, ok) = load_hash_file(&path);
                if !ok {
                    if !inputs.is_empty() {
                        if let Some(g) = self.groups.last_mut() {
                            g.partial = true;
                        }
                    }
                    break;
                }
                for input in inputs {
                    match input {
                        Input::Cert(mut cert) => {
                            cert.origin = origin;
                            cert.foreign = x509::name_hash(&cert.subject) != hash;
                            self.add(cert);
                        }
                        Input::Undecided {
                            subject,
                            selectable,
                        } => {
                            self.undecided.push(Undecided {
                                origin,
                                subject,
                                selectable,
                            });
                        }
                    }
                }
            }
        }
    }

    /// Determines whether an entry originating from `origin` is visible given the currently loaded
    /// hashed groups.
    #[must_use]
    pub(crate) fn visible(origin: Origin, loaded: &[usize]) -> bool {
        match origin {
            Origin::Cache => true,
            Origin::Group(g) => loaded.contains(&g),
        }
    }

    /// Checks whether the trust store currently contains any certificate matching the canonical
    /// subject `name`.
    fn holds_name(&self, name: &[u8], loaded: &[usize]) -> bool {
        self.certs
            .iter()
            .any(|c| c.subject == name && Self::visible(c.origin, loaded))
            || self.undecided_name(name, loaded, false)
    }

    /// Checks whether any visible undecided certificate matches the specified subject name.
    fn undecided_name(&self, name: &[u8], loaded: &[usize], selectable: bool) -> bool {
        self.undecided.iter().any(|u| {
            Self::visible(u.origin, loaded)
                && (u.selectable || !selectable)
                && u.subject.as_deref().is_none_or(|s| s == name)
        })
    }

    /// Executes subject-based certificate resolution matching OpenSSL
    /// `X509_STORE_CTX_get_by_subject`.
    ///
    /// First inspects the pre-loaded store cache. If no match is found, dynamically discovers and
    /// loads hashed directory groups matching the subject name hash in directory sequence. Tracks
    /// loaded groups in `loaded`.
    ///
    /// # Errors
    ///
    /// Returns [`UndecidedInput`] if a tainted group is encountered or if matching undecided inputs
    /// exist, failing closed.
    pub(crate) fn lookup(
        &self,
        name: &[u8],
        loaded: &mut Vec<usize>,
    ) -> Result<(), UndecidedInput> {
        if !self.holds_name(name, loaded) {
            let hash = x509::name_hash(name);
            for (g, group) in self.groups.iter().enumerate() {
                if group.hash != hash {
                    continue;
                }
                if !loaded.contains(&g) {
                    loaded.push(g);
                }
                if group.partial {
                    return Err(UndecidedInput);
                }
                if self.holds_name(name, loaded) {
                    break;
                }
            }
        }
        if self.undecided_name(name, loaded, true) {
            return Err(UndecidedInput);
        }
        Ok(())
    }

    /// Parses raw CA bundle text, recording certificates, CRLs, and undecided entries atomically.
    fn load_ca_text(&mut self, text: &[u8]) -> Result<(), TrustLoadError> {
        let (inputs, crls) = parse_ca_text(text)?;
        for input in inputs {
            match input {
                Input::Cert(cert) => self.add(cert),
                Input::Undecided {
                    subject,
                    selectable,
                } => self.undecided.push(Undecided {
                    origin: Origin::Cache,
                    subject,
                    selectable,
                }),
            }
        }
        self.crls = self.crls.saturating_add(crls);
        Ok(())
    }

    /// Inserts a parsed certificate into the store unless an identical DER representation has
    /// already been registered.
    fn add(&mut self, cert: TrustedCert) {
        if self.seen.contains(&cert.der) {
            return;
        }
        if cert.foreign {
            if let Origin::Group(g) = cert.origin {
                if self.seen_foreign.insert((g, cert.der.clone())) {
                    self.certs.push(cert);
                }
            }
            return;
        }
        self.push(cert);
    }

    /// Categorizes a verified certificate into corresponding role partitions (anchors, bridges, or
    /// rejected).
    pub fn push(&mut self, cert: TrustedCert) {
        match cert.role {
            Role::Rejected => self.rejected.push(cert.clone()),
            Role::Anchor => self.anchor_candidates.push(cert.clone()),
            Role::Bridge => self.bridges.push(cert.der.clone()),
            Role::LeafOnly => {}
        }
        self.seen.insert(cert.der.clone());
        self.certs.push(cert);
    }

    /// Returns a slice of all ingested trust inputs in registration order.
    #[must_use]
    pub fn certs(&self) -> &[TrustedCert] {
        &self.certs
    }

    /// Returns all candidate root trust anchors eligible for chain termination.
    #[must_use]
    pub fn anchor_candidates(&self) -> &[TrustedCert] {
        &self.anchor_candidates
    }

    /// Returns all non-self-signed intermediate bridge certificates.
    #[must_use]
    pub fn bridges(&self) -> &[CertificateDer<'static>] {
        &self.bridges
    }

    /// Returns certificates explicitly barred from server authentication by auxiliary trust rules.
    #[must_use]
    pub fn rejected(&self) -> &[TrustedCert] {
        &self.rejected
    }

    /// Returns the total count of valid CRL structures parsed across all bundles.
    #[must_use]
    pub fn crl_count(&self) -> usize {
        self.crls
    }

    /// Constructs a Rustls `RootCertStore` containing all anchor candidates whose validity window
    /// encompasses `now`.
    #[must_use]
    pub fn root_store_at(&self, now: UnixTime) -> RootCertStore {
        let mut store = RootCertStore::empty();
        for anchor in &self.anchor_candidates {
            if anchor.validity.contains(now) {
                let _ = store.add(anchor.der.clone());
            }
        }
        store
    }
}

/// Assigns a verification role to a certificate based on auxiliary trust attributes and X.509
/// constraints.
///
/// Priority is given to auxiliary trust settings (`obj_trust`). For self-signed certificates,
/// validates basic constraints (`check_ssl_ca`) and Extended Key Usage attributes to distinguish
/// root anchors from self-signed leaf certificates.
#[must_use]
pub fn classify(cert: &LegacyCert<'_>, aux: Option<&AuxTrust>) -> Role {
    if let Some(aux) = aux {
        if aux.rejects_server_auth() {
            return Role::Rejected;
        }
        if aux.trusts_server_auth() {
            return Role::Anchor;
        }
        if aux.trust.is_some() {
            return Role::Rejected;
        }
    }
    if !cert.openssl_self_signed() {
        return Role::Bridge;
    }
    let ca = cert.openssl_check_ca();
    let ssl_ca = ca != 0 && (ca != 5 || cert.ns_cert_type().is_some_and(|ns| ns & NS_SSL_CA != 0));
    if ssl_ca && cert.eku_allows_ssl_server() {
        Role::Anchor
    } else {
        Role::LeafOnly
    }
}

/// Injects platform default verification paths into the trust store.
pub fn add_default_paths(set: &mut TrustSet, env_allowed: bool) {
    let _ = set.load_default_paths(env_allowed);
}

/// Safely retrieves an environment variable path when process security permissions permit.
fn env_path(env_allowed: bool, name: &str) -> Option<PathBuf> {
    if env_allowed {
        std::env::var_os(name).map(PathBuf::from)
    } else {
        None
    }
}

/// Splits a delimited directory path string into distinct canonical directory paths, eliminating
/// duplicates and empty entries.
fn split_dir_list(list: &OsStr) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = Vec::new();
    for dir in split_os(list) {
        if !dir.as_os_str().is_empty() && !out.contains(&dir) {
            out.push(dir);
        }
    }
    out
}

#[cfg(unix)]
fn split_os(list: &OsStr) -> Vec<PathBuf> {
    use std::os::unix::ffi::OsStrExt;
    list.as_bytes()
        .split(|b| *b == b':')
        .map(|p| PathBuf::from(OsStr::from_bytes(p)))
        .collect()
}

#[cfg(windows)]
fn split_os(list: &OsStr) -> Vec<PathBuf> {
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    let wide: Vec<u16> = list.encode_wide().collect();
    wide.split(|c| *c == u16::from(b';'))
        .map(|p| PathBuf::from(std::ffi::OsString::from_wide(p)))
        .collect()
}

#[cfg(not(any(unix, windows)))]
fn split_os(list: &OsStr) -> Vec<PathBuf> {
    list.to_string_lossy()
        .split(':')
        .map(PathBuf::from)
        .collect()
}

/// Extracts the numeric subject hash from an OpenSSL `by_dir` formatted filename (`HHHHHHHH.N`).
fn hash_of_name(name: &OsStr) -> Option<u32> {
    let name = name.to_str()?;
    let (hash, suffix) = name.split_once('.')?;
    let hex_ok = hash.len() == 8 && hash.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
    let suffix_ok = !suffix.is_empty() && suffix.bytes().all(|b| b.is_ascii_digit());
    if hex_ok && suffix_ok {
        u32::from_str_radix(hash, 16).ok()
    } else {
        None
    }
}

/// Sequentially inspects candidate file paths and returns the raw byte payload of the first
/// readable regular file.
fn read_first(candidates: &[PathBuf]) -> Result<Vec<u8>, TrustLoadError> {
    for path in candidates {
        match read_regular(path) {
            Ok(text) => return Ok(text),
            Err(ReadError::NotFound) => {}
            Err(ReadError::Other) => return Err(TrustLoadError::Io),
        }
    }
    Err(TrustLoadError::Io)
}

/// Extracts the primary X.509 certificate DER slice and trailing auxiliary bytes from a raw block
/// payload.
fn split_cert(body: &[u8]) -> Option<(&[u8], &[u8])> {
    let rest = x509::ber_certificate(body)?;
    let cut = body.len().checked_sub(rest.len())?;
    body.split_at_checked(cut)
}

/// Discriminates between certificates parsed by `LegacyCert` and unparseable undecided inputs.
enum Input {
    /// Fully parsed certificate record.
    Cert(TrustedCert),
    /// Unresolvable certificate representation retained for fail-closed validation.
    Undecided {
        /// Canonical subject DN bytes when decodable.
        subject: Option<Vec<u8>>,
        /// Indicates whether OpenSSL considers this certificate an eligible issuer.
        selectable: bool,
    },
}

/// Wraps parsed certificate bytes into an appropriate store input variant.
fn input(cert: &[u8], aux: Option<AuxTrust>) -> Input {
    match TrustedCert::new(CertificateDer::from(cert.to_vec()), aux) {
        Ok(t) => Input::Cert(t),
        Err(_) => Input::Undecided {
            subject: x509::ber_subject(cert),
            selectable: !x509::ber_duplicate_extension(cert),
        },
    }
}

/// Parses a multi-entry PEM CA bundle according to OpenSSL `PEM_X509_INFO_read_bio_ex` semantics.
fn parse_ca_text(text: &[u8]) -> Result<(Vec<Input>, usize), TrustLoadError> {
    let blocks = pem::read_blocks(text).map_err(TrustLoadError::Pem)?;
    let mut found: usize = 0;
    let mut crls: usize = 0;
    let mut certs = Vec::new();
    for block in &blocks {
        let body = block.body.expose();
        let kind = pem::classify_label(&block.label);
        match kind {
            PemKind::Certificate | PemKind::TrustedCertificate => {
                no_headers(block)?;
                let (cert, rest) = split_cert(body).ok_or(TrustLoadError::Der)?;
                let aux = if kind == PemKind::TrustedCertificate && !rest.is_empty() {
                    Some(AuxTrust::parse(rest).map_err(|_| TrustLoadError::Der)?)
                } else {
                    None
                };
                found = found.saturating_add(1);
                certs.push(input(cert, aux));
            }
            PemKind::Crl => {
                no_headers(block)?;
                if !x509::ber_crl_ok(body) {
                    return Err(TrustLoadError::Der);
                }
                found = found.saturating_add(1);
                crls = crls.saturating_add(1);
            }
            _ if block.label.contains("PRIVATE KEY") => {
                if !block.headers.is_empty() {
                    if !pem::cipher_info_ok(&block.headers) {
                        return Err(TrustLoadError::Pem(PemError::Header));
                    }
                } else if block.label != "ENCRYPTED PRIVATE KEY"
                    && !pem::key_block_ok(&block.label, body)
                {
                    return Err(TrustLoadError::Der);
                }
            }
            _ => {}
        }
    }
    if found == 0 {
        return Err(TrustLoadError::Empty);
    }
    Ok((certs, crls))
}

/// Ensures that certificate and CRL blocks contain no legacy encapsulation headers.
fn no_headers(block: &PemBlock) -> Result<(), TrustLoadError> {
    if block.headers.is_empty() {
        Ok(())
    } else {
        Err(TrustLoadError::Pem(PemError::Header))
    }
}

/// Loads certificates from an OpenSSL hashed directory file per `X509_load_cert_file` rules.
fn load_hash_file(path: &Path) -> (Vec<Input>, bool) {
    let Ok(text) = read_regular(path) else {
        return (Vec::new(), false);
    };
    let (blocks, err) = pem::read_blocks_partial(&text);
    let mut certs = Vec::new();
    let mut count: usize = 0;
    for block in &blocks {
        if !matches!(
            pem::classify_label(&block.label),
            PemKind::Certificate | PemKind::TrustedCertificate
        ) {
            continue;
        }
        if !block.headers.is_empty() {
            return (certs, false);
        }
        let Some((cert, rest)) = split_cert(block.body.expose()) else {
            return (certs, false);
        };
        let aux = if rest.is_empty() {
            None
        } else {
            match AuxTrust::parse(rest) {
                Ok(aux) => Some(aux),
                Err(_) => return (certs, false),
            }
        };
        count = count.saturating_add(1);
        certs.push(input(cert, aux));
    }
    let ok = err.is_none() && count > 0;
    (certs, ok)
}
