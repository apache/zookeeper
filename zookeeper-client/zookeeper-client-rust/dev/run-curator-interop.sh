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

# Orchestrates the Apache Curator interoperability test suite (`interop/curator`)
# for `apache-zookeeper-recipes`.
#
# Compiles the `interop_peer` Rust example binary and runs the JUnit test suite
# across standalone and 3 node ensemble topologies for each specified server
# version.
#
# Usage: run-curator-interop.sh [VERSION ...]
#   VERSION   `head` or an official release version (default: `head 3.9.6`).
#
# Environment variables:
#   ZK_TEST_PORT_BASE       base TCP port (default: `23400`; standalone uses
#                           `BASE`, ensemble uses `BASE+1..BASE+9`)
#   ZK_INTEROP_PROXY_PORT   base TCP port for `CutProxy` (default: `0`, ephemeral)
#   ZK_INTEROP_TOPOLOGIES   topologies to test (default: `"standalone ensemble"`)
#   ZK_INTEROP_MVN_ARGS     additional arguments forwarded to `mvn test`
#   ZK_INTEROP_REPEAT       iterations per version and topology (default: `1`)
#   ZK_DEV_DIR              directory containing server lifecycle scripts
#                           (default: directory of this script)
#   CARGO_TARGET_DIR        Cargo target directory containing `interop_peer`
#   ZK_INTEROP_CARGO_ARGS   additional arguments forwarded to `cargo build`
#
# Acquires the `${TMPDIR:-/tmp}/zk-recipes-lease-BASE` lock directory shared with
# the `apache-zookeeper-recipes` live-server test suite so concurrent test runs
# do not collide on the same port range.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE="$(cd "$HERE/.." && pwd)"
DEV="${ZK_DEV_DIR:-$HERE}"
BASE="${ZK_TEST_PORT_BASE:-23400}"
PROXY_PORT="${ZK_INTEROP_PROXY_PORT:-0}"
TOPOLOGIES="${ZK_INTEROP_TOPOLOGIES:-standalone ensemble}"
REPEAT="${ZK_INTEROP_REPEAT:-1}"
case "$REPEAT" in
'' | *[!0-9]* | 0)
    echo "ZK_INTEROP_REPEAT must be a positive number: $REPEAT" >&2
    exit 2
    ;;
esac
TMP="${TMPDIR:-/tmp}"
TMP="${TMP%/}"
WORK="$TMP/zk-curator-interop-$BASE"
LEASE="$TMP/zk-recipes-lease-$BASE"
if [ "$#" -eq 0 ]; then
    set -- head 3.9.6
fi

# ---- Port range lease management ---------------------------------------

