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

//! GSSAPI (Kerberos) SASL mechanism integration via `libgssapi` (feature `sasl-gssapi`).
//!
//! Authenticates against Kerberos Key Distribution Centers (KDC) using ambient credentials,
//! default credential caches, or configured keytabs. The server principal is resolved as
//! `service@host`, where `host` defaults to the current target ensemble server address or a
//! hostname override. Following RFC 4752 §3.1 conventions for ZooKeeper, the client requests no
//! transport level security layer since packet confidentiality and integrity are provided via TLS
//! or application framing. The protocol handshake concludes with a final empty token transmitted
//! via `zk-proto`.
//!
//! Mutual authentication is explicitly enforced during context initialization, mirroring Java and
//! C client behaviors. Upon context establishment, the client issues an empty acknowledgment,
//! awaiting the server's security layer negotiation message.

use libgssapi::context::{ClientCtx, CtxFlags, SecurityContext};
use libgssapi::name::Name;
use libgssapi::oid::{GSS_MECH_KRB5, GSS_NT_HOSTBASED_SERVICE};

use super::{SaslContext, SaslError, SaslErrorKind, SaslMechanism, SaslMechanismFactory, SaslStep};

/// Standardized SASL mechanism name for Kerberos GSSAPI.
pub(crate) const NAME: &str = "GSSAPI";

/// RFC 4752 §3.1 bitmask representing "no security layer" (authentication only).
const LAYER_NONE: u8 = 1;

/// Factory instantiating GSSAPI authentication state machines per connection attempt.
pub(crate) struct GssapiFactory {
    /// Service principal name (e.g. `zookeeper`).
    service: String,
    /// Explicit server host override, or `None` to derive target hostnames from the active
    /// connection string.
    server_name: Option<String>,
}

impl GssapiFactory {
    /// Constructs a new GSSAPI mechanism factory.
    pub(crate) fn new(service: String, server_name: Option<String>) -> Self {
        Self {
            service,
            server_name,
        }
    }

    /// Formats the target server principal string according to standard `service@host`
    /// conventions.
    pub(crate) fn principal(&self, ctx: &SaslContext) -> String {
        let host = self.server_name.as_deref().unwrap_or(&ctx.server_host);
        format!("{}@{}", self.service, host)
    }
}

impl SaslMechanismFactory for GssapiFactory {
    /// Resolves the server principal and initializes a new GSSAPI client security context.
    fn create(&self, ctx: &SaslContext) -> Result<Box<dyn SaslMechanism>, SaslError> {
        let principal = self.principal(ctx);
        let target = Name::new(principal.as_bytes(), Some(GSS_NT_HOSTBASED_SERVICE))
            .map_err(|e| gss_error("the server principal is not valid", &e))?;
        let flags = CtxFlags::GSS_C_MUTUAL_FLAG | CtxFlags::GSS_C_SEQUENCE_FLAG;
        Ok(Box::new(Gssapi {
            ctx: ClientCtx::new(None, target, flags, Some(GSS_MECH_KRB5)),
            established: false,
        }))
    }
}

/// State machine managing a single GSSAPI / Kerberos authentication handshake.
pub(crate) struct Gssapi {
    /// Native GSSAPI client security context.
    ctx: ClientCtx,
    /// Indicates whether context negotiation has completed and the mechanism is awaiting security
    /// layer negotiation.
    established: bool,
}

impl Gssapi {
    /// Advances the GSSAPI security context handshake by processing inbound tokens and producing
    /// outbound replies.
    fn context_step(&mut self, token: Option<&[u8]>) -> Result<SaslStep, SaslError> {
        let out = self
            .ctx
            .step(token, None)
            .map_err(|e| gss_error("the GSSAPI context setup failed", &e))?;
        if self.ctx.is_complete() {
            self.established = true;
        }
        Ok(SaslStep {
            token: Some(out.map(|b| b.to_vec()).unwrap_or_default()),
            done: false,
        })
    }

    /// Responds to the server's security layer negotiation challenge (RFC 4752 §3.1), selecting no
    /// additional transport encryption.
    fn layer_step(&mut self, message: &[u8]) -> Result<SaslStep, SaslError> {
        let plain = self
            .ctx
            .unwrap(message)
            .map_err(|e| gss_error("the security-layer message is not valid", &e))?;
        let offer = match &plain[..] {
            [layers, _, _, _] => *layers,
            _ => {
                return Err(mechanism_error(
                    "the security-layer message does not have four bytes",
                ));
            }
        };
        if offer & LAYER_NONE == 0 {
            return Err(mechanism_error(
                "the server needs a security layer, which the client does not support",
            ));
        }
        let answer = self
            .ctx
            .wrap(false, &[LAYER_NONE, 0, 0, 0])
            .map_err(|e| gss_error("the security-layer answer failed", &e))?;
        Ok(SaslStep {
            token: Some(answer.to_vec()),
            done: true,
        })
    }
}

impl SaslMechanism for Gssapi {
    fn name(&self) -> &str {
        NAME
    }

    /// Generates the initial GSSAPI context establishment token.
    fn start(&mut self) -> Result<SaslStep, SaslError> {
        self.context_step(None)
    }

    /// Processes subsequent handshake tokens until context negotiation completes, then handles
    /// security layer exchange.
    fn step(&mut self, challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
        let token = challenge.unwrap_or_default();
        if self.established {
            self.layer_step(token)
        } else {
            self.context_step(Some(token))
        }
    }
}

/// Formats a GSSAPI status failure into a structured [`SaslError`].
fn gss_error(message: &str, e: &libgssapi::error::Error) -> SaslError {
    SaslError::new(SaslErrorKind::Mechanism, format!("{message}: {e}"))
}

/// Constructs a mechanism-level [`SaslError`] with the specified diagnostic message.
fn mechanism_error(message: &str) -> SaslError {
    SaslError::new(SaslErrorKind::Mechanism, message)
}

#[cfg(test)]
#[allow(clippy::expect_used, reason = "test code")]
mod tests {
    use super::*;

    fn ctx() -> SaslContext {
        SaslContext {
            server_host: "zk1.example.com".to_owned(),
            server_addr: "127.0.0.1:2181".parse().expect("addr"),
            local_addr: "127.0.0.1:40000".parse().expect("addr"),
        }
    }

    // Verify that server principal formatting correctly incorporates service and hostname
    // components.
    #[test]
    fn the_principal_is_service_at_host() {
        let f = GssapiFactory::new("zookeeper".to_owned(), None);
        assert_eq!(f.principal(&ctx()), "zookeeper@zk1.example.com");
        let f = GssapiFactory::new("zk".to_owned(), Some("zk.realm.example".to_owned()));
        assert_eq!(f.principal(&ctx()), "zk@zk.realm.example");
    }
}
