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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import javax.security.sasl.SaslException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class DIFFSyncConsistencyTest extends QuorumPeerTestBase {

    private static int SERVER_COUNT = 3;
    private MainThread[] mt = new MainThread[SERVER_COUNT];

    @Test
    @Timeout(value = 120)
    public void testInconsistentDueToUncommittedLog() throws Exception {
        final int LEADER_TIMEOUT_MS = 10_000;
        final int[] clientPorts = new int[SERVER_COUNT];

        StringBuilder sb = new StringBuilder();
        String server;
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique()
                    + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();

        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false) {
                @Override
                public TestQPMain getTestQPMain() {
                    return new MockTestQPMain();
                }
            };
            mt[i].start();
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                    "waiting for server " + i + " being up");
        }

        int leader = findLeader(mt);
        CountdownWatcher watch = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[leader], ClientBase.CONNECTION_TIMEOUT, watch);
        watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        Map<Long, Proposal> outstanding = mt[leader].main.quorumPeer.leader.outstandingProposals;
        // Increase the tick time to delay the leader going to looking to allow us proposal a transaction while other
        // followers are offline.
        int previousTick = mt[leader].main.quorumPeer.tickTime;
        mt[leader].main.quorumPeer.tickTime = LEADER_TIMEOUT_MS;
        // Let the previous tick on the leader exhaust itself so the new tick time takes effect
        Thread.sleep(previousTick);

        LOG.info("LEADER ELECTED {}", leader);

        // Shutdown followers to make sure we don't accidentally send the proposal we are going to make to follower.
        // In other words, we want to make sure the followers get the proposal later through DIFF sync.
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != leader) {
                mt[i].shutdown();
            }
        }

        // Send a create request to old leader and make sure it's synced to disk.
        try {
            zk.create("/zk" + leader, "zk".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("create /zk" + leader + " should have failed");
        } catch (KeeperException e) {
        }

        // Make sure that we actually did get it in process at the leader; there can be extra sessionClose proposals.
        assertTrue(outstanding.size() > 0);
        Proposal p = findProposalOfType(outstanding, OpCode.create);
        LOG.info("Old leader id: {}. All proposals: {}", leader, outstanding);
        assertNotNull(p, "Old leader doesn't have 'create' proposal");

        // Make sure leader sync the proposal to disk.
        int sleepTime = 0;
        Long longLeader = (long) leader;
        while (!p.qvAcksetPairs.get(0).getAckset().contains(longLeader)) {
            if (sleepTime > 2000) {
                fail("Transaction not synced to disk within 1 second " + p.qvAcksetPairs.get(0).getAckset() + " expected " + leader);
            }
            Thread.sleep(100);
            sleepTime += 100;
        }

        // Start controlled followers where we deliberately make the follower fail once follower receive the UPTODATE
        // message from leader. Because followers only persist proposals from DIFF sync after UPTODATE, this can
        // deterministically simulate the situation where followers ACK NEWLEADER (which makes leader think she has the
        // quorum support, but actually not afterwards) but immediately fail afterwards without persisting the proposals
        // from DIFF sync.
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i == leader) {
                continue;
            }

            mt[i].start();
            int sleepCount = 0;
            while (mt[i].getQuorumPeer() == null) {
                ++sleepCount;
                if (sleepCount > 100) {
                    fail("Can't start follower " + i + " !");
                }
                Thread.sleep(100);
            }

            ((CustomQuorumPeer) mt[i].getQuorumPeer()).setInjectError(true);
            LOG.info("Follower {} started.", i);
        }

        // Verify leader can see it. The fact that leader can see it implies that
        // leader should, at this point in time, get a quorum of ACK of NEWLEADER
        // from two followers so leader can start serving requests; this also implies
        // that DIFF sync from leader to followers are finished at this point in time.
        // We then verify later that followers should have the same view after we shutdown
        // this leader, otherwise it's a violation of ZAB / sequential consistency.
        int c = 0;
        while (c < 100) {
            ++c;
            try {
                Stat stat = zk.exists("/zk" + leader, false);
                assertNotNull(stat, "server " + leader + " should have /zk");
                break;
            } catch (KeeperException.ConnectionLossException e) {

            }
            Thread.sleep(100);
        }

        // Shutdown all servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
        waitForOne(zk, States.CONNECTING);

        // Now restart all servers except the old leader. Only old leader has the transaction sync to disk.
        // The old followers only had in memory view of the transaction, and they didn't have a chance
        // to sync to disk because we made them fail at UPTODATE.
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i == leader) {
                continue;
            }
            mt[i].start();
            int sleepCount = 0;
            while (mt[i].getQuorumPeer() == null) {
                ++sleepCount;
                if (sleepCount > 100) {
                    fail("Can't start follower " + i + " !");
                }
                Thread.sleep(100);
            }

            ((CustomQuorumPeer) mt[i].getQuorumPeer()).setInjectError(false);
            LOG.info("Follower {} started again.", i);
        }

        int newLeader = findLeader(mt);
        assertNotEquals(newLeader, leader, "new leader is still the old leader " + leader + " !!");

        // This simulates the case where clients connected to the old leader had a view of the data
        // "/zkX", but clients connect to the new leader does not have the same view of data (missing "/zkX").
        // This inconsistent view of the quorum exposed from leaders is a violation of ZAB.
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (i != newLeader) {
                continue;
            }
            zk.close();
            zk = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, watch);
            watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
            Stat val = zk.exists("/zk" + leader, false);
            assertNotNull(val, "Data inconsistency detected! "
                    + "Server " + i + " should have a view of /zk" + leader + "!");
        }

        zk.close();
    }

    @AfterEach
    public void tearDown() {
        for (int i = 0; i < mt.length; i++) {
            try {
                mt[i].shutdown();
            } catch (InterruptedException e) {
                LOG.warn("Quorum Peer interrupted while shutting it down", e);
            }
        }
    }

    static class CustomQuorumPeer extends QuorumPeer {

        private volatile boolean injectError = false;

        public CustomQuorumPeer() throws SaslException {

        }

        @Override
        protected Follower makeFollower(FileTxnSnapLog logFactory) throws IOException {
            return new Follower(this, new FollowerZooKeeperServer(logFactory, this, this.getZkDb())) {

                @Override
                void readPacket(QuorumPacket pp) throws IOException {
                    /**
                     * In real scenario got SocketTimeoutException while reading
                     * the packet from leader because of network problem, but
                     * here throwing SocketTimeoutException based on whether
                     * error is injected or not
                     */
                    super.readPacket(pp);
                    if (injectError && pp.getType() == Leader.UPTODATE) {
                        String type = LearnerHandler.packetToString(pp);
                        throw new SocketTimeoutException("Socket timeout while reading the packet for operation "
                                + type);
                    }
                }

            };
        }

        public void setInjectError(boolean injectError) {
            this.injectError = injectError;
        }

    }

    static class MockTestQPMain extends TestQPMain {

        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new CustomQuorumPeer();
        }

    }

    private Proposal findProposalOfType(Map<Long, Proposal> proposals, int type) {
        for (Proposal proposal : proposals.values()) {
            if (proposal.request.getHdr().getType() == type) {
                return proposal;
            }
        }
        return null;
    }

    private int findLeader(MainThread[] mt) {
        for (int i = 0; i < mt.length; i++) {
            if (mt[i].main.quorumPeer.leader != null) {
                return i;
            }
        }
        return -1;
    }
}
