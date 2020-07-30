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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperServerMaxCnxnsTest extends QuorumPeerTestBase {

    protected static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerMaxCnxnsTest.class);
    private static int SERVER_COUNT = 3;
    private MainThread[] mt;
    private ZooKeeper[] clients;

    /**
     * <pre>
     * Test case for https://issues.apache.org/jira/browse/ZOOKEEPER-2238.
     * Support limiting the maximum number of connections/clients to a ZooKeeper server.
     * </pre>
     */

    @Test
    @Timeout(value = 120)
    public void testMaxZooKeeperClientsWithNIOServerCnxnFactory() throws Exception {
        String serverCnxnFactory = "org.apache.zookeeper.server.NIOServerCnxnFactory";
        testMaxZooKeeperClients(serverCnxnFactory);
    }

    @Test
    @Timeout(value = 120)
    public void testMaxZooKeeperClientsWithNettyServerCnxnFactory() throws Exception {
        String serverCnxnFactory = "org.apache.zookeeper.server.NettyServerCnxnFactory";
        testMaxZooKeeperClients(serverCnxnFactory);
    }

    private void testMaxZooKeeperClients(String serverCnxnFactory) throws Exception {
        final int clientPorts[] = new int[SERVER_COUNT];
        int maxCnxns = 2;
        StringBuilder sb = new StringBuilder();
        sb.append("maxCnxns=" + maxCnxns + "\n");
        sb.append("serverCnxnFactory=" + serverCnxnFactory + "\n");
        String server;

        for (int i = 0; i < SERVER_COUNT; i++) {
            clientPorts[i] = PortAssignment.unique();
            server = "server." + i + "=127.0.0.1:" + PortAssignment.unique() + ":"
                    + PortAssignment.unique() + ":participant;127.0.0.1:" + clientPorts[i];
            sb.append(server + "\n");
        }
        String currentQuorumCfgSection = sb.toString();
        MainThread mt[] = new MainThread[SERVER_COUNT];

        // start 3 servers
        for (int i = 0; i < SERVER_COUNT; i++) {
            mt[i] = new MainThread(i, clientPorts[i], currentQuorumCfgSection, false);
            mt[i].start();
        }

        // ensure all servers started
        for (int i = 0; i < SERVER_COUNT; i++) {
            assertTrue(ClientBase.waitForServerUp("127.0.0.1:" + clientPorts[i], ClientBase.CONNECTION_TIMEOUT),
                    "waiting for server " + i + " being up");
        }

        int maxAllowedConnection = maxCnxns * SERVER_COUNT;
        String cxnString = getCxnString(clientPorts);

        final CountDownLatch countDownLatch = new CountDownLatch(maxAllowedConnection);
        ZooKeeper[] clients = new ZooKeeper[maxAllowedConnection];
        Watcher watcher = new Watcher() {

            @Override
            public void process(WatchedEvent event) {
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    countDownLatch.countDown();
                }
            }
        };
        for (int i = 0; i < maxAllowedConnection; i++) {
            clients[i] = new ZooKeeper(cxnString, ClientBase.CONNECTION_TIMEOUT, watcher);
            Thread.sleep(100);
        }
        countDownLatch.await();
        // reaching this point indicates that all maxAllowedConnection connected

        // No more client to be allowed to connect now as we have reached the
        // max connections
        CountdownWatcher cdw = new CountdownWatcher();
        ZooKeeper extraClient = new ZooKeeper(cxnString, ClientBase.CONNECTION_TIMEOUT, cdw);
        try {
            cdw.waitForConnected(ClientBase.CONNECTION_TIMEOUT / 2);
            fail("Client is not supposed to get connected as max connection already reached.");
        } catch (TimeoutException e) {
            extraClient.close();
        }

        // lets close one already connected client
        clients[0].close();

        // Now extra client must automatically get connected
        cdw = new CountdownWatcher();
        extraClient = new ZooKeeper(cxnString, ClientBase.CONNECTION_TIMEOUT, cdw);
        cdw.waitForConnected(ClientBase.CONNECTION_TIMEOUT);

        // verify some basic operation
        String create = extraClient.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        assertEquals("/test", create);

        // cleanup
        extraClient.close();
    }

    private String getCxnString(int[] clientPorts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < clientPorts.length; i++) {
            builder.append("127.0.0.1:" + clientPorts[i]);
            if (i != clientPorts.length - 1) {
                builder.append(",");
            }
        }
        return builder.toString();
    }

    @AfterEach
    public void tearDown() {
        // stop all clients
        if (clients != null) {
            for (ZooKeeper zooKeeper : clients) {
                try {
                    zooKeeper.close();
                } catch (InterruptedException e) {
                    LOG.warn("ZooKeeper interrupted while closing it.", e);
                }
            }
        }
        // stop all severs
        if (mt != null) {
            for (int i = 0; i < SERVER_COUNT; i++) {
                try {
                    mt[i].shutdown();
                } catch (InterruptedException e) {
                    LOG.warn("Quorum Peer interrupted while shutting it down", e);
                }
            }
        }
    }
}