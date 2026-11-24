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

//! Shared schema parsing and property based wire generation utilities for `zk-jute` integration
//! tests.

#![allow(dead_code, unused_macros)]

use proptest::prelude::*;

/// Invokes `$m!(module, Record)` across every generated Jute record structure emitted by the Rust
/// code generator from `zookeeper.jute`. Cross-checked against Java binary fixture files to ensure
/// coverage.
macro_rules! for_each_record {
    ($m:ident) => {
        $m!(data, ACL);
        $m!(data, ClientInfo);
        $m!(data, Id);
        $m!(data, Stat);
        $m!(data, StatPersisted);
        $m!(proto, AddWatchRequest);
        $m!(proto, AuthPacket);
        $m!(proto, CheckVersionRequest);
        $m!(proto, CheckWatchesRequest);
        $m!(proto, ConnectRequest);
        $m!(proto, ConnectResponse);
        $m!(proto, Create2Response);
        $m!(proto, CreateRequest);
        $m!(proto, CreateResponse);
        $m!(proto, CreateTTLRequest);
        $m!(proto, DeleteRequest);
        $m!(proto, ErrorResponse);
        $m!(proto, ExistsRequest);
        $m!(proto, ExistsResponse);
        $m!(proto, GetACLRequest);
        $m!(proto, GetACLResponse);
        $m!(proto, GetAllChildrenNumberRequest);
        $m!(proto, GetAllChildrenNumberResponse);
        $m!(proto, GetChildren2Request);
        $m!(proto, GetChildren2Response);
        $m!(proto, GetChildrenRequest);
        $m!(proto, GetChildrenResponse);
        $m!(proto, GetDataRequest);
        $m!(proto, GetDataResponse);
        $m!(proto, GetEphemeralsRequest);
        $m!(proto, GetEphemeralsResponse);
        $m!(proto, GetMaxChildrenRequest);
        $m!(proto, GetMaxChildrenResponse);
        $m!(proto, GetSASLRequest);
        $m!(proto, MultiHeader);
        $m!(proto, ReconfigRequest);
        $m!(proto, RemoveWatchesRequest);
        $m!(proto, ReplyHeader);
        $m!(proto, RequestHeader);
        $m!(proto, SetACLRequest);
        $m!(proto, SetACLResponse);
        $m!(proto, SetDataRequest);
        $m!(proto, SetDataResponse);
        $m!(proto, SetMaxChildrenRequest);
        $m!(proto, SetSASLRequest);
        $m!(proto, SetSASLResponse);
        $m!(proto, SetWatches);
        $m!(proto, SetWatches2);
        $m!(proto, SyncRequest);
        $m!(proto, SyncResponse);
        $m!(proto, WatcherEvent);
        $m!(proto, WhoAmIResponse);
    };
}

/// Abstract wire schema representation parsed from a compact Jute type signature descriptor.
#[derive(Debug, Clone)]
pub(crate) enum Schema {
    Int,
    Long,
    Bool,
    Str,
    Buf,
    Vec(Box<Schema>),
    Rec(Vec<Schema>),
}

/// Parses a compact Jute type signature string (such as `LACL(iLId(ss))`) into its structural
/// [`Schema`].
pub(crate) fn parse_signature(sig: &str) -> Schema {
    let bytes = sig.as_bytes();
    let mut pos = 0;
    let schema = parse_one(bytes, &mut pos);
    assert_eq!(pos, bytes.len(), "trailing signature text in {sig}");
    schema
}

fn parse_one(s: &[u8], pos: &mut usize) -> Schema {
    let c = s[*pos];
    *pos += 1;
    match c {
        b'i' => Schema::Int,
        b'l' => Schema::Long,
        b'z' => Schema::Bool,
        b's' => Schema::Str,
        b'B' => Schema::Buf,
        b'[' => {
            let elem = parse_one(s, pos);
            assert_eq!(s[*pos], b']');
            *pos += 1;
            Schema::Vec(Box::new(elem))
        }
        b'L' => {
            while s[*pos] != b'(' {
                *pos += 1;
            }
            *pos += 1;
            let mut fields = Vec::new();
            while s[*pos] != b')' {
                fields.push(parse_one(s, pos));
            }
            *pos += 1;
            Schema::Rec(fields)
        }
        other => panic!("unknown signature character {}", other as char),
    }
}

fn len_prefixed(body: Option<Vec<u8>>) -> Vec<u8> {
    match body {
        None => (-1i32).to_be_bytes().to_vec(),
        Some(b) => {
            let mut out = i32::try_from(b.len()).unwrap().to_be_bytes().to_vec();
            out.extend_from_slice(&b);
            out
        }
    }
}

/// Constructs a proptest generation strategy yielding canonical wire encoded byte sequences for
/// `schema`, encoding boolean values strictly as `0` or `1` to match Java and C serializers.
pub(crate) fn wire_bytes(schema: &Schema) -> BoxedStrategy<Vec<u8>> {
    match schema {
        Schema::Int => any::<i32>().prop_map(|v| v.to_be_bytes().to_vec()).boxed(),
        Schema::Long => any::<i64>().prop_map(|v| v.to_be_bytes().to_vec()).boxed(),
        Schema::Bool => any::<bool>().prop_map(|v| vec![u8::from(v)]).boxed(),
        Schema::Str | Schema::Buf => {
            proptest::option::of(proptest::collection::vec(any::<u8>(), 0..24))
                .prop_map(len_prefixed)
                .boxed()
        }
        Schema::Vec(elem) => {
            proptest::option::of(proptest::collection::vec(wire_bytes(elem), 0..4))
                .prop_map(|items| match items {
                    None => (-1i32).to_be_bytes().to_vec(),
                    Some(items) => {
                        let mut out = i32::try_from(items.len()).unwrap().to_be_bytes().to_vec();
                        for item in items {
                            out.extend_from_slice(&item);
                        }
                        out
                    }
                })
                .boxed()
        }
        Schema::Rec(fields) => fields
            .iter()
            .map(wire_bytes)
            .collect::<Vec<_>>()
            .prop_map(|parts| parts.concat())
            .boxed(),
    }
}
