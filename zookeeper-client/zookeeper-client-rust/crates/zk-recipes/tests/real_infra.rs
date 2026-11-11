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

//! Smoke tests for real server test infrastructure (`RealServer`, `Ensemble`, `Proxy`,
//! and cross-process port range leases).
//!
//! Real server tests launch a standalone server or three node ensemble (selecting the
//! release specified by `ZK_TEST_SERVER_VERSION`) and verify client connectivity,
//! failover, and proxy fault injection. Marked `#[ignore = "real-server"]`; run via:
//! `ZK_TEST_PORT_BASE=<base> cargo test --test real_infra -- --ignored`.
//!
//! Lease unit tests require no external server and run during standard `cargo test`
//! against isolated temporary directories.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::print_stderr,
    clippy::await_holding_lock
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{CreateMode, SessionState};
use common::{Ensemble, Proxy, RealServer, wait_state, within};

/// Asserts that the server on `port` reports `version` via `srvr` (or any valid
/// version string when testing the in-tree build with `version == None`).
fn assert_version(port: u16, version: Option<&str>) {
    let reported = common::srvr_version(port).expect("srvr gives a version");
    if let Some(v) = version {
        assert_eq!(reported, v, "the server on {port} runs another release");
    }
}

/// Serializes real server tests within this binary so post-drop assertions on the
/// port range lease cannot race with another test acquiring the range.
static SERIAL: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn serial() -> std::sync::MutexGuard<'static, ()> {
    SERIAL
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Returns the lease directory path for the configured test port range.
fn range_lease() -> std::path::PathBuf {
    common::lease_dir(common::real_port(common::STANDALONE_OFFSET).unwrap())
}

