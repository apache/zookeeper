/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 *
 */
package org.apache.zookeeper.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SSLTest extends QuorumPeerTestBase {

    @Before
    public void setup() {
        String testDataPath = System.getProperty("test.data.dir", "build/test/data");
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        System.setProperty(ZooKeeper.ZOOKEEPER_CLIENT_CNXN_SOCKET, "org.apache.zookeeper.ClientCnxnSocketNetty");
        System.setProperty(ZooKeeper.SECURE_CLIENT, "true");
        System.setProperty(X509Util.SSL_KEYSTORE_LOCATION, testDataPath + "/ssl/testKeyStore.jks");
        System.setProperty(X509Util.SSL_KEYSTORE_PASSWD, "testpass");
        System.setProperty(X509Util.SSL_TRUSTSTORE_LOCATION, testDataPath + "/ssl/testTrustStore.jks");
        System.setProperty(X509Util.SSL_TRUSTSTORE_PASSWD, "testpass");
    }

    @After
    public void teardown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZooKeeper.ZOOKEEPER_CLIENT_CNXN_SOCKET);
        System.clearProperty(ZooKeeper.SECURE_CLIENT);
        System.clearProperty(X509Util.SSL_KEYSTORE_LOCATION);
        System.clearProperty(X509Util.SSL_KEYSTORE_PASSWD);
        System.clearProperty(X509Util.SSL_TRUSTSTORE_LOCATION);
        System.clearProperty(X509Util.SSL_TRUSTSTORE_PASSWD);
    }

    /**
     * This test checks that SSL works in cluster setup of ZK servers, which includes:
     * 1. setting "secureClientPort" in "zoo.cfg" file.
     * 2. setting jvm flags for serverCnxn, keystore, truststore.
     * Finally, a zookeeper client should be able to connect to the secure port and
     * communicate with server via secure connection.
     * <p/>
     * Note that in this test a ZK server has two ports -- clientPort and secureClientPort.
     */
    @Test
    public void testSecureQuorumServer() throws Exception {
        final int SERVER_COUNT = 3;
        final int clientPorts[] = new int[SERVER_COUNT];
        final Integer secureClientPorts[] = new Integer[SERVER_COUNT];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            secureClientPorts[i] = PortAssignment.unique();
            String server = String.format("server.%d=localhost:%d:%d:participant;localhost:%d",
                    i, PortAssignment.unique(), PortAssignment.unique(), clientPorts[i]);
            sb.append(server + "\n");
        }
        String quorumCfg = sb.toString();


        MainThread[] mt = new MainThread[SERVER_COUNT];
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, quorumCfg, secureClientPorts[i], true);
            mt[i].start();
        }

        // Servers have been set up. Now go test if secure connection is successful.
        for (int i = 0; i < SERVER_COUNT; i++) {
            Assert.assertTrue("waiting for server " + i + " being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], TIMEOUT));

            final CountDownLatch latch = new CountDownLatch(1);
            ZooKeeper zk = new ZooKeeper("127.0.0.1:" + secureClientPorts[i], TIMEOUT,
                    new Watcher() {
                        @Override
                        public void process(WatchedEvent event) {
                            if (event.getState() != Event.KeeperState.SyncConnected) {
                                Assert.fail("failed to connect to ZK server secure client port");
                            }
                            latch.countDown();
                        }
                    });
            if (!latch.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
                Assert.fail("Timeout connecting to ZK server secure port");
            }
            // Do a simple operation to make sure the connection is fine.
            zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/test", -1);
            zk.close();
        }

        for (int i = 0; i < mt.length; i++) {
            mt[i].shutdown();
        }
    }


    /**
     * Developers might use standalone mode (which is the default for one server).
     * This test checks SSL works in standalone mode of ZK server.
     * <p/>
     * Note that in this test the Zk server has only secureClientPort
     */
    @Test
    public void testSecureStandaloneServer() throws Exception {
        Integer secureClientPort = PortAssignment.unique();
        MainThread mt = new MainThread(MainThread.UNSET_MYID, "", secureClientPort, false);
        mt.start();

        final CountDownLatch latch = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + secureClientPort, TIMEOUT,
                new Watcher() {
                    @Override
                    public void process(WatchedEvent event) {
                        if (event.getState() != Event.KeeperState.SyncConnected) {
                            Assert.fail("failed to connect to ZK server secure client port");
                        }
                        latch.countDown();
                    }
                });
        if (!latch.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
            Assert.fail("Timeout connecting to ZK server secure port");
        }
        zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/test", -1);
        zk.close();
        mt.shutdown();
    }
}
