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

//! Verification of structured logging output across `tracing` and `log` backends.
//!
//! Asserts proper event categorization across target namespaces, interpolation of
//! format parameters, redaction of sensitive credentials, and accurate propagation
//! of client labels and callsite metadata.

#![cfg(any(feature = "tracing", feature = "log"))]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::sync::{Arc, Mutex, OnceLock};

/// Structured representation of a captured log record.
#[derive(Clone, Debug)]
#[allow(dead_code)]
struct Line {
    target: String,
    level: String,
    client: Option<String>,
    site: Option<String>,
    text: String,
}

type Lines = Arc<Mutex<Vec<Line>>>;

use common::within;

/// Recognized logging target namespaces within the client crate.
#[allow(dead_code)]
const TARGETS: [&str; 5] = [
    "apache_zookeeper",
    "apache_zookeeper::session",
    "apache_zookeeper::request",
    "apache_zookeeper::watch",
    "apache_zookeeper::proto",
];

/// Checks whether an unformatted C printf-style conversion specifier remains in the formatted
/// message.
#[allow(dead_code)]
fn has_conversion(text: &str) -> bool {
    let b = text.as_bytes();
    let mut i = 0;
    while let Some(&c) = b.get(i) {
        i += 1;
        if c != b'%' {
            continue;
        }
        let mut j = i;
        while b
            .get(j)
            .is_some_and(|c| b"-+ #0123456789.*hlqLjzt".contains(c))
        {
            j += 1;
        }
        if b.get(j).is_some_and(|c| b"diouxXeEfgGcsp".contains(c)) {
            return true;
        }
    }
    false
}

#[cfg(feature = "tracing")]
mod capture {
    use tracing::field::{Field, Visit};
    use tracing::span::{Attributes, Id, Record};
    use tracing::{Event, Metadata, Subscriber};

    use super::{Line, Lines};

    #[derive(Default)]
    struct Fields {
        client: Option<String>,
        site: Option<String>,
        text: String,
    }

    impl Visit for Fields {
        fn record_str(&mut self, field: &Field, value: &str) {
            match field.name() {
                "client" => self.client = Some(value.to_owned()),
                "site" => self.site = Some(value.to_owned()),
                _ => {}
            }
        }
        fn record_debug(&mut self, field: &Field, value: &dyn std::fmt::Debug) {
            if field.name() == "message" {
                self.text = format!("{value:?}");
            }
        }
    }

    struct Capture(Lines);

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
            let mut f = Fields::default();
            e.record(&mut f);
            self.0.lock().unwrap().push(Line {
                target: e.metadata().target().to_owned(),
                level: e.metadata().level().to_string(),
                client: f.client,
                site: f.site,
                text: f.text,
            });
        }
        fn enter(&self, _: &Id) {}
        fn exit(&self, _: &Id) {}
    }

    pub(super) fn install(lines: Lines) {
        tracing::subscriber::set_global_default(Capture(lines)).unwrap();
    }
}

#[cfg(all(feature = "log", not(feature = "tracing")))]
mod capture {
    use log::{Log, Metadata, Record};

    use super::{Line, Lines};

    struct Capture(Lines);

    impl Log for Capture {
        fn enabled(&self, m: &Metadata<'_>) -> bool {
            m.target().starts_with("apache_zookeeper")
        }
        fn log(&self, r: &Record<'_>) {
            if !self.enabled(r.metadata()) {
                return;
            }
            let text = r.args().to_string();
            // Parses log messages adhering to the format "[client_name] payload (site CALLSITE)".
            let (client, rest) = match text.strip_prefix('[').and_then(|t| t.split_once("] ")) {
                Some((name, rest)) => (Some(name.to_owned()), rest.to_owned()),
                None => (None, text.clone()),
            };
            let (body, site) = match rest.rsplit_once(" (site ") {
                Some((body, site)) => (body.to_owned(), site.strip_suffix(')').map(str::to_owned)),
                None => (rest, None),
            };
            self.0.lock().unwrap().push(Line {
                target: r.target().to_owned(),
                level: r.level().to_string().to_uppercase(),
                client,
                site,
                text: body,
            });
        }
        fn flush(&self) {}
    }

    pub(super) fn install(lines: Lines) {
        log::set_boxed_logger(Box::new(Capture(lines))).unwrap();
        log::set_max_level(log::LevelFilter::Debug);
    }
}

