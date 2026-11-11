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

//! `RustlsConnector`: orchestrates TLS client configuration and session instantiation across
//! connection attempts and read-only cluster probes.
//!
//! # Legacy C Stage-Order Parity vs. Native Configuration
//!
//! [`RustlsConnector`] serves both the drop in C compatibility drivers (`ConfigSource::Legacy`)
//! and the native Rust drivers (`ConfigSource::Native`):
//!
//! - **Drop-In Legacy Mode (`ConfigSource::Legacy`)**: To maintain strict diagnostic and behavioral
//!   compatibility with the legacy C OpenSSL client (`zookeeper.c`), [`RustlsConnector::new_conn`]
//!   re-evaluates certificate files on each connection attempt in the exact procedural order of
//!   C `init_ssl_for_socket` (`zookeeper.c:2847-2935`), halting at the first failure and reporting
//!   the corresponding [`TlsSetupError`]:
//!   1. *Context Initialization* (`TlsResourceAt::ContextNew`, `"error creating ssl context"`):
//!      Initializes the `ClientConfig` builder with TLS 1.3 and TLS 1.2 support.
//!   2. *CA Certificate Loading* (`TlsConfigStep::CaFile`, `"Failed to load CA file %s"`): Loads
//!      the configured CA PEM file via [`TrustSet::load_ca_file`].
//!   3. *System Trust Anchors* (`TlsConfigStep::DefaultVerifyPaths`,
//!      `"Call to SSL_CTX_set_default_verify_paths failed"`): Loads system default trust paths and
//!      (when `env_allowed` is true) `SSL_CERT_FILE` / `SSL_CERT_DIR` via
//!      [`TrustSet::load_default_paths`].
//!   4. *Client Certificate Chain* (`TlsConfigStep::ClientCertChain`,
//!      `"Failed to load client certificate chain from %s"`), *Client Private Key*
//!      (`TlsConfigStep::ClientKey`, `"Failed to load client private key from %s"`), and *Private
//!      Key Consistency Check* (`TlsConfigStep::CheckPrivateKey`,
//!      `"SSL_CTX_check_private_key failed"`): Validated in sequence by [`Identity::load`] when
//!      both `cert` and `key` paths are supplied.
//!   5. *Session Instantiation* (`TlsResourceAt::ConnectionNew`, `"error creating ssl context"`):
//!      Instantiates the [`rustls::ClientConnection`] bound to `ServerName::IpAddress` (omitting
//!      SNI and disabling session resumption, matching C OpenSSL behavior).
//! - **Native Rust Mode (`ConfigSource::Native`)**: Delegates `Arc<ClientConfig>` retrieval to the
//!   caller's [`NativeConfig`] implementation (enabling cached configurations or dynamic credential
//!   rotation) and derives a full `ServerName` (DNS hostname or IP literal) from `peer.host` so SNI
//!   and WebPKI hostname verification operate normally.
//!
//! Cryptographic operations utilize an explicitly supplied `CryptoProvider`, ensuring that the
//! global process default provider remains untouched.

use std::fs::File;
use std::io::Read as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use rustls::client::Resumption;
use rustls::client::danger::ServerCertVerifier;
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{ServerName, UnixTime};
use rustls::time_provider::TimeProvider;
use rustls::{ClientConfig, ClientConnection};
use zk_proto::SecretBytes;
use zk_proto::compat::TlsVerify;

use crate::net::tls::conn::RustlsConn;
use crate::net::tls::identity::{Identity, LegacyClientCert};
use crate::net::tls::legacy_verify::LegacyChainOnly;
use crate::net::tls::trust::TrustSet;
use crate::net::tls::verify::webpki_verifier;
use crate::net::tls::{
    Endpoint, TlsConfigStep, TlsConn, TlsConnector, TlsPurpose, TlsResourceAt, TlsSetupError,
};
use crate::sync::Unlocked;

