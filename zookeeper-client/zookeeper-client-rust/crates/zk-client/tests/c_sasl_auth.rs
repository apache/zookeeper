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

//! C client compatibility tests mirroring `TestSASLAuth.cc` (`Zookeeper_SASLAuth`).
//!
//! Validates SASL authentication semantics against ZooKeeper server fixtures configured with
//! mandatory client SASL requirements (`sessionRequireClientSASLAuth`). Server configurations
//! provide a JAAS `Server` authentication stanza accepting identity `myuser` with credentials
//! `mypassword`. Tests provision server instances via `dev/test-server.sh` and negotiate native
//! DIGEST-MD5 authentication under service identity `zookeeper` and server realm `zk-sasl-md5`.
//! Scenarios evaluate behavior across both background thread and Tokio runtime drivers.
//!
//! Test fixtures utilize port offset 1 of `ZK_TEST_PORT_BASE` for single-server tests and
//! offsets 3 through 8 for read-only partition scenarios. Tests execute sequentially under
//! mutual exclusion to avoid port conflicts.
//! Execute via:
//! `ZK_TEST_PORT_BASE=<base> cargo test --features sasl --test c_sasl_auth -- --ignored`.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects,
    clippy::print_stderr,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::path::{Path, PathBuf};
use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{ClientBuilder, CreateMode, Error};
use common::{RealServer, Rt, real_port};

/// Serializes SASL test scenarios sharing the assigned server port allocations.
static PORTS: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Base port offset allocated for the SASL server endpoint.
const OFFSET: u16 = 1;

/// Execution budget for connection negotiation and failover reconnection.
const SLOW: Duration = Duration::from_secs(20);

/// Negotiated session timeout aligned with C test suite configuration.
const SESSION_TIMEOUT: Duration = Duration::from_secs(10);

/// server side JAAS configuration specification defining valid DIGEST-MD5 login credentials.
const JAAS: &str = "Server {\n  org.apache.zookeeper.server.auth.DigestLoginModule required\n    user_myuser=\"mypassword\";\n};\n";

/// Generates test entry points across all available runtime drivers under serialized port locking.
macro_rules! sasl_test {
    ($name:ident) => {
        mod $name {
            use super::*;

            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn thread() {
                let _lock = PORTS.lock().await;
                super::$name(Rt::Thread).await;
            }

            #[cfg(all(unix, feature = "tokio"))]
            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn tokio() {
                let _lock = PORTS.lock().await;
                super::$name(Rt::Tokio).await;
            }
        }
    };
}

/// Initializes an isolated server data directory with node ID initialized to 1.
fn server_dir(port: u16) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("zk-rust-c-sasl-{port}"));
    // On a first run the directory does not exist.
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("data")).unwrap();
    std::fs::write(dir.join("data").join("myid"), "1\n").unwrap();
    dir
}

/// Formulates JVM arguments matching C `zkServer.sh startRequireSASLAuth` expectations.
fn jvm_opts(jaas: Option<&Path>, read_only: bool) -> String {
    let mut opts = String::from(
        "-Dzookeeper.sessionRequireClientSASLAuth=true \
         -Dzookeeper.authProvider.1=org.apache.zookeeper.server.auth.SASLAuthenticationProvider \
         -Dzookeeper.fips-mode=false",
    );
    if let Some(j) = jaas {
        opts.push_str(&format!(
            " -Djava.security.auth.login.config={}",
            j.display()
        ));
    }
    if read_only {
        opts.push_str(" -Dreadonlymode.enabled=true");
    }
    opts
}

/// Launches a real ZooKeeper server configured for SASL authentication.
/// When `read_only` is true, configures an unformed three node cluster to force read-only mode on
/// node 1.
fn start_server(jaas: bool, read_only: bool) -> Option<RealServer> {
    let port = real_port(OFFSET)?;
    let dir = server_dir(port);
    let jaas_file = dir.join("jaas.conf");
    std::fs::write(&jaas_file, JAAS).unwrap();
    let props = if read_only {
        let mut p = String::from("localSessionsEnabled=true\ninitLimit=5\nsyncLimit=2\n");
        for n in 1..=3_u16 {
            let q = real_port(OFFSET + 2 * n)?;
            let e = real_port(OFFSET + 2 * n + 1)?;
            p.push_str(&format!("server.{n}=127.0.0.1:{q}:{e}\n"));
        }
        p
    } else {
        String::new()
    };
    let jvm = jvm_opts(jaas.then_some(jaas_file.as_path()), read_only);
    match RealServer::start_with(port, Some(dir), &props, &jvm) {
        Ok(s) => Some(s),
        Err(e) => common::skip("no real ZooKeeper server", &e),
    }
}

/// Constructs a `ClientBuilder` preconfigured with session timeout settings.
fn builder(rt: Rt, cs: &str) -> ClientBuilder {
    rt.apply(common::builder(cs).session_timeout(SESSION_TIMEOUT))
}

