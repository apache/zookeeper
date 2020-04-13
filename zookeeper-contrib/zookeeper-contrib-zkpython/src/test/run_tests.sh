#!/bin/sh
#
#  Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Usage: run_tests.sh testdir [logdir]
# logdir is optional, defaults to cwd

set -e

# get the number of command-line arguments given
ARGC=$#

# check to make sure enough arguments were given or exit
if [ $ARGC -lt 2 ]; then
    export ZKPY_LOG_DIR="."
else
    export ZKPY_LOG_DIR=$2
fi

# Find the build directory containing zookeeper.so
SO_PATH=`find ./target/ -name 'zookeeper*.so' | head -1`
PYTHONPATH=`dirname $SO_PATH`
LIB_PATH=../../zookeeper-client/zookeeper-client-c/target/c/.libs
for test in `ls $1/*_test.py`; 
do
    echo "Running $test"
    echo "Running LD_LIBRARY_PATH=$LIB_PATH:$LD_LIBRARY_PATH DYLD_LIBRARY_PATH=$LIB_PATH:$DYLD_LIBRARY_PATH PYTHONPATH=$PYTHONPATH python $test"
    LD_LIBRARY_PATH=$LIB_PATH:$LD_LIBRARY_PATH DYLD_LIBRARY_PATH=$LIB_PATH:$DYLD_LIBRARY_PATH PYTHONPATH=$PYTHONPATH python $test
done
