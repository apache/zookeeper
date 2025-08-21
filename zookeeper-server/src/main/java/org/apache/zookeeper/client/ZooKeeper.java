/*
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

package org.apache.zookeeper.client;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.apache.zookeeper.AddWatchMode;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.Transaction;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.ClientInfo;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.EphemeralType;

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
 */
public interface ZooKeeper extends AutoCloseable {
    /**
     * Creates a builder with given connect string and session timeout.
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
     *            session timeout
     */
    static ZooKeeperBuilder builder(String connectString, Duration sessionTimeout) {
        return new ZooKeeperBuilder(connectString, sessionTimeout);
    }

    /**
     * The session id for this ZooKeeper client instance. The value returned is
     * not valid until the client connects to a server and may change after a
     * re-connect.
     *
     * <p>This method is NOT thread safe
     *
     * @return current session id
     */
    long getSessionId();

    /**
     * The session password for this ZooKeeper client instance. The value
     * returned is not valid until the client connects to a server and may
     * change after a re-connect.
     *
     * <p>This method is NOT thread safe
     *
     * @return current session password
     */
    byte[] getSessionPasswd();

    /**
     * The negotiated session timeout for this ZooKeeper client instance. The
     * value returned is not valid until the client connects to a server and
     * may change after a re-connect.
     *
     * This method is NOT thread safe
     *
     * @return current session timeout
     */
    int getSessionTimeout();

    /**
     * Client config.
     */
    ZKClientConfig getClientConfig();

    /**
     * Add the specified scheme:auth information to this connection.
     *
     * @param scheme auth scheme
     * @param auth auth data
     */
    void addAuthInfo(String scheme, byte[] auth);

    /**
     * Specify the default watcher for the connection (overrides the one
     * specified during construction).
     */
    void register(Watcher watcher);

    /**
     * This function allows a client to update the connection string by providing
     * a new comma separated list of host:port pairs, each corresponding to a
     * ZooKeeper server.
     * <p>
     * The function invokes a <a href="https://issues.apache.org/jira/browse/ZOOKEEPER-1355">
     * probabilistic load-balancing algorithm</a> which may cause the client to disconnect from
     * its current host with the goal to achieve expected uniform number of connections per server
     * in the new list. In case the current host to which the client is connected is not in the new
     * list this call will always cause the connection to be dropped. Otherwise, the decision
     * is based on whether the number of servers has increased or decreased and by how much.
     * For example, if the previous connection string contained 3 hosts and now the list contains
     * these 3 hosts and 2 more hosts, 40% of clients connected to each of the 3 hosts will
     * move to one of the new hosts in order to balance the load. The algorithm will disconnect
     * from the current host with probability 0.4 and in this case cause the client to connect
     * to one of the 2 new hosts, chosen at random.
     * <p>
     * If the connection is dropped, the client moves to a special mode "reconfigMode" where he chooses
     * a new server to connect to using the probabilistic algorithm. After finding a server,
     * or exhausting all servers in the new list after trying all of them and failing to connect,
     * the client moves back to the normal mode of operation where it will pick an arbitrary server
     * from the connectString and attempt to connect to it. If establishment of
     * the connection fails, another server in the connect string will be tried
     * (the order is non-deterministic, as we random shuffle the list), until a
     * connection is established. The client will continue attempts until the
     * session is explicitly closed (or the session is expired by the server).
     * @param connectString
     *            comma separated host:port pairs, each corresponding to a zk
     *            server. e.g. "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002"
     *            If the optional chroot suffix is used the example would look
     *            like: "127.0.0.1:3000,127.0.0.1:3001,127.0.0.1:3002/app/a"
     *            where the client would be rooted at "/app/a" and all paths
     *            would be relative to this root - ie getting/setting/etc...
     *            "/foo/bar" would result in operations being run on
     *            "/app/a/foo/bar" (from the server perspective).
     *
     * @throws IOException in cases of network failure
     */
    void updateServerList(String connectString) throws IOException;

