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

//! Integration tests for listener restarts, multi-server ensembles, session failover, and lease
//! expiration.

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

use std::time::Duration;

use common::{WAIT, create_ok, open, open_session};
use zk_fakeserver::{ClusterConfig, Ensemble, FakeServer, RawClient, ServerOptions, create_mode};
use zk_jute::proto::{GetDataResponse, ReconfigRequest};
use zk_jute::rc::{ZBADVERSION, ZRECONFIGDISABLED};
use zk_jute::{JString, opcode};

#[test]
fn restart_keeps_or_drops_sessions() {
    let mut server = FakeServer::start().unwrap();
    let (mut c, r) = open_session(&server);
    create_ok(&mut c, "/eph", b"", create_mode::EPHEMERAL);
    let port = server.port();
    server.restart(true).unwrap();
    assert_eq!(server.port(), port);
    assert!(c.wait_closed(WAIT));
    let z = server.cluster().zxid();
    let (mut c, back) =
        RawClient::resume(server.addr(), r.session_id, r.passwd, 30_000, z).unwrap();
    assert_eq!(back.session_id, r.session_id);
    assert_eq!(c.ping().unwrap().err, 0);

    server.restart(false).unwrap();
    assert!(server.cluster().node("/eph").is_none());
    let (_c, gone) = RawClient::resume(server.addr(), r.session_id, r.passwd, 30_000, 0).unwrap();
    assert_eq!(gone.session_id, 0);
}

#[test]
fn ensemble_failover_moves_session() {
    let mut ens = Ensemble::start(3, ClusterConfig::default()).unwrap();
    assert_eq!(ens.connect_string().split(',').count(), 3);
    let s0 = ens.server(0).unwrap();
    let (mut c, r) = open_session(s0);
    create_ok(&mut c, "/shared", b"v", 0);
    ens.server_mut(0).unwrap().stop();
    assert!(c.wait_closed(WAIT));
    let s1 = ens.server(1).unwrap();
    let (mut c, back) = RawClient::resume(
        s1.addr(),
        r.session_id,
        r.passwd,
        30_000,
        ens.cluster().zxid(),
    )
    .unwrap();
    assert_eq!(back.session_id, r.session_id);
    assert_eq!(common::data_of(&mut c, "/shared"), b"v");
    let info = ens.cluster().sessions();
    assert_eq!(info[0].server, Some(1));
    // Frames received on listener 1 record `server == 1` in the shared cluster transcript.
    let rec = ens
        .cluster()
        .wait_for_frame(WAIT, |f| f.server == 1)
        .unwrap();
    assert_eq!(rec.session_id, r.session_id);
}

#[test]
fn timer_expires_idle_session() {
    let server = FakeServer::start_with(
        ClusterConfig {
            min_session_timeout_ms: 100,
            ..ClusterConfig::default()
        },
        ServerOptions::default(),
    )
    .unwrap();
    let (mut idle, r) = RawClient::open(server.addr(), 100).unwrap();
    assert_eq!(r.time_out, 100);
    create_ok(&mut idle, "/idle", b"", create_mode::EPHEMERAL);
    let (mut busy, _) = RawClient::open(server.addr(), 100).unwrap();
    for _ in 0..10 {
        std::thread::sleep(Duration::from_millis(30));
        assert_eq!(busy.ping().unwrap().err, 0);
    }
    assert!(idle.wait_closed(WAIT));
    assert!(server.cluster().node("/idle").is_none());
    assert_eq!(server.cluster().sessions().len(), 1);
}

#[test]
fn reconfig_and_config_node() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    let req = |cur| ReconfigRequest {
        joining_servers: "server.2=127.0.0.1:2888:3888;2181".into(),
        leaving_servers: JString::Null,
        new_members: JString::Null,
        cur_config_id: cur,
    };
    assert_eq!(
        c.call(opcode::RECONFIG, &req(-1)).unwrap().err,
        ZRECONFIGDISABLED
    );

    let server = FakeServer::start_with(
        ClusterConfig {
            reconfig_enabled: true,
            ..ClusterConfig::default()
        },
        ServerOptions::default(),
    )
    .unwrap();
    server
        .cluster()
        .set_config_data("server.1=127.0.0.1:2888:3888;2181\nversion=100000000");
    let mut c = open(&server);
    assert_eq!(c.call(opcode::RECONFIG, &req(5)).unwrap().err, ZBADVERSION);
    let r = c.call(opcode::RECONFIG, &req(0x1_0000_0000)).unwrap();
    assert_eq!(r.err, 0);
    let d: GetDataResponse = r.decode().unwrap();
    let text = String::from_utf8(d.data.into_bytes().unwrap()).unwrap();
    assert!(text.starts_with("server.1="));
    assert!(text.contains("\nserver.2="));
    assert!(text.ends_with(&format!("version={:x}", r.zxid)));
}

#[test]
fn reset_clears_state() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/gone", b"", 0);
    server.cluster().reset();
    assert!(c.wait_closed(WAIT));
    assert!(server.cluster().node("/gone").is_none());
    assert_eq!(server.cluster().zxid(), 0);
    assert!(server.cluster().node("/zookeeper/config").is_some());
}
