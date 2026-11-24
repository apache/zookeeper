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

//! Native TLS transport options and certificate management backed by `rustls` (feature
//! `tls-rustls`).
//!
//! Certificate and key paths are inspected and validated during construction so that filesystem
//! errors fail early at configuration time. The background I/O driver requests an updated
//! [`rustls::ClientConfig`] on each connection attempt via [`zk_driver::net::tls::NativeConfig`].
//! Under [`ReloadPolicy::OnChange`], modified certificate and key files are automatically re-read
//! before initiating subsequent connection attempts. Any failure during dynamic certificate reload
//! aborts the connection attempt and records the error to [`SessionInfo::last_error`] via the
//! error probe.

use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use rustls::client::danger::ServerCertVerifier;
use rustls::client::{ResolvesClientCert, Resumption};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::ServerName;
use rustls::sign::CertifiedKey;
use rustls::{
    ClientConfig, ProtocolVersion, RootCertStore, SignatureScheme, SupportedProtocolVersion,
};
use zk_driver::Unlocked;
use zk_driver::net::tls::identity::{Identity, LegacyClientCert};
use zk_driver::net::tls::legacy_verify::LegacyChainOnly;
use zk_driver::net::tls::trust::{Role, TrustSet, TrustedCert};
use zk_driver::net::tls::verify::{ChainOnlyVerifier, webpki_verifier};
use zk_driver::net::tls::{
    Endpoint, LegacyPath, NativeConfig, RustlsConnector, TlsConfigStep, TlsConn, TlsConnector,
    TlsPurpose, TlsResourceAt, TlsSetupError, default_provider, trust,
};
use zk_proto::SecretBytes;
use zk_proto::compat::TlsVerify;

use crate::error::LastError;
use crate::route::TlsErrorSource;
use crate::secret::SecretString;
use crate::sync::LockExt;

/// Strategy governing when TLS certificates, trust stores, and private keys are reloaded.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub enum ReloadPolicy {
    /// Re-evaluates file timestamps on each connection attempt and reloads credentials when
    /// modifications are detected.
    #[default]
    OnChange,
    /// Loads TLS assets strictly during client initialization, avoiding subsequent filesystem
    /// inspections.
    Never,
}

/// Diagnostic error encountered while configuring or reloading TLS credentials.
#[derive(Debug, Clone)]
#[non_exhaustive]
pub enum TlsConfigError {
    /// The specified Certificate Authority (CA) file cannot be read, contains malformed PEM
    /// blocks, or lacks certificates and CRLs.
    CaFile(PathBuf),
    /// System root certificate discovery completed without finding any usable trust anchors in
    /// standard platform locations.
    DefaultRoots,
    /// Client certificate chain file cannot be read or contains invalid PEM data.
    CertChain(PathBuf),
    /// Client private key file cannot be loaded, parsed, or decrypted with the supplied
    /// passphrase.
    PrivateKey(PathBuf),
    /// Client private key does not correspond to the public key presented in the leaf
    /// certificate.
    KeyMismatch,
    /// Underlying cryptographic provider failed to construct the client TLS context.
    Context,
    /// Platform certificate verifier initialization or verification failed with the specified
    /// reason.
    PlatformVerifier(String),
    /// Client configuration violates FIPS 140 compliance requirements on a FIPS-enabled build.
    NotFips,
}

impl std::fmt::Display for TlsConfigError {
    /// Formats a concise error description identifying the problematic certificate or key path
    /// without leaking sensitive passphrases.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::CaFile(p) => write!(f, "failed to load CA file {}", p.display()),
            Self::DefaultRoots => {
                f.write_str("no usable root certificate in the default locations")
            }
            Self::CertChain(p) => write!(
                f,
                "failed to load client certificate chain from {}",
                p.display()
            ),
            Self::PrivateKey(p) => {
                write!(f, "failed to load client private key from {}", p.display())
            }
            Self::KeyMismatch => {
                f.write_str("the client private key does not match the certificate")
            }
            Self::Context => f.write_str("cannot make the TLS configuration"),
            Self::PlatformVerifier(cause) => write!(f, "cannot use the platform verifier: {cause}"),
            Self::NotFips => f.write_str("the TLS configuration is not FIPS"),
        }
    }
}

impl std::error::Error for TlsConfigError {}

/// Configured certificate paths, identity credentials, and validation options for the TLS builder.
#[derive(Debug)]
pub(crate) struct FileSources {
    /// Pinned Certificate Authority (CA) bundle path.
    pub(crate) ca_file: Option<PathBuf>,
    /// Whether platform system root certificates are enabled.
    pub(crate) system_roots: bool,
    /// Optional client mutual-TLS credentials: certificate chain, private key, and optional
    /// passphrase.
    pub(crate) identity: Option<(PathBuf, PathBuf, Option<SecretString>)>,
    /// Pinned Server Name Indication (SNI) and certificate hostname validation override for all
    /// ensemble peers.
    pub(crate) server_name: Option<ServerName<'static>>,
    /// Whether hostname verification is explicitly bypassed, enforcing chain validation only.
    pub(crate) no_hostname_check: bool,
    /// Minimum TLS protocol version permitted during negotiation.
    pub(crate) min_protocol: &'static SupportedProtocolVersion,
    /// Whether verification delegates to the operating system platform certificate verifier.
    pub(crate) platform_verifier: bool,
}

/// Source of TLS configuration metadata and cryptographic primitives.
#[derive(Debug)]
pub(crate) enum TlsSource {
    /// Constructed from filesystem certificate files and options. `legacy` indicates C-client
    /// compatibility mode.
    Files {
        /// Configured filesystem sources and verification settings.
        files: FileSources,
        /// Whether C-client legacy compatibility mode is active (`TlsVerify::LegacyChainOnly`).
        legacy: bool,
    },
    /// Pre-configured `rustls::ClientConfig` supplied directly by the application.
    Rustls(Arc<rustls::ClientConfig>),
}

/// Shared internal state maintaining active TLS configuration, cached credentials, and error
/// diagnostics across client instances.
pub(crate) struct TlsInner {
    /// Originating configuration source (filesystem paths or external `ClientConfig`).
    pub(crate) source: TlsSource,
    /// Active certificate reloading strategy.
    pub(crate) reload: ReloadPolicy,
    /// Cached TLS client configuration along with filesystem metadata stamps. Protected by a leaf
    /// lock without I/O operations held under lock.
    pub(crate) cache: Mutex<Option<Cached>>,
    /// Error diagnostic recorded during the most recent failed certificate reload attempt, exposed
    /// to telemetry probes.
    pub(crate) last_error: Mutex<Option<TlsConfigError>>,
    /// Whether environment variables (`SSL_CERT_FILE`, `SSL_CERT_DIR`) are permitted based on
    /// process privilege checks.
    pub(crate) env_allowed: bool,
}

impl std::fmt::Debug for TlsInner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TlsInner")
            .field("source", &self.source)
            .field("reload", &self.reload)
            .finish_non_exhaustive()
    }
}

/// TLS configuration options shared across one or more ZooKeeper client instances. Cloning is
/// cheap via internal `Arc` references.
#[derive(Clone, Debug)]
pub struct TlsOptions {
    inner: Arc<TlsInner>,
}