    /**
     * Close this client object. Once the client is closed, its session becomes
     * invalid. All the ephemeral nodes in the ZooKeeper server associated with
     * the session will be removed. The watches left on those nodes (and on
     * their parents) will be triggered.
     * <p>
     * Added in 3.5.3: <a href="https://docs.oracle.com/javase/tutorial/essential/exceptions/tryResourceClose.html">try-with-resources</a>
     * may be used instead of calling close directly.
     * </p>
     * <p>
     * This method does not wait for all internal threads to exit.
     * Use the {@link #close(int) } method to wait for all resources to be released
     * </p>
     *
     * @throws InterruptedException
     */
    void close() throws InterruptedException;

    /**
     * Close this client object as the {@link #close() } method.
     * This method will wait for internal resources to be released.
     *
     * @param waitForShutdownTimeoutMs timeout (in milliseconds) to wait for resources to be released.
     * Use zero or a negative value to skip the wait
     * @throws InterruptedException
     * @return true if waitForShutdownTimeout is greater than zero and all of the resources have been released
     *
     * @since 3.5.4
     */
    boolean close(int waitForShutdownTimeoutMs) throws InterruptedException;

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
     * Arrays larger than this will cause a KeeperException to be thrown.
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
    String create(
            String path,
            byte[] data,
            List<ACL> acl,
            CreateMode createMode) throws KeeperException, InterruptedException;

    /**
     * Create a node with the given path and returns the Stat of that node. The
     * node data will be the given data and node acl will be the given acl.
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
     * Arrays larger than this will cause a KeeperException to be thrown.
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
     * @param stat
     *                The output Stat object.
     * @return the actual path of the created node
     * @throws KeeperException if the server returns a non-zero error code
     * @throws KeeperException.InvalidACLException if the ACL is invalid, null, or empty
     * @throws InterruptedException if the transaction is interrupted
     * @throws IllegalArgumentException if an invalid path is specified
     */
    String create(
            String path,
            byte[] data,
            List<ACL> acl,
            CreateMode createMode,
            Stat stat) throws KeeperException, InterruptedException;

    /**
     * same as {@link #create(String, byte[], List, CreateMode, Stat)} but
     * allows for specifying a TTL when mode is {@link CreateMode#PERSISTENT_WITH_TTL}
     * or {@link CreateMode#PERSISTENT_SEQUENTIAL_WITH_TTL}. If the znode has not been modified
     * within the given TTL, it will be deleted once it has no children. The TTL unit is
     * milliseconds and must be greater than 0 and less than or equal to
     * {@link EphemeralType#maxValue()} for {@link EphemeralType#TTL}.
     */
    String create(
            String path,
            byte[] data,
            List<ACL> acl,
            CreateMode createMode,
            Stat stat,
            long ttl) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of create.
     *
     * @see #create(String, byte[], List, CreateMode)
     */
    void create(
            String path,
            byte[] data,
            List<ACL> acl,
            CreateMode createMode,
            AsyncCallback.StringCallback cb,
            Object ctx);

    /**
     * The asynchronous version of create.
     *
     * @see #create(String, byte[], List, CreateMode, Stat)
     */
    void create(
            String path,
            byte[] data,
            List<ACL> acl,
            CreateMode createMode,
            AsyncCallback.Create2Callback cb,
            Object ctx);

    /**
     * The asynchronous version of create with ttl.
     *
     * @see #create(String, byte[], List, CreateMode, Stat, long)
     */
    void create(
            String path,
            byte[] data,
            List<ACL> acl,
            CreateMode createMode,
            AsyncCallback.Create2Callback cb,
            Object ctx,
            long ttl);

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
    Stat exists(String path, Watcher watcher) throws KeeperException, InterruptedException;

