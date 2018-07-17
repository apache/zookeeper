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
use Test::More tests => 107;

BEGIN { use_ok('Net::ZooKeeper', qw(:all)) };


my $test_dir;
(undef, $test_dir, undef) = File::Spec->splitpath($0);
require File::Spec->catfile($test_dir, 'util.pl');

my($hosts, $root_path, $node_path) = zk_test_setup(1);


## new()

eval {
    Net::ZooKeeper->new();
};
like($@, qr/Usage: Net::ZooKeeper::new\(package, hosts, \.\.\.\)/,
     'new(): no hostname specified');

eval {
    Net::ZooKeeper->new($hosts, 'bar');
};
like($@, qr/invalid number of arguments/,
     'new(): invalid number of arguments');

eval {
    Net::ZooKeeper->new($hosts, 'session_timeout' => -3);
};
like($@, qr/invalid session timeout/,
     'new(): invalid session timeout');

eval {
    Net::ZooKeeper->new($hosts, 'session_timeout' => 0x4000_0000);
};
like($@, qr/invalid session timeout/,
     'new(): invalid session timeout');

eval {
    Net::ZooKeeper->new($hosts, 'session_id' => 'abcdef');
};
like($@, qr/invalid session ID/,
     'new(): invalid session ID');

my $zkh = Net::ZooKeeper->new($hosts);
isa_ok($zkh, 'Net::ZooKeeper',
       'new(): created handle');


## DESTROY()

eval {
    $zkh->DESTROY('foo');
};
like($@, qr/Usage: Net::ZooKeeper::DESTROY\(zkh\)/,
     'DESTROY(): too many arguments');

my $bad_zkh = {};
$bad_zkh = bless($bad_zkh, 'Net::ZooKeeper');

my $ret = $bad_zkh->DESTROY();
ok(!$ret,
   'DESTROY(): no action on invalid handle');


## add_auth()

eval {
    $zkh->add_auth();
};
like($@, qr/Usage: Net::ZooKeeper::add_auth\(zkh, scheme, cert\)/,
     'add_auth(): no scheme specified');

eval {
    $zkh->add_auth('foo');
};
like($@, qr/Usage: Net::ZooKeeper::add_auth\(zkh, scheme, cert\)/,
     'add_auth(): no certificate specified');

eval {
    $zkh->add_auth('foo', 'foo', 'bar');
};
like($@, qr/Usage: Net::ZooKeeper::add_auth\(zkh, scheme, cert\)/,
     'add_auth(): too many arguments');

eval {
    $bad_zkh->add_auth('foo', 'foo');
};
like($@, qr/invalid handle/,
     'add_auth(): invalid handle');

eval {
    Net::ZooKeeper::add_auth(1, 'foo', 'foo');
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'add_auth(): invalid hash reference');


## create()

eval {
    $zkh->create();
};
like($@, qr/Usage: Net::ZooKeeper::create\(zkh, path, buf, \.\.\.\)/,
     'create(): no path specified');

eval {
    $zkh->create($node_path);
};
like($@, qr/Usage: Net::ZooKeeper::create\(zkh, path, buf, \.\.\.\)/,
     'create(): no data buffer specified');

eval {
    $zkh->create($node_path, 'foo', 'bar');
};
like($@, qr/invalid number of arguments/,
     'create(): invalid number of arguments');

eval {
    $zkh->create($node_path, 'foo', 'path_read_len' => -3);
};
like($@, qr/invalid path read length/,
     'create(): invalid path read length');

eval {
    $zkh->create($node_path, 'foo', 'path_read_len' => 1);
};
like($@, qr/invalid path read length/,
     'create(): invalid path read length');

eval {
    $zkh->create($node_path, 'foo', 'flags' => 15);
};
like($@, qr/invalid create flags/,
     'create(): invalid create flags');

