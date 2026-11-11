#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Cargo lifecycle task runner for the `zookeeper-client-rust` Maven module.
#
#   sh rust-module.sh prepare|build|test|lint|interop
#   sh rust-module.sh dropin-autoreconf|dropin-configure|dropin-install
#   sh rust-module.sh dropin-abi-check|dropin-check
#
# Invoked by `exec-maven-plugin` from `pom.xml` with Maven properties exported
# in the environment:
#   ZKRS_CLIENT          `rust-client` (`no` or `yes`)
#   ZKRS_TLS             `rust-tls` (`no` or `yes`)
#   ZKRS_TLS_PROVIDER    `rust-tls-provider` (`ring`, `aws-lc-rs`, or `aws-lc-rs-fips`)
#   ZKRS_TARGET_CPU      `rust-target-cpu` (`auto`, `no`, or a CPU name)
#   ZKRS_CARGO_VENDOR    `rust-cargo-vendor` (vendor directory path, or empty)
#   ZKRS_SYSTEM_CRATES   `rust-system-crates` (system crate directory path, or empty)
#   ZKRS_OPENSSL         `c-client-openssl` (`yes`, `no`, or prefix `DIR`)
#   ZKRS_SASL            `c-client-sasl` (`yes`, `no`, or prefix `DIR`)
#   ZKRS_VERSION         root Maven project version
#   ZKRS_BUILD_DIR       module build output directory
#   ZKRS_SKIP_TESTS      `skipTests` (`true` skips test execution)
#   ZKRS_TEST_MODE       test execution mode (`plain`, `coverage`, or `asan`)
#   ZKRS_TEST_PORT_BASE  base TCP port for live-server tests, or empty
#   ZKRS_IT_PORT_BASE    base TCP port for the Curator interop suite
#   ZKRS_IT_VERSIONS     target server versions for the Curator interop suite
#   ZKRS_IT_MVN_ARGS     additional Maven arguments for the Curator interop suite
#   ZKRS_MVN_OFFLINE     set to `offline=true` when Maven runs in offline mode
#   ZKRS_MVN_REPO        local Maven repository path
#   CARGO_TARGET_DIR     Cargo build target directory
#
# `prepare` resolves the toolchain, feature and crate-source configuration one
# time. It writes the result to `$ZKRS_BUILD_DIR/rust-build.env`. The later
# tasks read this state, so they use the same feature flags and crate sources.
# When `rust-client=no`, all tasks exit 0 immediately.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
ws=$(cd "$here/.." && pwd)
task=${1:-}
build_dir=${ZKRS_BUILD_DIR:-$ws/target}
state=$build_dir/rust-build.env
src_config=$build_dir/cargo-sources.toml
zoo_version_h=$ws/../zookeeper-client-c/include/zookeeper_version.h

fail() {
  echo "zookeeper-client-rust: $*" >&2
  exit 1
}

note() {
  echo "zookeeper-client-rust: $*"
}

# Prints `MAJOR * 1000 + MINOR` parsed from `$1 --version`.
tool_version() {
  "$1" --version 2>/dev/null | awk '{ split($2, v, "."); print (v[1] * 1000 + v[2]) }'
}

# Compiles and links a C probe program `$1` using prefix option `$2` (`yes` or
# `DIR`) and link flags `$3`.
cc_probe() {
  probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/zkrs-probe.XXXXXX")
  printf '%s\n' "$1" >"$probe_dir/probe.c"
  dir_flags=
  if [ "$2" != yes ]; then
    dir_flags="-I$2/include -L$2/lib"
  fi
  # Allow word splitting across compiler and flag variables.
  # shellcheck disable=SC2086
  if ${CC:-cc} ${CPPFLAGS:-} ${CFLAGS:-} $dir_flags ${LDFLAGS:-} -o "$probe_dir/probe" \
      "$probe_dir/probe.c" $3 >/dev/null 2>&1; then
    rm -rf "$probe_dir"
    return 0
  fi
  rm -rf "$probe_dir"
  return 1
}

