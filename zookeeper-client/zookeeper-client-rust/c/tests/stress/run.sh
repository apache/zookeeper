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

# Executes the close-path stress harness (`close_stress.c`) against a target
# `libzookeeper_{mt,st}` library.
#
# Compiles `close_stress.c` against the public C headers, links it against the
# library in `--lib`, and executes one pass of `ITERATIONS` cycles per random
# seed. Dynamic loader search paths (`DYLD_LIBRARY_PATH` and `LD_LIBRARY_PATH`)
# resolve the target library at runtime, and `close_stress` verifies via
# `dladdr` that the loaded image resides in `--lib`. This runner does not start
# or stop ZooKeeper servers.
#
# Because this test is CPU-intensive and timing-sensitive, it is excluded from
# default CI suites and intended for targeted verification of `zookeeper_close`
# teardown concurrency.
#
# Usage: run.sh --lib DIR --host HOST:PORT [--flavor mt|st]
#               [--iterations N] [--seeds "S1 S2 ..."] [--burn N]
#               [--ssl CERTDIR] [--out DIR]
#
#   --lib         the directory of libzookeeper_FL (the drop-in or legacy).
#                 dev/build-dropin.sh builds the drop-in on macOS; CONFIG=ssl
#                 gives a build for --ssl. On Linux, use the lib directory
#                 of the drop-in install: target/c/lib after
#                 mvn -Pfull-build -Prust install, or the prefix of a build
#                 of the drop-in project in c
#   --host        the server address. Start one with dev/test-server.sh.
#   --flavor      mt (default) or st. st compiles without THREADED.
#   --iterations  the iterations of each pass (default: 3000)
#   --seeds       the seed of each pass (default: "1 2 3 4")
#   --burn N      run N busy loops at normal priority, and run the test
#                 at nice 15. The load widens the race windows of the
#                 close (default: 0)
#   --ssl CERTDIR connect with zookeeper_init_ssl. CERTDIR holds root.crt
#                 (the CA of the server), client.crt and clientkey.pem
#                 (password "password"). OpenSSL 3 and the drop-in need a
#                 root with basicConstraints CA:TRUE. Make one with
#                 openssl req -x509 -new -key root.key -days 30
#                 -subj /CN=root -addext basicConstraints=critical,CA:TRUE
#                 -out root.crt, and sign the server and client
#                 certificates with it. --host must name the secure port,
#                 and --lib must hold an ssl or full build
#   --out DIR     the work directory (default: ${TMPDIR:-/tmp}/zk-close-stress)
#
# Environment variables:
#   STRESS_DMIN, STRESS_DMAX, STRESS_LATE, STRESS_LOG, and STRESS_LEVEL forward
#   directly to `close_stress` (see `close_stress.c`). `OPENSSL_PREFIX` specifies
#   the OpenSSL include root for `--ssl` builds (default: `pkg-config`, falling
#   back to Homebrew `openssl@3`).
#
# Each seed pass emits a single summary line. Exits with 0 if all passes complete
# with zero lost, duplicate, or stale completions, zero connection failures, and
# `ZOK` from every `zookeeper_close` call; exits with 1 on any test invariant
# failure, or 2 on usage or compilation errors.
#
# Platforms: macOS and Linux with bash and a C99 compiler.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CDIR="$(cd "$HERE/../../../../zookeeper-client-c" && pwd)"

LIB=""
HOST=""
FL=mt
ITER=3000
SEEDS="1 2 3 4"
BURN=0
SSL=""
OUT="${TMPDIR:-/tmp}/zk-close-stress"

usage() {
    sed -n '/^# Usage:/,/^# Platforms:/p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
    --lib) LIB="${2:-}"; shift 2 ;;
    --host) HOST="${2:-}"; shift 2 ;;
    --flavor) FL="${2:-}"; shift 2 ;;
    --iterations) ITER="${2:-}"; shift 2 ;;
    --seeds) SEEDS="${2:-}"; shift 2 ;;
    --burn) BURN="${2:-}"; shift 2 ;;
    --ssl) SSL="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "run.sh: unknown option $1" >&2; usage ;;
    esac
