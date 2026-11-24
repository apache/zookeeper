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

//! Integration tests for data tree operations, protocol error codes, and `multi` batches.

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

use common::{
    create, create_ok, create_req, data_of, delete, exists, get_children, get_data, open, open_acl,
    set_data,
};
use zk_fakeserver::{ClusterConfig, FakeServer, ServerOptions, create_mode};
use zk_jute::proto::{
    CheckVersionRequest, Create2Response, CreateRequest, CreateResponse, CreateTTLRequest,
    DeleteRequest, ErrorResponse, ExistsResponse, GetAllChildrenNumberRequest,
    GetAllChildrenNumberResponse, GetChildren2Request, GetChildren2Response, GetChildrenRequest,
    GetChildrenResponse, GetDataRequest, GetDataResponse, GetEphemeralsRequest,
    GetEphemeralsResponse, MultiHeader, SetDataRequest, SetDataResponse, SyncRequest, SyncResponse,
};
use zk_jute::rc::{
    ZBADARGUMENTS, ZBADVERSION, ZINVALIDACL, ZMARSHALLINGERROR, ZNOCHILDRENFOREPHEMERALS,
    ZNODEEXISTS, ZNONODE, ZNOTEMPTY, ZRUNTIMEINCONSISTENCY, ZUNIMPLEMENTED,
};
use zk_jute::{JBuffer, JVec, Reader, Record, opcode};

#[test]
fn create_get_set_delete() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    let z0 = server.cluster().zxid();
    assert_eq!(create_ok(&mut c, "/a", b"one", 0), "/a");
    assert_eq!(data_of(&mut c, "/a"), b"one");
    let r = set_data(&mut c, "/a", b"two", 0);
    assert_eq!(r.err, 0);
    let s: SetDataResponse = r.decode().unwrap();
    assert_eq!(s.stat.version, 1);
    assert_eq!(s.stat.czxid, z0 + 1);
    assert_eq!(s.stat.mzxid, z0 + 2);
    assert_eq!(s.stat.data_length, 3);
    assert_eq!(r.zxid, z0 + 2);
    assert_eq!(set_data(&mut c, "/a", b"x", 0).err, ZBADVERSION);
    assert_eq!(delete(&mut c, "/a", 5).err, ZBADVERSION);
    let missing = get_data(&mut c, "/nope", false);
    assert_eq!(missing.err, ZNONODE);
    assert!(missing.body.is_empty());
    assert_eq!(create(&mut c, "/a", b"", 0).err, ZNODEEXISTS);
    create_ok(&mut c, "/a/b", b"", 0);
    assert_eq!(delete(&mut c, "/a", -1).err, ZNOTEMPTY);
    assert_eq!(delete(&mut c, "/a/b", -1).err, 0);
    assert_eq!(delete(&mut c, "/a", -1).err, 0);
    assert_eq!(exists(&mut c, "/a", false).err, ZNONODE);
    assert_eq!(create(&mut c, "/x/y", b"", 0).err, ZNONODE);
    assert_eq!(create(&mut c, "bad", b"", 0).err, ZBADARGUMENTS);
    // Parent existence is validated before full path syntax, matching the Java server.
    assert_eq!(create(&mut c, "/bad/", b"", 0).err, ZNONODE);
    assert_eq!(create(&mut c, "/zookeeper/", b"", 0).err, ZBADARGUMENTS);
    assert_eq!(create(&mut c, "/a", b"", 9).err, ZBADARGUMENTS);
    assert_eq!(delete(&mut c, "/zookeeper", -1).err, ZBADARGUMENTS);
    // Failed write transactions still advance the cluster `zxid`.
    assert!(server.cluster().zxid() > z0 + 10);
}

