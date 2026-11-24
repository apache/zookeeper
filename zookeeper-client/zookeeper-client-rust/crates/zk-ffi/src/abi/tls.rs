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

//! TLS connector integration and certificate configuration for secure connections.
//!
//! Reconstructs legacy OpenSSL TLS configuration parameters passed from C (`zookeeper_init_ssl`)
//! via [`zkrs_init_args`]. File paths for CA bundles, client certificates, and private keys are
//! parsed into owned representations, while private key passphrases are copied directly into
//! zeroizing memory containers ([`SecretBytes`]).
//!
//! Instantiates a [`RustlsConnector`] backed by the build's cryptographic provider (`aws-lc-rs`).
//! Conforming to legacy C behavior, certificate and key files are loaded lazily on each connection
//! attempt rather than at handle initialization time, allowing invalid or missing certificate paths
//! to fail during transport connection rather than during initial handle creation.

use std::ffi::c_int;
use std::sync::Arc;

use zk_driver::net::tls::{
    LegacyPath, LegacyTlsArgs, RustlsConnector, TlsConnector, default_provider,
};
use zk_proto::compat::TlsVerify;
use zk_proto::{Config, SecretBytes};

use super::raw;
use super::types::zkrs_init_args;

/// Configures TLS session parameters during handle initialization (`zookeeper_init_ssl`).
///
/// Enables the TLS configuration flag and constructs a [`TlsConnector`] instance. Certificate
/// and key files are validated during connection establishment rather than handle setup.
pub(crate) fn setup(
    a: &zkrs_init_args,
    cfg: &mut Config,
    verify: TlsVerify,
) -> Result<Arc<dyn TlsConnector>, c_int> {
    let c = connector(a, verify);
    cfg.tls = true;
    Ok(c)
}

/// Extracts and parses TLS initialization parameters from raw FFI argument structs.
///
/// Converts null-terminated C string pointers into optional file path descriptors, capturing
/// environment variable overrides (`SSL_CERT_FILE`, `SSL_CERT_DIR`) and zeroized key passphrases.
pub(crate) fn legacy_args(a: &zkrs_init_args, verify: TlsVerify) -> LegacyTlsArgs {
    LegacyTlsArgs {
        ca: raw::cstr_opt(a.tls_ca).map(legacy_path),
        cert: raw::cstr_opt(a.tls_cert).map(legacy_path),
        key: raw::cstr_opt(a.tls_key).map(legacy_path),
        // The passphrase bytes are moved directly into zeroizing storage to prevent lingering
        // copies.
        passwd: raw::cstr_secret(a.tls_passwd)
            .map(|mut p| SecretBytes::new(std::mem::take(&mut *p))),
        env_allowed: a.tls_env_allowed != 0,
        verify,
    }
}

/// Instantiates a TLS connector backed by the active cryptographic provider.
///
/// Builds a [`RustlsConnector`] initialized with legacy path configurations and cryptographic
/// primitives selected by active build features (e.g. `aws-lc-rs` or FIPS mode).
pub(crate) fn connector(a: &zkrs_init_args, verify: TlsVerify) -> Arc<dyn TlsConnector> {
    let provider = default_provider();
    // Abort if a FIPS build is configured with a non-FIPS cryptographic provider.
    #[cfg(feature = "tls-aws-lc-rs-fips")]
    if !provider.fips() {
        super::entry::abort_with("zk-ffi: the TLS provider is not in FIPS mode");
    }
    Arc::new(RustlsConnector::legacy(legacy_args(a, verify), provider))
}

/// Exports a feature probe symbol indicating that `zk-ffi` was compiled with TLS support.
///
/// Used by C build systems and prelink checks to verify TLS symbol availability at link time.
#[allow(unsafe_code, reason = "a C export")]
#[unsafe(no_mangle)]
pub extern "C" fn zkrs_feature_tls() -> u32 {
    1
}

/// Constructs a [`LegacyPath`] on Unix systems, retaining the exact byte sequence.
#[cfg(unix)]
fn legacy_path(raw: Vec<u8>) -> LegacyPath {
    LegacyPath::new(raw)
}

/// Constructs a [`LegacyPath`] on non-Unix systems with character-set candidate conversions.
#[cfg(not(unix))]
fn legacy_path(raw: Vec<u8>) -> LegacyPath {
    let open = std::str::from_utf8(&raw)
        .map(std::path::PathBuf::from)
        .into_iter()
        .collect();
    LegacyPath::with_candidates(raw, open)
}

