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

# Generates the X.509 server-certificate trust-decision corpus (unit tier) and
# records the reference `openssl verify` oracle verdict for each test case.
#
# Each generated test case resides in `corpus/<case>/` and contains:
# - `chain.pem`: server certificate chain presented during handshake (leaf first)
# - `ca.pem`: configured CA bundle (populated with an unrelated root when
#   testing trust anchors resolved exclusively from default trust directories)
# - `defaults/`: optional `SSL_CERT_DIR` directory containing subject-hash links
# - `defaults2/`: optional secondary `SSL_CERT_DIR` directory appended after `defaults/`
# - `verdict.txt`: reference oracle outcome and expected Rust verifier outcomes
#
# `verdict.txt` key-value fields:
# - `oracle`: `ACCEPT` or `REJECT` as determined by `openssl verify`
# - `error`: primary OpenSSL X.509 verification error code, or `0`
# - `attime`: epoch timestamp passed to `openssl verify -attime` and the Rust verifier
# - `expect`: expected Rust verifier verdict under the `ring` provider
# - `expect-aws-lc-rs`: expected Rust verifier verdict under the `aws-lc-rs` provider
# - `tag`, `tag-aws-lc-rs`: `EXACT` when matching `oracle`, or a documented
#   provider algorithm deviation tag
# - `tc`: test-corpus specification ID; `note`: scenario description
# - `openssl`, `auth_level`: oracle OpenSSL version string and `-auth_level`
#
# `ZK_CORPUS_OUT` specifies the output directory (typically a fresh cache directory
# managed by `zk_testkit::fixtures`). Private keys are generated once per output
# directory; fixed certificate validity timestamps ensure deterministic verdicts
# across freshly generated key material.
#
# Safety invariant: generation aborts immediately if any case expects a Rust
# `ACCEPT` where the OpenSSL oracle returns `REJECT`. The drop-in verifier must
# never accept a certificate chain rejected by the legacy OpenSSL oracle.
#
# Usage:
#   gencorpus.sh            generate the corpus and record oracle verdicts
#   gencorpus.sh --check    re-run the oracle against an existing corpus and
#                           report any verification drift
#
# In `--check` mode, set `ZK_CORPUS_OUT` to the cached corpus directory printed
# by `zk-fixtures corpus` (`corpus-<key>/corpus`); `--check` fails if fewer than
# 101 cases are present.
#
# `OPENSSL` selects the `openssl` executable (default: Homebrew `openssl@3`).

set -euo pipefail

OPENSSL=${OPENSSL:-/opt/homebrew/opt/openssl@3/bin/openssl}
MODE=gen
if [ "${1:-}" = "--check" ]; then
  MODE=check
fi
# Resolve repository paths: RCDIR is the Rust C compatibility root and RDIR is
# the Rust workspace root. CDIR (`zookeeper-client-c`, overridable via `ZK_C_DIR`)
# is resolved on demand for the `ssl/gencerts.sh` test cases.
HERE=$(cd "$(dirname "$0")" && pwd)
RCDIR=$(cd "$HERE/../../.." && pwd)
RDIR=$(cd "$RCDIR/.." && pwd)
OUT=${ZK_CORPUS_OUT:-$HERE/corpus}
KEYS=$OUT/_keys
AUTH_LEVEL=2
# Reference verification epoch: 2030-01-01T00:00:00Z.
ATTIME=1893456000
NB=20250101000000Z
NA=20350101000000Z
# Boundary timestamp equal to `ATTIME` for exact `notBefore`/`notAfter` checks.
EDGE=20300101000000Z
EARLY_NA=20290101000000Z
LATE_NB=20310101000000Z
VERSION=$("$OPENSSL" version)
command -v xxd >/dev/null || { echo "gencorpus.sh: xxd is missing (vim-common)" >&2; exit 1; }
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/empty"

# oracle CASEDIR ATTIME: evaluates `openssl verify` and prints `"ACCEPT 0"` or `"REJECT <error>"`.
oracle() {
  local d=$1 at=$2 dir out err
  awk -v a="$T/leaf.pem" -v b="$T/rest.pem" '
    /-----BEGIN/ { n++ }
    { if (n == 1) print > a; else print > b }
  ' "$d/chain.pem"
  [ -f "$T/rest.pem" ] || : > "$T/rest.pem"
  dir=$T/empty
  if [ -d "$d/defaults" ]; then
    dir=$d/defaults
  fi
  if [ -d "$d/defaults2" ]; then
    dir=$dir:$d/defaults2
  fi
  set -- -purpose sslserver -auth_level "$AUTH_LEVEL" -attime "$at" \
    -no-CAstore -CAfile "$d/ca.pem"
  if [ -s "$T/rest.pem" ]; then
    set -- "$@" -untrusted "$T/rest.pem"
  fi
  if out=$(SSL_CERT_DIR=$dir SSL_CERT_FILE=$T/missing SSL_CERT_URI=$T/missing \
    "$OPENSSL" verify "$@" "$T/leaf.pem" 2>&1) && printf '%s\n' "$out" | grep -q ': OK$'; then
    echo "ACCEPT 0"
  else
    err=$(printf '%s\n' "$out" | sed -n 's/^error \([0-9]*\) at .*/\1/p' | head -n 1)
    echo "REJECT ${err:-0}"
  fi
  rm -f "$T/leaf.pem" "$T/rest.pem"
}

