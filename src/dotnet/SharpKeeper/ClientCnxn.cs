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

    public partial class ClientCnxn
    {
        private static readonly Logger LOG = Logger.getLogger(typeof(ClientCnxn));

        /// <summary>
        /// This controls whether automatic watch resetting is enabled.
        /// Clients automatically reset watches during session reconnect, this
        /// option allows the client to turn off this behavior by setting
        /// the environment variable "zookeeper.disableAutoWatchReset" to "true"
        /// </summary>
        private static bool disableAutoWatchReset;

        public static int packetLen;
        static ClientCnxn()
        {
            // this var should not be public, but otw there is no easy way
            // to test
            //disableAutoWatchReset = Boolean.getBoolean("zookeeper.disableAutoWatchReset");
            if (LOG.isDebugEnabled())
            {
                LOG.debug("zookeeper.disableAutoWatchReset is " + disableAutoWatchReset);
            }
            //packetLen = Integer.getInteger("jute.maxbuffer", 4096 * 1024);
        }

        private readonly List<IPEndPoint> serverAddrs = new List<IPEndPoint>();

        private class AuthData
        {
            private String scheme;
            private byte[] data;

            internal AuthData(String scheme, byte[] data)
            {
                this.scheme = scheme;
                this.data = data;
            }
        }

        private readonly List<AuthData> authInfo = new List<AuthData>();

        /**
     * These are the packets that need to be sent.
     */
        private readonly LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();

        private int nextAddrToTry = 0;

        private readonly int connectTimeout;

        /** The timeout in ms the client negotiated with the server. This is the 
     *  "real" timeout, not the timeout request by the client (which may
     *  have been increased/decreased by the server which applies bounds
     *  to this value.
     */
        private volatile int negotiatedSessionTimeout;

        private readonly int readTimeout;

        private readonly int sessionTimeout;
        private readonly ZooKeeper zooKeeper;
        readonly String chrootPath;
        readonly Thread senderThread;
        readonly Thread eventerThread;
        readonly SendThread sendThread;
        readonly EventConsumer eventConsumer;
        private IClientWatchManager watcher;
        volatile long lastZxid;
        private int xid = 1;

        private readonly long sessionId;

        private readonly byte[] sessionPasswd = new byte[16];

        private object path;

        /**
     * Set to true when close is called. Latches the connection such that we
     * don't attempt to re-connect to the server if in the middle of closing the
     * connection (client sends session disconnect to server as part of close
     * operation)
     */
        volatile bool closing = false;

        public long getSessionId()
        {
            return sessionId;
        }

        public byte[] getSessionPasswd()
        {
            return sessionPasswd;
        }

        public int getSessionTimeout()
        {
            return negotiatedSessionTimeout;
        }

        public override String ToString()
        {
            StringBuilder sb = new StringBuilder();

            //SocketAddress local = getLocalSocketAddress();
            //SocketAddress remote = getRemoteSocketAddress();
            sb
                .Append("sessionid:0x").Append(String.Format("{0:X}", getSessionId()))
                //.Append(" local:").Append(local)
                //.Append(" remoteserver:").Append(remote)
                .Append(" lastZxid:").Append(lastZxid)
                .Append(" xid:").Append(xid)
                .Append(" sent:").Append(sendThread.sentCount)
                .Append(" recv:").Append(sendThread.recvCount)
                .Append(" queuedpkts:").Append(outgoingQueue.Count)
                .Append(" pendingresp:").Append(sendThread.pendingQueue.Count)
                .Append(" queuedevents:").Append(eventConsumer.waitingEvents.Count);

            return sb.ToString();
        }

        /**
         * Returns the address to which the socket is connected.
         * @return ip address of the remote side of the connection or null if
         *         not connected
         */
        /*SocketAddress getRemoteSocketAddress() {
            // a lot could go wrong here, so rather than put in a bunch of code
            // to check for nulls all down the chain let's do it the simple
            // yet bulletproof way
            try {
                return ((SocketChannel)sendThread.sockKey.channel())
                    .socket().getRemoteSocketAddress();
            } catch (NullPointerException e) {
                return null;
            }
        }*/

        /** 
         * Returns the local address to which the socket is bound.
         * @return ip address of the remote side of the connection or null if
         *         not connected
         */
        /*SocketAddress getLocalSocketAddress() {
            // a lot could go wrong here, so rather than put in a bunch of code
            // to check for nulls all down the chain let's do it the simple
            // yet bulletproof way
            try {
                return ((SocketChannel)sendThread.sockKey.channel())
                    .socket().getLocalSocketAddress();
            } catch (NullPointerException e) {
                return null;
            }
        }*/

        /**
         * This class allows us to pass the headers and the relevant records around.
         */

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

        public ClientCnxn(String hosts, int sessionTimeout, ZooKeeper zooKeeper, IClientWatchManager watcher)
            : this(hosts, sessionTimeout, zooKeeper, watcher, 0, new byte[16])
        {
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

        public ClientCnxn(String hosts, int sessionTimeout, ZooKeeper zooKeeper, IClientWatchManager watcher, long sessionId, byte[] sessionPasswd)
        {
            this.zooKeeper = zooKeeper;
            this.watcher = watcher;
            this.sessionId = sessionId;
            this.sessionPasswd = sessionPasswd;

            // parse out chroot, if any
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
                    PathUtils.ValidatePath(chrootPath);
                    this.path = path;
                }
                hosts = hosts.Substring(0, off);
            }
            else
            {
                this.chrootPath = null;
            }

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
                var ip = new IPAddress(Encoding.UTF8.GetBytes(host));
                serverAddrs.Add(new IPEndPoint(ip, port));
            }
            this.sessionTimeout = sessionTimeout;
            connectTimeout = sessionTimeout / hostsList.Length;
            readTimeout = sessionTimeout * 2 / 3;
            serverAddrs.OrderBy(s => Guid.NewGuid());
            eventConsumer = new EventConsumer(watcher, chrootPath);
            sendThread = new SendThread(zooKeeper, eventConsumer, serverAddrs, connectTimeout, readTimeout, negotiatedSessionTimeout, sessionId, sessionPasswd, chrootPath);
            senderThread = new Thread(() =>
            {
                try
                {
                    sendThread.run();
                }
                catch (ThreadInterruptedException)
                {
                    return;
                }
                catch (Exception e)
                {
                    LOG.Error("Error in event thread", e);
                }
            }) { Name = "ZK-SendThread", IsBackground = true };
            eventerThread = new Thread(() =>
            {
                try
                {
                    eventConsumer.run();
                }
                catch (ThreadInterruptedException)
                {
                    return;
                }
                catch (Exception e)
                {
                    LOG.Error("Error in event thread", e);
                }
            }) { Name = "ZK-EventThread", IsBackground = true };
        }

        /**
         * tests use this to check on reset of watches
         * @return if the auto reset of watches are disabled
         */

        public static bool getDisableAutoResetWatch()
        {
            return disableAutoWatchReset;
        }
        /**
         * tests use this to set the auto reset
         * @param b the vaued to set disable watches to
         */

        public static void setDisableAutoResetWatch(bool b)
        {
            disableAutoWatchReset = b;
        }

        public void start()
        {
            senderThread.Start();
            eventerThread.Start();
        }

        static Object eventOfDeath = new Object();
        private object xidLock = new object();

        private class WatcherSetEventPair
        {
            internal HashSet<Watcher> watchers;
            internal WatchedEvent @event;

            public WatcherSetEventPair(HashSet<Watcher> watchers, WatchedEvent @event)
            {
                this.watchers = watchers;
                this.@event = @event;
            }
        }

        private void conLossPacket(Packet p)
        {
            if (p.replyHeader == null)
            {
                return;
            }
            string state = zooKeeper.state.State;
            if (state == ZooKeeper.States.AUTH_FAILED.State)
                p.replyHeader.Err = (int)KeeperException.Code.AUTHFAILED;
            else if (state == ZooKeeper.States.CLOSED.State)
                p.replyHeader.Err = (int)KeeperException.Code.SESSIONEXPIRED;
            else
                p.replyHeader.Err = (int)KeeperException.Code.CONNECTIONLOSS;

            finishPacket(p);
        }

        /**
         * Shutdown the send/event threads. This method should not be called
         * directly - rather it should be called as part of close operation. This
         * method is primarily here to allow the tests to verify disconnection
         * behavior.
         */
        public void disconnect()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Disconnecting client for session: 0x" + string.Format("{0:X}", sessionId));
            }

            sendThread.close();
            eventConsumer.queueEventOfDeath();
        }

        /**
         * Close the connection, which includes; send session disconnect to the
         * server, shutdown the send/event threads.
         *
         * @throws IOException
         */
        public void close()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug(string.Format("Closing client for session: 0x{0:X}", getSessionId()));
            }

            closing = true;

            try
            {
                RequestHeader h = new RequestHeader();
                h.Type = (int)OpCode.CloseSession;

                submitRequest(h, null, null, null);
            }
            catch (ThreadInterruptedException e)
            {
                // ignore, close the send/event threads
            }
            finally
            {
                disconnect();
            }
        }

        private int Xid
        {
            get
            {
                lock (xidLock)
                {
                    return xid++;
                }
            }
        }

        public ReplyHeader submitRequest(RequestHeader h, IRecord request,
                IRecord response, ZooKeeper.WatchRegistration watchRegistration)
        {
            ReplyHeader r = new ReplyHeader();
            ManualResetEvent reset = new ManualResetEvent(false);
            ThreadPool.QueueUserWorkItem(() =>
            {
                Packet packet = queuePacket(h, r, request, response, null, null, null, null, watchRegistration, reset);
            });
            lock (packet)
            {
                while (!packet.finished)
                {
                    Monitor.Wait(packet);
                }
            }
            return r;
        }

        public Packet queuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, AsyncCallback cb, String clientPath, String serverPath, Object ctx, ZooKeeper.WatchRegistration watchRegistration)
        {
            return queuePacket(h, r, request, response, cb, clientPath, serverPath, ctx, watchRegistration, null);
        }

        private Packet queuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, AsyncCallback cb, string clientPath, string serverPath, object ctx, ZooKeeper.WatchRegistration watchRegistration, ManualResetEvent reset)
        {
            Packet packet = null;
            lock (outgoingQueue)
            {
                if (h.Type != (int)OpCode.Ping && h.Type != (int)OpCode.Auth)
                {
                    h.Xid = Xid;
                }
                packet = new Packet(h, r, request, response, null, watchRegistration, reset);
                packet.cb = cb;
                packet.ctx = ctx;
                packet.clientPath = clientPath;
                packet.serverPath = serverPath;
                if (!zooKeeper.state.isAlive())
                {
                    conLossPacket(packet);
                }
                else
                {
                    outgoingQueue.AddLast(packet);
                }
            }
            //lock (sendThread) {
            //selector.wakeup();
            //}
            return packet;
        }

        public void addAuthInfo(String scheme, byte[] auth)
        {
            if (!zooKeeper.state.isAlive())
            {
                return;
            }
            authInfo.Add(new AuthData(scheme, auth));
            queuePacket(new RequestHeader(-4, (int)OpCode.Auth), null, new AuthPacket(0, scheme, auth), null, null, null, null, null, null);
        }

    }
}