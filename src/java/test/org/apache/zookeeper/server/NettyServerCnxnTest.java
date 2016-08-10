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


import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.TestableZooKeeper;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Test verifies the behavior of NettyServerCnxn which represents a connection
 * from a client to the server.
 */
public class NettyServerCnxnTest extends ClientBase {
    private static final Logger LOG = LoggerFactory
            .getLogger(NettyServerCnxnTest.class);

    @Override
    public void setUp() throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                "org.apache.zookeeper.server.NettyServerCnxnFactory");
        super.setUp();
    }

    /**
     * Test verifies the channel closure - while closing the channel
     * servercnxnfactory should remove all channel references to avoid
     * duplicate channel closure. Duplicate closure may result in indefinite
     * hanging due to netty open issue.
     * 
     * @see <a href="https://issues.jboss.org/browse/NETTY-412">NETTY-412</a>
     */
    @Test(timeout = 40000)
    public void testSendCloseSession() throws Exception {
        assertTrue(
                "Didn't instantiate ServerCnxnFactory with NettyServerCnxnFactory!",
                serverFactory instanceof NettyServerCnxnFactory);

        final ZooKeeper zk = createClient();
        final String path = "/a";
        try {
            // make sure zkclient works
            zk.create(path, "test".getBytes(), Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
            Assert.assertNotNull("Didn't create znode:" + path,
                    zk.exists(path, false));
            Iterable<ServerCnxn> connections = serverFactory.getConnections();
            Assert.assertEquals("Mismatch in number of live connections!", 1,
                    serverFactory.getNumAliveConnections());
            for (ServerCnxn serverCnxn : connections) {
                serverCnxn.sendCloseSession();
            }
            LOG.info("Waiting for the channel disconnected event");
            int timeout = 0;
            while (serverFactory.getNumAliveConnections() != 0) {
                Thread.sleep(1000);
                timeout += 1000;
                if (timeout > CONNECTION_TIMEOUT) {
                    Assert.fail("The number of live connections should be 0");
                }
            }
        } finally {
            zk.close();
        }
    }

    @Test(timeout = 40000)
    public void testMaxClientConnectionsReached() throws Exception {
        final int maxClientCnxns = 2;
        final int numClients = 10;
        createAndTestConnections(numClients, maxClientCnxns, maxClientCnxns);
    }

    @Test(timeout = 40000)
    public void testMaxClientConnectionsDisabled() throws Exception {
        final int maxClientCnxns = -1; // disabled cnxns limit
        final int numClients = 10;
        createAndTestConnections(numClients, maxClientCnxns, numClients);
    }

    private void createAndTestConnections(int numClients, int maxClientCnxns, int cnxnsAccepted) throws Exception {

        File tmpDir = ClientBase.createTmpDir();
        final int CLIENT_PORT = PortAssignment.unique();

        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        ServerCnxnFactory scf = ServerCnxnFactory.createFactory(CLIENT_PORT, maxClientCnxns);
        scf.startup(zks);

        try {
            assertTrue("waiting for server being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT, CONNECTION_TIMEOUT));
            assertTrue("Didn't instantiate ServerCnxnFactory with NettyServerCnxnFactory!",
                    scf instanceof NettyServerCnxnFactory);

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

            ConcurrentMap<InetAddress, Set<NettyServerCnxn>> ipMap = ((NettyServerCnxnFactory) scf).ipMap;
            assertEquals(1, ipMap.size());
            Set<NettyServerCnxn> set = ipMap.get(ipMap.keySet().toArray()[0]);
            assertEquals(cnxnsAccepted, set.size());


            int connected = 0;
            for (int i = 0; i < numClients; i++) {
                if (clients[i].getState().isConnected()) connected++;
            }
            assertEquals(connected, set.size());

            for (int i = 0; i < numClients; i++) {
                if (clients[i].getState().isConnected()) clients[i].close();
            }
            assertEquals(0, set.size());

        } finally {
            scf.shutdown();
            zks.shutdown();
        }
    }
}
