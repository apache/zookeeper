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

package org.apache.zookeeper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.AsyncCallback.ACLCallback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.ZooKeeper.WatchRegistration;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.ExistsResponse;
import org.apache.zookeeper.proto.GetACLResponse;
import org.apache.zookeeper.proto.GetChildrenResponse;
import org.apache.zookeeper.proto.GetDataResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetACLResponse;
import org.apache.zookeeper.proto.SetDataResponse;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ZooTrace;

/**
 * This class manages the socket i/o for the client. ClientCnxn maintains a list
 * of available servers to connect to and "transparently" switches servers it is
 * connected to as needed.
 *
 */
class ClientCnxn {
    private static final Logger LOG = Logger.getLogger(ClientCnxn.class);

    private ArrayList<InetSocketAddress> serverAddrs = new ArrayList<InetSocketAddress>();

    static class AuthData {
        AuthData(String scheme, byte data[]) {
            this.scheme = scheme;
            this.data = data;
        }

        String scheme;

        byte data[];
    }

    private ArrayList<AuthData> authInfo = new ArrayList<AuthData>();

    /**
     * These are the packets that have been sent and are waiting for a response.
     */
    private LinkedList<Packet> pendingQueue = new LinkedList<Packet>();

    private LinkedBlockingQueue<Object> waitingEvents = 
        new LinkedBlockingQueue<Object>();

    /**
     * These are the packets that need to be sent.
     */
    private LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

    private int nextAddrToTry = 0;

    private int connectTimeout;

    private int readTimeout;

    private final int sessionTimeout;

    private final ZooKeeper zooKeeper;

    private long sessionId;

    private byte sessionPasswd[] = new byte[16];

    final SendThread sendThread;

    final EventThread eventThread;

    final Selector selector = Selector.open();

    public long getSessionId() {
        return sessionId;
    }
    
    public byte[] getSessionPasswd() {
        return sessionPasswd;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("sessionId: 0x").append(Long.toHexString(getSessionId())).append("\n");
        sb.append("lastZxid: ").append(lastZxid).append("\n");
        sb.append("xid: ").append(xid).append("\n");
        sb.append("nextAddrToTry: ").append(nextAddrToTry).append("\n");
        sb.append("serverAddrs: ").append(serverAddrs.get(nextAddrToTry))
                .append("\n");
        return sb.toString();
    }

    /**
     * This class allows us to pass the headers and the relevant records around.
     */
    static class Packet {
        RequestHeader header;

        ByteBuffer bb;

        String path;

        ReplyHeader replyHeader;

        Record request;

        Record response;

        boolean finished;

        AsyncCallback cb;

        Object ctx;

        WatchRegistration watchRegistration;

