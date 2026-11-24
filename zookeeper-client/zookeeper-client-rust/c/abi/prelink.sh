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

# Joins the shim archive of one flavor and the zk-ffi staticlib into one
# relocatable object. Only the keep list stays global in the
# object. Make and CMake both run this script.
#
# Usage:
#   prelink.sh [--cc CC] [--cflags FLAGS] [--ld LD] [--objcopy OBJCOPY]
#              [--nm NM] [--host-os OS] [--arch ARCH]
#              --shim LIBZKS.a --rust LIBZK_FFI.a --keep FILE
#              (--out NAME | --object FILE)
#
#   --cflags FLAGS the C compiler flags of the target, as one word. The
#                  script compiles and links its helper objects with them
#   --keep FILE    the keep list, one name for each line, without a prefix
#                  (gen-exports.awk -v fmt=keep)
#   --out NAME     write NAME.o, .libs/NAME.o and the libtool object NAME.lo
#   --object FILE  write FILE only (CMake)
#   --host-os OS   the configure host_os; the default is from uname
#   --arch ARCH    the Mach-O architecture; the default is that of CC
#
# The script stops with an error when a post-check fails:
#   - the global defined symbols must equal the keep list;
#   - no undefined reference to a Rust, zkrs_*, zks_* or OpenSSL symbol
#     can remain;
#   - on ELF, a trial shared library of the object has no dynamic
#     OPENSSL_memory_* symbol.

set -eu

CC=${CC:-cc}
CFLAGS=
LD=${LD:-ld}
OBJCOPY=${OBJCOPY:-objcopy}
NM=${NM:-nm}
HOST_OS=
ARCH=
SHIM=
RUST=
KEEP=
OUT=
OBJECT=

die() {
    echo "prelink.sh: $*" >&2
    exit 1
}

need_arg() {
    test $# -ge 2 || die "$1 needs a value"
}

while test $# -gt 0; do
    case $1 in
        --cc) need_arg "$@"; CC=$2; shift 2 ;;
        --cflags) need_arg "$@"; CFLAGS=$2; shift 2 ;;
        --ld) need_arg "$@"; LD=$2; shift 2 ;;
        --objcopy) need_arg "$@"; OBJCOPY=$2; shift 2 ;;
        --nm) need_arg "$@"; NM=$2; shift 2 ;;
        --host-os) need_arg "$@"; HOST_OS=$2; shift 2 ;;
        --arch) need_arg "$@"; ARCH=$2; shift 2 ;;
        --shim) need_arg "$@"; SHIM=$2; shift 2 ;;
        --rust) need_arg "$@"; RUST=$2; shift 2 ;;
        --keep) need_arg "$@"; KEEP=$2; shift 2 ;;
        --out) need_arg "$@"; OUT=$2; shift 2 ;;
        --object) need_arg "$@"; OBJECT=$2; shift 2 ;;
        *) die "unknown option $1" ;;
    esac
done

test -n "$SHIM" || die "--shim is required"
test -n "$RUST" || die "--rust is required"
test -n "$KEEP" || die "--keep is required"
test -f "$SHIM" || die "$SHIM does not exist"
test -f "$RUST" || die "$RUST does not exist"
test -f "$KEEP" || die "$KEEP does not exist"
if test -n "$OUT" && test -n "$OBJECT"; then
    die "give --out or --object, not both"
fi
test -n "$OUT$OBJECT" || die "--out or --object is required"
test -n "$HOST_OS" || HOST_OS=$(uname -s | tr 'A-Z' 'a-z')

if test -n "$OUT"; then
    OUTDIR=$(dirname "$OUT")
    OUTBASE=$(basename "$OUT")
    mkdir -p "$OUTDIR/.libs"
    RESULT="$OUTDIR/.libs/$OUTBASE.o"
else
    RESULT=$OBJECT
fi

TMP="$RESULT.tmp"
rm -rf "$TMP"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT
LC_ALL=C sort -u "$KEEP" >"$TMP/keep.sorted"

# The Mach-O symbol table adds "_" to every C name. Windows hosts use PE.
# Other hosts use ELF.
ELF=no
case $HOST_OS in
    darwin*)
        MACHO=yes
        PREFIX=_
        ;;
    *mingw* | *cygwin* | *msys*)
        MACHO=no
        PREFIX=
        ;;
    *)
        MACHO=no
        PREFIX=
        ELF=yes
        ;;
esac

if test "$MACHO" = yes; then
    sed 's/^/_/' "$TMP/keep.sorted" >"$TMP/keep.exp"
    if test -n "$ARCH"; then
        set -- -arch "$ARCH"
    else
        set --
    fi
    # The partial link loads the whole shim archive. From the staticlib, it
    # loads only the members that the shim needs. The export list makes
    # every other global a private extern.
    $CC -r -nostdlib "$@" -Wl,-exported_symbols_list,"$TMP/keep.exp" \
        -Wl,-force_load,"$SHIM" "$RUST" -o "$RESULT"
