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

class AsyncTest(zktestbase.TestBase):
    """Test whether async works"""
    # to do: startup and teardown via scripts?
    def setUp( self ):
        zktestbase.TestBase.setUp(self)

    def test_async(self):
        self.assertEqual(self.connected, True)
        ret = zookeeper.async(self.handle, "/")
        self.assertEqual(ret, zookeeper.OK, "async failed")
        
if __name__ == '__main__':
    unittest.main()
