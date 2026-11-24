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

//! Request submission and validation pipeline tests exercising the public [`Connection`] API.

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

use common::{Tk, conn_with};
use zk_jute::rc::ZBADARGUMENTS;
use zk_proto::{
    Connection,
    compat::Compat,
    config::Config,
    request::{InputDefect, Op, PathIn, Request, Route, Stage, Submitted},
    time::Millis,
};

fn legacy() -> Connection<Tk> {
    conn_with(Config::legacy(30_000), Compat::LEGACY_C)
}

fn lp(p: &str) -> PathIn {
    PathIn::Legacy(Some(p.as_bytes().to_vec()))
}

fn req(op: Op<u32>, defect: InputDefect) -> Request<Tk> {
    Request {
        op,
        token: 7,
        route: Route::Queued,
        defect,
    }
}

/// Submits an operation expected to fail synchronously and returns its rejection stage and error
/// code.
fn fails(c: &mut Connection<Tk>, op: Op<u32>, defect: InputDefect) -> (Stage, i32) {
    match c.submit(req(op, defect), Millis(0)) {
        Err(e) => {
            assert_eq!(e.token, 7);
            (e.stage, e.code)
        }
        Ok(s) => panic!("submit passed: {s:?}"),
    }
}

fn queued(c: &mut Connection<Tk>, op: Op<u32>) -> i32 {
    match c.submit(req(op, InputDefect::default()), Millis(0)) {
        Ok(Submitted::Queued { xid }) => xid,
        Ok(Submitted::Local(_)) => panic!("local"),
        Err(e) => panic!("submit failed: {e:?}"),
    }
}

const VALIDATE_BAD: (Stage, i32) = (Stage::Validate, ZBADARGUMENTS);

#[test]
fn failed_submit_draws_no_xid() {
    let mut c = legacy();
    let op = Op::Sync {
        path: PathIn::Legacy(None),
    };
    assert_eq!(fails(&mut c, op, InputDefect::default()), VALIDATE_BAD);
    assert_eq!(queued(&mut c, Op::Sync { path: lp("/a") }), 1);
    assert_eq!(queued(&mut c, Op::Sync { path: lp("/b") }), 2);
}
