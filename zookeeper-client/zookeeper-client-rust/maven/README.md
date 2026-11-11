<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
//-->

# Maven Integration for the Rust Client

The optional `zookeeper-client-rust` Maven module integrates the Cargo workspace
into the Apache ZooKeeper Maven reactor. Standard Maven builds exclude this
module by default and do not require a Rust toolchain.

Execution is delegated to `rust-module.sh` via `exec-maven-plugin`, which
propagates Maven properties through environment variables.

## Build Properties and Activation

The `full-build` profile builds the native clients. The `rust` profile
of `zookeeper-client` adds this module, so `-Prust` also builds the
optional Rust client. When the module is in the reactor, it builds the
Rust client. These properties configure the build.

| Property | Default | Description |
|---|---|---|
| `rust-client` | `no` | `yes` builds Rust client and C shim; `no` skips. |
| `rust-tls` | `no` | `yes` enables TLS support in the Rust core. |
| `rust-tls-provider` | `ring` | `rustls` provider: `ring`, `aws-lc-rs`, or `aws-lc-rs-fips`. |
| `rust-target-cpu` | `auto` | `-C target-cpu` of the `zk-ffi` staticlib: `auto` follows the C compiler (`$CC` with `$CFLAGS`), `no` adds no flag, else a CPU name. |
| `rust-cargo-vendor` | empty | Absolute path to a `cargo vendor` directory for offline builds. |
| `rust-system-crates` | empty | Absolute path to system crates (updates `Cargo.lock`). |
| `c-client-openssl` | `yes` | OpenSSL configuration forwarded to the C client TLS header probe. |
| `c-client-sasl` | `yes` | Cyrus SASL configuration forwarded to the C client SASL library probe. |

`rust-cargo-vendor` and `rust-system-crates` are mutually exclusive. All Cargo
build artifacts are placed under `target/cargo` within this module.

The Maven Enforcer plugin validates `rust-client` during the `validate` phase of
this module (rejecting values other than `yes` or `no`).

## Maven Lifecycle Mapping

| Phase | Execution ID | Action |
|---|---|---|
| `validate` | `rust-client-value` | Validates `rust-client` via `maven-enforcer-plugin`. |
| `process-sources` | `rust-prepare` | Checks MSRV/versions, probes TLS/SASL, runs `cargo fetch`. |
| `compile` | `rust-build` | Builds `zk-ffi` (`capi` profile) and workspace crates (`--release`). |
| `test` | `rust-test` | Runs unit and integration tests for workspace and FFI crates. |
| `integration-test` | `rust-curator-interop` | Runs the Curator interop suite. |
| `verify` | `rust-lint` | Runs `cargo fmt --check` and `cargo clippy`. |

`rust-prepare` verifies options, the minimum supported Rust version ($\ge 1.85$),
and `Cargo.toml` version alignment, then persists resolved feature flags,
the Rust target CPU and probe outcomes to `target/rust-build.env` (and any local crate source replacement
to `target/cargo-sources.toml`), ensuring consistent configuration across
subsequent lifecycle phases.

## Build Profiles

| Profile | Activation | Action |
|---|---|---|
| `cargo-test` | Default (skip via `-DskipCargoTest` or `-DskipTests`) | Runs Cargo test suites. |
| `rust-lint` | Default (skip via `-DskipRustLint`) | Runs `cargo fmt --check` and `cargo clippy`. |
| `rust-integration` | `-Prust-integration` | Executes `dev/run-curator-interop.sh`. |
| `rust-test-coverage` | `-Prust-test-coverage` | Generates `llvm-cov` reports in `target/`. |
| `rust-test-asan` | `-Prust-test-asan` | Runs test suites under nightly AddressSanitizer. |

## Live-Server and Interoperability Tests

Live-server integration tests are skipped by default during `cargo test`.
Passing `-Drust-test-port-base=PORT` enables them; the test harness launches
local ZooKeeper servers via `dev/test-server.sh` across ports `PORT..PORT+19`
(requiring `zookeeper-server` to be compiled beforehand).

The Apache Curator interoperability suite uses `-Drust-it-port-base` (default
`23400`), `-Drust-it-server-versions` (default `head`), and optional extra Maven
arguments via `-Drust-it-mvn-args`.

## Example Invocations

```sh
# The drop-in C client libraries, with TLS through ring.
mvn -Pfull-build -Prust -Drust-tls=yes install

# The link tests and the public-API suites of the drop-in libraries.
mvn -Prust -Drust-dropin-check -Drust-test-port-base=23700 \
  -DskipCargoTest -pl zookeeper-client/zookeeper-client-rust -am install

# The Rust module only. Build zookeeper-jute and zookeeper-server first.
mvn -Prust -pl zookeeper-client/zookeeper-client-rust verify

# The real-server tests.
mvn -Prust -Drust-test-port-base=23700 \
  -pl zookeeper-client/zookeeper-client-rust verify

# An offline build from a cargo vendor directory.
mvn -Prust -Drust-cargo-vendor=/path/to/vendor \
  -pl zookeeper-client/zookeeper-client-rust verify

# The Curator interop test.
mvn -Prust -Prust-integration -DskipCargoTest \
  -pl zookeeper-client/zookeeper-client-rust verify
```

## Version Synchronization

`rust-prepare` verifies that `[workspace.package].version` in `Cargo.toml`
matches the root Maven project version (stripped of `-SNAPSHOT`). During release
preparation, the root `replace-rustclient-files-during-release` execution
updates the C version headers, `Cargo.toml`, workspace `Cargo.lock` files, and
parent POM versions. To synchronize versions manually:

```sh
mvn -N antrun:run@replace-rustclient-files-during-release
```
