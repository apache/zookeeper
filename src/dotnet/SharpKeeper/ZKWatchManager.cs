namespace SharpKeeper
{
    using System;
    using System.Collections.Generic;

    public class ZKWatchManager : IClientWatchManager {
        internal readonly Dictionary<string, HashSet<Watcher>> dataWatches = new Dictionary<string, HashSet<Watcher>>();
        internal readonly Dictionary<string, HashSet<Watcher>> existWatches = new Dictionary<string, HashSet<Watcher>>();
        internal readonly Dictionary<string, HashSet<Watcher>> childWatches = new Dictionary<string, HashSet<Watcher>>();

        internal volatile Watcher defaultWatcher;
        private static Logger LOG;

        private void AddTo(HashSet<Watcher> from, HashSet<Watcher> to) {
            if (from == null) return;
            from.UnionWith(to);
        }

        public HashSet<Watcher> Materialize(KeeperState state, EventType type, String clientPath)
        {
            HashSet<Watcher> result = new HashSet<Watcher>();

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
                        HashSet<Watcher> list = existWatches.GetAndRemove(clientPath);
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