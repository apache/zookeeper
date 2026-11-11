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

# Starts or stops a standalone ZooKeeper server on `127.0.0.1` for Rust and C
# integration tests (`zk-client`, `zk-ffi`, `zk-recipes`, `c/tests/conformance`,
# and `c/tests/public`).
#
# Usage: test-server.sh start|stop [PORT] [DATADIR]
#   PORT     client listener port (default: `22181`)
#   DATADIR  server working directory (default: `${TMPDIR:-/tmp}/zk-rust-test-PORT`)
#
# Environment variables:
#   ZK_TEST_SERVER_PROPS     additional newline-separated `zoo.cfg` properties
#   ZK_TEST_SERVER_JVM_OPTS  additional JVM flags passed to `java`
#   ZK_TEST_SERVER_HOME      path to an extracted binary release distribution
#   ZK_TEST_SERVER_VERSION   release version under `ZK_TEST_SERVERS_DIR`
#                            (default: `/tmp/zk-servers`)
#   ZK_TEST_SOURCE_ROOT      root of the ZooKeeper source tree for `HEAD` builds

set -euo pipefail
ROOT="${ZK_TEST_SOURCE_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}"
CMD="${1:-start}"
PORT="${2:-22181}"
DIR="${3:-${TMPDIR:-/tmp}/zk-rust-test-$PORT}"
PIDFILE="$DIR/server.pid"

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

case "$CMD" in
start)
    mkdir -p "$DIR/data"
    {
        echo "tickTime=2000"
        echo "dataDir=$DIR/data"
        echo "clientPort=$PORT"
        echo "admin.enableServer=false"
        echo "4lw.commands.whitelist=*"
        echo "extendedTypesEnabled=true"
        echo "localSessionsEnabled=false"
        echo "maxSessionTimeout=60000"
        echo "minSessionTimeout=1000"
        echo "${ZK_TEST_SERVER_PROPS:-}"
    } > "$DIR/zoo.cfg"
    CP="$ROOT/zookeeper-server/target/classes:$ROOT/zookeeper-server/target/lib/*"
    # Override classpath when running against a downloaded binary release.
    if [ -z "${ZK_TEST_SERVER_HOME:-}" ] && [ -n "${ZK_TEST_SERVER_VERSION:-}" ]; then
        VER="$ZK_TEST_SERVER_VERSION"
        ZK_TEST_SERVER_HOME="${ZK_TEST_SERVERS_DIR:-/tmp/zk-servers}/$VER/apache-zookeeper-$VER-bin"
    fi
    if [ -n "${ZK_TEST_SERVER_HOME:-}" ]; then
        if [ ! -d "$ZK_TEST_SERVER_HOME/lib" ]; then
            echo "No release server in $ZK_TEST_SERVER_HOME; run get-release-server.sh" >&2
            exit 1
        fi
        CP="$ZK_TEST_SERVER_HOME/lib/*:$ZK_TEST_SERVER_HOME/conf"
    fi
    # Allow word splitting across space-separated JVM options.
    # shellcheck disable=SC2086
    nohup java -Dzookeeper.extendedTypesEnabled=true -Dzookeeper.log.dir="$DIR" \
        ${ZK_TEST_SERVER_JVM_OPTS:-} -cp "$CP" \
        org.apache.zookeeper.server.quorum.QuorumPeerMain "$DIR/zoo.cfg" \
        > "$DIR/server.out" 2>&1 &
    echo $! > "$PIDFILE"
    for _ in $(seq 1 100); do
        # Fail immediately if the JVM terminates during startup (e.g., port conflict).
        if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
            echo "ZooKeeper exited at start; see $DIR/server.out" >&2
            rm -f "$PIDFILE"
            exit 1
        fi
        # Wait for `ruok` (`imok`) and, for standalone servers, `srvr` (`Mode:`)
        # so the request processor pipeline is initialized before clients connect.
        if echo ruok | nc -w 1 127.0.0.1 "$PORT" 2>/dev/null | grep -q imok &&
            { grep -q '^server\.' "$DIR/zoo.cfg" ||
              echo srvr | nc -w 1 127.0.0.1 "$PORT" 2>/dev/null | grep -q '^Mode: '; }; then
            echo "ZooKeeper listening on 127.0.0.1:$PORT (pid $(cat "$PIDFILE"))"
            exit 0
        fi
        sleep 0.2
    done
    echo "ZooKeeper failed to start; see $DIR/server.out" >&2
    exit 1
    ;;
stop)
    # Await process termination (up to 10 seconds before SIGKILL) so an
    # immediate restart on the same port cannot race with the old instance.
    if [ -f "$PIDFILE" ]; then
        PID="$(cat "$PIDFILE")"
        if is_zk_server "$PID"; then
            kill "$PID" 2>/dev/null || true
            for _ in $(seq 1 50); do
                kill -0 "$PID" 2>/dev/null || break
                sleep 0.2
            done
            kill -9 "$PID" 2>/dev/null || true
        fi
        rm -f "$PIDFILE"
    fi
    ;;
*)
    echo "usage: $0 start|stop [PORT] [DATADIR]" >&2
    exit 2
    ;;
esac
