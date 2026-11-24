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

# The build graph of the drop-in libraries with the Rust client core. This
# file comes after the legacy probes of ZkCProbes.cmake and uses their
# results. It adds the drop-in library, the programs and the ABI targets.
# ZKC_DIR is the legacy C client tree, which supplies headers and sources.
if(NOT CMAKE_VERSION VERSION_LESS 3.15)
  cmake_policy(VERSION 3.15)
endif()
if(NOT ZKC_DIR)
  message(FATAL_ERROR "ZkRustClient.cmake needs ZKC_DIR, the zookeeper-client-c directory")
endif()
get_filename_component(ZKRS_DIR "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)

option(ZOOKEEPER_BUILD_SHARED "Also build the zookeeper_mt or zookeeper_st shared library (Rust core)" OFF)
# ZKRS_PROVIDER_GIVEN records an explicit TLS provider. ZkRustTls gives a
# notice for it when TLS is off.
if(DEFINED CACHE{ZK_RUST_TLS_PROVIDER} OR DEFINED ZK_RUST_TLS_PROVIDER)
  set(ZKRS_PROVIDER_GIVEN ON)
else()
  set(ZK_RUST_TLS_PROVIDER "ring")
endif()
# Security configuration options governing TLS and SASL integration via ZkRustTls.
option(WITH_RUST_TLS "Build TLS into the Rust client also without OpenSSL (Rust core)" OFF)
set(ZK_RUST_TLS_DEFAULT_PATHS "" CACHE STRING
    "FILE:DIR of the default trust locations; empty means OPENSSLDIR, else the platform list")
set(ZKRS_WORKSPACE "" CACHE PATH "Rust workspace; empty means the parent of the drop-in project")
set(ZKRS_STATICLIB "" CACHE FILEPATH "Prebuilt libzk_ffi.a; empty means CMake runs cargo")
set(ZOOKEEPER_CARGO_VENDOR "" CACHE PATH "cargo vendor directory; cargo then runs offline (Rust core)")
set(ZK_SYSTEM_RUST_CRATES "" CACHE PATH
    "System crate directory; cargo then runs offline and writes Cargo.lock again (Rust core)")
option(ZK_WERROR "Add -Werror (/WX) to the shim flags" ON)
set(ZK_RUST_TARGET_CPU AUTO CACHE STRING
    "Rust target CPU: AUTO follows the C compiler, OFF adds no flag, else a CPU of rustc --print target-cpus")
set(ZKRS_SKIP_PROGRAMS OFF)
include("${CMAKE_CURRENT_LIST_DIR}/ZkRustDetect.cmake")

if(NOT ZOOKEEPER_CARGO_VENDOR STREQUAL "" AND NOT ZK_SYSTEM_RUST_CRATES STREQUAL "")
  message(FATAL_ERROR "give only one of -DZOOKEEPER_CARGO_VENDOR and -DZK_SYSTEM_RUST_CRATES")
endif()
zk_rust_check(ZKRS_OK ZKRS_WHY)
if(NOT ZKRS_OK)
  message(FATAL_ERROR
    "cannot build the Rust client: ${ZKRS_WHY}; zookeeper-client-c builds the legacy C client")
endif()

# The Rust modules and the legacy FindCyrusSASL module must be on the module path.
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}" "${ZKC_DIR}/../../tools/cmake/Modules")
if(NOT WITH_CYRUS_SASL STREQUAL "OFF" AND NOT CYRUS_SASL_FOUND)
  find_package(CyrusSASL)
  if(CYRUS_SASL_FOUND)
    message("-- Cyrus SASL 2.x found! will build with SASL support.")
  endif()
endif()
# ZkRustCore adds the Rust targets. WANT_SYNCAPI selects the flavor: ON
# gives mt, and OFF gives st.
include(ZkRustCore)

if(NOT ZKRS_SKIP_PROGRAMS)
  cmake_policy(PUSH)
  cmake_policy(VERSION 3.5)
  add_executable(cli ${ZKC_DIR}/src/cli.c)
  target_link_libraries(cli zookeeper)
  if(WANT_SYNCAPI AND NOT WIN32)
    add_executable(load_gen ${ZKC_DIR}/src/load_gen.c)
    target_link_libraries(load_gen zookeeper)
  endif()
  cmake_policy(POP)
endif()

if(WANT_CPPUNIT)
  message("-- zktest needs the legacy core; it is not built with the Rust core")
endif()
include("${CMAKE_CURRENT_LIST_DIR}/ZkRustPublicTests.cmake")