/// File path representation for legacy certificate and key configurations.
///
/// Encapsulates both the raw input bytes (retained for diagnostic logging and error reporting)
/// and an ordered list of candidate filesystem paths to inspect. On Unix platforms, the byte
/// sequence maps directly to a single native path candidate. On Windows systems, candidate paths
/// incorporate ANSI code-page conversions bridged from `zk-ffi`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LegacyPath {
    raw: Vec<u8>,
    open: Vec<PathBuf>,
}

impl LegacyPath {
    /// Constructs a path candidate directly from raw byte sequences on Unix platforms.
    #[cfg(unix)]
    #[must_use]
    pub fn new(raw: Vec<u8>) -> Self {
        use std::os::unix::ffi::OsStrExt;
        let open = vec![PathBuf::from(std::ffi::OsStr::from_bytes(&raw))];
        Self { raw, open }
    }

    /// Constructs a path representation with an explicit candidate sequence (e.g., Windows code
    /// page variants).
    #[must_use]
    pub fn with_candidates(raw: Vec<u8>, open: Vec<PathBuf>) -> Self {
        Self { raw, open }
    }

    /// Returns a reference to the raw underlying byte representation.
    #[must_use]
    pub fn raw(&self) -> &[u8] {
        &self.raw
    }

    /// Returns the ordered slice of filesystem candidate paths to attempt opening.
    #[must_use]
    pub fn candidates(&self) -> &[PathBuf] {
        &self.open
    }
}

/// TLS initialization parameters configured on a drop-in legacy handle.
///
/// Reconstructs the nullable parameters parsed from the legacy certificate specification string,
/// preserving standard C OpenSSL configuration semantics.
#[derive(Debug)]
pub struct LegacyTlsArgs {
    /// Path to the Certificate Authority (CA) verification file.
    pub ca: Option<LegacyPath>,
    /// Path to the client public certificate chain file.
    pub cert: Option<LegacyPath>,
    /// Path to the client private key file.
    pub key: Option<LegacyPath>,
    /// Optional decryption passphrase protecting the client private key (truncated to at most 1024
    /// bytes).
    pub passwd: Option<SecretBytes>,
    /// Security flag indicating whether environment variables (`SSL_CERT_FILE`, `SSL_CERT_DIR`)
    /// may be inspected. Disabled in set-uid and set-gid binaries to prevent privilege escalation.
    pub env_allowed: bool,
    /// Certificate verification policy selecting between legacy chain validation and modern WebPKI
    /// rules.
    pub verify: TlsVerify,
}

/// Dynamic TLS configuration source for native Rust client sessions.
///
/// Designed to decouple client-level configuration lifecycle policies (such as periodic certificate
/// renewal or runtime credential reloading) from the internal driver event loop. Evaluated outside
/// of driver mutex locks.
pub trait NativeConfig: Send + Sync {
    /// Generates or retrieves an active `ClientConfig` instance for a new transport session.
    ///
    /// # Errors
    ///
    /// Returns a [`TlsSetupError`] detailing the specific configuration step or system resource
    /// that failed.
    fn client_config(
        &self,
        proof: &Unlocked,
        purpose: TlsPurpose,
    ) -> Result<Arc<rustls::ClientConfig>, TlsSetupError>;
}

/// Origin source for TLS client configurations.
enum ConfigSource {
    /// Drop-in legacy compatibility mode: re-evaluates filesystem certificates on every connection
    /// attempt.
    Legacy(LegacyTlsArgs),
    /// Native Rust configuration provider managed dynamically by the high-level client.
    Native(Arc<dyn NativeConfig>),
}

/// Rustls connector engine serving both drop-in compatibility and native driver runtimes.
pub struct RustlsConnector {
    src: ConfigSource,
    provider: Arc<CryptoProvider>,
    /// Optional time provider for temporal certificate validation; defaults to system wall-clock
    /// time.
    time: Option<Arc<dyn TimeProvider>>,
}

impl RustlsConnector {
    /// Constructs a connector operating in drop-in legacy compatibility mode without eagerly
    /// reading files.
    #[must_use]
    pub fn legacy(args: LegacyTlsArgs, provider: Arc<CryptoProvider>) -> Self {
        Self {
            src: ConfigSource::Legacy(args),
            provider,
            time: None,
        }
    }

