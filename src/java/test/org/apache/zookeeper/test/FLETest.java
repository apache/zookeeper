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
import java.util.Random;

import org.apache.log4j.Logger;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumStats;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;

import junit.framework.TestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FLETest extends TestCase {
    protected static final Logger LOG = Logger.getLogger(FLETest.class);

    int count;
    int baseport;
    int baseLEport;
    HashMap<Long,QuorumServer> peers; 
    ArrayList<LEThread> threads;
    File tmpdir[];
    int port[];
    
    volatile Vote votes[];
    volatile boolean leaderDies;
    volatile long leader = -1;
    volatile int round = 1; 
    Random rand = new Random();
    
    @Override
    public void setUp() throws Exception {
        count = 7;
        baseport= 33003;
        baseLEport = 43003;
        
        peers = new HashMap<Long,QuorumServer>(count);
        threads = new ArrayList<LEThread>(count);
        votes = new Vote[count];
        tmpdir = new File[count];
        port = new int[count];
        
        QuorumStats.registerAsConcrete();
        LOG.info("SetUp " + getName());
    }

    @Override
    public void tearDown() throws Exception {
        for(int i = 0; i < threads.size(); i++) {
            ((FastLeaderElection) threads.get(i).peer.getElectionAlg()).shutdown();
        }
        LOG.info("FINISHED " + getName());
    }
    
    class LEThread extends Thread {
        FastLeaderElection le;
        int i;
        QuorumPeer peer;
    int peerRound = 1;

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
            peer.setCurrentVote(v);
            
                    LOG.info("Finished election: " + i + ", " + v.id);
                    votes[i] = v;
                    if (v.id == ((long) i)) {
                        LOG.debug("I'm the leader");
                        synchronized(FLETest.this) {
                            if (leaderDies) {
                                LOG.debug("Leader " + i + " dying");
                                leaderDies = false;
                                ((FastLeaderElection) peer.getElectionAlg()).shutdown();
                                leader = -1;
                                LOG.debug("Leader " + i + " dead");
                            } else {
                                leader = i; 
                            }
                round++; 
                            FLETest.this.notifyAll();
                        }
                        break;
                    }
                    synchronized(FLETest.this) {
                        if (round == ((FastLeaderElection) peer.getElectionAlg()).getLogicalClock()) {
                int tmp_round = round;
                            FLETest.this.wait(1000);
                if(tmp_round == round) round++;
                        }
            LOG.info("The leader: " + leader + " and my vote " + votes[i].id);
                        if (leader == votes[i].id) {
                            break;
                        }
            peerRound++;
                    }
                    Thread.sleep(rand.nextInt(1000));
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
            peers.put(Long.valueOf(i), new QuorumServer(i, new InetSocketAddress(baseport+100+i), 
                    new InetSocketAddress(baseLEport+100+i)));
            tmpdir[i] = File.createTempFile("letest", "test");
            port[i] = baseport+i;    
        }
        
        for(int i = 0; i < le.length; i++) {
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i], port[i], 3, i, 2, 2, 2);
            peer.startLeaderElection();
            //le[i] = new FastLeaderElection(peer, new QuorumCnxManager(peer));
            LEThread thread = new LEThread(peer, i);
            thread.start();
            threads.add(thread);
        }
        LOG.info("Started threads " + getName());
        
       for(int i = 0; i < threads.size(); i++) {
            threads.get(i).join(20000);
            if (threads.get(i).isAlive()) {
                fail("Threads didn't join: " + i);
            }
        }
        long id = votes[0].id;
        for(int i = 1; i < votes.length; i++) {
            if (votes[i] == null) {
                fail("Thread " + i + " had a null vote");
            }
        LOG.info("Final leader info: " + i + ", " + votes[i].id + ", " + id); 
            if (votes[i].id != id) {
                if (allowOneBadLeader && votes[i].id == i) {
                    allowOneBadLeader = false;
                } else {
                    fail("Thread " + i + " got " + votes[i].id + " expected " + id);
                }
            }
        }
    }
}
