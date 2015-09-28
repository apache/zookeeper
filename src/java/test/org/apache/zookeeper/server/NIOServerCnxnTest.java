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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;


import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NIOServerCnxnTest extends ClientBase {
    private static final Logger LOG = LoggerFactory
                        .getLogger(NIOServerCnxnTest.class);

    /**
     * Test operations on ServerCnxn after socket closure.
     */
    @Test(timeout = 60000)
    public void testOperationsAfterCnxnClose() throws IOException,
            InterruptedException, KeeperException {
        final ZooKeeper zk = createClient();

        final String path = "/a";
        try {
            // make sure zkclient works
            zk.create(path, "test".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            Assert.assertNotNull("Didn't create znode:" + path,
                    zk.exists(path, false));
            // Defaults ServerCnxnFactory would be instantiated with
            // NIOServerCnxnFactory
            assertTrue(
                    "Didn't instantiate ServerCnxnFactory with NIOServerCnxnFactory!",
                    serverFactory instanceof NIOServerCnxnFactory);
            Iterable<ServerCnxn> connections = serverFactory.getConnections();
            for (ServerCnxn serverCnxn : connections) {
                serverCnxn.close();
                try {
                    serverCnxn.toString();
                } catch (Exception e) {
                    LOG.error("Exception while getting connection details!", e);
                    Assert.fail("Shouldn't throw exception while "
                            + "getting connection details!");
                }
            }
        } finally {
            zk.close();
        }
    }

    @Test(timeout = 40000)
    public void testMaxConnections() throws Exception {

        final int numClients = 3;
        final int maxCnxns = 2;
        final int maxClientCnxns = -1;

        testConns(numClients, maxCnxns, maxClientCnxns, maxCnxns);
    }

    @Test(timeout = 40000)
    public void testMaxClientConnections() throws Exception {

        final int numClients = 3;
        final int maxCnxns = -1;
        final int maxClientCnxns = 2;

        testConns(numClients, maxCnxns, maxClientCnxns, maxClientCnxns);
    }

    @Test(timeout = 40000)
    public void testConnections() throws Exception {

        final int numClients = 3;
        final int maxCnxns = -1;
        final int maxClientCnxns = -1;

        testConns(numClients, maxCnxns, maxClientCnxns, numClients);
    }

    public void testConns(int numClients, int maxCnxns, int maxClientCnxns, int cnxnsAccepted) throws Exception {

        File tmpDir = ClientBase.createTmpDir();
        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        ServerCnxnFactory scf = ServerCnxnFactory.createFactory(CLIENT_PORT, maxCnxns, maxClientCnxns);
        scf.startup(zks);

        try {
            assertTrue("waiting for server being up", ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT));
            assertTrue("Didn't instantiate ServerCnxnFactory with NIOServerCnxnFactory!", scf instanceof NIOServerCnxnFactory);

            assertEquals(0, scf.getNumAliveConnections());

            final CountDownLatch countDownLatch = new CountDownLatch(cnxnsAccepted);

            TestableZooKeeper[] clients = new TestableZooKeeper[numClients];
            for (int i = 0; i < numClients; i++) {
                clients[i] = new TestableZooKeeper("127.0.0.1:" + CLIENT_PORT, 3000, new Watcher() {
                    @Override
                    public void process(WatchedEvent event)
                    {
                        if (event.getState() == Event.KeeperState.SyncConnected) {
                            countDownLatch.countDown();
                        }
                    }
                });
            }

            countDownLatch.await();

            assertEquals(cnxnsAccepted, scf.getNumAliveConnections());

            int connected = 0;
            for (int i = 0; i < numClients; i++) {
                if (clients[i].getState().isConnected()) connected++;
            }
            assertEquals(cnxnsAccepted, connected);

        } finally {
            scf.shutdown();
            zks.shutdown();
        }
    }
}
