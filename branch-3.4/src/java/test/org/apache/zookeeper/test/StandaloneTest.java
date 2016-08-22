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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Standalone server tests.
 */
public class StandaloneTest extends QuorumPeerTestBase implements Watcher{
    protected static final Logger LOG =
        LoggerFactory.getLogger(StandaloneTest.class);    
      
    /**
     * Ensure that a single standalone server comes up when misconfigured
     * with a single server.# line in the configuration. This handles the
     * case of HBase, which configures zoo.cfg in this way. Maintain b/w
     * compatibility.
     * TODO remove in a future version (4.0.0 hopefully)
     */
    @Test
    public void testStandaloneQuorum() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();        
        
        String quorumCfgSection =
            "server.1=127.0.0.1:" + (PortAssignment.unique())
            + ":" + (PortAssignment.unique()) + "\n";
                    
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        q1.start();
        try {
            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
        } finally {
            q1.shutdown();
        }
    }    
    
}
