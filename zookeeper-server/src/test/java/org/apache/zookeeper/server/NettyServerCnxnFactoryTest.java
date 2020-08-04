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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.server.metric.SimpleCounter;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.SSLAuthTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerCnxnFactoryTest extends ClientBase {

    private static final Logger LOG = LoggerFactory
            .getLogger(NettyServerCnxnFactoryTest.class);

    ClientX509Util x509Util;
    final LinkedBlockingQueue<ZooKeeper> zooKeeperClients = new LinkedBlockingQueue<>();


    @Override
    public void setUp() throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                "org.apache.zookeeper.server.NettyServerCnxnFactory");

        // by default, we don't start any ZooKeeper server, as not all the tests are needing it.
    }

    @Override
    public void tearDown() throws Exception {

        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        if (x509Util != null) {
            SSLAuthTest.clearSecureSetting(x509Util);
        }
        for (ZooKeeper zk : zooKeeperClients) {
            zk.close();
        }

        //stopping the server only if it was started
        if (serverFactory != null) {
            super.tearDown();
        }
    }

    @Test
    public void testRebind() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(PortAssignment.unique());
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        factory.configure(addr, 100, -1, false);
        factory.start();
        assertTrue(factory.getParentChannel().isActive());

        factory.reconfigure(addr);

        // wait the state change
        Thread.sleep(100);

        assertTrue(factory.getParentChannel().isActive());
    }

    @Test
    public void testRebindIPv4IPv6() throws Exception {
        int randomPort = PortAssignment.unique();
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", randomPort);
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        factory.configure(addr, 100, -1, false);
        factory.start();
        assertTrue(factory.getParentChannel().isActive());

        factory.reconfigure(new InetSocketAddress("[0:0:0:0:0:0:0:0]", randomPort));

        // wait the state change
        Thread.sleep(100);

        assertTrue(factory.getParentChannel().isActive());
    }

    /*
     * In this test we are flooding the server with SSL connections, and expecting that not
     * all the connection will succeed at once. Some of the connections should be closed,
     * as there is a maximum number of parallel SSL handshake the server is willing to do
     * for security reasons.
     */
    @Test
    public void testOutstandingHandshakeLimit() throws Exception {

        // setting up SSL params, but disable some debug logs
        x509Util = SSLAuthTest.setUpSecure();
        System.clearProperty("javax.net.debug");

        // starting a single server (it will be closed in the tearDown)
        setUpWithServerId(1);

        // initializing the statistics
        SimpleCounter tlsHandshakeExceeded = (SimpleCounter) ServerMetrics.getMetrics().TLS_HANDSHAKE_EXCEEDED;
        tlsHandshakeExceeded.reset();
        assertEquals(tlsHandshakeExceeded.get(), 0);

        // setting the HandshakeLimit to 3, so only 3 SSL handshakes can happen in parallel
        NettyServerCnxnFactory factory = (NettyServerCnxnFactory) serverFactory;
        factory.setSecure(true);
        factory.setOutstandingHandshakeLimit(3);

        // starting the threads that will try to connect to the server
        // we will have 3 threads, each of them establishing 3 connections
        int threadNum = 3;
        int cnxnPerThread = 3;
        int cnxnLimit = threadNum * cnxnPerThread;
        AtomicInteger cnxnCreated = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        Thread[] cnxnWorker = new Thread[threadNum];
        for (int i = 0; i < cnxnWorker.length; i++) {
            cnxnWorker[i] = new ClientConnectionGenerator(i, cnxnPerThread, cnxnCreated, cnxnLimit, latch, zooKeeperClients);
            cnxnWorker[i].start();
        }

        // we might need to wait potentially for a longer time for all the connection to get established,
        // as the ZooKeeper Server will close some of the connections and the clients will have to re-try
        boolean allConnectionsCreatedInTime = latch.await(30, TimeUnit.SECONDS);
        int actualConnections = cnxnCreated.get();
        LOG.info("created {} connections", actualConnections);
        if (!allConnectionsCreatedInTime) {
            fail(String.format("Only %d out of %d connections created!", actualConnections, cnxnLimit));
        }

        // Assert the server refused some of the connections because the handshake limit was reached
        // (throttling should be greater than 0)
        long handshakeThrottledNum = tlsHandshakeExceeded.get();
        LOG.info("TLS_HANDSHAKE_EXCEEDED: {}", handshakeThrottledNum);
        assertThat("The number of handshake throttled should be "
                + "greater than 0", handshakeThrottledNum, Matchers.greaterThan(0L));

        // Assert there is no outstanding handshake anymore, all the clients connected in the end
        int outstandingHandshakeNum = factory.getOutstandingHandshakeNum();
        LOG.info("outstanding handshake is {}", outstandingHandshakeNum);
        assertThat("The outstanding handshake number should be 0 "
                + "after all cnxns established", outstandingHandshakeNum, Matchers.is(0));
    }


    private final class ClientConnectionWatcher implements Watcher {

        private final AtomicInteger cnxnCreated;
        private final int cnxnLimit;
        private final int cnxnThreadId;
        private final int cnxnId;
        private final CountDownLatch latch;

        public ClientConnectionWatcher(AtomicInteger cnxnCreated, int cnxnLimit, int cnxnThreadId,
                                       int cnxnId, CountDownLatch latch) {
            this.cnxnCreated = cnxnCreated;
            this.cnxnLimit = cnxnLimit;
            this.cnxnThreadId = cnxnThreadId;
            this.cnxnId = cnxnId;
            this.latch = latch;
        }

        @Override
        public void process(WatchedEvent event) {
            LOG.info(String.format("WATCHER [thread: %d, cnx:%d] - new event: %s", cnxnThreadId, cnxnId, event.toString()));
            if (event.getState() == Event.KeeperState.SyncConnected) {
              int created = cnxnCreated.addAndGet(1);
              if (created == cnxnLimit) {
                latch.countDown();
              }
            }
        }
    }


    private final class ClientConnectionGenerator extends Thread {

        private final int cnxnThreadId;
        private final int cnxnPerThread;
        private final AtomicInteger cnxnCreated;
        private final int cnxnLimit;
        private final CountDownLatch latch;
        private final LinkedBlockingQueue<ZooKeeper> zks;

        private ClientConnectionGenerator(int cnxnThreadId, int cnxnPerThread,
                                          AtomicInteger cnxnCreated, int cnxnLimit,
                                          CountDownLatch latch,
                                          LinkedBlockingQueue<ZooKeeper> zks) {
            this.cnxnThreadId = cnxnThreadId;
            this.cnxnPerThread = cnxnPerThread;
            this.cnxnCreated = cnxnCreated;
            this.cnxnLimit = cnxnLimit;
            this.latch = latch;
            this.zks = zks;
        }

        @Override
        public void run() {

            for (int j = 0; j < cnxnPerThread; j++) {
                try {
                    zks.add(new ZooKeeper(hostPort, 30000,
                                          new ClientConnectionWatcher(cnxnCreated, cnxnLimit, cnxnThreadId, j, latch)));
                } catch (Exception e) {
                    LOG.info("Error while creating zk client", e);
                }
            }
        }
    }

}
