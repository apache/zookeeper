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

import os
import unittest, threading, zookeeper
ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}

class TestBase(unittest.TestCase):
    SERVER_PORT = 22182
    SERVER_SSL_PORT = 22183

    def __init__(self,methodName='runTest'):
        unittest.TestCase.__init__(self,methodName)
        self.host = "localhost:%d" % self.SERVER_PORT
        self.sslhost = "localhost:%d" % self.SERVER_SSL_PORT
        self.sslcert =  "./target/zkpython_tests/ssl/server.crt,./target/zkpython_tests/ssl/client.crt,./target/zkpython_tests/ssl/clientkey.pem,password"
        self.connected = False
        self.handle = -1
        logdir = os.environ.get("ZKPY_LOG_DIR")
        logfile = os.path.join(logdir, self.__class__.__name__ + ".log")
        try:
            f = open(logfile,"w")
            zookeeper.set_log_stream(f)
        except IOError:
            print("Couldn't open " + logfile + " for writing")


    def setUp(self):
        self.callback_flag = False
        self.cv = threading.Condition()
        self.connected = False
        def connection_watcher(handle, type, state, path):
            self.cv.acquire()
            self.connected = True
            self.cv.notify()
            self.cv.release()

        self.cv.acquire()
        self.handle = zookeeper.init(self.host, connection_watcher)
        self.cv.wait(15.0)
        self.cv.release()

        if not self.connected:
            raise Exception("Couldn't connect to host -", self.host)

    def newConnection(self):
        cv = threading.Condition()
        self.pending_connection = False
        def connection_watcher(handle, type, state, path):
            cv.acquire()
            self.pending_connection = True
            cv.notify()
            cv.release()

        cv.acquire()
        handle = zookeeper.init(self.host, connection_watcher)
        cv.wait(15.0)
        cv.release()

        if not self.pending_connection:
            raise Exception("Couldn't connect to host -", self.host)
        return handle

    def ensureDeleted(self,path):
        self.assertEqual(zookeeper.CONNECTED_STATE, zookeeper.state(self.handle), "Not connected!")
        try:
            self.assertEqual(zookeeper.OK, zookeeper.delete(self.handle, path))
        except zookeeper.NoNodeException:
            pass

    def ensureCreated(self,path,data="",flags=zookeeper.EPHEMERAL):
        """
        It's possible not to get the flags you want here if the node already exists
        """
        self.assertEqual(zookeeper.CONNECTED_STATE, zookeeper.state(self.handle), "Not connected!")
        try:
            self.assertEqual(path, zookeeper.create(self.handle, path, data, [ZOO_OPEN_ACL_UNSAFE], flags))
        except zookeeper.NodeExistsException:
            pass

    def tearDown(self):
        if self.connected:
            zookeeper.close(self.handle)

    def all(self, iterable):
        for element in iterable:
            if not element:
                return False
        return True
