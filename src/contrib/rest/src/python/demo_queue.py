#! /usr/bin/env python

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# This is a simple message queue built on top of ZooKeeper. In order
# to be used in production it needs better error handling but it's 
# still useful as a proof-of-concept. 

# Why use ZooKeeper as a queue? Highly available by design and has
# great performance.

import sys
import threading
import time

from zkrest import ZooKeeper

class Queue(object):
    def __init__(self, root, zk):
        self.root = root

        self.zk = zk

    def put(self, data):
        self.zk.create("%s/el-" % self.root, str(data), sequence=True, ephemeral=True)

        # creating ephemeral nodes for easy cleanup
        # in a real world scenario you should create
        # normal sequential znodes

    def fetch(self):
        """ Pull an element from the queue

        This function is not blocking if the queue is empty, it will
        just return None.
        """
        children = sorted(self.zk.get_children(self.root), \
            lambda a, b: cmp(a['path'], b['path']))

        if not children:
            return None

        try:
            first = children[0]
            self.zk.delete(first['path'], version=first['version'])
            if 'data64' not in first:
                return ''
            else:
                return first['data64'].decode('base64')

        except (ZooKeeper.WrongVersion, ZooKeeper.NotFound):
            # someone changed the znode between the get and delete
            # this should not happen
            # in practice you should retry the fetch
            raise
        

def main():
    zk = ZooKeeper()
    zk.start_session(expire=60)

    if not zk.exists('/queue'):
        zk.create('/queue')
    q = Queue('/queue', zk)

    print 'Pushing to queue 1 ... 5'
    map(q.put, [1,2,3,4,5])

    print 'Extracting ...'
    while True:
        el = q.fetch()
        if el is None:
            break
        print el    

    zk.close_session()
    zk.delete('/queue')

    print 'Done.'
   

if __name__ == '__main__':
    sys.exit(main())

