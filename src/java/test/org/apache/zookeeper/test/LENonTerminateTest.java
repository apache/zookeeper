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
import java.nio.ByteBuffer;
import java.util.HashMap;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.Test;

/**
 * Tests that a particular run of LeaderElection terminates correctly.
 */
public class LENonTerminateTest extends TestCase {
    protected static final Logger LOG = Logger.getLogger(FLELostMessageTest.class);
    
    int count;
    HashMap<Long,QuorumServer> peers;
    File tmpdir[];
    int port[];   
   
    @Override
    public void setUp() throws Exception {
        count = 3;

        peers = new HashMap<Long,QuorumServer>(count);
        tmpdir = new File[count];
        port = new int[count];
        
        LOG.info("SetUp " + getName());
    }

    @Override
    public void tearDown() throws Exception {
        LOG.info("FINISHED " + getName());
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
            try{
                Vote v = null;
                peer.setPeerState(ServerState.LOOKING);
                LOG.info("Going to call leader election: " + i);
                v = peer.getElectionAlg().lookForLeader();

                if (v == null){
                    fail("Thread " + i + " got a null vote");
                }

                /*
                 * A real zookeeper would take care of setting the current vote. Here
                 * we do it manually.
                 */
                peer.setCurrentVote(v);

                LOG.info("Finished election: " + i + ", " + v.id);                    
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
        LOG.info("TestNonTermination: " + getName()+ ", " + count);
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
         * peer1 and peer2 are A and B in the above example. 
         */
        QuorumPeer peer1 = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 0, 0, 2, 2, 2);
        peer1.startLeaderElection();
        LEThread thread1 = new LEThread(peer1, 0);
        thread1.start();
        
        QuorumPeer peer2 = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 0, 1, 2, 2, 2);
        peer2.startLeaderElection();        
        LEThread thread2 = new LEThread(peer2, 1);
        thread2.start();
                            
        /*
         * Start mock server.
         */
        Thread thread3 = new Thread() { 
            public void run() {
                try {
                    mockServer();
                } catch (Exception e) {
                    LOG.error(e);
                    fail("Exception when running mocked server " + e);
                }
            }
        };        
        
        thread3.start();
        /*
         * Occasionally seen false negatives with a 5s timeout.
         */
        thread1.join(15000);
        thread2.join(15000);
        thread3.join(15000);
        if (thread1.isAlive() || thread2.isAlive() || thread3.isAlive()) {
            fail("Threads didn't join");
        }
    }
     
    /**
     * MockServer plays the role of peer C. Respond to two requests for votes
     * with vote for self and then fail. 
     */
    void mockServer() throws InterruptedException, IOException {          
        byte b[] = new byte[36];
        ByteBuffer responseBuffer = ByteBuffer.wrap(b);
        DatagramPacket packet = new DatagramPacket(b, b.length);
        QuorumServer server = peers.get(Long.valueOf(2));
        DatagramSocket udpSocket = new DatagramSocket(server.addr.getPort());
        Vote current = new Vote(2, 1);
        for (int i=0;i<2;++i) {
            udpSocket.receive(packet);
            responseBuffer.clear();
            responseBuffer.getInt(); // Skip the xid
            responseBuffer.putLong(2);
            
            responseBuffer.putLong(current.id);
            responseBuffer.putLong(current.zxid);
            packet.setData(b);
            udpSocket.send(packet);
        }
    }    
}
