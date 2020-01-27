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
    echo "USAGE: $0 startClean|start|stop"
    exit 2
fi

if [ "x${base_dir}" == "x" ]
then
  PROJECT_ROOT="../../"
else
  PROJECT_ROOT=${base_dir}
fi
WORK_DIR=${PROJECT_ROOT}/zookeeper-contrib/zookeeper-contrib-zkpython/target/zkpython_tests
TEST_DIR=${PROJECT_ROOT}/zookeeper-contrib/zookeeper-contrib-zkpython/src/test


if [ -r "${WORK_DIR}/../zk.pid" ]
then
  pid=`cat "${WORK_DIR}/../zk.pid"`
  kill -9 $pid
  rm -f "${WORK_DIR}/../zk.pid"
fi

which lsof &> /dev/null
if [ $? -eq 0  ]
then
    pid=`lsof -i :22182 | grep LISTEN | awk '{print $2}'`
    if [ -n "$pid" ]
    then
        kill -9 $pid
    fi
fi




if [ "x$1" == "xstartClean" ]
then
    rm -rf ${WORK_DIR}
fi



CLASSPATH="$CLASSPATH:${PROJECT_ROOT}/zookeeper-server/target/classes"
CLASSPATH="$CLASSPATH:${zk_base}/conf"

for i in "${PROJECT_ROOT}"/zookeeper-server/target/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

for i in "${PROJECT_ROOT}"/zookeeper-server/src/main/resource/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

# Make sure nothing is left over from before
#fuser -skn tcp 22182/tcp

case $1 in
start|startClean)
    mkdir -p ${WORK_DIR}/zkdata

    rm -rf ${WORK_DIR}/ssl
    mkdir -p ${WORK_DIR}/ssl
    cp ${PROJECT_ROOT}/zookeeper-client/zookeeper-client-c/ssl/gencerts.sh ${WORK_DIR}/ssl/
    cd ${WORK_DIR}/ssl/
    ./gencerts.sh
    cd -

    sed "s#WORKDIR#${WORK_DIR}#g" ${TEST_DIR}/zoo.cfg > "${WORK_DIR}/zoo.cfg"
    java  -Dzookeeper.extendedTypesEnabled=true -Dznode.container.checkIntervalMs=100 -cp $CLASSPATH org.apache.zookeeper.server.ZooKeeperServerMain "${WORK_DIR}/zoo.cfg" &> "${WORK_DIR}/zoo.log" &
    pid=$!
    echo -n $! > ${WORK_DIR}/../zk.pid
    sleep 5
    ;;
stop)
    # Already killed above
    ;;
*)
    echo "Unknown command " + $1
    exit 2
esac

