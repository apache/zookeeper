#   Licensed to the Apache Software Foundation (ASF) under one or more
#   contributor license agreements.  See the NOTICE file distributed with
#   this work for additional information regarding copyright ownership.
#   The ASF licenses this file to You under the Apache License, Version 2.0
#   (the "License"); you may not use this file except in compliance with
#   the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

#
# RPM Spec file for ZooKeeper version @version@
#

%define name         zkpython
%define version      @version@
%define release      @package.release@

# Installation Locations
%define _prefix      @package.prefix@

# Build time settings
%define _build_dir    @package.build.dir@
%define _final_name   @final.name@
%define _python_lib   @python.lib@
%define debug_package %{nil}

# Disable brp-java-repack-jars for aspect J
%define __os_install_post    \
    /usr/lib/rpm/redhat/brp-compress \
    %{!?__debug_package:/usr/lib/rpm/redhat/brp-strip %{__strip}} \
    /usr/lib/rpm/redhat/brp-strip-static-archive %{__strip} \
    /usr/lib/rpm/redhat/brp-strip-comment-note %{__strip} %{__objdump} \
    /usr/lib/rpm/brp-python-bytecompile %{nil}

# RPM searches perl files for dependancies and this breaks for non packaged perl lib
# like thrift so disable this
%define _use_internal_dependency_generator 0

Summary: ZooKeeper python binding library
Group: Development/Libraries
License: Apache License, Version 2.0
URL: http://zookeeper.apache.org/
Vendor: Apache Software Foundation
Name: %{name}
Version: %{version}
Release: %{release} 
Source0: %{_python_lib}
Prefix: %{_prefix}
Requires: zookeeper-lib == %{version}
AutoReqProv: no
Provides: zkpython

%description
ZooKeeper python binding library

%prep
tar fxz %{_python_lib} -C %{_build_dir}

%build

#########################
#### INSTALL SECTION ####
#########################
%install

%pre

%post

%preun

%files 
%defattr(-,root,root)
%{_prefix}

