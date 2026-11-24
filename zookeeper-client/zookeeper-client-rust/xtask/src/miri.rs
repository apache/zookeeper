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

//! `cargo xtask miri`: Test orchestration and undefined behavior verification via Miri.
//!
//! Executes targeted test suites across workspace crates using the Miri undefined behavior
//! interpreter. Enforces strict provenance validation (`-Zmiri-strict-provenance`) across all
//! executions to detect illegal pointer-integer conversions and out-of-bounds provenance leaks.
//! Preserves hermetic host isolation by default, selectively disabling isolation only when test
//! suites require legitimate host access (e.g. wall-clock timers, loopback network sockets, or
//! TLS certificate fixtures). Aggregates execution wall time and libtest pass/fail counts.

use std::io::{BufRead as _, BufReader};
use std::process::{Command, Stdio};
use std::time::Instant;

use crate::util::{self, Error};

/// Mandatory Miri evaluation flags applied uniformly to every test execution.
const BASE_FLAGS: &[&str] = &["-Zmiri-strict-provenance"];

/// Flag disabling Miri host isolation, allowing controlled access to host system calls.
const NO_ISOLATION: &str = "-Zmiri-disable-isolation";

/// Environment variable bindings for pre-generated TLS test artifacts and their corresponding
/// `zk-fixtures` generator arguments.
const FIXTURE_VARS: [(&str, &str); 2] = [
    ("ZK_TEST_TLS_FIXTURES", "tls"),
    ("ZK_TEST_TLS_CORPUS", "corpus"),
];

/// Execution configuration and isolation policy for a Miri test slice.
struct Run {
    /// Identifier used to select this slice via `--only`.
    name: &'static str,
    /// Cargo package and target flags passed to `cargo miri test`.
    cargo: &'static [&'static str],
    /// Libtest substring filters placed before trailing harness arguments.
    filter: &'static [&'static str],
    /// Rationale for disabling host isolation, or `None` when the slice runs isolated.
    no_isolation: Option<&'static str>,
    /// `true` if the slice requires pre-generated TLS certificates and corpus fixtures
    /// produced on the host before invoking Miri.
    fixtures: bool,
}

/// Configured Miri test slices ordered topologically according to the crate dependency graph.
///
/// Test slice design rationale:
/// - `zk-driver`: Evaluates connection and state-machine logic under the `std-thread` backend.
///   The `tokio` runtime is excluded because Miri lacks system epoll/kqueue emulation on macOS.
/// - `zk-driver-tls`: Targets pure-Rust cryptographic routines (X.509 parsing, DER decoding,
///   PEM unmarshaling) using the `ring` provider facade while skipping native assembly paths.
/// - `zk-client`: Covers API types, value serialization, and sessionless integration tests.
///   Full live sessions require driver waker pipe descriptors (`fcntl(F_SETFD)`), which are
///   unsupported under Miri; end-to-end driver protocol mechanics are verified via mock sockets.
/// - `zk-ffi`: Confined strictly to pure unit tests, avoiding integration harnesses that invoke
///   external C compilers or inspect filesystem source trees.
const RUNS: &[Run] = &[
    Run {
        name: "zk-jute",
        cargo: &["-p", "zk-jute"],
        filter: &[],
        no_isolation: None,
        fixtures: false,
    },
    Run {
        name: "zk-proto",
        cargo: &["-p", "zk-proto"],
        filter: &[],
        no_isolation: None,
        fixtures: false,
    },
    Run {
        name: "zk-driver",
        cargo: &["-p", "zk-driver", "--features", "std-thread"],
        filter: &[],
        no_isolation: Some(
            "the tests use host loopback sockets, and the host seeds its random source from \
             the wall clock",
        ),
        fixtures: false,
    },
    Run {
        name: "zk-driver-tls",
        cargo: &[
            "-p",
            "zk-driver",
            "--features",
            "std-thread,tls-ring",
            "--lib",
        ],
        filter: &["net::tls::"],
        no_isolation: Some("the tests read the TLS fixtures and the corpus"),
        fixtures: true,
    },
    Run {
        name: "zk-client",
        cargo: &[
            "-p",
            "apache-zookeeper",
            "--no-default-features",
            "--features",
            "blocking,stream,tracing,log,serde,sasl",
            "--lib",
            "--test",
            "api_sasl",
            "--test",
            "api_values",
            "--test",
            "auto_traits",
            "--test",
            "error_codes",
            "--test",
            "multi_ops",
            "--test",
            "path",
            "--test",
            "serde_values",
            "--test",
            "smoke",
            "--test",
            "value_types",
            "--test",
            "values",
        ],
        filter: &[],
        no_isolation: Some("the client seeds its random source from the wall clock"),
        fixtures: false,
    },
    Run {
        name: "zk-ffi",
        cargo: &["-p", "zk-ffi", "--lib"],
        filter: &[],
        no_isolation: Some(
            "the log lines carry the wall clock time, and a log test reads the C site \
             header of the source tree",
        ),
        fixtures: false,
    },
];

