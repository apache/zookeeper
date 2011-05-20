using System;
using ZooKeeperNet.IO;

namespace ZooKeeperNet
{
    using System.Collections.Generic;
    using System.IO;
    using System.Net;
    using System.Net.Sockets;
    using System.Runtime.CompilerServices;
    using System.Text;
    using System.Threading;
    using log4net;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public class ClientConnectionRequestProducer : IStartable, IDisposable
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(ClientConnectionRequestProducer));
        private const string RETRY_CONN_MSG = ", closing socket connection and attempting reconnect";

        private readonly ClientConnection conn;
        private readonly ZooKeeper zooKeeper;
        private readonly Thread requestThread;
        private readonly object pendingQueueLock = new object();
        internal readonly LinkedList<Packet> pendingQueue = new LinkedList<Packet>();
        private readonly object outgoingQueueLock = new object();
        internal readonly LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();
        private bool writeEnabled;

        private TcpClient client;
        private int lastConnectIndex;
        private readonly Random random = new Random();
        private int nextAddrToTry;
        private int currentConnectIndex;
        private bool initialized;
        internal long lastZxid;
        private long lastPingSentNs;
        internal int xid = 1;

        private byte[] lenBuffer;
        private byte[] incomingBuffer = new byte[4];
        internal int sentCount;
        internal int recvCount;
        internal int negotiatedSessionTimeout;

        public ClientConnectionRequestProducer(ClientConnection conn)
        {
            this.conn = conn;
            zooKeeper = conn.zooKeeper;
            requestThread = new Thread(new SafeThreadStart(SendRequests).Run) { Name = "ZK-SendThread" + conn.zooKeeper.Id, IsBackground = true };
        }

        protected int Xid
        {
            get { return xid++; }
        }

        public void Start()
        {
            zooKeeper.State = ZooKeeper.States.CONNECTING;
            requestThread.Start();
        }

        public Packet QueuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, string clientPath, string serverPath, ZooKeeper.WatchRegistration watchRegistration)
        {
            lock (outgoingQueueLock)
            {
                //lock here for XID?
                if (h.Type != (int)OpCode.Ping && h.Type != (int)OpCode.Auth)
                {
                    h.Xid = Xid;
                }

                Packet p = new Packet(h, r, request, response, null, watchRegistration, clientPath, serverPath);
                p.clientPath = clientPath;
                p.serverPath = serverPath;

                if (!zooKeeper.State.IsAlive())
                    ConLossPacket(p);
                else
                    outgoingQueue.AddLast(p);

                return p;
            }
        }

        public void QueuePacket(Packet p)
        {

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
                    if (client == null)
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
                            EnableWrite();
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

                    if (outgoingQueue.Count > 0)
                    {
                        // We have something to send so it's the same
                        // as if we do the send now.
                        lastSend = now;
                    }
                    if (doIO(to))
                    {
                        lastHeard = now;
                    }
                    if (zooKeeper.State == ZooKeeper.States.CONNECTED)
                    {
                        if (outgoingQueue.Count > 0)
                        {
                            EnableWrite();
                        }
                        else
                        {
                            DisableWrite();
                        }
                    }
                }
                catch (Exception e)
                {
                    if (conn.closing)
                    {
                        if (LOG.IsDebugEnabled)
                        {
                            // closing so this is expected
                            LOG.Debug(string.Format("An exception was thrown while closing send thread for session 0x{0:X} : {1}", conn.SessionId, e.Message));
                        }
                        break;
                    }
                    
                    // this is ugly, you have a better way speak up
                    if (e is KeeperException.SessionExpiredException)
                    {
                        LOG.Info(e.Message + ", closing socket connection");
                    }
                    else if (e is SessionTimeoutException)
                    {
                        LOG.Info(e.Message + RETRY_CONN_MSG);
                    }
                    else if (e is System.IO.EndOfStreamException)
                    {
                        LOG.Info(e.Message + RETRY_CONN_MSG);
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
                    client = null;
                }
            }
            Cleanup();
            if (zooKeeper.State.IsAlive())
            {
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));
            }
            
            if (LOG.IsDebugEnabled) LOG.Debug("SendThread exitedloop.");
        }

        private void Cleanup()
        {
            if (client != null)
            {
                try
                {
                    client.Close();
                }
                catch (IOException e)
                {
                    if (LOG.IsDebugEnabled)
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
                if (LOG.IsDebugEnabled)
                {
                    LOG.Debug("SendThread interrupted during sleep, ignoring");
                }
            }

            lock (pendingQueueLock)
            {
                foreach (Packet p in pendingQueue)
                {
                    ConLossPacket(p);
                }
                pendingQueue.Clear();
            }

            lock (outgoingQueueLock)
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
                    Thread.Sleep(new TimeSpan(0, 0, 0, 0, random.Next(0, 50)));
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
            LOG.Info("Opening socket connection to server " + addr);
            client = new TcpClient();
            client.LingerState = new LingerOption(false, 0);
            client.NoDelay = true;
            
            ConnectSocket(addr);

            //sock.Blocking = true;
            PrimeConnection(client);
            initialized = false;
        }

        private void ConnectSocket(IPEndPoint addr)
        {
            bool connected = false;
            ManualResetEvent socketConnectTimeout = new ManualResetEvent(false);
            ThreadPool.QueueUserWorkItem(state =>
            {
                try
                {
                    client.Connect(addr);
                    connected = true;
                    socketConnectTimeout.Set();
                } 
                // ReSharper disable EmptyGeneralCatchClause
                catch
                // ReSharper restore EmptyGeneralCatchClause
                {                    
                }
            });
            socketConnectTimeout.WaitOne(10000);
            
            if (connected) return;

            throw new InvalidOperationException(string.Format("Could not make socket connection to {0}:{1}", addr.Address, addr.Port));
        }

        private void PrimeConnection(TcpClient client)
        {
            LOG.Info(string.Format("Socket connection established to {0}, initiating session", client.Client.RemoteEndPoint));
            lastConnectIndex = currentConnectIndex;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid, Convert.ToInt32(conn.SessionTimeout.TotalMilliseconds), conn.SessionId, conn.SessionPassword);

            byte[] buffer;
            using (MemoryStream ms = new MemoryStream())
            using (EndianBinaryWriter writer = new EndianBinaryWriter(EndianBitConverter.Big, ms, Encoding.UTF8))
            {
                BinaryOutputArchive boa = BinaryOutputArchive.getArchive(writer);
                boa.WriteInt(-1, "len");
                conReq.Serialize(boa, "connect");
                ms.Position = 0;
                writer.Write(ms.ToArray().Length - 4);                buffer = ms.ToArray();
            }
            lock (outgoingQueueLock)
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

            lock (this)
            {
                EnableWrite();
            }

            if (LOG.IsDebugEnabled)
            {
                LOG.Debug("Session establishment request sent on " + client.Client.RemoteEndPoint);
            }

        }

        private void SendPing()
        {
            lastPingSentNs = DateTime.Now.Nanos();
            RequestHeader h = new RequestHeader(-2, (int)OpCode.Ping);
            conn.QueuePacket(h, null, null, null, null, null, null, null, null);
        }

        bool doIO(TimeSpan to)
        {
            bool packetReceived = false;
            if (client == null) throw new IOException("Socket is null!");

            if (client.Client.Poll(Convert.ToInt32(to.TotalMilliseconds / 1000000), SelectMode.SelectRead))
            {
                packetReceived = true;
                int total = 0;
                int current = total = client.GetStream().Read(incomingBuffer, total, incomingBuffer.Length - total);

                while (total < incomingBuffer.Length && current > 0)
                {
                    current = client.GetStream().Read(incomingBuffer, total, incomingBuffer.Length - total);
                    total += current;
                }

                if (current <= 0)
                {
                    throw new EndOfStreamException(string.Format("Unable to read additional data from server sessionid 0x{0:X}, likely server has closed socket",
                            conn.SessionId));
                }

                if (lenBuffer == null)
                {
                    lenBuffer = incomingBuffer;
                    recvCount++;
                    ReadLength();
                }
                else if (!initialized)
                {
                    ReadConnectResult();
                    if (!outgoingQueue.IsEmpty()) EnableWrite();
                    lenBuffer = null;
                    incomingBuffer = new byte[4];
                    initialized = true;
                }
                else
                {
                    ReadResponse();
                    lenBuffer = null;
                    incomingBuffer = new byte[4];
                }
            }
            else if (writeEnabled && client.Client.Poll(Convert.ToInt32(to.TotalMilliseconds / 1000000), SelectMode.SelectWrite))
            {
                lock (outgoingQueueLock)
                {
                    if (!outgoingQueue.IsEmpty())
                    {
                        Packet first = outgoingQueue.First.Value;
                        client.GetStream().Write(first.data, 0, first.data.Length);
                        sentCount++;
                        outgoingQueue.RemoveFirst();
                        if (first.header != null && first.header.Type != (int)OpCode.Ping &&
                            first.header.Type != (int)OpCode.Auth)
                        {
                            pendingQueue.AddLast(first);
                        }
                    }
                }
            }

            if (outgoingQueue.IsEmpty())
            {
                DisableWrite();
            }
            else
            {
                EnableWrite();
            }
            return packetReceived;
        }

        private void ReadLength() 
        {
            lenBuffer = new byte[4];
            using (EndianBinaryReader reader = new EndianBinaryReader(EndianBitConverter.Big, new MemoryStream(incomingBuffer), Encoding.UTF8))
            {
                int len = reader.ReadInt32();
                if (len < 0 || len >= ClientConnection.packetLen)
                {
                    throw new IOException("Packet len " + len + " is out of range!");
                }
                incomingBuffer = new byte[len];
            }
        }

        private void ReadConnectResult()
        {
            using (var reader = new EndianBinaryReader(EndianBitConverter.Big, new MemoryStream(incomingBuffer), Encoding.UTF8))
            {
                BinaryInputArchive bbia = BinaryInputArchive.GetArchive(reader);
                ConnectResponse conRsp = new ConnectResponse();
                conRsp.Deserialize(bbia, "connect");
                negotiatedSessionTimeout = conRsp.TimeOut;
                if (negotiatedSessionTimeout <= 0)
                {
                    zooKeeper.State = ZooKeeper.States.CLOSED;
                    conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Expired, EventType.None, null));
                    throw new SessionExpiredException(string.Format("Unable to reconnect to ZooKeeper service, session 0x{0:X} has expired", conn.SessionId));
                }
                conn.readTimeout = new TimeSpan(0, 0, 0, 0, negotiatedSessionTimeout*2/3);
                conn.connectTimeout = new TimeSpan(0, 0, 0, negotiatedSessionTimeout/conn.serverAddrs.Count);
                conn.SessionId = conRsp.SessionId;
                conn.SessionPassword = conRsp.Passwd;
                zooKeeper.State = ZooKeeper.States.CONNECTED;
                LOG.Info(string.Format("Session establishment complete on server {0:X}, negotiated timeout = {1}", conn.SessionId, negotiatedSessionTimeout));
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.SyncConnected, EventType.None, null));
            }
        }

        private void ReadResponse()
        {
            using (MemoryStream ms = new MemoryStream(incomingBuffer))
            using (var reader = new EndianBinaryReader(EndianBitConverter.Big, ms, Encoding.UTF8))
            {
                BinaryInputArchive bbia = BinaryInputArchive.GetArchive(reader);
                ReplyHeader replyHdr = new ReplyHeader();

                replyHdr.Deserialize(bbia, "header");
                if (replyHdr.Xid == -2)
                {
                    // -2 is the xid for pings
                    if (LOG.IsDebugEnabled)
                    {
                        LOG.Debug(string.Format("Got ping response for sessionid: 0x{0:X} after {1}ms", conn.SessionId, (DateTime.Now.Nanos() - lastPingSentNs)/1000000));
                    }
                    return;
                }
                if (replyHdr.Xid == -4)
                {
                    // -2 is the xid for AuthPacket
                    // TODO: process AuthPacket here
                    if (LOG.IsDebugEnabled)
                    {
                        LOG.Debug(string.Format("Got auth sessionid:0x{0:X}", conn.SessionId));
                    }
                    return;
                }
                if (replyHdr.Xid == -1)
                {
                    // -1 means notification
                    if (LOG.IsDebugEnabled)
                    {
                        LOG.Debug(string.Format("Got notification sessionid:0x{0}", conn.SessionId));
                    }
                    WatcherEvent @event = new WatcherEvent(); 
                    @event.Deserialize(bbia, "response");

                    // convert from a server path to a client path
                    if (conn.ChrootPath != null)
                    {
                        string serverPath = @event.Path;
                        if (serverPath.CompareTo(conn.ChrootPath) == 0)
                            @event.Path = "/";
                        else
                            @event.Path = serverPath.Substring(conn.ChrootPath.Length);
                    }

                    WatchedEvent we = new WatchedEvent(@event);
                    if (LOG.IsDebugEnabled)
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
                
                Packet packet;
                lock (pendingQueueLock)
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

                    if (LOG.IsDebugEnabled)
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

        [MethodImpl(MethodImplOptions.Synchronized)]
        public void EnableWrite()
        {
            writeEnabled = true;
        }

        [MethodImpl(MethodImplOptions.Synchronized)]
        public void DisableWrite()
        {
            writeEnabled = false;
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

            p.Finished = true;
            conn.consumer.QueuePacket(p);
        }
        public void Dispose()
        {
            zooKeeper.State = ZooKeeper.States.CLOSED;
            requestThread.Join();
        }
    }

}
