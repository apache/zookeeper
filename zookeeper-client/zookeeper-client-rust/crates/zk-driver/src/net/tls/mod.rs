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

//! TLS transport integration contract and session abstractions.
//!
//! # Architectural Role in `zk-driver`
//!
//! This module defines the boundary between [`crate::iocore`] / `crate::iocore_tls` and the
//! cryptographic TLS implementation (`conn::RustlsConn` and `connector::RustlsConnector`):
//!
//! - **Sans-I/O Session Abstraction ([`TlsConn`])**: TLS sessions operate completely decoupled
//!   from raw socket file descriptors. [`crate::iocore`] owns the nonblocking [`TcpSock`], while
//!   [`TlsConn`] manages the cryptographic state machine, TLS record framing, and X.509
//!   verification. `crate::iocore_tls` feeds raw socket ciphertext into the session via
//!   [`TlsConn::feed`], drains decrypted ZooKeeper wire frames via [`TlsConn::read_plaintext`],
//!   encrypts outbound frames via [`TlsConn::write_frame`], and extracts outbound TLS record
//!   flights via [`TlsConn::take_ciphertext`].
//! - **Lock-Free Session Factory ([`TlsConnector`])**: Instantiates fresh [`TlsConn`] sessions per
//!   connection attempt (`TlsPurpose::Main`) or read-only probe (`TlsPurpose::Probe`). Its
//!   `new_conn` method takes `&Unlocked` to guarantee at compile time that certificate/key loading
//!   and OS CSPRNG entropy gathering never execute while holding the driver's `core` lock.
//! - **Transport State and Partial-Record Accounting ([`TlsTransport`])**: Pairs the active
//!   [`TcpSock`] and [`TlsConn`] with an outbound ciphertext staging buffer (`ct_out` / `ct_sent`),
//!   a credit counter of plaintext bytes whose encrypted TLS record is still in flight (`unacked`),
//!   and a latched read failure slot (`pending`) so `crate::iocore_tls` can preserve exact frame
//!   boundaries across partial socket writes and reads.
//!
//! # Error Latching Semantics
//!
//! To prevent data loss when a server sends a final ZooKeeper reply frame immediately followed by a
//! TLS `close_notify` alert or TCP FIN in the same socket read, [`TlsConn::feed`] and
//! [`TlsConn::feed_eof`] never discard already-decrypted application bytes. Instead, any fatal
//! alert, verification error, `close_notify`, or EOF is latched inside the [`TlsConn`] instance,
//! and [`TlsConn::read_plaintext`] drains all previously decrypted plaintext bytes before surfacing
//! the latched [`TlsFailure`] on the subsequent read.

use zk_jute::JString;
pub use zk_proto::{Endpoint, TlsConfigStep, TlsResourceAt};

use crate::net::sock::TcpSock;
use crate::sync::Unlocked;

#[cfg(feature = "tls")]
pub mod conn;
#[cfg(feature = "tls")]
pub mod connector;
#[cfg(feature = "tls")]
pub mod identity;
#[cfg(feature = "tls")]
pub mod legacy_pem;
#[cfg(feature = "tls")]
pub mod legacy_verify;
#[cfg(feature = "tls")]
pub mod pem;
#[cfg(feature = "tls")]
pub mod trust;
#[cfg(feature = "tls")]
pub mod v1_leaf;
#[cfg(feature = "tls")]
pub mod verify;
#[cfg(feature = "tls")]
pub mod x509;

#[cfg(feature = "tls")]
pub use conn::RustlsConn;
#[cfg(feature = "tls")]
pub use connector::{LegacyPath, LegacyTlsArgs, NativeConfig, RustlsConnector, default_provider};

/// Sans-I/O TLS client session abstraction driving handshake and record encryption/decryption.
pub trait TlsConn: Send {
    /// Returns `true` while the cryptographic handshake is in progress.
    fn is_handshaking(&self) -> bool;

    /// Returns `true` if encrypted ciphertext records are buffered and awaiting transmission via
    /// [`Self::take_ciphertext`].
    fn has_ciphertext(&self) -> bool;

