# Net::ZooKeeper - Perl extension for Apache ZooKeeper
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

use 5.008_008;

use Config;
use ExtUtils::MakeMaker;
use Getopt::Long;

my @zk_inc_paths;
my @zk_lib_paths;

GetOptions(
    'zookeeper-include=s' => \@zk_inc_paths,
    'zookeeper-lib=s' => \@zk_lib_paths
);

my $zk_inc_paths = join(' ', map("-I$_", @zk_inc_paths));
my $zk_lib_paths = join(' ', map("-L$_", @zk_lib_paths));

$zk_inc_paths .= ' ' unless ($zk_inc_paths eq '');
$zk_lib_paths .= ' ' unless ($zk_lib_paths eq '');

my $cc = $Config{'cc'};
my $check_file = 'build/check_zk_version';

my $check_out =
    qx($cc -c $zk_inc_paths -I. -c $check_file.c -o $check_file.o 2>&1);

if ($?) {
    if ($check_out =~ /zookeeper_version\.h/) {
        die("Could not determine ZooKeeper version:\n\n$check_out");
    }
    else {
        ## keep in sync with build/check_zk_version.h
        die("Net::ZooKeeper requires at least ZooKeeper version 3.1.1\n");
    }
}

WriteMakefile(
    'INC'          => "$zk_inc_paths-I.",
    'LIBS'         => [ "$zk_lib_paths-lzookeeper_mt" ],
    'NAME'         => 'Net::ZooKeeper',
    'VERSION_FROM' => 'ZooKeeper.pm',
    'clean'        => { 'FILES' => 'build/check_zk_version.o' }
);