    /// Constructs a connector operating against a dynamic native client configuration source.
    #[must_use]
    pub fn native(src: Arc<dyn NativeConfig>, provider: Arc<CryptoProvider>) -> Self {
        Self {
            src: ConfigSource::Native(src),
            provider,
            time: None,
        }
    }

    /// Configures an explicit time provider for certificate validity verification, primarily in
    /// test harnesses.
    #[must_use]
    pub fn with_time_provider(mut self, time: Arc<dyn TimeProvider>) -> Self {
        self.time = Some(time);
        self
    }

    /// Assembles a drop-in `ClientConfig` for an individual connection attempt, mirroring legacy
    /// OpenSSL setup steps.
    ///
    /// Configures support for TLS 1.2 and 1.3, disables session resumption and SNI, applies the
    /// requested verification policy, and attaches mutual TLS identity when both client certificate
    /// and private key are supplied. In FIPS builds, validates that the configuration meets
    /// cryptographic FIPS standards.
    fn legacy_config(&self, args: &LegacyTlsArgs) -> Result<rustls::ClientConfig, TlsSetupError> {
        // Initialize the client configuration builder, emulating SSL_CTX_new.
        let provider = Arc::clone(&self.provider);
        let builder = match &self.time {
            Some(t) => ClientConfig::builder_with_details(Arc::clone(&provider), Arc::clone(t)),
            None => ClientConfig::builder_with_provider(Arc::clone(&provider)),
        };
        let builder = builder
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
            .map_err(|_| resource(TlsResourceAt::ContextNew))?;

        // Load filesystem trust anchors and client identity credentials before attaching verifiers.
        let (verifier, identity) = self.legacy_inputs(args)?;
        let builder = builder
            .dangerous()
            .with_custom_certificate_verifier(verifier);
        let mut cfg = match identity {
            Some(id) => builder.with_client_cert_resolver(Arc::new(LegacyClientCert(Arc::clone(
                id.certified_key(),
            )))),
            None => builder.with_no_client_auth(),
        };
        // The legacy C client omits SNI extensions and creates fresh SSL_CTX contexts per attempt,
        // precluding session resumption or cross-attempt cache sharing.
        cfg.enable_sni = false;
        cfg.resumption = Resumption::disabled();
        fips_check(&mut cfg)?;
        Ok(cfg)
    }
}

impl RustlsConnector {
    /// Executes certificate file loading in the exact sequence expected by legacy C callers.
    ///
    /// Resolves the trust anchors, loads client identity keys when both certificate and private key
    /// paths are specified, and builds the target `ServerCertVerifier`. All certificate and key
    /// files are re-read from disk on every connection invocation to reflect updates immediately.
    #[inline(never)]
    fn legacy_inputs(
        &self,
        args: &LegacyTlsArgs,
    ) -> Result<(Arc<dyn ServerCertVerifier>, Option<Identity>), TlsSetupError> {
        // Step `CaFile`: Corresponds to SSL_CTX_load_verify_locations(ctx, ca, NULL).
        let mut trust = TrustSet::default();
        trust
            .load_ca_file(args.ca.as_ref())
            .map_err(|_| config(TlsConfigStep::CaFile))?;

        // Step `DefaultVerifyPaths`: Corresponds to SSL_CTX_set_default_verify_paths(ctx).
        trust
            .load_default_paths(args.env_allowed)
            .map_err(|_| config(TlsConfigStep::DefaultVerifyPaths))?;

        // Steps `ClientCertChain`, `ClientKey`, and `CheckPrivateKey`.
        // Whereas the legacy C driver skipped client identity loading when no password was
        // provided, this implementation treats an absent password as an empty passphrase.
        // Consequently, unencrypted keys load seamlessly without a passphrase, while encrypted keys
        // lacking a passphrase fail explicitly at the `ClientKey` stage.
        let identity = match (&args.cert, &args.key) {
            (Some(cert), Some(key)) => {
                let none = SecretBytes::new(Vec::new());
                let passwd = args.passwd.as_ref().unwrap_or(&none);
                Some(Identity::load(cert, key, passwd, &self.provider)?)
            }
            _ => None,
        };

        let verifier: Arc<dyn ServerCertVerifier> = match args.verify {
            TlsVerify::LegacyChainOnly => Arc::new(LegacyChainOnly::new(
                trust,
                self.provider.signature_verification_algorithms,
            )),
            TlsVerify::WebPki => {
                let roots = Arc::new(trust.root_store_at(self.now()));
                webpki_verifier(roots, Arc::clone(&self.provider))?
            }
        };
        Ok((verifier, identity))
    }

