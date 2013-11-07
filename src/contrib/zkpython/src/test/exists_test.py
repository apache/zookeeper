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

import zookeeper, zktestbase, unittest, threading

ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}
class ExistsTest(zktestbase.TestBase):
    def setUp( self ):
        zktestbase.TestBase.setUp(self)
        try:
            zookeeper.create(self.handle, "/zk-python-existstest","existstest", [ZOO_OPEN_ACL_UNSAFE],zookeeper.EPHEMERAL)
            zookeeper.create(self.handle, "/zk-python-aexiststest","existstest",[ZOO_OPEN_ACL_UNSAFE],zookeeper.EPHEMERAL)
        except:
            pass

    def test_sync_exists(self):
        self.assertEqual(self.connected, True)
        ret = zookeeper.exists(self.handle, "/zk-python-existstest", None)
        self.assertNotEqual(ret, None, "/zk-python-existstest does not exist (possibly means creation failure)")

    def test_sync_nexists(self):
        self.assertEqual(None, zookeeper.exists(self.handle, "/i-dont-exist", None))


    def test_async_exists(self):
        self.cv = threading.Condition()
        def callback(handle, rc, stat):
            self.cv.acquire()
            self.callback_flag = True
            self.cv.notify()
            self.cv.release()
            self.rc = rc

        self.assertEqual(self.connected, True)

        self.cv.acquire()
        ret = zookeeper.aexists(self.handle, "/zk-python-aexiststest", None,
                                callback )
        self.assertEqual(ret, zookeeper.OK)
        while not self.callback_flag:
            self.cv.wait(15)
        self.cv.release()

        self.assertEqual(self.callback_flag, True, "aexists timed out")
        self.assertEqual(self.rc, zookeeper.OK, "Return code not ok:" + zookeeper.zerror(self.rc))


if __name__ == '__main__':
    unittest.main()
