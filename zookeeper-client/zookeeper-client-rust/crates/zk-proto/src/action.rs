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

//! Driver I/O commands and asynchronous operation completion results.
//!
//! Because the protocol state machine remains pure and side effect free, it directs
//! external drivers to execute network I/O, DNS resolution, and cryptographic handshakes
//! through [`Action`] directives. Drivers perform the requested operations asynchronously
//! outside any protocol engine locks, subsequently returning the outcomes via dedicated
//! `handle_*` entry points on [`crate::connection::Connection`].

use std::net::SocketAddr;

use zk_jute::JString;

use crate::compat::TlsVerify;
use crate::config::{Endpoint, HostSpec};
use crate::secret::SecretBytes;

/// Discrete I/O directive emitted by the protocol engine for execution by the driver.
#[derive(Debug)]
pub enum Action {
    /// Request DNS hostname resolution across the ensemble host specifications.
    ///
    /// The driver delivers resolved socket addresses incrementally via `handle_host_resolved`
    /// before finalizing the resolution round with `handle_resolved`.
    Resolve {
        /// Monotonically increasing resolver generation counter, used to discard stale concurrent
        /// DNS results.
        generation: u64,
        /// Ordered sequence of ensemble server specifications extracted from the connection string.
        hosts: Vec<HostSpec>,
        /// Indicates whether this represents the initial bootstrap resolution upon client creation.
        initial: bool,
    },
    /// Directs the driver to initiate a transport-layer connection to the specified ensemble
    /// endpoint.
    Connect {
        /// Target ensemble endpoint containing resolved network socket address and host
        /// verification token.
        endpoint: Endpoint,
        /// TLS certificate verification policy, or `None` for unencrypted plaintext transport.
        tls: Option<TlsVerify>,
    },
    /// Instructs the driver to terminate the active transport connection, preceding any subsequent
    /// reconnection attempt.
    CloseTransport,
    /// Directs the driver to probe a target endpoint with the four-letter `"isro"` command to
    /// detect read-write quorum availability.
    ProbeRw {
        /// Target ensemble endpoint to query for read-write status.
        endpoint: Endpoint,
        /// TLS certificate verification policy for the probe connection, or `None` if unencrypted.
        tls: Option<TlsVerify>,
    },
    /// Delegates a step of the SASL authentication handshake to an external authentication
    /// mechanism provider.
    Sasl(SaslCall),
}

/// SASL authentication negotiation directive passed to the driver.
#[derive(Debug)]
pub struct SaslCall {
    /// Monotonic generation counter binding this call to a specific connection attempt; guarantees
    /// stale tokens are ignored.
    pub generation: u32,
    /// Specific SASL lifecycle invocation type.
    pub kind: SaslCallKind,
}

/// Specific SASL lifecycle invocation type.
#[derive(Debug)]
pub enum SaslCallKind {
    /// Initializes a new SASL negotiation context with the underlying mechanism provider.
    Start,
    /// Feeds an incoming server authentication challenge into the SASL mechanism; `None`
    /// represents an empty challenge.
    Step(Option<SecretBytes>),
    /// Disposes of the active SASL negotiation context and frees associated cryptographic
    /// resources.
    Dispose,
}

/// Evaluation result yielded by an individual SASL negotiation step.
#[derive(Debug)]
pub struct SaslStep {
    /// Outbound client authentication response token to be framed and transmitted to the ensemble.
    pub token: Option<SecretBytes>,
    /// Indicates whether the SASL handshake has reached definitive completion (`false` indicates
    /// further challenges expected).
    pub done: bool,
}

/// Error details returned by an external SASL mechanism failure.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SaslError {
    /// ZooKeeper return code associated with the authentication failure (typically `ZSYSTEMERROR`
    /// or `ZAUTHFAILED`).
    pub rc: i32,
    /// Primary error description string extracted from the underlying SASL provider (e.g.
    /// `sasl_errstring`).
    pub errstring: Vec<u8>,
    /// Extended diagnostic details supplied by the SASL mechanism provider (e.g. `sasl_errdetail`).
    pub errdetail: Vec<u8>,
}

/// Identifier indicating which socket address resolution query encountered an operating system
/// error prior to SASL initialization.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SaslAddr {
    /// Failure occurred while querying local socket address via `getsockname`.
    Local,
    /// Failure occurred while querying remote peer socket address via `getpeername`.
    Peer,
}

/// Address resolution outcome for a single ensemble host specification entry.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HostResolution {
    /// Index of the originating host entry within the committed ensemble host list.
    pub host: usize,
    /// Resolved IP socket addresses in order returned by the resolver, or an error detailing the
    /// resolution failure.
    pub result: Result<Vec<SocketAddr>, ResolveError>,
    /// Address family identifiers omitted or unsupported during resolution, recorded for
    /// diagnostic logging.
    pub skipped_families: Vec<i32>,
}

/// Detailed error context describing a DNS or address resolution failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ResolveError {
    /// Return code returned by `getaddrinfo`.
    pub gai_rc: i32,
    /// Operating system error number mapped from the resolution failure.
    pub errno: i32,
}

/// Identifies the OpenSSL or TLS engine resource allocation site that failed during initialization.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TlsResourceAt {
    /// Allocation of the global SSL context (`SSL_CTX_new`) failed.
    ContextNew,
    /// Allocation of the per-connection SSL session (`SSL_new`) failed.
    ConnectionNew,
}

