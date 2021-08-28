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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class QuorumOracleMajTest extends QuorumBaseOracle_2Nodes {

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
            if (qp.getPeerState() == QuorumPeer.ServerState.FOLLOWING) {
                bean = String.format("%s:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Follower", MBeanRegistry.DOMAIN, i, i);
            } else if (qp.getPeerState() == QuorumPeer.ServerState.LEADING) {
                bean = String.format("%s:name0=ReplicatedServer_id%d,name1=replica.%d,name2=Leader", MBeanRegistry.DOMAIN, i, i);
            }
            electionTimeTaken = (Long) JMXEnv.ensureBeanAttribute(bean, "ElectionTimeTaken");
            assertTrue(electionTimeTaken >= 0, "Wrong electionTimeTaken value!");
        }

        tearDown();
        //setup servers 1-2 to be followers
        // id=1, oracle is false; id=2, oracle is true
        setUp();

        QuorumPeer s;
        int leader;
        if ((leader = getLeaderIndex()) == 1) {
            s = s1;
        } else {
            s = s2;
        }

        noDropConectionTest(s);

        dropConnectionTest(s, leader);

    }

    private void noDropConectionTest(QuorumPeer s) {
        Leader.Proposal p = new Leader.Proposal();


        p.addQuorumVerifier(s.getQuorumVerifier());

        // 1 followers out of 2 is not a majority
        p.addAck(Long.valueOf(1));
        assertEquals(false, p.hasAllQuorums());

        // 6 is not in the view - its vote shouldn't count
        p.addAck(Long.valueOf(6));
        assertEquals(false, p.hasAllQuorums());

        // 2 followers out of 2 is good
        p.addAck(Long.valueOf(2));
        assertEquals(true, p.hasAllQuorums());

    }


    private void dropConnectionTest(QuorumPeer s, int leader) {
        Leader.Proposal p = new Leader.Proposal();
        p.addQuorumVerifier(s.getQuorumVerifier());

        ArrayList<LearnerHandler> fake = new ArrayList<>();

        LearnerHandler f = null;
        fake.add(f);

        s.getQuorumVerifier().updateNeedOracle(fake);
        // still have valid followers, the oracle should not take place
        assertEquals(false, s.getQuorumVerifier().getNeedOracle());

        fake.remove(0);
        s.getQuorumVerifier().updateNeedOracle(fake);
        // lose all of followers, the oracle should take place
        assertEquals(true, s.getQuorumVerifier().getNeedOracle());


        // when leader is 1, we expect false.
        // when leader is 2, we expect true.
        assertEquals(leader != 1, p.hasAllQuorums());
    }
}
