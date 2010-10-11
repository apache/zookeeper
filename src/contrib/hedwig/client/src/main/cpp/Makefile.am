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

ACLOCAL_AMFLAGS = -I m4

SUBDIRS = lib test

library_includedir=$(includedir)/hedwig-0.1/hedwig
library_include_HEADERS = inc/hedwig/callback.h inc/hedwig/client.h inc/hedwig/exceptions.h inc/hedwig/publish.h inc/hedwig/subscribe.h 

pkgconfigdir = $(libdir)/pkgconfig
nodist_pkgconfig_DATA = hedwig-0.1.pc

EXTRA_DIST = $(DX_CONFIG) doc/html
