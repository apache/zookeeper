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

//! Property-based round trip serialization and wire decoding conformance tests for generated Jute
//! records and primitive wrapper types.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

#[macro_use]
mod common;

use core::fmt::Debug;

use common::{parse_signature, wire_bytes};
use proptest::test_runner::{Config, TestCaseError, TestRunner};
use zk_jute::data::{ACL, Id, Stat};
use zk_jute::proto::{
    ConnectRequest, Create2Response, CreateResponse, GetChildrenResponse, GetDataResponse,
    SetSASLResponse,
};
use zk_jute::{DecodeError, JBuffer, JString, JVec, Reader, Record};

/// Exercises a generated [`Record`] implementation against arbitrary wire streams synthesized from
/// its Jute schema signature, verifying decoding, encoded byte length computation, round trip
/// serialization, prefix truncation error reporting, and trailing byte tolerance.
fn round_trip<T: Record + Debug + PartialEq>(sig: &str, name: &str) {
    let schema = parse_signature(sig);
    let mut runner = TestRunner::new(Config {
        // Reduce case count under Miri to account for interpreter execution overhead.
        cases: if cfg!(miri) { 4 } else { 128 },
        failure_persistence: None,
        ..Config::default()
    });
    runner
        .run(&wire_bytes(&schema), |bytes| {
            let mut r = Reader::new(&bytes);
            let rec = T::decode(&mut r).map_err(|e| TestCaseError::fail(format!("{name}: {e}")))?;
            proptest::prop_assert_eq!(r.position(), bytes.len(), "{}", name);
            proptest::prop_assert_eq!(rec.encoded_len(), bytes.len(), "{}", name);
            proptest::prop_assert_eq!(&rec.to_bytes(), &bytes, "{}", name);
            proptest::prop_assert_eq!(&T::from_bytes(&rec.to_bytes()).unwrap(), &rec);
            for cut in 0..bytes.len() {
                let e = T::from_bytes(&bytes[..cut]).unwrap_err();
                proptest::prop_assert!(
                    matches!(e, DecodeError::Truncated { .. }),
                    "{}: cut {} gave {:?}",
                    name,
                    cut,
                    e
                );
                proptest::prop_assert_eq!(e.c_rc(), -7);
            }
            let mut padded = bytes.clone();
            padded.extend_from_slice(&[0xa5; 7]);
            let mut r = Reader::new(&padded);
            proptest::prop_assert_eq!(&T::decode(&mut r).unwrap(), &rec);
            proptest::prop_assert_eq!(r.remaining(), 7);
            Ok(())
        })
        .unwrap();
}

#[test]
fn every_record_round_trips() {
    macro_rules! check {
        ($module:ident, $name:ident) => {
            round_trip::<zk_jute::$module::$name>(
                zk_jute::$module::$name::SIGNATURE,
                stringify!($name),
            )
        };
    }
    for_each_record!(check);
}

#[test]
fn default_record_has_null_wrappers() {
    let id = Id::default();
    assert_eq!(id.scheme, JString::Null);
    assert_eq!(id.to_bytes(), [0xff; 8]);
    let acl = ACL {
        perms: 31,
        id: Id {
            scheme: "world".into(),
            id: "anyone".into(),
        },
    };
    let bytes = acl.to_bytes();
    assert_eq!(
        bytes,
        b"\x00\x00\x00\x1f\x00\x00\x00\x05world\x00\x00\x00\x06anyone"
    );
    assert_eq!(ACL::from_bytes(&bytes).unwrap(), acl);
}

#[test]
fn null_and_empty_stay_distinct() {
    let null: JVec<JString> = JVec::Null;
    let empty: JVec<JString> = JVec::Items(Vec::new());
    assert_eq!(null.to_bytes(), (-1i32).to_be_bytes());
    assert_eq!(empty.to_bytes(), 0i32.to_be_bytes());
    assert_eq!(
        JVec::<JString>::from_bytes(&(-1i32).to_be_bytes()).unwrap(),
        null
    );
    assert_eq!(
        JVec::<JString>::from_bytes(&0i32.to_be_bytes()).unwrap(),
        empty
    );
    assert_eq!(
        JString::from_bytes(&(-1i32).to_be_bytes()).unwrap(),
        JString::Null
    );
    assert_eq!(
        JString::from_bytes(&0i32.to_be_bytes()).unwrap(),
        JString::Bytes(Vec::new())
    );
    assert_eq!(
        JBuffer::from_bytes(&(-1i32).to_be_bytes()).unwrap(),
        JBuffer::Null
    );
    assert!(null.is_null() && null.is_empty() && !empty.is_null() && empty.is_empty());
}

#[test]
fn strings_are_not_utf8_checked() {
    let bytes = b"\x00\x00\x00\x03\xff\xfe\x00";
    let s = JString::from_bytes(bytes).unwrap();
    assert_eq!(s.as_bytes(), Some(&b"\xff\xfe\x00"[..]));
    assert_eq!(s.as_str(), None);
    assert_eq!(JString::from("abc").as_str(), Some("abc"));
}

#[test]
fn booleans_decode_by_truthiness() {
    for b in 1..=255u8 {
        assert!(bool::from_bytes(&[b]).unwrap());
    }
    assert!(!bool::from_bytes(&[0]).unwrap());
    assert_eq!(true.to_bytes(), [1]);
}

