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
use Test::More tests => 40;
use Storable qw(dclone);

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);

my($username, $password, $digest) = zk_acl_test_setup();


SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    my $path = $zkh->create($node_path, 'foo',
                            'acl' => ZOO_OPEN_ACL_UNSAFE) if (defined($zkh));

    skip 'no connection to ZooKeeper', 36 unless
        (defined($path) and $path eq $node_path);


    ## _zk_acl_constant()

    my $no_read_acl = ZOO_OPEN_ACL_UNSAFE;
    ok((ref($no_read_acl) eq 'ARRAY' and
        @{$no_read_acl} == 1 and
        ref($no_read_acl->[0]) eq 'HASH' and
        keys(%{$no_read_acl->[0]}) == 3 and
        $no_read_acl->[0]->{'perms'} == ZOO_PERM_ALL),
       '_zk_acl_constant(): returned default ACL');

    my $zoo_read_acl_unsafe = ZOO_READ_ACL_UNSAFE;
    ok((ref($zoo_read_acl_unsafe) eq 'ARRAY' and
        @{$zoo_read_acl_unsafe} == 1 and
        ref($zoo_read_acl_unsafe->[0]) eq 'HASH' and
        keys(%{$zoo_read_acl_unsafe->[0]}) == 3 and
        $zoo_read_acl_unsafe->[0]->{'perms'} == ZOO_PERM_READ),
       '_zk_acl_constant(): returned good ACL');

    my $zoo_creator_all_acl = ZOO_CREATOR_ALL_ACL;
    ok((ref($zoo_creator_all_acl) eq 'ARRAY' and
        @{$zoo_creator_all_acl} == 1 and
        ref($zoo_creator_all_acl->[0]) eq 'HASH' and
        keys(%{$zoo_creator_all_acl->[0]}) == 3 and
        $zoo_creator_all_acl->[0]->{'perms'} == ZOO_PERM_ALL),
       '_zk_acl_constant(): returned good ACL');

    $no_read_acl->[0]->{'perms'} &= ~ZOO_PERM_READ;
    is($no_read_acl->[0]->{'perms'}, ((ZOO_PERM_ALL) & ~ZOO_PERM_READ),
       'assign: altered default ACL');

    is(ZOO_OPEN_ACL_UNSAFE->[0]->{'perms'}, ZOO_PERM_ALL,
       '_zk_acl_constant(): returned unaltered default ACL');

    my $copy_no_read_acl = $no_read_acl;
    is_deeply($copy_no_read_acl, $no_read_acl,
              'assign: copied default ACL');

    undef $no_read_acl;
    ok(!defined($no_read_acl),
       'undef: released original default ACL');

    is($copy_no_read_acl->[0]->{'perms'}, ((ZOO_PERM_ALL) & ~ZOO_PERM_READ),
       'undef: no change to copied default ACL');

    $no_read_acl = $copy_no_read_acl;
    is_deeply($no_read_acl, $copy_no_read_acl,
              'assign: re-copied default ACL');


    ## create()

    my $acl_node_path = "$node_path/a1";

    $path = $zkh->create($acl_node_path, 'foo', 'acl' => $no_read_acl);
    is($path, $acl_node_path,
       'create(): created node with no-read ACL');

    my $node = $zkh->get($acl_node_path);

    my $skip_acl;
    if (defined($node) and $node eq 'foo') {
        $skip_acl = 1;
    }
    elsif(!defined($node) and $zkh->get_error() == ZNOAUTH) {
        $skip_acl = 0;
    }
    else {
        $skip_acl = -1;
        diag(sprintf('unable to get node with no-read ACL %s: %d, %s',
                     $acl_node_path, $zkh->get_error(), $!));
    }

    my $ret = $zkh->delete($acl_node_path);
    diag(sprintf('unable to delete node with no-read ACL %s: %d, %s',
                 $acl_node_path, $zkh->get_error(), $!)) unless ($ret);

    my $digest_acl = [
        {
            'perms'  => ZOO_PERM_READ,
            'scheme' => 'world',
            'id'     => 'anyone'
        },
        {
            'perms'  => (ZOO_PERM_WRITE | ZOO_PERM_ADMIN),
            'scheme' => 'digest',
            'id'     => "$username:$digest"
        }
    ];

    $path = $zkh->create($acl_node_path, 'foo', 'acl' => $digest_acl);
    is($path, $acl_node_path,
       'create(): created node with digest auth ACL');

    SKIP: {
        skip 'ZooKeeper skipping ACLs', 1 unless (!$skip_acl);

        my $acl_node_path = "$node_path/a2";

        my $path = $zkh->create($acl_node_path, 'foo', 'acl' => [
            {
                'perms'  => ZOO_PERM_WRITE,
                'scheme' => 'foo',
                'id'     => 'bar'
            }
        ]);
        ok((!defined($path) and $zkh->get_error() == ZINVALIDACL and $! eq ''),
           'create(): undef when attempting to create node with invalid ACL');
    }


    ## get_acl()

    my @acl = ('abc');
    @acl = $zkh->get_acl($node_path . '/NONE');
    ok((@acl == 0 and $zkh->get_error() == ZNONODE and $! eq ''),
       'get_acl(): empty list returned for non-extant node');

    $num_acl_entries = $zkh->get_acl($node_path . '/NONE');
    ok((!defined($num_acl_entries) and $zkh->get_error() == ZNONODE and
        $! eq ''),
       'get_acl(): undef returned for non-extant node');

    # The test is not running as ADMIN, which means that the server
    # returns "redacted" ACLs (see ZOOKEEPER-1392 and OpCode.getACL in
    # FinalRequestProcessor).  We must do the same for the comparison
    # to succeed.
    my $redacted_digest_acl = dclone($digest_acl);
    $redacted_digest_acl->[1]->{id} =~ s/:.*/:x/;

    @acl = ('abc');
    @acl = $zkh->get_acl($acl_node_path);
    is_deeply(\@acl, $redacted_digest_acl,
              'get_acl(): retrieved digest ACL');

    my $stat = $zkh->stat();

    @acl = ('abc');
    @acl = $zkh->get_acl($node_path, 'stat' => $stat);
    is_deeply(\@acl, ZOO_OPEN_ACL_UNSAFE,
              'get_acl(): retrieved ACL');

    is($stat->{'data_len'}, 3,
       'get_acl(): retrieved ACL with stat handle');

    SKIP: {
        skip 'ZooKeeper not skipping ACLs', 3 unless ($skip_acl > 0);

        my $acl_node_path = "$node_path/a2";

        my $path = $zkh->create($acl_node_path, 'foo', 'acl' => []);
        is($path, $acl_node_path,
           'create(): created node with empty ACL');

        my @acl = ('abc');
        @acl = $zkh->get_acl($acl_node_path);
        ok((@acl == 0 and $zkh->get_error() == ZOK),
           'get_acl(): retrieved empty ACL');

        my $num_acl_entries = $zkh->get_acl($acl_node_path);
        ok((defined($num_acl_entries) and $num_acl_entries == 0),
           'get_acl(): retrieved zero count of ACL entries');

        my $ret = $zkh->delete($acl_node_path);
        diag(sprintf('unable to delete node with empty ACL %s: %d, %s',
                     $acl_node_path, $zkh->get_error(), $!)) unless ($ret);
    }


    ## set_acl()

    SKIP: {
        skip 'ZooKeeper skipping ACLs', 2 unless (!$skip_acl);

        my $ret = $zkh->set_acl($acl_node_path, [
            {
                'perms'  => ZOO_PERM_CREATE,
                'scheme' => 'foo',
                'id'     => 'bar'
            }
        ]);
        ok((!$ret and $zkh->get_error() == ZINVALIDACL and $! eq ''),
           'set_acl(): invalid ACL');

        push @{$digest_acl}, {
            'perms'  => (ZOO_PERM_CREATE | ZOO_PERM_DELETE),
            'scheme' => 'ip',
            'id'     => '0.0.0.0'
        };

        $ret = $zkh->set_acl($acl_node_path, $digest_acl);
        ok((!$ret and $zkh->get_error() == ZNOAUTH and $! eq ''),
           'set_acl(): ACL unchanged if no auth');
    }


    ## add_auth(), set_acl()

    $ret = $zkh->add_auth('digest', '');
    ok($ret,
       'add_auth(): empty digest cert');

    SKIP: {
        skip 'ZooKeeper skipping ACLs', 1 unless (!$skip_acl);

        my $ret = $zkh->set($acl_node_path, 'foo');
        ok((!$ret and $zkh->get_error() == ZNOAUTH and $! eq ''),
           'set(): node value unchanged if no auth');
    }

    $ret = $zkh->add_auth('digest', "$username:$password");
    ok($ret,
       'add_auth(): valid digest cert');

    SKIP: {
        skip 'ZooKeeper skipping ACLs', 13 unless (!$skip_acl);

        my $ret = $zkh->set($acl_node_path, 'baz');
        ok($ret,
           'set(): set node value with auth');

        my $node = $zkh->get($acl_node_path);
        is($node, 'baz',
           'get(): retrieved node value with auth');

        $ret = $zkh->set_acl($acl_node_path, $digest_acl);
        ok($ret,
           'set_acl(): set digest ACL with auth');

        my $stat = $zkh->stat();

        my @acl = ('abc');
        @acl = $zkh->get_acl($acl_node_path, 'stat' => $stat);
        is_deeply(\@acl, $digest_acl,
                  'get_acl(): retrieved digest ACL with auth');

        is($stat->{'data_len'}, 3,
           'get_acl(): retrieved digest ACL with stat handle and auth');

        SKIP: {
            skip 'invalid node data', 2 unless ($stat->{'version'} == 1);

            my $ret = $zkh->set_acl($acl_node_path, $digest_acl,
                                    'version' => $stat->{'version'});
            ok($ret,
               'set_acl(): set digest ACL with matching version with auth');

            $ret = $zkh->set_acl($acl_node_path, $digest_acl,
                                 'version' => $stat->{'version'});
            ok((!$ret and $zkh->get_error() == ZBADVERSION and $! eq ''),
               'set_acl(): ACL unchanged if non-matching version');
        }

        my $child_node_path = "$acl_node_path/c1";

        my $path = $zkh->create($child_node_path, 'foo',
                                'acl' => ZOO_OPEN_ACL_UNSAFE);
        ok((!defined($path) and $zkh->get_error() == ZNOAUTH and $! eq ''),
           'create(): undef when attempting to create node if no auth');

        $digest_acl->[1]->{'perms'} |= ZOO_PERM_CREATE;
        $digest_acl->[2]->{'perms'} &= ~ZOO_PERM_CREATE;

        $ret = $zkh->set_acl($acl_node_path, $digest_acl);
        ok($ret,
           'set_acl(): set changed digest ACL with auth');

        $path = $zkh->create($child_node_path, 'foo',
                             'acl' => ZOO_OPEN_ACL_UNSAFE);
        is($path, $child_node_path,
           'create(): created node with auth');

        $ret = $zkh->delete($child_node_path);
        ok((!$ret and $zkh->get_error() == ZNOAUTH and $! eq ''),
           'delete(): no deletion of node if no auth');

        $digest_acl->[1]->{'perms'} |= ZOO_PERM_DELETE;
        pop @{$digest_acl};

        $ret = $zkh->set_acl($acl_node_path, $digest_acl);
        ok($ret,
           'set_acl(): set reduced digest ACL with auth');

        $ret = $zkh->delete($child_node_path);
        ok($ret,
           'delete(): deleted node with auth');
    }


    ## cleanup

    $ret = $zkh->delete($acl_node_path);
    diag(sprintf('unable to delete node with digest auth ACL %s: %d, %s',
                 $acl_node_path, $zkh->get_error(), $!)) unless ($ret);

    $ret = $zkh->delete($node_path);
    diag(sprintf('unable to delete node %s: %d, %s',
                 $node_path, $zkh->get_error(), $!)) unless ($ret);
}

SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    my $ret = $zkh->exists($root_path) if (defined($zkh));

    skip 'no connection to ZooKeeper', 1 unless
        (defined($ret) and $ret);


    ## add_auth()

    $ret = $zkh->add_auth('foo', 'bar');
    my $err = $zkh->get_error();
    ok((!$ret and
        ($err == ZAUTHFAILED or
         $err == ZCONNECTIONLOSS or
         $err == ZSESSIONEXPIRED)
        and $! eq ''),
       'set_acl(): invalid scheme');
}

