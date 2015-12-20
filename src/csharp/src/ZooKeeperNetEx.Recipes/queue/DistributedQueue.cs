using System;
using System.Collections.Generic;

using System.Threading.Tasks;
using org.apache.zookeeper.data;
using org.apache.utils;
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

namespace org.apache.zookeeper.recipes.queue
{
	/// 
	/// <summary>
	/// A <a href="package.html">protocol to implement a distributed queue</a>.
	/// </summary>

	public sealed class DistributedQueue
	{
		private static readonly ILogProducer LOG = TypeLogger<DistributedQueue>.Instance;

		private readonly string dir;

		private readonly ZooKeeper zookeeper;
		private readonly List<ACL> acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;

	    private const string prefix = "qn-";


	    public DistributedQueue(ZooKeeper zookeeper, string dir, List<ACL> acl)
		{
			this.dir = dir;

			if (acl != null)
			{
				this.acl = acl;
			}
			this.zookeeper = zookeeper;

		}

		/// <summary>
		/// Returns a Map of the children, ordered by id. </summary>
		/// <param name="watcher"> optional watcher on getChildren() operation. </param>
		/// <returns> map from id to child name for all children </returns>
		private async Task<SortedDictionary<long, string>> getOrderedChildren(Watcher watcher)
		{
			SortedDictionary<long, string> orderedChildren = new SortedDictionary<long, string>();

            List<string> childNames = (await zookeeper.getChildrenAsync(dir, watcher).ConfigureAwait(false)).Children;
            
			foreach (string childName in childNames)
			{
				try
				{
					//Check format
				    if (!childName.StartsWith(prefix, StringComparison.Ordinal))
                    {
				        LOG.warn("Found child node with improper name: " + childName);
				        continue;
				    }
				    string suffix = childName.Substring(prefix.Length);
				    long childId = long.Parse(suffix);
					orderedChildren[childId] = childName;
				}
				catch (FormatException e)
				{
					LOG.warn("Found child node with improper format : " + childName + " " + e,e);
				}
			}

			return orderedChildren;
		}

		/// <summary>
		/// Return the head of the queue without modifying the queue. </summary>
		/// <returns> the data at the head of the queue. </returns>
		/// <exception cref="InvalidOperationException"> </exception>
		/// <exception cref="KeeperException"> </exception>
		public async Task<byte[]> element()
		{
		    // element, take, and remove follow the same pattern.
			// We want to return the child node with the smallest sequence number.
			// Since other clients are remove()ing and take()ing nodes concurrently, 
			// the child with the smallest sequence number in orderedChildren might be gone by the time we check.
			// We don't call getChildren again until we have tried the rest of the nodes in sequence order.
			while (true)
			{
			    SortedDictionary<long, string> orderedChildren;
			    try
				{
                    orderedChildren = await getOrderedChildren(null).ConfigureAwait(false);
				}
				catch (KeeperException.NoNodeException)
				{
					throw new InvalidOperationException();
				}
				if (orderedChildren.Count == 0)
				{
					throw new InvalidOperationException();
				}

				foreach (string headNode in orderedChildren.Values)
				{
					if (headNode != null)
					{
						try
						{
                            return (await zookeeper.getDataAsync(dir + "/" + headNode).ConfigureAwait(false)).Data;
						}
						catch (KeeperException.NoNodeException)
						{
							//Another client removed the node first, try next
						}
					}
				}

			}
		}


		/// <summary>
		/// Attempts to remove the head of the queue and return it. </summary>
		/// <returns> The former head of the queue </returns>
		/// <exception cref="InvalidOperationException"> </exception>
		/// <exception cref="KeeperException"> </exception>
		public async Task<byte[]> remove()
		{
		    // Same as for element.  Should refactor this.
			while (true)
			{
			    SortedDictionary<long, string> orderedChildren;
			    try
				{
                    orderedChildren = await getOrderedChildren(null).ConfigureAwait(false);
				}
				catch (KeeperException.NoNodeException)
				{
					throw new InvalidOperationException();
				}
				if (orderedChildren.Count == 0)
				{
					throw new InvalidOperationException();
				}

				foreach (string headNode in orderedChildren.Values)
				{
					string path = dir + "/" + headNode;
					try
					{
                        byte[] data = (await zookeeper.getDataAsync(path).ConfigureAwait(false)).Data;
                        await zookeeper.deleteAsync(path).ConfigureAwait(false);
						return data;
					}
					catch (KeeperException.NoNodeException)
					{
						// Another client deleted the node first.
					}
				}

			}
		}

