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

# Rust core static library orchestration and FFI artifact compilation.
# Resolves Cargo profile, target triple, and native runtime linker dependencies,
# providing the zkrs_cargo build target and zk_rust_exports symbol extraction helper.
# Variables defined:
#   ZKRS_PROFILE      Active Cargo compilation profile (capi-dev for Debug, otherwise capi)
#   ZKRS_RUST_TARGET  Target triple for host execution or cross-compilation
#   ZKRS_LIB          Path to the compiled zk-ffi static library archive
#   ZKRS_NATIVE_LIBS  Runtime system libraries required to statically link Rust artifacts
#   ZKRS_TARGET_CPU   The -C target-cpu value of the staticlib build, or empty for no flag
#   ZK_VERSION_H      Header file supplying client version macros to zk-ffi/build.rs

set(ZK_VERSION_H "${ZKC_DIR}/include/zookeeper_version.h")
set(ZKRS_CARGO_DIR "${CMAKE_BINARY_DIR}/cargo")

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  set(ZKRS_PROFILE capi-dev)
else()
  set(ZKRS_PROFILE capi)
endif()

if(NOT "${Rust_TARGET}" STREQUAL "")
  set(ZKRS_RUST_TARGET "${Rust_TARGET}")
  set(ZKRS_TARGET_ARGS --target "${Rust_TARGET}")
else()
  execute_process(COMMAND "${RUSTC_EXECUTABLE}" -vV OUTPUT_VARIABLE rustc_info ERROR_QUIET)
  string(REGEX MATCH "host: [^\n]*" rustc_host "${rustc_info}")
  string(REPLACE "host: " "" ZKRS_RUST_TARGET "${rustc_host}")
  set(ZKRS_TARGET_ARGS "")
endif()
string(REGEX REPLACE "[.-]" "_" zkrs_target_env "${ZKRS_RUST_TARGET}")

# Probe rustc to determine platform-specific runtime dependencies required when
# statically linking against Rust archives, filtering standard C runtime libraries.
set(zkrs_probe "${CMAKE_BINARY_DIR}/CMakeFiles/zkrs-probe")
file(MAKE_DIRECTORY "${zkrs_probe}")
file(WRITE "${zkrs_probe}/conftest.rs" "")
execute_process(
  COMMAND "${RUSTC_EXECUTABLE}" --crate-type staticlib --crate-name conftest ${ZKRS_TARGET_ARGS}
          --print native-static-libs -o "${zkrs_probe}/libconftest.a" "${zkrs_probe}/conftest.rs"
  OUTPUT_VARIABLE zkrs_out ERROR_VARIABLE zkrs_err RESULT_VARIABLE zkrs_rc)
file(REMOVE_RECURSE "${zkrs_probe}")
if(NOT zkrs_rc EQUAL 0)
  message(FATAL_ERROR "rustc cannot build a staticlib for ${ZKRS_RUST_TARGET}:\n${zkrs_err}")
endif()
string(REGEX MATCH "native-static-libs: [^\n]*" zkrs_native "${zkrs_out}${zkrs_err}")
string(REPLACE "native-static-libs: " "" zkrs_native "${zkrs_native}")
separate_arguments(zkrs_native UNIX_COMMAND "${zkrs_native}")
set(ZKRS_NATIVE_LIBS "")
foreach(lib IN LISTS zkrs_native)
  if(NOT lib MATCHES "^-l(c|System)$")
    list(APPEND ZKRS_NATIVE_LIBS "${lib}")
  endif()
endforeach()
list(REMOVE_DUPLICATES ZKRS_NATIVE_LIBS)
message(STATUS "The native libraries of a Rust staticlib: ${ZKRS_NATIVE_LIBS}")

# The Rust code gets the code generation baseline of the C toolchain.
# abi/target-cpu.sh makes the decision of ZK_RUST_TARGET_CPU. Autotools
# and Maven use the same script. The probe compiles with the C compiler
# and the C flags of this build.
set(ZKRS_TARGET_CPU "")
string(TOUPPER "${ZK_RUST_TARGET_CPU}" zkrs_cpu_choice)
if(NOT ZKRS_STATICLIB STREQUAL "")
  set(zkrs_cpu_why "the prebuilt staticlib keeps the code generation of its build")
elseif(zkrs_cpu_choice MATCHES "^(OFF|NO|FALSE|0|)$")
  set(zkrs_cpu_why "ZK_RUST_TARGET_CPU is ${ZK_RUST_TARGET_CPU}")
