/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.AsyncCallback.*;
import org.apache.zookeeper.OpResult.ErrorResult;
import org.apache.zookeeper.client.ConnectStringParser;
import org.apache.zookeeper.client.HostProvider;
import org.apache.zookeeper.client.StaticHostProvider;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.proto.*;
import org.apache.zookeeper.server.DataTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.*;

/**
 * This is the main class of ZooKeeper client library. To use a ZooKeeper
 * service, an application must first instantiate an object of ZooKeeper class.
 * All the iterations will be done by calling the methods of ZooKeeper class.
 * The methods of this class are thread-safe unless otherwise noted.
 * <p>
 * Once a connection to a server is established, a session ID is assigned to the
 * client. The client will send heart beats to the server periodically to keep
 * the session valid.
 * <p>
 * The application can call ZooKeeper APIs through a client as long as the
 * session ID of the client remains valid.
 * <p>
 * If for some reason, the client fails to send heart beats to the server for a
 * prolonged period of time (exceeding the sessionTimeout value, for instance),
 * the server will expire the session, and the session ID will become invalid.
 * The client object will no longer be usable. To make ZooKeeper API calls, the
 * application must create a new client object.
 * <p>
 * If the ZooKeeper server the client currently connects to fails or otherwise
 * does not respond, the client will automatically try to connect to another
 * server before its session ID expires. If successful, the application can
 * continue to use the client.
 * <p>
 * The ZooKeeper API methods are either synchronous or asynchronous. Synchronous
 * methods blocks until the server has responded. Asynchronous methods just queue
 * the request for sending and return immediately. They take a callback object that
 * will be executed either on successful execution of the request or on error with
 * an appropriate return code (rc) indicating the error.
 * <p>
 * Some successful ZooKeeper API calls can leave watches on the "data nodes" in
 * the ZooKeeper server. Other successful ZooKeeper API calls can trigger those
 * watches. Once a watch is triggered, an event will be delivered to the client
 * which left the watch at the first place. Each watch can be triggered only
 * once. Thus, up to one event will be delivered to a client for every watch it
 * leaves.
 * <p>
 * A client needs an object of a class implementing Watcher interface for
 * processing the events delivered to the client.
 *
 * When a client drops the current connection and re-connects to a server, all the
 * existing watches are considered as being triggered but the undelivered events
 * are lost. To emulate this, the client will generate a special event to tell
 * the event handler a connection has been dropped. This special event has
 * EventType None and KeeperState Disconnected.
 *
 */
@InterfaceAudience.Public
public class ZooKeeper {

    public static final String ZOOKEEPER_CLIENT_CNXN_SOCKET = "zookeeper.clientCnxnSocket";

    protected final ClientCnxn cnxn;
    private static final Logger LOG;
    static {
        //Keep these two lines together to keep the initialization order explicit
        LOG = LoggerFactory.getLogger(ZooKeeper.class);
        Environment.logEnv("Client environment:", LOG);
    }


    public ZooKeeperSaslClient getSaslClient() {
        return cnxn.zooKeeperSaslClient;
    }

    private final ZKWatchManager watchManager = new ZKWatchManager();

    List<String> getDataWatches() {
        synchronized(watchManager.dataWatches) {
            List<String> rc = new ArrayList<String>(watchManager.dataWatches.keySet());
            return rc;
        }
    }
    List<String> getExistWatches() {
        synchronized(watchManager.existWatches) {
            List<String> rc =  new ArrayList<String>(watchManager.existWatches.keySet());
            return rc;
        }
    }
    List<String> getChildWatches() {
        synchronized(watchManager.childWatches) {
            List<String> rc = new ArrayList<String>(watchManager.childWatches.keySet());
            return rc;
        }
    }

    /**
     * Manage watchers & handle events generated by the ClientCnxn object.
     *
     * We are implementing this as a nested class of ZooKeeper so that
     * the public methods will not be exposed as part of the ZooKeeper client
     * API.
     */
    private static class ZKWatchManager implements ClientWatchManager {
        private final Map<String, Set<Watcher>> dataWatches =
            new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> existWatches =
            new HashMap<String, Set<Watcher>>();
        private final Map<String, Set<Watcher>> childWatches =
            new HashMap<String, Set<Watcher>>();

        private volatile Watcher defaultWatcher;

        final private void addTo(Set<Watcher> from, Set<Watcher> to) {
            if (from != null) {
                to.addAll(from);
            }
        }

        /* (non-Javadoc)
         * @see org.apache.zookeeper.ClientWatchManager#materialize(Event.KeeperState, 
         *                                                        Event.EventType, java.lang.String)
         */
        @Override
        public Set<Watcher> materialize(Watcher.Event.KeeperState state,
                                        Watcher.Event.EventType type,
                                        String clientPath)
        {
            Set<Watcher> result = new HashSet<Watcher>();

            switch (type) {
            case None:
                result.add(defaultWatcher);
                boolean clear = ClientCnxn.getDisableAutoResetWatch() &&
                        state != Watcher.Event.KeeperState.SyncConnected;

                synchronized(dataWatches) {
                    for(Set<Watcher> ws: dataWatches.values()) {
                        result.addAll(ws);
                    }
                    if (clear) {
                        dataWatches.clear();
                    }
                }

                synchronized(existWatches) {
                    for(Set<Watcher> ws: existWatches.values()) {
                        result.addAll(ws);
                    }
                    if (clear) {
                        existWatches.clear();
                    }
                }

                synchronized(childWatches) {
                    for(Set<Watcher> ws: childWatches.values()) {
                        result.addAll(ws);
                    }
                    if (clear) {
                        childWatches.clear();
                    }
                }

                return result;
            case NodeDataChanged:
            case NodeCreated:
                synchronized (dataWatches) {
                    addTo(dataWatches.remove(clientPath), result);
                }
                synchronized (existWatches) {
                    addTo(existWatches.remove(clientPath), result);
                }
                break;
            case NodeChildrenChanged:
                synchronized (childWatches) {
                    addTo(childWatches.remove(clientPath), result);
                }
                break;
            case NodeDeleted:
                synchronized (dataWatches) {
                    addTo(dataWatches.remove(clientPath), result);
                }
                // XXX This shouldn't be needed, but just in case
                synchronized (existWatches) {
                    Set<Watcher> list = existWatches.remove(clientPath);
                    if (list != null) {
                        addTo(list, result);
                        LOG.warn("We are triggering an exists watch for delete! Shouldn't happen!");
                    }
                }
                synchronized (childWatches) {
                    addTo(childWatches.remove(clientPath), result);
                }
                break;
            default:
                String msg = "Unhandled watch event type " + type
                    + " with state " + state + " on path " + clientPath;
                LOG.error(msg);
                throw new RuntimeException(msg);
            }

            return result;
        }
    }

