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

//! Rustls-backed client session wrapper managing in-memory cryptographic state and record
//! buffers.
//!
//! # Architectural Role in `zk-driver`
//!
//! [`RustlsConn`] adapts [`rustls::ClientConnection`] to the driver's sans-I/O [`TlsConn`] trait:
//! the session has no awareness of raw socket descriptors or network polling. Ciphertext read from
//! the nonblocking [`TcpSock`](crate::net::TcpSock) by `crate::iocore_tls` is delivered via
//! [`TlsConn::feed`], decrypted ZooKeeper wire frames are drained via [`TlsConn::read_plaintext`],
//! outbound frames are encrypted via [`TlsConn::write_frame`], and outbound TLS record flights are
//! extracted into `TlsTransport::ct_out` via [`TlsConn::take_ciphertext`].
//!
//! # Failure Latching and Plaintext Preservation
//!
//! When TLS record parsing fails or the peer transmits a fatal alert or `close_notify`, the failure
//! status is latched in `RustlsConn::latched`. To preserve application payload integrity when a
//! server sends a final ZooKeeper reply frame immediately followed by a TLS alert or TCP FIN,
//! `RustlsConn::process` drains all plaintext bytes decrypted prior to the error into `self.rx`
//! *before* latching the failure, and [`TlsConn::read_plaintext`] returns all buffered `self.rx`
//! bytes before surfacing `self.latched` on the subsequent call.

use std::io::{BufRead, ErrorKind, Write};

use rustls::ClientConnection;

use crate::net::tls::{TlsConn, TlsFailure};

/// In-memory Rustls client session engine operating without socket coupling.
///
/// Instances are boxed as `Box<dyn TlsConn>` to ensure large `ClientConnection` buffers
/// reside on the heap rather than consuming thread stack space.
pub struct RustlsConn {
    /// Active Rustls client connection instance with unconstrained send buffering.
    conn: ClientConnection,
    /// Outbound ciphertext records awaiting transmission to the network socket.
    tx: Vec<u8>,
    /// Decrypted application plaintext bytes waiting to be consumed by the protocol layer.
    rx: Vec<u8>,
    /// First fatal protocol error, alert, or closure condition latched by the session.
    latched: Option<TlsFailure>,
}

impl RustlsConn {
    /// Wraps an initialized [`ClientConnection`] in a sans-I/O session container.
    ///
    /// Disables send buffer limits (`set_buffer_limit(None)`) so [`TlsConn::write_frame`] always
    /// accepts a complete ZooKeeper frame in a single call, and pre-populates `tx` with the initial
    /// `ClientHello` flight.
    #[must_use]
    pub fn new(mut conn: ClientConnection) -> Self {
        conn.set_buffer_limit(None);
        // Extract the initial ClientHello flight into the transmission buffer.
        let mut tx = Vec::new();
        pump(&mut conn, &mut tx);
        Self {
            conn,
            tx,
            rx: Vec::new(),
            latched: None,
        }
    }

    /// Feeds raw network ciphertext into the Rustls session state machine, processing records and
    /// preserving decoded plaintext.
    fn feed_all(&mut self, ct: &[u8]) -> Result<(), TlsFailure> {
        let mut ct = ct;
        let mut stalled = false;
        while !ct.is_empty() && self.latched.is_none() {
            match self.conn.read_tls(&mut ct) {
                // Rustls returns Ok(0) upon encountering EOF or close_notify.
                Ok(0) => self.latch(TlsFailure::Closed),
                Ok(_) => {
                    stalled = false;
                    self.process();
                }
                Err(_) if stalled => self.latch(TlsFailure::Data),
                Err(_) => {
                    stalled = true;
                    self.process();
                }
            }
        }
        self.latched.map_or(Ok(()), Err)
    }

