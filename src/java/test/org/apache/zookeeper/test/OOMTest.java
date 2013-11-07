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

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

import junit.framework.TestCase;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.Test;

/**
 *
 */
public class OOMTest extends TestCase implements Watcher {
    @Test
    public void testOOM() throws IOException, InterruptedException, KeeperException {
        // This test takes too long to run!
        if (true)
            return;
        File tmpDir = ClientBase.createTmpDir();
        // Grab some memory so that it is easier to cause an
        // OOM condition;
        ArrayList<byte[]> hog = new ArrayList<byte[]>();
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
        NIOServerCnxn.Factory f = new NIOServerCnxn.Factory(
                new InetSocketAddress(PORT));
        f.startup(zks);
        assertTrue("waiting for server up",
                   ClientBase.waitForServerUp("127.0.0.1:" + PORT,
                                              CONNECTION_TIMEOUT));

        System.err.println("OOM Stage 0");
        utestPrep(PORT);
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                + " total = " + Runtime.getRuntime().totalMemory() + " max = "
                + Runtime.getRuntime().maxMemory());
        System.err.println("OOM Stage 1");
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
            utestExists(PORT);
        }
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                + " total = " + Runtime.getRuntime().totalMemory() + " max = "
                + Runtime.getRuntime().maxMemory());
        System.err.println("OOM Stage 2");
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
            utestGet(PORT);
        }
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                + " total = " + Runtime.getRuntime().totalMemory() + " max = "
                + Runtime.getRuntime().maxMemory());
        System.err.println("OOM Stage 3");
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
            utestChildren(PORT);
        }
        System.out.println("Free = " + Runtime.getRuntime().freeMemory()
                + " total = " + Runtime.getRuntime().totalMemory() + " max = "
                + Runtime.getRuntime().maxMemory());
        hog.get(0)[0] = (byte) 1;

        f.shutdown();
        assertTrue("waiting for server down",
                   ClientBase.waitForServerDown("127.0.0.1:" + PORT,
                                                CONNECTION_TIMEOUT));
    }

    private void utestExists(int port)
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk =
            new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, this);
        for (int i = 0; i < 10000; i++) {
            zk.exists("/this/path/doesnt_exist!", true);
        }
        zk.close();
    }

    private void utestPrep(int port)
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk =
            new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, this);
        for (int i = 0; i < 10000; i++) {
            zk.create("/" + i, null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        zk.close();
    }

    private void utestGet(int port)
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk =
            new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, this);
        for (int i = 0; i < 10000; i++) {
            Stat stat = new Stat();
            zk.getData("/" + i, true, stat);
        }
        zk.close();
    }

    private void utestChildren(int port)
        throws IOException, InterruptedException, KeeperException
    {
        ZooKeeper zk =
            new ZooKeeper("127.0.0.1:" + port, CONNECTION_TIMEOUT, this);
        for (int i = 0; i < 10000; i++) {
            zk.getChildren("/" + i, true);
        }
        zk.close();
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.Watcher#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    public void process(WatchedEvent event) {
        System.err.println("Got event " + event.getType() + " "
                + event.getState() + " " + event.getPath());
    }
}
