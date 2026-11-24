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

//! Verification of TLS transport security, certificate verification, and mutual authentication.
//!
//! Asserts server identity verification using WebPki and IP SAN extensions, mutual TLS client
//! authentication, error categorization across malformed or expired certificates, platform
//! certificate verifier isolation, handshake deadline enforcement, and environment-driven
//! trust store discovery (`SSL_CERT_FILE` / `SSL_CERT_DIR`).

#![cfg(feature = "tls-rustls")]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::io::{self, Write};
use std::net::{SocketAddr, TcpListener};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use apache_zookeeper::{
    Client, ClientBuilder, DisconnectCause, Error, SessionState, StateWatcher, TlsConfigError,
    TlsOptions,
};
use zk_fakeserver::{ClusterConfig, FakeServer, ServerOptions, TlsAcceptor};

use common::{ca_only, fixture, within};

/// Instantiates a TLS mock server configured with the specified certificate fixture and optional
/// client authentication.
fn tls_server(leaf: &str, client_auth: bool) -> (FakeServer, Arc<TlsAcceptor>) {
    let mut b = TlsAcceptor::builder(zk_driver::net::tls::default_provider())
        .cert_chain_pem_file(fixture(&format!("{leaf}.pem")))
        .unwrap()
        .key_pem_file(fixture(&format!("{leaf}.key")))
        .unwrap();
    if client_auth {
        b = b.client_auth_ca_pem_file(fixture("ca.pem")).unwrap();
    }
    let acc = b.build().unwrap();
    let opts = ServerOptions::default().with_acceptor(Arc::clone(&acc) as _);
    let server = FakeServer::start_with(ClusterConfig::default(), opts).unwrap();
    (server, acc)
}

/// Constructs a [`ClientBuilder`] configured with the target connection string and TLS security
/// options.
fn tls_builder(cs: &str, tls: TlsOptions) -> ClientBuilder {
    common::builder(cs).tls(tls)
}

