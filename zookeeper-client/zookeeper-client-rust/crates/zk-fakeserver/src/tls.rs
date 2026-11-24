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

//! Transport Layer Security (TLS) server endpoint backed by `rustls`.
//!
//! [`TlsAcceptor`] implements [`Acceptor`] to upgrade accepted TCP sockets into encrypted TLS
//! streams. Because the connection loop reads and writes plaintext buffers through [`Transport`],
//! protocol decoding, state machine execution, and fault injection scripts remain
//! transport-agnostic. Callers supply the [`CryptoProvider`] explicitly so this crate never
//! relies on a process global default provider.
//!
//! For test assertions on connection setup and teardown, the acceptor records each session in a
//! [`TlsSession`] entry tracking handshake status, mutual TLS client certificates, outbound
//! `close_notify` alerts, and peer termination behavior (clean `close_notify` vs abrupt EOF).

use std::fmt;
use std::io::{self, ErrorKind, Read, Write};
use std::net::TcpStream;
use std::path::Path;
use std::sync::{Arc, Condvar, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use rustls::crypto::CryptoProvider;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use rustls::server::WebPkiClientVerifier;
use rustls::{RootCertStore, ServerConfig, ServerConnection, SupportedProtocolVersion};

use crate::transport::{Acceptor, Transport};

/// Default timeout allowed for clients to complete the TLS handshake.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

/// How the remote peer ended the TLS transport stream.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PeerEnd {
    /// Peer closed the TLS session cleanly with a `close_notify` alert.
    CloseNotify,
    /// Peer closed the TCP connection without sending a `close_notify` alert.
    Eof,
    /// TLS session failed with a record error or fatal protocol alert.
    Error(String),
}

/// Audit record capturing the handshake and teardown state of a single TLS connection.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TlsSession {
    /// Handshake result: `None` while in progress, then `Some(Ok(()))` or `Some(Err(reason))`.
    pub handshake: Option<Result<(), String>>,
    /// Whether the peer presented a verified client certificate during mutual TLS authentication.
    pub client_cert: bool,
    /// Negotiated TLS protocol version (such as `"TLSv1_3"` or `"TLSv1_2"`).
    pub version: Option<String>,
    /// Whether the server sent a TLS `close_notify` alert before closing the socket.
    pub sent_close_notify: bool,
    /// How the peer terminated the TLS stream, if observed before socket closure.
    pub peer_end: Option<PeerEnd>,
}

/// Synchronized session log with a condition variable for test waiters.
#[derive(Debug, Default)]
struct Log {
    sessions: Mutex<Vec<TlsSession>>,
    changed: Condvar,
}

impl Log {
    fn lock(&self) -> MutexGuard<'_, Vec<TlsSession>> {
        self.sessions.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Appends a new default session record and returns its index.
    fn open(&self) -> usize {
        let mut g = self.lock();
        g.push(TlsSession::default());
        g.len().saturating_sub(1)
    }

    /// Updates the session record at `at` and wakes any waiting threads.
    fn update(&self, at: usize, f: impl FnOnce(&mut TlsSession)) {
        if let Some(s) = self.lock().get_mut(at) {
            f(s);
        }
        self.changed.notify_all();
    }

    /// Records the first peer termination event observed for the session at `at`.
    fn peer_end(&self, at: usize, end: PeerEnd) {
        self.update(at, |s| {
            if s.peer_end.is_none() {
                s.peer_end = Some(end);
            }
        });
    }

    fn has_peer_end(&self, at: usize) -> bool {
        self.lock().get(at).is_some_and(|s| s.peer_end.is_some())
    }
}

/// Builder for configuring and constructing a [`TlsAcceptor`].
///
/// Requires a server certificate chain and matching private key. Supplying client root
/// certificates enables mandatory mutual TLS (mTLS), rejecting clients that do not present a
/// verifiable certificate.
pub struct TlsAcceptorBuilder {
    provider: Arc<CryptoProvider>,
    chain: Vec<CertificateDer<'static>>,
    key: Option<PrivateKeyDer<'static>>,
    client_roots: Option<Vec<CertificateDer<'static>>>,
    versions: Option<&'static [&'static SupportedProtocolVersion]>,
    close_notify: bool,
    linger: Duration,
    handshake_timeout: Duration,
}

impl fmt::Debug for TlsAcceptorBuilder {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TlsAcceptorBuilder")
            .field("chain", &self.chain.len())
            .field("key", &self.key.is_some())
            .field("client_roots", &self.client_roots.as_ref().map(Vec::len))
            .field("close_notify", &self.close_notify)
            .field("linger", &self.linger)
            .finish_non_exhaustive()
    }
}

