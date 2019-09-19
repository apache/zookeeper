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
use Test::More tests => 7;
use JSON::PP qw(decode_json);

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);

my $sasl_options = $ENV{'ZK_TEST_SASL_OPTIONS'};
if (defined($sasl_options)) {
    $sasl_options = decode_json($sasl_options);
}

SKIP: {
    skip 'no sasl_options', 6 unless defined($sasl_options);

    my $zkh = Net::ZooKeeper->new($hosts,
        'sasl_options' => $sasl_options);

    my $path = $zkh->create($node_path, 'foo',
                            'acl' => ZOO_OPEN_ACL_UNSAFE) if (defined($zkh));

    skip 'no connection to ZooKeeper', 36 unless
        (defined($path) and $path eq $node_path);

    ## _zk_acl_constant()

    my $acl_node_path = "$node_path/a1";

    my $sasl_acl = [
        {
            'perms'  => ZOO_PERM_READ,
            'scheme' => 'world',
            'id'     => 'anyone'
        },
        {
            'perms'  => ZOO_PERM_ALL,
            'scheme' => 'sasl',
            'id'     => $sasl_options->{user}
        }
    ];

    $path = $zkh->create($acl_node_path, 'foo', 'acl' => $sasl_acl);
    is($path, $acl_node_path,
       'create(): created node with SASL ACL');


    ## get_acl()

    @acl = ('abc');
    @acl = $zkh->get_acl($acl_node_path);
    is_deeply(\@acl, $sasl_acl,
              'get_acl(): retrieved SASL ACL');

    SKIP: {
        my $zkh2 = Net::ZooKeeper->new($hosts);

        my $ret = $zkh->exists($root_path) if (defined($zkh));

        skip 'no connection to ZooKeeper', 1 unless
            (defined($ret) and $ret);

        my $node = $zkh2->get($acl_node_path);
        is($node, 'foo',
           'get(): retrieved node value with world ACL');

        $ret = $zkh2->set($acl_node_path, 'bar');
        ok((!$ret and $zkh2->get_error() == ZNOAUTH and $! eq ''),
           'set(): node value unchanged if no auth');
    }

    my $ret = $zkh->set($acl_node_path, 'bar');
    ok($ret,
       'set(): set node with SASL ACL');

    my $node = $zkh->get($acl_node_path);
    is($node, 'bar',
       'get(): retrieved new node value with SASL ACL');

    $ret = $zkh->delete($acl_node_path);
    diag(sprintf('unable to delete node %s: %d, %s',
                 $acl_node_path, $zkh->get_error(), $!)) unless ($ret);

    $ret = $zkh->delete($node_path);
    diag(sprintf('unable to delete node %s: %d, %s',
                 $node_path, $zkh->get_error(), $!)) unless ($ret);
}
