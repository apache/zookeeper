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

package org.apache.zookeeper.server.watch;

import java.io.PrintWriter;

import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;

public interface IWatchManager {

    /**
     * Add watch to specific path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     *
     * @return true if the watcher added is not already present
     */
    public boolean addWatch(String path, Watcher watcher);

    /**
     * Checks the specified watcher exists for the given path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     *
     * @return true if the watcher exists, false otherwise
     */
    public boolean containsWatcher(String path, Watcher watcher);

    /**
     * Removes the specified watcher for the given path.
     *
     * @param path znode path
     * @param watcher watcher object reference
     *
     * @return true if the watcher successfully removed, false otherwise
     */
    public boolean removeWatcher(String path, Watcher watcher);

    /**
     * The entry to remove the watcher when the cnxn is closed.
     *
     * @param watcher watcher object reference
     */
    public void removeWatcher(Watcher watcher);

    /**
     * Distribute the watch event for the given path.
     *
     * @param path znode path
     * @param type the watch event type
     *
     * @return the watchers have been notified
     */
    public WatcherOrBitSet triggerWatch(String path, EventType type);

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
    public WatcherOrBitSet triggerWatch(
            String path, EventType type, WatcherOrBitSet suppress);

    /**
     * Get the size of watchers.
     *
     * @return the watchers number managed in this class.
     */
    public int size();

    /**
     * Clean up the watch manager.
     */
    public void shutdown();

    /**
     * Returns a watch summary.
     *
     * @return watch summary
     * @see WatchesSummary
     */
    public WatchesSummary getWatchesSummary();

    /**
     * Returns a watch report.
     *
     * @return watch report
     * @see WatchesReport
     */
    public WatchesReport getWatches();

    /**
     * Returns a watch report by path.
     *
     * @return watch report
     * @see WatchesPathReport
     */
    public WatchesPathReport getWatchesByPath();

    /**
     * String representation of watches. Warning, may be large!
     *
     * @param pwriter the writer to dump the watches
     * @param byPath iff true output watches by paths, otw output
     * watches by connection
     *
     * @return string representation of watches
     */
    public void dumpWatches(PrintWriter pwriter, boolean byPath);
}
