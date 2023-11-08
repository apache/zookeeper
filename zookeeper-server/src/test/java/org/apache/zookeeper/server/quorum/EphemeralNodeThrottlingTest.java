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

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

public class EphemeralNodeThrottlingTest extends QuorumPeerTestBase {

    protected static final Logger LOG = LoggerFactory.getLogger(EphemeralNodeThrottlingTest.class);

    public static final String EPHEMERAL_BYTE_LIMIT_KEY = "zookeeper.ephemeralNodes.total.byte.limit";
    public static final String EPHEMERAL_BYTE_LIMIT_VIOLATION_KEY = "ephemeral_node_limit_violation";
    static final int DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT = 2000;
    static final int NUM_SERVERS = 5;
    static final String TEST_PATH = "/ephemeral-throttling-test";

    @BeforeClass
    public static void setUpClass() {
        System.setProperty(EPHEMERAL_BYTE_LIMIT_KEY, Integer.toString(DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT));
    }

    @AfterClass
    public static void tearDownClass() {
        System.clearProperty(EPHEMERAL_BYTE_LIMIT_KEY);
    }

    @Before
    public void setUpMethod() throws Exception {
        servers = LaunchServers(NUM_SERVERS);
    }

    @After
    public void tearDownMethod() throws Exception {
        servers.shutDownAllServers();
    }

    @Test
    public void byteSizeTest() {
        System.setProperty(EPHEMERAL_BYTE_LIMIT_KEY, Integer.toString(DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT));
        ZooKeeper leaderServer = servers.zk[servers.findLeader()];
        int cumulativeBytes = 0;
        int i = 0;
        while (cumulativeBytes <= DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT) {
            cumulativeBytes += BinaryOutputArchive.getSerializedStringByteSize(TEST_PATH +i);
            try {
                leaderServer.create(TEST_PATH + i++, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            } catch (Exception e) {
                break;
            }
        }
        long actual = (long) MetricsUtils.currentServerMetrics().get(EPHEMERAL_BYTE_LIMIT_VIOLATION_KEY);
        System.out.println("byte limit property is: " + Integer.getInteger(EPHEMERAL_BYTE_LIMIT_KEY));
        assertEquals(1, actual);
    }

    @Test
    public void limitingEphemeralsTest() throws Exception {
        System.setProperty(EPHEMERAL_BYTE_LIMIT_KEY, Integer.toString(DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT));
        ZooKeeper leaderServer = servers.zk[servers.findLeader()];
        String leaderSubPath = TEST_PATH + "-leader-";
        assertTrue(checkLimitEnforcedForServer(leaderServer, leaderSubPath, CreateMode.EPHEMERAL));

        ZooKeeper followerServer = servers.zk[servers.findAnyFollower()];
        String followerSubPath = TEST_PATH + "-follower-";
        assertTrue(checkLimitEnforcedForServer(followerServer, followerSubPath, CreateMode.EPHEMERAL));

        // Assert both servers emitted failure metric
        long actual = (long) MetricsUtils.currentServerMetrics().get(EPHEMERAL_BYTE_LIMIT_VIOLATION_KEY);
        assertEquals(2, actual);
    }

    @Test
    public void limitingSequentialEphemeralsTest() throws Exception {
        System.setProperty(EPHEMERAL_BYTE_LIMIT_KEY, Integer.toString(DEFAULT_EPHEMERALNODES_TOTAL_BYTE_LIMIT));
        ZooKeeper leaderServer = servers.zk[servers.findLeader()];
        String leaderSubPath = TEST_PATH + "-leader-";
        assertTrue(checkLimitEnforcedForServer(leaderServer, leaderSubPath, CreateMode.EPHEMERAL_SEQUENTIAL));

        ZooKeeper followerServer = servers.zk[servers.findAnyFollower()];
        String followerSubPath = TEST_PATH + "-follower-";
        assertTrue(checkLimitEnforcedForServer(followerServer, followerSubPath, CreateMode.EPHEMERAL_SEQUENTIAL));

        // Assert both servers emitted failure metric
        long actual = (long) MetricsUtils.currentServerMetrics().get(EPHEMERAL_BYTE_LIMIT_VIOLATION_KEY);
        assertEquals(2, actual);
    }

    public boolean checkLimitEnforcedForServer(ZooKeeper server, String subPath, CreateMode mode) throws Exception {
        if (!mode.isEphemeral()) {
            return false;
        }

        int limit = Integer.getInteger(EPHEMERAL_BYTE_LIMIT_KEY);
        int cumulativeBytes = 0;

        if (mode.isSequential()) {
            int lastPathBytes = 0;
            while (cumulativeBytes + lastPathBytes <= limit) {
                String path = server.create(TEST_PATH + "-leader-", new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
                lastPathBytes = BinaryOutputArchive.getSerializedStringByteSize(path);
                cumulativeBytes += lastPathBytes;
            }

            try {
                server.create(TEST_PATH + "-leader-", new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
            } catch (KeeperException.TotalEphemeralLimitExceeded e) {
                return true;
            }
            return false;
        } else {
            int i = 0;
            while (cumulativeBytes + BinaryOutputArchive.getSerializedStringByteSize(subPath + i)
                    <= limit) {
                server.create(subPath + i, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                cumulativeBytes += BinaryOutputArchive.getSerializedStringByteSize(subPath + i);
                i++;
            }
            try {
                server.create(subPath + "-follower-" + i, new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            } catch (KeeperException.TotalEphemeralLimitExceeded e) {
                return true;
            }
            return false;
        }
    }

    @Test
    public void rejectedEphemeralMetricsTest() throws Exception {
        ZooKeeper leaderServer = servers.zk[servers.findLeader()];
        int expectedLimitExceededAttempts = 10;
        int i = expectedLimitExceededAttempts;
        int limit = Integer.getInteger(EPHEMERAL_BYTE_LIMIT_KEY);
        int cumulativeBytes = 0;
        int lastPathBytes = 0;
        while (i > 0 || cumulativeBytes + lastPathBytes <= limit) {
            try {
                String path = leaderServer.create(TEST_PATH + "-leader-", new byte[512], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
                lastPathBytes = BinaryOutputArchive.getSerializedStringByteSize(path);
                cumulativeBytes += lastPathBytes;
            } catch (KeeperException.TotalEphemeralLimitExceeded e) {
                LOG.info("Encountered TotalEphemeralLimitExceeded as expected, continuing...");
                i--;
            }
        }

        long actual = (long) MetricsUtils.currentServerMetrics().get(EPHEMERAL_BYTE_LIMIT_VIOLATION_KEY);
        assertEquals(expectedLimitExceededAttempts, actual);
    }
}
