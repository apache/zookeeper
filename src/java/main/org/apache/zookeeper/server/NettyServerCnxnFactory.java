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

import static org.jboss.netty.buffer.ChannelBuffers.dynamicBuffer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.common.X509Exception;
import org.apache.zookeeper.common.X509Exception.SSLContextException;
import org.apache.zookeeper.common.X509Util;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.X509AuthenticationProvider;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerCnxnFactory extends ServerCnxnFactory {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxnFactory.class);

    ServerBootstrap bootstrap;
    Channel parentChannel;
    ChannelGroup allChannels = new DefaultChannelGroup("zkServerCnxns");
    HashMap<InetAddress, Set<NettyServerCnxn>> ipMap =
        new HashMap<InetAddress, Set<NettyServerCnxn>>( );
    InetSocketAddress localAddress;
    int maxClientCnxns = 60;

    /**
     * This is an inner class since we need to extend SimpleChannelHandler, but
     * NettyServerCnxnFactory already extends ServerCnxnFactory. By making it inner
     * this class gets access to the member variables and methods.
     */
    @Sharable
    class CnxnChannelHandler extends SimpleChannelHandler {

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
            throws Exception
        {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel closed " + e);
            }
            allChannels.remove(ctx.getChannel());
        }

        @Override
        public void channelConnected(ChannelHandlerContext ctx,
                ChannelStateEvent e) throws Exception
        {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel connected " + e);
            }

            NettyServerCnxn cnxn = new NettyServerCnxn(ctx.getChannel(),
                    zkServer, NettyServerCnxnFactory.this);
            ctx.setAttachment(cnxn);

            if (secure) {
                SslHandler sslHandler = ctx.getPipeline().get(SslHandler.class);
                ChannelFuture handshakeFuture = sslHandler.handshake();
                handshakeFuture.addListener(new CertificateVerifier(sslHandler, cnxn));
            } else {
                allChannels.add(ctx.getChannel());
                addCnxn(cnxn);
            }
        }

        @Override
        public void channelDisconnected(ChannelHandlerContext ctx,
                ChannelStateEvent e) throws Exception
        {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel disconnected " + e);
            }
            NettyServerCnxn cnxn = (NettyServerCnxn) ctx.getAttachment();
            if (cnxn != null) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Channel disconnect caused close " + e);
                }
                cnxn.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
            throws Exception
        {
            LOG.warn("Exception caught " + e, e.getCause());
            NettyServerCnxn cnxn = (NettyServerCnxn) ctx.getAttachment();
            if (cnxn != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Closing " + cnxn);
                }
                cnxn.close();
            }
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
            throws Exception
        {
            if (LOG.isTraceEnabled()) {
                LOG.trace("message received called " + e.getMessage());
            }
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("New message " + e.toString()
                            + " from " + ctx.getChannel());
                }
                NettyServerCnxn cnxn = (NettyServerCnxn)ctx.getAttachment();
                synchronized(cnxn) {
                    processMessage(e, cnxn);
                }
            } catch(Exception ex) {
                LOG.error("Unexpected exception in receive", ex);
                throw ex;
            }
        }

        private void processMessage(MessageEvent e, NettyServerCnxn cnxn) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(Long.toHexString(cnxn.sessionId) + " queuedBuffer: "
                        + cnxn.queuedBuffer);
            }

            if (e instanceof NettyServerCnxn.ResumeMessageEvent) {
                LOG.debug("Received ResumeMessageEvent");
                if (cnxn.queuedBuffer != null) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("processing queue "
                                + Long.toHexString(cnxn.sessionId)
                                + " queuedBuffer 0x"
                                + ChannelBuffers.hexDump(cnxn.queuedBuffer));
                    }
                    cnxn.receiveMessage(cnxn.queuedBuffer);
                    if (!cnxn.queuedBuffer.readable()) {
                        LOG.debug("Processed queue - no bytes remaining");
                        cnxn.queuedBuffer = null;
                    } else {
                        LOG.debug("Processed queue - bytes remaining");
                    }
                } else {
                    LOG.debug("queue empty");
                }
                cnxn.channel.setReadable(true);
            } else {
                ChannelBuffer buf = (ChannelBuffer)e.getMessage();
                if (LOG.isTraceEnabled()) {
                    LOG.trace(Long.toHexString(cnxn.sessionId)
                            + " buf 0x"
                            + ChannelBuffers.hexDump(buf));
                }
                
                if (cnxn.throttled) {
                    LOG.debug("Received message while throttled");
                    // we are throttled, so we need to queue
                    if (cnxn.queuedBuffer == null) {
                        LOG.debug("allocating queue");
                        cnxn.queuedBuffer = dynamicBuffer(buf.readableBytes());
                    }
                    cnxn.queuedBuffer.writeBytes(buf);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace(Long.toHexString(cnxn.sessionId)
                                + " queuedBuffer 0x"
                                + ChannelBuffers.hexDump(cnxn.queuedBuffer));
                    }
                } else {
                    LOG.debug("not throttled");
                    if (cnxn.queuedBuffer != null) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(Long.toHexString(cnxn.sessionId)
                                    + " queuedBuffer 0x"
                                    + ChannelBuffers.hexDump(cnxn.queuedBuffer));
                        }
                        cnxn.queuedBuffer.writeBytes(buf);
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(Long.toHexString(cnxn.sessionId)
                                    + " queuedBuffer 0x"
                                    + ChannelBuffers.hexDump(cnxn.queuedBuffer));
                        }

                        cnxn.receiveMessage(cnxn.queuedBuffer);
                        if (!cnxn.queuedBuffer.readable()) {
                            LOG.debug("Processed queue - no bytes remaining");
                            cnxn.queuedBuffer = null;
                        } else {
                            LOG.debug("Processed queue - bytes remaining");
                        }
                    } else {
                        cnxn.receiveMessage(buf);
                        if (buf.readable()) {
                            if (LOG.isTraceEnabled()) {
                                LOG.trace("Before copy " + buf);
                            }
                            cnxn.queuedBuffer = dynamicBuffer(buf.readableBytes()); 
                            cnxn.queuedBuffer.writeBytes(buf);
                            if (LOG.isTraceEnabled()) {
                                LOG.trace("Copy is " + cnxn.queuedBuffer);
                                LOG.trace(Long.toHexString(cnxn.sessionId)
                                        + " queuedBuffer 0x"
                                        + ChannelBuffers.hexDump(cnxn.queuedBuffer));
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void writeComplete(ChannelHandlerContext ctx,
                WriteCompletionEvent e) throws Exception
        {
            if (LOG.isTraceEnabled()) {
                LOG.trace("write complete " + e);
            }
        }

        private final class CertificateVerifier
                implements ChannelFutureListener {
            private final SslHandler sslHandler;
            private final NettyServerCnxn cnxn;

            CertificateVerifier(SslHandler sslHandler, NettyServerCnxn cnxn) {
                this.sslHandler = sslHandler;
                this.cnxn = cnxn;
            }

            /**
             * Only allow the connection to stay open if certificate passes auth
             */
            public void operationComplete(ChannelFuture future)
                    throws SSLPeerUnverifiedException {
                if (future.isSuccess()) {
                    LOG.debug("Successful handshake with session 0x{}",
                            Long.toHexString(cnxn.sessionId));
                    SSLEngine eng = sslHandler.getEngine();
                    SSLSession session = eng.getSession();
                    cnxn.setClientCertificateChain(session.getPeerCertificates());

                    String authProviderProp
                            = System.getProperty(ZKConfig.SSL_AUTHPROVIDER, "x509");

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
                                Long.toHexString(cnxn.sessionId));
                        cnxn.close();
                        return;
                    }

                    allChannels.add(future.getChannel());
                    addCnxn(cnxn);
                } else {
                    LOG.error("Unsuccessful handshake with session 0x{}",
                            Long.toHexString(cnxn.sessionId));
                    cnxn.close();
                }
            }
        }
    }
    
    CnxnChannelHandler channelHandler = new CnxnChannelHandler();

    NettyServerCnxnFactory() {
        bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));
        // parent channel
        bootstrap.setOption("reuseAddress", true);
        // child channels
        bootstrap.setOption("child.tcpNoDelay", true);
        /* set socket linger to off, so that socket close does not block */
        bootstrap.setOption("child.soLinger", -1);
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline p = Channels.pipeline();
                if (secure) {
                    initSSL(p);
                }
                p.addLast("servercnxnfactory", channelHandler);

                return p;
            }
        });
    }

    private synchronized void initSSL(ChannelPipeline p)
            throws X509Exception, KeyManagementException, NoSuchAlgorithmException {
        String authProviderProp = System.getProperty(ZKConfig.SSL_AUTHPROVIDER);
        SSLContext sslContext;
        if (authProviderProp == null) {
            sslContext = X509Util.createSSLContext();
        } else {
            sslContext = SSLContext.getInstance("TLSv1");
            X509AuthenticationProvider authProvider =
                    (X509AuthenticationProvider)ProviderRegistry.getProvider(
                            System.getProperty(ZKConfig.SSL_AUTHPROVIDER,
                                    "x509"));

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
        LOG.info("SSL handler added for channel: {}", p.getChannel());
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

    boolean killed;
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
        LOG.info("shutdown called " + localAddress);
        if (login != null) {
            login.shutdown();
        }
        // null if factory never started
        if (parentChannel != null) {
            parentChannel.close().awaitUninterruptibly();
            closeAll();
            allChannels.close().awaitUninterruptibly();
            bootstrap.releaseExternalResources();
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
        LOG.info("binding to port " + localAddress);
        parentChannel = bootstrap.bind(localAddress);
    }
    
    public void reconfigure(InetSocketAddress addr) {
       Channel oldChannel = parentChannel;
       try {
           LOG.info("binding to port {}", addr);
           parentChannel = bootstrap.bind(addr);
           localAddress = addr;
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
                ((InetSocketAddress)cnxn.channel.getRemoteAddress())
                    .getAddress();
            Set<NettyServerCnxn> s = ipMap.get(addr);
            if (s == null) {
                s = new HashSet<NettyServerCnxn>();
            }
            s.add(cnxn);
            ipMap.put(addr,s);
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
        HashSet<Map<String,Object>> info = new HashSet<Map<String,Object>>();
        // No need to synchronize since cnxns is backed by a ConcurrentHashMap
        for (ServerCnxn c : cnxns) {
            info.add(c.getConnectionInfo(brief));
        }
        return info;
    }

}