eval {
    $zkh->create($node_path, 'foo', 'flags' => ZOO_EPHEMERAL, 'acl', 'foo');
};
like($@, qr/invalid ACL array reference/,
     'create(): invalid ACL array reference');

eval {
    $zkh->create($node_path, 'foo', 'acl', {});
};
like($@, qr/invalid ACL array reference/,
     'create(): invalid ACL array reference to hash');

eval {
    my @acl = ('foo', 'bar');
    $zkh->create($node_path, 'foo', 'acl', \@acl);
};
like($@, qr/invalid ACL entry hash reference/,
     'create(): invalid ACL entry hash reference');

eval {
    my @acl = ({ 'foo' => 'bar' });
    $zkh->create($node_path, 'foo', 'acl', \@acl);
};
like($@, qr/no ACL entry perms element/,
     'create(): no ACL entry perms element');

eval {
    my @acl = (
        {
            'perms'  => -1
        }
    );
    $zkh->create($node_path, 'foo', 'acl', \@acl);
};
like($@, qr/invalid ACL entry perms/,
     'create(): invalid ACL entry perms');

eval {
    my @acl = (
        {
            'perms'  => ZOO_PERM_ALL
        }
    );
    $zkh->create($node_path, 'foo', 'acl', \@acl);
};
like($@, qr/no ACL entry scheme element/,
     'create(): no ACL entry scheme element');

eval {
    my @acl = (
        {
            'perms'  => ZOO_PERM_ALL,
            'scheme' => 'foo'
        }
    );
    $zkh->create($node_path, 'foo', 'acl', \@acl);
};
like($@, qr/no ACL entry id element/,
     'create(): no ACL entry id element');

eval {
    my @acl = (
        {
            'perms'  => ZOO_PERM_ALL,
            'scheme' => 'foo',
            'id'     => 'bar'
        },
        'bar'
    );
    $zkh->create($node_path, 'foo', 'acl', \@acl);
};
like($@, qr/invalid ACL entry hash reference/,
     'create(): invalid second ACL entry hash reference');

eval {
    $bad_zkh->create($node_path, 'foo');
};
like($@, qr/invalid handle/,
     'create(): invalid handle');

eval {
    Net::ZooKeeper::create(1, $node_path, 'foo');
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'create(): invalid hash reference');


## delete()

eval {
    $zkh->delete();
};
like($@, qr/Usage: Net::ZooKeeper::delete\(zkh, path, \.\.\.\)/,
     'delete(): no path specified');

eval {
    $zkh->delete($node_path, 'bar');
};
like($@, qr/invalid number of arguments/,
     'delete(): invalid number of arguments');

eval {
    $zkh->delete($node_path, 'version' => -3);
};
like($@, qr/invalid version requirement/,
     'delete(): invalid version requirement');

eval {
    $bad_zkh->delete($node_path);
};
like($@, qr/invalid handle/,
     'delete(): invalid handle');

eval {
    Net::ZooKeeper::delete(1, $node_path);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'delete(): invalid hash reference');


## exists()

eval {
    $zkh->exists();
};
like($@, qr/Usage: Net::ZooKeeper::exists\(zkh, path, \.\.\.\)/,
     'exists(): no path specified');

eval {
    $zkh->exists($node_path, 'bar');
};
like($@, qr/invalid number of arguments/,
     'exists(): invalid number of arguments');

eval {
    $zkh->exists($node_path, 'watch', 'bar');
};
like($@, qr/watch is not a hash reference of type Net::ZooKeeper::Watch/,
     'exists(): invalid watch hash reference');

eval {
    $zkh->exists($node_path, 'watch', []);
};
like($@, qr/watch is not a hash reference of type Net::ZooKeeper::Watch/,
     'exists(): invalid watch hash reference to array');

eval {
    $zkh->exists($node_path, 'stat', 'bar');
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'exists(): invalid stat hash reference');

eval {
    $zkh->exists($node_path, 'stat', []);
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'exists(): invalid stat hash reference');

