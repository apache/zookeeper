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

//! Fuzzes the `zk-driver` TLS session wrapper (`RustlsConn`) under arbitrary and corrupted
//! ciphertext streams.
//!
//! In raw mode, fuzz bytes are fed directly as server ciphertext to a freshly initialized client
//! session. In session mode, the harness completes a valid TLS 1.2 or TLS 1.3 handshake against
//! an in-memory server before executing commands that write plaintext on either side, segment
//! record delivery, inject bit-flips or extra bytes into the ciphertext stream, and trigger EOF
//! or `close_notify`.
//!
//! Verified invariants:
//! - Error latching: once a session reports a `TlsFailure`, all subsequent operations return that
//!   identical failure.
//! - Data integrity: client-read plaintext is always a prefix of server-sent plaintext (and
//!   matches completely when no stream corruption, EOF, or close occurs).
//!
//! Test certificates are provisioned and cached via `zk-testkit`.

#![no_main]
#![forbid(unsafe_code)]

use std::io::{Read, Write};
use std::sync::{Arc, OnceLock};

use libfuzzer_sys::fuzz_target;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, IpAddr, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection};
use zk_driver::net::tls::verify::ChainOnlyVerifier;
use zk_driver::net::tls::{RustlsConn, TlsConn, TlsFailure, default_provider};
use zk_fuzz::Cursor;

/// Maximum number of stream commands executed per fuzz input.
const MAX_COMMANDS: usize = 256;

/// Cached client and server TLS configurations.
struct Material {
    client: Arc<ClientConfig>,
    server13: Arc<ServerConfig>,
    server12: Arc<ServerConfig>,
}

fn material() -> &'static Material {
    static M: OnceLock<Material> = OnceLock::new();
    M.get_or_init(|| {
        let dir = zk_testkit::fixtures::tls_dir();
        let certs = |name: &str| -> Vec<CertificateDer<'static>> {
            CertificateDer::pem_file_iter(dir.join(name))
                .expect("failed to open certificate fixture")
                .collect::<Result<_, _>>()
                .expect("failed to parse certificate fixture")
        };
        let mut roots = RootCertStore::empty();
        for c in certs("ca.pem") {
            roots.add(c).expect("failed to add CA certificate");
        }
        let provider = default_provider();
        let verifier = Arc::new(ChainOnlyVerifier::new(
            Arc::new(roots),
            Arc::clone(&provider),
        ));
        let mut client = ClientConfig::builder_with_provider(Arc::clone(&provider))
            .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
            .expect("valid TLS protocol versions")
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        // Match drop-in C client behavior by disabling SNI.
        client.enable_sni = false;
        let server = |v: &'static rustls::SupportedProtocolVersion| {
            let key =
                PrivateKeyDer::from_pem_file(dir.join("server.key")).expect("failed to load key");
            Arc::new(
                ServerConfig::builder_with_provider(Arc::clone(&provider))
                    .with_protocol_versions(&[v])
                    .expect("valid TLS protocol version")
                    .with_no_client_auth()
                    .with_single_cert(certs("server.pem"), key)
                    .expect("valid server identity"),
            )
        };
        Material {
            client: Arc::new(client),
            server13: server(&rustls::version::TLS13),
            server12: server(&rustls::version::TLS12),
        }
    })
}

/// Client TLS session wrapper and invariant tracking state.
struct Client {
    conn: RustlsConn,
    /// First terminal failure reported by the session, which must remain latched thereafter.
    latched: Option<TlsFailure>,
    /// Cumulative plaintext bytes read by the client.
    got: Vec<u8>,
}

impl Client {
    fn new() -> Self {
        let name = ServerName::IpAddress(IpAddr::from(std::net::IpAddr::from([127, 0, 0, 1])));
        let conn = ClientConnection::new(Arc::clone(&material().client), name)
            .expect("failed to initialize client TLS connection");
        Self {
            conn: RustlsConn::new(conn),
            latched: None,
            got: Vec::new(),
        }
    }

