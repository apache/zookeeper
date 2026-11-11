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

# The Rust target CPU of the zk-ffi staticlib. Autotools, CMake and Maven
# use this script, so the three builds make the same decision.
#
#   sh target-cpu.sh probe --choice=auto|no|CPU --rustc=RUSTC --target=TRIPLE
#                          --dir=DIR --cc=CC [--cflags=FLAGS]
#   sh target-cpu.sh run --cpu=CPU --policy=auto|explicit --target=TRIPLE
#                        -- CARGO [ARGS...]
#
# The probe prints two properties:
#   target_cpu=CPU   the CPU for -C target-cpu, or empty for no flag
#   why=TEXT         the reason for the decision
#
# The Rust code must have the same code generation baseline as the C
# code of the library. A C toolchain that makes x86-64-v2 code shows that
# the deployment already needs an x86-64-v2 CPU. Thus the choice auto
# gives x86-64-v2 when all of these conditions are true:
#   - the Rust target is x86_64;
#   - CC with FLAGS defines the predefined macros of x86-64-v2;
#   - rustc knows x86-64-v2 for the target;
#   - the rustflags of the user do not set a target CPU.
# The choice no never gives a flag. Another choice is a CPU name. rustc
# must know it for the target. Errors exit with status 2.
#
# The run task adds -C target-cpu=CPU to the rustflags that cargo uses,
# and then runs cargo. Cargo takes its rustflags from the first source
# of this list: CARGO_ENCODED_RUSTFLAGS, RUSTFLAGS, the target.<triple>
# and target.<cfg> rustflags of the configuration, build.rustflags. The
# task appends the flag to the same source, so the flags of the user stay.
# Some configurations do not let the task add the flag safely: a string
# rustflags value, or build.rustflags together with a target.<cfg> table.
# Then the policy auto adds no flag, and the policy explicit stops.
# The policy auto keeps a target CPU of the user. The policy explicit
# puts the flag last, so it has priority.
set -eu

me='target-cpu.sh'
fail() {
  echo "$me: $*" >&2
  exit 2
}

# Finds the rustflags source that cargo uses in the directory $1 for the
# target $2. It sets src (the name of the source), has_cpu (yes when the
# source sets a target CPU) and key (the configuration key for the flag).
# It sets conflict to a reason when no key can add the flag safely.
# A comment in a configuration file can give a false has_cpu. This keeps
# the flags of the user, so it is safe.
rustflags_source() {
  has_cpu=no
  key=build
  conflict=
  if [ "${CARGO_ENCODED_RUSTFLAGS+set}" = set ]; then
    src=CARGO_ENCODED_RUSTFLAGS
    case "$CARGO_ENCODED_RUSTFLAGS" in *target-cpu*) has_cpu=yes ;; esac
    return 0
  fi
  if [ "${RUSTFLAGS+set}" = set ]; then
    src=RUSTFLAGS
    case "$RUSTFLAGS" in *target-cpu*) has_cpu=yes ;; esac
    return 0
  fi
  src="the cargo configuration"
  has_triple=no has_cfg=no has_build=no
  tenv=CARGO_TARGET_$(echo "$2" | tr 'abcdefghijklmnopqrstuvwxyz.-' 'ABCDEFGHIJKLMNOPQRSTUVWXYZ__')_RUSTFLAGS
  tset='' tval=''
  eval "tset=\${$tenv+set} tval=\${$tenv-}"
  if [ "$tset" = set ]; then
    has_triple=yes
    case "$tval" in *target-cpu*) has_cpu=yes; src=$tenv ;; esac
  fi
  if [ "${CARGO_BUILD_RUSTFLAGS+set}" = set ]; then
    has_build=yes
    case "$CARGO_BUILD_RUSTFLAGS" in *target-cpu*) has_cpu=yes; src=CARGO_BUILD_RUSTFLAGS ;; esac
  fi
  # Cargo reads .cargo/config and .cargo/config.toml in the directory,
  # in each parent directory and in CARGO_HOME.
  files=
  d=$1
  while :; do
    files="$files
$d/.cargo/config
$d/.cargo/config.toml"
    [ "$d" = / ] && break
    d=$(dirname "$d")
  done
  home=${CARGO_HOME:-${HOME:-/nonexistent}/.cargo}
  files="$files
