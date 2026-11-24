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

# Developer and Integration Test Scripts

This directory contains Bash utilities for building the macOS C drop-in
libraries and managing local ZooKeeper server and ensemble instances for live
integration testing. Each script header documents its full command-line and
environment interface; this document provides an architectural overview.

Because Cargo integration tests, C conformance runners, and Maven executions
invoke these scripts by path, do not rename or relocate them.

| Script | Purpose | Invoked By | Platforms |
|---|---|---|---|
| `build-dropin.sh` | Builds drop-in `libzookeeper_{mt,st}` | developers, `c/tests/link` | macOS |
| `test-server.sh` | Manages a standalone loopback server | Rust/C tests, runners | macOS, Linux |
| `test-ensemble.sh` | Manages a 3-node loopback quorum | Rust/interop suites | macOS, Linux |
| `get-release-server.sh` | Downloads a ZooKeeper release | recipe/interop runners | macOS, Linux |
| `run-recipes-real.sh` | Runs recipe live-server tests | developers, CI | macOS, Linux |
| `run-curator-interop.sh` | Runs Curator interop tests | developers, CI, Maven | macOS, Linux |

## Server Selection

By default, live-server scripts launch the ZooKeeper server compiled from the
current source tree (`HEAD`). Compile the server module first:

```sh
mvn -DskipTests install -pl zookeeper-server -am
```

To test against a released ZooKeeper binary distribution instead of `HEAD`, set
one of the following environment variables:

| Variable | Description |
|---|---|
| `ZK_TEST_SERVER_HOME` | Path to an extracted release (`apache-zookeeper-<ver>-bin`). |
| `ZK_TEST_SERVER_VERSION` | Release version under `ZK_TEST_SERVERS_DIR`. |
| `ZK_TEST_SERVERS_DIR` | Cache directory for downloaded releases (default: `/tmp/zk-servers`). |
| `ZK_TEST_SOURCE_ROOT` | Root of the ZooKeeper source tree providing the `HEAD` server build. |

All server scripts require a JDK (`java` on `PATH`) and `nc` (netcat).

## `build-dropin.sh`

Builds the Rust-backed C client libraries (`libzookeeper_mt` and
`libzookeeper_st`) directly on macOS without running Autotools `configure` or
CMake. For each flavor, the script prelinks `libzk_ffi.a`, the C shim objects,
and the generated Jute / RecordIO objects into a single relocatable object
(`ld -r`) with all non-ABI symbols localized, then produces the shared library
(`.2.dylib`), static archive (`.a`), and CLI binaries (`cli_mt` / `cli_st`). It
verifies the exported symbol set against `c/abi/symbols.tsv`, runs dynamic and
static smoke binaries, and (when `ABI_CHECK=1`) executes `cargo xtask abi check`
and `cargo xtask abi static`.

Example invocation:

```sh
CONFIG=plain FLAVORS="mt st" ABI_CHECK=1 dev/build-dropin.sh .. /tmp/zk-dropin
```

Both positional arguments (`ZK_CLIENT_ROOT` and `OUT_DIR`) are optional and
default to `..` and `../target-dropin`.

| Variable | Default | Description |
|---|---|---|
| `CONFIG` | `plain` | Feature configuration: `plain`, `ssl`, `sasl`, or `full`. |
| `FLAVORS` | `mt st` | Library flavors to build (`mt`, `st`, or `mt st`). |
| `TLS_PROVIDER` | `ring` | `rustls` provider for `ssl`/`full`: `ring` or `aws-lc-rs`. |
| `TLS_DEFAULT_PATHS` | `OPENSSLDIR` | `FILE:DIR` default CA bundle and directory for `zk-ffi`. |
| `OPENSSL_PREFIX` | Homebrew `openssl@3` | Provides OpenSSL headers and `OPENSSLDIR` (unlinked). |
| `SASL_PREFIX` | Homebrew `cyrus-sasl` | Cyrus SASL prefix for `sasl` and `full` builds. |
| `CARGO_TARGET_DIR` | Workspace `target` | Cargo build output directory. |
| `ARCH` | `uname -m` | Target Mach-O architecture (`arm64` or `x86_64`). |
| `CC` | `cc` | C compiler executable. |
| `INSTALL_DIR` | `OUT_DIR` | Directory prefix embedded in the `.2.dylib` Mach-O `install_name`. |
| `ABI_CHECK` | `0` | Set to `1` to run `cargo xtask abi check` and `cargo xtask abi static`. |
| `SMOKE_HOST` | unset | Optional live server `HOST:PORT` for `cli_FL-static` (`ls /`). |

