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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.QuorumBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class EagerACLFilterTest extends QuorumBase {

    protected boolean checkEnabled;
    protected ServerState serverState;
    protected final CountDownLatch callComplete = new CountDownLatch(1);
    protected boolean complete = false;
    protected static final String PARENT_PATH = "/foo";
    protected static final String CHILD_PATH = "/foo/bar";
    protected static final String AUTH_PROVIDER = "digest";
    protected static final byte[] AUTH = "hello".getBytes();
    protected static final byte[] AUTHB = "goodbye".getBytes();
    protected static final byte[] DATA = "Hint Water".getBytes();
    protected TestableZooKeeper zkClient;
    protected TestableZooKeeper zkClientB;
    protected QuorumPeer zkLeader;
    protected ZooKeeperServer connectedServer;

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{ServerState.LEADING, true}, {ServerState.LEADING, false}, {ServerState.FOLLOWING, true}, {ServerState.FOLLOWING, false}, {ServerState.OBSERVING, true}, {ServerState.OBSERVING, false}});
    }

    public EagerACLFilterTest(ServerState state, boolean checkEnabled) {
        this.serverState = state;
        this.checkEnabled = checkEnabled;
    }

    @Before
    public void setUp() throws Exception {
        ensureCheck(checkEnabled);
        CountdownWatcher clientWatch = new CountdownWatcher();
        CountdownWatcher clientWatchB = new CountdownWatcher();
        super.setUp(true);

        String hostPort = getPeersMatching(serverState).split(",")[0];
        int clientPort = Integer.parseInt(hostPort.split(":")[1]);

        zkLeader = getPeerList().get(getLeaderIndex());
        connectedServer = getPeerByClientPort(clientPort).getActiveServer();

        zkClient = createClient(clientWatch, hostPort);
        zkClientB = createClient(clientWatchB, hostPort);
        zkClient.addAuthInfo(AUTH_PROVIDER, AUTH);
        zkClientB.addAuthInfo(AUTH_PROVIDER, AUTHB);
        clientWatch.waitForConnected(CONNECTION_TIMEOUT);
        clientWatchB.waitForConnected(CONNECTION_TIMEOUT);
    }

    @After
    public void tearDown() throws Exception {
        if (zkClient != null) {
            zkClient.close();
        }

        if (zkClientB != null) {
            zkClientB.close();
        }

        super.tearDown();
    }

    private void ensureCheck(boolean enabled) {
        if (enabled) {
            System.setProperty(ZooKeeperServer.ENABLE_EAGER_ACL_CHECK, "true");
        } else {
            System.clearProperty(ZooKeeperServer.ENABLE_EAGER_ACL_CHECK);
        }
    }

    private void assertTransactionState(String condition, long lastxid) {
        String assertion = String.format("Server State: %s Check Enabled: %s %s", serverState, checkEnabled, condition);
        if (checkEnabled) {
            assertEquals(assertion, lastxid, zkLeader.getLastLoggedZxid());
        } else {
            assertNotSame(assertion, lastxid, zkLeader.getLastLoggedZxid());
        }
    }

    @Test
    public void testCreateOK() throws Exception {
        ensureCheck(true);
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());
    }

    @Test
    public void testCreate2OK() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());
    }

    @Test
    public void testCreateFail() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("Transaction state on Leader after failed create", lastxid);
    }

    @Test
    public void testCreate2Fail() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("Transaction state on Leader after failed create2", lastxid);
    }

    @Test
    public void testDeleteOK() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zkClientB.delete(PARENT_PATH, -1);

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());
    }

    @Test
    public void testDeleteFail() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        zkClient.create(CHILD_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.delete(CHILD_PATH, -1);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("Transaction state on Leader after failed delete", lastxid);
    }

    @Test
    public void testSetDataOK() throws Exception {
        zkClient.create(PARENT_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.setData(PARENT_PATH, DATA, -1);
    }

    @Test
    public void testSetDataFail() throws Exception {
        zkClient.create(PARENT_PATH, null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.setData(PARENT_PATH, DATA, -1);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("Transaction state on Leader after failed setData", lastxid);
    }

    @Test
    public void testSetACLOK() throws Exception {
        zkClient.create(PARENT_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());
    }

    @Test
    public void testSetACLFail() throws Exception {
        zkClient.create(PARENT_PATH, null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("Transaction state on Leader after failed setACL", lastxid);
    }

    @Test
    public void testBadACL() throws Exception {
        CountdownWatcher cw = new CountdownWatcher();
        TestableZooKeeper zk = createClient(cw, getPeersMatching(serverState));
        long lastxid;

        cw.waitForConnected(CONNECTION_TIMEOUT);

        lastxid = zkLeader.getLastLoggedZxid();

        try {
            zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            fail("Should have received an invalid acl error");
        } catch (KeeperException.InvalidACLException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("zxid after invalid ACL", lastxid);
    }

}
