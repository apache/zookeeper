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

import static org.junit.Assert.*;

import java.util.Arrays;

import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.apache.zookeeper.server.quorum.QuorumStats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ObserverLETest extends ZKTestCase {
    final QuorumBase qb = new QuorumBase();
    final ClientTest ct = new ClientTest();

    @Before
    public void establishThreeParticipantOneObserverEnsemble() throws Exception {
        qb.setUp(true);
        ct.hostPort = qb.hostPort;
        ct.setUpAll();
        qb.s5.shutdown();
    }

    @After
    public void shutdownQuorum() throws Exception {
        ct.tearDownAll();
        qb.tearDown();
    }

    /**
     * See ZOOKEEPER-1294. Confirms that an observer will not support the quorum
     * of a leader by forming a 5-node, 2-observer ensemble (so quorum size is 2).
     * When all but the leader and one observer are shut down, the leader should
     * enter the 'looking' state, not stay in the 'leading' state.
     */
    @Test
    public void testLEWithObserver() throws Exception {
        QuorumPeer leader = null;
        for (QuorumPeer server : Arrays.asList(qb.s1, qb.s2, qb.s3)) {
            if (server.getServerState().equals(
                    QuorumStats.Provider.FOLLOWING_STATE)) {
                server.shutdown();
                assertTrue("Waiting for server down", ClientBase
                        .waitForServerDown("127.0.0.1:"
                                + server.getClientPort(),
                                ClientBase.CONNECTION_TIMEOUT));
            } else {
                assertNull("More than one leader found", leader);
                leader = server;
            }
        }
        assertTrue("Leader is not in Looking state", ClientBase
                .waitForServerState(leader, ClientBase.CONNECTION_TIMEOUT,
                        QuorumStats.Provider.LOOKING_STATE));
    }

}
