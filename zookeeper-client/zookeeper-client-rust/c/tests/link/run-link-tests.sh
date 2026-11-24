#!/usr/bin/env bash
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

# Executes the static archive linkage test suite against a static Rust client
# archive.
#
# Usage: run-link-tests.sh --flavor mt|st --archive FILE [options]
#   --flavor mt|st        archive flavor (required)
#   --config C            feature configuration: plain, ssl, sasl, or full
#                         (default: plain). Selects the golden symbol set and
#                         consumer TLS/SASL build flags.
#   --cmake-arg ARG       additional CMake argument for consumer builds (later
#                         `-D` options override defaults from `--config`)
#   --archive FILE        path to `libzookeeper_FL.a` (Autotools) or
#                         `libzookeeper.a` (CMake) (required)
#   --hashtable FILE      path to CMake `libhashtable.a` stub archive
#   --la FILE             path to the libtool `.la` file
#   --pc FILE             path to `zookeeper_FL.pc`
#   --destdir DIR         staging `DESTDIR` prefix prepended to `.pc` `libdir`
#                         paths
#   --prefix DIR          CMake installation prefix tested with manual
#                         `-lzookeeper -lhashtable` linkage
#   --cmake-consumer      configure and build `tests/link/cmake-consumer` via
#                         `add_subdirectory()`
#   --cmake PATH          CMake executable (default: cmake)
#   --rust-staticlib FILE prebuilt `libzk_ffi.a` passed to the consumer build
#                         (invokes Cargo if omitted)
#   --native-libs "LIBS"  native system libraries required by the Rust archive
#                         (`ZKRS_NATIVE_LIBS`; queried from `rustc` by default)
#   --ref DIR             directory containing legacy `libzookeeper_FL.a` for
#                         DEV characterization of symbol collisions
#   --host H:P            running ZooKeeper server; if omitted, test binaries
#                         execute offline checks only
#   --out DIR             working directory (default: ${TMPDIR:-/tmp}/zk-link-tests-FL)
#   --no-xtask            skip `cargo xtask abi static`
#   --no-rust             skip building the secondary Rust `staticlib`
#
# When `ZKRS_CARGO_SOURCE_CONFIG` is set to a `cargo-sources.toml` file (for
# vendored or system crate builds), all Cargo invocations run in `--offline`
# mode against that configuration, and `zklinkring` builds from a staging copy
# in `--out` (regenerating `Cargo.lock` if system crate versions differ).
#
# This runner does not start or stop ZooKeeper servers. `KNOWN` lines record
# characterized upstream limitations without incrementing the failure count.

set -uo pipefail

# Resolve repository paths: RCDIR is the Rust C compatibility root, RDIR is the
# Rust workspace root, and CDIR is the legacy C client tree (overridable via
# ZK_C_DIR).
HERE="$(cd "$(dirname "$0")" && pwd)"
RCDIR="$(cd "$HERE/../.." && pwd)"
RDIR="$(cd "$RCDIR/.." && pwd)"
CDIR="$(cd "${ZK_C_DIR:-$RDIR/../zookeeper-client-c}" && pwd)"

FL=""
CONFIG=plain
CMAKE_EXTRA=()
ARCHIVE=""
HASHTABLE=""
LA=""
PC=""
PREFIX=""
CONSUMER=0
CMAKE=cmake
RUSTLIB=""
NATIVE=""
NATIVE_SET=0
REF=""
HOST=""
OUT=""
XTASK=1
RUST=1
while [ $# -gt 0 ]; do
    case "$1" in
    --flavor) FL="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --cmake-arg) CMAKE_EXTRA+=("$2"); shift 2 ;;
    --archive) ARCHIVE="$2"; shift 2 ;;
    --hashtable) HASHTABLE="$2"; shift 2 ;;
    --la) LA="$2"; shift 2 ;;
    --pc) PC="$2"; shift 2 ;;
    --destdir) DESTDIR="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --cmake-consumer) CONSUMER=1; shift ;;
    --cmake) CMAKE="$2"; shift 2 ;;
    --rust-staticlib) RUSTLIB="$2"; shift 2 ;;
    --native-libs) NATIVE="$2"; NATIVE_SET=1; shift 2 ;;
    --ref) REF="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --no-xtask) XTASK=0; shift ;;
    --no-rust) RUST=0; shift ;;
    *) echo "run-link-tests.sh: unknown option $1" >&2; exit 2 ;;
    esac
