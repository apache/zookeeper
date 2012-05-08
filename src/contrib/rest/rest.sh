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

# Only follow symlinks if readlink supports it
if readlink -f "$0" > /dev/null 2>&1
then
  ZKREST=`readlink -f "$0"`
else
  ZKREST="$0"
fi
ZKREST_HOME=`dirname "$ZKREST"`

if $cygwin
then
    # cygwin has a "kill" in the shell itself, gets confused
    KILL=/bin/kill
else
    KILL=kill
fi

if [ -z $ZKREST_PIDFILE ]
    then ZKREST_PIDFILE=$ZKREST_HOME/server.pid
fi

ZKREST_MAIN=org.apache.zookeeper.server.jersey.RestMain

ZKREST_CONF=$ZKREST_HOME/conf
ZKREST_LOG=$ZKREST_HOME/zkrest.log

CLASSPATH="$ZKREST_CONF:$CLASSPATH"

for i in "$ZKREST_HOME"/lib/*.jar
do
    CLASSPATH="$i:$CLASSPATH"
done

for i in "$ZKREST_HOME"/zookeeper-*.jar
do
    CLASSPATH="$i:$CLASSPATH"
done

case $1 in
start)
    echo  "Starting ZooKeeper REST Gateway ... "
    java  -cp "$CLASSPATH" $JVMFLAGS $ZKREST_MAIN >$ZKREST_LOG 2>&1 &
    /bin/echo -n $! > "$ZKREST_PIDFILE"
    echo STARTED
    ;;
stop)
    echo "Stopping ZooKeeper REST Gateway ... "
    if [ ! -f "$ZKREST_PIDFILE" ]
    then
    echo "error: could not find file $ZKREST_PIDFILE"
    exit 1
    else
    $KILL -9 $(cat "$ZKREST_PIDFILE")
    rm "$ZKREST_PIDFILE"
    echo STOPPED
    fi
    ;;
restart)
    shift
    "$0" stop ${@}
    sleep 3
    "$0" start ${@}
    ;;
*)
    echo "Usage: $0 {start|stop|restart}" >&2

esac
