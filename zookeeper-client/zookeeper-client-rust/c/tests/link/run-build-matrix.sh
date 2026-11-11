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

# Executes the full build configuration matrix for the drop-in client libraries
# across macOS and Linux: feature configurations (`plain`, `ssl`, `sasl`,
# `full`), library flavors (`mt`, `st`), and build systems (`dev/build-dropin.sh`,
# Autotools `--with-rust-client={yes,no}`, and CMake shared and static-only
# modes). Specifications: "Producing the libraries" and "Selecting what to
# build". Because `dev/build-dropin.sh` targets macOS, the `dev` stage is
# skipped on Linux where the `autotools` stage covers the equivalent artifacts.
#
# Usage: run-build-matrix.sh [options]
#   --stage S     stage to execute: dev, autotools, cmake, provider, paths,
#                 gating, or legacy (repeatable; default: all stages)
#   --config C    feature configuration: plain, ssl, sasl, or full (repeatable;
#                 default: all configurations)
#   --work DIR    working directory (default: /tmp/xbuild/matrix)
#   --ref DIR     legacy reference installation root (default: /tmp/zk-legacy-ref)
#   --jobs N      parallel build job count (default: 8)
#   --no-runtime  skip executing `tests/abi` and `tests/link` on built outputs
#
# Environment variables:
#   OPENSSL_PREFIX   OpenSSL installation prefix (default: Homebrew `openssl@3`
#                    on macOS, `/usr` on Linux)
#   SASL_PREFIX      Cyrus SASL installation prefix (default: Homebrew
#                    `cyrus-sasl` on macOS, `/usr` on Linux)
#   CARGO_TARGET_DIR Cargo target directory used by `dev` builds, `xtask` checks,
#                    and feature-gate tests. Autotools and CMake builds share one
#                    Cargo target directory per configuration so each feature set
#                    compiles the Rust workspace once per build system.
#   ZK_CPU_GATE      optional command executed prior to each timed build or test
#                    step to throttle concurrency on shared hosts
#
# This runner does not start or stop ZooKeeper servers. All build and test steps
# run under bounded timeouts. `KNOWN` lines record characterized upstream
# limitations without incrementing the failure counter.

set -uo pipefail

# Resolve repository paths: RCDIR is the Rust C compatibility root, RDIR is the
# Rust workspace root, and CDIR is the legacy C client tree. `ZKRS_C_DIR` and
# `ZK_C_DIR` allow running from an out-of-tree copy of this script.
HERE="$(cd "$(dirname "$0")" && pwd)"
RCDIR="$(cd "${ZKRS_C_DIR:-$HERE/../..}" && pwd)"
RDIR="$(cd "$RCDIR/.." && pwd)"
CDIR="$(cd "${ZK_C_DIR:-$RDIR/../zookeeper-client-c}" && pwd)"
ABI="$RCDIR/abi"

STAGES=()
CONFIGS=()
WORK=/tmp/xbuild/matrix
REF=/tmp/zk-legacy-ref
JOBS=8
RUNTIME=1
while [ $# -gt 0 ]; do
    case "$1" in
    --stage) STAGES+=("$2"); shift 2 ;;
    --config) CONFIGS+=("$2"); shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --ref) REF="$2"; shift 2 ;;
    --jobs) JOBS="$2"; shift 2 ;;
    --no-runtime) RUNTIME=0; shift ;;
    *) echo "run-build-matrix.sh: unknown option $1" >&2; exit 2 ;;
    esac
done
[ "${#STAGES[@]}" -gt 0 ] || STAGES=(dev autotools cmake provider paths gating legacy)
[ "${#CONFIGS[@]}" -gt 0 ] || CONFIGS=(plain ssl sasl full)
for c in "${CONFIGS[@]}"; do
    case "$c" in
    plain | ssl | sasl | full) ;;
    *) echo "run-build-matrix.sh: unknown config $c" >&2; exit 2 ;;
    esac
done
# Platform abstraction layer (`MAC=1` on macOS, `MAC=0` on Linux), normalizing
# Mach-O vs. ELF shared library extensions, symbol prefixes, export tables, and
# dynamic dependency inspection.
case "$(uname -s)" in
Darwin) MAC=1 ;;
Linux) MAC=0 ;;
*)
    echo "run-build-matrix.sh: this runner supports macOS and Linux only" >&2
    exit 2
    ;;
esac
if [ "$MAC" = 1 ]; then
    OPENSSL_PREFIX="${OPENSSL_PREFIX:-/opt/homebrew/opt/openssl@3}"
    SASL_PREFIX="${SASL_PREFIX:-/opt/homebrew/opt/cyrus-sasl}"
    # Mach-O C symbols carry a leading underscore prefix.
    SYMP=_
    # CMake flag to prevent `find_path` from discovering Homebrew OpenSSL headers.
    CM_HIDE_OPENSSL=-DCMAKE_IGNORE_PREFIX_PATH=/opt/homebrew
    # macOS system SDK search paths do not include OpenSSL headers.
    SYS_OPENSSL=no
else
    OPENSSL_PREFIX="${OPENSSL_PREFIX:-/usr}"
    SASL_PREFIX="${SASL_PREFIX:-/usr}"
    SYMP=
    CM_HIDE_OPENSSL=-DCMAKE_IGNORE_PATH=/usr/include
    if [ -f /usr/include/openssl/ssl.h ]; then SYS_OPENSSL=yes; else SYS_OPENSSL=no; fi
fi
XT="${CARGO_TARGET_DIR:-$RDIR/target}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
LOGS="$WORK/logs"
mkdir -p "$LOGS"
RESULTS="$WORK/results.txt"
: >"$RESULTS"
# All C translation units must compile without warnings. Ignore harmless macOS
# linker deployment-target warnings emitted when linking against Homebrew dylibs
# (`libsasl2`, or OpenSSL in legacy builds) built for a newer macOS release.
WARN_ALLOW='ld: warning: .*linking with dylib .*/opt/homebrew/'
WARN_ALLOW="${WARN_ALLOW}.*was built for newer (macOS )?version"
# Per-step execution timeouts in seconds.
T_BUILD=2400
T_TEST=1800
T_CONF=600

PASS=0
FAIL=0
SKIP=0
KNOWN=0
pass() { PASS=$((PASS + 1)); echo "PASS $*" | tee -a "$RESULTS"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL $*" | tee -a "$RESULTS"; }
skip() { SKIP=$((SKIP + 1)); echo "SKIP $*" | tee -a "$RESULTS"; }
known() { KNOWN=$((KNOWN + 1)); echo "KNOWN $*" | tee -a "$RESULTS"; }
info() { echo "INFO $*" | tee -a "$RESULTS"; }

has_stage() {
    local s
    for s in "${STAGES[@]}"; do [ "$s" = "$1" ] && return 0; done
    return 1
}
has_config() {
    local c
    for c in "${CONFIGS[@]}"; do [ "$c" = "$1" ] && return 0; done
    return 1
}

# Executes a command under a timeout, redirecting stdout and stderr to `log`.
# Usage: run SECS LOG CMD...
run() {
    local secs="$1" log="$2"
    shift 2
    if [ -n "${ZK_CPU_GATE:-}" ]; then
        until "$ZK_CPU_GATE" >&2; do :; done
    fi
    timeout "$secs" "$@" >"$log" 2>&1
}

# Executes a test command under `T_TEST`, recording a pass on exit status 0.
check() {
    local name="$1" log="$LOGS/$1.log"
    shift
    if run "$T_TEST" "$log" "$@"; then
        pass "$name"
    else
        fail "$name (see $log)"
        tail -15 "$log"
    fi
}

# Verifies that a command exits with a non-zero status and emits output matching
# `pattern` (confirming that unsatisfiable build options fail fast with a clear
# diagnostic; spec "Selecting what to build").
expect_fail() {
    local name="$1" pattern="$2" log="$LOGS/$1.log"
    shift 2
    if run "$T_CONF" "$log" "$@"; then
        fail "$name: the command succeeds (see $log)"
    elif grep -Eq "$pattern" "$log"; then
        pass "$name: $(grep -Eo "$pattern" "$log" | head -1)"
    else
        fail "$name: no '$pattern' in the output (see $log)"
        tail -10 "$log"
    fi
}

# Sets `SSL01` and `SASL01` boolean flags for the given configuration name.
bits() {
    case "$1" in
    plain) SSL01=0 SASL01=0 ;;
    ssl) SSL01=1 SASL01=0 ;;
    sasl) SSL01=0 SASL01=1 ;;
    full) SSL01=1 SASL01=1 ;;
    esac
}