    /// Records an operation outcome and verifies that any prior failure remains latched.
    fn note(&mut self, r: Result<(), TlsFailure>) {
        match (self.latched, r) {
            (Some(f), r) => assert_eq!(r, Err(f), "session did not preserve latched TlsFailure"),
            (None, Err(f)) => self.latched = Some(f),
            (None, Ok(())) => {}
        }
    }

    fn feed(&mut self, ct: &[u8]) {
        let r = self.conn.feed(ct);
        self.note(r);
    }

    fn feed_eof(&mut self) {
        let r = self.conn.feed_eof();
        assert!(r.is_err(), "feed_eof() must report a failure");
        self.note(r);
    }

    fn read(&mut self) {
        let before = self.got.len();
        match self.conn.read_plaintext(&mut self.got) {
            Ok(n) => {
                assert_eq!(
                    self.got.len() - before,
                    n,
                    "read_plaintext return count did not match appended byte length"
                );
                if n == 0 {
                    assert!(self.latched.is_none(), "latched session returned Ok(0)");
                }
            }
            Err(f) => assert_eq!(
                Some(f),
                self.latched,
                "read_plaintext returned unlatched failure"
            ),
        }
    }

    /// Queues a plaintext frame for encryption, returning `true` if accepted.
    fn write(&mut self, pt: &[u8]) -> bool {
        let r = self.conn.write_frame(pt);
        let ok = r.is_ok();
        self.note(r);
        ok
    }

    fn take(&mut self, out: &mut Vec<u8>) {
        let before = out.len();
        let n = self.conn.take_ciphertext(out);
        assert_eq!(
            out.len() - before,
            n,
            "take_ciphertext return count did not match appended byte length"
        );
        assert!(
            !self.conn.has_ciphertext(),
            "has_ciphertext() remained true after take_ciphertext()"
        );
    }
}

/// Drains all pending outbound TLS records from `s` into `out`.
fn server_out(s: &mut ServerConnection, out: &mut Vec<u8>) {
    while s.wants_write() {
        match s.write_tls(out) {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
    }
}

/// Feeds client ciphertext `ct` into the server connection and drains decoded plaintext into `got`.
/// Returns `false` if the server rejects the TLS records.
fn server_in(s: &mut ServerConnection, mut ct: &[u8], got: &mut Vec<u8>) -> bool {
    while !ct.is_empty() {
        match s.read_tls(&mut ct) {
            Ok(0) | Err(_) => return false,
            Ok(_) => {}
        }
        if s.process_new_packets().is_err() {
            return false;
        }
        let mut buf = [0u8; 4096];
        loop {
            match s.reader().read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => got.extend_from_slice(&buf[..n]),
            }
        }
    }
    true
}

/// Feeds unauthenticated fuzzed ciphertext directly to a freshly initialized client connection.
fn raw(c: &mut Cursor<'_>) {
    let mut client = Client::new();
    let mut wire = Vec::new();
    client.take(&mut wire);
    assert!(!wire.is_empty(), "initial take_ciphertext must emit ClientHello");
    for _ in 0..MAX_COMMANDS {
        if c.is_empty() {
            break;
        }
        match c.u8() % 6 {
            0 | 1 => client.feed(c.lp()),
            2 => client.feed_eof(),
            3 => client.read(),
            4 => {
                let _ = client.write(c.lp());
            }
            _ => {
                if c.bool() {
                    client.conn.send_close_notify();
                }
                client.take(&mut wire);
                wire.clear();
            }
        }
    }
    assert!(
        client.got.is_empty(),
        "unhandshaken client session must never yield application plaintext"
    );
}