# Invokes `cargo` with `--locked` and any configured local crate source
# replacement. Setting `cargo_toolchain` (e.g., `+nightly`) selects the
# corresponding rustup toolchain.
# Setting `cargo_cpu_args` runs cargo through `abi/target-cpu.sh`, which
# adds `-C target-cpu` to the rustflags of the user.
cargo_toolchain=
cargo_cpu_args=
run_cargo() {
  sub=$1
  shift
  if [ -f "$src_config" ]; then
    set -- "$sub" --locked --offline --config "$src_config" "$@"
  else
    set -- "$sub" --locked "$@"
  fi
  # shellcheck disable=SC2086
  if [ -n "$cargo_cpu_args" ]; then
    sh "$ws/c/abi/target-cpu.sh" run $cargo_cpu_args -- cargo $cargo_toolchain "$@"
  else
    cargo $cargo_toolchain "$@"
  fi
}

# Loads the build configuration persisted by `prepare`.
load_state() {
  [ -f "$state" ] ||
    fail "$state is missing: run the process-sources goals of this module first"
  tls=no
  openssl=no
  sasl=no
  features=
  cert_file=
  cert_dir=
  rust_target=
  target_cpu=
  target_cpu_policy=auto
  while IFS='=' read -r key value; do
    case "$key" in
      tls) tls=$value ;;
      openssl) openssl=$value ;;
      sasl) sasl=$value ;;
      features) features=$value ;;
      cert_file) cert_file=$value ;;
      cert_dir) cert_dir=$value ;;
      rust_target) rust_target=$value ;;
      target_cpu) target_cpu=$value ;;
      target_cpu_policy) target_cpu_policy=$value ;;
    esac
  done <"$state"
  feature_args=
  if [ -n "$features" ]; then
    feature_args="--features=$features"
  fi
  ZK_TLS_DEFAULT_CERT_FILE=$cert_file
  ZK_TLS_DEFAULT_CERT_DIR=$cert_dir
  ZK_VERSION_H=$zoo_version_h
  export ZK_TLS_DEFAULT_CERT_FILE ZK_TLS_DEFAULT_CERT_DIR ZK_VERSION_H
}

# Configures local crate source replacement (`[source.crates-io]`) in
# `$src_config` when `ZKRS_CARGO_VENDOR` or `ZKRS_SYSTEM_CRATES` is set.
# In system-crates mode, lockfiles are regenerated offline to match the
# installed crate versions.
prepare_sources() {
  rm -f "$src_config"
  vendor=${ZKRS_CARGO_VENDOR:-}
  system=${ZKRS_SYSTEM_CRATES:-}
  if [ -n "$vendor" ] && [ -n "$system" ]; then
    fail "give only one of -Drust-cargo-vendor and -Drust-system-crates"
  fi
  dir=$vendor$system
  [ -n "$dir" ] || return 0
  [ -d "$dir" ] || fail "the crate directory $dir does not exist"
  dir=$(cd "$dir" && pwd)
  kind=directory
  if [ -n "$system" ] && [ -d "$dir/index" ]; then
    kind=local-registry
  fi
  mkdir -p "$build_dir"
  {
    echo "# Generated Cargo source replacement for the Maven build."
    echo "[source.crates-io]"
    echo "replace-with = \"zk-local\""
    echo "[source.zk-local]"
    echo "$kind = \"$dir\""
  } >"$src_config"
  if [ -n "$system" ]; then
    # Regenerate lockfiles against the system crate directory.
    note "system crates from $dir: cargo generate-lockfile writes Cargo.lock again"
    (cd "$ws" && cargo generate-lockfile --offline --config "$src_config")
    (cd "$ws" && cargo generate-lockfile --offline --config "$src_config" \
      --manifest-path crates/zk-ffi-layout/Cargo.toml)
  else
    note "vendored crates from $dir"
  fi
}

