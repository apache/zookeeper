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

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.util.Collection;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** After a replica starts, it should load commits in its committedLog list.
 *  This test checks if committedLog != 0 after replica restarted.
 */
public class RestoreCommittedLogTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(RestoreCommittedLogTest.class);
    private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private static final int CONNECTION_TIMEOUT = 3000;

    /**
     * Verify the logs can be used to restore when they are rolled
     * based on the size of the transactions received
     *
     * @throws Exception
     */
    @Test
    public void testRestoreCommittedLogWithSnapSize() throws Exception {
        final int minExpectedSnapshots = 5;
        final int minTxnsToSnap = 256;
        final int numTransactions = minExpectedSnapshots * minTxnsToSnap;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4 * 1024; i++) {
            sb.append("0");
        }
        final byte[] data = sb.toString().getBytes();

        SyncRequestProcessor.setSnapCount(numTransactions * 1000 /* just some high number */);
        // The test breaks if this number is less than the smallest size file
        // created on the system, as revealed through File::length.
        // Setting to about 1 Mb.
        SyncRequestProcessor.setSnapSizeInBytes(minTxnsToSnap * data.length);

        testRestoreCommittedLog(numTransactions, data, minExpectedSnapshots);

    }

    /**
     * Verify the logs can be used to restore when they are rolled
     * based on the number of transactions received
     *
     * @throws Exception
     */
    @Test
    public void testRestoreCommittedLogWithSnapCount() throws Exception {
        final int minExpectedSnapshots = 30;
        final int snapCount = 100;

        SyncRequestProcessor.setSnapCount(snapCount);
        SyncRequestProcessor.setSnapSizeInBytes(4294967296L);

        testRestoreCommittedLog(minExpectedSnapshots * snapCount, new byte[0], minExpectedSnapshots);
    }

    /**
     * test the purge
     * @throws Exception an exception might be thrown here
     */
    private void testRestoreCommittedLog(int totalTransactions, byte[] data, int minExpectedSnapshots) throws Exception {
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server being up ");
        ZooKeeper zk = ClientBase.createZKClient(HOSTPORT);
        try {
            for (int i = 0; i < totalTransactions; i++) {
                zk.create("/invalidsnap-" + i, data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
        final int numSnaps = zks.getTxnLogFactory().findNRecentSnapshots(10 * minExpectedSnapshots).size();
        LOG.info("number of snapshots taken {}", numSnaps);

        f.shutdown();
        zks.shutdown();
        assertTrue(ClientBase.waitForServerDown(HOSTPORT, CONNECTION_TIMEOUT), "waiting for server to shutdown");

        assertTrue(numSnaps > minExpectedSnapshots, "too few snapshot files");
        assertTrue(numSnaps <= minExpectedSnapshots * 2, "too many snapshot files");

        // start server again
        zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        zks.startdata();
        Collection<Proposal> committedLog = zks.getZKDatabase().getCommittedLog();
        int logsize = committedLog.size();
        LOG.info("committedLog size = {}", logsize);
        assertTrue((logsize != 0), "log size != 0");
        zks.shutdown();
    }

}
