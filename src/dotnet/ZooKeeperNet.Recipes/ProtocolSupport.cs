namespace ZooKeeperNet.Recipes
{
    using System;
    using System.Collections.Generic;
    using System.Threading;
    using log4net;
    using Org.Apache.Zookeeper.Data;
 
    public abstract class ProtocolSupport : IDisposable
    {
        protected static readonly ILog LOG = LogManager.GetLogger(typeof(ProtocolSupport));

        private int closed;

        public ProtocolSupport(ZooKeeper zookeeper)
        {
            RetryDelay = new TimeSpan(0, 0, 0, 0, 500);
            Acl = Ids.OPEN_ACL_UNSAFE;
            RetryCount = 10;
            Zookeeper = zookeeper;
        }

        public ZooKeeper Zookeeper { get; set; }

        public TimeSpan RetryDelay { get; set; }

        public List<ACL> Acl { get; set; }

        public int RetryCount { get; set; }


        /**
         * Perform the given operation, retrying if the connection fails
         * @return object. it needs to be cast to the callee's expected 
         * return type.
         */
        protected T RetryOperation<T>(Func<T> operation)
        {
            KeeperException exception = null;
            for (int i = 0; i < RetryCount; i++)
            {
                try
                {
                    return operation();
                }
                catch (KeeperException.SessionExpiredException e)
                {
                    LOG.Warn("Session expired for: " + Zookeeper + " so reconnecting due to: " + e, e);
                    throw e;
                }
                catch (KeeperException.ConnectionLossException e)
                {
                    if (exception == null)
                        exception = e;
                    LOG.Debug("Attempt " + i + " failed with connection loss so " +
                            "attempting to reconnect: " + e, e);
                    DoRetryDelay(i);
                }
                catch (TimeoutException e)
                {
                    if (exception == null)
                        exception = KeeperException.Create(KeeperException.Code.OPERATIONTIMEOUT);
                    LOG.Debug("Attempt " + i + " failed with connection loss so " +
                            "attempting to reconnect: " + e, e);
                    DoRetryDelay(i);
                }
            }
            throw exception;
        }

        /**
         * Ensures that the given path exists with no data, the current
         * ACL and no flags
         * @param path
         */
        protected void EnsurePathExists(string path)
        {
            EnsureExists(path, null, Acl, CreateMode.Persistent);
        }

        /**
         * Ensures that the given path exists with the given data, ACL and flags
         * @param path
         * @param acl
         * @param flags
         */
        protected void EnsureExists(string path, byte[] data, List<ACL> acl, CreateMode flags)
        {
            try
            {
                RetryOperation(() =>
                {
                    Stat stat = Zookeeper.Exists(path, false);
                    if (stat != null)
                    {
                        return true;
                    }
                    Zookeeper.Create(path, data, acl, flags);
                    return true;
                });
            }
            catch (KeeperException e)
            {
                LOG.Warn("Caught: " + e, e);
            }
            catch (ThreadInterruptedException e)
            {
                LOG.Warn("Caught: " + e, e);
            }
        }

        /**
         * Returns true if this protocol has been closed
         * @return true if this protocol is closed
         */
        protected bool IsDisposed()
        {
            return Interlocked.CompareExchange(ref closed,1,1) == 1;
        }

        /**
         * Performs a retry delay if this is not the first attempt
         * @param attemptCount the number of the attempts performed so far
         */
        protected void DoRetryDelay(int attemptCount)
        {
            if (attemptCount > 0)
            {
                try
                {
                    Thread.Sleep(Convert.ToInt32(attemptCount * RetryDelay.TotalMilliseconds));
                }
                catch (ThreadInterruptedException e)
                {
                    LOG.Debug("Failed to sleep: " + e, e);
                }
            }
        }

        protected virtual void Dispose(bool isDisposing)
        {
            if (Interlocked.CompareExchange(ref closed, 1, 0) == 0)
            {
                if (isDisposing)
                    GC.SuppressFinalize(this);
            }
        }

        #region IDisposable Members

        public void Dispose()
        {
            Dispose(true);
        }

        ~ProtocolSupport()
        {
            Dispose(false);
        }

        #endregion
    }
}