    /// Resolves the current timestamp for certificate validation from the custom provider or system
    /// clock.
    fn now(&self) -> UnixTime {
        self.time
            .as_ref()
            .and_then(|t| t.current_time())
            .unwrap_or_else(UnixTime::now)
    }

    /// Generates the shared drop-in `ClientConfig` on the heap within an isolated stack frame.
    #[inline(never)]
    fn legacy_shared(&self, args: &LegacyTlsArgs) -> Result<Arc<ClientConfig>, TlsSetupError> {
        self.legacy_config(args).map(Arc::new)
    }
}

/// Instantiates a new client TLS connection and wraps it in a boxed transport trait object.
///
/// Encapsulated in an out-of-line helper function to keep the caller's stack frame minimal.
#[inline(never)]
fn new_session(
    cfg: Arc<ClientConfig>,
    name: ServerName<'static>,
) -> Result<Box<dyn TlsConn>, TlsSetupError> {
    match ClientConnection::new(cfg, name).map(boxed_conn) {
        Ok(conn) => Ok(conn),
        Err(_) => Err(resource(TlsResourceAt::ConnectionNew)),
    }
}

/// Boxes a concrete [`RustlsConn`] session instance behind the abstract [`TlsConn`] interface.
#[inline(never)]
fn boxed_conn(conn: ClientConnection) -> Box<dyn TlsConn> {
    Box::new(RustlsConn::new(conn))
}

/// Convenience constructor for configuration-related TLS setup errors.
fn config(step: TlsConfigStep) -> TlsSetupError {
    TlsSetupError::Config { step }
}

/// Convenience constructor for system resource failure errors during TLS setup.
fn resource(at: TlsResourceAt) -> TlsSetupError {
    TlsSetupError::Resource { at }
}

/// Enforces Extended Master Secret (EMS) and FIPS compliance validation when built under FIPS mode.
#[cfg(feature = "tls-aws-lc-rs-fips")]
fn fips_check(cfg: &mut ClientConfig) -> Result<(), TlsSetupError> {
    cfg.require_ems = true;
    if cfg.fips() {
        Ok(())
    } else {
        Err(resource(TlsResourceAt::ContextNew))
    }
}

/// FIPS verification pass-through for non-FIPS builds.
#[cfg(not(feature = "tls-aws-lc-rs-fips"))]
#[allow(clippy::unnecessary_wraps)]
fn fips_check(_cfg: &mut ClientConfig) -> Result<(), TlsSetupError> {
    Ok(())
}

/// Reads and returns the contents of the first candidate path resolving to a regular file within
/// [`MAX_FILE`] bytes.
///
/// Missing, non-regular, or oversized candidates are skipped. See [`read_regular`] for platform
/// guarantees preventing FIFO or device paths from blocking the calling thread.
pub(crate) fn read_path(path: &LegacyPath) -> Option<Vec<u8>> {
    path.candidates()
        .iter()
        .find_map(|candidate| read_regular(candidate).ok())
}

/// Maximum allowable size for a CA bundle, certificate chain, or private key file (32 MiB),
/// bounding heap memory consumption against oversized or unbounded files.
pub(crate) const MAX_FILE: u64 = 0x0200_0000;

/// Failure categories encountered when reading a filesystem credential file.
pub(crate) enum ReadError {
    /// Target filesystem path does not exist.
    NotFound,
    /// Read failed, the target is not a regular file, or the file size exceeds [`MAX_FILE`].
    Other,
}