# Emits the sorted golden export symbol list for `flavor` and `config` (with
# leading underscore on Mach-O; `abi/symbols.tsv`: plain 201/178, ssl 202/179,
# sasl 210/187, full 211/188).
golden() {
    local fmt=sym
    [ "$MAC" = 1 ] && fmt=exp
    bits "$2"
    awk -f "$ABI/gen-exports.awk" -v flavor="$1" -v fmt="$fmt" -v ssl="$SSL01" -v sasl="$SASL01" \
        "$ABI/symbols.tsv" | sort -u
}

# Prints the platform-specific shared library filename for `flavor`.
shlib() {
    if [ "$MAC" = 1 ]; then echo "libzookeeper_$1.2.dylib"; else echo "libzookeeper_$1.so.2"; fi
}

# Lists defined dynamic exports of a shared library, stripping ELF version suffixes.
exports() {
    if [ "$MAC" = 1 ]; then
        nm -gU "$1" | awk '{ print $NF }' | sort -u
    else
        nm -D --defined-only "$1" | awk 'NF >= 3 && $2 ~ /^[A-Za-z]$/ && $2 !~ /^[AUw]$/ {
            sub(/@.*/, "", $3); print $3 }' | sort -u
    fi
}

# Note: pipeline checks below use `grep -c ... >/dev/null` rather than `grep -q`
# so early termination does not trigger `SIGPIPE` failures under `set -o pipefail`.

# Checks whether `nm` output for `file` matches `pattern`. Because Apple `nm`
# returns a non-zero exit code when encountering LLVM bitcode archive members
# even after printing object symbols, `nm` output is buffered to a file first.
nm_has() {
    nm "$1" >"$WORK/nm.out" 2>/dev/null
    grep -q "$2" "$WORK/nm.out"
}

# Lists defined global symbols of a static archive.
ar_globals() {
    nm -g "$1" 2>/dev/null | awk 'NF >= 3 && $(NF - 1) ~ /^[A-TV-Z]$/ { print $NF }' | sort -u
}

# Lists non-system dynamic library dependencies of a Mach-O or ELF binary
# (excluding `libSystem` on macOS and `libc.so`/`ld-linux` on Linux).
lib_deps() {
    if [ "$MAC" = 1 ]; then
        otool -L "$1" | awk 'NR > 1 { print $1 }' | grep -v '^/usr/lib/libSystem' |
            sed 's|.*/||' | sort
    else
        readelf -dW "$1" | sed -n 's/.*(NEEDED).*\[\(.*\)\]$/\1/p' |
            grep -Ev '^(libc\.so|ld-linux)' | sort
    fi
}

# Upstream `src/zk_sasl.c` emits `-Wgnu-folding-constant` under Apple clang 21
# in `-std=gnu23` mode; because upstream C sources are unmodified in legacy
# builds, classify this diagnostic as `KNOWN`.
LEGACY_WARN='src/zk_sasl\.c:[0-9]+:[0-9]+: warning: .*\[-Wgnu-folding-constant\]'

# Verifies that a build log contains no unexpected compiler or linker warnings.
warn_check() {
    local name="$1" log="$2" kw="${3:-}" w
    w="$(grep -E 'warning:' "$log" | grep -Ev "$WARN_ALLOW" || true)"
    if [ -n "$w" ] && [ -n "$kw" ] && [ -z "$(echo "$w" | grep -Ev "$kw" || true)" ]; then
        known "$name: the known legacy warning: $(echo "$w" | head -1)"
    elif [ -z "$w" ]; then
        pass "$name: no compiler warning"
    else
        fail "$name: warnings: $(echo "$w" | head -3)"
    fi
}

# Validates shared library exports and dynamic dependencies against the golden
# specification for `flavor` and `config`. Rust drop-in builds never link
# `libssl` or `libcrypto` and link `libsasl2` iff SASL is enabled (spec line
# 1081); legacy C builds link OpenSSL iff TLS is enabled.
shlib_facts() {
    local name="$1" lib="$2" fl="$3" config="$4" core="${5:-rust}" deps n
    if [ ! -f "$lib" ]; then
        fail "$name: no $lib"
        return
    fi
    golden "$fl" "$config" >"$LOGS/$name.golden"
    exports "$lib" >"$LOGS/$name.exports"
    n="$(wc -l <"$LOGS/$name.exports" | tr -d ' ')"
    if cmp -s "$LOGS/$name.golden" "$LOGS/$name.exports"; then
        pass "$name: $n exports, the $config $fl golden set"
    else
        fail "$name: the exports differ from the $config $fl golden set ($n exports)"
        diff "$LOGS/$name.golden" "$LOGS/$name.exports" | head -6
    fi
    # Confirm that weak `OPENSSL_memory_*` allocator hooks in `aws-lc` remain
    # bound to zero during prelinking and never leak into the dynamic symbol table.
    if [ "$MAC" = 0 ] && [ "$core" = rust ]; then
        if nm -D "$lib" | grep 'OPENSSL_memory_' >"$LOGS/$name.hooks"; then
            fail "$name: dynamic OPENSSL_memory_* symbols: $(tr '\n' ' ' <"$LOGS/$name.hooks")"
        else
            pass "$name: no dynamic OPENSSL_memory_* symbols"
        fi
    fi
    bits "$config"
    deps="$(lib_deps "$lib" | tr '\n' ' ')"
    local has_ssl=0 has_sasl=0 want_ssl=0
    case " $deps" in *" libssl"* | *" libcrypto"*) has_ssl=1 ;; esac
    case " $deps" in *" libsasl2"*) has_sasl=1 ;; esac
    [ "$core" = legacy ] && want_ssl="$SSL01"
    if [ "$has_ssl" = "$want_ssl" ] && [ "$has_sasl" = "$SASL01" ]; then
        pass "$name: dependencies: ${deps:-system only}"
    else
        fail "$name: dependencies: '$deps' (want OpenSSL=$want_ssl libsasl2=$SASL01)"
    fi
}

# Verifies that a static archive contains exactly one prelinked object member.
one_member() {
    local name="$1" a="$2" n
    n="$(ar t "$a" 2>/dev/null | grep -cv '^__.SYMDEF')"
    if [ "$n" = 1 ]; then
        pass "$name: one member"
    else
        fail "$name: $n members"
    fi
}

xtask_check() {
    local name="$1" lib="$2" fl="$3" config="$4"
    shift 4
    check "$name" sh -c "cd \"$RDIR\" && CARGO_TARGET_DIR=\"$XT\" cargo xtask abi check \"$lib\" \
        --flavor $fl --config $config $*"
}
xtask_static() {
    local name="$1" a="$2" fl="$3" config="$4"
    check "$name" sh -c "cd \"$RDIR\" && CARGO_TARGET_DIR=\"$XT\" cargo xtask abi static \"$a\" \
        --flavor $fl --config $config"
}

# Query native static library flags from `rustc` (matching `m4/zk_rust.m4` and
# `cmake/ZkRust.cmake`).
echo 'pub fn f() {}' >"$WORK/native.rs"
NATIVE="$(rustc --crate-type staticlib --print native-static-libs "$WORK/native.rs" \
    -o "$WORK/native.a" 2>&1 | sed -n 's/.*native-static-libs: //p' | tr ' ' '\n' |
    grep -Ev '^(-lc|-lSystem)$' | awk 'NF && !seen[$0]++' | tr '\n' ' ')"
info "native libraries of a Rust staticlib: ${NATIVE:-none}"

