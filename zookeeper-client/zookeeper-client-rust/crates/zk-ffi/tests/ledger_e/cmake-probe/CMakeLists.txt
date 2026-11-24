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

# The CMake probe of tests/ledger_e_build.rs.
#
# A CMake application includes the drop-in C client with
# add_subdirectory(). The probe prints the usage requirements of the
# zookeeper target. Thus the test sees the compile definitions and the link
# interface that an application gets. The probe only configures. It builds
# nothing.
#
# ZK_CLIENT_SOURCE_DIR is the directory of the drop-in CMake project. The
# default is zookeeper-client-rust/c. The other options go to the
# CMakeLists.txt of the drop-in project unchanged.

cmake_minimum_required(VERSION 3.15)
project(zk_ledger_e_probe C)

if(NOT ZK_CLIENT_SOURCE_DIR)
  get_filename_component(ZK_CLIENT_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../c" ABSOLUTE)
endif()
add_subdirectory("${ZK_CLIENT_SOURCE_DIR}" zookeeper)

get_target_property(zk_probe_defs zookeeper INTERFACE_COMPILE_DEFINITIONS)
get_target_property(zk_probe_libs zookeeper INTERFACE_LINK_LIBRARIES)
message(STATUS "ZKPROBE defs=${zk_probe_defs}")
message(STATUS "ZKPROBE libs=${zk_probe_libs}")
