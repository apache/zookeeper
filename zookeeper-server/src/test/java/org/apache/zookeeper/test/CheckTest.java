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
import java.io.File;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.CheckVersionRequest;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class CheckTest extends ClientBase {

    @BeforeEach
    public void setUp(TestInfo testInfo) throws Exception {
        System.setProperty(ZKClientConfig.ZOOKEEPER_REQUEST_TIMEOUT, "2000");
        if (testInfo.getDisplayName().contains("Cluster")) {
            return;
        }
        super.setUp();
    }

    @AfterEach
    public void tearDown(TestInfo testInfo) throws Exception {
        System.clearProperty(ZKClientConfig.ZOOKEEPER_REQUEST_TIMEOUT);
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
        testOperations(createClient());
    }

    @Test
    public void testStandaloneDatabaseReloadAfterCheck() throws Exception {
        try {
            testOperations(createClient());
        } catch (Exception ignored) {
            // Ignore to test database reload after check
        }
        stopServer();
        startServer();
    }

    @Test
    public void testCluster() throws Exception {
        QuorumBase qb = new QuorumBase();
        try {
            qb.setUp(true, true);
            testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.OBSERVING));
            testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.FOLLOWING));
            testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.LEADING));
        } finally {
            try {
                qb.tearDown();
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testClusterDatabaseReloadAfterCheck() throws Exception {
        QuorumBase qb = new QuorumBase();
        try {
            qb.setUp(true, true);

            // Get leader before possible damaging operations to
            // reduce chance of leader migration and log truncation.
            File dataDir = qb.getLeaderDataDir();
            QuorumPeer leader = qb.getLeaderQuorumPeer();

            try {
                testOperations(qb.createClient(new CountdownWatcher(), QuorumPeer.ServerState.LEADING));
            } catch (Exception ignored) {
                // Ignore to test database reload after check
            }
            qb.shutdown(leader);

            FileTxnSnapLog txnSnapLog = new FileTxnSnapLog(dataDir, dataDir);
            ZKDatabase database = new ZKDatabase(txnSnapLog);
            database.loadDataBase();
        } finally {
            try {
                qb.tearDown();
            } catch (Exception ignored) {}
        }
    }
}
