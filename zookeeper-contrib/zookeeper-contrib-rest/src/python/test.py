#! /usr/bin/env python

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import time
import unittest

from zkrest import ZooKeeper

class ZooKeeperREST_TestCase(unittest.TestCase):
    
    BASE_URI = 'http://localhost:9998'

    def setUp(self):
        self.zk = ZooKeeper(self.BASE_URI)

    def tearDown(self):
        try:
            self.zk.delete('/test')
        except ZooKeeper.NotFound:
            pass

    def test_get_root_node(self):
        assert self.zk.get('/') is not None

    def test_get_node_not_found(self):
        self.assertRaises(ZooKeeper.NotFound, \
            self.zk.get, '/dummy-node')

    def test_exists_node(self):
        assert self.zk.exists('/zookeeper') is True

    def test_get_children(self):
        assert any([child['path'] == '/zookeeper/quota' \
            for child in self.zk.get_children('/zookeeper')])
            
    def test_create_znode(self):
        try:
            self.zk.create('/test')
        except ZooKeeper.ZNodeExists:
            pass # it's ok if already exists
        assert self.zk.exists('/test') is True

    def test_create_hierarchy(self):
        try:
            self.zk.delete(['/a/b', '/a'])
        except ZooKeeper.NotFound:
            pass

        self.zk.create('/a')
        self.zk.create('/a/b')

        self.zk.delete(['/a/b', '/a'])

    def test_create_with_data(self):
        self.zk.create('/test', 'some-data')

        zn = self.zk.get('/test')
        self.assertEqual(zn.get('data64', None), \
            'some-data'.encode('base64').strip())

    def test_delete_znode(self):
        self.zk.create('/test')

        self.zk.delete('/test')
        assert not self.zk.exists('/test')

    def test_delete_older_version(self):
        self.zk.create('/test')

        zn = self.zk.get('/test')
        # do one more modification in order to increase the version number
        self.zk.set('/test', 'dummy-data')

        self.assertRaises(ZooKeeper.WrongVersion, \
            self.zk.delete, '/test', version=zn['version'])

    def test_delete_raise_not_found(self):
        self.zk.create('/test')

        zn = self.zk.get('/test')
        self.zk.delete('/test')
 
        self.assertRaises(ZooKeeper.NotFound, \
            self.zk.delete, '/test', version=zn['version'])

    def test_set(self):
        self.zk.create('/test')

        self.zk.set('/test', 'dummy')

        self.assertEqual(self.zk.get('/test')['data64'], \
            'dummy'.encode('base64').strip())

    def test_set_with_older_version(self):
        if not self.zk.exists('/test'):
            self.zk.create('/test', 'random-data')

        zn = self.zk.get('/test')
        self.zk.set('/test', 'new-data')
        self.assertRaises(ZooKeeper.WrongVersion, self.zk.set, \
            '/test', 'older-version', version=zn['version'])

    def test_set_null(self):
        if not self.zk.exists('/test'):
            self.zk.create('/test', 'random-data')
        self.zk.set('/test', 'data')
        assert 'data64' in self.zk.get('/test')

        self.zk.set('/test', null=True)
        assert 'data64' not in self.zk.get('/test')

    def test_create_ephemeral_node(self):
        with self.zk.session():
            if self.zk.exists('/ephemeral-test'):
                self.zk.delete('/ephemeral-test')

            self.zk.create('/ephemeral-test', ephemeral=True)
            zn = self.zk.get('/ephemeral-test')

            assert zn['ephemeralOwner'] != 0

    def test_create_session(self):
        with self.zk.session() as sid:
            self.assertEqual(len(sid), 36) # UUID

    def test_session_invalidation(self):
        self.zk.start_session(expire=1)
        self.zk.create('/ephemeral-test', ephemeral=True)

        # keep the session alive by sending heartbeat requests
        for _ in range(1,2):
            self.zk.heartbeat()
            time.sleep(0.9)

        time.sleep(2) # wait for the session to expire
        self.assertRaises(ZooKeeper.InvalidSession, \
            self.zk.create, '/ephemeral-test', ephemeral=True)

    def test_presence_signaling(self):
        with self.zk.session(expire=1):
            self.zk.create('/i-am-online', ephemeral=True)
            assert self.zk.exists('/i-am-online')
        assert not self.zk.exists('/i-am-online')


if __name__ == '__main__':
    unittest.main()