$home/config
$home/config.toml"
  old_ifs=$IFS
  IFS='
'
  for f in $files; do
    [ -f "$f" ] || continue
    if grep -v '^[[:space:]]*#' "$f" | grep 'target-cpu' >/dev/null; then
      has_cpu=yes
      src=$f
    fi
    # The kinds of rustflags keys in the file: string for a string value,
    # triple for this target, cfg for a cfg expression and build for the
    # build table. Cargo cannot merge a string value with the array of
    # the --config option.
    kinds=$(awk -v t="$2" '
        /^[ \t]*#/ { next }
        /^[ \t]*\[/ { tbl = $0; sub(/^[ \t]*\[+[ \t]*/, "", tbl); sub(/\].*$/, "", tbl); next }
        /rustflags[ \t"'\'']*=/ {
          k = $0; sub(/[ \t]*=.*/, "", k); gsub(/[ \t]/, "", k)
          full = (tbl == "") ? k : tbl "." k
          v = $0; sub(/^[^=]*=[ \t]*/, "", v)
          if (v ~ /^["'\'']/) print "string"
          if (full ~ /^target\./ && full ~ /cfg\(/) print "cfg"
          else if (full ~ /^target\./ && index(full, t)) print "triple"
          else if (full ~ /^build\./) print "build"
        }' "$f")
    case "$kinds" in *string*) conflict="$f gives rustflags as a string" ;; esac
    case "$kinds" in *triple*) has_triple=yes ;; esac
    case "$kinds" in *cfg*) has_cfg=yes ;; esac
    case "$kinds" in *build*) has_build=yes ;; esac
  done
  IFS=$old_ifs
  # Cargo uses the target rustflags in place of build.rustflags. With a
  # key for this triple, the flag goes into the target rustflags. A cfg
  # table can match the target or not. Without build.rustflags, a target
  # key is safe. With build.rustflags, a target key can remove the flags
  # of the user, so this is a conflict.
  if [ "$has_triple" = yes ]; then
    key=target
  elif [ "$has_cfg" = yes ]; then
    if [ "$has_build" = yes ]; then
      conflict=${conflict:-"the cargo configuration has build.rustflags and target.cfg rustflags"}
    else
      key=target
    fi
  fi
  return 0
}

# Prints the CPUs that rustc knows for the target $2.
target_cpus() {
  "$1" --print target-cpus --target "$2" 2>/dev/null |
    awk 'NR > 1 && /^[ \t]/ { print $1 }'
}

task=${1:-}
[ $# -gt 0 ] && shift
case "$task" in
  probe)
    choice=auto rustc=rustc target='' dir=$(pwd) cc=cc cflags=''
    for arg in "$@"; do
      case "$arg" in
        --choice=*) choice=${arg#--choice=} ;;
        --rustc=*) rustc=${arg#--rustc=} ;;
        --target=*) target=${arg#--target=} ;;
        --dir=*) dir=${arg#--dir=} ;;
        --cc=*) cc=${arg#--cc=} ;;
        --cflags=*) cflags=${arg#--cflags=} ;;
        *) fail "unknown option $arg" ;;
      esac
    done
    [ -n "$target" ] || fail "give --target=TRIPLE"
    target_cpu=
    case "$choice" in
      no|NO|off|OFF)
        why="the option is $choice" ;;
      auto|AUTO)
        tmp=$(mktemp -d "${TMPDIR:-/tmp}/zk-target-cpu.XXXXXX")
        trap 'rm -rf "$tmp"' EXIT
        cat >"$tmp/probe.c" <<'PROBE'
#if !defined(__SSE3__) || !defined(__SSSE3__) || !defined(__SSE4_1__) || \
    !defined(__SSE4_2__) || !defined(__POPCNT__) || \
    !defined(__GCC_HAVE_SYNC_COMPARE_AND_SWAP_16)
