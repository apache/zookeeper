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

//! Host resolution and observability tests: custom address resolvers, resolver panic fault
//! tolerance, structured tracing log attributes, and the telemetry `Metrics` hook.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    CreateMode, Error, EventKind, Metrics, OpKind, Resolve, Runtime, SessionState, ThreadOptions,
};

/// Mock address resolver mapping a designated hostname to a static `SocketAddr`,
/// recording resolution invocations and simulating initial panic failures.
struct MapResolve {
    name: &'static str,
    addr: SocketAddr,
    panics: usize,
    calls: Arc<Mutex<Vec<(String, u16)>>>,
}

impl MapResolve {
    fn new(name: &'static str, addr: SocketAddr, panics: usize) -> Self {
        Self {
            name,
            addr,
            panics,
            calls: Arc::default(),
        }
    }
}

impl Resolve for MapResolve {
    fn resolve(&self, host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
        let n = {
            let mut calls = self.calls.lock().unwrap();
            calls.push((host.to_owned(), port));
            calls.len()
        };
        if n <= self.panics {
            panic!("resolver bug {n}");
        }
        if host == self.name {
            Ok(vec![self.addr])
        } else {
            Err(io::Error::new(io::ErrorKind::NotFound, "unknown host"))
        }
    }
}

/// Telemetry metric sink recording callback events into an in-memory log.
#[derive(Default)]
struct Recording {
    seen: Mutex<Vec<String>>,
    calls: AtomicUsize,
    panic_always: AtomicBool,
}

impl Recording {
    fn push(&self, s: String) {
        self.calls.fetch_add(1, Ordering::SeqCst);
        assert!(!self.panic_always.load(Ordering::SeqCst), "metrics bug");
        self.seen.lock().unwrap().push(s);
    }

    fn seen(&self) -> Vec<String> {
        self.seen.lock().unwrap().clone()
    }
}

/// Thread-safe adapter delegating `Metrics` callbacks to an underlying `Recording` handle.
struct Shared(Arc<Recording>);

impl Metrics for Shared {
    fn request_completed(&self, op: OpKind, result: Result<(), &Error>, _latency: Duration) {
        let r = match result {
            Ok(()) => "ok".to_owned(),
            Err(e) => format!("{e:?}"),
        };
        self.0.push(format!("req {op:?} {r}"));
    }
    fn state_changed(&self, state: SessionState) {
        self.0.push(format!("state {state:?}"));
    }
    fn watch_event(&self, kind: EventKind) {
        self.0.push(format!("watch {kind:?}"));
    }
    fn pending_requests(&self, n: usize) {
        self.0.push(format!("pending {n}"));
    }
}

fn thread_runtime() -> Runtime {
    Runtime::Thread(ThreadOptions::default())
}

