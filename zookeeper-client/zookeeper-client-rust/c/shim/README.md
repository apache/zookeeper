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

# C Compatibility Shim for the Rust Client Core

This directory implements the C compatibility shim providing drop-in ABI and API replacement libraries for the legacy ZooKeeper C client (`libzookeeper_mt` and `libzookeeper_st`). The underlying protocol engine and runtime state machine are provided by the `zk-ffi` crate (`zookeeper-client-rust/crates/zk-ffi`).

For each library flavor, the build consolidates the `zk-ffi` static archive, flavor-specific shim translation units, and unmodified legacy Jute serialization objects (`src/recordio.c` and `generated/zookeeper.jute.c`) into a single relocatable object (`zookeeper_FL_all.o`). Symbol scoping rules localize internal symbols within this object via `abi/prelink.sh`, ensuring that only the canonical golden symbol set defined in `abi/symbols.tsv` is exported by the resulting dynamic libraries (`abi/gen-exports.awk`) and static archives.

## Architectural Scope and Symbol Baselines

Drop-in replacement libraries are provided for both multithreaded (`libzookeeper_mt`) and single threaded (`libzookeeper_st`) flavors across four discrete security configurations:

| Configuration | TLS Support | SASL Support | `mt` Export Count | `st` Export Count |
|---|---|---|---|---|
| `plain` | No | No | 201 | 178 |
| `ssl` | Yes | No | 202 | 179 |
| `sasl` | No | Yes | 210 | 187 |
| `full` | Yes | Yes | 211 | 188 |

A single `zk-ffi` static library artifact serves both flavors because runtime threading model behavior is parametrized dynamically. The project directory selects the client core. The projects in `zookeeper-client-c` build the legacy C core. The drop-in project in `c/` builds the Rust core.

### Execution Models and Thread Cancellation

- **Multithreaded (`mt`) Flavor**: Implements autonomous background I/O processing, heartbeat generation, and callback dispatch threads. Thread cancellation masks (`ZKS_HOLD_SCOPE`) ensure foreign cancellation requests do not interrupt internal Rust runtime frames or application callback boundaries. The shim clears its cached mask state around each application callback (`ZKS_APP_CALL`), so a `longjmp` out of a callback cannot make later calls skip the mask.
- **single threaded (`st`) Flavor**: Omits internal background threads, synchronous client entry points (`zks_sync.c`), and thread lifecycle wrappers (`zks_threads.c`). Execution is driven explicitly by the host application via `zookeeper_interest` and `zookeeper_process`. Because no Rust stack frames remain active during application callback invocations, cancellation semantics match legacy single threaded behavior.

## Source Tree Organization

Path specifications within this documentation follow these structural conventions:
- `c/` refers to `zookeeper-client/zookeeper-client-rust/c`. It holds the C shim sources, the ABI check data and the drop-in project. The drop-in project has an Autotools part (`configure.ac`, `Makefile.am`, `rust.am` and `m4/zk_rust.m4`) and a CMake part (`CMakeLists.txt` and `cmake/`).
- `zookeeper-client-c` holds the legacy C client: `include/`, `src/`, `generated/`, the CppUnit suites (`tests/*.cc`) and the legacy build files. The drop-in project reads the headers, the sources, the Jute files and `zookeeper_version.h` from this tree through `../../zookeeper-client-c`. It writes no file there.
- `tests/` in the Rust C tree contains development test runners, ABI verifiers, and conformance fixtures.

## Translation Units and Subsystem Architecture

| Translation Unit | Functional Role |
|---|---|
| `zks_internal.h` | Internal shim definitions, handle structures, outbox prototypes, and platform macros. Included first across all shim translation units. |
| `zkrs.h` | Rust FFI interface definitions generated from the `zk-ffi` crate via `cbindgen`. Do not edit manually. |
| `zks_log_sites.h` | Synchronized log site identifiers shared between C and Rust runtime components, generated via `cargo xtask log-sites header`. Do not edit manually. |
| `zks_*.c` | Core shim implementation units. Multithreading synchronization (`zks_sync.c`) and thread primitives (`zks_threads.c`) are compiled only in `mt` builds. |
| `zks_hashtable_stub.c` | Minimal compilation stub for the legacy `hashtable` static archive, containing no exported global symbols. |
| `zks_tls.c` | TLS initialization (`zookeeper_init_ssl`) and certificate configuration parser (active in TLS-enabled builds). |
| `zks_sasl.h` | Internal SASL interface definitions utilized by the shim. |
| `zks_sasl_cb.h` | Internal header for Cyrus SASL callback adapters. |
| `zks_sasl_cyrus.c` | Cyrus SASL session lifecycle, SASL entry points, and authentication negotiation state machine integration. |
| `zks_sasl_cb.c` | Legacy SASL callback dispatch adapters and `zoo_sasl_make_basic_callbacks`. |