/// Reads a regular file bounded to at most [`MAX_FILE`] bytes into a single sized buffer.
///
/// Validates file metadata both before and after opening the descriptor to reject non-regular
/// files (such as FIFOs or device nodes); on Unix targets, [`open_for_read`] sets `O_NONBLOCK` so
/// opening a swapped-in FIFO returns immediately without blocking. Reserving the exact file
/// size avoids intermediate reallocations when reading stable private key files, ensuring no
/// partial copies of secret key bytes remain in abandoned heap blocks.
pub(crate) fn read_regular(path: &Path) -> Result<Vec<u8>, ReadError> {
    let meta = std::fs::metadata(path).map_err(|e| match e.kind() {
        std::io::ErrorKind::NotFound => ReadError::NotFound,
        _ => ReadError::Other,
    })?;
    if !meta.is_file() || meta.len() > MAX_FILE {
        return Err(ReadError::Other);
    }
    let file = open_for_read(path).map_err(|_| ReadError::Other)?;
    let meta = file.metadata().map_err(|_| ReadError::Other)?;
    if !meta.is_file() || meta.len() > MAX_FILE {
        return Err(ReadError::Other);
    }
    let mut buf = Vec::new();
    let hint = usize::try_from(meta.len()).map_err(|_| ReadError::Other)?;
    buf.try_reserve_exact(hint).map_err(|_| ReadError::Other)?;
    file.take(MAX_FILE.saturating_add(1))
        .read_to_end(&mut buf)
        .map_err(|_| ReadError::Other)?;
    if u64::try_from(buf.len()).map_or(true, |n| n > MAX_FILE) {
        return Err(ReadError::Other);
    }
    Ok(buf)
}

/// Opens a file descriptor for reading on Unix with `O_NONBLOCK` and `O_NOCTTY`.
///
/// `O_NONBLOCK` prevents `open` from stalling on a named pipe (FIFO) or character device awaiting
/// a writer while having no effect on regular file reads, and `O_NOCTTY` prevents terminal devices
/// from becoming the process's controlling terminal.
#[cfg(unix)]
fn open_for_read(path: &Path) -> std::io::Result<File> {
    use rustix::fs::{Mode, OFlags};
    let flags = OFlags::RDONLY | OFlags::NONBLOCK | OFlags::NOCTTY | OFlags::CLOEXEC;
    let fd = rustix::fs::open(path, flags, Mode::empty())?;
    Ok(File::from(fd))
}

/// Opens a file for reading using standard platform flags.
#[cfg(not(unix))]
fn open_for_read(path: &Path) -> std::io::Result<File> {
    File::open(path)
}

/// Derives a typed `ServerName` for native client sessions from the destination host token.
///
/// Converts IP address literals into numerical server names (suppressing SNI extensions),
/// while domain names retain SNI and enable WebPKI name constraint validation. Strips enclosing
/// IPv6 square brackets where present. Returns `Resource{ConnectionNew}` if the host string is
/// malformed.
fn native_name(peer: &Endpoint) -> Result<ServerName<'static>, TlsSetupError> {
    let err = resource(TlsResourceAt::ConnectionNew);
    let host = std::str::from_utf8(&peer.host.name).map_err(|_| err)?;
    let host = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);
    ServerName::try_from(host.to_owned()).map_err(|_| err)
}

impl TlsConnector for RustlsConnector {
    /// Constructs an active TLS transport session according to the connector profile:
    /// - **Legacy Drop-in**: Builds configuration via `legacy_config`, binding to
    ///   `ServerName::IpAddress` to omit SNI and bypass server identity checks.
    /// - **Native Client**: Evaluates dynamic configuration via `client_config`, extracting the
    ///   server identity from `peer.host` to enable SNI and WebPKI name validation.
    /// - Maps session initialization failures to `Resource{ConnectionNew}`.
    fn new_conn(
        &self,
        proof: &Unlocked,
        purpose: TlsPurpose,
        peer: &Endpoint,
    ) -> Result<Box<dyn TlsConn>, TlsSetupError> {
        // Configuration assembly and session instantiation execute in distinct stack frames
        // to prevent large stack frame accumulation.
        let (cfg, name) = match &self.src {
            // Emulating legacy behavior: use the peer IP as a placeholder target name.
            // Domain verification is skipped and no SNI extension is transmitted.
            ConfigSource::Legacy(args) => (
                self.legacy_shared(args)?,
                ServerName::IpAddress(peer.addr.ip().into()),
            ),
            ConfigSource::Native(src) => (src.client_config(proof, purpose)?, native_name(peer)?),
        };
        new_session(cfg, name)
    }

