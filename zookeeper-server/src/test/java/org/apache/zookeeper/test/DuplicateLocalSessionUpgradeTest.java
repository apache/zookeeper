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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When request are route incorrectly, both follower and the leader will perform
 * local session upgrade. So we saw CreateSession twice in txnlog This doesn't
 * affect the correctness but cause the ensemble to see more load than
 * necessary.
 */
public class DuplicateLocalSessionUpgradeTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(DuplicateLocalSessionUpgradeTest.class);

    private final QuorumBase qb = new QuorumBase();

    private static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    @BeforeEach
    public void setUp() throws Exception {
        LOG.info("STARTING quorum {}", getClass().getName());
        qb.localSessionsEnabled = true;
        qb.localSessionsUpgradingEnabled = true;
        qb.setUp();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
    }

    @AfterEach
    public void tearDown() throws Exception {
        LOG.info("STOPPING quorum {}", getClass().getName());
        qb.tearDown();
    }

    @Test
    public void testLocalSessionUpgradeOnFollower() throws Exception {
        testLocalSessionUpgrade(false);
    }

    @Test
    public void testLocalSessionUpgradeOnLeader() throws Exception {
        testLocalSessionUpgrade(true);
    }

    private void testLocalSessionUpgrade(boolean testLeader) throws Exception {

        int leaderIdx = qb.getLeaderIndex();
        assertFalse(leaderIdx == -1, "No leader in quorum?");
        int followerIdx = (leaderIdx + 1) % 5;
        int testPeerIdx = testLeader ? leaderIdx : followerIdx;
        String[] hostPorts = qb.hostPort.split(",");

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = qb.createClient(watcher, hostPorts[testPeerIdx], CONNECTION_TIMEOUT);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        final String firstPath = "/first";
        final String secondPath = "/ephemeral";

        // Just create some node so that we know the current zxid
        zk.create(firstPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // Now, try an ephemeral node. This will trigger session upgrade
        // so there will be createSession request inject into the pipeline
        // prior to this request
        zk.create(secondPath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        Stat firstStat = zk.exists(firstPath, null);
        assertNotNull(firstStat);

        Stat secondStat = zk.exists(secondPath, null);
        assertNotNull(secondStat);

        long zxidDiff = secondStat.getCzxid() - firstStat.getCzxid();

        // If there is only one createSession request in between, zxid diff
        // will be exactly 2. The alternative way of checking is to actually
        // read txnlog but this should be sufficient
        assertEquals(2L, zxidDiff);

    }

}
