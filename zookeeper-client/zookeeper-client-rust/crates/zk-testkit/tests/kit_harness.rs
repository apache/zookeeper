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

//! Tests for the deterministic [`Harness`]: server address assignment, frame split invariance,
//! deterministic replay under fixed seeds, host failover on `ECONNREFUSED`, and local/rejected
//! submission recording.

#![allow(
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use std::net::SocketAddr;

use zk_jute::rc;
use zk_proto::{Compat, Config, DriverMode, Op, PathIn, State, TransportFailure};
use zk_testkit::chunk::Chunking;
use zk_testkit::harness::*;
use zk_testkit::net::ECONNREFUSED;
use zk_testkit::recorder::ActionRecord;
use zk_testkit::tokens::WatcherId;

type Summary = (Vec<(i32, Option<zk_proto::Reply>)>, usize, Vec<State>);

/// Executes a fixed sequence of operations and returns a summary that must remain invariant across
/// arbitrary wire-frame chunkings.
fn scenario(mut h: Harness) -> Summary {
    h.connect().unwrap();
    let ops = h.ops();
    let w = WatcherId::new(1, 1);
    let mut tickets = vec![
        h.submit(ops.create("/s", b"abc", 0)).unwrap(),
        h.submit(ops.get_data("/s", Some(w))).unwrap(),
        h.submit(ops.get_children("/", None)).unwrap(),
        h.submit(ops.exists("/none", None)).unwrap(),
    ];
    for t in &tickets {
        h.wait_for(*t).unwrap();
    }
    tickets.push(h.submit(ops.set_data("/s", b"d", -1)).unwrap());
    h.wait_for(*tickets.last().unwrap()).unwrap();
    h.run_until(|h| !h.recorder().watch_events().is_empty(), 10_000)
        .unwrap();
    h.expect_exactly_once().unwrap();
    let results = tickets
        .iter()
        .map(|t| {
            let r = h.recorder().completion(*t).unwrap();
            (r.rc, r.reply.clone())
        })
        .collect();
    (
        results,
        h.recorder().watch_events().len(),
        h.recorder().session_states(),
    )
}

#[test]
fn server_addresses() {
    assert_eq!(server_addr(0).to_string(), "127.0.0.1:2181");
    assert_eq!(server_addr(2).port(), 2183);
}

#[test]
fn frame_split_invariance() {
    let base = scenario(Harness::st(11));
    assert_eq!(base.0.first().map(|r| r.0), Some(rc::ZOK));
    assert_eq!(base.0.get(3).map(|r| r.0), Some(rc::ZNONODE));
    assert_eq!(base.1, 1);
    for plan in [
        Chunking::ONE_BYTE,
        Chunking::Fixed(3),
        Chunking::Seeded { seed: 5, max: 7 },
    ] {
        for (mode, read) in [
            (DriverMode::ST, ReadMode::Hint),
            (DriverMode::ST, ReadMode::Chunked),
            (DriverMode::MT, ReadMode::Chunked),
        ] {
            let h = HarnessBuilder::new(Config::legacy(10_000), Compat::LEGACY_C)
                .mode(mode)
                .seed(11)
                .chunking(plan.clone())
                .read_mode(read)
                .build();
            let got = scenario(h);
            assert_eq!(got.0, base.0, "{plan:?} {mode:?} {read:?}");
            assert_eq!(got.1, base.1);
        }
    }
}

#[test]
fn same_seed_same_run() {
    let run = |seed| {
        let mut h = HarnessBuilder::new(Config::legacy(10_000), Compat::LEGACY_C)
            .servers(3)
            .seed(seed)
            .build();
        h.connect().unwrap();
        h.run_for(15_000).unwrap();
        let f = h.finish();
        let addrs: Vec<SocketAddr> = f
            .recorder
            .actions()
            .filter_map(|a| match a {
                ActionRecord::Connect { addr, .. } => Some(*addr),
                _ => None,
            })
            .collect();
        (addrs, f.recorder.len())
    };
    assert_eq!(run(3), run(3));
}

#[test]
fn refused_connect_moves_to_next_host() {
    let mut h = HarnessBuilder::new(Config::legacy(10_000), Compat::LEGACY_C)
        .servers(2)
        .shuffle(false)
        .build();
    h.server_mut().set_accepting(server_addr(0), false);
    h.connect().unwrap();
    assert!(h.recorder().failures().any(|(f, _)| matches!(
        f,
        TransportFailure::TcpRefused {
            errno: ECONNREFUSED
        }
    )));
    assert_eq!(
        h.server().link_addr(h.link().unwrap()),
        Some(server_addr(1))
    );
}

#[test]
fn local_and_rejected_submits_are_recorded() {
    let mut h = Harness::st(1);
    let ops = h.ops();
    let bad = Op::Exists {
        path: PathIn::Legacy(Some(b"no-slash".to_vec())),
        watch: None,
    };
    match h.submit(bad) {
        Err(r) => {
            assert_eq!(
                h.recorder().delivery(r.ticket),
                Some(zk_testkit::recorder::Delivery::Rejected(r.code))
            );
        }
        Ok(t) => {
            let r = h.wait_for(t).unwrap();
            assert_ne!(r.rc, rc::ZOK);
        }
    }
    h.connect().unwrap();
    let r = h.call(ops.exists("/", None)).unwrap();
    assert_eq!(r.rc, rc::ZOK);
    h.close().unwrap();
    h.finish().recorder.check_exactly_once().unwrap();
}