# Validates link metadata (`dependency_libs`, `Libs.private`, and CMake link
# interfaces; spec line 1087): every native Rust dependency must be present,
# `-lsasl2` must appear iff SASL is enabled, and no OpenSSL library may appear.
libs_rule() {
    local name="$1" config="$2" libs="$3" l ok=1 has_sasl=0
    local ossl_re='^-l(ssl|crypto)$|/lib(ssl|crypto)([.0-9]*\.(a|dylib|tbd)|\.so[.0-9]*)$'
    local sasl_re='^-lsasl2$|/libsasl2([.0-9]*\.(dylib|tbd|a)|\.so[.0-9]*)$'
    bits "$config"
    for l in $NATIVE; do
        case " $libs " in *" $l "*) continue ;; esac
        local lib_re="/lib${l#-l}((\.[0-9]+)*\.(a|dylib|tbd)|\.so(\.[0-9]+)*)$"
        if printf '%s\n' $libs | grep -Eq "$lib_re"; then
            continue
        fi
        fail "$name: no $l in '$libs'"
        ok=0
    done
    if printf '%s\n' $libs | grep -Eq "$ossl_re"; then
        fail "$name: an OpenSSL library in '$libs'"
        ok=0
    fi
    if printf '%s\n' $libs | grep -Eq "$sasl_re"; then
        has_sasl=1
    fi
    if [ "$has_sasl" != "$SASL01" ]; then
        fail "$name: libsasl2=$has_sasl in '$libs', want $SASL01"
        ok=0
    fi
    [ "$ok" = 1 ] && pass "$name: '$libs'"
}

# Verifies that the installed header directory contains the exact six public
# headers byte-identical to the source tree (`zookeeper.h`, `zookeeper_version.h`,
# `zookeeper_log.h`, `proto.h`, `recordio.h`, and `zookeeper.jute.h`).
headers_check() {
    local name="$1" dir="$2" h src bad=""
    local want="proto.h recordio.h zookeeper.h zookeeper.jute.h zookeeper_log.h"
    want="$want zookeeper_version.h "
    if [ "$(ls "$dir" 2>/dev/null | sort | tr '\n' ' ')" != "$want" ]; then
        fail "$name: the header set is '$(ls "$dir" 2>/dev/null | tr '\n' ' ')'"
        return
    fi
    for h in zookeeper.h zookeeper_version.h zookeeper_log.h proto.h recordio.h \
        zookeeper.jute.h; do
        src="$CDIR/include/$h"
        [ "$h" = zookeeper.jute.h ] && src="$CDIR/generated/$h"
        cmp -s "$src" "$dir/$h" || bad="$bad $h"
    done
    if [ -z "$bad" ]; then
        pass "$name: the six headers equal the sources"
    else
        fail "$name: headers differ:$bad"
    fi
}

# Verifies that configured default CA trust store paths are embedded in `lib`.
paths_in_lib() {
    local name="$1" lib="$2" file="$3" dir="$4"
    if [ -z "$file$dir" ]; then
        skip "$name: no default trust path recorded"
        return
    fi
    # Buffer `strings` output to a file to avoid `SIGPIPE` under `set -o pipefail`.
    strings -a "$lib" >"$LOGS/$name.strings" 2>/dev/null
    if grep -qF "$file" "$LOGS/$name.strings" && grep -qF "$dir" "$LOGS/$name.strings"; then
        pass "$name: the library has $file and $dir"
    else
        fail "$name: the library lacks $file or $dir"
    fi
}

###########################################################################
# Stage `dev`: `dev/build-dropin.sh` across all configurations and flavors
# with `ABI_CHECK=1`, followed by `tests/abi` and `tests/link`.
###########################################################################
stage_dev() {
    local config out fl
    if [ "$MAC" = 0 ]; then
        skip "dev: dev/build-dropin.sh is a macOS tool; autotools builds the Linux libraries"
        return
    fi
    for config in "${CONFIGS[@]}"; do
        out="$WORK/dev-$config"
        if run "$T_BUILD" "$LOGS/dev-$config.log" env CONFIG="$config" ABI_CHECK=1 \
            CARGO_TARGET_DIR="$XT" OPENSSL_PREFIX="$OPENSSL_PREFIX" SASL_PREFIX="$SASL_PREFIX" \
            "$RDIR/dev/build-dropin.sh" "$CDIR/.." "$out"; then
            pass "dev-$config: build-dropin.sh with ABI_CHECK=1"
        else
            fail "dev-$config: build-dropin.sh (see $LOGS/dev-$config.log)"
            tail -15 "$LOGS/dev-$config.log"
            continue
        fi
        warn_check "dev-$config" "$LOGS/dev-$config.log"
        for fl in mt st; do
            shlib_facts "dev-$config-$fl" "$out/$(shlib "$fl")" "$fl" "$config"
            one_member "dev-$config-$fl-archive" "$out/libzookeeper_$fl.a"
            if [ "$RUNTIME" = 1 ]; then
                check "dev-$config-$fl-abi-tests" "$RCDIR/tests/abi/run-abi-tests.sh" \
                    --flavor "$fl" --config "$config" --lib "$out" --no-xtask \
                    --dead-host 127.0.0.1:23559 --out "$WORK/dev-$config/abi-tests-$fl"
                check "dev-$config-$fl-link-tests" env LIBRARY_PATH="$SASL_PREFIX/lib" \
                    "$RCDIR/tests/link/run-link-tests.sh" --flavor "$fl" --config "$config" \
                    --archive "$out/libzookeeper_$fl.a" --no-xtask \
                    --out "$WORK/dev-$config/link-tests-$fl"
            fi
        done
    done
}

###########################################################################
# Stage `autotools`: out-of-tree Autotools builds from a staged copy of CDIR.
###########################################################################
AT_SRC="$WORK/src/zookeeper-client-rust/c"
LEG_SRC="$WORK/src/zookeeper-client-c"
if [ "$MAC" = 1 ]; then
    LIBTOOLIZE_CMD=glibtoolize
    ACLOCAL_DIRS="$REF/m4:/opt/homebrew/share/aclocal"
else
    LIBTOOLIZE_CMD=libtoolize
    ACLOCAL_DIRS="$REF/m4"
fi
# The copy of the drop-in project excludes the outputs of an in-tree
# configure, because configure stops when the source directory has them.
AT_EXCLUDES=(--exclude .deps --exclude .libs --exclude /autom4te.cache --exclude /config.status
    --exclude /config.log --exclude /config.h --exclude /stamp-h1 --exclude /libtool
    --exclude /Makefile --exclude /zookeeper_st.pc --exclude /zookeeper_mt.pc)
