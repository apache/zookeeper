/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All rights reserved.
 * 
 */
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using org.apache.zookeeper.client;
using org.apache.jute;
using org.apache.utils;
using org.apache.zookeeper.proto;

namespace org.apache.zookeeper {
/**
 * This class manages the socket i/o for the client. ClientCnxn maintains a list
 * of available servers to connect to and "transparently" switches servers it is
 * connected to as needed.
 *
 */
    internal sealed class ClientCnxn {
        private static readonly ILogProducer LOG = TypeLogger<ClientCnxn>.Instance;

        /* ZOOKEEPER-706: If a session has a large number of watches set then
         * attempting to re-establish those watches after a connection loss may
         * fail due to the SetWatches request exceeding the server's configured
         * jute.maxBuffer value. To avoid this we instead split the watch
         * re-establishement across multiple SetWatches calls. This constant
         * controls the size of each call. It is set to 128kB to be conservative
         * with respect to the server's 1MB default for jute.maxBuffer.
         */
        private const int SET_WATCHES_MAX_LENGTH = 128 * 1024;
        
        private class AuthData {
            internal AuthData(string scheme, byte[] data) {
                this.scheme = scheme;
                this.data = data;
            }

            internal readonly string scheme;
            internal readonly byte[] data;
        }

        private List<AuthData> authInfo = new List<AuthData>();
        /**
	 * These are the packets that have been sent and are waiting for a response.
	 */
        internal readonly LinkedList<Packet> pendingQueue = new LinkedList<Packet>();
        /**
	 * These are the packets that need to be sent.
	 */
        internal readonly LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();
        private int connectTimeout;
        /**
	 * The timeout in ms the client negotiated with the server. This is the
	 * "real" timeout, not the timeout request by the client (which may have
	 * been increased/decreased by the server which applies bounds to this
	 * value.
	 */
        private readonly Fenced<int> negotiatedSessionTimeout = new Fenced<int>(0);
        private int readTimeout;
        private readonly int sessionTimeout;
        private readonly ZooKeeper zooKeeper;
        private readonly ClientWatchManager watcher;
        private long sessionId;
        private byte[] sessionPasswd;
        /**
	 * If true, the connection is allowed to go to r-o mode. This field's value
	 * is sent, besides other data, during session creation handshake. If the
	 * server on the other side of the wire is partitioned it'll accept
	 * read-only clients only.
	 */
        private readonly bool readOnly;
        public readonly string chrootPath;
        private Task sendTask;
        private Task eventTask;
        /**
	 * Set to true when close is called. Latches the connection such that we
	 * don't attempt to re-connect to the server if in the middle of closing the
	 * connection (client sends session disconnect to server as part of close
	 * operation)
	 */
        private readonly Fenced<bool> closing = new Fenced<bool>(false);

        /**
	 * A set of ZooKeeper hosts this client could connect to.
	 */
        private readonly HostProvider hostProvider;

	 // Is set to true when a connection to a r/w server is established for the
	 // first time; never changed afterwards.

	 // Is used to handle situations when client without sessionId connects to a
	 // read-only server. Such client receives "fake" sessionId from read-only
	 // server, but this sessionId is invalid for other servers. So when such
	 // client finds a r/w server, it sends 0 instead of fake sessionId during
	 // connection handshake and establishes new, valid session.

	 // If this field is false (which implies we haven't seen r/w server before)
	 // then non-zero sessionId is fake, otherwise it is valid.
	 //
        internal readonly Fenced<bool> seenRwServerBefore = new Fenced<bool>(false);

        //public ZooKeeperSaslClient zooKeeperSaslClient;
        public long getSessionId() {
            return sessionId;
        }

        public byte[] getSessionPasswd() {
            return sessionPasswd;
        }

        public int getSessionTimeout() {
            return negotiatedSessionTimeout.Value;
        }

        public override string ToString() {
            StringBuilder sb = new StringBuilder();

            EndPoint local = clientCnxnSocket
                .getLocalSocketAddress();
            EndPoint remote = clientCnxnSocket
                .getRemoteSocketAddress();
            sb.Append("sessionid:0x").Append(getSessionId().ToHexString())
                .Append(" local:").Append(local).Append(" remoteserver:")
                .Append(remote).Append(" lastZxid:").Append(lastZxid)
                .Append(" xid:").Append(xid).Append(" sent:")
                .Append(clientCnxnSocket.getSentCount())
                .Append(" recv:")
                .Append(clientCnxnSocket.getRecvCount())
                .Append(" queuedevents:")
                .Append(waitingEvents.size());

            return sb.ToString();
        }

/**
	 * This class allows us to pass the headers and the relevant records around.
	 */

