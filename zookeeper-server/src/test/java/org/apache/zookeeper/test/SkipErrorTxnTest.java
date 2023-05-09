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

package org.apache.zookeeper.test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.concurrent.CompletableFuture;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SkipErrorTxnTest extends QuorumBase {
    protected boolean skipErrorTxn() {
        return true;
    }

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        // TODO: setup an follower as observer master.
        System.setProperty(ZooKeeperServer.SKIP_ERROR_TXN, String.valueOf(skipErrorTxn()));
        setUp(true, false);
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

    private void testWriteError(String hp) throws Exception {
        ZooKeeper zk = createClient(hp);
        syncClient(zk);
        QuorumPeer leader = getLeaderQuorumPeer();
        long lastZxid = leader.getLastLoggedZxid();

        // Issue an asynchronous request first, so we can test response order.
        final CompletableFuture<Void> future = new CompletableFuture<>();
        zk.create("/a1", null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT, (int rc, String path, Object ctx, String name) -> {
            if (rc == 0) {
                future.complete(null);
            } else {
                future.completeExceptionally(KeeperException.create(KeeperException.Code.get(rc), path));
            }
        }, null);

        zk.sync("/", null, null);

        assertThrows(KeeperException.NoNodeException.class, () -> {
            zk.setData("/a2", null, -1);
        });
        future.join();

        syncClient(zk);
        long expectedZxid = lastZxid + 1;
        if (skipErrorTxn()) {
            assertThat(leader.getLastLoggedZxid(), is(expectedZxid));
        } else {
            assertThat(leader.getLastLoggedZxid(), greaterThan(expectedZxid));
        }
    }

    @Test
    public void testLeaderWriteError() throws Exception {
        testWriteError(getPeersMatching(QuorumPeer.ServerState.LEADING));
    }

    @Test
    public void testFollowerWriteError() throws Exception {
        testWriteError(getPeersMatching(QuorumPeer.ServerState.FOLLOWING));
    }

    @Test
    public void testObserverWriteError() throws Exception {
        testWriteError(getPeersMatching(QuorumPeer.ServerState.OBSERVING));
    }
}