    fn path_for(&self, step: TlsConfigStep) -> Option<&[u8]> {
        match &self.src {
            ConfigSource::Legacy(args) => match step {
                TlsConfigStep::CaFile => args.ca.as_ref().map(LegacyPath::raw),
                TlsConfigStep::ClientCertChain => args.cert.as_ref().map(LegacyPath::raw),
                TlsConfigStep::ClientKey => args.key.as_ref().map(LegacyPath::raw),
                TlsConfigStep::DefaultVerifyPaths | TlsConfigStep::CheckPrivateKey => None,
            },
            ConfigSource::Native(_) => None,
        }
    }
}

/// Selects and instantiates the configured cryptographic provider implementation.
///
/// Binds to `ring`, `aws-lc-rs`, or FIPS-validated `aws-lc-rs` based on active cargo feature
/// flags.
#[must_use]
pub fn default_provider() -> Arc<CryptoProvider> {
    #[cfg(feature = "tls-ring")]
    let provider = rustls::crypto::ring::default_provider();
    #[cfg(feature = "tls-aws-lc-rs")]
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    #[cfg(feature = "tls-aws-lc-rs-fips")]
    let provider = rustls::crypto::default_fips_provider();
    Arc::new(provider)
}

// Unit test suite verifying certificate loading, legacy parameter mappings, and error
// classifications.
#[cfg(all(test, unix))]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
mod tests {
    use std::net::SocketAddr;
    use std::os::unix::ffi::OsStrExt;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use rustls::SignatureScheme;
    use zk_proto::{Endpoint, HostToken};

    use super::*;
    use crate::net::tls::conn::testing::{self, fixture};

    /// Synthetic PKCS#8 Ed25519 private key for testing mismatched key algorithms against P-256
    /// leaves.
    const ED25519_KEY: &str = "-----BEGIN PRIVATE KEY-----\n\
        MC4CAQAwBQYDK2VwBCIEIFZsce3s/dPi6fmIVU4czLVxub7EWvhZWLqePPyi6WQ/\n\
        -----END PRIVATE KEY-----\n";

    /// RAII guard ensuring temporary filesystem certificates are removed on drop.
    struct Temp(PathBuf);

    impl Temp {
        fn new(body: &[u8]) -> Self {
            static NEXT: AtomicUsize = AtomicUsize::new(0);
            let n = NEXT.fetch_add(1, Ordering::Relaxed);
            let p =
                std::env::temp_dir().join(format!("zk-driver-tls-{}-{n}.pem", std::process::id()));
            std::fs::write(&p, body).unwrap();
            Self(p)
        }

        fn path(&self) -> LegacyPath {
            LegacyPath::new(self.0.as_os_str().as_bytes().to_vec())
        }
    }

    impl Drop for Temp {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn path(name: &str) -> LegacyPath {
        LegacyPath::new(fixture(name).as_os_str().as_bytes().to_vec())
    }

    fn args(ca: Option<LegacyPath>) -> LegacyTlsArgs {
        LegacyTlsArgs {
            ca,
            cert: None,
            key: None,
            passwd: None,
            env_allowed: false,
            verify: TlsVerify::LegacyChainOnly,
        }
    }

    fn identity_args(cert: LegacyPath, key: LegacyPath) -> LegacyTlsArgs {
        LegacyTlsArgs {
            cert: Some(cert),
            key: Some(key),
            passwd: Some(SecretBytes::empty()),
            ..args(Some(path("ca.pem")))
        }
    }

    fn legacy_cfg(args: LegacyTlsArgs) -> Result<ClientConfig, TlsSetupError> {
        RustlsConnector::legacy(args, default_provider()).legacy_config_for_test()
    }