        public sealed class Packet {
            internal readonly RequestHeader requestHeader;

            internal readonly ReplyHeader replyHeader;

            private readonly Record request;

            internal readonly Record response;

            internal ByteBuffer bb { get; private set; }

            private readonly AsyncManualResetEvent packetCompletion = new AsyncManualResetEvent();

            internal Task PacketTask {
                get { return packetCompletion.WaitAsync(); }
            }

            internal void SetFinished() {
                packetCompletion.Set();
            }

            /** Client's view of the path (may differ due to chroot) **/
            internal string clientPath;
            /** Servers's view of the path (may differ due to chroot) **/
            internal string serverPath;
            internal readonly ZooKeeper.WatchRegistration watchRegistration;
            private readonly bool readOnly;

            /** Convenience ctor */

            internal Packet(RequestHeader requestHeader, ReplyHeader replyHeader,
                Record request, Record response,
                ZooKeeper.WatchRegistration watchRegistration) :
                    this(requestHeader, replyHeader, request, response,
                        watchRegistration, false) {
            }

            internal Packet(RequestHeader requestHeader, ReplyHeader replyHeader,
                Record request, Record response,
                ZooKeeper.WatchRegistration watchRegistration, bool readOnly) {
                this.requestHeader = requestHeader;
                this.replyHeader = replyHeader;
                this.request = request;
                this.response = response;
                this.readOnly = readOnly;
                this.watchRegistration = watchRegistration;
            }

            public void createBB() {
                    MemoryStream ms = new MemoryStream();
                    BigEndianBinaryWriter writer = new BigEndianBinaryWriter(ms);
                    BinaryOutputArchive boa = BinaryOutputArchive.getArchive(writer);

                    boa.writeInt(-1, "len"); // We'll fill this in later
                    if (requestHeader != null) {
                        ((Record) requestHeader).serialize(boa, "header");
                    }
                    if (request is ConnectRequest) {
                        request.serialize(boa, "connect");
                        // append "am-I-allowed-to-be-readonly" flag
                        boa.writeBool(readOnly, "readOnly");
                    }
                    else if (request != null) {
                        request.serialize(boa, "request");
                    }
                    ms.Position = 0;
                    bb = new ByteBuffer(ms);
                    boa.writeInt(bb.limit() - 4, "len");
                    ms.Position = 0;
            }

            public override string ToString() {
                StringBuilder sb = new StringBuilder();
                sb.Append("  clientPath:").Append(clientPath);
                sb.Append("  serverPath:").Append(serverPath);
                sb.Append("    finished:").Append(packetCompletion.WaitAsync().IsCompleted);
                sb.Append("     header::").Append(requestHeader);
                sb.Append("replyHeader::").Append(replyHeader);
                sb.Append("    request::").Append(request);
                sb.Append("   response::").Append(response);
                // jute toString is horrible, remove unnecessary newlines
                return sb.ToString().Replace(@"\r*\n+", " ");
            }
        }


        /**
	 * Creates a connection object. The actual network connect doesn't get
	 * established until needed. The start() instance method must be called
	 * subsequent to construction.
	 *
	 * @param chrootPath
	 *            - the chroot of this client. Should be removed from this Class
	 *            in ZOOKEEPER-838
	 * @param hostProvider
	 *            the list of ZooKeeper servers to connect to
	 * @param sessionTimeout
	 *            the timeout for connections.
	 * @param zooKeeper
	 *            the zookeeper object that this connection is related to.
	 * @param watcher
	 *            watcher for this connection
	 * @param clientCnxnSocket
	 *            the socket implementation used (e.g. NIO/Netty)
	 * @param sessionId
	 *            session id if re-establishing session
	 * @param sessionPasswd
	 *            session passwd if re-establishing session
	 * @param canBeReadOnly
	 *            whether the connection is allowed to go to read-only mode in
	 *            case of partitioning
	 * @throws IOException
	 */

