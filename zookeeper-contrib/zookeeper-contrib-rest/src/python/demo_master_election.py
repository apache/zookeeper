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

import sys
import threading
import time

from zkrest import ZooKeeper

class Agent(threading.Thread):
    """ A basic agent that wants to become a master and exit """

    root = '/election'

    def __init__(self, id):
        super(Agent, self).__init__()
        self.zk = ZooKeeper()
        self.id = id

    def run(self):
        print 'Starting #%s' % self.id
        with self.zk.session(expire=5):

            # signal agent presence
            r = self.zk.create("%s/agent-" % self.root, 
                sequence=True, ephemeral=True)
            self.me = r['path']

            while True:
                children = sorted([el['path'] \
                    for el in self.zk.get_children(self.root)])
                master, previous = children[0], None
                try:
                    index = children.index(self.me)
                    if index != 0:
                        previous = children[index-1]
                except ValueError:
                    break

                if previous is None:
                    self.do_master_work()
                    # and don't forget to send heartbeat messages
                    break
                else:
                    # do slave work in another thread
                    pass
               
                # wait for the previous agent or current master to exit / finish
                while self.zk.exists(previous) or self.zk.exists(master):
                    time.sleep(0.5)
                    self.zk.heartbeat()

                # TODO signal the slave thread to exit and wait for it
                # and rerun the election loop

    def do_master_work(self):
        print "#%s: I'm the master: %s" % (self.id, self.me) 
            
def main():
    zk = ZooKeeper()

    # create the root node used for master election
    if not zk.exists('/election'):
        zk.create('/election')

    print 'Starting 10 agents ...'
    agents = [Agent(id) for id in range(0,15)]

    map(Agent.start, agents)
    map(Agent.join, agents)

    zk.delete('/election')    

if __name__ == '__main__':
    sys.exit(main())
