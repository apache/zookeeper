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

import static org.jboss.netty.buffer.ChannelBuffers.wrappedBuffer;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.command.CommandExecutor;
import org.apache.zookeeper.server.command.FourLetterCommands;
import org.apache.zookeeper.server.command.NopCommand;
import org.apache.zookeeper.server.command.SetTraceMaskCommand;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.MessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyServerCnxn extends ServerCnxn {
    private static final Logger LOG = LoggerFactory.getLogger(NettyServerCnxn.class);
    Channel channel;
    ChannelBuffer queuedBuffer;
    volatile boolean throttled;
    ByteBuffer bb;
    ByteBuffer bbLen = ByteBuffer.allocate(4);
    long sessionId;
    int sessionTimeout;
    AtomicLong outstandingCount = new AtomicLong();
    Certificate[] clientChain;
    volatile boolean closingChannel;

    /** The ZooKeeperServer for this connection. May be null if the server
     * is not currently serving requests (for example if the server is not
     * an active quorum participant.
     */
    private volatile ZooKeeperServer zkServer;

    NettyServerCnxnFactory factory;
    boolean initialized;
    
    NettyServerCnxn(Channel channel, ZooKeeperServer zks, NettyServerCnxnFactory factory) {
        this.channel = channel;
        this.closingChannel = false;
        this.zkServer = zks;
        this.factory = factory;
        if (this.factory.login != null) {
            this.zooKeeperSaslServer = new ZooKeeperSaslServer(factory.login);
        }
    }
    
    @Override
    public void close() {
        closingChannel = true;
        
        if (LOG.isDebugEnabled()) {
            LOG.debug("close called for sessionid:0x"
                    + Long.toHexString(sessionId));
        }

        // ZOOKEEPER-2743:
        // Always unregister connection upon close to prevent
        // connection bean leak under certain race conditions.
        factory.unregisterConnection(this);

        synchronized(factory.cnxns){
            // if this is not in cnxns then it's already closed
            if (!factory.cnxns.remove(this)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("cnxns size:" + factory.cnxns.size());
                }
                return;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("close in progress for sessionid:0x"
                        + Long.toHexString(sessionId));
            }

            synchronized (factory.ipMap) {
                Set<NettyServerCnxn> s =
                    factory.ipMap.get(((InetSocketAddress)channel
                            .getRemoteAddress()).getAddress());
                s.remove(this);
            }
        }

        if (channel.isOpen()) {
            // Since we don't check on the futures created by write calls to the channel complete we need to make sure
            // that all writes have been completed before closing the channel or we risk data loss
            // See: http://lists.jboss.org/pipermail/netty-users/2009-August/001122.html
            channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public long getSessionId() {
        return sessionId;
    }

    @Override
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public void process(WatchedEvent event) {
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                                     "Deliver event " + event + " to 0x"
                                     + Long.toHexString(this.sessionId)
                                     + " through " + this);
        }

        // Convert WatchedEvent to a type that can be sent over the wire
        WatcherEvent e = event.getWrapper();

        try {
            sendResponse(h, e, "notification");
        } catch (IOException e1) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Problem sending to " + getRemoteSocketAddress(), e1);
            }
            close();
        }
    }

    private static final byte[] fourBytes = new byte[4];
    static class ResumeMessageEvent implements MessageEvent {
        Channel channel;
        ResumeMessageEvent(Channel channel) {
            this.channel = channel;
        }
        @Override
        public Object getMessage() {return null;}
        @Override
        public SocketAddress getRemoteAddress() {return null;}
        @Override
        public Channel getChannel() {return channel;}
        @Override
        public ChannelFuture getFuture() {return null;}
    };
    
    @Override
    public void sendResponse(ReplyHeader h, Record r, String tag)
            throws IOException {
        if (closingChannel || !channel.isOpen()) {
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Make space for length
        BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
        try {
            baos.write(fourBytes);
            bos.writeRecord(h, "header");
            if (r != null) {
                bos.writeRecord(r, tag);
            }
            baos.close();
        } catch (IOException e) {
            LOG.error("Error serializing response");
        }
        byte b[] = baos.toByteArray();
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.putInt(b.length - 4).rewind();
        sendBuffer(bb);
        if (h.getXid() > 0) {
            // zks cannot be null otherwise we would not have gotten here!
            if (!zkServer.shouldThrottle(outstandingCount.decrementAndGet())) {
                enableRecv();
            }
        }
    }

    @Override
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public void enableRecv() {
        if (throttled) {
            throttled = false;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Sending unthrottle event " + this);
            }
            channel.getPipeline().sendUpstream(new ResumeMessageEvent(channel));
        }
    }

    @Override
    public void sendBuffer(ByteBuffer sendBuffer) {
        if (sendBuffer == ServerCnxnFactory.closeConn) {
            close();
            return;
        }
        channel.write(wrappedBuffer(sendBuffer));
        packetSent();
    }



    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     */
    private class SendBufferWriter extends Writer {
        private StringBuffer sb = new StringBuffer();
        
        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         */
        private void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) {
                sendBuffer(ByteBuffer.wrap(sb.toString().getBytes()));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

        @Override
        public void close() throws IOException {
            if (sb == null) return;
            checkFlush(true);
            sb = null; // clear out the ref to ensure no reuse
        }

        @Override
        public void flush() throws IOException {
            checkFlush(true);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            sb.append(cbuf, off, len);
            checkFlush(false);
        }
    }

    /** Return if four letter word found and responded to, otw false **/
    private boolean checkFourLetterWord(final Channel channel,
            ChannelBuffer message, final int len) throws IOException
    {
        // We take advantage of the limited size of the length to look
        // for cmds. They are all 4-bytes which fits inside of an int
        if (!FourLetterCommands.isKnown(len)) {
            return false;
        }

        String cmd = FourLetterCommands.getCommandString(len);

        channel.setInterestOps(0).awaitUninterruptibly();
        packetReceived();

        final PrintWriter pwriter = new PrintWriter(
                new BufferedWriter(new SendBufferWriter()));

        // ZOOKEEPER-2693: don't execute 4lw if it's not enabled.
        if (!FourLetterCommands.isEnabled(cmd)) {
            LOG.debug("Command {} is not executed because it is not in the whitelist.", cmd);
            NopCommand nopCmd = new NopCommand(pwriter, this, cmd +
                    " is not executed because it is not in the whitelist.");
            nopCmd.start();
            return true;
        }

        LOG.info("Processing " + cmd + " command from "
                + channel.getRemoteAddress());

       if (len == FourLetterCommands.setTraceMaskCmd) {
            ByteBuffer mask = ByteBuffer.allocate(8);
            message.readBytes(mask);
            mask.flip();
            long traceMask = mask.getLong();
            ZooTrace.setTextTraceLevel(traceMask);
            SetTraceMaskCommand setMask = new SetTraceMaskCommand(pwriter, this, traceMask);
            setMask.start();
            return true;
        } else {
            CommandExecutor commandExecutor = new CommandExecutor();
            return commandExecutor.execute(this, pwriter, len, zkServer,factory);
        }
    }

    public void receiveMessage(ChannelBuffer message) {
        try {
            while(message.readable() && !throttled) {
                if (bb != null) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("message readable " + message.readableBytes()
                                + " bb len " + bb.remaining() + " " + bb);
                        ByteBuffer dat = bb.duplicate();
                        dat.flip();
                        LOG.trace(Long.toHexString(sessionId)
                                + " bb 0x"
                                + ChannelBuffers.hexDump(
                                        ChannelBuffers.copiedBuffer(dat)));
                    }

                    if (bb.remaining() > message.readableBytes()) {
                        int newLimit = bb.position() + message.readableBytes();
                        bb.limit(newLimit);
                    }
                    message.readBytes(bb);
                    bb.limit(bb.capacity());

                    if (LOG.isTraceEnabled()) {
                        LOG.trace("after readBytes message readable "
                                + message.readableBytes()
                                + " bb len " + bb.remaining() + " " + bb);
                        ByteBuffer dat = bb.duplicate();
                        dat.flip();
                        LOG.trace("after readbytes "
                                + Long.toHexString(sessionId)
                                + " bb 0x"
                                + ChannelBuffers.hexDump(
                                        ChannelBuffers.copiedBuffer(dat)));
                    }
                    if (bb.remaining() == 0) {
                        packetReceived();
                        bb.flip();

                        ZooKeeperServer zks = this.zkServer;
                        if (zks == null || !zks.isRunning()) {
                            throw new IOException("ZK down");
                        }
                        if (initialized) {
                            zks.processPacket(this, bb);

                            if (zks.shouldThrottle(outstandingCount.incrementAndGet())) {
                                disableRecvNoWait();
                            }
                        } else {
                            LOG.debug("got conn req request from "
                                    + getRemoteSocketAddress());
                            zks.processConnectRequest(this, bb);
                            initialized = true;
                        }
                        bb = null;
                    }
                } else {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("message readable "
                                + message.readableBytes()
                                + " bblenrem " + bbLen.remaining());
                        ByteBuffer dat = bbLen.duplicate();
                        dat.flip();
                        LOG.trace(Long.toHexString(sessionId)
                                + " bbLen 0x"
                                + ChannelBuffers.hexDump(
                                        ChannelBuffers.copiedBuffer(dat)));
                    }

                    if (message.readableBytes() < bbLen.remaining()) {
                        bbLen.limit(bbLen.position() + message.readableBytes());
                    }
                    message.readBytes(bbLen);
                    bbLen.limit(bbLen.capacity());
                    if (bbLen.remaining() == 0) {
                        bbLen.flip();

                        if (LOG.isTraceEnabled()) {
                            LOG.trace(Long.toHexString(sessionId)
                                    + " bbLen 0x"
                                    + ChannelBuffers.hexDump(
                                            ChannelBuffers.copiedBuffer(bbLen)));
                        }
                        int len = bbLen.getInt();
                        if (LOG.isTraceEnabled()) {
                            LOG.trace(Long.toHexString(sessionId)
                                    + " bbLen len is " + len);
                        }

                        bbLen.clear();
                        if (!initialized) {
                            if (checkFourLetterWord(channel, message, len)) {
                                return;
                            }
                        }
                        if (len < 0 || len > BinaryInputArchive.maxBuffer) {
                            throw new IOException("Len error " + len);
                        }
                        bb = ByteBuffer.allocate(len);
                    }
                }
            }
        } catch(IOException e) {
            LOG.warn("Closing connection to " + getRemoteSocketAddress(), e);
            close();
        }
    }

    @Override
    public void disableRecv() {
        disableRecvNoWait().awaitUninterruptibly();
    }
    
    private ChannelFuture disableRecvNoWait() {
        throttled = true;
        if (LOG.isDebugEnabled()) {
            LOG.debug("Throttling - disabling recv " + this);
        }
        return channel.setReadable(false);
    }
    
    @Override
    public long getOutstandingRequests() {
        return outstandingCount.longValue();
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    @Override
    public int getInterestOps() {
        return channel.getInterestOps();
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return (InetSocketAddress)channel.getRemoteAddress();
    }

    /** Send close connection packet to the client.
     */
    @Override
    public void sendCloseSession() {
        sendBuffer(ServerCnxnFactory.closeConn);
    }

    @Override
    protected ServerStats serverStats() {
        if (zkServer == null) {
            return null;
        }
        return zkServer.serverStats();
    }

    @Override
    public boolean isSecure() {
        return factory.secure;
    }

    @Override
    public Certificate[] getClientCertificateChain() {
        if (clientChain == null)
        {
            return null;
        }
        return Arrays.copyOf(clientChain, clientChain.length);
    }

    @Override
    public void setClientCertificateChain(Certificate[] chain) {
        if (chain == null)
        {
            clientChain = null;
        } else {
            clientChain = Arrays.copyOf(chain, chain.length);
        }
    }
}
