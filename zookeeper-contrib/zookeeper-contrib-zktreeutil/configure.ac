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
ZOOKEEPER_PATH=${BUILD_PATH}/../../zookeeper-client/zookeeper-client-c
AC_CHECK_LIB(zookeeper_mt, main, [ZOOKEEPER="-L${ZOOKEEPER_PATH}/target/c/.libs -lzookeeper_mt"],,["-L${ZOOKEEPER_PATH}/target/c/.libs"])
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

dnl OpenSSL
AC_ARG_WITH(openssl,
 [AC_HELP_STRING([--with-openssl[=DIR]], [build with openssl (autodetect openssl library by default) )])],
 [], [with_openssl=yes])
AC_MSG_NOTICE([configuring SSL using --with-openssl=$with_openssl])
saved_CPPFLAGS="$CPPFLAGS"
saved_LDFLAGS="$LDFLAGS"
if test "x$with_openssl" != "xno" && test "x$with_openssl" != "xyes" ; then
        CPPFLAGS="$CPPFLAGS -I$with_openssl/include"
        LDFLAGS="$LDFLAGS -L$with_openssl/lib"
fi
have_openssl=no
AC_CHECK_HEADER(openssl/ssl.h, [ AC_CHECK_LIB(ssl, SSL_CTX_new, [have_openssl=yes]) ])
if test "x$with_openssl" != "xno" && test "x$with_openssl" != "xyes" && test "x$have_openssl" != "xyes"; then
    CPPFLAGS="$saved_CPPFLAGS"
    LDFLAGS="$saved_LDFLAGS"
fi
if test "x$with_openssl" != xno && test "x$have_openssl" = xno; then
    AC_MSG_WARN([cannot build SSL support -- openssl not found])
    with_openssl=no
fi
if test "x$with_openssl" != xno; then
    AC_MSG_NOTICE([building with SSL support])
else
    AC_MSG_NOTICE([building without SSL support])
fi
AM_CONDITIONAL([WANT_OPENSSL],[test "x$with_openssl" != xno])


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