#[tokio::test(flavor = "multi_thread")]
async fn a_custom_resolver_maps_a_name_to_the_server() {
    let server = common::fake();
    let resolver = MapResolve::new("zk.invalid", server.addr(), 0);
    let calls = Arc::clone(&resolver.calls);
    let client = common::builder("zk.invalid:2999")
        .resolver(resolver)
        .runtime(thread_runtime())
        .connect()
        .await
        .unwrap();
    assert!(client.exists("/").await.unwrap().is_some());
    assert_eq!(client.connected_server(), Some(server.addr()));
    let calls = calls.lock().unwrap().clone();
    assert!(!calls.is_empty());
    assert!(calls.iter().all(|c| *c == ("zk.invalid".to_owned(), 2999)));
    client.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_failed_host_is_skipped_and_the_next_host_connects() {
    let server = common::fake();
    let resolver = MapResolve::new("good.invalid", server.addr(), 0);
    let client = common::builder("bad.invalid:1,good.invalid:2")
        .resolver(resolver)
        .server_order(apache_zookeeper::ServerOrder::AsGiven)
        .runtime(thread_runtime())
        .connect()
        .await
        .unwrap();
    assert!(client.exists("/").await.unwrap().is_some());
    client.close().await.unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn a_resolver_panic_fails_the_host_and_the_client_retries() {
    let server = common::fake();
    let resolver = MapResolve::new("zk.invalid", server.addr(), 1);
    let calls = Arc::clone(&resolver.calls);
    let client = tokio::time::timeout(
        common::WAIT,
        common::builder("zk.invalid:2181")
            .resolver(resolver)
            .runtime(thread_runtime())
            .connect(),
    )
    .await
    .expect("connect after the panic")
    .unwrap();
    assert!(calls.lock().unwrap().len() >= 2);
    assert!(client.exists("/").await.unwrap().is_some());
    client.close().await.unwrap();
}

#[cfg(all(unix, feature = "tokio"))]
#[tokio::test(flavor = "multi_thread")]
async fn the_tokio_driver_uses_the_custom_resolver() {
    let server = common::fake();
    let resolver = MapResolve::new("zk.invalid", server.addr(), 1);
    let calls = Arc::clone(&resolver.calls);
    let client = tokio::time::timeout(
        common::WAIT,
        common::builder("zk.invalid:2181")
            .resolver(resolver)
            .runtime(Runtime::Tokio(tokio::runtime::Handle::current()))
            .connect(),
    )
    .await
    .expect("connect after the panic")
    .unwrap();
    assert!(calls.lock().unwrap().len() >= 2);
    assert!(client.exists("/").await.unwrap().is_some());
    client.close().await.unwrap();
}

/// Invariant: attempting a blocking wait within an address resolver callback fails immediately
/// with `Error::InvalidState`, preventing executor thread deadlock.
#[cfg(feature = "blocking")]
#[test]
fn a_blocking_wait_inside_the_resolver_is_refused() {
    struct Blocking {
        helper: apache_zookeeper::Client,
        addr: SocketAddr,
        outcome: Arc<Mutex<Option<Result<bool, Error>>>>,
    }
    impl Resolve for Blocking {
        fn resolve(&self, _host: &str, _port: u16) -> io::Result<Vec<SocketAddr>> {
            let r = self.helper.exists("/").wait().map(|s| s.is_some());
            self.outcome.lock().unwrap().get_or_insert(r);
            Ok(vec![self.addr])
        }
    }
    let server = common::fake();
    let helper = common::builder(&server.connect_string())
        .runtime(thread_runtime())
        .connect()
        .wait()
        .unwrap();
    let outcome = Arc::default();
    let client = common::builder("zk.invalid:2181")
        .resolver(Blocking {
            helper: helper.clone(),
            addr: server.addr(),
            outcome: Arc::clone(&outcome),
        })
        .runtime(thread_runtime())
        .connect()
        .wait()
        .unwrap();
    assert_eq!(*outcome.lock().unwrap(), Some(Err(Error::InvalidState)));
    client.close().wait().unwrap();
    helper.close().wait().unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn the_metrics_hook_sees_requests_states_and_watches() {
    let server = common::fake();
    let rec = Arc::new(Recording::default());
    let client = common::builder(&server.connect_string())
        .metrics(Shared(Arc::clone(&rec)))
        .runtime(thread_runtime())
        .connect()
        .await
        .unwrap();
    let missing = client.get_data("/missing").await;
    assert_eq!(missing.unwrap_err(), Error::NoNode);
    let (_, w) = client.exists_watched("/w").await.unwrap();
    client
        .create("/w", b"x", CreateMode::Persistent, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    let ev = tokio::time::timeout(common::WAIT, w)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(ev.kind, EventKind::NodeCreated);
    client.close().await.unwrap();

    let seen = rec.seen();
    assert!(seen.iter().any(|s| s == "state Connected"), "{seen:?}");
    assert!(seen.iter().any(|s| s == "req GetData NoNode"), "{seen:?}");
    assert!(seen.iter().any(|s| s == "req Exists ok"), "{seen:?}");
    assert!(seen.iter().any(|s| s == "req Create ok"), "{seen:?}");
    assert!(seen.iter().any(|s| s == "watch NodeCreated"), "{seen:?}");
    assert!(seen.iter().any(|s| s.starts_with("pending ")), "{seen:?}");
    assert!(seen.iter().any(|s| s == "state Closed"), "{seen:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_panicking_metrics_hook_is_disabled_and_the_client_works() {
    let server = common::fake();
    let rec = Arc::new(Recording::default());
    rec.panic_always.store(true, Ordering::SeqCst);
    let client = common::builder(&server.connect_string())
        .metrics(Shared(Arc::clone(&rec)))
        .runtime(thread_runtime())
        .connect()
        .await
        .unwrap();
    for _ in 0..3 {
        assert!(client.exists("/").await.unwrap().is_some());
    }
    client.close().await.unwrap();
    // Following the initial panic, the faulty metrics sink was disabled; subsequent operations
    // continue unaffected.
    assert_eq!(rec.calls.load(Ordering::SeqCst), 1);
    assert!(rec.seen().is_empty());
}

/// Invariant: configured client identifier strings are propagated into structured tracing metadata
/// fields.
#[cfg(feature = "tracing")]
#[tokio::test(flavor = "multi_thread")]
async fn records_carry_the_client_name_and_a_crate_target() {
    use tracing::field::{Field, Visit};
    use tracing::span::{Attributes, Id, Record};
    use tracing::{Event, Metadata, Subscriber};

    type Seen = Arc<Mutex<Vec<(String, Option<String>)>>>;

    struct Client(Option<String>);
    impl Visit for Client {
        fn record_str(&mut self, field: &Field, value: &str) {
            if field.name() == "client" {
                self.0 = Some(value.to_owned());
            }
        }
        fn record_debug(&mut self, _: &Field, _: &dyn std::fmt::Debug) {}
    }

    struct Capture(Seen);
    impl Subscriber for Capture {
        fn enabled(&self, m: &Metadata<'_>) -> bool {
            m.target().starts_with("apache_zookeeper")
        }
        fn new_span(&self, _: &Attributes<'_>) -> Id {
            Id::from_u64(1)
        }
        fn record(&self, _: &Id, _: &Record<'_>) {}
        fn record_follows_from(&self, _: &Id, _: &Id) {}
        fn event(&self, e: &Event<'_>) {
            let mut c = Client(None);
            e.record(&mut c);
            self.0
                .lock()
                .unwrap()
                .push((e.metadata().target().to_owned(), c.0));
        }
        fn enter(&self, _: &Id) {}
        fn exit(&self, _: &Id) {}
    }

    let seen: Seen = Arc::default();
    tracing::subscriber::set_global_default(Capture(Arc::clone(&seen))).unwrap();
    let server = common::fake();
    let client = common::builder(&server.connect_string())
        .name("probe")
        .runtime(thread_runtime())
        .connect()
        .await
        .unwrap();
    assert!(client.exists("/").await.unwrap().is_some());
    client.close().await.unwrap();

    // Concurrent test executions may emit log events without client identifiers;
    // verify that explicitly identified records match the configured name.
    let seen = seen.lock().unwrap();
    for (target, name) in seen.iter() {
        assert!(
            target == "apache_zookeeper"
                || [
                    "apache_zookeeper::session",
                    "apache_zookeeper::request",
                    "apache_zookeeper::watch",
                    "apache_zookeeper::proto"
                ]
                .contains(&target.as_str()),
            "{target}"
        );
        if let Some(name) = name {
            assert_eq!(name, "probe", "{target}");
        }
    }
    assert!(
        seen.iter()
            .any(|(t, n)| t == "apache_zookeeper::session" && n.as_deref() == Some("probe"))
    );
}