/// Awaits predicate satisfaction against observable diagnostic errors, returning the formatted
/// error text.
async fn wait_error(
    w: &StateWatcher,
    mut pred: impl FnMut(&(dyn std::error::Error + Send + Sync + 'static)) -> bool,
) -> String {
    within(async {
        loop {
            if let Some(e) = w.last_error() {
                if pred(e.as_ref()) {
                    return e.to_string();
                }
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
}

/// Identifies whether the supplied error encapsulates a TLS transport layer I/O failure.
fn is_tls_io(e: &(dyn std::error::Error + Send + Sync + 'static)) -> bool {
    e.downcast_ref::<io::Error>()
        .is_some_and(|e| e.kind() == io::ErrorKind::InvalidData)
}

/// Asserts that default WebPki verification authenticates servers presenting valid CA chains and
/// IP SAN matching endpoints.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn webpki_accepts_a_good_server_by_its_ip_literal() {
    let (fs, acc) = tls_server("server", false);
    let c: Client = within(tls_builder(&fs.connect_string(), ca_only()).connect())
        .await
        .unwrap();
    assert!(within(c.exists("/")).await.unwrap().is_some());
    assert!(c.state().last_error.is_none());
    within(c.close()).await.unwrap();
    assert_eq!(
        acc.sessions().first().and_then(|s| s.handshake.clone()),
        Some(Ok(()))
    );
}

/// Asserts that hostname mismatch between server certificates and endpoint addresses halts
/// connection progression.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn webpki_rejects_another_name() {
    let (fs, _acc) = tls_server("other-name", false);
    let f = tls_builder(&fs.connect_string(), ca_only()).connect();
    let w = f.state_watcher();
    let mut f = Box::pin(f);
    wait_error(&w, is_tls_io).await;
    assert!(
        tokio::time::timeout(Duration::from_millis(200), &mut f)
            .await
            .is_err()
    );
    assert_eq!(w.current().state, SessionState::Connecting);
}

/// Asserts that certificate verification failures due to untrusted roots or expired leaf validity
/// trigger retry loops.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_wrong_ca_and_an_expired_leaf_fail_the_handshake() {
    for leaf in ["wrong-server", "expired"] {
        let (fs, acc) = tls_server(leaf, false);
        let f = tls_builder(&fs.connect_string(), ca_only()).connect();
        let w = f.state_watcher();
        wait_error(&w, is_tls_io).await;
        // Transport failure initiates automated reconnection retry sequence.
        let t0 = Instant::now();
        while acc.sessions().len() < 2 {
            assert!(t0.elapsed() < common::WAIT, "{leaf}: no second attempt");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(
            acc.sessions()
                .iter()
                .all(|s| !matches!(s.handshake, Some(Ok(())))),
            "{leaf}"
        );
        drop(f);
    }
}

/// Asserts that TLS handshake failures tear down socket connections and reject enqueued requests
/// with [`Error::ConnectionLoss`].
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_failed_handshake_fails_the_waiting_request() {
    let (fs, _acc) = tls_server("wrong-server", false);
    let c = tls_builder(&fs.connect_string(), ca_only())
        .build()
        .unwrap();
    let f = c.get_data("/");
    assert_eq!(within(f).await, Err(Error::ConnectionLoss));
    wait_error(&c.state_watcher(), is_tls_io).await;
    let _ = within(c.close()).await;
}

/// Validates mutual TLS authentication, ensuring unauthenticated clients are rejected and valid
/// certificates succeed.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn mutual_tls_needs_the_client_identity() {
    let (fs, acc) = tls_server("server", true);
    let f = tls_builder(&fs.connect_string(), ca_only()).connect();
    let w = f.state_watcher();
    let mut f = Box::pin(f);
    assert!(
        tokio::time::timeout(Duration::from_millis(500), &mut f)
            .await
            .is_err()
    );
    assert!(
        w.last_error().is_some(),
        "a refused handshake sets last_error"
    );
    drop(f);
    assert!(acc.sessions().iter().all(|s| !s.client_cert));

    let id = TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .client_identity(fixture("client.pem"), fixture("client.key"), None)
        .build()
        .unwrap();
    let c = within(tls_builder(&fs.connect_string(), id).connect())
        .await
        .unwrap();
    assert!(within(c.exists("/")).await.unwrap().is_some());
    within(c.close()).await.unwrap();
    assert!(acc.sessions().iter().any(|s| s.client_cert));
}

/// RAII guard managing temporary filesystem resources for certificate testing.
struct Temp(PathBuf);

impl Temp {
    fn path(tag: &str) -> PathBuf {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let n = NEXT.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!("zk-api-tls-{}-{tag}-{n}", std::process::id()))
    }

    fn file(body: &[u8]) -> Self {
        let p = Self::path("f");
        std::fs::write(&p, body).unwrap();
        Self(p)
    }

    fn dir() -> Self {
        let p = Self::path("d");
        std::fs::create_dir_all(&p).unwrap();
        Self(p)
    }
}

impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// Asserts that [`TlsOptionsBuilder::build`] validates certificate files and returns specific
/// [`TlsConfigError`] variants.
#[test]
fn build_reports_each_file_error() {
    let missing = fixture("no-such.pem");
    let garbage =
        Temp::file(b"-----BEGIN CERTIFICATE-----\nnot base64\n-----END CERTIFICATE-----\n");
    let empty = Temp::file(b"");

    for ca in [&missing, &garbage.0, &empty.0] {
        let e = TlsOptions::builder().ca_file(ca).build().unwrap_err();
        assert!(
            matches!(&e, TlsConfigError::CaFile(p) if p == ca),
            "{ca:?}: {e:?}"
        );
        assert!(e.to_string().contains(&*ca.to_string_lossy()), "{e}");
    }

    let e = TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .client_identity(&missing, fixture("client.key"), None)
        .build()
        .unwrap_err();
    assert!(
        matches!(&e, TlsConfigError::CertChain(p) if *p == missing),
        "{e:?}"
    );

    let e = TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .client_identity(fixture("client.pem"), &missing, None)
        .build()
        .unwrap_err();
    assert!(
        matches!(&e, TlsConfigError::PrivateKey(p) if *p == missing),
        "{e:?}"
    );

    // Key mismatch against the certificate chain is rejected during client credential validation.
    let other = fixture("server.key");
    let e = TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .client_identity(fixture("client.pem"), &other, None)
        .build()
        .unwrap_err();
    assert!(
        matches!(&e, TlsConfigError::PrivateKey(p) if *p == other),
        "{e:?}"
    );

    // Empty certificate files fail parsing and yield CertChain configuration errors.
    let e = TlsOptions::builder()
        .ca_file(fixture("ca.pem"))
        .client_identity(&empty.0, fixture("client.key"), None)
        .build()
        .unwrap_err();
    assert!(matches!(&e, TlsConfigError::CertChain(_)), "{e:?}");
}

/// Asserts that platform certificate verification is mutually exclusive with explicit CA and
/// validation override settings.
#[cfg(feature = "tls-platform-verifier")]
#[test]
fn the_platform_verifier_refuses_another_trust_source() {
    let builders = [
        TlsOptions::builder().ca_file(fixture("ca.pem")),
        TlsOptions::builder().add_system_roots(),
        TlsOptions::builder().danger_disable_hostname_verification(),
    ];
    for b in builders {
        let e = b.with_platform_verifier().build().unwrap_err();
        assert!(matches!(e, TlsConfigError::PlatformVerifier(_)), "{e:?}");
    }
}

/// Asserts that platform verifier enforcement delegates trust decisions strictly to operating
/// system certificate stores.
#[cfg(feature = "tls-platform-verifier")]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_platform_verifier_does_not_trust_the_test_ca() {
    let tls = match TlsOptions::builder().with_platform_verifier().build() {
        Ok(tls) => tls,
        Err(TlsConfigError::PlatformVerifier(text)) => {
            assert!(!text.is_empty());
            return;
        }
        Err(e) => panic!("{e:?}"),
    };
    let (fs, acc) = tls_server("server", false);
    let f = tls_builder(&fs.connect_string(), tls).connect();
    let w = f.state_watcher();
    wait_error(&w, is_tls_io).await;
    drop(f);
    assert!(
        acc.sessions()
            .iter()
            .all(|s| !matches!(s.handshake, Some(Ok(()))))
    );
}

/// Mock TCP endpoint simulating an unresponsive TLS handshake by throttling inbound packet bytes.
struct Dribbler {
    addr: SocketAddr,
    stop: Arc<AtomicBool>,
}

impl Dribbler {
    fn start(silent: bool) -> Self {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        l.set_nonblocking(true).unwrap();
        let addr = l.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let s = Arc::clone(&stop);
        std::thread::spawn(move || {
            let mut conns = Vec::new();
            while !s.load(Ordering::SeqCst) {
                if let Ok((c, _)) = l.accept() {
                    let _ = c.set_nonblocking(false);
                    conns.push(c);
                    if !silent {
                        if let Some(c) = conns.last_mut() {
                            let _ = c.write_all(&[0x16, 0x03, 0x03, 0x40, 0x00]);
                        }
                    }
                }
                if !silent {
                    for c in &mut conns {
                        let _ = c.write_all(&[0]);
                    }
                }
                std::thread::sleep(Duration::from_millis(100));
            }
        });
        Self { addr, stop }
    }
}

impl Drop for Dribbler {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

/// Asserts that stalled TLS handshakes enforce receive deadlines bounded at two-thirds of the
/// session timeout.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stuck_handshake_ends_at_two_thirds_of_the_timeout() {
    for silent in [false, true] {
        let d = Dribbler::start(silent);
        let c = common::builder(&d.addr.to_string())
            .session_timeout(Duration::from_millis(1_500))
            .tls(ca_only())
            .build()
            .unwrap();
        let w = c.state_watcher();
        let t0 = Instant::now();
        let text = wait_error(&w, |e| e.downcast_ref::<DisconnectCause>().is_some()).await;
        let took = t0.elapsed();
        let cause = w
            .last_error()
            .and_then(|e| e.downcast_ref::<DisconnectCause>().copied());
        assert!(
            matches!(
                cause,
                Some(DisconnectCause::ReceiveTimeout | DisconnectCause::TlsHandshakeTimeout)
            ),
            "silent {silent}: {text}"
        );
        assert!(
            took >= Duration::from_millis(900) && took < Duration::from_millis(3_000),
            "silent {silent}: the deadline came after {took:?}"
        );
        let _ = within(c.close()).await;
    }
}

/// Environment variable discriminator signaling child process isolation mode.
const CHILD: &str = "ZK_API_TLS_ENV_CHILD";

/// Spawns an isolated child test process configured with custom environment variables to test
/// environment ingestion safely.
fn run_child(name: &str, env: &[(&str, &std::ffi::OsStr)]) -> bool {
    let exe = std::env::current_exe().unwrap();
    let mut cmd = std::process::Command::new(exe);
    cmd.args(["--exact", name, "--test-threads=1", "--nocapture"])
        .env(CHILD, "1")
        .env_remove("SSL_CERT_FILE")
        .env_remove("SSL_CERT_DIR");
    for (k, v) in env {
        cmd.env(k, v);
    }
    let out = cmd.output().unwrap();
    let text = String::from_utf8_lossy(&out.stdout);
    out.status.success() && text.contains("1 passed")
}

/// Validates ingestion of system trust anchors from `SSL_CERT_FILE` and `SSL_CERT_DIR` environment
/// variables.
#[test]
fn ssl_cert_file_feeds_the_system_roots() {
    if std::env::var_os(CHILD).is_some() {
        let tls = TlsOptions::builder().add_system_roots().build().unwrap();
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async {
            let (fs, _acc) = tls_server("server", false);
            let c = within(tls_builder(&fs.connect_string(), tls).connect())
                .await
                .unwrap();
            within(c.close()).await.unwrap();
        });
        return;
    }
    let empty_dir = Temp::dir();
    assert!(run_child(
        "ssl_cert_file_feeds_the_system_roots",
        &[
            ("SSL_CERT_FILE", fixture("ca.pem").as_os_str()),
            ("SSL_CERT_DIR", empty_dir.0.as_os_str()),
        ],
    ));
}

/// Asserts that enabling system roots yields [`TlsConfigError::DefaultRoots`] when trust stores
/// are missing or empty.
#[test]
fn empty_default_locations_give_default_roots() {
    if std::env::var_os(CHILD).is_some() {
        let e = TlsOptions::builder()
            .add_system_roots()
            .build()
            .unwrap_err();
        assert!(matches!(e, TlsConfigError::DefaultRoots), "{e:?}");
        // Explicit CA configurations combined with system roots fail if system root stores cannot
        // be resolved.
        let e = TlsOptions::builder()
            .ca_file(fixture("ca.pem"))
            .add_system_roots()
            .build()
            .unwrap_err();
        assert!(matches!(e, TlsConfigError::DefaultRoots), "{e:?}");
        return;
    }
    let empty = Temp::file(b"");
    let empty_dir = Temp::dir();
    assert!(run_child(
        "empty_default_locations_give_default_roots",
        &[
            ("SSL_CERT_FILE", empty.0.as_os_str()),
            ("SSL_CERT_DIR", empty_dir.0.as_os_str()),
        ],
    ));
}