Security feature preprocessor definitions (`ZKS_WITH_TLS` / `HAVE_OPENSSL_H` and `ZKS_WITH_SASL` / `HAVE_CYRUS_SASL_H`) govern conditional compilation. To avoid unintended symbol exposure or header contamination, `zks_internal.h` isolates Cyrus SASL and OpenSSL header visibility exclusively to dedicated integration units. Linkage against `-lsasl2` occurs only when SASL support is activated; neither configuration links against OpenSSL runtime libraries.

## Build Prerequisites

- **Rust Toolchain**: `rustc` and `cargo` 1.85 or later (precompiled staticlib workflows require only `rustc`).
- **Crate Dependencies**: Online access for dependency retrieval or a populated local cache (`CARGO_HOME`, vendored directories, or system crate packages); verification utilizes `cargo fetch --locked`.
- **C Build Systems**: GNU Autotools (`autoconf`, `automake`, `libtool`) or CMake 3.15 or later (CMake 3.17+ required for Mach-O dynamic library generation).
- **Jute Code Generator**: Pre-generated `generated/zookeeper.jute.{c,h}` sources from the ZooKeeper Jute compiler.

## Autotools Integration

The Autotools part of the drop-in project is in `c/`. It builds only the Rust core. `c/configure.ac` contains the probes of the legacy `configure.ac`, in the same order. Thus the drop-in libraries get the same compiler and linker flags as the legacy libraries. `configure` takes its version from `zookeeper_version.h` of the legacy tree.

The configure step stops with an error when the host cannot build the Rust core. The message gives the reason, for example `cannot build the Rust client: cargo and rustc 1.85 or later not found; zookeeper-client-c builds the legacy C client`. The project has no fallback to the legacy core.

### Configuration Options

- `--with-rust-workspace=DIR`: Path to the Cargo workspace (defaults to `$srcdir/..`, the `zookeeper-client-rust` directory that contains the project).
- `--with-rust-staticlib=PATH`: Link against a precompiled `libzk_ffi.a` archive, bypassing Cargo build invocation.
- `--with-rust-target=TRIPLE`: Explicit target triple passed to Cargo via `--target` (required for cross-compilation).
- `--with-rust-target-cpu=auto|no|CPU`: The `-C target-cpu` of the staticlib (defaults to `auto`). `auto` gives `x86-64-v2` when the Rust target is x86_64 and the C compiler with `CFLAGS` makes x86-64-v2 code. `no` adds no flag. A CPU name must be in `rustc --print target-cpus` for the target. A target CPU in the rustflags of the user stays as it is.
- `--with-rust-tls=yes|no`: Enable TLS support independently of OpenSSL header detection (defaults to `no`).
- `--with-rust-tls-provider=ring|aws-lc-rs|aws-lc-rs-fips`: Selects the Rustls cryptographic provider (defaults to `ring`). Note that `aws-lc-rs-fips` requires Linux on x86_64 or aarch64, as well as `cmake` and `go` on the build host.
- `--with-rust-tls-default-paths=FILE:DIR`: Colon-separated absolute paths specifying default CA certificate file and directory locations.
- `--with-cargo-vendor=DIR`: Enables offline compilation utilizing vendored crate dependencies.
- `--with-system-rust-crates[=DIR]`: Enables offline compilation utilizing packaged system crates (defaults to `/usr/share/cargo/registry`).
- `--disable-werror`: Disables `-Werror` compiler diagnostics during shim compilation.
- `--enable-debug`: Selects the `capi-dev` Cargo profile and activates the handle registry tracking mechanism (`ZKS_HANDLE_REGISTRY`).

The legacy options (`--with-openssl`, `--with-sasl`, `--with-syncapi`, `--without-cppunit`, `--enable-gcov`, `--enable-asan` and the others) keep their legacy meaning.