src_copied=0
src_copy() {
    [ "$src_copied" = 1 ] && return 0
    local e
    # An older runner made zookeeper-client-rust a link to RDIR. A copy
    # through that link writes into RDIR, so the runner removes the link.
    if [ -L "$WORK/src/zookeeper-client-rust" ]; then
        rm -f "$WORK/src/zookeeper-client-rust" || return 1
    fi
    mkdir -p "$LEG_SRC" "$AT_SRC" || return 1
    rsync -a --delete --exclude .deps --exclude target-dropin "$CDIR/" "$LEG_SRC/" || return 1
    rsync -a --delete "${AT_EXCLUDES[@]}" "$RCDIR/" "$AT_SRC/" || return 1
    for e in "$RDIR"/* "$RDIR"/.cargo; do
        [ -e "$e" ] || continue
        case "$(basename "$e")" in c | target) continue ;; esac
        ln -sfn "$e" "$WORK/src/zookeeper-client-rust/$(basename "$e")" || return 1
    done
    src_copied=1
}
at_prepared=0
at_prepare() {
    [ "$at_prepared" = 1 ] && return 0
    src_copy && (cd "$AT_SRC" && LIBTOOLIZE="$LIBTOOLIZE_CMD" ACLOCAL_PATH="$ACLOCAL_DIRS" \
        run "$T_CONF" "$LOGS/autoreconf.log" autoreconf -fi) || {
        fail "autoreconf -fi of the drop-in project (see $LOGS/autoreconf.log)"
        return 1
    }
    pass "autoreconf -fi of the drop-in project $AT_SRC"
    at_prepared=1
}
leg_prepared=0
leg_prepare() {
    [ "$leg_prepared" = 1 ] && return 0
    src_copy && (cd "$LEG_SRC" && LIBTOOLIZE="$LIBTOOLIZE_CMD" ACLOCAL_PATH="$ACLOCAL_DIRS" \
        run "$T_CONF" "$LOGS/autoreconf-legacy.log" autoreconf -fi) || {
        fail "autoreconf -fi of the legacy project (see $LOGS/autoreconf-legacy.log)"
        return 1
    }
    pass "autoreconf -fi of the legacy project $LEG_SRC"
    leg_prepared=1
}

# Maps a configuration name to Autotools `--with-openssl` and `--with-sasl` flags.
at_args() {
    case "$1" in
    plain) echo --without-openssl --without-sasl ;;
    ssl) echo "--with-openssl=$OPENSSL_PREFIX" --without-sasl ;;
    sasl) echo --without-openssl "--with-sasl=$SASL_PREFIX" ;;
    full) echo "--with-openssl=$OPENSSL_PREFIX" "--with-sasl=$SASL_PREFIX" ;;
    default) ;;
    esac
}

# Configures, compiles, and installs an Autotools build in `$WORK/at-ID`, sharing
# the Cargo target directory `$WORK/cargo-at-CARGOKEY` across Rust builds.
# Usage: at_build ID MODE CARGOKEY LEGACY_SASL CONFIGURE-ARGS...
at_build() {
    local id="$1" mode="$2" key="$3" lsasl="$4" b="$WORK/at-$1" conf
    shift 4
    local opts=()
    if [ "$mode" = no ]; then
        leg_prepare || return 1
        conf="$LEG_SRC/configure"
    else
        at_prepare || return 1
        conf="$AT_SRC/configure"
        opts=(--with-rust-workspace="$RDIR")
    fi
    rm -rf "$b"
    mkdir -p "$b"
    if [ "$mode" != no ]; then
        mkdir -p "$WORK/cargo-at-$key"
        ln -s "$WORK/cargo-at-$key" "$b/cargo"
    fi
    local cfl=()
    if [ "$lsasl" = 1 ] && [ "$MAC" = 1 ]; then
        cfl=("CFLAGS=-g -O2 -D_GNU_SOURCE -Wno-gnu-folding-constant")
    fi
    if ! (cd "$b" && PKG_CONFIG_PATH=/opt/homebrew/opt/cppunit/lib/pkgconfig run "$T_CONF" \
        "$LOGS/at-$id-configure.log" "$conf" --prefix="$b/inst" \
        ${opts[@]+"${opts[@]}"} "$@" ${cfl[@]+"${cfl[@]}"}); then
        fail "at-$id: configure (see $LOGS/at-$id-configure.log)"
        tail -5 "$LOGS/at-$id-configure.log"
        return 1
    fi
    if ! (cd "$b" && run "$T_BUILD" "$LOGS/at-$id-make.log" make -j"$JOBS" &&
        run "$T_CONF" "$LOGS/at-$id-install.log" make install); then
        fail "at-$id: make and make install (see $LOGS/at-$id-make.log)"
        grep -E 'error|Error' "$LOGS/at-$id-make.log" | head -8
        return 1
    fi
    pass "at-$id: configure, make, make install"
    warn_check "at-$id" "$LOGS/at-$id-make.log"
}

# Extracts a variable assignment from a configured Autotools `Makefile`.
mkvar() {
    sed -n "s|^$2 = ||p" "$WORK/at-$1/Makefile" | head -1
}

# Verifies a Rust-backed Autotools installation (`$WORK/at-ID`).
at_rust_checks() {
    local id="$1" config="$2" b="$WORK/at-$1" lib fl deps
    lib="$b/inst/lib"
    if [ "$(mkvar "$id" ZKRS_ABI_CONFIG)" = "$config" ]; then
        pass "at-$id: ZKRS_ABI_CONFIG=$config"
    else
        fail "at-$id: ZKRS_ABI_CONFIG=$(mkvar "$id" ZKRS_ABI_CONFIG), want $config"
    fi
    check "at-$id-abi-check-static" make -C "$b" abi-check abi-static
    headers_check "at-$id-headers" "$b/inst/include/zookeeper"
    for fl in mt st; do
        shlib_facts "at-$id-$fl" "$lib/$(shlib "$fl")" "$fl" "$config"
        one_member "at-$id-$fl-archive" "$lib/libzookeeper_$fl.a"
        # shellcheck disable=SC1090
        deps="$(. "$lib/libzookeeper_$fl.la" && echo "${dependency_libs:-}")"
        libs_rule "at-$id-$fl.la dependency_libs" "$config" "$deps"
        bits "$config"
        if [ "$SSL01" = 1 ]; then
            paths_in_lib "at-$id-$fl-trust-paths" "$lib/$(shlib "$fl")" \
                "$(mkvar "$id" ZK_TLS_DEFAULT_CERT_FILE)" \
                "$(mkvar "$id" ZK_TLS_DEFAULT_CERT_DIR)"
        fi
    done
    for fl in mt st; do
        if [ -f "$lib/pkgconfig/zookeeper_$fl.pc" ]; then
            libs_rule "at-$id-$fl.pc Libs.private" "$config" \
                "$(sed -n 's/^Libs.private:[[:space:]]*//p' "$lib/pkgconfig/zookeeper_$fl.pc")"
        else
            fail "at-$id: no zookeeper_$fl.pc"
        fi
        # Rust-backed CLI binaries must not link against OpenSSL.
        if lib_deps "$b/inst/bin/cli_$fl" | grep -Ec '^lib(ssl|crypto)' >/dev/null; then
            fail "at-$id: cli_$fl links OpenSSL"
        else
            pass "at-$id: cli_$fl: $(lib_deps "$b/inst/bin/cli_$fl" | tr '\n' ' ')"
        fi
    done
    if [ "$RUNTIME" = 1 ]; then
        check "at-$id-check-abi-link" make -C "$b" check-abi check-link \
            ZK_ABI_TEST_DEAD_HOST=127.0.0.1:23559
    fi
}

# Verifies that an Autotools `--with-rust-client=no` build produces artifacts
# identical to the prebuilt legacy reference installation (dynamic exports,
# archive globals, shared library dependencies, and headers; spec "Selecting
# what to build").
legacy_compare() {
    local id="$1" config="$2" b="$WORK/at-$1" r="$REF/install-$2" lib="$WORK/at-$1/inst/lib" fl
    if [ ! -d "$r" ]; then
        skip "at-$id: no reference $r"
        return
    fi
    for fl in mt st; do
        if cmp -s <(exports "$r/lib/$(shlib "$fl")") <(exports "$lib/$(shlib "$fl")"); then
            pass "at-$id-$fl: the legacy exports equal the reference" \
                "($(exports "$lib/$(shlib "$fl")" | wc -l | tr -d ' '))"
        else
            fail "at-$id-$fl: the legacy exports differ from the reference"
        fi
        if cmp -s <(ar_globals "$r/lib/libzookeeper_$fl.a") \
            <(ar_globals "$lib/libzookeeper_$fl.a"); then
            pass "at-$id-$fl: the legacy archive globals equal the reference"
        else
            fail "at-$id-$fl: the legacy archive globals differ from the reference"
        fi
        if [ "$(lib_deps "$r/lib/$(shlib "$fl")" | grep -v "libzookeeper_$fl")" = \
            "$(lib_deps "$lib/$(shlib "$fl")" | grep -v "libzookeeper_$fl")" ]; then
            pass "at-$id-$fl: the legacy dependencies equal the reference"
        else
            fail "at-$id-$fl: legacy dependencies differ:" \
                "$(lib_deps "$lib/$(shlib "$fl")" | tr '\n' ' ')"
        fi
    done
    if diff -r "$r/include" "$b/inst/include" >"$LOGS/at-$id-hdr.diff" 2>&1; then
        pass "at-$id: the installed headers equal the reference"
    else
        fail "at-$id: the installed headers differ (see $LOGS/at-$id-hdr.diff)"
    fi
}

