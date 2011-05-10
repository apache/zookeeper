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
namespace ZooKeeperNet
{
    using System;
    using System.Collections.Generic;
    using Org.Apache.Zookeeper.Data;

    public interface IZooKeeper
    {
        /// <summary>
        /// Unique ID representing the instance of the client
        /// </summary>
        Guid Id { get; set; }

        /// <summary>
        /// The session id for this ZooKeeper client instance. The value returned is
        /// not valid until the client connects to a server and may change after a
        /// re-connect.
        /// </summary>
        /// <value>The session id.</value>
        long SessionId { get; }

        /// <summary>
        /// The session password for this ZooKeeper client instance. The value
        /// returned is not valid until the client connects to a server and may
        ///  change after a re-connect.
        ///
        /// This method is NOT thread safe
        /// </summary>
        /// <value>The sesion password.</value>
        byte[] SesionPassword { get; }

        /// <summary>
        /// The negotiated session timeout for this ZooKeeper client instance. The
        /// value returned is not valid until the client connects to a server and
        /// may change after a re-connect.
        /// 
        /// This method is NOT thread safe
        /// </summary>
        /// <value>The session timeout.</value>
        TimeSpan SessionTimeout { get; }

        ZooKeeper.States State { get; }

        /// <summary>
        /// Add the specified scheme:auth information to this connection.
        ///
        /// This method is NOT thread safe
        /// </summary>
        /// <param name="scheme">The scheme.</param>
        /// <param name="auth">The auth.</param>
        void AddAuthInfo(string scheme, byte[] auth);

        /// <summary>
        /// Specify the default watcher for the connection (overrides the one
        /// specified during construction).
        /// </summary>
        /// <param name="watcher">The watcher.</param>
        void Register(IWatcher watcher);

        /// <summary>
        /// Create a node with the given path. The node data will be the given data,
        /// and node acl will be the given acl.
        /// 
        /// The flags argument specifies whether the created node will be ephemeral
        /// or not.
        /// 
        /// An ephemeral node will be removed by the ZooKeeper automatically when the
        /// session associated with the creation of the node expires.
        /// 
        /// The flags argument can also specify to create a sequential node. The
        /// actual path name of a sequential node will be the given path plus a
        /// suffix "i" where i is the current sequential number of the node. The sequence
        /// number is always fixed length of 10 digits, 0 padded. Once
        /// such a node is created, the sequential number will be incremented by one.
        /// 
        /// If a node with the same actual path already exists in the ZooKeeper, a
        /// KeeperException with error code KeeperException.NodeExists will be
        /// thrown. Note that since a different actual path is used for each
        /// invocation of creating sequential node with the same path argument, the
        /// call will never throw "file exists" KeeperException.
        /// 
        /// If the parent node does not exist in the ZooKeeper, a KeeperException
        /// with error code KeeperException.NoNode will be thrown.
        /// 
        /// An ephemeral node cannot have children. If the parent node of the given
        /// path is ephemeral, a KeeperException with error code
        /// KeeperException.NoChildrenForEphemerals will be thrown.
        /// 
        /// This operation, if successful, will trigger all the watches left on the
        /// node of the given path by exists and getData API calls, and the watches
        /// left on the parent node by getChildren API calls.
        /// 
        /// If a node is created successfully, the ZooKeeper server will trigger the
        /// watches on the path left by exists calls, and the watches on the parent
        /// of the node by getChildren calls.
        /// 
        /// The maximum allowable size of the data array is 1 MB (1,048,576 bytes).
        /// Arrays larger than this will cause a KeeperExecption to be thrown.
        /// </summary>
        /// <param name="path">The path for the node.</param>
        /// <param name="data">The data for the node.</param>
        /// <param name="acl">The acl for the node.</param>
        /// <param name="createMode">specifying whether the node to be created is ephemeral and/or sequential.</param>
        /// <returns></returns>
        string Create(string path, byte[] data, List<ACL> acl, CreateMode createMode);