impl TlsOptions {
    /// Initializes a fluent builder with no default trust anchors.
    pub fn builder() -> TlsOptionsBuilder {
        TlsOptionsBuilder::new()
    }

    /// Creates drop-in TLS options emulating the legacy C client `zookeeper_init_ssl`
    /// specification format.
    ///
    /// Accepts a comma-delimited string formatted as `"ca,cert,key,passwd"`. Configures
    /// [`TlsVerify::LegacyChainOnly`] mode. Trusts the specified CA bundle alongside platform
    /// default roots, suppresses Server Name Indication (SNI), and bypasses hostname verification.
    /// Passphrases are stored in zeroized memory. Never fails with
    /// [`TlsConfigError::DefaultRoots`].
    ///
    /// # Errors
    ///
    /// Returns [`TlsConfigError`] if any specified certificate or private key file cannot be
    /// loaded or parsed.
    pub fn legacy_c_compat(cert_string: &str) -> Result<TlsOptions, TlsConfigError> {
        let [ca, cert, key, passwd] = split_cert_string(cert_string);
        // Mutual-TLS client identity requires both certificate chain and private key paths.
        // The passphrase argument is optional, enabling unencrypted private keys to load without
        // credentials.
        let identity = match (cert, key) {
            (Some(c), Some(k)) => Some((
                PathBuf::from(c),
                PathBuf::from(k),
                passwd.map(SecretString::from),
            )),
            _ => None,
        };
        let files = FileSources {
            ca_file: ca.map(PathBuf::from),
            system_roots: true,
            identity,
            server_name: None,
            no_hostname_check: true,
            min_protocol: &rustls::version::TLS12,
            platform_verifier: false,
        };
        Self::load_new(files, true, ReloadPolicy::OnChange)
    }

    /// Constructs client TLS options directly from an existing `rustls::ClientConfig`.
    ///
    /// Preserves the provided configuration's SNI and certificate verification behavior directly.
    /// Dynamic file reloading does not apply to externally managed configurations.
    #[must_use]
    pub fn from_rustls(cfg: Arc<rustls::ClientConfig>) -> TlsOptions {
        Self::from_inner(TlsInner {
            source: TlsSource::Rustls(cfg),
            reload: ReloadPolicy::Never,
            cache: Mutex::new(None),
            last_error: Mutex::new(None),
            env_allowed: false,
        })
    }

    /// Wraps internal shared TLS state in an `Arc`-backed [`TlsOptions`] handle.
    pub(crate) fn from_inner(inner: TlsInner) -> Self {
        Self {
            inner: Arc::new(inner),
        }
    }

    /// Evaluates environment permissions, parses and validates all certificate inputs, and
    /// initializes the reload cache.
    fn load_new(
        files: FileSources,
        legacy: bool,
        reload: ReloadPolicy,
    ) -> Result<TlsOptions, TlsConfigError> {
        let env_allowed = env_allowed();
        let first = load(&files, legacy, env_allowed)?;
        Ok(Self::from_inner(TlsInner {
            source: TlsSource::Files { files, legacy },
            reload,
            cache: Mutex::new(Some(first)),
            last_error: Mutex::new(None),
            env_allowed,
        }))
    }

    /// Returns the wire level verification mode indicating whether legacy chain-only or modern
    /// WebPKI validation is active.
    pub(crate) fn tls_verify(&self) -> TlsVerify {
        match &self.inner.source {
            TlsSource::Files { legacy: true, .. } => TlsVerify::LegacyChainOnly,
            _ => TlsVerify::WebPki,
        }
    }

    /// Validates configuration invariants at client build and connect time. Refuses non-FIPS
    /// configurations when built with FIPS features.
    ///
    /// # Errors
    ///
    /// Returns [`TlsConfigError::NotFips`] if a non-FIPS configuration is provided under a FIPS
    /// build.
    pub(crate) fn check(&self) -> Result<(), TlsConfigError> {
        #[cfg(feature = "tls-aws-lc-rs-fips")]
        if let TlsSource::Rustls(cfg) = &self.inner.source {
            if !cfg.fips() {
                return Err(TlsConfigError::NotFips);
            }
        }
        Ok(())
    }

    /// Constructs the driver TLS connector backed by the configured options and default
    /// cryptographic provider.
    pub(crate) fn connector(&self) -> Arc<dyn TlsConnector> {
        let src: Arc<dyn NativeConfig> = Arc::clone(&self.inner) as Arc<dyn NativeConfig>;
        let mut connector = NativeConnector {
            inner: RustlsConnector::native(src, default_provider()),
            name: None,
            ca: None,
            cert: None,
            key: None,
        };
        if let TlsSource::Files { files, legacy } = &self.inner.source {
            connector.name = files.server_name.as_ref().and_then(name_text);
            connector.ca = files.ca_file.as_deref().map(path_bytes);
            if let Some((chain, key, _)) = &files.identity {
                connector.cert = Some(path_bytes(chain));
                connector.key = Some(path_bytes(key));
            }
            // Emit a diagnostic warning once per client connector when hostname verification is
            // bypassed in non-legacy mode.
            if files.no_hostname_check && !legacy {
                warn_no_hostname_check();
            }
        }
        Arc::new(connector)
    }

    /// Returns an error probe callback reporting the diagnostic from the most recent failed
    /// reload attempt to connection routing.
    pub(crate) fn error_probe(&self) -> TlsErrorSource {
        let inner = Arc::clone(&self.inner);
        Arc::new(move || {
            let e = inner.last_error.lock_unpoisoned().clone()?;
            let e: LastError = Arc::new(e);
            Some(e)
        })
    }
}

impl NativeConfig for TlsInner {
    /// Resolves the TLS client configuration for an individual connection attempt.
    ///
    /// Under [`ReloadPolicy::OnChange`], inspects filesystem timestamps outside any internal lock
    /// and reloads assets if modifications are detected. Failed reloads record the error
    /// diagnostic to `last_error` and return the matching [`TlsSetupError`] phase. Successful
    /// reloads update the cached configuration and clear error diagnostics.
    fn client_config(
        &self,
        _proof: &Unlocked,
        _purpose: TlsPurpose,
    ) -> Result<Arc<rustls::ClientConfig>, TlsSetupError> {
        let (files, legacy) = match &self.source {
            TlsSource::Rustls(cfg) => return Ok(Arc::clone(cfg)),
            TlsSource::Files { files, legacy } => (files, *legacy),
        };
        // Extract cached configuration and stamps under lock, performing filesystem stat checks
        // outside the lock.
        let cached = self.cache.lock_unpoisoned().as_ref().map(|c| {
            (
                Arc::clone(&c.config),
                Arc::clone(&c.watched),
                c.stamps.clone(),
            )
        });
        if let Some((cfg, watched, stamps)) = cached {
            if self.reload == ReloadPolicy::Never || stamps_of(&watched) == stamps {
                *self.last_error.lock_unpoisoned() = None;
                return Ok(cfg);
            }
        }
        match load(files, legacy, self.env_allowed) {
            Ok(fresh) => {
                let cfg = Arc::clone(&fresh.config);
                *self.cache.lock_unpoisoned() = Some(fresh);
                *self.last_error.lock_unpoisoned() = None;
                Ok(cfg)
            }
            Err(e) => {
                let step = setup_error(&e);
                *self.last_error.lock_unpoisoned() = Some(e);
                Err(step)
            }
        }
    }
}

