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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.NettyUtils;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerCnxnFactory extends ServerCnxnFactory {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxnFactory.class);

    private final ServerBootstrap bootstrap;
    private Channel parentChannel;
    private final ChannelGroup allChannels =
            new DefaultChannelGroup("zkServerCnxns", new DefaultEventExecutor());
    // Access to ipMap or to any Set contained in the map needs to be
    // protected with synchronized (ipMap) { ... }
    private final Map<InetAddress, Set<NettyServerCnxn>> ipMap = new HashMap<>();
    private InetSocketAddress localAddress;
    private int maxClientCnxns = 60;
    int listenBacklog = -1;
    private final ClientX509Util x509Util;

    private static final AttributeKey<NettyServerCnxn> CONNECTION_ATTRIBUTE =
            AttributeKey.valueOf("NettyServerCnxn");

    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR =
            new AtomicReference<>(null);

    /**
     * This is an inner class since we need to extend ChannelDuplexHandler, but
     * NettyServerCnxnFactory already extends ServerCnxnFactory. By making it inner
     * this class gets access to the member variables and methods.
     */
    @Sharable
    class CnxnChannelHandler extends ChannelDuplexHandler {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel active {}", ctx.channel());
            }

            final Channel channel = ctx.channel();
            InetAddress addr = ((InetSocketAddress) channel.remoteAddress())
                    .getAddress();
            if (maxClientCnxns > 0 && getClientCnxnCount(addr) >= maxClientCnxns) {
                ServerMetrics.CONNECTION_REJECTED.add(1);
                LOG.warn("Too many connections from {} - max is {}", addr,
                        maxClientCnxns);
                channel.close();
                return;
            }

            NettyServerCnxn cnxn = new NettyServerCnxn(channel,
                    zkServer, NettyServerCnxnFactory.this);
            ctx.channel().attr(CONNECTION_ATTRIBUTE).set(cnxn);

            if (secure) {
                SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
                Future<Channel> handshakeFuture = sslHandler.handshakeFuture();
                handshakeFuture.addListener(new CertificateVerifier(sslHandler, cnxn));
            } else {
                allChannels.add(ctx.channel());
                addCnxn(cnxn);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel inactive {}", ctx.channel());
            }
            allChannels.remove(ctx.channel());
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).getAndSet(null);
            if (cnxn != null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Channel inactive caused close {}", cnxn);
                }
                cnxn.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.warn("Exception caught", cause);
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).getAndSet(null);
            if (cnxn != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Closing {}", cnxn);
                }
                cnxn.close();
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            try {
                if (evt == NettyServerCnxn.AutoReadEvent.ENABLE) {
                    LOG.debug("Received AutoReadEvent.ENABLE");
                    NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
                    // TODO(ilyam): Not sure if cnxn can be null here. It becomes null if channelInactive()
                    // or exceptionCaught() trigger, but it's unclear to me if userEventTriggered() can run
                    // after either of those. Check for null just to be safe ...
                    if (cnxn != null) {
                        cnxn.processQueuedBuffer();
                    }
                    ctx.channel().config().setAutoRead(true);
                } else if (evt == NettyServerCnxn.AutoReadEvent.DISABLE) {
                    LOG.debug("Received AutoReadEvent.DISABLE");
                    ctx.channel().config().setAutoRead(false);
                }
            } finally {
                ReferenceCountUtil.release(evt);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("message received called {}", msg);
                }
                try {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("New message {} from {}", msg, ctx.channel());
                    }
                    NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
                    if (cnxn == null) {
                        LOG.error("channelRead() on a closed or closing NettyServerCnxn");
                    } else {
                        cnxn.processMessage((ByteBuf) msg);
                    }
                } catch (Exception ex) {
                    LOG.error("Unexpected exception in receive", ex);
                    throw ex;
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        // Use a single listener instance to reduce GC
        // Note: this listener is only added when LOG.isTraceEnabled() is true,
        // so it should not do any work other than trace logging.
        private final GenericFutureListener<Future<Void>> onWriteCompletedTracer = (f) -> {
            LOG.trace("write {}", f.isSuccess() ? "complete" : "failed");
        };

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (LOG.isTraceEnabled()) {
                promise.addListener(onWriteCompletedTracer);
            }
            super.write(ctx, msg, promise);
        }

        private final class CertificateVerifier implements GenericFutureListener<Future<Channel>> {
            private final SslHandler sslHandler;
            private final NettyServerCnxn cnxn;

            CertificateVerifier(SslHandler sslHandler, NettyServerCnxn cnxn) {
                this.sslHandler = sslHandler;
                this.cnxn = cnxn;
            }

            /**
             * Only allow the connection to stay open if certificate passes auth
             */
            public void operationComplete(Future<Channel> future) throws SSLPeerUnverifiedException {
                if (future.isSuccess()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Successful handshake with session 0x{}",
                                Long.toHexString(cnxn.getSessionId()));
                    }
                    SSLEngine eng = sslHandler.engine();
                    SSLSession session = eng.getSession();
                    cnxn.setClientCertificateChain(session.getPeerCertificates());

                    String authProviderProp
                            = System.getProperty(x509Util.getSslAuthProviderProperty(), "x509");

                    X509AuthenticationProvider authProvider =
                            (X509AuthenticationProvider)
                                    ProviderRegistry.getProvider(authProviderProp);

                    if (authProvider == null) {
                        LOG.error("Auth provider not found: {}", authProviderProp);
                        cnxn.close();
                        return;
                    }

                    if (KeeperException.Code.OK !=
                            authProvider.handleAuthentication(cnxn, null)) {
                        LOG.error("Authentication failed for session 0x{}",
                                Long.toHexString(cnxn.getSessionId()));
                        cnxn.close();
                        return;
                    }

                    final Channel futureChannel = future.getNow();
                    allChannels.add(Objects.requireNonNull(futureChannel));
                    addCnxn(cnxn);
                } else {
                    LOG.error("Unsuccessful handshake with session 0x{}",
                            Long.toHexString(cnxn.getSessionId()));
                    cnxn.close();
                }
            }
        }
    }
    
    CnxnChannelHandler channelHandler = new CnxnChannelHandler();

    private ServerBootstrap configureBootstrapAllocator(ServerBootstrap bootstrap) {
        ByteBufAllocator testAllocator = TEST_ALLOCATOR.get();
        if (testAllocator != null) {
            return bootstrap
                    .option(ChannelOption.ALLOCATOR, testAllocator)
                    .childOption(ChannelOption.ALLOCATOR, testAllocator);
        } else {
            return bootstrap;
        }
    }

    NettyServerCnxnFactory() {
        x509Util = new ClientX509Util();

        EventLoopGroup bossGroup = NettyUtils.newNioOrEpollEventLoopGroup(
                NettyUtils.getClientReachableLocalInetAddressCount());
        EventLoopGroup workerGroup = NettyUtils.newNioOrEpollEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NettyUtils.nioOrEpollServerSocketChannel())
                // parent channel options
                .option(ChannelOption.SO_REUSEADDR, true)
                // child channels options
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_LINGER, -1)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (secure) {
                            initSSL(pipeline);
                        }
                        pipeline.addLast("servercnxnfactory", channelHandler);
                    }
                });
        this.bootstrap = configureBootstrapAllocator(bootstrap);
        this.bootstrap.validate();
    }

    private synchronized void initSSL(ChannelPipeline p)
            throws X509Exception, KeyManagementException, NoSuchAlgorithmException {
        String authProviderProp = System.getProperty(x509Util.getSslAuthProviderProperty());
        SSLContext sslContext;
        if (authProviderProp == null) {
            sslContext = x509Util.getDefaultSSLContext();
        } else {
            sslContext = SSLContext.getInstance("TLSv1");
            X509AuthenticationProvider authProvider =
                    (X509AuthenticationProvider)ProviderRegistry.getProvider(
                            System.getProperty(x509Util.getSslAuthProviderProperty(), "x509"));

            if (authProvider == null)
            {
                LOG.error("Auth provider not found: {}", authProviderProp);
                throw new SSLContextException(
                        "Could not create SSLContext with specified auth provider: " +
                        authProviderProp);
            }

            sslContext.init(new X509KeyManager[] { authProvider.getKeyManager() },
                            new X509TrustManager[] { authProvider.getTrustManager() },
                            null);
        }

        SSLEngine sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(false);
        sslEngine.setNeedClientAuth(true);

        p.addLast("ssl", new SslHandler(sslEngine));
        LOG.info("SSL handler added for channel: {}", p.channel());
    }

    @Override
    public void closeAll() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closeAll()");
        }
        // clear all the connections on which we are selecting
        int length = cnxns.size();
        for (ServerCnxn cnxn : cnxns) {
            try {
                // This will remove the cnxn from cnxns
                cnxn.close();
            } catch (Exception e) {
                LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                         + Long.toHexString(cnxn.getSessionId()), e);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("allChannels size:" + allChannels.size() + " cnxns size:"
                    + length);
        }
    }

    @Override
    public void configure(InetSocketAddress addr, int maxClientCnxns, int backlog, boolean secure)
            throws IOException
    {
        configureSaslLogin();
        localAddress = addr;
        this.maxClientCnxns = maxClientCnxns;
        this.secure = secure;
        this.listenBacklog = backlog;
    }

    /** {@inheritDoc} */
    public int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /** {@inheritDoc} */
    public void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
    }

    /** {@inheritDoc} */
    public int getSocketListenBacklog() {
        return listenBacklog;
    }

    @Override
    public int getLocalPort() {
        return localAddress.getPort();
    }

    private boolean killed; // use synchronized(this) to access
    @Override
    public void join() throws InterruptedException {
        synchronized(this) {
            while(!killed) {
                wait();
            }
        }
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            if (killed) {
                LOG.info("already shutdown {}", localAddress);
                return;
            }
        }
        LOG.info("shutdown called {}", localAddress);

        x509Util.close();

        if (login != null) {
            login.shutdown();
        }

        final EventLoopGroup bossGroup = bootstrap.config().group();
        final EventLoopGroup workerGroup = bootstrap.config().childGroup();
        // null if factory never started
        if (parentChannel != null) {
            ChannelFuture parentCloseFuture = parentChannel.close();
            if (bossGroup != null) {
                parentCloseFuture.addListener(future -> {
                    bossGroup.shutdownGracefully();
                });
            }
            closeAll();
            ChannelGroupFuture allChannelsCloseFuture = allChannels.close();
            if (workerGroup != null) {
                allChannelsCloseFuture.addListener(future -> {
                    workerGroup.shutdownGracefully();
                });
            }
        } else {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
        }

        if (zkServer != null) {
            zkServer.shutdown();
        }
        synchronized(this) {
            killed = true;
            notifyAll();
        }
    }
    
    @Override
    public void start() {
        if (listenBacklog != -1) {
            bootstrap.option(ChannelOption.SO_BACKLOG, listenBacklog);
        }
        LOG.info("binding to port {}", localAddress);
        parentChannel = bootstrap.bind(localAddress).syncUninterruptibly().channel();
        // Port changes after bind() if the original port was 0, update
        // localAddress to get the real port.
        localAddress = (InetSocketAddress) parentChannel.localAddress();
        LOG.info("bound to port " + getLocalPort());
    }
    
    public void reconfigure(InetSocketAddress addr) {
       Channel oldChannel = parentChannel;
       try {
           LOG.info("binding to port {}", addr);
           parentChannel = bootstrap.bind(addr).syncUninterruptibly().channel();
           // Port changes after bind() if the original port was 0, update
           // localAddress to get the real port.
           localAddress = (InetSocketAddress) parentChannel.localAddress();
           LOG.info("bound to port " + getLocalPort());
       } catch (Exception e) {
           LOG.error("Error while reconfiguring", e);
       } finally {
           oldChannel.close();
       }
    }
    
    @Override
    public void startup(ZooKeeperServer zks, boolean startServer)
            throws IOException, InterruptedException {
        start();
        setZooKeeperServer(zks);
        if (startServer) {
            zks.startdata();
            zks.startup();
        }
    }

    @Override
    public Iterable<ServerCnxn> getConnections() {
        return cnxns;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    private void addCnxn(NettyServerCnxn cnxn) {
        cnxns.add(cnxn);
        synchronized (ipMap){
            InetAddress addr =
                ((InetSocketAddress)cnxn.getChannel().remoteAddress()).getAddress();
            Set<NettyServerCnxn> s = ipMap.get(addr);
            if (s == null) {
                s = new HashSet<>();
                ipMap.put(addr, s);
            }
            s.add(cnxn);
        }
    }

    void removeCnxnFromIpMap(NettyServerCnxn cnxn, InetAddress remoteAddress) {
        synchronized (ipMap) {
            Set<NettyServerCnxn> s = ipMap.get(remoteAddress);
            if (s != null) {
                s.remove(cnxn);
                if (s.isEmpty()) {
                    ipMap.remove(remoteAddress);
                }
                return;
            }
        }
        // Fallthrough and log errors outside the synchronized block
        LOG.error(
                "Unexpected null set for remote address {} when removing cnxn {}",
                remoteAddress,
                cnxn);
    }

    private int getClientCnxnCount(InetAddress addr) {
        synchronized (ipMap) {
            Set<NettyServerCnxn> s = ipMap.get(addr);
            if (s == null) return 0;
            return s.size();
        }
    }

    @Override
    public void resetAllConnectionStats() {
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for(ServerCnxn c : cnxns){
            c.resetStats();
        }
    }

    @Override
    public Iterable<Map<String, Object>> getAllConnectionInfo(boolean brief) {
        Set<Map<String,Object>> info = new HashSet<Map<String,Object>>();
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for (ServerCnxn c : cnxns) {
            info.add(c.getConnectionInfo(brief));
        }
        return info;
    }

    /**
     * Sets the test ByteBufAllocator. This allocator will be used by all
     * future instances of this class.
     * It is not recommended to use this method outside of testing.
     * @param allocator the ByteBufAllocator to use for all netty buffer
     *                  allocations.
     */
    static void setTestAllocator(ByteBufAllocator allocator) {
        TEST_ALLOCATOR.set(allocator);
    }

    /**
     * Clears the test ByteBufAllocator. The default allocator will be used
     * by all future instances of this class.
     * It is not recommended to use this method outside of testing.
     */
    static void clearTestAllocator() {
        TEST_ALLOCATOR.set(null);
    }
}