done
# Optional `--offline --config` arguments for Cargo invocations (guarded for
# empty-array expansion under `set -u` in bash 3.2).
SRCARGS=()
if [ -n "${ZKRS_CARGO_SOURCE_CONFIG:-}" ]; then
    if [ ! -f "$ZKRS_CARGO_SOURCE_CONFIG" ]; then
        echo "run-link-tests.sh: ZKRS_CARGO_SOURCE_CONFIG names no file:" \
            "$ZKRS_CARGO_SOURCE_CONFIG" >&2
        exit 2
    fi
    SRCARGS=(--offline --config "$ZKRS_CARGO_SOURCE_CONFIG")
fi
cargo_src() {
    cargo ${SRCARGS[@]+"${SRCARGS[@]}"} "$@"
}
case "$FL" in
mt) FLDEF=(-DTHREADED) ;;
st) FLDEF=() ;;
*) echo "usage: run-link-tests.sh --flavor mt|st --archive FILE [options]" >&2; exit 2 ;;
esac
# Align CMake consumer TLS/SASL flags with the target archive configuration.
case "$CONFIG" in
plain) CONFIG_ARGS=(-DWITH_OPENSSL=OFF -DWITH_CYRUS_SASL=OFF) ;;
ssl) CONFIG_ARGS=(-DWITH_RUST_TLS=ON -DWITH_CYRUS_SASL=OFF) ;;
sasl) CONFIG_ARGS=(-DWITH_OPENSSL=OFF -DWITH_CYRUS_SASL=ON) ;;
full) CONFIG_ARGS=(-DWITH_RUST_TLS=ON -DWITH_CYRUS_SASL=ON) ;;
*) echo "run-link-tests.sh: unknown config $CONFIG (use plain, ssl, sasl or full)" >&2; exit 2 ;;
esac
if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
    echo "run-link-tests.sh: --archive names no file: $ARCHIVE" >&2
    exit 2
fi
OUT="${OUT:-${TMPDIR:-/tmp}/zk-link-tests-$FL}"
mkdir -p "$OUT"
# TLS-enabled staticlib coexistence tests require the `zk-testkit` TLS
# certificate corpus. Unless `ZK_TEST_TLS_FIXTURES` points to an existing
# directory, invoke the `zk-fixtures` binary (using `ZK_TEST_OPENSSL` or
# `OPENSSL`, defaulting to Homebrew `openssl@3`) to generate and cache the
# fixtures on demand.
FIX=""
case "$CONFIG" in
ssl | full)
    if [ "$RUST" = 1 ]; then
        if [ -n "${ZK_TEST_TLS_FIXTURES:-}" ]; then
            FIX="$ZK_TEST_TLS_FIXTURES"
        elif command -v cargo >/dev/null 2>&1; then
            DEFAULT_OSSL="${OPENSSL:-/opt/homebrew/opt/openssl@3/bin/openssl}"
            FIX="$(cd "$RDIR" && \
                ZK_TEST_OPENSSL="${ZK_TEST_OPENSSL:-$DEFAULT_OSSL}" \
                cargo_src run -q --locked --offline -p zk-testkit --bin zk-fixtures -- tls)" || {
                echo "run-link-tests.sh: zk-fixtures failed to generate TLS fixtures" >&2
                exit 2
            }
        else
            echo "run-link-tests.sh: TLS fixtures require cargo; install cargo," \
                "set ZK_TEST_TLS_FIXTURES=DIR, or pass --no-rust" >&2
            exit 2
        fi
        if [ ! -f "$FIX/ca.pem" ]; then
            echo "run-link-tests.sh: TLS fixture directory $FIX is missing ca.pem" >&2
            exit 2
        fi
        # Normalize to an absolute path before embedding into a C string literal.
        FIX="$(cd "$FIX" && pwd)"
    fi
    ;;
esac
CC="${CC:-cc}"
OS="$(uname -s)"
INC=(-I"$CDIR/include" -I"$CDIR/generated")
CFLAGS=(-std=c99 -Wall -Wextra -Werror -D_DARWIN_C_SOURCE -D_GNU_SOURCE ${FLDEF[@]+"${FLDEF[@]}"})

