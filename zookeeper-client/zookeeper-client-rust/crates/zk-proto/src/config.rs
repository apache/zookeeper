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

//! Client session configuration, connection parameters, and driver operational modes.
//!
//! [`Config`] encapsulates per-session operational parameters, connection endpoints,
//! security configurations, and backoff timers. [`DriverMode`] governs driver-level
//! architectural semantics, including socket I/O interest calculation strategies and session
//! event delivery mechanisms across single threaded legacy, multithreaded C, and native runtimes.

use std::net::SocketAddr;

use zk_jute::JString;
use zk_jute::handshake::PASSWD_LEN;

use crate::legacy::addrvec::EndpointAddr;
use crate::legacy::connect::{HostSpec as LegacyHostSpec, NativeHost};

/// Session credential tuple consisting of the 64-bit session identifier and secret password.
///
/// Does not implement `Copy` because it holds the session password, ensuring that every copy of
/// the password is an explicit `clone`.
#[derive(Clone, PartialEq, Eq, Hash, Default)]
pub struct ClientId {
    /// ZooKeeper cluster-assigned 64-bit session identifier. A value of `0` requests initiation of
    /// a new session.
    pub session_id: i64,
    /// Cryptographic session password assigned by the ZooKeeper ensemble for session
    /// re-establishment.
    pub passwd: [u8; PASSWD_LEN],
}

impl core::fmt::Debug for ClientId {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("ClientId")
            .field("session_id", &format_args!("{:#x}", self.session_id))
            .field("passwd", &"<redacted>")
            .finish()
    }
}

/// Host specification representing an ensemble endpoint extracted from the connection string.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum HostSpec {
    /// Host specification produced by the legacy C-compatible parser, preserving raw hostname and
    /// service strings for `getaddrinfo`.
    Legacy(LegacyHostSpec),
    /// Host specification produced by the Java-compatible native parser, resolving host and
    /// explicit numeric port.
    Native(NativeHost),
}

impl HostSpec {
    /// Extracts the raw hostname or IP address byte slice, omitting any accompanying port number.
    #[must_use]
    pub fn host_bytes(&self) -> &[u8] {
        match self {
            Self::Legacy(h) => &h.host,
            Self::Native(h) => h.host.as_bytes(),
        }
    }
}

/// Origin metadata associating a resolved IP socket address with its source connection string
/// entry.
///
/// Propagated alongside resolved endpoints to facilitate TLS Server Name Indication (SNI)
/// and WebPKI hostname verification during secure transport handshakes.
#[derive(Clone, Debug, PartialEq, Eq, Hash, Default)]
pub struct HostToken {
    /// Positional index of this host specification within the configured ensemble host list.
    pub index: usize,
    /// Unadorned hostname or IP address representation used for endpoint identity verification.
    pub name: Vec<u8>,
}

/// Concrete network endpoint combining a resolved socket address with its originating host
/// identity.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Endpoint {
    /// Concrete IPv4 or IPv6 network address and port for TCP socket connection.
    pub addr: SocketAddr,
    /// Originating host specification token utilized for TLS certificate validation.
    pub host: HostToken,
}

impl EndpointAddr for Endpoint {
    fn socket_addr(&self) -> SocketAddr {
        self.addr
    }
}

