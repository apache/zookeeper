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

using System;
namespace org.apache.zookeeper
{
    public abstract class Watcher
    {
        public abstract void process(WatchedEvent @event);

        public static class Event
        {
            public enum KeeperState
            {
                /** The client is in the disconnected state - it is not connected
                     * to any server in the ensemble. */
                Disconnected = 0,
                /** The client is in the connected state - it is connected
                     * to a server in the ensemble (one of the servers specified
                     * in the host connection parameter during ZooKeeper client
                     * creation). */
                SyncConnected = 3,
                /**
                     * Auth failed state
                     */
                AuthFailed = 4,
                /**
                    * The client is connected to a read-only server, that is the
                    * server which is not currently connected to the majority.
                    * The only operations allowed after receiving this state is
                    * read operations.
                    * This state is generated for read-only clients only since
                    * read/write clients aren't allowed to connect to r/o servers.
                    */
                ConnectedReadOnly = 5,
                /**
                      * SaslAuthenticated: used to notify clients that they are SASL-authenticated,
                      * so that they can perform Zookeeper actions with their SASL-authorized permissions.
                      */
                //SaslAuthenticated = 6,
                /** The serving cluster has expired this session. The ZooKeeper
                     * client connection (the session) is no longer valid. You must
                     * create a new client connection (instantiate a new ZooKeeper
                     * instance) if you with to access the ensemble. */
                Expired = -112
            }
            public enum EventType
            {
                None = -1,
                NodeCreated = 1,
                NodeDeleted = 2,
                NodeDataChanged = 3,
                NodeChildrenChanged = 4
            }
        }
    }

    public class WatcherDelegate : Watcher {
        private readonly Action<WatchedEvent> processor;
        public WatcherDelegate(Action<WatchedEvent> processor) {
            this.processor = processor;
        }

        public override void process(WatchedEvent @event) {
            processor(@event);
        }
    }
}
