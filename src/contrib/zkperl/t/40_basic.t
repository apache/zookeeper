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
use Test::More tests => 35;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


my $zkh = Net::ZooKeeper->new($hosts);
my $path;

SKIP: {
    my $ret = $zkh->exists($root_path) if (defined($zkh));

    skip 'no connection to ZooKeeper', 1 unless
        (defined($ret) and $ret);

    $path = $zkh->create($node_path, 'foo', 'acl' => ZOO_OPEN_ACL_UNSAFE);
    is($path, $node_path,
       'create(): created node');
}

SKIP: {
    skip 'no connection to ZooKeeper', 21 unless
        (defined($path) and $path eq $node_path);


    ## exists()

    my $ret = $zkh->exists($node_path);
    ok($ret,
       'exists(): checked node existence');

    $ret = $zkh->exists($node_path . '/NONE');
    ok((!$ret and $zkh->get_error() == ZNONODE and $! eq ''),
       'exists(): checked node non-existence');

    my $stat = $zkh->stat();

    $ret = $zkh->exists($node_path, 'stat' => $stat);
    ok(($ret and $stat->{'data_len'} == 3),
       'exists(): checked node existence with stat handle');


    ## get()

    my $node = $zkh->get($node_path);
    is($node, 'foo',
       'get(): retrieved node value');

    $node = $zkh->get($node_path . '/NONE');
    ok((!defined($node) and $zkh->get_error() == ZNONODE and $! eq ''),
       'get(): undef returned for non-extant node');

    $node = $zkh->get($node_path, 'data_read_len', 2);
    is($node, 'fo',
       'get(): retrieved truncated node value');

    $node = $zkh->get($node_path, 'data_read_len' => 0);
    is($node, '',
       'get(): retrieved zero-length node value');

    $node = $zkh->get($node_path, 'stat' => $stat);
    ok(($node eq 'foo' and $stat->{'data_len'} == 3),
       'get(): retrieved node value with stat handle');


    ## set()

    $ret = $zkh->set($node_path, 'foo');
    ok($ret,
       'set(): set node value');

    SKIP: {
        my $ret = $zkh->exists($node_path, 'stat' => $stat);

        skip 'invalid node data', 2 unless
            ($ret and $stat->{'version'} == 1);

        $ret = $zkh->set($node_path, 'foo', 'version' => $stat->{'version'});
        ok($ret,
           'set(): set node value with matching version');

        $ret = $zkh->set($node_path, 'foo', 'version' => $stat->{'version'});
        ok((!$ret and $zkh->get_error() == ZBADVERSION and $! eq ''),
           'set(): node value unchanged if non-matching version');
    }

    $ret = $zkh->set($node_path, 'foobaz', 'stat' => $stat);
    ok(($ret and $stat->{'data_len'} == 6),
       'set(): retrieved node value with stat handle');


    ## create(), delete()

    $path = $zkh->create($node_path, 'foo', 'acl' => ZOO_OPEN_ACL_UNSAFE);
    ok((!defined($path) and $zkh->get_error() == ZNODEEXISTS and $! eq ''),
       'create(): undef when attempting to create extant node');

    $ret = $zkh->delete($node_path . '/NONE');
    ok((!$ret and $zkh->get_error() == ZNONODE and $! eq ''),
       'delete(): no deletion of non-extant node');

    $ret = $zkh->delete($node_path);
    ok($ret,
       'delete(): deleted node');

    my $path_read_len = length($node_path) - 2;

    $path = $zkh->create($node_path, 'foo',
                         'path_read_len' => $path_read_len,
                         'acl' => ZOO_OPEN_ACL_UNSAFE);
    is($path, substr($node_path, 0, -2),
       'create(): created node with small return path buffer');

    $path = $zkh->create("$node_path/s", 'foo',
                         'flags' => ZOO_SEQUENCE,
                         'acl' => ZOO_OPEN_ACL_UNSAFE);
    like($path, qr/^$node_path\/s[0-9]+$/,
       'create(): created sequential node');

    SKIP: {
        my $ret = $zkh->exists($path, 'stat' => $stat);

        unless ($ret and $stat->{'version'} == 0) {
            my $ret = $zkh->delete($path);
            diag(sprintf('unable to delete node %s: %d, %s',
                         $path, $zkh->get_error(), $!)) unless ($ret);

            skip 'invalid node data', 2;
        }

        $ret = $zkh->delete($path, 'version' => ($stat->{'version'} + 1));
        ok((!$ret and $zkh->get_error() == ZBADVERSION and $! eq ''),
           'delete(): node not deleted if non-matching version');

        $ret = $zkh->delete($path, 'version' => $stat->{'version'});
        ok($ret,
           'delete(): deleted sequential node with matching version');
    }

    $path = $zkh->create("$node_path/e", 'foo',
                         'flags' => ZOO_EPHEMERAL,
                         'acl' => ZOO_OPEN_ACL_UNSAFE);
    is($path, "$node_path/e",
       'create(): created ephemeral node');

    $path = $zkh->create("$node_path/es", 'foo',
                         'flags' => (ZOO_SEQUENCE | ZOO_EPHEMERAL),
                         'acl' => ZOO_OPEN_ACL_UNSAFE);
    like($path, qr/^$node_path\/es[0-9]+$/,
       'create(): created ephemeral sequential node');

    undef $zkh;
}