/// Fluent builder for constructing [`TlsOptions`].
#[derive(Debug)]
#[must_use = "a builder does nothing until build() is called"]
pub struct TlsOptionsBuilder {
    files: FileSources,
    reload: ReloadPolicy,
}

impl TlsOptionsBuilder {
    /// Initializes a builder with no trust anchors, TLS 1.2 minimum version, and
    /// [`ReloadPolicy::OnChange`].
    fn new() -> Self {
        Self {
            files: FileSources {
                ca_file: None,
                system_roots: false,
                identity: None,
                server_name: None,
                no_hostname_check: false,
                min_protocol: &rustls::version::TLS12,
                platform_verifier: false,
            },
            reload: ReloadPolicy::OnChange,
        }
    }

    /// Configures a pinned Certificate Authority (CA) bundle from a PEM file. Replaces default
    /// system roots unless [`TlsOptionsBuilder::add_system_roots`] is explicitly invoked.
    pub fn ca_file(mut self, p: impl Into<PathBuf>) -> Self {
        self.files.ca_file = Some(p.into());
        self
    }

    /// Includes platform default root certificates in addition to any explicitly configured CA
    /// files.
    pub fn add_system_roots(mut self) -> Self {
        self.files.system_roots = true;
        self
    }

    /// Configures client mutual TLS identity using a certificate chain, private key, and optional
    /// decryption passphrase.
    pub fn client_identity(
        mut self,
        chain: impl Into<PathBuf>,
        key: impl Into<PathBuf>,
        passphrase: Option<SecretString>,
    ) -> Self {
        self.files.identity = Some((chain.into(), key.into(), passphrase));
        self
    }

    /// Enforces a single static hostname or IP address for Server Name Indication (SNI) and
    /// certificate validation across all ensemble servers.
    pub fn server_name(mut self, name: ServerName<'static>) -> Self {
        self.files.server_name = Some(name);
        self
    }

    /// Disables server hostname verification, enforcing certificate chain cryptographic validity
    /// only. Emits a security warning.
    pub fn danger_disable_hostname_verification(mut self) -> Self {
        self.files.no_hostname_check = true;
        self
    }

    /// Sets the minimum TLS protocol version permitted during handshake negotiation
    /// (defaults to TLS 1.2).
    pub fn min_protocol(mut self, v: &'static SupportedProtocolVersion) -> Self {
        self.files.min_protocol = v;
        self
    }

    /// Sets the filesystem credential reloading policy.
    pub fn reload(mut self, r: ReloadPolicy) -> Self {
        self.reload = r;
        self
    }

    /// Configures the client to delegate certificate verification to the operating system trust
    /// store and platform verifier.
    ///
    /// The platform verifier supersedes all file based trust anchors. Consequently, `build` fails
    /// with [`TlsConfigError::PlatformVerifier`] if combined with `ca_file`, `add_system_roots`,
    /// or `danger_disable_hostname_verification`. On Linux and BSD platforms, setuid or setgid
    /// processes are likewise prohibited from using the platform verifier due to unvalidated
    /// environment variable inspection risks.
    #[cfg(feature = "tls-platform-verifier")]
    pub fn with_platform_verifier(mut self) -> Self {
        self.files.platform_verifier = true;
        self
    }

    /// Validates all configured files, environment permissions, and cryptographic settings,
    /// returning the built [`TlsOptions`].
    ///
    /// Evaluates privilege status via `rustix` to verify that real and effective credentials match
    /// before reading environment variables. Fails with [`TlsConfigError::DefaultRoots`] if system
    /// root resolution yields no trust anchors. Under FIPS builds, verifies provider FIPS
    /// certification.
    ///
    /// # Errors
    ///
    /// Returns [`TlsConfigError`] if any configuration validation check or certificate decoding
    /// fails.
    pub fn build(self) -> Result<TlsOptions, TlsConfigError> {
        check_platform_verifier(&self.files, env_allowed())?;
        TlsOptions::load_new(self.files, false, self.reload)
    }
}

// ---- Private helpers ----

/// Cached TLS client configuration along with filesystem metadata stamps for change detection.
pub(crate) struct Cached {
    /// Active TLS client configuration.
    config: Arc<ClientConfig>,
    /// Monitored certificate files and directories from which the configuration was derived.
    watched: Arc<[PathBuf]>,
    /// Filesystem metadata snapshots of `watched` captured prior to reading credential data.
    stamps: Vec<Option<Stamp>>,
}

/// Filesystem metadata snapshot capturing file modification time, length, and inode for change
/// detection.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Stamp {
    modified: Option<SystemTime>,
    len: u64,
    inode: u64,
}

/// Captures filesystem metadata for the specified path, returning `None` if the target is
/// inaccessible.
fn stamp(p: &Path) -> Option<Stamp> {
    let meta = std::fs::metadata(p).ok()?;
    #[cfg(unix)]
    let inode = std::os::unix::fs::MetadataExt::ino(&meta);
    #[cfg(not(unix))]
    let inode = 0;
    Some(Stamp {
        modified: meta.modified().ok(),
        len: meta.len(),
        inode,
    })
}

/// Collects filesystem metadata snapshots across an ordered slice of paths.
fn stamps_of(paths: &[PathBuf]) -> Vec<Option<Stamp>> {
    paths.iter().map(|p| stamp(p)).collect()
}

/// Determines whether environment variables (`SSL_CERT_FILE`, `SSL_CERT_DIR`) can be safely
/// inspected without privilege escalation risks.
///
/// In accordance with OpenSSL security behavior, set-uid and set-gid processes ignore environment
/// overrides to prevent privilege escalation.
#[cfg(unix)]
fn env_allowed() -> bool {
    use rustix::process::{getegid, geteuid, getgid, getuid};
    getuid() == geteuid() && getgid() == getegid()
}

/// Evaluates environment variable access permissions on non-Unix platforms (unconditionally
/// permitted).
#[cfg(not(unix))]
fn env_allowed() -> bool {
    true
}

/// Splits a legacy `zookeeper_init_ssl` connection parameter into `[ca, cert, key, passwd]`
/// following C client `strtok` semantics.
///
/// Collapses consecutive delimiters, preserves whitespace, and truncates beyond four tokens while
/// borrowing slices directly.
fn split_cert_string(s: &str) -> [Option<&str>; 4] {
    let mut fields = s.split(',').filter(|f| !f.is_empty());
    [fields.next(), fields.next(), fields.next(), fields.next()]
}

/// Validates configuration invariants when the platform certificate verifier is selected.
///
/// Ensures mutual exclusivity against custom CA pins, system root flags, and disabled hostname
/// checks. On Unix platforms outside macOS and Android, setuid or setgid processes are barred from
/// platform verification due to unvalidated environment variable inspection hazards.
fn check_platform_verifier(files: &FileSources, env_allowed: bool) -> Result<(), TlsConfigError> {
    if !files.platform_verifier {
        return Ok(());
    }
    if files.ca_file.is_some() || files.system_roots || files.no_hostname_check {
        return Err(TlsConfigError::PlatformVerifier(
            "with_platform_verifier cannot be combined with ca_file, add_system_roots or \
             danger_disable_hostname_verification"
                .to_owned(),
        ));
    }
    if !env_allowed
        && cfg!(all(
            unix,
            not(target_vendor = "apple"),
            not(target_os = "android")
        ))
    {
        return Err(TlsConfigError::PlatformVerifier(
            "the OS trust store reads the environment, which a set-uid or set-gid process \
             must ignore"
                .to_owned(),
        ));
    }
    Ok(())
}

