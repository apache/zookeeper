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

# ZooKeeper server lifecycle wrapper for the `zktest-public-mt` and
# `zktest-public-st` suites. Implements the command-line interface expected by
# `zookeeper-client-c/tests/zkServer.sh` while delegating process management to
# `zookeeper-client-rust/dev/test-server.sh`.
#
# Key differences from upstream `tests/zkServer.sh`:
# - Terminates only the server instance recorded in its own state directory,
#   never killing arbitrary processes via `/tmp/zk.pid` or port scans.
# - Isolates all runtime state under `ZKTEST_SERVER_DIR` rather than `/tmp`.
# - Configures TLS on `ZKTEST_SSL_PORT` using certificates in `ZKTEST_CERT_DIR`,
#   invoking `gencerts.sh` only when `server.jks` is absent so callers may
#   supply pre-generated key material.
# - Blocks until the server responds to readiness probes across plain, TLS,
#   read-only, and SASL-enforced modes.
#
# Usage: zkServer.sh startClean|start|startCleanReadOnly|stop [HOST:PORT]
#        zkServer.sh startRequireSASLAuth [JAAS_CONF] [READ_ONLY]
# The optional `HOST:PORT` positional argument is ignored for compatibility
# with upstream `tests/zkServer.sh`.
#
# Environment variables:
#   ZOOPORT              client port (default: 22181)
#   ZKMAXCNXNS           maxClientCnxns (default: 100)
#   EXTRA_JVM_ARGS       additional JVM flags
#   ZKTEST_SERVER_DIR    instance state directory
#                        (default: ${TMPDIR:-/tmp}/zktest-public-$ZOOPORT)
#   ZKTEST_RO_PORT_BASE  starting port for the six quorum/election ports of the
#                        read-only ensemble (0 or unset uses legacy 22881/33881)
#   ZKTEST_SERVER_SCRIPT path to `test-server.sh` (default: in-tree script)
#   ZKTEST_SSL_PORT      secure client port (0 or unset disables TLS)
#   ZKTEST_CERT_DIR      certificate directory containing `server.jks`,
#                        `servertrust.jks`, and client PEM files (default: /tmp/certs)
#   CLIENT_AUTH          `ssl.clientAuth` mode: need, want, or none (default: need)
#
# Note: `testSSL` requires a CA certificate chain whose leaf omits the Authority
# Key Identifier (AKID) extension. Because OpenSSL 3.2+ unconditionally adds
# AKID, pre-generate certificates in `ZKTEST_CERT_DIR` using LibreSSL or
# OpenSSL 3.1 or earlier (`zookeeper-client-c/ssl/gencerts.sh`).

set -euo pipefail

# Resolve repository paths: RCDIR is the Rust C compatibility root, RDIR is the
# Rust workspace root, and CDIR is the legacy C client tree (overridable via
# ZK_C_DIR).
HERE="$(cd "$(dirname "$0")" && pwd)"
RCDIR="$(cd "$HERE/../.." && pwd)"
RDIR="$(cd "$RCDIR/.." && pwd)"
CDIR="$(cd "${ZK_C_DIR:-$RDIR/../zookeeper-client-c}" && pwd)"
SERVER_SCRIPT="${ZKTEST_SERVER_SCRIPT:-$RDIR/dev/test-server.sh}"
ZOOPORT="${ZOOPORT:-22181}"
ZKMAXCNXNS="${ZKMAXCNXNS:-100}"
SSL_PORT="${ZKTEST_SSL_PORT:-0}"
CERT_DIR="${ZKTEST_CERT_DIR:-/tmp/certs}"
CLIENT_AUTH="${CLIENT_AUTH:-need}"
DIR="${ZKTEST_SERVER_DIR:-${TMPDIR:-/tmp}/zktest-public-$ZOOPORT}"
LOG="$DIR/wrapper.log"