/// Command-line configuration controlling Miri test orchestration.
struct Opts {
    /// List of target test slice names to execute (defaults to all configured slices if empty).
    only: Vec<String>,
    /// Target Rust toolchain equipped with the `miri` component (e.g. `nightly`).
    toolchain: String,
    /// Cross-compilation target triple, or `None` to target the default host architecture.
    target: Option<String>,
    /// Additional interpreter flags passed to Miri following slice-specific defaults.
    extra_flags: Vec<String>,
    /// Trailing arguments forwarded directly to the libtest runner after the `--` delimiter.
    harness: Vec<String>,
    /// Dry-run mode: prints synthesized Cargo command invocations without executing Miri.
    list: bool,
}

/// Aggregate pass, fail, and ignore counts harvested from libtest execution output.
#[derive(Clone, Copy, Default)]
struct Counts {
    passed: u64,
    failed: u64,
    ignored: u64,
}

impl Counts {
    fn add(&mut self, o: Self) {
        self.passed = self.passed.saturating_add(o.passed);
        self.failed = self.failed.saturating_add(o.failed);
        self.ignored = self.ignored.saturating_add(o.ignored);
    }
}

/// Execution summary recording timing, test outcomes, and success status for a single slice.
struct Outcome {
    name: &'static str,
    secs: u64,
    counts: Counts,
    ok: bool,
}

pub(crate) fn run(args: &[String]) -> Result<(), Error> {
    let opts = parse(args)?;
    let selected: Vec<&Run> = RUNS
        .iter()
        .filter(|r| opts.only.is_empty() || opts.only.iter().any(|o| o == r.name))
        .collect();
    if let Some(bad) = opts
        .only
        .iter()
        .find(|o| !RUNS.iter().any(|r| r.name == o.as_str()))
    {
        return Err(Error::usage(format!("miri: unknown run {bad}")));
    }
    let mut outcomes = Vec::new();
    let mut fixtures: Option<Vec<(String, String)>> = None;
    for r in selected {
        let flags = miri_flags(r, &opts, std::env::var("MIRIFLAGS").ok().as_deref());
        let cargo_args = cargo_args(r, &opts);
        if opts.list {
            let why = r
                .no_isolation
                .map_or_else(String::new, |w| format!("  # no isolation: {w}"));
            let vars: String = if r.fixtures {
                FIXTURE_VARS
                    .iter()
                    .map(|(var, arg)| format!("{var}=$(zk-fixtures {arg}) "))
                    .collect()
            } else {
                String::new()
            };
            util::out(&format!(
                "{}: {vars}MIRIFLAGS=\"{flags}\" cargo {}{why}\n",
                r.name,
                cargo_args.join(" ")
            ));
            continue;
        }
        let env = if r.fixtures {
            if fixtures.is_none() {
                fixtures = Some(make_fixtures()?);
            }
            fixtures.clone().unwrap_or_default()
        } else {
            Vec::new()
        };
        util::out(&format!("== miri {}: MIRIFLAGS=\"{flags}\"\n", r.name));
        outcomes.push(execute(r.name, &flags, &env, &cargo_args)?);
    }
    if opts.list {
        return Ok(());
    }
    report(&outcomes)
}

