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
import java.util.ArrayList;

import org.apache.zookeeper.jmx.CommonNames;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuorumMajorityTest extends QuorumBase {
    protected static final Logger LOG = LoggerFactory.getLogger(QuorumMajorityTest.class);
    public static final long CONNECTION_TIMEOUT = ClientTest.CONNECTION_TIMEOUT;

    /***************************************************************/
    /* Test that the majority quorum verifier only counts votes from */
    /* followers in its view                                    */
    /***************************************************************/
    @Test
    public void testMajQuorums() throws Throwable {
        LOG.info("Verify QuorumPeer#electionTimeTaken jmx bean attribute");

        ArrayList<QuorumPeer> peers = getPeerList();
        for (int i = 1; i <= peers.size(); i++) {
            QuorumPeer qp = peers.get(i - 1);
            Long electionTimeTaken = -1L;
            String bean = "";
            if (qp.getPeerState() == ServerState.FOLLOWING) {
                bean = String.format(
                        "%s:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Follower",
                        CommonNames.DOMAIN, i, i);
            } else if (qp.getPeerState() == ServerState.LEADING) {
                bean = String.format(
                        "%s:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Leader",
                        CommonNames.DOMAIN, i, i);
            }
            electionTimeTaken = (Long) JMXEnv.ensureBeanAttribute(bean,
                    "ElectionTimeTaken");
            Assert.assertTrue("Wrong electionTimeTaken value!",
                    electionTimeTaken >= 0);
        }

       //setup servers 1-5 to be followers
       setUp(false);
        
       Proposal p = new Proposal();
       
        p.addQuorumVerifier(s1.getQuorumVerifier());
        
        // 2 followers out of 5 is not a majority
        p.addAck(Long.valueOf(1));
        p.addAck(Long.valueOf(2));        
        Assert.assertEquals(false, p.hasAllQuorums());
        
        // 6 is not in the view - its vote shouldn't count
        p.addAck(Long.valueOf(6));  
        Assert.assertEquals(false, p.hasAllQuorums());
        
        // 3 followers out of 5 are a majority of the voting view
        p.addAck(Long.valueOf(3));  
        Assert.assertEquals(true, p.hasAllQuorums());
        
       //setup servers 1-3 to be followers and 4 and 5 to be observers
       setUp(true);
       
       p = new Proposal();
       p.addQuorumVerifier(s1.getQuorumVerifier());
        
        // 1 follower out of 3 is not a majority
       p.addAck(Long.valueOf(1));      
        Assert.assertEquals(false, p.hasAllQuorums());
        
        // 4 and 5 are observers, their vote shouldn't count
        p.addAck(Long.valueOf(4));
        p.addAck(Long.valueOf(5));
        Assert.assertEquals(false, p.hasAllQuorums());
        
        // 6 is not in the view - its vote shouldn't count
        p.addAck(Long.valueOf(6));
        Assert.assertEquals(false, p.hasAllQuorums());
        
        // 2 followers out of 3 are a majority of the voting view
        p.addAck(Long.valueOf(2));
        Assert.assertEquals(true, p.hasAllQuorums());
    }
}
