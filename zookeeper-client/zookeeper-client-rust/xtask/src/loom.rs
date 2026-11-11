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

//! `cargo xtask loom`: Permutation testing and concurrency model checking via Loom.
//!
//! Concurrency models are implemented as specialized unit tests within the `loom_models`
//! modules of `zk-driver` and `zk-ffi`. These models conditionally compile under `--cfg loom`,
//! which swaps production synchronization primitives for Loom's simulated scheduling facade.
//! This runner injects the appropriate compilation flags into `RUSTFLAGS`, configures
//! preemption bounds, and isolates build artifacts into a distinct target directory
//! to avoid invalidating standard development build caches.

use std::path::PathBuf;
use std::process::Command;

use crate::util::{self, Error};

/// Workspace member crates containing Loom concurrency verification test suites.
const CRATES: [&str; 2] = ["zk-driver", "zk-ffi"];

/// Default Loom preemption bound balancing state-space coverage against test runtime.
const DEFAULT_PREEMPTIONS: u32 = 3;

/// Parsed command-line configuration controlling a Loom verification execution run.
#[derive(Debug, PartialEq, Eq)]
struct Opts {
    crates: Vec<String>,
    preemptions: Option<u32>,
    filter: String,
}

fn parse(args: &[String]) -> Result<Opts, Error> {
    let mut crates = Vec::new();
    let mut preemptions = None;
    let mut filter = "loom_models::".to_owned();
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--crate" | "-p" => {
                let c = util::value(&mut it, a)?;
                if !CRATES.contains(&c) {
                    return Err(Error::usage(format!("{c} has no loom models")));
                }
                crates.push(c.to_owned());
            }
            "--preemptions" => {
                let v = util::value(&mut it, a)?;
                let n = v
                    .parse()
                    .map_err(|_| Error::usage(format!("--preemptions {v} is not a number")))?;
                preemptions = Some(n);
            }
            "--filter" => {
                let v = util::value(&mut it, a)?;
                filter = format!("loom_models::{v}");
            }
            other => return Err(Error::usage(format!("loom: unknown option {other}"))),
        }
    }
    if crates.is_empty() {
        crates = CRATES.iter().map(|c| (*c).to_owned()).collect();
    }
    Ok(Opts {
        crates,
        preemptions,
        filter,
    })
}

/// Computes the dedicated target directory used for Loom builds, isolating Loom artifacts.
fn target_dir() -> PathBuf {
    std::env::var_os("CARGO_TARGET_DIR")
        .map_or_else(|| util::workspace_dir().join("target"), PathBuf::from)
        .join("loom")
}

/// Executes Loom model verification according to the supplied command-line arguments.
pub(crate) fn run(args: &[String]) -> Result<(), Error> {
    let opts = parse(args)?;
    // Preemption bounds precedence: explicit CLI argument overrides environment variables,
    // which in turn override the built-in default bound.
    let bound = match opts.preemptions {
        Some(n) => n.to_string(),
        None => std::env::var("LOOM_MAX_PREEMPTIONS")
            .unwrap_or_else(|_| DEFAULT_PREEMPTIONS.to_string()),
    };
    let rustflags = match std::env::var("RUSTFLAGS") {
        Ok(f) if !f.trim().is_empty() => format!("{f} --cfg loom"),
        _ => "--cfg loom".to_owned(),
    };
    let cargo = std::env::var_os("CARGO").unwrap_or_else(|| "cargo".into());
    for c in &opts.crates {
        util::out(&format!(
            "xtask: loom models of {c}, LOOM_MAX_PREEMPTIONS={bound}\n"
        ));
        let status = Command::new(&cargo)
            .current_dir(util::workspace_dir())
            .env("RUSTFLAGS", &rustflags)
            .env("LOOM_MAX_PREEMPTIONS", &bound)
            .env("CARGO_TARGET_DIR", target_dir())
            .args(["test", "--release", "--locked", "-p", c, "--lib", "--"])
            .arg(&opts.filter)
            .status()
            .map_err(|e| Error::msg(format!("cargo: {e}")))?;
        if !status.success() {
            return Err(Error::msg(format!("the loom models of {c} failed")));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn the_defaults_run_every_model() {
        let want = Opts {
            crates: args(&["zk-driver", "zk-ffi"]),
            preemptions: None,
            filter: "loom_models::".to_owned(),
        };
        assert_eq!(parse(&[]).ok(), Some(want));
    }

    #[test]
    fn options_select_a_crate_a_bound_and_a_model() {
        let got = parse(&args(&[
            "-p",
            "zk-ffi",
            "--preemptions",
            "2",
            "--filter",
            "loom_abi",
        ]));
        let want = Opts {
            crates: args(&["zk-ffi"]),
            preemptions: Some(2),
            filter: "loom_models::loom_abi".to_owned(),
        };
        assert_eq!(got.ok(), Some(want));
    }

    #[test]
    fn bad_options_are_usage_errors() {
        for bad in [
            &["--crate", "zk-proto"][..],
            &["--preemptions", "x"][..],
            &["--preemptions"][..],
            &["--fast"][..],
        ] {
            assert!(matches!(parse(&args(bad)), Err(Error::Usage(_))), "{bad:?}");
        }
    }
}
