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
    echo "JMX enabled by default" >&2
    # for some reason these two options are necessary on jdk6 on Ubuntu
    #   accord to the docs they are not necessary, but otw jconsole cannot
    #   do a local attach
    ZOOMAIN="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.local.only=$JMXLOCALONLY org.apache.zookeeper.server.quorum.QuorumPeerMain"
else
    echo "JMX disabled by user request" >&2
    ZOOMAIN="org.apache.zookeeper.server.quorum.QuorumPeerMain"
fi

# Only follow symlinks if readlink supports it
if readlink -f "$0" > /dev/null 2>&1
then
  ZOOBIN=`readlink -f "$0"`
else
  ZOOBIN="$0"
fi
ZOOBINDIR=`dirname "$ZOOBIN"`

. "$ZOOBINDIR"/zkEnv.sh

if [ "x$SERVER_JVMFLAGS" ]
then
    JVMFLAGS="$SERVER_JVMFLAGS $JVMFLAGS"
fi

if [ "x$2" != "x" ]
then
    ZOOCFG="$ZOOCFGDIR/$2"
fi

# if we give a more complicated path to the config, don't screw around in $ZOOCFGDIR
if [ "x`dirname $ZOOCFG`" != "x$ZOOCFGDIR" ]
then
    ZOOCFG="$2"
    echo "Using config:$2" >&2
fi

if $cygwin
then
    ZOOCFG=`cygpath -wp "$ZOOCFG"`
    # cygwin has a "kill" in the shell itself, gets confused
    KILL=/bin/kill
else
    KILL=kill
fi

echo "Using config: $ZOOCFG" >&2

if [ -z $ZOOPIDFILE ]
  then ZOOPIDFILE=$(grep dataDir "$ZOOCFG" | sed -e 's/.*=//')/zookeeper_server.pid
fi

_ZOO_DAEMON_OUT="$ZOO_LOG_DIR/zookeeper.out"

case $1 in
start)
    echo  -n "Starting zookeeper ... "
    if [ -f $ZOOPIDFILE ]; then
      if kill -0 `cat $ZOOPIDFILE` > /dev/null 2>&1; then
         echo $command already running as process `cat $ZOOPIDFILE`. 
         exit 0
      fi
    fi
    nohup $JAVA "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
    -cp "$CLASSPATH" $JVMFLAGS $ZOOMAIN "$ZOOCFG" > "$_ZOO_DAEMON_OUT" 2>&1 < /dev/null &
    if [ $? -eq 0 ]
    then
      if /bin/echo -n $! > "$ZOOPIDFILE"
      then
        sleep 1
        echo STARTED
      else
        echo FAILED TO WRITE PID
        exit 1
      fi
    else
      echo SERVER DID NOT START
      exit 1
    fi
    ;;
start-foreground)
    ZOO_CMD="exec $JAVA"
    if [ "${ZOO_NOEXEC}" != "" ]; then
      ZOO_CMD="$JAVA"
    fi
    $ZOO_CMD "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
    -cp "$CLASSPATH" $JVMFLAGS $ZOOMAIN "$ZOOCFG"
    ;;
print-cmd)
    echo "$JAVA -Dzookeeper.log.dir=\"${ZOO_LOG_DIR}\" -Dzookeeper.root.logger=\"${ZOO_LOG4J_PROP}\" -cp \"$CLASSPATH\" $JVMFLAGS $ZOOMAIN \"$ZOOCFG\" > \"$_ZOO_DAEMON_OUT\" 2>&1 < /dev/null"
    ;;
stop)
    echo -n "Stopping zookeeper ... "
    if [ ! -f "$ZOOPIDFILE" ]
    then
      echo "no zookeeper to stop (could not find file $ZOOPIDFILE)"
    else
      $KILL -9 $(cat "$ZOOPIDFILE")
      rm "$ZOOPIDFILE"
      echo STOPPED
    fi
    ;;
upgrade)
    shift
    echo "upgrading the servers to 3.*"
    $JAVA "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
    -cp "$CLASSPATH" $JVMFLAGS org.apache.zookeeper.server.upgrade.UpgradeMain ${@}
    echo "Upgrading ... "
    ;;
restart)
    shift
    "$0" stop ${@}
    sleep 3
    "$0" start ${@}
    ;;
status)
    # -q is necessary on some versions of linux where nc returns too quickly, and no stat result is output
    STAT=`$JAVA "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
             -cp "$CLASSPATH" $JVMFLAGS org.apache.zookeeper.client.FourLetterWordMain localhost \
             $(grep "^[[:space:]]*clientPort" "$ZOOCFG" | sed -e 's/.*=//') srvr 2> /dev/null    \
          | grep Mode`
    if [ "x$STAT" = "x" ]
    then
        echo "Error contacting service. It is probably not running."
        exit 1
    else
        echo $STAT
        exit 0
    fi
    ;;
*)
    echo "Usage: $0 {start|start-foreground|stop|restart|status|upgrade|print-cmd}" >&2

esac