$zkh = Net::ZooKeeper->new($hosts);

SKIP: {
    my $ret = $zkh->exists($node_path) if (defined($zkh));

    skip 'no connection to ZooKeeper', 12 unless
        (defined($ret) and $ret);

    $ret = $zkh->exists("$node_path/e");
    ok((!$ret and $zkh->get_error() == ZNONODE and $! eq ''),
       'exists(): checked ephemeral node non-extant after reconnection');

    $ret = $zkh->exists($path);
    ok((!$ret and $zkh->get_error() == ZNONODE and $! eq ''),
       'exists(): checked ephemeral sequential node non-extant ' .
       'after reconnection');


    ## get_children()

    my @child_paths = ('abc');
    @child_paths = $zkh->get_children($node_path);
    ok((@child_paths == 0 and $zkh->get_error() == ZOK),
       'get_children(): retrieved empty list of child nodes');

    my $num_children = $zkh->get_children($node_path);
    ok((defined($num_children) and $num_children == 0),
       'get_children(): retrieved zero count of child nodes');

    @child_paths = $zkh->get_children($node_path . '/NONE');
    ok((@child_paths == 0 and $zkh->get_error() == ZNONODE and $! eq ''),
       'get_children(): empty list returned for non-extant node');

    $num_children = $zkh->get_children($node_path . '/NONE');
    ok((!defined($num_children) and $zkh->get_error() == ZNONODE and $! eq ''),
       'get_children(): undef returned for non-extant node');

    SKIP: {
        my $path = $zkh->create("$node_path/c1", 'foo',
                                'acl' => ZOO_OPEN_ACL_UNSAFE);

        skip 'no connection to ZooKeeper', 6 unless
            (defined($path) and $path eq "$node_path/c1");

        my @child_paths = ('abc');
        @child_paths = $zkh->get_children($node_path);
        ok((@child_paths == 1 and $child_paths[0] eq 'c1'),
           'get_children(): retrieved list of single child node');

        my $num_children = $zkh->get_children($node_path);
        ok((defined($num_children) and $num_children == 1),
           'get_children(): retrieved count of single child node');

        SKIP: {
            my $path = $zkh->create("$node_path/c2", 'foo',
                                    'acl' => ZOO_OPEN_ACL_UNSAFE);

            skip 'no connection to ZooKeeper', 2 unless
                (defined($path) and $path eq "$node_path/c2");

            my @child_paths = ('abc');
            @child_paths = $zkh->get_children($node_path);
            ok((@child_paths == 2 and $child_paths[0] eq 'c1' and
                $child_paths[1] eq 'c2'),
               'get_children(): retrieved list of two child nodes');

            my $num_children = $zkh->get_children($node_path);
            ok((defined($num_children) and $num_children == 2),
               'get_children(): retrieved count of two child nodes');

            my $ret = $zkh->delete("$node_path/c2");
            diag(sprintf('unable to delete node %s: %d, %s',
                         "$node_path/c2", $zkh->get_error(), $!)) unless
                ($ret);
        }

        @child_paths = ('abc');
        @child_paths = $zkh->get_children($node_path);
        ok((@child_paths == 1 and $child_paths[0] eq 'c1'),
           'get_children(): retrieved list of single child node');

        $num_children = $zkh->get_children($node_path);
        ok((defined($num_children) and $num_children == 1),
           'get_children(): retrieved count of single child node');

        my $ret = $zkh->delete("$node_path/c1");
        diag(sprintf('unable to delete node %s: %d, %s',
                     "$node_path/c1", $zkh->get_error(), $!)) unless ($ret);
    }


    ## cleanup

    $ret = $zkh->delete($node_path);
    diag(sprintf('unable to delete node %s: %d, %s',
                 $node_path, $zkh->get_error(), $!)) unless ($ret);
}

