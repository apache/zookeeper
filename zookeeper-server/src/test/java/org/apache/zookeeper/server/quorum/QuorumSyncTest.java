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
import java.util.Comparator;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class QuorumSyncTest extends ZKTestCase {
    private QuorumUtil qu;

    @AfterEach
    public void tearDown() throws Exception {
        if (qu != null) {
            qu.shutdownAll();
        }
    }

    @Test
    public void testStaleDiffSync() throws Exception {
        qu = new QuorumUtil(2);
        qu.startAll();

        int[] followerIds = qu.getFollowerQuorumPeers()
            .stream()
            .sorted(Comparator.comparingLong(QuorumPeer::getMyId).reversed())
            .mapToInt(peer -> (int) peer.getMyId()).toArray();

        int follower1 = followerIds[0];
        int follower2 = followerIds[1];

        String leaderConnectString = qu.getConnectString(qu.getLeaderQuorumPeer());
        try (ZooKeeper zk = ClientBase.createZKClient(leaderConnectString)) {
            qu.shutdown(follower2);

            for (int i = 0; i < 10; i++) {
                zk.create("/foo" + i, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            qu.shutdown(follower1);

            for (int i = 0; i < 10; i++) {
                zk.create("/bar" + i, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            qu.restart(follower1);
        }

        try (ZooKeeper zk = ClientBase.createZKClient(qu.getConnectionStringForServer(follower1))) {
            for (int i = 0; i < 10; i++) {
                String path = "/foo" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }

            for (int i = 0; i < 10; i++) {
                String path = "/bar" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }
        }

        qu.shutdown(qu.getLeaderServer());

        qu.restart(follower2);

        try (ZooKeeper zk = ClientBase.createZKClient(qu.getConnectionStringForServer(follower2))) {
            for (int i = 0; i < 10; i++) {
                String path = "/foo" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }

            for (int i = 0; i < 10; i++) {
                String path = "/bar" + i;
                assertNotNull(zk.exists(path, false), path + " not found");
            }
        }
    }
}