/// Process-wide log capture sink initialized once per test binary.
#[cfg(any(feature = "tracing", feature = "log"))]
fn lines() -> Lines {
    static LINES: OnceLock<Lines> = OnceLock::new();
    Arc::clone(LINES.get_or_init(|| {
        let l: Lines = Arc::default();
        capture::install(Arc::clone(&l));
        l
    }))
}

/// Filters captured log entries for events emitted by the specified client instance.
#[cfg(any(feature = "tracing", feature = "log"))]
fn of(name: &str) -> Vec<Line> {
    lines()
        .lock()
        .unwrap()
        .iter()
        .filter(|l| l.client.as_deref() == Some(name))
        .cloned()
        .collect()
}

/// Asserts that log events include namespace targets, client identifiers, site names, and fully
/// formatted text without raw specifiers.
#[cfg(any(feature = "tracing", feature = "log"))]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn records_have_a_target_a_name_a_site_and_rendered_text() {
    use apache_zookeeper::Error;
    use zk_fakeserver::{Action, Rule};
    use zk_jute::opcode;

    let _ = lines();
    let fs = common::fake();
    fs.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .path("/bad")
            .once()
            .then(Action::RewriteXid(0x7777)),
    );
    let c = within(
        common::builder(&fs.connect_string())
            .name("rendered")
            .connect(),
    )
    .await
    .unwrap();
    within(c.exists("/")).await.unwrap();
    assert_eq!(
        within(c.get_data("/bad")).await.err(),
        Some(Error::ConnectionLoss)
    );
    within(c.exists("/")).await.unwrap();
    within(c.close()).await.unwrap();

    let got = of("rendered");
    assert!(!got.is_empty(), "no record of the named client");
    for l in &got {
        assert!(TARGETS.contains(&l.target.as_str()), "{l:?}");
        assert!(l.site.as_deref().is_some_and(|s| !s.is_empty()), "{l:?}");
        assert!(!has_conversion(&l.text), "a C conversion is left: {l:?}");
        assert!(!l.text.ends_with('\n'), "{l:?}");
    }
    let has = |t: &str| got.iter().any(|l| l.target == t);
    assert!(has("apache_zookeeper::request"), "{got:#?}");
    assert!(has("apache_zookeeper::session"), "{got:#?}");
    assert!(
        got.iter().any(|l| l.level == "ERROR" || l.level == "WARN"),
        "the teardown must log a warning or an error: {got:#?}"
    );
    // Protocol-level transaction mismatches format the corrupted XID in hexadecimal notation.
    assert!(
        got.iter().any(|l| l.target == "apache_zookeeper::proto"
            && l.level == "ERROR"
            && l.text.contains("0x7777")),
        "{got:#?}"
    );
}

/// Asserts that sensitive authentication credentials and session secrets are omitted from all
/// emitted log records.
#[cfg(any(feature = "tracing", feature = "log"))]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn records_hold_no_secret() {
    let _ = lines();
    let fs = common::fake();
    let c = within(
        common::builder(&fs.connect_string())
            .name("secret")
            .auth("digest", "user:pw-of-a-test-4711")
            .connect(),
    )
    .await
    .unwrap();
    within(c.add_auth("digest", "other:pw-of-a-test-4712"))
        .await
        .unwrap();
    within(c.exists("/")).await.unwrap();
    fs.drop_connections();
    let mut w = c.state_watcher();
    within(async {
        while w.current().epoch < 2 {
            let _ = w.changed().await;
        }
    })
    .await;
    within(c.close()).await.unwrap();
    let got = of("secret");
    assert!(!got.is_empty());
    for l in &got {
        assert!(!l.text.contains("pw-of-a-test"), "{l:?}");
    }
}

/// Asserts that log events emitted by unlabelled client instances omit client name attributes.
#[cfg(feature = "tracing")]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_unnamed_client_has_no_client_field() {
    let _ = lines();
    let fs = common::fake();
    let before = lines().lock().unwrap().len();
    let c = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    within(c.exists("/")).await.unwrap();
    within(c.close()).await.unwrap();
    let after: Vec<Line> = lines()
        .lock()
        .unwrap()
        .iter()
        .skip(before)
        .cloned()
        .collect();
    assert!(after.iter().any(|l| l.client.is_none()), "{after:#?}");
}
