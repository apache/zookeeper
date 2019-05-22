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
#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.59)

AC_INIT([zooqueue], [3.2.0])

AC_CONFIG_SRCDIR([include/zoo_queue.h])

PACKAGE=zooqueue
VERSION=1.0

AC_SUBST(PACKAGE)
AC_SUBST(VERSION)

BUILD_PATH="`pwd`"

# Checks for programs.
AC_LANG_CPLUSPLUS

AM_INIT_AUTOMAKE([-Wall foreign])
# Checks for libraries.

#initialize Doxygen support
DX_HTML_FEATURE(ON)
DX_CHM_FEATURE(OFF)
DX_CHI_FEATURE(OFF)
DX_MAN_FEATURE(OFF)
DX_RTF_FEATURE(OFF)
DX_XML_FEATURE(OFF)
DX_PDF_FEATURE(OFF)
DX_PS_FEATURE(OFF)
DX_INIT_DOXYGEN([zookeeper-queues],[c-doc.Doxyfile],[docs])

  
ZOOKEEPER_PATH=${BUILD_PATH}/../../../../../zookeeper-client/zookeeper-client-c
ZOOKEEPER_LD=-L${BUILD_PATH}/../../../../../zookeeper-client/zookeeper-client-c\ -lzookeeper_mt

AC_SUBST(ZOOKEEPER_PATH)
AC_SUBST(ZOOKEEPER_LD)

# Checks for header files.
AC_HEADER_DIRENT
AC_HEADER_STDC
AC_CHECK_HEADERS([fcntl.h stdlib.h string.h sys/time.h unistd.h])

# Checks for typedefs, structures, and compiler characteristics.
AC_HEADER_STDBOOL
AC_C_CONST
AC_TYPE_UID_T
AC_C_INLINE
AC_TYPE_OFF_T
AC_TYPE_SIZE_T
AC_STRUCT_ST_BLOCKS
AC_HEADER_TIME
AC_C_VOLATILE
AC_PROG_CC
AC_PROG_LIBTOOL
#check for cppunit 
AM_PATH_CPPUNIT(1.10.2)
# Checks for library functions.
AC_FUNC_UTIME_NULL
AC_CHECK_FUNCS([gettimeofday memset mkdir rmdir strdup strerror strstr strtol strtoul strtoull utime])

AC_CONFIG_FILES([Makefile])
AC_OUTPUT
AC_C_VOLATILE