    /**
     * Register a watcher for a particular path.
     */
    abstract class WatchRegistration {
        private Watcher watcher;
        private String clientPath;
        public WatchRegistration(Watcher watcher, String clientPath)
        {
            this.watcher = watcher;
            this.clientPath = clientPath;
        }

        abstract protected Map<String, Set<Watcher>> getWatches(int rc);

        /**
         * Register the watcher with the set of watches on path.
         * @param rc the result code of the operation that attempted to
         * add the watch on the path.
         */
        public void register(int rc) {
            if (shouldAddWatch(rc)) {
                Map<String, Set<Watcher>> watches = getWatches(rc);
                synchronized(watches) {
                    Set<Watcher> watchers = watches.get(clientPath);
                    if (watchers == null) {
                        watchers = new HashSet<Watcher>();
                        watches.put(clientPath, watchers);
                    }
                    watchers.add(watcher);
                }
            }
        }
        /**
         * Determine whether the watch should be added based on return code.
         * @param rc the result code of the operation that attempted to add the
         * watch on the node
         * @return true if the watch should be added, otw false
         */
        protected boolean shouldAddWatch(int rc) {
            return rc == 0;
        }
    }

    /** Handle the special case of exists watches - they add a watcher
     * even in the case where NONODE result code is returned.
     */
    class ExistsWatchRegistration extends WatchRegistration {
        public ExistsWatchRegistration(Watcher watcher, String clientPath) {
            super(watcher, clientPath);
        }

        @Override
        protected Map<String, Set<Watcher>> getWatches(int rc) {
            return rc == 0 ?  watchManager.dataWatches : watchManager.existWatches;
        }

        @Override
        protected boolean shouldAddWatch(int rc) {
            return rc == 0 || rc == KeeperException.Code.NONODE.intValue();
        }
    }

    class DataWatchRegistration extends WatchRegistration {
        public DataWatchRegistration(Watcher watcher, String clientPath) {
            super(watcher, clientPath);
        }

        @Override
        protected Map<String, Set<Watcher>> getWatches(int rc) {
            return watchManager.dataWatches;
        }
    }

    class ChildWatchRegistration extends WatchRegistration {
        public ChildWatchRegistration(Watcher watcher, String clientPath) {
            super(watcher, clientPath);
        }

        @Override
        protected Map<String, Set<Watcher>> getWatches(int rc) {
            return watchManager.childWatches;
        }
    }

    @InterfaceAudience.Public
    public enum States {
        CONNECTING, ASSOCIATING, CONNECTED, CONNECTEDREADONLY,
        CLOSED, AUTH_FAILED, NOT_CONNECTED;

        public boolean isAlive() {
            return this != CLOSED && this != AUTH_FAILED;
        }

        /**
         * Returns whether we are connected to a server (which
         * could possibly be read-only, if this client is allowed
         * to go to read-only mode)
         * */
        public boolean isConnected() {
            return this == CONNECTED || this == CONNECTEDREADONLY;
        }
    }

