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

# TLS and SASL security subsystem configuration for the Rust client core.
# Probes OpenSSL development headers to determine TLS API exposure and Rustls
# feature flags without introducing direct linker dependencies on libssl or libcrypto.
# Manages Cyrus SASL discovery, CA trust root resolution, and feature compile definitions,
# synchronizing with configuration logic in m4/zk_rust.m4.
#
# Variables defined:
#   ZKRS_TLS, ZKRS_SASL       Security subsystem enablement indicators (ON/OFF)
#   ZKRS_SSL01, ZKRS_SASL01   Numeric flags (1/0) consumed by abi/gen-exports.awk
#   ZKRS_ABI_CONFIG           Golden ABI target configuration variant (plain, ssl, sasl, full)
#   ZKRS_CARGO_FEATURES       Cargo feature configuration arguments
#   ZKRS_TLS_ENV              Environment variable overrides defining default CA trust roots
#   ZKS_CONFIG_DEFS           C preprocessor definitions for feature-gated shim units
#   ZKS_CONFIG_SOURCES        C shim translation units enabled for the selected configuration
#   ZKS_SASL_SOURCES          Cyrus SASL integration translation units
#   ZKRS_SASL_LIBS            Linker flags for Cyrus SASL runtime dependencies (-lsasl2)
#   ZKRS_OPENSSL_INCLUDE_DIR  Discovered path to OpenSSL public headers for client consumers
#   ZKRS_SKIP_PROGRAMS        Suppression flag when sample programs cannot compile due to missing headers

# Preserve parity with legacy CMake option defining OpenSSL enablement or prefix.
option(WITH_OPENSSL "turn ON/OFF SSL support, or define openssl library location (default: ON)" ON)
message("-- using WITH_OPENSSL=${WITH_OPENSSL}")
if(WITH_RUST_TLS AND WITH_OPENSSL STREQUAL "OFF")
  message(FATAL_ERROR "WITH_RUST_TLS=ON contradicts WITH_OPENSSL=OFF: give only one of them")
endif()

if(NOT ZK_RUST_TLS_PROVIDER MATCHES "^(ring|aws-lc-rs|aws-lc-rs-fips)$")
  message(FATAL_ERROR
    "ZK_RUST_TLS_PROVIDER=${ZK_RUST_TLS_PROVIDER}: give ring, aws-lc-rs or aws-lc-rs-fips")
endif()
if(ZK_RUST_TLS_PROVIDER STREQUAL "aws-lc-rs-fips")
  if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
    message(FATAL_ERROR
      "ZK_RUST_TLS_PROVIDER=aws-lc-rs-fips needs a Linux target, not ${CMAKE_SYSTEM_NAME}")
  elseif(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|aarch64)$")
    message(FATAL_ERROR
      "ZK_RUST_TLS_PROVIDER=aws-lc-rs-fips needs x86_64 or aarch64, not ${CMAKE_SYSTEM_PROCESSOR}")
  endif()
endif()

# Validate user-specified default trust root paths.
set(zkrs_cert_file "")
set(zkrs_cert_dir "")
set(zkrs_paths_from none)
set(zkrs_paths_given OFF)
if(NOT "${ZK_RUST_TLS_DEFAULT_PATHS}" STREQUAL "")
  if(ZK_RUST_TLS_DEFAULT_PATHS MATCHES ":.*:")
    message(FATAL_ERROR
      "ZK_RUST_TLS_DEFAULT_PATHS=${ZK_RUST_TLS_DEFAULT_PATHS}: give FILE:DIR with one colon")
  elseif(NOT ZK_RUST_TLS_DEFAULT_PATHS MATCHES "^([^:]*):([^:]*)$")
    message(FATAL_ERROR "ZK_RUST_TLS_DEFAULT_PATHS=${ZK_RUST_TLS_DEFAULT_PATHS}: give FILE:DIR")
  endif()
  set(zkrs_cert_file "${CMAKE_MATCH_1}")
  set(zkrs_cert_dir "${CMAKE_MATCH_2}")
  foreach(p "${zkrs_cert_file}" "${zkrs_cert_dir}")
    if(NOT p STREQUAL "" AND NOT p MATCHES "^/")
      message(FATAL_ERROR "ZK_RUST_TLS_DEFAULT_PATHS=${ZK_RUST_TLS_DEFAULT_PATHS}: "
                          "give absolute paths (FILE or DIR can be empty)")
    endif()
  endforeach()
  set(zkrs_paths_given ON)
  set(zkrs_paths_from ZK_RUST_TLS_DEFAULT_PATHS)
endif()

