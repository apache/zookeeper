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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EphemeralThrottleTest extends QuorumPeerTestBase {

    protected static final Logger LOG = LoggerFactory.getLogger(EphemeralThrottleTest.class);

    static final int MAX_EPHEMERAL_NODES = 32;
    static final int NUM_SERVERS = 5;
    static final String PATH = "/eph-test";

    @Test(expected = KeeperException.ThrottledOpException.class)
    public void limitingEphemeralsTest() throws Exception {
        System.setProperty("zookeeper.ephemeral.count.limit", Integer.toString(MAX_EPHEMERAL_NODES));
        servers = LaunchServers(NUM_SERVERS);
        for (int i = 0; i < MAX_EPHEMERAL_NODES + 1; i++) {
            servers.zk[0].create(PATH + "-" + i, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
    }

    @Test(expected = KeeperException.ThrottledOpException.class)
    public void limitingSequentialEphemeralsTest() throws Exception {
        System.setProperty("zookeeper.ephemeral.count.limit", Integer.toString(MAX_EPHEMERAL_NODES));
        servers = LaunchServers(NUM_SERVERS);
        for (int i = 0; i < MAX_EPHEMERAL_NODES + 1; i++) {
            servers.zk[0].create(PATH, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        }
    }

    /**
     *  Verify that the ephemeral limit enforced correctly when there are delete operations.
     */
    @Test
    public void limitingEphemeralsWithDeletesTest() throws Exception {
        int numDelete = 8;
        System.setProperty("zookeeper.ephemeral.count.limit", Integer.toString(MAX_EPHEMERAL_NODES));
        servers = LaunchServers(NUM_SERVERS);
        for (int i = 0; i < MAX_EPHEMERAL_NODES / 2; i++) {
            servers.zk[0].create(PATH + "-" + i, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }
        for (int i = 0; i < numDelete; i++) {
            servers.zk[0].delete(PATH + "-" + i, -1);
        }
        for (int i = 0; i < (MAX_EPHEMERAL_NODES / 2) + numDelete; i++) {
            servers.zk[0].create(PATH, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        }

        boolean threw = false;
        try {
            servers.zk[0].create(PATH, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        } catch (KeeperException.ThrottledOpException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    /**
     *  Check that our emitted metric around the number of request rejections from too many ephemerals is accurate.
     */
    @Test
    public void rejectedEphemeralCreatesMetricsTest() throws Exception {
        int ephemeralExcess = 8;
        System.setProperty("zookeeper.ephemeral.count.limit", Integer.toString(MAX_EPHEMERAL_NODES));
        servers = LaunchServers(NUM_SERVERS);
        for (int i = 0; i < MAX_EPHEMERAL_NODES + ephemeralExcess; i++) {
            try {
                servers.zk[0].create(PATH + "-" + i, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            } catch (KeeperException.ThrottledOpException e) {
                LOG.info("Encountered ThrottledOpException as expected, continuing...");
            }
        }

        long actual = (long) MetricsUtils.currentServerMetrics().get("ephemeral_violation_request_rejection_count");
        assertEquals(ephemeralExcess, actual);
    }

    /**
     *  Test that the ephemeral limit is accurate in the case where an ephemeral node is deleted before it is committed.
     */
    CountDownLatch latch = null;
    @Test
    public void createThenDeleteBeforeCommitTest() throws Exception {
        System.setProperty("zookeeper.ephemeral.count.limit", Integer.toString(MAX_EPHEMERAL_NODES));

        String hostPort = "127.0.0.1:" + PortAssignment.unique();
        File tmpDir = ClientBase.createTmpDir();
        ClientBase.setupTestEnv();
        ZooKeeperServer server = new ZooKeeperServerWithLatch(tmpDir, tmpDir, 3000);
        final int port = Integer.parseInt(hostPort.split(":")[1]);
        ServerCnxnFactory cnxnFactory = ServerCnxnFactory.createFactory(port, -1);
        ServerMetrics.getMetrics().resetAll();
        cnxnFactory.startup(server);

        latch = new CountDownLatch(1);

        ZooKeeper zk = ClientBase.createZKClient(hostPort);
        zk.create(PATH, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        zk.delete(PATH, -1);

        latch.countDown();

        boolean noException = true;
        try {
            for (int i = 0; i < MAX_EPHEMERAL_NODES; i++) {
                zk.create(PATH, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            }
        } catch (KeeperException.ThrottledOpException e) {
            noException = false;
        }
        assertEquals(noException, true);

        boolean threw = false;
        try {
            zk.create(PATH, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
        } catch (KeeperException.ThrottledOpException e) {
            threw = true;
        }
        assertEquals(threw, true);
    }

    class ZooKeeperServerWithLatch extends ZooKeeperServer {
        public ZooKeeperServerWithLatch(File snapDir, File logDir, int tickTime) throws IOException {
            super(snapDir, logDir, tickTime);
        }

        @Override
        public DataTree.ProcessTxnResult processTxn(Request request) {
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {}
            }
            DataTree.ProcessTxnResult res = super.processTxn(request);
            return res;
        }
    }
}
