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
use Test::More tests => 3;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(0);


my $zkh = Net::ZooKeeper->new($hosts);

Net::ZooKeeper::set_log_level(ZOO_LOG_LEVEL_INFO);

SKIP: {
    skip 'no valid handle', 2 unless (defined($zkh));

    SKIP: {
        my $dup = 0;

        if (open(OLDERR, '>&', fileno(STDERR))) {
            if (close(STDERR) and open(STDERR, '+>', undef)) {
                $dup = 1;

                my $old_select = select(STDERR);
                $| = 1;
                select($old_select);
            }
            else {
                open(STDERR, '>&', fileno(OLDERR));
                close(OLDERR);
            }
        }

        skip 'no duplicated stderr', 2 unless ($dup);

        SKIP: {
            $zkh->exists($root_path);

            sleep(1);

            skip 'no seek on stderr', 1 unless (seek(STDERR, 0, 0));

            my $log = <STDERR>;
            like($log, qr/ZOO_/,
                 'exists(): generated log message');
        }

        SKIP: {
            $zkh->DESTROY();

            sleep(1);

            skip 'no seek on stderr', 1 unless (seek(STDERR, 0, 0));

            my $log = <STDERR>;
            like($log, qr/ZOO_/,
                 'DESTROY(): generated log message');
        }

        open(STDERR, '>&', fileno(OLDERR));
        close(OLDERR);
    }
}

Net::ZooKeeper::set_log_level(ZOO_LOG_LEVEL_OFF);