        internal ClientCnxn(string chrootPath, HostProvider hostProvider,
            int sessionTimeout, ZooKeeper zooKeeper,
            ClientWatchManager watcher,
            long sessionId, byte[] sessionPasswd, bool canBeReadOnly) {
            this.zooKeeper = zooKeeper;
            this.watcher = watcher;
            this.sessionId = sessionId;
            this.sessionPasswd = sessionPasswd;
            this.sessionTimeout = sessionTimeout;
            this.hostProvider = hostProvider;
            this.chrootPath = chrootPath;
            connectTimeout = sessionTimeout/hostProvider.size();
            readTimeout = sessionTimeout*2/3;
            readOnly = canBeReadOnly;
            clientCnxnSocket = new ClientCnxnSocketNIO(this);
            state.Value = ZooKeeper.States.CONNECTING;
        }
        public void start() {
            sendTask = startSendTask();
            eventTask = startEventTask();
        }

        private static readonly WatcherSetEventPair eventOfDeath = new WatcherSetEventPair(null, null);

        private class WatcherSetEventPair {
            internal readonly HashSet<Watcher> watchers;
            internal readonly WatchedEvent @event;

            public WatcherSetEventPair(HashSet<Watcher> watchers, WatchedEvent @event) {
                this.watchers = watchers;
                this.@event = @event;
            }
        }

      private readonly AsyncManualResetEvent waitingEventsManualResetEvent = new AsyncManualResetEvent();
      
      private readonly ConcurrentQueue<WatcherSetEventPair> waitingEvents=new ConcurrentQueue<WatcherSetEventPair>();

        

/**
		 * This is really the queued session state until the event task
		 * actually processes the event and hands it to the watcher. But for all
		 * intents and purposes this is the state.
		 */

            private readonly Fenced<Watcher.Event.KeeperState> sessionState =
                new Fenced<Watcher.Event.KeeperState>(Watcher.Event.KeeperState.Disconnected);

        private void queueEvent(WatchedEvent @event) {
                if (@event.get_Type() == Watcher.Event.EventType.None &&
                    sessionState.Value == @event.getState()) {
                    return;
                }
                sessionState.Value = @event.getState();
                // materialize the watchers based on the event
                WatcherSetEventPair pair = new WatcherSetEventPair(
                    watcher.materialize(@event.getState(), @event.get_Type(),
                        @event.getPath()), @event);
                // queue the pair (watch set & event) for later processing
                waitingEvents.Enqueue(pair);
                waitingEventsManualResetEvent.Set();
            }


            private void queueEventOfDeath() {
                waitingEvents.Enqueue(eventOfDeath);
                waitingEventsManualResetEvent.Set();
            }

        private async Task startEventTask() {
            bool wasKilled = false;

            try {
            while (!(wasKilled && waitingEvents.IsEmpty)) {
                await waitingEventsManualResetEvent.WaitAsync().ConfigureAwait(false);
                waitingEventsManualResetEvent.Reset();

                WatcherSetEventPair @event;
                while (waitingEvents.TryDequeue(out @event)) {
                    if (@event == eventOfDeath) {
                        wasKilled = true;
                    }
                    else {
                        await processEvent(@event).ConfigureAwait(false);
                    }
                }
                }
            } catch (Exception e) {
                LOG.warn("Exception occurred in EventTask", e);
            }
            LOG.info("EventTask shut down for session: 0x" +
                     getSessionId().ToHexString());
        }

        private static async Task processEvent(WatcherSetEventPair @event) {
            foreach (Watcher watcher in @event.watchers) {
                try {
                    await watcher.process(@event.@event).ConfigureAwait(false);
                }
                catch (Exception t) {
                    LOG.error("Error while calling watcher ", t);
                }
            }
        }

        private static void finishPacket(Packet p) {
            if (p.watchRegistration != null) {
                p.watchRegistration.register(p.replyHeader.getErr());
            }
                p.SetFinished();
        }

        private void conLossPacket(Packet p) {
            if (p.replyHeader == null) {
                return;
            }
            switch (getState()) {
                case ZooKeeper.States.AUTH_FAILED:
                    p.replyHeader.setErr((int) KeeperException.Code.AUTHFAILED);
                    break;
                case ZooKeeper.States.CLOSED:
                    p.replyHeader.setErr((int) KeeperException.Code.SESSIONEXPIRED);
                    break;
                default:
                    p.replyHeader.setErr((int) KeeperException.Code.CONNECTIONLOSS);
                    break;
            }
            finishPacket(p);
        }

        private readonly Fenced<long> lastZxid = new Fenced<long>(0);

        private class SessionTimeoutException : IOException {
            public SessionTimeoutException(string msg) : base(msg) {
            }
        }

