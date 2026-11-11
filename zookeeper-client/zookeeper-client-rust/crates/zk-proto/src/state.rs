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

//! Client session lifecycle states, transport connection phases, and teardown classification.
//!
//! [`State`] represents the externally visible session state as reported through C API
//! bindings (`zoo_state`). The internal `Phase` tracks granular transport and handshake
//! progress (TCP connection, TLS establishment, prime connection requests, and SASL exchange),
//! reconciling discrepancies where legacy C client implementations report transient or stale
//! states. [`Cause`] categorizes transport drops and protocol failures to drive appropriate
//! recovery workflows.

use crate::compat::DisconnectRc;
use crate::rc;
use crate::time::Millis;

/// Externally observable ZooKeeper session state conforming to legacy C API definitions
/// (`zoo_state`).
#[repr(i32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum State {
    /// Session is cleanly closed or resting in an idle state following a recoverable transport
    /// disconnection (`ZOO_CLOSED_STATE`).
    Closed = 0,
    /// Underlying TCP transport connection is actively being established (`ZOO_CONNECTING_STATE`).
    Connecting = 1,
    /// Transport connection is open and the client has sent the initial `ConnectRequest`, awaiting
    /// ensemble acknowledgment (`ZOO_ASSOCIATING_STATE`).
    Associating = 2,
    /// Client session is established with full read-write quorum access on the ensemble
    /// (`ZOO_CONNECTED_STATE`).
    Connected = 3,
    /// Client session is established with a read-only ensemble follower during quorum partition
    /// (`ZOO_READONLY_STATE`).
    ReadOnly = 5,
    /// TLS cryptographic handshake is in progress over an established TCP transport
    /// (`ZOO_SSL_CONNECTING_STATE`).
    SslConnecting = 7,
    /// Client handle is freshly allocated or has undergone forced unrecoverable closure
    /// (`ZOO_NOTCONNECTED_STATE`).
    NotConnected = 999,
    /// Session was explicitly expired by the ZooKeeper ensemble; this state is terminal and
    /// unrecoverable (`ZOO_EXPIRED_SESSION_STATE`).
    ExpiredSession = -112,
    /// Client authentication failed due to invalid credentials or SASL negotiation failure; this
    /// state is terminal (`ZOO_AUTH_FAILED_STATE`).
    AuthFailed = -113,
}

impl State {
    /// Returns the corresponding legacy C integer representation for this state.
    #[must_use]
    pub const fn code(self) -> i32 {
        match self {
            Self::Closed => 0,
            Self::Connecting => 1,
            Self::Associating => 2,
            Self::Connected => 3,
            Self::ReadOnly => 5,
            Self::SslConnecting => 7,
            Self::NotConnected => 999,
            Self::ExpiredSession => -112,
            Self::AuthFailed => -113,
        }
    }

    /// Resolves a legacy C integer state code into the corresponding [`State`] variant, or `None`
    /// if unmapped.
    #[must_use]
    pub const fn from_code(code: i32) -> Option<Self> {
        match code {
            0 => Some(Self::Closed),
            1 => Some(Self::Connecting),
            2 => Some(Self::Associating),
            3 => Some(Self::Connected),
            5 => Some(Self::ReadOnly),
            7 => Some(Self::SslConnecting),
            999 => Some(Self::NotConnected),
            -112 => Some(Self::ExpiredSession),
            -113 => Some(Self::AuthFailed),
            _ => None,
        }
    }

    /// Indicates whether the client currently holds an active session capable of serving
    /// operations ([`State::Connected`] or [`State::ReadOnly`]).
    #[must_use]
    pub const fn is_connected(self) -> bool {
        matches!(self, Self::Connected | Self::ReadOnly)
    }

    /// Determines whether this state represents a terminal, unrecoverable session condition
    /// matching C `is_unrecoverable`.
    #[must_use]
    pub const fn is_unrecoverable(self) -> bool {
        self.code() < 0
    }

    /// Returns the canonical C string identifier returned by `state2String`.
    #[must_use]
    pub const fn c_name(self) -> &'static str {
        state_name(self.code())
    }
}

/// Converts a raw C integer state code into its standard descriptive string representation
/// conforming to `state2String`.
#[must_use]
pub const fn state_name(code: i32) -> &'static str {
    match code {
        0 => "ZOO_CLOSED_STATE",
        1 => "ZOO_CONNECTING_STATE",
        7 => "ZOO_SSL_CONNECTING_STATE",
        2 => "ZOO_ASSOCIATING_STATE",
        3 => "ZOO_CONNECTED_STATE",
        5 => "ZOO_READONLY_STATE",
        -112 => "ZOO_EXPIRED_SESSION_STATE",
        -113 => "ZOO_AUTH_FAILED_STATE",
        _ => "INVALID_STATE",
    }
}

/// Maps a raw watcher event code to its symbolic representation matching C `watcherEvent2String`.
#[must_use]
pub const fn event_name(event_type: i32) -> &'static str {
    match event_type {
        0 => "ZOO_ERROR_EVENT",
        1 => "ZOO_CREATED_EVENT",
        2 => "ZOO_DELETED_EVENT",
        3 => "ZOO_CHANGED_EVENT",
        4 => "ZOO_CHILD_EVENT",
        -1 => "ZOO_SESSION_EVENT",
        -2 => "ZOO_NOTWATCHING_EVENT",
        _ => "INVALID_EVENT",
    }
}

