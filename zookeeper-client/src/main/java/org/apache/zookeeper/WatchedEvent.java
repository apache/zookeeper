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

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.proto.WatcherEvent;

/**
 *  A WatchedEvent represents a change on the ZooKeeper that a Watcher
 *  is able to respond to.  The WatchedEvent includes exactly what happened,
 *  the current state of the ZooKeeper, and the path of the znode that
 *  was involved in the event.
 */
@InterfaceAudience.Public
public class WatchedEvent {
    public static final long NO_ZXID = -1L;

    private final KeeperState keeperState;
    private final EventType eventType;
    private final String path;
    private final long zxid;

    /**
     * Create a WatchedEvent with specified type, state, path and zxid
     */
    public WatchedEvent(EventType eventType, KeeperState keeperState, String path, long zxid) {
        this.keeperState = keeperState;
        this.eventType = eventType;
        this.path = path;
        this.zxid = zxid;
    }

    /**
     * Create a WatchedEvent with specified type, state and path
     */
    public WatchedEvent(EventType eventType, KeeperState keeperState, String path) {
        this(eventType, keeperState, path, NO_ZXID);
    }

    /**
     * Convert a WatcherEvent sent over the wire into a full-fledged WatchedEvent
     */
    public WatchedEvent(WatcherEvent eventMessage, long zxid) {
        keeperState = KeeperState.fromInt(eventMessage.getState());
        eventType = EventType.fromInt(eventMessage.getType());
        path = eventMessage.getPath();
        this.zxid = zxid;
    }

    public KeeperState getState() {
        return keeperState;
    }

    public EventType getType() {
        return eventType;
    }

    public String getPath() {
        return path;
    }

    /**
     * Returns the zxid of the transaction that triggered this watch if it is
     * of one of the following types:<ul>
     *   <li>{@link EventType#NodeCreated}</li>
     *   <li>{@link EventType#NodeDeleted}</li>
     *   <li>{@link EventType#NodeDataChanged}</li>
     *   <li>{@link EventType#NodeChildrenChanged}</li>
     * </ul>
     * Otherwise, returns {@value #NO_ZXID}. Note that {@value #NO_ZXID} is also
     * returned by old servers that do not support this feature.
     */
    public long getZxid() {
        return zxid;
    }

    @Override
    public String toString() {
        return "WatchedEvent state:" + keeperState + " type:" + eventType + " path:" + path + " zxid: " + zxid;
    }

    /**
     *  Convert WatchedEvent to type that can be sent over network
     */
    public WatcherEvent getWrapper() {
        return new WatcherEvent(eventType.getIntValue(), keeperState.getIntValue(), path);
    }
}
