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

# Only follow symlinks if readlink supports it. Find the directory path
# for where this script is being executed from.
if readlink -f "$0" > /dev/null 2>&1
then
  HWBIN=`readlink -f "$0"`
else
  HWBIN="$0"
fi
HWBINDIR=`dirname "$HWBIN"`

# We use HWCFGDIR if defined, otherwise we use /etc/hedwig
# or the conf directory that is a sibling of this script's directory.
# This is to find out where the Hedwig server config file resides.
if [ "x$HWCFGDIR" = "x" ]
then
    if [ -d "/etc/hedwig" ]
    then
        HWCFGDIR="/etc/hedwig"
    else
        HWCFGDIR="$HWBINDIR/../conf"
    fi
fi

# We use HWCFG as the name of the Hedwig server config file if defined,
# otherwise use the default file name "hw_server.conf".
if [ "x$HWCFG" = "x" ]
then
    HWCFG="hw_server.conf"
fi
HWCFG="$HWCFGDIR/$HWCFG"

# If a config file is passed in directly when invoking the script,
# use that instead.
if [ "x$2" != "x" ]
then
    HWCFG="$HWCFGDIR/$2"
fi

# Find the Hedwig server jar and setup the CLASSPATH. We assume it to be
# located in a standard place relative to the location of where this script
# is executed from.
HWJAR="$HWBINDIR/../server/target/server-1.0-SNAPSHOT-jar-with-dependencies.jar"
CLASSPATH="$HWJAR:$CLASSPATH"

# Store the Hedwig server's PID (java process) in the same $HWBINDIR. 
# This is used for us to stop the server later on via this same script.
if [ -z $HWPIDFILE ]
    then HWPIDFILE=$HWBINDIR/hedwig_server.pid
fi

case $1 in
start)
    echo  "Starting Hedwig server ... "
    java  -cp "$CLASSPATH" org.apache.hedwig.server.netty.PubSubServer "$HWCFG" &
    /bin/echo -n $! > "$HWPIDFILE"
    echo STARTED
    ;;
stop)
    echo "Stopping Hedwig server ... "
    if [ ! -f "$HWPIDFILE" ]
    then
    echo "error: could not find file $HWPIDFILE"
    exit 1
    else
    kill -9 $(cat "$HWPIDFILE")
    rm "$HWPIDFILE"
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