    /**
     * Return the stat of the node of the given path. Return null if no such a
     * node exists.
     *
     * <p>If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
     * triggered by a successful operation that creates/delete the node or sets
     * the data on the node.
     *
     * @param path the node path
     * @param watch whether need to watch this node
     * @return the stat of the node of the given path; return null if no such a
     *         node exists.
     * @throws KeeperException If the server signals an error
     * @throws IllegalStateException if watch this node with a null default watcher
     * @throws InterruptedException If the server transaction is interrupted.
     */
    Stat exists(String path, boolean watch) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of exists.
     *
     * @see #exists(String, Watcher)
     */
    void exists(String path, Watcher watcher, AsyncCallback.StatCallback cb, Object ctx);

    /**
     * The asynchronous version of exists.
     *
     * @throws IllegalStateException if watch this node with a null default watcher
     *
     * @see #exists(String, boolean)
     */
    void exists(String path, boolean watch, AsyncCallback.StatCallback cb, Object ctx);

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
    void delete(String path, int version) throws InterruptedException, KeeperException;

    /**
     * The asynchronous version of delete.
     *
     * @see #delete(String, int)
     */
    void delete(String path, int version, AsyncCallback.VoidCallback cb, Object ctx);

    /**
     * Executes multiple ZooKeeper operations. In case of transactions all of them or none of them will be executed.
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
     * These should be created using the factory methods on {@link Op} and must be the same kind of ops.
     * @return A list of results, one for each input Op, the order of
     * which exactly matches the order of the <code>ops</code> input
     * operations.
     * @throws InterruptedException If the operation was interrupted.
     * The operation may or may not have succeeded, but will not have
     * partially succeeded if this exception is thrown.
     * @throws KeeperException If the operation could not be completed
     * due to some error in doing one of the specified ops.
     * @throws IllegalArgumentException if an invalid path is specified or different kind of ops are mixed
     *
     * @since 3.4.0
     */
    List<OpResult> multi(Iterable<Op> ops) throws InterruptedException, KeeperException;

    /**
     * The asynchronous version of multi.
     *
     * @see #multi(Iterable)
     */
    void multi(Iterable<Op> ops, AsyncCallback.MultiCallback cb, Object ctx);

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
    byte[] getData(String path, Watcher watcher, Stat stat) throws KeeperException, InterruptedException;

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
     * @throws IllegalStateException if watch this node with a null default watcher
     * @throws InterruptedException If the server transaction is interrupted.
     */
    byte[] getData(String path, boolean watch, Stat stat) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of getData.
     *
     * @see #getData(String, Watcher, Stat)
     */
    void getData(String path, Watcher watcher, AsyncCallback.DataCallback cb, Object ctx);

    /**
     * The asynchronous version of getData.
     *
     * @throws IllegalStateException if watch this node with a null default watcher
     *
     * @see #getData(String, boolean, Stat)
     */
    void getData(String path, boolean watch, AsyncCallback.DataCallback cb, Object ctx);

    /**
     * Return the last committed configuration (as known to the server to which the client is connected)
     * and the stat of the configuration.
     * <p>
     * If the watch is non-null and the call is successful (no exception is
     * thrown), a watch will be left on the configuration node (ZooDefs.CONFIG_NODE). The watch
     * will be triggered by a successful reconfig operation
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if the configuration node doesn't exists.
     *
     * @param watcher explicit watcher
     * @param stat the stat of the configuration node ZooDefs.CONFIG_NODE
     * @return configuration data stored in ZooDefs.CONFIG_NODE
     * @throws KeeperException If the server signals an error with a non-zero error code
     * @throws InterruptedException If the server transaction is interrupted.
     */
    byte[] getConfig(Watcher watcher, Stat stat) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of getConfig.
     *
     * @see #getConfig(Watcher, Stat)
     */
    void getConfig(Watcher watcher, AsyncCallback.DataCallback cb, Object ctx);

