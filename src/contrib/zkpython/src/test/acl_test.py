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

ZOO_OPEN_ACL_UNSAFE = {"perms":zookeeper.PERM_ALL, "scheme":"world", "id" :"anyone"}
ZOO_ACL_READ = {"perms":zookeeper.PERM_READ, "scheme": "world",
                "id":"anyone"}
class ACLTest(zktestbase.TestBase):
    """Test whether basic ACL setting and getting work correctly"""
    # to do: startup and teardown via scripts?
    def setUp(self):
      zktestbase.TestBase.setUp(self)
      try:
        zookeeper.delete(self.handle, "/zk-python-acltest")
        zookeeper.delete(self.handle, "/zk-python-aacltest")
      except:
        pass

    def test_sync_acl(self):
      self.assertEqual(self.connected, True)
      ret = zookeeper.create(self.handle, "/zk-python-acltest", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
      acls = zookeeper.get_acl(self.handle, "/zk-python-acltest")
      self.assertEqual(acls[1], [ZOO_OPEN_ACL_UNSAFE])
      self.assertRaises(zookeeper.InvalidACLException,zookeeper.set_acl,self.handle, "/zk-python-acltest", -1, ZOO_ACL_READ)
      zookeeper.set_acl(self.handle, "/zk-python-acltest", -1, [ZOO_ACL_READ])
      acls = zookeeper.get_acl(self.handle, "/zk-python-acltest")
      self.assertEqual(acls[1], [ZOO_ACL_READ])


    def test_async_acl(self):
      self.cv = threading.Condition()
      self.cv = threading.Condition()
      def aget_callback(handle, rc, acl, stat):
        self.cv.acquire()
        self.callback_flag = True
        self.rc = rc
        self.acl = acl
        self.stat = stat
        self.cv.notify()
        self.cv.release()

      def aset_callback(handle, rc):
        self.cv.acquire()
        self.callback_flag = True
        self.rc = rc
        self.cv.notify()
        self.cv.release()

      self.assertEqual(self.connected, True, "Not connected!")
      ret = zookeeper.create(self.handle, "/zk-python-aacltest", "nodecontents", [ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)

      self.cv.acquire()
      zookeeper.aget_acl(self.handle, "/zk-python-aacltest", aget_callback)
      self.cv.wait(15)
      self.cv.release()

      self.assertEqual(self.callback_flag, True, "aget_acl timed out")
      self.assertEqual(self.rc, zookeeper.OK, "aget failed")
      self.assertEqual(self.acl, [ZOO_OPEN_ACL_UNSAFE], "Wrong ACL returned from aget")

      self.cv.acquire()
      self.callback_flag = False
      zookeeper.aset_acl(self.handle, "/zk-python-aacltest", -1, [ZOO_ACL_READ], aset_callback)
      self.cv.wait(15)
      self.cv.release()

      self.assertEqual(self.callback_flag, True, "aset_acl timed out")
      self.assertEqual(self.rc, zookeeper.OK, "aset failed")
      acls = zookeeper.get_acl(self.handle, "/zk-python-aacltest")
      self.assertEqual(acls[1], [ZOO_ACL_READ], "Wrong ACL returned from get when aset")

    def test_invalid_acl(self):
      self.assertRaises(zookeeper.InvalidACLException,
                        zookeeper.create,
                        self.handle,
                        "/zk-python-aclverifytest",
                        "",
                        None,
                        zookeeper.EPHEMERAL)
      
    def test_invalid_acl2(self):
      """Verify all required keys are present in the ACL."""
      invalid_acl = [{"schema": "digest", "id": "zebra"}]
      self.assertRaises(zookeeper.InvalidACLException,
                        zookeeper.create,
                        self.handle,
                        "/zk-python-aclverifytest",
                        "",
                        invalid_acl,
                        zookeeper.EPHEMERAL)

if __name__ == '__main__':
    unittest.main()
