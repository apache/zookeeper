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

/**
 * Statistics for multiple different watches on one node.
 */
public final class WatchStats {
    private static final WatchStats[] WATCH_STATS = new WatchStats[] {
            new WatchStats(0), // NONE
            new WatchStats(1), // STANDARD
            new WatchStats(2), // PERSISTENT
            new WatchStats(3), // STANDARD + PERSISTENT
            new WatchStats(4), // PERSISTENT_RECURSIVE
            new WatchStats(5), // STANDARD + PERSISTENT_RECURSIVE
            new WatchStats(6), // PERSISTENT + PERSISTENT_RECURSIVE
            new WatchStats(7), // STANDARD + PERSISTENT + PERSISTENT_RECURSIVE
    };

    /**
     * Stats that have no watchers attached.
     *
     * <p>This could be used as start point to compute new stats using {@link #addMode(WatcherMode)}.
     */
    public static final WatchStats NONE = WATCH_STATS[0];

    private final int flags;

    private WatchStats(int flags) {
        this.flags = flags;
    }

    private static int modeToFlag(WatcherMode mode) {
        return 1 << mode.ordinal();
    }

    /**
     * Compute stats after given mode attached to node.
     *
     * @param mode watcher mode
     * @return a new stats if given mode is not attached to this node before, otherwise old stats
     */
    public WatchStats addMode(WatcherMode mode) {
        int flags = this.flags | modeToFlag(mode);
        return WATCH_STATS[flags];
    }

    /**
     * Compute stats after given mode removed from node.
     *
     * @param mode watcher mode
     * @return null if given mode is the last attached mode, otherwise a new stats
     */
    public WatchStats removeMode(WatcherMode mode) {
        int mask = ~modeToFlag(mode);
        int flags = this.flags & mask;
        if (flags == 0) {
            return NONE;
        }
        return WATCH_STATS[flags];
    }

    /**
     * Check whether given mode is attached to this node.
     *
     * @param mode watcher mode
     * @return true if given mode is attached to this node.
     */
    public boolean hasMode(WatcherMode mode) {
        int flags = modeToFlag(mode);
        return (this.flags & flags) != 0;
    }
}