        /// <summary>
        /// Delete the node with the given path. The call will succeed if such a node
        /// exists, and the given version matches the node's version (if the given
        /// version is -1, it matches any node's versions).
        ///
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if the nodes does not exist.
        ///
        /// A KeeperException with error code KeeperException.BadVersion will be
        /// thrown if the given version does not match the node's version.
        ///
        /// A KeeperException with error code KeeperException.NotEmpty will be thrown
        /// if the node has children.
        /// 
        /// This operation, if successful, will trigger all the watches on the node
        /// of the given path left by exists API calls, and the watches on the parent
        /// node left by getChildren API calls.
        /// </summary>
        /// <param name="path">The path.</param>
        /// <param name="version">The version.</param>
        void Delete(string path, int version);

        /// <summary>
        /// Return the stat of the node of the given path. Return null if no such a
        /// node exists.
        /// 
        /// If the watch is non-null and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch will be
        /// triggered by a successful operation that creates/delete the node or sets
        /// the data on the node.
        /// </summary>
        /// <param name="path">The path.</param>
        /// <param name="watcher">The watcher.</param>
        /// <returns>the stat of the node of the given path; return null if no such a node exists.</returns>
        Stat Exists(string path, IWatcher watcher);

        /// <summary>
        /// Return the stat of the node of the given path. Return null if no such a
        /// node exists.
        /// 
        /// If the watch is true and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch will be
        /// triggered by a successful operation that creates/delete the node or sets
        /// the data on the node.
        /// @param path
        ///                the node path
        /// @param watch
        ///                whether need to watch this node
        /// @return the stat of the node of the given path; return null if no such a
        ///         node exists.
        /// @throws KeeperException If the server signals an error
        /// @throws InterruptedException If the server transaction is interrupted.
        /// </summary>
        Stat Exists(string path, bool watch);

        /// <summary>
        /// Return the data and the stat of the node of the given path.
        /// 
        /// If the watch is non-null and the call is successful (no exception is
        /// thrown), a watch will be left on the node with the given path. The watch
        /// will be triggered by a successful operation that sets data on the node, or
        /// deletes the node.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// @param path the given path
        /// @param watcher explicit watcher
        /// @param stat the stat of the node
        /// @return the data of the node
        /// @throws KeeperException If the server signals an error with a non-zero error code
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws IllegalArgumentException if an invalid path is specified
        /// </summary>
        byte[] GetData(string path, IWatcher watcher, Stat stat);

        /// <summary>
        /// Return the data and the stat of the node of the given path.
        /// 
        /// If the watch is true and the call is successful (no exception is
        /// thrown), a watch will be left on the node with the given path. The watch
        /// will be triggered by a successful operation that sets data on the node, or
        /// deletes the node.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// @param path the given path
        /// @param watch whether need to watch this node
        /// @param stat the stat of the node
        /// @return the data of the node
        /// @throws KeeperException If the server signals an error with a non-zero error code
        /// @throws InterruptedException If the server transaction is interrupted.
        /// </summary>
        byte[] GetData(string path, bool watch, Stat stat);

        /// <summary>
        /// Set the data for the node of the given path if such a node exists and the
        /// given version matches the version of the node (if the given version is
        /// -1, it matches any node's versions). Return the stat of the node.
        /// 
        /// This operation, if successful, will trigger all the watches on the node
        /// of the given path left by getData calls.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// 
        /// A KeeperException with error code KeeperException.BadVersion will be
        /// thrown if the given version does not match the node's version.
        ///
        /// The maximum allowable size of the data array is 1 MB (1,048,576 bytes).
        /// Arrays larger than this will cause a KeeperExecption to be thrown.
        /// @param path
        ///                the path of the node
        /// @param data
        ///                the data to set
        /// @param version
        ///                the expected matching version
        /// @return the state of the node
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws KeeperException If the server signals an error with a non-zero error code.
        /// @throws IllegalArgumentException if an invalid path is specified
        /// </summary>
        Stat SetData(string path, byte[] data, int version);