/// Identifies the specific TLS configuration step that encountered an error during certificate or
/// key initialization.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TlsConfigStep {
    /// Loading certificate authority (CA) bundle file failed.
    CaFile,
    /// Configuring platform-default certificate verification search paths failed.
    DefaultVerifyPaths,
    /// Loading client certificate chain file failed.
    ClientCertChain,
    /// Loading client private key file failed.
    ClientKey,
    /// Private key validation check against loaded client certificate failed.
    CheckPrivateKey,
}

/// Transport failure classification capturing operating system errors and handshake breakdowns.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TransportFailure {
    /// Socket creation syscall (`socket()`) failed with the indicated operating system errno.
    SocketCreate {
        /// System error number returned by the failed socket creation syscall.
        errno: i32,
    },
    /// Outgoing TCP connection attempt was refused or timed out at the transport layer.
    TcpRefused {
        /// System error number returned by `connect()`.
        errno: i32,
    },
    /// Socket read or write operation returned an unexpected operating system I/O error.
    Io {
        /// System error number associated with the failed I/O operation.
        errno: i32,
    },
    /// Remote ZooKeeper server closed the TCP stream unexpectedly (EOF).
    Eof,
    /// Allocation of required cryptographic TLS context or session resources failed.
    TlsResource {
        /// Specific TLS resource allocation site where the failure occurred.
        at: TlsResourceAt,
        /// Operating system error number associated with the TLS allocation failure.
        errno: i32,
    },
    /// TLS cryptographic handshake failed due to certificate validation or cipher mismatch.
    TlsHandshake {
        /// System error number or OpenSSL error code captured during the handshake failure.
        errno: i32,
    },
    /// Loading or validating TLS certificates, keys, or trust stores failed prior to connection.
    TlsConfig {
        /// Configuration step that failed.
        step: TlsConfigStep,
        /// Path to the certificate or key file that triggered the failure, or `JString::Null`.
        file: JString,
    },
    /// Polling operation timed out or failed while awaiting socket flush during graceful teardown.
    Poll {
        /// System error number encountered during poll execution.
        errno: i32,
    },
}

/// TLS failure details encountered during background read-only probe execution.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProbeTlsFailure {
    /// TLS certificate or key configuration failed during probe initialization.
    Config {
        /// Configuration step that failed.
        step: TlsConfigStep,
        /// Path to the certificate or key file that failed to load.
        file: JString,
    },
    /// Cryptographic context allocation failed during probe setup.
    Resource {
        /// TLS resource allocation site where the failure occurred.
        at: TlsResourceAt,
    },
    /// Cryptographic handshake failed while attempting to establish probe connection.
    Handshake,
}

/// Result of evaluating an ensemble server's read-write availability via the `"isro"` probe
/// command.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProbeOutcome {
    /// Probe confirmed the server is a fully synchronized read-write quorum member (`"rw"`
    /// response).
    ReadWrite,
    /// Probe succeeded but server indicated read-only follower mode (`"ro"` response).
    NotReadWrite,
    /// Probe connection or handshake failed prior to receiving a valid response.
    Failed(Option<ProbeTlsFailure>),
}

/// Status of the nonblocking TCP socket connection initiation.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ConnectStart {
    /// Socket `connect()` syscall initiated asynchronously, returning `EINPROGRESS`.
    InProgress,
    /// Socket `connect()` syscall completed synchronously without blocking.
    Immediate,
}

/// Aggregate status produced following an I/O interest calculation round.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct InterestOutcome {
    /// ZooKeeper return code (`rc`) associated with the interest calculation.
    pub rc: i32,
    /// Calculated socket readiness interest flags and deadline, or `None` if the client is
    /// unrecoverable.
    pub outputs: Option<Interest>,
}

/// Socket event registration mask and next scheduled deadline for event loop polling.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Interest {
    /// Indicates whether an underlying transport socket is active and subject to event monitoring.
    pub has_socket: bool,
    /// Indicates whether the driver should monitor the socket for read readiness (incoming frames
    /// or EOF).
    pub read: bool,
    /// Indicates whether the driver should monitor the socket for write readiness (pending frames
    /// or connect completion).
    pub write: bool,
    /// Maximum duration in milliseconds before the next protocol timer expires (heartbeat, connect,
    /// or recv timeout).
    pub timeout_ms: u32,
}

/// Outcome of processing inbound wire data through the protocol receive state machine.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct ReadOutcome {
    /// Total number of complete protocol frames successfully decoded and processed during this
    /// invocation.
    pub frames: usize,
    /// Total count of incoming byte slice bytes consumed from the transport receive buffer.
    pub consumed: usize,
    /// ZooKeeper return code (`rc`) indicating processing status.
    pub rc: i32,
}

/// Execution status of a graceful session close sequence.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CloseProgress {
    /// Graceful close remains in progress, waiting for pending frames to flush or `closeSession`
    /// response to arrive.
    Pending,
    /// Graceful close sequence has fully concluded; the driver may proceed to release session
    /// resources.
    Complete,
}

/// Directive indicating a server side watch registration that should be explicitly purged via
/// `removeWatches`.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct RemoveHint {
    /// Target znode path stripped of any client chroot prefix, ready for `removeWatches` request
    /// construction.
    pub path: JString,
    /// Specific watch category to unregister (e.g. data, child, or any watch).
    pub wtype: i32,
}
