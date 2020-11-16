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

package org.apache.zookeeper.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelPipeline;
import io.netty.util.Attribute;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.BufferStats;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.SSLAuthTest;
import org.apache.zookeeper.test.TestByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test verifies the behavior of NettyServerCnxn which represents a connection
 * from a client to the server.
 */
public class NettyServerCnxnTest extends ClientBase {

    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxnTest.class);

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY, "org.apache.zookeeper.server.NettyServerCnxnFactory");
        NettyServerCnxnFactory.setTestAllocator(TestByteBufAllocator.getInstance());
        super.maxCnxns = 1;
        super.exceptionOnFailedConnect = true;
        super.setUp();
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        NettyServerCnxnFactory.clearTestAllocator();
        TestByteBufAllocator.checkForLeaks();
    }

    /**
     * Test verifies the channel closure - while closing the channel
     * servercnxnfactory should remove all channel references to avoid
     * duplicate channel closure. Duplicate closure may result in indefinite
     * hanging due to netty open issue.
     *
     * @see <a href="https://issues.jboss.org/browse/NETTY-412">NETTY-412</a>
     */
    @Test
    @Timeout(value = 40)
    public void testSendCloseSession() throws Exception {
        assertTrue(serverFactory instanceof NettyServerCnxnFactory, "Didn't instantiate ServerCnxnFactory with NettyServerCnxnFactory!");

        final ZooKeeper zk = createClient();
        final ZooKeeperServer zkServer = serverFactory.getZooKeeperServer();
        final String path = "/a";
        try {
            // make sure zkclient works
            zk.create(path, "test".getBytes(StandardCharsets.UTF_8), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            // set on watch
            assertNotNull(zk.exists(path, true), "Didn't create znode:" + path);
            assertEquals(1, zkServer.getZKDatabase().getDataTree().getWatchCount());
            Iterable<ServerCnxn> connections = serverFactory.getConnections();
            assertEquals(1, serverFactory.getNumAliveConnections(), "Mismatch in number of live connections!");
            for (ServerCnxn serverCnxn : connections) {
                serverCnxn.sendCloseSession();
            }
            LOG.info("Waiting for the channel disconnected event");
            int timeout = 0;
            while (serverFactory.getNumAliveConnections() != 0) {
                Thread.sleep(1000);
                timeout += 1000;
                if (timeout > CONNECTION_TIMEOUT) {
                    fail("The number of live connections should be 0");
                }
            }
            // make sure the watch is removed when the connection closed
            assertEquals(0, zkServer.getZKDatabase().getDataTree().getWatchCount());
        } finally {
            zk.close();
        }
    }

    /**
     * In the {@link #setUp()} routine, the maximum number of connections per IP
     * is set to 1. This tests that if more than one connection is attempted, the
     * connection fails.
     */
    @Test
    @Timeout(value = 40)
    public void testMaxConnectionPerIpSurpased() {
        assertTrue(serverFactory instanceof NettyServerCnxnFactory, "Did not instantiate ServerCnxnFactory with NettyServerCnxnFactory!");
        assertThrows(ProtocolException.class, () -> {
            try (final ZooKeeper zk1 = createClient(); final ZooKeeper zk2 = createClient()) {
            }
        });
    }

    @Test
    public void testClientResponseStatsUpdate() throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient()) {
            BufferStats clientResponseStats = serverFactory.getZooKeeperServer().serverStats().getClientResponseStats();
            assertThat("Last client response size should be initialized with INIT_VALUE", clientResponseStats.getLastBufferSize(), equalTo(BufferStats.INIT_VALUE));

            zk.create("/a", "test".getBytes(StandardCharsets.UTF_8), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            assertThat("Last client response size should be greater than 0 after client request was performed", clientResponseStats.getLastBufferSize(), greaterThan(0));

            byte[] contents = zk.getData("/a", null, null);
            assertArrayEquals("test".getBytes(StandardCharsets.UTF_8), contents, "unexpected data");
        }
    }

    @Test
    public void testNonMTLSLocalConn() throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient()) {
            ServerStats serverStats = serverFactory.getZooKeeperServer().serverStats();
            //2 for local stat connection and this client
            assertEquals(2, serverStats.getNonMTLSLocalConnCount());
            assertEquals(0, serverStats.getNonMTLSRemoteConnCount());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testNonMTLSRemoteConn() throws Exception {
        Channel channel = mock(Channel.class);
        ChannelId id = mock(ChannelId.class);
        ChannelFuture success = mock(ChannelFuture.class);
        ChannelHandlerContext context = mock(ChannelHandlerContext.class);
        ChannelPipeline channelPipeline = mock(ChannelPipeline.class);

        when(context.channel()).thenReturn(channel);
        when(channel.pipeline()).thenReturn(channelPipeline);
        when(success.channel()).thenReturn(channel);
        when(channel.closeFuture()).thenReturn(success);

        InetSocketAddress address = new InetSocketAddress(0);
        when(channel.remoteAddress()).thenReturn(address);
        when(channel.id()).thenReturn(id);
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        LeaderZooKeeperServer zks = mock(LeaderZooKeeperServer.class);
        factory.setZooKeeperServer(zks);
        Attribute atr = mock(Attribute.class);
        Mockito.doReturn(atr).when(channel).attr(
                Mockito.any()
        );
        doNothing().when(atr).set(Mockito.any());

        when(zks.isRunning()).thenReturn(true);

        ServerStats.Provider providerMock = mock(ServerStats.Provider.class);
        when(zks.serverStats()).thenReturn(new ServerStats(providerMock));

        factory.channelHandler.channelActive(context);

        assertEquals(0, zks.serverStats().getNonMTLSLocalConnCount());
        assertEquals(1, zks.serverStats().getNonMTLSRemoteConnCount());
    }

    @Test
    public void testServerSideThrottling() throws IOException, InterruptedException, KeeperException {
        try (ZooKeeper zk = createClient()) {
            BufferStats clientResponseStats = serverFactory.getZooKeeperServer().serverStats().getClientResponseStats();
            assertThat("Last client response size should be initialized with INIT_VALUE", clientResponseStats.getLastBufferSize(), equalTo(BufferStats.INIT_VALUE));

            zk.create("/a", "test".getBytes(StandardCharsets.UTF_8), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            assertThat("Last client response size should be greater than 0 after client request was performed", clientResponseStats.getLastBufferSize(), greaterThan(0));

            for (final ServerCnxn cnxn : serverFactory.cnxns) {
                final NettyServerCnxn nettyCnxn = ((NettyServerCnxn) cnxn);
                // Disable receiving data for all open connections ...
                nettyCnxn.disableRecv();
                // ... then force a throttled read after 1 second (this puts the read into queuedBuffer) ...
                nettyCnxn.getChannel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        nettyCnxn.getChannel().read();
                    }
                }, 1, TimeUnit.SECONDS);

                // ... and finally disable throttling after 2 seconds.
                nettyCnxn.getChannel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        nettyCnxn.enableRecv();
                    }
                }, 2, TimeUnit.SECONDS);
            }

            byte[] contents = zk.getData("/a", null, null);
            assertArrayEquals("test".getBytes(StandardCharsets.UTF_8), contents, "unexpected data");

            // As above, but don't do the throttled read. Make the request bytes wait in the socket
            // input buffer until after throttling is turned off. Need to make sure both modes work.
            for (final ServerCnxn cnxn : serverFactory.cnxns) {
                final NettyServerCnxn nettyCnxn = ((NettyServerCnxn) cnxn);
                // Disable receiving data for all open connections ...
                nettyCnxn.disableRecv();
                // ... then disable throttling after 2 seconds.
                nettyCnxn.getChannel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        nettyCnxn.enableRecv();
                    }
                }, 2, TimeUnit.SECONDS);
            }

            contents = zk.getData("/a", null, null);
            assertArrayEquals("test".getBytes(StandardCharsets.UTF_8), contents, "unexpected data");
        }
    }

    @Test
    public void testEnableDisableThrottling_secure_random() throws Exception {
        runEnableDisableThrottling(true, true);
    }

    @Test
    public void testEnableDisableThrottling_secure_sequentially() throws Exception {
        runEnableDisableThrottling(true, false);
    }

    @Test
    public void testEnableDisableThrottling_nonSecure_random() throws Exception {
        runEnableDisableThrottling(false, true);
    }

    @Test
    public void testEnableDisableThrottling_nonSecure_sequentially() throws Exception {
        runEnableDisableThrottling(false, false);
    }

    private void runEnableDisableThrottling(boolean secure, boolean randomDisableEnable) throws Exception {
        ClientX509Util x509Util = null;
        if (secure) {
            x509Util = SSLAuthTest.setUpSecure();
        }
        try {
            NettyServerCnxnFactory factory = (NettyServerCnxnFactory) serverFactory;
            factory.setAdvancedFlowControlEnabled(true);
            if (secure) {
                factory.setSecure(true);
            }

            final String path = "/testEnableDisableThrottling";
            try (ZooKeeper zk = createClient()) {
                zk.create(path, new byte[1], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

                // meanwhile start another thread to enable and disable recv
                AtomicBoolean stopped = new AtomicBoolean(false);
                Random random = new Random();

                Thread enableDisableThread = null;
                if (randomDisableEnable) {
                    enableDisableThread = new Thread() {
                        @Override
                        public void run() {
                            while (!stopped.get()) {
                                for (final ServerCnxn cnxn : serverFactory.cnxns) {
                                    boolean shouldDisableEnable = random.nextBoolean();
                                    if (shouldDisableEnable) {
                                        cnxn.disableRecv();
                                    } else {
                                        cnxn.enableRecv();
                                    }
                                }
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException e) { /* ignore */ }
                            }
                            // always enable the recv at end
                            for (final ServerCnxn cnxn : serverFactory.cnxns) {
                                cnxn.enableRecv();
                            }
                        }
                    };
                } else {
                    enableDisableThread = new Thread() {
                        @Override
                        public void run() {
                            while (!stopped.get()) {
                                for (final ServerCnxn cnxn : serverFactory.cnxns) {
                                    try {
                                        cnxn.disableRecv();
                                        Thread.sleep(10);
                                        cnxn.enableRecv();
                                        Thread.sleep(10);
                                    } catch (InterruptedException e) { /* ignore */ }
                                }
                            }
                        }
                    };
                }
                enableDisableThread.start();
                LOG.info("started thread to enable and disable recv");

                // start a thread to keep sending requests
                int totalRequestsNum = 100000;
                AtomicInteger successResponse = new AtomicInteger();
                CountDownLatch responseReceivedLatch = new CountDownLatch(totalRequestsNum);
                Thread clientThread = new Thread() {
                    @Override
                    public void run() {
                        int requestIssued = 0;
                        while (requestIssued++ < totalRequestsNum) {
                            zk.getData(path, null, new DataCallback() {
                                @Override
                                public void processResult(int rc, String path, Object ctx, byte[] data, Stat stat) {
                                    if (rc == KeeperException.Code.OK.intValue()) {
                                        successResponse.addAndGet(1);
                                    } else {
                                        LOG.info("failed response is {}", rc);
                                    }
                                    responseReceivedLatch.countDown();
                                }
                            }, null);
                        }
                    }
                };
                clientThread.start();
                LOG.info("started thread to issue {} async requests", totalRequestsNum);

                // and verify the response received is same as what we issued
                assertTrue(responseReceivedLatch.await(60, TimeUnit.SECONDS));
                LOG.info("received all {} responses", totalRequestsNum);

                stopped.set(true);
                enableDisableThread.join();
                LOG.info("enable and disable recv thread exited");

                // wait another second for the left requests to finish
                LOG.info("waiting another 1s for the requests to go through");
                Thread.sleep(1000);
                assertEquals(successResponse.get(), totalRequestsNum);
            }
        } finally {
            if (secure) {
                SSLAuthTest.clearSecureSetting(x509Util);
            }
        }
    }

}
