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

//! Parity test suite validating read-only ensemble operation over TLS corresponding to
//! `TestReadOnlyClient.cc`.
//!
//! Evaluates client behavior connecting to partitioned ensemble members operating in read-only
//! mode using mutual TLS with legacy C certificate configuration strings.

#![cfg(feature = "tls-rustls")]
#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::print_stderr
)]
#![allow(clippy::disallowed_macros)]

mod common;
mod parity_g1;

use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{CreateMode, Error, SessionState, TlsOptions};
use common::{real_port, within};
use parity_g1::{legacy_cert_string, quorum_dir, start_or_skip, tls_props, within_for};

/// Maximum duration permitted for a quorum member to achieve serving state following readiness
/// probing.
const SERVE_WAIT: Duration = Duration::from_secs(20);

/// C parity test case (`Zookeeper_readOnly::testReadOnlyWithSSL`): asserts that clients configured
/// with `allow_read_only(true)` establish mutual TLS sessions with partitioned quorum members,
/// permitting read operations while strictly rejecting mutating operations with
/// [`Error::NotReadOnly`].
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn read_only_server_serves_reads_over_tls() {
    let (Some(client), Some(secure), Some(q1), Some(e1), Some(q2), Some(e2)) = (
        real_port(12),
        real_port(13),
        real_port(14),
        real_port(15),
        real_port(16),
        real_port(17),
    ) else {
        return;
    };
    let dir = quorum_dir("ro-tls", client);
    // Partitioned quorum configuration: single node out of two voters forces read-only serving
    // mode with local sessions.
    let props = format!(
        "localSessionsEnabled=true\ninitLimit=5\nsyncLimit=2\n\
         server.1=127.0.0.1:{q1}:{e1}\nserver.2=127.0.0.1:{q2}:{e2}\n{}",
        tls_props(&dir, secure, "need")
    );
    let Some(_server) = start_or_skip(client, Some(dir), &props, "-Dreadonlymode.enabled=true")
    else {
        return;
    };
    let cs = format!("localhost:{secure}");
    for rt in common::all_rts() {
        let tls = TlsOptions::legacy_c_compat(&legacy_cert_string()).unwrap();
        let b = rt
            .apply(common::builder(&cs))
            .tls(tls)
            .allow_read_only(true);
        let c = within_for(SERVE_WAIT, b.connect()).await.unwrap();
        assert_eq!(c.state().state, SessionState::ConnectedReadOnly);
        assert!(within(c.get_data("/")).await.is_ok());
        let r = within(c.create("/testSSL", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE)).await;
        assert_eq!(r.err(), Some(Error::NotReadOnly));
        within(c.close()).await.unwrap();
    }
}
