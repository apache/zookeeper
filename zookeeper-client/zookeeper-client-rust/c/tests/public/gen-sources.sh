#!/bin/sh
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

# Generates patched CppUnit source files for the `zktest-public-mt` and
# `zktest-public-st` suites from the upstream `zookeeper-client-c/tests` tree
# without mutating the legacy sources in place. The generated copies apply three
# transformations:
# - Substitute build-specific client ports and certificate paths so concurrent
#   test runs do not collide on hard-coded ports.
# - Strip `CPPUNIT_TEST(...)` registrations listed in `omit-cases.txt` that rely
#   on private `zhandle_t` internals or unexported symbols.
# - When `QUORUM_BASE > 0`, relocate `TestReconfigServer` ports to a contiguous
#   nine-port block starting at `QUORUM_BASE` (three client ports, three quorum
#   ports, and three leader-election ports).
# Passing default port arguments preserves the upstream port assignments.
#
# Each output path maps by basename to the corresponding file in `TESTS_DIR`.
# Placing generated `.cc` files in the same directory as the patched
# `ZooKeeperQuorumServer.h` ensures quoted `#include` directives resolve the
# patched header first.
#
# Usage: gen-sources.sh TESTS_DIR PORT DOWN_PORT SSL_PORT CERT_DIR QUORUM_BASE OUTPUT...

set -eu

if [ $# -lt 7 ]; then
    echo "usage: $0 TESTS_DIR PORT DOWN_PORT SSL_PORT CERT_DIR QUORUM_BASE OUTPUT..." >&2
    exit 2
fi
tests=$1 port=$2 down=$3 ssl=$4 certs=$5 quorum=$6
shift 6
for v in "$port" "$down" "$ssl" "$quorum"; do
    case $v in
    '' | *[!0-9]*) echo "gen-sources.sh: $v is not a port number" >&2; exit 2 ;;
    esac
done
omit="$(dirname "$0")/omit-cases.txt"

# Construct a sed script to rewrite hard-coded ports and certificate paths and
# delete omitted `CPPUNIT_TEST` lines, escaping sed metacharacters in `CERT_DIR`.
certs_esc=$(printf '%s\n' "$certs" | sed 's/[\\|&]/\\&/g')
script="s|:22181|:$port|g
s|:22182|:$down|g
s|:22281|:$ssl|g
s|/tmp/certs/|$certs_esc/|g"
cases=$(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$omit")
for c in $cases; do
    script="$script
/^[[:space:]]*CPPUNIT_TEST($c);/d"
done
if [ "$quorum" -gt 0 ]; then
    script="$script
s|CLIENT_PORT_BASE = 4000;|CLIENT_PORT_BASE = $quorum;|
s|SERVER_PORT_BASE = 2000;|SERVER_PORT_BASE = $((quorum + 3));|
s|ELECTION_PORT_BASE = 3000;|ELECTION_PORT_BASE = $((quorum + 6));|"
fi

# Verify that every expected substitution matched at least one upstream source
# file so upstream test changes cannot silently revert port overrides or
# re-enable omitted internal tests.
found=
for out in "$@"; do
    in="$tests/$(basename "$out")"
    mkdir -p "$(dirname "$out")"
    sed -e "$script" "$in" >"$out.tmp"
    for c in $cases; do
        if grep -q "CPPUNIT_TEST($c);" "$in"; then
            found="$found $c"
        fi
    done
    if [ "$quorum" -gt 0 ] && [ "$(basename "$out")" = ZooKeeperQuorumServer.h ]; then
        for b in "CLIENT_PORT_BASE = $quorum;" "SERVER_PORT_BASE = $((quorum + 3));" \
            "ELECTION_PORT_BASE = $((quorum + 6));"; do
            if ! grep -q "$b" "$out.tmp"; then
                echo "gen-sources.sh: $in has no legacy line for $b" >&2
                exit 1
            fi
        done
    fi
    mv "$out.tmp" "$out"
done
for c in $cases; do
    case " $found " in
    *" $c "*) ;;
    *) echo "gen-sources.sh: no source registers the omitted case $c" >&2; exit 1 ;;
    esac
done