if [ "$MODE" = check ]; then
  drift=0
  checked=0
  for d in "$OUT"/*/; do
    d=${d%/}
    [ -f "$d/verdict.txt" ] || continue
    checked=$((checked + 1))
    at=$(sed -n 's/^attime=//p' "$d/verdict.txt")
    want="$(sed -n 's/^oracle=//p' "$d/verdict.txt") $(sed -n 's/^error=//p' "$d/verdict.txt")"
    got=$(oracle "$d" "$at")
    if [ "$got" != "$want" ]; then
      echo "drift: $(basename "$d"): recorded $want, now $got ($VERSION)"
      drift=1
    fi
  done
  # Require a complete corpus (> 100 cases) so an empty or misconfigured
  # `ZK_CORPUS_OUT` cannot trivially pass `--check`.
  if [ "$checked" -le 100 ]; then
    echo "gencorpus.sh --check: $OUT has $checked cases. Set ZK_CORPUS_OUT to" \
      "the corpus directory that zk-fixtures corpus prints" >&2
    exit 2
  fi
  exit "$drift"
fi

mkdir -p "$KEYS"

# key NAME TYPE: generates `corpus/_keys/NAME.pem` if absent. Supported types:
# `rsa`, `rsa1024`, `rsa1536`, `rsa16384`, `rsapss`, `dsa`, `ec` (P-256),
# `ec384`, `ec521`, `bp256`, `k256`, `ed25519`, `ed448`, or `ecc:BASE` (re-encoded
# from `BASE` with compressed EC public point form).
key() {
  local f=$KEYS/$1.pem
  [ -f "$f" ] && return 0
  case $2 in
    rsa) "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$f" ;;
    rsa1024) "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:1024 -out "$f" ;;
    rsa1536) "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:1536 -out "$f" ;;
    rsa16384) "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:16384 -out "$f" ;;
    dsa)
      "$OPENSSL" genpkey -genparam -algorithm DSA \
        -pkeyopt dsa_paramgen_bits:2048 -out "$T/dsa.param"
      "$OPENSSL" genpkey -paramfile "$T/dsa.param" -out "$f"
      ;;
    ec384) "$OPENSSL" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -out "$f" ;;
    ec) "$OPENSSL" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$f" ;;
    ed25519) "$OPENSSL" genpkey -algorithm ED25519 -out "$f" ;;
    ec521) "$OPENSSL" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-521 -out "$f" ;;
    bp256) "$OPENSSL" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:brainpoolP256r1 -out "$f" ;;
    k256) "$OPENSSL" genpkey -algorithm EC -pkeyopt ec_paramgen_curve:secp256k1 -out "$f" ;;
    ed448) "$OPENSSL" genpkey -algorithm ED448 -out "$f" ;;
    rsapss) "$OPENSSL" genpkey -algorithm RSA-PSS -pkeyopt rsa_keygen_bits:2048 -out "$f" ;;
    ecc:*) "$OPENSSL" ec -in "$KEYS/${2#ecc:}.pem" -conv_form compressed -out "$f" 2>/dev/null ;;
  esac
}

SERIAL=4096

# mk NAME KEY SUBJECT ISSUER PROFILE [OPTION...]
#
# Generates certificate `$T/NAME.pem`, where `ISSUER` is `"self"` or the name of
# a previously generated issuer certificate, and `PROFILE` is `ca`, `leaf`, or
# `bare`. Options:
#   nb=TIME na=TIME       validity window (`notBefore` / `notAfter`)
#   v1                    generate an X.509 v1 certificate without extensions
#   md=DIGEST             signature digest algorithm (default: `sha256`)
#   sigopt=OPT            additional `-sigopt` parameter
#   mask=MASK             ASN.1 `string_mask` (default: `utf8only`)
#   bc=, ku=, eku=, san=  override or omit (`none`) profile extensions
#   skid=VALUE            `subjectKeyIdentifier` value (default: `hash`)
#   noakid                omit `authorityKeyIdentifier`
#   ext=LINE              append a raw line to the X.509 extension section
#   sect=LINE             append a raw line following the extension section
mk() {
  local name=$1 k=$2 subj=$3 iss=$4 prof=$5
  shift 5
  local nb=$NB na=$NA ver=v3 md=sha256 mask=utf8only skid=hash akid=keyid
  local bc="" ku="" eku="" san="" extra="" sect="" sigopt=""
  case $prof in
    ca)
      bc="critical,CA:TRUE"
      ku="critical,keyCertSign,cRLSign"
      ;;
    leaf)
      bc="critical,CA:FALSE"
      ku="critical,digitalSignature,keyEncipherment"
      eku="serverAuth"
      san="DNS:server.test"
      ;;
    bare) ;;
  esac
  local o
  for o in "$@"; do
    case $o in
      nb=*) nb=${o#nb=} ;;
      na=*) na=${o#na=} ;;
      v1) ver=v1 ;;
      md=*) md=${o#md=} ;;
      sigopt=*) sigopt="$sigopt ${o#sigopt=}" ;;
      mask=*) mask=${o#mask=} ;;
      bc=*) bc=${o#bc=} ;;
      ku=*) ku=${o#ku=} ;;
      eku=*) eku=${o#eku=} ;;
      san=*) san=${o#san=} ;;
      skid=*) skid=${o#skid=} ;;
      noakid) akid=none ;;
      ext=*) extra="$extra${o#ext=}
" ;;
      sect=*) sect="$sect${o#sect=}
" ;;
      *) echo "mk: bad option $o" >&2; exit 2 ;;
    esac
  done
  local cnf=$T/$name.cnf
  {
    printf '[req]\ndistinguished_name=dn\nprompt=no\nstring_mask=%s\n' "$mask"
    printf '[dn]\nCN=x\n[sec]\n'
    [ -z "$bc" ] || [ "$bc" = none ] || printf 'basicConstraints=%s\n' "$bc"
    [ -z "$ku" ] || [ "$ku" = none ] || printf 'keyUsage=%s\n' "$ku"
    [ -z "$eku" ] || [ "$eku" = none ] || printf 'extendedKeyUsage=%s\n' "$eku"
    [ -z "$san" ] || [ "$san" = none ] || printf 'subjectAltName=%s\n' "$san"
    printf 'subjectKeyIdentifier=%s\n' "$skid"
    printf 'authorityKeyIdentifier=%s\n' "$akid"
    printf '%s' "$extra"
    printf '%s' "$sect"
  } > "$cnf"
  SERIAL=$((SERIAL + 1))
  set -- req -new -x509 -config "$cnf" -key "$KEYS/$k.pem" -subj "$subj" \
    -set_serial "$SERIAL" -not_before "$nb" -not_after "$na" -out "$T/$name.pem"
  local signer=$k
  if [ "$iss" != self ]; then
    signer=$(cat "$T/$iss.key")
    set -- "$@" -CA "$T/$iss.pem" -CAkey "$KEYS/$signer.pem"
  fi
  case $(cat "$KEYS/$signer.type" 2>/dev/null || echo rsa) in
    ed25519 | ed448) ;;
    *) set -- "$@" "-$md" ;;
  esac
  local s
  for s in $sigopt; do
    set -- "$@" -sigopt "$s"
  done
  if [ "$ver" = v1 ]; then
    set -- "$@" -x509v1
  else
    set -- "$@" -extensions sec
  fi
  # Filter the expected `openssl req` informational notice emitted when `-CA`
  # and `-CAkey` sign a subject public key provided via `-key`.
  if ! "$OPENSSL" "$@" 2> "$T/err"; then
    cat "$T/err" >&2
    exit 1
  fi
  grep -v 'Not using -key or -newkey' "$T/err" >&2 || true
  echo "$k" > "$T/$name.key"
}

# trusted NAME OUT FLAGS...: writes an OpenSSL `TRUSTED CERTIFICATE` copy of `NAME`.
trusted() {
  local name=$1 out=$2
  shift 2
  "$OPENSSL" x509 -in "$T/$name.pem" "$@" -trustout -out "$T/$out.pem"
}

CASE=""
# begin CASE TC EXPECT [EXPECT-AWS-LC-RS]
begin() {
  CASE=$OUT/$1
  rm -rf "$CASE"
  mkdir -p "$CASE"
  : > "$CASE/chain.pem"
  : > "$CASE/ca.pem"
  C_TC=$2
  C_EXPECT=$3
  C_EXPECT_AWS=${4:-$3}
  C_AT=$ATTIME
}
at() { C_AT=$1; }
chain() { local c; for c in "$@"; do cat "$T/$c.pem" >> "$CASE/chain.pem"; done; }
ca() { local c; for c in "$@"; do cat "$T/$c.pem" >> "$CASE/ca.pem"; done; }
# default NAME [FILENAME]: copies `NAME` into `defaults/` under its OpenSSL subject hash filename.
default() {
  local f=${2:-$("$OPENSSL" x509 -noout -subject_hash -in "$T/$1.pem").0}
  mkdir -p "$CASE/defaults"
  cp "$T/$1.pem" "$CASE/defaults/$f"
}
tag_of() { if [ "$1" = "$2" ]; then echo EXACT; else echo DV-TLS-3; fi; }
# end NOTE: evaluates the oracle and writes `verdict.txt`.
end() {
  [ -s "$CASE/ca.pem" ] || ca U
  local v e
  read -r v e <<< "$(oracle "$CASE" "$C_AT")"
  if [ "$v" = REJECT ] && { [ "$C_EXPECT" = ACCEPT ] || [ "$C_EXPECT_AWS" = ACCEPT ]; }; then
    echo "unsafe accept: $(basename "$CASE") expects ACCEPT and the oracle REJECTs ($e)" >&2
    exit 1
  fi
  {
    echo "oracle=$v"
    echo "error=$e"
    echo "attime=$C_AT"
    echo "expect=$C_EXPECT"
    echo "expect-aws-lc-rs=$C_EXPECT_AWS"
    echo "tag=$(tag_of "$v" "$C_EXPECT")"
    echo "tag-aws-lc-rs=$(tag_of "$v" "$C_EXPECT_AWS")"
    echo "tc=$C_TC"
    echo "note=$1"
    echo "openssl=$VERSION"
    echo "auth_level=$AUTH_LEVEL"
  } > "$CASE/verdict.txt"
  printf '%-28s %-7s %-4s expect=%-7s %s\n' "$(basename "$CASE")" "$v" "$e" "$C_EXPECT" "$1"
}

# ---------------------------------------------------------------------
# Key generation.
# ---------------------------------------------------------------------
for k in root rootb rootc rootd rootp rootv1 rootnoku rootbare int int2 int3 \
  leaf ss ss2 ss3 wrong brg z depth ba bb other rx q; do
  key "$k" rsa
done
key small rsa1024
key ecsi ec
key ecl ec
key ecc ecc:ecl
key ed ed25519
key edl ed25519
key ed448 ed448
key p521 ec521
key pss rsapss
key bp bp256
key k1 k256
for k in ed edl; do echo ed25519 > "$KEYS/$k.type"; done

# ---------------------------------------------------------------------
# Baseline PKI hierarchy.
# ---------------------------------------------------------------------
UNK="1.3.6.1.4.1.55555.1=critical,ASN1:UTF8String:unknown"
mk R root "/CN=Corpus Root" self ca
mk U other "/CN=Unrelated Root" self ca
mk I int "/CN=Corpus Int" R ca
mk L leaf "/CN=server.test" I leaf
mk LR leaf "/CN=server.test" R leaf
mk L1 leaf "/CN=v1.server.test" I bare v1
mk L1R leaf "/CN=v1.server.test" R bare v1

# `gencerts.sh` topology: shared DN across root and leaves without `basicConstraints`.
GDN="/C=US/ST=California/L=San Francisco/O=ZooKeeper/emailAddress=dev@zk.test/CN=zk.test"
mk gR root "$GDN" self bare
mk gL30 leaf "$GDN" gR bare v1
mk gL35 leaf "$GDN" gR bare

begin tc01a-gen30 TC-01a ACCEPT; ca gL30; chain gL30; end "gen-3.0 v1 leaf as CA file (path b)"
begin tc01b-gen35 TC-01b REJECT; ca gL35; chain gL35; end "gen-3.5 v3 leaf with AKID as CA file"
begin tc02-gen35 TC-02 REJECT; ca gR; chain gL35; end "gencerts root (no basicConstraints), v3 leaf"
begin tc02-gen30 TC-02 REJECT; ca gR; chain gL30
end "gencerts root, self-signed v1 leaf (error 18)"
begin tc02-catrue TC-02 ACCEPT; ca R; chain LR; end "CA:TRUE root, v3 leaf"
begin tc03 TC-03 ACCEPT; ca R; chain L I; end "root in CA file, leaf + intermediate sent"

# Self-signed leaf certificates (verification path b).
mk S ss "/CN=ss.server.test" self leaf
mk Sx ss "/CN=ss.server.test" self leaf "ext=$UNK"
mk S5 ss3 "/CN=ss5.server.test" self leaf bc=critical,CA:TRUE \
  ku=critical,digitalSignature,keyEncipherment,keyCertSign
trusted S Stsa -addtrust serverAuth
trusted Sx Sxtsa -addtrust serverAuth
begin tc04 TC-04 ACCEPT; ca S; chain S; end "self-signed CA:FALSE leaf in CA file"
begin tc04-trusted TC-04 ACCEPT; ca Stsa; chain S; end "same leaf as TRUSTED CERTIFICATE serverAuth"
begin tc04-crit TC-04 REJECT; ca Sx; chain Sx
end "self-signed leaf with unknown critical extension"
begin tc04-crit-trusted TC-04 REJECT; ca Sxtsa; chain Sx
end "same, TRUSTED CERTIFICATE serverAuth"
begin tc05 TC-05 ACCEPT; ca S5; chain S5; end "self-signed CA:TRUE leaf in CA file"
begin tc06 TC-06 REJECT; ca R; chain S; end "self-signed leaf not in CA file"
begin tc07 TC-07 REJECT; ca R; chain L; end "missing intermediate"
begin tc08 TC-08 ACCEPT; ca I R; chain L; end "intermediate and root in CA file, leaf sent"
begin tc09 TC-09 REJECT; ca I; chain L; end "only the intermediate in CA file (partial chain)"

# Validity intervals and boundary timestamps.
mk Lexp leaf "/CN=server.test" I leaf na=$EARLY_NA
mk Lny leaf "/CN=server.test" I leaf nb=$LATE_NB
mk Ledge leaf "/CN=server.test" I leaf na=$EDGE
mk Lnb leaf "/CN=server.test" I leaf nb=$EDGE
mk Sedge ss "/CN=ss.server.test" self leaf na=$EDGE
mk Iedge int "/CN=Corpus Int" R ca na=$EDGE
mk Le leaf "/CN=server.test" Iedge leaf
mk Redge rootd "/CN=Corpus Edge Root" self ca na=$EDGE
mk LRedge leaf "/CN=server.test" Redge leaf
begin tc10-expired TC-10 REJECT; ca R; chain Lexp I; end "expired leaf"
begin tc10-notyet TC-10 REJECT; ca R; chain Lny I; end "leaf not yet valid"
begin tc10b-leaf-a-na TC-10b REJECT; ca R; chain Ledge I; end "now == notAfter of leaf, path a"
begin tc10b-leaf-a-na1 TC-10b ACCEPT; at $((ATTIME - 1)); ca R; chain Ledge I
end "now == notAfter - 1 of leaf, path a"
begin tc10b-leaf-b-na TC-10b REJECT; ca Sedge; chain Sedge; end "now == notAfter of leaf, path b"
begin tc10b-leaf-b-na1 TC-10b ACCEPT; at $((ATTIME - 1)); ca Sedge; chain Sedge
end "now == notAfter - 1 of leaf, path b"
begin tc10b-int-na TC-10b REJECT; ca R; chain Le Iedge
end "now == notAfter of sent intermediate"
begin tc10b-int-na1 TC-10b ACCEPT; at $((ATTIME - 1)); ca R; chain Le Iedge
end "now == notAfter - 1 of sent intermediate"
begin tc10b-anchor-na TC-10b REJECT; ca Redge; chain LRedge
end "now == notAfter of CA-file anchor"
begin tc10b-anchor-na1 TC-10b ACCEPT; at $((ATTIME - 1)); ca Redge; chain LRedge
end "now == notAfter - 1 of CA-file anchor"
begin tc10b-leaf-nb TC-10b ACCEPT; ca R; chain Lnb I; end "now == notBefore of leaf"

mk Iexp int "/CN=Corpus Int" R ca na=$EARLY_NA
mk Lie leaf "/CN=server.test" Iexp leaf
mk Rexp rootc "/CN=Corpus Old Root" self ca na=$EARLY_NA
mk Lre leaf "/CN=server.test" Rexp leaf
begin tc11 TC-11 REJECT; ca R; chain Lie Iexp; end "expired sent intermediate"
begin tc12 TC-12 REJECT; ca Iexp R; chain Lie; end "expired intermediate in CA file as the anchor"
begin tc13 TC-13 REJECT; ca Rexp; chain Lre; end "expired root in CA file"

# Extended key usage (EKU) purpose checks.
mk Lcli leaf "/CN=server.test" I leaf eku=clientAuth
mk Lnoeku leaf "/CN=server.test" I leaf eku=none
mk Lany leaf "/CN=server.test" I leaf eku=anyExtendedKeyUsage
mk Lname leaf "/CN=other.test" I leaf san=DNS:other.test,IP:192.0.2.1
begin tc14-client TC-14 REJECT; ca R; chain Lcli I; end "leaf EKU clientAuth only"
begin tc14-noeku TC-14 ACCEPT; ca R; chain Lnoeku I; end "leaf without EKU"
begin tc14-anyeku TC-14 REJECT; ca R; chain Lany I; end "leaf EKU anyExtendedKeyUsage only"
begin tc15 TC-15 ACCEPT; ca R; chain Lname I; end "names for another host (no name check)"

# Non-CA intermediate certificates.
mk Icf int "/CN=Corpus Int" R bare bc=critical,CA:FALSE ku=critical,digitalSignature
mk Inobc int "/CN=Corpus Int" R bare ku=critical,keyCertSign,cRLSign
mk Inoku int "/CN=Corpus Int" R ca ku=critical,digitalSignature
for i in cf nobc noku; do
  mk "L$i" leaf "/CN=server.test" "I$i" leaf
  mk "L1$i" leaf "/CN=v1.server.test" "I$i" bare v1
done
begin tc16-cafalse TC-16 REJECT; ca R; chain Lcf Icf; end "intermediate CA:FALSE"
begin tc16-nobc TC-16 REJECT; ca R; chain Lnobc Inobc; end "intermediate without basicConstraints"
begin tc16-noku TC-16/TC-44a REJECT; ca R; chain Lnoku Inoku
end "CA:TRUE intermediate without keyCertSign"

# Path length constraints.
mk I1p int "/CN=Corpus P Int 1" R ca bc=critical,CA:TRUE,pathlen:0
mk I2p int2 "/CN=Corpus P Int 2" I1p ca
mk Lp leaf "/CN=server.test" I2p leaf
mk L1p leaf "/CN=v1.server.test" I2p bare v1
begin tc17 TC-17 REJECT; ca R; chain Lp I2p I1p; end "pathLenConstraint 0 on first intermediate"

# Name constraints.
mk Inc int "/CN=Corpus NC Int" R ca ext=nameConstraints=critical,excluded\;DNS:server.test
mk Lnc leaf "/CN=server.test" Inc leaf
mk Idn int "/CN=Corpus DN Int" R ca ext=nameConstraints=critical,permitted\;dirName:dn_sect \
  sect="[dn_sect]" sect="O=Corpus"
mk Ldn leaf "/O=Corpus/CN=dn.server.test" Idn leaf san=DNS:dn.server.test
begin tc18-excl TC-18 REJECT; ca R; chain Lnc Inc; end "excluded dNSName covers the leaf SAN"
begin tc18-dirname TC-18 REJECT; ca R; chain Ldn Idn
end "permitted directoryName covers the leaf DN"

# Unrecognized critical X.509 extensions.
mk Lcrit leaf "/CN=server.test" I leaf "ext=$UNK"
mk Icrit int "/CN=Corpus Crit Int" R ca "ext=$UNK"
mk Lic leaf "/CN=server.test" Icrit leaf
mk L1ic leaf "/CN=v1.server.test" Icrit bare v1
begin tc19-leaf TC-19 REJECT; ca R; chain Lcrit I; end "unknown critical extension in leaf"
begin tc19-int TC-19 REJECT; ca R; chain Lic Icrit; end "unknown critical extension in intermediate"

# OpenSSL `TRUSTED CERTIFICATE` auxiliary trust/reject OIDs.
trusted I Irej -addreject serverAuth
trusted I Icli -addtrust clientAuth
mk Scli ss2 "/CN=sscli.server.test" self leaf eku=clientAuth
trusted Scli Sclitsa -addtrust serverAuth
begin tc21a TC-21 REJECT; ca R Irej; chain L I; end "CA file holds I with serverAuth reject list"
begin tc21a-trust TC-21 REJECT; ca R Icli; chain L I
end "CA file holds I with clientAuth trust list"
begin tc21a-v1 TC-21 REJECT; ca R Irej; chain L1 I
end "v1 leaf, CA file holds I with reject list"
begin tc21c TC-21 ACCEPT; ca Sclitsa; chain Scli
end "self-signed clientAuth leaf, TRUSTED serverAuth"

# Cross-signed root (`RXc` is an expired cross-certificate of `RX` issued by `R`).
mk RX rx "/CN=Corpus X Root" self ca
mk RXc rx "/CN=Corpus X Root" R ca na=$EARLY_NA
mk IX int2 "/CN=Corpus X Int" RX ca
mk LX leaf "/CN=server.test" IX leaf
begin tc20-both TC-20 ACCEPT; ca R RX; chain LX IX RXc
end "two paths, one through an expired cross-certificate"
begin tc20-cross TC-20 REJECT; ca R; chain LX IX RXc
end "only the path through the expired cross-certificate"

# Weak cryptographic algorithms and key sizes (OpenSSL security level 2).
mk Lsha1 leaf "/CN=server.test" I leaf md=sha1
mk Lsmall small "/CN=server.test" I leaf
begin tc22-sha1 TC-22 REJECT; ca R; chain Lsha1 I; end "SHA-1-signed leaf"
begin tc23-rsa1024 TC-23 REJECT; ca R; chain Lsmall I; end "RSA-1024 leaf"

# Algorithms outside the `ring` / `aws-lc-rs` provider support matrix.
mk L1sha1 leaf "/CN=v1.server.test" I bare v1 md=sha1
mk L1small small "/CN=v1.server.test" I bare v1
mk Led448 ed448 "/CN=server.test" I leaf
mk Lp521 p521 "/CN=server.test" I leaf
mk Lpss pss "/CN=server.test" I leaf
mk Lbp bp "/CN=server.test" I leaf
mk Lk1 k1 "/CN=server.test" I leaf
mk Lsha224 leaf "/CN=server.test" I leaf md=sha224
mk Iec ecsi "/CN=Corpus EC Int" R ca
mk Lec224 leaf "/CN=server.test" Iec leaf md=sha224
mk Lec512 leaf "/CN=server.test" Iec leaf md=sha512
mk Lec256 leaf "/CN=server.test" Iec leaf
begin tc33-sha1 TC-33 REJECT; ca R; chain L1sha1 I; end "SHA-1-signed v1 leaf"
begin tc33-rsa1024 TC-33 REJECT; ca R; chain L1small I; end "RSA-1024 v1 leaf"
begin tc34-ed448 TC-34 REJECT; ca R; chain Led448 I; end "Ed448 leaf"
begin tc34-p521 TC-34 REJECT ACCEPT; ca R; chain Lp521 I; end "ECDSA P-521 leaf"
begin tc34-pss TC-34 REJECT; ca R; chain Lpss I; end "id-RSASSA-PSS SPKI leaf"
begin tc46-brainpool TC-46 REJECT; ca R; chain Lbp I; end "brainpoolP256r1 leaf"
begin tc46-secp256k1 TC-46 REJECT; ca R; chain Lk1 I; end "secp256k1 leaf"
begin tc48-rsa-sha224 TC-48 REJECT; ca R; chain Lsha224 I; end "sha224WithRSAEncryption leaf"
begin tc48-ec-sha224 TC-48 REJECT; ca R; chain Lec224 Iec
end "ecdsa-with-SHA224 leaf, P-256 issuer"
begin tc48-ec-sha512 TC-48 REJECT ACCEPT; ca R; chain Lec512 Iec
end "ecdsa-with-SHA512 leaf, P-256 issuer"
begin tc48-ec-sha256 TC-48 ACCEPT; ca R; chain Lec256 Iec
end "control: ecdsa-with-SHA256 leaf, P-256 issuer"

# Extended key usage, critical policy extensions, and `CA:TRUE` leaves.
mk Isgc int "/CN=Corpus SGC Int" R ca eku=1.3.6.1.4.1.311.10.3.3
mk Lsgc leaf "/CN=server.test" I leaf eku=1.3.6.1.4.1.311.10.3.3
mk Lsgci leaf "/CN=server.test" Isgc leaf
mk Ipc int "/CN=Corpus PC Int" R ca ext=policyConstraints=critical,inhibitPolicyMapping:0
mk Lpc leaf "/CN=server.test" Ipc leaf
mk Iiap int "/CN=Corpus IAP Int" R ca ext=inhibitAnyPolicy=critical,0
mk Liap leaf "/CN=server.test" Iiap leaf
mk Lcatrue leaf "/CN=server.test" I leaf bc=critical,CA:TRUE
begin tc36-leaf TC-36 REJECT; ca R; chain Lsgc I; end "leaf EKU msSGC only"
begin tc36-int TC-36 REJECT; ca R; chain Lsgci Isgc; end "v3 leaf, intermediate EKU msSGC only"
begin tc37-pc TC-37 REJECT; ca R; chain Lpc Ipc; end "critical policyConstraints on intermediate"
begin tc37-iap TC-37 REJECT; ca R; chain Liap Iiap; end "critical inhibitAnyPolicy on intermediate"
begin tc38 TC-38 REJECT; ca U; chain L1R; end "v1 leaf, root absent from every trust source"
begin tc45-catrue TC-45 REJECT; ca R; chain Lcatrue I; end "non-self-signed CA:TRUE v3 leaf"

# Default trust store directory (`SSL_CERT_DIR`) resolution.
begin tc24-dir TC-24 ACCEPT; ca U; default R; chain L I; end "root only in SSL_CERT_DIR, hash name"
begin tc24-wronghash TC-24 REJECT; ca U; default R 00000000.0; chain L I
end "root in SSL_CERT_DIR, wrong hash name"
begin tc27-order TC-27 ACCEPT; ca R; chain L U I
end "extra unrelated certificate in the sent chain"

# X.509 v1 leaves issued under valid CA chains.
mk I2 int2 "/CN=Corpus Int 2" I ca
mk L1d leaf "/CN=v1.server.test" I2 bare v1
begin tc29a TC-29 ACCEPT; ca R; chain L1R; end "v1 leaf issued by the root"
begin tc29b TC-29 ACCEPT; ca R; chain L1 I; end "v1 leaf below a sent intermediate"
begin tc29c TC-29 ACCEPT; ca I R; chain L1; end "v1 leaf below a CA-file intermediate"
begin tc29d TC-29 ACCEPT; ca R; chain L1d I2 I; end "v1 leaf two intermediates deep"
begin tc29e TC-29 ACCEPT; ca U; default R; chain L1 I; end "v1 leaf, root only in SSL_CERT_DIR"

mk Iw wrong "/CN=Corpus Int" R ca
mk L1w leaf "/CN=v1.server.test" Iw bare v1
mk Iecli int "/CN=Corpus Int" R ca eku=clientAuth
mk L1ecli leaf "/CN=v1.server.test" Iecli bare v1
mk L1exp leaf "/CN=v1.server.test" I bare v1 na=$EARLY_NA
mk L1edge leaf "/CN=v1.server.test" I bare v1 na=$EDGE
mk L1ie leaf "/CN=v1.server.test" Iexp bare v1
mk L1re leaf "/CN=v1.server.test" Rexp bare v1
mk L1ie2 leaf "/CN=v1.server.test" Iedge bare v1
begin tc30a TC-30 REJECT; ca R; chain L1w I; end "v1 leaf signature does not verify"
begin tc30b TC-30 REJECT; ca R; chain L1noku Inoku; end "v1 leaf, issuer without keyCertSign"
begin tc30c-cafalse TC-30 REJECT; ca R; chain L1cf Icf; end "v1 leaf, issuer CA:FALSE"
begin tc30c-nobc TC-30 REJECT; ca R; chain L1nobc Inobc
end "v1 leaf, issuer without basicConstraints"
begin tc30d TC-30 REJECT; ca R; chain L1p I2p I1p; end "v1 leaf, pathLenConstraint exceeded"
begin tc30e-leaf TC-30 REJECT; ca R; chain L1exp I; end "expired v1 leaf"
begin tc30e-int TC-30 REJECT; ca R; chain L1ie Iexp; end "v1 leaf, expired intermediate"
begin tc30e-root TC-30 REJECT; ca Rexp; chain L1re; end "v1 leaf, expired root"
begin tc30e-leaf-na TC-30 REJECT; ca R; chain L1edge I; end "now == notAfter of v1 leaf"
begin tc30e-int-na TC-30 REJECT; ca R; chain L1ie2 Iedge
end "v1 leaf, now == notAfter of intermediate"
begin tc30f TC-30 REJECT; ca R; chain L1ecli Iecli; end "v1 leaf, issuer EKU clientAuth"

# X.509 v1 intermediates and X.509 v1 root anchors.
mk Iv1 int "/CN=Corpus V1 Int" R bare v1
mk Lv1i leaf "/CN=server.test" Iv1 leaf noakid
mk L1v1i leaf "/CN=v1.server.test" Iv1 bare v1
mk Rv1 rootv1 "/CN=Corpus V1 Root" self bare v1
mk LRv1 leaf "/CN=server.test" Rv1 leaf noakid
mk L1Rv1 leaf "/CN=v1.server.test" Rv1 bare v1
mk IRv1 int "/CN=Corpus Int Under V1" Rv1 ca noakid
mk LIRv1 leaf "/CN=server.test" IRv1 leaf
mk L1IRv1 leaf "/CN=v1.server.test" IRv1 bare v1
begin tc31-v3 TC-31 REJECT; ca R; chain Lv1i Iv1; end "v1 intermediate, v3 leaf"
begin tc31-v1 TC-31 REJECT; ca R; chain L1v1i Iv1; end "v1 intermediate, v1 leaf"
begin tc32-v3 TC-32 ACCEPT; ca Rv1; chain LRv1; end "v1 root, v3 leaf"
begin tc32-v1 TC-32 ACCEPT; ca Rv1; chain L1Rv1; end "v1 root, v1 leaf"
begin tc32-v3-int TC-32 ACCEPT; ca Rv1; chain LIRv1 IRv1; end "v1 root, v3 intermediate, v3 leaf"
begin tc32-v1-int TC-32 ACCEPT; ca Rv1; chain L1IRv1 IRv1; end "v1 root, v3 intermediate, v1 leaf"

# Chain depth of seven intermediates.
prev=R
for i in 1 2 3 4 5 6 7; do
  mk "D7_$i" depth "/CN=Seven $i" "$prev" ca
  prev=D7_$i
done
mk L7 leaf "/CN=server.test" D7_7 leaf
mk L17 leaf "/CN=v1.server.test" D7_7 bare v1
begin tc35 TC-35 REJECT; ca R; chain L7 D7_7 D7_6 D7_5 D7_4 D7_3 D7_2 D7_1
end "v3 leaf under seven intermediates"
begin tc39-seven TC-39 ACCEPT; ca R; chain L17 D7_7 D7_6 D7_5 D7_4 D7_3 D7_2 D7_1
end "v1 leaf under seven intermediates"

# Self-issued EC intermediate under an RSA `pathlen:0` root (ensuring
# signature-algorithm mismatch prevents `check_issued` from short-circuiting the
# v1 leaf directly to the root).
mk Rp0 rootp "/CN=Corpus P0 Root" self ca bc=critical,CA:TRUE,pathlen:0
mk Isi ecsi "/CN=Corpus P0 Root" Rp0 ca
mk L1si leaf "/CN=v1.server.test" Isi bare v1
mk L1sgc leaf "/CN=v1.server.test" Isgc bare v1
begin tc39-selfissued TC-39 ACCEPT; ca Rp0; chain L1si Isi
end "v1 leaf, self-issued intermediate, root pathlen 0"
begin tc39-sgc TC-39 ACCEPT; ca R; chain L1sgc Isgc; end "v1 leaf, intermediate EKU msSGC only"

# AKID mismatch against root SKID (`Ralias` shares the root DN and key pair but
# carries a distinct SKID and signs `Iak`).
mk Ralias root "/CN=Corpus Root" self ca skid=DEADBEEF
mk Iak int "/CN=Corpus AK Int" Ralias ca
mk L1ak leaf "/CN=v1.server.test" Iak bare v1
mk Lak leaf "/CN=server.test" Iak leaf
begin tc40-akid TC-40 REJECT; ca R; chain L1ak Iak
end "v1 leaf, intermediate AKID does not match root SKID"
begin tc44c TC-44 REJECT; ca R; chain Lak Iak
end "v3 leaf, intermediate AKID does not match root SKID"

mk Iexp2 int "/CN=Corpus Int" R ca na=$EARLY_NA
begin tc40-expvalid-a TC-40 ACCEPT; ca R; chain L1 Iexp2 I
end "v1 leaf, expired then valid same-name intermediate"
begin tc40-expvalid-b TC-40 ACCEPT; ca R; chain L1 I Iexp2
end "v1 leaf, valid then expired same-name intermediate"

mk Rb rootb "/CN=Corpus Root" self ca
mk LRnoak leaf "/CN=server.test" R leaf noakid
begin tc40-roots-ab TC-40 REJECT; ca R Rb; chain L1R; end "v1 leaf, two same-subject roots"
begin tc40-roots-ba TC-40 REJECT; ca Rb R; chain L1R
end "v1 leaf, two same-subject roots, other order"
begin tc40-roots-v3-ab TC-40 REJECT; ca R Rb; chain LRnoak
end "v3 leaf without AKID, two same-subject roots"
begin tc40-roots-v3-ba TC-40 REJECT; ca Rb R; chain LRnoak; end "same, other order"

begin tc40-sent-noku-first TC-40 REJECT; ca R; chain L1 Inoku I
end "v1 leaf, same-name issuers sent, no keyCertSign first"
begin tc40-sent-noku-last TC-40 ACCEPT; ca R; chain L1 I Inoku
end "v1 leaf, same-name issuers sent, no keyCertSign last"
begin tc40-trust-noku-first TC-40 REJECT; ca R Inoku I; chain L1
end "v1 leaf, same-name issuers in CA file"
begin tc40-trust-noku-last TC-40 REJECT; ca R I Inoku; chain L1; end "same, other CA-file order"

# Name constraints evaluated above X.509 v1 leaves.
mk Indn int "/CN=Corpus NDN Int" R ca ext=nameConstraints=critical,excluded\;dirName:dn_sect \
  sect="[dn_sect]" sect="O=Corpus"
mk L1ndn leaf "/O=Corpus/CN=v1dn.server.test" Indn bare v1
mk Incn int "/CN=Corpus NCN Int" R ca ext=nameConstraints=critical,permitted\;DNS:.allowed.test
mk L1cn leaf "/CN=host.other.test" Incn bare v1
mk L1cnok leaf "/CN=host.allowed.test" Incn bare v1
mk Lcn leaf "/CN=host.other.test" Incn leaf san=none
mk Inem int "/CN=Corpus NEM Int" R ca ext=nameConstraints=critical,permitted\;email:.allowed.test
mk L1em leaf "/CN=v1em/emailAddress=a@other.test" Inem bare v1
begin tc41-dirname TC-41 REJECT; ca R; chain L1ndn Indn
end "v1 leaf DN in excluded directoryName"
begin tc41-cn TC-41 REJECT; ca R; chain L1cn Incn; end "v1 leaf CN outside permitted dNSName"
begin tc41-cn-ok TC-41 ACCEPT; ca R; chain L1cnok Incn; end "v1 leaf CN inside permitted dNSName"
begin tc41-email TC-41 REJECT; ca R; chain L1em Inem
end "v1 leaf emailAddress outside permitted rfc822Name"
begin tc44e TC-44 REJECT; ca R; chain Lcn Incn
end "v3 leaf CN without SAN outside permitted dNSName"

# Maximum chain depth bounds (100 vs. 101 intermediates).
prev=R
for i in $(seq 1 101); do
  mk "D_$i" depth "/CN=Depth $i" "$prev" ca
  prev=D_$i
done
mk L1d100 leaf "/CN=v1.server.test" D_100 bare v1
mk L1d101 leaf "/CN=v1.server.test" D_101 bare v1
begin tc42-depth100 TC-42 ACCEPT; ca R; chain L1d100
for i in $(seq 100 -1 1); do chain "D_$i"; done
end "v1 leaf under 100 intermediates"
begin tc42-depth101 TC-42 REJECT; ca R; chain L1d101
for i in $(seq 101 -1 1); do chain "D_$i"; done
end "v1 leaf under 101 intermediates (error 22)"

# Path-building work budget exhaustion (16 same-subject bridge certificates across two tiers).
for i in $(seq 1 16); do mk "BB_$i" bb "/CN=Budget B" R ca; done
for i in $(seq 1 16); do mk "BA_$i" ba "/CN=Budget A" BB_1 ca; done
mk L1ba leaf "/CN=v1.server.test" BA_1 bare v1
mk Lba leaf "/CN=server.test" BA_1 leaf
budget_ca() { local i; ca R; for i in $(seq 1 16); do ca "BA_$i" "BB_$i"; done; }
begin tc42-budget-v1 TC-42 REJECT; budget_ca; chain L1ba
end "v1 leaf, same-name bridges exhaust WORK_BUDGET"
begin tc42-budget-v3 TC-42 REJECT; budget_ca; chain Lba
end "v3 leaf, same-name bridges exhaust WORK_BUDGET"
begin tc42-crit TC-42 REJECT; ca R; chain L1ic Icrit
end "v1 leaf, unknown critical extension on intermediate"

# Verification path (b) same-subject ambiguity in the trust store.
mk S1 ss "/CN=same.server.test" self leaf skid=none noakid
mk S2 ss2 "/CN=same.server.test" self leaf skid=none noakid
begin tc43-ab TC-43 REJECT; ca S1 S2; chain S1
end "self-signed leaf, two same-subject CA-file entries"
begin tc43-ba TC-43 REJECT; ca S2 S1; chain S1; end "same, other CA-file order"
begin tc43-mimic TC-43 REJECT; ca S2; chain S1; end "self-signed mimic of a CA-file entry"

# OpenSSL post-chain-construction constraint verification.
mk Ib int "/CN=Corpus B Int" Rp0 ca
mk Lb leaf "/CN=server.test" Ib leaf
mk Lnoak leaf "/CN=server.test" I leaf noakid
begin tc44b TC-44 REJECT; ca Rp0; chain Lb Ib; end "root pathlen 0 over one intermediate"
begin tc44d TC-44 REJECT; ca R; chain Lnoak Iw I
end "same-name intermediates, wrong key first, leaf without AKID"

# `keyCertSign` post-check (`L` carries the AKID of `int`, so both `Inoku` and
# `I` satisfy `check_issued`).
begin tc47a-noku-first TC-47 REJECT; ca R; chain L Inoku I
end "sent same-name intermediates, no keyCertSign first"
begin tc47a-noku-last TC-47 REJECT; ca R; chain L I Inoku
end "sent same-name intermediates, no keyCertSign last"
begin tc47b-bridge-noku TC-47 REJECT; ca R Inoku; chain L I
end "bridge without keyCertSign, good one sent"
begin tc47b-bridge-ok TC-47 REJECT; ca R I; chain L Inoku
end "good bridge, one without keyCertSign sent"

# (c) `Rnk` shares the anchor subject DN, matches `check_issued` for `LRnoak`
# (no AKID), and omits `keyCertSign`. (d) `RXr` cross-certifies `R` under `Q`,
# where `Q` omits `keyCertSign`.
mk Rnk int2 "/CN=Corpus Root" R ca ku=critical,digitalSignature
mk Q q "/CN=Corpus Q" self ca ku=critical,cRLSign
mk RXr root "/CN=Corpus Root" Q ca
begin tc47c TC-47 REJECT; ca R; chain LRnoak Rnk
end "sent anchor-name certificate without keyCertSign"
begin tc47d TC-47 REJECT; ca R; chain LR RXr Q
end "sent cross-certificate of the anchor, its issuer without keyCertSign"

# Untrusted intermediates above a trust-store bridge or self-signed certificate.
mk B brg "/CN=Corpus Bridge" I ca
mk LB leaf "/CN=server.test" B leaf
mk L1B leaf "/CN=v1.server.test" B bare v1
mk Z z "/CN=Corpus Z" self ca
mk Zp z "/CN=Corpus Z" R ca
mk LZ leaf "/CN=server.test" Z leaf
mk L1Z leaf "/CN=v1.server.test" Z bare v1
begin tc49a-v3 TC-49 REJECT; ca R B; chain LB I; end "bridge in CA file, its issuer only sent"
begin tc49a-v1 TC-49 REJECT; ca R B; chain L1B I; end "same, v1 leaf"
begin tc49b-v3 TC-49 REJECT; ca R; chain LZ Z Zp; end "self-signed Z sent before Z'"
begin tc49b-v1 TC-49 REJECT; ca R; chain L1Z Z Zp; end "same, v1 leaf"
begin tc49c-v3 TC-49 ACCEPT; ca R; chain LZ Zp Z; end "Z' sent before self-signed Z"
begin tc49c-v1 TC-49 ACCEPT; ca R; chain L1Z Zp Z; end "same, v1 leaf"

# Leaf keyUsage and Netscape certificate-type purpose constraints.
mk Lkcs leaf "/CN=server.test" I leaf ku=critical,keyCertSign
mk Lns leaf "/CN=server.test" I leaf ext=nsCertType=client
mk Skcs ss "/CN=sskcs.server.test" self leaf ku=critical,keyCertSign
mk Sns ss "/CN=ssns.server.test" self leaf ext=nsCertType=client
begin tc50-ku TC-50 REJECT; ca R; chain Lkcs I; end "leaf keyUsage keyCertSign only"
begin tc50-ns TC-50 REJECT; ca R; chain Lns I; end "leaf nsCertType client only"
begin tc50-ku-b TC-50 REJECT; ca Skcs; chain Skcs; end "self-signed leaf keyUsage keyCertSign only"
begin tc50-ns-b TC-50 REJECT; ca Sns; chain Sns; end "self-signed leaf nsCertType client only"

# Root keyUsage requirements and leaf certificates present in the CA bundle.
mk Rnoku rootnoku "/CN=Corpus NoKU Root" self ca ku=critical,cRLSign
mk LRnoku leaf "/CN=server.test" Rnoku leaf
mk Rbare rootbare "/CN=Corpus Bare Root" self bare
mk LRbare leaf "/CN=server.test" Rbare leaf
mk S1v1 ss3 "/CN=v1ss.server.test" self bare v1
mk Red ed "/CN=Corpus Ed Root" self ca
mk Led edl "/CN=server.test" Red leaf
begin tc51-root-noku TC-51 REJECT; ca Rnoku; chain LRnoku; end "root keyUsage without keyCertSign"
begin tc51-root-bare TC-51 REJECT; ca Rbare; chain LRbare
end "root without basicConstraints and keyUsage"
begin tc51-ss-v1 TC-51 ACCEPT; ca S1v1; chain S1v1; end "self-signed v1 leaf in CA file"
begin tc51-leaf-in-ca TC-51 ACCEPT; ca LR R; chain LR
end "non-self-signed leaf in CA file, root present"
begin tc51-ed25519 TC-51 ACCEPT; ca Red; chain Led; end "Ed25519 chain"

# Self-issued intermediate certificates and path-length / name-constraint interaction.
mk CAp0 int2 "/CN=Corpus P0 CA" R ca bc=critical,CA:TRUE,pathlen:0
mk Isi2 ecsi "/CN=Corpus P0 CA" CAp0 ca
mk Lsi2 leaf "/CN=server.test" Isi2 leaf
mk L1si2 leaf "/CN=v1.server.test" Isi2 bare v1
mk CAnc int2 "/CN=Corpus NC CA" R ca ext=nameConstraints=critical,permitted\;DNS:.ok.test
mk Isi3 ecsi "/CN=Corpus NC CA" CAnc ca san=DNS:bad.test
mk Lsi3 leaf "/CN=www.ok.test" Isi3 leaf san=DNS:www.ok.test
mk L1si3 leaf "/CN=www.ok.test" Isi3 bare v1
begin tc52a-v3 TC-52 REJECT; ca R; chain Lsi2 Isi2 CAp0
end "self-issued intermediate below pathlen-0 CA, v3 leaf"
begin tc52a-v1 TC-52 ACCEPT; ca R; chain L1si2 Isi2 CAp0; end "same, v1 leaf"
begin tc52b-v3 TC-52 REJECT; ca R; chain Lsi3 Isi3 CAnc
end "self-issued intermediate SAN outside subtree, v3 leaf"
begin tc52b-v1 TC-52 ACCEPT; ca R; chain L1si3 Isi3 CAnc; end "same, v1 leaf"

# X.500 canonical DN comparison (`Ic` vs. case, whitespace, and `PrintableString` variants).
mk Ic int3 "/CN=Corpus Canon" R ca
mk Icase int3 "/CN=CORPUS CANON" R ca
mk Ispace int3 "/CN=Corpus  Canon" R ca
mk Iprint int3 "/CN=Corpus Canon" R ca mask=pkix
mk Lc leaf "/CN=server.test" Ic leaf
mk L1c leaf "/CN=v1.server.test" Ic bare v1
for v in case space print; do
  begin "tc53-$v-v3" TC-53 REJECT; ca R; chain Lc "I$v"
  end "issuer name matches only canonically ($v), v3 leaf"
  begin "tc53-$v-v1" TC-53 ACCEPT; ca R; chain L1c "I$v"
  end "issuer name matches only canonically ($v), v1 leaf"
done

# Compressed EC public point encoding.
mk Lecc ecc "/CN=server.test" I leaf
begin tc54-leaf TC-54 REJECT ACCEPT; ca R; chain Lecc I; end "leaf with compressed P-256 point"

# ---------------------------------------------------------------------
# Additional key types (appended after earlier cases to preserve serial numbers).
# ---------------------------------------------------------------------
key mid rsa1536
key big rsa16384
key dsa dsa
key dsal dsa
key ec384 ec384
key ecci ecc:ecsi
echo ed448 > "$KEYS/ed448.type"

# Low-level DER manipulation helpers operating on lowercase hex streams.
DER_AWK='
function h2d(h,  i, n) {
  n = 0
  for (i = 1; i <= length(h); i++) {
    n = n * 16 + index("0123456789abcdef", substr(h, i, 1)) - 1
  }
  return n
}
function hdr(s, p,  b, k) {
  b = h2d(substr(s, p + 2, 2))
  if (b < 128) {
    HL = 4; CL = 2 * b
  } else {
    k = b - 128; HL = 4 + 2 * k; CL = 2 * h2d(substr(s, p + 4, 2 * k))
  }
}
function tlv(tag, c,  n) {
  n = length(c) / 2
  if (n < 128) return tag sprintf("%02x", n) c
  if (n < 256) return tag "81" sprintf("%02x", n) c
  if (n < 65536) return tag "82" sprintf("%04x", n) c
  return tag "83" sprintf("%06x", n) c
}
'
# derhex NAME: prints the DER encoding of `$T/NAME.pem` as a single hex line.
derhex() { "$OPENSSL" x509 -in "$T/$1.pem" -outform DER | xxd -p | tr -d '\n'; echo; }
# derkids: splits the outer TLV on stdin into its immediate child TLVs, one per line.
derkids() {
  awk "$DER_AWK"'{ hdr($0, 1); p = 1 + HL; e = p + CL
    while (p < e) { hdr($0, p); print substr($0, p, HL + CL); p += HL + CL } }'
}
# derwrap TAG: wraps concatenated hex lines from stdin in a outer TLV with `TAG`.
derwrap() { awk -v t="$1" "$DER_AWK"'{ c = c $0 } END { print tlv(t, c) }'; }

# redo NAME ISSUER OUT EDIT: pipes the `TBSCertificate` child TLVs of `NAME`
# through `EDIT`, re-signs the modified `TBSCertificate` with `ISSUER`
# (RSA-SHA256), and writes `$T/OUT.pem`.
redo() {
  local name=$1 iss=$2 out=$3 edit=$4 hex tbs alg sig ikey
  hex=$(derhex "$name")
  tbs=$(printf '%s\n' "$hex" | derkids | sed -n 1p | derkids | "$edit" | derwrap 30)
  alg=$(printf '%s\n' "$hex" | derkids | sed -n 2p)
  printf '%s\n' "$tbs" | xxd -r -p > "$T/tbs.der"
  ikey="$KEYS/$(cat "$T/$iss.key").pem"
  sig=$("$OPENSSL" dgst -sha256 -sign "$ikey" "$T/tbs.der" | xxd -p | tr -d '\n')
  { printf '%s\n%s\n' "$tbs" "$alg"; printf '00%s\n' "$sig" | derwrap 03; } | derwrap 30 |
    xxd -r -p > "$T/$out.der"
  {
    echo "-----BEGIN CERTIFICATE-----"
    "$OPENSSL" base64 -in "$T/$out.der"
    echo "-----END CERTIFICATE-----"
  } > "$T/$out.pem"
  cp "$T/$name.key" "$T/$out.key"
}
# `TBSCertificate` transformation filters (operating on child fields: version,
# serialNumber, signature, issuer, validity, subject, subjectPublicKeyInfo, extensions).
neg_serial() { awk 'NR == 2 { print "020180"; next } { print }'; }
# Duplicates the `subjectKeyIdentifier` extension entry.
dup_ext() {
  local e
  while read -r e; do
    case $e in
      a3*)
        printf '%s\n' "$e" | derkids | derkids |
          awk '{ print } /^30..0603551d0e/ { print }' | derwrap 30 | derwrap a3
        ;;
      *) printf '%s\n' "$e" ;;
    esac
  done
}
# Encodes `notBefore` (2025) as `GeneralizedTime` instead of RFC 5280 `UTCTime`.
gen_time() {
  local e n=0
  while read -r e; do
    n=$((n + 1))
    if [ "$n" = 5 ]; then
      printf '%s\n' "$e" | derkids | sed '1s/^170d/180f3230/' | derwrap 30
    else
      printf '%s\n' "$e"
    fi
  done
}
# Encodes the `notBefore` `UTCTime` length in non-canonical BER long form (`81 0d`).
long_time() {
  local e n=0
  while read -r e; do
    n=$((n + 1))
    if [ "$n" = 5 ]; then
      printf '%s\n' "$e" | derkids | sed '1s/^170d/17810d/' | derwrap 30
    else
      printf '%s\n' "$e"
    fi
  done
}
# Strips the explicit `version` field (yielding v1) while retaining v3 extensions.
v1_ext() { sed 1d; }
# Prepends an explicit X.509 v2 version tag (`[0] EXPLICIT INTEGER 1`) to a v1 `TBSCertificate`.
v2() { echo a003020101; cat; }

# crl NAME ISSUER: generates an empty CRL signed by `ISSUER` at `$T/NAME.pem`.
crl() {
  local d=$T/crl-$1 ikey
  mkdir -p "$d"
  : > "$d/index.txt"
  echo 01 > "$d/crlnumber"
  printf '[ca]\ndefault_ca=c\n[c]\ndatabase=%s/index.txt\ncrlnumber=%s/crlnumber\n' \
    "$d" "$d" > "$d/cnf"
  echo 'default_md=sha256' >> "$d/cnf"
  ikey="$KEYS/$(cat "$T/$2.key").pem"
  "$OPENSSL" ca -gencrl -config "$d/cnf" -cert "$T/$2.pem" -keyfile "$ikey" \
    -crl_lastupdate "$NB" -crl_nextupdate "$NA" -out "$T/$1.pem" 2> "$T/err" || {
    cat "$T/err" >&2
    exit 1
  }
}
# hash NAME: prints the 8-hex-digit OpenSSL subject hash of `$T/NAME.pem`.
hash() { "$OPENSSL" x509 -noout -subject_hash -in "$T/$1.pem"; }
# default_text FILENAME TEXT: writes raw `TEXT` to `defaults/FILENAME`.
default_text() { mkdir -p "$CASE/defaults"; printf '%s\n' "$2" > "$CASE/defaults/$1"; }
BAD_BLOCK="-----BEGIN CERTIFICATE-----
MIIB!!!!notbase64
-----END CERTIFICATE-----"

# ---------------------------------------------------------------------
# Upstream `ssl/gencerts.sh` topologies (v3 leaf without SKID/AKID, which
# OpenSSL 3.2+ classifies as self-issued when subject DN equals issuer DN).
# ---------------------------------------------------------------------
mk gLm3 leaf "$GDN" gR bare skid=none noakid
begin tc01c-m3 TC-01a ACCEPT; ca gLm3; chain gLm3
end "legacy testSSL leaf: v3 leaf without SKID and AKID as CA file (path b)"
begin tc02-m3 TC-02 REJECT; ca gR; chain gLm3
end "gencerts root, legacy testSSL leaf (self-signed, error 18)"
if command -v keytool > /dev/null 2>&1; then
  CDIR=$(cd "${ZK_C_DIR:-$RDIR/../zookeeper-client-c}" && pwd)
  mkdir -p "$T/gencerts"
  (cd "$T/gencerts" && PATH=$(dirname "$OPENSSL"):$PATH \
    bash "$CDIR/ssl/gencerts.sh" zk.test > /dev/null 2>&1)
  cp "$T/gencerts/server.crt" "$T/gsrv.pem"
  cp "$T/gencerts/root.crt" "$T/groot.pem"
  begin tc01b-gencerts TC-01b REJECT; ca gsrv; chain gsrv
  end "real gencerts.sh: server.crt as CA file ($VERSION)"
  begin tc02-gencerts TC-02 REJECT; ca groot; chain gsrv
  end "real gencerts.sh: root.crt as CA file ($VERSION)"
else
  echo "keytool not found: skipping live gencerts.sh corpus cases" >&2
fi

# ---------------------------------------------------------------------
# `SSL_CERT_DIR` hash-directory lookup and mixed CA bundle contents.
# ---------------------------------------------------------------------
mk I2s int "/CN=Corpus Int" R ca
HR=$(hash R)
HI=$(hash I)
begin tc08-defaults TC-08 ACCEPT; ca I; default R; chain L
end "intermediate in CA file, root in SSL_CERT_DIR"
begin tc29f TC-29 ACCEPT; ca I; default R; chain L1
end "v1 leaf, bridge in CA file, root in SSL_CERT_DIR"
begin tc24-gap TC-24 REJECT; ca U; default R "$HR.1"; chain L I
end "root in SSL_CERT_DIR as .1 without .0 (gap)"
begin tc24-nonhash TC-24 REJECT; ca U; default R root.pem; chain L I
end "root in SSL_CERT_DIR under a non-hash name"
begin tc24-bad0 TC-24 REJECT; ca U; default_text "$HR.0" "$BAD_BLOCK"; default R "$HR.1"
chain L I
end ".0 does not load, valid root in .1"
begin tc24-corrupt "TC-24(corrupt)" REJECT; ca U; default R "$HR.0"
printf '%s\n' "$BAD_BLOCK" >> "$CASE/defaults/$HR.0"; chain L I
end ".0 with the root before a corrupt block"
begin tc24-other-subject "TC-24(other-subject)" REJECT; ca U; default I "$HI.0"
cat "$T/R.pem" >> "$CASE/defaults/$HI.0"; chain L
end "<hash(I)>.0 holds I and the root, no file under the root hash"
begin tc24-twodirs TC-24 ACCEPT; ca U; default I; mkdir -p "$CASE/defaults2"
cp "$T/R.pem" "$CASE/defaults2/$HR.0"; chain L
end "SSL_CERT_DIR list of two directories: I in the first, root in the second"

crl crlR R
begin tc25b-a TC-25b REJECT; ca crlR; chain L I
end "CA file with only a CRL, root in no trust source"
begin tc25b-b TC-25b ACCEPT; ca crlR; default R; chain L I
end "CA file with only a CRL, root in SSL_CERT_DIR"
"$OPENSSL" x509 -in "$T/R.pem" | sed 's/ CERTIFICATE-----$/ X509 CERTIFICATE-----/' > "$T/Rold.pem"
trusted U Uplain
cp "$KEYS/other.pem" "$T/otherkey.pem"
begin tc21-mix TC-21 ACCEPT; ca crlR otherkey U Uplain R Rold R; chain L I
end "CA file with CRL, key, unrelated, X509 CERTIFICATE, plain TRUSTED and duplicate blocks"
begin tc21b-ab "TC-21(b)" REJECT; ca R I2s Irej; chain L
end "non-rejected store issuer of the leaf first, rejected one second"
begin tc21b-ba "TC-21(b)" REJECT; ca R Irej I2s; chain L
end "rejected store issuer of the leaf first, non-rejected one second"
trusted I Irejc -addreject clientAuth
begin tc21a-rejcli TC-21 ACCEPT; ca R Irejc; chain L I
end "CA file holds I with a clientAuth reject list (not a rejected input)"

# ---------------------------------------------------------------------
# Subject names, EKU purpose, and intermediate ordering.
# ---------------------------------------------------------------------
mk Lnosan leaf "/CN=other.test" I leaf san=none
mk Lip leaf "/CN=server.test" I leaf san=IP:198.51.100.7
mk Lecli3 leaf "/CN=server.test" Iecli leaf
mk Lnssgc leaf "/CN=server.test" I leaf eku=2.16.840.1.113730.4.1
mk L2 leaf "/CN=server.test" I2 leaf
begin tc15-nosan TC-15 ACCEPT; ca R; chain Lnosan I
end "no SAN, CN for another host (no name check)"
begin tc15-ip TC-15 ACCEPT; ca R; chain Lip I; end "IP SAN for another address (no name check)"
begin tc14-int TC-14 REJECT; ca R; chain Lecli3 Iecli
end "v3 leaf, intermediate EKU clientAuth only"
begin tc36-nssgc TC-36 REJECT; ca R; chain Lnssgc I; end "leaf EKU Netscape SGC only"
begin tc27-reorder TC-27 ACCEPT; ca R; chain L2 I I2; end "sent intermediates out of order"

# ---------------------------------------------------------------------
# Weak and non-standard signature/key algorithms.
# ---------------------------------------------------------------------
mk Lmd5 leaf "/CN=server.test" I leaf md=md5
mk Isha1 int "/CN=Corpus SHA1 Int" R ca md=sha1
mk Lisha1 leaf "/CN=server.test" Isha1 leaf
mk Imd5 int "/CN=Corpus MD5 Int" R ca md=md5
mk Limd5 leaf "/CN=server.test" Imd5 leaf
mk Lmid mid "/CN=server.test" I leaf
mk Ismall small "/CN=Corpus Small Int" R ca
mk Lismall leaf "/CN=server.test" Ismall leaf
mk Ssmall small "/CN=sssmall.server.test" self leaf
mk Idsa dsa "/CN=Corpus DSA Int" R ca
mk Ldsa dsal "/CN=server.test" Idsa leaf
mk Ldsal dsal "/CN=server.test" I leaf
mk L1dsa dsal "/CN=v1.server.test" I bare v1
mk Lpsssig leaf "/CN=server.test" I leaf sigopt=rsa_padding_mode:pss sigopt=rsa_pss_saltlen:32
begin tc22-md5 TC-22 REJECT; ca R; chain Lmd5 I; end "MD5-signed leaf"
begin tc22-sha1-int TC-22 REJECT; ca R; chain Lisha1 Isha1; end "SHA-1-signed intermediate"
begin tc22-md5-int TC-22 REJECT; ca R; chain Limd5 Imd5; end "MD5-signed intermediate"
begin tc23-rsa1536 TC-23 REJECT; ca R; chain Lmid I; end "RSA-1536 leaf"
begin tc23-rsa1024-int TC-23 REJECT; ca R; chain Lismall Ismall; end "RSA-1024 intermediate"
begin tc23-rsa1024-b TC-23 REJECT; ca Ssmall; chain Ssmall
end "RSA-1024 self-signed leaf in CA file (path b)"
begin tc23-dsa TC-23 REJECT; ca R; chain Ldsa Idsa; end "DSA intermediate and DSA leaf"
begin tc23-dsa-leaf TC-23 REJECT; ca R; chain Ldsal I; end "DSA leaf below an RSA intermediate"
begin tc33-dsa TC-33 REJECT; ca R; chain L1dsa I; end "DSA v1 leaf"
begin tc48-rsapss-sig TC-48 ACCEPT; ca R; chain Lpsssig I
end "control: RSA-PSS signature by an rsaEncryption key"

mk Ip521 p521 "/CN=Corpus P521 Int" R ca
mk Lip521 leaf "/CN=server.test" Ip521 leaf md=sha512
mk Ied448 ed448 "/CN=Corpus Ed448 Int" R ca
mk Lied448 leaf "/CN=server.test" Ied448 leaf
mk Ipss pss "/CN=Corpus PSS Int" R ca
mk Lipss leaf "/CN=server.test" Ipss leaf
mk Ibp bp "/CN=Corpus BP Int" R ca
mk Libp leaf "/CN=server.test" Ibp leaf
mk Ik1 k1 "/CN=Corpus K1 Int" R ca
mk Lik1 leaf "/CN=server.test" Ik1 leaf
mk Iecc ecci "/CN=Corpus ECC Int" R ca
mk Liecc leaf "/CN=server.test" Iecc leaf
mk Iec384 ec384 "/CN=Corpus P384 Int" R ca
mk Lec384s512 leaf "/CN=server.test" Iec384 leaf md=sha512
mk Lec384s384 leaf "/CN=server.test" Iec384 leaf md=sha384
mk Ibig big "/CN=Corpus Big Int" R ca
mk Libig leaf "/CN=server.test" Ibig leaf
begin tc34-p521-int TC-34 REJECT ACCEPT; ca R; chain Lip521 Ip521; end "ECDSA P-521 intermediate"
begin tc34-ed448-int TC-34 REJECT; ca R; chain Lied448 Ied448; end "Ed448 intermediate"
begin tc34-pss-int TC-34 REJECT; ca R; chain Lipss Ipss; end "id-RSASSA-PSS SPKI intermediate"
begin tc46-brainpool-int TC-46 REJECT; ca R; chain Libp Ibp; end "brainpoolP256r1 intermediate"
begin tc46-secp256k1-int TC-46 REJECT; ca R; chain Lik1 Ik1; end "secp256k1 intermediate"
begin tc54-int TC-54 REJECT ACCEPT; ca R; chain Liecc Iecc
end "intermediate with compressed P-256 point"
begin tc48-p384-sha512 TC-48 REJECT ACCEPT; ca R; chain Lec384s512 Iec384
end "ecdsa-with-SHA512 leaf, P-384 issuer"
begin tc48-p384-sha384 TC-48 ACCEPT; ca R; chain Lec384s384 Iec384
end "control: ecdsa-with-SHA384 leaf, P-384 issuer"
begin tc48-rsa16384 TC-48 REJECT; ca R; chain Libig Ibig; end "RSA-16384 intermediate"

# ---------------------------------------------------------------------
# X.509 v2 and non-canonical DER encodings tolerated by OpenSSL.
# ---------------------------------------------------------------------
redo L1 I L1v2 v2
redo Iv1 R Iv2 v2
mk L1v2i leaf "/CN=v1.server.test" Iv1 bare v1
redo L I Lneg neg_serial
redo L I Ldup dup_ext
redo L I Lgt gen_time
redo L I Lv1x v1_ext
redo L I Llt long_time
redo I R Idup dup_ext
begin tc45-v2-leaf TC-45 REJECT; ca R; chain L1v2 I; end "X.509 v2 leaf"
begin tc45-v2-int TC-45 REJECT; ca R; chain L1v2i Iv2; end "X.509 v2 intermediate, v1 leaf"
begin tc28-neg-serial TC-28 REJECT; ca R; chain Lneg I; end "leaf with a negative serial number"
begin tc28-dup-ext TC-28 REJECT; ca R; chain Ldup I; end "leaf with a duplicate extension"
# Both `webpki` and OpenSSL accept `GeneralizedTime` dates prior to 2050
# (control case), whereas BER long-form length encoding is rejected by DER.
begin tc28-gen-time TC-28 ACCEPT; ca R; chain Lgt I
end "control: leaf with a GeneralizedTime notBefore before 2050"
begin tc28-long-time TC-28 REJECT; ca R; chain Llt I
end "leaf with a long-form length in the notBefore UTCTime"
begin tc28-v1-ext TC-28 REJECT; ca R; chain Lv1x I; end "v1 leaf with an extensions field"
begin tc28-dup-ext-int TC-28 REJECT; ca R; chain L Idup
end "sent intermediate with a duplicate extension"
begin tc28-ca-file TC-28 ACCEPT; ca Lneg Ldup Lgt Lv1x Llt Idup L1v2 R; chain L I
end "the odd certificates in the CA file next to a valid root"
begin tc28-ca-bridge TC-28 REJECT; ca R Idup; chain L
end "intermediate with a duplicate extension as the CA-file bridge"

# ---------------------------------------------------------------------
# OpenSSL `X509_STORE` cache and hash-directory lookup precedence:
# `CAfile` populates the in-memory store cache upfront; `SSL_CERT_DIR` hash
# files (`<hash>.N`) are loaded on demand only when no cached certificate
# matches the target subject DN, and all certificates in each read file are
# inserted into the cache. Cached store issuers take precedence over untrusted
# peer-supplied intermediates without backtracking on verification failure.
# ---------------------------------------------------------------------
SKI=$("$OPENSSL" x509 -in "$T/I.pem" -noout -ext subjectKeyIdentifier \
  | tail -n 1 | tr -d ' :')
mk Iws wrong "/CN=Corpus Int" R ca "skid=$SKI"
redo Iws R Iwsneg neg_serial
redo I R Ineg neg_serial
trusted Ineg Inegrej -addreject serverAuth
mk Lz leaf "/CN=server.test" I2 leaf
mk Rk rx "/CN=Corpus Root" self ca
mk Rold root "/CN=Corpus Root" self ca na=$EARLY_NA
HI2=$(hash I2)
HU=$(hash U)
begin tc28-rej-neg TC-28 REJECT; ca R Inegrej; chain L I
end "CA file holds I as a rejected input with a negative serial"
begin tc28-wrongkey-neg TC-28 REJECT; ca Iwsneg R; chain L I
end "CA file holds a wrong-key Corpus Int with a negative serial"
begin tc28-neg-in-hash TC-28 REJECT; ca R; default Iwsneg "$HI.0"; chain L I
end "<hash(I)>.0 holds a wrong-key Corpus Int with a negative serial"
begin tc24-partial-rej "TC-24(corrupt)" REJECT; ca R; default Irej "$HI.0"
printf '%s\n' "$BAD_BLOCK" >> "$CASE/defaults/$HI.0"; chain L I
end "<hash(I)>.0 holds a rejected I before a corrupt block"
begin tc24-partial-wrongkey "TC-24(corrupt)" REJECT; ca R; default Iws "$HI.0"
printf '%s\n' "$BAD_BLOCK" >> "$CASE/defaults/$HI.0"; chain L I
end "<hash(I)>.0 holds a wrong-key Corpus Int before a corrupt block"
begin tc24-foreign-control TC-24 ACCEPT; ca R; chain Lz I2 I
end "control: L, I2 and I sent, root in CA file"
begin tc24-foreign-wrongkey TC-24 REJECT; ca R; default Iws "$HI2.0"; chain Lz I2 I
end "<hash(I2)>.0 holds a wrong-key Corpus Int, read before the lookup of I"
begin tc24-foreign-rej TC-24 REJECT; ca R; default Irej "$HI2.0"; chain Lz I2 I
end "<hash(I2)>.0 holds a rejected I, read before the lookup of I"
begin tc24-foreign-wrongkey2 TC-24 REJECT; ca R; default U "$HI2.0"
cat "$T/Iws.pem" >> "$CASE/defaults/$HI2.0"; chain Lz I2 I
end "<hash(I2)>.0 holds U and a wrong-key Corpus Int"
begin tc24-foreign-late TC-24 ACCEPT; ca U; default R "$HR.0"
cat "$T/Iws.pem" >> "$CASE/defaults/$HR.0"; chain L I
end "control: <hash(R)>.0 holds R and a wrong-key Corpus Int, read after the lookup of I"
begin tc24-foreign-late-first TC-24 ACCEPT; ca U; default Iws "$HR.0"
cat "$T/R.pem" >> "$CASE/defaults/$HR.0"; chain L I
end "control: <hash(R)>.0 holds a wrong-key Corpus Int and R"
begin tc24-foreign-late-rej TC-24 ACCEPT; ca U; default R "$HR.0"
cat "$T/Irej.pem" >> "$CASE/defaults/$HR.0"; chain L I
end "control: <hash(R)>.0 holds R and a rejected I"
begin tc24-foreign-late-rej-ca TC-24 ACCEPT; ca R; default R "$HR.0"
cat "$T/Irej.pem" >> "$CASE/defaults/$HR.0"; chain L I
end "control: R in CA file, <hash(R)>.0 holds R and a rejected I"
begin tc24-foreign-unread TC-24 ACCEPT; ca R; default U "$HU.0"
cat "$T/Iws.pem" >> "$CASE/defaults/$HU.0"; chain L I
end "control: <hash(U)>.0 holds U and a wrong-key Corpus Int, never read"
begin tc24-cache-rekeyed-root TC-24 REJECT; ca Rk; default R; chain L I
end "CA file holds a Corpus Root of another key, the real root in SSL_CERT_DIR"
begin tc24-cache-expired-root TC-24 REJECT; ca Rold; default R; chain L I
end "CA file holds an expired Corpus Root, the renewed root in SSL_CERT_DIR"
