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
use Test::More tests => 47;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    skip 'no valid handle', 15 unless (defined($zkh));

    my $stat = $zkh->stat();
    my $watch = $zkh->watch();


    ## DESTROY() on reblessed handle

    bless($zkh, 'My::ZooKeeper');
    is(ref($zkh), 'My::ZooKeeper',
       'bless(): reblessed handle');

    eval {
        $zkh->EXISTS();
    };
    like($@, qr/Can't locate object method "EXISTS" via package "My::ZooKeeper"/,
         'EXISTS(): not defined on reblessed handle');

    my $attr = tied(%{$zkh});

    my $ret = $attr->DESTROY();
    ok($ret,
       'DESTROY(): destroyed inner hash of reblessed handle');

    $ret = $attr->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on destroyed inner hash of reblessed handle');

    undef $zkh;
    ok(!defined($zkh),
       'undef: released reblessed handle');


    ## DESTROY() on reblessed stat handle

    bless($stat, 'My::ZooKeeper::Stat');
    is(ref($stat), 'My::ZooKeeper::Stat',
       'bless(): reblessed stat handle');

    eval {
        $stat->EXISTS(1);
    };
    like($@, qr/Can't locate object method "EXISTS" via package "My::ZooKeeper::Stat"/,
         'stat EXISTS(): not defined on reblessed stat handle');

    $attr = tied(%{$stat});

    $ret = $attr->DESTROY();
    ok($ret,
       'stat DESTROY(): destroyed inner hash of reblessed stat handle');

    $ret = $attr->DESTROY();
    ok(!$ret,
       'stat DESTROY(): no action on destroyed inner hash of ' .
       'reblessed stat handle');

    undef $stat;
    ok(!defined($stat),
       'undef: released reblessed stat handle');


    ## DESTROY() on reblessed watch handle

    bless($watch, 'My::ZooKeeper::Watch');
    is(ref($watch), 'My::ZooKeeper::Watch',
       'bless(): reblessed watch handle');

    eval {
        $watch->EXISTS(1);
    };
    like($@, qr/Can't locate object method "EXISTS" via package "My::ZooKeeper::Watch"/,
         'watch EXISTS(): not defined on reblessed watch handle');

    $attr = tied(%{$watch});

    $ret = $attr->DESTROY();
    ok($ret,
       'watch DESTROY(): destroyed inner hash of reblessed watch handle');

    $ret = $attr->DESTROY();
    ok(!$ret,
       'watch DESTROY(): no action on destroyed inner hash of ' .
       'reblessed watch handle');

    undef $watch;
    ok(!defined($watch),
       'undef: released reblessed watch handle');
}

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    skip 'no valid handle', 9 unless (defined($zkh));

    my $stat = $zkh->stat();
    my $watch = $zkh->watch();


    ## UNTIE() on reblessed handle

    bless($zkh, 'My::ZooKeeper');
    is(ref($zkh), 'My::ZooKeeper',
       'bless(): reblessed handle');

    eval {
        untie(%{$zkh});
    };
    like($@, qr/untying hashes of class Net::ZooKeeper not supported/,
         'untie(): untying hashes from reblessed handle not supported');

    my $attr = tied(%{$zkh});

    eval {
        $attr->UNTIE(0);
    };
    like($@, qr/untying hashes of class Net::ZooKeeper not supported/,
         'UNTIE(): untying hashes from reblessed handle not supported');


    ## UNTIE() on reblessed stat handle

    bless($stat, 'My::ZooKeeper::Stat');
    is(ref($stat), 'My::ZooKeeper::Stat',
       'bless(): reblessed stat handle');

    eval {
        untie(%{$stat});
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Stat not supported/,
         'untie(): untying hashes from reblessed stat handle not supported');

    $attr = tied(%{$stat});

    eval {
        $attr->UNTIE(0);
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Stat not supported/,
         'stat UNTIE(): untying hashes from reblessed stat handle ' .
         'not supported');


    ## UNTIE() on reblessed watch handle

    bless($watch, 'My::ZooKeeper::Watch');
    is(ref($watch), 'My::ZooKeeper::Watch',
       'bless(): reblessed watch handle');

    eval {
        untie(%{$watch});
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Watch not supported/,
         'untie(): untying hashes from reblessed watch handle not supported');

    $attr = tied(%{$watch});

    eval {
        $attr->UNTIE(0);
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Watch not supported/,
         'watch UNTIE(): untying hashes from reblessed watch handle ' .
         'not supported');
}


package Net::ZooKeeper::Test;

use Net::ZooKeeper qw(:acls);

our @ISA = qw(Net::ZooKeeper);

sub create
{
    my($self, $path, $buf) = @_;

    return $self->SUPER::create($path, $buf,
                                'path_read_len' => length($path),
                                'acl' => ZOO_OPEN_ACL_UNSAFE);
}

sub get_first_child
{
    my($self, $path) = @_;

    my @child_paths = $self->get_children($path);

    if (@child_paths > 0) {
        return $path . (($path =~ /\/$/) ? '' : '/') . $child_paths[0];
    }

    return undef;
}

