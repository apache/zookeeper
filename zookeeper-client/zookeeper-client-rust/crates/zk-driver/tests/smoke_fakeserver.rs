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

//! End-to-end smoke verification of driver execution against `zk-fakeserver`.
//!
//! Validates fundamental lifecycle workflows across native threaded and single threaded polled
//! runtimes: session connection, transactional znode creation and verification, heartbeat ping
//! exchange, and graceful session closure.

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

use common::{Collect, IpHost, TestSpawner, WAIT, env};
use zk_driver::native::start;
use zk_driver::outbox::{Flavor, Item};
use zk_driver::{Init, InitArgs, InitStep, InterestOut, Outbox, Polled, Step, Unlocked};
use zk_fakeserver::{ClusterConfig, FakeServer, ServerOptions};
use zk_jute::data::{ACL, Id};
use zk_jute::{JBuffer, JString, JVec, opcode};
use zk_proto::rc::ZOK;
use zk_proto::{
    Compat, Config, Connection, DriverMode, InputDefect, LogLevel, LogSite, Millis, Op, PathIn,
    Reply, Request, Route, State,
};
use zk_testkit::hooks::{LevelFilter, SharedDefaultWatcher};
use zk_testkit::rng::SeededRng;
use zk_testkit::tokens::{CompletionId, TestTokens};

/// Negotiated session timeout duration (1,500ms; ping interval approximately 500ms).
const SESSION_MS: i32 = 1_500;

/// Test payload buffer written to smoke znode.
const DATA: &[u8] = b"smoke";

/// Starts a mock ensemble instance configured with accelerated session timeout boundaries.
fn server() -> FakeServer {
    let cfg = ClusterConfig {
        min_session_timeout_ms: 1_000,
        ..ClusterConfig::default()
    };
    FakeServer::start_with(cfg, ServerOptions::default()).unwrap()
}

fn path(native: bool) -> PathIn {
    if native {
        PathIn::Native("/smoke".into())
    } else {
        PathIn::Legacy(Some(b"/smoke".to_vec()))
    }
}

fn create(id: u64, native: bool) -> Request<TestTokens> {
    Request {
        op: Op::Create {
            path: path(native),
            data: JBuffer::Bytes(DATA.to_vec()),
            acl: JVec::from(vec![ACL {
                perms: 31,
                id: Id {
                    scheme: "world".into(),
                    id: "anyone".into(),
                },
            }]),
            mode: 0,
            ttl: -1,
            create2: false,
        },
        token: CompletionId::new(id),
        route: Route::Queued,
        defect: InputDefect::default(),
    }
}

fn get_data(id: u64, native: bool) -> Request<TestTokens> {
    Request {
        op: Op::GetData {
            path: path(native),
            watch: None,
        },
        token: CompletionId::new(id),
        route: Route::Queued,
        defect: InputDefect::default(),
    }
}

/// Verifies receipt and structural correctness of `create` and `getData` response payloads.
fn check_results(items: &[Item<TestTokens>]) {
    let mut created = false;
    let mut read = false;
    for item in items {
        let Item::Result { token, result, .. } = item else {
            continue;
        };
        assert_eq!(result.rc, ZOK, "result of request {}", token.id());
        match (token.id(), &result.reply) {
            (1, Some(Reply::Path(p))) => {
                assert_eq!(*p, JString::from("/smoke"));
                created = true;
            }
            (2, Some(Reply::Data { data, .. })) => {
                assert_eq!(*data, JBuffer::Bytes(DATA.to_vec()));
                read = true;
            }
            other => panic!("unexpected result {other:?}"),
        }
    }
    assert!(created && read, "both results arrive");
}

fn results(items: &[Item<TestTokens>]) -> usize {
    items
        .iter()
        .filter(|i| matches!(i, Item::Result { .. }))
        .count()
}

fn saw_ping(server: &FakeServer) -> bool {
    server
        .wait_for(WAIT, |r| r.op == Some(opcode::PING))
        .is_some()
}

// The native threaded driver.

/// Makes a native connection with the short session timeout and the
/// native close timeout.
fn native_connection(hosts: &str) -> Box<Connection<TestTokens>> {
    let mut cfg = common::legacy_config(hosts);
    cfg.timeout_ms = SESSION_MS;
    cfg.close_timeout_ms = 3_000;
    Box::new(Connection::new(
        cfg,
        Compat::NATIVE,
        DriverMode::NATIVE,
        Box::new(SeededRng::new(1)),
        Box::new(LevelFilter::up_to(LogLevel::Debug)),
        Box::new(SharedDefaultWatcher::new(None)),
        Millis(0),
    ))
}