/// Wraps a displayable error message into an [`ErrorKind::InvalidInput`] [`io::Error`].
fn invalid(text: impl fmt::Display) -> io::Error {
    io::Error::new(ErrorKind::InvalidInput, text.to_string())
}

/// Loads all DER-encoded X.509 certificates from a PEM file.
fn pem_certs(path: &Path) -> io::Result<Vec<CertificateDer<'static>>> {
    let certs = CertificateDer::pem_file_iter(path)
        .map_err(invalid)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(invalid)?;
    if certs.is_empty() {
        return Err(invalid(format!("no certificate in {}", path.display())));
    }
    Ok(certs)
}

impl TlsAcceptorBuilder {
    /// Creates a builder using the given cryptographic provider.
    #[must_use]
    pub fn new(provider: Arc<CryptoProvider>) -> Self {
        Self {
            provider,
            chain: Vec::new(),
            key: None,
            client_roots: None,
            versions: None,
            close_notify: true,
            linger: Duration::ZERO,
            handshake_timeout: HANDSHAKE_TIMEOUT,
        }
    }

    /// Sets the server certificate chain, ordered from leaf certificate to intermediates.
    #[must_use]
    pub fn cert_chain(mut self, chain: Vec<CertificateDer<'static>>) -> Self {
        self.chain = chain;
        self
    }

    /// Loads the server certificate chain from a PEM file, ordered leaf first.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the file cannot be read or contains no valid certificates.
    pub fn cert_chain_pem_file(self, path: impl AsRef<Path>) -> io::Result<Self> {
        let chain = pem_certs(path.as_ref())?;
        Ok(self.cert_chain(chain))
    }

    /// Sets the private key corresponding to the server leaf certificate.
    #[must_use]
    pub fn key(mut self, key: PrivateKeyDer<'static>) -> Self {
        self.key = Some(key);
        self
    }

    /// Loads the server private key from a PEM file.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the file cannot be read or contains no valid private key.
    pub fn key_pem_file(self, path: impl AsRef<Path>) -> io::Result<Self> {
        let key = PrivateKeyDer::from_pem_file(path.as_ref()).map_err(invalid)?;
        Ok(self.key(key))
    }

    /// Configures trusted root certificates for mandatory client certificate verification (mTLS).
    #[must_use]
    pub fn client_auth_roots(mut self, roots: Vec<CertificateDer<'static>>) -> Self {
        self.client_roots = Some(roots);
        self
    }

    /// Loads trusted client CA certificates from a PEM file for mandatory mTLS verification.
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the file cannot be read or contains no valid certificates.
    pub fn client_auth_ca_pem_file(self, path: impl AsRef<Path>) -> io::Result<Self> {
        let roots = pem_certs(path.as_ref())?;
        Ok(self.client_auth_roots(roots))
    }

    /// Restricts supported TLS protocol versions (defaults to TLS 1.2 and TLS 1.3).
    #[must_use]
    pub fn versions(mut self, versions: &'static [&'static SupportedProtocolVersion]) -> Self {
        self.versions = Some(versions);
        self
    }

    /// Controls whether graceful server shutdown sends a TLS `close_notify` alert before closing
    /// the socket (defaults to `true`).
    #[must_use]
    pub const fn close_notify(mut self, send: bool) -> Self {
        self.close_notify = send;
        self
    }

    /// Sets how long to read from the peer after sending `close_notify` (defaults to zero).
    ///
    /// A non-zero linger duration allows the server to observe a reciprocal `close_notify` alert
    /// sent by the client in response to the server's final reply.
    #[must_use]
    pub const fn linger(mut self, wait: Duration) -> Self {
        self.linger = wait;
        self
    }

    /// Sets the maximum duration allowed for a client to complete the TLS handshake (defaults to
    /// 5 seconds).
    #[must_use]
    pub const fn handshake_timeout(mut self, timeout: Duration) -> Self {
        self.handshake_timeout = timeout;
        self
    }

    /// Validates the configuration and builds a shared [`TlsAcceptor`].
    ///
    /// # Errors
    ///
    /// Returns an [`io::Error`] if the certificate chain or private key is missing or rejected by
    /// `rustls`.
    pub fn build(self) -> io::Result<Arc<TlsAcceptor>> {
        let key = self.key.ok_or_else(|| invalid("no server key"))?;
        if self.chain.is_empty() {
            return Err(invalid("no server certificate chain"));
        }
        let builder = ServerConfig::builder_with_provider(Arc::clone(&self.provider));
        let builder = match self.versions {
            Some(v) => builder.with_protocol_versions(v),
            None => builder.with_safe_default_protocol_versions(),
        }
        .map_err(invalid)?;
        let builder = match self.client_roots {
            Some(certs) => {
                let mut roots = RootCertStore::empty();
                for c in certs {
                    roots.add(c).map_err(invalid)?;
                }
                let verifier = WebPkiClientVerifier::builder_with_provider(
                    Arc::new(roots),
                    Arc::clone(&self.provider),
                )
                .build()
                .map_err(invalid)?;
                builder.with_client_cert_verifier(verifier)
            }
            None => builder.with_no_client_auth(),
        };
        let config = builder.with_single_cert(self.chain, key).map_err(invalid)?;
        Ok(Arc::new(TlsAcceptor {
            config: Arc::new(config),
            close_notify: self.close_notify,
            linger: self.linger,
            handshake_timeout: self.handshake_timeout,
            log: Arc::new(Log::default()),
        }))
    }
}

