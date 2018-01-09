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

package org.apache.zookeeper.server.quorum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.sasl.SaslException;

import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.apache.zookeeper.server.Request;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zookeeper.test.QuorumBase;
import org.apache.zookeeper.test.DisconnectableZooKeeper;

/**
 * Tests that session upgrade works from local to global sessions.
 * Expected behavior is that if global-only sessions are unset,
 * and no upgrade interval is specified, then sessions will be
 * created locally to the host.  They will be upgraded to global
 * sessions iff an operation is done on that session which requires
 * persistence, i.e. creating an ephemeral node.
 */
public class SessionUpgradeTest extends QuorumPeerTestBase {
    protected static final Logger LOG = LoggerFactory.getLogger(SessionUpgradeTest.class);
    public static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    private final QuorumBase qb = new QuorumBase();

    @Before
    public void setUp() throws Exception {
        LOG.info("STARTING quorum " + getClass().getName());
        qb.localSessionsEnabled = true;
        qb.localSessionsUpgradingEnabled = true;
        qb.setUp();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
    }

    @After
    public void tearDown() throws Exception {
        LOG.info("STOPPING quorum " + getClass().getName());
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

    private void testLocalSessionsWithoutEphemeral(boolean testLeader)
            throws Exception {
        String nodePrefix = "/testLocalSessions-"
            + (testLeader ? "leaderTest-" : "followerTest-");
        int leaderIdx = qb.getLeaderIndex();
        Assert.assertFalse("No leader in quorum?", leaderIdx == -1);
        int followerIdx = (leaderIdx + 1) % 5;
        int otherFollowerIdx = (leaderIdx + 2) % 5;
        int testPeerIdx = testLeader ? leaderIdx : followerIdx;
        String hostPorts[] = qb.hostPort.split(",");
        CountdownWatcher watcher = new CountdownWatcher();
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(
                hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // Try creating some data.
        for (int i = 0; i < 5; i++) {
            zk.create(nodePrefix + i, new byte[0],
                      ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        long localSessionId = zk.getSessionId();
        byte[] localSessionPwd = zk.getSessionPasswd().clone();

        // Try connecting with the same session id on a different
        // server.  This should fail since it is a local sesion.
        try {
            watcher.reset();
            DisconnectableZooKeeper zknew = new DisconnectableZooKeeper(
                    hostPorts[otherFollowerIdx], CONNECTION_TIMEOUT, watcher,
                    localSessionId, localSessionPwd);

            zknew.create(nodePrefix + "5", new byte[0],
                         ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Assert.fail("Connection on the same session ID should fail.");
        } catch (KeeperException.SessionExpiredException e) {
        } catch (KeeperException.ConnectionLossException e) {
        }

        // If we're testing a follower, also check the session id on the
        // leader. This should also fail
        if (!testLeader) {
            try {
                watcher.reset();
                DisconnectableZooKeeper zknew = new DisconnectableZooKeeper(
                        hostPorts[leaderIdx], CONNECTION_TIMEOUT,
                        watcher, localSessionId, localSessionPwd);

                zknew.create(nodePrefix + "5", new byte[0],
                             ZooDefs.Ids.OPEN_ACL_UNSAFE,
                             CreateMode.PERSISTENT);
                Assert.fail("Connection on the same session ID should fail.");
            } catch (KeeperException.SessionExpiredException e) {
            } catch (KeeperException.ConnectionLossException e) {
            }
        }

        // However, we should be able to disconnect and reconnect to the same
        // server with the same session id (as long as we do it quickly
        // before expiration).
        zk.disconnect();

        watcher.reset();
        zk = new DisconnectableZooKeeper(
                hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher,
                localSessionId, localSessionPwd);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        zk.create(nodePrefix + "6", new byte[0],
                  ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // If we explicitly close the session, then the session id should no
        // longer be valid.
        zk.close();
        try {
            watcher.reset();
            zk = new DisconnectableZooKeeper(
                    hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher,
                    localSessionId, localSessionPwd);

            zk.create(nodePrefix + "7", new byte[0],
                      ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Assert.fail("Reconnecting to a closed session ID should fail.");
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

    private void testUpgradeWithEphemeral(boolean testLeader)
            throws Exception {
        String nodePrefix = "/testUpgrade-"
            + (testLeader ? "leaderTest-" : "followerTest-");
        int leaderIdx = qb.getLeaderIndex();
        Assert.assertFalse("No leader in quorum?", leaderIdx == -1);
        int followerIdx = (leaderIdx + 1) % 5;
        int otherFollowerIdx = (leaderIdx + 2) % 5;
        int testPeerIdx = testLeader ? leaderIdx : followerIdx;
        String hostPorts[] = qb.hostPort.split(",");

        CountdownWatcher watcher = new CountdownWatcher();
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(
                hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // Create some ephemeral nodes.  This should force the session to
        // be propagated to the other servers in the ensemble.
        for (int i = 0; i < 5; i++) {
            zk.create(nodePrefix + i, new byte[0],
                      ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        }

        // We should be able to reconnect with the same session id on a
        // different server, since it has been propagated.
        long localSessionId = zk.getSessionId();
        byte[] localSessionPwd = zk.getSessionPasswd().clone();

        zk.disconnect();
        watcher.reset();
        zk = new DisconnectableZooKeeper(
                hostPorts[otherFollowerIdx], CONNECTION_TIMEOUT, watcher,
                localSessionId, localSessionPwd);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // The created ephemeral nodes are still around.
        for (int i = 0; i < 5; i++) {
            Assert.assertNotNull(zk.exists(nodePrefix + i, null));
        }

        // When we explicitly close the session, we should not be able to
        // reconnect with the same session id
        zk.close();

        try {
            watcher.reset();
            zk = new DisconnectableZooKeeper(
                    hostPorts[otherFollowerIdx], CONNECTION_TIMEOUT, watcher,
                    localSessionId, localSessionPwd);
            zk.exists(nodePrefix + "0", null);
            Assert.fail("Reconnecting to a closed session ID should fail.");
        } catch (KeeperException.SessionExpiredException e) {
        }

        watcher.reset();
        // And the ephemeral nodes will be gone since the session died.
        zk = new DisconnectableZooKeeper(
                hostPorts[testPeerIdx], CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        for (int i = 0; i < 5; i++) {
            Assert.assertNull(zk.exists(nodePrefix + i, null));
        }
    }

    @Test
    public void testLocalSessionUpgradeSnapshot() throws IOException, InterruptedException {
        // setup the env with RetainDB and local session upgrading
        ClientBase.setupTestEnv();

        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            sb.append("server.").append(i).append("=127.0.0.1:")
              .append(PortAssignment.unique()).append(":")
              .append(PortAssignment.unique()).append("\n");
        }
        sb.append("localSessionsEnabled=true\n");
        sb.append("localSessionsUpgradingEnabled=true\n");
        String cfg = sb.toString();

        // create a 3 server ensemble
        MainThread mt[] = new MainThread[SERVER_COUNT];
        final TestQPMainDropSessionUpgrading qpMain[] =
                new TestQPMainDropSessionUpgrading[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT; i++) {
            final TestQPMainDropSessionUpgrading qp = new TestQPMainDropSessionUpgrading();
            qpMain[i] = qp;
            mt[i] = new MainThread(i, clientPorts[i], cfg, false) {
                @Override
                public TestQPMain getTestQPMain() {
                    return qp;
                }
            };
            mt[i].start();
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        // select the candidate of follower
        int leader = -1;
        int followerA = -1;
        for (int i = SERVER_COUNT - 1; i >= 0; i--) {
            if (mt[i].main.quorumPeer.leader != null) {
                leader = i;
            } else if (followerA == -1) {
                followerA = i;
            }
        }

        LOG.info("follower A is {}", followerA);
        qpMain[followerA].setDropCreateSession(true);

        // create a client, and create an ephemeral node to trigger the
        // upgrading process
        final String node = "/node-1";
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[followerA],
                    ClientBase.CONNECTION_TIMEOUT, this);

        waitForOne(zk, States.CONNECTED);

        // clone the session id and passwd for later usage
        long sessionId = zk.getSessionId();

        // should fail because of the injection
        try {
            zk.create(node, new byte[2], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.EPHEMERAL);
            Assert.fail("expect to failed to upgrade session due to the " +
                    "TestQPMainDropSessionUpgrading is being used");
        } catch (KeeperException e) {
            LOG.info("KeeperException when create ephemeral node, {}", e);
        }

        // force to take snapshot
        qpMain[followerA].quorumPeer.follower.zk.takeSnapshot(true);

        // wait snapshot finish
        Thread.sleep(500);

        // shutdown all servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }

        ArrayList<States> waitStates =new ArrayList<States>();
        waitStates.add(States.CONNECTING);
        waitStates.add(States.CLOSED);
        waitForOne(zk, waitStates);

        // start the servers again, start follower A last as we want to
        // keep it running as follower
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].start();
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        // check global session not exist on follower A
        for (int i = 0; i < SERVER_COUNT; i++) {
            ConcurrentHashMap<Long, Integer> sessions =
                    mt[i].main.quorumPeer.getZkDb().getSessionWithTimeOuts();
            Assert.assertFalse("server " + i + " should not have global " +
                    "session " + sessionId, sessions.containsKey(sessionId));
        }

        // clean al the setups and close the zk
        zk.close();
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    @Test
    public void testOnlyUpgradeSessionOnce()
            throws IOException, InterruptedException, KeeperException {
        // set up env
        ClientBase.setupTestEnv();
        System.setProperty("zookeeper.retainZKDatabase", "true");

        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            sb.append("server.").append(i).append("=127.0.0.1:")
              .append(PortAssignment.unique()).append(":")
              .append(PortAssignment.unique()).append("\n");
        }
        sb.append("localSessionsEnabled=true\n");
        sb.append("localSessionsUpgradingEnabled=true\n");
        String cfg = sb.toString();

        // create a 3 server ensemble
        MainThread mt[] = new MainThread[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], cfg, false);
            mt[i].start();
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        // create a client, and create an ephemeral node to trigger the
        // upgrading process
        final String node = "/node-1";
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[0],
                    ClientBase.CONNECTION_TIMEOUT, this);

        waitForOne(zk, States.CONNECTED);
        long sessionId = zk.getSessionId();

        QuorumZooKeeperServer server =
                (QuorumZooKeeperServer) mt[0].main.quorumPeer.getActiveServer();
        Request create1 = createEphemeralRequest("/data-1", sessionId);
        Request create2 = createEphemeralRequest("/data-2", sessionId);

        Assert.assertNotNull("failed to upgrade on a ephemeral create",
                server.checkUpgradeSession(create1));
        Assert.assertNull("tried to upgrade again", server.checkUpgradeSession(create2));

        // clean al the setups and close the zk
        zk.close();
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    private static class TestQPMainDropSessionUpgrading extends TestQPMain {

        private volatile boolean shouldDrop = false;

        public void setDropCreateSession(boolean dropCreateSession) {
            shouldDrop = dropCreateSession;
        }

        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new QuorumPeer() {

                @Override
                protected Follower makeFollower(FileTxnSnapLog logFactory)
                        throws IOException {

                    return new Follower(this, new FollowerZooKeeperServer(
                            logFactory, this, this.getZkDb())) {

                        @Override
                        protected void request(Request request)
                                throws IOException {
                            if (!shouldDrop) {
                                super.request(request);
                                return;
                            }
                            LOG.info("request is {}, cnxn {}", request.type, request.cnxn);

                            if (request.type == ZooDefs.OpCode.createSession) {
                                LOG.info("drop createSession request {}", request);
                                return;
                            }

                            if (request.type == ZooDefs.OpCode.create &&
                                    request.cnxn != null) {
                                CreateRequest createRequest = new CreateRequest();
                                request.request.rewind();
                                ByteBufferInputStream.byteBuffer2Record(
                                        request.request, createRequest);
                                request.request.rewind();
                                try {
                                    CreateMode createMode =
                                          CreateMode.fromFlag(createRequest.getFlags());
                                    if (createMode.isEphemeral()) {
                                        request.cnxn.sendCloseSession();
                                    }
                                } catch (KeeperException e) {}
                                return;
                            }

                            super.request(request);
                        }
                    };
                }
            };
        }
    }

    private void waitForOne(ZooKeeper zk, States state) throws InterruptedException {
        ArrayList<States> states = new ArrayList<States>();
        states.add(state);
        waitForOne(zk, states);
    }

    private void waitForOne(ZooKeeper zk, ArrayList<States> states) throws InterruptedException {
        int iterations = ClientBase.CONNECTION_TIMEOUT / 500;
        while (!states.contains(zk.getState())) {
            if (iterations-- == 0) {
                LOG.info("state is {}", zk.getState());
                throw new RuntimeException("Waiting too long");
            }
            Thread.sleep(500);
        }
    }

    private Request createEphemeralRequest(String path, long sessionId) throws IOException {
        ByteArrayOutputStream boas = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(boas);
        CreateRequest createRequest = new CreateRequest(path,
                "data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL.toFlag());
        createRequest.serialize(boa, "request");
        ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
        return new Request(null, sessionId, 1, ZooDefs.OpCode.create2, bb,
                new ArrayList<Id>());
    }

}
