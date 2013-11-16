/* Licensed to the Apache Software Foundation (ASF) under one
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


public class FLEBackwardElectionRoundTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory.getLogger(FLELostMessageTest.class);
    
    int count;
    HashMap<Long,QuorumServer> peers;
    File tmpdir[];
    int port[];

    QuorumCnxManager cnxManagers[];

    @Before
    public void setUp() throws Exception {
        count = 3;

        peers = new HashMap<Long,QuorumServer>(count);
        tmpdir = new File[count];
        port = new int[count];
        cnxManagers = new QuorumCnxManager[count - 1];
    }

    @After
    public void tearDown() throws Exception {
        for(int i = 0; i < (count - 1); i++){
            if(cnxManagers[i] != null){
                cnxManagers[i].halt();
            }
        }
    }
    
    /**
     * This test is checking the following case. A server S is
     * currently LOOKING and it receives notifications from 
     * a quorum indicating they are following S. The election
     * round E of S is higher than the election round E' in the 
     * notification messages, so S becomes the leader and sets
     * its epoch back to E'. In the meanwhile, one or more
     * followers turn to LOOKING and elect S in election round E.
     * Having leader and followers with different election rounds
     * might prevent other servers from electing a leader because
     * they can't get a consistent set of notifications from a 
     * quorum. 
     * 
     * {@link https://issues.apache.org/jira/browse/ZOOKEEPER-1514}
     *    
     * 
     * @throws Exception
     */
    
    @Test
    public void testBackwardElectionRound() throws Exception {
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

        QuorumPeer peer = new QuorumPeer(peers, tmpdir[0], tmpdir[0], port[0], 3, 0, 1000, 2, 2);
        peer.startLeaderElection();
        FLETestUtils.LEThread thread = new FLETestUtils.LEThread(peer, 0);
        thread.start();  
        
        
        /*
         * Start mock server 1
         */
        QuorumPeer mockPeer = new QuorumPeer(peers, tmpdir[1], tmpdir[1], port[1], 3, 1, 1000, 2, 2);
        cnxManagers[0] = new QuorumCnxManager(mockPeer);
        QuorumCnxManager.Listener listener = cnxManagers[0].listener;
        listener.start();

        ByteBuffer msg = FLETestUtils.createMsg(ServerState.FOLLOWING.ordinal(), 0, 0, 1);
        cnxManagers[0].toSend(0l, msg);
        
        /*
         * Start mock server 2
         */
        mockPeer = new QuorumPeer(peers, tmpdir[2], tmpdir[2], port[2], 3, 2, 1000, 2, 2);
        cnxManagers[1] = new QuorumCnxManager(mockPeer);
        listener = cnxManagers[1].listener;
        listener.start();

        cnxManagers[1].toSend(0l, msg);
        
        /*
         * Run another instance of leader election.
         */
        thread.join(5000);
        thread = new FLETestUtils.LEThread(peer, 0);
        thread.start();
        
        /*
         * Send the same messages, this time should not make 0 the leader.
         */
        cnxManagers[0].toSend(0l, msg);
        cnxManagers[1].toSend(0l, msg);
        
        
        thread.join(5000);
        
        if (!thread.isAlive()) {
            Assert.fail("Should not have joined");
        }
        
    }
}