    fn endpoint(name: &[u8]) -> Endpoint {
        Endpoint {
            addr: SocketAddr::from(([127, 0, 0, 1], 2281)),
            host: HostToken {
                index: 0,
                name: name.to_vec(),
            },
        }
    }

    fn new_conn(c: &RustlsConnector, name: &[u8]) -> Result<Box<dyn TlsConn>, TlsSetupError> {
        c.new_conn(&Unlocked::new_at_entry(), TlsPurpose::Main, &endpoint(name))
    }

    const CA_FILE: TlsSetupError = TlsSetupError::Config {
        step: TlsConfigStep::CaFile,
    };
    const CHAIN: TlsSetupError = TlsSetupError::Config {
        step: TlsConfigStep::ClientCertChain,
    };
    const KEY: TlsSetupError = TlsSetupError::Config {
        step: TlsConfigStep::ClientKey,
    };
    const CHECK: TlsSetupError = TlsSetupError::Config {
        step: TlsConfigStep::CheckPrivateKey,
    };

    impl RustlsConnector {
        fn legacy_config_for_test(&self) -> Result<ClientConfig, TlsSetupError> {
            match &self.src {
                ConfigSource::Legacy(args) => self.legacy_config(args),
                ConfigSource::Native(_) => panic!("not a legacy connector"),
            }
        }
    }

    #[test]
    fn legacy_config_has_the_drop_in_settings() {
        let cfg = legacy_cfg(args(Some(path("ca.pem")))).unwrap();
        assert!(!cfg.enable_sni);
        assert!(!cfg.enable_early_data);
        assert!(cfg.alpn_protocols.is_empty());
        assert!(!cfg.client_auth_cert_resolver.has_certs());
        assert!(
            !cfg.crypto_provider()
                .signature_verification_algorithms
                .all
                .is_empty()
        );
    }

    #[test]
    fn missing_ca_file_is_a_config_error() {
        assert_eq!(legacy_cfg(args(None)).err(), Some(CA_FILE));
        let missing = path("no-such-file.pem");
        assert_eq!(legacy_cfg(args(Some(missing))).err(), Some(CA_FILE));
        // Verify behavior when the candidate path list is completely empty.
        let none = LegacyPath::with_candidates(b"x".to_vec(), Vec::new());
        assert_eq!(legacy_cfg(args(Some(none))).err(), Some(CA_FILE));
        // Validate that identical error classifications propagate through new_conn.
        let c = RustlsConnector::legacy(args(None), default_provider());
        assert_eq!(new_conn(&c, b"127.0.0.1").err(), Some(CA_FILE));
    }

    #[test]
    fn ca_file_without_certificates_is_a_config_error() {
        for body in [
            &b"not a pem file\n"[..],
            b"",
            b"-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n",
            b"-----BEGIN CERTIFICATE-----\nMIIB\n",
            b"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
        ] {
            let t = Temp::new(body);
            assert_eq!(legacy_cfg(args(Some(t.path()))).err(), Some(CA_FILE));
        }
        // Private key blocks cannot serve as root trust anchors.
        assert_eq!(legacy_cfg(args(Some(path("ca.key")))).err(), Some(CA_FILE));
    }

    #[test]
    fn ca_file_with_only_a_crl_loads() {
        legacy_cfg(args(Some(path("crl.pem")))).unwrap();
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "the client key load reaches the native code of ring, which Miri cannot run"
    )]
    fn identity_loads_with_cert_and_key() {
        let cfg = legacy_cfg(identity_args(path("client.pem"), path("client.key"))).unwrap();
        assert!(cfg.client_auth_cert_resolver.has_certs());
        let chain = cfg
            .client_auth_cert_resolver
            .resolve(&[], &[SignatureScheme::ECDSA_NISTP256_SHA256])
            .unwrap();
        assert_eq!(chain.cert, testing::certs("client.pem"));

        // Absence of a password parameter is treated as an empty passphrase.
        // Whereas the legacy C driver completely dropped the client identity in this case,
        // this implementation enables unencrypted key loading without requiring dummy passphrases.
        let cfg = legacy_cfg(LegacyTlsArgs {
            passwd: None,
            ..identity_args(path("client.pem"), path("client.key"))
        })
        .unwrap();
        assert!(cfg.client_auth_cert_resolver.has_certs());
        let no_files = LegacyTlsArgs {
            passwd: None,
            ..identity_args(path("no-such-cert.pem"), path("no-such-key.pem"))
        };
        assert_eq!(legacy_cfg(no_files).err(), Some(CHAIN));

        for args in [
            LegacyTlsArgs {
                cert: None,
                ..identity_args(path("client.pem"), path("client.key"))
            },
            LegacyTlsArgs {
                key: None,
                ..identity_args(path("client.pem"), path("client.key"))
            },
        ] {
            // Unless both certificate and key are present, filesystem reads are skipped entirely.
            let cfg = legacy_cfg(LegacyTlsArgs {
                cert: args.cert.map(|_| path("no-such-cert.pem")),
                key: args.key.map(|_| path("no-such-key.pem")),
                ..args
            })
            .unwrap();
            assert!(!cfg.client_auth_cert_resolver.has_certs());
        }
    }

