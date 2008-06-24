#!/bin/sh
#
# This script cleans up old transaction logs and snapshots
#

#
# If this scripted is run out of /usr/bin or some other system bin directory
# it should be linked to and not copied. Things like java jar files are found
# relative to the canonical path of this script.
#

ZOOBIN=`readlink -f "$0"`
ZOOBINDIR=`dirname "$ZOOBIN"`

. $ZOOBINDIR/zkEnv.sh

eval `grep -e "^dataDir=" $ZOOCFG`

java "-Dzookeeper.log.dir=${ZOO_LOG_DIR}" "-Dzookeeper.root.logger=${ZOO_LOG4J_PROP}" \
     -cp $CLASSPATH $JVMFLAGS \
     org.apache.zookeeper.ZooKeeper $@
