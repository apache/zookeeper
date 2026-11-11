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

//! Parity test suite validating connection retry policies corresponding to `TestClientRetry.cc`.
//!
//! Asserts client reconnection backoff and connection establishment behavior against a ZooKeeper
//! ensemble member enforcing strict connection throttling (`maxClientCnxns=1`).

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

use apache_zookeeper::SessionState;
use common::within;
use parity_g1::{RECONNECT_WAIT, fresh_server_with, within_for};

/// Port offset allocated for single-connection server testing.
const OFFSET: u16 = 11;

/// Bounded interval asserting connection rejection while the single permitted client slot is
/// occupied.
const REFUSED: Duration = Duration::from_millis(1_500);

/// C parity test case (`Zookeeper_clientretry::testRetry`): verifies that connection throttling
/// rejects concurrent connections from a single IP, while automated retry succeeds once the
/// primary client disconnects.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "real-server"]
async fn second_client_connects_after_the_first_closes() {
    let Some(server) = fresh_server_with(OFFSET, "maxClientCnxns=1", "") else {
        return;
    };
    let cs = server.connect_string();
    for rt in common::all_rts() {
        // Await socket teardown of prior connections to ensure initial connection succeeds.
        let zk1 = within_for(RECONNECT_WAIT, rt.apply(common::builder(&cs)).connect())
            .await
            .unwrap();
        let f = rt.apply(common::builder(&cs)).connect();
        let w = f.state_watcher();
        let mut f = Box::pin(f);
        assert!(
            tokio::time::timeout(REFUSED, &mut f).await.is_err(),
            "the second client connected while the first held the slot"
        );
        assert_eq!(w.current().state, SessionState::Connecting);

        within(zk1.close()).await.unwrap();
        let zk2 = within_for(RECONNECT_WAIT, f).await.unwrap();
        assert!(zk2.state().state.is_connected());
        assert!(within(zk2.exists("/")).await.unwrap().is_some());
        within(zk2.close()).await.unwrap();
    }
}