/// Connection-scoped tracking flags for in-flight SASL authentication handshakes.
///
/// Instantiated anew upon each prime connection handshake response to guarantee that transient
/// negotiation state does not leak across physical socket reconnects.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct SaslNegotiation {
    /// Indicates whether the active mechanism negotiated is GSSAPI (Kerberos).
    pub(crate) is_gssapi: bool,
    /// Indicates that the GSSAPI handshake finished locally and expects one final acknowledgment
    /// frame from the ensemble.
    pub(crate) gssapi_last_packet: bool,
    /// Indicates that the final SASL response token was transmitted and the engine is awaiting the
    /// terminal server response.
    pub(crate) awaiting_final: bool,
}

/// Granular internal transport connection and handshake phase.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) enum Phase {
    /// No physical transport connection is open or actively being attempted.
    #[default]
    Idle,
    /// TCP socket connection establishment is currently in progress.
    TcpConnecting,
    /// Transport-layer TLS cryptographic handshake is in progress, subject to a strict timeout
    /// deadline.
    TlsHandshaking {
        /// Absolute deadline for TLS handshake completion (TCP establishment timestamp plus 2/3 of
        /// session timeout).
        deadline: Millis,
    },
    /// TLS configuration or certificate validation failed; transport remains quiescent without
    /// write interest pending read deadline.
    TlsConfigFailed,
    /// Initial `ConnectRequest` frame is queued or transmitted; awaiting ensemble `PrimeConnection`
    /// response.
    Associating,
    /// SASL authentication exchange is underway across the established transport.
    Sasl(SaslNegotiation),
    /// Session is fully established and actively processing protocol requests and heartbeats.
    Established,
}

/// Rationale triggering transport teardown and protocol error handling.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Cause {
    /// Underlying TCP connection attempt failed or was refused.
    ConnectFailed,
    /// Transport socket read or write operation returned an unexpected I/O error.
    Io,
    /// Remote ZooKeeper server unexpectedly terminated the TCP stream (EOF).
    Eof,
    /// Operating system socket allocation failed or resolved ensemble address list is empty.
    SocketCreate,
    /// TLS cryptographic handshake failed or required certificate resources could not be loaded.
    Tls,
    /// TLS cryptographic handshake exceeded its allotted completion deadline.
    TlsHandshakeTimeout,
    /// Heartbeat or response timeout elapsed without receiving frames (2/3 session timeout), or
    /// TCP connect bound expired.
    RecvTimeout,
    /// Incoming response transaction identifier (xid) failed to match the front of the pending
    /// request queue.
    XidMismatch,
    /// Received wire frame could not be decoded or violated Jute serialization invariants.
    ProtocolError,
    /// ZooKeeper ensemble explicitly rejected session resumption, declaring the session expired.
    SessionExpired,
    /// Server rejected client authentication credentials submitted during an `add_auth` request.
    AuthFailed,
    /// Read-only probe successfully located an available read-write server, prompting clean
    /// failover to the read-write ensemble.
    RwServerFound,
    /// SASL mechanism negotiation encountered an unrecoverable protocol or cryptographic error.
    SaslTeardown,
    /// Client application invoked explicit forced connection termination.
    ForcedClose,
}

impl Cause {
    /// Returns `true` if the teardown condition represents a terminal, unrecoverable session
    /// demise ([`Cause::SessionExpired`], [`Cause::AuthFailed`], [`Cause::SaslTeardown`]).
    #[must_use]
    pub const fn is_terminal(self) -> bool {
        matches!(
            self,
            Self::SessionExpired | Self::AuthFailed | Self::SaslTeardown
        )
    }

    /// Indicates whether teardown processing executes the legacy stage T5 (`handle_error`)
    /// completion callbacks.
    #[must_use]
    pub const fn runs_handle_error(self) -> bool {
        !matches!(self, Self::RwServerFound | Self::ForcedClose)
    }

    /// Maps this teardown cause to a ZooKeeper return code (`rc`), respecting compatibility policy
    /// rules.
    #[must_use]
    pub const fn rc_for(self, rule: DisconnectRc) -> i32 {
        let per_cause = match self {
            Self::ConnectFailed
            | Self::Io
            | Self::Eof
            | Self::ProtocolError
            | Self::RwServerFound
            | Self::ForcedClose => rc::ZCONNECTIONLOSS,
            Self::SocketCreate => rc::ZSYSTEMERROR,
            Self::Tls => rc::ZSSLCONNECTIONERROR,
            Self::RecvTimeout | Self::TlsHandshakeTimeout => rc::ZOPERATIONTIMEOUT,
            Self::XidMismatch => rc::ZRUNTIMEINCONSISTENCY,
            Self::SessionExpired => rc::ZSESSIONEXPIRED,
            Self::AuthFailed | Self::SaslTeardown => rc::ZAUTHFAILED,
        };
        match rule {
            DisconnectRc::PerCause => per_cause,
            DisconnectRc::ConnectionLoss => {
                if self.is_terminal() {
                    per_cause
                } else {
                    rc::ZCONNECTIONLOSS
                }
            }
        }
    }
}
