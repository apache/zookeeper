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

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.apache.zookeeper.server.quorum.ZabUtils.createLeader;
import static org.apache.zookeeper.server.quorum.ZabUtils.createQuorumPeer;

public class LeaderWithObserverTest {

    QuorumPeer peer;
    Leader leader;
    File tmpDir;
    long participantId;
    long observerId;

    @Before
    public void setUp() throws Exception {
        tmpDir = ClientBase.createTmpDir();
        peer = createQuorumPeer(tmpDir);
        participantId = 1;
        observerId = peer.quorumPeers.size();
        leader = createLeader(tmpDir, peer);
        peer.leader = leader;
        peer.quorumPeers.put(observerId, new QuorumPeer.QuorumServer(observerId, "127.0.0.1", PortAssignment.unique(),
                0, QuorumPeer.LearnerType.OBSERVER));

        // these tests are serial, we can speed up InterruptedException
        peer.tickTime = 1;
    }

    @After
    public void tearDown(){
        leader.shutdown("end of test");
        tmpDir.delete();
    }

    @Test
    public void testGetEpochToPropose() throws Exception {
        long lastAcceptedEpoch = 5;
        peer.setAcceptedEpoch(5);

        Assert.assertEquals("Unexpected vote in connectingFollowers", 0, leader.connectingFollowers.size());
        Assert.assertTrue(leader.waitingForNewEpoch);
        try {
            // Leader asks for epoch (mocking Leader.lead behavior)
            // First add to connectingFollowers
            leader.getEpochToPropose(peer.getId(), lastAcceptedEpoch);
        } catch (InterruptedException e) {
            // ignore timeout
        }

        Assert.assertEquals("Unexpected vote in connectingFollowers", 1, leader.connectingFollowers.size());
        Assert.assertEquals("Leader shouldn't set new epoch until quorum of participants is in connectingFollowers",
                lastAcceptedEpoch, peer.getAcceptedEpoch());
        Assert.assertTrue(leader.waitingForNewEpoch);
        try {
            // Observer asks for epoch (mocking LearnerHandler behavior)
            leader.getEpochToPropose(observerId, lastAcceptedEpoch);
        } catch (InterruptedException e) {
            // ignore timeout
        }

        Assert.assertEquals("Unexpected vote in connectingFollowers", 1, leader.connectingFollowers.size());
        Assert.assertEquals("Leader shouldn't set new epoch after observer asks for epoch",
                lastAcceptedEpoch, peer.getAcceptedEpoch());
        Assert.assertTrue(leader.waitingForNewEpoch);
        try {
            // Now participant asks for epoch (mocking LearnerHandler behavior). Second add to connectingFollowers.
            // Triggers verifier.containsQuorum = true
            leader.getEpochToPropose(participantId, lastAcceptedEpoch);
        } catch (Exception e) {
            Assert.fail("Timed out in getEpochToPropose");
        }

        Assert.assertEquals("Unexpected vote in connectingFollowers", 2, leader.connectingFollowers.size());
        Assert.assertEquals("Leader should record next epoch", lastAcceptedEpoch + 1, peer.getAcceptedEpoch());
        Assert.assertFalse(leader.waitingForNewEpoch);
    }

    @Test
    public void testWaitForEpochAck() throws Exception {
        // things needed for waitForEpochAck to run (usually in leader.lead(), but we're not running leader here)
        leader.readyToStart = true;
        leader.leaderStateSummary = new StateSummary(leader.self.getCurrentEpoch(), leader.zk.getLastProcessedZxid());

        Assert.assertEquals("Unexpected vote in electingFollowers", 0, leader.electingFollowers.size());
        Assert.assertFalse(leader.electionFinished);
        try {
            // leader calls waitForEpochAck, first add to electingFollowers
            leader.waitForEpochAck(peer.getId(), new StateSummary(0, 0));
        }  catch (InterruptedException e) {
            // ignore timeout
        }

        Assert.assertEquals("Unexpected vote in electingFollowers", 1, leader.electingFollowers.size());
        Assert.assertFalse(leader.electionFinished);
        try {
            // observer calls waitForEpochAck, should fail verifier.containsQuorum
            leader.waitForEpochAck(observerId, new StateSummary(0, 0));
        }  catch (InterruptedException e) {
            // ignore timeout
        }

        Assert.assertEquals("Unexpected vote in electingFollowers", 1, leader.electingFollowers.size());
        Assert.assertFalse(leader.electionFinished);
        try {
            // second add to electingFollowers, verifier.containsQuorum=true, waitForEpochAck returns without exceptions
            leader.waitForEpochAck(participantId, new StateSummary(0, 0));
            Assert.assertEquals("Unexpected vote in electingFollowers", 2, leader.electingFollowers.size());
            Assert.assertTrue(leader.electionFinished);
        } catch (Exception e) {
            Assert.fail("Timed out in waitForEpochAck");
        }
    }

    @Test
    public void testWaitForNewLeaderAck() throws Exception {
        long zxid = leader.zk.getZxid();

        // things needed for waitForNewLeaderAck to run (usually in leader.lead(), but we're not running leader here)
        leader.newLeaderProposal.packet = new QuorumPacket(0, zxid,null, null);

        Assert.assertEquals("Unexpected vote in ackSet", 0, leader.newLeaderProposal.ackSet.size());
        Assert.assertFalse(leader.quorumFormed);
        try {
            // leader calls waitForNewLeaderAck, first add to ackSet
            leader.waitForNewLeaderAck(peer.getId(), zxid);
        }  catch (InterruptedException e) {
            // ignore timeout
        }

        Assert.assertEquals("Unexpected vote in ackSet", 1, leader.newLeaderProposal.ackSet.size());
        Assert.assertFalse(leader.quorumFormed);
        try {
            // observer calls waitForNewLeaderAck, should fail verifier.containsQuorum
            leader.waitForNewLeaderAck(observerId, zxid);
        }  catch (InterruptedException e) {
            // ignore timeout
        }

        Assert.assertEquals("Unexpected vote in ackSet", 1, leader.newLeaderProposal.ackSet.size());
        Assert.assertFalse(leader.quorumFormed);
        try {
            // second add to ackSet, verifier.containsQuorum=true, waitForNewLeaderAck returns without exceptions
            leader.waitForNewLeaderAck(participantId, zxid);
            Assert.assertEquals("Unexpected vote in ackSet", 2, leader.newLeaderProposal.ackSet.size());
            Assert.assertTrue(leader.quorumFormed);
        } catch (Exception e) {
            Assert.fail("Timed out in waitForEpochAck");
        }
    }
}