prepare() {
  case "${ZKRS_TLS:-no}" in
    yes|no) ;;
    *) fail "rust-tls=${ZKRS_TLS}: give yes or no" ;;
  esac
  provider=${ZKRS_TLS_PROVIDER:-ring}
  case "$provider" in
    ring|aws-lc-rs|aws-lc-rs-fips) ;;
    *) fail "rust-tls-provider=$provider: give ring, aws-lc-rs or aws-lc-rs-fips" ;;
  esac

  # Verify that cargo and rustc satisfy the workspace MSRV (1.85+).
  for tool in cargo rustc; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool not found: the Rust client needs cargo and rustc 1.85 or later"
    v=$(tool_version "$tool")
    if [ -z "$v" ] || [ "$v" -lt 1085 ]; then
      fail "$tool $("$tool" --version 2>/dev/null) is too old: the Rust client needs 1.85 or later"
    fi
  done

  # Verify that Cargo.toml matches the root POM version (stripped of -SNAPSHOT).
  want=${ZKRS_VERSION:-}
  want=${want%-SNAPSHOT}
  have=$(sed -n '/^\[workspace.package\]/,/^\[/s/^version *= *"\(.*\)"/\1/p' "$ws/Cargo.toml")
  if [ "$have" != "$want" ]; then
    fail "the Cargo.toml workspace version $have is not the project version $want:" \
      "run 'mvn -N antrun:run@replace-rustclient-files-during-release' from the top level directory"
  fi

  # Probe TLS support and default certificate paths to align with C client configure.
  probe_out=$(sh "$ws/c/abi/tls-probe.sh" "--with-openssl=${ZKRS_OPENSSL:-yes}" \
    "--with-rust-tls=${ZKRS_TLS:-no}") ||
    fail "abi/tls-probe.sh failed"
  tls=$(printf '%s\n' "$probe_out" | sed -n 's/^tls=//p')
  openssl=$(printf '%s\n' "$probe_out" | sed -n 's/^openssl=//p')
  cert_file=$(printf '%s\n' "$probe_out" | sed -n 's/^cert_file=//p')
  cert_dir=$(printf '%s\n' "$probe_out" | sed -n 's/^cert_dir=//p')

  # Probe Cyrus SASL headers and libsasl2 linkage.
  sasl=no
  if [ "${ZKRS_SASL:-yes}" != no ] &&
      cc_probe '#include <sasl/sasl.h>
int main(void) { return sasl_client_init(0); }' "${ZKRS_SASL:-yes}" -lsasl2; then
    sasl=yes
  fi

  features=
  if [ "$tls" = yes ]; then
    features=tls-$provider
  elif [ "$provider" != ring ]; then
    note "TLS is not built: rust-tls-provider=$provider is not used"
  fi
  if [ "$sasl" = yes ]; then
    features=${features:+$features,}sasl
  fi

  # The Rust target CPU of the staticlib. abi/target-cpu.sh makes the
  # decision of configure and CMake, with the C compiler of cc_probe.
  rust_target=$(rustc -vV | sed -n 's/^host: //p')
  cpu_choice=${ZKRS_TARGET_CPU:-auto}
  target_cpu_policy=explicit
  case "$(printf '%s' "$cpu_choice" | tr '[:upper:]' '[:lower:]')" in
    auto | yes) cpu_choice=auto target_cpu_policy=auto ;;
    no | off) cpu_choice=no ;;
  esac
  target_cpu=
  if [ "$cpu_choice" = no ]; then
    cpu_why="rust-target-cpu=no"
  else
    cpu_out=$(sh "$ws/c/abi/target-cpu.sh" probe "--choice=$cpu_choice" --rustc=rustc \
      "--target=$rust_target" "--dir=$ws" "--cc=${CC:-cc}" "--cflags=${CPPFLAGS:-} ${CFLAGS:-}") ||
      fail "rust-target-cpu=$cpu_choice is not valid"
    target_cpu=$(printf '%s\n' "$cpu_out" | sed -n 's/^target_cpu=//p')
    cpu_why=$(printf '%s\n' "$cpu_out" | sed -n 's/^why=//p')
  fi
  note "Rust target CPU: ${target_cpu:-none} ($cpu_why)"

  prepare_sources
  note "cargo fetch"
  (cd "$ws" && run_cargo fetch)

  mkdir -p "$build_dir"
  {
    echo "tls=$tls"
    echo "openssl=$openssl"
    echo "sasl=$sasl"
    echo "features=$features"
    echo "cert_file=$cert_file"
    echo "cert_dir=$cert_dir"
    echo "rust_target=$rust_target"
    echo "target_cpu=$target_cpu"
    echo "target_cpu_policy=$target_cpu_policy"
  } >"$state"
  note "tls=$tls provider=$provider sasl=$sasl features=${features:-none}"
}