#error "the C compiler does not target x86-64-v2"
#endif
int zk_target_cpu_probe;
PROBE
        rustflags_source "$dir" "$target"
        # CC and the flags can hold more than one word, so they are not quoted.
        cc_ok=no
        # shellcheck disable=SC2086
        if $cc $cflags -c -o "$tmp/probe.o" "$tmp/probe.c" >/dev/null 2>&1; then
          cc_ok=yes
        fi
        if [ "${target%%-*}" != x86_64 ]; then
          why="the Rust target $target is not x86_64"
        elif [ "$cc_ok" != yes ]; then
          why="the C compiler ($cc${cflags:+ $cflags}) does not target x86-64-v2 or later"
        elif ! target_cpus "$rustc" "$target" | grep -F -x 'x86-64-v2' >/dev/null; then
          why="rustc does not know x86-64-v2 for $target"
        elif [ "$has_cpu" = yes ]; then
          why="$src already sets a target CPU"
        elif [ -n "$conflict" ]; then
          why="$conflict"
        else
          target_cpu=x86-64-v2
          why="the C compiler targets x86-64-v2 or later"
        fi
        ;;
      *)
        cpus=$(target_cpus "$rustc" "$target")
        [ -n "$cpus" ] || fail "rustc --print target-cpus --target $target failed"
        printf '%s\n' "$cpus" | grep -F -x -e "$choice" >/dev/null ||
          fail "rustc does not know the target CPU $choice for $target: give auto, no or a CPU of 'rustc --print target-cpus --target $target'"
        rustflags_source "$dir" "$target"
        [ -z "$conflict" ] ||
          fail "$conflict, so -C target-cpu=$choice cannot be added: write rustflags as an array in a build or target.<triple> table"
        target_cpu=$choice
        why="the option gives $choice"
        ;;
    esac
    echo "target_cpu=$target_cpu"
    echo "why=$why"
    ;;
  run)
    cpu='' policy=auto target=''
    while [ $# -gt 0 ]; do
      case "$1" in
        --cpu=*) cpu=${1#--cpu=} ;;
        --policy=*) policy=${1#--policy=} ;;
        --target=*) target=${1#--target=} ;;
        --) shift; break ;;
        *) fail "unknown option $1" ;;
      esac
      shift
    done
    [ $# -gt 0 ] || fail "give the cargo command after --"
    [ -n "$cpu" ] || exec "$@"
    [ -n "$target" ] || fail "give --target=TRIPLE"
    case "$policy" in auto|explicit) ;; *) fail "--policy=$policy: give auto or explicit" ;; esac
    rustflags_source "$(pwd)" "$target"
    if [ "$policy" = auto ] && [ "$has_cpu" = yes ]; then
      echo "$me: $src sets a target CPU, so cargo gets no -C target-cpu=$cpu" >&2
      exec "$@"
    fi
    if [ -n "$conflict" ]; then
      [ "$policy" = auto ] ||
        fail "$conflict, so -C target-cpu=$cpu cannot be added: write rustflags as an array in a build or target.<triple> table"
      echo "$me: $conflict, so cargo gets no -C target-cpu=$cpu" >&2
      exec "$@"
    fi
    flag=-Ctarget-cpu=$cpu
    case "$src" in
      CARGO_ENCODED_RUSTFLAGS)
        CARGO_ENCODED_RUSTFLAGS=${CARGO_ENCODED_RUSTFLAGS:+$CARGO_ENCODED_RUSTFLAGS$(printf '\037')}$flag
        export CARGO_ENCODED_RUSTFLAGS
        echo "$me: cargo gets $flag in CARGO_ENCODED_RUSTFLAGS" >&2
        exec "$@" ;;
      RUSTFLAGS)
        RUSTFLAGS=${RUSTFLAGS:+$RUSTFLAGS }$flag
        export RUSTFLAGS
        echo "$me: cargo gets $flag in RUSTFLAGS" >&2
        exec "$@" ;;
    esac
    if [ "$key" = target ]; then
      key="target.\"$target\".rustflags"
    else
      key=build.rustflags
    fi
    # The --config option goes after a +toolchain argument.
    cargo=$1
    shift
    if [ $# -gt 0 ]; then
      case "$1" in
        +*) tc=$1; shift
            echo "$me: cargo gets $flag in $key" >&2
            exec "$cargo" "$tc" --config "$key=[\"$flag\"]" "$@" ;;
      esac
    fi
    echo "$me: cargo gets $flag in $key" >&2
    exec "$cargo" --config "$key=[\"$flag\"]" "$@"
    ;;
  *)
    fail "give probe or run"
    ;;
esac