#[test]
fn null_data_and_stat_details() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    let r = c
        .call(
            opcode::CREATE2,
            &CreateRequest {
                path: "/n".into(),
                data: JBuffer::Null,
                acl: open_acl(),
                flags: 0,
            },
        )
        .unwrap();
    let resp: Create2Response = r.decode().unwrap();
    assert_eq!(resp.path.as_str(), Some("/n"));
    assert_eq!(resp.stat.data_length, 0);
    assert_eq!(resp.stat.ctime, ClusterConfig::default().epoch_ms + r.zxid);
    let d: GetDataResponse = get_data(&mut c, "/n", false).decode().unwrap();
    assert!(d.data.is_null());
    let root: ExistsResponse = exists(&mut c, "/", false).decode().unwrap();
    assert_eq!(root.stat.num_children, 2);
    assert_eq!(root.stat.cversion, 1);
    let bad_acl = CreateRequest {
        path: "/acl".into(),
        data: JBuffer::Null,
        acl: JVec::from(vec![]),
        flags: 0,
    };
    assert_eq!(c.call(opcode::CREATE, &bad_acl).unwrap().err, ZINVALIDACL);
}

#[test]
fn sequential_and_ephemeral() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/q", b"", 0);
    let a = create_ok(&mut c, "/q/n-", b"", create_mode::PERSISTENT_SEQUENTIAL);
    let b = create_ok(&mut c, "/q/n-", b"", create_mode::EPHEMERAL_SEQUENTIAL);
    assert_eq!(a, "/q/n-0000000000");
    assert_eq!(b, "/q/n-0000000001");
    create_ok(&mut c, "/q/other", b"", 0);
    assert_eq!(
        create_ok(&mut c, "/q/n-", b"", create_mode::PERSISTENT_SEQUENTIAL),
        "/q/n-0000000003"
    );
    // Deleting a child does not increment the parent's `cversion` sequential counter.
    assert_eq!(delete(&mut c, "/q/other", -1).err, 0);
    assert_eq!(
        create_ok(&mut c, "/q/n-", b"", create_mode::PERSISTENT_SEQUENTIAL),
        "/q/n-0000000004"
    );
    assert_eq!(
        create(&mut c, &format!("{b}/child"), b"", 0).err,
        ZNOCHILDRENFOREPHEMERALS
    );
    let e: GetEphemeralsResponse = c
        .call(
            opcode::GET_EPHEMERALS,
            &GetEphemeralsRequest {
                prefix_path: "/q".into(),
            },
        )
        .unwrap()
        .decode()
        .unwrap();
    assert_eq!(e.ephemerals.len(), 1);
    let stat: ExistsResponse = exists(&mut c, &b, false).decode().unwrap();
    let sid = server.cluster().sessions()[0].id;
    assert_eq!(stat.stat.ephemeral_owner, sid);
    drop(c);
    // Dropping the TCP connection preserves the session and its ephemerals until expiration.
    assert!(server.cluster().node(&b).is_some());
    assert!(server.cluster().expire_session(sid));
    assert!(server.cluster().node(&b).is_none());
    assert!(server.cluster().node(&a).is_some());
}

#[test]
fn children_counts_sync_check() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/p", b"", 0);
    create_ok(&mut c, "/p/b", b"", 0);
    create_ok(&mut c, "/p/a", b"", 0);
    create_ok(&mut c, "/p/a/x", b"", 0);
    let g: GetChildrenResponse = get_children(&mut c, "/p", false).decode().unwrap();
    let names: Vec<_> = g.children.iter().map(|s| s.as_str().unwrap()).collect();
    assert_eq!(names, vec!["a", "b"]);
    let g2: GetChildren2Response = c
        .call(
            opcode::GET_CHILDREN2,
            &GetChildren2Request {
                path: "/p".into(),
                watch: false,
            },
        )
        .unwrap()
        .decode()
        .unwrap();
    assert_eq!(g2.stat.num_children, 2);
    let n: GetAllChildrenNumberResponse = c
        .call(
            opcode::GET_ALL_CHILDREN_NUMBER,
            &GetAllChildrenNumberRequest { path: "/p".into() },
        )
        .unwrap()
        .decode()
        .unwrap();
    assert_eq!(n.total_number, 3);
    let s: SyncResponse = c
        .call(opcode::SYNC, &SyncRequest { path: "/p".into() })
        .unwrap()
        .decode()
        .unwrap();
    assert_eq!(s.path.as_str(), Some("/p"));
    let check = c
        .call(
            opcode::CHECK,
            &CheckVersionRequest {
                path: "/p".into(),
                version: 3,
            },
        )
        .unwrap();
    assert_eq!(check.err, ZBADVERSION);
}