### Autotools Build Invocations

Out-of-tree minimal build:

```sh
cd zookeeper-client/zookeeper-client-rust/c
autoreconf -if        # macOS with Homebrew requires: LIBTOOLIZE=glibtoolize
mkdir -p /tmp/zk-build && cd /tmp/zk-build
/path/to/zookeeper-client-rust/c/configure --prefix=/tmp/zk-install \
    --without-openssl --without-sasl --without-cppunit
make -j8
make install
```

`autoreconf` writes its outputs (`configure`, `Makefile.in`, `build-aux/` and others) into `c/`. Git ignores them. Use an out-of-tree build directory for `configure` and `make`.

Full configuration (TLS and SASL enabled) with Homebrew prefix paths:

```sh
/path/to/zookeeper-client-rust/c/configure --without-cppunit \
    --with-openssl=/opt/homebrew/opt/openssl@3 --with-sasl=/opt/homebrew/opt/cyrus-sasl
make -j8 && make abi-check abi-static
```

Autoconf macro resolution requires `CHECK_CPPUNIT` (`cppunit.m4`), which can be supplied via `ACLOCAL_PATH` if omitted by package managers.

Incremental build handling: Cargo execution is integrated into top level make targets. When Rust source inputs remain unmodified, Cargo exits without touching the static library, preventing redundant pre-link and downstream link operations. Intermediate Cargo artifacts reside in `<builddir>/cargo`.

### Dedicated Autotools Make Targets

- `make abi-check`: Validates exported dynamic symbol tables against golden ABI specifications via `cargo xtask abi check`.
- `make abi-static`: Verifies internal symbol localization within static archives via `cargo xtask abi static`.
- `make check`: Executes the Cargo workspace test suite (`cargo test`).
- `make check-abi`: Executes dynamic library symbol interposition and ABI verification test harnesses (`tests/abi`). Requires `libsasl2`.
- `make check-link`: Validates static archive linkability and consumer symbol encapsulation (`tests/link`). Requires prior execution of `make install`.

## CMake Integration

The CMake part of the drop-in project is `c/CMakeLists.txt` with the modules in `c/cmake/`. It builds only the Rust core. `cmake/ZkCProbes.cmake` contains the options and the probes of the legacy `CMakeLists.txt`, in the same order. `CMakeLists.txt` declares `cmake_minimum_required(VERSION 3.5)`, as the legacy project does, but the Rust core needs CMake 3.15 or later. When the host cannot build the Rust core, the configure step stops with the same message as the Autotools part.

A parent project can include the drop-in project with `add_subdirectory()`. The drop-in project finds the legacy tree relative to its own directory.

Flavor selection is governed by `WANT_SYNCAPI`: `ON` (default) selects the multithreaded (`mt`) flavor, while `OFF` selects the single threaded (`st`) flavor.

```sh
cmake -S zookeeper-client/zookeeper-client-rust/c -B /tmp/zk-cmake \
    -DWITH_OPENSSL=OFF -DWITH_CYRUS_SASL=OFF \
    -DWANT_CPPUNIT=OFF -DZOOKEEPER_BUILD_SHARED=ON -DCMAKE_INSTALL_PREFIX=/tmp/zk-cmake-install
cmake --build /tmp/zk-cmake -j8
cmake --install /tmp/zk-cmake
cmake --build /tmp/zk-cmake --target abi-check abi-static
```

Full configuration with Homebrew dependency paths:

```sh
cmake -S zookeeper-client/zookeeper-client-rust/c -B /tmp/zk-cmake-full \
    -DWITH_OPENSSL=/opt/homebrew/opt/openssl@3 \
    -DWITH_CYRUS_SASL=/opt/homebrew/opt/cyrus-sasl \
    -DWANT_CPPUNIT=OFF -DZOOKEEPER_BUILD_SHARED=ON
```

### Configuration Variables