/// Instantiates the platform certificate verifier backed by the OS trust store and provider
/// signature algorithms.
#[cfg(feature = "tls-platform-verifier")]
fn platform_verifier(
    provider: &Arc<CryptoProvider>,
) -> Result<Arc<dyn ServerCertVerifier>, TlsConfigError> {
    match rustls_platform_verifier::Verifier::new(Arc::clone(provider)) {
        Ok(v) => Ok(Arc::new(v)),
        Err(e) => Err(TlsConfigError::PlatformVerifier(e.to_string())),
    }
}

/// Fallback platform verifier constructor returning an error when the `tls-platform-verifier`
/// feature is disabled.
#[cfg(not(feature = "tls-platform-verifier"))]
fn platform_verifier(
    _provider: &Arc<CryptoProvider>,
) -> Result<Arc<dyn ServerCertVerifier>, TlsConfigError> {
    Err(TlsConfigError::PlatformVerifier(
        "the platform verifier is not in this build".to_owned(),
    ))
}

/// Translates a [`TlsConfigError`] into its corresponding [`TlsSetupError`] driver setup phase.
///
/// Filesystem errors map to specific setup steps for retry handling, while context and platform
/// errors trigger teardown.
fn setup_error(e: &TlsConfigError) -> TlsSetupError {
    let step = match e {
        TlsConfigError::CaFile(_) => TlsConfigStep::CaFile,
        TlsConfigError::DefaultRoots => TlsConfigStep::DefaultVerifyPaths,
        TlsConfigError::CertChain(_) => TlsConfigStep::ClientCertChain,
        TlsConfigError::PrivateKey(_) => TlsConfigStep::ClientKey,
        TlsConfigError::KeyMismatch => TlsConfigStep::CheckPrivateKey,
        TlsConfigError::Context | TlsConfigError::PlatformVerifier(_) | TlsConfigError::NotFips => {
            return TlsSetupError::Resource {
                at: TlsResourceAt::ContextNew,
            };
        }
    };
    TlsSetupError::Config { step }
}

/// Standard platform trust anchor locations resolved from environment variables or compile time
/// defaults.
#[derive(Debug, Default)]
struct DefaultLocations {
    /// Primary root certificate bundle file path.
    file: Option<PathBuf>,
    /// OpenSSL-style hashed certificate directory search paths.
    dirs: Vec<PathBuf>,
}

impl DefaultLocations {
    /// Resolves default trust locations from environment overrides or platform defaults.
    ///
    /// When environment access is permitted, `SSL_CERT_FILE` and `SSL_CERT_DIR` supersede compile
    /// time defaults.
    fn select(env_allowed: bool, var: &dyn Fn(&str) -> Option<OsString>) -> Self {
        let env = |name: &str| if env_allowed { var(name) } else { None };
        let file = match env("SSL_CERT_FILE") {
            Some(v) => Some(PathBuf::from(v)),
            None => trust::DEFAULT_CERT_FILE
                .filter(|f| !f.is_empty())
                .map(PathBuf::from),
        };
        let dirs: Vec<PathBuf> = match env("SSL_CERT_DIR") {
            Some(v) => std::env::split_paths(&v).collect(),
            None => trust::DEFAULT_CERT_DIR
                .filter(|d| !d.is_empty())
                .map(|d| std::env::split_paths(d).collect())
                .unwrap_or_default(),
        };
        let dirs = dirs
            .into_iter()
            .filter(|d| !d.as_os_str().is_empty())
            .collect();
        Self { file, dirs }
    }

    /// Ingests certificates from standard locations into the target trust set, returning the
    /// number of certificates successfully added.
    ///
    /// Missing, empty, or unparseable paths are skipped gracefully without failing configuration.
    fn add_to(&self, trust: &mut TrustSet) -> usize {
        let before = trust.certs().len();
        if let Some(file) = &self.file {
            trust.load_default_file(file);
        }
        for dir in &self.dirs {
            trust.load_hash_dir(dir);
        }
        trust
            .certs()
            .get(before..)
            .unwrap_or_default()
            .iter()
            .filter(|c| !c.foreign())
            .count()
    }

    /// Enumerates filesystem paths monitored for timestamp changes during reload checks.
    fn watched(&self) -> impl Iterator<Item = &PathBuf> {
        self.file.iter().chain(self.dirs.iter())
    }
}

/// Wraps a filesystem path in a legacy diagnostic representation for driver logging.
fn legacy_path(p: &Path) -> LegacyPath {
    LegacyPath::with_candidates(path_bytes(p), vec![p.to_path_buf()])
}

/// Reads and decodes a Certificate Authority bundle into the destination trust set.
///
/// Accepts PEM certificates, trusted certificates, and DER encoded files. Fails if the file
/// contains no valid certificates or CRLs.
fn load_ca_file(path: &Path, trust: &mut TrustSet) -> Result<(), TlsConfigError> {
    trust
        .load_ca_file(Some(&legacy_path(path)))
        .map_err(|_| TlsConfigError::CaFile(path.to_path_buf()))
}

/// Populates a root certificate store with valid trust anchors, returning the number of roots
/// added.
///
/// Excludes certificates marked rejected or foreign hash-directory entries whose filenames do not
/// match their subject hashes.
fn add_roots(trust: &[TrustedCert], roots: &mut RootCertStore) -> usize {
    trust
        .iter()
        .filter(|c| c.role() != Role::Rejected && !c.foreign())
        .filter(|c| roots.add(c.der().clone()).is_ok())
        .count()
}

/// Loads and validates mutual-TLS client identity credentials, decrypting private keys if a
/// passphrase is provided.
fn load_identity(
    chain: &Path,
    key: &Path,
    passphrase: Option<&SecretString>,
    provider: &CryptoProvider,
) -> Result<Arc<CertifiedKey>, TlsConfigError> {
    let passwd = match passphrase {
        Some(p) => SecretBytes::from_slice(p.expose().as_bytes()),
        None => SecretBytes::empty(),
    };
    match Identity::load(&legacy_path(chain), &legacy_path(key), &passwd, provider) {
        Ok(identity) => Ok(Arc::clone(identity.certified_key())),
        Err(TlsSetupError::Config {
            step: TlsConfigStep::ClientCertChain,
        }) => Err(TlsConfigError::CertChain(chain.to_path_buf())),
        Err(TlsSetupError::Config {
            step: TlsConfigStep::CheckPrivateKey,
        }) => Err(TlsConfigError::KeyMismatch),
        Err(TlsSetupError::Config {
            step: TlsConfigStep::ClientKey,
        }) => Err(TlsConfigError::PrivateKey(key.to_path_buf())),
        Err(_) => Err(TlsConfigError::Context),
    }
}