/// Completes a valid handshake with an in-memory server and executes a stream of read, write,
/// segmentation, and fault-injection commands.
fn session(c: &mut Cursor<'_>, tls12: bool) {
    let m = material();
    let cfg = if tls12 { &m.server12 } else { &m.server13 };
    let mut server =
        ServerConnection::new(Arc::clone(cfg)).expect("failed to initialize server TLS connection");
    server.set_buffer_limit(None);
    let mut client = Client::new();

    // Perform an undamaged handshake.
    let mut server_got = Vec::new();
    for _ in 0..16 {
        let mut ct = Vec::new();
        client.take(&mut ct);
        assert!(
            server_in(&mut server, &ct, &mut server_got),
            "server rejected valid client handshake flight"
        );
        let mut sct = Vec::new();
        server_out(&mut server, &mut sct);
        if ct.is_empty() && sct.is_empty() {
            break;
        }
        client.feed(&sct);
    }
    assert!(
        client.latched.is_none(),
        "handshake failed unexpectedly: {:?}",
        client.latched
    );
    assert!(
        !client.conn.is_handshaking() && !server.is_handshaking(),
        "handshake failed to complete within 16 rounds"
    );

    let mut s2c: Vec<u8> = Vec::new();
    let mut sent: Vec<u8> = Vec::new();
    let mut c2s_sent: Vec<u8> = Vec::new();
    let mut damaged = false;
    let mut client_eof = false;
    let mut server_ok = true;
    let mut server_closed = false;
    // Production driver code sends close_notify only during teardown and never reads afterward;
    // stop the simulation upon client close_notify to match driver lifecycle invariants.
    let mut client_closed = false;
    for _ in 0..MAX_COMMANDS {
        if c.is_empty() || client_closed {
            break;
        }
        match c.u8() % 12 {
            0 | 1 if !server_closed => {
                // Server refuses further plaintext writes after processing a fatal alert.
                let pt = c.lp();
                if server.writer().write_all(pt).is_ok() {
                    sent.extend_from_slice(pt);
                    server_out(&mut server, &mut s2c);
                } else {
                    server_closed = true;
                }
            }
            2 => {
                let n = usize::from(c.u16()).min(s2c.len());
                let chunk: Vec<u8> = s2c.drain(..n).collect();
                client.feed(&chunk);
            }
            3 => {
                let chunk = std::mem::take(&mut s2c);
                client.feed(&chunk);
            }
            4 if !s2c.is_empty() => {
                let at = usize::from(c.u16()) % s2c.len();
                s2c[at] ^= c.u8() | 1;
                damaged = true;
            }
            5 => {
                s2c.extend_from_slice(c.lp());
                damaged = true;
            }
            6 => client.read(),
            7 => {
                let pt = c.lp();
                if client.write(pt) {
                    c2s_sent.extend_from_slice(pt);
                }
            }
            8 => {
                let mut ct = Vec::new();
                client.take(&mut ct);
                if server_ok {
                    server_ok = server_in(&mut server, &ct, &mut server_got);
                }
            }
            9 => {
                server.send_close_notify();
                server_out(&mut server, &mut s2c);
                server_closed = true;
            }
            10 => {
                client.feed_eof();
                client_eof = true;
            }
            11 => {
                client.conn.send_close_notify();
                client_closed = true;
            }
            _ => {}
        }
        assert!(
            sent.starts_with(&client.got),
            "client yielded plaintext not sent by the server"
        );
        assert!(
            c2s_sent.starts_with(&server_got),
            "server yielded plaintext not sent by the client"
        );
    }

    // Flush remaining queued records and verify final plaintext prefixes.
    if !client_closed {
        let chunk = std::mem::take(&mut s2c);
        client.feed(&chunk);
    }
    client.read();
    assert!(
        sent.starts_with(&client.got),
        "client yielded plaintext not sent by the server"
    );
    if !damaged && !client_eof && !client_closed {
        assert!(
            matches!(client.latched, None | Some(TlsFailure::Closed)),
            "undamaged stream reported unexpected failure: {:?}",
            client.latched
        );
        assert_eq!(
            client.got.len(),
            sent.len(),
            "undamaged stream truncated server plaintext"
        );
    }
    let mut ct = Vec::new();
    client.take(&mut ct);
    if server_ok && server_in(&mut server, &ct, &mut server_got) && !client_closed {
        assert_eq!(
            server_got, c2s_sent,
            "undamaged stream truncated client plaintext"
        );
    }
}

fuzz_target!(|data: &[u8]| {
    let mut c = Cursor::new(data);
    match c.u8() % 3 {
        0 => raw(&mut c),
        1 => session(&mut c, false),
        _ => session(&mut c, true),
    }
});
