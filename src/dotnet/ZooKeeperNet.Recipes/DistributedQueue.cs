namespace ZooKeeperNet.Recipes
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using System.Threading;
    using log4net;
    using Org.Apache.Zookeeper.Data;


    public class DistributedQueue
    {
        private static readonly ILog LOG = LogManager.GetLogger(typeof(DistributedQueue));

        private readonly string dir;
        private readonly ZooKeeper zookeeper;
        private readonly List<ACL> acl = Ids.OPEN_ACL_UNSAFE;

        private const string prefix = "qn-";

        public DistributedQueue(ZooKeeper zookeeper, string dir)
        {
            this.dir = dir;
            this.zookeeper = zookeeper;
        }

        public DistributedQueue(ZooKeeper zookeeper, string dir, List<ACL> acl)
        {
            this.zookeeper = zookeeper;
            this.dir = dir;            
            if (acl != null) this.acl = acl;
        }

        private SortedDictionary<long, string> OrderedChildren(IWatcher watcher)
        {
            var orderedChildren = new SortedDictionary<long, string>();

            foreach (string childName in zookeeper.GetChildren(dir, watcher))
            {
                try
                {
                    bool matches = childName.Length > prefix.Length && childName.Substring(0, prefix.Length) == prefix;
                    if (!matches)
                    {
                        LOG.Warn("Found child node with improper name: " + childName);
                        continue;
                    }
                    string suffix = childName.Substring(prefix.Length);
                    long childId = Convert.ToInt64(suffix);
                    orderedChildren[childId] = childName;
                }
                catch (InvalidCastException e)
                {
                    LOG.Warn("Found child node with improper format : " + childName + " " + e, e);
                }
            }

            return orderedChildren;
        }

        public byte[] Peek()
        {
            try
            {
                return GetElement(false);
            }
            catch (NoSuchElementException e)
            {
                return null;
            }
        }

        /// <summary>
        /// Adds an item to the queue
        /// </summary>
        /// <param name="data">The data.</param>
        /// <returns></returns>
        public bool Enqueue(byte[] data)
        {
            for (;;)
            {
                try
                {
                    zookeeper.Create(dir + "/" + prefix, data, acl, CreateMode.PersistentSequential);
                    return true;
                }
                catch (KeeperException.NoNodeException e)
                {
                    zookeeper.Create(dir, new byte[0], acl, CreateMode.Persistent);
                }
            }
        }

        /// <summary>
        /// Removes an item from the queue.  If an item is not available, a <see cref="NoSuchElementException">NoSuchElementException</see>
        /// is thrown.
        /// </summary>
        /// <returns></returns>
        public byte[] Dequeue()
        {
            return GetElement(true);
        }

        /// <summary>
        /// Removes an item from the queue.  If an item is not available, then the method blocks until one is.
        /// </summary>
        /// <returns></returns>
        public byte[] Take()
        {
            byte[] data;
            TryTakeInternal(Int32.MaxValue, out data);
            return data;
        }

        public bool TryTake(TimeSpan timeout, out byte[] data)
        {
            var time = timeout == TimeSpan.MaxValue ? Int32.MaxValue : Convert.ToInt32(timeout.TotalMilliseconds);
            return TryTakeInternal(time, out data);
        }

        private bool TryTakeInternal(int wait, out byte[] data)
        {
            data = null;
            SortedDictionary<long, string> orderedChildren;
            while (true)
            {
                ResetChildWatcher childWatcher = new ResetChildWatcher();
                try
                {
                    orderedChildren = OrderedChildren(childWatcher);
                }
                catch (KeeperException.NoNodeException e)
                {
                    zookeeper.Create(dir, new byte[0], acl, CreateMode.Persistent);
                    continue;
                }
                if (orderedChildren.Count == 0)
                {
                    if (!childWatcher.WaitOne(wait)) return false;
                    continue;
                }

                foreach (string path in orderedChildren.Values.Select(headNode => dir.Combine(headNode)))
                {
                    try
                    {
                        data = zookeeper.GetData(path, false, null);
                        zookeeper.Delete(path, -1);
                        return true;
                    }
                    catch (KeeperException.NoNodeException e)
                    {
                        // Another client deleted the node first.
                    }
                }
            }
        }

        private byte[] GetElement(bool delete)
        {
            SortedDictionary<long, string> orderedChildren;

            while (true)
            {
                try
                {
                    orderedChildren = OrderedChildren(null);
                }
                catch (KeeperException.NoNodeException)
                {
                    throw new NoSuchElementException();
                }

                foreach (string path in orderedChildren.Values.Select(head => dir.Combine(head)))
                {
                    try
                    {
                        byte[] data = zookeeper.GetData(path, false, null);
                        if (delete) zookeeper.Delete(path, -1);
                        return data;
                    }
                    catch (KeeperException.NoNodeException)
                    {
                    }
                }
            }
        }

        private class ResetChildWatcher : IWatcher
        {
            private readonly ManualResetEvent reset;

            public ResetChildWatcher()
            {
                reset = new ManualResetEvent(false);
            }

            public void Process(WatchedEvent @event)
            {
                LOG.Debug(string.Format("Watcher fired on path: {0} state: {1} type {2}", @event.Path, @event.State, @event.Type));
                reset.Set();
            }

            public bool WaitOne(int wait)
            {
                return reset.WaitOne(wait);
            }
        }
    }

    public class NoSuchElementException : Exception
    {
    }
}
