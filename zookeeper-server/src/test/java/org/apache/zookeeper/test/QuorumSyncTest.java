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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import java.util.concurrent.CompletableFuture;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LearnerHandler;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.Test;

public class QuorumSyncTest extends QuorumBase {
    @Test
    public void testReadAfterSync() throws Exception {
        int leaderPort = getLeaderClientPort();

        ZooKeeper leaderReader = createClient("127.0.0.1:" + leaderPort);
        ZooKeeper followerWriter = createClient(getPeersMatching(QuorumPeer.ServerState.FOLLOWING));

        followerWriter.create("/test", "test0".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

        // given: dying leader
        Leader leader = getLeaderQuorumPeer().leader;
        for (LearnerHandler f : leader.getForwardingFollowers()) {
            f.getSocket().shutdownInput();
        }

        // and: write succeed in new epoch
        while (true) {
            try {
                followerWriter.setData("/test", "test1".getBytes(), -1);
                break;
            } catch (KeeperException.ConnectionLossException ignored) {
            }
        }

        while (true) {
            try {
                // when: sync succeed
                syncClient(leaderReader);

                // then: read up-to-date data
                byte[] test1 = leaderReader.getData("/test", null, null);
                assertArrayEquals("test1".getBytes(), test1);
                break;
            } catch (Exception ignored) {
            }
        }
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
}