/// Comprehensive client configuration controlling protocol state machine execution.
///
/// Contains all static configuration governing connection negotiation, session timeouts,
/// authentication schemes, chroot prefixes, and retry backoffs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Config {
    /// Negotiated session timeout requested from the ensemble, expressed in milliseconds.
    pub timeout_ms: i32,
    /// Existing session credentials for session resumption across reconnects, or zero for initial
    /// connection.
    pub client_id: ClientId,
    /// Flag indicating whether the client permits connecting to read-only ensemble followers during
    /// quorum loss.
    pub allow_read_only: bool,
    /// Flag indicating whether SASL authentication handshakes should be initiated upon connection
    /// establishment.
    pub sasl: bool,
    /// Flag indicating whether TLS encryption and certificate verification are mandated for
    /// transport connections.
    pub tls: bool,
    /// Ensemble server specifications in the order defined within the connection string.
    pub hosts: Vec<HostSpec>,
    /// Unparsed ensemble host string as supplied by the application, preserved for diagnostic
    /// logging.
    pub host_text: JString,
    /// Optional chroot path prefix transparently prepended to outgoing request paths and stripped
    /// from incoming event paths.
    pub chroot: Option<JString>,
    /// Delay interval in milliseconds before initiating background host re-resolution while
    /// transport is active (-1 disables).
    pub resolve_delay_ms: i32,
    /// Initial transaction identifier (xid) seed value; values non-positive default to 1.
    pub xid_seed: i32,
    /// Target architecture platform signedness flag for C `char`, preserving C hash table bucket
    /// ordering in legacy mode. Defaults to [`TARGET_C_CHAR_SIGNED`], the signedness of C `char`
    /// on the build target.
    ///
    /// [`TARGET_C_CHAR_SIGNED`]: crate::legacy::hashtable::TARGET_C_CHAR_SIGNED
    pub c_char_signed: bool,
    /// Flag governing whether active watch registrations are re-submitted to the ensemble following
    /// session reconnection.
    pub restore_watches: bool,
    /// Optional upper bound on pending in-flight requests before backpressure triggers rejection.
    pub max_pending: Option<usize>,
    /// Maximum duration in milliseconds permitted for establishing underlying TCP transport
    /// connections.
    pub connect_timeout_ms: Option<u32>,
    /// Base backoff delay in milliseconds applied after a full traversal of the host list fails to
    /// connect.
    pub reconnect_delay_ms: u32,
    /// Randomization jitter percentage applied to the reconnect backoff delay to prevent ensemble
    /// thundering herds.
    pub reconnect_jitter_pct: u8,
    /// Timeout duration in milliseconds allocated for draining pending requests during graceful
    /// connection shutdown.
    pub close_timeout_ms: u32,
}

impl Config {
    /// Constructs a configuration initialized with legacy C-client compatible defaults and empty
    /// host lists.
    #[must_use]
    pub fn legacy(timeout_ms: i32) -> Self {
        Self {
            timeout_ms,
            client_id: ClientId::default(),
            allow_read_only: false,
            sasl: false,
            tls: false,
            hosts: Vec::new(),
            host_text: JString::Bytes(Vec::new()),
            chroot: None,
            resolve_delay_ms: 0,
            xid_seed: 1,
            c_char_signed: crate::legacy::hashtable::TARGET_C_CHAR_SIGNED,
            restore_watches: true,
            max_pending: None,
            connect_timeout_ms: None,
            reconnect_delay_ms: 0,
            reconnect_jitter_pct: 0,
            close_timeout_ms: 0,
        }
    }
}

/// Strategy for calculating socket write interest readiness flags.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum InterestBits {
    /// Replicates the legacy single threaded C client algorithm, where write interest is asserted
    /// during active handshakes and pending queues.
    LegacyExact,
    /// Modern reactive strategy where write interest is asserted strictly when serialization
    /// buffers contain pending outbound bytes.
    Precise,
}

/// Dispatch mechanism for session lifecycle state change notifications.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SessionDelivery {
    /// Synchronous delivery barrier where callbacks are executed inline within the single threaded
    /// event loop.
    Inline,
    /// Asynchronous dispatch model routing events to dedicated background delivery queues.
    Dispatch,
}

/// Driver operational profile dictating low-level I/O interest calculation and notification
/// routing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct DriverMode {
    /// Strategy governing socket write readiness bit calculation.
    pub interest: InterestBits,
    /// Routing mechanism governing session event delivery to client callbacks.
    pub session_delivery: SessionDelivery,
}

impl DriverMode {
    /// Operational mode mimicking the single threaded C client library (`zookeeper_st`).
    pub const ST: Self = Self {
        interest: InterestBits::LegacyExact,
        session_delivery: SessionDelivery::Inline,
    };
    /// Operational mode mimicking the multithreaded C client library (`zookeeper_mt`).
    pub const MT: Self = Self {
        interest: InterestBits::Precise,
        session_delivery: SessionDelivery::Dispatch,
    };
    /// Operational mode utilized by modern asynchronous native Rust drivers.
    pub const NATIVE: Self = Self::MT;
}