HAVE_LEASE=0
take_lease() {
    local waited=0 owner
    while ! mkdir "$LEASE" 2>/dev/null; do
        owner="$(cat "$LEASE/pid" 2>/dev/null || true)"
        # Reclaim the lease if its recorded owner PID is no longer alive or if
        # no PID file appears within 10 seconds of directory creation.
        if { [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; } ||
            { [ -z "$owner" ] && [ "$waited" -ge 50 ]; }; then
            rm -rf "$LEASE"
            waited=0
            continue
        fi
        if [ "$waited" -ge 4500 ]; then
            echo "The lease $LEASE stays taken" >&2
            exit 1
        fi
        sleep 0.2
        waited=$((waited + 1))
    done
    echo $$ > "$LEASE/pid"
    HAVE_LEASE=1
}

drop_lease() {
    if [ "$HAVE_LEASE" = 1 ] && [ "$(cat "$LEASE/pid" 2>/dev/null)" = "$$" ]; then
        rm -rf "$LEASE"
    fi
    HAVE_LEASE=0
}

# ---- Server lifecycle helpers ------------------------------------------

# Active topology (`standalone` or `ensemble`) and data directory, if running.
RUNNING=""
RUNNING_DIR=""
RUNNING_ENV=()

server_env() {
    if [ "$1" = head ]; then
        RUNNING_ENV=(env -u ZK_TEST_SERVER_VERSION -u ZK_TEST_SERVER_HOME)
    else
        RUNNING_ENV=(env -u ZK_TEST_SERVER_HOME ZK_TEST_SERVER_VERSION="$1")
    fi
}

stop_server() {
    [ -n "$RUNNING" ] || return 0
    if [ "$RUNNING" = standalone ]; then
        "${RUNNING_ENV[@]}" "$DEV/test-server.sh" stop "$BASE" "$RUNNING_DIR" || true
    else
        "${RUNNING_ENV[@]}" "$DEV/test-ensemble.sh" stop "$((BASE + 1))" "$RUNNING_DIR" || true
    fi
    RUNNING=""
    RUNNING_DIR=""
}

cleanup() {
    stop_server
    drop_lease
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Verifies that no pre-existing process is listening on ports `BASE..BASE+9`.
check_ports_free() {
    local p
    for p in $(seq "$BASE" "$((BASE + 9))"); do
        if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then
            echo "Port $p is in use; stop the process that listens on it" >&2
            return 1
        fi
    done
}

# Starts a fresh standalone server or 3-node ensemble and populates `CONNECT`.
start_server() {
    local version="$1" topology="$2" dir="$WORK/$1-$2" b
    check_ports_free || return 1
    rm -rf "$dir"
    mkdir -p "$dir" || return 1
    server_env "$version"
    RUNNING="$topology"
    RUNNING_DIR="$dir"
    if [ "$topology" = standalone ]; then
        "${RUNNING_ENV[@]}" "$DEV/test-server.sh" start "$BASE" "$dir" || return 1
        CONNECT="127.0.0.1:$BASE"
    else
        b=$((BASE + 1))
        "${RUNNING_ENV[@]}" "$DEV/test-ensemble.sh" start "$b" "$dir" || return 1
        CONNECT="127.0.0.1:$b,127.0.0.1:$((b + 1)),127.0.0.1:$((b + 2))"
    fi
}

# ---- Test execution ----------------------------------------------------

for v in "$@"; do
    case "$v" in
    head) ;;
    *) "$DEV/get-release-server.sh" "$v" ;;
    esac
done

# Allow word splitting across optional Cargo flags.
# shellcheck disable=SC2086
(cd "$WORKSPACE" && cargo build --locked ${ZK_INTEROP_CARGO_ARGS:-} \
    -p apache-zookeeper-recipes --example interop_peer)
TARGET="${CARGO_TARGET_DIR:-$WORKSPACE/target}"
case "$TARGET" in
/*) ;;
*) TARGET="$WORKSPACE/$TARGET" ;;
esac
PEER="$TARGET/debug/examples/interop_peer"
if [ ! -x "$PEER" ]; then
    echo "No peer binary at $PEER" >&2
    exit 1
fi

take_lease
FAILED=()
for v in "$@"; do
    for t in $TOPOLOGIES; do
        case "$t" in
        standalone | ensemble) ;;
        *)
            echo "Unknown topology $t" >&2
            exit 2
            ;;
        esac
        for i in $(seq 1 "$REPEAT"); do
            pass="$v/$t"
            if [ "$REPEAT" -gt 1 ]; then
                pass="$v/$t#$i"
            fi
            echo "== Curator interop against $pass (ports $BASE+)"
            CONNECT=""
            if ! start_server "$v" "$t"; then
                FAILED+=("$pass (server start)")
                stop_server
                continue
            fi
            # shellcheck disable=SC2086
            if ! mvn -B -f "$WORKSPACE/interop/curator/pom.xml" test \
                -Dzk.connect="$CONNECT" -Drust.peer.bin="$PEER" \
                -Dinterop.proxy.port="$PROXY_PORT" ${ZK_INTEROP_MVN_ARGS:-}; then
                FAILED+=("$pass")
            fi
            stop_server
        done
    done
done

if [ "${#FAILED[@]}" -gt 0 ]; then
    echo "Curator interop failed: ${FAILED[*]}" >&2
    exit 1
fi
echo "Curator interop passed: $* ($TOPOLOGIES, $REPEAT pass(es) each)"
