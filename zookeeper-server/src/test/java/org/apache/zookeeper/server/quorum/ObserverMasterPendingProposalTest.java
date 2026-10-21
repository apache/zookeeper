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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.security.sasl.SaslException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.txn.CreateTxn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the synchronization window between a Follower and its
 * ObserverMaster. The only faults injected by this test are delayed ACKs and
 * a pause at Leader.startForwarding; all proposals, commits, queues, and zxids
 * are produced by the normal ZooKeeper protocol.
 */
public class ObserverMasterPendingProposalTest extends QuorumPeerTestBase {

    private static final String WARM_PATH = "/observer-cache-is-warm";
    private static final String MISSED_PATH = "/missed-during-follower-sync";
    private static final String AFTER_GAP_PATH = "/commit-after-gap";
    private static final long WAIT_SECONDS = 30;

    private final Harness harness = new Harness();
    private final List<MainThread> startedPeers = new ArrayList<>();
    private ZooKeeper client;

    @AfterEach
    public void shutDownCluster() throws Exception {
        harness.abort();
        if (client != null) {
            client.close();
        }
        for (int i = startedPeers.size() - 1; i >= 0; i--) {
            startedPeers.get(i).shutdown();
        }
    }

    @Test
    @Timeout(value = 90)
    public void testProposalReceivedDuringFollowerSyncIsForwardedByObserverMaster() throws Exception {
        ClientBase.setupTestEnv();

        final int participantCount = 3;
        final int observerId = participantCount + 1;
        final int[] clientPorts = new int[observerId + 1];
        final int[] observerMasterPorts = new int[participantCount + 1];
        StringBuilder quorumConfig = new StringBuilder();

        for (int sid = 1; sid <= observerId; sid++) {
            clientPorts[sid] = PortAssignment.unique();
            String role = sid <= participantCount ? "participant" : "observer";
            quorumConfig.append(String.format(
                "server.%d=127.0.0.1:%d:%d:%s;127.0.0.1:%d%n",
                sid,
                PortAssignment.unique(),
                PortAssignment.unique(),
                role,
                clientPorts[sid]));
            if (sid <= participantCount) {
                observerMasterPorts[sid] = PortAssignment.unique();
            }
        }

        MainThread[] participants = new MainThread[participantCount + 1];
        for (int sid = 1; sid <= participantCount; sid++) {
            participants[sid] = new ControlledMainThread(
                sid,
                clientPorts[sid],
                PortAssignment.unique(),
                quorumConfig.toString(),
                commonConfig(observerMasterPorts[sid]),
                harness);
            start(participants[sid]);
        }

        for (int sid = 1; sid <= participantCount; sid++) {
            assertTrue(
                ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[sid], CONNECTION_TIMEOUT),
                "participant " + sid + " did not start");
        }

        int leaderId = findLeader(participants, participantCount);
        int targetFollowerId = findFollower(participants, participantCount);
        MainThread leaderThread = participants[leaderId];
        MainThread targetFollowerThread = participants[targetFollowerId];

