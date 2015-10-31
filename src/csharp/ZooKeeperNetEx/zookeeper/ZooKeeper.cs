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

using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;

using org.apache.utils;
using org.apache.zookeeper.client;
using org.apache.zookeeper.common;
using org.apache.zookeeper.data;
using org.apache.zookeeper.proto;

namespace org.apache.zookeeper {
    /// <summary>
    /// This is the main class of ZooKeeper client library. To use a ZooKeeper
    /// service, an application must first instantiate an object of ZooKeeper class.
    /// All the iterations will be done by calling the methods of ZooKeeper class.
    /// The methods of this class are thread-safe unless otherwise noted.
    /// </summary>
    /// <remarks>
    /// Once a connection to a server is established, a session ID is assigned to the
    /// client. The client will send heart beats to the server periodically to keep
    /// the session valid.
    /// 
    /// The application can call ZooKeeper APIs through a client as long as the
    /// session ID of the client remains valid.
    /// 
    /// If for some reason, the client fails to send heart beats to the server for a
    /// prolonged period of time (exceeding the sessionTimeout value, for instance),
    /// the server will expire the session, and the session ID will become invalid.
    /// The client object will no longer be usable. To make ZooKeeper API calls, the
    /// application must create a new client object.
    /// 
    /// If the ZooKeeper server the client currently connects to fails or otherwise
    /// does not respond, the client will automatically try to connect to another
    /// server before its session ID expires. If successful, the application can
    /// continue to use the client.
    /// 
    /// The ZooKeeper API methods are either synchronous or asynchronous. Synchronous
    /// methods blocks until the server has responded. Asynchronous methods just queue
    /// the request for sending and return immediately. They take a callback object that
    /// will be executed either on successful execution of the request or on error with
    /// an appropriate return code (rc) indicating the error.
    /// 
    /// Some successful ZooKeeper API calls can leave watches on the "data nodes" in
    /// the ZooKeeper server. Other successful ZooKeeper API calls can trigger those
    /// watches. Once a watch is triggered, an event will be delivered to the client
    /// which left the watch at the first place. Each watch can be triggered only
    /// once. Thus, up to one event will be delivered to a client for every watch it
    /// leaves.
    /// 
    /// A client needs an object of a class implementing Watcher interface for
    /// processing the events delivered to the client.
    ///
    /// When a client drops current connection and re-connects to a server, all the
    /// existing watches are considered as being triggered but the undelivered events
    /// are lost. To emulate this, the client will generate a special event to tell
    /// the event handler a connection has been dropped. This special event has type
    /// EventNone and state sKeeperStateDisconnected
    /// </remarks>
    public class ZooKeeper {
        static ZooKeeper() {
            const string ZKConfigFile = "ZooKeeperConfiguration.xml";
            ZooKeeperConfiguration config = null;
            FileInfo fileInfo = new FileInfo(ZKConfigFile);
            if (fileInfo.Exists) {
                try {
                    config = ZooKeeperConfiguration.LoadFromFile(ZKConfigFile);
                }
                    // ReSharper disable once EmptyGeneralCatchClause
                catch {}
            }
            TraceLogger.Initialize(config);
        }
        private static readonly byte[] NO_PASSWORD = new byte[0];
        private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof (ZooKeeper));

        private readonly ZKWatchManager watchManager = new ZKWatchManager();

        internal List<string> getDataWatches() {
            lock (watchManager.dataWatches) {
                List<string> rc = new List<string>(watchManager.dataWatches.Keys);
                return rc;
            }
        }

        internal List<string> getExistWatches() {
            lock (watchManager.existWatches) {
                List<string> rc = new List<string>(watchManager.existWatches.Keys);
                return rc;
            }
        }

        internal List<string> getChildWatches() {
            lock (watchManager.childWatches) {
                List<string> rc = new List<string>(watchManager.childWatches.Keys);
                return rc;
            }
        }

        /// <summary>
        /// Manage watchers and handle events generated by the ClientCnxn object.
        /// We are implementing this as a nested class of ZooKeeper so that
        /// the public methods will not be exposed as part of the ZooKeeper client API.
        /// </summary>
        private class ZKWatchManager : ClientWatchManager {
            internal readonly Dictionary<string, HashSet<Watcher>> dataWatches =
                new Dictionary<string, HashSet<Watcher>>();

            internal readonly Dictionary<string, HashSet<Watcher>> existWatches =
                new Dictionary<string, HashSet<Watcher>>();

            internal readonly Dictionary<string, HashSet<Watcher>> childWatches =
                new Dictionary<string, HashSet<Watcher>>();

            private readonly Fenced<Watcher> _defaultWatcher = new Fenced<Watcher>(null);

            internal Watcher defaultWatcher {
                get { return _defaultWatcher.Value; }
                set { _defaultWatcher.Value = value; }
            }

            private static void addTo(HashSet<Watcher> from, HashSet<Watcher> to) {
                if (from != null) {
                    to.addAll(from);
                }
            }

