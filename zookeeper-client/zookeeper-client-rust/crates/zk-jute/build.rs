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

//! Verifies the presence of generated Jute protocol record sources prior to compilation.
//!
//! This build script does not invoke code generation directly. Jute protocol records
//! are generated from `zookeeper.jute` during the Maven build lifecycle (`generate-sources`),
//! matching the workflow of the canonical C client. If the generated Rust source module is
//! absent, the build aborts with diagnostic guidance matching `configure.ac`.

#![forbid(unsafe_code)]

use std::path::PathBuf;
use std::process::ExitCode;

const GENERATED: &str = "src/generated/zookeeper_jute.rs";

const MISSING: &str = "jute files are missing! Run 'mvn -pl zookeeper-jute generate-sources' from the top level directory.";

#[allow(clippy::print_stdout, clippy::print_stderr)]
fn main() -> ExitCode {
    println!("cargo:rerun-if-changed={GENERATED}");
    println!("cargo:rerun-if-changed=build.rs");
    let root = std::env::var_os("CARGO_MANIFEST_DIR").map_or_else(PathBuf::new, PathBuf::from);
    if root.join(GENERATED).is_file() {
        ExitCode::SUCCESS
    } else {
        eprintln!("{MISSING}");
        ExitCode::FAILURE
    }
}