    /**
     * To create a ZooKeeper client object, the application needs to pass a
     * connection string containing a comma separated list of host:port pairs,
     * each corresponding to a ZooKeeper server.
     * <p>
     * Session establishment is asynchronous. This constructor will initiate
     * connection to the server and return immediately - potentially (usually)
     * before the session is fully established. The watcher argument specifies
     * the watcher that will be notified of any changes in state. This
     * notification can come at any point before or after the constructor call
     * has returned.
     * <p>
     * The instantiated ZooKeeper client object will pick an arbitrary server
     * from the connectString and attempt to connect to it. If establishment of
     * the connection fails, another server in the connect string will be tried
     * (the order is non-deterministic, as we random shuffle the list), until a
     * connection is established. The client will continue attempts until the
     * session is explicitly closed.
     * <p>
     * Added in 3.2.0: An optional "chroot" suffix may also be appended to the
     * connection string. This will run the client commands while interpreting
     * all paths relative to this root (similar to the unix chroot command).
     *
     * @param connectString
     *            comma separated host:port pairs, each corresponding to a zk
     *            server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002" If
     *            the optional chroot suffix is used the example would look
     *            like: "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a"
     *            where the client would be rooted at "/app/a" and all paths
     *            would be relative to this root - ie getting/setting/etc...
     *            "/foo/bar" would result in operations being run on
     *            "/app/a/foo/bar" (from the server perspective).
     * @param sessionTimeout
     *            session timeout in milliseconds
     * @param watcher
     *            a watcher object which will be notified of state changes, may
     *            also be notified for node events
     *
     * @throws IOException
     *             in cases of network failure
     * @throws IllegalArgumentException
     *             if an invalid chroot path is specified
     */
    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher)
        throws IOException
    {
        this(connectString, sessionTimeout, watcher, false);
    }

    /**
     * To create a ZooKeeper client object, the application needs to pass a
     * connection string containing a comma separated list of host:port pairs,
     * each corresponding to a ZooKeeper server.
     * <p>
     * Session establishment is asynchronous. This constructor will initiate
     * connection to the server and return immediately - potentially (usually)
     * before the session is fully established. The watcher argument specifies
     * the watcher that will be notified of any changes in state. This
     * notification can come at any point before or after the constructor call
     * has returned.
     * <p>
     * The instantiated ZooKeeper client object will pick an arbitrary server
     * from the connectString and attempt to connect to it. If establishment of
     * the connection fails, another server in the connect string will be tried
     * (the order is non-deterministic, as we random shuffle the list), until a
     * connection is established. The client will continue attempts until the
     * session is explicitly closed.
     * <p>
     * Added in 3.2.0: An optional "chroot" suffix may also be appended to the
     * connection string. This will run the client commands while interpreting
     * all paths relative to this root (similar to the unix chroot command).
     *
     * @param connectString
     *            comma separated host:port pairs, each corresponding to a zk
     *            server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002" If
     *            the optional chroot suffix is used the example would look
     *            like: "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a"
     *            where the client would be rooted at "/app/a" and all paths
     *            would be relative to this root - ie getting/setting/etc...
     *            "/foo/bar" would result in operations being run on
     *            "/app/a/foo/bar" (from the server perspective).
     * @param sessionTimeout
     *            session timeout in milliseconds
     * @param watcher
     *            a watcher object which will be notified of state changes, may
     *            also be notified for node events
     * @param canBeReadOnly
     *            (added in 3.4) whether the created client is allowed to go to
     *            read-only mode in case of partitioning. Read-only mode
     *            basically means that if the client can't find any majority
     *            servers but there's partitioned server it could reach, it
     *            connects to one in read-only mode, i.e. read requests are
     *            allowed while write requests are not. It continues seeking for
     *            majority in the background.
     *
     * @throws IOException
     *             in cases of network failure
     * @throws IllegalArgumentException
     *             if an invalid chroot path is specified
     */
    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher,
            boolean canBeReadOnly)
        throws IOException
    {
        LOG.info("Initiating client connection, connectString=" + connectString
                + " sessionTimeout=" + sessionTimeout + " watcher=" + watcher);

        watchManager.defaultWatcher = watcher;

        ConnectStringParser connectStringParser = new ConnectStringParser(
                connectString);
        HostProvider hostProvider = new StaticHostProvider(
                connectStringParser.getServerAddresses());
        cnxn = new ClientCnxn(connectStringParser.getChrootPath(),
                hostProvider, sessionTimeout, this, watchManager,
                getClientCnxnSocket(), canBeReadOnly);
        cnxn.start();
    }

    /**
     * To create a ZooKeeper client object, the application needs to pass a
     * connection string containing a comma separated list of host:port pairs,
     * each corresponding to a ZooKeeper server.
     * <p>
     * Session establishment is asynchronous. This constructor will initiate
     * connection to the server and return immediately - potentially (usually)
     * before the session is fully established. The watcher argument specifies
     * the watcher that will be notified of any changes in state. This
     * notification can come at any point before or after the constructor call
     * has returned.
     * <p>
     * The instantiated ZooKeeper client object will pick an arbitrary server
     * from the connectString and attempt to connect to it. If establishment of
     * the connection fails, another server in the connect string will be tried
     * (the order is non-deterministic, as we random shuffle the list), until a
     * connection is established. The client will continue attempts until the
     * session is explicitly closed (or the session is expired by the server).
     * <p>
     * Added in 3.2.0: An optional "chroot" suffix may also be appended to the
     * connection string. This will run the client commands while interpreting
     * all paths relative to this root (similar to the unix chroot command).
     * <p>
     * Use {@link #getSessionId} and {@link #getSessionPasswd} on an established
     * client connection, these values must be passed as sessionId and
     * sessionPasswd respectively if reconnecting. Otherwise, if not
     * reconnecting, use the other constructor which does not require these
     * parameters.
     *
     * @param connectString
     *            comma separated host:port pairs, each corresponding to a zk
     *            server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
     *            If the optional chroot suffix is used the example would look
     *            like: "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a"
     *            where the client would be rooted at "/app/a" and all paths
     *            would be relative to this root - ie getting/setting/etc...
     *            "/foo/bar" would result in operations being run on
     *            "/app/a/foo/bar" (from the server perspective).
     * @param sessionTimeout
     *            session timeout in milliseconds
     * @param watcher
     *            a watcher object which will be notified of state changes, may
     *            also be notified for node events
     * @param sessionId
     *            specific session id to use if reconnecting
     * @param sessionPasswd
     *            password for this session
     *
     * @throws IOException in cases of network failure
     * @throws IllegalArgumentException if an invalid chroot path is specified
     * @throws IllegalArgumentException for an invalid list of ZooKeeper hosts
     */
    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher,
            long sessionId, byte[] sessionPasswd)
        throws IOException
    {
        this(connectString, sessionTimeout, watcher, sessionId, sessionPasswd, false);
    }

    /**
     * To create a ZooKeeper client object, the application needs to pass a
     * connection string containing a comma separated list of host:port pairs,
     * each corresponding to a ZooKeeper server.
     * <p>
     * Session establishment is asynchronous. This constructor will initiate
     * connection to the server and return immediately - potentially (usually)
     * before the session is fully established. The watcher argument specifies
     * the watcher that will be notified of any changes in state. This
     * notification can come at any point before or after the constructor call
     * has returned.
     * <p>
     * The instantiated ZooKeeper client object will pick an arbitrary server
     * from the connectString and attempt to connect to it. If establishment of
     * the connection fails, another server in the connect string will be tried
     * (the order is non-deterministic, as we random shuffle the list), until a
     * connection is established. The client will continue attempts until the
     * session is explicitly closed (or the session is expired by the server).
     * <p>
     * Added in 3.2.0: An optional "chroot" suffix may also be appended to the
     * connection string. This will run the client commands while interpreting
     * all paths relative to this root (similar to the unix chroot command).
     * <p>
     * Use {@link #getSessionId} and {@link #getSessionPasswd} on an established
     * client connection, these values must be passed as sessionId and
     * sessionPasswd respectively if reconnecting. Otherwise, if not
     * reconnecting, use the other constructor which does not require these
     * parameters.
     *
     * @param connectString
     *            comma separated host:port pairs, each corresponding to a zk
     *            server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
     *            If the optional chroot suffix is used the example would look
     *            like: "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a"
     *            where the client would be rooted at "/app/a" and all paths
     *            would be relative to this root - ie getting/setting/etc...
     *            "/foo/bar" would result in operations being run on
     *            "/app/a/foo/bar" (from the server perspective).
     * @param sessionTimeout
     *            session timeout in milliseconds
     * @param watcher
     *            a watcher object which will be notified of state changes, may
     *            also be notified for node events
     * @param sessionId
     *            specific session id to use if reconnecting
     * @param sessionPasswd
     *            password for this session
     * @param canBeReadOnly
     *            (added in 3.4) whether the created client is allowed to go to
     *            read-only mode in case of partitioning. Read-only mode
     *            basically means that if the client can't find any majority
     *            servers but there's partitioned server it could reach, it
     *            connects to one in read-only mode, i.e. read requests are
     *            allowed while write requests are not. It continues seeking for
     *            majority in the background.
     *
     * @throws IOException in cases of network failure
     * @throws IllegalArgumentException if an invalid chroot path is specified
     */
    public ZooKeeper(String connectString, int sessionTimeout, Watcher watcher,
            long sessionId, byte[] sessionPasswd, boolean canBeReadOnly)
        throws IOException
    {
        LOG.info("Initiating client connection, connectString=" + connectString
                + " sessionTimeout=" + sessionTimeout
                + " watcher=" + watcher
                + " sessionId=" + Long.toHexString(sessionId)
                + " sessionPasswd="
                + (sessionPasswd == null ? "<null>" : "<hidden>"));

        watchManager.defaultWatcher = watcher;

        ConnectStringParser connectStringParser = new ConnectStringParser(
                connectString);
        HostProvider hostProvider = new StaticHostProvider(
                connectStringParser.getServerAddresses());
        cnxn = new ClientCnxn(connectStringParser.getChrootPath(),
                hostProvider, sessionTimeout, this, watchManager,
                getClientCnxnSocket(), sessionId, sessionPasswd, canBeReadOnly);
        cnxn.seenRwServerBefore = true; // since user has provided sessionId
        cnxn.start();
    }

    // VisibleForTesting
    public Testable getTestable() {
        return new ZooKeeperTestable(this, cnxn);
    }

    /**
     * The session id for this ZooKeeper client instance. The value returned is
     * not valid until the client connects to a server and may change after a
     * re-connect.
     *
     * This method is NOT thread safe
     *
     * @return current session id
     */
    public long getSessionId() {
        return cnxn.getSessionId();
    }

    /**
     * The session password for this ZooKeeper client instance. The value
     * returned is not valid until the client connects to a server and may
     * change after a re-connect.
     *
     * This method is NOT thread safe
     *
     * @return current session password
     */
    public byte[] getSessionPasswd() {
        return cnxn.getSessionPasswd();
    }

    /**
     * The negotiated session timeout for this ZooKeeper client instance. The
     * value returned is not valid until the client connects to a server and
     * may change after a re-connect.
     *
     * This method is NOT thread safe
     *
     * @return current session timeout
     */
    public int getSessionTimeout() {
        return cnxn.getSessionTimeout();
    }

    /**
     * Add the specified scheme:auth information to this connection.
     *
     * This method is NOT thread safe
     *
     * @param scheme
     * @param auth
     */
    public void addAuthInfo(String scheme, byte auth[]) {
        cnxn.addAuthInfo(scheme, auth);
    }

    /**
     * Specify the default watcher for the connection (overrides the one
     * specified during construction).
     *
     * @param watcher
     */
    public synchronized void register(Watcher watcher) {
        watchManager.defaultWatcher = watcher;
    }

    /**
     * Close this client object. Once the client is closed, its session becomes
     * invalid. All the ephemeral nodes in the ZooKeeper server associated with
     * the session will be removed. The watches left on those nodes (and on
     * their parents) will be triggered.
     *
     * @throws InterruptedException
     */
    public synchronized void close() throws InterruptedException {
        if (!cnxn.getState().isAlive()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Close called on already closed client");
            }
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Closing session: 0x" + Long.toHexString(getSessionId()));
        }

        try {
            cnxn.close();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Ignoring unexpected exception during close", e);
            }
        }

        LOG.info("Session: 0x" + Long.toHexString(getSessionId()) + " closed");
    }

    /**
     * Prepend the chroot to the client path (if present). The expectation of
     * this function is that the client path has been validated before this
     * function is called
     * @param clientPath path to the node
     * @return server view of the path (chroot prepended to client path)
     */
    private String prependChroot(String clientPath) {
        if (cnxn.chrootPath != null) {
            // handle clientPath = "/"
            if (clientPath.length() == 1) {
                return cnxn.chrootPath;
            }
            return cnxn.chrootPath + clientPath;
        } else {
            return clientPath;
        }
    }

    /**
     * Create a node with the given path. The node data will be the given data,
     * and node acl will be the given acl.
     * <p>
     * The flags argument specifies whether the created node will be ephemeral
     * or not.
     * <p>
     * An ephemeral node will be removed by the ZooKeeper automatically when the
     * session associated with the creation of the node expires.
     * <p>
     * The flags argument can also specify to create a sequential node. The
     * actual path name of a sequential node will be the given path plus a
     * suffix "i" where i is the current sequential number of the node. The sequence
     * number is always fixed length of 10 digits, 0 padded. Once
     * such a node is created, the sequential number will be incremented by one.
     * <p>
     * If a node with the same actual path already exists in the ZooKeeper, a
     * KeeperException with error code KeeperException.NodeExists will be
     * thrown. Note that since a different actual path is used for each
     * invocation of creating sequential node with the same path argument, the
     * call will never throw "file exists" KeeperException.
     * <p>
     * If the parent node does not exist in the ZooKeeper, a KeeperException
     * with error code KeeperException.NoNode will be thrown.
     * <p>
     * An ephemeral node cannot have children. If the parent node of the given
     * path is ephemeral, a KeeperException with error code
     * KeeperException.NoChildrenForEphemerals will be thrown.
     * <p>
     * This operation, if successful, will trigger all the watches left on the
     * node of the given path by exists and getData API calls, and the watches
     * left on the parent node by getChildren API calls.
     * <p>
     * If a node is created successfully, the ZooKeeper server will trigger the
     * watches on the path left by exists calls, and the watches on the parent
     * of the node by getChildren calls.
     * <p>
     * The maximum allowable size of the data array is 1 MB (1,048,576 bytes).
     * Arrays larger than this will cause a KeeperExecption to be thrown.
     *
     * @param path
     *                the path for the node
     * @param data
     *                the initial data for the node
     * @param acl
     *                the acl for the node
     * @param createMode
     *                specifying whether the node to be created is ephemeral
     *                and/or sequential
     * @return the actual path of the created node
     * @throws KeeperException if the server returns a non-zero error code
     * @throws KeeperException.InvalidACLException if the ACL is invalid, null, or empty
     * @throws InterruptedException if the transaction is interrupted
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public String create(final String path, byte data[], List<ACL> acl,
            CreateMode createMode)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath, createMode.isSequential());

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.create);
        CreateRequest request = new CreateRequest();
        CreateResponse response = new CreateResponse();
        request.setData(data);
        request.setFlags(createMode.toFlag());
        request.setPath(serverPath);
        if (acl != null && acl.size() == 0) {
            throw new KeeperException.InvalidACLException();
        }
        request.setAcl(acl);
        ReplyHeader r = cnxn.submitRequest(h, request, response, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        if (cnxn.chrootPath == null) {
            return response.getPath();
        } else {
            return response.getPath().substring(cnxn.chrootPath.length());
        }
    }

    /**
     * The asynchronous version of create.
     *
     * @see #create(String, byte[], List, CreateMode)
     */

    public void create(final String path, byte data[], List<ACL> acl,
            CreateMode createMode,  StringCallback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath, createMode.isSequential());

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.create);
        CreateRequest request = new CreateRequest();
        CreateResponse response = new CreateResponse();
        ReplyHeader r = new ReplyHeader();
        request.setData(data);
        request.setFlags(createMode.toFlag());
        request.setPath(serverPath);
        request.setAcl(acl);
        cnxn.queuePacket(h, r, request, response, cb, clientPath,
                serverPath, ctx, null);
    }

    /**
     * Delete the node with the given path. The call will succeed if such a node
     * exists, and the given version matches the node's version (if the given
     * version is -1, it matches any node's versions).
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if the nodes does not exist.
     * <p>
     * A KeeperException with error code KeeperException.BadVersion will be
     * thrown if the given version does not match the node's version.
     * <p>
     * A KeeperException with error code KeeperException.NotEmpty will be thrown
     * if the node has children.
     * <p>
     * This operation, if successful, will trigger all the watches on the node
     * of the given path left by exists API calls, and the watches on the parent
     * node left by getChildren API calls.
     *
     * @param path
     *                the path of the node to be deleted.
     * @param version
     *                the expected node version.
     * @throws InterruptedException IF the server transaction is interrupted
     * @throws KeeperException If the server signals an error with a non-zero
     *   return code.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public void delete(final String path, int version)
        throws InterruptedException, KeeperException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath;

        // maintain semantics even in chroot case
        // specifically - root cannot be deleted
        // I think this makes sense even in chroot case.
        if (clientPath.equals("/")) {
            // a bit of a hack, but delete(/) will never succeed and ensures
            // that the same semantics are maintained
            serverPath = clientPath;
        } else {
            serverPath = prependChroot(clientPath);
        }

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.delete);
        DeleteRequest request = new DeleteRequest();
        request.setPath(serverPath);
        request.setVersion(version);
        ReplyHeader r = cnxn.submitRequest(h, request, null, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
    }

    /**
     * Executes multiple ZooKeeper operations or none of them.
     * <p>
     * On success, a list of results is returned.
     * On failure, an exception is raised which contains partial results and
     * error details, see {@link KeeperException#getResults}
     * <p>
     * Note: The maximum allowable size of all of the data arrays in all of
     * the setData operations in this single request is typically 1 MB
     * (1,048,576 bytes). This limit is specified on the server via
     * <a href="http://zookeeper.apache.org/doc/current/zookeeperAdmin.html#Unsafe+Options">jute.maxbuffer</a>.
     * Requests larger than this will cause a KeeperException to be
     * thrown.
     *
     * @param ops An iterable that contains the operations to be done.
     * These should be created using the factory methods on {@link Op}.
     * @return A list of results, one for each input Op, the order of
     * which exactly matches the order of the <code>ops</code> input
     * operations.
     * @throws InterruptedException If the operation was interrupted.
     * The operation may or may not have succeeded, but will not have
     * partially succeeded if this exception is thrown.
     * @throws KeeperException If the operation could not be completed
     * due to some error in doing one of the specified ops.
     * @throws IllegalArgumentException if an invalid path is specified
     *
     * @since 3.4.0
     */
    public List<OpResult> multi(Iterable<Op> ops) throws InterruptedException, KeeperException {
        for (Op op : ops) {
            op.validate();
        }
        return multiInternal(generateMultiTransaction(ops));
    }

    /**
     * The asynchronous version of multi.
     *
     * @see #multi(Iterable)
     * @since 3.4.7
     */
    public void multi(Iterable<Op> ops, MultiCallback cb, Object ctx) {
        List<OpResult> results = validatePath(ops);
        if (results.size() > 0) {
            cb.processResult(KeeperException.Code.BADARGUMENTS.intValue(),
                             null, ctx, results);
            return;
        }
        multiInternal(generateMultiTransaction(ops), cb, ctx);
    }

    private List<OpResult> validatePath(Iterable<Op> ops) {
        List<OpResult> results = new ArrayList<OpResult>();
        boolean error = false;
        for (Op op : ops) {
            try {
                op.validate();
            } catch (IllegalArgumentException iae) {
                LOG.error("IllegalArgumentException: " + iae.getMessage());
                ErrorResult err = new ErrorResult(
                        KeeperException.Code.BADARGUMENTS.intValue());
                results.add(err);
                error = true;
                continue;
            } catch (KeeperException ke) {
                LOG.error("KeeperException: " + ke.getMessage());
                ErrorResult err = new ErrorResult(ke.code().intValue());
                results.add(err);
                error = true;
                continue;
            }
            ErrorResult err = new ErrorResult(
                    KeeperException.Code.RUNTIMEINCONSISTENCY.intValue());
            results.add(err);
        }
        if (false == error) {
            results.clear();
        }
        return results;
    }

    private MultiTransactionRecord generateMultiTransaction(Iterable<Op> ops) {
        List<Op> transaction = new ArrayList<Op>();

        for (Op op : ops) {
            transaction.add(withRootPrefix(op));
        }
        return new MultiTransactionRecord(transaction);
    }

    private Op withRootPrefix(Op op) {
        if (null != op.getPath()) {
            final String serverPath = prependChroot(op.getPath());
            if (!op.getPath().equals(serverPath)) {
                return op.withChroot(serverPath);
            }
        }
        return op;
    }

    protected void multiInternal(MultiTransactionRecord request, MultiCallback cb, Object ctx) {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.multi);
        MultiResponse response = new MultiResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb, null, null, ctx, null);
    }

    protected List<OpResult> multiInternal(MultiTransactionRecord request)
        throws InterruptedException, KeeperException {
        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.multi);
        MultiResponse response = new MultiResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()));
        }

        List<OpResult> results = response.getResultList();
        
        ErrorResult fatalError = null;
        for (OpResult result : results) {
            if (result instanceof ErrorResult && ((ErrorResult)result).getErr() != KeeperException.Code.OK.intValue()) {
                fatalError = (ErrorResult) result;
                break;
            }
        }

        if (fatalError != null) {
            KeeperException ex = KeeperException.create(KeeperException.Code.get(fatalError.getErr()));
            ex.setMultiResults(results);
            throw ex;
        }

        return results;
    }

    /**
     * A Transaction is a thin wrapper on the {@link #multi} method
     * which provides a builder object that can be used to construct
     * and commit an atomic set of operations.
     *
     * @since 3.4.0
     *
     * @return a Transaction builder object
     */
    public Transaction transaction() {
        return new Transaction(this);
    }

    /**
     * The asynchronous version of delete.
     *
     * @see #delete(String, int)
     */
    public void delete(final String path, int version, VoidCallback cb,
            Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath;

        // maintain semantics even in chroot case
        // specifically - root cannot be deleted
        // I think this makes sense even in chroot case.
        if (clientPath.equals("/")) {
            // a bit of a hack, but delete(/) will never succeed and ensures
            // that the same semantics are maintained
            serverPath = clientPath;
        } else {
            serverPath = prependChroot(clientPath);
        }

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.delete);
        DeleteRequest request = new DeleteRequest();
        request.setPath(serverPath);
        request.setVersion(version);
        cnxn.queuePacket(h, new ReplyHeader(), request, null, cb, clientPath,
                serverPath, ctx, null);
    }

    /**
     * Return the stat of the node of the given path. Return null if no such a
     * node exists.
     * <p>
     * If the watch is non-null and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
     * triggered by a successful operation that creates/delete the node or sets
     * the data on the node.
     *
     * @param path the node path
     * @param watcher explicit watcher
     * @return the stat of the node of the given path; return null if no such a
     *         node exists.
     * @throws KeeperException If the server signals an error
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public Stat exists(final String path, Watcher watcher)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new ExistsWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.exists);
        ExistsRequest request = new ExistsRequest();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        SetDataResponse response = new SetDataResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, wcb);
        if (r.getErr() != 0) {
            if (r.getErr() == KeeperException.Code.NONODE.intValue()) {
                return null;
            }
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }

        return response.getStat().getCzxid() == -1 ? null : response.getStat();
    }

    /**
     * Return the stat of the node of the given path. Return null if no such a
     * node exists.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
     * triggered by a successful operation that creates/delete the node or sets
     * the data on the node.
     *
     * @param path
     *                the node path
     * @param watch
     *                whether need to watch this node
     * @return the stat of the node of the given path; return null if no such a
     *         node exists.
     * @throws KeeperException If the server signals an error
     * @throws InterruptedException If the server transaction is interrupted.
     */
    public Stat exists(String path, boolean watch) throws KeeperException,
        InterruptedException
    {
        return exists(path, watch ? watchManager.defaultWatcher : null);
    }

    /**
     * The asynchronous version of exists.
     *
     * @see #exists(String, Watcher)
     */
    public void exists(final String path, Watcher watcher,
            StatCallback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new ExistsWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.exists);
        ExistsRequest request = new ExistsRequest();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        SetDataResponse response = new SetDataResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, wcb);
    }

    /**
     * The asynchronous version of exists.
     *
     * @see #exists(String, boolean)
     */
    public void exists(String path, boolean watch, StatCallback cb, Object ctx) {
        exists(path, watch ? watchManager.defaultWatcher : null, cb, ctx);
    }

    /**
     * Return the data and the stat of the node of the given path.
     * <p>
     * If the watch is non-null and the call is successful (no exception is
     * thrown), a watch will be left on the node with the given path. The watch
     * will be triggered by a successful operation that sets data on the node, or
     * deletes the node.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param path the given path
     * @param watcher explicit watcher
     * @param stat the stat of the node
     * @return the data of the node
     * @throws KeeperException If the server signals an error with a non-zero error code
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public byte[] getData(final String path, Watcher watcher, Stat stat)
        throws KeeperException, InterruptedException
     {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new DataWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getData);
        GetDataRequest request = new GetDataRequest();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        GetDataResponse response = new GetDataResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, wcb);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        if (stat != null) {
            DataTree.copyStat(response.getStat(), stat);
        }
        return response.getData();
    }

    /**
     * Return the data and the stat of the node of the given path.
     * <p>
     * If the watch is true and the call is successful (no exception is
     * thrown), a watch will be left on the node with the given path. The watch
     * will be triggered by a successful operation that sets data on the node, or
     * deletes the node.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param path the given path
     * @param watch whether need to watch this node
     * @param stat the stat of the node
     * @return the data of the node
     * @throws KeeperException If the server signals an error with a non-zero error code
     * @throws InterruptedException If the server transaction is interrupted.
     */
    public byte[] getData(String path, boolean watch, Stat stat)
            throws KeeperException, InterruptedException {
        return getData(path, watch ? watchManager.defaultWatcher : null, stat);
    }

    /**
     * The asynchronous version of getData.
     *
     * @see #getData(String, Watcher, Stat)
     */
    public void getData(final String path, Watcher watcher,
            DataCallback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new DataWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getData);
        GetDataRequest request = new GetDataRequest();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        GetDataResponse response = new GetDataResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, wcb);
    }

    /**
     * The asynchronous version of getData.
     *
     * @see #getData(String, boolean, Stat)
     */
    public void getData(String path, boolean watch, DataCallback cb, Object ctx) {
        getData(path, watch ? watchManager.defaultWatcher : null, cb, ctx);
    }

    /**
     * Set the data for the node of the given path if such a node exists and the
     * given version matches the version of the node (if the given version is
     * -1, it matches any node's versions). Return the stat of the node.
     * <p>
     * This operation, if successful, will trigger all the watches on the node
     * of the given path left by getData calls.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * <p>
     * A KeeperException with error code KeeperException.BadVersion will be
     * thrown if the given version does not match the node's version.
     * <p>
     * The maximum allowable size of the data array is 1 MB (1,048,576 bytes).
     * Arrays larger than this will cause a KeeperException to be thrown.
     *
     * @param path
     *                the path of the node
     * @param data
     *                the data to set
     * @param version
     *                the expected matching version
     * @return the state of the node
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public Stat setData(final String path, byte data[], int version)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setData);
        SetDataRequest request = new SetDataRequest();
        request.setPath(serverPath);
        request.setData(data);
        request.setVersion(version);
        SetDataResponse response = new SetDataResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        return response.getStat();
    }

    /**
     * The asynchronous version of setData.
     *
     * @see #setData(String, byte[], int)
     */
    public void setData(final String path, byte data[], int version,
            StatCallback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setData);
        SetDataRequest request = new SetDataRequest();
        request.setPath(serverPath);
        request.setData(data);
        request.setVersion(version);
        SetDataResponse response = new SetDataResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, null);
    }

    /**
     * Return the ACL and stat of the node of the given path.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param path
     *                the given path for the node
     * @param stat
     *                the stat of the node will be copied to this parameter if
     *                not null.
     * @return the ACL array of the given node.
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public List<ACL> getACL(final String path, Stat stat)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getACL);
        GetACLRequest request = new GetACLRequest();
        request.setPath(serverPath);
        GetACLResponse response = new GetACLResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        if (stat != null) {
            DataTree.copyStat(response.getStat(), stat);
        }
        return response.getAcl();
    }

    /**
     * The asynchronous version of getACL.
     *
     * @see #getACL(String, Stat)
     */
    public void getACL(final String path, Stat stat, ACLCallback cb,
            Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getACL);
        GetACLRequest request = new GetACLRequest();
        request.setPath(serverPath);
        GetACLResponse response = new GetACLResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, null);
    }

    /**
     * Set the ACL for the node of the given path if such a node exists and the
     * given aclVersion matches the acl version of the node. Return the stat of the
     * node.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     * <p>
     * A KeeperException with error code KeeperException.BadVersion will be
     * thrown if the given aclVersion does not match the node's aclVersion.
     *
     * @param path the given path for the node
     * @param acl the given acl for the node
     * @param aclVersion the given acl version of the node
     * @return the stat of the node.
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     * @throws org.apache.zookeeper.KeeperException.InvalidACLException If the acl is invalide.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public Stat setACL(final String path, List<ACL> acl, int aclVersion)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setACL);
        SetACLRequest request = new SetACLRequest();
        request.setPath(serverPath);
        if (acl != null && acl.size() == 0) {
            throw new KeeperException.InvalidACLException(clientPath);
        }
        request.setAcl(acl);
        request.setVersion(aclVersion);
        SetACLResponse response = new SetACLResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, null);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        return response.getStat();
    }

    /**
     * The asynchronous version of setACL.
     *
     * @see #setACL(String, List, int)
     */
    public void setACL(final String path, List<ACL> acl, int version,
            StatCallback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.setACL);
        SetACLRequest request = new SetACLRequest();
        request.setPath(serverPath);
        request.setAcl(acl);
        request.setVersion(version);
        SetACLResponse response = new SetACLResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, null);
    }

    /**
     * Return the list of the children of the node of the given path.
     * <p>
     * If the watch is non-null and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch willbe
     * triggered by a successful operation that deletes the node of the given
     * path or creates/delete a child under the node.
     * <p>
     * The list of children returned is not sorted and no guarantee is provided
     * as to its natural or lexical order.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param path
     * @param watcher explicit watcher
     * @return an unordered array of children of the node with the given path
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public List<String> getChildren(final String path, Watcher watcher)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new ChildWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getChildren);
        GetChildrenRequest request = new GetChildrenRequest();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        GetChildrenResponse response = new GetChildrenResponse();
        ReplyHeader r = cnxn.submitRequest(h, request, response, wcb);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        return response.getChildren();
    }

    /**
     * Return the list of the children of the node of the given path.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch willbe
     * triggered by a successful operation that deletes the node of the given
     * path or creates/delete a child under the node.
     * <p>
     * The list of children returned is not sorted and no guarantee is provided
     * as to its natural or lexical order.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param path
     * @param watch
     * @return an unordered array of children of the node with the given path
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     */
    public List<String> getChildren(String path, boolean watch)
            throws KeeperException, InterruptedException {
        return getChildren(path, watch ? watchManager.defaultWatcher : null);
    }

    /**
     * The asynchronous version of getChildren.
     *
     * @see #getChildren(String, Watcher)
     */
    public void getChildren(final String path, Watcher watcher,
            ChildrenCallback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new ChildWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getChildren);
        GetChildrenRequest request = new GetChildrenRequest();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        GetChildrenResponse response = new GetChildrenResponse();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, wcb);
    }

    /**
     * The asynchronous version of getChildren.
     *
     * @see #getChildren(String, boolean)
     */
    public void getChildren(String path, boolean watch, ChildrenCallback cb,
            Object ctx)
    {
        getChildren(path, watch ? watchManager.defaultWatcher : null, cb, ctx);
    }

    /**
     * For the given znode path return the stat and children list.
     * <p>
     * If the watch is non-null and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch willbe
     * triggered by a successful operation that deletes the node of the given
     * path or creates/delete a child under the node.
     * <p>
     * The list of children returned is not sorted and no guarantee is provided
     * as to its natural or lexical order.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @since 3.3.0
     * 
     * @param path
     * @param watcher explicit watcher
     * @param stat stat of the znode designated by path
     * @return an unordered array of children of the node with the given path
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public List<String> getChildren(final String path, Watcher watcher,
            Stat stat)
        throws KeeperException, InterruptedException
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new ChildWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getChildren2);
        GetChildren2Request request = new GetChildren2Request();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        GetChildren2Response response = new GetChildren2Response();
        ReplyHeader r = cnxn.submitRequest(h, request, response, wcb);
        if (r.getErr() != 0) {
            throw KeeperException.create(KeeperException.Code.get(r.getErr()),
                    clientPath);
        }
        if (stat != null) {
            DataTree.copyStat(response.getStat(), stat);
        }
        return response.getChildren();
    }

    /**
     * For the given znode path return the stat and children list.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch willbe
     * triggered by a successful operation that deletes the node of the given
     * path or creates/delete a child under the node.
     * <p>
     * The list of children returned is not sorted and no guarantee is provided
     * as to its natural or lexical order.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @since 3.3.0
     * 
     * @param path
     * @param watch
     * @param stat stat of the znode designated by path
     * @return an unordered array of children of the node with the given path
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero
     *  error code.
     */
    public List<String> getChildren(String path, boolean watch, Stat stat)
            throws KeeperException, InterruptedException {
        return getChildren(path, watch ? watchManager.defaultWatcher : null,
                stat);
    }

    /**
     * The asynchronous version of getChildren.
     *
     * @since 3.3.0
     * 
     * @see #getChildren(String, Watcher, Stat)
     */
    public void getChildren(final String path, Watcher watcher,
            Children2Callback cb, Object ctx)
    {
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        // the watch contains the un-chroot path
        WatchRegistration wcb = null;
        if (watcher != null) {
            wcb = new ChildWatchRegistration(watcher, clientPath);
        }

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.getChildren2);
        GetChildren2Request request = new GetChildren2Request();
        request.setPath(serverPath);
        request.setWatch(watcher != null);
        GetChildren2Response response = new GetChildren2Response();
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, wcb);
    }

    /**
     * The asynchronous version of getChildren.
     *
     * @since 3.3.0
     * 
     * @see #getChildren(String, boolean, Stat)
     */
    public void getChildren(String path, boolean watch, Children2Callback cb,
            Object ctx)
    {
        getChildren(path, watch ? watchManager.defaultWatcher : null, cb, ctx);
    }

    /**
     * Asynchronous sync. Flushes channel between process and leader.
     * @param path
     * @param cb a handler for the callback
     * @param ctx context to be provided to the callback
     * @throws IllegalArgumentException if an invalid path is specified
     */
    public void sync(final String path, VoidCallback cb, Object ctx){
        final String clientPath = path;
        PathUtils.validatePath(clientPath);

        final String serverPath = prependChroot(clientPath);

        RequestHeader h = new RequestHeader();
        h.setType(ZooDefs.OpCode.sync);
        SyncRequest request = new SyncRequest();
        SyncResponse response = new SyncResponse();
        request.setPath(serverPath);
        cnxn.queuePacket(h, new ReplyHeader(), request, response, cb,
                clientPath, serverPath, ctx, null);
    }

    public States getState() {
        return cnxn.getState();
    }

    /**
     * String representation of this ZooKeeper client. Suitable for things
     * like logging.
     * 
     * Do NOT count on the format of this string, it may change without
     * warning.
     * 
     * @since 3.3.0
     */
    @Override
    public String toString() {
        States state = getState();
        return ("State:" + state.toString()
                + (state.isConnected() ?
                        " Timeout:" + getSessionTimeout() + " " :
                        " ")
                + cnxn);
    }

    /*
     * Methods to aid in testing follow.
     * 
     * THESE METHODS ARE EXPECTED TO BE USED FOR TESTING ONLY!!!
     */

    /**
     * Wait up to wait milliseconds for the underlying threads to shutdown.
     * THIS METHOD IS EXPECTED TO BE USED FOR TESTING ONLY!!!
     * 
     * @since 3.3.0
     * 
     * @param wait max wait in milliseconds
     * @return true iff all threads are shutdown, otw false
     */
    protected boolean testableWaitForShutdown(int wait)
        throws InterruptedException
    {
        cnxn.sendThread.join(wait);
        if (cnxn.sendThread.isAlive()) return false;
        cnxn.eventThread.join(wait);
        if (cnxn.eventThread.isAlive()) return false;
        return true;
    }

    /**
     * Returns the address to which the socket is connected. Useful for testing
     * against an ensemble - test client may need to know which server
     * to shutdown if interested in verifying that the code handles
     * disconnection/reconnection correctly.
     * THIS METHOD IS EXPECTED TO BE USED FOR TESTING ONLY!!!
     *
     * @since 3.3.0
     * 
     * @return ip address of the remote side of the connection or null if
     *         not connected
     */
    protected SocketAddress testableRemoteSocketAddress() {
        return cnxn.sendThread.getClientCnxnSocket().getRemoteSocketAddress();
    }

    /** 
     * Returns the local address to which the socket is bound.
     * THIS METHOD IS EXPECTED TO BE USED FOR TESTING ONLY!!!
     *
     * @since 3.3.0
     * 
     * @return ip address of the remote side of the connection or null if
     *         not connected
     */
    protected SocketAddress testableLocalSocketAddress() {
        return cnxn.sendThread.getClientCnxnSocket().getLocalSocketAddress();
    }

    private static ClientCnxnSocket getClientCnxnSocket() throws IOException {
        String clientCnxnSocketName = System
                .getProperty(ZOOKEEPER_CLIENT_CNXN_SOCKET);
        if (clientCnxnSocketName == null) {
            clientCnxnSocketName = ClientCnxnSocketNIO.class.getName();
        }
        try {
            return (ClientCnxnSocket) Class.forName(clientCnxnSocketName).getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            IOException ioe = new IOException("Couldn't instantiate "
                    + clientCnxnSocketName);
            ioe.initCause(e);
            throw ioe;
        }
    }
}
