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

import java.util.List;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * This is the main class for ZooKeeperAdmin client library.
 * This library is used to perform cluster administration tasks,
 * such as reconfigure cluster membership. The ZooKeeperAdmin class
 * inherits ZooKeeper and has similar usage pattern as ZooKeeper class.
 * Please check {@link org.apache.zookeeper.ZooKeeper} class document for more details.
 *
 * @since 3.5.3
 */
public interface ZooKeeperAdmin extends ZooKeeper {
    /**
     * Reconfigure - add/remove servers. Return the new configuration.
     * @param joiningServers
     *                a comma separated list of servers being added (incremental reconfiguration)
     * @param leavingServers
     *                a comma separated list of servers being removed (incremental reconfiguration)
     * @param newMembers
     *                a comma separated list of new membership (non-incremental reconfiguration)
     * @param fromConfig
     *                version of the current configuration
     *                (optional - causes reconfiguration to throw an exception if configuration is no longer current)
     * @param stat the stat of /zookeeper/config znode will be copied to this
     *             parameter if not null.
     * @return new configuration
     * @throws InterruptedException If the server transaction is interrupted.
     * @throws KeeperException If the server signals an error with a non-zero error code.
     */
    byte[] reconfigure(
            String joiningServers,
            String leavingServers,
            String newMembers,
            long fromConfig,
            Stat stat) throws KeeperException, InterruptedException;

    /**
     * Convenience wrapper around reconfig that takes Lists of strings instead of comma-separated servers.
     *
     * @see #reconfigure
     *
     */
    byte[] reconfigure(
            List<String> joiningServers,
            List<String> leavingServers,
            List<String> newMembers,
            long fromConfig,
            Stat stat) throws KeeperException, InterruptedException;

    /**
     * The Asynchronous version of reconfig.
     *
     * @see #reconfigure
     *
     **/
    void reconfigure(
            String joiningServers,
            String leavingServers,
            String newMembers,
            long fromConfig,
            AsyncCallback.DataCallback cb,
            Object ctx);

    /**
     * Convenience wrapper around asynchronous reconfig that takes Lists of strings instead of comma-separated servers.
     *
     * @see #reconfigure
     *
     */
    void reconfigure(
            List<String> joiningServers,
            List<String> leavingServers,
            List<String> newMembers,
            long fromConfig,
            AsyncCallback.DataCallback cb,
            Object ctx);
}
