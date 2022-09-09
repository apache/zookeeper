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

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.function.Supplier;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuorumPeerReadOnlyTest extends ZKTestCase {

    private String prePropertyReadOnlyValue = null;
    private QuorumUtil qu = new QuorumUtil(1);

    @BeforeEach
    void setReadOnlySystemProperty() {
        prePropertyReadOnlyValue = System.getProperty("readonlymode.enabled");
        System.setProperty("readonlymode.enabled", "true");
    }

    @AfterEach
    void restoreReadOnlySystemProperty() throws Exception {
        if (prePropertyReadOnlyValue == null) {
            System.clearProperty("readonlymode.enabled");
        } else {
            System.setProperty("readonlymode.enabled", prePropertyReadOnlyValue);
        }
        qu.tearDown();
    }

    /**
     * Test zxid is not set to a truncate value
     */
    @Test
    void testReadOnlyZxid() throws Exception {
        final int SERVER_COUNT = qu.ALL;
        final int WRITE_COUNT = 3;
        ZooKeeper[] zk = new ZooKeeper[SERVER_COUNT];
        CountdownWatcher[] watcher = new CountdownWatcher[SERVER_COUNT];
        qu.enableLocalSession(true);
        qu.startAll();

        for (int i = 0; i < SERVER_COUNT; i++) {
            watcher[i] = new CountdownWatcher();
            zk[i] = new ZooKeeper(qu.getConnString(), ClientBase.CONNECTION_TIMEOUT, watcher[i], true);
        }

        waitForAllConnected(watcher);

        zk[0].create("/test", "Test".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        for (int i = 0; i < WRITE_COUNT; i++) {
            zk[0].create("/test" + i, ("Test" + i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk[0].create("/test/" + i, ("Sub-Test" + i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        // Shutdown all clients first, to ensure that the close zxid is seen and logged by all servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i].close();
            watcher[i].reset();
        }

        qu.shutdownAll();
        qu.start(SERVER_COUNT);

        // Ensure that close will trigger a timeout
        zk[SERVER_COUNT - 1] = new ZooKeeper(qu.getConnString(), ClientBase.CONNECTION_TIMEOUT,
                watcher[SERVER_COUNT - 1], true) {
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

        watcher[SERVER_COUNT - 1].waitForReadOnlyConnected(ClientBase.CONNECTION_TIMEOUT);

        // Read some data
        assertTrue(Arrays.equals(zk[SERVER_COUNT - 1].getData("/test", false, null), "Test".getBytes()));

        // Kill the client, without notification
        zk[SERVER_COUNT - 1].close();
        watcher[SERVER_COUNT - 1].reset();

        // Start enough server for a quorum, but not all yet. Don't start any client, to avoid changing the zxid
        for (int i = SERVER_COUNT - 1; i > ((SERVER_COUNT - 1) / 2); i--) {
            qu.start(i);
        }
        waitFor(() -> qu.leaderExists(), ClientBase.CONNECTION_TIMEOUT);
        qu.getLeaderQuorumPeer();

        // Seems like without a waiting time, the test does succeeded sometimes without the fix applied
        Thread.sleep(1000);

        // Start the remaining servers
        for (int i = (SERVER_COUNT - 1) / 2; i > 0; i--) {
            qu.start(i);
        }
        waitFor(() -> qu.allPeersAreConnected(), ClientBase.CONNECTION_TIMEOUT);
        assertTrue(qu.allPeersAreConnected(), "Not all servers are connected");

        for (int i = 0; i < SERVER_COUNT; i++) {
            zk[i] = new ZooKeeper(qu.getConnString(), ClientBase.CONNECTION_TIMEOUT, watcher[i], true);
        }
        waitForAllConnected(watcher);

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
        qu.shutdownAll();
    }

    private void waitForAllConnected(CountdownWatcher[] watcher) throws Exception {
        long timeout = ClientBase.CONNECTION_TIMEOUT;
        long left = timeout;
        long expire = Time.currentElapsedTime() + timeout;
        for (int i = 0; i < watcher.length; i++) {
            watcher[i].waitForConnected(left);
            left = expire - Time.currentElapsedTime();
        }
    }

    private void waitFor(Supplier<Boolean> waitCondition, long timeout) {
        long left = timeout;
        long expire = Time.currentElapsedTime() + timeout;
        while (!waitCondition.get() && (left > 0)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                //ignore
            }
            left = expire - Time.currentElapsedTime();
        }
    }
}
