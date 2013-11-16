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

import java.nio.ByteBuffer;

import org.apache.zookeeper.server.quorum.FastLeaderElection;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.Vote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Assert;

import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;

public class FLETestUtils {
    protected static final Logger LOG = LoggerFactory.getLogger(FLETestUtils.class);
    
    
    /*
     * Thread to run an instance of leader election for 
     * a given quorum peer.
     */
    static class LEThread extends Thread {
        private int i;
        private QuorumPeer peer;

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
    
    /*
     * Creates a leader election notification message.
     */
    
    static ByteBuffer createMsg(int state, long leader, long zxid, long epoch){
        return FastLeaderElection.buildMsg(state, leader, zxid, 1, epoch);
    }

}