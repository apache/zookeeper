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

use File::Spec;
use Test::More tests => 29;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


## new(), DESTROY()

Net::ZooKeeper::set_deterministic_conn_order(1);

my $zkh = Net::ZooKeeper->new($hosts);
isa_ok($zkh, 'Net::ZooKeeper',
       'new(): created handle');

SKIP: {
    skip 'no valid handle', 3 unless (defined($zkh));

    my $ret = $zkh->DESTROY();
    ok($ret,
       'DESTROY(): destroyed handle');

    $ret = $zkh->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on destroyed handle');

    undef $zkh;
    ok(!defined($zkh),
       'undef: released handle');
}

Net::ZooKeeper::set_deterministic_conn_order(0);

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    skip 'no valid handle', 10 unless (defined($zkh));

    my $copy_zkh = $zkh;
    isa_ok($copy_zkh, 'Net::ZooKeeper',
           'assign: copied handle');

    my $ret = $zkh->exists($root_path);
    ok(defined($ret),
       'exists(): no error from original handle');

    undef $zkh;
    ok(!defined($zkh),
       'undef: released original handle');

    $ret = $copy_zkh->exists($root_path);
    ok(defined($ret),
       'exists(): no error from first copy of handle');

    $zkh = $copy_zkh;
    isa_ok($zkh, 'Net::ZooKeeper',
           'assign: re-copied handle');

    $ret = $copy_zkh->DESTROY();
    ok($ret,
       'DESTROY(): destroyed first copy of handle');

    eval {
        $zkh->exists($root_path);
    };
    like($@, qr/invalid handle/,
         'exists(): invalid second copy of handle');

    undef $copy_zkh;
    ok(!defined($copy_zkh),
       'undef: released first copy of handle');

    $ret = $zkh->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on second copy of destroyed handle');

    undef $zkh;
    ok(!defined($zkh),
       'undef: released second copy of handle');
}

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    skip 'no valid handle', 6 unless (defined($zkh));

    my $copy_zkh;
    {
        my %copy_zkh = %{$zkh};
        $copy_zkh = \%copy_zkh;
    }
    bless($copy_zkh, 'Net::ZooKeeper');
    isa_ok($copy_zkh, 'Net::ZooKeeper',
           'FIRSTKEY(), NEXTKEY(): copied dereferenced handle');

    eval {
        $copy_zkh->exists($root_path);
    };
    like($@, qr/invalid handle/,
         'exists(): invalid copy of dereferenced handle');

    $ret = $copy_zkh->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on copy of dereferenced handle');

    undef $copy_zkh;
    ok(!defined($copy_zkh),
       'undef: released copy of dereferenced handle');

    my $ret = $zkh->exists($root_path);
    ok(defined($ret),
       'exists(): no error from original handle');

    undef $zkh;
    ok(!defined($zkh),
       'undef: released original handle');
}

Net::ZooKeeper::set_deterministic_conn_order(1);

my $zkh1 = Net::ZooKeeper->new($hosts, 'session_timeout' => 0x3FFF_FFFF);
isa_ok($zkh1, 'Net::ZooKeeper',
       'new(): created handle with maximum session timeout');

SKIP: {
    my $ret = $zkh1->exists($root_path) if (defined($zkh1));

    skip 'no connection to ZooKeeper', 7 unless
        (defined($ret) and $ret);


    ## FETCH() of read-only attributes

    ok(($zkh1->{'session_timeout'} > 0 and
        $zkh1->{'session_timeout'} <= 0x3FFF_FFFF),
       'FETCH(): session timeout reset after connection');

    my $session_id1 = $zkh1->{'session_id'};
    ok((length($session_id1) > 0),
       'FETCH(): non-empty session ID after connection');

    SKIP: {
        skip 'no session ID after connection', 1 unless
            (length($session_id1) > 0);

        my @nonzero_bytes = grep($_ != 0, unpack('c' x length($session_id1),
                                                 $session_id1));
        ok((@nonzero_bytes > 0),
           'FETCH(): non-zero session ID after connection');
    }

    ## NOTE: to test re-connections with saved session IDs we create a second
    ## connection with the same ID while the first is still active;
    ## this is bad practice in normal usage

    my $zkh2 = Net::ZooKeeper->new($hosts,
                                  'session_id' => $session_id1,
                                  'session_timeout' => 20000);
    isa_ok($zkh2, 'Net::ZooKeeper',
           'new(): created handle with session ID and valid session timeout');

    $ret = $zkh2->exists($root_path);
    ok($ret,
       'new(): reconnection with session ID');

    SKIP: {
        skip 'no connection to ZooKeeper', 2 unless ($ret);

        is($zkh2->{'session_timeout'}, 20000,
           'FETCH(): session timeout unchanged after connection');

        my $session_id2 = $zkh2->{'session_id'};
        ok((length($session_id2) == length($session_id1)
            and $session_id2 eq $session_id1),
           'FETCH(): reconnect with session ID');
    }
}

