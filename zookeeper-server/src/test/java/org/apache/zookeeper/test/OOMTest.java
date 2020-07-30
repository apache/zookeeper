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

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class OOMTest extends ZKTestCase {

    private static final Watcher TEST_WATCHER = event -> System.err.println("Got event: " + event);

    @Test
    @Disabled
    public void testOOM() throws IOException, InterruptedException, KeeperException {
        File tmpDir = ClientBase.createTmpDir();
        // Grab some memory so that it is easier to cause an
        // OOM condition;
        List<byte[]> hog = new ArrayList<>();
        while (true) {
            try {
                hog.add(new byte[1024 * 1024 * 2]);
            } catch (OutOfMemoryError e) {
                hog.remove(0);
                break;
            }
        }
        ClientBase.setupTestEnv();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);

        final int PORT = PortAssignment.unique();
        ServerCnxnFactory f = ServerCnxnFactory.createFactory(PORT, -1);
        f.startup(zks);
        assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + PORT, CONNECTION_TIMEOUT), "waiting for server up");

        System.err.println("OOM Stage 0");
        utestPrep(PORT);
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                               + " total = " + Runtime.getRuntime().totalMemory()
                               + " max = " + Runtime.getRuntime().maxMemory());
        System.err.println("OOM Stage 1");
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
            utestExists(PORT);
        }
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                               + " total = " + Runtime.getRuntime().totalMemory()
                               + " max = " + Runtime.getRuntime().maxMemory());
        System.err.println("OOM Stage 2");
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
            utestGet(PORT);
        }
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                               + " total = " + Runtime.getRuntime().totalMemory()
                               + " max = " + Runtime.getRuntime().maxMemory());
        System.err.println("OOM Stage 3");
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
            utestChildren(PORT);
        }
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                               + " total = " + Runtime.getRuntime().totalMemory()
                               + " max = " + Runtime.getRuntime().maxMemory());
        hog.get(0)[0] = (byte) 1;

        f.shutdown();
        zks.shutdown();
        assertTrue(ClientBase.waitForServerDown("127.0.0.1:" + PORT, CONNECTION_TIMEOUT),
                "waiting for server down");
    }

    private void utestExists(int port) throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, TEST_WATCHER);
        for (int i = 0; i < 10000; i++) {
            zk.exists("/this/path/doesnt_exist!", true);
        }
        zk.close();
    }

    private void utestPrep(int port) throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, TEST_WATCHER);
        for (int i = 0; i < 10000; i++) {
            zk.create("/" + i, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
    }

    private void utestGet(int port) throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, TEST_WATCHER);
        for (int i = 0; i < 10000; i++) {
            Stat stat = new Stat();
            zk.getData("/" + i, true, stat);
        }
        zk.close();
    }

    private void utestChildren(int port) throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, TEST_WATCHER);
        for (int i = 0; i < 10000; i++) {
            zk.getChildren("/" + i, true);
        }
        zk.close();
    }

}
