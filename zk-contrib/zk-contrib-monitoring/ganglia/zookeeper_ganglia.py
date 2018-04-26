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
""" Python Ganglia Module for ZooKeeper monitoring 

Inspired by: http://gist.github.com/448007

Copy this file to /usr/lib/ganglia/python_plugins

"""

import sys
import socket
import time
import re
import copy

from StringIO import StringIO

TIME_BETWEEN_QUERIES = 20
ZK_METRICS = {
    'time' : 0,
    'data' : {}
}
ZK_LAST_METRICS = copy.deepcopy(ZK_METRICS)


class ZooKeeperServer(object):

    def __init__(self, host='localhost', port='2181', timeout=1):
        self._address = (host, int(port))
        self._timeout = timeout

    def get_stats(self):
        """ Get ZooKeeper server stats as a map """
        global ZK_METRICS, ZK_LAST_METRICS
        # update cache
        ZK_METRICS = {
          'time' : time.time(),
          'data' : {}
        }
        data = self._send_cmd('mntr')
        if data:
            parsed_data =  self._parse(data)
        else:
            data = self._send_cmd('stat')
            parsed_data = self._parse_stat(data)
        ZK_METRICS['data'] = parsed_data
        ZK_LAST_METRICS = copy.deepcopy(ZK_METRICS)
        return parsed_data

    def _create_socket(self):
        return socket.socket()

    def _send_cmd(self, cmd):
        """ Send a 4letter word command to the server """
        s = self._create_socket()
        s.settimeout(self._timeout)

        s.connect(self._address)
        s.send(cmd)

        data = s.recv(2048)
        s.close()

        return data

    def _parse(self, data):
        """ Parse the output from the 'mntr' 4letter word command """
        h = StringIO(data)

        result = {}
        for line in h.readlines():
            try:
                key, value = self._parse_line(line)
                result[key] = value
            except ValueError:
                pass # ignore broken lines

        return result

    def _parse_stat(self, data):
        """ Parse the output from the 'stat' 4letter word command """
        global ZK_METRICS, ZK_LAST_METRICS

        h = StringIO(data)

        result = {}

        version = h.readline()
        if version:
            result['zk_version'] = version[version.index(':')+1:].strip()

        # skip all lines until we find the empty one
        while h.readline().strip(): pass

        for line in h.readlines():
            m = re.match('Latency min/avg/max: (\d+)/(\d+)/(\d+)', line)
            if m is not None:
                result['zk_min_latency'] = int(m.group(1))
                result['zk_avg_latency'] = int(m.group(2))
                result['zk_max_latency'] = int(m.group(3))
                continue

            m = re.match('Received: (\d+)', line)
            if m is not None:
                cur_packets = int(m.group(1))
                packet_delta = cur_packets - ZK_LAST_METRICS['data'].get('zk_packets_received_total', cur_packets)
                time_delta = ZK_METRICS['time'] - ZK_LAST_METRICS['time']
                time_delta = 10.0
                try:
                    result['zk_packets_received_total'] = cur_packets
                    result['zk_packets_received'] = packet_delta / float(time_delta)
                except ZeroDivisionError:
                    result['zk_packets_received'] = 0
                continue

            m = re.match('Sent: (\d+)', line)
            if m is not None:
                cur_packets = int(m.group(1))
                packet_delta = cur_packets - ZK_LAST_METRICS['data'].get('zk_packets_sent_total', cur_packets)
                time_delta = ZK_METRICS['time'] - ZK_LAST_METRICS['time']
                try:
                    result['zk_packets_sent_total'] = cur_packets
                    result['zk_packets_sent'] = packet_delta / float(time_delta)
                except ZeroDivisionError:
                    result['zk_packets_sent'] = 0
                continue

            m = re.match('Outstanding: (\d+)', line)
            if m is not None:
                result['zk_outstanding_requests'] = int(m.group(1))
                continue

            m = re.match('Mode: (.*)', line)
            if m is not None:
                result['zk_server_state'] = m.group(1)
                continue

            m = re.match('Node count: (\d+)', line)
            if m is not None:
                result['zk_znode_count'] = int(m.group(1))
                continue

        return result

    def _parse_line(self, line):
        try:
            key, value = map(str.strip, line.split('\t'))
        except ValueError:
            raise ValueError('Found invalid line: %s' % line)

        if not key:
            raise ValueError('The key is mandatory and should not be empty')

        try:
            value = int(value)
        except (TypeError, ValueError):
            pass

        return key, value

def metric_handler(name):
    if time.time() - ZK_LAST_METRICS['time'] > TIME_BETWEEN_QUERIES:
        zk = ZooKeeperServer(metric_handler.host, metric_handler.port, 5)
        try:
            metric_handler.info = zk.get_stats()
        except Exception, e:
            print >>sys.stderr, e
            metric_handler.info = {}

    return metric_handler.info.get(name, 0)

def metric_init(params=None):
    params = params or {}

    metric_handler.host = params.get('host', 'localhost')
    metric_handler.port = int(params.get('port', 2181))
    metric_handler.timestamp = 0

    metrics = {
        'zk_avg_latency': {'units': 'ms'},
        'zk_max_latency': {'units': 'ms'},
        'zk_min_latency': {'units': 'ms'},
        'zk_packets_received': {
            'units': 'pps',
            'value_type': 'float',
            'format': '%f'
        },
        'zk_packets_sent': {
            'units': 'pps',
            'value_type': 'double',
            'format': '%f'
        },
        'zk_outstanding_requests': {'units': 'connections'},
        'zk_znode_count': {'units': 'znodes'},
        'zk_watch_count': {'units': 'watches'},
        'zk_ephemerals_count': {'units': 'znodes'},
        'zk_approximate_data_size': {'units': 'bytes'},
        'zk_open_file_descriptor_count': {'units': 'descriptors'},
        'zk_max_file_descriptor_count': {'units': 'descriptors'},
        'zk_followers': {'units': 'nodes'},
        'zk_synced_followers': {'units': 'nodes'},
        'zk_pending_syncs': {'units': 'syncs'}
    }
    metric_handler.descriptors = {}
    for name, updates in metrics.iteritems():
        descriptor = {
            'name': name,
            'call_back': metric_handler,
            'time_max': 90,
            'value_type': 'int',
            'units': '',
            'slope': 'both',
            'format': '%d',
            'groups': 'zookeeper',
        }
        descriptor.update(updates)
        metric_handler.descriptors[name] = descriptor

    return metric_handler.descriptors.values()

def metric_cleanup():
    pass


if __name__ == '__main__':
    ds = metric_init({'host':'localhost', 'port': '2181'})
    while True:
        for d in ds:
            print "%s=%s" % (d['name'], metric_handler(d['name']))
        time.sleep(10)


