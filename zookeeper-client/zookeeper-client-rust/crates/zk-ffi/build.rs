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

//! Build-time version check against the C client header.
//!
//! Because the drop-in C library reports its release version through `ZOO_VERSION` in
//! `zookeeper_version.h`, the Rust FFI crate must be built from the matching release. Compilation
//! fails when `CARGO_PKG_VERSION` differs from `ZOO_VERSION`, or when the header has no
//! `ZOO_VERSION` line.
//!
//! The header read is the file named by the `ZK_VERSION_H` environment variable, exported by the
//! three build systems (autotools `rust.am`, CMake `ZkRust.cmake`, and the Maven script
//! `maven/rust-module.sh`). An empty `ZK_VERSION_H` counts as unset. When `ZK_VERSION_H` is set
//! and the file cannot be read, the build fails. When `ZK_VERSION_H` is unset, the header is
//! `zookeeper-client-c/include/zookeeper_version.h` in the repository tree, and a standalone crate
//! build outside the full tree skips the check when that file is absent. The script generates no
//! build artifacts.
//!
//! `tests/build_script.rs` includes this file as a module to test [`check`].

#![forbid(unsafe_code)]

use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

const ENV: &str = "ZK_VERSION_H";

// Not a fan of this but am not sure there is a better way to do it.
const HEADER: &str = "../../../zookeeper-client-c/include/zookeeper_version.h";

/// Cargo directives printed by `main` and the result of the version check.
pub(crate) struct Check {
    pub(crate) directives: Vec<String>,
    pub(crate) result: Result<(), String>,
}

/// Verifies `want` against the `ZOO_VERSION` definition in the C client header.
///
/// The `env` argument provides the value of `ZK_VERSION_H` (an empty value counts as unset), and
/// `root` is the crate directory. Always returns the `cargo:rerun-if-changed=build.rs`,
/// `cargo:rerun-if-env-changed=ZK_VERSION_H`, and `cargo:rerun-if-changed=` directives for the
/// header path it reads.
pub(crate) fn check(env: Option<OsString>, root: &Path, want: &str) -> Check {
    let mut directives = vec![
        "cargo:rerun-if-changed=build.rs".to_owned(),
        format!("cargo:rerun-if-env-changed={ENV}"),
    ];
    let given = env.filter(|v| !v.is_empty()).map(PathBuf::from);
    let path = given.clone().unwrap_or_else(|| root.join(HEADER));
    directives.push(format!("cargo:rerun-if-changed={}", path.display()));
    let text = match std::fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) => {
            let result = match given {
                Some(_) => Err(format!("cannot read {ENV}={}: {e}", path.display())),
                // Without ZK_VERSION_H, a missing tree header indicates a standalone crate build,
                // which skips the check.
                None => Ok(()),
            };
            return Check { directives, result };
        }
    };
    let have = text
        .lines()
        .filter_map(|l| l.trim().strip_prefix("#define ZOO_VERSION"))
        .map(|v| v.trim().trim_matches('"').to_owned())
        .next();
    let result = match have {
        Some(v) if v == want => Ok(()),
        Some(v) => Err(format!(
            "zk-ffi {want} does not match ZOO_VERSION {v} in {}",
            path.display()
        )),
        None => Err(format!("no ZOO_VERSION line in {}", path.display())),
    };
    Check { directives, result }
}

#[allow(clippy::print_stdout, clippy::print_stderr)]
fn main() -> ExitCode {
    let root = std::env::var_os("CARGO_MANIFEST_DIR").map_or_else(PathBuf::new, PathBuf::from);
    let want = std::env::var("CARGO_PKG_VERSION").unwrap_or_default();
    let c = check(std::env::var_os(ENV), &root, &want);
    for d in &c.directives {
        println!("{d}");
    }
    match c.result {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            eprintln!("{msg}");
            ExitCode::FAILURE
        }
    }
}
