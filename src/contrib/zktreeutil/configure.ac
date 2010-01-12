#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.59)

AC_INIT([zktreeutil], [1.0.0])
AM_INIT_AUTOMAKE(foreign)

AC_CONFIG_SRCDIR([src])
AM_CONFIG_HEADER([config.h])

PACKAGE=zktreeutil
VERSION=1.0.0

AC_SUBST(PACKAGE)
AC_SUBST(VERSION)
BUILD_PATH="`pwd`"

# Checks for programs.
AC_LANG_CPLUSPLUS
AC_PROG_CXX

# Checks for libxm2.
AM_PATH_XML2(2.7.3)
XML2_INCLUDE="/usr/include/libxml2"
AC_SUBST(XML2_INCLUDE)

# Zookeeper C client
ZOOKEEPER_PATH=${BUILD_PATH}/../../c
AC_CHECK_LIB(zookeeper_mt, main, [ZOOKEEPER="-L${ZOOKEEPER_PATH}/.libs -lzookeeper_mt"],,["-L${ZOOKEEPER_PATH}/.libs"])
if test -z "${ZOOKEEPER}"; then
      AC_ERROR("... zookeeper C client not found!")
fi

AC_SUBST(ZOOKEEPER)
AC_SUBST(ZOOKEEPER_PATH)

### log4cxx ###

LOG4CXX_VERSION="0.10.0"
LOG4CXX_INCLUDE="/usr/local/include"
LOG4CXX_LIB_PATH="/usr/local/lib"
AC_CHECK_LIB(log4cxx, main, [LOG4CXX="-L${LOG4CXX_LIB_PATH} -llog4cxx"],,["-L${LOG4CXX_LIB_PATH}"])
if test -z "${LOG4CXX}"; then
      AC_ERROR("... log4cxx not found!")
fi

AC_SUBST(LOG4CXX)
AC_SUBST(LOG4CXX_VERSION)
AC_SUBST(LOG4CXX_INCLUDE)

# Checks for header files.
AC_HEADER_DIRENT
AC_HEADER_STDC
AC_CHECK_HEADERS([stdlib.h string.h stdio.h unistd.h boost/shared_ptr.hpp boost/algorithm/string.hpp boost/algorithm/string/split.hpp])

# Checks for typedefs, structures, and compiler characteristics.
AC_HEADER_STDBOOL
AC_C_CONST
AC_C_INLINE
AC_TYPE_SIZE_T
AC_C_VOLATILE

AC_CONFIG_FILES([Makefile])
AC_CONFIG_FILES([src/Makefile])
AC_OUTPUT