        /// <summary>
        /// Return the ACL and stat of the node of the given path.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// @param path
        ///                the given path for the node
        /// @param stat
        ///                the stat of the node will be copied to this parameter.
        /// @return the ACL array of the given node.
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws KeeperException If the server signals an error with a non-zero error code.
        /// @throws IllegalArgumentException if an invalid path is specified
        /// </summary>
        List<ACL> GetACL(string path, Stat stat);

        /// <summary>
        /// Set the ACL for the node of the given path if such a node exists and the
        /// given version matches the version of the node. Return the stat of the
        /// node.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// 
        /// A KeeperException with error code KeeperException.BadVersion will be
        /// thrown if the given version does not match the node's version.
        /// @param path
        /// @param acl
        /// @param version
        /// @return the stat of the node.
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws KeeperException If the server signals an error with a non-zero error code.
        /// @throws org.apache.zookeeper.KeeperException.InvalidACLException If the acl is invalide.
        /// @throws IllegalArgumentException if an invalid path is specified
        /// </summary>
        Stat SetACL(string path, List<ACL> acl, int version);

        /// <summary>
        /// Return the list of the children of the node of the given path.
        /// 
        /// If the watch is non-null and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch willbe
        /// triggered by a successful operation that deletes the node of the given
        /// path or creates/delete a child under the node.
        /// 
        /// The list of children returned is not sorted and no guarantee is provided
        /// as to its natural or lexical order.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// @param path
        /// @param watcher explicit watcher
        /// @return an unordered array of children of the node with the given path
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws KeeperException If the server signals an error with a non-zero error code.
        /// @throws IllegalArgumentException if an invalid path is specified
        /// </summary>
        List<string> GetChildren(string path, IWatcher watcher);

        List<string> GetChildren(string path, bool watch);

        /// <summary>
        /// For the given znode path return the stat and children list.
        /// 
        /// If the watch is non-null and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch willbe
        /// triggered by a successful operation that deletes the node of the given
        /// path or creates/delete a child under the node.
        /// 
        /// The list of children returned is not sorted and no guarantee is provided
        /// as to its natural or lexical order.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// @since 3.3.0
        /// 
        /// @param path
        /// @param watcher explicit watcher
        /// @param stat stat of the znode designated by path
        /// @return an unordered array of children of the node with the given path
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws KeeperException If the server signals an error with a non-zero error code.
        /// @throws IllegalArgumentException if an invalid path is specified
        /// </summary>
        List<string> GetChildren(string path, IWatcher watcher, Stat stat);

        /// <summary>
        /// For the given znode path return the stat and children list.
        /// 
        /// If the watch is true and the call is successful (no exception is thrown),
        /// a watch will be left on the node with the given path. The watch willbe
        /// triggered by a successful operation that deletes the node of the given
        /// path or creates/delete a child under the node.
        /// 
        /// The list of children returned is not sorted and no guarantee is provided
        /// as to its natural or lexical order.
        /// 
        /// A KeeperException with error code KeeperException.NoNode will be thrown
        /// if no node with the given path exists.
        /// @since 3.3.0
        /// 
        /// @param path
        /// @param watch
        /// @param stat stat of the znode designated by path
        /// @return an unordered array of children of the node with the given path
        /// @throws InterruptedException If the server transaction is interrupted.
        /// @throws KeeperException If the server signals an error with a non-zero
        ///  error code.
        /// </summary>
        List<string> GetChildren(string path, bool watch, Stat stat);

        /// <summary>
        /// Close this client object. Once the client is closed, its session becomes
        /// invalid. All the ephemeral nodes in the ZooKeeper server associated with
        /// the session will be removed. The watches left on those nodes (and on
        /// their parents) will be triggered.
        /// </summary>   
        void Dispose();

        /// <summary>
        /// string representation of this ZooKeeper client. Suitable for things
        /// like logging.
        /// 
        /// Do NOT count on the format of this string, it may change without
        /// warning.
        /// 
        /// @since 3.3.0
        /// </summary>
        string ToString();
    }
}