- `ZKRS_WORKSPACE`: Path to the Rust workspace root (defaults to the parent of `c/`).
- `ZKRS_STATICLIB`: Path to a precompiled `libzk_ffi.a` archive, bypassing Cargo execution.
- `Rust_TARGET`: Target architecture triple supplied to Cargo.
- `ZK_RUST_TARGET_CPU`: The `-C target-cpu` of the staticlib: `AUTO` [default], `OFF` or a CPU name. The values have the meaning of `--with-rust-target-cpu`.
- `ZOOKEEPER_BUILD_SHARED`: Enables shared library generation (`libzookeeper_mt.2.dylib` / `libzookeeper_st.2.dylib` on macOS, `.so.2` on Linux) with golden export filtering.
- `WITH_RUST_TLS`: Compiles TLS support independently of OpenSSL detection (defaults to `OFF`).
- `ZK_RUST_TLS_PROVIDER`: Selects the Rustls cryptographic provider (`ring` [default], `aws-lc-rs`, or `aws-lc-rs-fips`).
- `ZK_RUST_TLS_DEFAULT_PATHS`: Colon-separated default CA trust file and directory paths.
- `ZOOKEEPER_CARGO_VENDOR`: Path to vendored crate directory for offline builds.
- `ZK_SYSTEM_RUST_CRATES`: Path to system crate package repository.
- `ZK_WERROR`: Promotes compiler diagnostics to errors during shim compilation (defaults to `ON`).
- `CMAKE_BUILD_TYPE=Debug`: Activates the `capi-dev` compilation profile and handle tracking registry.

### Dedicated CMake Targets

- `abi-check`: Runs dynamic symbol verification against the shared library artifact. Requires `ZOOKEEPER_BUILD_SHARED=ON`.
- `abi-static`: Runs static archive symbol localization checks against `libzookeeper.a`.

## Maven Integration

The Maven module `zookeeper-client-rust` builds the crates and then the Autotools part of the drop-in project. It installs the drop-in libraries in `zookeeper-client-rust/target/c`. The legacy build of `-Pfull-build` installs in `zookeeper-client-c/target/c`, so the two installs stay apart.

| Build | Command |
|---|---|
| Legacy C client | `mvn -Pfull-build install` |
| Legacy C client and Rust client | `mvn -Pfull-build -Prust install` |
| Without the drop-in libraries | add `-Drust-dropin=no` |

`-Drust-dropin-check` adds the link tests and the public-API suites to the `test` phase. `maven/README.md` describes the properties, the executions and the profiles.

## Offline and Hermetic Build Environments

By default, capability checks run `cargo fetch --locked`, requiring network access or an existing Cargo cache. For isolated or hermetic build pipelines, dependency resolution can be anchored to local crate repositories:

```sh
cd zookeeper-client/zookeeper-client-rust
cargo vendor --locked -s crates/zk-ffi-layout/Cargo.toml /path/to/vendor
```

Pass the target vendor directory to the build system:

```sh
/path/to/zookeeper-client-rust/c/configure \
    --with-cargo-vendor=/path/to/vendor
make -j8 && make abi-check

cmake -S zookeeper-client/zookeeper-client-rust/c -B /tmp/zk-cmake \
    -DZOOKEEPER_CARGO_VENDOR=/path/to/vendor
```

Package managed distributions utilizing preinstalled system crates:

```sh
/path/to/zookeeper-client-rust/c/configure \
    --with-system-rust-crates
```

### Offline Configuration Invariants

- During configuration, a local `cargo-sources.toml` overlay is generated in the build directory, redirecting `crates-io` to a local `directory` or `local-registry` source.
- All subsequent Cargo invocations pass `--offline` and `--config <builddir>/cargo-sources.toml`.
- Consumer link tests (`tests/link/zklinkring`) execute from an isolated working copy to prevent mutation of tracked lockfiles.
- When resolving against system crate repositories where installed package versions deviate from the upstream lockfile, configuration triggers `cargo generate-lockfile --offline` to reconcile dependencies. This mode is intended for package builds rather than working git trees.
- Crate source options are strictly mutually exclusive. Specified paths must exist, be absolute, and contain no whitespace or shell quoting characters.

## Security Subsystems: TLS and SASL Integration

The drop in libraries synchronize TLS and SASL feature decisions with upstream build probes:

### Transport Layer Security (TLS) Architecture