done
[ -n "$LIB" ] && [ -n "$HOST" ] || usage
case "$FL" in mt|st) ;; *) echo "run.sh: --flavor must be mt or st" >&2; exit 2 ;; esac
case "$BURN" in ''|*[!0-9]*) echo "run.sh: --burn needs a number" >&2; exit 2 ;; esac
if [ ! -d "$LIB" ]; then
    echo "run.sh: --lib $LIB is not a directory" >&2
    exit 2
fi
LIB="$(cd "$LIB" && pwd)"
if [ ! -f "$CDIR/generated/zookeeper.jute.h" ]; then
    echo "run.sh: $CDIR/generated has no jute header; generate the jute sources first" >&2
    exit 2
fi

mkdir -p "$OUT" || exit 2
BIN="$OUT/close_stress_$FL"
CFLAGS=(-std=c99 -Wall -Wextra -Werror -O2 -I"$CDIR/include" -I"$CDIR/generated")
if [ "$FL" = mt ]; then
    CFLAGS+=(-DTHREADED)
fi
CERT=""
if [ -n "$SSL" ]; then
    SSL="$(cd "$SSL" && pwd)" || exit 2
    CFLAGS+=(-DHAVE_OPENSSL_H)
    # `zookeeper.h` includes `<openssl/ossl_typ.h>` when `HAVE_OPENSSL_H` is defined.
    if [ -n "${OPENSSL_PREFIX:-}" ]; then
        CFLAGS+=(-I"$OPENSSL_PREFIX/include")
    elif pkg-config --exists openssl 2>/dev/null; then
        read -r -a ossl <<<"$(pkg-config --cflags openssl)"
        CFLAGS+=("${ossl[@]+"${ossl[@]}"}")
    elif [ -d /opt/homebrew/opt/openssl@3/include ]; then
        CFLAGS+=(-I/opt/homebrew/opt/openssl@3/include)
    fi
    CERT="$SSL/root.crt,$SSL/client.crt,$SSL/clientkey.pem,password"
fi
# Link `-ldl` on Linux for `dladdr` compatibility on glibc versions prior to 2.34.
LIBS=(-lzookeeper_"$FL" -lpthread)
if [ "$(uname -s)" = Linux ]; then
    LIBS+=(-ldl)
fi
if ! cc "${CFLAGS[@]}" "$HERE/close_stress.c" -L"$LIB" "${LIBS[@]}" -o "$BIN"; then
    echo "run.sh: compile of close_stress.c failed" >&2
    exit 2
fi

# Terminate background CPU-burn worker processes by PID on script exit.
BURNERS=()
stop_burners() {
    local p
    for p in "${BURNERS[@]+"${BURNERS[@]}"}"; do
        kill "$p" 2>/dev/null
    done
    for p in "${BURNERS[@]+"${BURNERS[@]}"}"; do
        wait "$p" 2>/dev/null
    done
    BURNERS=()
}
trap stop_burners EXIT
trap 'exit 130' INT TERM
NICE=()
if [ "$BURN" -gt 0 ]; then
    for _ in $(seq "$BURN"); do
        bash -c 'while :; do :; done' &
        BURNERS+=("$!")
    done
    NICE=(nice -n 15)
fi

# Invoke `env` inside `nice` so macOS SIP does not strip `DYLD_LIBRARY_PATH`
# across the `/usr/bin/nice` execution boundary.
fail=0
for seed in $SEEDS; do
    log="$OUT/$FL-$seed.log"
    "${NICE[@]+"${NICE[@]}"}" env DYLD_LIBRARY_PATH="$LIB" LD_LIBRARY_PATH="$LIB" \
        STRESS_EXPECT_DIR="$LIB" ${CERT:+STRESS_CERT="$CERT"} \
        "$BIN" "$HOST" "$ITER" "$seed" >"$log" 2>&1
    rc=$?
    summary="$(grep '^seed=' "$log" || true)"
    echo "${summary:-seed=$seed: no summary (exit $rc), see $log}"
    if [ "$rc" -ne 0 ]; then
        fail=1
        [ "$rc" -eq 2 ] && tail -n 5 "$log" >&2
    fi
done
exit "$fail"
