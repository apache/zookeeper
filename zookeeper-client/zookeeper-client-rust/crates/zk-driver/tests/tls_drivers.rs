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

//! Multi-flavor driver runtime verification over TLS transports.
//!
//! Validates end-to-end TLS session lifecycles across native, multithreaded (`mt`),
//! and asynchronous Tokio driver implementations:
//! - Secure session establishment and protocol framing over encrypted transports.
//! - Cryptographic session termination emitting RFC 5246 / RFC 8446 `close_notify` alerts.
//! - Strict hostname and SAN validation under WebPKI verification rules.
//! - Transport failure mapping and diagnostic event dispatch on cryptographic handshake abort.

#![cfg(all(unix, feature = "tls"))]
#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::Arc;
use std::sync::mpsc;
use std::thread::JoinHandle;
use std::time::Duration;

use common::tls::{legacy_connector, native_connector, provider};
use common::{
    Collect, CountingPoll, IpHost, TestSpawner, WAIT, env, exists_at, fixture, legacy_config,
};
use zk_driver::native::start;
use zk_driver::outbox::{Flavor, Item};
use zk_driver::{Init, InitArgs, InitStep, Outbox, Poll, Role, Shared, Step, Unlocked};
use zk_fakeserver::{ClusterConfig, FakeServer, PeerEnd, ServerOptions, TlsAcceptor};
use zk_proto::rc::ZOK;
use zk_proto::{Compat, Config, Connection, DriverMode, LogLevel, LogSite, PathIn, State};
use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
use zk_testkit::rng::SeededRng;
use zk_testkit::tokens::TestTokens;

/// Configured session timeout threshold (milliseconds).
const T: i32 = 3_000;

/// Spawns an in-process TLS fake server configured with specified leaf credentials.
fn tls_server(leaf: &str) -> (FakeServer, Arc<TlsAcceptor>) {
    let acc = TlsAcceptor::builder(provider())
        .cert_chain_pem_file(fixture(&format!("{leaf}.pem")))
        .unwrap()
        .key_pem_file(fixture(&format!("{leaf}.key")))
        .unwrap()
        .close_notify(false)
        .linger(Duration::from_secs(2))
        .build()
        .unwrap();
    let opts = ServerOptions::default().with_acceptor(Arc::clone(&acc) as _);
    let server = FakeServer::start_with(ClusterConfig::default(), opts).unwrap();
    (server, acc)
}

/// Asserts that the primary server connection terminated with a cryptographic `close_notify`
/// alert.
fn assert_close_notify(acc: &TlsAcceptor) {
    assert!(acc.wait_sessions(WAIT, |s| s.first().is_some_and(|s| s.peer_end.is_some())));
    assert_eq!(acc.sessions()[0].peer_end, Some(PeerEnd::CloseNotify));
}

/// Constructs a native [`Connection`] specification configured for TLS transport.
fn native_tls_connection(hosts: &str) -> Box<Connection<TestTokens>> {
    let mut cfg = legacy_config(hosts);
    cfg.timeout_ms = T;
    cfg.close_timeout_ms = 3_000;
    cfg.tls = true;
    Box::new(Connection::new(
        cfg,
        Compat::NATIVE,
        DriverMode::NATIVE,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        zk_proto::Millis(0),
    ))
}

/// Initializes a native driver execution environment configured with WebPKI TLS connectors.
fn native_env() -> zk_driver::Env<TestTokens> {
    let mut e = env(IpHost::new(), true);
    e.tls = Some(native_connector());
    e
}

