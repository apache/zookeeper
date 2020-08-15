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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests that session upgrade works from local to global sessions.
 * Expected behavior is that if global-only sessions are unset,
 * and no upgrade interval is specified, then sessions will be
 * created locally to the host.  They will be upgraded to global
 * sessions iff an operation is done on that session which requires
 * persistence, i.e. creating an ephemeral node.
 */
public class SessionUpgradeTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(SessionUpgradeTest.class);
    public static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    private final QuorumBase qb = new QuorumBase();

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
    public void testLocalSessionsWithoutEphemeralOnFollower() throws Exception {
        testLocalSessionsWithoutEphemeral(false);
    }

    @Test
    public void testLocalSessionsWithoutEphemeralOnLeader() throws Exception {
        testLocalSessionsWithoutEphemeral(true);
    }

    private void testLocalSessionsWithoutEphemeral(boolean testLeader) throws Exception {
        String nodePrefix = "/testLocalSessions-" + (testLeader ? "leaderTest-" : "followerTest-");
        int leaderIdx = qb.getLeaderIndex();
        assertFalse(leaderIdx == -1, "No leader in quorum?");
        int followerIdx = (leaderIdx + 1) % 5;
        int otherFollowerIdx = (leaderIdx + 2) % 5;
        int testPeerIdx = testLeader ? leaderIdx : followerIdx;
        String[] hostPorts = qb.hostPort.split(",");
        CountdownWatcher watcher = new CountdownWatcher();
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // Try creating some data.
        for (int i = 0; i < 5; i++) {
            zk.create(nodePrefix + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        long localSessionId = zk.getSessionId();
        byte[] localSessionPwd = zk.getSessionPasswd().clone();

        // Try connecting with the same session id on a different
        // server.  This should fail since it is a local sesion.
        try {
            watcher.reset();
            DisconnectableZooKeeper zknew = new DisconnectableZooKeeper(hostPorts[otherFollowerIdx], CONNECTION_TIMEOUT, watcher, localSessionId, localSessionPwd);

            zknew.create(nodePrefix + "5", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("Connection on the same session ID should fail.");
        } catch (KeeperException.SessionExpiredException e) {
        } catch (KeeperException.ConnectionLossException e) {
        }

        // If we're testing a follower, also check the session id on the
        // leader. This should also fail
        if (!testLeader) {
            try {
                watcher.reset();
                DisconnectableZooKeeper zknew = new DisconnectableZooKeeper(hostPorts[leaderIdx], CONNECTION_TIMEOUT, watcher, localSessionId, localSessionPwd);

                zknew.create(nodePrefix + "5", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                fail("Connection on the same session ID should fail.");
            } catch (KeeperException.SessionExpiredException e) {
            } catch (KeeperException.ConnectionLossException e) {
            }
        }

        // However, we should be able to disconnect and reconnect to the same
        // server with the same session id (as long as we do it quickly
        // before expiration).
        zk.disconnect();

        watcher.reset();
        zk = new DisconnectableZooKeeper(hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher, localSessionId, localSessionPwd);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        zk.create(nodePrefix + "6", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // If we explicitly close the session, then the session id should no
        // longer be valid.
        zk.close();
        try {
            watcher.reset();
            zk = new DisconnectableZooKeeper(hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher, localSessionId, localSessionPwd);

            zk.create(nodePrefix + "7", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("Reconnecting to a closed session ID should fail.");
        } catch (KeeperException.SessionExpiredException e) {
        }
    }

    @Test
    public void testUpgradeWithEphemeralOnFollower() throws Exception {
        testUpgradeWithEphemeral(false);
    }

    @Test
    public void testUpgradeWithEphemeralOnLeader() throws Exception {
        testUpgradeWithEphemeral(true);
    }

    private void testUpgradeWithEphemeral(boolean testLeader) throws Exception {
        String nodePrefix = "/testUpgrade-" + (testLeader ? "leaderTest-" : "followerTest-");
        int leaderIdx = qb.getLeaderIndex();
        assertFalse(leaderIdx == -1, "No leader in quorum?");
        int followerIdx = (leaderIdx + 1) % 5;
        int otherFollowerIdx = (leaderIdx + 2) % 5;
        int testPeerIdx = testLeader ? leaderIdx : followerIdx;
        String[] hostPorts = qb.hostPort.split(",");

        CountdownWatcher watcher = new CountdownWatcher();
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // Create some ephemeral nodes.  This should force the session to
        // be propagated to the other servers in the ensemble.
        for (int i = 0; i < 5; i++) {
            zk.create(nodePrefix + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }

        // We should be able to reconnect with the same session id on a
        // different server, since it has been propagated.
        long localSessionId = zk.getSessionId();
        byte[] localSessionPwd = zk.getSessionPasswd().clone();

        zk.disconnect();
        watcher.reset();
        zk = new DisconnectableZooKeeper(hostPorts[otherFollowerIdx], CONNECTION_TIMEOUT, watcher, localSessionId, localSessionPwd);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // The created ephemeral nodes are still around.
        for (int i = 0; i < 5; i++) {
            assertNotNull(zk.exists(nodePrefix + i, null));
        }

        // When we explicitly close the session, we should not be able to
        // reconnect with the same session id
        zk.close();

        try {
            watcher.reset();
            zk = new DisconnectableZooKeeper(hostPorts[otherFollowerIdx], CONNECTION_TIMEOUT, watcher, localSessionId, localSessionPwd);
            zk.exists(nodePrefix + "0", null);
            fail("Reconnecting to a closed session ID should fail.");
        } catch (KeeperException.SessionExpiredException e) {
        }

        watcher.reset();
        // And the ephemeral nodes will be gone since the session died.
        zk = new DisconnectableZooKeeper(hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        for (int i = 0; i < 5; i++) {
            assertNull(zk.exists(nodePrefix + i, null));
        }
    }

}
