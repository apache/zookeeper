
AM_CPPFLAGS = -Iinclude -Igenerated
AM_CFLAGS = -Wall -Werror

LIB_LDFLAGS = -no-undefined -version-info 1

pkginclude_HEADERS = include/zookeeper.h include/recordio.h generated/zookeeper.jute.h

lib_LTLIBRARIES = libzookeeper_st.la

COMMON_SRC = src/zookeeper.c include/zookeeper.h \
	src/recordio.c include/recordio.h include/proto.h \
	src/zk_adaptor.h generated/zookeeper.jute.c
	
libzookeeper_st_la_SOURCES = $(COMMON_SRC) src/st_adaptor.c
libzookeeper_st_la_LDFLAGS = $(LIB_LDFLAGS)

if WANT_SYNCAPI
lib_LTLIBRARIES += libzookeeper_mt.la
libzookeeper_mt_la_SOURCES = $(COMMON_SRC) src/mt_adaptor.c
libzookeeper_mt_la_CFLAGS = -DTHREADED
libzookeeper_mt_la_LDFLAGS = $(LIB_LDFLAGS) -lpthread
endif

bin_PROGRAMS = cli_st

cli_st_SOURCES = src/cli.c
cli_st_LDADD = libzookeeper_st.la

if WANT_SYNCAPI
bin_PROGRAMS += cli_mt
cli_mt_SOURCES = src/cli.c
cli_mt_LDADD = libzookeeper_mt.la
cli_mt_CFLAGS = -DTHREADED
endif
