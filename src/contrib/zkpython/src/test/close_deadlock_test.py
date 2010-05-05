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
import time


class CloseDeadlockTest(zktestbase.TestBase):
  """
  This tests for the issue found in
  https://issues.apache.org/jira/browse/ZOOKEEPER-763

  zookeeper.close blocks on waiting for all completions to
  finish. Previously it was doing so while holding teh GIL, stopping
  any completions from actually continuing.

  This test is a failure if it does not exit within a few seconds.
  """
  def deadlock():
    cv = threading.Condition()

    def callback(*args):
        cv.acquire()
        cv.notifyAll()
        cv.release()
        time.sleep(1)

    cv.acquire()
    zookeeper.aget(handle, "/", None, callback)
    cv.wait()
    zookeeper.close(handle)


if __name__ == '__main__':
  unittest.main()