    /// Processes newly buffered records from Rustls packet buffers, draining available application
    /// plaintext before handling error conditions.
    fn process(&mut self) {
        let r = self.conn.process_new_packets();
        // Extract all valid application plaintext decoded before the error was encountered.
        self.take_plaintext();
        match r {
            // Peer alert records terminate the stream, mirroring OpenSSL EHOSTDOWN semantics.
            Err(rustls::Error::AlertReceived(_)) => self.latch(TlsFailure::Closed),
            Err(_) => self.latch(TlsFailure::Data),
            Ok(_) => {}
        }
        // Extract any response alerts or handshake records generated during packet processing.
        self.pump_out();
    }

    /// Records the primary fatal protocol failure, ignoring any subsequent secondary errors.
    fn latch(&mut self, f: TlsFailure) {
        if self.latched.is_none() {
            // Handshake phase errors are uniformly categorized under TlsFailure::Handshake.
            let f = if self.conn.is_handshaking() {
                TlsFailure::Handshake
            } else {
                f
            };
            self.latched = Some(f);
        }
    }

    /// Drains all decrypted application data bytes from the Rustls reader into the internal `rx`
    /// buffer.
    fn take_plaintext(&mut self) {
        let mut closed = false;
        let mut reader = self.conn.reader();
        loop {
            match reader.fill_buf() {
                Ok([]) => {
                    closed = true;
                    break;
                }
                Ok(chunk) => {
                    let n = chunk.len();
                    self.rx.extend_from_slice(chunk);
                    reader.consume(n);
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(_) => {
                    closed = true;
                    break;
                }
            }
        }
        if closed {
            self.latch(TlsFailure::Closed);
        }
    }

    /// Flushes all pending outbound records from the Rustls connection into the `tx` ciphertext
    /// buffer.
    fn pump_out(&mut self) {
        pump(&mut self.conn, &mut self.tx);
    }
}

/// Flushes pending record flights from the Rustls connection into the designated ciphertext buffer.
fn pump(conn: &mut ClientConnection, tx: &mut Vec<u8>) {
    while conn.wants_write() {
        match conn.write_tls(tx) {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
    }
}

impl TlsConn for RustlsConn {
    fn is_handshaking(&self) -> bool {
        self.conn.is_handshaking()
    }

    fn has_ciphertext(&self) -> bool {
        !self.tx.is_empty() || self.conn.wants_write()
    }

    fn feed(&mut self, ciphertext: &[u8]) -> Result<(), TlsFailure> {
        self.feed_all(ciphertext)
    }

    fn feed_eof(&mut self) -> Result<(), TlsFailure> {
        if self.latched.is_none() {
            // Signal EOF to Rustls via zero-length read slice to drain any remaining decoded
            // frames.
            let _ = self.conn.read_tls(&mut &[][..]);
            self.process();
            // Latch stream closure in case no reader error was surfaced.
            self.latch(TlsFailure::Closed);
        }
        self.latched.map_or(Ok(()), Err)
    }

    fn read_plaintext(&mut self, out: &mut Vec<u8>) -> Result<usize, TlsFailure> {
        if !self.rx.is_empty() {
            let n = self.rx.len();
            out.append(&mut self.rx);
            return Ok(n);
        }
        self.latched.map_or(Ok(0), Err)
    }

    fn write_frame(&mut self, plaintext: &[u8]) -> Result<(), TlsFailure> {
        if let Some(f) = self.latched {
            return Err(f);
        }
        // Send buffer limits are disabled, so writing the entire frame should always succeed
        // unless broken.
        if self.conn.writer().write_all(plaintext).is_err() {
            self.latch(TlsFailure::Data);
            return self.latched.map_or(Err(TlsFailure::Data), Err);
        }
        self.pump_out();
        Ok(())
    }

    fn take_ciphertext(&mut self, out: &mut Vec<u8>) -> usize {
        self.pump_out();
        let n = self.tx.len();
        super::move_ciphertext(out, &mut self.tx);
        n
    }

    fn send_close_notify(&mut self) {
        // Suppress close_notify if the session has failed or handshake never finished.
        if self.latched.is_some() || self.conn.is_handshaking() {
            return;
        }
        self.conn.send_close_notify();
        self.pump_out();
    }
}

/// Test fixtures, mock in-memory TLS server sessions, and flight exchange utilities.
#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
pub(crate) mod testing {
    use std::io::Read;
    use std::path::PathBuf;
    use std::sync::Arc;

    use rustls::client::danger::ServerCertVerifier;
    use rustls::crypto::CryptoProvider;
    use rustls::pki_types::pem::PemObject;
    use rustls::pki_types::{CertificateDer, IpAddr, PrivateKeyDer, ServerName};
    use rustls::{
        ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection,
        SupportedProtocolVersion,
    };

    use super::RustlsConn;
    use crate::net::tls::connector::default_provider;
    use crate::net::tls::verify::ChainOnlyVerifier;
    use crate::net::tls::{TlsConn, TlsFailure};

    /// Protocol version list restricting negotiation to TLS 1.3 exclusively.
    pub(crate) const TLS13: &[&SupportedProtocolVersion] = &[&rustls::version::TLS13];
    /// Protocol version list restricting negotiation to TLS 1.2 exclusively.
    pub(crate) const TLS12: &[&SupportedProtocolVersion] = &[&rustls::version::TLS12];

    pub(crate) fn fixture(name: &str) -> PathBuf {
        zk_testkit::fixtures::tls_dir().join(name)
    }

    pub(crate) fn provider() -> Arc<CryptoProvider> {
        default_provider()
    }

    pub(crate) fn certs(name: &str) -> Vec<CertificateDer<'static>> {
        CertificateDer::pem_file_iter(fixture(name))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap()
    }

    pub(crate) fn key(name: &str) -> PrivateKeyDer<'static> {
        PrivateKeyDer::from_pem_file(fixture(name)).unwrap()
    }

