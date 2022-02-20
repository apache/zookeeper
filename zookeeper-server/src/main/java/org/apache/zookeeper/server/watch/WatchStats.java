/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
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
    /**
     * Stats that have no watchers attached.
     *
     * <p>This could be used as start point to compute new stats using {@link #addMode(WatcherMode)}.
     */
    public static final WatchStats NONE;

    private static final WatchStats[] WATCH_STATS;

    static {
        WatchStats[] stats = new WatchStats[8];
        for (int i = 0; i < stats.length; i++) {
            stats[i] = new WatchStats(i);
        }
        NONE = stats[0];
        WATCH_STATS = stats;
    }

    private final int flags;

    private WatchStats(int flags) {
        this.flags = flags;
    }

    private static int mode2flag(WatcherMode mode) {
        switch (mode) {
            case STANDARD: return 1;
            case PERSISTENT: return  2;
            case PERSISTENT_RECURSIVE: return 4;
            default:
                throw new IllegalArgumentException("unknown watcher mode " + mode.name());
        }
    }

    /**
     * Compute a stats for given mode.
     *
     * @param mode watcher mode
     * @return stats that represent only given mode attached
     */
    public static WatchStats fromMode(WatcherMode mode) {
        int flags = mode2flag(mode);
        return WATCH_STATS[flags];
    }

    /**
     * Compute stats after given mode attached to node.
     *
     * @param mode watcher mode
     * @return a new stats if given mode is not attached to this node before, otherwise old stats
     */
    public WatchStats addMode(WatcherMode mode) {
        int flags = this.flags | mode2flag(mode);
        return WATCH_STATS[flags];
    }

    /**
     * Compute stats after given mode removed from node.
     *
     * @param mode watcher mode
     * @return null if given mode is the last attached mode, otherwise a new stats
     */
    public WatchStats removeMode(WatcherMode mode) {
        int negative_mask = ~mode2flag(mode);
        int flags = this.flags & negative_mask;
        if (flags == 0) {
            return null;
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
        int flags = mode2flag(mode);
        return (this.flags & flags) != 0;
    }
}
