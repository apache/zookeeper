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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.security.sasl.SaslException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooKeeperServerListener;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class DIFFSyncTest extends QuorumPeerTestBase {
    private static final int SERVER_COUNT = 3;
    private static final String PATH_PREFIX = "/test_";

    private int[] clientPorts;
    private MainThread[] mt;
    private ZooKeeper[] zkClients;

    @BeforeEach
    public void start() throws Exception {
        clientPorts = new int[SERVER_COUNT];
        mt = startQuorum(clientPorts);
        zkClients = new ZooKeeper[SERVER_COUNT];
    }

    @AfterEach
    public void tearDown() throws Exception{
        for (final ZooKeeper zk : zkClients) {
            try {
                if (zk != null) {
                    zk.close();
                }
            } catch (final InterruptedException e) {
                LOG.warn("ZooKeeper interrupted while shutting it down", e);
            }
        }

        for (final MainThread mainThread : mt) {
            try {
                mainThread.shutdown();
            } catch (final InterruptedException e) {
                LOG.warn("Quorum Peer interrupted while shutting it down", e);
            }
        }
    }

    @Test
    @Timeout(value = 120)
    public void testTxnLoss_FailToPersistAndCommitTxns() throws Exception {
        final List<String> paths = new ArrayList<>();
        assertEquals(2, mt[2].getQuorumPeer().getLeaderId());

        // create a ZK client to the leader (currentEpoch=1, lastLoggedZxid=<1, 1>)
        createZKClient(2);

        // create a znode (currentEpoch=1, lastLoggedZxid=<1, 2>)
        paths.add(createNode(zkClients[2], PATH_PREFIX + "0"));

        // shut down S0
        mt[0].shutdown();
        LOG.info("S0 shutdown.");

        // create a znode (currentEpoch=1, lastLoggedZxid=<1, 3>), so S0 is 1 txn behind
        paths.add(createNode(zkClients[2], PATH_PREFIX + "1"));
        logEpochsAndLastLoggedTxnForAllServers();

        // shut down S1
        mt[1].shutdown();
        LOG.info("S1 shutdown.");

        // restart S0 and trigger a new leader election (currentEpoch=2)
        // S0 starts with MockSyncRequestProcessor and MockCommitProcessor to simulate it writes the
        // currentEpoch and sends NEWLEADER ACK but fails to persist and commit txns afterwards
        // in DIFF sync
        mt[0].start(new MockTestQPMain());
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[0], CONNECTION_TIMEOUT),
                "waiting for server 0 being up");
        LOG.info("S0 restarted.");
        logEpochsAndLastLoggedTxnForAllServers();

        // validate S2 is still the leader
        assertEquals(2, mt[2].getQuorumPeer().getLeaderId());

        // shut down the leader (i.e. S2). This causes S0 disconnects from leader, performs partial
        // shutdown, fast forwards its database to the latest persisted tnx (i.e. <1, 3>) and change
        // its state to LOOKING
        mt[2].shutdown();
        LOG.info("S2 shutdown.");

        // start S1 and trigger a leader election (currentEpoch=3)
        mt[1].start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[1], CONNECTION_TIMEOUT),
                "waiting for server 1 being up");
        LOG.info("S1 restarted.");
        logEpochsAndLastLoggedTxnForAllServers();

        // validate S0 is the new leader because of it has higher epoch
        assertEquals(0, mt[0].getQuorumPeer().getLeaderId());

        // connect to the new leader (i.e. S0) (currentEpoch=3, lastLoggedZxid=<3, 1>
        createZKClient(0);

        // create a znode (currentEpoch=3, lastLoggedZxid=<3, 2>)
        paths.add(createNode(zkClients[0], PATH_PREFIX + "3"));

        // start S2 which is the old leader
        mt[2].start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[2], CONNECTION_TIMEOUT),
                "waiting for server " + 2 + " being up");
        LOG.info("S2 restarted.");
        logEpochsAndLastLoggedTxnForAllServers();

        // validate all the znodes exist from all the clients
        validateDataFromAllClients(paths);
    }

    @Test
    @Timeout(value = 120)
    public void testLeaderShutdown_AckProposalBeforeAckNewLeader() throws Exception {
        assertEquals(2, mt[2].getQuorumPeer().getLeaderId());

        // create a ZK client to the leader (currentEpoch=1, lastLoggedZxid=<1, 1>)
        createZKClient(2);

        // create a znode (currentEpoch=1, lastLoggedZxid=<1, 2>)
        createNode(zkClients[2], PATH_PREFIX + "0");

        // shut down S0
        mt[0].shutdown();
        LOG.info("S0 shutdown.");

        // create a znode (currentEpoch=1, lastLoggedZxid=<1, 3>), so S0 is 1 txn behind
        createNode(zkClients[2], PATH_PREFIX + "1");
        logEpochsAndLastLoggedTxnForAllServers();

        // shut down S1
        mt[1].shutdown();
        LOG.info("S1 shutdown.");

        // restart S0 and trigger a new leader election and DIFF sync (currentEpoch=2)
        mt[0].start();
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[0], CONNECTION_TIMEOUT),
                "waiting for server 0 being up");
        LOG.info("S0 restarted.");

        // create a znode (currentEpoch=2, lastLoggedZxid=<2, 1>)
        createNode(zkClients[2], PATH_PREFIX + "2");

        // validate quorum is up without additional round of leader election
        for (int  i = 0; i < SERVER_COUNT; i++) {
            if (i != 1) {
                final QuorumPeer qp = mt[i].getQuorumPeer();
                assertNotNull(qp);
                assertEquals(2, qp.getCurrentEpoch());
                assertEquals(2, qp.getAcceptedEpoch());
                assertEquals("200000001", Long.toHexString(qp.getLastLoggedZxid()));
            }
        }
    }

    private MainThread[] startQuorum(final int[] clientPorts) throws IOException {
        final StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":" + PortAssignment.unique()
                    + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server);
            sb.append("\n");
        }

        final MainThread[] mt = new MainThread[SERVER_COUNT];

        // start all the servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], sb.toString(), false);
            mt[i].start();
        }

        // ensure all servers started
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], CONNECTION_TIMEOUT),
                    "waiting for server " + i + " being up");
        }
        return mt;
    }

    private void createZKClient(final int idx) throws Exception {
        zkClients[idx] = null;
        final ClientBase.CountdownWatcher watch = new ClientBase.CountdownWatcher();
        zkClients[idx] = new ZooKeeper("127.0.0.1:" + clientPorts[idx], ClientBase.CONNECTION_TIMEOUT, watch);
        watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
    }

    private String createNode(final ZooKeeper zk, final String path) throws Exception {
        final String fullPath = zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        assertNotNull(zk.exists(path, false));
        return fullPath;
    }

    private static class MockTestQPMain extends TestQPMain {
        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new TestQuorumPeer();
        }
    }

    private static class TestQuorumPeer extends QuorumPeer {
        public TestQuorumPeer() throws SaslException {
        }

        @Override
        protected Follower makeFollower(FileTxnSnapLog logFactory) throws IOException {
            final FollowerZooKeeperServer followerZookeeperServer = new FollowerZooKeeperServer(logFactory, this, this.getZkDb()) {
                @Override
                protected void setupRequestProcessors() {
                    RequestProcessor finalProcessor = new FinalRequestProcessor(this);
                    commitProcessor = new MockCommitProcessor(finalProcessor, Long.toString(getServerId()), true, getZooKeeperServerListener());
                    commitProcessor.start();

                    firstProcessor = new FollowerRequestProcessor(this, commitProcessor);
                    ((FollowerRequestProcessor) firstProcessor).start();
                    syncProcessor = new MockSyncRequestProcessor(this, new SendAckRequestProcessor(getFollower()));

                    syncProcessor.start();
                }
            };
            return new Follower(this, followerZookeeperServer);
        }
    }

    private static class MockSyncRequestProcessor extends SyncRequestProcessor {
        public MockSyncRequestProcessor(final ZooKeeperServer zks, final RequestProcessor nextProcessor) {
            super(zks, nextProcessor);
        }

        @Override
        public void processRequest(final Request request) {
            LOG.info("Sync request for zxid {} is dropped", Long.toHexString(request.getHdr().getZxid()));
        }
    }

    private static class MockCommitProcessor extends CommitProcessor {
        public MockCommitProcessor(final RequestProcessor nextProcessor, final String id,
                                   final boolean matchSyncs, final ZooKeeperServerListener listener) {

            super(nextProcessor, id, matchSyncs, listener);
        }

        @Override
        public void commit(final Request request) {
            LOG.info("Commit request for zxid {} is dropped", Long.toHexString(request.getHdr().getZxid()));
        }
    }

    private void logEpochsAndLastLoggedTxnForAllServers() throws Exception {
        for (int  i = 0; i < SERVER_COUNT; i++) {
            final QuorumPeer qp = mt[i].getQuorumPeer();
            if (qp != null) {
                LOG.info(String.format("server id=%d, acceptedEpoch=%d, currentEpoch=%d, lastLoggedTxn=%s",
                        qp.getMyId(), qp.getAcceptedEpoch(),
                        qp.getCurrentEpoch(), Long.toHexString(qp.getLastLoggedZxid())));
            }
        }
    }

    private void validateDataFromAllClients(final List<String> paths) throws Exception{
        for (int i = 0; i < SERVER_COUNT; i++) {
            if (zkClients[i] == null) {
                createZKClient(i);
            }

            for (final String path : paths) {
                assertNotNull(zkClients[i].exists(path, false), "znode " + path + " is missing");
            }
            assertEquals(3, paths.size());
        }
    }
}