    /**
     * Return the last committed configuration (as known to the server to which the client is connected)
     * and the stat of the configuration.
     * <p>
     * If the watch is true and the call is successful (no exception is
     * thrown), a watch will be left on the configuration node (ZooDefs.CONFIG_NODE). The watch
     * will be triggered by a successful reconfig operation
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param watch whether need to watch this node
     * @param stat the stat of the configuration node ZooDefs.CONFIG_NODE
     * @return configuration data stored in ZooDefs.CONFIG_NODE
     * @throws KeeperException If the server signals an error with a non-zero error code
     * @throws IllegalStateException if watch this node with a null default watcher
     * @throws InterruptedException If the server transaction is interrupted.
     */
    byte[] getConfig(boolean watch, Stat stat) throws KeeperException, InterruptedException;

    /**
     * The Asynchronous version of getConfig.
     *
     * @throws IllegalStateException if watch this node with a null default watcher
     *
     * @see #getData(String, boolean, Stat)
     */
    void getConfig(boolean watch, AsyncCallback.DataCallback cb, Object ctx);

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
    Stat setData(String path, byte[] data, int version) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of setData.
     *
     * @see #setData(String, byte[], int)
     */
    void setData(String path, byte[] data, int version, AsyncCallback.StatCallback cb, Object ctx);

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
    List<ACL> getACL(String path, Stat stat) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of getACL.
     *
     * @see #getACL(String, Stat)
     */
    void getACL(String path, Stat stat, AsyncCallback.ACLCallback cb, Object ctx);

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
     * @throws org.apache.zookeeper.KeeperException.InvalidACLException If the acl is invalid.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    Stat setACL(String path, List<ACL> acl, int aclVersion) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of setACL.
     *
     * @see #setACL(String, List, int)
     */
    void setACL(String path, List<ACL> acl, int version, AsyncCallback.StatCallback cb, Object ctx);

    /**
     * Return the list of the children of the node of the given path.
     * <p>
     * If the watch is non-null and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
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
    List<String> getChildren(String path, Watcher watcher) throws KeeperException, InterruptedException;

    /**
     * Return the list of the children of the node of the given path.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
     * triggered by a successful operation that deletes the node of the given
     * path or creates/delete a child under the node.
     * <p>
     * The list of children returned is not sorted and no guarantee is provided
     * as to its natural or lexical order.
     * <p>
     * A KeeperException with error code KeeperException.NoNode will be thrown
     * if no node with the given path exists.
     *
     * @param path the node path
     * @param watch whether need to watch this node
     * @return an unordered array of children of the node with the given path
     * @throws IllegalStateException if watch this node with a null default watcher
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     */
    List<String> getChildren(String path, boolean watch) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of getChildren.
     *
     * @see #getChildren(String, Watcher)
     */
    void getChildren(String path, Watcher watcher, AsyncCallback.ChildrenCallback cb, Object ctx);

    /**
     * The asynchronous version of getChildren.
     *
     * @throws IllegalStateException if watch this node with a null default watcher
     *
     * @see #getChildren(String, boolean)
     */
    void getChildren(String path, boolean watch, AsyncCallback.ChildrenCallback cb, Object ctx);

    /**
     * For the given znode path return the stat and children list.
     * <p>
     * If the watch is non-null and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
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
    List<String> getChildren(
            String path,
            Watcher watcher,
            Stat stat) throws KeeperException, InterruptedException;

    /**
     * For the given znode path return the stat and children list.
     * <p>
     * If the watch is true and the call is successful (no exception is thrown),
     * a watch will be left on the node with the given path. The watch will be
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
     * @param path the node path
     * @param watch whether need to watch this node
     * @param stat stat of the znode designated by path
     * @return an unordered array of children of the node with the given path
     * @throws IllegalStateException if watch this node with a null default watcher
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero
     *  error code.
     */
    List<String> getChildren(
            String path,
            boolean watch,
            Stat stat) throws KeeperException, InterruptedException;

