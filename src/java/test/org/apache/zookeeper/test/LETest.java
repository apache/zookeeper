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

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.LeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;

public class LETest extends TestCase {
    private static final Logger LOG = Logger.getLogger(LETest.class);
    volatile Vote votes[];
    volatile boolean leaderDies;
    volatile long leader = -1;
    Random rand = new Random();
    class LEThread extends Thread {
        LeaderElection le;
        int i;
        QuorumPeer peer;
        LEThread(LeaderElection le, QuorumPeer peer, int i) {
            this.le = le;
            this.i = i;
            this.peer = peer;
        }
        public void run() {
            try {
                Vote v = null;
                while(true) {
                    v = le.lookForLeader();
                    votes[i] = v;
                    if (v.id == i) {
                        synchronized(LETest.this) {
                            if (leaderDies) {
                                leaderDies = false;
                                peer.stopLeaderElection();
                                LOG.info("Leader " + i + " dying");
                                leader = -2;
                            } else {
                                leader = i;
                            }
                            LETest.this.notifyAll();
                        }
                        break;
                    }
                    synchronized(LETest.this) {
                        if (leader == -1) {
                            LETest.this.wait();
                        }
                        if (leader == v.id) {
                            break;
                        }
                    }
                    Thread.sleep(rand.nextInt(1000));
                    peer.setCurrentVote(new Vote(peer.getId(), 0));
                }
                LOG.info("Thread " + i + " votes " + v);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    public void testLE() throws Exception {
        int count = 30;
        HashMap<Long,QuorumServer> peers = new HashMap<Long,QuorumServer>(count);
        ArrayList<LEThread> threads = new ArrayList<LEThread>(count);
        File tmpdir[] = new File[count];
        int port[] = new int[count];
        votes = new Vote[count];
        for(int i = 0; i < count; i++) {
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress("127.0.0.1",
                                    PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = PortAssignment.unique();
        }
        LeaderElection le[] = new LeaderElection[count];
        leaderDies = true;
        boolean allowOneBadLeader = leaderDies;
        for(int i = 0; i < le.length; i++) {
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i],
                    port[i], 0, i, 2, 2, 2);
            peer.startLeaderElection();
            le[i] = new LeaderElection(peer);
            LEThread thread = new LEThread(le[i], peer, i);
            thread.start();
            threads.add(thread);
        }
        for(int i = 0; i < threads.size(); i++) {
            threads.get(i).join(15000);
            if (threads.get(i).isAlive()) {
                fail("Threads didn't join");
            }
        }
        long id = votes[0].id;
        for(int i = 1; i < votes.length; i++) {
            if (votes[i] == null) {
                fail("Thread " + i + " had a null vote");
            }
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
