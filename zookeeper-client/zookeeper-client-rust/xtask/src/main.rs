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

//! Build and verification automation harness for the ZooKeeper Rust client workspace.
//!
//! This crate provides verification tooling and development tasks without external
//! dependencies. Static inspection tasks analyze C header files, Rust source trees,
//! compiled object files, and shared libraries directly as structured data rather than
//! executing checked code paths. Dynamic test orchestration tasks, such as Loom model
//! checking and Miri interpreter runs, manage environment flags and isolate execution
//! across target workspace crates.

#![forbid(unsafe_code)]
// Unit test harnesses are permitted to evaluate assertions and panic on failure.
#![cfg_attr(test, allow(clippy::disallowed_macros))]

mod abi;
mod clex;
mod headers;
mod layout;
mod logsites;
mod loom;
mod miri;
mod objfile;
mod util;

use std::process::ExitCode;

use util::Error;

/// Top-level command-line usage and task dispatch reference.
const USAGE: &str = "\
usage: cargo xtask <task> [options]

The file names are relative to zookeeper-client-rust/c. --c-dir names the
legacy C tree, zookeeper-client-c by default.

tasks:
  abi golden [--ref DIR] [--c-dir DIR] [--out-dir DIR]
      Write abi/symbols.tsv, abi/static-symbols.tsv and tests/abi/absent.tsv
      from the legacy reference build in DIR (default /tmp/zk-legacy-ref).
  abi check <shared library> --flavor mt|st (--config plain|ssl|sasl|full | --features ssl,sasl)
            [--reference] [--libdir DIR] [--max-minos X.Y] [--golden-dir DIR] [--verbose]
      Check a shared library against the goldens (E1-E16).
  abi static <archive> --flavor mt|st (--config ... | --features ...) [--reference] [--verbose]
      Check a static archive against the goldens (S1-S3).
  log-sites [--check] [--verbose] [--c-dir DIR] [--out FILE] [--table FILE] [--proto FILE]
      Extract tests/abi/log-sites.tsv from the legacy C sources, or compare
      it with the zk-proto log-site table.
  log-sites [--c-dir DIR] [--proto FILE] header [--check] [--out FILE] [--table FILE]
      Write shim/zks_log_sites.h (the core and the C sites) from
      tests/abi/log-sites.tsv and the zk-proto log-site table, or check
      that it is up to date.
  layout gen [--check] | layout expect --include DIR [--full-cflags FLAGS] [--cc CC] [--target T] [--check]
      Write tests/abi/layout/probe.c, or measure it into
      tests/abi/layout/<target>.expect.
  loom [-p zk-driver|zk-ffi] [--preemptions N] [--filter NAME]
      Run the loom models with --cfg loom in target/loom. The bound is N,
      else LOOM_MAX_PREEMPTIONS, else 3.
  miri [--only RUN,...] [--toolchain T] [--target T] [--miri-flags FLAGS] [--list] [-- HARNESS ARGS]
      Run the Miri test set: zk-jute, zk-proto, zk-driver, the zk-driver
      TLS unit tests, zk-client and the zk-ffi unit tests, each with its
      Miri flags. The TLS run first makes the TLS fixtures with
      zk-fixtures, outside Miri. Print the time and the test counts of
      each run. The toolchain needs the miri component (default nightly).
";

fn dispatch(args: &[String]) -> Result<(), Error> {
    let (task, rest) = args
        .split_first()
        .ok_or_else(|| Error::usage("no task given".to_owned()))?;
    match task.as_str() {
        "abi" => abi::run(rest),
        "log-sites" => logsites::run(rest),
        "layout" => layout::run(rest),
        "loom" => loom::run(rest),
        "miri" => miri::run(rest),
        "help" | "--help" | "-h" => {
            util::out(USAGE);
            Ok(())
        }
        other => Err(Error::usage(format!("unknown task {other}"))),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match dispatch(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(Error::Usage(m)) => {
            util::err(&format!("xtask: {m}\n\n{USAGE}"));
            ExitCode::from(2)
        }
        Err(Error::Failed(m)) => {
            util::err(&format!("xtask: {m}\n"));
            ExitCode::FAILURE
        }
    }
}
