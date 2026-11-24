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

include(GNUInstallDirs)
include(ZkRustTls)
include(ZkRust)
include(ZkHarden)
if(WANT_SYNCAPI)
  set(zks_fl mt)
  find_package(Threads REQUIRED)
else()
  set(zks_fl st)
endif()
message("-- building the ${zks_fl} flavor of the Rust client core from ${ZKRS_WORKSPACE}")

# Compile legacy Jute serialization sources into an intermediate object library using baseline compiler flags.
set(zks_c "${ZKC_DIR}")
add_library(zks_jute OBJECT ${zks_c}/src/recordio.c ${zks_c}/generated/zookeeper.jute.c)
target_include_directories(zks_jute PRIVATE ${zks_c}/include ${CMAKE_CURRENT_BINARY_DIR}/include
                           ${zks_c}/generated)
target_compile_options(zks_jute PRIVATE -ffunction-sections -fdata-sections)
set_target_properties(zks_jute PROPERTIES POSITION_INDEPENDENT_CODE ON)

set(zks_shim_units
  zks_data.c zks_handle.c zks_async.c zks_multi.c zks_drain.c
  zks_log.c zks_env.c zks_errors.c zks_resolve.c zks_rng.c
  zks_compat.c zks_layout_check.c)
if(zks_fl STREQUAL "mt")
  list(APPEND zks_shim_units zks_sync.c zks_threads.c)
  set(zks_flavor_defs THREADED)
else()
  set(zks_flavor_defs "")
endif()
# Propagate SOCK_CLOEXEC_ENABLED from ZkCProbes.cmake as a compile definition in ZKS_CONFIG_DEFS,
# as the shim units do not include cmake_config.h.
if(SOCK_CLOEXEC_ENABLED)
  list(APPEND ZKS_CONFIG_DEFS SOCK_CLOEXEC_ENABLED)
endif()
set(zks_sources "")
foreach(f ${zks_shim_units})
  list(APPEND zks_sources "${ZKRS_DIR}/shim/${f}")
endforeach()
list(APPEND zks_sources ${ZKS_CONFIG_SOURCES})
add_library(zks_${zks_fl} STATIC ${zks_sources} $<TARGET_OBJECTS:zks_jute>)
target_include_directories(zks_${zks_fl} PRIVATE ${zks_c}/include ${zks_c}/generated
                           ${ZKRS_DIR}/shim)
target_compile_definitions(zks_${zks_fl} PRIVATE _GNU_SOURCE ${zks_flavor_defs} ${ZKS_CONFIG_DEFS}
                           $<$<CONFIG:Debug>:ZKS_HANDLE_REGISTRY>)
target_compile_options(zks_${zks_fl} PRIVATE ${ZKS_WARN_CFLAGS} ${ZKS_CFLAGS} ${ZKS_HARDEN_CFLAGS}
                       -ffunction-sections -fdata-sections)
set_target_properties(zks_${zks_fl} PROPERTIES POSITION_INDEPENDENT_CODE ON)
# Configure SASL compilation flags and restrict Cyrus SASL header definitions to dedicated adapter units.
if(ZKS_SASL_SOURCES)
  set(ZKS_HARDEN_SASL_CFLAGS ${ZKS_HARDEN_CFLAGS})
  set(zks_sasl_flags -Wconversion -Wsign-conversion)
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|i.86)$")
    list(APPEND zks_sasl_flags -fcf-protection)
  endif()
  # Suppress deprecation warnings on macOS where Cyrus SASL is provided by the system SDK.
  if(APPLE AND CYRUS_SASL_INCLUDE_DIR MATCHES "(\\.sdk/|^/usr/include)")
    list(APPEND zks_sasl_flags -Wno-deprecated-declarations)
  endif()
  foreach(f ${zks_sasl_flags})
    zk_harden_flag(ZKS_HARDEN_SASL_CFLAGS zks_sasl_flag_ok ${f})
    if(NOT zks_sasl_flag_ok)
      message(STATUS "dropped the SASL glue flag ${f}")
    endif()
  endforeach()
  set(zks_sasl_opts ${ZKS_HARDEN_SASL_CFLAGS})
  if(CYRUS_SASL_INCLUDE_DIR)
    list(APPEND zks_sasl_opts -isystem "${CYRUS_SASL_INCLUDE_DIR}")
  endif()
  set_source_files_properties(${ZKS_SASL_SOURCES} PROPERTIES
    COMPILE_DEFINITIONS HAVE_CYRUS_SASL_H COMPILE_OPTIONS "${zks_sasl_opts}")
