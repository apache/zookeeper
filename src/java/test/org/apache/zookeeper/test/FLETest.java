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
import java.util.Random;
import java.util.Set;

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
    HashMap<Long,QuorumServer> peers;
    ArrayList<LEThread> threads;
    HashMap<Integer, HashSet<TestVote> > voteMap;
    File tmpdir[];
    int port[];
    int successCount;
    Object finalObj;

    volatile Vote votes[];
    volatile boolean leaderDies;
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
        finalObj = new Object();
        joinedThreads = new HashSet<Long>();
    }

    @After
    public void tearDown() throws Exception {
        for (int i = 0; i < threads.size(); i++) {
            leThread = threads.get(i);
            QuorumBase.shutdown(leThread.peer);
        }
    }

    class LEThread extends Thread {
        int i;
        QuorumPeer peer;
        //int peerRound = 1;

        LEThread(QuorumPeer peer, int i) {
            this.i = i;
            this.peer = peer;
            LOG.info("Constructor: " + getName());
        }
        public void run() {
            try {
                Vote v = null;
                while(true) {
                    peer.setPeerState(ServerState.LOOKING);
                    LOG.info("Going to call leader election again.");
                    v = peer.getElectionAlg().lookForLeader();
                    if(v == null){
                        LOG.info("Thread " + i + " got a null vote");
                        break;
                    }

                    /*
                     * A real zookeeper would take care of setting the current vote. Here
                     * we do it manually.
                     */
                    peer.setCurrentVote(v);

                    LOG.info("Finished election: " + i + ", " + v.getId());
                    votes[i] = v;

                    /*
                     * Get the current value of the logical clock for this peer.
                     */
                    int lc = (int) ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock();

                    if (v.getId() == i) {
                        /*
                         * A leader executes this part of the code. If it is the first leader to be
                         * elected, then it Assert.fails right after. Otherwise, it waits until it has enough
                         * followers supporting it.
                         */
                        LOG.info("I'm the leader: " + i);
                        synchronized(FLETest.this) {
                            if (leaderDies) {
                                LOG.info("Leader " + i + " dying");
                                leaderDies = false;
                                ((FastLeaderElection) peer.getElectionAlg()).shutdown();
                                leader = -1;
                                LOG.info("Leader " + i + " dead");

                                //round++;
                                FLETest.this.notifyAll();

                                break;

                            } else {
                                synchronized(voteMap){
                                    if(voteMap.get(lc) == null)
                                        voteMap.put(lc, new HashSet<TestVote>());
                                    HashSet<TestVote> hs = voteMap.get(lc);
                                    hs.add(new TestVote(i, v.getId()));

                                    if(countVotes(hs, v.getId()) > (count/2)){
                                        leader = i;
                                        LOG.info("Got majority: " + i);
                                    } else {
                                        voteMap.wait(3000);
                                        LOG.info("Notified or expired: " + i);
                                        hs = voteMap.get(lc);
                                        if(countVotes(hs, v.getId()) > (count/2)){
                                            leader = i;
                                            LOG.info("Got majority: " + i);
                                        } else {
                                            //round++;
                                        }
                                    }
                                }
                                FLETest.this.notifyAll();

                                if(leader == i){
                                    synchronized(finalObj){
                                        successCount++;
                                        joinedThreads.add((long)i);
                                        if(successCount > (count/2)) finalObj.notify();
                                    }

                                    break;
                                }
                            }
                        }
                    } else {
                        /*
                         * Followers execute this part. They first add their vote to voteMap, and then
                         * they wait for bounded amount of time. A leader notifies followers through the
                         * FLETest.this object.
                         *
                         * Note that I can get FLETest.this, and then voteMap before adding the vote of
                         * a follower, otherwise a follower would be blocked out until the leader notifies
                         * or leaves the synchronized block on FLEtest.this.
                         */


                        LOG.info("Logical clock " + ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock());
                        synchronized(voteMap){
                            LOG.info("Voting on " + votes[i].getId() + ", round " + ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock());
                            if(voteMap.get(lc) == null)
                                voteMap.put(lc, new HashSet<TestVote>());
                            HashSet<TestVote> hs = voteMap.get(lc);
                            hs.add(new TestVote(i, votes[i].getId()));
                            if(countVotes(hs, votes[i].getId()) > (count/2)){
                                LOG.info("Logical clock: " + lc + ", " + votes[i].getId());
                                voteMap.notify();
                            }
                        }

                        /*
                         * In this part a follower waits until the leader notifies it, and remove its
                         * vote if the leader takes too long to respond.
                         */
                        synchronized(FLETest.this){
                            if (leader != votes[i].getId()) FLETest.this.wait(3000);

                            LOG.info("The leader: " + leader + " and my vote " + votes[i].getId());
                            synchronized(voteMap){
                                if (leader == votes[i].getId()) {
                                    synchronized(finalObj){
                                        successCount++;
                                        joinedThreads.add((long)i);
                                        if(successCount > (count/2)) finalObj.notify();
                                    }
                                    break;
                                } else {
                                    HashSet<TestVote> hs = voteMap.get(lc);
                                    TestVote toRemove = null;
                                    for(TestVote tv : hs){
                                        if(v.getId() == i){
                                            toRemove = tv;
                                            break;
                                        }
                                    }
                                    hs.remove(toRemove);
                                }
                            }
                        }
                    }
                    /*
                     * Add some randomness to the execution.
                     */
                    Thread.sleep(rand.nextInt(500));
                    peer.setCurrentVote(new Vote(peer.getId(), 0));
                }
                LOG.debug("Thread " + i + " votes " + v);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void testLE() throws Exception {

        FastLeaderElection le[] = new FastLeaderElection[count];
        leaderDies = true;
        boolean allowOneBadLeader = leaderDies;

        LOG.info("TestLE: " + getTestName()+ ", " + count);
        for(int i = 0; i < count; i++) {
            peers.put(Long.valueOf(i),
                      new QuorumServer(i, "0.0.0.0", PortAssignment.unique(),
                                       PortAssignment.unique(), null));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = PortAssignment.unique();
        }

        for(int i = 0; i < le.length; i++) {
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i],
                    port[i], 3, i, 1000, 2, 2);
            peer.startLeaderElection();
            LEThread thread = new LEThread(peer, i);
            thread.start();
            threads.add(thread);
        }
        LOG.info("Started threads " + getTestName());


        int waitCounter = 0;
        synchronized(finalObj){
            while((successCount <= count/2) && (waitCounter < 50)){
                finalObj.wait(2000);
                waitCounter++;
            }
        }

        /*
        * Lists what threads haven-t joined. A thread doesn't join if
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

       synchronized(finalObj){
           if(!joinedThreads.contains(leader)){
               Assert.fail("Leader hasn't joined: " + leader);
           }
       }
    }

    /*
     * Class to verify of the thread has become a follower
     */
    class VerifyState extends Thread {
        volatile private boolean success = false;
        QuorumPeer peer;
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
            peers.put(Long.valueOf(sid),
                      new QuorumServer(sid, "0.0.0.0", PortAssignment.unique(),
                                       PortAssignment.unique(), null));
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
                      new QuorumServer(sid, "0.0.0.0", PortAssignment.unique(),
                                       PortAssignment.unique(), null));
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
}
