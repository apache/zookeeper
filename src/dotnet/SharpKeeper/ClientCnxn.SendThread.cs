namespace SharpKeeper
{
    using System;
    using System.Collections.Generic;
    using System.IO;
    using System.Net;
    using System.Net.Sockets;
    using System.Runtime.CompilerServices;
    using System.Threading;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public partial class ClientCnxn : IClientCnxn
    {
        class SendThread
        {
            public int NegotiatedSessionTimeout { get; set; }
            private readonly ZooKeeper zooKeeper;
            private readonly EventConsumer eventConsumer;
            private List<IPEndPoint> serverAddrs;
            private int connectTimeout;
            private int readTimeout;
            private int negotiatedSessionTimeout;
            private long sessionId;
            private byte[] sessionPasswd;
            private string chrootPath;
            internal readonly LinkedList<Packet> pendingQueue = new LinkedList<Packet>();
            private System.Net.Sockets.Socket server;

            readonly byte[] buffer = new byte[4];
            MemoryStream incomingBuffer;

            bool initialized;

            private long lastPingSentNs;

            internal long sentCount = 0;
            internal long recvCount = 0;

            internal SendThread(ZooKeeper zooKeeper, EventConsumer eventConsumer, List<IPEndPoint> serverAddrs, int connectTimeout, int readTimeout, int negotiatedSessionTimeout, long sessionId, byte[] sessionPasswd, string chrootPath)
            {
                NegotiatedSessionTimeout = negotiatedSessionTimeout;
                this.zooKeeper = zooKeeper;
                this.eventConsumer = eventConsumer;
                this.serverAddrs = serverAddrs;
                this.connectTimeout = connectTimeout;
                this.readTimeout = readTimeout;
                this.negotiatedSessionTimeout = negotiatedSessionTimeout;
                this.sessionId = sessionId;
                this.sessionPasswd = sessionPasswd;
                this.chrootPath = chrootPath;
                this.zooKeeper.state = ZooKeeper.States.CONNECTING;
                incomingBuffer = new MemoryStream(buffer);
            }

            void readLength()
            {
                int len = incomingBuffer.getInt();
                if (len < 0 || len >= packetLen)
                {
                    throw new IOException("Packet len" + len + " is out of range!");
                }
                incomingBuffer = new MemoryStream(new byte[len]);
            }

            void readConnectResult()
            {
                BinaryReader bbis = new BinaryReader(incomingBuffer);
                BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
                ConnectResponse conRsp = new ConnectResponse();
                conRsp.Deserialize(bbia, "connect");
                negotiatedSessionTimeout = conRsp.TimeOut;
                if (negotiatedSessionTimeout <= 0)
                {
                    zooKeeper.state = ZooKeeper.States.CLOSED;

                    eventConsumer.queueEvent(new WatchedEvent(
                            KeeperState.Expired,
                            EventType.None,
                            null));
                    throw new SessionExpiredException(string.Format("Unable to reconnect to ZooKeeper service, session 0x{0:X} has expired", sessionId));
                }
                readTimeout = negotiatedSessionTimeout * 2 / 3;
                connectTimeout = negotiatedSessionTimeout / serverAddrs.Count;
                sessionId = conRsp.SessionId;
                sessionPasswd = conRsp.Passwd;
                zooKeeper.state = ZooKeeper.States.CONNECTED;
                LOG.info(string.Format("Session establishment complete on server {0}, sessionid = 0x{1:X}, negotiated timeout = {2}", null, sessionId, negotiatedSessionTimeout));
                eventConsumer.queueEvent(new WatchedEvent(KeeperState.SyncConnected, EventType.None, null));
            }

            void readResponse()
            {
                BinaryReader bbis = new BinaryReader(incomingBuffer);
                BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
                ReplyHeader replyHdr = new ReplyHeader();

                replyHdr.Deserialize(bbia, "header");
                if (replyHdr.Xid == -2)
                {
                    // -2 is the xid for pings
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got ping response for sessionid: 0x{0:X} after {1}ms", sessionId, (DateTime.Now.Nanos() - lastPingSentNs) / 1000000));
                    }
                    return;
                }
                if (replyHdr.Xid == -4)
                {
                    // -2 is the xid for AuthPacket
                    // TODO: process AuthPacket here
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got auth sessionid:0x{0:X}", sessionId));
                    }
                    return;
                }
                if (replyHdr.Xid == -1)
                {
                    // -1 means notification
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got notification sessionid:0x{0:X}", sessionId));
                    }
                    WatcherEvent @event = new WatcherEvent();
                    @event.Deserialize(bbia, "response");

                    // convert from a server path to a client path
                    if (chrootPath != null)
                    {
                        String serverPath = @event.Path;
                        if (serverPath.CompareTo(chrootPath) == 0)
                            @event.Path = "/";
                        else
                            @event.Path = serverPath.Substring(chrootPath.Length);
                    }

                    WatchedEvent we = new WatchedEvent(@event);
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got {0} for sessionid 0x{1:X}", we, sessionId));
                    }

                    eventConsumer.queueEvent(we);
                    return;
                }
                if (pendingQueue.Count == 0)
                {
                    throw new IOException("Nothing in the queue, but got "
                            + replyHdr.Xid);
                }
                Packet packet = null;
                lock (pendingQueue)
                {
                    packet = pendingQueue.First;
                    pendingQueue.RemoveFirst();
                }
                /*
                 * Since requests are processed in order, we better get a response
                 * to the first request!
                 */
                try
                {
                    if (packet.header.Xid != replyHdr.Xid)
                    {
                        packet.replyHeader.Err = (int)KeeperException.Code.CONNECTIONLOSS;
                        throw new IOException("Xid out of order. Got "
                                + replyHdr.Xid + " expected "
                                + packet.header.Xid);
                    }

                    packet.replyHeader.Xid = replyHdr.Xid;
                    packet.replyHeader.Err = replyHdr.Err;
                    packet.replyHeader.Zxid = replyHdr.Zxid;
                    if (replyHdr.Zxid > 0)
                    {
                        lastZxid = replyHdr.Zxid;
                    }
                    if (packet.response != null && replyHdr.Err == 0)
                    {
                        packet.response.Deserialize(bbia, "response");
                    }

                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Reading reply sessionid:0x{0:X}, packet:: {1}", sessionId, packet));
                    }
                }
                finally
                {
                    finishPacket(packet);
                }
            }

            /**
             * @return true if a packet was received
             * @throws InterruptedException
             * @throws IOException
             */
            bool doIO() {
            bool packetReceived = false;
            if (sock == null) throw new IOException("Socket is null!");
            


            if (sockKey.isReadable()) {
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new EndOfStreamException(
                            string.Format("Unable to read additional data from server sessionid 0x{0:X}, likely server has closed socket", sessionId));
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

            [MethodImpl(MethodImplOptions.Synchronized)]
            private void enableWrite()
            {
                int i = sockKey.interestOps();
                if ((i & SelectionKey.OP_WRITE) == 0)
                {
                    sockKey.interestOps(i | SelectionKey.OP_WRITE);
                }
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            private void disableWrite()
            {
                int i = sockKey.interestOps();
                if ((i & SelectionKey.OP_WRITE) != 0)
                {
                    sockKey.interestOps(i & (~SelectionKey.OP_WRITE));
                }
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            private void enableRead()
            {
                int i = sockKey.interestOps();
                if ((i & SelectionKey.OP_READ) == 0)
                {
                    sockKey.interestOps(i | SelectionKey.OP_READ);
                }
            }

            [MethodImpl(MethodImplOptions.Synchronized)]
            private void disableRead()
            {
                int i = sockKey.interestOps();
                if ((i & SelectionKey.OP_READ) != 0)
                {
                    sockKey.interestOps(i & (~SelectionKey.OP_READ));
                }
            }

            private void primeConnection(Socket k) {
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
                if (!disableAutoWatchReset && (!zooKeeper.getDataWatches().isEmpty()
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
                                null, TODO);
                    outgoingQueue.addFirst(packet);
                }

                for (AuthData id : authInfo) {
                    outgoingQueue.addFirst(new Packet(new RequestHeader(-4,
                                                                        OpCode.auth), null, new AuthPacket(0, id.scheme,
                                                                                                           id.data), null, null, null, TODO));
                }
                outgoingQueue.addFirst((new Packet(null, null, null, null, bb,
                        null, TODO)));
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

            private void sendPing()
            {
                lastPingSentNs = DateTime.Now.Nanos();
                RequestHeader h = new RequestHeader(-2, (int)OpCode.Ping);
                queuePacket(h, null, null, null, null, null, null, null, null);
            }

            int lastConnectIndex = -1;

            int currentConnectIndex;

            readonly Random r = new Random();

            private void startConnect()
            {
                if (lastConnectIndex == -1)
                {
                    // We don't want to delay the first try at a connect, so we
                    // start with -1 the first time around
                    lastConnectIndex = 0;
                }
                else
                {
                    try
                    {
                        Thread.Sleep(r.Next());
                    }
                    catch (ThreadInterruptedException e1)
                    {
                        LOG.Warn("Unexpected exception", e1);
                    }
                    if (nextAddrToTry == lastConnectIndex)
                    {
                        try
                        {
                            // Try not to spin too fast!
                            Thread.Sleep(1000);
                        }
                        catch (ThreadInterruptedException e)
                        {
                            LOG.Warn("Unexpected exception", e);
                        }
                    }
                }
                zooKeeper.state = ZooKeeper.States.CONNECTING;
                currentConnectIndex = nextAddrToTry;
                IPEndPoint addr = serverAddrs.Get(nextAddrToTry);
                nextAddrToTry++;
                if (nextAddrToTry == serverAddrs.size())
                {
                    nextAddrToTry = 0;
                }
                LOG.info("Opening socket connection to server " + addr);
                sock = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
                sock.Blocking = false;

                //sock.socket().setSoLinger(false, -1);
                //sock.socket().setTcpNoDelay(true);
                //setName(getName().replaceAll("\\(.*\\)",
                //        "(" + addr.getHostName() + ":" + addr.getPort() + ")"));
                //sockKey = sock.register(selector, SelectionKey.OP_CONNECT);

                sock.Connect(addr);
                primeConnection(sock);
                initialized = false;

                /*
                 * Reset incomingBuffer
                 */
                lenBuffer = new byte[
                incomingBuffer = new MemoryStream(new byte[4]);
            }

            private static String RETRY_CONN_MSG = ", closing socket connection and attempting reconnect";
            private Socket sock;
            private MemoryStream lenBuffer;

            public void run()
            {
                long now = DateTime.Now.Ticks;
                long lastHeard = now;
                long lastSend = now;
                while (zooKeeper.state.isAlive())
                {
                    try
                    {
                        if (sock == null)
                        {
                            // don't re-establish connection if we are closing
                            if (closing)
                            {
                                break;
                            }
                            startConnect();
                            lastSend = now;
                            lastHeard = now;
                        }
                        int idleRecv = (int)(now - lastHeard);
                        int idleSend = (int)(now - lastSend);
                        int to = readTimeout - idleRecv;
                        if (zooKeeper.state != ZooKeeper.States.CONNECTED)
                        {
                            to = connectTimeout - idleRecv;
                        }
                        if (to <= 0)
                        {
                            throw new SessionTimeoutException(
                                    string.Format("Client session timed out, have not heard from server in {0}ms for sessionid 0x{1:X}", idleRecv, sessionId));
                        }
                        if (zooKeeper.state == ZooKeeper.States.CONNECTED)
                        {
                            int timeToNextPing = readTimeout / 2 - idleSend;
                            if (timeToNextPing <= 0)
                            {
                                sendPing();
                                lastSend = now;
                                enableWrite();
                            }
                            else
                            {
                                if (timeToNextPing < to)
                                {
                                    to = timeToNextPing;
                                }
                            }
                        }

                        // Everything below and until we get back to the select is
                        // non blocking, so time is effectively a constant. That is
                        // Why we just have to do this once, here
                        now = DateTime.Now.Ticks;
                        if (outgoingQueue.Count > 0)
                        {
                            // We have something to send so it's the same
                            // as if we do the send now.
                            lastSend = now;
                        }
                        if (doIO())
                        {
                            lastHeard = now;
                        }
                        if (zooKeeper.state == ZooKeeper.States.CONNECTED)
                        {
                            if (outgoingQueue.Count > 0)
                            {
                                enableWrite();
                            }
                            else
                            {
                                disableWrite();
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        if (closing)
                        {
                            if (LOG.isDebugEnabled())
                            {
                                // closing so this is expected
                                LOG.debug(string.Format("An exception was thrown while closing send thread for session 0x{0:X} : {1}", sessionId, e.Message));
                            }
                            break;
                        }
                        else
                        {
                            // this is ugly, you have a better way speak up
                            if (e is SessionExpiredException)
                            {
                                LOG.info(e.Message + ", closing socket connection");
                            }
                            else if (e is SessionTimeoutException)
                            {
                                LOG.info(e.Message + RETRY_CONN_MSG);
                            }
                            else if (e is EndOfStreamException)
                            {
                                LOG.info(e.Message + RETRY_CONN_MSG);
                            }
                            else
                            {
                                LOG.Warn(string.Format("Session 0x{0:X} for server {1}, unexpected error{2}", sessionId, null, RETRY_CONN_MSG), e);
                            }
                            cleanup();
                            if (zooKeeper.state.isAlive())
                            {
                                eventConsumer.queueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));
                            }

                            now = DateTime.Now.Ticks;
                            lastHeard = now;
                            lastSend = now;
                        }
                    }
                }
                cleanup();
                try
                {
                    sock.Close();
                }
                catch (IOException e)
                {
                    LOG.Warn("Ignoring exception during selector close", e);
                }
                if (zooKeeper.state.isAlive())
                {
                    eventConsumer.queueEvent(new WatchedEvent(
                            KeeperState.Disconnected,
                            EventType.None,
                            null));
                }
                ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(), "SendThread exitedloop.");
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
                Thread.Sleep(100);
            } catch (ThreadInterruptedException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("SendThread interrupted during sleep, ignoring");
                }
            }
            sockKey = null;
            lock (pendingQueue) {
                for (Packet p : pendingQueue) {
                    conLossPacket(p);
                }
                pendingQueue.clear();
            }
            lock (outgoingQueue) {
                for (Packet p : outgoingQueue) {
                    conLossPacket(p);
                }
                outgoingQueue.clear();
            }
        }

            public void close()
            {
                zooKeeper.state = ZooKeeper.States.CLOSED;
                lock (this)
                {
                    selector.wakeup();
                }
            }
        }
    }
}
