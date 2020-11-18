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
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.NettyUtils;
import org.apache.zookeeper.common.SSLContextAndOptions;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.server.NettyServerCnxn.HandshakeState;
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

    private final AtomicInteger outstandingHandshake = new AtomicInteger();
    public static final String OUTSTANDING_HANDSHAKE_LIMIT = "zookeeper.netty.server.outstandingHandshake.limit";
    private int outstandingHandshakeLimit;
    private boolean handshakeThrottlingEnabled;

    public void setOutstandingHandshakeLimit(int limit) {
        outstandingHandshakeLimit = limit;
        handshakeThrottlingEnabled = (secure || shouldUsePortUnification) && outstandingHandshakeLimit > 0;
        LOG.info("handshakeThrottlingEnabled = {}, {} = {}",
                handshakeThrottlingEnabled, OUTSTANDING_HANDSHAKE_LIMIT, outstandingHandshakeLimit);
    }

    private final ServerBootstrap bootstrap;
    private Channel parentChannel;
    private final ChannelGroup allChannels = new DefaultChannelGroup("zkServerCnxns", new DefaultEventExecutor());
    private final Map<InetAddress, AtomicInteger> ipMap = new ConcurrentHashMap<>();
    private InetSocketAddress localAddress;
    private int maxClientCnxns = 60;
    int listenBacklog = -1;
    private final ClientX509Util x509Util;

    public static final String NETTY_ADVANCED_FLOW_CONTROL = "zookeeper.netty.advancedFlowControl.enabled";
    private boolean advancedFlowControlEnabled = false;

    private static final AttributeKey<NettyServerCnxn> CONNECTION_ATTRIBUTE = AttributeKey.valueOf("NettyServerCnxn");

    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR = new AtomicReference<>(null);

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
            // Mark handshake finished if it's a insecure cnxn
            updateHandshakeCountIfStarted(cnxn);
            allChannels.add(context.channel());
            addCnxn(cnxn);
            return super.newNonSslHandler(context);
        }

    }

    private void updateHandshakeCountIfStarted(NettyServerCnxn cnxn) {
        if (cnxn != null && cnxn.getHandshakeState() == HandshakeState.STARTED) {
            cnxn.setHandshakeState(HandshakeState.FINISHED);
            outstandingHandshake.addAndGet(-1);
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

            final Channel channel = ctx.channel();
            if (limitTotalNumberOfCnxns()) {
                ServerMetrics.getMetrics().CONNECTION_REJECTED.add(1);
                channel.close();
                return;
            }
            InetAddress addr = ((InetSocketAddress) channel.remoteAddress()).getAddress();
            if (maxClientCnxns > 0 && getClientCnxnCount(addr) >= maxClientCnxns) {
                ServerMetrics.getMetrics().CONNECTION_REJECTED.add(1);
                LOG.warn("Too many connections from {} - max is {}", addr, maxClientCnxns);
                channel.close();
                return;
            }

            NettyServerCnxn cnxn = new NettyServerCnxn(channel, zkServer, NettyServerCnxnFactory.this);
            ctx.channel().attr(CONNECTION_ATTRIBUTE).set(cnxn);

            // Check the zkServer assigned to the cnxn is still running,
            // close it before starting the heavy TLS handshake
            if (!cnxn.isZKServerRunning()) {
                LOG.warn("Zookeeper server is not running, close the connection before starting the TLS handshake");
                ServerMetrics.getMetrics().CNXN_CLOSED_WITHOUT_ZK_SERVER_RUNNING.add(1);
                channel.close();
                return;
            }

            if (handshakeThrottlingEnabled) {
                // Favor to check and throttling even in dual mode which
                // accepts both secure and insecure connections, since
                // it's more efficient than throttling when we know it's
                // a secure connection in DualModeSslHandler.
                //
                // From benchmark, this reduced around 15% reconnect time.
                int outstandingHandshakesNum = outstandingHandshake.addAndGet(1);
                if (outstandingHandshakesNum > outstandingHandshakeLimit) {
                    outstandingHandshake.addAndGet(-1);
                    channel.close();
                    ServerMetrics.getMetrics().TLS_HANDSHAKE_EXCEEDED.add(1);
                } else {
                    cnxn.setHandshakeState(HandshakeState.STARTED);
                }
            }

            if (secure) {
                SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
                Future<Channel> handshakeFuture = sslHandler.handshakeFuture();
                handshakeFuture.addListener(new CertificateVerifier(sslHandler, cnxn));
            } else if (!shouldUsePortUnification) {
                allChannels.add(ctx.channel());
                addCnxn(cnxn);
            }
            if (ctx.channel().pipeline().get(SslHandler.class) == null) {
                SocketAddress remoteAddress = cnxn.getChannel().remoteAddress();
                if (remoteAddress != null
                        && !((InetSocketAddress) remoteAddress).getAddress().isLoopbackAddress()) {
                    LOG.trace("NettyChannelHandler channelActive: remote={} local={}", remoteAddress, cnxn.getChannel().localAddress());
                    zkServer.serverStats().incrementNonMTLSRemoteConnCount();
                } else {
                    zkServer.serverStats().incrementNonMTLSLocalConnCount();
                }
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
                updateHandshakeCountIfStarted(cnxn);
                cnxn.close(ServerCnxn.DisconnectReason.CHANNEL_DISCONNECTED);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOG.warn("Exception caught", cause);
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).getAndSet(null);
            if (cnxn != null) {
                LOG.debug("Closing {}", cnxn);
                updateHandshakeCountIfStarted(cnxn);
                cnxn.close(ServerCnxn.DisconnectReason.CHANNEL_CLOSED_EXCEPTION);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            try {
                if (evt == NettyServerCnxn.ReadEvent.ENABLE) {
                    LOG.debug("Received ReadEvent.ENABLE");
                    NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
                    // TODO: Not sure if cnxn can be null here. It becomes null if channelInactive()
                    // or exceptionCaught() trigger, but it's unclear to me if userEventTriggered() can run
                    // after either of those. Check for null just to be safe ...
                    if (cnxn != null) {
                        if (cnxn.getQueuedReadableBytes() > 0) {
                            cnxn.processQueuedBuffer();
                            if (advancedFlowControlEnabled && cnxn.getQueuedReadableBytes() == 0) {
                                // trigger a read if we have consumed all
                                // backlog
                                ctx.read();
                                LOG.debug("Issued a read after queuedBuffer drained");
                            }
                        }
                    }
                    if (!advancedFlowControlEnabled) {
                        ctx.channel().config().setAutoRead(true);
                    }
                } else if (evt == NettyServerCnxn.ReadEvent.DISABLE) {
                    LOG.debug("Received ReadEvent.DISABLE");
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
                    LOG.debug("New message {} from {}", msg, ctx.channel());
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

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            if (advancedFlowControlEnabled) {
                NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
                if (cnxn != null && cnxn.getQueuedReadableBytes() == 0 && cnxn.readIssuedAfterReadComplete == 0) {
                    ctx.read();
                    LOG.debug("Issued a read since we do not have anything to consume after channelReadComplete");
                }
            }

            ctx.fireChannelReadComplete();
        }

        // Use a single listener instance to reduce GC
        // Note: this listener is only added when LOG.isTraceEnabled() is true,
        // so it should not do any work other than trace logging.
        private final GenericFutureListener<Future<Void>> onWriteCompletedTracer = (f) -> {
            if (LOG.isTraceEnabled()) {
                LOG.trace("write success: {}", f.isSuccess());
            }
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
            updateHandshakeCountIfStarted(cnxn);

            if (future.isSuccess()) {
                LOG.debug("Successful handshake with session 0x{}", Long.toHexString(cnxn.getSessionId()));
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

                    String authProviderProp = System.getProperty(x509Util.getSslAuthProviderProperty(), "x509");

                    X509AuthenticationProvider authProvider = (X509AuthenticationProvider) ProviderRegistry.getProvider(authProviderProp);

                    if (authProvider == null) {
                        LOG.error("X509 Auth provider not found: {}", authProviderProp);
                        cnxn.close(ServerCnxn.DisconnectReason.AUTH_PROVIDER_NOT_FOUND);
                        return;
                    }

                    KeeperException.Code code = authProvider.handleAuthentication(cnxn, null);
                    if (KeeperException.Code.OK != code) {
                        zkServer.serverStats().incrementAuthFailedCount();
                        LOG.error("Authentication failed for session 0x{}", Long.toHexString(cnxn.getSessionId()));
                        cnxn.close(ServerCnxn.DisconnectReason.SASL_AUTH_FAILURE);
                        return;
                    }
                }

                final Channel futureChannel = future.getNow();
                allChannels.add(Objects.requireNonNull(futureChannel));
                addCnxn(cnxn);
            } else {
                zkServer.serverStats().incrementAuthFailedCount();
                LOG.error("Unsuccessful handshake with session 0x{}", Long.toHexString(cnxn.getSessionId()));
                ServerMetrics.getMetrics().UNSUCCESSFUL_HANDSHAKE.add(1);
                cnxn.close(ServerCnxn.DisconnectReason.FAILED_HANDSHAKE);
            }
        }

    }

    @Sharable
    static class ReadIssuedTrackingHandler extends ChannelDuplexHandler {

        @Override
        public void read(ChannelHandlerContext ctx) throws Exception {
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
            if (cnxn != null) {
                cnxn.readIssuedAfterReadComplete++;
            }

            ctx.read();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            NettyServerCnxn cnxn = ctx.channel().attr(CONNECTION_ATTRIBUTE).get();
            if (cnxn != null) {
                cnxn.readIssuedAfterReadComplete = 0;
            }

            ctx.fireChannelReadComplete();
        }

    }

    CnxnChannelHandler channelHandler = new CnxnChannelHandler();
    ReadIssuedTrackingHandler readIssuedTrackingHandler = new ReadIssuedTrackingHandler();

    private ServerBootstrap configureBootstrapAllocator(ServerBootstrap bootstrap) {
        ByteBufAllocator testAllocator = TEST_ALLOCATOR.get();
        if (testAllocator != null) {
            return bootstrap.option(ChannelOption.ALLOCATOR, testAllocator)
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

        this.advancedFlowControlEnabled = Boolean.getBoolean(NETTY_ADVANCED_FLOW_CONTROL);
        LOG.info("{} = {}", NETTY_ADVANCED_FLOW_CONTROL, this.advancedFlowControlEnabled);

        setOutstandingHandshakeLimit(Integer.getInteger(OUTSTANDING_HANDSHAKE_LIMIT, -1));

        EventLoopGroup bossGroup = NettyUtils.newNioOrEpollEventLoopGroup(NettyUtils.getClientReachableLocalInetAddressCount());
        EventLoopGroup workerGroup = NettyUtils.newNioOrEpollEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
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
                                                                 if (advancedFlowControlEnabled) {
                                                                     pipeline.addLast(readIssuedTrackingHandler);
                                                                 }
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

    private synchronized void initSSL(ChannelPipeline p, boolean supportPlaintext) throws X509Exception, KeyManagementException, NoSuchAlgorithmException {
        String authProviderProp = System.getProperty(x509Util.getSslAuthProviderProperty());
        SslContext nettySslContext;
        if (authProviderProp == null) {
            SSLContextAndOptions sslContextAndOptions = x509Util.getDefaultSSLContextAndOptions();
            nettySslContext = sslContextAndOptions.createNettyJdkSslContext(sslContextAndOptions.getSSLContext(), false);
        } else {
            SSLContext sslContext = SSLContext.getInstance(ClientX509Util.DEFAULT_PROTOCOL);
            X509AuthenticationProvider authProvider = (X509AuthenticationProvider) ProviderRegistry.getProvider(
                System.getProperty(x509Util.getSslAuthProviderProperty(), "x509"));

            if (authProvider == null) {
                LOG.error("Auth provider not found: {}", authProviderProp);
                throw new SSLContextException("Could not create SSLContext with specified auth provider: " + authProviderProp);
            }

            sslContext.init(new X509KeyManager[]{authProvider.getKeyManager()}, new X509TrustManager[]{authProvider.getTrustManager()}, null);
            nettySslContext = x509Util.getDefaultSSLContextAndOptions().createNettyJdkSslContext(sslContext, false);
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
    public void closeAll(ServerCnxn.DisconnectReason reason) {
        LOG.debug("closeAll()");

        // clear all the connections on which we are selecting
        int length = cnxns.size();
        for (ServerCnxn cnxn : cnxns) {
            try {
                // This will remove the cnxn from cnxns
                cnxn.close(reason);
            } catch (Exception e) {
                LOG.warn("Ignoring exception closing cnxn sessionid 0x{}", Long.toHexString(cnxn.getSessionId()), e);
            }
        }

        LOG.debug("allChannels size: {} cnxns size: {}", allChannels.size(), length);
    }

    @Override
    public void configure(InetSocketAddress addr, int maxClientCnxns, int backlog, boolean secure) throws IOException {
        configureSaslLogin();
        initMaxCnxns();
        localAddress = addr;
        this.maxClientCnxns = maxClientCnxns;
        this.secure = secure;
        this.listenBacklog = backlog;
        LOG.info("configure {} secure: {} on addr {}", this, secure, addr);
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
        synchronized (this) {
            while (!killed) {
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
            closeAll(ServerCnxn.DisconnectReason.SERVER_SHUTDOWN);
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
        synchronized (this) {
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
        LOG.info("bound to port {}", getLocalPort());
    }

    public void reconfigure(InetSocketAddress addr) {
        LOG.info("binding to port {}, {}", addr, localAddress);
        if (addr != null && localAddress != null) {
            if (addr.equals(localAddress) || (addr.getAddress().isAnyLocalAddress()
                    && localAddress.getAddress().isAnyLocalAddress()
                    && addr.getPort() == localAddress.getPort())) {
                 LOG.info("address is the same, skip rebinding");
                 return;
            }
        }

        Channel oldChannel = parentChannel;
        try {
            parentChannel = bootstrap.bind(addr).syncUninterruptibly().channel();
            // Port changes after bind() if the original port was 0, update
            // localAddress to get the real port.
            localAddress = (InetSocketAddress) parentChannel.localAddress();
            LOG.info("bound to port {}", getLocalPort());
        } catch (Exception e) {
            LOG.error("Error while reconfiguring", e);
        } finally {
            oldChannel.close();
        }
    }

    @Override
    public void startup(ZooKeeperServer zks, boolean startServer) throws IOException, InterruptedException {
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

    private void addCnxn(final NettyServerCnxn cnxn) {
        cnxns.add(cnxn);
        InetAddress addr = ((InetSocketAddress) cnxn.getChannel().remoteAddress()).getAddress();

        ipMap.compute(addr, (a, cnxnCount) -> {
            if (cnxnCount == null) {
                cnxnCount = new AtomicInteger();
            }
            cnxnCount.incrementAndGet();
            return cnxnCount;
        });
    }

    void removeCnxnFromIpMap(NettyServerCnxn cnxn, InetAddress remoteAddress) {
        ipMap.compute(remoteAddress, (addr, cnxnCount) -> {
            if (cnxnCount == null) {
                LOG.error("Unexpected remote address {} when removing cnxn {}", remoteAddress, cnxn);
                return null;
            }
            final int newValue = cnxnCount.decrementAndGet();
            return newValue == 0 ? null : cnxnCount;
        });
    }

    private int getClientCnxnCount(final InetAddress addr) {
        final AtomicInteger count = ipMap.get(addr);
        return count == null ? 0 : count.get();
    }

    @Override
    public void resetAllConnectionStats() {
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for (ServerCnxn c : cnxns) {
            c.resetStats();
        }
    }

    @Override
    public Iterable<Map<String, Object>> getAllConnectionInfo(boolean brief) {
        Set<Map<String, Object>> info = new HashSet<Map<String, Object>>();
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

    // VisibleForTest
    public void setAdvancedFlowControlEnabled(boolean advancedFlowControlEnabled) {
        this.advancedFlowControlEnabled = advancedFlowControlEnabled;
    }

    // VisibleForTest
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    // VisibleForTest
    public Channel getParentChannel() {
        return parentChannel;
    }

    public int getOutstandingHandshakeNum() {
        return outstandingHandshake.get();
    }
}
