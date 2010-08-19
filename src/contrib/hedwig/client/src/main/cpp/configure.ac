AC_INIT([Hedwig C++ Client], [0.1], [zookeeper-dev@hadoop.apache.org], [hedwig-cpp], [http://hadoop.apache.org/zookeeper//])

AC_PREREQ([2.59])
AM_INIT_AUTOMAKE([1.10 no-define foreign])
AC_CONFIG_HEADERS([config.h])
AC_PROG_CXX
AC_CONFIG_FILES([Makefile lib/Makefile test/Makefile hedwig-0.1.pc])
AC_PROG_LIBTOOL
AC_CONFIG_MACRO_DIR([m4])
PKG_CHECK_MODULES([DEPS], [log4cpp >= 0.23 protobuf >= 2.3.0 cppunit])

DX_HTML_FEATURE(ON)
DX_INIT_DOXYGEN(hedwig-c++, c-doc.Doxyfile, doc)

CXXFLAGS="$CXXFLAGS -fno-inline"

AC_OUTPUT

