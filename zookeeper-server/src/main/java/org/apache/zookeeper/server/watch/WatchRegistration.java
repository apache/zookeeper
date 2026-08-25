/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file to you under
 * the Apache License, Version 2.0 (the "License");
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

package org.apache.zookeeper.server.watch;

import java.util.Objects;

/**
 * A single Watch registration held by an {@link IWatchManager}.
 */
public final class WatchRegistration {

    private final String path;
    private final long sessionId;
    private final WatcherMode watcherMode;

    /**
     * Creates a Watch registration.
     *
     * @param path watched znode path
     * @param sessionId owning Session ID
     * @param watcherMode Watch registration mode
     */
    public WatchRegistration(String path, long sessionId, WatcherMode watcherMode) {
        this.path = path;
        this.sessionId = sessionId;
        this.watcherMode = watcherMode;
    }

    /**
     * @return watched znode path
     */
    public String getPath() {
        return path;
    }

    /**
     * @return owning Session ID
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * @return Watch registration mode
     */
    public WatcherMode getWatcherMode() {
        return watcherMode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WatchRegistration)) {
            return false;
        }
        WatchRegistration that = (WatchRegistration) other;
        return sessionId == that.sessionId
            && Objects.equals(path, that.path)
            && watcherMode == that.watcherMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, sessionId, watcherMode);
    }
}
