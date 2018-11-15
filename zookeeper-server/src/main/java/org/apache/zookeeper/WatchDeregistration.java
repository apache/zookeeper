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

import java.util.Map;
import java.util.Set;

import org.apache.zookeeper.Watcher.WatcherType;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.ZooKeeper.ZKWatchManager;

/**
 * Handles the special case of removing watches which has registered for a
 * client path
 */
public class WatchDeregistration {

    private final String clientPath;
    private final Watcher watcher;
    private final WatcherType watcherType;
    private final boolean local;
    private final ZKWatchManager zkManager;

    public WatchDeregistration(String clientPath, Watcher watcher,
            WatcherType watcherType, boolean local, ZKWatchManager zkManager) {
        this.clientPath = clientPath;
        this.watcher = watcher;
        this.watcherType = watcherType;
        this.local = local;
        this.zkManager = zkManager;
    }

    /**
     * Unregistering watcher that was added on path.
     * 
     * @param rc
     *            the result code of the operation that attempted to remove
     *            watch on the path.
     */
    public Map<EventType, Set<Watcher>> unregister(int rc)
            throws KeeperException {
        return zkManager.removeWatcher(clientPath, watcher, watcherType, local,
                rc);
    }

    /**
     * Returns client path which has specified for unregistering its watcher
     * 
     * @return client path
     */
    public String getClientPath() {
        return clientPath;
    }
}
