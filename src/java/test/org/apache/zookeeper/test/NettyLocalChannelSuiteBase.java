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

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.zookeeper.ClientCnxnSocketNetty;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.SocketAddressUtils;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ServerChannelFactory;
import org.jboss.netty.channel.local.DefaultLocalClientChannelFactory;
import org.jboss.netty.channel.local.DefaultLocalServerChannelFactory;
import org.jboss.netty.channel.local.LocalAddress;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run tests with: Netty Client against Netty server
 */
@RunWith(Suite.class)
public class NettyLocalChannelSuiteBase {

    @BeforeClass
    public static void setUp() {
        System.setProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY,
            LocalAndRealNetworkNettyChannelServerCnxnFactory.class.getName());
        System.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET,
            LocalClientCnxnSocketNetty.class.getName());
        System.setProperty("zookeeper.admin.enableServer", "false");
    }

    @AfterClass
    public static void tearDown() {
        System.clearProperty(ServerCnxnFactory.ZOOKEEPER_SERVER_CNXN_FACTORY);
        System.clearProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET);
    }

    /**
     * Example of NettyServerCnxnFactory which listens on the real network and on the local Netty network. We have to
     * listen to real network in order to leverage existing tests.
     * See {@link NetworklessTest} in order to look a pure networkless implementation
     */
    public static final class LocalAndRealNetworkNettyChannelServerCnxnFactory extends NettyServerCnxnFactory {

        private static final Logger LOG = LoggerFactory.getLogger(LocalAndRealNetworkNettyChannelServerCnxnFactory.class);

        private final ServerBootstrap localChannelBootstrap;
        Channel localParentChannel;
        LocalAddress localLocalAddress;

        public LocalAndRealNetworkNettyChannelServerCnxnFactory() {
            super();

            localChannelBootstrap = new ServerBootstrap(
                new DefaultLocalServerChannelFactory()
            );
            initServerBootStrap(localChannelBootstrap);
        }

        @Override
        public void configure(InetSocketAddress addr, int maxClientCnxns, boolean secure) throws IOException {
            super.configure(addr, maxClientCnxns, secure);
            localLocalAddress = SocketAddressUtils.mapToLocalAddress(addr);
        }

        @Override
        public void start() {
            super.start();
            localParentChannel = localChannelBootstrap.bind(localLocalAddress);
            LOG.info("binding to local port " + localLocalAddress);
        }

        @Override
        public void shutdown() {
            super.shutdown();

            if (localParentChannel != null) {
                localParentChannel.close().awaitUninterruptibly();
                localChannelBootstrap.releaseExternalResources();
            }
        }

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

}
