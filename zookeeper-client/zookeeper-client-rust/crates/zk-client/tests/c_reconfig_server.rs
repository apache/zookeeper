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

//! C client compatibility tests mirroring `TestReconfigServer.cc`.
//!
//! Validates dynamic ensemble reconfiguration semantics against active multi-node ZooKeeper
//! quorums. Scenarios provision three-node quorums using `dev/test-ensemble.sh` with
//! `reconfigEnabled=true` and administrative credentials (`super:test`), executing both
//! incremental and non-incremental membership changes via `Client::reconfig` and inspecting
//! configuration state via `Client::get_config`. Each test scenario executes across both
//! standard worker thread and Tokio runtime drivers on freshly provisioned quorums.
//!
//! Port allocations span offsets 8 through 16 relative to `ZK_TEST_PORT_BASE`. Tests execute
//! sequentially under mutual exclusion to prevent port collisions.
//! Execute via:
//! `ZK_TEST_PORT_BASE=<base> cargo test --test c_reconfig_server -- --ignored`.

// A scenario is one long async body. It runs on a 2 MiB test or worker
// thread, so the stack frame limit of the library does not apply.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::print_stderr,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::collections::BTreeSet;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::Duration;

use apache_zookeeper::{
    Client, ClientBuilder, EnsembleConfig, Error, Id, Reconfig, ServerOrder, Stat,
};
use common::{Rt, real_port};

/// Serializes ensemble reconfiguration tests sharing the dedicated port range.
static PORTS: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Base port offset allocated for server 1 in the multi-node test ensemble.
const OFFSET: u16 = 8;

/// Cardinality of the active ensemble quorum (mirrors C test constant `NUM_SERVERS`).
const NUM_SERVERS: usize = 3;

/// Operation timeout budget for multi-node consensus transitions and cluster failover.
const SLOW: Duration = Duration::from_secs(20);

/// Negotiated session timeout aligned with C test suite parameters.
const SESSION_TIMEOUT: Duration = Duration::from_secs(10);

/// SHA-1 digest representation for administrative identity `super:test`.
const SUPER_DIGEST: &str = "super:D/InIHSb7yEEbrWz8b9l71RjZJU=";

/// Initial configuration version corresponding to the epoch zxid of the initial leader
/// election (1 << 32).
const FIRST_VERSION: i64 = 0x1_0000_0000;

/// Subsequent configuration version following an initial dynamic reconfiguration sequence.
const RECONFIG_VERSION: i64 = 0x1_0000_0002;

/// RAII handle managing the lifecycle of an isolated multi-node test ensemble.
struct Quorum {
    base: u16,
    dir: PathBuf,
}

impl Quorum {
    /// Launches an ephemeral three-node quorum configured with dynamic reconfiguration enabled.
    /// When `super_user` is true, injects administrative digest authentication credentials for
    /// `super:test`.
    fn start(super_user: bool) -> Option<Self> {
        let base = real_port(OFFSET)?;
        let dir = std::env::temp_dir().join(format!("zk-rust-c-reconfig-{base}"));
        // Ensures a clean storage directory for each invocation.
        let _ = std::fs::remove_dir_all(&dir);
        let q = Self { base, dir };
        // The last line of a key wins, so these lines replace the
        // static server lines of the script.
        let mut props = String::from("reconfigEnabled=true\n");
        for n in 1..=NUM_SERVERS {
            props.push_str(&q.server_string(n));
            props.push('\n');
        }
        let jvm = if super_user {
            format!("-Dzookeeper.DigestAuthenticationProvider.superDigest={SUPER_DIGEST}")
        } else {
            String::new()
        };
        let status = q
            .script()
            .arg("start")
            .arg(base.to_string())
            .arg(&q.dir)
            .env("ZK_TEST_SERVER_PROPS", props)
            .env("ZK_TEST_SERVER_JVM_OPTS", jvm)
            .stdout(Stdio::null())
            .status();
        match status {
            Ok(s) if s.success() => Some(q),
            other => common::skip("the quorum did not start", &format!("{other:?}")),
        }
    }

