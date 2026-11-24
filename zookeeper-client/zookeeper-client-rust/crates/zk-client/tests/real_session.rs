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

//! Session lifecycle integration tests against real ZooKeeper server instances.
//!
//! Validates session resumption, expiration semantics, disconnect cause classification,
//! client detachment, and graceful session termination.
//!
//! Tests share a dedicated real server instance running at port offset 13. Scenarios requiring
//! transport partition injection interpose a [`Proxy`] layer in front of the server.
//! Run with: `ZK_TEST_PORT_BASE=<base> cargo test --test real_session -- --ignored`.

// Test scenarios execute extended asynchronous flows on dedicated worker threads;
// stack size limits configured for the core library do not apply.
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::large_stack_frames
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::io::ErrorKind;
use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    AddWatchMode, Client, ClientBuilder, CreateMode, DisconnectCause, EphemeralKind, Error,
    EventKind, SessionInfo, SessionState, WatchError,
};
use common::proxy::Proxy;
use common::{Ctx, Rt, SharedServer, WAIT, setup, wait_state, within};

/// Real server instance backing session lifecycle tests in this module.
static SERVER: SharedServer = SharedServer::new(13);

/// Executes a test scenario across both OS thread and Tokio drivers on a multithreaded runtime.
macro_rules! real_test {
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

/// Constructs a ClientBuilder configured to route through the specified proxy using the context
/// driver.
fn via(ctx: &Ctx, proxy: &Proxy) -> ClientBuilder {
    ctx.rt.apply(common::builder(&proxy.connect_string()))
}

/// Returns true if the session disconnect info indicates peer initiated termination,
/// matching either `DisconnectCause::ServerClosed` or a transport reset error.
fn peer_closed(info: &SessionInfo) -> bool {
    common::disconnect_cause(info) == Some(DisconnectCause::ServerClosed)
        || matches!(
            common::io_error_kind(info),
            Some(ErrorKind::ConnectionReset | ErrorKind::BrokenPipe)
        )
}

/// Helper function creating an empty ephemeral znode at the specified path.
async fn ephemeral(c: &Client, path: &str) {
    within(c.create(path, b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
}

real_test!(detach_keeps_the_session);
/// Invariant: invoking `detach` terminates in-flight pending requests with `Error::Closing`,
/// concludes active watchers with `WatchError::Closed`, and yields session credentials without
/// invalidating the server side session. The session and its ephemeral znodes may subsequently
/// be resumed using `ClientBuilder::session`.
async fn detach_keeps_the_session(ctx: Ctx) {
    let admin = setup(&ctx).await;
    let eph = ctx.path("/eph");
    let proxy = Proxy::start(ctx.port());
    let c = within(via(&ctx, &proxy).connect()).await.unwrap();
    ephemeral(&c, &eph).await;
    let (_, _, w) = within(c.get_data_watched(&eph)).await.unwrap();
    let mut p = within(c.add_watch(&ctx.root, AddWatchMode::Persistent))
        .await
        .unwrap();
    let mut s = c.state_watcher();

    // Holding the proxy halts packet forwarding, leaving dispatched requests pending.
    proxy.hold();
    let pending = c.get_data(&eph);
    let (id, pw) = c.detach();
    assert_eq!(within(pending).await, Err(Error::Closing));
    assert_eq!(within(w).await, Err(WatchError::Closed));
    assert_eq!(within(p.next()).await, Some(Err(WatchError::Closed)));
    assert_eq!(within(p.next()).await, None);
    wait_state(&mut s, WAIT, |i| i.state == SessionState::Closed).await;

    // The server side session remains valid, preserving associated ephemeral znodes.
    let (_, st) = within(admin.get_data(&eph)).await.unwrap();
    assert_eq!(st.ephemeral_kind(), EphemeralKind::Session(id));
    let r = within(ctx.builder().session(id, pw).connect())
        .await
        .unwrap();
    assert_eq!(r.session().0, id);
    let mine = within(r.get_ephemerals(&ctx.root)).await.unwrap();
    assert_eq!(mine.len(), 1);
    assert_eq!(mine.first().map(|p| p.as_str()), Some(eph.as_str()));
    // Graceful close explicitly terminates the server session, immediately deleting ephemerals.
    within(r.close()).await.unwrap();
    assert_eq!(within(admin.exists(&eph)).await, Ok(None));
}

real_test!(close_drains_then_ends);
/// Invariant: graceful shutdown via `close` allows currently queued requests to drain and
/// complete, subsequently rejects newly submitted operations with `Error::Closing`, and
/// dispatches `WatchError::Closed` to active watchers. Associated ephemeral nodes are purged
/// immediately.
async fn close_drains_then_ends(ctx: Ctx) {
    let admin = setup(&ctx).await;
    let eph = ctx.path("/eph");
    let c = ctx.connect().await;
    ephemeral(&c, &eph).await;
    let (_, w) = within(c.exists_watched(ctx.path("/none"))).await.unwrap();
    // A persistent recursive watch on an unmutated path receives no events prior to closure.
    let mut p = within(c.add_watch(ctx.path("/quiet"), AddWatchMode::PersistentRecursive))
        .await
        .unwrap();
    let clone = c.clone();
    let queued = c.set_data(&eph, b"last", None);
    let first = c.close();
    assert_eq!(within(c.get_data(&eph)).await, Err(Error::Closing));
    assert!(within(queued).await.is_ok());
    assert_eq!(within(first).await, Ok(()));
    assert_eq!(within(clone.close()).await, Ok(()));
    assert_eq!(c.state().state, SessionState::Closed);
    assert_eq!(within(w).await, Err(WatchError::Closed));
    assert_eq!(within(p.next()).await, Some(Err(WatchError::Closed)));
    assert_eq!(within(p.next()).await, None);
    assert_eq!(within(admin.exists(&eph)).await, Ok(None));
}

real_test!(resume_moves_the_session);
/// Invariant: a session detached or disconnected from one client handle may be resumed by another
/// client configured with matching `SessionId` and `SessionPassword`. Negotiated timeouts and
/// owned ephemeral znodes are retained, and newly registered watchers receive change
/// notifications.
async fn resume_moves_the_session(ctx: Ctx) {
    let admin = setup(&ctx).await;
    let eph = ctx.path("/eph");
    let a = ctx.connect().await;
    ephemeral(&a, &eph).await;
    let (id, pw) = a.detach();
    let b = within(ctx.builder().session(id, pw.clone()).connect())
        .await
        .unwrap();
    assert_eq!(b.session(), (id, pw));
    assert!(b.negotiated_timeout().is_some());
    // Resumed client possesses ownership of ephemeral nodes created by the original session.
    within(b.delete(&eph, None)).await.unwrap();
    ephemeral(&b, &eph).await;
    let (_, st) = within(admin.get_data(&eph)).await.unwrap();
    assert_eq!(st.ephemeral_kind(), EphemeralKind::Session(id));
    let (_, w) = within(b.exists_watched(ctx.path("/n"))).await.unwrap();
    within(admin.create(ctx.path("/n"), b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(within(w).await.unwrap().kind, EventKind::NodeCreated);
    within(b.close()).await.unwrap();
    assert_eq!(within(admin.exists(&eph)).await, Ok(None));
}

real_test!(expiry_ends_everything);
/// Invariant: session expiration transitions the client state to `SessionState::Expired`,
/// fails one-shot watchers and active requests with `SessionExpired`, and emits a final
/// `WatchError::SessionExpired` on persistent streams before stream termination.
/// Expired sessions cannot be resumed.
///
/// The test simulates expiration deterministically: proxy partition isolates client A,
/// client B resumes the same session and executes an explicit close, and client A subsequently
/// reconnects.
async fn expiry_ends_everything(ctx: Ctx) {
    let admin = setup(&ctx).await;
    let eph = ctx.path("/eph");
    let proxy = Proxy::start(ctx.port());
    let a = within(via(&ctx, &proxy).connect()).await.unwrap();
    ephemeral(&a, &eph).await;
    let (_, _, w) = within(a.get_data_watched(&ctx.root)).await.unwrap();
    let mut p = within(a.add_watch(&ctx.root, AddWatchMode::Persistent))
        .await
        .unwrap();
    let mut s = a.state_watcher();
    let (id, pw) = a.session();

    proxy.cut();
    let info = wait_state(&mut s, WAIT, |i| i.state == SessionState::Connecting).await;
    assert!(info.disconnected_since.is_some(), "{info:?}");
    let b = within(ctx.builder().session(id, pw.clone()).connect())
        .await
        .unwrap();
    within(b.close()).await.unwrap();
    // Session termination deletes ephemeral znodes. Client A receives no watch events while
    // disconnected.
    assert_eq!(within(admin.exists(&eph)).await, Ok(None));
    proxy.restore();

    let info = wait_state(&mut s, WAIT, |i| i.state.is_terminal()).await;
    assert_eq!(info.state, SessionState::Expired, "{info:?}");
    assert_eq!(within(w).await, Err(WatchError::SessionExpired));
    assert_eq!(
        within(p.next()).await,
        Some(Err(WatchError::SessionExpired))
    );
    assert_eq!(within(p.next()).await, None);
    assert_eq!(within(a.get_data(&eph)).await, Err(Error::SessionExpired));
    assert_eq!(
        within(a.add_watch(&ctx.root, AddWatchMode::Persistent))
            .await
            .unwrap_err(),
        Error::SessionExpired
    );
    // Invoking close on an already terminal session completes immediately without generating
    // network traffic.
    let _ = within(a.close()).await;

    let e = within(ctx.builder().session(id, pw).connect())
        .await
        .unwrap_err();
    assert_eq!(e.error(), &Error::SessionExpired);
}

real_test!(teardown_causes);
/// Invariant: `SessionInfo::last_error` accurately records the diagnostic reason for connection
/// loss: peer socket shutdown yields `ServerClosed`, heartbeat/read silence exceeding two-thirds
/// of the session timeout yields `ReceiveTimeout`, and each reconnection monotonically increments
/// the epoch.
async fn teardown_causes(ctx: Ctx) {
    let proxy = Proxy::start(ctx.port());
    let c = within(
        via(&ctx, &proxy)
            .session_timeout(Duration::from_secs(2))
            .connect(),
    )
    .await
    .unwrap();
    let mut s = c.state_watcher();
    let first = s.current();
    assert_eq!((first.state, first.epoch), (SessionState::Connected, 1));
    assert!(first.disconnected_since.is_none());

    proxy.cut();
    let info = wait_state(&mut s, WAIT, |i| i.state == SessionState::Connecting).await;
    assert!(peer_closed(&info), "{info:?}");
    assert!(info.disconnected_since.is_some());
    // Requests submitted while transport is partitioned fail with ConnectionLoss upon connection
    // failure.
    assert_eq!(
        within(c.exists(&ctx.root)).await,
        Err(Error::ConnectionLoss)
    );
    proxy.restore();
    let info = wait_state(&mut s, WAIT, |i| i.state == SessionState::Connected).await;
    assert_eq!(info.epoch, 2);
    assert!(info.disconnected_since.is_none());

    // Holding the proxy prevents transmission of heartbeat ping responses.
    proxy.hold();
    let info = wait_state(&mut s, WAIT, |i| i.state == SessionState::Connecting).await;
    assert_eq!(
        common::disconnect_cause(&info),
        Some(DisconnectCause::ReceiveTimeout),
        "{info:?}"
    );
    proxy.restore();
    // Depending on timing, the server may expire the session due to missed heartbeat pings.
    let info = wait_state(&mut s, WAIT, |i| {
        i.state == SessionState::Connected || i.state.is_terminal()
    })
    .await;
    if info.state == SessionState::Connected {
        assert_eq!(info.epoch, 3);
    } else {
        assert_eq!(info.state, SessionState::Expired);
    }
    let _ = within(c.close()).await;
}
