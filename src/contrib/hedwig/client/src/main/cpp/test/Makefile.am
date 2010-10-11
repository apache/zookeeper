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

bin_PROGRAMS = hedwigtest
hedwigtest_SOURCES = main.cpp utiltest.cpp pubsubdatatest.cpp publishtest.cpp subscribetest.cpp servercontrol.cpp pubsubtest.cpp
hedwigtest_CPPFLAGS = -I$(top_srcdir)/inc $(DEPS_CFLAGS) $(BOOST_CPPFLAGS) 
hedwigtest_LDADD = $(DEPS_LIBS) -L$(top_builddir)/lib -lhedwig01 
hedwigtest_LDFLAGS = -no-undefined $(BOOST_ASIO_LIB) $(BOOST_LDFLAGS) $(BOOST_THREAD_LIB)

check: hedwigtest
	bash ../scripts/tester.sh all
