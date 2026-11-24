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

//! C client compatibility tests mirroring `TestClient.cc` (suite `Zookeeper_simpleSystem`).
//! Each test validates parity with corresponding C implementation behaviors.
//!
//! Test executions rely on live ensemble instances managed at fixed port offsets:
//!
//! - Offset 0: Primary shared server. Test scenarios execute in isolated subtrees
//!   partitioned across runtime drivers.
//! - Offset 1: Unbound port used to evaluate initial connection failover (`testFirstServerDown`).
//! - Offset 2: Dedicated server reserved for scenarios requiring cluster lifecycle manipulation
//!   (restarts, pauses) or clean transactional zxid histories. Execution is serialized.
//! - Offset 3: Server configured with accelerated container and TTL eviction intervals.
//! - Offsets 4 and 5: Dual cleartext and TLS server configured with `ssl.clientAuth=none`.
//!
//! These tests require `#[ignore = "real-server"]`. Execute via:
//! `ZK_TEST_PORT_BASE=<base> cargo test --test c_client -- --ignored`.
//! For TLS scenarios, activate the `tls-ring` feature flag.

// A scenario is one long async body. It runs on a 2 MiB test or worker
// thread, so the stack frame limit of the library does not apply.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::print_stderr,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;
mod parity_g1;

