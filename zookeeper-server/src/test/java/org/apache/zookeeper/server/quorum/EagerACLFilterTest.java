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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.QuorumBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class EagerACLFilterTest extends QuorumBase {

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

    public static Stream<Arguments> data() {
        return Stream.of(
                Arguments.of(ServerState.LEADING, true),
                Arguments.of(ServerState.LEADING, false),
                Arguments.of(ServerState.FOLLOWING, true),
                Arguments.of(ServerState.FOLLOWING, false),
                Arguments.of(ServerState.OBSERVING, true),
                Arguments.of(ServerState.OBSERVING, false));
    }

    @BeforeEach
    @Override
    public void setUp() {
        //since parameterized test methods need a parameterized setUp method
        //the inherited method has to be overridden with an empty function body
    }

    public void setUp(ServerState serverState, boolean checkEnabled) throws Exception {
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

    @AfterEach
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

    private void assertTransactionState(String condition, long lastxid, ServerState serverState, boolean checkEnabled) {
        String assertion = String.format("Server State: %s Check Enabled: %s %s", serverState, checkEnabled, condition);
        if (checkEnabled) {
            assertEquals(lastxid, zkLeader.getLastLoggedZxid(), assertion);
        } else {
            assertNotSame(lastxid, zkLeader.getLastLoggedZxid(), assertion);
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreateOK(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        ensureCheck(true);
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreate2OK(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreateFail(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");

        assertTransactionState("Transaction state on Leader after failed create", lastxid, serverState, checkEnabled);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCreate2Fail(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.create(CHILD_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");

        assertTransactionState("Transaction state on Leader after failed create2", lastxid, serverState, checkEnabled);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDeleteOK(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, DATA, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zkClientB.delete(PARENT_PATH, -1);

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testDeleteFail(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        zkClient.create(CHILD_PATH, DATA, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.delete(CHILD_PATH, -1);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");

        assertTransactionState("Transaction state on Leader after failed delete", lastxid, serverState, checkEnabled);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetDataOK(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.setData(PARENT_PATH, DATA, -1);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetDataFail(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.setData(PARENT_PATH, DATA, -1);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");

        assertTransactionState("Transaction state on Leader after failed setData", lastxid, serverState, checkEnabled);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetACLOK(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, null);
        zkClientB.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testSetACLFail(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
        zkClient.create(PARENT_PATH, null, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT, null);
        long lastxid = zkLeader.getLastLoggedZxid();
        try {
            zkClientB.setACL(PARENT_PATH, Ids.READ_ACL_UNSAFE, -1);
        } catch (KeeperException.NoAuthException e) {
        }

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");

        assertTransactionState("Transaction state on Leader after failed setACL", lastxid, serverState, checkEnabled);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testBadACL(ServerState serverState, boolean checkEnabled) throws Exception {
        setUp(serverState, checkEnabled);
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

        assertEquals(0, connectedServer.getInProcess(), "OutstandingRequests not decremented");

        assertTransactionState("zxid after invalid ACL", lastxid, serverState, checkEnabled);
    }

}
