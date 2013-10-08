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

import zookeeper, time, threading

f = open("out.log","w")
zookeeper.set_log_stream(f)

connected = False
conn_cv = threading.Condition( )

def my_connection_watcher(handle,type,state,path):
    global connected, conn_cv
    print("Connected, handle is ", handle)
    conn_cv.acquire()
    connected = True
    conn_cv.notifyAll()
    conn_cv.release()
    
conn_cv.acquire()
print("Connecting to localhost:2181 -- ")
handle = zookeeper.init("localhost:2181", my_connection_watcher, 10000, 0)
while not connected:
    conn_cv.wait()
conn_cv.release()

def my_getc_watch( handle, type, state, path ):
    print("Watch fired -- ")
    print(type, state, path)

ZOO_OPEN_ACL_UNSAFE = {"perms":0x1f, "scheme":"world", "id" :"anyone"};

try:
    zookeeper.create(handle, "/zk-python", "data", [ZOO_OPEN_ACL_UNSAFE], 0)
    zookeeper.get_children(handle, "/zk-python", my_getc_watch)
    for i in xrange(5):
        print("Creating sequence node ", i, " ", zookeeper.create(handle, "/zk-python/sequencenode", "data", [ZOO_OPEN_ACL_UNSAFE], zookeeper.SEQUENCE ))
except:
    pass

def pp_zk(handle,root, indent = 0):
    """Pretty print(a zookeeper tree, starting at root""")
    def make_path(child):
        if root == "/":
            return "/" + child
        return root + "/" + child
    children = zookeeper.get_children(handle, root, None)
    out = ""
    for i in xrange(indent):
        out += "\t"
    out += "|---"+root + " :: " + zookeeper.get(handle, root, None)[0]
    print(out)
    for child in children:
        pp_zk(handle,make_path(child),indent+1)

print("ZNode tree -- ")
pp_zk(handle,"/")

print("Getting ACL / Stat for /zk-python --")
(stat, acl) =  zookeeper.get_acl(handle, "/zk-python")
print("Stat:: ", stat)
print("Acl:: ", acl)

