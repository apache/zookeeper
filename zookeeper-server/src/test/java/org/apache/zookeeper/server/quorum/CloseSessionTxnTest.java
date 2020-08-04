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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.Test;

public class CloseSessionTxnTest extends QuorumPeerTestBase {

    /**
     * Test leader/leader compatibility with/without CloseSessionTxn, so that
     * we can gradually rollout this code and rollback if there is problem.
     */
    @Test
    public void testCloseSessionTxnCompatile() throws Exception {
        // Test 4 cases:
        // 1. leader disabled, follower disabled
        testCloseSessionWithDifferentConfig(false, false);

        // 2. leader disabled, follower enabled
        testCloseSessionWithDifferentConfig(false, true);

        // 3. leader enabled, follower disabled
        testCloseSessionWithDifferentConfig(true, false);

        // 4. leader enabled, follower enabled
        testCloseSessionWithDifferentConfig(true, true);
    }

    private void testCloseSessionWithDifferentConfig(
            boolean closeSessionEnabledOnLeader,
            boolean closeSessionEnabledOnFollower) throws Exception {
        // 1. set up an ensemble with 3 servers
        final int numServers = 3;
        servers = LaunchServers(numServers);
        int leaderId = servers.findLeader();
        ZooKeeperServer.setCloseSessionTxnEnabled(closeSessionEnabledOnLeader);

        // 2. shutdown one of the follower, start it later to pick up the
        // CloseSessionTxnEnabled config change
        //
        // We cannot use different static config in the same JVM, so have to
        // use this tricky
        int followerA = (leaderId + 1) % numServers;
        servers.mt[followerA].shutdown();
        waitForOne(servers.zk[followerA], States.CONNECTING);

        // 3. create an ephemeral node
        String path = "/testCloseSessionTxnCompatile";
        servers.zk[leaderId].create(path, new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);

        // 3. close the client
        servers.restartClient(leaderId, this);
        waitForOne(servers.zk[leaderId], States.CONNECTED);

        // 4. update the CloseSessionTxnEnabled config before follower A
        // started
        System.setProperty("zookeeper.retainZKDatabase", "true");
        ZooKeeperServer.setCloseSessionTxnEnabled(closeSessionEnabledOnFollower);

        // 5. restart follower A
        servers.mt[followerA].start();
        waitForOne(servers.zk[followerA], States.CONNECTED);

        // 4. verify the ephemeral node is gone
        for (int i = 0; i < numServers; i++) {
            final CountDownLatch syncedLatch = new CountDownLatch(1);
            servers.zk[i].sync(path, (rc, path1, ctx) -> syncedLatch.countDown(), null);
            assertTrue(syncedLatch.await(3, TimeUnit.SECONDS));
            assertNull(servers.zk[i].exists(path, false));
        }
    }
 }