    pub(crate) fn roots(name: &str) -> Arc<RootCertStore> {
        let mut roots = RootCertStore::empty();
        for c in certs(name) {
            roots.add(c).unwrap();
        }
        Arc::new(roots)
    }

    /// Constructs a mock TLS server session using `server.pem` without client authentication.
    pub(crate) fn server(versions: &[&'static SupportedProtocolVersion]) -> Box<ServerConnection> {
        let cfg = ServerConfig::builder_with_provider(provider())
            .with_protocol_versions(versions)
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(certs("server.pem"), key("server.key"))
            .unwrap();
        let mut s = Box::new(ServerConnection::new(Arc::new(cfg)).unwrap());
        s.set_buffer_limit(None);
        s
    }

    /// Constructs a client session supporting TLS 1.3 and 1.2 using the specified custom
    /// certificate verifier.
    pub(crate) fn client_with(verifier: Arc<dyn ServerCertVerifier>) -> Box<RustlsConn> {
        let mut cfg = ClientConfig::builder_with_provider(provider())
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        cfg.enable_sni = false;
        let name = ServerName::IpAddress(IpAddr::from(std::net::IpAddr::from([127, 0, 0, 1])));
        Box::new(RustlsConn::new(
            ClientConnection::new(Arc::new(cfg), name).unwrap(),
        ))
    }

    /// Constructs a client session performing chain-only validation against the specified root CA
    /// certificate.
    pub(crate) fn client(ca: &str) -> Box<RustlsConn> {
        client_with(Arc::new(ChainOnlyVerifier::new(roots(ca), provider())))
    }

    /// Extracts all pending ciphertext bytes from the mock server connection.
    pub(crate) fn server_out(s: &mut ServerConnection) -> Vec<u8> {
        let mut out = Vec::new();
        while s.wants_write() {
            s.write_tls(&mut out).unwrap();
        }
        out
    }

    /// Feeds ciphertext bytes into the server session and returns all decrypted application
    /// plaintext.
    pub(crate) fn server_in(s: &mut ServerConnection, mut ct: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        while !ct.is_empty() {
            if s.read_tls(&mut ct).unwrap() == 0 {
                break;
            }
            if s.process_new_packets().is_err() {
                break;
            }
            out.extend_from_slice(&server_read(s));
        }
        out
    }

    /// Reads all decrypted application plaintext currently buffered in the server connection.
    pub(crate) fn server_read(s: &mut ServerConnection) -> Vec<u8> {
        let mut out = Vec::new();
        let mut buf = vec![0u8; 4096];
        loop {
            match s.reader().read(&mut buf) {
                Ok(0) | Err(_) => return out,
                Ok(n) => out.extend_from_slice(&buf[..n]),
            }
        }
    }

    /// Interleaves ciphertext exchange flights between client and server sessions until both
    /// pipelines idle.
    pub(crate) fn exchange(
        c: &mut dyn TlsConn,
        s: &mut ServerConnection,
        split: bool,
    ) -> Result<Vec<u8>, TlsFailure> {
        let mut got = Vec::new();
        loop {
            let mut ct = Vec::new();
            c.take_ciphertext(&mut ct);
            got.extend_from_slice(&server_in(s, &ct));
            let sct = server_out(s);
            if ct.is_empty() && sct.is_empty() {
                return Ok(got);
            }
            if split {
                for b in &sct {
                    c.feed(std::slice::from_ref(b))?;
                }
            } else {
                c.feed(&sct)?;
            }
        }
    }

    /// Executes an in-memory client-server handshake, asserting that both endpoints successfully
    /// negotiate.
    pub(crate) fn connected(
        versions: &[&'static SupportedProtocolVersion],
    ) -> (Box<RustlsConn>, Box<ServerConnection>) {
        let mut c = client("ca.pem");
        let mut s = server(versions);
        exchange(c.as_mut(), &mut s, false).unwrap();
        assert!(!c.is_handshaking());
        assert!(!s.is_handshaking());
        (c, s)
    }
}

#[cfg(test)]
#[allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames
)]
mod tests {
    use super::testing::{TLS12, TLS13, connected};

