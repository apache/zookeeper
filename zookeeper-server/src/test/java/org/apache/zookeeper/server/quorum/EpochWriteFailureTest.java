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
package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class EpochWriteFailureTest extends QuorumPeerTestBase {
    private static int SERVER_COUNT = 3;
    private static int[] clientPorts = new int[SERVER_COUNT];
    private static MainThread[] mt = new MainThread[SERVER_COUNT];
    private static ZooKeeper zk;

    /*
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2307
     * Expectation: During leader election when accepted epoch write to file
     * fails, it should not complete leader election, also it should not update
     * run time values of acceptedEpoch,
     */
    @Test
    @Timeout(value = 120)
    public void testAcceptedEpochWriteFailure() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("admin.enableServer=false");
        sb.append("\n");
        String server;
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":"
                    + PortAssignment.unique() + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server);
            sb.append("\n");
        }
        String currentQuorumCfgSection = sb.toString();
        for (int i = 0; i < SERVER_COUNT - 1; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            mt[i].start();
        }

        // ensure two servers started
        for (int i = 0; i < SERVER_COUNT - 1; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                    "waiting for server " + i + " being up");
        }

        CountdownWatcher watch1 = new CountdownWatcher();
        zk = new ZooKeeper("127.0.0.1:" + clientPorts[0], ClientBase.CONNECTION_TIMEOUT,
                watch1);
        watch1.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        String data = "originalData";
        zk.create("/epochIssue", data.getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        //initialize third server
        mt[2] = new MainThread(2, clientPorts[2], currentQuorumCfgSection, false) {

            @Override
            public TestQPMain getTestQPMain() {
                return new MockTestQPMain();
            }
        };

        //This server has problem it fails while writing acceptedEpoch.
        mt[2].start();

        /*
         * Verify that problematic server does not start as acceptedEpoch update
         * failure is injected and it keeps on trying to join the quorum
         */

        assertFalse(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[2], CONNECTION_TIMEOUT / 2),
                "verify server 2 not started");

        QuorumPeer quorumPeer = mt[2].getQuorumPeer();

        assertEquals(0, quorumPeer.getAcceptedEpoch(), "acceptedEpoch must not have changed");
        assertEquals(0, quorumPeer.getCurrentEpoch(), "currentEpoch must not have changed");
    }

    static class CustomQuorumPeer extends QuorumPeer {
        CustomQuorumPeer(Map<Long, QuorumServer> quorumPeers, File snapDir, File logDir, int clientPort,
                         int electionAlg, long myid, int tickTime, int initLimit, int syncLimit,
                         int connectToLearnerMasterLimit) throws IOException {
            super(quorumPeers, snapDir, logDir, clientPort, electionAlg, myid, tickTime, initLimit, syncLimit, connectToLearnerMasterLimit);
        }

        @Override
        protected void writeLongToFile(String name, long value) throws IOException {
            // initial epoch writing should be successful
            if (0 != value) {
                throw new IOException("Input/output error");
            }
        }
    }

    private static class MockTestQPMain extends TestQPMain {
        @Override
        public void runFromConfig(QuorumPeerConfig config)
                throws IOException {
            quorumPeer = new CustomQuorumPeer(config.getQuorumVerifier().getAllMembers(),
                    config.getDataDir(), config.getDataLogDir(),
                    config.getClientPortAddress().getPort(), config.getElectionAlg(),
                    config.getServerId(), config.getTickTime(), config.getInitLimit(),
                    config.getSyncLimit(), config.getSyncLimit());
            quorumPeer.start();
            try {
                quorumPeer.join();
            } catch (InterruptedException e) {
                LOG.warn("Quorum Peer interrupted", e);
            }
        }
    }

    @AfterAll
    public static void tearDownAfterClass() throws InterruptedException {
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (mt[i] != null) {
                mt[i].shutdown();
            }
        }
        if (zk != null) {
            zk.close();
        }
    }
}
