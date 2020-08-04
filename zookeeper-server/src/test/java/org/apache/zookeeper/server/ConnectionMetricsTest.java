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

import static org.apache.zookeeper.server.NIOServerCnxnFactory.ZOOKEEPER_NIO_SESSIONLESS_CNXN_TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.metrics.MetricsUtils;
import org.apache.zookeeper.test.ClientBase;
import org.apache.zookeeper.test.QuorumUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionMetricsTest extends ZKTestCase {

    protected static final Logger LOG = LoggerFactory.getLogger(ConnectionMetricsTest.class);

    @Test
    public void testRevalidateCount() throws Exception {
        ServerMetrics.getMetrics().resetAll();
        QuorumUtil util = new QuorumUtil(1); // create a quorum of 3 servers
        // disable local session to make sure we create a global session
        util.enableLocalSession(false);
        util.startAll();

        int follower1 = (int) util.getFollowerQuorumPeers().get(0).getId();
        int follower2 = (int) util.getFollowerQuorumPeers().get(1).getId();
        LOG.info("connecting to server: {}", follower1);
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        // create a connection to follower
        ZooKeeper zk = new ZooKeeper(util.getConnectionStringForServer(follower1), ClientBase.CONNECTION_TIMEOUT, watcher);
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        LOG.info("connected");

        // update the connection to allow to connect to the other follower
        zk.updateServerList(util.getConnectionStringForServer(follower2));

        // follower is shut down and zk should be disconnected
        util.shutdown(follower1);
        watcher.waitForDisconnected(ClientBase.CONNECTION_TIMEOUT);
        LOG.info("disconnected");
        // should reconnect to another follower, will ask leader to revalidate
        watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        LOG.info("reconnected");

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("connection_revalidate_count"));
        assertEquals(1L, values.get("revalidate_count"));

        zk.close();
        util.shutdownAll();
    }

    private class MockNIOServerCnxn extends NIOServerCnxn {

        public MockNIOServerCnxn(ZooKeeperServer zk, SocketChannel sock, SelectionKey sk, NIOServerCnxnFactory factory, NIOServerCnxnFactory.SelectorThread selectorThread) throws IOException {
            super(zk, sock, sk, factory, selectorThread);
        }

        @Override
        protected boolean isSocketOpen() {
            return true;
        }

    }

    private static class FakeSK extends SelectionKey {

        @Override
        public SelectableChannel channel() {
            return null;
        }

        @Override
        public Selector selector() {
            return mock(Selector.class);
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public int interestOps() {
            return ops;
        }

        private int ops = OP_WRITE + OP_READ;

        @Override
        public SelectionKey interestOps(int ops) {
            this.ops = ops;
            return this;
        }

        @Override
        public int readyOps() {
            return ops;
        }

    }

    private NIOServerCnxn createMockNIOCnxn() throws IOException {
        InetSocketAddress socketAddr = new InetSocketAddress(80);
        Socket socket = mock(Socket.class);
        when(socket.getRemoteSocketAddress()).thenReturn(socketAddr);
        SocketChannel sock = mock(SocketChannel.class);
        when(sock.socket()).thenReturn(socket);
        when(sock.read(any(ByteBuffer.class))).thenReturn(-1);

        return new MockNIOServerCnxn(mock(ZooKeeperServer.class), sock, null, mock(NIOServerCnxnFactory.class), null);
    }

    @Test
    public void testNIOConnectionDropCount() throws Exception {
        ServerMetrics.getMetrics().resetAll();

        NIOServerCnxn cnxn = createMockNIOCnxn();
        cnxn.doIO(new FakeSK());

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("connection_drop_count"));
    }

    @Test
    public void testNettyConnectionDropCount() throws Exception {
        InetSocketAddress socketAddr = new InetSocketAddress(80);
        Channel channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(false);
        when(channel.remoteAddress()).thenReturn(socketAddr);
        EventLoop eventLoop = mock(EventLoop.class);
        when(channel.eventLoop()).thenReturn(eventLoop);

        ServerMetrics.getMetrics().resetAll();

        NettyServerCnxnFactory factory = new NettyServerCnxnFactory();
        NettyServerCnxn cnxn = new NettyServerCnxn(channel, mock(ZooKeeperServer.class), factory);

        // pretend it's connected
        factory.cnxns.add(cnxn);
        cnxn.close();

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        assertEquals(1L, values.get("connection_drop_count"));
    }

    @Test
    public void testSessionlessConnectionsExpired() throws Exception {
        ServerCnxnFactory factory = new NIOServerCnxnFactory();
        factory.configure(new InetSocketAddress(PortAssignment.unique()), 1000);
        factory.start();
        int timeout = Integer.getInteger(ZOOKEEPER_NIO_SESSIONLESS_CNXN_TIMEOUT, 10000);

        ServerMetrics.getMetrics().resetAll();
        // add two connections w/o touching them so they will expire
        ((NIOServerCnxnFactory) factory).touchCnxn(createMockNIOCnxn());
        ((NIOServerCnxnFactory) factory).touchCnxn(createMockNIOCnxn());

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        int sleptTime = 0;
        while (values.get("sessionless_connections_expired") == null || sleptTime < 2 * timeout) {
            Thread.sleep(100);
            sleptTime += 100;
            values = MetricsUtils.currentServerMetrics();
        }

        assertEquals(2L, values.get("sessionless_connections_expired"));

        factory.shutdown();
    }

    @Test
    public void testStaleSessionsExpired() throws Exception {
        int tickTime = 1000;
        SessionTrackerImpl tracker = new SessionTrackerImpl(mock(ZooKeeperServer.class), new ConcurrentHashMap<>(), tickTime, 1L, null);

        tracker.sessionsById.put(1L, mock(SessionTrackerImpl.SessionImpl.class));
        tracker.sessionsById.put(2L, mock(SessionTrackerImpl.SessionImpl.class));

        tracker.touchSession(1L, tickTime);
        tracker.touchSession(2L, tickTime);

        ServerMetrics.getMetrics().resetAll();

        tracker.start();

        Map<String, Object> values = MetricsUtils.currentServerMetrics();
        int sleptTime = 0;
        while (values.get("stale_sessions_expired") == null || sleptTime < 2 * tickTime) {
            Thread.sleep(100);
            sleptTime += 100;
            values = MetricsUtils.currentServerMetrics();
        }

        assertEquals(2L, values.get("stale_sessions_expired"));

        tracker.shutdown();
    }

}
