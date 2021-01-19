#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.59)

AC_INIT([zookeeper C client],3.7.1,[user@zookeeper.apache.org],[zookeeper])
AC_CONFIG_SRCDIR([src/zookeeper.c])

# Save initial CFLAGS and CXXFLAGS values before AC_PROG_CC and AC_PROG_CXX
init_cflags="$CFLAGS"
init_cxxflags="$CXXFLAGS"

# initialize Doxygen support
DX_HTML_FEATURE(ON)
DX_CHM_FEATURE(OFF)
DX_CHI_FEATURE(OFF)
DX_MAN_FEATURE(OFF)
DX_RTF_FEATURE(OFF)
DX_XML_FEATURE(OFF)
DX_PDF_FEATURE(OFF)
DX_PS_FEATURE(OFF)
DX_INIT_DOXYGEN([zookeeper],[c-doc.Doxyfile],[docs])

# initialize automake
AM_INIT_AUTOMAKE([-Wall foreign])

AC_CONFIG_HEADER([config.h])

# Checks for programs.
AC_ARG_WITH(cppunit,
        [  --without-cppunit       do not use CPPUNIT])

if test "$with_cppunit" = "no" ; then
   CPPUNIT_PATH="No_CPPUNIT"
   CPPUNIT_INCLUDE=
   CPPUNIT_LIBS=
else
   CHECK_CPPUNIT(1.10.2)
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

if test "$CALLER" = "ANT" ; then
CPPUNIT_CFLAGS="$CPPUNIT_CFLAGS -DZKSERVER_CMD=\"\\\"${base_dir}/zookeeper-client/zookeeper-client-c/tests/zkServer.sh\\\"\""
else
CPPUNIT_CFLAGS="$CPPUNIT_CFLAGS -DZKSERVER_CMD=\"\\\"./tests/zkServer.sh\\\"\""
AC_CHECK_FILES([$srcdir/generated/zookeeper.jute.c $srcdir/generated/zookeeper.jute.h],[],
    [AC_MSG_ERROR([jute files are missing! Please run "ant compile_jute" while in the zookeeper top level directory.])
])
fi
AC_SUBST(CPPUNIT_CFLAGS)

AC_PROG_CC
AM_PROG_CC_C_O
AC_PROG_CXX
AC_PROG_INSTALL
AC_PROG_LN_S

# AC_DISABLE_SHARED
AC_PROG_LIBTOOL

#enable -D_GNU_SOURCE since the return code value of getaddrinfo
#ifdefed with __USE_GNU
#features.h header undef's __USE_GNU and defines it only if _GNU_SOURCE is defined
#hence this define for gcc
AC_ARG_ENABLE([debug],
 [AS_HELP_STRING([--enable-debug],[enable debug build [default=no]])],
 [],[enable_debug=no])

if test "x$enable_debug" = xyes; then
    if test "x$init_cflags" = x; then
        CFLAGS=""
    fi
    CFLAGS="$CFLAGS -g -O0 -D_GNU_SOURCE"
else
    if test "x$init_cflags" = x; then
        CFLAGS="-g -O2 -D_GNU_SOURCE"
    fi
fi

if test "x$enable_debug" = xyes; then
    if test "x$init_cxxflags" = x; then
        CXXFLAGS=""
    fi
    CXXFLAGS="$CXXFLAGS -g -O0"
else
    if test "x$init_cxxflags" = x; then
        CXXFLAGS="-g -O2"
    fi
fi

# Check whether to enable gcov (coverage test)
AC_ARG_ENABLE(gcov, [AS_HELP_STRING([--enable-gcov],[enable coverage test])])
AC_MSG_CHECKING([whether to enable gcov])
AS_IF([test "x${enable_gcov}" = "xyes"],AC_MSG_RESULT([yes]),AC_MSG_RESULT([no]))
AM_CONDITIONAL([ENABLEGCOV],[test "x${enable_gcov}" = "xyes"])


CXXFLAGS="$CXXFLAGS -std=c++11"

AC_ARG_WITH([syncapi],
 [AS_HELP_STRING([--with-syncapi],[build with support for SyncAPI [default=yes]])],
 [],[with_syncapi=yes])

# Checks for libraries.
AC_CHECK_LIB([pthread], [pthread_mutex_lock],[have_pthread=yes],[have_pthread=no])

if test "x$with_syncapi" != xno && test "x$have_pthread" = xno; then
    AC_MSG_WARN([cannot build SyncAPI -- pthread not found])
    with_syncapi=no
fi
if test "x$with_syncapi" != xno; then
    AC_MSG_NOTICE([building with SyncAPI support])
else
    AC_MSG_NOTICE([building without SyncAPI support])
fi

AM_CONDITIONAL([WANT_SYNCAPI],[test "x$with_syncapi" != xno])

dnl Cyrus SASL 2.x
AC_ARG_WITH(sasl,
 [AC_HELP_STRING([--with-sasl[=DIR]], [build with SASL support via Cyrus SASL 2.x (default=auto)])],
 [], [with_sasl=yes])
