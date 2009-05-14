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

import unittest, threading, zookeeper

class TestBase(unittest.TestCase):
    def __init__(self,methodName='runTest'):
        unittest.TestCase.__init__(self,methodName)
        self.host = "localhost:22182"
        self.connected = False
        self.handle = -1
        f = open("build/test/logs/" + self.__class__.__name__ +".log","w")
        zookeeper.set_log_stream(f)
    
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
        self.handle = zookeeper.init( self.host, connection_watcher, 10000, 0)
        self.cv.wait(15.0)
        self.cv.release()

        if not self.connected:
            raise Exception("Couldn't connect to host -", self.host)
        
    def tearDown(self):
        if self.connected:
            zookeeper.close(self.handle)
    
