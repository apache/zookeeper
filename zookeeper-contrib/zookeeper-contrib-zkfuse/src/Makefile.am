AM_CXXFLAGS = -I${ZOOKEEPER_PATH}/include -I${ZOOKEEPER_PATH}/generated \
  -I$(top_srcdir)/include -I/usr/include -D_FILE_OFFSET_BITS=64 -D_REENTRANT

noinst_PROGRAMS = zkfuse

zkfuse_SOURCES = zkfuse.cc zkadapter.cc thread.cc log.cc
zkfuse_LDADD = ${ZOOKEEPER_LD}