        private class SessionExpiredException : IOException {
            public SessionExpiredException(string msg) : base(msg) {
            }
        }

        private class RWServerFoundException : IOException {
            public RWServerFoundException(string msg) : base(msg) {
            }
        }

        public const int packetLen = 0xfffff;

            private long lastPingSentNs;
            private readonly ClientCnxnSocket clientCnxnSocket;
            private readonly Random r = new Random();
            private bool isFirstConnect = true;

            internal void readResponse(ByteBuffer incomingBuffer) {
                BigEndianBinaryReader bbis = new BigEndianBinaryReader(incomingBuffer.Stream);
                BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);

                ReplyHeader replyHdr = new ReplyHeader();
                ((Record) replyHdr).deserialize(bbia, "header");
                if (replyHdr.getXid() == -2) {
                    // -2 is the xid for pings
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Got ping response for sessionid: 0x" + sessionId.ToHexString() + " after " +
                                  ((TimeHelper.ElapsedNanoseconds - lastPingSentNs) / 1000000) + "ms");
                    }
                    return;
                }
                if (replyHdr.getXid() == -4) {
                    // -4 is the xid for AuthPacket               
                    if (replyHdr.getErr() == (int) KeeperException.Code.AUTHFAILED) {
                        state.Value = ZooKeeper.States.AUTH_FAILED;
                        queueEvent(new WatchedEvent(Watcher.Event.EventType.None,
                            Watcher.Event.KeeperState.AuthFailed, null));
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Got auth sessionid:0x"
                                  + sessionId.ToHexString());
                    }
                    return;
                }
                if (replyHdr.getXid() == -1) {
                    // -1 means notification
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Got notification sessionid:0x"
                                  + sessionId.ToHexString());
                    }
                    WatcherEvent @event = new WatcherEvent();
                    ((Record) @event).deserialize(bbia, "response");
                    // convert from a server path to a client path
                    if (chrootPath != null) {
                        string serverPath = @event.getPath();
                        if (serverPath == chrootPath) {
                            @event.setPath("/");
                        }
                        else if (serverPath.Length > chrootPath.Length) {
                            @event.setPath(serverPath.Substring(chrootPath.Length));
                        }
                        else {
                            LOG.warn("Got server path " + @event.getPath()
                                     + " which is too short for chroot path "
                                     + chrootPath);
                        }
                    }
                    WatchedEvent we = new WatchedEvent(@event);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Got " + we + " for sessionid 0x" + sessionId.ToHexString());
                    }
                    queueEvent(we);
                    return;
                }
       
                Packet packet;
                lock (pendingQueue) {
                    if (pendingQueue.size() == 0) {
                        throw new IOException("Nothing in the queue, but got "
                                              + replyHdr.getXid());
                    }
                    packet = pendingQueue.First.Value;
                    pendingQueue.RemoveFirst();
                }
                /*
			 * Since requests are processed in order, we better get a response
			 * to the first request!
			 */
                try {
                    if (packet.requestHeader.getXid() != replyHdr.getXid()) {
                        packet.replyHeader.setErr((int) KeeperException.Code.CONNECTIONLOSS);
                        throw new IOException("Xid out of order. Got Xid "
                                              + replyHdr.getXid() + " with err "
                                              + +replyHdr.getErr() + " expected Xid "
                                              + packet.requestHeader.getXid()
                                              + " for a packet with details: " + packet);
                    }
                    packet.replyHeader.setXid(replyHdr.getXid());
                    packet.replyHeader.setErr(replyHdr.getErr());
                    packet.replyHeader.setZxid(replyHdr.getZxid());
                    if (replyHdr.getZxid() > 0) {
                        lastZxid.Value = replyHdr.getZxid();
                    }
                    if (packet.response != null && replyHdr.getErr() == 0) {
                        packet.response.deserialize(bbia, "response");
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Reading reply sessionid:0x" + sessionId.ToHexString() + ", packet:: " +
                                  packet);
                    }
                }
                finally {
                    finishPacket(packet);
                }
            }
        internal void primeConnection() {
                LOG.info("Socket connection established to " +
                         clientCnxnSocket.getRemoteSocketAddress() +
                         ", initiating session");
                isFirstConnect = false;
                long sessId = (seenRwServerBefore.Value) ? sessionId : 0;
                ConnectRequest conReq = new ConnectRequest(0, lastZxid.Value,
                    sessionTimeout, sessId, sessionPasswd);
                lock (outgoingQueue) {
                    // We add backwards since we are pushing into the front
                    // Only send if there's a pending watch
                    // TODO: here we have the only remaining use of zooKeeper in
                    // this class. It's to be eliminated!
                        List<string> dataWatches = zooKeeper.getDataWatches();
                        List<string> existWatches = zooKeeper.getExistWatches();
                        List<string> childWatches = zooKeeper.getChildWatches();
                        if (dataWatches.Count > 0 || existWatches.Count > 0 || childWatches.Count > 0) {
                        var dataWatchesIter = prependChroot(dataWatches).GetEnumerator();
                        var existWatchesIter = prependChroot(existWatches).GetEnumerator();
                        var childWatchesIter = prependChroot(childWatches).GetEnumerator();
                        long setWatchesLastZxid = lastZxid.Value;
                        bool done = false;

                        while (!done) {
                            var dataWatchesBatch = new List<string>();
                            var existWatchesBatch = new List<string>();
                            var childWatchesBatch = new List<string>();
                            int batchLength = 0;

                            // Note, we may exceed our max length by a bit when we add the last
                            // watch in the batch. This isn't ideal, but it makes the code simpler.
                            while (batchLength < SET_WATCHES_MAX_LENGTH) {
                                string watch;
                                if (dataWatchesIter.MoveNext()) {
                                    watch = dataWatchesIter.Current;
                                    dataWatchesBatch.Add(watch);
                                } else if (existWatchesIter.MoveNext()) {
                                    watch = existWatchesIter.Current;
                                    existWatchesBatch.Add(watch);
                                } else if (childWatchesIter.MoveNext()) {
                                    watch = childWatchesIter.Current;
                                    childWatchesBatch.Add(watch);
                                } else {
                                    done = true;
                                    break;
                                }
                                batchLength += watch.Length;
                            }

                            SetWatches sw = new SetWatches(setWatchesLastZxid,
                                    dataWatchesBatch,
                                    existWatchesBatch,
                                    childWatchesBatch);
                            RequestHeader h = new RequestHeader();
                            h.set_Type((int) ZooDefs.OpCode.setWatches);
                            h.setXid(-8);
                            Packet packet = new Packet(h, new ReplyHeader(), sw,
                                null, null);
                            outgoingQueue.AddFirst(packet);
                        }
                    }
                    foreach (AuthData id in authInfo) {
                        outgoingQueue.AddFirst(new Packet(new RequestHeader(-4,
                            (int) ZooDefs.OpCode.auth), null, new AuthPacket(0, id.scheme,
                                id.data), null, null));
                    }
                    outgoingQueue.AddFirst(new Packet(null, null, conReq, null,
                        null, readOnly));
                }
                clientCnxnSocket.enableReadWriteOnly();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Session establishment request sent on " + clientCnxnSocket.getRemoteSocketAddress());
                }
            }

            private List<string> prependChroot(List<string> paths) {
                if (chrootPath != null && paths.Count > 0) 
                {
                    for (int i = 0; i < paths.size(); ++i) {
                        string clientPath = paths[i];
                        string serverPath;
                        // handle clientPath = "/"
                        if (clientPath.Length == 1) {
                            serverPath = chrootPath;
                        } else {
                            serverPath = chrootPath + clientPath;
                        }
                        paths[i] = serverPath;
                    }
                }
                return paths;
            }

            private void sendPing() {
                lastPingSentNs = TimeHelper.ElapsedNanoseconds;
                RequestHeader h = new RequestHeader(-2, (int) ZooDefs.OpCode.ping);
                queuePacket(h, null, null, null, null, null, null);
            }

            private DnsEndPoint rwServerAddress;
            private const int minPingRwTimeout = 100;
            private const int maxPingRwTimeout = 60000;
            private int pingRwTimeout = minPingRwTimeout;
            // Set to true if and only if constructor of ZooKeeperSaslClient
            // throws a LoginException: see startConnect() below.
            //internal bool saslLoginFailed = false;

            private async Task startConnect() {
                state.Value = ZooKeeper.States.CONNECTING;
                DnsEndPoint addr;
                if (rwServerAddress != null) {
                    addr = rwServerAddress;
                    rwServerAddress = null;
                }
                else {
                    addr = await hostProvider.next(1000).ConfigureAwait(false);
                }

                logStartConnect(addr);
                clientCnxnSocket.connect(addr);
            }

            private static void logStartConnect(DnsEndPoint addr) {
                string msg = "Opening socket connection to server " + addr;
                LOG.info(msg);
            }

            private const string RETRY_CONN_MSG = ", closing socket connection and attempting reconnect";

            private async Task startSendTask() {
                try { 
                clientCnxnSocket.introduce(sessionId);
                clientCnxnSocket.updateNow();
                clientCnxnSocket.updateLastSendAndHeard();
                long lastPingRwServer = TimeHelper.ElapsedMiliseconds;
                const int MAX_SEND_PING_INTERVAL = 10000; //10 seconds
                while (getState().isAlive()) {
                    try {
                        if (!clientCnxnSocket.isConnected()) {
                            if (!isFirstConnect) {
                                await TaskEx.Delay(r.Next(1000)).ConfigureAwait(false);
                            }
                            // don't re-establish connection if we are closing
                            if (closing.Value || !getState().isAlive()) {
                                break;
                            }
                            await startConnect().ConfigureAwait(false);
                            clientCnxnSocket.updateLastSendAndHeard();
                        }
                        int to;
                        if (getState().isConnected()) {
                            to = readTimeout - clientCnxnSocket.getIdleRecv();
                        }
                        else {
                            to = connectTimeout - clientCnxnSocket.getIdleRecv();
                        }

                        if (to <= 0) {
                            string warnInfo = 
                                "Client session timed out, have not heard from server in "
                                + clientCnxnSocket.getIdleRecv() + "ms"
                                + " for sessionid 0x"
                                + clientCnxnSocket.sessionId.ToHexString();
                            LOG.warn(warnInfo);
                            throw new SessionTimeoutException(warnInfo);
                        }
                        if (getState().isConnected())
                        {
                            // 1000(1 second) is to prevent race condition missing
                            // to send the second ping
                            // also make sure not to send too many pings when
                            // readTimeout is small
                            int timeToNextPing = readTimeout
                                    / 2
                                    - clientCnxnSocket.getIdleSend()
                                    - ((clientCnxnSocket.getIdleSend() > 1000) ? 1000
                                            : 0);
                            // send a ping request either time is due or no packet
                            // sent out within MAX_SEND_PING_INTERVAL
                            if (timeToNextPing <= 0
                            || clientCnxnSocket.getIdleSend() > MAX_SEND_PING_INTERVAL) {
                                sendPing();
                                clientCnxnSocket.updateLastSend();
                            }
                            else {
                                if (timeToNextPing < to) {
                                    to = timeToNextPing;
                                }
                            }
                        }
                        // If we are in read-only mode, seek for read/write server
                        if (getState() == ZooKeeper.States.CONNECTEDREADONLY)
                        {
                            long now = TimeHelper.ElapsedMiliseconds;
                            int idlePingRwServer = (int) (now - lastPingRwServer);
                            if (idlePingRwServer >= pingRwTimeout) {
                                lastPingRwServer = now;
                                idlePingRwServer = 0;
                                pingRwTimeout = Math.Min(2*pingRwTimeout,
                                maxPingRwTimeout);
                                await pingRwServer().ConfigureAwait(false);
                            }
                            to = Math.Min(to, pingRwTimeout - idlePingRwServer);
                        }
                        await clientCnxnSocket.doTransport(to).ConfigureAwait(false);
                    }
                    catch (Exception e) {
                        if (closing.Value) {
                            if (LOG.isDebugEnabled()) {
                                // closing so this is expected
                                LOG.debug("An exception was thrown while closing send task for session 0x" +
                                          getSessionId().ToHexString()
                                          + " : "
                                          + e.Message);
                            }
                            break;
                        }
                        // this is ugly, you have a better way speak up
                        if (e is SessionExpiredException) {
                            LOG.info(e.Message + ", closing socket connection");
                        }
                        else if (e is SessionTimeoutException) {
                            LOG.info(e.Message + RETRY_CONN_MSG);
                        }
                        else if (e is EndOfStreamException) {
                            LOG.info(e.Message + RETRY_CONN_MSG);
                        }
                        else if (e is RWServerFoundException) {
                            LOG.info(e.Message);
                        }
                        else {
                            LOG.warn(
                                "Session 0x" + getSessionId().ToHexString() +
                                " for server " +
                                clientCnxnSocket.getRemoteSocketAddress()
                                + ", unexpected error" +
                                RETRY_CONN_MSG, e);
                        }
                        await cleanup().ConfigureAwait(false);
                        if (getState().isAlive())
                        {
                            queueEvent(new WatchedEvent(
                                Watcher.Event.EventType.None,
                                Watcher.Event.KeeperState.Disconnected, null));
                        }
                        clientCnxnSocket.updateNow();
                        clientCnxnSocket.updateLastSendAndHeard();
                    }
                }

                await cleanup().ConfigureAwait(false);
                clientCnxnSocket.close();
                if (getState().isAlive()) {
                    queueEvent(new WatchedEvent(Watcher.Event.EventType.None,
                        Watcher.Event.KeeperState.Disconnected, null));
                }
                }
                catch(Exception e){
                LOG.warn("Exception occurred in SendTask", e);
                }
            LOG.debug("SendTask exited loop for session: 0x" +
                     getSessionId().ToHexString());
            }

            private async Task pingRwServer() {
                string result = null;
                DnsEndPoint addr = await hostProvider.next(0).ConfigureAwait(false);
                LOG.info("Checking server " + addr + " for being r/w." +
                         " Timeout " + pingRwTimeout);

                TcpClient sock = null;
                StreamReader br = null;
                try {
                    sock = new TcpClient();
                    sock.LingerState = new LingerOption(false, 0);
                    sock.SendTimeout = 1000;
                    sock.NoDelay = true;
                    await sock.ConnectAsync(addr.Host, addr.Port).ConfigureAwait(false);
                    var networkStream = sock.GetStream();
                    await networkStream.WriteAsync("isro".UTF8getBytes(), 0, 4).ConfigureAwait(false);
                    await networkStream.FlushAsync().ConfigureAwait(false);
                    br = new StreamReader(networkStream);
                    result = await br.ReadLineAsync().ConfigureAwait(false);
            }
                catch (Exception e) {
                    var se = e.InnerException as SocketException;
                    if (se != null && se.SocketErrorCode == SocketError.ConnectionRefused) {
                        // ignore, this just means server is not up
                    }
                    else {
                        // some unexpected error, warn about it
                        LOG.warn("Exception while seeking for r/w server " +
                                 e.Message, e);
                    }
                }
                if (sock != null) {
                    try {
                        ((IDisposable) sock).Dispose();
                    }
                    catch (Exception e) {
                        LOG.warn("Unexpected exception", e);
                    }
                }
                if (br != null) {
                    try {
                        br.Dispose();
                    }
                    catch (Exception e) {
                        LOG.warn("Unexpected exception", e);
                    }
                }

                if ("rw" == result) {
                    pingRwTimeout = minPingRwTimeout;
                    // save the found address so that it's used during the next
                    // connection attempt
                    rwServerAddress = addr;
                    throw new RWServerFoundException("Majority server found at " + addr.Host + ":" +
                                                    addr.Port);
                }
            }

            private async Task cleanup() {
                await clientCnxnSocket.cleanup().ConfigureAwait(false);
                lock (pendingQueue) {
                    foreach (Packet p in pendingQueue) {
                        conLossPacket(p);
                    }
                    pendingQueue.Clear();
                }
                lock (outgoingQueue) {
                    foreach (Packet p in outgoingQueue) {
                        conLossPacket(p);
                    }
                    outgoingQueue.Clear();
                }
            }

            /**
		 * Callback invoked by the ClientCnxnSocket once a connection has been
		 * established.
		 * 
		 * @param _negotiatedSessionTimeout
		 * @param _sessionId
		 * @param _sessionPasswd
		 * @param isRO
		 * @throws IOException
		 */

            internal void onConnected(int _negotiatedSessionTimeout, long _sessionId,
                byte[] _sessionPasswd, bool isRO) {
                negotiatedSessionTimeout.Value = _negotiatedSessionTimeout;
                if (negotiatedSessionTimeout.Value <= 0) {
                    state.Value = ZooKeeper.States.CLOSED;
                    queueEvent(new WatchedEvent(
                        Watcher.Event.EventType.None,
                        Watcher.Event.KeeperState.Expired, null));
                    queueEventOfDeath();
                string warnInfo = "Unable to reconnect to ZooKeeper service, session 0x" +
                                                      sessionId.ToHexString() + " has expired";
                LOG.warn(warnInfo);
                throw new SessionExpiredException(warnInfo);
                }
                if (!readOnly && isRO) {
                    LOG.error("Read/write client got connected to read-only server");
                }
                readTimeout = negotiatedSessionTimeout.Value*2/3;
                connectTimeout = negotiatedSessionTimeout.Value/hostProvider.size();
                hostProvider.onConnected();
                sessionId = _sessionId;
                sessionPasswd = _sessionPasswd;
                state.Value = (isRO) ? ZooKeeper.States.CONNECTEDREADONLY : ZooKeeper.States.CONNECTED;
                seenRwServerBefore.Value |= !isRO;
                LOG.info("Session establishment complete on server " + clientCnxnSocket.getRemoteSocketAddress() +
                         ", sessionid = 0x" + sessionId.ToHexString()
                         + ", negotiated timeout = " + negotiatedSessionTimeout.Value
                         + (isRO ? " (READ-ONLY mode)" : ""));
                Watcher.Event.KeeperState eventState = (isRO)
                    ? Watcher.Event.KeeperState.ConnectedReadOnly
                    : Watcher.Event.KeeperState.SyncConnected;
                queueEvent(new WatchedEvent(
                    Watcher.Event.EventType.None, eventState, null));
            }

            private void close() {
                state.Value = ZooKeeper.States.CLOSED;
                clientCnxnSocket.wakeupCnxn();
            }
        

        /**
             * Shutdown the send/event tasks. This method should not be called
             * directly - rather it should be called as part of close operation. This
             * method is primarily here to allow the tests to verify disconnection
             * behavior.
             */

        private void disconnect() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Disconnecting client for session: 0x" + getSessionId().ToHexString());
            }
            close();
            queueEventOfDeath();
        }

        private int isDisposed;

        /**
             * Close the connection, which includes; send session disconnect to the
             * server, shutdown the send/event tasks.
             *
             */

        internal async Task closeAsync() {
            if (Interlocked.CompareExchange(ref isDisposed, 1, 0) == 0) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Closing client for session: 0x" + getSessionId().ToHexString());
                }

                RequestHeader h = new RequestHeader();
                h.set_Type((int) ZooDefs.OpCode.closeSession);

                await (await submitRequest(h, null, null, null).ContinueWith(async closeTask=>
                {
                    disconnect();
                    await closeTask.ConfigureAwait(false);
                }).ConfigureAwait(false)).ConfigureAwait(false);
            }
        }

        private int xid = 1;

        private readonly Fenced<ZooKeeper.States> state = new Fenced<ZooKeeper.States>(ZooKeeper.States.NOT_CONNECTED);
        /*
             * getXid() is called externally by ClientCnxnNIO::doIO() when packets are
             * sent from the outgoingQueue to the server. Thus, getXid() must be public.
             */

        public int getXid() {
            return Interlocked.Increment(ref xid);
        }
        
        public async Task<ReplyHeader> submitRequest(RequestHeader h, Record request, Record response,
            ZooKeeper.WatchRegistration watchRegistration) {
            ReplyHeader rep = new ReplyHeader();
            Packet packet = queuePacket(h, rep, request, response, null, null, watchRegistration);
            await packet.PacketTask.ConfigureAwait(false);
            return rep;
        }

  

        internal Packet queuePacket(RequestHeader h, ReplyHeader rep, Record request,
            Record response, string clientPath,
            string serverPath, ZooKeeper.WatchRegistration watchRegistration) {
            Packet packet;
            // Note that we do not generate the Xid for the packet yet. It is
            // generated later at send-time, by an implementation of
            // ClientCnxnSocket::doIO(),
            // where the packet is actually sent.
            lock (outgoingQueue) {
                packet = new Packet(h, rep, request, response, watchRegistration);
                packet.clientPath = clientPath;
                packet.serverPath = serverPath;
                if (!getState().isAlive() || closing.Value) {
                    conLossPacket(packet);
                }
                else {
                    // If the client is asking to close the session then
                    // mark as closing
                    if (h.get_Type() == (int) ZooDefs.OpCode.closeSession) {
                        closing.Value = true;
                    }
                    outgoingQueue.AddLast(packet);
                }
            }
            clientCnxnSocket.wakeupCnxn();
            return packet;
        }

        public void addAuthInfo(string scheme, byte[] auth) {
            if (!getState().isAlive()) {
                return;
            }
            List<AuthData> newAuthDataList = new List<AuthData>(authInfo) {new AuthData(scheme, auth)};
            authInfo = newAuthDataList;
            queuePacket(new RequestHeader(-4, (int) ZooDefs.OpCode.auth), null, new AuthPacket(0,
                scheme, auth), null, null, null, null);
        }

        internal ZooKeeper.States getState() {
            return state.Value;
        }
    }
}