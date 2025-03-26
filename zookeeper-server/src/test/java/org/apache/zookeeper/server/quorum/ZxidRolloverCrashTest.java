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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZxidRolloverCrashTest extends ZKTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(ZxidRolloverCrashTest.class);
    private static final long MAX_ZXID_COUNTER = ZxidUtils.MAX_COUNTER;
    private static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    QuorumUtil qu;

    @BeforeEach
    public void setUp() {
        // write and sync
        System.setProperty("zookeeper.maxBatchSize", "1");
    }

    @AfterEach
    public void tearDown() {
        if (qu != null) {
            qu.shutdownAll();
        }
    }

    private long setZxidCounter(long zxid, long counter) {
        return ZxidUtils.makeZxid(ZxidUtils.getEpochFromZxid(zxid), counter);
    }

    private long maximizeZxid(long zxid) {
        return setZxidCounter(zxid, MAX_ZXID_COUNTER);
    }

    static class LeaderContext {
        private final AtomicReference<Leader> leader = new AtomicReference<>();
        private final AtomicLong zxid = new AtomicLong(Long.MAX_VALUE);
        private final AtomicInteger acks = new AtomicInteger();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        Leader setLeader(Leader leader) {
            this.leader.compareAndSet(null, leader);
            return leader;
        }

        boolean isLeader(Leader leader) {
            return this.leader.get() == leader;
        }

        int incAcks() {
            return acks.incrementAndGet();
        }
    }

    @Test
    public void testLeaderCrashAfterRolloverMajorityReplicated() throws Exception {
        // Intercepts leader to replicate rollover proposal only to n+1 of 2n+1 nodes(including leader).
        LeaderContext context = new LeaderContext();
        final int N = 1;
        qu = new QuorumUtil(N) {
            @Override
            protected Leader makeLeader(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException, X509Exception {
                return context.setLeader(new Leader(self, new LeaderZooKeeperServer(logFactory, self, self.getZkDb())) {
                    @Override
                    public synchronized void processAck(long sid, long zxid, SocketAddress followerAddr) {
                        if (context.isLeader(this) && zxid == context.zxid.get()) {
                            LOG.info("Ack to 0x{} from peer {}", ZxidUtils.zxidToString(zxid), sid);
                            if (context.incAcks() >= N + 1) {
                                self.setSuspended(true);
                                self.shuttingDownLE = true;
                                self.getElectionAlg().shutdown();
                                context.completed.complete(null);
                            }
                            return;
                        }
                        super.processAck(sid, zxid, followerAddr);
                    }

                    @Override
                    protected void sendPacket(QuorumPacket qp) {
                        if (context.isLeader(this) && qp.getType() == Leader.PROPOSAL && qp.getZxid() >= context.zxid.get()) {
                            getForwardingFollowers().stream().limit(N).forEach(follower -> {
                                follower.queuePacket(qp);
                            });
                        } else {
                            super.sendPacket(qp);
                        }
                    }
                });
            }
        };
        qu.disableJMXTest = true;
        qu.startAll();

        int leaderId = qu.getLeaderServer();
        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;

        // Connect only to leader to avoid re-connect attempts.
        try (ZooKeeper zk = ClientBase.createZKClient(qu.getConnectString(qu.getLeaderQuorumPeer()))) {
            // given: leader about to rollover
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            context.zxid.set(maximizeZxid(zkLeader.getZxid()));
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                    "/foo" + Long.toHexString(exclusiveStartCounter + i),
                    new byte[0],
                    ZooDefs.Ids.READ_ACL_UNSAFE,
                    CreateMode.PERSISTENT,
                    (rc, path, ctx, name) -> {},
                    null);
            }

            // when: leader crash after rollover proposal replicated to minority,
            // but before claiming it "committed"
            context.completed.join();
            qu.shutdown(leaderId);
        }

        qu.restart(leaderId);

        // then: after all servers up, every node should meet following conditions
        for (int serverId = 1; serverId <= 2 * N + 1; serverId++) {
            String connectString = qu.getConnectionStringForServer(serverId);
            try (ZooKeeper zk = ClientBase.createZKClient(connectString)) {
                // then: all proposals up to and including the rollover proposal must be replicated
                for (long i = exclusiveStartCounter + 1; i <= MAX_ZXID_COUNTER; i++) {
                    String path = "/foo" + Long.toHexString(i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                    assertEquals(ZxidUtils.makeZxid(epoch, i), stat.getCzxid());
                }

                // then: new epoch proposals after rollover don't persist as leader declares no leadership yet
                assertNull(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER + 1), false));

                // then: new epoch must greater than `epoch + 1` as at least n+1 nodes fenced it
                Stat stat = new Stat();
                zk.create("/server" + serverId, null, ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
                long newEpoch = ZxidUtils.getEpochFromZxid(stat.getCzxid());
                assertEquals(epoch + 2, newEpoch);
            }
        }
    }

    @Test
    public void testLeaderCrashAfterRolloverMinorityReplicated() throws Exception {
        // Intercepts leader to replicate rollover proposal only to n of 2n+1 nodes(including leader).
        LeaderContext context = new LeaderContext();
        final int N = 2;
        qu = new QuorumUtil(N) {
            @Override
            protected Leader makeLeader(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException, X509Exception {
                return context.setLeader(new Leader(self, new LeaderZooKeeperServer(logFactory, self, self.getZkDb())) {
                    @Override
                    public synchronized void processAck(long sid, long zxid, SocketAddress followerAddr) {
                        if (context.isLeader(this) && zxid == context.zxid.get()) {
                            LOG.info("Ack to 0x{} from peer {}", ZxidUtils.zxidToString(zxid), sid);
                            if (context.incAcks() >= N) {
                                self.setSuspended(true);
                                self.shuttingDownLE = true;
                                self.getElectionAlg().shutdown();
                                context.completed.complete(null);
                            }
                            return;
                        }
                        super.processAck(sid, zxid, followerAddr);
                    }

                    @Override
                    protected void sendPacket(QuorumPacket qp) {
                        if (context.isLeader(this) && qp.getType() == Leader.PROPOSAL && qp.getZxid() >= context.zxid.get()) {
                            getForwardingFollowers().stream().limit(N - 1).forEach(follower -> {
                                follower.queuePacket(qp);
                            });
                        } else {
                            super.sendPacket(qp);
                        }
                    }
                });
            }
        };
        qu.disableJMXTest = true;
        qu.startAll();

        int leaderId = qu.getLeaderServer();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        final long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;
        // Connect only to leader to avoid re-connect attempts.
        try (ZooKeeper zk = ClientBase.createZKClient(qu.getConnectString(qu.getLeaderQuorumPeer()))) {
            // given: leader with about to rollover zxid
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            context.zxid.set(maximizeZxid(zkLeader.getZxid()));
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }

            // when: leader crash after rollover proposal replicated to minority
            context.completed.join();
            qu.shutdown(leaderId);
        }

        qu.restart(leaderId);

        // then: after all servers up, every node should meet following conditions
        long rolloverProposalZxid = 0;
        for (int serverId = 1; serverId <= 2 * N + 1; serverId++) {
            try (ZooKeeper zk = ClientBase.createZKClient(qu.getConnString())) {
                // then: all proposals up to but not including the rollover proposal must be replicated
                for (long i = exclusiveStartCounter + 1; i < MAX_ZXID_COUNTER; i++) {
                    String path = "/foo" + Long.toHexString(i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                    assertEquals(ZxidUtils.makeZxid(epoch, i), stat.getCzxid());
                }

                // It is indeterminate which part will win. The minority could win as they have higher `currentEpoch`.
                //
                // We can't make aggressive assertion like `equalTo(epoch + 1)` even when majority wins. As the new
                // leader epoch is negotiated to greater than all `acceptedEpoch` in the quorum. So, it is possible
                // for the leader epoch to be `greaterThan(epoch + 1)`.
                //
                // The situation is similar to leader crashed after minority has persisted `currentEpoch` in a
                // normal "DISCOVERY" phase.
                //
                // See "Phase 1: Establish an epoch" of https://cwiki.apache.org/confluence/display/ZOOKEEPER/Zab1.0
                Stat stat = new Stat();
                zk.create("/server" + serverId, null, ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
                long newEpoch = ZxidUtils.getEpochFromZxid(stat.getCzxid());
                assertThat(newEpoch, greaterThanOrEqualTo(epoch + 1));

                // then: it is indeterminate whether the rollover proposal has been committed,
                // but it must be consistent among all nodes
                if (rolloverProposalZxid == 0) {
                    rolloverProposalZxid =
                            Optional.ofNullable(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER), false))
                                    .map(Stat::getCzxid).orElse(-1L);
                    LOG.info("Get rollover proposal zxid {}", ZxidUtils.zxidToString(rolloverProposalZxid));
                } else {
                    long zxid =
                            Optional.ofNullable(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER), false))
                                    .map(Stat::getCzxid).orElse(-1L);
                    assertEquals(ZxidUtils.zxidToString(rolloverProposalZxid), ZxidUtils.zxidToString(zxid));
                }
            }
        }
    }

    @Test
    public void testLeaderCrashBeforeRolloverReplication() throws Exception {
        // Intercepts leader not to replicate rollover proposal.
        LeaderContext context = new LeaderContext();
        final int N = 1;
        qu = new QuorumUtil(N) {
            @Override
            protected Leader makeLeader(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException, X509Exception {
                return context.setLeader(new Leader(self, new LeaderZooKeeperServer(logFactory, self, self.getZkDb())) {
                    @Override
                    public synchronized void processAck(long sid, long zxid, SocketAddress followerAddr) {
                        super.processAck(sid, zxid, followerAddr);
                        if (!context.isLeader(this)) {
                            return;
                        }
                        if (zxid == context.zxid.get()) {
                            context.acks.incrementAndGet();
                        }
                        if (context.acks.get() != 0 && outstandingProposals.get(context.zxid.get() - 1) == null) {
                            self.setSuspended(true);
                            self.shuttingDownLE = true;
                            self.getElectionAlg().shutdown();
                            context.completed.complete(null);
                        }
                    }

                    @Override
                    protected void sendPacket(QuorumPacket qp) {
                        if (context.isLeader(this) && qp.getType() == Leader.PROPOSAL && qp.getZxid() >= context.zxid.get()) {
                            return;
                        }
                        super.sendPacket(qp);
                    }
                });
            }
        };
        qu.disableJMXTest = true;
        qu.startAll();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());

        int leaderId = qu.getLeaderServer();
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;
        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            // given: leader about to rollover
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            context.zxid.set(setZxidCounter(zkLeader.getZxid(), MAX_ZXID_COUNTER));
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name, stat) -> {
                        },
                        null);
            }

            // when: leader crash before broadcasting rollover proposal
            context.completed.join();
            qu.shutdown(leaderId);
        }

        String connString = qu.getConnString()
            .replace(leaderConnectString + ",", "")
            .replace(leaderConnectString,  "");
        boolean restarted = false;
        for (int j = 0; true; j++) {
            try (ZooKeeper zk = ClientBase.createZKClient(connString, ClientBase.CONNECTION_TIMEOUT)) {
                // then: all proposals up to but not including the rollover proposal must be replicated
                for (long i = exclusiveStartCounter + 1; i < ZxidUtils.MAX_COUNTER; i++) {
                    String path = "/foo" + Long.toHexString(i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                    assertEquals(ZxidUtils.makeZxid(epoch, i), stat.getCzxid());
                }

                // then: the rollover proposal is lost even after old leader with higher `currentEpoch` re-join.
                assertNull(zk.exists("/foo" + Long.toHexString(ZxidUtils.MAX_COUNTER), false));

                // then: new epoch will be `epoch + 1`.
                Stat stat = new Stat();
                zk.create("/bar" + j, null, ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
                assertEquals(epoch + 1, ZxidUtils.getEpochFromZxid(stat.getCzxid()));
            }

            if (restarted) {
                break;
            }

            // when: rejoin old leader which fences `epoch + 1`
            // then: all above holds as majority has formed
            qu.start(leaderId);
            restarted = true;
            connString = qu.getConnectionStringForServer(leaderId);
        }
    }

    @Test
    public void testMinorityFollowersCrashBeforeWriteRolloverToDisk() throws Exception {
        final int N = 1;
        final int minorityN = N;
        class Context {
            final BlockingQueue<Follower> followers = new ArrayBlockingQueue<>(minorityN);
            final AtomicLong rolloverZxid = new AtomicLong(Long.MAX_VALUE);
            final Set<Long> crashed_servers = Collections.synchronizedSet(new HashSet<>());
            final BlockingQueue<Long> crashing_servers = new LinkedBlockingQueue<>();

            boolean bypass(Follower follower, long zxid) {
                boolean minority = followers.offer(follower) || followers.contains(follower);
                if (minority && zxid >= rolloverZxid.get()) {
                    if (crashed_servers.add(follower.self.getMyId())) {
                        crashing_servers.add(follower.self.getMyId());
                    }
                    return true;
                }
                return false;
            }
        }
        Context context = new Context();
        qu = new QuorumUtil(N) {
            @Override
            protected Follower makeFollower(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException {
                return new Follower(self, new FollowerZooKeeperServer(logFactory, self, self.getZkDb()) {
                    @Override
                    public void logRequest(TxnHeader hdr, Record txn, TxnDigest digest) {
                        if (!context.bypass(getFollower(), hdr.getZxid())) {
                            super.logRequest(hdr, txn, digest);
                        }
                    }
                });
            }
        };
        qu.startAll();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;

        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            context.rolloverZxid.set(ZxidUtils.makeZxid(epoch, MAX_ZXID_COUNTER));

            // given: leader about to rollover
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }

            // when: minority followers crashed before persisting rollover proposal
            for (int i = 0; i < minorityN; i++) {
                long serverId = context.crashing_servers.take();
                qu.shutdown((int) serverId);
            }
        }

        for (long serverId : context.crashed_servers) {
            qu.restart((int) serverId);
        }

        // then: after all servers up, every node should meet following conditions
        for (int serverId = 1; serverId <= 2 * N + 1; serverId++) {
            String connectString = qu.getConnectionStringForServer(serverId);
            try (ZooKeeper zk = ClientBase.createZKClient(connectString)) {
                // then: all proposals must be replicated
                for (int i = 1; i <= 10; i++) {
                    String path = "/foo" + Long.toHexString(exclusiveStartCounter + i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                }

                // then: we have rollover to nextEpoch
                long nextEpoch = epoch + 1;
                assertEquals(nextEpoch, ZxidUtils.getEpochFromZxid(qu.getPeer(serverId).peer.getZkDb().getDataTreeLastProcessedZxid()));
                Stat stat = new Stat();
                zk.create("/server" + serverId, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
                assertEquals(nextEpoch, ZxidUtils.getEpochFromZxid(stat.getMzxid()));
            }
        }
    }

    @Test
    public void testMajorityFollowersCrashBeforeWriteRolloverToDisk() throws Exception {
        final int N = 1;
        final int majorityN = N + 1;
        class Context {
            final BlockingQueue<Follower> followers = new ArrayBlockingQueue<>(majorityN);
            final AtomicLong rolloverZxid = new AtomicLong(Long.MAX_VALUE);
            final Set<Long> crashed_servers = Collections.synchronizedSet(new HashSet<>());
            final BlockingQueue<Long> crashing_servers = new LinkedBlockingQueue<>();

            boolean bypass(Follower follower, long zxid) {
                boolean majority = followers.offer(follower) || followers.contains(follower);
                if (majority && zxid >= rolloverZxid.get()) {
                    if (crashed_servers.add(follower.self.getMyId())) {
                        crashing_servers.add(follower.self.getMyId());
                    }
                    return true;
                }
                return false;
            }
        }
        Context context = new Context();
        qu = new QuorumUtil(N) {
            @Override
            protected Follower makeFollower(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException {
                return new Follower(self, new FollowerZooKeeperServer(logFactory, self, self.getZkDb()) {
                    @Override
                    public void logRequest(TxnHeader hdr, Record txn, TxnDigest digest) {
                        if (!context.bypass(getFollower(), hdr.getZxid())) {
                            super.logRequest(hdr, txn, digest);
                        }
                    }
                });
            }
        };
        qu.startAll();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;

        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString, watcher)) {
            context.rolloverZxid.set(ZxidUtils.makeZxid(epoch, MAX_ZXID_COUNTER));

            // given: leader about to rollover
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }

            // when: majority followers crashed before persisting rollover proposal
            for (int i = 0; i < majorityN; i++) {
                long serverId = context.crashing_servers.take();
                qu.shutdown((int) serverId);
            }

            watcher.waitForDisconnected(CONNECTION_TIMEOUT);
            watcher.reset();

            for (long serverId : context.crashed_servers) {
                qu.restart((int) serverId);
            }

            watcher.waitForConnected(CONNECTION_TIMEOUT);
        }

        // then: after quorum reformed, every node should meet following conditions
        long rolloverProposalZxid = 0;
        for (int serverId = 1; serverId <= 2 * N + 1; serverId++) {
            String connectString = qu.getConnectionStringForServer(serverId);
            try (ZooKeeper zk = ClientBase.createZKClient(connectString)) {
                // then: all proposals up to but not including the rollover proposal must be replicated
                for (long i = exclusiveStartCounter + 1; i < ZxidUtils.MAX_COUNTER; i++) {
                    String path = "/foo" + Long.toHexString(i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                }

                // then: it is indeterminate whether the rollover proposal has been committed,
                // but it must be consistent among all nodes
                if (rolloverProposalZxid == 0) {
                    rolloverProposalZxid =
                        Optional.ofNullable(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER), false))
                        .map(Stat::getCzxid).orElse(-1L);
                    LOG.info("Get rollover proposal zxid {}", ZxidUtils.zxidToString(rolloverProposalZxid));
                } else {
                    long zxid =
                        Optional.ofNullable(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER), false))
                        .map(Stat::getCzxid).orElse(-1L);
                    assertEquals(ZxidUtils.zxidToString(rolloverProposalZxid), ZxidUtils.zxidToString(zxid));
                }

                // then: new epoch proposal must not be committed
                assertNull(zk.exists("/foo" + Long.toHexString(ZxidUtils.MAX_COUNTER + 1), false));
            }
        }
    }

    @Test
    public void testMinorityFollowersCrashAfterWriteRolloverToDisk() throws Exception {
        final int N = 1;
        class Context {
            final Set<Long> crashed_servers = Collections.synchronizedSet(new HashSet<>());
            final BlockingQueue<Long> crashing_servers = new LinkedBlockingQueue<>();
        }
        Context context = new Context();
        qu = new QuorumUtil(N) {
            @Override
            protected Follower makeFollower(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException {
                return new Follower(self, new FollowerZooKeeperServer(logFactory, self, self.getZkDb()) {
                    @Override
                    public void fenceRolloverEpoch(long newEpoch) throws IOException {
                        super.fenceRolloverEpoch(newEpoch);
                        long myId = self.getMyId();
                        if (context.crashed_servers.size() < N && !context.crashed_servers.contains(myId)) {
                            context.crashed_servers.add(myId);
                            context.crashing_servers.add(myId);
                            throw new IOException("crash peer " + myId + "after persist max epoch zxid");
                        }
                    }
                });
            }
        };
        qu.startAll();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;
        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            // given: leader about to rollover
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }

            // when: minority followers crashed after replication
            for (int i = 0; i < N; i++) {
                long serverId = context.crashing_servers.take();
                qu.shutdown((int) serverId);
            }
        }

        for (long serverId : context.crashed_servers) {
            qu.restart((int) serverId);
        }

        // then: after all servers up, every node should meet following conditions
        for (int serverId = 1; serverId <= 2 * N + 1; serverId++) {
            String connectString = qu.getConnectionStringForServer(serverId);
            try (ZooKeeper zk = ClientBase.createZKClient(connectString)) {
                // then: all proposals must be replicated
                for (int i = 1; i <= 10; i++) {
                    String path = "/foo" + Long.toHexString(exclusiveStartCounter + i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                }

                // then: we have rollover to nextEpoch
                long nextEpoch = epoch + 1;
                assertEquals(nextEpoch, ZxidUtils.getEpochFromZxid(qu.getPeer(serverId).peer.getZkDb().getDataTreeLastProcessedZxid()));
                Stat stat = new Stat();
                zk.create("/server" + serverId, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, stat);
                assertEquals(nextEpoch, ZxidUtils.getEpochFromZxid(stat.getMzxid()));
            }
        }
    }

    @Test
    public void testMajorityFollowersCrashAfterWriteRolloverToDisk() throws Exception {
        final int N = 1;
        class Context {
            final Set<Long> crashed_servers = Collections.synchronizedSet(new LinkedHashSet<>());
            final BlockingQueue<Long> crashing_servers = new LinkedBlockingQueue<>();
        }
        Context context = new Context();
        qu = new QuorumUtil(N) {
            @Override
            protected Follower makeFollower(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException {
                return new Follower(self, new FollowerZooKeeperServer(logFactory, self, self.getZkDb()) {
                    @Override
                    public void fenceRolloverEpoch(long newEpoch) throws IOException {
                        super.fenceRolloverEpoch(newEpoch);
                        long myId = self.getMyId();
                        if (context.crashed_servers.size() < N + 1 && !context.crashed_servers.contains(myId)) {
                            context.crashed_servers.add(myId);
                            context.crashing_servers.add(myId);
                            throw new IOException("crash peer " + myId  + "after persist max epoch zxid");
                        }
                    }
                });
            }
        };
        qu.startAll();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;

        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString, watcher)) {
            // given: leader about to rollover
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));

            // when: multiple proposals during rollover to next epoch
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }

            // when: majority followers crashed after replicating rollover proposal
            for (int i = 0; i < N + 1; i++) {
                long serverId = context.crashing_servers.take();
                qu.shutdown((int) serverId);
            }

            watcher.waitForDisconnected(CONNECTION_TIMEOUT);
            watcher.reset();

            for (long serverId : context.crashed_servers) {
                qu.restart((int) serverId);
            }

            watcher.waitForConnected(CONNECTION_TIMEOUT);
        }

        // then: after quorum reformed, every node should meet following conditions
        long rolloverProposalZxid = 0;
        for (int serverId = 1; serverId <= 2 * N + 1; serverId++) {
            String connectString = qu.getConnectionStringForServer(serverId);
            try (ZooKeeper zk = ClientBase.createZKClient(connectString)) {
                // then: all proposals up to rollover proposal must be replicated
                for (long i = exclusiveStartCounter + 1; i < ZxidUtils.MAX_COUNTER; i++) {
                    String path = "/foo" + Long.toHexString(i);
                    Stat stat = zk.exists(path, false);
                    assertNotNull(stat, path + " not found");
                }

                // then: it is indeterminate whether the rollover proposal has been committed,
                // but it must be consistent among all nodes
                if (rolloverProposalZxid == 0) {
                    rolloverProposalZxid =
                            Optional.ofNullable(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER), false))
                                    .map(Stat::getCzxid).orElse(-1L);
                    LOG.info("Get rollover proposal zxid {}", ZxidUtils.zxidToString(rolloverProposalZxid));
                } else {
                    long zxid =
                            Optional.ofNullable(zk.exists("/foo" + Long.toHexString(MAX_ZXID_COUNTER), false))
                                    .map(Stat::getCzxid).orElse(-1L);
                    assertEquals(ZxidUtils.zxidToString(rolloverProposalZxid), ZxidUtils.zxidToString(zxid));
                }

                // then: new epoch proposal must not be committed
                assertNull(zk.exists("/foo" + Long.toHexString(ZxidUtils.MAX_COUNTER + 1), false));
            }
        }
    }

    @Test
    public void testLearnerRejoinDuringLeaderRolloverEpoch() throws Exception {
        final int N = 1;
        class Context {
            private final AtomicLong rolloverZxid = new AtomicLong(Long.MAX_VALUE);
            private final AtomicLong followerId = new AtomicLong(-1);
            private final CompletableFuture<Void> rolloverCommitting = new CompletableFuture<>();
            private final CompletableFuture<Void> rolloverCommitted = new CompletableFuture<>();
        }
        Context context = new Context();
        qu = new QuorumUtil(N) {
            @Override
            protected Leader makeLeader(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException, X509Exception {
                return new Leader(self, new LeaderZooKeeperServer(logFactory, self, self.getZkDb()) {
                    @Override
                    public DataTree.ProcessTxnResult processTxn(Request request) {
                        DataTree.ProcessTxnResult result = super.processTxn(request);
                        // Leader is about to rollover, combining with below randomness,
                        // we can test all cases:
                        // 1. Sync before rollover proposal committed.
                        // 2. Sync after rollover proposal committed.
                        // 3. Sync after proposals from new epoch committed.
                        if (request.zxid + 2 >= context.rolloverZxid.get()) {
                            context.rolloverCommitting.join();
                            context.rolloverCommitted.complete(null);
                        }
                        return result;
                    }
                }) {
                    @Override
                    public void waitForEpochAck(long id, StateSummary ss) throws IOException, InterruptedException {
                        super.waitForEpochAck(id, ss);
                        if (id == context.followerId.get()) {
                            context.rolloverCommitted.join();
                            // Sleep a bit before sync to allow more proposals to come.
                            Thread.sleep(new Random().nextInt(10));
                        }
                    }
                };
            }

            @Override
            protected Follower makeFollower(QuorumPeer self, FileTxnSnapLog logFactory) throws IOException {
                return new Follower(self, new FollowerZooKeeperServer(logFactory, self, self.getZkDb())) {
                    @Override
                    protected void syncWithLeader(long newLeaderZxid) throws Exception {
                        super.syncWithLeader(newLeaderZxid);
                    }

                    @Override
                    protected long registerWithLeader(int pktType) throws IOException {
                        long leaderZxid = super.registerWithLeader(pktType);
                        if (self.getMyId() == context.followerId.get()) {
                            context.rolloverCommitting.complete(null);
                        }
                        return leaderZxid;
                    }
                };
            }
        };
        qu.startAll();

        int followerId = (int) qu.getFollowerQuorumPeers().get(0).getMyId();
        CompletableFuture<Void> restarted = new CompletableFuture<>();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;
        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            // given: a re-joining follower
            qu.shutdown(followerId);

            ForkJoinPool.commonPool().submit(() -> {
                context.followerId.set(followerId);
                qu.restart(followerId);
                restarted.complete(null);
                return null;
            });

            // given: leader rollover to next epoch
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));
            context.rolloverZxid.set(ZxidUtils.makeZxid(epoch, MAX_ZXID_COUNTER));
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }
        }

        // when: follower rejoin
        restarted.join();

        String followerAddr = qu.getConnectionStringForServer(followerId);
        try (ZooKeeper zk = ClientBase.createZKClient(followerAddr)) {
            zk.sync("/");
            // then: all proposals must be replicated
            for (int i = 1; i <= 10; i++) {
                String path = "/foo" + Long.toHexString(exclusiveStartCounter + i);
                Stat stat = zk.exists(path, false);
                assertNotNull(stat, path + " not found");
            }
            QuorumPeer follower = qu.getPeer(followerId).peer;
            assertEquals(epoch + 1, follower.getAcceptedEpoch());
            assertEquals(epoch + 1, follower.getCurrentEpoch());
            assertEquals(epoch + 1, follower.getCurrentVote().getPeerEpoch());
        }
    }

    @Test
    public void testLearnerRejoinAfterLeaderRolloverEpoch() throws Exception {
        final int N = 1;
        qu = new QuorumUtil(N);
        qu.startAll();

        int followerId = (int) qu.getFollowerQuorumPeers().get(0).getMyId();

        ZooKeeperServer zkLeader = qu.getLeaderQuorumPeer().getActiveServer();
        long epoch = ZxidUtils.getEpochFromZxid(zkLeader.getZxid());
        long exclusiveStartCounter = MAX_ZXID_COUNTER - 5;
        // Connect only to leader to avoid re-connect attempts.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            // given: a shutdown follower
            qu.shutdown(followerId);

            // given: leader rollover to next epoch
            zkLeader.setZxid(setZxidCounter(zkLeader.getZxid(), exclusiveStartCounter));
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + Long.toHexString(exclusiveStartCounter + i),
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }
        }

        // when: follower rejoin
        qu.restart(followerId);

        String followerAddr = qu.getConnectionStringForServer(followerId);
        try (ZooKeeper zk = ClientBase.createZKClient(followerAddr)) {
            zk.sync("/");
            // then: all proposals must be replicated
            for (int i = 1; i <= 10; i++) {
                String path = "/foo" + Long.toHexString(exclusiveStartCounter + i);
                Stat stat = zk.exists(path, false);
                assertNotNull(stat, path + " not found");
            }
            QuorumPeer follower = qu.getPeer(followerId).peer;
            assertEquals(epoch + 1, follower.getAcceptedEpoch());
            assertEquals(epoch + 1, follower.getCurrentEpoch());
            assertEquals(epoch + 1, follower.getCurrentVote().getPeerEpoch());
        }
    }
}