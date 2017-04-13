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
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.zookeeper.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.ClientCnxnSocketNetty;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.SocketAddressUtils;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.quorum.QuorumPeerTestBase;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ServerChannelFactory;
import org.jboss.netty.channel.local.DefaultLocalClientChannelFactory;
import org.jboss.netty.channel.local.DefaultLocalServerChannelFactory;
import org.jboss.netty.channel.local.LocalAddress;

import org.junit.AfterClass;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example of using ZooKeeper for internal testing without a real network.
 */
public class NetworklessTest extends QuorumPeerTestBase {

    @BeforeClass
    public static void setUp() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
            LocalNettyChannelServerCnxnFactory.class.getName());
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET,
            LocalClientCnxnSocketNetty.class.getName());
        System.setProperty("zookeeper.admin.enableServer", "false");
    }

    @AfterClass
    public static void tearDown() {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
    }

    @Test
    public void testLocalStandaloneServer() throws Exception {
        int clientPort = PortAssignment.unique();
        MainThread mt = new MainThread(MainThread.UNSET_MYID, clientPort, "");
        mt.start();

        assertTrue("ServerCnxnFactory did not start in time",
            LocalNettyChannelServerCnxnFactory.listening.await(1, TimeUnit.MINUTES));
        ZooKeeper zk = ClientBase.createZKClient("127.0.0.1:" + clientPort, 60000);
        zk.create("/test", "".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.delete("/test", -1);

        zk.close();
        mt.shutdown();
    }

    /**
     * Example of ClientCnxnSocketNetty which connects ONLY to the Local JVM
     */
    public static final class LocalClientCnxnSocketNetty extends ClientCnxnSocketNetty {

        private static final Logger LOG = LoggerFactory.getLogger(LocalClientCnxnSocketNetty.class);

        public LocalClientCnxnSocketNetty(ZKClientConfig clientConfig) throws IOException {
            super(clientConfig);
        }

        @Override
        protected ChannelFactory buildChannelFactory() {
            return new DefaultLocalClientChannelFactory();
        }

        @Override
        protected ChannelFuture connect(ClientBootstrap bootstrap, InetSocketAddress addr) {
            LocalAddress localAddress = SocketAddressUtils.mapToLocalAddress(addr);
            LOG.info("connect to local " + localAddress + " remote was " + addr);
            return bootstrap.connect(localAddress);
        }
    }

    /**
     * Example of NettyServerCnxnFactory which listens only the local Netty network.
     */
    public static final class LocalNettyChannelServerCnxnFactory extends NettyServerCnxnFactory {

        static CountDownLatch listening = new CountDownLatch(1);

        @Override
        protected ServerChannelFactory buildSocketChannelFactory() {
            return new DefaultLocalServerChannelFactory();
        }

        public LocalNettyChannelServerCnxnFactory() {
        }

        @Override
        public void configure(InetSocketAddress addr, int maxClientCnxns, boolean secure) throws IOException {
            super.configure(addr, maxClientCnxns, secure);
            localAddress = SocketAddressUtils.mapToLocalAddress(addr);
        }

        @Override
        public void start() {
            super.start();
            listening.countDown();
        }

    }
}
