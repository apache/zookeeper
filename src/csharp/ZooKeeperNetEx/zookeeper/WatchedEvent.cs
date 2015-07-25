/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */


using org.apache.utils;
using org.apache.zookeeper.proto;

namespace org.apache.zookeeper
{
    /// <summary>
    /// an incoming event
    /// </summary>
    public class WatchedEvent {
        private readonly Watcher.Event.KeeperState keeperState;
        private readonly Watcher.Event.EventType eventType;
        private readonly string path;

        internal WatchedEvent(Watcher.Event.EventType eventType, Watcher.Event.KeeperState keeperState, string path) {
            this.keeperState = keeperState;
            this.eventType = eventType;
            this.path = path;
        }

        /**
     * Convert a WatcherEvent sent over the wire into a full-fledged WatcherEvent
     */

        internal WatchedEvent(WatcherEvent eventMessage) {
            keeperState = EnumUtil<Watcher.Event.KeeperState>.DefinedCast(eventMessage.getState());
            eventType = EnumUtil<Watcher.Event.EventType>.DefinedCast(eventMessage.get_Type());
            path = eventMessage.getPath();
        }

        /// <summary>
        /// Gets the state of the client.
        /// </summary>
        public Watcher.Event.KeeperState getState() {
            return keeperState;
        }

        /// <summary>
        /// Gets the node type.
        /// </summary>
        public Watcher.Event.EventType get_Type() {
            return eventType;
        }

        /// <summary>
        /// Gets the ndoe path.
        /// </summary>
        public string getPath() {
            return path;
        }

        /// <summary/>
        public override string ToString() {
            return "WatchedEvent state:" + keeperState
                   + " type:" + eventType + " path:" + path;
        }

        /**
     *  Convert WatchedEvent to type that can be sent over network
     */

        internal WatcherEvent getWrapper() {
            return new WatcherEvent((int)eventType,
                (int)keeperState,
                path);
        }
    }
}
