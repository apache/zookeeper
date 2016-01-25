using System.Collections.Generic;
using System.Threading.Tasks;

using org.apache.utils;
using org.apache.zookeeper.data;

// 
// <summary>
// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to You under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
// 
// http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// </summary>
namespace org.apache.zookeeper.recipes.@lock
{
    /// <summary>
    /// A base class for protocol implementations which provides a number of higher 
    /// level helper methods for working with ZooKeeper along with retrying synchronous
    ///  operations if the connection to ZooKeeper closes such as 
    ///  <seealso cref="retryOperation(ZooKeeperOperation)"/>
    /// 
    /// </summary>
    public class ProtocolSupport 
    {
        private static readonly ILogProducer LOG = TypeLogger<ProtocolSupport>.Instance;

        internal readonly ZooKeeper zookeeper;
        private readonly ThreadSafeInt closed = new ThreadSafeInt(0);
        private const int retryDelayInMs = 500;
        private const int retryCount = 10;
        internal List<ACL> m_acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

        internal  ProtocolSupport(ZooKeeper zookeeper) 
        {
            this.zookeeper = zookeeper;
        }

        /// <summary>
        /// Closes this strategy and releases any ZooKeeper resources; but keeps the
        ///  ZooKeeper instance open
        /// </summary>
        public void close() 
        {
            if (closed.TrySetValue(0,1)) {
                doClose();
            }
        }

        /// <summary>
        /// return zookeeper client instance </summary>
        /// <returns> zookeeper client instance </returns>
        public ZooKeeper getZookeeper() {
            return zookeeper;
        }

        /// <summary>
        /// return the acl its using </summary>
        /// <returns> the acl. </returns>
        public List<ACL> Acl 
        {
            get 
            {
                return m_acl;
            }
        }

        /// <summary>
        /// Allow derived classes to perform 
        /// some custom closing operations to release resources
        /// </summary>
        protected virtual void doClose() 
        {
        }


        /// <summary>
        /// Perform the given operation, retrying if the connection fails </summary>
        /// <returns> object. it needs to be cast to the callee's expected 
        /// return type. </returns>
        protected async Task<bool> retryOperation(ZooKeeperOperation operation) 
        {
            KeeperException exception = null;
            for (int i = 0; i < retryCount; i++) 
            {
                try 
                {
                    return await operation.execute().ConfigureAwait(false);
                }
                catch (KeeperException.SessionExpiredException e) 
                {
                    LOG.warn("Session expired for: " + zookeeper + " so reconnecting due to: " + e, e);
                    throw;
                }
                catch (KeeperException.ConnectionLossException e) 
                {
                    if (exception == null) 
                    {
                        exception = e;
                    }
                    LOG.debug("Attempt " + i + " failed with connection loss so " + "attempting to reconnect: " + e, e);
                }
                await retryDelay(i).ConfigureAwait(false);
            }
            throw exception;
        }

        /// <summary>
        /// Ensures that the given path exists with no data, the current
        /// ACL and no flags </summary>
        /// <param name="path"> </param>
        protected Task ensurePathExists(string path) 
        {
            return ensureExists(path, null, m_acl, CreateMode.PERSISTENT);
        }

        /// <summary>
        /// Ensures that the given path exists with the given data, ACL and flags </summary>
        /// <param name="path"> </param>
        /// <param name="data"></param>
        /// <param name="acl"> </param>
        /// <param name="flags"> </param>
        private async Task ensureExists(string path, byte[] data, List<ACL> acl, CreateMode flags) {
            try 
			{
                await retryOperation(new EnsureExists(zookeeper, path, data, acl, flags)).ConfigureAwait(false);
            }
            catch (KeeperException e) 
            {
                LOG.warn("Caught: " + e, e);
            }
        }

        private sealed class EnsureExists : ZooKeeperOperation
        {
            private readonly ZooKeeper zookeeper;

            private readonly string path;
            private readonly byte[] data;
            private readonly List<ACL> acl;
            private readonly CreateMode flags;

            public EnsureExists(ZooKeeper zookeeper, string path, byte[] data, List<ACL> acl, CreateMode flags) 
            {
                this.zookeeper = zookeeper;
                this.path = path;
                this.data = data;
                this.acl = acl;
                this.flags = flags;
            }

            public async Task<bool> execute() 
            {
                Stat stat = await zookeeper.existsAsync(path).ConfigureAwait(false);
                if (stat != null) 
                {
                    return true;
                }
                await zookeeper.createAsync(path, data, acl, flags).ConfigureAwait(false);
                return true;
            }
        }

        /// <summary>
        /// Returns true if this protocol has been closed </summary>
        /// <returns> true if this protocol is closed </returns>
        protected bool Closed 
        {
            get { return closed.Value == 1; }
        }

        /// <summary>
        /// Performs a retry delay if this is not the first attempt </summary>
        /// <param name="attemptCount"> the number of the attempts performed so far </param>
        private static async Task retryDelay(int attemptCount) 
        {
            if (attemptCount > 0) 
            {
                await TaskEx.Delay(attemptCount*retryDelayInMs).ConfigureAwait(false);
            }
        }
    }
}