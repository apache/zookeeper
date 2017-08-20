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
import java.util.Iterator;
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

    enum Type {
        STANDARD() {
            @Override
            boolean isPersistent() {
                return false;
            }

            @Override
            boolean isRecursive() {
                return false;
            }
        },
        PERSISTENT() {
            @Override
            boolean isPersistent() {
                return true;
            }

            @Override
            boolean isRecursive() {
                return false;
            }
        },
        PERSISTENT_RECURSIVE() {
            @Override
            boolean isPersistent() {
                return true;
            }

            @Override
            boolean isRecursive() {
                return true;
            }
        }
        ;

        abstract boolean isPersistent();
        abstract boolean isRecursive();
    }

    private final Map<String, Map<Watcher, Type>> watchTable =
        new HashMap<>();

    private final Map<Watcher, Set<String>> watch2Paths =
        new HashMap<>();

    private int recursiveWatchQty = 0;    // guarded by sync

    // visible for testing
    synchronized int getRecursiveWatchQty() {
        return recursiveWatchQty;
    }

    synchronized int size(){
        int result = 0;
        for(Map<Watcher, Type> watches : watchTable.values()) {
            result += watches.size();
        }
        return result;
    }

    synchronized void addWatch(String path, Watcher watcher, WatchManager.Type type) {
        Map<Watcher, Type> list = watchTable.get(path);
        if (list == null) {
            // don't waste memory if there are few watches on a node
            // rehash when the 4th entry is added, doubling size thereafter
            // seems like a good compromise
            list = new HashMap<>(4);
            watchTable.put(path, list);
        }
        Type previousType = list.put(watcher, type);
        if ((previousType != null) && previousType.isRecursive()) {
            --recursiveWatchQty;
        }
        if (type.isRecursive()) {
            ++recursiveWatchQty;
        }

        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null) {
            // cnxns typically have many watches, so use default cap here
            paths = new HashSet<>();
            watch2Paths.put(watcher, paths);
        }
        paths.add(path);
    }

    synchronized void removeWatcher(Watcher watcher) {
        Set<String> paths = watch2Paths.remove(watcher);
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            Map<Watcher, Type> list = watchTable.get(p);
            if (list != null) {
                Type removedType = list.remove(watcher);
                if (removedType.isRecursive()) {
                    --recursiveWatchQty;
                }
                if (list.isEmpty()) {
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
        Set<Watcher> watchers = new HashSet<>();
        synchronized (this) {
            PathParentIterator pathParentIterator = getPathParentIterator(path);
            for (String localPath : pathParentIterator.asIterable()) {
                Map<Watcher, Type> thisWatchers = watchTable.get(localPath);
                if (thisWatchers == null || thisWatchers.isEmpty()) {
                    continue;
                }
                Iterator<Entry<Watcher, Type>> iterator = thisWatchers.entrySet().iterator();
                while (iterator.hasNext()) {
                    Entry<Watcher, Type> entry = iterator.next();
                    Type entryType = entry.getValue();
                    Watcher watcher = entry.getKey();
                    if (entryType.isRecursive()) {
                        if ( type != EventType.NodeChildrenChanged ) {
                            watchers.add(watcher);
                        }
                    } else if (!pathParentIterator.atParentPath()) {
                        watchers.add(watcher);
                        if (!entryType.isPersistent()) {
                            iterator.remove();
                            Set<String> paths = watch2Paths.get(watcher);
                            if (paths != null) {
                                paths.remove(localPath);
                            }
                        }
                    }
                }
                if (thisWatchers.isEmpty()) {
                    watchTable.remove(localPath);
                }
            }
        }
        if (watchers.isEmpty()) {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG,
                        ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                        "No watchers for " + path   );
            }
            return null;
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
        for (Set<String> paths : watch2Paths.values()) {
            total += paths.size();
        }
        sb.append("Total watches:").append(total);

        return sb.toString();
    }

    /**
     * String representation of watches. Warning, may be large!
     * @param byPath iff true output watches by paths, otw output
     * watches by connection
     */
    synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        if (byPath) {
            for (Entry<String, Map<Watcher, Type>> e : watchTable.entrySet()) {
                pwriter.println(e.getKey());
                for (Watcher w : e.getValue().keySet()) {
                    pwriter.print("\t0x");
                    pwriter.print(Long.toHexString(((ServerCnxn)w).getSessionId()));
                    pwriter.print("\n");
                }
            }
        } else {
            for (Entry<Watcher, Set<String>> e : watch2Paths.entrySet()) {
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
        PathParentIterator pathParentIterator = getPathParentIterator(path);
        for (String localPath : pathParentIterator.asIterable()) {
            Map<Watcher, Type> watchers = watchTable.get(localPath);
            Type watcherType = (watchers != null) ? watchers.get(watcher) : null;
            if ( !pathParentIterator.atParentPath() ) {
                if ( watcherType != null ) {
                    return true;    // at the leaf node, all watcher types match
                }
            }
            if (watcherType == Type.PERSISTENT_RECURSIVE) {
                return true;
            }
        }
        return false;
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
        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null || !paths.remove(path)) {
            return false;
        }

        Map<Watcher, Type> list = watchTable.get(path);
        Type removedType = (list != null) ? list.remove(watcher) : null;
        if (removedType == null) {
            return false;
        }
        if (removedType.isRecursive()) {
            --recursiveWatchQty;
        }

        if (list.isEmpty()) {
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
        Map<Long, Set<String>> id2paths = new HashMap<>();
        for (Entry<Watcher, Set<String>> e: watch2Paths.entrySet()) {
            Long id = ((ServerCnxn) e.getKey()).getSessionId();
            HashSet<String> paths = new HashSet<>(e.getValue());
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
        Map<String, Set<Long>> path2ids = new HashMap<>();
        for (Entry<String, Map<Watcher, Type>> e : watchTable.entrySet()) {
            Set<Long> ids = new HashSet<>(e.getValue().size());
            path2ids.put(e.getKey(), ids);
            for (Watcher watcher : e.getValue().keySet()) {
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
        for (Set<String> paths : watch2Paths.values()) {
            totalWatches += paths.size();
        }
        return new WatchesSummary (watch2Paths.size(), watchTable.size(),
                                   totalWatches);
    }

    private PathParentIterator getPathParentIterator(String path) {
        if (recursiveWatchQty == 0) {
            return PathParentIterator.forPathOnly(path);
        }
        return PathParentIterator.forAll(path);
    }

    private String getParent(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }
}
