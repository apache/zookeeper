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

cmake_minimum_required(VERSION 3.5)

project(zookeeper VERSION 3.7.1)
set(email user@zookeeper.apache.org)
set(description "zookeeper C client")

list(APPEND CMAKE_MODULE_PATH "${CMAKE_SOURCE_DIR}/../../tools/cmake/Modules")

# general options
if(UNIX)
  add_compile_options(-Wall -fPIC)
elseif(WIN32)
  add_compile_options(/W3)
endif()
add_definitions(-DUSE_STATIC_LIB)

# TODO: Enable /WX and /W4 on Windows. Currently there are ~1000 warnings.
# TODO: Add Solaris support.
# TODO: Add a shared library option.
# TODO: Specify symbols to export.
# TODO: Generate doxygen documentation.

# Sync API option
option(WANT_SYNCAPI "Enables Sync API support" ON)
if(WANT_SYNCAPI)
  add_definitions(-DTHREADED)
endif()

# CppUnit option
if(WIN32 OR APPLE)
  # The tests do not yet compile on Windows or macOS,
  # so we set this to off by default.
  #
  # Note that CMake does not have expressions except in conditionals,
  # so we're left with this if/else/endif pattern.
  set(DEFAULT_WANT_CPPUNIT OFF)
else()
  set(DEFAULT_WANT_CPPUNIT ON)
endif()
option(WANT_CPPUNIT "Enables CppUnit and tests" ${DEFAULT_WANT_CPPUNIT})

# SOCK_CLOEXEC
option(WANT_SOCK_CLOEXEC "Enables SOCK_CLOEXEC on sockets" OFF)
include(CheckSymbolExists)
check_symbol_exists(SOCK_CLOEXEC sys/socket.h HAVE_SOCK_CLOEXEC)
if(WANT_SOCK_CLOEXEC AND HAVE_SOCK_CLOEXEC)
  set(SOCK_CLOEXEC_ENABLED 1)
endif()

# Cyrus SASL 2.x
option(WITH_CYRUS_SASL "turn ON/OFF Cyrus SASL 2.x support, or define SASL library location (default: ON)" ON)
message("-- using WITH_CYRUS_SASL=${WITH_CYRUS_SASL}")
if(NOT WITH_CYRUS_SASL STREQUAL "OFF")
  if(NOT WITH_CYRUS_SASL STREQUAL "ON")
    set(CYRUS_SASL_ROOT_DIR "${WITH_CYRUS_SASL}")
  endif()
  find_package(CyrusSASL)
  if(CYRUS_SASL_FOUND)
    message("-- Cyrus SASL 2.x found! will build with SASL support.")
  else()
    message("-- WARNING: unable to find Cyrus SASL 2.x! will build without SASL support.")
  endif()
endif()

# The function `to_have(in out)` converts a header name like `arpa/inet.h`
# into an Autotools style preprocessor definition `HAVE_ARPA_INET_H`.
# This is then set or unset in `configure_file()` step.
#
# Note that CMake functions do not have return values; instead an "out"
# variable must be passed, and explicitly set with parent scope.
function(to_have in out)
  string(TOUPPER ${in} str)
  string(REGEX REPLACE "/|\\." "_" str ${str})
  set(${out} "HAVE_${str}" PARENT_SCOPE)
endfunction()

# include file checks
foreach(f generated/zookeeper.jute.h generated/zookeeper.jute.c)
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${f}")
    to_have(${f} name)
    set(${name} 1)
  else()
    message(FATAL_ERROR
      "jute files are missing!\n"
      "Please run 'ant compile_jute' while in the ZooKeeper top level directory.")
  endif()
endforeach()

# header checks
include(CheckIncludeFile)
set(check_headers
  arpa/inet.h
  dlfcn.h
  fcntl.h
  inttypes.h
  memory.h
  netdb.h
  netinet/in.h
  stdint.h
  stdlib.h
  string.h
  strings.h
  sys/socket.h
  sys/stat.h
  sys/time.h
  sys/types.h
  unistd.h
  sys/utsname.h)

foreach(f ${check_headers})
  to_have(${f} name)
  check_include_file(${f} ${name})
endforeach()

# function checks
include(CheckFunctionExists)
set(check_functions
  getcwd
  gethostbyname
  gethostname
  getlogin
  getpwuid_r
  gettimeofday
  getuid
  memmove
  memset
  poll
  socket
  strchr
  strdup
  strerror
  strtol)

foreach(fn ${check_functions})
  to_have(${fn} name)
  check_function_exists(${fn} ${name})
endforeach()

# library checks
set(check_libraries rt m pthread)
foreach(lib ${check_libraries})
  to_have("lib${lib}" name)
  find_library(${name} ${lib})
endforeach()

# IPv6 check
include(CheckStructHasMember)
check_struct_has_member("struct sockaddr_in6" sin6_addr "netinet/in.h" ZOO_IPV6_ENABLED)

# configure
configure_file(cmake_config.h.in ${CMAKE_CURRENT_BINARY_DIR}/include/config.h)

