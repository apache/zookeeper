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
import java.net.SocketAddress;
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

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.AsyncCallback.ACLCallback;
import org.apache.zookeeper.AsyncCallback.Children2Callback;
import org.apache.zookeeper.AsyncCallback.ChildrenCallback;
import org.apache.zookeeper.AsyncCallback.DataCallback;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.ZooKeeper.WatchRegistration;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.CreateResponse;
import org.apache.zookeeper.proto.ExistsResponse;
import org.apache.zookeeper.proto.GetACLResponse;
import org.apache.zookeeper.proto.GetChildren2Response;
import org.apache.zookeeper.proto.GetChildrenResponse;
import org.apache.zookeeper.proto.GetDataResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.SetACLResponse;
import org.apache.zookeeper.proto.SetDataResponse;
import org.apache.zookeeper.proto.SetWatches;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.apache.zookeeper.server.ZooTrace;

/**
 * This class manages the socket i/o for the client. ClientCnxn maintains a list
 * of available servers to connect to and "transparently" switches servers it is
 * connected to as needed.
 *
 */
public class ClientCnxn {
    private static final Logger LOG = Logger.getLogger(ClientCnxn.class);

    /** This controls whether automatic watch resetting is enabled.
     * Clients automatically reset watches during session reconnect, this
     * option allows the client to turn off this behavior by setting
     * the environment variable "zookeeper.disableAutoWatchReset" to "true" */
    private static boolean disableAutoWatchReset;

    public static final int packetLen;
    static {
        // this var should not be public, but otw there is no easy way
        // to test
        disableAutoWatchReset =
            Boolean.getBoolean("zookeeper.disableAutoWatchReset");
        if (LOG.isDebugEnabled()) {
            LOG.debug("zookeeper.disableAutoWatchReset is "
                    + disableAutoWatchReset);
        }
        packetLen = Integer.getInteger("jute.maxbuffer", 4096 * 1024);
    }

    private final ArrayList<InetSocketAddress> serverAddrs =
        new ArrayList<InetSocketAddress>();

    static class AuthData {
        AuthData(String scheme, byte data[]) {
            this.scheme = scheme;
            this.data = data;
        }

        String scheme;

        byte data[];
    }

    private final ArrayList<AuthData> authInfo = new ArrayList<AuthData>();

    /**
     * These are the packets that have been sent and are waiting for a response.
     */
    private final LinkedList<Packet> pendingQueue = new LinkedList<Packet>();

    /**
     * These are the packets that need to be sent.
     */
    private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

    private int nextAddrToTry = 0;

    private int connectTimeout;

    /** The timeout in ms the client negotiated with the server. This is the 
     *  "real" timeout, not the timeout request by the client (which may
     *  have been increased/decreased by the server which applies bounds
     *  to this value.
     */
    private volatile int negotiatedSessionTimeout;

    private int readTimeout;

    private final int sessionTimeout;

    private final ZooKeeper zooKeeper;

    private final ClientWatchManager watcher;

    private long sessionId;

    private byte sessionPasswd[] = new byte[16];

    final String chrootPath;

    final SendThread sendThread;

    final EventThread eventThread;

    final Selector selector = Selector.open();

    /**
     * Set to true when close is called. Latches the connection such that we
     * don't attempt to re-connect to the server if in the middle of closing the
     * connection (client sends session disconnect to server as part of close
     * operation)
     */
    volatile boolean closing = false;

    public long getSessionId() {
        return sessionId;
    }

    public byte[] getSessionPasswd() {
        return sessionPasswd;
    }

    public int getSessionTimeout() {
        return negotiatedSessionTimeout;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        SocketAddress local = getLocalSocketAddress();
        SocketAddress remote = getRemoteSocketAddress();
        sb
            .append("sessionid:0x").append(Long.toHexString(getSessionId()))
            .append(" local:").append(local)
            .append(" remoteserver:").append(remote)
            .append(" lastZxid:").append(lastZxid)
            .append(" xid:").append(xid)
            .append(" sent:").append(sendThread.sentCount)
            .append(" recv:").append(sendThread.recvCount)
            .append(" queuedpkts:").append(outgoingQueue.size())
            .append(" pendingresp:").append(pendingQueue.size())
            .append(" queuedevents:").append(eventThread.waitingEvents.size());

        return sb.toString();
    }
    
