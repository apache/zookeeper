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

cd `dirname $0`;

source network-delays.sh
source server-control.sh

all() {
    if [ "z$HEDWIG_NETWORK_DELAY" != "z" ]; then
	setup_delays $HEDWIG_NETWORK_DELAY
    fi

    start_control_server;

    ../test/hedwigtest 
    RESULT=$?
    stop_control_server;

    if [ "z$HEDWIG_NETWORK_DELAY" != "z" ]; then
	clear_delays
    else
	cat <<EOF

The environment variable HEDWIG_NETWORK_DELAY is not set, so the tests were run directly 
with a localhost server. This isn't quite realistic as usually there will be some delay between 
the client and the hedwig server. Set HEDWIG_NETWORK_DELAY to the number of milliseconds you want
to delay the packets between the server and client. 

 $ export HEDWIG_NETWORK_DELAY=500

Requires root privileges.

WARNING!!! This will modify your traffic shaping and firewall rules. If you do run with delays, 
check your firewall rules afterwards.

EOF
    fi

    exit $RESULT
}

case "$1" in
    start-control-server)
	start_control_server
	;;
    stop-control-server)
	stop_control_server
	;;
    setup-delays)
	setup_delays $2
	;;
    clear-delays)
	clear_delays
	;;
    all)
	all
	;;
    *)
	cat <<EOF
Usage: tester.sh [command]

tester.sh all
   Run through the tests, setting up and cleaning up all prerequisites.

tester.sh start-control-server
   Starts the deamon which the tests use to start and stop hedwig/zookeeper/bookeeper servers

tester.sh stop-control-server
   Stops the aforementioned daemon

tester.sh setup-delays <delay>
   Set the millisecond delay for accessing the hedwig servers for the tests.

tester.sh clear-delays
   Clear the delay for accessing the hedwig servers.
EOF
	;;
esac
