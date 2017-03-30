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
using System.Threading.Tasks;

namespace org.apache.zookeeper
{
    /// <summary>
    /// This is a base class that an event handler class must implement. 
    /// A ZooKeeper client will get various events from the ZooKeepr server it connects to. 
    /// An application using such a client handles these events by registering a callback object 
    /// with the client. The callback object is expected to be an instance of a class that implements 
    /// Watcher class.
    /// </summary>
    public abstract class Watcher {
        internal static readonly Task CompletedTask = Task.FromResult(1);

        /// <summary>
        /// Processes the specified event.
        /// </summary>
        /// <param name="event">The event.</param>
        /// <returns></returns>
        public abstract Task process(WatchedEvent @event);
        
        /// <summary/>
        public static class Event
        {
            /// <summary>
            /// The state of the client from the server point of view
            /// </summary>
            public enum KeeperState
            {
                /// <summary>
                /// The client is in the disconnected state - it is not connected
                /// to any server in the ensemble.
                /// </summary>
                Disconnected = 0,
                /// <summary>
                /// The client is in the connected state - it is connected
                /// to a server in the ensemble (one of the servers specified
                /// in the host connection parameter during ZooKeeper client
                /// creation).
                /// </summary>
                SyncConnected = 3,
                /// <summary>
                /// The authentication failed
                /// </summary>
                AuthFailed = 4,
                /// <summary>
                /// The client is connected to a read-only server, that is the
                /// server which is not currently connected to the majority.
                /// The only operations allowed after receiving this state is
                /// read operations.
                /// This state is generated for read-only clients only since
                /// read/write clients aren't allowed to connect to r/o servers.
                /// </summary>
                ConnectedReadOnly = 5,
                /// <summary>
                /// The serving cluster has expired this session. The ZooKeeper
                /// client connection (the session) is no longer valid. You must
                /// create a new client connection (instantiate a new ZooKeeper
                /// instance) if you with to access the ensemble.
                /// </summary>
                Expired = -112
            }
            /// <summary>
            /// Enumeration of types of events that may occur on the ZooKeeper
            /// </summary>
            public enum EventType
            {
                /// <summary/>
                None = -1,
                /// <summary>
                /// a node created
                /// </summary>
                NodeCreated = 1,
                /// <summary>
                /// a node deleted
                /// </summary>
                NodeDeleted = 2,
                /// <summary>
                /// a node data changed
                /// </summary>
                NodeDataChanged = 3,
                /// <summary>
                /// a node children changed
                /// </summary>
                NodeChildrenChanged = 4
            }
        }
    }

    internal class WatcherDelegate : Watcher {
        private readonly Func<WatchedEvent,Task> processor;
        public WatcherDelegate(Func<WatchedEvent,Task> processor) {
            this.processor = processor;
        }

        public override Task process(WatchedEvent @event) {
            return processor(@event);
        }
    }
}