/// Verifies standalone server lifecycle, basic CRUD operations, non-reentrant
/// lease enforcement on the same thread, and lease release on drop.
#[tokio::test]
#[ignore = "real-server"]
async fn standalone_server_starts_and_stops() {
    let _serial = serial();
    let Some(server) = RealServer::start_or_skip() else {
        return;
    };
    let port = server.port();
    assert_eq!(common::srvr_mode(port).as_deref(), Some("standalone"));
    assert_eq!(server.version(), common::server_version().as_deref());
    assert_version(port, server.version());
    assert_eq!(
        common::lease_owner(&range_lease()),
        Some(std::process::id())
    );

    // Range leases are non-reentrant.
    // A second acquisition on the same thread panics immediately.
    let again = std::panic::catch_unwind(Ensemble::start_or_skip);
    assert!(again.is_err(), "a second take on one thread must fail");

    let client = common::connect(&server.connect_string()).await;
    let path = "/real-infra-standalone";
    within(client.create(path, b"one", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (data, _) = within(client.get_data(path)).await.unwrap();
    assert_eq!(data, b"one");
    within(client.delete(path, None)).await.unwrap();
    within(client.close()).await.unwrap();

    drop(server);
    assert!(!common::port_open(port), "the drop stops the server");
    assert_ne!(
        common::lease_owner(&range_lease()),
        Some(std::process::id()),
        "the drop releases the lease"
    );
}

/// Verifies that `start_version_or_skip` launches the explicitly requested release
/// (such as 3.5.10 for `TreeCache` compatibility tests) regardless of `ZK_TEST_SERVER_VERSION`.
#[tokio::test]
#[ignore = "real-server"]
async fn release_server_is_selected() {
    let _serial = serial();
    let Some(server) = RealServer::start_version_or_skip(Some(common::LEGACY_VERSION)) else {
        return;
    };
    assert_eq!(server.version(), Some(common::LEGACY_VERSION));
    assert_version(server.port(), Some(common::LEGACY_VERSION));
    let client = common::connect(&server.connect_string()).await;
    let children = within(client.get_children("/")).await.unwrap();
    assert!(children.iter().any(|c| c == "zookeeper"), "{children:?}");
    within(client.close()).await.unwrap();
}

/// Verifies three node ensemble startup, leader election after stopping the leader,
/// session preservation across failover, and follower catch up upon restart.
#[tokio::test]
#[ignore = "real-server"]
async fn ensemble_starts_and_fails_over() {
    let _serial = serial();
    let Some(ensemble) = Ensemble::start_or_skip() else {
        return;
    };
    let mut leaders = 0;
    for n in 1..=3 {
        let port = ensemble.client_port(n);
        match common::srvr_mode(port).as_deref() {
            Some("leader") => leaders += 1,
            Some("follower") => {}
            other => panic!("node {n} serves as {other:?}"),
        }
        assert_version(port, ensemble.version());
    }
    assert_eq!(leaders, 1, "one leader");
    let old = ensemble.leader().expect("a leader");

    // Use a 10s session timeout so the session survives leader election.
    let client = within(
        common::builder(&ensemble.connect_string())
            .session_timeout(Duration::from_secs(10))
            .connect(),
    )
    .await
    .unwrap();
    let path = "/real-infra-ensemble";
    within(client.create(path, b"before", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (session, _) = client.session();
    let epoch = client.state().epoch;
    let mut states = client.state_watcher();

    ensemble.stop_node(old).unwrap();
    assert!(!common::port_open(ensemble.client_port(old)));
    let new = ensemble.wait_leader(Some(old)).expect("a new leader");
    assert_ne!(new, old);
    // Stopping the leader causes all ensemble nodes to drop client connections
    // until a new quorum leader is elected.
    wait_state(&mut states, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    assert_eq!(client.session().0, session, "the session stays");
    within(client.set_data(path, b"after", None)).await.unwrap();

    ensemble.start_node(old).unwrap();
    assert!(matches!(
        common::srvr_mode(ensemble.client_port(old)).as_deref(),
        Some("leader" | "follower")
    ));
    assert!(ensemble.leader().is_some());
    let back = common::connect(&ensemble.node_connect_string(old)).await;
    within(back.sync(path)).await.unwrap();
    let (data, _) = within(back.get_data(path)).await.unwrap();
    assert_eq!(data, b"after", "the node catches up");
    within(back.close()).await.unwrap();
    within(client.close()).await.unwrap();

    let ports: Vec<u16> = (1..=3).map(|n| ensemble.client_port(n)).collect();
    drop(ensemble);
    for p in ports {
        assert!(!common::port_open(p), "the drop stops the node on {p}");
    }
}

/// Verifies that cutting a `Proxy` transitions the client to `Connecting` and that
/// restoring the proxy reconnects the existing session at the next epoch with
/// ephemeral nodes intact.
#[tokio::test]
#[ignore = "real-server"]
async fn proxy_cut_reconnects_the_same_session() {
    let _serial = serial();
    let Some(server) = RealServer::start_or_skip() else {
        return;
    };
    let proxy = Proxy::start(server.port());
    let client = common::connect(&proxy.connect_string()).await;
    let path = "/real-infra-proxy";
    within(client.create(path, b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (session, _) = client.session();
    let epoch = client.state().epoch;
    let mut states = client.state_watcher();

    proxy.cut();
    wait_state(&mut states, |i| i.state == SessionState::Connecting).await;
    // Client remains in `Connecting` across reconnect attempts while the proxy is cut.
    tokio::time::sleep(Duration::from_millis(500)).await;
    assert_eq!(client.state().state, SessionState::Connecting);

    proxy.restore();
    let info = wait_state(&mut states, |i| {
        i.state == SessionState::Connected && i.epoch > epoch
    })
    .await;
    assert_eq!(info.epoch, epoch + 1, "one new connection");
    assert_eq!(client.session().0, session, "the session stays");
    assert!(
        within(client.exists(path)).await.unwrap().is_some(),
        "the ephemeral node of the session stays"
    );
    within(client.close()).await.unwrap();
}

/// Unit tests for cross-process directory leases using isolated paths.
#[cfg(unix)]
mod lease {
    use std::path::PathBuf;
    use std::process::Command;
    use std::time::{Duration, Instant};

    use super::common::{acquire_lease_at, lease_owner, process_alive, release_lease_at};

    /// Returns a clean temporary lease directory path for test `name`.
    fn dir(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "zk-recipes-lease-test-{}-{name}",
            std::process::id()
        ));
        // Remove any leftover directory from a prior run.
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    /// Creates a lease directory owned by `pid`.
    fn foreign_lease(d: &PathBuf, pid: u32) {
        std::fs::create_dir(d).unwrap();
        std::fs::write(d.join("pid"), pid.to_string()).unwrap();
    }

    /// Verifies immediate acquisition and cleanup of an uncontended lease.
    #[test]
    fn free_lease_is_taken_and_released() {
        let d = dir("free");
        assert!(acquire_lease_at(&d, Duration::ZERO, Duration::ZERO));
        assert_eq!(lease_owner(&d), Some(std::process::id()));
        release_lease_at(&d);
        assert!(!d.exists());
    }

    /// Verifies that a lease held by a terminated process is reclaimed.
    #[test]
    fn lease_of_a_dead_process_is_taken_over() {
        let d = dir("dead");
        let mut child = Command::new("true").spawn().unwrap();
        let pid = child.id();
        child.wait().unwrap();
        assert!(!process_alive(pid));
        foreign_lease(&d, pid);
        assert!(acquire_lease_at(
            &d,
            Duration::from_secs(5),
            Duration::from_secs(5)
        ));
        assert_eq!(lease_owner(&d), Some(std::process::id()));
        release_lease_at(&d);
    }

    /// Verifies that a lease held by a live process blocks acquisition until timeout,
    /// ignores foreign release calls, and can be reclaimed once the owner exits.
    #[test]
    fn lease_of_a_live_process_stays() {
        let d = dir("live");
        let mut child = Command::new("sleep").arg("30").spawn().unwrap();
        let pid = child.id();
        foreign_lease(&d, pid);
        let start = Instant::now();
        assert!(!acquire_lease_at(
            &d,
            Duration::from_millis(300),
            Duration::from_secs(5)
        ));
        assert!(start.elapsed() >= Duration::from_millis(300));
        release_lease_at(&d);
        assert_eq!(lease_owner(&d), Some(pid), "the lease of the owner stays");
        child.kill().unwrap();
        child.wait().unwrap();
        assert!(acquire_lease_at(
            &d,
            Duration::from_secs(5),
            Duration::from_secs(5)
        ));
        release_lease_at(&d);
    }

    /// Verifies that an uninitialized lease directory (missing a `pid` file) is
    /// reclaimed after the grace period elapses.
    #[test]
    fn lease_without_an_owner_is_taken_after_the_grace_time() {
        let d = dir("no-owner");
        std::fs::create_dir(&d).unwrap();
        let start = Instant::now();
        assert!(acquire_lease_at(
            &d,
            Duration::from_secs(5),
            Duration::from_millis(200)
        ));
        assert!(start.elapsed() >= Duration::from_millis(200));
        assert_eq!(lease_owner(&d), Some(std::process::id()));
        release_lease_at(&d);
    }
}