eval {
    $bad_zkh->exists($node_path);
};
like($@, qr/invalid handle/,
     'exists(): invalid handle');

eval {
    Net::ZooKeeper::exists(1, $node_path);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'exists(): invalid hash reference');


## get_children()

eval {
    $zkh->get_children();
};
like($@, qr/Usage: Net::ZooKeeper::get_children\(zkh, path, \.\.\.\)/,
     'get_children(): no path specified');

eval {
    $zkh->get_children($node_path, 'bar');
};
like($@, qr/invalid number of arguments/,
     'get_children(): invalid number of arguments');

eval {
    $zkh->get_children($node_path, 'watch', 'bar');
};
like($@, qr/watch is not a hash reference of type Net::ZooKeeper::Watch/,
     'get_children(): invalid watch hash reference');

eval {
    $zkh->get_children($node_path, 'watch', []);
};
like($@, qr/watch is not a hash reference of type Net::ZooKeeper::Watch/,
     'get_children(): invalid watch ash reference to array');

eval {
    $bad_zkh->get_children($node_path);
};
like($@, qr/invalid handle/,
     'get_children(): invalid handle');

eval {
    Net::ZooKeeper::get_children(1, $node_path);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'get_children(): invalid hash reference');


## get()

eval {
    $zkh->get();
};
like($@, qr/Usage: Net::ZooKeeper::get\(zkh, path, \.\.\.\)/,
     'get(): no path specified');

eval {
    $zkh->get($node_path, 'bar');
};
like($@, qr/invalid number of arguments/,
     'get(): invalid number of arguments');

eval {
    $zkh->get($node_path, 'data_read_len' => -3);
};
like($@, qr/invalid data read length/,
     'get(): invalid data read length');

eval {
    $zkh->get($node_path, 'data_read_len' => 10, 'watch', 'bar');
};
like($@, qr/watch is not a hash reference of type Net::ZooKeeper::Watch/,
     'get(): invalid watch hash reference');

eval {
    $zkh->get($node_path, 'watch', []);
};
like($@, qr/watch is not a hash reference of type Net::ZooKeeper::Watch/,
     'get(): invalid watch hash reference to array');

eval {
    $zkh->get($node_path, 'stat', 'bar');
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'get(): invalid stat hash reference');

eval {
    $zkh->get($node_path, 'stat', []);
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'get(): invalid stat hash reference');

eval {
    $bad_zkh->get($node_path);
};
like($@, qr/invalid handle/,
     'get(): invalid handle');

eval {
    Net::ZooKeeper::get(1, $node_path);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'get(): invalid hash reference');


## set()

eval {
    $zkh->set();
};
like($@, qr/Usage: Net::ZooKeeper::set\(zkh, path, buf, \.\.\.\)/,
     'set(): no path specified');

eval {
    $zkh->set($node_path);
};
like($@, qr/Usage: Net::ZooKeeper::set\(zkh, path, buf, \.\.\.\)/,
     'set(): no data buffer specified');

eval {
    $zkh->set($node_path, 'foo', 'bar');
};
like($@, qr/invalid number of arguments/,
     'set(): invalid number of arguments');

eval {
    $zkh->set($node_path, 'foo', 'version' => -3);
};
like($@, qr/invalid version requirement/,
     'set(): invalid version requirement');

eval {
    $zkh->set($node_path, 'foo', 'version', 0, 'stat', 'bar');
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'set(): invalid stat hash reference');

eval {
    $zkh->set($node_path, 'foo', 'stat', []);
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'set(): invalid stat hash reference');

eval {
    $bad_zkh->set($node_path, 'foo');
};
like($@, qr/invalid handle/,
     'set(): invalid handle');

eval {
    Net::ZooKeeper::set(1, $node_path, 'foo');
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'set(): invalid hash reference');


## get_acl()

eval {
    $zkh->get_acl();
};
like($@, qr/Usage: Net::ZooKeeper::get_acl\(zkh, path, \.\.\.\)/,
     'get_acl(): no path specified');