endif()

# Execute ABI pre-link step (abi/prelink.sh) to unify C shim objects and the Rust static library,
# localizing all non-golden symbols within the combined object.
set(zks_keep "${CMAKE_CURRENT_BINARY_DIR}/zookeeper_${zks_fl}.keep")
zk_rust_exports(${zks_fl} keep "${zks_keep}")
set(ZK_ALL_OBJ "${CMAKE_CURRENT_BINARY_DIR}/zookeeper_${zks_fl}_all${CMAKE_C_OUTPUT_EXTENSION}")
if(APPLE)
  set(zks_prelink_args --host-os darwin)
  list(LENGTH CMAKE_OSX_ARCHITECTURES zks_narch)
  if(zks_narch EQUAL 1)
    list(APPEND zks_prelink_args --arch ${CMAKE_OSX_ARCHITECTURES})
  elseif(zks_narch GREATER 1)
    message(FATAL_ERROR "the Rust core builds one architecture; give one CMAKE_OSX_ARCHITECTURES")
  endif()
else()
  string(TOLOWER "${CMAKE_SYSTEM_NAME}" zks_host_os)
  # The helper objects of the ELF pre-link compile for the target. A cross
  # build can give the target in the flags, in the compiler target or in a
  # sysroot.
  set(zks_prelink_cflags "${CMAKE_C_FLAGS}")
  if(CMAKE_C_COMPILER_TARGET AND CMAKE_C_COMPILE_OPTIONS_TARGET)
    string(APPEND zks_prelink_cflags " ${CMAKE_C_COMPILE_OPTIONS_TARGET}${CMAKE_C_COMPILER_TARGET}")
  endif()
  if(CMAKE_SYSROOT AND CMAKE_C_COMPILE_OPTIONS_SYSROOT)
    string(APPEND zks_prelink_cflags " ${CMAKE_C_COMPILE_OPTIONS_SYSROOT}${CMAKE_SYSROOT}")
  endif()
  set(zks_prelink_args --host-os ${zks_host_os} --ld "${CMAKE_LINKER}")
  # An empty list element disappears in the command, so an empty value gets
  # no option.
  string(STRIP "${zks_prelink_cflags}" zks_prelink_cflags)
  if(NOT zks_prelink_cflags STREQUAL "")
    list(APPEND zks_prelink_args --cflags "${zks_prelink_cflags}")
  endif()
  if(NOT CMAKE_OBJCOPY)
    message(FATAL_ERROR "the Rust client pre-link needs objcopy")
  endif()
  list(APPEND zks_prelink_args --objcopy "${CMAKE_OBJCOPY}")
endif()
if(CMAKE_NM)
  list(APPEND zks_prelink_args --nm "${CMAKE_NM}")
  set(zks_nm "${CMAKE_NM}")
else()
  set(zks_nm nm)
endif()
add_custom_command(OUTPUT "${ZK_ALL_OBJ}"
  COMMAND sh "${ZKRS_DIR}/abi/prelink.sh" --cc "${CMAKE_C_COMPILER}"
          ${zks_prelink_args} --shim $<TARGET_FILE:zks_${zks_fl}> --rust "${ZKRS_LIB}"
          --keep "${zks_keep}" --object "${ZK_ALL_OBJ}"
  DEPENDS zks_${zks_fl} "${ZKRS_LIB}" "${zks_keep}" "${ZKRS_DIR}/abi/prelink.sh"
  COMMENT "Pre-linking the Rust core object (${zks_fl})"
  VERBATIM)
add_custom_target(zks_${zks_fl}_all DEPENDS "${ZK_ALL_OBJ}")
add_dependencies(zks_${zks_fl}_all zkrs_cargo)
set_source_files_properties("${ZK_ALL_OBJ}" PROPERTIES EXTERNAL_OBJECT ON GENERATED ON)

if(ZKRS_SASL)
  set(zks_link_libs CyrusSASL)
else()
  set(zks_link_libs "")
endif()
list(APPEND zks_link_libs ${ZKRS_NATIVE_LIBS} $<$<PLATFORM_ID:Linux>:rt>)
if(zks_fl STREQUAL "mt")
  list(APPEND zks_link_libs Threads::Threads)
