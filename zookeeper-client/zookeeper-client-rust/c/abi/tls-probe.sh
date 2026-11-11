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

# The TLS decision of the Maven build.
#
#   sh tls-probe.sh [--with-openssl=yes|no|DIR] [--with-rust-tls=yes|no]
#                   [--with-rust-tls-default-paths=FILE:DIR]
#
# The Rust module builds before configure runs, so this script takes the
# same TLS decision as configure. It prints four properties:
#   tls=yes|no
#   openssl=yes|no   the result of the OpenSSL header probe
#   cert_file=FILE
#   cert_dir=DIR
# The OpenSSL probe only decides the TLS feature and the trust paths. The
# Rust core never links OpenSSL.
#
# The probe is the probe of configure.ac. It uses $CC (else cc), CPPFLAGS
# and LDFLAGS. For --with-openssl=DIR it adds -IDIR/include and -LDIR/lib.
# A test program includes openssl/ssl.h and links SSL_CTX_new with -lssl.
# TLS is on when the probe passes or --with-rust-tls=yes is given. The
# trust paths follow ZK_RUST_TLS_DEFAULT_PATHS in m4/zk_rust.m4. Errors
# exit with status 2.
set -eu

with_openssl=yes
with_rust_tls=no
default_paths=auto
for arg in "$@"; do
  case "$arg" in
    --with-openssl=*) with_openssl=${arg#--with-openssl=} ;;
    --without-openssl) with_openssl=no ;;
    --with-rust-tls=*) with_rust_tls=${arg#--with-rust-tls=} ;;
    --with-rust-tls-default-paths=*) default_paths=${arg#--with-rust-tls-default-paths=} ;;
    *) echo "tls-probe.sh: unknown option $arg" >&2; exit 2 ;;
  esac
done
case "$with_rust_tls" in
  yes|no) ;;
  *) echo "tls-probe.sh: --with-rust-tls=$with_rust_tls: give yes or no" >&2; exit 2 ;;
esac
if [ "$with_rust_tls" = yes ] && [ "$with_openssl" = no ]; then
  echo "tls-probe.sh: --with-rust-tls=yes contradicts --with-openssl=no: give only one of them" >&2
  exit 2
fi

# The OpenSSL probe of configure.ac. CC can hold options, so it is not
# quoted.
have_openssl=no
if [ "$with_openssl" != no ]; then
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/zk-tls-probe.XXXXXX")
  trap 'rm -rf "$tmp"' EXIT
  dir_flags=
  if [ "$with_openssl" != yes ]; then
    dir_flags="-I$with_openssl/include -L$with_openssl/lib"
  fi
  cat >"$tmp/probe.c" <<'PROBE'
#include <openssl/ssl.h>
int main(void) { return SSL_CTX_new(0) != 0; }
PROBE
  # shellcheck disable=SC2086
  if ${CC:-cc} ${CPPFLAGS:-} ${CFLAGS:-} $dir_flags ${LDFLAGS:-} -o "$tmp/probe" "$tmp/probe.c" \
      -lssl >/dev/null 2>&1; then
    have_openssl=yes
  fi
fi
tls=no
if [ "$have_openssl" = yes ] || [ "$with_rust_tls" = yes ]; then
  tls=yes
fi

# The default trust locations. The option comes first.
# Then the OPENSSLDIR of the openssl command of the installation: DIR/bin/
# openssl for --with-openssl=DIR, else the first openssl on PATH. Then the
# first directory of the platform list. Windows records no location.
cert_file=
cert_dir=
case "$default_paths" in
  auto)
    case "$tls:$(uname -s)" in
      no:*|*:MINGW*|*:MSYS*|*:CYGWIN*|*:Windows*) ;;
      *)
        ossl_bin=
        case "$with_openssl" in
          yes) ossl_bin=$(command -v openssl 2>/dev/null || true) ;;
          no) ;;
          *) if [ -x "$with_openssl/bin/openssl" ]; then ossl_bin=$with_openssl/bin/openssl; fi ;;
        esac
        ossl_dir=
        if [ -n "$ossl_bin" ]; then
          ossl_dir=$("$ossl_bin" version -d 2>/dev/null | sed -n 's/^OPENSSLDIR: "\(.*\)"$/\1/p' || true)
        fi
        if [ -z "$ossl_dir" ]; then
          for d in /usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl \
              /usr/local/etc/openssl@3 /opt/homebrew/etc/openssl@3; do
            if [ -d "$d" ]; then ossl_dir=$d; break; fi
          done
        fi
        if [ -n "$ossl_dir" ]; then
          cert_file=$ossl_dir/cert.pem
          cert_dir=$ossl_dir/certs
        fi
        ;;
    esac
    ;;
  *:*:*)
    echo "tls-probe.sh: --with-rust-tls-default-paths=$default_paths: give FILE:DIR with one colon" >&2
    exit 2
    ;;
  *:*)
    cert_file=${default_paths%%:*}
    cert_dir=${default_paths#*:}
    for p in "$cert_file" "$cert_dir"; do
      case "$p" in
        ""|/*) ;;
        *) echo "tls-probe.sh: --with-rust-tls-default-paths=$default_paths: give absolute paths (FILE or DIR can be empty)" >&2
           exit 2 ;;
      esac
    done
    ;;
  *)
    echo "tls-probe.sh: --with-rust-tls-default-paths=$default_paths: give FILE:DIR" >&2
    exit 2
    ;;
esac
if [ "$tls" = no ]; then
  cert_file=
  cert_dir=
fi
echo "tls=$tls"
echo "openssl=$have_openssl"
echo "cert_file=$cert_file"
echo "cert_dir=$cert_dir"