## `test-server.sh`

Starts or stops a standalone ZooKeeper server on `127.0.0.1`:

```sh
dev/test-server.sh start [PORT] [DATADIR]
dev/test-server.sh stop [PORT] [DATADIR]
```

`PORT` defaults to `22181` and `DATADIR` defaults to
`${TMPDIR:-/tmp}/zk-rust-test-PORT`. `start` polls 4-letter word commands
(`ruok` and `srvr`) until the server is ready to accept client sessions; `stop`
verifies that the recorded PID belongs to `QuorumPeerMain` before sending
`SIGTERM` and awaits process termination.

Additional `zoo.cfg` directives may be supplied via `ZK_TEST_SERVER_PROPS` and
extra JVM flags via `ZK_TEST_SERVER_JVM_OPTS`.

## `test-ensemble.sh`

Manages a 3-node ZooKeeper quorum on `127.0.0.1`:

```sh
dev/test-ensemble.sh start BASE [DIR]
dev/test-ensemble.sh stop BASE [DIR]
dev/test-ensemble.sh stop-node BASE N [DIR]
dev/test-ensemble.sh start-node BASE N [DIR]
dev/test-ensemble.sh leader BASE
```

Peer `N` (`1..3`) binds client port `BASE+N-1`, quorum port `BASE+N+2`, and
leader-election port `BASE+N+5` (occupying ports `BASE..BASE+8`), with per-node
state under `DIR/nodeN` (default: `${TMPDIR:-/tmp}/zk-rust-ensemble-BASE`).

## `get-release-server.sh`

Downloads an official Apache ZooKeeper binary tarball from `archive.apache.org`,
verifies its SHA-512 checksum, and extracts it atomically into
`$ZK_TEST_SERVERS_DIR/VERSION/apache-zookeeper-VERSION-bin` (default:
`/tmp/zk-servers`):

```sh
dev/get-release-server.sh 3.9.6
```

Requires `curl`, `tar`, and `shasum` or `sha512sum`. No code from the downloaded
archive is executed during extraction.

## `run-recipes-real.sh`

Executes the `apache-zookeeper-recipes` live-server test suites (`real_infra`,
`lock_real`, `latch_real`, `tree_cache_real`) against each specified server
version (`head` and `3.9.6` by default, plus `3.5.10` for persistent-watch
fallback verification):

```sh
dev/run-recipes-real.sh [VERSION ...]
```

| Variable | Default | Description |
|---|---|---|
| `ZK_TEST_PORT_BASE` | `23400` | Base port for the 10-port range `BASE..BASE+9`. |
| `ZK_TEST_REQUIRE_SERVER` | `1` | Fails tests if a server cannot be started rather than skipping. |
| `ZK_RECIPES_REPEAT` | `1` | Number of full test passes per server version. |
| `ZK_RECIPES_CARGO_ARGS` | empty | Additional arguments forwarded to `cargo test`. |
| `ZK_TEST_SOURCE_ROOT` | repo root | Source tree root for the `head` server build. |

## `run-curator-interop.sh`

Builds the `interop_peer` example binary and runs the `interop/curator` JUnit
suite across standalone and 3-node ensemble topologies for each specified server
version (`head` and `3.9.6` by default):

```sh
dev/run-curator-interop.sh [VERSION ...]
```

| Variable | Default | Description |
|---|---|---|
| `ZK_TEST_PORT_BASE` | `23400` | Standalone server uses `BASE`; ensemble uses `BASE+1..BASE+9`. |
| `ZK_INTEROP_PROXY_PORT` | `0` (ephemeral) | Base listener port for `CutProxy`. |
| `ZK_INTEROP_TOPOLOGIES` | `standalone ensemble` | Space-separated topologies to run. |
| `ZK_INTEROP_MVN_ARGS` | empty | Additional arguments forwarded to `mvn test`. |
| `ZK_INTEROP_REPEAT` | `1` | Number of passes per version and topology. |
| `ZK_DEV_DIR` | `dev/` | Directory containing `test-server.sh` and `test-ensemble.sh`. |
| `CARGO_TARGET_DIR` | Workspace `target` | Cargo directory containing `examples/interop_peer`. |

`run-curator-interop.sh` and the `apache-zookeeper-recipes` live-server tests
coordinate exclusive access to a port range via the lock directory
`${TMPDIR:-/tmp}/zk-recipes-lease-BASE`.