endif()

add_library(hashtable STATIC ${ZKRS_DIR}/shim/zks_hashtable_stub.c)
target_include_directories(hashtable PUBLIC ${zks_c}/include)
target_compile_options(hashtable PRIVATE ${ZKS_WARN_CFLAGS})

set(zks_hashtable_check sh -c
  "g=$(\"$1\" -g \"$3\" | awk 'NF >= 3 && $(NF - 1) ~ /^[A-TV-Z]$/ { print $NF }') && n=$(\"$2\" t \"$3\" | awk '!/^__.SYMDEF/ { n++ } END { print n + 0 }') && ( ( test -z \"$g\" && test \"$n\" = 1 ) || ( echo \"$3: $n members, globals: $g\" >&2 && exit 1 ) )"
  sh "${zks_nm}" "${CMAKE_AR}" $<TARGET_FILE:hashtable>)
add_custom_command(TARGET hashtable POST_BUILD
  COMMAND ${zks_hashtable_check}
  COMMENT "Checking that the hashtable archive has no global symbol"
  VERBATIM)

add_library(zookeeper STATIC "${ZK_ALL_OBJ}")
add_dependencies(zookeeper zks_${zks_fl}_all)
set_target_properties(zookeeper PROPERTIES LINKER_LANGUAGE C)
target_include_directories(zookeeper PUBLIC ${zks_c}/include ${CMAKE_CURRENT_BINARY_DIR}/include
                           ${zks_c}/generated)
target_link_libraries(zookeeper PUBLIC hashtable ${zks_link_libs})
if(ZKRS_TLS)
  target_compile_definitions(zookeeper PUBLIC HAVE_OPENSSL_H)
  if(ZKRS_OPENSSL_INCLUDE_DIR)
    target_include_directories(zookeeper SYSTEM PUBLIC "${ZKRS_OPENSSL_INCLUDE_DIR}")
  endif()
endif()
if(ZKRS_SASL)
  target_compile_definitions(zookeeper PUBLIC HAVE_CYRUS_SASL_H)
endif()
set(zks_install_targets zookeeper hashtable)

if(ZOOKEEPER_BUILD_SHARED)
  set(zks_shared zookeeper_${zks_fl})
  add_library(${zks_shared} SHARED "${ZK_ALL_OBJ}")
  add_dependencies(${zks_shared} zks_${zks_fl}_all)
  set_target_properties(${zks_shared} PROPERTIES LINKER_LANGUAGE C SOVERSION 2)
  target_link_libraries(${zks_shared} PRIVATE ${zks_link_libs})
  if(APPLE)
    # Mach-O shared library configuration matching legacy ABI properties:
    # Mach-O version 3.0.0, absolute install name directory, and uninhibited symbol interposition.
    if(CMAKE_VERSION VERSION_LESS 3.17)
      message(FATAL_ERROR "the Mach-O shared library needs CMake 3.17 or later")
    endif()
    set(zks_exports "${CMAKE_CURRENT_BINARY_DIR}/zookeeper_${zks_fl}.exp")
    zk_rust_exports(${zks_fl} exp "${zks_exports}")
    set_target_properties(${zks_shared} PROPERTIES
      MACHO_COMPATIBILITY_VERSION 3.0.0 MACHO_CURRENT_VERSION 3.0.0
      INSTALL_NAME_DIR "${CMAKE_INSTALL_FULL_LIBDIR}")
    target_link_options(${zks_shared} PRIVATE "LINKER:-exported_symbols_list,${zks_exports}"
                        "LINKER:-dead_strip")
  else()
    set(zks_exports "${CMAKE_CURRENT_BINARY_DIR}/zookeeper_${zks_fl}.map")
    zk_rust_exports(${zks_fl} map "${zks_exports}")
    set_target_properties(${zks_shared} PROPERTIES VERSION 2.0.0)
    target_link_options(${zks_shared} PRIVATE "LINKER:--version-script=${zks_exports}"
                        "LINKER:--gc-sections")
  endif()
  set_property(TARGET ${zks_shared} APPEND PROPERTY LINK_DEPENDS "${zks_exports}")
  list(APPEND zks_install_targets ${zks_shared})
endif()