        Packet(RequestHeader header, ReplyHeader replyHeader, Record record,
                Record response, ByteBuffer bb,
                WatchRegistration watchRegistration) {
            this.header = header;
            this.replyHeader = replyHeader;
            this.request = record;
            this.response = response;
            if (bb != null) {
                this.bb = bb;
            } else {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    BinaryOutputArchive boa = BinaryOutputArchive
                            .getArchive(baos);
                    boa.writeInt(-1, "len"); // We'll fill this in later
                    header.serialize(boa, "header");
                    if (record != null) {
                        record.serialize(boa, "request");
                    }
                    baos.close();
                    this.bb = ByteBuffer.wrap(baos.toByteArray());
                    this.bb.putInt(this.bb.capacity() - 4);
                    this.bb.rewind();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception",e);
                }
            }
            this.watchRegistration = watchRegistration;
        }
    }

    public ClientCnxn(String hosts, int sessionTimeout, ZooKeeper zooKeeper)
            throws IOException {
        this(hosts, sessionTimeout, zooKeeper, 0, new byte[16]);
    }

    /**
     * Creates a connection object. The actual network connect doesn't get
     * established until needed.
     *
     * @param hosts
     *                a comma separated list of hosts that can be connected to.
     * @param sessionTimeout
     *                the timeout for connections.
     * @param zooKeeper
     *                the zookeeper object that this connection is related to.
     * @throws KeeperException
     * @throws IOException
     */
    public ClientCnxn(String hosts, int sessionTimeout, ZooKeeper zooKeeper,
            long sessionId, byte[] sessionPasswd) throws IOException {
        this.zooKeeper = zooKeeper;
        this.sessionId = sessionId;
        this.sessionPasswd = sessionPasswd;
        String hostsList[] = hosts.split(",");
        for (String host : hostsList) {
            int port = 2181;
            String parts[] = host.split(":");
            if (parts.length > 1) {
                port = Integer.parseInt(parts[1]);
                host = parts[0];
            }
            InetAddress addrs[] = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                serverAddrs.add(new InetSocketAddress(addr, port));
            }
        }
        this.sessionTimeout = sessionTimeout;
        connectTimeout = sessionTimeout / hostsList.length;
        readTimeout = sessionTimeout * 2 / 3;
        Collections.shuffle(serverAddrs);
        sendThread = new SendThread();
        sendThread.start();
        eventThread = new EventThread();
        eventThread.start();
    }

    WatcherEvent eventOfDeath = new WatcherEvent();

    final static UncaughtExceptionHandler uncaughtExceptionHandler = new UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
            LOG.error("from " + t.getName(), e);
        }
    };

    class EventThread extends Thread {
        EventThread() {
            super("EventThread");
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }

        public void run() {
            try {
                while (true) {
                    Object event = waitingEvents.take();
                    if (event == eventOfDeath) {
                        break;
                    }
                    if (event instanceof WatcherEvent) {
                        zooKeeper.processWatchEvent((WatcherEvent) event);
                    } else {
                        Packet p = (Packet) event;
                        int rc = 0;
                        String path = p.path;
                        if (p.replyHeader.getErr() != 0) {
                            rc = p.replyHeader.getErr();
                        }
                        if (p.cb == null) {
                            LOG.warn("Somehow a null cb got to EventThread!");
                        } else if (p.response instanceof ExistsResponse
                                || p.response instanceof SetDataResponse
                                || p.response instanceof SetACLResponse) {
                            StatCallback cb = (StatCallback) p.cb;
                            if (rc == 0) {
                                if (p.response instanceof ExistsResponse) {
                                    cb.processResult(rc, path, p.ctx,
                                            ((ExistsResponse) p.response)
                                                    .getStat());
                                } else if (p.response instanceof SetDataResponse) {
                                    cb.processResult(rc, path, p.ctx,
                                            ((SetDataResponse) p.response)
                                                    .getStat());
                                } else if (p.response instanceof SetACLResponse) {
                                    cb.processResult(rc, path, p.ctx,
                                            ((SetACLResponse) p.response)
                                                    .getStat());
                                }
                            } else {
                                cb.processResult(rc, path, p.ctx, null);
                            }
                        } else if (p.response instanceof GetDataResponse) {
                            DataCallback cb = (DataCallback) p.cb;
                            GetDataResponse rsp = (GetDataResponse) p.response;
                            if (rc == 0) {
                                cb.processResult(rc, path, p.ctx,
                                        rsp.getData(), rsp.getStat());
                            } else {
                                cb.processResult(rc, path, p.ctx, null, null);
                            }
                        } else if (p.response instanceof GetACLResponse) {
                            ACLCallback cb = (ACLCallback) p.cb;
                            GetACLResponse rsp = (GetACLResponse) p.response;
                            if (rc == 0) {
                                cb.processResult(rc, path, p.ctx, rsp.getAcl(),
                                        rsp.getStat());
                            } else {
                                cb.processResult(rc, path, p.ctx, null, null);
                            }
                        } else if (p.response instanceof GetChildrenResponse) {
                            ChildrenCallback cb = (ChildrenCallback) p.cb;
                            GetChildrenResponse rsp = (GetChildrenResponse) p.response;
                            if (rc == 0) {
                                cb.processResult(rc, path, p.ctx, rsp
                                        .getChildren());
                            } else {
                                cb.processResult(rc, path, p.ctx, null);
                            }
                        } else if (p.response instanceof CreateResponse) {
                            StringCallback cb = (StringCallback) p.cb;
                            CreateResponse rsp = (CreateResponse) p.response;
                            if (rc == 0) {
                                cb
                                        .processResult(rc, path, p.ctx, rsp
                                                .getPath());
                            } else {
                                cb.processResult(rc, path, p.ctx, null);
                            }
                        } else if (p.cb instanceof VoidCallback) {
                            VoidCallback cb = (VoidCallback) p.cb;
                            cb.processResult(rc, path, p.ctx);
                        }
                    }
                }
            } catch (InterruptedException e) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void finishPacket(Packet p) {
        if (p.watchRegistration != null) {
            p.watchRegistration.register(p.replyHeader.getErr());
        }

        p.finished = true;
        if (p.cb == null) {
            synchronized (p) {
                p.notifyAll();
            }
        } else {
            waitingEvents.add(p);
        }
    }

    private void conLossPacket(Packet p) {
        if (p.replyHeader == null) {
            return;
        }
        switch(zooKeeper.state) {
        case AUTH_FAILED:
            p.replyHeader.setErr(KeeperException.Code.AuthFailed);
            break;
        case CLOSED:
            p.replyHeader.setErr(KeeperException.Code.SessionExpired);
            break;
        default:
            p.replyHeader.setErr(KeeperException.Code.ConnectionLoss);
        }
        finishPacket(p);
    }

    long lastZxid;

    /**
     * This class services the outgoing request queue and generates the heart
     * beats. It also spawns the ReadThread.
     */
    class SendThread extends Thread {
        SelectionKey sockKey;

        ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

        ByteBuffer incomingBuffer = lenBuffer;

        boolean initialized;

        void readLength() throws IOException {
            int len = incomingBuffer.getInt();
            if (len < 0 || len >= 4096 * 1024) {
                throw new IOException("Packet len" + len + " is out of range!");
            }
            incomingBuffer = ByteBuffer.allocate(len);
        }

        void readConnectResult() throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(
                    incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            ConnectResponse conRsp = new ConnectResponse();
            conRsp.deserialize(bbia, "connect");
            int sessionTimeout = conRsp.getTimeOut();
            if (sessionTimeout <= 0) {
                zooKeeper.state = States.CLOSED;
                waitingEvents.add(new WatcherEvent(Watcher.Event.EventNone,
                        Watcher.Event.KeeperStateExpired, null));
                throw new IOException("Session Expired");
            }
            readTimeout = sessionTimeout * 2 / 3;
            connectTimeout = sessionTimeout / serverAddrs.size();
            sessionId = conRsp.getSessionId();
            sessionPasswd = conRsp.getPasswd();
            waitingEvents.add(new WatcherEvent(Watcher.Event.EventNone,
                    Watcher.Event.KeeperStateSyncConnected, null));
        }

        @SuppressWarnings("unchecked")
        void readResponse() throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(
                    incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            ReplyHeader r = new ReplyHeader();

            r.deserialize(bbia, "header");
            if (r.getXid() == -2) {
                // -2 is the xid for pings
                return;
            }
            if (r.getXid() == -4) {
                // -2 is the xid for AuthPacket
                // TODO: process AuthPacket here
                return;
            }
            if (r.getXid() == -1) {
                // -1 means notification
                WatcherEvent event = new WatcherEvent();
                event.deserialize(bbia, "response");
                // System.out.println("Got an event: " + event + " for " +
                // sessionId + " through" + _cnxn);
                waitingEvents.add(event);
                return;
            }
            if (pendingQueue.size() == 0) {
                throw new IOException("Nothing in the queue, but got "
                        + r.getXid());
            }
            Packet p = null;
            synchronized (pendingQueue) {
                p = pendingQueue.remove();
            }
            /*
             * Since requests are processed in order, we better get a response
             * to the first request!
             */
            if (p.header.getXid() != r.getXid()) {
                throw new IOException("Xid out of order. Got " + r.getXid()
                        + " expected " + p.header.getXid());
            }
            p.replyHeader.setXid(r.getXid());
            p.replyHeader.setErr(r.getErr());
            p.replyHeader.setZxid(r.getZxid());
            lastZxid = r.getZxid();
            if (p.response != null && r.getErr() == 0) {
                p.response.deserialize(bbia, "response");
            }
            p.finished = true;
            finishPacket(p);
        }

        /**
         * @return true if a packet was received
         * @throws InterruptedException
         * @throws IOException
         */
        boolean doIO() throws InterruptedException, IOException {
            boolean packetReceived = false;
            SocketChannel sock = (SocketChannel) sockKey.channel();
            if (sock == null) {
                throw new IOException("Socket is null!");
            }
            if (sockKey.isReadable()) {
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new IOException("Read error rc = " + rc + " "
                            + incomingBuffer);
                }
                if (incomingBuffer.remaining() == 0) {
                    incomingBuffer.flip();
                    if (incomingBuffer == lenBuffer) {
                        readLength();
                    } else if (!initialized) {
                        readConnectResult();
                        enableRead();
                        if (outgoingQueue.size() > 0) {
                            enableWrite();
                        }
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        packetReceived = true;
                        initialized = true;
                    } else {
                        readResponse();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        packetReceived = true;
                    }
                }
            }
            if (sockKey.isWritable()) {
                synchronized (outgoingQueue) {
                    if (outgoingQueue.size() > 0) {
                        int rc = sock.write(outgoingQueue.getFirst().bb);
                        if (outgoingQueue.getFirst().bb.remaining() == 0) {
                            Packet p = outgoingQueue.removeFirst();
                            if (p.header != null
                                    && p.header.getType() != OpCode.ping
                                    && p.header.getType() != OpCode.auth) {
                                pendingQueue.add(p);
                            }
                        }
                    }
                }
            }
            if (outgoingQueue.size() == 0) {
                disableWrite();
            } else {
                enableWrite();
            }
            return packetReceived;
        }

        synchronized private void enableWrite() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_WRITE) == 0) {
                sockKey.interestOps(i | SelectionKey.OP_WRITE);
            }
        }

        synchronized private void disableWrite() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_WRITE) != 0) {
                sockKey.interestOps(i & (~SelectionKey.OP_WRITE));
            }
        }

        synchronized private void enableRead() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_READ) == 0) {
                sockKey.interestOps(i | SelectionKey.OP_READ);
            }
        }

        synchronized private void disableRead() {
            int i = sockKey.interestOps();
            if ((i & SelectionKey.OP_READ) != 0) {
                sockKey.interestOps(i & (~SelectionKey.OP_READ));
            }
        }

        SendThread() {
            super("SendThread");
            zooKeeper.state = States.CONNECTING;
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }

        private void primeConnection(SelectionKey k) throws IOException {
            LOG.info("Priming connection to "
                    + ((SocketChannel) sockKey.channel()));
            lastConnectIndex = currentConnectIndex;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid,
                    sessionTimeout, sessionId, sessionPasswd);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
            boa.writeInt(-1, "len");
            conReq.serialize(boa, "connect");
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.capacity() - 4);
            bb.rewind();
            synchronized (outgoingQueue) {
                for (AuthData id : authInfo) {
                    outgoingQueue.addFirst(new Packet(new RequestHeader(-4,
                            OpCode.auth), null, new AuthPacket(0, id.scheme,
                            id.data), null, null, null));
                }
                outgoingQueue.addFirst((new Packet(null, null, null, null, bb,
                        null)));
            }
            synchronized (this) {
                k.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
            zooKeeper.state = States.CONNECTED;
        }

        private void sendPing() {
            RequestHeader h = new RequestHeader(-2, OpCode.ping);
            queuePacket(h, null, null, null, null, null, null, null);
        }

        int lastConnectIndex = -1;

        int currentConnectIndex;

        Random r = new Random(System.nanoTime());

        private void startConnect() throws IOException {
            if (lastConnectIndex == -1) {
                // We don't want to delay the first try at a connect, so we
                // start with -1 the first time around
                lastConnectIndex = 0;
            } else {
                try {
                    Thread.sleep(r.nextInt(1000));
                } catch (InterruptedException e1) {
                    LOG.warn("Unexpected exception", e1);
                }
                if (nextAddrToTry == lastConnectIndex) {
                    try {
                        // Try not to spin too fast!
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        LOG.warn("Unexpected exception", e);
                    }
                }
            }
            zooKeeper.state = States.CONNECTING;
            currentConnectIndex = nextAddrToTry;
            InetSocketAddress addr = serverAddrs.get(nextAddrToTry);
            nextAddrToTry++;
            if (nextAddrToTry == serverAddrs.size()) {
                nextAddrToTry = 0;
            }
            SocketChannel sock;
            sock = SocketChannel.open();
            sock.configureBlocking(false);
            sock.socket().setSoLinger(false, -1);
            sock.socket().setTcpNoDelay(true);
            LOG.info("Attempting connection to server " + addr);
            sockKey = sock.register(selector, SelectionKey.OP_CONNECT);
            if (sock.connect(addr)) {
                primeConnection(sockKey);
            }
            initialized = false;

            /*
             * Reset incomingBuffer
             */
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }

        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long lastHeard = now;
            long lastSend = now;
            while (zooKeeper.state.isAlive()) {
                try {
                    if (sockKey == null) {
                        startConnect();
                        lastSend = now;
                        lastHeard = now;
                    }
                    int idleRecv = (int) (now - lastHeard);
                    int idleSend = (int) (now - lastSend);
                    int to = readTimeout - idleRecv;
                    if (zooKeeper.state != States.CONNECTED) {
                        to = connectTimeout - idleRecv;
                    }
                    if (to <= 0) {
                        throw new IOException("TIMED OUT");
                    }
                    if (zooKeeper.state == States.CONNECTED) {
                        int timeToNextPing = readTimeout/2 - idleSend;
                        if (timeToNextPing <= 0) {
                            sendPing();
                            lastSend = now;
                            enableWrite();
                        } else {
                            if (timeToNextPing < to) {
                                to = timeToNextPing;
                            }
                        }
                    }

                    selector.select(to);
                    Set<SelectionKey> selected;
                    synchronized (this) {
                        selected = selector.selectedKeys();
                    }
                    // Everything below and until we get back to the select is
                    // non blocking, so time is effectively a constant. That is
                    // Why we just have to do this once, here
                    now = System.currentTimeMillis();
                    for (SelectionKey k : selected) {
                        SocketChannel sc = ((SocketChannel) k.channel());
                        if ((k.readyOps() & SelectionKey.OP_CONNECT) != 0) {
                            if (sc.finishConnect()) {
                                lastHeard = now;
                                lastSend = now;
                                primeConnection(k);
                                LOG.info("Server connection successful");
                            }
                        } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                            if (outgoingQueue.size() > 0) {
                                // We have something to send so it's the same
                                // as if we do the send now.
                                lastSend = now;
                            }
                            if (doIO()) {
                                lastHeard = now;
                            }
                        }
                    }
                    if (zooKeeper.state == States.CONNECTED) {
                        if (outgoingQueue.size() > 0) {
                            enableWrite();
                        } else {
                            disableWrite();
                        }
                    }
                    selected.clear();
                } catch (Exception e) {
                    LOG.warn("Closing session 0x" 
                            + Long.toHexString(getSessionId()),
                            e);
                    cleanup();
                    if (zooKeeper.state.isAlive()) {
                        waitingEvents.add(new WatcherEvent(Event.EventNone,
                                Event.KeeperStateDisconnected, null));
                    }

                    now = System.currentTimeMillis();
                    lastHeard = now;
                    lastSend = now;
                }
            }
            cleanup();
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                     "SendThread exitedloop.");
        }

        private void cleanup() {
            if (sockKey != null) {
                SocketChannel sock = (SocketChannel) sockKey.channel();
                sockKey.cancel();
                try {
                    sock.socket().shutdownInput();
                } catch (IOException e2) {
                }
                try {
                    sock.socket().shutdownOutput();
                } catch (IOException e2) {
                }
                try {
                    sock.socket().close();
                } catch (IOException e1) {
                }
                try {
                    sock.close();
                } catch (IOException e1) {
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
            sockKey = null;
            synchronized (pendingQueue) {
                for (Packet p : pendingQueue) {
                    conLossPacket(p);
                }
                pendingQueue.clear();
            }
            synchronized (outgoingQueue) {
                for (Packet p : outgoingQueue) {
                    conLossPacket(p);
                }
                outgoingQueue.clear();
            }
        }

        public void close() {
            zooKeeper.state = States.CLOSED;
            synchronized (this) {
                selector.wakeup();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void close() throws IOException {
        LOG.info("Closing ClientCnxn for session: 0x" 
                + Long.toHexString(getSessionId()));

        sendThread.close();
        waitingEvents.add(eventOfDeath);
    }

    private int xid = 1;

    synchronized private int getXid() {
        return xid++;
    }

    public ReplyHeader submitRequest(RequestHeader h, Record request,
            Record response,
            WatchRegistration watchRegistration)
        throws InterruptedException
    {
        ReplyHeader r = new ReplyHeader();
        Packet packet =
            queuePacket(h, r, request, response, null, null, null,
                    watchRegistration);
        synchronized (packet) {
            while (!packet.finished) {
                packet.wait();
            }
        }
        return r;
    }

    Packet queuePacket(RequestHeader h, ReplyHeader r, Record request,
            Record response, AsyncCallback cb, String path, Object ctx,
            WatchRegistration watchRegistration)
    {
        Packet packet = null;
        synchronized (outgoingQueue) {
            if (h.getType() != OpCode.ping && h.getType() != OpCode.auth) {
                h.setXid(getXid());
            }
            packet = new Packet(h, r, request, response, null,
                    watchRegistration);
            packet.cb = cb;
            packet.ctx = ctx;
            packet.path = path;
            if (!zooKeeper.state.isAlive()) {
                conLossPacket(packet);
            } else {
                outgoingQueue.add(packet);
            }
        }
        synchronized (sendThread) {
            selector.wakeup();
        }
        return packet;
    }

    public void addAuthInfo(String scheme, byte auth[]) {
        authInfo.add(new AuthData(scheme, auth));
        if (zooKeeper.state == States.CONNECTED) {
            queuePacket(new RequestHeader(-4, OpCode.auth), null,
                    new AuthPacket(0, scheme, auth), null, null, null, null,
                    null);
        }
    }
}
