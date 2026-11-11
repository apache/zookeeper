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

//! Domain value integration tests: canonical path grammar, wire error conversions,
//! connection string syntax validation, DIGEST-MD5 identity generation, and property fuzzing.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects
)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::collections::HashSet;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    Acl, CreateMode, DisconnectCause, Error, Id, MultiError, OpKind, PathErrorReason, Perms,
    WatchError, ZPath, ZPathBuf,
};

/// Statically validated path constant representing the ZooKeeper cluster configuration znode.
const CONFIG_NODE: ZPath<'static> = ZPath::from_static("/zookeeper/config").unwrap();
/// Statically validated path constant representing the root namespace.
const ROOT: ZPath<'static> = ZPath::from_static("/").unwrap();

// ---- paths ----

fn reason(s: &str) -> PathErrorReason {
    ZPath::new(s).unwrap_err().reason
}

#[test]
fn const_paths_compile() {
    assert_eq!(CONFIG_NODE.as_str(), "/zookeeper/config");
    assert_eq!(CONFIG_NODE.name(), Some("config"));
    assert_eq!(CONFIG_NODE.parent().unwrap().as_str(), "/zookeeper");
    assert!(ROOT.is_root());
    assert_eq!(ROOT, ZPath::ROOT);
}

#[test]
fn path_edge_cases() {
    assert!(ZPath::new("/").is_ok());
    assert_eq!(reason(""), PathErrorReason::Empty);
    assert_eq!(reason("a/b"), PathErrorReason::NotAbsolute);
    assert_eq!(reason("/a/"), PathErrorReason::TrailingSlash);
    assert_eq!(reason("//"), PathErrorReason::TrailingSlash);
    assert_eq!(reason("//a"), PathErrorReason::EmptyComponent);
    assert_eq!(reason("/."), PathErrorReason::RelativeComponent);
    assert_eq!(reason("/a/.."), PathErrorReason::RelativeComponent);
    assert_eq!(reason("/a/./b"), PathErrorReason::RelativeComponent);
    assert!(ZPath::new("/...").is_ok());
    assert!(ZPath::new("/.a/b.").is_ok());
    assert_eq!(reason("/a\0"), PathErrorReason::InvalidChar('\0'));
    for c in ['\u{1}', '\u{1f}', '\u{7f}', '\u{80}', '\u{9f}'] {
        assert_eq!(reason(&format!("/x{c}")), PathErrorReason::InvalidChar(c));
    }
    // Unicode reserved and surrogate codepoints are rejected by path validation.
    for c in ['\u{e000}', '\u{f8ff}', '\u{fff0}', '\u{ffff}'] {
        assert_eq!(reason(&format!("/x{c}")), PathErrorReason::InvalidChar(c));
    }
    // Supplementary characters outside the BMP are rejected for compatibility with Java ZooKeeper.
    assert!(ZPath::new("/x\u{1f600}").is_err());
    assert!(ZPath::new("/caf\u{e9}/\u{4e2d}").is_ok());
    assert!(ZPath::new("/x\u{f900}").is_ok());
}

#[test]
fn path_error_text() {
    let e = ZPath::new("/a/").unwrap_err();
    assert_eq!(e.path, "/a/");
    assert_eq!(e.index, 2);
    assert_eq!(
        e.to_string(),
        "invalid path string \"/a/\" caused by path must not end with / character @2"
    );
    let err: Error = e.clone().into();
    assert_eq!(err, Error::InvalidPath(e));
    assert_eq!(err.code(), -8);
}

#[test]
fn path_helpers() {
    let p = ZPath::new("/app/locks/l-1").unwrap();
    assert_eq!(p.components().collect::<Vec<_>>(), ["app", "locks", "l-1"]);
    assert_eq!(p.name(), Some("l-1"));
    assert_eq!(p.parent().unwrap().as_str(), "/app/locks");
    assert!(p.starts_with(ZPath::new("/app").unwrap()));
    assert!(p.starts_with(ZPath::new("/app/locks/l-1").unwrap()));
    assert!(!p.starts_with(ZPath::new("/ap").unwrap()));
    assert!(
        !ZPath::new("/app2")
            .unwrap()
            .starts_with(ZPath::new("/app").unwrap())
    );
    let child = p.parent().unwrap().join("l-2").unwrap();
    assert_eq!(child, "/app/locks/l-2");
    assert_eq!(
        ZPath::ROOT
            .join("a/b")
            .unwrap()
            .as_path()
            .components()
            .count(),
        2
    );
    assert!(ZPath::ROOT.join("").is_err());
    assert!(p.join("x/").is_err());
    assert_eq!(ZPath::ROOT.components().next(), None);
    assert_eq!(ZPath::ROOT.parent(), None);
    let owned = ZPathBuf::new("/a/b".to_owned()).unwrap();
    assert_eq!(owned.as_path().parent().unwrap().as_str(), "/a");
}

// ---- errors ----

