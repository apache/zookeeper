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

import unittest, threading

import zookeeper, zktestbase

class ClientidTest(zktestbase.TestBase):
    """Test whether clientids work"""
    def setUp(self):
        pass
            
    def testclientid(self):
        cv = threading.Condition()
        self.connected = False
        def connection_watcher(handle, type, state, path):
            cv.acquire()
            self.connected = True
            cv.notify()
            cv.release()

        cv.acquire()
        self.handle = zookeeper.init(self.host, connection_watcher,10000,(123456,"mypassword"))
        self.assertEqual(self.handle, zookeeper.OK)
        cv.wait(15.0)
        cv.release()
        self.assertEqual(self.connected, True, "Connection timed out to " + self.host)
        (cid,passwd) = zookeeper.client_id(self.handle)
        self.assertEqual(cid,123456)
        self.assertEqual(passwd,"mypassword")   

if __name__ == '__main__':
    unittest.main()