    /**
     * The asynchronous version of getChildren.
     *
     * @since 3.3.0
     *
     * @see #getChildren(String, Watcher, Stat)
     */
    void getChildren(String path, Watcher watcher, AsyncCallback.Children2Callback cb, Object ctx);

    /**
     * The asynchronous version of getChildren.
     *
     * @since 3.3.0
     *
     * @throws IllegalStateException if watch this node with a null default watcher
     *
     * @see #getChildren(String, boolean, Stat)
     */
    void getChildren(String path, boolean watch, AsyncCallback.Children2Callback cb, Object ctx);

    /**
     * Synchronously gets all numbers of children nodes under a specific path
     *
     * @since 3.6.0
     * @param path
     * @return Children nodes count under path
     * @throws KeeperException
     * @throws InterruptedException
     */
    int getAllChildrenNumber(String path) throws KeeperException, InterruptedException;

    /**
     * Asynchronously gets all numbers of children nodes under a specific path
     *
     * @since 3.6.0
     * @param path
     */
    void getAllChildrenNumber(String path, AsyncCallback.AllChildrenNumberCallback cb, Object ctx);

    /**
     * Synchronously gets all the ephemeral nodes  created by this session.
     *
     * @since 3.6.0
     *
     */
    List<String> getEphemerals() throws KeeperException, InterruptedException;

    /**
     * Synchronously gets all the ephemeral nodes matching prefixPath
     * created by this session.  If prefixPath is "/" then it returns all
     * ephemerals
     *
     * @since 3.6.0
     *
     */
    List<String> getEphemerals(String prefixPath) throws KeeperException, InterruptedException;

    /**
     * Asynchronously gets all the ephemeral nodes matching prefixPath
     * created by this session.  If prefixPath is "/" then it returns all
     * ephemerals
     *
     * @since 3.6.0
     *
     */
    void getEphemerals(String prefixPath, AsyncCallback.EphemeralsCallback cb, Object ctx);

    /**
     * Asynchronously gets all the ephemeral nodes created by this session.
     * ephemerals
     *
     * @since 3.6.0
     *
     */
    void getEphemerals(AsyncCallback.EphemeralsCallback cb, Object ctx);

    /**
     * Synchronous sync. Flushes channel between process and leader.
     *
     * @param path the given path
     * @throws KeeperException If the server signals an error with a non-zero error code
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws IllegalArgumentException if an invalid path is specified
     */
    void sync(String path) throws KeeperException, InterruptedException;

    /**
     * Asynchronous sync. Flushes channel between process and leader.
     * @param path
     * @param cb a handler for the callback
     * @param ctx context to be provided to the callback
     * @throws IllegalArgumentException if an invalid path is specified
     */
    void sync(String path, AsyncCallback.VoidCallback cb, Object ctx);

    /**
     * For the given znode path, removes the specified watcher of given
     * watcherType.
     *
     * <p>
     * Watcher shouldn't be null. A successful call guarantees that, the
     * removed watcher won't be triggered.
     * </p>
     *
     * @param path
     *            - the path of the node
     * @param watcher
     *            - a concrete watcher
     * @param watcherType
     *            - the type of watcher to be removed
     * @param local
     *            - whether the watcher can be removed locally when there is no
     *            server connection
     * @throws InterruptedException
     *             if the server transaction is interrupted.
     * @throws KeeperException.NoWatcherException
     *             if no watcher exists that match the specified parameters
     * @throws KeeperException
     *             if the server signals an error with a non-zero error code.
     * @throws IllegalArgumentException
     *             if any of the following is true:
     *             <ul>
     *             <li> {@code path} is invalid
     *             <li> {@code watcher} is null
     *             </ul>
     *
     * @since 3.5.0
     */
    void removeWatches(
            String path,
            Watcher watcher,
            Watcher.WatcherType watcherType,
            boolean local) throws InterruptedException, KeeperException;

