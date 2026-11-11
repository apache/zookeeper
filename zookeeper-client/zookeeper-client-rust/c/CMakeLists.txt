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

# This project builds the drop-in zookeeper library with the Rust client
# core. It reads the headers, the jute files and the program sources of the
# legacy C client in ../../zookeeper-client-c. It never writes to that tree.
# zookeeper-client-c/CMakeLists.txt builds the legacy C client.
cmake_minimum_required(VERSION 3.5)

# ZKC_DIR is the legacy C client tree. It is not a cache entry, because
# the drop-in always reads the tree next to it.
get_filename_component(ZKC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../zookeeper-client-c" ABSOLUTE)

# The version comes from zookeeper_version.h of the legacy tree. Thus the
# two clients always have the same version.
set(zkc_version_h "${ZKC_DIR}/include/zookeeper_version.h")
set(zkc_version_line "")
if(EXISTS "${zkc_version_h}")
  file(STRINGS "${zkc_version_h}" zkc_version_line REGEX "^#define ZOO_VERSION \"")
endif()
if(NOT zkc_version_line MATCHES "^#define ZOO_VERSION \"([0-9]+(\\.[0-9]+)*)")
  message(FATAL_ERROR "cannot read ZOO_VERSION from ${zkc_version_h}")
endif()
set(zkc_version "${CMAKE_MATCH_1}")

project(zookeeper VERSION ${zkc_version})
set(email user@zookeeper.apache.org)
set(description "zookeeper C client")

list(APPEND CMAKE_MODULE_PATH "${ZKC_DIR}/../../tools/cmake/Modules"
     "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

# The legacy options and platform probes come first. The Rust client core
# then uses their results.
include(ZkCProbes)
include(ZkRustClient)
