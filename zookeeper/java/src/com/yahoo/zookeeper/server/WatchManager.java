/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.zookeeper.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.proto.WatcherEvent;

/**
 * This class manages watches. It allows watches to be associated with a string
 * and removes watchers and their watches in addition to managing triggers.
 */
public class WatchManager {
    private static final Logger LOG = Logger.getLogger(WatchManager.class);

    private HashMap<String, HashSet<Watcher>> watchTable = 
        new HashMap<String, HashSet<Watcher>>();

    private HashMap<Watcher, HashSet<String>> watch2Paths = 
        new HashMap<Watcher, HashSet<String>>();

    synchronized int size(){
        return watchTable.size();
    }

    synchronized void addWatch(String path, Watcher watcher) {
        HashSet<Watcher> list = watchTable.get(path);
        if (list == null) {
            list = new HashSet<Watcher>();
            watchTable.put(path, list);
        }
        list.add(watcher);

        HashSet<String> paths = watch2Paths.get(watcher);
        if (paths == null) {
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

    Set<Watcher> triggerWatch(String path, int type) {
        return triggerWatch(path, type, null);
    }
    
    Set<Watcher> triggerWatch(String path, int type, Set<Watcher> supress) {
        WatcherEvent e = new WatcherEvent(type,
                Watcher.Event.KeeperStateSyncConnected, path);
        HashSet<Watcher> watchers;
        synchronized (this) {
            watchers = watchTable.remove(path);
            if (watchers == null || watchers.isEmpty()) {
                ZooTrace.logTraceMessage(LOG,
                        ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                        "No watchers for " + path);
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
}