else()
  if(zkrs_cpu_choice MATCHES "^(AUTO|ON|YES|TRUE|1)$")
    set(zkrs_cpu_arg auto)
    set(ZKRS_TARGET_CPU_POLICY auto)
  else()
    set(zkrs_cpu_arg "${ZK_RUST_TARGET_CPU}")
    set(ZKRS_TARGET_CPU_POLICY explicit)
  endif()
  string(TOUPPER "${CMAKE_BUILD_TYPE}" zkrs_bt)
  set(zkrs_cpu_cflags "${CMAKE_C_FLAGS} ${CMAKE_C_FLAGS_${zkrs_bt}}")
  if(CMAKE_C_COMPILER_TARGET AND CMAKE_C_COMPILE_OPTIONS_TARGET)
    string(APPEND zkrs_cpu_cflags " ${CMAKE_C_COMPILE_OPTIONS_TARGET}${CMAKE_C_COMPILER_TARGET}")
  endif()
  if(CMAKE_SYSROOT AND CMAKE_C_COMPILE_OPTIONS_SYSROOT)
    string(APPEND zkrs_cpu_cflags " ${CMAKE_C_COMPILE_OPTIONS_SYSROOT}${CMAKE_SYSROOT}")
  endif()
  list(LENGTH CMAKE_OSX_ARCHITECTURES zkrs_narch)
  if(APPLE AND zkrs_narch EQUAL 1)
    string(APPEND zkrs_cpu_cflags " -arch ${CMAKE_OSX_ARCHITECTURES}")
  endif()
  string(STRIP "${zkrs_cpu_cflags}" zkrs_cpu_cflags)
  execute_process(
    COMMAND sh "${ZKRS_DIR}/abi/target-cpu.sh" probe "--choice=${zkrs_cpu_arg}"
            "--rustc=${RUSTC_EXECUTABLE}" "--target=${ZKRS_RUST_TARGET}" "--dir=${ZKRS_WORKSPACE}"
            "--cc=${CMAKE_C_COMPILER}" "--cflags=${zkrs_cpu_cflags}"
    OUTPUT_VARIABLE zkrs_cpu_out ERROR_VARIABLE zkrs_cpu_err RESULT_VARIABLE zkrs_cpu_rc)
  if(NOT zkrs_cpu_rc EQUAL 0)
    string(STRIP "${zkrs_cpu_err}" zkrs_cpu_err)
    message(FATAL_ERROR "ZK_RUST_TARGET_CPU=${ZK_RUST_TARGET_CPU}: ${zkrs_cpu_err}")
  endif()
  string(REGEX MATCH "target_cpu=[^\n]*" ZKRS_TARGET_CPU "${zkrs_cpu_out}")
  string(REPLACE "target_cpu=" "" ZKRS_TARGET_CPU "${ZKRS_TARGET_CPU}")
  string(REGEX MATCH "why=[^\n]*" zkrs_cpu_why "${zkrs_cpu_out}")
  string(REPLACE "why=" "" zkrs_cpu_why "${zkrs_cpu_why}")
endif()
if(ZKRS_TARGET_CPU STREQUAL "")
  message(STATUS "The Rust target CPU: none (${zkrs_cpu_why})")
  set(zkrs_cargo_cmd "${CARGO_EXECUTABLE}")
else()
  message(STATUS "The Rust target CPU: ${ZKRS_TARGET_CPU} (${zkrs_cpu_why})")
  # The script adds the flag to the rustflags of the user at build time.
  set(zkrs_cargo_cmd sh "${ZKRS_DIR}/abi/target-cpu.sh" run "--cpu=${ZKRS_TARGET_CPU}"
      "--policy=${ZKRS_TARGET_CPU_POLICY}" "--target=${ZKRS_RUST_TARGET}" -- "${CARGO_EXECUTABLE}")
endif()

if(NOT ZKRS_STATICLIB STREQUAL "")
  # Precompiled static library specified; bypass Cargo invocation.
  get_filename_component(ZKRS_LIB "${ZKRS_STATICLIB}" ABSOLUTE)
  add_custom_target(zkrs_cargo)
else()
  if(NOT "${Rust_TARGET}" STREQUAL "")
    set(ZKRS_LIB "${ZKRS_CARGO_DIR}/${Rust_TARGET}/${ZKRS_PROFILE}/libzk_ffi.a")
  else()
    set(ZKRS_LIB "${ZKRS_CARGO_DIR}/${ZKRS_PROFILE}/libzk_ffi.a")
  endif()
  add_custom_target(zkrs_cargo
    COMMAND "${CMAKE_COMMAND}" -E env --unset=MAKEFLAGS --unset=MFLAGS
            "CARGO_TARGET_DIR=${ZKRS_CARGO_DIR}"
            "ZK_VERSION_H=${ZK_VERSION_H}" "CC_${zkrs_target_env}=${CMAKE_C_COMPILER}"
            "AR_${zkrs_target_env}=${CMAKE_AR}" ${ZKRS_TLS_ENV}
            ${zkrs_cargo_cmd} ${ZKRS_CARGO_SOURCE_ARGS} rustc --manifest-path "${ZKRS_WORKSPACE}/Cargo.toml" -p zk-ffi
            ${ZKRS_CARGO_FEATURES}
            --profile ${ZKRS_PROFILE} ${ZKRS_TARGET_ARGS} --locked --crate-type staticlib
    BYPRODUCTS "${ZKRS_LIB}"
    WORKING_DIRECTORY "${ZKRS_WORKSPACE}"
    COMMENT "Building the zk-ffi staticlib (${ZKRS_PROFILE})"
    USES_TERMINAL VERBATIM)
endif()

# Generates symbol export lists for the designated flavor and format via abi/gen-exports.awk,
# registering configure-time dependencies on the underlying golden symbol definitions.
find_program(ZK_AWK NAMES awk gawk nawk mawk)
if(NOT ZK_AWK)
  message(FATAL_ERROR "the Rust core build needs awk")
endif()
function(zk_rust_exports flavor fmt out)
  set(abi "${ZKRS_DIR}/abi")
  set(inputs "${abi}/gen-exports.awk" "${abi}/symbols.tsv" "${abi}/static-symbols.tsv")
  execute_process(
    COMMAND "${ZK_AWK}" -f "${abi}/gen-exports.awk" -v flavor=${flavor} -v fmt=${fmt}
            -v ssl=${ZKRS_SSL01} -v sasl=${ZKRS_SASL01} "${abi}/symbols.tsv" "${abi}/static-symbols.tsv"
    OUTPUT_FILE "${out}" RESULT_VARIABLE rc)
  if(NOT rc EQUAL 0)
    message(FATAL_ERROR "gen-exports.awk failed for ${flavor} ${fmt}")
  endif()
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${inputs})
endfunction()
