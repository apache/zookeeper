#!/usr/bin/python
#
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

import unittest, threading, re, sys
if sys.version_info < (3,):
	range = xrange

import zookeeper, zktestbase
ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}

class ConnectionTest(zktestbase.TestBase):
    """Test whether we can make a connection"""
    def setUp(self):
        pass

    def testconnection(self):
        cv = threading.Condition()
        self.connected = False
        def connection_watcher(handle, type, state, path):
            cv.acquire()
            self.connected = True
            self.assertEqual(zookeeper.CONNECTED_STATE, state)
            self.handle = handle
            cv.notify()
            cv.release()

        cv.acquire()
        ret = zookeeper.init(self.host, connection_watcher)
        cv.wait(15.0)
        cv.release()
        self.assertEqual(self.connected, True, "Connection timed out to " + self.host)
        self.assertEqual(zookeeper.CONNECTED_STATE, zookeeper.state(self.handle))

        self.assertEqual(zookeeper.close(self.handle), zookeeper.OK)
        # Trying to close the same handle twice is an error, and the C library will segfault on it
        # so make sure this is caught at the Python module layer
        self.assertRaises(zookeeper.ZooKeeperException,
                          zookeeper.close,
                          self.handle)

        self.assertRaises(zookeeper.ZooKeeperException,
                          zookeeper.get,
                          self.handle,
                          "/")

    def testhandlereuse(self):
        """
        Test a) multiple concurrent connections b) reuse of closed handles
        """
        cv = threading.Condition()
        self.connected = False
        def connection_watcher(handle, type, state, path):
            cv.acquire()
            self.connected = True
            self.assertEqual(zookeeper.CONNECTED_STATE, state)
            self.handle = handle
            cv.notify()
            cv.release()

        cv.acquire()
        handles = [ zookeeper.init(self.host) for i in range(10) ]
        ret = zookeeper.init(self.host, connection_watcher)
        cv.wait(15.0)
        cv.release()
        self.assertEqual(self.connected, True, "Connection timed out to " + self.host)
        self.assertEqual(True, self.all( [ zookeeper.state(handle) == zookeeper.CONNECTED_STATE for handle in handles ] ),
                         "Not all connections succeeded")
        oldhandle = handles[3]
        zookeeper.close(oldhandle)
        newhandle = zookeeper.init(self.host)

        # This assertion tests *internal* behaviour; i.e. that the module
        # correctly reuses closed handles. This is therefore implementation
        # dependent.
        self.assertEqual(newhandle, oldhandle, "Didn't get reused handle")

    def testmanyhandles(self):
        """
        Test the ability of the module to support many handles.
        """
        # We'd like to do more, but currently the C client doesn't
        # work with > 83 handles (fails to create a pipe) on MacOS 10.5.8
        handles = [ zookeeper.init(self.host) for i in range(9) ]

        cv = threading.Condition()
        self.connected = False
        def connection_watcher(handle, type, state, path):
            cv.acquire()
            self.connected = True
            self.assertEqual(zookeeper.CONNECTED_STATE, state)
            self.handle = handle
            cv.notify()
            cv.release()

        cv.acquire()
        ret = zookeeper.init(self.host, connection_watcher)
        cv.wait(15.0)
        cv.release()
        self.assertEqual(self.connected, True, "Connection timed out to " + self.host)

        for i,h in enumerate(handles):
            path = "/zkpython-test-handles-%s" % str(i)
            self.assertEqual(path, zookeeper.create(h, path, "", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL))

        self.assertEqual(True, self.all( zookeeper.close(h) == zookeeper.OK for h in handles ))

    def testversionstringexists(self):
        self.assertTrue(hasattr(zookeeper, '__version__'))
        self.assertTrue(re.match("\d.\d.\d", zookeeper.__version__))


    def tearDown(self):
        pass

if __name__ == '__main__':
    unittest.main()
