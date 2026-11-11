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

//! Build-time discovery and configuration of default TLS trust store locations.
//!
//! Discovers and exports system CA certificate bundle paths and certificate directories
//! for consumption by `net::tls::trust` via compile-time environment variables
//! (`ZK_TLS_DEFAULT_CERT_FILE` and `ZK_TLS_DEFAULT_CERT_DIR`).
//!
//! When invoked via standard Cargo workflows without environment overrides this script probes
//! well-known platform directory hierarchies on POSIX build hosts to establish fallback
//! certificate trust anchors. Target environments lacking default CA hierarchies default to
//! empty search locations.

#![forbid(unsafe_code)]

use std::path::Path;

const FILE_VAR: &str = "ZK_TLS_DEFAULT_CERT_FILE";
const DIR_VAR: &str = "ZK_TLS_DEFAULT_CERT_DIR";

/// Well-known platform directory paths searched for system CA certificate bundles, in priority
/// order.
const PLATFORM_DIRS: [&str; 6] = [
    "/usr/lib/ssl",
    "/etc/pki/tls",
    "/etc/ssl",
    "/usr/local/ssl",
    "/usr/local/etc/openssl@3",
    "/opt/homebrew/etc/openssl@3",
];

#[allow(clippy::print_stdout, clippy::print_stderr)]
fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed={FILE_VAR}");
    println!("cargo:rerun-if-env-changed={DIR_VAR}");
    let file = std::env::var_os(FILE_VAR);
    let dir = std::env::var_os(DIR_VAR);
    if file.is_some() || dir.is_some() {
        for (name, value) in [(FILE_VAR, file), (DIR_VAR, dir)] {
            match value.as_ref().map(|v| v.to_str()) {
                Some(Some(v)) => println!("cargo:rustc-env={name}={v}"),
                Some(None) => {
                    println!("cargo:warning={name} is not valid UTF-8; the build ignores it")
                }
                None => {}
            }
        }
        return;
    }
    let windows = std::env::var("CARGO_CFG_TARGET_OS").is_ok_and(|os| os == "windows");
    let (file, dir) = if windows {
        (String::new(), String::new())
    } else {
        platform_defaults()
    };
    println!("cargo:rustc-env={FILE_VAR}={file}");
    println!("cargo:rustc-env={DIR_VAR}={dir}");
}

/// Probes host filesystem candidate directories for default CA certificate files and directory
/// stores.
fn platform_defaults() -> (String, String) {
    PLATFORM_DIRS
        .iter()
        .find(|d| Path::new(d).is_dir())
        .map_or_else(
            || (String::new(), String::new()),
            |d| (format!("{d}/cert.pem"), format!("{d}/certs")),
        )
}
