using System;
using System.Collections.Generic;
using System.Threading;

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
		private static readonly TraceLogger LOG = TraceLogger.GetLogger(typeof(DistributedQueue));

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
		private SortedDictionary<long, string> getOrderedChildren(Watcher watcher)
		{
			SortedDictionary<long, string> orderedChildren = new SortedDictionary<long, string>();

		    IList<string> childNames = zookeeper.getChildren(dir, watcher);
            
			foreach (string childName in childNames)
			{
				try
				{
					//Check format
					if (!childName.StartsWith(prefix))
					{
						LOG.warn("Found child node with improper name: " + childName);
						continue;
					}
					string suffix = childName.Substring(prefix.Length);
					long childId = Convert.ToInt64(suffix);
					orderedChildren[childId] = childName;
				}
				catch (System.FormatException e)
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
		/// <exception cref="ThreadInterruptedException"> </exception>
		public byte[] element()
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
                    orderedChildren = getOrderedChildren(null);
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
							return zookeeper.getData(dir + "/" + headNode, false, null);
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
		/// <exception cref="ThreadInterruptedException"> </exception>
		public byte[] remove()
		{
		    // Same as for element.  Should refactor this.
			while (true)
			{
			    SortedDictionary<long, string> orderedChildren;
			    try
				{
					orderedChildren = getOrderedChildren(null);
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
						byte[] data = zookeeper.getData(path, false, null);
						zookeeper.delete(path, -1);
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
		    private readonly ManualResetEventSlim latch;

			public LatchChildWatcher()
			{
                latch = new ManualResetEventSlim(false);
			}

			public override void process(WatchedEvent @event)
			{
				LOG.debug("Watcher fired on path: " + @event.getPath() + " state: " + @event.getState() + " type " + @event.get_Type());
				latch.Set();
			}

			public void @await()
			{
				latch.Wait();
			}
		}

		/// <summary>
		/// Removes the head of the queue and returns it, blocks until it succeeds. </summary>
		/// <returns> The former head of the queue </returns>
		/// <exception cref="InvalidOperationException"> </exception>
		/// <exception cref="KeeperException"> </exception>
		/// <exception cref="ThreadInterruptedException"> </exception>
		public byte[] take()
		{
		    // Same as for element.  Should refactor this.
			while (true)
			{
				LatchChildWatcher childWatcher = new LatchChildWatcher();
			    SortedDictionary<long, string> orderedChildren;
			    try
				{
					orderedChildren = getOrderedChildren(childWatcher);
				}
				catch (KeeperException.NoNodeException)
				{
					zookeeper.create(dir, new byte[0], acl, CreateMode.PERSISTENT);
					continue;
				}
				if (orderedChildren.Count == 0)
				{
					childWatcher.@await();
					continue;
				}

				foreach (string headNode in orderedChildren.Values)
				{
					string path = dir + "/" + headNode;
					try
					{
						byte[] data = zookeeper.getData(path, false, null);
						zookeeper.delete(path, -1);
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
		public bool offer(byte[] data)
		{
			for (;;)
			{
				try
				{
					zookeeper.create(dir + "/" + prefix, data, acl, CreateMode.PERSISTENT_SEQUENTIAL);
					return true;
				}
				catch (KeeperException.NoNodeException)
				{
					zookeeper.create(dir, new byte[0], acl, CreateMode.PERSISTENT);
				}
			}

		}

		/// <summary>
		/// Returns the data at the first element of the queue, or null if the queue is empty. </summary>
		/// <returns> data at the first element of the queue, or null. </returns>
		/// <exception cref="KeeperException"> </exception>
		/// <exception cref="ThreadInterruptedException"> </exception>
		public byte[] peek()
		{
			try
			{
				return element();
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
		/// <exception cref="ThreadInterruptedException"> </exception>
		public byte[] poll()
		{
			try
			{
				return remove();
			}
			catch (InvalidOperationException)
			{
				return null;
			}
		}



	}

}