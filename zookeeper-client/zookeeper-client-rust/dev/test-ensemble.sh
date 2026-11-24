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

# Manages a local 3-node ZooKeeper quorum on `127.0.0.1` for live-server
# integration tests (`zk-client`, `zk-recipes`, and `run-curator-interop.sh`).
#
# Usage:
#   test-ensemble.sh start BASE [DIR]        start quorum peers 1..3
#   test-ensemble.sh stop BASE [DIR]         stop all quorum peers
#   test-ensemble.sh stop-node BASE N [DIR]  stop quorum peer N
#   test-ensemble.sh start-node BASE N [DIR] start quorum peer N
#   test-ensemble.sh leader BASE             print the 1-based node ID of the active leader
#
# Port layout for peer `N` (`1..3`): client port `BASE+N-1`, quorum port
# `BASE+N+2`, and election port `BASE+N+5` (occupying ports `BASE..BASE+8`).
# Per-node configuration and data directories reside under `DIR/nodeN`
# (default: `${TMPDIR:-/tmp}/zk-rust-ensemble-BASE`).
#
# Honours `ZK_TEST_SERVER_HOME`, `ZK_TEST_SERVER_VERSION`,
# `ZK_TEST_SERVERS_DIR`, `ZK_TEST_SOURCE_ROOT`, `ZK_TEST_SERVER_PROPS`, and
# `ZK_TEST_SERVER_JVM_OPTS` identically to `test-server.sh`.

set -euo pipefail
ROOT="${ZK_TEST_SOURCE_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}"
CMD="${1:?usage: $0 start|stop|stop-node|start-node|leader BASE ...}"
BASE="${2:?BASE port missing}"
case "$CMD" in
stop-node | start-node)
    NODE="${3:?node number missing}"
    DIR="${4:-${TMPDIR:-/tmp}/zk-rust-ensemble-$BASE}"
    ;;
*)
    DIR="${3:-${TMPDIR:-/tmp}/zk-rust-ensemble-$BASE}"
    ;;
esac

client_port() { echo $((BASE + $1 - 1)); }

classpath() {
    local home="${ZK_TEST_SERVER_HOME:-}"
    local ver="${ZK_TEST_SERVER_VERSION:-}"
    if [ -z "$home" ] && [ -n "$ver" ]; then
        home="${ZK_TEST_SERVERS_DIR:-/tmp/zk-servers}/$ver/apache-zookeeper-$ver-bin"
    fi
    if [ -n "$home" ]; then
        if [ ! -d "$home/lib" ]; then
            echo "No release server in $home; run get-release-server.sh" >&2
            return 1
        fi
        echo "$home/lib/*:$home/conf"
    else
        echo "$ROOT/zookeeper-server/target/classes:$ROOT/zookeeper-server/target/lib/*"
    fi
}

# Queries the `Mode:` line (`leader` or `follower`) from the `srvr` 4-letter word command.
mode() {
    echo srvr | nc -w 1 127.0.0.1 "$(client_port "$1")" 2>/dev/null |
        sed -n 's/^Mode: //p' | tr -d '\r'
}

write_config() {
    local n="$1" d="$DIR/node$1"
    mkdir -p "$d/data"
    echo "$n" > "$d/data/myid"
    {
        echo "tickTime=500"
        echo "initLimit=20"
        echo "syncLimit=10"
        echo "dataDir=$d/data"
        echo "clientPort=$(client_port "$n")"
        echo "clientPortAddress=127.0.0.1"
        echo "admin.enableServer=false"
        echo "4lw.commands.whitelist=*"
        echo "extendedTypesEnabled=true"
        echo "localSessionsEnabled=false"
        echo "standaloneEnabled=false"
        echo "reconfigEnabled=false"
        echo "maxSessionTimeout=60000"
        echo "minSessionTimeout=1000"
        for m in 1 2 3; do
            echo "server.$m=127.0.0.1:$((BASE + m + 2)):$((BASE + m + 5))"
        done
        echo "${ZK_TEST_SERVER_PROPS:-}"
    } > "$d/zoo.cfg"
}

start_node() {
    local n="$1" d="$DIR/node$1" cp
    cp="$(classpath)"
    [ -f "$d/zoo.cfg" ] || write_config "$n"
    # Allow word splitting across space-separated JVM options.
    # shellcheck disable=SC2086
    nohup java -Dzookeeper.extendedTypesEnabled=true -Dzookeeper.log.dir="$d" \
        ${ZK_TEST_SERVER_JVM_OPTS:-} -cp "$cp" \
        org.apache.zookeeper.server.quorum.QuorumPeerMain "$d/zoo.cfg" \
        > "$d/server.out" 2>&1 &
    echo $! > "$d/server.pid"
}

# Verifies that PID `$1` is an active ZooKeeper `QuorumPeerMain` process before
# sending signals, preventing stale PID files from affecting unrelated processes.
is_zk_server() {
    local cmd
    cmd="$(ps -ww -p "$1" -o command= 2>/dev/null || true)"
    case "$cmd" in
    *org.apache.zookeeper.server.quorum.QuorumPeerMain*) return 0 ;;
    esac
    return 1
}

# Terminates peer `$1` and waits up to 10 seconds before escalating to `SIGKILL`.
stop_node() {
    local pidfile="$DIR/node$1/server.pid" pid
    [ -f "$pidfile" ] || return 0
    pid="$(cat "$pidfile")"
    if is_zk_server "$pid"; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 50); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.2
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
}

# Polls until peer `$1` reports `leader` or `follower` via `srvr`, failing
# immediately if the server process exits prematurely.
wait_serving() {
    local pidfile="$DIR/node$1/server.pid"
    for _ in $(seq 1 150); do
        if ! kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
            echo "node $1 exited; see $DIR/node$1/server.out" >&2
            return 1
        fi
        case "$(mode "$1")" in
        leader | follower) return 0 ;;
        esac
        sleep 0.2
    done
    echo "node $1 did not join; see $DIR/node$1/server.out" >&2
    return 1
}

case "$CMD" in
start)
    for n in 1 2 3; do write_config "$n"; done
    for n in 1 2 3; do start_node "$n"; done
    for n in 1 2 3; do
        if ! wait_serving "$n"; then
            for m in 1 2 3; do stop_node "$m"; done
            exit 1
        fi
    done
    echo "ZooKeeper ensemble on 127.0.0.1:$(client_port 1)-$(client_port 3)"
    ;;
stop)
    for n in 1 2 3; do stop_node "$n"; done
    ;;
start-node)
    start_node "$NODE"
    wait_serving "$NODE"
    ;;
stop-node)
    stop_node "$NODE"
    ;;
leader)
    for n in 1 2 3; do
        if [ "$(mode "$n")" = leader ]; then
            echo "$n"
            exit 0
        fi
    done
    exit 1
    ;;
*)
    echo "usage: $0 start|stop|stop-node|start-node|leader BASE ..." >&2
    exit 2
    ;;
esac
