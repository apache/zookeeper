#!/bin/sh

# This script should be sourced into other zookeeper
# scripts to setup the env variables

# We use ZOOCFGDIR if defined,
# otherwise we use /etc/zookeeper
# or the conf directory that is
# a sibling of this script's directory
if [ "x$ZOOCFGDIR" = "x" ]
then
    if [ -d "/etc/zookeeper" ]
    then
        ZOOCFGDIR="/etc/zookeeper"
    else
        ZOOCFGDIR="$ZOOBINDIR/../conf"
    fi
fi

if [ -e "$ZOOCFGDIR/java.env" ]
then
    . "$ZOOCFGDIR/java.env"
fi

if [ "x$ZOO_LOG_DIR" = "x" ]
then 
    ZOO_LOG_DIR="."
fi

if [ "x$ZOO_LOG4J_PROP" = "x" ]
then 
    ZOO_LOG4J_PROP="INFO,CONSOLE"
fi

for f in ${ZOOBINDIR}/../zookeeper-*.jar
do 
    CLASSPATH="$CLASSPATH:$f"
done

ZOOLIBDIR=${ZOOLIBDIR:-$ZOOBINDIR/../lib}
for i in "$ZOOLIBDIR"/*.jar
do
    CLASSPATH="$CLASSPATH:$i"
done
#make it work for developers
for d in ${ZOOBINDIR}/../java/lib/*.jar
do
   CLASSPATH="$CLASSPATH:$d"
done


ZOOCFG="$ZOOCFGDIR/zoo.cfg"