- TLS enablement is triggered when OpenSSL headers (`openssl/ssl.h`) and libraries (`SSL_CTX_new`) are detected, or when explicitly requested via `--with-rust-tls=yes` (`-DWITH_RUST_TLS=ON`). Probing determines the exposure of `zookeeper_init_ssl` and activates the corresponding Cargo feature (`tls-<provider>`).
- Direct linkage against OpenSSL libraries (`-lssl`, `-lcrypto`) is eliminated entirely. Network encryption is handled internally by Rustls using the selected provider (`ring` or `aws-lc-rs`).
- Dynamic trust validation implements legacy ZooKeeper verification semantics (certificate chain validation without mandatory hostname identity constraints).
- System CA trust stores are resolved in order of precedence: user-specified paths (`--with-rust-tls-default-paths`), probed OpenSSL runtime configuration (`openssl version -d` / `OPENSSLDIR`), or standardized platform directory fallbacks.
- When compiling against prebuilt static archives (`ZKRS_STATICLIB`), feature consistency is verified by querying the `zkrs_feature_tls` symbol across binary object tables.

### Cyrus SASL Authentication Architecture

- SASL authentication delegates mechanism negotiations to Cyrus SASL (`libsasl2`), while the wire protocol framing and authentication state machine execute within the Rust core.
- Sensitive memory management: C translation units clear credentials from memory via `zks_secure_zero` upon function return; the Rust core isolates tokens in zeroize-protected `SecretBytes` structures.
- Error handling guarantees: On authentication failure, the client transitions immediately to `ZOO_AUTH_FAILED_STATE`, dispatches an `AUTH_FAILED` session event, and terminates all pending operations with `ZAUTHFAILED`. This prevents indefinite client hangs that could occur in legacy implementations.
- Completion tracking: Authentication callbacks (`zoo_add_auth`) receive results correlated directly with the specific AUTH packet reply, ensuring precise error reporting per credential scheme.

## Standalone Drop-in Compilation (`dev/build-dropin.sh`)

For localized development and rapid iteration on shim modifications without invoking full Autotools or CMake cycles, `dev/build-dropin.sh` compiles macOS drop-in libraries directly:

```sh
cd zookeeper-client/zookeeper-client-rust
FLAVORS="mt st" ABI_CHECK=1 dev/build-dropin.sh .. /tmp/zk-dropin
```

The script builds `libzookeeper_FL.2.dylib`, `libzookeeper_FL.a`, and `cli_FL` for each designated flavor, validates symbol exports, and executes sanity checks against both shared and static artifacts. Supported configuration options include `CONFIG=plain|ssl|sasl|full`, `OPENSSL_PREFIX`, `SASL_PREFIX`, `TLS_PROVIDER`, and `TLS_DEFAULT_PATHS`.

## Conformance Verification and Test Infrastructure

### ABI Conformance Verification

Validates exported dynamic symbols against golden symbol tables across configuration variants:

```sh
cd zookeeper-client/zookeeper-client-rust
cargo xtask abi check /tmp/zk-install/lib/libzookeeper_mt.dylib --flavor mt --config plain \
    --libdir /tmp/zk-install/lib
cargo xtask abi check /tmp/zk-install/lib/libzookeeper_st.dylib --flavor st --config plain \
    --libdir /tmp/zk-install/lib
```

Verifies encapsulation within static library archives:

```sh
cargo xtask abi static /tmp/zk-install/lib/libzookeeper_st.a --flavor st --config plain
```

### Header Synchronization Checks

Ensures that C declarations generated from Rust structures and log site enumerations remain synchronized:

```sh
(cd crates/zk-ffi && cbindgen --config cbindgen.toml --crate zk-ffi \
    --output ../../c/shim/zkrs.h --verify)
cargo xtask log-sites --check
cargo xtask log-sites header --check
```

### Shim Compilation Diagnostics

Verifies clean compilation of all shim translation units across both threading flavors under strict diagnostic settings:

```sh
cd zookeeper-client/zookeeper-client-c
for t in -DTHREADED ""; do
  for f in ../zookeeper-client-rust/c/shim/*.c; do
    cc -std=c99 -Wall -Wextra -Wdeclaration-after-statement -Werror -D_GNU_SOURCE \
        -fexceptions $t -Iinclude -Igenerated -I../zookeeper-client-rust/c/shim -c "$f" -o /dev/null
  done
done
```

### Rust Workspace Test Matrix

Executes code formatting, static analysis, unit test suites, and documentation verification:

```sh
cargo fmt --all --check
cargo clippy --locked --workspace --all-targets -- -D warnings
cargo test --locked --workspace
RUSTDOCFLAGS='-D warnings' cargo doc --locked --workspace --no-deps
cargo +1.85.0 check --locked --workspace --all-targets
```

