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

//! Evaluates native SASL authentication protocol flows against DIGEST-MD5 mock server endpoints.
//!
//! Validates authentication handshake state machines across supported runtime drivers, confirming
//! that authentication rejections trigger terminal `SessionState::AuthFailed` transitions, populate
//! `SessionInfo::last_error` with structured `SaslError` context, and halt ensemble failover.

#![cfg(feature = "sasl")]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::Arc;
use std::time::Duration;

use apache_zookeeper::{
    Client, CredentialError, CredentialSource, Error, SaslConfig, SaslContext, SaslError,
    SaslErrorKind, SaslMechanism, SaslMechanismFactory, SaslStep, SecretString, SessionInfo,
    SessionState,
};
use common::{Rt, all_rts, within};
use zk_fakeserver::{FakeServer, FrameKind};
use zk_jute::opcode;

/// Default SASL authentication username recognized by test fixtures.
const USER: &str = "bob";
/// Matching secret credential for [`USER`].
const PASSWORD: &str = "hunter2";

/// Static credential provider yielding preconfigured secret strings or transient/permanent errors.
struct Password(Result<&'static str, CredentialError>);

impl CredentialSource for Password {
    fn password(&self) -> Result<SecretString, CredentialError> {
        self.0.map(SecretString::from)
    }
}

/// Constructs DIGEST-MD5 client configuration with the designated credential outcome.
fn digest(password: Result<&'static str, CredentialError>) -> SaslConfig {
    SaslConfig::digest_md5(USER, Arc::new(Password(password)))
}

/// Initializes a fake server fixture configured with DIGEST-MD5 authentication credentials for
/// [`USER`].
fn sasl_server() -> FakeServer {
    let fs = common::fake();
    fs.set_sasl_digest(USER, PASSWORD);
    fs
}

/// Extracts the inner `SaslErrorKind` from session error state if present.
fn sasl_kind(info: &SessionInfo) -> Option<SaslErrorKind> {
    info.last_error
        .as_ref()
        .and_then(|e| e.downcast_ref::<SaslError>())
        .map(|e| e.kind.clone())
}

/// Connects with the provided SASL configuration and asserts immediate failure with
/// `Error::AuthFailed`, returning the root `SaslErrorKind`.
async fn refused(rt: Rt, cs: &str, cfg: SaslConfig) -> SaslErrorKind {
    let r = within(rt.apply(common::builder(cs)).sasl(cfg).connect()).await;
    let e = match r {
        Err(e) => e,
        Ok(_) => panic!("{rt:?}: the connect succeeds"),
    };
    assert_eq!(e.error(), &Error::AuthFailed, "{rt:?}: {e}");
    std::error::Error::source(&e)
        .and_then(|c| c.downcast_ref::<SaslError>())
        .map(|c| c.kind.clone())
        .unwrap_or_else(|| panic!("{rt:?}: no SaslError in {e:?}"))
}

/// Instantiates a nonblocking client and monitors state transitions until
/// `SessionState::AuthFailed`, returning the recorded `SaslErrorKind`.
async fn refused_state(rt: Rt, cs: &str, cfg: SaslConfig) -> SaslErrorKind {
    let c: Client = rt
        .apply(common::builder(cs))
        .sasl(cfg)
        .build()
        .unwrap_or_else(|e| panic!("{rt:?}: {e:?}"));
    let mut w = c.state_watcher();
    let info = common::wait_state(&mut w, common::WAIT, |i| {
        i.state == SessionState::AuthFailed
    })
    .await;
    assert_eq!(within(c.exists("/")).await, Err(Error::AuthFailed));
    sasl_kind(&info).unwrap_or_else(|| panic!("{rt:?}: no SaslError in {info:?}"))
}

/// Validates standard DIGEST-MD5 handshake completion: client transmits initial empty token,
/// responds to server challenge, and enters `Connected` state upon mutual validation.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn digest_md5_authenticates_with_the_fake_server() {
    for rt in all_rts() {
        let fs = sasl_server();
        let b = rt.apply(common::builder(&fs.connect_string()));
        let c = within(b.sasl(digest(Ok(PASSWORD))).connect())
            .await
            .unwrap_or_else(|e| panic!("{rt:?}: {e:?}"));
        assert!(within(c.exists("/")).await.unwrap().is_some());
        let sasl = fs
            .transcript()
            .into_iter()
            .filter(|r| r.kind == FrameKind::Request && r.op == Some(opcode::SASL))
            .count();
        assert_eq!(sasl, 2, "{rt:?}");
        let info = c.state();
        assert_eq!(info.state, SessionState::Connected, "{rt:?}");
        assert!(info.last_error.is_none(), "{rt:?}: {info:?}");
        within(c.close()).await.unwrap();
    }
}

