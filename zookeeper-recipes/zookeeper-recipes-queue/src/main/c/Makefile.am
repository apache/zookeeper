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

include $(top_srcdir)/aminclude.am

AM_CFLAGS = -Wall -fPIC -I${ZOOKEEPER_PATH}/include -I${ZOOKEEPER_PATH}/generated \
  -I$(top_srcdir)/include -I/usr/include 
AM_CPPFLAGS = -Wall -I${ZOOKEEPER_PATH}/include -I${ZOOKEEPER_PATH}/generated\
  -I${top_srcdir}/include -I/usr/include
EXTRA_DIST = LICENSE
lib_LTLIBRARIES = libzooqueue.la
libzooqueue_la_SOURCES = src/zoo_queue.c include/zoo_queue.h
libzooqueue_la_CPPFLAGS = -DDLOPEN_MODULE
libzooqueue_la_LDFLAGS = -version-info 0:1:0

#run the tests now

TEST_SOURCES = tests/TestDriver.cc tests/TestClient.cc tests/Util.cc 


check_PROGRAMS = zkqueuetest
nodist_zkqueuetest_SOURCES = ${TEST_SOURCES}
zkqueuetest_LDADD =  ${ZOOKEEPER_LD} libzooqueue.la -lpthread  ${CPPUNIT_LIBS}
zkqueuetest_CXXFLAGS = -DUSE_STATIC_LIB ${CPPUNIT_CFLAGS}

run-check: check
	./zkqueuetest ${TEST_OPTIONS}

clean-local: clean-check
	${RM} ${DX_CLEANFILES}

clean-check:
	${RM} ${nodist_zkqueuetest_OBJECTS} 
