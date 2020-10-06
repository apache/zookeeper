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

package org.apache.zookeeper;

import java.util.List;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

/**
 * Interface definitions of asynchronous callbacks.
 *
 * <p>ZooKeeper provides asynchronous version as equivalent to synchronous APIs.
 *
 * <p>An asynchronous callback is deferred to invoke after a function returns.
 * Asynchronous calls usually improve system efficiency on IO-related APIs.
 *
 * <p>It is highly recommended NOT to perform any blocking operation inside
 * the callbacks. If you block the thread the ZooKeeper client won't process
 * other events.
 */
@InterfaceAudience.Public
public interface AsyncCallback {

    /**
     * This callback is used to retrieve the stat of the node.
     */
    @InterfaceAudience.Public
    interface StatCallback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}.
         *
         * <p>On failure, rc is set to the corresponding failure code in {@link KeeperException}.
         * <ul>
         *  <li> {@link KeeperException.Code#NONODE}
         *              - The node on given path doesn't exist for some API calls.</li>
         *  <li> {@link KeeperException.Code#BADVERSION}
         *              - The given version doesn't match the node's version for some API calls.</li>
         * </ul>
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         * @param stat {@link Stat} object of the node on given path.
         *
         * @see ZooKeeper#exists(String, boolean, AsyncCallback.StatCallback, Object)
         * @see ZooKeeper#exists(String, Watcher, AsyncCallback.StatCallback, Object)
         * @see ZooKeeper#setData(String, byte[], int, AsyncCallback.StatCallback, Object)
         * @see ZooKeeper#setACL(String, List, int, AsyncCallback.StatCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, Stat stat);

    }

    /**
     * This callback is used to get all children node number of the node.
     *
     * @since 3.6.0
     */
    @InterfaceAudience.Public
    interface AllChildrenNumberCallback extends AsyncCallback {

        /**
         * @param rc      The return code or the result of the call.
         * @param ctx     Whatever context object that we passed to asynchronous calls.
         * @param number  The number of children nodes under a specific path.
         *
         * @see ZooKeeper#getAllChildrenNumber(String, AsyncCallback.AllChildrenNumberCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, int number);

    }

    /**
     * This callback is used to retrieve the data and stat of the node.
     */
    @InterfaceAudience.Public
    interface DataCallback extends AsyncCallback {

        /**
         * Process the result of asynchronous calls.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}.
         *
         * <p>On failure, rc is set to the corresponding failure code in {@link KeeperException}.
         * <ul>
         *  <li>{@link KeeperException.Code#NONODE}
         *             - The node on given path doesn't exist for some API calls.</li>
         * </ul>
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         * @param data The data of the node.
         * @param stat {@link Stat} object of the node on given path.
         *
         * @see ZooKeeper#getData(String, boolean, AsyncCallback.DataCallback, Object)
         * @see ZooKeeper#getData(String, Watcher, AsyncCallback.DataCallback, Object)
         * @see ZooKeeper#getConfig(boolean, AsyncCallback.DataCallback, Object)
         * @see ZooKeeper#getConfig(Watcher, AsyncCallback.DataCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, byte[] data, Stat stat);

    }

    /**
     * This callback is used to retrieve the ACL and stat of the node.
     */
    @InterfaceAudience.Public
    interface ACLCallback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}.
         *
         * <p>On failure, rc is set to the corresponding failure code in {@link KeeperException}.
         * <ul>
         *  <li>{@link KeeperException.Code#NONODE}
         *             - The node on given path doesn't exist for some API calls.</li>
         * </ul>
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         * @param acl  ACL Id in {@link ZooDefs.Ids}.
         * @param stat {@link Stat} object of the node on given path.
         *
         * @see ZooKeeper#getACL(String, Stat, AsyncCallback.ACLCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, List<ACL> acl, Stat stat);

    }

    /**
     * This callback is used to retrieve the children of the node.
     */
    @InterfaceAudience.Public
    interface ChildrenCallback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}.
         *
         * <p>On failure, rc is set to the corresponding failure code in {@link KeeperException}.
         * <ul>
         *  <li>{@link KeeperException.Code#NONODE}
         *             - The node on given path doesn't exist for some API calls.</li>
         * </ul>
         *
         * @param rc       The return code or the result of the call.
         * @param path     The path that we passed to asynchronous calls.
         * @param ctx      Whatever context object that we passed to asynchronous calls.
         * @param children An unordered array of children of the node on given path.
         *
         * @see ZooKeeper#getChildren(String, boolean, AsyncCallback.ChildrenCallback, Object)
         * @see ZooKeeper#getChildren(String, Watcher, AsyncCallback.ChildrenCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, List<String> children);

    }

    /**
     * This callback is used to retrieve the children and stat of the node.
     */
    @InterfaceAudience.Public
    interface Children2Callback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * @param rc       The return code or the result of the call.
         * @param path     The path that we passed to asynchronous calls.
         * @param ctx      Whatever context object that we passed to asynchronous calls.
         * @param children An unordered array of children of the node on given path.
         * @param stat     {@link Stat} object of the node on given path.
         *
         * @see ChildrenCallback
         * @see ZooKeeper#getChildren(String, boolean, AsyncCallback.Children2Callback, Object)
         * @see ZooKeeper#getChildren(String, Watcher, AsyncCallback.Children2Callback, Object)
         */
        void processResult(int rc, String path, Object ctx, List<String> children, Stat stat);

    }

    /**
     * This callback is used to retrieve the name and stat of the node.
     */
    @InterfaceAudience.Public
    interface Create2Callback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         * @param name The name of the Znode that was created. On success, <i>name</i>
         *             and <i>path</i> are usually equal, unless a sequential node has
         *             been created.
         * @param stat {@link Stat} object of the node on given path.
         *
         * @see StringCallback
         * @see ZooKeeper#create(String, byte[], List, CreateMode, AsyncCallback.Create2Callback, Object)
         * @see ZooKeeper#create(String, byte[], List, CreateMode, AsyncCallback.Create2Callback, Object, long)
         */
        void processResult(int rc, String path, Object ctx, String name, Stat stat);

    }

    /**
     * This callback is used to retrieve the name of the node.
     */
    @InterfaceAudience.Public
    interface StringCallback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}.
         *
         * <p>On failure, rc is set to the corresponding failure code in {@link KeeperException}.
         * <ul>
         *  <li>{@link KeeperException.Code#NODEEXISTS}
         *             - The node on give path already exists for some API calls.</li>
         *  <li>{@link KeeperException.Code#NONODE}
         *             - The node on given path doesn't exist for some API calls.</li>
         *  <li>{@link KeeperException.Code#NOCHILDRENFOREPHEMERALS}
         *             - An ephemeral node cannot have children. There is discussion in
         *             community. It might be changed in the future.</li>
         * </ul>
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         * @param name The name of the znode that was created. On success, <i>name</i>
         *             and <i>path</i> are usually equal, unless a sequential node has
         *             been created.
         *
         * @see ZooKeeper#create(String, byte[], List, CreateMode, AsyncCallback.StringCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, String name);

    }

    /**
     * This callback doesn't retrieve anything from the node. It is useful for some APIs
     * that doesn't want anything sent back, e.g. {@link ZooKeeper#sync(String, AsyncCallback.VoidCallback, Object)}.
     */
    @InterfaceAudience.Public
    interface VoidCallback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}.
         *
         * <p>On failure, rc is set to the corresponding failure code in {@link KeeperException}.
         * <ul>
         *  <li>{@link KeeperException.Code#NONODE}
         *             - The node on given path doesn't exist for some API calls.</li>
         *  <li>{@link KeeperException.Code#BADVERSION}
         *             - The given version doesn't match the node's version for some API calls.</li>
         *  <li>{@link KeeperException.Code#NOTEMPTY}
         *             - the node has children and some API calls cannot succeed, e.g.
         *             {@link ZooKeeper#delete(String, int, AsyncCallback.VoidCallback, Object)}.</li>
         * </ul>
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         *
         * @see ZooKeeper#delete(String, int, AsyncCallback.VoidCallback, Object)
         * @see ZooKeeper#removeAllWatches(String, Watcher.WatcherType, boolean, AsyncCallback.VoidCallback, Object)
         * @see ZooKeeper#removeWatches(String, Watcher, Watcher.WatcherType, boolean, AsyncCallback.VoidCallback, Object)
         * @see ZooKeeper#sync(String, AsyncCallback.VoidCallback, Object)
         *
         */
        void processResult(int rc, String path, Object ctx);

    }

    /**
     * This callback is used to process the multiple results from a single multi call.
     */
    @InterfaceAudience.Public
    interface MultiCallback extends AsyncCallback {

        /**
         * Process the result of the asynchronous call.
         *
         * <p>On success, rc is {@link KeeperException.Code#OK}. All {@code opResults} are
         * non-{@link OpResult.ErrorResult}.
         *
         * <p>On failure, rc is a failure code in {@link KeeperException.Code}. Either
         * {@code opResults} is null, or all {@code opResults} are {@link OpResult.ErrorResult}.
         * All operations will be rolled back even if operations before the failing one were
         * successful.
         *
         * @param rc   The return code or the result of the call.
         * @param path The path that we passed to asynchronous calls.
         * @param ctx  Whatever context object that we passed to asynchronous calls.
         * @param opResults The list of results. One result for each operation, and the order
         *                  matches that of input.
         *
         * @see ZooKeeper#multi(Iterable, AsyncCallback.MultiCallback, Object)
         */
        void processResult(int rc, String path, Object ctx, List<OpResult> opResults);

    }

    /**
     * This callback is used to process the getEphemerals results from a single getEphemerals call.
     *
     * @see ZooKeeper#getEphemerals(AsyncCallback.EphemeralsCallback, Object)
     * @see ZooKeeper#getEphemerals(String, AsyncCallback.EphemeralsCallback, Object)
     *
     * @since 3.6.0
     */
    interface EphemeralsCallback extends AsyncCallback {

        /**
         * @param rc      The return code or the result of the call.
         * @param ctx     Whatever context object that we passed to asynchronous calls.
         * @param paths   The path that we passed to asynchronous calls.
         */
        void processResult(int rc, Object ctx, List<String> paths);

    }

}
