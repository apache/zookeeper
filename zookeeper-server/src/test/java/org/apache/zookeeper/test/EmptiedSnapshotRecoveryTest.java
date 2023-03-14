/*
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.List;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** If snapshots are corrupted to the empty file or deleted, Zookeeper should
 *  not proceed to read its transaction log files
 *  Test that zxid == -1 in the presence of emptied/deleted snapshots
 */
public class EmptiedSnapshotRecoveryTest extends ZKTestCase implements Watcher {

    private static final Logger LOG = LoggerFactory.getLogger(RestoreCommittedLogTest.class);
    private static String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;
    private static final int N_TRANSACTIONS = 150;
    private static final int SNAP_COUNT = 100;

    public void runTest(boolean leaveEmptyFile, boolean trustEmptySnap) throws Exception {
        File tmpSnapDir = ClientBase.createTmpDir();
        File tmpLogDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpSnapDir, tmpLogDir, 3000);
        SyncRequestProcessor.setSnapCount(SNAP_COUNT);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        ZooKeeper zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this);
        try {
            for (int i = 0; i < N_TRANSACTIONS; i++) {
                zk.create("/node-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        f.shutdown();
        zks.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server to shutdown");

        // start server again with intact database
        zks = new ZooKeeperServer(tmpSnapDir, tmpLogDir, 3000);
        zks.startdata();
        long zxid = zks.getZKDatabase().getDataTreeLastProcessedZxid();
        LOG.info("After clean restart, zxid = {}", zxid);
        assertTrue(zxid > 0, "zxid > 0");
        zks.shutdown();

        // Make all snapshots empty
        FileTxnSnapLog txnLogFactory = zks.getTxnLogFactory();
        List<File> snapshots = txnLogFactory.findNRecentSnapshots(10);
        assertTrue(snapshots.size() > 0, "We have a snapshot to corrupt");
        for (File file : snapshots) {
            if (leaveEmptyFile) {
                new PrintWriter(file).close();
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
                fail("Should have gotten exception for corrupted database");
            }
            assertEquals(currentZxid, zxid, "zxid mismatch after restoring database");
        } catch (IOException e) {
            // expected behavior
            if (trustEmptySnap) {
                fail("Should not get exception for empty database");
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

    @Test
    public void testRestoreWithTrustedEmptySnapFilesWhenFollowing() throws Exception {
        QuorumUtil qu = new QuorumUtil(1);
        try {
            qu.startAll();
            String connString = qu.getConnectionStringForServer(1);
            try (ZooKeeper zk = new ZooKeeper(connString, CONNECTION_TIMEOUT, this)) {
                for (int i = 0; i < N_TRANSACTIONS; i++) {
                    zk.create("/node-" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
            }
            int leaderIndex = qu.getLeaderServer();
            //Shut down the cluster and delete the snapshots from the followers
            for (int i = 1; i <= qu.ALL; i++) {
                qu.shutdown(i);
                if (i != leaderIndex) {
                    FileTxnSnapLog txnLogFactory = qu.getPeer(i).peer.getTxnFactory();
                    List<File> snapshots = txnLogFactory.findNRecentSnapshots(10);
                    assertTrue(snapshots.size() > 0, "We have a snapshot to corrupt");
                    for (File file : snapshots) {
                        Files.delete(file.toPath());
                    }
                    assertEquals(txnLogFactory.findNRecentSnapshots(10).size(), 0);
                }
            }
            //Start while trusting empty snapshots, verify that the followers save snapshots
            System.setProperty(FileTxnSnapLog.ZOOKEEPER_SNAPSHOT_TRUST_EMPTY, "true");
            qu.start(leaderIndex);
            for (int i = 1; i <= qu.ALL; i++) {
                if (i != leaderIndex) {
                    qu.restart(i);
                    FileTxnSnapLog txnLogFactory = qu.getPeer(i).peer.getTxnFactory();
                    List<File> snapshots = txnLogFactory.findNRecentSnapshots(10);
                    assertTrue(snapshots.size() > 0, "A snapshot should have been created on follower " + i);
                }
            }
            //Check that the created nodes are still there
            try (ZooKeeper zk = new ZooKeeper(connString, CONNECTION_TIMEOUT, this)) {
                for (int i = 0; i < N_TRANSACTIONS; i++) {
                    assertNotNull(zk.exists("/node-" + i, false));
                }
            }
        } finally {
            System.clearProperty(FileTxnSnapLog.ZOOKEEPER_SNAPSHOT_TRUST_EMPTY);
            qu.tearDown();
        }
    }

    public void process(WatchedEvent event) {
        // do nothing
    }

}
