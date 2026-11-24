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

# Standalone CMake consumer project that incorporates the
# C client via `add_subdirectory()` and links against `zookeeper` and
# `hashtable` via `target_link_libraries(zklink_consumer zookeeper hashtable)`
# identically to legacy CMake consumers. The `zookeeper` target propagates
# public include paths and the required native libraries for the Rust core.
# Because the C client scopes `-DTHREADED` to its own target, multithreaded
# consumers define `THREADED` when `WANT_SYNCAPI` is enabled.
#
# Pass `-DZK_C_SOURCE_DIR=<zookeeper-client-c>` at configure time; C client
# build options (`WITH_RUST_CLIENT`, `WANT_SYNCAPI`, `ZKRS_STATICLIB`, etc.)
# forward transparently to the subdirectory build.

cmake_minimum_required(VERSION 3.15)
project(zklink_consumer C)

if(NOT ZK_CLIENT_SOURCE_DIR)
  get_filename_component(ZK_CLIENT_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)
endif()
add_subdirectory("${ZK_CLIENT_SOURCE_DIR}" zookeeper)

add_executable(zklink_consumer "${CMAKE_CURRENT_LIST_DIR}/../link_app.c")
target_link_libraries(zklink_consumer zookeeper hashtable)
if(WANT_SYNCAPI)
  target_compile_definitions(zklink_consumer PRIVATE THREADED)
endif()
