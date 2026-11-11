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

//! Verification of core value types, path validation invariants, create modes, and TTL bounds.
//!
//! Evaluates equivalence between high-level path parsing ([`ZPath`], [`ZPathBuf`]) and strict
//! wire protocol validation rules via property tests, verifies client side path checking
//! discipline for sequential node creation, asserts create mode classification predicates, and
//! validates wire encoding of extended znode modes.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

mod common;

use std::time::Duration;

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    Client, CreateMode, EphemeralKind, Error, PathErrorReason, Ttl, ZPath, ZPathBuf,
};
use proptest::prelude::*;
use zk_fakeserver::{ClusterConfig, FakeServer, FrameKind, ServerOptions};
use zk_jute::opcode;
use zk_proto::native::{PathErrorKind, validate_path_strict};

use common::within;

/// Retains static string references under Miri execution to satisfy static root reachability
/// checks.
static LEAKED: std::sync::Mutex<Vec<&'static str>> = std::sync::Mutex::new(Vec::new());

/// Allocates a static string slice to facilitate compile-time [`ZPath::from_static`] evaluation.
fn leak(s: &str) -> &'static str {
    let leaked: &'static str = Box::leak(s.to_owned().into_boxed_str());
    if cfg!(miri) {
        LEAKED.lock().unwrap().push(leaked);
    }
    leaked
}

/// Maps wire protocol path error classifications to public [`PathErrorReason`] variants.
fn same_reason(kind: PathErrorKind, reason: PathErrorReason) -> bool {
    match kind {
        PathErrorKind::Empty => reason == PathErrorReason::Empty,
        PathErrorKind::NotAbsolute => reason == PathErrorReason::NotAbsolute,
        PathErrorKind::TrailingSlash => reason == PathErrorReason::TrailingSlash,
        PathErrorKind::NulCharacter => reason == PathErrorReason::InvalidChar('\0'),
        PathErrorKind::EmptyComponent => reason == PathErrorReason::EmptyComponent,
        PathErrorKind::RelativeComponent => reason == PathErrorReason::RelativeComponent,
        PathErrorKind::InvalidCharacter => matches!(reason, PathErrorReason::InvalidChar(_)),
    }
}

/// Generates syntactically varied string candidates probing character boundaries, control
/// characters, and Unicode codepoints.
fn near_path() -> impl Strategy<Value = String> {
    let piece = prop_oneof![
        Just("/".to_owned()),
        Just(".".to_owned()),
        Just("..".to_owned()),
        "[a-z0-9_-]{1,3}",
        Just("\u{0}".to_owned()),
        Just("\u{1f}".to_owned()),
        Just("\u{7f}".to_owned()),
        Just("\u{9f}".to_owned()),
        Just("\u{a0}".to_owned()),
        Just("\u{d7ff}".to_owned()),
        Just("\u{e000}".to_owned()),
        Just("\u{f8ff}".to_owned()),
        Just("\u{f900}".to_owned()),
        Just("\u{fff0}".to_owned()),
        Just("\u{fffe}".to_owned()),
        Just("\u{10000}".to_owned()),
        Just("\u{e9}".to_owned()),
    ];
    proptest::collection::vec(piece, 0..8).prop_map(|v| v.concat())
}

proptest! {
    // Under Miri execution, constrain iteration counts to bound interpretation latency.
    #![proptest_config(ProptestConfig::with_cases(if cfg!(miri) { 4 } else { 2_000 }))]

    /// Asserts strict equivalence between [`ZPath`] constructor validation and underlying protocol
    /// specification rules.
    #[test]
    fn the_path_types_agree_with_the_proto_check(s in prop_oneof![near_path(), any::<String>()]) {
        let want = validate_path_strict(&s, false);
        let got = ZPath::new(&s);
        prop_assert_eq!(got.is_ok(), want.is_ok(), "{:?}", s);
        prop_assert_eq!(ZPathBuf::new(s.clone()).is_ok(), want.is_ok(), "{:?}", s);
        if let (Err(w), Err(g)) = (&want, &got) {
            prop_assert!(same_reason(w.kind, g.reason), "{:?}: {:?} vs {:?}", s, w, g);
            prop_assert_eq!(&g.path, &s);
            prop_assert!(g.index <= s.len());
        }
        if let Ok(p) = got {
            prop_assert_eq!(p.as_str(), s.as_str());
        }
        let lit = ZPath::from_static(leak(&s));
        prop_assert_eq!(lit.is_some(), want.is_ok(), "from_static {:?}", s);
        if let Some(p) = lit {
            prop_assert_eq!(p.as_str(), s.as_str());
        }
    }

    /// Asserts that path concatenation preserves canonical hierarchical relationships, name
    /// extraction, and parent resolution.
    #[test]
    fn join_gives_the_parts_back(base in "(/[a-z]{1,4}){0,3}", child in "[a-z0-9]{1,4}") {
        let base = if base.is_empty() { "/".to_owned() } else { base };
        let p = ZPath::new(&base).unwrap();
        let j = p.join(&child).unwrap();
        prop_assert!(validate_path_strict(j.as_str(), false).is_ok());
        prop_assert_eq!(j.as_path().name(), Some(child.as_str()));
        prop_assert_eq!(j.as_path().parent(), Some(p));
        prop_assert!(j.as_path().starts_with(p));
    }
}

