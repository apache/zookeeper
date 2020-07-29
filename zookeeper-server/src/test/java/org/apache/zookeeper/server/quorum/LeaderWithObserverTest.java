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

import static org.apache.zookeeper.server.quorum.ZabUtils.createLeader;
import static org.apache.zookeeper.server.quorum.ZabUtils.createQuorumPeer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LeaderWithObserverTest {

    QuorumPeer peer;
    Leader leader;
    File tmpDir;
    long participantId;
    long observerId;

    @BeforeEach
    public void setUp() throws Exception {
        tmpDir = ClientBase.createTmpDir();
        peer = createQuorumPeer(tmpDir);
        participantId = 1;
        Map<Long, QuorumPeer.QuorumServer> peers = peer.getQuorumVerifier().getAllMembers();
        observerId = peers.size();
        leader = createLeader(tmpDir, peer);
        peer.leader = leader;
        peers.put(observerId, new QuorumPeer.QuorumServer(observerId, new InetSocketAddress("127.0.0.1", PortAssignment.unique()), new InetSocketAddress("127.0.0.1", PortAssignment.unique()), new InetSocketAddress("127.0.0.1", PortAssignment.unique()), QuorumPeer.LearnerType.OBSERVER));

        // these tests are serial, we can speed up InterruptedException
        peer.tickTime = 1;
    }

    @AfterEach
    public void tearDown() {
        leader.shutdown("end of test");
        tmpDir.delete();
    }

    @Test
    public void testGetEpochToPropose() throws Exception {
        long lastAcceptedEpoch = 5;
        peer.setAcceptedEpoch(5);

        assertEquals(0, leader.connectingFollowers.size(), "Unexpected vote in connectingFollowers");
        assertTrue(leader.waitingForNewEpoch);
        try {
            // Leader asks for epoch (mocking Leader.lead behavior)
            // First add to connectingFollowers
            leader.getEpochToPropose(peer.getId(), lastAcceptedEpoch);
        } catch (InterruptedException e) {
            // ignore timeout
        }

        assertEquals(1, leader.connectingFollowers.size(), "Unexpected vote in connectingFollowers");
        assertEquals(lastAcceptedEpoch, peer.getAcceptedEpoch(), "Leader shouldn't set new epoch until quorum of participants is in connectingFollowers");
        assertTrue(leader.waitingForNewEpoch);
        try {
            // Observer asks for epoch (mocking LearnerHandler behavior)
            leader.getEpochToPropose(observerId, lastAcceptedEpoch);
        } catch (InterruptedException e) {
            // ignore timeout
        }

        assertEquals(1, leader.connectingFollowers.size(), "Unexpected vote in connectingFollowers");
        assertEquals(lastAcceptedEpoch, peer.getAcceptedEpoch(), "Leader shouldn't set new epoch after observer asks for epoch");
        assertTrue(leader.waitingForNewEpoch);
        try {
            // Now participant asks for epoch (mocking LearnerHandler behavior). Second add to connectingFollowers.
            // Triggers verifier.containsQuorum = true
            leader.getEpochToPropose(participantId, lastAcceptedEpoch);
        } catch (Exception e) {
            fail("Timed out in getEpochToPropose");
        }

        assertEquals(2, leader.connectingFollowers.size(), "Unexpected vote in connectingFollowers");
        assertEquals(lastAcceptedEpoch + 1, peer.getAcceptedEpoch(), "Leader should record next epoch");
        assertFalse(leader.waitingForNewEpoch);
    }

    @Test
    public void testWaitForEpochAck() throws Exception {
        // things needed for waitForEpochAck to run (usually in leader.lead(), but we're not running leader here)
        leader.leaderStateSummary = new StateSummary(leader.self.getCurrentEpoch(), leader.zk.getLastProcessedZxid());

        assertEquals(0, leader.electingFollowers.size(), "Unexpected vote in electingFollowers");
        assertFalse(leader.electionFinished);
        try {
            // leader calls waitForEpochAck, first add to electingFollowers
            leader.waitForEpochAck(peer.getId(), new StateSummary(0, 0));
        } catch (InterruptedException e) {
            // ignore timeout
        }

        assertEquals(1, leader.electingFollowers.size(), "Unexpected vote in electingFollowers");
        assertFalse(leader.electionFinished);
        try {
            // observer calls waitForEpochAck, should fail verifier.containsQuorum
            leader.waitForEpochAck(observerId, new StateSummary(0, 0));
        } catch (InterruptedException e) {
            // ignore timeout
        }

        assertEquals(1, leader.electingFollowers.size(), "Unexpected vote in electingFollowers");
        assertFalse(leader.electionFinished);
        try {
            // second add to electingFollowers, verifier.containsQuorum=true, waitForEpochAck returns without exceptions
            leader.waitForEpochAck(participantId, new StateSummary(0, 0));
            assertEquals(2, leader.electingFollowers.size(), "Unexpected vote in electingFollowers");
            assertTrue(leader.electionFinished);
        } catch (Exception e) {
            fail("Timed out in waitForEpochAck");
        }
    }

    @Test
    public void testWaitForNewLeaderAck() throws Exception {
        long zxid = leader.zk.getZxid();

        // things needed for waitForNewLeaderAck to run (usually in leader.lead(), but we're not running leader here)
        leader.newLeaderProposal.packet = new QuorumPacket(0, zxid, null, null);
        leader.newLeaderProposal.addQuorumVerifier(peer.getQuorumVerifier());

        Set<Long> ackSet = leader.newLeaderProposal.qvAcksetPairs.get(0).getAckset();
        assertEquals(0, ackSet.size(), "Unexpected vote in ackSet");
        assertFalse(leader.quorumFormed);
        try {
            // leader calls waitForNewLeaderAck, first add to ackSet
            leader.waitForNewLeaderAck(peer.getId(), zxid);
        } catch (InterruptedException e) {
            // ignore timeout
        }

        assertEquals(1, ackSet.size(), "Unexpected vote in ackSet");
        assertFalse(leader.quorumFormed);
        try {
            // observer calls waitForNewLeaderAck, should fail verifier.containsQuorum
            leader.waitForNewLeaderAck(observerId, zxid);
        } catch (InterruptedException e) {
            // ignore timeout
        }

        assertEquals(1, ackSet.size(), "Unexpected vote in ackSet");
        assertFalse(leader.quorumFormed);
        try {
            // second add to ackSet, verifier.containsQuorum=true, waitForNewLeaderAck returns without exceptions
            leader.waitForNewLeaderAck(participantId, zxid);
            assertEquals(2, ackSet.size(), "Unexpected vote in ackSet");
            assertTrue(leader.quorumFormed);
        } catch (Exception e) {
            fail("Timed out in waitForEpochAck");
        }
    }

}