        MainThread observerThread = new MainThread(
            observerId,
            clientPorts[observerId],
            PortAssignment.unique(),
            quorumConfig.toString(),
            commonConfig(observerMasterPorts[targetFollowerId]));
        start(observerThread);
        assertTrue(
            ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[observerId], CONNECTION_TIMEOUT),
            "observer did not start");

        client = ClientBase.createZKClient("127.0.0.1:" + clientPorts[leaderId]);
        client.create(WARM_PATH, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        QuorumPeer observerPeer = observerThread.getQuorumPeer();
        QuorumPeer leaderPeer = leaderThread.getQuorumPeer();
        long warmZxid = leaderPeer.getZkDb().getDataTreeLastProcessedZxid();
        await("observer did not cache the warm-up proposal", () -> {
            ZKDatabase db = observerPeer.getZkDb();
            return db.getNode(WARM_PATH) != null && db.getmaxCommittedLog() == warmZxid;
        });

        ObserverZooKeeperServer firstObserverServer = (ObserverZooKeeperServer) observerPeer.getActiveServer();

        targetFollowerThread.shutdown();
        assertTrue(
            ClientBase.waitForServerDown(
                "127.0.0.1:" + clientPorts[targetFollowerId],
                CONNECTION_TIMEOUT),
            "target follower did not stop");

        harness.arm(targetFollowerId);
        targetFollowerThread.start();
        assertTrue(
            harness.beforeStartForwarding.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "leader never reached startForwarding for the restarting follower");

        CountDownLatch missedCreateCompleted = new CountDownLatch(1);
        AtomicInteger missedCreateResult = new AtomicInteger(Integer.MIN_VALUE);
        client.create(
            MISSED_PATH,
            new byte[0],
            Ids.OPEN_ACL_UNSAFE,
            CreateMode.PERSISTENT,
            (rc, path, context, name) -> {
                missedCreateResult.set(rc);
                missedCreateCompleted.countDown();
            },
            null);

        assertTrue(
            harness.targetProposalSeen.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "leader never proposed the create in the synchronization window");
        long missedZxid = harness.targetZxid;
        assertEquals(warmZxid + 1, missedZxid, "the fixed workload should make the missed create the next transaction");
        harness.continueStartForwarding.countDown();

        await("the restarting follower did not retain the in-flight proposal", () -> {
            QuorumPeer peer = targetFollowerThread.getQuorumPeer();
            Follower follower = peer == null ? null : peer.follower;
            return follower != null
                && follower.om != null
                && follower.fzk.pendingTxns.stream().anyMatch(request -> request.zxid == missedZxid);
        });
        await("a quorum of ACKs was not held", () -> harness.heldAckCount() >= 2);

        await("observer did not reconnect through the restarted ObserverMaster", () -> {
            ObserverZooKeeperServer server = (ObserverZooKeeperServer) observerPeer.getActiveServer();
            Observer observer = observerPeer.observer;
            return server != null
                && server != firstObserverServer
                && server.isRunning()
                && observer != null
                && observer.getSocket() != null
                && observer.getSocket().isConnected()
                && observer.getSocket().getPort() == observerMasterPorts[targetFollowerId]
                && observerPeer.getZkDb().getDataTreeLastProcessedZxid() == warmZxid
                && observerPeer.getZkDb().getmaxCommittedLog() == warmZxid;
        });

        ObserverZooKeeperServer observerServer = (ObserverZooKeeperServer) observerPeer.getActiveServer();
        CommitProcessor observerCommitProcessor = observerServer.commitProcessor;
        assertTrue(observerCommitProcessor.isAlive(), "observer CommitProcessor was not running before the commit");

        harness.releaseAcks();
        assertTrue(
            missedCreateCompleted.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "the in-flight create did not commit after ACK release");
        assertEquals(Code.OK.intValue(), missedCreateResult.get());

        await("target follower did not commit the in-flight proposal", () -> {
            QuorumPeer peer = targetFollowerThread.getQuorumPeer();
            Follower follower = peer == null ? null : peer.follower;
            return follower != null
                && follower.fzk.pendingTxns.stream().noneMatch(request -> request.zxid == missedZxid)
                && peer.getZkDb().getNode(MISSED_PATH) != null;
        });
        client.create(AFTER_GAP_PATH, new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        long afterGapZxid = leaderPeer.getZkDb().getDataTreeLastProcessedZxid();
        assertEquals(missedZxid + 1, afterGapZxid, "the follow-up create should be the next transaction");
        await("observer did not receive the inherited and follow-up proposals", () ->
            observerPeer.getZkDb().getNode(MISSED_PATH) != null
                && observerPeer.getZkDb().getNode(AFTER_GAP_PATH) != null
                && observerPeer.getZkDb().getmaxCommittedLog() == afterGapZxid);

        assertTrue(observerServer.isRunning());
        assertTrue(observerCommitProcessor.isAlive());
    }

    private void start(MainThread peer) {
        startedPeers.add(peer);
        peer.start();
    }

    private static String commonConfig(int observerMasterPort) {
        return String.format(
            "observerMasterPort=%d%nsnapCount=100000%nadmin.enableServer=false%n",
            observerMasterPort);
    }

    private static int findLeader(MainThread[] participants, int participantCount) throws InterruptedException {
        AtomicInteger result = new AtomicInteger(-1);
        await("leader was not elected", () -> {
            for (int sid = 1; sid <= participantCount; sid++) {
                QuorumPeer peer = participants[sid].getQuorumPeer();
                if (peer != null && peer.leader != null) {
                    result.set(sid);
                    return true;
                }
            }
            return false;
        });
        return result.get();
    }

    private static int findFollower(MainThread[] participants, int participantCount) throws InterruptedException {
        AtomicInteger result = new AtomicInteger(-1);
        await("follower was not available", () -> {
            for (int sid = 1; sid <= participantCount; sid++) {
                QuorumPeer peer = participants[sid].getQuorumPeer();
                if (peer != null && peer.follower != null && peer.follower.om != null) {
                    result.set(sid);
                    return true;
                }
            }
            return false;
        });
        return result.get();
    }

    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static final class ControlledMainThread extends MainThread {

        private final Harness harness;

        ControlledMainThread(
                int myid,
                int clientPort,
                int adminPort,
                String quorumConfig,
                String otherConfig,
                Harness harness) throws IOException {
            super(myid, clientPort, adminPort, quorumConfig, otherConfig);
            this.harness = harness;
        }

        @Override
        public TestQPMain getTestQPMain() {
            return new ControlledQPMain(harness);
        }
    }

    private static final class ControlledQPMain extends TestQPMain {

        private final Harness harness;

        ControlledQPMain(Harness harness) {
            this.harness = harness;
        }

        @Override
        protected QuorumPeer getQuorumPeer() throws SaslException {
            return new ControlledQuorumPeer(harness);
        }
    }

    private static final class ControlledQuorumPeer extends QuorumPeer {

        private final Harness harness;

        ControlledQuorumPeer(Harness harness) throws SaslException {
            this.harness = harness;
        }

        @Override
        protected Leader makeLeader(FileTxnSnapLog logFactory) throws IOException, X509Exception {
            return new ControlledLeader(
                this,
                new LeaderZooKeeperServer(logFactory, this, getZkDb()),
                harness);
        }
    }

    private static final class ControlledLeader extends Leader {

        private final Harness harness;

        ControlledLeader(QuorumPeer self, LeaderZooKeeperServer zk, Harness harness) throws IOException, X509Exception {
            super(self, zk);
            this.harness = harness;
        }

        @Override
        public Proposal propose(Request request) throws XidRolloverException {
            if (harness.armed && request.getTxn() instanceof CreateTxn
                    && MISSED_PATH.equals(((CreateTxn) request.getTxn()).getPath())) {
                harness.targetZxid = request.zxid;
                harness.targetProposalSeen.countDown();
            }
            return super.propose(request);
        }

        @Override
        public void processAck(long sid, long zxid, SocketAddress followerAddress) {
            harness.awaitAckRelease(zxid);
            super.processAck(sid, zxid, followerAddress);
        }

        @Override
        public long startForwarding(LearnerHandler handler, long lastSeenZxid) {
            if (harness.pauseStartForwarding(handler.getSid())) {
                harness.beforeStartForwarding.countDown();
                try {
                    if (!harness.continueStartForwarding.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release Leader.startForwarding");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while pausing Leader.startForwarding", e);
                }
            }
            return super.startForwarding(handler, lastSeenZxid);
        }

    }

    private static final class Harness {

        private final AtomicBoolean forwardingPaused = new AtomicBoolean();
        private final AtomicInteger heldAckCount = new AtomicInteger();

        private volatile boolean armed;
        private volatile long targetFollowerId = -1;
        private volatile long targetZxid = -1;
        private volatile CountDownLatch beforeStartForwarding = new CountDownLatch(1);
        private volatile CountDownLatch continueStartForwarding = new CountDownLatch(1);
        private volatile CountDownLatch targetProposalSeen = new CountDownLatch(1);
        private volatile CountDownLatch releaseAcks = new CountDownLatch(0);

        void arm(long followerId) {
            targetFollowerId = followerId;
            targetZxid = -1;
            forwardingPaused.set(false);
            heldAckCount.set(0);
            beforeStartForwarding = new CountDownLatch(1);
            continueStartForwarding = new CountDownLatch(1);
            targetProposalSeen = new CountDownLatch(1);
            releaseAcks = new CountDownLatch(1);
            armed = true;
        }

        boolean pauseStartForwarding(long followerId) {
            return armed
                && followerId == targetFollowerId
                && forwardingPaused.compareAndSet(false, true);
        }

        void awaitAckRelease(long zxid) {
            if (armed && zxid == targetZxid) {
                heldAckCount.incrementAndGet();
                try {
                    if (!releaseAcks.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release proposal ACKs");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while holding proposal ACK", e);
                }
            }
        }

        int heldAckCount() {
            return heldAckCount.get();
        }

        void releaseAcks() {
            releaseAcks.countDown();
        }

        void abort() {
            continueStartForwarding.countDown();
            releaseAcks();
        }
    }
}
