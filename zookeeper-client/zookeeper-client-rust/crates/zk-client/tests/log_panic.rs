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

//! Verification of session resilience when the active logging backend panics during emission.
//!
//! Because logging subscribers and facades are process-global singletons, these tests execute in a
//! dedicated integration test binary.

#![cfg(any(feature = "tracing", feature = "log"))]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::OnceLock;
use std::sync::atomic::{AtomicUsize, Ordering};

use apache_zookeeper::CreateMode;
use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use common::{Rt, all_rts, within};

/// Sentinel panic payload emitted by the fault-injecting logging backend.
const MESSAGE: &str = "test log backend panics on each record";

/// Cumulative count of log records dispatched to the fault-injecting backend.
static PANICS: AtomicUsize = AtomicUsize::new(0);

#[cfg(feature = "tracing")]
mod capture {
    use tracing::span::{Attributes, Id, Record};
    use tracing::{Event, Metadata, Subscriber};

    struct Panicking;

    impl Subscriber for Panicking {
        fn enabled(&self, m: &Metadata<'_>) -> bool {
            m.target().starts_with("apache_zookeeper")
        }
        fn new_span(&self, _: &Attributes<'_>) -> Id {
            Id::from_u64(1)
        }
        fn record(&self, _: &Id, _: &Record<'_>) {}
        fn record_follows_from(&self, _: &Id, _: &Id) {}
        fn event(&self, _: &Event<'_>) {
            super::PANICS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            panic!("{}", super::MESSAGE);
        }
        fn enter(&self, _: &Id) {}
        fn exit(&self, _: &Id) {}
    }

    pub(super) fn install() {
        tracing::subscriber::set_global_default(Panicking).unwrap();
    }
}

#[cfg(all(feature = "log", not(feature = "tracing")))]
mod capture {
    use log::{Log, Metadata, Record};

    struct Panicking;

    impl Log for Panicking {
        fn enabled(&self, m: &Metadata<'_>) -> bool {
            m.target().starts_with("apache_zookeeper")
        }
        fn log(&self, r: &Record<'_>) {
            if self.enabled(r.metadata()) {
                super::PANICS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                panic!("{}", super::MESSAGE);
            }
        }
        fn flush(&self) {}
    }

    pub(super) fn install() {
        log::set_boxed_logger(Box::new(Panicking)).unwrap();
        log::set_max_level(log::LevelFilter::Debug);
    }
}

/// Installs the fault-injecting logger singleton and suppresses its expected panic output.
fn install() {
    static ONCE: OnceLock<()> = OnceLock::new();
    ONCE.get_or_init(|| {
        let hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let ours = info
                .payload()
                .downcast_ref::<String>()
                .is_some_and(|s| s == MESSAGE);
            if !ours {
                hook(info);
            }
        }));
        capture::install();
    });
}

/// Executes CRUD operations and graceful session teardown while every emitted log record triggers
/// a backend panic.
async fn survive(rt: Rt) {
    install();
    let srv = common::fake();
    let c = within(rt.apply(common::builder(&srv.connect_string())).connect())
        .await
        .unwrap();
    within(c.exists("/")).await.unwrap();
    let path = format!("/{}", rt.name());
    within(c.create(path.as_str(), b"x", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    within(c.exists(path.as_str())).await.unwrap().unwrap();
    within(c.close()).await.unwrap();
    assert!(
        PANICS.load(Ordering::SeqCst) > 0,
        "no record reached the backend"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_panicking_log_backend_does_not_end_the_session() {
    for rt in all_rts() {
        survive(rt).await;
    }
}
