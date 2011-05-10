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
﻿namespace ZooKeeperNet
{
    using System;
    using System.Collections.Generic;
    using log4net;

    public class ZKWatchManager : IClientWatchManager 
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(ZKWatchManager));

        internal readonly Dictionary<string, HashSet<IWatcher>> dataWatches = new Dictionary<string, HashSet<IWatcher>>();
        internal readonly Dictionary<string, HashSet<IWatcher>> existWatches = new Dictionary<string, HashSet<IWatcher>>();
        internal readonly Dictionary<string, HashSet<IWatcher>> childWatches = new Dictionary<string, HashSet<IWatcher>>();

        internal volatile IWatcher defaultWatcher;

        private void AddTo(HashSet<IWatcher> from, HashSet<IWatcher> to) {
            if (from == null) return;
            to.UnionWith(from);
        }

        public HashSet<IWatcher> Materialize(KeeperState state, EventType type, string clientPath)
        {
            HashSet<IWatcher> result = new HashSet<IWatcher>();

            switch (type) {
                case EventType.None:
                    result.Add(defaultWatcher);
                    foreach (var ws in dataWatches.Values) {
                        result.UnionWith(ws);
                    }
                    foreach (var ws in existWatches.Values) {
                        result.UnionWith(ws);
                    }
                    foreach(var ws in childWatches.Values) {
                        result.UnionWith(ws);
                    }

                    // clear the watches if auto watch reset is not enabled
                    if (ClientConnection.disableAutoWatchReset &&
                        state != KeeperState.SyncConnected)
                    {
                        lock(dataWatches) {
                            dataWatches.Clear();
                        }
                        lock(existWatches) {
                            existWatches.Clear();
                        }
                        lock(childWatches) {
                            childWatches.Clear();
                        }
                    }

                    return result;
                case EventType.NodeDataChanged:
                case EventType.NodeCreated:
                    lock (dataWatches) {
                        AddTo(dataWatches.GetAndRemove(clientPath), result);
                    }
                    lock (existWatches) {
                        AddTo(existWatches.GetAndRemove(clientPath), result);
                    }
                    break;
                case EventType.NodeChildrenChanged:
                    lock (childWatches) {
                        AddTo(childWatches.GetAndRemove(clientPath), result);
                    }
                    break;
                case EventType.NodeDeleted:
                    lock (dataWatches) {
                        AddTo(dataWatches.GetAndRemove(clientPath), result);
                    }
                    // XXX This shouldn't be needed, but just in case
                    lock (existWatches) {
                        HashSet<IWatcher> list = existWatches.GetAndRemove(clientPath);
                        if (list != null) {
                            AddTo(existWatches.GetAndRemove(clientPath), result);
                            LOG.Warn("We are triggering an exists watch for delete! Shouldn't happen!");
                        }
                    }
                    lock (childWatches) {
                        AddTo(childWatches.GetAndRemove(clientPath), result);
                    }
                    break;
                default:
                    var msg = string.Format("Unhandled watch event type {0} with state {1} on path {2}", type, state, clientPath);
                    LOG.Error(msg);
                    throw new InvalidOperationException(msg);
            }

            return result;
        }
    }
}
