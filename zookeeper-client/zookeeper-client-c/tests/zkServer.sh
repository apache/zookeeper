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

# This is the port where zookeeper server runs on.
ZOOPORT=${ZOOPORT:-"22181"}

# Some tests are setting the maxClientConnections. When it is not set, we fallback to default 100
ZKMAXCNXNS=${ZKMAXCNXNS:-"100"}

EXTRA_JVM_ARGS=${EXTRA_JVM_ARGS:-""}

if [ "x$1" == "x" ]
then
    echo "USAGE: $0 startClean|start|startCleanReadOnly|startRequireSASLAuth [jaasConf] [readOnly]|stop"
    exit 2
fi




# =====
# ===== cleanup old executions
# =====

case "`uname`" in
    CYGWIN*) cygwin=true ;;
    *) cygwin=false ;;
esac


if $cygwin
then
    # cygwin has a "kill" in the shell itself, gets confused
    KILL=/bin/kill
else
    KILL=kill
fi

# Make sure nothing is left over from before
if [ -r "/tmp/zk.pid" ]
then
pid=`cat /tmp/zk.pid`
$KILL -9 $pid
rm -f /tmp/zk.pid
fi

if [ -r "${base_dir}/build/tmp/zk.pid" ]
then
pid=`cat "${base_dir}/build/tmp/zk.pid"`
$KILL -9 $pid
rm -f "${base_dir}/build/tmp/zk.pid"
fi

# [ZOOKEEPER-820] If lsof command is present, look for a process listening
# on ZOOPORT and kill it. 
which lsof &> /dev/null
if [ $? -eq 0  ]
then
    pid=`lsof -i :$ZOOPORT | grep LISTEN | awk '{print $2}'`
    if [ -n "$pid" ]
    then
        $KILL -9 $pid
    fi
fi



# =====
# ===== build classpath
# =====

if [ "x${base_dir}" == "x" ]
then
zk_base="../../../"
else
zk_base="${base_dir}"
fi

CLASSPATH="$CLASSPATH:${zk_base}/build/classes"
CLASSPATH="$CLASSPATH:${zk_base}/conf"
CLASSPATH="$CLASSPATH:${zk_base}/zookeeper-server/target/classes"

for i in "${zk_base}"/build/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

for d in "${zk_base}"/zookeeper-server/target/lib/*.jar
do
    CLASSPATH="$d:$CLASSPATH"
done

for i in "${zk_base}"/zookeeper-server/src/main/resource/lib/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done

CLASSPATH="$CLASSPATH:${CLOVER_HOME}/lib/clover*.jar"

if $cygwin
then
    CLASSPATH=`cygpath -wp "$CLASSPATH"`
fi



# =====
# ===== initialize JVM arguments
# =====

read_only=
PROPERTIES="$EXTRA_JVM_ARGS -Dzookeeper.extendedTypesEnabled=true -Dznode.container.checkIntervalMs=100"
if [ "x$1" == "xstartRequireSASLAuth" ]
then
    PROPERTIES="-Dzookeeper.sessionRequireClientSASLAuth=true $PROPERTIES"
    PROPERTIES="$PROPERTIES -Dzookeeper.authProvider.1=org.apache.zookeeper.server.auth.SASLAuthenticationProvider"
    if [ "x$2" != "x" ]
    then
        PROPERTIES="$PROPERTIES -Djava.security.auth.login.config=$2"
    fi
    if [ "x$3" != "x" ]
    then
        PROPERTIES="-Dreadonlymode.enabled=true $PROPERTIES"
        read_only=true
    fi
fi
if [ "x$1" == "xstartCleanReadOnly" ]
then
    PROPERTIES="-Dreadonlymode.enabled=true $PROPERTIES"
    read_only=true
fi



# =====
# ===== initialize data and test directories
# =====

if [ "x${base_dir}" == "x" ]
then
    tmp_dir="/tmp"
    tests_dir="tests"
else
    tmp_dir="${base_dir}/build/tmp"
    tests_dir=${base_dir}/zookeeper-client/zookeeper-client-c/tests
fi




# =====
# ===== start the ZooKeeper server
# =====

case $1 in
start|startClean|startRequireSASLAuth|startCleanReadOnly)

    if [ "x$1" == "xstartClean" ] || [ "x$1" == "xstartCleanReadOnly" ]
    then
        rm -rf "${tmp_dir}/zkdata"
    fi
    mkdir -p "${tmp_dir}/zkdata"


    # ===== initialize certificates
    certs_dir="/tmp/certs"
    rm -rf "${certs_dir}"
    mkdir -p "${certs_dir}"
    cp ${tests_dir}/../ssl/gencerts.sh "${certs_dir}/"  > /dev/null
    cd ${certs_dir} > /dev/null
    ./gencerts.sh > ./gencerts.stdout 2> ./gencerts.stderr
    cd - > /dev/null


    # ===== prepare the configs
    sed "s#TMPDIR#${tmp_dir}#g;s#CERTDIR#${certs_dir}#g;s#MAXCLIENTCONNECTIONS#${ZKMAXCNXNS}#g;s#CLIENTPORT#${ZOOPORT}#g" ${tests_dir}/zoo.cfg > "${tmp_dir}/zoo.cfg"
    if [ "x$read_only" != "x" ]
    then
        # we can put the new server to read-only mode by starting only a single instance of a three node server
        echo "server.1=localhost:22881:33881" >> ${tmp_dir}/zoo.cfg
        echo "server.2=localhost:22882:33882" >> ${tmp_dir}/zoo.cfg
        echo "server.3=localhost:22883:33883" >> ${tmp_dir}/zoo.cfg
        echo "1" > ${tmp_dir}/zkdata/myid
        main_class="org.apache.zookeeper.server.quorum.QuorumPeerMain"
    else
        main_class="org.apache.zookeeper.server.ZooKeeperServerMain"
    fi


    # ===== start the server
    java -cp "$CLASSPATH" $PROPERTIES ${main_class} ${tmp_dir}/zoo.cfg &> "${tmp_dir}/zk.log" &
    pid=$!
    echo -n $! > /tmp/zk.pid


    # ===== wait for the server to start
    if [ "x$1" == "xstartRequireSASLAuth" ] || [ "x$1" == "xstartCleanReadOnly" ]
    then
       # ===== in these cases we can not connect simply with the java client, so we are just waiting...
       sleep 4
       success=true
    else
        # ===== wait max 120 seconds for server to be ready to server clients (this handles testing on slow hosts)
        success=false
        for i in {1..120}
        do
            if ps -p $pid > /dev/null
            then
                java -cp "$CLASSPATH" $PROPERTIES org.apache.zookeeper.ZooKeeperMain -server localhost:$ZOOPORT ls / > /dev/null 2>&1
                if [ $? -ne 0  ]
                then
                    # server not up yet - wait
                    sleep 1
                else
                    # server is up and serving client connections
                    success=true
                    break
                fi
            else
                # server died - exit now
                echo -n " ZooKeeper server process failed"
                break
            fi
        done
    fi

    if $success
    then
        ## in case for debug, but generally don't use as it messes up the
        ## console test output
        echo -n " ZooKeeper server started"
    else
        echo -n " ZooKeeper server NOT started"
    fi

    ;;
stop)
    # Already killed above
    ;;
*)
    echo "Unknown command " + $1
    exit 2
esac

