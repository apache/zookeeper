/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import java.io.IOException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ThrottledOpQuorumTest extends QuorumBase {
    @BeforeAll
    public static void applyMockUps() {
        ThrottledOpHelper.applyMockUps();
    }

    @Test
    public void testThrottledOpLeader() throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = null;
        try {
            zk = createClient("localhost:" + getLeaderClientPort());
            ZooKeeperServer zs = getLeaderQuorumPeer().getActiveServer();
            ThrottledOpHelper test = new ThrottledOpHelper();
            test.testThrottledOp(zk, zs);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    @Test
    public void testThrottledAclLeader() throws Exception {
        ZooKeeper zk = null;
        try {
            zk = createClient("localhost:" + getLeaderClientPort());
            ZooKeeperServer zs = getLeaderQuorumPeer().getActiveServer();
            ThrottledOpHelper test = new ThrottledOpHelper();
            test.testThrottledAcl(zk, zs);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    @Test
    public void testThrottledOpFollower() throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = null;
        try {
            int clientPort = (getLeaderClientPort() == portClient1) ? portClient2 : portClient1;
            zk = createClient("localhost:" + clientPort);
            QuorumPeer qp = (getLeaderClientPort() == portClient1) ? s2 : s1;
            ZooKeeperServer zs = qp.getActiveServer();
            ThrottledOpHelper test = new ThrottledOpHelper();
            test.testThrottledOp(zk, zs);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    @Test
    public void testThrottledAclFollower() throws Exception {
        ZooKeeper zk = null;
        try {
            int clientPort = (getLeaderClientPort() == portClient1) ? portClient2 : portClient1;
            zk = createClient("localhost:" + clientPort);
            QuorumPeer qp = (getLeaderClientPort() == portClient1) ? s2 : s1;
            ZooKeeperServer zs = qp.getActiveServer();
            ThrottledOpHelper test = new ThrottledOpHelper();
            test.testThrottledAcl(zk, zs);
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }
}