if [[ $# -lt 1 ]]; then
    echo "usage: $0 startClean|start|startCleanReadOnly" \
        "|startRequireSASLAuth [jaasConf] [readOnly]|stop" >&2
    exit 2
fi
mkdir -p "$DIR"

# The read-only test configures a 3-node quorum where only server.1 is started.
# Upstream legacy ports default to 22881-22883 (quorum) and 33881-33883 (election).
if [[ ${ZKTEST_RO_PORT_BASE:-0} != 0 ]]; then
    RO_QUORUM=$ZKTEST_RO_PORT_BASE
    RO_ELECTION=$((ZKTEST_RO_PORT_BASE + 3))
else
    RO_QUORUM=22881
    RO_ELECTION=33881
fi

stop_server() {
    "$SERVER_SCRIPT" stop "$ZOOPORT" "$DIR" >>"$LOG" 2>&1
}

# Emits baseline `zoo.cfg` properties matching upstream `tests/zoo.cfg` (with
# `tickTime=500` and session timeout bounds of 2..20 ticks), appending Netty TLS
# settings when `SSL_PORT` is enabled.
base_props() {
    printf '%s\n' "tickTime=500" "initLimit=10" "syncLimit=5" \
        "maxClientCnxns=$ZKMAXCNXNS" "localSessionsEnabled=true" \
        "minSessionTimeout=1000" "maxSessionTimeout=10000" \
        "serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory"
    if [[ $SSL_PORT != 0 ]]; then
        printf '%s\n' "secureClientPort=$SSL_PORT" "ssl.clientAuth=$CLIENT_AUTH" \
            "ssl.keyStore.location=$CERT_DIR/server.jks" \
            "ssl.keyStore.password=password" \
            "ssl.trustStore.location=$CERT_DIR/servertrust.jks" \
            "ssl.trustStore.password=password"
    fi
}

# Generates test certificates via `gencerts.sh` if `CERT_DIR/server.jks` is
# absent. Existing keystores are preserved. If certificate generation fails,
# TLS is disabled so non-TLS test cases can still proceed.
ensure_certs() {
    [[ $SSL_PORT != 0 ]] || return 0
    [[ -f $CERT_DIR/server.jks ]] && return 0
    mkdir -p "$CERT_DIR"
    cp "$CDIR/ssl/gencerts.sh" "$CERT_DIR/"
    if ! (cd "$CERT_DIR" && ./gencerts.sh tests.zookeeper.apache.org \
        >gencerts.stdout 2>gencerts.stderr); then
        echo "zkServer.sh: gencerts.sh failed; disabling secure port" \
            "(see $CERT_DIR/gencerts.stderr)" >&2
        rm -f "$CERT_DIR/server.jks"
        SSL_PORT=0
    fi
}

read_only_props() {
    local i
    for i in 1 2 3; do
        echo "server.$i=localhost:$((RO_QUORUM + i - 1)):$((RO_ELECTION + i - 1))"
    done
}

# Polls a 4-letter word command (`cmd`) up to 150 times (0.2 s interval) until
# the response contains `want`.
wait_4lw() {
    local cmd=$1 want=$2 n
    for n in $(seq 1 150); do
        if echo "$cmd" | nc -w 1 127.0.0.1 "$ZOOPORT" 2>/dev/null | grep -q "$want"; then
            return 0
        fi
        sleep 0.2
    done
    echo "zkServer.sh: no '$want' reply to '$cmd' on port $ZOOPORT; see $DIR" >&2
    return 1
}

start_server() {
    local props=$1 jvm=$2
    if nc -z 127.0.0.1 "$ZOOPORT" 2>/dev/null; then
        echo "zkServer.sh: another process listens on port $ZOOPORT" >&2
        return 1
    fi
    ZK_TEST_SERVER_PROPS="$props" ZK_TEST_SERVER_JVM_OPTS="$jvm" \
        "$SERVER_SCRIPT" start "$ZOOPORT" "$DIR" >>"$LOG" 2>&1 || {
        echo "zkServer.sh: the server did not start; see $DIR/server.out" >&2
        return 1
    }
    # `test-server.sh` waits only for the plaintext client port; wait for the
    # Netty TLS listener as well when `SSL_PORT` is configured.
    if [[ $SSL_PORT != 0 ]]; then
        local n
        for n in $(seq 1 100); do
            nc -z 127.0.0.1 "$SSL_PORT" 2>/dev/null && break
            sleep 0.1
        done
    fi
    echo -n " ZooKeeper server started"
}

# Default JVM flags matching upstream `tests/zkServer.sh`.
JVM="${EXTRA_JVM_ARGS:-} -Dzookeeper.extendedTypesEnabled=true"
JVM="$JVM -Dznode.container.checkIntervalMs=100"

# Stop any prior server instance managed in `DIR` before executing the requested command.
stop_server
if [[ $1 != stop ]]; then
    ensure_certs
fi

case $1 in
startClean | start)
    if [[ $1 == startClean ]]; then
        rm -rf "$DIR/data"
    fi
    start_server "$(base_props)" "$JVM"
    ;;
startCleanReadOnly)
    rm -rf "$DIR/data"
    mkdir -p "$DIR/data"
    echo 1 >"$DIR/data/myid"
    start_server "$(base_props; read_only_props)" "-Dreadonlymode.enabled=true $JVM"
    wait_4lw isro ro
    ;;
startRequireSASLAuth)
    sasl_prov="org.apache.zookeeper.server.auth.SASLAuthenticationProvider"
    sasl="-Dzookeeper.sessionRequireClientSASLAuth=true"
    sasl="$sasl -Dzookeeper.authProvider.1=$sasl_prov"
    sasl="$sasl -Dzookeeper.fips-mode=false"
    if [[ -n ${2:-} ]]; then
        # Resolve the JAAS configuration path relative to the caller's working directory.
        jaas="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
        sasl="$sasl -Djava.security.auth.login.config=$jaas"
    fi
    if [[ -n ${3:-} ]]; then
        mkdir -p "$DIR/data"
        echo 1 >"$DIR/data/myid"
        start_server "$(base_props; read_only_props)" "-Dreadonlymode.enabled=true $sasl $JVM"
        wait_4lw isro ro
    else
        start_server "$(base_props)" "$sasl $JVM"
    fi
    ;;
stop) ;;
*)
    echo "Unknown command $1" >&2
    exit 2
    ;;
esac