if test "x$with_sasl" != "xno"; then
    saved_CPPFLAGS="$CPPFLAGS"
    saved_LDFLAGS="$LDFLAGS"
    if test "x$with_sasl" != "xyes" ; then
        CPPFLAGS="$CPPFLAGS -I$with_sasl/include"
        LDFLAGS="$LDFLAGS -L$with_sasl/lib"
    fi
    have_sasl=no
    AC_CHECK_HEADER(sasl/sasl.h, [
     AC_CHECK_LIB(sasl2, sasl_client_init, [have_sasl=yes])])
    if test "x$have_sasl" != "xyes"; then
        CPPFLAGS="$saved_CPPFLAGS"
        LDFLAGS="$saved_LDFLAGS"
    fi
fi
if test "x$with_sasl" != xno && test "x$have_sasl" = xno; then
    AC_MSG_WARN([cannot build SASL support -- sasl2 not found])
    with_sasl=no
fi
if test "x$with_sasl" != xno; then
    AC_MSG_NOTICE([building with SASL support])
else
    AC_MSG_NOTICE([building without SASL support])
fi
AM_CONDITIONAL([WANT_SASL],[test "x$with_sasl" != xno])

# Checks for header files.
AC_HEADER_STDC
AC_CHECK_HEADERS([arpa/inet.h fcntl.h netdb.h netinet/in.h stdlib.h string.h sys/socket.h sys/time.h unistd.h sys/utsname.h])

# Checks for typedefs, structures, and compiler characteristics.
AC_C_CONST
AC_C_INLINE
AC_HEADER_TIME
AC_CHECK_TYPE([nfds_t],
    [AC_DEFINE([POLL_NFDS_TYPE],[nfds_t],[poll() second argument type])],
    [AC_DEFINE([POLL_NFDS_TYPE],[unsigned int],[poll() second argument type])],
    [#include <poll.h>])

AC_MSG_CHECKING([whether to enable ipv6])

AC_TRY_RUN([ /* is AF_INET6 available? */
#include <sys/types.h>
#include <sys/socket.h>
main()
{
 if (socket(AF_INET6, SOCK_STREAM, 0) < 0)
   exit(1);
 else
   exit(0);
}
], AC_MSG_RESULT(yes)
   ipv6=yes,
   AC_MSG_RESULT(no)
   ipv6=no,
   AC_MSG_RESULT(no)
   ipv6=no)

if test x"$ipv6" = xyes; then
  USEIPV6="-DZOO_IPV6_ENABLED"
  AC_SUBST(USEIPV6)
fi

# use SOCK_CLOEXEC if available and wanted
AC_ARG_WITH([sock_cloexec],
[AS_HELP_STRING([--with-sock-cloexec],[build with SOCK_CLOEXEC flag set on the connections])],
[],[with_sock_cloexec=no])

AC_MSG_CHECKING([whether SOCK_CLOEXEC is available])

AC_TRY_RUN([ /* is SOCK_CLOEXEC available ? */
#include <sys/types.h>
#include <sys/socket.h>
#include <stdlib.h>
main()
{
#ifdef SOCK_CLOEXEC
  exit(0);
#else
  exit(1);
#endif
}
], AC_MSG_RESULT(yes)
   has_sock_cloexec=yes,
   AC_MSG_RESULT(no)
   has_sock_cloexec=no,
   AC_MSG_RESULT(no)
   has_sock_cloexec=no)

if test "x$with_sock_cloexec" != xno && test "x$has_sock_cloexec" = xno; then
  AC_MSG_WARN([cannot use SOCK_CLOEXEC -- SOCK_CLOEXEC undefined on this platform])
  with_sock_cloexec=no
fi

if test "x$with_sock_cloexec" != xno; then
AC_MSG_NOTICE([building with SOCK_CLOEXEC])
else
AC_MSG_NOTICE([building without SOCK_CLOEXEC])
fi

AS_IF([test x"$with_sock_cloexec" != xno], [AC_DEFINE([SOCK_CLOEXEC_ENABLED], [1], [Define to 1, if SOCK_CLOEXEC is defined and wanted])])
AM_CONDITIONAL([SOCK_CLOEXEC_ENABLED],[test "x$with_sock_cloexec" != xno])

# Determine which libraries we need to use clock_gettime
saved_LIBS="$LIBS"
LIBS=""
AC_CHECK_LIB(rt, clock_gettime)
CLOCK_GETTIME_LIBS=$LIBS
AC_SUBST(CLOCK_GETTIME_LIBS)
LIBS="$saved_LIBS"

# Checks for library functions.
AC_CHECK_FUNCS([getcwd gethostbyname gethostname getlogin getpwuid_r gettimeofday getuid memmove memset poll socket strchr strdup strerror strtol])

AC_CONFIG_FILES([Makefile])
AC_CANONICAL_HOST
AM_CONDITIONAL([SOLARIS],[
  case "$host_os" in
  *solaris*)
    true
    ;;
  *)
    false
    ;;
  esac ])
AC_OUTPUT
