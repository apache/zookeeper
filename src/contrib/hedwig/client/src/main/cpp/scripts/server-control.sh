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

HEDWIGBASE=../../../../..

HEDWIGJAR=`ls $HEDWIGBASE/server/target/server-*-with-dependencies.jar`
if [ ! $? -eq 0 ]; then
    echo "\n\nCould not find server-VERSION-with-dependencies.jar. \nYou need to build the java part of hedwig. \nRun mvn package in the toplevel hedwig directory.\n\n"
    exit 1;
fi

HEDWIGSERVERTESTS=$HEDWIGBASE/server/target/test-classes/
if [ ! -e $HEDWIGSERVERTESTS ]; then
    echo "\n\nThe hedwig java server tests need to be build.\b\b"
    exit 1;
fi

export CP=.:$HEDWIGJAR:$HEDWIGSERVERTESTS

start_control_server() {
    if [ -e server-control.pid ]; then
	kill -9 `cat server-control.pid`
	rm server-control.pid
    fi
    java -cp $CP  -Dlog4j.configuration=log4j.properties org.apache.hedwig.ServerControlDaemon  <&-  1> servercontrol.out  2>&1  &
    echo $! > server-control.pid
    sleep 5
}

stop_control_server() {
    kill -9 `cat server-control.pid`
    rm server-control.pid
}
