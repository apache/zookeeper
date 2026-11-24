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

# Executes a public-API CppUnit test binary (`zktest-public-mt` or
# `zktest-public-st`) against a specified client library directory. Because the
# test binary is compiled once and binds `libzookeeper_{mt,st}` at runtime via
# the dynamic loader search path (`--libdir`), the identical executable runs
# against both the legacy C and Rust implementations.
#
# Usage: run-public.sh [--libdir DIR] [--workdir DIR] BINARY [TEST_NAME]
#
# Port environment variables must match the values baked into `BINARY` at build
# time: `ZKTEST_PORT`, `ZKTEST_QUORUM_PORT_BASE`, and `ZKTEST_RO_PORT_BASE`
# (where 0 or unset selects upstream legacy ports).
#
# `ZKTEST_REPO` points to the ZooKeeper repository root containing the compiled
# Java server (default: the enclosing repository of this script). All servers
# started during the run are terminated on exit.

set -euo pipefail

# Resolve repository paths: RCDIR is the Rust C compatibility root and RDIR is
# the Rust workspace root.
HERE="$(cd "$(dirname "$0")" && pwd)"
RCDIR="$(cd "$HERE/../.." && pwd)"
RDIR="$(cd "$RCDIR/.." && pwd)"
REPO="$(cd "${ZKTEST_REPO:-$RDIR/../..}" && pwd)"
DEFAULT_SERVER_SCRIPT="$REPO/zookeeper-client/zookeeper-client-rust/dev/test-server.sh"
export ZKTEST_SERVER_SCRIPT="${ZKTEST_SERVER_SCRIPT:-$DEFAULT_SERVER_SCRIPT}"
LIBDIR=
WORK=

while [[ $# -gt 0 ]]; do
    case $1 in
    --libdir) LIBDIR=$2; shift 2 ;;
    --workdir) WORK=$2; shift 2 ;;
    --) shift; break ;;
    -*) echo "run-public.sh: unknown option $1" >&2; exit 2 ;;
    *) break ;;
    esac
done
if [[ $# -lt 1 ]]; then
    echo "usage: $0 [--libdir DIR] [--workdir DIR] BINARY [TEST_NAME]" >&2
    exit 2
fi
BIN="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
shift

export ZOOPORT="${ZKTEST_PORT:-22181}"
WORK="${WORK:-${TMPDIR:-/tmp}/zktest-public-run-$ZOOPORT}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
export ZKTEST_SERVER_DIR="$WORK/server"
if [[ ${ZKTEST_RO_PORT_BASE:-0} != 0 ]]; then
    export ZKTEST_RO_PORT_BASE
fi

# `TestReconfigServer` invokes `$ZKROOT/bin/zkServer.sh` and writes quorum state
# under `$ZKROOT/build`. Provision an isolated `$ZKROOT` inside `$WORK` with a
# dedicated `conf/java.env` that disables the Jetty AdminServer to avoid port
# 8080 conflicts across the three quorum peers.
ROOT="$WORK/zkroot"
mkdir -p "$ROOT/conf"
ln -sfn "$REPO/bin" "$ROOT/bin"
ln -sfn "$REPO/zookeeper-server" "$ROOT/zookeeper-server"
cp "$REPO/conf/logback.xml" "$ROOT/conf/logback.xml"
# Append to any `SERVER_JVMFLAGS` set by `TestReconfigServer` per quorum member.
echo 'SERVER_JVMFLAGS="${SERVER_JVMFLAGS:-} -Dzookeeper.admin.enableServer=false"' \
    >"$ROOT/conf/java.env"
export ZKROOT="$ROOT"
export ZOOCFGDIR="$ROOT/conf"

cleanup() {
    "$HERE/zkServer.sh" stop >/dev/null 2>&1 || true
    local conf
    for conf in "$ROOT"/build/test/test-cppunit/conf/*.conf; do
        [[ -f $conf ]] || continue
        "$ROOT/bin/zkServer.sh" stop "$conf" >/dev/null 2>&1 || true
    done
}
trap cleanup EXIT

if [[ -n $LIBDIR ]]; then
    LIBDIR="$(cd "$LIBDIR" && pwd)"
    export DYLD_LIBRARY_PATH="$LIBDIR${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
    export LD_LIBRARY_PATH="$LIBDIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

cd "$WORK"
echo "run-public.sh: $BIN${LIBDIR:+ with $LIBDIR}, port $ZOOPORT, work $WORK"
"$BIN" "$@"
