#!/usr/bin/env python2.6
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
""" ZNode Change Watcher Skeleton Script

This script shows you how to write a python program that watches a specific 
znode for changes and reacts to them.

Steps to understand how this script works:

1. start a standalone ZooKeeper server (by default it listens on localhost:2181)

Did you know you can deploy "local clusters" by using zkconf[1]? 
[1] http://github.com/phunt/zkconf

2. enter the command line console

3. create the test node:
    [zk: (CONNECTED) 1] create /watch-test dummy-data 
    Created /watch-test

4. in another shell start this script in verbose mode
    $ python watch_znode_for_changes.py -v 

    # you should see a lot of log messages. have a look over them because
    # you can easily understand how zookeeper works

5. update the node data:

    [zk: (CONNECTED) 2] set /watch-test new-data 
    cZxid = 0xa0000001a
    ctime = Fri Jul 09 19:14:45 EEST 2010
    mZxid = 0xa0000001e
    mtime = Fri Jul 09 19:18:18 EEST 2010
    pZxid = 0xa0000001a
    cversion = 0
    dataVersion = 1
    aclVersion = 0
    ephemeralOwner = 0x0
    dataLength = 8
    numChildren = 0

    ... and you should see similar log messages:

    2010-07-09 19:18:18,537:11542(0xb6ea5b70):ZOO_DEBUG@process_completions@1765: Calling a watcher for node [/watch-test], type = -1 event=ZOO_CHANGED_EVENT
    2010-07-09 19:18:18,537 watch_znode_for_changes.py:83 - Running watcher: zh=0 event=3 state=3 path=/watch-test
    2010-07-09 19:18:18,537:11542(0xb6ea5b70):ZOO_DEBUG@zoo_awget@2400: Sending request xid=0x4c374b33 for path [/watch-test] to 127.0.0.1:2181
    2010-07-09 19:18:18,545:11542(0xb76a6b70):ZOO_DEBUG@zookeeper_process@1980: Queueing asynchronous response
    2010-07-09 19:18:18,545:11542(0xb6ea5b70):ZOO_DEBUG@process_completions@1772: Calling COMPLETION_DATA for xid=0x4c374b33 rc=0
    2010-07-09 19:18:18,545 watch_znode_for_changes.py:54 - This is where your application does work.

    You can repeat this step multiple times. 

6. that's all. in the end you can delete the node and you should see a ZOO_DELETED_EVENT

"""

import logging
import logging.handlers
import signal
import sys
import time
import threading
import zookeeper

from optparse import OptionParser

logger = logging.getLogger()

class MyClass(threading.Thread):
  znode = '/watch-test'

  def __init__(self, options, args):
    threading.Thread.__init__(self)

    logger.debug('Initializing MyClass thread.')
    if options.verbose:
      zookeeper.set_debug_level(zookeeper.LOG_LEVEL_DEBUG)

    self.zh = zookeeper.init(options.servers)
    if zookeeper.OK != zookeeper.aget(self.zh, self.znode,
                                      self.watcher, self.handler):
      logger.critical('Unable to get znode! Exiting.')
      sys.exit(1)

  def __del__(self):
    zookeeper.close(self.zh)

  def aget(self):
    return zookeeper.aget(self.zh, self.znode, self.watcher, self.handler)

  def handler(self, zh, rc, data, stat):
    """Handle zookeeper.aget() responses.

    This code handles the zookeeper.aget callback. It does not handle watches.

    Numeric arguments map to constants. See ``DATA`` in ``help(zookeeper)``
    for more information.

    Args:
      zh Zookeeper handle that made this request.
      rc Return code.
      data Data stored in the znode.

    Does not provide a return value.
    """
    if zookeeper.OK == rc:
      logger.debug('This is where your application does work.')
    else:
      if zookeeper.NONODE == rc:
        # avoid sending too many requests if the node does not yet exists
        logger.info('Node not found. Trying again to set the watch.')
        time.sleep(1)
 
      if zookeeper.OK != self.aget():
        logger.critical('Unable to get znode! Exiting.')
        sys.exit(1)

  def watcher(self, zh, event, state, path):
    """Handle zookeeper.aget() watches.

    This code is called when a znode changes and triggers a data watch.
    It is not called to handle the zookeeper.aget call itself.

    Numeric arguments map to constants. See ``DATA`` in ``help(zookeeper)``
    for more information.

    Args:
      zh Zookeeper handle that set this watch.
      event Event that caused the watch (often called ``type`` elsewhere).
      state Connection state.
      path Znode that triggered this watch.

    Does not provide a return value.
    """
    out = ['Running watcher:',
           'zh=%d' % zh,
           'event=%d' % event,
           'state=%d' % state,
           'path=%s' % path]
    logger.debug(' '.join(out))
    if event == zookeeper.CHANGED_EVENT and \
       state == zookeeper.CONNECTED_STATE and \
       self.znode == path:
      if zookeeper.OK != self.aget():
        logger.critical('Unable to get znode! Exiting.')
        sys.exit(1)

  def run(self):
    while True:
      time.sleep(86400)


def main(argv=None):
  # Allow Ctrl-C
  signal.signal(signal.SIGINT, signal.SIG_DFL)

  parser = OptionParser()
  parser.add_option('-v', '--verbose',
    dest='verbose',
    default=False,
    action='store_true',
    help='Verbose logging. (default: %default)')
  parser.add_option('-s', '--servers',
    dest='servers',
    default='localhost:2181',
    help='Comma-separated list of host:port pairs. (default: %default)')

  (options, args) = parser.parse_args()

  if options.verbose:
    logger.setLevel(logging.DEBUG)
  else:
    logger.setLevel(logging.INFO)

  formatter = logging.Formatter("%(asctime)s %(filename)s:%(lineno)d - %(message)s")
  stream_handler = logging.StreamHandler()
  stream_handler.setFormatter(formatter)
  logger.addHandler(stream_handler)

  logger.info('Starting Zookeeper python example: %s' % ' '.join(sys.argv))

  mc = MyClass(options, args)
  mc.start()
  mc.join()


if __name__ == '__main__':
  main()
