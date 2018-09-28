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

import java.util.Set;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.server.util.BitHashSet;

public class WatcherOrBitSet {

    private Set<Watcher> watchers;
    private BitHashSet watcherBits;

    public WatcherOrBitSet(final Set<Watcher> watchers) {
        this.watchers = watchers;
    }

    public WatcherOrBitSet(final BitHashSet watcherBits) {
        this.watcherBits = watcherBits;
    }

    public boolean contains(Watcher watcher) {
        if (watchers == null) {
            return false;
        }
        return watchers.contains(watcher);
    }

    public boolean contains(int watcherBit) {
        if (watcherBits == null) {
            return false;
        }
        return watcherBits.contains(watcherBit);
    }

    public int size() {
        if (watchers != null) {
            return watchers.size();
        }
        if (watcherBits != null) {
            return watcherBits.size();
        }
        return 0;
    }
}