at_one() {
    local mode="$1" config="$2" id="$1-$2" lsasl=0
    bits "$config"
    [ "$mode" = no ] && [ "$SASL01" = 1 ] && lsasl=1
    # shellcheck disable=SC2046
    at_build "$id" "$mode" "$config" "$lsasl" $(at_args "$config") || return
    case "$mode" in
    yes) at_rust_checks "$id" "$config" ;;
    no)
        legacy_compare "$id" "$config"
        if [ "$(ls "$WORK/at-$id/inst/lib" | tr '\n' ' ')" = \
            "$(ls "$REF/install-$config/lib" 2>/dev/null | tr '\n' ' ')" ]; then
            pass "at-$id: the installed library files equal the reference"
        else
            fail "at-$id: the library files are '$(ls "$WORK/at-$id/inst/lib" | tr '\n' ' ')'"
        fi
        ;;
    esac
}

stage_autotools() {
    local config
    for config in "${CONFIGS[@]}"; do
        at_one yes "$config"
    done
    if at_prepare; then
        if "$AT_SRC/configure" --help >"$LOGS/at-help.log" 2>&1 &&
            ! grep -c 'with-rust-client' "$LOGS/at-help.log" >/dev/null; then
            pass "at-no-client-option: the drop-in configure has no --with-rust-client"
        else
            fail "at-no-client-option: configure --help fails or lists --with-rust-client"
        fi
    fi
}

# The legacy project builds with no Rust option and equals the reference.
# Then the public-API suite of the drop-in tree of the same configuration
# runs against the legacy install. This gives the legacy baseline of the
# suite. The suite starts a server, so it runs only when ZKTEST_PORT is
# set. The suite binary is built with that port. ZKTEST_REPO names a
# source tree with a built server (see tests/public/run-public.sh).
stage_legacy() {
    local config fl b
    for config in "${CONFIGS[@]}"; do
        at_one no "$config"
    done
    for config in "${CONFIGS[@]}"; do
        if [ -z "${ZKTEST_PORT:-}" ]; then
            skip "legacy-$config-public: no ZKTEST_PORT; the public suite needs a server port"
            continue
        fi
        b="$WORK/at-yes-$config"
        if [ ! -f "$WORK/at-no-$config/inst/lib/$(shlib mt)" ] || [ ! -f "$b/Makefile" ]; then
            skip "legacy-$config-public: run the autotools and legacy stages of $config first"
            continue
        fi
        if [ -z "$(mkvar "yes-$config" CPPUNIT_LIBS)" ]; then
            skip "legacy-$config-public: the drop-in configure found no CppUnit"
            continue
        fi
        if ! run "$T_BUILD" "$LOGS/legacy-$config-public-build.log" make -C "$b" -j"$JOBS" \
            ZKTEST_PORT="$ZKTEST_PORT" zktest-public-mt zktest-public-st; then
            fail "legacy-$config-public: the suite build (see $LOGS/legacy-$config-public-build.log)"
            continue
        fi
        for fl in mt st; do
            check "legacy-$config-public-$fl" env ZKTEST_PORT="$ZKTEST_PORT" \
                "$RCDIR/tests/public/run-public.sh" --libdir "$WORK/at-no-$config/inst/lib" \
                --workdir "$WORK/legacy-$config-public-$fl" "$b/.libs/zktest-public-$fl"
        done
    done
}

###########################################################################
# Stage `cmake`: shared and static-only CMake builds across all configurations
# and flavors, running `ctest -L 'abi|link'`.
###########################################################################
cm_args() {
    case "$1" in
    plain) echo -DWITH_OPENSSL=OFF -DWITH_CYRUS_SASL=OFF ;;
    ssl) echo "-DWITH_OPENSSL=$OPENSSL_PREFIX" -DWITH_CYRUS_SASL=OFF ;;
    sasl) echo -DWITH_OPENSSL=OFF "-DWITH_CYRUS_SASL=$SASL_PREFIX" ;;
    full) echo "-DWITH_OPENSSL=$OPENSSL_PREFIX" "-DWITH_CYRUS_SASL=$SASL_PREFIX" ;;
    default) ;;
    esac
}

# Configures, builds, and installs a CMake tree in `$WORK/cm-ID` sharing the
# Cargo target directory `$WORK/cargo-cm-CARGOKEY`.
# Usage: cm_build ID CARGOKEY CMAKE-ARGS...
cm_build() {
    local id="$1" key="$2" b="$WORK/cm-$1" src="$RCDIR"
    shift 2
    [ "$key" = legacy ] && src="$CDIR"
    rm -rf "$b"
    mkdir -p "$b" "$WORK/cargo-cm-$key"
    ln -s "$WORK/cargo-cm-$key" "$b/cargo"
    if ! run "$T_CONF" "$LOGS/cm-$id-configure.log" cmake -S "$src" -B "$b" -DWANT_CPPUNIT=OFF \
        -DCMAKE_INSTALL_PREFIX="$b/inst" -DZK_ABI_TEST_DEAD_HOST=127.0.0.1:23559 "$@"; then
        fail "cm-$id: configure (see $LOGS/cm-$id-configure.log)"
        grep -A3 'CMake Error' "$LOGS/cm-$id-configure.log" | head -8
        return 1
    fi
    if ! run "$T_BUILD" "$LOGS/cm-$id-build.log" cmake --build "$b" -j "$JOBS" ||
        ! run "$T_CONF" "$LOGS/cm-$id-install.log" cmake --install "$b"; then
        fail "cm-$id: build and install (see $LOGS/cm-$id-build.log)"
        grep -E 'error|Error' "$LOGS/cm-$id-build.log" | head -8
        return 1
    fi
    pass "cm-$id: configure, build, install"
    if [ "$key" = legacy ]; then
        warn_check "cm-$id" "$LOGS/cm-$id-build.log" "$LEGACY_WARN"
    else
        warn_check "cm-$id" "$LOGS/cm-$id-build.log"
    fi
}

# Extracts the TLS/SASL feature summary line from the CMake configure log.
cm_decision() {
    sed -n 's/.*The TLS and SASL build of the Rust client: //p' \
        "$LOGS/cm-$1-configure.log" | head -1
}

cm_checks() {
    local id="$1" config="$2" fl="$3" shared="$4" b="$WORK/cm-$1" want lt pc
    bits "$config"
    want="tls=$([ "$SSL01" = 1 ] && echo ON || echo OFF) provider=${5:-ring}"
    want="$want sasl=$([ "$SASL01" = 1 ] && echo ON || echo OFF)"
    if [ "$(cm_decision "$id")" = "$want" ]; then
        pass "cm-$id: $want"
    else
        fail "cm-$id: '$(cm_decision "$id")', want '$want'"
    fi
    one_member "cm-$id-archive" "$b/libzookeeper.a"
    headers_check "cm-$id-headers" "$b/inst/include/zookeeper"
    check "cm-$id-abi-static" cmake --build "$b" --target abi-static
    if [ "$shared" = ON ]; then
        shlib_facts "cm-$id" "$b/$(shlib "$fl")" "$fl" "$config"
        check "cm-$id-abi-check" cmake --build "$b" --target abi-check
    elif compgen -G "$b/libzookeeper_*.dylib" >/dev/null ||
        compgen -G "$b/libzookeeper_*.so*" >/dev/null; then
        fail "cm-$id: the static-only build makes a shared library"
    else
        pass "cm-$id: static only"
    fi
    # Verify the transitive link interface propagated to `zks_link_check` via
    # `target_link_libraries(... zookeeper hashtable)`.
    lt="$b/CMakeFiles/zks_link_check.dir/link.txt"
    if [ -f "$lt" ]; then
        libs_rule "cm-$id link interface" "$config" \
            "$(tr -s ' ' '\n' <"$lt" | grep -E '^-l|\.(dylib|tbd)$|\.so(\.[0-9]+)*$|/lib[^/]*\.a$' |
                grep -Ev '/libzookeeper\.a$|/libhashtable\.a$' | tr '\n' ' ')"
    else
        fail "cm-$id: no $lt"
    fi
    pc="$b/inst/lib/pkgconfig/zookeeper_$fl.pc"
    if [ -f "$pc" ]; then
        libs_rule "cm-$id .pc Libs.private" "$config" \
            "$(sed -n 's/^Libs.private:[[:space:]]*//p' "$pc")"
    else
        fail "cm-$id: no $pc"
    fi
    if [ -f "$b/cli" ] && lib_deps "$b/cli" | grep -Ec '^lib(ssl|crypto)' >/dev/null; then
        fail "cm-$id: cli links OpenSSL"
    fi
    if [ "$RUNTIME" = 1 ]; then
        check "cm-$id-ctest" ctest --test-dir "$b" -L 'abi|link' --output-on-failure \
            --timeout 1200
    fi
}