// The test suite validates TLS configuration behavior using Unix byte paths.
#[cfg(all(test, unix))]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use std::ffi::CString;
    use std::net::SocketAddr;
    use std::path::PathBuf;

    use zk_driver::Unlocked;
    use zk_driver::net::tls::{TlsConfigStep, TlsPurpose, TlsSetupError};
    use zk_proto::{Compat, Endpoint, HostToken};

    use super::*;
    use crate::abi::handle::test_support::init_args;

    /// Test helper holding owned CString instances for TLS path parameters.
    struct Fields {
        ca: Option<CString>,
        cert: Option<CString>,
        key: Option<CString>,
        passwd: Option<CString>,
    }

    fn c(s: Option<&str>) -> Option<CString> {
        s.map(|s| CString::new(s).unwrap())
    }

    fn fields(ca: Option<&str>, cert: Option<&str>, key: Option<&str>, pw: Option<&str>) -> Fields {
        Fields {
            ca: c(ca),
            cert: c(cert),
            key: c(key),
            passwd: c(pw),
        }
    }

    fn ptr(s: &Option<CString>) -> *const std::ffi::c_char {
        s.as_ref().map_or(std::ptr::null(), |s| s.as_ptr())
    }

    /// Constructs a [`zkrs_init_args`] record referencing test string pointers.
    fn args(f: &Fields, env_allowed: u32) -> zkrs_init_args {
        zkrs_init_args {
            tls: 1,
            tls_ca: ptr(&f.ca),
            tls_cert: ptr(&f.cert),
            tls_key: ptr(&f.key),
            tls_passwd: ptr(&f.passwd),
            tls_env_allowed: env_allowed,
            ..init_args(c"127.0.0.1:2181")
        }
    }

    /// Resolves absolute file paths to test certificate fixtures.
    fn fixture(name: &str) -> String {
        zk_testkit::fixtures::tls_dir()
            .join(name)
            .to_str()
            .unwrap()
            .to_owned()
    }

    fn new_conn(c: &dyn TlsConnector) -> Result<(), TlsSetupError> {
        let peer = Endpoint {
            addr: SocketAddr::from(([127, 0, 0, 1], 2281)),
            host: HostToken {
                index: 0,
                name: b"127.0.0.1".to_vec(),
            },
        };
        c.new_conn(&Unlocked::new_at_entry(), TlsPurpose::Main, &peer)
            .map(drop)
    }

    #[test]
    fn null_fields_are_none() {
        let f = fields(None, None, None, None);
        let la = legacy_args(&args(&f, 0), TlsVerify::LegacyChainOnly);
        assert!(la.ca.is_none() && la.cert.is_none() && la.key.is_none());
        assert!(la.passwd.is_none());
        assert!(!la.env_allowed);
        assert_eq!(la.verify, TlsVerify::LegacyChainOnly);
    }

    #[test]
    fn fields_are_copied_as_bytes() {
        let f = fields(
            Some(" ca.pem"),
            Some("c.pem"),
            Some("k.pem"),
            Some("pass word"),
        );
        let la = legacy_args(&args(&f, 1), TlsVerify::WebPki);
        drop(f);
        let ca = la.ca.as_ref().unwrap();
        assert_eq!(ca.raw(), b" ca.pem");
        assert_eq!(ca.candidates(), [PathBuf::from(" ca.pem")]);
        assert_eq!(la.cert.as_ref().unwrap().raw(), b"c.pem");
        assert_eq!(la.key.as_ref().unwrap().raw(), b"k.pem");
        assert_eq!(la.passwd.as_ref().unwrap().expose(), b"pass word");
        assert!(la.env_allowed);
        assert_eq!(la.verify, TlsVerify::WebPki);
    }

    #[test]
    fn a_long_passphrase_is_copied_whole() {
        // Validates that oversized passphrases are preserved intact prior to PEM callback limits.
        let long = "p".repeat(1500);
        let f = fields(None, None, None, Some(&long));
        let la = legacy_args(&args(&f, 0), TlsVerify::LegacyChainOnly);
        assert_eq!(la.passwd.unwrap().len(), 1500);
    }

    #[test]
    fn setup_sets_tls_and_reads_no_file() {
        let f = fields(Some("/nonexistent/zkrs/ca.pem"), None, None, None);
        let mut cfg = Config::legacy(30_000);
        assert!(!cfg.tls);
        let c = setup(&args(&f, 0), &mut cfg, Compat::LEGACY_C.tls_verify).unwrap();
        assert!(cfg.tls);
        assert_eq!(
            c.path_for(TlsConfigStep::CaFile),
            Some(&b"/nonexistent/zkrs/ca.pem"[..])
        );
    }

    #[test]
    fn path_for_names_the_field_of_each_step() {
        let f = fields(Some("a"), Some("b"), Some("c"), Some("d"));
        let conn = connector(&args(&f, 0), TlsVerify::LegacyChainOnly);
        assert_eq!(conn.path_for(TlsConfigStep::CaFile), Some(&b"a"[..]));
        assert_eq!(
            conn.path_for(TlsConfigStep::ClientCertChain),
            Some(&b"b"[..])
        );
        assert_eq!(conn.path_for(TlsConfigStep::ClientKey), Some(&b"c"[..]));
        assert_eq!(conn.path_for(TlsConfigStep::DefaultVerifyPaths), None);
        assert_eq!(conn.path_for(TlsConfigStep::CheckPrivateKey), None);
        let none = fields(None, None, None, None);
        let conn = connector(&args(&none, 0), TlsVerify::LegacyChainOnly);
        assert_eq!(conn.path_for(TlsConfigStep::CaFile), None);
    }

    #[test]
    fn a_null_or_missing_ca_fails_the_ca_step() {
        let ca_step = Err(TlsSetupError::Config {
            step: TlsConfigStep::CaFile,
        });
        for ca in [None, Some("/nonexistent/zkrs/ca.pem")] {
            let f = fields(ca, None, None, None);
            let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
            assert_eq!(new_conn(&*conn), ca_step);
        }
    }

    #[test]
    fn the_identity_needs_cert_and_key() {
        let ca = fixture("ca.pem");
        let key = fixture("client.key");
        // When all three fields are supplied, a missing certificate file fails during chain
        // loading.
        let f = fields(
            Some(&ca),
            Some("/nonexistent/zkrs/c.pem"),
            Some(&key),
            Some("pw"),
        );
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        assert_eq!(
            new_conn(&*conn),
            Err(TlsSetupError::Config {
                step: TlsConfigStep::ClientCertChain
            })
        );
        // In the absence of a passphrase, identity loading attempts decryption with an empty
        // secret.
        let f = fields(Some(&ca), Some("/nonexistent/zkrs/c.pem"), Some(&key), None);
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        assert_eq!(
            new_conn(&*conn),
            Err(TlsSetupError::Config {
                step: TlsConfigStep::ClientCertChain
            })
        );
        // Omission of the private key skips client identity configuration without error.
        let f = fields(Some(&ca), Some("/nonexistent/zkrs/c.pem"), None, Some("pw"));
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        assert_eq!(new_conn(&*conn), Ok(()));
    }

    #[test]
    fn a_valid_identity_makes_a_session() {
        let f = fields(
            Some(&fixture("ca.pem")),
            Some(&fixture("client.pem")),
            Some(&fixture("client.key")),
            Some("pw"),
        );
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        assert_eq!(new_conn(&*conn), Ok(()));
    }

    #[test]
    fn a_missing_key_fails_the_key_step() {
        let f = fields(
            Some(&fixture("ca.pem")),
            Some(&fixture("client.pem")),
            Some("/nonexistent/zkrs/k.pem"),
            Some("pw"),
        );
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        assert_eq!(
            new_conn(&*conn),
            Err(TlsSetupError::Config {
                step: TlsConfigStep::ClientKey
            })
        );
    }

    #[test]
    fn a_key_in_the_slot_of_the_leaf_fails_the_key_step() {
        // OpenSSL associates one key per algorithmic family; replacing a leaf key triggers failure.
        let f = fields(
            Some(&fixture("ca.pem")),
            Some(&fixture("client.pem")),
            Some(&fixture("server.key")),
            Some("pw"),
        );
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        assert_eq!(
            new_conn(&*conn),
            Err(TlsSetupError::Config {
                step: TlsConfigStep::ClientKey
            })
        );
    }

    #[test]
    fn a_key_in_another_slot_fails_the_check_step() {
        // Mismatched cryptographic algorithms between leaf certificate and private key fail key
        // validation.
        let pem = "-----BEGIN PRIVATE KEY-----\n\
                   MC4CAQAwBQYDK2VwBCIEIEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl9g\n\
                   -----END PRIVATE KEY-----\n";
        let dir = std::env::temp_dir().join(format!("zkrs-tls-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let key = dir.join("ed25519.key");
        std::fs::write(&key, pem).unwrap();
        let f = fields(
            Some(&fixture("ca.pem")),
            Some(&fixture("client.pem")),
            Some(key.to_str().unwrap()),
            Some("pw"),
        );
        let conn = connector(&args(&f, 0), Compat::LEGACY_C.tls_verify);
        let got = new_conn(&*conn);
        drop(std::fs::remove_dir_all(&dir));
        assert_eq!(
            got,
            Err(TlsSetupError::Config {
                step: TlsConfigStep::CheckPrivateKey
            })
        );
    }
}
