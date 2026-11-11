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

//! Integration tests for the TLS listener (`tls` feature) using a synchronous `rustls` client
//! and test certificates from `zk_testkit::fixtures::tls_dir`.

#![cfg(feature = "tls")]
#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use zk_fakeserver::{ClusterConfig, FakeServer, PeerEnd, ServerOptions, TlsAcceptor};

const WAIT: Duration = Duration::from_secs(5);

fn fixture(name: &str) -> PathBuf {
    zk_testkit::fixtures::tls_dir().join(name)
}

fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

fn certs(name: &str) -> Vec<CertificateDer<'static>> {
    CertificateDer::pem_file_iter(fixture(name))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap()
}

fn acceptor(client_auth: bool) -> Arc<TlsAcceptor> {
    let b = TlsAcceptor::builder(provider())
        .cert_chain_pem_file(fixture("server.pem"))
        .unwrap()
        .key_pem_file(fixture("server.key"))
        .unwrap();
    let b = if client_auth {
        b.client_auth_ca_pem_file(fixture("ca.pem")).unwrap()
    } else {
        b
    };
    b.build().unwrap()
}

fn server(acc: &Arc<TlsAcceptor>) -> FakeServer {
    let opts = ServerOptions::default().with_acceptor(Arc::clone(acc) as _);
    FakeServer::start_with(ClusterConfig::default(), opts).unwrap()
}

/// Connects a `rustls` client stream to `server`, presenting `client.pem` when `identity` is set.
fn client(server: &FakeServer, identity: bool) -> StreamOwned<ClientConnection, TcpStream> {
    let mut roots = RootCertStore::empty();
    for c in certs("ca.pem") {
        roots.add(c).unwrap();
    }
    let b = ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_root_certificates(roots);
    let cfg = if identity {
        let key = PrivateKeyDer::from_pem_file(fixture("client.key")).unwrap();
        b.with_client_auth_cert(certs("client.pem"), key).unwrap()
    } else {
        b.with_no_client_auth()
    };
    let name = ServerName::try_from("127.0.0.1").unwrap();
    let conn = ClientConnection::new(Arc::new(cfg), name).unwrap();
    let tcp = TcpStream::connect(server.addr()).unwrap();
    tcp.set_read_timeout(Some(WAIT)).unwrap();
    StreamOwned::new(conn, tcp)
}

#[test]
fn four_letter_word_over_tls_ends_with_close_notify() {
    let acc = acceptor(false);
    let server = server(&acc);
    let mut s = client(&server, false);
    s.write_all(b"isro").unwrap();
    let mut answer = Vec::new();
    // `StreamOwned::read_to_end` requires a clean server `close_notify` alert to return `Ok`.
    s.read_to_end(&mut answer).unwrap();
    assert_eq!(answer, b"rw");
    assert!(acc.wait_sessions(WAIT, |v| v.first().is_some_and(|x| x.sent_close_notify)));
    let rec = &acc.sessions()[0];
    assert_eq!(rec.handshake, Some(Ok(())));
    assert!(!rec.client_cert);
}

#[test]
fn client_close_notify_is_recorded() {
    let acc = acceptor(false);
    let server = server(&acc);
    let mut s = client(&server, false);
    // Initiate the TLS handshake via the first write flush.
    s.write_all(b"").unwrap();
    s.flush().unwrap();
    while s.conn.is_handshaking() {
        s.conn.complete_io(&mut s.sock).unwrap();
    }
    s.conn.send_close_notify();
    s.flush().unwrap();
    s.conn.complete_io(&mut s.sock).unwrap();
    assert!(acc.wait_sessions(WAIT, |v| v.first().is_some_and(|x| x.peer_end.is_some())));
    assert_eq!(acc.sessions()[0].peer_end, Some(PeerEnd::CloseNotify));
}

#[test]
fn tcp_end_without_close_notify_is_recorded_as_eof() {
    let acc = acceptor(false);
    let server = server(&acc);
    let mut s = client(&server, false);
    while s.conn.is_handshaking() {
        s.conn.complete_io(&mut s.sock).unwrap();
    }
    drop(s);
    assert!(acc.wait_sessions(WAIT, |v| v.first().is_some_and(|x| x.peer_end.is_some())));
    assert_eq!(acc.sessions()[0].peer_end, Some(PeerEnd::Eof));
}

#[test]
fn client_auth_records_the_client_certificate() {
    let acc = acceptor(true);
    let server = server(&acc);
    let mut s = client(&server, true);
    s.write_all(b"isro").unwrap();
    let mut answer = Vec::new();
    s.read_to_end(&mut answer).unwrap();
    assert_eq!(answer, b"rw");
    let rec = &acc.sessions()[0];
    assert_eq!(rec.handshake, Some(Ok(())));
    assert!(rec.client_cert);
}

#[test]
fn client_auth_rejects_a_client_with_no_certificate() {
    let acc = acceptor(true);
    let server = server(&acc);
    let mut s = client(&server, false);
    let mut answer = Vec::new();
    let _ = s.write_all(b"isro");
    assert!(s.read_to_end(&mut answer).is_err());
    assert!(acc.wait_sessions(WAIT, |v| {
        v.first()
            .is_some_and(|x| matches!(x.handshake, Some(Err(_))))
    }));
    // Await transport closure after the connection worker records the handshake failure.
    assert!(
        server
            .cluster()
            .wait_until(WAIT, |_| server.connections().is_empty())
    );
}

#[test]
fn builder_needs_a_chain_and_a_key() {
    let no_key = TlsAcceptor::builder(provider())
        .cert_chain_pem_file(fixture("server.pem"))
        .unwrap()
        .build();
    assert!(no_key.is_err());
    let no_chain = TlsAcceptor::builder(provider())
        .key_pem_file(fixture("server.key"))
        .unwrap()
        .build();
    assert!(no_chain.is_err());
    assert!(
        TlsAcceptor::builder(provider())
            .cert_chain_pem_file(fixture("missing.pem"))
            .is_err()
    );
}
