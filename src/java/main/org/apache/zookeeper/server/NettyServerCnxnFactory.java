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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

public class NettyServerCnxnFactory extends ServerCnxnFactory {
    Logger LOG = LoggerFactory.getLogger(NettyServerCnxnFactory.class);

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
            allChannels.add(ctx.getChannel());
            NettyServerCnxn cnxn = new NettyServerCnxn(ctx.getChannel(),
                    zkServer, NettyServerCnxnFactory.this);
            ctx.setAttachment(cnxn);
            addCnxn(cnxn);
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
                    cnxn.close();
                }
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
                    LOG.debug(Long.toHexString(cnxn.sessionId)
                            + " queuedBuffer 0x"
                            + ChannelBuffers.hexDump(cnxn.queuedBuffer));
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
        bootstrap.setOption("child.soLinger", 2);

        bootstrap.getPipeline().addLast("servercnxnfactory", channelHandler);
    }
    
    @Override
    public void closeAll() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closeAll()");
        }

        synchronized (cnxns) {
            // got to clear all the connections that we have in the selector
            for (NettyServerCnxn cnxn : cnxns.toArray(new NettyServerCnxn[cnxns.size()])) {
                try {
                    cnxn.close();
                } catch (Exception e) {
                    LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                            + Long.toHexString(cnxn.getSessionId()), e);
                }
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("allChannels size:" + allChannels.size()
                    + " cnxns size:" + cnxns.size());
        }
    }

    @Override
    public void closeSession(long sessionId) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closeSession sessionid:0x" + sessionId);
        }

        synchronized (cnxns) {
            for (NettyServerCnxn cnxn : cnxns.toArray(new NettyServerCnxn[cnxns.size()])) {
                if (cnxn.getSessionId() == sessionId) {
                    try {
                        cnxn.close();
                    } catch (Exception e) {
                        LOG.warn("exception during session close", e);
                    }
                    break;
                }
            }
        }
    }

    @Override
    public void configure(InetSocketAddress addr, int maxClientCnxns)
            throws IOException
    {
        configureSaslLogin();
        localAddress = addr;
        this.maxClientCnxns = maxClientCnxns;
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

    @Override
    public void startup(ZooKeeperServer zks) throws IOException,
            InterruptedException {
        start();
        zks.startdata();
        zks.startup();
        setZooKeeperServer(zks);
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
        synchronized (cnxns) {
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
    }

}