/// Transport acceptor that upgrades accepted TCP sockets into `rustls` server streams.
///
/// Passed to [`ServerOptions::with_acceptor`](crate::ServerOptions::with_acceptor). The TLS
/// handshake runs on each connection worker thread so a stalled handshake does not block the
/// listener accept loop.
pub struct TlsAcceptor {
    config: Arc<ServerConfig>,
    close_notify: bool,
    linger: Duration,
    handshake_timeout: Duration,
    log: Arc<Log>,
}

impl fmt::Debug for TlsAcceptor {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TlsAcceptor")
            .field("close_notify", &self.close_notify)
            .field("linger", &self.linger)
            .field("sessions", &self.log.lock().len())
            .finish_non_exhaustive()
    }
}

impl TlsAcceptor {
    /// Returns a [`TlsAcceptorBuilder`] using the given cryptographic provider.
    #[must_use]
    pub fn builder(provider: Arc<CryptoProvider>) -> TlsAcceptorBuilder {
        TlsAcceptorBuilder::new(provider)
    }

    /// Returns a snapshot of all recorded TLS sessions in acceptance order.
    #[must_use]
    pub fn sessions(&self) -> Vec<TlsSession> {
        self.log.lock().clone()
    }

    /// Waits up to `timeout` for the session log to satisfy `pred`, returning whether `pred`
    /// succeeded.
    pub fn wait_sessions(
        &self,
        timeout: Duration,
        mut pred: impl FnMut(&[TlsSession]) -> bool,
    ) -> bool {
        let end = Instant::now().checked_add(timeout);
        let mut g = self.log.lock();
        loop {
            if pred(&g) {
                return true;
            }
            let left = end.map_or(timeout, |e| e.saturating_duration_since(Instant::now()));
            if left.is_zero() {
                return false;
            }
            g = self
                .log
                .changed
                .wait_timeout(g, left)
                .unwrap_or_else(PoisonError::into_inner)
                .0;
        }
    }

    /// Drives the server side TLS handshake to completion over `tcp` within `handshake_timeout`.
    fn handshake(&self, conn: &mut ServerConnection, tcp: &mut TcpStream) -> Result<(), String> {
        let end = Instant::now().checked_add(self.handshake_timeout);
        while conn.is_handshaking() {
            write_all_tls(conn, tcp).map_err(|e| e.to_string())?;
            if !conn.is_handshaking() {
                break;
            }
            match conn.read_tls(tcp) {
                Ok(0) => return Err("EOF in the handshake".to_owned()),
                Ok(_) => {
                    if let Err(e) = conn.process_new_packets() {
                        // Flush any outgoing fatal TLS alert before closing the socket.
                        drop(write_all_tls(conn, tcp));
                        return Err(e.to_string());
                    }
                }
                Err(e) if is_timeout(&e) => {
                    if end.is_some_and(|e| Instant::now() >= e) {
                        return Err("handshake timeout".to_owned());
                    }
                }
                Err(e) => return Err(e.to_string()),
            }
        }
        write_all_tls(conn, tcp).map_err(|e| e.to_string())
    }
}