PASS=0
FAIL=0
SKIP=0
KNOWN=0
pass() { PASS=$((PASS + 1)); echo "PASS $*"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL $*"; }
skip() { SKIP=$((SKIP + 1)); echo "SKIP $*"; }
known() { KNOWN=$((KNOWN + 1)); echo "KNOWN $*"; }
info() { echo "INFO $*"; }

# Executes a command, recording a pass on exit status 0 and dumping log tail on failure.
check() {
    local name="$1"
    shift
    if "$@" >"$OUT/$name.log" 2>&1; then
        pass "$name"
    else
        fail "$name (see $OUT/$name.log)"
        tail -20 "$OUT/$name.log"
    fi
}

# Links an executable, verifies that no duplicate-symbol warnings were emitted,
# and executes the resulting binary. Usage: link_run NAME LINK-COMMAND...
link_run() {
    local name="$1"
    shift
    if ! "$@" -o "$OUT/$name" >"$OUT/$name.link.log" 2>&1; then
        fail "$name: the link fails (see $OUT/$name.link.log)"
        head -20 "$OUT/$name.link.log"
        return
    fi
    if grep -qi 'duplicate' "$OUT/$name.link.log"; then
        fail "$name: the linker reports a duplicate symbol (see $OUT/$name.link.log)"
        return
    fi
    check "$name" "$OUT/$name" ${HOST:+"$HOST"}
}

# Lists defined global symbols of an archive or object file, one per line.
globals() {
    nm -g "$1" 2>/dev/null | awk 'NF >= 3 && $(NF - 1) ~ /^[A-TV-Z]$/ { print $NF }' | sort -u
}

# Verifies that `libs` includes every required native library
# (`ZKRS_NATIVE_LIBS`) and omits OpenSSL (`libssl`/`libcrypto`). Accepts full
# library paths emitted by CMake as equivalent to `-lNAME`.
has_native() {
    local what="$1" libs="$2" l
    for l in $NATIVE; do
        case " $libs " in
        *" $l "*) continue ;;
        esac
        case "$l" in
        -l*)
            if printf '%s\n' $libs |
                grep -Eq "/lib${l#-l}(\.[0-9]+)*\.(a|so|dylib|tbd)(\.[0-9]+)*$"; then
                continue
            fi
            ;;
        esac
        fail "LINK-05 $what does not name $l"
        return 1
    done
    case " $libs " in
    *ssl* | *crypto*) fail "LINK-05 $what names an OpenSSL library: $libs"; return 1 ;;
    esac
    pass "LINK-05 $what: '$libs'"
}

# Query `rustc --print native-static-libs` (matching `m4/zk_rust.m4` and
# `cmake/ZkRust.cmake`), filtering out `-lc` and `-lSystem` supplied by the C
# compiler driver.
if [ "$NATIVE_SET" = 0 ] && command -v rustc >/dev/null 2>&1; then
    echo 'pub fn f() {}' >"$OUT/native.rs"
    NATIVE="$(rustc --crate-type staticlib --print native-static-libs "$OUT/native.rs" \
        -o "$OUT/native.a" 2>&1 | sed -n 's/.*native-static-libs: //p' | tr ' ' '\n' |
        grep -Ev '^(-lc|-lSystem)$' | awk 'NF && !seen[$0]++' | tr '\n' ' ')"
fi
read -r -a LIBS <<<"$NATIVE"
[ "$FL" = mt ] && LIBS+=(-lpthread)
[ "$OS" = Linux ] && LIBS+=(-lrt)
# SASL-enabled archives require `-lsasl2` (resolved via `LIBRARY_PATH` or `--native-libs`).
case "$CONFIG" in
sasl | full)
    case " ${LIBS[*]-} " in
    *" -lsasl2 "*) ;;
    *) LIBS+=(-lsasl2) ;;
    esac
    ;;
esac
info "flavor $FL, config $CONFIG, archive $ARCHIVE, native libraries: ${NATIVE:-none}"

# ---- Static archive golden symbol table and member count ----
if [ "$XTASK" = 1 ] && [ -n "$RDIR" ] && command -v cargo >/dev/null 2>&1; then
    xtask_static() {
        (cd "$RDIR" && cargo_src xtask abi static "$ARCHIVE" --flavor "$FL" --config "$CONFIG")
    }
    check xtask-abi-static xtask_static
else
    skip "xtask abi static"
