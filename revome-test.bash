#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
set -euo pipefail

ROOT="${1:-.}"

if [[ ! -d "$ROOT" ]]; then
  echo "Directory non trovata: $ROOT"
  exit 1
fi

echo "Root progetto: $(realpath "$ROOT")"
echo

echo "[1/4] Pulizia contenuto di src/test..."
find "$ROOT" -type d -path "*/src/test" | while read -r testdir; do
  echo "  Pulisco: $testdir"
  find "$testdir" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
done
echo

echo "[2/4] Rimozione file test residui fuori da src/test..."
find "$ROOT" -type f \( \
  -name "*Test.java" -o \
  -name "*Tests.java" -o \
  -name "*IT.java" -o \
  -name "*Test.groovy" -o \
  -name "*Tests.groovy" -o \
  -name "*IT.groovy" \
\) ! -path "*/src/main/*" | while read -r f; do
  rm -f "$f"
  echo "  Rimosso: $f"
done
echo

echo "[3/4] Ricreo cartelle base src/test dove necessario..."
find "$ROOT" -type d -path "*/src" | while read -r srcdir; do
  mkdir -p "$srcdir/test/java"
  mkdir -p "$srcdir/test/resources"
done
echo

echo "[4/4] Fine."
echo
echo "Ora puoi creare i tuoi test dentro src/test/java."
echo "Per compilare senza lanciarli usa:"
echo "  mvn clean install -DskipTests"
echo
echo "Per lanciarli quando vuoi:"
echo "  mvn test"