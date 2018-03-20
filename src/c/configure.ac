#                                               -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.59)

AC_INIT([zookeeper C client],3.4.12,[user@zookeeper.apache.org],[zookeeper])
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
   AM_PATH_CPPUNIT(1.10.2)
fi

if test "$CALLER" = "ANT" ; then
CPPUNIT_CFLAGS="$CPPUNIT_CFLAGS -DZKSERVER_CMD=\"\\\"${base_dir}/src/c/tests/zkServer.sh\\\"\""
else
CPPUNIT_CFLAGS="$CPPUNIT_CFLAGS -DZKSERVER_CMD=\"\\\"./tests/zkServer.sh\\\"\""
AC_CHECK_FILES([generated/zookeeper.jute.c generated/zookeeper.jute.h],[],
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
