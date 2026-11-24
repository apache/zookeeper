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

//! Native Simple Authentication and Security Layer (SASL) framework and mechanism abstractions.
//!
//! Exposes mechanism contracts and configuration options for authenticating ZooKeeper client
//! connections.
//! Supported built-in mechanisms include pure-Rust RFC 2831 DIGEST-MD5 (under the `sasl` feature)
//! and GSSAPI / Kerberos integration via `libgssapi` (under `sasl-gssapi`). Custom proprietary
//! mechanisms can be integrated through [`SaslConfig::Custom`]. External legacy C libraries (such
//! as Cyrus SASL) are completely omitted.
//!
//! Protocol framing and state transitions including packet encapsulation (`opcode::SASL`), sender
//! gate synchronization, deferred `CONNECTED` transitions, DIGEST-MD5 initial empty token
//! semantics, and final GSSAPI confirmation packets are orchestrated by `zk-proto`. This module
//! focuses on generating client response tokens and validating server challenges.
//!
//! All SASL negotiation failures are strictly terminal: failing negotiation immediately transitions
//! the session to `AuthFailed`, suppressing reconnection attempts to subsequent ensemble servers.
//! Structured failure details are recorded in [`SessionInfo::last_error`], preserving either
//! [`SaslError`] or [`SaslErrorKind::ServerRejected`].
//!
//! [`SessionInfo::last_error`]: crate::SessionInfo::last_error

mod adapter;
mod digest_md5;
#[cfg(feature = "sasl-gssapi")]
mod gssapi;

use std::net::SocketAddr;
use std::sync::Arc;

use crate::secret::SecretString;

// Internal bridge adapter connecting mechanism implementations to driver protocol sessions.
pub(crate) use adapter::{LastSaslError, NativeSasl};

/// Configuration defining the SASL mechanism and credentials used for connection authentication.
#[non_exhaustive]
pub enum SaslConfig {
    /// Pure-Rust implementation of DIGEST-MD5 with authentication protection (`qop=auth`).
    ///
    /// By default, authenticates against the standard ZooKeeper service (`zookeeper`) and server
    /// name (`zk-sasl-md5`).
    DigestMd5 {
        /// Authentication user identity principal.
        username: String,
        /// Dynamic password provider evaluated on each connection attempt, allowing credential
        /// rotation without restart.
        password: Arc<dyn CredentialSource>,
        /// Service identifier component incorporated into the digest URI.
        service: String,
        /// Server hostname component incorporated into the digest URI.
        server_name: String,
    },
    /// GSSAPI / Kerberos authentication mechanism integrated via `libgssapi`.
    ///
    /// Resolves client credentials from the ambient environment, Kerberos credential cache, or
    /// configured keytab.
    #[cfg(feature = "sasl-gssapi")]
    Gssapi {
        /// Kerberos service principal name (e.g. `zookeeper`).
        service: String,
        /// Server host principal component. If `None`, defaults to the resolved hostname of the
        /// target ensemble member.
        server_name: Option<String>,
    },
    /// Application provided custom SASL mechanism factory.
    Custom(Arc<dyn SaslMechanismFactory>),
}

impl SaslConfig {
    /// Constructs a standard DIGEST-MD5 configuration using canonical service name `zookeeper` and
    /// server name `zk-sasl-md5`.
    #[must_use]
    pub fn digest_md5(username: impl Into<String>, password: Arc<dyn CredentialSource>) -> Self {
        Self::DigestMd5 {
            username: username.into(),
            password,
            service: DEFAULT_SERVICE.to_owned(),
            server_name: DEFAULT_DIGEST_SERVER.to_owned(),
        }
    }
}

/// Canonical Kerberos service principal name for ZooKeeper servers.
pub(crate) const DEFAULT_SERVICE: &str = "zookeeper";

/// Default server name expected by standard ZooKeeper DIGEST-MD5 authentication handlers.
pub(crate) const DEFAULT_DIGEST_SERVER: &str = "zk-sasl-md5";

impl std::fmt::Debug for SaslConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::DigestMd5 {
                username,
                service,
                server_name,
                ..
            } => f
                .debug_struct("DigestMd5")
                .field("username", username)
                .field("password", &"<redacted>")
                .field("service", service)
                .field("server_name", server_name)
                .finish(),
            #[cfg(feature = "sasl-gssapi")]
            Self::Gssapi {
                service,
                server_name,
            } => f
                .debug_struct("Gssapi")
                .field("service", service)
                .field("server_name", server_name)
                .finish(),
            Self::Custom(_) => f.write_str("Custom(..)"),
        }
    }
}

