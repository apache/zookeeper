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

package org.apache.zookeeper;

import java.util.Objects;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * Intercepts {@link Watcher#process(WatchedEvent)} to pass given {@link #path} as {@link WatchedEvent#getPath()}.
 */
@InterfaceAudience.Private
class PathWatcher implements Watcher {
    private final String path;
    private final Watcher watcher;

    public PathWatcher(String path, Watcher watcher) {
        this.path = Objects.requireNonNull(path);
        this.watcher = Objects.requireNonNull(watcher);
    }

    @Override
    public void process(WatchedEvent event) {
        if (event.getType() != Event.EventType.None) {
            event = new WatchedEvent(event.getType(), event.getState(), path);
        }
        watcher.process(event);
    }


    @Override
    public boolean equals(Object other) {
        return other instanceof PathWatcher
            && this.path.equals(((PathWatcher) other).path)
            && this.watcher.equals(((PathWatcher) other).watcher);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, watcher);
    }
}
