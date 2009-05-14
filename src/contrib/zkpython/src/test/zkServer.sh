#!/bin/bash
#
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

if [ "x$1" == "x" ]
then
    echo "USAGE: $0 startClean|start|stop hostPorts"
    exit 2
fi

if [ "x$1" == "xstartClean" ]
then
    if [ "x${base_dir}" == "x" ]
    then
    rm -rf /tmp/zkdata
    else
    rm -rf ${base_dir}/build/tmp
    fi
fi

# Make sure nothing is left over from before
#fuser -skn tcp 22182/tcp

case $1 in
start|startClean)
    if [ "x${base_dir}" == "x" ]
        then
        mkdir -p /tmp/zkdata
        java -cp ../../../zookeeper-dev.jar:../../../src/java/lib/log4j-1.2.15.jar:../../../conf org.apache.zookeeper.server.ZooKeeperServerMain 22182 /tmp/zkdata &> /tmp/zk.log &
        else
        mkdir -p ${base_dir}/build/tmp/zkdata
        java -cp ${base_dir}/zookeeper-dev.jar:${base_dir}/src/java/lib/log4j-1.2.15.jar:${base_dir}/conf org.apache.zookeeper.server.ZooKeeperServerMain 22182 ${base_dir}/build/tmp/zkdata &> ${base_dir}/build/tmp/zk.log &
    fi
        sleep 5
    ;;
stop)
    # Already killed above
    ;;
*)
    echo "Unknown command " + $1
    exit 2
esac

