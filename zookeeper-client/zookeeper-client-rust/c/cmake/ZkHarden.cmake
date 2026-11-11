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

# Compiler configuration and hardening discovery for the C compatibility shim.
# Probes toolchain support for ABI stability, memory safety, and exploit mitigation
# flags using strict diagnostic checks, falling back gracefully when unsupported.
# Configures:
#   ZKS_CFLAGS         ABI conformance and exception unwinding semantics (-fsemantic-interposition, -fexceptions)
#   ZKS_HARDEN_CFLAGS  Hardening flags (stack protection, buffer overflow fortify levels, branch protection)
#   ZKS_WARN_CFLAGS    Diagnostic warning flags, optionally elevated to compilation errors (ZK_WERROR)

include(CheckCSourceCompiles)

# Evaluates whether the active C compiler accepts the specified diagnostic or hardening flags.
# Appends accepted flags to the target variable and sets the status boolean.
function(zk_harden_flag var result)
  string(MAKE_C_IDENTIFIER "ZKS_FLAG_${ARGN}" key)
  string(REPLACE ";" " " flags "${ARGN}")
  set(CMAKE_REQUIRED_FLAGS "-Werror ${flags}")
  set(CMAKE_REQUIRED_QUIET ON)
  check_c_source_compiles("#include <stdio.h>
#include <string.h>
int main(void) { char b[8]; snprintf(b, sizeof b, \"%d\", 1); return (int)strlen(b); }" ${key})
  if(${key})
    set(${var} ${${var}} ${ARGN} PARENT_SCOPE)
    set(${result} ON PARENT_SCOPE)
  else()
    set(${result} OFF PARENT_SCOPE)
  endif()
endfunction()

set(ZKS_CFLAGS "")
set(ZKS_HARDEN_CFLAGS "")
if(NOT MSVC)
  zk_harden_flag(ZKS_CFLAGS ok -fsemantic-interposition)
  zk_harden_flag(ZKS_CFLAGS ok -fexceptions)

  zk_harden_flag(ZKS_HARDEN_CFLAGS ok -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=3)
  if(NOT ok)
    zk_harden_flag(ZKS_HARDEN_CFLAGS ok -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=2)
    if(NOT ok)
      message(STATUS "Dropped the hardening flag -D_FORTIFY_SOURCE")
    endif()
  endif()
  set(zks_flags -fstack-protector-strong -fstack-clash-protection -ftrivial-auto-var-init=zero
      -fno-strict-overflow -Wformat=2 -Wformat-security)
  if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)")
    list(APPEND zks_flags -mbranch-protection=standard)
  endif()
  foreach(flag IN LISTS zks_flags)
    zk_harden_flag(ZKS_HARDEN_CFLAGS ok ${flag})
    if(NOT ok)
      message(STATUS "Dropped the hardening flag ${flag}")
    endif()
  endforeach()
endif()

if(MSVC)
  set(ZKS_WARN_CFLAGS /W3)
  if(ZK_WERROR)
    list(APPEND ZKS_WARN_CFLAGS /WX)
  endif()
else()
  set(ZKS_WARN_CFLAGS -Wall -Wdeclaration-after-statement)
  if(ZK_WERROR)
    list(APPEND ZKS_WARN_CFLAGS -Werror)
  endif()
endif()
