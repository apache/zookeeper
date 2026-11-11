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


# Mach-O and archive dependency check on macOS:
# Verifies that no artifact produced by a drop-in build links against, embeds
# path references to, or contains undefined symbol references to OpenSSL
# (`libssl` or `libcrypto`), while confirming that SASL-enabled shared libraries
# link against `libsasl2`.
#
# Usage: run-e9.sh DIR...
#   DIR  build output directory produced by `dev/build-dropin.sh`. Inspects all
#        Mach-O binaries and `ar` archives in `DIR` up to depth 2.
#
# Each binary is checked for:
# 1. Absence of `libssl` or `libcrypto` load commands (`otool -L`).
# 2. Absence of `libssl.` or `libcrypto.` string literals.
# 3. Absence of undefined OpenSSL symbol references (`nm -u`).
# In addition, any `*.2.dylib` inside a `sasl` or `full` output directory must
# carry a `libsasl2` load command.

set -uo pipefail

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); echo "PASS $*"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL $*"; }
OSSL='^_?(SSL_|ERR_|EVP_|X509_|PEM_|BIO_|OPENSSL_|CRYPTO_)'

[ $# -gt 0 ] || { echo "usage: run-e9.sh DIR..." >&2; exit 2; }
for dir in "$@"; do
    [ -d "$dir" ] || { fail "$dir is not a directory"; continue; }
    n=0
    while IFS= read -r f; do
        case "$(file -b "$f")" in
        *Mach-O* | *"ar archive"*) ;;
        *) continue ;;
        esac
        n=$((n + 1))
        bad=""
        otool -L "$f" 2>/dev/null | tail -n +2 | grep -Eqi 'libssl|libcrypto' && bad="$bad load"
        strings -a "$f" | grep -Eq 'libssl\.|libcrypto\.' && bad="$bad string"
        nm -u "$f" 2>/dev/null | grep -Eq "$OSSL" && bad="$bad undefined"
        if [ -n "$bad" ]; then
            fail "E9 $f: OpenSSL$bad"
        fi
        case "$dir" in
        *sasl | *full | *sasl/ | *full/)
            case "$f" in
            *.2.dylib)
                otool -L "$f" | grep -q libsasl2 || fail "E9 $f does not load libsasl2"
                ;;
            esac
            ;;
        esac
    done < <(find "$dir" -maxdepth 2 -type f ! -path '*.dSYM/*' | sort)
    if [ "$n" = 0 ]; then
        fail "E9 $dir has no Mach-O file or archive"
    else
        pass "E9 $dir: $n files checked"
    fi
done
echo "run-e9.sh: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