            public HashSet<Watcher> materialize(Watcher.Event.KeeperState state, Watcher.Event.EventType type,
                string clientPath) {
                HashSet<Watcher> result = new HashSet<Watcher>();
                switch (type) {
                    case Watcher.Event.EventType.None:
                        result.Add(defaultWatcher);
                        bool clear = ClientCnxn.getDisableAutoResetWatch() && state != Watcher.Event.KeeperState.SyncConnected;
                        lock (dataWatches) {
                            foreach (HashSet<Watcher> ws in dataWatches.Values) {
                                result.addAll(ws);
                            }
                            if (clear)
                            {
                                dataWatches.Clear();
                            }
                        }
                        lock (existWatches) {
                            foreach (HashSet<Watcher> ws in existWatches.Values) {
                                result.addAll(ws);
                            }
                            if (clear)
                            {
                                existWatches.Clear();
                            }
                        }
                        lock (childWatches) {
                            foreach (HashSet<Watcher> ws in childWatches.Values) {
                                result.addAll(ws);
                            }
                            if (clear)
                            {
                                childWatches.Clear();
                            }
                        }
                        return result;
                    case Watcher.Event.EventType.NodeDataChanged:
                    case Watcher.Event.EventType.NodeCreated:
                        lock (dataWatches) {
                            addTo(dataWatches.remove(clientPath), result);
                        }
                        lock (existWatches) {
                            addTo(existWatches.remove(clientPath), result);
                        }
                        break;
                    case Watcher.Event.EventType.NodeChildrenChanged:
                        lock (childWatches) {
                            addTo(childWatches.remove(clientPath), result);
                        }
                        break;
                    case Watcher.Event.EventType.NodeDeleted:
                        lock (dataWatches) {
                            addTo(dataWatches.remove(clientPath), result);
                        }
                        // XXX This shouldn't be needed, but just in case
                        lock (existWatches) {
                            HashSet<Watcher> list = existWatches.remove(clientPath);
                            if (list != null) {
                                addTo(existWatches.remove(clientPath), result);
                                LOG.warn("We are triggering an exists watch for delete! Shouldn't happen!");
                            }
                        }
                        lock (childWatches) {
                            addTo(childWatches.remove(clientPath), result);
                        }
                        break;
                    default:
                        string msg = "Unhandled watch event type " + type + " with state " + state + " on path " +
                                     clientPath;
                        LOG.error(msg);
                        throw new InvalidOperationException(msg);
                }
                return result;
            }
        }

        /// <summary>
        /// Register a watcher for a particular path.
        /// </summary>
        internal abstract class WatchRegistration {
            private readonly Watcher watcher;
            private readonly string clientPath;

            protected WatchRegistration(Watcher watcher, string clientPath) {
                this.watcher = watcher;
                this.clientPath = clientPath;
            }

            protected abstract Dictionary<string, HashSet<Watcher>> getWatches(int rc);

            /// <summary>
            /// Register the watcher with the set of watches on path.
            /// </summary>
            /// <param name="rc">the result code of the operation that attempted to add the watch on the path.</param>
            public void register(int rc) {
                if (shouldAddWatch(rc)) {
                    var watches = getWatches(rc);
                    lock (watches){
                        HashSet<Watcher> watchers;
                        watches.TryGetValue(clientPath, out watchers);
                        if (watchers == null) {
                            watchers = new HashSet<Watcher>();
                            watches[clientPath] = watchers;
                        }
                        watchers.Add(watcher);
                    }
                }
            }

            /// <summary>
            /// Determine whether the watch should be added based on return code.
            /// </summary>
            /// <param name="rc">the result code of the operation that attempted to add the watch on the node</param>
            /// <returns>true if the watch should be added, otw false</returns>
            protected virtual bool shouldAddWatch(int rc) {
                return rc == 0;
            }
        }

        /// <summary>
        /// Handle the special case of exists watches - they add a watcher
        /// even in the case where NONODE result code is returned.
        /// </summary>
        private class ExistsWatchRegistration : WatchRegistration {
            private readonly ZKWatchManager watchManager;

            public ExistsWatchRegistration(ZKWatchManager watchManager, Watcher watcher, string clientPath)
                : base(watcher, clientPath) {
                this.watchManager = watchManager;
            }

            protected override Dictionary<string, HashSet<Watcher>> getWatches(int rc) {
                return rc == 0 ? watchManager.dataWatches : watchManager.existWatches;
            }

            protected override bool shouldAddWatch(int rc) {
                return rc == 0 || rc == (int) KeeperException.Code.NONODE;
            }
        }

        private class DataWatchRegistration : WatchRegistration {
            private readonly ZKWatchManager watchManager;

            public DataWatchRegistration(ZKWatchManager watchManager, Watcher watcher, string clientPath)
                : base(watcher, clientPath) {
                this.watchManager = watchManager;
            }

            protected override Dictionary<string, HashSet<Watcher>> getWatches(int rc) {
                return watchManager.dataWatches;
            }
        }

        private class ChildWatchRegistration : WatchRegistration {
            private readonly ZKWatchManager watchManager;

            public ChildWatchRegistration(ZKWatchManager watchManager, Watcher watcher, string clientPath)
                : base(watcher, clientPath) {
                this.watchManager = watchManager;
            }

            protected override Dictionary<string, HashSet<Watcher>> getWatches(int rc) {
                return watchManager.childWatches;
            }
        }