    /**
     * The asynchronous version of removeWatches.
     *
     * @see #removeWatches
     */
    void removeWatches(
            String path,
            Watcher watcher,
            Watcher.WatcherType watcherType,
            boolean local,
            AsyncCallback.VoidCallback cb,
            Object ctx);

    /**
     * For the given znode path, removes all the registered watchers of given
     * watcherType.
     *
     * <p>
     * A successful call guarantees that, the removed watchers won't be
     * triggered.
     * </p>
     *
     * @param path
     *            - the path of the node
     * @param watcherType
     *            - the type of watcher to be removed
     * @param local
     *            - whether watches can be removed locally when there is no
     *            server connection
     * @throws InterruptedException
     *             if the server transaction is interrupted.
     * @throws KeeperException.NoWatcherException
     *             if no watcher exists that match the specified parameters
     * @throws KeeperException
     *             if the server signals an error with a non-zero error code.
     * @throws IllegalArgumentException
     *             if an invalid {@code path} is specified
     *
     * @since 3.5.0
     */
    void removeAllWatches(
            String path,
            Watcher.WatcherType watcherType,
            boolean local) throws InterruptedException, KeeperException;

    /**
     * The asynchronous version of removeAllWatches.
     *
     * @see #removeAllWatches
     */
    void removeAllWatches(String path, Watcher.WatcherType watcherType, boolean local, AsyncCallback.VoidCallback cb, Object ctx);

    /**
     * Add a watch to the given znode using the given mode. Note: not all
     * watch types can be set with this method. Only the modes available
     * in {@link AddWatchMode} can be set with this method.
     *
     * @param basePath the path that the watcher applies to
     * @param watcher the watcher
     * @param mode type of watcher to add
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero
     *  error code.
     * @since 3.6.0
     */
    void addWatch(String basePath, Watcher watcher, AddWatchMode mode)
            throws KeeperException, InterruptedException;

    /**
     * Add a watch to the given znode using the given mode. Note: not all
     * watch types can be set with this method. Only the modes available
     * in {@link AddWatchMode} can be set with this method. In this version of the method,
     * the default watcher is used
     *
     * @param basePath the path that the watcher applies to
     * @param mode type of watcher to add
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero
     *  error code.
     * @since 3.6.0
     */
    void addWatch(
            String basePath,
            AddWatchMode mode
    ) throws KeeperException, InterruptedException;

    /**
     * Async version of {@link #addWatch(String, Watcher, AddWatchMode)} (see it for details)
     *
     * @param basePath the path that the watcher applies to
     * @param watcher the watcher
     * @param mode type of watcher to add
     * @param cb a handler for the callback
     * @param ctx context to be provided to the callback
     * @throws IllegalArgumentException if an invalid path is specified
     * @since 3.6.0
     */
    void addWatch(String basePath, Watcher watcher, AddWatchMode mode, AsyncCallback.VoidCallback cb, Object ctx);

    /**
     * Async version of {@link #addWatch(String, AddWatchMode)} (see it for details)
     *
     * @param basePath the path that the watcher applies to
     * @param mode type of watcher to add
     * @param cb a handler for the callback
     * @param ctx context to be provided to the callback
     * @throws IllegalArgumentException if an invalid path is specified
     * @since 3.6.0
     */
    void addWatch(String basePath, AddWatchMode mode, AsyncCallback.VoidCallback cb, Object ctx);

    /**
     * A Transaction is a thin wrapper on the {@link #multi} method
     * which provides a builder object that can be used to construct
     * and commit an atomic set of operations.
     *
     * @since 3.4.0
     *
     * @return a Transaction builder object
     */
    Transaction transaction();

    /**
     * Gives all authentication information added into the current session.
     *
     * @return list of authentication info
     * @throws InterruptedException when interrupted
     */
    List<ClientInfo> whoAmI() throws InterruptedException;
}