Validates `zk-ffi` feature combinations:

```sh
for f in tls,tls-ring sasl tls,tls-ring,sasl tls,tls-aws-lc-rs,sasl; do
  cargo clippy --locked -p zk-ffi --all-targets --features "$f" -- -D warnings
  cargo test --locked -p zk-ffi --features "$f"
done
```

## Integration Testing Against Standalone ZooKeeper Ensembles

The development harness `dev/test-server.sh` provides standalone ZooKeeper server lifecycle control for integration testing:

```sh
cd zookeeper-client/zookeeper-client-rust
dev/test-server.sh start 22720 /tmp/zk-server
/tmp/zk-install/bin/cli_mt -h 127.0.0.1:22720
/tmp/zk-install/bin/cli_st -h 127.0.0.1:22720
dev/test-server.sh stop 22720 /tmp/zk-server
```

### TLS Session Testing

Requires a server configured with `secureClientPort`, Netty connection factory (`NettyServerCnxnFactory`), and key store definitions configured via `ZK_TEST_SERVER_PROPS`. Certificates are generated using `ssl/gencerts.sh`:

```sh
cli_mt -s "server.crt,client.crt,clientkey.pem,password" -h localhost:22721 -c "ls /"
```

Both C client cores validate server certificates using chain-only trust validation. Certificate generation via OpenSSL 3.2+ produces v3 certificates with authority key identifiers that require appropriate trust anchor configuration matching OpenSSL 3.1 legacy conventions.

### SASL Authentication Session Testing

Requires JAAS configuration with a `Server` entry and SASL authentication provider registration via `ZK_TEST_SERVER_JVM_OPTS`:

```sh
cli_mt --mechlist DIGEST-MD5 --user myuser --password-file /tmp/pw \
    --server-fqdn zk-sasl-md5 -h localhost:22720 -c "ls /"
```

## Public API CppUnit Integration Suite

The integration suites `zktest-public-mt` and `zktest-public-st` adapt legacy CppUnit integration tests to execute exclusively against exported public client APIs:
- Test cases include `TestClient`, `TestMulti`, `TestClientRetry`, `TestLogClientEnv`, `TestReadOnlyClient`, `TestSASLAuth`, and `TestReconfigServer`.
- Sources are prepared dynamically via `tests/public/gen-sources.sh`, which strips internal legacy core symbol references (`tests/public/omit-cases.txt`) while maintaining source tree separation.
- Tests link dynamically against `libzookeeper_mt` or `libzookeeper_st` without mocking or linker symbol wrapping (`--wrap`).
- Server lifecycle is managed by `tests/public/zkServer.sh` backed by `dev/test-server.sh`.

### Execution Invocations

Autotools suite execution:

```sh
make check-public ZKTEST_PORT=22730 ZKTEST_DOWN_PORT=22731 \
    ZKTEST_QUORUM_PORT_BASE=22738 ZKTEST_RO_PORT_BASE=22732
```

CMake suite execution:

```sh
cmake -S zookeeper-client/zookeeper-client-rust/c -B /tmp/zk-cmake -DZOOKEEPER_BUILD_SHARED=ON \
    -DWANT_CPPUNIT_PUBLIC=ON -DZKTEST_PORT=22730 -DZKTEST_DOWN_PORT=22731 \
    -DZKTEST_QUORUM_PORT_BASE=22738 -DZKTEST_RO_PORT_BASE=22732
cmake --build /tmp/zk-cmake --target check-public
```

Comparative validation against legacy library artifacts:

```sh
ZKTEST_PORT=22730 ZKTEST_RO_PORT_BASE=22732 tests/public/run-public.sh \
    --libdir /tmp/zk-legacy/lib --workdir /tmp/zk-public-legacy /tmp/zk-cmake/zktest-public-mt
```

### Deterministic Session Comparison (`run-cli-session.sh`)

Executes scripted interactive sessions (`tests/public/cli-session.txt`) against both legacy and Rust client implementations, normalizing session identifiers, transaction IDs (zxids), and timestamps to verify output parity:

```sh
ZKTEST_PORT=22730 tests/public/run-cli-session.sh \
    --legacy /tmp/zk-legacy/bin/cli_st --legacy-libdir /tmp/zk-legacy/lib \
    --rust /tmp/zk-install/bin/cli_st --rust-libdir /tmp/zk-install/lib
```
