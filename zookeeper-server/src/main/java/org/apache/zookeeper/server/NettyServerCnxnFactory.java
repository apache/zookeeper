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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
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
import io.netty.channel.ChannelHandler;
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
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.NettyUtils;
import org.apache.zookeeper.common.SSLContextAndOptions;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerCnxnFactory extends ServerCnxnFactory {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxnFactory.class);

    /**
     * Allow client-server sockets to accept both SSL and plaintext connections
     */
    public static final String PORT_UNIFICATION_KEY = "zookeeper.client.portUnification";
    private final boolean shouldUsePortUnification;

    /**
     * The first byte in TLS protocol is the content type of the subsequent record.
     * Handshakes use value 22 (0x16) so the first byte offered on any TCP connection
     * attempting to establish a TLS connection will be this value.
     * https://tools.ietf.org/html/rfc8446#page-79
     */
    private static final byte TLS_HANDSHAKE_RECORD_TYPE = 0x16;

    private final ServerBootstrap bootstrap;
    private Channel parentChannel;
    private final ChannelGroup allChannels =
            new DefaultChannelGroup("zkServerCnxns", new DefaultEventExecutor());
    // Access to ipMap or to any Set contained in the map needs to be
    // protected with synchronized (ipMap) { ... }
    private final Map<InetAddress, Set<NettyServerCnxn>> ipMap = new HashMap<>();
    private InetSocketAddress localAddress;
    private int maxClientCnxns = 60;
    private final ClientX509Util x509Util;

    private static final AttributeKey<NettyServerCnxn> CONNECTION_ATTRIBUTE =
            AttributeKey.valueOf("NettyServerCnxn");

    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR =
            new AtomicReference<>(null);

    /**
     * A handler that detects whether the client would like to use
     * TLS or not and responds in kind. The first bytes are examined
     * for the static TLS headers to make the determination and
     * placed back in the stream with the correct ChannelHandler
     * instantiated.
     */
    class DualModeSslHandler extends OptionalSslHandler {
        DualModeSslHandler(SslContext sslContext) {
            super(sslContext);
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) throws Exception {
            if (in.readableBytes() >= 5) {
                super.decode(context, in, out);
            } else if (in.readableBytes() > 0) {
                // It requires 5 bytes to detect a proper ssl connection. In the
                // case that the server receives fewer, check if we can fail to plaintext.
                // This will occur when for any four letter work commands.
                if (TLS_HANDSHAKE_RECORD_TYPE != in.getByte(0)) {
                    LOG.debug("first byte {} does not match TLS handshake, failing to plaintext", in.getByte(0));
                    handleNonSsl(context);
                }
            }
        }

        /**
         * pulled directly from OptionalSslHandler to allow for access
         * @param context
         */
        private void handleNonSsl(ChannelHandlerContext context) {
            ChannelHandler handler = this.newNonSslHandler(context);
            if (handler != null) {
                context.pipeline().replace(this, this.newNonSslHandlerName(), handler);
            } else {
                context.pipeline().remove(this);
            }
        }

        @Override
        protected SslHandler newSslHandler(ChannelHandlerContext context, SslContext sslContext) {
            NettyServerCnxn cnxn = Objects.requireNonNull(context.channel().attr(CONNECTION_ATTRIBUTE).get());
            LOG.debug("creating ssl handler for session {}", cnxn.getSessionId());
            SslHandler handler = super.newSslHandler(context, sslContext);
            Future<Channel> handshakeFuture = handler.handshakeFuture();
            handshakeFuture.addListener(new CertificateVerifier(handler, cnxn));
            return handler;
        }

        @Override
        protected ChannelHandler newNonSslHandler(ChannelHandlerContext context) {
            NettyServerCnxn cnxn = Objects.requireNonNull(context.channel().attr(CONNECTION_ATTRIBUTE).get());
            LOG.debug("creating plaintext handler for session {}", cnxn.getSessionId());
            allChannels.add(context.channel());
            addCnxn(cnxn);
            return super.newNonSslHandler(context);
        }
    }

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

            NettyServerCnxn cnxn = new NettyServerCnxn(ctx.channel(),
                    zkServer, NettyServerCnxnFactory.this);
            ctx.channel().attr(CONNECTION_ATTRIBUTE).set(cnxn);

            if (secure) {
                SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
                Future<Channel> handshakeFuture = sslHandler.handshakeFuture();
                handshakeFuture.addListener(new CertificateVerifier(sslHandler, cnxn));
            } else if (!shouldUsePortUnification) {
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
                    // TODO(ivmaykov): Not sure if cnxn can be null here. It becomes null if channelInactive()
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
    }

    final class CertificateVerifier implements GenericFutureListener<Future<Channel>> {
        private final SslHandler sslHandler;
        private final NettyServerCnxn cnxn;

        CertificateVerifier(SslHandler sslHandler, NettyServerCnxn cnxn) {
            this.sslHandler = sslHandler;
            this.cnxn = cnxn;
        }

        /**
         * Only allow the connection to stay open if certificate passes auth
         */
        public void operationComplete(Future<Channel> future) {
            if (future.isSuccess()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Successful handshake with session 0x{}",
                            Long.toHexString(cnxn.getSessionId()));
                }
                SSLEngine eng = sslHandler.engine();
                // Don't try to verify certificate if we didn't ask client to present one
                if (eng.getNeedClientAuth() || eng.getWantClientAuth()) {
                    SSLSession session = eng.getSession();
                    try {
                        cnxn.setClientCertificateChain(session.getPeerCertificates());
                    } catch (SSLPeerUnverifiedException e) {
                        if (eng.getNeedClientAuth()) {
                            // Certificate was requested but not present
                            LOG.error("Error getting peer certificates", e);
                            cnxn.close();
                            return;
                        } else {
                            // Certificate was requested but was optional
                            // TODO: what auth info should we set on the connection?
                            final Channel futureChannel = future.getNow();
                            allChannels.add(Objects.requireNonNull(futureChannel));
                            addCnxn(cnxn);
                            return;
                        }
                    } catch (Exception e) {
                        LOG.error("Error getting peer certificates", e);
                        cnxn.close();
                        return;
                    }

                    String authProviderProp
                            = System.getProperty(x509Util.getSslAuthProviderProperty(), "x509");

                    X509AuthenticationProvider authProvider =
                            (X509AuthenticationProvider)
                                    ProviderRegistry.getProvider(authProviderProp);

                    if (authProvider == null) {
                        LOG.error("X509 Auth provider not found: {}", authProviderProp);
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

        boolean usePortUnification = Boolean.getBoolean(PORT_UNIFICATION_KEY);
        LOG.info("{}={}", PORT_UNIFICATION_KEY, usePortUnification);
        if (usePortUnification) {
            try {
                QuorumPeerConfig.configureSSLAuth();
            } catch (QuorumPeerConfig.ConfigException e) {
                LOG.error("unable to set up SslAuthProvider, turning off client port unification", e);
                usePortUnification = false;
            }
        }
        this.shouldUsePortUnification = usePortUnification;

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
                            initSSL(pipeline, false);
                        } else if (shouldUsePortUnification) {
                            initSSL(pipeline, true);
                        }
                        pipeline.addLast("servercnxnfactory", channelHandler);
                    }
                });
        this.bootstrap = configureBootstrapAllocator(bootstrap);
        this.bootstrap.validate();
    }

    private synchronized void initSSL(ChannelPipeline p, boolean supportPlaintext)
            throws X509Exception, KeyManagementException, NoSuchAlgorithmException {
        String authProviderProp = System.getProperty(x509Util.getSslAuthProviderProperty());
        SslContext nettySslContext;
        if (authProviderProp == null) {
            SSLContextAndOptions sslContextAndOptions = x509Util.getDefaultSSLContextAndOptions();
            nettySslContext = sslContextAndOptions.createNettyJdkSslContext(
                        sslContextAndOptions.getSSLContext(), false);
        } else {
            SSLContext sslContext = SSLContext.getInstance(ClientX509Util.DEFAULT_PROTOCOL);
            X509AuthenticationProvider authProvider =
                    (X509AuthenticationProvider) ProviderRegistry.getProvider(
                            System.getProperty(x509Util.getSslAuthProviderProperty(), "x509"));

            if (authProvider == null) {
                LOG.error("Auth provider not found: {}", authProviderProp);
                throw new SSLContextException(
                        "Could not create SSLContext with specified auth provider: " +
                                authProviderProp);
            }

            sslContext.init(new X509KeyManager[]{authProvider.getKeyManager()},
                    new X509TrustManager[]{authProvider.getTrustManager()},
                    null);
            nettySslContext = x509Util.getDefaultSSLContextAndOptions()
                    .createNettyJdkSslContext(sslContext,false);
        }

        if (supportPlaintext) {
            p.addLast("ssl", new DualModeSslHandler(nettySslContext));
            LOG.debug("dual mode SSL handler added for channel: {}", p.channel());
        } else {
            p.addLast("ssl", nettySslContext.newHandler(p.channel().alloc()));
            LOG.debug("SSL handler added for channel: {}", p.channel());
        }
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
    public boolean closeSession(long sessionId) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closeSession sessionid:0x" + sessionId);
        }
        for (ServerCnxn cnxn : cnxns) {
            if (cnxn.getSessionId() == sessionId) {
                try {
                    cnxn.close();
                } catch (Exception e) {
                    LOG.warn("exception during session close", e);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void configure(InetSocketAddress addr, int maxClientCnxns, boolean secure)
            throws IOException
    {
        configureSaslLogin();
        localAddress = addr;
        this.maxClientCnxns = maxClientCnxns;
        this.secure = secure;
    }

    /** {@inheritDoc} */
    public int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /** {@inheritDoc} */
    public void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
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

    @Override
    public void resetAllConnectionStats() {
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for(ServerCnxn c : cnxns){
            c.resetStats();
        }
    }

    @Override
    public Iterable<Map<String, Object>> getAllConnectionInfo(boolean brief) {
        HashSet<Map<String,Object>> info = new HashSet<Map<String,Object>>();
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
