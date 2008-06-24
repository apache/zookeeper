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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
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
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 */
public class NIOServerCnxn implements Watcher, ServerCnxn {
    private static final Logger LOG = Logger.getLogger(NIOServerCnxn.class);

    static public class Factory extends Thread {
        ZooKeeperServer zks;

        ServerSocketChannel ss;

        Selector selector = Selector.open();

        /**
         * We use this buffer to do efficient socket I/O. Since there is a single
         * sender thread per NIOServerCnxn instance, we can use a member variable to
         * only allocate it once.
        */
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);

        HashSet<NIOServerCnxn> cnxns = new HashSet<NIOServerCnxn>();

        int outstandingLimit = 1;

        public Factory(int port) throws IOException {
            super("NIOServerCxn.Factory");
            setDaemon(true);
            this.ss = ServerSocketChannel.open();
            ss.socket().bind(new InetSocketAddress(port));
            ss.configureBlocking(false);
            ss.register(selector, SelectionKey.OP_ACCEPT);
            start();
        }

        public void startup(ZooKeeperServer zks) throws IOException,
                InterruptedException {
            zks.startup();
            setZooKeeperServer(zks);
        }

        public void setZooKeeperServer(ZooKeeperServer zks) {
            this.zks = zks;
            if (zks != null) {
                this.outstandingLimit = zks.getGlobalOutstandingLimit();
                zks.setServerCnxnFactory(this);
            } else {
                this.outstandingLimit = 1;
            }
        }

        public InetSocketAddress getLocalAddress(){
            return (InetSocketAddress)ss.socket().getLocalSocketAddress();
        }

        private void addCnxn(NIOServerCnxn cnxn) {
            synchronized (cnxns) {
                cnxns.add(cnxn);
            }
        }

        protected NIOServerCnxn createConnection(SocketChannel sock,
                SelectionKey sk) throws IOException {
            return new NIOServerCnxn(zks, sock, sk, this);
        }

