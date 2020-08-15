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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.fail;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.Vote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FLERestartTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(FLETest.class);

    private int count;
    private Map<Long, QuorumServer> peers;
    private List<FLERestartThread> restartThreads;
    private File[] tmpdir;
    private int[] port;
    private Semaphore finish;

    static class TestVote {

        long leader;

        TestVote(int id, long leader) {
            this.leader = leader;
        }

    }

    int countVotes(HashSet<TestVote> hs, long id) {
        int counter = 0;
        for (TestVote v : hs) {
            if (v.leader == id) {
                counter++;
            }
        }

        return counter;
    }

    @BeforeEach
    public void setUp() throws Exception {
        count = 3;
        peers = new HashMap<Long, QuorumServer>(count);
        restartThreads = new ArrayList<FLERestartThread>(count);
        tmpdir = new File[count];
        port = new int[count];
        finish = new Semaphore(0);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (int i = 0; i < restartThreads.size(); i++) {
            restartThreads.get(i).peer.getElectionAlg().shutdown();
        }
    }

    class FLERestartThread extends Thread {

        int i;
        QuorumPeer peer;
        int peerRound = 0;

        FLERestartThread(QuorumPeer peer, int i) {
            this.i = i;
            this.peer = peer;
            LOG.info("Constructor: {}", getName());
        }
        public void run() {
            try {
                Vote v = null;
                while (true) {
                    peer.setPeerState(ServerState.LOOKING);
                    LOG.info("Going to call leader election again.");
                    v = peer.getElectionAlg().lookForLeader();
                    if (v == null) {
                        LOG.info("Thread {} got a null vote", i);
                        break;
                    }

                    /*
                     * A real zookeeper would take care of setting the current vote. Here
                     * we do it manually.
                     */
                    peer.setCurrentVote(v);

                    LOG.info("Finished election: {}, {}", i, v.getId());
                    //votes[i] = v;

                    switch (i) {
                    case 0:
                        if (peerRound == 0) {
                            LOG.info("First peer, shutting it down");
                            QuorumBase.shutdown(peer);
                            restartThreads.get(i).peer.getElectionAlg().shutdown();

                            peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i], port[i], 3, i, 1000, 2, 2, 2);
                            peer.startLeaderElection();
                            peerRound++;
                        } else {
                            finish.release(2);
                            return;
                        }

                        break;
                    case 1:
                        LOG.info("Second entering case");
                        finish.acquire();
                        //if(threads.get(0).peer.getPeerState() == ServerState.LEADING ){
                        LOG.info("Release");

                        return;
                    case 2:
                        LOG.info("First peer, do nothing, just join");
                        finish.acquire();
                        //if(threads.get(0).peer.getPeerState() == ServerState.LEADING ){
                        LOG.info("Release");

                        return;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    @Test
    public void testLERestart() throws Exception {

        LOG.info("TestLE: {}, {}", getTestName(), count);
        for (int i = 0; i < count; i++) {
            peers.put((long) i, new QuorumServer(i, new InetSocketAddress("127.0.0.1", PortAssignment.unique()), new InetSocketAddress("127.0.0.1", PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = PortAssignment.unique();
        }

        for (int i = 0; i < count; i++) {
            QuorumPeer peer = new QuorumPeer(peers, tmpdir[i], tmpdir[i], port[i], 3, i, 1000, 2, 2, 2);
            peer.startLeaderElection();
            FLERestartThread thread = new FLERestartThread(peer, i);
            thread.start();
            restartThreads.add(thread);
        }
        LOG.info("Started threads {}", getTestName());
        for (int i = 0; i < restartThreads.size(); i++) {
            restartThreads.get(i).join(10000);
            if (restartThreads.get(i).isAlive()) {
                fail("Threads didn't join");
            }

        }
    }

}