		private sealed class LatchChildWatcher : Watcher
		{
		    private readonly AsyncManualResetEvent latch;

			public LatchChildWatcher()
			{
                latch = new AsyncManualResetEvent();
			}

			public override Task process(WatchedEvent @event)
			{
				LOG.debug("Watcher fired on path: " + @event.getPath() + " state: " + @event.getState() + " type " + @event.get_Type());
				latch.Set();
			    return CompletedTask;
			}

			public Task getTask()
			{
				return latch.WaitAsync();
			}
		}

		/// <summary>
		/// Removes the head of the queue and returns it, blocks until it succeeds. </summary>
		/// <returns> The former head of the queue </returns>
		/// <exception cref="InvalidOperationException"> </exception>
		/// <exception cref="KeeperException"> </exception>
		public async Task<byte[]> take()
		{
		    // Same as for element.  Should refactor this.
			while (true)
			{
				LatchChildWatcher childWatcher = new LatchChildWatcher();
			    SortedDictionary<long, string> orderedChildren = null;
			    bool isNoNode = false;
			    try
				{
                    orderedChildren = await getOrderedChildren(childWatcher).ConfigureAwait(false);
				}
				catch (KeeperException.NoNodeException) {
				    isNoNode = true;
				}
			    if (isNoNode) {
                    await zookeeper.createAsync(dir, new byte[0], acl, CreateMode.PERSISTENT).ConfigureAwait(false);
                    continue;
			    }
				if (orderedChildren.Count == 0)
				{
                    await childWatcher.getTask().ConfigureAwait(false);
					continue;
				}

				foreach (string headNode in orderedChildren.Values)
				{
					string path = dir + "/" + headNode;
					try
					{
						byte[] data = (await zookeeper.getDataAsync(path).ConfigureAwait(false)).Data;
                        await zookeeper.deleteAsync(path).ConfigureAwait(false);
						return data;
					}
					catch (KeeperException.NoNodeException)
					{
						// Another client deleted the node first.
					}
				}
			}
		}

		/// <summary>
		/// Inserts data into queue. </summary>
		/// <param name="data"> </param>
		/// <returns> true if data was successfully added </returns>
		public async Task<bool> offer(byte[] data)
		{
			for (;;)
            {
				try
				{
					await zookeeper.createAsync(dir + "/" + prefix, data, acl, CreateMode.PERSISTENT_SEQUENTIAL).ConfigureAwait(false);
					return true;
				}
				catch (KeeperException.NoNodeException) 
                {
				
				}
			    await zookeeper.createAsync(dir, new byte[0], acl, CreateMode.PERSISTENT).ConfigureAwait(false);
			}

		}

		/// <summary>
		/// Returns the data at the first element of the queue, or null if the queue is empty. </summary>
		/// <returns> data at the first element of the queue, or null. </returns>
		/// <exception cref="KeeperException"> </exception>
		public async Task<byte[]> peek()
		{
			try
			{
                return await element().ConfigureAwait(false);
			}
			catch (InvalidOperationException)
			{
				return null;
			}
		}


		/// <summary>
		/// Attempts to remove the head of the queue and return it. Returns null if the queue is empty. </summary>
		/// <returns> Head of the queue or null. </returns>
		/// <exception cref="KeeperException"> </exception>
		public async Task<byte[]> poll()
		{
			try
			{
                return await remove().ConfigureAwait(false);
			}
			catch (InvalidOperationException)
			{
				return null;
			}
		}
	}
}