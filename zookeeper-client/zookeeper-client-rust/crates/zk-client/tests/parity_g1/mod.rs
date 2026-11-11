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

//! Shared infrastructure and support utilities for legacy C client parity test suites.
//!
//! Provides lifecycle management primitives for external ZooKeeper instances, facilitating
//! automated restarts, quorum initialization, mutual TLS server configuration, and
//! deterministic event verification corresponding to `TestClient.cc`, `TestClientRetry.cc`,
//! and `TestReadOnlyClient.cc`.

#![allow(
    dead_code,
    unreachable_pub,
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::print_stderr
)]

use std::future::Future;
use std::path::{Path, PathBuf};
use std::time::Duration;

use apache_zookeeper::{
    Client, ClientBuilder, EventKind, OneshotWatcher, SessionState, WatchedEvent,
};

use crate::common::{self, RealServer, Rt, wait_state};

/// Session timeout configured for client handles expected to survive server restart intervals.
pub const RESTART_SESSION: Duration = Duration::from_secs(10);

/// Maximum duration permitted for clients to reestablish connectivity following a server restart.
pub const RECONNECT_WAIT: Duration = Duration::from_secs(20);

/// Observation window employed to assert watcher quiescence (absence of spurious event delivery).
pub const QUIET: Duration = Duration::from_millis(300);

/// Constructs a [`ClientBuilder`] configured with the specified runtime and restart resilient
/// session timeout.
pub fn restart_builder(rt: Rt, cs: &str) -> ClientBuilder {
    rt.apply(common::builder(cs))
        .session_timeout(RESTART_SESSION)
}

/// Executes a future bounded by an explicit timeout duration, panicking upon timeout expiration.
pub async fn within_for<F: Future>(d: Duration, f: F) -> F::Output {
    tokio::time::timeout(d, f).await.expect("timed out")
}

/// Asserts that a one-shot watcher remains pending and delivers no event during the quiescence
/// window.
pub async fn is_pending(w: &mut OneshotWatcher) -> bool {
    tokio::time::timeout(QUIET, w).await.is_err()
}

/// Awaits resolution of a one-shot watcher, asserting that the emitted event matches expected kind
/// and path.
pub async fn expect_event(w: OneshotWatcher, kind: EventKind, path: &str) -> WatchedEvent {
    let ev = common::within(w)
        .await
        .unwrap_or_else(|e| panic!("{path}: watch error {e:?}"));
    assert_eq!(ev.kind, kind, "{path}: {ev:?}");
    assert_eq!(ev.path, path, "{ev:?}");
    ev
}

/// Orchestrates graceful server restart, verifying disconnection across provided clients before
/// awaiting reconnection.
pub async fn restart(server: &RealServer, clients: &[&Client]) {
    server.stop_now();
    for c in clients {
        let mut s = c.state_watcher();
        wait_state(&mut s, common::WAIT, |i| {
            i.state == SessionState::Connecting
        })
        .await;
    }
    server.start_again().unwrap();
    for c in clients {
        let mut s = c.state_watcher();
        wait_state(&mut s, RECONNECT_WAIT, |i| i.state.is_connected()).await;
    }
}

/// Initializes an isolated temporary quorum node directory populated with a standard `data/myid`
/// identifier.
pub fn quorum_dir(name: &str, port: u16) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("zk-rust-test-{name}-{port}"));
    // Clean prior execution artifacts if present.
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("data")).unwrap();
    std::fs::write(dir.join("data").join("myid"), "1\n").unwrap();
    dir
}

/// Attempts to launch a real server instance, returning `None` to skip execution if prerequisites
/// are unmet.
pub fn start_or_skip(
    port: u16,
    dir: Option<PathBuf>,
    props: &str,
    jvm_opts: &str,
) -> Option<RealServer> {
    match RealServer::start_with(port, dir, props, jvm_opts) {
        Ok(s) => Some(s),
        Err(e) => common::skip("no real ZooKeeper server", &e),
    }
}

/// Spawns an isolated real server on an empty data tree configured with custom JVM and
/// configuration properties.
pub fn fresh_server_with(offset: u16, props: &str, jvm_opts: &str) -> Option<RealServer> {
    let port = common::real_port(offset)?;
    // Reset data directory prior to launch.
    let _ = std::fs::remove_dir_all(common::default_server_dir(port).join("data"));
    start_or_skip(port, None, props, jvm_opts)
}

/// Canonical passphrase decrypting test fixture private keys.
pub const TLS_PASS: &str = "zk-test-pass";

/// Resolves the absolute path to a TLS test certificate fixture.
pub fn tls_fixture(name: &str) -> PathBuf {
    zk_testkit::fixtures::tls_dir().join(name)
}

/// Generates Netty TLS server configuration properties with PEM key/trust store locations and
/// client authentication policy.
pub fn tls_props(dir: &Path, secure: u16, client_auth: &str) -> String {
    std::fs::create_dir_all(dir).unwrap();
    // Concatenate server private key and leaf certificate into unified PEM bundle.
    let ks = dir.join("server-keystore.pem");
    let mut pem = std::fs::read(tls_fixture("server.key")).unwrap();
    pem.extend(std::fs::read(tls_fixture("server.pem")).unwrap());
    std::fs::write(&ks, pem).unwrap();
    format!(
        "secureClientPort={secure}\n\
         serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory\n\
         authProvider.x509=org.apache.zookeeper.server.auth.X509AuthenticationProvider\n\
         ssl.keyStore.location={}\n\
         ssl.keyStore.type=PEM\n\
         ssl.trustStore.location={}\n\
         ssl.trustStore.type=PEM\n\
         ssl.clientAuth={client_auth}\n",
        ks.display(),
        tls_fixture("ca.pem").display()
    )
}

/// Formats a legacy C client `zookeeper_init_ssl` certificate string formatted as
/// `ca,cert,key,passphrase`.
pub fn legacy_cert_string() -> String {
    format!(
        "{},{},{},{TLS_PASS}",
        tls_fixture("ca.pem").display(),
        tls_fixture("client.pem").display(),
        tls_fixture("keys/p8-sha256-aes256.pem").display()
    )
}
