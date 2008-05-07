#!/bin/sh
#
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#

ZOOBIN=`readlink -f "$0"`
ZOOBINDIR=`dirname "$ZOOBIN"`

. $ZOOBINDIR/zkEnv.sh

case $1 in
start) 
    echo -n "Starting zookeeper ... "
    java  "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
    -cp $CLASSPATH $JVMFLAGS com.yahoo.zookeeper.server.quorum.QuorumPeer $ZOOCFG &
    echo STARTED
    ;;
stop) 
    echo -n "Stopping zookeeper ... "
    echo kill | nc localhost $(grep clientPort $ZOOCFG | sed -e 's/.*=//')
    echo STOPPED
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
