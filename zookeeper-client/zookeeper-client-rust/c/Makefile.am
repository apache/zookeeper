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

# This file builds the drop-in libraries libzookeeper_st and libzookeeper_mt
# with the Rust client core. The variables below are the global variables
# of the legacy Makefile.am, in the same order and with the same values.
# The test dv_bld_makefile_parity compares them with the legacy file.
# rust.am holds the libraries, the programs and the tests.

AUTOMAKE_OPTIONS = serial-tests

# The legacy C tree. The build reads it and never writes to it.
ZKC_SRCDIR = $(srcdir)/../../zookeeper-client-c
ZKC_ABS_SRCDIR = $(abs_srcdir)/../../zookeeper-client-c

if SOLARIS
  SOLARIS_CPPFLAGS = -D_POSIX_PTHREAD_SEMANTICS
  SOLARIS_LIB_LDFLAGS = -lnsl -lsocket
endif

# zk_rust.m4 removes the directory flags of the OpenSSL and SASL probes
# from CPPFLAGS and LDFLAGS. ZK_OPENSSL_DIR_CPPFLAGS and ZK_SASL_DIR_CPPFLAGS
# give the include directories back to the programs. The programs link no
# OpenSSL library.
if WANT_OPENSSL
  OPENSSL_CPPFLAGS = -DHAVE_OPENSSL_H $(ZK_OPENSSL_DIR_CPPFLAGS)
endif

if WANT_SASL
  SASL_CPPFLAGS = -DHAVE_CYRUS_SASL_H $(ZK_SASL_DIR_CPPFLAGS)
endif

AM_CPPFLAGS = -I$(ZKC_SRCDIR)/include -I$(ZKC_SRCDIR)/tests -I$(ZKC_SRCDIR)/generated $(SOLARIS_CPPFLAGS) $(OPENSSL_CPPFLAGS) $(SASL_CPPFLAGS)
AM_CFLAGS = -Wall -Werror -Wdeclaration-after-statement
AM_CXXFLAGS = -Wall $(USEIPV6)

# Additional flags for coverage testing (if enabled)
if ENABLEGCOV
  AM_CFLAGS += -fprofile-arcs -ftest-coverage
  AM_LDFLAGS = -lgcov
endif

# The installed headers are the headers of the legacy tree.
nodist_pkginclude_HEADERS = $(ZKC_SRCDIR)/include/zookeeper.h \
    $(ZKC_SRCDIR)/include/zookeeper_version.h $(ZKC_SRCDIR)/include/zookeeper_log.h \
    $(ZKC_SRCDIR)/include/proto.h $(ZKC_SRCDIR)/include/recordio.h \
    $(ZKC_SRCDIR)/generated/zookeeper.jute.h

noinst_LTLIBRARIES =

# configure takes its version from this header.
CONFIGURE_DEPENDENCIES = $(ZKC_SRCDIR)/include/zookeeper_version.h

# The drop-in project has no source tarball of its own, because the
# ZooKeeper source release ships it. Thus make dist stops with an error.
dist-hook:
	@test -z "$(distdir)" || rm -rf "$(distdir)"
	@echo "the drop-in project has no source tarball: use the ZooKeeper source release" >&2
	@exit 1

include $(srcdir)/rust.am