build() {
  load_state
  cd "$ws"
  # Build zk-ffi in isolation so workspace feature unification cannot alter its closure.
  # The staticlib gets the Rust target CPU of the prepare task. The other
  # crates keep the default code generation.
  if [ -n "$target_cpu" ]; then
    cargo_cpu_args="--cpu=$target_cpu --policy=$target_cpu_policy --target=$rust_target"
  fi
  # shellcheck disable=SC2086
  run_cargo build -p zk-ffi --profile capi $feature_args
  cargo_cpu_args=
  run_cargo build --workspace --release --exclude zk-ffi
  note "staticlib $CARGO_TARGET_DIR/capi/libzk_ffi.a"
}

# Runs the standalone `zk-ffi-layout` cross-language struct layout checks across
# `plain` and all active `ssl`, `sasl`, and `full` header configurations.
# Because `zookeeper.h` exposes OpenSSL types only when `<openssl/ossl_typ.h>`
# is available, the `ssl` layout configuration is gated on the OpenSSL header
# probe rather than `tls`.
layout_check() {
  configs=plain
  if [ "$openssl" = yes ]; then
    configs=$configs,ssl
    case "${ZKRS_OPENSSL:-yes}" in
      yes | '') ;;
      *)
        ZK_LAYOUT_SSL_CFLAGS="-I$ZKRS_OPENSSL/include"
        export ZK_LAYOUT_SSL_CFLAGS
        ;;
    esac
  fi
  if [ "$sasl" = yes ]; then
    configs=$configs,sasl
    if [ "${ZKRS_SASL:-yes}" != yes ]; then
      ZK_LAYOUT_SASL_CFLAGS="-I$ZKRS_SASL/include"
      export ZK_LAYOUT_SASL_CFLAGS
    fi
  fi
  if [ "$openssl" = yes ] && [ "$sasl" = yes ]; then
    configs=$configs,full
  fi
  ZK_LAYOUT_CONFIGS=$configs
  export ZK_LAYOUT_CONFIGS
  note "layout check: $configs"
  run_cargo test --manifest-path crates/zk-ffi-layout/Cargo.toml
}

