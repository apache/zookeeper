# need this for Doxygen integration
include $(top_srcdir)/aminclude.am

AUTOMAKE_OPTIONS = serial-tests
if SOLARIS
  SOLARIS_CPPFLAGS = -D_POSIX_PTHREAD_SEMANTICS
  SOLARIS_LIB_LDFLAGS = -lnsl -lsocket
endif
AM_CPPFLAGS = -I${srcdir}/include -I${srcdir}/tests -I${srcdir}/generated $(SOLARIS_CPPFLAGS)
AM_CFLAGS = -Wall -Werror -Wdeclaration-after-statement
AM_CXXFLAGS = -Wall $(USEIPV6)

# Additional flags for coverage testing (if enabled)
if ENABLEGCOV
  AM_CFLAGS += -fprofile-arcs -ftest-coverage
  AM_LDFLAGS = -lgcov
endif

LIB_LDFLAGS = -no-undefined -version-info 2 $(SOLARIS_LIB_LDFLAGS)

if WANT_SASL
SASL_CFLAGS = -DSASL
SASL_LIBS = -lsasl2
SASL_HDR = include/zookeeper_sasl.h
SASL_SRC = src/zk_sasl.c $(SASL_HDR)
else
STATIC_CXX=-DUSE_STATIC_LIB
STATIC_LD=-static-libtool-libs
endif

pkginclude_HEADERS = include/zookeeper.h include/zookeeper_version.h include/zookeeper_log.h include/proto.h include/recordio.h generated/zookeeper.jute.h $(SASL_HDR)
EXTRA_DIST=LICENSE

HASHTABLE_SRC = src/hashtable/hashtable_itr.h src/hashtable/hashtable_itr.c \
    src/hashtable/hashtable_private.h src/hashtable/hashtable.h src/hashtable/hashtable.c

noinst_LTLIBRARIES = libhashtable.la
libhashtable_la_SOURCES = $(HASHTABLE_SRC)

COMMON_SRC = src/zookeeper.c include/zookeeper.h include/zookeeper_version.h include/zookeeper_log.h\
    src/recordio.c include/recordio.h include/proto.h \
    src/zk_adaptor.h generated/zookeeper.jute.c \
    src/zk_log.c src/zk_hashtable.h src/zk_hashtable.c \
    src/addrvec.h src/addrvec.c $(SASL_SRC)

# These are the symbols (classes, mostly) we want to export from our library.
EXPORT_SYMBOLS = '(zoo_|zookeeper_|zhandle|Z|format_log_message|log_message|logLevel|deallocate_|allocate_|zerror|is_unrecoverable)'
noinst_LTLIBRARIES += libzkst.la
libzkst_la_SOURCES =$(COMMON_SRC) src/st_adaptor.c
libzkst_la_LIBADD = -lm $(CLOCK_GETTIME_LIBS) $(SASL_LIBS)

lib_LTLIBRARIES = libzookeeper_st.la
libzookeeper_st_la_SOURCES =
libzookeeper_st_la_LIBADD=libzkst.la libhashtable.la
libzookeeper_st_la_DEPENDENCIES=libzkst.la libhashtable.la
libzookeeper_st_la_LDFLAGS = $(LIB_LDFLAGS) -export-symbols-regex $(EXPORT_SYMBOLS)

if WANT_SYNCAPI
noinst_LTLIBRARIES += libzkmt.la
libzkmt_la_SOURCES =$(COMMON_SRC) src/mt_adaptor.c
libzkmt_la_CFLAGS = -DTHREADED
libzkmt_la_LIBADD = -lm $(CLOCK_GETTIME_LIBS) $(SASL_LIBS)

lib_LTLIBRARIES += libzookeeper_mt.la
libzookeeper_mt_la_SOURCES =
libzookeeper_mt_la_LIBADD=libzkmt.la libhashtable.la -lpthread
libzookeeper_mt_la_DEPENDENCIES=libzkmt.la libhashtable.la
libzookeeper_mt_la_LDFLAGS = $(LIB_LDFLAGS) -export-symbols-regex $(EXPORT_SYMBOLS)
endif

bin_PROGRAMS = cli_st

cli_st_SOURCES = src/cli.c
cli_st_LDADD = libzookeeper_st.la
cli_st_CFLAGS = $(SASL_CFLAGS)

if WANT_SASL
bin_PROGRAMS += cli_sasl_st

cli_sasl_st_SOURCES = src/cli_sasl.c
cli_sasl_st_LDADD = libzookeeper_st.la
cli_sasl_st_CFLAGS = $(SASL_CFLAGS)
endif

if WANT_SYNCAPI
bin_PROGRAMS += cli_mt load_gen

