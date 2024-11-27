/*
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

package org.apache.zookeeper.server;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ZookeeperServerSnapshotTest extends ZKTestCase {
    private static final String BASE_PATH = "/takeSnapshotTest";
    private static final int NODE_COUNT = 10;
    private static final String HOST_PORT = "127.0.0.1:" + PortAssignment.unique();

    @TempDir
    static File dataDir;

    @TempDir
    static File logDir;

    @Test
    public void testTakeSnapshot() throws Exception {
        ZooKeeperServer zks = new ZooKeeperServer(dataDir, logDir, 3000);
        ZooKeeperServer.setSerializeLastProcessedZxidEnabled(true);

        final int port = Integer.parseInt(HOST_PORT.split(":")[1]);
        final ServerCnxnFactory serverCnxnFactory = ServerCnxnFactory.createFactory(port, -1);
        ZooKeeper zk = null;
        try  {
            serverCnxnFactory.startup(zks);
            assertTrue(ClientBase.waitForServerUp(HOST_PORT, CONNECTION_TIMEOUT));

            zk = ClientBase.createZKClient(HOST_PORT);
            for (int i = 0; i < NODE_COUNT; i++) {
                final String path = BASE_PATH + "-" + i;
                zk.create(path, String.valueOf(i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // takeSnapshot
            zks.takeSnapshot(false, false);

            // clean up
            zk.close();
            zks.shutdown();

            // restart server and assert the data restored from snapshot
            zks = new ZooKeeperServer(dataDir, logDir, 3000);
            ZooKeeperServer.setSerializeLastProcessedZxidEnabled(false);

            serverCnxnFactory.startup(zks);
            assertTrue(ClientBase.waitForServerUp(HOST_PORT, CONNECTION_TIMEOUT));

            zk = ClientBase.createZKClient(HOST_PORT);
            for (int i = 0; i < NODE_COUNT; i++) {
                final String path = BASE_PATH + "-" + i;
                final String expectedData = String.valueOf(i);
                assertArrayEquals(expectedData.getBytes(), zk.getData(path, null, null));
            }
            assertEquals(NODE_COUNT + 3, zk.getAllChildrenNumber("/"));
        } finally {
            if (zk != null) {
                zk.close();
            }

            zks.shutdown();
            serverCnxnFactory.shutdown();
        }
    }
}
