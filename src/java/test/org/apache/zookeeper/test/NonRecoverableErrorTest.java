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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.Assert;
import org.junit.Test;

/**
 * This class tests the non-recoverable error behavior of quorum server.
 */
public class NonRecoverableErrorTest extends QuorumPeerTestBase {
    private static final String NODE_PATH = "/noLeaderIssue";

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2247.
     * Test to verify that even after non recoverable error (error while
     * writing transaction log), ZooKeeper is still available.
     */
    @Test(timeout = 30000)
    public void testZooKeeperServiceAvailableOnLeader() throws Exception {
        int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique() + ":participant;127.0.0.1:"
                    + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread mt[] = new MainThread[SERVER_COUNT];

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection,
                    false);
            mt[i].start();
        }

        // ensure server started
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[0],
                ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        String data = "originalData";
        zk.create(NODE_PATH, data.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // get information of current leader
        QuorumPeer leader = getLeaderQuorumPeer(mt);
        assertNotNull("Leader must have been elected by now", leader);

        // inject problem in leader
        FileTxnSnapLog snapLog = leader.getActiveServer().getTxnLogFactory();
        FileTxnSnapLog fileTxnSnapLogWithError = new FileTxnSnapLog(
                snapLog.getDataDir(), snapLog.getSnapDir()) {
            @Override
            public void commit() throws IOException {
                throw new IOException("Input/output error");
            }
        };
        ZKDatabase originalZKDatabase = leader.getActiveServer()
                .getZKDatabase();
        long leaderCurrentEpoch = leader.getCurrentEpoch();

        ZKDatabase newDB = new ZKDatabase(fileTxnSnapLogWithError);
        leader.getActiveServer().setZKDatabase(newDB);

        try {
            // do create operation, so that injected IOException is thrown
            zk.create(uniqueZnode(), data.getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            fail("IOException is expected due to error injected to transaction log commit");
        } catch (Exception e) {
            // do nothing
        }

        // resetting watcher so that this watcher can be again used to ensure
        // that the zkClient is able to re-establish connection with the
        // newly elected zookeeper quorum.
        watcher.reset();
        waitForNewLeaderElection(leader, leaderCurrentEpoch);

        // ensure server started, give enough time, so that new leader election
        // takes place
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        // revert back the error
        leader.getActiveServer().setZKDatabase(originalZKDatabase);

        // verify that now ZooKeeper service is up and running
        leader = getLeaderQuorumPeer(mt);
        assertNotNull("New leader must have been elected by now", leader);

        String uniqueNode = uniqueZnode();
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        String createNode = zk.create(uniqueNode, data.getBytes(),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        // if node is created successfully then it means that ZooKeeper service
        // is available
        assertEquals("Failed to create znode", uniqueNode, createNode);
        zk.close();
        // stop all severs
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    private void waitForNewLeaderElection(QuorumPeer peer,
            long leaderCurrentEpoch) throws IOException, InterruptedException {
        LOG.info("Waiting for new LE cycle..");
        int count = 100; // giving a grace period of 10seconds
        while (count > 0) {
            if (leaderCurrentEpoch == peer.getCurrentEpoch()) {
                Thread.sleep(100);
            }
            count--;
        }
        Assert.assertNotEquals("New LE cycle must have triggered",
                leaderCurrentEpoch, peer.getCurrentEpoch());
    }

    private QuorumPeer getLeaderQuorumPeer(MainThread[] mt) {
        for (int i = mt.length - 1; i >= 0; i--) {
            QuorumPeer quorumPeer = mt[i].getQuorumPeer();
            if (null != quorumPeer
                    && ServerState.LEADING == quorumPeer.getPeerState()) {
                return quorumPeer;
            }
        }
        return null;
    }

    private String uniqueZnode() {
        UUID randomUUID = UUID.randomUUID();
        String node = NODE_PATH + "/" + randomUUID.toString();
        return node;
    }
}
