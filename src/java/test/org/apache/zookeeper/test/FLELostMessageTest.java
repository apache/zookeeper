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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class FLELostMessageTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(FLELostMessageTest.class);

    
    int count;
    HashMap<Long,QuorumServer> peers;
    File tmpdir[];
    int port[];
    
    QuorumCnxManager cnxManager;
   
    @Before
    public void setUp() throws Exception {
        count = 3;

        peers = new HashMap<Long,QuorumServer>(count);
        tmpdir = new File[count];
        port = new int[count];
    }

    @After
    public void tearDown() throws Exception {
        cnxManager.halt();
    }


    class LEThread extends Thread {
        int i;
        QuorumPeer peer;

        LEThread(QuorumPeer peer, int i) {
            this.i = i;
            this.peer = peer;
            LOG.info("Constructor: " + getName());

        }

        public void run(){
            boolean flag = true;
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
                    
                Assert.assertTrue("State is not leading.", peer.getPeerState() == ServerState.LEADING);
            } catch (Exception e) {
                e.printStackTrace();
            }
            LOG.info("Joining");
        }
    }
    @Test
    public void testLostMessage() throws Exception {
        FastLeaderElection le[] = new FastLeaderElection[count];
        
        LOG.info("TestLE: " + getTestName()+ ", " + count);
        for(int i = 0; i < count; i++) {
            int clientport = PortAssignment.unique();
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress(clientport),
                            new InetSocketAddress(PortAssignment.unique())));
            tmpdir[i] = ClientBase.createTmpDir();
            port[i] = clientport;
        }
        
        /*
         * Start server 0
         */
            
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 1000, 2, 2);
        peer.startLeaderElection();
        LEThread thread = new LEThread(peer, 1);
        thread.start();
            
        /*
         * Start mock server 1
         */
        mockServer();
        thread.join(5000);
        if (thread.isAlive()) {
            Assert.fail("Threads didn't join");
        }
    }
        
    ByteBuffer createMsg(int state, long leader, long zxid, long epoch){
        byte requestBytes[] = new byte[28];
        ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);  
        
        /*
         * Building notification packet to send
         */
                
        requestBuffer.clear();
        requestBuffer.putInt(state);
        requestBuffer.putLong(leader);
        requestBuffer.putLong(zxid);
        requestBuffer.putLong(epoch);
        
        return requestBuffer;
    }
        
    void mockServer() throws InterruptedException, IOException {
        /*
         * Create an instance of the connection manager
         */
        QuorumPeer peer = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2);
        cnxManager = new QuorumCnxManager(peer);
        QuorumCnxManager.Listener listener = cnxManager.listener;
        if(listener != null){
            listener.start();
        } else {
            LOG.error("Null listener when initializing cnx manager");
        }
        
        cnxManager.toSend(new Long(1), createMsg(ServerState.LOOKING.ordinal(), 0, 0, 1));
        cnxManager.recvQueue.take();
        cnxManager.toSend(new Long(1), createMsg(ServerState.FOLLOWING.ordinal(), 1, 0, 1));  
    }
}
