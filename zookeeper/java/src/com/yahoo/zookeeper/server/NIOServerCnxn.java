/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import com.yahoo.jute.BinaryInputArchive;
import com.yahoo.jute.BinaryOutputArchive;
import com.yahoo.jute.Record;
import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooDefs.OpCode;
import com.yahoo.zookeeper.data.Id;
import com.yahoo.zookeeper.proto.AuthPacket;
import com.yahoo.zookeeper.proto.ConnectRequest;
import com.yahoo.zookeeper.proto.ConnectResponse;
import com.yahoo.zookeeper.proto.ReplyHeader;
import com.yahoo.zookeeper.proto.RequestHeader;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.auth.AuthenticationProvider;
import com.yahoo.zookeeper.server.quorum.FollowerHandler;
import com.yahoo.zookeeper.server.quorum.QuorumPeer;

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 */
public class NIOServerCnxn implements Watcher, ServerCnxn {
    static public class Factory extends Thread {
        ZooKeeperServer zks;

        ServerSocketChannel ss;

        Selector selector = Selector.open();

        int packetsSent;

        int packetsReceived;

        HashSet<NIOServerCnxn> cnxns = new HashSet<NIOServerCnxn>();

        QuorumPeer self;

        long avgLatency;

        long maxLatency;

        long minLatency = 99999999;

        int outstandingLimit = 1;

        void setStats(long latency, long avg) {
            this.avgLatency = avg;
            if (latency < minLatency) {
                minLatency = latency;
            }
            if (latency > maxLatency) {
                maxLatency = latency;
            }
        }

        public Factory(int port) throws IOException {
            super("NIOServerCxn.Factory");
            setDaemon(true);
            this.ss = ServerSocketChannel.open();
            ss.socket().bind(new InetSocketAddress(port));
            ss.configureBlocking(false);
            ss.register(selector, SelectionKey.OP_ACCEPT);
            start();
        }

