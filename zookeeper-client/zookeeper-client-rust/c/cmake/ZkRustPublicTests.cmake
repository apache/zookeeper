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

# Build configuration and test runner definitions for the public API CppUnit integration suite.
# Targets zktest-public-mt or zktest-public-st by adapting legacy CppUnit test sources
# to exercise only exported, public ZooKeeper client APIs without internal symbol coupling.

if(NOT CMAKE_VERSION VERSION_LESS 3.15)
  cmake_policy(VERSION 3.15)
endif()
get_filename_component(zktest_public_dir "${CMAKE_CURRENT_LIST_DIR}/../tests/public" ABSOLUTE)
option(WANT_CPPUNIT_PUBLIC "Build the public-API CppUnit suite zktest-public-mt or -st" OFF)
if(WANT_CPPUNIT_PUBLIC AND NOT EXISTS "${zktest_public_dir}/gen-sources.sh")
  message(FATAL_ERROR "WANT_CPPUNIT_PUBLIC needs ${zktest_public_dir}")
endif()
set(ZKTEST_PORT 22181 CACHE STRING "Client port of the public-API suite server")
set(ZKTEST_DOWN_PORT 22182 CACHE STRING "A client port with no server, for the public-API suite")
set(ZKTEST_QUORUM_PORT_BASE 0 CACHE STRING "First of nine reconfig quorum ports (0: legacy ports)")
set(ZKTEST_RO_PORT_BASE 0 CACHE STRING "First of six read-only ensemble ports (0: legacy ports)")
set(ZKTEST_SSL_PORT 22281 CACHE STRING "Secure client port of the public-API suite server (testSSL*)")
set(ZKTEST_CERT_DIR /tmp/certs CACHE STRING "Directory of the gencerts.sh certificates (testSSL*)")
if(WANT_CPPUNIT_PUBLIC)
  if(WIN32)
    message(FATAL_ERROR "WANT_CPPUNIT_PUBLIC needs a POSIX host")
  endif()
  # Configure multithreaded (mt) or single threaded (st) test suite flavor.
  if(WANT_SYNCAPI)
    set(zktest_fl mt)
  else()
    set(zktest_fl st)
  endif()
  set(zktest_public zktest-public-${zktest_fl})
  find_package(PkgConfig QUIET)
  if(PKG_CONFIG_FOUND)
    pkg_check_modules(ZKTEST_PC_CPPUNIT QUIET cppunit)
  endif()
  find_path(CPPUNIT_INCLUDE_DIR cppunit/TestFixture.h HINTS ${ZKTEST_PC_CPPUNIT_INCLUDE_DIRS})
  find_library(CPPUNIT_LIBRARY cppunit HINTS ${ZKTEST_PC_CPPUNIT_LIBRARY_DIRS})
  if(NOT CPPUNIT_INCLUDE_DIR OR NOT CPPUNIT_LIBRARY)
    message(FATAL_ERROR "WANT_CPPUNIT_PUBLIC needs CppUnit")
  endif()
  set(zktest_public_server
    "ZOOPORT=${ZKTEST_PORT} ZKTEST_RO_PORT_BASE=${ZKTEST_RO_PORT_BASE} ZKTEST_SSL_PORT=${ZKTEST_SSL_PORT} ZKTEST_CERT_DIR=${ZKTEST_CERT_DIR} ${zktest_public_dir}/zkServer.sh")
  # Source compilation order establishes test execution sequence; TestMulti precedes TestClient to maintain server lifecycle.
  set(zktest_public_sources
    TestDriver.cc LibCSymTable.cc Util.cc ThreadingUtil.cc TestReconfigServer.cc
    TestClientRetry.cc TestMulti.cc TestClient.cc
    ZooKeeperQuorumServer.cc TestReadOnlyClient.cc TestLogClientEnv.cc
    TestSASLAuth.cc)
  set(zktest_public_gen ${CMAKE_CURRENT_BINARY_DIR}/zktest-public-src)
  set(zktest_public_args ${ZKTEST_PORT} ${ZKTEST_DOWN_PORT} ${ZKTEST_SSL_PORT}
    ${ZKTEST_CERT_DIR} ${ZKTEST_QUORUM_PORT_BASE})
  file(WRITE ${zktest_public_gen}/values.new "${zktest_public_args}\n")
  configure_file(${zktest_public_gen}/values.new ${zktest_public_gen}/values COPYONLY)
  set(zktest_public_out)
  set(zktest_public_in)
  foreach(f ${zktest_public_sources} ZooKeeperQuorumServer.h)
    list(APPEND zktest_public_out ${zktest_public_gen}/${f})
    list(APPEND zktest_public_in ${ZKC_DIR}/tests/${f})
  endforeach()
  add_custom_command(OUTPUT ${zktest_public_out}
    COMMAND sh ${zktest_public_dir}/gen-sources.sh
            ${ZKC_DIR}/tests ${zktest_public_args} ${zktest_public_out}
    DEPENDS ${zktest_public_dir}/gen-sources.sh
            ${zktest_public_dir}/omit-cases.txt
            ${zktest_public_gen}/values ${zktest_public_in}
    VERBATIM)
  add_executable(${zktest_public} ${zktest_public_out})
  set_target_properties(${zktest_public} PROPERTIES CXX_STANDARD 11)
  target_include_directories(${zktest_public} PRIVATE
    ${ZKC_DIR}/include ${ZKC_DIR}/tests
    ${ZKC_DIR}/generated ${ZKC_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}/include ${CPPUNIT_INCLUDE_DIR})
  target_compile_definitions(${zktest_public} PRIVATE
    "ZKSERVER_CMD=\"${zktest_public_server}\"")
  if(WANT_SYNCAPI)
    target_compile_definitions(${zktest_public} PRIVATE THREADED)
  endif()
  if(ZOO_IPV6_ENABLED)
    target_compile_definitions(${zktest_public} PRIVATE ZOO_IPV6_ENABLED)
  endif()
  # Prefer dynamic linking against the shared library target to validate exported symbol resolution.
  if(TARGET zookeeper_${zktest_fl})
    target_link_libraries(${zktest_public} PRIVATE zookeeper_${zktest_fl})
    set(zktest_public_libdir --libdir $<TARGET_FILE_DIR:zookeeper_${zktest_fl}>)
  else()
    target_link_libraries(${zktest_public} PRIVATE zookeeper)
    set(zktest_public_libdir)
  endif()
  # Link dynamic loader library for pthread interposition probes in ThreadingUtil/LibCSymTable.
  target_link_libraries(${zktest_public} PRIVATE ${CPPUNIT_LIBRARY} ${CMAKE_DL_LIBS})
  if(WANT_SYNCAPI)
    find_package(Threads REQUIRED)
    target_link_libraries(${zktest_public} PRIVATE Threads::Threads)
  endif()

  enable_testing()
  add_test(NAME zktest_public_${zktest_fl}
    COMMAND "${CMAKE_COMMAND}" -E env "ZKTEST_PORT=${ZKTEST_PORT}"
            "ZKTEST_RO_PORT_BASE=${ZKTEST_RO_PORT_BASE}"
            "${zktest_public_dir}/run-public.sh" ${zktest_public_libdir}
            --workdir "${CMAKE_CURRENT_BINARY_DIR}/zktest-public" $<TARGET_FILE:${zktest_public}>)
  set_tests_properties(zktest_public_${zktest_fl} PROPERTIES LABELS public TIMEOUT 1800)
  add_custom_target(check-public
    COMMAND "${CMAKE_CTEST_COMMAND}" -L public --output-on-failure
    DEPENDS ${zktest_public}
    WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
    USES_TERMINAL)
endif()
