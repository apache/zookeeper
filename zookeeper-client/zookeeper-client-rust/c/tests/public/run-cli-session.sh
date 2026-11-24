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

# Invariant: executing `cli_st` against the Rust single threaded library must
# produce output identical to the legacy C library after normalizing ephemeral
# session IDs, zxids, and timestamps. Each run launches an isolated server
# instance so both executions observe identical initial server state.
#
# Usage: run-cli-session.sh [options]
#   --legacy CLI          path to the `cli_st` binary for the legacy C run
#   --legacy-libdir DIR   dynamic loader library directory for the legacy run
#   --rust CLI            path to the `cli_st` binary for the Rust run
#   --rust-libdir DIR     dynamic loader library directory for the Rust run
#   --expected DIR        compare each run's normalized output against DIR
#   --port N              server client port (default: ZKTEST_PORT or 22181)
#   --workdir DIR         working directory (default: $TMPDIR/zk-cli-session-PORT)
#   --script FILE         session script path (default: ./cli-session.txt)
#   --wait SECONDS        per-directive wait timeout in seconds (default: 15)
# At least one of `--legacy` or `--rust` must be specified; when both are
# provided, their normalized outputs are diffed.
#
# `ZKTEST_REPO` specifies the ZooKeeper repository root containing the built
# server (default: the enclosing tree of this script). All spawned server and
# client processes are cleaned up on exit.

set -euo pipefail

# Resolve repository paths: RCDIR is the Rust C compatibility root and RDIR is
# the Rust workspace root.
HERE="$(cd "$(dirname "$0")" && pwd)"
RCDIR="$(cd "$HERE/../.." && pwd)"
RDIR="$(cd "$RCDIR/.." && pwd)"
REPO="$(cd "${ZKTEST_REPO:-$RDIR/../..}" && pwd)"
DEFAULT_SERVER_SCRIPT="$REPO/zookeeper-client/zookeeper-client-rust/dev/test-server.sh"
SERVER_SCRIPT="${ZKTEST_SERVER_SCRIPT:-$DEFAULT_SERVER_SCRIPT}"

LEGACY='' LEGACY_LIB='' RUST='' RUST_LIB='' EXPECTED=''
PORT="${ZKTEST_PORT:-22181}"
WORK=''
SCRIPT="$HERE/cli-session.txt"
WAIT=15

while [[ $# -gt 0 ]]; do
    case $1 in
    --legacy) LEGACY=$2; shift 2 ;;
    --legacy-libdir) LEGACY_LIB=$2; shift 2 ;;
    --rust) RUST=$2; shift 2 ;;
    --rust-libdir) RUST_LIB=$2; shift 2 ;;
    --expected) EXPECTED=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    --workdir) WORK=$2; shift 2 ;;
    --script) SCRIPT=$2; shift 2 ;;
    --wait) WAIT=$2; shift 2 ;;
    *) echo "run-cli-session.sh: unknown argument $1" >&2; exit 2 ;;
    esac
done
if [[ -z $LEGACY && -z $RUST ]]; then
    echo "run-cli-session.sh: give --legacy CLI, --rust CLI or both" >&2
    exit 2
fi
WORK="${WORK:-${TMPDIR:-/tmp}/zk-cli-session-$PORT}"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"
HOST="127.0.0.1:$PORT"

