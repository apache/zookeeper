using System;
using ZooKeeperNet.IO;

namespace ZooKeeperNet
{
    using System.IO;
    using System.Net;
    using System.Net.Sockets;
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
        private readonly ManualResetEvent queueEvent = new ManualResetEvent(false);
        
        internal readonly System.Collections.Concurrent.ConcurrentQueue<Packet> pendingQueue = new System.Collections.Concurrent.ConcurrentQueue<Packet>();
        internal readonly System.Collections.Concurrent.ConcurrentQueue<Packet> outgoingQueue = new System.Collections.Concurrent.ConcurrentQueue<Packet>();

        private TcpClient client;
        private int lastConnectIndex;
        private readonly Random random = new Random();
        private int nextAddrToTry;
        private int currentConnectIndex;
        private bool initialized;
        internal long lastZxid;
        private long lastPingSentNs;
        internal int xid = 1;

        private byte[] incomingBuffer = new byte[4];
        internal int sentCount;
        internal int recvCount;
        internal int negotiatedSessionTimeout;

        public ClientConnectionRequestProducer(ClientConnection conn)
        {
            this.conn = conn;
            zooKeeper = conn.zooKeeper;
            StartConnect();
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
            {
                outgoingQueue.Enqueue(p);
                queueEvent.Set();
            }
            return p;
        }

        public void SendRequests()
        {
            DateTime now = DateTime.Now;
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
                    }
                    TimeSpan idleSend = now - lastSend;
                    TimeSpan to = conn.readTimeout - idleSend;
                    if (zooKeeper.State != ZooKeeper.States.CONNECTED)
                    {
                        to = conn.connectTimeout - idleSend;
                    }
                    if (to <= TimeSpan.Zero)
                    {
                        throw new SessionTimeoutException(
                                string.Format("Client session timed out, have not heard from server in {0}ms for sessionid 0x{1:X}", idleSend, conn.SessionId));
                    }
                    if (zooKeeper.State == ZooKeeper.States.CONNECTED)
                    {
                        TimeSpan timeToNextPing = new TimeSpan(0, 0, 0, 0, Convert.ToInt32(conn.readTimeout.TotalMilliseconds / 2 - idleSend.TotalMilliseconds));
                        if (timeToNextPing <= TimeSpan.Zero)
                        {
                            SendPing();
                            lastSend = now;
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
                        if (doIO(to))
                            lastSend = now;
                    }
                    else if (queueEvent.WaitOne(2))
                        queueEvent.Reset();

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
            lock (synchRoot)
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
                //try
                //{
                //    Thread.Sleep(100);
                //}
                //catch (ThreadInterruptedException)
                //{
                //    if (LOG.IsDebugEnabled)
                //    {
                //        LOG.Debug("SendThread interrupted during sleep, ignoring");
                //    }
                //}
                Packet pack;                
                while (pendingQueue.TryDequeue(out pack))
                    ConLossPacket(pack);

                while (outgoingQueue.TryDequeue(out pack))
                    ConLossPacket(pack);
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
            client.GetStream().BeginRead(incomingBuffer, 0, incomingBuffer.Length, ReceiveAsynch, incomingBuffer);
            //sock.Blocking = true;
            PrimeConnection(client);
            initialized = false;
        }

        private byte[] juteBuffer;

        private object synchRoot = new object();