use std::io;
use std::net::{Ipv6Addr, SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use apache_zookeeper::acl::{CREATOR_ALL_ACL, OPEN_ACL_UNSAFE, READ_ACL_UNSAFE};
use apache_zookeeper::{
    Client, CreateMode, Error, EventKind, Perms, Resolve, ServerOrder, SessionState, Ttl,
    WatchError, WatcherKind,
};
use common::{Ctx, Rt, SharedServer, WAIT, real_port, wait_state, within};
use parity_g1::{
    RECONNECT_WAIT, expect_event, fresh_server_with, is_pending, restart, restart_builder,
    within_for,
};

/// Shared ensemble endpoint allocated for read-write operations in this test suite.
static SERVER: SharedServer = SharedServer::new(0);

/// Port offset reserved for an intentionally inactive endpoint to test connection failover.
const DOWN_OFFSET: u16 = 1;

/// Port offset designated for the isolated server instance utilized in lifecycle testing.
const DEDICATED_OFFSET: u16 = 2;

/// Port offset allocated for the server instance configured with aggressive TTL eviction.
const TTL_OFFSET: u16 = 3;

/// Cleartext listener port offset for the dual-mode TLS server fixture.
#[cfg(feature = "tls-rustls")]
const TLS_OFFSET: u16 = 4;

/// TLS listener port offset configured for server-only authentication verification.
#[cfg(feature = "tls-rustls")]
const TLS_SECURE_OFFSET: u16 = 5;

/// Serializes test cases requiring exclusive control over the dedicated server fixture.
static DEDICATED: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Generates multithreaded test entry points for each supported runtime driver against the
/// shared cluster fixture.
macro_rules! shared_test {
    ($name:ident) => {
        mod $name {
            use super::*;

            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn thread() {
                if let Some(ctx) = Ctx::new(&SERVER, stringify!($name), Rt::Thread) {
                    super::$name(ctx).await;
                }
            }

            #[cfg(all(unix, feature = "tokio"))]
            #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
            #[ignore = "real-server"]
            async fn tokio() {
                if let Some(ctx) = Ctx::new(&SERVER, stringify!($name), Rt::Tokio) {
                    super::$name(ctx).await;
                }
            }
        }
    };
}

/// Helper utility to synchronously create an empty persistent znode at the specified path.
async fn mk(c: &Client, path: &str) {
    within(c.create(path, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap_or_else(|e| panic!("create {path}: {e:?}"));
}

/// Asserts client side path validation failure prior to wire dispatch.
async fn create_refused(c: &Client, path: &str, mode: CreateMode) {
    let f = c.create(path, b"", mode, OPEN_ACL_UNSAFE);
    assert!(f.is_ready(), "{path:?} {mode:?}: the request went out");
    assert!(
        matches!(f.await, Err(Error::InvalidPath(_))),
        "{path:?} {mode:?}"
    );
}

/// Asserts successful znode creation under the provided mode.
async fn create_ok(c: &Client, path: &str, mode: CreateMode) {
    let r = within(c.create(path, b"", mode, OPEN_ACL_UNSAFE)).await;
    assert!(r.is_ok(), "{path:?} {mode:?}: {r:?}");
}

/// Acquires exclusive access to the dedicated server fixture and initializes an empty znode
/// hierarchy.
async fn dedicated() -> Option<(tokio::sync::MutexGuard<'static, ()>, common::RealServer)> {
    let lock = DEDICATED.lock().await;
    let server = common::fresh_server(DEDICATED_OFFSET)?;
    Some((lock, server))
}

// ---- host list ----

shared_test!(first_host_down_connects_to_the_next);
/// Validates C client parity (`Zookeeper_simpleSystem::testFirstServerDown`): verifies that with
/// deterministic host ordering (`ServerOrder::AsGiven`), an initial unreachable ensemble address
/// does not abort the connection attempt, allowing seamless failover to the subsequent reachable
/// host.
async fn first_host_down_connects_to_the_next(ctx: Ctx) {
    let down = real_port(DOWN_OFFSET).unwrap();
    let cs = format!("127.0.0.1:{down},{}", ctx.cs);
    let b = ctx
        .rt
        .apply(common::builder(&cs))
        .server_order(ServerOrder::AsGiven);
    let c = within(b.connect()).await.unwrap();
    assert_eq!(c.connected_server().map(|a| a.port()), Some(ctx.port()));
    assert!(within(c.exists("/")).await.unwrap().is_some());
    within(c.close()).await.unwrap();
}

/// Custom resolver implementation simulating immediate resolution failure for the synthetic
/// hostname `jimmy`, while delegating valid hostnames to system resolution to eliminate
/// DNS timeout latency during tests.
struct UnknownJimmy;

impl Resolve for UnknownJimmy {
    fn resolve(&self, host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
        if host == "jimmy" {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                "unknown host jimmy",
            ));
        }
        (host, port).to_socket_addrs().map(Iterator::collect)
    }
}

shared_test!(unknown_host_name_does_not_block_the_connect);
/// Validates C client parity (`Zookeeper_simpleSystem::testNonexistentHost`): confirms that DNS
/// resolution failure for the leading host does not impede connection establishment when fallback
/// hosts remain available under `ServerOrder::AsGiven`.
async fn unknown_host_name_does_not_block_the_connect(ctx: Ctx) {
    let cs = format!("jimmy:5555,{}", ctx.cs);
    let b = ctx
        .rt
        .apply(common::builder(&cs))
        .resolver(UnknownJimmy)
        .server_order(ServerOrder::AsGiven);
    let c = within(b.connect()).await.unwrap();
    assert_eq!(c.connected_server().map(|a| a.port()), Some(ctx.port()));
    within(c.close()).await.unwrap();
}

shared_test!(ipv6_literal_host_connects);
/// Validates C client parity (`Zookeeper_simpleSystem::testIPV6`): asserts support for IPv6
/// loopback connection strings and subsequent operational correctness across IPv6 sockets.
async fn ipv6_literal_host_connects(ctx: Ctx) {
    let addr = SocketAddr::from((Ipv6Addr::LOCALHOST, ctx.port()));
    if std::net::TcpStream::connect_timeout(&addr, Duration::from_secs(1)).is_err() {
        eprintln!("skip: the server does not listen on {addr}");
        return;
    }
    let cs = format!("::1:{}", ctx.port());
    let c = within(ctx.rt.apply(common::builder(&cs)).connect())
        .await
        .unwrap();
    assert_eq!(c.connected_server(), Some(addr));
    // In accordance with C client test semantics (which pass NULL data buffers), the Rust client
    // supplies an empty byte slice.
    mk(&c, &ctx.root).await;
    within(c.close()).await.unwrap();
}

// ---- create modes and paths ----

/// Validates C client parity (`Zookeeper_simpleSystem::testCreateTtl`): verifies automated server
/// side eviction of persistent nodes created with short time-to-live intervals once the TTL
/// expires.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn ttl_node_goes_away_after_its_ttl() {
    let Some(server) = fresh_server_with(TTL_OFFSET, "", "-Dznode.container.checkIntervalMs=100")
    else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        let c = within(rt.apply(common::builder(&cs)).connect())
            .await
            .unwrap();
        let p = format!("/testTtl-{}", rt.name());
        let ttl = Ttl::from_millis(1).unwrap();
        let (created, _) = within(c.create(
            &*p,
            b"",
            CreateMode::PersistentWithTtl(ttl),
            OPEN_ACL_UNSAFE,
        ))
        .await
        .unwrap();
        assert_eq!(created, p.as_str());
        within_for(Duration::from_secs(10), async {
            while within(c.exists(&*p)).await.unwrap().is_some() {
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await;
        assert_eq!(within(c.exists(&*p)).await, Ok(None));
        within(c.close()).await.unwrap();
    }
}

shared_test!(two_creates_give_their_paths_and_different_stats);
/// Validates C client parity (`Zookeeper_simpleSystem::testCreate`): ensures node creation returns
/// the canonical path alongside populated metadata, with subsequent sequential creations yielding
/// distinct transaction IDs and timestamps.
async fn two_creates_give_their_paths_and_different_stats(ctx: Ctx) {
    let c = ctx.connect().await;
    mk(&c, &ctx.root).await;
    let a = ctx.path("/testcreateA");
    let b = ctx.path("/testcreateB");
    let (pa, sa) = within(c.create(&*a, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (pb, sb) = within(c.create(&*b, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(pa, a.as_str());
    assert_eq!(pb, b.as_str());
    for st in [&sa, &sb] {
        assert!(st.czxid.0 > 0, "{st:?}");
        assert!(st.mtime > 0, "{st:?}");
    }
    assert_ne!(sa, sb);
    within(c.close()).await.unwrap();
}

shared_test!(get_acl_reads_back_each_set_acl);
/// Validates C client parity (`Zookeeper_simpleSystem::testAcl`): confirms that node creation
/// establishes the requested access control list and subsequent `set_acl` updates mutate and
/// reflect the active permissions.
async fn get_acl_reads_back_each_set_acl(ctx: Ctx) {
    let c = ctx.connect().await;
    mk(&c, &ctx.root).await;
    let (acl, _) = within(c.get_acl(&*ctx.root)).await.unwrap();
    assert_eq!(acl, OPEN_ACL_UNSAFE);
    within(c.set_acl(&*ctx.root, READ_ACL_UNSAFE, None))
        .await
        .unwrap();
    let (acl, _) = within(c.get_acl(&*ctx.root)).await.unwrap();
    assert_eq!(acl, READ_ACL_UNSAFE);
    within(c.close()).await.unwrap();
}

shared_test!(child_list_stat_matches_the_exists_stat);
/// Validates C client parity (`Zookeeper_simpleSystem::testGetChildren2`): verifies that
/// `get_children_with_stat` returns child node entries accompanied by node stat metadata matching
/// direct `exists` queries, including child count.
async fn child_list_stat_matches_the_exists_stat(ctx: Ctx) {
    let c = ctx.connect().await;
    mk(&c, &ctx.root).await;
    for k in ["a", "b", "c", "d"] {
        mk(&c, &ctx.path(&format!("/child_{k}"))).await;
    }
    let (kids, st_a) = within(c.get_children_with_stat(&*ctx.root)).await.unwrap();
    assert_eq!(kids.len(), 4, "{kids:?}");
    let st_b = within(c.exists(&*ctx.root)).await.unwrap().unwrap();
    assert_eq!(st_a, st_b);
    assert_eq!(st_a.num_children, 4);
    within(c.close()).await.unwrap();
}

shared_test!(path_rules_match_the_c_table);
/// Validates C client parity (`Zookeeper_simpleSystem::testPathValidation`): enforces client side
/// validation matrix equivalent to ZooKeeper C client specifications, ensuring invalid znode paths
/// are rejected prior to network dispatch while permitting legitimate relative and sequential
/// prefixes.
async fn path_rules_match_the_c_table(ctx: Ctx) {
    let admin = ctx.connect().await;
    mk(&admin, &ctx.root).await;
    let c = within(ctx.builder().chroot(ctx.root.as_str()).connect())
        .await
        .unwrap();

    for bad in [
        "",
        "//",
        "///",
        "////",
        "/.",
        "/..",
        "/./",
        "/../",
        "/foo/./",
        "/foo/../",
        "/foo/.",
        "/foo/..",
        "/./.",
        "/../..",
        "/foo/bar/",
        "/foo//bar",
        "/foo/bar//",
        "foo",
        "a",
    ] {
        create_refused(&c, bad, CreateMode::Persistent).await;
    }

    create_ok(&c, "/createseq", CreateMode::Persistent).await;
    create_refused(&c, "/createseq/", CreateMode::Persistent).await;
    for ok in ["/createseq/", "/createseq/.", "/createseq/.."] {
        create_ok(&c, ok, CreateMode::PersistentSequential).await;
    }
    for bad in ["/createseq//", "/createseq/./", "/createseq/../"] {
        create_refused(&c, bad, CreateMode::PersistentSequential).await;
    }

    for ok in [
        "/.foo", "/.f.", "/..f", "/..f..", "/f.c", "/f", "/f/.f", "/f/f.", "/f/..f", "/f/f..",
        "/f/.f/f", "/f/f./f",
    ] {
        create_ok(&c, ok, CreateMode::Persistent).await;
    }
    assert_eq!(c.stats().pending, 0);
    within(c.close()).await.unwrap();
    within(admin.close()).await.unwrap();
}

// ---- session ----

shared_test!(idle_session_stays_while_another_client_deletes);
/// Validates C client parity (`Zookeeper_simpleSystem::testPing`): confirms heartbeat ping
/// generation sustains idle sessions beyond session timeout thresholds while registered
/// existence watchers correctly capture out-of-band node deletions triggered by peer sessions.
async fn idle_session_stays_while_another_client_deletes(ctx: Ctx) {
    let idle = ctx.connect().await;
    let creator = ctx.connect().await;
    mk(&creator, &ctx.root).await;
    let paths: Vec<String> = (0..30).map(|i| ctx.path(&format!("/{i}"))).collect();
    for p in &paths {
        mk(&creator, p).await;
    }
    let first = idle.state();
    let (id, _) = idle.session();
    let mut watchers = Vec::new();
    for p in &paths {
        let (st, w) = within(idle.exists_watched(p)).await.unwrap();
        assert!(st.is_some(), "{p}");
        watchers.push(w);
    }

    for p in &paths {
        tokio::time::sleep(Duration::from_millis(200)).await;
        within(creator.delete(p, None)).await.unwrap();
    }

    let now = idle.state();
    assert_eq!(now.state, SessionState::Connected);
    assert_eq!(now.epoch, first.epoch, "the idle client reconnected");
    assert_eq!(idle.session().0, id);
    assert_eq!(within(idle.exists(paths.first().unwrap())).await, Ok(None));
    for (w, p) in watchers.into_iter().zip(&paths) {
        expect_event(w, EventKind::NodeDeleted, p).await;
    }
    within(idle.close()).await.unwrap();
    within(creator.close()).await.unwrap();
}

// ---- chroot ----

shared_test!(chroot_client_sees_paths_below_its_root);
/// Validates C client parity (`Zookeeper_simpleSystem::testChroot`): verifies transparent
/// namespace partitioning when operating within a chrooted path prefix, ensuring operational
/// and watcher event paths reflect paths relative to the designated prefix.
async fn chroot_client_sees_paths_below_its_root(ctx: Ctx) {
    let admin = ctx.connect().await;
    let ch1 = ctx.path("/testch1");
    let base = ctx.path("/testch1/mahadev");
    mk(&admin, &ctx.root).await;
    mk(&admin, &ch1).await;
    within(admin.create(&base, b"garbage", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let c = within(ctx.builder().chroot(base.as_str()).connect())
        .await
        .unwrap();

    let (data, _) = within(c.get_data("/")).await.unwrap();
    assert_eq!(data, b"garbage");

    // Existence watch registered on a non-existent znode fires upon creation.
    let (st, w) = within(c.exists_watched("/chroot")).await.unwrap();
    assert!(st.is_none());
    let (created, _) = within(c.create("/chroot", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(created, "/chroot");
    expect_event(w, EventKind::NodeCreated, "/chroot").await;

    // Mutating operations are transparently scoped beneath the chroot prefix.
    let full_child = format!("{base}/chroot/child");
    mk(&c, "/chroot/child").await;
    assert!(within(admin.exists(&full_child)).await.unwrap().is_some());
    within(c.delete("/chroot/child", None)).await.unwrap();
    assert_eq!(within(admin.exists(&full_child)).await, Ok(None));

    // Data watch triggers on data modification.
    let (_, _, w) = within(c.get_data_watched("/chroot")).await.unwrap();
    within(c.set_data("/chroot", b"gar", None)).await.unwrap();
    expect_event(w, EventKind::NodeDataChanged, "/chroot").await;

    let kids = within(c.get_children("/")).await.unwrap();
    assert_eq!(kids, vec!["chroot".to_owned()]);

    let (acl, _) = within(c.get_acl("/")).await.unwrap();
    assert_eq!(acl.len(), 1, "{acl:?}");
    assert_eq!(acl.first().map(|a| a.perms), Some(Perms::ALL));

    // Child node creation is rejected when parent node permissions are restricted to read-only
    // access.
    within(c.set_acl("/chroot", READ_ACL_UNSAFE, None))
        .await
        .unwrap();
    let r = within(c.create(
        "/chroot/child1",
        b"",
        CreateMode::Persistent,
        OPEN_ACL_UNSAFE,
    ))
    .await;
    assert_eq!(r.err(), Some(Error::NoAuth));

    // Child watch on the root of the chroot hierarchy reports relative path `/`.
    let (_, _, w) = within(c.get_children_watched("/")).await.unwrap();
    mk(&c, "/child2").await;
    expect_event(w, EventKind::NodeChildrenChanged, "/").await;

    for p in ["/child3", "/zookeeper1027", "/zookeeper2282"] {
        let (created, _) = within(c.create(p, b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
            .await
            .unwrap();
        assert_eq!(created, p);
    }
    within(c.close()).await.unwrap();
    within(admin.close()).await.unwrap();
}

// ---- close ----

shared_test!(close_ends_a_task_that_rearms_a_child_watch);
/// Validates C client parity (`Zookeeper_simpleSystem::testHangingClient`). Ensures session
/// termination completes cleanly without deadlock while concurrent worker tasks continuously
/// reregister child watches across events.
async fn close_ends_a_task_that_rearms_a_child_watch(ctx: Ctx) {
    let c = ctx.connect().await;
    let root = ctx.root.clone();
    within(c.create(&*root, b"testall", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (_, _, first) = within(c.get_children_watched(&*root)).await.unwrap();
    let events = Arc::new(AtomicUsize::new(0));
    let task = {
        let c = c.clone();
        let root = root.clone();
        let events = Arc::clone(&events);
        tokio::spawn(async move {
            let mut w = first;
            loop {
                if w.await.is_err() {
                    return;
                }
                events.fetch_add(1, Ordering::SeqCst);
                match c.get_children_watched(&*root).await {
                    Ok((_, _, next)) => w = next,
                    Err(_) => return,
                }
            }
        })
    };

    let child = ctx.path("/test1");
    mk(&c, &child).await;
    within(async {
        while events.load(Ordering::SeqCst) == 0 {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await;
    within(c.delete(&*child, None)).await.unwrap();
    within(c.close()).await.unwrap();
    within(task).await.unwrap();
    assert!(events.load(Ordering::SeqCst) >= 1);
}

// ---- auth ----

/// Validates C client parity (`Zookeeper_simpleSystem::testAuth`). Validates authentication
/// semantics using digest credentials, verifying access enforcement on `creator` ACLs, graceful
/// rejection on invalid credentials, wire sequencing of credentials before dependent reads, and
/// automatic reauthentication upon session reconnection across server restarts.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn digest_auth_gives_access_and_survives_a_restart() {
    let Some((_lock, server)) = dedicated().await else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        let node = format!("/tauth1-{}", rt.name());
        let connect = || async { within(restart_builder(rt, &cs).connect()).await.unwrap() };

        let c1 = connect().await;
        within(c1.add_auth("digest", "pat:passwd")).await.unwrap();
        within(c1.create(&*node, b"", CreateMode::Persistent, CREATOR_ALL_ACL))
            .await
            .unwrap();

        let c4 = connect().await;
        within(c4.add_auth("digest", "")).await.unwrap();
        within(c4.add_auth("digest", "")).await.unwrap();

        let c2 = connect().await;
        within(c2.add_auth("digest", "pat:passwd2")).await.unwrap();
        assert_eq!(
            within(c2.get_data(&*node)).await.map(|_| ()),
            Err(Error::NoAuth)
        );
        // Pipeline ordering guarantees credentials precede subsequent read operations on the wire.
        let auth = c2.add_auth("digest", "pat:passwd");
        let read = c2.get_data(&*node);
        assert!(within(read).await.is_ok());
        within(auth).await.unwrap();

        let c3 = connect().await;
        within(c3.add_auth("digest", "pat:passwd")).await.unwrap();
        within(c3.add_auth("ip", "none")).await.unwrap();
        let c5 = connect().await;

        server.stop_now();
        for c in [&c3, &c5] {
            let mut s = c.state_watcher();
            wait_state(&mut s, WAIT, |i| i.state == SessionState::Connecting).await;
        }
        assert_eq!(c5.connected_server(), None);
        server.start_again().unwrap();
        for c in [&c3, &c5] {
            let mut s = c.state_watcher();
            wait_state(&mut s, RECONNECT_WAIT, |i| i.state.is_connected()).await;
        }
        assert_eq!(c5.connected_server().map(|a| a.port()), Some(server.port()));

        // Client re-submits authenticated credentials upon session reconnect.
        assert!(within(c3.get_data(&*node)).await.is_ok());
        let (acl, _) = within(c3.get_acl("/")).await.unwrap();
        assert_eq!(acl.len(), 1, "{acl:?}");
        assert_eq!(acl.first().map(|a| a.perms), Some(Perms::ALL));
        within(c3.set_acl("/", CREATOR_ALL_ACL, None))
            .await
            .unwrap();
        within(c3.set_acl("/", OPEN_ACL_UNSAFE, None))
            .await
            .unwrap();

        // In accordance with ZOOKEEPER-1108, credentials configured prior to connection
        // establishment are transmitted immediately upon link activation.
        let early = restart_builder(rt, &cs).build().unwrap();
        within_for(RECONNECT_WAIT, early.add_auth("digest", "pat:passwd"))
            .await
            .unwrap();
        assert!(within(early.get_data(&*node)).await.is_ok());

        for c in [c1, c2, c3, c4, c5, early] {
            within(c.close()).await.unwrap();
        }
    }
}

// ---- watches across server restarts ----

/// Validates C client parity (`Zookeeper_simpleSystem::testAsyncWatcherAutoReset`). Evaluates
/// watcher reregistration behavior under heavy in-flight operation volume interrupted by server
/// restarts, ensuring reset watches correctly observe post-recovery znode modifications.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn exists_watches_fire_across_a_restart_with_creates_in_flight() {
    let Some((_lock, server)) = dedicated().await else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        let c = within(restart_builder(rt, &cs).connect()).await.unwrap();
        let path = |i: usize| format!("/awar-{}-{i}", rt.name());

        let arms: Vec<_> = (0..100).map(|i| c.exists_watched(path(i))).collect();
        let mut watchers = Vec::new();
        for f in arms {
            let (st, w) = within(f).await.unwrap();
            assert!(st.is_none());
            watchers.push(w);
        }
        let creates: Vec<_> = (0..50)
            .map(|i| c.create(path(i), b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
            .collect();
        for f in creates {
            within(f).await.unwrap();
        }
        let second = watchers.split_off(50);
        for (i, w) in watchers.into_iter().enumerate() {
            expect_event(w, EventKind::NodeCreated, &path(i)).await;
        }

        let in_flight: Vec<_> = (100..1000)
            .map(|i| {
                let f = c.create(path(i), b"", CreateMode::Persistent, OPEN_ACL_UNSAFE);
                (i, f)
            })
            .collect();
        restart(&server, &[&c]).await;
        for (i, f) in in_flight {
            match within_for(RECONNECT_WAIT, f).await {
                Ok(_) => {}
                Err(Error::ConnectionLoss) => {
                    let r = within(c.create(path(i), b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
                        .await;
                    assert!(
                        matches!(r, Ok(_) | Err(Error::NodeExists)),
                        "{}: {r:?}",
                        path(i)
                    );
                }
                Err(e) => panic!("{}: {e:?}", path(i)),
            }
        }

        // server side watch registrations are invalidated upon restart. Client reregistration
        // enables subsequent events to fire.
        let late: Vec<_> = (51..100)
            .map(|i| c.create(path(i), b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
            .collect();
        for f in late {
            within(f).await.unwrap();
        }

        let mut second = second.into_iter();
        // Retains dormant watcher for non-created target to verify untriggered state.
        let mut w50 = second.next().unwrap();
        for (i, w) in (51..100).zip(second) {
            expect_event(w, EventKind::NodeCreated, &path(i)).await;
        }
        assert!(is_pending(&mut w50).await);
        within(c.close()).await.unwrap();
    }
}

/// Validates C client parity (`Zookeeper_simpleSystem::testWatcherAutoResetWithGlobal` and
/// `testWatcherAutoResetWithLocal`). Confirms child, data, and existence watchers registered in
/// chrooted contexts persist across successive server restarts and fire accurately with prefix-
/// stripped paths while ephemeral nodes persist within the surviving session.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn watches_stay_across_three_server_restarts() {
    let Some((_lock, server)) = dedicated().await else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        let base = format!("/testarw-{}", rt.name());
        let chroot = format!("{base}/arw");
        let admin = within(common::builder(&cs).connect()).await.unwrap();
        mk(&admin, &base).await;
        mk(&admin, &chroot).await;
        within(admin.close()).await.unwrap();

        let c = within(restart_builder(rt, &cs).chroot(chroot.as_str()).connect())
            .await
            .unwrap();
        mk(&c, "/watchtest").await;
        within(c.create(
            "/watchtest/child",
            b"",
            CreateMode::Ephemeral,
            OPEN_ACL_UNSAFE,
        ))
        .await
        .unwrap();

        let (_, _, mut wc) = within(c.get_children_watched("/watchtest")).await.unwrap();
        let (_, _, mut wd) = within(c.get_data_watched("/watchtest/child"))
            .await
            .unwrap();
        let (st, mut we) = within(c.exists_watched("/watchtest/child2")).await.unwrap();
        assert!(st.is_none());
        assert!(is_pending(&mut wc).await);

        restart(&server, &[&c]).await;
        assert!(is_pending(&mut wc).await);
        assert!(is_pending(&mut wd).await);
        assert!(is_pending(&mut we).await);

        within(c.set_data("/watchtest/child", b"1", None))
            .await
            .unwrap();
        let s1 = within(c.set_data("/watchtest/child", b"1", None))
            .await
            .unwrap();
        assert!(s1.version.get() >= 0);
        let s2 = within(c.set_data("/watchtest/child", b"1", s1.version))
            .await
            .unwrap();
        within(c.set_data("/watchtest/child", b"1", s2.version))
            .await
            .unwrap();
        mk(&c, "/watchtest/child2").await;
        expect_event(wd, EventKind::NodeDataChanged, "/watchtest/child").await;
        expect_event(we, EventKind::NodeCreated, "/watchtest/child2").await;
        expect_event(wc, EventKind::NodeChildrenChanged, "/watchtest").await;

        restart(&server, &[&c]).await;
        let (_, _, wc) = within(c.get_children_watched("/watchtest")).await.unwrap();
        let (_, _, mut wd) = within(c.get_data_watched("/watchtest/child"))
            .await
            .unwrap();
        let (st, we) = within(c.exists_watched("/watchtest/child2")).await.unwrap();
        assert!(st.is_some());
        within(c.delete("/watchtest/child2", None)).await.unwrap();
        expect_event(we, EventKind::NodeDeleted, "/watchtest/child2").await;
        expect_event(wc, EventKind::NodeChildrenChanged, "/watchtest").await;
        assert!(is_pending(&mut wd).await);

        restart(&server, &[&c]).await;
        within(c.delete("/watchtest/child", None)).await.unwrap();
        within(c.delete("/watchtest", None)).await.unwrap();
        expect_event(wd, EventKind::NodeDeleted, "/watchtest/child").await;
        within(c.close()).await.unwrap();
    }
}

// ---- zxid ----

/// Validates C client parity (`Zookeeper_simpleSystem::testLastZxid`, ZOOKEEPER-1417). Validates
/// that client-tracked `last_zxid` updates exclusively on transactional request replies, remaining
/// unaffected by heartbeat pings or asynchronous watch event notifications.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn last_zxid_follows_replies_and_not_pings_or_events() {
    let Some((_lock, server)) = dedicated().await else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        let node = format!("/lastzxid-{}", rt.name());
        let zk1 = within(rt.apply(common::builder(&cs)).connect())
            .await
            .unwrap();
        let zk2 = within(rt.apply(common::builder(&cs)).connect())
            .await
            .unwrap();
        let original = zk2.last_zxid();

        mk(&zk1, &node).await;
        // Awaits sufficient interval to trigger at least one periodic ping frame.
        tokio::time::sleep(Duration::from_millis(2_500)).await;

        let st = within(zk1.exists(&*node)).await.unwrap().unwrap();
        assert_eq!(zk1.last_zxid(), st.czxid);
        assert_eq!(zk2.last_zxid(), original);

        let (st, w) = within(zk2.exists_watched(&*node)).await.unwrap();
        let updated = zk2.last_zxid();
        assert_eq!(updated, st.unwrap().czxid);

        within(zk1.set_data(&*node, b"", None)).await.unwrap();
        expect_event(w, EventKind::NodeDataChanged, &node).await;
        assert_eq!(zk2.last_zxid(), updated);

        within(zk1.close()).await.unwrap();
        within(zk2.close()).await.unwrap();
    }
}

// ---- remove watches ----

/// Validates C client parity (`Zookeeper_simpleSystem::testRemoveWatchers`). Verifies
/// watch deregistration across varied `WatcherKind` scopes, asserting appropriate error returns
/// (`NoWatcher`, `ConnectionLoss`) during active and severed server connectivity.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn remove_watches_follow_the_c_results_with_the_server_down() {
    let Some((_lock, server)) = dedicated().await else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        let path = format!("/something-{}", rt.name());
        let path2 = format!("/something2-{}", rt.name());
        let c = within(restart_builder(rt, &cs).connect()).await.unwrap();
        mk(&c, &path).await;
        mk(&c, &path2).await;

        let (_, _, w1) = within(c.get_data_watched(&*path)).await.unwrap();
        let (_, _, w2) = within(c.get_data_watched(&*path)).await.unwrap();
        within(c.remove_watches(&*path, WatcherKind::Any, false))
            .await
            .unwrap();
        within(c.set_data(&*path, b"nowatch", None)).await.unwrap();
        assert_eq!(within(w1).await, Err(WatchError::Removed));
        assert_eq!(within(w2).await, Err(WatchError::Removed));

        assert_eq!(
            within(c.remove_watches(&*path, WatcherKind::Data, false)).await,
            Err(Error::NoWatcher)
        );
        let (_, _, w) = within(c.get_data_watched(&*path)).await.unwrap();
        within(c.remove_watches(&*path, WatcherKind::Data, false))
            .await
            .unwrap();
        assert_eq!(within(w).await, Err(WatchError::Removed));

        assert_eq!(
            within(c.remove_watches(&*path, WatcherKind::Children, false)).await,
            Err(Error::NoWatcher)
        );
        let (_, _, w) = within(c.get_children_watched(&*path)).await.unwrap();
        within(c.remove_watches(&*path, WatcherKind::Children, false))
            .await
            .unwrap();
        assert_eq!(within(w).await, Err(WatchError::Removed));

        // Remote watch removal request fails with ConnectionLoss when link is severed.
        let (_, _, _w) = within(c.get_data_watched(&*path)).await.unwrap();
        server.stop_now();
        let mut s = c.state_watcher();
        wait_state(&mut s, WAIT, |i| i.state == SessionState::Connecting).await;
        assert_eq!(
            within_for(
                RECONNECT_WAIT,
                c.remove_watches(&*path, WatcherKind::Data, false)
            )
            .await,
            Err(Error::ConnectionLoss)
        );
        // Mirrors C test fixture teardown while connection is severed.
        let _ = within_for(RECONNECT_WAIT, c.close()).await;

        server.start_again().unwrap();
        let c = within_for(RECONNECT_WAIT, restart_builder(rt, &cs).connect())
            .await
            .unwrap();
        let (_, _, w1) = within(c.get_data_watched(&*path)).await.unwrap();
        let (_, _, w2) = within(c.get_data_watched(&*path2)).await.unwrap();
        server.stop_now();
        within(c.remove_watches(&*path, WatcherKind::Data, true))
            .await
            .unwrap();
        assert_eq!(within(w1).await, Err(WatchError::Removed));
        assert_eq!(
            within(c.remove_watches(&*path, WatcherKind::Data, true)).await,
            Err(Error::NoWatcher)
        );
        within(c.remove_watches(&*path2, WatcherKind::Data, true))
            .await
            .unwrap();
        assert_eq!(within(w2).await, Err(WatchError::Removed));

        server.start_again().unwrap();
        let mut s = c.state_watcher();
        wait_state(&mut s, RECONNECT_WAIT, |i| i.state.is_connected()).await;
        within(c.close()).await.unwrap();
    }
}

// ---- TLS ----

/// Validates C client parity (`Zookeeper_simpleSystem::testSSLServerOnly`). Validates TLS
/// connectivity against an ensemble configured with `ssl.clientAuth=none`, confirming that
/// certificate authority validation suffices for bidirectional communication without mutual
/// TLS.
#[cfg(feature = "tls-rustls")]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn tls_server_auth_only_accepts_a_client_without_a_certificate() {
    use apache_zookeeper::TlsOptions;
    use parity_g1::{start_or_skip, tls_fixture, tls_props};

    let (Some(port), Some(secure)) = (real_port(TLS_OFFSET), real_port(TLS_SECURE_OFFSET)) else {
        return;
    };
    let dir = common::default_server_dir(port);
    // Ensures an empty state tree for repeatable execution across test runs.
    let _ = std::fs::remove_dir_all(dir.join("data"));
    let props = tls_props(&dir, secure, "none");
    let Some(_server) = start_or_skip(port, None, &props, "") else {
        return;
    };
    let cs = format!("127.0.0.1:{secure}");
    for rt in common::all_rts() {
        let tls = TlsOptions::builder()
            .ca_file(tls_fixture("ca.pem"))
            .build()
            .unwrap();
        let c = within(rt.apply(common::builder(&cs)).tls(tls).connect())
            .await
            .unwrap();
        let p = format!("/ssl-server-only-{}", rt.name());
        mk(&c, &p).await;
        within(c.close()).await.unwrap();
    }
}
