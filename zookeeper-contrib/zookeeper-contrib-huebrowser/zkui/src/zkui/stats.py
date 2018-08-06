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

import socket
import re

from StringIO import StringIO

class Session(object):

  class BrokenLine(Exception): pass

  def __init__(self, session):
    m = re.search('/(\d+\.\d+\.\d+\.\d+):(\d+)\[(\d+)\]\((.*)\)', session)
    if m:
        self.host = m.group(1)
        self.port = m.group(2)
        self.interest_ops = m.group(3)
        for d in m.group(4).split(","):
            k,v = d.split("=")
            self.__dict__[k] = v
    else:
        raise Session.BrokenLine() 

class ZooKeeperStats(object):

    def __init__(self, host='localhost', port='2181', timeout=1):
        self._address = (host, int(port))
        self._timeout = timeout

    def get_stats(self):
        """ Get ZooKeeper server stats as a map """
        data = self._send_cmd('mntr')
        if data:
            return self._parse(data)
        else:
            data = self._send_cmd('stat')
            return self._parse_stat(data)

    def get_clients(self):
      """ Get ZooKeeper server clients """
      clients = []

      stat = self._send_cmd('stat')
      if not stat:
        return clients

      sio = StringIO(stat)

      #skip two lines
      sio.readline()
      sio.readline()

      for line in sio:
        if not line.strip():
          break
        try:
          clients.append(Session(line.strip()))
        except Session.BrokenLine:
          continue

      return clients

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
                result['zk_packets_received'] = int(m.group(1))
                continue

            m = re.match('Sent: (\d+)', line)
            if m is not None:
                result['zk_packets_sent'] = int(m.group(1))
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

