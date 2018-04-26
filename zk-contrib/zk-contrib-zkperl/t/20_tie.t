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
use Test::More tests => 54;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    skip 'no valid handle', 4 unless (defined($zkh));


    ## DESTROY()

    my $attr = tied(%{$zkh});

    my $ret = $attr->DESTROY();
    ok($ret,
       'DESTROY(): destroyed inner hash');

    $ret = $attr->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on destroyed inner hash');

    $ret = $zkh->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on handle with destroyed inner hash');

    undef $zkh;
    ok(!defined($zkh),
       'undef: released handle with destroyed inner hash');
}

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    skip 'no valid handle', 49 unless (defined($zkh));


    ## TIEHASH(), UNTIE()

    eval {
        tie(%{$zkh}, 'Net::ZooKeeper');
    };
    like($@, qr/tying hashes of class Net::ZooKeeper not supported/,
         'tie(): tying hashes not supported');

    eval {
        Net::ZooKeeper::TIEHASH('Net::ZooKeeper');
    };
    like($@, qr/tying hashes of class Net::ZooKeeper not supported/,
         'TIEHASH(): tying hashes not supported');

    eval {
        untie(%{$zkh});
    };
    like($@, qr/untying hashes of class Net::ZooKeeper not supported/,
         'untie(): untying hashes not supported');

    my $attr = tied(%{$zkh});

    eval {
        $attr->UNTIE(0);
    };
    like($@, qr/untying hashes of class Net::ZooKeeper not supported/,
         'UNTIE(): untying hashes not supported');


    ## FIRSTKEY(), NEXTKEY(), SCALAR()

    my $copy_zkh;
    {
        my %copy_zkh = %{$zkh};
        $copy_zkh = \%copy_zkh;
    }
    bless($copy_zkh, 'Net::ZooKeeper');
    is(ref($copy_zkh), 'Net::ZooKeeper',
       'FIRSTKEY(), NEXTKEY(): copied dereferenced handle');

    eval {
        my $val = $copy_zkh->FIRSTKEY();
    };
    like($@, qr/invalid handle/,
         'FETCHKEY(): invalid handle');

    eval {
        my $val = $copy_zkh->NEXTKEY('data_read_len');
    };
    like($@, qr/invalid handle/,
         'NEXTKEY(): invalid handle');

    my @keys = keys(%{$zkh});
    is(scalar(@keys), 7,
       'keys(): count of keys from handle');

    @keys = keys(%{$copy_zkh});
    is(scalar(@keys), 7,
       'keys(): count of keys from copied dereferenced handle');

    is($attr->FIRSTKEY(), 'data_read_len',
       'FIRSTKEY(): retrieved first key using inner hash');

    is($attr->NEXTKEY('session_id'), 'pending_watches',
       'NEXTKEY(): retrieved last key using inner hash');

    is($attr->NEXTKEY('pending_watches'), undef,
       'NEXTKEY(): undef returned after last key using inner hash');

    ok(scalar(%{$zkh}),
       'scalar(): true value returned for dereferenced handle');

    ok($zkh->SCALAR(),
       'SCALAR(): true value returned');


    ## FETCH()

    eval {
        my $val = $copy_zkh->FETCH('data_read_len');
    };
    like($@, qr/invalid handle/,
         'FETCH(): invalid handle');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        my $val = $zkh->{'foo'};
        ok(!defined($val),
           'FETCH(): undef returned for invalid element');

        like($msg, qr/invalid element/,
             'FETCH(): invalid element');
    }

    is($zkh->{'data_read_len'}, 1023,
       'FETCH(): default data read length');

    is($zkh->{'path_read_len'}, 1023,
       'FETCH(): default path read length');

    is($zkh->{'hosts'}, $hosts,
       'FETCH(): server hosts');

    is($zkh->{'session_timeout'}, 10000,
       'FETCH(): default session timeout');

    ok(defined($zkh->{'session_id'}),
       'FETCH(): session ID');

    SKIP: {
        my $zkh = Net::ZooKeeper->new('0.0.0.0:0');

        skip 'no valid handle with invalid host', 1 unless (defined($zkh));

        is($zkh->{'session_id'}, '',
           'FETCH(): empty session ID with invalid host');
    }

    is($zkh->{'pending_watches'}, 0,
       'FETCH(): default pending watch list length');

    is($attr->FETCH('data_read_len'), 1023,
       'FETCH(): default data read length using inner hash');


    ## STORE()

    eval {
        my $val = $copy_zkh->STORE('data_read_len', 'foo');
    };
    like($@, qr/invalid handle/,
         'STORE(): invalid handle');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->{'foo'} = 'foo';
        like($msg, qr/invalid element/,
             'STORE(): invalid element');
    }

    eval {
        $zkh->{'data_read_len'} = -3;
    };
    like($@, qr/invalid data read length/,
         'STORE(): invalid data read length');

    eval {
        $zkh->{'path_read_len'} = -3;
    };
    like($@, qr/invalid path read length/,
         'STORE(): invalid path read length');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->{'hosts'} = 'foo';
        like($msg, qr/read-only element: hosts/,
             'STORE(): read-only server hosts element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->{'session_timeout'} = 0;
        like($msg, qr/read-only element: session_timeout/,
             'STORE(): read-only session timeout element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->{'session_id'} = 'foo';
        like($msg, qr/read-only element: session_id/,
             'STORE(): read-only session ID element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->{'pending_watches'} = 0;
        like($msg, qr/read-only element: pending_watches/,
             'STORE(): read-only pending watch list length element');
    }

    $zkh->{'data_read_len'} = 200;
    is($zkh->{'data_read_len'}, 200,
       'STORE(): updated data read length');

    $zkh->{'path_read_len'} = 100;
    is($zkh->{'path_read_len'}, 100,
       'STORE(): updated path read length');

    $attr->STORE('data_read_len', 100);
    is($zkh->{'data_read_len'}, 100,
       'STORE(): updated data read length using inner hash');


    ## EXISTS()

    eval {
        my $val = $copy_zkh->EXISTS('data_read_len');
    };
    like($@, qr/invalid handle/,
         'EXISTS(): invalid handle');

    ok(!exists($zkh->{'foo'}),
       'exists(): invalid element of handle');

    ok(exists($zkh->{'data_read_len'}),
       'exists(): data read length');

    ok(exists($zkh->{'path_read_len'}),
       'exists(): path read length');

    ok(exists($zkh->{'hosts'}),
       'exists(): server hosts');

    ok(exists($zkh->{'session_timeout'}),
       'exists(): session timeout');

    ok(exists($zkh->{'session_id'}),
       'exists(): session ID');

    ok(exists($zkh->{'pending_watches'}),
       'exists(): pending watch list length');

    ok($attr->EXISTS('data_read_len'),
       'EXISTS(): data read length using inner hash');


    ## DELETE(), CLEAR()

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        delete($zkh->{'data_read_len'});
        like($msg,
             qr/deleting elements from hashes of class Net::ZooKeeper not supported/,
             'delete(): deleting hash elements not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->DELETE({'data_read_len'});
        like($msg,
             qr/deleting elements from hashes of class Net::ZooKeeper not supported/,
             'DELETE(): deleting hash elements not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        %{$zkh} = ();
        like($msg, qr/clearing hashes of class Net::ZooKeeper not supported/,
             'assign: clearing hashes not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $zkh->CLEAR();
        like($msg, qr/clearing hashes of class Net::ZooKeeper not supported/,
             'CLEAR(): clearing hashes not supported');
    }
}