    /// Ingests incoming ciphertext bytes received from the network socket into the TLS engine.
    ///
    /// The session decodes records and latches any fatal error, alert, or clean closure.
    /// Handshake-phase failures are latched as [`TlsFailure::Handshake`].
    ///
    /// # Errors
    ///
    /// Returns the latched error immediately if a fatal condition occurred and no buffered
    /// plaintext remains.
    fn feed(&mut self, ciphertext: &[u8]) -> Result<(), TlsFailure>;

    /// Signals peer stream EOF, latching [`TlsFailure::Closed`] (or [`TlsFailure::Handshake`]
    /// during negotiation).
    ///
    /// # Errors
    ///
    /// Returns the latched failure if no pending plaintext remains.
    fn feed_eof(&mut self) -> Result<(), TlsFailure>;

    /// Drains decrypted plaintext application bytes into `out`.
    ///
    /// Returns `Ok(n)` where `n > 0` indicates the number of bytes appended, or `Ok(0)` when no
    /// decrypted data is currently available and no fatal error is latched.
    ///
    /// # Errors
    ///
    /// Returns the latched [`TlsFailure`] once all previously decrypted plaintext has been drained.
    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure>;

    /// Encrypts an outgoing plaintext protocol frame into one or more TLS ciphertext records.
    ///
    /// # Errors
    ///
    /// Returns a [`TlsFailure`] if the session cannot encrypt the frame (e.g. if already closed or
    /// in error).
    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure>;

    /// Extracts all pending outbound ciphertext records into `out` for transmission over the
    /// network socket.
    ///
    /// Returns the total number of bytes appended.
    fn take_ciphertext(&mut self, out: &mut Vec<u8>) -> usize;

    /// Generates and queues a TLS `close_notify` alert record for transmission during graceful
    /// session termination.
    fn send_close_notify(&mut self);
}

/// Appends all buffered ciphertext bytes from `from` into `out`, optimizing buffer allocation via
/// buffer swap when `out` is empty.
#[cfg(any(test, feature = "tls"))]
pub(crate) fn move_ciphertext(out: &mut Vec<u8>, from: &mut Vec<u8>) {
    if out.is_empty() {
        std::mem::swap(out, from);
    } else {
        out.append(from);
    }
}

/// Categorization of fatal TLS protocol failures latched during session execution.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TlsFailure {
    /// Failure occurred during TLS certificate validation or handshake negotiation.
    Handshake,
    /// Protocol or framing error encountered during application data phase.
    Data,
    /// Connection was closed by the remote peer (via `close_notify` alert or TCP EOF).
    Closed,
}

/// Configuration and resource errors encountered during TLS session construction
/// ([`TlsConnector::new_conn`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TlsSetupError {
    /// TLS certificate, private key, or CA bundle loading failed at the designated configuration
    /// step.
    Config {
        /// Specific configuration step that encountered an error.
        step: TlsConfigStep,
    },
    /// Cryptographic context allocation or session instantiation failed.
    Resource {
        /// Location where resource allocation failed.
        at: TlsResourceAt,
    },
}

/// Intended operational role for an instantiated TLS session.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TlsPurpose {
    /// Primary ZooKeeper client session connection.
    Main,
    /// Ephemeral read-write server probe connection (`isro`).
    Probe,
}

/// Factory responsible for instantiating configured TLS client sessions for connection attempts and
/// probes.
pub trait TlsConnector: Send + Sync {
    /// Creates a newly initialized TLS client session for the specified peer endpoint.
    ///
    /// Requires an [`Unlocked`] witness token, guaranteeing that certificate parsing and CSPRNG
    /// reseeding execute outside the `core` state lock (while permitting the outer `io` runner
    /// lock to remain held).
    ///
    /// # Errors
    ///
    /// Returns a [`TlsSetupError`] indicating which configuration step or resource allocation
    /// failed.
    fn new_conn(
        &self,
        proof: &Unlocked,
        purpose: TlsPurpose,
        peer: &Endpoint,
    ) -> Result<Box<dyn TlsConn>, TlsSetupError>;

    /// Returns the configured file path or identifier corresponding to a configuration step, used
    /// for diagnostics.
    fn path_for(&self, step: TlsConfigStep) -> Option<&[u8]>;
}