sasl_test!(server_require_client_sasl);
/// Validates C client parity (`Zookeeper_SASLAuth::testServerRequireClientSASL`). Confirms that
/// an ensemble demanding SASL allows connection establishment but rejects subsequent write
/// requests with `SessionClosedRequireSaslAuth`.
async fn server_require_client_sasl(rt: Rt) {
    let Some(server) = start_server(false, false) else {
        return;
    };
    let c = tokio::time::timeout(SLOW, builder(rt, &server.connect_string()).connect())
        .await
        .expect("timed out")
        .expect("connect");
    let r = common::within(c.create(
        "/serverRequireClientSASL",
        b"",
        CreateMode::Persistent,
        OPEN_ACL_UNSAFE,
    ))
    .await;
    assert_eq!(r.unwrap_err(), Error::SessionClosedRequireSaslAuth);
    let _ = common::within(c.close()).await;
}

#[cfg(feature = "sasl")]
use sasl::*;

/// The cases with client SASL. They need the `sasl` feature.
#[cfg(feature = "sasl")]
mod sasl {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use apache_zookeeper::{
        Client, CredentialError, CredentialSource, SaslConfig, SecretString, SessionState,
    };

    use super::*;

    /// Target SASL user identity mirroring C suite fixtures.
    pub(super) const USER: &str = "myuser";

    /// Cleartext password for [`USER`].
    pub(super) const PASSWORD: &str = "mypassword";

    /// Credential provider yielding fixed static credentials.
    pub(super) struct Fixed;

    impl CredentialSource for Fixed {
        fn password(&self) -> Result<SecretString, CredentialError> {
            Ok(SecretString::from(PASSWORD))
        }
    }

    /// Dynamic credential source simulating runtime decryption upon each authentication cycle.
    /// Applies a byte-offset rotation matching C callbacks `textPasswordCallback` and
    /// `binaryPasswordCallback`.
    pub(super) struct Decrypting {
        pub(super) file: PathBuf,
        pub(super) incr: u8,
        pub(super) calls: AtomicUsize,
    }

