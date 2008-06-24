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

package org.apache.zookeeper;

import org.apache.zookeeper.proto.WatcherEvent;

/**
 * This interface specifies the public interface an event handler class must
 * implement. A ZooKeeper client will get various events from the ZooKeepr
 * server it connects to. An application using such a client handles these
 * events by registering a callback object with the client. The callback object
 * is expected to be an instance of a class that implements Watcher interface.
 * 
 */
public interface Watcher {

    /**
     * 
     * This interface defines the list of possible event codes
     * 
     */
    public interface Event {

        // constants for connection states
        final public static int KeeperStateChanged = 0;

        final public static int KeeperStateUnknown = -1;

        final public static int KeeperStateDisconnected = 0;

        final public static int KeeperStateNoSyncConnected = 1;

        final public static int KeeperStateSyncConnected = 3;

        public static final int KeeperStateExpired = -112;

        // constants for event types
        final public static int EventNone = -1;

        final public static int EventNodeCreated = 1;

        final public static int EventNodeDeleted = 2;

        final public static int EventNodeDataChanged = 3;

        final public static int EventNodeChildrenChanged = 4;
    }

    abstract public void process(WatcherEvent event);
}