if(CARGO_EXECUTABLE)
  set(zks_xtask "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${ZKRS_CARGO_DIR}"
      "${CARGO_EXECUTABLE}" ${ZKRS_CARGO_SOURCE_ARGS} xtask abi)
  set(zks_abi_static_cmd ${zks_xtask} static $<TARGET_FILE:zookeeper> --flavor ${zks_fl}
      --config ${ZKRS_ABI_CONFIG})
  set(zks_abi_check_cmd ${zks_xtask} check $<TARGET_FILE:zookeeper_${zks_fl}> --flavor ${zks_fl}
      --config ${ZKRS_ABI_CONFIG})
  if(TARGET zookeeper_${zks_fl})
    add_custom_target(abi-check COMMAND ${zks_abi_check_cmd}
      DEPENDS zookeeper_${zks_fl}
      WORKING_DIRECTORY "${ZKRS_WORKSPACE}"
      USES_TERMINAL VERBATIM)
  endif()
  add_custom_target(abi-static COMMAND ${zks_abi_static_cmd}
    DEPENDS zookeeper
    WORKING_DIRECTORY "${ZKRS_WORKSPACE}"
    USES_TERMINAL VERBATIM)
endif()

# Public API headers and library artifact installation targets.
install(FILES ${zks_c}/include/zookeeper.h ${zks_c}/include/zookeeper_version.h
              ${zks_c}/include/zookeeper_log.h ${zks_c}/include/proto.h
              ${zks_c}/include/recordio.h ${zks_c}/generated/zookeeper.jute.h
        DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/zookeeper")
install(TARGETS ${zks_install_targets}
        ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}"
        LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
        RUNTIME DESTINATION "${CMAKE_INSTALL_BINDIR}")

foreach(d LIBDIR INCLUDEDIR)
  if(IS_ABSOLUTE "${CMAKE_INSTALL_${d}}")
    set(zks_pc_${d} "${CMAKE_INSTALL_${d}}")
  else()
    set(zks_pc_${d} "\${prefix}/${CMAKE_INSTALL_${d}}")
  endif()
endforeach()
if(ZOOKEEPER_BUILD_SHARED)
  set(zks_pc_libs "-lzookeeper_${zks_fl}")
else()
  set(zks_pc_libs "-lzookeeper -lhashtable")
endif()
# Aggregate private link dependencies for pkg-config descriptors and consumer link validation.
set(zks_core_libs ${ZKRS_SASL_LIBS} ${ZKRS_NATIVE_LIBS})
if(zks_fl STREQUAL "mt")
  set(zks_pc_desc "multithreaded")
  list(APPEND zks_core_libs ${CMAKE_THREAD_LIBS_INIT})
else()
  set(zks_pc_desc "single threaded")