    /**
     * Returns the address to which the socket is connected.
     * @return ip address of the remote side of the connection or null if
     *         not connected
     */
    SocketAddress getRemoteSocketAddress() {
        // a lot could go wrong here, so rather than put in a bunch of code
        // to check for nulls all down the chain let's do it the simple
        // yet bulletproof way
        try {
            return ((SocketChannel)sendThread.sockKey.channel())
                .socket().getRemoteSocketAddress();
        } catch (NullPointerException e) {
            return null;
        }
    }

    /** 
     * Returns the local address to which the socket is bound.
     * @return ip address of the remote side of the connection or null if
     *         not connected
     */
    SocketAddress getLocalSocketAddress() {
        // a lot could go wrong here, so rather than put in a bunch of code
        // to check for nulls all down the chain let's do it the simple
        // yet bulletproof way
        try {
            return ((SocketChannel)sendThread.sockKey.channel())
                .socket().getLocalSocketAddress();
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * This class allows us to pass the headers and the relevant records around.
     */
    static class Packet {
        RequestHeader header;

        ByteBuffer bb;

        /** Client's view of the path (may differ due to chroot) **/
        String clientPath;
        /** Servers's view of the path (may differ due to chroot) **/
        String serverPath;

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
                    LOG.warn("Ignoring unexpected exception", e);
                }
            }
            this.watchRegistration = watchRegistration;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();

            sb.append("clientPath:" + clientPath);
            sb.append(" serverPath:" + serverPath);
            sb.append(" finished:" + finished);

            sb.append(" header:: " + header);
            sb.append(" replyHeader:: " + replyHeader);
            sb.append(" request:: " + request);
            sb.append(" response:: " + response);

            // jute toString is horrible, remove unnecessary newlines
            return sb.toString().replaceAll("\r*\n+", " ");
        }
    }


    /**
     * Creates a connection object. The actual network connect doesn't get
     * established until needed. The start() instance method must be called
     * subsequent to construction.
     *
     * @param hosts
     *                a comma separated list of hosts that can be connected to.
     * @param sessionTimeout
     *                the timeout for connections.
     * @param zooKeeper
     *                the zookeeper object that this connection is related to.
     * @param watcher watcher for this connection
     * @throws IOException
     */
    public ClientCnxn(String hosts, int sessionTimeout, ZooKeeper zooKeeper,
            ClientWatchManager watcher)
        throws IOException
    {
        this(hosts, sessionTimeout, zooKeeper, watcher, 0, new byte[16]);
    }

    /**
     * Creates a connection object. The actual network connect doesn't get
     * established until needed. The start() instance method must be called
     * subsequent to construction.
     *
     * @param hosts
     *                a comma separated list of hosts that can be connected to.
     * @param sessionTimeout
     *                the timeout for connections.
     * @param zooKeeper
     *                the zookeeper object that this connection is related to.
     * @param watcher watcher for this connection
     * @param sessionId session id if re-establishing session
     * @param sessionPasswd session passwd if re-establishing session
     * @throws IOException
     */
    public ClientCnxn(String hosts, int sessionTimeout, ZooKeeper zooKeeper,
            ClientWatchManager watcher, long sessionId, byte[] sessionPasswd)
        throws IOException
    {
        this.zooKeeper = zooKeeper;
        this.watcher = watcher;
        this.sessionId = sessionId;
        this.sessionPasswd = sessionPasswd;

        // parse out chroot, if any
        int off = hosts.indexOf('/');
        if (off >= 0) {
            String chrootPath = hosts.substring(off);
            // ignore "/" chroot spec, same as null
            if (chrootPath.length() == 1) {
                this.chrootPath = null;
            } else {
                PathUtils.validatePath(chrootPath);
                this.chrootPath = chrootPath;
            }
            hosts = hosts.substring(0,  off);
        } else {
            this.chrootPath = null;
        }

        String hostsList[] = hosts.split(",");
        for (String host : hostsList) {
            int port = 2181;
            int pidx = host.lastIndexOf(':');
            if (pidx >= 0) {
                // otherwise : is at the end of the string, ignore
                if (pidx < host.length() - 1) {
                    port = Integer.parseInt(host.substring(pidx + 1));
                }
                host = host.substring(0, pidx);
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
        eventThread = new EventThread();
    }

    /**
     * tests use this to check on reset of watches
     * @return if the auto reset of watches are disabled
     */
    public static boolean getDisableAutoResetWatch() {
        return disableAutoWatchReset;
    }
    /**
     * tests use this to set the auto reset
     * @param b the vaued to set disable watches to
     */
    public static void setDisableAutoResetWatch(boolean b) {
        disableAutoWatchReset = b;
    }
    public void start() {
        sendThread.start();
        eventThread.start();
    }

    Object eventOfDeath = new Object();

    final static UncaughtExceptionHandler uncaughtExceptionHandler = new UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
            LOG.error("from " + t.getName(), e);
        }
    };

    private static class WatcherSetEventPair {
        private final Set<Watcher> watchers;
        private final WatchedEvent event;

        public WatcherSetEventPair(Set<Watcher> watchers, WatchedEvent event) {
            this.watchers = watchers;
            this.event = event;
        }
    }

    class EventThread extends Thread {
        private final LinkedBlockingQueue<Object> waitingEvents =
            new LinkedBlockingQueue<Object>();

        /** This is really the queued session state until the event
         * thread actually processes the event and hands it to the watcher.
         * But for all intents and purposes this is the state.
         */
        private volatile KeeperState sessionState = KeeperState.Disconnected;

        EventThread() {
            super(currentThread().getName() + "-EventThread");
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }

        public void queueEvent(WatchedEvent event) {
            if (event.getType() == EventType.None
                    && sessionState == event.getState()) {
                return;
            }
            sessionState = event.getState();

            // materialize the watchers based on the event
            WatcherSetEventPair pair = new WatcherSetEventPair(
                    watcher.materialize(event.getState(), event.getType(),
                            event.getPath()),
                            event);
            // queue the pair (watch set & event) for later processing
            waitingEvents.add(pair);
        }

        public void queuePacket(Packet packet) {
            waitingEvents.add(packet);
        }

        public void queueEventOfDeath() {
            waitingEvents.add(eventOfDeath);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Object event = waitingEvents.take();
                    try {
                        if (event == eventOfDeath) {
                            return;
                        }

                        if (event instanceof WatcherSetEventPair) {
                            // each watcher will process the event
                            WatcherSetEventPair pair = (WatcherSetEventPair) event;
                            for (Watcher watcher : pair.watchers) {
                                try {
                                    watcher.process(pair.event);
                                } catch (Throwable t) {
                                    LOG.error("Error while calling watcher ", t);
                                }
                            }
                        } else {
                            Packet p = (Packet) event;
                            int rc = 0;
                            String clientPath = p.clientPath;
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
                                        cb.processResult(rc, clientPath, p.ctx,
                                                ((ExistsResponse) p.response)
                                                        .getStat());
                                    } else if (p.response instanceof SetDataResponse) {
                                        cb.processResult(rc, clientPath, p.ctx,
                                                ((SetDataResponse) p.response)
                                                        .getStat());
                                    } else if (p.response instanceof SetACLResponse) {
                                        cb.processResult(rc, clientPath, p.ctx,
                                                ((SetACLResponse) p.response)
                                                        .getStat());
                                    }
                                } else {
                                    cb.processResult(rc, clientPath, p.ctx, null);
                                }
                            } else if (p.response instanceof GetDataResponse) {
                                DataCallback cb = (DataCallback) p.cb;
                                GetDataResponse rsp = (GetDataResponse) p.response;
                                if (rc == 0) {
                                    cb.processResult(rc, clientPath, p.ctx, rsp
                                            .getData(), rsp.getStat());
                                } else {
                                    cb.processResult(rc, clientPath, p.ctx, null,
                                            null);
                                }
                            } else if (p.response instanceof GetACLResponse) {
                                ACLCallback cb = (ACLCallback) p.cb;
                                GetACLResponse rsp = (GetACLResponse) p.response;
                                if (rc == 0) {
                                    cb.processResult(rc, clientPath, p.ctx, rsp
                                            .getAcl(), rsp.getStat());
                                } else {
                                    cb.processResult(rc, clientPath, p.ctx, null,
                                            null);
                                }
                            } else if (p.response instanceof GetChildrenResponse) {
                                ChildrenCallback cb = (ChildrenCallback) p.cb;
                                GetChildrenResponse rsp = (GetChildrenResponse) p.response;
                                if (rc == 0) {
                                    cb.processResult(rc, clientPath, p.ctx, rsp
                                            .getChildren());
                                } else {
                                    cb.processResult(rc, clientPath, p.ctx, null);
                                }
                            } else if (p.response instanceof GetChildren2Response) {
                                Children2Callback cb = (Children2Callback) p.cb;
                                GetChildren2Response rsp = (GetChildren2Response) p.response;
                                if (rc == 0) {
                                    cb.processResult(rc, clientPath, p.ctx, rsp
                                            .getChildren(), rsp.getStat());
                                } else {
                                    cb.processResult(rc, clientPath, p.ctx, null, null);
                                }
                            } else if (p.response instanceof CreateResponse) {
                                StringCallback cb = (StringCallback) p.cb;
                                CreateResponse rsp = (CreateResponse) p.response;
                                if (rc == 0) {
                                    cb.processResult(rc, clientPath, p.ctx,
                                            (chrootPath == null
                                                    ? rsp.getPath()
                                                    : rsp.getPath()
                                              .substring(chrootPath.length())));
                                } else {
                                    cb.processResult(rc, clientPath, p.ctx, null);
                                }
                            } else if (p.cb instanceof VoidCallback) {
                                VoidCallback cb = (VoidCallback) p.cb;
                                cb.processResult(rc, clientPath, p.ctx);
                            }
                        }
                    } catch (Throwable t) {
                        LOG.error("Caught unexpected throwable", t);
                    }
                }
            } catch (InterruptedException e) {
                LOG.error("Event thread exiting due to interruption", e);
            }

            LOG.info("EventThread shut down");
        }
    }

    private void finishPacket(Packet p) {
        if (p.watchRegistration != null) {
            p.watchRegistration.register(p.replyHeader.getErr());
        }

        if (p.cb == null) {
            synchronized (p) {
                p.finished = true;
                p.notifyAll();
            }
        } else {
            p.finished = true;
            eventThread.queuePacket(p);
        }
    }

    private void conLossPacket(Packet p) {
        if (p.replyHeader == null) {
            return;
        }
        switch(zooKeeper.state) {
        case AUTH_FAILED:
            p.replyHeader.setErr(KeeperException.Code.AUTHFAILED.intValue());
            break;
        case CLOSED:
            p.replyHeader.setErr(KeeperException.Code.SESSIONEXPIRED.intValue());
            break;
        default:
            p.replyHeader.setErr(KeeperException.Code.CONNECTIONLOSS.intValue());
        }
        finishPacket(p);
    }

    volatile long lastZxid;

    private static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -5438877188796231422L;

        public EndOfStreamException(String msg) {
            super(msg);
        }
        
        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    private static class SessionTimeoutException extends IOException {
        private static final long serialVersionUID = 824482094072071178L;

        public SessionTimeoutException(String msg) {
            super(msg);
        }
    }
    
    private static class SessionExpiredException extends IOException {
        private static final long serialVersionUID = -1388816932076193249L;

        public SessionExpiredException(String msg) {
            super(msg);
        }
    }
    
    /**
     * This class services the outgoing request queue and generates the heart
     * beats. It also spawns the ReadThread.
     */
    class SendThread extends Thread {
        SelectionKey sockKey;

        final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

        ByteBuffer incomingBuffer = lenBuffer;

        boolean initialized;

        private long lastPingSentNs;

        long sentCount = 0;
        long recvCount = 0;

        void readLength() throws IOException {
            int len = incomingBuffer.getInt();
            if (len < 0 || len >= packetLen) {
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
            negotiatedSessionTimeout = conRsp.getTimeOut();
            if (negotiatedSessionTimeout <= 0) {
                zooKeeper.state = States.CLOSED;

                eventThread.queueEvent(new WatchedEvent(
                        Watcher.Event.EventType.None,
                        Watcher.Event.KeeperState.Expired, null));
                throw new SessionExpiredException(
                        "Unable to reconnect to ZooKeeper service, session 0x"
                        + Long.toHexString(sessionId) + " has expired");
            }
            readTimeout = negotiatedSessionTimeout * 2 / 3;
            connectTimeout = negotiatedSessionTimeout / serverAddrs.size();
            sessionId = conRsp.getSessionId();
            sessionPasswd = conRsp.getPasswd();
            zooKeeper.state = States.CONNECTED;
            LOG.info("Session establishment complete on server "
                    + ((SocketChannel)sockKey.channel())
                        .socket().getRemoteSocketAddress()
                    + ", sessionid = 0x"
                    + Long.toHexString(sessionId)
                    + ", negotiated timeout = " + negotiatedSessionTimeout);
            eventThread.queueEvent(new WatchedEvent(Watcher.Event.EventType.None,
                    Watcher.Event.KeeperState.SyncConnected, null));
        }

        void readResponse() throws IOException {
            ByteBufferInputStream bbis = new ByteBufferInputStream(
                    incomingBuffer);
            BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
            ReplyHeader replyHdr = new ReplyHeader();

            replyHdr.deserialize(bbia, "header");
            if (replyHdr.getXid() == -2) {
                // -2 is the xid for pings
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got ping response for sessionid: 0x"
                            + Long.toHexString(sessionId)
                            + " after "
                            + ((System.nanoTime() - lastPingSentNs) / 1000000)
                            + "ms");
                }
                return;
            }
            if (replyHdr.getXid() == -4) {
                // -2 is the xid for AuthPacket
                // TODO: process AuthPacket here
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got auth sessionid:0x"
                            + Long.toHexString(sessionId));
                }
                return;
            }
            if (replyHdr.getXid() == -1) {
                // -1 means notification
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got notification sessionid:0x"
                        + Long.toHexString(sessionId));
                }
                WatcherEvent event = new WatcherEvent();
                event.deserialize(bbia, "response");

                // convert from a server path to a client path
                if (chrootPath != null) {
                    String serverPath = event.getPath();
                    if(serverPath.compareTo(chrootPath)==0)
                        event.setPath("/");
                    else
                        event.setPath(serverPath.substring(chrootPath.length()));
                }

                WatchedEvent we = new WatchedEvent(event);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Got " + we + " for sessionid 0x"
                            + Long.toHexString(sessionId));
                }

                eventThread.queueEvent( we );
                return;
            }
            if (pendingQueue.size() == 0) {
                throw new IOException("Nothing in the queue, but got "
                        + replyHdr.getXid());
            }
            Packet packet = null;
            synchronized (pendingQueue) {
                packet = pendingQueue.remove();
            }
            /*
             * Since requests are processed in order, we better get a response
             * to the first request!
             */
            try {
                if (packet.header.getXid() != replyHdr.getXid()) {
                    packet.replyHeader.setErr(
                            KeeperException.Code.CONNECTIONLOSS.intValue());
                    throw new IOException("Xid out of order. Got "
                            + replyHdr.getXid() + " expected "
                            + packet.header.getXid());
                }

                packet.replyHeader.setXid(replyHdr.getXid());
                packet.replyHeader.setErr(replyHdr.getErr());
                packet.replyHeader.setZxid(replyHdr.getZxid());
                if (replyHdr.getZxid() > 0) {
                    lastZxid = replyHdr.getZxid();
                }
                if (packet.response != null && replyHdr.getErr() == 0) {
                    packet.response.deserialize(bbia, "response");
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Reading reply sessionid:0x"
                            + Long.toHexString(sessionId) + ", packet:: " + packet);
                }
            } finally {
                finishPacket(packet);
            }
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
                    throw new EndOfStreamException(
                            "Unable to read additional data from server sessionid 0x"
                            + Long.toHexString(sessionId)
                            + ", likely server has closed socket");
                }
                if (!incomingBuffer.hasRemaining()) {
                    incomingBuffer.flip();
                    if (incomingBuffer == lenBuffer) {
                        recvCount++;
                        readLength();
                    } else if (!initialized) {
                        readConnectResult();
                        enableRead();
                        if (!outgoingQueue.isEmpty()) {
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
                    if (!outgoingQueue.isEmpty()) {
                        ByteBuffer pbb = outgoingQueue.getFirst().bb;
                        sock.write(pbb);
                        if (!pbb.hasRemaining()) {
                            sentCount++;
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
            if (outgoingQueue.isEmpty()) {
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
            super(currentThread().getName() + "-SendThread()");
            zooKeeper.state = States.CONNECTING;
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }

        private void primeConnection(SelectionKey k) throws IOException {
            LOG.info("Socket connection established to "
                    + ((SocketChannel)sockKey.channel())
                        .socket().getRemoteSocketAddress()
                    + ", initiating session");
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
                // We add backwards since we are pushing into the front
                // Only send if there's a pending watch
                if (!disableAutoWatchReset &&
                        (!zooKeeper.getDataWatches().isEmpty()
                         || !zooKeeper.getExistWatches().isEmpty()
                         || !zooKeeper.getChildWatches().isEmpty()))
                {
                    SetWatches sw = new SetWatches(lastZxid,
                            zooKeeper.getDataWatches(),
                            zooKeeper.getExistWatches(),
                            zooKeeper.getChildWatches());
                    RequestHeader h = new RequestHeader();
                    h.setType(ZooDefs.OpCode.setWatches);
                    h.setXid(-8);
                    Packet packet = new Packet(h, new ReplyHeader(), sw, null, null,
                                null);
                    outgoingQueue.addFirst(packet);
                }

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
            if (LOG.isDebugEnabled()) {
                LOG.debug("Session establishment request sent on "
                        + ((SocketChannel)sockKey.channel())
                            .socket().getRemoteSocketAddress());
            }
        }

        private void sendPing() {
            lastPingSentNs = System.nanoTime();
            RequestHeader h = new RequestHeader(-2, OpCode.ping);
            queuePacket(h, null, null, null, null, null, null, null, null);
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
            LOG.info("Opening socket connection to server " + addr);
            SocketChannel sock;
            sock = SocketChannel.open();
            sock.configureBlocking(false);
            sock.socket().setSoLinger(false, -1);
            sock.socket().setTcpNoDelay(true);
            setName(getName().replaceAll("\\(.*\\)",
                    "(" + addr.getHostName() + ":" + addr.getPort() + ")"));
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

        private static final String RETRY_CONN_MSG =
            ", closing socket connection and attempting reconnect";
        
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            long lastHeard = now;
            long lastSend = now;
            while (zooKeeper.state.isAlive()) {
                try {
                    if (sockKey == null) {
                        // don't re-establish connection if we are closing
                        if (closing) {
                            break;
                        }
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
                        throw new SessionTimeoutException(
                                "Client session timed out, have not heard from server in "
                                + idleRecv + "ms"
                                + " for sessionid 0x"
                                + Long.toHexString(sessionId));
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
                    if (closing) {
                        if (LOG.isDebugEnabled()) {
                            // closing so this is expected
                            LOG.debug("An exception was thrown while closing send thread for session 0x"
                                    + Long.toHexString(getSessionId())
                                    + " : " + e.getMessage());
                        }
                        break;
                    } else {
                        // this is ugly, you have a better way speak up
                        if (e instanceof SessionExpiredException) {
                            LOG.info(e.getMessage() + ", closing socket connection");
                        } else if (e instanceof SessionTimeoutException) {
                            LOG.info(e.getMessage() + RETRY_CONN_MSG);
                        } else if (e instanceof EndOfStreamException) {
                            LOG.info(e.getMessage() + RETRY_CONN_MSG);
                        } else {
                            LOG.warn("Session 0x"
                                    + Long.toHexString(getSessionId())
                                    + " for server "
                                    + ((SocketChannel)sockKey.channel())
                                        .socket().getRemoteSocketAddress()
                                    + ", unexpected error"
                                    + RETRY_CONN_MSG,
                                    e);
                        }
                        cleanup();
                        if (zooKeeper.state.isAlive()) {
                            eventThread.queueEvent(new WatchedEvent(
                                    Event.EventType.None,
                                    Event.KeeperState.Disconnected,
                                    null));
                        }

                        now = System.currentTimeMillis();
                        lastHeard = now;
                        lastSend = now;
                    }
                }
            }
            cleanup();
            try {
                selector.close();
            } catch (IOException e) {
                LOG.warn("Ignoring exception during selector close", e);
            }
            if (zooKeeper.state.isAlive()) {
                eventThread.queueEvent(new WatchedEvent(
                        Event.EventType.None,
                        Event.KeeperState.Disconnected,
                        null));
            }
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                     "SendThread exitedloop.");
        }

        private void cleanup() {
            if (sockKey != null) {
                SocketChannel sock = (SocketChannel) sockKey.channel();
                sockKey.cancel();
                try {
                    sock.socket().shutdownInput();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during shutdown input", e);
                    }
                }
                try {
                    sock.socket().shutdownOutput();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during shutdown output", e);
                    }
                }
                try {
                    sock.socket().close();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during socket close", e);
                    }
                }
                try {
                    sock.close();
                } catch (IOException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Ignoring exception during channel close", e);
                    }
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("SendThread interrupted during sleep, ignoring");
                }
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

    /**
     * Shutdown the send/event threads. This method should not be called
     * directly - rather it should be called as part of close operation. This
     * method is primarily here to allow the tests to verify disconnection
     * behavior.
     */
    public void disconnect() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Disconnecting client for session: 0x"
                      + Long.toHexString(getSessionId()));
        }

        sendThread.close();
        eventThread.queueEventOfDeath();
    }

    /**
     * Close the connection, which includes; send session disconnect to the
     * server, shutdown the send/event threads.
     *
     * @throws IOException
     */
    public void close() throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Closing client for session: 0x"
                      + Long.toHexString(getSessionId()));
        }

        closing = true;

        try {
            RequestHeader h = new RequestHeader();
            h.setType(ZooDefs.OpCode.closeSession);

            submitRequest(h, null, null, null);
        } catch (InterruptedException e) {
            // ignore, close the send/event threads
        } finally {
            disconnect();
        }
    }

    private int xid = 1;

    synchronized private int getXid() {
        return xid++;
    }

    public ReplyHeader submitRequest(RequestHeader h, Record request,
            Record response, WatchRegistration watchRegistration)
            throws InterruptedException {
        ReplyHeader r = new ReplyHeader();
        Packet packet = queuePacket(h, r, request, response, null, null, null,
                    null, watchRegistration);
        synchronized (packet) {
            while (!packet.finished) {
                packet.wait();
            }
        }
        return r;
    }

    Packet queuePacket(RequestHeader h, ReplyHeader r, Record request,
            Record response, AsyncCallback cb, String clientPath,
            String serverPath, Object ctx, WatchRegistration watchRegistration)
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
            packet.clientPath = clientPath;
            packet.serverPath = serverPath;
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
        if (!zooKeeper.state.isAlive()) {
            return;
        }
        authInfo.add(new AuthData(scheme, auth));
        queuePacket(new RequestHeader(-4, OpCode.auth), null,
                new AuthPacket(0, scheme, auth), null, null, null, null,
                null, null);
    }
}
