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

package org.apache.zookeeper;

import java.io.File;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TakeSnapshotTest extends ClientBase {
    private static final Logger LOG = LoggerFactory.getLogger(TakeSnapshotTest.class);

    private static final String BASE = "/takeSnapshotTest";
    private static final int PERSISTENT_CNT = 100;
    private static final String HOSTPORT = "127.0.0.1:" + PortAssignment.unique();
    private ZooKeeper zk;
    private File tmpDir;
    private File backupDir;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        tmpDir = ClientBase.createTmpDir();
        backupDir = ClientBase.createTmpDir();

        ClientBase.setupTestEnv();
    }

    @Override
    public void tearDown() throws Exception {
        zk.close();
        ClientBase.recursiveDelete(tmpDir);
        ClientBase.recursiveDelete(backupDir);
    }

    @Test
    public void testTakeSnapshotSyncAndRestore() throws Exception {

        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));
        zk = ClientBase.createZKClient(HOSTPORT);

        for (int i = 0; i < PERSISTENT_CNT; i++) {
            String path = BASE + "-" + i;
            String data = String.valueOf(i);
            zk.create(path, data.getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        }

        String dir = backupDir.getAbsolutePath();
        // takeSnapshot
        zks.takeSnapshotExternal(dir);

        //cleaning up
        zk.close();
        zks.shutdown();

        // start server again and assert the data restored from snapshot
        zks = new ZooKeeperServer(backupDir, backupDir, 3000);
        f.startup(zks);
        Assert.assertTrue("waiting for server being up ",
                ClientBase.waitForServerUp(HOSTPORT, CONNECTION_TIMEOUT));

        zk = ClientBase.createZKClient(HOSTPORT);

        for (int i = 0; i < PERSISTENT_CNT; i++) {
            String path = BASE + "-" + i;
            String data = String.valueOf(i);

            Assert.assertEquals(new String(data.getBytes()), new String(zk.getData(path, null, null)));
        }

        //add a complete integrity check for the snapshot.the 3 node is /zookeeper,/zookeeper/config,/zookeeper/quota;
        Assert.assertEquals(3 + PERSISTENT_CNT, zk.getAllChildrenNumber("/"));

        zks.shutdown();
        f.shutdown();
    }
}