# hashtable library
set(hashtable_sources src/hashtable/hashtable_itr.c src/hashtable/hashtable.c)
add_library(hashtable STATIC ${hashtable_sources})
target_include_directories(hashtable PUBLIC include)
target_link_libraries(hashtable PUBLIC $<$<OR:$<PLATFORM_ID:Linux>,$<PLATFORM_ID:FreeBSD>>:m>)

# zookeeper library
set(zookeeper_sources
  src/zookeeper.c
  src/recordio.c
  generated/zookeeper.jute.c
  src/zk_log.c
  src/zk_hashtable.c
  src/addrvec.c)

if(WANT_SYNCAPI)
  list(APPEND zookeeper_sources src/mt_adaptor.c)
else()
  list(APPEND zookeeper_sources src/st_adaptor.c)
endif()

if(CYRUS_SASL_FOUND)
  list(APPEND zookeeper_sources src/zk_sasl.c)
endif()

if(WIN32)
  list(APPEND zookeeper_sources src/winport.c)
endif()

add_library(zookeeper STATIC ${zookeeper_sources})
target_include_directories(zookeeper PUBLIC include ${CMAKE_CURRENT_BINARY_DIR}/include generated)
target_link_libraries(zookeeper PUBLIC
  hashtable
  $<$<PLATFORM_ID:Linux>:rt> # clock_gettime
  $<$<PLATFORM_ID:Windows>:ws2_32>) # Winsock 2.0

option(WITH_OPENSSL "turn ON/OFF SSL support, or define openssl library location (default: ON)" ON)
message("-- using WITH_OPENSSL=${WITH_OPENSSL}")
if(NOT WITH_OPENSSL STREQUAL "OFF")
  if(NOT WITH_OPENSSL STREQUAL "ON")
    set(OPENSSL_ROOT_DIR,${WITH_OPENSSL})
  endif()
  find_package(OpenSSL)
  if(OPENSSL_FOUND)
    target_compile_definitions(zookeeper PUBLIC HAVE_OPENSSL_H)
    target_link_libraries(zookeeper PUBLIC OpenSSL::SSL OpenSSL::Crypto)
    message("-- OpenSSL libraries found! will build with SSL support.")
  else()
    message("-- WARNING: unable to find OpenSSL libraries! will build without SSL support.")
  endif()
endif()

if(WANT_SYNCAPI AND NOT WIN32)
  find_package(Threads REQUIRED)
  target_link_libraries(zookeeper PUBLIC Threads::Threads)
endif()

if(CYRUS_SASL_FOUND)
  target_compile_definitions(zookeeper PUBLIC HAVE_CYRUS_SASL_H)
  target_link_libraries(zookeeper PUBLIC CyrusSASL)
endif()

# cli executable
add_executable(cli src/cli.c)
target_link_libraries(cli zookeeper)

# load_gen executable
if(WANT_SYNCAPI AND NOT WIN32)
  add_executable(load_gen src/load_gen.c)
  target_link_libraries(load_gen zookeeper)
endif()

# tests
set(test_sources
  tests/TestDriver.cc
  tests/LibCMocks.cc
  tests/LibCSymTable.cc
  tests/MocksBase.cc
  tests/ZKMocks.cc
  tests/Util.cc
  tests/ThreadingUtil.cc
  tests/TestZookeeperInit.cc
  tests/TestZookeeperClose.cc
  tests/TestReconfig.cc
  tests/TestReconfigServer.cc
  tests/TestClientRetry.cc
  tests/TestOperations.cc
  tests/TestMulti.cc
  tests/TestWatchers.cc
  tests/TestClient.cc
  tests/ZooKeeperQuorumServer.cc
  tests/TestReadOnlyClient.cc
  tests/TestLogClientEnv.cc)

if(WANT_SYNCAPI)
  list(APPEND test_sources tests/PthreadMocks.cc)
endif()

if(WANT_CPPUNIT)
  set (CMAKE_CXX_STANDARD 11)
  add_executable(zktest ${test_sources})
  target_include_directories(zktest PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

  target_compile_definitions(zktest
    PRIVATE -DZKSERVER_CMD="${CMAKE_CURRENT_SOURCE_DIR}/tests/zkServer.sh")
  # TODO: Use `find_library()` for `cppunit`.
  target_link_libraries(zktest zookeeper cppunit dl)

  # This reads the link flags from the file `tests/wrappers.opt` into
  # the variable `symbol_wrappers` for use in `target_link_libraries`.
  # It is a holdover from the original build system.
  file(STRINGS tests/wrappers.opt symbol_wrappers)
  if(WANT_SYNCAPI)
    file(STRINGS tests/wrappers-mt.opt symbol_wrappers_mt)
  endif()

  target_link_libraries(zktest ${symbol_wrappers} ${symbol_wrappers_mt})

  enable_testing()
  add_test(NAME zktest_runner COMMAND zktest)
  set_property(TEST zktest_runner PROPERTY ENVIRONMENT
    "ZKROOT=${CMAKE_CURRENT_SOURCE_DIR}/../.."
    "CLASSPATH=$CLASSPATH:$CLOVER_HOME/lib/clover*.jar")
endif()
