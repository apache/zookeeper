#! /usr/bin/env python
#  Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import unittest
import socket
import sys

from StringIO import StringIO

from check_zookeeper import ZooKeeperServer, NagiosHandler, CactiHandler, GangliaHandler

ZK_MNTR_OUTPUT = """zk_version\t3.4.0--1, built on 06/19/2010 15:07 GMT
zk_avg_latency\t1
zk_max_latency\t132
zk_min_latency\t0
zk_packets_received\t640
zk_packets_sent\t639
zk_outstanding_requests\t0
zk_server_state\tfollower
zk_znode_count\t4
zk_watch_count\t0
zk_ephemerals_count\t0
zk_approximate_data_size\t27
zk_open_file_descriptor_count\t22
zk_max_file_descriptor_count\t1024
"""

ZK_MNTR_OUTPUT_WITH_BROKEN_LINES = """zk_version\t3.4.0
zk_avg_latency\t23
broken-line

"""

ZK_STAT_OUTPUT = """Zookeeper version: 3.3.0-943314, built on 05/11/2010 22:20 GMT
Clients:
 /0:0:0:0:0:0:0:1:34564[0](queued=0,recved=1,sent=0)

Latency min/avg/max: 0/40/121
Received: 11
Sent: 10
Outstanding: 0
Zxid: 0x700000003
Mode: follower
Node count: 4
"""

class SocketMock(object):
    def __init__(self):
        self.sent = []

    def settimeout(self, timeout):
        self.timeout = timeout

    def connect(self, address):
        self.address = address

    def send(self, data):
        self.sent.append(data)
        return len(data)

    def recv(self, size):
        return ZK_MNTR_OUTPUT[:size]

    def close(self): pass

class ZK33xSocketMock(SocketMock):
    def __init__(self):
        SocketMock.__init__(self)
        self.got_stat_cmd = False

    def recv(self, size):
        if 'stat' in self.sent:
            return ZK_STAT_OUTPUT[:size]
        else:
            return ''

class UnableToConnectSocketMock(SocketMock):
    def connect(self, _):
        raise socket.error('[Errno 111] Connection refused')

def create_server_mock(socket_class):
    class ZooKeeperServerMock(ZooKeeperServer):
        def _create_socket(self):
            return socket_class()
    return ZooKeeperServerMock()

class TestCheckZookeeper(unittest.TestCase):

    def setUp(self):
        self.zk = ZooKeeperServer()
    
    def test_parse_valid_line(self):
        key, value = self.zk._parse_line('something\t5')

        self.assertEqual(key, 'something')
        self.assertEqual(value, 5)

    def test_parse_line_raises_exception_on_invalid_output(self):
        invalid_lines = ['something', '', 'a\tb\tc', '\t1']
        for line in invalid_lines:
            self.assertRaises(ValueError, self.zk._parse_line, line)

    def test_parser_on_valid_output(self):
        data = self.zk._parse(ZK_MNTR_OUTPUT)

        self.assertEqual(len(data), 14)
        self.assertEqual(data['zk_znode_count'], 4)
        
    def test_parse_should_ignore_invalid_lines(self):
        data = self.zk._parse(ZK_MNTR_OUTPUT_WITH_BROKEN_LINES)

        self.assertEqual(len(data), 2)

    def test_parse_stat_valid_output(self):
        data = self.zk._parse_stat(ZK_STAT_OUTPUT)

        result = {
            'zk_version' : '3.3.0-943314, built on 05/11/2010 22:20 GMT',
            'zk_min_latency' : 0,
            'zk_avg_latency' : 40,
            'zk_max_latency' : 121,
            'zk_packets_received': 11,
            'zk_packets_sent': 10,
            'zk_server_state': 'follower',
            'zk_znode_count': 4
        }
        for k, v in result.iteritems():
            self.assertEqual(v, data[k])

    def test_recv_valid_output(self):
        zk = create_server_mock(SocketMock)

        data = zk.get_stats()
        self.assertEqual(len(data), 14)
        self.assertEqual(data['zk_znode_count'], 4)

    def test_socket_unable_to_connect(self):
        zk = create_server_mock(UnableToConnectSocketMock)

        self.assertRaises(socket.error, zk.get_stats)

    def test_use_stat_cmd_if_mntr_is_not_available(self):
        zk = create_server_mock(ZK33xSocketMock)

        data = zk.get_stats()
        self.assertEqual(data['zk_version'], '3.3.0-943314, built on 05/11/2010 22:20 GMT')