#[test]
fn containers_and_ttl() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    let r = c
        .call(
            opcode::CREATE_CONTAINER,
            &create_req("/box", b"", create_mode::CONTAINER),
        )
        .unwrap();
    assert_eq!(r.err, 0);
    let made: Create2Response = r.decode().unwrap();
    assert_eq!(made.stat.ephemeral_owner, i64::MIN);
    let ttl = |ttl: i64, flags: i32| CreateTTLRequest {
        path: "/ttl".into(),
        data: JBuffer::from(&b"t"[..]),
        acl: open_acl(),
        flags,
        ttl,
    };
    assert_eq!(
        c.call(opcode::CREATE_TTL, &ttl(0, 5)).unwrap().err,
        ZBADARGUMENTS
    );
    assert_eq!(
        c.call(opcode::CREATE_TTL, &ttl(10, 0)).unwrap().err,
        ZBADARGUMENTS
    );
    assert_eq!(c.call(opcode::CREATE_TTL, &ttl(1, 5)).unwrap().err, 0);
    // Newly created containers with `cversion == 0` are not reaped until a child has been removed.
    std::thread::sleep(std::time::Duration::from_millis(5));
    assert!(server.cluster().reap().contains(&"/ttl".to_owned()));
    create_ok(&mut c, "/box/k", b"", 0);
    assert_eq!(delete(&mut c, "/box/k", -1).err, 0);
    assert_eq!(server.cluster().reap(), vec!["/box".to_owned()]);

    let no_ttl = FakeServer::start_with(
        ClusterConfig {
            ttl_enabled: false,
            ..ClusterConfig::default()
        },
        ServerOptions::default(),
    )
    .unwrap();
    let mut c = open(&no_ttl);
    assert_eq!(
        c.call(opcode::CREATE_TTL, &ttl(1, 5)).unwrap().err,
        ZUNIMPLEMENTED
    );
}

#[test]
fn bad_body_and_unknown_opcode() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    let xid = c.take_xid();
    c.send(xid, opcode::GET_DATA, &[0, 0]).unwrap();
    let r = c.read_reply(common::WAIT).unwrap();
    assert_eq!((r.xid, r.err), (xid, ZMARSHALLINGERROR));
    let xid = c.take_xid();
    c.send(xid, 77, &[]).unwrap();
    let r = c.read_reply(common::WAIT).unwrap();
    assert_eq!(r.err, ZUNIMPLEMENTED);
    assert!(c.wait_closed(common::WAIT));
}

fn multi_body(ops: &[(i32, Vec<u8>)]) -> Vec<u8> {
    let mut out = Vec::new();
    for (ty, body) in ops {
        zk_jute::multi::encode_op_header(*ty, &mut out);
        out.extend_from_slice(body);
    }
    zk_jute::multi::encode_end(&mut out);
    out
}

