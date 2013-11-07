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
use Test::More tests => 30;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


SKIP: {
    my $zkh = Net::ZooKeeper->new($hosts);

    my $path = $zkh->create($node_path, 'foo',
                            'acl' => ZOO_OPEN_ACL_UNSAFE) if (defined($zkh));

    skip 'no connection to ZooKeeper', 20 unless
        (defined($path) and $path eq $node_path);


    ## exists()

    $zkh->{'watch_timeout'} = 100;

    my $watch = $zkh->watch();

    my $ret = $zkh->exists($node_path, 'watch' => $watch);
    ok($ret,
       'exists(): checked node existence with watch handle');

    $ret = $watch->wait();
    ok(!$ret,
       'wait(): watch after checking node existence timed out');

    $ret = $zkh->exists($node_path, 'watch' => $watch);
    ok($ret,
       'exists(): checked node existence with renewed watch handle');

    $ret = $watch->wait();
    ok(!$ret,
       'wait(): watch after checking node existence timed out with ' .
       'renewed watch handle');

    undef $watch;
    ok(!defined($watch),
       'undef: released watch handle');

    my $pending_watches = $zkh->{'pending_watches'};
    is($pending_watches, 2,
       '_zk_release_watches(): report pending watches');


    ## get_children()

    $watch = $zkh->watch('timeout' => 50);

    my $num_children = $zkh->get_children($node_path, 'watch' => $watch);
    ok((defined($num_children) and $num_children == 0),
       'get_children(): retrieved zero count of child nodes with ' .
       'watch handle');

    $ret = $watch->wait();
    ok(!$ret,
       'wait(): watch after retrieving child nodes timed out with ' .
       'watch handle');

    $watch->{'timeout'} = 100;

    my @child_paths = $zkh->get_children($node_path, 'watch' => $watch);
    ok((@child_paths == 0),
       'get_children(): retrieved empty list of child nodes with ' .
       'renewed watch handle');

    $ret = $watch->wait();
    ok(!$ret,
       'wait(): watch after retrieving child nodes timed out with ' .
       'renewed watch handle');

    $pending_watches = $zkh->{'pending_watches'};
    is($pending_watches, 4,
       '_zk_release_watches(): report pending watches');


    ## get()

    $watch = $zkh->watch();

    my $node = $zkh->get($node_path, 'watch' => $watch);
    is($node, 'foo',
       'get(): retrieved node value with watch handle');

    $ret = $watch->wait('timeout' => 0);
    ok(!$ret,
       'wait(): watch after retrieving node value timed out with ' .
       'watch handle');

    $node = $zkh->get($node_path, 'watch' => $watch);
    is($node, 'foo',
       'get(): retrieved node value with renewed watch handle');

    $ret = $watch->wait();
    ok(!$ret,
       'wait(): watch after retrieving node value timed out with ' .
       'renewed watch handle');

    $pending_watches = $zkh->{'pending_watches'};
    is($pending_watches, 6,
       '_zk_release_watches(): all watches pending');


    ## _zk_release_watches()

    $ret = $zkh->DESTROY();
    ok($ret,
       'DESTROY(): destroyed handle with pending watches');

    my $event = $watch->{'event'};
    is($event, 0,
       '_zk_release_watches(): watch not destroyed when tied to watch handle');

    $zkh = Net::ZooKeeper->new($hosts);

    SKIP: {
        my $ret = $zkh->exists($node_path, 'watch' => $watch);

        skip 'no connection to ZooKeeper', 2 unless
            (defined($ret) and $ret);

        ok($ret,
           'exists(): checked node existence with renewed watch handle ' .
           'from prior connection');

        $ret = $watch->wait();
        ok(!$ret,
           'wait(): watch after checking node existence timed out with ' .
           'renewed watch handle from prior connection');


    }
}

my $pid = fork();

