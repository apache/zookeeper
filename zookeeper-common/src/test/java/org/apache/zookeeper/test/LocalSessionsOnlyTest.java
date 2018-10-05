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

import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests learners configured to use local sessions only. Expected
 * behavior is that sessions created on the learner will never be
 * made global.  Operations requiring a global session (e.g.
 * creation of ephemeral nodes) will fail with an error.
 */
public class LocalSessionsOnlyTest extends ZKTestCase {
    protected static final Logger LOG = LoggerFactory
            .getLogger(LocalSessionsOnlyTest.class);
    public static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    private final QuorumBase qb = new QuorumBase();

    @Before
    public void setUp() throws Exception {
        LOG.info("STARTING quorum " + getClass().getName());
        qb.localSessionsEnabled = true;
        qb.localSessionsUpgradingEnabled = false;
        qb.setUp();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
    }

    @After
    public void tearDown() throws Exception {
        LOG.info("STOPPING quorum " + getClass().getName());
        qb.tearDown();
    }

    @Test
    public void testLocalSessionsOnFollower() throws Exception {
        testLocalSessions(false);
    }

    @Test
    public void testLocalSessionsOnLeader() throws Exception {
        testLocalSessions(true);
    }

    private void testLocalSessions(boolean testLeader) throws Exception {
        String nodePrefix = "/testLocalSessions-"
            + (testLeader ? "leaderTest-" : "followerTest-");
        int leaderIdx = qb.getLeaderIndex();
        Assert.assertFalse("No leader in quorum?", leaderIdx == -1);
        int followerIdx = (leaderIdx + 1) % 5;
        int testPeerIdx = testLeader ? leaderIdx : followerIdx;
        String hostPorts[] = qb.hostPort.split(",");

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = qb.createClient(watcher, hostPorts[testPeerIdx],
                                       CONNECTION_TIMEOUT);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        long localSessionId = zk.getSessionId();

        // Try creating some data.
        for (int i = 0; i < 5; i++) {
            zk.create(nodePrefix + i, new byte[0],
                      ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        // Now, try an ephemeral node.  This should fail since we
        // cannot create ephemeral nodes on a local session.
        try {
            zk.create(nodePrefix + "ephemeral", new byte[0],
                      ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            Assert.fail("Ephemeral node creation should fail.");
        } catch (KeeperException.EphemeralOnLocalSessionException e) {
        }

        // Close the session.
        zk.close();

        // Validate data on both follower and leader
        HashMap<String, Integer> peers = new HashMap<String, Integer>();
        peers.put("leader", leaderIdx);
        peers.put("follower", followerIdx);
        for (Entry<String, Integer> entry: peers.entrySet()) {
            watcher.reset();
            // Try reconnecting with a new session.
            // The data should be persisted, even though the session was not.
            zk = qb.createClient(watcher, hostPorts[entry.getValue()],
                                 CONNECTION_TIMEOUT);
            watcher.waitForConnected(CONNECTION_TIMEOUT);

            long newSessionId = zk.getSessionId();
            Assert.assertFalse(newSessionId == localSessionId);

            for (int i = 0; i < 5; i++) {
                Assert.assertNotNull("Data not exists in " + entry.getKey(),
                        zk.exists(nodePrefix + i, null));
            }

            // We may get the correct exception but the txn may go through
            Assert.assertNull("Data exists in " + entry.getKey(),
                    zk.exists(nodePrefix + "ephemeral", null));

            zk.close();
        }
        qb.shutdownServers();
    }
}
