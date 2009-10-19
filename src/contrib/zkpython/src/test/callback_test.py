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

import zookeeper, zktestbase, unittest, threading, gc

ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"}

class CallbackTest(zktestbase.TestBase):
    """
    Test whether callbacks (watchers/completions) are correctly invoked
    """
    # to do: startup and teardown via scripts?
    def setUp(self):
        zktestbase.TestBase.setUp(self)
        self.cv = threading.Condition()

    def create_callback(self, callback):
        """
        Returns a callable which signals cv and then calls callback
        """
        def wrapper(*args, **kwargs):
            self.cv.acquire()
            callback(*args, **kwargs)
            self.cv.notify()
            self.cv.release()
        return wrapper

    def test_none_callback(self):
        """
        Test that no errors are raised when None is passed as a callback.
        """
        self.ensureCreated("/zk-python-none-callback-test","test")
        # To do this we need to issue two operations, waiting on the second
        # to ensure that the first completes
        zookeeper.get(self.handle, "/zk-python-none-callback-test", None)
        (d,s) = zookeeper.get(self.handle, "/zk-python-none-callback-test")
        self.assertEqual(d, "test")

    def callback_harness(self, trigger, test):
        self.callback_flag = False
        self.cv.acquire()
        trigger()
        self.cv.wait(15)
        test()

    def test_dispatch_types(self):
        """
        Test all the various dispatch mechanisms internal to the module.
        """
        def dispatch_callback(*args, **kwargs):
            self.callback_flag = True
        self.ensureCreated("/zk-python-dispatch-test")
        self.callback_harness( lambda: zookeeper.adelete(self.handle,
                                                            "/zk-python-dispatch-test",
                                                            -1,
                                                            self.create_callback(dispatch_callback)),
                                  lambda: self.assertEqual(True, self.callback_flag, "Void dispatch not fired"))


        self.ensureCreated("/zk-python-dispatch-test")
        self.callback_harness( lambda: zookeeper.aexists(self.handle,
                                                         "/zk-python-dispatch-test",
                                                         None,
                                                         self.create_callback(dispatch_callback)),
                               lambda: self.assertEqual(True, self.callback_flag, "Stat dispatch not fired"))

        self.callback_harness( lambda: zookeeper.aget(self.handle,
                                                      "/zk-python-dispatch-test",
                                                      None,
                                                      self.create_callback(dispatch_callback)),
                               lambda: self.assertEqual(True, self.callback_flag, "Data dispatch not fired"))

        self.callback_harness( lambda: zookeeper.aget_children(self.handle,
                                                               "/",
                                                               None,
                                                               self.create_callback( dispatch_callback )),
                               lambda: self.assertEqual(True, self.callback_flag, "Strings dispatch not fired"))

        self.callback_harness( lambda: zookeeper.async(self.handle,
                                                       "/",
                                                       self.create_callback( dispatch_callback )),
                               lambda: self.assertEqual(True, self.callback_flag, "String dispatch not fired"))

        self.callback_harness( lambda: zookeeper.aget_acl(self.handle,
                                                          "/",
                                                          self.create_callback( dispatch_callback )),
                               lambda: self.assertEqual(True, self.callback_flag, "ACL dispatch not fired"))

    def test_multiple_watchers(self):
        """
        Test whether multiple watchers are correctly called
        """
        cv1, cv2 = threading.Condition(), threading.Condition()
        def watcher1(*args, **kwargs):
            cv1.acquire()
            self.watcher1 = True
            cv1.notify()
            cv1.release()

        def watcher2(*args, **kwargs):
            cv2.acquire()
            self.watcher2 = True
            cv2.notify()
            cv2.release()

        nodename = "/zk-python-multiple-watcher-test"
        self.ensureCreated(nodename, "test")
        cv1.acquire()
        cv2.acquire()
        zookeeper.get(self.handle, nodename, watcher1)
        zookeeper.get(self.handle, nodename, watcher2)
        zookeeper.set(self.handle, nodename, "test")
        cv1.wait(15)
        cv2.wait(15)
        self.assertTrue(self.watcher1 and self.watcher2, "One or more watchers failed to fire")

    def test_lose_scope(self):
        """
        The idea is to test that the reference counting doesn't
        fail when we retain no references outside of the module
        """
        self.ensureDeleted("/zk-python-lose-scope-test")
        self.ensureCreated("/zk-python-lose-scope-test")
        def set_watcher():
            def fn(): self.callback_flag = True
            self.callback_flag = False
            zookeeper.exists(self.handle, "/zk-python-lose-scope-test",
                             self.create_callback( lambda handle, type, state, path: fn() )
                             )

        set_watcher()
        gc.collect()
        self.cv.acquire()
        zookeeper.set(self.handle, "/zk-python-lose-scope-test", "test")
        self.cv.wait(15)
        self.assertEqual(self.callback_flag, True)


if __name__ == '__main__':
    unittest.main()
