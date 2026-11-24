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

//! Verification of SASL configuration contracts, security redaction, and error formatting.
//!
//! Asserts default mechanism configuration invariants, redaction of sensitive credentials
//! in [`std::fmt::Debug`] representations, error display formatting, and builder integration.

#![cfg(feature = "sasl")]
#![allow(clippy::unwrap_used, clippy::panic, reason = "test code")]
#![allow(clippy::disallowed_macros)]

use std::sync::Arc;

use apache_zookeeper::{
    CredentialError, CredentialSource, SaslConfig, SaslError, SaslErrorKind, SaslStep, SecretString,
};

struct Fixed;

impl CredentialSource for Fixed {
    fn password(&self) -> Result<SecretString, CredentialError> {
        Ok(SecretString::from("hunter2"))
    }
}

#[test]
fn digest_md5_defaults() {
    let cfg = SaslConfig::digest_md5("bob", Arc::new(Fixed));
    match cfg {
        SaslConfig::DigestMd5 {
            username,
            service,
            server_name,
            ..
        } => {
            assert_eq!(username, "bob");
            assert_eq!(service, "zookeeper");
            assert_eq!(server_name, "zk-sasl-md5");
        }
        _ => panic!("not DigestMd5"),
    }
}

#[test]
fn config_debug_hides_password() {
    let text = format!("{:?}", SaslConfig::digest_md5("bob", Arc::new(Fixed)));
    assert!(text.contains("bob"));
    assert!(text.contains("<redacted>"));
    assert!(!text.contains("hunter2"));
}

#[test]
fn step_debug_hides_token() {
    let step = SaslStep {
        token: Some(b"secret-token".to_vec()),
        done: false,
    };
    let text = format!("{step:?}");
    assert!(!text.contains("secret-token"));
}

#[test]
fn error_display_names_kind() {
    let e = SaslError::new(
        SaslErrorKind::Credential(CredentialError::Transient),
        "no file",
    );
    assert_eq!(e.to_string(), "SASL transient credential error: no file");
    let e = SaslError::new(SaslErrorKind::ServerRejected, "bad");
    assert_eq!(e.to_string(), "SASL rejected by the server: bad");
    let boxed: Box<dyn std::error::Error> = Box::new(e);
    assert!(boxed.source().is_none());
}

/// Asserts that [`ClientBuilder`] debug formatting outputs SASL metadata while suppressing
/// sensitive credentials.
#[test]
fn builder_debug_hides_password() {
    let b = apache_zookeeper::Client::builder("127.0.0.1:1")
        .sasl(SaslConfig::digest_md5("bob", Arc::new(Fixed)));
    let text = format!("{b:?}");
    assert!(text.contains("DigestMd5"));
    assert!(text.contains("bob"));
    assert!(!text.contains("hunter2"));
}

/// Validates builder integration with GSSAPI mechanism configurations without initiating live KDC
/// network exchanges.
#[cfg(feature = "sasl-gssapi")]
#[test]
fn a_gssapi_config_goes_into_the_builder() {
    let cfg = SaslConfig::Gssapi {
        service: "zookeeper".to_owned(),
        server_name: None,
    };
    let text = format!("{cfg:?}");
    assert!(text.contains("Gssapi"));
    let b = apache_zookeeper::Client::builder("127.0.0.1:1").sasl(cfg);
    assert!(format!("{b:?}").contains("zookeeper"));
}
