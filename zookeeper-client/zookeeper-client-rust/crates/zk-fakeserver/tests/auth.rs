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

//! Integration tests for client authentication, ACL enforcement, and `whoAmI`.

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

use common::{WAIT, acl, create_ok, get_data, open, set_data};
use zk_fakeserver::{
    AuthPolicy, ClusterConfig, FakeServer, RawClient, ServerOptions, digest_id, perms,
};
use zk_jute::proto::{
    AuthPacket, CreateRequest, GetACLRequest, GetACLResponse, SetACLRequest, WhoAmIResponse,
};
use zk_jute::rc::{ZAUTHFAILED, ZBADVERSION, ZINVALIDACL, ZNOAUTH};
use zk_jute::{JBuffer, JVec, opcode, xid};

fn add_auth(c: &mut RawClient, scheme: &str, creds: &str) -> i32 {
    c.send(
        xid::AUTH,
        opcode::SET_AUTH,
        &common::bytes(&AuthPacket {
            r#type: 0,
            scheme: scheme.into(),
            auth: JBuffer::from(creds.as_bytes()),
        }),
    )
    .unwrap();
    let r = c.read_reply(WAIT).unwrap();
    assert_eq!(r.xid, xid::AUTH);
    r.err
}

fn who(c: &mut RawClient) -> Vec<(String, String)> {
    let r: WhoAmIResponse = c.call_empty(opcode::WHO_AM_I).unwrap().decode().unwrap();
    r.client_info
        .iter()
        .map(|i| {
            (
                i.auth_scheme.as_str().unwrap().to_owned(),
                i.user.as_str().unwrap().to_owned(),
            )
        })
        .collect()
}

#[test]
fn digest_acl_enforced() {
    let server = FakeServer::start().unwrap();
    let mut alice = open(&server);
    let mut anon = open(&server);
    assert_eq!(add_auth(&mut alice, "digest", "alice:secret"), 0);
    assert_eq!(
        who(&mut alice),
        vec![
            ("ip".to_owned(), "127.0.0.1".to_owned()),
            ("digest".to_owned(), "alice".to_owned())
        ]
    );
    let req = CreateRequest {
        path: "/priv".into(),
        data: JBuffer::from(&b"s"[..]),
        acl: JVec::from(vec![acl(perms::ALL, "auth", "")]),
        flags: 0,
    };
    assert_eq!(anon.call(opcode::CREATE, &req).unwrap().err, ZINVALIDACL);
    assert_eq!(alice.call(opcode::CREATE, &req).unwrap().err, 0);
    assert_eq!(get_data(&mut anon, "/priv", false).err, ZNOAUTH);
    assert_eq!(get_data(&mut alice, "/priv", false).err, 0);
    assert_eq!(set_data(&mut anon, "/priv", b"x", -1).err, ZNOAUTH);
    let acl_now = server.cluster().node("/priv").unwrap().acl;
    assert_eq!(
        acl_now[0].id.id.as_str(),
        Some(digest_id("alice:secret").as_str())
    );

    // `getACL` with `READ` permission but without `ADMIN` redacts the digest credential hash.
    let shared = CreateRequest {
        path: "/shared".into(),
        data: JBuffer::Null,
        acl: JVec::from(vec![
            acl(perms::READ, "world", "anyone"),
            acl(perms::ALL, "digest", &digest_id("alice:secret")),
        ]),
        flags: 0,
    };
    assert_eq!(alice.call(opcode::CREATE, &shared).unwrap().err, 0);
    let g: GetACLResponse = anon
        .call(
            opcode::GET_ACL,
            &GetACLRequest {
                path: "/shared".into(),
            },
        )
        .unwrap()
        .decode()
        .unwrap();
    assert_eq!(g.acl.as_slice().unwrap()[1].id.id.as_str(), Some("alice:x"));

    let set = |c: &mut RawClient, version| {
        c.call(
            opcode::SET_ACL,
            &SetACLRequest {
                path: "/shared".into(),
                acl: JVec::from(vec![acl(perms::ALL, "world", "anyone")]),
                version,
            },
        )
        .unwrap()
        .err
    };
    assert_eq!(set(&mut anon, -1), ZNOAUTH);
    assert_eq!(set(&mut alice, 3), ZBADVERSION);
    assert_eq!(set(&mut alice, 0), 0);
    assert_eq!(server.cluster().node("/shared").unwrap().stat.aversion, 1);
    assert_eq!(get_data(&mut anon, "/shared", false).err, 0);
}

#[test]
fn ip_acl_and_super() {
    let server = FakeServer::start_with(
        ClusterConfig {
            super_digest: Some(digest_id("super:test")),
            ..ClusterConfig::default()
        },
        ServerOptions::default(),
    )
    .unwrap();
    let mut c = open(&server);
    let req = |path: &str, ip: &str| CreateRequest {
        path: path.into(),
        data: JBuffer::Null,
        acl: JVec::from(vec![acl(perms::READ, "ip", ip)]),
        flags: 0,
    };
    assert_eq!(
        c.call(opcode::CREATE, &req("/local", "127.0.0.0/8"))
            .unwrap()
            .err,
        0
    );
    assert_eq!(
        c.call(opcode::CREATE, &req("/far", "10.0.0.1"))
            .unwrap()
            .err,
        0
    );
    assert_eq!(
        c.call(opcode::CREATE, &req("/bad", "10.0.0.1/40"))
            .unwrap()
            .err,
        ZINVALIDACL
    );
    assert_eq!(get_data(&mut c, "/local", false).err, 0);
    assert_eq!(get_data(&mut c, "/far", false).err, ZNOAUTH);
    let mut root = open(&server);
    assert_eq!(add_auth(&mut root, "digest", "super:test"), 0);
    assert_eq!(get_data(&mut root, "/far", false).err, 0);
    assert!(who(&mut root).contains(&("super".to_owned(), String::new())));
}

#[test]
fn auth_policies() {
    let server = FakeServer::start_with(
        ClusterConfig {
            auth: AuthPolicy::Only(vec!["bob:pw".into()]),
            ..ClusterConfig::default()
        },
        ServerOptions::default(),
    )
    .unwrap();
    let mut ok = open(&server);
    assert_eq!(add_auth(&mut ok, "digest", "bob:pw"), 0);
    let mut bad = open(&server);
    assert_eq!(add_auth(&mut bad, "digest", "bob:nope"), ZAUTHFAILED);
    assert!(bad.wait_closed(WAIT));
    let mut odd = open(&server);
    assert_eq!(add_auth(&mut odd, "kerberos", "x"), ZAUTHFAILED);
    assert!(odd.wait_closed(WAIT));
    create_ok(&mut ok, "/fine", b"", 0);
}

#[test]
fn digest_matches_java() {
    // Reference digest produced by Java's `DigestAuthenticationProvider` for `"super:test"`,
    // as documented in the ZooKeeper Administrator's Guide.
    assert_eq!(
        digest_id("super:test"),
        "super:D/InIHSb7yEEbrWz8b9l71RjZJU="
    );
}
