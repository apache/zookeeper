#!/bin/sh

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
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#

# See the following page for extensive details on setting
# up the JVM to accept JMX remote management:
# http://java.sun.com/javase/6/docs/technotes/guides/management/agent.html
# by default we allow local JMX connections
if [ "x$JMXLOCALONLY" = "x" ]
then 
    JMXLOCALONLY=false
fi

if [ "x$JMXDISABLE" = "x" ]
then
    echo "JMX enabled by default"
    # for some reason these two options are necessary on jdk6 on Ubuntu
    #   accord to the docs they are not necessary, but otw jconsole cannot
    #   do a local attach
    ZOOMAIN="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.local.only=$JMXLOCALONLY org.apache.zookeeper.server.quorum.QuorumPeerMain"
else
    echo "JMX disabled by user request"
    ZOOMAIN="org.apache.zookeeper.server.quorum.QuorumPeerMain"
fi

ZOOBIN=`readlink -f "$0"`
ZOOBINDIR=`dirname "$ZOOBIN"`

. $ZOOBINDIR/zkEnv.sh

case $1 in
start) 
    echo -n "Starting zookeeper ... "
    java  "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
    -cp $CLASSPATH $JVMFLAGS $ZOOMAIN $ZOOCFG &
    echo STARTED
    ;;
stop) 
    echo -n "Stopping zookeeper ... "
    echo kill | nc localhost $(grep clientPort $ZOOCFG | sed -e 's/.*=//')
    echo STOPPED
    ;;
upgrade)
    shift
    echo "upgrading the servers to 3.*"
    java "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
    -cp $CLASSPATH $JVMFLAGS org.apache.zookeeper.server.upgrade.UpgradeMain ${@} 
    echo "Upgrading ... "
    ;;
restart)
    shift
    $0 stop ${@}
    sleep 3
    $0 start ${@}
    ;;
status)
    STAT=`echo stat | nc localhost $(grep clientPort $ZOOCFG | sed -e 's/.*=//') 2> /dev/null| grep Mode`
    if [ "x$STAT" = "x" ]
    then
        echo "Error contacting service. It is probably not running." 
    else
        echo $STAT
    fi
    ;;
*)
    echo "Usage: $0 {start|stop|restart|status}" >&2

esac
