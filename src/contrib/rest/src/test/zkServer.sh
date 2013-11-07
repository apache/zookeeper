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
if [ -r "/tmp/zk.pid" ]
then
pid=`cat /tmp/zk.pid`
kill -9 $pid
rm -f /tmp/zk.pid
fi

if [ -r "${base_dir}/build/tmp/zk.pid" ]
then
pid=`cat ${base_dir}/build/tmp/zk.pid`
kill -9 $pid
rm -f ${base_dir}/build/tmp/zk.pid
fi

if [ "x${base_dir}" == "x" ]	
then
zk_base="../../../"
else
zk_base="${base_dir}"
fi

CLASSPATH="$CLASSPATH:${zk_base}/build/classes"
CLASSPATH="$CLASSPATH:${zk_base}/conf"

for i in "${zk_base}"/build/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

for i in "${zk_base}"/src/java/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

case $1 in
start|startClean)
    if [ "x${base_dir}" == "x" ]
        then
        mkdir -p /tmp/zkdata
        java -cp $CLASSPATH org.apache.zookeeper.server.ZooKeeperServerMain 22182 /tmp/zkdata &> /tmp/zk.log &
        echo $! > /tmp/zk.pid
        else
        mkdir -p ${base_dir}/build/tmp/zkdata
        java -cp $CLASSPATH org.apache.zookeeper.server.ZooKeeperServerMain 22182 ${base_dir}/build/tmp/zkdata &> ${base_dir}/build/tmp/zk.log &
        echo $! > ${base_dir}/build/tmp/zk.pid
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