impl Acceptor for TlsAcceptor {
    // ServerConnection is large in debug builds; worker threads have sufficient stack space.
    #[allow(clippy::large_stack_frames)]
    fn accept(&self, tcp: TcpStream) -> io::Result<Box<dyn Transport>> {
        let at = self.log.open();
        let mut conn = match ServerConnection::new(Arc::clone(&self.config)) {
            Ok(c) => Box::new(c),
            Err(e) => {
                self.log
                    .update(at, |s| s.handshake = Some(Err(e.to_string())));
                return Err(io::Error::other(e));
            }
        };
        let mut tcp = tcp;
        let result = self.handshake(&mut conn, &mut tcp);
        let client_cert = conn.peer_certificates().is_some_and(|c| !c.is_empty());
        let version = conn.protocol_version().map(|v| format!("{v:?}"));
        let failed = result.as_ref().err().cloned();
        self.log.update(at, |s| {
            s.handshake = Some(result);
            s.client_cert = client_cert;
            s.version = version;
        });
        if let Some(text) = failed {
            return Err(io::Error::other(text));
        }
        Ok(Box::new(TlsStream {
            conn,
            tcp,
            at,
            log: Arc::clone(&self.log),
            close_notify: self.close_notify,
            linger: self.linger,
        }))
    }
}

/// Returns whether `e` is a transient socket read timeout or signal interruption.
fn is_timeout(e: &io::Error) -> bool {
    matches!(
        e.kind(),
        ErrorKind::WouldBlock | ErrorKind::TimedOut | ErrorKind::Interrupted
    )
}

/// Writes all pending outbound TLS ciphertext records from `conn` to `tcp`.
fn write_all_tls(conn: &mut ServerConnection, tcp: &mut TcpStream) -> io::Result<()> {
    while conn.wants_write() {
        if conn.write_tls(tcp)? == 0 {
            return Err(ErrorKind::WriteZero.into());
        }
    }
    Ok(())
}

/// server side TLS transport stream pairing a `rustls` [`ServerConnection`] with a [`TcpStream`].
struct TlsStream {
    conn: Box<ServerConnection>,
    tcp: TcpStream,
    at: usize,
    log: Arc<Log>,
    close_notify: bool,
    linger: Duration,
}

impl TlsStream {
    /// Reads ciphertext from the TCP socket and processes newly received TLS records.
    fn read_records(&mut self) -> io::Result<()> {
        match self.conn.read_tls(&mut self.tcp) {
            // If the socket reached EOF, `process_new_packets` and `reader().read()` report it
            // once buffered plaintext is drained.
            Ok(_) => {}
            Err(e) if is_timeout(&e) => return Err(e),
            Err(e) => {
                self.log.peer_end(self.at, PeerEnd::Eof);
                return Err(e);
            }
        }
        if let Err(e) = self.conn.process_new_packets() {
            drop(write_all_tls(&mut self.conn, &mut self.tcp));
            self.log.peer_end(self.at, PeerEnd::Error(e.to_string()));
            return Err(io::Error::new(ErrorKind::InvalidData, e));
        }
        Ok(())
    }
}

impl Read for TlsStream {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            match self.conn.reader().read(buf) {
                Ok(0) if !buf.is_empty() => {
                    self.log.peer_end(self.at, PeerEnd::CloseNotify);
                    return Ok(0);
                }
                Ok(n) => return Ok(n),
                Err(e) if e.kind() == ErrorKind::WouldBlock => {}
                Err(e) => {
                    if e.kind() == ErrorKind::UnexpectedEof {
                        self.log.peer_end(self.at, PeerEnd::Eof);
                    }
                    return Err(e);
                }
            }
            self.read_records()?;
            // Key updates or post-handshake messages may queue outbound TLS records.
            write_all_tls(&mut self.conn, &mut self.tcp)?;
        }
    }
}

impl Write for TlsStream {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.conn.writer().write(buf)?;
        write_all_tls(&mut self.conn, &mut self.tcp)?;
        Ok(n)
    }

    fn flush(&mut self) -> io::Result<()> {
        write_all_tls(&mut self.conn, &mut self.tcp)
    }
}

impl Transport for TlsStream {
    fn tcp(&self) -> &TcpStream {
        &self.tcp
    }

    fn finish(&mut self, graceful: bool) {
        if !graceful {
            return;
        }
        if self.close_notify {
            self.conn.send_close_notify();
            let sent = write_all_tls(&mut self.conn, &mut self.tcp).is_ok();
            self.log.update(self.at, |s| s.sent_close_notify = sent);
        }
        // Drain incoming bytes up to `linger` so a peer `close_notify` can be recorded.
        let Some(end) = Instant::now().checked_add(self.linger) else {
            return;
        };
        let mut scratch = vec![0u8; 4096];
        while Instant::now() < end && !self.log.has_peer_end(self.at) {
            match self.read(&mut scratch) {
                Ok(0) => return,
                Ok(_) => {}
                Err(e) if is_timeout(&e) => {}
                Err(_) => return,
            }
        }
    }
}