fi
globals "$ARCHIVE" >"$OUT/core-globals.txt"
NMEM="$(ar t "$ARCHIVE" | grep -cv '^__.SYMDEF')"
if [ "$NMEM" = 1 ]; then
    pass "S3 the archive has one member"
else
    fail "S3 the archive has $NMEM members"
fi

# ---- Coexistence with an independent Rust staticlib ----
# Both archives embed their own copy of `std`; because the prelinked ZooKeeper
# archive localizes all non-ABI symbols, the two `std` instances link without
# symbol collisions. In TLS configurations (`ssl` or `full`), the secondary
# archive (`zklinkring`) also embeds `rustls` and `ring` and performs an
# in-process mTLS handshake with the ZooKeeper client.
RUSTDEF=()
RUSTA=()
case "$CONFIG" in
ssl | full) RING=1 ;;
*) RING=0 ;;
esac
build_second() {
    if [ "$RING" = 1 ]; then
        command -v cargo >/dev/null 2>&1 || return 1
        local ring="$HERE/zklinkring"
        : >"$OUT/zklinkring.log"
        if [ ${#SRCARGS[@]} -gt 0 ]; then
            # Copy `zklinkring` and `zklinkrs.rs` into `$OUT` so `Cargo.lock` can
            # be regenerated if offline/vendored crate versions differ from git.
            rm -rf "$OUT/zklinkring-src"
            ring="$OUT/zklinkring-src/zklinkring"
            mkdir -p "$ring"
            cp "$HERE/zklinkrs.rs" "$OUT/zklinkring-src/" || return 1
            cp -R "$HERE/zklinkring/Cargo.toml" "$HERE/zklinkring/Cargo.lock" \
                "$HERE/zklinkring/src" "$ring/" || return 1
            if ! cargo_src fetch --locked --manifest-path "$ring/Cargo.toml" \
                >>"$OUT/zklinkring.log" 2>&1; then
                info "zklinkring: regenerating Cargo.lock for configured crate sources"
                cargo_src generate-lockfile --manifest-path "$ring/Cargo.toml" \
                    >>"$OUT/zklinkring.log" 2>&1 || return 1
            fi
        fi
        cargo_src build --release --locked --offline --manifest-path "$ring/Cargo.toml" \
            --target-dir "$OUT/zklinkring-target" >>"$OUT/zklinkring.log" 2>&1 || return 1
        cp "$OUT/zklinkring-target/release/libzklinkring.a" "$OUT/libzklinkrs.a"
    else
        rustc --edition 2021 --crate-type staticlib --crate-name zklinkrs -C panic=abort \
            -C opt-level=1 "$HERE/zklinkrs.rs" -o "$OUT/libzklinkrs.a" >"$OUT/zklinkrs.log" 2>&1
    fi
}
if [ "$RUST" = 1 ] && command -v rustc >/dev/null 2>&1; then
    if build_second; then
        RUSTDEF=(-DZKLINK_RUST)
        if [ "$RING" = 1 ]; then
            RUSTDEF+=(-DZKLINK_RING
                "-DZKLINK_TLS_FIX=\"$FIX\"")
        fi
        RUSTA=("$OUT/libzklinkrs.a")
        globals "$OUT/libzklinkrs.a" >"$OUT/rust-globals.txt"
        COMMON="$(comm -12 "$OUT/core-globals.txt" "$OUT/rust-globals.txt")"
        if [ -z "$COMMON" ]; then
            pass "LINK-01 the core and the Rust staticlib define no common global" \
                "($(wc -l <"$OUT/rust-globals.txt" | tr -d ' ') Rust globals)"
        else
            fail "LINK-01 common globals: $(echo "$COMMON" | head -5 | tr '\n' ' ')"
        fi
        if [ "$RING" = 1 ]; then
            # Confirm `zklinkring` exports unlocalized `ring_core_*` symbols
            # while the prelinked ZooKeeper archive localizes its own copy.
            NRING="$(grep -c 'ring_core_' "$OUT/rust-globals.txt")"
            if [ "$NRING" -gt 0 ]; then
                pass "LINK-01 the Rust staticlib has its own ring ($NRING ring_core_ globals)"
            else
                fail "LINK-01 the Rust staticlib has no ring_core_ global"
            fi
        fi
        link_run link01-core-first "$CC" "${CFLAGS[@]}" "${INC[@]}" ${RUSTDEF[@]+"${RUSTDEF[@]}"} \
            "$HERE/link_app.c" "$ARCHIVE" ${RUSTA[@]+"${RUSTA[@]}"} ${LIBS[@]+"${LIBS[@]}"}
        link_run link01-rust-first "$CC" "${CFLAGS[@]}" "${INC[@]}" ${RUSTDEF[@]+"${RUSTDEF[@]}"} \
            "$HERE/link_app.c" ${RUSTA[@]+"${RUSTA[@]}"} "$ARCHIVE" ${LIBS[@]+"${LIBS[@]}"}
    elif [ "$RING" = 1 ]; then
        fail "LINK-01 zklinkring does not build (see $OUT/zklinkring.log)"
    else
        fail "LINK-01 the Rust staticlib does not build (see $OUT/zklinkrs.log)"
    fi
    if [ "$RING" = 0 ]; then
        info "LINK-01 has no TLS in this configuration: the crypto provider part is not run"
    fi
else
    skip "LINK-01 (no rustc)"
fi

# ---- Whole-archive linkage ----
# Verifies `-force_load` and `-all_load` on macOS and `--whole-archive` on
# Linux, and confirms that no global symbol in the archive collides with `libc`,
# `libpthread`, or `libSystem`.
if [ "$OS" = Darwin ]; then
    link_run link02-force-load "$CC" "${CFLAGS[@]}" "${INC[@]}" "$HERE/link_app.c" \
        -Wl,-force_load,"$ARCHIVE" ${LIBS[@]+"${LIBS[@]}"}
    link_run link02-all-load "$CC" "${CFLAGS[@]}" "${INC[@]}" ${RUSTDEF[@]+"${RUSTDEF[@]}"} \
        "$HERE/link_app.c" -Wl,-all_load "$ARCHIVE" ${RUSTA[@]+"${RUSTA[@]}"} \
        ${LIBS[@]+"${LIBS[@]}"}
    # Extract exported symbols from the macOS SDK `libSystem` `.tbd` stubs.
    SDK="$(xcrun --show-sdk-path 2>/dev/null || true)"
    if [ -n "$SDK" ] && [ -f "$SDK/usr/lib/libSystem.B.tbd" ]; then
        grep -ohE '_[A-Za-z0-9_$.]+' "$SDK/usr/lib/libSystem.B.tbd" "$SDK"/usr/lib/system/*.tbd |
            sort -u >"$OUT/libsystem-names.txt"
        if ! grep -qx '_malloc' "$OUT/libsystem-names.txt" ||
            ! grep -qx '_pthread_create' "$OUT/libsystem-names.txt"; then
            fail "LINK-02 the libSystem name list is not complete"
        else
            COMMON="$(comm -12 "$OUT/core-globals.txt" "$OUT/libsystem-names.txt")"
            if [ -z "$COMMON" ]; then
                pass "LINK-02 no global of the core is a libSystem name"
            else
                fail "LINK-02 libSystem names: $(echo "$COMMON" | head -5 | tr '\n' ' ')"
            fi
        fi
    else
        skip "LINK-02 libSystem names (no SDK stubs)"
    fi
elif [ "$OS" = Linux ]; then
    link_run link02-whole-archive "$CC" "${CFLAGS[@]}" "${INC[@]}" "$HERE/link_app.c" \
        -Wl,--whole-archive "$ARCHIVE" -Wl,--no-whole-archive ${LIBS[@]+"${LIBS[@]}"}
    : >"$OUT/libc-names.txt"
    for so in libc.so.6 libpthread.so.0; do
        f="$("$CC" -print-file-name="$so")"
        [ -f "$f" ] && nm -D --defined-only "$f" | awk '{ sub(/@.*/, "", $NF); print $NF }' \
            >>"$OUT/libc-names.txt"
    done
    sort -u -o "$OUT/libc-names.txt" "$OUT/libc-names.txt"
    COMMON="$(comm -12 "$OUT/core-globals.txt" "$OUT/libc-names.txt")"
    if [ -z "$COMMON" ]; then
        pass "LINK-02 no global of the core is a libc or libpthread name"
    else
        fail "LINK-02 libc names: $(echo "$COMMON" | head -5 | tr '\n' ' ')"
    fi