stage_cmake() {
    local config fl shared sync id
    for config in "${CONFIGS[@]}"; do
        for fl in mt st; do
            for shared in ON OFF; do
                sync=OFF
                [ "$fl" = mt ] && sync=ON
                id="$config-$fl-$([ "$shared" = ON ] && echo shared || echo static)"
                # shellcheck disable=SC2046
                cm_build "$id" "$config" "-DWANT_SYNCAPI=$sync" \
                    "-DZOOKEEPER_BUILD_SHARED=$shared" $(cm_args "$config") || continue
                cm_checks "$id" "$config" "$fl" "$shared"
            done
        done
    done
}

###########################################################################
# Stage `provider`: TLS cryptographic provider selection (spec "Workspace
# layout and dependency rules" and "Selecting what to build"). Verifies that
# `ring` is the default, `aws-lc-rs` builds and passes TLS tests, specifying
# zero or multiple providers fails fast, and `aws-lc-rs-fips` rejects non-Linux
# targets.
###########################################################################
cargo_fail() {
    local name="$1" pattern="$2"
    shift 2
    expect_fail "$name" "$pattern" sh -c "cd \"$RDIR\" && CARGO_TARGET_DIR=\"$XT\" \
        cargo check --locked $*"
}

stage_provider() {
    local fl
    # Verify `compile_error!` guards on `zk-driver`, `zk-ffi`, and `zk-client`
    # (published package name `apache-zookeeper`).
    cargo_fail prov-ffi-none 'needs exactly one provider feature' -p zk-ffi --features tls
    cargo_fail prov-ffi-two 'only one provider feature|only one TLS provider' \
        -p zk-ffi --features tls-ring,tls-aws-lc-rs
    cargo_fail prov-driver-none 'needs exactly one provider feature' -p zk-driver --features tls
    cargo_fail prov-driver-two 'takes only one provider feature' \
        -p zk-driver --features tls-ring,tls-aws-lc-rs
    # `apache-zookeeper` enables `zk-driver/tls`, so either crate's guard may
    # emit the diagnostic first.
    cargo_fail prov-client-none 'needs exactly one (TLS )?provider feature' \
        -p apache-zookeeper --features tls-rustls
    cargo_fail prov-client-two 'only one (TLS )?provider feature' \
        -p apache-zookeeper --features tls-ring,tls-aws-lc-rs

    # `aws-lc-rs-fips` is supported only on Linux; verify rejection on macOS and
    # full build on Linux (`at-fips-ssl`).
    if [ "$MAC" = 1 ]; then
        expect_fail prov-dev-fips 'needs a Linux host' env CONFIG=ssl \
            TLS_PROVIDER=aws-lc-rs-fips \
            CARGO_TARGET_DIR="$XT" "$RDIR/dev/build-dropin.sh" "$CDIR/.." "$WORK/dev-fips"
        expect_fail prov-cm-fips 'aws-lc-rs-fips needs a Linux target' cmake -S "$RCDIR" \
            -B "$WORK/cm-fips" -DWANT_CPPUNIT=OFF \
            -DZK_RUST_TLS_PROVIDER=aws-lc-rs-fips
    else
        skip "prov-dev-fips, prov-cm-fips, prov-at-fips: macOS rejection checks;" \
            "Linux builds the FIPS provider in at-fips-ssl"
    fi
    expect_fail prov-cm-bogus 'give ring, aws-lc-rs or aws-lc-rs-fips' cmake -S "$RCDIR" \
        -B "$WORK/cm-bogus" -DWANT_CPPUNIT=OFF -DZK_RUST_TLS_PROVIDER=bogus
    # The build systems take exactly one provider. Two providers or an
    # empty value stop the build, as the compile_error! checks do.
    expect_fail prov-cm-two 'give ring, aws-lc-rs or aws-lc-rs-fips' cmake -S "$RCDIR" \
        -B "$WORK/cm-two" -DWANT_CPPUNIT=OFF \
        "-DZK_RUST_TLS_PROVIDER=ring;aws-lc-rs"
    expect_fail prov-cm-empty 'give ring, aws-lc-rs or aws-lc-rs-fips' cmake -S "$RCDIR" \
        -B "$WORK/cm-empty" -DWANT_CPPUNIT=OFF -DZK_RUST_TLS_PROVIDER=
    if [ "$MAC" = 1 ]; then
        expect_fail prov-dev-two 'unknown TLS_PROVIDER' env CONFIG=ssl \
            "TLS_PROVIDER=ring aws-lc-rs" \
            CARGO_TARGET_DIR="$XT" "$RDIR/dev/build-dropin.sh" "$CDIR/.." "$WORK/dev-two"
    else
        skip "prov-dev-two: dev/build-dropin.sh is a macOS tool"
    fi
    if at_prepare; then
        mkdir -p "$WORK/at-conf"
        [ "$MAC" = 1 ] && expect_fail prov-at-fips 'aws-lc-rs-fips needs a Linux host' sh -c "cd \"$WORK/at-conf\" && \
            \"$AT_SRC/configure\" --with-rust-workspace=\"$RDIR\" \
            --with-rust-tls-provider=aws-lc-rs-fips"
        expect_fail prov-at-bogus 'give ring, aws-lc-rs or aws-lc-rs-fips' sh -c "cd \"$WORK/at-conf\" && \
            \"$AT_SRC/configure\" --with-rust-workspace=\"$RDIR\" \
            --with-rust-tls-provider=bogus"
        expect_fail prov-at-two 'give ring, aws-lc-rs or aws-lc-rs-fips' sh -c "cd \"$WORK/at-conf\" && \
            \"$AT_SRC/configure\" --with-rust-workspace=\"$RDIR\" \
            --with-rust-tls-provider=ring,aws-lc-rs"
        expect_fail prov-at-none 'give ring, aws-lc-rs or aws-lc-rs-fips' sh -c "cd \"$WORK/at-conf\" && \
            \"$AT_SRC/configure\" --with-rust-workspace=\"$RDIR\" \
            --without-rust-tls-provider"
    fi

    # Verify default `ring` selection: an `ssl` build without an explicit
    # provider flag embeds `ring_core` symbols and zero `aws_lc` symbols.
    local ring_a="$WORK/dev-ssl/ffi-ssl/libzk_ffi.a"
    [ "$MAC" = 0 ] && ring_a="$WORK/at-yes-ssl/inst/lib/libzookeeper_mt.a"
    if [ -f "$ring_a" ]; then
        if nm_has "$ring_a" aws_lc; then
            fail "prov-ring-default: the default ssl staticlib has aws-lc symbols"
        elif nm_has "$ring_a" ring_core; then
            pass "prov-ring-default: the default ssl staticlib uses ring"
        else
            fail "prov-ring-default: the default ssl staticlib has no ring symbols"
        fi
    else
        skip "prov-ring-default (no $ring_a; run the dev or autotools stage first)"
    fi

    # Build and test with `aws-lc-rs` across `dev/build-dropin.sh`, Rust unit
    # tests, Autotools, and CMake.
    local out="$WORK/dev-ssl-awslc"
    if [ "$MAC" = 0 ]; then
        skip "prov-dev-awslc: dev/build-dropin.sh is a macOS tool; at-awslc-ssl covers Linux"
    elif run "$T_BUILD" "$LOGS/prov-dev-awslc.log" env CONFIG=ssl TLS_PROVIDER=aws-lc-rs \
        ABI_CHECK=1 CARGO_TARGET_DIR="$XT" OPENSSL_PREFIX="$OPENSSL_PREFIX" \
        "$RDIR/dev/build-dropin.sh" "$CDIR/.." "$out"; then
        pass "prov-dev-awslc: build-dropin.sh TLS_PROVIDER=aws-lc-rs with ABI_CHECK=1"
        if nm_has "$out/ffi-ssl/libzk_ffi.a" ring_core; then
            fail "prov-dev-awslc: the aws-lc-rs staticlib has ring symbols"
        else
            pass "prov-dev-awslc: the aws-lc-rs staticlib has no ring symbols"
        fi
        for fl in mt st; do
            shlib_facts "prov-dev-awslc-$fl" "$out/$(shlib "$fl")" "$fl" ssl
            [ "$RUNTIME" = 1 ] && check "prov-dev-awslc-$fl-abi-tests" \
                "$RCDIR/tests/abi/run-abi-tests.sh" --flavor "$fl" --config ssl --lib "$out" \
                --no-xtask --dead-host 127.0.0.1:23559 --out "$out/abi-tests-$fl"
        done
    else
        fail "prov-dev-awslc: build-dropin.sh (see $LOGS/prov-dev-awslc.log)"
        tail -10 "$LOGS/prov-dev-awslc.log"
    fi
    check prov-driver-awslc-tests sh -c "cd \"$RDIR\" && CARGO_TARGET_DIR=\"$XT\" \
        cargo test --locked -p zk-driver --features tls-aws-lc-rs"
    check prov-client-awslc-tests sh -c "cd \"$RDIR\" && CARGO_TARGET_DIR=\"$XT\" \
        cargo test --locked -p apache-zookeeper --features tls-aws-lc-rs,sasl --test api_tls \
        --test tls_fake --test tls_keys"
    check prov-ffi-awslc-tests sh -c "cd \"$RDIR\" && CARGO_TARGET_DIR=\"$XT\" \
        cargo test --locked -p zk-ffi --features tls-aws-lc-rs"
    # Autotools build with `--with-rust-tls-provider=aws-lc-rs`.
    # shellcheck disable=SC2046
    if has_config ssl &&
        at_build awslc-ssl yes ssl-awslc 0 $(at_args ssl) --with-rust-tls-provider=aws-lc-rs; then
        if grep -q 'tls=yes provider=aws-lc-rs sasl=no' "$LOGS/at-awslc-ssl-configure.log"; then
            pass "at-awslc-ssl: configure selects aws-lc-rs"
        else
            fail "at-awslc-ssl: configure does not report provider=aws-lc-rs"
        fi
        at_rust_checks awslc-ssl ssl
    fi
    # Autotools build with `--with-rust-tls-provider=aws-lc-rs-fips` (Linux x86_64/aarch64
    # with Go and CMake installed).
    if [ "$MAC" = 0 ] && has_config ssl; then
        case "$(uname -m)" in
        x86_64 | aarch64) fips_cpu=1 ;;
        *) fips_cpu=0 ;;
        esac
        if [ "$fips_cpu" = 0 ]; then
            skip "at-fips-ssl: the FIPS provider needs an x86_64 or aarch64 CPU"
        elif command -v go >/dev/null 2>&1 && command -v cmake >/dev/null 2>&1; then
            # shellcheck disable=SC2046
            if at_build fips-ssl yes ssl-fips 0 $(at_args ssl) \
                --with-rust-tls-provider=aws-lc-rs-fips; then
                if grep -q 'tls=yes provider=aws-lc-rs-fips sasl=no' \
                    "$LOGS/at-fips-ssl-configure.log"; then
                    pass "at-fips-ssl: configure selects aws-lc-rs-fips"
                else
                    fail "at-fips-ssl: configure does not report provider=aws-lc-rs-fips"
                fi
                at_rust_checks fips-ssl ssl
            fi
        else
            skip "at-fips-ssl: the FIPS provider builds on Linux; Go or CMake is not installed"
        fi
    fi
    # CMake build with `ZK_RUST_TLS_PROVIDER=aws-lc-rs`, verifying that
    # `abi/prelink.sh` localizes common symbols from `aws-lc` C objects.
    # shellcheck disable=SC2046
    if has_config full &&
        cm_build awslc-full-mt awslc-full -DWANT_SYNCAPI=ON \
            -DZOOKEEPER_BUILD_SHARED=ON -DZK_RUST_TLS_PROVIDER=aws-lc-rs $(cm_args full); then
        cm_checks awslc-full-mt full mt ON aws-lc-rs
    fi
}

