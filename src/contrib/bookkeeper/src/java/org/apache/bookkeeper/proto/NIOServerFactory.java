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

package org.apache.bookkeeper.proto;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

/**
 * This class handles communication with clients using NIO. There is one Cnxn
 * per client, but only one thread doing the communication.
 */
public class NIOServerFactory extends Thread {

    public interface PacketProcessor {
        public void processPacket(ByteBuffer packet, Cnxn src);
    }
    
    ServerStats stats = new ServerStats();

    Logger LOG = Logger.getLogger(NIOServerFactory.class);

    ServerSocketChannel ss;

    Selector selector = Selector.open();

    /**
     * We use this buffer to do efficient socket I/O. Since there is a single
     * sender thread per NIOServerCnxn instance, we can use a member variable to
     * only allocate it once.
     */
    ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);

    HashSet<Cnxn> cnxns = new HashSet<Cnxn>();

    int outstandingLimit = 2000;

    PacketProcessor processor;

    long minLatency = 99999999;

    public NIOServerFactory(int port, PacketProcessor processor) throws IOException {
        super("NIOServerFactory");
        setDaemon(true);
        this.processor = processor;
        this.ss = ServerSocketChannel.open();
        ss.socket().bind(new InetSocketAddress(port));
        ss.configureBlocking(false);
        ss.register(selector, SelectionKey.OP_ACCEPT);
        start();
    }

    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) ss.socket().getLocalSocketAddress();
    }

    private void addCnxn(Cnxn cnxn) {
        synchronized (cnxns) {
            cnxns.add(cnxn);
        }
    }

    @Override
    public void run() {
        while (!ss.socket().isClosed()) {
            try {
                selector.select(1000);
                Set<SelectionKey> selected;
                synchronized (this) {
                    selected = selector.selectedKeys();
                }
                ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(selected);
                Collections.shuffle(selectedList);
                for (SelectionKey k : selectedList) {
                    if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                        SocketChannel sc = ((ServerSocketChannel) k.channel()).accept();
                        sc.configureBlocking(false);
                        SelectionKey sk = sc.register(selector, SelectionKey.OP_READ);
                        Cnxn cnxn = new Cnxn(sc, sk);
                        sk.attach(cnxn);
                        addCnxn(cnxn);
                    } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                        Cnxn c = (Cnxn) k.attachment();
                        c.doIO(k);
                    }
                }
                selected.clear();
            } catch (Exception e) {
                LOG.warn(e);
            }
        }
        LOG.debug("NIOServerCnxn factory exitedloop.");
        clear();
        // System.exit(0);
    }

    /**
     * clear all the connections in the selector
     * 
     */
    synchronized public void clear() {
        selector.wakeup();
        synchronized (cnxns) {
            // got to clear all the connections that we have in the selector
            for (Iterator<Cnxn> it = cnxns.iterator(); it.hasNext();) {
                Cnxn cnxn = it.next();
                it.remove();
                try {
                    cnxn.close();
                } catch (Exception e) {
                    // Do nothing.
                }
            }
        }

    }

    public void shutdown() {
        try {
            ss.close();
            clear();
            this.interrupt();
            this.join();
        } catch (InterruptedException e) {
            LOG.warn("Interrupted", e);
        } catch (Exception e) {
            LOG.error("Unexpected exception", e);
        }
    }

    /**
     * The buffer will cause the connection to be close when we do a send.
     */
    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    public class Cnxn {

        private SocketChannel sock;

        private SelectionKey sk;

        boolean initialized;

        ByteBuffer lenBuffer = ByteBuffer.allocate(4);

        ByteBuffer incomingBuffer = lenBuffer;

        LinkedBlockingQueue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();

        int sessionTimeout;

        int packetsSent;

        int packetsReceived;

        void doIO(SelectionKey k) throws InterruptedException {
            try {
                if (sock == null) {
                    return;
                }
                if (k.isReadable()) {
                    int rc = sock.read(incomingBuffer);
                    if (rc < 0) {
                        throw new IOException("Read error");
                    }
                    if (incomingBuffer.remaining() == 0) {
                        incomingBuffer.flip();
                        if (incomingBuffer == lenBuffer) {
                            readLength(k);
                        } else {
                            cnxnStats.packetsReceived++;
                            stats.incrementPacketsReceived();
                            try {
                                readRequest();
                            } finally {
                                lenBuffer.clear();
                                incomingBuffer = lenBuffer;
                            }
                        }
                    }
                }
                if (k.isWritable()) {
                    if (outgoingBuffers.size() > 0) {
                        // ZooLog.logTraceMessage(LOG,
                        // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                        // "sk " + k + " is valid: " +
                        // k.isValid());

                        /*
                         * This is going to reset the buffer position to 0 and
                         * the limit to the size of the buffer, so that we can
                         * fill it with data from the non-direct buffers that we
                         * need to send.
                         */
                        directBuffer.clear();

                        for (ByteBuffer b : outgoingBuffers) {
                            if (directBuffer.remaining() < b.remaining()) {
                                /*
                                 * When we call put later, if the directBuffer
                                 * is to small to hold everything, nothing will
                                 * be copied, so we've got to slice the buffer
                                 * if it's too big.
                                 */
                                b = (ByteBuffer) b.slice().limit(directBuffer.remaining());
                            }
                            /*
                             * put() is going to modify the positions of both
                             * buffers, put we don't want to change the position
                             * of the source buffers (we'll do that after the
                             * send, if needed), so we save and reset the
                             * position after the copy
                             */
                            int p = b.position();
                            directBuffer.put(b);
                            b.position(p);
                            if (directBuffer.remaining() == 0) {
                                break;
                            }
                        }
                        /*
                         * Do the flip: limit becomes position, position gets
                         * set to 0. This sets us up for the write.
                         */
                        directBuffer.flip();

                        int sent = sock.write(directBuffer);
                        ByteBuffer bb;

                        // Remove the buffers that we have sent
                        while (outgoingBuffers.size() > 0) {
                            bb = outgoingBuffers.peek();
                            if (bb == closeConn) {
                                throw new IOException("closing");
                            }
                            int left = bb.remaining() - sent;
                            if (left > 0) {
                                /*
                                 * We only partially sent this buffer, so we
                                 * update the position and exit the loop.
                                 */
                                bb.position(bb.position() + sent);
                                break;
                            }
                            cnxnStats.packetsSent++;
                            /* We've sent the whole buffer, so drop the buffer */
                            sent -= bb.remaining();
                            ServerStats.getInstance().incrementPacketsSent();
                            outgoingBuffers.remove();
                        }
                        // ZooLog.logTraceMessage(LOG,
                        // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK, "after send,
                        // outgoingBuffers.size() = " + outgoingBuffers.size());
                    }
                    synchronized (this) {
                        if (outgoingBuffers.size() == 0) {
                            if (!initialized && (sk.interestOps() & SelectionKey.OP_READ) == 0) {
                                throw new IOException("Responded to info probe");
                            }
                            sk.interestOps(sk.interestOps() & (~SelectionKey.OP_WRITE));
                        } else {
                            sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                }
            } catch (CancelledKeyException e) {
                close();
            } catch (IOException e) {
                // LOG.error("FIXMSG",e);
                close();
            }
        }

        private void readRequest() throws IOException {
            incomingBuffer = incomingBuffer.slice();
            processor.processPacket(incomingBuffer, this);
        }

        public void disableRecv() {
            sk.interestOps(sk.interestOps() & (~SelectionKey.OP_READ));
        }

        public void enableRecv() {
            if (sk.isValid()) {
                int interest = sk.interestOps();
                if ((interest & SelectionKey.OP_READ) == 0) {
                    sk.interestOps(interest | SelectionKey.OP_READ);
                }
            }
        }

        private void readLength(SelectionKey k) throws IOException {
            // Read the length, now get the buffer
            int len = lenBuffer.getInt();
            if (len < 0 || len > 0xfffff) {
                throw new IOException("Len error " + len);
            }
            incomingBuffer = ByteBuffer.allocate(len);
        }

        /**
         * The number of requests that have been submitted but not yet responded
         * to.
         */
        int outstandingRequests;

        /*
         * (non-Javadoc)
         * 
         * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionTimeout()
         */
        public int getSessionTimeout() {
            return sessionTimeout;
        }

        String peerName;

        public Cnxn(SocketChannel sock, SelectionKey sk) throws IOException {
            this.sock = sock;
            this.sk = sk;
            sock.socket().setTcpNoDelay(true);
            sock.socket().setSoLinger(true, 2);
            sk.interestOps(SelectionKey.OP_READ);
            if (LOG.isTraceEnabled()) {
                peerName = sock.socket().toString();
            }

            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }

        @Override
        public String toString() {
            return "NIOServerCnxn object with sock = " + sock + " and sk = " + sk;
        }

        boolean closed;

        /*
         * (non-Javadoc)
         * 
         * @see org.apache.zookeeper.server.ServerCnxnIface#close()
         */
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            synchronized (cnxns) {
                cnxns.remove(this);
            }
            LOG.debug("close  NIOServerCnxn: " + sock);
            try {
                /*
                 * The following sequence of code is stupid! You would think
                 * that only sock.close() is needed, but alas, it doesn't work
                 * that way. If you just do sock.close() there are cases where
                 * the socket doesn't actually close...
                 */
                sock.socket().shutdownOutput();
            } catch (IOException e) {
                // This is a relatively common exception that we can't avoid
            }
            try {
                sock.socket().shutdownInput();
            } catch (IOException e) {
            }
            try {
                sock.socket().close();
            } catch (IOException e) {
                LOG.error("FIXMSG", e);
            }
            try {
                sock.close();
                // XXX The next line doesn't seem to be needed, but some posts
                // to forums suggest that it is needed. Keep in mind if errors
                // in
                // this section arise.
                // factory.selector.wakeup();
            } catch (IOException e) {
                LOG.error("FIXMSG", e);
            }
            sock = null;
            if (sk != null) {
                try {
                    // need to cancel this selection key from the selector
                    sk.cancel();
                } catch (Exception e) {
                }
            }
        }

        private void makeWritable(SelectionKey sk) {
            try {
                selector.wakeup();
                if (sk.isValid()) {
                    sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                }
            } catch (RuntimeException e) {
                LOG.error("Problem setting writable", e);
                throw e;
            }
        }

        private void sendBuffers(ByteBuffer bb[]) {
            ByteBuffer len = ByteBuffer.allocate(4);
            int total = 0;
            for (int i = 0; i < bb.length; i++) {
                if (bb[i] != null) {
                    total += bb[i].remaining();
                }
            }
            if (LOG.isTraceEnabled()) {
                LOG.debug("Sending response of size " + total + " to " + peerName);
            }
            len.putInt(total);
            len.flip();
            outgoingBuffers.add(len);
            for (int i = 0; i < bb.length; i++) {
                if (bb[i] != null) {
                    outgoingBuffers.add(bb[i]);
                }
            }
            makeWritable(sk);
        }

        synchronized public void sendResponse(ByteBuffer bb[]) {
            if (closed) {
                return;
            }
            sendBuffers(bb);
            synchronized (NIOServerFactory.this) {
                outstandingRequests--;
                // check throttling
                if (outstandingRequests < outstandingLimit) {
                    sk.selector().wakeup();
                    enableRecv();
                }
            }
        }

        public InetSocketAddress getRemoteAddress() {
            return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
        }

        private class CnxnStats {
            long packetsReceived;

            long packetsSent;

            /**
             * The number of requests that have been submitted but not yet
             * responded to.
             */
            public long getOutstandingRequests() {
                return outstandingRequests;
            }

            public long getPacketsReceived() {
                return packetsReceived;
            }

            public long getPacketsSent() {
                return packetsSent;
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                Channel channel = sk.channel();
                if (channel instanceof SocketChannel) {
                    sb.append(" ").append(((SocketChannel) channel).socket().getRemoteSocketAddress()).append("[")
                            .append(Integer.toHexString(sk.interestOps())).append("](queued=").append(
                                    getOutstandingRequests()).append(",recved=").append(getPacketsReceived()).append(
                                    ",sent=").append(getPacketsSent()).append(")\n");
                }
                return sb.toString();
            }
        }

        private CnxnStats cnxnStats = new CnxnStats();

        public CnxnStats getStats() {
            return cnxnStats;
        }
    }
}
