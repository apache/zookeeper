#! /usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

#
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#

# use POSIX interface, symlink is followed automatically
ZOOBIN="${BASH_SOURCE-$0}"
ZOOBIN="$(dirname "$ZOOBIN")"
ZOOBINDIR="$(cd "$ZOOBIN" && pwd)"

if [[ -e "$ZOOBIN/../libexec/zkEnv.sh" ]]; then
  # shellcheck source=bin/zkEnv.sh
  . "$ZOOBINDIR"/../libexec/zkEnv.sh
else
  # shellcheck source=bin/zkEnv.sh
  . "$ZOOBINDIR"/zkEnv.sh
fi

# See the following page for extensive details on setting
# up the JVM to accept JMX remote management:
# http://java.sun.com/javase/6/docs/technotes/guides/management/agent.html
# by default we allow local JMX connections
if [[ -z $JMXLOCALONLY ]]; then
  JMXLOCALONLY=false
fi

if [[ -z $JMXDISABLE ]] || [[ $JMXDISABLE == 'false' ]]; then
  echo "ZooKeeper JMX enabled by default" >&2
  if [[ -z $JMXPORT ]]; then
    # for some reason these two options are necessary on jdk6 on Ubuntu
    #   accord to the docs they are not necessary, but otw jconsole cannot
    #   do a local attach
    ZOOMAIN=("-Dcom.sun.management.jmxremote" "-Dcom.sun.management.jmxremote.local.only=$JMXLOCALONLY" "org.apache.zookeeper.server.quorum.QuorumPeerMain")
  else
    if [[ -z $JMXAUTH ]]; then
      JMXAUTH=false
    fi
    if [[ -z $JMXSSL ]]; then
      JMXSSL=false
    fi
    if [[ -z $JMXLOG4J ]]; then
      JMXLOG4J=true
    fi
    echo "ZooKeeper remote JMX Port set to $JMXPORT" >&2
    echo "ZooKeeper remote JMX authenticate set to $JMXAUTH" >&2
    echo "ZooKeeper remote JMX ssl set to $JMXSSL" >&2
    echo "ZooKeeper remote JMX log4j set to $JMXLOG4J" >&2
    if [[ -z $JMXHOSTNAME ]]; then
      ZOOMAIN=("-Dcom.sun.management.jmxremote" "-Dcom.sun.management.jmxremote.port=$JMXPORT" "-Dcom.sun.management.jmxremote.authenticate=$JMXAUTH" "-Dcom.sun.management.jmxremote.ssl=$JMXSSL" "-Dzookeeper.jmx.log4j.disable=$JMXLOG4J" "org.apache.zookeeper.server.quorum.QuorumPeerMain")
    else
      echo "ZooKeeper remote JMX Hostname set to $JMXHOSTNAME" >&2
      ZOOMAIN=("-Dcom.sun.management.jmxremote" "-Djava.rmi.server.hostname=$JMXHOSTNAME" "-Dcom.sun.management.jmxremote.port=$JMXPORT" "-Dcom.sun.management.jmxremote.authenticate=$JMXAUTH" "-Dcom.sun.management.jmxremote.ssl=$JMXSSL" "-Dzookeeper.jmx.log4j.disable=$JMXLOG4J" "org.apache.zookeeper.server.quorum.QuorumPeerMain")
    fi
  fi
else
  echo "JMX disabled by user request" >&2
  ZOOMAIN=("org.apache.zookeeper.server.quorum.QuorumPeerMain")
fi

if [[ -n $SERVER_JVMFLAGS ]]; then
  JVMFLAGS="$SERVER_JVMFLAGS $JVMFLAGS"
fi
# shellcheck disable=SC2206
flags=($JVMFLAGS)
# shellcheck disable=SC2206
clientflags=($CLIENT_JVMFLAGS)

if [[ -n $2 ]]; then
  ZOOCFG="$ZOOCFGDIR/$2"
fi

# if we give a more complicated path to the config, don't screw around in $ZOOCFGDIR
if [[ "$(dirname "$ZOOCFG")" != "$ZOOCFGDIR" ]]; then
  ZOOCFG="$2"
fi

if $cygwin; then
  ZOOCFG=$(cygpath -wp "$ZOOCFG")
  # cygwin has a "kill" in the shell itself, gets confused
  KILL='/bin/kill'
else
  KILL='kill'
fi

echo "Using config: $ZOOCFG" >&2

case "$OSTYPE" in
  *solaris*)
    GREP='/usr/xpg4/bin/grep'
    ;;
  *)
    GREP='grep'
    ;;
esac
ZOO_DATADIR="$($GREP "^[[:space:]]*dataDir" "$ZOOCFG" | sed -e 's/.*=//')"
ZOO_DATADIR="$(echo -e "$ZOO_DATADIR" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
ZOO_DATALOGDIR="$($GREP "^[[:space:]]*dataLogDir" "$ZOOCFG" | sed -e 's/.*=//')"