cli_mt_SOURCES = src/cli.c
cli_mt_LDADD = libzookeeper_mt.la
cli_mt_CFLAGS = -DTHREADED $(SASL_CFLAGS)

load_gen_SOURCES = src/load_gen.c
load_gen_LDADD = libzookeeper_mt.la
load_gen_CFLAGS = -DTHREADED $(SASL_CFLAGS)

if WANT_SASL
bin_PROGRAMS += cli_sasl_mt

cli_sasl_mt_SOURCES = src/cli_sasl.c
cli_sasl_mt_LDADD = libzookeeper_mt.la
cli_sasl_mt_CFLAGS = -DTHREADED $(SASL_CFLAGS)
endif

endif

#########################################################################
# build and run unit tests

EXTRA_DIST+=$(wildcard ${srcdir}/tests/*.cc) $(wildcard ${srcdir}/tests/*.h) \
    ${srcdir}/tests/wrappers.opt ${srcdir}/tests/wrappers-mt.opt

# These tests are ordered in a logical manner such that each builds upon basic
# functionality tested in prior tests. e.g. the most basic functionality is
# tested in TestZookeeperInit and TestZookeeperClose and as such should be tested
# first as a foundation with more complex test suites to follow.
TEST_SOURCES = \
	tests/TestDriver.cc \
	tests/LibCMocks.cc \
	tests/LibCSymTable.cc \
	tests/MocksBase.cc \
	tests/ZKMocks.cc \
	tests/Util.cc \
	tests/ThreadingUtil.cc \
	tests/TestZookeeperInit.cc \
	tests/TestZookeeperClose.cc \
	tests/TestReconfig.cc \
	tests/TestReconfigServer.cc \
	tests/TestClientRetry.cc \
	tests/TestOperations.cc \
	tests/TestMulti.cc \
	tests/TestWatchers.cc \
	tests/TestClient.cc \
	tests/ZooKeeperQuorumServer.cc \
	tests/ZooKeeperQuorumServer.h \
	tests/TestReadOnlyClient.cc \
	tests/TestLogClientEnv.cc \
        tests/TestServerRequireClientSASLAuth.cc \
	$(NULL)

if SOLARIS
  SHELL_SYMBOL_WRAPPERS = cat ${srcdir}/tests/wrappers.opt
  SYMBOL_WRAPPERS=$(SHELL_SYMBOL_WRAPPERS:sh)
else
  SYMBOL_WRAPPERS=$(shell cat ${srcdir}/tests/wrappers.opt)
endif

check_PROGRAMS = zktest-st
TESTS_ENVIRONMENT = ZKROOT=${srcdir}/../.. \
                    CLASSPATH=$$CLASSPATH:$$CLOVER_HOME/lib/clover*.jar
nodist_zktest_st_SOURCES = $(TEST_SOURCES)
zktest_st_LDADD = libzkst.la libhashtable.la $(CPPUNIT_LIBS) -ldl
zktest_st_CXXFLAGS = -DUSE_STATIC_LIB $(CPPUNIT_CFLAGS) $(USEIPV6) $(SASL_CFLAGS) $(SOLARIS_CPPFLAGS)
zktest_st_LDFLAGS = -shared $(SYMBOL_WRAPPERS) $(SOLARIS_LIB_LDFLAGS)

if WANT_SYNCAPI
  check_PROGRAMS += zktest-mt
  nodist_zktest_mt_SOURCES = $(TEST_SOURCES) tests/PthreadMocks.cc
  zktest_mt_LDADD = libzkmt.la libhashtable.la -lpthread $(CPPUNIT_LIBS) -ldl
  zktest_mt_CXXFLAGS = -DUSE_STATIC_LIB -DTHREADED $(CPPUNIT_CFLAGS) $(USEIPV6) $(SASL_CFLAGS)
if SOLARIS
  SHELL_SYMBOL_WRAPPERS_MT = cat ${srcdir}/tests/wrappers-mt.opt
  SYMBOL_WRAPPERS_MT=$(SYMBOL_WRAPPERS) $(SHELL_SYMBOL_WRAPPERS_MT:sh)
else
  SYMBOL_WRAPPERS_MT=$(SYMBOL_WRAPPERS) $(shell cat ${srcdir}/tests/wrappers-mt.opt)
endif
  zktest_mt_LDFLAGS = -shared $(SYMBOL_WRAPPERS_MT) $(SOLARIS_LIB_LDFLAGS)
endif

TESTS = $(check_PROGRAMS)

clean-local: clean-check
	$(RM) $(DX_CLEANFILES)

clean-check:
	$(RM) $(nodist_zktest_st_OBJECTS) $(nodist_zktest_mt_OBJECTS)
