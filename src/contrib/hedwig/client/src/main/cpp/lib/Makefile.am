#
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
#

PROTODEF = ../../../../../protocol/src/main/protobuf/PubSubProtocol.proto

lib_LTLIBRARIES = libhedwig01.la
libhedwig01_la_SOURCES = protocol.cpp channel.cpp client.cpp util.cpp clientimpl.cpp publisherimpl.cpp subscriberimpl.cpp eventdispatcher.cpp data.cpp
libhedwig01_la_CPPFLAGS = -I$(top_srcdir)/inc $(DEPS_CFLAGS)
libhedwig01_la_LIBADD = $(DEPS_LIBS) $(BOOST_CPPFLAGS) 
libhedwig01_la_LDFLAGS = -no-undefined $(BOOST_ASIO_LIB) $(BOOST_LDFLAGS) $(BOOST_THREAD_LIB)

protocol.cpp: $(PROTODEF)
	protoc --cpp_out=. -I`dirname $(PROTODEF)` $(PROTODEF)
	sed "s/PubSubProtocol.pb.h/hedwig\/protocol.h/" PubSubProtocol.pb.cc > protocol.cpp
	rm PubSubProtocol.pb.cc
	mv PubSubProtocol.pb.h $(top_srcdir)/inc/hedwig/protocol.h