SKIP: {
    skip 'unable to fork', 4 unless (defined($pid));

    my $zkh = Net::ZooKeeper->new($hosts);

    my $ret = $zkh->exists($node_path) if (defined($zkh));

    if ($pid == 0) {
        ## child process

        my $code = 0;

        if (defined($ret) and $ret) {
            sleep(1);

            my $ret = $zkh->set($node_path, 'foo');

            diag(sprintf('set(): failed in child process: %d, %s',
                         $zkh->get_error(), $!)) unless ($ret);

            $code = !$ret;

            sleep(1);

            my $path = $zkh->create("$node_path/c", 'foo',
                                    'acl' => ZOO_OPEN_ACL_UNSAFE);

            diag(sprintf('create(): failed in child process: %d, %s',
                         $zkh->get_error(), $!)) unless
                (defined($path) and $path eq "$node_path/c");

            $code &= !$ret;

            sleep(1);

            $ret = $zkh->delete("$node_path/c");

            diag(sprintf('delete(): failed in child process: %d, %s',
                         $zkh->get_error(), $!)) unless ($ret);

            $code &= !$ret;

            sleep(1);

            $ret = $zkh->set($node_path, 'foo');

            diag(sprintf('set(): failed in child process: %d, %s',
                         $zkh->get_error(), $!)) unless ($ret);

            $code &= !$ret;
        }

        exit($code);
    }
    else {
        ## parent process

        SKIP: {
            skip 'no connection to ZooKeeper', 9 unless
                (defined($ret) and $ret);

            my $watch = $zkh->watch('timeout' => 5000);


            ## wait()

            my $ret = $zkh->exists($node_path, 'watch' => $watch);
            ok($ret,
               'exists(): checked node existence with watch handle ' .
               'in parent');

            $ret = $watch->wait();
            ok(($ret and $watch->{'event'} == ZOO_CHANGED_EVENT and
                $watch->{'state'} == ZOO_CONNECTED_STATE),
               'wait(): waited for event after checking node existence');

            my $num_children = $zkh->get_children($node_path,
                                                 'watch' => $watch);
            ok((defined($num_children) and $num_children == 0),
               'get_children(): retrieved zero count of child nodes with ' .
               'watch handle in parent');

            $ret = $watch->wait();
            ok(($ret and $watch->{'event'} == ZOO_CHILD_EVENT and
                $watch->{'state'} == ZOO_CONNECTED_STATE),
               'wait(): waited for create child event after ' .
               'retrieving child nodes');

            my @child_paths = $zkh->get_children($node_path,
                                                'watch' => $watch);
            ok((@child_paths == 1 and $child_paths[0] eq 'c'),
               'get_children(): retrieved list of child nodes with ' .
               'watch handle in parent');

            $ret = $watch->wait();
            ok(($ret and $watch->{'event'} == ZOO_CHILD_EVENT and
                $watch->{'state'} == ZOO_CONNECTED_STATE),
               'wait(): waited for delete child event after ' .
               'retrieving child nodes');

            my $node = $zkh->get($node_path, 'watch' => $watch);
            is($node, 'foo',
               'get(): retrieved node value with watch handle in parent');

            $ret = $watch->wait();
            ok(($ret and $watch->{'event'} == ZOO_CHANGED_EVENT and
                $watch->{'state'} == ZOO_CONNECTED_STATE),
               'wait(): waited for event after retrieving node value');

            undef $watch;

            my $pending_watches = $zkh->{'pending_watches'};
            is($pending_watches, 0,
               '_zk_release_watches(): no watches pending');
        }

        my $reap = waitpid($pid, 0);

        diag(sprintf('child process failed: exit %d, signal %d%s',
                     ($? >> 8), ($? & 127),
                     (($? & 128) ? ', core dump' : ''))) if
            ($reap == $pid and $? != 0);
    }
}


## cleanup

{
    my $zkh = Net::ZooKeeper->new($hosts);

    my $ret = $zkh->exists($node_path) if (defined($zkh));

    if (defined($ret) and $ret) {
        $ret = $zkh->delete($node_path);
        diag(sprintf('unable to delete node %s: %d, %s',
                     $node_path, $zkh->get_error(), $!)) unless ($ret);
    }
}

