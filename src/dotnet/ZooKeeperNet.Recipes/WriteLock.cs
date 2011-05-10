namespace ZooKeeperNet.Recipes
{
    using System.Collections.Generic;
    using System;
    using System.Linq;
    using System.Runtime.CompilerServices;
    using System.Threading;
    using Org.Apache.Zookeeper.Data;

    public class WriteLock : ProtocolSupport
    {
        private readonly string dir;
        private readonly byte[] data = new[] { (byte)12, (byte)34 };
        private readonly Func<object> lockOperation;

        private string id;
        private ZNodeName idName;
        private string ownerId;
        private string lastChildId;

        private void OnLockAcquired()
        {
            var lockAcquired = LockAcquired;
            if (lockAcquired != null)
                lockAcquired();
        }

        public event Action LockAcquired;

        private void OnLockReleased()
        {
            var lockReleased = LockReleased;
            if (lockReleased != null)
                lockReleased();
        }

        public event Action LockReleased;

        public WriteLock(ZooKeeper zookeeper, string dir) : base(zookeeper)
        {
            this.dir = dir;
        }

        public WriteLock(ZooKeeper zookeeper, string dir, List<ACL> acl) : base(zookeeper)
        {
            this.dir = dir;
            if (acl != null) Acl = acl;
            lockOperation = LockOperation;
        }

        public string Id
        {
            get { return id; }
        }

        public bool Owner
        {
            get
            {
                return id != null && ownerId != null && id.Equals(ownerId);
            }
        }

        [MethodImpl(MethodImplOptions.Synchronized)]
        public void Unlock()
        {
            if (IsDisposed() || id == null) return;

            try
            {
                Zookeeper.Delete(id, -1);
            }
            catch (ThreadInterruptedException e)
            {
                Thread.CurrentThread.Interrupt();
            }
            catch (KeeperException.NoNodeException e)
            {
                //do nothing
            }
            catch (KeeperException e)
            {
                LOG.Warn("Caught: " + e, e);
                throw;
            }
            finally
            {
                OnLockReleased();
                id = null;
            }
        }

        private object LockOperation()
        {
            do
            {
                if (id == null)
                {
                    long sessionId = Zookeeper.SessionId;
                    string prefix = "x-" + sessionId + "-";
                    FindPrefixInChildren(prefix, Zookeeper, dir);
                    idName = new ZNodeName(id);
                }
                
                if (id == null) continue;

                List<string> names = Zookeeper.GetChildren(dir, false);
                if (names.IsEmpty())
                {
                    LOG.Warn("No children in: " + dir + " when we've just " +
                             "created one! Lets recreate it...");
                    // lets force the recreation of the id
                    id = null;
                }
                else
                {
                    // lets sort them explicitly (though they do seem to come back in order ususally :)
                    var sortedNames = new SortedSet<ZNodeName>();
                    foreach (string name in names)
                    {
                        sortedNames.Add(new ZNodeName(dir.Combine(name)));
                    }
                    ownerId = sortedNames.First().Name;
                    SortedSet<ZNodeName> lessThanMe = sortedNames.HeadSet(idName);
                    if (!lessThanMe.IsEmpty())
                    {
                        ZNodeName lastChildName = lessThanMe.Last();
                        lastChildId = lastChildName.Name;
                        if (LOG.IsDebugEnabled)
                        {
                            LOG.Debug("watching less than me node: " + lastChildId);
                        }
                        Stat stat = Zookeeper.Exists(lastChildId, new LockWatcher(this));
                        if (stat != null)
                        {
                            return false;
                        }
                            
                        LOG.Warn("Could not find the stats for less than me: " + lastChildName.Name);
                    }
                    else
                    {
                        if (Owner)
                        {
                            OnLockAcquired();
                            return true;
                        }
                    }
                }
            } while (id == null);
            return false;
        }

        private void FindPrefixInChildren(String prefix, ZooKeeper zookeeper, String dir)
        {
            List<String> names = Zookeeper.GetChildren(dir, false);
            foreach (string name in names)
            {
                if (name.StartsWith(prefix))
                {
                    id = name;
                    if (LOG.IsDebugEnabled)
                    {
                        LOG.Debug("Found id created last time: " + id);
                    }
                    break;
                }
            }
            if (id == null)
            {
                id = zookeeper.Create(dir.Combine(prefix), data, Acl, CreateMode.EphemeralSequential);

                if (LOG.IsDebugEnabled)
                {
                    LOG.Debug("Created id: " + id);
                }
            }

        }

        [MethodImpl(MethodImplOptions.Synchronized)]
        public bool Lock()
        {
            if (IsDisposed())
            {
                return false;
            }
            EnsurePathExists(dir);

            return (bool)RetryOperation(lockOperation);
        }

        public IDisposable UseLock()
        {
            return new DisposableLock(this);
        }

        private class LockWatcher : IWatcher
        {
            private readonly WriteLock writeLock;

            public LockWatcher(WriteLock writeLock)
            {
                this.writeLock = writeLock;
            }

            public void Process(WatchedEvent @event)
            {
                if (LOG.IsDebugEnabled)
                    LOG.Debug(string.Format("Watcher fired on path: {0} state: {1} type {2}", @event.Path, @event.State, @event.Type));
                try
                {
                    writeLock.Lock();
                }
                catch (Exception e)
                {
                    LOG.Warn("Failed to acquire lock: " + e, e);
                }
            }
        }

        private class DisposableLock : IDisposable
        {
            private readonly WriteLock writeLock;

            public DisposableLock(WriteLock writeLock)
            {
                this.writeLock = writeLock;
            }

            public void Dispose()
            {
                writeLock.Unlock();
            }
        }
    }
}
