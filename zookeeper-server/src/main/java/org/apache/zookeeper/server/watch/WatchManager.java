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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ServerWatcher;
import org.apache.zookeeper.server.ZooTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages watches. It allows watches to be associated with a string
 * and removes watchers and their watches in addition to managing triggers.
 */
public class WatchManager implements IWatchManager {

    private static final Logger LOG = LoggerFactory.getLogger(WatchManager.class);

    private final Map<String, Set<Watcher>> watchTable = new HashMap<>();

    private final Map<Watcher, Map<String, WatchStats>> watch2Paths = new HashMap<>();

    private int recursiveWatchQty = 0;

    @Override
    public synchronized int size() {
        int result = 0;
        for (Set<Watcher> watches : watchTable.values()) {
            result += watches.size();
        }
        return result;
    }

    private boolean isDeadWatcher(Watcher watcher) {
        return watcher instanceof ServerCnxn && ((ServerCnxn) watcher).isStale();
    }

    @Override
    public boolean addWatch(String path, Watcher watcher) {
        return addWatch(path, watcher, WatcherMode.DEFAULT_WATCHER_MODE);
    }

    @Override
    public synchronized boolean addWatch(String path, Watcher watcher, WatcherMode watcherMode) {
        if (isDeadWatcher(watcher)) {
            LOG.debug("Ignoring addWatch with closed cnxn");
            return false;
        }

        Set<Watcher> list = watchTable.get(path);
        if (list == null) {
            // don't waste memory if there are few watches on a node
            // rehash when the 4th entry is added, doubling size thereafter
            // seems like a good compromise
            list = new HashSet<>(4);
            watchTable.put(path, list);
        }
        list.add(watcher);

        Map<String, WatchStats> paths = watch2Paths.get(watcher);
        if (paths == null) {
            // cnxns typically have many watches, so use default cap here
            paths = new HashMap<>();
            watch2Paths.put(watcher, paths);
        }

        WatchStats stats = paths.getOrDefault(path, WatchStats.NONE);
        WatchStats newStats = stats.addMode(watcherMode);

        if (newStats != stats) {
            paths.put(path, newStats);
            if (watcherMode.isRecursive()) {
                ++recursiveWatchQty;
            }
            return true;
        }

        return false;
    }

    @Override
    public synchronized void removeWatcher(Watcher watcher) {
        Map<String, WatchStats> paths = watch2Paths.remove(watcher);
        if (paths == null) {
            return;
        }
        for (String p : paths.keySet()) {
            Set<Watcher> list = watchTable.get(p);
            if (list != null) {
                list.remove(watcher);
                if (list.isEmpty()) {
                    watchTable.remove(p);
                }
            }
        }
        for (WatchStats stats : paths.values()) {
            if (stats.hasMode(WatcherMode.PERSISTENT_RECURSIVE)) {
                --recursiveWatchQty;
            }
        }
    }

    @Override
    public WatcherOrBitSet triggerWatch(String path, EventType type, long zxid, List<ACL> acl) {
        return triggerWatch(path, type, zxid, acl, null);
    }

