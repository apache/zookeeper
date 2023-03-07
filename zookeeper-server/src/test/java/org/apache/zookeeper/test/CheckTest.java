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

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CheckVersionRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class CheckTest extends ClientBase {

    @BeforeEach
    public void setUp(TestInfo testInfo) throws Exception {
        if (testInfo.getDisplayName().contains("Cluster")) {
            return;
        }
        super.setUp();
    }

    @AfterEach
    public void tearDown(TestInfo testInfo) throws Exception {
        if (testInfo.getDisplayName().contains("Cluster")) {
            return;
        }
        super.tearDown();
    }

    @Override
    public void setUp() throws Exception {
    }

    @Override
    public void tearDown() throws Exception {
    }

    private static void checkVersion(TestableZooKeeper zk, String path, int version) throws Exception {
        RequestHeader header = new RequestHeader();
        header.setType(ZooDefs.OpCode.check);
        CheckVersionRequest request = new CheckVersionRequest(path, version);
        ReplyHeader replyHeader = zk.submitRequest(header, request, null, null);
        if (replyHeader.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(replyHeader.getErr()), path);
        }
    }

    private void testOperations(TestableZooKeeper zk) throws Exception {
        Stat stat = new Stat();
        zk.getData("/", false, stat);
        checkVersion(zk, "/", -1);
        checkVersion(zk, "/", stat.getVersion());
        assertThrows(KeeperException.BadVersionException.class, () -> {
            checkVersion(zk, "/", stat.getVersion() + 1);
        });
        assertThrows(KeeperException.NoNodeException.class, () -> {
            checkVersion(zk, "/no-node", Integer.MAX_VALUE);
        });
    }

    @Test
    public void testStandalone() throws Exception {
        TestableZooKeeper zk = createClient();
        testOperations(zk);
        stopServer();
        startServer();
        createClient();
    }

    @Test
    public void testCluster() throws Exception {
        QuorumBase qb = new QuorumBase();
        try {
            qb.setUp(true, true);
            testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.OBSERVING));
            testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.FOLLOWING));
            testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.LEADING));
            int leaderIndex = qb.getLeaderIndex();
            int leaderPort = qb.getLeaderClientPort();
            qb.shutdown(qb.getLeaderQuorumPeer());
            qb.setupServer(leaderIndex + 1);
            QuorumPeer quorumPeer = qb.getPeerList().get(leaderIndex);
            quorumPeer.start();
            qb.createClient("localhost:" + leaderPort, 2 * CONNECTION_TIMEOUT);
        } finally {
            try {
                qb.tearDown();
            } catch (Exception ignored) {}
        }
    }
}