else
    skip "LINK-02 (host is $OS)"
fi

# ---- Application-defined symbols matching legacy internals ----
# Links `own_symbols.c` (defining `hash`, `do_io`, etc.) alongside the archive
# and verifies that no symbol collision or unexpected dispatch occurs.
link_run link03-own-symbols "$CC" "${CFLAGS[@]}" "${INC[@]}" -DZKLINK_OWN "$HERE/link_app.c" \
    "$HERE/own_symbols.c" "$ARCHIVE" ${LIBS[@]+"${LIBS[@]}"}
OWN="$(sed -En 's/^(int|void|long|double) \*?([A-Za-z_]+)[ (].*/\2/p' "$HERE/own_symbols.c" |
    grep -v '^zklink_own_check$' | sort -u)"
for s in $OWN; do
    grep -Eqx "_?$s" "$OUT/core-globals.txt" && fail "LINK-03 the core defines $s"
done
if [ -n "$REF" ] && [ -f "$REF/libzookeeper_$FL.a" ]; then
    # DEV characterization: legacy archives export internal symbols and fail to link.
    if "$CC" "${CFLAGS[@]}" "${INC[@]}" -DZKLINK_OWN "$HERE/link_app.c" "$HERE/own_symbols.c" \
        "$REF/libzookeeper_$FL.a" -lm ${FLDEF[@]+-lpthread} -o "$OUT/link03-legacy" \
        >"$OUT/link03-legacy.log" 2>&1; then
        info "LINK-03 legacy: the link works"
    else
        info "LINK-03 legacy: the link fails, $(grep -ci 'duplicate symbol' \
            "$OUT/link03-legacy.log") duplicate symbols (DV-ABI-1)"
    fi
