#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# This script cleans up old transaction logs and snapshots
#

#
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#

# use POSIX interface, symlink is followed automatically
ZOOBIN="${BASH_SOURCE-$0}"
ZOOBIN="$(dirname "${ZOOBIN}")"
ZOOBINDIR="$(cd "${ZOOBIN}"; pwd)"
ZOOCFGDIR="$ZOOBINDIR/../conf"

ZOO_LOG_DIR="${ZOOBINDIR}/../logs"

CLASSPATH="$ZOOCFGDIR:$CLASSPATH"

for d in "$ZOOBINDIR"/../zookeeper-server/target/*.jar
do
   CLASSPATH="$d:$CLASSPATH"
done

for d in "$ZOOBINDIR"/../zookeeper-server/target/lib/*.jar
do
   CLASSPATH="$d:$CLASSPATH"
done

if [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    JAVA="$JAVA_HOME/bin/java"
elif type -p java; then
    JAVA=java
else
    echo "Error: JAVA_HOME is not set and java could not be found in PATH." 1>&2
    exit 1
fi

# default heap for zookeeper client
ZK_TRACE_HEAP="${ZK_TRACE_HEAP:-256}"

"$JAVA" "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=INFO,TRACEFILE" \
     -cp "$CLASSPATH" "-Xmx${ZK_TRACE_HEAP}m" \
     org.apache.zookeeper.server.instrument.TraceServer "$@"
