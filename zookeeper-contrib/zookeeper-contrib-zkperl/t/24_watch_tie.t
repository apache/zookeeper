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
use Test::More tests => 42;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);
    my $watch = $zkh->watch() if (defined($zkh));

    skip 'no valid watch handle', 4 unless (defined($watch));


    ## DESTROY()

    my $attr = tied(%{$watch});

    my $ret = $attr->DESTROY();
    ok($ret,
       'watch DESTROY(): destroyed inner watch hash');

    $ret = $attr->DESTROY();
    ok(!$ret,
       'watch DESTROY(): no action on destroyed inner watch hash');

    $ret = $watch->DESTROY();
    ok(!$ret,
       'watch DESTROY(): no action on watch handle with destroyed inner hash');

    undef $watch;
    ok(!defined($watch),
       'undef: released watch handle with destroyed inner hash');
}

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);
    my $watch = $zkh->watch() if (defined($zkh));

    skip 'no valid watch handle', 37 unless (defined($watch));


    ## TIEHASH(), UNTIE()

    eval {
        tie(%{$watch}, 'Net::ZooKeeper::Watch');
    };
    like($@, qr/tying hashes of class Net::ZooKeeper::Watch not supported/,
         'tie(): tying watch hashes not supported');

    eval {
        Net::ZooKeeper::Watch::TIEHASH('Net::ZooKeeper::Watch');
    };
    like($@, qr/tying hashes of class Net::ZooKeeper::Watch not supported/,
         'watch TIEHASH(): tying watch hashes not supported');

    eval {
        untie(%{$watch});
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Watch not supported/,
         'untie(): untying watch hashes not supported');

    my $attr = tied(%{$watch});

    eval {
        $attr->UNTIE(0);
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Watch not supported/,
         'watch UNTIE(): untying watch hashes not supported');


    ## FIRSTKEY(), NEXTKEY(), SCALAR()

    my $copy_watch;
    {
        my %copy_watch = %{$watch};
        $copy_watch = \%copy_watch;
    }
    bless($copy_watch, 'Net::ZooKeeper::Watch');
    is(ref($copy_watch), 'Net::ZooKeeper::Watch',
       'watch FIRSTKEY(), NEXTKEY(): copied dereferenced watch handle');

    eval {
        my $val = $copy_watch->FIRSTKEY();
    };
    like($@, qr/invalid handle/,
         'watch FETCHKEY(): invalid watch handle');

    eval {
        my $val = $copy_watch->NEXTKEY('czxid');
    };
    like($@, qr/invalid handle/,
         'watch NEXTKEY(): invalid watch handle');

    my @keys = keys(%{$watch});
    is(scalar(@keys), 3,
       'keys(): count of keys from watch handle');

    @keys = keys(%{$copy_watch});
    is(scalar(@keys), 3,
       'keys(): count of keys from copied dereferenced watch handle');

    is($attr->FIRSTKEY(), 'timeout',
       'watch FIRSTKEY(): retrieved first key using inner watch hash');

    is($attr->NEXTKEY('event'), 'state',
       'watch NEXTKEY(): retrieved last key using inner watch hash');

    is($attr->NEXTKEY('state'), undef,
       'NEXTKEY(): undef returned after last key using inner watch hash');

    ok(scalar(%{$watch}),
       'scalar(): true value returned for dereferenced watch handle');

    ok($watch->SCALAR(),
       'watch SCALAR(): true value returned');


    ## FETCH()

    eval {
        my $val = $copy_watch->FETCH('version');
    };
    like($@, qr/invalid handle/,
         'watch FETCH(): invalid watch handle');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        my $val = $watch->{'foo'};
        ok(!defined($val),
           'watch FETCH(): undef returned for invalid element');

        like($msg, qr/invalid element/,
             'watch FETCH(): invalid element');
    }

    is($watch->{'timeout'}, 60000,
       'watch FETCH(): default timeout');

    is($watch->{'event'}, 0,
       'watch FETCH(): default event');

    is($watch->{'state'}, 0,
       'watch FETCH(): default state');

    is($attr->FETCH('timeout'), 60000,
       'watch FETCH(): default timeout using inner watch hash');


    ## STORE()

    eval {
        my $val = $copy_watch->STORE('version', 'foo');
    };
    like($@, qr/invalid handle/,
         'watch STORE(): invalid watch handle');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $watch->{'foo'} = 'foo';
        like($msg, qr/invalid element/,
             'watch STORE(): invalid element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $watch->{'event'} = 'foo';
        like($msg, qr/read-only element: event/,
             'watch STORE(): read-only event element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $watch->{'state'} = 'foo';
        like($msg, qr/read-only element: state/,
             'watch STORE(): read-only state element');
    }

    $watch->{'timeout'} = 100;
    is($watch->{'timeout'}, 100,
       'watch STORE(): updated timeout');

    $attr->STORE('timeout', 200);
    is($watch->{'timeout'}, 200,
       'watch STORE(): updated timeout using inner hash');


    ## EXISTS()

    eval {
        my $val = $copy_watch->EXISTS('version');
    };
    like($@, qr/invalid handle/,
         'watch EXISTS(): invalid watch handle');

    ok(!exists($watch->{'foo'}),
       'exists(): invalid element of watch handle');

    ok(exists($watch->{'timeout'}),
       'exists(): timeout');

    ok(exists($watch->{'event'}),
       'exists(): event');

    ok(exists($watch->{'state'}),
       'exists(): state');

    ok($attr->EXISTS('timeout'),
       'watch EXISTS(): timeout using inner watch hash');


    ## DELETE(), CLEAR()

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        delete($watch->{'version'});
        like($msg,
             qr/deleting elements from hashes of class Net::ZooKeeper::Watch not supported/,
             'delete(): deleting watch hash elements not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $watch->DELETE({'version'});
        like($msg,
             qr/deleting elements from hashes of class Net::ZooKeeper::Watch not supported/,
             'watch DELETE(): deleting watch hash elements not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        %{$watch} = ();
        like($msg, qr/clearing hashes of class Net::ZooKeeper::Watch not supported/,
             'assign: clearing watch hashes not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $watch->CLEAR();
        like($msg, qr/clearing hashes of class Net::ZooKeeper::Watch not supported/,
             'watch CLEAR(): clearing watch hashes not supported');
    }
}

