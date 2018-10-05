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

package org.apache.zookeeper.server;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages watches. It allows watches to be associated with a string
 * and removes watchers and their watches in addition to managing triggers.
 */
class WatchManager {
    private static final Logger LOG = LoggerFactory.getLogger(WatchManager.class);

    private final HashMap<String, HashSet<Watcher>> watchTable =
        new HashMap<String, HashSet<Watcher>>();

    private final HashMap<Watcher, HashSet<String>> watch2Paths =
        new HashMap<Watcher, HashSet<String>>();

    synchronized int size(){
        int result = 0;
        for(Set<Watcher> watches : watchTable.values()) {
            result += watches.size();
        }
        return result;
    }

    synchronized void addWatch(String path, Watcher watcher) {
        HashSet<Watcher> list = watchTable.get(path);
        if (list == null) {
            // don't waste memory if there are few watches on a node
            // rehash when the 4th entry is added, doubling size thereafter
            // seems like a good compromise
            list = new HashSet<Watcher>(4);
            watchTable.put(path, list);
        }
        list.add(watcher);

        HashSet<String> paths = watch2Paths.get(watcher);
        if (paths == null) {
            // cnxns typically have many watches, so use default cap here
            paths = new HashSet<String>();
            watch2Paths.put(watcher, paths);
        }
        paths.add(path);
    }

    synchronized void removeWatcher(Watcher watcher) {
        HashSet<String> paths = watch2Paths.remove(watcher);
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            HashSet<Watcher> list = watchTable.get(p);
            if (list != null) {
                list.remove(watcher);
                if (list.size() == 0) {
                    watchTable.remove(p);
                }
            }
        }
    }

    Set<Watcher> triggerWatch(String path, EventType type) {
        return triggerWatch(path, type, null);
    }

    Set<Watcher> triggerWatch(String path, EventType type, Set<Watcher> supress) {
        WatchedEvent e = new WatchedEvent(type,
                KeeperState.SyncConnected, path);
        HashSet<Watcher> watchers;
        synchronized (this) {
            watchers = watchTable.remove(path);
            if (watchers == null || watchers.isEmpty()) {
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logTraceMessage(LOG,
                            ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                            "No watchers for " + path);
                }
                return null;
            }
            for (Watcher w : watchers) {
                HashSet<String> paths = watch2Paths.get(w);
                if (paths != null) {
                    paths.remove(path);
                }
            }
        }
        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                continue;
            }
            w.process(e);
        }
        return watchers;
    }

    /**
     * Brief description of this object.
     */
    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(watch2Paths.size()).append(" connections watching ")
            .append(watchTable.size()).append(" paths\n");

        int total = 0;
        for (HashSet<String> paths : watch2Paths.values()) {
            total += paths.size();
        }
        sb.append("Total watches:").append(total);

        return sb.toString();
    }

    /**
     * String representation of watches. Warning, may be large!
     * @param byPath iff true output watches by paths, otw output
     * watches by connection
     * @return string representation of watches
     */
    synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        if (byPath) {
            for (Entry<String, HashSet<Watcher>> e : watchTable.entrySet()) {
                pwriter.println(e.getKey());
                for (Watcher w : e.getValue()) {
                    pwriter.print("\t0x");
                    pwriter.print(Long.toHexString(((ServerCnxn)w).getSessionId()));
                    pwriter.print("\n");
                }
            }
        } else {
            for (Entry<Watcher, HashSet<String>> e : watch2Paths.entrySet()) {
                pwriter.print("0x");
                pwriter.println(Long.toHexString(((ServerCnxn)e.getKey()).getSessionId()));
                for (String path : e.getValue()) {
                    pwriter.print("\t");
                    pwriter.println(path);
                }
            }
        }
    }

    /**
     * Checks the specified watcher exists for the given path
     *
     * @param path
     *            znode path
     * @param watcher
     *            watcher object reference
     * @return true if the watcher exists, false otherwise
     */
    synchronized boolean containsWatcher(String path, Watcher watcher) {
        HashSet<String> paths = watch2Paths.get(watcher);
        if (paths == null || !paths.contains(path)) {
            return false;
        }
        return true;
    }

    /**
     * Removes the specified watcher for the given path
     *
     * @param path
     *            znode path
     * @param watcher
     *            watcher object reference
     * @return true if the watcher successfully removed, false otherwise
     */
    synchronized boolean removeWatcher(String path, Watcher watcher) {
        HashSet<String> paths = watch2Paths.get(watcher);
        if (paths == null || !paths.remove(path)) {
            return false;
        }

        HashSet<Watcher> list = watchTable.get(path);
        if (list == null || !list.remove(watcher)) {
            return false;
        }

        if (list.size() == 0) {
            watchTable.remove(path);
        }

        return true;
    }

    /**
     * Returns a watch report.
     *
     * @return watch report
     * @see WatchesReport
     */
    synchronized WatchesReport getWatches() {
        Map<Long, Set<String>> id2paths = new HashMap<Long, Set<String>>();
        for (Entry<Watcher, HashSet<String>> e: watch2Paths.entrySet()) {
            Long id = ((ServerCnxn) e.getKey()).getSessionId();
            HashSet<String> paths = new HashSet<String>(e.getValue());
            id2paths.put(id, paths);
        }
        return new WatchesReport(id2paths);
    }

    /**
     * Returns a watch report by path.
     *
     * @return watch report
     * @see WatchesPathReport
     */
    synchronized WatchesPathReport getWatchesByPath() {
        Map<String, Set<Long>> path2ids = new HashMap<String, Set<Long>>();
        for (Entry<String, HashSet<Watcher>> e : watchTable.entrySet()) {
            Set<Long> ids = new HashSet<Long>(e.getValue().size());
            path2ids.put(e.getKey(), ids);
            for (Watcher watcher : e.getValue()) {
                ids.add(((ServerCnxn) watcher).getSessionId());
            }
        }
        return new WatchesPathReport(path2ids);
    }

    /**
     * Returns a watch summary.
     *
     * @return watch summary
     * @see WatchesSummary
     */
    synchronized WatchesSummary getWatchesSummary() {
        int totalWatches = 0;
        for (HashSet<String> paths : watch2Paths.values()) {
            totalWatches += paths.size();
        }
        return new WatchesSummary (watch2Paths.size(), watchTable.size(),
                                   totalWatches);
    }
}