        /// <summary>
        /// 
        /// </summary>
        public enum States {
            /// <summary>
            /// Connecting to ZooKeeper Server
            /// </summary>
            CONNECTING, /*ASSOCIATING,*/
            /// <summary>
            /// Connected to ZooKeeper Service
            /// </summary>
            CONNECTED,
            /// <summary>
            /// Connecting to ZooKeeper Service
            /// </summary>
            CONNECTEDREADONLY,
            /// <summary>
            /// Closed Connection to ZooKeeper Service
            /// </summary>
            CLOSED,
            /// <summary>
            /// Authentication Failed with ZooKeeper Service
            /// </summary>
            AUTH_FAILED,
            /// <summary>
            /// No Connected to ZooKeeper Service
            /// </summary>
            NOT_CONNECTED
        }

        internal readonly ClientCnxn cnxn;

        /// <summary>
        /// To create a ZooKeeper client object, the application needs to pass a connection string containing a comma separated list 
        /// of host:port pairs, each corresponding to a ZooKeeper server.
        /// Session establishment is asynchronous. This constructor will initiate connection to the server and return immediately - 
        /// potentially (usually) before the session is fully established. The watcher argument specifies the watcher that will be 
        /// notified of any changes in state. This notification can come at any point before or after the constructor call has returned.
        /// The instantiated ZooKeeper client object will pick an arbitrary server from the connectstring and attempt to connect to it.
        /// If establishment of the connection fails, another server in the connect string will be tried (the order is non-deterministic, as we random shuffle the list)
        /// , until a connection is established. The client will continue attempts until the session is explicitly closed 
        /// (or the session is expired by the server).
        /// Added in 3.2.0: An optional "chroot" suffix may also be appended to the connection string. This will run the client 
        /// commands while interpreting all paths relative to this root (similar to the unix chroot command).
        /// </summary>
        /// <param name="connectstring">comma separated host:port pairs, each corresponding to a zk server. 
        /// e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002" If the optional chroot suffix is used the example would look like:
        /// "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a" where the client would be rooted at "/app/a" and all paths would 
        /// be relative to this root - ie getting/setting/etc... "/foo/bar" would result in operations being run on "/app/a/foo/bar" 
        /// (from the server perspective).</param>
        /// <param name="sessionTimeout"> session timeout in milliseconds</param>
        /// <param name="watcher">a watcher object which will be notified of state changes, may also be notified for node events</param>
        /// <param name="canBeReadOnly">(added in 3.4) whether the created client is allowed to go to read-only mode in case of 
        /// partitioning. Read-only mode basically means that if the client can't find any majority servers but there's partitioned 
        /// server it could reach, it connects to one in read-only mode, i.e. read requests are allowed while write requests are not.
        /// It continues seeking for majority in the background.</param>
        /// <exception cref="ArgumentException">when <paramref name="connectstring"/>parsed or resolved to an empty list. also when given chroot is invalid"/></exception>
        public ZooKeeper(string connectstring, int sessionTimeout, Watcher watcher, bool canBeReadOnly = false) :
            this(connectstring, sessionTimeout, watcher, 0, NO_PASSWORD, canBeReadOnly){
        }

        /// <summary>
        /// To create a ZooKeeper client object, the application needs to pass a connection string containing a comma separated list 
        /// of host:port pairs, each corresponding to a ZooKeeper server.
        /// Session establishment is asynchronous. This constructor will initiate connection to the server and return immediately - 
        /// potentially (usually) before the session is fully established. The watcher argument specifies the watcher that will be 
        /// notified of any changes in state. This notification can come at any point before or after the constructor call has returned.
        /// The instantiated ZooKeeper client object will pick an arbitrary server from the connectstring and attempt to connect to it.
        /// If establishment of the connection fails, another server in the connect string will be tried (the order is non-deterministic, as we random shuffle the list)
        /// , until a connection is established. The client will continue attempts until the session is explicitly closed 
        /// (or the session is expired by the server).
        /// Added in 3.2.0: An optional "chroot" suffix may also be appended to the connection string. This will run the client 
        /// commands while interpreting all paths relative to this root (similar to the unix chroot command).
        /// Use <see cref="getSessionId"/> and <see cref="getSessionPasswd"/> on an established client connection, these values must be 
        /// passed as sessionId and sessionPasswd respectively if reconnecting. Otherwise, if not reconnecting, use the other 
        /// constructor which does not require these parameters.
        /// </summary>
        /// <param name="connectString">comma separated host:port pairs, each corresponding to a zk server. 
        /// e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002" If the optional chroot suffix is used the example would look like:
        /// "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a" where the client would be rooted at "/app/a" and all paths would 
        /// be relative to this root - ie getting/setting/etc... "/foo/bar" would result in operations being run on "/app/a/foo/bar" 
        /// (from the server perspective).</param>
        /// <param name="sessionTimeout"> session timeout in milliseconds</param>
        /// <param name="watcher">a watcher object which will be notified of state changes, may also be notified for node events</param>
        /// <param name="sessionId">specific session id to use if reconnecting</param>
        /// <param name="sessionPasswd">password for this session</param>
        /// <param name="canBeReadOnly">(added in 3.4) whether the created client is allowed to go to read-only mode in case of 
        /// partitioning. Read-only mode basically means that if the client can't find any majority servers but there's partitioned 
        /// server it could reach, it connects to one in read-only mode, i.e. read requests are allowed while write requests are not.
        /// It continues seeking for majority in the background.</param>
        /// <exception cref="ArgumentException">when <paramref name="connectString"/> parsed or resolved to an empty list. also when given chroot is invalid"/></exception>
        public ZooKeeper(string connectString, int sessionTimeout, Watcher watcher,
            long sessionId, byte[] sessionPasswd, bool canBeReadOnly = false) {
            LOG.info("Initiating client connection, connectString=" + connectString
                     + " sessionTimeout=" + sessionTimeout
                     + " watcher=" + watcher
                     + " sessionId=" + sessionId.ToHexString()
                     + " sessionPasswd="
                     + (sessionPasswd == null ? "<null>" : "<hidden>"));

            sessionPasswd = sessionPasswd ?? NO_PASSWORD;
            watchManager.defaultWatcher = new WatcherDelegate(@event =>
            {
                if (@event.getState() == Watcher.Event.KeeperState.SyncConnected || @event.getState() == Watcher.Event.KeeperState.ConnectedReadOnly) 
                {
                    if (!connectedTask.WaitAsync().IsCompleted) 
                        connectedTask.Set();
                }
                else connectedTask.Reset();
                return watcher.process(@event);
            });

            ConnectStringParser connectStringParser = new ConnectStringParser(connectString);
            HostProvider hostProvider = new StaticHostProvider(
                connectStringParser.getServerAddresses());
            cnxn = new ClientCnxn(connectStringParser.getChrootPath(),
                hostProvider, sessionTimeout, this, watchManager, 
                sessionId, sessionPasswd, canBeReadOnly);
            cnxn.seenRwServerBefore.Value = sessionId > 0; // since user has provided sessionId
            userDefinedSessionTimeout = sessionTimeout;
            cnxn.start();
        }

