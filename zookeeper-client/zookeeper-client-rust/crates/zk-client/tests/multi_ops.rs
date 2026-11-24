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

//! Multi-operation transaction builder and result decoding tests: strongly typed result
//! extraction via `FromOpResult` and client side path validation upon operation insertion.

#![allow(clippy::unwrap_used)]
#![allow(clippy::disallowed_macros)]

use apache_zookeeper::acl::OPEN_ACL_UNSAFE;
use apache_zookeeper::{
    CreateMode, Created, Error, FromOpResult, MultiReader, MultiWriter, OpResult, Stat, Version,
    ZPathBuf,
};

#[test]
fn typed_access_matches_the_variant() {
    let created = OpResult::Create(Created {
        path: ZPathBuf::new("/a".to_owned()).unwrap(),
        stat: Stat::default(),
    });
    assert!(Created::from_op_result(&created).is_some());
    assert!(<()>::from_op_result(&created).is_none());
    assert!(<()>::from_op_result(&OpResult::Check).is_some());
    assert!(Stat::from_op_result(&OpResult::SetData(Stat::default())).is_some());
}

#[test]
fn writer_checks_paths_at_add() {
    let mut w = MultiWriter::new();
    assert!(matches!(
        w.create("a", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE),
        Err(Error::InvalidPath(_))
    ));
    // Trailing slashes are permitted solely for sequential creation modes.
    assert!(matches!(
        w.create("/a/", b"", CreateMode::Persistent, OPEN_ACL_UNSAFE),
        Err(Error::InvalidPath(_))
    ));
    assert!(matches!(
        w.delete("/a/../b", Version::ANY),
        Err(Error::InvalidPath(_))
    ));
    assert!(matches!(
        w.set_data("", b"", Version::ANY),
        Err(Error::InvalidPath(_))
    ));
    assert!(matches!(
        w.check("/a//b", Version::ANY),
        Err(Error::InvalidPath(_))
    ));
    assert!(w.is_empty());
    let mut r = MultiReader::new();
    assert!(matches!(r.get_data("x"), Err(Error::InvalidPath(_))));
    assert!(matches!(r.get_children("/x/"), Err(Error::InvalidPath(_))));
    assert!(r.is_empty());
}
