package org.apache.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;
import org.apache.zookeeper.ClientCnxn.EndOfStreamException;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

public class ClientCnxnSocketNetty extends ClientCnxnSocket {
    private static final Logger LOG = Logger
            .getLogger(ClientCnxnSocketNetty.class);

    private Channel channel;

    private ChannelFactory factory;

    private boolean disconnected;

    @Override
    boolean isConnected() {
        return channel != null;
    }

    private boolean doWrites(List<Packet> pendingQueue) {
        boolean written = false;
        while (!outgoingQueue.isEmpty() && channel.isWritable()) {
            Packet p;
            synchronized(outgoingQueue){
                p = outgoingQueue.removeFirst();                
            }

            ByteBuffer pbb = p.bb;
            ChannelFuture write;
            synchronized(pendingQueue){
                write = channel.write(ChannelBuffers
                        .copiedBuffer(pbb));
                pbb.position(pbb.limit());
                if (p.header != null && p.header.getType() != OpCode.ping
                        && p.header.getType() != OpCode.auth) {
                    pendingQueue.add(p);
                }               
            }
            if (p.header != null && p.header.getType() == OpCode.closeSession) {
                // ensure that the close session is sent before
                // we close the channel
                write.awaitUninterruptibly();
            }

            written = true;
            sentCount++;
        }
        return written;
    }

    @Override
    void connect(InetSocketAddress addr) throws IOException {
        factory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool());

        ClientBootstrap bootstrap = new ClientBootstrap(factory);

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() {
                return Channels.pipeline(new ZKClientHandler());
            }
        });

        bootstrap.setOption("soLinger", -1);
        bootstrap.setOption("tcpNoDelay", true);

        disconnected = false;
        bootstrap.connect(addr);
    }

    @Override
    void enableReadWriteOnly() {

    }

    /**
     * Returns the address to which the socket is connected.
     * 
     * @return ip address of the remote side of the connection or null if not
     *         connected
     */
    @Override
    public SocketAddress getRemoteSocketAddress() {
        if (channel == null) {
            return null;
        }

        return channel.getRemoteAddress();
    }

    /**
     * Returns the local address to which the socket is bound.
     * 
     * @return ip address of the remote side of the connection or null if not
     *         connected
     */
    @Override
    SocketAddress getLocalSocketAddress() {
        if (channel == null) {
            return null;
        }

        return channel.getLocalAddress();
    }

    @Override
    void cleanup() {
        if (channel != null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("cleanup closing sessionId:0x"
                        + Long.toHexString(sessionId));
            }
            channel.close().awaitUninterruptibly();
        }
        channel = null;
        if (factory != null) {
            factory.releaseExternalResources();
        }
        sendThread.cleanup();
    }

    @Override
    void close() {
        // NO-OP
    }

    @Override
    void testableCloseSocket() throws IOException {
        LOG.info("testableCloseSocket() called");
        channel.disconnect().awaitUninterruptibly();
    }

    @Override
    void wakeupCnxn() {
        synchronized (outgoingQueue) {
            outgoingQueue.notifyAll();
        }
    }

    @Override
    void enableWrite() {

    }

    @Override
    void doTransport(int waitTimeOut, List<Packet> pendingQueue)
            throws EndOfStreamException {
        if (disconnected) {
            throw new EndOfStreamException("connection for sessionid 0x"
                    + Long.toHexString(sessionId)
                    + " lost, likely server has closed socket");

        }

        // channel may not have been connected yet
        if (isConnected() && doWrites(pendingQueue)) {
            updateLastSend();
        }

        if (sendThread.getZkState().isAlive()) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("WAIT to=" + waitTimeOut + " sessionId:0x"
                        + Long.toHexString(sessionId));
            }
            try {
                synchronized (outgoingQueue) {
                    outgoingQueue.wait(waitTimeOut);
                }
            } catch (InterruptedException e) {
                LOG.trace("WOKE via interrupt sessionId:0x"
                        + Long.toHexString(sessionId));
            } finally {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("WOKE sessionId:0x" + Long.toHexString(sessionId));
                }
            }

        }
        // Everything below and until we get back to the wait is
        // non blocking, so time is effectively a constant. That is
        // Why we just have to do this once, here
        updateNow();
    }

    private class ZKClientHandler extends SimpleChannelHandler {

        @Override
        public void channelDisconnected(ChannelHandlerContext ctx,
                ChannelStateEvent e) throws Exception {
            disconnected = true;
            wakeupCnxn();
        }

        @Override
        public void channelConnected(ChannelHandlerContext ctx,
                ChannelStateEvent e) throws Exception {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Channel connected " + e);
            }
            channel = ctx.getChannel();

            long now = System.currentTimeMillis();

            lastHeard = now;
            lastSend = now;

            sendThread.primeConnection();

            initialized = false;

            /*
             * Reset incomingBuffer
             */
            lenBuffer.clear();
            incomingBuffer = lenBuffer;

            wakeupCnxn();
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
                throws IOException {
            lastHeard = System.currentTimeMillis();

            ChannelBuffer buf = (ChannelBuffer) e.getMessage();
            while (buf.readable()) {
                if (incomingBuffer.remaining() > buf.readableBytes()) {
                    int newLimit = incomingBuffer.position()
                            + buf.readableBytes();
                    incomingBuffer.limit(newLimit);
                }
                buf.readBytes(incomingBuffer);
                incomingBuffer.limit(incomingBuffer.capacity());

                if (!incomingBuffer.hasRemaining()) {
                    incomingBuffer.flip();
                    if (incomingBuffer == lenBuffer) {
                        recvCount++;
                        readLength();
                    } else if (!initialized) {
                        readConnectResult();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        initialized = true;
                    } else {
                        synchronized (outgoingQueue) {
                            sendThread.readResponse(incomingBuffer);                            
                        }

                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                    }
                }
            }
        }

        @Override
        public void channelInterestChanged(ChannelHandlerContext ctx,
                ChannelStateEvent e) {
            if (e.getState() == ChannelState.INTEREST_OPS) {
                // handle the case where OP_WRITE changes
                wakeupCnxn();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            LOG.warn("Exception caught " + e, e.getCause());
        }
    }
}
