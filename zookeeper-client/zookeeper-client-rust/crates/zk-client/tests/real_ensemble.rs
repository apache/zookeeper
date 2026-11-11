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

//! Integration tests verifying ZooKeeper ensemble features against real server processes.
//!
//! Exercises read-only mode transitions and dynamic cluster reconfiguration (`reconfig`).
//! Quorum servers are bound to allocated ports in the base range. Because instances share
//! port resources, tests are executed sequentially under mutex synchronization.
//! Run with: `ZK_TEST_PORT_BASE=<base> cargo test --test real_ensemble -- --ignored`.

// Test scenarios execute extended asynchronous flows on dedicated worker threads;
// stack size limits configured for the core library do not apply.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::print_stderr,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::path::PathBuf;
use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    AddWatchMode, Client, CreateMode, Error, EventKind, Id, MultiError, MultiReader, MultiWriter,
    ReadResult, Reconfig, ServerRole, SessionState, Version,
};
use common::{RealServer, within};

/// Mutex ensuring serialized execution across tests sharing local network port allocations.
static PORTS: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Maximum wait duration for a quorum server to transition to an active serving state after
/// initial socket readiness.
const SERVE_WAIT: Duration = Duration::from_secs(20);

/// Network port allocation for the ensemble test instance.
struct Ports {
    client: u16,
    q1: u16,
    e1: u16,
    q2: u16,
    e2: u16,
    client2: u16,
}

impl Ports {
    /// Resolves port assignments based on `ZK_TEST_PORT_BASE`, returning `None` if unset.
    fn get() -> Option<Self> {
        Some(Self {
            client: common::real_port(14)?,
            q1: common::real_port(15)?,
            e1: common::real_port(16)?,
            q2: common::real_port(17)?,
            e2: common::real_port(18)?,
            client2: common::real_port(19)?,
        })
    }
}

/// Initializes a clean server working directory with an initial `myid` configuration of 1.
fn server_dir(name: &str, port: u16) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("zk-rust-test-{name}-{port}"));
    // Remove lingering directory state from prior test executions.
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("data")).unwrap();
    std::fs::write(dir.join("data").join("myid"), "1\n").unwrap();
    dir
}

/// Launches a server instance on the specified port, or records a test skip note if launch fails.
fn start(port: u16, dir: PathBuf, props: &str, jvm: &str) -> Option<RealServer> {
    match RealServer::start_with(port, Some(dir), props, jvm) {
        Ok(s) => Some(s),
        Err(e) => common::skip("the quorum server did not start", &e),
    }
}

/// Establishes a client connection with an extended timeout accommodating quorum election and
/// initialization.
async fn connect_slow(b: apache_zookeeper::ClientBuilder) -> Client {
    tokio::time::timeout(SERVE_WAIT, b.connect())
        .await
        .expect("timed out")
        .expect("connect")
}

/// Invariant: when configured with `allow_read_only(true)`, a client successfully connects
/// to an ensemble partition lacking a quorum, entering state `ConnectedReadOnly`. Read operations
/// and watches continue to function, whereas mutating operations, `sync`, and write transactions
/// are rejected with `Error::NotReadOnly`. Clients lacking this flag remain in `Connecting`.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn read_only_server_serves_reads() {
    let _lock = PORTS.lock().await;
    let Some(p) = Ports::get() else { return };
    let dir = server_dir("ro", p.client);
    // In a two-voter configuration with a single active server, quorum cannot be achieved.
    // A partitioned read-only server permits only local sessions (ReadOnlyRequestProcessor).
    let props = format!(
        "localSessionsEnabled=true\ninitLimit=5\nsyncLimit=2\nserver.1=127.0.0.1:{}:{}\nserver.2=127.0.0.1:{}:{}",
        p.q1, p.e1, p.q2, p.e2
    );
    let Some(server) = start(p.client, dir.clone(), &props, "-Dreadonlymode.enabled=true") else {
        return;
    };
    let cs = server.connect_string();

    let rts = common::all_rts();
    for rt in rts {
        let c = connect_slow(rt.apply(common::builder(&cs)).allow_read_only(true)).await;
        assert_eq!(c.state().state, SessionState::ConnectedReadOnly);
        let kids = within(c.get_children("/")).await.unwrap();
        assert!(kids.iter().any(|k| k == "zookeeper"), "{kids:?}");
        assert!(within(c.exists("/zookeeper")).await.unwrap().is_some());
        let mut reads = MultiReader::new();
        reads.get_children("/").unwrap();
        reads.get_data("/missing").unwrap();
        let r = within(c.multi_read(&reads)).await.unwrap();
        assert!(
            matches!(r.first(), Some(Ok(ReadResult::Children(_)))),
            "{r:?}"
        );
        assert!(matches!(r.get(1), Some(Err(Error::NoNode))), "{r:?}");

        let f = c.create("/w", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
        assert_eq!(within(f).await.unwrap_err(), Error::NotReadOnly);
        assert_eq!(
            within(c.set_data("/zookeeper", b"x", None)).await,
            Err(Error::NotReadOnly)
        );
        assert_eq!(within(c.sync("/")).await, Err(Error::NotReadOnly));
        let mut txn = MultiWriter::new();
        txn.check("/zookeeper", Version::ANY).unwrap();
        assert_eq!(
            within(c.multi(&txn)).await.unwrap_err(),
            MultiError::Request(Error::NotReadOnly)
        );
        // Watches remain functional on read-only server instances.
        let w = within(c.add_watch("/", AddWatchMode::PersistentRecursive)).await;
        assert!(w.is_ok(), "{w:?}");
        within(c.close()).await.unwrap();
    }

    // Standard clients lacking read-only permission remain in Connecting state.
    let plain = common::builder(&cs).connect();
    let s = plain.state_watcher();
    assert!(
        tokio::time::timeout(Duration::from_millis(1500), plain)
            .await
            .is_err()
    );
    assert_eq!(s.current().state, SessionState::Connecting);

    drop(server);
    let _ = std::fs::remove_dir_all(&dir);
}