        private int currentLen;
        private void ReceiveAsynch(IAsyncResult ar)
        {
            lock (synchRoot)
            {
                int len = 0;
                try
                {
                    if (!client.Connected)
                        return;
                    len = client.GetStream().EndRead(ar);
                    if (len == 0)
                        return;
                    byte[] bData = (byte[])ar.AsyncState;

                    recvCount++;
                    if (bData == incomingBuffer)
                    {
                        currentLen = 0;
                        juteBuffer = null;
                        juteBuffer = new byte[ReadLength(bData)];
                        client.GetStream().BeginRead(juteBuffer, 0, juteBuffer.Length, ReceiveAsynch, juteBuffer);
                    }
                    else
                    {
                        if (!initialized)
                        {
                            initialized = true;
                            ReadConnectResult(juteBuffer);
                            client.GetStream().BeginRead(incomingBuffer, 0, incomingBuffer.Length, ReceiveAsynch, incomingBuffer);
                        }
                        else
                        {
                            currentLen += len;
                            if (juteBuffer.Length > currentLen)
                            {
                                client.GetStream().BeginRead(juteBuffer, currentLen, juteBuffer.Length - currentLen, ReceiveAsynch, juteBuffer);
                            }
                            else
                            {
                                ReadResponse(juteBuffer);
                                client.GetStream().BeginRead(incomingBuffer, 0, incomingBuffer.Length, ReceiveAsynch, incomingBuffer);
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    LOG.Error(ex);
                }
            }
        }

        private void ConnectAsynch(IAsyncResult ar)
        {
            client.EndConnect(ar);
            ManualResetEvent evt = (ManualResetEvent)ar.AsyncState;
            evt.Set();
        }

        private void ConnectSocket(IPEndPoint addr)
        {
            using (ManualResetEvent socketConnectTimeout = new ManualResetEvent(false))
            {
                client.BeginConnect(addr.Address, addr.Port, ConnectAsynch, socketConnectTimeout);
                if (socketConnectTimeout.WaitOne(10000))
                    return;
                throw new InvalidOperationException(string.Format("Could not make socket connection to {0}:{1}", addr.Address, addr.Port));
            }
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
                writer.Write(ms.ToArray().Length - 4);
                buffer = ms.ToArray();
            }
            outgoingQueue.Enqueue((new Packet(null, null, null, null, buffer, null, null, null)));

            foreach (ClientConnection.AuthData id in conn.authInfo)
            {
                outgoingQueue.Enqueue(new Packet(new RequestHeader(-4, (int)OpCode.Auth), null, new AuthPacket(0, id.scheme, id.data), null, null, null, null, null));
            }
            if (!ClientConnection.disableAutoWatchReset && (!zooKeeper.DataWatches.IsEmpty() || !zooKeeper.ExistWatches.IsEmpty() || !zooKeeper.ChildWatches.IsEmpty()))
            {
                var sw = new SetWatches(lastZxid, zooKeeper.DataWatches, zooKeeper.ExistWatches, zooKeeper.ChildWatches);
                var h = new RequestHeader();
                h.Type = (int)OpCode.SetWatches;
                h.Xid = -8;
                Packet packet = new Packet(h, new ReplyHeader(), sw, null, null, null, null, null);
                outgoingQueue.Enqueue(packet);
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
            bool packetSend = false;
            if (client == null) throw new IOException("Socket is null!");
            if (client.Client.Poll(Convert.ToInt32(to.TotalMilliseconds / 1000000), SelectMode.SelectWrite))
            {
                Packet first;
                if (outgoingQueue.TryDequeue(out first))
                {
                    if (first.header != null && first.header.Type != (int)OpCode.Ping &&
                        first.header.Type != (int)OpCode.Auth)
                    {
                        pendingQueue.Enqueue(first);
                    }
                    client.GetStream().Write(first.data, 0, first.data.Length);
                    sentCount++;
                    packetSend = true;
                }
            }

            return packetSend;
        }

        private int ReadLength(byte[] content)
        {
            using (EndianBinaryReader reader = new EndianBinaryReader(EndianBitConverter.Big, new MemoryStream(content), Encoding.UTF8))
            {
                int len = reader.ReadInt32();
                if (len < 0 || len >= ClientConnection.packetLen)
                {
                    throw new IOException("Packet len " + len + " is out of range!");
                }
                return len;
            }
        }

        private void ReadConnectResult(byte[] content)
        {
            using (var reader = new EndianBinaryReader(EndianBitConverter.Big, new MemoryStream(content), Encoding.UTF8))
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
                conn.readTimeout = new TimeSpan(0, 0, 0, 0, negotiatedSessionTimeout * 2 / 3);
                conn.connectTimeout = new TimeSpan(0, 0, 0, negotiatedSessionTimeout / conn.serverAddrs.Count);
                conn.SessionId = conRsp.SessionId;
                conn.SessionPassword = conRsp.Passwd;
                zooKeeper.State = ZooKeeper.States.CONNECTED;
                LOG.Info(string.Format("Session establishment complete on server {0:X}, negotiated timeout = {1}", conn.SessionId, negotiatedSessionTimeout));
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.SyncConnected, EventType.None, null));
            }
        }

        private void ReadResponse(byte[] content)
        {
            using (MemoryStream ms = new MemoryStream(content))
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
                        LOG.Debug(string.Format("Got ping response for sessionid: 0x{0:X} after {1}ms", conn.SessionId, (DateTime.Now.Nanos() - lastPingSentNs) / 1000000));
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
                Packet packet;
                /*
             * Since requests are processed in order, we better get a response
             * to the first request!
             */
                if (pendingQueue.TryDequeue(out packet))
                {
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
                else
                {
                    throw new IOException(string.Format("Nothing in the queue, but got {0}", replyHdr.Xid));
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

            p.Finished = true;
            conn.consumer.QueuePacket(p);
        }
        public void Dispose()
        {
            zooKeeper.State = ZooKeeper.States.CLOSED;
            queueEvent.Set();
            requestThread.Join();
            queueEvent.Dispose();
        }
    }

}
