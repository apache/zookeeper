using System;
using ZooKeeperNet.IO;

namespace ZooKeeperNet
{
    using System.IO;
    using System.Linq;
    using System.Net;
    using System.Net.Sockets;
    using System.Text;
    using System.Threading;
    using log4net;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;
    using System.Collections.Generic;

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
        private volatile bool initialized;
        internal long lastZxid;
        private long lastPingSentNs;
        internal int xid = 1;

        private byte[] incomingBuffer = new byte[4];
        internal int sentCount;
        internal int recvCount;
        internal int negotiatedSessionTimeout;

        public bool IsConnectionClosedByServer
        {
            get;
            private set;
        }

        public ClientConnectionRequestProducer(ClientConnection conn)
        {
            this.conn = conn;
            zooKeeper = conn.zooKeeper;
            requestThread = new Thread(new SafeThreadStart(SendRequests).Run) { Name = new StringBuilder("ZK-SendThread ").Append(conn.zooKeeper.Id).ToString(), IsBackground = true };
        }

        protected int Xid
        {
            get { return xid++; }
        }

        public void Start()
        {
            //zooKeeper.State = ZooKeeper.States.CONNECTING;
            StartConnect();
            requestThread.Start();
        }

        public Packet QueuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, string clientPath, string serverPath, ZooKeeper.WatchRegistration watchRegistration)
        {
            //lock here for XID?
            if (h.Type != (int)OpCode.Ping && h.Type != (int)OpCode.Auth)
                h.Xid = Xid;

            Packet p = new Packet(h, r, request, response, null, watchRegistration, clientPath, serverPath);
            p.clientPath = clientPath;
            p.serverPath = serverPath;

            if (!zooKeeper.State.IsAlive() || Interlocked.CompareExchange(ref isDisposed, 1, 1) == 1)
                ConLossPacket(p);
            else
            {
                outgoingQueue.Enqueue(p);
                queueEvent.Set();
            }
            return p;
        }

        private void SendRequests()
        {
            DateTime now = DateTime.Now;
            DateTime lastSend = now;
            Packet packet = null;
            while (zooKeeper.State.IsAlive())
            {
                try
                {
                    now = DateTime.Now;
                    if (client == null)
                    {
                        // don't re-establish connection if we are closing
                        if (conn.closing)
                            break;

                        StartConnect();
                        //lastSend = now;
                    }
                    TimeSpan idleSend = now - lastSend;
                    if (zooKeeper.State == ZooKeeper.States.CONNECTED)
                    {
                        TimeSpan timeToNextPing = new TimeSpan(0, 0, 0, 0, Convert.ToInt32(conn.readTimeout.TotalMilliseconds / 2 - idleSend.TotalMilliseconds));
                        if (timeToNextPing <= TimeSpan.Zero)
                            SendPing();
                    }

                    // Everything below and until we get back to the select is
                    // non blocking, so time is effectively a constant. That is
                    // Why we just have to do this once, here                    

                    packet = null;
                    if (outgoingQueue.TryDequeue(out packet))
                    {
                        // We have something to send so it's the same
                        // as if we do the send now.                        
                        DoSend(packet);
                        lastSend = DateTime.Now;
                        packet = null;
                    }
                    else if (queueEvent.WaitOne(1111))
                        queueEvent.Reset();

                }
                catch (Exception e)
                {
                    if (conn.closing)
                    {
                        if (LOG.IsDebugEnabled)
                        {
                            // closing so this is expected
                            LOG.DebugFormat("An exception was thrown while closing send thread for session 0x{0:X} : {1}", conn.SessionId, e.Message);
                        }
                        break;
                    }

                    // this is ugly, you have a better way speak up
                    if (e is KeeperException.SessionExpiredException)
                        LOG.InfoFormat("{0}, closing socket connection",e.Message);
                    else if (e is SessionTimeoutException)
                        LOG.InfoFormat("{0}{1}",e.Message,RETRY_CONN_MSG);
                    else if (e is System.IO.EndOfStreamException)
                        LOG.InfoFormat("{0}{1}", e.Message, RETRY_CONN_MSG);
                    else
                        LOG.WarnFormat("Session 0x{0:X} for server {1}, unexpected error{2}, detail:{3}-{4}", conn.SessionId, null, RETRY_CONN_MSG, e.Message, e.StackTrace);
                    if(packet != null)
                        ConLossPacket(packet);
                    Cleanup();
                    if (zooKeeper.State.IsAlive())
                        conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));

                    now = DateTime.Now;
                    lastSend = now;
                }
            }
            Cleanup();
            if (zooKeeper.State.IsAlive())
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.Disconnected, EventType.None, null));

            if (LOG.IsDebugEnabled) 
                LOG.Debug("SendThread exitedloop.");
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
                        LOG.Debug("Ignoring exception during channel close", e);
                }
                finally
                {
                    client = null;
                }
            }
            Packet pack;
            while (pendingQueue.TryDequeue(out pack))
                ConLossPacket(pack);

            while (outgoingQueue.TryDequeue(out pack))
                ConLossPacket(pack);
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
                nextAddrToTry = 0;

            LOG.InfoFormat("Opening socket connection to server {0}", addr);
            client = new TcpClient();
            client.LingerState = new LingerOption(false, 0);
            client.NoDelay = true;
            initialized = IsConnectionClosedByServer = false;
            //ConnectSocket(addr);
            client.Connect(addr);
            //initialized = false;
            client.GetStream().BeginRead(incomingBuffer, 0, incomingBuffer.Length, ReceiveAsynch, incomingBuffer);
            PrimeConnection();
        }

        private byte[] juteBuffer;

        private int currentLen;

        private void ReceiveAsynch(IAsyncResult ar)
        {
            if (Interlocked.CompareExchange(ref isDisposed, 1, 1) == 1)
                return;
            int len = 0;
            try
            {
                len = client.GetStream().EndRead(ar);
                if (len == 0) //server closed the connection
                {
                    zooKeeper.State = ZooKeeper.States.CLOSED;
                    queueEvent.Set();
                    lock (this)
                    {
                        Monitor.Enter(this);
                        IsConnectionClosedByServer = true;
                        Monitor.PulseAll(this);
                        Monitor.Exit(this);
                    }
                    return;
                }
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
                        ReadConnectResult(bData);
                        client.GetStream().BeginRead(incomingBuffer, 0, incomingBuffer.Length, ReceiveAsynch, incomingBuffer);
                    }
                    else
                    {
                        currentLen += len;
                        if (juteBuffer.Length > currentLen)
                            client.GetStream().BeginRead(juteBuffer, currentLen, juteBuffer.Length - currentLen, ReceiveAsynch, juteBuffer);
                        else
                        {
                            ReadResponse(bData);
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

        //private void ConnectAsynch(IAsyncResult ar)
        //{
        //    client.EndConnect(ar);
        //    ManualResetEvent evt = (ManualResetEvent)ar.AsyncState;
        //    evt.Set();
        //}

        //private void ConnectSocket(IPEndPoint addr)
        //{
        //    client.Connect(addr);
            //using (ManualResetEvent socketConnectTimeout = new ManualResetEvent(false))
            //{
            //    client.BeginConnect(addr.Address, addr.Port, ConnectAsynch, socketConnectTimeout);
            //    if (socketConnectTimeout.WaitOne(conn.connectTimeout))
            //        return;
            //    throw new InvalidOperationException(string.Format("Could not make socket connection to {0}:{1}", addr.Address, addr.Port));
            //}
        //}

        private void PrimeConnection()
        {
            LOG.InfoFormat("Socket connection established to {0}, initiating session", client.Client.RemoteEndPoint);
            lastConnectIndex = currentConnectIndex;
            ConnectRequest conReq = new ConnectRequest(0, lastZxid, Convert.ToInt32(conn.SessionTimeout.TotalMilliseconds), conn.SessionId, conn.SessionPassword);

            byte[] buffer;
            MemoryStream ms = new MemoryStream();
            using (EndianBinaryWriter writer = new EndianBinaryWriter(EndianBitConverter.Big, ms, Encoding.UTF8))
            {
                BinaryOutputArchive boa = BinaryOutputArchive.getArchive(writer);
                boa.WriteInt(-1, "len");
                conReq.Serialize(boa, "connect");
                ms.Position = 0;
                int len = Convert.ToInt32(ms.Length);
                writer.Write(len - 4);
                buffer = ms.ToArray();
            }
            outgoingQueue.Enqueue((new Packet(null, null, null, null, buffer, null, null, null)));

            foreach (ClientConnection.AuthData id in conn.authInfo)
                outgoingQueue.Enqueue(new Packet(new RequestHeader(-4, (int)OpCode.Auth), null, new AuthPacket(0, id.Scheme, id.GetData()), null, null, null, null, null));

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
                LOG.DebugFormat("Session establishment request sent on {0}",client.Client.RemoteEndPoint);
        }

        private void SendPing()
        {
            lastPingSentNs = DateTime.Now.Nanos();
            RequestHeader h = new RequestHeader(-2, (int)OpCode.Ping);
            conn.QueuePacket(h, null, null, null, null, null, null, null, null);
        }

        // send packet to server        
        // there's posibility when server closed the socket and client try to send some packet, when this happen it will throw exception
        // the exception is either IOException, NullReferenceException and/or ObjectDisposedException
        // so it is mandatory to catch these excepetions
        private void DoSend(Packet packet)
        {
            //if (client == null)
            //    throw new IOException("Socket is null!");
            if (packet.header != null 
                && packet.header.Type != (int)OpCode.Ping 
                && packet.header.Type != (int)OpCode.Auth)
                pendingQueue.Enqueue(packet);
            client.GetStream().Write(packet.data, 0, packet.data.Length);
            sentCount++;
        }

        private static int ReadLength(byte[] content)
        {
            using (EndianBinaryReader reader = new EndianBinaryReader(EndianBitConverter.Big, new MemoryStream(content), Encoding.UTF8))
            {
                return reader.ReadInt32();
                //int len = reader.ReadInt32();
                //if (len < 0 || len >= ClientConnection.packetLen)
                //    throw new IOException(new StringBuilder("Packet len ").Append(len).Append("is out of range!").ToString());
                //return len;
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
                    throw new SessionExpiredException(new StringBuilder().AppendFormat("Unable to reconnect to ZooKeeper service, session 0x{0:X} has expired", conn.SessionId).ToString());
                }
                conn.readTimeout = new TimeSpan(0, 0, 0, 0, negotiatedSessionTimeout * 2 / 3);
                //conn.connectTimeout = new TimeSpan(0, 0, 0, negotiatedSessionTimeout / conn.serverAddrs.Count);
                conn.SessionId = conRsp.SessionId;
                conn.SessionPassword = conRsp.Passwd;
                zooKeeper.State = ZooKeeper.States.CONNECTED;
                LOG.InfoFormat("Session establishment complete on server {0:X}, negotiated timeout = {1}", conn.SessionId, negotiatedSessionTimeout);
                conn.consumer.QueueEvent(new WatchedEvent(KeeperState.SyncConnected, EventType.None, null));
            }
        }

        private void ReadResponse(byte[] content)
        {
            using (var reader = new EndianBinaryReader(EndianBitConverter.Big, new MemoryStream(content), Encoding.UTF8))
            {
                BinaryInputArchive bbia = BinaryInputArchive.GetArchive(reader);
                ReplyHeader replyHdr = new ReplyHeader();

                replyHdr.Deserialize(bbia, "header");
                if (replyHdr.Xid == -2)
                {
                    // -2 is the xid for pings
                    if (LOG.IsDebugEnabled)
                        LOG.DebugFormat("Got ping response for sessionid: 0x{0:X} after {1}ms", conn.SessionId, (DateTime.Now.Nanos() - lastPingSentNs) / 1000000);
                    return;
                }
                if (replyHdr.Xid == -4)
                {
                    // -2 is the xid for AuthPacket
                    // TODO: process AuthPacket here
                    if (LOG.IsDebugEnabled)
                        LOG.DebugFormat("Got auth sessionid:0x{0:X}", conn.SessionId);
                    return;
                }
                if (replyHdr.Xid == -1)
                {
                    // -1 means notification
                    if (LOG.IsDebugEnabled)
                        LOG.DebugFormat("Got notification sessionid:0x{0}", conn.SessionId);

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
                        LOG.DebugFormat("Got {0} for sessionid 0x{1:X}", we, conn.SessionId);

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
                            throw new IOException(new StringBuilder("Xid out of order. Got ").Append(replyHdr.Xid).Append(" expected ").Append(packet.header.Xid).ToString());
                        }

                        packet.replyHeader.Xid = replyHdr.Xid;
                        packet.replyHeader.Err = replyHdr.Err;
                        packet.replyHeader.Zxid = replyHdr.Zxid;
                        if (replyHdr.Zxid > 0)
                            lastZxid = replyHdr.Zxid;

                        if (packet.response != null && replyHdr.Err == 0)
                            packet.response.Deserialize(bbia, "response");

                        if (LOG.IsDebugEnabled)
                            LOG.DebugFormat("Reading reply sessionid:0x{0:X}, packet:: {1}", conn.SessionId, packet);
                    }
                    finally
                    {
                        FinishPacket(packet);
                    }
                }
                else
                    throw new IOException(new StringBuilder("Nothing in the queue, but got ").Append(replyHdr.Xid).ToString());
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

        private static void FinishPacket(Packet p)
        {
            if (p.watchRegistration != null)
                p.watchRegistration.Register(p.replyHeader.Err);

            p.Finished = true;
            //conn.consumer.QueuePacket(p);
        }

        private int isDisposed = 0;
        private void InternalDispose()
        {
            if (Interlocked.CompareExchange(ref isDisposed, 1, 0) == 0)
            {

                zooKeeper.State = ZooKeeper.States.CLOSED;
                queueEvent.Set();
                requestThread.Join();
                queueEvent.Dispose();
                incomingBuffer = juteBuffer = null;
            }
        }
        public void Dispose()
        {
            InternalDispose();
            GC.SuppressFinalize(this);
        }
        ~ClientConnectionRequestProducer()
        {
            InternalDispose();
        }
    }

}
