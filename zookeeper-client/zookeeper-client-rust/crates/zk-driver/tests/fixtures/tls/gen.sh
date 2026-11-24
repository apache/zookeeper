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

# Makes the TLS test fixtures of zk-driver and zk-fakeserver.
#
# Section "base":
#
# - ca: the test CA.
# - wrong-ca: a second CA that the clients do not trust.
# - server: a server certificate for 127.0.0.1 and localhost, from ca.
# - other-name: a server certificate for other.invalid only, from ca.
# - expired: a server certificate for 127.0.0.1 and localhost, from ca,
#   that expired in 2001.
# - wrong-server: a server certificate for 127.0.0.1 and localhost, from
#   wrong-ca.
# - client: a client certificate, from ca.
#
# Section "crl":
#
# - crl.pem: an empty CRL from ca. A CA file with only this CRL loads and
#   adds no trust input.
#
# Section "ku" uses ca from section "base":
#
# - ka-server: a P-256 server certificate whose keyUsage is only
#   keyAgreement. libssl refuses it on TLS 1.2 and accepts it on TLS 1.3.
# - ka-ed-server: the same rule with an Ed25519 key.
#
# Section "keys" makes the client-identity corpus in keys/.
# It uses ca and client from section "base". Each base key of keys/ is
# made only when it is missing. The section makes the certificates and
# the encrypted forms again on each run. The passphrase of every
# encrypted key is "zk-test-pass".
#
# The certificates are valid from 2024 to 2124, so a clock that is a
# little wrong does not break the tests.
#
# Usage: gen.sh [path-to-openssl] [all|base|crl|ku|keys]
#
# The default section is "all". Section "base" makes new keys for every
# base fixture, which the tests of other crates also use.
#
# The tests do not run this script in place. The zk_testkit::fixtures
# module checks the OpenSSL version first. Then it runs copies of this
# script and trust/gen.sh in a new directory of the test cache. Thus each
# cache entry has new keys, and no test depends on fixed key bytes. The
# free-scan test takes its marker from the key of the current entry.

set -eu
OPENSSL=${1:-/opt/homebrew/opt/openssl@3/bin/openssl}
SECTION=${2:-all}
cd "$(dirname "$0")"
VALID="-not_before 20240101000000Z -not_after 21240101000000Z"
PASS="pass:zk-test-pass"

key() {
  "$OPENSSL" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null
}

ca() {
  key "$1.key"
  # shellcheck disable=SC2086
  "$OPENSSL" req -x509 -new -key "$1.key" -sha256 $VALID -subj "/CN=$2" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$1.pem"
}