fn results(body: &[u8]) -> Vec<(i32, i32)> {
    let mut r = Reader::new(body);
    let mut out = Vec::new();
    loop {
        let h = MultiHeader::decode(&mut r).unwrap();
        if h.done {
            assert_eq!((h.r#type, h.err), (-1, -1));
            break;
        }
        match h.r#type {
            -1 => {
                let e = ErrorResponse::decode(&mut r).unwrap();
                out.push((-1, e.err));
            }
            1 => {
                CreateResponse::decode(&mut r).unwrap();
                out.push((1, h.err));
            }
            15 => {
                Create2Response::decode(&mut r).unwrap();
                out.push((15, h.err));
            }
            5 => {
                SetDataResponse::decode(&mut r).unwrap();
                out.push((5, h.err));
            }
            4 => {
                GetDataResponse::decode(&mut r).unwrap();
                out.push((4, h.err));
            }
            8 => {
                GetChildrenResponse::decode(&mut r).unwrap();
                out.push((8, h.err));
            }
            t => out.push((t, h.err)),
        }
    }
    out
}

#[test]
fn multi_commit_and_rollback() {
    let server = FakeServer::start().unwrap();
    let mut c = open(&server);
    create_ok(&mut c, "/m", b"", 0);
    let ok = multi_body(&[
        (opcode::CREATE, create_req("/m/a", b"", 0).to_bytes()),
        (opcode::CREATE2, create_req("/m/b", b"", 0).to_bytes()),
        (
            opcode::SET_DATA,
            SetDataRequest {
                path: "/m".into(),
                data: JBuffer::from(&b"v"[..]),
                version: -1,
            }
            .to_bytes(),
        ),
        (
            opcode::CHECK,
            CheckVersionRequest {
                path: "/m".into(),
                version: 1,
            }
            .to_bytes(),
        ),
        (
            opcode::DELETE,
            DeleteRequest {
                path: "/m/b".into(),
                version: -1,
            }
            .to_bytes(),
        ),
    ]);
    let z = server.cluster().zxid();
    let xid = c.take_xid();
    c.send(xid, opcode::MULTI, &ok).unwrap();
    let r = c.read_reply(common::WAIT).unwrap();
    assert_eq!(r.err, 0);
    assert_eq!(r.zxid, z + 1);
    assert_eq!(
        results(&r.body),
        vec![(1, 0), (15, 0), (5, 0), (13, 0), (2, 0)]
    );
    assert!(server.cluster().node("/m/a").is_some());
    assert!(server.cluster().node("/m/b").is_none());

    let bad = multi_body(&[
        (opcode::CREATE, create_req("/m/c", b"", 0).to_bytes()),
        (opcode::CREATE, create_req("/m/a", b"", 0).to_bytes()),
        (opcode::CREATE, create_req("/m/d", b"", 0).to_bytes()),
    ]);
    let xid = c.take_xid();
    c.send(xid, opcode::MULTI, &bad).unwrap();
    let r = c.read_reply(common::WAIT).unwrap();
    assert_eq!(r.err, 0);
    assert_eq!(
        results(&r.body),
        vec![(-1, 0), (-1, ZNODEEXISTS), (-1, ZRUNTIMEINCONSISTENCY)]
    );
    assert!(server.cluster().node("/m/c").is_none());

    let read = multi_body(&[
        (
            opcode::GET_DATA,
            GetDataRequest {
                path: "/m".into(),
                watch: false,
            }
            .to_bytes(),
        ),
        (
            opcode::GET_CHILDREN,
            GetChildrenRequest {
                path: "/none".into(),
                watch: false,
            }
            .to_bytes(),
        ),
        (
            opcode::GET_CHILDREN,
            GetChildrenRequest {
                path: "/m".into(),
                watch: false,
            }
            .to_bytes(),
        ),
    ]);
    let xid = c.take_xid();
    c.send(xid, opcode::MULTI_READ, &read).unwrap();
    let r = c.read_reply(common::WAIT).unwrap();
    assert_eq!(results(&r.body), vec![(4, 0), (-1, ZNONODE), (8, 0)]);

    // Submitting read operations inside a mutating `MULTI` request fails with `ZMARSHALLINGERROR`.
    let xid = c.take_xid();
    c.send(xid, opcode::MULTI, &read).unwrap();
    assert_eq!(c.read_reply(common::WAIT).unwrap().err, ZMARSHALLINGERROR);
}
