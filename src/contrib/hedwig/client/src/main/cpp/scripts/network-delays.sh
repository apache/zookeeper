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
#

setup_delays() {

    UNAME=`uname -s`

    echo "Setting delay to ${1}ms"
    case "$UNAME" in
	Darwin|FreeBSD)
	    sudo ipfw pipe 1 config delay ${1}ms
	    sudo ipfw add pipe 1 dst-port 12349 
	    sudo ipfw add pipe 1 dst-port 12350
	    sudo ipfw add pipe 1 src-port 12349 
	    sudo ipfw add pipe 1 src-port 12350 
            ;;
	Linux)
	    sudo tc qdisc add dev lo root handle 1: prio
	    sudo tc qdisc add dev lo parent 1:3 handle 30: netem delay ${1}ms 
	    sudo tc filter add dev lo protocol ip parent 1:0 prio 3 u32 match ip dport 12349 0xffff flowid 1:3
	    sudo tc filter add dev lo protocol ip parent 1:0 prio 3 u32 match ip dport 12350 0xffff flowid 1:3
	    sudo tc filter add dev lo protocol ip parent 1:0 prio 3 u32 match ip sport 12349 0xffff flowid 1:3
	    sudo tc filter add dev lo protocol ip parent 1:0 prio 3 u32 match ip sport 12350 0xffff flowid 1:3
	    ;;
	*)
	    echo "Unknown system type, $UNAME, only Linux, Darwin & FreeBSD supported"
	    ;;
    esac
}

clear_delays() {
    UNAME=`uname -s`

    case "$UNAME" in
	Darwin|FreeBSD)
	    echo "Flushing ipfw"
	    sudo ipfw -f -q flush
            ;;
	Linux)
	    echo "Clearing delay"
	    sudo tc qdisc del dev lo root
	    ;;
	*)
	    echo "Unknown system type, $UNAME, only Linux, Darwin & FreeBSD supported"
	    ;;
    esac
}

