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

package org.apache.zookeeper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TakeSnapshotTest extends ClientBase {
    private static final String BASE_PATH = "/takeSnapshotTest";
    private static final int NODE_COUNT = 100;
    private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private ZooKeeper zk;

    @TempDir
    static File dataDir;

    @TempDir
    static File logDir;


    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        ClientBase.setupTestEnv();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (zk != null) {
            zk.close();
        }
    }

    @Test
    public void testTakeSnapshotAndRestore() throws Exception {
        ZooKeeperServer zks = new ZooKeeperServer(dataDir, logDir, 3000);
        ZooKeeperServer.setSerializeLastProcessedZxidEnabled(true);

        final int port = Integer.parseInt(HOSTPORT.split(":")[1]);
        final ServerCnxnFactory serverCnxnFactory = ServerCnxnFactory.createFactory(port, -1);
        serverCnxnFactory.startup(zks);
        assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

        try {
            zk = ClientBase.createZKClient(HOSTPORT);
            for (int i = 0; i < NODE_COUNT; i++) {
                final String path = BASE_PATH + "-" + i;
                zk.create(path, String.valueOf(i).getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            // takeSnapshot
            zks.takeSnapshot(false, false, true);

            // clean up
            zk.close();
            zks.shutdown();

            // start server again and assert the data restored from snapshot
            zks = new ZooKeeperServer(dataDir, logDir, 3000);
            ZooKeeperServer.setSerializeLastProcessedZxidEnabled(false);

            serverCnxnFactory.startup(zks);
            assertTrue(ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

            zk = ClientBase.createZKClient(HOSTPORT);
            for (int i = 0; i < NODE_COUNT; i++) {
                final String path = BASE_PATH + "-" + i;
                final String expectedData = String.valueOf(i);
                assertArrayEquals(expectedData.getBytes(), zk.getData(path, null, null));
            }
            assertEquals(NODE_COUNT + 3, zk.getAllChildrenNumber("/"));
        } finally {
            zks.shutdown();
            serverCnxnFactory.shutdown();
        }
    }
}