sub stat
{
    my $self = shift;

    my $stat = $self->SUPER::stat();

    return bless($stat, 'Net::ZooKeeper::Test::Stat');
}


sub watch
{
    my $self = shift;

    my $watch = $self->SUPER::watch();

    return bless($watch, 'Net::ZooKeeper::Test::Watch');
}


package Net::ZooKeeper::Test::Stat;

our @ISA = qw(Net::ZooKeeper::Stat);

sub get_ctime
{
    my $self = shift;

    return $self->{'ctime'};
}


package Net::ZooKeeper::Test::Watch;

our @ISA = qw(Net::ZooKeeper::Watch);

sub get_timeout
{
    my $self = shift;

    return $self->{'timeout'};
}


package main;

my $sub_zkh = Net::ZooKeeper::Test->new($hosts);
isa_ok($sub_zkh, 'Net::ZooKeeper::Test',
       'new(): created subclassed handle');

SKIP: {
    skip 'no valid subclassed handle', 21 unless (defined($sub_zkh));

    is($sub_zkh->{'data_read_len'}, 1023,
       'FETCH(): default data read length using subclassed handle');

    my $path;

    SKIP: {
        my $ret = $sub_zkh->exists($root_path);

        skip 'no connection to ZooKeeper', 1 unless
            (defined($ret) and $ret);

        $path = $sub_zkh->create($node_path, 'foo',
                                 'acl' => ZOO_OPEN_ACL_UNSAFE);
        is($path, $node_path,
           'create(): created node with subclassed handle');
    }

    SKIP: {
        skip 'no connection to ZooKeeper', 1 unless
            (defined($path) and $path eq $node_path);

        my $child_path = $sub_zkh->get_first_child($root_path);
        is($child_path, $node_path,
           'get_first_child(): retrieved first child with subclassed handle');
    }

    my $sub_stat = $sub_zkh->stat();
    isa_ok($sub_stat, 'Net::ZooKeeper::Test::Stat',
           'stat(): created subclassed stat handle');

    SKIP: {
        skip 'no valid subclassed stat handle', 6 unless
            (defined($sub_stat));

        is($sub_stat->{'ctime'}, 0,
           'stat FETCH(): default ctime using subclassed stat handle');

        SKIP: {
            my $ret = $sub_zkh->exists($node_path, 'stat' => $sub_stat) if
                (defined($path) and $path eq $node_path);

            skip 'no connection to ZooKeeper', 2 unless
                (defined($ret) and $ret);

            my $ctime = $sub_stat->get_ctime();
            ok($ctime > 0,
               'get_ctime(): retrieved ctime with subclassed stat handle');

            is($sub_stat->{'ctime'}, $ctime,
               'stat FETCH(): ctime using subclassed stat handle');
        }

        my $ret = $sub_stat->DESTROY();
        ok($ret,
           'stat DESTROY(): destroyed subclassed stat handle');

        $ret = $sub_stat->DESTROY();
        ok(!$ret,
           'stat DESTROY(): no action on destroyed subclassed stat handle');

        undef $sub_stat;
        ok(!defined($sub_stat),
           'undef: released subclassed stat handle');
    }

    my $sub_watch = $sub_zkh->watch();
    isa_ok($sub_watch, 'Net::ZooKeeper::Test::Watch',
           'watch(): created subclassed watch handle');

    SKIP: {
        skip 'no valid subclassed watch handle', 6 unless
            (defined($sub_watch));

        SKIP: {
            my $ret = $sub_zkh->exists($root_path, 'watch' => $sub_watch);

            skip 'no connection to ZooKeeper', 3 unless
                (defined($ret) and $ret);

            $sub_watch->{'timeout'} = 50;

            is($sub_watch->get_timeout(), 50,
               'get_timeout(): retrieved timeout with subclassed ' .
               'watch handle');

            is($sub_watch->{'timeout'}, 50,
               'watch FETCH(): timeout using subclassed stat handle');

            $ret = $sub_watch->wait();
            ok(!$ret,
               'wait(): watch after checking node existence timed out with ' .
               'subclassed watch handle');
        }

        my $ret = $sub_watch->DESTROY();
        ok($ret,
           'watch DESTROY(): destroyed subclassed watch handle');

        $ret = $sub_watch->DESTROY();
        ok(!$ret,
           'watch DESTROY(): no action on destroyed subclassed watch handle');

        undef $sub_watch;
        ok(!defined($sub_watch),
           'undef: released subclassed watch handle');
    }

    SKIP: {
        skip 'no connection to ZooKeeper', 1 unless
            (defined($path) and $path eq $node_path);

        my $ret = $sub_zkh->delete($node_path);
        ok($ret,
           'delete(): deleted node with subclassed handle');
    }

    my $ret = $sub_zkh->DESTROY();
    ok($ret,
       'DESTROY(): destroyed subclassed handle');

    $ret = $sub_zkh->DESTROY();
    ok(!$ret,
       'DESTROY(): no action on destroyed subclassed handle');

    undef $sub_zkh;
    ok(!defined($sub_zkh),
       'undef: released subclassed handle');
}

