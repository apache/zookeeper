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

sub zk_test_setup
{
    my $verbose = shift;

    $SIG{'PIPE'} = 'IGNORE';

    my $hosts = $ENV{'ZK_TEST_HOSTS'};
    unless (defined($hosts) and $hosts =~ /\S/) {
        $hosts = 'localhost:0';
        diag('no ZooKeeper hostnames specified in ZK_TEST_HOSTS env var, ' .
             "using $hosts") if ($verbose);
    }

    my $root_path = $ENV{'ZK_TEST_PATH'};
    if (defined($root_path) and $root_path =~ /^\//) {
        $root_path =~ s/\/+/\//g;
        $root_path =~ s/\/$//;
    }
    else {
        $root_path = '/';
        diag('no ZooKeeper path specified in ZK_TEST_PATH env var, ' .
             'using root path') if ($verbose);
    }

    my $node_path = $root_path . (($root_path =~ /\/$/) ? '' : '/') .
        '_net_zookeeper_test';

    return ($hosts, $root_path, $node_path);
}

sub zk_acl_test_setup
{
    my $username = '_net_zookeeper_test';

    my $password = 'test';

    ## digest is Base64-encoded SHA1 digest of username:password
    my $digest = '2qi7Erp2cXYLGcQbXADiwUFaOGo=';

    return ($username, $password, $digest);
}

1;
    