else
    # On ELF and PE, the partial link keeps all globals. objcopy then makes
    # every name outside the keep list local.
    $LD -r -o "$RESULT" --whole-archive "$SHIM" --no-whole-archive "$RUST"
    # The aws-lc C code on ELF has weak references to optional allocator
    # hooks (OPENSSL_memory_*). Another image could define them and thus
    # replace the allocator of the library. A small object refers to each
    # weak undefined OpenSSL name with hidden visibility. The final link
    # then binds each name to zero, as the aws-lc code does on other targets.
    # The object uses the target flags, so a cross build can link it.
    if test "$ELF" = yes; then
        $NM -u "$RESULT" | awk '$1 == "w" && $2 ~ /^(OPENSSL_|CRYPTO_)/ { print $2 }' |
            LC_ALL=C sort -u >"$TMP/hidden"
    fi
    if test -s "$TMP/hidden"; then
        awk '{ print "extern char " $1 "[] __attribute__((weak, visibility(\"hidden\")));" }
            END { print "static const void *const zkrs_hidden[] __attribute__((used)) = {" }' \
            "$TMP/hidden" >"$TMP/hidden.c"
        awk '{ print "    " $1 "," } END { print "};" }' "$TMP/hidden" >>"$TMP/hidden.c"
        # shellcheck disable=SC2086
        $CC $CFLAGS -fno-lto -c -fPIC -o "$TMP/hidden.o" "$TMP/hidden.c"
        $LD -r -o "$RESULT" "$TMP/hidden.o" --whole-archive "$SHIM" --no-whole-archive "$RUST"
    fi
    $OBJCOPY --keep-global-symbols="$TMP/keep.sorted" "$RESULT"
fi

# Post-check 1: the global defined symbols equal the keep list.
$NM -g "$RESULT" | awk -v p="$PREFIX" '
    NF >= 3 && $(NF - 1) ~ /^[A-TV-Z]$/ {
        s = $NF
        if (p != "" && substr(s, 1, 1) == p) s = substr(s, 2)
        print s
    }' | LC_ALL=C sort -u >"$TMP/globals.all"
# On Mach-O, the export list can make a common symbol a private extern.
# Such a symbol is local to the linkage unit, as the other private externs
# are, but "nm -g" lists it as a "C" symbol. The aws-lc C code has such
# symbols. The check thus removes the private externs that "nm -m" shows.
: >"$TMP/private"
if test "$MACHO" = yes; then
    $NM -g -m "$RESULT" 2>/dev/null | awk -v p="$PREFIX" '
        / private external / {
            s = $NF
            if (p != "" && substr(s, 1, 1) == p) s = substr(s, 2)
            print s
        }' | LC_ALL=C sort -u >"$TMP/private"
fi
LC_ALL=C comm -23 "$TMP/globals.all" "$TMP/private" >"$TMP/globals"
if ! diff "$TMP/keep.sorted" "$TMP/globals" >"$TMP/globals.diff"; then
    cat "$TMP/globals.diff" >&2
    rm -f "$RESULT"
    die "the global symbols of $RESULT differ from $KEEP (< keep only, > object only)"
fi

# Post-check 2: no undefined reference to a private or OpenSSL symbol.
$NM -u "$RESULT" | awk -v p="$PREFIX" '
    {
        s = $NF
        if (p != "" && substr(s, 1, 1) == p) s = substr(s, 2)
        print s
    }' | LC_ALL=C sort -u >"$TMP/undefined.all"
# The hidden weak names bind to zero in the final link, so they are not
# undefined references to OpenSSL.
test -f "$TMP/hidden" || : >"$TMP/hidden"
LC_ALL=C comm -23 "$TMP/undefined.all" "$TMP/hidden" >"$TMP/undefined"
BAD='^(_ZN|_R|rust_|__rust|__rdl|__rg|zkrs_|zks_|SSL_|ERR_|EVP_|X509_|PEM_|BIO_|OPENSSL_|CRYPTO_)'
if grep -E "$BAD" "$TMP/undefined" >"$TMP/bad"; then
    cat "$TMP/bad" >&2
    rm -f "$RESULT"
    die "$RESULT has undefined references to private or OpenSSL symbols"
fi

# Post-check 3: the hidden names do not reach the dynamic symbol table. A
# trial shared library of the object shows the result of the final link.
# A linker that ignores the hidden visibility gives a dynamic symbol. Then
# another image could supply the allocator hooks.
if test -s "$TMP/hidden"; then
    # shellcheck disable=SC2086
    if ! $CC $CFLAGS -fno-lto -shared -nostdlib -o "$TMP/trial.so" "$RESULT" \
        >"$TMP/trial.log" 2>&1; then
        cat "$TMP/trial.log" >&2
        rm -f "$RESULT"
        die "the trial shared link of $RESULT failed"
    fi
    if $NM -D "$TMP/trial.so" | awk '{ sub(/@.*/, "", $NF); print $NF }' |
        grep -Fx -f "$TMP/hidden" >"$TMP/bad"; then
        cat "$TMP/bad" >&2
        rm -f "$RESULT"
        die "a shared library of $RESULT has dynamic symbols of the hidden names"
    fi
fi

# The libtool object. Both slots name the same PIC object.
if test -n "$OUT"; then
    cp -f "$RESULT" "$OUTDIR/$OUTBASE.o"
    cat >"$OUTDIR/$OUTBASE.lo" <<LO
# $OUTBASE.lo - a libtool object file
# Generated by abi/prelink.sh for GNU libtool
pic_object='.libs/$OUTBASE.o'
non_pic_object='$OUTBASE.o'
LO
fi