fn parse(args: &[String]) -> Result<Opts, Error> {
    let mut o = Opts {
        only: Vec::new(),
        toolchain: "nightly".to_owned(),
        target: None,
        extra_flags: Vec::new(),
        harness: Vec::new(),
        list: false,
    };
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "--only" => o.only.extend(
                util::value(&mut it, a)?
                    .split(',')
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned),
            ),
            "--toolchain" => util::value(&mut it, a)?.clone_into(&mut o.toolchain),
            "--target" => o.target = Some(util::value(&mut it, a)?.to_owned()),
            "--miri-flags" => o.extra_flags.extend(
                util::value(&mut it, a)?
                    .split_whitespace()
                    .map(str::to_owned),
            ),
            "--list" => o.list = true,
            "--" => {
                o.harness.extend(it.by_ref().cloned());
            }
            _ => return Err(Error::usage(format!("miri: unknown argument {a}"))),
        }
    }
    Ok(o)
}

/// Assembles the `MIRIFLAGS` string for a slice, placing ambient `MIRIFLAGS` last so caller
/// overrides take precedence.
fn miri_flags(r: &Run, o: &Opts, env: Option<&str>) -> String {
    let mut flags: Vec<String> = BASE_FLAGS.iter().map(|&s| s.to_owned()).collect();
    if r.no_isolation.is_some() {
        flags.push(NO_ISOLATION.to_owned());
    }
    flags.extend(o.extra_flags.iter().cloned());
    if let Some(env) = env {
        flags.extend(env.split_whitespace().map(str::to_owned));
    }
    flags.join(" ")
}

/// Constructs the argument vector for `cargo +<toolchain> miri test`.
fn cargo_args(r: &Run, o: &Opts) -> Vec<String> {
    let mut v = vec![
        format!("+{}", o.toolchain),
        "miri".to_owned(),
        "test".to_owned(),
        "--no-fail-fast".to_owned(),
    ];
    v.extend(r.cargo.iter().map(|&s| s.to_owned()));
    if let Some(t) = &o.target {
        v.push("--target".to_owned());
        v.push(t.clone());
    }
    // Miri schedules simulated threads on a single OS thread; serialize test cases with
    // `--test-threads=1` to avoid cooperative scheduling deadlocks.
    v.push("--".to_owned());
    v.push("--test-threads=1".to_owned());
    v.extend(r.filter.iter().map(|&s| s.to_owned()));
    v.extend(o.harness.iter().cloned());
    v
}

