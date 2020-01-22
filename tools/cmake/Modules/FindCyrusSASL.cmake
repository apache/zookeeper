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
#
# - Find Cyrus SASL (sasl.h, libsasl2.so)
#
# This module defines
#  CYRUS_SASL_INCLUDE_DIR, directory containing headers
#  CYRUS_SASL_SHARED_LIB, path to Cyrus SASL's shared library
#  CYRUS_SASL_FOUND, whether Cyrus SASL and its plugins have been found
#
# It also defines the following IMPORTED targets:
#  CyrusSASL
#
# Hints:
#  Set CYRUS_SASL_ROOT_DIR to the root directory of a Cyrus SASL installation.
#
# The initial version of this file was extracted from
# https://github.com/cloudera/kudu, at the following commit:
#
#  commit 9806863e78107505a622b44112a897189d9b3c24
#  Author: Dan Burkert <dan@cloudera.com>
#  Date:   Mon Nov 30 12:15:36 2015 -0800
#
#      Enable C++11

find_path(CYRUS_SASL_INCLUDE_DIR sasl/sasl.h HINTS "${CYRUS_SASL_ROOT_DIR}/include")
find_library(CYRUS_SASL_SHARED_LIB sasl2 HINTS "${CYRUS_SASL_ROOT_DIR}/lib")

include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(CYRUS_SASL REQUIRED_VARS
  CYRUS_SASL_SHARED_LIB CYRUS_SASL_INCLUDE_DIR)

if(CYRUS_SASL_FOUND)
  if(NOT TARGET CyrusSASL)
    add_library(CyrusSASL UNKNOWN IMPORTED)
    set_target_properties(CyrusSASL PROPERTIES
      INTERFACE_INCLUDE_DIRECTORIES "${CYRUS_SASL_INCLUDE_DIR}"
      IMPORTED_LINK_INTERFACE_LANGUAGES "C"
      IMPORTED_LOCATION "${CYRUS_SASL_SHARED_LIB}")
  endif()
endif()
