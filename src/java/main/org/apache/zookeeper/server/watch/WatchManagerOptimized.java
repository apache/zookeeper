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
import java.util.HashMap;
import java.util.BitSet;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.Iterator;
import java.lang.Iterable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.util.BitMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimized in memory and time complexity, compared to WatchManager, both the
 * memory consumption and time complexity improved a lot, but it cannot
 * efficiently remove the watcher when the session or socket is closed, for
 * majority usecase this is not a problem.
 *
 * Changed made compared to WatchManager:
 *
 * - Use HashSet and BitSet to store the watchers to find a balance between
 *   memory usage and time complexity
 * - Use ReadWriteLock instead of synchronized to reduce lock retention
 * - Lazily clean up the closed watchers
 */
public class WatchManagerOptimized
        implements IWatchManager, DeadWatcherListener {

    private static final Logger LOG =
            LoggerFactory.getLogger(WatchManagerOptimized.class);

    private final ConcurrentHashMap<String, BitHashSet> pathWatches =
            new ConcurrentHashMap<String, BitHashSet>();

    // watcher to bit id mapping
    private final BitMap<Watcher> watcherBitIdMap = new BitMap<Watcher>();

    // used to lazily remove the dead watchers
    private final WatcherCleaner watcherCleaner;

    private final ReentrantReadWriteLock addRemovePathRWLock = new ReentrantReadWriteLock();

    public WatchManagerOptimized() {
        watcherCleaner = new WatcherCleaner(this);
        watcherCleaner.start();
    }

    @Override
    public boolean addWatch(String path, Watcher watcher) {
        boolean result = false;
        addRemovePathRWLock.readLock().lock();
        try {
            // avoid race condition of adding a on flying dead watcher
            if (isDeadWatcher(watcher)) {
                LOG.debug("Ignoring addWatch with closed cnxn");
            } else {
                Integer bit = watcherBitIdMap.add(watcher);
                BitHashSet watchers = pathWatches.get(path);
                if (watchers == null) {
                    watchers = new BitHashSet();
                    BitHashSet existingWatchers = pathWatches.putIfAbsent(path, watchers);
                    if (existingWatchers != null) {
                        watchers = existingWatchers;
                    }
                }
                result = watchers.add(bit);
            }
        } finally {
            addRemovePathRWLock.readLock().unlock();
        }
        return result;
    }

    @Override
    public boolean containsWatcher(String path, Watcher watcher) {
        BitHashSet watchers = pathWatches.get(path);
        if (watchers == null || !watchers.contains(watcherBitIdMap.getBit(watcher))) {
            return false;
        }
        return true;
    }

    @Override
    public boolean removeWatcher(String path, Watcher watcher) {
        addRemovePathRWLock.writeLock().lock();
        try {
            BitHashSet list = pathWatches.get(path);
            if (list == null || !list.remove(watcherBitIdMap.getBit(watcher))) {
                return false;
            }
            if (list.isEmpty()) {
                pathWatches.remove(path);
            }
            return true;
        } finally {
            addRemovePathRWLock.writeLock().unlock();
        }
    }

    @Override
    public void removeWatcher(Watcher watcher) {
        Integer watcherBit;
        addRemovePathRWLock.writeLock().lock();
        try {
            // do nothing if the watcher is not tracked
            watcherBit = watcherBitIdMap.getBit(watcher);
            if (watcherBit == null) {
                return;
            }
        } finally {
            addRemovePathRWLock.writeLock().unlock();
        }
        watcherCleaner.addDeadWatcher(watcherBit);
    }

    /**
     * Entry for WatcherCleaner to remove dead watchers
     *
     * @param deadWatchers the watchers need to be removed
     */
    @Override
    public void processDeadWatchers(Set<Integer> deadWatchers) {
        BitSet bits = new BitSet();
        for (int dw: deadWatchers) {
            bits.set(dw);
        }
        // The value iterator will reflect the state when it was
        // created, don't need to synchronize.
        for (BitHashSet watchers: pathWatches.values()) {
            watchers.remove(deadWatchers, bits);
        }
        // Better to remove the empty path from pathWatches, but it will add
        // lot of lock contention and affect the throughput of addWatch,
        // let's rely on the triggerWatch to delete it.
        for (Integer wbit: deadWatchers) {
            watcherBitIdMap.remove(wbit);
        }
    }

    @Override
    public WatcherOrBitSet triggerWatch(String path, EventType type) {
        return triggerWatch(path, type, null);
    }

    @Override
    public WatcherOrBitSet triggerWatch(
            String path, EventType type, WatcherOrBitSet supress) {
        WatchedEvent e = new WatchedEvent(type, KeeperState.SyncConnected, path);

        BitHashSet watchers = remove(path);
        if (watchers == null) {
            return null;
        }

        int triggeredWatches = 0;

        // Avoid race condition between dead watcher cleaner in
        // WatcherCleaner and iterating here
        synchronized (watchers) {
            for (Integer wBit : watchers) {
                if (supress != null && supress.contains(wBit)) {
                    continue;
                }

                Watcher w = watcherBitIdMap.get(wBit);

                // skip dead watcher
                if (w == null || isDeadWatcher(w)) {
                    continue;
                }

                w.process(e);
                triggeredWatches++;
            }
        }

        return new WatcherOrBitSet(watchers);
    }

    @Override
    public int size() {
        int size = 0;
        for(BitHashSet watches : pathWatches.values()) {
            size += watches.size();
        }
        return size;
    }

    @Override
    public void shutdown() {
        if (watcherCleaner != null) {
            watcherCleaner.shutdown();
        }
    }

    private BitHashSet remove(String path) {
        addRemovePathRWLock.writeLock().lock();
        try {
            return pathWatches.remove(path);
        } finally {
            addRemovePathRWLock.writeLock().unlock();
        }
    }

    boolean isDeadWatcher(Watcher watcher) {
        return watcher instanceof ServerCnxn && ((ServerCnxn) watcher).isStale();
    }

    int pathSize() {
        return pathWatches.size();
    }

    @Override
    public WatchesSummary getWatchesSummary() {
        return new WatchesSummary(
                watcherBitIdMap.size(), pathSize(), size());
    }

    @Override
    public WatchesReport getWatches() {
        Map<Long, Set<String>> id2paths = new HashMap<Long, Set<String>>();
        for (Entry<Watcher, Set<String>> e: getWatcher2PathesMap().entrySet()) {
            Long id = ((ServerCnxn) e.getKey()).getSessionId();
            Set<String> paths = new HashSet<String>(e.getValue());
            id2paths.put(id, paths);
        }
        return new WatchesReport(id2paths);
    }

    /**
     * Iterate through ConcurrentHashMap is 'safe', it will reflect the state
     * of the map at the time iteration began, may miss update while iterating,
     * given this is used in the commands to get a general idea of the watches
     * state, we don't care about missing some update.
     */
    @Override
    public WatchesPathReport getWatchesByPath() {
        Map<String, Set<Long>> path2ids = new HashMap<String, Set<Long>>();
        for (Entry<String, BitHashSet> e : pathWatches.entrySet()) {
            BitHashSet watchers = e.getValue();
            synchronized (watchers) {
                Set<Long> ids = new HashSet<Long>(watchers.size());
                path2ids.put(e.getKey(), ids);
                for (Integer wbit : watchers) {
                    Watcher watcher = watcherBitIdMap.get(wbit);
                    if (watcher instanceof ServerCnxn) {
                        ids.add(((ServerCnxn) watcher).getSessionId());
                    }
                }
            }
        }
        return new WatchesPathReport(path2ids);
    }

    /**
     * May cause OOM if there are lots of watches, might better to forbid
     * it in this class.
     */
    public Map<Watcher, Set<String>> getWatcher2PathesMap() {
        Map<Watcher, Set<String>> watcher2paths =
                new HashMap<Watcher, Set<String>>();
        for (Entry<String, BitHashSet> e : pathWatches.entrySet()) {
            String path = e.getKey();
            BitHashSet watchers = e.getValue();
            // avoid race condition with add/remove
            synchronized (watchers) {
                for (Integer wbit: watchers) {
                    Watcher w = watcherBitIdMap.get(wbit);
                    if (w == null) {
                        continue;
                    }
                    if (!watcher2paths.containsKey(w)) {
                        watcher2paths.put(w, new HashSet<String>());
                    }
                    watcher2paths.get(w).add(path);
                }
            }
        }
        return watcher2paths;
    }

    @Override
    public void dumpWatches(PrintWriter pwriter, boolean byPath) {
        if (byPath) {
            for (Entry<String, BitHashSet> e : pathWatches.entrySet()) {
                pwriter.println(e.getKey());
                BitHashSet watchers = e.getValue();
                synchronized (watchers) {
                    for (Integer wbit : watchers) {
                        Watcher w = watcherBitIdMap.get(wbit);
                        if (!(w instanceof ServerCnxn)) {
                            continue;
                        }
                        pwriter.print("\t0x");
                        pwriter.print(
                                Long.toHexString(((ServerCnxn)w).getSessionId()));
                        pwriter.print("\n");
                    }
                }
            }
        } else {
            for (Entry<Watcher, Set<String>> e : getWatcher2PathesMap().entrySet()) {
                pwriter.print("0x");
                pwriter.println(Long.toHexString(((ServerCnxn)e.getKey()).getSessionId()));
                for (String path : e.getValue()) {
                    pwriter.print("\t");
                    pwriter.println(path);
                }
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(watcherBitIdMap.size())
            .append(" connections watching ")
            .append(pathSize()).append(" paths\n");
        sb.append("Total watches:").append(size());
        return sb.toString();
    }
}