eval {
    $zkh->get_acl($node_path, 'bar');
};
like($@, qr/invalid number of arguments/,
     'get_acl(): invalid number of arguments');

eval {
    $zkh->get_acl($node_path, 'stat', 'bar');
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'get_acl(): invalid stat hash reference');

eval {
    $zkh->get_acl($node_path, 'stat', []);
};
like($@, qr/stat is not a hash reference of type Net::ZooKeeper::Stat/,
     'get_acl(): invalid stat hash reference');

eval {
    $bad_zkh->get_acl($node_path);
};
like($@, qr/invalid handle/,
     'get_acl(): invalid handle');

eval {
    Net::ZooKeeper::get_acl(1, $node_path);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'get_acl(): invalid hash reference');


## set_acl()

eval {
    $zkh->set_acl();
};
like($@, qr/Usage: Net::ZooKeeper::set_acl\(zkh, path, acl_arr, \.\.\.\)/,
     'set_acl(): no path specified');

eval {
    $zkh->set_acl($node_path);
};
like($@, qr/Usage: Net::ZooKeeper::set_acl\(zkh, path, acl_arr, \.\.\.\)/,
     'set_acl(): no data buffer specified');

eval {
    $zkh->set_acl($node_path, 'foo');
};
like($@, qr/acl_arr is not an array reference/,
     'set_acl(): invalid ACL array reference');

eval {
    $zkh->set_acl($node_path, {});
};
like($@, qr/acl_arr is not an array reference/,
     'set_acl(): invalid ACL array reference to hash');

eval {
    my @acl = ('foo', 'bar');
    $zkh->set_acl($node_path, \@acl);
};
like($@, qr/invalid ACL entry hash reference/,
     'set_acl(): invalid ACL entry hash reference');

eval {
    my @acl = ({ 'foo' => 'bar' });
    $zkh->set_acl($node_path, \@acl);
};
like($@, qr/no ACL entry perms element/,
     'set_acl(): no ACL entry perms element');

eval {
    my @acl = (
        {
            'perms'  => -1
        }
    );
    $zkh->set_acl($node_path, \@acl);
};
like($@, qr/invalid ACL entry perms/,
     'set_acl(): invalid ACL entry perms');

eval {
    my @acl = (
        {
            'perms'  => ZOO_PERM_ALL
        }
    );
    $zkh->set_acl($node_path, \@acl);
};
like($@, qr/no ACL entry scheme element/,
     'set_acl(): no ACL entry scheme element');

eval {
    my @acl = (
        {
            'perms'  => ZOO_PERM_ALL,
            'scheme' => 'foo'
        }
    );
    $zkh->set_acl($node_path, \@acl);
};
like($@, qr/no ACL entry id element/,
     'set_acl(): no ACL entry id element');

eval {
    my @acl = (
        {
            'perms'  => ZOO_PERM_ALL,
            'scheme' => 'foo',
            'id'     => 'bar'
        },
        'bar'
    );
    $zkh->set_acl($node_path, \@acl);
};
like($@, qr/invalid ACL entry hash reference/,
     'set_acl(): invalid second ACL entry hash reference');

eval {
    $zkh->set_acl($node_path, [], 'bar');
};
like($@, qr/invalid number of arguments/,
     'set_acl(): invalid number of arguments');

eval {
    $zkh->set_acl($node_path, [], 'version' => -3);
};
like($@, qr/invalid version requirement/,
     'set_acl(): invalid version requirement');

eval {
    $bad_zkh->set_acl($node_path, []);
};
like($@, qr/invalid handle/,
     'set_acl(): invalid handle');

eval {
    Net::ZooKeeper::set_acl(1, $node_path, []);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'set_acl(): invalid hash reference');


## stat()

eval {
    $zkh->stat('bar');
};
like($@, qr/Usage: Net::ZooKeeper::stat\(zkh\)/,
     'stat(): too many arguments');

