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

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.test.ClientBase.StateWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validate that open/close session request of a local session to not propagate
 * to other machines in the quorum. We verify this by checking that
 * these request doesn't show up in committedLog on other machines.
 */
public class LocalSessionRequestTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(LocalSessionRequestTest.class);
    // Need to be short since we need to wait for session to expire
    public static final int CONNECTION_TIMEOUT = 4000;

    private final QuorumBase qb = new QuorumBase();

    @BeforeEach
    public void setUp() throws Exception {
        LOG.info("STARTING quorum {}", getClass().getName());
        qb.localSessionsEnabled = true;
        qb.localSessionsUpgradingEnabled = true;
        qb.setUp();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
    }

    @AfterEach
    public void tearDown() throws Exception {
        LOG.info("STOPPING quorum {}", getClass().getName());
        qb.tearDown();
    }

    @Test
    public void testLocalSessionsOnFollower() throws Exception {
        testOpenCloseSession(false);
    }

    @Test
    public void testLocalSessionsOnLeader() throws Exception {
        testOpenCloseSession(true);
    }

    /**
     * Walk through the target peer commmittedLog.
     * @param sessionId
     * @param peerId
     */
    private void validateRequestLog(long sessionId, int peerId) {
        String session = Long.toHexString(sessionId);
        LOG.info("Searching for txn of session 0x " + session + " on peer " + peerId);
        String peerType = peerId == qb.getLeaderIndex() ? "leader" : "follower";
        QuorumPeer peer = qb.getPeerList().get(peerId);
        ZKDatabase db = peer.getActiveServer().getZKDatabase();
        for (Proposal p : db.getCommittedLog()) {
            assertFalse(p.request.sessionId == sessionId,
                    "Should not see " + Request.op2String(p.request.type)
                            + " request from local session 0x" + session + " on the " + peerType);
        }
    }

    /**
     * Test that a CloseSession request generated by both the server (client
     * disconnect) or by the client (client explicitly issue close()) doesn't
     * get committed by the ensemble
     */
    public void testOpenCloseSession(boolean onLeader) throws Exception {
        int leaderIdx = qb.getLeaderIndex();
        assertFalse(leaderIdx == -1, "No leader in quorum?");
        int followerIdx = (leaderIdx + 1) % 5;
        int testPeerIdx = onLeader ? leaderIdx : followerIdx;
        int verifyPeerIdx = onLeader ? followerIdx : leaderIdx;

        String[] hostPorts = qb.hostPort.split(",");

        StateWatcher watcher = new StateWatcher();
        DisconnectableZooKeeper client = new DisconnectableZooKeeper(hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        long localSessionId1 = client.getSessionId();

        // Cut the connection, so the server will create closeSession as part
        // of expiring the session.
        client.dontReconnect();
        client.disconnect();
        watcher.reset();

        // We don't validate right away, will do another session create first

        ZooKeeper zk = qb.createClient(watcher, hostPorts[testPeerIdx], CONNECTION_TIMEOUT);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        long localSessionId2 = zk.getSessionId();

        // Send closeSession request.
        zk.close();
        watcher.reset();

        // This should be enough time for the first session to expire and for
        // the closeSession request to propagate to other machines (if there is a bug)
        // Since it is time sensitive, we have false negative when test
        // machine is under load
        Thread.sleep(CONNECTION_TIMEOUT * 2);

        // Validate that we don't see any txn from the first session
        validateRequestLog(localSessionId1, verifyPeerIdx);

        // Validate that we don't see any txn from the second session
        validateRequestLog(localSessionId2, verifyPeerIdx);

        qb.shutdownServers();

    }

}
