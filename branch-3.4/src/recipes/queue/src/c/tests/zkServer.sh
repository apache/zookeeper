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
	rm -rf /tmp/zkdata
fi

# Make sure nothing is left over from before
if [ -r "/tmp/zk.pid" ]
then
pid=`cat /tmp/zk.pid`
kill -9 $pid
rm -f /tmp/zk.pid
fi

base_dir="../../../../.."

CLASSPATH="$CLASSPATH:${base_dir}/build/classes"
CLASSPATH="$CLASSPATH:${base_dir}/conf"

for f in "${base_dir}"/zookeeper-*.jar
do
    CLASSPATH="$CLASSPATH:$f"
done

for i in "${base_dir}"/build/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

for i in "${base_dir}"/src/java/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

CLASSPATH="$CLASSPATH:${CLOVER_HOME}/lib/clover.jar"

case $1 in
start|startClean)
	mkdir -p /tmp/zkdata
	java -cp $CLASSPATH org.apache.zookeeper.server.ZooKeeperServerMain 22181 /tmp/zkdata &> /tmp/zk.log &
        echo $! > /tmp/zk.pid
        sleep 5
	;;
stop)
	# Already killed above
	;;
*)
	echo "Unknown command " + $1
	exit 2
esac