class HandlerTestCase(unittest.TestCase):
    
    def setUp(self):
        try:
            sys._stdout
        except:
            sys._stdout = sys.stdout
        
        sys.stdout = StringIO()

    def tearDown(self):
        sys.stdout = sys._stdout

    def output(self):
        sys.stdout.seek(0)
        return sys.stdout.read()


class TestNagiosHandler(HandlerTestCase):

    def _analyze(self, w, c, k, stats):
        class Opts(object):
            warning = w
            critical = c
            key = k

        return NagiosHandler().analyze(Opts(), {'localhost:2181':stats})

    def test_ok_status(self):
        r = self._analyze(10, 20, 'a', {'a': 5})

        self.assertEqual(r, 0)
        self.assertEqual(self.output(), 'Ok "a"!|localhost:2181=5;10;20\n')

        r = self._analyze(20, 10, 'a', {'a': 30})
        self.assertEqual(r, 0)

    def test_warning_status(self):
        r = self._analyze(10, 20, 'a', {'a': 15})
        self.assertEqual(r, 1)
        self.assertEqual(self.output(), 
            'Warning "a" localhost:2181!|localhost:2181=15;10;20\n')

        r = self._analyze(20, 10, 'a', {'a': 15})
        self.assertEqual(r, 1)

    def test_critical_status(self):
        r = self._analyze(10, 20, 'a', {'a': 30})
        self.assertEqual(r, 2)
        self.assertEqual(self.output(),
            'Critical "a" localhost:2181!|localhost:2181=30;10;20\n')

        r = self._analyze(20, 10, 'a', {'a': 5})
        self.assertEqual(r, 2)

    def test_check_a_specific_key_on_all_hosts(self):
        class Opts(object):
            warning = 10
            critical = 20
            key = 'latency'

        r = NagiosHandler().analyze(Opts(), {
            's1:2181': {'latency': 5},
            's2:2181': {'latency': 15},
            's3:2181': {'latency': 35},
        })
        self.assertEqual(r, 2)
        self.assertEqual(self.output(), 
            'Critical "latency" s3:2181!|s1:2181=5;10;20 '\
            's3:2181=35;10;20 s2:2181=15;10;20\n')

class TestCactiHandler(HandlerTestCase):
    class Opts(object):
        key = 'a'
        leader = False

        def __init__(self, leader=False):
            self.leader = leader

    def test_output_values_for_all_hosts(self):
        r = CactiHandler().analyze(TestCactiHandler.Opts(), {
            's1:2181':{'a':1},
            's2:2181':{'a':2, 'b':3}
        })
        self.assertEqual(r, None)
        self.assertEqual(self.output(), 's1_2181:1 s2_2181:2')
    
    def test_output_single_value_for_leader(self):
        r = CactiHandler().analyze(TestCactiHandler.Opts(leader=True), {
            's1:2181': {'a':1, 'zk_server_state': 'leader'},
            's2:2181': {'a':2}
        })
        self.assertEqual(r, 0)
        self.assertEqual(self.output(), '1\n')


class TestGangliaHandler(unittest.TestCase):

    class TestableGangliaHandler(GangliaHandler):
        def __init__(self):
            GangliaHandler.__init__(self)
            self.cli_calls = []
    
        def call(self, cli):
            self.cli_calls.append(' '.join(cli))
            
    def test_send_single_metric(self):
        class Opts(object):
            @property
            def gmetric(self): return '/usr/bin/gmetric'
        opts = Opts()
        
        h = TestGangliaHandler.TestableGangliaHandler()
        h.analyze(opts, {'localhost:2181':{'latency':10}})

        cmd = "%s -n latency -v 10 -t uint32" % opts.gmetric
        assert cmd in h.cli_calls

if __name__ == '__main__':
    unittest.main()