CLI_PID=''
SERVER_DIR=''
cleanup() {
    if [[ -n $CLI_PID ]]; then
        kill -9 "$CLI_PID" 2>/dev/null || true
        wait "$CLI_PID" 2>/dev/null || true
    fi
    exec 3>&- 2>/dev/null || true
    if [[ -n $SERVER_DIR ]]; then
        "$SERVER_SCRIPT" stop "$PORT" "$SERVER_DIR" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

fail() {
    echo "run-cli-session.sh: $*" >&2
    exit 1
}

# Reports the `libzookeeper` shared object resolved by the dynamic loader for
# `cli`. `DYLD_*` variables are exported inside a subshell and passed directly
# to `exec "$cli"` because macOS SIP strips `DYLD_*` across `/usr/bin/env`.
loaded_library() {
    local lib=$1 cli=$2
    if [[ $(uname -s) == Darwin ]]; then
        (
            [[ -z $lib ]] || export DYLD_LIBRARY_PATH="$lib"
            export DYLD_PRINT_LIBRARIES=1
            exec "$cli" </dev/null
        ) 2>&1 | sed -n 's/^dyld\[[0-9]*\]: <[^>]*> //p' | grep 'libzookeeper' || true
    else
        (
            [[ -z $lib ]] || export LD_LIBRARY_PATH="$lib"
            exec ldd "$cli"
        ) | grep 'libzookeeper' || true
    fi
}

# Counts lines matching extended regex `ere` in `file` starting at line `start`.
count_from() {
    local file=$1 start=$2 ere=$3
    tail -n "+$start" "$file" | grep -cE -- "$ere" || true
}

# Polls `file` at 50 ms intervals up to `WAIT` seconds until `n` lines from
# `start` onward match `ere`.
wait_for() {
    local file=$1 start=$2 n=$3 ere=$4 polls=$((WAIT * 20))
    while ((polls > 0)); do
        if (($(count_from "$file" "$start" "$ere") >= n)); then
            return 0
        fi
        if ! kill -0 "$CLI_PID" 2>/dev/null; then
            fail "cli_st ended before '$ere' (see $file)"
        fi
        sleep 0.05
        polls=$((polls - 1))
    done
    fail "no '$ere' within $WAIT s (see $file)"
}

# Normalizes run-specific fields (session IDs, zxids, timestamps, elapsed times,
# and log prefixes containing timestamps, PIDs, and TIDs).
normalize() {
    sed -E \
        -e 's/(Got a new session id: )0x[0-9a-f]+/\1SESSION/' \
        -e 's/(session Id = )[0-9a-f]+/\1SESSION/' \
        -e 's/(ephemeralOwner = )[1-9a-f][0-9a-f]*/\1SESSION/' \
        -e 's/(c|m)zxid=[0-9a-f]+/\1zxid=ZXID/' \
        -e 's/^(	ctime = |	mtime=).*/\1TIME/' \
        -e 's/^time = -?[0-9]+ msec$/time = MSEC msec/' \
        -e 's/^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:,]+:[0-9]+\(0x[0-9a-f]+\):/LOGTIME:PID(TID):/' \
        "$1"
}

# Executes a single scripted session against `cli` with loader path `lib`,
# writing raw and normalized (`*.norm`) streams under `$WORK/$label`.
run_session() {
    local label=$1 cli=$2 lib=$3 dir="$WORK/$1" line op arg n start=1 sending=0 aux polls
    [[ -x $cli ]] || fail "$cli is not executable"
    rm -rf "$dir"
    mkdir -p "$dir"
    echo "run-cli-session.sh: $label: $cli${lib:+ with $lib}, server $HOST"
    echo "run-cli-session.sh: $label: library $(loaded_library "$lib" "$cli")"

    SERVER_DIR="$dir/server"
    "$SERVER_SCRIPT" start "$PORT" "$SERVER_DIR" >"$dir/server.log" 2>&1 ||
        fail "the server did not start (see $dir/server.log)"

    # Launch `cli_st` via `exec` inside a subshell so `CLI_PID` tracks the process directly.
    mkfifo "$dir/stdin"
    (
        if [[ -n $lib ]]; then
            export DYLD_LIBRARY_PATH="$lib" LD_LIBRARY_PATH="$lib"
        fi
        exec "$cli" "$HOST" <"$dir/stdin" >"$dir/cli.out" 2>"$dir/cli.err"
    ) &
    CLI_PID=$!
    exec 3>"$dir/stdin"
    : >"$dir/aux.out"

    while IFS= read -r line || [[ -n $line ]]; do
        [[ -z $line || $line == \#* ]] && continue
        op=${line%% *}
        arg=${line#* }
        case $op in
        '>')
            # The first command following a wait begins a new group; subsequent
            # pattern waits scan stderr lines emitted from this offset onward.
            if ((sending == 0)); then
                start=$(($(wc -l <"$dir/cli.err") + 1))
                sending=1
            fi
            printf '%s\n' "$arg" >&3
            ;;
        '!')
            start=$(($(wc -l <"$dir/cli.err") + 1))
            sending=1
            echo "! $arg" >>"$dir/aux.out"
            (
                if [[ -n $lib ]]; then
                    export DYLD_LIBRARY_PATH="$lib" LD_LIBRARY_PATH="$lib"
                fi
                exec "$cli" "$HOST" "cmd:$arg" </dev/null >>"$dir/aux.out" 2>&1
            ) &
            aux=$!
            polls=$((WAIT * 20))
            while kill -0 "$aux" 2>/dev/null && ((polls > 0)); do
                sleep 0.05
                polls=$((polls - 1))
            done
            if kill -0 "$aux" 2>/dev/null; then
                kill -9 "$aux" 2>/dev/null || true
                fail "the batch client '$arg' did not end within $WAIT s"
            fi
            wait "$aux" || fail "the batch client '$arg' failed"
            ;;
        '?'*)
            n=${op#\?}
            wait_for "$dir/cli.err" "$start" "${n:-1}" "$arg"
            sending=0
            ;;
        *) fail "bad directive in $SCRIPT: $line" ;;
        esac
    done <"$SCRIPT"

    # The script concludes with `quit`; closing stdin acts as a fallback EOF.
    exec 3>&-
    polls=$((WAIT * 20))
    while kill -0 "$CLI_PID" 2>/dev/null && ((polls > 0)); do
        sleep 0.05
        polls=$((polls - 1))
    done
    if kill -0 "$CLI_PID" 2>/dev/null; then
        fail "$label: cli_st did not end within $WAIT s after quit"
    fi
    wait "$CLI_PID" || fail "$label: cli_st exit status $?"
    CLI_PID=

    "$SERVER_SCRIPT" stop "$PORT" "$SERVER_DIR" >>"$dir/server.log" 2>&1 || true
    SERVER_DIR=

    for f in cli.out cli.err aux.out; do
        normalize "$dir/$f" >"$dir/$f.norm"
    done
}

# Diffs normalized output streams between two session directories.
compare() {
    local a=$1 b=$2 f rc=0
    for f in cli.out cli.err aux.out; do
        if ! diff -u "$a/$f.norm" "$b/$f.norm"; then
            rc=1
        fi
    done
    return $rc
}

RUNS=()
if [[ -n $LEGACY ]]; then
    run_session legacy "$LEGACY" "$LEGACY_LIB"
    RUNS+=("$WORK/legacy")
fi
if [[ -n $RUST ]]; then
    run_session rust "$RUST" "$RUST_LIB"
    RUNS+=("$WORK/rust")
fi

status=0
if [[ ${#RUNS[@]} -eq 2 ]]; then
    if compare "${RUNS[0]}" "${RUNS[1]}"; then
        echo "run-cli-session.sh: legacy and rust: same output"
    else
        echo "run-cli-session.sh: legacy and rust: the output differs" >&2
        status=1
    fi
fi
if [[ -n $EXPECTED ]]; then
    for r in "${RUNS[@]}"; do
        if compare "$EXPECTED" "$r"; then
            echo "run-cli-session.sh: $(basename "$r") and $EXPECTED: same output"
        else
            echo "run-cli-session.sh: $(basename "$r") and $EXPECTED: the output differs" >&2
            status=1
        fi
    done
fi
exit $status
