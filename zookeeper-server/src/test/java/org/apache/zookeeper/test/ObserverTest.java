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

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObserverTest extends QuorumPeerTestBase implements Watcher {

    protected static final Logger LOG = LoggerFactory.getLogger(ObserverTest.class);

    ZooKeeper zk;

    /**
     * This test ensures that an Observer does not elect itself as a leader, or
     * indeed come up properly, if it is the lone member of an ensemble.
     * @throws Exception
     */
    @Test
    public void testObserverOnly() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();

        String quorumCfgSection = "server.1=127.0.0.1:" + (PortAssignment.unique()) + ":" + (PortAssignment.unique()) + ":observer;" + CLIENT_PORT_QP1 + "\n";

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        q1.join(ClientBase.CONNECTION_TIMEOUT);
        assertFalse(q1.isAlive());
    }

    /**
     * Ensure that observer only comes up when a proper ensemble is configured.
     * (and will not come up with standalone server).
     */
    @Test
    public void testObserverWithStandlone() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();

        String quorumCfgSection = "server.1=127.0.0.1:" + (PortAssignment.unique()) + ":" + (PortAssignment.unique()) + ":observer\n"
                                  + "server.2=127.0.0.1:" + (PortAssignment.unique()) + ":" + (PortAssignment.unique()) + "\npeerType=observer\n";

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        q1.join(ClientBase.CONNECTION_TIMEOUT);
        assertFalse(q1.isAlive());
    }

}
