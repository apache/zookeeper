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
import java.util.HashMap;

import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumCnxManager;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.Assert;
import org.junit.Test;

public class FLEPredicateTest extends ZKTestCase {
    
    protected static final Logger LOG = LoggerFactory.getLogger(FLEPredicateTest.class);
    
    class MockFLE extends FastLeaderElection {
        MockFLE(QuorumPeer peer){
            super(peer, new QuorumCnxManager(peer));
        }
        
        boolean predicate(long newId, long newZxid, long newEpoch, long curId, long curZxid, long curEpoch){
            return this.totalOrderPredicate(newId, newZxid, newEpoch, curId, curZxid, curEpoch);
        }
    }
    
    
    HashMap<Long,QuorumServer> peers;
    
    @Test
    public void testPredicate() throws IOException {
        
        peers = new HashMap<Long,QuorumServer>(3);
        
        /*
         * Creates list of peers.
         */
        for(int i = 0; i < 3; i++) {
            peers.put(Long.valueOf(i),
                    new QuorumServer(i,
                            new InetSocketAddress(PortAssignment.unique()),
                    new InetSocketAddress(PortAssignment.unique())));
        }

        /*
         * Creating peer.
         */
        try{
            File tmpDir = ClientBase.createTmpDir();
            QuorumPeer peer = new QuorumPeer(peers, tmpDir, tmpDir,
                                        PortAssignment.unique(), 3, 0, 1000, 2, 2);
        
            MockFLE mock = new MockFLE(peer);
            
            /*
             * Lower epoch must return false
             */
            
            Assert.assertFalse (mock.predicate(4L, 0L, 0L, 3L, 0L, 2L));
            
            /*
             * Later epoch
             */
            Assert.assertTrue (mock.predicate(0L, 0L, 1L, 1L, 0L, 0L));
        
            /*
             * Higher zxid
             */
            Assert.assertTrue(mock.predicate(0L, 1L, 0L, 1L, 0L, 0L));
        
            /*
             * Higher id
             */
            Assert.assertTrue(mock.predicate(1L, 1L, 0L, 0L, 1L, 0L));
        } catch (IOException e) {
            LOG.error("Exception while creating quorum peer", e);
            Assert.fail("Exception while creating quorum peer");
        }
    }
}