# Probe system or user-specified OpenSSL installation for public development headers.
set(ZKRS_TLS OFF)
if(NOT WITH_OPENSSL STREQUAL "OFF")
  if(NOT WITH_OPENSSL STREQUAL "ON")
    set(OPENSSL_ROOT_DIR "${WITH_OPENSSL}")
  endif()
  find_package(OpenSSL)
  if(OPENSSL_FOUND)
    message("-- OpenSSL libraries found! will build with SSL support.")
  elseif(WITH_RUST_TLS)
    message("-- OpenSSL libraries not found. WITH_RUST_TLS=ON builds SSL support.")
  else()
    message("-- WARNING: unable to find OpenSSL libraries! will build without SSL support.")
  endif()
endif()
if(OPENSSL_FOUND OR WITH_RUST_TLS)
  set(ZKRS_TLS ON)
endif()
if(CYRUS_SASL_FOUND)
  set(ZKRS_SASL ON)
else()
  set(ZKRS_SASL OFF)
endif()
if(ZKRS_PROVIDER_GIVEN AND NOT ZKRS_TLS)
  message(STATUS "TLS is not built: ZK_RUST_TLS_PROVIDER=${ZK_RUST_TLS_PROVIDER} is not used")
endif()
if(ZKRS_TLS AND ZK_RUST_TLS_PROVIDER STREQUAL "aws-lc-rs-fips" AND ZKRS_STATICLIB STREQUAL "")
  find_program(ZKRS_GO_EXECUTABLE go)
  if(NOT ZKRS_GO_EXECUTABLE)
    message(FATAL_ERROR "ZK_RUST_TLS_PROVIDER=aws-lc-rs-fips needs cmake and go")
  endif()
endif()

# Verify feature flag compatibility of precompiled static libraries by inspecting
# the zkrs_feature_tls marker symbol, accounting for platform nm differences.
if(NOT ZKRS_STATICLIB STREQUAL "")
  if(CMAKE_NM)
    set(zkrs_nm "${CMAKE_NM}")
  else()
    set(zkrs_nm nm)
  endif()
  execute_process(COMMAND "${zkrs_nm}" --no-llvm-bc "${ZKRS_STATICLIB}"
                  OUTPUT_VARIABLE zkrs_nm_bc_out ERROR_QUIET)
  execute_process(COMMAND "${zkrs_nm}" "${ZKRS_STATICLIB}" OUTPUT_VARIABLE zkrs_nm_out
                  ERROR_QUIET)
  string(APPEND zkrs_nm_out "${zkrs_nm_bc_out}")
  if(zkrs_nm_out STREQUAL "")
    message(FATAL_ERROR "nm cannot read the staticlib ${ZKRS_STATICLIB}")
  endif()
  if(zkrs_nm_out MATCHES " [A-Z] _?zkrs_feature_tls(\n|$)")
    set(zkrs_lib_tls ON)
  else()
    set(zkrs_lib_tls OFF)
  endif()
  if(NOT zkrs_lib_tls STREQUAL ZKRS_TLS)
    message(FATAL_ERROR
      "the staticlib ${ZKRS_STATICLIB} has tls=${zkrs_lib_tls}, but this build has "
      "tls=${ZKRS_TLS}: build the staticlib with the TLS decision of abi/tls-probe.sh "
      "and the same WITH_OPENSSL and WITH_RUST_TLS")
  endif()
endif()

# Resolve default certificate authority trust store paths using precedence:
# explicit option, probed OpenSSL runtime configuration (OPENSSLDIR), or platform defaults.
if(ZKRS_TLS AND NOT zkrs_paths_given AND NOT WIN32)
  set(zkrs_ossl_bin "")
  if(NOT "${OPENSSL_ROOT_DIR}" STREQUAL "")
    if(EXISTS "${OPENSSL_ROOT_DIR}/bin/openssl")
      set(zkrs_ossl_bin "${OPENSSL_ROOT_DIR}/bin/openssl")
    endif()
  elseif(NOT WITH_OPENSSL STREQUAL "OFF")
    find_program(ZKRS_OPENSSL_EXECUTABLE openssl)
    if(ZKRS_OPENSSL_EXECUTABLE)
      set(zkrs_ossl_bin "${ZKRS_OPENSSL_EXECUTABLE}")
    endif()
  endif()
  set(zkrs_ossl_dir "")
  if(zkrs_ossl_bin)
    execute_process(COMMAND "${zkrs_ossl_bin}" version -d OUTPUT_VARIABLE zkrs_ossl_out
                    ERROR_QUIET RESULT_VARIABLE zkrs_ossl_rc)
    if(zkrs_ossl_rc EQUAL 0 AND zkrs_ossl_out MATCHES "OPENSSLDIR: \"([^\"]*)\"")
      set(zkrs_ossl_dir "${CMAKE_MATCH_1}")
      set(zkrs_paths_from "${zkrs_ossl_bin} version -d")
    endif()
  endif()
  if(zkrs_ossl_dir STREQUAL "")
    foreach(d /usr/lib/ssl /etc/pki/tls /etc/ssl /usr/local/ssl /usr/local/etc/openssl@3
              /opt/homebrew/etc/openssl@3)
      if(IS_DIRECTORY "${d}")
        set(zkrs_ossl_dir "${d}")
        set(zkrs_paths_from "the platform list")
        break()
      endif()
    endforeach()
  endif()
  if(NOT zkrs_ossl_dir STREQUAL "")
    set(zkrs_cert_file "${zkrs_ossl_dir}/cert.pem")
    set(zkrs_cert_dir "${zkrs_ossl_dir}/certs")
  endif()