eval {
    $bad_zkh->stat();
};
like($@, qr/invalid handle/,
     'stat(): invalid handle');

eval {
    Net::ZooKeeper::stat(1);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'stat(): invalid hash reference');

my $stat = $zkh->stat();
isa_ok($stat, 'Net::ZooKeeper::Stat',
       'stat(): created stat handle');


## stat DESTROY()

eval {
    $stat->DESTROY('foo');
};
like($@, qr/Usage: Net::ZooKeeper::Stat::DESTROY\(zksh\)/,
     'stat DESTROY(): too many arguments');

my $bad_stat = {};
$bad_stat = bless($bad_stat, 'Net::ZooKeeper::Stat');

$ret = $bad_stat->DESTROY();
ok(!$ret,
   'stat DESTROY(): no action on invalid handle');


## watch()

eval {
    $zkh->watch('bar');
};
like($@, qr/invalid number of arguments/,
     'watch(): invalid number of arguments');

eval {
    $bad_zkh->watch();
};
like($@, qr/invalid handle/,
     'watch(): invalid handle');

eval {
    Net::ZooKeeper::watch(1);
};
like($@, qr/zkh is not a hash reference of type Net::ZooKeeper/,
     'watch(): invalid hash reference');

my $watch = $zkh->watch();
isa_ok($watch, 'Net::ZooKeeper::Watch',
       'watch(): created watch handle');


## watch DESTROY()

eval {
    $watch->DESTROY('foo');
};
like($@, qr/Usage: Net::ZooKeeper::Watch::DESTROY\(zkwh\)/,
     'watch DESTROY(): too many arguments');

my $bad_watch = {};
$bad_watch = bless($bad_watch, 'Net::ZooKeeper::Watch');

$ret = $bad_watch->DESTROY();
ok(!$ret,
   'watch DESTROY(): no action on invalid handle');


## wait()

eval {
    $watch->wait('bar');
};
like($@, qr/invalid number of arguments/,
     'wait(): invalid number of arguments');

eval {
    $bad_watch->wait();
};
like($@, qr/invalid handle/,
     'wait(): invalid watch handle');

eval {
    Net::ZooKeeper::Watch::wait(1);
};
like($@, qr/zkwh is not a hash reference of type Net::ZooKeeper::Watch/,
     'wait(): invalid watch hash reference');


## set_log_level()

eval {
    my $f = \&Net::ZooKeeper::set_log_level;
    &$f();
};
like($@, qr/Usage: Net::ZooKeeper::set_log_level\(level\)/,
     'set_log_level(): no level specified');

eval {
    my $f = \&Net::ZooKeeper::set_log_level;
    &$f(ZOO_LOG_LEVEL_OFF, 'foo');
};
like($@, qr/Usage: Net::ZooKeeper::set_log_level\(level\)/,
     'set_log_level(): too many arguments');

eval {
    Net::ZooKeeper::set_log_level((ZOO_LOG_LEVEL_OFF) - 1);
};
like($@, qr/invalid log level/,
     'set_log_level(): invalid low log level');

eval {
    Net::ZooKeeper::set_log_level((ZOO_LOG_LEVEL_DEBUG) + 1);
};
like($@, qr/invalid log level/,
     'set_log_level(): invalid high log level');


## set_deterministic_conn_order()

eval {
    my $f = \&Net::ZooKeeper::set_deterministic_conn_order;
    &$f();
};
like($@, qr/Usage: Net::ZooKeeper::set_deterministic_conn_order\(flag\)/,
     'set_deterministic_conn_order(): no flag specified');

eval {
    my $f = \&Net::ZooKeeper::set_deterministic_conn_order;
    &$f(1, 'foo');
};
like($@, qr/Usage: Net::ZooKeeper::set_deterministic_conn_order\(flag\)/,
     'set_deterministic_conn_order(): too many arguments');

