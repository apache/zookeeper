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

class DeletionTest(zktestbase.TestBase):
    """Test whether we can delete znodes"""

    def test_sync_delete(self):
        ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}
        self.assertEqual(self.connected, True)
        ret = zookeeper.create(self.handle, "/zk-python-deletetest", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
        self.assertEqual(ret, "/zk-python-deletetest")
        ret = zookeeper.delete(self.handle,"/zk-python-deletetest")
        self.assertEqual(ret, zookeeper.OK)
        children = zookeeper.get_children(self.handle, "/")
        self.assertEqual(False, "zk-python-deletetest" in children)

        # test exception
        self.assertRaises(zookeeper.NoNodeException,
                          zookeeper.delete,
                          self.handle,
                          "/zk-python-deletetest")

    def test_async_delete(self):
        ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}
        self.assertEqual(self.connected, True)
        ret = zookeeper.create(self.handle, "/zk-python-adeletetest", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
        self.assertEqual(ret, "/zk-python-adeletetest")

        self.cv = threading.Condition()
        self.callback_flag = False
        self.rc = -1
        def callback(handle, rc):
            self.cv.acquire()
            self.callback_flag = True
            self.cv.notify()
            self.rc = rc # don't assert this here, as if the assertion fails, the test will block
            self.cv.release()

        self.cv.acquire()
        ret = zookeeper.adelete(self.handle,"/zk-python-adeletetest",-1,callback)
        self.assertEqual(ret, zookeeper.OK, "adelete failed")
        while not self.callback_flag:
            self.cv.wait(15)
        self.cv.release()

        self.assertEqual(self.callback_flag, True, "adelete timed out")
        self.assertEqual(self.rc, zookeeper.OK)


if __name__ == '__main__':
    unittest.main()