/// Resolves TLS fixture directories from the environment or runs `zk-fixtures` on the host
/// before entering Miri.
fn make_fixtures() -> Result<Vec<(String, String)>, Error> {
    let mut vars = Vec::new();
    let mut missing = Vec::new();
    for (var, arg) in FIXTURE_VARS {
        match std::env::var(var) {
            Ok(dir) if !dir.is_empty() => vars.push((var.to_owned(), dir)),
            _ => missing.push((var, arg)),
        }
    }
    if missing.is_empty() {
        return Ok(vars);
    }
    util::out("== miri: making the TLS fixtures with zk-fixtures\n");
    let cargo = std::env::var_os("CARGO").unwrap_or_else(|| "cargo".into());
    let out = Command::new(cargo)
        .args([
            "run",
            "-q",
            "--locked",
            "-p",
            "zk-testkit",
            "--bin",
            "zk-fixtures",
            "--",
        ])
        .args(missing.iter().map(|(_, arg)| *arg))
        .current_dir(util::workspace_dir())
        .stderr(Stdio::inherit())
        .output()
        .map_err(|e| Error::msg(format!("miri: cannot start zk-fixtures: {e}")))?;
    if !out.status.success() {
        return Err(Error::msg(format!(
            "miri: zk-fixtures failed ({})",
            out.status
        )));
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let dirs: Vec<&str> = text.lines().collect();
    if dirs.len() != missing.len() {
        return Err(Error::msg(format!(
            "miri: zk-fixtures printed {} directories for {} sets",
            dirs.len(),
            missing.len()
        )));
    }
    for ((var, _), dir) in missing.iter().zip(dirs) {
        vars.push(((*var).to_owned(), dir.to_owned()));
    }
    Ok(vars)
}

/// Runs a single Miri test slice, streaming output and aggregating libtest result counts.
fn execute(
    name: &'static str,
    flags: &str,
    env: &[(String, String)],
    args: &[String],
) -> Result<Outcome, Error> {
    let start = Instant::now();
    let mut child = Command::new("cargo")
        .args(args)
        .current_dir(util::workspace_dir())
        .env("MIRIFLAGS", flags)
        .envs(env.iter().map(|(k, v)| (k, v)))
        // Clear toolchain overrides inherited from the outer `cargo xtask` process so
        // `cargo +<toolchain> miri` invokes the requested nightly compiler.
        .env_remove("RUSTC")
        .env_remove("RUSTUP_TOOLCHAIN")
        .stdout(Stdio::piped())
        .spawn()
        .map_err(|e| Error::msg(format!("miri {name}: cannot start cargo: {e}")))?;
    let mut counts = Counts::default();
    if let Some(stdout) = child.stdout.take() {
        for line in BufReader::new(stdout).lines() {
            let Ok(line) = line else { break };
            util::out(&format!("{line}\n"));
            if let Some(c) = parse_result(&line) {
                counts.add(c);
            }
        }
    }
    let status = child
        .wait()
        .map_err(|e| Error::msg(format!("miri {name}: cannot wait for cargo: {e}")))?;
    Ok(Outcome {
        name,
        secs: start.elapsed().as_secs(),
        counts,
        ok: status.success(),
    })
}

/// Parses libtest summary lines (e.g. `test result: ok. 12 passed; 0 failed; 3 ignored`).
fn parse_result(line: &str) -> Option<Counts> {
    let rest = line.trim().strip_prefix("test result: ")?;
    let mut c = Counts::default();
    for part in rest.split(';') {
        let words: Vec<&str> = part.split_whitespace().collect();
        for pair in words.windows(2) {
            if let [n, kind] = pair {
                let Ok(n) = n.parse::<u64>() else { continue };
                match *kind {
                    "passed" => c.passed = n,
                    "failed" => c.failed = n,
                    "ignored" => c.ignored = n,
                    _ => {}
                }
            }
        }
    }
    Some(c)
}

/// Prints a tabular summary of all executed Miri slices and fails if any slice failed.
fn report(outcomes: &[Outcome]) -> Result<(), Error> {
    let mut s = format!(
        "\n{:<12} {:>7} {:>7} {:>7} {:>8}  status\n",
        "run", "seconds", "passed", "failed", "ignored"
    );
    let mut total = Counts::default();
    let mut secs: u64 = 0;
    for o in outcomes {
        total.add(o.counts);
        secs = secs.saturating_add(o.secs);
        s.push_str(&format!(
            "{:<12} {:>7} {:>7} {:>7} {:>8}  {}\n",
            o.name,
            o.secs,
            o.counts.passed,
            o.counts.failed,
            o.counts.ignored,
            if o.ok { "ok" } else { "FAILED" }
        ));
    }
    s.push_str(&format!(
        "{:<12} {:>7} {:>7} {:>7} {:>8}\n",
        "total", secs, total.passed, total.failed, total.ignored
    ));
    util::out(&s);
    let failed: Vec<&str> = outcomes.iter().filter(|o| !o.ok).map(|o| o.name).collect();
    if failed.is_empty() {
        Ok(())
    } else {
        Err(Error::msg(format!(
            "miri: failed runs: {}",
            failed.join(", ")
        )))
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used, clippy::panic)]

    use super::*;

    #[test]
    fn reads_the_libtest_summary() {
        let c = parse_result(
            "test result: ok. 12 passed; 1 failed; 3 ignored; 0 measured; 4 filtered out; finished in 9.10s",
        );
        let c = c.unwrap();
        assert_eq!((c.passed, c.failed, c.ignored), (12, 1, 3));
        assert!(parse_result("test foo ... ok").is_none());
    }

    #[test]
    fn the_environment_flags_come_last() {
        let o = parse(&[
            "--miri-flags".to_owned(),
            "-Zmiri-many-seeds=0..4".to_owned(),
        ])
        .unwrap();
        let r = RUNS.first().unwrap();
        let flags = miri_flags(r, &o, Some("-Zmiri-tree-borrows"));
        assert_eq!(
            flags,
            "-Zmiri-strict-provenance -Zmiri-many-seeds=0..4 -Zmiri-tree-borrows"
        );
    }

    #[test]
    fn every_run_has_strict_provenance() {
        let o = parse(&[]).unwrap();
        for r in RUNS {
            let flags = miri_flags(r, &o, None);
            assert!(flags.contains("-Zmiri-strict-provenance"), "{}", r.name);
            assert_eq!(flags.contains(NO_ISOLATION), r.no_isolation.is_some());
        }
    }
}
