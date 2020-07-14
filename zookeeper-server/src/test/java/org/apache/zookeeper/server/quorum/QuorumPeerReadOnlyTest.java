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

package org.apache.zookeeper.server.quorum;

import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.test.ClientBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class QuorumPeerReadOnlyTest extends QuorumPeerTestBase {

    String prePropertyReadOnlyValue = null;

    @Before
    public void setReadOnlySystemProperty() {
        prePropertyReadOnlyValue = System.getProperty("readonlymode.enabled");
        System.setProperty("readonlymode.enabled", "true");
    }

    @After
    public void restoreReadOnlySystemProperty() {
        if (prePropertyReadOnlyValue == null) {
            System.clearProperty("readonlymode.enabled");
        } else {
            System.setProperty("readonlymode.enabled", prePropertyReadOnlyValue);
        }
    }

    /**
     * Test zxid is not set to a truncate value
     */
    @Test
    public void testReadOnlyZxid() throws Exception {
        ClientBase.setupTestEnv();
        final int SERVER_COUNT = 3;
        final int WRITE_COUNT = 3;
        final int[] clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            sb.append("server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique() + "\n");
        }
        String quorumCfgSection = sb.toString();

        MainThread[] mt = new MainThread[SERVER_COUNT];
        ZooKeeper[] zk = new ZooKeeper[SERVER_COUNT];


        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], quorumCfgSection + "\nclientPort=" + clientPorts[i]);
            mt[i].start();

        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this, true);
        }

        waitForAll(zk, States.CONNECTED);

        zk[0].create("/test", "Test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        for (int i = 0; i < WRITE_COUNT; i++) {
            zk[0].create("/test" + i, ("Test" + i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk[0].create("/test/" + i, ("Sub-Test" + i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        // Shutdown all clients first, to ensure that the close zxid is seen and logged by all servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
        }
        // Shutdown all servers, before starting one again, as the commonly used data storage is cleaned
        // up, when the first server is stopped
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }

        mt[SERVER_COUNT - 1].start();
        // Ensure that close will trigger a timeout
        zk[SERVER_COUNT - 1] = new ZooKeeper("127.0.0.1:" + clientPorts[SERVER_COUNT - 1], ClientBase.CONNECTION_TIMEOUT, this, true) {
            @Override
            public void close() {
                getTestable().injectSessionExpiration();
                cnxn.disconnect();
                try {
                    Thread.sleep(ClientBase.CONNECTION_TIMEOUT * 2);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };

        waitForAll(new ZooKeeper[] {zk[SERVER_COUNT - 1]}, States.CONNECTEDREADONLY);

        // Read some data
        assertTrue(Arrays.equals(zk[SERVER_COUNT - 1].getData("/test", false, null), "Test".getBytes()));

        // Kill the client, without notification
        zk[SERVER_COUNT - 1].close();

        // Start enough server for a quorum, but not all yet. Don't start any client, to avoid changing the zxid
        for (int i = SERVER_COUNT - 1; i >= ((SERVER_COUNT - 1) / 2); i--) {
            if (i != SERVER_COUNT - 1) {
                mt[i].start();
            }
        }
        ensureLeaderIsPresent(mt);

        // Seems like without a waiting time, the test does succeeded sometimes without the fix applied
        Thread.sleep(1000);

        // Start the remaining servers
        for (int i = (SERVER_COUNT - 1) / 2 - 1; i >= 0; i--) {
            mt[i].start();
        }
        ensureRemainingserversPresent(mt);

        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i] = new ZooKeeper("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT, this, true);
        }
        waitForAll(zk, States.CONNECTED);

        // Ensure that all clients can read the data
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(Arrays.equals(zk[i].getData("/test", false, null), "Test".getBytes()));
            for (int j = 0; j < WRITE_COUNT; j++) {
                assertTrue(Arrays.equals(zk[i].getData("/test" + j, false, null), ("Test" + j).getBytes()));
                assertTrue(Arrays.equals(zk[i].getData("/test/" + j, false, null), ("Sub-Test" + j).getBytes()));
            }
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
        }
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    private void ensureLeaderIsPresent(MainThread[] mt) throws InterruptedException {
        boolean leaderFound = false;
        int searchCount = 0;
        do {
            Thread.sleep(100);
            for (int i = mt.length - 1; i >= ((mt.length - 1) / 2); i--) {
                if (mt[i].main.quorumPeer.leader != null) {
                    leaderFound = true;
                    break;
                }
            }
            if (searchCount++ >= 300) {
                throw new RuntimeException("Waiting too long");
            }
        } while(!leaderFound);
    }

    private void ensureRemainingserversPresent(MainThread[] mt) throws InterruptedException {
        int searchCount = 0;
        boolean missingConnection;
        do {
            Thread.sleep(100);
            missingConnection = false;
            for (int i = (mt.length - 1) / 2 - 1; i >= 0; i--) {
                if (mt[i].main.quorumPeer.follower == null && mt[i].main.quorumPeer.leader == null) {
                    missingConnection = true;
                    break;
                }
            }
            if (searchCount++ >= 300) {
                throw new RuntimeException("Waiting too long");
            }
        } while(missingConnection);
    }
}
