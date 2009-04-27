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

use Config;
use File::Spec;
use Test::More;

BEGIN {
    if ($Config{'useithreads'}) {
        plan tests => 10;
    }
    else {
        plan skip_all => 'no thread support';
    }
}

use threads;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


my $zkh = Net::ZooKeeper->new($hosts);

SKIP: {
    skip 'no valid handle', 9 unless (defined($zkh));

    my($thread) = threads->new(\&thread_test, $zkh);

    SKIP: {
        skip 'no valid thread', 3 unless (defined($thread));

        my(@ret) = $thread->join;

        ok((@ret == 3 and $ret[0]),
           'CLONE_SKIP(): handle reference after spawning thread');

        ok((@ret == 3 and $ret[1]),
           'CLONE_SKIP(): scalar handle reference after spawning thread');

        ok((@ret == 3 and $ret[2]),
           'CLONE_SKIP(): undef handle reference after spawning thread');
    }

    my $stat = $zkh->stat();

    ($thread) = threads->new(\&thread_test, $stat);

    SKIP: {
        skip 'no valid thread', 3 unless (defined($thread));

        my(@ret) = $thread->join;

        ok((@ret == 3 and $ret[0]),
           'stat CLONE_SKIP(): stat handle reference after spawning thread');

        ok((@ret == 3 and $ret[1]),
           'stat CLONE_SKIP(): scalar stat handle reference after ' .
           'spawning thread');

        ok((@ret == 3 and $ret[2]),
           'stat CLONE_SKIP(): undef stat handle reference after ' .
           'spawning thread');
    }

    my $watch = $zkh->watch();

    ($thread) = threads->new(\&thread_test, $watch);

    SKIP: {
        skip 'no valid thread', 3 unless (defined($thread));

        my(@ret) = $thread->join;

        ok((@ret == 3 and $ret[0]),
           'watch CLONE_SKIP(): watch handle reference after spawning thread');

        ok((@ret == 3 and $ret[1]),
           'watch CLONE_SKIP(): scalar watch handle reference after ' .
           'spawning thread');

        ok((@ret == 3 and $ret[2]),
           'watch CLONE_SKIP(): undef watch handle reference after ' .
           'spawning thread');
    }
}

sub thread_test
{
    my $zkh = shift;

    my @ret;

    $ret[0] = ref($zkh) ? 1 : 0;
    $ret[1] = ($ret[0] and ref($zkh) eq 'SCALAR') ? 1 : 0;
    $ret[2] = ($ret[1] and !defined(${$zkh})) ? 1 : 0;

    return @ret;
}