    #[test]
    #[cfg_attr(
        miri,
        ignore = "the handshake reaches the native code of ring, which Miri cannot run"
    )]
    fn handshake_completes_in_memory_with_tls13_and_tls12() {
        for (versions, want) in [
            (TLS13, rustls::ProtocolVersion::TLSv1_3),
            (TLS12, rustls::ProtocolVersion::TLSv1_2),
        ] {
            let (c, s) = connected(versions);
            assert_eq!(s.protocol_version(), Some(want));
            assert_eq!(c.conn.protocol_version(), Some(want));
            // Verify that C-compatible clients omit Server Name Indication (SNI).
            assert_eq!(s.server_name(), None);
        }
    }

    /// Verifies buffer swapping optimization when draining into empty destination vectors.
    #[test]
    #[cfg_attr(
        miri,
        ignore = "the handshake reaches the native code of ring, which Miri cannot run"
    )]
    fn take_ciphertext_swaps_into_an_empty_buffer_in_order() {
        use super::testing::server_in;
        use crate::net::tls::TlsConn;

        let (mut c, mut s) = connected(TLS13);
        let first: Vec<u8> = (0..40_000u32).map(|i| (i % 251) as u8).collect();
        let second: Vec<u8> = (0..30_000u32).map(|i| (i % 241) as u8).collect();
        let mut out = Vec::with_capacity(8);
        let spare = out.as_ptr();
        c.write_frame(&first).unwrap();
        let n1 = c.take_ciphertext(&mut out);
        assert!(n1 > first.len() && out.len() == n1);
        assert!(c.tx.is_empty() && c.tx.as_ptr() == spare, "the swap");
        c.write_frame(&second).unwrap();
        let n2 = c.take_ciphertext(&mut out);
        assert_eq!(out.len(), n1 + n2, "the append keeps the first chunk");
        let mut want = first.clone();
        want.extend_from_slice(&second);
        assert_eq!(server_in(&mut s, &out), want);

        // Verify that clearing the destination vector allows subsequent swap re-use.
        let held = out.as_ptr();
        out.clear();
        c.write_frame(&second).unwrap();
        assert_eq!(c.take_ciphertext(&mut out), n2);
        assert!(out.len() == n2 && c.tx.as_ptr() == held);
        assert_eq!(server_in(&mut s, &out), second);
    }
}