/// Invariant: dynamic cluster reconfiguration via `reconfig` allows incremental addition and
/// removal of ensemble members, validates optimistic concurrency against the expected
/// configuration version (`BadVersion`), and enforces superuser authentication requirements
/// (`NoAuth`). Configuration updates publish an updated ensemble topology and trigger watched
/// change notifications.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn reconfig_adds_and_removes_an_observer() {
    let _lock = PORTS.lock().await;
    let Some(p) = Ports::get() else { return };
    let dir = server_dir("reconfig", p.client);
    let props = format!(
        "standaloneEnabled=false\nreconfigEnabled=true\ninitLimit=5\nsyncLimit=2\n\
         server.1=127.0.0.1:{}:{}:participant;127.0.0.1:{}",
        p.q1, p.e1, p.client
    );
    let jvm = format!(
        "-Dzookeeper.DigestAuthenticationProvider.superDigest={}",
        Id::digest("super", "sekret").id
    );
    let Some(server) = start(p.client, dir.clone(), &props, &jvm) else {
        return;
    };
    let c = connect_slow(common::builder(&server.connect_string())).await;

    let (cfg, _) = within(c.get_config()).await.unwrap();
    assert_eq!(cfg.servers.len(), 1, "{cfg:?}");
    let one = cfg.servers.first().unwrap();
    assert_eq!((one.id, one.role), (1, ServerRole::Participant));
    assert_eq!(one.client, Some(("127.0.0.1".to_owned(), p.client)));
    let v = cfg.version.expect("config version");

    let join = Reconfig::Incremental {
        joining: vec![format!(
            "server.2=127.0.0.1:{}:{}:observer;127.0.0.1:{}",
            p.q2, p.e2, p.client2
        )],
        leaving: Vec::new(),
    };
    assert_eq!(
        within(c.reconfig(&join, Some(v))).await.unwrap_err(),
        Error::NoAuth
    );
    within(c.add_auth("digest", b"super:sekret".to_vec()))
        .await
        .unwrap();
    assert_eq!(
        within(c.reconfig(&join, Some(v.wrapping_add(7))))
            .await
            .unwrap_err(),
        Error::BadVersion
    );

    let (_, _, w) = within(c.get_config_watched()).await.unwrap();
    let (cfg, _) = within(c.reconfig(&join, Some(v))).await.unwrap();
    assert_eq!(cfg.servers.len(), 2, "{cfg:?}");
    let two = cfg.servers.iter().find(|s| s.id == 2).unwrap();
    assert_eq!(two.role, ServerRole::Observer);
    assert_eq!((two.quorum_port, two.election_port), (p.q2, p.e2));
    let v2 = cfg.version.expect("config version");
    assert!(v2 > v);
    let ev = within(w).await.unwrap();
    assert_eq!(
        (ev.kind, ev.path.as_str()),
        (EventKind::NodeDataChanged, "/zookeeper/config")
    );

    let leave = Reconfig::Incremental {
        joining: Vec::new(),
        leaving: vec![2],
    };
    let (cfg, _) = within(c.reconfig(&leave, None)).await.unwrap();
    assert_eq!(cfg.servers.len(), 1, "{cfg:?}");
    assert!(cfg.version.expect("config version") > v2);
    let (now, _) = within(c.get_config()).await.unwrap();
    assert_eq!(now, cfg);

    within(c.close()).await.unwrap();
    drop(server);
    let _ = std::fs::remove_dir_all(&dir);
}
