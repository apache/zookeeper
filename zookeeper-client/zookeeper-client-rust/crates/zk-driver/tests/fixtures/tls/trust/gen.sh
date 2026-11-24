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

# Makes the trust-loader fixtures of tests/tls_trust.rs.
#
# Every key is a fixed Ed25519 seed and every time is fixed, so a second
# run gives the same files. Ed25519 signatures do not use random numbers.
#
# The script also asks OpenSSL for its verdicts and writes them to
# expect.txt:
#
# - "hash NAME HEX": `openssl x509 -subject_hash` of NAME.pem.
# - "load NAME ok|fail": whether `SSL_CTX_load_verify_locations` takes
#   NAME as the CA file. s_client loads the file and then connects to a
#   closed port, so "connect" in its output means that the load passed.
# - "verify NAME CHAIN ok|fail": `openssl verify -purpose sslserver` of
#   CHAIN with the CA file NAME, or with the hash directory NAME.
#
# Usage: gen.sh [path-to-openssl]

set -eu
OPENSSL=${1:-/opt/homebrew/opt/openssl@3/bin/openssl}
cd "$(dirname "$0")"
# The closed port of the load check. It is in the port range of the trust tests.
PORT=23419
VALID="-not_before 20240101000000Z -not_after 21240101000000Z"
rm -rf hd-* ./*.pem ./*.der expect.txt
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# key NAME SEED: an Ed25519 key whose seed is SEED (one base64 letter).
key() {
  printf -- '-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA%s\n-----END PRIVATE KEY-----\n' "$2" > "$TMP/$1.key"
}

cat > "$TMP/gen.cnf" <<'CNF'
[req]
distinguished_name = dn
prompt = no
[dn]
[none]
[ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
[ca_client]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
extendedKeyUsage = clientAuth
[ca_nokcs]
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature
[ns_ssl]
nsCertType = sslCA
[ns_obj]
nsCertType = objCA
[noca]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = serverAuth
[inter]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
[leaf]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost
CNF

# self NAME SEED SERIAL EXT SUBJECT [OPTIONS...]: a self-signed certificate.
self() {
  name=$1 seed=$2 serial=$3 ext=$4 subj=$5
  shift 5
  key "$name" "$seed"
  # shellcheck disable=SC2086
  "$OPENSSL" req -config "$TMP/gen.cnf" -x509 -new -key "$TMP/$name.key" \
    -set_serial "$serial" $VALID -subj "$subj" -extensions "$ext" "$@" -out "$name.pem"
}

# signed NAME SEED SERIAL EXT SUBJECT ISSUER: a certificate from ISSUER.
signed() {
  name=$1 seed=$2 serial=$3 ext=$4 subj=$5 issuer=$6
  key "$name" "$seed"
  "$OPENSSL" req -config "$TMP/gen.cnf" -new -key "$TMP/$name.key" -subj "$subj" \
    -out "$TMP/$name.csr"
  # shellcheck disable=SC2086
  "$OPENSSL" x509 -req -in "$TMP/$name.csr" -CA "$issuer.pem" -CAkey "$TMP/$issuer.key" \
    -set_serial "$serial" $VALID -extfile "$TMP/gen.cnf" -extensions "$ext" \
    -out "$name.pem" 2>/dev/null
}

# The main chain, and a second root with the same name and another key.
self root B 1 ca "/O=Apache ZooKeeper/CN=ZK Trust Root"
self root2 C 2 ca "/O=Apache ZooKeeper/CN=ZK Trust Root"
signed inter D 3 inter "/O=Apache ZooKeeper/CN=ZK Trust Intermediate" root
signed leaf E 4 leaf "/CN=localhost" inter

# Self-signed inputs for the check_ca and EKU rules, each with a leaf.
self v1root F 5 none "/CN=ZK V1 Root" -x509v1
self clientca G 6 ca_client "/CN=ZK Client CA"
self nokcs H 7 ca_nokcs "/CN=ZK No KeyCertSign"
self nsssl I 8 ns_ssl "/CN=ZK NS SSL CA"
self nsobj J 9 ns_obj "/CN=ZK NS Obj CA"
self noca K 10 noca "/CN=localhost"
# Serial zero: RFC 5280 forbids it, but deployed roots use it.
self zero-serial N 0 ca "/CN=ZK Zero Serial"
for ca in v1root clientca nokcs nsssl nsobj; do
  signed "$ca-leaf" L 11 leaf "/CN=localhost" "$ca"
done

# Names for the canonical form. odd and odd-fold differ only in case and
# space, so their hashes are equal.
self odd M 12 ca "/O=  Apache   ZooKeeper /CN=ZK  Odd  NAME"
self odd-fold M 13 ca "/O=apache zookeeper/CN=zk odd name"
self multi M 14 ca "/CN=ZK Multi+O=Apache" -multivalue-rdn
self utf8 M 15 ca "/CN=ZK Ünïcode Náme" -utf8
self email M 16 ca "/CN=ZK Mail/emailAddress=ZK@Example.ORG"

# Aux trust settings.
"$OPENSSL" x509 -in root.pem -addtrust serverAuth -out root-t-server.pem
"$OPENSSL" x509 -in root.pem -addtrust anyExtendedKeyUsage -out root-t-any.pem
"$OPENSSL" x509 -in root.pem -addtrust clientAuth -out root-t-client.pem
"$OPENSSL" x509 -in root.pem -addreject serverAuth -out root-r-server.pem
"$OPENSSL" x509 -in root.pem -addreject clientAuth -out root-r-client.pem
"$OPENSSL" x509 -in root.pem -setalias zk-root -trustout -out root-alias.pem
"$OPENSSL" x509 -in inter.pem -addtrust serverAuth -out inter-t-server.pem
"$OPENSSL" x509 -in inter.pem -addtrust clientAuth -out inter-t-client.pem
"$OPENSSL" x509 -in noca.pem -addtrust serverAuth -out noca-t-server.pem

# pem LABEL FILE...: the concatenation of FILE... as one PEM block.
pem() {
  label=$1
  shift
  printf -- '-----BEGIN %s-----\n' "$label"
  cat "$@" | "$OPENSSL" base64 -e
  printf -- '-----END %s-----\n' "$label"
}

der() {
  "$OPENSSL" x509 -in "$1.pem" -outform DER -out "$TMP/$1.der"
}
der root

# An aux with an empty trust list. x509 -clrtrust removes the list.
printf 'asn1 = SEQUENCE:aux\n[aux]\ntrust = SEQUENCE:empty\n[empty]\n' > "$TMP/aux.cnf"
"$OPENSSL" asn1parse -genconf "$TMP/aux.cnf" -noout -out "$TMP/aux-empty.der"
pem "TRUSTED CERTIFICATE" "$TMP/root.der" "$TMP/aux-empty.der" > root-t-empty.pem
# The aux of root-r-server under the plain CERTIFICATE label.
sed '1d;$d' root-r-server.pem | "$OPENSSL" base64 -d > "$TMP/root-r.der"
pem CERTIFICATE "$TMP/root-r.der" > plain-aux-reject.pem

# A CRL from root.
: > "$TMP/index.txt"
echo 01 > "$TMP/crlnum"
printf '[ca]\ndefault_ca = c\n[c]\ndatabase = %s/index.txt\ndefault_md = default\ncrlnumber = %s/crlnum\n' \
  "$TMP" "$TMP" > "$TMP/ca.cnf"
"$OPENSSL" ca -config "$TMP/ca.cnf" -gencrl -keyfile "$TMP/root.key" -cert root.pem \
  -crl_lastupdate 20240101000000Z -crl_nextupdate 21240101000000Z -out crl.pem 2>/dev/null

# CA-file images for the load rule. The names start with "ca-".
cp root.pem ca-root.pem
"$OPENSSL" x509 -in root.pem -outform DER -out ca-root.der
: > ca-empty.pem
cp crl.pem ca-crl-only.pem
cat root.pem v1root.pem nsssl.pem > ca-bundle.pem
sed 's/BEGIN CERTIFICATE/BEGIN X509 CERTIFICATE/;s/END CERTIFICATE/END X509 CERTIFICATE/' \
  root.pem > ca-x509-label.pem
{ printf 'leading text\n'; cat root.pem; printf 'trailing text\n'; } > ca-text-around.pem
{ printf '\357\273\277'; cat root.pem; } > ca-bom.pem
sed 's/$/\r/' root.pem > ca-crlf.pem
sed '$d' root.pem > ca-unterminated.pem
sed 's/END CERTIFICATE/END X509 CRL/' root.pem > ca-end-mismatch.pem
sed '2s/^./!/' root.pem > ca-bad-base64.pem
{ cat root.pem; sed '2s/^./!/' v1root.pem; } > ca-second-bad.pem
sed '1a\
Proc-Type: 4,ENCRYPTED\
DEK-Info: AES-128-CBC,00112233445566778899AABBCCDDEEFF\
' root.pem > ca-cert-header.pem
printf 'not a certificate' > "$TMP/junk"
pem CERTIFICATE "$TMP/junk" > "$TMP/junk-cert.pem"
pem "X509 CRL" "$TMP/junk" > "$TMP/junk-crl.pem"
pem FOO "$TMP/junk" > "$TMP/junk-foo.pem"
cat root.pem "$TMP/junk-cert.pem" > ca-bad-der.pem
cat root.pem "$TMP/junk-crl.pem" > ca-bad-crl.pem
cp "$TMP/junk-foo.pem" ca-other-only.pem
cat "$TMP/junk-foo.pem" root.pem > ca-other-and-cert.pem
cat root.pem "$TMP/root.key" > ca-key.pem
sed 's/PRIVATE KEY/RSA PRIVATE KEY/' "$TMP/root.key" > "$TMP/typed.key"
cat root.pem "$TMP/typed.key" > ca-key-typed-mismatch.pem
sed 's/PRIVATE KEY/FOO PRIVATE KEY/' "$TMP/root.key" > "$TMP/foo.key"
cat root.pem "$TMP/foo.key" > ca-key-foo.pem
pem "PRIVATE KEY" "$TMP/junk" > "$TMP/junk.key"
cat root.pem "$TMP/junk.key" > ca-key-bad.pem
pem "ENCRYPTED PRIVATE KEY" "$TMP/junk" > "$TMP/junk-enc.key"
cat root.pem "$TMP/junk-enc.key" > ca-key-encrypted.pem
sed '1a\
Proc-Type: 4,ENCRYPTED\
DEK-Info: AES-128-CBC,00112233445566778899AABBCCDDEEFF\
' "$TMP/junk-enc.key" | sed 's/ENCRYPTED PRIVATE KEY/RSA PRIVATE KEY/' > "$TMP/hdr.key"
cat root.pem "$TMP/hdr.key" > ca-key-dek.pem
sed 's/AES-128-CBC,0011/AES-128-CBC,00/' "$TMP/hdr.key" > "$TMP/hdr-bad.key"
cat root.pem "$TMP/hdr-bad.key" > ca-key-dek-short-iv.pem
cp root-t-server.pem ca-trusted.pem
cat "$TMP/root.der" "$TMP/junk" > "$TMP/root-junk.der"
pem "TRUSTED CERTIFICATE" "$TMP/root-junk.der" > ca-trusted-bad-aux.pem
pem CERTIFICATE "$TMP/root-junk.der" > ca-cert-trailing.pem
cp plain-aux-reject.pem ca-plain-aux-reject.pem

# Hash directories. The names start with "hd-".
H=$("$OPENSSL" x509 -noout -subject_hash -in root.pem)
mkdir hd-basic hd-gap hd-next hd-wrong hd-rname hd-corrupt hd-partial hd-auxreject
cp root.pem "hd-basic/$H.0"
cp root2.pem "hd-gap/$H.0"
cp root.pem "hd-gap/$H.2"
cp root2.pem "hd-next/$H.0"
cp root.pem "hd-next/$H.1"
cp root.pem hd-wrong/deadbeef.0
cp inter.pem "hd-wrong/$H.0"
cp root.pem "hd-rname/$H.r0"
cp "$TMP/junk-cert.pem" "hd-corrupt/$H.0"
cp root.pem "hd-corrupt/$H.1"
cat root2.pem "$TMP/junk-cert.pem" > "hd-partial/$H.0"
cp root.pem "hd-partial/$H.1"
cp plain-aux-reject.pem "hd-auxreject/$H.0"

# The verdicts of OpenSSL.
for f in ./*.pem; do
  n=${f#./}
  n=${n%.pem}
  case $n in ca-* | crl | plain-aux-reject) continue ;; esac
  echo "hash $n $("$OPENSSL" x509 -noout -subject_hash -in "$f")" >> expect.txt
done
for f in ca-*; do
  if "$OPENSSL" s_client -connect "127.0.0.1:$PORT" -CAfile "$f" < /dev/null 2>&1 |
    grep -q 'connect:errno'; then
    echo "load $f ok" >> expect.txt
  else
    echo "load $f fail" >> expect.txt
  fi
done
# check CA CHAIN [-CApath]: one verify verdict.
check() {
  ca=$1 chain=$2
  shift 2
  if [ "${1:-}" = dir ]; then
    set -- -CApath "$ca"
  else
    set -- -CAfile "$ca"
  fi
  case $chain in
    leaf) set -- "$@" -untrusted inter.pem leaf.pem ;;
    leaf-only) set -- "$@" leaf.pem ;;
    *) set -- "$@" "$chain.pem" ;;
  esac
  if "$OPENSSL" verify -purpose sslserver "$@" > /dev/null 2>&1; then
    echo "verify $ca $chain ok" >> expect.txt
  else
    echo "verify $ca $chain fail" >> expect.txt
  fi
}
for ca in root root2 root-t-server root-t-any root-t-client root-r-server root-r-client \
  root-t-empty root-alias plain-aux-reject; do
  check "$ca.pem" leaf
done
check inter-t-server.pem leaf-only
cat root.pem inter-t-client.pem > ca-root-inter-t-client.pem
check ca-root-inter-t-client.pem leaf-only
cat root.pem inter.pem > ca-root-inter.pem
check ca-root-inter.pem leaf-only
for ca in v1root clientca nokcs nsssl nsobj; do
  check "$ca.pem" "$ca-leaf"
done
check noca.pem noca
check noca-t-server.pem noca
for d in hd-*; do
  check "$d" leaf dir
done
