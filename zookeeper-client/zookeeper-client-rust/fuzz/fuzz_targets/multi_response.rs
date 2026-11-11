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

//! Fuzzes multi-operation response decoding both standalone and within an active session.
//!
//! The standalone check runs `decode_multi` and verifies round-trip re-encoding. The session
//! check submits a matching `Multi` or `MultiRead` request on a connected `Sim` session, delivers
//! the payload as the server reply, and executes the remaining fuzz command stream.

#![no_main]
#![forbid(unsafe_code)]

use libfuzzer_sys::fuzz_target;
use zk_fuzz::Cursor;
use zk_fuzz::sim::{Options, Sim, read_multi_op};
use zk_jute::multi::{MultiBody, MultiItem, MultiKind, decode_multi, encode_end};
use zk_jute::proto::MultiHeader;
use zk_jute::{Reader, Record};
use zk_proto::{InputDefect, Op, Route};

fn encode(items: &[MultiItem]) -> Vec<u8> {
    let mut out = Vec::new();
    for i in items {
        MultiHeader {
            r#type: i.wire_type,
            done: false,
            err: i.err,
        }
        .encode(&mut out);
        match &i.body {
            MultiBody::Error(b) => b.encode(&mut out),
            MultiBody::Create(b) => b.encode(&mut out),
            MultiBody::Create2(b) => b.encode(&mut out),
            MultiBody::SetData(b) => b.encode(&mut out),
            MultiBody::GetData(b) => b.encode(&mut out),
            MultiBody::GetChildren(b) => b.encode(&mut out),
            MultiBody::Empty => {}
        }
    }
    encode_end(&mut out);
    out
}

fuzz_target!(|data: &[u8]| {
    let mut c = Cursor::new(data);
    let opts = Options::read(&mut c);
    let n = usize::from(c.u8() % 8);
    let read = c.bool();
    let native = opts.profile == zk_fuzz::sim::Profile::Native;
    let ops: Vec<_> = (0..n).map(|_| read_multi_op(&mut c, native)).collect();
    let zxid = c.i64();
    let err = c.i32();
    let body = c.lp();

    let kind = if read {
        MultiKind::MultiRead
    } else {
        MultiKind::Multi
    };
    let mut r = Reader::new(body);
    if let Ok(items) = decode_multi(&mut r, n, kind) {
        assert_eq!(items.len(), n);
        let again = encode(&items);
        let mut r2 = Reader::new(&again);
        let items2 = decode_multi(&mut r2, n, kind).expect("re-encoded multi response must decode");
        assert_eq!(items, items2, "multi response items mutated across round-trip");
        assert!(r2.is_empty(), "decoder must consume through the terminal MultiHeader");
    }

    let mut sim = Sim::new(opts);
    sim.settle();
    sim.push_connect_response(30_000, 0x5eed, false);
    sim.settle();
    let op = if read {
        Op::MultiRead { ops }
    } else {
        Op::Multi { ops }
    };
    sim.submit(op, Route::Queued, InputDefect::default());
    sim.settle();
    let xid = sim.seen_xids().last().unwrap_or(0);
    sim.push_reply(xid, zxid, err, body);
    sim.run_commands(&mut c);
    sim.finish();
});
