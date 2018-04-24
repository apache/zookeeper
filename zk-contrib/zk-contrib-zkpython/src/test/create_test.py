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

class CreationTest(zktestbase.TestBase):
    """Test whether we can create znodes"""
    # to do: startup and teardown via scripts?
    def setUp(self):
        zktestbase.TestBase.setUp(self)
        try:
            zookeeper.delete(self.handle, "/zk-python-createtest")
            zookeeper.delete(self.handle, "/zk-python-acreatetest")
        except:
            pass

    def test_sync_create(self):
        self.assertEqual(self.connected, True)
        ret = zookeeper.create(self.handle, "/zk-python-createtest", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
        self.assertEqual(ret, "/zk-python-createtest")
        self.assertRaises(zookeeper.NoChildrenForEphemeralsException,
                          zookeeper.create,
                          self.handle,
                          "/zk-python-createtest/invalid-child",
                          "",
                          [ZOO_OPEN_ACL_UNSAFE],
                          zookeeper.EPHEMERAL)

    def test_sync_create_existing(self):
        self.assertEqual(self.connected, True)
        ret = zookeeper.create(self.handle, "/zk-python-createtest-existing", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
        self.assertEqual(ret, "/zk-python-createtest-existing")

        self.assertRaises(zookeeper.NodeExistsException,
                          zookeeper.create,
                          self.handle,
                          "/zk-python-createtest-existing",
                          "nodecontents",
                          [ZOO_OPEN_ACL_UNSAFE],
                          zookeeper.EPHEMERAL)


    def test_exception_paths(self):
        """
        Make sure common exceptions due to API misuse are correctly propogated
        """
        self.assertRaises(zookeeper.BadArgumentsException,
                          zookeeper.create,
                          self.handle,
                          "/zk-python-badargs-test",
                          "",
                          [ZOO_OPEN_ACL_UNSAFE],
                          -1)
        self.assertRaises(zookeeper.InvalidACLException,
                          zookeeper.create,
                          self.handle,
                          "/zk-python-invalidacl-test",
                          "",
                          ZOO_OPEN_ACL_UNSAFE) # Error - not a list


    def test_async_create(self):
        self.cv = threading.Condition()
        def callback(handle, rc, value):
            self.cv.acquire()
            self.callback_flag = True
            self.rc = rc
            self.cv.notify()
            self.cv.release()

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
        self.assertEqual(self.rc, zookeeper.OK)


if __name__ == '__main__':
    unittest.main()