run_tests() {
  if [ "${ZKRS_SKIP_TESTS:-false}" = true ]; then
    note "skipTests is set: the cargo tests do not run"
    return 0
  fi
  load_state
  cd "$ws"
  # When `ZKRS_TEST_PORT_BASE` is set, include ignored live-server tests
  # (excluding the host-dependent DNS resolver test).
  set --
  if [ -n "${ZKRS_TEST_PORT_BASE:-}" ]; then
    ZK_TEST_PORT_BASE=$ZKRS_TEST_PORT_BASE
    ZK_TEST_REQUIRE_SERVER=${ZK_TEST_REQUIRE_SERVER:-1}
    export ZK_TEST_PORT_BASE ZK_TEST_REQUIRE_SERVER
    set -- -- --include-ignored --skip std_host_leaves_resolve_localhost
    note "real-server tests on the ports $ZK_TEST_PORT_BASE to $((ZK_TEST_PORT_BASE + 19))"
  fi
  case "${ZKRS_TEST_MODE:-plain}" in
    plain)
      run_cargo test --workspace --exclude zk-ffi "$@"
      # shellcheck disable=SC2086
      run_cargo test -p zk-ffi $feature_args "$@"
      layout_check
      ;;
    coverage)
      command -v cargo-llvm-cov >/dev/null 2>&1 ||
        fail "rust-test-coverage needs cargo-llvm-cov: cargo install --locked cargo-llvm-cov"
      run_cargo llvm-cov --no-report --workspace --exclude zk-ffi "$@"
      # shellcheck disable=SC2086
      run_cargo llvm-cov --no-report -p zk-ffi $feature_args "$@"
      cargo llvm-cov report --html --output-dir "$build_dir/rust-coverage"
      cargo llvm-cov report --lcov --output-path "$build_dir/rust-coverage/lcov.info"
      note "coverage report in $build_dir/rust-coverage"
      ;;
    asan)
      # AddressSanitizer requires a nightly toolchain and explicit `--target`
      # so instrumentation applies only to target crates.
      cargo +nightly --version >/dev/null 2>&1 ||
        fail "rust-test-asan needs a nightly toolchain: rustup toolchain install nightly"
      host=$(rustc -vV | sed -n 's/^host: //p')
      CARGO_TARGET_DIR=$build_dir/cargo-asan
      RUSTFLAGS="${RUSTFLAGS:-} -Zsanitizer=address"
      export CARGO_TARGET_DIR RUSTFLAGS
      case "$host" in
        # LeakSanitizer is unsupported on macOS.
        *-apple-darwin) ASAN_OPTIONS=${ASAN_OPTIONS:-detect_leaks=0} ;;
        *) ASAN_OPTIONS=${ASAN_OPTIONS:-detect_leaks=1} ;;
      esac
      export ASAN_OPTIONS
      cargo_toolchain=+nightly
      # Skip doctests under ASan because rustdoc does not forward target sanitizer flags.
      run_cargo test --target "$host" --workspace --exclude zk-ffi --lib --bins --tests "$@"
      # shellcheck disable=SC2086
      run_cargo test --target "$host" -p zk-ffi $feature_args --lib --tests "$@"
      ;;
    *) fail "unknown test mode ${ZKRS_TEST_MODE}" ;;
  esac
}

lint() {
  load_state
  cd "$ws"
  cargo fmt --all --check || fail "cargo fmt --check found unformatted code: run cargo fmt --all"
  run_cargo clippy --workspace --exclude zk-ffi --all-targets -- -D warnings
  # shellcheck disable=SC2086
  run_cargo clippy -p zk-ffi --all-targets $feature_args -- -D warnings
}

# Executes the Apache Curator recipe interoperability suite via `dev/run-curator-interop.sh`.
interop() {
  args=-B
  if [ "${ZKRS_MVN_OFFLINE:-}" = offline=true ]; then
    args="$args -o"
  fi
  if [ -n "${ZKRS_MVN_REPO:-}" ]; then
    args="$args -Dmaven.repo.local=$ZKRS_MVN_REPO"
  fi
  ZK_INTEROP_MVN_ARGS="$args ${ZKRS_IT_MVN_ARGS:-}"
  # Forward configured crate source replacements to the interop peer build.
  ZK_INTEROP_CARGO_ARGS=
  if [ -f "$src_config" ]; then
    ZK_INTEROP_CARGO_ARGS="--offline --config $src_config"
  fi
  ZK_TEST_PORT_BASE=${ZKRS_IT_PORT_BASE:-23400}
  export ZK_INTEROP_MVN_ARGS ZK_INTEROP_CARGO_ARGS ZK_TEST_PORT_BASE
  # Allow word splitting across space-separated server versions.
  # shellcheck disable=SC2086
  bash "$ws/dev/run-curator-interop.sh" ${ZKRS_IT_VERSIONS:-head}
}

# The dropin tasks run only with rust-dropin=yes. This check also runs
# when the script runs alone.
dropin_enabled() {
  case "${ZKRS_DROPIN:-yes}" in
    yes) return 0 ;;
    no)
      note "rust-dropin=no: the $task task does nothing"
      return 1
      ;;
    *) fail "rust-dropin=${ZKRS_DROPIN}: give no or yes" ;;
  esac
}

dropin_src=$ws/c
dropin_build=$build_dir/c-build
dropin_prefix=$build_dir/c

