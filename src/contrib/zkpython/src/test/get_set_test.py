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

class GetSetTest(zktestbase.TestBase):
    def setUp( self ):
        zktestbase.TestBase.setUp(self)
        try:
            zookeeper.create(self.handle, "/zk-python-getsettest", "on",[ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
            zookeeper.create(self.handle, "/zk-python-agetsettest",
                             "on",[ZOO_OPEN_ACL_UNSAFE], zookeeper.EPHEMERAL)
        except:
            pass

    def test_sync_getset(self):
        self.assertEqual(self.connected, True, "Not connected!")
        (data,stat) = zookeeper.get(self.handle, "/zk-python-getsettest", None)
        self.assertEqual(data, "on", "Data is not 'on' as expected: " + data)
        ret = zookeeper.set(self.handle, "/zk-python-getsettest",
                            "off", stat["version"])
        (data,stat) = zookeeper.get(self.handle, "/zk-python-getsettest", None)
        self.assertEqual(data, "off", "Data is not 'off' as expected: " + data)
        self.assertRaises(zookeeper.BadVersionException,
                          zookeeper.set,
                          self.handle,
                          "/zk-python-getsettest",
                          "test",
                          stat["version"]+1)

    def test_stat_deleted_node(self):
        """
        Test for a bug that surfaced when trying to build a
        stat object from a non-existant node.

        """
        self.ensureDeleted("/zk-python-test-deleteme")
        self.assertRaises(zookeeper.NoNodeException,
                          zookeeper.get,
                          self.handle,
                          "/zk-python-test-deleteme")
        self.cv = threading.Condition()
        def callback(handle, rc, value, stat):
            self.cv.acquire()
            self.stat = stat
            self.rc = rc
            self.value = value
            self.callback_flag = True
            self.cv.notify()
            self.cv.release()
        self.cv.acquire()
        zookeeper.aget(self.handle, "/zk-python-test-deleteme", None, callback)
        self.cv.wait(15)
        self.assertEqual(self.callback_flag, True, "aget timed out!")
        self.assertEqual(self.stat, None, "Stat should be none!")
        self.assertEqual(self.value, None, "Value should be none!")

    def test_sync_get_large_datanode(self):
        """
        Test that we can retrieve datanode sizes up to
        1Mb with default parameters (depends on ZooKeeper server).
        """

        data = ''.join(["A" for x in xrange(1024*1023)])
        self.ensureDeleted("/zk-python-test-large-datanode")
        zookeeper.create(self.handle, "/zk-python-test-large-datanode", data,
                         [{"perms":0x1f, "scheme":"world", "id" :"anyone"}])
        (ret,stat) = zookeeper.get(self.handle, "/zk-python-test-large-datanode")
        self.assertEqual(len(ret), 1024*1023,
                         "Should have got 1Mb returned, instead got %s" % len(ret))
        (ret,stat) = zookeeper.get(self.handle, "/zk-python-test-large-datanode",None,500)
        self.assertEqual(len(ret), 500,
                         "Should have got 500 bytes returned, instead got %s" % len(ret))



    def test_async_getset(self):
        self.cv = threading.Condition()
        def get_callback(handle, rc, value, stat):
            self.cv.acquire()
            self.callback_flag = True
            self.rc = rc
            self.value = (value,stat)
            self.cv.notify()
            self.cv.release()

        def set_callback(handle, rc, stat):
            self.cv.acquire()
            self.callback_flag = True
            self.rc = rc
            self.value = stat
            self.cv.notify()
            self.cv.release()

        self.assertEqual(self.connected, True, "Not connected!")

        self.cv.acquire()
        self.callback_flag = False
        ret = zookeeper.aset(self.handle, "/zk-python-agetsettest", "off", -1, set_callback)
        self.assertEqual(ret, zookeeper.OK, "aset failed")
        while not self.callback_flag:
            self.cv.wait(15)
        self.cv.release()
        self.assertEqual(self.callback_flag, True, "aset timed out")

        self.cv.acquire()
        self.callback_flag = False
        ret = zookeeper.aget(self.handle, "/zk-python-agetsettest", None, get_callback)
        self.assertEqual(ret, zookeeper.OK, "aget failed")
        self.cv.wait(15)
        self.cv.release()
        self.assertEqual(self.callback_flag, True, "aget timed out")
        self.assertEqual(self.value[0], "off", "Data is not 'off' as expected: " + self.value[0])

    def test_sync_getchildren(self):
        self.ensureCreated("/zk-python-getchildrentest", flags=0)
        self.ensureCreated("/zk-python-getchildrentest/child")
        children = zookeeper.get_children(self.handle, "/zk-python-getchildrentest")
        self.assertEqual(len(children), 1, "Expected to find 1 child, got " + str(len(children)))

    def test_async_getchildren(self):
        self.ensureCreated("/zk-python-getchildrentest", flags=0)
        self.ensureCreated("/zk-python-getchildrentest/child")

        def gc_callback(handle, rc, children):
            self.cv.acquire()
            self.rc = rc
            self.children = children
            self.callback_flag = True
            self.cv.notify()
            self.cv.release()

        self.cv.acquire()
        self.callback_flag = False
        zookeeper.aget_children(self.handle, "/zk-python-getchildrentest", None, gc_callback)
        self.cv.wait(15)
        self.assertEqual(self.callback_flag, True, "aget_children timed out")
        self.assertEqual(self.rc, zookeeper.OK, "Return code for aget_children was not OK - %s" % zookeeper.zerror(self.rc))
        self.assertEqual(len(self.children), 1, "Expected to find 1 child, got " + str(len(self.children)))


if __name__ == '__main__':
    unittest.main()
