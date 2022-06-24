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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
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
    protected boolean complete = false;
    protected static final String PARENT_PATH = "/foo";
    protected static final String CHILD_PATH = "/foo/bar";
    protected static final String AUTH_PROVIDER = "digest";
    protected static final byte[] AUTH = "hello".getBytes();
    protected static final byte[] AUTHB = "goodbye".getBytes();
    protected static final byte[] DATA = "Hint Water".getBytes();
    protected TestableZooKeeper zkClient;
    protected TestableZooKeeper zkClientB;
    protected TestableZooKeeper zkLeaderClient;
    protected QuorumPeer zkLeader;
    protected QuorumPeer zkConnected;
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
        CountdownWatcher leaderWatch = new CountdownWatcher();
        CountdownWatcher clientWatch = new CountdownWatcher();
        CountdownWatcher clientWatchB = new CountdownWatcher();
        super.setUp(true);

        String hostPort = getPeersMatching(serverState).split(",")[0];
        int clientPort = Integer.parseInt(hostPort.split(":")[1]);

        zkLeader = getPeerList().get(getLeaderIndex());
        zkConnected = getPeerByClientPort(clientPort);
        connectedServer = zkConnected.getActiveServer();

        zkLeaderClient = createClient(leaderWatch, getPeersMatching(ServerState.LEADING));
        zkClient = createClient(clientWatch, hostPort);
        zkClientB = createClient(clientWatchB, hostPort);
        zkClient.addAuthInfo(AUTH_PROVIDER, AUTH);
        zkClientB.addAuthInfo(AUTH_PROVIDER, AUTHB);
        leaderWatch.waitForConnected(CONNECTION_TIMEOUT);
        clientWatch.waitForConnected(CONNECTION_TIMEOUT);
        clientWatchB.waitForConnected(CONNECTION_TIMEOUT);
    }

    void syncClient(ZooKeeper zk) {
        CompletableFuture<Void> synced = new CompletableFuture<>();
        zk.sync("/", (rc, path, ctx) -> {
            if (rc == 0) {
                synced.complete(null);
            } else {
                synced.completeExceptionally(KeeperException.create(KeeperException.Code.get(rc)));
            }
        }, null);
        synced.join();
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
        ZooKeeperServer.setEnableEagerACLCheck(enabled);
    }

    private void assertTransactionState(String operation, QuorumPeer peer, long lastxid) {
        if (peer == zkLeader && peer != zkConnected) {
            // The operation is performed on no leader, but we are asserting on leader.
            // There is no happen-before between `zkLeader.getLastLoggedZxid()` and
            // successful response from other server. The commit and response are routed
            // to different servers and performed asynchronous in each server. So we have
            // to sync leader client to go through commit and response path in leader to
            // build happen-before between `zkLeader.getLastLoggedZxid()` and side effect
            // of previous operation.
            syncClient(zkLeaderClient);
        }
        assertTrue(peer == zkLeader || peer == zkConnected);
        boolean eagerACL = ZooKeeperServer.isEnableEagerACLCheck();
        String assertion = String.format(
                "Connecting: %s Checking: %s EagerACL: %s Operation: %s",
                zkConnected.getPeerState(), peer.getPeerState(), eagerACL, operation);
        if (eagerACL) {
            assertEquals(assertion, lastxid, peer.getLastLoggedZxid());
        } else {
            assertNotEquals(assertion, lastxid, peer.getLastLoggedZxid());
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
        long lastxid = zkConnected.getLastLoggedZxid();
        try {
            zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            fail("expect no auth");
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("failed create", zkConnected, lastxid);
        assertTransactionState("failed create", zkLeader, lastxid);
    }

    @Test
    public void testCreate2Fail() throws Exception {
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkConnected.getLastLoggedZxid();
        try {
            zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
            fail("expect no auth");
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("failed create2", zkConnected, lastxid);
        assertTransactionState("failed create2", zkLeader, lastxid);
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
        long lastxid = zkConnected.getLastLoggedZxid();
        try {
            zkClientB.delete(CHILD_PATH, -1);
            fail("expect no auth");
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("failed delete", zkConnected, lastxid);
        assertTransactionState("failed delete", zkLeader, lastxid);
    }

    @Test
    public void testSetDataOK() throws Exception {
        zkClient.create(PARENT_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.setData(PARENT_PATH, DATA, -1);
    }

    @Test
    public void testSetDataFail() throws Exception {
        zkClient.create(PARENT_PATH, null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkConnected.getLastLoggedZxid();
        try {
            zkClientB.setData(PARENT_PATH, DATA, -1);
            fail("expect no auth");
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("failed setData", zkConnected, lastxid);
        assertTransactionState("failed setData", zkLeader, lastxid);
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
        long lastxid = zkConnected.getLastLoggedZxid();
        try {
            zkClientB.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);
            fail("expect no auth");
        } catch (KeeperException.NoAuthException ignored) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("failed setACL", zkConnected, lastxid);
        assertTransactionState("failed setACL", zkLeader, lastxid);
    }

    @Test
    public void testBadACL() throws Exception {
        CountdownWatcher cw = new CountdownWatcher();
        String addr = String.format("%s:%d", LOCALADDR, zkConnected.getClientPort());
        TestableZooKeeper zk = createClient(cw, addr);

        cw.waitForConnected(CONNECTION_TIMEOUT);

        long lastxid = zkConnected.getLastLoggedZxid();

        try {
            zk.create("/acltest", new byte[0], Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
            fail("Should have received an invalid acl error");
        } catch (KeeperException.InvalidACLException e) {
        }

        assertEquals("OutstandingRequests not decremented", 0, connectedServer.getInProcess());

        assertTransactionState("invalid ACL", zkConnected, lastxid);
        assertTransactionState("invalid ACL", zkLeader, lastxid);
    }

}