#[test]
fn decode_errors_match_c_codes() {
    // In `ia_deserialize_string`, buffer exhaustion maps to `-E2BIG` and negative lengths below -1
    // map to `-EINVAL`.
    let e = JString::from_bytes(&[0, 0, 0, 5, b'a']).unwrap_err();
    assert_eq!(e, DecodeError::Truncated { need: 5, have: 1 });
    assert_eq!(e.c_rc(), -7);
    let e = JString::from_bytes(&(-2i32).to_be_bytes()).unwrap_err();
    assert_eq!(e, DecodeError::NegativeString(-2));
    assert_eq!(e.c_rc(), -22);
    // In `ia_deserialize_buffer`, lengths below -1 fail allocation guards with `-ENOMEM`.
    let e = JBuffer::from_bytes(&i32::MIN.to_be_bytes()).unwrap_err();
    assert_eq!(e, DecodeError::NegativeBuffer(i32::MIN));
    assert_eq!(e.c_rc(), -12);
    // Although C vector deserialization collapses errors, the protocol specification maps vector
    // element counts below -1 to `-ENOMEM`, consistent with buffer allocation guard failures.
    let e = JVec::<i32>::from_bytes(&(-5i32).to_be_bytes()).unwrap_err();
    assert_eq!(e, DecodeError::NegativeCount(-5));
    assert_eq!(e.c_rc(), -12);
    let e = i64::from_bytes(&[1, 2, 3]).unwrap_err();
    assert_eq!(e, DecodeError::Truncated { need: 8, have: 3 });
    // `SetSASLResponse` encapsulates a single `JBuffer` token, yielding `-E2BIG` on truncation or
    // `-ENOMEM` on invalid negative length prefixes.
    let e = SetSASLResponse::from_bytes(&(-3i32).to_be_bytes()).unwrap_err();
    assert_eq!(e.c_rc(), -12);
    let e = SetSASLResponse::from_bytes(&[0, 0, 1, 0, 9]).unwrap_err();
    assert_eq!(e.c_rc(), -7);
}

#[test]
fn hostile_vector_count_fails_as_truncation() {
    // An adversarial element count of `i32::MAX` backed by minimal payload bytes must fail
    // immediately with `DecodeError::Truncated` without exhausting heap memory.
    let mut bytes = i32::MAX.to_be_bytes().to_vec();
    bytes.extend_from_slice(&[0, 0, 0, 1, b'x']);
    let e = JVec::<JString>::from_bytes(&bytes).unwrap_err();
    assert!(matches!(e, DecodeError::Truncated { .. }));
    let e = JVec::<Stat>::from_bytes(&i32::MAX.to_be_bytes()).unwrap_err();
    assert!(matches!(e, DecodeError::Truncated { .. }));
    let e = JBuffer::from_bytes(&i32::MAX.to_be_bytes()).unwrap_err();
    assert_eq!(
        e,
        DecodeError::Truncated {
            need: 0x7fff_ffff,
            have: 0
        }
    );
}

#[test]
fn element_errors_propagate() {
    let mut bytes = 2i32.to_be_bytes().to_vec();
    bytes.extend_from_slice(&0i32.to_be_bytes());
    bytes.extend_from_slice(&(-9i32).to_be_bytes());
    assert_eq!(
        JVec::<JString>::from_bytes(&bytes).unwrap_err(),
        DecodeError::NegativeString(-9)
    );
}

#[test]
fn create2_response_parses_as_create_response() {
    // Legacy `zoo_acreate` callers decode `Create2Response` payloads using `CreateResponse`,
    // leaving the trailing 68-byte `Stat` structure unconsumed in the reader.
    let reply = Create2Response {
        path: "/a".into(),
        stat: Stat {
            czxid: 7,
            ..Stat::default()
        },
    };
    let bytes = reply.to_bytes();
    let mut r = Reader::new(&bytes);
    let short = CreateResponse::decode(&mut r).unwrap();
    assert_eq!(short.path, reply.path);
    assert_eq!(r.remaining(), 68);
}

#[test]
fn field_order_is_declaration_order() {
    let req = ConnectRequest {
        protocol_version: 1,
        last_zxid_seen: 2,
        time_out: 3,
        session_id: 4,
        passwd: JBuffer::Bytes(vec![9; 2]),
        read_only: true,
    };
    assert_eq!(
        req.to_bytes(),
        [
            0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 2, 9,
            9, 1
        ]
    );
    let reply = GetDataResponse {
        data: JBuffer::Null,
        stat: Stat::default(),
    };
    assert_eq!(reply.encoded_len(), 4 + 68);
    let children = GetChildrenResponse {
        children: vec![JString::from("a"), JString::Null].into(),
    };
    assert_eq!(
        children.to_bytes(),
        [0, 0, 0, 2, 0, 0, 0, 1, b'a', 0xff, 0xff, 0xff, 0xff]
    );
}

#[test]
fn vec_default_needs_no_element_default() {
    struct NoDefault;
    let v: JVec<NoDefault> = JVec::default();
    assert!(v.is_null());
    assert_eq!(v.iter().count(), 0);
}

#[test]
fn signatures_match_java() {
    assert_eq!(Stat::SIGNATURE, "LStat(lllliiiliil)");
    assert_eq!(ACL::SIGNATURE, "LACL(iLId(ss))");
    assert_eq!(
        zk_jute::proto::GetACLResponse::SIGNATURE,
        "LGetACLResponse([LACL(iLId(ss))]LStat(lllliiiliil))"
    );
}

#[test]
fn decoded_vector_capacity_is_bounded() {
    // Deserializing a small vector pre-allocates capacity bounded strictly by its element count.
    let v = JVec::Items(vec![1i32, 2, 3]);
    let JVec::Items(items) = JVec::<i32>::from_bytes(&v.to_bytes()).unwrap() else {
        unreachable!("a non-null vector decodes as items");
    };
    assert_eq!(items, [1, 2, 3]);
    assert!(items.capacity() <= 3);
}
