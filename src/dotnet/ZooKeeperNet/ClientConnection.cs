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
 */
﻿namespace ZooKeeperNet
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using System.Net;
    using System.Text;
    using System.Threading;
    using log4net;
    using Org.Apache.Jute;
    using Org.Apache.Zookeeper.Proto;

    public class ClientConnection : IClientConnection
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(ClientConnection));

        public static readonly int packetLen;
        internal static bool disableAutoWatchReset;

        static ClientConnection()
        {
            // this var should not be public, but otw there is no easy way
            // to test
            //disableAutoWatchReset = Boolean.getBoolean("zookeeper.disableAutoWatchReset");
            if (LOG.IsDebugEnabled)
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
            hosts = SetChrootPath();
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

        private string SetChrootPath()
        {
            int off = hosts.IndexOf('/');
            if (off >= 0)
            {
                string path = hosts.Substring(off);
                // ignore "/" chroot spec, same as null
                if (path.Length == 1)
                {
                    ChrootPath = null;
                }
                else
                {
                    PathUtils.ValidatePath(path);
                    ChrootPath = path;
                }
                hosts = hosts.Substring(0, off);
            }
            else
            {
                ChrootPath = null;
            }
            return hosts;
        }

        private void GetHosts(string hosts)
        {
            string[] hostsList = hosts.Split(',');
            foreach (string h in hostsList)
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

        public void AddAuthInfo(string scheme, byte[] auth)
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
                while (!p.Finished)
                {
                    if (!Monitor.Wait(p, SessionTimeout))
                        throw new TimeoutException(string.Format("The request {0} timed out while waiting for a resposne from the server.", request));
                }
            }
            return r;
        }

        public Packet QueuePacket(RequestHeader h, ReplyHeader r, IRecord request, IRecord response, string clientPath, string serverPath, ZooKeeper.WatchRegistration watchRegistration, object callback, object ctx)
        {
            return producer.QueuePacket(h, r, request, response, clientPath, serverPath, watchRegistration);
        }

        /// <summary>
        /// Performs application-defined tasks associated with freeing, releasing, or resetting unmanaged resources.
        /// </summary>
        public void Dispose()
        {
            if (LOG.IsDebugEnabled)
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
                producer.Dispose();
                consumer.Dispose();
            }
        }

        /// <summary>
        /// Returns a <see cref="System.string"/> that represents this instance.
        /// </summary>
        /// <returns>
        /// A <see cref="System.string"/> that represents this instance.
        /// </returns>
        public override string ToString()
        {
            var sb = new StringBuilder();
            sb
                .Append("sessionid:0x").Append(string.Format("{0:X}", SessionId))
                .Append(" lastZxid:").Append(producer.lastZxid)
                .Append(" xid:").Append(producer.xid)
                .Append(" sent:").Append(producer.sentCount)
                .Append(" recv:").Append(producer.recvCount)
                .Append(" queuedpkts:").Append(producer.outgoingQueue.Count)
                .Append(" pendingresp:").Append(producer.pendingQueue.Count)
                .Append(" queuedevents:").Append(consumer.waitingEvents.Count);

            return sb.ToString();
        }

        internal class AuthData
        {
            internal string scheme;
            internal byte[] data;

            internal AuthData(string scheme, byte[] data)
            {
                this.scheme = scheme;
                this.data = data;
            }

        }

        internal class WatcherSetEventPair
        {
            internal HashSet<IWatcher> watchers;
            internal WatchedEvent @event;

            public WatcherSetEventPair(HashSet<IWatcher> watchers, WatchedEvent @event)
            {
                this.watchers = watchers;
                this.@event = @event;
            }
        }
    }
}