fi

# ---- CMake `libhashtable.a` stub and `add_subdirectory()` consumer ----
# `libhashtable.a` contains a single archive member with no global symbols so
# legacy CMake consumers linking `zookeeper hashtable` or `-lzookeeper -lhashtable`
# continue to build unchanged.
check_hashtable() {
    local a="$1" n g
    n="$(ar t "$a" | grep -cv '^__.SYMDEF')"
    g="$(globals "$a" | tr '\n' ' ')"
    if [ "$n" = 1 ] && [ -z "$g" ]; then
        pass "LINK-04 $a has one member and no global"
    else
        fail "LINK-04 $a has $n members, globals: $g"
    fi
}
# Links `link_app.c` using manual `-L<dir> -lzookeeper -lhashtable` flags and runs it.
hand_link() {
    local name="$1" dir="$2"
    shift 2
    link_run "$name" "$CC" "${CFLAGS[@]}" "$@" "$HERE/link_app.c" -L"$dir" -lzookeeper \
        -lhashtable ${LIBS[@]+"${LIBS[@]}"}
}
[ -n "$HASHTABLE" ] && check_hashtable "$HASHTABLE"
if [ -n "$HASHTABLE" ] && [ "$(basename "$ARCHIVE")" = libzookeeper.a ]; then
    hand_link link04-build-tree "$(dirname "$ARCHIVE")" "${INC[@]}" -L"$(dirname "$HASHTABLE")"
fi
if [ -n "$PREFIX" ]; then
    check_hashtable "$PREFIX/lib/libhashtable.a"
    hand_link link04-prefix "$PREFIX/lib" -I"$PREFIX/include/zookeeper"
fi
if [ "$CONSUMER" = 1 ]; then
    B="$OUT/cmake-consumer"
    SYNC=OFF
    [ "$FL" = mt ] && SYNC=ON
    ARGS=(-S "$HERE/cmake-consumer" -B "$B" -DZK_CLIENT_SOURCE_DIR="$RCDIR"
        "${CONFIG_ARGS[@]}" -DWANT_CPPUNIT=OFF "-DWANT_SYNCAPI=$SYNC"
        -DCMAKE_INSTALL_PREFIX="$B/inst" -DCMAKE_C_COMPILER="$CC"
        ${CMAKE_EXTRA[@]+"${CMAKE_EXTRA[@]}"})
    [ -n "$RUSTLIB" ] && ARGS+=(-DZKRS_STATICLIB="$RUSTLIB")
    if "$CMAKE" "${ARGS[@]}" >"$OUT/consumer.log" 2>&1 &&
        "$CMAKE" --build "$B" -j 4 >>"$OUT/consumer.log" 2>&1 &&
        "$CMAKE" --install "$B" >>"$OUT/consumer.log" 2>&1; then
        pass "LINK-04 the add_subdirectory() application configures and links"
        check link04-consumer "$B/zklink_consumer" ${HOST:+"$HOST"}
        # Verify the transitive CMake link interface of the `zookeeper` target.
        LINKTXT="$B/CMakeFiles/zklink_consumer.dir/link.txt"
        if [ -f "$LINKTXT" ]; then
            CMLIBS="$(tr -s ' ' <"$LINKTXT" | tr ' ' '\n' | grep -E '^-l|\.(a|dylib|so|tbd)$' |
                tr '\n' ' ')"
            has_native "the CMake link interface" "$CMLIBS"
        fi
        check_hashtable "$B/inst/lib/libhashtable.a"
        hand_link link04-consumer-prefix "$B/inst/lib" -I"$B/inst/include/zookeeper"
    else
        fail "LINK-04 the add_subdirectory() application (see $OUT/consumer.log)"
        tail -20 "$OUT/consumer.log"
    fi
