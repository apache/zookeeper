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

import java.io.File;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.FastLeaderElection.Notification;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.FLETest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test FastLeaderElection with out of election servers.
 */
public class FLEOutOfElectionTest {

    private FastLeaderElection fle;

    @Before
    public void setUp() throws Exception {
        File tmpdir = ClientBase.createTmpDir();
        Map<Long, QuorumServer> peers = new HashMap<Long,QuorumServer>();
        for(int i = 0; i < 5; i++) {
            peers.put(Long.valueOf(i), new QuorumServer(Long.valueOf(i), 
                    new InetSocketAddress("127.0.0.1", PortAssignment.unique())));
        }
        QuorumPeer peer = new QuorumPeer(peers, tmpdir, tmpdir, 
                PortAssignment.unique(), 3, 3, 1000, 2, 2);
        fle = new FastLeaderElection(peer, peer.createCnxnManager());
    }

    @Test
    public void testIgnoringZxidElectionEpoch() {
        Map<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 1), 1, 2, ServerState.FOLLOWING));
        votes.put(1L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 2), 1, 2, ServerState.FOLLOWING));
        votes.put(3L, new Vote(0x1, 4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING));
        votes.put(4L, new Vote(0x1, 4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LEADING));

        Assert.assertTrue(fle.termPredicate(votes, 
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING)));
    }

    @Test
    public void testElectionWIthDifferentVersion() {
        Map<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 1), 1, 1, ServerState.FOLLOWING));
        votes.put(1L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 1), 1, 1, ServerState.FOLLOWING));
        votes.put(3L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING));
        votes.put(4L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LEADING));

        Assert.assertTrue(fle.termPredicate(votes, 
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING)));
    }

    @Test
    public void testLookingNormal() {
        Map<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING));
        votes.put(1L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING));
        votes.put(3L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING));
        votes.put(4L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LEADING));

        Assert.assertTrue(fle.termPredicate(votes, 
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING)));
    }

    @Test
    public void testLookingDiffRounds() {
        HashMap<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(4L, ZxidUtils.makeZxid(1, 1), 1, 1, ServerState.LOOKING));
        votes.put(1L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LOOKING));
        votes.put(3L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 3, 2, ServerState.LOOKING));
        votes.put(4L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 3, 2, ServerState.LEADING));

        Assert.assertFalse(fle.termPredicate(votes,
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LOOKING)));
    }

    @Test
    public void testOutofElection() {
        HashMap<Long,Vote> outofelection = new HashMap<Long,Vote>();

        outofelection.put(1L, new Vote(0x0, 5, ZxidUtils.makeZxid(15, 0), 0xa, 0x17, ServerState.FOLLOWING));
        outofelection.put(2L, new Vote(0x0, 5, ZxidUtils.makeZxid(15, 0), 0xa, 0x17, ServerState.FOLLOWING));
        outofelection.put(4L, new Vote(0x1, 5, ZxidUtils.makeZxid(15, 0), 0xa, 0x18, ServerState.FOLLOWING));
        Vote vote = new Vote(0x1, 5, ZxidUtils.makeZxid(15, 0), 0xa, 0x18, ServerState.LEADING);
        outofelection.put(5L, vote);

        Notification n = new Notification();
        n.version = vote.getVersion();
        n.leader = vote.getId();
        n.zxid = vote.getZxid();
        n.electionEpoch = vote.getElectionEpoch();
        n.state = vote.getState();
        n.peerEpoch = vote.getPeerEpoch();
        n.sid = 5L;
        
        // Set the logical clock to 1 on fle instance of server 3.
        fle.logicalclock.set(0x1);

        Assert.assertTrue("Quorum check failed",
                fle.termPredicate(outofelection, new Vote(n.version, n.leader, 
                        n.zxid, n.electionEpoch, n.peerEpoch, n.state)));

        Assert.assertTrue("Leader check failed", fle.checkLeader(outofelection, 
                n.leader, n.electionEpoch)); 
    }
}
