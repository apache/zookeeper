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

import zookeeper, zktestbase, unittest, threading

class SyncCreationTest(zktestbase.TestBase):
    """Test whether we can create znodes"""
    # to do: startup and teardown via scripts?
    def setUp( self ):
        zktestbase.TestBase.setUp(self)
        try:
            zookeeper.delete(self.handle, "/zk-python-createtest",-1)
        except:
            pass

    def test_sync_create(self):
        ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}
        self.assertEqual(self.connected, True)
        ret = zookeeper.create(self.handle, "/zk-python-createtest", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
        self.assertEqual(ret, zookeeper.OK)

class AsyncCreationTest(zktestbase.TestBase):
    def setUp( self ):
        zktestbase.TestBase.setUp(self)
        try:
            zookeeper.delete(self.handle, "/zk-python-acreatetest",-1)
        except:
            pass
        
    def test_async_create(self):
        self.cv = threading.Condition()
        def callback(handle, rc, value):
            self.cv.acquire()
            self.callback_flag = True            
            self.cv.notify()
            self.cv.release()
            
        ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}
        self.assertEqual(self.connected, True, "Not connected!")
        self.cv.acquire()
        
        ret = zookeeper.acreate(self.handle, "/zk-python-acreatetest", "nodecontents",
                                [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL,
                                callback )
        self.assertEqual(ret, zookeeper.OK, "acreate failed")
        while not self.callback_flag:
            self.cv.wait(15)
        self.cv.release()

        self.assertEqual(self.callback_flag, True, "acreate timed out")

        
if __name__ == '__main__':
    unittest.main()
