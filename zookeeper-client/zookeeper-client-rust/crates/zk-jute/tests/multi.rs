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

//! Conformance tests for batched multi-operation request header encoding and response stream
//! demultiplexing.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

use zk_jute::data::Stat;
use zk_jute::multi::{
    MultiBody, MultiError, MultiItem, MultiKind, decode_multi, encode_end, encode_op_header,
};
use zk_jute::proto::{
    Create2Response, CreateResponse, ErrorResponse, GetChildrenResponse, GetDataResponse,
    MultiHeader, SetDataResponse,
};
use zk_jute::{DecodeError, JBuffer, Reader, Record, opcode, rc};

fn header(op: i32, err: i32, out: &mut Vec<u8>) {
    MultiHeader {
        r#type: op,
        done: false,
        err,
    }
    .encode(out);
}

fn end(out: &mut Vec<u8>) {
    MultiHeader {
        r#type: -1,
        done: true,
        err: -1,
    }
    .encode(out);
}

fn stat(n: i64) -> Stat {
    Stat {
        czxid: n,
        mzxid: n + 1,
        version: 3,
        ..Stat::default()
    }
}

#[test]
fn request_headers_match_c() {
    let mut out = Vec::new();
    encode_op_header(opcode::CREATE, &mut out);
    assert_eq!(out, [0, 0, 0, 1, 0, 0xff, 0xff, 0xff, 0xff]);
    out.clear();
    encode_end(&mut out);
    assert_eq!(out, [0xff, 0xff, 0xff, 0xff, 1, 0xff, 0xff, 0xff, 0xff]);
}

/// Verifies that multi-operation responses demultiplex each sub-result according to the opcode
/// recorded in its wire `MultiHeader`.
#[test]
fn multi_decodes_by_wire_type() {
    let mut b = Vec::new();
    header(opcode::CREATE, 0, &mut b);
    CreateResponse { path: "/a".into() }.encode(&mut b);
    header(opcode::CREATE2, 0, &mut b);
    Create2Response {
        path: "/b".into(),
        stat: stat(10),
    }
    .encode(&mut b);
    header(opcode::DELETE, 0, &mut b);
    header(opcode::SET_DATA, 0, &mut b);
    SetDataResponse { stat: stat(20) }.encode(&mut b);
    header(opcode::CHECK, 0, &mut b);
    header(opcode::ERROR, rc::ZNONODE, &mut b);
    ErrorResponse { err: rc::ZNONODE }.encode(&mut b);
    end(&mut b);
    b.extend_from_slice(b"trailing");

    let mut r = Reader::new(&b);
    let items = decode_multi(&mut r, 6, MultiKind::Multi).unwrap();
    assert_eq!(r.rest(), b"trailing");
    let bodies: Vec<_> = items
        .iter()
        .map(|i| (i.wire_type, i.body.clone()))
        .collect();
    assert_eq!(
        bodies,
        vec![
            (1, MultiBody::Create(CreateResponse { path: "/a".into() })),
            (
                15,
                MultiBody::Create2(Create2Response {
                    path: "/b".into(),
                    stat: stat(10)
                })
            ),
            (2, MultiBody::Empty),
            (5, MultiBody::SetData(SetDataResponse { stat: stat(20) })),
            (13, MultiBody::Empty),
            (-1, MultiBody::Error(ErrorResponse { err: rc::ZNONODE })),
        ]
    );
    assert_eq!(items[5].err, rc::ZNONODE);
}

#[test]
fn multi_read_accepts_only_read_types() {
    let mut b = Vec::new();
    header(opcode::GET_DATA, 0, &mut b);
    GetDataResponse {
        data: JBuffer::Bytes(b"v".to_vec()),
        stat: stat(1),
    }
    .encode(&mut b);
    header(opcode::GET_CHILDREN, 0, &mut b);
    GetChildrenResponse {
        children: vec!["c".into()].into(),
    }
    .encode(&mut b);
    header(opcode::ERROR, rc::ZNOAUTH, &mut b);
    ErrorResponse { err: rc::ZNOAUTH }.encode(&mut b);
    end(&mut b);
    let items = decode_multi(&mut Reader::new(&b), 3, MultiKind::MultiRead).unwrap();
    assert!(matches!(items[0].body, MultiBody::GetData(_)));
    assert!(matches!(items[1].body, MultiBody::GetChildren(_)));
    assert!(matches!(
        items[2],
        MultiItem {
            wire_type: -1,
            err: rc::ZNOAUTH,
            ..
        }
    ));
    // Read-only opcodes are rejected in mutating `Multi` batches, and mutating opcodes are
    // rejected in `MultiRead` batches.
    assert_eq!(
        decode_multi(&mut Reader::new(&b), 3, MultiKind::Multi),
        Err(MultiError::UnexpectedType(opcode::GET_DATA))
    );
    let mut w = Vec::new();
    header(opcode::DELETE, 0, &mut w);
    end(&mut w);
    assert_eq!(
        decode_multi(&mut Reader::new(&w), 1, MultiKind::MultiRead),
        Err(MultiError::UnexpectedType(opcode::DELETE))
    );
}

#[test]
fn create_variants_on_the_wire_are_rejected() {
    // ZooKeeper servers encode responses for `createContainer` and `createTTL` sub-operations
    // using the `CREATE2` (15) wire opcode; raw request opcodes 19 and 21 are invalid in replies.
    for op in [opcode::CREATE_CONTAINER, opcode::CREATE_TTL, 99] {
        let mut b = Vec::new();
        header(op, 0, &mut b);
        end(&mut b);
        assert_eq!(
            decode_multi(&mut Reader::new(&b), 1, MultiKind::Multi),
            Err(MultiError::UnexpectedType(op))
        );
    }
}

#[test]
fn count_mismatch_is_an_error() {
    let mut b = Vec::new();
    header(opcode::DELETE, 0, &mut b);
    header(opcode::DELETE, 0, &mut b);
    end(&mut b);
    assert_eq!(
        decode_multi(&mut Reader::new(&b), 3, MultiKind::Multi),
        Err(MultiError::CountMismatch {
            expected: 3,
            got: 2
        })
    );
    assert_eq!(
        decode_multi(&mut Reader::new(&b), 1, MultiKind::Multi),
        Err(MultiError::CountMismatch {
            expected: 1,
            got: 2
        })
    );
    assert_eq!(
        decode_multi(&mut Reader::new(&b), 2, MultiKind::Multi)
            .unwrap()
            .len(),
        2
    );
}

#[test]
fn missing_terminator_is_a_decode_error() {
    let mut b = Vec::new();
    header(opcode::DELETE, 0, &mut b);
    let e = decode_multi(&mut Reader::new(&b), 1, MultiKind::Multi).unwrap_err();
    assert!(matches!(
        e,
        MultiError::Decode(DecodeError::Truncated { .. })
    ));
    let mut b = Vec::new();
    header(opcode::CREATE, 0, &mut b);
    b.extend_from_slice(&(-3i32).to_be_bytes());
    end(&mut b);
    assert_eq!(
        decode_multi(&mut Reader::new(&b), 1, MultiKind::Multi),
        Err(MultiError::Decode(DecodeError::NegativeString(-3)))
    );
    assert!(!e.to_string().is_empty());
}

#[test]
fn empty_multi_is_only_the_terminator() {
    let mut b = Vec::new();
    end(&mut b);
    assert!(
        decode_multi(&mut Reader::new(&b), 0, MultiKind::Multi)
            .unwrap()
            .is_empty()
    );
}