    #[test]
    fn bad_client_chain_is_a_chain_error() {
        let junk = Temp::new(b"junk");
        let bad = Temp::new(b"-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n");
        for cert in [
            path("no-such-cert.pem"),
            junk.path(),
            bad.path(),
            path("client.key"),
        ] {
            assert_eq!(
                legacy_cfg(identity_args(cert, path("client.key"))).err(),
                Some(CHAIN)
            );
        }
        // Verify CA verification occurs prior to client certificate chain evaluation.
        let a = LegacyTlsArgs {
            ca: None,
            ..identity_args(path("no-such-cert.pem"), path("client.key"))
        };
        assert_eq!(legacy_cfg(a).err(), Some(CA_FILE));
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "the client key load reaches the native code of ring, which Miri cannot run"
    )]
    fn bad_client_key_is_a_key_error() {
        let junk = Temp::new(b"junk");
        let bad = Temp::new(b"-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        for key in [
            path("no-such-key.pem"),
            junk.path(),
            bad.path(),
            path("client.pem"),
        ] {
            assert_eq!(
                legacy_cfg(identity_args(path("client.pem"), key)).err(),
                Some(KEY)
            );
        }
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "the client key load reaches the native code of ring, which Miri cannot run"
    )]
    fn key_of_another_leaf_is_a_key_error() {
        // Matching key algorithm but mismatched key material triggers a client key load failure.
        assert_eq!(
            legacy_cfg(identity_args(path("client.pem"), path("server.key"))).err(),
            Some(KEY)
        );
    }

    #[test]
    #[cfg_attr(
        miri,
        ignore = "the client key load reaches the native code of ring, which Miri cannot run"
    )]
    fn key_of_another_type_is_a_check_error() {
        // Discrepant key algorithm types trigger failure at the private key verification stage
        // (SSL_CTX_check_private_key).
        let ed = Temp::new(ED25519_KEY.as_bytes());
        assert_eq!(
            legacy_cfg(identity_args(path("client.pem"), ed.path())).err(),
            Some(CHECK)
        );
    }

    /// Synthetic time provider projecting a timestamp far into the future beyond certificate
    /// validity intervals.
    #[derive(Debug)]
    struct FarFuture;

    impl TimeProvider for FarFuture {
        fn current_time(&self) -> Option<UnixTime> {
            Some(UnixTime::since_unix_epoch(std::time::Duration::from_secs(
                1 << 40,
            )))
        }
    }

    #[test]
    fn legacy_webpki_without_roots_is_a_context_failure() {
        // System default trust paths may populate root anchors; projecting the clock into the far
        // future guarantees that all certificate trust anchors expire, triggering a context
        // initialization error.
        let a = LegacyTlsArgs {
            verify: TlsVerify::WebPki,
            ..args(Some(path("crl.pem")))
        };
        let c =
            RustlsConnector::legacy(a, default_provider()).with_time_provider(Arc::new(FarFuture));
        assert_eq!(
            c.legacy_config_for_test().err(),
            Some(TlsSetupError::Resource {
                at: TlsResourceAt::ContextNew
            })
        );
    }
}
