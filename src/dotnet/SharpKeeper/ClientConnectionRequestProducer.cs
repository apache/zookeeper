using System;

namespace SharpKeeper
{
    using System.Collections.Generic;
    using System.IO;
    using System.Net;
    using System.Net.Sockets;
    using System.Threading;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public class ClientConnectionRequestProducer : IStartable, IDisposable
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientConnectionRequestProducer));
        private const string RETRY_CONN_MSG = ", closing socket connection and attempting reconnect";

        private readonly ClientConnection conn;
        private ZooKeeper zooKeeper;
        private readonly Thread requestThread;
        private readonly LinkedList<Packet> pendingQueue = new LinkedList<Packet>();
        private readonly LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();
        private Socket sock;
        private int lastConnectIndex;
        private readonly Random r = new Random();
        private int nextAddrToTry;
        private int currentConnectIndex;
        private bool initialized;
        private long lastZxid;
        private long lastPingSentNs;
        
        private byte[] lenBuffer = new byte[4];
        private byte[] incomingBuffer;
        private int recvCount;
        private int negotiatedSessionTimeout;

        public ClientConnectionRequestProducer(ClientConnection conn)
        {
            this.conn = conn;
            zooKeeper = conn.zooKeeper;
            requestThread = new Thread(SendRequests) { Name = "ZK-SendThread", IsBackground = true };
            incomingBuffer = lenBuffer;
        }

        public void Start()
        {
            requestThread.Start();
        }

        public void SendRequests()
        {
            long now = DateTime.Now.Ticks;
            long lastHeard = now;
            long lastSend = now;
            while (zooKeeper.State.isAlive())
            {
                try
                {
                    if (sock == null)
                    {
                        // don't re-establish connection if we are closing
                        if (conn.closing)
                        {
                            break;
                        }
                        StartConnect();
                        lastSend = now;
                        lastHeard = now;
                    }
                    int idleRecv = (int)(now - lastHeard);
                    int idleSend = (int)(now - lastSend);
                    int to = conn.readTimeout - idleRecv;
                    if (zooKeeper.State != ZooKeeper.States.CONNECTED)
                    {
                        to = conn.connectTimeout - idleRecv;
                    }
                    if (to <= 0)
                    {
                        throw new SessionTimeoutException(
                                string.Format("Client session timed out, have not heard from server in {0}ms for sessionid 0x{1:X}", idleRecv, conn.SessionId));
                    }
                    if (zooKeeper.State == ZooKeeper.States.CONNECTED)
                    {
                        int timeToNextPing = conn.readTimeout / 2 - idleSend;
                        if (timeToNextPing <= 0)
                        {
                            SendPing();
                            lastSend = now;
                            //EnableWrite();
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
                }
                catch (Exception e)
                {
                    if (conn.closing)
                    {
                        if (LOG.isDebugEnabled())
                        {
                            // closing so this is expected
                            LOG.debug(string.Format("An exception was thrown while closing send thread for session 0x{0:X} : {1}", conn.SessionId, e.Message));
                        }
                        break;
                    }
                    else
                    {
                        // this is ugly, you have a better way speak up
                        if (e is KeeperException.SessionExpiredException)
                        {
                            LOG.info(e.Message + ", closing socket connection");
                        }
                        else if (e is SessionTimeoutException)
                        {
                            LOG.info(e.Message + RETRY_CONN_MSG);
                        }
                        else if (e is System.IO.EndOfStreamException)
                        {
                            LOG.info(e.Message + RETRY_CONN_MSG);
                        }
                        else
                        {
                            LOG.Warn(string.Format("Session 0x{0:X} for server {1}, unexpected error{2}", conn.SessionId, null, RETRY_CONN_MSG), e);
                        }
                        Cleanup();
                        if (zooKeeper.State.isAlive())
                        {
                            conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));
                        }

                        now = DateTime.Now.Ticks;
                        lastHeard = now;
                        lastSend = now;
                    }
                }
            }
            Cleanup();
            try
            {
                sock.Close();
            }
            catch (IOException e)
            {
                LOG.Warn("Ignoring exception during selector close", e);
            }
            if (zooKeeper.State.isAlive())
            {
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));
            }
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(), "SendThread exitedloop.");
        }

        private void Cleanup()
        {
            if (sock != null)
            {
                try
                {
                    sock.Close();
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Ignoring exception during channel close", e);
                    }
                }
            }
            try
            {
                Thread.Sleep(100);
            }
            catch (ThreadInterruptedException e)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("SendThread interrupted during sleep, ignoring");
                }
            }
            lock (pendingQueue)
            {
                foreach (Packet p in pendingQueue)
                {
                    ConLossPacket(p);
                }
                pendingQueue.Clear();
            }
            lock (outgoingQueue)
            {
                foreach (Packet p in outgoingQueue)
                {
                    ConLossPacket(p);
                }
                outgoingQueue.Clear();
            }
        }

        private void StartConnect()
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
            zooKeeper.State = ZooKeeper.States.CONNECTING;
            currentConnectIndex = nextAddrToTry;
            IPEndPoint addr = conn.serverAddrs[nextAddrToTry];
            nextAddrToTry++;
            if (nextAddrToTry == conn.serverAddrs.Count)
            {
                nextAddrToTry = 0;
            }
            LOG.info("Opening socket connection to server " + addr);
            sock = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            sock.Blocking = false;
            sock.LingerState = new LingerOption(false, -1);
            sock.NoDelay = true;
            

            sock.Connect(addr);
            PrimeConnection(sock);
            initialized = false;

            incomingBuffer = new byte[4];
        }

        private void PrimeConnection(Socket socket)
        {
            LOG.info(string.Format("Socket connection established to {0}, initiating session", socket.RemoteEndPoint));
            lastConnectIndex = currentConnectIndex;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid, Convert.ToInt32(conn.SessionTimeout.TotalMilliseconds), conn.SessionId, conn.SessionPassword);
            using (MemoryStream ms = new MemoryStream())
            using (BinaryWriter writer = new BinaryWriter(ms))
            {
                BinaryOutputArchive boa = BinaryOutputArchive.getArchive(writer);
                boa.WriteInt(-1, "len");
                conReq.Serialize(boa, "connect");
                lock (outgoingQueue)
                {
                    if (!ClientConnection.disableAutoWatchReset &&
                        (!zooKeeper.DataWatches.IsEmpty() || !zooKeeper.ExistWatches.IsEmpty() ||
                         !zooKeeper.ChildWatches.IsEmpty()))
                    {
                        SetWatches sw = new SetWatches(lastZxid, zooKeeper.DataWatches, zooKeeper.ExistWatches,
                                                       zooKeeper.ChildWatches);
                        RequestHeader h = new RequestHeader();
                        h.Type = (int) OpCode.SetWatches;
                        h.Xid = -8;
                        Packet packet = new Packet(h, new ReplyHeader(), sw, null, null, null);
                        outgoingQueue.AddFirst(packet);
                    }

                    foreach (ClientConnection.AuthData id in conn.authInfo)
                    {
                        outgoingQueue.AddFirst(new Packet(new RequestHeader(-4, (int) OpCode.Auth), null,
                                                          new AuthPacket(0, id.scheme, id.data), null, null, null));
                    }

                    ms.Position = 0;
                    outgoingQueue.AddFirst((new Packet(null, null, null, null, ms.GetBuffer().WrapLength(), null)));
                }
            }
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Session establishment request sent on " + socket.RemoteEndPoint);
            }

        }

        private void SendPing()
        {
            lastPingSentNs = DateTime.Now.Nanos();
            RequestHeader h = new RequestHeader(-2, (int)OpCode.Ping);
            conn.QueuePacket(h, null, null, null, null, null, null, null, null);
        }

        bool doIO()
        {
            bool packetReceived = false;
            if (sock == null) throw new IOException("Socket is null!");

            if (sock.Poll(-1, SelectMode.SelectRead))
            {
                var buffer = new byte[4];
                int rc = sock.Receive(buffer);
                if (rc < 0)
                {
                    throw new EndOfStreamException(
                        string.Format(
                            "Unable to read additional data from server sessionid 0x{0:X}, likely server has closed socket",
                            conn.SessionId));
                }

                var remaining = BitConverter.ToInt32(buffer, 0);
                buffer = new byte[remaining];
                sock.Receive(buffer, buffer.Length, SocketFlags.None);

                if (incomingBuffer == lenBuffer)
                {
                    recvCount++;
                    ReadLength();
                }
                else if (!initialized)
                {
                    ReadConnectResult();
                    lenBuffer = new byte[4];
                    incomingBuffer = lenBuffer;
                    packetReceived = true;
                    initialized = true;
                }
                else
                {
                    ReadResponse();
                    lenBuffer = new byte[4];
                    incomingBuffer = lenBuffer;
                    packetReceived = true;
                }
            }
            else if (sock.Poll(-1, SelectMode.SelectWrite))
            {
                lock (outgoingQueue)
                {
                    if (!outgoingQueue.IsEmpty())
                    {
                        Packet first = outgoingQueue.First.Value;
                        sock.Send(first.data);
                        outgoingQueue.RemoveFirst();
                        if (first.header != null && first.header.Type != (int) OpCode.Ping &&
                            first.header.Type != (int) OpCode.Auth)
                        {
                            pendingQueue.AddLast(first);
                        }
                    }
                }
            }

            return packetReceived;
        }

        private void ReadLength()
        {
            using (BinaryReader reader = new BinaryReader(new MemoryStream(incomingBuffer)))
            {
                int len = reader.ReadInt32();
                if (len < 0 || len >= ClientConnection.packetLen)
                {
                    throw new IOException("Packet len" + len + " is out of range!");
                }
                incomingBuffer = new byte[len];
            }
        }

        private void ReadConnectResult()
        {
            using (BinaryReader reader = new BinaryReader(new MemoryStream(incomingBuffer)))
            {
                BinaryInputArchive bbia = BinaryInputArchive.getArchive(reader);
                ConnectResponse conRsp = new ConnectResponse();
                conRsp.Deserialize(bbia, "connect");
                negotiatedSessionTimeout = conRsp.TimeOut;
                if (negotiatedSessionTimeout <= 0)
                {
                    zooKeeper.State = ZooKeeper.States.CLOSED;
                    conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Expired, EventType.None, null));
                    throw new SessionExpiredException(string.Format("Unable to reconnect to ZooKeeper service, session 0x{{0:X}}{0} has expired", conn.SessionId));
                }
                conn.readTimeout = negotiatedSessionTimeout*2/3;
                conn.connectTimeout = negotiatedSessionTimeout/conn.serverAddrs.Count;
                conn.SessionId = conRsp.SessionId;
                conn.SessionPassword = conRsp.Passwd;
                zooKeeper.State = ZooKeeper.States.CONNECTED;
                LOG.info(string.Format("Session establishment complete on server {{0:X}} {0}, negotiated timeout = {1}", conn.SessionId, negotiatedSessionTimeout));
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.SyncConnected, EventType.None, null));
            }
        }

        private void ReadResponse()
        {
            using (MemoryStream ms = new MemoryStream(incomingBuffer))
            using (BinaryReader reader = new BinaryReader(ms))
            {
                BinaryInputArchive bbia = BinaryInputArchive.getArchive(reader);
                ReplyHeader replyHdr = new ReplyHeader();

                replyHdr.Deserialize(bbia, "header");
                if (replyHdr.Xid == -2)
                {
                    // -2 is the xid for pings
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got ping response for sessionid: 0x{0:X} after {1}ms", conn.SessionId, (DateTime.Now.Nanos() - lastPingSentNs)/1000000));
                    }
                    return;
                }
                if (replyHdr.Xid == -4)
                {
                    // -2 is the xid for AuthPacket
                    // TODO: process AuthPacket here
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got auth sessionid:0x{0:X}", conn.SessionId));
                    }
                    return;
                }
                if (replyHdr.Xid == -1)
                {
                    // -1 means notification
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got notification sessionid:0x{0}", conn.SessionId));
                    }
                    WatcherEvent @event = new WatcherEvent(); 
                    @event.Deserialize(bbia, "response");

                    // convert from a server path to a client path
                    if (conn.ChrootPath != null)
                    {
                        String serverPath = @event.Path;
                        if (serverPath.CompareTo(conn.ChrootPath) == 0)
                        @event.Path = "/";
                    else
                        @event.Path = serverPath.Substring(conn.ChrootPath.Length);
                    }

                    WatchedEvent we = new WatchedEvent(@event);
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(string.Format("Got {0} for sessionid 0x{1:X}", we, conn.SessionId));
                    }

                    conn.consumer.QueueEvent(we);
                    return;
                }
                if (pendingQueue.IsEmpty())
                {
                    throw new IOException(string.Format("Nothing in the queue, but got {0}", replyHdr.Xid));
                }
                Packet packet = null;
                lock(pendingQueue)
                {
                    packet = pendingQueue.First.Value;
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
                        throw new IOException(string.Format("Xid out of order. Got {0} expected {1}", replyHdr.Xid, packet.header.Xid));
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
                        LOG.debug(string.Format("Reading reply sessionid:0x{0:X}, packet:: {1}", conn.SessionId, packet));
                    }
                }
                finally
                {
                    FinishPacket(packet);
                }
            }
        }

        private void ConLossPacket(Packet p)
        {
            if (p.replyHeader == null) return;
            
            string state = zooKeeper.State.State;
            if (state == ZooKeeper.States.AUTH_FAILED.State)
                p.replyHeader.Err = (int)KeeperException.Code.AUTHFAILED;
            else if (state == ZooKeeper.States.CLOSED.State)
                p.replyHeader.Err = (int)KeeperException.Code.SESSIONEXPIRED;
            else
                p.replyHeader.Err = (int)KeeperException.Code.CONNECTIONLOSS;

            FinishPacket(p);        
        }

        private void FinishPacket(Packet p)
        {
            if (p.watchRegistration != null)
            {
                p.watchRegistration.Register(p.replyHeader.Err);
            }

            if (p.cb == null)
            {
                lock (p)
                {
                    p.finished = true;
                    Monitor.PulseAll(p);
                }
            }
            else
            {
                p.finished = true;
                conn.consumer.QueuePacket(p);
            }
        }

        public void Dispose()
        {
            requestThread.Join();
        }
    }

}
