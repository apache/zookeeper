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

//! Validation and manipulation tests for `ZPath` and `ZPathBuf`: canonical ZooKeeper path
//! grammar enforcement, error byte offset reporting, compile-time `from_static`, and
//! hierarchical operations.

#![allow(clippy::unwrap_used)]
#![allow(clippy::disallowed_macros)]

use apache_zookeeper::{PathErrorReason, ZPath};

#[test]
fn strict_rules_apply() {
    assert!(ZPath::new("/").is_ok());
    assert!(ZPath::new("/a/b").is_ok());
    let e = ZPath::new("").unwrap_err();
    assert_eq!(e.reason, PathErrorReason::Empty);
    let e = ZPath::new("a").unwrap_err();
    assert_eq!(e.reason, PathErrorReason::NotAbsolute);
    let e = ZPath::new("/a/").unwrap_err();
    assert_eq!(e.reason, PathErrorReason::TrailingSlash);
    assert_eq!(e.index, 2);
    let e = ZPath::new("/a//b").unwrap_err();
    assert_eq!(e.reason, PathErrorReason::EmptyComponent);
    let e = ZPath::new("/a/./b").unwrap_err();
    assert_eq!(e.reason, PathErrorReason::RelativeComponent);
    let e = ZPath::new("/a\u{1}").unwrap_err();
    assert_eq!(e.reason, PathErrorReason::InvalidChar('\u{1}'));
    assert_eq!(e.index, 2);
}

#[test]
fn invalid_char_index_is_a_byte_offset() {
    let e = ZPath::new("/\u{e9}\u{1}").unwrap_err();
    assert_eq!(e.index, 3);
    assert_eq!(e.reason, PathErrorReason::InvalidChar('\u{1}'));
}

const CONST_PATH: ZPath<'static> = ZPath::from_static("/app/config").unwrap();

#[test]
fn from_static_in_a_const_item() {
    assert_eq!(CONST_PATH.as_str(), "/app/config");
    assert_eq!(ZPath::from_static("/").map(ZPath::as_str), Some("/"));
}

#[test]
fn from_static_gives_none_for_a_bad_literal() {
    for bad in [
        "",
        "a",
        "/a/",
        "//",
        "/a/./b",
        "/a/../b",
        "/a\0b",
        "/zookeeper\u{1}",
    ] {
        assert!(ZPath::from_static(bad).is_none(), "{bad:?}");
    }
}

#[test]
fn parent_and_name() {
    let p = ZPath::new("/a/b/c").unwrap();
    assert_eq!(p.name(), Some("c"));
    let parent = p.parent().unwrap();
    assert_eq!(parent.as_str(), "/a/b");
    assert_eq!(parent.parent().unwrap().as_str(), "/a");
    assert_eq!(parent.parent().unwrap().parent(), Some(ZPath::ROOT));
    assert_eq!(ZPath::ROOT.parent(), None);
    assert_eq!(ZPath::ROOT.name(), None);
    assert_eq!(ZPath::new("/x").unwrap().name(), Some("x"));
}

#[test]
fn components_in_order() {
    let p = ZPath::new("/a/bb/c").unwrap();
    assert_eq!(p.components().collect::<Vec<_>>(), vec!["a", "bb", "c"]);
    assert_eq!(ZPath::ROOT.components().count(), 0);
    let mut it = ZPath::new("/x").unwrap().components();
    assert_eq!(it.next(), Some("x"));
    assert_eq!(it.next(), None);
    assert_eq!(it.next(), None);
}

#[test]
fn join_checks_the_result() {
    let root = ZPath::ROOT;
    assert_eq!(root.join("a").unwrap().as_str(), "/a");
    assert_eq!(root.join("a/b").unwrap().as_str(), "/a/b");
    let a = ZPath::new("/a").unwrap();
    assert_eq!(a.join("b").unwrap().as_str(), "/a/b");
    assert_eq!(
        a.join("").unwrap_err().reason,
        PathErrorReason::EmptyComponent
    );
    assert_eq!(
        root.join("").unwrap_err().reason,
        PathErrorReason::EmptyComponent
    );
    assert_eq!(
        a.join("/b").unwrap_err().reason,
        PathErrorReason::EmptyComponent
    );
    assert_eq!(
        a.join("b/").unwrap_err().reason,
        PathErrorReason::TrailingSlash
    );
    assert_eq!(
        a.join("..").unwrap_err().reason,
        PathErrorReason::RelativeComponent
    );
    assert_eq!(
        a.join("b\0").unwrap_err().reason,
        PathErrorReason::InvalidChar('\0')
    );
}

#[test]
fn starts_with_is_component_wise() {
    let p = |s| ZPath::new(s).unwrap();
    assert!(p("/app/x").starts_with(p("/app")));
    assert!(p("/app").starts_with(p("/app")));
    assert!(p("/app").starts_with(ZPath::ROOT));
    assert!(ZPath::ROOT.starts_with(ZPath::ROOT));
    assert!(!p("/app2").starts_with(p("/app")));
    assert!(!p("/app").starts_with(p("/app/x")));
    assert!(!ZPath::ROOT.starts_with(p("/a")));
}

#[test]
fn root_and_buf() {
    assert!(ZPath::ROOT.is_root());
    let b = ZPath::new("/x").unwrap().to_buf();
    assert_eq!(b.as_path().as_str(), "/x");
    assert_eq!(b.to_string(), "/x");
}
