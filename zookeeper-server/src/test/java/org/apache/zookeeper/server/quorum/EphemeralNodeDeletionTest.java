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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import javax.security.sasl.SaslException;

public class EphemeralNodeDeletionTest extends QuorumPeerTestBase {
    private static int SERVER_COUNT = 3;
    private MainThread[] mt = new MainThread[SERVER_COUNT];

    /**
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2355.
     * ZooKeeper ephemeral node is never deleted if follower fail while reading
     * the proposal packet.
     */

    @Test(timeout = 120000)
    public void testEphemeralNodeDeletion() throws Exception {
        final int clientPorts[] = new int[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique()
                    + ":" + PortAssignment.unique();
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        System.out.println(currentQuorumCfgSection);
        // start all the servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection) {
                @Override
                public TestQPMain getTestQPMain() {
                    return new MockTestQPMain();
                }
            };
            mt[i].start();
        }

        // ensure all servers started
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i],
                            CONNECTION_TIMEOUT));
        }

        CountdownWatcher watch = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + clientPorts[1],
                ClientBase.CONNECTION_TIMEOUT, watch);
        watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        /**
         * now the problem scenario starts
         */

        // 1: create ephemeral node
        String nodePath = "/e1";
        zk.create(nodePath, "1".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        // 2: inject network problem in one of the follower
        CustomQuorumPeer follower = (CustomQuorumPeer) getByServerState(mt,
                ServerState.FOLLOWING);
        follower.setInjectError(true);

        // 3: close the session so that ephemeral node is deleted
        zk.close();

        // remove the error
        follower.setInjectError(false);

        Assert.assertTrue("Faulted Follower should have joined quorum by now",
                ClientBase.waitForServerUp(
                        "127.0.0.1:" + follower.getClientPort(),
                        CONNECTION_TIMEOUT));

        QuorumPeer leader = getByServerState(mt, ServerState.LEADING);
        assertNotNull("Leader should not be null", leader);
        Assert.assertTrue("Leader must be running", ClientBase.waitForServerUp(
                "127.0.0.1:" + leader.getClientPort(), CONNECTION_TIMEOUT));

        watch = new CountdownWatcher();
        zk = new ZooKeeper("127.0.0.1:" + leader.getClientPort(),
                ClientBase.CONNECTION_TIMEOUT, watch);
        watch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        Stat exists = zk.exists(nodePath, false);
        assertNull("Node must have been deleted from leader", exists);

        CountdownWatcher followerWatch = new CountdownWatcher();
        ZooKeeper followerZK = new ZooKeeper(
                "127.0.0.1:" + follower.getClientPort(),
                ClientBase.CONNECTION_TIMEOUT, followerWatch);
        followerWatch.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        Stat nodeAtFollower = followerZK.exists(nodePath, false);

        // Problem 1: Follower had one extra ephemeral node /e1
        assertNull("ephemeral node must not exist", nodeAtFollower);

        // Create the node with another session
        zk.create(nodePath, "2".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

        // close the session and newly created ephemeral node should be deleted
        zk.close();

        nodeAtFollower = followerZK.exists(nodePath, false);

        // Problem 2: Before fix, after session close the ephemeral node
        // was not getting deleted. But now after the fix after session close
        // ephemeral node is getting deleted.
        assertNull("After session close ephemeral node must be deleted",
                nodeAtFollower);
        followerZK.close();
    }

    @After
    public void tearDown() {
        // stop all severs
        for (int i = 0; i < mt.length; i++) {
            try {
                mt[i].shutdown();
            } catch (InterruptedException e) {
                LOG.warn("Quorum Peer interrupted while shutting it down", e);
            }
        }
    }

    private QuorumPeer getByServerState(MainThread[] mt, ServerState state) {
        for (int i = mt.length - 1; i >= 0; i--) {
            QuorumPeer quorumPeer = mt[i].getQuorumPeer();
            if (null != quorumPeer && state == quorumPeer.getPeerState()) {
                return quorumPeer;
            }
        }
        return null;
    }

    static class CustomQuorumPeer extends QuorumPeer  {
        private boolean injectError = false;

        public CustomQuorumPeer() throws SaslException {
        }

        @Override
        protected Follower makeFollower(FileTxnSnapLog logFactory)
                throws IOException {
            return new Follower(this, new FollowerZooKeeperServer(logFactory,
                    this, null /*DataTreeBuilder is never used*/,
                    this.getZkDb())) {

                @Override
                void readPacket(QuorumPacket pp) throws IOException {
                    /**
                     * In real scenario got SocketTimeoutException while reading
                     * the packet from leader because of network problem, but
                     * here throwing SocketTimeoutException based on whether
                     * error is injected or not
                     */
                    super.readPacket(pp);
                    if (injectError && pp.getType() == Leader.PROPOSAL) {
                        String type = LearnerHandler.packetToString(pp);
                        throw new SocketTimeoutException(
                                "Socket timeout while reading the packet for operation "
                                        + type);
                    }
                }

            };
        }

        public void setInjectError(boolean injectError) {
            this.injectError = injectError;
        }

    }

    static class MockTestQPMain extends TestQPMain {
        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new CustomQuorumPeer();
        }
    }
}