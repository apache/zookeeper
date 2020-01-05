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

package org.apache.zookeeper.test;

import static org.junit.Assert.assertEquals;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.Assert;
import org.junit.Test;

/** If snapshots are corrupted to the empty file or deleted, Zookeeper should 
 *  not proceed to read its transaction log files
 *  Test that zxid == -1 in the presence of emptied/deleted snapshots
 */
public class EmptiedSnapshotRecoveryTest extends ZKTestCase implements  Watcher {
    private static final Logger LOG = Logger.getLogger(RestoreCommittedLogTest.class);
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;
    private static final int N_TRANSACTIONS = 150;
    private static final int SNAP_COUNT = 100;

    public void runTest(boolean leaveEmptyFile, boolean trustEmptySnap) throws Exception {
        File tmpSnapDir = ClientBase.createTmpDir();
        File tmpLogDir  = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpSnapDir, tmpLogDir, 3000);
        SyncRequestProcessor.setSnapCount(SNAP_COUNT);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(HOSTPORT,CONNECTION_TIMEOUT));
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
        try {
            for (int i = 0; i< N_TRANSACTIONS; i++) {
                zk.create("/node-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        zks.shutdown();
        Assert.assertTrue("waiting for server to shutdown",
                ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT));

        // start server again with intact database
        zks = new ZooKeeperServer(tmpSnapDir, tmpLogDir, 3000);
        zks.startdata();
        long zxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
        LOG.info("After clean restart, zxid = " + zxid);
        Assert.assertTrue("zxid > 0", zxid > 0);
        zks.shutdown();

        // Make all snapshots empty
        FileTxnSnapLog txnLogFactory = zks.getTxnLogFactory();
        List<File> snapshots = txnLogFactory.findNRecentSnapshots(10);
        Assert.assertTrue("We have a snapshot to corrupt", snapshots.size() > 0);
        for (File file: snapshots) {
            if (leaveEmptyFile) {
                new PrintWriter(file).close ();
            } else {
                file.delete();
            }
        }

        if (trustEmptySnap) {
          System.setProperty(FileTxnSnapLog.ZOOKEEPER_SNAPSHOT_TRUST_EMPTY, "true");
        }

        // start server again with corrupted database
        zks = new ZooKeeperServer(tmpSnapDir, tmpLogDir, 3000);
        try {
            zks.startdata();
            long currentZxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
            if (!trustEmptySnap) {
                Assert.fail("Should have gotten exception for corrupted database");
            }
            assertEquals("zxid mismatch after restoring database", currentZxid, zxid);
        } catch (IOException e) {
            // expected behavior
            if (trustEmptySnap) {
                Assert.fail("Should not get exception for empty database");
            }
        } finally {
            if (trustEmptySnap) {
              System.clearProperty(FileTxnSnapLog.ZOOKEEPER_SNAPSHOT_TRUST_EMPTY);
            }
        }

        zks.shutdown();
    }

    /**
     * Test resilience to empty Snapshots
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testRestoreWithEmptySnapFiles() throws Exception {
        runTest(true, false);
    }

    /**
     * Test resilience to deletion of Snapshots
     * @throws Exception an exception might be thrown here
     */
    @Test
    public void testRestoreWithNoSnapFiles() throws Exception {
        runTest(false, false);
    }

    @Test
    public void testRestoreWithTrustedEmptySnapFiles() throws Exception {
        runTest(false, true);
    }

    public void process(WatchedEvent event) {
        // do nothing
    }

}
