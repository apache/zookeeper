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

# The options and platform probes of the legacy C client. The lines below
# are a copy of zookeeper-client-c/CMakeLists.txt, in the same order. Thus
# the drop-in gets the same options, flags and config.h as the legacy build.
# Only the paths change: ZKC_DIR is the legacy tree. The probes use the
# policies of the legacy project.
cmake_policy(PUSH)
cmake_policy(VERSION 3.5)

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
  if(EXISTS "${ZKC_DIR}/${f}")
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
configure_file(${ZKC_DIR}/cmake_config.h.in ${CMAKE_CURRENT_BINARY_DIR}/include/config.h)

cmake_policy(POP)