    fn script(&self) -> Command {
        let mut cmd = Command::new("bash");
        cmd.arg(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../dev/test-ensemble.sh"));
        cmd
    }

    /// Computes the client-facing TCP port for node `n` (1-indexed).
    fn client_port(&self, n: usize) -> u16 {
        self.base + u16::try_from(n).unwrap() - 1
    }

    /// Formats the loopback host and client port address for node `n`.
    fn host_port(&self, n: usize) -> String {
        format!("127.0.0.1:{}", self.client_port(n))
    }

    /// Generates the canonical configuration line for node `n` matching dynamic reconfig syntax.
    fn server_string(&self, n: usize) -> String {
        let n16 = u16::try_from(n).unwrap();
        format!(
            "server.{n}=127.0.0.1:{}:{}:participant;127.0.0.1:{}",
            self.base + n16 + 2,
            self.base + n16 + 5,
            self.client_port(n)
        )
    }

    /// Queries the active ensemble to identify the current leader server ID.
    fn leader(&self) -> usize {
        let out = self
            .script()
            .arg("leader")
            .arg(self.base.to_string())
            .arg(&self.dir)
            .output()
            .unwrap();
        String::from_utf8_lossy(&out.stdout).trim().parse().unwrap()
    }

    /// Enumerates follower node IDs in ascending order.
    fn followers(&self) -> Vec<usize> {
        let leader = self.leader();
        (1..=NUM_SERVERS).filter(|n| *n != leader).collect()
    }
}

impl Drop for Quorum {
    fn drop(&mut self) {
        let _ = self
            .script()
            .arg("stop")
            .arg(self.base.to_string())
            .arg(&self.dir)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// Dispatches test execution across each supported runtime driver against a fresh quorum
/// instance.
macro_rules! quorum_test {
    ($name:ident, $super_user:expr) => {
        mod $name {
            use super::*;

            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn thread() {
                let _lock = PORTS.lock().await;
                if let Some(q) = Quorum::start($super_user) {
                    super::$name(&q, Rt::Thread).await;
                }
            }

            #[cfg(all(unix, feature = "tokio"))]
            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn tokio() {
                let _lock = PORTS.lock().await;
                if let Some(q) = Quorum::start($super_user) {
                    super::$name(&q, Rt::Tokio).await;
                }
            }
        }
    };
}

/// Executes an asynchronous future bounded by timeout budget [`SLOW`].
async fn slow<F: std::future::Future>(f: F) -> F::Output {
    tokio::time::timeout(SLOW, f).await.expect("timed out")
}

/// Constructs a `ClientBuilder` initialized with the test connection string and suite session
/// timeout.
fn builder(rt: Rt, cs: &str) -> ClientBuilder {
    rt.apply(common::builder(cs).session_timeout(SESSION_TIMEOUT))
}

/// Establishes a client connection targeting the active ensemble leader.
async fn connect_leader(q: &Quorum, rt: Rt) -> Client {
    slow(builder(rt, &q.host_port(q.leader())).connect())
        .await
        .expect("connect")
}

/// Connects using an ordered host sequence `[follower_0, follower_1, leader]` and validates
/// that the initial active link attaches to the first follower (mirroring C `connectFollowers`).
async fn connect_followers(q: &Quorum, rt: Rt, followers: &[usize]) -> Client {
    assert_eq!(followers.len(), NUM_SERVERS - 1);
    let mut hosts: Vec<String> = followers.iter().map(|n| q.host_port(*n)).collect();
    hosts.push(q.host_port(q.leader()));
    let c = slow(
        builder(rt, &hosts.join(","))
            .server_order(ServerOrder::AsGiven)
            .connect(),
    )
    .await
    .expect("connect");
    let at = c.connected_server().expect("a connected server");
    assert_eq!(at.port(), q.client_port(followers[0]));
    c
}

/// Extracts raw `server.` configuration directives from ensemble configuration text.
fn server_lines(cfg: &EnsembleConfig) -> BTreeSet<String> {
    cfg.raw
        .lines()
        .filter(|l| l.starts_with("server."))
        .map(str::to_owned)
        .collect()
}

/// Validates that both the raw configuration text and parsed server metadata match expected
/// member IDs.
fn assert_members(q: &Quorum, cfg: &EnsembleConfig, nodes: &[usize]) {
    let want: BTreeSet<String> = nodes.iter().map(|n| q.server_string(*n)).collect();
    assert_eq!(server_lines(cfg), want, "{}", cfg.raw);
    let ids: BTreeSet<u64> = cfg.servers.iter().map(|s| s.id).collect();
    let want_ids: BTreeSet<u64> = nodes.iter().map(|n| u64::try_from(*n).unwrap()).collect();
    assert_eq!(ids, want_ids, "{cfg:?}");
}

/// Generates a node ID set omitting the specified member ID.
fn all_but(gone: usize) -> Vec<usize> {
    (1..=NUM_SERVERS).filter(|n| *n != gone).collect()
}

/// Retrieves configuration state, tolerating intermediate connection loss and reconnecting if a
/// member drop terminates the link.
async fn config_after_move(c: &Client) -> (EnsembleConfig, Stat) {
    let deadline = tokio::time::Instant::now() + SLOW;
    loop {
        match slow(c.get_config()).await {
            Err(Error::ConnectionLoss) if tokio::time::Instant::now() < deadline => {
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
            other => return other.unwrap(),
        }
    }
}

/// Dispatches an incremental reconfiguration command to decommission node `n`.
async fn remove(c: &Client, n: usize) -> Result<(EnsembleConfig, Stat), Error> {
    let leave = Reconfig::Incremental {
        joining: Vec::new(),
        leaving: vec![u64::try_from(n).unwrap()],
    };
    slow(c.reconfig(&leave, None)).await
}

/// Asserts that the computed digest for administrative credentials matches C suite constants.
#[test]
fn super_digest_is_super_test() {
    assert_eq!(Id::digest("super", "test").id, SUPER_DIGEST);
}

quorum_test!(non_incremental, true);
/// Validates C client parity (`TestReconfigServer::testNonIncremental`): connects to the ensemble
/// leader, validates the initial configuration version (1 << 32), and executes non-incremental
/// membership mutations down to two members and subsequently restoring the original three members.
async fn non_incremental(q: &Quorum, rt: Rt) {
    let leader = q.leader();
    let c = connect_leader(q, rt).await;
    let (cfg, _) = slow(c.get_config()).await.unwrap();
    slow(c.add_auth("digest", b"super:test".to_vec()))
        .await
        .unwrap();
    assert_eq!(cfg.version, Some(FIRST_VERSION), "{}", cfg.raw);
    assert_members(q, &cfg, &[1, 2, 3]);

    let followers = q.followers();
    assert_eq!(followers.len(), NUM_SERVERS - 1);
    let mut members: Vec<String> = followers[1..].iter().map(|n| q.server_string(*n)).collect();
    members.push(q.server_string(leader));
    let set = Reconfig::NonIncremental { members };
    let (cfg, _) = slow(c.reconfig(&set, None)).await.unwrap();
    assert_eq!(cfg.version, Some(RECONFIG_VERSION), "{}", cfg.raw);
    assert_members(q, &cfg, &all_but(followers[0]));

    let all = Reconfig::NonIncremental {
        members: (1..=NUM_SERVERS).map(|n| q.server_string(n)).collect(),
    };
    let (cfg, _) = slow(c.reconfig(&all, None)).await.unwrap();
    assert_members(q, &cfg, &[1, 2, 3]);
    slow(c.close()).await.unwrap();
}

quorum_test!(remove_connected_follower, true);
/// Validates C client parity (`TestReconfigServer::testRemoveConnectedFollower`). A client
/// connected to a follower initiates a reconfiguration removing that same follower, verifying
/// that the client recovers and reads the updated two member configuration after link termination
/// or failover.
async fn remove_connected_follower(q: &Quorum, rt: Rt) {
    let followers = q.followers();
    let c = connect_followers(q, rt, &followers).await;
    slow(c.add_auth("digest", b"super:test".to_vec()))
        .await
        .unwrap();
    let r = remove(&c, followers[0]).await;
    assert!(matches!(r, Ok(_) | Err(Error::ConnectionLoss)), "{r:?}");
    let (cfg, _) = config_after_move(&c).await;
    assert_members(q, &cfg, &all_but(followers[0]));
    slow(c.close()).await.unwrap();
}

quorum_test!(remove_follower, true);
/// Validates C client parity (`TestReconfigServer::testRemoveFollower`). Executes incremental
/// reconfiguration from the leader to evict a follower, incrementing configuration version,
/// followed by a second reconfiguration readmitting the member.
async fn remove_follower(q: &Quorum, rt: Rt) {
    let c = connect_leader(q, rt).await;
    let (cfg, _) = slow(c.get_config()).await.unwrap();
    slow(c.add_auth("digest", b"super:test".to_vec()))
        .await
        .unwrap();
    assert_eq!(cfg.version, Some(FIRST_VERSION), "{}", cfg.raw);
    assert_members(q, &cfg, &[1, 2, 3]);

    let followers = q.followers();
    assert_eq!(followers.len(), NUM_SERVERS - 1);
    let (cfg, _) = remove(&c, followers[0]).await.unwrap();
    assert_eq!(cfg.version, Some(RECONFIG_VERSION), "{}", cfg.raw);
    assert_members(q, &cfg, &all_but(followers[0]));

    let join = Reconfig::Incremental {
        joining: vec![q.server_string(followers[0])],
        leaving: Vec::new(),
    };
    let (cfg, _) = slow(c.reconfig(&join, None)).await.unwrap();
    assert_members(q, &cfg, &[1, 2, 3]);
    slow(c.close()).await.unwrap();
}

quorum_test!(reconfig_failure_without_auth, true);
/// Validates C client parity (`TestReconfigServer::testReconfigFailureWithoutAuth`,
/// ZOOKEEPER-2014). Verifies that reconfiguration operations require administrative privileges,
/// failing with `NoAuth` when unauthenticated or provided invalid credentials, and succeeding
/// upon authenticating as `super:test`.
async fn reconfig_failure_without_auth(q: &Quorum, rt: Rt) {
    let followers = q.followers();
    let c = connect_followers(q, rt, &followers).await;
    assert_eq!(remove(&c, followers[0]).await.unwrap_err(), Error::NoAuth);
    slow(c.add_auth("digest", b"super:wrong".to_vec()))
        .await
        .unwrap();
    assert_eq!(remove(&c, followers[0]).await.unwrap_err(), Error::NoAuth);
    slow(c.add_auth("digest", b"super:test".to_vec()))
        .await
        .unwrap();
    let (cfg, _) = remove(&c, followers[0]).await.unwrap();
    assert_members(q, &cfg, &all_but(followers[0]));
    let (cfg, _) = config_after_move(&c).await;
    assert_members(q, &cfg, &all_but(followers[0]));
    slow(c.close()).await.unwrap();
}

quorum_test!(
    reconfig_failure_without_server_superuser_password_configured,
    false
);
/// Validates C client parity
/// (`TestReconfigServer::testReconfigFailureWithoutServerSuperuserPasswordConfigured`).
/// Confirms that when the ensemble is started without a superuser password hash configured,
/// all reconfiguration attempts fail with `NoAuth` regardless of supplied credentials.
async fn reconfig_failure_without_server_superuser_password_configured(q: &Quorum, rt: Rt) {
    let followers = q.followers();
    let c = connect_followers(q, rt, &followers).await;
    assert_eq!(remove(&c, followers[0]).await.unwrap_err(), Error::NoAuth);
    for auth in [&b"super:"[..], &b"super:test"[..]] {
        slow(c.add_auth("digest", auth.to_vec())).await.unwrap();
        assert_eq!(remove(&c, followers[0]).await.unwrap_err(), Error::NoAuth);
    }
    let (cfg, _) = slow(c.get_config()).await.unwrap();
    assert_members(q, &cfg, &[1, 2, 3]);
    slow(c.close()).await.unwrap();
}
