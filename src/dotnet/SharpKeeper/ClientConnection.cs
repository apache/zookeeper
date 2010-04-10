namespace SharpKeeper
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using System.Net;
    using System.Text;
    using System.Threading;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public interface IClientConnection : IStartable, IDisposable
    {
        /// <summary>
        /// Gets or sets the session timeout.
        /// </summary>
        /// <value>The session timeout.</value>
        TimeSpan SessionTimeout { get; }

        /// <summary>
        /// Gets or sets the session password.
        /// </summary>
        /// <value>The session password.</value>
        byte[] SessionPassword { get; }

        /// <summary>
        /// Gets or sets the session id.
        /// </summary>
        /// <value>The session id.</value>
        long SessionId { get; }

        /// <summary>
        /// Gets or sets the chroot path.
        /// </summary>
        /// <value>The chroot path.</value>
        string ChrootPath { get; }

        /// <summary>
        /// Adds the auth info.
        /// </summary>
        /// <param name="scheme">The scheme.</param>
        /// <param name="auth">The auth.</param>
        void AddAuthInfo(String scheme, byte[] auth);

        /// <summary>
        /// Submits the request.
        /// </summary>
        /// <param name="h">The request header.</param>
        /// <param name="request">The request.</param>
        /// <param name="response">The response.</param>
        /// <param name="watchRegistration">The watch registration.</param>
        /// <returns></returns>
        ReplyHeader SubmitRequest(RequestHeader h, IRecord request, IRecord response, ZooKeeper.WatchRegistration watchRegistration);

        /// <summary>
        /// Queues the packet.
        /// </summary>
        /// <param name="h">The request header.</param>
        /// <param name="r">The reply header.</param>
        /// <param name="request">The request.</param>
        /// <param name="response">The response.</param>
        /// <param name="clientPath">The client path.</param>
        /// <param name="serverPath">The server path.</param>
        /// <param name="watchRegistration">The watch registration.</param>
        /// <param name="callback">The callback.</param>
        /// <param name="ctx">The context.</param>
        /// <returns></returns>
        Packet QueuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, String clientPath, String serverPath, ZooKeeper.WatchRegistration watchRegistration, object callback, object ctx);
    }

    public class ClientConnection : IClientConnection
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientConnection));

        internal static bool disableAutoWatchReset;
        public static readonly int packetLen;
        
        static ClientConnection()
        {
            // this var should not be public, but otw there is no easy way
            // to test
            //disableAutoWatchReset = Boolean.getBoolean("zookeeper.disableAutoWatchReset");
            if (LOG.IsDebugEnabled())
            {
                LOG.Debug("zookeeper.disableAutoWatchReset is " + disableAutoWatchReset);
            }
            //packetLen = Integer.getInteger("jute.maxbuffer", 4096 * 1024);
            packetLen = 4096 * 1024;
        }

        internal string hosts;
        internal readonly ZooKeeper zooKeeper;
        internal readonly ZKWatchManager watcher;
        internal readonly List<IPEndPoint> serverAddrs = new List<IPEndPoint>();
        internal readonly List<AuthData> authInfo = new List<AuthData>();
        internal string path;
        internal TimeSpan connectTimeout;
        internal TimeSpan readTimeout;
        internal bool closing;
        internal ClientConnectionRequestProducer producer;
        internal ClientConnectionEventConsumer consumer;

        /// <summary>
        /// Initializes a new instance of the <see cref="ClientConnection"/> class.
        /// </summary>
        /// <param name="connectionString">The connection string.</param>
        /// <param name="sessionTimeout">The session timeout.</param>
        /// <param name="zooKeeper">The zoo keeper.</param>
        /// <param name="watcher">The watch manager.</param>
        public ClientConnection(string connectionString, TimeSpan sessionTimeout, ZooKeeper zooKeeper, ZKWatchManager watcher) :
            this(connectionString, sessionTimeout, zooKeeper, watcher, 0, new byte[16])
        {
        }

        /// <summary>
        /// Initializes a new instance of the <see cref="ClientConnection"/> class.
        /// </summary>
        /// <param name="hosts">The hosts.</param>
        /// <param name="sessionTimeout">The session timeout.</param>
        /// <param name="zooKeeper">The zoo keeper.</param>
        /// <param name="watcher">The watch manager.</param>
        /// <param name="sessionId">The session id.</param>
        /// <param name="sessionPasswd">The session passwd.</param>
        public ClientConnection(string hosts, TimeSpan sessionTimeout, ZooKeeper zooKeeper, ZKWatchManager watcher, long sessionId, byte[] sessionPasswd)
        {
            this.hosts = hosts;
            this.zooKeeper = zooKeeper;
            this.watcher = watcher;
            SessionTimeout = sessionTimeout;
            SessionId = sessionId;
            SessionPassword = sessionPasswd;

            // parse out chroot, if any
            SetChrootPath();
            GetHosts(hosts);
            SetTimeouts(sessionTimeout);
            CreateConsumer();
            CreateProducer();
        }

        private void CreateConsumer()
        {
            consumer = new ClientConnectionEventConsumer(this);
        }

        private void CreateProducer()
        {
            producer = new ClientConnectionRequestProducer(this);
        }

        private void GetHosts(string hosts)
        {
            String[] hostsList = hosts.Split(',');
            foreach (String h in hostsList)
            {
                string host = h;
                int port = 2181;
                int pidx = h.LastIndexOf(':');
                if (pidx >= 0)
                {
                    // otherwise : is at the end of the string, ignore
                    if (pidx < h.Length - 1)
                    {
                        port = Int32.Parse(h.Substring(pidx + 1));
                    }
                    host = h.Substring(0, pidx);
                }
                var ip = IPAddress.Parse(host);
                serverAddrs.Add(new IPEndPoint(ip, port));
            }

            serverAddrs.OrderBy(s => Guid.NewGuid()); //Random order the servers
        }

        private void SetChrootPath()
        {
            int off = hosts.IndexOf('/');
            if (off >= 0)
            {
                String path = hosts.Substring(off);
                // ignore "/" chroot spec, same as null
                if (path.Length == 1)
                {
                    this.path = null;
                }
                else
                {
                    PathUtils.ValidatePath(ChrootPath);
                    this.path = path;
                }
                hosts = hosts.Substring(0, off);
            }
            else
            {
                this.ChrootPath = null;
            }
        }

        private void SetTimeouts(TimeSpan sessionTimeout)
        {
            connectTimeout = new TimeSpan(0, 0, 0, 0, Convert.ToInt32(sessionTimeout.TotalMilliseconds / serverAddrs.Count));
            readTimeout = new TimeSpan(0, 0, 0, 0, Convert.ToInt32(sessionTimeout.TotalMilliseconds * 2 / 3));
        }

        /// <summary>
        /// Gets or sets the session timeout.
        /// </summary>
        /// <value>The session timeout.</value>
        public TimeSpan SessionTimeout { get; private set; }

        /// <summary>
        /// Gets or sets the session password.
        /// </summary>
        /// <value>The session password.</value>
        public byte[] SessionPassword { get; internal set; }

        /// <summary>
        /// Gets or sets the session id.
        /// </summary>
        /// <value>The session id.</value>
        public long SessionId { get; internal set; }

        /// <summary>
        /// Gets or sets the chroot path.
        /// </summary>
        /// <value>The chroot path.</value>
        public string ChrootPath { get; private set; }

        public void Start()
        {
            zooKeeper.State = ZooKeeper.States.CONNECTING;
            consumer.Start();
            producer.Start();
        }

        public void AddAuthInfo(String scheme, byte[] auth)
        {
            if (!zooKeeper.State.IsAlive())
            {
                return;
            }
            authInfo.Add(new AuthData(scheme, auth));
            QueuePacket(new RequestHeader(-4, (int)OpCode.Auth), null, new AuthPacket(0, scheme, auth), null, null, null, null, null, null);
        }

        public ReplyHeader SubmitRequest(RequestHeader h, IRecord request, IRecord response, ZooKeeper.WatchRegistration watchRegistration)
        {
            ReplyHeader r = new ReplyHeader();
            Packet p = QueuePacket(h, r, request, response, null, null, watchRegistration, null, null);
            lock (p)
            {
                while (!p.finished)
                {
                    Monitor.Wait(p);
                }
            }
            return r;
        }

        public Packet QueuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, String clientPath, String serverPath, ZooKeeper.WatchRegistration watchRegistration, object callback, object ctx)
        {
            //lock here for XID?
            if (h.Type != (int)OpCode.Ping && h.Type != (int)OpCode.Auth)
            {
                h.Xid = 1;
            }

            Packet p = new Packet(h, r, request, response, null, watchRegistration, callback, ctx);
            p.clientPath = clientPath;
            p.serverPath = serverPath;
            producer.QueuePacket(p);
            return p;
        }

        /// <summary>
        /// Performs application-defined tasks associated with freeing, releasing, or resetting unmanaged resources.
        /// </summary>
        public void Dispose()
        {
            if (LOG.IsDebugEnabled())
                LOG.Debug(string.Format("Closing client for session: 0x{0:X}", SessionId));

            closing = true;

            try
            {
                SubmitRequest(new RequestHeader {Type = (int) OpCode.CloseSession}, null, null, null);
            }
            catch (ThreadInterruptedException e)
            {
                // ignore, close the send/event threads
            }
            finally
            {
                consumer.Dispose();
                producer.Dispose();
            }
        }

        /// <summary>
        /// Returns a <see cref="System.String"/> that represents this instance.
        /// </summary>
        /// <returns>
        /// A <see cref="System.String"/> that represents this instance.
        /// </returns>
        public override string ToString()
        {
            StringBuilder sb = new StringBuilder();

            //SocketAddress local = getLocalSocketAddress();
            //SocketAddress remote = getRemoteSocketAddress();
            sb
                .Append("sessionid:0x").Append(String.Format("{0:X}", SessionId));
                //.Append(" local:").Append(local)
                //.Append(" remoteserver:").Append(remote)
                //.Append(" lastZxid:").Append(lastZxid)
                //.Append(" xid:").Append(xid)
                //.Append(" sent:").Append(sendThread.sentCount)
                //.Append(" recv:").Append(sendThread.recvCount)
                //.Append(" queuedpkts:").Append(outgoingQueue.Count)
                //.Append(" pendingresp:").Append(sendThread.pendingQueue.Count)
                //.Append(" queuedevents:").Append(eventConsumer.waitingEvents.Count);

            return sb.ToString();
        }

        internal class AuthData
        {
            internal String scheme;
            internal byte[] data;

            internal AuthData(String scheme, byte[] data)
            {
                this.scheme = scheme;
                this.data = data;
            }

        }

        internal class WatcherSetEventPair
        {
            internal HashSet<Watcher> watchers;
            internal WatchedEvent @event;

            public WatcherSetEventPair(HashSet<Watcher> watchers, WatchedEvent @event)
            {
                this.watchers = watchers;
                this.@event = @event;
            }
        }
    }
}
