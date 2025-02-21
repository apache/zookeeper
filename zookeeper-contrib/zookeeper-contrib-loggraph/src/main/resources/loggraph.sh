#! /usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

make_canonical() {
  cd "$1" && pwd
}

SCRIPTDIR=$(dirname "$0")
BUILDDIR=$(make_canonical "$SCRIPTDIR"/../../../target/)
LIBDIR=$(make_canonical "$BUILDDIR"/../lib)

if [[ ! -x $BUILDDIR ]]; then
  echo
  echo
  echo "*** You need to build loggraph before running it ***"
  echo
  echo
  exit
fi

for i in "$LIBDIR"/* "$BUILDDIR"/*.jar; do
  CLASSPATH=$i:$CLASSPATH
done
export CLASSPATH

java org.apache.zookeeper.graph.LogServer "$@"