#[test]
fn error_codes_round_trip() {
    let codes = [
        -1, -2, -3, -4, -5, -6, -7, -8, -9, -12, -13, -14, -15, -100, -101, -102, -103, -108, -110,
        -111, -112, -113, -114, -115, -116, -117, -118, -119, -120, -121, -122, -123, -124, -125,
        -127,
    ];
    let mut texts = HashSet::new();
    for code in codes {
        let e = Error::from_code(code).unwrap();
        assert!(!matches!(e, Error::Unknown(_)), "{code}");
        assert_eq!(e.code(), code);
        assert!(texts.insert(e.to_string()), "{code}");
    }
    assert_eq!(Error::from_code(0), None);
    assert_eq!(Error::from_code(-126), Some(Error::Unknown(-126)));
    assert_eq!(Error::Unknown(-126).code(), -126);
    assert_eq!(Error::TooManyPendingRequests.code(), -127);
    assert_eq!(Error::InvalidArgument("x".into()).code(), -8);
}

#[test]
fn error_display_texts() {
    let cases = [
        (Error::NoNode, "KeeperErrorCode = NoNode"),
        (Error::NodeExists, "KeeperErrorCode = NodeExists"),
        (Error::BadVersion, "KeeperErrorCode = BadVersion"),
        (Error::NoAuth, "KeeperErrorCode = NoAuth"),
        (Error::NotEmpty, "KeeperErrorCode = Directory not empty"),
        (Error::SessionExpired, "KeeperErrorCode = Session expired"),
        (Error::ConnectionLoss, "KeeperErrorCode = ConnectionLoss"),
        (Error::NotReadOnly, "KeeperErrorCode = Not a read-only call"),
        (
            Error::ReconfigDisabled,
            "KeeperErrorCode = Reconfig is disabled",
        ),
        (Error::QuotaExceeded, "KeeperErrorCode = Quota has exceeded"),
        (
            Error::ThrottledOp,
            "KeeperErrorCode = Op throttled due to high load",
        ),
        (
            Error::SslConnectionError,
            "KeeperErrorCode = SSL connection error",
        ),
        (Error::Closing, "KeeperErrorCode = Closing"),
        (Error::Unknown(-77), "KeeperErrorCode = Unknown error -77"),
        (
            Error::InvalidArgument("bad port".into()),
            "invalid argument: bad port",
        ),
    ];
    for (e, want) in cases {
        assert_eq!(e.to_string(), want);
    }
}

#[test]
fn error_predicates() {
    assert!(Error::ConnectionLoss.is_connection_loss());
    assert!(Error::OperationTimeout.is_connection_loss());
    assert!(Error::SslConnectionError.is_connection_loss());
    assert!(Error::RuntimeInconsistency.is_connection_loss());
    assert!(!Error::NoNode.is_connection_loss());
    assert!(!Error::SessionExpired.is_connection_loss());
    assert!(Error::SessionExpired.is_session_terminal());
    assert!(Error::AuthFailed.is_session_terminal());
    assert!(Error::Closing.is_session_terminal());
    assert!(!Error::ConnectionLoss.is_session_terminal());
    assert!(Error::ConnectionLoss.is_retry_safe(OpKind::GetData));
    assert!(Error::ConnectionLoss.is_retry_safe(OpKind::Exists));
    assert!(Error::ConnectionLoss.is_retry_safe(OpKind::MultiRead));
    assert!(!Error::ConnectionLoss.is_retry_safe(OpKind::Create));
    assert!(!Error::ConnectionLoss.is_retry_safe(OpKind::SetData));
    assert!(!Error::ConnectionLoss.is_retry_safe(OpKind::Multi));
    assert!(!Error::ConnectionLoss.is_retry_safe(OpKind::Reconfig));
    assert!(!Error::ConnectionLoss.is_retry_safe(OpKind::AddAuth));
    assert!(Error::NodeExists.is_retry_safe(OpKind::Create));
}

#[test]
fn multi_watch_and_disconnect_texts() {
    let e = MultiError::Aborted {
        index: 2,
        error: Error::BadVersion,
        per_op: vec![None, None, Some(Error::BadVersion)],
    };
    assert_eq!(
        e.to_string(),
        "transaction rolled back: operation 2 failed: KeeperErrorCode = BadVersion"
    );
    assert_eq!(Error::from(e), Error::BadVersion);
    let e = MultiError::from(Error::ConnectionLoss);
    assert_eq!(e.to_string(), "KeeperErrorCode = ConnectionLoss");

    assert_eq!(WatchError::Removed.to_string(), "the watcher was removed");
    assert_eq!(WatchError::Closed.to_string(), "the client is closed");
    assert_eq!(
        WatchError::SessionExpired.to_string(),
        "the session expired"
    );
    assert_eq!(WatchError::AuthFailed.to_string(), "authentication failed");
    assert_eq!(
        WatchError::ConnectionLost.to_string(),
        "the watch was not restored after the connection was lost"
    );
    assert_eq!(
        WatchError::Lagged { missed: 12 }.to_string(),
        "the watcher dropped 12 events because its buffer was full"
    );
    assert_eq!(
        WatchError::InvalidState.to_string(),
        "a blocking wait is not allowed on the IO side of the client"
    );

    let causes = [
        (
            DisconnectCause::ReceiveTimeout,
            "no frame arrived within 2/3 of the session timeout",
        ),
        (
            DisconnectCause::TlsHandshakeTimeout,
            "the TLS handshake did not end before its deadline",
        ),
        (
            DisconnectCause::XidMismatch,
            "a reply did not match the oldest pending request",
        ),
        (
            DisconnectCause::MalformedFrame,
            "a frame from the server was malformed or too large",
        ),
        (
            DisconnectCause::ServerClosed,
            "the server closed the connection",
        ),
    ];
    for (c, want) in causes {
        assert_eq!(c.to_string(), want);
    }
}

