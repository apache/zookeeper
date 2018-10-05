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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.NettyServerCnxnFactory.CnxnChannelHandler;
import org.apache.zookeeper.test.ClientBase;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * duplicate channel closure. Duplicate closure may result in 
     * indefinite hanging due to netty open issue.
     * 
     * @see <a href="https://issues.jboss.org/browse/NETTY-412">NETTY-412</a>
     */
    @Test(timeout = 30000)
    public void testSendCloseSession() throws Exception {
        Assert.assertTrue(
                "Didn't instantiate ServerCnxnFactory with NettyServerCnxnFactory!",
                serverFactory instanceof NettyServerCnxnFactory);

        NettyServerCnxnFactory nettyServerFactory = (NettyServerCnxnFactory) serverFactory;
        final CountDownLatch channelLatch = new CountDownLatch(1);
        CnxnChannelHandler channelHandler = nettyServerFactory.new CnxnChannelHandler() {
            @Override
            public void channelDisconnected(ChannelHandlerContext ctx,
                    ChannelStateEvent e) throws Exception {
                LOG.info("Recieves channel disconnected event");
                channelLatch.countDown();
            }
        };
        LOG.info("Adding custom channel handler for simulation");
        nettyServerFactory.bootstrap.getPipeline().remove("servercnxnfactory");
        nettyServerFactory.bootstrap.getPipeline().addLast("servercnxnfactory",
                channelHandler);

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
            channelLatch.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
            Assert.assertEquals("Mismatch in number of live connections!", 0,
                    serverFactory.getNumAliveConnections());
        } finally {
            zk.close();
        }
    }
}
