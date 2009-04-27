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
use Test::More tests => 66;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);
    my $stat = $zkh->stat() if (defined($zkh));

    skip 'no valid stat handle', 4 unless (defined($stat));


    ## DESTROY()

    my $attr = tied(%{$stat});

    my $ret = $attr->DESTROY();
    ok($ret,
       'stat DESTROY(): destroyed inner stat hash');

    $ret = $attr->DESTROY();
    ok(!$ret,
       'stat DESTROY(): no action on destroyed inner stat hash');

    $ret = $stat->DESTROY();
    ok(!$ret,
       'stat DESTROY(): no action on stat handle with destroyed inner hash');

    undef $stat;
    ok(!defined($stat),
       'undef: released stat handle with destroyed inner hash');
}

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);
    my $stat = $zkh->stat() if (defined($zkh));

    skip 'no valid stat handle', 61 unless (defined($stat));


    ## TIEHASH(), UNTIE()

    eval {
        tie(%{$stat}, 'Net::ZooKeeper::Stat');
    };
    like($@, qr/tying hashes of class Net::ZooKeeper::Stat not supported/,
         'tie(): tying stat hashes not supported');

    eval {
        Net::ZooKeeper::Stat::TIEHASH('Net::ZooKeeper::Stat');
    };
    like($@, qr/tying hashes of class Net::ZooKeeper::Stat not supported/,
         'stat TIEHASH(): tying stat hashes not supported');

    eval {
        untie(%{$stat});
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Stat not supported/,
         'untie(): untying stat hashes not supported');

    my $attr = tied(%{$stat});

    eval {
        $attr->UNTIE(0);
    };
    like($@, qr/untying hashes of class Net::ZooKeeper::Stat not supported/,
         'stat UNTIE(): untying stat hashes not supported');


    ## FIRSTKEY(), NEXTKEY(), SCALAR()

    my $copy_stat;
    {
        my %copy_stat = %{$stat};
        $copy_stat = \%copy_stat;
    }
    bless($copy_stat, 'Net::ZooKeeper::Stat');
    is(ref($copy_stat), 'Net::ZooKeeper::Stat',
       'stat FIRSTKEY(), NEXTKEY(): copied dereferenced stat handle');

    eval {
        my $val = $copy_stat->FIRSTKEY();
    };
    like($@, qr/invalid handle/,
         'stat FETCHKEY(): invalid stat handle');

    eval {
        my $val = $copy_stat->NEXTKEY('czxid');
    };
    like($@, qr/invalid handle/,
         'stat NEXTKEY(): invalid stat handle');

    my @keys = keys(%{$stat});
    is(scalar(@keys), 11,
       'keys(): count of keys from stat handle');

    @keys = keys(%{$copy_stat});
    is(scalar(@keys), 11,
       'keys(): count of keys from copied dereferenced stat handle');

    is($attr->FIRSTKEY(), 'czxid',
       'stat FIRSTKEY(): retrieved first key using inner stat hash');

    is($attr->NEXTKEY('num_children'), 'children_zxid',
       'stat NEXTKEY(): retrieved last key using inner stat hash');

    is($attr->NEXTKEY('children_zxid'), undef,
       'NEXTKEY(): undef returned after last key using inner stat hash');

    ok(scalar(%{$stat}),
       'scalar(): true value returned for dereferenced stat handle');

    ok($stat->SCALAR(),
       'stat SCALAR(): true value returned');


    ## FETCH()

    eval {
        my $val = $copy_stat->FETCH('version');
    };
    like($@, qr/invalid handle/,
         'stat FETCH(): invalid stat handle');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        my $val = $stat->{'foo'};
        ok(!defined($val),
           'stat FETCH(): undef returned for invalid element');

        like($msg, qr/invalid element/,
             'stat FETCH(): invalid element');
    }

    is($stat->{'czxid'}, 0,
       'stat FETCH(): default node creation ZooKeeper transaction ID');

    is($stat->{'mzxid'}, 0,
       'stat FETCH(): default data last-modified ZooKeeper transaction ID');

    is($stat->{'ctime'}, 0,
       'stat FETCH(): default node creation time');

    is($stat->{'mtime'}, 0,
       'stat FETCH(): default data last-modified time');

    is($stat->{'version'}, 0,
       'stat FETCH(): default data version');

    is($stat->{'children_version'}, 0,
       'stat FETCH(): default child node list version');

    is($stat->{'acl_version'}, 0,
       'stat FETCH(): default ACL version');

    is($stat->{'ephemeral_owner'}, 0,
       'stat FETCH(): ephemeral node owner session ID');

    is($stat->{'data_len'}, 0,
       'stat FETCH(): default data length');

    is($stat->{'num_children'}, 0,
       'stat FETCH(): default child node list length');

    is($stat->{'children_zxid'}, 0,
       'stat FETCH(): default child node list last-modified ' .
       'ZooKeeper transaction ID');

    is($attr->FETCH('version'), 0,
       'stat FETCH(): default data version using inner stat hash');


    ## STORE()

    eval {
        my $val = $copy_stat->STORE('version', 'foo');
    };
    like($@, qr/invalid handle/,
         'stat STORE(): invalid stat handle');

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'foo'} = 'foo';
        like($msg, qr/invalid element/,
             'stat STORE(): invalid element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'czxid'} = 'foo';
        like($msg, qr/read-only element: czxid/,
             'stat STORE(): read-only node creation ' .
             'ZooKeeper transaction ID element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'mzxid'} = 'foo';
        like($msg, qr/read-only element: mzxid/,
             'stat STORE(): read-only data last-modified ' .
             'ZooKeeper transaction ID element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'ctime'} = 'foo';
        like($msg, qr/read-only element: ctime/,
             'stat STORE(): read-only node creation time element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'mtime'} = 'foo';
        like($msg, qr/read-only element: mtime/,
             'stat STORE(): read-only data last-modified time element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'version'} = 'foo';
        like($msg, qr/read-only element: version/,
             'stat STORE(): read-only data version element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'children_version'} = 'foo';
        like($msg, qr/read-only element: children_version/,
             'stat STORE(): read-only child node list version element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'acl_version'} = 'foo';
        like($msg, qr/read-only element: acl_version/,
             'stat STORE(): read-only ACL version element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'ephemeral_owner'} = 'foo';
        like($msg, qr/read-only element: ephemeral_owner/,
             'stat STORE(): read-only ephemeral node owner ' .
             'session ID element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'data_len'} = 'foo';
        like($msg, qr/read-only element: data_len/,
             'stat STORE(): read-only data length element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'num_children'} = 'foo';
        like($msg, qr/read-only element: num_children/,
             'stat STORE(): read-only child node list length element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->{'children_zxid'} = 'foo';
        like($msg, qr/read-only element: children_zxid/,
             'stat STORE(): read-only child node list last-modified ' .
             'ZooKeeper transaction ID element');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $attr->STORE('version', 'foo');
        like($msg, qr/read-only element: version/,
             'stat STORE(): read-only data version element using ' .
             'inner stat hash');
    }


    ## EXISTS()

    eval {
        my $val = $copy_stat->EXISTS('version');
    };
    like($@, qr/invalid handle/,
         'stat EXISTS(): invalid stat handle');

    ok(!exists($stat->{'foo'}),
       'exists(): invalid element of stat handle');

    ok(exists($stat->{'czxid'}),
       'exists(): node creation ZooKeeper transaction ID');

    ok(exists($stat->{'mzxid'}),
       'exists(): data last-modified ZooKeeper transaction ID');

    ok(exists($stat->{'ctime'}),
       'exists(): node creation time');

    ok(exists($stat->{'mtime'}),
       'exists(): data last-modified time');

    ok(exists($stat->{'version'}),
       'exists(): data version');

    ok(exists($stat->{'children_version'}),
       'exists(): child node list version');

    ok(exists($stat->{'acl_version'}),
       'exists(): ACL version');

    ok(exists($stat->{'ephemeral_owner'}),
       'exists(): ephemeral node owner session ID');

    ok(exists($stat->{'data_len'}),
       'exists(): data length');

    ok(exists($stat->{'num_children'}),
       'exists(): child node list length');

    ok(exists($stat->{'children_zxid'}),
       'exists(): child node list last-modified ZooKeeper transaction ID');

    ok($attr->EXISTS('version'),
       'stat EXISTS(): data version using inner stat hash');


    ## DELETE(), CLEAR()

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        delete($stat->{'version'});
        like($msg,
             qr/deleting elements from hashes of class Net::ZooKeeper::Stat not supported/,
             'delete(): deleting stat hash elements not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->DELETE({'version'});
        like($msg,
             qr/deleting elements from hashes of class Net::ZooKeeper::Stat not supported/,
             'stat DELETE(): deleting stat hash elements not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        %{$stat} = ();
        like($msg, qr/clearing hashes of class Net::ZooKeeper::Stat not supported/,
             'assign: clearing stat hashes not supported');
    }

    {
        my $msg;

        $SIG{'__WARN__'} = sub { $msg = $_[0]; };

        $stat->CLEAR();
        like($msg, qr/clearing hashes of class Net::ZooKeeper::Stat not supported/,
             'stat CLEAR(): clearing stat hashes not supported');
    }
}

