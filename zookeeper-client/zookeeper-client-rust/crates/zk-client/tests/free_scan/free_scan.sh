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

# Executes heap zeroization verification across sensitive security test cases
# using the dynamic memory interposition scanner (`free_scan.c`) on macOS and on
# Linux (glibc).
#
# Target test suites comprise:
#   - SASL session credential zeroization in `sasl_native.rs`
#   - DIGEST-MD5 long-nonce challenge buffer zeroization
#   - Private key PEM decoding buffer zeroization in `zk-driver::tls_identity`
#   - Transmit buffer payload zeroization in `zk-driver::iocore_tests`
#
# Usage:
#   ZK_TEST_PORT_BASE=<base> ./free_scan.sh
#
# Compiles the interposition dynamic library and executes each test binary with
# the library injected (`DYLD_INSERT_LIBRARIES` on macOS, `LD_PRELOAD` on Linux),
# asserting that no deallocated heap memory
# retains confidential sentinel values.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CRATE="$(cd "$HERE/../.." && pwd)"
: "${ZK_TEST_PORT_BASE:?set ZK_TEST_PORT_BASE}"
TARGET="${CARGO_TARGET_DIR:-$CRATE/../../target}"
WORK="$TARGET/free_scan"
mkdir -p "$WORK"

# The platform layer: the library flags and the loader variable that
# inserts the scanner.
if [ "$(uname -s)" = Darwin ]; then
    SCANNER="$WORK/libfree_scan.dylib"
    SHLIB_FLAGS=(-dynamiclib)
    INSERT_VAR=DYLD_INSERT_LIBRARIES
else
    # The scanner calls the __libc_* allocator names of glibc. Other C
    # libraries do not export them.
    if ! getconf GNU_LIBC_VERSION >/dev/null 2>&1; then
        echo "free_scan.sh: needs glibc on a host that is not macOS" >&2
        exit 2
    fi
    SCANNER="$WORK/libfree_scan.so"
    SHLIB_FLAGS=(-shared -fPIC)
    INSERT_VAR=LD_PRELOAD
fi
cc -std=c99 -Wall -Wextra -Wdeclaration-after-statement -Werror "${SHLIB_FLAGS[@]}" \
    "$HERE/free_scan.c" -o "$SCANNER"

BIN="$(cd "$CRATE" && cargo test --locked --features sasl --test sasl_native --no-run \
    --message-format=json | jq -r 'select(.executable != null) | .executable' | tail -n 1)"

OUT="$WORK/hits.txt"
rm -f "$OUT"
# Target sentinel corresponds to the test password credential (`sasl_native.rs::PASSWORD`).
env "$INSERT_VAR=$SCANNER" \
    ZK_FREE_SCAN_MARKER="zk-sasl-native-4c1e9b" \
    ZK_FREE_SCAN_OUT="$OUT" \
    "$BIN" --ignored --exact secrets_are_zero_before_free --test-threads=1

# Target sentinel corresponds to the intermediate HA1 hash digest during long-nonce exchange.
LIB="$(cd "$CRATE" && cargo test --locked --features sasl --lib --no-run \
    --message-format=json | jq -r 'select(.executable != null) | .executable' | tail -n 1)"
rm -f "$OUT"
env "$INSERT_VAR=$SCANNER" \
    ZK_FREE_SCAN_MARKER="66a1b64b6346800663dd35e9c207ad26" \
    ZK_FREE_SCAN_OUT="$OUT" \
    "$LIB" --ignored --exact sasl::digest_md5::tests::long_nonce_secrets_are_zero_before_free \
    --test-threads=1

# TLS fixtures are generated dynamically by `zk-testkit`. To track private key material across
# runs, the sentinel pattern is extracted directly from the generated RSA private key fixture
# (`keys/rsa-pkcs1.pem`).
FIX="$(cd "$CRATE/../.." && cargo run -q --locked --offline -p zk-testkit --bin zk-fixtures \
    -- tls)" || exit 1
MARKER="$(sed -n 3p "$FIX/keys/rsa-pkcs1.pem" | cut -c1-24)"
if [ "${#MARKER}" -ne 24 ]; then
    echo "free_scan.sh: no marker in $FIX/keys/rsa-pkcs1.pem" >&2
    exit 1
fi
DRV="$(cd "$CRATE/../zk-driver" && cargo test --locked --features tls-ring --test tls_identity \
    --no-run --message-format=json | jq -r 'select(.executable != null) | .executable' | tail -n 1)"
rm -f "$OUT"
# Export pre-generated TLS fixtures via `ZK_TEST_TLS_FIXTURES` to avoid spawning external OpenSSL
# processes under interposition.
env "$INSERT_VAR=$SCANNER" \
    ZK_TEST_TLS_FIXTURES="$FIX" \
    ZK_FREE_SCAN_MARKER="$MARKER" \
    ZK_FREE_SCAN_OUT="$OUT" \
    "$DRV" --ignored --exact pem_buffers_are_zero_before_free --test-threads=1

# Target sentinel corresponds to outbound authentication payload (`iocore_tests.rs::TX_MARKER`).
DRVLIB="$(cd "$CRATE/../zk-driver" && cargo test --locked --lib --no-run \
    --message-format=json | jq -r 'select(.executable != null) | .executable' | tail -n 1)"
rm -f "$OUT"
env "$INSERT_VAR=$SCANNER" \
    ZK_FREE_SCAN_MARKER="zk-tx-copy-secret-7b3e91" \
    ZK_FREE_SCAN_OUT="$OUT" \
    "$DRVLIB" --ignored --exact iocore_tests::tx_copy_is_zero_before_free --test-threads=1
# Verify confidential payload zeroization along the TLS encrypted transmit pipeline.
rm -f "$OUT"
env "$INSERT_VAR=$SCANNER" \
    ZK_FREE_SCAN_MARKER="zk-tx-copy-secret-7b3e91" \
    ZK_FREE_SCAN_OUT="$OUT" \
    "$DRVLIB" --ignored --exact iocore_tests::tls_tx_copy_is_zero_before_free --test-threads=1
# Verify confidential payload zeroization along plaintext transmission of multi-frame messages.
rm -f "$OUT"
env "$INSERT_VAR=$SCANNER" \
    ZK_FREE_SCAN_MARKER="zk-tx-copy-secret-7b3e91" \
    ZK_FREE_SCAN_OUT="$OUT" \
    "$DRVLIB" --ignored --exact iocore_tests::plain_large_tx_copy_is_zero_before_free \
    --test-threads=1
