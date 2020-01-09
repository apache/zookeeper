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

import org.apache.zookeeper.ZooDefs;

/**
 * STANDARD mode is used to keep compatibility with the traditional one-time watch
 * other modes are added for addWatch api
 */
public enum WatcherMode {
    STANDARD(false, false),
    STANDARD_DATA(false, false),
    STANDARD_CHILD(false, false),
    STANDARD_EXIST(false, false),
    PERSISTENT(true, false),
    PERSISTENT_RECURSIVE(true, true)
    ;

    public static final WatcherMode DEFAULT_WATCHER_MODE = WatcherMode.STANDARD;

    public static WatcherMode fromZooDef(int mode) {
        switch (mode) {
            case ZooDefs.AddWatchModes.persistent:
                return PERSISTENT;
            case ZooDefs.AddWatchModes.persistentRecursive:
                return PERSISTENT_RECURSIVE;
            case ZooDefs.AddWatchModes.standardChild:
                return STANDARD_CHILD;
            case ZooDefs.AddWatchModes.standardData:
                return STANDARD_DATA;
            case ZooDefs.AddWatchModes.standardExist:
                return STANDARD_EXIST;
        }
        throw new IllegalArgumentException("Unsupported mode: " + mode);
    }

    private final boolean isPersistent;
    private final boolean isRecursive;

    WatcherMode(boolean isPersistent, boolean isRecursive) {
        this.isPersistent = isPersistent;
        this.isRecursive = isRecursive;
    }

    public boolean isPersistent() {
        return isPersistent;
    }

    public boolean isRecursive() {
        return isRecursive;
    }
}
