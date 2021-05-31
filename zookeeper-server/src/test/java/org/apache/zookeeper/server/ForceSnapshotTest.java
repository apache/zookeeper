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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ForceSnapshotTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(ForceSnapshotTest.class);

    private static final int TEST_OP_COUNT = 10;

    private ZooKeeper zk;
    private ZooKeeperServer server;

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        server = serverFactory.getZooKeeperServer();
        zk = createClient();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // server will be closed in super.tearDown
        super.tearDown();

        if (zk != null) {
            zk.close();
        }
    }

    @Test
    public void noForceSnapshot() throws Exception {
        assertFalse(this.server.isForceSnapshot());

        String pathPrefix = "/testForceSnapshot";
        for (int i = 0; i < TEST_OP_COUNT; i++) {
            String path = pathPrefix + i;
            zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        List<File> snaps = fileTxnSnapLog.findNValidSnapshots(TEST_OP_COUNT * 2);
        assertEquals(1, snaps.size());
    }

    @Test
    public void forceSnapshotOnEveryOp() throws Exception {
        assertFalse(this.server.isForceSnapshot());

        String pathPrefix = "/testForceSnapshot";
        for (int i = 0; i < TEST_OP_COUNT; i++) {
            String path = pathPrefix + i;
            server.setForceSnapshot(true);
            zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertFalse(server.isForceSnapshot());
        }

        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        List<File> snaps = fileTxnSnapLog.findNValidSnapshots(TEST_OP_COUNT * 2);
        assertEquals(TEST_OP_COUNT + 1, snaps.size());
    }

    @Test
    public void forceSnapshotOnce() throws Exception {
        assertFalse(this.server.isForceSnapshot());

        String pathPrefix = "/testForceSnapshot";
        int triggerId = ThreadLocalRandom.current().nextInt(TEST_OP_COUNT);
        for (int i = 0; i < TEST_OP_COUNT; i++) {
            String path = pathPrefix + i;
            if (triggerId == i) {
                server.setForceSnapshot(true);
            }
            zk.create(path, path.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            assertFalse(server.isForceSnapshot());
        }

        FileTxnSnapLog fileTxnSnapLog = new FileTxnSnapLog(tmpDir, tmpDir);
        List<File> snaps = fileTxnSnapLog.findNValidSnapshots(TEST_OP_COUNT * 2);
        assertEquals(2, snaps.size());
    }
}
