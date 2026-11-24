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

//! Tests for the version check in `build.rs`, which this test includes directly as a module.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
#![allow(clippy::disallowed_macros)]

use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};

#[allow(dead_code, reason = "main runs only as the build script")]
#[path = "../build.rs"]
mod build_script;

use build_script::check;

const WANT: &str = env!("CARGO_PKG_VERSION");

fn work_dir(name: &str) -> PathBuf {
    let d = PathBuf::from(env!("CARGO_TARGET_TMPDIR"))
        .join("build_script")
        .join(name);
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn header(dir: &Path, version: &str) -> PathBuf {
    let p = dir.join("zookeeper_version.h");
    fs::write(&p, format!("#define ZOO_VERSION \"{version}\"\n")).unwrap();
    p
}

fn env_of(p: &Path) -> Option<OsString> {
    Some(p.as_os_str().to_owned())
}

/// Returns a crate directory under which no repository tree header exists, so the fallback path is
/// absent.
fn no_tree() -> PathBuf {
    work_dir("no-tree").join("a/b/c")
}

#[test]
fn the_script_watches_the_variable_and_the_header_it_reads() {
    let d = work_dir("watch");
    let h = header(&d, WANT);
    let c = check(env_of(&h), &no_tree(), WANT);
    assert_eq!(c.result, Ok(()));
    assert!(
        c.directives
            .contains(&"cargo:rerun-if-env-changed=ZK_VERSION_H".to_owned()),
        "{:?}",
        c.directives
    );
    assert!(
        c.directives
            .contains(&format!("cargo:rerun-if-changed={}", h.display())),
        "{:?}",
        c.directives
    );
}

#[test]
fn a_set_variable_wins_over_the_tree_header() {
    let d = work_dir("wins");
    let h = header(&d, "0.0.1");
    let c = check(env_of(&h), Path::new(env!("CARGO_MANIFEST_DIR")), WANT);
    let err = c.result.unwrap_err();
    assert!(err.contains("does not match ZOO_VERSION 0.0.1"), "{err}");
}

#[test]
fn a_set_variable_that_names_no_file_fails() {
    let missing = work_dir("missing").join("zookeeper_version.h");
    let c = check(env_of(&missing), &no_tree(), WANT);
    let err = c.result.unwrap_err();
    assert!(err.contains("cannot read ZK_VERSION_H="), "{err}");
}

#[test]
fn without_the_variable_the_tree_header_is_read() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    for env in [None, Some(OsString::new())] {
        let c = check(env, root, "0.0.1");
        let err = c.result.unwrap_err();
        assert!(err.contains("does not match ZOO_VERSION"), "{err}");
    }
    assert_eq!(check(None, root, WANT).result, Ok(()));
}

#[test]
fn without_the_variable_a_missing_tree_header_is_skipped() {
    assert_eq!(check(None, &no_tree(), WANT).result, Ok(()));
}
