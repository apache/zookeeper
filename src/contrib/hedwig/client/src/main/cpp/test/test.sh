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

export LD_LIBRARY_PATH=/usr/lib/jvm/java-6-sun/jre/lib/i386/server/:/usr/lib/jvm/java-6-sun/jre/lib/i386/
export CLASSPATH=$HOME/src/hedwig/server/target/test-classes:$HOME/src/hedwig/server/lib/bookkeeper-SNAPSHOT.jar:$HOME/src/hedwig/server/lib/zookeeper-SNAPSHOT.jar:$HOME/src/hedwig/server/target/classes:$HOME/src/hedwig/protocol/target/classes:$HOME/src/hedwig/client/target/classes:$HOME/.m2/repository/commons-configuration/commons-configuration/1.6/commons-configuration-1.6.jar:$HOME/.m2/repository/org/jboss/netty/netty/3.1.2.GA/netty-3.1.2.GA.jar:$HOME/.m2/repository/commons-lang/commons-lang/2.4/commons-lang-2.4.jar:$HOME/.m2/repository/commons-collections/commons-collections/3.2.1/commons-collections-3.2.1.jar:$HOME/.m2/repository/commons-logging/commons-logging/1.1.1/commons-logging-1.1.1.jar:$HOME/.m2/repository/com/google/protobuf/protobuf-java/2.3.0/protobuf-java-2.3.0.jar:$HOME/.m2/repository/log4j/log4j/1.2.14/log4j-1.2.14.jar:$HOME/src/hedwig/client/target/classes/

./hedwigtest