#!/usr/bin/env bash

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

# This script configures zookeeper-env.sh and zoo.cfg.

usage() {
  echo "
usage: $0 <parameters>
  Required parameters:
     --prefix=PREFIX               path to install into

  Optional parameters:
     --arch=i386                   OS Architecture
     --conf-dir=/etc/zookeeper     Configuration directory
     --log-dir=/var/log/zookeeper  Log directory
     --pid-dir=/var/run            PID file location
  "
  exit 1
}

template_generator() {
  REGEX='(\$\{[a-zA-Z_][a-zA-Z_0-9]*\})'
  cat $1 |
  while read line ; do
    while [[ "$line" =~ $REGEX ]] ; do
      LHS=${BASH_REMATCH[1]}
      RHS="$(eval echo "\"$LHS\"")"
      line=${line//$LHS/$RHS}
    done
    echo $line >> $2
  done
}

OPTS=$(getopt \
  -n $0 \
  -o '' \
  -l 'arch:' \
  -l 'prefix:' \
  -l 'conf-dir:' \
  -l 'log-dir:' \
  -l 'pid-dir:' \
  -l 'var-dir:' \
  -l 'uninstall' \
  -- "$@")

if [ $? != 0 ] ; then
    usage
fi

eval set -- "${OPTS}"
while true ; do
  case "$1" in
    --arch)
      ARCH=$2 ; shift 2
      ;;
    --prefix)
      PREFIX=$2 ; shift 2
      ;;
    --log-dir)
      LOG_DIR=$2 ; shift 2
      ;;
    --lib-dir)
      LIB_DIR=$2 ; shift 2
      ;;
    --conf-dir)
      CONF_DIR=$2 ; shift 2
      ;;
    --pid-dir)
      PID_DIR=$2 ; shift 2
      ;;
    --uninstall)
      UNINSTALL=1; shift
      ;;
    --var-dir)
      VAR_DIR=$2 ; shift 2
      ;;
    --)
      shift ; break
      ;;
    *)
      echo "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

for var in PREFIX; do
  if [ -z "$(eval "echo \$$var")" ]; then
    echo Missing param: $var
    usage
  fi
done

ARCH=${ARCH:-i386}
CONF_DIR=${CONF_DIR:-$PREFIX/etc/zookeeper}
LIB_DIR=${LIB_DIR:-$PREFIX/lib}
LOG_DIR=${LOG_DIR:-$PREFIX/var/log}
PID_DIR=${PID_DIR:-$PREFIX/var/run}
VAR_DIR=${VAR_DIR:-$PREFIX/var/lib}
UNINSTALL=${UNINSTALL:-0}

if [ "${ARCH}" != "i386" ]; then
  LIB_DIR=${LIB_DIR}64
fi

if [ "${UNINSTALL}" -eq "1" ]; then
  # Remove symlinks
  if [ -e ${PREFIX}/etc/zookeeper ]; then
    rm -f ${PREFIX}/etc/zookeeper
  fi
else
  # Create symlinks
  if [ ${CONF_DIR} != ${PREFIX}/etc/zookeeper ]; then
    mkdir -p ${PREFIX}/etc
    ln -sf ${CONF_DIR} ${PREFIX}/etc/zookeeper
  fi

  mkdir -p ${LOG_DIR}
  chown zookeeper:hadoop ${LOG_DIR}
  chmod 755 ${LOG_DIR}

  if [ ! -d ${PID_DIR} ]; then
    mkdir -p ${PID_DIR}
    chown zookeeper:hadoop ${PID_DIR}
    chmod 755 ${PID_DIR}
  fi

  if [ ! -d ${VAR_DIR} ]; then
    mkdir -p ${VAR_DIR}/data
    chown -R zookeeper:hadoop ${VAR_DIR}
    chmod -R 755 ${VAR_DIR}
  fi

  TFILE="/tmp/$(basename $0).$$.tmp"
  if [ -z "${JAVA_HOME}" ]; then
    if [ -e /etc/debian_version ]; then
      JAVA_HOME=/usr/lib/jvm/java-6-sun/jre
    else
      JAVA_HOME=/usr/java/default
    fi
  fi
  template_generator ${PREFIX}/share/zookeeper/templates/conf/zookeeper-env.sh $TFILE
  cp ${TFILE} ${CONF_DIR}/zookeeper-env.sh
  rm -f ${TFILE}
  template_generator ${PREFIX}/share/zookeeper/templates/conf/zoo.cfg $TFILE
  cp ${TFILE} ${CONF_DIR}/zoo.cfg
  rm -f ${TFILE}
fi