###########################################################################
# Stage `paths`: default CA trust store path resolution (explicit option value
# vs. `OPENSSLDIR` auto-detection) and invalid path rejection.
###########################################################################
stage_paths() {
    local p="$RCDIR/abi/tls-probe.sh" out
    out="$(sh "$p" --with-openssl="$OPENSSL_PREFIX" | tr '\n' ' ')"
    if [ "$out" = "tls=yes openssl=yes cert_file=$("$OPENSSL_PREFIX/bin/openssl" version -d |
        sed -n 's/^OPENSSLDIR: "\(.*\)"$/\1/p')/cert.pem cert_dir=$("$OPENSSL_PREFIX/bin/openssl" \
        version -d | sed -n 's/^OPENSSLDIR: "\(.*\)"$/\1/p')/certs " ]; then
        pass "paths-probe-auto: $out"
    else
        fail "paths-probe-auto: '$out'"
    fi
    out="$(sh "$p" --without-openssl --with-rust-tls=no | tr '\n' ' ')"
    [ "$out" = "tls=no openssl=no cert_file= cert_dir= " ] && pass "paths-probe-no-tls: $out" ||
        fail "paths-probe-no-tls: '$out'"
    # The probe falls back to compiler default include paths, which may contain
    # system OpenSSL headers on Linux (`SYS_OPENSSL`).
    out="$(sh "$p" --with-openssl=/nonexistent --with-rust-tls=yes \
        --with-rust-tls-default-paths=:/x/certs | tr '\n' ' ')"
    [ "$out" = "tls=yes openssl=$SYS_OPENSSL cert_file= cert_dir=/x/certs " ] &&
        pass "paths-probe-empty-file: $out" ||
        fail "paths-probe-empty-file: '$out'"
    expect_fail paths-probe-two-colons 'with one colon' sh "$p" \
        --with-rust-tls-default-paths=/a:/b:/c
    expect_fail paths-probe-relative 'give absolute paths' sh "$p" \
        --with-rust-tls-default-paths=a.pem:/b
    expect_fail paths-probe-no-colon 'give FILE:DIR' sh "$p" --with-rust-tls-default-paths=/a
    expect_fail paths-probe-contradiction 'contradicts --with-openssl=no' sh "$p" \
        --without-openssl --with-rust-tls=yes

    expect_fail paths-cm-two-colons 'with one colon' cmake -S "$RCDIR" -B "$WORK/cm-badpaths" \
        -DWANT_CPPUNIT=OFF -DZK_RUST_TLS_DEFAULT_PATHS=/a:/b:/c
    expect_fail paths-cm-relative 'give absolute paths' cmake -S "$RCDIR" -B "$WORK/cm-badpaths" \
        -DWANT_CPPUNIT=OFF -DZK_RUST_TLS_DEFAULT_PATHS=a.pem:/b
    if at_prepare; then
        mkdir -p "$WORK/at-conf"
        # Path syntax validation runs even when TLS is disabled.
        expect_fail paths-at-two-colons 'with one colon' sh -c "cd \"$WORK/at-conf\" && \
            \"$AT_SRC/configure\" --with-rust-workspace=\"$RDIR\" \
            --without-openssl --without-sasl --with-rust-tls-default-paths=/a:/b:/c"
    fi
    # Verify custom `TLS_DEFAULT_PATHS` / `--with-rust-tls-default-paths` embedding.
    local out2="$WORK/dev-ssl-paths"
    if [ "$MAC" = 0 ]; then
        # shellcheck disable=SC2046
        if at_build paths-ssl yes ssl 0 $(at_args ssl) \
            --with-rust-tls-default-paths=/zkxbuild/etc/cert.pem:/zkxbuild/etc/certs; then
            paths_in_lib paths-at "$WORK/at-paths-ssl/inst/lib/$(shlib mt)" \
                /zkxbuild/etc/cert.pem /zkxbuild/etc/certs
        fi
    elif run "$T_BUILD" "$LOGS/paths-dev.log" env CONFIG=ssl FLAVORS=mt CARGO_TARGET_DIR="$XT" \
        TLS_DEFAULT_PATHS=/zkxbuild/etc/cert.pem:/zkxbuild/etc/certs \
        "$RDIR/dev/build-dropin.sh" "$CDIR/.." "$out2"; then
        paths_in_lib paths-dev "$out2/$(shlib mt)" /zkxbuild/etc/cert.pem \
            /zkxbuild/etc/certs
    else
        fail "paths-dev: build-dropin.sh (see $LOGS/paths-dev.log)"
    fi
}