fi

# ---- Libtool `.la` and pkg-config `.pc` link metadata ----
# Verifies that `.la` `dependency_libs` and `.pc` `Libs.private` satisfy
# `has_native` and suffice to statically link `link_app.c`.
if [ -n "$LA" ]; then
    # shellcheck disable=SC1090
    DEPS="$(. "$LA" && echo "${dependency_libs:-}" | tr -s ' ' | sed 's/^ //')"
    if has_native "$(basename "$LA") dependency_libs" "$DEPS"; then
        # shellcheck disable=SC2086
        link_run link05-la "$CC" "${CFLAGS[@]}" "${INC[@]}" "$HERE/link_app.c" "$ARCHIVE" $DEPS
    fi
else
    skip "LINK-05 .la (no --la)"
fi
if [ -z "$PC" ] && [ -n "$LA" ]; then
    PC="$(dirname "$LA")/pkgconfig/zookeeper_$FL.pc"
fi
if [ -n "$PC" ] && [ -f "$PC" ]; then
    PRIV="$(sed -n 's/^Libs.private:[[:space:]]*//p' "$PC")"
    if has_native "$(basename "$PC") Libs.private" "$PRIV" &&
        command -v pkg-config >/dev/null 2>&1; then
        # Because system linkers prefer shared libraries over static archives
        # when given `-lzookeeper_FL`, substitute the explicit path to
        # `libzookeeper_FL.a` in the `.pc` file's `libdir` (accounting for
        # `--destdir` staged installs) to force static linkage.
        PCDIR="$(PKG_CONFIG_PATH="$(dirname "$PC")" pkg-config --variable=libdir "zookeeper_$FL")"
        PCARCHIVE="$PCDIR/libzookeeper_$FL.a"
        PCOK=0
        case "$PCDIR" in
        /*)
            if [ -f "$PCARCHIVE" ] && [ "$PCARCHIVE" -ef "$ARCHIVE" ]; then
                PCOK=1
            elif [ -n "$DESTDIR" ] && [ -f "$DESTDIR$PCARCHIVE" ] &&
                [ "$DESTDIR$PCARCHIVE" -ef "$ARCHIVE" ]; then
                PCARCHIVE="$ARCHIVE"
                PCOK=1
            fi
            ;;
        esac
        PCLIBS=()
        for w in $(PKG_CONFIG_PATH="$(dirname "$PC")" \
            pkg-config --static --libs "zookeeper_$FL"); do
            if [ "$w" = "-lzookeeper_$FL" ]; then
                [ "$PCOK" = 1 ] || fail "LINK-05 .pc libdir '$PCDIR' does not hold $ARCHIVE"
                PCLIBS+=("$PCARCHIVE")
            else
                PCLIBS+=("$w")
            fi
        done
        # shellcheck disable=SC2046
        link_run link05-pc "$CC" "${CFLAGS[@]}" $(PKG_CONFIG_PATH="$(dirname "$PC")" \
            pkg-config --cflags "zookeeper_$FL") "$HERE/link_app.c" ${PCLIBS[@]+"${PCLIBS[@]}"}
    fi
elif [ -n "$LA" ] || [ -n "$PC" ]; then
    known "LINK-05 .pc: the build installs no zookeeper_$FL.pc"
fi

echo "run-link-tests.sh: $PASS passed, $FAIL failed, $SKIP skipped, $KNOWN known"
[ "$FAIL" = 0 ]