// ---- digest identities ----

#[test]
fn digest_ids_match_java() {
    // Reference vectors generated from Java `DigestAuthenticationProvider.generateDigest`.
    assert_eq!(
        Id::digest("super", "test"),
        Id::new("digest", "super:D/InIHSb7yEEbrWz8b9l71RjZJU=")
    );
    assert_eq!(
        Id::digest("alice", "secret").id,
        "alice:aYXlLOpEooaV1cRAvUL1fp9Qt7E="
    );
    for (u, p) in [
        ("u", "p"),
        ("bob", ""),
        ("a:b", "c"),
        ("\u{e9}t\u{e9}", "\u{1f600}"),
    ] {
        assert_eq!(
            Id::digest(u, p).id,
            zk_fakeserver::digest_id(&format!("{u}:{p}"))
        );
    }
}

// ---- no panic on any input ----

/// Configures property-based fuzz testing parameters, using reduced iterations under Miri.
fn config() -> proptest::test_runner::Config {
    let mut c = proptest::test_runner::Config::default();
    if cfg!(miri) {
        c.cases = 4;
    }
    c
}

proptest::proptest! {
    #![proptest_config(config())]
    #[test]
    fn zpath_new_never_panics(s in proptest::prelude::any::<String>()) {
        if let Ok(p) = ZPath::new(&s) {
            let n = p.components().count();
            let mut up = 0usize;
            let mut cur = p;
            while let Some(parent) = cur.parent() {
                assert!(cur.starts_with(parent));
                cur = parent;
                up += 1;
            }
            assert_eq!(up, n);
            assert!(p.starts_with(ZPath::ROOT));
        }
    }

    #[test]
    fn zpath_join_never_panics(base in "(/[a-z.]{1,3}){0,3}", child in "\\PC{0,8}") {
        let base = if base.is_empty() { "/".to_owned() } else { base };
        if let Ok(b) = ZPath::new(&base) {
            if let Ok(joined) = b.join(&child) {
                assert!(joined.as_path().starts_with(b));
            }
        }
    }

    #[test]
    fn error_codes_never_panic(code in proptest::prelude::any::<i32>()) {
        match Error::from_code(code) {
            None => assert_eq!(code, 0),
            Some(e) => {
                assert_eq!(e.code(), code);
                let _ = e.to_string();
            }
        }
    }
}

// ---- through the client ----

/// Invariant: connection string parsing errors return `Error::InvalidArgument`.
#[test]
fn build_rejects_bad_connect_strings() {
    for bad in [
        "", "  ", ",", "h:abc", "h:70000", "[::1", "h:1/app/", "h:1/a//b",
    ] {
        match common::builder(bad).build() {
            Err(e) => assert!(
                matches!(e.error(), Error::InvalidArgument(_)),
                "{bad:?}: {e}"
            ),
            Ok(_) => panic!("{bad:?} built"),
        }
    }
}

/// Invariant: ACL nodes configured with `Id::digest` enforce authentication,
/// granting access only to sessions supplying matching credentials via `add_auth`.
#[tokio::test(flavor = "multi_thread")]
#[ignore = "real-server"]
async fn digest_acl_on_a_real_server() {
    let Some(port) = common::real_port(0) else {
        return;
    };
    let Some(server) = common::RealServer::start_or_skip(port) else {
        return;
    };
    let owner = common::builder(&server.connect_string())
        .auth("digest", b"alice:secret".to_vec())
        .connect()
        .await
        .unwrap();
    let acl = [Acl {
        perms: Perms::ALL,
        id: Id::digest("alice", "secret"),
    }];
    let path = "/wp1-digest";
    let _ = owner.delete(path, None).await;
    owner
        .create(path, b"x", CreateMode::Persistent, &acl)
        .await
        .unwrap();

    let other = common::builder(&server.connect_string())
        .connect()
        .await
        .unwrap();
    assert_eq!(other.get_data(path).await.unwrap_err(), Error::NoAuth);
    let (data, _) = owner.get_data(path).await.unwrap();
    assert_eq!(data, b"x");
    let (acls, _) = owner.get_acl(path).await.unwrap();
    assert_eq!(acls, acl);

    owner.delete(path, None).await.unwrap();
    assert!(owner.exists(path).await.unwrap().is_none());
    let _ = owner
        .create("/wp1-open", b"", CreateMode::Ephemeral, OPEN_ACL_UNSAFE)
        .await
        .unwrap();
    other.close().await.unwrap();
    owner.close().await.unwrap();
}
