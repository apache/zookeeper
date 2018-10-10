#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.59)

AC_INIT([zkfuse], [2.2.0])
AM_INIT_AUTOMAKE(foreign)

AC_CONFIG_SRCDIR([src/zkadapter.h])
AM_CONFIG_HEADER([config.h])

PACKAGE=zkfuse
VERSION=1.0

AC_SUBST(PACKAGE)
AC_SUBST(VERSION)

BUILD_PATH="`pwd`"

# Checks for programs.
AC_LANG_CPLUSPLUS
AC_PROG_CXX

# Checks for libraries.
AC_CHECK_LIB([fuse], [main])
AC_CHECK_LIB([log4cxx], [main], [], [AC_MSG_ERROR("We need log4cxx to build zkfuse")])
AC_CHECK_LIB([thread], [thr_create])
AC_CHECK_LIB([pthread], [pthread_create])
AC_CHECK_LIB([rt], [clock_gettime])
AC_CHECK_LIB([socket], [socket])
AC_CHECK_LIB([nsl], [gethostbyname])
AC_CHECK_LIB([ulockmgr], [ulockmgr_op])

ZOOKEEPER_PATH=${BUILD_PATH}/../../c
ZOOKEEPER_BUILD_PATH=${BUILD_PATH}/../../../build/c
AC_CHECK_LIB(zookeeper_mt, main, [ZOOKEEPER_LD="-L${ZOOKEEPER_BUILD_PATH}/.libs -lzookeeper_mt"],,["-L${ZOOKEEPER_BUILD_PATH}/.libs"])

AC_SUBST(ZOOKEEPER_PATH)
AC_SUBST(ZOOKEEPER_LD)

# Checks for header files.
AC_HEADER_DIRENT
AC_HEADER_STDC
AC_CHECK_HEADERS([fcntl.h stdlib.h string.h sys/time.h unistd.h])
AC_CHECK_HEADERS([boost/shared_ptr.hpp boost/utility.hpp boost/weak_ptr.hpp],, AC_MSG_ERROR([boost library headers not found. Please install boost library.]))

# Checks for typedefs, structures, and compiler characteristics.
AC_HEADER_STDBOOL
AC_C_CONST
AC_TYPE_UID_T
AC_C_INLINE
AC_TYPE_INT32_T
AC_TYPE_INT64_T
AC_TYPE_MODE_T
AC_TYPE_OFF_T
AC_TYPE_SIZE_T
AC_CHECK_MEMBERS([struct stat.st_blksize])
AC_STRUCT_ST_BLOCKS
AC_HEADER_TIME
AC_TYPE_UINT32_T
AC_TYPE_UINT64_T
AC_TYPE_UINT8_T
AC_C_VOLATILE

# Checks for library functions.
AC_FUNC_UTIME_NULL
AC_CHECK_FUNCS([gettimeofday memset mkdir rmdir strdup strerror strstr strtol strtoul strtoull utime])

AC_CONFIG_FILES([Makefile])
AC_CONFIG_FILES([src/Makefile])
AC_OUTPUT
AC_C_VOLATILE
