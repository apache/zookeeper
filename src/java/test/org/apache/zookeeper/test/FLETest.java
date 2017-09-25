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
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class FLETest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(FLETest.class);
    private final int MAX_LOOP_COUNTER = 300;
    private FLETest.LEThread leThread;

    static class TestVote {
        TestVote(int id, long leader) {
            this.leader = leader;
        }

        long leader;
    }

    int countVotes(HashSet<TestVote> hs, long id) {
        int counter = 0;
        for(TestVote v : hs){
            if(v.leader == id) counter++;
        }

        return counter;
    }

    int count;
    Map<Long,QuorumServer> peers;
    ArrayList<LEThread> threads;
    Map<Integer, HashSet<TestVote> > voteMap;
    Map<Long, LEThread> quora;
    File tmpdir[];
    int port[];
    int successCount;

    volatile Vote votes[];
    volatile long leader = -1;
    //volatile int round = 1;
    Random rand = new Random();
    Set<Long> joinedThreads;
    
    @Before
    public void setUp() throws Exception {
        count = 7;

        peers = new HashMap<Long,QuorumServer>(count);
        threads = new ArrayList<LEThread>(count);
        voteMap = new HashMap<Integer, HashSet<TestVote> >();
        votes = new Vote[count];
        tmpdir = new File[count];
        port = new int[count];
        successCount = 0;
        joinedThreads = new HashSet<Long>();
    }

    @After
    public void tearDown() throws Exception {
        for (int i = 0; i < threads.size(); i++) {
            leThread = threads.get(i);
            QuorumBase.shutdown(leThread.peer);
        }
    }

    
    /**
     * Implements the behavior of a peer during the leader election rounds
     * of tests.
     */
    class LEThread extends Thread {
        FLETest self;
        int i;
        QuorumPeer peer;
        int totalRounds;
        ConcurrentHashMap<Long, HashSet<Integer> > quora;

        LEThread(FLETest self, QuorumPeer peer, int i, int rounds, ConcurrentHashMap<Long, HashSet<Integer> > quora) {
            this.self = self;
            this.i = i;
            this.peer = peer;
            this.totalRounds = rounds;
            this.quora = quora;
            
            LOG.info("Constructor: " + getName());
        }
        
        public void run() {
            try {
                Vote v = null;
                while(true) {
                    
                    /*
                     * Set the state of the peer to LOOKING and look for leader
                     */
                    peer.setPeerState(ServerState.LOOKING);
                    LOG.info("Going to call leader election again.");
                    v = peer.getElectionAlg().lookForLeader();
                    if(v == null){
                        LOG.info("Thread " + i + " got a null vote");
                        break;
                    }

                    /*
                     * Done with the election round, so now we set the vote in
                     * the peer. A real zookeeper would take care of setting the
                     * current vote. Here we do it manually.
                     */
                    peer.setCurrentVote(v);

                    LOG.info("Finished election: " + i + ", " + v.getId());
                    votes[i] = v;

                    /*
                     * Get the current value of the logical clock for this peer
                     * so that we know in which round this peer has executed.
                     */
                    int lc = (int) ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock();
                    
                    /*
                     * The leader executes the following block, which essentially shuts down
                     * the peer if it is not the last round. 
                     */
                    if (v.getId() == i) {
                        LOG.info("I'm the leader: " + i);
                        if (lc < this.totalRounds) {
                            LOG.info("Leader " + i + " dying");
                            FastLeaderElection election =
                                (FastLeaderElection) peer.getElectionAlg();
                            election.shutdown();
                            // Make sure the vote is reset to -1 after shutdown.
                            Assert.assertEquals(-1, election.getVote().getId());
                            LOG.info("Leader " + i + " dead");
                            
                            break;
                        } 
                    }
                    
                    /*
                     * If the peer has done enough rounds, then consider joining. The thread
                     * will only join if it is part of a quorum supporting the current 
                     * leader. Otherwise it will try again.
                     */
                    if (lc >= this.totalRounds) {
                        /*
                         * quora keeps the supporters of a given leader, so 
                         * we first update it with the vote of this peer.
                         */
                        if(quora.get(v.getId()) == null) quora.put(v.getId(), new HashSet<Integer>());
                        quora.get(v.getId()).add(i);
                        
                        /*
                         * we now wait until a quorum supports the same leader.
                         */
                        if(waitForQuorum(v.getId())){   
                            synchronized(self){
                                
                                /*
                                 * Assert that the state of the thread is the one expected.
                                 */
                                if(v.getId() == i){
                                    Assert.assertTrue("Wrong state" + peer.getPeerState(), 
                                                                    peer.getPeerState() == ServerState.LEADING);
                                    leader = i;
                                } else {
                                    Assert.assertTrue("Wrong state" + peer.getPeerState(), 
                                                                    peer.getPeerState() == ServerState.FOLLOWING);
                                }
                                
                                /*
                                 * Global variable keeping track of 
                                 * how many peers have successfully 
                                 * joined.
                                 */
                                successCount++;
                                joinedThreads.add((long)i);
                                self.notify();
                            }
                        
                            /*
                             * I'm done so joining. 
                             */
                            break;
                        } else {
                            quora.get(v.getId()).remove(i);
                        }
                    } 
                    
                    /*
                     * This sleep time represents the time a follower
                     * would take to declare the leader dead and start
                     * a new leader election.
                     */
                    Thread.sleep(100);
                    
                }
                LOG.debug("Thread " + i + " votes " + v);
            } catch (InterruptedException e) {
                Assert.fail(e.toString());
            }
        }
        
        /**
         * Auxiliary method to make sure that enough followers terminated.
         * 
         * @return boolean  followers successfully joined.
         */
        boolean waitForQuorum(long id)
        throws InterruptedException {
            int loopCounter = 0;
            while((quora.get(id).size() <= count/2) && (loopCounter < MAX_LOOP_COUNTER)){
                Thread.sleep(100);
                loopCounter++;
            }
            
            if((loopCounter >= MAX_LOOP_COUNTER) && (quora.get(id).size() <= count/2)){
                return false;
            } else {
                return true;
            }
        }
        
    }

    
    
    @Test
    public void testSingleElection() throws Exception {
        try{
            runElection(1);
        } catch (Exception e) {
            Assert.fail(e.toString());
        }
    }
    
    
    @Test
    public void testDoubleElection() throws Exception {
        try{
            runElection(2);
        } catch (Exception e) {
            Assert.fail(e.toString());
        }
    }
    
    @Test
    public void testTripleElection() throws Exception {
        try{
            runElection(3);
        } catch (Exception e) {
            Assert.fail(e.toString());
        }
    }

    /**
     * Test leader election for a number of rounds. In all rounds but the last one
     * we kill the leader.
     * 
     * @param rounds
     * @throws Exception
     */
    private void runElection(int rounds) throws Exception {
        ConcurrentHashMap<Long, HashSet<Integer> > quora = 
            new ConcurrentHashMap<Long, HashSet<Integer> >();

        LOG.info("TestLE: " + getTestName()+ ", " + count);

        /*
         * Creates list of peers.
         */
        for(int i = 0; i < count; i++) {
            port[i] = PortAssignment.unique();
            peers.put(Long.valueOf(i),
                new QuorumServer(i,
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress(
                        "127.0.0.1", port[i])));
            tmpdir[i] = ClientBase.createTmpDir();           
        }

        /*
         * Start one LEThread for each peer we want to run.
         */
        for(int i = 0; i < count; i++) {
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i],
                    port[i], 3, i, 1000, 2, 2);
            peer.startLeaderElection();
            LEThread thread = new LEThread(this, peer, i, rounds, quora);
            thread.start();
            threads.add(thread);
        }
        LOG.info("Started threads " + getTestName());

        int waitCounter = 0;
        synchronized(this){
            while(((successCount <= count/2) || (leader == -1))
                && (waitCounter < MAX_LOOP_COUNTER))
            {
                this.wait(200);
                waitCounter++;
            }
        }
        LOG.info("Success count: " + successCount);

        /*
        * Lists what threads haven't joined. A thread doesn't join if
        * it hasn't decided upon a leader yet. It can happen that a
        * peer is slow or disconnected, and it can take longer to
        * nominate and connect to the current leader.
        */
       for (int i = 0; i < threads.size(); i++) {
            if (threads.get(i).isAlive()) {
                LOG.info("Threads didn't join: " + i);
            }
        }

       /*
        * If we have a majority, then we are good to go.
        */
       if(successCount <= count/2){
           Assert.fail("Fewer than a a majority has joined");
       }

       /*
        * I'm done so joining.
        */
       if(!joinedThreads.contains(leader)){
           Assert.fail("Leader hasn't joined: " + leader);
       }
    }
    
    
    /*
     * Class to verify of the thread has become a follower
     */
    static class VerifyState extends Thread {
        volatile private boolean success = false;
        private QuorumPeer peer;
        public VerifyState(QuorumPeer peer) {
            this.peer = peer;
        }
        public void run() {
            setName("VerifyState-" + peer.getId());
            while (true) {
                if(peer.getPeerState() == ServerState.FOLLOWING) {
                    LOG.info("I am following");
                    success = true;
                    break;
                } else if (peer.getPeerState() == ServerState.LEADING) {
                    LOG.info("I am leading");
                    success = false;
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (Exception e) {
                    LOG.warn("Sleep failed ", e);
                }
            }
        }
        public boolean isSuccess() {
            return success;
        }
    }

    /*
     * For ZOOKEEPER-975 verify that a peer joining an established cluster
     * does not go in LEADING state.
     */
    @Test
    public void testJoin() throws Exception {
        int sid;
        QuorumPeer peer;
        int waitTime = 10 * 1000;
        ArrayList<QuorumPeer> peerList = new ArrayList<QuorumPeer>();
        for(sid = 0; sid < 3; sid++) {
            port[sid] = PortAssignment.unique();
            peers.put(Long.valueOf(sid),
                new QuorumServer(sid,
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress(
                        "127.0.0.1", port[sid])));
            tmpdir[sid] = ClientBase.createTmpDir();          
        }
        // start 2 peers and verify if they form the cluster
        for (sid = 0; sid < 2; sid++) {
            peer = new QuorumPeer(peers, tmpdir[sid], tmpdir[sid],
                                             port[sid], 3, sid, 2000, 2, 2);
            LOG.info("Starting peer " + peer.getId());
            peer.start();
            peerList.add(sid, peer);
        }
        peer = peerList.get(0);
        VerifyState v1 = new VerifyState(peerList.get(0));
        v1.start();
        v1.join(waitTime);
        Assert.assertFalse("Unable to form cluster in " +
            waitTime + " ms",
            !v1.isSuccess());
        // Start 3rd peer and check if it goes in LEADING state
        peer = new QuorumPeer(peers, tmpdir[sid], tmpdir[sid],
                 port[sid], 3, sid, 2000, 2, 2);
        LOG.info("Starting peer " + peer.getId());
        peer.start();
        peerList.add(sid, peer);
        v1 = new VerifyState(peer);
        v1.start();
        v1.join(waitTime);
        if (v1.isAlive()) {
               Assert.fail("Peer " + peer.getId() + " failed to join the cluster " +
                "within " + waitTime + " ms");
        } else if (!v1.isSuccess()) {
               Assert.fail("Incorrect LEADING state for peer " + peer.getId());
        }
        // cleanup
        for (int id = 0; id < 3; id++) {
            peer = peerList.get(id);
            if (peer != null) {
                peer.shutdown();
            }
        }
    }

    /*
     * For ZOOKEEPER-1732 verify that it is possible to join an ensemble with
     * inconsistent election round information.
     */
    @Test
    public void testJoinInconsistentEnsemble() throws Exception {
        int sid;
        QuorumPeer peer;
        int waitTime = 10 * 1000;
        ArrayList<QuorumPeer> peerList = new ArrayList<QuorumPeer>();
        for(sid = 0; sid < 3; sid++) {
            peers.put(Long.valueOf(sid),
                new QuorumServer(sid,
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique()),
                    new InetSocketAddress(
                        "127.0.0.1", PortAssignment.unique())));
            tmpdir[sid] = ClientBase.createTmpDir();
            port[sid] = PortAssignment.unique();
        }
        // start 2 peers and verify if they form the cluster
        for (sid = 0; sid < 2; sid++) {
            peer = new QuorumPeer(peers, tmpdir[sid], tmpdir[sid],
                                             port[sid], 3, sid, 2000, 2, 2);
            LOG.info("Starting peer " + peer.getId());
            peer.start();
            peerList.add(sid, peer);
        }
        peer = peerList.get(0);
        VerifyState v1 = new VerifyState(peerList.get(0));
        v1.start();
        v1.join(waitTime);
        Assert.assertFalse("Unable to form cluster in " +
            waitTime + " ms",
            !v1.isSuccess());
        // Change the election round for one of the members of the ensemble
        long leaderSid = peer.getCurrentVote().getId();
        long zxid = peer.getCurrentVote().getZxid();
        long electionEpoch = peer.getCurrentVote().getElectionEpoch();
        ServerState state = peer.getCurrentVote().getState();
        long peerEpoch = peer.getCurrentVote().getPeerEpoch();
        Vote newVote = new Vote(leaderSid, zxid+100, electionEpoch+100, peerEpoch, state);
        peer.setCurrentVote(newVote);
        // Start 3rd peer and check if it joins the quorum
        peer = new QuorumPeer(peers, tmpdir[2], tmpdir[2],
                 port[2], 3, 2, 2000, 2, 2);
        LOG.info("Starting peer " + peer.getId());
        peer.start();
        peerList.add(sid, peer);
        v1 = new VerifyState(peer);
        v1.start();
        v1.join(waitTime);
        if (v1.isAlive()) {
               Assert.fail("Peer " + peer.getId() + " failed to join the cluster " +
                "within " + waitTime + " ms");
        }
        // cleanup
        for (int id = 0; id < 3; id++) {
            peer = peerList.get(id);
            if (peer != null) {
                peer.shutdown();
            }
        }
    }

    @Test
    public void testElectionTimeUnit() throws Exception {
        Assert.assertEquals("MS", QuorumPeer.FLE_TIME_UNIT);
    }
}
