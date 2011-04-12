/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.File;
import java.io.RandomAccessFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * this test checks that the server works
 * even if the last snapshot is invalidated
 * by corruption or if the server crashes
 * while generating the snapshot.
 */
public class InvalidSnapshotTest extends ZKTestCase implements Watcher {
    private static final Logger LOG =
        LoggerFactory.getLogger(InvalidSnapshotTest.class);

    private static final String HOSTPORT =
        "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;

    /**
     * this test does the main work of testing
     * an invalid snapshot
     * @throws Exception
     */
    @Test
    public void testInvalidSnapshot() throws Exception {
       File tmpDir = ClientBase.createTmpDir();
       ClientBase.setupTestEnv();
       ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
       SyncRequestProcessor.setSnapCount(100);
       final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
       ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
       f.startup(zks);
       Assert.assertTrue("waiting for server being up ",
               ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
       ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
       try {
           for (int i=0; i< 2000; i++) {
               zk.create("/invalidsnap-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                       CreateMode.PERSISTENT);
           }
       } finally {
           zk.close();
       }
       f.shutdown();
       Assert.assertTrue("waiting for server to shutdown",
               ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
       // now corrupt the snapshot
       File snapFile = zks.getTxnLogFactory().findMostRecentSnapshot();
       RandomAccessFile raf = new RandomAccessFile(snapFile, "rws");
       raf.setLength(3);
       raf.close();
       // now restart the server and see if it starts
       zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
       SyncRequestProcessor.setSnapCount(100);
       f = ServerCnxnFactory.createFactory(PORT, -1);
       f.startup(zks);
       Assert.assertTrue("waiting for server being up ",
               ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
       // the server should come up
       zk = new ZooKeeper(HOSTPORT, 20000, this);
       try {
           Assert.assertTrue("the node should exist",
                   (zk.exists("/invalidsnap-1999", false) != null));
           f.shutdown();
           Assert.assertTrue("waiting for server to shutdown",
                   ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
       } finally {
           zk.close();
       }

       f.shutdown();
       Assert.assertTrue("waiting for server to shutdown",
               ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));
    }

    public void process(WatchedEvent event) {
        // do nothing for now
    }

}
