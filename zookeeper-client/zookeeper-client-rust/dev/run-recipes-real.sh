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

# Runs the `apache-zookeeper-recipes` live server integration test suites
# (`real_infra`, `lock_real`, `latch_real`, `tree_cache_real`) against one or
# more ZooKeeper server versions (`head` and `3.9.6` by default). Also ensures
# the `3.5.10` release is cached for the `TreeCache` persistent watch fallback
# test.
#
# Usage: run-recipes-real.sh [VERSION ...]
#   VERSION   `head` or an official release version (default: `head 3.9.6`).
#
# Environment variables:
#   ZK_TEST_PORT_BASE       base TCP port for `BASE..BASE+9` (default: `23400`)
#   ZK_TEST_REQUIRE_SERVER  `1` to fail rather than skip when a server cannot
#                           start (default: `1`)
#   ZK_RECIPES_REPEAT       iterations per server version (default: `1`)
#   ZK_RECIPES_CARGO_ARGS   additional arguments forwarded to `cargo test`
#   ZK_TEST_SOURCE_ROOT     source tree root for the `head` server build

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE="$(cd "$HERE/.." && pwd)"
ROOT="${ZK_TEST_SOURCE_ROOT:-$(cd "$HERE/../../.." && pwd)}"
export ZK_TEST_PORT_BASE="${ZK_TEST_PORT_BASE:-23400}"
export ZK_TEST_REQUIRE_SERVER="${ZK_TEST_REQUIRE_SERVER:-1}"
REPEAT="${ZK_RECIPES_REPEAT:-1}"
case "$REPEAT" in
'' | *[!0-9]* | 0)
    echo "ZK_RECIPES_REPEAT must be a positive number: $REPEAT" >&2
    exit 2
    ;;
esac
if [ "$#" -eq 0 ]; then
    set -- head 3.9.6
fi

"$HERE/get-release-server.sh" 3.5.10
for v in "$@"; do
    if [ "$v" = head ]; then
        if [ ! -d "$ROOT/zookeeper-server/target/classes" ] ||
            [ ! -d "$ROOT/zookeeper-server/target/lib" ]; then
            echo "no HEAD server in $ROOT;" \
                "run mvn -DskipTests install -pl zookeeper-server -am" >&2
            exit 1
        fi
    else
        "$HERE/get-release-server.sh" "$v"
    fi
done

TESTS=(--test real_infra --test lock_real --test latch_real --test tree_cache_real)
FAILED=()
# Allow word splitting across optional Cargo arguments.
# shellcheck disable=SC2086
for v in "$@"; do
    for run in $(seq 1 "$REPEAT"); do
        echo "== zk-recipes real-server tests against $v," \
            "run $run of $REPEAT (ports $ZK_TEST_PORT_BASE+)"
        if [ "$v" = head ]; then
            if ! (cd "$WORKSPACE" && env -u ZK_TEST_SERVER_VERSION -u ZK_TEST_SERVER_HOME \
                cargo test --locked -p apache-zookeeper-recipes ${ZK_RECIPES_CARGO_ARGS:-} \
                "${TESTS[@]}" -- --ignored); then
                FAILED+=("$v#$run")
            fi
        else
            if ! (cd "$WORKSPACE" && env -u ZK_TEST_SERVER_HOME ZK_TEST_SERVER_VERSION="$v" \
                cargo test --locked -p apache-zookeeper-recipes ${ZK_RECIPES_CARGO_ARGS:-} \
                "${TESTS[@]}" -- --ignored); then
                FAILED+=("$v#$run")
            fi
        fi
    done
done

if [ "${#FAILED[@]}" -ne 0 ]; then
    echo "== zk-recipes real-server tests failed against: ${FAILED[*]}" >&2
    exit 1
fi
echo "== zk-recipes real-server tests passed against: $* ($REPEAT runs each)"
