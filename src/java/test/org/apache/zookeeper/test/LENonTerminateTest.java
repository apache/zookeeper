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

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.Election;
import org.apache.zookeeper.server.quorum.FLELostMessageTest;
import org.apache.zookeeper.server.quorum.LeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class LENonTerminateTest extends ZKTestCase {
    public static class MockLeaderElection extends LeaderElection {
        public MockLeaderElection(QuorumPeer self) {
            super(self);
        }

        /**
         * Temporary for 3.3.0 - we want to ensure that a round of voting happens
         * before any of the peers update their votes. The easiest way to do that
         * is to add a latch that all wait on after counting their votes.
         *
         * In 3.4.0 we intend to make this class more testable, and therefore
         * there should be much less duplicated code.
         *
         * JMX bean method calls are removed to reduce noise.
         */
        public Vote lookForLeader() throws InterruptedException {
            self.setCurrentVote(new Vote(self.getId(),
                    self.getLastLoggedZxid()));
            // We are going to look for a leader by casting a vote for ourself
            byte requestBytes[] = new byte[4];
            ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);
            byte responseBytes[] = new byte[28];
            ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
            /* The current vote for the leader. Initially me! */
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(200);
            } catch (SocketException e1) {
                LOG.error("Socket exception when creating socket for leader election", e1);
                System.exit(4);
            }
            DatagramPacket requestPacket = new DatagramPacket(requestBytes,
                    requestBytes.length);
            DatagramPacket responsePacket = new DatagramPacket(responseBytes,
                    responseBytes.length);
            int xid = epochGen.nextInt();
            while (self.isRunning()) {
                HashMap<InetSocketAddress, Vote> votes =
                    new HashMap<InetSocketAddress, Vote>(self.getVotingView().size());

                requestBuffer.clear();
                requestBuffer.putInt(xid);
                requestPacket.setLength(4);
                HashSet<Long> heardFrom = new HashSet<Long>();
                for (QuorumServer server :
                    self.getVotingView().values())
                {
                    LOG.info("Server address: " + server.addr);
                    try {
                        requestPacket.setSocketAddress(server.addr);
                    } catch (IllegalArgumentException e) {
                        // Sun doesn't include the address that causes this
                        // exception to be thrown, so we wrap the exception
                        // in order to capture this critical detail.
                        throw new IllegalArgumentException(
                                "Unable to set socket address on packet, msg:"
                                + e.getMessage() + " with addr:" + server.addr,
                                e);
                    }

                    try {
                        s.send(requestPacket);
                        responsePacket.setLength(responseBytes.length);
                        s.receive(responsePacket);
                        if (responsePacket.getLength() != responseBytes.length) {
                            LOG.error("Got a short response: "
                                    + responsePacket.getLength());
                            continue;
                        }
                        responseBuffer.clear();
                        int recvedXid = responseBuffer.getInt();
                        if (recvedXid != xid) {
                            LOG.error("Got bad xid: expected " + xid
                                    + " got " + recvedXid);
                            continue;
                        }
                        long peerId = responseBuffer.getLong();
                        heardFrom.add(peerId);
                        //if(server.id != peerId){
                        Vote vote = new Vote(responseBuffer.getLong(),
                                responseBuffer.getLong());
                        InetSocketAddress addr =
                            (InetSocketAddress) responsePacket
                            .getSocketAddress();
                        votes.put(addr, vote);
                        //}
                    } catch (IOException e) {
                        LOG.warn("Ignoring exception while looking for leader",
                                e);
                        // Errors are okay, since hosts may be
                        // down
                    }
                }

                ElectionResult result = countVotes(votes, heardFrom);

                /**
                 * This is the only difference from LeaderElection - wait for
                 * this latch on the first time through this method. This ensures
                 * that the first round of voting happens before setCurrentVote
                 * is called below.
                 */
                LOG.info("Waiting for first round of voting to complete");
                latch.countDown();
                Assert.assertTrue("Thread timed out waiting for latch",
                        latch.await(10000, TimeUnit.MILLISECONDS));

                // ZOOKEEPER-569:
                // If no votes are received for live peers, reset to voting
                // for ourselves as otherwise we may hang on to a vote
                // for a dead peer
                if (result.numValidVotes == 0) {
                    self.setCurrentVote(new Vote(self.getId(),
                            self.getLastLoggedZxid()));
                } else {
                    if (result.winner.getId() >= 0) {
                        self.setCurrentVote(result.vote);
                        // To do: this doesn't use a quorum verifier
                        if (result.winningCount > (self.getVotingView().size() / 2)) {
                            self.setCurrentVote(result.winner);
                            s.close();
                            Vote current = self.getCurrentVote();
                            LOG.info("Found leader: my type is: " + self.getLearnerType());
                            /*
                             * We want to make sure we implement the state machine
                             * correctly. If we are a PARTICIPANT, once a leader
                             * is elected we can move either to LEADING or
                             * FOLLOWING. However if we are an OBSERVER, it is an
                             * error to be elected as a Leader.
                             */
                            if (self.getLearnerType() == LearnerType.OBSERVER) {
                                if (current.getId() == self.getId()) {
                                    // This should never happen!
                                    LOG.error("OBSERVER elected as leader!");
                                    Thread.sleep(100);
                                }
                                else {
                                    self.setPeerState(ServerState.OBSERVING);
                                    Thread.sleep(100);
                                    return current;
                                }
                            } else {
                                self.setPeerState((current.getId() == self.getId())
                                        ? ServerState.LEADING: ServerState.FOLLOWING);
                                if (self.getPeerState() == ServerState.FOLLOWING) {
                                    Thread.sleep(100);
                                }
                                return current;
                            }
                        }
                    }
                }
                Thread.sleep(1000);
            }
            return null;
        }
    }

    public static class MockQuorumPeer extends QuorumPeer {
        public MockQuorumPeer(Map<Long,QuorumServer> quorumPeers, File snapDir,
                File logDir, int clientPort, int electionAlg,
                long myid, int tickTime, int initLimit, int syncLimit)
        throws IOException
        {
            super(quorumPeers, snapDir, logDir, electionAlg,
                    myid,tickTime, initLimit,syncLimit, false,
                    ServerCnxnFactory.createFactory(clientPort, -1),
                    new QuorumMaj(quorumPeers));
        }

        protected  Election createElectionAlgorithm(int electionAlgorithm){
            LOG.info("Returning mocked leader election");
            return new MockLeaderElection(this);
        }
    }


    protected static final Logger LOG = LoggerFactory.getLogger(FLELostMessageTest.class);

    int count;
    HashMap<Long,QuorumServer> peers;
    File tmpdir[];
    int port[];

    @Before
    public void setUp() throws Exception {
        count = 3;

        peers = new HashMap<Long,QuorumServer>(count);
        tmpdir = new File[count];
        port = new int[count];
    }

    static final CountDownLatch latch = new CountDownLatch(2);
    static final CountDownLatch mockLatch = new CountDownLatch(1);

    private static class LEThread extends Thread {
        private int i;
        private QuorumPeer peer;

        LEThread(QuorumPeer peer, int i) {
            this.i = i;
            this.peer = peer;
            LOG.info("Constructor: " + getName());

        }

        public void run(){
            try{
                Vote v = null;
                peer.setPeerState(ServerState.LOOKING);
                LOG.info("Going to call leader election: " + i);
                v = peer.getElectionAlg().lookForLeader();

                if (v == null){
                    Assert.fail("Thread " + i + " got a null vote");
                }

                /*
                 * A real zookeeper would take care of setting the current vote. Here
                 * we do it manually.
                 */
                peer.setCurrentVote(v);

                LOG.info("Finished election: " + i + ", " + v.getId());
            } catch (Exception e) {
                e.printStackTrace();
            }
            LOG.info("Joining");
        }
    }

    /**
     * This tests ZK-569.
     * With three peers A, B and C, the following could happen:
     * 1. Round 1, A,B and C all vote for themselves
     * 2. Round 2, C dies, A and B vote for C
     * 3. Because C has died, votes for it are ignored, but A and B never
     * reset their votes. Hence LE never terminates. ZK-569 fixes this by
     * resetting votes to themselves if the set of votes for live peers is null.
     */
    @Test
    public void testNonTermination() throws Exception {
        LOG.info("TestNonTermination: " + getTestName()+ ", " + count);
        for(int i = 0; i < count; i++) {
            int clientport = PortAssignment.unique();
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress("127.0.0.1", clientport),
                            new InetSocketAddress("127.0.0.1", PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = clientport;
        }

        /*
         * peer1 and peer2 are A and B in the above example.
         */
        QuorumPeer peer1 = new MockQuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 0, 0, 2, 2, 2);
        peer1.startLeaderElection();
        LEThread thread1 = new LEThread(peer1, 0);

        QuorumPeer peer2 = new MockQuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 0, 1, 2, 2, 2);
        peer2.startLeaderElection();
        LEThread thread2 = new LEThread(peer2, 1);

        /*
         * Start mock server.
         */
        Thread thread3 = new Thread() {
            public void run() {
                try {
                    mockServer();
                } catch (Exception e) {
                    LOG.error("exception", e);
                    Assert.fail("Exception when running mocked server " + e);
                }
            }
        };

        thread3.start();
        Assert.assertTrue("mockServer did not start in 5s",
                mockLatch.await(5000, TimeUnit.MILLISECONDS));
        thread1.start();
        thread2.start();
        /*
         * Occasionally seen false negatives with a 5s timeout.
         */
        thread1.join(15000);
        thread2.join(15000);
        thread3.join(15000);
        if (thread1.isAlive() || thread2.isAlive() || thread3.isAlive()) {
            Assert.fail("Threads didn't join");
        }
    }

    /**
     * MockServer plays the role of peer C. Respond to two requests for votes
     * with vote for self and then Assert.fail.
     */
    void mockServer() throws InterruptedException, IOException {
        byte b[] = new byte[36];
        ByteBuffer responseBuffer = ByteBuffer.wrap(b);
        DatagramPacket packet = new DatagramPacket(b, b.length);
        QuorumServer server = peers.get(Long.valueOf(2));
        DatagramSocket udpSocket = new DatagramSocket(server.addr.getPort());
        LOG.info("In MockServer");
        mockLatch.countDown();
        Vote current = new Vote(2, 1);
        for (int i=0;i<2;++i) {
            udpSocket.receive(packet);
            responseBuffer.rewind();
            LOG.info("Received " + responseBuffer.getInt() + " " + responseBuffer.getLong() + " " + responseBuffer.getLong());
            LOG.info("From " + packet.getSocketAddress());
            responseBuffer.clear();
            responseBuffer.getInt(); // Skip the xid
            responseBuffer.putLong(2);

            responseBuffer.putLong(current.getId());
            responseBuffer.putLong(current.getZxid());
            packet.setData(b);
            udpSocket.send(packet);
        }
    }
}
