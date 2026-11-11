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

//! Differential fuzz target for native and legacy ZooKeeper path validation and chroot handling.
//!
//! Verifies that `ZPath::new` agrees with `validate_path_strict`, that parent/name decomposition
//! and component iteration reconstruct valid paths, and that legacy and native chroot prefixing
//! and stripping operations are exact inverses.

#![no_main]
#![forbid(unsafe_code)]

use apache_zookeeper::ZPath;
use libfuzzer_sys::fuzz_target;
use zk_fuzz::Cursor;
use zk_proto::legacy::chroot::{join_legacy, prepend_c, strip, strip_c, strip_event};
use zk_proto::legacy::path::is_valid_path;
use zk_proto::native::{join_component, strip_str, validate_path_strict};

fn check_zpath(s: &str) {
    let z = ZPath::new(s);
    assert_eq!(
        z.is_ok(),
        validate_path_strict(s, false).is_ok(),
        "ZPath::new and validate_path_strict disagreed"
    );
    let Ok(p) = z else {
        return;
    };
    assert_eq!(p.as_str(), s);
    let parts: Vec<&str> = p.components().collect();
    if p.is_root() {
        assert!(parts.is_empty());
        assert!(p.parent().is_none() && p.name().is_none());
        return;
    }
    assert_eq!(
        format!("/{}", parts.join("/")),
        s,
        "joined path components did not reconstruct the original path"
    );
    let parent = p.parent().expect("non-root path must have a parent");
    let name = p.name().expect("non-root path must have a terminal name");
    assert_eq!(parts.last().copied(), Some(name));
    assert!(p.starts_with(parent) && p.starts_with(ZPath::ROOT) && p.starts_with(p));
    let joined = parent.join(name).expect("joining parent and terminal name must succeed");
    assert_eq!(
        joined.as_str(),
        s,
        "parent.join(name) did not reconstruct the original path"
    );
    assert_eq!(
        validate_path_strict(s, true).is_ok(),
        ZPath::new(&format!("{s}1")).is_ok()
    );
}

fuzz_target!(|data: &[u8]| {
    let mut c = Cursor::new(data);
    let mode = i32::from(c.u8() % 8);
    let root = c.lp();
    let path = c.rest();

    if let Ok(s) = std::str::from_utf8(path) {
        check_zpath(s);
        if let Ok(r) = std::str::from_utf8(root) {
            let full = join_component(Some(r), s);
            let (back, mismatch) = strip_str(Some(r), &full);
            if r.len() > 1 && ZPath::new(r).is_ok() && ZPath::new(s).is_ok() {
                assert!(!mismatch, "joined path must reside within the chroot prefix");
                assert_eq!(back, s, "strip_str did not invert join_component");
            }
        }
    }

    let _ = is_valid_path(path, mode);
    let chroot = (!root.is_empty()).then_some(root);
    // Legacy C client behavior: a 1-byte root path ("/") resolves to the chroot path itself.
    let full = prepend_c(chroot, path);
    let back = strip_c(chroot, &full);
    if path.len() > 1 {
        assert_eq!(back.path.as_ref(), path, "strip_c did not invert prepend_c");
    }
    // Component-aware chroot stripping: a joined path must strip back cleanly on a boundary.
    if let Ok(joined) = join_legacy(chroot, path, mode) {
        assert!(
            is_valid_path(&joined, mode),
            "join_legacy produced a path rejected by is_valid_path"
        );
        let s = strip(chroot, &joined);
        if chroot.is_some_and(|r| r.len() > 1) {
            assert!(!s.mismatch, "joined path must reside within the chroot prefix");
            assert_eq!(s.path.as_ref(), path, "strip did not invert join_legacy");
        }
    }
    let _ = strip_event(chroot, path, mode & 1 == 1);
});