        public void run() {
            while (!ss.socket().isClosed()) {
                try {
                    selector.select(1000);
                    Set<SelectionKey> selected;
                    synchronized (this) {
                        selected = selector.selectedKeys();
                    }
                    ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(
                            selected);
                    Collections.shuffle(selectedList);
                    for (SelectionKey k : selectedList) {
                        if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                            SocketChannel sc = ((ServerSocketChannel) k
                                    .channel()).accept();
                            sc.configureBlocking(false);
                            SelectionKey sk = sc.register(selector,
                                    SelectionKey.OP_READ);
                            NIOServerCnxn cnxn = createConnection(sc, sk);
                            sk.attach(cnxn);
                            addCnxn(cnxn);
                        } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                            NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                            c.doIO(k);
                        }
                    }
                    selected.clear();
                } catch (Exception e) {
                    LOG.error("FIXMSG",e);
                }
            }
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                     "NIOServerCnxn factory exitedloop.");
            clear();
            LOG.error("=====> Goodbye cruel world <======");
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
                for (Iterator<NIOServerCnxn> it = cnxns.iterator(); it
                        .hasNext();) {
                    NIOServerCnxn cnxn = it.next();
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
                LOG.warn("Interrupted",e);
            } catch (Exception e) {
                LOG.error("Unexpected exception", e);
            }
            if (zks != null) {
                zks.shutdown();
            }
        }

        synchronized void closeSession(long sessionId) {
            selector.wakeup();
            synchronized (cnxns) {
                for (Iterator<NIOServerCnxn> it = cnxns.iterator(); it
                        .hasNext();) {
                    NIOServerCnxn cnxn = it.next();
                    if (cnxn.sessionId == sessionId) {
                        it.remove();
                        try {
                            cnxn.close();
                        } catch (Exception e) {
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * The buffer will cause the connection to be close when we do a send.
     */
    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    Factory factory;

    ZooKeeperServer zk;

    private SocketChannel sock;

    private SelectionKey sk;

    boolean initialized;

    ByteBuffer lenBuffer = ByteBuffer.allocate(4);

    ByteBuffer incomingBuffer = lenBuffer;

    LinkedBlockingQueue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();

    int sessionTimeout;

    ArrayList<Id> authInfo = new ArrayList<Id>();

    LinkedList<Request> outstanding = new LinkedList<Request>();

    void sendBuffer(ByteBuffer bb) {
        synchronized (factory) {
            try {
                sk.selector().wakeup();
                // ZooLog.logTraceMessage(LOG,
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                // "Add a buffer to outgoingBuffers");
                // ZooLog.logTraceMessage(LOG,
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                //"sk " + sk + " is valid: " +
                // sk.isValid(), );
                outgoingBuffers.add(bb);
                if (sk.isValid()) {
                    sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                }
            } catch (RuntimeException e) {
                LOG.error("FIXMSG",e);
                throw e;
            }
        }
    }

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
                    } else if (!initialized) {
                        stats.packetsReceived++;
                        ServerStats.getInstance().incrementPacketsReceived();
                        readConnectRequest();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                    } else {
                        stats.packetsReceived++;
                        ServerStats.getInstance().incrementPacketsReceived();
                        readRequest();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                    }
                }
            }
            if (k.isWritable()) {
                // ZooLog.logTraceMessage(LOG,
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK
                // "outgoingBuffers.size() = " +
                // outgoingBuffers.size());
                if (outgoingBuffers.size() > 0) {
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                    // "sk " + k + " is valid: " +
                    // k.isValid());

                    /*
                     * This is going to reset the buffer position to 0 and the
                     * limit to the size of the buffer, so that we can fill it
                     * with data from the non-direct buffers that we need to
                     * send.
                     */
                    ByteBuffer directBuffer = factory.directBuffer;
                    directBuffer.clear();

                    for (ByteBuffer b : outgoingBuffers) {
                        if (directBuffer.remaining() < b.remaining()) {
                            /*
                             * When we call put later, if the directBuffer is to
                             * small to hold everything, nothing will be copied,
                             * so we've got to slice the buffer if it's too big.
                             */
                            b = (ByteBuffer) b.slice().limit(
                                    directBuffer.remaining());
                        }
                        /*
                         * put() is going to modify the positions of both
                         * buffers, put we don't want to change the position of
                         * the source buffers (we'll do that after the send, if
                         * needed), so we save and reset the position after the
                         * copy
                         */
                        int p = b.position();
                        directBuffer.put(b);
                        b.position(p);
                        if (directBuffer.remaining() == 0) {
                            break;
                        }
                    }
                    /*
                     * Do the flip: limit becomes position, position gets set to
                     * 0. This sets us up for the write.
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
                             * We only partially sent this buffer, so we update
                             * the position and exit the loop.
                             */
                            bb.position(bb.position() + sent);
                            break;
                        }
                        stats.packetsSent++;
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
                        if (!initialized
                                && (sk.interestOps() & SelectionKey.OP_READ) == 0) {
                            throw new IOException("Responded to info probe");
                        }
                        sk.interestOps(sk.interestOps()
                                & (~SelectionKey.OP_WRITE));
                    } else {
                        sk.interestOps(sk.interestOps()
                                        | SelectionKey.OP_WRITE);
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
        // We have the request, now process and setup for next
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");
        // Through the magic of byte buffers, txn will not be
        // pointing
        // to the start of the txn
        incomingBuffer = incomingBuffer.slice();
        if (h.getType() == OpCode.auth) {
            AuthPacket authPacket = new AuthPacket();
            ZooKeeperServer.byteBuffer2Record(incomingBuffer, authPacket);
            String scheme = authPacket.getScheme();
            AuthenticationProvider ap = ProviderRegistry.getProvider(scheme);
            if (ap == null
                    || ap.handleAuthentication(this, authPacket.getAuth()) != KeeperException.Code.Ok) {
                if (ap == null)
                    LOG.error("No authentication provider for scheme: "
                            + scheme);
                else
                    LOG.error("Authentication failed for scheme: "
                            + scheme);
                // send a response...
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.AuthFailed);
                sendResponse(rh, null, null);
                // ... and close connection
                sendBuffer(NIOServerCnxn.closeConn);
                disableRecv();
            } else {
                LOG.error("Authentication succeeded for scheme: "
                        + scheme);
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.Ok);
                sendResponse(rh, null, null);
            }
            return;
        } else {
            zk.submitRequest(this, sessionId, h.getType(), h.getXid(),
                    incomingBuffer, authInfo);
        }
        if (h.getXid() >= 0) {
            synchronized (this) {
                outstandingRequests++;
                // check throttling
                if (zk.getInProcess() > factory.outstandingLimit) {
                    disableRecv();
                    // following lines should not be needed since we are already
                    // reading
                    // } else {
                    // enableRecv();
                }
            }
        }
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

    private void readConnectRequest() throws IOException, InterruptedException {
        BinaryInputArchive bia = BinaryInputArchive
                .getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        connReq.deserialize(bia, "connect");
        LOG.warn("Connected to " + sock.socket().getRemoteSocketAddress()
                + " lastZxid " + connReq.getLastZxidSeen());
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        if (connReq.getLastZxidSeen() > zk.dataTree.lastProcessedZxid) {
            LOG.error("Client has seen "
                    + Long.toHexString(connReq.getLastZxidSeen())
                    + " our last zxid is "
                    + Long.toHexString(zk.dataTree.lastProcessedZxid));
            throw new IOException("We are out of date");
        }
        sessionTimeout = connReq.getTimeOut();
        byte passwd[] = connReq.getPasswd();
        if (sessionTimeout < zk.tickTime * 2) {
            sessionTimeout = zk.tickTime * 2;
        }
        if (sessionTimeout > zk.tickTime * 20) {
            sessionTimeout = zk.tickTime * 20;
        }
        // We don't want to receive any packets until we are sure that the
        // session is setup
        disableRecv();
        if (connReq.getSessionId() != 0) {
            setSessionId(connReq.getSessionId());
            zk.reopenSession(this, sessionId, passwd, sessionTimeout);
            LOG.warn("Renewing session " + Long.toHexString(sessionId));
        } else {
            zk.createSession(this, passwd, sessionTimeout);
            LOG.warn("Creating new session "
                    + Long.toHexString(sessionId));
        }
        initialized = true;
    }

    private void readLength(SelectionKey k) throws IOException {
        // Read the length, now get the buffer
        int len = lenBuffer.getInt();
        if (!initialized) {
            // We take advantage of the limited size of the length to look
            // for cmds. They are all 4-bytes which fits inside of an int
            if (len == ruokCmd) {
                sendBuffer(imok.duplicate());
                sendBuffer(NIOServerCnxn.closeConn);
                k.interestOps(SelectionKey.OP_WRITE);
                return;
            } else if (len == killCmd) {
                System.exit(0);
            } else if (len == getTraceMaskCmd) {
                long traceMask = ZooTrace.getTextTraceLevel();
                ByteBuffer resp = ByteBuffer.allocate(8);
                resp.putLong(traceMask);
                resp.flip();
                sendBuffer(resp);
                sendBuffer(NIOServerCnxn.closeConn);
                k.interestOps(SelectionKey.OP_WRITE);
                return;
            } else if (len == setTraceMaskCmd) {
                incomingBuffer = ByteBuffer.allocate(8);

                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new IOException("Read error");
                }
                System.out.println("rc=" + rc);
                incomingBuffer.flip();
                long traceMask = incomingBuffer.getLong();
                ZooTrace.setTextTraceLevel(traceMask);
                ByteBuffer resp = ByteBuffer.allocate(8);
                resp.putLong(traceMask);
                resp.flip();
                sendBuffer(resp);
                sendBuffer(NIOServerCnxn.closeConn);
                k.interestOps(SelectionKey.OP_WRITE);
                return;
            } else if (len == dumpCmd) {
                if (zk == null) {
                    sendBuffer(ByteBuffer.wrap("ZooKeeper not active \n"
                            .getBytes()));
                } else {
                    StringBuffer sb = new StringBuffer();
                    sb.append("SessionTracker dump: \n");
                    sb.append(zk.sessionTracker.toString()).append("\n");
                    sb.append("ephemeral nodes dump:\n");
                    sb.append(zk.dataTree.dumpEphemerals()).append("\n");
                    sendBuffer(ByteBuffer.wrap(sb.toString().getBytes()));
                }
                k.interestOps(SelectionKey.OP_WRITE);
                return;
            } else if (len == reqsCmd) {
                StringBuffer sb = new StringBuffer();
                sb.append("Requests:\n");
                synchronized (outstanding) {
                    for (Request r : outstanding) {
                        sb.append(r.toString());
                        sb.append('\n');
                    }
                }
                sendBuffer(ByteBuffer.wrap(sb.toString().getBytes()));
                k.interestOps(SelectionKey.OP_WRITE);
                return;
            } else if (len == statCmd) {
                StringBuffer sb = new StringBuffer();
                if(zk!=null){
                    sb.append("Zookeeper version: ").append(Version.getFullVersion())
                        .append("\n");
                    sb.append("Clients:\n");
                    synchronized(factory.cnxns){
                        for(NIOServerCnxn c : factory.cnxns){
                            sb.append(c.getStats().toString());
                        }
                    }
                    sb.append("\n");
                    sb.append(ServerStats.getInstance().toString());
                    sb.append("Node count: ").append(zk.dataTree.getNodeCount()).
                        append("\n");
                }else
                    sb.append("ZooKeeperServer not running\n");

                sendBuffer(ByteBuffer.wrap(sb.toString().getBytes()));
                k.interestOps(SelectionKey.OP_WRITE);
                return;
            }
        }
        if (len < 0 || len > BinaryInputArchive.maxBuffer) {
            throw new IOException("Len error " + len);
        }
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        incomingBuffer = ByteBuffer.allocate(len);
    }

    /**
     * The number of requests that have been submitted but not yet responded to.
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

    /**
     * This is the id that uniquely identifies the session of a client. Once
     * this session is no longer active, the ephemeral nodes will go away.
     */
    long sessionId;

    static long nextSessionId = 1;

    public NIOServerCnxn(ZooKeeperServer zk, SocketChannel sock,
            SelectionKey sk, Factory factory) throws IOException {
        this.zk = zk;
        this.sock = sock;
        this.sk = sk;
        this.factory = factory;
        sock.socket().setTcpNoDelay(true);
        sock.socket().setSoLinger(true, 2);
        InetAddress addr = ((InetSocketAddress) sock.socket()
                .getRemoteSocketAddress()).getAddress();
        authInfo.add(new Id("ip", addr.getHostAddress()));
        authInfo.add(new Id("host", addr.getCanonicalHostName()));
        sk.interestOps(SelectionKey.OP_READ);
    }

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
        synchronized (factory.cnxns) {
            factory.cnxns.remove(this);
        }
        if (zk != null) {
            zk.removeCnxn(this);
        }

        ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                                 "close  NIOServerCnxn: " + sock);
        try {
            /*
             * The following sequence of code is stupid! You would think that
             * only sock.close() is needed, but alas, it doesn't work that way.
             * If you just do sock.close() there are cases where the socket
             * doesn't actually close...
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
            LOG.error("FIXMSG",e);
        }
        try {
            sock.close();
            // XXX The next line doesn't seem to be needed, but some posts
            // to forums suggest that it is needed. Keep in mind if errors in
            // this section arise.
            // factory.selector.wakeup();
        } catch (IOException e) {
            LOG.error("FIXMSG",e);
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

    private final static byte fourBytes[] = new byte[4];

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#sendResponse(org.apache.zookeeper.proto.ReplyHeader,
     *      org.apache.jute.Record, java.lang.String)
     */
    synchronized public void sendResponse(ReplyHeader h, Record r, String tag) {
        if (closed) {
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
            synchronized (this.factory) {
                outstandingRequests--;
                // check throttling
                if (zk.getInProcess() < factory.outstandingLimit
                        || outstandingRequests < 1) {
                    sk.selector().wakeup();
                    enableRecv();
                }
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    synchronized public void process(WatcherEvent event) {
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                                 "Deliver event " + event + " to "
                                 + this.sessionId + " through " + this);
        sendResponse(h, event, "notification");
    }

    public void finishSessionInit(boolean valid) {
        try {
            ConnectResponse rsp = new ConnectResponse(0, valid ? sessionTimeout
                    : 0, valid ? sessionId : 0, // send 0 if session is no
                    // longer valid
                    valid ? zk.generatePasswd(sessionId) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            sendBuffer(bb);
            LOG.warn("Finished init of " + Long.toHexString(sessionId)
                    + ": " + valid);
            if (!valid) {
                sendBuffer(closeConn);
            }
            // Now that the session is ready we can start receiving packets
            synchronized (this.factory) {
                sk.selector().wakeup();
                enableRecv();
            }
        } catch (Exception e) {
            LOG.error("FIXMSG",e);
            close();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionId()
     */
    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public ArrayList<Id> getAuthInfo() {
        return authInfo;
    }

    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
    }

    private class CnxnStats implements ServerCnxn.Stats{
        long packetsReceived;
        long packetsSent;

        /**
         * The number of requests that have been submitted but not yet responded to.
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
        public String toString(){
            StringBuilder sb=new StringBuilder();
            Channel channel = sk.channel();
            if (channel instanceof SocketChannel) {
                sb.append(" ").append(((SocketChannel)channel).socket()
                                .getRemoteSocketAddress())
                  .append("[").append(Integer.toHexString(sk.interestOps()))
                  .append("](queued=").append(getOutstandingRequests())
                  .append(",recved=").append(getPacketsReceived())
                  .append(",sent=").append(getPacketsSent()).append(")\n");
            }
            return sb.toString();
        }
    }

    private CnxnStats stats=new CnxnStats();
    public Stats getStats() {
        return stats;
    }

}
