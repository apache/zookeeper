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
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class NettyServerCnxnFactoryTest extends ClientBase {

    private static final Logger LOG = LoggerFactory
            .getLogger(NettyServerCnxnFactoryTest.class);

    final LinkedBlockingQueue<ZooKeeper> zks = new LinkedBlockingQueue<ZooKeeper>();

    @Override
    public void setUp() throws Exception {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
                "org.apache.zookeeper.server.NettyServerCnxnFactory");
        super.setUp();
    }

    @Override
    public void tearDown() throws Exception {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);

        // clean up
        for (ZooKeeper zk : zks) {
            zk.close();
        }
        super.tearDown();
    }

    @Test
    public void testRebind() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(PortAssignment.unique());
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        factory.configure(addr, 100, -1, false);
        factory.start();
        Assert.assertTrue(factory.getParentChannel().isActive());

        factory.reconfigure(addr);

        // wait the state change
        Thread.sleep(100);

        Assert.assertTrue(factory.getParentChannel().isActive());
    }

    @Test
    public void testRebindIPv4IPv6() throws Exception {
        int randomPort = PortAssignment.unique();
        InetSocketAddress addr = new InetSocketAddress("0.0.0.0", randomPort);
        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        factory.configure(addr, 100, -1, false);
        factory.start();
        Assert.assertTrue(factory.getParentChannel().isActive());

        factory.reconfigure(new InetSocketAddress("[0:0:0:0:0:0:0:0]", randomPort));

        // wait the state change
        Thread.sleep(100);

        Assert.assertTrue(factory.getParentChannel().isActive());
    }

    @Test
    public void testOutstandingHandshakeLimit() throws Exception {

        SimpleCounter tlsHandshakeExceeded = (SimpleCounter) ServerMetrics.getMetrics().TLS_HANDSHAKE_EXCEEDED;
        tlsHandshakeExceeded.reset();
        Assert.assertEquals(tlsHandshakeExceeded.get(), 0);

        ClientX509Util x509Util = SSLAuthTest.setUpSecure();
        NettyServerCnxnFactory factory = (NettyServerCnxnFactory) serverFactory;
        factory.setSecure(true);
        factory.setOutstandingHandshakeLimit(10);

        int threadNum = 3;
        int cnxnPerThread = 10;
        Thread[] cnxnWorker = new Thread[threadNum];

        AtomicInteger cnxnCreated = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < cnxnWorker.length; i++) {
            cnxnWorker[i] = new Thread() {
                @Override
                public void run() {
                    for (int i = 0; i < cnxnPerThread; i++) {
                        try {
                            zks.add(new ZooKeeper(hostPort, 3000, new Watcher() {
                                @Override
                                public void process(WatchedEvent event) {
                                    int created = cnxnCreated.addAndGet(1);
                                    if (created == threadNum * cnxnPerThread) {
                                        latch.countDown();
                                    }
                                }
                            }));
                        } catch (Exception e) {
                            LOG.info("Error while creating zk client", e);
                        }
                    }
                }
            };
            cnxnWorker[i].start();
        }

        Assert.assertThat(latch.await(3, TimeUnit.SECONDS), Matchers.is(true));
        LOG.info("created {} connections", threadNum * cnxnPerThread);

        // Assert throttling not 0
        long handshakeThrottledNum = tlsHandshakeExceeded.get();
        LOG.info("TLS_HANDSHAKE_EXCEEDED: {}", handshakeThrottledNum);
        Assert.assertThat("The number of handshake throttled should be "
                + "greater than 0", handshakeThrottledNum, Matchers.greaterThan(0L));

        // Assert there is no outstanding handshake anymore
        int outstandingHandshakeNum = factory.getOutstandingHandshakeNum();
        LOG.info("outstanding handshake is {}", outstandingHandshakeNum);
        Assert.assertThat("The outstanding handshake number should be 0 "
                + "after all cnxns established", outstandingHandshakeNum, Matchers.is(0));

    }
}