        private readonly int userDefinedSessionTimeout;

        private readonly AsyncManualResetEvent connectedTask = new AsyncManualResetEvent();
        
        /// <summary>
        /// The session id for this ZooKeeper client instance. The value returned is not valid until the client connects to a 
        /// server and may change after a re-connect. This method is NOT thread safe.
        /// </summary>
        public long getSessionId() {
            return cnxn.getSessionId();
        }

        /// <summary>
        /// The session password for this ZooKeeper client instance. The value returned is not valid until the client connects to 
        /// a server and may change after a re-connect.This method is NOT thread safe
        /// </summary>
        public byte[] getSessionPasswd() {
            return cnxn.getSessionPasswd();
        }

        /// <summary>
        /// The negotiated session timeout for this ZooKeeper client instance.The value returned is not valid until the client 
        /// connects to a server and may change after a re-connect.This method is NOT thread safe.
        /// </summary>
        public int getSessionTimeout() {
            return cnxn.getSessionTimeout();
        }

        /// <summary>
        /// Add the specified scheme:auth information to this connection.
        ///
        /// This method is NOT thread safe
        /// </summary>
        /// <param name="scheme">The scheme.</param>
        /// <param name="auth">The auth.</param>
        public void addAuthInfo(string scheme, byte[] auth) {
            cnxn.addAuthInfo(scheme, auth);
        }
        
        /// <summary>
        /// Close this client object. Once the client is closed, its session becomes
        /// invalid. All the ephemeral nodes in the ZooKeeper server associated with
        /// the session will be removed. The watches left on those nodes (and on
        /// their parents) will be triggered.
        /// </summary>   
        public async Task closeAsync() 
        {
            if (!cnxn.getState().isAlive()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Close called on already closed client");
                }
                return;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Closing session: 0x" + getSessionId().ToHexString());
            }

