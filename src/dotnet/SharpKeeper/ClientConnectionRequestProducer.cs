using System;

namespace SharpKeeper
{
    using System.Collections.Generic;
    using System.IO;
    using System.Net;
    using System.Net.Sockets;
    using System.Threading;
    using MiscUtil.Conversion;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public class ClientConnectionRequestProducer : IStartable, IDisposable
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientConnectionRequestProducer));
        private const string RETRY_CONN_MSG = ", closing socket connection and attempting reconnect";

        private readonly ClientConnection conn;
        private readonly ZooKeeper zooKeeper;
        private readonly Thread requestThread;
        private readonly ReaderWriterLockSlim pendingQueueLock = new ReaderWriterLockSlim();
        private readonly ReaderWriterLockSlim outgoingQueueLock = new ReaderWriterLockSlim();
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
            requestThread = new Thread(new SafeThreadStart(SendRequests).Run) { Name = "ZK-SendThread", IsBackground = true };
            incomingBuffer = lenBuffer;
        }

        public void Start()
        {
            zooKeeper.State = ZooKeeper.States.CONNECTING;
            requestThread.Start();
        }

        public void QueuePacket(Packet p)
        {
            if (!zooKeeper.State.IsAlive())
            {
                ConLossPacket(p);
                return;
            }                

            outgoingQueueLock.EnterReadLock();
            try
            {
                outgoingQueue.AddLast(p);    
            } 
            finally
            {
                outgoingQueueLock.ExitReadLock();
            }
        }

        public void SendRequests()
        {
            DateTime now = DateTime.Now;
            DateTime lastHeard = now;
            DateTime lastSend = now;
            while (zooKeeper.State.IsAlive())
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
                    TimeSpan idleRecv = now - lastHeard;
                    TimeSpan idleSend = now - lastSend;
                    TimeSpan to = conn.readTimeout - idleRecv;
                    if (zooKeeper.State != ZooKeeper.States.CONNECTED)
                    {
                        to = conn.connectTimeout - idleRecv;
                    }
                    if (to <= TimeSpan.Zero)
                    {
                        throw new SessionTimeoutException(
                                string.Format("Client session timed out, have not heard from server in {0}ms for sessionid 0x{1:X}", idleRecv, conn.SessionId));
                    }
                    if (zooKeeper.State == ZooKeeper.States.CONNECTED)
                    {
                        TimeSpan timeToNextPing = new TimeSpan(0, 0, 0, 0, Convert.ToInt32(conn.readTimeout.TotalMilliseconds / 2 - idleSend.TotalMilliseconds));
                        if (timeToNextPing <= TimeSpan.Zero)
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
                    now = DateTime.Now;

                    int outgoingQueueCount;
                    outgoingQueueLock.EnterReadLock();
                    try
                    {
                        outgoingQueueCount = outgoingQueue.Count;
                    }
                    finally
                    {
                        outgoingQueueLock.ExitReadLock();
                    }

                    if (outgoingQueueCount > 0)
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
                        if (LOG.IsDebugEnabled())
                        {
                            // closing so this is expected
                            LOG.Debug(string.Format("An exception was thrown while closing send thread for session 0x{0:X} : {1}", conn.SessionId, e.Message));
                        }
                        break;
                    }
                    
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
                    if (zooKeeper.State.IsAlive())
                    {
                        conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));
                    }

                    now = DateTime.Now;
                    lastHeard = now;
                    lastSend = now;
                    sock = null;
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
            if (zooKeeper.State.IsAlive())
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
                    if (LOG.IsDebugEnabled())
                    {
                        LOG.Debug("Ignoring exception during channel close", e);
                    }
                }
            }
            try
            {
                Thread.Sleep(100);
            }
            catch (ThreadInterruptedException e)
            {
                if (LOG.IsDebugEnabled())
                {
                    LOG.Debug("SendThread interrupted during sleep, ignoring");
                }
            }
            
            pendingQueueLock.EnterWriteLock();
            try 
            {
                foreach (Packet p in pendingQueue)
                {
                    ConLossPacket(p);
                }
                pendingQueue.Clear();
            }
            finally
            {
                pendingQueueLock.ExitWriteLock();
            }


            Packet[] queue;
            outgoingQueueLock.EnterWriteLock();
            try
            {
                queue = new Packet[outgoingQueue.Count];
                outgoingQueue.CopyTo(queue, 0);
                outgoingQueue.Clear();
            }
            finally
            {
                outgoingQueueLock.ExitWriteLock();
            }

            foreach (Packet p in queue)
            {
                ConLossPacket(p);
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
                    Thread.Sleep(new TimeSpan(0, 0, 0, 0, r.Next(0, 50)));
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
            sock.LingerState = new LingerOption(false, 0);
            sock.NoDelay = true;

            sock.Connect(addr);
            sock.Blocking = false;
            PrimeConnection(sock);
            initialized = false;

            incomingBuffer = new byte[4];
        }

        private void PrimeConnection(Socket socket)
        {
            LOG.info(string.Format("Socket connection established to {0}, initiating session", socket.RemoteEndPoint));
            lastConnectIndex = currentConnectIndex;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid, Convert.ToInt32(conn.SessionTimeout.TotalMilliseconds), conn.SessionId, conn.SessionPassword);

            byte[] buffer;
            using (MemoryStream ms = new MemoryStream())
            using (ZooKeeperBinaryWriter writer = new ZooKeeperBinaryWriter(ms))
            {
                BinaryOutputArchive boa = BinaryOutputArchive.getArchive(writer);
                boa.WriteInt(-1, "len");
                conReq.Serialize(boa, "connect");
                ms.Position = 0;
                writer.Write(ms.ToArray().Length);                buffer = ms.ToArray();
            }
            outgoingQueueLock.EnterWriteLock();
            try
            {
                if (!ClientConnection.disableAutoWatchReset && (!zooKeeper.DataWatches.IsEmpty() || !zooKeeper.ExistWatches.IsEmpty() || !zooKeeper.ChildWatches.IsEmpty()))
                {
                    var sw = new SetWatches(lastZxid, zooKeeper.DataWatches, zooKeeper.ExistWatches, zooKeeper.ChildWatches);
                    var h = new RequestHeader();
                    h.Type = (int)OpCode.SetWatches;
                    h.Xid = -8;
                    Packet packet = new Packet(h, new ReplyHeader(), sw, null, null, null, null, null);
                    outgoingQueue.AddFirst(packet);
                }

                foreach (ClientConnection.AuthData id in conn.authInfo)
                {
                    outgoingQueue.AddFirst(new Packet(new RequestHeader(-4, (int)OpCode.Auth), null, new AuthPacket(0, id.scheme, id.data), null, null, null, null, null));
                }


                outgoingQueue.AddFirst((new Packet(null, null, null, null, buffer, null, null, null)));
            }
            finally
            {
                outgoingQueueLock.ExitWriteLock();
            }
            if (LOG.IsDebugEnabled())
            {
                LOG.Debug("Session establishment request sent on " + socket.RemoteEndPoint);
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

            if (sock.Poll(100000, SelectMode.SelectRead))
            {
                var buffer = new byte[4];
                int rc = sock.Receive(buffer);
                if (rc < 0)
                {
                    throw new EndOfStreamException(string.Format("Unable to read additional data from server sessionid 0x{0:X}, likely server has closed socket",
                            conn.SessionId));
                }

                var remaining = BigEndianBitConverter.INSTANCE.ToInt32(buffer, 0);
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
            else if (sock.Poll(100000, SelectMode.SelectWrite))
            {
                outgoingQueueLock.EnterUpgradeableReadLock();
                try
                {
                    if (!outgoingQueue.IsEmpty())
                    {
                        outgoingQueueLock.EnterWriteLock();
                        Packet first;
                        try
                        {
                            first = outgoingQueue.First.Value;
                            sock.Send(first.data);
                            outgoingQueue.RemoveFirst();
                        }                        finally
                        {
                            outgoingQueueLock.ExitWriteLock();
                        }
                        if (first.header != null && first.header.Type != (int) OpCode.Ping && first.header.Type != (int) OpCode.Auth)
                        {
                            pendingQueueLock.EnterWriteLock();
                            try
                            {
                                pendingQueue.AddLast(first);
                            }                            finally
                            {
                                pendingQueueLock.ExitWriteLock();
                            }
                        }
                    }
                } 
                finally
                {
                    outgoingQueueLock.ExitUpgradeableReadLock();    
                }
            }

            return packetReceived;
        }

        private void ReadLength() 
        {
            using (ZooKeeperBinaryReader reader = new ZooKeeperBinaryReader(new MemoryStream(lenBuffer))) 
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
            using (var reader = new ZooKeeperBinaryReader(new MemoryStream(incomingBuffer)))
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
                conn.readTimeout = new TimeSpan(0, 0, 0, 0, negotiatedSessionTimeout*2/3);
                conn.connectTimeout = new TimeSpan(0, 0, 0, negotiatedSessionTimeout/conn.serverAddrs.Count);
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
            using (ZooKeeperBinaryReader reader = new ZooKeeperBinaryReader(ms))
            {
                BinaryInputArchive bbia = BinaryInputArchive.getArchive(reader);
                ReplyHeader replyHdr = new ReplyHeader();

                replyHdr.Deserialize(bbia, "header");
                if (replyHdr.Xid == -2)
                {
                    // -2 is the xid for pings
                    if (LOG.IsDebugEnabled())
                    {
                        LOG.Debug(string.Format("Got ping response for sessionid: 0x{0:X} after {1}ms", conn.SessionId, (DateTime.Now.Nanos() - lastPingSentNs)/1000000));
                    }
                    return;
                }
                if (replyHdr.Xid == -4)
                {
                    // -2 is the xid for AuthPacket
                    // TODO: process AuthPacket here
                    if (LOG.IsDebugEnabled())
                    {
                        LOG.Debug(string.Format("Got auth sessionid:0x{0:X}", conn.SessionId));
                    }
                    return;
                }
                if (replyHdr.Xid == -1)
                {
                    // -1 means notification
                    if (LOG.IsDebugEnabled())
                    {
                        LOG.Debug(string.Format("Got notification sessionid:0x{0}", conn.SessionId));
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
                    if (LOG.IsDebugEnabled())
                    {
                        LOG.Debug(string.Format("Got {0} for sessionid 0x{1:X}", we, conn.SessionId));
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

                    if (LOG.IsDebugEnabled())
                    {
                        LOG.Debug(string.Format("Reading reply sessionid:0x{0:X}, packet:: {1}", conn.SessionId, packet));
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