/// Latched transport read errors evaluated following the driver frame processing loop.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ReadFailure {
    /// Remote peer sent `close_notify` or closed the TCP connection cleanly
    /// (`TransportFailure::Eof`).
    Closed,
    /// TLS framing or decryption failure (`TransportFailure::Io`).
    Data,
    /// Socket read system call failed with the specified OS errno (`TransportFailure::Io`).
    Io(i32),
}

impl From<TlsFailure> for ReadFailure {
    fn from(f: TlsFailure) -> Self {
        match f {
            TlsFailure::Closed => Self::Closed,
            TlsFailure::Handshake | TlsFailure::Data => Self::Data,
        }
    }
}

/// State machine tracking nonblocking TLS socket I/O, buffered flights, and latched errors.
pub struct TlsTransport {
    /// Underlying nonblocking TCP socket descriptor.
    pub(crate) sock: TcpSock,
    /// Active sans-I/O TLS client session.
    pub(crate) tls: Box<dyn TlsConn>,
    /// Pending ciphertext records extracted from the session that have not yet been accepted by the
    /// kernel.
    pub(crate) ct_out: Vec<u8>,
    /// Byte offset within `ct_out` tracking how many bytes have been successfully transmitted on
    /// the wire.
    pub(crate) ct_sent: usize,
    /// Count of unacknowledged plaintext bytes passed to the session whose ciphertext remains in
    /// flight.
    pub(crate) unacked: usize,
    /// Latched transport error or EOF condition evaluated after completing the frame processing
    /// loop.
    pub(crate) pending: Option<ReadFailure>,
}

impl TlsTransport {
    /// Initializes transport state around an established TCP socket and newly negotiated TLS
    /// session.
    #[must_use]
    pub fn new(sock: TcpSock, tls: Box<dyn TlsConn>) -> Self {
        Self {
            sock,
            tls,
            ct_out: Vec::new(),
            ct_sent: 0,
            unacked: 0,
            pending: None,
        }
    }

    /// Returns a reference to the underlying network socket.
    #[must_use]
    pub fn sock(&self) -> &TcpSock {
        &self.sock
    }

    /// Returns `true` if unsent ciphertext records remain buffered in `ct_out` or inside the TLS
    /// session.
    #[must_use]
    pub fn has_unsent(&self) -> bool {
        !self.ct_out.is_empty() || self.tls.has_ciphertext()
    }

    /// Returns a slice of queued ciphertext bytes that have not yet been accepted by the kernel
    /// socket buffer.
    pub(crate) fn unsent(&self) -> &[u8] {
        self.ct_out.get(self.ct_sent..).unwrap_or_default()
    }

    /// Advances the send cursor by `n` bytes, compacting the buffer once all pending bytes have
    /// been transmitted.
    pub(crate) fn mark_sent(&mut self, n: usize) {
        self.ct_sent = self.ct_sent.saturating_add(n);
        if self.ct_sent >= self.ct_out.len() {
            self.ct_out.clear();
            self.ct_sent = 0;
        }
    }

    /// Reclaims the underlying TCP socket, dropping the TLS session without transmitting a
    /// `close_notify` alert.
    #[must_use]
    pub fn into_sock(self) -> TcpSock {
        self.sock
    }
}

impl std::fmt::Debug for TlsTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TlsTransport")
            .field("sock", &self.sock)
            .field("ct_out", &self.ct_out.len())
            .field("ct_sent", &self.ct_sent)
            .field("unacked", &self.unacked)
            .field("pending", &self.pending)
            .finish_non_exhaustive()
    }
}

/// Converts an optional certificate configuration path slice into a protocol [`JString`].
#[must_use]
pub fn jstring(path: Option<&[u8]>) -> JString {
    path.map_or(JString::Null, JString::from)
}

/// Attempts a single nonblocking transmission of any pending fatal TLS alert record prior to
/// session destruction.
///
/// If earlier application ciphertext is queued, the alert is suppressed to maintain record
/// ordering. Any transmission errors are silently ignored because the transport is terminating.
pub fn alert_attempt(sock: &TcpSock, tls: &mut dyn TlsConn, ct_out: &[u8]) {
    let mut scratch = Vec::new();
    tls.take_ciphertext(&mut scratch);
    if ct_out.is_empty() && !scratch.is_empty() {
        let _ = sock.send(&scratch);
    }
}
