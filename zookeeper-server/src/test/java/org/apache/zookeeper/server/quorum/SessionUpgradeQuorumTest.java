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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.sasl.SaslException;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionUpgradeQuorumTest extends QuorumPeerTestBase {

    protected static final Logger LOG = LoggerFactory.getLogger(SessionUpgradeQuorumTest.class);
    public static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    public static final int SERVER_COUNT = 3;
    private MainThread[] mt;
    private int[] clientPorts;
    private TestQPMainDropSessionUpgrading[] qpMain;

    @BeforeEach
    public void setUp() throws Exception {
        LOG.info("STARTING quorum {}", getClass().getName());
        // setup the env with RetainDB and local session upgrading
        ClientBase.setupTestEnv();

        mt = new MainThread[SERVER_COUNT];
        clientPorts = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            sb.append("server.").append(i).append("=127.0.0.1:").append(PortAssignment.unique()).append(":").append(PortAssignment.unique()).append("\n");
        }
        sb.append("localSessionsEnabled=true\n");
        sb.append("localSessionsUpgradingEnabled=true\n");
        String cfg = sb.toString();

        // create a 3 server ensemble
        qpMain = new TestQPMainDropSessionUpgrading[SERVER_COUNT];
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
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT), "waiting for server " + i + " being up");
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        LOG.info("STOPPING quorum {}", getClass().getName());
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }
    }

    @Test
    public void testLocalSessionUpgradeSnapshot() throws IOException, InterruptedException {
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
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[followerA], ClientBase.CONNECTION_TIMEOUT, this);

        waitForOne(zk, States.CONNECTED);

        // clone the session id and passwd for later usage
        long sessionId = zk.getSessionId();

        // should fail because of the injection
        try {
            zk.create(node, new byte[2], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            fail("expect to failed to upgrade session due to the "
                 + "TestQPMainDropSessionUpgrading is being used");
        } catch (KeeperException e) {
            LOG.info("KeeperException when create ephemeral node.", e);
        }

        // force to take snapshot
        qpMain[followerA].quorumPeer.follower.zk.takeSnapshot(true);

        // wait snapshot finish
        Thread.sleep(500);

        // shutdown all servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].shutdown();
        }

        ArrayList<States> waitStates = new ArrayList<States>();
        waitStates.add(States.CONNECTING);
        waitStates.add(States.CLOSED);
        waitForOne(zk, waitStates);

        // start the servers again, start follower A last as we want to
        // keep it running as follower
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i].start();
        }

        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT), "waiting for server " + i + " being up");
        }

        // check global session not exist on follower A
        for (int i = 0; i < SERVER_COUNT; i++) {
            ConcurrentHashMap<Long, Integer> sessions = mt[i].main.quorumPeer.getZkDb().getSessionWithTimeOuts();
            assertFalse(sessions.containsKey(sessionId),
                    "server " + i + " should not have global " + "session " + sessionId);
        }

        zk.close();
    }

    @Test
    public void testOnlyUpgradeSessionOnce() throws IOException, InterruptedException, KeeperException {
        // create a client, and create an ephemeral node to trigger the
        // upgrading process
        final String node = "/node-1";
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[0], ClientBase.CONNECTION_TIMEOUT, this);

        waitForOne(zk, States.CONNECTED);
        long sessionId = zk.getSessionId();

        QuorumZooKeeperServer server = (QuorumZooKeeperServer) mt[0].main.quorumPeer.getActiveServer();
        Request create1 = createEphemeralRequest("/data-1", sessionId);
        Request create2 = createEphemeralRequest("/data-2", sessionId);

        assertNotNull(server.checkUpgradeSession(create1), "failed to upgrade on a ephemeral create");
        assertNull(server.checkUpgradeSession(create2), "tried to upgrade again");

        // clean al the setups and close the zk
        zk.close();
    }

    @Test
    public void testCloseSessionWhileUpgradeOnLeader()
            throws IOException, KeeperException, InterruptedException {
        int leaderId = -1;
        for (int i = SERVER_COUNT - 1; i >= 0; i--) {
            if (mt[i].main.quorumPeer.leader != null) {
                leaderId = i;
            }
        }
        if (leaderId > 0) {
            makeSureEphemeralIsGone(leaderId);
        }
    }

    @Test
    public void testCloseSessionWhileUpgradeOnLearner()
            throws IOException, KeeperException, InterruptedException {
        int learnerId = -1;
        for (int i = SERVER_COUNT - 1; i >= 0; i--) {
            if (mt[i].main.quorumPeer.follower != null) {
                learnerId = i;
            }
        }
        if (learnerId > 0) {
            makeSureEphemeralIsGone(learnerId);
        }
    }

    private void makeSureEphemeralIsGone(int sid)
            throws IOException, KeeperException, InterruptedException {
        // Delay submit request to simulate the request queued in
        // RequestThrottler
        qpMain[sid].setSubmitDelayMs(200);

        // Create a client and an ephemeral node
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[sid],
                    ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);

        final String node = "/node-1";
        zk.create(node, new byte[2], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL, new StringCallback() {
                    @Override
                    public void processResult(int rc, String path, Object ctx,
                            String name) {}
                }, null);

        // close the client
        zk.close();

        // make sure the ephemeral is gone
        zk = new ZooKeeper("127.0.0.1:" + clientPorts[sid],
                ClientBase.CONNECTION_TIMEOUT, this);
        waitForOne(zk, States.CONNECTED);
        assertNull(zk.exists(node, false));
        zk.close();
    }

    private static class TestQPMainDropSessionUpgrading extends TestQPMain {

        private volatile boolean shouldDrop = false;
        private volatile int submitDelayMs = 0;

        public void setDropCreateSession(boolean dropCreateSession) {
            shouldDrop = dropCreateSession;
        }

        public void setSubmitDelayMs(int delay) {
            this.submitDelayMs = delay;
        }

        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new QuorumPeer() {

                @Override
                protected Leader makeLeader(FileTxnSnapLog logFactory) throws IOException {
                    return new Leader(this, new LeaderZooKeeperServer(
                              logFactory, this, this.getZkDb()) {

                        @Override
                        public void submitRequestNow(Request si) {
                            if (submitDelayMs > 0) {
                                try {
                                    Thread.sleep(submitDelayMs);
                                } catch (Exception e) {}
                            }
                            super.submitRequestNow(si);
                        }
                    });
                }

                @Override
                protected Follower makeFollower(FileTxnSnapLog logFactory) throws IOException {

                    return new Follower(this, new FollowerZooKeeperServer(logFactory, this, this.getZkDb()) {

                        @Override
                        public void submitRequestNow(Request si) {
                            if (submitDelayMs > 0) {
                                try {
                                    Thread.sleep(submitDelayMs);
                                } catch (Exception e) {}
                            }
                            super.submitRequestNow(si);
                        }

                    }) {

                        @Override
                        protected void request(Request request) throws IOException {
                            if (!shouldDrop) {
                                super.request(request);
                                return;
                            }
                            LOG.info("request is {}, cnxn {}", request.type, request.cnxn);

                            if (request.type == ZooDefs.OpCode.createSession) {
                                LOG.info("drop createSession request {}", request);
                                return;
                            }

                            if (request.type == ZooDefs.OpCode.create && request.cnxn != null) {
                                CreateRequest createRequest = new CreateRequest();
                                request.request.rewind();
                                ByteBufferInputStream.byteBuffer2Record(request.request, createRequest);
                                request.request.rewind();
                                try {
                                    CreateMode createMode = CreateMode.fromFlag(createRequest.getFlags());
                                    if (createMode.isEphemeral()) {
                                        request.cnxn.sendCloseSession();
                                    }
                                } catch (KeeperException e) {
                                }
                                return;
                            }

                            super.request(request);
                        }
                    };
                }
            };
        }

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
        CreateRequest createRequest = new CreateRequest(path, "data".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL.toFlag());
        createRequest.serialize(boa, "request");
        ByteBuffer bb = ByteBuffer.wrap(boas.toByteArray());
        return new Request(null, sessionId, 1, ZooDefs.OpCode.create2, bb, new ArrayList<Id>());
    }

}