###########################################################################
# Stage `gating`: export-gating equivalence between default legacy and Rust
# configures, and standalone Rust TLS builds (`--with-rust-tls=yes` /
# `WITH_RUST_TLS=ON`) without OpenSSL headers.
###########################################################################
stage_gating() {
    local fl
    local why='cannot build the Rust client: no Rust workspace at [^;]*; zookeeper-client-c builds the legacy C client'
    if at_prepare; then
        mkdir -p "$WORK/at-conf"
        expect_fail gating-at-no-workspace "$why" sh -c "cd \"$WORK/at-conf\" && \
            \"$AT_SRC/configure\" --with-rust-workspace=\"$WORK/no-workspace\""
    fi
    expect_fail gating-cm-no-workspace "$why" sh -c "cmake -S \"$RCDIR\" -B \"$WORK/cm-nows\" \
        -DWANT_CPPUNIT=OFF -DZKRS_WORKSPACE=\"$WORK/no-workspace\" >\"$WORK/cm-nows.log\" 2>&1
        rc=\$?; tr -s ' \n' '  ' <\"$WORK/cm-nows.log\"; exit \$rc"
    at_build default-no no default 1 &&
        if awk -v w="$WORK" '{ while (w != "" && (i = index($0, w)) > 0)
                $0 = substr($0, 1, i - 1) "WORK" substr($0, i + length(w)); print }' \
            "$LOGS/at-default-no-configure.log" | grep -Ewqi 'rust'; then
            fail "gating-at-legacy: the legacy configure output mentions Rust"
        else
            pass "gating-at-legacy: the legacy configure has no Rust check"
        fi &&
        at_build default-yes yes default 0 && {
        info "gating: autotools default config is $(mkvar default-yes ZKRS_ABI_CONFIG)"
        for fl in mt st; do
            if cmp -s <(exports "$WORK/at-default-no/inst/lib/$(shlib "$fl")") \
                <(exports "$WORK/at-default-yes/inst/lib/$(shlib "$fl")"); then
                pass "gating-at-default-$fl: the Rust and legacy default exports are equal" \
                    "($(exports "$WORK/at-default-yes/inst/lib/$(shlib "$fl")" | wc -l |
                        tr -d ' '))"
            else
                fail "gating-at-default-$fl: the Rust and legacy default exports differ"
            fi
            shlib_facts "gating-at-default-yes-$fl" \
                "$WORK/at-default-yes/inst/lib/$(shlib "$fl")" "$fl" \
                "$(mkvar default-yes ZKRS_ABI_CONFIG)"
        done
        check gating-at-default-abi make -C "$WORK/at-default-yes" abi-check abi-static
    }

    # `--with-rust-tls=yes` on a host where `<openssl/ssl.h>` is not on the
    # default compiler include path.
    if echo '#include <openssl/ssl.h>' | cc -E -x c - >/dev/null 2>&1; then
        skip "gating-at-rusttls: the compiler finds openssl/ssl.h without -I"
    elif at_build rusttls yes rusttls 0 --with-openssl=yes --with-rust-tls=yes --without-sasl \
        --with-rust-tls-default-paths=/zkxbuild/etc/cert.pem:/zkxbuild/etc/certs; then
        if grep -q 'no OpenSSL headers' "$LOGS/at-rusttls-configure.log"; then
            pass "gating-at-rusttls: configure warns that the programs have no TLS option"
        else
            fail "gating-at-rusttls: configure gives no warning about the OpenSSL headers"
        fi
        at_rust_checks rusttls ssl
    fi

    # CMake default configure comparison between legacy (static-only) and Rust
    # (comparing `zookeeper_init_ssl` and `zoo_sasl_connect` presence).
    if cm_build default-legacy legacy -DWANT_SYNCAPI=ON &&
        cm_build default-rust default -DZOOKEEPER_BUILD_SHARED=ON; then
        local ls=0 la=0 cfg
        ar_globals "$WORK/cm-default-legacy/libzookeeper.a" \
            | grep -cx "${SYMP}zookeeper_init_ssl" >/dev/null && ls=1
        ar_globals "$WORK/cm-default-legacy/libzookeeper.a" \
            | grep -cx "${SYMP}zoo_sasl_connect" >/dev/null && la=1
        case "$ls$la" in 00) cfg=plain ;; 10) cfg=ssl ;; 01) cfg=sasl ;; *) cfg=full ;; esac
        info "gating: the legacy CMake default is $cfg"
        for s in "${SYMP}zookeeper_init_ssl" "${SYMP}zoo_sasl_connect"; do
            if [ "$(ar_globals "$WORK/cm-default-legacy/libzookeeper.a" | grep -cx "$s")" = \
                "$(ar_globals "$WORK/cm-default-rust/libzookeeper.a" | grep -cx "$s")" ]; then
                pass "gating-cm-default: $s is in both archives or in none"
            else
                fail "gating-cm-default: $s differs between the archives"
            fi
        done
        shlib_facts gating-cm-default "$WORK/cm-default-rust/$(shlib mt)" mt "$cfg"
        [ "$RUNTIME" = 1 ] && check gating-cm-default-ctest \
            ctest --test-dir "$WORK/cm-default-rust" \
            -L 'abi|link' --output-on-failure --timeout 1200
    fi

    # CMake `WITH_RUST_TLS=ON` with OpenSSL discovery disabled.
    if cm_build rusttls rusttls -DZOOKEEPER_BUILD_SHARED=ON \
        -DWITH_RUST_TLS=ON -DWITH_CYRUS_SASL=OFF -DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=ON \
        "$CM_HIDE_OPENSSL" \
        -DZK_RUST_TLS_DEFAULT_PATHS=/zkxbuild/etc/cert.pem:/zkxbuild/etc/certs; then
        if grep -q 'OpenSSL libraries not found. WITH_RUST_TLS=ON builds SSL support' \
            "$LOGS/cm-rusttls-configure.log"; then
            pass "gating-cm-rusttls: the probe finds no OpenSSL, and TLS is on"
        else
            fail "gating-cm-rusttls: no 'WITH_RUST_TLS=ON builds SSL support' message"
        fi
        # Normalize newlines in case CMake wraps the warning message across lines.
        if tr -s ' \n' '  ' <"$LOGS/cm-rusttls-configure.log" |
            grep -c 'cli and load_gen skipped' >/dev/null && [ ! -f "$WORK/cm-rusttls/cli" ]; then
            pass "gating-cm-rusttls: no OpenSSL headers, so cli and load_gen are not built"
        else
            fail "gating-cm-rusttls: no 'cli and load_gen skipped' warning, or cli is built"
        fi
        cm_checks rusttls ssl mt ON
        paths_in_lib gating-cm-rusttls-paths "$WORK/cm-rusttls/$(shlib mt)" \
            /zkxbuild/etc/cert.pem /zkxbuild/etc/certs
    fi
}

for s in "${STAGES[@]}"; do
    case "$s" in
    dev | autotools | cmake | provider | paths | gating | legacy)
        echo "==== stage $s ($(date '+%H:%M:%S'))" | tee -a "$RESULTS"
        "stage_$s"
        ;;
    *) echo "run-build-matrix.sh: unknown stage $s" >&2; exit 2 ;;
    esac
done

echo "run-build-matrix.sh: $PASS passed, $FAIL failed, $SKIP skipped, $KNOWN known" \
    | tee -a "$RESULTS"
[ "$FAIL" = 0 ]
