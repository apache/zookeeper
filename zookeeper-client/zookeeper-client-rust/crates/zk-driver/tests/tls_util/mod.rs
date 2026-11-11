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

//! In-memory TLS protocol simulation harness and test fixture utilities.
//!
//! Provides utilities for testing TLS connections in memory:
//! - Certificate and private key test fixture loaders.
//! - In-memory mock server sessions backed by Rustls.
//! - Flight exchange engine shuttling handshakes and records across client and server endpoints.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::large_stack_frames,
    dead_code,
    unreachable_pub
)]

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

use zk_driver::net::tls::RustlsConn;
use zk_driver::net::tls::connector::default_provider;
use zk_driver::net::tls::verify::ChainOnlyVerifier;
use zk_driver::net::tls::{TlsConn, TlsFailure};

/// Protocol version constraint restricting handshakes to TLS 1.3.
pub const TLS13: &[&SupportedProtocolVersion] = &[&rustls::version::TLS13];
/// Protocol version constraint restricting handshakes to TLS 1.2.
pub const TLS12: &[&SupportedProtocolVersion] = &[&rustls::version::TLS12];

pub fn fixture(name: &str) -> PathBuf {
    zk_testkit::fixtures::tls_dir().join(name)
}

pub fn provider() -> Arc<CryptoProvider> {
    default_provider()
}

pub fn certs(name: &str) -> Vec<CertificateDer<'static>> {
    CertificateDer::pem_file_iter(fixture(name))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap()
}

pub fn key(name: &str) -> PrivateKeyDer<'static> {
    PrivateKeyDer::from_pem_file(fixture(name)).unwrap()
}

pub fn roots(name: &str) -> Arc<RootCertStore> {
    let mut roots = RootCertStore::empty();
    for c in certs(name) {
        roots.add(c).unwrap();
    }
    Arc::new(roots)
}

/// Constructs an in-memory Rustls server connection configured with default server certificates
/// and no client authentication.
pub fn server(versions: &[&'static SupportedProtocolVersion]) -> Box<ServerConnection> {
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

/// Constructs an in-memory client connection with custom certificate verification under loopback
/// IP addressing.
pub fn client_with(verifier: Arc<dyn ServerCertVerifier>) -> Box<RustlsConn> {
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

/// Constructs a client connection configured with chain-only certificate validation trusting the
/// specified CA fixture.
pub fn client(ca: &str) -> Box<RustlsConn> {
    client_with(Arc::new(ChainOnlyVerifier::new(roots(ca), provider())))
}

/// Drains all pending ciphertext emitted by the server connection.
pub fn server_out(s: &mut ServerConnection) -> Vec<u8> {
    let mut out = Vec::new();
    while s.wants_write() {
        s.write_tls(&mut out).unwrap();
    }
    out
}

/// Ingests ciphertext into the server session and extracts decrypted application plaintext.
pub fn server_in(s: &mut ServerConnection, mut ct: &[u8]) -> Vec<u8> {
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

/// Reads all available application plaintext buffered by the server connection.
pub fn server_read(s: &mut ServerConnection) -> Vec<u8> {
    let mut out = Vec::new();
    let mut buf = vec![0u8; 4096];
    loop {
        match s.reader().read(&mut buf) {
            Ok(0) | Err(_) => return out,
            Ok(n) => out.extend_from_slice(&buf[..n]),
        }
    }
}

/// Exchanges handshake and record flights between client and server sessions until both endpoints
/// stabilize.
pub fn exchange(
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

/// Executes a complete mutual handshake in memory, asserting successful establishment across both
/// endpoints.
pub fn connected(
    versions: &[&'static SupportedProtocolVersion],
) -> (Box<RustlsConn>, Box<ServerConnection>) {
    let mut c = client("ca.pem");
    let mut s = server(versions);
    exchange(c.as_mut(), &mut s, false).unwrap();
    assert!(!c.is_handshaking());
    assert!(!s.is_handshaking());
    (c, s)
}
