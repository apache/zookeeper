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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
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


public class FLEDontCareTest {
    protected static final Logger LOG = LoggerFactory.getLogger(FLEDontCareTest.class);

    class MockFLE extends FastLeaderElection {
        MockFLE(QuorumPeer peer, QuorumCnxManager cnxManager) {
            super(peer, cnxManager);
        }

        public boolean termPredicate(HashMap<Long, Vote> votes, Vote vote) {
            return super.termPredicate(votes, vote);
        }

        public boolean checkLeader(HashMap<Long,Vote> votes, long leader, long electionEpoch) {
            return super.checkLeader(votes, leader, electionEpoch);
        }

        public boolean ooePredicate(HashMap<Long,Vote> recv,
                                    HashMap<Long,Vote> ooe,
                                    FastLeaderElection.Notification n) {
            return super.ooePredicate(recv, ooe, n);

        }
    }

    HashMap<Long,QuorumServer> peers;
    QuorumPeer peer;
    File tmpdir;

    @Before
    public void setUp()
    throws Exception {
        tmpdir = ClientBase.createTmpDir();
        peers = new HashMap<Long,QuorumServer>();
        for(int i = 0; i < 5; i++) {
            peers.put(Long.valueOf(i),
                    new QuorumServer(Long.valueOf(i),
                            new InetSocketAddress("127.0.0.1", PortAssignment.unique())));
        }
        peer = new QuorumPeer(peers,
                tmpdir,
                tmpdir,
                PortAssignment.unique(),
                3, 3, 1000, 2, 2);
    }

    @After
    public void tearDown(){
        tmpdir.delete();
    }

    @Test
    public void testDontCare() {
        MockFLE fle = new MockFLE(peer, new QuorumCnxManager(peer));

        HashMap<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 1), 1, 2, ServerState.FOLLOWING));
        votes.put(1L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 2), 1, 2, ServerState.FOLLOWING));
        votes.put(3L, new Vote(0x1, 4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING));
        votes.put(4L, new Vote(0x1, 4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LEADING));

        Assert.assertTrue(fle.termPredicate(votes,
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING)));
    }

    @Test
    public void testDontCareVersion() {
        MockFLE fle = new MockFLE(peer, new QuorumCnxManager(peer));

        HashMap<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 1), 1, 1, ServerState.FOLLOWING));
        votes.put(1L, new Vote(0x1, 4L, ZxidUtils.makeZxid(1, 1), 1, 1, ServerState.FOLLOWING));
        votes.put(3L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING));
        votes.put(4L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LEADING));

        Assert.assertTrue(fle.termPredicate(votes,
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.FOLLOWING)));
    }

    @Test
    public void testLookingNormal() {
        MockFLE fle = new MockFLE(peer, new QuorumCnxManager(peer));

        HashMap<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING));
        votes.put(1L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING));
        votes.put(3L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING));
        votes.put(4L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LEADING));

        Assert.assertTrue(fle.termPredicate(votes,
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 1, 1, ServerState.LOOKING)));
    }

    @Test
    public void testLookingDiffRounds() {
        MockFLE fle = new MockFLE(peer, new QuorumCnxManager(peer));

        HashMap<Long, Vote> votes = new HashMap<Long, Vote>();
        votes.put(0L, new Vote(4L, ZxidUtils.makeZxid(1, 1), 1, 1, ServerState.LOOKING));
        votes.put(1L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LOOKING));
        votes.put(3L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 3, 2, ServerState.LOOKING));
        votes.put(4L, new Vote(4L, ZxidUtils.makeZxid(2, 1), 3, 2, ServerState.LEADING));

        Assert.assertFalse(fle.termPredicate(votes,
                new Vote(4L, ZxidUtils.makeZxid(2, 1), 2, 2, ServerState.LOOKING)));
    }


    /**
     * Helper method to build notifications and populate outofelection.
     *
     *
     * @param version
     * @param leader
     * @param zxid
     * @param electionEpoch
     * @param state
     * @param sid
     * @param peerEpoch
     * @param outofelection
     * @return
     */
    FastLeaderElection.Notification genNotification(int version,
                                                        long leader,
                                                        long zxid,
                                                        long electionEpoch,
                                                        ServerState state,
                                                        long sid,
                                                        long peerEpoch,
                                                        HashMap<Long,Vote> outofelection) {
        FastLeaderElection.Notification n = new FastLeaderElection.Notification();
        n.version = version;
        n.leader = leader;
        n.zxid = zxid;
        n.electionEpoch = electionEpoch;
        n.state = state;
        n.sid = sid;
        n.peerEpoch = peerEpoch;

        outofelection.put(n.sid, new Vote(n.version,
                                            n.leader,
                                            n.zxid,
                                            n.electionEpoch,
                                            n.peerEpoch,
                                            n.state));

        return n;
    }

    @Test
    public void testOutofElection() {
        MockFLE fle = new MockFLE(peer, new QuorumCnxManager(peer));
        HashMap<Long,Vote> outofelection = new HashMap<Long,Vote>();

        /*
         * Generates notifications emulating servers 1, 2, 4, and 5.
         * Server 5 is the elected leader.
         */

        genNotification( 0x0,
                            5,
                            ZxidUtils.makeZxid(15, 0),
                            0xa,
                            ServerState.FOLLOWING,
                            1,
                            0x17,
                            outofelection);

        genNotification( 0x0,
                            5,
                            ZxidUtils.makeZxid(15, 0),
                            0xa,
                            ServerState.FOLLOWING,
                            2,
                            0x17,
                            outofelection);

        genNotification( 0x1,
                            5,
                            ZxidUtils.makeZxid(15, 0),
                            0xa,
                            ServerState.FOLLOWING,
                            4,
                            0x18,
                            outofelection);

        FastLeaderElection.Notification n = genNotification( 0x1,
                                                                5,
                                                                ZxidUtils.makeZxid(15, 0),
                                                                0xa,
                                                                ServerState.LEADING,
                                                                5,
                                                                0x18,
                                                                outofelection);

        /*
         * fle represents the FLE instance of server 3.Here we set
         * its logical clock to 1.
         */
        fle.logicalclock = 0x1;


        /*
         * Here we test the predicates we use in FLE.
         */
        Assert.assertTrue("Termination predicate failed",
                            fle.termPredicate(outofelection,
                                                new Vote(n.version,
                                                         n.leader,
                                                         n.zxid,
                                                         n.electionEpoch,
                                                         n.peerEpoch,
                                                         n.state)));
        Assert.assertTrue("Leader check failed",
                            fle.checkLeader(outofelection,
                                                n.leader,
                                                n.electionEpoch));

        Assert.assertTrue("Out of election predicate failed",
                            fle.ooePredicate( outofelection, outofelection, n ));

    }
}