endif()
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  list(APPEND zks_core_libs -lrt)
endif()
list(REMOVE_DUPLICATES zks_core_libs)
string(JOIN " " zks_pc_private ${zks_core_libs})
set(zks_pc "${CMAKE_CURRENT_BINARY_DIR}/zookeeper_${zks_fl}.pc")
file(WRITE "${zks_pc}.in" "prefix=${CMAKE_INSTALL_PREFIX}
exec_prefix=\${prefix}
libdir=${zks_pc_LIBDIR}
includedir=${zks_pc_INCLUDEDIR}

Name: zookeeper_${zks_fl}
Description: Apache ZooKeeper C client library, ${zks_pc_desc}
Version: ${PROJECT_VERSION}
Cflags: -I\${includedir} -I\${includedir}/zookeeper
Libs: -L\${libdir} ${zks_pc_libs}
Libs.private: ${zks_pc_private}
")
configure_file("${zks_pc}.in" "${zks_pc}" COPYONLY)
install(FILES "${zks_pc}" DESTINATION "${CMAKE_INSTALL_LIBDIR}/pkgconfig")

# Register Rust workspace and FFI crate unit test suites under the "rust" test label.
enable_testing()
if(ZKRS_STATICLIB STREQUAL "")
  add_test(NAME zkrs_workspace_tests
    COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${ZKRS_CARGO_DIR}"
            "${CARGO_EXECUTABLE}" ${ZKRS_CARGO_SOURCE_ARGS} test --locked --workspace --exclude zk-ffi
    WORKING_DIRECTORY "${ZKRS_WORKSPACE}")
  add_test(NAME zkrs_ffi_tests
    COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${ZKRS_CARGO_DIR}"
            ${ZKRS_TLS_ENV} "${CARGO_EXECUTABLE}" ${ZKRS_CARGO_SOURCE_ARGS} test --locked -p zk-ffi ${ZKRS_CARGO_FEATURES}
    WORKING_DIRECTORY "${ZKRS_WORKSPACE}")
  set_tests_properties(zkrs_workspace_tests zkrs_ffi_tests PROPERTIES LABELS rust)
endif()

# Register post-build binary artifact validation and symbol table verification tests under the "abi" label.
set(ZK_ABI_TEST_HOST "" CACHE STRING "host:port of a running server for the ABI tests; empty skips them")
set(ZK_ABI_TEST_DEAD_HOST "" CACHE STRING "host:port with no server for the ABI tests; empty: the default")
set(ZK_ABI_TEST_REF "" CACHE PATH "Directory of a legacy library of this flavor for the ABI tests")
set(zks_abi_tests zk_abi_hashtable zk_abi_link_target zk_abi_link_flags)
add_test(NAME zk_abi_hashtable COMMAND ${zks_hashtable_check})

set(zks_link_src "${CMAKE_CURRENT_BINARY_DIR}/zks_link_check.c")
file(WRITE "${zks_link_src}.in" "/* Generated by cmake/ZkRustCore.cmake. */
#include <stdio.h>
#include <string.h>
#ifdef ZKS_LINK_NO_OPENSSL_H
#undef HAVE_OPENSSL_H
#endif
#include <zookeeper.h>
int main(void)
{
    int rc = zoo_acreate_ttl(NULL, \"/a\", NULL, -1, &ZOO_OPEN_ACL_UNSAFE, ZOO_PERSISTENT_WITH_TTL,
                             0, NULL, NULL);
    if (rc != ZBADARGUMENTS || ZOO_CONNECTED_STATE != 3 || strcmp(zoo_version_str(), ZOO_VERSION)) {
        fprintf(stderr, \"zks_link_check: unexpected result %d\\n\", rc);
        return 1;
    }
    return 0;
}
")
configure_file("${zks_link_src}.in" "${zks_link_src}" COPYONLY)
add_executable(zks_link_check "${zks_link_src}")
target_link_libraries(zks_link_check zookeeper hashtable)
# Suppress OpenSSL header inclusion in consumer link checks when built with Rust TLS on hosts lacking OpenSSL headers.
if(ZKRS_SKIP_PROGRAMS)
  target_compile_definitions(zks_link_check PRIVATE ZKS_LINK_NO_OPENSSL_H)
endif()
add_test(NAME zk_abi_link_target COMMAND zks_link_check)
# Propagate Cyrus SASL library search paths discovered during configuration.
set(zks_link_flags ${zks_core_libs})
if(ZKRS_SASL)
  get_filename_component(zks_sasl_libdir "${CYRUS_SASL_SHARED_LIB}" DIRECTORY)
  list(INSERT zks_link_flags 0 -L${zks_sasl_libdir})
endif()
set(zks_link_cflags "")
foreach(d ${zks_flavor_defs})
  list(APPEND zks_link_cflags -D${d})
endforeach()
add_test(NAME zk_abi_link_flags
  COMMAND sh -c "out=$1 && shift && \"$@\" -o \"$out\" && \"$out\"" sh
          "${CMAKE_CURRENT_BINARY_DIR}/zks_link_flags_check" "${CMAKE_C_COMPILER}"
          ${zks_link_cflags} -I${ZKC_DIR}/include
          -I${CMAKE_CURRENT_BINARY_DIR}/include -I${ZKC_DIR}/generated
          "${zks_link_src}" -L$<TARGET_FILE_DIR:zookeeper> -L$<TARGET_FILE_DIR:hashtable>
          -lzookeeper -lhashtable ${zks_link_flags})

if(CARGO_EXECUTABLE)
  add_test(NAME zk_abi_static COMMAND ${zks_abi_static_cmd} WORKING_DIRECTORY "${ZKRS_WORKSPACE}")
  list(APPEND zks_abi_tests zk_abi_static)
endif()
if(TARGET zookeeper_${zks_fl})
  if(CARGO_EXECUTABLE)
    add_test(NAME zk_abi_check COMMAND ${zks_abi_check_cmd} WORKING_DIRECTORY "${ZKRS_WORKSPACE}")
    list(APPEND zks_abi_tests zk_abi_check)
  endif()
  # Configure runtime ABI test harness execution against the built shared library target.
  set(zks_abi_args --lib $<TARGET_FILE_DIR:zookeeper_${zks_fl}> --no-xtask
      --out "${CMAKE_CURRENT_BINARY_DIR}/abi-tests")
  if(zks_fl STREQUAL "st")
    list(APPEND zks_abi_args --flavor st)
  endif()
  if(NOT ZK_ABI_TEST_HOST STREQUAL "")
    list(APPEND zks_abi_args --host "${ZK_ABI_TEST_HOST}")
  endif()
  if(NOT ZK_ABI_TEST_DEAD_HOST STREQUAL "")
    list(APPEND zks_abi_args --dead-host "${ZK_ABI_TEST_DEAD_HOST}")
  endif()
  if(NOT ZK_ABI_TEST_REF STREQUAL "")
    list(APPEND zks_abi_args --ref "${ZK_ABI_TEST_REF}")
  endif()
  if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    list(APPEND zks_abi_args --debug-lib $<TARGET_FILE_DIR:zookeeper_${zks_fl}>)
  endif()
  # Specify target configuration variant governing golden symbol expectations.
  list(APPEND zks_abi_args --config "${ZKRS_ABI_CONFIG}")
  if(SOCK_CLOEXEC_ENABLED)
    list(APPEND zks_abi_args --sock-cloexec yes)
  else()
    list(APPEND zks_abi_args --sock-cloexec no)
  endif()
  if(EXISTS "${ZKRS_DIR}/tests/abi/run-abi-tests.sh")
    add_test(NAME zk_abi_tests
      COMMAND "${CMAKE_COMMAND}" -E env "CC=${CMAKE_C_COMPILER}"
              "${ZKRS_DIR}/tests/abi/run-abi-tests.sh" ${zks_abi_args})
    list(APPEND zks_abi_tests zk_abi_tests)
  endif()
endif()
set_tests_properties(${zks_abi_tests} PROPERTIES LABELS abi)

# Register static archive consumer linkage and symbol boundary verification tests under the "link" label.
string(JOIN " " zks_native_str ${ZKRS_NATIVE_LIBS} ${ZKRS_SASL_LIBS})
set(zks_link_env "CC=${CMAKE_C_COMPILER}" "ZKRS_WORKSPACE=${ZKRS_WORKSPACE}"
    "ZKRS_CARGO_SOURCE_CONFIG=${ZKRS_CARGO_SOURCE_CONFIG}")
if(ZKRS_SASL)
  list(APPEND zks_link_env "LIBRARY_PATH=${zks_sasl_libdir}")
endif()
set(zks_link_args --flavor ${zks_fl} --config ${ZKRS_ABI_CONFIG} --archive $<TARGET_FILE:zookeeper>
    --hashtable $<TARGET_FILE:hashtable> --native-libs "${zks_native_str}" --no-xtask
    --cmake-consumer --cmake "${CMAKE_COMMAND}" --rust-staticlib "${ZKRS_LIB}"
    --cmake-arg "-DWITH_OPENSSL=${WITH_OPENSSL}" --cmake-arg "-DWITH_CYRUS_SASL=${WITH_CYRUS_SASL}"
    --cmake-arg "-DZK_RUST_TLS_PROVIDER=${ZK_RUST_TLS_PROVIDER}"
    --cmake-arg "-DZKRS_WORKSPACE=${ZKRS_WORKSPACE}"
    --out "${CMAKE_CURRENT_BINARY_DIR}/link-tests")
if(WITH_RUST_TLS)
  list(APPEND zks_link_args --cmake-arg -DWITH_RUST_TLS=ON)
endif()
if(NOT ZK_ABI_TEST_HOST STREQUAL "")
  list(APPEND zks_link_args --host "${ZK_ABI_TEST_HOST}")
endif()
if(NOT ZK_ABI_TEST_REF STREQUAL "")
  list(APPEND zks_link_args --ref "${ZK_ABI_TEST_REF}")
endif()
if(EXISTS "${ZKRS_DIR}/tests/link/run-link-tests.sh")
  add_test(NAME zk_link_tests
    COMMAND "${CMAKE_COMMAND}" -E env ${zks_link_env}
            "${ZKRS_DIR}/tests/link/run-link-tests.sh" ${zks_link_args})
  set_tests_properties(zk_link_tests PROPERTIES LABELS link TIMEOUT 1200)
endif()