        public Factory(int port, QuorumPeer self) throws IOException {
            this(port);
            this.self = self;
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

        public void run() {
            while (!ss.socket().isClosed()) {
                try {
                    selector.select(1000);
                    Set<SelectionKey> selected;
                    synchronized (this) {
                        selected = selector.selectedKeys();
                    }
                    for (SelectionKey k : selected) {
                        if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                            SocketChannel sc = ((ServerSocketChannel) k
                                    .channel()).accept();
                            sc.configureBlocking(false);
                            SelectionKey sk = sc.register(selector,
                                    SelectionKey.OP_READ);
                            NIOServerCnxn cnxn = new NIOServerCnxn(zks, sc, sk,
                                    this);
                            sk.attach(cnxn);
                            addCnxn(cnxn);
                        } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                            NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                            c.doIO(k);
                        }
                    }
                    selected.clear();
                } catch (Exception e) {
                    ZooLog.logException(e);
                }
            }
            ZooLog.logTextTraceMessage("NIOServerCnxn factory exitedloop.",
                    ZooLog.textTraceMask);
            clear();
            ZooLog.logError("=====> Goodbye cruel world <======");
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
            } catch (Exception e) {
                ZooLog.logException(e);
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

    int packetsSent;

    int packetsReceived;

    ArrayList<Id> authInfo = new ArrayList<Id>();

    LinkedList<Request> outstanding = new LinkedList<Request>();

    void sendBuffer(ByteBuffer bb) {
        synchronized (factory) {
            try {
                sk.selector().wakeup();
                // ZooLog.logTextTraceMessage("Add a buffer to outgoingBuffers",
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK);
                // ZooLog.logTextTraceMessage("sk " + sk + " is valid: " +
                // sk.isValid(), ZooLog.CLIENT_DATA_PACKET_TRACE_MASK);
                outgoingBuffers.add(bb);
                if (sk.isValid()) {
                    sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                }
            } catch (RuntimeException e) {
                ZooLog.logException(e);
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
                        packetsReceived++;
                        factory.packetsReceived++;
                        readConnectRequest();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                    } else {
                        packetsReceived++;
                        factory.packetsReceived++;
                        readRequest();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                    }
                }
            }
            if (k.isWritable()) {
                // ZooLog.logTextTraceMessage("outgoingBuffers.size() = " +
                // outgoingBuffers.size(),
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK);
                if (outgoingBuffers.size() > 0) {
                    // ZooLog.logTextTraceMessage("sk " + k + " is valid: " +
                    // k.isValid(), ZooLog.CLIENT_DATA_PACKET_TRACE_MASK);
                    ByteBuffer bbs[] = outgoingBuffers
                            .toArray(new ByteBuffer[0]);
                    // Write as much as we can
                    long i = sock.write(bbs);
                    ByteBuffer bb;
                    // Remove the buffers that we have sent
                    while (outgoingBuffers.size() > 0
                            && (bb = outgoingBuffers.peek()).remaining() == 0) {
                        if (bb == closeConn) {
                            throw new IOException("closing");
                        }
                        if (bb.remaining() > 0) {
                            break;
                        }
                        packetsSent++;
                        factory.packetsSent++;
                        outgoingBuffers.remove();
                    }
                    // ZooLog.logTextTraceMessage("after send,
                    // outgoingBuffers.size() = " + outgoingBuffers.size(),
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK);
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
                        sk
                                .interestOps(sk.interestOps()
                                        | SelectionKey.OP_WRITE);
                    }
                }
            }
        } catch (CancelledKeyException e) {
            close();
        } catch (IOException e) {
            // ZooLog.logException(e);
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
            AuthenticationProvider ap = zk.authenticationProviders.get(scheme);
            if (ap == null
                    || ap.handleAuthentication(this, authPacket.getAuth()) != KeeperException.Code.Ok) {
                if (ap == null)
                    ZooLog.logError("No authentication provider for scheme: "
                            + scheme);
                else
                    ZooLog.logError("Authentication failed for scheme: "
                            + scheme);
                // send a response...
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.AuthFailed);
                sendResponse(rh, null, null);
                // ... and close connection
                sendBuffer(NIOServerCnxn.closeConn);
                disableRecv();
            } else {
                ZooLog.logError("Authentication succeeded for scheme: "
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
        ZooLog.logWarn("Connected to " + sock.socket().getRemoteSocketAddress()
                + " lastZxid " + connReq.getLastZxidSeen());
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        if (connReq.getLastZxidSeen() > zk.dataTree.lastProcessedZxid) {
            ZooLog.logError("Client has seen "
                    + Long.toHexString(connReq.getLastZxidSeen())
                    + " our last zxid is "
                    + Long.toHexString(zk.dataTree.lastProcessedZxid));
            throw new IOException("We are out of date");
        }
        sessionTimeout = connReq.getTimeOut();
        sessionId = connReq.getSessionId();
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
        if (sessionId != 0) {
            zk.reopenSession(this, sessionId, passwd, sessionTimeout);
            ZooLog.logWarn("Renewing session " + Long.toHexString(sessionId));
        } else {
            zk.createSession(this, passwd, sessionTimeout);
            ZooLog.logWarn("Creating new session "
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
                long traceMask = ZooLog.getTextTraceLevel();
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
                ZooLog.setTextTraceLevel(traceMask);
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
                sb.append("Clients:\n");
                for (SelectionKey sk : factory.selector.keys()) {
                    Channel channel = sk.channel();
                    if (channel instanceof SocketChannel) {
                        NIOServerCnxn cnxn = (NIOServerCnxn) sk.attachment();
                        sb.append(" "
                                + ((SocketChannel) channel).socket()
                                        .getRemoteSocketAddress() + "["
                                + Integer.toHexString(sk.interestOps())
                                + "](queued=" + cnxn.outstandingRequests
                                + ",recved=" + cnxn.packetsReceived + ",sent="
                                + cnxn.packetsSent + ")\n");
                    }
                }
                sb.append("\n");
                sb.append("Latency min/avg/max: " + factory.minLatency + "/"
                        + factory.avgLatency + "/" + factory.maxLatency + "\n");
                sb.append("Received: " + factory.packetsReceived + "\n");
                sb.append("Sent: " + factory.packetsSent + "\n");
                if (zk != null) {
                    sb.append("Outstanding: " + zk.getInProcess() + "\n");
                    sb.append("Zxid: "
                            + Long.toHexString(zk.dataTree.lastProcessedZxid)
                            + "\n");
                }
                // sb.append("Done: " + ZooKeeperServer.getRequests() + "\n");
                if (factory.self == null) {
                    sb.append("Mode: standalone\n");
                } else {
                    switch (factory.self.state) {
                    case LOOKING:
                        sb.append("Mode: leaderelection\n");
                        break;
                    case LEADING:
                        sb.append("Mode: leading\n");
                        sb.append("Followers:");
                        for (FollowerHandler fh : factory.self.leader.followers) {
                            if (fh.s == null) {
                                continue;
                            }
                            sb.append(" ");
                            sb.append(fh.s.getRemoteSocketAddress());
                            if (factory.self.leader.forwardingFollowers
                                    .contains(fh)) {
                                sb.append("*");
                            }
                        }
                        sb.append("\n");
                        break;
                    case FOLLOWING:
                        sb.append("Mode: following\n");
                        sb.append("Leader: ");
                        Socket s = factory.self.follower.sock;
                        if (s == null) {
                            sb.append("not connected\n");
                        } else {
                            sb.append(s.getRemoteSocketAddress() + "\n");
                        }
                    }
                }
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
     * @see com.yahoo.zookeeper.server.ServerCnxnIface#getSessionTimeout()
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
     * @see com.yahoo.zookeeper.server.ServerCnxnIface#close()
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

        ZooLog.logTextTraceMessage("close  NIOServerCnxn: " + sock,
                ZooLog.SESSION_TRACE_MASK);
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
            ZooLog.logException(e);
        }
        try {
            sock.close();
            // XXX The next line doesn't seem to be needed, but some posts
            // to forums suggest that it is needed. Keep in mind if errors in
            // this section arise.
            // factory.selector.wakeup();
        } catch (IOException e) {
            ZooLog.logException(e);
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
     * @see com.yahoo.zookeeper.server.ServerCnxnIface#sendResponse(com.yahoo.zookeeper.proto.ReplyHeader,
     *      com.yahoo.jute.Record, java.lang.String)
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
            ZooLog.logError("Error serializing response");
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
     * @see com.yahoo.zookeeper.server.ServerCnxnIface#process(com.yahoo.zookeeper.proto.WatcherEvent)
     */
    synchronized public void process(WatcherEvent event) {
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        ZooLog.logTextTraceMessage("Deliver event " + event + " to "
                + this.sessionId + " through " + this,
                ZooLog.EVENT_DELIVERY_TRACE_MASK);
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
            ZooLog.logWarn("Finished init of " + Long.toHexString(sessionId)
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
            ZooLog.logException(e);
            close();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.yahoo.zookeeper.server.ServerCnxnIface#getSessionId()
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

    public void setStats(long latency, long avg) {
        factory.setStats(latency, avg);
    }
}