# iff autocreate is turned off and the datadirs don't exist fail
# immediately as we can't create the PID file, etc..., anyway.
if [[ -n $ZOO_DATADIR_AUTOCREATE_DISABLE ]]; then
  if [[ ! -d "$ZOO_DATADIR/version-2" ]]; then
    echo "ZooKeeper data directory is missing at $ZOO_DATADIR fix the path or run initialize"
    exit 1
  fi

  if [[ -n $ZOO_DATALOGDIR ]] && [[ ! -d "$ZOO_DATALOGDIR/version-2" ]]; then
    echo "ZooKeeper txnlog directory is missing at $ZOO_DATALOGDIR fix the path or run initialize"
    exit 1
  fi
  flags=("-Dzookeeper.datadir.autocreate=false" "${flags[@]}")
fi

if [[ -z $ZOOPIDFILE ]]; then
  if [[ ! -d $ZOO_DATADIR ]]; then
    mkdir -p "$ZOO_DATADIR"
  fi
  ZOOPIDFILE="$ZOO_DATADIR/zookeeper_server.pid"
else
  # ensure it exists, otw stop will fail
  mkdir -p "$(dirname "$ZOOPIDFILE")"
fi

if [[ ! -w $ZOO_LOG_DIR ]]; then
  mkdir -p "$ZOO_LOG_DIR"
fi

ZOO_LOG_FILE=${ZOO_LOG_FILE:-zookeeper-$USER-server-$HOSTNAME.log}
_ZOO_DAEMON_OUT="$ZOO_LOG_DIR/zookeeper-$USER-server-$HOSTNAME.out"

