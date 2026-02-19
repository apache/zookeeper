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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SyncDiskErrorTest extends ZKTestCase {
    private static final int CONNECTION_TIMEOUT = ClientBase.CONNECTION_TIMEOUT;

    QuorumUtil qu;

    @BeforeEach
    public void setUp() {
        // write and sync every txn
        System.setProperty("zookeeper.maxBatchSize", "1");
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty("zookeeper.maxBatchSize");
        if (qu != null) {
            qu.shutdownAll();
        }
    }

    @Test
    public void testFollowerRejoinAndRestartAfterTemporaryDiskError() throws Exception {
        class Context {
            final AtomicLong followerId = new AtomicLong(-1);
            final CompletableFuture<Void> hang = new CompletableFuture<>();
            final CompletableFuture<Void> error = new CompletableFuture<>();
        }
        Context context = new Context();
        final int N = 1;
        qu = new QuorumUtil(N) {
            @Override
            protected QuorumPeer newQuorumPeer(PeerStruct ps) throws IOException {
                QuorumPeer peer = super.newQuorumPeer(ps);
                peer.setZKDatabase(new ZKDatabase(peer.getTxnFactory()) {
                    @Override
                    public void commit() throws IOException {
                        if (peer.follower != null && peer.getMyId() == context.followerId.get()) {
                            context.hang.complete(null);
                            context.error.join();
                            throw new IOException("temporary disk error");
                        }
                        super.commit();
                    }
                });
                return peer;
            }
        };
        qu.startAll();

        int followerId = (int) qu.getFollowerQuorumPeers().get(0).getMyId();
        String followerConnectString = qu.getConnectionStringForServer(followerId);

        // Connect to leader to avoid connection to faulty node.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            // given: follower disk hang temporarily and error
            context.followerId.set(followerId);

            // given: multiple write txn committed meanwhile
            for (int i = 1; i < 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + i,
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }
            zk.create("/foo" + 10, new byte[0], ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);
            context.hang.join();
            context.followerId.set(-1);
            context.error.complete(null);

            // given: re-join after disk error
            ClientBase.waitForServerUp(followerConnectString, CONNECTION_TIMEOUT);

            // given: follower state is good
            try (ZooKeeper followerZk = ClientBase.createZKClient(followerConnectString)) {
                followerZk.sync("/");
                for (int i = 1; i <= 10; i++) {
                    String path = "/foo" + i;
                    assertNotNull(followerZk.exists(path, false), path + " not found");
                }
            }

            // given: more write txns
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/bar" + i,
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }
        }

        // when: restart follower node
        qu.shutdown(followerId);
        qu.restart(followerId);

        // then: follower state should still be good too
        try (ZooKeeper zk = ClientBase.createZKClient(followerConnectString)) {
            for (int i = 1; i <= 10; i++) {
                String path = "/bar" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }
            for (int i = 1; i <= 10; i++) {
                String path = "/foo" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }
        }
    }

    @Test
    public void testDiffSyncAfterTemporaryDiskErrorAndLeading() throws Exception {
        class Context {
            final AtomicLong followerId = new AtomicLong(-1);
            final CompletableFuture<Void> hang = new CompletableFuture<>();
            final CompletableFuture<Void> error = new CompletableFuture<>();
        }
        Context context = new Context();
        // N >= 2, so we can commit with stale follower and error follower
        final int N = 2;
        qu = new QuorumUtil(N) {
            @Override
            protected QuorumPeer newQuorumPeer(PeerStruct ps) throws IOException {
                QuorumPeer peer = super.newQuorumPeer(ps);
                peer.setZKDatabase(new ZKDatabase(peer.getTxnFactory()) {
                    @Override
                    public void commit() throws IOException {
                        if (peer.follower != null && peer.getMyId() == context.followerId.get()) {
                            context.hang.complete(null);
                            context.error.join();
                            throw new IOException("temporary disk error");
                        }
                        super.commit();
                    }
                });
                // Force DIFF sync
                peer.getZkDb().setSnapshotSizeFactor(1000000);
                return peer;
            }
        };
        qu.startAll();

        int[] followerIds = qu.getFollowerQuorumPeers()
                .stream()
                .sorted(Comparator.comparingLong(QuorumPeer::getMyId).reversed())
                .mapToInt(peer -> (int) peer.getMyId())
                .toArray();
        int followerId = followerIds[0];
        String followerConnectString = qu.getConnectionStringForServer(followerId);

        int staleFollowerId = followerIds[1];

        // Connect to leader to avoid connection to faulty node.
        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            // given: another stale follower
            qu.shutdown(staleFollowerId);

            // given: follower disk hang temporarily and error
            context.followerId.set(followerId);

            // given: multiple write txn committed meanwhile
            for (int i = 1; i < 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/foo" + i,
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }
            zk.create("/foo" + 10, new byte[0], ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);
            context.hang.join();
            context.followerId.set(-1);
            context.error.complete(null);

            // given: re-join after disk error
            ClientBase.waitForServerUp(followerConnectString, CONNECTION_TIMEOUT);

            // given: follower state is good
            try (ZooKeeper followerZk = ClientBase.createZKClient(followerConnectString)) {
                followerZk.sync("/");
                for (int i = 1; i <= 10; i++) {
                    String path = "/foo" + i;
                    assertNotNull(followerZk.exists(path, false), path + " not found");
                }
            }

            // given: more write txns
            for (int i = 1; i <= 10; i++) {
                // Creates them asynchronous to mimic concurrent operations.
                zk.create(
                        "/bar" + i,
                        new byte[0],
                        ZooDefs.Ids.READ_ACL_UNSAFE,
                        CreateMode.PERSISTENT,
                        (rc, path, ctx, name) -> {},
                        null);
            }
        }

        // when: become leader
        while (qu.getLeaderServer() != followerId) {
            int leaderId = qu.getLeaderServer();
            long syncTimeout = (long) qu.getLeaderQuorumPeer().getTickTime() * qu.getLeaderQuorumPeer().getSyncLimit();
            qu.shutdown(leaderId);
            Thread.sleep(syncTimeout);
            qu.restart(leaderId);
        }

        // and: write some txns
        try (ZooKeeper zk = ClientBase.createZKClient(followerConnectString)) {
            for (int i = 1; i <= 10; i++) {
                zk.create("/foobar" + i, new byte[0], ZooDefs.Ids.READ_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }

        // when: DIFF sync to stale follower
        qu.restart(staleFollowerId);

        // then: follower state should still be good too
        String staleFollowerConnectString = qu.getConnectionStringForServer(staleFollowerId);
        try (ZooKeeper zk = ClientBase.createZKClient(staleFollowerConnectString)) {
            zk.sync("/");
            for (int i = 1; i <= 10; i++) {
                String path = "/bar" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }
            for (int i = 1; i <= 10; i++) {
                String path = "/foo" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }
        }
    }
}