/// Verifies that invalid credentials result in terminal rejection with
/// `SaslErrorKind::ServerRejected` and transition the session to `SessionState::AuthFailed`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_wrong_password_ends_in_auth_failed() {
    for rt in all_rts() {
        let fs = sasl_server();
        let kind = refused(rt, &fs.connect_string(), digest(Ok("wrong"))).await;
        assert_eq!(kind, SaslErrorKind::ServerRejected, "{rt:?}");
        let fs = sasl_server();
        let kind = refused_state(rt, &fs.connect_string(), digest(Ok("wrong"))).await;
        assert_eq!(kind, SaslErrorKind::ServerRejected, "{rt:?}");
    }
}

/// Verifies that credential retrieval errors (`Transient` or `Permanent`) propagate directly into
/// `last_error` as `SaslErrorKind::Credential`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_credential_error_keeps_its_kind() {
    for rt in all_rts() {
        for e in [CredentialError::Transient, CredentialError::Permanent] {
            let fs = sasl_server();
            let kind = refused(rt, &fs.connect_string(), digest(Err(e))).await;
            assert_eq!(kind, SaslErrorKind::Credential(e), "{rt:?}");
        }
    }
}

/// Synthetic SASL mechanism factory configured to test panic handling and error returns.
struct Faulty {
    panic: bool,
}

impl SaslMechanismFactory for Faulty {
    fn create(&self, _ctx: &SaslContext) -> Result<Box<dyn SaslMechanism>, SaslError> {
        Ok(Box::new(FaultyMech { panic: self.panic }))
    }
}

struct FaultyMech {
    panic: bool,
}

impl SaslMechanism for FaultyMech {
    fn name(&self) -> &str {
        "DIGEST-MD5"
    }

    fn start(&mut self) -> Result<SaslStep, SaslError> {
        Ok(SaslStep {
            token: None,
            done: false,
        })
    }

    fn step(&mut self, _challenge: Option<&[u8]>) -> Result<SaslStep, SaslError> {
        assert!(!self.panic, "the mechanism panics");
        Err(SaslError::new(SaslErrorKind::Mechanism, "no answer"))
    }
}

/// Verifies that unwinding panics within user-supplied mechanism callbacks are caught at the
/// driver boundary, converted to `SaslErrorKind::Mechanism`, and terminate the session safely.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_mechanism_panic_is_a_mechanism_error() {
    for rt in all_rts() {
        for panic in [true, false] {
            let fs = sasl_server();
            let cfg = SaslConfig::Custom(Arc::new(Faulty { panic }));
            let kind = refused(rt, &fs.connect_string(), cfg).await;
            assert_eq!(kind, SaslErrorKind::Mechanism, "{rt:?} panic={panic}");
        }
    }
}

/// Custom mechanism factory capturing hostname parameters passed via `SaslContext`.
struct HostSeen(std::sync::Mutex<Vec<String>>);

impl SaslMechanismFactory for HostSeen {
    fn create(&self, ctx: &SaslContext) -> Result<Box<dyn SaslMechanism>, SaslError> {
        self.0.lock().unwrap().push(ctx.server_host.clone());
        Ok(Box::new(FaultyMech { panic: false }))
    }
}

/// Asserts that `SaslContext::server_host` receives the logical host name specified in the
/// connection string rather than the resolved socket IP address.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn the_mechanism_gets_the_connect_string_host() {
    // Aggregates execution results across all runtimes prior to asserting uniform behavior.
    let mut seen_by_rt = Vec::new();
    for rt in all_rts() {
        let fs = sasl_server();
        let seen = Arc::new(HostSeen(std::sync::Mutex::new(Vec::new())));
        let factory: Arc<dyn SaslMechanismFactory> = seen.clone();
        let cs = format!("localhost:{}", fs.port());
        let kind = refused(rt, &cs, SaslConfig::Custom(factory)).await;
        let hosts = seen.0.lock().unwrap().clone();
        seen_by_rt.push((rt, kind, hosts));
    }
    let bad: Vec<_> = seen_by_rt
        .iter()
        .filter(|(_, kind, hosts)| {
            *kind != SaslErrorKind::Mechanism
                || hosts.is_empty()
                || hosts.iter().any(|h| h != "localhost")
        })
        .collect();
    assert!(bad.is_empty(), "{bad:?}");
}

/// Confirms that SASL authentication failures are terminal to the session attempt and do not fail
/// over to remaining ensemble hosts.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_sasl_failure_does_not_try_the_next_server() {
    for rt in all_rts() {
        let first = sasl_server();
        let second = sasl_server();
        let cs = format!("{},{}", first.connect_string(), second.connect_string());
        let kind = refused(rt, &cs, digest(Ok("wrong"))).await;
        assert_eq!(kind, SaslErrorKind::ServerRejected, "{rt:?}");
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert_eq!(first.accepted() + second.accepted(), 1, "{rt:?}");
    }
}