#[test]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
fn native_driver_creates_reads_pings_and_closes() {
    let server = server();
    let sink = Arc::new(Collect::default());
    let h = start(
        native_connection(&server.connect_string()),
        env(IpHost::new(), true),
        sink.clone(),
        None,
        Arc::new(TestSpawner),
    )
    .unwrap();
    assert!(common::wait_until(WAIT, || h.state() == State::Connected.code()));
    h.submit(create(1, true)).ok().unwrap();
    h.submit(get_data(2, true)).ok().unwrap();
    assert!(common::wait_until(WAIT, || {
        results(&sink.0.lock().unwrap()) == 2
    }));
    // The receiver takes each result, so the read gate stays open.
    h.taken();
    h.taken();
    check_results(&sink.0.lock().unwrap());
    assert_eq!(server.cluster().data("/smoke"), Some(Some(DATA.to_vec())));
    assert!(saw_ping(&server), "the client sends a ping");
    assert_eq!(h.state(), State::Connected.code());
    h.close();
    assert_eq!(h.wait_closed(WAIT), Some(ZOK));
    assert!(
        server
            .transcript()
            .iter()
            .any(|r| r.op == Some(opcode::CLOSE)),
        "the client sends CLOSE_OP"
    );
}

// The st polled step functions.

fn polled(server: &FakeServer) -> Polled<TestTokens> {
    let args = InitArgs {
        cfg: Config::legacy(SESSION_MS),
        host_string: server.connect_string().into_bytes(),
        compat: Compat::LEGACY_C,
        mode: DriverMode::ST,
        flavor: Flavor::St,
    };
    let mut ob = Outbox::new();
    let mut init = Init::new(args, env(IpHost::new(), false), &mut ob).unwrap();
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
    Polled::from_init(init)
}

/// Drives an execution step through outbox continuation loops, collecting emitted items.
fn finish(
    h: &Polled<TestTokens>,
    mut step: Step,
    ob: &mut Outbox<TestTokens>,
    out: &mut InterestOut,
    rc: &mut i32,
    items: &mut Vec<Item<TestTokens>>,
) -> Step {
    loop {
        items.extend(common::drain(ob));
        if step != Step::More {
            return step;
        }
        step = h.resume(ob, out, rc);
    }
}

/// Executes a single discrete iteration of the single threaded application event loop.
fn round(h: &Polled<TestTokens>, items: &mut Vec<Item<TestTokens>>) {
    let mut ob = h.outbox();
    let mut out = InterestOut::UNWRITTEN;
    let mut rc = 0;
    let first = h.interest(&mut ob, &mut out, &mut rc);
    let step = finish(h, first, &mut ob, &mut out, &mut rc, items);
    assert_ne!(step, Step::Release, "no close is in progress");
    if out.fd < 0 {
        return;
    }
    let events = h.wait_events(&out, 50);
    let mut ob = h.outbox();
    let first = h.process(events, &mut ob, &mut rc);
    let step = finish(h, first, &mut ob, &mut out, &mut rc, items);
    assert_ne!(step, Step::Release, "no close is in progress");
}

fn log_pos(items: &[Item<TestTokens>], site: LogSite) -> Option<usize> {
    items
        .iter()
        .position(|i| matches!(i, Item::Log(r) if r.site == site))
}

#[test]
fn st_polled_driver_creates_reads_pings_and_closes() {
    let server = server();
    let h = polled(&server);
    let mut items = Vec::new();
    assert!(common::wait_until(WAIT, || {
        round(&h, &mut items);
        h.state() == State::Connected.code()
    }));
    let mut ob = h.outbox();
    h.submit(create(1, false), &mut ob).ok().unwrap();
    h.submit(get_data(2, false), &mut ob).ok().unwrap();
    items.extend(common::drain(&mut ob));
    assert!(common::wait_until(WAIT, || {
        round(&h, &mut items);
        results(&items) == 2
    }));
    check_results(&items);
    assert_eq!(server.cluster().data("/smoke"), Some(Some(DATA.to_vec())));
    // The application loop runs until the server records a ping.
    assert!(common::wait_until(WAIT, || {
        round(&h, &mut items);
        server
            .transcript()
            .iter()
            .any(|r| r.op == Some(opcode::PING))
    }));
    // One more round reads the ping reply.
    round(&h, &mut items);
    assert_eq!(h.state(), State::Connected.code());
    let mut close_items = Vec::new();
    let mut ob = h.outbox();
    let mut out = InterestOut::UNWRITTEN;
    let mut rc = -1;
    let first = h.close(&mut ob, &mut rc);
    let step = finish(&h, first, &mut ob, &mut out, &mut rc, &mut close_items);
    assert_eq!(step, Step::Release);
    assert_eq!(rc, ZOK);
    let closing = log_pos(&close_items, LogSite::Closing).expect("the Closing line");
    let freeing = log_pos(&close_items, LogSite::Freeing).expect("the Freeing line");
    assert!(closing < freeing);
    assert_eq!(h.state(), 0);
    assert!(
        server
            .transcript()
            .iter()
            .any(|r| r.op == Some(opcode::CLOSE)),
        "the client sends CLOSE_OP"
    );
}
