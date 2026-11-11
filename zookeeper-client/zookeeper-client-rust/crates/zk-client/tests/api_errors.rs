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

//! Verification of protocol error mapping and connection teardown diagnostics.
//!
//! Validates mapping from wire return codes to strongly typed [`Error`] variants,
//! including unrecognized error passthrough, error code precedence over reply payloads,
//! address resolution failure handling, dynamic server migration behavior, and
//! timeout configuration boundary validations.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use apache_zookeeper::{
    Client, ClientBuilder, Error, Resolve, ServerOrder, SessionState, StateWatcher,
};
use zk_fakeserver::{Action, Cluster, ClusterConfig, Rule, ServerOptions};
use zk_jute::opcode;

use common::{connect, within};

/// Awaits transition of the observable session state until matching the target state.
async fn wait_state(w: &mut StateWatcher, want: SessionState) {
    within(async {
        while w.current().state != want {
            let _ = w.changed().await;
        }
    })
    .await;
}

/// Canonical ZooKeeper wire protocol return codes and client error variants under test.
const CODES: [i32; 35] = [
    -1, -2, -3, -4, -5, -6, -7, -8, -9, -12, -13, -14, -15, -100, -101, -102, -103, -108, -110,
    -111, -112, -113, -114, -115, -116, -117, -118, -119, -120, -121, -122, -123, -124, -125, -127,
];

/// Asserts that wire return codes map to canonical [`Error`] variants, preserving unknown codes as
/// [`Error::Unknown`].
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn each_reply_code_maps_to_its_error() {
    let fs = common::fake();
    for rc in CODES.iter().copied().chain([-77, -200]) {
        fs.add_rule(
            Rule::on_request()
                .op(opcode::GET_DATA)
                .path("/rc")
                .once()
                .then(Action::Reply { err: rc }),
        );
    }
    let c = connect(common::builder(&fs.connect_string())).await;
    for rc in CODES.iter().copied().chain([-77, -200]) {
        let want = Error::from_code(rc).unwrap();
        let got = within(c.get_data("/rc")).await;
        assert_eq!(got.err(), Some(want), "rc {rc}");
    }
    // Protocol-level operational errors do not terminate the underlying transport session.
    assert_eq!(c.state().state, SessionState::Connected);
    assert_eq!(c.state().epoch, 1);
    within(c.close()).await.unwrap();
}

/// Asserts that wire response error return codes take precedence over accompanying response
/// payload bodies.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_rewritten_code_wins_over_the_body() {
    let fs = common::fake();
    fs.add_rule(
        Rule::on_request()
            .op(opcode::GET_DATA)
            .once()
            .then(Action::RewriteErr(-102)),
    );
    let c = connect(common::builder(&fs.connect_string())).await;
    assert_eq!(within(c.get_data("/")).await.err(), Some(Error::NoAuth));
    within(c.get_data("/")).await.unwrap();
    within(c.close()).await.unwrap();
}

/// Mock name resolver simulating complete address resolution failure.
struct Empty;

impl Resolve for Empty {
    fn resolve(&self, _host: &str, _port: u16) -> io::Result<Vec<SocketAddr>> {
        Ok(Vec::new())
    }
}

/// Asserts that exhaustion of candidate server addresses sets the disconnect cause to SocketCreate
/// and records an I/O error.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn no_address_gives_an_io_last_error() {
    let c = common::builder("zk.invalid:2181")
        .resolver(Empty)
        .reconnect_delay(Duration::from_millis(20))
        .build()
        .unwrap();
    let w = c.state_watcher();
    let e = within(async {
        loop {
            if let Some(e) = w.last_error() {
                return e;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
    let io = e.downcast_ref::<io::Error>().expect("an io::Error");
    assert_eq!(io.kind(), io::ErrorKind::AddrNotAvailable, "{io}");
    assert_eq!(c.state().state, SessionState::Connecting);
    // In-flight requests during connection establishment await outcome or fail on session
    // teardown.
    let f = c.get_data("/");
    within(c.close()).await.ok();
    let r = within(f).await;
    assert!(
        matches!(r, Err(Error::Closing | Error::ConnectionLoss)),
        "{r:?}"
    );
}

/// Asserts that administrative cluster reconfiguration via `update_servers` drains pending
/// requests with `ConnectionLoss` without recording diagnostic errors.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_forced_move_keeps_last_error() {
    let cluster = Cluster::new(ClusterConfig::default());
    let a = cluster.start_server(ServerOptions::default()).unwrap();
    let b = cluster.start_server(ServerOptions::default()).unwrap();
    let c = connect(common::builder(&a.connect_string())).await;
    let mut w = c.state_watcher();
    assert!(w.last_error().is_none());
    a.hold();
    let pending = c.get_data("/");
    c.update_servers(&b.connect_string()).unwrap();
    assert_eq!(within(pending).await, Err(Error::ConnectionLoss));
    within(async {
        while c.connected_server() != Some(b.addr()) {
            let _ = w.changed().await;
        }
    })
    .await;
    assert!(w.last_error().is_none(), "{:?}", w.last_error());
    a.release();
    within(c.close()).await.unwrap();
}

/// Asserts that autonomous migration from a read-only replica to an active read-write ensemble
/// member preserves diagnostic error state.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_read_write_switch_keeps_last_error() {
    let cluster = Cluster::new(ClusterConfig::default());
    let ro = cluster
        .start_server(ServerOptions {
            read_only: true,
            ..ServerOptions::default()
        })
        .unwrap();
    let mut rw = cluster.start_server(ServerOptions::default()).unwrap();
    let rw_addr = rw.addr();
    rw.stop();
    let c = connect(
        common::builder(&format!("{},{}", ro.addr(), rw_addr))
            .server_order(ServerOrder::AsGiven)
            .allow_read_only(true),
    )
    .await;
    let mut w = c.state_watcher();
    assert_eq!(w.current().state, SessionState::ConnectedReadOnly);
    assert!(w.last_error().is_none());
    rw.resume().unwrap();
    within(async {
        while !(w.current().state == SessionState::Connected
            && c.connected_server() == Some(rw_addr))
        {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await;
    assert!(w.last_error().is_none(), "{:?}", w.last_error());
    wait_state(&mut w, SessionState::Connected).await;
    within(c.close()).await.unwrap();
}

/// Validates that builder validation rejects non-positive timeout values with
/// [`Error::InvalidArgument`].
#[test]
fn a_zero_timeout_is_refused() {
    let refused = |b: ClientBuilder| match b.build() {
        Err(e) => matches!(e.error(), Error::InvalidArgument(_)),
        Ok(_) => false,
    };
    let b = || Client::builder("127.0.0.1:1");
    assert!(refused(b().session_timeout(Duration::ZERO)));
    assert!(refused(b().session_timeout(Duration::from_micros(900))));
    assert!(refused(b().connect_timeout(Duration::ZERO)));
    assert!(refused(b().session_timeout(Duration::from_millis(2))));
    assert!(!refused(
        b().session_timeout(Duration::from_millis(2))
            .connect_timeout(Duration::from_millis(1))
    ));
}