# sign NAME KEY CN EKU SAN [ISSUER [VALIDITY...]]: a certificate for KEY.
sign() {
  name=$1 skey=$2 cn=$3 eku=$4 san=$5
  issuer=${6:-ca}
  shift 5
  [ $# -gt 0 ] && shift
  # shellcheck disable=SC2086
  [ $# -gt 0 ] || set -- $VALID
  "$OPENSSL" req -new -key "$skey" -subj "/CN=$cn" -out "$name.csr"
  printf '%s\n' "basicConstraints=critical,CA:FALSE" \
    "keyUsage=${KU:-critical,digitalSignature}" "extendedKeyUsage=$eku" "$san" > "$name.ext"
  "$OPENSSL" x509 -req -in "$name.csr" -CA "$issuer.pem" -CAkey "$issuer.key" \
    -CAcreateserial -sha256 "$@" -extfile "$name.ext" -out "$name.pem" 2>/dev/null
  rm -f "$name.csr" "$name.ext"
}

# leaf NAME CN EKU SAN [ISSUER [VALIDITY...]]: a new key and its certificate.
leaf() {
  key "$1.key"
  name=$1
  shift
  sign "$name" "$name.key" "$@"
}

base() {
  ca ca "zk-driver test CA"
  ca wrong-ca "zk-driver wrong CA"
  SAN="subjectAltName=IP:127.0.0.1,DNS:localhost"
  leaf server "localhost" serverAuth "$SAN"
  leaf other-name "other.invalid" serverAuth "subjectAltName=DNS:other.invalid"
  leaf expired "localhost" serverAuth "$SAN" ca \
    -not_before 20000101000000Z -not_after 20010101000000Z
  leaf wrong-server "localhost" serverAuth "$SAN" wrong-ca
  leaf client "zk-driver client" clientAuth "subjectAltName=DNS:client.invalid"
  rm -f ca.srl wrong-ca.srl wrong-ca.key
}

ku() {
  SAN="subjectAltName=IP:127.0.0.1,DNS:localhost"
  KU=critical,keyAgreement leaf ka-server "localhost" serverAuth "$SAN"
  "$OPENSSL" genpkey -algorithm ED25519 -out ka-ed-server.key 2>/dev/null
  KU=critical,keyAgreement sign ka-ed-server ka-ed-server.key "localhost" serverAuth "$SAN"
  rm -f ca.srl
}

crl() {
  tmp=$(mktemp -d)
  : > "$tmp/index.txt"
  echo 01 > "$tmp/crlnumber"
  printf '%s\n' "[ca]" "default_ca = d" "[d]" "database = $tmp/index.txt" \
    "crlnumber = $tmp/crlnumber" "default_md = sha256" > "$tmp/ca.cnf"
  "$OPENSSL" ca -batch -config "$tmp/ca.cnf" -gencrl -keyfile ca.key -cert ca.pem \
    -crl_lastupdate 20240101000000Z -crl_nextupdate 21240101000000Z \
    -out crl.pem 2>/dev/null
  rm -rf "$tmp"
}

# basekey FILE GENPKEY-ARGS...: makes FILE only when it is missing.
basekey() {
  file=$1
  shift
  [ -f "$file" ] || "$OPENSSL" genpkey "$@" -out "$file" 2>/dev/null
}

# p8 NAME ARGS...: client.key as encrypted PKCS#8.
p8() {
  name=$1
  shift
  "$OPENSSL" pkcs8 -topk8 -in ../client.key "$@" -passout "$PASS" -out "$name.pem"
}

# trad NAME CIPHER [ARGS...]: client.key as traditional encrypted SEC1.
# OpenSSL 3 writes an unencrypted key, with exit status 0, when the cipher
# is not in a loaded provider. The check stops the script in that case.
trad() {
  name=$1
  shift
  "$OPENSSL" ec -in ../client.key "$@" -passout "$PASS" -out "$name.pem" 2>/dev/null
  grep -q '^Proc-Type: 4,ENCRYPTED' "$name.pem" || {
    echo "gen.sh: $name.pem is not encrypted" >&2
    exit 1
  }
}

keys() {
  mkdir -p keys
  cd keys
  ln -sf ../ca.pem ca.pem
  ln -sf ../ca.key ca.key

  # The base keys.
  basekey rsa.key -algorithm RSA -pkeyopt rsa_keygen_bits:2048
  basekey rsa1024.key -algorithm RSA -pkeyopt rsa_keygen_bits:1024
  basekey rsa6144.key -algorithm RSA -pkeyopt rsa_keygen_bits:6144
  basekey rsa-e3.key -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -pkeyopt rsa_keygen_pubexp:3
  basekey rsa-pss.key -algorithm RSA-PSS -pkeyopt rsa_keygen_bits:2048
  basekey ed25519.key -algorithm ED25519
  basekey ed448.key -algorithm ED448
  basekey p384.key -algorithm EC -pkeyopt ec_paramgen_curve:P-384
  basekey p521.key -algorithm EC -pkeyopt ec_paramgen_curve:P-521
  if [ ! -f dsa.key ]; then
    "$OPENSSL" genpkey -genparam -algorithm DSA -pkeyopt pbits:2048 -out dsa.param 2>/dev/null
    "$OPENSSL" genpkey -paramfile dsa.param -out dsa.key
    rm -f dsa.param
  fi

  # The leaves. Each has the name of its key.
  for k in rsa rsa6144 rsa-e3 rsa-pss ed25519 p521; do
    sign "$k" "$k.key" "zk-driver $k client" clientAuth "subjectAltName=DNS:client.invalid"
  done
  # The client key, with the point in compressed form in the leaf.
  "$OPENSSL" ec -in ../client.key -pubout -conv_form compressed -out compressed.pub 2>/dev/null
  "$OPENSSL" req -new -key ../client.key -subj "/CN=zk-driver compressed" -out compressed.csr
  # shellcheck disable=SC2086
  "$OPENSSL" x509 -req -in compressed.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
    -sha256 $VALID -force_pubkey compressed.pub -out compressed.pem 2>/dev/null
  rm -f compressed.pub compressed.csr
  # A v1 leaf for the client key. A config without extensions keeps v1.
  printf '%s\n' "[req]" "distinguished_name = dn" "[dn]" > v1.cnf
  # shellcheck disable=SC2086
  "$OPENSSL" req -config v1.cnf -x509v1 -new -key ../client.key -CA ca.pem -CAkey ca.key \
    -subj "/CN=zk-driver v1 client" $VALID -out v1.pem 2>/dev/null
  rm -f v1.cnf

  # Chain files.
  "$OPENSSL" x509 -in ../client.pem -trustout -addtrust clientAuth -out trusted.pem
  "$OPENSSL" x509 -in ../ca.pem -trustout -addtrust serverAuth -out trusted-ca.pem
  cat ../client.pem ../ca.pem > chain.pem
  cat ../client.pem trusted-ca.pem > chain-trusted-later.pem
  cat trusted.pem ../ca.pem > trusted-chain.pem
  cat ../client.pem ../client.key > cert-then-key.pem

  # Unencrypted key forms.
  "$OPENSSL" ec -in ../client.key -out client-sec1.pem 2>/dev/null
  "$OPENSSL" ec -in ../client.key -no_public -out client-sec1-nopub.pem 2>/dev/null
  "$OPENSSL" ec -in ../client.key -param_enc explicit -out client-explicit.pem 2>/dev/null
  "$OPENSSL" rsa -in rsa.key -traditional -out rsa-pkcs1.pem 2>/dev/null
  "$OPENSSL" dsa -in dsa.key -out dsa-trad.pem 2>/dev/null

  # Encrypted PKCS#8 (PBES2). The last three are not accepted.
  p8 p8-sha256-aes256 -v2 aes-256-cbc -v2prf hmacWithSHA256
  p8 p8-sha1-aes128 -v2 aes-128-cbc -v2prf hmacWithSHA1
  p8 p8-sha224-aes192 -v2 aes-192-cbc -v2prf hmacWithSHA224
  p8 p8-sha384-aes256 -v2 aes-256-cbc -v2prf hmacWithSHA384
  p8 p8-sha512-aes128 -v2 aes-128-cbc -v2prf hmacWithSHA512
  p8 p8-des3 -v2 des3
  p8 p8-scrypt -scrypt -scrypt_N 1024 -scrypt_r 8 -scrypt_p 1 -v2 aes-256-cbc
  p8 p8-sha512-256 -v2 aes-256-cbc -v2prf hmacWithSHA512-256
  p8 p8-pkcs12 -v1 PBE-SHA1-3DES
  p8 p8-camellia -v2 camellia-256-cbc

  # Traditional encryption (Proc-Type and DEK-Info). The last two are
  # not accepted.
  trad trad-aes128 -aes128
  trad trad-aes192 -aes192
  trad trad-aes256 -aes256
  trad trad-des3 -des3
  # DES-CBC is in the legacy provider of OpenSSL 3.
  trad trad-des -des -provider legacy -provider default
  trad trad-cfb -aes-128-cfb
  trad trad-camellia -camellia256
  "$OPENSSL" rsa -in rsa.key -traditional -aes256 -passout "$PASS" \
    -out rsa-trad-aes256.pem 2>/dev/null

  rm -f ca.pem ca.key ca.srl
  cd ..
}

case $SECTION in
  all) base; crl; ku; keys ;;
  base) base ;;
  crl) crl ;;
  ku) ku ;;
  keys) keys ;;
  *) echo "unknown section: $SECTION" >&2; exit 2 ;;
esac