    impl CredentialSource for Decrypting {
        fn password(&self) -> Result<SecretString, CredentialError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            let content = std::fs::read(&self.file).map_err(|_| CredentialError::Transient)?;
            let plain: Vec<u8> = content.iter().map(|b| b.wrapping_add(self.incr)).collect();
            String::from_utf8(plain)
                .map(SecretString::from)
                .map_err(|_| CredentialError::Permanent)
        }
    }

    /// Constructs a DIGEST-MD5 client configuration bound to service `zookeeper` and realm
    /// `zk-sasl-md5`.
    pub(super) fn sasl_builder(rt: Rt, cs: &str, src: Arc<dyn CredentialSource>) -> ClientBuilder {
        builder(rt, cs).sasl(SaslConfig::digest_md5(USER, src))
    }

    /// Establishes an authenticated connection or aborts on timeout/failure.
    pub(super) async fn sasl_connect(b: ClientBuilder) -> Client {
        tokio::time::timeout(SLOW, b.connect())
            .await
            .expect("timed out")
            .unwrap_or_else(|e| panic!("connect: {e:?}"))
    }

    /// Helper executing C `testClientSASLHelper`. Connects with SASL, provisions a test node,
    /// restarts the server, awaits reconnect and automatic re-authentication, and verifies node
    /// visibility.
    pub(super) async fn sasl_helper(
        server: &RealServer,
        rt: Rt,
        cs: &str,
        path: &str,
        src: Arc<dyn CredentialSource>,
    ) {
        let c = sasl_connect(sasl_builder(rt, cs, src)).await;
        common::within(c.create(path, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
            .await
            .unwrap();

        let mut w = c.state_watcher();
        server.stop_now();
        common::wait_state(&mut w, SLOW, |i| !i.state.is_connected()).await;
        server.start_again().unwrap();
        let back = common::wait_state(&mut w, SLOW, |i| {
            i.state == SessionState::Connected && i.epoch >= 2
        })
        .await;
        assert_eq!(back.state, SessionState::Connected);
        // Under `sessionRequireClientSASLAuth`, operations succeed only following successful
        // reauthentication.
        assert!(common::within(c.exists(path)).await.unwrap().is_some());
        common::within(c.close()).await.unwrap();
    }

    /// Common harness for encrypted credential source test cases.
    pub(super) async fn encrypted(rt: Rt, content: &[u8], incr: u8, name: &str, path: &str) {
        let Some(server) = start_server(true, false) else {
            return;
        };
        // Incorporate port in filename to avoid inter-test collisions.
        let file = std::env::temp_dir().join(format!("{name}-{}-{}", rt.name(), server.port()));
        std::fs::write(&file, content).unwrap();
        let src = Arc::new(Decrypting {
            file: file.clone(),
            incr,
            calls: AtomicUsize::new(0),
        });
        let cs = server.connect_string();
        sasl_helper(&server, rt, &cs, path, src.clone()).await;
        // Verify that re-authentication caused subsequent credential re-read.
        assert!(src.calls.load(Ordering::SeqCst) >= 2);
        let _ = std::fs::remove_file(&file);
    }
}

#[cfg(feature = "sasl")]
sasl_test!(client_sasl);
/// Validates C client parity (`Zookeeper_SASLAuth::testClientSASL`). Establishes a DIGEST-MD5
/// authenticated session, performs writes, restarts the server, and confirms transparent
/// reconnection and reauthentication.
#[cfg(feature = "sasl")]
async fn client_sasl(rt: Rt) {
    let Some(server) = start_server(true, false) else {
        return;
    };
    let cs = server.connect_string();
    sasl_helper(&server, rt, &cs, "/clientSASL", std::sync::Arc::new(Fixed)).await;
}

#[cfg(feature = "sasl")]
sasl_test!(client_sasl_with_password_encrypted_text);
/// Validates C client parity (`Zookeeper_SASLAuth::testClientSASLWithPasswordEncryptedText`).
/// Authenticates using dynamically decrypted credentials sourced from an encoded text file across
/// server restart.
#[cfg(feature = "sasl")]
async fn client_sasl_with_password_encrypted_text(rt: Rt) {
    let content = [0x6C, 0x78, 0x6F, 0x60, 0x72, 0x72, 0x76, 0x6E, 0x71, 0x63];
    encrypted(
        rt,
        &content,
        1,
        "Zookeeper_SASLAuth.password.encrypted.text",
        "/clientSASLWithPasswordEncryptedText",
    )
    .await;
}

#[cfg(feature = "sasl")]
sasl_test!(client_sasl_with_password_encrypted_binary);
/// Validates C client parity (`Zookeeper_SASLAuth::testClientSASLWithPasswordEncryptedBinary`).
/// Authenticates using dynamically decrypted credentials sourced from an encoded binary file
/// across server restart.
#[cfg(feature = "sasl")]
async fn client_sasl_with_password_encrypted_binary(rt: Rt) {
    let content = [0x0C, 0x18, 0x0F, 0x00, 0x12, 0x12, 0x16, 0x0E, 0x11, 0x03];
    encrypted(
        rt,
        &content,
        b'a',
        "Zookeeper_SASLAuth.password.encrypted.binary",
        "/clientSASLWithPasswordEncryptedBinary",
    )
    .await;
}

#[cfg(feature = "sasl")]
sasl_test!(client_sasl_over_ipv6);
/// Validates C client parity (`Zookeeper_SASLAuth::testClientSASLOverIPv6`). Verifies SASL
/// authentication and transparent session recovery over IPv6 loopback endpoints.
#[cfg(feature = "sasl")]
async fn client_sasl_over_ipv6(rt: Rt) {
    let Some(server) = start_server(true, false) else {
        return;
    };
    let cs = format!("[::1]:{}", server.port());
    sasl_helper(
        &server,
        rt,
        &cs,
        "/clientSASLOverIPv6",
        std::sync::Arc::new(Fixed),
    )
    .await;
}

#[cfg(feature = "sasl")]
sasl_test!(client_sasl_read_only);
/// Validates C client parity (`Zookeeper_SASLAuth::testClientSASLReadOnly`). Verifies SASL
/// authentication against a read-only partition, confirming successful reads while mutating
/// operations return `NotReadOnly`.
#[cfg(feature = "sasl")]
async fn client_sasl_read_only(rt: Rt) {
    let Some(server) = start_server(true, true) else {
        return;
    };
    let cs = server.connect_string();
    let b = sasl_builder(rt, &cs, std::sync::Arc::new(Fixed)).allow_read_only(true);
    let c = sasl_connect(b).await;
    assert_eq!(
        c.state().state,
        apache_zookeeper::SessionState::ConnectedReadOnly
    );
    assert!(common::within(c.get_data("/")).await.is_ok());
    let r =
        common::within(c.create("/test", b"hello", CreateMode::Persistent, OPEN_ACL_UNSAFE)).await;
    assert_eq!(r.unwrap_err(), Error::NotReadOnly);
    common::within(c.close()).await.unwrap();
}

#[cfg(feature = "sasl")]
sasl_test!(client_sasl_packet_order);
/// Validates C client parity (`Zookeeper_SASLAuth::testClientSASLPacketOrder`). Confirms that
/// read requests dispatched concurrently with session initialization queue behind in-flight SASL
/// handshake exchanges and execute successfully.
#[cfg(feature = "sasl")]
async fn client_sasl_packet_order(rt: Rt) {
    let Some(server) = start_server(true, false) else {
        return;
    };
    let cs = server.connect_string();
    let c = sasl_builder(rt, &cs, std::sync::Arc::new(Fixed))
        .build()
        .unwrap();
    let r = tokio::time::timeout(SLOW, c.get_data("/"))
        .await
        .expect("timed out");
    assert!(r.is_ok(), "{r:?}");
    common::within(c.close()).await.unwrap();
}