/// Asserts that sequential creation permits trailing slashes, validating path correctness prior
/// to wire transmission.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
async fn a_sequential_prefix_is_checked_with_a_suffix() {
    let fs = common::fake();
    let c: Client = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    within(c.create("/jobs", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    let (p, _) = within(c.create(
        "/jobs/",
        b"",
        CreateMode::EphemeralSequential,
        OPEN_ACL_UNSAFE,
    ))
    .await
    .unwrap();
    assert_eq!(p.as_str(), "/jobs/0000000000");
    let sent = creates(&fs);
    for mode in [
        CreateMode::Persistent,
        CreateMode::Ephemeral,
        CreateMode::Container,
    ] {
        let r = within(c.create("/jobs/", b"", mode, OPEN_ACL_UNSAFE)).await;
        assert!(matches!(r, Err(Error::InvalidPath(_))), "{mode:?}: {r:?}");
    }
    // Consecutive slash separators remain invalid regardless of sequential creation mode.
    let r = within(c.create(
        "/jobs//",
        b"",
        CreateMode::PersistentSequential,
        OPEN_ACL_UNSAFE,
    ))
    .await;
    assert!(matches!(r, Err(Error::InvalidPath(_))), "{r:?}");
    assert_eq!(creates(&fs), sent, "a refused create must not go out");
    within(c.close()).await.unwrap();
}

/// Computes total node creation requests recorded in the server packet transcript.
fn creates(fs: &FakeServer) -> usize {
    fs.transcript()
        .iter()
        .filter(|r| r.kind == FrameKind::Request)
        .filter(|r| {
            matches!(
                r.op,
                Some(
                    opcode::CREATE
                        | opcode::CREATE2
                        | opcode::CREATE_CONTAINER
                        | opcode::CREATE_TTL
                )
            )
        })
        .count()
}

/// Validates classification predicates governing sequential naming, ephemeral ownership, and TTL
/// metadata.
#[test]
fn the_mode_predicates() {
    let t = Ttl::from_millis(1_000).unwrap();
    let table = [
        (CreateMode::Persistent, false, false, None),
        (CreateMode::PersistentSequential, true, false, None),
        (CreateMode::Ephemeral, false, true, None),
        (CreateMode::EphemeralSequential, true, true, None),
        (CreateMode::Container, false, false, None),
        (CreateMode::PersistentWithTtl(t), false, false, Some(t)),
        (
            CreateMode::PersistentSequentialWithTtl(t),
            true,
            false,
            Some(t),
        ),
    ];
    for (mode, seq, eph, ttl) in table {
        assert_eq!(mode.is_sequential(), seq, "{mode:?}");
        assert_eq!(mode.is_ephemeral(), eph, "{mode:?}");
        assert_eq!(mode.ttl(), ttl, "{mode:?}");
    }
}

/// Asserts numeric boundaries and millisecond resolution limits enforced on [`Ttl`] durations.
#[test]
fn the_ttl_bounds() {
    assert!(matches!(
        Ttl::from_millis(0),
        Err(Error::InvalidArgument(_))
    ));
    assert_eq!(Ttl::from_millis(1).unwrap().as_millis(), 1);
    assert_eq!(Ttl::from_millis(0xFF_FFFF_FFFF).unwrap(), Ttl::MAX);
    assert!(matches!(
        Ttl::from_millis(0x100_0000_0000),
        Err(Error::InvalidArgument(_))
    ));
    assert!(Ttl::from_millis(u64::MAX).is_err());
    assert!(Ttl::new(Duration::from_micros(999)).is_err());
    assert_eq!(
        Ttl::new(Duration::from_micros(1_999)).unwrap().as_millis(),
        1
    );
    assert!(Ttl::new(Duration::MAX).is_err());
    assert_eq!(
        Ttl::MAX.as_duration(),
        Duration::from_millis(0xFF_FFFF_FFFF)
    );
}

fn ttl_fake(enabled: bool) -> FakeServer {
    let cfg = ClusterConfig {
        ttl_enabled: enabled,
        ..ClusterConfig::default()
    };
    FakeServer::start_with(cfg, ServerOptions::default()).unwrap()
}

/// Validates wire protocol dispatch of [`CreateMode::PersistentWithTtl`] and
/// [`CreateMode::Container`] operations.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[cfg_attr(
    miri,
    ignore = "Miri has no fcntl(F_SETFD), which sets CLOEXEC on the waker pipe"
)]
async fn ttl_and_container_modes_on_the_wire() {
    let fs = ttl_fake(true);
    let c: Client = within(common::builder(&fs.connect_string()).connect())
        .await
        .unwrap();
    let ttl = Ttl::new(Duration::from_secs(90)).unwrap();
    let (_, st) = within(c.create(
        "/t",
        b"",
        CreateMode::PersistentWithTtl(ttl),
        OPEN_ACL_UNSAFE,
    ))
    .await
    .unwrap();
    assert_eq!(
        st.ephemeral_kind(),
        EphemeralKind::Ttl(Duration::from_secs(90))
    );
    let (_, st) = within(c.create("/k", b"", CreateMode::Container, OPEN_ACL_UNSAFE))
        .await
        .unwrap();
    assert_eq!(st.ephemeral_kind(), EphemeralKind::Container);
    let ops: Vec<i32> = fs
        .transcript()
        .iter()
        .filter(|r| r.kind == FrameKind::Request)
        .filter_map(|r| r.op)
        .collect();
    assert!(ops.contains(&opcode::CREATE_TTL), "{ops:?}");
    assert!(ops.contains(&opcode::CREATE_CONTAINER), "{ops:?}");
    within(c.close()).await.unwrap();

    let off = ttl_fake(false);
    let c: Client = within(common::builder(&off.connect_string()).connect())
        .await
        .unwrap();
    let r = within(c.create(
        "/t",
        b"",
        CreateMode::PersistentWithTtl(ttl),
        OPEN_ACL_UNSAFE,
    ))
    .await;
    assert_eq!(r.err(), Some(Error::Unimplemented));
    within(c.close()).await.unwrap();
}
