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
package org.apache.zookeeper.server;

import static org.apache.zookeeper.server.persistence.FileSnap.SNAPSHOT_FILE_PREFIX;
import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.util.Set;
import java.util.zip.CheckedInputStream;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.persistence.SnapStream;
import org.apache.zookeeper.server.persistence.Util;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ZookeeperServerRestoreTest extends ZKTestCase {
    private static final String BASE_PATH = "/restoreFromSnapshotTest";
    private static final int NODE_COUNT = 10;
    private static final String HOST_PORT = "127.0.0.1:" + PortAssignment.unique();

    @TempDir
    static File dataDir;

    @TempDir
    static File logDir;

    @Test
    public void testRestoreFromSnapshot() throws Exception {
        ZooKeeperServer.setSerializeLastProcessedZxidEnabled(true);

        final ZooKeeperServer zks = new ZooKeeperServer(dataDir, logDir, 3000);
        final int port = Integer.parseInt(HOST_PORT.split(":")[1]);
        final ServerCnxnFactory serverCnxnFactory = ServerCnxnFactory.createFactory(port, -1);

        ZooKeeper zk1 = null;
        ZooKeeper zk2 = null;
        ZooKeeper zk3 = null;

        try {
            // start the server
            serverCnxnFactory.startup(zks);
            assertTrue(ClientBase.waitForServerUp(HOST_PORT, CONNECTION_TIMEOUT));

            // zk1 create test data
            zk1 = ClientBase.createZKClient(HOST_PORT);
            for (int i = 0; i < NODE_COUNT; i++) {
                final String path = BASE_PATH + "-" + i;
                zk1.create(path, String.valueOf(i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // take Snapshot
            final File snapshotFile = zks.takeSnapshot(false, false);
            final long lastZxidFromSnapshot = Util.getZxidFromName(snapshotFile.getName(), SNAPSHOT_FILE_PREFIX);

            // zk2 create more test data after snapshotting
            zk2 = ClientBase.createZKClient(HOST_PORT);
            for (int i = NODE_COUNT; i < NODE_COUNT * 2; i++) {
                final String path = BASE_PATH + "-" + i;
                zk2.create(path, String.valueOf(i).getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // restore from snapshot
            try (final CheckedInputStream is = SnapStream.getInputStream(snapshotFile)) {
                final long lastZxidFromRestore = zks.restoreFromSnapshot(is);

                // validate the last processed zxid
                assertEquals(lastZxidFromSnapshot, lastZxidFromRestore);

                // validate restored data only contains data from snapshot
                zk3 = ClientBase.createZKClient(HOST_PORT);
                for (int i = 0; i < NODE_COUNT; i++) {
                    final String path = BASE_PATH + "-" + i;
                    final String expectedData = String.valueOf(i);
                    assertArrayEquals(expectedData.getBytes(), zk3.getData(path, null, null));
                }
                assertEquals(NODE_COUNT + 3, zk3.getAllChildrenNumber("/"));

                // validate sessions
                final SessionTracker sessionTracker = zks.getSessionTracker();
                final Set<Long> globalSessions = sessionTracker.globalSessions();
                assertEquals(2, globalSessions.size());
                assertTrue(globalSessions.contains(zk1.getSessionId()));
                Assertions.assertFalse(globalSessions.contains(zk2.getSessionId()));
                assertTrue(globalSessions.contains(zk3.getSessionId()));

                // validate ZookeeperServer state
                assertEquals(ZooKeeperServer.State.RUNNING, zks.state);

                // validate being able to create more data after restore
                zk3.create(BASE_PATH + "_" + "after", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                assertEquals(NODE_COUNT + 4, zk3.getAllChildrenNumber("/"));
            }
        } finally {
            System.clearProperty("zookeeper.serializeLastProcessedZxid.enabled");

            if (zk1 != null) {
                zk1.close();
            }
            if (zk2 != null) {
                zk2.close();
            }
            if (zk3 != null) {
                zk3.close();
            }

            zks.shutdown();
            serverCnxnFactory.shutdown();
        }
    }

    @Test
    public void testRestoreFromSnapshot_nulInputStream() throws Exception {
        final ZooKeeperServer zks = new ZooKeeperServer(dataDir, logDir, 3000);
        assertThrows(IllegalArgumentException.class, () -> zks.restoreFromSnapshot(null));
    }
}