/// Provider interface for dynamic retrieval of authentication passwords.
///
/// Invoked on each connection attempt, allowing clients to accommodate credential rotation without
/// requiring restart. Executes outside of client synchronization locks, either directly on
/// background I/O threads or via thread pool dispatch. Unhandled panics are intercepted and
/// treated as authentication failures.
pub trait CredentialSource: Send + Sync + 'static {
    /// Retrieves the current password credential.
    ///
    /// # Errors
    ///
    /// Returns [`CredentialError`] categorizing the failure as transient or permanent.
    fn password(&self) -> Result<SecretString, CredentialError>;
}

/// Categorization of credential acquisition failures.
///
/// Failure categorization assists application layers in determining whether re-establishing a
/// client instance may succeed.
#[non_exhaustive]
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum CredentialError {
    /// Transient failure condition that may recover upon subsequent retry (e.g. network partition
    /// to KDC or temporary file lock).
    Transient,
    /// Permanent failure condition indicating invalid credentials or missing configuration.
    Permanent,
}

/// Factory responsible for instantiating fresh SASL mechanism state machines per connection
/// attempt.
pub trait SaslMechanismFactory: Send + Sync + 'static {
    /// Instantiates a new SASL mechanism state machine for an incoming connection attempt.
    ///
    /// Called once per connection, ensuring that no state leaks across reconnect boundaries.
    ///
    /// # Errors
    ///
    /// Returns [`SaslError`] indicating mechanism creation failure, resulting in terminal
    /// session teardown.
    fn create(&self, ctx: &SaslContext) -> Result<Box<dyn SaslMechanism>, SaslError>;
}

/// State machine driving a single SASL authentication handshake.
pub trait SaslMechanism: Send {
    /// Returns the standardized IANA mechanism identifier (e.g. `DIGEST-MD5`, `GSSAPI`).
    fn name(&self) -> &str;

    /// Produces the initial client response token to begin the authentication handshake.
    ///
    /// # Errors
    ///
    /// Returns [`SaslError`] terminating the session if initial token generation fails.
    fn start(&mut self) -> Result<SaslStep, SaslError>;

    /// Processes an incoming server challenge and generates the subsequent client response token.
    /// A challenge of `None` indicates an empty server token payload.
    ///
    /// # Errors
    ///
    /// Returns [`SaslError`] terminating the session if challenge validation fails.
    fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError>;
}

/// Outcome of a single SASL negotiation step.
#[derive(Clone, PartialEq, Eq)]
pub struct SaslStep {
    /// Response token to be transmitted to the server, or `None` if no payload is emitted.
    pub token: Option<Vec<u8>>,
    /// Indicates whether the SASL handshake has reached successful mutual completion.
    pub done: bool,
}

impl std::fmt::Debug for SaslStep {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SaslStep")
            .field("token", &self.token.as_ref().map(|_| "<redacted>"))
            .field("done", &self.done)
            .finish()
    }
}

/// Contextual network socket details provided to mechanism factories upon connection
/// establishment.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SaslContext {
    /// Hostname of the target server as specified in the client connection string.
    pub server_host: String,
    /// Remote socket address of the connected ensemble member.
    pub server_addr: SocketAddr,
    /// Local socket address bound by the client transport.
    pub local_addr: SocketAddr,
}

/// Structured error describing SASL authentication negotiation failures.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SaslError {
    /// High-level categorization of the authentication failure.
    pub kind: SaslErrorKind,
    /// Diagnostic message describing the failure without exposing sensitive credentials.
    pub message: String,
}

impl SaslError {
    /// Constructs a new SASL authentication error.
    #[must_use]
    pub fn new(kind: SaslErrorKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

/// Categorization of root causes for SASL authentication failures.
#[non_exhaustive]
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SaslErrorKind {
    /// Internal failure or unhandled panic within the mechanism state machine.
    Mechanism,
    /// Explicit authentication refusal received from the ZooKeeper server.
    ServerRejected,
    /// Credential retrieval error encountered when querying the [`CredentialSource`].
    Credential(CredentialError),
}

impl std::fmt::Display for SaslError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let kind = match self.kind {
            SaslErrorKind::Mechanism => "mechanism error",
            SaslErrorKind::ServerRejected => "rejected by the server",
            SaslErrorKind::Credential(CredentialError::Transient) => "transient credential error",
            SaslErrorKind::Credential(CredentialError::Permanent) => "permanent credential error",
        };
        write!(f, "SASL {kind}: {}", self.message)
    }
}

impl std::error::Error for SaslError {}

/// Fuzzing test harnesses for DIGEST-MD5 challenge-response validation.
#[cfg(fuzzing)]
#[doc(hidden)]
pub mod fuzzing {
    use super::SaslMechanism;
    use crate::secret::SecretString;

    /// Constructs a deterministic DIGEST-MD5 mechanism instance using a predefined client nonce.
    #[must_use]
    pub fn digest_md5(
        username: String,
        password: SecretString,
        digest_uri: String,
        cnonce: String,
    ) -> Box<dyn SaslMechanism> {
        Box::new(super::digest_md5::DigestMd5::new(
            username, password, digest_uri, cnonce,
        ))
    }
}