/// Captures pre-read filesystem metadata stamps and constructs the TLS client configuration.
///
/// Sampling stamps prior to reading credential data ensures that modifications during loading
/// trigger a subsequent reload.
fn load(files: &FileSources, legacy: bool, env_allowed: bool) -> Result<Cached, TlsConfigError> {
    let defaults = (legacy || files.system_roots)
        .then(|| DefaultLocations::select(env_allowed, &|name| std::env::var_os(name)));
    let mut watched: Vec<PathBuf> = Vec::new();
    watched.extend(files.ca_file.iter().cloned());
    if let Some((chain, key, _)) = &files.identity {
        watched.push(chain.clone());
        watched.push(key.clone());
    }
    if let Some(d) = &defaults {
        watched.extend(d.watched().cloned());
    }
    let stamps = stamps_of(&watched);
    let config = make_config(files, legacy, defaults.as_ref())?;
    Ok(Cached {
        config,
        watched: watched.into(),
        stamps,
    })
}

/// Constructs a complete `rustls::ClientConfig` from parsed file sources and verification options.
///
/// Native verification mandates a valid trust anchor and at least one parsed root certificate.
/// Under C-client legacy mode (`legacy_c_compat`), CA files must load successfully while default
/// locations never cause fatal failures.
#[inline(never)]
fn make_config(
    files: &FileSources,
    legacy: bool,
    defaults: Option<&DefaultLocations>,
) -> Result<Arc<ClientConfig>, TlsConfigError> {
    let provider = default_provider();
    let verifier = if files.platform_verifier {
        platform_verifier(&provider)?
    } else {
        file_verifier(files, legacy, defaults, &provider)?
    };
    let identity = match &files.identity {
        Some((chain, key, pass)) => Some(load_identity(chain, key, pass.as_ref(), &provider)?),
        None => None,
    };
    let versions: &[&'static SupportedProtocolVersion] =
        if files.min_protocol.version == ProtocolVersion::TLSv1_3 {
            &[&rustls::version::TLS13]
        } else {
            &[&rustls::version::TLS13, &rustls::version::TLS12]
        };
    finish_config(provider, versions, verifier, identity, legacy)
}

/// Constructs the certificate verifier enforcing configured CA pins and system roots.
///
/// In legacy mode, delegates to [`LegacyChainOnly`] to replicate C client verification semantics
/// without SNI or hostname checks.
fn file_verifier(
    files: &FileSources,
    legacy: bool,
    defaults: Option<&DefaultLocations>,
    provider: &Arc<CryptoProvider>,
) -> Result<Arc<dyn ServerCertVerifier>, TlsConfigError> {
    if !legacy && files.ca_file.is_none() && !files.system_roots {
        return Err(TlsConfigError::Context);
    }
    let mut trust = TrustSet::default();
    match &files.ca_file {
        Some(p) => load_ca_file(p, &mut trust)?,
        // In legacy mode, omitting the CA file triggers a CaFile configuration error matching C
        // client behavior.
        None if legacy => return Err(TlsConfigError::CaFile(PathBuf::new())),
        None => {}
    }
    let from_file = trust.certs().len();
    if let Some(d) = defaults {
        d.add_to(&mut trust);
    }
    if legacy {
        let algs = provider.signature_verification_algorithms;
        return Ok(Arc::new(LegacyChainOnly::new(trust, algs)));
    }
    let (file_certs, default_certs) = trust
        .certs()
        .split_at_checked(from_file)
        .unwrap_or_default();
    let mut roots = RootCertStore::empty();
    let file_roots = add_roots(file_certs, &mut roots);
    let default_roots = add_roots(default_certs, &mut roots);
    if defaults.is_some() && files.system_roots && default_roots == 0 {
        return Err(TlsConfigError::DefaultRoots);
    }
    if file_roots == 0 && default_roots == 0 {
        return Err(match &files.ca_file {
            Some(p) => TlsConfigError::CaFile(p.clone()),
            None => TlsConfigError::DefaultRoots,
        });
    }
    let roots = Arc::new(roots);
    if files.no_hostname_check {
        Ok(Arc::new(ChainOnlyVerifier::new(
            roots,
            Arc::clone(provider),
        )))
    } else {
        webpki_verifier(roots, Arc::clone(provider)).map_err(|_| TlsConfigError::Context)
    }
}

/// Finalizes construction of the `rustls::ClientConfig`, configuring protocol versions, verifiers,
/// and client identity.
///
/// In legacy mode, Server Name Indication (SNI) and session resumption are disabled to mirror
/// legacy C client semantics.
#[inline(never)]
fn finish_config(
    provider: Arc<CryptoProvider>,
    versions: &[&'static SupportedProtocolVersion],
    verifier: Arc<dyn ServerCertVerifier>,
    identity: Option<Arc<CertifiedKey>>,
    legacy: bool,
) -> Result<Arc<ClientConfig>, TlsConfigError> {
    let builder = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(versions)
        .map_err(|_| TlsConfigError::Context)?
        .dangerous()
        .with_custom_certificate_verifier(verifier);
    let mut cfg = match identity {
        // Legacy mode filters presented client certificates based on signature schemes offered by
        // the peer.
        Some(key) if legacy => builder.with_client_cert_resolver(Arc::new(LegacyClientCert(key))),
        Some(key) => builder.with_client_cert_resolver(Arc::new(FixedIdentity(key))),
        None => builder.with_no_client_auth(),
    };
    if legacy {
        cfg.enable_sni = false;
        cfg.resumption = Resumption::disabled();
    }
    fips_check(&mut cfg)?;
    Ok(Arc::new(cfg))
}

/// Verifies that the client TLS configuration satisfies FIPS 140 requirements under FIPS-enabled
/// builds.
#[cfg(feature = "tls-aws-lc-rs-fips")]
fn fips_check(cfg: &mut ClientConfig) -> Result<(), TlsConfigError> {
    cfg.require_ems = true;
    if cfg.fips() {
        Ok(())
    } else {
        Err(TlsConfigError::NotFips)
    }
}

/// No-op FIPS verification pass-through utilized when FIPS cryptographic providers are disabled.
#[cfg(not(feature = "tls-aws-lc-rs-fips"))]
#[allow(clippy::unnecessary_wraps)]
fn fips_check(_cfg: &mut ClientConfig) -> Result<(), TlsConfigError> {
    Ok(())
}

/// Resolves client mutual TLS identity using a fixed pre-loaded certificate chain and private key.
#[derive(Debug)]
struct FixedIdentity(Arc<CertifiedKey>);

impl ResolvesClientCert for FixedIdentity {
    fn resolve(
        &self,
        _root_hint_subjects: &[&[u8]],
        _sigschemes: &[SignatureScheme],
    ) -> Option<Arc<CertifiedKey>> {
        Some(Arc::clone(&self.0))
    }

    fn has_certs(&self) -> bool {
        true
    }
}

/// Extracts raw bytes from a server name representation for driver endpoint tracking.
fn name_text(name: &ServerName<'static>) -> Option<Vec<u8>> {
    match name {
        ServerName::DnsName(d) => Some(d.as_ref().as_bytes().to_vec()),
        ServerName::IpAddress(ip) => Some(std::net::IpAddr::from(*ip).to_string().into_bytes()),
        _ => None,
    }
}

/// Converts a filesystem path into raw bytes for driver diagnostic logs.
fn path_bytes(p: &Path) -> Vec<u8> {
    #[cfg(unix)]
    {
        std::os::unix::ffi::OsStrExt::as_bytes(p.as_os_str()).to_vec()
    }
    #[cfg(not(unix))]
    {
        p.to_string_lossy().into_owned().into_bytes()
    }
}

/// Emits a diagnostic warning when TLS hostname verification is disabled.
fn warn_no_hostname_check() {
    #[cfg(feature = "tracing")]
    tracing::warn!(
        target: "apache_zookeeper::session",
        "TLS hostname verification is off; only the certificate chain is checked"
    );
    #[cfg(all(feature = "log", not(feature = "tracing")))]
    log::warn!(
        target: "apache_zookeeper::session",
        "TLS hostname verification is off; only the certificate chain is checked"
    );
}

/// Driver TLS connector wrapping `RustlsConnector` with ensemble-wide server name overrides and
/// diagnostic path logging.
struct NativeConnector {
    /// Wrapped driver TLS connector.
    inner: RustlsConnector,
    /// Configured server hostname override for SNI and validation.
    name: Option<Vec<u8>>,
    /// CA bundle filesystem path for diagnostic logging.
    ca: Option<Vec<u8>>,
    /// Client certificate chain filesystem path for diagnostic logging.
    cert: Option<Vec<u8>>,
    /// Client private key filesystem path for diagnostic logging.
    key: Option<Vec<u8>>,
}

impl TlsConnector for NativeConnector {
    fn new_conn(
        &self,
        proof: &Unlocked,
        purpose: TlsPurpose,
        peer: &Endpoint,
    ) -> Result<Box<dyn TlsConn>, TlsSetupError> {
        match &self.name {
            Some(name) => {
                let mut named = peer.clone();
                named.host.name.clone_from(name);
                self.inner.new_conn(proof, purpose, &named)
            }
            None => self.inner.new_conn(proof, purpose, peer),
        }
    }

    fn path_for(&self, step: TlsConfigStep) -> Option<&[u8]> {
        match step {
            TlsConfigStep::CaFile => self.ca.as_deref(),
            TlsConfigStep::ClientCertChain => self.cert.as_deref(),
            TlsConfigStep::ClientKey => self.key.as_deref(),
            TlsConfigStep::DefaultVerifyPaths | TlsConfigStep::CheckPrivateKey => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_builder_records_its_inputs() {
        let b = TlsOptions::builder()
            .ca_file("/ca.pem")
            .add_system_roots()
            .reload(ReloadPolicy::Never);
        assert_eq!(b.files.ca_file, Some(PathBuf::from("/ca.pem")));
        assert!(b.files.system_roots);
        assert_eq!(b.reload, ReloadPolicy::Never);
    }

    use std::net::SocketAddr;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use rustls::ServerConnection;
    use rustls::pki_types::pem::PemObject;
    use rustls::pki_types::{CertificateDer, PrivateKeyDer};
    use rustls::server::WebPkiClientVerifier;
    use zk_proto::HostToken;

    /// A file of the TLS test fixtures (`zk_testkit::fixtures::tls_dir`).
    fn fixture(name: &str) -> PathBuf {
        zk_testkit::fixtures::tls_dir().join(name)
    }

    fn read(name: &str) -> Vec<u8> {
        std::fs::read(fixture(name)).unwrap()
    }

    /// A temporary file or directory that is removed on drop.
    struct Temp(PathBuf);

    impl Temp {
        fn path() -> PathBuf {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let n = NEXT.fetch_add(1, Ordering::Relaxed);
            std::env::temp_dir().join(format!("zk-client-tls-{}-{n}", std::process::id()))
        }

        fn file(body: &[u8]) -> Self {
            let p = Self::path();
            std::fs::write(&p, body).unwrap();
            Self(p)
        }
    }

    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn inner(o: &TlsOptions) -> &TlsInner {
        &o.inner
    }

    fn attempt(o: &TlsOptions) -> Result<Arc<ClientConfig>, TlsSetupError> {
        inner(o).client_config(&Unlocked::new_at_entry(), TlsPurpose::Main)
    }

    fn err_of(r: Result<TlsOptions, TlsConfigError>) -> TlsConfigError {
        r.err().unwrap()
    }

    fn no_env(_: &str) -> Option<OsString> {
        None
    }

    // ---- Display, parsing and the environment rule ----

    #[test]
    fn the_cert_string_follows_strtok() {
        assert_eq!(split_cert_string(""), [None; 4]);
        assert_eq!(
            split_cert_string("ca,cert,key,pw"),
            [Some("ca"), Some("cert"), Some("key"), Some("pw")]
        );
        // Consecutive delimiters collapse, shifting subsequent tokens forward.
        assert_eq!(
            split_cert_string("ca,,key,pw"),
            [Some("ca"), Some("key"), Some("pw"), None]
        );
        assert_eq!(split_cert_string(",,ca,"), [Some("ca"), None, None, None]);
        // Surrounding whitespace is preserved within tokens, and trailing fields beyond four are
        // discarded.
        assert_eq!(
            split_cert_string(" ca ,b,c,d,e"),
            [Some(" ca "), Some("b"), Some("c"), Some("d")]
        );
    }

    #[test]
    fn a_normal_test_process_allows_the_environment() {
        assert!(env_allowed());
    }

    #[test]
    fn the_environment_replaces_the_defaults_only_when_allowed() {
        let var = |name: &str| match name {
            "SSL_CERT_FILE" => Some(OsString::from("/env/cert.pem")),
            "SSL_CERT_DIR" => Some(OsString::from("/env/a:/env/b")),
            _ => None,
        };
        let allowed = DefaultLocations::select(true, &var);
        assert_eq!(allowed.file, Some(PathBuf::from("/env/cert.pem")));
        #[cfg(unix)]
        assert_eq!(
            allowed.dirs,
            vec![PathBuf::from("/env/a"), PathBuf::from("/env/b")]
        );
        let refused = DefaultLocations::select(false, &var);
        let defaults = DefaultLocations::select(true, &no_env);
        assert_eq!(refused.file, defaults.file);
        assert_eq!(refused.dirs, defaults.dirs);
    }

    #[test]
    fn the_default_locations_use_the_strict_loader() {
        let locs = DefaultLocations {
            file: Some(fixture("ca.pem")),
            dirs: vec![PathBuf::from("/no/such/dir")],
        };
        let mut trust = TrustSet::default();
        assert_eq!(locs.add_to(&mut trust), 1);

        // Missing or corrupt trust files are skipped gracefully without populating the store.
        let bad = Temp::file(b"-----BEGIN CERTIFICATE-----\n!!!\n-----END CERTIFICATE-----\n");
        for file in [bad.0.clone(), PathBuf::from("/no/such/file")] {
            let locs = DefaultLocations {
                file: Some(file),
                dirs: Vec::new(),
            };
            let mut trust = TrustSet::default();
            assert_eq!(locs.add_to(&mut trust), 0);
        }
    }

    // ---- build ----

    #[test]
    fn build_reads_a_good_ca_file() {
        let o = TlsOptions::builder()
            .ca_file(fixture("ca.pem"))
            .build()
            .unwrap();
        assert_eq!(o.tls_verify(), TlsVerify::WebPki);
        assert!(o.check().is_ok());
        assert!(attempt(&o).is_ok());
        assert!(o.error_probe()().is_none());
    }

    #[test]
    fn system_roots_with_no_root_fail_with_default_roots() {
        // Validation outcome depends on trust store contents: absence of valid root anchors
        // triggers DefaultRoots.
        let mut trust = TrustSet::default();
        DefaultLocations::select(env_allowed(), &|n| std::env::var_os(n)).add_to(&mut trust);
        let found = add_roots(trust.certs(), &mut RootCertStore::empty());
        let r = TlsOptions::builder().add_system_roots().build();
        if found == 0 {
            assert!(matches!(r, Err(TlsConfigError::DefaultRoots)));
        } else {
            assert!(r.is_ok());
        }
        let files = FileSources {
            system_roots: true,
            ..TlsOptions::builder().files
        };
        let empty = DefaultLocations::default();
        assert!(matches!(
            make_config(&files, false, Some(&empty)),
            Err(TlsConfigError::DefaultRoots)
        ));
        let one = DefaultLocations {
            file: Some(fixture("ca.pem")),
            dirs: Vec::new(),
        };
        assert!(make_config(&files, false, Some(&one)).is_ok());
    }

    #[test]
    fn a_crl_only_ca_file_loads_but_gives_no_root() {
        // Minimal synthetic CRL v2 with empty issuer and timestamp; signature validation is
        // bypassed during discovery.
        let crl = Temp::file(
            b"-----BEGIN X509 CRL-----\n\
              MDEwIAIBATAKBggqhkjOPQQDAjAAFw0yNTAxMDEwMDAwMDBaMAoGCCqGSM49BAMCAwEA\n\
              -----END X509 CRL-----\n",
        );
        let mut trust = TrustSet::default();
        load_ca_file(&crl.0, &mut trust).unwrap();
        assert!(trust.certs().is_empty());
        assert_eq!(trust.crl_count(), 1);
        // Modern verification requires at least one root trust anchor; CRL-only input fails CA
        // file loading.
        let e = err_of(TlsOptions::builder().ca_file(&crl.0).build());
        assert!(matches!(&e, TlsConfigError::CaFile(p) if *p == crl.0));
        // C-client legacy compatibility mode tolerates CRL-only inputs matching historical
        // drop in behavior.
        let files = FileSources {
            ca_file: Some(crl.0.clone()),
            no_hostname_check: true,
            ..TlsOptions::builder().files
        };
        assert!(make_config(&files, true, None).is_ok());
    }

    #[test]
    fn from_rustls_passes_the_check_and_gives_its_config() {
        let cfg = Arc::new(
            ClientConfig::builder_with_provider(default_provider())
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_root_certificates(RootCertStore::empty())
                .with_no_client_auth(),
        );
        let o = TlsOptions::from_rustls(Arc::clone(&cfg));
        assert!(o.check().is_ok());
        assert_eq!(o.tls_verify(), TlsVerify::WebPki);
        assert!(Arc::ptr_eq(&attempt(&o).unwrap(), &cfg));
    }

    // ---- legacy_c_compat ----

    #[test]
    fn legacy_c_compat_reads_the_fields() {
        let ca = fixture("ca.pem");
        let o = TlsOptions::legacy_c_compat(ca.to_str().unwrap()).unwrap();
        assert_eq!(o.tls_verify(), TlsVerify::LegacyChainOnly);
        let cfg = attempt(&o).unwrap();
        assert!(!cfg.enable_sni);

        // Mutual TLS identity resolves without credentials when the private key is unencrypted.
        let s = format!(
            "{},{},{}",
            ca.display(),
            fixture("client.pem").display(),
            fixture("client.key").display()
        );
        assert!(TlsOptions::legacy_c_compat(&s).is_ok());

        // Providing a passphrase with an unencrypted key is tolerated without error.
        let s = format!("{s},secret");
        assert!(TlsOptions::legacy_c_compat(&s).is_ok());

        let key = fixture("ca.pem");
        let s = format!(
            "{},{},{}",
            ca.display(),
            fixture("client.pem").display(),
            key.display()
        );
        let e = err_of(TlsOptions::legacy_c_compat(&s));
        assert!(matches!(&e, TlsConfigError::PrivateKey(p) if *p == key));
    }

    #[test]
    fn legacy_c_compat_never_reports_default_roots() {
        let files = FileSources {
            ca_file: Some(fixture("ca.pem")),
            system_roots: true,
            no_hostname_check: true,
            ..TlsOptions::builder().files
        };
        let empty = DefaultLocations::default();
        assert!(make_config(&files, true, Some(&empty)).is_ok());
    }

    // ---- the reload cache ----

    #[test]
    fn on_change_reads_a_changed_file_again() {
        let ca = Temp::file(&read("ca.pem"));
        let o = TlsOptions::builder().ca_file(&ca.0).build().unwrap();
        let first = attempt(&o).unwrap();
        assert!(Arc::ptr_eq(&first, &attempt(&o).unwrap()));

        // Appending comment bytes alters file length and modification timestamps without altering
        // parsed trust anchors.
        let mut body = read("ca.pem");
        body.extend_from_slice(b"# rotated\n");
        std::fs::write(&ca.0, &body).unwrap();
        let second = attempt(&o).unwrap();
        assert!(!Arc::ptr_eq(&first, &second));
        assert!(Arc::ptr_eq(&second, &attempt(&o).unwrap()));
    }

    #[test]
    fn a_failed_reload_fails_the_attempt_and_feeds_the_probe() {
        let ca = Temp::file(&read("ca.pem"));
        let o = TlsOptions::builder().ca_file(&ca.0).build().unwrap();
        let probe = o.error_probe();
        assert!(attempt(&o).is_ok());

        std::fs::write(&ca.0, b"not a certificate").unwrap();
        assert_eq!(
            attempt(&o).err(),
            Some(TlsSetupError::Config {
                step: TlsConfigStep::CaFile
            })
        );
        let e = probe().unwrap();
        assert_eq!(
            e.to_string(),
            format!("failed to load CA file {}", ca.0.display())
        );
        // Subsequent connection attempts encounter the corrupted credential file until rectified.
        assert!(attempt(&o).is_err());

        std::fs::write(&ca.0, read("ca.pem")).unwrap();
        assert!(attempt(&o).is_ok());
        assert!(probe().is_none());
    }

    #[test]
    fn never_keeps_the_first_configuration() {
        let ca = Temp::file(&read("ca.pem"));
        let o = TlsOptions::builder()
            .ca_file(&ca.0)
            .reload(ReloadPolicy::Never)
            .build()
            .unwrap();
        let first = attempt(&o).unwrap();
        std::fs::write(&ca.0, b"not a certificate").unwrap();
        assert!(Arc::ptr_eq(&first, &attempt(&o).unwrap()));
        assert!(o.error_probe()().is_none());
    }

    #[test]
    fn the_error_steps_follow_the_driver() {
        let p = PathBuf::from("x");
        let cases = [
            (TlsConfigError::CaFile(p.clone()), TlsConfigStep::CaFile),
            (
                TlsConfigError::DefaultRoots,
                TlsConfigStep::DefaultVerifyPaths,
            ),
            (
                TlsConfigError::CertChain(p.clone()),
                TlsConfigStep::ClientCertChain,
            ),
            (TlsConfigError::PrivateKey(p), TlsConfigStep::ClientKey),
            (TlsConfigError::KeyMismatch, TlsConfigStep::CheckPrivateKey),
        ];
        for (e, step) in cases {
            assert_eq!(setup_error(&e), TlsSetupError::Config { step });
        }
        assert_eq!(
            setup_error(&TlsConfigError::Context),
            TlsSetupError::Resource {
                at: TlsResourceAt::ContextNew
            }
        );
    }

    // ---- handshakes through the connector, in memory ----

    fn certs(name: &str) -> Vec<CertificateDer<'static>> {
        CertificateDer::pem_slice_iter(&read(name))
            .collect::<Result<_, _>>()
            .unwrap()
    }

    /// Instantiates an in-memory TLS server for tests, conditionally enforcing client mutual-TLS
    /// authentication.
    fn server(leaf: &str, client_auth: bool) -> ServerConnection {
        let key = PrivateKeyDer::from_pem_slice(&read(&format!("{leaf}.key"))).unwrap();
        let b = rustls::ServerConfig::builder_with_provider(default_provider())
            .with_safe_default_protocol_versions()
            .unwrap();
        let b = if client_auth {
            let mut roots = RootCertStore::empty();
            for c in certs("ca.pem") {
                roots.add(c).unwrap();
            }
            let v =
                WebPkiClientVerifier::builder_with_provider(Arc::new(roots), default_provider())
                    .build()
                    .unwrap();
            b.with_client_cert_verifier(v)
        } else {
            b.with_no_client_auth()
        };
        let cfg = b
            .with_single_cert(certs(&format!("{leaf}.pem")), key)
            .unwrap();
        ServerConnection::new(Arc::new(cfg)).unwrap()
    }

    fn endpoint(host: &str) -> Endpoint {
        Endpoint {
            addr: SocketAddr::from(([127, 0, 0, 1], 2281)),
            host: HostToken {
                index: 0,
                name: host.as_bytes().to_vec(),
            },
        }
    }

    /// Drives in-memory TLS handshake progression between client connector session and server
    /// connection.
    fn handshake(o: &TlsOptions, host: &str, server: &mut ServerConnection) -> Result<(), String> {
        let c = o.connector();
        let mut client = c
            .new_conn(&Unlocked::new_at_entry(), TlsPurpose::Main, &endpoint(host))
            .map_err(|e| format!("{e:?}"))?;
        for _ in 0..16 {
            let mut ct = Vec::new();
            client.take_ciphertext(&mut ct);
            let mut input = &ct[..];
            while !input.is_empty() {
                server.read_tls(&mut input).map_err(|e| e.to_string())?;
                server.process_new_packets().map_err(|e| e.to_string())?;
            }
            let mut back = Vec::new();
            while server.wants_write() {
                server.write_tls(&mut back).map_err(|e| e.to_string())?;
            }
            if !back.is_empty() {
                client.feed(&back).map_err(|e| format!("client {e:?}"))?;
            }
            if !client.is_handshaking() && !server.is_handshaking() {
                return Ok(());
            }
        }
        Err("no progress".to_owned())
    }

    fn ca_options() -> TlsOptionsBuilder {
        TlsOptions::builder().ca_file(fixture("ca.pem"))
    }

    #[test]
    fn webpki_checks_the_host_token_and_sends_sni() {
        let o = ca_options().build().unwrap();
        let mut s = server("server", false);
        handshake(&o, "localhost", &mut s).unwrap();
        assert_eq!(s.server_name(), Some("localhost"));

        // IP address endpoints validate against IP Subject Alternative Names without transmitting
        // SNI extensions.
        let mut s = server("server", false);
        handshake(&o, "127.0.0.1", &mut s).unwrap();
        assert_eq!(s.server_name(), None);

        // Verification fails when the presented certificate does not match the requested hostname.
        let mut s = server("other-name", false);
        assert!(handshake(&o, "localhost", &mut s).is_err());
    }

    #[test]
    fn webpki_refuses_another_ca_and_an_expired_leaf() {
        let o = ca_options().build().unwrap();
        let mut s = server("wrong-server", false);
        assert!(handshake(&o, "localhost", &mut s).is_err());
        let mut s = server("expired", false);
        assert!(handshake(&o, "localhost", &mut s).is_err());
    }

    #[test]
    fn server_name_replaces_the_host_token() {
        let name = ServerName::try_from("other.invalid").unwrap();
        let o = ca_options().server_name(name).build().unwrap();
        let mut s = server("other-name", false);
        handshake(&o, "localhost", &mut s).unwrap();
        assert_eq!(s.server_name(), Some("other.invalid"));
        let mut s = server("server", false);
        assert!(handshake(&o, "localhost", &mut s).is_err());
    }

    #[test]
    fn no_hostname_check_keeps_the_chain_check() {
        let o = ca_options()
            .danger_disable_hostname_verification()
            .build()
            .unwrap();
        let mut s = server("other-name", false);
        handshake(&o, "localhost", &mut s).unwrap();
        let mut s = server("wrong-server", false);
        assert!(handshake(&o, "localhost", &mut s).is_err());
    }

    #[test]
    fn legacy_c_compat_checks_the_chain_only_with_no_sni() {
        let o = TlsOptions::legacy_c_compat(fixture("ca.pem").to_str().unwrap()).unwrap();
        let mut s = server("other-name", false);
        handshake(&o, "localhost", &mut s).unwrap();
        assert_eq!(s.server_name(), None);
        let mut s = server("wrong-server", false);
        assert!(handshake(&o, "localhost", &mut s).is_err());
    }

    #[test]
    fn the_client_identity_goes_to_the_server() {
        let o = ca_options()
            .client_identity(fixture("client.pem"), fixture("client.key"), None)
            .build()
            .unwrap();
        let mut s = server("server", true);
        handshake(&o, "localhost", &mut s).unwrap();
        assert!(s.peer_certificates().is_some_and(|c| !c.is_empty()));

        let o = ca_options().build().unwrap();
        let mut s = server("server", true);
        assert!(handshake(&o, "localhost", &mut s).is_err());
    }

    #[test]
    fn tls13_only_refuses_a_tls12_server() {
        let o = ca_options()
            .min_protocol(&rustls::version::TLS13)
            .build()
            .unwrap();
        let key = PrivateKeyDer::from_pem_slice(&read("server.key")).unwrap();
        let cfg = rustls::ServerConfig::builder_with_provider(default_provider())
            .with_protocol_versions(&[&rustls::version::TLS12])
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(certs("server.pem"), key)
            .unwrap();
        let mut s = ServerConnection::new(Arc::new(cfg)).unwrap();
        assert!(handshake(&o, "localhost", &mut s).is_err());
        let mut s = server("server", false);
        handshake(&o, "localhost", &mut s).unwrap();
    }

    #[test]
    fn the_connector_names_the_files_for_the_log() {
        let o = ca_options()
            .client_identity(fixture("client.pem"), fixture("client.key"), None)
            .build()
            .unwrap();
        let c = o.connector();
        assert_eq!(
            c.path_for(TlsConfigStep::CaFile),
            Some(path_bytes(&fixture("ca.pem")).as_slice())
        );
        assert_eq!(
            c.path_for(TlsConfigStep::ClientKey),
            Some(path_bytes(&fixture("client.key")).as_slice())
        );
        assert_eq!(c.path_for(TlsConfigStep::CheckPrivateKey), None);
    }
}