dropin_autoreconf() {
  dropin_enabled || return 0
  cd "$dropin_src"
  autoreconf -if
}

# configure gets the decisions of the prepare task and the staticlib of
# the build task. It checks that the staticlib has the same TLS and SASL
# decisions as its own probes.
dropin_configure() {
  dropin_enabled || return 0
  load_state
  staticlib=${CARGO_TARGET_DIR:-$ws/target}/capi/libzk_ffi.a
  [ -f "$staticlib" ] || fail "$staticlib is missing: run the compile goals of this module first"
  [ -x "$dropin_src/configure" ] || fail "$dropin_src/configure is missing: run the process-sources goals of this module first"
  mkdir -p "$dropin_build"
  cd "$dropin_build"
  # The configure options can hold more than one word, so they are not quoted.
  # shellcheck disable=SC2086
  "$dropin_src/configure" \
    "--with-openssl=${ZKRS_OPENSSL:-yes}" \
    "--with-sasl=${ZKRS_SASL:-yes}" \
    "--prefix=$dropin_prefix" \
    ${ZKRS_CONFIGURE_ARGS:-} \
    "--with-rust-staticlib=$staticlib" \
    "--with-rust-tls=${ZKRS_TLS:-no}" \
    "--with-rust-tls-provider=${ZKRS_TLS_PROVIDER:-ring}" \
    "--with-cargo-vendor=${ZKRS_CARGO_VENDOR:-}" \
    "--with-system-rust-crates=${ZKRS_SYSTEM_CRATES:-}"
}

dropin_make() {
  [ -f "$dropin_build/Makefile" ] || fail "$dropin_build/Makefile is missing: run the compile goals of this module first"
  cd "$dropin_build"
  make "$@"
}

# Each goal has its own make call, because a parallel make can run two
# goals of one call at the same time.
dropin_install() {
  dropin_enabled || return 0
  dropin_make clean
  dropin_make install
}

# abi-check compares the exports of each shared library with the
# goldens. check-abi runs the runtime ABI tests on the installed
# libraries.
dropin_abi_check() {
  dropin_enabled || return 0
  dropin_make abi-check
  dropin_make check-abi
}

# The link tests and the public-API suites. The suites start a test
# server. A port base gives the server ports BASE to BASE+17. Without a
# port base, the suites use the ports of the legacy tests.
dropin_check() {
  case "${ZKRS_DROPIN_CHECK:-no}" in
    yes | true) ;;
    no | false)
      note "rust-dropin-check=${ZKRS_DROPIN_CHECK}: the $task task does nothing"
      return 0
      ;;
    *) fail "rust-dropin-check=${ZKRS_DROPIN_CHECK}: give no or yes" ;;
  esac
  if [ "${ZKRS_SKIP_TESTS:-false}" = true ]; then
    note "skipTests is set: the $task task does nothing"
    return 0
  fi
  dropin_enabled || return 0
  set --
  base=${ZKRS_TEST_PORT_BASE:-}
  if [ -n "$base" ]; then
    set -- "ZKTEST_PORT=$base" "ZKTEST_DOWN_PORT=$((base + 1))" \
      "ZKTEST_RO_PORT_BASE=$((base + 2))" "ZKTEST_QUORUM_PORT_BASE=$((base + 8))" \
      "ZKTEST_SSL_PORT=$((base + 17))"
    note "public-API suites on the ports $base to $((base + 17))"
  fi
  dropin_make check-link
  dropin_make check-public "$@"
}

case "$task" in
  prepare) prepare ;;
  build) build ;;
  test) run_tests ;;
  lint) lint ;;
  interop) interop ;;
  dropin-autoreconf) dropin_autoreconf ;;
  dropin-configure) dropin_configure ;;
  dropin-install) dropin_install ;;
  dropin-abi-check) dropin_abi_check ;;
  dropin-check) dropin_check ;;
  *) fail "unknown task '$task': give prepare, build, test, lint, interop, dropin-autoreconf, dropin-configure, dropin-install, dropin-abi-check or dropin-check" ;;
esac