/// Asserts that native WebPKI drivers establish TLS sessions and emit `close_notify` upon
/// shutdown.
#[test]
fn native_webpki_session_closes_with_close_notify() {
    let (server, acc) = tls_server("server");
    let sink = Arc::new(Collect::default());
    let h = start(
        native_tls_connection(&server.connect_string()),
        native_env(),
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    assert!(common::wait_until(WAIT, || h.state() == State::Connected.code()));
    h.submit(exists_at(1, PathIn::Native("/".into())))
        .ok()
        .unwrap();
    assert!(common::wait_until(WAIT, || sink.result_rc().is_some()));
    assert_eq!(sink.result_rc(), Some(ZOK));
    h.taken();
    h.close();
    assert_eq!(h.wait_closed(WAIT), Some(ZOK));
    assert_close_notify(&acc);
}

/// Asserts that hostname mismatches under WebPKI fail the TLS handshake and emit log events.
#[test]
fn native_webpki_rejects_a_host_name_mismatch() {
    let (server, acc) = tls_server("other-name");
    let sink = Arc::new(Collect::default());
    let h = start(
        native_tls_connection(&server.connect_string()),
        native_env(),
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    assert!(acc.wait_sessions(WAIT, |s| {
        s.first()
            .is_some_and(|s| matches!(s.handshake, Some(Err(_))))
    }));
    assert!(common::wait_until(WAIT, || sink.has_log(LogSite::SockTlsConnect)));
    assert_ne!(h.state(), State::Connected.code());
    h.close();
    assert!(h.wait_closed(WAIT).is_some());
}

#[cfg(feature = "tokio")]
/// Asserts that asynchronous Tokio drivers execute TLS sessions and emit `close_notify` upon
/// termination.
#[test]
fn tokio_session_closes_with_close_notify() {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let (server, acc) = tls_server("server");
    let sink = Arc::new(Collect::default());
    let h = zk_driver::tokio::spawn(
        native_tls_connection(&server.connect_string()),
        native_env(),
        sink.clone(),
        None,
        rt.handle(),
    );
    let rc = rt.block_on(async {
        let start = tokio::time::Instant::now();
        while h.state() != State::Connected.code() {
            assert!(start.elapsed() < WAIT, "no session");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        h.submit(exists_at(1, PathIn::Native("/".into())))
            .ok()
            .unwrap();
        while sink.result_rc().is_none() {
            assert!(start.elapsed() < WAIT, "no result");
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        h.close();
        h.closed().await
    });
    assert_eq!(sink.result_rc(), Some(ZOK));
    assert_eq!(rc, ZOK);
    assert_close_notify(&acc);
}

/// Initializes a multithreaded shared driver state machine configured for TLS.
fn mt_handle(server: &FakeServer) -> Arc<Shared<TestTokens>> {
    mt_handle_with(server, None)
}

/// Initializes a multithreaded drop-in driver configured with the legacy TLS connector and an
/// optional custom poll backend.
fn mt_handle_with(server: &FakeServer, poll: Option<Arc<dyn Poll>>) -> Arc<Shared<TestTokens>> {
    let mut cfg = Config::legacy(T);
    cfg.tls = true;
    let args = InitArgs {
        cfg,
        host_string: server.connect_string().into_bytes(),
        compat: Compat::LEGACY_C,
        mode: DriverMode::MT,
        flavor: Flavor::Mt,
    };
    let mut e = env(IpHost::new(), false);
    e.tls = Some(legacy_connector());
    if let Some(p) = poll {
        e.poll = p;
    }
    let mut ob = Outbox::new();
    let mut init = Init::new(args, e, &mut ob).unwrap();
    let mut proof = Unlocked::new_at_entry();
    loop {
        match init.step(&mut proof, &mut ob) {
            InitStep::More | InitStep::SaslPoint => {
                common::drain(&mut ob);
            }
            InitStep::Ready => break,
            InitStep::Failed(e) => panic!("init failed: {e:?}"),
        }
    }
    Shared::from_init(init, &mut ob).unwrap()
}

/// Drives multithreaded state transitions to completion, draining emitted outbox items to a
/// channel sink.
fn drive(
    shared: &Shared<TestTokens>,
    first: impl FnOnce(&mut Outbox<TestTokens>, &mut i32) -> Step,
    sink: &mpsc::Sender<Item<TestTokens>>,
) -> Step {
    let mut ob = shared.outbox();
    let mut rc = 0;
    let mut step = first(&mut ob, &mut rc);
    loop {
        while let Some(item) = ob.pop() {
            let _ = sink.send(item);
        }
        if step != Step::More {
            return step;
        }
        step = shared.resume(&mut ob, &mut rc);
    }
}

/// Spawns background I/O and completion worker threads for a multithreaded handle, routing
/// dispatched outbox items to the returned channel receiver.
fn start_mt_threads(
    shared: &Arc<Shared<TestTokens>>,
) -> (mpsc::Receiver<Item<TestTokens>>, [JoinHandle<()>; 2]) {
    let (tx, rx) = mpsc::channel();
    shared.thread_enter(Role::Io);
    shared.thread_enter(Role::Dispatch);
    let io = {
        let s = Arc::clone(shared);
        let tx = tx.clone();
        std::thread::spawn(move || {
            while drive(&s, |ob, rc| s.io_step(ob, rc), &tx) != Step::Exit {}
            drive(&s, |ob, rc| s.thread_exit(Role::Io, ob, rc), &tx);
        })
    };
    let dispatch = {
        let s = Arc::clone(shared);
        std::thread::spawn(move || {
            loop {
                let mut ob = s.outbox();
                if s.wait_dispatch(&mut ob) == Step::Exit {
                    break;
                }
                while let Some(item) = ob.pop() {
                    let _ = tx.send(item);
                }
            }
            // Terminal worker thread executes final cleanup during epilog processing.
            drive(&s, |ob, rc| s.thread_exit(Role::Dispatch, ob, rc), &tx);
        })
    };
    (rx, [io, dispatch])
}

/// Asserts that multithreaded I/O and dispatch workers manage TLS session execution and emit
/// `close_notify` on close.
#[test]
fn mt_session_closes_with_close_notify() {
    let (server, acc) = tls_server("server");
    let shared = mt_handle(&server);
    let (rx, [io, dispatch]) = start_mt_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
    shared
        .submit(exists_at(1, PathIn::Legacy(Some(b"/".to_vec()))))
        .ok()
        .unwrap();
    let got = std::iter::from_fn(|| rx.recv_timeout(WAIT).ok())
        .find(|i| matches!(i, Item::Result { .. }))
        .unwrap();
    assert!(matches!(got, Item::Result { result, .. } if result.rc == ZOK));
    let (tx, _keep) = mpsc::channel();
    let end = drive(&shared, |ob, rc| shared.close(Role::Foreign, ob, rc), &tx);
    assert!(matches!(end, Step::Done(_) | Step::Release));
    io.join().unwrap();
    dispatch.join().unwrap();
    assert_close_notify(&acc);
}

/// Verifies that once the TLS handshake is established, submitting a request to an idle handle
/// flushes the encrypted frame during the immediate waker wakeup pass, completing the round-trip
/// in two ready poll events.
#[test]
fn mt_tls_request_goes_out_in_the_pass_of_its_wake() {
    let (server, acc) = tls_server("server");
    let poll = Arc::new(CountingPoll::default());
    let shared = mt_handle_with(&server, Some(Arc::clone(&poll) as Arc<dyn Poll>));
    let (rx, [io, dispatch]) = start_mt_threads(&shared);
    assert!(common::wait_until(WAIT, || shared.state() == State::Connected.code()));
    assert!(poll.wait_idle(WAIT));
    let before = poll.ready();
    shared
        .submit(exists_at(1, PathIn::Legacy(Some(b"/".to_vec()))))
        .ok()
        .unwrap();
    let got = std::iter::from_fn(|| rx.recv_timeout(WAIT).ok())
        .find(|i| matches!(i, Item::Result { .. }))
        .unwrap();
    assert!(matches!(got, Item::Result { result, .. } if result.rc == ZOK));
    assert_eq!(poll.ready() - before, 2);
    let (tx, _keep) = mpsc::channel();
    let end = drive(&shared, |ob, rc| shared.close(Role::Foreign, ob, rc), &tx);
    assert!(matches!(end, Step::Done(_) | Step::Release));
    io.join().unwrap();
    dispatch.join().unwrap();
    assert_close_notify(&acc);
}
