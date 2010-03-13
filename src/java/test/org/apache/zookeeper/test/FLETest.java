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

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.Test;

public class FLETest extends TestCase {
    protected static final Logger LOG = Logger.getLogger(FLETest.class);
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

    @Override
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

        LOG.info("SetUp " + getName());
    }

    @Override
    public void tearDown() throws Exception {
        for (int i = 0; i < threads.size(); i++) {
            leThread = threads.get(i);
            QuorumBase.shutdown(leThread.peer);
        }
        LOG.info("FINISHED " + getName());
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

                    LOG.info("Finished election: " + i + ", " + v.id);
                    votes[i] = v;

                    /*
                     * Get the current value of the logical clock for this peer.
                     */
                    int lc = (int) ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock();

                    if (v.id == ((long) i)) {
                        /*
                         * A leader executes this part of the code. If it is the first leader to be
                         * elected, then it fails right after. Otherwise, it waits until it has enough
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
                                    hs.add(new TestVote(i, v.id));

                                    if(countVotes(hs, v.id) > (count/2)){
                                        leader = i;
                                        LOG.info("Got majority: " + i);
                                    } else {
                                        voteMap.wait(3000);
                                        LOG.info("Notified or expired: " + i);
                                        hs = voteMap.get(lc);
                                        if(countVotes(hs, v.id) > (count/2)){
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
                            LOG.info("Voting on " + votes[i].id + ", round " + ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock());
                            if(voteMap.get(lc) == null)
                                voteMap.put(lc, new HashSet<TestVote>());
                            HashSet<TestVote> hs = voteMap.get(lc);
                            hs.add(new TestVote(i, votes[i].id));
                            if(countVotes(hs, votes[i].id) > (count/2)){
                                LOG.info("Logical clock: " + lc + ", " + votes[i].id);
                                voteMap.notify();
                            }
                        }

                        /*
                         * In this part a follower waits until the leader notifies it, and remove its
                         * vote if the leader takes too long to respond.
                         */
                        synchronized(FLETest.this){
                            if (leader != votes[i].id) FLETest.this.wait(3000);

                            LOG.info("The leader: " + leader + " and my vote " + votes[i].id);
                            synchronized(voteMap){
                                if (leader == votes[i].id) {
                                    synchronized(finalObj){
                                        successCount++;
                                        if(successCount > (count/2)) finalObj.notify();
                                    }
                                    break;
                                } else {
                                    HashSet<TestVote> hs = voteMap.get(lc);
                                    TestVote toRemove = null;
                                    for(TestVote tv : hs){
                                        if(v.id == i){
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

        LOG.info("TestLE: " + getName()+ ", " + count);
        for(int i = 0; i < count; i++) {
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress(PortAssignment.unique()),
                    new InetSocketAddress(PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = PortAssignment.unique();
        }

        for(int i = 0; i < le.length; i++) {
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i],
                    port[i], 3, i, 2, 2, 2);
            peer.startLeaderElection();
            LEThread thread = new LEThread(peer, i);
            thread.start();
            threads.add(thread);
        }
        LOG.info("Started threads " + getName());


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
           fail("Fewer than a a majority has joined");
       }

       if(threads.get((int) leader).isAlive()){
           fail("Leader hasn't joined: " + leader);
       }
    }
}