            try {
                await cnxn.closeAsync().ConfigureAwait(false);
            }
            catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Ignoring unexpected exception during close", e);
                }
            }

            LOG.info("Session: 0x" + getSessionId().ToHexString() + " closed");
        }

        /// <summary>
        /// Prepend the chroot to the client path (if present). The expectation of
        /// this function is that the client path has been validated before this
        /// function is called
        /// </summary>
        /// <param name="clientPath">The path to the node.</param>
        /// <returns>server view of the path (chroot prepended to client path)</returns>
        private string prependChroot(string clientPath) {
            if (cnxn.chrootPath != null) {
                // handle clientPath = "/"
                if (clientPath.Length == 1) {
                    return cnxn.chrootPath;
                }
                return cnxn.chrootPath + clientPath;
            }
            return clientPath;
        }

        /// <summary>
        /// Create a node with the given path. The node data will be the given data,
        /// and node acl will be the given acl.
        /// 
        /// This operation, if successful, will trigger all the watches left on the
        /// node of the given path by exists and getData API calls, and the watches
        /// left on the parent node by getChildren API calls.
        /// 
        /// If a node is created successfully, the ZooKeeper server will trigger the
        /// watches on the path left by exists calls, and the watches on the parent
        /// of the node by getChildrenAsync calls
        /// 
        /// The maximum allowable size of the data array is 1 MB (1,048,576 bytes).
        /// Arrays larger than this will cause a KeeperExecption to be thrown.
        /// </summary>
        /// <param name="path">The path for the node.</param>
        /// <param name="data">The data for the node.</param>
        /// <param name="acl">The acl for the node.</param>
        /// <param name="createMode">specifying whether the node to be created is ephemeral and/or sequential. 
        /// An ephemeral node will be removed by the ZK server automatically when the session associated with the creation of the node expires.
        /// A sequential node will be the given path plus a suffix "i" where i is the current sequential number of the node. 
        /// The sequence number is always fixed length of 10 digits, 0 padded. Once such a node is created, the sequential number will be incremented by one.
        /// </param>
        /// <returns>the actual path of the created node</returns>
        /// <exception cref="KeeperException.NoChildrenForEphemeralsException">An ephemeral node cannot have children.</exception>
        /// <exception cref="KeeperException.NoNodeException">the parent node does not exist</exception>
        /// <exception cref="KeeperException.NodeExistsException">a node with the same actual path already exists.(Never happens when CreateMode is sequential)</exception>
        /// <exception cref="KeeperException.InvalidACLException">the ACL is invalid, null, or empty</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception>
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task<string> createAsync(string path, byte[] data, List<ACL> acl, CreateMode createMode) {
            string clientPath = path;
            PathUtils.validatePath(clientPath, createMode.isSequential());

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int) ZooDefs.OpCode.create);
            CreateRequest request = new CreateRequest();
            CreateResponse response = new CreateResponse();
            request.setData(data);
            request.setFlags(createMode.toFlag());
            request.setPath(serverPath);
            if (acl != null && acl.size() == 0) {
                throw new KeeperException.InvalidACLException();
            }
            request.setAcl(acl);
            ReplyHeader r = await cnxn.submitRequest(h, request, response, null).ConfigureAwait(false);
            if (r.getErr() != 0) {
                throw KeeperException.create(r.getErr(),
                    clientPath);
            }
            if (cnxn.chrootPath == null) {
                return response.getPath();
            }
            return response.getPath().Substring(cnxn.chrootPath.Length);
        }

        /// <summary>
        /// Delete the node with the given path.
        /// This operation, if successful, will trigger all the watches on the node of the given path left by existsAsync calls, 
        /// and the watches on the parent node left by getChildrenAsync calls.
        /// </summary>
        /// <param name="path">The path to delete</param>
        /// <param name="version">The version matches the node's version(if the given  version is -1, it matches any node's versions).</param>
        /// <exception cref="KeeperException.NoNodeException">the parent node does not exist</exception>
        /// <exception cref="KeeperException.NotEmptyException">the node has children</exception>
        /// <exception cref="KeeperException.BadVersionException">the given version does not match the node's version</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task deleteAsync(string path, int version = -1)
        {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            string serverPath;

            // maintain semantics even in chroot case
            // specifically - root cannot be deleted
            // I think this makes sense even in chroot case.
            if (clientPath.Equals("/"))
            {
                // a bit of a hack, but delete(/) will never succeed and ensures
                // that the same semantics are maintained
                serverPath = clientPath;
            }
            else
            {
                serverPath = prependChroot(clientPath);
            }

            RequestHeader h = new RequestHeader();
            h.set_Type((int)ZooDefs.OpCode.delete);
            DeleteRequest request = new DeleteRequest();
            request.setPath(serverPath);
            request.setVersion(version);
            ReplyHeader r = await cnxn.submitRequest(h, request, null, null).ConfigureAwait(false);
            if (r.getErr() != 0)
            {
                throw KeeperException.create(r.getErr(), clientPath);
            }
        }

        /// <summary>
        /// Executes multiple ZooKeeper operations or none of them.
        /// </summary>
        /// <param name="ops">A list that contains the operations to be done.
        /// These should be created using the factory methods on <see cref="Op"/></param>
        /// <remarks>
        /// Note: The maximum allowable size of all of the data arrays in all of the setData operations in this single 
        /// request is typically 1 MB (1,048,576 bytes). This limit is specified on the server via
        /// <a href="http://zookeeper.apache.org/doc/current/zookeeperAdmin.html#Unsafe+Options">jute.maxbuffer</a>.
        /// Requests larger than this will cause a KeeperException to be thrown.
        /// @since 3.4.0
        /// </remarks>
        /// <returns>A list of results, one for each input Op, the order of
        /// which exactly matches the order of the <paramref name="ops"/> input
        /// operations.</returns>
        /// <exception cref="KeeperException">If the operation could not be completed due to some error in doing one of 
        /// the specified ops.contains partial results and error details, see <see cref="KeeperException.getResults()"/></exception> 
        public Task<List<OpResult>> multiAsync(List<Op> ops) {
            if (ops == null) throw new ArgumentNullException("ops");
            foreach (Op op in ops) {
                op.validate();
            }
            // reconstructing transaction with the chroot prefix
            IList<Op> transaction = new List<Op>();
            foreach (Op op in ops) {
                transaction.Add(withRootPrefix(op));
            }
            return multiInternal(new MultiTransactionRecord(transaction));
        }

        private Op withRootPrefix(Op op) {
            if (null != op.getPath()) {
                string serverPath = prependChroot(op.getPath());
                if (!op.getPath().Equals(serverPath)) {
                    return op.withChroot(serverPath);
                }
            }
            return op;
        }

        private async Task<List<OpResult>> multiInternal(MultiTransactionRecord request) {
            RequestHeader h = new RequestHeader();
            h.set_Type((int) ZooDefs.OpCode.multi);
            MultiResponse response = new MultiResponse();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, null).ConfigureAwait(false);
            if (r.getErr() != 0) {
                throw KeeperException.create(r.getErr());
            }
            List<OpResult> results = response.getResultList();

            OpResult.ErrorResult fatalError = null;
            foreach (OpResult result in results) {
                if (result is OpResult.ErrorResult &&
                    ((OpResult.ErrorResult) result).getErr() != (int) KeeperException.Code.OK) {
                    fatalError = (OpResult.ErrorResult) result;
                    break;
                }
            }
            if (fatalError != null) {
                KeeperException ex = KeeperException.create(fatalError.getErr());
                ex.setMultiResults(results);
                throw ex;
            }
            return results;
        }
        
        /// <summary>
        /// A Transaction is a thin wrapper on the <see cref="multiAsync"/> method which provides a builder object 
        /// that can be used to construct and commit an atomic set of operations. @since 3.4.0
        /// </summary>
        /// <returns>Transaction builder object</returns>
        public Transaction transaction() {
            return new Transaction(this);
        }

        /// <summary>
        /// Return the stat of the node of the given path. Return null if no such a
        /// node exists.
        /// If the watch is non-null and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch will be
        /// triggered by a successful operation that creates/delete the node or sets
        /// the data on the node.
        /// </summary>
        /// <param name="path">the node path</param>
        /// <param name="watcher">explicit watcher</param>
        /// <returns>the stat of the node of the given path; return null if no such a node exists.</returns>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task<Stat> existsAsync(string path, Watcher watcher)
        {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            // the watch contains the un-chroot path
            WatchRegistration wcb = null;
            if (watcher != null)
            {
                wcb = new ExistsWatchRegistration(watchManager, watcher, clientPath);
            }

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int)ZooDefs.OpCode.exists);
            ExistsRequest request = new ExistsRequest();
            request.setPath(serverPath);
            request.setWatch(watcher != null);
            SetDataResponse response = new SetDataResponse();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, wcb).ConfigureAwait(false);

            if (r.getErr() != 0)
            {
                if (r.getErr() == (int)KeeperException.Code.NONODE)
                {
                    return null;
                }
                throw KeeperException.create(r.getErr(), clientPath);
            }

            return response.getStat().getCzxid() == -1 ? null : response.getStat();
        }

        /// <summary>
        /// Return the stat of the node of the given path. Return null if no such a
        /// node exists.
        /// If the watch is true and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch will be
        /// triggered by a successful operation that creates/delete the node or sets
        /// the data on the node.
        /// </summary>
        /// <param name="path">the node path</param>
        /// <param name="watch">whether need to watch this node</param>
        /// <returns>the stat of the node of the given path; return null if no such a node exists.</returns>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public Task<Stat> existsAsync(string path, bool watch = false) {
            return existsAsync(path, watch ? watchManager.defaultWatcher : null);
        }

        /// <summary>
        /// Returns the data and the stat of the node of the given path.
        /// If the watch is non-null and the call is successful (no exception is
        /// thrown), a watch will be left on the node with the given path. The watch
        /// will be triggered by a successful operation that sets data on the node, or
        /// deletes the node.
        /// </summary>
        /// <returns>the data and the stat of the node of the given path</returns>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task<DataResult> getDataAsync(string path, Watcher watcher)
        {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            // the watch contains the un-chroot path
            WatchRegistration wcb = null;
            if (watcher != null)
            {
                wcb = new DataWatchRegistration(watchManager, watcher, clientPath);
            }

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int)ZooDefs.OpCode.getData);
            GetDataRequest request = new GetDataRequest();
            request.setPath(serverPath);
            request.setWatch(watcher != null);
            GetDataResponse response = new GetDataResponse();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, wcb).ConfigureAwait(false);
            if (r.getErr() != 0)
            {
                throw KeeperException.create(r.getErr(), clientPath);
            }

            return new DataResult(response.getData(), response.getStat());
        }

        /// <summary>
        /// Return the data and the stat of the node of the given path.
        /// If the watch is true and the call is successful (no exception is
        /// thrown), a watch will be left on the node with the given path. The watch
        /// will be triggered by a successful operation that sets data on the node, or
        /// deletes the node.
        /// </summary>
        /// <param name="path">the given path</param>
        /// <param name="watch">whether need to watch this node</param>
        /// <returns>
        /// the data and the stat of the node of the given path
        /// </returns>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception>
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public Task<DataResult> getDataAsync(string path, bool watch = false) {
            return getDataAsync(path, watch ? watchManager.defaultWatcher : null);
        }

        /// <summary>
        /// Set the data for the node of the given path if such a node exists and the
        /// given version matches the version of the node (if the given version is
        /// -1, it matches any node's versions). Return the stat of the node.
        /// This operation, if successful, will trigger all the watches on the node
        /// of the given path left by getData calls.
        /// The maximum allowable size of the data array is 1 MB (1,048,576 bytes).
        /// Arrays larger than this will cause a KeeperExecption to be thrown.
        /// </summary>
        /// <param name="path">the path of the node</param>
        /// <param name="data">the data to set</param>
        /// <param name="version">the expected matching version</param>
        /// <returns>the state of the node</returns>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.BadVersionException">the given version does not match the node's version</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task<Stat> setDataAsync(string path, byte[] data, int version = -1)
        {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int)ZooDefs.OpCode.setData);
            SetDataRequest request = new SetDataRequest();
            request.setPath(serverPath);
            request.setData(data);
            request.setVersion(version);
            SetDataResponse response = new SetDataResponse();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, null).ConfigureAwait(false);
            if (r.getErr() != 0)
            {
                throw KeeperException.create(r.getErr(), clientPath);
            }
            return response.getStat();
        }

        /// <summary>
        /// Return the ACL and stat of the node of the given path.
        /// </summary>
        /// <param name="path">the given path for the node</param>
        /// <returns>the ACL array of the given node</returns>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task<ACLResult> getACLAsync(string path)
        {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int)ZooDefs.OpCode.getACL);
            GetACLRequest request = new GetACLRequest();
            request.setPath(serverPath);
            GetACLResponse response = new GetACLResponse();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, null).ConfigureAwait(false);
            if (r.getErr() != 0)
            {
                throw KeeperException.create(r.getErr(), clientPath);
            }
            return new ACLResult(response.getAcl(), response.getStat());
        }

        /// <summary>
        /// Set the ACL for the node of the given path if such a node exists and the
        /// given version matches the version of the node. Return the stat of the
        /// node.
        /// </summary>
        /// <param name="path">node path</param>
        /// <param name="acl">acl</param>
        /// <param name="version">version</param>
        /// <returns></returns>
        /// <exception cref="KeeperException.BadVersionException">the given version does not match the node's version</exception>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception> 
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception> 
        public async Task<Stat> setACLAsync(string path, List<ACL> acl, int version = -1) {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int) ZooDefs.OpCode.setACL);
            SetACLRequest request = new SetACLRequest();
            request.setPath(serverPath);
            if (acl != null && acl.size() == 0) {
                throw new KeeperException.InvalidACLException(clientPath);
            }
            request.setAcl(acl);
            request.setVersion(version);
            SetACLResponse response = new SetACLResponse();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, null).ConfigureAwait(false);
            if (r.getErr() != 0) {
                throw KeeperException.create(r.getErr(), clientPath);
            }
            return response.getStat();
        }

        /// <summary>
        /// For the given znode path return the stat and children list.
        /// If the watch is non-null and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch will be
        /// triggered by a successful operation that deletes the node of the given
        /// path or creates/delete a child under the node.
        /// The list of children returned is not sorted and no guarantee is provided
        /// as to its natural or lexical order.
        /// @since 3.3.0
        /// </summary>
        /// <param name="path">path</param>
        /// <param name="watcher">explicit watcher</param>
        /// <returns>an unordered children of the node with the given path</returns>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception>
        /// <exception cref="ArgumentException">when <paramref name="path" /> is invalid</exception>
        public async Task<ChildrenResult> getChildrenAsync(string path, Watcher watcher)
        {
            string clientPath = path;
            PathUtils.validatePath(clientPath);

            // the watch contains the un-chroot path
            WatchRegistration wcb = null;
            if (watcher != null)
            {
                wcb = new ChildWatchRegistration(watchManager, watcher, clientPath);
            }

            string serverPath = prependChroot(clientPath);

            RequestHeader h = new RequestHeader();
            h.set_Type((int)ZooDefs.OpCode.getChildren2);
            GetChildren2Request request = new GetChildren2Request();
            request.setPath(serverPath);
            request.setWatch(watcher != null);
            GetChildren2Response response = new GetChildren2Response();
            ReplyHeader r = await cnxn.submitRequest(h, request, response, wcb).ConfigureAwait(false);
            if (r.getErr() != 0)
            {
                throw KeeperException.create(r.getErr(), clientPath);
            }

            return new ChildrenResult(response.getChildren(), response.getStat());
        }
        /// <summary>
        /// For the given znode path return the stat and children list.
        /// If the watch is true and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch will be
        /// triggered by a successful operation that deletes the node of the given
        /// path or creates/delete a child under the node.
        /// The list of children returned is not sorted and no guarantee is provided
        /// as to its natural or lexical order.
        /// @since 3.3.0
        /// </summary>
        /// <param name="path">The path.</param>
        /// <param name="watch">watch</param>
        /// <returns>an unordered children of the node with the given path</returns>
        /// <exception cref="KeeperException.NoNodeException">if no node with the given path exists.</exception>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception>
        /// <exception cref="ArgumentException">when <paramref name="path" /> is invalid</exception>
        public Task<ChildrenResult> getChildrenAsync(string path, bool watch = false) {
            return getChildrenAsync(path, watch ? watchManager.defaultWatcher : null);
        }

        /// <summary>
        /// Asynchronous sync. Flushes channel between process and leader.
        /// </summary>
        /// <param name="path">path</param>
        /// <exception cref="KeeperException.ConnectionLossException">the connection has been lost, you should retry</exception>
        /// <exception cref="KeeperException.SessionExpiredException">the server says the session has expired, you should create a new client</exception>
        /// <exception cref="KeeperException">the server signals an error with a non-zero error code</exception>  
        /// <exception cref="ArgumentException">when <paramref name="path"/> is invalid</exception>
        public async Task sync(string path) {
            string clientPath = path;
            PathUtils.validatePath(clientPath);
            string serverPath = prependChroot(clientPath);
            RequestHeader h = new RequestHeader();
            h.set_Type((int) ZooDefs.OpCode.sync);
            SyncRequest request = new SyncRequest();
            SyncResponse response = new SyncResponse();
            request.setPath(serverPath);
            await
                cnxn.queuePacket(h, new ReplyHeader(), request, response, clientPath, serverPath, null)
                    .PacketTask.ConfigureAwait(false);
        }

        /// <summary>
        /// Gets the current state of the connection
        /// </summary>
        public States getState() {
            return cnxn.getState();
        }

        /// <summary>
        /// string representation of this ZooKeeper client. Suitable for things
        /// like logging.
        /// 
        /// Do NOT count on the format of this string, it may change without
        /// warning.
        /// 
        /// @since 3.3.0
        /// </summary>
        public override string ToString() {
            States state = getState();
            return ("State:" + state + (cnxn.getState().isConnected() ? " Timeout:" + getSessionTimeout() + " " : " ") + cnxn);
        }

        #region Using        
        /// <summary>
        /// Creates a ZK with <see cref="ZooKeeper(string,int,Watcher,long,byte[],bool)"/> and pass it to <paramref name="zkMethod"/>
        /// </summary>
        public static Task Using(string connectstring, int sessionTimeout, Watcher watcher, long sessionId, byte[] sessionPasswd,
           Func<ZooKeeper, Task> zkMethod, bool canBeReadOnly = false)
        {
            return Using(new ZooKeeper(connectstring, sessionTimeout, watcher, sessionId, sessionPasswd, canBeReadOnly), zkMethod);
        }

        /// <summary>
        /// Creates a ZK with <see cref="ZooKeeper(string,int,Watcher,long,byte[],bool)"/> and pass it to <paramref name="zkMethod"/>
        /// </summary>
        public static Task<T> Using<T>(string connectstring, int sessionTimeout, Watcher watcher, long sessionId, byte[] sessionPasswd,
            Func<ZooKeeper, Task<T>> zkMethod, bool canBeReadOnly = false)
        {
            return Using(new ZooKeeper(connectstring, sessionTimeout, watcher, sessionId, sessionPasswd, canBeReadOnly), zkMethod);
        }

        /// <summary>
        /// Creates a ZK with <see cref="ZooKeeper(string,int,Watcher,bool)"/> and pass it to <paramref name="zkMethod"/>
        /// </summary>
        public static Task Using(string connectstring, int sessionTimeout, Watcher watcher,
           Func<ZooKeeper, Task> zkMethod, bool canBeReadOnly = false)
        {
            return Using(new ZooKeeper(connectstring, sessionTimeout, watcher, canBeReadOnly), zkMethod);
        }

        /// <summary>
        /// Creates a ZK with <see cref="ZooKeeper(string,int,Watcher,bool)"/> and pass it to <paramref name="zkMethod"/>
        /// </summary>
        public static Task<T> Using<T>(string connectstring, int sessionTimeout, Watcher watcher,
            Func<ZooKeeper, Task<T>> zkMethod, bool canBeReadOnly = false)
        {
            return Using(new ZooKeeper(connectstring, sessionTimeout, watcher, canBeReadOnly), zkMethod);
        }

        private static Task Using(ZooKeeper zooKeeper, Func<ZooKeeper, Task> zkMethod) {
            return Using(zooKeeper,
                async zk =>
                {
                    await zkMethod(zk).ConfigureAwait(false);
                    return 0;
                });
        }

        private static async Task<T> Using<T>(ZooKeeper zk, Func<ZooKeeper, Task<T>> zkMethod) {

            return await (await TryOperation(zk,zkMethod).ContinueWith(async zkTask =>
            {
                await zk.closeAsync().ConfigureAwait(false);
                return await zkTask.ConfigureAwait(false);
            }).ConfigureAwait(false)).ConfigureAwait(false);
        }

        private static async Task<T> TryOperation<T>(ZooKeeper zk, Func<ZooKeeper, Task<T>> zkMethod) 
        {
            Task<T> zkMethodTask;
            Task timeoutTask = null;
            do {
                zkMethodTask = zkMethod(zk);
                try {
                    return await zkMethodTask.ConfigureAwait(false);
                }
                catch (KeeperException.ConnectionLossException) {
                }
                if (timeoutTask == null)
                    timeoutTask = TaskEx.Delay(zk.userDefinedSessionTimeout);

                await TaskEx.WhenAny(zk.connectedTask.WaitAsync(), timeoutTask).ConfigureAwait(false);
            } while (!timeoutTask.IsCompleted);
            return await zkMethodTask.ConfigureAwait(false);
        }

        #endregion
    }
}
