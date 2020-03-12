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

import static org.apache.zookeeper.client.ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET;
import static org.junit.Assert.assertTrue;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuorumRestartTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumRestartTest.class);
    private QuorumUtil qu;

    @Before
    public void setUp() throws Exception {
        System.setProperty(ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");

        // starting a 3 node ensemble without observers
        qu = new QuorumUtil(1, 2);
        qu.startAll();
    }

    /**
     * A basic test for rolling restart. We are restarting the ZooKeeper servers one by one,
     * starting from the first server. We always make sure that all the nodes joined to the
     * Quorum before moving forward.
     *
     * @throws Exception
     */
    @Test
    public void testRollingRestart() throws Exception {
        for (int serverToRestart = 1; serverToRestart <= 3; serverToRestart++) {
            LOG.info("***** restarting: " + serverToRestart);
            qu.shutdown(serverToRestart);

            assertTrue(String.format("Timeout during waiting for server %d to go down", serverToRestart),
                    ClientBase.waitForServerDown("127.0.0.1:" + qu.getPeer(serverToRestart).clientPort, ClientBase.CONNECTION_TIMEOUT));

            qu.restart(serverToRestart);

            final String errorMessage = "Not all the quorum members are connected after restarting server " + serverToRestart;
            waitFor(errorMessage, () -> qu.allPeersAreConnected(), 30);

            LOG.info("***** Restart {} succeeded", serverToRestart);
        }
    }

    /**
     * Testing one of the errors reported in ZOOKEEPER-2164, when some servers can not
     * rejoin to the Quorum after restarting the servers backwards
     *
     * @throws Exception
     */
    @Test
    public void testRollingRestartBackwards() throws Exception {
        for (int serverToRestart = 3; serverToRestart >= 1; serverToRestart--) {
            LOG.info("***** restarting: " + serverToRestart);
            qu.shutdown(serverToRestart);

            assertTrue(String.format("Timeout during waiting for server %d to go down", serverToRestart),
                    ClientBase.waitForServerDown("127.0.0.1:" + qu.getPeer(serverToRestart).clientPort, ClientBase.CONNECTION_TIMEOUT));

            qu.restart(serverToRestart);

            final String errorMessage = "Not all the quorum members are connected after restarting server " + serverToRestart;
            waitFor(errorMessage, () -> qu.allPeersAreConnected(), 30);

            LOG.info("***** Restart {} succeeded", serverToRestart);
        }
    }

    /**
     * Testing one of the errors reported in ZOOKEEPER-2164, when some servers can not
     * rejoin to the Quorum after restarting the current leader multiple times
     *
     * @throws Exception
     */
    @Test
    public void testRestartingLeaderMultipleTimes() throws Exception {
        for (int restartCount = 1; restartCount <= 3; restartCount++) {
            int leaderId = qu.getLeaderServer();
            LOG.info("***** new leader: " + leaderId);
            qu.shutdown(leaderId);

            assertTrue("Timeout during waiting for current leader to go down",
                    ClientBase.waitForServerDown("127.0.0.1:" + qu.getPeer(leaderId).clientPort, ClientBase.CONNECTION_TIMEOUT));

            String errorMessage = "No new leader was elected";
            waitFor(errorMessage, () -> qu.leaderExists() && qu.getLeaderServer() != leaderId, 30);

            qu.restart(leaderId);

            errorMessage = "Not all the quorum members are connected after restarting the old leader";
            waitFor(errorMessage, () -> qu.allPeersAreConnected(), 30);

            LOG.info("***** Leader Restart {} succeeded", restartCount);
        }
    }

    @After
    public void tearDown() throws Exception {
        qu.shutdownAll();
        System.clearProperty(ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
    }
}
