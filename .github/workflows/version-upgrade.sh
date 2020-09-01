#!/bin/sh

#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# Enable below when using locally/manually
# cd ../../

echo There are $# arguments to $0: $*
if [ "$#" -eq 2 ]; then
  version=$1
  new_version=$2
else
  version=`grep -A 4 "<groupId>com.linkedin.zookeeper</groupId>" pom.xml | grep "<version>" | awk 'BEGIN {FS="[<,>]"};{print $3}'`  

# just use the given version as the new version
  new_version=$1

# Below version upgrade logic is left here just in case

#  minor_version=`echo $version | cut -d'.' -f3`
#  major_version=`echo $version | cut -d'.' -f1` # should be 0
#  submajor_version=`echo $version | cut -d'.' -f2`

#  new_minor_version=`expr $minor_version + 1`
#  new_version=`echo $version | sed -e "s/${minor_version}/${new_minor_version}/g"`
#  new_version="$major_version.$submajor_version.$new_minor_version"
fi
echo "bump up: $version -> $new_version"

for MODULE in $(find . -name 'pom.xml')
do 
  echo "bump up $MODULE"
  sed -i "s/${version}/${new_version}/g" $MODULE
  grep -C 1 "$new_version" $MODULE
done
