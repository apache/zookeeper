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

package org.apache.zookeeper.server.quorum;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements a connection manager for leader election using TCP. It
 * maintains one connection for every pair of servers. The tricky part is to
 * guarantee that there is exactly one connection for every pair of servers that
 * are operating correctly and that can communicate over the network.
 * 
 * If two servers try to start a connection concurrently, then the connection
 * manager uses a very simple tie-breaking mechanism to decide which connection
 * to drop based on the IP addressed of the two parties. 
 * 
 * For every peer, the manager maintains a queue of messages to send. If the
 * connection to any particular peer drops, then the sender thread puts the
 * message back on the list. As this implementation currently uses a queue
 * implementation to maintain messages to send to another peer, we add the
 * message to the tail of the queue, thus changing the order of messages.
 * Although this is not a problem for the leader election, it could be a problem
 * when consolidating peer communication. This is to be verified, though.
 * 
 */

public class QuorumCnxManager {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumCnxManager.class);

    /*
     * Maximum capacity of thread queues
     */
    static final int RECV_CAPACITY = 100;
    // Initialized to 1 to prevent sending
    // stale notifications to peers
    static final int SEND_CAPACITY = 1;

    static final int PACKETMAXSIZE = 1024 * 512;
    
    /*
     * Negative counter for observer server ids.
     */

    private AtomicLong observerCounter = new AtomicLong(-1);

    /*
     * Protocol identifier used among peers
     */
    public static final long PROTOCOL_VERSION = -65536L;

    /*
     * Max buffer size to be read from the network.
     */
    static public final int maxBuffer = 2048;

    /*
     * Connection time out value in milliseconds 
     */
    
    private int cnxTO = 5000;

    final QuorumPeer self;

    /*
     * Local IP address
     */
    final long mySid;
    final int socketTimeout;
    final Map<Long, QuorumPeer.QuorumServer> view;
    final boolean listenOnAllIPs;
    private ThreadPoolExecutor connectionExecutor;
    private final Set<Long> inprogressConnections = Collections
            .synchronizedSet(new HashSet<Long>());
    private QuorumAuthServer authServer;
    private QuorumAuthLearner authLearner;
    private boolean quorumSaslAuthEnabled;
    /*
     * Counter to count connection processing threads.
     */
    private AtomicInteger connectionThreadCnt = new AtomicInteger(0);

    /*
     * Mapping from Peer to Thread number
     */
    final ConcurrentHashMap<Long, SendWorker> senderWorkerMap;
    final ConcurrentHashMap<Long, ArrayBlockingQueue<ByteBuffer>> queueSendMap;
    final ConcurrentHashMap<Long, ByteBuffer> lastMessageSent;

    /*
     * Reception queue
     */
    public final ArrayBlockingQueue<Message> recvQueue;
    /*
     * Object to synchronize access to recvQueue
     */
    private final Object recvQLock = new Object();

    /*
     * Shutdown flag
     */

    volatile boolean shutdown = false;

    /*
     * Listener thread
     */
    public final Listener listener;

    /*
     * Counter to count worker threads
     */
    private AtomicInteger threadCnt = new AtomicInteger(0);

    /*
     * Socket options for TCP keepalive
     */
    private final boolean tcpKeepAlive = Boolean.getBoolean("zookeeper.tcpKeepAlive");


    static public class Message {
        Message(ByteBuffer buffer, long sid) {
            this.buffer = buffer;
            this.sid = sid;
        }

        ByteBuffer buffer;
        long sid;
    }

    /*
     * This class parses the initial identification sent out by peers with their
     * sid & hostname.
     */
    static public class InitialMessage {
        public Long sid;
        public InetSocketAddress electionAddr;

        InitialMessage(Long sid, InetSocketAddress address) {
            this.sid = sid;
            this.electionAddr = address;
        }

        @SuppressWarnings("serial")
        public static class InitialMessageException extends Exception {
            InitialMessageException(String message, Object... args) {
                super(String.format(message, args));
            }
        }

        static public InitialMessage parse(Long protocolVersion, DataInputStream din)
            throws InitialMessageException, IOException {
            Long sid;

            if (protocolVersion != PROTOCOL_VERSION) {
                throw new InitialMessageException(
                        "Got unrecognized protocol version %s", protocolVersion);
            }

            sid = din.readLong();

            int remaining = din.readInt();
            if (remaining <= 0 || remaining > maxBuffer) {
                throw new InitialMessageException(
                        "Unreasonable buffer length: %s", remaining);
            }

            byte[] b = new byte[remaining];
            int num_read = din.read(b);

            if (num_read != remaining) {
                throw new InitialMessageException(
                        "Read only %s bytes out of %s sent by server %s",
                        num_read, remaining, sid);
            }

            // FIXME: IPv6 is not supported. Using something like Guava's HostAndPort
            //        parser would be good.
            String addr = new String(b);
            String[] host_port = addr.split(":");

            if (host_port.length != 2) {
                throw new InitialMessageException("Badly formed address: %s", addr);
            }

            int port;
            try {
                port = Integer.parseInt(host_port[1]);
            } catch (NumberFormatException e) {
                throw new InitialMessageException("Bad port number: %s", host_port[1]);
            }

            return new InitialMessage(sid, new InetSocketAddress(host_port[0], port));
        }
    }

    public QuorumCnxManager(QuorumPeer self,
                            final long mySid,
                            Map<Long,QuorumPeer.QuorumServer> view,
                            QuorumAuthServer authServer,
                            QuorumAuthLearner authLearner,
                            int socketTimeout,
                            boolean listenOnAllIPs,
                            int quorumCnxnThreadsSize,
                            boolean quorumSaslAuthEnabled) {
        this.recvQueue = new ArrayBlockingQueue<Message>(RECV_CAPACITY);
        this.queueSendMap = new ConcurrentHashMap<Long, ArrayBlockingQueue<ByteBuffer>>();
        this.senderWorkerMap = new ConcurrentHashMap<Long, SendWorker>();
        this.lastMessageSent = new ConcurrentHashMap<Long, ByteBuffer>();
        
        String cnxToValue = System.getProperty("zookeeper.cnxTimeout");
        if(cnxToValue != null){
            this.cnxTO = Integer.parseInt(cnxToValue);
        }

        this.self = self;

        this.mySid = mySid;
        this.socketTimeout = socketTimeout;
        this.view = view;
        this.listenOnAllIPs = listenOnAllIPs;

        initializeAuth(mySid, authServer, authLearner, quorumCnxnThreadsSize,
                quorumSaslAuthEnabled);

        // Starts listener thread that waits for connection requests 
        listener = new Listener();
        listener.setName("QuorumPeerListener");
    }

    private void initializeAuth(final long mySid,
                                final QuorumAuthServer authServer,
                                final QuorumAuthLearner authLearner,
                                final int quorumCnxnThreadsSize,
                                final boolean quorumSaslAuthEnabled) {
        this.authServer = authServer;
        this.authLearner = authLearner;
        this.quorumSaslAuthEnabled = quorumSaslAuthEnabled;
        if (!this.quorumSaslAuthEnabled) {
            LOG.debug("Not initializing connection executor as quorum sasl auth is disabled");
            return;
        }

        // init connection executors
        final AtomicInteger threadIndex = new AtomicInteger(1);
        SecurityManager s = System.getSecurityManager();
        final ThreadGroup group = (s != null) ? s.getThreadGroup()
                : Thread.currentThread().getThreadGroup();
        ThreadFactory daemonThFactory = new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(group, r, "QuorumConnectionThread-"
                        + "[myid=" + mySid + "]-"
                        + threadIndex.getAndIncrement());
                return t;
            }
        };
        this.connectionExecutor = new ThreadPoolExecutor(3,
                quorumCnxnThreadsSize, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), daemonThFactory);
        this.connectionExecutor.allowCoreThreadTimeOut(true);
    }


    /**
     * Invokes initiateConnection for testing purposes
     * 
     * @param sid
     */
    public void testInitiateConnection(long sid) throws Exception {
        LOG.debug("Opening channel to server " + sid);
        Socket sock = new Socket();
        setSockOpts(sock);
        sock.connect(self.getVotingView().get(sid).electionAddr, cnxTO);
        initiateConnection(sock, sid);
    }

    /**
     * If this server has initiated the connection, then it gives up on the
     * connection if it loses challenge. Otherwise, it keeps the connection.
     */
    public void initiateConnection(final Socket sock, final Long sid) {
        try {
            startConnection(sock, sid);
        } catch (IOException e) {
            LOG.error("Exception while connecting, id: {}, addr: {}, closing learner connection",
                    new Object[] { sid, sock.getRemoteSocketAddress() }, e);
            closeSocket(sock);
            return;
        }
    }

    /**
     * Server will initiate the connection request to its peer server
     * asynchronously via separate connection thread.
     */
    public void initiateConnectionAsync(final Socket sock, final Long sid) {
        if(!inprogressConnections.add(sid)){
            // simply return as there is a connection request to
            // server 'sid' already in progress.
            LOG.debug("Connection request to server id: {} is already in progress, so skipping this request",
                    sid);
            closeSocket(sock);
            return;
        }
        try {
            connectionExecutor.execute(
                    new QuorumConnectionReqThread(sock, sid));
            connectionThreadCnt.incrementAndGet();
        } catch (Throwable e) {
            // Imp: Safer side catching all type of exceptions and remove 'sid'
            // from inprogress connections. This is to avoid blocking further
            // connection requests from this 'sid' in case of errors.
            inprogressConnections.remove(sid);
            LOG.error("Exception while submitting quorum connection request", e);
            closeSocket(sock);
        }
    }

    /**
     * Thread to send connection request to peer server.
     */
    private class QuorumConnectionReqThread extends ZooKeeperThread {
        final Socket sock;
        final Long sid;
        QuorumConnectionReqThread(final Socket sock, final Long sid) {
            super("QuorumConnectionReqThread-" + sid);
            this.sock = sock;
            this.sid = sid;
        }

        @Override
        public void run() {
            try{
                initiateConnection(sock, sid);
            } finally {
                inprogressConnections.remove(sid);
            }
        }
    }

    private boolean startConnection(Socket sock, Long sid)
            throws IOException {
        DataOutputStream dout = null;
        DataInputStream din = null;
        try {
            // Use BufferedOutputStream to reduce the number of IP packets. This is
            // important for x-DC scenarios.
            BufferedOutputStream buf = new BufferedOutputStream(sock.getOutputStream());
            dout = new DataOutputStream(buf);

            // Sending id and challenge
            // represents protocol version (in other words - message type)
            dout.writeLong(PROTOCOL_VERSION);
            dout.writeLong(self.getId());
            String addr = self.getElectionAddress().getHostString() + ":" + self.getElectionAddress().getPort();
            byte[] addr_bytes = addr.getBytes();
            dout.writeInt(addr_bytes.length);
            dout.write(addr_bytes);
            dout.flush();

            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));
        } catch (IOException e) {
            LOG.warn("Ignoring exception reading or writing challenge: ", e);
            closeSocket(sock);
            return false;
        }

        // authenticate learner
        QuorumPeer.QuorumServer qps = self.getVotingView().get(sid);
        if (qps != null) {
            // TODO - investigate why reconfig makes qps null.
            authLearner.authenticate(sock, qps.hostname);
        }

        // If lost the challenge, then drop the new connection
        if (sid > self.getId()) {
            LOG.info("Have smaller server identifier, so dropping the " +
                    "connection: (" + sid + ", " + self.getId() + ")");
            closeSocket(sock);
            // Otherwise proceed with the connection
        } else {
            SendWorker sw = new SendWorker(sock, sid);
            RecvWorker rw = new RecvWorker(sock, din, sid, sw);
            sw.setRecv(rw);

            SendWorker vsw = senderWorkerMap.get(sid);

            if(vsw != null)
                vsw.finish();

            senderWorkerMap.put(sid, sw);
            queueSendMap.putIfAbsent(sid, new ArrayBlockingQueue<ByteBuffer>(
                    SEND_CAPACITY));

            sw.start();
            rw.start();

            return true;

        }
        return false;
    }

    /**
     * If this server receives a connection request, then it gives up on the new
     * connection if it wins. Notice that it checks whether it has a connection
     * to this server already or not. If it does, then it sends the smallest
     * possible long value to lose the challenge.
     *
     */
    public void receiveConnection(final Socket sock) {
        DataInputStream din = null;
        try {
            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));

            handleConnection(sock, din);
        } catch (IOException e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                    sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
    }

    /**
     * Server receives a connection request and handles it asynchronously via
     * separate thread.
     */
    public void receiveConnectionAsync(final Socket sock) {
        try {
            connectionExecutor.execute(
                    new QuorumConnectionReceiverThread(sock));
            connectionThreadCnt.incrementAndGet();
        } catch (Throwable e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                    sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
    }

    /**
     * Thread to receive connection request from peer server.
     */
    private class QuorumConnectionReceiverThread extends ZooKeeperThread {
        private final Socket sock;
        QuorumConnectionReceiverThread(final Socket sock) {
            super("QuorumConnectionReceiverThread-" + sock.getRemoteSocketAddress());
            this.sock = sock;
        }

        @Override
        public void run() {
            receiveConnection(sock);
        }
    }

    private void handleConnection(Socket sock, DataInputStream din)
            throws IOException {
        Long sid = null, protocolVersion = null;
        InetSocketAddress electionAddr = null;

        try {
            protocolVersion = din.readLong();
            if (protocolVersion >= 0) { // this is a server id and not a protocol version
                sid = protocolVersion;
            } else {
                try {
                    InitialMessage init = InitialMessage.parse(protocolVersion, din);
                    sid = init.sid;
                    electionAddr = init.electionAddr;
                } catch (InitialMessage.InitialMessageException ex) {
                    LOG.error(ex.toString());
                    closeSocket(sock);
                    return;
                }
            }

            if (sid == QuorumPeer.OBSERVER_ID) {
                /*
                 * Choose identifier at random. We need a value to identify
                 * the connection.
                 */
                sid = observerCounter.getAndDecrement();
                LOG.info("Setting arbitrary identifier to observer: " + sid);
            }
        } catch (IOException e) {
            closeSocket(sock);
            LOG.warn("Exception reading or writing challenge: {}", e.toString());
            return;
        }

        // do authenticating learner
        authServer.authenticate(sock, din);

        //If wins the challenge, then close the new connection.
        if (sid < self.getId()) {
            /*
             * This replica might still believe that the connection to sid is
             * up, so we have to shut down the workers before trying to open a
             * new connection.
             */
            SendWorker sw = senderWorkerMap.get(sid);
            if (sw != null) {
                sw.finish();
            }

            /*
             * Now we start a new connection
             */
            LOG.debug("Create new connection to server: {}", sid);
            closeSocket(sock);

            if (electionAddr != null) {
                connectOne(sid, electionAddr);
            } else {
                connectOne(sid);
            }

        } else { // Otherwise start worker threads to receive data.
            SendWorker sw = new SendWorker(sock, sid);
            RecvWorker rw = new RecvWorker(sock, din, sid, sw);
            sw.setRecv(rw);

            SendWorker vsw = senderWorkerMap.get(sid);

            if (vsw != null) {
                vsw.finish();
            }

            senderWorkerMap.put(sid, sw);

            queueSendMap.putIfAbsent(sid,
                    new ArrayBlockingQueue<ByteBuffer>(SEND_CAPACITY));

            sw.start();
            rw.start();
        }
    }

    /**
     * Processes invoke this message to queue a message to send. Currently, 
     * only leader election uses it.
     */
    public void toSend(Long sid, ByteBuffer b) {
        /*
         * If sending message to myself, then simply enqueue it (loopback).
         */
        if (this.mySid == sid) {
             b.position(0);
             addToRecvQueue(new Message(b.duplicate(), sid));
            /*
             * Otherwise send to the corresponding thread to send.
             */
        } else {
             /*
              * Start a new connection if doesn't have one already.
              */
             ArrayBlockingQueue<ByteBuffer> bq = new ArrayBlockingQueue<ByteBuffer>(
                SEND_CAPACITY);
             ArrayBlockingQueue<ByteBuffer> oldq = queueSendMap.putIfAbsent(sid, bq);
             if (oldq != null) {
                 addToSendQueue(oldq, b);
             } else {
                 addToSendQueue(bq, b);
             }
             connectOne(sid);
                
        }
    }
    
    /**
     * Try to establish a connection to server with id sid using its electionAddr.
     * 
     *  @param sid  server id
     *  @return boolean success indication
     */
    synchronized private boolean connectOne(long sid, InetSocketAddress electionAddr){
        if (senderWorkerMap.get(sid) != null) {
            LOG.debug("There is a connection already for server " + sid);
            return true;
        }

        Socket sock = null;
        try {
            LOG.debug("Opening channel to server " + sid);
            sock = new Socket();
            setSockOpts(sock);
            sock.connect(electionAddr, cnxTO);
            LOG.debug("Connected to server " + sid);
            // Sends connection request asynchronously if the quorum
            // sasl authentication is enabled. This is required because
            // sasl server authentication process may take few seconds to
            // finish, this may delay next peer connection requests.
            if (quorumSaslAuthEnabled) {
                initiateConnectionAsync(sock, sid);
            } else {
                initiateConnection(sock, sid);
            }
            return true;
        } catch (UnresolvedAddressException e) {
            // Sun doesn't include the address that causes this
            // exception to be thrown, also UAE cannot be wrapped cleanly
            // so we log the exception in order to capture this critical
            // detail.
            LOG.warn("Cannot open channel to " + sid
                    + " at election address " + electionAddr, e);
            closeSocket(sock);
            throw e;
        } catch (IOException e) {
            LOG.warn("Cannot open channel to " + sid
                            + " at election address " + electionAddr,
                    e);
            closeSocket(sock);
            return false;
        }
    }
    
    /**
     * Try to establish a connection to server with id sid.
     * 
     *  @param sid  server id
     */
    synchronized void connectOne(long sid){
        if (senderWorkerMap.get(sid) != null) {
            LOG.debug("There is a connection already for server " + sid);
            return;
        }
        synchronized (self.QV_LOCK) {
            boolean knownId = false;
            // Resolve hostname for the remote server before attempting to
            // connect in case the underlying ip address has changed.
            self.recreateSocketAddresses(sid);
            Map<Long, QuorumPeer.QuorumServer> lastCommittedView = self.getView();
            QuorumVerifier lastSeenQV = self.getLastSeenQuorumVerifier();
            Map<Long, QuorumPeer.QuorumServer> lastProposedView = lastSeenQV.getAllMembers();
            if (lastCommittedView.containsKey(sid)) {
                knownId = true;
                if (connectOne(sid, lastCommittedView.get(sid).electionAddr))
                    return;
            }
            if (lastSeenQV != null && lastProposedView.containsKey(sid)
                    && (!knownId || (lastProposedView.get(sid).electionAddr !=
                    lastCommittedView.get(sid).electionAddr))) {
                knownId = true;
                if (connectOne(sid, lastProposedView.get(sid).electionAddr))
                    return;
            }
            if (!knownId) {
                LOG.warn("Invalid server id: " + sid);
                return;
            }
        }
    }
    
    
    /**
     * Try to establish a connection with each server if one
     * doesn't exist.
     */
    
    public void connectAll(){
        long sid;
        for(Enumeration<Long> en = queueSendMap.keys();
            en.hasMoreElements();){
            sid = en.nextElement();
            connectOne(sid);
        }      
    }
    

    /**
     * Check if all queues are empty, indicating that all messages have been delivered.
     */
    boolean haveDelivered() {
        for (ArrayBlockingQueue<ByteBuffer> queue : queueSendMap.values()) {
            LOG.debug("Queue size: " + queue.size());
            if (queue.size() == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Flag that it is time to wrap up all activities and interrupt the listener.
     */
    public void halt() {
        shutdown = true;
        LOG.debug("Halting listener");
        listener.halt();
        
        // Wait for the listener to terminate.
        try {
            listener.join();
        } catch (InterruptedException ex) {
            LOG.warn("Got interrupted before joining the listener", ex);
        }
        softHalt();

        // clear data structures used for auth
        if (connectionExecutor != null) {
            connectionExecutor.shutdown();
        }
        inprogressConnections.clear();
        resetConnectionThreadCount();
    }
   
    /**
     * A soft halt simply finishes workers.
     */
    public void softHalt() {
        for (SendWorker sw : senderWorkerMap.values()) {
            LOG.debug("Halting sender: " + sw);
            sw.finish();
        }
    }

    /**
     * Helper method to set socket options.
     * 
     * @param sock
     *            Reference to socket
     */
    private void setSockOpts(Socket sock) throws SocketException {
        sock.setTcpNoDelay(true);
        sock.setKeepAlive(tcpKeepAlive);
        sock.setSoTimeout(self.tickTime * self.syncLimit);
    }

    /**
     * Helper method to close a socket.
     * 
     * @param sock
     *            Reference to socket
     */
    private void closeSocket(Socket sock) {
        if (sock == null) {
            return;
        }

        try {
            sock.close();
        } catch (IOException ie) {
            LOG.error("Exception while closing", ie);
        }
    }

    /**
     * Return number of worker threads
     */
    public long getThreadCount() {
        return threadCnt.get();
    }

    /**
     * Return number of connection processing threads.
     */
    public long getConnectionThreadCount() {
        return connectionThreadCnt.get();
    }

    /**
     * Reset the value of connection processing threads count to zero.
     */
    private void resetConnectionThreadCount() {
        connectionThreadCnt.set(0);
    }

    /**
     * Thread to listen on some port
     */
    public class Listener extends ZooKeeperThread {

        volatile ServerSocket ss = null;

        public Listener() {
            // During startup of thread, thread name will be overridden to
            // specific election address
            super("ListenerThread");
        }

        /**
         * Sleeps on accept().
         */
        @Override
        public void run() {
            int numRetries = 0;
            InetSocketAddress addr;
            Socket client = null;
            while((!shutdown) && (numRetries < 3)){
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    if (self.getQuorumListenOnAllIPs()) {
                        int port = self.getElectionAddress().getPort();
                        addr = new InetSocketAddress(port);
                    } else {
                        // Resolve hostname for this server in case the
                        // underlying ip address has changed.
                        self.recreateSocketAddresses(self.getId());
                        addr = self.getElectionAddress();
                    }
                    LOG.info("My election bind port: " + addr.toString());
                    setName(addr.toString());
                    ss.bind(addr);
                    while (!shutdown) {
                        client = ss.accept();
                        setSockOpts(client);
                        LOG.info("Received connection request "
                                + client.getRemoteSocketAddress());
                        // Receive and handle the connection request
                        // asynchronously if the quorum sasl authentication is
                        // enabled. This is required because sasl server
                        // authentication process may take few seconds to finish,
                        // this may delay next peer connection requests.
                        if (quorumSaslAuthEnabled) {
                            receiveConnectionAsync(client);
                        } else {
                            receiveConnection(client);
                        }
                        numRetries = 0;
                    }
                } catch (IOException e) {
                    if (shutdown) {
                        break;
                    }
                    LOG.error("Exception while listening", e);
                    numRetries++;
                    try {
                        ss.close();
                        Thread.sleep(1000);
                    } catch (IOException ie) {
                        LOG.error("Error closing server socket", ie);
                    } catch (InterruptedException ie) {
                        LOG.error("Interrupted while sleeping. " +
                            "Ignoring exception", ie);
                    }
                    closeSocket(client);
                }
            }
            LOG.info("Leaving listener");
            if (!shutdown) {
                LOG.error("As I'm leaving the listener thread, "
                        + "I won't be able to participate in leader "
                        + "election any longer: "
                        + self.getElectionAddress());
            } else if (ss != null) {
                // Clean up for shutdown.
                try {
                    ss.close();
                } catch (IOException ie) {
                    // Don't log an error for shutdown.
                    LOG.debug("Error closing server socket", ie);
                }
            }
        }

        /**
         * Halts this listener thread.
         */
        void halt(){
            try{
                LOG.debug("Trying to close listener: " + ss);
                if(ss != null) {
                    LOG.debug("Closing listener: "
                              + QuorumCnxManager.this.mySid);
                    ss.close();
                }
            } catch (IOException e){
                LOG.warn("Exception when shutting down listener: " + e);
            }
        }
    }

    /**
     * Thread to send messages. Instance waits on a queue, and send a message as
     * soon as there is one available. If connection breaks, then opens a new
     * one.
     */
    class SendWorker extends ZooKeeperThread {
        Long sid;
        Socket sock;
        RecvWorker recvWorker;
        volatile boolean running = true;
        DataOutputStream dout;

        /**
         * An instance of this thread receives messages to send
         * through a queue and sends them to the server sid.
         * 
         * @param sock
         *            Socket to remote peer
         * @param sid
         *            Server identifier of remote peer
         */
        SendWorker(Socket sock, Long sid) {
            super("SendWorker:" + sid);
            this.sid = sid;
            this.sock = sock;
            recvWorker = null;
            try {
                dout = new DataOutputStream(sock.getOutputStream());
            } catch (IOException e) {
                LOG.error("Unable to access socket output stream", e);
                closeSocket(sock);
                running = false;
            }
            LOG.debug("Address of remote peer: " + this.sid);
        }

        synchronized void setRecv(RecvWorker recvWorker) {
            this.recvWorker = recvWorker;
        }

        /**
         * Returns RecvWorker that pairs up with this SendWorker.
         * 
         * @return RecvWorker 
         */
        synchronized RecvWorker getRecvWorker(){
            return recvWorker;
        }
                
        synchronized boolean finish() {
            LOG.debug("Calling finish for " + sid);
            
            if(!running){
                /*
                 * Avoids running finish() twice. 
                 */
                return running;
            }
            
            running = false;
            closeSocket(sock);

            this.interrupt();
            if (recvWorker != null) {
                recvWorker.finish();
            }

            LOG.debug("Removing entry from senderWorkerMap sid=" + sid);

            senderWorkerMap.remove(sid, this);
            threadCnt.decrementAndGet();
            return running;
        }
        
        synchronized void send(ByteBuffer b) throws IOException {
            byte[] msgBytes = new byte[b.capacity()];
            try {
                b.position(0);
                b.get(msgBytes);
            } catch (BufferUnderflowException be) {
                LOG.error("BufferUnderflowException ", be);
                return;
            }
            dout.writeInt(b.capacity());
            dout.write(b.array());
            dout.flush();
        }

        @Override
        public void run() {
            threadCnt.incrementAndGet();
            try {
                /**
                 * If there is nothing in the queue to send, then we
                 * send the lastMessage to ensure that the last message
                 * was received by the peer. The message could be dropped
                 * in case self or the peer shutdown their connection
                 * (and exit the thread) prior to reading/processing
                 * the last message. Duplicate messages are handled correctly
                 * by the peer.
                 *
                 * If the send queue is non-empty, then we have a recent
                 * message than that stored in lastMessage. To avoid sending
                 * stale message, we should send the message in the send queue.
                 */
                ArrayBlockingQueue<ByteBuffer> bq = queueSendMap.get(sid);
                if (bq == null || isSendQueueEmpty(bq)) {
                   ByteBuffer b = lastMessageSent.get(sid);
                   if (b != null) {
                       LOG.debug("Attempting to send lastMessage to sid=" + sid);
                       send(b);
                   }
                }
            } catch (IOException e) {
                LOG.error("Failed to send last message. Shutting down thread.", e);
                this.finish();
            }
            
            try {
                while (running && !shutdown && sock != null) {

                    ByteBuffer b = null;
                    try {
                        ArrayBlockingQueue<ByteBuffer> bq = queueSendMap
                                .get(sid);
                        if (bq != null) {
                            b = pollSendQueue(bq, 1000, TimeUnit.MILLISECONDS);
                        } else {
                            LOG.error("No queue of incoming messages for " +
                                      "server " + sid);
                            break;
                        }

                        if(b != null){
                            lastMessageSent.put(sid, b);
                            send(b);
                        }
                    } catch (InterruptedException e) {
                        LOG.warn("Interrupted while waiting for message on queue",
                                e);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Exception when using channel: for id " + sid
                         + " my id = " + QuorumCnxManager.this.mySid
                         + " error = " + e);
            }
            this.finish();
            LOG.warn("Send worker leaving thread " + " id " + sid + " my id = " + self.getId());
        }
    }

    /**
     * Thread to receive messages. Instance waits on a socket read. If the
     * channel breaks, then removes itself from the pool of receivers.
     */
    class RecvWorker extends ZooKeeperThread {
        Long sid;
        Socket sock;
        volatile boolean running = true;
        final DataInputStream din;
        final SendWorker sw;

        RecvWorker(Socket sock, DataInputStream din, Long sid, SendWorker sw) {
            super("RecvWorker:" + sid);
            this.sid = sid;
            this.sock = sock;
            this.sw = sw;
            this.din = din;
            try {
                // OK to wait until socket disconnects while reading.
                sock.setSoTimeout(0);
            } catch (IOException e) {
                LOG.error("Error while accessing socket for " + sid, e);
                closeSocket(sock);
                running = false;
            }
        }
        
        /**
         * Shuts down this worker
         * 
         * @return boolean  Value of variable running
         */
        synchronized boolean finish() {
            if(!running){
                /*
                 * Avoids running finish() twice. 
                 */
                return running;
            }
            running = false;            

            this.interrupt();
            threadCnt.decrementAndGet();
            return running;
        }

        @Override
        public void run() {
            threadCnt.incrementAndGet();
            try {
                while (running && !shutdown && sock != null) {
                    /**
                     * Reads the first int to determine the length of the
                     * message
                     */
                    int length = din.readInt();
                    if (length <= 0 || length > PACKETMAXSIZE) {
                        throw new IOException(
                                "Received packet with invalid packet: "
                                        + length);
                    }
                    /**
                     * Allocates a new ByteBuffer to receive the message
                     */
                    byte[] msgArray = new byte[length];
                    din.readFully(msgArray, 0, length);
                    ByteBuffer message = ByteBuffer.wrap(msgArray);
                    addToRecvQueue(new Message(message.duplicate(), sid));
                }
            } catch (Exception e) {
                LOG.warn("Connection broken for id " + sid + ", my id = "
                         + QuorumCnxManager.this.mySid + ", error = " , e);
            } finally {
                LOG.warn("Interrupting SendWorker");
                sw.finish();
                closeSocket(sock);
            }
        }
    }

    /**
     * Inserts an element in the specified queue. If the Queue is full, this
     * method removes an element from the head of the Queue and then inserts
     * the element at the tail. It can happen that the an element is removed
     * by another thread in {@link SendWorker#processMessage() processMessage}
     * method before this method attempts to remove an element from the queue.
     * This will cause {@link ArrayBlockingQueue#remove() remove} to throw an
     * exception, which is safe to ignore.
     *
     * Unlike {@link #addToRecvQueue(Message) addToRecvQueue} this method does
     * not need to be synchronized since there is only one thread that inserts
     * an element in the queue and another thread that reads from the queue.
     *
     * @param queue
     *          Reference to the Queue
     * @param buffer
     *          Reference to the buffer to be inserted in the queue
     */
    private void addToSendQueue(ArrayBlockingQueue<ByteBuffer> queue,
          ByteBuffer buffer) {
        if (queue.remainingCapacity() == 0) {
            try {
                queue.remove();
            } catch (NoSuchElementException ne) {
                // element could be removed by poll()
                LOG.debug("Trying to remove from an empty " +
                        "Queue. Ignoring exception " + ne);
            }
        }
        try {
            queue.add(buffer);
        } catch (IllegalStateException ie) {
            // This should never happen
            LOG.error("Unable to insert an element in the queue " + ie);
        }
    }

    /**
     * Returns true if queue is empty.
     * @param queue
     *          Reference to the queue
     * @return
     *      true if the specified queue is empty
     */
    private boolean isSendQueueEmpty(ArrayBlockingQueue<ByteBuffer> queue) {
        return queue.isEmpty();
    }

    /**
     * Retrieves and removes buffer at the head of this queue,
     * waiting up to the specified wait time if necessary for an element to
     * become available.
     *
     * {@link ArrayBlockingQueue#poll(long, java.util.concurrent.TimeUnit)}
     */
    private ByteBuffer pollSendQueue(ArrayBlockingQueue<ByteBuffer> queue,
          long timeout, TimeUnit unit) throws InterruptedException {
       return queue.poll(timeout, unit);
    }

    /**
     * Inserts an element in the {@link #recvQueue}. If the Queue is full, this
     * methods removes an element from the head of the Queue and then inserts
     * the element at the tail of the queue.
     *
     * This method is synchronized to achieve fairness between two threads that
     * are trying to insert an element in the queue. Each thread checks if the
     * queue is full, then removes the element at the head of the queue, and
     * then inserts an element at the tail. This three-step process is done to
     * prevent a thread from blocking while inserting an element in the queue.
     * If we do not synchronize the call to this method, then a thread can grab
     * a slot in the queue created by the second thread. This can cause the call
     * to insert by the second thread to fail.
     * Note that synchronizing this method does not block another thread
     * from polling the queue since that synchronization is provided by the
     * queue itself.
     *
     * @param msg
     *          Reference to the message to be inserted in the queue
     */
    public void addToRecvQueue(Message msg) {
        synchronized(recvQLock) {
            if (recvQueue.remainingCapacity() == 0) {
                try {
                    recvQueue.remove();
                } catch (NoSuchElementException ne) {
                    // element could be removed by poll()
                     LOG.debug("Trying to remove from an empty " +
                         "recvQueue. Ignoring exception " + ne);
                }
            }
            try {
                recvQueue.add(msg);
            } catch (IllegalStateException ie) {
                // This should never happen
                LOG.error("Unable to insert element in the recvQueue " + ie);
            }
        }
    }

    /**
     * Retrieves and removes a message at the head of this queue,
     * waiting up to the specified wait time if necessary for an element to
     * become available.
     *
     * {@link ArrayBlockingQueue#poll(long, java.util.concurrent.TimeUnit)}
     */
    public Message pollRecvQueue(long timeout, TimeUnit unit)
       throws InterruptedException {
       return recvQueue.poll(timeout, unit);
    }

    public boolean connectedToPeer(long peerSid) {
        return senderWorkerMap.get(peerSid) != null;
    }
}