elseif(NOT ZKRS_TLS)
  set(zkrs_cert_file "")
  set(zkrs_cert_dir "")
endif()

# Verify availability of openssl/ossl_typ.h required by consumers when compiling against zookeeper.h with TLS enabled.
set(ZKRS_OPENSSL_INCLUDE_DIR "")
set(ZKRS_SKIP_PROGRAMS OFF)
if(ZKRS_TLS)
  set(zkrs_ossl_hints ${OPENSSL_INCLUDE_DIR})
  if(NOT "${OPENSSL_ROOT_DIR}" STREQUAL "")
    list(APPEND zkrs_ossl_hints "${OPENSSL_ROOT_DIR}/include")
  endif()
  find_path(ZK_OPENSSL_INCLUDE_DIR openssl/ossl_typ.h HINTS ${zkrs_ossl_hints})
  if(ZK_OPENSSL_INCLUDE_DIR)
    set(ZKRS_OPENSSL_INCLUDE_DIR "${ZK_OPENSSL_INCLUDE_DIR}")
  else()
    message(WARNING
      "consumers need OpenSSL headers to compile against zookeeper.h; cli and load_gen skipped")
    set(ZKRS_SKIP_PROGRAMS ON)
  endif()
endif()

set(ZKRS_SSL01 0)
set(ZKRS_SASL01 0)
set(zkrs_features "")
set(ZKS_CONFIG_DEFS "")
set(ZKS_CONFIG_SOURCES "")
set(ZKS_SASL_SOURCES "")
set(ZKRS_SASL_LIBS "")
set(ZKRS_TLS_ENV "ZK_TLS_DEFAULT_CERT_FILE=${zkrs_cert_file}"
                 "ZK_TLS_DEFAULT_CERT_DIR=${zkrs_cert_dir}")
if(ZKRS_TLS)
  set(ZKRS_SSL01 1)
  list(APPEND zkrs_features "tls-${ZK_RUST_TLS_PROVIDER}")
  list(APPEND ZKS_CONFIG_DEFS ZKS_WITH_TLS HAVE_OPENSSL_H)
  list(APPEND ZKS_CONFIG_SOURCES ${ZKRS_DIR}/shim/zks_tls.c)
  if(zkrs_cert_file STREQUAL "")
    set(zkrs_show_file none)
  else()
    set(zkrs_show_file "${zkrs_cert_file}")
  endif()
  if(zkrs_cert_dir STREQUAL "")
    set(zkrs_show_dir none)
  else()
    set(zkrs_show_dir "${zkrs_cert_dir}")
  endif()
  message(STATUS "The default trust locations of the Rust client: file=${zkrs_show_file} "
                 "dir=${zkrs_show_dir} (from ${zkrs_paths_from})")
endif()
if(ZKRS_SASL)
  set(ZKRS_SASL01 1)
  list(APPEND zkrs_features sasl)
  list(APPEND ZKS_CONFIG_DEFS ZKS_WITH_SASL HAVE_CYRUS_SASL_H)
  set(ZKS_SASL_SOURCES ${ZKRS_DIR}/shim/zks_sasl_cyrus.c ${ZKRS_DIR}/shim/zks_sasl_cb.c)
  list(APPEND ZKS_CONFIG_SOURCES ${ZKS_SASL_SOURCES})
  set(ZKRS_SASL_LIBS -lsasl2)
endif()
if(zkrs_features)
  string(JOIN "," zkrs_features_str ${zkrs_features})
  set(ZKRS_CARGO_FEATURES "--features=${zkrs_features_str}")
else()
  set(ZKRS_CARGO_FEATURES "")
endif()
if(ZKRS_TLS AND ZKRS_SASL)
  set(ZKRS_ABI_CONFIG full)
elseif(ZKRS_TLS)
  set(ZKRS_ABI_CONFIG ssl)
elseif(ZKRS_SASL)
  set(ZKRS_ABI_CONFIG sasl)
else()
  set(ZKRS_ABI_CONFIG plain)
endif()
message(STATUS
  "The TLS and SASL build of the Rust client: tls=${ZKRS_TLS} provider=${ZK_RUST_TLS_PROVIDER} sasl=${ZKRS_SASL}")
