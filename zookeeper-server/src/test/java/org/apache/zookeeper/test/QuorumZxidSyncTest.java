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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.DummyWatcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class QuorumZxidSyncTest extends ZKTestCase {

    QuorumBase qb = new QuorumBase();

    @BeforeEach
    public void setUp() throws Exception {
        qb.setUp();
    }

    /**
     * find out what happens when a follower connects to leader that is behind
     */
    @Test
    public void testBehindLeader() throws Exception {
        // crank up the epoch numbers
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ZooKeeper zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        zk.create("/0", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        zk.create("/1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        zk.create("/2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdownServers();
        cleanAndInitializeDataDir(qb.s1dir);
        cleanAndInitializeDataDir(qb.s2dir);
        cleanAndInitializeDataDir(qb.s3dir);
        cleanAndInitializeDataDir(qb.s4dir);
        qb.setupServers();
        qb.s1.start();
        qb.s2.start();
        qb.s3.start();
        qb.s4.start();
        assertTrue(ClientBase.waitForServerUp(qb.hostPort, 10000), "Servers didn't come up");
        qb.s5.start();
        String hostPort = "127.0.0.1:" + qb.s5.getClientPort();
        assertFalse(ClientBase.waitForServerUp(hostPort, 10000), "Servers came up, but shouldn't have since it's ahead of leader");
    }

    private void cleanAndInitializeDataDir(File f) throws IOException {
        File v = new File(f, "version-2");
        for (File c : v.listFiles()) {
            c.delete();
        }
        ClientBase.createInitializeFile(f);
    }

    /**
     * find out what happens when the latest state is in the snapshots not
     * the logs.
     */
    @Test
    public void testLateLogs() throws Exception {
        // crank up the epoch numbers
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        ZooKeeper zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        zk.create("/0", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        zk.create("/1", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        qb.shutdownServers();
        deleteLogs(qb.s1dir);
        deleteLogs(qb.s2dir);
        deleteLogs(qb.s3dir);
        deleteLogs(qb.s4dir);
        deleteLogs(qb.s5dir);
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        zk.create("/2", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.close();
        qb.shutdownServers();
        qb.startServers();
        ClientBase.waitForServerUp(qb.hostPort, 10000);
        zk = new ZooKeeper(qb.hostPort, 10000, DummyWatcher.INSTANCE);
        boolean saw2 = false;
        for (String child : zk.getChildren("/", false)) {
            if (child.equals("2")) {
                saw2 = true;
            }
        }
        zk.close();
        assertTrue(saw2, "Didn't see /2 (went back in time)");
    }

    private void deleteLogs(File f) {
        File v = new File(f, "version-2");
        for (File c : v.listFiles()) {
            if (c.getName().startsWith("log")) {
                c.delete();
            }
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        qb.tearDown();
    }

}