case $1 in
  start)
    echo -n "Starting zookeeper ... "
    if [[ -f $ZOOPIDFILE ]]; then
      if kill -0 "$(cat "$ZOOPIDFILE")" &>/dev/null; then
        echo "already running as process $(cat "$ZOOPIDFILE")."
        exit 1
      fi
    fi
    nohup "$JAVA" "-Dzookeeper.log.dir=$ZOO_LOG_DIR" \
      "-Dzookeeper.log.file=$ZOO_LOG_FILE" \
      -XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError='kill -9 %p' \
      "${flags[@]}" "${ZOOMAIN[@]}" "$ZOOCFG" &>"$_ZOO_DAEMON_OUT" </dev/null &
    case "$OSTYPE" in
      *solaris*)
        /bin/echo "${!}\\c" >"$ZOOPIDFILE"
        ;;
      aix*)
        /bin/echo "$!\c" >"$ZOOPIDFILE"
        ;;
      *)
        echo -n $! >"$ZOOPIDFILE"
        ;;
    esac
    # this checks the exit code of the echo statements to verify the PID was written
    # shellcheck disable=SC2320,SC2181
    if [[ $? -eq 0 ]]; then
      sleep 1
      pid=$(cat "$ZOOPIDFILE")
      if ps -p "$pid" &>/dev/null; then
        echo STARTED
      else
        echo FAILED TO START
        exit 1
      fi
    else
      echo FAILED TO WRITE PID
      exit 1
    fi
    ;;
  start-foreground)
    ZOO_CMD=(exec "$JAVA")
    if [[ -n $ZOO_NOEXEC ]]; then
      ZOO_CMD=("$JAVA")
    fi
    "${ZOO_CMD[@]}" "-Dzookeeper.log.dir=$ZOO_LOG_DIR" \
      "-Dzookeeper.log.file=$ZOO_LOG_FILE" \
      -XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError='kill -9 %p' \
      "${flags[@]}" "${ZOOMAIN[@]}" "$ZOOCFG"
    ;;
  print-classpath)
    echo "CLASSPATH=$CLASSPATH"
    ;;
  print-cmd)
    echo "\"$JAVA\" -Dzookeeper.log.dir=\"$ZOO_LOG_DIR\" \
    -Dzookeeper.log.file=\"$ZOO_LOG_FILE\" \
    -XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError='kill -9 %p' \
    $(for x in "${flags[@]}"; do echo "\"$x\""; done | paste -sd' ') \
    $(for x in "${ZOOMAIN[@]}"; do echo "\"$x\""; done | paste -sd' ') \
    \"$ZOOCFG\" &>\"$_ZOO_DAEMON_OUT\" </dev/null"
    ;;
  stop)
    echo -n "Stopping zookeeper ... "
    if [[ ! -f $ZOOPIDFILE ]]; then
      echo "no zookeeper to stop (could not find file $ZOOPIDFILE)"
    else
      $KILL "$(cat "$ZOOPIDFILE")"
      rm "$ZOOPIDFILE"
      sleep 1
      echo STOPPED
    fi
    exit 0
    ;;
  version)
    ZOOMAIN=("org.apache.zookeeper.version.VersionInfoMain")
    $JAVA "${ZOOMAIN[@]}" 2>/dev/null
    ;;
  restart)
    shift
    "$0" stop "$@"
    sleep 3
    "$0" start "$@"
    ;;
  status)
    # -q is necessary on some versions of linux where nc returns too quickly, and no stat result is output
    isSSL="false"
    clientPortAddress=$($GREP "^[[:space:]]*clientPortAddress[^[:alpha:]]" "$ZOOCFG" | sed -e 's/.*=//')
    if [[ -z $clientPortAddress ]]; then
      clientPortAddress="localhost"
    fi
    clientPort=$($GREP "^[[:space:]]*clientPort[^[:alpha:]]" "$ZOOCFG" | sed -e 's/.*=//')
    if ! [[ $clientPort =~ ^[0-9]+$ ]]; then
      dataDir=$($GREP "^[[:space:]]*dataDir" "$ZOOCFG" | sed -e 's/.*=//')
      myid=$(cat "$dataDir/myid" 2>/dev/null)
      if ! [[ $myid =~ ^[0-9]+$ ]]; then
        echo "myid could not be determined, will not able to locate clientPort in the server configs."
      else
        clientPortAndAddress=$($GREP "^[[:space:]]*server.$myid=.*;.*" "$ZOOCFG" | sed -e 's/.*=//' | sed -e 's/.*;//')
        if [[ -z $clientPortAndAddress ]]; then
          echo "Client port not found in static config file. Looking in dynamic config file."
          dynamicConfigFile=$($GREP "^[[:space:]]*dynamicConfigFile" "$ZOOCFG" | sed -e 's/.*=//')
          clientPortAndAddress=$($GREP "^[[:space:]]*server.$myid=.*;.*" "$dynamicConfigFile" | sed -e 's/.*=//' | sed -e 's/.*;//')
        fi
        if [[ -z $clientPortAndAddress ]]; then
          echo "Client port not found in the server configs"
        else
          # Extracts address and port from address:port, even if address contains optional square braces
          # For example, this extracts the address 127::1 and the port 2181 from "[127::1]:2181"
          if [[ $clientPortAndAddress =~ ^\[?([^\]]*)\]?:([0-9]+) ]]; then
            clientPortAddress=${BASH_REMATCH[1]}
            clientPort=${BASH_REMATCH[2]}
          elif [[ $clientPortAndAddress =~ ^[0-9]+$ ]]; then
            clientPort=$clientPortAndAddress
          fi
        fi
      fi
    fi
    if [[ -z $clientPort ]]; then
      echo "Client port not found. Looking for secureClientPort in the static config."
      secureClientPort=$($GREP "^[[:space:]]*secureClientPort[^[:alpha:]]" "$ZOOCFG" | sed -e 's/.*=//')
      if [[ -n $secureClientPort ]]; then
        isSSL="true"
        clientPort=$secureClientPort
        clientPortAddress=$($GREP "^[[:space:]]*secureClientPortAddress[^[:alpha:]]" "$ZOOCFG" | sed -e 's/.*=//')
        if [[ -z $clientPortAddress ]]; then
          clientPortAddress="localhost"
        fi
      else
        echo "Unable to find either secure or unsecure client port in any configs. Terminating."
        exit 1
      fi
    fi
    echo "Client port found: $clientPort. Client address: $clientPortAddress. Client SSL: $isSSL."
    STAT=$("$JAVA" "-Dzookeeper.log.dir=$ZOO_LOG_DIR" "-Dzookeeper.log.file=$ZOO_LOG_FILE" \
      "${clientflags[@]}" "${flags[@]}" org.apache.zookeeper.client.FourLetterWordMain \
      "$clientPortAddress" "$clientPort" srvr "$isSSL" 2>/dev/null |
      $GREP Mode)
    if [[ -z $STAT ]]; then
      if [[ $isSSL == "true" ]]; then
        echo " "
        echo "Note: We used secureClientPort ($secureClientPort) to establish connection, but we failed. The 'status'"
        echo "  command establishes a client connection to the server to execute diagnostic commands. Please make sure you"
        echo "  provided all the Client SSL connection related parameters in the CLIENT_JVMFLAGS environment variable! E.g.:"
        echo '  CLIENT_JVMFLAGS="-Dzookeeper.clientCnxnSocket=org.apache.zookeeper.ClientCnxnSocketNetty'
        echo "  -Dzookeeper.ssl.trustStore.location=/tmp/clienttrust.jks -Dzookeeper.ssl.trustStore.password=password"
        echo "  -Dzookeeper.ssl.keyStore.location=/tmp/client.jks -Dzookeeper.ssl.keyStore.password=password"
        echo '  -Dzookeeper.client.secure=true" ./zkServer.sh status'
        echo " "
      fi
      echo "Error contacting service. It is probably not running."
      exit 1
    else
      echo "$STAT"
      exit 0
    fi
    ;;
  *)
    echo "Usage: $0 [--config <conf-dir>] {start|start-foreground|stop|version|restart|status|print-cmd}" >&2
    ;;

esac
