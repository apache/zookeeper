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

# Downloads an official Apache ZooKeeper binary release tarball from
# `https://archive.apache.org`, verifies its SHA-512 digest, and extracts it
# atomically for live-server integration testing.
#
# Usage: get-release-server.sh VERSION
#   Extracts into `$ZK_TEST_SERVERS_DIR/VERSION/apache-zookeeper-VERSION-bin`
#   (default: `/tmp/zk-servers`). `test-server.sh` and `test-ensemble.sh`
#   select this installation when `ZK_TEST_SERVER_VERSION=VERSION` is set.
#
# Extraction occurs inside an isolated temporary staging directory that is
# atomically renamed into place upon verification, ensuring concurrent runs
# never observe a partially extracted release directory.

set -euo pipefail
VERSION="${1:?usage: $0 VERSION}"
case "$VERSION" in
*[!0-9.]* | "") echo "bad version: $VERSION" >&2; exit 2 ;;
esac
SERVERS="${ZK_TEST_SERVERS_DIR:-/tmp/zk-servers}"
DEST="$SERVERS/$VERSION"
NAME="apache-zookeeper-$VERSION-bin"
TARBALL="$NAME.tar.gz"
URL="https://archive.apache.org/dist/zookeeper/zookeeper-$VERSION/$TARBALL"

if [ -d "$DEST/$NAME/lib" ]; then
    echo "ZooKeeper $VERSION is in $DEST/$NAME"
    exit 0
fi
if [ -e "$DEST" ]; then
    echo "$DEST exists but holds no complete release; remove it first" >&2
    exit 1
fi
mkdir -p "$SERVERS"
STAGE="$(mktemp -d "$SERVERS/.$VERSION.partial.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

# Enable `--retry-all-errors` when supported by the host `curl` binary to
# recover from transient connection resets against `archive.apache.org`.
RETRY=(--retry 3 --retry-delay 2)
if curl --help all 2>/dev/null | grep -- '--retry-all-errors' >/dev/null; then
    RETRY+=(--retry-all-errors)
fi
curl -fsSL "${RETRY[@]}" -o "$STAGE/$TARBALL" "$URL"
curl -fsSL "${RETRY[@]}" -o "$STAGE/$TARBALL.sha512" "$URL.sha512"

# Normalize both standard (`<hash>  <filename>`) and GPG (`<filename>: <hex>...`)
# `.sha512` file formats by stripping the filename and non-hexadecimal characters.
EXPECTED="$(tr -d '\n\r' < "$STAGE/$TARBALL.sha512" | sed "s|$TARBALL||" |
    tr -cd '0-9a-fA-F' | tr 'A-F' 'a-f')"
if command -v shasum >/dev/null 2>&1; then
    ACTUAL="$(shasum -a 512 "$STAGE/$TARBALL" | cut -d' ' -f1)"
else
    ACTUAL="$(sha512sum "$STAGE/$TARBALL" | cut -d' ' -f1)"
fi
if [ "${#EXPECTED}" -ne 128 ] || [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "sha512 mismatch for $TARBALL" >&2
    echo "  expected: $EXPECTED" >&2
    echo "  actual:   $ACTUAL" >&2
    exit 1
fi

tar -xzf "$STAGE/$TARBALL" -C "$STAGE"
if [ ! -d "$STAGE/$NAME/lib" ]; then
    echo "the archive has no $NAME/lib directory" >&2
    exit 1
fi
# Atomically publish the staging directory; if a concurrent invocation published
# `$DEST` first, clean up any nested staging directory and use the existing tree.
if [ ! -e "$DEST" ]; then
    mv "$STAGE" "$DEST" || true
fi
rm -rf "${DEST:?}/$(basename "$STAGE")"
if [ ! -d "$DEST/$NAME/lib" ]; then
    echo "cannot move the release to $DEST" >&2
    exit 1
fi
echo "ZooKeeper $VERSION is in $DEST/$NAME"
