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

//! Shared request helpers over `RawClient` for integration tests.

#![allow(
    dead_code,
    unreachable_pub,
    missing_docs,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::time::Duration;

use zk_fakeserver::{FakeServer, RawClient, RawReply, perms};
use zk_jute::data::{ACL, Id};
use zk_jute::proto::{
    CreateRequest, CreateResponse, DeleteRequest, ExistsRequest, GetChildrenRequest,
    GetDataRequest, GetDataResponse, SetDataRequest,
};
use zk_jute::{JBuffer, JVec, Record, opcode};

pub const WAIT: Duration = Duration::from_secs(5);
pub const SHORT: Duration = Duration::from_millis(150);

pub fn open_acl() -> JVec<ACL> {
    JVec::from(vec![ACL {
        perms: perms::ALL,
        id: Id {
            scheme: "world".into(),
            id: "anyone".into(),
        },
    }])
}

pub fn acl(perms: i32, scheme: &str, id: &str) -> ACL {
    ACL {
        perms,
        id: Id {
            scheme: scheme.into(),
            id: id.into(),
        },
    }
}

pub fn open(server: &FakeServer) -> RawClient {
    RawClient::open(server.addr(), 30_000).unwrap().0
}

pub fn open_session(server: &FakeServer) -> (RawClient, zk_jute::handshake::ConnectResponse) {
    RawClient::open(server.addr(), 30_000).unwrap()
}

pub fn create_req(path: &str, data: &[u8], flags: i32) -> CreateRequest {
    CreateRequest {
        path: path.into(),
        data: JBuffer::from(data),
        acl: open_acl(),
        flags,
    }
}

pub fn create(c: &mut RawClient, path: &str, data: &[u8], flags: i32) -> RawReply {
    c.call(opcode::CREATE, &create_req(path, data, flags))
        .unwrap()
}

/// Creates a znode and returns its created path, panicking if the server returns an error.
pub fn create_ok(c: &mut RawClient, path: &str, data: &[u8], flags: i32) -> String {
    let r = create(c, path, data, flags);
    assert_eq!(r.err, 0, "create {path}");
    let resp: CreateResponse = r.decode().unwrap();
    resp.path.as_str().unwrap().to_owned()
}

pub fn get_data(c: &mut RawClient, path: &str, watch: bool) -> RawReply {
    c.call(
        opcode::GET_DATA,
        &GetDataRequest {
            path: path.into(),
            watch,
        },
    )
    .unwrap()
}

pub fn data_of(c: &mut RawClient, path: &str) -> Vec<u8> {
    let r = get_data(c, path, false);
    assert_eq!(r.err, 0);
    let d: GetDataResponse = r.decode().unwrap();
    d.data.into_bytes().unwrap_or_default()
}

pub fn set_data(c: &mut RawClient, path: &str, data: &[u8], version: i32) -> RawReply {
    c.call(
        opcode::SET_DATA,
        &SetDataRequest {
            path: path.into(),
            data: JBuffer::from(data),
            version,
        },
    )
    .unwrap()
}

pub fn delete(c: &mut RawClient, path: &str, version: i32) -> RawReply {
    c.call(
        opcode::DELETE,
        &DeleteRequest {
            path: path.into(),
            version,
        },
    )
    .unwrap()
}

pub fn exists(c: &mut RawClient, path: &str, watch: bool) -> RawReply {
    c.call(
        opcode::EXISTS,
        &ExistsRequest {
            path: path.into(),
            watch,
        },
    )
    .unwrap()
}

pub fn get_children(c: &mut RawClient, path: &str, watch: bool) -> RawReply {
    c.call(
        opcode::GET_CHILDREN,
        &GetChildrenRequest {
            path: path.into(),
            watch,
        },
    )
    .unwrap()
}

pub fn bytes<R: Record>(r: &R) -> Vec<u8> {
    r.to_bytes()
}