    @Override
    public WatcherOrBitSet triggerWatch(String path, EventType type, long zxid, List<ACL> acl, WatcherOrBitSet supress) {
        WatchedEvent e = new WatchedEvent(type, KeeperState.SyncConnected, path, zxid);
        Set<Watcher> watchers = new HashSet<>();
        synchronized (this) {
            PathParentIterator pathParentIterator = getPathParentIterator(path);
            for (String localPath : pathParentIterator.asIterable()) {
                Set<Watcher> thisWatchers = watchTable.get(localPath);
                if (thisWatchers == null || thisWatchers.isEmpty()) {
                    continue;
                }
                Iterator<Watcher> iterator = thisWatchers.iterator();
                while (iterator.hasNext()) {
                    Watcher watcher = iterator.next();
                    Map<String, WatchStats> paths = watch2Paths.getOrDefault(watcher, Collections.emptyMap());
                    WatchStats stats = paths.get(localPath);
                    if (stats == null) {
                        LOG.warn("inconsistent watch table for watcher {}, {} not in path list", watcher, localPath);
                        continue;
                    }
                    if (!pathParentIterator.atParentPath()) {
                        watchers.add(watcher);
                        WatchStats newStats = stats.removeMode(WatcherMode.STANDARD);
                        if (newStats == WatchStats.NONE) {
                            iterator.remove();
                            paths.remove(localPath);
                        } else if (newStats != stats) {
                            paths.put(localPath, newStats);
                        }
                    } else if (stats.hasMode(WatcherMode.PERSISTENT_RECURSIVE)) {
                        watchers.add(watcher);
                    }
                }
                if (thisWatchers.isEmpty()) {
                    watchTable.remove(localPath);
                }
            }
        }
        if (watchers.isEmpty()) {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK, "No watchers for " + path);
            }
            return null;
        }

        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                continue;
            }
            if (w instanceof ServerWatcher) {
                ((ServerWatcher) w).process(e, acl);
            } else {
                w.process(e);
            }
        }

        switch (type) {
            case NodeCreated:
                ServerMetrics.getMetrics().NODE_CREATED_WATCHER.add(watchers.size());
                break;

            case NodeDeleted:
                ServerMetrics.getMetrics().NODE_DELETED_WATCHER.add(watchers.size());
                break;

            case NodeDataChanged:
                ServerMetrics.getMetrics().NODE_CHANGED_WATCHER.add(watchers.size());
                break;

            case NodeChildrenChanged:
                ServerMetrics.getMetrics().NODE_CHILDREN_WATCHER.add(watchers.size());
                break;
            default:
                // Other types not logged.
                break;
        }

        return new WatcherOrBitSet(watchers);
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(watch2Paths.size()).append(" connections watching ").append(watchTable.size()).append(" paths\n");

        int total = 0;
        for (Map<String, WatchStats> paths : watch2Paths.values()) {
            total += paths.size();
        }
        sb.append("Total watches:").append(total);

        return sb.toString();
    }

    @Override
    public synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        if (byPath) {
            for (Entry<String, Set<Watcher>> e : watchTable.entrySet()) {
                pwriter.println(e.getKey());
                for (Watcher w : e.getValue()) {
                    pwriter.print("\t0x");
                    pwriter.print(Long.toHexString(((ServerCnxn) w).getSessionId()));
                    pwriter.print("\n");
                }
            }
        } else {
            for (Entry<Watcher, Map<String, WatchStats>> e : watch2Paths.entrySet()) {
                pwriter.print("0x");
                pwriter.println(Long.toHexString(((ServerCnxn) e.getKey()).getSessionId()));
                for (String path : e.getValue().keySet()) {
                    pwriter.print("\t");
                    pwriter.println(path);
                }
            }
        }
    }

    @Override
    public synchronized boolean containsWatcher(String path, Watcher watcher) {
        return containsWatcher(path, watcher, null);
    }

    @Override
    public synchronized boolean containsWatcher(String path, Watcher watcher, WatcherMode watcherMode) {
        Map<String, WatchStats> paths = watch2Paths.get(watcher);
        if (paths == null) {
            return false;
        }
        WatchStats stats = paths.get(path);
        return stats != null && (watcherMode == null || stats.hasMode(watcherMode));
    }

    private WatchStats unwatch(String path, Watcher watcher, Map<String, WatchStats> paths, Set<Watcher> watchers) {
        WatchStats stats = paths.remove(path);
        if (stats == null) {
            return WatchStats.NONE;
        }
        if (paths.isEmpty()) {
            watch2Paths.remove(watcher);
        }
        watchers.remove(watcher);
        if (watchers.isEmpty()) {
            watchTable.remove(path);
        }
        return stats;
    }

    @Override
    public synchronized boolean removeWatcher(String path, Watcher watcher, WatcherMode watcherMode) {
        Map<String, WatchStats> paths = watch2Paths.get(watcher);
        Set<Watcher> watchers = watchTable.get(path);
        if (paths == null || watchers == null) {
            return false;
        }

        WatchStats oldStats;
        WatchStats newStats;
        if (watcherMode != null) {
            oldStats = paths.getOrDefault(path, WatchStats.NONE);
            newStats = oldStats.removeMode(watcherMode);
            if (newStats != WatchStats.NONE) {
                if (newStats != oldStats) {
                    paths.put(path, newStats);
                }
            } else if (oldStats != WatchStats.NONE) {
                unwatch(path, watcher, paths, watchers);
            }
        } else {
            oldStats = unwatch(path, watcher, paths, watchers);
            newStats = WatchStats.NONE;
        }

        if (oldStats.hasMode(WatcherMode.PERSISTENT_RECURSIVE) && !newStats.hasMode(WatcherMode.PERSISTENT_RECURSIVE)) {
            --recursiveWatchQty;
        }

        return oldStats != newStats;
    }

    @Override
    public synchronized boolean removeWatcher(String path, Watcher watcher) {
        return removeWatcher(path, watcher, null);
    }

    // VisibleForTesting
    Map<Watcher, Map<String, WatchStats>> getWatch2Paths() {
        return watch2Paths;
    }

    @Override
    public synchronized WatchesReport getWatches() {
        Map<Long, Set<String>> id2paths = new HashMap<>();
        for (Entry<Watcher, Map<String, WatchStats>> e : watch2Paths.entrySet()) {
            Long id = ((ServerCnxn) e.getKey()).getSessionId();
            Set<String> paths = new HashSet<>(e.getValue().keySet());
            id2paths.put(id, paths);
        }
        return new WatchesReport(id2paths);
    }

    @Override
    public synchronized WatchesPathReport getWatchesByPath() {
        Map<String, Set<Long>> path2ids = new HashMap<>();
        for (Entry<String, Set<Watcher>> e : watchTable.entrySet()) {
            Set<Long> ids = new HashSet<>(e.getValue().size());
            path2ids.put(e.getKey(), ids);
            for (Watcher watcher : e.getValue()) {
                ids.add(((ServerCnxn) watcher).getSessionId());
            }
        }
        return new WatchesPathReport(path2ids);
    }

    @Override
    public synchronized WatchesSummary getWatchesSummary() {
        int totalWatches = 0;
        for (Map<String, WatchStats> paths : watch2Paths.values()) {
            totalWatches += paths.size();
        }
        return new WatchesSummary(watch2Paths.size(), watchTable.size(), totalWatches);
    }

    @Override
    public void shutdown() { /* do nothing */ }

    // VisibleForTesting
    synchronized int getRecursiveWatchQty() {
        return recursiveWatchQty;
    }

    private PathParentIterator getPathParentIterator(String path) {
        if (getRecursiveWatchQty() == 0) {
            return PathParentIterator.forPathOnly(path);
        }
        return PathParentIterator.forAll(path);
    }
}
