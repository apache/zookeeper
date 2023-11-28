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

package org.apache.zookeeper.server.watch;

import java.io.PrintWriter;
import java.util.List;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.data.ACL;

public interface IWatchManager {

    /**
     * Add watch to specific path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     *
     * @return true if the watcher added is not already present
     */
    boolean addWatch(String path, Watcher watcher);

    /**
     * Add watch to specific path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     * @param watcherMode the watcher mode to use
     *
     * @return true if the watcher added is not already present
     */
    default boolean addWatch(String path, Watcher watcher, WatcherMode watcherMode) {
        if (watcherMode == WatcherMode.DEFAULT_WATCHER_MODE) {
            return addWatch(path, watcher);
        }
        throw new UnsupportedOperationException();  // custom implementations must defeat this
    }

    /**
     * Checks the specified watcher exists for the given path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     *
     * @return true if the watcher exists, false otherwise
     */
    boolean containsWatcher(String path, Watcher watcher);

    /**
     * Removes the specified watcher for the given path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     *
     * @return true if the watcher successfully removed, false otherwise
     */
    boolean removeWatcher(String path, Watcher watcher);

    /**
     * The entry to remove the watcher when the cnxn is closed.
     *
     * @param watcher watcher object reference
     */
    void removeWatcher(Watcher watcher);

    /**
     * Distribute the watch event for the given path.
     *
     * @param path znode path
     * @param type the watch event type
     * @param acl ACL of the znode in path
     *
     * @return the watchers have been notified
     */
    WatcherOrBitSet triggerWatch(String path, EventType type, List<ACL> acl);

    /**
     * Distribute the watch event for the given path, but ignore those
     * suppressed ones.
     *
     * @param path znode path
     * @param type the watch event type
     * @param suppress the suppressed watcher set
     *
     * @return the watchers have been notified
     */
    WatcherOrBitSet triggerWatch(String path, EventType type, List<ACL> acl, WatcherOrBitSet suppress);

    /**
     * Get the size of watchers.
     *
     * @return the watchers number managed in this class.
     */
    int size();

    /**
     * Clean up the watch manager.
     */
    void shutdown();

    /**
     * Returns a watch summary.
     *
     * @return watch summary
     * @see WatchesSummary
     */
    WatchesSummary getWatchesSummary();

    /**
     * Returns a watch report.
     *
     * @return watch report
     * @see WatchesReport
     */
    WatchesReport getWatches();

    /**
     * Returns a watch report by path.
     *
     * @return watch report
     * @see WatchesPathReport
     */
    WatchesPathReport getWatchesByPath();

    /**
     * String representation of watches. Warning, may be large!
     *
     * @param pwriter the writer to dump the watches
     * @param byPath iff true output watches by paths, otw output
     * watches by connection
     *
     */
    void dumpWatches(PrintWriter pwriter, boolean byPath);

    /**
     * Return the current number of recursive watchers
     *
     * @return qty
     */
    default int getRecursiveWatchQty() {
        return 0;
    }
}
